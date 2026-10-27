"""Installation-only OAuth authority, never a row in the legacy grants table.

All state transitions run under the caller's BEGIN IMMEDIATE. SDK binding is
identity, a pending foreground challenge is permission to attempt pairing, and
only authenticated OAuth client code redemption creates stable authority.
"""
from __future__ import annotations

from config import EXACT_REDIRECT, EXACT_SCOPE
from roster_store import SCOPE, require
from security import new_uuid, sha256_text
from yandex_store import epoch

SCHEMA = """
CREATE TABLE IF NOT EXISTS installation_pair_requests (
 id TEXT PRIMARY KEY, installation_id TEXT NOT NULL REFERENCES installations(id) ON DELETE CASCADE,
 uid TEXT NOT NULL, epoch INTEGER NOT NULL, scope TEXT NOT NULL,
 client_id TEXT NOT NULL, redirect_uri TEXT NOT NULL, wire_scope TEXT NOT NULL,
 created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
 approved_at INTEGER, revoked_at INTEGER, browser_hash TEXT, state_hash TEXT, provider_state_hash TEXT
);
CREATE INDEX IF NOT EXISTS installation_pair_pending ON installation_pair_requests(uid,expires_at);
CREATE TABLE IF NOT EXISTS installation_pair_codes (
 code_hash TEXT PRIMARY KEY, request_id TEXT NOT NULL UNIQUE REFERENCES installation_pair_requests(id),
 expires_at INTEGER NOT NULL, used_at INTEGER, grant_id TEXT
);
CREATE TABLE IF NOT EXISTS installation_pair_grants (
 id TEXT PRIMARY KEY, request_id TEXT NOT NULL UNIQUE REFERENCES installation_pair_requests(id),
 installation_id TEXT NOT NULL REFERENCES installations(id) ON DELETE CASCADE,
 uid TEXT NOT NULL, epoch INTEGER NOT NULL, scope TEXT NOT NULL, client_id TEXT NOT NULL,
 created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, revoked_at INTEGER
);
CREATE UNIQUE INDEX IF NOT EXISTS installation_pair_one_live ON installation_pair_grants(installation_id) WHERE revoked_at IS NULL;
CREATE TABLE IF NOT EXISTS installation_pair_access (
 token_hash TEXT PRIMARY KEY, grant_id TEXT NOT NULL REFERENCES installation_pair_grants(id),
 expires_at INTEGER NOT NULL, revoked_at INTEGER
);
CREATE TABLE IF NOT EXISTS installation_pair_refresh (
 token_hash TEXT PRIMARY KEY, grant_id TEXT NOT NULL REFERENCES installation_pair_grants(id),
 expires_at INTEGER NOT NULL, rotated_at INTEGER, revoked_at INTEGER
);
CREATE TRIGGER IF NOT EXISTS installation_pair_grant_revoke AFTER UPDATE OF revoked_at ON installation_pair_grants
 WHEN NEW.revoked_at IS NOT NULL BEGIN
 UPDATE installation_pair_access SET revoked_at=NEW.revoked_at WHERE grant_id=NEW.id AND revoked_at IS NULL;
 UPDATE installation_pair_refresh SET revoked_at=NEW.revoked_at WHERE grant_id=NEW.id AND revoked_at IS NULL;
 UPDATE roster_v2_connections SET revoked_at=NEW.revoked_at WHERE source_grant_id=NEW.id AND revoked_at IS NULL;
END;
"""
# Triggers live in the latest DB, so unchanged old readers cannot resurrect
# authority by unbinding/rebinding/disconnecting during a rollback interval.
for _name, _event, _ref, _when in (
    ('epoch_update', 'AFTER UPDATE ON yandex_epochs', 'NEW', ''),
    ('epoch_insert', 'AFTER INSERT ON yandex_epochs', 'NEW', ''),
    ('epoch_delete', 'BEFORE DELETE ON yandex_epochs', 'OLD', ''),
    ('binding_delete', 'BEFORE DELETE ON yandex_bindings', 'OLD', ''),
    ('binding_update', 'AFTER UPDATE OF uid ON yandex_bindings', 'OLD', 'WHEN NEW.uid!=OLD.uid'),
):
    SCHEMA += f"""
CREATE TRIGGER IF NOT EXISTS installation_pair_{_name} {_event} {_when} BEGIN
 UPDATE installation_pair_requests SET revoked_at=0 WHERE installation_id={_ref}.installation_id AND revoked_at IS NULL;
 UPDATE installation_pair_grants SET revoked_at=0 WHERE installation_id={_ref}.installation_id AND revoked_at IS NULL;
END;
"""


def identity_valid(c, row):
    binding = c.execute('SELECT uid FROM yandex_bindings WHERE installation_id=?', (row['installation_id'],)).fetchone()
    return (binding is not None and binding[0] == row['uid']
            and epoch(c, row['installation_id']) == row['epoch'] and row['scope'] == SCOPE)


def grant(c, grant_id, now, client_id=None):
    row = c.execute('SELECT * FROM installation_pair_grants WHERE id=?', (grant_id,)).fetchone()
    if (row is None or row['revoked_at'] is not None or row['expires_at'] <= now
            or (client_id is not None and row['client_id'] != client_id) or not identity_valid(c, row)
            or c.execute('SELECT COUNT(*) FROM yandex_bindings WHERE uid=?', (row['uid'],)).fetchone()[0] != 1):
        return None
    return row


def revoke(c, grant_id, now):
    c.execute('UPDATE installation_pair_grants SET revoked_at=? WHERE id=? AND revoked_at IS NULL', (now, grant_id))


def request(c, snapshot, request_id, client_id, now, ttl):
    installation = snapshot['id']
    require(snapshot['binding_uid'] is not None, 'binding_required', 409)
    evidence = dict(installation_id=installation, uid=snapshot['binding_uid'],
                    epoch=snapshot['authority_epoch'], scope=SCOPE)
    require(identity_valid(c, evidence), 'binding_changed', 409)
    previous = c.execute('SELECT * FROM installation_pair_requests WHERE id=?', (request_id,)).fetchone()
    if previous is not None:
        require(previous['installation_id'] == installation and previous['uid'] == evidence['uid']
                and previous['epoch'] == evidence['epoch'] and previous['scope'] == SCOPE
                and previous['client_id'] == client_id and previous['revoked_at'] is None
                and previous['approved_at'] is None and previous['expires_at'] > now, 'challenge_consumed', 409)
        return previous['expires_at']  # duplicate never extends or re-arms
    live = c.execute('SELECT id FROM installation_pair_grants WHERE installation_id=? AND revoked_at IS NULL', (installation,)).fetchone()
    if live:
        require(grant(c, live[0], now) is None, 'already_paired', 409)
        revoke(c, live[0], now)
    c.execute('UPDATE installation_pair_requests SET revoked_at=? WHERE installation_id=? AND revoked_at IS NULL', (now, installation))
    expires = now + min(ttl, 300000)
    c.execute('INSERT INTO installation_pair_requests(id,installation_id,uid,epoch,scope,client_id,redirect_uri,wire_scope,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?)',
              (request_id, installation, evidence['uid'], evidence['epoch'], SCOPE, client_id, EXACT_REDIRECT, EXACT_SCOPE, now, expires))
    return expires


def pending_snapshot(c, now):
    """Freeze candidates BEFORE provider I/O; new consent cannot join an old flow."""
    candidates = {}
    for row in c.execute('SELECT * FROM installation_pair_requests WHERE approved_at IS NULL AND revoked_at IS NULL AND expires_at>?', (now,)):
        if identity_valid(c, row):
            candidates.setdefault(row['uid'], []).append(row['id'])
    return candidates


def approve_and_issue(c, uid, candidates, checked, browser, provider_state, code, now, ttl):
    # No arbitrary/latest installation match, even when only one device opted in.
    ids = candidates.get(uid, [])
    if len(ids) != 1 or c.execute('SELECT COUNT(*) FROM yandex_bindings WHERE uid=?', (uid,)).fetchone()[0] != 1:
        return False
    row = c.execute('SELECT * FROM installation_pair_requests WHERE id=?', (ids[0],)).fetchone()
    if (row is None or row['uid'] != uid or row['approved_at'] is not None or row['revoked_at'] is not None
            or row['expires_at'] <= now or not identity_valid(c, row)
            or row['client_id'] != checked['client_id'] or row['redirect_uri'] != checked['redirect_uri']
            or row['wire_scope'] != checked['scope']):
        return False
    c.execute('UPDATE installation_pair_requests SET approved_at=?,browser_hash=?,state_hash=?,provider_state_hash=? WHERE id=?',
              (now, sha256_text(browser), sha256_text(checked['state']), sha256_text(provider_state), row['id']))
    c.execute('INSERT INTO installation_pair_codes(code_hash,request_id,expires_at) VALUES(?,?,?)',
              (sha256_text(code), row['id'], min(row['expires_at'], now + ttl)))
    return True


def known_code(c, digest):
    return c.execute('SELECT 1 FROM installation_pair_codes WHERE code_hash=?', (digest,)).fetchone() is not None


def known_refresh(c, digest):
    return c.execute('SELECT 1 FROM installation_pair_refresh WHERE token_hash=?', (digest,)).fetchone() is not None


def _tokens(c, row, access, refresh, now, access_ttl_s):
    expires = min(row['expires_at'], now + access_ttl_s * 1000)
    c.execute('INSERT INTO installation_pair_access(token_hash,grant_id,expires_at) VALUES(?,?,?)', (sha256_text(access), row['id'], expires))
    c.execute('INSERT INTO installation_pair_refresh(token_hash,grant_id,expires_at) VALUES(?,?,?)', (sha256_text(refresh), row['id'], row['expires_at']))
    from store import TokenPair
    return TokenPair(access, refresh, max(0, (expires - now) // 1000), row['id'])


def redeem(c, digest, client_id, redirect, access, refresh, now, access_ttl_s, grant_ttl):
    code = c.execute('SELECT * FROM installation_pair_codes WHERE code_hash=?', (digest,)).fetchone()
    if code is None:
        return None
    row = c.execute('SELECT * FROM installation_pair_requests WHERE id=?', (code['request_id'],)).fetchone()
    if row is None or row['client_id'] != client_id or row['redirect_uri'] != redirect or row['wire_scope'] != EXACT_SCOPE:
        return None
    if code['used_at'] is not None:
        if code['grant_id']:
            revoke(c, code['grant_id'], now)
        return None
    c.execute('UPDATE installation_pair_codes SET used_at=? WHERE code_hash=?', (now, digest))
    if (code['expires_at'] <= now or row['expires_at'] <= now or row['approved_at'] is None
            or row['revoked_at'] is not None or not identity_valid(c, row)):
        return None
    # Revalidate ambiguity at redemption, not only at provider callback.
    if c.execute('SELECT COUNT(*) FROM yandex_bindings WHERE uid=?', (row['uid'],)).fetchone()[0] != 1:
        return None
    c.execute('UPDATE installation_pair_grants SET revoked_at=? WHERE installation_id=? AND revoked_at IS NULL', (now, row['installation_id']))
    grant_id = new_uuid()
    c.execute('INSERT INTO installation_pair_grants(id,request_id,installation_id,uid,epoch,scope,client_id,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?)',
              (grant_id, row['id'], row['installation_id'], row['uid'], row['epoch'], SCOPE, client_id, now, now + grant_ttl))
    c.execute('UPDATE installation_pair_codes SET grant_id=? WHERE code_hash=?', (grant_id, digest))
    return _tokens(c, grant(c, grant_id, now, client_id), access, refresh, now, access_ttl_s)


def refresh(c, digest, client_id, access, rotated, now, access_ttl_s):
    token = c.execute('SELECT * FROM installation_pair_refresh WHERE token_hash=?', (digest,)).fetchone()
    if token is None:
        return None
    row = c.execute('SELECT * FROM installation_pair_grants WHERE id=?', (token['grant_id'],)).fetchone()
    if row is None or row['client_id'] != client_id:
        return None
    if (token['rotated_at'] is not None or token['revoked_at'] is not None or token['expires_at'] <= now
            or grant(c, row['id'], now, client_id) is None):
        revoke(c, row['id'], now)
        return None
    c.execute('UPDATE installation_pair_refresh SET rotated_at=? WHERE token_hash=?', (now, digest))
    c.execute('UPDATE installation_pair_access SET revoked_at=? WHERE grant_id=? AND revoked_at IS NULL', (now, row['id']))
    return _tokens(c, row, access, rotated, now, access_ttl_s)


def by_access(c, digest, now, client_id):
    token = c.execute('SELECT * FROM installation_pair_access WHERE token_hash=?', (digest,)).fetchone()
    if token is None or token['revoked_at'] is not None or token['expires_at'] <= now:
        return None
    return grant(c, token['grant_id'], now, client_id)


def source(c, installation, now, client_id=None):
    row = c.execute('SELECT id FROM installation_pair_grants WHERE installation_id=? AND revoked_at IS NULL', (installation,)).fetchone()
    return grant(c, row[0], now, client_id) if row else None
