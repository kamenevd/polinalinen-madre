"""Test-only launcher. Exact producer source; only external provider is substituted.

The fixture bootstraps its synthetic paired grant through unchanged real HTTP
OAuth challenge/preview/approve/confirm/token handlers, never inserts a grant.
Android supplies actual Room-derived roster and actual post-commit receipts.
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
assert manifest['source_commit'] == 'f1380c21665ebd51fff1da856cdd3164ad36ba9c'
assert hashlib.sha256(archive.read_bytes()).hexdigest() == manifest['archive_sha256']
with tarfile.open(archive) as source:
    # Python 3.10/3.11 CI peers may predate tarfile's filter keyword. Admit only
    # ordinary git archive files/directories with exact safe relative paths.
    for member in source.getmembers():
        path = Path(member.name)
        assert not path.is_absolute() and '..' not in path.parts
        assert member.isfile() or member.isdir()
    source.extractall(root)
backend = root / 'backend/alice'
sys.path[:0] = [str(backend), str(backend / 'tests')]
from test_roster_v2 import RosterHTTP

fixture = RosterHTTP()
fixture.setUp()
try:
    fixture.credential, fixture.installation = fixture.enroll()
    # Capture actual rejected attach before there is a pair. No DB authority mutation.
    gap = fixture.call('attach', dict(protocol_version=2, consent_scope='installation_current_bakes', consent=True))[0]
    fixture.access, fixture.legacy_bake, fixture.generation = fixture._link(fixture.credential)
    print(json.dumps(dict(port=fixture.port, credential=fixture.credential, unpaired_status=gap)), flush=True)
    for line in sys.stdin:
        request = json.loads(line)
        op = request['op']
        if op == 'speech':
            response = dict(text=fixture.speech(request['text']))
        elif op == 'inspect':
            with sqlite3.connect(fixture.db_path) as conn:
                rows = conn.execute('SELECT state,ack_json FROM roster_v2_commands ORDER BY rowid').fetchall()
                response = dict(commands=[dict(state=s, ack=json.loads(a) if a else None) for s, a in rows],
                                snapshots=[json.loads(row[0]) for row in conn.execute('SELECT bakes_json FROM roster_v2_connections WHERE revoked_at IS NULL')])
        elif op == 'repair_pair':
            fixture.access, fixture.legacy_bake, fixture.generation = fixture._link(fixture.credential)
            response = dict(paired=True)
        elif op == 'stop':
            print(json.dumps(dict(stopped=True)), flush=True)
            break
        else:
            raise ValueError('unknown fixture operation')
        print(json.dumps(response, ensure_ascii=False), flush=True)
finally:
    fixture.tearDown()
