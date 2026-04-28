import sqlite3
from pathlib import Path
from typing import Optional, List, Dict, Any
import json
import re
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
    # Create sessions table
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS sessions (
            session_id TEXT PRIMARY KEY,
            contact_name TEXT,
            profile_json TEXT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            status TEXT DEFAULT 'auto'
        )
    """)
    # Add status column if it doesn't exist (for older DBs)
    try:
        cursor.execute("ALTER TABLE sessions ADD COLUMN status TEXT DEFAULT 'auto'")
    except sqlite3.OperationalError:
        pass  # Column already exists

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
    conn.commit()
    conn.close()

def get_connection():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def resolve_session_id(session_id: str, contact_name: str) -> str:
    """优先复用同一规范化联系人已有的后端会话，兼容旧客户端的不同 session_id。"""
    incoming_session_id = (session_id or "").strip()
    canonical_name = canonicalize_contact_name(contact_name)
    if not incoming_session_id:
        return incoming_session_id

    conn = get_connection()
    cursor = conn.cursor()
    try:
        cursor.execute("SELECT contact_name FROM sessions WHERE session_id = ?", (incoming_session_id,))
        exact_row = cursor.fetchone()

        if canonical_name not in {"客户", "当前联系人"}:
            cursor.execute("SELECT session_id, contact_name FROM sessions ORDER BY updated_at DESC")
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

def save_message(session_id: str, contact_name: str, role: str, content: str):
    conn = get_connection()
    cursor = conn.cursor()
    contact_name = canonicalize_contact_name(contact_name)
    
    # Ensure session exists
    now_bj = get_beijing_time()
    cursor.execute("SELECT session_id FROM sessions WHERE session_id = ?", (session_id,))
    if not cursor.fetchone():
        cursor.execute(
            "INSERT INTO sessions (session_id, contact_name, created_at, updated_at) VALUES (?, ?, ?, ?)",
            (session_id, contact_name, now_bj, now_bj)
        )
    else:
        cursor.execute(
            "UPDATE sessions SET updated_at = ? WHERE session_id = ?",
            (now_bj, session_id)
        )
        
    # Insert message
    cursor.execute(
        "INSERT INTO messages (session_id, role, content, created_at) VALUES (?, ?, ?, ?)",
        (session_id, role, content, now_bj)
    )
    conn.commit()
    conn.close()

def get_all_sessions() -> List[Dict[str, Any]]:
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute("""
        SELECT s.session_id, s.contact_name, s.updated_at, s.profile_json, s.status,
               (SELECT COUNT(*) FROM messages m WHERE m.session_id = s.session_id) as msg_count,
               (SELECT content FROM messages m WHERE m.session_id = s.session_id ORDER BY created_at DESC LIMIT 1) as last_msg
        FROM sessions s
        ORDER BY s.updated_at DESC
    """)
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

def get_session(session_id: str) -> Optional[Dict[str, Any]]:
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute(
        "SELECT session_id, contact_name, updated_at, profile_json, status FROM sessions WHERE session_id = ?",
        (session_id,),
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

def delete_session(session_id: str):
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute("DELETE FROM messages WHERE session_id = ?", (session_id,))
    cursor.execute("DELETE FROM sessions WHERE session_id = ?", (session_id,))
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

def update_session_status(session_id: str, status: str):
    status = normalize_session_status(status)
    conn = get_connection()
    cursor = conn.cursor()
    now_bj = get_beijing_time()
    cursor.execute(
        "UPDATE sessions SET status = ?, updated_at = ? WHERE session_id = ?",
        (status, now_bj, session_id)
    )
    conn.commit()
    conn.close()
