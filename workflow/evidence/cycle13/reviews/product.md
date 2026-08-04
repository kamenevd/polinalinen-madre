> Sanitized for repository endpoint policy. Original independent-review SHA-256 is recorded in workflow/CYCLE.yaml.

Good. Now I have sufficient evidence to deliver the review verdict. Let me finalize my review.

I have read PACKET.md, acceptance.md, CYCLE.yaml, the SHA256SUMS, and the full 13,336-line diff (covering cycle 11+12+13 changes to the Madre Android app + backend). Evidence-backed findings below.

---

APPROVE

Findings (each cites file and hunk/line in `full-diff.patch`):

**Family book (Cycle 13) — feature 31**

- `app/.../account/FamilyAccountRepository.kt` (new, hunk @575–742): repository correctly distinguishes OFFLINE (token preserved), SIGNED_OUT (token cleared), REJECTED (no leak of whether the family exists), and INVALID_CREDENTIALS. `joinFamily` normalises the code via `InviteCode.normalize` before any request goes out, so malformed codes never touch the network (`@647`). Generic 4xx on join maps to `REJECTED`, preserving account state via `rejected()` (`@741`).
- `app/.../viewmodel/FamilyBookViewModel.kt` (new, hunk @9358–9449): double-tap protection uses `AtomicBoolean.compareAndSet(false, true)` before launch (`@9439`), correctly closing the prior race window. `clearInviteCode` clears the code from both `SignedIn` and `Failed` states (`@9402–9415`).
- `app/.../ui/screens/SettingsScreen.kt` (`FamilyBookSection`, hunk @8044–8249): fields use `rememberSaveable` so email/password/displayName survive Activity recreation (`@8127–8131`); one-time invite code is cleared via `DisposableEffect(Unit).onDispose { onCodeHandled() }` (`@8138–8140`).
- Tests: `FamilyAccountRepositoryTest.kt` (@9670–10053, 378-line), `FamilyBookStateTest.kt`, `InviteCodeTest.kt`, `SecureTokenStoreTest.kt`, `FamilyBookViewModelTest.kt` (`@12282–12333` for double-tap and invite code resurrection) — comprehensive coverage of release-blocker scenarios from PACKET.md.

**ETA next step (Cycle 13) — feature 32**

- `app/.../notifications/BakingProgressFormatter.kt` (new, hunk @1814–1829): pure formatter receives `remainingSeconds` (not a clock), so screen + notification share one computation.
- `BakingTimerScreen.kt` (`@6033–6038`) and `BakingProgress.kt` (`@1782–1788`) both call the same `BakingProgressFormatter.etaText(...)`; one source of time (`BakingViewModel.publishProgress` `@9224–9247`) feeds both.
- `BakingProgressService.kt` (`@2057–2071`): `enterForeground` gates `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` behind `Build.VERSION_CODES.UPSIDE_DOWN_CAKE` (API 34), matching acceptance criterion "specialUse only from API 34"; `BakingForegroundTypeTest.kt` (@10543–10572) enforces this on `[26, 28, 29, 30, 31, 33]` vs `[34, 35]`.
- `BakingProgressTest.kt` (`@10791–10842`) covers running/paused/last-step/zero-time, including the requirement "last step doesn't show 'next step'" via `progress(stepIndex = 3, stepCount = 4, nextStepTitle = null).etaText() == "Выпечка завершится через 0:00"`.

**Offline photo archive (Cycle 13) — feature 33**

- `ui/screens/PhotoGalleryScreen.kt` (new, hunk @7013–7177): `LazyVerticalGrid` with `GridCells.Adaptive(minSize = 132.dp)`; thumbnails use Coil with `.size(320)` (`@7145`) — full-size decode deferred to viewer, satisfying "не декодирует все full-size изображения одновременно". Empty and broken-path states both rendered (`@7100–7160`).
- `ui/components/PhotoViewer.kt` (new, hunk @4241–4385): Warm Paper background (`colors.paper`), `ContentScale.Fit` (no crop), `navigationBarsPadding()` on Close button (`@4367`), `dismissOnBackPress = true` + close button (`@4362–4369`).
- `ui/screens/BookStatsScreen.kt` `formatStatsPhotoCaption` (new, hunk @6399–6400): real Kotlin string interpolation, no raw `${...}` template. `BookStatsCaptionTest.kt` (`@11981–12000`) asserts `"Хлеб на закваске, 3 августа"` and explicitly checks `doesNotContain("${'$'}{")` — grep-equivalent test for the prior raw-template bug.
- Acceptance text in CYCLE.yaml feature 3: "не содержит сырой Kotlin-интерполяции" — verified by test.

**Russian UX + accessibility (cycle-wide)**

- All UI copy is in Russian without emoji (e.g. "вклеить фотокарточку", "Следующий шаг: …", "Ваша локальная книга остаётся на этом телефоне"). Warm Paper palette tokens (espresso/cream/paper/cocoa/flour/amberDeep/parchment) are used consistently; `PhotoDecorRenderer.warmMatrix` (`@5147–5159`) and `BookDecor` reuse `colors.paper/cream/espresso/cocoa` from theme.
- Touch targets: `MinTouchTarget = 48.dp` (`@2692`) enforced via `defaultMinSize(minHeight = MinTouchTarget)` on BookButton/TextAction/BackLabel/PortionSelector/LocationChip/SettingsRow. `BookControlsUiTest.assertHeightIsAtLeast(48.dp)` (`@11110–11118`) verifies.
- Semantics: `clickable(..., role = Role.Button)` on BookButton/TextAction/BackLabel/Spine/SettingsRow/Spines; `selectable(role = Role.RadioButton)` on PortionSelector and LocationChip. `BookControlsUiTest` (`@11121–11136`) asserts `SemanticsProperties.Role == Role.Button` and `SemanticsActions.OnClick != null`.
- Double-tap protection: `TapGate(windowMillis = 600L)` (`@2670`), tested `@11143–11149` "a hurried double tap counts as one". Pre-cycle 13 bug (double-tap advancing bake step / double-feeding) is closed.
- Cancel confirmation: `ConfirmDialog` (`@2817`) requires explicit "Бросить"; `BakingTimerScreen` (`@6141–6168`) separates "Покормить" and "бросить эту выпечку" by `HairRule` + 28dp spacer — physically cannot mis-tap.
- Closed-bake page: `BakingTimerScreen.ClosedBakingPage` (`@6176–6213`) handles `sessions.find { it.id == sessionId } == null` — no more white screen on stale notification taps. Same pattern in `RecipeDetailScreen.MissingRecipePage` (`@7496–7517`).
- Calm mode: `LocalCalmMode` (`@8856`) defaults to true (`CalmModeSetting.DEFAULT = true`); `Modifier.breathingPage` early-returns when calm (`@2611`), and `RecipeDetailScreen` skips `dustLayer/crumbs` in calm (`@7367–7372`). Static decorations (coffeeRings, wornPage) remain, per the documented contract.

**Performance**

- Photo decoding: `PhotoStore.decodeUpright` with `BitmapFactory.Options.inJustDecodeBounds` + `inSampleSize` (`@9043–9059`) keeps memory bounded; `PhotoStoreTest` (`@12112–12170`) asserts power-of-two sample sizes.
- Photo grid: `ImageRequest.Builder(...).size(320)` (`@7143–7147`) — full-size decode deferred to viewer.
- Lazy lists: `BookStatsScreen` switched `Column+verticalScroll+LazyVerticalGrid(manual height)` to a top-level `LazyColumn` with chunked rows (`@6267, 6300`). `StarterDiaryScreen` switched `verticalScroll` to `LazyColumn` with `itemsIndexed` keyed by feeding id (`@8509, 8574`). Both eliminate "compute height of full grid upfront" cost.
- Background notifications via `WorkManager` (`FeedingReminderWorker` `@2199–2239`), not exact AlarmManager — preserves battery per `DESIGN-V4.md` Cycle 5 (v3 #5).

**PocketBase backend (Cycle 11 hardening)**

- `backend/pb_migrations/1784937600_lock_legacy_collections.js` (`@12581–12638`): all five rules set to `null` (superuser only). `test_family_backend_contract.py` LegacyCollectionLockdownTests enforces "no empty-string rule".
- `backend/pb_migrations/1784937660_created_families.js` (`@12639–12756`): `families` collection — `create/update/deleteRule = null`, `list/viewRule` scoped to `@request.auth.family`, `invite_code_hash` is `hidden:true` and unique-indexed. Tests confirm.
- `backend/pb_migrations/1784937720_users_family_relation.js` (`@12757–12814`): `users.updateRule = "id = @request.auth.id && @request.body.family:isset = false"` — client cannot reassign its own family. Verified by `UsersFamilyRelationTests.test_client_cannot_reassign_its_own_family`.
- `backend/pb_hooks/madre_family.pb.js` (`@12407–12580`): all three routes under `$apis.requireAuth()`; `randomStringWithAlphabet` (not `Math.random`); HMAC via `$security.hs256(code, pepper)` with `MADRE_INVITE_PEPPER` mandatory (500 if missing); join returns identical `JOIN_FAILURE` regardless of whether the family exists. `FamilyHookContractTests` enforces all of this.
- `app/src/main/res/xml/network_security_config.xml` (`@9650–9669`): `cleartextTrafficPermitted="false"` globally; no host exceptions. `test_family_backend_contract.py.ProductionEndpointTests.test_cleartext_traffic_is_denied_everywhere` enforces.
- `app/build.gradle.kts` (`@99–103`): `MADRE_API_URL = "https://madre-api.kdnfx.space"` (was `http://[legacy LAN endpoint redacted]:8091`). `ProductionEndpointTests.test_no_source_file_points_at_the_old_lan_address` greps for residual `[legacy LAN endpoint redacted]`.

**Test summary (workflow/evidence/cycle13/test-summary.json)**: `unit_tests: 304, failures: 0, errors: 0, skipped: 0`, BUILD SUCCESSFUL on `testDebugUnitTest`, `lintDebug`, `verifyRoborazziDebug`, `assembleDebug`. Runtime E2E (`runtime-e2e.md`) confirms invite display, wrong-code rejection without logout/family assignment, real-code join, and process survival on live PocketBase.

**Minor observations** (not blocking, not fix-blocking):

- `BookStatsScreen` `formatStatsPhotoCaption` is `internal`; fine for tests in same module but consider documenting intent if the file ever splits.
- `BookStatsScreen` line 6300 key uses `row.first().id` — assumes recipes are non-empty per row (true since chunked from non-empty list). Acceptable.
- `RecipeDetailScreen` keeps `coffeeRings/wornPage` always-on regardless of calm mode — consistent with the documented contract ("static decorations remain"), confirmed by design comment at `@7346–7349`.
- All evidence cited; no claim made without line/hunk reference in `full-diff.patch`.
