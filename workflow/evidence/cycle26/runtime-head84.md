# Cycle 26 exact-HEAD runtime evidence

- HEAD: `84c5c8a353deb87d9a23b292344ef69c40e5d6cc`
- Date: `2026-08-19`
- AVD viewport: `1080×1920`
- Package: `com.polinalinen.madre`
- Version: `6.4.5 (33)` (intentionally pre-release)

## Evidence artifacts

- Debug APK: `/tmp/madre-head84-debug.apk`
- Debug APK SHA-256: `320d81ea3c48da976a2f63c039c9333080ffa56fd1169467f739bf911c566631`
- AndroidTest APK SHA-256: `3fa0fc1cdcba9d47b4f3422cbe0fedb1fc1eba99cbc8d3df1596742c47bebd88`
- Machine-readable result: `/root/madre-qa-lab/runs/20260819-head84-final/result.json`
- Screenshots:
  - `/root/madre-qa-lab/runs/20260819-head84-final/01-home-clean.png`
  - `/root/madre-qa-lab/runs/20260819-head84-final/02-home-after-first.png`
  - `/root/madre-qa-lab/runs/20260819-head84-final/03-diary-after-first.png`
  - `/root/madre-qa-lab/runs/20260819-head84-final/06-diary-verified.png`

## Verified exact-HEAD checks

- `python3 -m pytest scripts/tests/test_cycle26_visible_copy.py -q`: failed with `/usr/bin/python3: No module named pytest`, exit `1` on exact HEAD.
- `python3 scripts/cycle.py validate`: `CYCLE VALID` on exact HEAD.
- Final unit aggregate: `98 suites / 728 tests / 0 failures / 0 errors / 0 skipped`.
- `MigrationTest`: `OK (8 tests)` with migration chain `8 -> 9 -> 10` and `1 -> 10`.

## Persisted scenario and DB results

- First feeding defaults `50/100/50` persisted as computed hydration `50%`.
- Second feeding `50/100/100` produced computed hydration `88%`.
- Simultaneous double-submit on Save produced exactly one extra row (single logical feed).
- `Room` pulled with DB/WAL/SHM; read-only SQLite result showed exactly two total feeding rows.
- Row order by insertion:
  - id `1`: `50 / 100 / 50 / 50%`
  - id `2`: `50 / 100 / 100 / 88%`
- `sourdough_configs.lastFeedingMillis` equals row id `2` timestamp.
- Exact next-feeding label: `20 августа, 07:22`.
