"""
对比测试：
方案A: VLM OCR 提取 → 知识库 LLM 回复（当前架构）
方案B: 知识库 LLM 直接多模态识别+回复（doubao-seed-2-0-lite-260428）

测试维度：耗时、正确率、回复质量
"""

import base64
import json
import os
import sys
import time
from pathlib import Path
from typing import Any

from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent
load_dotenv(BASE_DIR / ".env.example", override=False)
load_dotenv(BASE_DIR / ".env", override=True)

# 复用项目已有模块
from volc_knowledge_client import (
    BASE_PROMPT,
    build_knowledge_context,
    load_volc_config_from_env,
    search_knowledge,
    sanitize_reply_text,
    _request_json,
    _extract_chat_content,
)

import requests

# ==================== 方案A：VLM OCR + 知识库 LLM ====================

def run_vlm_ocr(image_bytes: bytes) -> dict:
    """阶段1：用 VLM 视觉模型提取微信截图中的聊天内容"""
    api_key = os.environ.get("ARK_VISION_API_KEY")
    model_id = os.environ.get("ARK_VISION_MODEL_ID", "doubao-seed-2-0-mini-260215")
    base64_image = base64.b64encode(image_bytes).decode("utf-8")

    prompt = (
        "你是一个微信界面提取专家。请先判断图片是否为一对一微信聊天页，再提取顶部标题栏的「联系人名称」以及所有的「聊天记录」。\n"
        "1. 绿色气泡表示'我方(agent)'发送的，白色/其他非绿色气泡表示'客户(client)'发送的。\n"
        "2. 请判断该聊天是否为群聊。\n"
        "3. 如果图片不是聊天页面，请把 is_chat_page 设为 false。\n"
        '严格返回一个 JSON 对象，格式如下：\n'
        '{"page_type": "chat", "is_chat_page": true, "contact_name": "张三", "is_group_chat": false, '
        '"messages": [{"sender": "client", "text": "你好"}, {"sender": "agent", "text": "您好"}]}'
    )

    payload = {
        "model": model_id,
        "messages": [{"role": "user", "content": [
            {"type": "text", "text": prompt},
            {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{base64_image}"}}
        ]}],
        "temperature": 0.1,
        "max_tokens": 2048,
    }

    resp = requests.post(
        "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"},
        json=payload,
        timeout=(10, 60),
    )
    content = resp.json()["choices"][0]["message"]["content"].strip()
    # 清理 markdown 包裹
    for prefix in ("```json", "```"):
        if content.startswith(prefix):
            content = content[len(prefix):]
    if content.endswith("```"):
        content = content[:-3]
    return json.loads(content.strip())


def run_knowledge_llm(user_text: str) -> str:
    """阶段2：用 Ark 标准接口 LLM 根据提取的文本生成回复"""
    api_key = os.environ.get("ARK_VISION_API_KEY")
    # 尝试知识库检索（可能已过期）
    knowledge_context = ""
    try:
        cfg = load_volc_config_from_env()
        search_payload = search_knowledge(user_text[-800:], cfg)
        knowledge_context = build_knowledge_context(search_payload)
    except Exception as e:
        print(f"  [知识库检索跳过] {e}")

    messages = [{"role": "system", "content": BASE_PROMPT}]
    if knowledge_context:
        messages.append({"role": "system", "content": knowledge_context})
    messages.append({"role": "user", "content": f"代聊微信联系人。对方刚刚发来的最新消息：\n\n{user_text}"})

    # 使用 Ark 标准接口 + doubao-seed-2-0-mini 模型
    resp = requests.post(
        "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"},
        json={"model": os.environ.get("ARK_VISION_MODEL_ID", "doubao-seed-2-0-mini-260215"), "messages": messages, "temperature": 0.7, "max_tokens": 1024},
        timeout=(10, 60),
    )
    content = resp.json()["choices"][0]["message"]["content"].strip()
    return sanitize_reply_text(content)


def test_plan_a(image_bytes: bytes) -> dict:
    """方案A完整流程"""
    print("\n" + "=" * 60)
    print("方案A: VLM OCR → 知识库 LLM")
    print("=" * 60)

    # 阶段1: VLM OCR
    t1 = time.time()
    vlm_result = run_vlm_ocr(image_bytes)
    t_vlm = time.time() - t1
    print(f"\n[阶段1] VLM OCR 耗时: {t_vlm:.2f}s")
    print(f"  联系人: {vlm_result.get('contact_name', '未识别')}")
    print(f"  是否聊天页: {vlm_result.get('is_chat_page', '?')}")
    print(f"  是否群聊: {vlm_result.get('is_group_chat', '?')}")

    msgs = vlm_result.get("messages", [])
    print(f"  消息数: {len(msgs)}")
    for i, m in enumerate(msgs):
        sender = "客户" if m.get("sender") == "client" else "我方"
        print(f"    [{i+1}] {sender}: {m.get('text', '')[:80]}")

    # 提取最后一条客户消息
    last_client_msgs = [m for m in msgs if m.get("sender") == "client"]
    last_client_text = last_client_msgs[-1]["text"] if last_client_msgs else ""

    # 阶段2: 知识库 LLM
    t2 = time.time()
    reply = run_knowledge_llm(last_client_text)
    t_llm = time.time() - t2
    print(f"\n[阶段2] 知识库 LLM 耗时: {t_llm:.2f}s")
    print(f"  回复: {reply}")

    total = t_vlm + t_llm
    print(f"\n[总耗时] {total:.2f}s (VLM={t_vlm:.2f}s + LLM={t_llm:.2f}s)")

    return {
        "plan": "A",
        "vlm_time": t_vlm,
        "llm_time": t_llm,
        "total_time": total,
        "contact_name": vlm_result.get("contact_name", ""),
        "is_chat_page": vlm_result.get("is_chat_page"),
        "is_group_chat": vlm_result.get("is_group_chat"),
        "messages": msgs,
        "last_client_text": last_client_text,
        "reply": reply,
    }


# ==================== 方案B：知识库 LLM 直接多模态 ====================

def test_plan_b(image_bytes: bytes) -> dict:
    """方案B: 用 Ark 标准接口直接调 doubao-seed-2-0-lite-260428 做多模态识别+回复"""
    print("\n" + "=" * 60)
    print("方案B: 直接多模态 (doubao-seed-2-0-lite-260428)")
    print("=" * 60)
    return test_plan_b_ark_fallback(image_bytes)


def test_plan_b_ark_fallback(image_bytes: bytes) -> dict:
    """方案B回退：用 Ark 标准接口调 doubao-seed-2-0-lite-260428"""
    print("\n--- 方案B (Ark 标准接口回退) ---")
    api_key = os.environ.get("ARK_VISION_API_KEY")
    base64_image = base64.b64encode(image_bytes).decode("utf-8")

    # 尝试知识库检索（可能已过期）
    knowledge_context = ""
    try:
        cfg = load_volc_config_from_env()
        search_query = "微信聊天截图中客户询问旅游相关问题 苍山云雾森林公园 214国道"
        search_payload = search_knowledge(search_query, cfg)
        knowledge_context = build_knowledge_context(search_payload)
    except Exception as e:
        print(f"  [知识库检索跳过] {e}")

    multimodal_prompt = (
        "这是一张微信聊天截图。请你完成以下任务：\n"
        "1. 识别截图中的所有聊天内容（绿色气泡=我方发送，白色气泡=客户发送）\n"
        "2. 提取顶部标题栏的联系人名称\n"
        "3. 判断是否是群聊\n"
        "4. 针对客户最新的消息，以旅游顾问身份给出回复\n\n"
        "请严格返回 JSON，格式：\n"
        '{"contact_name": "联系人名", "is_chat_page": true, "is_group_chat": false, '
        '"messages": [{"sender": "client/agent", "text": "..."}], '
        '"last_client_message": "客户最新消息", '
        '"reply": "旅游顾问回复"}'
    )

    sys_messages = [{"role": "system", "content": BASE_PROMPT}]
    if knowledge_context:
        sys_messages.append({"role": "system", "content": knowledge_context})

    all_messages = sys_messages + [{
        "role": "user",
        "content": [
            {"type": "text", "text": multimodal_prompt},
            {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{base64_image}"}},
        ],
    }]

    t1 = time.time()
    resp = requests.post(
        "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"},
        json={
            "model": "doubao-seed-2-0-lite-260428",
            "messages": all_messages,
            "temperature": 0.3,
            "max_tokens": 2048,
        },
        timeout=(10, 90),
    )
    elapsed = time.time() - t1

    if resp.status_code != 200:
        print(f"  Ark 接口失败: {resp.status_code} {resp.text[:300]}")
        return {"plan": "B_ark_fallback", "total_time": elapsed, "error": resp.text[:300]}

    raw = resp.json()["choices"][0]["message"]["content"].strip()
    print(f"\n[Ark接口] 耗时: {elapsed:.2f}s")
    print(f"  原始返回: {raw[:500]}")

    content = raw
    for prefix in ("```json", "```"):
        if content.startswith(prefix):
            content = content[len(prefix):]
    if content.endswith("```"):
        content = content[:-3]

    try:
        result = json.loads(content.strip())
    except json.JSONDecodeError:
        result = {"raw_reply": raw, "parse_error": True}

    contact_name = result.get("contact_name", "")
    msgs = result.get("messages", [])
    reply = result.get("reply", raw)
    last_client = result.get("last_client_message", "")

    print(f"  联系人: {contact_name}")
    print(f"  消息数: {len(msgs)}")
    for i, m in enumerate(msgs):
        sender = "客户" if m.get("sender") == "client" else "我方"
        print(f"    [{i+1}] {sender}: {str(m.get('text', ''))[:80]}")
    print(f"  客户最新消息: {last_client}")
    print(f"  回复: {reply}")
    print(f"\n[总耗时] {elapsed:.2f}s")

    return {
        "plan": "B_ark_fallback",
        "total_time": elapsed,
        "contact_name": contact_name,
        "is_chat_page": result.get("is_chat_page"),
        "is_group_chat": result.get("is_group_chat"),
        "messages": msgs,
        "last_client_text": last_client,
        "reply": reply,
        "parse_error": result.get("parse_error", False),
    }


# ==================== 正确性评估 ====================

# 人工标注的参考答案（基于用户提供的截图）
GROUND_TRUTH = {
    "contact_name": "Yuki",
    "is_chat_page": True,
    "is_group_chat": False,
    "expected_messages": [
        {"sender": "client", "text_contains": "油菜花"},
        {"sender": "agent", "text_contains": "喜洲"},
        {"sender": "client", "text_contains": "摄影师跟拍是怎么收费"},
        {"sender": "agent", "text_contains": "680"},
        {"sender": "client", "text_contains": "214国道"},
        {"sender": "client", "text_contains": "苍山云雾森林公园"},
    ],
    "last_client_text_contains": "苍山云雾森林公园",
}


def evaluate_accuracy(result: dict) -> dict:
    """评估识别准确度"""
    scores = {}

    # 联系人名
    gt_name = GROUND_TRUTH["contact_name"]
    scores["contact_name_correct"] = gt_name in (result.get("contact_name") or "")

    # 页面类型
    scores["is_chat_page_correct"] = result.get("is_chat_page") == GROUND_TRUTH["is_chat_page"]
    scores["is_group_chat_correct"] = result.get("is_group_chat") == GROUND_TRUTH["is_group_chat"]

    # 消息提取
    msgs = result.get("messages", [])
    matched = 0
    for exp in GROUND_TRUTH["expected_messages"]:
        for m in msgs:
            if m.get("sender") == exp["sender"] and exp["text_contains"] in str(m.get("text", "")):
                matched += 1
                break
    total_expected = len(GROUND_TRUTH["expected_messages"])
    scores["message_match"] = f"{matched}/{total_expected}"
    scores["message_match_rate"] = matched / total_expected if total_expected else 0

    # 最后一条客户消息
    last = result.get("last_client_text", "")
    scores["last_client_correct"] = GROUND_TRUTH["last_client_text_contains"] in last

    # 回复质量（是否涉及景区回答）
    reply = result.get("reply", "")
    scores["reply_relevant"] = any(kw in reply for kw in ("苍山", "森林公园", "景区", "214", "公园"))
    scores["reply_length"] = len(reply)
    scores["has_parse_error"] = result.get("parse_error", False)

    return scores


def print_comparison(result_a: dict, result_b: dict):
    """打印对比结果"""
    eval_a = evaluate_accuracy(result_a)
    eval_b = evaluate_accuracy(result_b)

    print("\n" + "=" * 70)
    print("                       对比结果汇总")
    print("=" * 70)
    print(f"{'指标':<30} {'方案A (VLM+LLM)':<20} {'方案B (直接多模态)':<20}")
    print("-" * 70)
    print(f"{'总耗时':<28} {result_a['total_time']:.2f}s{'':<14} {result_b['total_time']:.2f}s")
    if 'vlm_time' in result_a:
        print(f"{'  VLM阶段':<28} {result_a['vlm_time']:.2f}s{'':<14} {'N/A'}")
        print(f"{'  LLM阶段':<28} {result_a['llm_time']:.2f}s{'':<14} {'N/A'}")
    print(f"{'联系人识别':<26} {'✅' if eval_a['contact_name_correct'] else '❌':<20} {'✅' if eval_b['contact_name_correct'] else '❌'}")
    print(f"{'聊天页判断':<26} {'✅' if eval_a['is_chat_page_correct'] else '❌':<20} {'✅' if eval_b['is_chat_page_correct'] else '❌'}")
    print(f"{'群聊判断':<28} {'✅' if eval_a['is_group_chat_correct'] else '❌':<20} {'✅' if eval_b['is_group_chat_correct'] else '❌'}")
    print(f"{'消息提取率':<26} {eval_a['message_match']:<20} {eval_b['message_match']}")
    print(f"{'最新客户消息':<24} {'✅' if eval_a['last_client_correct'] else '❌':<20} {'✅' if eval_b['last_client_correct'] else '❌'}")
    print(f"{'回复相关性':<26} {'✅' if eval_a['reply_relevant'] else '❌':<20} {'✅' if eval_b['reply_relevant'] else '❌'}")
    print(f"{'回复长度':<28} {eval_a['reply_length']:<20} {eval_b['reply_length']}")
    print(f"{'JSON解析错误':<24} {'❌' if eval_a['has_parse_error'] else '✅':<20} {'❌' if eval_b['has_parse_error'] else '✅'}")
    print("=" * 70)

    # 耗时对比
    speedup = result_a["total_time"] / result_b["total_time"] if result_b["total_time"] > 0 else 0
    if speedup > 1:
        print(f"\n⏱️  方案B 比方案A 快 {speedup:.1f}x ({result_a['total_time'] - result_b['total_time']:.2f}s)")
    else:
        print(f"\n⏱️  方案A 比方案B 快 {1/speedup:.1f}x ({result_b['total_time'] - result_a['total_time']:.2f}s)")

    # 正确率
    a_score = sum([
        eval_a["contact_name_correct"],
        eval_a["is_chat_page_correct"],
        eval_a["is_group_chat_correct"],
        eval_a["last_client_correct"],
        eval_a["reply_relevant"],
        not eval_a["has_parse_error"],
    ]) + eval_a["message_match_rate"]
    b_score = sum([
        eval_b["contact_name_correct"],
        eval_b["is_chat_page_correct"],
        eval_b["is_group_chat_correct"],
        eval_b["last_client_correct"],
        eval_b["reply_relevant"],
        not eval_b["has_parse_error"],
    ]) + eval_b["message_match_rate"]

    print(f"📊 综合得分: 方案A={a_score:.1f}/7.0  方案B={b_score:.1f}/7.0")

    # 方案B 会话信息检查
    print("\n--- 方案B 会话信息完整性检查（聊天页返回） ---")
    required_fields = ["contact_name", "is_chat_page", "is_group_chat", "messages", "last_client_text", "reply"]
    for f in required_fields:
        val = result_b.get(f)
        status = "✅" if val not in (None, "", [], {}) else "❌ 缺失"
        print(f"  {f}: {status}  →  {str(val)[:60] if val else 'N/A'}")


def main():
    if len(sys.argv) < 2:
        # 默认使用项目下的 uploads 目录中最新的图片
        uploads = BASE_DIR / "uploads"
        images = sorted(uploads.glob("*.png")) + sorted(uploads.glob("*.jpg")) + sorted(uploads.glob("*.jpeg"))
        if not images:
            print("用法: python test_ab_compare.py <图片路径>")
            print("或将图片放到 uploads/ 目录下")
            sys.exit(1)
        image_path = images[-1]
    else:
        image_path = Path(sys.argv[1])

    if not image_path.exists():
        print(f"图片不存在: {image_path}")
        sys.exit(1)

    print(f"📸 测试图片: {image_path}")
    print(f"   大小: {image_path.stat().st_size / 1024:.1f} KB")
    image_bytes = image_path.read_bytes()

    # 运行方案A
    result_a = test_plan_a(image_bytes)

    # 运行方案B
    result_b = test_plan_b(image_bytes)

    # 对比输出
    print_comparison(result_a, result_b)


if __name__ == "__main__":
    main()
