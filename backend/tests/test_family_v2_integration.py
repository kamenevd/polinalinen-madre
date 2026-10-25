"""K3–K9 against the actual pinned PB/JSVM/SQLite. Synthetic fixtures ONLY.
Reuse the historical deployed-source upgrade harness; only Yandex is external-mocked.
"""
from __future__ import annotations
import concurrent.futures
import hashlib
import hmac
import json
import os
import sqlite3
import subprocess
import threading
import unittest
from pathlib import Path
from typing import BinaryIO
from backend.tests import test_yandex_auth_integration as runtime

KEY = "synthetic_separate_domain_invite_derivation_256bit_key"

@unittest.skipUnless(os.environ.get("MADRE_PB_BINARY"), "explicit isolated PB binary required")
class FamilyMembershipIntegration(unittest.TestCase):
    # Reuse fixture lifecycle, NOT inherited auth test methods (no duplicate totals).
    start = runtime.YandexAuthIntegration.__dict__["start"]
    stop = runtime.YandexAuthIntegration.__dict__["stop"]
    cleanup = runtime.YandexAuthIntegration.__dict__["cleanup"]
    request = runtime.YandexAuthIntegration.__dict__["request"]
    register = runtime.YandexAuthIntegration.__dict__["register"]
    sql = runtime.YandexAuthIntegration.__dict__["sql"]
    profile = runtime.YandexAuthIntegration.__dict__["profile"]
    auth = runtime.YandexAuthIntegration.__dict__["auth"]

    env: dict[str, str]
    before_users: list[dict]
    before_families: list[dict]
    before_bakes: list[dict]
    users: list[dict]
    binary: Path
    data: Path
    hooks: Path
    migrations: Path
    work: Path
    log: BinaryIO
    oauth_canaries: list[str]
    uid_canaries: list[str]

    @classmethod
    def setUpClass(cls):
        runtime.YandexAuthIntegration.setUpClass.__func__(cls)
        cls.stop()
        cls.env["MADRE_INVITE_DERIVATION_KEY"] = KEY
        cls.start()
        cls.serial = 10000
        cls.invite_canaries, cls.restore_canaries, cls.pb_canaries = [], [], []
        cls.label_canary = "FamilyScopedPrivateLabel"

    def setUp(self):
        self.sql("DELETE FROM family_limits")
        self.o = self.user()
        self.f = self.create(self.o)
        self.t = self.rotate(self.o)

    @classmethod
    def user(cls):
        cls.serial += 1
        token, _ = cls.profile(cls.serial, first_name=f"Synthetic {cls.serial}")
        status, u = cls.auth(token)
        assert status == 200, (status, u)
        cls.pb_canaries.append(u["token"])
        return u

    @classmethod
    def create(cls, u):
        status, f = cls.request("/api/madre/family/create", {"name": "Synthetic family"}, u["token"], v2=True)
        assert status == 200, (status, f)
        return f

    @classmethod
    def rotate(cls, u):
        status, i = cls.request("/api/madre/family/invite", {}, u["token"], v2=True)
        assert status == 200, (status, i)
        t = i["url"].removeprefix("https://kdnfx.space/madre/f/")
        assert len(t) == 16
        cls.invite_canaries.append(t)
        return t

    @classmethod
    def join(cls, u, t, switch=False):
        return cls.request(f"/api/madre/invite/{t}/join", {"switch_family": switch}, u["token"], v2=True)

    @classmethod
    def get(cls, u):
        return cls.request("/api/madre/family", auth=u["token"], method="GET", v2=True)

    @classmethod
    def settings(cls, u, **b):
        return cls.request("/api/madre/family", b, u["token"], method="PATCH", v2=True)

    @classmethod
    def preview(cls, t, web=False, **kw):
        return cls.request(("/api/madre/web/invite/" if web else "/api/madre/invite/")+t, method="GET", v2=True, **kw)

    @classmethod
    def remove(cls, u, p, rotate=True):
        status, r = cls.request("/api/madre/family/members/"+p["record"]["id"]+("" if rotate else "?rotate_invite=0"), auth=u["token"], method="DELETE", v2=True)
        if status == 200:
            cls.restore_canaries.append(r["restore_token"])
        return status, r

    @classmethod
    def restore(cls, u, p, token):
        return cls.request("/api/madre/family/members/"+p["record"]["id"]+"/restore", {"restore_token": token}, u["token"], v2=True)

    @classmethod
    def decide(cls, o, r, accept=True):
        return cls.request("/api/madre/family/requests/"+r, {"accept": accept}, o["token"], v2=True)

    @classmethod
    def status(cls, u, r):
        return cls.request("/api/madre/family/requests/"+r+"/status", auth=u["token"], method="GET", v2=True)

    def add(self, n=1):
        p = [self.user() for _ in range(n)]
        for u in p:
            self.assertEqual(200, self.join(u, self.t)[0])
        return p

    def test_01_same_historical_db_upgraded_without_record_loss_or_registration_closure(self):
        after = {r["id"]: r for r in self.sql("SELECT * FROM users")}
        for before in self.before_users:
            self.assertEqual(before, {k: after[before["id"]][k] for k in before})
        afterf = {r["id"]: r for r in self.sql("SELECT * FROM families")}
        for before in self.before_families:
            self.assertEqual(before, {k: afterf[before["id"]][k] for k in before})
        self.assertEqual(self.before_bakes, self.sql("SELECT * FROM bake_stats"))
        self.assertIsNotNone(self.sql("SELECT createRule FROM _collections WHERE name='users'")[0]["createRule"])
        self.assertEqual(200, self.request("/api/collections/users/auth-with-password", {"identity": "synthetic-0@example.invalid", "password": "SyntheticOnlyPassword_123!"})[0])

    def test_02_hash_only_current_reconstruction_survives_runtime_restart(self):
        i = self.sql("SELECT * FROM family_invites WHERE family=? AND revoked_at=''", (self.f["id"],))[0]
        self.assertEqual(hashlib.sha256(self.t.encode()).hexdigest(), i["token_hash"])
        import base64
        derived = base64.urlsafe_b64encode(hmac.new(KEY.encode(), ("madre.family.invite.v2\0"+i["family"]+"\0"+i["nonce"]).encode(), "sha256").digest()[:12]).decode()
        self.assertEqual(self.t, derived)
        self.assertNotIn(self.t, json.dumps(i))
        from datetime import datetime
        ttl = (datetime.fromisoformat(i["expires_at"].replace("Z", "+00:00"))-datetime.fromisoformat(i["created_at"].replace("Z", "+00:00"))).total_seconds()
        self.assertAlmostEqual(604800, ttl, delta=1)
        m = self.add()[0]
        self.stop(); self.start()
        status, reply = self.request("/api/madre/family/invite", auth=m["token"], method="GET", v2=True)
        self.assertEqual(200, status, reply)
        self.assertEqual("https://kdnfx.space/madre/f/"+self.t, reply["url"])
        self.assertEqual({"url", "expires_at"}, set(reply))
        self.assertEqual(403, self.request("/api/madre/family/invite", {}, m["token"], v2=True)[0])

    def test_03_rotation_revocation_expiry_and_unknown_are_same_404(self):
        expected = (404, {"error": "invite_unavailable"})
        self.assertEqual(200, self.preview(self.t)[0])
        t = self.rotate(self.o)
        self.assertEqual(expected, self.preview(self.t))
        self.assertEqual(expected, self.preview("z"*16))
        self.assertEqual(204, self.request("/api/madre/family/invite", auth=self.o["token"], method="DELETE", v2=True)[0])
        self.assertEqual(expected, self.preview(t))
        t = self.rotate(self.o)
        self.sql("UPDATE family_invites SET expires_at='2000-01-01 00:00:00.000Z' WHERE family=?", (self.f["id"],))
        self.assertEqual(expected, self.preview(t))
        self.assertEqual(expected, self.join(self.user(), t))

    def test_04_web_preview_exact_two_fields_no_ids_people_counts_or_bakes(self):
        self.add(2)
        status, web = self.preview(self.t, web=True)
        self.assertEqual(200, status, web)
        self.assertEqual({"family_name", "inviter_name"}, set(web))
        status, preview = self.preview(self.t)
        self.assertEqual(200, status, preview)
        self.assertEqual({"family_name", "inviter_name", "members_count", "bakes_count", "initials", "approval_required"}, set(preview))
        self.assertEqual(3, preview["members_count"])
        self.assertEqual(3, len(preview["initials"]))
        self.assertNotIn(self.o["record"]["id"], json.dumps(preview))

    def test_05_concurrent_last_seat_never_exceeds_eight(self):
        self.add(6)
        users = [self.user() for _ in range(12)]
        gate = threading.Barrier(len(users))
        def call(u):
            gate.wait(10)
            return self.join(u, self.t)
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(users)) as pool:
            replies = list(pool.map(call, users))
        self.assertEqual(1, sum(s == 200 for s, _ in replies), replies)
        self.assertEqual(11, sum(r == (409, {"error": "family_full"}) for r in replies), replies)
        self.assertEqual(8, len(self.get(self.o)[1]["members"]))

    def test_06_pending_no_access_idempotent_status_and_owner_only_list(self):
        m = self.add()[0]
        self.assertEqual(200, self.settings(self.o, approval_required=True)[0])
        u = self.user(); status, r = self.join(u, self.t)
        self.assertEqual(200, status, r); self.assertEqual("pending", r["status"])
        self.assertEqual((200, r), self.join(u, self.t))
        self.assertEqual(403, self.get(u)[0])
        self.assertEqual([], self.get(m)[1]["pending"])
        self.assertEqual([r["request_id"]], [p["id"] for p in self.get(self.o)[1]["pending"]])
        self.assertEqual(404, self.status(m, r["request_id"])[0])
        self.assertEqual(200, self.decide(self.o, r["request_id"])[0])
        joined = self.status(u, r["request_id"])[1]
        self.assertEqual(dict(r, status="joined"), joined)
        self.assertEqual(200, self.decide(self.o, r["request_id"])[0])
        self.assertEqual(409, self.decide(self.o, r["request_id"], False)[0])
        self.assertEqual(self.f["id"], self.get(u)[1]["id"])

    def test_07_pending_switch_preserves_old_family_then_accept_moves_atomically(self):
        other = self.user(); old = self.create(other); t = self.rotate(other)
        p = self.user(); self.assertEqual(200, self.join(p, t)[0])
        self.settings(self.o, approval_required=True)
        self.assertEqual(409, self.join(p, self.t)[0])
        s, r = self.join(p, self.t, True); self.assertEqual(200, s, r)
        self.assertEqual(old["id"], self.get(p)[1]["id"])
        self.assertEqual(200, self.decide(self.o, r["request_id"])[0])
        self.assertEqual(self.f["id"], self.get(p)[1]["id"])
        self.assertEqual(1, len(self.get(other)[1]["members"]))

    def test_08_owner_switch_cannot_abandon_other_members_even_at_accept(self):
        other = self.user(); old = self.create(other); t = self.rotate(other)
        self.join(self.user(), t)
        self.assertEqual(409, self.join(other, self.t, True)[0])
        self.assertEqual(old["id"], self.get(other)[1]["id"])
        self.settings(self.o, approval_required=True)
        s, r = self.join(other, self.t, True); self.assertEqual(200, s, r)
        self.assertEqual(409, self.decide(self.o, r["request_id"])[0])
        self.assertEqual(old["id"], self.get(other)[1]["id"])
        self.assertEqual("pending", self.status(other, r["request_id"])[1]["status"])

    def test_09_concurrent_accepts_recheck_capacity(self):
        self.add(6); self.settings(self.o, approval_required=True)
        ps = [self.user() for _ in range(4)]
        rs = [self.join(p, self.t)[1]["request_id"] for p in ps]
        gate = threading.Barrier(len(rs))
        def call(r):
            gate.wait(10); return self.decide(self.o, r)
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(rs)) as pool:
            results = list(pool.map(call, rs))
        self.assertEqual([200, 409, 409, 409], sorted(s for s, _ in results), results)
        self.assertEqual(8, len(self.get(self.o)[1]["members"]))

    def test_10_rotation_expiry_owner_changes_invalidate_pending_without_hiding_status(self):
        m = self.add()[0]; self.settings(self.o, approval_required=True)
        p = self.user(); r = self.join(p, self.t)[1]["request_id"]
        self.rotate(self.o)
        self.assertEqual("declined", self.status(p, r)[1]["status"])
        self.assertEqual(409, self.decide(self.o, r)[0])
        t = self.rotate(self.o); p2 = self.user(); r2 = self.join(p2, t)[1]["request_id"]
        self.sql("UPDATE family_invites SET expires_at='2000-01-01 00:00:00.000Z' WHERE family=?", (self.f["id"],))
        self.assertEqual("declined", self.status(p2, r2)[1]["status"])
        self.assertEqual(409, self.decide(self.o, r2)[0])
        t = self.rotate(self.o); p3 = self.user(); r3 = self.join(p3, t)[1]["request_id"]
        self.assertEqual(200, self.request("/api/madre/family/owner", {"member_id": m["record"]["id"]}, self.o["token"], v2=True)[0])
        self.assertEqual("declined", self.status(p3, r3)[1]["status"])
        self.assertEqual(409, self.decide(m, r3)[0])

    def test_11_removed_always_pending_and_restore_scoped_label_not_global(self):
        p = self.add()[0]; before = self.sql("SELECT * FROM users WHERE id=?", (p["record"]["id"],))[0]
        self.assertEqual(200, self.request("/api/madre/family/members/"+p["record"]["id"], {"display_name": self.label_canary}, self.o["token"], method="PATCH", v2=True)[0])
        self.assertEqual(before, self.sql("SELECT * FROM users WHERE id=?", (p["record"]["id"],))[0])
        status, undo = self.remove(self.o, p); self.assertEqual(200, status, undo)
        self.assertEqual(403, self.get(p)[0])
        self.assertEqual(404, self.preview(self.t)[0])
        self.assertEqual(200, self.restore(self.o, p, undo["restore_token"])[0])
        self.assertEqual(self.label_canary, [m["name"] for m in self.get(p)[1]["members"] if m["id"] == p["record"]["id"]][0])
        self.assertEqual(409, self.restore(self.o, p, undo["restore_token"])[0])
        self.remove(self.o, p)
        t = self.rotate(self.o)
        self.assertEqual("pending", self.join(p, t)[1]["status"])
        self.assertEqual(403, self.get(p)[0])

    def test_12_restore_revision_owner_revocation_expiry_and_other_family_guards(self):
        p, m = self.add(2)
        _, a = self.remove(self.o, p, False)
        self.assertEqual(409, self.restore(self.o, m, a["restore_token"])[0])
        self.assertEqual(200, self.restore(self.o, p, a["restore_token"])[0])
        _, b = self.remove(self.o, p, False)
        self.assertGreater(b["removal_revision"], a["removal_revision"])
        self.assertEqual(409, self.restore(self.o, p, a["restore_token"])[0])
        self.sql("UPDATE family_members SET restore_until='2000-01-01 00:00:00.000Z' WHERE family=? AND user=?", (self.f["id"], p["record"]["id"]))
        self.assertEqual(409, self.restore(self.o, p, b["restore_token"])[0])
        # Different current family cannot be stolen by a valid-looking old undo.
        self.create(p)
        self.assertEqual(409, self.restore(self.o, p, b["restore_token"])[0])
        q = self.add()[0]; _, undo = self.remove(self.o, q, False)
        self.rotate(self.o)
        self.assertEqual(409, self.restore(self.o, q, undo["restore_token"])[0])
        t = self.rotate(self.o); z = self.user(); self.join(z, t); _, uz = self.remove(self.o, z, False)
        self.request("/api/madre/family/owner", {"member_id": m["record"]["id"]}, self.o["token"], v2=True)
        self.assertEqual(403, self.restore(self.o, z, uz["restore_token"])[0])
        self.assertEqual(409, self.restore(m, z, uz["restore_token"])[0])

    def test_13_leave_transfer_solo_softdelete_and_no_retention_purge(self):
        p = self.add()[0]
        self.assertEqual(409, self.request("/api/madre/family/leave", {}, self.o["token"], v2=True)[0])
        self.assertEqual(409, self.request("/api/madre/family/leave", {}, self.o["token"])[0])
        self.assertEqual(409, self.request("/api/madre/family", auth=self.o["token"], method="DELETE", v2=True)[0])
        self.assertEqual(200, self.request("/api/madre/family/leave", {"new_owner_id": p["record"]["id"]}, self.o["token"], v2=True)[0])
        self.assertEqual(p["record"]["id"], self.get(p)[1]["owner"])
        self.assertEqual(204, self.request("/api/madre/family", auth=p["token"], method="DELETE", v2=True)[0])
        row = self.sql("SELECT * FROM families WHERE id=?", (self.f["id"],))[0]
        self.assertTrue(row["deleted_at"])
        self.assertEqual(403, self.get(p)[0])
        self.assertEqual(404, self.preview(self.t)[0])
        self.assertEqual(1, len(self.sql("SELECT * FROM users WHERE id=?", (p["record"]["id"],))))
        self.sql("UPDATE families SET deleted_at='2000-01-01 00:00:00.000Z' WHERE id=?", (self.f["id"],))
        self.stop(); self.start()
        self.assertEqual(1, len(self.sql("SELECT * FROM families WHERE id=?", (self.f["id"],))))

    def test_14_bakes_follow_author_removed_feed_hidden_own_record_and_photo_acl_retained(self):
        p = self.add()[0]
        status, bake = self.request("/api/collections/bake_stats/records", {"device_id": "synthetic-device", "recipe_id": "synthetic-recipe", "recipe_name": "Bread", "bake_date": "2026-10-25 00:00:00.000Z", "client_event_id": p["record"]["id"], "family": self.f["id"]}, p["token"])
        self.assertEqual(200, status, bake)
        path = "/api/collections/bake_stats/records/"+bake["id"]
        before = self.sql("SELECT * FROM bake_stats WHERE id=?", (bake["id"],))
        self.assertEqual(200, self.request(path, auth=self.o["token"], method="GET")[0])
        self.remove(self.o, p, False)
        self.assertEqual(404, self.request(path, auth=self.o["token"], method="GET")[0])
        self.assertEqual(200, self.request(path, auth=p["token"], method="GET")[0])
        self.assertEqual(before, self.sql("SELECT * FROM bake_stats WHERE id=?", (bake["id"],)))
        fields = json.loads(self.sql("SELECT fields FROM _collections WHERE name='bake_stats'")[0]["fields"])
        self.assertTrue(next(f for f in fields if f["name"] == "photo")["protected"])
        outsider = self.user()
        self.assertNotEqual(200, self.request("/api/madre/shelf/photo/clear", {"id": bake["id"]}, outsider["token"])[0])

    def test_15_direct_record_api_never_sets_identity_family_owner_or_ledger(self):
        p = self.user(); before = self.sql("SELECT * FROM users WHERE id=?", (p["record"]["id"],))
        for key, value in [("family", self.f["id"]), ("family+", self.f["id"]), ("family-", self.f["id"]), ("membership_revision", 99), ("membership_revision+", 1), ("yandex_uid_hash", "a"*64)]:
            self.request("/api/collections/users/records/"+p["record"]["id"], {key: value}, p["token"], method="PATCH")
            self.assertEqual([{k: v for k, v in row.items() if k != "updated"} for row in before], [{k: v for k, v in row.items() if k != "updated"} for row in self.sql("SELECT * FROM users WHERE id=?", (p["record"]["id"],))])
        for c in ["family_invites", "family_members", "family_requests", "family_events", "family_limits"]:
            self.assertNotEqual(200, self.request("/api/collections/"+c+"/records", {}, self.o["token"])[0])
            self.assertEqual([], self.request("/api/collections/"+c+"/records", auth=self.o["token"], method="GET")[1].get("items", []))
        self.assertNotEqual(200, self.request("/api/collections/families/records/"+self.f["id"], {"owner": p["record"]["id"]}, self.o["token"], method="PATCH")[0])
        self.assertEqual(self.o["record"]["id"], self.get(self.o)[1]["owner"])

    def test_16_rate_limits_persist_restart_ignore_spoof_headers_and_uniform_failures(self):
        for n in range(30):
            self.assertEqual(404, self.preview("z"*16, web=n % 2 == 0, headers={"X-Forwarded-For": f"192.0.2.{n}"})[0])
        self.stop(); self.start()
        self.assertEqual((429, {"error": "rate_limited"}), self.preview(self.t))
        p = self.user()
        for _ in range(10): self.assertEqual(404, self.join(p, "z"*16)[0])
        self.assertEqual((429, {"error": "rate_limited"}), self.join(p, self.t))
        self.assertEqual(200, self.join(self.user(), self.t)[0])
        self.sql("UPDATE family_limits SET until=1")
        self.assertEqual(200, self.preview(self.t)[0])
        self.assertEqual([], self.sql("SELECT * FROM family_limits WHERE until=1"))

    def test_17_sql_failure_rolls_back_remove_rotate_transfer_join_and_approval(self):
        p = self.add()[0]
        tables = ["users", "families", "family_members", "family_invites", "family_requests", "family_events"]
        def snapshot(): return {t: self.sql("SELECT * FROM "+t+" ORDER BY id") for t in tables}
        def failure(trigger, call):
            before = snapshot(); self.sql(trigger)
            try:
                self.assertEqual((503, {"error": "unavailable"}), call())
                self.assertEqual(before, snapshot())
            finally: self.sql("DROP TRIGGER isolated_family_abort")
        failure("CREATE TRIGGER isolated_family_abort BEFORE UPDATE OF family ON users WHEN NEW.family != OLD.family BEGIN SELECT RAISE(ABORT, 'synthetic SQL failure'); END", lambda: self.remove(self.o, p))
        failure("CREATE TRIGGER isolated_family_abort BEFORE INSERT ON family_invites BEGIN SELECT RAISE(ABORT, 'synthetic SQL failure'); END", lambda: self.request("/api/madre/family/invite", {}, self.o["token"], v2=True))
        failure("CREATE TRIGGER isolated_family_abort BEFORE UPDATE OF owner ON families WHEN NEW.owner != OLD.owner BEGIN SELECT RAISE(ABORT, 'synthetic SQL failure'); END", lambda: self.request("/api/madre/family/owner", {"member_id": p["record"]["id"]}, self.o["token"], v2=True))
        q = self.user()
        failure("CREATE TRIGGER isolated_family_abort BEFORE INSERT ON family_events BEGIN SELECT RAISE(ABORT, 'synthetic SQL failure'); END", lambda: self.join(q, self.t))
        self.settings(self.o, approval_required=True); r = self.join(q, self.t)[1]["request_id"]
        failure("CREATE TRIGGER isolated_family_abort BEFORE UPDATE OF status ON family_requests WHEN NEW.status='joined' BEGIN SELECT RAISE(ABORT, 'synthetic SQL failure'); END", lambda: self.decide(self.o, r))

    def test_18_protocol_types_permissions_and_no_legacy_bypass(self):
        p = self.add()[0]
        before = self.sql("SELECT * FROM families WHERE id=?", (self.f["id"],))
        self.assertEqual(426, self.settings_bad_version("3")[0])
        self.assertEqual(426, self.settings_bad_version("")[0])
        q = self.user()
        self.assertEqual(426, self.request("/api/madre/family/create", {"name": "must not create"}, q["token"], headers={"X-Madre-Family-Version": ""})[0])
        self.assertEqual("", self.sql("SELECT family FROM users WHERE id=?", (q["record"]["id"],))[0]["family"])
        self.assertEqual(before, self.sql("SELECT * FROM families WHERE id=?", (self.f["id"],)))
        for payload in [{}, {"approval_required": "true"}, {"name": ""}, {"owner": p["record"]["id"]}, {"name": "x\n"}]:
            self.assertEqual(400, self.request("/api/madre/family", payload, self.o["token"], method="PATCH", v2=True)[0])
        self.assertEqual(403, self.settings(p, name="forged")[0])
        self.assertEqual(403, self.remove(p, self.o)[0])
        self.assertEqual(409, self.remove(self.o, self.o)[0])
        self.assertEqual(403, self.get(self.users[3])[0])  # email-only not v2 principal
        self.assertEqual(403, self.request("/api/madre/family/members/"+self.o["record"]["id"], {"display_name": "forged"}, p["token"], method="PATCH", v2=True)[0])
        self.assertEqual(200, self.request("/api/madre/family/members/"+p["record"]["id"], {"display_name": "Self label"}, p["token"], method="PATCH", v2=True)[0])

    def settings_bad_version(self, version):
        return self.request("/api/madre/family", {"name": "must not change"}, self.o["token"], method="PATCH", headers={"X-Madre-Family-Version": version})

    def test_19_legacy_email_code_registration_capacity_removal_and_deleted_guards(self):
        legacy_o = self.register(20001); legacy_p = self.register(20002)
        s, f = self.request("/api/madre/family/create", {"name": "Legacy synthetic"}, legacy_o["token"])
        self.assertEqual(200, s, f)
        self.assertEqual(200, self.request("/api/madre/family/join", {"code": f["invite_code"]}, legacy_p["token"])[0])
        # Bind explicitly to same records, then remove using v2 and try old code.
        type(self).serial += 1; oauth, _ = self.profile(type(self).serial)
        self.assertEqual(200, self.auth(oauth, legacy_o["token"])[0])
        type(self).serial += 1; oauth, _ = self.profile(type(self).serial)
        self.assertEqual(200, self.auth(oauth, legacy_p["token"])[0])
        self.assertEqual(200, self.remove(legacy_o, legacy_p, False)[0])
        self.assertEqual(400, self.request("/api/madre/family/join", {"code": f["invite_code"]}, legacy_p["token"])[0])
        self.assertEqual(200, self.request("/api/madre/family/leave", {}, legacy_o["token"])[0])
        self.assertEqual(400, self.request("/api/madre/family/join", {"code": f["invite_code"]}, legacy_o["token"])[0])
        # Fill a legacy shelf to eight and race another old email/code account.
        legacy_o = self.register(20003)
        s, f = self.request("/api/madre/family/create", {"name": "Legacy capacity"}, legacy_o["token"])
        for i in range(7):
            p = self.register(20100+i)
            self.assertEqual(200, self.request("/api/madre/family/join", {"code": f["invite_code"]}, p["token"])[0])
        self.assertEqual(400, self.request("/api/madre/family/join", {"code": f["invite_code"]}, self.register(20200)["token"])[0])
        self.assertEqual(409, self.request("/api/madre/family/leave", {}, legacy_o["token"])[0])

    def test_20_race_rotation_join_and_restore_remove_preserves_consistency(self):
        p = self.user(); gate = threading.Barrier(2)
        def join(): gate.wait(10); return self.join(p, self.t)
        def rotate(): gate.wait(10); return self.request("/api/madre/family/invite", {}, self.o["token"], v2=True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            a, b = pool.submit(join), pool.submit(rotate); jr, rr = a.result(), b.result()
        self.assertIn(jr[0], [200, 404]); self.assertEqual(200, rr[0])
        if jr[0] == 404: self.assertEqual("", self.sql("SELECT family FROM users WHERE id=?", (p["record"]["id"],))[0]["family"])
        else: self.assertEqual(self.f["id"], self.get(p)[1]["id"])
        self.t = self.rotate(self.o); q = self.user(); self.join(q, self.t); _, undo = self.remove(self.o, q, False)
        gate = threading.Barrier(2)
        def restore(): gate.wait(10); return self.restore(self.o, q, undo["restore_token"])
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            rs = [pool.submit(restore) for _ in range(2)]; results = [r.result() for r in rs]
        self.assertEqual([200, 409], sorted(s for s, _ in results), results)
        self.assertEqual(1, len(self.sql("SELECT * FROM family_members WHERE family=? AND user=?", (self.f["id"], q["record"]["id"]))))

    def test_21_pending_cannot_retarget_after_user_moves_or_removal(self):
        self.settings(self.o, approval_required=True); p = self.user(); r = self.join(p, self.t)[1]["request_id"]
        other = self.user(); self.create(other); t = self.rotate(other); self.join(p, t)
        self.assertEqual("declined", self.status(p, r)[1]["status"])
        self.assertEqual(409, self.decide(self.o, r)[0])
        self.assertNotEqual(self.f["id"], self.get(p)[1]["id"])
        q = self.user(); r = self.join(q, self.t)[1]["request_id"]; self.decide(self.o, r)
        self.remove(self.o, q, False)
        self.assertEqual("declined", self.status(q, r)[1]["status"])
        self.assertEqual(409, self.decide(self.o, r)[0])

    def test_22_revoke_deleted_family_undo_never_resurrects_and_full_restore_denied(self):
        p = self.add()[0]; _, undo = self.remove(self.o, p, False)
        self.add(7)
        self.assertEqual(409, self.restore(self.o, p, undo["restore_token"])[0])
        solo = self.user(); f = self.create(solo); t = self.rotate(solo); q = self.user(); self.join(q, t)
        _, undo = self.remove(solo, q, False)
        self.request("/api/madre/family", auth=solo["token"], method="DELETE", v2=True)
        self.assertEqual(403, self.restore(solo, q, undo["restore_token"])[0])
        self.assertTrue(self.sql("SELECT deleted_at FROM families WHERE id=?", (f["id"],))[0]["deleted_at"])

    def test_23_concurrent_preview_and_join_rate_budgets_are_atomic(self):
        gate = threading.Barrier(36)
        def call(_): gate.wait(10); return self.preview("z"*16)
        with concurrent.futures.ThreadPoolExecutor(max_workers=36) as pool: r = list(pool.map(call, range(36)))
        self.assertEqual(30, sum(s == 404 for s, _ in r), r)
        self.assertEqual(6, sum(s == 429 for s, _ in r), r)
        p = self.user(); gate = threading.Barrier(14)
        def join(_): gate.wait(10); return self.join(p, "z"*16)
        with concurrent.futures.ThreadPoolExecutor(max_workers=14) as pool: r = list(pool.map(join, range(14)))
        self.assertEqual(10, sum(s == 404 for s, _ in r), r)
        self.assertEqual(4, sum(s == 429 for s, _ in r), r)

    def test_24_key_missing_equal_uid_salt_or_rotated_fail_closed_without_mutation(self):
        before = self.sql("SELECT * FROM family_invites WHERE family=?", (self.f["id"],))
        for k in ["", runtime.SALT, runtime.PEPPER, "another_distinct_synthetic_key_32bytes"]:
            self.stop(); self.env["MADRE_INVITE_DERIVATION_KEY"] = k; self.start()
            self.assertEqual(503, self.request("/api/madre/family/invite", auth=self.o["token"], method="GET", v2=True)[0])
            if k != "another_distinct_synthetic_key_32bytes": self.assertEqual(503, self.request("/api/madre/family/invite", {}, self.o["token"], v2=True)[0])
            self.assertEqual(before, self.sql("SELECT * FROM family_invites WHERE family=?", (self.f["id"],)))
        self.stop(); self.env["MADRE_INVITE_DERIVATION_KEY"] = KEY; self.start()
        self.assertEqual(200, self.request("/api/madre/family/invite", auth=self.o["token"], method="GET", v2=True)[0])

    def test_25_sql_count_read_failure_never_grants_access_or_zero_count(self):
        p = self.user(); self.sql("ALTER TABLE family_members RENAME TO isolated_missing_ledger")
        try:
            self.assertEqual((503, {"error": "unavailable"}), self.join(p, self.t))
            self.assertEqual("", self.sql("SELECT family FROM users WHERE id=?", (p["record"]["id"],))[0]["family"])
            self.assertEqual(503, self.get(self.o)[0])
        finally: self.sql("ALTER TABLE isolated_missing_ledger RENAME TO family_members")

    def test_26_body_limit_and_exact_url_fail_closed(self):
        self.assertEqual(413, self.request("/api/madre/invite/"+self.t+"/join", raw=b"x"*65537, auth=self.o["token"], v2=True)[0])
        self.assertEqual(413, self.request("/api/madre/family/create", raw=b"x"*65537, auth=self.o["token"], v2=True)[0])
        self.assertEqual(400, self.preview(self.t+"?token=canary")[0])
        self.assertEqual(400, self.preview("%"+format(ord(self.t[0]), "02x")+self.t[1:])[0])
        self.assertEqual(404, self.preview(self.t+"x")[0])
        self.assertEqual(400, self.request("/api/madre/invite/"+self.t+"/join", {"switch_family": "true"}, self.user()["token"], v2=True)[0])

    def test_27_failed_atomic_switch_retains_old_family_and_every_bake(self):
        p = self.user(); old = self.create(p)
        before = {t: self.sql("SELECT * FROM "+t+" ORDER BY id") for t in ["users", "families", "family_members", "family_events", "bake_stats"]}
        self.sql("CREATE TRIGGER isolated_switch_abort BEFORE INSERT ON family_events BEGIN SELECT RAISE(ABORT, 'synthetic switch failure'); END")
        try:
            self.assertEqual((503, {"error": "unavailable"}), self.join(p, self.t, True))
            for t, b in before.items(): self.assertEqual(b, self.sql("SELECT * FROM "+t+" ORDER BY id"), t)
            self.assertEqual(old["id"], self.get(p)[1]["id"])
        finally: self.sql("DROP TRIGGER isolated_switch_abort")

    def test_28_mixed_legacy_and_v2_last_seat_race_has_one_winner(self):
        legacy = self.register(28001)
        _, f = self.request("/api/madre/family/create", {"name": "Mixed synthetic"}, legacy["token"])
        type(self).serial += 1; oauth, _ = self.profile(type(self).serial)
        self.assertEqual(200, self.auth(oauth, legacy["token"])[0])
        t = self.rotate(legacy)
        for _ in range(6): self.assertEqual(200, self.join(self.user(), t)[0])
        email = self.register(28002); p = self.user(); gate = threading.Barrier(2)
        def old(): gate.wait(10); return self.request("/api/madre/family/join", {"code": f["invite_code"]}, email["token"])
        def new(): gate.wait(10); return self.join(p, t)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            a, b = pool.submit(old), pool.submit(new); result = [a.result(), b.result()]
        self.assertEqual(1, sum(s == 200 for s, _ in result), result)
        self.assertTrue(all(s in [200, 400, 409] for s, _ in result), result)
        self.assertEqual(8, len(self.get(legacy)[1]["members"]))

    def test_29_remove_transfer_race_never_removes_current_owner(self):
        p = self.add()[0]; gate = threading.Barrier(2)
        def remove(): gate.wait(10); return self.remove(self.o, p, False)
        def transfer(): gate.wait(10); return self.request("/api/madre/family/owner", {"member_id": p["record"]["id"]}, self.o["token"], v2=True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            a, b = pool.submit(remove), pool.submit(transfer); result = [a.result(), b.result()]
        self.assertEqual(1, sum(s == 200 for s, _ in result), result)
        s, f = self.get(self.o); self.assertEqual(200, s, f)
        self.assertEqual(1, sum(m["role"] == "owner" for m in f["members"]))
        self.assertIn(f["owner"], [m["id"] for m in f["members"]])

    def test_30_concurrent_create_and_repeated_join_no_orphans_duplicate_events_or_proof(self):
        p = self.user(); gate = threading.Barrier(2)
        def create(): gate.wait(10); return self.request("/api/madre/family/create", {"name": "Concurrent synthetic"}, p["token"], v2=True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            a, b = pool.submit(create), pool.submit(create); result = [a.result(), b.result()]
        self.assertEqual([200, 409], sorted(s for s, _ in result), result)
        self.assertEqual(1, len(self.sql("SELECT * FROM families WHERE owner=?", (p["record"]["id"],))))
        q = self.user(); gate = threading.Barrier(8)
        def join(_): gate.wait(10); return self.join(q, self.t)
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool: result = list(pool.map(join, range(8)))
        self.assertEqual([200]*8, [s for s, _ in result], result)
        self.assertEqual(1, len(self.sql("SELECT * FROM family_members WHERE family=? AND user=?", (self.f["id"], q["record"]["id"]))))
        self.assertEqual(1, len(self.sql("SELECT * FROM family_events WHERE family=? AND user=? AND kind='joined'", (self.f["id"], q["record"]["id"]))))

    def test_31_bake_read_sql_failure_never_reports_zero_preview(self):
        self.sql("ALTER TABLE bake_stats RENAME TO isolated_missing_bakes")
        try:
            self.assertEqual((503, {"error": "unavailable"}), self.preview(self.t))
            self.assertEqual((503, {"error": "unavailable"}), self.get(self.o))
            # Tiny web preview does not query or expose bake data at all.
            self.assertEqual(200, self.preview(self.t, web=True)[0])
        finally: self.sql("ALTER TABLE isolated_missing_bakes RENAME TO bake_stats")

    def test_32_actual_protected_photo_read_is_revoked_for_old_family_not_author(self):
        import base64
        p = self.add()[0]
        s, bake = self.request("/api/collections/bake_stats/records", {"device_id": "synthetic-photo", "recipe_id": "synthetic", "recipe_name": "Synthetic", "bake_date": "2026-10-25 00:00:00.000Z", "client_event_id": p["record"]["id"], "family": self.f["id"]}, p["token"])
        self.assertEqual(200, s, bake)
        image = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aRz8AAAAASUVORK5CYII=")
        boundary = "MadreSyntheticMultipartBoundary"
        raw = (f'--{boundary}\r\nContent-Disposition: form-data; name="id"\r\n\r\n{bake["id"]}\r\n--{boundary}\r\nContent-Disposition: form-data; name="photo"; filename="synthetic.png"\r\nContent-Type: image/png\r\n\r\n').encode()+image+f'\r\n--{boundary}--\r\n'.encode()
        s, b = self.request("/api/madre/shelf/photo", auth=p["token"], raw=raw, headers={"Content-Type": "multipart/form-data; boundary="+boundary})
        self.assertEqual(200, s, b)
        self.assertTrue(b["photo"])
        _, ot = self.request("/api/files/token", {}, self.o["token"])
        _, pt = self.request("/api/files/token", {}, p["token"])
        self.pb_canaries.extend([ot["token"], pt["token"]])
        path = f'/api/files/bake_stats/{bake["id"]}/{b["photo"]}'
        self.assertEqual(200, self.request(path+"?token="+ot["token"], method="GET")[0])
        self.assertNotEqual(200, self.request(path, method="GET")[0])
        _, undo = self.remove(self.o, p, False)
        self.assertEqual(404, self.request(path+"?token="+ot["token"], method="GET")[0])
        self.assertEqual(200, self.request(path+"?token="+pt["token"], method="GET")[0])
        self.assertEqual(200, self.restore(self.o, p, undo["restore_token"])[0])
        self.assertEqual(200, self.request(path+"?token="+ot["token"], method="GET")[0])

    def test_33_consistent_synthetic_sqlite_backup_hash_and_isolated_restored_runtime(self):
        import shutil, time, urllib.request
        backup = self.work/"synthetic-consistent-backup"
        backup.mkdir()
        with sqlite3.connect(self.data/"data.db") as src, sqlite3.connect(backup/"data.db") as dst:
            src.backup(dst)
            self.assertEqual("ok", dst.execute("PRAGMA integrity_check").fetchone()[0])
        if (self.data/"storage").exists(): shutil.copytree(self.data/"storage", backup/"storage")
        digest = hashlib.sha256((backup/"data.db").read_bytes()).hexdigest()
        (self.work/"synthetic-backup.sha256").write_text(digest+"  data.db\n")
        restored = self.work/"synthetic-restored"
        shutil.copytree(backup, restored)
        self.assertEqual(digest, hashlib.sha256((restored/"data.db").read_bytes()).hexdigest())
        port = runtime.port(); base = f"http://127.0.0.1:{port}"
        child = subprocess.Popen([str(self.binary), "serve", f"--http=127.0.0.1:{port}", f"--dir={restored}", f"--hooksDir={self.hooks}", f"--migrationsDir={self.migrations}"], cwd=self.work, env=self.env, stdin=subprocess.DEVNULL, stdout=self.log, stderr=self.log)
        try:
            for _ in range(100):
                try:
                    if urllib.request.urlopen(base+"/api/health", timeout=2).status == 200: break
                except OSError: time.sleep(.05)
            else: self.fail("restored isolated PB readiness timeout")
            req = urllib.request.Request(base+"/api/madre/family/invite", headers={"Authorization": self.o["token"], "X-Madre-Family-Version": "2"})
            with urllib.request.urlopen(req, timeout=5) as response: restored_invite = json.load(response)
            self.assertEqual("https://kdnfx.space/madre/f/"+self.t, restored_invite["url"])
        finally: child.terminate(); child.wait(timeout=15)

    def test_34_pending_rejoin_remains_idempotent_when_family_fills(self):
        self.add(6); self.settings(self.o, approval_required=True)
        p, q = self.user(), self.user()
        a = self.join(p, self.t); b = self.join(q, self.t)
        self.assertEqual(200, self.decide(self.o, b[1]["request_id"])[0])
        self.assertEqual(a, self.join(p, self.t))
        self.assertEqual(409, self.decide(self.o, a[1]["request_id"])[0])
        self.assertEqual("pending", self.status(p, a[1]["request_id"])[1]["status"])

    def test_35_oversized_utf8_and_go_escaped_roster_rolls_back_before_commit(self):
        for count, label in [(400, "Ж"*80), (150, "<"*80), (180, "\u2028"*80)]:
            self._oversized_roster(count, label)

    def _oversized_roster(self, count, label):
        self.settings(self.o, approval_required=True)
        p = self.user(); self.join(p, self.t)
        template = self.sql("SELECT * FROM users WHERE id=?", (p["record"]["id"],))[0]
        invite = self.sql("SELECT id FROM family_invites WHERE family=? AND revoked_at=''", (self.f["id"],))[0]["id"]
        ids = []
        # Synthetic, transactionally inserted stress fixtures; no production DB.
        with sqlite3.connect(self.data/"data.db") as db:
            for n in range(count):
                uid = f"{8800000+n:015d}"; rid = f"{8900000+n:015d}"; ids.append((uid, rid))
                row = dict(template, id=uid, yandex_uid_hash="", name=label, tokenKey=f"synthetic-only-{uid}")
                columns = list(row)
                db.execute("INSERT INTO users ("+",".join('"'+k+'"' for k in columns)+") VALUES ("+",".join("?" for _ in columns)+")", [row[k] for k in columns])
                db.execute("INSERT INTO family_requests (id,family,user,invite,status,origin_family,source_revision,owner_revision,requested_at) VALUES (?,?,?,?,?,?,?,?,?)", (rid, self.f["id"], uid, invite, "pending", "", 0, 0, "2026-10-25 00:00:00.000Z"))
        before = self.sql("SELECT * FROM families WHERE id=?", (self.f["id"],))
        try:
            self.assertEqual((503, {"error": "unavailable"}), self.settings(self.o, name="Must roll back"))
            self.assertEqual(before, self.sql("SELECT * FROM families WHERE id=?", (self.f["id"],)))
        finally:
            with sqlite3.connect(self.data/"data.db") as db:
                db.executemany("DELETE FROM family_requests WHERE id=?", [(r,) for _, r in ids])
                db.executemany("DELETE FROM users WHERE id=?", [(u,) for u, _ in ids])

    def test_98_executable_rollback_retains_latest_records_invites_revocations_and_reupgrade(self):
        before = {t: self.sql("SELECT * FROM "+t+" ORDER BY id") for t in ["users", "families", "family_members", "family_invites", "family_requests", "family_events"]}
        self.stop()
        r = subprocess.run([str(self.binary), "migrate", "down", "1", f"--dir={self.data}", f"--hooksDir={self.hooks}", f"--migrationsDir={self.migrations}", "--automigrate=false"], input=b"y\n", stdout=self.log, stderr=self.log, env=self.env, cwd=self.work, timeout=15)
        self.assertEqual(0, r.returncode)
        self.assertEqual([], self.sql("SELECT * FROM _migrations WHERE file='1792886500_family_v2.js'"))
        self.start()
        for t, b in before.items(): self.assertEqual(b, self.sql("SELECT * FROM "+t+" ORDER BY id"), t)

    def test_99_full_db_wal_and_logs_contain_no_bearer_restore_invite_oauth_uid_keys(self):
        p = self.add()[0]; self.remove(self.o, p)
        for t in [self.t, "InvalidToken_1234"]:
            for path in ["/api/madre/invite/"+t, "/api/madre/web/invite/"+t, "/api/madre/invite/"+t+"/unknown"]:
                self.request(path+"?secret="+t, method="GET", v2=True, headers={"Referer": "https://example.invalid/"+t, "User-Agent": t})
        self.stop()
        data = bytearray((self.work/"pocketbase.log").read_bytes())
        for p in self.data.rglob("*"):
            if p.is_file() and p.name.endswith((".db", "-wal", "-shm")):
                data.extend(p.read_bytes())
                if p.suffix == ".db":
                    with sqlite3.connect(p) as db: data.extend("\n".join(db.iterdump()).encode())
        for marker in self.invite_canaries + self.restore_canaries + self.pb_canaries + self.oauth_canaries + self.uid_canaries + runtime.PROFILE_MARKERS + [KEY, runtime.SALT, runtime.PEPPER]:
            self.assertNotIn(marker.encode(), data, "sensitive canary persisted")
        self.start()

if __name__ == "__main__": unittest.main()
