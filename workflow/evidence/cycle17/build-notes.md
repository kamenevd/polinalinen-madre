# Cycle 17 build notes

## Done
- Stage0 state truth (CYCLE.yaml 17 maintenance, DESIGN 15-17, ADR-0003, contracts)
- P0 family-sync-honesty (commit c804a48)
- P1 data-loss-guards: restore policy A, WM step deadline each transition,
  camera rememberSaveable paths, ChapterPhotos IO via produceState,
  orphan photo sweep, androidx.exifinterface, VisibleForTesting apiOverride,
  ADR-0004 targetSdk 35

## Live PB
2026-08-07 public list bake_stats/feeding_stats/margin returned HTTP 200 empty.
Family migration still shipped versioned.

## Claude Code
Session limit hit mid-P0; P0 finished by orchestrator; P1 by orchestrator.
