from __future__ import annotations

import json
from collections import defaultdict
import os
import re
import time
from typing import Any

import requests
from volcengine.Credentials import Credentials
from volcengine.auth.SignerV4 import SignerV4
from volcengine.base.Request import Request


BASE_PROMPT = """# 角色
你不是机械客服，也不是知识库机器人。
你是云南云鹿旅行社的高级旅游顾问“小鹿”，正在微信里和客户进行一对一聊天沟通。

# 目标
你的目标不是生硬回答问题，而是：
1. 用自然、真实、有人味的中文和客户交流；
2. 结合参考资料回答客户与旅行相关的问题；
3. 在合适的时候推进咨询，让客户愿意继续沟通；
4. 帮助客户逐步明确出行需求，尤其是出行人数、出行时间、出发地、是否有老人/孩子同行、是否有病患或腿脚不便者，再逐步了解目的地、预算、偏好等；
5. 让客户感受到专业、靠谱、耐心，而不是模板化客服话术。

# 核心原则
如果提供了参考资料（<context>标签内），你必须优先依据参考资料回答问题。
如果参考资料中有足够信息，应基于参考资料作答；
如果参考资料不足，不要编造，不要强行回答。

# 回答风格要求与限制
1. 像真人聊天，简短自然，1 到 3 句即可，绝不长篇大论。
2. 严禁重复固定开场白，严禁重复自我介绍，严禁再次索要微信。
3. 优先先回应客户当前情绪或问题，如果客户是在拒绝、质疑、抱怨或表达不方便，要先顺着客户的话回应，不要答非所问。
4. 除非用户明确要求，不要使用分点、编号、大段说明、Markdown 或小标题。
5. 不要输出分析过程，不要输出标题，不要加括号说明，只输出最终要发给客户的话。
6. 客户明确要求最终定制方案、最终报价、下单、确认安排时，必须先检查是否已掌握：出行人数、出行时间、出发地、是否有老人/孩子同行、是否有病患或腿脚不便者。信息齐全时，不要现场编造完整行程或价格，只输出一句自然的确认过渡话术，并在回复末尾追加 [HANDOFF]。
7. 如果客户表达“可以安排/出方案/报价/就这个”等推进意图，但上述基础信息仍不完整，不要追加 [HANDOFF]，而是先用一句自然话术补问缺失项；其中老人/孩子、病患/腿脚不便需要明确问有或没有，不能默认没有。
8. 只有客户还在普通咨询、闲聊或补充偏好时，才继续正常解答或轻量追问。
9. 不要输出“根据参考资料”“根据知识库”“参考文档”“资料显示”等字样，也不要输出任何引用标记、标签（如 <reference>等）。
10. 当前实际时间只用于判断今天/明天/下周和避免过期建议，不能把当前日期默认当成客户出行日期；客户没明确出行日期时，不要说“安排进今天/5月4日行程”，要先问计划哪天去。
11. 如果当前时段已经过去，不要建议客户今天在已过去的时段出发、拍照或游玩；例如傍晚不能再排今天上午9点。
12. 不要承诺具体出方案/报价交付时间，例如“下午3点前发您”“半小时后给您”；只能说“我整理好后发您确认”“核算清楚后发您”。
13. 【绝对禁止】在每句话开头说“xx你好”、“xx晚上好”等客套问候语！这是连续的微信聊天，请像真人一样直接接话，开门见山回答问题。如确实需要称呼客户，只能以系统提示中给出的联系人名为准，不要使用历史聊天记录中的名字（可能存在 OCR 同音字误差）。"""


def load_volc_config_from_env() -> dict[str, Any]:
    cfg = {
        "ak": os.environ.get("VOLCENGINE_AK", "").strip(),
        "sk": os.environ.get("VOLCENGINE_SK", "").strip(),
        "account_id": os.environ.get("VOLCENGINE_ACCOUNT_ID", "").strip(),
        "domain": os.environ.get("VOLCENGINE_KNOWLEDGE_DOMAIN", "api-knowledgebase.mlp.cn-beijing.volces.com").strip(),
        "schema": os.environ.get("VOLCENGINE_KNOWLEDGE_SCHEMA", "https").strip() or "https",
        "project": os.environ.get("VOLCENGINE_PROJECT_NAME", "default").strip() or "default",
        "collection": os.environ.get("VOLCENGINE_COLLECTION_NAME", "").strip(),
        "model": os.environ.get("VOLCENGINE_MODEL", "Deepseek-v3").strip() or "Deepseek-v3",
        "model_version": os.environ.get("VOLCENGINE_MODEL_VERSION", "250324").strip() or "250324",
        "temperature": float(os.environ.get("VOLCENGINE_TEMPERATURE", "0.7") or "0.7"),
        "max_tokens": int(os.environ.get("VOLCENGINE_MAX_TOKENS", "250") or "250"),
        "search_limit": int(os.environ.get("VOLCENGINE_SEARCH_LIMIT", "3") or "3"),
    }
    required = ["ak", "sk", "account_id", "collection"]
    missing = [key for key in required if not cfg[key]]
    if missing:
        raise RuntimeError(f"请配置火山知识库环境变量: {', '.join('VOLCENGINE_' + key.upper() for key in missing)}")
    return cfg


def prepare_request(
    *,
    method: str,
    path: str,
    ak: str,
    sk: str,
    domain: str,
    account_id: str,
    schema: str = "https",
    params: dict[str, Any] | None = None,
    data: dict[str, Any] | None = None,
) -> Request:
    if params:
        for key in list(params.keys()):
            value = params[key]
            if isinstance(value, (int, float, bool)):
                params[key] = str(value)
            elif isinstance(value, list):
                params[key] = ",".join(str(v) for v in value)

    r = Request()
    r.set_shema(schema)
    r.set_method(method)
    r.set_connection_timeout(10)
    r.set_socket_timeout(30)
    r.set_headers(
        {
            "Accept": "application/json",
            "Content-Type": "application/json; charset=utf-8",
            "Host": domain,
            "V-Account-Id": account_id,
        },
    )
    if params:
        r.set_query(params)
    r.set_host(domain)
    r.set_path(path)
    if data is not None:
        r.set_body(json.dumps(data, ensure_ascii=False))

    credentials = Credentials(ak, sk, "air", "cn-north-1")
    SignerV4.sign(r, credentials)
    return r


def _request_json(
    *,
    method: str,
    path: str,
    cfg: dict[str, Any],
    data: dict[str, Any] | None = None,
) -> dict[str, Any]:
    req = prepare_request(
        method=method,
        path=path,
        ak=cfg["ak"],
        sk=cfg["sk"],
        domain=cfg["domain"],
        account_id=cfg["account_id"],
        schema=cfg["schema"],
        data=data,
    )
    last_error: RuntimeError | None = None
    for attempt in range(3):
        response = requests.request(
            method=req.method,
            url=f"{cfg['schema']}://{cfg['domain']}{req.path}",
            headers=req.headers,
            data=req.body,
            timeout=40,
        )
        response.encoding = "utf-8"
        payload: dict[str, Any] | None = None
        try:
            payload = response.json()
        except Exception:
            payload = None

        if response.status_code in range(200, 300):
            code = (payload or {}).get("code", 0)
            if code in (0, "0", None):
                return payload or {}
            last_error = RuntimeError(f"火山接口返回异常 code={code}: {payload}")
        else:
            last_error = RuntimeError(f"火山接口失败({response.status_code}): {response.text}")

        retryable = response.status_code == 429 or ((payload or {}).get("code") == 1000029)
        if not retryable or attempt == 2:
            break
        time.sleep(1.2 * (attempt + 1))

    raise last_error or RuntimeError("火山接口请求失败")


def search_knowledge(query: str, cfg: dict[str, Any]) -> dict[str, Any]:
    request_params = {
        "project": cfg["project"],
        "name": cfg["collection"],
        "query": query,
        "limit": cfg["search_limit"],
        "pre_processing": {
            "need_instruction": False,
            "return_token_usage": False,
            "rewrite": False,
        },
        "post_processing": {
            "get_attachment_link": False,
            "rerank_only_chunk": False,
            "rerank_switch": False,
            "chunk_group": True,
            "retrieve_count": max(cfg["search_limit"], 10),
            "chunk_diffusion_count": 0,
        },
        "dense_weight": 0.5,
    }
    return _request_json(
        method="POST",
        path="/api/knowledge/collection/search_knowledge",
        cfg=cfg,
        data=request_params,
    )


def get_content_for_prompt(point: dict[str, Any]) -> str:
    content = str(point.get("content") or "").strip()
    original_question = str(point.get("original_question") or "").strip()
    if original_question and content:
        return f"相似问题：{original_question}\n参考答案：{content}"
    return content


def is_pure_social_phrase(text: str) -> bool:
    """只有纯寒暄、感谢等低业务信息短语才跳过知识库检索。"""
    cleaned = text.strip()
    if not cleaned:
        return True

    common_phrases = {
        "你好", "您好", "在吗", "在不在", "有人吗", "谢谢", "谢谢你", "谢谢啦",
        "辛苦了", "收到", "嗯嗯收到", "哈喽", "hello", "hi",
    }
    if cleaned.lower() in common_phrases:
        return True

    # 正则匹配常见的开头寒暄（不超过 6 个字）
    if len(cleaned) <= 6:
        patterns = ["^哈喽", "^hello", "^hi", "^你好", "^您好", "^在吗", "^谢谢"]
        for p in patterns:
            if re.match(p, cleaned, re.IGNORECASE):
                return True

    return False


def is_greeting_or_short(text: str) -> bool:
    """兼容旧调用名：不再按“短”跳过，只判断纯寒暄。"""
    return is_pure_social_phrase(text)


def is_fallback_assistant_message(text: str) -> bool:
    cleaned = text.strip()
    if not cleaned:
        return True
    fallback_keywords = [
        "没听清", "没看清", "再发一遍", "打字告诉", "发一遍或者打字",
        "用户上传了一张聊天截图", "发了一条语音", "系统未能读取到文字内容",
    ]
    return any(keyword in cleaned for keyword in fallback_keywords)


def is_placeholder_user_message(text: str) -> bool:
    cleaned = text.strip()
    if not cleaned:
        return True
    placeholder_keywords = [
        "用户上传了一张聊天截图", "发了一条语音", "系统未能读取到文字内容",
    ]
    return any(keyword in cleaned for keyword in placeholder_keywords)


def is_low_information_message(text: str) -> bool:
    cleaned = text.strip()
    if not cleaned:
        return True
    compact = re.sub(r"[\s,，。.!！?？、；;：:~～]+", "", cleaned.lower())
    if not compact:
        return True
    if len(compact) <= 12:
        return True
    return False


def find_recent_substantive_message(history_messages: list[dict[str, Any]], role: str) -> str:
    for msg in reversed(history_messages):
        if msg.get("role") != role:
            continue
        content = str(msg.get("content") or "").strip()
        if role == "assistant" and is_fallback_assistant_message(content):
            continue
        if role == "user" and is_placeholder_user_message(content):
            continue
        if content:
            return content
    return ""


def format_context_for_interpretation(history_messages: list[dict[str, Any]], limit: int = 6) -> str:
    rows: list[str] = []
    for msg in history_messages[-limit:]:
        role = msg.get("role")
        content = str(msg.get("content") or "").strip()
        if not content or role not in {"user", "assistant"}:
            continue
        if role == "user" and is_placeholder_user_message(content):
            label = "客户[系统占位]"
        elif role == "assistant" and is_fallback_assistant_message(content):
            label = "Agent[系统兜底]"
        elif role == "user":
            label = "客户"
        else:
            label = "Agent"
        rows.append(f"{label}：{content[:220]}")
    return "\n".join(rows)


def build_context_bridge(
    pure_user_text: str,
    history_messages: list[dict[str, Any]],
) -> tuple[str, str, dict[str, str]]:
    """整理短句/符号消息的上下文，不替模型硬判意图。"""
    latest_is_low_info = is_low_information_message(pure_user_text)
    recent_history = history_messages[-6:]
    has_fallback_noise = any(
        (msg.get("role") == "assistant" and is_fallback_assistant_message(str(msg.get("content") or ""))) or
        (msg.get("role") == "user" and is_placeholder_user_message(str(msg.get("content") or "")))
        for msg in recent_history
    )
    recent_user = find_recent_substantive_message(history_messages, "user")
    recent_assistant = find_recent_substantive_message(history_messages, "assistant")

    if latest_is_low_info and (recent_user or recent_assistant):
        search_query = "\n".join(
            part for part in [
                f"最近实质客户消息：{recent_user}" if recent_user else "",
                f"最近实质Agent回复：{recent_assistant}" if recent_assistant else "",
                f"客户最新短消息：{pure_user_text}",
            ] if part
        )
    else:
        search_query = pure_user_text

    if not (latest_is_low_info or has_fallback_noise):
        return search_query, "", {
            "latest_is_low_info": str(latest_is_low_info),
            "has_fallback_noise": str(has_fallback_noise),
            "recent_user": recent_user,
            "recent_assistant": recent_assistant,
        }

    annotated_context = format_context_for_interpretation(history_messages)
    instruction = (
        "【上下文理解提示】客户最新消息可能是短句、符号、追问、确认、质疑或纠偏。"
        "不要孤立理解客户最新消息，请结合最近聊天记录判断其真实意图，并优先回应最近的有效业务上下文。"
        "标注为“系统占位”或“系统兜底”的消息只代表 OCR/VLM 未识别或系统兜底话术，除非客户明确回应它，否则不要把它当成业务问题。"
        "如果客户像是在同意上一条提议，请继续上一条提议；如果像是在质疑或不满上一条回复，请先解释、纠偏或致歉；如果是在追问，请围绕最近有效话题回答。\n"
        f"最近实质客户消息：{recent_user or '无'}\n"
        f"最近实质Agent回复：{recent_assistant or '无'}\n"
        f"最近上下文标注：\n{annotated_context or '无'}"
    )
    return search_query, instruction, {
        "latest_is_low_info": str(latest_is_low_info),
        "has_fallback_noise": str(has_fallback_noise),
        "recent_user": recent_user,
        "recent_assistant": recent_assistant,
    }


def build_knowledge_context(search_payload: dict[str, Any]) -> str:
    rsp_data = search_payload.get("data") or {}
    points = rsp_data.get("result_list") or []
    prompt_parts: list[str] = []

    for idx, point in enumerate(points, start=1):
        body = get_content_for_prompt(point)
        if not body:
            continue
        doc_info = point.get("doc_info") or {}
        title = (
            str(doc_info.get("title") or "").strip()
            or str(point.get("chunk_title") or "").strip()
            or str(doc_info.get("chunk_title") or "").strip()
        )
        if title:
            prompt_parts.append(f"资料片段{idx}（主题：{title}）\n{body}")
        else:
            prompt_parts.append(f"资料片段{idx}\n{body}")

    if not prompt_parts:
        return ""
        
    context_body = "\n\n".join(prompt_parts)
    return f"# 参考资料\n<context>\n{context_body}\n</context>"


def _extract_chat_content(payload: dict[str, Any]) -> str:
    choices = payload.get("choices") or []
    if choices:
        message = choices[0].get("message") or {}
        content = message.get("content")
        if isinstance(content, str):
            return content
        if isinstance(content, list):
            parts: list[str] = []
            for item in content:
                if isinstance(item, dict) and item.get("type") == "text":
                    parts.append(str(item.get("text") or ""))
            return "\n".join(parts).strip()
    data = payload.get("data") or {}
    if isinstance(data, dict):
        generated_answer = data.get("generated_answer")
        if isinstance(generated_answer, str) and generated_answer.strip():
            return generated_answer
        message = data.get("message") or {}
        content = message.get("content")
        if isinstance(content, str):
            return content
    return ""


def sanitize_reply_text(text: str) -> str:
    cleaned = (text or "").strip()
    if not cleaned:
        return ""
    cleaned = re.sub(r"<reference[^>]*></reference>", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"</?[^>]+>", "", cleaned)
    cleaned = re.sub(r"参考文档[:：].*", "", cleaned)
    cleaned = re.sub(r"参考资料[:：].*", "", cleaned)
    cleaned = re.sub(r"根据参考资料[，,：:]?", "", cleaned)
    cleaned = re.sub(r"根据知识库[，,：:]?", "", cleaned)
    cleaned = re.sub(r"资料显示[，,：:]?", "", cleaned)
    cleaned = re.sub(r"point_id[:：]?[^\s，,。；;]*", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"doc_name[:：]?[^\n]*", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"title[:：]?[^\n]*", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"\n{2,}", "\n", cleaned)
    cleaned = re.sub(r"[ \t]{2,}", " ", cleaned)
    cleaned = cleaned.strip()
    
    # 防系统提示词泄漏检测
    leak_keywords = ["系统提示", "称呼规则", "HANDOFF 规则", "代聊微信联系人", "系统提供的联系人名", "当前实际时间是"]
    for kw in leak_keywords:
        if kw in cleaned:
            return "收到～"
            
    return cleaned


def chat_completion(messages: list[dict[str, Any]], cfg: dict[str, Any]) -> str:
    payload = _request_json(
        method="POST",
        path="/api/knowledge/chat/completions",
        cfg=cfg,
        data={
            "messages": messages,
            "stream": False,
            "return_token_usage": True,
            "model": cfg["model"],
            "max_tokens": cfg["max_tokens"],
            "temperature": cfg["temperature"],
            "model_version": cfg["model_version"],
        },
    )
    reply = sanitize_reply_text(_extract_chat_content(payload))
    if not reply:
        raise RuntimeError(f"火山模型未返回可用回复: {payload}")
    return reply


try:
    from database import get_session_messages
except ImportError:
    pass

MAX_HISTORY_TURNS = 5  # 保留最近5轮对话（10条消息）


def run_volc_knowledge_chat(
    *,
    pure_user_text: str,
    user_message: str,
    system_prompt_addition: str,
    contact_name: str,
    session_id: str,
    contact_context: str = "",
) -> tuple[str, dict[str, Any]]:
    cfg = load_volc_config_from_env()

    # 从数据库提取历史记录，并截取最近 MAX_HISTORY_TURNS 轮
    try:
        db_messages = get_session_messages(session_id)
        # 获取最近 N 条记录，并且转化为模型可识别的 format
        history = [
            {"role": msg["role"], "content": msg["content"]} 
            for msg in db_messages[-MAX_HISTORY_TURNS * 2:]
            if msg["role"] in ["user", "assistant"]
        ]
    except Exception:
        # 兼容未接入数据库的错误情况
        history = []

    context_search_query, context_instruction, context_meta = build_context_bridge(
        pure_user_text,
        history,
    )

    # 只有纯寒暄/感谢跳过检索；短句、符号、追问等用上下文桥接后的 query 检索。
    if is_pure_social_phrase(pure_user_text):
        search_payload = {}
        knowledge_context = ""
        search_query = ""
        search_skipped_reason = "pure_social_phrase"
        context_instruction = ""
    else:
        search_query = context_search_query
        search_payload = search_knowledge(search_query, cfg)
        knowledge_context = build_knowledge_context(search_payload)
        search_skipped_reason = ""
    
    # 彻底拆分系统规则、知识库检索内容，把变动部分放在后面，确保深度命中 Prefix Caching
    messages = [
        {"role": "system", "content": BASE_PROMPT},
    ]
    if knowledge_context:
        messages.append({"role": "system", "content": knowledge_context})
    if contact_context:
        messages.append({"role": "system", "content": contact_context})
    if system_prompt_addition:
        messages.append({"role": "system", "content": system_prompt_addition})
    if context_instruction:
        messages.append({"role": "system", "content": context_instruction})
        
    # 拼接历史记录和最新一条消息
    messages.extend(history)
    messages.append({"role": "user", "content": user_message})
    
    reply = chat_completion(messages, cfg)
    
    return reply, {
        "provider": "volc_knowledge",
        "messages": messages,
        "search_payload": search_payload,
        "search_query": search_query,
        "search_skipped_reason": search_skipped_reason,
        "context_bridge": context_meta,
    }
