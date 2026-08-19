# Cycle 26 final gates (post-commit MoA remediation)

- Host: `LXC108`
- Model: `gpt-5.3-codex-spark`
- Workdir: `/home/claude/projects/madre-cycle26`
- Date: `2026-08-19`

## Scope validated
- `./gradlew testDebugUnitTest lintDebug verifyRoborazziDebug assembleDebug assembleDebugAndroidTest --no-daemon` executed and finished **BUILD SUCCESSFUL**.
- `python3 scripts/tests/test_cycle26_visible_copy.py -q` executed on exact HEAD: `Ran 2 tests in 0.010s`, `OK`, exit `0`.
- `python3 -m unittest scripts/tests/test_workflow_contract.py scripts/tests/test_documentation_contract.py scripts/tests/test_workflow_version_contract.py` executed: `Ran 24 tests in 0.005s (OK)`.
- `python3 scripts/cycle.py validate` executed: `CYCLE VALID`, exit `0` on exact HEAD; gate statuses (`review`, `visual`, `runtime`, `release`) remain pending and are not yet advanced.
- `git diff --check` clean.

## Build/test outcome
- Aggregated JUnit XML from `app/build/test-results/testDebugUnitTest/*.xml`:
  - suites: **98**
  - tests: **728**
  - failures: **0**
  - errors: **0**
- Replaced prior baseline `98 suites / 722 tests / 0 failures` with this final rerun count.

## Runtime + migration
- AVD migration suite: `8/8` passed in `app` instrumentation output (`MigrationTest` class reports 8 tests, all pass).
- Migration chain is additive and explicit: `8 -> 9 -> 10`.
- Baseline remains `v8`; `v9` is non-populated intermediate/baseline step.

## Persistence scenario evidence
- Real runtime behavior confirmed with existing evidence cache:
  - First feed row: 50%
  - Second feed row: 88%
  - Simultaneous double-submit produced exactly one additional row, resulting in second row at top
    (`50 / 100 / 100 / 88% / кухня`) above prior row (`50 / 100 / 50 / 50% / кухня`), no duplicates.
  - `sourdough_configs.lastFeedingMillis` updated to latest row timestamp.
- Evidence source: `/home/claude/.cache/madre-book-review/cycle26-v650-review-packet.md`

## Visual evidence (goldens)
- Re-recorded with viewport fixed 360×640 portrait via roborazzi test harness.
- `app/src/test/snapshots/com.polinalinen.madre.ui.visual.FeedingFormGoldenTest.feeding form geometry in portrait.png`
  - SHA-256: `8ad02d87127766d996fe6b15388312cac83d89d4e4b620349bf0b1704635dc85`
- `app/src/test/snapshots/com.polinalinen.madre.ui.visual.FeedingFormGoldenTest.feeding form saving error state.png`
  - SHA-256: `9963ba0236feaa07a3ceb986cb33026dcc3f12bc8b961f691410cfbae3d87050`
  - Distinct from normal-state PNG.
- `app/src/test/snapshots/com.polinalinen.madre.ui.visual.StarterDiaryGoldenTest.starter formulary keeps full header and widest row.png`
  - SHA-256: `743294a8f9c6d005038e586eae99e19fd4134e5c8b697673d35511f3f366ce8f`

## RuStore screenshots (1080×1920)
Evidence source: `/home/claude/.cache/madre-book-review/cycle26-v650-visual-seven.md`

1. `01-starter-diary.png` — SHA-256 `8a84e1273e0744b6e1a7d45e63470666671ac66bc8de1a4d85e4b4c7cd14f0e0`
2. `02-home-hydration.png` — SHA-256 `808744dbc3115661f9ea8a8008fe88a22664b11f46c6ab7ed5d97ebebb2b297b`
3. `03-feeding-masses.png` — SHA-256 `4f4334f955900382bf74368526f2b985364f799d06389cfe4b04bb6f57ee6c53`
4. `04-exact-weight-input.png` — SHA-256 `0a7bbdc44714ad6d21af4e23e037fe0863c4d960005948eec0c28400b423ac5f`
5. `05-generated-comment.png` — SHA-256 `87560664c23e4dee923c0a9d0c59f90f39d00777403358bd07dcac036c344fe9`
6. `06-feeding-reminder.png` — SHA-256 `545f2b15d76805aa02b6f66955970dd9286ff0a91b8e6d5d85cfb55fe5031aa7`
7. `07-home-due-action.png` — SHA-256 `b1cab7f5fb99fce589e20aa56df69d6114d11443a3a177a5eb098db90a0653f9`

Screenshot state capture: 7/7 captured.

## Gate status and evidence
- `review`, `visual`, `runtime`, and `release` gates remain `pending`; runtime evidence exists before gate status advancement.
- `workflow/CYCLE.yaml` gate transitions were not changed in this pass.
- `python3 scripts/cycle.py validate` confirms pending gates are legal and expected at this evidence stage.
