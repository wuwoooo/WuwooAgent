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
4. 帮助客户逐步明确出行需求，例如目的地、时间、人数、预算、偏好等；
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
6. 客户明确要求定制方案、报价、下单、确认安排时，不要现场编造完整行程或价格，只输出一句自然的确认过渡话术，并在回复末尾追加 [HANDOFF]。
7. 只有客户还在普通咨询、闲聊或补充偏好时，才继续正常解答或轻量追问；一旦客户表达“可以安排/出方案/报价/就这个”等推进意图，必须触发 [HANDOFF]。
8. 不要输出“根据参考资料”“根据知识库”“参考文档”“资料显示”等字样，也不要输出任何引用标记、标签（如 <reference>等）。
9. 【绝对禁止】在每句话开头说“xx你好”、“xx晚上好”等客套问候语！这是连续的微信聊天，请像真人一样直接接话，开门见山回答问题。如确实需要称呼客户，只能以系统提示中给出的联系人名为准，不要使用历史聊天记录中的名字（可能存在 OCR 同音字误差）。"""


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


def is_greeting_or_short(text: str) -> bool:
    """如果只是一些常规简短寒暄，则跳过知识库检索以提升响应速度。"""
    cleaned = text.strip()
    if not cleaned:
        return True
    
    # 极短词汇且仅包含寒暄意图
    if len(cleaned) <= 4:
        # 直接匹配常见短语
        common_phrases = {"你好", "在吗", "在不在", "有人吗", "谢谢", "好的", "好", "可以", "嗯", "嗯嗯", "ok", "OK", "哈喽", "hi", "hello"}
        if cleaned.lower() in common_phrases:
            return True
            
    # 正则匹配常见的开头寒暄 (不超过 6 个字)
    if len(cleaned) <= 6:
        patterns = ["^哈喽", "^hello", "^hi", "^你好", "^在吗", "^收到", "^谢谢"]
        for p in patterns:
            if re.match(p, cleaned, re.IGNORECASE):
                return True
                
    return False


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
    
    # 低价值短语直接拦截检索，压榨速度极限
    if is_greeting_or_short(pure_user_text):
        search_payload = {}
        knowledge_context = ""
    else:
        search_payload = search_knowledge(pure_user_text, cfg)
        knowledge_context = build_knowledge_context(search_payload)
    
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
        
    # 拼接历史记录和最新一条消息
    messages.extend(history)
    messages.append({"role": "user", "content": user_message})
    
    reply = chat_completion(messages, cfg)
    
    return reply, {
        "provider": "volc_knowledge",
        "messages": messages,
        "search_payload": search_payload,
    }
