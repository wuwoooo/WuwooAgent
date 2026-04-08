"""
腾讯云智能体开发平台（ADP）对话端 WebSocket V2 客户端。
文档：https://cloud.tencent.com/document/product/1759/129365
"""

from __future__ import annotations

import asyncio
import hashlib
import os
import uuid
from typing import Any

import socketio
from tencentcloud.common import credential
from tencentcloud.common.profile.client_profile import ClientProfile
from tencentcloud.common.profile.http_profile import HttpProfile
from tencentcloud.lke.v20231130 import lke_client, models


def _normalize_visitor_biz_id(session_key: str) -> str:
    """访客 ID，最长 64 字符。"""
    s = (session_key or "").strip()
    if len(s) <= 64:
        return s or "visitor"
    return hashlib.sha256(s.encode("utf-8")).hexdigest()[:64]


def conversation_id_from_session(session_id: str) -> str:
    """
    会话 ID：文档要求 32–64 字符，建议与外部会话一一对应。
    使用 uuid5 保证同一 session_id 稳定映射到同一 ConversationId。
    """
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"wechat-chat:{session_id}"))


def _extract_reply_from_completed(payload: dict[str, Any]) -> str | None:
    """从 response.completed 事件中取出 Type=reply 的文本。"""
    resp = payload.get("Response") or {}
    messages = resp.get("Messages") or []
    for msg in messages:
        if msg.get("Type") != "reply":
            continue
        texts: list[str] = []
        for c in msg.get("Contents") or []:
            if c.get("Type") == "text" and c.get("Text"):
                texts.append(str(c["Text"]))
        if texts:
            return "\n".join(texts)
    return None


async def get_ws_token_async(
    *,
    secret_id: str,
    secret_key: str,
    region: str,
    bot_app_key: str,
    visitor_biz_id: str,
) -> str:
    """调用 GetWsToken 获取一次性 WebSocket 鉴权 Token（同步 SDK 放到线程中执行）。"""

    def _call() -> str:
        cred = credential.Credential(secret_id, secret_key)
        http_profile = HttpProfile()
        http_profile.endpoint = "lke.tencentcloudapi.com"
        client_profile = ClientProfile()
        client_profile.httpProfile = http_profile
        cli = lke_client.LkeClient(cred, region, client_profile)
        req = models.GetWsTokenRequest()
        req.Type = 5
        req.BotAppKey = bot_app_key
        req.VisitorBizId = visitor_biz_id
        resp = cli.GetWsToken(req)
        if not resp.Token:
            raise RuntimeError("GetWsToken 未返回 Token")
        return resp.Token

    return await asyncio.to_thread(_call)


async def adp_chat_reply(
    *,
    user_text: str,
    conversation_id: str,
    visitor_biz_id: str,
    ws_token: str,
    timeout_sec: float = 120.0,
) -> str:
    """
    建立 Socket.IO 连接，发送 request，等待 response.completed 或 error。
    """
    result: dict[str, Any] = {}
    done = asyncio.Event()

    sio = socketio.AsyncClient(
        reconnection=False,
        logger=False,
        engineio_logger=False,
    )

    @sio.on("response.completed", namespace="/")
    async def _on_completed(data: dict[str, Any]) -> None:
        result["reply"] = _extract_reply_from_completed(data)
        done.set()

    @sio.on("error", namespace="/")
    async def _on_error(data: dict[str, Any]) -> None:
        err = data.get("Error") if isinstance(data, dict) else {}
        if isinstance(err, dict):
            result["error"] = err.get("Message") or str(err)
        else:
            result["error"] = str(data)
        done.set()

    @sio.on("connect_error")
    async def _on_connect_error(data: Any) -> None:
        result["error"] = f"WebSocket 连接失败: {data}"
        done.set()

    url = "wss://wss.lke.cloud.tencent.com/?language=zh-CN"
    try:
        await sio.connect(
            url,
            transports=["websocket"],
            socketio_path="adp/v2/chat/conn",
            auth={"token": ws_token},
            wait=True,
            wait_timeout=30,
        )
        request_id = str(uuid.uuid4())
        payload = {
            "Type": "request",
            "Request": {
                "RequestId": request_id,
                "ConversationId": conversation_id,
                "Contents": [{"Type": "text", "Text": user_text}],
                "Incremental": False,
            },
        }
        await sio.emit("request", payload, namespace="/")
        await asyncio.wait_for(done.wait(), timeout=timeout_sec)
    finally:
        if sio.connected:
            await sio.disconnect()

    if result.get("error"):
        raise RuntimeError(str(result["error"]))
    reply = result.get("reply")
    if not reply:
        raise RuntimeError("智能体未返回可解析的回复（未找到 reply 消息）")
    return reply


async def run_adp_chat(
    *,
    secret_id: str,
    secret_key: str,
    region: str,
    bot_app_key: str,
    session_id: str,
    user_text: str,
) -> str:
    """获取 Token 并发起一轮对话，返回助手回复文本。"""
    visitor = _normalize_visitor_biz_id(session_id)
    conv = conversation_id_from_session(session_id)
    token = await get_ws_token_async(
        secret_id=secret_id,
        secret_key=secret_key,
        region=region,
        bot_app_key=bot_app_key,
        visitor_biz_id=visitor,
    )
    return await adp_chat_reply(
        user_text=user_text,
        conversation_id=conv,
        visitor_biz_id=visitor,
        ws_token=token,
    )


def load_adp_config_from_env() -> tuple[str, str, str, str]:
    """从环境变量读取 ADP 与腾讯云密钥配置。"""
    sid = os.environ.get("TENCENT_SECRET_ID", "").strip()
    skey = os.environ.get("TENCENT_SECRET_KEY", "").strip()
    region = os.environ.get("TENCENT_REGION", "ap-guangzhou").strip()
    app_key = os.environ.get("TENCENT_ADP_APP_KEY", "").strip()
    if not sid or not skey:
        raise RuntimeError("请配置环境变量 TENCENT_SECRET_ID 与 TENCENT_SECRET_KEY")
    if not app_key:
        raise RuntimeError("请配置环境变量 TENCENT_ADP_APP_KEY")
    return sid, skey, region, app_key
