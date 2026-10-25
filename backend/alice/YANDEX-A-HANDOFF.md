# Yandex A backend — parent review/deployment handoff

## Source and trust boundaries

The branch starts at **PR58 `564fbb9179885a420ebdf5b17e5bc9541a38cb46`**, exactly the installed backend; it does not import the Android writer's branch. Only backend source/tests/operations change. Android's serialized contract was read with `git show ef83a475606de9c99bae0c2434419f02a6dcc98b:app/src/main/java/com/polinalinen/madre/account/yandex/HttpsYandexAccountApi.kt`, not edited.

* `POST /alice/madre/voice/phone/yandex-bind`: existing enrolled `Authorization: Installation <43 base64url characters>` and JSON `{"yandex_token": "…"}`; 200 has exactly `uid_hash`, `login`, `display_name`, `avatar_id`, `avatar_empty`. Foreign client: 403 `foreign_client`; external failure/malformed response: 502 `yandex_unavailable`; missing configuration: 503. JSON/input rejection: 400. Unenrolled/malformed credentials: 401, **no implicit enrollment**.
* `POST /alice/madre/voice/phone/yandex-unbind`, same existing credential and `{}`: idempotent **204 only after committed revocation**. Deletes that installation's identity binding, revokes every local Dialogs grant/access/refresh family, kills commands/intents, invalidates pending approved challenges and unredeemed auth codes, clears selection/lease/snapshot. Other installations, including the same uid, are not revoked.
* Identity persistence is ONLY raw `uid` plus technical binding, monotonic epoch, narrowly scoped consent evidence and issued-code provenance. No psuid/login/name/avatar or Yandex access/refresh token in SQLite/disk/logs. Profile fields travel in the HTTP response for Android's local storage. `uid_hash` is stable HMAC-SHA256 with an installation-independent, protected random server salt; logs contain only that salted hash. Initial installation authentication, selection, binding uid and epoch are one SQL snapshot. Pair-approve rechecks selection AND snapshot epoch inside its write transaction; bind uses that initial epoch, never a later read. Logout/reselection ABA cannot resurrect a delayed action.
* Outbound HTTPS uses only `login.yandex.ru/info` (OAuth header) and `oauth.yandex.ru/token` (form body). No redirects, retries, cookies, proxy environment or body/error logging. TLS certificate verification enabled; body <=65536 bytes, caller <=2 seconds per external request, <=4 outstanding external workers even with hung DNS. Callback performs at most two such requests under the existing 6-second server deadline.
* `YANDEX_MATCH=1` adds browser-bound, random 256-bit RAM-only single-use inner state, 5-minute expiry, S256 PKCE, proper own Yandex client secret, fixed callback URI, and exact existing broker client/scope/redirect validation. The preserved outer state is never substituted with the inner state. Used external codes have a bounded RAM-only hash replay fence as well as provider enforcement.
* **Login + selected bake is NOT voice consent.** UID auto-match requires exactly one eligible bound phone: EITHER fresh evidence from the separately approved explicit phone action below OR an unexpired/unrevoked Dialogs grant for its *current* bake/generation (reauthorization). First-time select+bind alone, unknown/wrong uid, expired/consumed evidence, revoked/changed selection or ambiguous eligible phones renders the original pairing form, with zero new grants/codes, preserving the real preview → pair-approve → confirm → token flow. Auto-match logs `match=uid uid_hash=…`; broker 303/code is emitted only with that authority. Both paths retain source uid/epoch/scope provenance, and redeem rechecks binding, selection, revocation and evidence expiry or source grant validity.
* Poll/enroll/select/ack serialization is unchanged (including `linked`); no invented poll event or Station acceptance is claimed.

## Explicit phone action — exact Android wire contract

The signed-in phone first displays an explicit warning that Alice can **read the
current bake step and control its timer**. Only a user tap on the existing
**«Подключить Алису»** button after that warning calls:

* `POST /alice/madre/voice/phone/yandex-voice-consent`
* `Authorization: Installation <43 base64url characters>` (existing enrolled credential)
* JSON exactly `{"bake_id":"<UUID>","generation":"<UUID>"}`. These map to the
  exact current `/select` bake_key/selection_generation; they cannot select a bake.
* Success **200**, JSON exactly `{"expires_at":<Unix epoch milliseconds>}`.
  Default lifetime **300 seconds**. No additional cookie, token, profile or scope field.
* 401 unknown/malformed auth (never enrolls); 400 wrong fields/types; 409 no bound
  uid/current matching selection or auth-snapshot race. SQLite lock is 503, not false success.

There is no call from login/bind/select, screen opening, SDK/auth callback,
background worker or automatic retry. These are never implicit first consent.
Android must not automatically retry this action after process death, reconnect,
expiry or revocation: a new request then requires a fresh user tap and warning.
Duplicate deliveries during the same five-minute lifetime return the original
expires_at, without extending TTL or re-arming consumed evidence. This is a
defensive delivery guard, not evidence of another user action. After expiry,
another explicit tap may record new evidence with the same two-field body.

Server persists only technical evidence for the bound uid + installation + epoch
+ bake/generation + exact existing scope `bake:read&bake:control`, timestamps and
consumed marker. The phone POST itself does not issue a grant or code. Only actual
broker auth-code issuance consumes it, exactly once and atomically in the same
SQLite transaction. Fallback, invalid state/cookie/client, wrong uid, ambiguity,
provider failure, aborted code insertion or replay cannot consume/re-arm it.
Logout/disconnect/reselection/uid change/grant revocation invalidate evidence and
pending shortcut authority. Installation deletion cascades evidence; schema1/2
binding/epoch FKs retain RESTRICT, so their existing deletion ordering still applies.
No Station `surface` is knowable here: keep `linked: boolean`; do not synthesize
`alice_linked{at,surface}`. This wire contract requires no change from the approved
two-field request and one-field 200 response; Android implementation is separate.

## Configuration — parent only, values never in chat/argv/PR

Keep `/root/.secrets/madre-alice-voice.env` and `/root/.secrets/madre-alice-auth.env` unchanged. They contain the *existing Dialogs broker* and legacy static credentials, **not the Yandex ID client**. Never replace `MADRE_ALICE_OAUTH_CLIENT_ID`/`MADRE_ALICE_OAUTH_CLIENT_SECRET` or exact scope `bake:read&bake:control` with Yandex ID registration fields.

Parent reads existing protected `/root/.secrets/madre-yandex-oauth.json` fields `MADRE_YANDEX_CLIENT_ID` and `MADRE_YANDEX_CLIENT_SECRET` without emitting values, and prepares a separate root-owned 0600 `/root/.secrets/madre-yandex-server.env` with:

* `MADRE_YANDEX_CLIENT_ID`: that same Android/Web own client.
* `MADRE_YANDEX_CLIENT_SECRET`: the corresponding Web confidential secret.
* `MADRE_YANDEX_UID_SALT`: newly generated cryptographically random >=32-character secret, retained stably through restart/rollback. Never rotate casually: hashes would change.
* `YANDEX_MATCH=1` only after parent review/config/route verification; default is 0.

Parent-owned, narrowly named `/etc/systemd/system/madre-alice-voice.service.d/yandex-a.conf` adds only that EnvironmentFile plus `LimitCORE=0` and `MemorySwapMax=0` (prevent RAM tokens entering a crash core/swap), preserving DynamicUser/User, sandbox, existing env files, working directory, port8743 and DB path. Check effective cgroup swap/core settings on the target; no debug HTTP trace, exception locals, request-body logging, profiler/core collector. Parent must preserve preconfiguration backup and exact drop-in/env digests for removal of ONLY its owned additions on rollback.

### Required runtime/config test (parent gate, NOT performed by the codewriter)

Before any real Yandex token reaches the candidate, require all of:

1. `systemctl show madre-alice-voice.service -p LimitCORE -p MemorySwapMax`
   reports **LimitCORE=0** and **MemorySwapMax=0**, not just matching unit-file text.
2. For the actual non-root MainPID, `/proc/<pid>/limits` has both soft/hard
   `Max core file size` equal to **0**.
3. The actual service cgroup's `memory.swap.max` is **0** and
   `memory.swap.current` is **0**; parent verifies the effective cgroup path,
   not a guessed file. No token-bearing child may escape these limits.
4. Existing confinement, env ownership/modes and request/log redaction remain.
   Missing/unreadable limits, infinity/max or nonzero swap usage fail this gate.

Local synthetic tests cannot certify that live runtime gate. No drop-in, daemon
reload, service lifecycle or cgroup mutation is performed in this branch.

The existing edge may be exact-route allowlisted: **parent must add/verify only** POST `…/phone/yandex-bind`, POST `…/phone/yandex-unbind`, POST `…/phone/yandex-voice-consent`, GET `…/oauth/yandex/callback`, unchanged upstream8743. Existing phone/OAuth routes remain. Never broaden generic `/api` or proxy the legacy reader8741. No edge/console mutation is made by this branch or tool. Confirm the exact callback is already registered in the existing Yandex client; no registration/name/visibility/webhook/credential change is authorized here.

## Local verification

```sh
python3 -m unittest discover -s backend/alice/tests -v
python3 -m compileall -q backend/alice
```

The suite runs the 37 unchanged legacy reader/voice/security tests plus real local HTTP/SQLite Yandex acceptance and deployment fault fixtures. Only the external Yandex HTTPS peer is mocked in account tests; repository, grants, refresh rotation, revocation, pairing and HTTP are real. Full SQL dump, DB/WAL/SHM bytes and captured logs are checked for token/profile/secret absence after EVERY Yandex HTTP test. Fake profile/token strings are explicitly synthetic, never fetched from production. Browser redirects are inspected, never followed to the real provider or social broker.

`tests/test_yandex_consent_races.py` adds review-window regressions: barriers pause
after the real initial auth SQL, BEFORE the former late epoch read (B2), or before
the approve write (B1). On unchanged f8ad2536, both B1 cases returned200 and B2
returned200/binding restored; corrected assertions require400/409/no authority.
First-link test starts with zero grants: select+bind -> fallback, explicit phone
POST -> callback303 -> real token200 -> poll linked=true -> real webhook reads
the published bake. It also tests expiry, code/POST replay, UID/eligible-phone
ambiguity, logout/reselection/uid-change races, redeem-time fences, real SQLite
ABORT rollback, lock ACK, additive schema2→3 and rollback/re-upgrade fencing.

## Backup, stage, deployment, rollback

`ops/deployment.py` is a tested file transaction utility, **not a service manager**. It never calls start/stop/restart, touches edge, activates config, or edits secrets. It refuses pointer switching unless the service is already inactive through the genuine sanctioned lifecycle path. A serviceguard refusal is a blocker, not permission for shell/subprocess/native stop/start/kill bypass; parent must obtain the normal narrowly authorized helper/transaction capability. Do not run switch while an active process still owns the old files.

**Known capability blocker (parent-owned):** the actual sanctioned
`/usr/local/sbin/hermes-restart-helper` has no `madre-alice-voice` allow-entry per
the parent handoff. Do not invent a service installer, hook, alternate helper,
native call, direct systemctl/kill or another channel to bypass it. This child
does not modify the helper or attempt lifecycle calls. A green backend CI is not
deployment authorization or a claim that this capability exists.

The following commands are a parent handoff, **not executed on production in this child stage**. Replace `SOURCE_SHA` with the exact reviewed full commit, not a branch name; `B` with a new dedicated root-only backup directory whose parent already exists. Run with umask077. Use the source checkout's utility for both deploy and rollback (never a possibly replaced live copy):

```sh
S=/root/.hermes/work/madre-yandex-A-server-20261024
T=$S/backend/alice/ops/deployment.py
# BEFORE any config changes: initial protected backup (includes legacy code/env/unit).
python3 "$T" backup --backup "$PRECONFIG_BACKUP"
python3 "$T" check --backup "$PRECONFIG_BACKUP"
# Parent alone prepares the narrowly owned env/drop-in and permitted edge delta.
# Then take the final, config-fenced online backup immediately before deployment.
python3 "$T" backup --backup "$B"
python3 "$T" check --backup "$B"
python3 "$T" stage --source "$S" --sha "$SOURCE_SHA"
TARGET=/opt/madre-alice-voice/releases/$SOURCE_SHA/backend/alice
# Parent's sanctioned lifecycle transaction quiesces ONLY madre-alice-voice.
# If refused, STOP: do not emulate this through another mechanism.
python3 "$T" switch --backup "$B" --target "$TARGET"
# Parent's sanctioned helper starts/restarts ONLY that candidate normally.
```

Backup creates a dedicated0700 directory, protected0600 code/config archive and SQLite online `.backup` equivalent, checks `integrity_check`, computes SHA256 and reads every archived member back against its source manifest. It includes the untouched legacy reader and both existing env files; archive contents are private and must never be posted. A main-file-only copy cannot substitute for the online backup with live WAL. Initial and final backup are separate because config fencing refuses intervening changes. Re-take a fresh final backup if parent changes config before switching.

Stage uses exact committed Git archive into a *new* owned release; refuses dirty source, existing release, non-full SHA, symlink/traversal entries. Switch uses exact original/current/candidate/backup/config/legacy hashes, atomic symlink replacement and readback. No shared edge write, no legacy restart, no permissions expansion.

After sanctioned activation parent reads back current symlink/source hashes, effective settings booleans (not values), required core/swap gate above, non-root service state8743, DB integrity/schema3, legacy service8741/PID/authenticated reader unchanged, and public endpoint method/auth behavior. Use only a separately authorized synthetic installation; do not call real Yandex/token endpoint or broker for synthetic acceptance. Real Huawei/SDK/owner/Station acceptance remains separate.

If rollback is needed, sanctioned lifecycle must first quiesce ONLY candidate, then:

```sh
python3 "$T" rollback --backup "$B" --target "$TARGET"
# Parent's sanctioned helper activates the exact old pointer normally.
```

**Keep the latest DB.** Schema3 adds `yandex_voice_consents` and `yandex_code_authority` to the schema2 uid-binding/epoch tables. Migration1/2→3 is additive, atomic and preserves existing data/revocations. Old code ignores additive tables and sets `user_version=1` on startup. On every re-upgrade from1/2 the new initializer clears pre-rollback phone evidence, marks pending UID shortcut codes used and advances epochs: old code cannot maintain the new revocation fences, so its ignored evidence must never revive. Normal schema3 restart does not renew consent. The deployment utility accepts latest schema1/2/3 for pointer rollback, refuses newer unknown schema/corruption, and NEVER restores an earlier DB automatically. Restoring backup DB could resurrect revoked grants or erase post-deploy records; the validated snapshot is an emergency recovery artifact, not the normal rollback action.

The utility restores ONLY its owned `current` pointer. Parent separately reverts ONLY its exact owned edge delta/drop-in (using the preconfiguration backup and postimage fences) if necessary; legacy env/auth/unit and old skill remain intact. Never use the previous23October full side-by-side rollback helper to disable this previously installed service or undo unrelated shared edge changes. No production rollback/restart/deploy is claimed here.

## Upstream references reviewed

Yandex official `codes/code-url` documents S256/code_verifier and confidential code exchange; `user-information` documents OAuth header and `client_id`/`id` response. This implementation deliberately overrides any provider suggestion to save access tokens: tokens are request-local RAM only.
