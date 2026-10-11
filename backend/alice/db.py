"""SQLite helpers. Short lock timeout. Callers must not wait while a transaction is open."""

from __future__ import annotations

import os
import sqlite3
from contextlib import contextmanager
from pathlib import Path

from config import SQLITE_BUSY_MS, SQLITE_TIMEOUT_S

_SHARED_PARENTS = {
    Path("/"),
    Path("/tmp"),
    Path("/root"),
    Path("/opt"),
    Path("/var"),
    Path("/home"),
    Path("/usr"),
}


class LockBusy(sqlite3.OperationalError):
    pass


SCHEMA = """
CREATE TABLE IF NOT EXISTS installations (
    id TEXT PRIMARY KEY,
    credential_hash TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL,
    selected_bake_key TEXT,
    selected_generation TEXT,
    selected_at INTEGER
);
CREATE TABLE IF NOT EXISTS grants (
    id TEXT PRIMARY KEY,
    installation_id TEXT NOT NULL,
    bake_key TEXT NOT NULL,
    selection_generation TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    revoked_at INTEGER,
    refresh_family TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS access_tokens (
    token_hash TEXT PRIMARY KEY,
    grant_id TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    revoked_at INTEGER
);
CREATE TABLE IF NOT EXISTS refresh_tokens (
    token_hash TEXT PRIMARY KEY,
    grant_id TEXT NOT NULL,
    family TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    rotated_at INTEGER,
    revoked_at INTEGER
);
CREATE TABLE IF NOT EXISTS auth_codes (
    code_hash TEXT PRIMARY KEY,
    challenge_id TEXT NOT NULL,
    grant_id TEXT,
    expires_at INTEGER NOT NULL,
    used_at INTEGER,
    redirect_uri TEXT NOT NULL,
    client_id TEXT NOT NULL,
    scope TEXT NOT NULL,
    state TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS challenges (
    id TEXT PRIMARY KEY,
    code_hash TEXT NOT NULL UNIQUE,
    client_id TEXT NOT NULL,
    redirect_uri TEXT NOT NULL,
    scope TEXT NOT NULL,
    state TEXT NOT NULL,
    browser_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    approved_at INTEGER,
    installation_id TEXT,
    bake_key TEXT,
    selection_generation TEXT,
    consumed_at INTEGER
);
CREATE TABLE IF NOT EXISTS snapshots (
    installation_id TEXT PRIMARY KEY,
    bake_key TEXT NOT NULL,
    selection_generation TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    received_at INTEGER NOT NULL,
    connection_id TEXT NOT NULL,
    clock_sane INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS leases (
    installation_id TEXT PRIMARY KEY,
    connection_id TEXT NOT NULL,
    selection_generation TEXT NOT NULL,
    bake_key TEXT NOT NULL,
    last_poll_at INTEGER NOT NULL,
    boot_id TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS commands (
    id TEXT PRIMARY KEY,
    installation_id TEXT NOT NULL,
    connection_id TEXT NOT NULL,
    selection_generation TEXT NOT NULL,
    bake_key TEXT NOT NULL,
    operation TEXT NOT NULL,
    recipe_revision TEXT NOT NULL,
    expected_revision INTEGER NOT NULL,
    expected_step INTEGER NOT NULL,
    expected_paused INTEGER NOT NULL,
    issued_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    boot_id TEXT NOT NULL,
    state TEXT NOT NULL,
    dispatched_at INTEGER,
    session_id TEXT,
    intent_id TEXT
);
CREATE TABLE IF NOT EXISTS receipts (
    command_id TEXT PRIMARY KEY,
    installation_id TEXT NOT NULL,
    status TEXT NOT NULL,
    new_revision INTEGER,
    received_at INTEGER NOT NULL,
    connection_id TEXT NOT NULL,
    expected_revision INTEGER NOT NULL,
    late INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS pending_intents (
    id TEXT PRIMARY KEY,
    grant_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    operation TEXT NOT NULL,
    recipe_id TEXT NOT NULL,
    recipe_revision TEXT NOT NULL,
    step_index INTEGER NOT NULL,
    bake_key TEXT NOT NULL,
    revision INTEGER NOT NULL,
    selection_generation TEXT NOT NULL,
    expected_paused INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    consumed_at INTEGER,
    boot_id TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS rate_buckets (
    bucket TEXT NOT NULL,
    window INTEGER NOT NULL,
    count INTEGER NOT NULL,
    PRIMARY KEY (bucket, window)
);
CREATE TABLE IF NOT EXISTS retired_connections (
    installation_id TEXT NOT NULL,
    connection_id TEXT NOT NULL,
    retired_at INTEGER NOT NULL,
    PRIMARY KEY (installation_id, connection_id)
);
CREATE INDEX IF NOT EXISTS idx_grants_install ON grants(installation_id);
CREATE INDEX IF NOT EXISTS idx_commands_conn ON commands(connection_id, state);
CREATE INDEX IF NOT EXISTS idx_refresh_family ON refresh_tokens(family);
"""


def _reject_shared_parent(parent: Path) -> None:
    resolved = parent.resolve()
    if resolved in _SHARED_PARENTS or resolved.parent == Path("/"):
        # Allow a dedicated child such as /root/.hermes/... but not /root itself.
        if resolved in _SHARED_PARENTS:
            raise RuntimeError("refusing to chmod a shared directory")


def init_db(db_path: str) -> None:
    path = Path(db_path)
    parent = path.parent
    if str(parent) in ("", "."):
        raise RuntimeError("database path must include a dedicated parent directory")
    _reject_shared_parent(parent)
    parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(parent, 0o700)
    conn = sqlite3.connect(str(path), timeout=SQLITE_TIMEOUT_S, isolation_level=None)
    try:
        conn.execute(f"PRAGMA busy_timeout={int(SQLITE_BUSY_MS)}")
        conn.execute("PRAGMA foreign_keys=ON")
        conn.execute("PRAGMA journal_mode=WAL")
        conn.executescript(SCHEMA)
        conn.execute("PRAGMA user_version=1")
    finally:
        conn.close()
    os.chmod(path, 0o600)
    for suffix in ("-wal", "-shm"):
        side = Path(str(path) + suffix)
        if side.exists():
            os.chmod(side, 0o600)


def connect(db_path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(
        db_path,
        timeout=SQLITE_TIMEOUT_S,
        isolation_level=None,
        check_same_thread=False,
    )
    conn.row_factory = sqlite3.Row
    conn.execute(f"PRAGMA busy_timeout={int(SQLITE_BUSY_MS)}")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


@contextmanager
def transaction(db_path: str):
    conn = connect(db_path)
    try:
        conn.execute("BEGIN IMMEDIATE")
    except sqlite3.OperationalError as exc:
        conn.close()
        raise LockBusy(str(exc)) from exc
    try:
        yield conn
        conn.execute("COMMIT")
    except Exception:
        try:
            conn.execute("ROLLBACK")
        except sqlite3.Error:
            pass
        raise
    finally:
        conn.close()


def startup_invalidate(conn: sqlite3.Connection) -> None:
    """Process start drops live leases and undelivered commands. Grants stay."""
    conn.execute("DELETE FROM leases")
    conn.execute(
        "UPDATE commands SET state='killed' WHERE state IN ('pending', 'dispatched')"
    )
    conn.execute(
        "UPDATE pending_intents SET consumed_at=created_at WHERE consumed_at IS NULL"
    )
