# Final QA readiness — 7.1.24(77), Cycle 29

Scope is frozen at reviewed UI `477cf0b1d5ff4d9354b0231e7f57d2598e4cf287`.
No new screens or backend payload. Larger SCREENS work is deferred to 7.2.

## Source integration

- PR67: reviewed waiter, locked Gradle/pre-push and sole-integrator tooling copied
  into source; installed runtime generation/config remains untouched.
- PR68: supported `cache-cleanup: on-success`, trusted feat/default-branch writes,
  fork deny, independent short producer with identical cheap pre-setup guards.
  Existing bake-back frame and its exact-head selector remain intact.
- PR69: source defaults are Xmx1536m/workers2/cache/parallel/daemon; workflow
  `--no-daemon` removed, including release.yml. Ordinary signed QA is produced
  **once** by Android quality. Optional detailed acceptance downloads the same-run
  APK/mapping/merged manifest/proofs; test-only persistence ABI is a separate job.
- PR70: inert source/docs/fixture tests only. No cleanup, cron or installation.
- Deliverable `qa`: YANDEX_ID, FAMILY_LINK_UI, FAMILY_ROSTER_V2 and ALICE are ON.
  Actual DEX verifier enforces all four. Legacy debug keeps family transport OFF;
  ordinary release keeps Yandex/family flags OFF and its original signing policy.

## One automatic final-candidate CI run

The parent integrator's **final commit message** must contain this exact standalone
line (cherry-pick preserves it unless the message is edited):

```
Madre-Final-QA: 7.1.24(77)
```

`select_final_qa.py` accepts only that exact marker at HEAD, the trusted same-repo
PR on `feat/razvorot-7-0-1`, exact PR-head checkout and source version 7.1.24(77).
An unmarked next microstep, fork, wip branch or push cannot opt itself in. Marking
a different source version fails closed. There is no second feature-push trigger
and **no duplicate manual dispatch is needed**.

The existing protected check name **Instrumented smoke** now depends only on
Workflow integrity + Android quality. It downloads `madre-short-qa` from that
same run, verifies exact HEAD/hash/version, R8/nondebug/profileability/ART,
permanent QA certificate and all four actual DEX gates before booting API35 AVD.
Only the external self-instrumenting macrobenchmark driver is built there; the
ordinary QA target is never rebuilt, repackaged or replaced by qaRegression.

The existing precise method
`QaOwnerAcceptance#actualR8SettingsProbeAndLocalFpsAreVisible` opens real QA
MainActivity/settings, tests the local haptic probe/FPS/night theme, and returns
to the actual table of contents. The seeded plan method is NOT selected. Runtime
requires a named GitHub CI emulator before its first adb command, checks installed
APK hashes before/after, and rejects empty/skipped/failed instrumentation even
when `am instrument` exits zero. Raw protocol/logcat/full PNG/XML and controls go
to `final-qa-smoke` with `always()`. No provider flow, QR, physical device or live
account acceptance is asserted. The legacy coordinator still exists separately
as **Optional extended instrumented acceptance**, manual/nightly only.

`critical_only` still skips all native work; family-only render stays isolated.
Skipped smoke is not PASS and release context still demands an actual successful
**Instrumented smoke** at the exact SHA.

## Disabled release preparation (NOT applied)

`release-enable-after-live.patch` is intentionally unapplied. Tests run real
`git apply --check` and apply it only to a temporary fixture, verifying unchanged
QA code, application identities and signing. It switches only release YANDEX_ID,
FAMILY_LINK_UI and FAMILY_ROSTER_V2 to true; ALICE already remains true.

After separately authorized live Yandex/family/Alice/Station checks, final review,
and a new explicit owner release window, the controller may review a fresh patch
against the then-current source. **Do not apply, merge, tag, publish or upload now.**
No new key, signing fallback or policy change is prepared.

## Honest acceptance ledger

Cycle28 CYCLE is archived byte-for-byte; Cycle29 does not inherit its old PASS.
New lifecycle gates remain pending; live/provider/Station/phone acceptance is
NOT_RUN. Local source fixtures/compile do not satisfy parent review/full short
gate/exact-head CI or owner live acceptance. No APK was built for delivery here.

Leaf evidence is kept outside Git at
`/root/.hermes/work/madre-final-qa-20261026/`; the parent records the final
integrator SHA/run/artifact verification separately. No push is authorized in
this leaf.
