"""
微信聊天分析接口：本地 OCR 文本（推荐）或上传截图（兼容）
+ 火山知识库检索 + 火山大模型生成（默认）
+ 腾讯云 ADP 智能体（可选回退）
"""

from __future__ import annotations

import asyncio
from collections import deque
import datetime
import logging
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
from typing import Optional, Tuple

from dotenv import load_dotenv
from fastapi import Depends, FastAPI, File, Form, HTTPException, status, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, HTMLResponse
from fastapi.security import HTTPBasic, HTTPBasicCredentials, HTTPAuthorizationCredentials, HTTPBearer
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from adp_client import load_adp_config_from_env, run_adp_chat
from volc_knowledge_client import run_volc_knowledge_chat
import secrets

try:
    import database
    from profile_extractor import async_extract_profile, check_and_trigger_profile_update
except ImportError:
    pass

BASE_DIR = Path(__file__).resolve().parent
# 先加载示例中的默认值，再由 .env 覆盖（与本地仅配置 .env.example 时的行为一致）
load_dotenv(BASE_DIR / ".env.example", override=False)
load_dotenv(BASE_DIR / ".env", override=True)
UPLOAD_DIR = BASE_DIR / "uploads"
UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
LOCAL_TZ = datetime.timezone(datetime.timedelta(hours=8))
APP_STARTED_AT = datetime.datetime.now(LOCAL_TZ)
APP_STARTED_MONOTONIC = time.monotonic()
LOG_FORMAT = "%(asctime)s %(levelname)s [%(name)s] %(message)s"
LOG_DATE_FORMAT = "%Y-%m-%d %H:%M:%S %z"


def _configure_logging_timestamps() -> None:
    formatter = logging.Formatter(LOG_FORMAT, LOG_DATE_FORMAT)
    logging.basicConfig(level=logging.INFO, format=LOG_FORMAT, datefmt=LOG_DATE_FORMAT)
    for logger_name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        logger = logging.getLogger(logger_name)
        for handler in logger.handlers:
            handler.setFormatter(formatter)


_configure_logging_timestamps()
logger = logging.getLogger("fastapi-admin")

app = FastAPI(title="微信聊天分析", version="0.3.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Initialize DB on startup
try:
    database.init_db()
except Exception:
    pass

app.mount("/static", StaticFiles(directory="static"), name="static")

security = HTTPBasic()
agent_security = HTTPBearer(auto_error=False)

def verify_admin(credentials: HTTPBasicCredentials = Depends(security)):
    admin_user = os.environ.get("ADMIN_USER", "admin").strip()
    admin_pass = os.environ.get("ADMIN_PASSWORD", "admin123").strip()
    
    is_user_ok = secrets.compare_digest(credentials.username, admin_user)
    is_pass_ok = secrets.compare_digest(credentials.password, admin_pass)
    
    if not (is_user_ok and is_pass_ok):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect username or password",
            headers={"WWW-Authenticate": "CustomBasic"},
        )
    return credentials.username


def verify_agent(credentials: HTTPAuthorizationCredentials | None = Depends(agent_security)):
    if credentials is None or not credentials.credentials:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="请先登录 Agent 账号",
            headers={"WWW-Authenticate": "Bearer"},
        )

    agent = database.get_agent_by_token(credentials.credentials)
    if not agent:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Agent 登录已失效或账号已禁用",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return agent


class AgentLoginRequest(BaseModel):
    username: str
    password: str
    device_id: str = ""


class AdminAgentCreateRequest(BaseModel):
    username: str
    password: str
    display_name: str = ""
    note: str = ""


class AdminAgentUpdateRequest(BaseModel):
    display_name: str
    status: str = "active"
    note: str = ""


class AdminAgentPasswordRequest(BaseModel):
    password: str


def get_current_time_str() -> str:
    now = datetime.datetime.now(LOCAL_TZ)
    weekdays = ["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"]
    weekday_str = weekdays[now.weekday()]
    return now.strftime("%Y-%m-%d %H:%M") + f"（{weekday_str}）"


HANDOFF_DIRECT_RE = re.compile(
    r"(帮我.{0,8}(安排|规划|定制|报价)|"
    r"(出|做|整理|安排|定制).{0,8}(方案|行程|报价)|"
    r"(方案|行程|报价).{0,8}(出|做|整理|安排|定制)|"
    r"报个价|报价|核价|价格|费用|多少钱|"
    r"下单|预订|订一下|定一下|"
    r"就这个|按这个|可以安排|安排吧|确定了|定了)"
)
HANDOFF_ACK_TEXTS = {"可以", "可以的", "好的", "好", "行", "没问题", "嗯", "嗯嗯", "ok", "OK"}
HANDOFF_CONTEXT_RE = re.compile(
    r"(按这个安排|就按|安排来|出方案|方案和报价|报价方案|"
    r"整理.{0,8}(方案|行程|报价)|"
    r"行程细节.{0,8}落实|"
    r"帮您.{0,12}(落实|安排|整理)|"
    r"(酒店|用车|租车|咖啡厅).{0,12}(推荐|安排|落实))"
)


def _should_request_handoff(latest_text: str, session_id: str) -> bool:
    """用确定性规则兜底识别客户已经同意推进方案/报价。"""
    text = re.sub(r"\s+", "", (latest_text or "").strip())
    if not text:
        return False
    if HANDOFF_DIRECT_RE.search(text):
        return True

    if text not in HANDOFF_ACK_TEXTS:
        return False

    try:
        recent_messages = database.get_session_messages(session_id)[-8:]
    except Exception:
        return False

    recent_context = "\n".join(str(msg.get("content") or "") for msg in recent_messages)
    return bool(HANDOFF_CONTEXT_RE.search(recent_context))


def _handoff_reply_text() -> str:
    return "好的，我这就给您整理具体的行程方案和报价～"


def _format_duration(seconds: int) -> str:
    days, rem = divmod(max(seconds, 0), 86400)
    hours, rem = divmod(rem, 3600)
    minutes, seconds = divmod(rem, 60)
    parts = []
    if days:
        parts.append(f"{days}天")
    if hours or parts:
        parts.append(f"{hours}小时")
    if minutes or parts:
        parts.append(f"{minutes}分钟")
    parts.append(f"{seconds}秒")
    return "".join(parts)


def _read_recent_log_lines(log_path: Path, limit: int) -> list[str]:
    if not log_path.exists() or not log_path.is_file():
        return []

    safe_limit = max(1, min(limit, 500))
    lines: deque[str] = deque(maxlen=safe_limit)
    try:
        with log_path.open("r", encoding="utf-8", errors="replace") as f:
            for line in f:
                lines.append(line.rstrip("\n"))
    except OSError:
        return []
    return list(lines)


def _build_restart_script(current_pid: int, log_path: Path, pid_path: Path) -> str:
    uvicorn_path = BASE_DIR / "venv" / "bin" / "uvicorn"
    if uvicorn_path.exists():
        uvicorn_cmd = shlex.quote(str(uvicorn_path))
    else:
        uvicorn_cmd = f"{shlex.quote(sys.executable)} -m uvicorn"

    base_dir = shlex.quote(str(BASE_DIR))
    log_file = shlex.quote(str(log_path))
    pid_file = shlex.quote(str(pid_path))
    return f"""
set -e
cd {base_dir}
echo "$(date '+%Y-%m-%d %H:%M:%S %z') INFO [admin.restart] restart requested; old_pid={current_pid}" >> {log_file}
sleep 1
kill -TERM {current_pid} 2>/dev/null || true
for i in $(seq 1 20); do
  if kill -0 {current_pid} 2>/dev/null; then
    sleep 0.5
  else
    break
  fi
done
if kill -0 {current_pid} 2>/dev/null; then
  echo "$(date '+%Y-%m-%d %H:%M:%S %z') WARNING [admin.restart] old process did not exit after timeout; forcing stop" >> {log_file}
  kill -KILL {current_pid} 2>/dev/null || true
  sleep 1
fi
nohup {uvicorn_cmd} main:app --host 0.0.0.0 --port 8000 >> {log_file} 2>&1 &
echo $! > {pid_file}
echo "$(date '+%Y-%m-%d %H:%M:%S %z') INFO [admin.restart] restarted; new_pid=$(cat {pid_file})" >> {log_file}
"""

def _build_user_prompt(contact_name: str, current_status: str = "auto") -> Tuple[str, str]:
    """未提供 ocr_text 时：仅截图场景，服务端不做 OCR，用占位描述发给智能体。"""
    name = (contact_name or "").strip() or "客户"
    current_time = get_current_time_str()
    pure_text = "[用户上传了一张聊天截图]"
    
    receptionist_rule = ""
    if current_status == "handoff_requested":
        receptionist_rule = "【当前状态：正在为客户制作方案】你之前已告知客户要去整理行程方案和报价了。对于客户的新消息，请注意：如果客户询问普通旅行问题，可正常解答；如果客户催促方案进度，必须以第一人称安抚（例如：‘我正在快马加鞭为您核算报价和行程呢，请您稍等片刻哦～’），绝对不要自己编造具体方案或报价数字。\n"

    wrapped_text = (
        f"（系统提示：当前实际时间是 {current_time}。注意：仅在第一轮对话或主动打招呼时才进行时间问候，在后续连续的对话中直接回答用户的问题，绝对不要重复问候！你在问候客户时必须以此为准判断今天是星期几，绝对不要参考下方文本中可能出现的旧时间标签（如“周一16:40”等是微信界面上旧消息的时间戳，不代表当前时间）。请以此作为判断今天、明天、下周等相对时间的基准。\n"
        "【HANDOFF 规则】在以下两种情况下，才可在回复末尾加上 [HANDOFF] 标记：1) 客户在本轮消息中明确表达了要你出方案/报价/下单的意图（例如‘帮我安排一下’、‘出个方案吧’、‘可以报价了’）；2) 虽然客户没有直接说‘出方案’，但从对话上下文判断客户意愿已经非常强烈，目的地、人数、时间等关键信息都已明确，且客户表现出明显的决策倾向（例如‘就这个行程吧’、‘五一就走’、‘两个人确定了’）。如果客户只是打招呼、闲聊、问问题、或者你自己还在提问收集基本信息，**绝对不要**加 [HANDOFF]。触发 [HANDOFF] 时，你的回复**必须是一句确认性的第一人称过渡话术**（例如‘好的，我这就给您整理具体的行程方案和报价～’），**绝对不能是提问句**，也绝对不能说‘转交给人工’或‘转交给定制师’，因为你本身就是这位专属的定制师小鹿。）\n\n"
        f"{receptionist_rule}"
        f"当前微信会话联系人是「{name}」。用户上传了一张聊天截图，但服务端未能拿到具体聊天文字。\n"
        "请作为旅游定制顾问，直接给出一句简短的回复建议。"
    )
    return pure_text, wrapped_text


def _build_user_prompt_from_ocr(contact_name: str, ocr_text: str, current_status: str = "auto") -> Tuple[str, str]:
    """客户端已完成本地 OCR 时，仅把最新一条客户消息发给智能体。"""
    name = (contact_name or "").strip() or "客户"
    text = (ocr_text or "").strip()
    current_time = get_current_time_str()
    pure_text = text
    
    receptionist_rule = ""
    if current_status == "handoff_requested":
        receptionist_rule = "【当前状态：正在为客户制作方案】你之前已告知客户要去整理行程方案和报价了。对于客户的新消息，请注意：如果客户询问普通旅行问题，可正常解答；如果客户催促方案进度，必须以第一人称安抚（例如：‘我正在快马加鞭为您核算报价和行程呢，请您稍等片刻哦～’），绝对不要自己编造具体方案或报价数字。\n"

    wrapped_text = (
        f"（系统提示：当前实际时间是 {current_time}。注意：仅在第一轮对话或主动打招呼时才进行时间问候，在后续连续的对话中直接回答用户的问题，绝对不要重复问候！你在问候客户时必须以此为准判断今天是星期几，绝对不要参考下方 OCR 文本中可能出现的旧时间标签（如“周一16:40”等是微信界面上旧消息的时间戳，不代表当前时间）。如果在聊天中客户提到诸如“下月”、“明天”等相对时间，也请以此为基准推算。\n"
        "【HANDOFF 规则】在以下两种情况下，才可在回复末尾加上 [HANDOFF] 标记：1) 客户在本轮消息中明确表达了要你出方案/报价/下单的意图（例如‘帮我安排一下’、‘出个方案吧’、‘可以报价了’）；2) 虽然客户没有直接说‘出方案’，但从对话上下文判断客户意愿已经非常强烈，目的地、人数、时间等关键信息都已明确，且客户表现出明显的决策倾向（例如‘就这个行程吧’、‘五一就走’、‘两个人确定了’）。如果客户只是打招呼、闲聊、问问题、或者你自己还在提问收集基本信息，**绝对不要**加 [HANDOFF]。触发 [HANDOFF] 时，你的回复**必须是一句确认性的第一人称过渡话术**（例如‘好的，我这就给您整理具体的行程方案和报价～’），**绝对不能是提问句**，也绝对不能说‘转交给人工’或‘转交给定制师’，因为你本身就是这位专属的定制师小鹿。）\n\n"
        f"{receptionist_rule}"
        f"代聊微信联系人「{name}」。对方刚刚发来的最新消息：\n\n{text}"
    )
    return pure_text, wrapped_text


def _provider_from_env() -> str:
    return (os.environ.get("CHAT_PROVIDER", "volc_knowledge").strip() or "volc_knowledge").lower()


@app.post("/agent/login")
@app.post("/api/agent/login")
async def api_agent_login(payload: AgentLoginRequest):
    agent = database.authenticate_agent(payload.username, payload.password, payload.device_id)
    if not agent:
        return JSONResponse(
            status_code=401,
            content={"ok": False, "error": "用户名或密码错误，或账号已被禁用"},
        )
    token = agent.pop("access_token")
    return {"ok": True, "access_token": token, "agent": agent}


@app.post("/api/wechat/chat")
@app.post("/wechat/chat")
async def wechat_chat(
    session_id: str = Form(...),
    contact_name: str = Form(...),
    ocr_text: Optional[str] = Form(None),
    image: Optional[UploadFile] = File(default=None),
    is_human_reply: bool = Form(False),
    agent: dict = Depends(verify_agent),
):
    """接收本地 OCR 文本（推荐）或截图（兼容），返回 reply_text。

    - 仅传 ocr_text：Agent IME 等客户端本地 OCR，不上传图片（推荐）。
    - 仅传 image：兼容旧版；服务端保存图片，智能体使用占位描述（不做 OCR）。
    - 若两者同时提供：按接口说明优先使用 ocr_text。
    - is_human_reply：标识此条消息是否为真人客服手动发出的回复。
    """
    ocr_ok = bool((ocr_text or "").strip())
    image_bytes: bytes | None = None
    if image is not None:
        image_bytes = await image.read()
    # 空文件视为未上传，避免 multipart 带空 image 字段误判
    has_image = bool(image_bytes)

    if not ocr_ok and not has_image:
        return JSONResponse(
            status_code=400,
            content={
                "ok": False,
                "error": "请提供 ocr_text（本地 OCR）或上传非空 image",
                "messages": [],
                "reply_text": "",
            },
        )

    if has_image:
        assert image_bytes is not None
        safe_name = (image.filename or "upload.bin").replace("/", "_")[:200]
        dest = UPLOAD_DIR / safe_name
        dest.write_bytes(image_bytes)

    agent_id = int(agent["id"])
    raw_session_id = session_id
    raw_contact_name = contact_name
    contact_name = database.canonicalize_contact_name(contact_name)
    try:
        session_id = database.resolve_session_id(session_id, contact_name, agent_id=agent_id)
        if session_id != raw_session_id:
            logger.info(
                "复用规范化联系人已有会话: agent_id=%s raw_session_id=%s resolved_session_id=%s raw_contact=%s canonical_contact=%s",
                agent_id,
                raw_session_id,
                session_id,
                raw_contact_name,
                contact_name,
            )
    except Exception as e:
        logger.warning("联系人会话归一化失败，沿用客户端 session_id: session_id=%s error=%s", raw_session_id, e)
        session_id = raw_session_id
        
    try:
        current_status = database.get_session_status(session_id)
    except Exception:
        current_status = "auto"
        
    if is_human_reply:
        # 真人客服发出了回复
        pure_user_text = (ocr_text or "").strip()
        if pure_user_text:
            try:
                # 记录真人的回复，role为assistant，表示我方发出的消息
                database.save_message(session_id, contact_name, "assistant", pure_user_text, agent_id=agent_id)
                logger.info(
                    "记录真人回复，不自动切换接管状态: agent_id=%s session_id=%s contact=%s status=%s text=%s",
                    agent_id,
                    session_id,
                    contact_name,
                    current_status,
                    pure_user_text[:80],
                )
            except Exception:
                pass
        
        return {
            "ok": True,
            "recorded": True,
            "current_status": current_status,
            "messages": [{"role": "assistant", "text": ""}],
            "reply_text": "",
        }

    if current_status in {"human_takeover", "muted"}:
        # 人工接管或后台静音期间，AI 完全静音，不生成回复。
        pure_user_text = (ocr_text or "").strip()
        if pure_user_text:
            try:
                database.save_message(session_id, contact_name, "user", pure_user_text, agent_id=agent_id)
            except Exception as e:
                logger.warning(
                    "静音期间保存客户消息失败: session_id=%s contact=%s status=%s error=%s",
                    session_id,
                    contact_name,
                    current_status,
                    e,
                )
        logger.info(
            "会话处于静音状态，跳过 AI 回复: session_id=%s contact=%s status=%s text=%s",
            session_id,
            contact_name,
            current_status,
            pure_user_text[:80],
        )
        return {
            "ok": True,
            "silenced": True,
            "reason": current_status,
            "current_status": current_status,
            "messages": [{"role": "assistant", "text": ""}],
            "reply_text": "",
        }

    # 优先使用客户端本地 OCR 全文（与接口说明「构造 user 文本」一致）
    if ocr_ok:
        pure_user_text, wrapped_user_text = _build_user_prompt_from_ocr(contact_name, ocr_text or "", current_status)
    else:
        pure_user_text, wrapped_user_text = _build_user_prompt(contact_name, current_status)

    if _should_request_handoff(pure_user_text, session_id):
        reply_text = _handoff_reply_text()
        try:
            database.save_message(session_id, contact_name, "user", pure_user_text, agent_id=agent_id)
            database.save_message(session_id, contact_name, "assistant", reply_text, agent_id=agent_id)
            database.update_session_status(session_id, "handoff_requested", agent_id=agent_id)
            asyncio.create_task(check_and_trigger_profile_update(session_id))
        except Exception as e:
            logger.warning(
                "确定性接管触发后保存状态失败: agent_id=%s session_id=%s contact=%s error=%s",
                agent_id,
                session_id,
                contact_name,
                e,
            )
        return {
            "ok": True,
            "agent_id": agent_id,
            "agent_display_name": agent.get("display_name") or agent.get("username"),
            "session_id": session_id,
            "current_status": "handoff_requested",
            "handoff_requested": True,
            "messages": [
                {"role": "user", "text": pure_user_text},
                {"role": "assistant", "text": reply_text},
            ],
            "reply_text": reply_text,
        }

    provider = _provider_from_env()
    try:
        if provider == "adp":
            sid, skey, region, app_key = load_adp_config_from_env()
            reply_text = await run_adp_chat(
                secret_id=sid,
                secret_key=skey,
                region=region,
                bot_app_key=app_key,
                session_id=session_id,
                user_text=wrapped_user_text,
            )
            messages = [
                {"role": "user", "text": pure_user_text},
                {"role": "assistant", "text": reply_text},
            ]
        else:
            reply_text, debug_payload = await asyncio.to_thread(
                run_volc_knowledge_chat,
                pure_user_text=pure_user_text,
                wrapped_user_text=wrapped_user_text,
                contact_name=contact_name,
                session_id=session_id,
            )
            messages = [
                {"role": "user", "text": pure_user_text},
                {"role": "assistant", "text": reply_text},
            ]
            if debug_payload.get("provider"):
                messages.append({"role": "system", "text": f"provider={debug_payload['provider']}"})
    except Exception as e:
        return JSONResponse(
            status_code=503,
            content={
                "ok": False,
                "error": str(e),
                "messages": [],
                "reply_text": "",
            },
        )
        
    # Check for handoff
    if "[HANDOFF]" in reply_text:
        reply_text = reply_text.replace("[HANDOFF]", "").strip()
        try:
            database.update_session_status(session_id, "handoff_requested", agent_id=agent_id)
        except Exception:
            pass

    # Save messages to database and trigger async profile check
    try:
        database.save_message(session_id, contact_name, "user", pure_user_text, agent_id=agent_id)
        database.save_message(session_id, contact_name, "assistant", reply_text, agent_id=agent_id)
        asyncio.create_task(check_and_trigger_profile_update(session_id))
    except Exception as e:
        pass # Ignore db errors to not fail the main chat

    return {
        "ok": True,
        "agent_id": agent_id,
        "agent_display_name": agent.get("display_name") or agent.get("username"),
        "session_id": session_id,
        "messages": messages,
        "reply_text": reply_text,
    }


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.get("/api/admin/status")
async def api_get_admin_status(limit: int = 200, username: str = Depends(verify_admin)):
    log_path = BASE_DIR / os.environ.get("APP_LOG_FILE", "log.txt")
    log_stat = None
    if log_path.exists() and log_path.is_file():
        stat = log_path.stat()
        log_stat = {
            "path": str(log_path),
            "size_bytes": stat.st_size,
            "modified_at": datetime.datetime.fromtimestamp(stat.st_mtime, LOCAL_TZ).isoformat(),
        }

    uptime_seconds = int(time.monotonic() - APP_STARTED_MONOTONIC)
    return {
        "ok": True,
        "status": "running",
        "pid": os.getpid(),
        "server_time": datetime.datetime.now(LOCAL_TZ).isoformat(),
        "started_at": APP_STARTED_AT.isoformat(),
        "uptime_seconds": uptime_seconds,
        "uptime_text": _format_duration(uptime_seconds),
        "log_file": log_stat,
        "logs": _read_recent_log_lines(log_path, limit),
    }


@app.post("/api/admin/restart")
async def api_restart_service(username: str = Depends(verify_admin)):
    log_path = BASE_DIR / os.environ.get("APP_LOG_FILE", "log.txt")
    pid_path = BASE_DIR / "uvicorn.pid"
    current_pid = os.getpid()
    script = _build_restart_script(current_pid, log_path, pid_path)
    logger.warning("管理员 %s 请求重启 FastAPI 服务，当前 PID=%s", username, current_pid)
    subprocess.Popen(
        ["/bin/bash", "-lc", script],
        cwd=BASE_DIR,
        start_new_session=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return {"ok": True, "message": "FastAPI 重启已触发，请稍后刷新状态", "old_pid": current_pid}


@app.get("/admin", response_class=HTMLResponse)
async def get_admin_page():
    html_path = BASE_DIR / "static" / "admin" / "index.html"
    return html_path.read_text(encoding="utf-8")

@app.get("/api/admin/sessions")
async def api_get_sessions(
    agent_id: Optional[int] = None,
    limit: int = 50,
    offset: int = 0,
    username: str = Depends(verify_admin),
):
    return database.get_all_sessions(agent_id=agent_id, limit=limit, offset=offset)

@app.get("/api/admin/sessions/{session_id}")
async def api_get_session_detail(session_id: str, username: str = Depends(verify_admin)):
    session = database.get_session(session_id)
    messages = database.get_session_messages(session_id)
    profile = database.get_session_profile(session_id)
    return {"session": session, "messages": messages, "profile": profile}


@app.get("/api/admin/agents")
async def api_admin_list_agents(username: str = Depends(verify_admin)):
    return database.list_agents()


@app.post("/api/admin/agents")
async def api_admin_create_agent(payload: AdminAgentCreateRequest, username: str = Depends(verify_admin)):
    try:
        agent = database.create_agent(
            username=payload.username,
            password=payload.password,
            display_name=payload.display_name,
            note=payload.note,
        )
        logger.info("管理员 %s 创建 Agent 账号: agent_id=%s username=%s", username, agent.get("id"), agent.get("username"))
        return {"ok": True, "agent": agent}
    except Exception as e:
        return JSONResponse(status_code=400, content={"ok": False, "error": str(e)})


@app.put("/api/admin/agents/{agent_id}")
async def api_admin_update_agent(agent_id: int, payload: AdminAgentUpdateRequest, username: str = Depends(verify_admin)):
    try:
        agent = database.update_agent(agent_id, payload.display_name, payload.status, payload.note)
        if not agent:
            return JSONResponse(status_code=404, content={"ok": False, "error": "Agent 不存在"})
        logger.info("管理员 %s 更新 Agent 账号: agent_id=%s status=%s", username, agent_id, payload.status)
        return {"ok": True, "agent": agent}
    except Exception as e:
        return JSONResponse(status_code=400, content={"ok": False, "error": str(e)})


@app.post("/api/admin/agents/{agent_id}/password")
async def api_admin_set_agent_password(agent_id: int, payload: AdminAgentPasswordRequest, username: str = Depends(verify_admin)):
    try:
        database.set_agent_password(agent_id, payload.password)
        logger.info("管理员 %s 重置 Agent 密码并清空旧 token: agent_id=%s", username, agent_id)
        return {"ok": True}
    except Exception as e:
        return JSONResponse(status_code=400, content={"ok": False, "error": str(e)})

@app.post("/api/admin/sessions/{session_id}/profile")
async def api_extract_profile(session_id: str, username: str = Depends(verify_admin)):
    try:
        profile = await async_extract_profile(session_id)
        return {"ok": True, "profile": profile}
    except Exception as e:
        return JSONResponse(
            status_code=500,
            content={"ok": False, "error": str(e)}
        )

@app.delete("/api/admin/sessions/{session_id}")
async def api_delete_session(session_id: str, username: str = Depends(verify_admin)):
    database.delete_session(session_id)
    return {"ok": True}

@app.delete("/api/admin/messages/{message_id}")
async def api_delete_message(message_id: int, username: str = Depends(verify_admin)):
    database.delete_message(message_id)
    return {"ok": True}

class StatusUpdate(BaseModel):
    status: str

@app.post("/api/admin/sessions/{session_id}/status")
async def api_update_session_status(session_id: str, payload: StatusUpdate, username: str = Depends(verify_admin)):
    allowed_statuses = {"auto", "handoff_requested", "human_takeover", "muted"}
    status_value = (payload.status or "").strip().lower()
    if status_value not in allowed_statuses:
        return JSONResponse(
            status_code=400,
            content={"ok": False, "error": f"不支持的会话状态: {payload.status}"},
        )
    database.update_session_status(session_id, status_value)
    logger.info(
        "管理员 %s 更新会话状态: session_id=%s status=%s",
        username,
        session_id,
        status_value,
    )
    return {"ok": True, "status": status_value}
