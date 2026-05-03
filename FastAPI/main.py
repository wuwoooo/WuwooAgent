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
import requests
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
from typing import Any, Optional, Tuple

from dotenv import load_dotenv
from fastapi import Depends, FastAPI, File, Form, HTTPException, status, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, HTMLResponse
from fastapi.security import HTTPBasic, HTTPBasicCredentials, HTTPAuthorizationCredentials, HTTPBearer
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from adp_client import load_adp_config_from_env, run_adp_chat
from volc_knowledge_client import (
    build_knowledge_context,
    chat_completion,
    load_volc_config_from_env,
    run_volc_knowledge_chat,
    search_knowledge,
)
import secrets

try:
    import database
    from profile_extractor import async_extract_contact_memory, async_extract_profile, check_and_trigger_profile_update
    from conversation_summary import async_summarize_conversation
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


class ConversationSummaryRequest(BaseModel):
    limit: int | None = 20


class AgentContinuationRequest(BaseModel):
    limit: int | None = 30


class RemarkAppliedRequest(BaseModel):
    applied_remark: str = ""


class ContactManualNotesRequest(BaseModel):
    manual_notes: dict[str, Any] = {}
    manual_notes_text: str = ""


class AdminOutboundTaskRequest(BaseModel):
    agent_id: int | None = None
    session_id: str = ""
    contact_name: str
    search_keyword: str = ""
    message: str
    auto_send: bool = False


class AgentOutboundTaskResultRequest(BaseModel):
    success: bool
    error: str = ""


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


def _format_profile_for_prompt(profile: dict[str, Any] | None) -> str:
    if not profile:
        return "暂无已提取画像。"

    parts = []
    labels = {
        "destination": "目的地",
        "people_count": "人数",
        "travel_time": "出行时间",
        "budget": "预算",
        "preferences": "偏好",
        "sales_stage": "销售阶段",
    }
    for key, label in labels.items():
        value = profile.get(key)
        if value:
            if isinstance(value, (list, tuple)):
                value = "、".join(str(item) for item in value if item)
            parts.append(f"{label}：{value}")
    return "\n".join(parts) if parts else "暂无已提取画像。"


def _memory_value_text(value: Any) -> str:
    if isinstance(value, dict):
        value = value.get("value")
    if isinstance(value, (list, tuple)):
        return "、".join(str(item).strip() for item in value if str(item).strip())
    return str(value or "").strip()


def _format_contact_context_for_prompt(
    contact: dict[str, Any] | None,
    session_profile: dict[str, Any] | None = None,
) -> str:
    """压缩联系人长期记忆，供模型作为参考上下文使用。"""
    lines = [
        "联系人长期记忆（仅作参考；如与客户最新消息冲突，以最新消息为准）："
    ]
    if not contact:
        lines.append("暂无长期联系人记忆。")
    else:
        display_name = contact.get("display_name") or contact.get("preferred_name") or contact.get("primary_name") or ""
        if display_name:
            lines.append(f"- 主要称呼：{display_name}")

        manual_notes = contact.get("manual_notes") or {}
        if isinstance(manual_notes, dict):
            for key, value in manual_notes.items():
                text = _memory_value_text(value)
                if text:
                    lines.append(f"- 人工补充/{key}：{text}")

        memory = contact.get("memory") or {}
        stable_profile = memory.get("stable_profile") or {}
        for key, value in stable_profile.items():
            text = _memory_value_text(value)
            if text:
                lines.append(f"- 稳定偏好/{key}：{text}")

        dynamic_state = memory.get("dynamic_state") or {}
        for key, value in dynamic_state.items():
            text = _memory_value_text(value)
            if text:
                lines.append(f"- 当前状态/{key}：{text}")

        facts = memory.get("facts") or []
        shown = 0
        for fact in reversed(facts if isinstance(facts, list) else []):
            if not isinstance(fact, dict):
                continue
            text = _memory_value_text(fact.get("value"))
            if not text:
                continue
            lines.append(f"- 已知事实：{text}")
            shown += 1
            if shown >= 5:
                break

    session_profile_text = _format_profile_for_prompt(session_profile)
    if session_profile_text != "暂无已提取画像。":
        lines.append("本次咨询画像：")
        lines.append(session_profile_text)

    return "\n".join(lines)


def _format_messages_for_prompt(messages: list[dict[str, Any]], limit: int | None = 30) -> str:
    safe_limit = max(1, min(int(limit or 30), 80))
    recent_messages = messages[-safe_limit:]
    if not recent_messages:
        return "暂无聊天记录。"

    lines = []
    for msg in recent_messages:
        role = "客户" if msg.get("role") == "user" else "我方"
        content = str(msg.get("content") or "").strip()
        if content:
            lines.append(f"{role}：{content}")
    return "\n".join(lines) if lines else "暂无有效聊天记录。"


def _strip_repeated_customer_address(reply_text: str, contact_name: str, session_id: str) -> str:
    """连续对话中去掉生硬的开头称呼，保留首次问候的自然感。"""
    text = (reply_text or "").strip()
    name = (contact_name or "").strip()
    if not text or not name:
        return text

    try:
        has_prior_assistant_reply = any(
            msg.get("role") == "assistant" and str(msg.get("content") or "").strip()
            for msg in database.get_session_messages(session_id)
        )
    except Exception:
        has_prior_assistant_reply = False

    if not has_prior_assistant_reply:
        return text

    if name and name != "客户":
        escaped_name = re.escape(name)
        text = re.sub(
            rf"^{escaped_name}\s*(?:您好|你好|早上好|上午好|中午好|下午好|晚上好)?[，,、：:！!\s~。.]*",
            "",
            text,
            count=1,
        ).lstrip()

    stripped = re.sub(
        r"^[^，,、：:！!\s~。]{0,8}\s*(?:您好|你好|早上好|上午好|中午好|下午好|晚上好)[，,、：:！!\s~。.]*",
        "",
        text,
        count=1,
    ).lstrip()
    return stripped or text


def _build_agent_continuation_prompt(
    *,
    contact_name: str,
    messages: list[dict[str, Any]],
    profile: dict[str, Any] | None,
    contact_context: str = "",
    limit: int | None = 30,
) -> str:
    return (
        f"当前微信联系人：{contact_name or '客户'}\n"
        f"当前实际时间：{get_current_time_str()}\n\n"
        f"【称呼规则】如果确实需要称呼客户，只能使用「{contact_name or '客户'}」；但【绝对不要】在每条消息开头加“你好”、“晚上好”等问候语，更不要重复称呼客户姓名。这是连续对话，请开门见山直接回答客户问题。不要使用聊天记录中出现的其他名字（可能存在 OCR 同音字误差）。\n\n"
        "客户画像：\n"
        f"{_format_profile_for_prompt(profile)}\n\n"
        "联系人背景：\n"
        f"{contact_context or '暂无长期联系人记忆。'}\n\n"
        f"最近聊天记录：\n{_format_messages_for_prompt(messages, limit)}\n\n"
        "任务：后台人员已经点击“由 Agent 出方案”，请你继续以旅游定制顾问“小鹿”的身份，"
        "直接生成一条可以发送给客户的初版行程方案。请使用微信里容易阅读和复制的排版，"
        "每个自然段之间必须用空行分隔。"
    )


def _run_volc_agent_continuation(prompt: str) -> str:
    cfg = load_volc_config_from_env()
    cfg["max_tokens"] = max(int(cfg.get("max_tokens") or 250), 1200)
    cfg["temperature"] = min(float(cfg.get("temperature") or 0.7), 0.5)

    search_payload = search_knowledge(prompt[-1200:], cfg)
    knowledge_context = build_knowledge_context(search_payload)

    system_prompt = """你是云南云鹿旅行社的高级旅游顾问“小鹿”，正在微信里和客户一对一沟通。
现在客户已经请求推进方案，后台人员点击按钮让 Agent 继续出方案。

输出要求：
1. 直接输出要发给客户的微信消息，不要解释你的思考过程。
2. 可以给出初版行程框架、玩法/住宿/用车建议、下一步确认项。
3. 如果价格、酒店房态、车价等信息没有可靠依据，不要编造具体数字，用“我这边继续核算后发您准确报价”表达。
4. 如果关键信息不足，也要先给一个可执行的初步方向，并用 1 到 3 个问题补齐缺口。
5. 必须保留清晰排版：开头一句话单独成段；方案按“初步安排：”“推荐重点：”“还需要确认：”等小段落组织；段落之间用空行分隔。
6. 不要使用 Markdown 表格，不要堆成一整段；适合复制到微信后仍能分段阅读。
7. 语气自然、专业、像真人定制师；不要输出 [HANDOFF]。"""

    messages = [{"role": "system", "content": system_prompt}]
    if knowledge_context:
        messages.append({"role": "system", "content": knowledge_context})
    messages.append({"role": "user", "content": prompt})

    return chat_completion(messages, cfg).replace("[HANDOFF]", "").strip()


async def _generate_agent_continuation(session_id: str, limit: int | None = 30) -> tuple[str, dict[str, Any]]:
    session = database.get_session(session_id)
    if not session:
        raise ValueError("会话不存在")

    messages = database.get_session_messages(session_id)
    if not messages:
        raise ValueError("当前会话还没有聊天记录，无法生成方案")

    contact_name = session.get("contact_name") or "客户"
    profile = database.get_session_profile(session_id)
    contact = database.get_contact_for_session(session_id, agent_id=session.get("agent_id"))
    contact_context = _format_contact_context_for_prompt(contact, profile)
    # 人工纠错称呼：如果联系人设置了 preferred_name，优先使用它
    if contact and contact.get("preferred_name"):
        contact_name = contact["preferred_name"]
    prompt = _build_agent_continuation_prompt(
        contact_name=contact_name,
        messages=messages,
        profile=profile,
        contact_context=contact_context,
        limit=limit,
    )

    provider = _provider_from_env()
    if provider == "adp":
        sid, skey, region, app_key = load_adp_config_from_env()
        reply_text = await run_adp_chat(
            secret_id=sid,
            secret_key=skey,
            region=region,
            bot_app_key=app_key,
            session_id=session_id,
            user_text=prompt,
        )
        reply_text = reply_text.replace("[HANDOFF]", "").strip()
    else:
        reply_text = await asyncio.to_thread(_run_volc_agent_continuation, prompt)

    if not reply_text:
        raise ValueError("Agent 未生成可用方案，请稍后重试")
    reply_text = _strip_repeated_customer_address(reply_text, contact_name, session_id)

    agent_id = session.get("agent_id")
    database.save_message(session_id, contact_name, "assistant", reply_text, agent_id=agent_id)
    database.update_session_status(session_id, "auto", agent_id=agent_id)
    return reply_text, session


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

def _build_user_prompt(contact_name: str, current_status: str = "auto", contact_context: str = "") -> Tuple[str, str]:
    """未提供 ocr_text 时：仅截图场景，服务端不做 OCR，用占位描述发给智能体。"""
    name = (contact_name or "").strip() or "客户"
    current_time = get_current_time_str()
    pure_text = "[用户上传了一张聊天截图]"
    
    receptionist_rule = ""
    if current_status == "handoff_requested":
        receptionist_rule = "【当前状态：正在为客户制作方案】你之前已告知客户要去整理行程方案和报价了。对于客户的新消息，请注意：如果客户询问普通旅行问题，可正常解答；如果客户催促方案进度，必须以第一人称安抚（例如：‘我正在快马加鞭为您核算报价和行程呢，请您稍等片刻哦～’），绝对不要自己编造具体方案或报价数字。\n"

    wrapped_text = (
        f"（系统提示：当前实际时间是 {current_time}。注意：仅在第一轮对话或主动打招呼时才进行时间问候，在后续连续的对话中直接回答用户的问题，绝对不要重复问候！你在问候客户时必须以此为准判断今天是星期几，绝对不要参考下方文本中可能出现的旧时间标签（如“周一16:40”等是微信界面上旧消息的时间戳，不代表当前时间）。请以此作为判断今天、明天、下周等相对时间的基准。\n"
        f"【称呼规则】如果确实需要称呼客户，只能使用系统提供的联系人名「{name}」；但【绝对不要】在每条消息开头加“你好”、“晚上好”等问候语，更不要重复称呼客户姓名。这是连续对话，请开门见山直接回答客户问题。不要使用聊天记录或 OCR 文本中出现的名字。因为 OCR 识别存在同音字误差（如索被识别为素），聊天记录中的名字不可靠，以系统提供的联系人名为唯一准确来源。\n"
        "【HANDOFF 规则】在以下两种情况下，才可在回复末尾加上 [HANDOFF] 标记：1) 客户在本轮消息中明确表达了要你出方案/报价/下单的意图（例如‘帮我安排一下’、‘出个方案吧’、‘可以报价了’）；2) 虽然客户没有直接说‘出方案’，但从对话上下文判断客户意愿已经非常强烈，目的地、人数、时间等关键信息都已明确，且客户表现出明显的决策倾向（例如‘就这个行程吧’、‘五一就走’、‘两个人确定了’）。如果客户只是打招呼、闲聊、问问题、或者你自己还在提问收集基本信息，**绝对不要**加 [HANDOFF]。触发 [HANDOFF] 时，你的回复**必须是一句确认性的第一人称过渡话术**（例如‘好的，我这就给您整理具体的行程方案和报价～’），**绝对不能是提问句**，也绝对不能说‘转交给人工’或‘转交给定制师’，因为你本身就是这位专属的定制师小鹿。）\n\n"
        f"{receptionist_rule}"
        f"{contact_context + chr(10) if contact_context else ''}"
        f"当前微信会话联系人是「{name}」。用户上传了一张聊天截图，但服务端未能拿到具体聊天文字。\n"
        "请作为旅游定制顾问，直接给出一句简短的回复建议。"
    )
    return pure_text, wrapped_text


def _build_user_prompt_from_ocr(contact_name: str, ocr_text: str, current_status: str = "auto", contact_context: str = "") -> Tuple[str, str]:
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
        f"【称呼规则】如果确实需要称呼客户，只能使用系统提供的联系人名「{name}」；但【绝对不要】在每条消息开头加“你好”、“晚上好”等问候语，更不要重复称呼客户姓名。这是连续对话，请开门见山直接回答客户问题。不要使用聊天记录或 OCR 文本中出现的名字。因为 OCR 识别存在同音字误差（如索被识别为素），聊天记录中的名字不可靠，以系统提供的联系人名为唯一准确来源。\n"
        "【HANDOFF 规则】在以下两种情况下，才可在回复末尾加上 [HANDOFF] 标记：1) 客户在本轮消息中明确表达了要你出方案/报价/下单的意图（例如‘帮我安排一下’、‘出个方案吧’、‘可以报价了’）；2) 虽然客户没有直接说‘出方案’，但从对话上下文判断客户意愿已经非常强烈，目的地、人数、时间等关键信息都已明确，且客户表现出明显的决策倾向（例如‘就这个行程吧’、‘五一就走’、‘两个人确定了’）。如果客户只是打招呼、闲聊、问问题、或者你自己还在提问收集基本信息，**绝对不要**加 [HANDOFF]。触发 [HANDOFF] 时，你的回复**必须是一句确认性的第一人称过渡话术**（例如‘好的，我这就给您整理具体的行程方案和报价～’），**绝对不能是提问句**，也绝对不能说‘转交给人工’或‘转交给定制师’，因为你本身就是这位专属的定制师小鹿。）\n\n"
        f"{receptionist_rule}"
        f"{contact_context + chr(10) if contact_context else ''}"
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


@app.get("/agent/outbound-tasks/next")
@app.get("/api/agent/outbound-tasks/next")
async def api_agent_claim_outbound_task(agent: dict = Depends(verify_agent)):
    task = database.claim_next_outbound_task(int(agent["id"]))
    return {"ok": True, "task": task}


@app.post("/agent/outbound-tasks/{task_id}/result")
@app.post("/api/agent/outbound-tasks/{task_id}/result")
async def api_agent_complete_outbound_task(
    task_id: int,
    payload: AgentOutboundTaskResultRequest,
    agent: dict = Depends(verify_agent),
):
    task = database.complete_outbound_task(
        task_id=task_id,
        agent_id=int(agent["id"]),
        success=payload.success,
        error=payload.error,
    )
    if not task:
        return JSONResponse(status_code=404, content={"ok": False, "error": "任务不存在或不属于当前 Agent"})
    return {"ok": True, "task": task}


import base64

def _run_vision_ocr(image_bytes: bytes) -> list[dict[str, Any]]:
    api_key = os.environ.get("ARK_VISION_API_KEY", "ark-0773e8a1-0054-4243-83b9-b1a4f06b67da-d677d")
    model_id = os.environ.get("ARK_VISION_MODEL_ID", "doubao-seed-2-0-pro-260215")
    base_url = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
    
    base64_image = base64.b64encode(image_bytes).decode('utf-8')
    prompt = (
        "你是一个微信聊天记录提取专家。请提取图片中的所有聊天记录，按时间顺序输出。\n"
        "请注意：绿色气泡表示'我方(agent)'发送的，白色/其他非绿色气泡表示'客户(client)'发送的。\n"
        "请忽略顶部标题栏、底部输入框等无关元素。严格只返回一个 JSON 数组，不要包裹在任何 Markdown 标记中，格式如下：\n"
        '[{"sender": "client", "text": "你好"}, {"sender": "agent", "text": "您好，需要什么旅游定制服务？"}]'
    )
    
    payload = {
        "model": model_id,
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt},
                    {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{base64_image}"}}
                ]
            }
        ],
        "temperature": 0.1
    }
    
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {api_key}"
    }
    
    try:
        response = requests.post(base_url, headers=headers, json=payload, timeout=40)
        if response.status_code == 200:
            result = response.json()
            content = result["choices"][0]["message"]["content"].strip()
            if content.startswith("```json"):
                content = content[7:]
            if content.startswith("```"):
                content = content[3:]
            if content.endswith("```"):
                content = content[:-3]
            content = content.strip()
            return json.loads(content)
        else:
            logger.error(f"VLM OCR 请求失败: {response.status_code} {response.text}")
    except Exception as e:
        logger.error(f"VLM OCR 发生异常: {e}")
    return []

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
        
        # 阶段一：用 VLM 做高级云端 OCR 提取
        if not ocr_ok:
            logger.info(f"客户端未提供 ocr_text，触发 VLM 视觉大模型提取: {safe_name}")
            extracted_msgs = await asyncio.to_thread(_run_vision_ocr, image_bytes)
            last_agent_idx = -1
            for i, msg in enumerate(extracted_msgs):
                if msg.get("sender") == "agent":
                    last_agent_idx = i
                    
            if is_human_reply:
                if last_agent_idx >= 0:
                    ocr_text = extracted_msgs[last_agent_idx].get("text", "")
                    ocr_ok = bool(ocr_text.strip())
                    logger.info(f"VLM OCR 成功提取真人回复: {ocr_text}")
                else:
                    logger.info("VLM OCR 未能找到 agent 发送的真人回复")
            else:
                new_client_texts = []
                for i in range(last_agent_idx + 1, len(extracted_msgs)):
                    if extracted_msgs[i].get("sender") == "client":
                        new_client_texts.append(extracted_msgs[i].get("text", ""))
                
                if new_client_texts:
                    ocr_text = "\n".join(new_client_texts)
                    ocr_ok = True
                    logger.info(f"VLM OCR 成功提取 {len(new_client_texts)} 条客户新消息: {ocr_text}")
                else:
                    logger.info("VLM OCR 未提取到客户新消息")

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

    contact = None
    contact_id = None
    session_profile = None
    contact_context = ""
    try:
        contact = database.resolve_contact(session_id, contact_name, agent_id=agent_id)
        contact_id = contact.get("id") if contact else None
        session_profile = database.get_session_profile(session_id)
        contact_context = _format_contact_context_for_prompt(contact, session_profile)
        # 人工纠错称呼：如果联系人设置了 preferred_name，优先使用它
        if contact and contact.get("preferred_name"):
            contact_name = contact["preferred_name"]
    except Exception as e:
        logger.warning("联系人长期记忆解析失败，跳过上下文注入: session_id=%s contact=%s error=%s", session_id, contact_name, e)
        
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
        pure_user_text, wrapped_user_text = _build_user_prompt_from_ocr(contact_name, ocr_text or "", current_status, contact_context)
    else:
        pure_user_text, wrapped_user_text = _build_user_prompt(contact_name, current_status, contact_context)

    if _should_request_handoff(pure_user_text, session_id):
        reply_text = _handoff_reply_text()
        try:
            database.save_message(session_id, contact_name, "user", pure_user_text, agent_id=agent_id)
            database.save_message(session_id, contact_name, "assistant", reply_text, agent_id=agent_id)
            database.update_session_status(session_id, "handoff_requested", agent_id=agent_id)
            asyncio.create_task(check_and_trigger_profile_update(session_id, contact_id=contact_id))
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
                contact_context=contact_context,
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

    reply_text = _strip_repeated_customer_address(reply_text, contact_name, session_id)
    for msg in messages:
        if msg.get("role") == "assistant":
            msg["text"] = reply_text
            break

    # Save messages to database and trigger async profile check
    try:
        database.save_message(session_id, contact_name, "user", pure_user_text, agent_id=agent_id)
        database.save_message(session_id, contact_name, "assistant", reply_text, agent_id=agent_id)
        asyncio.create_task(check_and_trigger_profile_update(session_id, contact_id=contact_id))
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
    contact = database.get_contact_for_session(session_id) if session else None
    if contact:
        session = database.get_session(session_id)
    return {"session": session, "messages": messages, "profile": profile, "contact": contact}


@app.get("/api/admin/outbound-tasks")
async def api_admin_list_outbound_tasks(
    agent_id: Optional[int] = None,
    limit: int = 30,
    username: str = Depends(verify_admin),
):
    return {"ok": True, "items": database.list_outbound_tasks(agent_id=agent_id, limit=limit)}


@app.post("/api/admin/outbound-tasks")
async def api_admin_create_outbound_task(payload: AdminOutboundTaskRequest, username: str = Depends(verify_admin)):
    try:
        task = database.create_outbound_task(
            agent_id=payload.agent_id,
            session_id=payload.session_id,
            contact_name=payload.contact_name,
            search_keyword=payload.search_keyword or payload.contact_name,
            message=payload.message,
            auto_send=payload.auto_send,
        )
        logger.info(
            "管理员 %s 创建主动外发任务: task_id=%s agent_id=%s contact=%s auto_send=%s",
            username,
            task.get("id"),
            task.get("agent_id"),
            task.get("contact_name"),
            task.get("auto_send"),
        )
        return {"ok": True, "task": task}
    except Exception as e:
        return JSONResponse(status_code=400, content={"ok": False, "error": str(e)})


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


@app.get("/api/admin/contacts/{contact_id}")
async def api_admin_get_contact(contact_id: int, username: str = Depends(verify_admin)):
    contact = database.get_contact(contact_id)
    if not contact:
        return JSONResponse(status_code=404, content={"ok": False, "error": "联系人不存在"})
    return {"ok": True, "contact": contact}


@app.post("/api/admin/contacts/{contact_id}/manual-notes")
async def api_admin_update_contact_manual_notes(
    contact_id: int,
    payload: ContactManualNotesRequest,
    username: str = Depends(verify_admin),
):
    try:
        manual_notes = payload.manual_notes
        if payload.manual_notes_text.strip():
            manual_notes = {"补充资料": payload.manual_notes_text.strip()}
        contact = database.update_contact_manual_notes(contact_id, manual_notes)
    except Exception as e:
        return JSONResponse(status_code=400, content={"ok": False, "error": str(e)})
    if not contact:
        return JSONResponse(status_code=404, content={"ok": False, "error": "联系人不存在"})
    logger.info("管理员 %s 更新联系人长期记忆人工资料: contact_id=%s", username, contact_id)
    return {"ok": True, "contact": contact}


@app.post("/api/admin/contacts/{contact_id}/merge")
async def api_admin_merge_contact_memory(contact_id: int, session_id: str = "", username: str = Depends(verify_admin)):
    try:
        if not session_id:
            contact = database.get_contact(contact_id)
            if not contact:
                return JSONResponse(status_code=404, content={"ok": False, "error": "联系人不存在"})
            return {"ok": True, "contact": contact}
        await async_extract_contact_memory(session_id, contact_id=contact_id)
        contact = database.get_contact(contact_id)
        return {"ok": True, "contact": contact}
    except Exception as e:
        return JSONResponse(status_code=500, content={"ok": False, "error": str(e)})

class ContactPreferredNameRequest(BaseModel):
    preferred_name: str = ""


@app.post("/api/admin/contacts/{contact_id}/preferred-name")
async def api_admin_update_contact_preferred_name(
    contact_id: int,
    payload: ContactPreferredNameRequest,
    username: str = Depends(verify_admin),
):
    """人工修正联系人的正确称呼（用于纠正 OCR 识别错误）。"""
    try:
        contact = database.update_contact_preferred_name(contact_id, payload.preferred_name)
    except Exception as e:
        return JSONResponse(status_code=400, content={"ok": False, "error": str(e)})
    if not contact:
        return JSONResponse(status_code=404, content={"ok": False, "error": "联系人不存在"})
    logger.info(
        "管理员 %s 修正联系人称呼: contact_id=%s preferred_name=%s",
        username, contact_id, payload.preferred_name,
    )
    return {"ok": True, "contact": contact}


@app.post("/api/admin/sessions/{session_id}/profile")
async def api_extract_profile(session_id: str, username: str = Depends(verify_admin)):
    try:
        profile = await async_extract_profile(session_id)
        session = database.get_session(session_id)
        contact = database.get_contact_for_session(session_id) if session else None
        if contact:
            try:
                await async_extract_contact_memory(session_id, contact_id=int(contact["id"]))
                contact = database.get_contact(int(contact["id"]))
            except Exception as e:
                logger.warning("管理员手动提取画像时，联系人长期记忆更新失败: session_id=%s error=%s", session_id, e)
        session = database.get_session(session_id)
        return {"ok": True, "profile": profile, "session": session, "contact": contact}
    except Exception as e:
        return JSONResponse(
            status_code=500,
            content={"ok": False, "error": str(e)}
        )


@app.post("/api/admin/sessions/{session_id}/remark-suggestion")
async def api_refresh_remark_suggestion(session_id: str, username: str = Depends(verify_admin)):
    session = database.refresh_remark_suggestion(session_id)
    if not session:
        return JSONResponse(status_code=404, content={"ok": False, "error": "会话不存在"})
    logger.info(
        "管理员 %s 刷新备注建议: session_id=%s remark=%s",
        username,
        session_id,
        session.get("suggested_remark"),
    )
    return {"ok": True, "session": session}


@app.post("/api/admin/sessions/{session_id}/remark-applied")
async def api_mark_remark_applied(
    session_id: str,
    payload: RemarkAppliedRequest,
    username: str = Depends(verify_admin),
):
    session = database.mark_remark_applied(session_id, payload.applied_remark)
    if not session:
        return JSONResponse(status_code=404, content={"ok": False, "error": "会话不存在"})
    logger.info(
        "管理员 %s 标记已手工备注: session_id=%s remark=%s",
        username,
        session_id,
        payload.applied_remark or session.get("suggested_remark"),
    )
    return {"ok": True, "session": session}


@app.post("/api/admin/sessions/{session_id}/summary")
async def api_summarize_conversation(
    session_id: str,
    payload: ConversationSummaryRequest,
    username: str = Depends(verify_admin),
):
    try:
        result = await async_summarize_conversation(session_id, payload.limit)
        return {"ok": True, **result}
    except Exception as e:
        return JSONResponse(
            status_code=500,
            content={"ok": False, "error": str(e)}
        )


@app.post("/api/admin/sessions/{session_id}/agent-continuation")
async def api_generate_agent_continuation(
    session_id: str,
    payload: AgentContinuationRequest,
    username: str = Depends(verify_admin),
):
    try:
        reply_text, session = await _generate_agent_continuation(session_id, payload.limit)
        logger.info(
            "管理员 %s 触发 Agent 继续出方案: session_id=%s contact=%s",
            username,
            session_id,
            session.get("contact_name"),
        )
        return {
            "ok": True,
            "status": "auto",
            "reply_text": reply_text,
        }
    except ValueError as e:
        return JSONResponse(status_code=400, content={"ok": False, "error": str(e)})
    except Exception as e:
        logger.exception("Agent 继续出方案失败: session_id=%s", session_id)
        return JSONResponse(status_code=500, content={"ok": False, "error": str(e)})

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
