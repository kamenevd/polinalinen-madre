# Cycle 26 — 6.5.0 release artifact evidence

Date: `2026-08-19`
LXC: `LXC108`
Model: `gpt-5.3-codex-spark`
Repository: `/home/claude/projects/madre-cycle26`
Runtime state: `/var/lib/madre-workflow/runs/cycle26-final-gate-order-20260819/state.json`

## Build/test outcome and run-level transparency
- Final signed command (from external build evidence): `clean testDebugUnitTest lintDebug verifyRoborazziDebug assembleRelease bundleRelease --no-daemon`
- Build path: `/home/claude/.cache/madre-book-review/cycle26-v650-signed-build-rerun.log`
- Final task results from that log:
  - `BUILD SUCCESSFUL in 5m 27s`
  - `testDebugUnitTest` completed with `98` suites / `728` tests, `0` failures, `0` errors
  - `lintDebug`, `verifyRoborazziDebug`, `assembleRelease`, `bundleRelease` all completed
- Earlier attempts (from the same external evidence):
  - initial environment setup had no `JAVA_HOME`
  - first full run had transient `LightPageNodeTest` `AppNotIdleException`
  - rerun target with `--rerun-tasks` was executed, and subsequent full signed run passed

## APK package metadata
- APK path: `/opt/madre-releases/madre-v6.5.0.apk`
- Package: `com.polinalinen.madre`
- versionName: `6.5.0`
- versionCode: `34`
- Size: `3104045` bytes
- SHA-256: `d8e8100a4956b0be4f879c7f192a3b7e10aaeaa354544b1d91db9d1045fe785a`
- Signature verification (`/home/claude/android-toolchain/sdk/build-tools/34.0.0/apksigner verify --verbose --print-certs`):
  - `Verifies`
  - Verified using v2 scheme: `true`
  - Signer SHA-256 digest: `230aca3b5382e31d5f4350a841b64646ba6ddc09fb08c049ac00f50f719bf8f1`

## AAB artifact
- AAB path: `/opt/madre-releases/madre-v6.5.0.aab`
- Size: `6179854` bytes
- SHA-256: `b49dee30975f8c5d7a85360835c8cb70fdc2b4f7f42515edcd1590d5c6e021ce`

## Release manifest and source archive
- Release manifest path: `/home/claude/.cache/madre-releases/v6.5.0/madre-v6.5.0-manifest.json`
  - SHA-256: `5ba15b74098f838b78fad8c1640607b4ea33142a9e0c2e3bbfa0e71573590480`
- Source archive path: `/home/claude/.cache/madre-releases/v6.5.0/madre-v6.5.0-src.tar.gz`
  - File name: `madre-v6.5.0-src.tar.gz`
  - Size: `2682632` bytes
  - SHA-256: `5f17c73f984558a04b06f9d5464f0a1fd9041a48d464c9af3c8848d58bf87a76`
- Manifest record check (`release_cycle.py package` followed by `release_cycle.py verify`): `RELEASE ARTIFACTS VERIFIED`

## Runtime baseline-to-6.5.0 migration evidence
- On disposable AVD:
  - Installed public signed `6.4.5 (33)`
  - Installed `6.5.0 (34)` using `adb install -r` on same package without uninstalling
  - `firstInstallTime` remained `2026-08-19 08:14:43`
  - `lastUpdateTime` changed to `2026-08-19 08:14:46`
  - package/dataDir remained `com.polinalinen.madre` / `/data/user/0/com.polinalinen.madre`

## Public URL and HTTP/content evidence
- URL: `https://kdnfx.space/downloads/madre-v6.5.0.apk`
- HTTP status: `200`
- Content-Type: `application/vnd.android.package-archive`
- Content-Length: `3104045`
- Content-Disposition: `attachment`
- Full download SHA-256: `d8e8100a4956b0be4f879c7f192a3b7e10aaeaa354544b1d91db9d1045fe785a`
- Local/public bytes match (`same SHA-256`)

## Publication status
- `RuStore publication`, `GitHub tag/release`, `merge to main`, and `released` stage advancement were **not performed** for this cycle.
