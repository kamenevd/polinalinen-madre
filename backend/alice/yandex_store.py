"""Identity persistence: uid + installation binding and technical epochs only."""
from store import disconnect


def epoch(conn, installation_id):
    row = conn.execute("SELECT epoch FROM yandex_epochs WHERE installation_id=?", (installation_id,)).fetchone()
    return int(row[0]) if row else 0


def bump(conn, installation_id):
    conn.execute("INSERT INTO yandex_epochs(installation_id,epoch) VALUES(?,1) "
                 "ON CONFLICT(installation_id) DO UPDATE SET epoch=epoch+1", (installation_id,))


def unbind(conn, installation_id, now):
    # Even an already-unbound installation can own legacy Dialogs authority.
    # Never return success before all revocation work commits.
    disconnect(conn, installation_id, now)
    conn.execute("DELETE FROM yandex_bindings WHERE installation_id=?", (installation_id,))
    bump(conn, installation_id)


def bind(conn, installation_id, uid, expected_epoch, now):
    if epoch(conn, installation_id) != expected_epoch:
        return False
    old = conn.execute("SELECT uid FROM yandex_bindings WHERE installation_id=?", (installation_id,)).fetchone()
    if old is not None and old[0] != uid:
        unbind(conn, installation_id, now)
    conn.execute("INSERT INTO yandex_bindings(installation_id,uid,bound_at) VALUES(?,?,?) "
                 "ON CONFLICT(installation_id) DO UPDATE SET uid=excluded.uid,bound_at=excluded.bound_at",
                 (installation_id, uid, now))
    bump(conn, installation_id)
    return True


def eligible(conn, uid, now):
    # Selection is necessary but NOT sufficient consent. Only an existing,
    # unrevoked Dialogs grant on this exact phone selection provides authority.
    # First-time or ambiguous identities must take the existing pair-approve UI.
    rows = conn.execute("SELECT i.* FROM yandex_bindings y JOIN installations i ON i.id=y.installation_id "
                        "WHERE y.uid=? AND i.selected_bake_key IS NOT NULL AND i.selected_generation IS NOT NULL "
                        "AND EXISTS(SELECT 1 FROM grants g WHERE g.installation_id=i.id "
                        "AND g.bake_key=i.selected_bake_key AND g.selection_generation=i.selected_generation "
                        "AND g.revoked_at IS NULL AND g.expires_at>?) LIMIT 2", (uid, now)).fetchall()
    return rows[0] if len(rows) == 1 else None
