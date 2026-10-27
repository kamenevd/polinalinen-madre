# FAMILY 7.1 Android UI — scoped implementation

Authority: approved F «Разворот» / FAMILY-LINK §§0–7 + 24 October overrides;
wire contract is frozen `docs/contracts/madre-7.1-v2.md` at de88baa. This change implements
Android family actions only. It does not enable or deploy the server.

- `FAMILY_ROSTER_V2=false` remains unchanged; existing email/password/code implementation and
  personal Room12/history/consents remain intact behind the legacy branch. New UI is Yandex-only.
- K01 empty shelf + existing live-email migration; K02 owner/member roster; K03 explicit share/copy,
  owner rotation/revoke; K05/K06 public preview → official SDK → same invite → explicit Join;
  atomic switch confirmation, pending single foreground status refresh; K07 self/owner labels,
  ownership confirmation; K08 removal/default rotate, RAM-only Undo receipt with8s UI deadline;
  K09 explicit owner transfer/leave and solo soft-delete; K10 dead/full/removed/network/conflict.
- Production `HttpsFamilyLinkApi` builds strict JSON with family-v2 header, pinned HTTPS/PB auth,
  no redirects/cache/cookies/raw error retention; delete requires204. Mutations and auth share the
  existing repository mutex and logout generation; real auth refresh and exact roster readback
  are required before UI authority. No offline membership/success cache.
- MainActivity cold `onCreate`, warm `onNewIntent`, pending queue and retained Activity ViewModel
  route only literal `https://kdnfx.space/madre/f/<16 base64url>`. Manifest autoVerify is declared;
  this is NOT public App Links verification. SavedState keeps only validated INVITE, never OAuth,
  PB credential or Undo receipt. PB credential remains in the existing protected token store;
  one boolean local routing marker distinguishes a completed PB Yandex auth from legacy email.
- Sign-in reuses existing foreground SDK/Alice→PB bridge. Partial Alice success does not imply
  PB success. Returning/sign-in/restore never invokes join. Names may honestly be absent.

## Focused evidence
`FamilyManagementTransportTest`: actual loopback socket plus pinned request/strict response cases.
`FamilyLinkRepositoryTest`, `FamilyLinkViewModelTest`: actual production serializer/controller
against an explicitly synthetic HTTP interceptor, not the separate real PocketBase producer.
`FamilyInviteRoutingTest`, `FamilyLinkUiTest`: production queue/manifest and Compose interactions.
Existing account/SDK custody, migrations, domain/style and core20 remain critical.

## Optional visual scope
`./gradlew :app:testDebugUnitTest --tests 'com.polinalinen.madre.familyui.FamilyLinkRenderTest' -Pmadre.familyLinkRender=true`
produces 22 native Compose Roborazzi PNG 412×915 into
`app/build/outputs/roborazzi/family-link/` (required §4 names + pending/full/switch/removal/error/owner
variants). `family_link_render=true` is a manual-only Quality gates selector; its additional K step is separate
from core20 and skips the broad legacy visual/native acceptance work. Its concurrency prefix
is separate from critical-only runs; neither `critical_only` nor other native acceptance
selectors may be mixed with `family_link_render`. Existing selector combinations without this
new flag retain their previous behavior.
Default PR/core20 does not run the new K suite; no committed baseline is rewritten.
These are render fixtures, NOT emulator/device, real authenticated server, K4/K5 e2e or Huawei.

## Direction contract (existing world, no redesign)
Mode: Operate. Inherit approved K01–K10 composition and F Paper kit; no AI restyle.
THESIS: personal book stays independent; family access follows explicit identity and consent.
OWN-WORLD: existing four paper themes, Literata/Manrope, monograms, outline icons, PaperCard,
PaperSheet/PaperDialog and official YandexIdButton; no portrait shown to family.
STORY: inspect inviter/sharing → sign in → review same invitation → explicit Join.
FIRST VIEWPORT: paper header, large family title, member card or honest empty shelf, one main
accent action; decision overlays preserve consequences, remove checkbox and Undo8s.
FORM: inherited approved K boards1–4, not a new concept/seed or token system.
FINISH: one batched K render self-check and at most one fix-confirmation; full7.1 review belongs
solely to parent. No additional Astra/style cycle and no native detector on Kotlin.

## Local verification (before exact-head CI)
- Final source Kotlin + unit-test Kotlin compile: success, offline/one worker/in-process,
  requested timeout900s; focused tests: 71 tests, no failures/errors/skips.
- Full default suite:1183 tests,213 XML, zero failures/errors/skips; core20 PNG. VoiceMigration2,
  BakeNumberMigration3 and StyleBoundary1 passed. Final additional account-generation fence is
  covered by the subsequent focused compile/tests. No baselines or Room schemas changed.
- Optional K render:22 PNG412×915; one batched visual check + one fix confirmation. No Astra cycle.
- Existing unchanged `BakingCompleteMissingPhotoUiTest` timed out in two full local runs; it passed
  isolated and in full repeats. No ignore, timeout relaxation, or unrelated production fix was made.
- Evidence folders under `app/build/reports/`: `family-link-core-verified/`,
  `family-link-focused/` (includes render manifest), `family-link-focused-final/`.
- Preimage git bundle verified at `/root/.hermes/cache/scratch/madre-family-ui-de88.bundle`, SHA256
  `57fa0822fd9e7f70ad2937149dbcc8015a1f774877f74efccdeb011fa99e4634`.

## Remaining, intentionally not claimed
Separate PB producer/integration and capabilities/enable gate; Android B7–B10 real roster adapter,
lease/receipt executor; K11 public fallback/assetlinks/real production certificate; deployment
backup/rollback/cutover; native emulator e2e and owner Huawei verified-link/fallback/SDK acceptance.
No phone install, live auth, web/edge/server mutation, version bump, APK delivery, main merge,
tag, release or RuStore in this slice.
