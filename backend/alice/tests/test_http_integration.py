#!/usr/bin/env python3
"""Local HTTP integration against the voice bridge.

Simulated phone and OAuth fixture only. Not a physical device, not Station,
and not a real Yandex OAuth exchange.
"""

from __future__ import annotations

import json
import os
import re
import sqlite3
import sys
import tempfile
import threading
import time
import unittest
import uuid
import socket
from http.client import HTTPConnection
from pathlib import Path
from urllib.parse import parse_qs, quote, urlencode, urlsplit

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app import App, make_server  # noqa: E402
from config import (  # noqa: E402
    ALPHABET32,
    EXACT_REDIRECT,
    EXACT_SCOPE,
    POLL_WAIT_S,
    REQUEST_BUDGET_S,
    REQUEST_DEADLINE_S,
    Settings,
)
from security import new_installation_credential, sha256_text  # noqa: E402

RFC4648 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
PHONE = "/alice/madre/voice/phone"
OAUTH = "/alice/madre/voice/oauth"
WEBHOOK = "/alice/madre"
CLIENT_ID = "madre-test-client"
CLIENT_SECRET = "madre-test-secret"
STATE = "st ate&1"
CODE_RE = re.compile(r"^[A-Z2-7]{10}$")
ACK_FIELDS = {
    "connection_id",
    "selection_generation",
    "command_id",
    "bake_key",
    "expected_revision",
    "status",
    "new_revision",
}
COMMAND_FIELDS = {
    "id",
    "operation",
    "bake_key",
    "recipe_revision",
    "selection_generation",
    "connection_id",
    "expected_revision",
    "expected_step",
    "expected_paused",
    "issued_at",
    "expires_at",
}
POLL_FIELDS = {"server_time", "linked", "command"}


def uid() -> str:
    return str(uuid.uuid4())


class HttpFixture(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.tmp = tempfile.TemporaryDirectory(prefix="madre-alice-http-")
        cls.db_path = os.path.join(cls.tmp.name, "voice.sqlite")
        cls.settings = Settings(
            db_path=cls.db_path,
            oauth_client_id=CLIENT_ID,
            oauth_client_secret=CLIENT_SECRET,
            poll_wait_s=2.0,
            ack_wait_s=0.6,
            request_budget_s=4.5,
            pair_fail_limit=3,
            enroll_limit=100,
            oauth_limit=100,
        )
        cls.app = App(cls.settings)
        cls.httpd = make_server("127.0.0.1", 0, cls.app)
        cls.port = int(cls.httpd.server_address[1])
        cls.thread = threading.Thread(
            target=cls.httpd.serve_forever,
            kwargs={"poll_interval": 0.05},
            daemon=True,
        )
        cls.thread.start()

    @classmethod
    def tearDownClass(cls) -> None:
        cls.httpd.shutdown()
        cls.httpd.server_close()
        cls.tmp.cleanup()

    def request(self, method: str, path: str, body: bytes | None = None, headers: dict | None = None, timeout: float = 8, port: int | None = None):
        conn = HTTPConnection("127.0.0.1", self.port if port is None else port, timeout=timeout)
        try:
            conn.request(method, path, body=body, headers=headers or {})
            resp = conn.getresponse()
            raw = resp.read()
            return resp.status, list(resp.getheaders()), raw
        finally:
            conn.close()

    def json_call(self, method: str, path: str, payload: object | None = None, headers: dict | None = None, timeout: float = 8, port: int | None = None):
        hdrs = {"Content-Type": "application/json"}
        if headers:
            hdrs.update(headers)
        raw = b"" if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        status, resp_headers, body = self.request(method, path, raw, hdrs, timeout=timeout, port=port)
        parsed = None
        if body:
            parsed = json.loads(body.decode("utf-8"))
        return status, resp_headers, parsed, body

    def form_call(self, path: str, fields: dict, headers: dict | None = None, port: int | None = None):
        raw = urlencode(fields).encode("ascii")
        hdrs = {"Content-Type": "application/x-www-form-urlencoded"}
        if headers:
            hdrs.update(headers)
        return self.request("POST", path, raw, hdrs, port=port)

    def install_header(self, credential: str) -> dict:
        return {"Authorization": "Installation " + credential}

    def bearer_header(self, token: str) -> dict:
        return {"Authorization": "Bearer " + token}

    def enroll(self, credential: str | None = None) -> tuple[str, str]:
        credential = credential or new_installation_credential()
        status, _, body, _ = self.json_call(
            "POST",
            PHONE + "/enroll",
            {"credential": credential},
            self.install_header(credential),
        )
        self.assertEqual(status, 200, body)
        self.assertEqual(set(body), {"installation_id"})
        return credential, body["installation_id"]

    def select(self, credential: str, bake: str | None = None, generation: str | None = None):
        bake = bake or uid()
        generation = generation or uid()
        status, _, body, _ = self.json_call(
            "POST",
            PHONE + "/select",
            {"bake_key": bake, "selection_generation": generation},
            self.install_header(credential),
        )
        self.assertEqual(status, 200, body)
        self.assertEqual(body, {"selected": True})
        return bake, generation

    def snapshot(self, bake: str, **over) -> dict:
        now = int(time.time() * 1000)
        snap = {
            "bake_key": bake,
            "recipe_id": "home_bread",
            "recipe_revision": "rev-1",
            "recipe_name": "Хлебушек",
            "portions": 1,
            "step_index": 0,
            "step_count": 3,
            "step_title": "Опара",
            "step_text": "Смешать",
            "next_step_title": "Автолиз",
            "next_step_text": "Ждать",
            "quantities": "610 г муки",
            "paused": False,
            "ended": False,
            "revision": 4,
            "remaining_seconds": 600,
            "captured_at": now,
            "control_ready": True,
        }
        snap.update(over)
        return snap

    def poll(self, credential: str, connection: str, generation: str, bake: str, **over):
        return self.json_call(
            "POST",
            PHONE + "/poll",
            {
                "connection_id": connection,
                "selection_generation": generation,
                "snapshot": self.snapshot(bake, **over),
            },
            self.install_header(credential),
            timeout=8,
        )

    def ack(self, credential: str, command: dict, status: str, new_revision):
        payload = {
            "connection_id": command["connection_id"],
            "selection_generation": command["selection_generation"],
            "command_id": command["id"],
            "bake_key": command["bake_key"],
            "expected_revision": command["expected_revision"],
            "status": status,
            "new_revision": new_revision,
        }
        self.assertEqual(set(payload), ACK_FIELDS)
        return self.json_call("POST", PHONE + "/ack", payload, self.install_header(credential))

    def alice(self, command: str, token: str | None = None, session_id: str = "sess-1", original: str | None = None):
        body = {
            "meta": {"locale": "ru-RU", "timezone": "Europe/Moscow", "client_id": "test", "interfaces": {}},
            "request": {
                "command": command,
                "original_utterance": command if original is None else original,
                "type": "SimpleUtterance",
            },
            "session": {
                "new": False,
                "message_id": 1,
                "session_id": session_id,
                "skill_id": "not-an-auth",
                "user_id": "not-an-auth",
            },
            "version": "1.0",
        }
        if token is not None:
            body["session"]["user"] = {"access_token": token}
        return body

    def webhook(self, command: str, token: str, session_id: str = "sess-1", original: str | None = None, timeout: float = 8):
        return self.json_call(
            "POST",
            WEBHOOK,
            self.alice(command, token, session_id, original),
            self.bearer_header(token),
            timeout=timeout,
        )

    def header_map(self, headers: list[tuple[str, str]]) -> dict[str, list[str]]:
        out: dict[str, list[str]] = {}
        for key, value in headers:
            out.setdefault(key.lower(), []).append(value)
        return out

    def cookies(self, headers: list[tuple[str, str]]) -> dict[str, str]:
        found = {}
        for key, value in headers:
            if key.lower() != "set-cookie":
                continue
            pair = value.split(";", 1)[0]
            name, val = pair.split("=", 1)
            found[name] = val
        return found

    def cookie_header(self, pairs: dict[str, str]) -> str:
        return "; ".join(f"{key}={value}" for key, value in pairs.items())

    def authorize_query(self, **over) -> str:
        params = {
            "client_id": CLIENT_ID,
            "redirect_uri": EXACT_REDIRECT,
            "response_type": "code",
            "scope": EXACT_SCOPE,
            "state": STATE,
        }
        params.update(over)
        return urlencode(params)

    def csrf_from(self, page: bytes) -> str:
        match = re.search(br'name="csrf" value="([^"]+)"', page)
        self.assertIsNotNone(match)
        return match.group(1).decode("ascii")

    def display_code_from(self, page: bytes) -> str:
        match = re.search(br"<p>([A-Z2-7]{10})</p>", page)
        self.assertIsNotNone(match, page[:400])
        return match.group(1).decode("ascii")

    def start_challenge(self) -> tuple[str, dict[str, str]]:
        status, headers, page = self.request("GET", OAUTH + "/authorize?" + self.authorize_query())
        self.assertEqual(status, 200, page[:200])
        csrf = self.csrf_from(page)
        jar = self.cookies(headers)
        self.assertIn("madre_csrf", jar)
        self.assertNotIn("madre_browser", jar)
        status, headers, page = self.form_call(
            OAUTH + "/authorize",
            {
                "csrf": csrf,
                "client_id": CLIENT_ID,
                "redirect_uri": EXACT_REDIRECT,
                "response_type": "code",
                "scope": EXACT_SCOPE,
                "state": STATE,
            },
            {"Cookie": self.cookie_header({"madre_csrf": jar["madre_csrf"]})},
        )
        self.assertEqual(status, 200, page[:300])
        code = self.display_code_from(page)
        self.assertRegex(code, CODE_RE)
        jar = self.cookies(headers)
        self.assertIn("madre_browser", jar)
        self.assertIn("madre_csrf", jar)
        return code, jar

    def sql_one(self, query: str, args: tuple = ()):
        conn = sqlite3.connect(self.db_path)
        try:
            return conn.execute(query, args).fetchone()
        finally:
            conn.close()

    def command_count(self) -> int:
        row = self.sql_one("SELECT COUNT(*) FROM commands")
        return int(row[0])

    def test_alphabet_is_rfc4648(self) -> None:
        self.assertEqual(ALPHABET32, RFC4648)
        self.assertEqual(len(ALPHABET32), 32)
        self.assertEqual(len(set(ALPHABET32)), 32)

    def test_enroll_rejects_bearer_and_keeps_installation_separate(self) -> None:
        credential = new_installation_credential()
        before = self.sql_one("SELECT COUNT(*) FROM installations")[0]
        status, _, body, raw = self.json_call(
            "POST",
            PHONE + "/enroll",
            {"credential": credential},
            self.bearer_header(credential),
        )
        self.assertEqual(status, 401, body)
        self.assertNotIn(b"installation_id", raw)
        self.assertEqual(self.sql_one("SELECT COUNT(*) FROM installations")[0], before)
        status, _, body, _ = self.json_call(
            "POST",
            PHONE + "/enroll",
            {"credential": credential},
            self.install_header(credential + "x"),
        )
        self.assertEqual(status, 401, body)
        credential, installation_id = self.enroll(credential)
        again, same_id = self.enroll(credential)
        self.assertEqual(again, credential)
        self.assertEqual(same_id, installation_id)
        stored = self.sql_one("SELECT credential_hash FROM installations WHERE id=?", (installation_id,))
        self.assertEqual(stored[0], sha256_text(credential))
        self.assertNotEqual(stored[0], credential)
        blob = Path(self.db_path).read_bytes()
        self.assertNotIn(credential.encode("ascii"), blob)
        for path in ("/select", "/disconnect", "/pair-preview", "/pair-approve", "/poll", "/ack"):
            status, _, body, raw = self.json_call(
                "POST",
                PHONE + path,
                {},
                self.bearer_header("mat_not-a-phone"),
            )
            self.assertEqual(status, 401, (path, body))
            self.assertNotIn("Хлебушек".encode(), raw)

    def test_oauth_get_does_not_grant_and_token_replay_revokes(self) -> None:
        self.assertEqual(self.sql_one("SELECT COUNT(*) FROM grants")[0], self.sql_one("SELECT COUNT(*) FROM grants")[0])
        grants_before = self.sql_one("SELECT COUNT(*) FROM grants")[0]
        challenges_before = self.sql_one("SELECT COUNT(*) FROM challenges")[0]
        evil = self.authorize_query(redirect_uri="https://evil.example/cb")
        status, headers, page = self.request("GET", OAUTH + "/authorize?" + evil)
        self.assertEqual(status, 400, page[:200])
        self.assertNotIn(b"evil.example", page)
        self.assertNotIn(b"madre_browser", b"".join(v.encode() for _, v in headers))
        split = (
            "client_id=" + quote(CLIENT_ID)
            + "&redirect_uri=" + quote(EXACT_REDIRECT, safe="")
            + "&response_type=code&scope=bake:read&bake:control&state=x"
        )
        status, _, page = self.request("GET", OAUTH + "/authorize?" + split)
        self.assertEqual(status, 400, page[:200])
        swapped = self.authorize_query(scope="bake:control&bake:read")
        status, _, page = self.request("GET", OAUTH + "/authorize?" + swapped)
        self.assertEqual(status, 400, page[:200])
        codes_before = self.sql_one("SELECT COUNT(*) FROM auth_codes")[0]
        status, headers, page = self.request("GET", OAUTH + "/authorize?" + self.authorize_query())
        self.assertEqual(status, 200, page[:200])
        self.assertIn("Голос закваски".encode(), page)
        self.assertNotIn("навык Мадре".encode(), page)
        self.assertIsNone(self.header_map(headers).get("location"))
        self.assertEqual(self.sql_one("SELECT COUNT(*) FROM grants")[0], grants_before)
        self.assertEqual(self.sql_one("SELECT COUNT(*) FROM challenges")[0], challenges_before)
        self.assertEqual(self.sql_one("SELECT COUNT(*) FROM auth_codes")[0], codes_before)
        csrf = self.csrf_from(page)
        jar = self.cookies(headers)
        cookie_line = headers[[k.lower() for k, _ in headers].index("set-cookie")][1]
        self.assertIn("HttpOnly", cookie_line)
        self.assertIn("Secure", cookie_line)
        self.assertIn("SameSite=Lax", cookie_line)
        status, _, page = self.form_call(
            OAUTH + "/authorize",
            {
                "csrf": "not-the-cookie",
                "client_id": CLIENT_ID,
                "redirect_uri": EXACT_REDIRECT,
                "response_type": "code",
                "scope": EXACT_SCOPE,
                "state": STATE,
            },
            {"Cookie": self.cookie_header({"madre_csrf": jar["madre_csrf"]})},
        )
        self.assertEqual(status, 400, page[:200])
        self.assertEqual(self.sql_one("SELECT COUNT(*) FROM challenges")[0], challenges_before)
        code, jar = self.start_challenge()
        self.assertRegex(code, CODE_RE)
        self.assertNotIn(code.encode(), Path(self.db_path).read_bytes())
        status, _, spoken, _ = self.json_call("POST", WEBHOOK, self.alice(code))
        self.assertEqual(status, 200, spoken)
        self.assertIn("телефоне", spoken["response"]["text"])
        self.assertIsNone(self.sql_one("SELECT approved_at FROM challenges WHERE code_hash=?", (sha256_text(code),))[0])
        status, _, challenge, _ = self.json_call(
            "GET",
            OAUTH + "/challenge",
            headers={"Cookie": self.cookie_header(jar)},
        )
        self.assertEqual(status, 200, challenge)
        self.assertEqual(set(challenge), {"status", "expires_at"})
        self.assertEqual(challenge["status"], "pending")
        self.assertEqual(self.sql_one("SELECT COUNT(*) FROM grants")[0], grants_before)
        self.assertEqual(self.sql_one("SELECT COUNT(*) FROM auth_codes")[0], codes_before)
        credential, _installation = self.enroll()
        bake, generation = self.select(credential)
        status, _, preview, _ = self.json_call(
            "POST",
            PHONE + "/pair-preview",
            {"code": code},
            self.install_header(credential),
        )
        self.assertEqual(status, 200, preview)
        self.assertEqual(set(preview), {"scope", "expires_at", "challenge_id"})
        self.assertEqual(preview["scope"], EXACT_SCOPE)
        status, _, approved, _ = self.json_call(
            "POST",
            PHONE + "/pair-approve",
            {
                "challenge_id": preview["challenge_id"],
                "code": code,
                "bake_key": bake,
                "selection_generation": generation,
            },
            self.install_header(credential),
        )
        self.assertEqual(status, 200, approved)
        self.assertEqual(approved, {"approved": True})
        status, _, pending, _ = self.json_call(
            "GET",
            OAUTH + "/challenge",
            headers={"Cookie": self.cookie_header(jar)},
        )
        self.assertEqual(pending["status"], "approved")
        self.assertNotIn("code", pending)
        status, headers, page = self.form_call(
            OAUTH + "/confirm",
            {"csrf": "wrong"},
            {"Cookie": self.cookie_header(jar)},
        )
        self.assertEqual(status, 400, page[:200])
        self.assertIsNone(self.header_map(headers).get("location"))
        status, headers, page = self.form_call(
            OAUTH + "/confirm",
            {"csrf": jar["madre_csrf"]},
            {"Cookie": self.cookie_header(jar)},
        )
        self.assertEqual(status, 303, page[:200])
        location = self.header_map(headers)["location"][0]
        self.assertTrue(location.startswith(EXACT_REDIRECT + "?code="), location)
        query = location.split("?", 1)[1]
        self.assertEqual([part.split("=", 1)[0] for part in query.split("&")], ["code", "state", "client_id", "scope"])
        self.assertEqual(query.count("&"), 3)
        self.assertIn("state=" + quote(STATE, safe=""), query)
        self.assertIn("client_id=" + quote(CLIENT_ID, safe=""), query)
        self.assertIn("scope=" + quote(EXACT_SCOPE, safe=""), query)
        auth_code = parse_qs(urlsplit(location).query)["code"][0]
        self.assertNotEqual(auth_code, code)
        status, _, raw = self.form_call(
            OAUTH + "/token",
            {
                "grant_type": "authorization_code",
                "code": auth_code,
                "redirect_uri": EXACT_REDIRECT,
                "client_id": CLIENT_ID,
                "client_secret": CLIENT_SECRET,
            },
        )
        self.assertEqual(status, 200, raw[:300])
        token = json.loads(raw.decode("utf-8"))
        self.assertEqual(set(token), {"access_token", "token_type", "expires_in", "refresh_token"})
        self.assertEqual(token["token_type"], "bearer")
        self.assertIs(type(token["expires_in"]), int)
        self.assertEqual(token["expires_in"], 900)
        self.assertLess(len(raw), 5000)
        mismatch, _, denied, _ = self.json_call(
            "POST",
            WEBHOOK,
            self.alice("что сейчас", token["access_token"] + "x"),
            self.bearer_header(token["access_token"]),
        )
        self.assertEqual(mismatch, 401, denied)
        status, _, rotated_raw = self.form_call(
            OAUTH + "/token",
            {
                "grant_type": "refresh_token",
                "refresh_token": token["refresh_token"],
                "client_id": CLIENT_ID,
                "client_secret": CLIENT_SECRET,
            },
        )
        self.assertEqual(status, 200, rotated_raw[:300])
        rotated = json.loads(rotated_raw.decode("utf-8"))
        self.assertNotEqual(rotated["refresh_token"], token["refresh_token"])
        self.assertEqual(rotated["expires_in"], 900)
        status, _, replay_raw = self.form_call(
            OAUTH + "/refresh",
            {
                "grant_type": "refresh_token",
                "refresh_token": token["refresh_token"],
                "client_id": CLIENT_ID,
                "client_secret": CLIENT_SECRET,
            },
        )
        self.assertEqual(status, 400, replay_raw[:300])
        status, _, second = self.form_call(
            OAUTH + "/refresh",
            {
                "grant_type": "refresh_token",
                "refresh_token": rotated["refresh_token"],
                "client_id": CLIENT_ID,
                "client_secret": CLIENT_SECRET,
            },
        )
        self.assertEqual(status, 400, second[:200])
        status, _, denied, _ = self.json_call(
            "POST",
            WEBHOOK,
            self.alice("что сейчас", rotated["access_token"]),
            self.bearer_header(rotated["access_token"]),
        )
        self.assertEqual(status, 401, denied)

    def test_pair_fail_cap_counts_failures_not_success(self) -> None:
        code, _jar = self.start_challenge()
        credential, installation_id = self.enroll()
        self.select(credential)
        bucket = "pair:" + installation_id

        def failures() -> int:
            row = self.sql_one(
                "SELECT count FROM rate_buckets WHERE bucket=?",
                (bucket,),
            )
            return 0 if row is None else int(row[0])

        status, _, body, _ = self.json_call(
            "POST",
            PHONE + "/pair-preview",
            {"code": "AAAAAAAAAA"},
            self.install_header(credential),
        )
        self.assertEqual(status, 400, body)
        self.assertEqual(failures(), 1)
        for _ in range(2):
            status, _, body, _ = self.json_call(
                "POST",
                PHONE + "/pair-preview",
                {"code": code},
                self.install_header(credential),
            )
            self.assertEqual(status, 200, body)
            self.assertEqual(set(body), {"scope", "expires_at", "challenge_id"})
        self.assertEqual(failures(), 1)
        for _ in range(2):
            status, _, body, _ = self.json_call(
                "POST",
                PHONE + "/pair-preview",
                {"code": "BBBBBBBBBB"},
                self.install_header(credential),
            )
            self.assertEqual(status, 400, body)
        self.assertEqual(failures(), 3)
        status, _, body, _ = self.json_call(
            "POST",
            PHONE + "/pair-preview",
            {"code": code},
            self.install_header(credential),
        )
        self.assertEqual(status, 429, body)
        self.assertEqual(failures(), 3)

    def test_poll_select_generation_stale_and_restart(self) -> None:
        credential, _installation = self.enroll()
        bake, generation = uid(), uid()
        connection = uid()
        status, _, body, _ = self.poll(credential, connection, generation, bake)
        self.assertEqual(status, 409, body)
        bake, generation = self.select(credential, bake, generation)
        other = uid()
        status, _, body, _ = self.poll(credential, connection, other, bake)
        self.assertEqual(status, 409, body)
        status, _, body, _ = self.poll(credential, connection, generation, bake)
        self.assertEqual(status, 200, body)
        self.assertEqual(set(body), POLL_FIELDS)
        self.assertIs(body["linked"], False)
        self.assertIsNone(body["command"])
        self.assertIs(type(body["server_time"]), int)
        status, _, body, _ = self.poll(
            credential,
            connection,
            generation,
            bake,
            captured_at=int(time.time() * 1000) - 120_000,
        )
        self.assertEqual(status, 200, body)
        self.assertIsNone(body["command"])
        new_generation = uid()
        self.select(credential, bake, new_generation)
        status, _, body, _ = self.poll(credential, connection, generation, bake)
        self.assertEqual(status, 409, body)
        status, _, body, _ = self.poll(credential, uid(), new_generation, bake)
        self.assertEqual(status, 200, body)
        self.assertIs(body["linked"], False)
        self.assertIsNone(body["command"])
        restarted = App(self.settings)
        self.httpd.app = restarted
        status, _, body, _ = self.poll(credential, uid(), new_generation, bake)
        self.assertEqual(status, 200, body)
        self.assertIsNone(body["command"])
        self.assertEqual(self.sql_one("SELECT COUNT(*) FROM leases")[0] >= 1, True)

    def test_receipt_fields_unknown_ack_and_concurrent_progress(self) -> None:
        credential, _installation = self.enroll()
        missing = {
            "connection_id": uid(),
            "selection_generation": uid(),
            "command_id": uid(),
            "bake_key": uid(),
            "expected_revision": 1,
            "status": "STALE",
        }
        status, _, body, _ = self.json_call(
            "POST",
            PHONE + "/ack",
            missing,
            self.install_header(credential),
        )
        self.assertEqual(status, 400, body)
        extra = dict(missing)
        extra["new_revision"] = None
        extra["note"] = "nope"
        status, _, body, _ = self.json_call("POST", PHONE + "/ack", extra, self.install_header(credential))
        self.assertEqual(status, 400, body)
        applied_null = dict(missing)
        applied_null["new_revision"] = None
        applied_null["status"] = "APPLIED"
        status, _, body, _ = self.json_call(
            "POST",
            PHONE + "/ack",
            applied_null,
            self.install_header(credential),
        )
        self.assertEqual(status, 400, body)
        stale = dict(missing)
        stale["new_revision"] = None
        status, _, body, raw = self.json_call("POST", PHONE + "/ack", stale, self.install_header(credential))
        self.assertEqual(status, 200, body)
        self.assertEqual(set(body), {"accepted"})
        self.assertIs(body["accepted"], False)
        self.assertEqual(raw, b'{"accepted":false}')
        status, _, body, _ = self.json_call("POST", PHONE + "/ack", stale, self.bearer_header(credential))
        self.assertEqual(status, 401, body)

        access, bake, generation = self._link(credential)
        connection = uid()
        status, _, polled, _ = self.poll(credential, connection, generation, bake)
        self.assertEqual(status, 200, polled)
        self.assertIs(polled["linked"], True)
        before = self.command_count()
        status, _, turn1, _ = self.webhook("пауза", access)
        self.assertEqual(status, 200, turn1)
        self.assertIn("Скажи да", turn1["response"]["text"])
        self.assertIn("Хлебушек", turn1["response"]["text"])
        self.assertNotIn("Мадре", turn1["response"]["text"])
        self.assertFalse(turn1["response"]["end_session"])
        self.assertEqual(self.command_count(), before)

        late = {}
        holder = threading.Event()

        def receive_then_late_ack() -> None:
            status_p, _, body_p, _ = self.poll(credential, connection, generation, bake)
            late["poll_status"] = status_p
            late["command"] = body_p.get("command") if isinstance(body_p, dict) else None
            holder.set()
            time.sleep(1.2)
            if late["command"]:
                status_a, _, body_a, raw_a = self.ack(credential, late["command"], "STALE", None)
                late["ack_status"] = status_a
                late["ack"] = body_a
                late["ack_raw"] = raw_a

        worker = threading.Thread(target=receive_then_late_ack, daemon=True)
        worker.start()
        time.sleep(0.25)
        status, _, unknown, _ = self.webhook("да", access, timeout=8)
        self.assertEqual(status, 200, unknown)
        self.assertEqual(unknown["response"]["text"], "Результат неизвестен, проверь телефон.")
        worker.join(timeout=4)
        self.assertTrue(holder.is_set())
        self.assertEqual(late.get("poll_status"), 200)
        command = late.get("command")
        self.assertIsInstance(command, dict)
        self.assertEqual(set(command), COMMAND_FIELDS)
        self.assertEqual(command["operation"], "PAUSE")
        self.assertEqual(command["connection_id"], connection)
        self.assertEqual(late.get("ack_status"), 200)
        self.assertEqual(late.get("ack"), {"accepted": True})
        self.assertEqual(late.get("ack_raw"), b'{"accepted":true}')
        stored = self.sql_one(
            "SELECT status, new_revision FROM receipts WHERE command_id=?",
            (command["id"],),
        )
        self.assertEqual(stored[0], "STALE")
        self.assertIsNone(stored[1])
        status, _, again, _ = self.poll(credential, connection, generation, bake)
        self.assertIsNone(again["command"])
        status, _, receipt_speech, _ = self.webhook("что получилось", access)
        self.assertIn("состояние уже другое", receipt_speech["response"]["text"])
        self.assertIn("Повторно не отправляю", receipt_speech["response"]["text"])

        seen = {}

        def poll_and_apply() -> None:
            status_p, _, body_p, _ = self.poll(credential, connection, generation, bake)
            seen["command"] = body_p.get("command") if isinstance(body_p, dict) else None
            if seen["command"]:
                status_a, _, body_a, _ = self.ack(
                    credential,
                    seen["command"],
                    "APPLIED",
                    int(seen["command"]["expected_revision"]) + 1,
                )
                seen["ack_status"] = status_a
                seen["ack"] = body_a

        worker = threading.Thread(target=poll_and_apply, daemon=True)
        worker.start()
        time.sleep(0.25)
        self.webhook("дальше", access)
        status, _, applied, _ = self.webhook("да", access, timeout=8)
        worker.join(timeout=4)
        self.assertEqual(status, 200, applied)
        self.assertEqual(applied["response"]["text"], "Телефон применил команду.")
        self.assertEqual(set(seen["command"]), COMMAND_FIELDS)
        self.assertEqual(seen["command"]["operation"], "ADVANCE")
        self.assertIs(type(seen["command"]["expected_paused"]), bool)
        self.assertEqual(seen["ack"], {"accepted": True})
        self.assertLessEqual(int(seen["command"]["expires_at"]) - int(seen["command"]["issued_at"]), 4000)

        before = self.command_count()
        status, _, last, _ = self.poll(
            credential,
            connection,
            generation,
            bake,
            revision=5,
            step_index=2,
            step_count=3,
            next_step_title=None,
            next_step_text=None,
        )
        self.assertEqual(status, 200, last)
        status, _, speech, _ = self.webhook("дальше", access)
        self.assertIn("Не отправлено", speech["response"]["text"])
        self.assertIn("экране", speech["response"]["text"])
        self.assertEqual(self.command_count(), before)

        for word in ("стоп", "выход", "хватит", "отмена"):
            self.poll(credential, connection, generation, bake, revision=6)
            self.webhook("пауза", access)
            status, _, speech, _ = self.webhook(word, access)
            self.assertTrue(speech["response"]["end_session"], word)
            self.assertIn("не отменяю", speech["response"]["text"])
            status, _, speech, _ = self.webhook("да", access)
            self.assertIn("Не отправлено", speech["response"]["text"])
        self.assertEqual(self.command_count(), before)

        self.poll(credential, connection, generation, bake, revision=6)
        status, _, speech, _ = self.webhook("пауза", access, original="дальше")
        self.assertIn("Не разобрала", speech["response"]["text"])
        status, _, speech, _ = self.webhook("да", access)
        self.assertIn("Не отправлено", speech["response"]["text"])

        self.poll(credential, connection, generation, bake, revision=6)
        self.webhook("отмени выпечку", access)
        before_cancel = self.command_count()
        status, _, speech, _ = self.webhook("да", access)
        self.assertIn("подтверждаю отмену", speech["response"]["text"])
        self.assertEqual(self.command_count(), before_cancel)
        cancel_seen = {}

        def poll_cancel() -> None:
            status_p, _, body_p, _ = self.poll(credential, connection, generation, bake, revision=6)
            cancel_seen["command"] = body_p.get("command") if isinstance(body_p, dict) else None
            if cancel_seen["command"]:
                self.ack(credential, cancel_seen["command"], "APPLIED", int(cancel_seen["command"]["expected_revision"]) + 1)

        worker = threading.Thread(target=poll_cancel, daemon=True)
        worker.start()
        time.sleep(0.25)
        status, _, speech, _ = self.webhook("подтверждаю отмену", access, timeout=8)
        worker.join(timeout=4)
        self.assertEqual(speech["response"]["text"], "Телефон применил команду.")
        self.assertEqual(cancel_seen["command"]["operation"], "CANCEL")

        self.poll(
            credential,
            connection,
            generation,
            bake,
            revision=6,
            captured_at=int(time.time() * 1000) - 120_000,
        )
        before_stale = self.command_count()
        status, _, speech, _ = self.webhook("пауза", access)
        self.assertIn("Не отправлено", speech["response"]["text"])
        self.assertIn("Часы", speech["response"]["text"])
        self.assertEqual(self.command_count(), before_stale)

        self.poll(credential, connection, generation, bake, revision=6)
        self.webhook("пауза", access)
        other = uid()
        confirm_box = {}

        def confirm_pause() -> None:
            status_c, _, speech_c, _ = self.webhook("да", access, timeout=8)
            confirm_box["status"] = status_c
            confirm_box["speech"] = speech_c

        confirmer = threading.Thread(target=confirm_pause, daemon=True)
        confirmer.start()
        deadline = time.time() + 2
        pending = None
        while time.time() < deadline:
            pending = self.sql_one(
                "SELECT id, state FROM commands WHERE connection_id=? AND state IN ('pending','dispatched')",
                (connection,),
            )
            if pending:
                break
            time.sleep(0.05)
        self.assertIsNotNone(pending)
        status, _, replaced, _ = self.poll(credential, other, generation, bake, revision=6)
        self.assertIsNone(replaced["command"])
        killed = self.sql_one("SELECT state FROM commands WHERE id=?", (pending[0],))
        self.assertEqual(killed[0], "killed")
        confirmer.join(timeout=4)
        self.assertEqual(confirm_box["speech"]["response"]["text"], "Результат неизвестен, проверь телефон.")
        status, _, old_poll, _ = self.poll(credential, connection, generation, bake)
        self.assertEqual(status, 200, old_poll)
        self.assertIsNone(old_poll["command"])

        status, _, body, _ = self.json_call(
            "POST",
            PHONE + "/disconnect",
            {},
            self.install_header(credential),
        )
        self.assertEqual(body, {"revoked": True})
        status, _, body, _ = self.json_call(
            "POST",
            PHONE + "/disconnect",
            {},
            self.install_header(credential),
        )
        self.assertEqual(status, 200)
        self.assertEqual(body, {"revoked": True})
        status, _, denied, _ = self.webhook("что сейчас", access)
        self.assertEqual(status, 401, denied)

    def _link(self, credential: str) -> tuple[str, str, str]:
        code, jar = self.start_challenge()
        bake, generation = self.select(credential)
        status, _, preview, _ = self.json_call(
            "POST",
            PHONE + "/pair-preview",
            {"code": code},
            self.install_header(credential),
        )
        self.assertEqual(status, 200, preview)
        status, _, approved, _ = self.json_call(
            "POST",
            PHONE + "/pair-approve",
            {
                "challenge_id": preview["challenge_id"],
                "code": code,
                "bake_key": bake,
                "selection_generation": generation,
            },
            self.install_header(credential),
        )
        self.assertEqual(approved, {"approved": True})
        status, headers, _page = self.form_call(
            OAUTH + "/confirm",
            {"csrf": jar["madre_csrf"]},
            {"Cookie": self.cookie_header(jar)},
        )
        self.assertEqual(status, 303, headers)
        location = self.header_map(headers)["location"][0]
        auth_code = parse_qs(urlsplit(location).query)["code"][0]
        status, _, raw = self.form_call(
            OAUTH + "/token",
            {
                "grant_type": "authorization_code",
                "code": auth_code,
                "redirect_uri": EXACT_REDIRECT,
                "client_id": CLIENT_ID,
                "client_secret": CLIENT_SECRET,
            },
        )[:3]
        self.assertEqual(status, 200, raw[:300])
        token = json.loads(raw.decode("utf-8"))
        return token["access_token"], bake, generation

    def spawn(self, **over):
        tmp = tempfile.TemporaryDirectory(prefix="madre-alice-iso-")
        self.addCleanup(tmp.cleanup)
        fields = dict(
            db_path=os.path.join(tmp.name, "voice.sqlite"),
            oauth_client_id=CLIENT_ID,
            oauth_client_secret=CLIENT_SECRET,
            poll_wait_s=0.3,
            ack_wait_s=4.0,
            request_budget_s=4.5,
            pair_fail_limit=3,
            enroll_limit=100,
            oauth_limit=100,
        )
        fields.update(over)
        app = App(Settings(**fields))
        httpd = make_server("127.0.0.1", 0, app)
        thread = threading.Thread(target=httpd.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        thread.start()
        self.addCleanup(httpd.shutdown)
        self.addCleanup(httpd.server_close)
        return app, int(httpd.server_address[1]), fields["db_path"]

    def test_f1_health_routes_and_semicolon_is_400(self) -> None:
        status, _, body = self.request("GET", "/health")
        self.assertEqual(status, 200, body)
        self.assertEqual(body, b'{"ok":true}')
        status, _, body = self.request("HEAD", "/alice/madre/health")
        self.assertEqual(status, 200, body)
        status, _, body = self.request("GET", "/health;jsessionid=1")
        self.assertEqual(status, 400, body)
        self.assertNotEqual(status, 500)
        status, _, body = self.request("POST", PHONE + "/enroll;x", b"{}", {"Content-Type": "application/json"})
        self.assertEqual(status, 400, body)
        credential, installation_id = self.enroll()
        self.assertTrue(installation_id)

    def test_f2_rate_window_not_purged_before_count(self) -> None:
        app, port, db_path = self.spawn(enroll_limit=2, oauth_limit=2)

        def enroll_once() -> int:
            cred = new_installation_credential()
            status, _, body, _ = self.json_call(
                "POST",
                PHONE + "/enroll",
                {"credential": cred},
                self.install_header(cred),
                port=port,
            )
            self.assertIn(status, (200, 429), body)
            return status

        self.assertEqual(enroll_once(), 200)
        self.assertEqual(enroll_once(), 200)
        self.assertEqual(enroll_once(), 429)
        self.assertEqual(enroll_once(), 429)
        now = app.now()
        conn = sqlite3.connect(db_path)
        try:
            row = conn.execute(
                "SELECT window, count FROM rate_buckets WHERE bucket LIKE 'enroll:%'"
            ).fetchone()
        finally:
            conn.close()
        self.assertIsNotNone(row)
        self.assertGreaterEqual(int(row[0]), (now // 60_000) - 1)
        self.assertGreater(int(row[0]), (now // 60_000) - 120)
        self.assertGreaterEqual(int(row[1]), 3)

        def authorize_once():
            status, headers, page = self.request(
                "GET",
                OAUTH + "/authorize?" + self.authorize_query(),
                port=port,
            )
            self.assertEqual(status, 200, page[:160])
            csrf = self.csrf_from(page)
            jar = self.cookies(headers)
            return self.form_call(
                OAUTH + "/authorize",
                {
                    "csrf": csrf,
                    "client_id": CLIENT_ID,
                    "redirect_uri": EXACT_REDIRECT,
                    "response_type": "code",
                    "scope": EXACT_SCOPE,
                    "state": STATE,
                },
                {"Cookie": self.cookie_header({"madre_csrf": jar["madre_csrf"]})},
                port=port,
            )

        first, _, page = authorize_once()
        self.assertEqual(first, 200, page[:160])
        second, _, page = authorize_once()
        self.assertEqual(second, 200, page[:160])
        third, _, page = authorize_once()
        self.assertEqual(third, 429, page[:160])

    def test_f3_revoked_grant_does_not_deliver_queued_command(self) -> None:
        credential, _installation = self.enroll()
        code, jar = self.start_challenge()
        bake, generation = self.select(credential)
        status, _, preview, _ = self.json_call(
            "POST",
            PHONE + "/pair-preview",
            {"code": code},
            self.install_header(credential),
        )
        self.assertEqual(status, 200, preview)
        status, _, approved, _ = self.json_call(
            "POST",
            PHONE + "/pair-approve",
            {
                "challenge_id": preview["challenge_id"],
                "code": code,
                "bake_key": bake,
                "selection_generation": generation,
            },
            self.install_header(credential),
        )
        self.assertEqual(approved, {"approved": True})
        status, headers, _page = self.form_call(
            OAUTH + "/confirm",
            {"csrf": jar["madre_csrf"]},
            {"Cookie": self.cookie_header(jar)},
        )
        self.assertEqual(status, 303)
        auth_code = parse_qs(urlsplit(self.header_map(headers)["location"][0]).query)["code"][0]
        status, _, raw = self.form_call(
            OAUTH + "/token",
            {
                "grant_type": "authorization_code",
                "code": auth_code,
                "redirect_uri": EXACT_REDIRECT,
                "client_id": CLIENT_ID,
                "client_secret": CLIENT_SECRET,
            },
        )
        self.assertEqual(status, 200, raw[:200])
        token = json.loads(raw.decode("utf-8"))
        access = token["access_token"]
        connection = uid()
        status, _, polled, _ = self.poll(credential, connection, generation, bake)
        self.assertEqual(status, 200, polled)
        self.webhook("пауза", access)
        box: dict = {}

        def confirm() -> None:
            status_c, _, speech, _ = self.webhook("да", access, timeout=8)
            box["status"] = status_c
            box["speech"] = speech

        worker = threading.Thread(target=confirm, daemon=True)
        worker.start()
        pending = None
        deadline = time.time() + 2
        while time.time() < deadline:
            pending = self.sql_one(
                "SELECT id, state FROM commands WHERE connection_id=? AND state='pending'",
                (connection,),
            )
            if pending:
                break
            time.sleep(0.05)
        self.assertIsNotNone(pending)
        status, _, rotated_raw = self.form_call(
            OAUTH + "/token",
            {
                "grant_type": "refresh_token",
                "refresh_token": token["refresh_token"],
                "client_id": CLIENT_ID,
                "client_secret": CLIENT_SECRET,
            },
        )
        self.assertEqual(status, 200, rotated_raw[:200])
        status, _, replay = self.form_call(
            OAUTH + "/refresh",
            {
                "grant_type": "refresh_token",
                "refresh_token": token["refresh_token"],
                "client_id": CLIENT_ID,
                "client_secret": CLIENT_SECRET,
            },
        )
        self.assertEqual(status, 400, replay[:200])
        status, _, delivered, _ = self.poll(credential, connection, generation, bake)
        self.assertEqual(status, 200, delivered)
        self.assertIsNone(delivered["command"])
        state = self.sql_one("SELECT state FROM commands WHERE id=?", (pending[0],))
        self.assertEqual(state[0], "killed")
        worker.join(timeout=6)
        self.assertNotIn("применил", box["speech"]["response"]["text"])
        status, _, denied, _ = self.webhook("что сейчас", access)
        self.assertEqual(status, 401, denied)

        credential_b, _ = self.enroll()
        code_b, jar_b = self.start_challenge()
        bake_b, generation_b = self.select(credential_b)
        status, _, preview_b, _ = self.json_call(
            "POST",
            PHONE + "/pair-preview",
            {"code": code_b},
            self.install_header(credential_b),
        )
        self.assertEqual(status, 200, preview_b)
        self.json_call(
            "POST",
            PHONE + "/pair-approve",
            {
                "challenge_id": preview_b["challenge_id"],
                "code": code_b,
                "bake_key": bake_b,
                "selection_generation": generation_b,
            },
            self.install_header(credential_b),
        )
        status, headers_b, _page = self.form_call(
            OAUTH + "/confirm",
            {"csrf": jar_b["madre_csrf"]},
            {"Cookie": self.cookie_header(jar_b)},
        )
        self.assertEqual(status, 303)
        auth_b = parse_qs(urlsplit(self.header_map(headers_b)["location"][0]).query)["code"][0]
        status, _, raw_b = self.form_call(
            OAUTH + "/token",
            {
                "grant_type": "authorization_code",
                "code": auth_b,
                "redirect_uri": EXACT_REDIRECT,
                "client_id": CLIENT_ID,
                "client_secret": CLIENT_SECRET,
            },
        )
        token_b = json.loads(raw_b.decode("utf-8"))
        connection_b = uid()
        self.poll(credential_b, connection_b, generation_b, bake_b)
        self.webhook("пауза", token_b["access_token"])
        box_b: dict = {}

        def confirm_b() -> None:
            _status, _, speech, _ = self.webhook("да", token_b["access_token"], timeout=8)
            box_b["speech"] = speech

        worker_b = threading.Thread(target=confirm_b, daemon=True)
        worker_b.start()
        pending_b = None
        deadline = time.time() + 2
        while time.time() < deadline:
            pending_b = self.sql_one(
                "SELECT id FROM commands WHERE connection_id=? AND state='pending'",
                (connection_b,),
            )
            if pending_b:
                break
            time.sleep(0.05)
        self.assertIsNotNone(pending_b)
        status, _, replay_b = self.form_call(
            OAUTH + "/token",
            {
                "grant_type": "authorization_code",
                "code": auth_b,
                "redirect_uri": EXACT_REDIRECT,
                "client_id": CLIENT_ID,
                "client_secret": CLIENT_SECRET,
            },
        )
        self.assertEqual(status, 400, replay_b[:200])
        status, _, delivered_b, _ = self.poll(credential_b, connection_b, generation_b, bake_b)
        self.assertIsNone(delivered_b["command"])
        self.assertEqual(
            self.sql_one("SELECT state FROM commands WHERE id=?", (pending_b[0],))[0],
            "killed",
        )
        worker_b.join(timeout=6)
        self.assertNotIn("применил", box_b["speech"]["response"]["text"])

    def test_f3_expired_grant_does_not_dispatch(self) -> None:
        app, port, _db = self.spawn(
            grant_ttl_ms=12_000,
            command_ttl_ms=120_000,
            ack_wait_s=6.0,
            poll_wait_s=0.2,
            freshness_ms=60_000,
            skew_ms=120_000,
        )
        cred = new_installation_credential()
        status, _, body, _ = self.json_call(
            "POST",
            PHONE + "/enroll",
            {"credential": cred},
            self.install_header(cred),
            port=port,
        )
        self.assertEqual(status, 200, body)
        bake, generation = uid(), uid()
        status, _, selected, _ = self.json_call(
            "POST",
            PHONE + "/select",
            {"bake_key": bake, "selection_generation": generation},
            self.install_header(cred),
            port=port,
        )
        self.assertEqual(selected, {"selected": True})
        status, headers, page = self.request("GET", OAUTH + "/authorize?" + self.authorize_query(), port=port)
        self.assertEqual(status, 200, page[:160])
        csrf = self.csrf_from(page)
        jar = self.cookies(headers)
        status, headers, page = self.form_call(
            OAUTH + "/authorize",
            {
                "csrf": csrf,
                "client_id": CLIENT_ID,
                "redirect_uri": EXACT_REDIRECT,
                "response_type": "code",
                "scope": EXACT_SCOPE,
                "state": STATE,
            },
            {"Cookie": self.cookie_header({"madre_csrf": jar["madre_csrf"]})},
            port=port,
        )
        self.assertEqual(status, 200, page[:200])
        code = self.display_code_from(page)
        jar = self.cookies(headers)
        status, _, preview, _ = self.json_call(
            "POST",
            PHONE + "/pair-preview",
            {"code": code},
            self.install_header(cred),
            port=port,
        )
        self.assertEqual(status, 200, preview)
        status, _, approved, _ = self.json_call(
            "POST",
            PHONE + "/pair-approve",
            {
                "challenge_id": preview["challenge_id"],
                "code": code,
                "bake_key": bake,
                "selection_generation": generation,
            },
            self.install_header(cred),
            port=port,
        )
        self.assertEqual(approved, {"approved": True})
        status, headers, _page = self.form_call(
            OAUTH + "/confirm",
            {"csrf": jar["madre_csrf"]},
            {"Cookie": self.cookie_header(jar)},
            port=port,
        )
        self.assertEqual(status, 303)
        auth_code = parse_qs(urlsplit(self.header_map(headers)["location"][0]).query)["code"][0]
        status, _, raw = self.form_call(
            OAUTH + "/token",
            {
                "grant_type": "authorization_code",
                "code": auth_code,
                "redirect_uri": EXACT_REDIRECT,
                "client_id": CLIENT_ID,
                "client_secret": CLIENT_SECRET,
            },
            port=port,
        )
        self.assertEqual(status, 200, raw[:200])
        access = json.loads(raw.decode("utf-8"))["access_token"]
        connection = uid()
        status, _, polled, _ = self.json_call(
            "POST",
            PHONE + "/poll",
            {
                "connection_id": connection,
                "selection_generation": generation,
                "snapshot": self.snapshot(bake),
            },
            self.install_header(cred),
            port=port,
        )
        self.assertEqual(status, 200, polled)
        status, _, turn1, _ = self.json_call(
            "POST",
            WEBHOOK,
            self.alice("пауза", access),
            self.bearer_header(access),
            port=port,
        )
        self.assertIn("Скажи да", turn1["response"]["text"])
        box: dict = {}

        def confirm() -> None:
            status_c, _, speech, _ = self.json_call(
                "POST",
                WEBHOOK,
                self.alice("да", access),
                self.bearer_header(access),
                timeout=8,
                port=port,
            )
            box["speech"] = speech
            box["status"] = status_c

        worker = threading.Thread(target=confirm, daemon=True)
        worker.start()
        deadline = time.time() + 2
        pending = None
        conn = sqlite3.connect(app.settings.db_path)
        try:
            while time.time() < deadline:
                pending = conn.execute(
                    "SELECT id, state FROM commands WHERE connection_id=? AND state='pending'",
                    (connection,),
                ).fetchone()
                if pending:
                    break
                time.sleep(0.05)
        finally:
            conn.close()
        self.assertIsNotNone(pending)
        app.clock.advance_ms(20_000)
        status, _, delivered, _ = self.json_call(
            "POST",
            PHONE + "/poll",
            {
                "connection_id": connection,
                "selection_generation": generation,
                "snapshot": self.snapshot(bake),
            },
            self.install_header(cred),
            port=port,
            timeout=8,
        )
        self.assertEqual(status, 200, delivered)
        self.assertIsNone(delivered["command"])
        conn = sqlite3.connect(app.settings.db_path)
        try:
            state = conn.execute("SELECT state FROM commands WHERE id=?", (pending[0],)).fetchone()
        finally:
            conn.close()
        self.assertEqual(state[0], "killed")
        worker.join(timeout=8)
        self.assertNotIn("применил", box["speech"]["response"]["text"])

    def test_f4_retired_and_stale_snapshot_does_not_restore(self) -> None:
        credential, installation_id = self.enroll()
        bake, generation = self.select(credential)
        current = uid()
        status, _, body, _ = self.poll(credential, current, generation, bake, revision=6)
        self.assertEqual(status, 200, body)
        stale = uid()
        status, _, body, _ = self.poll(credential, stale, generation, bake, revision=5)
        self.assertEqual(status, 409, body)
        status, _, body, _ = self.poll(credential, stale, generation, bake, revision=6, paused=True)
        self.assertEqual(status, 409, body)
        status, _, body, _ = self.poll(credential, current, generation, bake, revision=5)
        self.assertEqual(status, 409, body)
        nxt = uid()
        status, _, body, _ = self.poll(credential, nxt, generation, bake, revision=7)
        self.assertEqual(status, 200, body)
        self.assertIsNone(body["command"])
        status, _, rolled, _ = self.poll(credential, current, generation, bake, revision=8, paused=True)
        self.assertEqual(status, 200, rolled)
        self.assertIsNone(rolled["command"])
        lease = self.sql_one(
            "SELECT connection_id FROM leases WHERE installation_id=?",
            (installation_id,),
        )
        snap = json.loads(
            self.sql_one(
                "SELECT payload_json FROM snapshots WHERE installation_id=?",
                (installation_id,),
            )[0]
        )
        self.assertEqual(lease[0], nxt)
        self.assertEqual(snap["revision"], 7)
        self.assertIs(snap["paused"], False)
        self.assertEqual(
            self.sql_one(
                "SELECT 1 FROM retired_connections WHERE installation_id=? AND connection_id=?",
                (installation_id, current),
            )[0],
            1,
        )

    def test_f5_receipt_scoped_to_bake_generation_and_ttl(self) -> None:
        credential, _installation = self.enroll()
        access, bake, generation = self._link(credential)
        connection = uid()
        self.poll(credential, connection, generation, bake)
        seen: dict = {}

        def apply() -> None:
            status_p, _, body_p, _ = self.poll(credential, connection, generation, bake)
            command = body_p.get("command") if isinstance(body_p, dict) else None
            seen["command"] = command
            if command:
                self.ack(credential, command, "APPLIED", int(command["expected_revision"]) + 1)

        self.webhook("пауза", access)
        worker = threading.Thread(target=apply, daemon=True)
        worker.start()
        time.sleep(0.2)
        status, _, speech, _ = self.webhook("да", access, timeout=8)
        worker.join(timeout=4)
        self.assertEqual(speech["response"]["text"], "Телефон применил команду.")
        status, _, spoken, _ = self.webhook("что получилось", access)
        self.assertIn("применил", spoken["response"]["text"])
        old_id = seen["command"]["id"]
        access_b, new_bake, new_generation = self._link(credential)
        conn_b = uid()
        self.poll(credential, conn_b, new_generation, new_bake)
        status, _, spoken_b, _ = self.webhook("что получилось", access_b)
        self.assertNotIn("применил", spoken_b["response"]["text"])
        self.assertIn("Сохранённого ответа", spoken_b["response"]["text"])
        self.assertEqual(
            self.sql_one("SELECT status FROM receipts WHERE command_id=?", (old_id,))[0],
            "APPLIED",
        )
        self.webhook("пауза", access_b)
        seen_b: dict = {}

        def apply_b() -> None:
            _status, _, body_p, _ = self.poll(credential, conn_b, new_generation, new_bake)
            command = body_p.get("command") if isinstance(body_p, dict) else None
            seen_b["command"] = command
            if command:
                self.ack(credential, command, "APPLIED", int(command["expected_revision"]) + 1)

        worker = threading.Thread(target=apply_b, daemon=True)
        worker.start()
        time.sleep(0.2)
        self.webhook("да", access_b, timeout=8)
        worker.join(timeout=4)
        self.assertIsNotNone(seen_b.get("command"))
        aged = self.app.now() - self.app.settings.receipt_ttl_ms - 1000
        conn = sqlite3.connect(self.db_path)
        try:
            conn.execute(
                "UPDATE receipts SET received_at=? WHERE command_id=?",
                (aged, seen_b["command"]["id"]),
            )
            conn.commit()
        finally:
            conn.close()
        status, _, expired_speech, _ = self.webhook("что получилось", access_b)
        self.assertNotIn("применил", expired_speech["response"]["text"])
        self.assertIn("Сохранённого ответа", expired_speech["response"]["text"])

    def test_f6_waiters_reclaimed_and_wakeup_still_delivers(self) -> None:
        credential, _installation = self.enroll()
        bake, generation = self.select(credential)
        connection = uid()
        held = threading.Event()

        def blocking() -> None:
            held.set()
            self.poll(credential, connection, generation, bake)

        worker = threading.Thread(target=blocking, daemon=True)
        worker.start()
        self.assertTrue(held.wait(1))
        time.sleep(0.15)
        self.assertGreaterEqual(self.app.waiters.sizes()["poll"], 1)
        worker.join(timeout=4)
        self.assertEqual(self.app.waiters.sizes()["poll"], 0)

        access, bake, generation = self._link(credential)
        connection = uid()
        seen: dict = {}

        def poll_and_ack() -> None:
            started = time.monotonic()
            status_p, _, body_p, _ = self.poll(credential, connection, generation, bake)
            seen["elapsed"] = time.monotonic() - started
            seen["status"] = status_p
            command = body_p.get("command") if isinstance(body_p, dict) else None
            seen["command"] = command
            if command:
                self.ack(credential, command, "APPLIED", int(command["expected_revision"]) + 1)

        worker = threading.Thread(target=poll_and_ack, daemon=True)
        worker.start()
        time.sleep(0.25)
        self.webhook("пауза", access)
        status, _, speech, _ = self.webhook("да", access, timeout=8)
        worker.join(timeout=4)
        self.assertEqual(speech["response"]["text"], "Телефон применил команду.")
        self.assertLess(seen["elapsed"], self.app.settings.poll_wait_s)
        self.assertEqual(seen["command"]["operation"], "PAUSE")
        self.assertEqual(self.app.waiters.sizes(), {"poll": 0, "ack": 0})

    def test_readonly_state_does_not_mint(self) -> None:
        credential, _installation = self.enroll()
        access, bake, generation = self._link(credential)
        connection = uid()
        self.poll(credential, connection, generation, bake, remaining_seconds=90, paused=True)
        before = self.command_count()
        status, _, what, _ = self.webhook("что готовится", access)
        self.assertEqual(status, 200, what)
        self.assertIn("Хлебушек", what["response"]["text"])
        self.assertIn("паузе", what["response"]["text"])
        status, _, now_step, _ = self.webhook("что сейчас", access)
        self.assertIn("Опара", now_step["response"]["text"])
        self.assertNotIn("отправлено", now_step["response"]["text"].lower())
        status, _, peek, _ = self.webhook("что дальше", access)
        self.assertIn("не двигаю", peek["response"]["text"])
        status, _, left, _ = self.webhook("сколько осталось", access)
        self.assertIn("Пауза", left["response"]["text"])
        status, _, qty, _ = self.webhook("граммовки", access)
        self.assertIn("610", qty["response"]["text"])
        self.assertEqual(self.command_count(), before)

    def test_cross_install_cannot_take_or_ack(self) -> None:
        cred_a, _ = self.enroll()
        access_a, bake_a, gen_a = self._link(cred_a)
        cred_b, _ = self.enroll()
        access_b, bake_b, gen_b = self._link(cred_b)
        conn_a = uid()
        conn_b = uid()
        self.poll(cred_a, conn_a, gen_a, bake_a, recipe_name="Хлебушек")
        self.poll(cred_b, conn_b, gen_b, bake_b, recipe_name="Ржаной край")
        status, _, speech_b, _ = self.webhook("что сейчас", access_b)
        self.assertIn("Ржаной край", speech_b["response"]["text"])
        self.assertNotIn("Хлебушек", speech_b["response"]["text"])
        self.webhook("пауза", access_a)
        box: dict = {}

        def confirm() -> None:
            _status, _, speech, _ = self.webhook("да", access_a, timeout=8)
            box["speech"] = speech

        worker = threading.Thread(target=confirm, daemon=True)
        worker.start()
        pending = None
        deadline = time.time() + 2
        while time.time() < deadline:
            pending = self.sql_one(
                "SELECT id, expected_revision, bake_key, selection_generation FROM commands WHERE connection_id=? AND state='pending'",
                (conn_a,),
            )
            if pending:
                break
            time.sleep(0.05)
        self.assertIsNotNone(pending)
        status, _, stolen, _ = self.poll(cred_b, conn_b, gen_b, bake_b, recipe_name="Ржаной край")
        self.assertIsNone(stolen["command"])
        status, _, ack, _ = self.json_call(
            "POST",
            PHONE + "/ack",
            {
                "connection_id": conn_a,
                "selection_generation": gen_a,
                "command_id": pending[0],
                "bake_key": pending[2],
                "expected_revision": pending[1],
                "status": "APPLIED",
                "new_revision": int(pending[1]) + 1,
            },
            self.install_header(cred_b),
        )
        self.assertEqual(status, 200, ack)
        self.assertIs(ack["accepted"], False)
        self.assertNotEqual(
            self.sql_one("SELECT state FROM commands WHERE id=?", (pending[0],))[0],
            "acked",
        )
        self.assertIsNone(self.sql_one("SELECT status FROM receipts WHERE command_id=?", (pending[0],)))
        worker.join(timeout=6)

    def test_lifecycle_select_change_revokes_grant(self) -> None:
        credential, _installation = self.enroll()
        access, bake, generation = self._link(credential)
        connection = uid()
        status, _, body, _ = self.poll(credential, connection, generation, bake)
        self.assertIs(body["linked"], True)
        new_generation = uid()
        self.select(credential, bake, new_generation)
        status, _, denied, _ = self.webhook("что сейчас", access)
        self.assertEqual(status, 401, denied)
        status, _, old, _ = self.poll(credential, connection, generation, bake)
        self.assertEqual(status, 409, old)
        status, _, fresh, _ = self.poll(credential, uid(), new_generation, bake)
        self.assertEqual(status, 200, fresh)
        self.assertIs(fresh["linked"], False)
        self.assertIsNone(fresh["command"])
        status, _, again, _ = self.json_call(
            "POST",
            PHONE + "/disconnect",
            {},
            self.install_header(credential),
        )
        self.assertEqual(again, {"revoked": True})
        status, _, again, _ = self.json_call(
            "POST",
            PHONE + "/disconnect",
            {},
            self.install_header(credential),
        )
        self.assertEqual(status, 200)
        self.assertEqual(again, {"revoked": True})

    def test_f4_same_connection_equal_revision_contradiction(self) -> None:
        credential, installation_id = self.enroll()
        bake, generation = self.select(credential)
        connection = uid()
        status, _, body, _ = self.poll(credential, connection, generation, bake, revision=4, remaining_seconds=600)
        self.assertEqual(status, 200, body)
        captured = int(time.time() * 1000) - 1000
        status, _, beat, _ = self.poll(
            credential,
            connection,
            generation,
            bake,
            revision=4,
            remaining_seconds=120,
            captured_at=captured,
        )
        self.assertEqual(status, 200, beat)
        durable = self.sql_one(
            "SELECT payload_json, connection_id FROM snapshots WHERE installation_id=?",
            (installation_id,),
        )
        snap = json.loads(durable[0])
        self.assertEqual(snap["revision"], 4)
        self.assertIs(snap["paused"], False)
        self.assertEqual(snap["step_index"], 0)
        self.assertEqual(snap["remaining_seconds"], 120)
        self.assertEqual(snap["captured_at"], captured)
        lease_before = self.sql_one(
            "SELECT connection_id FROM leases WHERE installation_id=?",
            (installation_id,),
        )
        status, _, denied, _ = self.poll(credential, connection, generation, bake, revision=4, paused=True)
        self.assertEqual(status, 409, denied)
        self.assertEqual(
            self.sql_one(
                "SELECT payload_json, connection_id FROM snapshots WHERE installation_id=?",
                (installation_id,),
            ),
            durable,
        )
        self.assertEqual(
            self.sql_one("SELECT connection_id FROM leases WHERE installation_id=?", (installation_id,)),
            lease_before,
        )
        status, _, higher, _ = self.poll(credential, connection, generation, bake, revision=5, paused=True)
        self.assertEqual(status, 200, higher)
        stored = json.loads(
            self.sql_one("SELECT payload_json FROM snapshots WHERE installation_id=?", (installation_id,))[0]
        )
        self.assertEqual(stored["revision"], 5)
        self.assertIs(stored["paused"], True)
        self.assertEqual(stored["step_index"], 0)

    def _settle_inflight(self, app: App, timeout: float = 2.0) -> None:
        # Bytes are sent before _serve finally releases the permit. With
        # max_inflight=1 the next connection can see 503 until that finally
        # runs. Wait on the permit itself; do not sleep or drop assertions.
        acquired = app.slots.acquire(timeout=timeout)
        try:
            self.assertTrue(acquired, "inflight slot was not released")
        finally:
            if acquired:
                app.slots.release()

    def _health_status(self, port: int) -> int:
        status, _, body = self.request("GET", "/health", port=port, timeout=2)
        if status == 200:
            self.assertEqual(body, b'{"ok":true}')
        return status

    def _hold_until(self, port: int, mode: str, stop: threading.Event, ready: threading.Event) -> None:
        sock = socket.create_connection(("127.0.0.1", port), timeout=2)
        sock.settimeout(0.2)
        ready.set()
        try:
            if mode == "idle_body":
                sock.sendall(b"POST /health HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 64\r\n\r\n")
                stop.wait(3)
            elif mode == "trickle":
                blob = b"POST /health HTTP/1.1\r\nX-Slow: " + (b"a" * 500)
                for byte in blob:
                    if stop.is_set():
                        return
                    sock.send(bytes([byte]))
                    time.sleep(0.04)
            else:
                stop.wait(3)
        except OSError:
            return
        finally:
            try:
                sock.close()
            except OSError:
                pass

    def _assert_deadline_releases(self, port: int, deadline: float, mode: str) -> None:
        stop = threading.Event()
        ready = threading.Event()
        started = time.monotonic()
        worker = threading.Thread(target=self._hold_until, args=(port, mode, stop, ready), daemon=True)
        worker.start()
        self.assertTrue(ready.wait(1), mode)
        busy = False
        while time.monotonic() - started < 0.4:
            if self._health_status(port) == 503:
                time.sleep(0.08)
                if self._health_status(port) == 503:
                    busy = True
                    break
            time.sleep(0.02)
        self.assertTrue(busy, mode)
        released = None
        while time.monotonic() - started < deadline + 1.2:
            if self._health_status(port) == 200:
                released = time.monotonic()
                break
            time.sleep(0.04)
        stop.set()
        worker.join(timeout=1)
        self.assertIsNotNone(released, mode)
        assert released is not None
        self.assertLess(released - started, deadline + 1.2, mode)

    def test_request_deadline_releases_slot_without_proxy(self) -> None:
        self.assertGreater(REQUEST_DEADLINE_S, REQUEST_BUDGET_S)
        self.assertGreater(REQUEST_DEADLINE_S, POLL_WAIT_S)
        self.assertGreater(self.settings.request_deadline_s, self.settings.request_budget_s)
        self.assertGreater(self.settings.request_deadline_s, self.settings.poll_wait_s)
        deadline = 0.85
        _app, port, _db = self.spawn(max_inflight=1, request_deadline_s=deadline, poll_wait_s=0.05, ack_wait_s=0.05)
        for mode in ("idle_headers", "idle_body", "trickle"):
            self._settle_inflight(_app)
            self._assert_deadline_releases(port, deadline, mode)
        for _ in range(8):
            self._settle_inflight(_app)
            self.assertEqual(self._health_status(port), 200)


if __name__ == "__main__":
    unittest.main()
