"""Credential classes, hashes, and constant-time compares. No token logging."""

from __future__ import annotations

import hashlib
import hmac
import re
import secrets
from base64 import urlsafe_b64decode

from config import (
    ACCESS_PREFIX,
    ALPHABET32,
    AUTH_CODE_PREFIX,
    DISPLAY_CODE_LEN,
    REFRESH_PREFIX,
    TOKEN_MAX_CHARS,
)

UUID_RE = re.compile(
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)
CREDENTIAL_RE = re.compile(r"^[A-Za-z0-9_-]{43}$")


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def const_eq(left: str, right: str) -> bool:
    """Compare digests so unequal lengths do not short-circuit the secret itself."""
    return hmac.compare_digest(
        hashlib.sha256(left.encode("utf-8")).digest(),
        hashlib.sha256(right.encode("utf-8")).digest(),
    )


def const_eq_hash(presented_hash: str, stored_hash: str) -> bool:
    if len(presented_hash) != len(stored_hash):
        return False
    return hmac.compare_digest(presented_hash, stored_hash)


def is_uuid(value: object) -> bool:
    return isinstance(value, str) and bool(UUID_RE.fullmatch(value))


def canon_uuid(value: str) -> str:
    return value.lower()


def valid_installation_credential(value: object) -> bool:
    if not isinstance(value, str) or not CREDENTIAL_RE.fullmatch(value):
        return False
    if "=" in value:
        return False
    try:
        raw = urlsafe_b64decode(value + "=" * ((4 - len(value) % 4) % 4))
    except Exception:
        return False
    return len(raw) == 32


def new_installation_credential() -> str:
    return _b64url_32()


def _b64url_32() -> str:
    from base64 import urlsafe_b64encode

    return urlsafe_b64encode(secrets.token_bytes(32)).decode("ascii").rstrip("=")


def new_uuid() -> str:
    import uuid

    return str(uuid.uuid4())


def new_display_code() -> str:
    return "".join(secrets.choice(ALPHABET32) for _ in range(DISPLAY_CODE_LEN))


def is_display_code(value: str) -> bool:
    if len(value) != DISPLAY_CODE_LEN:
        return False
    upper = value.upper()
    return all(ch in ALPHABET32 for ch in upper)


def new_token(prefix: str, nbytes: int = 32) -> str:
    token = prefix + secrets.token_urlsafe(nbytes)
    if len(token) >= TOKEN_MAX_CHARS:
        raise RuntimeError("token exceeds protocol limit")
    return token


def new_access_token() -> str:
    return new_token(ACCESS_PREFIX)


def new_refresh_token() -> str:
    return new_token(REFRESH_PREFIX)


def new_auth_code() -> str:
    return new_token(AUTH_CODE_PREFIX, 24)


def new_browser_token() -> str:
    return secrets.token_urlsafe(32)


def new_csrf() -> str:
    return secrets.token_urlsafe(32)


def parse_authorization(value: str | None) -> tuple[str, str] | None:
    """Return (scheme, secret) without logging. scheme is installation|bearer|raw."""
    if value is None:
        return None
    if value.startswith("Installation "):
        return ("installation", value[len("Installation ") :])
    if value.startswith("Bearer "):
        return ("bearer", value[len("Bearer ") :])
    if value == "":
        return None
    return ("raw", value)


def static_matches(header: str | None, secret: str) -> bool:
    """Distinct legacy verifier. Empty secret matches nothing."""
    if not secret or header is None:
        return False
    parsed = parse_authorization(header)
    if parsed is None:
        return False
    scheme, got = parsed
    if scheme == "installation":
        return False
    if scheme == "bearer":
        return const_eq(got, secret)
    if scheme == "raw":
        return const_eq(got, secret)
    return False
