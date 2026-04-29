import asyncio
import json
import logging
import os
import re
import time
from typing import Any, Dict

import requests
from dotenv import load_dotenv

from database import get_session_messages

load_dotenv()

logger = logging.getLogger(__name__)

API_URL = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
_API_KEY = os.getenv("VOLC_ARK_API_KEY", "")
AUTHORIZATION = f"Bearer {_API_KEY}"
MODEL = os.getenv("VOLC_ARK_MODEL", "doubao-seed-2-0-mini-260215")

SUMMARY_PROMPT = """你是一个旅游定制人工接管助手。你的任务是根据微信聊天记录，为即将接手的人工客服快速总结当前会话。
请务必返回一个纯 JSON 结构，不要包含任何额外说明、markdown 格式或代码块标记，只需返回合法的 JSON 字符串。
需要的 JSON 字段：
{
  "overview": "用 1-2 句话概括客户当前诉求和会话进展",
  "confirmed_info": ["已经明确的信息，如目的地、人数、时间、预算、偏好等"],
  "pending_questions": ["仍需向客户确认的问题"],
  "customer_sentiment": "客户情绪或意向强度，如：观望/积极/犹豫/催促/不满",
  "latest_status": "最近几轮对话正在讨论什么，卡点是什么",
  "suggested_next_reply": "人工客服接手后建议发出的下一句话，语气自然、简短"
}
"""


def _normalize_limit(limit: int | None, total: int) -> int:
    if not limit:
        return min(total, 100)
    return max(1, min(int(limit), 100, total))


def summarize_conversation_sync(session_id: str, limit: int | None = 20) -> Dict[str, Any]:
    messages = get_session_messages(session_id)
    total_messages = len(messages)
    if not messages:
        return {
            "summary": {},
            "used_message_count": 0,
            "total_message_count": 0,
        }

    used_count = _normalize_limit(limit, total_messages)
    recent_msgs = messages[-used_count:]
    chat_text = "\n".join([
        f"{'客户' if msg['role'] == 'user' else '客服'}: {msg['content']}"
        for msg in recent_msgs
    ])

    headers = {
        "Content-Type": "application/json",
        "Authorization": AUTHORIZATION,
    }
    payload = {
        "model": MODEL,
        "messages": [
            {
                "role": "system",
                "content": SUMMARY_PROMPT,
            },
            {
                "role": "user",
                "content": f"以下是最近 {used_count} 条聊天记录：\n{chat_text}",
            },
        ],
        "temperature": 0.1,
    }

    last_error = None
    for attempt in range(2):
        try:
            start_time = time.time()
            logger.info(
                "开始生成会话总结 (Session: %s, 尝试 %s, 消息数: %s/%s, 上下文长度: %s)",
                session_id,
                attempt + 1,
                used_count,
                total_messages,
                len(chat_text),
            )
            response = requests.post(API_URL, headers=headers, json=payload, timeout=(10, 60))
            response.raise_for_status()

            data = response.json()
            result_text = data["choices"][0]["message"]["content"]
            logger.info("会话总结接口返回成功，耗时 %.2fs", time.time() - start_time)

            match = re.search(r"\{.*\}", result_text, re.DOTALL)
            if not match:
                logger.error("未能从模型回复中提取到 JSON 结构. 原文本: %s", result_text)
                raise Exception("大模型未返回有效的 JSON 结构，请稍后重试。")

            try:
                summary_json = json.loads(match.group())
            except json.JSONDecodeError as exc:
                logger.error("会话总结 JSON 解析失败: %s. 原文本: %s", exc, result_text)
                raise Exception(f"大模型返回的 JSON 格式不规范: {exc}") from exc

            return {
                "summary": summary_json,
                "used_message_count": used_count,
                "total_message_count": total_messages,
            }
        except requests.exceptions.Timeout:
            last_error = Exception("连接火山引擎接口超时，可能是由于总结内容较多或网络波动，请尝试减少条数后重试。")
            logger.warning("第 %s 次会话总结请求超时", attempt + 1)
        except requests.exceptions.HTTPError as exc:
            error_message = f"API 请求失败(HTTP {response.status_code}): {response.text}"
            last_error = Exception(f"大模型 API 调用失败: {error_message}")
            logger.error(error_message)
            break
        except Exception as exc:
            last_error = exc
            logger.error("会话总结发生未知错误: %s", exc)
            break

        if attempt == 0:
            time.sleep(1)

    if last_error:
        raise last_error
    return {
        "summary": {},
        "used_message_count": used_count,
        "total_message_count": total_messages,
    }


async def async_summarize_conversation(session_id: str, limit: int | None = 20):
    return await asyncio.to_thread(summarize_conversation_sync, session_id, limit)
