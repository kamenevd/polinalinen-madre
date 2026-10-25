"""Yandex trust boundary. Credentials/profile exist only in request-local RAM.

Only the fixed HTTPS endpoints below can be reached. No redirects, proxy env,
HTTP debug logging, retries, or exception details escape this module.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import http.client
import json
import re
import queue
import secrets
import socket
import ssl
import threading
import time
from urllib.parse import urlencode

CALLBACK = "https://kdnfx.space/alice/madre/voice/oauth/yandex/callback"
MAX_RESPONSE = 65536
TOKEN_RE = re.compile(r"[A-Za-z0-9_.~-]{1,2048}\Z", re.ASCII)


class YandexUnavailable(Exception):
    pass


class ForeignClient(Exception):
    pass


def valid_token(value):
    return isinstance(value, str) and TOKEN_RE.fullmatch(value) is not None


_OUTBOUND_SLOTS = threading.BoundedSemaphore(4)


def _json_request(host, path, *, headers, body=None, timeout=2.0):
    # DNS itself is not interruptible by socket timeout on all Python platforms.
    # Bound caller latency and outstanding external work, including hung DNS.
    if not _OUTBOUND_SLOTS.acquire(blocking=False):
        raise YandexUnavailable()
    result = queue.Queue(maxsize=1)
    def worker():
        try:
            result.put((True, _https_json(host, path, headers=headers, body=body, timeout=timeout)))
        except Exception:
            result.put((False, None))
        finally:
            _OUTBOUND_SLOTS.release()
    thread = threading.Thread(target=worker, daemon=True)
    try:
        thread.start()
    except Exception:
        _OUTBOUND_SLOTS.release()
        raise YandexUnavailable() from None
    try:
        ok, value = result.get(timeout=timeout)
    except queue.Empty:
        raise YandexUnavailable() from None
    if not ok:
        raise YandexUnavailable()
    return value


def _https_json(host, path, *, headers, body=None, timeout=2.0):
    # An absolute deadline covers DNS, TLS, headers and a drip-fed body. Socket
    # timeouts alone are inactivity limits, not total request bounds.
    conn = http.client.HTTPSConnection(host, timeout=timeout, context=ssl.create_default_context())
    done = threading.Event()
    def expire():
        if not done.is_set() and conn.sock is not None:
            try:
                conn.sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
    timer = threading.Timer(timeout, expire)
    timer.daemon = True
    started = time.monotonic()
    timer.start()
    try:
        conn.request("POST" if body is not None else "GET", path, body=body, headers=headers)
        response = conn.getresponse()
        if response.status != 200:
            raise YandexUnavailable()
        if response.getheader("Content-Encoding", "identity") != "identity":
            raise YandexUnavailable()
        length = response.getheader("Content-Length")
        if length is not None and (not length.isdigit() or int(length) > MAX_RESPONSE):
            raise YandexUnavailable()
        raw = response.read(MAX_RESPONSE + 1)
        if len(raw) > MAX_RESPONSE or time.monotonic() - started >= timeout:
            raise YandexUnavailable()
        value = json.loads(raw)
        if not isinstance(value, dict):
            raise YandexUnavailable()
        return value
    except Exception:
        raise YandexUnavailable() from None
    finally:
        done.set()
        timer.cancel()
        conn.close()


class YandexClient:
    def __init__(self, settings):
        self.settings = settings

    def info(self, token):
        if not valid_token(token):
            raise YandexUnavailable()
        return _json_request("login.yandex.ru", "/info", headers={
            "Authorization": "OAuth " + token, "Accept": "application/json"})

    def exchange(self, code, verifier):
        value = _json_request("oauth.yandex.ru", "/token", headers={
            "Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json"},
            body=urlencode({"grant_type": "authorization_code", "code": code,
                            "client_id": self.settings.yandex_client_id,
                            "client_secret": self.settings.yandex_client_secret,
                            "code_verifier": verifier}).encode("ascii"))
        token = value.get("access_token")
        if not valid_token(token) or value.get("token_type", "bearer").lower() != "bearer":
            raise YandexUnavailable()
        return token


def verified_identity(info, own_client):
    if not isinstance(info, dict):
        raise YandexUnavailable()
    client = info.get("client_id")
    if not isinstance(client, str) or not hmac.compare_digest(client.encode(), own_client.encode()):
        raise ForeignClient()
    uid = info.get("id")
    if not isinstance(uid, str) or re.fullmatch(r"[0-9]{1,32}", uid, re.ASCII) is None:
        raise YandexUnavailable()
    return uid


def uid_hash(uid, salt):
    return hmac.new(salt.encode(), ("madre-yandex-uid-v1:" + uid).encode(), hashlib.sha256).hexdigest()


def profile_response(info, digest):
    def text(key, default=""):
        value = info.get(key, default)
        if not isinstance(value, str) or len(value) > 512 or any(ord(c) < 32 for c in value):
            raise YandexUnavailable()
        return value
    avatar = info.get("default_avatar_id")
    if avatar is not None and (not isinstance(avatar, str) or re.fullmatch(r"[A-Za-z0-9_/-]{1,256}", avatar) is None):
        raise YandexUnavailable()
    empty = info.get("is_avatar_empty", not avatar)
    if not isinstance(empty, bool):
        raise YandexUnavailable()
    return {"uid_hash": digest, "login": text("login"), "display_name": text("display_name"),
            "avatar_id": avatar, "avatar_empty": empty}


class PendingStates:
    """Bounded RAM-only, browser-bound, expiring, atomic single-use state/PKCE.

    Restart deliberately denies unfinished flows. Neither outer state nor PKCE
    verifier nor authorization code is written to a Yandex-state SQLite table.
    """
    def __init__(self):
        self.lock = threading.Lock()
        self.entries = {}
        self.used_codes = {}

    def create(self, checked, now, cap=256):
        with self.lock:
            self.entries = {k: v for k, v in self.entries.items() if v[0] > now}
            if len(self.entries) >= cap:
                return None
            state, browser, verifier = (secrets.token_urlsafe(32) for _ in range(3))
            self.entries[state] = (now + 300000, browser, verifier, dict(checked))
        challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
        return state, browser, challenge

    def claim_code(self, code, now):
        digest = hashlib.sha256(code.encode()).hexdigest()
        with self.lock:
            self.used_codes = {k: expiry for k, expiry in self.used_codes.items() if expiry > now}
            if digest in self.used_codes or len(self.used_codes) >= 512:
                return False
            self.used_codes[digest] = now + 600000
            return True

    def consume(self, state, browser, now):
        with self.lock:
            entry = self.entries.get(state)
            if entry is None or not browser or not hmac.compare_digest(entry[1].encode(), browser.encode()):
                return None
            self.entries.pop(state)
            return entry if entry[0] > now else None
