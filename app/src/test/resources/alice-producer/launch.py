"""Exact immutable Alice producer; only external provider HTTPS is substituted.

Android sends pair-consent itself. This external-boundary helper alone acts as
synthetic broker client. Real authorize/state/cookie/PKCE callback and authenticated
code redemption create authority, never grant seeding or a dummy bake.
"""
import hashlib
import json
from pathlib import Path
import sqlite3
import sys
import tarfile
import tempfile

root = Path(sys.argv[1]).resolve()
tempfile.tempdir = str(root)
manifest = json.loads(Path(sys.argv[2]).read_text())
archive = Path(sys.argv[3])
assert manifest['source_commit'] == '18aef851a5c5fa8f969cf812a8c90b792846967a'
assert hashlib.sha256(archive.read_bytes()).hexdigest() == manifest['archive_sha256']
with tarfile.open(archive) as source:
    for member in source.getmembers():
        path = Path(member.name)
        assert not path.is_absolute() and '..' not in path.parts
        assert member.isfile() or member.isdir()
    source.extractall(root)
for name, digest in manifest['files'].items():
    assert hashlib.sha256((root / name).read_bytes()).hexdigest() == digest, name
backend = root / 'backend/alice'
sys.path[:0] = [str(backend), str(backend / 'tests')]
from test_installation_pair import InstallationPairHTTP

fixture = InstallationPairHTTP()
fixture.setUp()  # real enrollment and SDK bind with external-only HTTPS peer
code = None
try:
    gap = fixture.call('attach', dict(protocol_version=2, consent_scope='installation_current_bakes', consent=True))[0]
    print(json.dumps(dict(port=fixture.port, credential=fixture.credential, unpaired_status=gap)), flush=True)
    for line in sys.stdin:
        request = json.loads(line)
        op = request['op']
        if op == 'speech':
            response = dict(text=fixture.speech(request['text']))
        elif op == 'authorize':
            code = fixture.code(consent=False)
            response = dict(approved=True)  # approved code is still NOT confirmed permission
        elif op == 'redeem':
            if code is None:
                code = fixture.code(consent=False)
            status, fixture.pair = fixture.exchange(code)
            assert status == 200
            fixture.access = fixture.pair['access_token']
            response = dict(paired=True)
        elif op == 'replay_code':
            response = dict(status=fixture.exchange(code)[0])
        elif op == 'refresh_replay':
            old = fixture.pair['refresh_token']
            status, rotated = fixture.refresh(old)
            assert status == 200
            response = dict(status=fixture.refresh(old)[0])
        elif op == 'expire':
            fixture.app.clock.advance_ms(300001)
            response = dict(expired=True)
        elif op == 'account_change':
            fixture.peer.info['id'] = '123456789'
            response = dict(status=fixture.bind(fixture.credential)[0])
        elif op == 'inspect':
            with sqlite3.connect(fixture.db_path) as conn:
                rows = conn.execute('SELECT state,ack_json FROM roster_v2_commands ORDER BY rowid').fetchall()
                response = dict(commands=[dict(state=s, ack=json.loads(a) if a else None) for s, a in rows],
                    snapshots=[json.loads(row[0]) for row in conn.execute('SELECT bakes_json FROM roster_v2_connections WHERE revoked_at IS NULL')],
                    pending=conn.execute('SELECT COUNT(*) FROM installation_pair_requests WHERE revoked_at IS NULL').fetchone()[0],
                    grants=conn.execute('SELECT COUNT(*) FROM installation_pair_grants WHERE revoked_at IS NULL').fetchone()[0],
                    legacy=conn.execute('SELECT COUNT(*) FROM grants').fetchone()[0],
                    selected=conn.execute('SELECT selected_bake_key FROM installations WHERE id=?', (fixture.installation,)).fetchone()[0])
        elif op == 'stop':
            print(json.dumps(dict(stopped=True)), flush=True)
            break
        else:
            raise ValueError('unknown fixture operation')
        print(json.dumps(response, ensure_ascii=False), flush=True)
finally:
    fixture.tearDown()
