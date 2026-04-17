import sqlite3
from pathlib import Path
from typing import Optional, List, Dict, Any
import json
from datetime import datetime

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
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    """)
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
    cursor.execute("SELECT session_id FROM sessions WHERE session_id = ?", (session_id,))
    if not cursor.fetchone():
        cursor.execute(
            "INSERT INTO sessions (session_id, contact_name) VALUES (?, ?)",
            (session_id, contact_name)
        )
    else:
        cursor.execute(
            "UPDATE sessions SET updated_at = CURRENT_TIMESTAMP WHERE session_id = ?",
            (session_id,)
        )
        
    # Insert message
    cursor.execute(
        "INSERT INTO messages (session_id, role, content) VALUES (?, ?, ?)",
        (session_id, role, content)
    )
    conn.commit()
    conn.close()

def get_all_sessions() -> List[Dict[str, Any]]:
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute("""
        SELECT s.session_id, s.contact_name, s.updated_at, s.profile_json,
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
        "SELECT role, content, created_at FROM messages WHERE session_id = ? ORDER BY created_at ASC",
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
