# Yandex A: isolated identity binding and consent-safe UID reauthorization

## Why
Android's optional Yandex sign-in needs actual installation-authenticated verification/unbind endpoints and an OAuth UID match, without saving profile/token material or changing the existing private skill/legacy reader. Work is isolated from Android in a fresh branch pinned to the installed PR58 backend.

## Scope
Add bounded own-client Yandex verification and uid-only persistence; exact Android bind/unbind contract; real Dialogs grant/code/access/refresh revocation; feature-flagged browser-state/PKCE callback using existing broker authorization code machinery. Auto-match requires exactly one current installation with existing explicit voice authority; first-time/unknown/ambiguous/revoked identities use the existing pair-approve path. Prepare tested online-backup/stage/fenced pointer rollback utilities; activation/config/edge belong only to parent after its review.

## Acceptance
* Existing 37 backend reader/voice/security tests remain unmodified and pass.
* Real HTTP/SQLite tests mock only external Yandex, prove exact client check, wire fields, absent profile/token persistence (complete SQL/DB/WAL/log inspection), race-safe bind/unbind and actual grant/access/refresh revocation without enrolling a substitute identity.
* Validate exact existing Dialogs client/scope/redirect, preserved outer state, expiring browser-bound single-use inner state, S256 PKCE/confidential secret, unknown UID/ambiguity/no-consent fallback, replay/CSRF/expiry denial and real broker code redemption/revocation.
* Temp-root deployment tests exercise WAL-consistent archive+SQLite backup, checksums/readback, source/config/current-pointer fences, active-service refusal and rollback retaining post-deploy revocations.
* No production activation/restart/edge/console, real provider grants/QR/consents, merge/tag/release/RuStore, or Android changes.

## Flag
`YANDEX_MATCH` defaults off. Bind requires own Yandex client and stable protected uid salt. Parent alone supplies protected secrets and enables the flag after review and routing/config prerequisites. See `backend/alice/YANDEX-A-HANDOFF.md`.
