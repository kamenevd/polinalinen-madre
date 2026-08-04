APPROVE

## Evidence summary

**Prior blockers (review2-regression.md) — all 6 VERIFIED CLOSED:**

| # | Blocker | File/hunk evidence | Status |
|---|---|---|---|
| 1 | FGS type API 29–33 | `enterForeground()` gates `specialUse` behind `UPSIDE_DOWN_CAKE`; `BakingForegroundTypeTest` enforces null below 34 | ✅ |
| 2 | Form data loss | Five fields migrated `remember`→`rememberSaveable` in `SettingsScreen.kt`; `FamilyBookSectionUiTest` validates restoration | ✅ |
| 3 | `ForegroundServiceStartNotAllowedException` swallowing | `runCatching` removed; `enterForeground()` returns false only on that exception, rethrows others | ✅ |
| 4 | Double-tap race | `AtomicBoolean.compareAndSet(false,true)` before `launch` in `FamilyBookViewModel.runNetwork()`; `FamilyBookViewModelTest` covers create + rotate | ✅ |
| 5 | Invite-code resurrection | Three-layer defence: `FamilyAccountRepository.clearInviteCode()`, `FamilyBookViewModel.clearInviteCode()` (both SignedIn and Failed), `DisposableEffect(onDispose)` | ✅ |
| 6 | Stale CYCLE | `"number": 13`, `"version": "5.3.0-cycle13"`, `"branch": "cycle/13"`, `"stage": "reviewing"` | ✅ |

**TOCTOU (review2-security.md MEDIUM-1) — CLOSED:**

`security-remediation.patch` re-reads `member.getString("family")` **inside** both `runInTransaction` callbacks (create + join), before `member.set("family", ...)`. Contract tests (`InTransactionMembershipRecheckTests`) enforce the re-check precedes member.set/save and throws the same generic errors (BadRequestError / JOIN_FAILURE). Live E2E confirmed: concurrent create → 200+400 with exactly one family/membership; concurrent join → 200+400 with exactly one membership; QA cleanup verified 0 users/0 families.

**304 tests pass, 0 failures. lintDebug, verifyRoborazziDebug, assembleDebug — BUILD SUCCESSFUL. Runtime E2E pass on live PocketBase.**

---

## LOW findings (APPROVED with observations)

**LOW-1 — `DisposableEffect(onDispose)` clears invite code on any composition leave**

File: `app/src/main/java/.../ui/screens/SettingsScreen.kt` — `DisposableEffect(Unit) { onDispose { onCodeHandled() } }`. Back gesture, incoming call, or share-sheet launch all clear the displayed code. The user cannot both Copy *and* Send without re-rotating. Consistent with design decision "Invite codes are one-time UI secrets" (CYCLE.yaml decisions). **Documented UX choice, not a defect.**

**LOW-2 — `intervals` display list and `intervalHoursOptions` value list are decoupled**

File: `app/src/main/java/.../ui/screens/SettingsScreen.kt` — `intervalHoursOptions` drives the actual value, `intervals` drives display text. Coupled implicitly by array index rather than a data class. Adding a 6th option to one list but not the other would cause `ArrayIndexOutOfBounds` at runtime. Both are local vals 5 lines apart in the same composable — desync is practically impossible. **Defence-in-depth nit, not a functional risk.**
