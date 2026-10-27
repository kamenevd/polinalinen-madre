# Diary tabs — 7.1.15 (68)

## Scope
SCREENS3:103 only: use the existing PaperSegmented with «Журнал | Советы», Journal by default. Switch the existing guide and formulary, not their content or renderers. Keep the header, hero, handwritten entries/InkBlot and feeding/photo actions outside the switch. No kit, palette, typography, data/schema or backend changes.

## State and navigation
- Selected tab is one saveable caller-side slot scoped to the real Diary visit; feeding/gallery → Back and ordinary restoration retain it. Standalone screen callers keep the existing local saveable fallback.
- Gate the entry on known actual `userId:configId`; unknown bootstrap must not consume restored UI state. Reset that single slot on each real owner transition even when Diary is offscreen, so A→B→A resets to Journal. Renaming the same owner does not reset.
- Gallery above Diary is still part of that Diary visit: retain its existing closed feeding draft until actual Diary exit, owner change or successful save. No second draft model/preferences are introduced.

## Acceptance
1. Native selected role/state, real clicks including idempotent re-selection, mutually exclusive guide/formulary, empty history, saved-state restore, and 320dp/fontScale2 real text layout without overflow.
2. All 24 test records remain accessible through the unchanged lazy rows/disclosure. Guide string and FormularyHeader/FormularyRow bytes stay unchanged.
3. Real NavHost/Room form close/reopen + gallery→Back preserve exact pending grams and note alongside Advice. Recreated VM with deliberately delayed null bootstrap retains them after restore.
4. Actual Room config/user identities reset the tab, including A→B→A while the form/gallery covers Diary; same-owner rename retains it. Preserve all 15 prior navigation/F4/gallery cases and 13 existing Diary cases.
5. Style check; registered common-flock debug/release compile and focused executed tests with fresh JUnit. Parent owns the one review/full exact integration gate and push. No APK, rendering, device or remote publication in this leaf.
