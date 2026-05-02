import asyncio
import json
import logging
import os
import requests
import re
import time
from typing import List, Dict, Any, Set
from dotenv import load_dotenv
from database import (
    get_contact,
    get_session,
    get_session_messages,
    merge_contact_memory,
    update_session_profile,
)

# 加载环境变量
load_dotenv()

logger = logging.getLogger(__name__)

# 保存后台任务的强引用，防止被垃圾回收
background_tasks: Set[asyncio.Task] = set()

# 从环境变量加载配置
API_URL = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
# 从环境变量获取 API Key 和模型名称
_API_KEY = os.getenv("VOLC_ARK_API_KEY", "")
AUTHORIZATION = f"Bearer {_API_KEY}"
MODEL = os.getenv("VOLC_ARK_MODEL", "doubao-seed-2-0-mini-260215")

PROFILE_PROMPT = """你是一个旅游定制 analysis 助手，你的任务是从下面的微信聊天记录中提取出用户的画像特征。
请务必返回一个纯 JSON 结构，不要包含任何额外的多余说明、markdown 格式或代码块标记（如 ```json），只需返回合法的 JSON 字符串。
需要的 JSON 字段：
{
  "destination": "倾向的目的地，如果没有提请留空或无",
  "budget": "预算情况",
  "people_count": "出行人数",
  "travel_time": "出行时间/天数",
  "preferences": "用户偏好或关注点（数组形式），如 ['品质游', '不早起']",
  "sales_stage": "当前销售阶段（如：初步咨询 / 需求明确 / 意向强烈 / 已出方案 / 成交 / 拒绝）"
}
"""

CONTACT_MEMORY_PROMPT = """你是一个联系人长期记忆抽取助手。请从下面的微信聊天记录中，只抽取适合长期保存、未来继续沟通会有帮助的信息。
不要抽取一次性寒暄，不要编造，不确定的信息要降低 confidence。
请务必返回纯 JSON，不要包含 markdown 或解释文字。

JSON 结构：
{
  "stable_profile": {
    "称呼": {"value": "客户喜欢被如何称呼", "confidence": "high/medium/low"},
    "家庭结构": {"value": "如亲子、老人同行等长期背景", "confidence": "high/medium/low"},
    "消费偏好": {"value": "如重视品质、价格敏感、喜欢自由度", "confidence": "high/medium/low"},
    "沟通风格": {"value": "如喜欢直接、不要太官方、需要详细解释", "confidence": "high/medium/low"},
    "禁忌": {"value": "未来沟通需要避免的点", "confidence": "high/medium/low"}
  },
  "dynamic_state": {
    "最近关注点": {"value": "当前最关心的问题", "confidence": "high/medium/low"},
    "销售温度": {"value": "冷淡/观望/积极/准备推进", "confidence": "high/medium/low"},
    "下次跟进重点": {"value": "下一步适合怎么跟进", "confidence": "high/medium/low"}
  },
  "facts": [
    {"category": "背景事实", "value": "可审计的具体事实", "confidence": "high/medium/low"}
  ]
}
"""


def _extract_json_object(result_text: str) -> Dict[str, Any]:
    match = re.search(r'\{.*\}', result_text or "", re.DOTALL)
    if not match:
        logger.error(f"未能从模型回复中提取到 JSON 结构. 原文本: {result_text}")
        raise Exception("大模型未返回有效的 JSON 结构，请稍后重试。")
    try:
        parsed = json.loads(match.group())
    except json.JSONDecodeError as je:
        logger.error(f"JSON 解析失败: {je}. 原文本: {result_text}")
        raise Exception(f"大模型返回的 JSON 格式不规范: {je}")
    if not isinstance(parsed, dict):
        raise Exception("大模型返回的 JSON 不是对象结构")
    return parsed

def extract_profile_sync(session_id: str) -> Dict[str, Any]:
    messages = get_session_messages(session_id)
    if not messages:
        return {}
        
    # Get last N messages to save context, e.g. last 20 messages
    recent_msgs = messages[-20:]
    
    chat_text = "\n".join([
        f"{'客户' if msg['role'] == 'user' else '客服'}: {msg['content']}" 
        for msg in recent_msgs
    ])
    
    headers = {
        "Content-Type": "application/json",
        "Authorization": AUTHORIZATION
    }
    
    payload = {
        "model": MODEL,
        "messages": [
            {
                "role": "system",
                "content": PROFILE_PROMPT
            },
            {
                "role": "user",
                "content": f"以下是聊天记录：\n{chat_text}"
            }
        ],
        "temperature": 0.1
    }
    
    last_error = None
    # 增加自动重试机制
    for attempt in range(2):
        try:
            start_time = time.time()
            logger.info(f"开始提取画像 (Session: {session_id}, 尝试 {attempt + 1}, 上下文长度: {len(chat_text)})")
            
            # 使用 (连接超时, 读取超时) 的格式
            response = requests.post(API_URL, headers=headers, json=payload, timeout=(10, 60))
            response.raise_for_status()
            
            data = response.json()
            result_text = data["choices"][0]["message"]["content"]
            
            elapsed = time.time() - start_time
            logger.info(f"画像提取接口返回成功，耗时 {elapsed:.2f}s")
            
            profile_json = _extract_json_object(result_text)
            update_session_profile(session_id, profile_json)
            return profile_json
                
        except requests.exceptions.Timeout:
            last_error = Exception("连接火山引擎接口超时，可能是由于提取内容较多或网络波动，请尝试再次点击。")
            logger.warning(f"第 {attempt + 1} 次尝试请求超时")
        except requests.exceptions.HTTPError as he:
            err_msg = f"API 请求失败(HTTP {response.status_code}): {response.text}"
            last_error = Exception(f"大模型 API 调用失败: {err_msg}")
            logger.error(err_msg)
            break # HTTP 错误通常不重试
        except Exception as e:
            last_error = e
            logger.error(f"画像提取发生未知错误: {e}")
            break
            
        if attempt == 0:
            time.sleep(1) # 重试前稍作等待
            
    if last_error:
        raise last_error
    return {}

async def async_extract_profile(session_id: str):
    return await asyncio.to_thread(extract_profile_sync, session_id)


def extract_contact_memory_sync(session_id: str, contact_id: int | None = None) -> Dict[str, Any]:
    session = get_session(session_id)
    resolved_contact_id = contact_id or (session or {}).get("contact_id")
    if not resolved_contact_id:
        return {}

    messages = get_session_messages(session_id)
    if not messages:
        return {}

    recent_msgs = messages[-24:]
    chat_text = "\n".join([
        f"{'客户' if msg['role'] == 'user' else '我方'}: {msg['content']}"
        for msg in recent_msgs
    ])
    contact = get_contact(int(resolved_contact_id))
    existing_memory = json.dumps((contact or {}).get("memory") or {}, ensure_ascii=False)
    manual_notes = json.dumps((contact or {}).get("manual_notes") or {}, ensure_ascii=False)

    headers = {
        "Content-Type": "application/json",
        "Authorization": AUTHORIZATION
    }
    payload = {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": CONTACT_MEMORY_PROMPT},
            {
                "role": "user",
                "content": (
                    f"已有人工补充资料：\n{manual_notes}\n\n"
                    f"已有长期记忆：\n{existing_memory}\n\n"
                    f"最新聊天记录：\n{chat_text}"
                ),
            },
        ],
        "temperature": 0.1
    }

    last_error = None
    for attempt in range(2):
        try:
            start_time = time.time()
            logger.info(
                f"开始提取联系人长期记忆 (Session: {session_id}, Contact: {resolved_contact_id}, 尝试 {attempt + 1})"
            )
            response = requests.post(API_URL, headers=headers, json=payload, timeout=(10, 60))
            response.raise_for_status()
            data = response.json()
            result_text = data["choices"][0]["message"]["content"]
            extracted = _extract_json_object(result_text)
            contact = merge_contact_memory(int(resolved_contact_id), extracted, session_id=session_id)
            elapsed = time.time() - start_time
            logger.info(f"联系人长期记忆合并成功，耗时 {elapsed:.2f}s")
            return (contact or {}).get("memory") or {}
        except requests.exceptions.Timeout:
            last_error = Exception("连接火山引擎接口超时，联系人长期记忆提取未完成。")
            logger.warning(f"联系人长期记忆第 {attempt + 1} 次尝试请求超时")
        except requests.exceptions.HTTPError as he:
            err_msg = f"API 请求失败(HTTP {response.status_code}): {response.text}"
            last_error = Exception(f"大模型 API 调用失败: {err_msg}")
            logger.error(err_msg)
            break
        except Exception as e:
            last_error = e
            logger.error(f"联系人长期记忆提取发生未知错误: {e}")
            break

        if attempt == 0:
            time.sleep(1)

    if last_error:
        raise last_error
    return {}


async def async_extract_contact_memory(session_id: str, contact_id: int | None = None):
    return await asyncio.to_thread(extract_contact_memory_sync, session_id, contact_id)


async def async_update_profile_and_contact_memory(session_id: str, contact_id: int | None = None):
    profile = await async_extract_profile(session_id)
    try:
        await async_extract_contact_memory(session_id, contact_id=contact_id)
    except Exception as e:
        logger.warning(f"联系人长期记忆更新失败，不影响会话画像: {e}")
    return profile


async def check_and_trigger_profile_update(session_id: str, contact_id: int | None = None):
    """
    Check if we need to trigger a background profile update.
    We convert msg_count to interaction rounds (user + assistant = 1 round).
    """
    messages = get_session_messages(session_id)
    msg_count = len(messages)
    rounds = msg_count // 2
    
    # 策略：第 2 轮（约 3~4 条消息）触发首次提取，之后每 3 轮再去提取一次
    if rounds == 2 or (rounds > 2 and rounds % 3 == 0):
        task = asyncio.create_task(async_update_profile_and_contact_memory(session_id, contact_id=contact_id))
        background_tasks.add(task)
        task.add_done_callback(background_tasks.discard)
