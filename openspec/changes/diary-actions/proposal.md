# Diary actions — 7.1.14 (67)

## Why / scope
SCREENS3:101: make feeding explicit and show the live size of the album the diary actually opens. Preserve the incumbent PRIMARY feeding / SECONDARY photo actions, callbacks, palette, kit defaults (except the explicitly allowed backwards-compatible label-width opt-in below), hero, presentation clock, and F4 draft lifetime.

## Contract
- Labels are exactly `+ Покормить` and `Фото N`.
- `N` is `galleryPhotos.size` from the same reactive NavHost list passed to PhotoGalleryScreen: feeding AND bake entries with nonblank paths; duplicate paths remain separate records and missing files remain placeholders.
- `Фото 0` stays enabled and opens the actual empty album.
- Button labels use the unchanged kit type/variants/tap targets. The explicitly allowed `PaperButton` label-width opt-in is backwards-compatible: preserve the original signature/JVM entry point and default `fillLabelWidth = false` behavior; use `fillLabelWidth = true` only for the two Diary actions (both row and stacked layouts). This changes only the label's available width; font, palette, padding, tap targets and all other callers remain unaffected. Locally stack actions when measured labels plus kit padding cannot fit the row; do not shrink text.
- No album-content, photo-byte, persistence/schema, storage/permission, backend or identity changes. Synthetic Room/photo fixture writes are tests only.

## Acceptance
1. Real NavHost / Room: one feeding + one bake => 2; later feeding duplicate + missing bake => 4; null/blank excluded; opened gallery shows those same entries. Navigation preserves all synthetic source rows and actual JPEG bytes.
2. Empty album opens; feeding opens the existing form; successful feeding photo save updates the label. All 13 pre-existing F4 cases remain intact.
3. Native button roles, exact labels and onClick labels, callbacks, enabled zero state, >=50dp targets, measured untruncated full-size text at 320dp and fontScale 2; zero/multidigit/live labels.
4. Style-boundary check and focused common-flock debug/release compile + executed Diary/Home/Gallery/F4 tests. Parent owns the single review/full integration gate; leaf does not push, build APKs or touch devices.
