"""
微信聊天分析接口：本地 OCR 文本（推荐）或上传截图（兼容）
+ 火山知识库检索 + 火山大模型生成（默认）
+ 腾讯云 ADP 智能体（可选回退）
"""

from __future__ import annotations

import asyncio
import os
from pathlib import Path
from typing import Optional

from dotenv import load_dotenv
from fastapi import FastAPI, File, Form, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from adp_client import load_adp_config_from_env, run_adp_chat
from volc_knowledge_client import run_volc_knowledge_chat

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


def _build_user_prompt(contact_name: str) -> str:
    """未提供 ocr_text 时：仅截图场景，服务端不做 OCR，用占位描述发给智能体。"""
    name = (contact_name or "").strip() or "客户"
    return (
        f"当前微信会话联系人是「{name}」。用户上传了一张聊天截图，但服务端未能拿到具体聊天文字。"
        "请作为旅游定制顾问，输出一句非常简短、自然、像真人微信聊天的回复建议。"
        "不要自我介绍，不要索要微信，不要重复固定话术，不要输出多段。"
    )


def _build_user_prompt_from_ocr(contact_name: str, ocr_text: str) -> str:
    """客户端已完成本地 OCR 时，仅把最新一条客户消息发给智能体。"""
    name = (contact_name or "").strip() or "客户"
    text = (ocr_text or "").strip()
    return (
        f"你正在代聊微信联系人「{name}」。\n"
        f"下面是客户刚刚发来的最新一条消息，不是完整聊天记录：\n\n{text}\n\n"
        "请只针对这条最新消息，生成一句适合直接微信发送的中文回复。\n"
        "要求：\n"
        "1. 像真人聊天，简短自然，1 到 2 句即可。\n"
        "2. 严禁重复固定开场白，严禁重复自我介绍，严禁再次索要微信。\n"
        "3. 如果客户是在拒绝、质疑、抱怨或表达不方便，要先顺着客户的话回应，不要答非所问。\n"
        "4. 不要输出分析，不要输出标题，不要加括号说明，只输出最终要发给客户的话。"
    )


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
        user_text = _build_user_prompt_from_ocr(contact_name, ocr_text or "")
    else:
        user_text = _build_user_prompt(contact_name)

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
                user_text=user_text,
            )
            messages = [
                {"role": "user", "text": user_text},
                {"role": "assistant", "text": reply_text},
            ]
        else:
            reply_text, debug_payload = await asyncio.to_thread(
                run_volc_knowledge_chat,
                user_text=user_text,
                contact_name=contact_name,
            )
            messages = [
                {"role": "user", "text": user_text},
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

    return {
        "ok": True,
        "messages": messages,
        "reply_text": reply_text,
    }


@app.get("/health")
async def health():
    return {"status": "ok"}
