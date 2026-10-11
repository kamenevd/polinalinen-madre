# Plan r2 review — Grok 4.7

Role: independent reviewer. Not the author. No production edit, no plan edit, no Dialogs POST, no /opt/madre-alice change, no phone/Station/AVD, no secrets, no PAYG.
Provider/model for this review: xai-oauth / grok-4.7. No fallback.
Source pin: fe10334e52b767c0d2499ce430bc695d6fdc918a, source/ worktree clean.
Reviewed: PLAN.md r2 (49 lines) against reviews/plan-r1-grok.md closed scope and the pinned tree. Official Dialogs pages re-read: how-it-works, make-skill, add-skill-to-console.

VERDICT: APPROVE

This approves bounded implementation of PLAN.md r2 only. It does not waive A8 or A9. It does not clear release, catalog, RuStore, live /opt/madre-alice, or hardware. It does not demand a phone, Station, or AVD action. OEM locked-screen lifetime stays NOT TESTED.

Closed re-review. Every r1 P0 amendment is in r2. No r1 P1 acceptance line is contradicted. r2 does not add a safety contradiction. No new finding class.

## P0 — closed

1. Two turns, two objects. PLAN.md:27-30.
Turn 1 stores one server pending intent (operation, recipe, step, bake UUID, revision, generation, session), TTL 30s, speaks the exact target, and ends the webhook. It does not mint or enqueue a phone command. The bake UUID is the bakeKey defined at PLAN.md:21. A later utterance revalidates that same intent against the current snapshot and revision, and only then mints a UUID with a 4s delivery expiry and a <=2s ack wait inside the 4.5s budget. No reconnect replay. Before enqueue/dispatch: не отправлено. After enqueue/dispatch without ack: результат неизвестен, never success or unchanged. A later read may return the stored receipt and must not resend. стоп / выход / хватит / bare отмена clear the intent and exit the skill, and must not call cancelSession. Bare да confirms only a pending non-cancel intent and is never advance by itself. подтверждаю отмену confirms only a pending CANCEL and must not confirm pause/advance/back. Pending intent is bound to the same grant and session and cleared once consumed.

2. Installation credential, Dialogs bearer, and PocketBase token stay separate. PLAN.md:11-16, 34.
Long-poll, snapshot upload, phone receipt/ack, disconnect, and pairing approve accept only the installation credential. The bearer may read or enqueue only the bound bake and grant. It cannot poll, upload, ack, or mint success. The family/account/PB token is not sent to the bridge and is not accepted on a voice endpoint; phone routes are installation-only. No email merge. The 10-character code is shown in the browser and typed only into the phone; the skill rejects it as an utterance. Binding still needs installation authentication, the originating browser cookie plus CSRF, and a second explicit phone approve. One Yandex account reuses the same token on every surface. Grants are not replaced by user_id. A second installation does not overwrite another. No auto-select.

3. Redirect contract matches the current official text, including the r1 wording that does not. PLAN.md:13.
Fetched how-it-works: Dialogs sends state, redirect_uri, response_type=code, client_id, and scope (several scopes joined with &). It does not send code. Step 7 and create-server say the authorization server generates code. The redirect to https://social.yandex.net/broker/redirect must carry code plus state, client_id, and scope, and those received values must be the same values Dialogs sent. r2 allowlists that exact URI, uses response_type=code, configures scope bake:read&bake:control, and preserves received state/client_id/scope exactly. "Only normal URL percent encoding" plus "do not normalize/reorder/split-and-rejoin" is the way an &-joined value survives a query string. It is not the r1 bug (space-separated rewrite). Generating the one-use code, rather than echoing a code Dialogs never sent, is the official contract, not a missed echo. Token/refresh body under 5000 characters, each token under 2048, expires_in an integer, refresh within 5s. Raw access and refresh tokens stay out of URLs and logs. The required code redirect is not permission to log the URI.

4. UNKNOWN scale covers domain, renderers, and history. PLAN.md:21-22, 42.
Pinned tree still coerces null into 1.0 / ×1 today, so the amendment is still necessary and r2 actually states it:
- restoreFromActive defaults scaleFactor 1.0 and the snapshot does not store scale (BakingModel.kt:203-215). The restore call passes no scale (BakingViewModel.kt:214-222).
- Timer header and step text coerce (BakingTimerScreen.kt:112, :165). Home ActiveBakingTicket (HomeScreen.kt:240). Completion screen (BakingCompleteScreen.kt:150). History writers (BakingViewModel.kt:290, :466, and ensureCompletion :458-470 into startRecordInsert :523-532). BakeRecordEntity.portions is non-null Int (BakeRecord.kt:19). Portions are integers 1..5 (RecipeScale.kt:20-21).
r2: nullable column, no ×1 backfill, no destructive migration, explicit registered Room 10→11 migration (the only registration list is MadreDatabase.MIGRATIONS, MadreDatabase.kt:319, 337-339). Legacy null stays UNKNOWN after restore/recreation. No renderer and no history writer may apply the 1.0 default or coerceAtLeast(1) to that null. Timer, home ticket, completion, and voice withhold weights/×1 until a supported cyclic portion is confirmed. Phone finish does not insert bake_records until then; historical portions schema is not rewritten to invent 1. RecipeScaler only for confirmed values. Recipe text stays verbatim. Tests: null quantities / no ×1 history, and unknown phone finish blocks insertion.

## P1 — present, not contradicted

- Last-step voice ADVANCE returns PHONE_REQUIRED and must not call advance(), completedAt, startRecordInsert, finish, share, photo, or record (PLAN.md:29, 24). advance() on the last step still sets completedAt (BakingModel.kt:305-306) and advanceStep then writes history (BakingViewModel.kt:279-293). Phone Готово stays the local path (BakingTimerScreen.kt:231-234). Test: last-step voice no bake record.
- One serial gate covers UI and voice start/advance/back/pause/resume/cancel/phone-finish. Consent and installation checks are voice-entry only. Offline local actions need no grant (PLAN.md:23, 42).
- Detach, disconnect, and FGS stop use that gate and the live registration generation is checked inside the state transaction. A loser does not commit and does not ack (PLAN.md:24). Poll cancels on empty list, service stop, VM clear, and process death, not only a happy disconnect (PLAN.md:20). No second service. specialUse stays; no covert dataSync. Poll only while the countdown process is already running. Test: detach-during-apply does not commit. Source still matches the boundary being kept: activity-scoped VM (MadreNavHost.kt:59-71), onCleared clears snapshots and stops the service (BakingViewModel.kt:728-735), empty snapshot list stops the service (BakingProgressService.kt:79-93), START_NOT_STICKY (BakingProgressService.kt:114-116), specialUse and allowBackup=false (AndroidManifest.xml:28, :52).
- Receipt primary key is the server UUID. Same UUID returns the stored outcome and does not apply twice; a different payload on that UUID rejects. Fingerprint does not collapse two new confirmed UUIDs. Crash after commit restores the row and the same receipt. remember/forget must not remain a parallel writer (PLAN.md:23-24). They are separate launches today (BakingViewModel.kt:325-328, 369-372).
- null recipeRevision is a mismatch. Restore does not hash current JSON or overwrite a stored revision. A coerced step (BakingModel.kt:221) is not a successful voice apply. Voice control and quantities refuse (PLAN.md:21).
- Static verifier and OAuth verifier stay distinct. Do not drop the static secret on a guess. Unlinked recipe/linking responses only if a read-only trace shows Dialogs omits that header. Private snapshot and enqueue need a verified grant. Phone routes need the installation credential. skill_id and user_id are not authentication (PLAN.md:34).
- start_account_linking only when meta.interfaces.account_linking is present, and that response must not also include response. account_linking_complete_event has no command: verify token, optional status read, never enqueue or replay a saved mutation (PLAN.md:17).
- Snapshot is the selected bake's confirmed recipe id/text, quantities, current/next step, paused/running, revision, freshness. Not starterName, location, photos, shelf, diary, other bakes, or account identity (PLAN.md:15, 35). Shared Station copy: anyone near it can control only the selected bake. Status from the live lease, not a toggle. No unconditional управляет. Native notification UI unchanged (PLAN.md:20, 38).
- Запусти навык Мадре only after an A9 persisted published rename. Before that, no false renamed-capability copy (PLAN.md:38).
- Added tests are in PLAN.md:42-43: no ×1 history and blocked unknown-scale insert; last-step voice no bake record; pending-intent expiry does not enqueue; spoken code does not bind; bearer cannot poll or ack; стоп never cancel; exact cancel does the coffee-ring write once and not shelf/photo/bake record (cancelSession today increments cancelledCount and the coffee-ring pref, then exitSession, and does not write bake_records — BakingViewModel.kt:592-601); detach-during-apply does not commit. Standing r1 protocol, migration, FGS, golden, and build tests are not withdrawn.

## Not a new contradiction

Bearer "enqueue" (PLAN.md:12) is the turn-2 mint path. Turn 1 still must not enqueue (PLAN.md:28). Both sentences bind. "may not insert" is a prohibition; the test line says the insert is blocked. Insane clock skew fails closed and cannot extend TTL (PLAN.md:30). That was a non-expanding P2 note; it is not a new requirement.

## Holds — not waived by this APPROVE

- A8. Official purple mark and permitted-use evidence. PLAN.md:8, 39. No badge, no third-party logo, no invented mark. Held: in-app brand asset and any release that would ship one.
- A9. Exact card rename Мадре, matching verified description, brand/Webmaster rights URL, persisted private card, skill status. PLAN.md:8, 38-39. Rejection stays a bounded blocker. No substitute name. Browser console test is not Station recognition. This review did not touch the console.
- OEM locked-screen and process death. Not tested. Not a demand to test. Fail-closed copy remains mandatory. Lost controller or lease does not queue for reopen (PLAN.md:20, 43).
- Live /opt/madre-alice, RuStore submit, signed release, public mirror. Out of this gate. 6.7.0 / code 37 stays tentative. APK workflow stays unless a fresh store read requires AAB (PLAN.md:48-49).

## Docs read

- https://yandex.ru/dev/dialogs/alice/doc/ru/auth/how-it-works — inbound parameters, generated code, redirect echo of state/client_id/scope, 5000/2048/expires_in, bearer after link.
- https://yandex.ru/dev/dialogs/alice/doc/ru/auth/create-server — server generates code; redirect passes that code and the other parameters.
- https://yandex.ru/dev/dialogs/alice/doc/ru/auth/make-skill — account_linking interface, start_account_linking must not include response, complete_event has no command, refresh within 5 seconds, same token on every surface of that Yandex account.
- https://yandex.ru/dev/dialogs/alice/doc/ru/auth/add-skill-to-console — scopes joined with &.
