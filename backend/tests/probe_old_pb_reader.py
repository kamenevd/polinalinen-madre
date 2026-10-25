"""Probe an exact old PB hook on the LATEST synthetic upgraded database.

Exit 2 is a demonstrated unsafe rollback, NOT a passed security test. This probe
never restores an old DB. The caller supplies a public-source old hook + digest;
the actual pinned PB binary, SQL, JWT, ACL and old hook stay unchanged.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import shutil
from pathlib import Path
from backend.tests.test_family_v2_integration import FamilyMembershipIntegration as Fixture


def probe(hook: Path, digest: str, source: str, evidence: Path) -> int:
    if hashlib.sha256(hook.read_bytes()).hexdigest() != digest:
        raise AssertionError("old reader hook checksum mismatch")
    Fixture.setUpClass()
    original_hooks = Fixture.hooks
    invite_code = getattr(Fixture, "invite_code")
    family_id = getattr(Fixture, "family_id")
    bake_id = getattr(Fixture, "bake_id")
    result = dict(reader_source=source, reader_hook_sha256=digest, stale_database_restore=False)
    try:
        owner, target = Fixture.users[0], Fixture.users[2]
        # Existing historical email family, password and code; add writes AFTER upgrade.
        token, _ = Fixture.profile(700001)
        assert Fixture.auth(token, owner["token"])[0] == 200
        assert Fixture.request("/api/madre/family/join", {"code": invite_code}, target["token"])[0] == 200
        label = "Synthetic latest write after upgrade"
        assert Fixture.request("/api/collections/users/records/"+owner["record"]["id"],
            {"name": label}, owner["token"], method="PATCH")[0] == 200
        assert Fixture.remove(owner, target, False)[0] == 200
        result["candidate_removed_code_rejoin_status"] = Fixture.request(
            "/api/madre/family/join", {"code": invite_code}, target["token"])[0]
        assert result["candidate_removed_code_rejoin_status"] != 200
        before = Fixture.sql("SELECT * FROM users ORDER BY id")
        revoked = Fixture.sql("SELECT * FROM family_members WHERE user=? AND family=?",
            (target["record"]["id"], family_id))[0]
        assert revoked["status"] == "removed" and revoked["removed_once"]
        Fixture.stop()
        old = Fixture.work / "exact-old-reader-hooks"
        old.mkdir()
        shutil.copy2(hook, old / "madre_family.pb.js")
        shutil.copy2(original_hooks / "madre_stats.pb.js", old)
        Fixture.hooks = old
        Fixture.start()  # Same latest data directory; all additive migrations already present.
        result["latest_rows_preserved_at_old_startup"] = before == Fixture.sql("SELECT * FROM users ORDER BY id")
        status, account = Fixture.request("/api/collections/users/auth-with-password", {
            "identity": "synthetic-0@example.invalid", "password": "SyntheticOnlyPassword_123!"})
        result["old_password_latest_label"] = status == 200 and account["record"]["name"] == label
        status, shelf = Fixture.request("/api/collections/bake_stats/records", auth=target["token"], method="GET")
        result["removed_reader_cannot_see_family_bake"] = status == 200 and bake_id not in [b["id"] for b in shelf["items"]]
        status, _ = Fixture.request("/api/madre/family/join", {"code": invite_code}, target["token"])
        result["old_code_rejoin_status"] = status
        result["revocation_preserved_after_old_write"] = Fixture.sql("SELECT family FROM users WHERE id=?",
            (target["record"]["id"],))[0]["family"] == ""
        # Restart the candidate on the latest DB, never rewind its state.
        Fixture.stop(); Fixture.hooks = original_hooks; Fixture.start()
        if not result["revocation_preserved_after_old_write"]:
            assert Fixture.remove(owner, target, False)[0] == 200
        result["candidate_rerevocation_verified"] = Fixture.sql("SELECT family FROM users WHERE id=?",
            (target["record"]["id"],))[0]["family"] == ""
        result["latest_label_survives_return"] = Fixture.sql("SELECT name FROM users WHERE id=?",
            (owner["record"]["id"],))[0]["name"] == label
        result["integrity"] = Fixture.sql("PRAGMA integrity_check")[0]["integrity_check"]
        safe = all(result[k] for k in ("latest_rows_preserved_at_old_startup", "old_password_latest_label",
            "removed_reader_cannot_see_family_bake", "revocation_preserved_after_old_write",
            "candidate_rerevocation_verified", "latest_label_survives_return")) and result["integrity"] == "ok"
        result["status"] = "passed" if safe else "rollback_blocked"
        result["isolated_evidence_directory"] = str(Fixture.work)
        evidence.parent.mkdir(parents=True, exist_ok=True)
        evidence.write_text(json.dumps(result, indent=2)+"\n")
        print(json.dumps(result, indent=2))
        return 0 if safe else 2
    finally:
        Fixture.hooks = original_hooks
        Fixture.doClassCleanups()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reader-hook", type=Path, required=True)
    parser.add_argument("--reader-sha256", required=True)
    parser.add_argument("--reader-source", required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    raise SystemExit(probe(args.reader_hook, args.reader_sha256, args.reader_source, args.evidence))