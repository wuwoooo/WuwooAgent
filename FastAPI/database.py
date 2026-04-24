import sqlite3
from pathlib import Path
from typing import Optional, List, Dict, Any
import json
from datetime import datetime, timezone, timedelta

def get_beijing_time() -> str:
    """获取格式化的北京时间字符串"""
    tz = timezone(timedelta(hours=8))
    return datetime.now(tz).strftime("%Y-%m-%d %H:%M:%S")

BASE_DIR = Path(__file__).resolve().parent
DB_PATH = BASE_DIR / "agent_chat.db"

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

def save_message(session_id: str, contact_name: str, role: str, content: str):
    conn = get_connection()
    cursor = conn.cursor()
    
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
    return [dict(row) for row in rows]

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
        return row['status']
    return 'auto'

def update_session_status(session_id: str, status: str):
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute(
        "UPDATE sessions SET status = ? WHERE session_id = ?",
        (status, session_id)
    )
    conn.commit()
    conn.close()
