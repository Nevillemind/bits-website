"""
TRACE Shield — Database Layer (SQLite MVP)
"""
import sqlite3
import os

DB_PATH = os.path.join(os.path.dirname(__file__), "../db/trace_shield.db")

def get_conn():
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    conn = get_conn()
    c = conn.cursor()
    c.execute("""
        CREATE TABLE IF NOT EXISTS creators (
            id TEXT PRIMARY KEY,
            email TEXT UNIQUE NOT NULL,
            name TEXT NOT NULL,
            plan TEXT DEFAULT 'shield',
            verifications_used INTEGER DEFAULT 0,
            created_at TEXT DEFAULT (datetime('now')),
            stripe_customer_id TEXT,
            stripe_subscription_id TEXT,
            subscription_status TEXT DEFAULT 'trial',
            active INTEGER DEFAULT 1,
            notes TEXT
        )
    """)
    c.execute("""
        CREATE TABLE IF NOT EXISTS certificates (
            cert_id TEXT PRIMARY KEY,
            creator_id TEXT NOT NULL,
            filename TEXT NOT NULL,
            sha256_hash TEXT NOT NULL,
            perceptual_hash TEXT,
            file_size_bytes INTEGER,
            solana_tx TEXT,
            solana_slot INTEGER,
            cert_url TEXT,
            created_at TEXT DEFAULT (datetime('now')),
            FOREIGN KEY (creator_id) REFERENCES creators(id)
        )
    """)
    c.execute("""
        CREATE TABLE IF NOT EXISTS dispute_checks (
            id TEXT PRIMARY KEY,
            checker_ip TEXT,
            filename TEXT,
            sha256_hash TEXT,
            result TEXT,
            matched_cert_id TEXT,
            created_at TEXT DEFAULT (datetime('now'))
        )
    """)
    conn.commit()
    conn.close()
    print("✅ TRACE Shield DB initialized")

init_db()
