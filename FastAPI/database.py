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


def _json_dict(value: str | None) -> Dict[str, Any]:
    if not value:
        return {}
    try:
        parsed = json.loads(value)
    except Exception:
        return {}
    return parsed if isinstance(parsed, dict) else {}


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


def _add_contact_record_alias(cursor: sqlite3.Cursor, contact_id: int, alias: str | None) -> None:
    alias = canonicalize_contact_name(alias, default="")
    if not alias or alias in {"客户", "当前联系人"}:
        return

    cursor.execute("SELECT aliases_json FROM contacts WHERE id = ?", (contact_id,))
    row = cursor.fetchone()
    aliases = _json_list(row["aliases_json"] if row else None)
    if alias not in aliases:
        aliases.append(alias)
        cursor.execute(
            "UPDATE contacts SET aliases_json = ?, updated_at = ? WHERE id = ?",
            (json.dumps(aliases[-50:], ensure_ascii=False), get_beijing_time(), contact_id),
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
        "ALTER TABLE sessions ADD COLUMN contact_id INTEGER",
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

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS contacts (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            agent_id INTEGER,
            primary_name TEXT NOT NULL,
            aliases_json TEXT,
            manual_notes_json TEXT,
            memory_json TEXT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (agent_id) REFERENCES agents (id)
        )
    """)
    # 新增 preferred_name 字段（人工纠错称呼）
    for col_sql in [
        "ALTER TABLE contacts ADD COLUMN preferred_name TEXT",
    ]:
        try:
            cursor.execute(col_sql)
        except sqlite3.OperationalError:
            pass
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_contacts_agent_name ON contacts(agent_id, primary_name)")
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_sessions_contact ON sessions(contact_id)")

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
    for column_sql in [
        "ALTER TABLE outbound_tasks ADD COLUMN recorded_message_at TIMESTAMP",
    ]:
        try:
            cursor.execute(column_sql)
        except sqlite3.OperationalError:
            pass
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
                    SELECT s.session_id, s.contact_name, s.contact_aliases_json, s.suggested_remark,
                           c.preferred_name AS contact_preferred_name
                    FROM sessions s
                    LEFT JOIN contacts c ON c.id = s.contact_id
                    WHERE s.agent_id IS NULL
                    ORDER BY s.updated_at DESC
                    """
                )
            else:
                cursor.execute(
                    """
                    SELECT s.session_id, s.contact_name, s.contact_aliases_json, s.suggested_remark,
                           c.preferred_name AS contact_preferred_name
                    FROM sessions s
                    LEFT JOIN contacts c ON c.id = s.contact_id
                    WHERE s.agent_id = ?
                    ORDER BY s.updated_at DESC
                    """,
                    (agent_id,),
                )
            matching_rows = []
            for row in cursor.fetchall():
                known_names = [
                    row["contact_name"],
                    row["suggested_remark"],
                    row["contact_preferred_name"],
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


def _contact_public(row: sqlite3.Row | Dict[str, Any] | None) -> Optional[Dict[str, Any]]:
    if not row:
        return None
    item = dict(row)
    item["aliases"] = _json_list(item.get("aliases_json"))
    item["manual_notes"] = _json_dict(item.get("manual_notes_json"))
    item["memory"] = _json_dict(item.get("memory_json"))
    # 人工纠错称呼：优先使用 preferred_name，否则 fallback 到 primary_name
    item["display_name"] = item.get("preferred_name") or item.get("primary_name") or ""
    return item


def _find_contact_row(
    cursor: sqlite3.Cursor,
    contact_name: str,
    agent_id: int | None = None,
) -> Optional[sqlite3.Row]:
    canonical_name = canonicalize_contact_name(contact_name, default="")
    if not canonical_name:
        return None

    if agent_id is None:
        cursor.execute(
            """
            SELECT * FROM contacts
            WHERE agent_id IS NULL
            ORDER BY updated_at DESC, id DESC
            """
        )
    else:
        cursor.execute(
            """
            SELECT * FROM contacts
            WHERE agent_id = ?
            ORDER BY updated_at DESC, id DESC
            """,
            (agent_id,),
        )
    for row in cursor.fetchall():
        # 匹配时同时检查 primary_name、preferred_name 和所有别名
        known_names = [row["primary_name"], row["preferred_name"], *_json_list(row["aliases_json"])]
        if any(canonicalize_contact_name(name, default="") == canonical_name for name in known_names):
            return row
    return None


def resolve_contact(session_id: str, contact_name: str, agent_id: int | None = None) -> Dict[str, Any]:
    """解析或创建真实联系人，并把当前会话关联到联系人。"""
    canonical_name = canonicalize_contact_name(contact_name)
    now_bj = get_beijing_time()
    conn = get_connection()
    cursor = conn.cursor()
    try:
        cursor.execute(
            """
            SELECT c.*
            FROM sessions s
            JOIN contacts c ON c.id = s.contact_id
            WHERE s.session_id = ? AND (? IS NULL OR s.agent_id = ?)
            """,
            (session_id, agent_id, agent_id),
        )
        contact_row = cursor.fetchone()
        if not contact_row:
            contact_row = _find_contact_row(cursor, canonical_name, agent_id=agent_id)

        if contact_row:
            contact_id = int(contact_row["id"])
            _add_contact_record_alias(cursor, contact_id, canonical_name)
            cursor.execute(
                """
                UPDATE sessions
                SET contact_id = ?, agent_id = COALESCE(agent_id, ?), updated_at = ?
                WHERE session_id = ?
                """,
                (contact_id, agent_id, now_bj, session_id),
            )
            if cursor.rowcount == 0:
                cursor.execute(
                    """
                    INSERT INTO sessions (session_id, agent_id, contact_id, contact_name, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    (session_id, agent_id, contact_id, canonical_name, now_bj, now_bj),
                )
        else:
            cursor.execute(
                """
                INSERT INTO contacts (
                    agent_id, primary_name, aliases_json, manual_notes_json, memory_json,
                    created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    agent_id,
                    canonical_name,
                    json.dumps([canonical_name], ensure_ascii=False),
                    "{}",
                    "{}",
                    now_bj,
                    now_bj,
                ),
            )
            contact_id = int(cursor.lastrowid)
            cursor.execute(
                """
                UPDATE sessions
                SET contact_id = ?, agent_id = COALESCE(agent_id, ?), updated_at = ?
                WHERE session_id = ?
                """,
                (contact_id, agent_id, now_bj, session_id),
            )
            if cursor.rowcount == 0:
                cursor.execute(
                    """
                    INSERT INTO sessions (session_id, agent_id, contact_id, contact_name, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    (session_id, agent_id, contact_id, canonical_name, now_bj, now_bj),
                )

        conn.commit()
        cursor.execute("SELECT * FROM contacts WHERE id = ?", (contact_id,))
        return _contact_public(cursor.fetchone()) or {}
    finally:
        conn.close()


def link_session_contact(session_id: str, contact_id: int, agent_id: int | None = None) -> Optional[Dict[str, Any]]:
    now_bj = get_beijing_time()
    conn = get_connection()
    cursor = conn.cursor()
    if agent_id is None:
        cursor.execute(
            "UPDATE sessions SET contact_id = ?, updated_at = ? WHERE session_id = ?",
            (contact_id, now_bj, session_id),
        )
    else:
        cursor.execute(
            "UPDATE sessions SET contact_id = ?, updated_at = ? WHERE session_id = ? AND agent_id = ?",
            (contact_id, now_bj, session_id, agent_id),
        )
    conn.commit()
    cursor.execute("SELECT * FROM contacts WHERE id = ?", (contact_id,))
    contact = _contact_public(cursor.fetchone())
    conn.close()
    return contact


def get_contact(contact_id: int | None, agent_id: int | None = None) -> Optional[Dict[str, Any]]:
    if not contact_id:
        return None
    conn = get_connection()
    cursor = conn.cursor()
    if agent_id is None:
        cursor.execute("SELECT * FROM contacts WHERE id = ?", (contact_id,))
    else:
        cursor.execute("SELECT * FROM contacts WHERE id = ? AND agent_id = ?", (contact_id, agent_id))
    contact = _contact_public(cursor.fetchone())
    conn.close()
    return contact


def get_contact_for_session(session_id: str, agent_id: int | None = None) -> Optional[Dict[str, Any]]:
    session = get_session(session_id, agent_id=agent_id)
    if not session:
        return None
    contact_id = session.get("contact_id")
    if contact_id:
        return get_contact(int(contact_id), agent_id=agent_id)
    return resolve_contact(session_id, session.get("contact_name") or "客户", agent_id=session.get("agent_id"))


def update_contact_manual_notes(contact_id: int, manual_notes: Dict[str, Any], agent_id: int | None = None) -> Optional[Dict[str, Any]]:
    if not isinstance(manual_notes, dict):
        raise ValueError("人工补充资料必须是 JSON 对象")
    now_bj = get_beijing_time()
    conn = get_connection()
    cursor = conn.cursor()
    params: tuple[Any, ...]
    if agent_id is None:
        sql = "UPDATE contacts SET manual_notes_json = ?, updated_at = ? WHERE id = ?"
        params = (json.dumps(manual_notes, ensure_ascii=False), now_bj, contact_id)
    else:
        sql = "UPDATE contacts SET manual_notes_json = ?, updated_at = ? WHERE id = ? AND agent_id = ?"
        params = (json.dumps(manual_notes, ensure_ascii=False), now_bj, contact_id, agent_id)
    cursor.execute(sql, params)
    if cursor.rowcount == 0:
        conn.commit()
        conn.close()
        return None
    conn.commit()
    cursor.execute("SELECT * FROM contacts WHERE id = ?", (contact_id,))
    contact = _contact_public(cursor.fetchone())
    conn.close()
    return contact


def update_contact_preferred_name(contact_id: int, preferred_name: str, agent_id: int | None = None) -> Optional[Dict[str, Any]]:
    """人工修正联系人称呼，会同步更新 preferred_name 字段。
    同时把旧 primary_name 和新 preferred_name 都加入 aliases，
    确保 Agent 无论传入哪个名称都能匹配到同一个联系人/会话。
    """
    preferred_name = (preferred_name or "").strip()
    now_bj = get_beijing_time()
    conn = get_connection()
    cursor = conn.cursor()
    # 先查出旧记录，用于后续别名同步
    cursor.execute("SELECT * FROM contacts WHERE id = ?", (contact_id,))
    old_row = cursor.fetchone()
    if not old_row:
        conn.close()
        return None
    params: tuple[Any, ...]
    if agent_id is None:
        sql = "UPDATE contacts SET preferred_name = ?, updated_at = ? WHERE id = ?"
        params = (preferred_name or None, now_bj, contact_id)
    else:
        sql = "UPDATE contacts SET preferred_name = ?, updated_at = ? WHERE id = ? AND agent_id = ?"
        params = (preferred_name or None, now_bj, contact_id, agent_id)
    cursor.execute(sql, params)
    if cursor.rowcount == 0:
        conn.commit()
        conn.close()
        return None
    # 把旧 primary_name（可能是 OCR 错误名）和新 preferred_name 都加入 contact 别名
    old_primary = old_row["primary_name"] or ""
    if old_primary:
        _add_contact_record_alias(cursor, contact_id, old_primary)
    if preferred_name:
        _add_contact_record_alias(cursor, contact_id, preferred_name)
    # 同步到关联的 sessions 的 contact_aliases_json，确保 resolve_session_id 也能匹配
    cursor.execute("SELECT session_id FROM sessions WHERE contact_id = ?", (contact_id,))
    linked_sessions = cursor.fetchall()
    for sess_row in linked_sessions:
        sid = sess_row["session_id"]
        if old_primary:
            _add_contact_alias(cursor, sid, old_primary)
        if preferred_name:
            _add_contact_alias(cursor, sid, preferred_name)
    conn.commit()
    cursor.execute("SELECT * FROM contacts WHERE id = ?", (contact_id,))
    contact = _contact_public(cursor.fetchone())
    conn.close()
    return contact


def _merge_mapping(target: Dict[str, Any], incoming: Dict[str, Any], now_bj: str, session_id: str | None) -> Dict[str, Any]:
    result = dict(target or {})
    for key, value in (incoming or {}).items():
        if not _is_meaningful_profile_value(value):
            continue
        if isinstance(value, dict) and "value" in value:
            stored_value = value.get("value")
            confidence = value.get("confidence") or "medium"
        else:
            stored_value = value
            confidence = "medium"
        if not _is_meaningful_profile_value(stored_value):
            continue
        result[key] = {
            "value": stored_value,
            "source": "conversation",
            "confidence": confidence,
            "updated_at": now_bj,
            "session_id": session_id,
        }
    return result


def merge_contact_memory(
    contact_id: int,
    extracted_memory: Dict[str, Any],
    session_id: str | None = None,
    agent_id: int | None = None,
) -> Optional[Dict[str, Any]]:
    """把模型抽取的增量记忆合并到联系人长期记忆，不覆盖人工补充资料。"""
    if not extracted_memory:
        return get_contact(contact_id, agent_id=agent_id)

    now_bj = get_beijing_time()
    conn = get_connection()
    cursor = conn.cursor()
    if agent_id is None:
        cursor.execute("SELECT * FROM contacts WHERE id = ?", (contact_id,))
    else:
        cursor.execute("SELECT * FROM contacts WHERE id = ? AND agent_id = ?", (contact_id, agent_id))
    row = cursor.fetchone()
    if not row:
        conn.close()
        return None

    memory = _json_dict(row["memory_json"])
    stable_profile = _merge_mapping(
        _json_dict(json.dumps(memory.get("stable_profile") or {}, ensure_ascii=False)),
        extracted_memory.get("stable_profile") or {},
        now_bj,
        session_id,
    )
    dynamic_state = _merge_mapping(
        _json_dict(json.dumps(memory.get("dynamic_state") or {}, ensure_ascii=False)),
        extracted_memory.get("dynamic_state") or {},
        now_bj,
        session_id,
    )

    facts = list(memory.get("facts") or [])
    incoming_facts = extracted_memory.get("facts") or []
    if isinstance(incoming_facts, dict):
        incoming_facts = [incoming_facts]
    for fact in incoming_facts:
        if isinstance(fact, dict):
            value = fact.get("value") or fact.get("text") or fact.get("fact")
            confidence = fact.get("confidence") or "medium"
            category = fact.get("category") or "背景事实"
        else:
            value = str(fact or "").strip()
            confidence = "medium"
            category = "背景事实"
        if not _is_meaningful_profile_value(value):
            continue
        item = {
            "category": category,
            "value": value,
            "source": "conversation",
            "confidence": confidence,
            "updated_at": now_bj,
            "session_id": session_id,
        }
        if not any(str(existing.get("value")) == str(value) for existing in facts if isinstance(existing, dict)):
            facts.append(item)

    memory.update(
        {
            "stable_profile": stable_profile,
            "dynamic_state": dynamic_state,
            "facts": facts[-80:],
            "last_merged_at": now_bj,
        }
    )
    cursor.execute(
        "UPDATE contacts SET memory_json = ?, updated_at = ? WHERE id = ?",
        (json.dumps(memory, ensure_ascii=False), now_bj, contact_id),
    )
    conn.commit()
    cursor.execute("SELECT * FROM contacts WHERE id = ?", (contact_id,))
    contact = _contact_public(cursor.fetchone())
    conn.close()
    return contact

def _save_message_with_cursor(
    cursor: sqlite3.Cursor,
    session_id: str,
    contact_name: str,
    role: str,
    content: str,
    agent_id: int | None = None,
    created_at: str | None = None,
) -> None:
    contact_name = canonicalize_contact_name(contact_name)

    now_bj = created_at or get_beijing_time()
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


def save_message(session_id: str, contact_name: str, role: str, content: str, agent_id: int | None = None):
    conn = get_connection()
    cursor = conn.cursor()
    _save_message_with_cursor(cursor, session_id, contact_name, role, content, agent_id=agent_id)
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
            SELECT s.session_id, s.agent_id, s.contact_id, s.contact_name, s.updated_at, s.profile_json, s.status,
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
        SELECT ps.session_id, ps.agent_id, ps.contact_id, ps.contact_name, ps.updated_at, ps.profile_json, ps.status,
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
            SELECT s.session_id, s.agent_id, s.contact_id, s.contact_name, s.updated_at, s.profile_json, s.status,
                   s.contact_aliases_json, s.suggested_remark, s.suggested_remark_reason,
                   s.suggested_remark_confidence, s.suggested_remark_status, s.suggested_remark_updated_at,
                   c.primary_name AS contact_primary_name, c.manual_notes_json AS contact_manual_notes_json,
                   c.memory_json AS contact_memory_json,
                   a.username AS agent_username, a.display_name AS agent_display_name
            FROM sessions s
            LEFT JOIN contacts c ON c.id = s.contact_id
            LEFT JOIN agents a ON a.id = s.agent_id
            WHERE s.session_id = ?
            """,
            (session_id,),
        )
    else:
        cursor.execute(
            """
            SELECT s.session_id, s.agent_id, s.contact_id, s.contact_name, s.updated_at, s.profile_json, s.status,
                   s.contact_aliases_json, s.suggested_remark, s.suggested_remark_reason,
                   s.suggested_remark_confidence, s.suggested_remark_status, s.suggested_remark_updated_at,
                   c.primary_name AS contact_primary_name, c.manual_notes_json AS contact_manual_notes_json,
                   c.memory_json AS contact_memory_json,
                   a.username AS agent_username, a.display_name AS agent_display_name
            FROM sessions s
            LEFT JOIN contacts c ON c.id = s.contact_id
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
    item["contact_manual_notes"] = _json_dict(item.get("contact_manual_notes_json"))
    item["contact_memory"] = _json_dict(item.get("contact_memory_json"))
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
    cursor.execute("SELECT contact_id, contact_name, suggested_remark FROM sessions WHERE session_id = ?", (session_id,))
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
        if row["contact_id"]:
            _add_contact_record_alias(cursor, int(row["contact_id"]), row["contact_name"])
            _add_contact_record_alias(cursor, int(row["contact_id"]), row["suggested_remark"])
            _add_contact_record_alias(cursor, int(row["contact_id"]), remark)
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

def merge_sessions(
    source_session_id: str,
    target_session_id: str,
    agent_id: int | None = None,
) -> Dict[str, Any]:
    """将 source 会话的所有消息和元数据物理合并到 target 会话中，然后删除 source 会话。

    合并逻辑：
    1. 消息：source 的所有 messages 搬到 target（按 created_at 自然排序）
    2. 别名：source 的所有别名合并进 target 和 target 的 contact
    3. 画像：如果 target 没有画像但 source 有，继承 source 的画像
    4. 联系人：如果 source 关联了不同 contact，将其记忆合并到 target 的 contact
    5. 状态：保留 target 的 status（如果 target 是 auto 且 source 有人工接管等状态，继承 source 的）
    6. 外发任务：将 source 的 outbound_tasks 关联到 target
    7. 删除 source 会话
    """
    if source_session_id == target_session_id:
        raise ValueError("不能将会话合并到自己")

    now_bj = get_beijing_time()
    conn = get_connection()
    cursor = conn.cursor()
    try:
        # 查询两个会话的信息
        cursor.execute("SELECT * FROM sessions WHERE session_id = ?", (source_session_id,))
        source_row = cursor.fetchone()
        if not source_row:
            raise ValueError(f"源会话不存在: {source_session_id}")

        cursor.execute("SELECT * FROM sessions WHERE session_id = ?", (target_session_id,))
        target_row = cursor.fetchone()
        if not target_row:
            raise ValueError(f"目标会话不存在: {target_session_id}")

        # --- 1. 迁移所有消息 ---
        cursor.execute(
            "UPDATE messages SET session_id = ? WHERE session_id = ?",
            (target_session_id, source_session_id),
        )
        migrated_count = cursor.rowcount

        # --- 2. 合并别名 ---
        source_aliases = _json_list(source_row["contact_aliases_json"])
        target_aliases = _json_list(target_row["contact_aliases_json"])
        # 把 source 的 contact_name 也加入别名
        source_contact_name = canonicalize_contact_name(source_row["contact_name"], default="")
        if source_contact_name and source_contact_name not in {"客户", "当前联系人"}:
            if source_contact_name not in source_aliases:
                source_aliases.append(source_contact_name)

        merged_aliases = list(target_aliases)
        for alias in source_aliases:
            if alias and alias not in merged_aliases:
                merged_aliases.append(alias)
        cursor.execute(
            "UPDATE sessions SET contact_aliases_json = ? WHERE session_id = ?",
            (json.dumps(merged_aliases[-30:], ensure_ascii=False), target_session_id),
        )

        # --- 3. 合并画像 (profile_json) ---
        target_profile = _json_dict(target_row["profile_json"])
        source_profile = _json_dict(source_row["profile_json"])
        if not target_profile and source_profile:
            # target 没有画像但 source 有，直接继承
            cursor.execute(
                "UPDATE sessions SET profile_json = ? WHERE session_id = ?",
                (json.dumps(source_profile, ensure_ascii=False), target_session_id),
            )
        elif target_profile and source_profile:
            # 两个都有画像，补充 target 中缺失的字段
            for key, value in source_profile.items():
                if key not in target_profile or not _is_meaningful_profile_value(target_profile[key]):
                    if _is_meaningful_profile_value(value):
                        target_profile[key] = value
            cursor.execute(
                "UPDATE sessions SET profile_json = ? WHERE session_id = ?",
                (json.dumps(target_profile, ensure_ascii=False), target_session_id),
            )

        # --- 4. 合并状态：如果 target 是 auto 且 source 有更高优先级状态，继承之 ---
        target_status = normalize_session_status(target_row["status"])
        source_status = normalize_session_status(source_row["status"])
        status_priority = {"auto": 0, "handoff_requested": 1, "human_takeover": 2, "muted": 3}
        if status_priority.get(source_status, 0) > status_priority.get(target_status, 0):
            cursor.execute(
                "UPDATE sessions SET status = ? WHERE session_id = ?",
                (source_status, target_session_id),
            )

        # --- 5. 合并联系人 (Contact) ---
        source_contact_id = source_row["contact_id"]
        target_contact_id = target_row["contact_id"]

        if source_contact_id and target_contact_id and int(source_contact_id) != int(target_contact_id):
            # 两个会话绑定了不同的联系人，需要合并联系人记忆
            cursor.execute("SELECT * FROM contacts WHERE id = ?", (source_contact_id,))
            source_contact = cursor.fetchone()
            cursor.execute("SELECT * FROM contacts WHERE id = ?", (target_contact_id,))
            target_contact = cursor.fetchone()

            if source_contact and target_contact:
                # 合并联系人别名
                src_c_aliases = _json_list(source_contact["aliases_json"])
                tgt_c_aliases = _json_list(target_contact["aliases_json"])
                src_primary = source_contact["primary_name"] or ""
                if src_primary and src_primary not in src_c_aliases:
                    src_c_aliases.append(src_primary)
                merged_c_aliases = list(tgt_c_aliases)
                for alias in src_c_aliases:
                    if alias and alias not in merged_c_aliases:
                        merged_c_aliases.append(alias)
                cursor.execute(
                    "UPDATE contacts SET aliases_json = ?, updated_at = ? WHERE id = ?",
                    (json.dumps(merged_c_aliases[-50:], ensure_ascii=False), now_bj, target_contact_id),
                )

                # 合并联系人长期记忆 (memory_json)
                src_memory = _json_dict(source_contact["memory_json"])
                tgt_memory = _json_dict(target_contact["memory_json"])
                if src_memory and not tgt_memory:
                    cursor.execute(
                        "UPDATE contacts SET memory_json = ?, updated_at = ? WHERE id = ?",
                        (json.dumps(src_memory, ensure_ascii=False), now_bj, target_contact_id),
                    )
                elif src_memory and tgt_memory:
                    # 合并 stable_profile
                    for section in ("stable_profile", "dynamic_state"):
                        src_section = src_memory.get(section) or {}
                        tgt_section = tgt_memory.get(section) or {}
                        for key, value in src_section.items():
                            if key not in tgt_section:
                                tgt_section[key] = value
                        tgt_memory[section] = tgt_section
                    # 合并 facts（去重）
                    tgt_facts = list(tgt_memory.get("facts") or [])
                    src_facts = list(src_memory.get("facts") or [])
                    existing_values = {str(f.get("value", "")) for f in tgt_facts if isinstance(f, dict)}
                    for fact in src_facts:
                        if isinstance(fact, dict) and str(fact.get("value", "")) not in existing_values:
                            tgt_facts.append(fact)
                            existing_values.add(str(fact.get("value", "")))
                    tgt_memory["facts"] = tgt_facts[-80:]
                    tgt_memory["last_merged_at"] = now_bj
                    cursor.execute(
                        "UPDATE contacts SET memory_json = ?, updated_at = ? WHERE id = ?",
                        (json.dumps(tgt_memory, ensure_ascii=False), now_bj, target_contact_id),
                    )

                # 合并人工补充资料 (manual_notes_json)
                src_notes = _json_dict(source_contact["manual_notes_json"])
                tgt_notes = _json_dict(target_contact["manual_notes_json"])
                if src_notes and not tgt_notes:
                    cursor.execute(
                        "UPDATE contacts SET manual_notes_json = ?, updated_at = ? WHERE id = ?",
                        (json.dumps(src_notes, ensure_ascii=False), now_bj, target_contact_id),
                    )
                elif src_notes and tgt_notes:
                    for key, value in src_notes.items():
                        if key not in tgt_notes:
                            tgt_notes[key] = value
                    cursor.execute(
                        "UPDATE contacts SET manual_notes_json = ?, updated_at = ? WHERE id = ?",
                        (json.dumps(tgt_notes, ensure_ascii=False), now_bj, target_contact_id),
                    )

                # 将指向 source_contact 的其他会话也指向 target_contact
                cursor.execute(
                    "UPDATE sessions SET contact_id = ? WHERE contact_id = ?",
                    (target_contact_id, source_contact_id),
                )
                # 将指向 source_contact 的外发任务也指向 target session
                cursor.execute(
                    "UPDATE outbound_tasks SET session_id = ? WHERE session_id = ?",
                    (target_session_id, source_session_id),
                )
                # 删除 source contact（已无引用）
                cursor.execute(
                    "SELECT COUNT(*) AS cnt FROM sessions WHERE contact_id = ?",
                    (source_contact_id,),
                )
                if cursor.fetchone()["cnt"] == 0:
                    cursor.execute("DELETE FROM contacts WHERE id = ?", (source_contact_id,))

        elif source_contact_id and not target_contact_id:
            # target 没有 contact 但 source 有，把 contact 让给 target
            cursor.execute(
                "UPDATE sessions SET contact_id = ? WHERE session_id = ?",
                (source_contact_id, target_session_id),
            )

        # --- 6. 外发任务迁移 ---
        cursor.execute(
            "UPDATE outbound_tasks SET session_id = ? WHERE session_id = ?",
            (target_session_id, source_session_id),
        )

        # --- 7. 更新 target 的时间戳 ---
        cursor.execute(
            "UPDATE sessions SET updated_at = ? WHERE session_id = ?",
            (now_bj, target_session_id),
        )

        # --- 8. 删除 source 会话 ---
        cursor.execute("DELETE FROM sessions WHERE session_id = ?", (source_session_id,))

        conn.commit()

        # 返回合并后的 target 会话信息
        result = {
            "target_session_id": target_session_id,
            "source_session_id": source_session_id,
            "migrated_messages": migrated_count,
            "merged_aliases": merged_aliases,
        }
        return result
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def find_existing_session_for_contact(
    contact_name: str,
    exclude_session_id: str,
    agent_id: int | None = None,
) -> str | None:
    """根据联系人名在已有会话中查找匹配的 session_id（排除指定的当前会话）。
    用于 VLM 纠正名称后自动触发合并。
    """
    canonical_name = canonicalize_contact_name(contact_name, default="")
    if not canonical_name or canonical_name in {"客户", "当前联系人"}:
        return None

    conn = get_connection()
    cursor = conn.cursor()
    try:
        if agent_id is None:
            cursor.execute(
                """
                SELECT s.session_id, s.contact_name, s.contact_aliases_json, s.suggested_remark,
                       c.preferred_name AS contact_preferred_name
                FROM sessions s
                LEFT JOIN contacts c ON c.id = s.contact_id
                WHERE s.agent_id IS NULL
                ORDER BY s.updated_at DESC
                """
            )
        else:
            cursor.execute(
                """
                SELECT s.session_id, s.contact_name, s.contact_aliases_json, s.suggested_remark,
                       c.preferred_name AS contact_preferred_name
                FROM sessions s
                LEFT JOIN contacts c ON c.id = s.contact_id
                WHERE s.agent_id = ?
                ORDER BY s.updated_at DESC
                """,
                (agent_id,),
            )
        for row in cursor.fetchall():
            if row["session_id"] == exclude_session_id:
                continue
            known_names = [
                row["contact_name"],
                row["suggested_remark"],
                row["contact_preferred_name"],
                *_json_list(row["contact_aliases_json"]),
            ]
            if any(canonicalize_contact_name(name, default="") == canonical_name for name in known_names):
                return row["session_id"]
        return None
    finally:
        conn.close()


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
        search_keyword = contact_name
    if not message:
        raise ValueError("发送内容不能为空")

    now_bj = get_beijing_time()
    resolved_session_id = resolve_session_id(
        session_id or f"manual_outbound:{contact_name}",
        contact_name,
        agent_id=agent_id,
    )
    resolve_contact(resolved_session_id, contact_name, agent_id=agent_id)
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
    if task and success and task.get("auto_send") and not task.get("recorded_message_at"):
        session_id = str(task.get("session_id") or "")
        contact_name = str(task.get("contact_name") or "")
        message = str(task.get("message") or "").strip()
        if session_id and message:
            _save_message_with_cursor(cursor, session_id, contact_name, "assistant", message, agent_id=agent_id, created_at=now_bj)
            cursor.execute(
                "UPDATE outbound_tasks SET recorded_message_at = ?, updated_at = ? WHERE id = ?",
                (now_bj, now_bj, task_id),
            )
            task = _get_outbound_task_by_id(cursor, task_id)
    conn.commit()
    conn.close()
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
