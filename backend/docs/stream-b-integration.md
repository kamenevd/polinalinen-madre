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

## Discovered rollback blocker — do not deploy

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

Until an independently reviewed rollback fence or retained compatible security
layer is implemented and tested against this probe, reverting the whole PB hook
tree is forbidden. Do not work around this by restoring an old DB, clearing old
codes/passwords/accounts, calling a migration receipt test a rollback pass, or
loosening the assertion. No production safety behavior was rewritten as part of
the source transplant.

## Remaining parent-owned gates

- Integrated-head CI and independent review; source PR green is insufficient.
- Resolve the demonstrated PB old-writer rollback issue; then repeat latest-data
  and revocation tests for the exact deployment/rollback artifact.
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