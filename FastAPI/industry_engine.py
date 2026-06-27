"""
行业模板引擎：根据 industry_id 加载行业相关的所有配置。
支持多行业切换（旅游定制 / 房地产 / 旅拍等），每个 Agent 账号绑定一个行业。
"""

from __future__ import annotations

import json
import logging
import re
from functools import lru_cache
from typing import Any, Dict, List, Optional, Tuple

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# 行业模板缓存（避免每次请求都查数据库）
# ---------------------------------------------------------------------------
_template_cache: Dict[str, Dict[str, Any]] = {}


def invalidate_cache(industry_id: str | None = None):
    """清除行业模板缓存。传 None 清除全部。"""
    if industry_id:
        _template_cache.pop(industry_id, None)
    else:
        _template_cache.clear()


def get_template(industry_id: str) -> Dict[str, Any]:
    """获取行业模板配置。优先从缓存读取，缓存未命中时查数据库。"""
    if industry_id in _template_cache:
        return _template_cache[industry_id]

    # 延迟导入避免循环依赖
    import database
    template = database.get_industry_template(industry_id)
    if not template:
        logger.warning(f"行业模板 '{industry_id}' 不存在，回退到 'travel'")
        template = database.get_industry_template("travel")
    if template:
        _template_cache[industry_id] = template
    return template or {}


def get_agent_industry_id(agent_id: int) -> str:
    """根据 agent_id 获取其绑定的 industry_id。"""
    import database
    agent = database.get_agent_by_id(agent_id)
    return (agent or {}).get("industry_id") or "travel"


# ---------------------------------------------------------------------------
# Prompt 获取
# ---------------------------------------------------------------------------

def get_base_prompt(industry_id: str, agent_brand: str | None = None,
                    agent_persona: str | None = None) -> str:
    """
    获取基础 System Prompt，支持 Agent 级品牌/人设覆盖。
    Prompt 模板中的占位符 [品牌名] 和 [人设名] 会被替换。
    """
    tpl = get_template(industry_id)
    prompt = tpl.get("base_prompt") or ""

    # 确定品牌名和人设名：Agent 级优先，否则用模板默认值
    brand = agent_brand or tpl.get("brand_name") or ""
    persona = agent_persona or tpl.get("agent_persona") or ""

    # 替换占位符
    if brand:
        prompt = prompt.replace("[品牌名]", brand)
    if persona:
        prompt = prompt.replace("[人设名]", persona)

    return prompt


def get_plan_generation_prompt(industry_id: str, agent_brand: str | None = None,
                               agent_persona: str | None = None) -> str:
    """获取 Agent 出方案的 Prompt。"""
    tpl = get_template(industry_id)
    prompt = tpl.get("plan_generation_prompt") or ""
    brand = agent_brand or tpl.get("brand_name") or ""
    persona = agent_persona or tpl.get("agent_persona") or ""
    if brand:
        prompt = prompt.replace("[品牌名]", brand)
    if persona:
        prompt = prompt.replace("[人设名]", persona)
    return prompt


def get_summary_prompt(industry_id: str) -> str:
    """获取对话摘要 Prompt。"""
    tpl = get_template(industry_id)
    return tpl.get("summary_prompt") or ""


def get_profile_prompt(industry_id: str) -> str:
    """获取会话画像提取 Prompt。"""
    tpl = get_template(industry_id)
    return tpl.get("session_profile_prompt") or ""


def get_contact_memory_prompt(industry_id: str) -> str:
    """获取联系人长期记忆提取 Prompt。"""
    tpl = get_template(industry_id)
    return tpl.get("contact_memory_prompt") or ""


def get_handoff_prompt_addon(industry_id: str) -> str:
    """获取 Handoff 相关的动态 Prompt 片段。"""
    tpl = get_template(industry_id)
    return tpl.get("handoff_prompt_addon") or ""


# ---------------------------------------------------------------------------
# 知识库配置
# ---------------------------------------------------------------------------

def get_knowledge_collection(industry_id: str) -> str:
    """获取行业对应的知识库 collection 名。"""
    tpl = get_template(industry_id)
    return tpl.get("knowledge_collection") or ""


def get_knowledge_search_limit(industry_id: str) -> int:
    """获取知识库检索条数。"""
    tpl = get_template(industry_id)
    return tpl.get("knowledge_search_limit") or 8


# ---------------------------------------------------------------------------
# Handoff（转人工）配置
# ---------------------------------------------------------------------------

def _parse_json_list(value: str | None) -> list:
    """安全解析 JSON 列表字段。"""
    if not value:
        return []
    try:
        parsed = json.loads(value)
        return parsed if isinstance(parsed, list) else []
    except (json.JSONDecodeError, TypeError):
        return []


def _parse_json_dict(value: str | None) -> dict:
    """安全解析 JSON 字典字段。"""
    if not value:
        return {}
    try:
        parsed = json.loads(value)
        return parsed if isinstance(parsed, dict) else {}
    except (json.JSONDecodeError, TypeError):
        return {}


def get_handoff_keywords(industry_id: str) -> List[str]:
    """获取行业转人工关键词列表。"""
    tpl = get_template(industry_id)
    return _parse_json_list(tpl.get("handoff_keywords_json"))


def get_handoff_confirm_keywords(industry_id: str) -> List[str]:
    """获取确认性短答关键词。"""
    tpl = get_template(industry_id)
    return _parse_json_list(tpl.get("handoff_confirm_keywords_json"))


def get_handoff_required_fields(industry_id: str) -> List[Dict[str, str]]:
    """
    获取转人工必备信息字段列表。
    返回格式: [{"key": "budget_range", "label": "预算范围", "regex": "预算.*万|总价.*万"}, ...]
    """
    tpl = get_template(industry_id)
    return _parse_json_list(tpl.get("handoff_required_fields_json"))


def get_handoff_field_patterns(industry_id: str) -> Dict[str, str]:
    """
    获取每个必备字段的正则检测模式。
    返回格式: {"budget_range": "预算.*万|总价", "preferred_area": "区|板块|片区", ...}
    """
    tpl = get_template(industry_id)
    return _parse_json_dict(tpl.get("handoff_field_patterns_json"))


def check_handoff_readiness(industry_id: str, profile: Dict[str, Any],
                            recent_messages: List[str] | None = None,
                            current_message: str = "") -> Dict[str, Any]:
    """
    检查行业特定的 Handoff 成熟度。
    返回: {"ready": bool, "met": [...], "missing": [...], "missing_labels": [...]}
    """
    required_fields = get_handoff_required_fields(industry_id)
    field_patterns = get_handoff_field_patterns(industry_id)

    # 将所有待检查的文本合并
    all_text = current_message + "\n"
    if recent_messages:
        all_text += "\n".join(recent_messages[-24:])

    met: List[str] = []
    missing: List[Dict[str, str]] = []

    for field_def in required_fields:
        key = field_def.get("key", "")
        label = field_def.get("label", key)

        # 先检查画像中是否已有值
        profile_value = str(profile.get(key) or "").strip()
        if profile_value and profile_value not in {"", "无", "未知", "未明确", "待定", "待确认", "暂无"}:
            met.append(label)
            continue

        # 再用正则在消息文本中检测
        pattern = field_patterns.get(key) or field_def.get("regex", "")
        if pattern:
            try:
                if re.search(pattern, all_text):
                    met.append(label)
                    continue
            except re.error:
                pass

        missing.append({"key": key, "label": label})

    return {
        "ready": len(missing) == 0,
        "met": met,
        "missing": [m["key"] for m in missing],
        "missing_labels": [m["label"] for m in missing],
    }


def is_handoff_intent(industry_id: str, message: str,
                      recent_context: str = "") -> bool:
    """
    检测客户消息是否包含转人工意图。
    综合关键词匹配和确认性短答上下文判断。
    """
    keywords = get_handoff_keywords(industry_id)
    confirm_keywords = get_handoff_confirm_keywords(industry_id)

    # 直接意图关键词匹配
    for kw in keywords:
        if kw in message:
            return True

    # 确认性短答 + 上下文关键词
    msg_stripped = message.strip().rstrip("。，！!.,~～")
    if msg_stripped in confirm_keywords or len(msg_stripped) <= 4:
        for kw in keywords:
            if kw in recent_context:
                return True

    return False


# ---------------------------------------------------------------------------
# 备注建议
# ---------------------------------------------------------------------------

def get_remark_config(industry_id: str) -> Dict[str, Any]:
    """
    获取备注生成配置。
    返回格式: {"tag_fields": ["preferred_area", "budget_range", "room_requirement"],
               "stage_show": ["意向强烈", "已看房", "成交"],
               "no_data_reason": "暂未识别到可用于备注的称呼或购房需求"}
    """
    tpl = get_template(industry_id)
    return _parse_json_dict(tpl.get("remark_config_json"))


def get_sales_stages(industry_id: str) -> List[str]:
    """获取行业销售阶段列表。"""
    tpl = get_template(industry_id)
    return _parse_json_list(tpl.get("sales_stages_json"))


# ---------------------------------------------------------------------------
# Agent 话术关键词（给 Android 端用）
# ---------------------------------------------------------------------------

def get_agent_style_keywords(industry_id: str, agent_brand: str | None = None,
                              agent_persona: str | None = None) -> List[str]:
    """
    获取 Agent 话术识别关键词，用于 Android 端区分 Agent 回复和客户消息。
    包含行业通用关键词 + Agent 品牌/人设名。
    """
    tpl = get_template(industry_id)
    keywords = _parse_json_list(tpl.get("agent_style_keywords_json"))

    # 加入 Agent 级品牌名和人设名
    brand = agent_brand or tpl.get("brand_name") or ""
    persona = agent_persona or tpl.get("agent_persona") or ""
    if brand and brand not in keywords:
        keywords.append(brand)
    if persona and persona not in keywords:
        keywords.append(persona)

    return keywords


# ---------------------------------------------------------------------------
# 时间修正
# ---------------------------------------------------------------------------

def is_time_repair_enabled(industry_id: str) -> bool:
    """该行业是否启用时间修正逻辑。"""
    tpl = get_template(industry_id)
    return bool(tpl.get("time_repair_enabled", 1))


def use_knowledge_base(industry_id: str) -> bool:
    """该行业是否使用知识库。"""
    tpl = get_template(industry_id)
    if not tpl:
        return True
    val = tpl.get("use_knowledge_base")
    return int(val) != 0 if val is not None else True
