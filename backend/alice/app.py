"""Bounded HTTP API. No network wait while a SQLite transaction is open."""

from __future__ import annotations

import json
import logging
import socket
import threading
import time
import weakref
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import parse_qs, quote, urlsplit, urlencode
from yandex_identity import (YandexClient, PendingStates, ForeignClient, YandexUnavailable,
                             CALLBACK, valid_token, verified_identity, uid_hash, profile_response)

from config import (
    EXACT_REDIRECT,
    EXACT_RESPONSE_TYPE,
    EXACT_SCOPE,
    HEALTH_PATHS,
    RENAME_VERIFIED,
    SKILL_NAME,
    TOKEN_MAX_CHARS,
    TOKEN_RESPONSE_MAX_CHARS,
    VOICE_PREFIX,
    WEBHOOK_PATH,
    Settings,
)
from db import LockBusy, init_db, startup_invalidate, transaction
from madre_alice import RECIPES_SHA256, SKILL_NAME as READER_SKILL
from madre_alice.dialog import handle as public_handle
from madre_alice.recipes import load_recipes
from security import (
    const_eq,
    is_display_code,
    is_uuid,
    new_access_token,
    new_auth_code,
    new_browser_token,
    new_csrf,
    new_display_code,
    new_refresh_token,
    parse_authorization,
    sha256_text,
    static_matches,
    valid_installation_credential,
    canon_uuid,
)
from snapshot import SnapshotError, clock_sane, project_remaining, sanitize_snapshot
from store import (
    ack_command,
    approve_challenge,
    challenge_by_browser,
    challenge_by_code_hash,
    challenge_by_id,
    clear_intents,
    consume_intent,
    count_challenges,
    count_installations,
    create_challenge,
    disconnect,
    enroll,
    grant_by_access,
    inflight_exists,
    installation_by_hash,
    installation_by_id,
    issue_auth_code,
    latest_receipt,
    lease_row,
    linked_grant_exists,
    mark_unknown,
    mint_command,
    open_intent,
    purge,
    put_intent,
    rate_add_failure,
    rate_allow,
    rate_failures,
    read_receipt,
    redeem_auth_code,
    rotate_refresh,
    save_snapshot,
    select_bake,
    snapshot_row,
    take_command,
    touch_lease,
    grant_live,
)
from voice import (
    Utterance,
    alice_reply,
    asserts_renamed_skill,
    help_text,
    linking_reply,
    not_sent,
    resolve_pair,
    speak_duration,
    unknown_outcome,
)

LOG = logging.getLogger("madre-alice")

PHONE_PREFIX = VOICE_PREFIX + "/phone"
OAUTH_PREFIX = VOICE_PREFIX + "/oauth"
CSRF_COOKIE = "madre_csrf"
BROWSER_COOKIE = "madre_browser"


class Waiters:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._poll: dict[str, tuple[threading.Event, int]] = {}
        self._ack: dict[str, tuple[threading.Event, int]] = {}

    def poll_event(self, connection_id: str) -> threading.Event:
        return self._acquire(self._poll, connection_id)

    def release_poll(self, connection_id: str) -> None:
        self._release(self._poll, connection_id)

    def wake_poll(self, connection_id: str) -> None:
        self._wake(self._poll, connection_id)

    def ack_event(self, command_id: str) -> threading.Event:
        return self._acquire(self._ack, command_id)

    def release_ack(self, command_id: str) -> None:
        self._release(self._ack, command_id)

    def wake_ack(self, command_id: str) -> None:
        self._wake(self._ack, command_id)

    def sizes(self) -> dict[str, int]:
        with self._lock:
            return {"poll": len(self._poll), "ack": len(self._ack)}

    def _acquire(self, table: dict[str, tuple[threading.Event, int]], key: str) -> threading.Event:
        with self._lock:
            slot = table.get(key)
            if slot is None:
                ev = threading.Event()
                table[key] = (ev, 1)
                return ev
            ev, refs = slot
            table[key] = (ev, refs + 1)
            return ev

    def _release(self, table: dict[str, tuple[threading.Event, int]], key: str) -> None:
        with self._lock:
            slot = table.get(key)
            if slot is None:
                return
            ev, refs = slot
            if refs <= 1:
                current = table.get(key)
                if current is not None and current[0] is ev:
                    table.pop(key, None)
            else:
                table[key] = (ev, refs - 1)

    def _wake(self, table: dict[str, tuple[threading.Event, int]], key: str) -> None:
        with self._lock:
            slot = table.get(key)
            if slot is not None:
                slot[0].set()


class Clock:
    def __init__(self) -> None:
        self.offset_ms = 0

    def now_ms(self) -> int:
        return int(time.time() * 1000) + self.offset_ms

    def advance_ms(self, ms: int) -> None:
        self.offset_ms += int(ms)


class App:
    def __init__(self, settings: Settings, clock: Clock | None = None) -> None:
        if SKILL_NAME != "Голос закваски" or READER_SKILL != "Голос закваски" or RENAME_VERIFIED:
            raise RuntimeError("refusing a config that claims an unverified skill rename")
        book = load_recipes()
        if len(book) != 11:
            raise RuntimeError("recipe catalog is not the standalone 11-recipe reader")
        _ = RECIPES_SHA256
        init_db(settings.db_path)
        self.settings = settings
        self.clock = clock or Clock()
        self.boot_id = new_csrf()
        self.waiters = Waiters()
        self.yandex = YandexClient(settings)
        self.yandex_states = PendingStates()
        self.slots = threading.BoundedSemaphore(settings.max_inflight)
        with transaction(settings.db_path) as conn:
            startup_invalidate(conn)

    def now(self) -> int:
        return self.clock.now_ms()


def contains_spoken_code(command: str, original: str) -> bool:
    import re

    blob = f"{command or ''} {original or ''}"
    for token in re.split(r"[^A-Za-z0-9]+", blob):
        if is_display_code(token):
            return True
    return False


def build_redirect(redirect_uri: str, code: str, state: str, client_id: str, scope: str) -> str:
    query = "&".join(
        [
            "code=" + quote(code, safe=""),
            "state=" + quote(state, safe=""),
            "client_id=" + quote(client_id, safe=""),
            "scope=" + quote(scope, safe=""),
        ]
    )
    return redirect_uri + "?" + query


def html_escape(value: str) -> str:
    return (
        value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def parse_cookies(header: str | None) -> dict[str, str]:
    out: dict[str, str] = {}
    if not header:
        return out
    for part in header.split(";"):
        if "=" not in part:
            continue
        key, val = part.split("=", 1)
        out[key.strip()] = val.strip()
    return out


def one_param(params: dict[str, list[str]], key: str) -> str | None:
    values = params.get(key)
    if not values or len(values) != 1:
        return None
    return values[0]


def validate_oauth_params(params: dict[str, list[str]], settings: Settings) -> dict[str, str] | None:
    if not settings.oauth_configured():
        return None
    client_id = one_param(params, "client_id")
    redirect_uri = one_param(params, "redirect_uri")
    response_type = one_param(params, "response_type")
    scope = one_param(params, "scope")
    if "state" not in params or len(params["state"]) != 1:
        return None
    state = params["state"][0]
    if client_id is None or redirect_uri is None or response_type is None or scope is None:
        return None
    if not const_eq(client_id, settings.oauth_client_id):
        return None
    if redirect_uri != EXACT_REDIRECT:
        return None
    if response_type != EXACT_RESPONSE_TYPE:
        return None
    if scope != EXACT_SCOPE:
        return None
    if len(state) > 512:
        return None
    return {
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "response_type": response_type,
        "scope": scope,
        "state": state,
    }


def form_params(raw: bytes) -> dict[str, list[str]] | None:
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        return None
    parsed = parse_qs(text, keep_blank_values=True, strict_parsing=False)
    return parsed


def media_type(value: str | None) -> str:
    if not value:
        return ""
    return value.split(";", 1)[0].strip().lower()


def origin_ok(origin: str | None, host: str | None) -> bool:
    if not origin:
        return True
    if not host:
        return False
    parts = urlsplit(origin)
    if parts.scheme not in ("http", "https") or not parts.netloc:
        return False
    return parts.netloc == host


def account_linking_supported(body: dict) -> bool:
    meta = body.get("meta")
    if not isinstance(meta, dict):
        return False
    interfaces = meta.get("interfaces")
    if not isinstance(interfaces, dict):
        return False
    return "account_linking" in interfaces


def session_id_of(body: dict) -> str:
    session = body.get("session")
    if not isinstance(session, dict):
        return ""
    value = session.get("session_id")
    if not isinstance(value, str) or not value or len(value) > 128:
        return ""
    return value


def utterance_of(body: dict) -> Utterance:
    request = body.get("request") if isinstance(body.get("request"), dict) else {}
    command = request.get("command") if isinstance(request.get("command"), str) else ""
    original = request.get("original_utterance") if isinstance(request.get("original_utterance"), str) else ""
    if contains_spoken_code(command, original):
        return Utterance("spoken_code")
    return resolve_pair(command, original)


def step_phrase(snap: dict[str, Any]) -> str:
    return (
        f"{snap['recipe_name']}, шаг {int(snap['step_index']) + 1} из {int(snap['step_count'])}: "
        f"{snap['step_title']}"
    )


def speech_for_receipt(status: str) -> str:
    if status == "APPLIED":
        return "Телефон применил команду."
    if status == "STALE":
        return "Телефон не применил команду: состояние уже другое."
    if status == "EXPIRED":
        return "Телефон не применил команду: время доставки вышло."
    if status == "REJECTED":
        return "Телефон отклонил команду."
    if status == "PHONE_REQUIRED":
        return "Телефон просит закончить это на экране. Выпечку голосом не завершала."
    return unknown_outcome()


def guard_copy(payload: dict) -> dict:
    text = ""
    response = payload.get("response")
    if isinstance(response, dict):
        text = str(response.get("text") or "")
    if asserts_renamed_skill(text) or asserts_renamed_skill(json.dumps(payload, ensure_ascii=False)):
        return alice_reply(f"{SKILL_NAME} не подтверждает переименование. Повтори запрос.")
    return payload


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server: "BoundedServer"

    def version_string(self) -> str:
        return "madre-alice"

    def log_message(self, fmt: str, *args) -> None:
        LOG.info("http %s", self.command)

    def log_request(self, code: str | int = "-", size: str | int = "-") -> None:
        LOG.info("http %s %s", self.command, code)

    def log_error(self, fmt: str, *args) -> None:
        LOG.warning("http error")

    def do_GET(self) -> None:  # noqa: N802
        self._dispatch("GET")

    def do_POST(self) -> None:  # noqa: N802
        self._dispatch("POST")

    def do_HEAD(self) -> None:  # noqa: N802
        self._dispatch("HEAD")

    def do_OPTIONS(self) -> None:  # noqa: N802
        self._send(405, b'{"ok":false,"error":"denied"}')

    def _dispatch(self, method: str) -> None:
        try:
            self._route(method)
        except LockBusy:
            self._send(503, b'{"ok":false,"error":"denied"}')
        except Exception:
            LOG.warning("handler failed: %s", "error")
            path = urlsplit(self.path).path
            if path == WEBHOOK_PATH:
                self._send_json(200, alice_reply("Книга на секунду запнулась. Повтори, пожалуйста."))
            else:
                self._send(500, b'{"ok":false,"error":"denied"}')

    def _route(self, method: str) -> None:
        split = urlsplit(self.path)
        path = split.path
        if ";" in path or "\\" in path or ".." in path:
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        app = self.server.app
        if method in ("GET", "HEAD") and path in HEALTH_PATHS:
            body = b"" if method == "HEAD" else b'{"ok":true}'
            self._send(200, body)
            return
        if method != "POST" and not (method == "GET" and path in (OAUTH_PREFIX + "/authorize", OAUTH_PREFIX + "/challenge", OAUTH_PREFIX + "/yandex/callback")):
            self._send(405, b'{"ok":false,"error":"denied"}')
            return
        if path.startswith(PHONE_PREFIX) and split.query:
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        if path in (OAUTH_PREFIX + "/token", OAUTH_PREFIX + "/refresh") and split.query:
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        length = self.headers.get("Content-Length")
        if self.headers.get("Transfer-Encoding"):
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        if method == "POST":
            if length is None or not length.isdigit():
                self._send(400, b'{"ok":false,"error":"denied"}')
                return
            size = int(length)
            if size > app.settings.max_body:
                self._send(413, b'{"ok":false,"error":"denied"}')
                return
            self._arm_io_timeout()
            raw = self.rfile.read(size) if size else b""
        else:
            raw = b""
        if path == WEBHOOK_PATH and method == "POST":
            self._webhook(app, raw)
            return
        if path == PHONE_PREFIX + "/enroll":
            self._phone_enroll(app, raw)
            return
        if path in (PHONE_PREFIX + "/yandex-bind", PHONE_PREFIX + "/yandex-unbind"):
            self._phone_yandex(app, raw, unbind=path.endswith("yandex-unbind"))
            return
        if path == PHONE_PREFIX + "/yandex-voice-consent":
            self._phone_yandex_consent(app, raw)
            return
        if path == OAUTH_PREFIX + "/yandex/callback" and method == "GET":
            self._yandex_callback(app, split.query)
            return
        if path == PHONE_PREFIX + "/select":
            self._phone_select(app, raw)
            return
        if path == PHONE_PREFIX + "/disconnect":
            self._phone_disconnect(app, raw)
            return
        if path == PHONE_PREFIX + "/pair-preview":
            self._phone_preview(app, raw)
            return
        if path == PHONE_PREFIX + "/pair-approve":
            self._phone_approve(app, raw)
            return
        if path == PHONE_PREFIX + "/poll":
            self._phone_poll(app, raw)
            return
        if path == PHONE_PREFIX + "/ack":
            self._phone_ack(app, raw)
            return
        if path == OAUTH_PREFIX + "/authorize" and method == "GET":
            self._oauth_get(app, split.query)
            return
        if path == OAUTH_PREFIX + "/authorize" and method == "POST":
            self._oauth_post(app, raw)
            return
        if path == OAUTH_PREFIX + "/challenge" and method == "GET":
            self._oauth_challenge(app)
            return
        if path == OAUTH_PREFIX + "/confirm" and method == "POST":
            self._oauth_confirm(app, raw)
            return
        if path == OAUTH_PREFIX + "/token" and method == "POST":
            self._oauth_token(app, raw, refresh_only=False)
            return
        if path == OAUTH_PREFIX + "/refresh" and method == "POST":
            self._oauth_token(app, raw, refresh_only=True)
            return
        self._send(404, b'{"ok":false,"error":"denied"}')

    def _peer(self) -> str:
        return self.client_address[0]

    def _arm_io_timeout(self) -> None:
        """Cap the next socket read/write by the remaining absolute deadline."""
        deadline = _DEADLINES.get(self.connection)
        if deadline is None:
            return
        remain = deadline - time.monotonic()
        if remain <= 0:
            raise TimeoutError("request deadline")
        self.connection.settimeout(remain)

    def _send(self, code: int, body: bytes, content_type: str = "application/json; charset=utf-8", extra: list[tuple[str, str]] | None = None) -> None:
        self._arm_io_timeout()
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Pragma", "no-cache")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header(
            "Content-Security-Policy",
            "default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src 'none'; "
            "connect-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
        )
        self.send_header("Connection", "close")
        for key, value in extra or []:
            self.send_header(key, value)
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _send_json(self, code: int, payload: object, extra: list[tuple[str, str]] | None = None) -> None:
        raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self._send(code, raw, extra=extra)

    def _cookie(self, name: str, value: str) -> str:
        return (
            f"{name}={value}; HttpOnly; Secure; SameSite=Lax; Path={OAUTH_PREFIX}; Max-Age=300"
        )

    def _json_object(self, raw: bytes) -> dict | None:
        if media_type(self.headers.get("Content-Type")) != "application/json":
            return None
        try:
            body = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return None
        if not isinstance(body, dict):
            return None
        return body

    def _installation(self, app: App):
        parsed = parse_authorization(self.headers.get("Authorization"))
        if parsed is None or parsed[0] != "installation":
            return None
        credential = parsed[1]
        if not valid_installation_credential(credential):
            return None
        with transaction(app.settings.db_path) as conn:
            return installation_by_hash(conn, sha256_text(credential))

    def _deny_phone_if_not_installation(self) -> bool:
        parsed = parse_authorization(self.headers.get("Authorization"))
        if parsed is None or parsed[0] != "installation":
            self._send(401, b'{"ok":false,"error":"denied"}')
            return True
        return False

    def _phone_enroll(self, app: App, raw: bytes) -> None:
        if self._deny_phone_if_not_installation():
            return
        body = self._json_object(raw)
        if body is None or set(body) != {"credential"}:
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        credential = body.get("credential")
        header = parse_authorization(self.headers.get("Authorization"))
        if not isinstance(credential, str) or header is None or not const_eq(credential, header[1]):
            self._send(401, b'{"ok":false,"error":"denied"}')
            return
        if not valid_installation_credential(credential):
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        digest = sha256_text(credential)
        with transaction(app.settings.db_path) as conn:
            purge(conn, app.now(), app.settings.receipt_ttl_ms, app.settings.snapshot_ttl_ms)
            if not rate_allow(conn, "enroll:" + self._peer(), app.now(), app.settings.enroll_window_ms, app.settings.enroll_limit):
                self._send(429, b'{"ok":false,"error":"denied"}')
                return
            existing = installation_by_hash(conn, digest)
            if existing is None and count_installations(conn) >= app.settings.install_cap:
                self._send(429, b'{"ok":false,"error":"denied"}')
                return
            installation_id = enroll(conn, digest, app.now())
        self._send_json(200, {"installation_id": installation_id})

    def _phone_yandex(self, app: App, raw: bytes, unbind: bool) -> None:
        import yandex_store
        inst = self._installation(app)
        if inst is None:
            self._send_json(401, {"error": "denied"})
            return
        body = self._json_object(raw)
        if body is None or set(body) != (set() if unbind else {"yandex_token"}):
            self._send_json(400, {"error": "denied"})
            return
        if unbind:
            with transaction(app.settings.db_path) as conn:
                yandex_store.unbind(conn, inst["id"], app.now())
            self._send(204, b"")
            return
        token = body.get("yandex_token")
        if not valid_token(token):
            self._send_json(400, {"error": "denied"})
            return
        if not app.settings.yandex_configured():
            self._send_json(503, {"error": "yandex_unavailable"})
            return
        with transaction(app.settings.db_path) as conn:
            if not rate_allow(conn, "yandex-bind:" + inst["id"], app.now(), 600000, 20):
                self._send_json(429, {"error": "denied"})
                return
        expected = inst["authority_epoch"]
        try:
            info = app.yandex.info(token)
            uid = verified_identity(info, app.settings.yandex_client_id)
            payload = profile_response(info, uid_hash(uid, app.settings.yandex_uid_salt))
        except ForeignClient:
            self._send_json(403, {"error": "foreign_client"})
            return
        except YandexUnavailable:
            self._send_json(502, {"error": "yandex_unavailable"})
            return
        with transaction(app.settings.db_path) as conn:
            ok = yandex_store.bind(conn, inst["id"], uid, expected, app.now())
        self._send_json(200 if ok else 409, payload if ok else {"error": "binding_changed"})

    def _phone_yandex_consent(self, app: App, raw: bytes) -> None:
        import yandex_store
        inst = self._installation(app)
        if inst is None:
            self._send_json(401, {"error": "denied"})
            return
        body = self._json_object(raw)
        if (body is None or set(body) != {"bake_id", "generation"}
                or not is_uuid(body.get("bake_id")) or not is_uuid(body.get("generation"))):
            self._send_json(400, {"error": "denied"})
            return
        with transaction(app.settings.db_path) as conn:
            expires = yandex_store.record_consent(conn, inst, canon_uuid(body["bake_id"]),
                                                  canon_uuid(body["generation"]), app.now())
        self._send_json(200 if expires is not None else 409,
                        {"expires_at": expires} if expires is not None else {"error": "binding_changed"})

    def _phone_select(self, app: App, raw: bytes) -> None:
        inst = self._installation(app)
        if inst is None:
            self._send(401, b'{"ok":false,"error":"denied"}')
            return
        body = self._json_object(raw)
        if body is None or set(body) != {"bake_key", "selection_generation"}:
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        bake = body.get("bake_key")
        generation = body.get("selection_generation")
        if not is_uuid(bake) or not is_uuid(generation):
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        with transaction(app.settings.db_path) as conn:
            select_bake(conn, inst["id"], bake, generation, app.now())
        self._send_json(200, {"selected": True})

    def _phone_disconnect(self, app: App, raw: bytes) -> None:
        inst = self._installation(app)
        if inst is None:
            self._send(401, b'{"ok":false,"error":"denied"}')
            return
        body = self._json_object(raw)
        if body is None or set(body) != set():
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        with transaction(app.settings.db_path) as conn:
            disconnect(conn, inst["id"], app.now())
        self._send_json(200, {"revoked": True})

    def _phone_preview(self, app: App, raw: bytes) -> None:
        inst = self._installation(app)
        if inst is None:
            self._send(401, b'{"ok":false,"error":"denied"}')
            return
        body = self._json_object(raw)
        if body is None or set(body) != {"code"} or not isinstance(body.get("code"), str):
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        if not inst["selected_bake_key"] or not inst["selected_generation"]:
            self._send(403, b'{"ok":false,"error":"denied"}')
            return
        code = body["code"]
        blocked = False
        failed = False
        payload = None
        with transaction(app.settings.db_path) as conn:
            bucket = "pair:" + inst["id"]
            if rate_failures(conn, bucket, app.now(), app.settings.pair_window_ms) >= app.settings.pair_fail_limit:
                blocked = True
            else:
                row = challenge_by_code_hash(conn, sha256_text(code)) if is_display_code(code) else None
                if row is None or row["consumed_at"] is not None or int(row["expires_at"]) <= app.now():
                    rate_add_failure(conn, bucket, app.now(), app.settings.pair_window_ms)
                    failed = True
                else:
                    payload = {
                        "scope": row["scope"],
                        "expires_at": int(row["expires_at"]),
                        "challenge_id": row["id"],
                    }
        if blocked:
            self._send(429, b'{"ok":false,"error":"denied"}')
            return
        if failed or payload is None:
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        self._send_json(200, payload)

    def _phone_approve(self, app: App, raw: bytes) -> None:
        inst = self._installation(app)
        if inst is None:
            self._send(401, b'{"ok":false,"error":"denied"}')
            return
        body = self._json_object(raw)
        needed = {"challenge_id", "code", "bake_key", "selection_generation"}
        if body is None or set(body) != needed:
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        if not inst["selected_bake_key"] or not inst["selected_generation"]:
            self._send(403, b'{"ok":false,"error":"denied"}')
            return
        if not is_uuid(body.get("challenge_id")) or not is_uuid(body.get("bake_key")) or not is_uuid(body.get("selection_generation")):
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        if not isinstance(body.get("code"), str) or not is_display_code(body["code"]):
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        if canon_uuid(body["bake_key"]) != inst["selected_bake_key"] or canon_uuid(body["selection_generation"]) != inst["selected_generation"]:
            self._send(403, b'{"ok":false,"error":"denied"}')
            return
        blocked = False
        ok = False
        with transaction(app.settings.db_path) as conn:
            bucket = "pair:" + inst["id"]
            if rate_failures(conn, bucket, app.now(), app.settings.pair_window_ms) >= app.settings.pair_fail_limit:
                blocked = True
            else:
                ok = approve_challenge(
                    conn,
                    challenge_id=canon_uuid(body["challenge_id"]),
                    code_hash=sha256_text(body["code"]),
                    installation_id=inst["id"],
                    bake_key=body["bake_key"],
                    generation=body["selection_generation"],
                    expected_epoch=inst["authority_epoch"],
                    now_ms=app.now(),
                )
                if not ok:
                    rate_add_failure(conn, bucket, app.now(), app.settings.pair_window_ms)
        if blocked:
            self._send(429, b'{"ok":false,"error":"denied"}')
            return
        if not ok:
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        self._send_json(200, {"approved": True})

    def _phone_poll(self, app: App, raw: bytes) -> None:
        inst = self._installation(app)
        if inst is None:
            self._send(401, b'{"ok":false,"error":"denied"}')
            return
        body = self._json_object(raw)
        if body is None or set(body) != {"connection_id", "selection_generation", "snapshot"}:
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        if not is_uuid(body.get("connection_id")) or not is_uuid(body.get("selection_generation")):
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        connection_id = canon_uuid(body["connection_id"])
        generation = canon_uuid(body["selection_generation"])
        try:
            replaced, linked, command = self._ingest_poll(app, inst["id"], connection_id, generation, body["snapshot"])
        except SnapshotError:
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        except ValueError:
            self._send(409, b'{"ok":false,"error":"denied"}')
            return
        if replaced:
            app.waiters.wake_poll(replaced)
        if command is not None:
            self._send_json(200, {"server_time": app.now(), "linked": linked, "command": command})
            return
        event = app.waiters.poll_event(connection_id)
        try:
            event.clear()
            command, linked = self._take_only(app, inst["id"], connection_id, generation)
            if command is None:
                event.wait(app.settings.poll_wait_s)
                command, linked = self._take_only(app, inst["id"], connection_id, generation)
            self._touch(app, inst["id"], connection_id)
            self._send_json(200, {"server_time": app.now(), "linked": linked, "command": command})
        finally:
            app.waiters.release_poll(connection_id)

    def _ingest_poll(self, app: App, installation_id: str, connection_id: str, generation: str, raw_snapshot: object):
        with transaction(app.settings.db_path) as conn:
            inst = installation_by_id(conn, installation_id)
            if inst is None or not inst["selected_bake_key"] or not inst["selected_generation"]:
                raise ValueError("no_selection")
            if generation != inst["selected_generation"]:
                raise ValueError("generation_mismatch")
            snap = sanitize_snapshot(raw_snapshot, inst["selected_bake_key"])
            now = app.now()
            sane = clock_sane(int(snap["captured_at"]), now, app.settings.skew_ms)
            replaced = save_snapshot(
                conn,
                installation_id=installation_id,
                generation=generation,
                connection_id=connection_id,
                payload=snap,
                now_ms=now,
                clock_ok=sane,
                boot_id=app.boot_id,
            )
            linked = linked_grant_exists(conn, installation_id, now)
            command = take_command(
                conn,
                installation_id=installation_id,
                connection_id=connection_id,
                generation=generation,
                boot_id=app.boot_id,
                now_ms=now,
            )
            return replaced, linked, command

    def _take_only(self, app: App, installation_id: str, connection_id: str, generation: str):
        with transaction(app.settings.db_path) as conn:
            now = app.now()
            command = take_command(
                conn,
                installation_id=installation_id,
                connection_id=connection_id,
                generation=generation,
                boot_id=app.boot_id,
                now_ms=now,
            )
            linked = linked_grant_exists(conn, installation_id, now)
            return command, linked

    def _touch(self, app: App, installation_id: str, connection_id: str) -> None:
        with transaction(app.settings.db_path) as conn:
            touch_lease(conn, installation_id, connection_id, app.now(), app.boot_id)

    def _phone_ack(self, app: App, raw: bytes) -> None:
        inst = self._installation(app)
        if inst is None:
            self._send(401, b'{"ok":false,"error":"denied"}')
            return
        body = self._json_object(raw)
        needed = {
            "connection_id",
            "selection_generation",
            "command_id",
            "bake_key",
            "expected_revision",
            "status",
            "new_revision",
        }
        if body is None or set(body) != needed:
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        if not all(is_uuid(body.get(key)) for key in ("connection_id", "selection_generation", "command_id", "bake_key")):
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        status = body.get("status")
        if status not in ("APPLIED", "STALE", "EXPIRED", "REJECTED", "PHONE_REQUIRED"):
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        expected = body.get("expected_revision")
        new_revision = body.get("new_revision")
        if isinstance(expected, bool) or not isinstance(expected, int):
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        if status == "APPLIED":
            if isinstance(new_revision, bool) or not isinstance(new_revision, int) or new_revision < 0:
                self._send(400, b'{"ok":false,"error":"denied"}')
                return
        elif new_revision is not None and (isinstance(new_revision, bool) or not isinstance(new_revision, int)):
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        with transaction(app.settings.db_path) as conn:
            accepted = ack_command(
                conn,
                installation_id=inst["id"],
                command_id=canon_uuid(body["command_id"]),
                connection_id=canon_uuid(body["connection_id"]),
                generation=body["selection_generation"],
                bake_key=body["bake_key"],
                expected_revision=expected,
                status=status,
                new_revision=new_revision,
                now_ms=app.now(),
            )
        if accepted:
            app.waiters.wake_ack(canon_uuid(body["command_id"]))
        self._send_json(200, {"accepted": accepted})

    def _yandex_callback(self, app: App, query: str) -> None:
        import yandex_store
        if not app.settings.yandex_match_configured():
            self._send_json(400, {"error": "denied"})
            return
        params = parse_qs(query, keep_blank_values=True)
        state = one_param(params, "state")
        browser = parse_cookies(self.headers.get("Cookie")).get("madre_yandex", "")
        pending = app.yandex_states.consume(state, browser, app.now()) if state else None
        if pending is None:
            self._send_json(400, {"error": "denied"})
            return
        _expires, _browser, verifier, checked = pending
        checked = validate_oauth_params({k: [v] for k, v in checked.items()}, app.settings)
        if checked is None:
            self._send_json(400, {"error": "denied"})
            return
        clear_cookie = ("Set-Cookie", self._cookie("madre_yandex", "") + "; Max-Age=0")
        if set(params) == {"state", "error"} and one_param(params, "error"):
            self._yandex_fallback(checked, clear_cookie)
            return
        code = one_param(params, "code")
        if set(params) != {"state", "code"} or not valid_token(code) or not app.yandex_states.claim_code(code, app.now()):
            self._send_json(400, {"error": "denied"})
            return
        try:
            token = app.yandex.exchange(code, verifier)
            info = app.yandex.info(token)
            uid = verified_identity(info, app.settings.yandex_client_id)
        except ForeignClient:
            self._send_json(403, {"error": "foreign_client"}, [clear_cookie])
            return
        except YandexUnavailable:
            self._send_json(502, {"error": "yandex_unavailable"}, [clear_cookie])
            return
        auth_code = new_auth_code()
        display_code, browser_token = new_display_code(), new_browser_token()
        with transaction(app.settings.db_path) as conn:
            now = app.now()
            inst = yandex_store.eligible(conn, uid, now)
            issued = None
            if inst is not None:
                challenge = create_challenge(conn, code=display_code, client_id=checked["client_id"],
                    redirect_uri=checked["redirect_uri"], scope=checked["scope"], state=checked["state"],
                    browser_token=browser_token, now_ms=app.now(), ttl_ms=app.settings.challenge_ttl_ms)
                approved = approve_challenge(conn, challenge_id=challenge, code_hash=sha256_text(display_code),
                    installation_id=inst["id"], bake_key=inst["selected_bake_key"],
                    generation=inst["selected_generation"], expected_epoch=inst["authority_epoch"], now_ms=now)
                if approved:
                    issued = issue_auth_code(conn, challenge_id=challenge, browser_hash=sha256_text(browser_token),
                        code=auth_code, now_ms=now, ttl_ms=app.settings.auth_code_ttl_ms)
                    if issued is not None:
                        # Same transaction as real code issuance, not login, select,
                        # callback opening, fallback, or a failed/ambiguous match.
                        yandex_store.consume_authority(conn, inst, uid, challenge, now)
        if issued is None:
            self._yandex_fallback(checked, clear_cookie)
            return
        LOG.info("match=uid uid_hash=%s", uid_hash(uid, app.settings.yandex_uid_salt))
        location = build_redirect(issued["redirect_uri"], auth_code, issued["state"], issued["client_id"], issued["scope"])
        self._send(303, b"", extra=[("Location", location), clear_cookie])

    def _yandex_fallback(self, checked, clear_cookie) -> None:
        csrf = new_csrf()
        self._send(200, _start_page(checked, csrf), "text/html; charset=utf-8",
                   [clear_cookie, ("Set-Cookie", self._cookie(CSRF_COOKIE, csrf))])

    def _oauth_get(self, app: App, query: str) -> None:
        params = parse_qs(query, keep_blank_values=True)
        checked = validate_oauth_params(params, app.settings)
        if checked is None:
            self._send(400, _HTML_REJECTED, "text/html; charset=utf-8")
            return
        if app.settings.yandex_match_configured():
            with transaction(app.settings.db_path) as conn:
                allowed = rate_allow(conn, "yandex-oauth:" + self._peer(), app.now(),
                                     app.settings.oauth_window_ms, app.settings.oauth_limit)
            pending = app.yandex_states.create(checked, app.now()) if allowed else None
            if pending is None:
                self._send_json(429, {"error": "denied"})
                return
            state, browser, challenge = pending
            location = "https://oauth.yandex.ru/authorize?" + urlencode({
                "response_type": "code", "client_id": app.settings.yandex_client_id,
                "redirect_uri": CALLBACK, "state": state,
                "code_challenge": challenge, "code_challenge_method": "S256"})
            self._send(302, b"", extra=[("Location", location),
                       ("Set-Cookie", self._cookie("madre_yandex", browser))])
            return
        csrf = new_csrf()
        page = _start_page(checked, csrf)
        self._send(200, page, "text/html; charset=utf-8", [("Set-Cookie", self._cookie(CSRF_COOKIE, csrf))])

    def _oauth_post(self, app: App, raw: bytes) -> None:
        if not origin_ok(self.headers.get("Origin"), self.headers.get("Host")):
            self._send(400, _HTML_REJECTED, "text/html; charset=utf-8")
            return
        if media_type(self.headers.get("Content-Type")) != "application/x-www-form-urlencoded":
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        params = form_params(raw)
        if params is None:
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        checked = validate_oauth_params(params, app.settings)
        cookies = parse_cookies(self.headers.get("Cookie"))
        form_csrf = one_param(params, "csrf")
        if checked is None or not form_csrf or not const_eq(form_csrf, cookies.get(CSRF_COOKIE, "")):
            self._send(400, _HTML_REJECTED, "text/html; charset=utf-8")
            return
        code = new_display_code()
        browser = new_browser_token()
        csrf = new_csrf()
        with transaction(app.settings.db_path) as conn:
            purge(conn, app.now(), app.settings.receipt_ttl_ms, app.settings.snapshot_ttl_ms)
            if not rate_allow(conn, "oauth:" + self._peer(), app.now(), app.settings.oauth_window_ms, app.settings.oauth_limit):
                self._send(429, b'{"ok":false,"error":"denied"}')
                return
            create_challenge(
                conn,
                code=code,
                client_id=checked["client_id"],
                redirect_uri=checked["redirect_uri"],
                scope=checked["scope"],
                state=checked["state"],
                browser_token=browser,
                now_ms=app.now(),
                ttl_ms=app.settings.challenge_ttl_ms,
            )
        page = _code_page(code, csrf)
        self._send(
            200,
            page,
            "text/html; charset=utf-8",
            [
                ("Set-Cookie", self._cookie(BROWSER_COOKIE, browser)),
                ("Set-Cookie", self._cookie(CSRF_COOKIE, csrf)),
            ],
        )

    def _oauth_challenge(self, app: App) -> None:
        cookies = parse_cookies(self.headers.get("Cookie"))
        browser = cookies.get(BROWSER_COOKIE, "")
        if not browser:
            self._send(401, b'{"ok":false,"error":"denied"}')
            return
        with transaction(app.settings.db_path) as conn:
            row = challenge_by_browser(conn, sha256_text(browser))
            if row is None:
                self._send(401, b'{"ok":false,"error":"denied"}')
                return
            if row["consumed_at"] is not None:
                status = "consumed"
            elif int(row["expires_at"]) <= app.now():
                status = "expired"
            elif row["approved_at"] is not None:
                status = "approved"
            else:
                status = "pending"
            payload = {"status": status, "expires_at": int(row["expires_at"])}
        self._send_json(200, payload)

    def _oauth_confirm(self, app: App, raw: bytes) -> None:
        if not origin_ok(self.headers.get("Origin"), self.headers.get("Host")):
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        if media_type(self.headers.get("Content-Type")) != "application/x-www-form-urlencoded":
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        params = form_params(raw)
        cookies = parse_cookies(self.headers.get("Cookie"))
        if params is None:
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        form_csrf = one_param(params, "csrf")
        browser = cookies.get(BROWSER_COOKIE, "")
        if not form_csrf or not browser or not const_eq(form_csrf, cookies.get(CSRF_COOKIE, "")):
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        code = new_auth_code()
        with transaction(app.settings.db_path) as conn:
            row = challenge_by_browser(conn, sha256_text(browser))
            if row is None:
                self._send(400, b'{"ok":false,"error":"denied"}')
                return
            issued = issue_auth_code(
                conn,
                challenge_id=row["id"],
                browser_hash=sha256_text(browser),
                code=code,
                now_ms=app.now(),
                ttl_ms=app.settings.auth_code_ttl_ms,
            )
            if issued is None:
                self._send(400, b'{"ok":false,"error":"denied"}')
                return
            location = build_redirect(issued["redirect_uri"], code, issued["state"], issued["client_id"], issued["scope"])
        body = "<!doctype html><html><head><meta charset=\"utf-8\"><title>redirect</title></head><body>redirect</body></html>".encode()
        self._send(303, body, "text/html; charset=utf-8", [("Location", location)])

    def _oauth_token(self, app: App, raw: bytes, refresh_only: bool) -> None:
        if media_type(self.headers.get("Content-Type")) != "application/x-www-form-urlencoded":
            self._send_json(400, {"error": "invalid_request"})
            return
        if not app.settings.oauth_configured():
            self._send_json(503, {"error": "invalid_client"})
            return
        params = form_params(raw)
        if params is None:
            self._send_json(400, {"error": "invalid_request"})
            return
        client_id = one_param(params, "client_id")
        client_secret = one_param(params, "client_secret")
        if not client_id or client_secret is None or not const_eq(client_id, app.settings.oauth_client_id) or not const_eq(client_secret, app.settings.oauth_client_secret):
            self._send_json(401, {"error": "invalid_client"})
            return
        grant_type = one_param(params, "grant_type")
        if refresh_only:
            grant_type = grant_type or "refresh_token"
            if grant_type != "refresh_token":
                self._send_json(400, {"error": "invalid_request"})
                return
        started = time.monotonic()
        if grant_type == "authorization_code":
            code = one_param(params, "code")
            redirect_uri = one_param(params, "redirect_uri")
            if not code or redirect_uri != EXACT_REDIRECT or len(code) >= TOKEN_MAX_CHARS:
                self._send_json(400, {"error": "invalid_grant"})
                return
            access = new_access_token()
            refresh = new_refresh_token()
            with transaction(app.settings.db_path) as conn:
                pair = redeem_auth_code(
                    conn,
                    code_hash=sha256_text(code),
                    client_id=client_id,
                    redirect_uri=redirect_uri,
                    now_ms=app.now(),
                    access_token=access,
                    refresh_token=refresh,
                    access_ttl_s=app.settings.access_ttl_s,
                    grant_ttl_ms=app.settings.grant_ttl_ms,
                )
            if pair is None:
                self._send_json(400, {"error": "invalid_grant"})
                return
            self._send_token(pair.access_token, pair.refresh_token, pair.expires_in)
            return
        if grant_type == "refresh_token":
            refresh = one_param(params, "refresh_token")
            if not refresh or len(refresh) >= TOKEN_MAX_CHARS:
                self._send_json(400, {"error": "invalid_grant"})
                return
            access = new_access_token()
            rotated = new_refresh_token()
            with transaction(app.settings.db_path) as conn:
                pair = rotate_refresh(
                    conn,
                    refresh_hash=sha256_text(refresh),
                    now_ms=app.now(),
                    access_token=access,
                    refresh_token=rotated,
                    access_ttl_s=app.settings.access_ttl_s,
                    grant_ttl_ms=app.settings.grant_ttl_ms,
                )
            if time.monotonic() - started > 5:
                self._send_json(500, {"error": "invalid_grant"})
                return
            if pair is None:
                self._send_json(400, {"error": "invalid_grant"})
                return
            self._send_token(pair.access_token, pair.refresh_token, pair.expires_in)
            return
        self._send_json(400, {"error": "invalid_request"})

    def _send_token(self, access: str, refresh: str, expires_in: int) -> None:
        if len(access) >= TOKEN_MAX_CHARS or len(refresh) >= TOKEN_MAX_CHARS or not isinstance(expires_in, int):
            self._send_json(500, {"error": "invalid_grant"})
            return
        payload = {
            "access_token": access,
            "token_type": "bearer",
            "expires_in": expires_in,
            "refresh_token": refresh,
        }
        raw = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        if len(raw) >= TOKEN_RESPONSE_MAX_CHARS:
            self._send_json(500, {"error": "invalid_grant"})
            return
        self._send(200, raw)

    def _webhook(self, app: App, raw: bytes) -> None:
        body = self._json_object(raw)
        if body is None:
            self._send(400, b'{"ok":false,"error":"denied"}')
            return
        kind, grant = self._classify(app, body)
        if kind == "deny":
            self._send(401, b'{"ok":false,"error":"denied"}')
            return
        if kind in ("unlinked", "static"):
            self._send_json(200, guard_copy(self._public(body)))
            return
        self._send_json(200, guard_copy(self._linked(app, body, grant)))

    def _classify(self, app: App, body: dict) -> tuple[str, Any]:
        header = self.headers.get("Authorization")
        session = body.get("session") if isinstance(body.get("session"), dict) else {}
        user = session.get("user") if isinstance(session.get("user"), dict) else {}
        body_token = user.get("access_token") if "access_token" in user else None
        if "access_token" in user and not isinstance(body_token, str):
            return "deny", None
        parsed = parse_authorization(header)
        if parsed is not None and parsed[0] == "installation":
            return "deny", None
        if header and static_matches(header, app.settings.static_auth):
            return "static", None
        header_token = None
        if parsed is not None and parsed[0] == "bearer":
            header_token = parsed[1]
        elif parsed is not None and parsed[0] == "raw":
            return "deny", None
        if header_token is not None and body_token is not None and not const_eq(header_token, body_token):
            return "deny", None
        token = header_token if header_token is not None else body_token
        if token is None and not header:
            return "unlinked", None
        if token is None or len(token) >= TOKEN_MAX_CHARS:
            return "deny", None
        with transaction(app.settings.db_path) as conn:
            grant = grant_by_access(conn, sha256_text(token), app.now())
        if grant is None:
            return "deny", None
        return "oauth", grant

    def _public(self, body: dict) -> dict:
        if "account_linking_complete_event" in body:
            return alice_reply("Связка не подтверждена. Ничего не отправляю.")
        utter = utterance_of(body)
        if utter.name == "spoken_code":
            return alice_reply("Код вводится только на телефоне, на экране. Голосом его не принимаю.")
        if utter.name == "link":
            if account_linking_supported(body):
                return linking_reply()
            return alice_reply("На этом устройстве связка аккаунта недоступна.")
        try:
            return public_handle(body)
        except Exception:
            LOG.warning("public reader failed")
            return alice_reply("Книга на секунду запнулась. Повтори, пожалуйста.")

    def _linked(self, app: App, body: dict, grant) -> dict:
        if "account_linking_complete_event" in body:
            return self._complete_event(app, grant)
        utter = utterance_of(body)
        session_id = session_id_of(body)
        if utter.name == "spoken_code":
            return alice_reply("Код вводится только на телефоне, на экране. Голосом его не принимаю.")
        if utter.name == "ping":
            return alice_reply("pong", end=True)
        if utter.name == "help":
            return alice_reply(help_text())
        if utter.name == "link":
            return alice_reply("Аккаунт уже связан с выбранной выпечкой. Новый код голосом не принимаю.")
        if utter.name == "exit":
            if session_id:
                with transaction(app.settings.db_path) as conn:
                    clear_intents(conn, grant["id"], session_id, app.now())
            return alice_reply("Выхожу. Выпечку не отменяю.", end=True)
        if utter.name in ("unknown", "ambiguous"):
            return alice_reply("Не разобрала. Ничего не отправляю.")
        if utter.name == "yes":
            return self._confirm(app, grant, session_id, expect_cancel=False)
        if utter.name == "confirm_cancel":
            return self._confirm(app, grant, session_id, expect_cancel=True)
        if utter.name == "receipt":
            return self._read_receipt_speech(app, grant)
        if utter.operation:
            return self._turn1(app, grant, session_id, utter.operation)
        return self._read(app, grant, utter.name)

    def _complete_event(self, app: App, grant) -> dict:
        fresh = self._fresh(app, grant)
        if fresh is None:
            return alice_reply("Аккаунт связан. Живой выпечки на телефоне сейчас не видно. Ничего не отправляю.")
        snap, _received, _sane = fresh
        return alice_reply(f"Аккаунт связан. На телефоне {step_phrase(snap)}. Шаг не меняю.")

    def _fresh(self, app: App, grant):
        with transaction(app.settings.db_path) as conn:
            lease = lease_row(conn, grant["installation_id"])
            snap_row = snapshot_row(conn, grant["installation_id"])
            now = app.now()
        if lease is None or snap_row is None:
            return None
        if lease["boot_id"] != app.boot_id:
            return None
        if lease["bake_key"] != grant["bake_key"] or lease["selection_generation"] != grant["selection_generation"]:
            return None
        if now - int(lease["last_poll_at"]) > app.settings.freshness_ms:
            return None
        snap = json.loads(snap_row["payload_json"])
        if snap["bake_key"] != grant["bake_key"]:
            return None
        return snap, int(snap_row["received_at"]), bool(snap_row["clock_sane"])

    def _read(self, app: App, grant, name: str) -> dict:
        fresh = self._fresh(app, grant)
        if fresh is None:
            return alice_reply("Телефон сейчас не на связи. Состояние не подтверждаю и команду не отправляю.")
        snap, received, sane = fresh
        if name == "what":
            extra = ""
            if snap.get("ended"):
                extra = " На телефоне выпечка уже завершена."
            elif snap.get("paused"):
                extra = " Сейчас на паузе."
            return alice_reply("Сейчас " + step_phrase(snap) + "." + extra)
        if name == "repeat":
            text = "Сейчас " + step_phrase(snap) + "."
            if snap.get("control_ready") and snap.get("step_text"):
                text += " " + snap["step_text"]
            elif not snap.get("control_ready"):
                text += " Текст шага не подтверждаю: порция или версия рецепта неизвестны."
            return alice_reply(text)
        if name == "peek":
            title = snap.get("next_step_title")
            if not title:
                return alice_reply("Это последний шаг. Шаг не двигаю и голосом не завершаю.")
            text = f"Следующий, шаг не двигаю: {title}."
            if snap.get("control_ready") and snap.get("next_step_text"):
                text += " " + snap["next_step_text"]
            return alice_reply(text)
        if name == "remaining":
            projected = project_remaining(
                snap,
                now_ms=app.now(),
                received_at=received,
                freshness_ms=app.settings.freshness_ms,
                clock_ok=sane,
            )
            if projected is None:
                return alice_reply("Точное время неизвестно. Проверь телефон.")
            if snap.get("paused"):
                return alice_reply(f"На паузе, осталось {speak_duration(projected)}. Пауза время не уменьшает.")
            return alice_reply(f"Осталось около {speak_duration(projected)}.")
        if name == "quantities":
            if snap.get("control_ready") and snap.get("quantities"):
                return alice_reply("Количества с телефона: " + snap["quantities"])
            return alice_reply("Точные количества неизвестны. Не считаю порцию сама.")
        return alice_reply("Не разобрала. Ничего не отправляю.")

    def _read_receipt_speech(self, app: App, grant) -> dict:
        with transaction(app.settings.db_path) as conn:
            receipt = latest_receipt(
                conn,
                grant["installation_id"],
                grant["bake_key"],
                grant["selection_generation"],
                app.now(),
                app.settings.receipt_ttl_ms,
            )
        if receipt is None:
            return alice_reply("Сохранённого ответа телефона нет. Ничего не отправляю повторно.")
        return alice_reply(speech_for_receipt(receipt["status"]) + " Повторно не отправляю.")

    def _turn1(self, app: App, grant, session_id: str, operation: str) -> dict:
        if not session_id:
            return alice_reply(not_sent("Нет сессии."))
        fresh = self._fresh(app, grant)
        if fresh is None:
            return alice_reply(not_sent("Телефон не на связи."))
        snap, _received, sane = fresh
        if not sane:
            return alice_reply(not_sent("Часы телефона не сходятся."))
        if snap.get("ended") or not snap.get("control_ready"):
            return alice_reply(not_sent("Телефон не подтвердил управление этим шагом."))
        if operation == "ADVANCE" and int(snap["step_index"]) >= int(snap["step_count"]) - 1:
            return alice_reply("Последний шаг завершается только на экране телефона. Не отправлено.")
        now = app.now()
        with transaction(app.settings.db_path) as conn:
            put_intent(
                conn,
                {
                    "grant_id": grant["id"],
                    "session_id": session_id,
                    "operation": operation,
                    "recipe_id": snap["recipe_id"],
                    "recipe_revision": snap["recipe_revision"],
                    "step_index": snap["step_index"],
                    "bake_key": snap["bake_key"],
                    "revision": snap["revision"],
                    "selection_generation": grant["selection_generation"],
                    "expected_paused": snap["paused"],
                    "created_at": now,
                    "expires_at": now + app.settings.intent_ttl_ms,
                    "boot_id": app.boot_id,
                },
            )
        target = step_phrase(snap)
        if operation == "PAUSE":
            ask = f"Поставить на паузу {target}? Скажи да."
        elif operation == "RESUME":
            ask = f"Продолжить {target}? Скажи да."
        elif operation == "ADVANCE":
            ask = f"Перейти дальше, сейчас {target}? Скажи да."
        elif operation == "BACK":
            ask = f"Вернуться на шаг назад, сейчас {target}? Скажи да."
        else:
            ask = f"Отменить выпечку {snap['recipe_name']}? Скажи подтверждаю отмену."
        return alice_reply(ask)

    def _confirm(self, app: App, grant, session_id: str, expect_cancel: bool) -> dict:
        if not session_id:
            return alice_reply(not_sent("Нет сессии."))
        started = time.monotonic()
        minted = self._mint(app, grant, session_id, expect_cancel)
        if minted[0] is None:
            return alice_reply(minted[1])
        command_id, connection_id, speech_if_immediate = minted
        if speech_if_immediate:
            return alice_reply(speech_if_immediate)
        app.waiters.wake_poll(connection_id)
        event = app.waiters.ack_event(command_id)
        try:
            remain = app.settings.request_budget_s - (time.monotonic() - started)
            wait_s = min(app.settings.ack_wait_s, remain) if remain > 0 else 0
            if wait_s > 0:
                event.wait(wait_s)
            with transaction(app.settings.db_path) as conn:
                receipt = read_receipt(conn, command_id)
                if receipt is None:
                    mark_unknown(conn, command_id)
            if receipt is None:
                return alice_reply(unknown_outcome())
            return alice_reply(speech_for_receipt(receipt["status"]))
        finally:
            app.waiters.release_ack(command_id)

    def _mint(self, app: App, grant, session_id: str, expect_cancel: bool):
        with transaction(app.settings.db_path) as conn:
            now = app.now()
            intent = open_intent(conn, grant["id"], session_id, now, app.boot_id)
            if intent is None:
                return None, not_sent("Подтверждать нечего."), None
            if expect_cancel and intent["operation"] != "CANCEL":
                return None, "Эта фраза подтверждает только отмену выпечки. Не отправлено.", None
            if not expect_cancel and intent["operation"] == "CANCEL":
                return None, "Для отмены скажи подтверждаю отмену. Не отправлено.", None
            lease = lease_row(conn, grant["installation_id"])
            snap_row = snapshot_row(conn, grant["installation_id"])
            if lease is None or snap_row is None or lease["boot_id"] != app.boot_id:
                consume_intent(conn, intent["id"], now)
                return None, not_sent("Телефон не на связи."), None
            if now - int(lease["last_poll_at"]) > app.settings.freshness_ms:
                consume_intent(conn, intent["id"], now)
                return None, not_sent("Телефон не на связи."), None
            if lease["connection_id"] is None or lease["bake_key"] != grant["bake_key"]:
                consume_intent(conn, intent["id"], now)
                return None, not_sent("Выбор выпечки изменился."), None
            if lease["selection_generation"] != intent["selection_generation"]:
                consume_intent(conn, intent["id"], now)
                return None, not_sent("Выбор выпечки изменился."), None
            snap = json.loads(snap_row["payload_json"])
            if not snap_row["clock_sane"]:
                consume_intent(conn, intent["id"], now)
                return None, not_sent("Часы телефона не сходятся."), None
            if int(snap["revision"]) != int(intent["revision"]) or int(snap["step_index"]) != int(intent["step_index"]):
                consume_intent(conn, intent["id"], now)
                return None, not_sent("Состояние изменилось."), None
            if bool(snap["paused"]) != bool(intent["expected_paused"]):
                consume_intent(conn, intent["id"], now)
                return None, not_sent("Состояние изменилось."), None
            if snap.get("recipe_revision") != intent["recipe_revision"] or snap.get("bake_key") != intent["bake_key"]:
                consume_intent(conn, intent["id"], now)
                return None, not_sent("Состояние изменилось."), None
            if snap.get("ended") or not snap.get("control_ready"):
                consume_intent(conn, intent["id"], now)
                return None, not_sent("Телефон не подтвердил управление этим шагом."), None
            if intent["operation"] == "ADVANCE" and int(snap["step_index"]) >= int(snap["step_count"]) - 1:
                consume_intent(conn, intent["id"], now)
                return None, "Последний шаг завершается только на экране телефона. Не отправлено.", None
            if inflight_exists(conn, grant["installation_id"], now):
                return None, not_sent("Предыдущая команда ещё не закрыта."), None
            if not grant_live(conn, grant["id"], now):
                consume_intent(conn, intent["id"], now)
                return None, not_sent("Связка больше не действует."), None
            command_id = mint_command(
                conn,
                {
                    "installation_id": grant["installation_id"],
                    "connection_id": lease["connection_id"],
                    "selection_generation": intent["selection_generation"],
                    "bake_key": intent["bake_key"],
                    "operation": intent["operation"],
                    "recipe_revision": intent["recipe_revision"],
                    "expected_revision": int(intent["revision"]),
                    "expected_step": int(intent["step_index"]),
                    "expected_paused": bool(intent["expected_paused"]),
                    "issued_at": now,
                    "expires_at": now + app.settings.command_ttl_ms,
                    "boot_id": app.boot_id,
                    "session_id": session_id,
                    "intent_id": intent["id"],
                },
            )
            consume_intent(conn, intent["id"], now)
            return command_id, lease["connection_id"], None


_HTML_REJECTED = (
    "<!doctype html><html><head><meta charset=\"utf-8\"><title>rejected</title></head>"
    "<body><p>Запрос отклонён.</p></body></html>"
).encode("utf-8")


def _start_page(checked: dict[str, str], csrf: str) -> bytes:
    scope = html_escape(checked["scope"])
    return (
        "<!doctype html><html><head><meta charset=\"utf-8\"><title>Голос закваски</title></head><body>"
        "<p>Подтвердите запрос связки. Код появится после отправки формы и вводится только на телефоне.</p>"
        f"<form method=\"post\" action=\"{OAUTH_PREFIX}/authorize\">"
        f"<input type=\"hidden\" name=\"csrf\" value=\"{html_escape(csrf)}\">"
        f"<input type=\"hidden\" name=\"client_id\" value=\"{html_escape(checked['client_id'])}\">"
        f"<input type=\"hidden\" name=\"redirect_uri\" value=\"{html_escape(checked['redirect_uri'])}\">"
        "<input type=\"hidden\" name=\"response_type\" value=\"code\">"
        f"<input type=\"hidden\" name=\"scope\" value=\"{scope}\">"
        f"<input type=\"hidden\" name=\"state\" value=\"{html_escape(checked['state'])}\">"
        "<button type=\"submit\">Показать код</button></form></body></html>"
    ).encode("utf-8")


def _code_page(code: str, csrf: str) -> bytes:
    return (
        "<!doctype html><html><head><meta charset=\"utf-8\"><title>Голос закваски</title></head><body>"
        "<p>Введите код только на экране телефона. Не произносите его.</p>"
        f"<p>{html_escape(code)}</p>"
        f"<form method=\"post\" action=\"{OAUTH_PREFIX}/confirm\">"
        f"<input type=\"hidden\" name=\"csrf\" value=\"{html_escape(csrf)}\">"
        "<button type=\"submit\">Продолжить</button></form></body></html>"
    ).encode("utf-8")


_DEADLINES: weakref.WeakKeyDictionary = weakref.WeakKeyDictionary()


class _SocketDeadline:
    """Absolute request lifetime. Disarm before close so a late timer cannot shut a reused fd."""

    def __init__(self, sock: socket.socket, seconds: float) -> None:
        self._lock = threading.Lock()
        self._sock = sock
        self._done = False
        limit = seconds if seconds > 0 else 0.001
        _DEADLINES[sock] = time.monotonic() + limit
        sock.settimeout(limit)
        self._timer = threading.Timer(limit, self._expire)
        self._timer.daemon = True
        self._timer.start()

    def _expire(self) -> None:
        with self._lock:
            if self._done or self._sock is None:
                return
            try:
                self._sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

    def disarm(self) -> None:
        with self._lock:
            self._done = True
            sock = self._sock
            self._sock = None
        self._timer.cancel()
        if sock is not None:
            _DEADLINES.pop(sock, None)


class BoundedServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True
    request_queue_size = 32
    app: App

    def process_request(self, request, client_address) -> None:  # noqa: N802
        if not self.app.slots.acquire(blocking=False):
            self._reject_saturated(request)
            return
        try:
            thread = threading.Thread(target=self._serve, args=(request, client_address), daemon=True)
            thread.start()
        except Exception:
            self.app.slots.release()
            try:
                request.close()
            except OSError:
                pass
            raise

    def _reject_saturated(self, request) -> None:
        try:
            request.settimeout(self.app.settings.request_deadline_s)
            request.sendall(
                b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            )
        except OSError:
            pass
        finally:
            try:
                request.close()
            except OSError:
                pass

    def _serve(self, request, client_address) -> None:
        budget = None
        try:
            budget = _SocketDeadline(request, self.app.settings.request_deadline_s)
            self.finish_request(request, client_address)
        except Exception:
            LOG.warning("request ended")
        finally:
            if budget is not None:
                budget.disarm()
            try:
                self.shutdown_request(request)
            finally:
                self.app.slots.release()


def make_server(host: str, port: int, app: App) -> BoundedServer:
    httpd = BoundedServer((host, port), Handler)
    httpd.app = app
    return httpd
