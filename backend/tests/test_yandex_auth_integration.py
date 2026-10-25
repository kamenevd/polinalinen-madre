"""Real pinned PocketBase/SQLite/JSVM HTTP tests, synthetic accounts ONLY.

Run with MADRE_PB_BINARY=/private/path/pocketbase python3 -m unittest \
    backend.tests.test_yandex_auth_integration -v
Only the external Yandex origin/protocol is rewritten in a PRIVATE copied hook.
The actual curl transport, PB auth/JWT/transactions/rules/SQL are not mocked.
No production endpoint override exists. No live data or service is consulted.
"""
from __future__ import annotations
import concurrent.futures
import hashlib
import hmac
import http.server
import json
import os
from pathlib import Path
import shutil
import socket
import sqlite3
import subprocess
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from typing import Any, cast

ROOT = Path(__file__).resolve().parents[2]
BINARY_SHA256 = "88370d5f6fa4820cd2414fa53c6e168d3dd0e33b7a7fd9ff914265492a7aa3b6"
CLIENT = "synthetic_family_client"
SALT = "synthetic_separate_uid_salt_32_bytes_minimum"
PEPPER = "synthetic_distinct_invite_pepper_32_bytes"
PROFILE_MARKERS = ["private-provider-email@example.invalid", "PRIVATE_PHONE_MARKER", "PRIVATE_AVATAR_MARKER", "PRIVATE_LASTNAME_MARKER"]


def port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Provider(http.server.BaseHTTPRequestHandler):
    def log_message(self, format: str, *args: Any):
        pass

    def do_GET(self):
        owner = getattr(self.server, "owner")
        if self.path == "/redirect-target":
            owner.redirect_hits += 1
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"{}")
            return
        if self.path != "/info?format=json":
            self.send_error(404)
            return
        token = self.headers.get("Authorization", "").removeprefix("OAuth ")
        with owner.lock:
            owner.calls += 1
            value = owner.profiles.get(token)
            gate = owner.gates.get(token)
        if gate:
            gate[0].set()
            if not gate[1].wait(8):
                self.send_error(504)
                return
        status, value = value if value else (401, {})
        self.send_response(status)
        if status in (301, 302, 303, 307, 308):
            self.send_header("Location", "/redirect-target")
        self.send_header("Content-Type", "application/json")
        raw = value.encode() if isinstance(value, str) else json.dumps(value).encode()
        if token not in owner.unknown_lengths:
            self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        try:
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError):
            pass


class AuthTransportSourceContract(unittest.TestCase):
    def test_fixed_origin_no_shell_no_token_argv_and_explicit_environment(self):
        source = (ROOT / "backend/pb_hooks/lib/yandex_info.js").read_text()
        self.assertEqual(1, source.count("https://login.yandex.ru/info?format=json"))
        self.assertIn('$os.cmd("/usr/bin/curl", "-q", "--config", "-")', source)
        self.assertIn('cmd.env = ["PATH=/usr/bin:/bin", "LANG=C"]', source)
        self.assertIn('toString(stdout, 65541)', source)
        self.assertIn("cmd.wait()", source)
        self.assertNotIn("$os.getenv", source)
        self.assertNotIn("--location", source)
        self.assertNotIn("--insecure", source)
        workflow = (ROOT / ".github/workflows/pocketbase-auth.yml").read_text()
        # runner context is not available in jobs.<job>.env. Resolve the path
        # in the actual runner step and export it for later steps instead.
        self.assertNotIn("${{ runner.temp }}", workflow)
        self.assertIn('export TMPDIR="$RUNNER_TEMP/madre-pb-isolated"', workflow)
        self.assertIn('>> "$GITHUB_ENV"', workflow)

    def test_auth_implementation_is_not_an_alice_or_identity_shortcut(self):
        source = (ROOT / "backend/pb_hooks/lib/madre_auth.js").read_text()
        self.assertIn('info.client_id !== config.client', source)
        self.assertIn('$security.hs256(info.id, config.salt)', source)
        self.assertIn('findAuthRecordByToken(token, "auth")', source)
        self.assertIn('txApp.canAccessRecord', source)
        self.assertIn('$apis.recordAuthResponse(e, record, "yandex")', source)
        self.assertNotIn("default_email", source)
        self.assertNotIn("installation_id", source)


@unittest.skipUnless(os.environ.get("MADRE_PB_BINARY"), "explicit isolated PB binary required")
class YandexAuthIntegration(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.binary = Path(os.environ["MADRE_PB_BINARY"]).resolve(strict=True)
        if hashlib.sha256(cls.binary.read_bytes()).hexdigest() != BINARY_SHA256:
            raise AssertionError("not the approved exact PocketBase 0.39.11 binary")
        cls.addClassCleanup(cls.cleanup)
        cls.work = Path(tempfile.mkdtemp(prefix="madre-pb-isolated-", dir=os.environ.get("TMPDIR")))
        os.chmod(cls.work, 0o700)
        cls.data = cls.work / "pb_data"
        cls.hooks = cls.work / "pb_hooks"
        cls.migrations = cls.work / "pb_migrations"
        cls.hooks.mkdir()
        cls.migrations.mkdir()
        # Exact public deployed baseline, resolved to original committed source.
        baseline = ROOT / "backend/tests/fixtures/deployed_family_15adb63.js"
        assert hashlib.sha256(baseline.read_bytes()).hexdigest() == "d03c7381e9e999faa5c4fc7bece59ec35bbdfc26244136491d550474cdfb70d3"
        shutil.copy2(baseline, cls.hooks / "madre_family.pb.js")
        shutil.copy2(ROOT / "backend/pb_hooks/madre_stats.pb.js", cls.hooks)
        for p in (ROOT / "backend/pb_migrations").glob("*.js"):
            if p.name < "1787251200":
                shutil.copy2(p, cls.migrations)
        cls.proc = None
        cls.log = (cls.work / "pocketbase.log").open("ab")
        cls.env = dict(os.environ, MADRE_UID_SALT=SALT, MADRE_YANDEX_CLIENT_ID=CLIENT, MADRE_INVITE_PEPPER=PEPPER)
        cls.start()
        cls.users = [cls.register(i) for i in range(14)]
        cls.owner, cls.member, cls.outsider = cls.users[:3]
        _, created = cls.request("/api/madre/family/create", {"name": "Synthetic old shelf"}, cls.owner["token"])
        cls.family_id = created["family_id"]
        cls.invite_code = created["invite_code"]
        status, _ = cls.request("/api/madre/family/join", {"code": cls.invite_code}, cls.member["token"])
        assert status == 200
        status, bake = cls.request("/api/collections/bake_stats/records", {
            "device_id": "synthetic-device", "recipe_id": "synthetic-recipe", "recipe_name": "Synthetic bread",
            "bake_date": "2026-10-25 00:00:00.000Z", "client_event_id": "synthetic-bake", "family": cls.family_id,
        }, cls.owner["token"])
        assert status == 200, (status, bake)
        cls.bake_id = bake["id"]
        cls.stop()
        cls.before_users = cls.sql("SELECT * FROM users")
        cls.before_families = cls.sql("SELECT * FROM families")
        cls.before_bakes = cls.sql("SELECT * FROM bake_stats")
        cls.before_schema = cls.sql("SELECT * FROM _collections WHERE name='users'")[0]
        # Upgrade the SAME synthetic DB, not a fresh post-migration fixture.
        shutil.copytree(ROOT / "backend/pb_hooks", cls.hooks, dirs_exist_ok=True)
        shutil.copytree(ROOT / "backend/pb_migrations", cls.migrations, dirs_exist_ok=True)
        cls.lock = threading.Lock()
        cls.profiles, cls.gates, cls.oauth_canaries, cls.uid_canaries = {}, {}, [], []
        cls.unknown_lengths = set()
        cls.calls, cls.redirect_hits = 0, 0
        cls.provider = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Provider)
        setattr(cls.provider, "owner", cls)
        threading.Thread(target=cls.provider.serve_forever, daemon=True).start()
        # Replace ONLY the fixed external transport boundary, in private copy.
        boundary = cls.hooks / "lib/yandex_info.js"
        source = boundary.read_text()
        assert source.count("https://login.yandex.ru/info?format=json") == 1
        source = source.replace("https://login.yandex.ru/info?format=json", f"http://127.0.0.1:{cls.provider.server_port}/info?format=json")
        source = source.replace('proto = "=https"', 'proto = "=http"')
        boundary.write_text(source)
        cls.start()

    @classmethod
    def start(cls):
        cls.port = port()
        cls.base = f"http://127.0.0.1:{cls.port}"
        cls.proc = subprocess.Popen([str(cls.binary), "serve", f"--http=127.0.0.1:{cls.port}",
            f"--dir={cls.data}", f"--hooksDir={cls.hooks}", f"--migrationsDir={cls.migrations}"],
            cwd=cls.work, env=cls.env, stdin=subprocess.DEVNULL, stdout=cls.log, stderr=cls.log)
        for _ in range(100):
            if cls.proc.poll() is not None:
                raise AssertionError(f"isolated PB failed; private log: {cls.work}/pocketbase.log")
            try:
                if cls.request("/api/health", method="GET")[0] == 200:
                    return
            except OSError:
                pass
            time.sleep(0.05)
        raise AssertionError("isolated PB readiness timeout")

    @classmethod
    def stop(cls):
        if cls.proc and cls.proc.poll() is None:
            cls.proc.terminate()  # ONLY this harness-owned local child PID.
            cls.proc.wait(timeout=15)
        cls.proc = None

    @classmethod
    def cleanup(cls):
        cls.stop()
        if hasattr(cls, "provider"):
            cls.provider.shutdown()
            cls.provider.server_close()
        if hasattr(cls, "log"):
            cls.log.close()
        if hasattr(cls, "work"):
            print(f"isolated evidence directory: {cls.work}")

    @classmethod
    def request(cls, path, body=None, auth=None, *, method="POST", v2=False, headers=None, raw=None) -> tuple[int, Any]:
        h = {"Content-Type": "application/json"}
        if v2:
            h["X-Madre-Family-Version"] = "2"
        if auth:
            h["Authorization"] = auth
        h.update(headers or {})
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        req = urllib.request.Request(cls.base + path, data=data, headers=h, method=method)
        try:
            response = urllib.request.urlopen(req, timeout=15)
        except urllib.error.HTTPError as e:
            response = e
        with response:
            raw = response.read()
            try:
                result = json.loads(raw) if raw else None
            except ValueError:
                result = {"invalid_json": True}
            return cast(int, response.status), result

    @classmethod
    def register(cls, i):
        email = f"synthetic-{i}@example.invalid"
        password = "SyntheticOnlyPassword_123!"
        status, record = cls.request("/api/collections/users/records", {
            "email": email, "password": password, "passwordConfirm": password, "name": f"Existing synthetic {i}"})
        assert status == 200, (status, record)
        status, auth = cls.request("/api/collections/users/auth-with-password", {"identity": email, "password": password})
        assert status == 200
        return auth

    @classmethod
    def sql(cls, sql, params=()):
        with sqlite3.connect(cls.data / "data.db") as db:
            db.row_factory = sqlite3.Row
            return [dict(row) for row in db.execute(sql, params)]

    @classmethod
    def profile(cls, n, **changes):
        token = f"SyntheticOAuthCanary_{n}"
        uid = "919191919191919191919191919191919191919191919191" + str(n)
        value = {"id": uid, "client_id": CLIENT, "first_name": "Allowed synthetic given name",
            "default_email": PROFILE_MARKERS[0], "default_phone": PROFILE_MARKERS[1],
            "default_avatar_id": PROFILE_MARKERS[2], "last_name": PROFILE_MARKERS[3]}
        value.update(changes)
        with cls.lock:
            cls.profiles[token] = (200, value)
            cls.oauth_canaries.append(token)
            cls.uid_canaries.append(uid)
        return token, uid

    @classmethod
    def auth(cls, token, old=None, **kwargs):
        return cls.request("/api/madre/auth/yandex" + ("/link" if old else ""), {"yandex_token": token}, old, v2=True, **kwargs)

    def test_01_upgrade_preserves_every_legacy_column_and_rules(self):
        rows = self.sql("SELECT * FROM users")
        for before, after in zip(self.before_users, rows):
            self.assertEqual(before, {k: after[k] for k in before})
            self.assertEqual("", after["yandex_uid_hash"])
        self.assertEqual(self.before_families, [{k: row[k] for k in self.before_families[0]} for row in self.sql("SELECT * FROM families")])
        self.assertEqual(self.before_bakes, self.sql("SELECT * FROM bake_stats"))
        before = self.before_schema
        after = self.sql("SELECT * FROM _collections WHERE name='users'")[0]
        for key in ("viewRule", "listRule", "deleteRule", "options"):
            self.assertEqual(before[key], after[key])
        self.assertIsNotNone(after["createRule"])
        self.assertIn("yandex_uid_hash:isset = false", after["createRule"])
        self.assertEqual(2, len(self.sql("SELECT * FROM family_members")))

    def test_02_login_uses_separate_hmac_no_email_merge_no_rename(self):
        token, uid = self.profile(2, default_email="synthetic-0@example.invalid")
        status, result = self.auth(token)
        self.assertEqual(200, status, result)
        self.assertEqual({"token", "record"}, set(result))
        self.assertEqual({"id", "name", "family"}, set(result["record"]))
        self.assertNotEqual(self.owner["record"]["id"], result["record"]["id"])
        self.assertEqual("", result["record"]["family"])
        user = self.sql("SELECT * FROM users WHERE id=?", (result["record"]["id"],))[0]
        self.assertEqual(hmac.new(SALT.encode(), uid.encode(), "sha256").hexdigest(), user["yandex_uid_hash"])
        self.assertEqual("", user["email"])
        self.assertEqual("", user["avatar"])
        self.assertEqual(0, user["verified"])
        self.assertEqual(200, self.request("/api/collections/users/auth-refresh", {}, result["token"])[0])
        self.profiles[token][1]["first_name"] = "Not an automatic rename"
        status, again = self.auth(token)
        self.assertEqual(200, status, again)
        self.assertEqual(result["record"], again["record"])
        self.assertEqual(0, len(self.sql("SELECT * FROM _externalAuths")))

    def test_03_explicit_same_record_link_preserves_password_family_bakes_name(self):
        token, _ = self.profile(3)
        before = self.sql("SELECT * FROM users WHERE id=?", (self.owner["record"]["id"],))[0]
        status, result = self.auth(token, self.owner["token"])
        self.assertEqual(200, status, result)
        self.assertEqual(self.owner["record"]["id"], result["record"]["id"])
        self.assertEqual(self.family_id, result["record"]["family"])
        after = self.sql("SELECT * FROM users WHERE id=?", (before["id"],))[0]
        for key in before:
            if key not in {"yandex_uid_hash", "updated"}:
                self.assertEqual(before[key], after[key], key)
        self.assertEqual(self.before_families, [{k: row[k] for k in self.before_families[0]} for row in self.sql("SELECT * FROM families")])
        self.assertEqual(self.before_bakes, self.sql("SELECT * FROM bake_stats"))
        self.assertEqual(200, self.auth(token, self.owner["token"])[0])
        self.assertEqual(result["record"], self.auth(token)[1]["record"])
        status, password = self.request("/api/collections/users/auth-with-password", {
            "identity": "synthetic-0@example.invalid", "password": "SyntheticOnlyPassword_123!"})
        self.assertEqual(200, status)
        self.assertEqual(before["id"], password["record"]["id"])

    def test_04_link_conflicts_never_change_either_record(self):
        token, _ = self.profile(4)
        other, _ = self.profile(400)
        self.assertEqual(200, self.auth(token, self.member["token"])[0])
        before = self.sql("SELECT * FROM users ORDER BY id")
        self.assertEqual((409, {"error": "uid_already_linked"}), self.auth(token, self.outsider["token"]))
        self.assertEqual((409, {"error": "conflict"}), self.auth(other, self.member["token"]))
        self.assertEqual(before, self.sql("SELECT * FROM users ORDER BY id"))

    def test_05_client_validation_malformed_upstream_and_no_redirects(self):
        bad = [dict(client_id="another-client"), dict(client_id=None), dict(id=123), dict(id=""), dict(id="raw-uid"), dict(id=None)]
        before = self.sql("SELECT id,yandex_uid_hash FROM users ORDER BY id")
        for n, changes in enumerate(bad):
            token, _ = self.profile(500+n, **changes)
            self.assertEqual((401, {"error": "unauthorized"}), self.auth(token))
        for status, body in [(401, {}), (403, {}), (500, {}), (200, "not-json"), (200, []), (200, "x" * 70000), (302, {}), (307, {})]:
            token, _ = self.profile(600 + status)
            self.profiles[token] = (status, body)
            self.assertNotEqual(200, self.auth(token)[0])
        self.assertEqual(0, self.redirect_hits)
        self.assertEqual(before, self.sql("SELECT id,yandex_uid_hash FROM users ORDER BY id"))

    def test_06_protocol_and_authentication_guards_before_provider(self):
        token, _ = self.profile(6)
        before = self.calls
        body = {"yandex_token": token}
        path = "/api/madre/auth/yandex"
        self.assertEqual(426, self.request(path, body)[0])
        self.assertEqual(426, self.request(path, body, headers={"X-Madre-Family-Version": "1"})[0])
        for body in [{}, {"yandex_token": 1}, {"yandex_token": token, "email": "no"}, {"yandex_token": "x\nheader"}, {"yandex_token": "x"*4097}]:
            self.assertEqual(400, self.request(path, body, v2=True)[0])
        self.assertEqual(400, self.request(path, raw=b"{", v2=True)[0])
        self.assertEqual(413, self.request(path, raw=b"x"*65537, v2=True)[0])
        self.assertEqual(400, self.request(path, {"yandex_token": token}, v2=True, headers={"Content-Type": "text/plain"})[0])
        self.assertEqual(400, self.request(path, {"yandex_token": token}, self.owner["token"], v2=True)[0])
        self.assertEqual(401, self.request(path+"/link", {"yandex_token": token}, v2=True)[0])
        self.assertEqual(401, self.auth(token, "Bearer " + self.owner["token"])[0])
        self.assertEqual(before, self.calls)

    def test_07_direct_collection_cannot_forge_clear_or_append_identity(self):
        victim = self.users[4]
        for key in ("yandex_uid_hash", "yandex_uid_hash+"):
            status, _ = self.request("/api/collections/users/records/" + victim["record"]["id"],
                {key: "a"*64}, victim["token"], method="PATCH")
            # Hidden input fields and unsupported text modifiers may be
            # silently ignored by native PB (200/no-op). The durable identity
            # MUST remain untouched, not merely hidden in the HTTP reply.
            self.assertEqual("", self.sql("SELECT yandex_uid_hash FROM users WHERE id=?", (victim["record"]["id"],))[0]["yandex_uid_hash"])
        status, created = self.request("/api/collections/users/records", {"email": "forged@example.invalid",
            "password": "SyntheticOnlyPassword_123!", "passwordConfirm": "SyntheticOnlyPassword_123!", "yandex_uid_hash": "a"*64})
        if status == 200:
            self.assertEqual("", self.sql("SELECT yandex_uid_hash FROM users WHERE id=?", (created["id"],))[0]["yandex_uid_hash"])
        self.assertEqual("", self.sql("SELECT yandex_uid_hash FROM users WHERE id=?", (victim["record"]["id"],))[0]["yandex_uid_hash"])
        with sqlite3.connect(self.data / "data.db") as db:
            db.execute("UPDATE users SET yandex_uid_hash=? WHERE id=?", ("a"*64, victim["record"]["id"]))
            with self.assertRaises(sqlite3.IntegrityError):
                db.execute("UPDATE users SET yandex_uid_hash=? WHERE id=?", ("a"*64, self.users[5]["record"]["id"]))
            db.rollback()

    def test_08_concurrent_logins_get_exactly_one_record(self):
        token, uid = self.profile(8)
        gate = threading.Barrier(12)
        def call(_):
            gate.wait(10)
            return self.auth(token)
        with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
            results = list(pool.map(call, range(12)))
        self.assertEqual([200]*12, [r[0] for r in results], results)
        self.assertEqual(1, len({r[1]["record"]["id"] for r in results}))
        self.assertEqual(1, len(self.sql("SELECT id FROM users WHERE yandex_uid_hash=?", (hmac.new(SALT.encode(), uid.encode(), "sha256").hexdigest(),))))

    def test_09_concurrent_two_records_one_uid_and_one_record_two_uids(self):
        token, _ = self.profile(9)
        gate = threading.Barrier(2)
        def call(old):
            gate.wait(10)
            return self.auth(token, old)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(call, [self.users[5]["token"], self.users[6]["token"]]))
        self.assertEqual([200, 409], sorted(r[0] for r in results), results)
        first, _ = self.profile(91)
        second, _ = self.profile(92)
        gate = threading.Barrier(2)
        def same(t):
            gate.wait(10)
            return self.auth(t, self.users[7]["token"])
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(same, [first, second]))
        self.assertEqual([200, 409], sorted(r[0] for r in results), results)

    def test_10_link_revalidates_revoked_old_token_after_provider(self):
        token, _ = self.profile(10)
        entered, release = threading.Event(), threading.Event()
        self.gates[token] = (entered, release)
        target = self.users[8]
        before = self.sql("SELECT yandex_uid_hash FROM users WHERE id=?", (target["record"]["id"],))
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(self.auth, token, target["token"])
            try:
                self.assertTrue(entered.wait(5))
                assert self.proc is not None
                children = set()
                for thread_children in Path(f"/proc/{self.proc.pid}/task").glob("*/children"):
                    children.update(thread_children.read_text().split())
                self.assertTrue(children, "real curl boundary child was not exercised")
                for pid in children:
                    args = Path(f"/proc/{pid}/cmdline").read_bytes()
                    # A raced zombie exposes an empty cmdline; it cannot
                    # reveal credentials and isn't the gated active transport.
                    if not args:
                        continue
                    self.assertEqual([b"/usr/bin/curl", b"-q", b"--config", b"-", b""], args.split(b"\0"))
                    self.assertNotIn(token.encode(), args)
                    # Some unprivileged LXC procfs deny child environ even to
                    # root. Don't weaken ptrace/procfs or claim a runtime read
                    # there; the exact env allowlist is checked in source tests.
                    try:
                        environment = Path(f"/proc/{pid}/environ").read_bytes()
                    except PermissionError:
                        environment = None
                    if environment is not None:
                        self.assertNotIn(token.encode(), environment)
                        self.assertNotIn(SALT.encode(), environment)
                status, _ = self.request("/api/collections/users/records/"+target["record"]["id"], {
                    "oldPassword": "SyntheticOnlyPassword_123!", "password": "ChangedSyntheticPassword_123!",
                    "passwordConfirm": "ChangedSyntheticPassword_123!"}, target["token"], method="PATCH")
                self.assertEqual(200, status)
            finally:
                release.set()
            self.assertEqual((401, {"error": "unauthorized"}), future.result(10))
        self.assertEqual(before, self.sql("SELECT yandex_uid_hash FROM users WHERE id=?", (target["record"]["id"],)))

    def test_11_legacy_registration_code_and_family_statistics_isolation(self):
        self.assertTrue(self.register(111)["record"]["id"])
        status, _ = self.request("/api/madre/family/join", {"code": self.invite_code}, self.outsider["token"])
        self.assertEqual(200, status)
        status, roster = self.request("/api/collections/bake_stats/records", auth=self.outsider["token"], method="GET")
        self.assertEqual(200, status)
        self.assertIn(self.bake_id, [r["id"] for r in roster["items"]])
        status, hidden = self.request("/api/collections/bake_stats/records", auth=self.users[9]["token"], method="GET")
        self.assertEqual(200, status)
        self.assertEqual([], hidden["items"])
        status, _ = self.request("/api/madre/family/rename", {"name": "Synthetic renamed shelf"}, self.owner["token"], method="PATCH")
        self.assertEqual(200, status)
        self.assertEqual("Synthetic old shelf", self.sql("SELECT family_name FROM bake_stats WHERE id=?", (self.bake_id,))[0]["family_name"])
        self.assertEqual(403, self.request("/api/madre/family/invite", {}, self.member["token"])[0])
        self.assertEqual(200, self.request("/api/madre/family/leave", {}, self.outsider["token"])[0])
        self.assertEqual(200, self.request("/api/madre/family/invite", {}, self.owner["token"])[0])

    def test_12_configuration_fail_closed_and_registration_still_requires_real_email(self):
        token, _ = self.profile(120)
        before = self.sql("SELECT id,yandex_uid_hash FROM users ORDER BY id")
        for key, value in [("MADRE_UID_SALT", ""), ("MADRE_UID_SALT", PEPPER), ("MADRE_YANDEX_CLIENT_ID", "")]:
            old = self.env[key]
            self.stop()
            self.env[key] = value
            self.start()
            try:
                count = self.calls
                self.assertEqual((503, {"error": "unavailable"}), self.auth(token))
                self.assertEqual(count, self.calls)
                self.assertEqual(before, self.sql("SELECT id,yandex_uid_hash FROM users ORDER BY id"))
            finally:
                self.stop()
                self.env[key] = old
                self.start()
        self.assertNotEqual(200, self.request("/api/collections/users/records", {
            "password": "SyntheticOnlyPassword_123!", "passwordConfirm": "SyntheticOnlyPassword_123!"})[0])

    def test_13_auth_link_race_does_not_take_over_or_duplicate(self):
        token, uid = self.profile(13)
        gate = threading.Barrier(2)
        def login():
            gate.wait(10)
            return self.auth(token)
        def link():
            gate.wait(10)
            return self.auth(token, self.users[10]["token"])
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            a, b = pool.submit(login), pool.submit(link)
            login_result, link_result = a.result(10), b.result(10)
        self.assertEqual(200, login_result[0])
        self.assertIn(link_result[0], [200, 409])
        if link_result[0] == 200:
            self.assertEqual(login_result[1]["record"]["id"], self.users[10]["record"]["id"])
        else:
            self.assertNotEqual(login_result[1]["record"]["id"], self.users[10]["record"]["id"])
        self.assertEqual(1, len(self.sql("SELECT id FROM users WHERE yandex_uid_hash=?", (hmac.new(SALT.encode(), uid.encode(), "sha256").hexdigest(),))))

    def test_14_actual_sql_abort_rolls_back_link_without_changing_old_account(self):
        token, _ = self.profile(14)
        target = self.users[11]
        before = self.sql("SELECT * FROM users WHERE id=?", (target["record"]["id"],))
        with sqlite3.connect(self.data / "data.db") as db:
            db.execute("CREATE TRIGGER isolated_binding_abort BEFORE UPDATE OF yandex_uid_hash ON users "
                "WHEN NEW.yandex_uid_hash != OLD.yandex_uid_hash BEGIN SELECT RAISE(ABORT, 'synthetic abort'); END")
        try:
            self.assertEqual((503, {"error": "unavailable"}), self.auth(token, target["token"]))
            self.assertEqual(before, self.sql("SELECT * FROM users WHERE id=?", (target["record"]["id"],)))
        finally:
            with sqlite3.connect(self.data / "data.db") as db:
                db.execute("DROP TRIGGER isolated_binding_abort")

    def test_15_native_auth_rule_and_mfa_are_never_bypassed(self):
        token, _ = self.profile(15)
        self.stop()
        with sqlite3.connect(self.data / "data.db") as db:
            original = json.loads(db.execute("SELECT options FROM _collections WHERE name='users'").fetchone()[0])
        for change in [{"authRule": "verified = true"}, {"mfa": {"enabled": True, "duration": 1800, "rule": ""}}]:
            options = dict(original, **change)
            with sqlite3.connect(self.data / "data.db") as db:
                db.execute("UPDATE _collections SET options=? WHERE name='users'", (json.dumps(options),))
            self.start()
            try:
                before = self.sql("SELECT * FROM users ORDER BY id")
                self.assertEqual((401, {"error": "unauthorized"}), self.auth(token))
                self.assertEqual((401, {"error": "unauthorized"}), self.auth(token, self.users[12]["token"]))
                self.assertEqual(before, self.sql("SELECT * FROM users ORDER BY id"))
            finally:
                self.stop()
        with sqlite3.connect(self.data / "data.db") as db:
            db.execute("UPDATE _collections SET options=? WHERE name='users'", (json.dumps(original),))
        self.start()

    def test_16_unknown_length_response_is_bounded_and_does_not_authenticate(self):
        token, _ = self.profile(16)
        self.profiles[token] = (200, "x"*1000000)
        self.unknown_lengths.add(token)
        self.assertEqual((503, {"error": "unavailable"}), self.auth(token))

    def test_17_bound_identity_cannot_be_cleared_by_normal_or_modified_body(self):
        token, _ = self.profile(17)
        target = self.users[13]
        self.assertEqual(200, self.auth(token, target["token"])[0])
        before = self.sql("SELECT yandex_uid_hash FROM users WHERE id=?", (target["record"]["id"],))
        for body in [{"yandex_uid_hash": ""}, {"yandex_uid_hash+": "a"}, {"yandex_uid_hash-": "a"}]:
            self.request("/api/collections/users/records/"+target["record"]["id"], body, target["token"], method="PATCH")
            self.assertEqual(before, self.sql("SELECT yandex_uid_hash FROM users WHERE id=?", (target["record"]["id"],)))

    def test_18_non_destructive_schema_rollback_and_reupgrade_keeps_bindings(self):
        before = self.sql("SELECT * FROM users ORDER BY id")
        self.stop()
        args = [str(self.binary), "migrate", "down", "3", f"--dir={self.data}",
            f"--hooksDir={self.hooks}", f"--migrationsDir={self.migrations}", "--automigrate=false"]
        result = subprocess.run(args, input=b"y\n", stdout=self.log, stderr=self.log,
            env=self.env, cwd=self.work, timeout=15)
        self.assertEqual(0, result.returncode)
        self.assertEqual(before, self.sql("SELECT * FROM users ORDER BY id"))
        self.assertEqual([], self.sql("SELECT * FROM _migrations WHERE file='1792886400_yandex_identity.js'"))
        self.start()  # applies the same additive migration again
        self.assertEqual(before, self.sql("SELECT * FROM users ORDER BY id"))
        self.assertEqual(1, len(self.sql("SELECT * FROM _migrations WHERE file='1792886400_yandex_identity.js'")))

    def test_99_unsafe_query_headers_and_provider_data_never_reach_db_or_logs(self):
        token, _ = self.profile(12)
        status, result = self.request("/api/madre/auth/yandex?yandex_token="+token,
            {"yandex_token": token}, v2=True, headers={"Referer": "https://example.invalid/"+token, "User-Agent": token})
        self.assertEqual((400, {"error": "invalid_request"}), (status, result))
        # Logs are asynchronously flushed by PB; clean stop drains its logger.
        self.stop()
        markers = self.oauth_canaries + self.uid_canaries + PROFILE_MARKERS + [SALT, PEPPER]
        data = bytearray((self.work/"pocketbase.log").read_bytes())
        for p in self.data.rglob("*"):
            if p.is_file() and (p.name.endswith((".db", "-wal", "-shm"))):
                data.extend(p.read_bytes())
                if p.suffix == ".db":
                    with sqlite3.connect(p) as db:
                        data.extend("\n".join(db.iterdump()).encode())
        for marker in markers:
            self.assertNotIn(marker.encode(), data, "OAuth/UID/profile/secret persisted in isolated DB/log")
        self.start()


if __name__ == "__main__":
    unittest.main()
