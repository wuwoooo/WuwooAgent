import asyncio
import json
import logging
import os
import requests
import re
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
        
        # 更加鲁棒的 JSON 提取：使用正则匹配第一个 { 到最后一个 }
        match = re.search(r'\{.*\}', result_text, re.DOTALL)
        if match:
            json_str = match.group()
            try:
                profile_json = json.loads(json_str)
                update_session_profile(session_id, profile_json)
                return profile_json
            except json.JSONDecodeError as je:
                logger.error(f"JSON 解析失败: {je}. 原文本: {result_text}")
                raise Exception(f"大模型返回的 JSON 格式不规范: {je}")
        else:
            logger.error(f"未能从模型回复中提取到 JSON 结构. 原文本: {result_text}")
            raise Exception("大模型未返回有效的 JSON 结构，请稍后重试。")
            
    except requests.exceptions.HTTPError as he:
        err_msg = f"API 请求失败(HTTP {response.status_code}): {response.text}"
        logger.error(err_msg)
        raise Exception(f"大模型 API 调用失败: {err_msg}")
    except Exception as e:
        logger.error(f"画像提取发生未知错误: {e}")
        raise e

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
    
    # 策略改成：第 2 轮（约 3~4 条消息）触发首次提取，之后每 3 轮再去提取一次
    if rounds == 2 or (rounds > 2 and rounds % 3 == 0):
        task = asyncio.create_task(async_extract_profile(session_id))
        background_tasks.add(task)
        task.add_done_callback(background_tasks.discard)
