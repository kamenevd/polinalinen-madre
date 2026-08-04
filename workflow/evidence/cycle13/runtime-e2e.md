# Cycle 13 runtime E2E

Android 11 KVM emulator, real installed 5.3.0-cycle13 debug APK and live PocketBase through https://madre-api.kdnfx.space.

Passed: owner registration; family creation; 16-character invite displayed; owner logout/login; wrong valid-format invite rejected without family assignment or logout; second and third users joined by the real code and received the same family id; process remained alive; no fatal crash. QA users and family were then removed and emulator stopped. Backend and public API health returned HTTP 200 JSON.
