import sqlite3
from pathlib import Path
from typing import Optional, List, Dict, Any
import hashlib
import hmac
import json
import re
import secrets
from datetime import datetime, timezone, timedelta

def get_beijing_time() -> str:
    """获取格式化的北京时间字符串"""
    tz = timezone(timedelta(hours=8))
    return datetime.now(tz).strftime("%Y-%m-%d %H:%M:%S")

BASE_DIR = Path(__file__).resolve().parent
DB_PATH = BASE_DIR / "agent_chat.db"

LEGACY_STATUS_MAP = {
    "manual": "handoff_requested",
    "takeover": "human_takeover",
    "silence": "muted",
}

VALID_SESSION_STATUSES = {"auto", "handoff_requested", "human_takeover", "muted"}

TRAILING_EMOJI_RE = re.compile(r"[\U0001F000-\U0001FAFF\u2600-\u27BF\u200D\uFE0F\U000E0020-\U000E007F]+$")
TRAILING_EMOJI_PLACEHOLDER_RE = re.compile(r"\s*[\[(](?:表情|动画表情|emoji)[\])]$", re.IGNORECASE)
EMPTY_PROFILE_VALUES = {"", "无", "未知", "未明确", "待定", "待确认", "没有", "暂无"}


def canonicalize_contact_name(raw: str | None, default: str = "客户") -> str:
    """去掉联系人名尾部的装饰 emoji，避免同一客户被拆成多个会话。"""
    normalized = (raw or "").replace("（", "(").replace("）", ")").strip()
    if not normalized:
        return default

    while True:
        before = normalized
        normalized = TRAILING_EMOJI_PLACEHOLDER_RE.sub("", normalized).rstrip()
        normalized = TRAILING_EMOJI_RE.sub("", normalized).strip()
        if normalized == before:
            break
        if not normalized:
            return default

    return normalized or default


def _json_list(value: str | None) -> list[str]:
    if not value:
        return []
    try:
        parsed = json.loads(value)
    except Exception:
        return []
    if not isinstance(parsed, list):
        return []
    return [str(item).strip() for item in parsed if str(item).strip()]


def _compact_profile_value(value: Any) -> str:
    if isinstance(value, (list, tuple)):
        return "、".join(str(item).strip() for item in value if str(item).strip())
    return str(value or "").strip()


def _is_meaningful_profile_value(value: Any) -> bool:
    text = _compact_profile_value(value)
    return bool(text and text not in EMPTY_PROFILE_VALUES)


def _add_contact_alias(cursor: sqlite3.Cursor, session_id: str, contact_name: str | None) -> None:
    alias = canonicalize_contact_name(contact_name, default="")
    if not alias or alias in {"客户", "当前联系人"}:
        return

    cursor.execute("SELECT contact_aliases_json FROM sessions WHERE session_id = ?", (session_id,))
    row = cursor.fetchone()
    aliases = _json_list(row["contact_aliases_json"] if row else None)
    if alias not in aliases:
        aliases.append(alias)
        cursor.execute(
            "UPDATE sessions SET contact_aliases_json = ? WHERE session_id = ?",
            (json.dumps(aliases[-20:], ensure_ascii=False), session_id),
        )


def build_remark_suggestion(contact_name: str | None, profile: Dict[str, Any] | None) -> Dict[str, str]:
    """根据已确认画像生成短备注，供人工复制到微信备注。"""
    profile = profile or {}
    base_name = canonicalize_contact_name(contact_name, default="")
    if base_name in {"客户", "当前联系人"}:
        base_name = ""

    destination = _compact_profile_value(profile.get("destination"))
    people_count = _compact_profile_value(profile.get("people_count"))
    preferences = profile.get("preferences")
    preference_text = _compact_profile_value(preferences)

    tags: list[str] = []
    if _is_meaningful_profile_value(destination):
        tags.append(destination[:8])
    if _is_meaningful_profile_value(people_count):
        tags.append(people_count[:6])
    elif any(keyword in preference_text for keyword in ("亲子", "孩子", "老人", "家庭")):
        tags.append("亲子")
    elif any(keyword in preference_text for keyword in ("团建", "公司", "企业")):
        tags.append("团建")
    elif any(keyword in preference_text for keyword in ("蜜月", "情侣")):
        tags.append("蜜月")

    stage = _compact_profile_value(profile.get("sales_stage"))
    if stage in {"意向强烈", "已出方案", "成交"}:
        tags.append(stage[:4])

    deduped_tags: list[str] = []
    for tag in tags:
        tag = re.sub(r"\s+", "", tag)
        if tag and tag not in deduped_tags:
            deduped_tags.append(tag)

    parts = [part for part in [base_name, *deduped_tags[:2]] if part]
    suggestion = "-".join(parts)[:32]
    if not suggestion:
        return {
            "suggested_remark": "",
            "suggested_remark_reason": "暂未识别到可用于备注的称呼或旅行需求",
            "suggested_remark_confidence": "low",
        }

    confidence = "high" if base_name and len(deduped_tags) >= 1 else "medium"
    reason_bits = []
    if base_name:
        reason_bits.append(f"联系人称呼为「{base_name}」")
    if deduped_tags:
        reason_bits.append(f"已识别需求标签：{'、'.join(deduped_tags[:2])}")
    return {
        "suggested_remark": suggestion,
        "suggested_remark_reason": "；".join(reason_bits) or "根据当前联系人名和画像生成",
        "suggested_remark_confidence": confidence,
    }


def normalize_session_status(status: str | None) -> str:
    normalized = (status or "").strip().lower()
    normalized = LEGACY_STATUS_MAP.get(normalized, normalized)
    return normalized if normalized in VALID_SESSION_STATUSES else "auto"

def init_db():
    conn = sqlite3.connect(DB_PATH)
    _configure_connection(conn)
    cursor = conn.cursor()
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS agents (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT NOT NULL UNIQUE,
            display_name TEXT NOT NULL,
            password_hash TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'active',
            note TEXT,
            token_hash TEXT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            last_login_at TIMESTAMP,
            last_seen_at TIMESTAMP,
            last_device_id TEXT
        )
    """)
    for column_sql in [
        "ALTER TABLE agents ADD COLUMN note TEXT",
        "ALTER TABLE agents ADD COLUMN token_hash TEXT",
        "ALTER TABLE agents ADD COLUMN last_login_at TIMESTAMP",
        "ALTER TABLE agents ADD COLUMN last_seen_at TIMESTAMP",
        "ALTER TABLE agents ADD COLUMN last_device_id TEXT",
    ]:
        try:
            cursor.execute(column_sql)
        except sqlite3.OperationalError:
            pass

    # Create sessions table
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS sessions (
            session_id TEXT PRIMARY KEY,
            agent_id INTEGER,
            contact_name TEXT,
            profile_json TEXT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            status TEXT DEFAULT 'auto',
            FOREIGN KEY (agent_id) REFERENCES agents (id)
        )
    """)
    # Add columns if they don't exist (for older DBs)
    for column_sql in [
        "ALTER TABLE sessions ADD COLUMN status TEXT DEFAULT 'auto'",
        "ALTER TABLE sessions ADD COLUMN agent_id INTEGER",
        "ALTER TABLE sessions ADD COLUMN contact_aliases_json TEXT",
        "ALTER TABLE sessions ADD COLUMN suggested_remark TEXT",
        "ALTER TABLE sessions ADD COLUMN suggested_remark_reason TEXT",
        "ALTER TABLE sessions ADD COLUMN suggested_remark_confidence TEXT",
        "ALTER TABLE sessions ADD COLUMN suggested_remark_status TEXT DEFAULT 'pending'",
        "ALTER TABLE sessions ADD COLUMN suggested_remark_updated_at TIMESTAMP",
    ]:
        try:
            cursor.execute(column_sql)
        except sqlite3.OperationalError:
            pass

    # Create messages table
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id TEXT,
            role TEXT,
            content TEXT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (session_id) REFERENCES sessions (session_id)
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_sessions_agent_updated ON sessions(agent_id, updated_at)")
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_sessions_agent_contact ON sessions(agent_id, contact_name)")
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_messages_session_created ON messages(session_id, created_at)")
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS outbound_tasks (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            agent_id INTEGER,
            session_id TEXT,
            contact_name TEXT NOT NULL,
            search_keyword TEXT NOT NULL,
            message TEXT NOT NULL,
            auto_send INTEGER NOT NULL DEFAULT 0,
            status TEXT NOT NULL DEFAULT 'pending',
            error TEXT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            claimed_at TIMESTAMP,
            completed_at TIMESTAMP,
            FOREIGN KEY (agent_id) REFERENCES agents (id)
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_outbound_tasks_agent_status ON outbound_tasks(agent_id, status, id)")
    conn.commit()
    conn.close()

def get_connection():
    conn = sqlite3.connect(DB_PATH)
    _configure_connection(conn)
    conn.row_factory = sqlite3.Row
    return conn


def _configure_connection(conn: sqlite3.Connection) -> None:
    conn.execute("PRAGMA busy_timeout = 5000")
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA journal_mode = WAL")
    conn.execute("PRAGMA synchronous = NORMAL")


def _password_hash(password: str, salt: str | None = None) -> str:
    salt = salt or secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt.encode("utf-8"), 120_000)
    return f"pbkdf2_sha256${salt}${digest.hex()}"


def _verify_password(password: str, stored_hash: str | None) -> bool:
    if not stored_hash:
        return False
    try:
        algorithm, salt, expected = stored_hash.split("$", 2)
    except ValueError:
        return False
    if algorithm != "pbkdf2_sha256":
        return False
    candidate = _password_hash(password, salt).split("$", 2)[2]
    return hmac.compare_digest(candidate, expected)


def _token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _agent_public(row: sqlite3.Row | Dict[str, Any] | None) -> Optional[Dict[str, Any]]:
    if not row:
        return None
    item = dict(row)
    item.pop("password_hash", None)
    item.pop("token_hash", None)
    return item


def create_agent(username: str, password: str, display_name: str = "", note: str = "") -> Dict[str, Any]:
    username = (username or "").strip()
    display_name = (display_name or "").strip() or username
    if not username:
        raise ValueError("用户名不能为空")
    if not password:
        raise ValueError("密码不能为空")

    now_bj = get_beijing_time()
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute(
        """
        INSERT INTO agents (username, display_name, password_hash, note, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        (username, display_name, _password_hash(password), (note or "").strip(), now_bj, now_bj),
    )
    agent_id = cursor.lastrowid
    conn.commit()
    cursor.execute(
        "SELECT id, username, display_name, status, note, created_at, updated_at, last_login_at, last_seen_at, last_device_id FROM agents WHERE id = ?",
        (agent_id,),
    )
    agent = _agent_public(cursor.fetchone())
    conn.close()
    return agent or {}


def list_agents() -> List[Dict[str, Any]]:
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute(
        """
        SELECT a.id, a.username, a.display_name, a.status, a.note, a.created_at, a.updated_at,
               a.last_login_at, a.last_seen_at, a.last_device_id,
               (SELECT COUNT(*) FROM sessions s WHERE s.agent_id = a.id) AS session_count
        FROM agents a
        ORDER BY a.updated_at DESC, a.id DESC
        """
    )
    rows = cursor.fetchall()
    conn.close()
    return [dict(row) for row in rows]


def update_agent(agent_id: int, display_name: str, status: str, note: str = "") -> Optional[Dict[str, Any]]:
    status = (status or "active").strip().lower()
    if status not in {"active", "disabled"}:
        raise ValueError("Agent 状态只支持 active / disabled")
    now_bj = get_beijing_time()
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute(
        """
        UPDATE agents
        SET display_name = ?, status = ?, note = ?, updated_at = ?
        WHERE id = ?
        """,
        ((display_name or "").strip(), status, (note or "").strip(), now_bj, agent_id),
    )
    conn.commit()
    cursor.execute(
        "SELECT id, username, display_name, status, note, created_at, updated_at, last_login_at, last_seen_at, last_device_id FROM agents WHERE id = ?",
        (agent_id,),
    )
    agent = _agent_public(cursor.fetchone())
    conn.close()
    return agent


def set_agent_password(agent_id: int, password: str) -> None:
    if not password:
        raise ValueError("密码不能为空")
    now_bj = get_beijing_time()
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute(
        "UPDATE agents SET password_hash = ?, token_hash = NULL, updated_at = ? WHERE id = ?",
        (_password_hash(password), now_bj, agent_id),
    )
    conn.commit()
    conn.close()


def authenticate_agent(username: str, password: str, device_id: str = "") -> Optional[Dict[str, Any]]:
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute("SELECT * FROM agents WHERE username = ?", ((username or "").strip(),))
    row = cursor.fetchone()
    if not row or row["status"] != "active" or not _verify_password(password, row["password_hash"]):
        conn.close()
        return None

    token = secrets.token_urlsafe(32)
    now_bj = get_beijing_time()
    cursor.execute(
        """
        UPDATE agents
        SET token_hash = ?, last_login_at = ?, last_seen_at = ?, last_device_id = ?, updated_at = ?
        WHERE id = ?
        """,
        (_token_hash(token), now_bj, now_bj, (device_id or "").strip(), now_bj, row["id"]),
    )
    conn.commit()
    cursor.execute(
        "SELECT id, username, display_name, status, note, created_at, updated_at, last_login_at, last_seen_at, last_device_id FROM agents WHERE id = ?",
        (row["id"],),
    )
    agent = _agent_public(cursor.fetchone()) or {}
    conn.close()
    agent["access_token"] = token
    return agent


def get_agent_by_token(token: str) -> Optional[Dict[str, Any]]:
    token = (token or "").strip()
    if not token:
        return None
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute(
        "SELECT id, username, display_name, status, note, created_at, updated_at, last_login_at, last_seen_at, last_device_id FROM agents WHERE token_hash = ?",
        (_token_hash(token),),
    )
    row = cursor.fetchone()
    if not row or row["status"] != "active":
        conn.close()
        return None
    now_bj = get_beijing_time()
    cursor.execute("UPDATE agents SET last_seen_at = ? WHERE id = ?", (now_bj, row["id"]))
    conn.commit()
    agent = _agent_public(row)
    conn.close()
    return agent


def make_agent_session_id(agent_id: int | None, session_id: str) -> str:
    incoming_session_id = (session_id or "").strip()
    if not agent_id or not incoming_session_id:
        return incoming_session_id
    prefix = f"agent_{agent_id}:"
    return incoming_session_id if incoming_session_id.startswith(prefix) else f"{prefix}{incoming_session_id}"


def resolve_session_id(session_id: str, contact_name: str, agent_id: int | None = None) -> str:
    """优先复用同一规范化联系人已有的后端会话，兼容联系人备注变更后的新 session_id。"""
    incoming_session_id = make_agent_session_id(agent_id, session_id)
    canonical_name = canonicalize_contact_name(contact_name)
    if not incoming_session_id:
        return incoming_session_id

    conn = get_connection()
    cursor = conn.cursor()
    try:
        cursor.execute("SELECT contact_name FROM sessions WHERE session_id = ?", (incoming_session_id,))
        exact_row = cursor.fetchone()

        if canonical_name not in {"客户", "当前联系人"}:
            if agent_id is None:
                cursor.execute(
                    """
                    SELECT session_id, contact_name, contact_aliases_json, suggested_remark
                    FROM sessions
                    WHERE agent_id IS NULL
                    ORDER BY updated_at DESC
                    """
                )
            else:
                cursor.execute(
                    """
                    SELECT session_id, contact_name, contact_aliases_json, suggested_remark
                    FROM sessions
                    WHERE agent_id = ?
                    ORDER BY updated_at DESC
                    """,
                    (agent_id,),
                )
            matching_rows = []
            for row in cursor.fetchall():
                known_names = [
                    row["contact_name"],
                    row["suggested_remark"],
                    *_json_list(row["contact_aliases_json"]),
                ]
                if any(canonicalize_contact_name(name, default="") == canonical_name for name in known_names):
                    matching_rows.append(row)
            preferred_row = next(
                (row for row in matching_rows if row["contact_name"] == canonical_name),
                matching_rows[0] if matching_rows else None,
            )
            if preferred_row:
                if preferred_row["contact_name"] != canonical_name:
                    _add_contact_alias(cursor, preferred_row["session_id"], preferred_row["contact_name"])
                    _add_contact_alias(cursor, preferred_row["session_id"], canonical_name)
                    cursor.execute(
                        "UPDATE sessions SET contact_name = ?, updated_at = ? WHERE session_id = ?",
                        (canonical_name, get_beijing_time(), preferred_row["session_id"]),
                    )
                    conn.commit()
                return preferred_row["session_id"]

        if exact_row:
            if exact_row["contact_name"] != canonical_name:
                _add_contact_alias(cursor, incoming_session_id, exact_row["contact_name"])
                _add_contact_alias(cursor, incoming_session_id, canonical_name)
                cursor.execute(
                    "UPDATE sessions SET contact_name = ?, updated_at = ? WHERE session_id = ?",
                    (canonical_name, get_beijing_time(), incoming_session_id),
                )
                conn.commit()
            return incoming_session_id
    finally:
        conn.close()

    return incoming_session_id

def save_message(session_id: str, contact_name: str, role: str, content: str, agent_id: int | None = None):
    conn = get_connection()
    cursor = conn.cursor()
    contact_name = canonicalize_contact_name(contact_name)
    
    # Ensure session exists
    now_bj = get_beijing_time()
    cursor.execute("SELECT session_id, agent_id FROM sessions WHERE session_id = ?", (session_id,))
    if not cursor.fetchone():
        cursor.execute(
            "INSERT INTO sessions (session_id, agent_id, contact_name, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            (session_id, agent_id, contact_name, now_bj, now_bj)
        )
        _add_contact_alias(cursor, session_id, contact_name)
    else:
        cursor.execute(
            "UPDATE sessions SET updated_at = ?, agent_id = COALESCE(agent_id, ?) WHERE session_id = ?",
            (now_bj, agent_id, session_id)
        )
        _add_contact_alias(cursor, session_id, contact_name)
        
    # Insert message
    cursor.execute(
        "INSERT INTO messages (session_id, role, content, created_at) VALUES (?, ?, ?, ?)",
        (session_id, role, content, now_bj)
    )
    conn.commit()
    conn.close()

def get_all_sessions(agent_id: int | None = None, limit: int = 50, offset: int = 0) -> Dict[str, Any]:
    conn = get_connection()
    cursor = conn.cursor()
    safe_limit = max(1, min(int(limit or 50), 200))
    safe_offset = max(0, int(offset or 0))
    params: tuple[Any, ...] = ()
    where_sql = ""
    if agent_id is not None:
        where_sql = "WHERE s.agent_id = ?"
        params = (agent_id,)

    cursor.execute(f"SELECT COUNT(*) AS total FROM sessions s {where_sql}", params)
    total = int(cursor.fetchone()["total"])

    cursor.execute(
        f"""
        WITH page_sessions AS (
            SELECT s.session_id, s.agent_id, s.contact_name, s.updated_at, s.profile_json, s.status,
                   s.contact_aliases_json, s.suggested_remark, s.suggested_remark_reason,
                   s.suggested_remark_confidence, s.suggested_remark_status, s.suggested_remark_updated_at
            FROM sessions s
            {where_sql}
            ORDER BY s.updated_at DESC
            LIMIT ? OFFSET ?
        ),
        message_summary AS (
            SELECT m.session_id, COUNT(*) AS msg_count, MAX(m.id) AS last_message_id
            FROM messages m
            JOIN page_sessions ps ON ps.session_id = m.session_id
            GROUP BY m.session_id
        )
        SELECT ps.session_id, ps.agent_id, ps.contact_name, ps.updated_at, ps.profile_json, ps.status,
               ps.contact_aliases_json, ps.suggested_remark, ps.suggested_remark_reason,
               ps.suggested_remark_confidence, ps.suggested_remark_status, ps.suggested_remark_updated_at,
               a.username AS agent_username, a.display_name AS agent_display_name,
               COALESCE(ms.msg_count, 0) AS msg_count,
               substr(COALESCE(last_m.content, ''), 1, 160) AS last_msg
        FROM page_sessions ps
        LEFT JOIN agents a ON a.id = ps.agent_id
        LEFT JOIN message_summary ms ON ms.session_id = ps.session_id
        LEFT JOIN messages last_m ON last_m.id = ms.last_message_id
        ORDER BY ps.updated_at DESC
        """,
        (*params, safe_limit, safe_offset),
    )
    rows = cursor.fetchall()
    conn.close()
    sessions = []
    for row in rows:
        item = dict(row)
        item["status"] = normalize_session_status(item.get("status"))
        sessions.append(item)
    return {
        "items": sessions,
        "total": total,
        "limit": safe_limit,
        "offset": safe_offset,
        "has_more": safe_offset + len(sessions) < total,
    }

def get_session_messages(session_id: str) -> List[Dict[str, Any]]:
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute(
        "SELECT id, role, content, created_at FROM messages WHERE session_id = ? ORDER BY created_at ASC",
        (session_id,)
    )
    rows = cursor.fetchall()
    conn.close()
    return [dict(row) for row in rows]

def get_session_profile(session_id: str) -> Optional[Dict[str, Any]]:
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute("SELECT profile_json FROM sessions WHERE session_id = ?", (session_id,))
    row = cursor.fetchone()
    conn.close()
    
    if row and row['profile_json']:
        try:
            return json.loads(row['profile_json'])
        except Exception:
            return None
    return None

def get_session(session_id: str, agent_id: int | None = None) -> Optional[Dict[str, Any]]:
    conn = get_connection()
    cursor = conn.cursor()
    if agent_id is None:
        cursor.execute(
            """
            SELECT s.session_id, s.agent_id, s.contact_name, s.updated_at, s.profile_json, s.status,
                   s.contact_aliases_json, s.suggested_remark, s.suggested_remark_reason,
                   s.suggested_remark_confidence, s.suggested_remark_status, s.suggested_remark_updated_at,
                   a.username AS agent_username, a.display_name AS agent_display_name
            FROM sessions s
            LEFT JOIN agents a ON a.id = s.agent_id
            WHERE s.session_id = ?
            """,
            (session_id,),
        )
    else:
        cursor.execute(
            """
            SELECT s.session_id, s.agent_id, s.contact_name, s.updated_at, s.profile_json, s.status,
                   s.contact_aliases_json, s.suggested_remark, s.suggested_remark_reason,
                   s.suggested_remark_confidence, s.suggested_remark_status, s.suggested_remark_updated_at,
                   a.username AS agent_username, a.display_name AS agent_display_name
            FROM sessions s
            LEFT JOIN agents a ON a.id = s.agent_id
            WHERE s.session_id = ? AND s.agent_id = ?
            """,
            (session_id, agent_id),
        )
    row = cursor.fetchone()
    conn.close()
    if not row:
        return None
    item = dict(row)
    item["status"] = normalize_session_status(item.get("status"))
    return item

def update_session_profile(session_id: str, profile_dict: Dict[str, Any]):
    conn = get_connection()
    cursor = conn.cursor()
    profile_str = json.dumps(profile_dict, ensure_ascii=False)
    now_bj = get_beijing_time()
    cursor.execute("SELECT contact_name, suggested_remark_status FROM sessions WHERE session_id = ?", (session_id,))
    row = cursor.fetchone()
    suggestion = build_remark_suggestion(row["contact_name"] if row else "", profile_dict)
    cursor.execute(
        """
        UPDATE sessions
        SET profile_json = ?,
            suggested_remark = ?,
            suggested_remark_reason = ?,
            suggested_remark_confidence = ?,
            suggested_remark_status = CASE
                WHEN COALESCE(suggested_remark_status, 'pending') = 'applied' THEN suggested_remark_status
                ELSE 'pending'
            END,
            suggested_remark_updated_at = ?
        WHERE session_id = ?
        """,
        (
            profile_str,
            suggestion["suggested_remark"],
            suggestion["suggested_remark_reason"],
            suggestion["suggested_remark_confidence"],
            now_bj,
            session_id,
        )
    )
    conn.commit()
    conn.close()


def refresh_remark_suggestion(session_id: str) -> Optional[Dict[str, Any]]:
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute("SELECT contact_name, profile_json FROM sessions WHERE session_id = ?", (session_id,))
    row = cursor.fetchone()
    if not row:
        conn.close()
        return None

    profile: Dict[str, Any] = {}
    if row["profile_json"]:
        try:
            profile = json.loads(row["profile_json"])
        except Exception:
            profile = {}
    suggestion = build_remark_suggestion(row["contact_name"], profile)
    now_bj = get_beijing_time()
    cursor.execute(
        """
        UPDATE sessions
        SET suggested_remark = ?,
            suggested_remark_reason = ?,
            suggested_remark_confidence = ?,
            suggested_remark_status = 'pending',
            suggested_remark_updated_at = ?
        WHERE session_id = ?
        """,
        (
            suggestion["suggested_remark"],
            suggestion["suggested_remark_reason"],
            suggestion["suggested_remark_confidence"],
            now_bj,
            session_id,
        ),
    )
    _add_contact_alias(cursor, session_id, suggestion["suggested_remark"])
    conn.commit()
    cursor.execute("SELECT * FROM sessions WHERE session_id = ?", (session_id,))
    updated = dict(cursor.fetchone())
    conn.close()
    updated["status"] = normalize_session_status(updated.get("status"))
    return updated


def mark_remark_applied(session_id: str, applied_remark: str | None = None) -> Optional[Dict[str, Any]]:
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute("SELECT contact_name, suggested_remark FROM sessions WHERE session_id = ?", (session_id,))
    row = cursor.fetchone()
    if not row:
        conn.close()
        return None

    remark = canonicalize_contact_name(applied_remark or row["suggested_remark"] or row["contact_name"], default="")
    now_bj = get_beijing_time()
    if remark:
        _add_contact_alias(cursor, session_id, row["contact_name"])
        _add_contact_alias(cursor, session_id, row["suggested_remark"])
        _add_contact_alias(cursor, session_id, remark)
    cursor.execute(
        """
        UPDATE sessions
        SET suggested_remark_status = 'applied',
            suggested_remark_updated_at = ?
        WHERE session_id = ?
        """,
        (now_bj, session_id),
    )
    conn.commit()
    cursor.execute("SELECT * FROM sessions WHERE session_id = ?", (session_id,))
    updated = dict(cursor.fetchone())
    conn.close()
    updated["status"] = normalize_session_status(updated.get("status"))
    return updated

def delete_session(session_id: str, agent_id: int | None = None):
    conn = get_connection()
    cursor = conn.cursor()
    if agent_id is None:
        cursor.execute("DELETE FROM messages WHERE session_id = ?", (session_id,))
        cursor.execute("DELETE FROM sessions WHERE session_id = ?", (session_id,))
    else:
        cursor.execute("DELETE FROM messages WHERE session_id IN (SELECT session_id FROM sessions WHERE session_id = ? AND agent_id = ?)", (session_id, agent_id))
        cursor.execute("DELETE FROM sessions WHERE session_id = ? AND agent_id = ?", (session_id, agent_id))
    conn.commit()
    conn.close()

def delete_message(message_id: int):
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute("DELETE FROM messages WHERE id = ?", (message_id,))
    conn.commit()
    conn.close()

def create_outbound_task(
    agent_id: int | None,
    session_id: str | None,
    contact_name: str,
    search_keyword: str,
    message: str,
    auto_send: bool = False,
) -> Dict[str, Any]:
    contact_name = canonicalize_contact_name(contact_name, default="")
    search_keyword = (search_keyword or contact_name).strip()
    message = (message or "").strip()
    if not contact_name:
        raise ValueError("联系人不能为空")
    if not search_keyword:
        raise ValueError("搜索关键词不能为空")
    if not message:
        raise ValueError("发送内容不能为空")

    now_bj = get_beijing_time()
    resolved_session_id = resolve_session_id(
        session_id or f"manual_outbound:{contact_name}",
        contact_name,
        agent_id=agent_id,
    )
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute(
        """
        INSERT INTO outbound_tasks (
            agent_id, session_id, contact_name, search_keyword, message,
            auto_send, status, created_at, updated_at
        )
        VALUES (?, ?, ?, ?, ?, ?, 'pending', ?, ?)
        """,
        (
            agent_id,
            resolved_session_id,
            contact_name,
            search_keyword,
            message,
            1 if auto_send else 0,
            now_bj,
            now_bj,
        ),
    )
    task_id = cursor.lastrowid
    conn.commit()
    task = _get_outbound_task_by_id(cursor, task_id)
    conn.close()
    return task or {}


def list_outbound_tasks(agent_id: int | None = None, limit: int = 30) -> List[Dict[str, Any]]:
    conn = get_connection()
    cursor = conn.cursor()
    safe_limit = max(1, min(int(limit or 30), 100))
    if agent_id is None:
        cursor.execute(
            "SELECT * FROM outbound_tasks ORDER BY id DESC LIMIT ?",
            (safe_limit,),
        )
    else:
        cursor.execute(
            "SELECT * FROM outbound_tasks WHERE agent_id = ? ORDER BY id DESC LIMIT ?",
            (agent_id, safe_limit),
        )
    rows = cursor.fetchall()
    conn.close()
    return [_outbound_task_public(row) for row in rows]


def claim_next_outbound_task(agent_id: int) -> Optional[Dict[str, Any]]:
    now_bj = get_beijing_time()
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute("BEGIN IMMEDIATE")
    cursor.execute(
        """
        SELECT * FROM outbound_tasks
        WHERE status = 'pending' AND (agent_id = ? OR agent_id IS NULL)
        ORDER BY CASE WHEN agent_id = ? THEN 0 ELSE 1 END, id ASC
        LIMIT 1
        """,
        (agent_id, agent_id),
    )
    row = cursor.fetchone()
    if not row:
        conn.commit()
        conn.close()
        return None

    cursor.execute(
        """
        UPDATE outbound_tasks
        SET status = 'running', agent_id = COALESCE(agent_id, ?), claimed_at = ?, updated_at = ?
        WHERE id = ? AND status = 'pending'
        """,
        (agent_id, now_bj, now_bj, row["id"]),
    )
    conn.commit()
    task = _get_outbound_task_by_id(cursor, row["id"])
    conn.close()
    return task


def complete_outbound_task(task_id: int, agent_id: int, success: bool, error: str = "") -> Optional[Dict[str, Any]]:
    now_bj = get_beijing_time()
    status = "succeeded" if success else "failed"
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute(
        """
        UPDATE outbound_tasks
        SET status = ?, error = ?, completed_at = ?, updated_at = ?
        WHERE id = ? AND agent_id = ?
        """,
        (status, (error or "").strip()[:500], now_bj, now_bj, task_id, agent_id),
    )
    if cursor.rowcount == 0:
        conn.commit()
        conn.close()
        return None

    task = _get_outbound_task_by_id(cursor, task_id)
    conn.commit()
    conn.close()
    if task and success and task.get("auto_send"):
        save_outbound_message_after_task(
            session_id=str(task.get("session_id") or ""),
            contact_name=str(task.get("contact_name") or ""),
            message=str(task.get("message") or ""),
            agent_id=agent_id,
        )
    return task


def save_outbound_message_after_task(session_id: str, contact_name: str, message: str, agent_id: int | None = None) -> None:
    if not session_id or not message.strip():
        return
    save_message(session_id, contact_name, "assistant", message, agent_id=agent_id)


def _get_outbound_task_by_id(cursor: sqlite3.Cursor, task_id: int) -> Optional[Dict[str, Any]]:
    cursor.execute("SELECT * FROM outbound_tasks WHERE id = ?", (task_id,))
    row = cursor.fetchone()
    return _outbound_task_public(row) if row else None


def _outbound_task_public(row: sqlite3.Row | Dict[str, Any]) -> Dict[str, Any]:
    item = dict(row)
    item["auto_send"] = bool(item.get("auto_send"))
    return item

def get_session_status(session_id: str) -> str:
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute("SELECT status FROM sessions WHERE session_id = ?", (session_id,))
    row = cursor.fetchone()
    conn.close()
    if row and row['status']:
        return normalize_session_status(row['status'])
    return 'auto'

def update_session_status(session_id: str, status: str, agent_id: int | None = None):
    status = normalize_session_status(status)
    conn = get_connection()
    cursor = conn.cursor()
    now_bj = get_beijing_time()
    if agent_id is None:
        cursor.execute(
            "UPDATE sessions SET status = ?, updated_at = ? WHERE session_id = ?",
            (status, now_bj, session_id)
        )
    else:
        cursor.execute(
            "UPDATE sessions SET status = ?, updated_at = ? WHERE session_id = ? AND agent_id = ?",
            (status, now_bj, session_id, agent_id)
        )
    conn.commit()
    conn.close()
