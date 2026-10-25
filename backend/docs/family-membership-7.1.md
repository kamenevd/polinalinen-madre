# Family membership 7.1 — isolated server slice K3–K9

Base: `f6655599753f0224c2b1424194e1a2414c0b52af`, stacked on `feat/family-pb-auth-7-1-0`.
Wire reference: controller-frozen `madre-7.1-v2.md` at `de88baa`; no Android/Alice DTO changes.
This is source and isolated-runtime evidence, **not a deployment, phone acceptance or whole-7.1 PASS**.

## Implementation

- `1792886500_family_v2.js`: additive family/request/invite/removal metadata; private collections; hidden, server-managed user membership revision. Public email registration stays enabled with existing guards. No email/password/code removal, physical family purge, cron, service or edge change.
- `lib/family_v2.js` + `madre_membership.pb.js`: all family-v2 contract §3/4 operations; shared legacy create/invite/leave paths dispatch on `X-Madre-Family-Version: 2`. Unsupported supplied versions never mutate. Email-only records cannot invoke v2; existing email/password/code APIs remain available.
- Current `users.family` and a fresh JWT/auth rule check inside the pinned nonconcurrent transaction authorize each mutation/read. One owner; eight seats; pending grants no access; pending switch retains the original family. Approval rechecks identity, original membership revision, source-family leave rights, invite and owner generation, expiry and capacity. Requester-only status remains readable after invite invalidation. Decisions are idempotent only for matching, still-current outcomes.
- Family labels live in `family_members.display_name`; no global `users.name` or historical bake/book rename. Roster returns only current joined members; pending list is owner-only. Private durable `family_events` records each real join once; this slice does not invent a notification delivery API or claim UI notification delivery.
- Invites are exactly 16 base64url characters / 96 HMAC-derived bits, with an independent crypto-random nonce and seven-day expiry. One unrevoked row per family is enforced by a partial unique index. Rotation/revocation is atomic and invalidates pending generations and old restore capabilities.
- Separate `MADRE_INVITE_DERIVATION_KEY` (at least 32 secret bytes, different from UID salt and old invite pepper) is required for creation/current reconstruction. Domain: `madre.family.invite.v2` + NUL + family ID + NUL + random nonce. First 12 HMAC-SHA256 bytes, standard base64url without padding. DB stores nonce and SHA256(token), never token/URL. GET derives with the key and verifies the stored hash in constant time. A lost/replaced key cannot reconstruct old links; fail closed, do not reuse UID salt or pretend to reverse SHA256.
- Restore uses a one-use random 192-bit capability, SHA256-only storage and ten-minute TTL, bound to family/member/removal revision, current owner, security revision and removed user's membership revision. It cannot steal from another family, survive ownership/revocation/new-removal changes, or revive a deleted family. UI Undo remains eight seconds, outside this server slice.
- Old code join also rechecks capacity, removed-once/approval restrictions and deleted-family guards inside its transaction. Historical dormant-family recovery is restricted to that family's existing owner with proof, not arbitrary former members. Old owner leave cannot silently transfer to the oldest person; solo leave is a soft-delete. Old code rotation also revokes v2 capabilities. No 410 or registration shutdown.
- The former bake ACL used an immutable family snapshot. The additive migration changes READ authorization to current author membership (or the author themselves), retaining every bake row and existing protected photo flag. After removal/leave the old family loses bake/photo access while the author retains it. No extra photo permission or personal-diary changes.
- Preview budget 30/min/IP is shared between app and web; join 10/hour/user. Serialized private expiring counters survive runtime restart and ignore untrusted forwarded-IP headers under default PB trust configuration. Only expired abuse counters are opportunistically deleted; no user/family/bake retention purge.
- Invite paths, malformed/unknown invite paths, query, Referer and User-Agent are masked before PB activity logging; mutation errors are enum-only. Protected-file query capabilities are redacted on handler unwind inside the PB logger, after native authorization but before URL serialization. No new CORS/admin allowlist. Shared-edge masking remains a parent-only deployment gate.
- Request bodies are bounded at 65536 bytes. Response UTF-8 size is checked **inside** the transaction before commit, including oversized owner pending rosters.

## Reproduce the real isolated tests

Use the binary verified by `backend/tests/fetch_pocketbase.py`, not a production binary/config/DB:

```sh
export TMPDIR=/private/test-directory
export MADRE_PB_BINARY=/private/runtime/pocketbase
python3 -m backend.tests.fetch_pocketbase "$MADRE_PB_BINARY"
python3 -m unittest discover -s backend/tests -v
python3 -m unittest discover -s scripts/tests -v
```

The suite boots the byte-exact historical deployed family hook `15adb6359f1c83900aeaa068333187de0facbc38`, creates synthetic email accounts/family/bakes, stops **only its own child**, then upgrades that SAME SQLite database through the ledger, auth and membership migrations. Only the fixed external Yandex origin/protocol is changed in the private copied transport. Real curl, PB JWT/auth/rules, JSVM, SQL, transactions and HTTP routes remain in use. Old auth tests are retained; historical-column comparison permits additive schema columns and the old implicit-transfer assertion is explicitly superseded by the stronger K9 invariant. No test is skipped to hide a failure.

New cases exercise reconstruction across restart, expiry/revoke/uniform dead links, exact web fields, max-eight races (including old/new clients), approval races, idempotence when full, atomic switching and SQL ABORT rollback, removed-only approval, one-use/stale/cross-owner/cross-family restore, ACL/hidden-field modifiers, true protected photo upload/download/revocation, SQL read failures, owner-transfer/removal races, no duplicate joins/events, persistent concurrent rate limits, key failure, UTF-8 oversized roster rollback, soft-delete without physical purge, a consistent synthetic SQLite backup/hash/integrity and a separately booted restored runtime. Executable down/up retains the latest identities, membership, labels, invites, decisions and revocations. Full DB dumps/WAL/SHM/stdout/aux logs are scanned for synthetic invite/restore/PB/file/OAuth/raw-UID/profile/key canaries.

## Pinned API primary sources

Official PocketBase 0.39.11 source commit: `5d217ddb50cb144d80a5d0b0bdf11b52b2c3e457`.
Official Linux amd64 binary SHA256: `88370d5f6fa4820cd2414fa53c6e168d3dd0e33b7a7fd9ff914265492a7aa3b6`.
Primary source tree (all paths at that exact commit, not current online docs):

- `core/db_tx.go`: RunInTransaction uses NonconcurrentDB; callback app owns transactional readers/writers.
- `plugins/jsvm/binds.go`: crypto/random/toBytes, middleware binding and independent Goja runtimes; add constructed Field objects with `collection.fields.add`, not as an unconverted constructor fields array.
- `apis/middlewares.go`, `apis/middlewares_rate_limit.go`: logger priority -1040, URL.RequestURI serialization after inner handler, body/header/logging boundaries.
- `apis/record_crud.go`: native body modifier normalization and create-rule evaluation BEFORE request hooks. Hidden-field writes can be ignored with 200/no-op; durable immutable columns are the security assertion.
- `apis/file.go`, `core/event_request.go`: protected-file authorization and trusted-proxy-dependent RealIP.
- `tools/security/crypto.go`, `tools/security/random.go`: HMAC-SHA256, SHA256, constant-time Equal, crypto/rand alphabet sampling.

Existing auth source/runtime provenance stays in `pb-0.39.11-source-provenance.json`; the historical deployed-source receipt is not rewritten to pretend it is this new candidate.

## Gates deliberately still external

Parent owns independent review/integration, exact CI completion, fresh production email count/report and full fresh consistent production DB/storage/code/config/keys/unit/edge backups plus non-destructive rollback rehearsal before activation. No live DB/config/env has been read for fixtures. Genuine approved PocketBase lifecycle is still missing: do not add a helper, directly restart, close registration, deploy or work around that gate. Web/edge writer owns K11 page, escaped labels, no-store/no-referrer and shared-edge log masking; Android owns UI/notifications/App Links, Huawei physical fallback, K5 explicit login and remaining 7.1 integration. No merge/release/RuStore/skill publication/phone/personal-account test is authorized here.
