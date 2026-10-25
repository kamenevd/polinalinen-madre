# Roster-v2 — code-only B8–B10 backend slice

Base: deployed/reference source `d4999178a53571304642486c13388460cfa1ec86`.
This branch is additive to PR #62; it does not replace that PR or the issued
7.0.16 Android build. No deployment, real provider login, Station or phone tests
are part of this change.

## Frozen wire contract

Implements Alice §§1,5,6 of `docs/contracts/madre-7.1-v2.md` from Android source
`4e55c7a2b65bd44137e99ef83abd6a68fa0da487`. Contract bytes are unchanged,
SHA256 `14723a81baeb14e53616eb6c9e9fe4268f6e10b13fe5109e220f274c21cd94f1`.
No family/PocketBase or Android source changes here.

`MADRE_ALICE_ROSTER_V2` defaults **off**, and only exact `1` enables it.
Disabled `/phone/v2/*` returns 426 unsupported_protocol without authority mutation.
Do NOT enable it until parent has accepted the real Android adapter/executor,
security review, capabilities/integration gate and QA e2e.

All requests below use the existing Installation credential and JSON/no-store,
are bounded to 65536 bytes, and reject bool-as-integer, malformed/case-normalized
UUIDs, unknown fields, duplicated JSON fields and nonfinite numbers.

* `/v2/attach`: protocol_version=2, consent_scope=installation_current_bakes,
  consent=true. Requires an existing current confirmed **paired grant**, not just
  enrollment, selection, Yandex login or old five-minute single-bake consent.
  This is a conservative bootstrap using the already confirmed pair: the new
  installation grant is a separate connection row and never mutates/promotes
  the old bake-scoped grant. Only its exact source paired grant's live bearer
  accesses the roster. Source grant selection is newest created_at, then id.
  Repeated active attach preserves connection and original expiry; no implicit
  consent renewal. Expiry cannot outlive the source grant. No UID/profile/token
  fields are added. Parent must integrate this explicit-pair bootstrap into the
  Android foreground flow, not assume login alone attaches v2.
* `/v2/poll`: version 2, connection_id, roster_revision, full bakes[0..9].
  Full atomic replacement, canonical full-roster digest, monotonic roster and
  per-key revisions; unchanged revision/state digest is idempotent. Remaining
  time may change only with a new roster revision. Resource number is stable
  per bake_key. Empty list immediately removes all snapshots. Different/new
  connection cannot write until explicit attach; restart revokes all v2
  connections, requiring fresh scoped attach. Commands are dispatched once,
  after pinned action confirmation and revalidation against this exact roster.
* `/v2/ack`: exact contract fields connection_id, command_id, lease_id,
  lowercase outcome and resulting_revision. Reject wrong installation, guessed
  lease, undispatched/never-dispatched expired command and revoked connection.
  Matching duplicate is idempotent; different payload conflicts. Applied requires
  expected_revision+1. A late receipt for a previously dispatched command is
  evidence only; it never re-dispatches or mutates the bake snapshot.
* `/v2/disconnect`: connection_id; 204/no body. Current disconnect revokes the
  v2 installation grant, commands, leases, pending and snapshots, and the source
  legacy authority so there is no silent v1 fallback. Old/revoked connection
  retries are 204 but cannot revoke a newer attach. Reattach after a disconnect
  needs a new confirmed pair, then explicit v2 scope consent.

Legacy enroll/pair/poll/select/ack/bind/unbind/consent and the protected executor
are retained. Reselection, identity epoch change, old disconnect/unbind and source
OAuth replay/revocation invalidate v2 as well. Bearer validity is checked again
inside the same transaction that creates confirmation/command authority.

## Dialogue and command authority

Number references 1–9: digits/№, cardinal and ordinal inflections, both positions,
number/bake/preposition wrappers, ё normalization and fillers. Recipe-token
stems may identify several resources and then require clarification. Time and
repetition operands are never numbers. Different command/original intent OR
resource is ambiguous and cannot execute.

0/1/many, unknown number, pending clarification 60 seconds, scheduled/finale
read-only, trusted-only text/quantities, formal «вы» and «Голос закваски».
Clarification pins original candidate keys/state too, not only a recycled number.
Every action needs a second turn; confirmation pins connection/key/number/recipe
revision/step/state/revision. Cancel only accepts «подтверждаю отмену».
ADVANCE cannot complete the last step. Back at step zero, redundant pause/resume,
untrusted control and stale phone state fail closed.

Each command carries an immutable random UUID lease, key/number, expected
revision/step/paused and recipe revision; TTL <=4000ms and never beyond grant
expiry. The server does not apply Android state. It reports applied only after
an authenticated matching phone receipt, otherwise the outcome is unknown.
Synthetic HTTP acknowledgments below do NOT prove phone execution.

## Additive DB / retention / rollback

New `roster_v2_*` tables, indexes and triggers only; legacy columns and
`PRAGMA user_version=3` remain readable by exact d499. Table namespace is the
additive schema version. Do not restore an old SQLite snapshot on rollback.
Keep the **latest DB**, including connection revocations/receipt dedup evidence.
SQL triggers are intentionally installed in that DB: even unchanged old d499
unbind/grant revocation invalidates v2. New executable startup revokes old v2
connections, including after a rollback interval.

Only current snapshot JSON is kept; missing resources disappear on replacement.
Lost-contact snapshots expire at nine minutes. Server `service_actions` sweeps
without incoming requests, with a one-second cadence plus bounded SQLite wait;
reads/control additionally fail closed on stale state. No recipe text/name in
pending, version fences or commands: they retain only keys, numbers, revisions,
state fingerprints and technical authority. Commands/receipts have a 24-hour
bounded retention; revocation tombstones and per-key anti-ABA fences remain.
This is logical snapshot deletion, not a claim of forensic secure erasure.

## Reproducible local verification

```
python3 -m compileall -q backend/alice
python3 -m unittest discover -s backend/alice/tests -v
```

`test_roster_v2.py`: real SQLite + loopback Handler/server, full replace and SQL
rollback, competing poll/confirmation/dispatch, auth-snapshot and classification
revoke races, lease/ack/replay, disconnected/expired/current state, oldreader
and numbered dialogue. NLU independently lists 177 references and exercises
5310 intent/position/filler combinations. Existing tests remain; only four
legacy speech expectations are updated from informal to formal address.

`tests/fixtures/d499-reader.tar.gz` contains **unchanged** d499 runtime files
including its recipes catalog, with exact source SHA and per-file/archive SHA256
in `d499-reader.json`. It runs as a separate real HTTP reader on the latest
additive DB, executes old poll and old unbind, then the new reader verifies
revocation and integrity. No git/network/production dependency in this test.
All external Yandex HTTPS calls alone use the existing synthetic provider peer.

## Still required — not server-ready / not all of 7.1

1. Parent: exact-head CI/review and acceptance of consent bootstrap, bounded
   lease semantics, UTC-labelled scheduled time and reattach/re-pair UX.
2. Android: explicit new scope UI, persisted trusted roster adapter; foreground
   attach/disconnect; real protected lease/executor/atomic receipt+revision,
   replay handling and commit-time revalidation under Application mutex. The
   current policy alone is not an executor. B7 UI/native acceptance is separate.
3. Parent: capabilities/enable gate; fresh full runtime/data/config/credential
   backups and isolated tested rollback retaining latest data and revocations;
   approved deployment and real QA e2e only after those gates. No action here
   changes current service/config/live DB/edge or any real user.
4. PocketBase/family and remaining 7.1 UI/App Links work belong to other writers;
   this backend slice does not claim K1–K10 or B10 end-to-end acceptance.
