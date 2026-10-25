"""UID binding and narrowly scoped technical consent/code provenance, never profile/token."""
from config import EXACT_SCOPE
from store import disconnect, installation_by_id

CONSENT_TTL_MS = 300_000


def epoch(conn, installation_id):
    row = conn.execute("SELECT epoch FROM yandex_epochs WHERE installation_id=?", (installation_id,)).fetchone()
    return int(row[0]) if row else 0


def bump(conn, installation_id):
    conn.execute("INSERT INTO yandex_epochs(installation_id,epoch) VALUES(?,1) "
                 "ON CONFLICT(installation_id) DO UPDATE SET epoch=epoch+1", (installation_id,))


def unbind(conn, installation_id, now):
    # Even an already-unbound installation can own legacy Dialogs authority.
    # disconnect advances the epoch and deletes consent in the same transaction.
    disconnect(conn, installation_id, now)
    conn.execute("DELETE FROM yandex_bindings WHERE installation_id=?", (installation_id,))


def bind(conn, installation_id, uid, expected_epoch, now):
    if installation_by_id(conn, installation_id) is None or epoch(conn, installation_id) != expected_epoch:
        return False
    old = conn.execute("SELECT uid FROM yandex_bindings WHERE installation_id=?", (installation_id,)).fetchone()
    if old is not None and old[0] == uid:
        # A same-UID retry neither invents nor refreshes consent. Preserve its TTL.
        return True
    if old is not None:
        unbind(conn, installation_id, now)
    conn.execute("INSERT INTO yandex_bindings(installation_id,uid,bound_at) VALUES(?,?,?) "
                 "ON CONFLICT(installation_id) DO UPDATE SET uid=excluded.uid,bound_at=excluded.bound_at",
                 (installation_id, uid, now))
    bump(conn, installation_id)
    return True


def record_consent(conn, snapshot, bake, generation, now):
    """Called ONLY by the dedicated phone action, with its initial auth snapshot.

    Login/bind/select/screen opening/OAuth callback/retry are not consent sources.
    The caller's warning and button are an Android responsibility, not inferred
    from provider consent to login:info/login:avatar.
    """
    installation = snapshot["id"]
    current = installation_by_id(conn, installation)
    binding = conn.execute("SELECT uid FROM yandex_bindings WHERE installation_id=?", (installation,)).fetchone()
    if (current is None or binding is None or not snapshot["binding_uid"]
            or binding[0] != snapshot["binding_uid"]
            or epoch(conn, installation) != snapshot["authority_epoch"]
            or snapshot["selected_bake_key"] != bake or snapshot["selected_generation"] != generation
            or current["selected_bake_key"] != bake or current["selected_generation"] != generation):
        return None
    expires = now + CONSENT_TTL_MS
    existing = conn.execute("SELECT expires_at FROM yandex_voice_consents WHERE installation_id=? "
                            "AND uid=? AND epoch=? AND bake_key=? AND generation=? AND scope=? AND expires_at>?",
                            (installation, binding[0], snapshot["authority_epoch"], bake, generation, EXACT_SCOPE, now)).fetchone()
    if existing is not None:
        # Duplicate delivery within its five-minute lifetime is idempotent,
        # even after consumption: never re-arm or extend it with a retry.
        return int(existing[0])
    conn.execute("INSERT INTO yandex_voice_consents "
                 "(installation_id,uid,epoch,bake_key,generation,scope,created_at,expires_at,consumed_at) "
                 "VALUES(?,?,?,?,?,?,?,?,NULL) ON CONFLICT(installation_id) DO UPDATE SET "
                 "uid=excluded.uid,epoch=excluded.epoch,bake_key=excluded.bake_key,generation=excluded.generation,"
                 "scope=excluded.scope,created_at=excluded.created_at,expires_at=excluded.expires_at,consumed_at=NULL",
                 (installation, binding[0], snapshot["authority_epoch"], bake, generation, EXACT_SCOPE, now, expires))
    return expires


def eligible(conn, uid, now):
    # Exactly one ELIGIBLE phone, not an arbitrary/latest match. Selection and
    # identity alone never grant voice authority; require fresh explicit evidence
    # OR a current live grant on this exact selection (reauthorization).
    rows = conn.execute(
        "SELECT i.*, COALESCE(e.epoch,0) AS authority_epoch, c.expires_at AS consent_expires_at, "
        "(SELECT g.id FROM grants g WHERE g.installation_id=i.id "
        "AND g.bake_key=i.selected_bake_key AND g.selection_generation=i.selected_generation "
        "AND g.revoked_at IS NULL AND g.expires_at>? ORDER BY g.id LIMIT 1) AS source_grant_id "
        "FROM yandex_bindings y JOIN installations i ON i.id=y.installation_id "
        "LEFT JOIN yandex_epochs e ON e.installation_id=i.id "
        "LEFT JOIN yandex_voice_consents c ON c.installation_id=i.id AND c.uid=y.uid "
        "AND c.epoch=COALESCE(e.epoch,0) AND c.bake_key=i.selected_bake_key "
        "AND c.generation=i.selected_generation AND c.scope=? AND c.expires_at>? AND c.consumed_at IS NULL "
        "WHERE y.uid=? AND i.selected_bake_key IS NOT NULL AND i.selected_generation IS NOT NULL "
        "AND (c.installation_id IS NOT NULL OR source_grant_id IS NOT NULL) LIMIT 2",
        (now, EXACT_SCOPE, now, uid)).fetchall()
    return rows[0] if len(rows) == 1 else None


def consume_authority(conn, inst, uid, challenge_id, now):
    """Inside the SAME transaction that issued the broker code, or all rolls back."""
    consent_expires = inst["consent_expires_at"]
    source_grant = None if consent_expires is not None else inst["source_grant_id"]
    if consent_expires is not None:
        cur = conn.execute("UPDATE yandex_voice_consents SET consumed_at=? "
                           "WHERE installation_id=? AND uid=? AND epoch=? AND bake_key=? AND generation=? "
                           "AND scope=? AND expires_at>? AND consumed_at IS NULL",
                           (now, inst["id"], uid, inst["authority_epoch"], inst["selected_bake_key"],
                            inst["selected_generation"], EXACT_SCOPE, now))
        if cur.rowcount != 1:
            raise RuntimeError("voice authority changed")  # rollback code and challenge, never partial success
    elif source_grant is None:
        raise RuntimeError("missing voice authority")
    conn.execute("INSERT INTO yandex_code_authority "
                 "(challenge_id,installation_id,uid,epoch,scope,consent_expires_at,source_grant_id) VALUES(?,?,?,?,?,?,?)",
                 (challenge_id, inst["id"], uid, inst["authority_epoch"], EXACT_SCOPE, consent_expires, source_grant))


def code_authority_valid(conn, challenge, code, now):
    evidence = conn.execute("SELECT * FROM yandex_code_authority WHERE challenge_id=?", (challenge["id"],)).fetchone()
    if evidence is None:
        return True  # unchanged explicit legacy pair-approve
    installation = challenge["installation_id"]
    binding = conn.execute("SELECT uid FROM yandex_bindings WHERE installation_id=?", (installation,)).fetchone()
    if (evidence["installation_id"] != installation or binding is None or binding[0] != evidence["uid"]
            or epoch(conn, installation) != evidence["epoch"]
            or evidence["scope"] != EXACT_SCOPE or code["scope"] != EXACT_SCOPE or challenge["scope"] != EXACT_SCOPE):
        return False
    if evidence["consent_expires_at"] is not None:
        return int(evidence["consent_expires_at"]) > now
    grant = conn.execute("SELECT * FROM grants WHERE id=?", (evidence["source_grant_id"],)).fetchone()
    return (grant is not None and grant["installation_id"] == installation
            and grant["bake_key"] == challenge["bake_key"]
            and grant["selection_generation"] == challenge["selection_generation"]
            and grant["revoked_at"] is None and int(grant["expires_at"]) > now)
