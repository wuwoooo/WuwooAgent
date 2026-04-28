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


def normalize_session_status(status: str | None) -> str:
    normalized = (status or "").strip().lower()
    normalized = LEGACY_STATUS_MAP.get(normalized, normalized)
    return normalized if normalized in VALID_SESSION_STATUSES else "auto"

def init_db():
    conn = sqlite3.connect(DB_PATH)
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
    # Add status column if it doesn't exist (for older DBs)
    try:
        cursor.execute("ALTER TABLE sessions ADD COLUMN status TEXT DEFAULT 'auto'")
    except sqlite3.OperationalError:
        pass  # Column already exists
    try:
        cursor.execute("ALTER TABLE sessions ADD COLUMN agent_id INTEGER")
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
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_messages_session_created ON messages(session_id, created_at)")
    conn.commit()
    conn.close()

def get_connection():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


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
    """优先复用同一规范化联系人已有的后端会话，兼容旧客户端的不同 session_id。"""
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
                cursor.execute("SELECT session_id, contact_name FROM sessions WHERE agent_id IS NULL ORDER BY updated_at DESC")
            else:
                cursor.execute("SELECT session_id, contact_name FROM sessions WHERE agent_id = ? ORDER BY updated_at DESC", (agent_id,))
            matching_rows = [
                row
                for row in cursor.fetchall()
                if canonicalize_contact_name(row["contact_name"]) == canonical_name
            ]
            preferred_row = next(
                (row for row in matching_rows if row["contact_name"] == canonical_name),
                matching_rows[0] if matching_rows else None,
            )
            if preferred_row:
                if preferred_row["contact_name"] != canonical_name:
                    cursor.execute(
                        "UPDATE sessions SET contact_name = ? WHERE session_id = ?",
                        (canonical_name, preferred_row["session_id"]),
                    )
                    conn.commit()
                return preferred_row["session_id"]

        if exact_row:
            if exact_row["contact_name"] != canonical_name:
                cursor.execute(
                    "UPDATE sessions SET contact_name = ? WHERE session_id = ?",
                    (canonical_name, incoming_session_id),
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
    else:
        cursor.execute(
            "UPDATE sessions SET updated_at = ?, agent_id = COALESCE(agent_id, ?) WHERE session_id = ?",
            (now_bj, agent_id, session_id)
        )
        
    # Insert message
    cursor.execute(
        "INSERT INTO messages (session_id, role, content, created_at) VALUES (?, ?, ?, ?)",
        (session_id, role, content, now_bj)
    )
    conn.commit()
    conn.close()

def get_all_sessions(agent_id: int | None = None) -> List[Dict[str, Any]]:
    conn = get_connection()
    cursor = conn.cursor()
    params: tuple[Any, ...] = ()
    where_sql = ""
    if agent_id is not None:
        where_sql = "WHERE s.agent_id = ?"
        params = (agent_id,)
    cursor.execute(f"""
        SELECT s.session_id, s.agent_id, s.contact_name, s.updated_at, s.profile_json, s.status,
               a.username AS agent_username, a.display_name AS agent_display_name,
               (SELECT COUNT(*) FROM messages m WHERE m.session_id = s.session_id) as msg_count,
               (SELECT content FROM messages m WHERE m.session_id = s.session_id ORDER BY created_at DESC LIMIT 1) as last_msg
        FROM sessions s
        LEFT JOIN agents a ON a.id = s.agent_id
        {where_sql}
        ORDER BY s.updated_at DESC
    """, params)
    rows = cursor.fetchall()
    conn.close()
    sessions = []
    for row in rows:
        item = dict(row)
        item["status"] = normalize_session_status(item.get("status"))
        sessions.append(item)
    return sessions

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
    cursor.execute(
        "UPDATE sessions SET profile_json = ? WHERE session_id = ?",
        (profile_str, session_id)
    )
    conn.commit()
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
