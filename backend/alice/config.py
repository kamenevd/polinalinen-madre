"""Bounded voice-bridge settings. Defaults must not claim a renamed skill card."""

from __future__ import annotations

import os
from dataclasses import dataclass

# Independent A9 readback has not verified a catalog rename. Ignore env overrides.
SKILL_NAME = "Голос закваски"
RENAME_VERIFIED = False

EXACT_REDIRECT = "https://social.yandex.net/broker/redirect"
EXACT_SCOPE = "bake:read&bake:control"
EXACT_RESPONSE_TYPE = "code"

# RFC 4648 base32. Display pairing codes only; matches [A-Z2-7]{10}. Not OAuth auth codes.
ALPHABET32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
DISPLAY_CODE_LEN = 10

ACCESS_PREFIX = "mat_"
REFRESH_PREFIX = "mrt_"
AUTH_CODE_PREFIX = "mac_"

WEBHOOK_PATH = "/alice/madre"
HEALTH_PATHS = ("/health", "/alice/madre/health")
VOICE_PREFIX = "/alice/madre/voice"

MAX_BODY = 64 * 1024
TOKEN_MAX_CHARS = 2048
TOKEN_RESPONSE_MAX_CHARS = 5000
DIALOG_TEXT_MAX = 1024

POLL_WAIT_S = 1.0
ACK_WAIT_S = 2.0
REQUEST_BUDGET_S = 4.5
# Absolute socket lifetime enforced here. Not a reverse-proxy read timeout.
# Margin above the dialog budget so a legal ack/poll can still write its answer.
REQUEST_DEADLINE_S = REQUEST_BUDGET_S + 1.5
COMMAND_TTL_MS = 4000
INTENT_TTL_MS = 30_000
CHALLENGE_TTL_MS = 5 * 60 * 1000
AUTH_CODE_TTL_MS = 60_000
ACCESS_TTL_S = 900
GRANT_TTL_MS = 30 * 24 * 3600 * 1000
RECEIPT_TTL_MS = 24 * 3600 * 1000
SNAPSHOT_TTL_MS = 3600 * 1000
FRESHNESS_MS = 6_000
# PROTOCOL says insane clock skew fails closed but does not give a millisecond bound.
SKEW_MS = 30_000
REMAINING_MAX_S = 7 * 24 * 3600

SQLITE_TIMEOUT_S = 0.4
SQLITE_BUSY_MS = 400

PHONE_STATUSES = ("APPLIED", "STALE", "EXPIRED", "REJECTED", "PHONE_REQUIRED")
OPERATIONS = ("PAUSE", "RESUME", "ADVANCE", "BACK", "CANCEL")


@dataclass(frozen=True)
class Settings:
    db_path: str
    oauth_client_id: str = ""
    oauth_client_secret: str = ""
    static_auth: str = ""
    host: str = "127.0.0.1"
    port: int = 8741
    poll_wait_s: float = POLL_WAIT_S
    ack_wait_s: float = ACK_WAIT_S
    request_budget_s: float = REQUEST_BUDGET_S
    request_deadline_s: float = REQUEST_DEADLINE_S
    max_body: int = MAX_BODY
    skew_ms: int = SKEW_MS
    freshness_ms: int = FRESHNESS_MS
    command_ttl_ms: int = COMMAND_TTL_MS
    intent_ttl_ms: int = INTENT_TTL_MS
    challenge_ttl_ms: int = CHALLENGE_TTL_MS
    auth_code_ttl_ms: int = AUTH_CODE_TTL_MS
    access_ttl_s: int = ACCESS_TTL_S
    grant_ttl_ms: int = GRANT_TTL_MS
    receipt_ttl_ms: int = RECEIPT_TTL_MS
    snapshot_ttl_ms: int = SNAPSHOT_TTL_MS
    enroll_limit: int = 20
    enroll_window_ms: int = 600_000
    install_cap: int = 5000
    oauth_limit: int = 30
    oauth_window_ms: int = 600_000
    pair_fail_limit: int = 8
    pair_window_ms: int = 600_000
    max_inflight: int = 32

    def oauth_configured(self) -> bool:
        return bool(self.oauth_client_id) and bool(self.oauth_client_secret)


def settings_from_env() -> Settings:
    db_path = os.environ.get("MADRE_ALICE_DB", "").strip()
    if not db_path:
        raise SystemExit("MADRE_ALICE_DB is required; refusing an implicit database path")
    host = os.environ.get("MADRE_ALICE_HOST", "127.0.0.1").strip() or "127.0.0.1"
    port_raw = os.environ.get("MADRE_ALICE_PORT", "8741").strip() or "8741"
    try:
        port = int(port_raw)
    except ValueError as exc:
        raise SystemExit("MADRE_ALICE_PORT must be an integer") from exc
    if port < 1 or port > 65535:
        raise SystemExit("MADRE_ALICE_PORT out of range")
    return Settings(
        db_path=db_path,
        oauth_client_id=os.environ.get("MADRE_ALICE_OAUTH_CLIENT_ID", ""),
        oauth_client_secret=os.environ.get("MADRE_ALICE_OAUTH_CLIENT_SECRET", ""),
        static_auth=os.environ.get("MADRE_ALICE_AUTH", ""),
        host=host,
        port=port,
    )
