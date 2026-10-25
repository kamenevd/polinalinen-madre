"""Additive roster-v2 authority. All functions run under one BEGIN IMMEDIATE.

Schema marker intentionally remains legacy user_version=3: d499 can read this
latest DB. SQL triggers also revoke v2 when the OLD executable unbinds/revokes.
Never roll back by replacing this DB with a historical snapshot.
"""
from __future__ import annotations

import hashlib
import json
import uuid

SCOPE = "installation_current_bakes"
SNAPSHOT_TTL = 540_000  # nine minutes; bounded janitor has margin below ten
PENDING_TTL = 60_000
COMMAND_TTL = 4000
MAX_INT = (1 << 63) - 1

SCHEMA = """
CREATE TABLE IF NOT EXISTS roster_v2_connections (
 id TEXT PRIMARY KEY, installation_id TEXT NOT NULL REFERENCES installations(id) ON DELETE CASCADE,
 source_grant_id TEXT NOT NULL, epoch INTEGER NOT NULL, scope TEXT NOT NULL,
 created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, revoked_at INTEGER,
 boot_id TEXT NOT NULL, roster_revision INTEGER NOT NULL DEFAULT -1,
 digest TEXT, received_at INTEGER, bakes_json TEXT NOT NULL DEFAULT '[]'
);
CREATE UNIQUE INDEX IF NOT EXISTS roster_v2_one_live ON roster_v2_connections(installation_id) WHERE revoked_at IS NULL;
CREATE TABLE IF NOT EXISTS roster_v2_versions (
 connection_id TEXT NOT NULL REFERENCES roster_v2_connections(id) ON DELETE CASCADE,
 bake_key TEXT NOT NULL, number INTEGER NOT NULL, revision INTEGER NOT NULL, fingerprint TEXT NOT NULL,
 PRIMARY KEY(connection_id,bake_key)
);
CREATE TABLE IF NOT EXISTS roster_v2_pending (
 connection_id TEXT NOT NULL REFERENCES roster_v2_connections(id) ON DELETE CASCADE,
 session_id TEXT NOT NULL, intent TEXT NOT NULL, target_json TEXT,
 expires_at INTEGER NOT NULL, PRIMARY KEY(connection_id,session_id)
);
CREATE TABLE IF NOT EXISTS roster_v2_commands (
 id TEXT PRIMARY KEY, connection_id TEXT NOT NULL REFERENCES roster_v2_connections(id) ON DELETE CASCADE,
 installation_id TEXT NOT NULL, lease_id TEXT NOT NULL UNIQUE, payload_json TEXT NOT NULL,
 fingerprint TEXT NOT NULL, expires_at INTEGER NOT NULL, state TEXT NOT NULL,
 ack_json TEXT, ack_at INTEGER, dispatched_at INTEGER
);
CREATE INDEX IF NOT EXISTS roster_v2_dispatch ON roster_v2_commands(connection_id,state);
CREATE TRIGGER IF NOT EXISTS roster_v2_revoke_effect AFTER UPDATE OF revoked_at ON roster_v2_connections
 WHEN NEW.revoked_at IS NOT NULL BEGIN
 UPDATE roster_v2_connections SET bakes_json='[]',received_at=NULL WHERE id=NEW.id;
 DELETE FROM roster_v2_pending WHERE connection_id=NEW.id;
 UPDATE roster_v2_commands SET state='revoked' WHERE connection_id=NEW.id AND state IN ('pending','dispatched');
END;
CREATE TRIGGER IF NOT EXISTS roster_v2_legacy_grant_revoke AFTER UPDATE OF revoked_at ON grants
 WHEN NEW.revoked_at IS NOT NULL BEGIN
 UPDATE roster_v2_connections SET revoked_at=NEW.revoked_at WHERE source_grant_id=NEW.id AND revoked_at IS NULL;
END;
CREATE TRIGGER IF NOT EXISTS roster_v2_legacy_grant_delete BEFORE DELETE ON grants BEGIN
 UPDATE roster_v2_connections SET revoked_at=0 WHERE source_grant_id=OLD.id AND revoked_at IS NULL;
END;
CREATE TRIGGER IF NOT EXISTS roster_v2_epoch_update AFTER UPDATE ON yandex_epochs BEGIN
 UPDATE roster_v2_connections SET revoked_at=0 WHERE installation_id=NEW.installation_id AND revoked_at IS NULL;
END;
CREATE TRIGGER IF NOT EXISTS roster_v2_epoch_insert AFTER INSERT ON yandex_epochs BEGIN
 UPDATE roster_v2_connections SET revoked_at=0 WHERE installation_id=NEW.installation_id AND revoked_at IS NULL;
END;
CREATE TRIGGER IF NOT EXISTS roster_v2_epoch_delete BEFORE DELETE ON yandex_epochs BEGIN
 UPDATE roster_v2_connections SET revoked_at=0 WHERE installation_id=OLD.installation_id AND revoked_at IS NULL;
END;
CREATE TRIGGER IF NOT EXISTS roster_v2_binding_delete BEFORE DELETE ON yandex_bindings BEGIN
 UPDATE roster_v2_connections SET revoked_at=0 WHERE installation_id=OLD.installation_id AND revoked_at IS NULL;
END;
CREATE TRIGGER IF NOT EXISTS roster_v2_binding_update AFTER UPDATE OF uid ON yandex_bindings WHEN NEW.uid!=OLD.uid BEGIN
 UPDATE roster_v2_connections SET revoked_at=0 WHERE installation_id=OLD.installation_id AND revoked_at IS NULL;
END;
"""


class ProtocolError(ValueError):
    def __init__(self, error="invalid_request", status=400):
        self.error, self.status = error, status
        super().__init__(error)


def require(condition, error="invalid_request", status=400):
    if not condition:
        raise ProtocolError(error, status)


def integer(value, low=0, high=MAX_INT):
    return type(value) is int and low <= value <= high


def canonical_uuid(value):
    if type(value) is not str:
        return False
    try:
        return str(uuid.UUID(value)) == value
    except ValueError:
        return False


def text(value, limit=2048):
    return type(value) is str and 0 < len(value) <= limit and not any(ord(c) < 32 for c in value)


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def digest(value):
    return hashlib.sha256(encoded(value).encode()).hexdigest()


def fingerprint(bake):
    return digest({k: v for k, v in bake.items() if k != "remaining_seconds"})


def validate_bakes(bakes):
    fields = set("bake_key bake_number revision recipe_revision recipe_name state step_index step_count step_title remaining_seconds start_at step_text next_step_title next_step_text quantities portions control_ready".split())
    require(type(bakes) is list and len(bakes) <= 9)
    keys, numbers = set(), set()
    for b in bakes:
        require(type(b) is dict and set(b) == fields)
        require(canonical_uuid(b['bake_key']) and b['bake_key'] not in keys)
        require(integer(b['bake_number'], 1, 9) and b['bake_number'] not in numbers)
        keys.add(b['bake_key']); numbers.add(b['bake_number'])
        require(integer(b['revision']) and text(b['recipe_revision'], 256))
        require(text(b['recipe_name'], 160) and text(b['step_title'], 256))
        require(type(b['state']) is str and b['state'] in ('running', 'paused', 'scheduled', 'finale'))
        require(integer(b['step_count'], 1, 10000) and integer(b['step_index'], 0, b['step_count'] - 1))
        require(integer(b['remaining_seconds'], 0, 604800))
        require(integer(b['start_at'], 1) if b['state'] == 'scheduled' else b['start_at'] is None)
        for k in ('step_text', 'next_step_title', 'next_step_text', 'quantities'):
            require(b[k] is None or text(b[k]))
        require(b['portions'] is None or integer(b['portions'], 1, 5))
        require(type(b['control_ready']) is bool)
        if b['control_ready']:
            require(b['state'] in ('running', 'paused') and integer(b['portions'], 1, 5))
        if b['portions'] is None:
            require(not b['control_ready'] and all(b[k] is None for k in ('step_text', 'next_step_text', 'quantities')))
    return sorted(bakes, key=lambda b: b['bake_number'])


def cleanup(c, now):
    c.execute("UPDATE roster_v2_connections SET revoked_at=? WHERE revoked_at IS NULL AND expires_at<=?", (now, now))
    c.execute("UPDATE roster_v2_connections SET bakes_json='[]',received_at=NULL WHERE received_at<=?", (now - SNAPSHOT_TTL,))
    c.execute("DELETE FROM roster_v2_pending WHERE expires_at<=?", (now,))
    c.execute("UPDATE roster_v2_commands SET state='expired' WHERE expires_at<=? AND state IN ('pending','dispatched')", (now,))
    # Technical replay evidence is bounded; connections/revocations remain.
    c.execute("DELETE FROM roster_v2_commands WHERE expires_at<?", (now - 86400000,))


def current(c, installation, now, boot, connection=None, grant=None, client_id=None):
    import installation_pair
    row = c.execute("SELECT v.* FROM roster_v2_connections v "
                    "JOIN installations i ON i.id=v.installation_id LEFT JOIN yandex_epochs e ON e.installation_id=i.id "
                    "WHERE v.installation_id=? AND v.revoked_at IS NULL AND v.expires_at>? AND v.boot_id=? "
                    "AND v.scope=? AND v.epoch=COALESCE(e.epoch,0)",
                    (installation, now, boot, SCOPE)).fetchone()
    if row is None or (connection is not None and row['id'] != connection) or (grant is not None and row['source_grant_id'] != grant):
        return None
    paired = installation_pair.grant(c, row['source_grant_id'], now, client_id)
    if paired is not None and paired['installation_id'] == installation:
        return row
    legacy = c.execute("SELECT 1 FROM grants g JOIN installations i ON i.id=g.installation_id "
                       "WHERE g.id=? AND i.id=? AND g.revoked_at IS NULL AND g.expires_at>? "
                       "AND g.bake_key=i.selected_bake_key AND g.selection_generation=i.selected_generation",
                       (row['source_grant_id'], installation, now)).fetchone()
    if legacy is None:
        return None
    return row


def attach(c, snapshot, now, boot, client_id=None):
    import installation_pair
    # Compare the original authentication epoch inside the mutation transaction.
    epoch = c.execute("SELECT epoch FROM yandex_epochs WHERE installation_id=?", (snapshot['id'],)).fetchone()
    require((epoch[0] if epoch else 0) == snapshot['authority_epoch'], 'binding_changed', 409)
    cleanup(c, now)
    row = current(c, snapshot['id'], now, boot, client_id=client_id)
    if row:
        return row['id']  # idempotency never refreshes consent lifetime
    c.execute("UPDATE roster_v2_connections SET revoked_at=? WHERE installation_id=? AND revoked_at IS NULL", (now, snapshot['id']))
    source = installation_pair.source(c, snapshot['id'], now, client_id)
    if source is None:
        source = c.execute("SELECT g.* FROM grants g JOIN installations i ON i.id=g.installation_id "
                           "WHERE i.id=? AND g.bake_key=i.selected_bake_key AND g.selection_generation=i.selected_generation "
                           "AND g.revoked_at IS NULL AND g.expires_at>? ORDER BY g.created_at DESC,g.id LIMIT 1",
                           (snapshot['id'], now)).fetchone()
    require(source is not None, 'pair_required', 409)
    connection = str(uuid.uuid4())
    c.execute("INSERT INTO roster_v2_connections(id,installation_id,source_grant_id,epoch,scope,created_at,expires_at,boot_id) VALUES(?,?,?,?,?,?,?,?)",
              (connection, snapshot['id'], source['id'], snapshot['authority_epoch'], SCOPE, now, source['expires_at'], boot))
    return connection


def disconnect(c, installation, connection, now):
    row = c.execute("SELECT * FROM roster_v2_connections WHERE id=? AND installation_id=?", (connection, installation)).fetchone()
    require(row is not None, 'connection_revoked', 409)
    if row['revoked_at'] is not None:
        return False  # stale disconnect must not revoke a reattached connection
    c.execute("UPDATE roster_v2_connections SET revoked_at=COALESCE(revoked_at,?) WHERE id=?", (now, connection))
    return True


def list_bakes(row, now):
    if row['received_at'] is None or not 0 <= now - row['received_at'] < SNAPSHOT_TTL:
        return []
    return json.loads(row['bakes_json'])


def poll(c, installation, body, now, boot, freshness, client_id=None):
    require(set(body) == {'protocol_version', 'connection_id', 'roster_revision', 'bakes'})
    require(canonical_uuid(body['connection_id']) and integer(body['roster_revision']))
    bakes = validate_bakes(body['bakes'])
    cleanup(c, now)
    row = current(c, installation, now, boot, connection=body['connection_id'], client_id=client_id)
    require(row is not None, 'connection_revoked', 409)
    rev, dg = body['roster_revision'], digest(bakes)
    require(rev >= row['roster_revision'], 'stale_roster', 409)
    require(rev != row['roster_revision'] or dg == row['digest'], 'conflict', 409)
    for b in bakes:
        old = c.execute("SELECT * FROM roster_v2_versions WHERE connection_id=? AND bake_key=?", (row['id'], b['bake_key'])).fetchone()
        if old:
            require(old['number'] == b['bake_number'], 'conflict', 409)
            require(b['revision'] >= old['revision'], 'stale_roster', 409)
            require(b['revision'] != old['revision'] or fingerprint(b) == old['fingerprint'], 'conflict', 409)
        c.execute("INSERT INTO roster_v2_versions VALUES(?,?,?,?,?) ON CONFLICT(connection_id,bake_key) DO UPDATE SET revision=excluded.revision,fingerprint=excluded.fingerprint",
                  (row['id'], b['bake_key'], b['bake_number'], b['revision'], fingerprint(b)))
    c.execute("UPDATE roster_v2_connections SET roster_revision=?,digest=?,received_at=?,bakes_json=? WHERE id=?",
              (rev, dg, now, encoded(bakes), row['id']))
    # Full replacement and command dispatch are atomic. Changed targets cannot dispatch.
    by_key = {b['bake_key']: b for b in bakes}
    commands = []
    for cmd in c.execute("SELECT * FROM roster_v2_commands WHERE connection_id=? AND state='pending' ORDER BY rowid LIMIT 9", (row['id'],)).fetchall():
        payload = json.loads(cmd['payload_json']); b = by_key.get(payload['bake_key'])
        valid = b is not None and b['control_ready'] and fingerprint(b) == cmd['fingerprint'] and now < cmd['expires_at']
        c.execute("UPDATE roster_v2_commands SET state=?,dispatched_at=? WHERE id=?", ('dispatched' if valid else 'stale', now if valid else None, cmd['id']))
        if valid:
            commands.append(payload)
    return {'protocol_version': 2, 'connection_id': row['id'], 'roster_revision': rev, 'server_time': now, 'commands': commands}


def ack(c, installation, body, now, boot, client_id=None):
    require(set(body) == {'connection_id', 'command_id', 'lease_id', 'outcome', 'resulting_revision'})
    require(all(canonical_uuid(body[k]) for k in ('connection_id', 'command_id', 'lease_id')))
    require(type(body['outcome']) is str and body['outcome'] in ('applied', 'stale', 'expired', 'rejected', 'phone_required'))
    require(body['resulting_revision'] is None or integer(body['resulting_revision']))
    require(body['outcome'] != 'applied' or integer(body['resulting_revision']))
    row = c.execute("SELECT * FROM roster_v2_commands WHERE id=? AND installation_id=? AND connection_id=? AND lease_id=?",
                    (body['command_id'], installation, body['connection_id'], body['lease_id'])).fetchone()
    require(row is not None, 'unknown_command', 409)
    require(current(c, installation, now, boot, connection=body['connection_id'], client_id=client_id) is not None, 'connection_revoked', 409)
    wire = encoded(body)
    if row['ack_json'] is not None:
        require(row['ack_json'] == wire, 'conflict', 409)
        return
    require(row['state'] in ('dispatched', 'expired') and row['dispatched_at'] is not None, 'connection_revoked', 409)
    payload = json.loads(row['payload_json'])
    require(body['outcome'] != 'applied' or body['resulting_revision'] == payload['expected_revision'] + 1)
    # A late receipt is evidence only; never re-enables dispatch or mutates a bake.
    c.execute("UPDATE roster_v2_commands SET ack_json=?,ack_at=?,state='acked' WHERE id=?", (wire, now, row['id']))


def pending(c, connection, session, now):
    return c.execute("SELECT * FROM roster_v2_pending WHERE connection_id=? AND session_id=? AND expires_at>?", (connection, session, now)).fetchone()


def clear_pending(c, connection, session):
    c.execute("DELETE FROM roster_v2_pending WHERE connection_id=? AND session_id=?", (connection, session))


def save_pending(c, connection, session, intent, target, now):
    clear_pending(c, connection, session)
    c.execute("INSERT INTO roster_v2_pending VALUES(?,?,?,?,?)", (connection, session, intent, encoded(target) if target else None, now + PENDING_TTL))


def eligible_action(bake, operation):
    return (bake['control_ready'] and bake['state'] in ('running', 'paused')
            and not (operation == 'ADVANCE' and bake['step_index'] >= bake['step_count'] - 1)
            and not (operation == 'BACK' and bake['step_index'] == 0)
            and not (operation == 'PAUSE' and bake['state'] == 'paused')
            and not (operation == 'RESUME' and bake['state'] == 'running'))


def mint(c, row, session, confirmation, now, freshness):
    p = pending(c, row['id'], session, now)
    if not p or p['target_json'] is None:
        return None, 'Подтверждать нечего. Ничего не отправляю.'
    if (p['intent'] == 'CANCEL') != (confirmation == 'confirm_cancel'):
        return None, 'Для отмены скажите «подтверждаю отмену». Остальные действия подтвердите словом «да».'
    target = json.loads(p['target_json'])
    if 'bake_key' not in target:
        return None, 'Сначала назовите номер выпечки. Ничего не отправляю.'
    b = next((b for b in list_bakes(row, now) if b['bake_key'] == target['bake_key']), None)
    clear_pending(c, row['id'], session)
    if (b is None or fingerprint(b) != target['fingerprint'] or row['received_at'] is None
            or not 0 <= now - row['received_at'] <= freshness or not eligible_action(b, p['intent'])):
        return None, 'Выпечка уже закрыта или состояние изменилось. Ничего не меняю.'
    if c.execute("SELECT 1 FROM roster_v2_commands WHERE connection_id=? AND state IN ('pending','dispatched') AND expires_at>?", (row['id'], now)).fetchone():
        return None, 'Предыдущая команда ещё не закрыта. Ничего не отправляю.'
    command = dict(id=str(uuid.uuid4()), connection_id=row['id'], bake_key=b['bake_key'], bake_number=b['bake_number'],
                   expected_revision=b['revision'], recipe_revision=b['recipe_revision'], operation=p['intent'],
                   issued_at=now, expires_at=min(now + COMMAND_TTL, row['expires_at']), lease_id=str(uuid.uuid4()),
                   expected_step=b['step_index'], expected_paused=b['state'] == 'paused')
    c.execute("INSERT INTO roster_v2_commands(id,connection_id,installation_id,lease_id,payload_json,fingerprint,expires_at,state) VALUES(?,?,?,?,?,?,?,'pending')",
              (command['id'], row['id'], row['installation_id'], command['lease_id'], encoded(command), fingerprint(b), command['expires_at']))
    return command, None
