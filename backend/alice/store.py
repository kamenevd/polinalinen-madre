"""Short SQLite transactions for installation, OAuth, lease, intent, and receipt state."""

from __future__ import annotations

import json
import sqlite3
from dataclasses import dataclass
from typing import Any

from security import canon_uuid, const_eq_hash, new_uuid, sha256_text


def purge(conn: sqlite3.Connection, now_ms: int, receipt_ttl_ms: int, snapshot_ttl_ms: int) -> None:
    conn.execute(
        "DELETE FROM receipts WHERE received_at < ?",
        (now_ms - receipt_ttl_ms,),
    )
    conn.execute(
        "DELETE FROM snapshots WHERE received_at < ?",
        (now_ms - snapshot_ttl_ms,),
    )
    conn.execute("DELETE FROM challenges WHERE expires_at < ?", (now_ms - snapshot_ttl_ms,))
    # window column is a minute index (now_ms // 60_000). Keep two hours.
    # Do not compare that index to now_ms // window_ms: a 10-minute key is ~10x smaller
    # and would be deleted as if it were already stale, including the current bucket.
    conn.execute("DELETE FROM rate_buckets WHERE window < ?", ((now_ms // 60_000) - 120,))


def _minute_index(now_ms: int) -> int:
    return int(now_ms) // 60_000


def _window_start(now_ms: int, window_ms: int) -> int:
    span = max(1, (int(window_ms) + 59_999) // 60_000)
    return _minute_index(now_ms) - span + 1


def _rate_sum(conn: sqlite3.Connection, bucket: str, now_ms: int, window_ms: int) -> int:
    minute = _minute_index(now_ms)
    row = conn.execute(
        "SELECT COALESCE(SUM(count), 0) AS n FROM rate_buckets WHERE bucket=? AND window>=? AND window<=?",
        (bucket, _window_start(now_ms, window_ms), minute),
    ).fetchone()
    return int(row["n"])


def rate_allow(conn: sqlite3.Connection, bucket: str, now_ms: int, window_ms: int, limit: int) -> bool:
    minute = _minute_index(now_ms)
    conn.execute(
        "INSERT INTO rate_buckets(bucket, window, count) VALUES(?,?,1) "
        "ON CONFLICT(bucket, window) DO UPDATE SET count = count + 1",
        (bucket, minute),
    )
    return _rate_sum(conn, bucket, now_ms, window_ms) <= limit


def rate_failures(conn: sqlite3.Connection, bucket: str, now_ms: int, window_ms: int) -> int:
    """Current failure count across the window. Does not increment."""
    return _rate_sum(conn, bucket, now_ms, window_ms)


def rate_add_failure(conn: sqlite3.Connection, bucket: str, now_ms: int, window_ms: int) -> None:
    minute = _minute_index(now_ms)
    conn.execute(
        "INSERT INTO rate_buckets(bucket, window, count) VALUES(?,?,1) "
        "ON CONFLICT(bucket, window) DO UPDATE SET count = count + 1",
        (bucket, minute),
    )


def count_installations(conn: sqlite3.Connection) -> int:
    return int(conn.execute("SELECT COUNT(*) AS n FROM installations").fetchone()["n"])


def enroll(conn: sqlite3.Connection, credential_hash: str, now_ms: int) -> str:
    row = conn.execute(
        "SELECT id FROM installations WHERE credential_hash=?",
        (credential_hash,),
    ).fetchone()
    if row:
        return str(row["id"])
    installation_id = new_uuid()
    conn.execute(
        "INSERT INTO installations(id, credential_hash, created_at) VALUES(?,?,?)",
        (installation_id, credential_hash, now_ms),
    )
    return installation_id


def installation_by_hash(conn: sqlite3.Connection, credential_hash: str) -> sqlite3.Row | None:
    return conn.execute(
        # One SQL snapshot: never read the epoch later than authentication.
        "SELECT i.*, COALESCE(e.epoch,0) AS authority_epoch, y.uid AS binding_uid "
        "FROM installations i LEFT JOIN yandex_epochs e ON e.installation_id=i.id "
        "LEFT JOIN yandex_bindings y ON y.installation_id=i.id WHERE i.credential_hash=?",
        (credential_hash,),
    ).fetchone()


def installation_by_id(conn: sqlite3.Connection, installation_id: str) -> sqlite3.Row | None:
    return conn.execute(
        "SELECT * FROM installations WHERE id=?",
        (installation_id,),
    ).fetchone()


def _invalidate_yandex_authority(conn: sqlite3.Connection, installation_id: str) -> None:
    # Covers logout, disconnect AND selection A -> B -> A (not just value equality).
    conn.execute("INSERT INTO yandex_epochs(installation_id,epoch) VALUES(?,1) "
                 "ON CONFLICT(installation_id) DO UPDATE SET epoch=epoch+1", (installation_id,))
    conn.execute("DELETE FROM yandex_voice_consents WHERE installation_id=?", (installation_id,))


def _revoke_installation(conn: sqlite3.Connection, installation_id: str, now_ms: int) -> None:
    _invalidate_yandex_authority(conn, installation_id)
    # Prevent old codes/challenges resurrecting authority after logout/reselect.
    conn.execute("UPDATE auth_codes SET used_at=? WHERE used_at IS NULL AND challenge_id IN "
                 "(SELECT id FROM challenges WHERE installation_id=?)", (now_ms, installation_id))
    conn.execute("UPDATE challenges SET consumed_at=? WHERE installation_id=? AND consumed_at IS NULL",
                 (now_ms, installation_id))
    conn.execute(
        "UPDATE grants SET revoked_at=? WHERE installation_id=? AND revoked_at IS NULL",
        (now_ms, installation_id),
    )
    conn.execute(
        "UPDATE access_tokens SET revoked_at=? WHERE revoked_at IS NULL AND grant_id IN "
        "(SELECT id FROM grants WHERE installation_id=?)",
        (now_ms, installation_id),
    )
    conn.execute(
        "UPDATE refresh_tokens SET revoked_at=? WHERE revoked_at IS NULL AND grant_id IN "
        "(SELECT id FROM grants WHERE installation_id=?)",
        (now_ms, installation_id),
    )
    conn.execute(
        "UPDATE pending_intents SET consumed_at=? WHERE consumed_at IS NULL AND grant_id IN "
        "(SELECT id FROM grants WHERE installation_id=?)",
        (now_ms, installation_id),
    )
    conn.execute(
        "UPDATE commands SET state='killed' WHERE installation_id=? AND state IN ('pending','dispatched')",
        (installation_id,),
    )
    conn.execute("DELETE FROM snapshots WHERE installation_id=?", (installation_id,))
    conn.execute("DELETE FROM leases WHERE installation_id=?", (installation_id,))


def select_bake(
    conn: sqlite3.Connection,
    installation_id: str,
    bake_key: str,
    generation: str,
    now_ms: int,
) -> None:
    row = installation_by_id(conn, installation_id)
    if row is None:
        raise KeyError("installation")
    bake_key = canon_uuid(bake_key)
    generation = canon_uuid(generation)
    same = row["selected_bake_key"] == bake_key and row["selected_generation"] == generation
    if same and row["selected_bake_key"] is not None:
        return
    if row["selected_bake_key"] is not None:
        _revoke_installation(conn, installation_id, now_ms)
    clear_retired(conn, installation_id)
    conn.execute(
        "UPDATE installations SET selected_bake_key=?, selected_generation=?, selected_at=? WHERE id=?",
        (bake_key, generation, now_ms, installation_id),
    )


def disconnect(conn: sqlite3.Connection, installation_id: str, now_ms: int) -> None:
    _revoke_installation(conn, installation_id, now_ms)
    clear_retired(conn, installation_id)
    conn.execute(
        "UPDATE installations SET selected_bake_key=NULL, selected_generation=NULL, selected_at=NULL WHERE id=?",
        (installation_id,),
    )


def create_challenge(
    conn: sqlite3.Connection,
    *,
    code: str,
    client_id: str,
    redirect_uri: str,
    scope: str,
    state: str,
    browser_token: str,
    now_ms: int,
    ttl_ms: int,
) -> str:
    challenge_id = new_uuid()
    conn.execute(
        "INSERT INTO challenges(id, code_hash, client_id, redirect_uri, scope, state, browser_hash, "
        "created_at, expires_at) VALUES(?,?,?,?,?,?,?,?,?)",
        (
            challenge_id,
            sha256_text(code),
            client_id,
            redirect_uri,
            scope,
            state,
            sha256_text(browser_token),
            now_ms,
            now_ms + ttl_ms,
        ),
    )
    return challenge_id


def challenge_by_id(conn: sqlite3.Connection, challenge_id: str) -> sqlite3.Row | None:
    return conn.execute("SELECT * FROM challenges WHERE id=?", (challenge_id,)).fetchone()


def challenge_by_code_hash(conn: sqlite3.Connection, code_hash: str) -> sqlite3.Row | None:
    return conn.execute(
        "SELECT * FROM challenges WHERE code_hash=?",
        (code_hash,),
    ).fetchone()


def challenge_by_browser(conn: sqlite3.Connection, browser_hash: str) -> sqlite3.Row | None:
    return conn.execute(
        "SELECT * FROM challenges WHERE browser_hash=? ORDER BY created_at DESC LIMIT 1",
        (browser_hash,),
    ).fetchone()


def count_challenges(conn: sqlite3.Connection) -> int:
    return int(conn.execute("SELECT COUNT(*) AS n FROM challenges").fetchone()["n"])


def approve_challenge(
    conn: sqlite3.Connection,
    *,
    challenge_id: str,
    code_hash: str,
    installation_id: str,
    bake_key: str,
    generation: str,
    expected_epoch: int,
    now_ms: int,
) -> bool:
    # The phone's initial auth snapshot is an optimistic fence. Check it inside
    # this same write transaction, before attaching a previously unowned challenge.
    inst = installation_by_id(conn, installation_id)
    epoch = conn.execute("SELECT epoch FROM yandex_epochs WHERE installation_id=?", (installation_id,)).fetchone()
    if (inst is None or (int(epoch[0]) if epoch else 0) != expected_epoch
            or inst["selected_bake_key"] != canon_uuid(bake_key)
            or inst["selected_generation"] != canon_uuid(generation)):
        return False
    row = challenge_by_id(conn, challenge_id)
    if row is None:
        return False
    if row["consumed_at"] is not None or row["approved_at"] is not None:
        return False
    if int(row["expires_at"]) <= now_ms:
        return False
    if not const_eq_hash(row["code_hash"], code_hash):
        return False
    cur = conn.execute(
        "UPDATE challenges SET approved_at=?, installation_id=?, bake_key=?, selection_generation=? "
        "WHERE id=? AND approved_at IS NULL AND consumed_at IS NULL",
        (now_ms, installation_id, canon_uuid(bake_key), canon_uuid(generation), challenge_id),
    )
    return cur.rowcount == 1


def issue_auth_code(
    conn: sqlite3.Connection,
    *,
    challenge_id: str,
    browser_hash: str,
    code: str,
    now_ms: int,
    ttl_ms: int,
) -> sqlite3.Row | None:
    row = challenge_by_id(conn, challenge_id)
    if row is None or not const_eq_hash(row["browser_hash"], browser_hash):
        return None
    if row["approved_at"] is None or row["consumed_at"] is not None:
        return None
    if int(row["expires_at"]) <= now_ms:
        return None
    if not row["installation_id"] or not row["bake_key"] or not row["selection_generation"]:
        return None
    inst = installation_by_id(conn, row["installation_id"])
    if inst is None:
        return None
    if inst["selected_bake_key"] != row["bake_key"] or inst["selected_generation"] != row["selection_generation"]:
        return None
    cur = conn.execute(
        "UPDATE challenges SET consumed_at=? WHERE id=? AND consumed_at IS NULL",
        (now_ms, challenge_id),
    )
    if cur.rowcount != 1:
        return None
    conn.execute(
        "INSERT INTO auth_codes(code_hash, challenge_id, expires_at, redirect_uri, client_id, scope, state) "
        "VALUES(?,?,?,?,?,?,?)",
        (
            sha256_text(code),
            challenge_id,
            now_ms + ttl_ms,
            row["redirect_uri"],
            row["client_id"],
            row["scope"],
            row["state"],
        ),
    )
    return row


@dataclass
class TokenPair:
    access_token: str
    refresh_token: str
    expires_in: int
    grant_id: str


def redeem_auth_code(
    conn: sqlite3.Connection,
    *,
    code_hash: str,
    client_id: str,
    redirect_uri: str,
    now_ms: int,
    access_token: str,
    refresh_token: str,
    access_ttl_s: int,
    grant_ttl_ms: int,
) -> TokenPair | None:
    row = conn.execute("SELECT * FROM auth_codes WHERE code_hash=?", (code_hash,)).fetchone()
    if row is None:
        return None
    if row["client_id"] != client_id or row["redirect_uri"] != redirect_uri:
        return None
    if row["used_at"] is not None:
        if row["grant_id"]:
            _revoke_grant(conn, row["grant_id"], now_ms)
        return None
    if int(row["expires_at"]) <= now_ms:
        conn.execute("UPDATE auth_codes SET used_at=? WHERE code_hash=?", (now_ms, code_hash))
        return None
    challenge = challenge_by_id(conn, row["challenge_id"])
    if challenge is None or not challenge["installation_id"]:
        return None
    inst = installation_by_id(conn, challenge["installation_id"])
    if inst is None:
        return None
    if inst["selected_bake_key"] != challenge["bake_key"] or inst["selected_generation"] != challenge["selection_generation"]:
        conn.execute("UPDATE auth_codes SET used_at=? WHERE code_hash=?", (now_ms, code_hash))
        return None
    # UID shortcut codes retain their exact authority provenance. Legacy pairing
    # has no such row and keeps its existing semantics.
    from yandex_store import code_authority_valid
    if not code_authority_valid(conn, challenge, row, now_ms):
        conn.execute("UPDATE auth_codes SET used_at=? WHERE code_hash=?", (now_ms, code_hash))
        return None
    grant_id = new_uuid()
    family = new_uuid()
    conn.execute(
        "INSERT INTO grants(id, installation_id, bake_key, selection_generation, created_at, expires_at, refresh_family) "
        "VALUES(?,?,?,?,?,?,?)",
        (
            grant_id,
            challenge["installation_id"],
            challenge["bake_key"],
            challenge["selection_generation"],
            now_ms,
            now_ms + grant_ttl_ms,
            family,
        ),
    )
    conn.execute(
        "INSERT INTO access_tokens(token_hash, grant_id, expires_at) VALUES(?,?,?)",
        (sha256_text(access_token), grant_id, now_ms + access_ttl_s * 1000),
    )
    conn.execute(
        "INSERT INTO refresh_tokens(token_hash, grant_id, family, expires_at) VALUES(?,?,?,?)",
        (sha256_text(refresh_token), grant_id, family, now_ms + grant_ttl_ms),
    )
    conn.execute(
        "UPDATE auth_codes SET used_at=?, grant_id=? WHERE code_hash=?",
        (now_ms, grant_id, code_hash),
    )
    return TokenPair(access_token, refresh_token, access_ttl_s, grant_id)


def _revoke_grant(conn: sqlite3.Connection, grant_id: str, now_ms: int) -> None:
    cur = conn.execute("UPDATE grants SET revoked_at=? WHERE id=? AND revoked_at IS NULL", (now_ms, grant_id))
    if cur.rowcount:
        installation = conn.execute("SELECT installation_id FROM grants WHERE id=?", (grant_id,)).fetchone()
        _invalidate_yandex_authority(conn, installation[0])
    conn.execute(
        "UPDATE access_tokens SET revoked_at=? WHERE grant_id=? AND revoked_at IS NULL",
        (now_ms, grant_id),
    )
    conn.execute(
        "UPDATE refresh_tokens SET revoked_at=? WHERE grant_id=? AND revoked_at IS NULL",
        (now_ms, grant_id),
    )
    conn.execute(
        "UPDATE pending_intents SET consumed_at=? WHERE grant_id=? AND consumed_at IS NULL",
        (now_ms, grant_id),
    )
    _kill_grant_commands(conn, grant_id)


def grant_live(conn: sqlite3.Connection, grant_id: str, now_ms: int) -> bool:
    row = conn.execute(
        "SELECT revoked_at, expires_at FROM grants WHERE id=?",
        (grant_id,),
    ).fetchone()
    if row is None or row["revoked_at"] is not None:
        return False
    return int(row["expires_at"]) > now_ms


def _kill_grant_commands(conn: sqlite3.Connection, grant_id: str) -> None:
    """Stop undelivered and in-flight commands. Do not delete receipts or rewind acked state."""
    conn.execute(
        "UPDATE commands SET state='killed' WHERE state IN ('pending','dispatched') "
        "AND intent_id IN (SELECT id FROM pending_intents WHERE grant_id=?)",
        (grant_id,),
    )


def clear_retired(conn: sqlite3.Connection, installation_id: str) -> None:
    conn.execute("DELETE FROM retired_connections WHERE installation_id=?", (installation_id,))


def connection_retired(conn: sqlite3.Connection, installation_id: str, connection_id: str) -> bool:
    row = conn.execute(
        "SELECT 1 FROM retired_connections WHERE installation_id=? AND connection_id=?",
        (installation_id, connection_id),
    ).fetchone()
    return row is not None


def _retire_connection(conn: sqlite3.Connection, installation_id: str, connection_id: str, now_ms: int) -> None:
    conn.execute(
        "INSERT INTO retired_connections(installation_id, connection_id, retired_at) VALUES(?,?,?) "
        "ON CONFLICT(installation_id, connection_id) DO NOTHING",
        (installation_id, connection_id, now_ms),
    )


def _canonical_state(payload: dict[str, Any]) -> tuple:
    """Identity of a controller snapshot. Remaining time and capture clock are not identity."""
    return (
        payload.get("bake_key"),
        payload.get("recipe_id"),
        payload.get("recipe_revision"),
        payload.get("recipe_name"),
        payload.get("portions"),
        payload.get("step_index"),
        payload.get("step_count"),
        payload.get("step_title"),
        payload.get("step_text"),
        payload.get("next_step_title"),
        payload.get("next_step_text"),
        payload.get("quantities"),
        bool(payload.get("paused")),
        bool(payload.get("ended")),
        int(payload["revision"]) if isinstance(payload.get("revision"), int) and not isinstance(payload.get("revision"), bool) else -1,
        bool(payload.get("control_ready")),
    )


def _command_grant_live(conn: sqlite3.Connection, row: sqlite3.Row, now_ms: int) -> bool:
    intent_id = row["intent_id"]
    if not intent_id:
        return False
    intent = conn.execute(
        "SELECT grant_id FROM pending_intents WHERE id=?",
        (intent_id,),
    ).fetchone()
    if intent is None or not intent["grant_id"]:
        return False
    return grant_live(conn, str(intent["grant_id"]), now_ms)


def rotate_refresh(
    conn: sqlite3.Connection,
    *,
    refresh_hash: str,
    now_ms: int,
    access_token: str,
    refresh_token: str,
    access_ttl_s: int,
    grant_ttl_ms: int,
) -> TokenPair | None:
    row = conn.execute("SELECT * FROM refresh_tokens WHERE token_hash=?", (refresh_hash,)).fetchone()
    if row is None:
        return None
    grant = conn.execute("SELECT * FROM grants WHERE id=?", (row["grant_id"],)).fetchone()
    if grant is None:
        return None
    if row["rotated_at"] is not None or row["revoked_at"] is not None or grant["revoked_at"] is not None:
        _revoke_grant(conn, grant["id"], now_ms)
        return None
    if int(row["expires_at"]) <= now_ms or int(grant["expires_at"]) <= now_ms:
        _revoke_grant(conn, grant["id"], now_ms)
        return None
    family = row["family"]
    conn.execute(
        "UPDATE refresh_tokens SET rotated_at=? WHERE token_hash=? AND rotated_at IS NULL",
        (now_ms, refresh_hash),
    )
    conn.execute(
        "UPDATE access_tokens SET revoked_at=? WHERE grant_id=? AND revoked_at IS NULL",
        (now_ms, grant["id"]),
    )
    conn.execute(
        "INSERT INTO access_tokens(token_hash, grant_id, expires_at) VALUES(?,?,?)",
        (sha256_text(access_token), grant["id"], now_ms + access_ttl_s * 1000),
    )
    conn.execute(
        "INSERT INTO refresh_tokens(token_hash, grant_id, family, expires_at) VALUES(?,?,?,?)",
        (sha256_text(refresh_token), grant["id"], family, min(int(grant["expires_at"]), now_ms + grant_ttl_ms)),
    )
    return TokenPair(access_token, refresh_token, access_ttl_s, grant["id"])


def grant_by_access(conn: sqlite3.Connection, token_hash: str, now_ms: int) -> sqlite3.Row | None:
    row = conn.execute(
        "SELECT a.expires_at AS access_expires, a.revoked_at AS access_revoked, g.* "
        "FROM access_tokens a JOIN grants g ON g.id=a.grant_id WHERE a.token_hash=?",
        (token_hash,),
    ).fetchone()
    if row is None:
        return None
    if row["access_revoked"] is not None or row["revoked_at"] is not None:
        return None
    if int(row["access_expires"]) <= now_ms or int(row["expires_at"]) <= now_ms:
        return None
    inst = installation_by_id(conn, row["installation_id"])
    if inst is None:
        return None
    if inst["selected_bake_key"] != row["bake_key"] or inst["selected_generation"] != row["selection_generation"]:
        return None
    return row


def save_snapshot(
    conn: sqlite3.Connection,
    *,
    installation_id: str,
    generation: str,
    connection_id: str,
    payload: dict[str, Any],
    now_ms: int,
    clock_ok: bool,
    boot_id: str,
) -> str | None:
    """Store snapshot and refresh lease. Returns previous connection id if replaced.

    A retired connection cannot restore the lease or kill the current controller's
    commands. Same bake+generation revision is monotonic. Same revision with a
    different canonical state is rejected on every connection, including the one
    that wrote the current snapshot. Remaining time and captured_at may change.
    """
    inst = installation_by_id(conn, installation_id)
    if inst is None or not inst["selected_bake_key"] or not inst["selected_generation"]:
        return None
    if canon_uuid(generation) != inst["selected_generation"]:
        raise ValueError("generation_mismatch")
    if payload["bake_key"] != inst["selected_bake_key"]:
        raise ValueError("bake_mismatch")
    if connection_retired(conn, installation_id, connection_id):
        return None
    existing = snapshot_row(conn, installation_id)
    if (
        existing is not None
        and existing["bake_key"] == payload["bake_key"]
        and existing["selection_generation"] == canon_uuid(generation)
    ):
        old = json.loads(existing["payload_json"])
        old_rev = int(old["revision"])
        new_rev = int(payload["revision"])
        if new_rev < old_rev:
            raise ValueError("stale_revision")
        if new_rev == old_rev and _canonical_state(old) != _canonical_state(payload):
            raise ValueError("contradictory_snapshot")
    prev = conn.execute(
        "SELECT connection_id FROM leases WHERE installation_id=?",
        (installation_id,),
    ).fetchone()
    previous = str(prev["connection_id"]) if prev else None
    retire_ids: set[str] = set()
    if previous and previous != connection_id:
        retire_ids.add(previous)
    if existing is not None and str(existing["connection_id"]) != connection_id:
        retire_ids.add(str(existing["connection_id"]))
    for old_id in retire_ids:
        _retire_connection(conn, installation_id, old_id, now_ms)
        conn.execute(
            "UPDATE commands SET state='killed' WHERE installation_id=? AND connection_id=? "
            "AND state IN ('pending','dispatched')",
            (installation_id, old_id),
        )
    conn.execute(
        "INSERT INTO snapshots(installation_id, bake_key, selection_generation, payload_json, received_at, "
        "connection_id, clock_sane) VALUES(?,?,?,?,?,?,?) "
        "ON CONFLICT(installation_id) DO UPDATE SET bake_key=excluded.bake_key, "
        "selection_generation=excluded.selection_generation, payload_json=excluded.payload_json, "
        "received_at=excluded.received_at, connection_id=excluded.connection_id, clock_sane=excluded.clock_sane",
        (
            installation_id,
            payload["bake_key"],
            canon_uuid(generation),
            json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
            now_ms,
            connection_id,
            1 if clock_ok else 0,
        ),
    )
    conn.execute(
        "INSERT INTO leases(installation_id, connection_id, selection_generation, bake_key, last_poll_at, boot_id) "
        "VALUES(?,?,?,?,?,?) ON CONFLICT(installation_id) DO UPDATE SET connection_id=excluded.connection_id, "
        "selection_generation=excluded.selection_generation, bake_key=excluded.bake_key, "
        "last_poll_at=excluded.last_poll_at, boot_id=excluded.boot_id",
        (
            installation_id,
            connection_id,
            canon_uuid(generation),
            payload["bake_key"],
            now_ms,
            boot_id,
        ),
    )
    return previous if previous and previous != connection_id else None


def touch_lease(conn: sqlite3.Connection, installation_id: str, connection_id: str, now_ms: int, boot_id: str) -> None:
    conn.execute(
        "UPDATE leases SET last_poll_at=? WHERE installation_id=? AND connection_id=? AND boot_id=?",
        (now_ms, installation_id, connection_id, boot_id),
    )


def lease_row(conn: sqlite3.Connection, installation_id: str) -> sqlite3.Row | None:
    return conn.execute("SELECT * FROM leases WHERE installation_id=?", (installation_id,)).fetchone()


def snapshot_row(conn: sqlite3.Connection, installation_id: str) -> sqlite3.Row | None:
    return conn.execute("SELECT * FROM snapshots WHERE installation_id=?", (installation_id,)).fetchone()


def linked_grant_exists(conn: sqlite3.Connection, installation_id: str, now_ms: int) -> bool:
    inst = installation_by_id(conn, installation_id)
    if inst is None or not inst["selected_bake_key"] or not inst["selected_generation"]:
        return False
    row = conn.execute(
        "SELECT id FROM grants WHERE installation_id=? AND bake_key=? AND selection_generation=? "
        "AND revoked_at IS NULL AND expires_at>?",
        (installation_id, inst["selected_bake_key"], inst["selected_generation"], now_ms),
    ).fetchone()
    return row is not None


def take_command(
    conn: sqlite3.Connection,
    *,
    installation_id: str,
    connection_id: str,
    generation: str,
    boot_id: str,
    now_ms: int,
) -> dict[str, Any] | None:
    row = None
    for _ in range(8):
        row = conn.execute(
            "SELECT * FROM commands WHERE installation_id=? AND connection_id=? AND selection_generation=? "
            "AND boot_id=? AND state='pending' AND expires_at>? ORDER BY issued_at LIMIT 1",
            (installation_id, connection_id, canon_uuid(generation), boot_id, now_ms),
        ).fetchone()
        if row is None:
            return None
        if not _command_grant_live(conn, row, now_ms):
            conn.execute(
                "UPDATE commands SET state='killed' WHERE id=? AND state='pending'",
                (row["id"],),
            )
            continue
        cur = conn.execute(
            "UPDATE commands SET state='dispatched', dispatched_at=? WHERE id=? AND state='pending'",
            (now_ms, row["id"]),
        )
        if cur.rowcount != 1:
            continue
        return command_json(row)
    return None


def command_json(row: sqlite3.Row) -> dict[str, Any]:
    return {
        "id": row["id"],
        "operation": row["operation"],
        "bake_key": row["bake_key"],
        "recipe_revision": row["recipe_revision"],
        "selection_generation": row["selection_generation"],
        "connection_id": row["connection_id"],
        "expected_revision": int(row["expected_revision"]),
        "expected_step": int(row["expected_step"]),
        "expected_paused": bool(row["expected_paused"]),
        "issued_at": int(row["issued_at"]),
        "expires_at": int(row["expires_at"]),
    }


def command_count(conn: sqlite3.Connection, installation_id: str | None = None) -> int:
    if installation_id is None:
        return int(conn.execute("SELECT COUNT(*) AS n FROM commands").fetchone()["n"])
    return int(
        conn.execute(
            "SELECT COUNT(*) AS n FROM commands WHERE installation_id=?",
            (installation_id,),
        ).fetchone()["n"]
    )


def inflight_exists(conn: sqlite3.Connection, installation_id: str, now_ms: int) -> bool:
    row = conn.execute(
        "SELECT id FROM commands WHERE installation_id=? AND state IN ('pending','dispatched') AND expires_at>?",
        (installation_id, now_ms),
    ).fetchone()
    return row is not None


def put_intent(conn: sqlite3.Connection, fields: dict[str, Any]) -> str:
    intent_id = new_uuid()
    conn.execute(
        "UPDATE pending_intents SET consumed_at=? WHERE grant_id=? AND session_id=? AND consumed_at IS NULL",
        (fields["created_at"], fields["grant_id"], fields["session_id"]),
    )
    conn.execute(
        "INSERT INTO pending_intents(id, grant_id, session_id, operation, recipe_id, recipe_revision, "
        "step_index, bake_key, revision, selection_generation, expected_paused, created_at, expires_at, boot_id) "
        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (
            intent_id,
            fields["grant_id"],
            fields["session_id"],
            fields["operation"],
            fields["recipe_id"],
            fields["recipe_revision"],
            fields["step_index"],
            fields["bake_key"],
            fields["revision"],
            fields["selection_generation"],
            1 if fields["expected_paused"] else 0,
            fields["created_at"],
            fields["expires_at"],
            fields["boot_id"],
        ),
    )
    return intent_id


def open_intent(conn: sqlite3.Connection, grant_id: str, session_id: str, now_ms: int, boot_id: str) -> sqlite3.Row | None:
    return conn.execute(
        "SELECT * FROM pending_intents WHERE grant_id=? AND session_id=? AND consumed_at IS NULL "
        "AND expires_at>? AND boot_id=? ORDER BY created_at DESC LIMIT 1",
        (grant_id, session_id, now_ms, boot_id),
    ).fetchone()


def consume_intent(conn: sqlite3.Connection, intent_id: str, now_ms: int) -> None:
    conn.execute(
        "UPDATE pending_intents SET consumed_at=? WHERE id=? AND consumed_at IS NULL",
        (now_ms, intent_id),
    )


def clear_intents(conn: sqlite3.Connection, grant_id: str, session_id: str, now_ms: int) -> None:
    conn.execute(
        "UPDATE pending_intents SET consumed_at=? WHERE grant_id=? AND session_id=? AND consumed_at IS NULL",
        (now_ms, grant_id, session_id),
    )


def mint_command(conn: sqlite3.Connection, fields: dict[str, Any]) -> str:
    command_id = new_uuid()
    conn.execute(
        "INSERT INTO commands(id, installation_id, connection_id, selection_generation, bake_key, operation, "
        "recipe_revision, expected_revision, expected_step, expected_paused, issued_at, expires_at, boot_id, "
        "state, session_id, intent_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'pending',?,?)",
        (
            command_id,
            fields["installation_id"],
            fields["connection_id"],
            fields["selection_generation"],
            fields["bake_key"],
            fields["operation"],
            fields["recipe_revision"],
            fields["expected_revision"],
            fields["expected_step"],
            1 if fields["expected_paused"] else 0,
            fields["issued_at"],
            fields["expires_at"],
            fields["boot_id"],
            fields["session_id"],
            fields["intent_id"],
        ),
    )
    return command_id


def mark_unknown(conn: sqlite3.Connection, command_id: str) -> None:
    conn.execute(
        "UPDATE commands SET state='unknown' WHERE id=? AND state IN ('pending','dispatched')",
        (command_id,),
    )


def read_receipt(conn: sqlite3.Connection, command_id: str) -> sqlite3.Row | None:
    return conn.execute("SELECT * FROM receipts WHERE command_id=?", (command_id,)).fetchone()


def latest_receipt(
    conn: sqlite3.Connection,
    installation_id: str,
    bake_key: str,
    generation: str,
    now_ms: int,
    ttl_ms: int,
) -> sqlite3.Row | None:
    if not bake_key or not generation:
        return None
    return conn.execute(
        "SELECT r.* FROM receipts r JOIN commands c ON c.id=r.command_id "
        "WHERE r.installation_id=? AND c.bake_key=? AND c.selection_generation=? "
        "AND r.received_at>=? ORDER BY r.received_at DESC LIMIT 1",
        (
            installation_id,
            canon_uuid(bake_key),
            canon_uuid(generation),
            int(now_ms) - int(ttl_ms),
        ),
    ).fetchone()


def ack_command(
    conn: sqlite3.Connection,
    *,
    installation_id: str,
    command_id: str,
    connection_id: str,
    generation: str,
    bake_key: str,
    expected_revision: int,
    status: str,
    new_revision: int | None,
    now_ms: int,
) -> bool:
    row = conn.execute("SELECT * FROM commands WHERE id=?", (command_id,)).fetchone()
    if row is None:
        return False
    if row["installation_id"] != installation_id:
        return False
    if row["connection_id"] != connection_id:
        return False
    if row["selection_generation"] != canon_uuid(generation):
        return False
    if row["bake_key"] != canon_uuid(bake_key):
        return False
    if int(row["expected_revision"]) != expected_revision:
        return False
    delivered = row["state"] in ("dispatched", "unknown", "acked") or (
        row["state"] == "killed" and row["dispatched_at"] is not None
    )
    if not delivered:
        return False
    existing = read_receipt(conn, command_id)
    if existing is not None:
        return existing["status"] == status and (
            existing["new_revision"] == new_revision
            or (existing["new_revision"] is None and new_revision is None)
        )
    late = 1 if row["state"] == "unknown" or int(row["expires_at"]) <= now_ms else 0
    if row["state"] == "pending":
        return False
    conn.execute(
        "INSERT INTO receipts(command_id, installation_id, status, new_revision, received_at, connection_id, "
        "expected_revision, late) VALUES(?,?,?,?,?,?,?,?)",
        (
            command_id,
            installation_id,
            status,
            new_revision,
            now_ms,
            connection_id,
            expected_revision,
            late,
        ),
    )
    conn.execute(
        "UPDATE commands SET state='acked' WHERE id=? AND state IN ('dispatched','unknown','killed')",
        (command_id,),
    )
    return True


def command_state(conn: sqlite3.Connection, command_id: str) -> str | None:
    row = conn.execute("SELECT state FROM commands WHERE id=?", (command_id,)).fetchone()
    return None if row is None else str(row["state"])
