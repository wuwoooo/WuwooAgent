import asyncio
import json
import logging
import os
import requests
from typing import List, Dict, Any, Set
from dotenv import load_dotenv
from database import get_session_messages, update_session_profile, get_session_profile

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

PROFILE_PROMPT = """你是一个旅游定制分析助手，你的任务是从下面的微信聊天记录中提取出用户的画像特征。
请务必返回一个纯JSON结构，不要包含任何额外的多余说明、markdown格式或代码块标记（如```json），只需返回合法的JSON字符串。
需要的JSON字段：
{
  "destination": "倾向的目的地，如果没有提请留空或无",
  "budget": "预算情况",
  "people_count": "出行人数",
  "travel_time": "出行时间/天数",
  "preferences": "用户偏好或关注点（数组形式），如 ['品质游', '不早起']",
  "sales_stage": "当前销售阶段（如：初步咨询 / 需求明确 / 意向强烈 / 已出方案 / 成交 / 拒绝）"
}
"""

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
    
    try:
        response = requests.post(API_URL, headers=headers, json=payload, timeout=30)
        response.raise_for_status()
        data = response.json()
        result_text = data["choices"][0]["message"]["content"]
        
        # Clean json if the model adds markdown
        result_text = result_text.strip()
        if result_text.startswith("```json"):
            result_text = result_text[7:]
        if result_text.endswith("```"):
            result_text = result_text[:-3]
        result_text = result_text.strip()
            
        profile_json = json.loads(result_text)
        update_session_profile(session_id, profile_json)
        return profile_json
    except Exception as e:
        logger.error(f"Failed to extract profile: {e}")
        return {}

async def async_extract_profile(session_id: str):
    return await asyncio.to_thread(extract_profile_sync, session_id)

async def check_and_trigger_profile_update(session_id: str):
    """
    Check if we need to trigger a background profile update.
    We convert msg_count to interaction rounds (user + assistant = 1 round).
    """
    messages = get_session_messages(session_id)
    msg_count = len(messages)
    rounds = msg_count // 2
    
    # 策略改成：第2轮（约3~4条消息）触发首次提取，之后每 3 轮再去提取一次
    # 也可根据需要改成如果超过 5 轮就每轮提取：(rounds > 5)
    if rounds == 2 or (rounds > 2 and rounds % 3 == 0):
        task = asyncio.create_task(async_extract_profile(session_id))
        background_tasks.add(task)
        task.add_done_callback(background_tasks.discard)

