# Yandex A backend — parent review/deployment handoff

## Source and trust boundaries

The branch starts at **PR58 `564fbb9179885a420ebdf5b17e5bc9541a38cb46`**, exactly the installed backend; it does not import the Android writer's branch. Only backend source/tests/operations change. Android's serialized contract was read with `git show ef83a475606de9c99bae0c2434419f02a6dcc98b:app/src/main/java/com/polinalinen/madre/account/yandex/HttpsYandexAccountApi.kt`, not edited.

* `POST /alice/madre/voice/phone/yandex-bind`: existing enrolled `Authorization: Installation <43 base64url characters>` and JSON `{"yandex_token": "…"}`; 200 has exactly `uid_hash`, `login`, `display_name`, `avatar_id`, `avatar_empty`. Foreign client: 403 `foreign_client`; external failure/malformed response: 502 `yandex_unavailable`; missing configuration: 503. JSON/input rejection: 400. Unenrolled/malformed credentials: 401, **no implicit enrollment**.
* `POST /alice/madre/voice/phone/yandex-unbind`, same existing credential and `{}`: idempotent **204 only after committed revocation**. Deletes that installation's identity binding, revokes every local Dialogs grant/access/refresh family, kills commands/intents, invalidates pending approved challenges and unredeemed auth codes, clears selection/lease/snapshot. Other installations, including the same uid, are not revoked.
* Identity persistence is ONLY raw `uid`, technical `installation_id`, `bound_at`, and a monotonic per-installation epoch preventing in-flight bind resurrection after unbind. No psuid/login/name/avatar or Yandex access/refresh token in SQLite/disk/logs. Profile fields travel in the HTTP response for Android's local storage. `uid_hash` is stable HMAC-SHA256 with an installation-independent, protected random server salt; logs contain only that salted hash.
* Outbound HTTPS uses only `login.yandex.ru/info` (OAuth header) and `oauth.yandex.ru/token` (form body). No redirects, retries, cookies, proxy environment or body/error logging. TLS certificate verification enabled; body <=65536 bytes, caller <=2 seconds per external request, <=4 outstanding external workers even with hung DNS. Callback performs at most two such requests under the existing 6-second server deadline.
* `YANDEX_MATCH=1` adds browser-bound, random 256-bit RAM-only single-use inner state, 5-minute expiry, S256 PKCE, proper own Yandex client secret, fixed callback URI, and exact existing broker client/scope/redirect validation. The preserved outer state is never substituted with the inner state. Used external codes have a bounded RAM-only hash replay fence as well as provider enforcement.
* **Login + selected bake is NOT voice consent.** UID auto-match is eligible only when exactly one bound installation already has an unexpired/unrevoked Dialogs grant for its *current* selected bake/generation. This is safe seamless reauthorization, not silent first-time overgrant. First-time/no prior voice authority, unknown/wrong uid, revoked/changed selection, or ambiguous eligible phones renders the original pairing form and preserves the real preview → pair-approve → confirm → token flow. Auto-match logs `match=uid uid_hash=…`; broker 303/code is emitted only for legitimate existing authority. Consent expansion would require a separately approved explicit phone consent contract; no consent is invented here.
* Poll/enroll/select/ack serialization is unchanged (including `linked`); no invented poll event or Station acceptance is claimed.

## Configuration — parent only, values never in chat/argv/PR

Keep `/root/.secrets/madre-alice-voice.env` and `/root/.secrets/madre-alice-auth.env` unchanged. They contain the *existing Dialogs broker* and legacy static credentials, **not the Yandex ID client**. Never replace `MADRE_ALICE_OAUTH_CLIENT_ID`/`MADRE_ALICE_OAUTH_CLIENT_SECRET` or exact scope `bake:read&bake:control` with Yandex ID registration fields.

Parent reads existing protected `/root/.secrets/madre-yandex-oauth.json` fields `MADRE_YANDEX_CLIENT_ID` and `MADRE_YANDEX_CLIENT_SECRET` without emitting values, and prepares a separate root-owned 0600 `/root/.secrets/madre-yandex-server.env` with:

* `MADRE_YANDEX_CLIENT_ID`: that same Android/Web own client.
* `MADRE_YANDEX_CLIENT_SECRET`: the corresponding Web confidential secret.
* `MADRE_YANDEX_UID_SALT`: newly generated cryptographically random >=32-character secret, retained stably through restart/rollback. Never rotate casually: hashes would change.
* `YANDEX_MATCH=1` only after parent review/config/route verification; default is 0.

Parent-owned, narrowly named `/etc/systemd/system/madre-alice-voice.service.d/yandex-a.conf` adds only that EnvironmentFile plus `LimitCORE=0` and `MemorySwapMax=0` (prevent RAM tokens entering a crash core/swap), preserving DynamicUser/User, sandbox, existing env files, working directory, port8743 and DB path. Check effective cgroup swap/core settings on the target; no debug HTTP trace, exception locals, request-body logging, profiler/core collector. Parent must preserve preconfiguration backup and exact drop-in/env digests for removal of ONLY its owned additions on rollback.

The existing edge may be exact-route allowlisted: **parent must add/verify only** POST `…/phone/yandex-bind`, POST `…/phone/yandex-unbind`, GET `…/oauth/yandex/callback`, unchanged upstream8743. Existing phone/OAuth routes remain. Never broaden generic `/api` or proxy the legacy reader8741. No edge/console mutation is made by this branch or tool. Confirm the exact callback is already registered in the existing Yandex client; no registration/name/visibility/webhook/credential change is authorized here.

## Local verification

```sh
python3 -m unittest discover -s backend/alice/tests -v
python3 -m compileall -q backend/alice
```

The suite runs the 37 unchanged legacy reader/voice/security tests plus real local HTTP/SQLite Yandex acceptance and deployment fault fixtures. Only the external Yandex HTTPS peer is mocked in account tests; repository, grants, refresh rotation, revocation, pairing and HTTP are real. Full SQL dump, DB/WAL/SHM bytes and captured logs are checked for token/profile/secret absence after EVERY Yandex HTTP test. Fake profile/token strings are explicitly synthetic, never fetched from production. Browser redirects are inspected, never followed to the real provider or social broker.

## Backup, stage, deployment, rollback

`ops/deployment.py` is a tested file transaction utility, **not a service manager**. It never calls start/stop/restart, touches edge, activates config, or edits secrets. It refuses pointer switching unless the service is already inactive through the genuine sanctioned lifecycle path. A serviceguard refusal is a blocker, not permission for shell/subprocess/native stop/start/kill bypass; parent must obtain the normal narrowly authorized helper/transaction capability. Do not run switch while an active process still owns the old files.

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

After sanctioned activation parent reads back current symlink/source hashes, effective settings booleans (not values), non-root service state8743, DB integrity/schema2, legacy service8741/PID/authenticated reader unchanged, and public endpoint method/auth behavior. Use only a separately authorized synthetic installation; do not call real Yandex/token endpoint or broker for synthetic acceptance. Real Huawei/SDK/owner/Station acceptance remains separate.

If rollback is needed, sanctioned lifecycle must first quiesce ONLY candidate, then:

```sh
python3 "$T" rollback --backup "$B" --target "$TARGET"
# Parent's sanctioned helper activates the exact old pointer normally.
```

**Keep the latest DB.** Migration1→2 adds only `yandex_bindings` and `yandex_epochs`/index. Old code ignores additive tables; never restore an earlier DB automatically, which would resurrect revoked grants or erase post-deploy records. The legacy executable sets `user_version=1` on its next startup; the new initializer explicitly accepts1 and recreates/retains its additive tables atomically. Any newer unknown schema or current DB corruption refuses automated rollback and needs reviewed recovery. The validated DB snapshot is an emergency recovery artifact, not the normal rollback action.

The utility restores ONLY its owned `current` pointer. Parent separately reverts ONLY its exact owned edge delta/drop-in (using the preconfiguration backup and postimage fences) if necessary; legacy env/auth/unit and old skill remain intact. Never use the previous23October full side-by-side rollback helper to disable this previously installed service or undo unrelated shared edge changes. No production rollback/restart/deploy is claimed here.

## Upstream references reviewed

Yandex official `codes/code-url` documents S256/code_verifier and confidential code exchange; `user-information` documents OAuth header and `client_id`/`id` response. This implementation deliberately overrides any provider suggestion to save access tokens: tokens are request-local RAM only.
