# Android roster-v2 / B7–B10 — code-only

Base: `67f52a15cd2f5848ea919546dde9997baca1b800`, PR61. `FAMILY_ROSTER_V2` remains **false**. Version stays unissued 7.1.0 (53); issued APK14/15/16, latest/INDEX and Room12 schema are not changed.

## Implemented source path

* `BakingViewModel.persistedRoster`: Application operation mutex → retained controller transition mutex → actual Room rows / committed plan preferences / strict finale reservations. Full current roster 0..9; no history/photo/family/account payload. Stable persisted keys/numbers and exact active revision, recipe digest, scale/step/time trust fences; unknown recipe/portions are read-only and omit quantities/text/portions. A failed plan/finale read is **not** an empty replacement. Matching live sessions retain the phone's monotonic clock instead of re-timing from wall clock on every poll.
* Plans acquire a technical incarnation key and revision in the **same preferences commit** as the plan only when v2 is enabled. Existing plans retain their legacy deterministic key. Edits advance the plan revision; planned start carries key/number and advances revision before inserting the actual active Room row. Removal/recreation mints another incarnation, not the recycled number as identity. Finales persist the active key/revision/recipe digest as additive optional JSON metadata; old finals stay read-only. No Room migration or destructive fallback.
* `RosterCoordinator`: explicit installation-wide scope warning/tap → existing protected installation identity → strict attach. ATTACHED is not CONNECTED: only the exact authenticated poll reply produces CONNECTED. No enrollment, selection, pair creation, SDK launch, legacy-consent widening, token worker or automatic attach in load/restore. Existing scope and monotonic roster revision are encrypted and committed before HTTP. Repeated explicit attach preserves revision for the same returned connection, but never renews source authority. Reconnect has an explicit scope action; disconnect invalidates locally under the **same** commit fence before network.
* Activity-owned foreground job works for empty, planned-only and finale-only lists without a baking service. STOP/controller detach fences late poll/attach/commit/ACK and cancels the job. The legacy timer-service voice loop is disabled only in the gated v2 build; flag-off code/UI/endpoints remain the legacy path. Logout synchronously fences both lanes; Room/history/family membership are not erased.
* `HttpsRosterV2Api`: unchanged canonical origin and frozen revision-1 DTOs for attach/poll/disconnect/ack. Exact statuses, field allowlists, JSON primitive types, canonical UUIDs, unique command/lease IDs, bounded strict JSON with duplicate/depth/trailing-token rejection; no redirects/cache/cookies/logger. Public construction remains disabled behind the flag.
* J05/J06 production Compose uses the F kit: numbered current list, feminine ordinal words, numbered phrases, installation switch and **separate explicit consent dialog**, no v2 bake picker. J06 highlights only its actual navigation context, not the first bake as implicit selection. Station timer opens the existing phone hint; no invented Station name, listening status or native Station timer promise. Native Roborazzi renders cover dawn/night page, sheet and actual consent dialog; they are synthetic UI fixtures, not phone/e2e acceptance.

## Durable executor, not policy eligibility

`executeRoster` rereads the persisted row under Application → controller locks. It reuses protected transition rules only after live connection/consent, canonical lease, key + number + revision + recipe + step + paused and bounded wall/monotonic expiry checks. Final advance is PHONE_REQUIRED; no voice photo, history insertion, completion or publication.

`ActiveBakeRepository.commitRoster` holds the existing `commitFence` and actual SQLite transaction, compares the **complete** previous row (not just revision), and commits state + revision + fingerprinted command receipt + consumed-lease marker together. The lease marker is a namespaced technical row in existing Room12 `voice_command_receipts` (operation LEASE, effects already satisfied), not another database or a schema migration. It uses the complete immutable command fingerprint including connection, lease, number and expiry. Same command/different bytes and same lease/another command cannot replay as success. A post-write expiry/authority check rolls all rows back before successful transaction completion. Native SQLITE_FULL may auto-rollback; the original disk exception is preserved with secondary rollback error suppressed, never mistaken for success.

Only an actual returned durable receipt can reach HTTP ACK. Local SQL/I/O/fence failure produces **no ACK**, never an optimistic APPLIED. A matching committed replay can supply its original receipt without changing state. A post-commit transport failure still means the server may report unknown; the client does not execute the same lease again or manufacture success. This slice does not claim a general durable ACK outbox or guaranteed delivery after process death. Receipt/dedup retention remains the existing bounded 24-hour policy; cancellation side effects use the existing idempotent recovery path.

## Isolated real producer integration

`app/src/test/resources/alice-producer/f1380c2.tar.gz` is the unchanged tracked `backend/alice` tree at `f1380c21665ebd51fff1da856cdd3164ad36ba9c`; archive SHA/source pin are checked before extraction. The launcher creates a private temporary DB/server on loopback, clears inherited provider environment, substitutes only the external Yandex fixture and bootstraps synthetic pairing through the real legacy HTTP challenge/preview/approve/confirm/token handlers — no inserted grant and no backend edits.

The real Kotlin HTTPS caller's pinned URL is asserted before its **test-only socket destination** is redirected to that loopback producer. Real JSON/auth/parser/status, producer Handler/SQLite/NLU/confirmation/dispatch, Android foreground coordinator/Room executor, and post-commit ACK all run. Integration verifies genuine applied ACK after persisted revision, no ACK on actual SQL failure, empty replacement, source-pair requirement, disconnect/re-pair/reattach and stale disconnect isolation. No synthetic network response is substituted for an Android commit. This is JVM source integration, not execution on a physical phone, native Android acceptance, live SDK/OAuth or Station.

Native Room/SQLite regressions additionally exercise 0/9/tenth capacity, plans→running→finale identity/revision, dedup/conflicts/lease reuse, local-vs-voice and two-command races, persisted drift vs UI cache, monotonic expiry at final SQL fence, logout, actual SQLITE_FULL, SQL-trigger rollback, file-DB reopen and recycled number.

## Exact backend handshake gap — preserved fail-closed

The pinned producer's `roster_store.attach` requires a **current confirmed paired legacy grant** whose bake key/selection generation still match the installation. Enrollment and Yandex identity bind, and old five-minute single-bake consent, do not satisfy that prerequisite. A brand-new or zero-bake installation without an existing confirmed pair gets `409 pair_required`; Android cannot honestly create this pair without the missing agreed installation-scoped pairing/bootstrap flow. V2 intentionally has no picker and does **not** silently select a bake just to manufacture that old authority. The UI reports unconfirmed connection and this prerequisite, not CONNECTED. After explicit disconnect the producer also revokes the source grant; a new pair then explicit scoped attach is required.

The fixture proves both the real rejection before pairing and success after its **test-only real-HTTP synthetic pairing**. That fixture is not an implementation of new-device bootstrap. Parent integration must resolve the installation-scoped pair/consent handshake for real new/empty devices with the backend owner; this Android writer has not changed producer/public URLs or permissions to conceal the gap.

## Reproducible checks / remaining acceptance

```
ANDROID_HOME=/opt/android-sdk timeout 1200 ./gradlew --offline :app:compileDebugKotlin :app:compileDebugUnitTestKotlin
ANDROID_HOME=/opt/android-sdk timeout 1200 ./gradlew --offline :app:testDebugUnitTest --tests 'com.polinalinen.madre.integration.v2.Roster*'
ANDROID_HOME=/opt/android-sdk timeout 1200 ./gradlew --offline :app:testDebugUnitTest
```

Core includes the existing domain/style/Room migration regressions and unchanged core20 plus the new J fixtures. Logs and test XML, exact final commit/CI IDs and immutable-source backup are supplied in the writer handoff. No assertion/timeout/ignore/baseline is weakened.

Still parent-owned: exact CI watch and sole full7.1 review, capability/server enable gate and the bootstrap decision, web/edge/AppLinks, fresh full backups/latest-data rollback, sanctioned deployment, native/physical/e2e/Station acceptance and release/delivery. This source slice is **not whole7.1 acceptance** and does not enable the flag.
