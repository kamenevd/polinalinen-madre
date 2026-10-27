# Stream B: backend-only PR63–66 integration

Local baseline: `7af7c3297acb3e41b77491c0c42c0a4c8e396072`.
This is an integration candidate, **not a deployment**. Android source,
quality-gates/release workflows, feature flags and version numbers are unchanged.

## Source order and dependencies

1. PR63 `f6655599753f0224c2b1424194e1a2414c0b52af`: `backend/`,
   `scripts/tests/test_family_backend_contract.py`, and only the lightweight
   `pocketbase-auth.yml` workflow. Runtime code originated at `d4dede1`; runner
   path fix at `72fcc39`. No historical quality workflow/selector is imported.
2. PR65 `9a2ef09aad262dc44576393a81e179ae90cb2ed3`: stacked on PR63;
   transactional family membership and the workflow's test label update.
3. PR64 `f1380c21665ebd51fff1da856cdd3164ad36ba9c`: `backend/alice/` and
   `alice-backend.yml` only. The Android baseline did not contain Alice runtime,
   so include its required backend ancestors `c47064b`, `f8ad253`, `9cc4dcd`,
   `d499917` rather than importing their historical Android/workflow changes.
4. PR66 `18aef851a5c5fa8f969cf812a8c90b792846967a`: installation pairing;
   `docs/contracts/madre-7.1-installation-pair.md` already has identical bytes
   in the baseline and needs no duplicate edit.
5. Local verification additions under `backend/tests/` and this document.

Source PR CI was green at those exact four heads (runs `40156508503`,
`40160917885`, `40153360292`, `40169370372`, respectively; parent-verified).
That is **not** CI for the integrated head. No push/dispatch was authorized here.

## Local verification

Use the official PocketBase 0.39.11 binary fetched by
`python3 -m backend.tests.fetch_pocketbase <new-scratch-path>/pocketbase`;
its SHA256 must be
`88370d5f6fa4820cd2414fa53c6e168d3dd0e33b7a7fd9ff914265492a7aa3b6`.

```sh
MADRE_PB_BINARY=<verified-scratch-binary> python3 -m unittest discover -s backend/tests -v
python3 -m compileall -q backend/alice
python3 -m unittest discover -s backend/alice/tests -v
python3 -m unittest discover -s scripts/tests -v
python3 -m backend.tests.run_android_producer --evidence <private-evidence>/producer
```

The producer command uses cached JVM dependencies, **not Gradle**. It compiles
the unchanged production `RosterV2.kt`, `ContractHttp.kt`, `StrictJson.kt`,
`InstallationPairConsent.kt`, flags and receipt entity. Only generated
`BuildConfig.YANDEX_ID=false` is supplied. It uses real OkHttp/Gson, strict
response parsing, and real integrated Alice HTTP/SQLite; only the socket
destination and external Yandex peer are test boundaries. It exercises explicit
pairing, zero/four-state snapshots, repeat attach, confirmed numbered command,
actual ack serialization, duplicate/replay, empty replacement and revocation.
A synthetic receipt is not protected Android executor/Room/phone acceptance.

The inherited Alice suite executes exact d499/f138 readers in separate processes
on the **latest** additive DB and checks revocation after old unbind. The inherited
PB suite checks same-database historical migration, additive down/up, legacy
password/code, same-record identity binding, current-membership photo ACLs,
restored isolated synthetic backup and secret-canary scans.

## Old-writer rollback fence — review/deployment gates still apply

PB's inherited `test_98_executable_rollback...` only removes a migration receipt
and restarts **new hooks**. It does not establish old-hook rollback safety.

An additional real probe boots the exact old baseline family hook on the latest
synthetic upgraded DB, preserving latest account writes, identity binding and
ACLs. The removed member initially cannot read the family bake; however old
`/family/join` accepts its still-valid legacy email code (HTTP 200), bypassing
the retained `removed_once` record and restoring membership. The new hook denies
that same path. This is a real unsafe old-writer rollback, not a schema failure.
The probe returns **2 / rollback_blocked**, records the latest-data results,
then returns to the candidate and verifies re-revocation without rewinding DB.

```sh
MADRE_PB_BINARY=<verified-scratch-binary> python3 -m backend.tests.probe_old_pb_reader \
  --reader-hook <byte-exact-public-old-hook> --reader-sha256 <sha256> \
  --reader-source <full-source-commit> --evidence <private-evidence>/pb-old-reader.json
```

The additive `1792886600_family_rollback_fence.js` now retains a SQL security
floor across executable rollback. A `users.family` transition into a family with
`removed_once` is denied unless the validated v2 transaction mints a one-save
permit. An AFTER trigger consumes it on the user save, BEFORE the normal ledger
save. This deliberately does not trust ledger `active` state: approval followed
by a later old-hook leave must not leave reusable authorization. SQL failure
rolls back both the permit and membership. The private SQL table has no API
collection, and no old hook bytes or probe assertions are changed.

Migration down retains both triggers, the permit table and all latest rows;
re-up is idempotent. **A rollback artifact MUST retain this database security
floor and additive schema/ACLs.** Do not drop triggers/table, restore an old DB,
clear codes/passwords/accounts, or treat migration receipt removal alone as a
security pass. Old code without this retained database fence remains unsafe.

Real pinned PB tests run the unchanged negative-before probe against both exact
old hooks above: fenced-after returns HTTP 400, retains removal and latest name,
and reports `passed` / integrity `ok`. Additional tests exercise owner restore,
forced SQL failure/permit rollback, owner-approved pending acceptance, both old
leave orderings followed by denied rejoin, working historical password and
legitimate legacy email-code joins. Fresh joins remain enabled. Evidence lives
in the private `fence-stage` directory; parent independent review is still due.

## Remaining parent-owned gates

- Integrated-head CI and independent review; source PR green is insufficient.
- Independently review the retained PB fence; repeat latest-data and revocation
  tests for the exact deployment/rollback artifact before deployment approval.
- Genuine PocketBase lifecycle remains unresolved. Alice may use only the
  existing exact `madre-alice-voice` helper; neither service was touched here.
- Before a later deployment: fresh complete DB/storage/code/config/env/keys/unit/
  edge backup, verified reconstruction and fenced rollback retaining latest data.
- Registration remains enabled. Count/report actual email accounts again before
  any separately controlled registration cutover; preserve password/code and
  same-record binding. Existing 7.0.14/16/53 paths remain present.
- Actual Android protected executor/native/Station acceptance, K11 edge page/
  assetlinks/log masking and Huawei fallback remain outside local server tests.
- `MADRE_ALICE_ROSTER_V2` and Android `FAMILY_ROSTER_V2` stay off by default.

The external exact-head JSON packet supplies full local commit IDs, commands,
counts, evidence and ordered cherry-pick instructions. A integrates only those
backend commits into its own working branch after review; B does not push to
feature/main and does not merge/release/deploy.