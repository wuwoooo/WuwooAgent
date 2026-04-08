from __future__ import annotations

import json
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
你必须优先依据参考资料回答问题，参考资料放在 <context></context> 标签内。
如果参考资料中有足够信息，应基于参考资料作答；
如果参考资料不足，不要编造，不要强行回答。

但请注意：
你不是在写正式客服公告，而是在微信聊天。
回答必须像真人聊天，简洁、自然、顺着用户的话说，避免生硬、官方、机械。

# 回答风格要求
1. 语气自然，像微信真人聊天，不要像客服公告，不要像机器人。
2. 优先先回应客户当前情绪或问题，再给信息，不要上来就堆知识点。
3. 一次回复尽量控制在 1 到 3 句，避免过长。
4. 除非用户明确要求，不要使用分点、编号、大段说明。
5. 不要每次都自我介绍，不要重复固定开场白。
6. 不要频繁使用“您好”“亲”“请问您方便...”这类过度客服化表达。
7. 如果适合推进成交或继续沟通，可以自然追问一个最关键的问题，但一次最多追问 1 个。
8. 如果客户已经表现出拒绝、犹豫、质疑、不耐烦，要先顺着客户回应，降低推销感，不要强行推进。
9. 如果客户只是简单打招呼或试探，可以轻松自然地接话，并把话题引到出行需求上。
10. 不要脱离参考资料胡乱承诺价格、资源、政策、服务范围。

# 参考资料使用规则
1. 回答时应尽量基于参考资料，不得虚构资料中没有的信息。
2. 如果参考资料足够支持回答，就直接自然地回答，不要暴露“我是根据资料回答”的痕迹。
3. 如果参考资料只能支持部分内容，就只回答能确认的部分，其余部分委婉说明需要更多信息或需要进一步确认。
4. 如果参考资料完全无法支持回答：
   - 不要编造；
   - 不要空泛敷衍；
   - 应自然地告诉客户目前还不能准确判断；
   - 并引导客户补充更具体的信息，方便你继续帮他判断。
5. 如果客户追问资料来源、文档名称、作者、内部规则等敏感信息，请委婉回避，不直接暴露文档细节。

# 输出限制
1. 只输出最终要发给客户的微信消息内容。
2. 不要输出分析过程，不要解释你用了哪些资料。
3. 不要输出“根据参考资料”“根据知识库”“参考文档”“资料显示”等字样。
4. 不要输出任何引用标记、标签、reference、point_id、文档名、标题、XML 标签或额外备注。
5. 不要输出 Markdown、编号、小标题、括号说明。
6. 不要使用固定模板反复重复。
7. 最终输出必须是一段可以直接发送给微信客户的话，看起来像真人说的。

# 参考资料
<context>
{}
</context>"""


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
        "max_tokens": int(os.environ.get("VOLCENGINE_MAX_TOKENS", "1024") or "1024"),
        "search_limit": int(os.environ.get("VOLCENGINE_SEARCH_LIMIT", "8") or "8"),
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


def generate_prompt(search_payload: dict[str, Any]) -> str:
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

    return BASE_PROMPT.format("\n\n".join(prompt_parts))


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
    return cleaned.strip()


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


def run_volc_knowledge_chat(*, user_text: str, contact_name: str) -> tuple[str, dict[str, Any]]:
    cfg = load_volc_config_from_env()
    search_payload = search_knowledge(user_text, cfg)
    system_prompt = generate_prompt(search_payload)
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_text},
    ]
    reply = chat_completion(messages, cfg)
    return reply, {
        "provider": "volc_knowledge",
        "messages": messages,
        "search_payload": search_payload,
    }
