# 7.1.21 — Источники фото

## Why
The existing album combines feeding and bake references without source selection. Implement only the approved source filters from SCREENS-7.0.3 §5; the completed source audit is authoritative for persistence and scope.

## Change
- Add a presentation-only nullable source kind to GalleryPhoto, explicitly assigned by both real MadreNavHost producer branches. Legacy four-argument callers remain unclassified and appear in All only.
- Show the local heading «Фото» and existing PaperSegmented «Все N / Закваска N / Выпечка N» over nonblank photo references. Missing files and duplicate paths remain counted and retain their record identities, timestamps, captions and stable ordering.
- Keep selection saveable for the same album Nav entry and actual book generation. Reset on real user/config transitions, including offscreen A→B→A, and on a fresh album entry; unknown bootstrap and renames do not reset it.

## Acceptance / verification
Real Compose and NavHost/Room tests cover both typed producers, misleading identifiers/captions/paths, shared JPEG, missing references, null/blank paths, reactive counts and filtered empty state, legacy unclassified construction, stable ordering, viewer behavior, same-entry restore/delayed bootstrap and owner ABA. All existing navigation tests (including F4 draft/gallery Back) remain byte-identical and run in the focused gate. Actual TextLayoutResult checks cover the heading and all three labels at 320dp, font scales 1/2, and all four fixed atmospheres without pixels.

Run style boundary and exact-head debug/release Kotlin compilation plus focused unit tests only, via the installed resource-locked launcher. One unpublished microcommit. Parent owns review and the full integrated suite.

## Boundaries / flag
Presentation-only completion of the existing album, no new feature flag or persistence. No kit, VM, domain, schema or backend change. No grouping, grid redesign, multiselect, removal, export, render, extended tests, device/APK, integration or external publishing. Preserve the existing F4 Nav-entry and submission lifetime unchanged.
