"""Synthetic LOCAL HTTP/SQLite acceptance; only external Yandex is mocked.

No provider, broker, production DB, Android, or Station is contacted. The real
repository, Handler, token redemption, refresh rotation/revocation run unchanged.
"""
import base64
import hashlib
import io
import json
import logging
import sqlite3
import tempfile
import threading
import time
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch
from urllib.parse import parse_qs, urlencode, urlsplit

import test_http_integration as legacy
from app import App, make_server
from config import Settings, EXACT_REDIRECT, EXACT_SCOPE
from db import init_db
from security import new_installation_credential
from yandex_identity import CALLBACK, MAX_RESPONSE, YandexUnavailable, _json_request

PHONE, OAUTH = legacy.PHONE, legacy.OAUTH
TOKEN = "synthetic-Yandex-token-NOT-REAL"
LOGIN, NAME, AVATAR, PSUID = "private_login_marker", "Private Display Marker", "avatar_marker", "psuid_marker"
UID = "918273645001"


class ExternalYandex:
    """A fake *external* HTTPS peer, not a fake local repository/API."""
    def __init__(self):
        self.info = {"id": UID, "client_id": "our-yandex-client", "login": LOGIN,
                     "display_name": NAME, "default_avatar_id": AVATAR,
                     "is_avatar_empty": False, "psuid": PSUID}
        self.status = 200
        self.raw = None
        self.encoding = None
        self.challenge = None
        self.calls = []
        self.token_error = False
        self.block_info = None

    def connection(self, host, **kwargs):
        peer = self
        class Connection:
            sock = None
            def request(self, method, path, body=None, headers=None):
                peer.calls.append((host, method, path, body, headers))
                if peer.block_info is not None and host == "login.yandex.ru":
                    peer.block_info[0].set()
                    peer.block_info[1].wait(1)
                if host == "oauth.yandex.ru":
                    assert method == "POST" and path == "/token"
                    fields = parse_qs(body.decode())
                    assert fields["client_id"] == ["our-yandex-client"]
                    assert fields["client_secret"] == ["our-yandex-secret"]
                    verifier = fields["code_verifier"][0]
                    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
                    self.bad = peer.token_error or challenge != peer.challenge
                else:
                    assert host == "login.yandex.ru" and method == "GET" and path == "/info"
                    assert headers["Authorization"] == "OAuth " + TOKEN
                    self.bad = False
            def getresponse(self):
                self.status = 400 if self.bad else peer.status
                return self
            def getheader(self, key, default=None):
                if key == "Content-Encoding":
                    return peer.encoding or default
                return default
            def read(self, maximum):
                if peer.raw is not None:
                    return peer.raw[:maximum]
                return json.dumps({"access_token": TOKEN, "token_type": "bearer"} if host == "oauth.yandex.ru" else peer.info).encode()[:maximum]
            def close(self):
                pass
        return Connection()


class YandexHTTP(unittest.TestCase):
    # Reuse test client helpers, never the legacy tests or repository mocks.
    for _name in ("request", "json_call", "form_call", "install_header", "bearer_header", "enroll", "select",
                  "snapshot", "poll", "webhook", "alice", "header_map", "cookies", "cookie_header",
                  "authorize_query", "csrf_from", "display_code_from", "start_challenge", "sql_one", "_link"):
        locals()[_name] = getattr(legacy.HttpFixture, _name)

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="madre-yandex-")
        self.db_path = str(Path(self.tmp.name) / "state" / "voice.sqlite")
        self.settings = Settings(db_path=self.db_path, oauth_client_id=legacy.CLIENT_ID,
            oauth_client_secret=legacy.CLIENT_SECRET, yandex_client_id="our-yandex-client",
            yandex_client_secret="our-yandex-secret", yandex_uid_salt="synthetic-stable-salt-at-least-32bytes",
            poll_wait_s=0.01, oauth_limit=100)
        self.app = App(self.settings)
        self.httpd = make_server("127.0.0.1", 0, self.app)
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
        self.thread.start()
        self.peer = ExternalYandex()
        self.peer_patch = patch("yandex_identity.http.client.HTTPSConnection", self.peer.connection)
        self.peer_patch.start()
        self.logs = io.StringIO()
        self.log_handler = logging.StreamHandler(self.logs)
        self.logger = logging.getLogger("madre-alice")
        self.level = self.logger.level
        self.logger.setLevel(logging.INFO)
        self.logger.addHandler(self.log_handler)

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(2)
        self.peer_patch.stop()
        self.logger.removeHandler(self.log_handler)
        self.logger.setLevel(self.level)
        # Full SQL dump AND every SQLite file, including WAL, plus captured logs.
        with sqlite3.connect(self.db_path) as conn:
            dump = "\n".join(conn.iterdump()).encode()
        blobs = [dump, self.logs.getvalue().encode()]
        blobs += [p.read_bytes() for p in Path(self.db_path).parent.iterdir() if p.is_file()]
        for blob in blobs:
            for private in (TOKEN, LOGIN, NAME, AVATAR, PSUID, "our-yandex-secret"):
                self.assertNotIn(private.encode(), blob)
        self.assertNotIn(UID, self.logs.getvalue())
        self.tmp.cleanup()

    def bind(self, credential):
        return self.json_call("POST", PHONE + "/yandex-bind", {"yandex_token": TOKEN}, self.install_header(credential))

    def unbind(self, credential):
        return self.json_call("POST", PHONE + "/yandex-unbind", {}, self.install_header(credential))

    def start_auto(self, **over):
        self.app.settings = replace(self.settings, yandex_match=True)
        status, headers, body = self.request("GET", OAUTH + "/authorize?" + self.authorize_query(**over))
        self.assertEqual(status, 302, body)
        location = self.header_map(headers)["location"][0]
        self.assertEqual(urlsplit(location).netloc, "oauth.yandex.ru")
        query = parse_qs(urlsplit(location).query)
        self.assertEqual(query["redirect_uri"], [CALLBACK])
        self.assertEqual(query["client_id"], [self.settings.yandex_client_id])
        self.assertEqual(query["code_challenge_method"], ["S256"])
        self.assertNotEqual(query["state"], [over.get("state", legacy.STATE)])
        self.peer.challenge = query["code_challenge"][0]
        return query["state"][0], self.cookies(headers)

    def callback(self, state, jar, code=None, **over):
        params = {"state": state, "code": code or "synthetic-code-" + legacy.uid()}
        params.update(over)
        return self.request("GET", OAUTH + "/yandex/callback?" + urlencode(params),
                            headers={"Cookie": self.cookie_header(jar)})

    def exchange(self, code):
        status, _, body = self.form_call(OAUTH + "/token", {"grant_type": "authorization_code",
            "client_id": legacy.CLIENT_ID, "client_secret": legacy.CLIENT_SECRET,
            "redirect_uri": EXACT_REDIRECT, "code": code})
        return status, json.loads(body)

    def refresh(self, token):
        status, _, body = self.form_call(OAUTH + "/refresh", {"refresh_token": token,
            "client_id": legacy.CLIENT_ID, "client_secret": legacy.CLIENT_SECRET})
        return status, json.loads(body)

    def fallback_finish(self, page, headers, credential):
        # Exercise actual old consent / challenge / preview / phone approval / code.
        csrf = self.csrf_from(page)
        jar = self.cookies(headers)
        fields = {k: v[0] for k, v in parse_qs(self.authorize_query(), keep_blank_values=True).items()}
        fields["csrf"] = csrf
        status, headers, page = self.form_call(OAUTH + "/authorize", fields, {"Cookie": self.cookie_header(jar)})
        self.assertEqual(status, 200)
        code = self.display_code_from(page)
        jar = self.cookies(headers)
        bake, generation = self.select(credential)
        status, _, preview, _ = self.json_call("POST", PHONE + "/pair-preview", {"code": code}, self.install_header(credential))
        self.assertEqual(status, 200)
        status, _, _, _ = self.json_call("POST", PHONE + "/pair-approve", {"challenge_id": preview["challenge_id"],
            "code": code, "bake_key": bake, "selection_generation": generation}, self.install_header(credential))
        self.assertEqual(status, 200)
        status, headers, _ = self.form_call(OAUTH + "/confirm", {"csrf": jar["madre_csrf"]}, {"Cookie": self.cookie_header(jar)})
        self.assertEqual(status, 303)
        return parse_qs(urlsplit(self.header_map(headers)["location"][0]).query)["code"][0]

    def test_bind_contract_minimal_persistence_and_stable_salted_hash(self):
        credential, install = self.enroll()
        status, headers, profile, _ = self.bind(credential)
        self.assertEqual(status, 200)
        self.assertEqual(set(profile), {"uid_hash", "login", "display_name", "avatar_id", "avatar_empty"})
        self.assertEqual(profile["login"], LOGIN)
        self.assertEqual(profile["display_name"], NAME)
        self.assertFalse(profile["avatar_empty"])
        self.assertRegex(profile["uid_hash"], r"^[a-f0-9]{64}$")
        self.assertEqual(self.bind(credential)[2]["uid_hash"], profile["uid_hash"])
        self.assertEqual(self.sql_one("SELECT uid FROM yandex_bindings WHERE installation_id=?", (install,))[0], UID)
        self.assertEqual([r[1] for r in sqlite3.connect(self.db_path).execute("PRAGMA table_info(yandex_bindings)")],
                         ["installation_id", "uid", "bound_at"])
        self.assertEqual(self.header_map(headers)["cache-control"], ["no-store"])
        self.peer.info["is_avatar_empty"] = True
        self.peer.info.pop("default_avatar_id")
        self.assertEqual(self.bind(credential)[2]["avatar_id"], None)

    def test_bind_and_unbind_never_enroll_unknown_identity(self):
        credential = new_installation_credential()
        for route, payload in (("yandex-bind", {"yandex_token": TOKEN}), ("yandex-unbind", {})):
            for header in ({}, self.bearer_header(credential), self.install_header(credential), self.install_header(credential + "x")):
                self.assertEqual(self.json_call("POST", PHONE + "/" + route, payload, header)[0], 401)
        self.assertEqual(self.sql_one("SELECT COUNT(*) FROM installations")[0], 0)
        self.assertEqual(self.peer.calls, [])

    def test_bind_foreign_client_upstream_and_input_failures(self):
        credential, _ = self.enroll()
        for value in (None, "", "wrong-client", 42):
            self.peer.info["client_id"] = value
            self.assertEqual(self.bind(credential)[0:3:2], (403, {"error": "foreign_client"}))
        self.peer.info["client_id"] = "our-yandex-client"
        for status in (301, 302, 401, 429, 500):
            self.peer.status = status
            self.assertEqual(self.bind(credential)[0], 502)
        self.peer.status = 200
        for payload in ({}, {"yandex_token": 42}, {"yandex_token": "bad\nheader"}, {"yandex_token": TOKEN, "login": LOGIN}):
            self.assertEqual(self.json_call("POST", PHONE + "/yandex-bind", payload, self.install_header(credential))[0], 400)
        self.assertEqual(self.sql_one("SELECT COUNT(*) FROM yandex_bindings")[0], 0)

    def test_upstream_body_encoding_malformed_identity_are_bounded(self):
        credential, _ = self.enroll()
        for raw in (b"not json", b"[]", b"x" * (MAX_RESPONSE + 1)):
            self.peer.raw = raw
            self.assertEqual(self.bind(credential)[0], 502)
        self.peer.raw = None
        self.peer.encoding = "gzip"
        self.assertEqual(self.bind(credential)[0], 502)
        self.peer.encoding = None
        for identity in (None, 12, "not-numeric", "9" * 33):
            self.peer.info["id"] = identity
            self.assertEqual(self.bind(credential)[0], 502)

    def test_unbind_revokes_grants_access_refresh_and_does_not_touch_other_phone(self):
        a, _ = self.enroll()
        access, bake, gen = self._link(a)
        b, _ = self.enroll()
        other_access, _, _ = self._link(b)
        self.bind(a)
        # Obtain real refresh through UID code flow, then rotate.
        state, jar = self.start_auto()
        status, headers, _ = self.callback(state, jar)
        self.assertEqual(status, 303)
        code = parse_qs(urlsplit(self.header_map(headers)["location"][0]).query)["code"][0]
        status, pair = self.exchange(code)
        self.assertEqual(status, 200)
        status, rotated = self.refresh(pair["refresh_token"])
        self.assertEqual(status, 200)
        self.assertEqual(self.unbind(a)[0], 204)
        self.assertEqual(self.unbind(a)[0], 204)
        self.assertEqual(self.webhook("что печём", access)[0], 401)
        self.assertEqual(self.webhook("что печём", rotated["access_token"])[0], 401)
        self.assertEqual(self.refresh(rotated["refresh_token"])[0], 400)
        self.assertEqual(self.refresh(pair["refresh_token"])[0], 400)
        self.assertEqual(self.webhook("что печём", other_access)[0], 200)
        self.assertEqual(self.sql_one("SELECT COUNT(*) FROM yandex_bindings")[0], 0)
        self.assertEqual(self.sql_one("SELECT COUNT(*) FROM installations")[0], 2)

    def test_uid_match_existing_authority_preserves_state_pkce_and_broker_contract(self):
        credential, _ = self.enroll()
        self._link(credential)
        self.bind(credential)
        outer = "outer Ω state &=+"
        state, jar = self.start_auto(state=outer)
        status, headers, _ = self.callback(state, jar)
        self.assertEqual(status, 303)
        location = self.header_map(headers)["location"][0]
        self.assertTrue(location.startswith(EXACT_REDIRECT + "?"))
        query = parse_qs(urlsplit(location).query)
        self.assertEqual(query["state"], [outer])
        self.assertEqual(query["scope"], [EXACT_SCOPE])
        self.assertEqual(query["client_id"], [legacy.CLIENT_ID])
        status, pair = self.exchange(query["code"][0])
        self.assertEqual(status, 200)
        self.assertEqual(self.webhook("что печём", pair["access_token"])[0], 200)
        self.assertIn("match=uid uid_hash=", self.logs.getvalue())
        self.assertEqual(self.exchange(query["code"][0])[0], 400)
        self.assertEqual(self.webhook("что печём", pair["access_token"])[0], 401)

    def test_no_consent_unknown_wrong_uid_or_ambiguous_falls_back(self):
        credential, _ = self.enroll()
        self.select(credential)
        self.bind(credential)
        state, jar = self.start_auto()
        status, headers, page = self.callback(state, jar)
        self.assertEqual(status, 200)  # select + login is not a voice grant
        self.assertEqual(self.sql_one("SELECT COUNT(*) FROM auth_codes")[0], 0)
        code = self.fallback_finish(page, headers, credential)
        self.assertEqual(self.exchange(code)[0], 200)
        self.peer.info["id"] = "22222222"
        state, jar = self.start_auto()
        self.assertEqual(self.callback(state, jar)[0], 200)
        self.peer.info["id"] = UID
        self.app.settings = self.settings
        other, _ = self.enroll()
        self._link(other)
        self.bind(other)
        state, jar = self.start_auto()
        self.assertEqual(self.callback(state, jar)[0], 200)

    def test_state_csrf_expiry_single_use_and_code_replay(self):
        state, jar = self.start_auto()
        self.assertEqual(self.callback("unknown-state", jar)[0], 400)
        self.assertEqual(self.callback(state, {})[0], 400)
        self.assertEqual(self.callback(state, {"madre_yandex": "foreign-cookie"})[0], 400)
        code = "one-upstream-code"
        self.assertEqual(self.callback(state, jar, code=code)[0], 200)
        before = len(self.peer.calls)
        self.assertEqual(self.callback(state, jar, code=code)[0], 400)
        state, jar = self.start_auto()
        self.assertEqual(self.callback(state, jar, code=code)[0], 400)
        self.assertEqual(len(self.peer.calls), before)
        state, jar = self.start_auto()
        self.app.clock.advance_ms(300001)
        self.assertEqual(self.callback(state, jar)[0], 400)
        self.app.clock.advance_ms(-300001)
        self.assertEqual(self.sql_one("SELECT COUNT(*) FROM auth_codes")[0], 0)

    def test_pkce_exchange_failure_foreign_client_and_denied_consent(self):
        state, jar = self.start_auto()
        self.peer.challenge = "wrong-challenge"
        self.assertEqual(self.callback(state, jar)[0], 502)
        state, jar = self.start_auto()
        self.peer.info["client_id"] = "foreign-client"
        self.assertEqual(self.callback(state, jar)[0], 403)
        state, jar = self.start_auto()
        status, headers, page = self.request("GET", OAUTH + "/yandex/callback?" + urlencode({"state": state, "error": "access_denied"}),
            headers={"Cookie": self.cookie_header(jar)})
        self.assertEqual(status, 200)
        self.assertIn(b"csrf", page)
        self.assertEqual(self.sql_one("SELECT COUNT(*) FROM auth_codes")[0], 0)

    def test_bad_broker_client_scope_redirect_cannot_start_yandex(self):
        self.app.settings = replace(self.settings, yandex_match=True)
        for over in ({"client_id": "other"}, {"scope": "bake:read"}, {"scope": "bake:control&bake:read"},
                     {"redirect_uri": "https://evil.example"}, {"response_type": "token"}):
            self.assertEqual(self.request("GET", OAUTH + "/authorize?" + self.authorize_query(**over))[0], 400)
        self.assertEqual(self.peer.calls, [])
        self.assertEqual(len(self.app.yandex_states.entries), 0)

    def test_unbind_invalidates_pending_phone_challenge_and_issued_authcode(self):
        credential, _ = self.enroll()
        self._link(credential)
        self.bind(credential)
        state, jar = self.start_auto()
        status, headers, _ = self.callback(state, jar)
        code = parse_qs(urlsplit(self.header_map(headers)["location"][0]).query)["code"][0]
        self.assertEqual(self.unbind(credential)[0], 204)
        self.select(credential)
        self.assertEqual(self.exchange(code)[0], 400)
        self.assertEqual(self.sql_one("SELECT COUNT(*) FROM auth_codes WHERE used_at IS NULL")[0], 0)
        self.assertEqual(self.sql_one("SELECT COUNT(*) FROM challenges WHERE consumed_at IS NULL AND installation_id IS NOT NULL")[0], 0)

    def test_unbind_during_bind_does_not_resurrect_identity(self):
        credential, _ = self.enroll()
        started, proceed = threading.Event(), threading.Event()
        self.peer.block_info = (started, proceed)
        results = []
        thread = threading.Thread(target=lambda: results.append(self.bind(credential)[0]))
        thread.start()
        self.assertTrue(started.wait(1))
        self.assertEqual(self.unbind(credential)[0], 204)
        proceed.set()
        thread.join(2)
        self.assertEqual(results, [409])
        self.assertEqual(self.sql_one("SELECT COUNT(*) FROM yandex_bindings")[0], 0)

    def test_approved_but_unissued_phone_challenge_cannot_survive_unbind(self):
        credential, _ = self.enroll()
        code, jar = self.start_challenge()
        bake, generation = self.select(credential)
        _, _, preview, _ = self.json_call("POST", PHONE + "/pair-preview", {"code": code}, self.install_header(credential))
        status, _, _, _ = self.json_call("POST", PHONE + "/pair-approve", {
            "challenge_id": preview["challenge_id"], "code": code, "bake_key": bake,
            "selection_generation": generation}, self.install_header(credential))
        self.assertEqual(status, 200)
        self.assertEqual(self.unbind(credential)[0], 204)
        self.select(credential, bake, generation)
        status, _, _ = self.form_call(OAUTH + "/confirm", {"csrf": jar["madre_csrf"]},
                                     {"Cookie": self.cookie_header(jar)})
        self.assertEqual(status, 400)
        self.assertEqual(self.sql_one("SELECT COUNT(*) FROM auth_codes")[0], 0)

    def test_parallel_callbacks_consume_inner_state_once(self):
        credential, _ = self.enroll()
        self._link(credential)
        self.bind(credential)
        state, jar = self.start_auto()
        statuses = []
        threads = [threading.Thread(target=lambda: statuses.append(self.callback(state, jar)[0])) for _ in range(2)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(2)
        self.assertEqual(sorted(statuses), [303, 400])

    def test_rebind_different_uid_revokes_old_voice_authority(self):
        credential, _ = self.enroll()
        access, _, _ = self._link(credential)
        self.bind(credential)
        self.peer.info["id"] = "1122334455"
        self.assertEqual(self.bind(credential)[0], 200)
        self.assertEqual(self.webhook("что печём", access)[0], 401)
        state, jar = self.start_auto()
        self.assertEqual(self.callback(state, jar)[0], 200)

    def test_unbind_while_oauth_pending_and_selection_change_force_fallback(self):
        credential, _ = self.enroll()
        self._link(credential)
        self.bind(credential)
        state, jar = self.start_auto()
        self.unbind(credential)
        self.assertEqual(self.callback(state, jar)[0], 200)
        self.app.settings = self.settings
        self._link(credential)
        self.bind(credential)
        state, jar = self.start_auto()
        self.select(credential)
        self.assertEqual(self.callback(state, jar)[0], 200)

    def test_missing_configuration_fails_closed_without_breaking_legacy(self):
        credential, _ = self.enroll()
        self.app.settings = replace(self.settings, yandex_uid_salt="", yandex_match=True)
        self.assertEqual(self.bind(credential)[0], 503)
        self.assertEqual(self.request("GET", OAUTH + "/authorize?" + self.authorize_query())[0], 200)
        self.assertEqual(self.unbind(credential)[0], 204)


class TransportBounds(unittest.TestCase):
    def test_external_dns_hang_caller_deadline_and_parallel_cap(self):
        gate = threading.Event()
        def hang(*args, **kwargs):
            gate.wait(1)
            raise OSError("must never appear in logs")
        with patch("yandex_identity._https_json", hang):
            started = time.monotonic()
            for _ in range(4):
                with self.assertRaises(YandexUnavailable):
                    _json_request("login.yandex.ru", "/info", headers={}, timeout=0.02)
            with self.assertRaises(YandexUnavailable):
                _json_request("login.yandex.ru", "/info", headers={}, timeout=0.02)
            self.assertLess(time.monotonic() - started, 0.5)
            gate.set()
        time.sleep(0.03)

    def test_migration_preserves_existing_rows_and_rejects_newer_schema(self):
        with tempfile.TemporaryDirectory(prefix="madre-migration-") as tmp:
            db = str(Path(tmp) / "state" / "voice.sqlite")
            init_db(db)
            with sqlite3.connect(db) as conn:
                conn.execute("DROP TABLE yandex_bindings")
                conn.execute("DROP TABLE yandex_epochs")
                conn.execute("PRAGMA user_version=1")
                conn.execute("INSERT INTO installations(id,credential_hash,created_at) VALUES('old','oldhash',1)")
            init_db(db)
            with sqlite3.connect(db) as conn:
                self.assertEqual(conn.execute("SELECT id FROM installations").fetchall(), [("old",)])
                self.assertEqual(conn.execute("PRAGMA integrity_check").fetchone()[0], "ok")
                self.assertEqual(conn.execute("PRAGMA user_version").fetchone()[0], 2)
                conn.execute("PRAGMA user_version=999")
            with self.assertRaises(RuntimeError):
                init_db(db)
