"""
微信聊天分析接口：本地 OCR 文本（推荐）或上传截图（兼容）
+ 火山知识库检索 + 火山大模型生成（默认）
+ 腾讯云 ADP 智能体（可选回退）
"""

from __future__ import annotations

import asyncio
import datetime
import os
from pathlib import Path
from typing import Optional, Tuple

from dotenv import load_dotenv
from fastapi import Depends, FastAPI, File, Form, HTTPException, status, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, HTMLResponse
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from fastapi.staticfiles import StaticFiles

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


def get_current_time_str() -> str:
    tz = datetime.timezone(datetime.timedelta(hours=8))
    now = datetime.datetime.now(tz)
    weekdays = ["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"]
    weekday_str = weekdays[now.weekday()]
    return now.strftime("%Y-%m-%d %H:%M") + f"（{weekday_str}）"

def _build_user_prompt(contact_name: str) -> Tuple[str, str]:
    """未提供 ocr_text 时：仅截图场景，服务端不做 OCR，用占位描述发给智能体。"""
    name = (contact_name or "").strip() or "客户"
    current_time = get_current_time_str()
    pure_text = "[用户上传了一张聊天截图]"
    wrapped_text = (
        f"（系统提示：当前实际时间是 {current_time}，请以此作为判断今天、明天、下周等相对时间的基准。）\n\n"
        f"当前微信会话联系人是「{name}」。用户上传了一张聊天截图，但服务端未能拿到具体聊天文字。\n"
        "请作为旅游定制顾问，直接给出一句简短的回复建议。"
    )
    return pure_text, wrapped_text


def _build_user_prompt_from_ocr(contact_name: str, ocr_text: str) -> Tuple[str, str]:
    """客户端已完成本地 OCR 时，仅把最新一条客户消息发给智能体。"""
    name = (contact_name or "").strip() or "客户"
    text = (ocr_text or "").strip()
    current_time = get_current_time_str()
    pure_text = text
    wrapped_text = (
        f"（系统提示：当前实际时间是 {current_time}。如果在聊天中客户提到诸如“下月”、“明天”等相对时间，请以此为基准推算。）\n\n"
        f"代聊微信联系人「{name}」。对方刚刚发来的最新消息：\n\n{text}"
    )
    return pure_text, wrapped_text


def _provider_from_env() -> str:
    return (os.environ.get("CHAT_PROVIDER", "volc_knowledge").strip() or "volc_knowledge").lower()


@app.post("/wechat/chat")
async def wechat_chat(
    session_id: str = Form(...),
    contact_name: str = Form(...),
    ocr_text: Optional[str] = Form(None),
    image: Optional[UploadFile] = File(default=None),
):
    """接收本地 OCR 文本（推荐）或截图（兼容），返回 reply_text。

    - 仅传 ocr_text：Agent IME 等客户端本地 OCR，不上传图片（推荐）。
    - 仅传 image：兼容旧版；服务端保存图片，智能体使用占位描述（不做 OCR）。
    - 若两者同时提供：按接口说明优先使用 ocr_text。
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

    # 优先使用客户端本地 OCR 全文（与接口说明「构造 user 文本」一致）
    if ocr_ok:
        pure_user_text, wrapped_user_text = _build_user_prompt_from_ocr(contact_name, ocr_text or "")
    else:
        pure_user_text, wrapped_user_text = _build_user_prompt(contact_name)

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

    # Save messages to database and trigger async profile check
    try:
        database.save_message(session_id, contact_name, "user", pure_user_text)
        database.save_message(session_id, contact_name, "assistant", reply_text)
        asyncio.create_task(check_and_trigger_profile_update(session_id))
    except Exception as e:
        pass # Ignore db errors to not fail the main chat

    return {
        "ok": True,
        "messages": messages,
        "reply_text": reply_text,
    }


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.get("/admin", response_class=HTMLResponse)
async def get_admin_page():
    html_path = BASE_DIR / "static" / "admin" / "index.html"
    return html_path.read_text(encoding="utf-8")

@app.get("/api/admin/sessions")
async def api_get_sessions(username: str = Depends(verify_admin)):
    return database.get_all_sessions()

@app.get("/api/admin/sessions/{session_id}")
async def api_get_session_detail(session_id: str, username: str = Depends(verify_admin)):
    messages = database.get_session_messages(session_id)
    profile = database.get_session_profile(session_id)
    return {"messages": messages, "profile": profile}

@app.post("/api/admin/sessions/{session_id}/profile")
async def api_extract_profile(session_id: str, username: str = Depends(verify_admin)):
    profile = await async_extract_profile(session_id)
    return {"profile": profile}
