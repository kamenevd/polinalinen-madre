# FAMILY L2: invitation sheet and roster presentation

## Why
Creation should immediately lead to inviting family, without a separate naming dialog. Reuse the existing FAMILY-LINK3 repository, authorization and management operations.

## Scope
- One-tap creation using a verified surname only when independently available, otherwise «Наша семья». The current public session has no surname field; do not infer one from the display name.
- After authoritative create/readback, load the invitation and open the created sheet. Invitation retry must never repeat create.
- Share through ACTION_SEND/createChooser, explicit clipboard write with feedback, and generation-only ZXing QR (M, quiet zone and white padding), all from the same validated typed HTTPS invitation. Real expiry and optional seats come from the existing contract.
- L empty/owner/member surfaces in the current Paper kit. Keep member edits, transfer, removal/8-second undo, revoke and leave. Hide approval switch but retain existing pending decisions and removed-member exceptions.
- Preserve SDK return, explicit joining, dead/switch-family handling, email migration and personal-data boundaries.

## Acceptance and activation
Compile debug + unit-test sources, execute the unchanged short-gate domain/auth/migration/privacy suite, assemble debug and ordinary R8 QA, verify permanent certificate/account/R8 binary. Optional visual acceptance is separate and is not a prerequisite. Prepare unissued 7.1.1/54 through release_cycle.py; never replace issued 7.1.0/53.

FAMILY_ROSTER_V2 remains false. No backend/DTO/auth-route changes, live family mutations, deployments, phone installation, merge/tag/release or delivery. Parent owns deployment/activation, review and delivery. Fixture verification is not production or phone/e2e readiness.
