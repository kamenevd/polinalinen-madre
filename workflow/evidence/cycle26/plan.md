# Cycle 26 — factual UX/data foundation

## Authorization and sources

Dima, product owner, explicitly authorized alignment of all user-visible guidance,
reminders, and editorial copy with the private Levito Madre booklet. Source pages:
booklet p.3 for 2:1:2, 50% hydration, white high-grade wheat flour above 10 g
protein, middle refrigerator shelf at 4–6°C and 3–5 day refrigerated feeding
cadence, and approximate 3–5 h peak as labelled booklet guidance. p.33 for the
statement that the booklet is a free appendix drawing on public sources. OCR is
navigation aid only; uncertain OCR is never quoted.

## Acceptance

- Display precedence for hydration in the Starter Diary table is fixed: computed `finalHydrationPercent`
  first, then legacy `hydrationPercent`, else a dash (`—`).
- Home and WorkManager share one due calculation based solely on saved personal
  interval and timestamp. Home refreshes it while visible and on lifecycle return,
  with an injectable clock for deterministic tests. The Home action appears when
  due or when no first feeding exists (`NeverFed`); permission state cannot
  affect it.
- Exact next-feeding datetime must be shown in local time and always in the
  exact form `Следующее кормление: 19 августа, 08:30`.
- Feeding math seeds exclusively from the latest computed hydration and uses
  insertion order (`id DESC`) when timestamps regress.
- Feedings use automatic hydration only: 50/100/50 are editable convenience
  defaults only; no manual hydration field and no manual hydration validation.
- DB baseline for this cycle is schema `v8`; cycle migrations are additive and
  non-destructive `8 → 9` and `9 → 10` (`v9` is not a released populated
  baseline); working schema is `v10`.
- Migration chain reaches `Room` schema 10 (v9→v10 included), preserving all
  legacy rows, legacy hydration/observations, notes, and photo paths while adding
  `retainedStarterGrams`, `finalHydrationPercent`, and `generatedComment`.
- `Hydration` in history and next-calculation seed is taken from the latest
  computed hydration (`finalHydrationPercent`) by insertion order (`id DESC`) when
  tie-breakers or clock rollback happen. Null means no computed value exists yet.
  Legacy `hydrationPercent` is for display only.
- Comments remain immutable snapshots of facts at save time; weather is only added
  when permission and fresh coarse location allow a successful fetch.
- Formula is exact and timestamped. Hydration math is rational `Long` arithmetic with
  final half-up rounding only (`87.5 -> 88`), no truncation from division order.
- Start-of-book reference: 2:1:2, 50% hydration, белая пшеничная мука >10 г белка/100 г,
  хранение 4–6°C, и кормление в холодильнике обычно через 3–5 дней.
- Table (`StarterDiaryScreen`) has accessible ordered heading semantics and one
  ordered sentence per row: six columns, sticky header, lazy full history,
  hydration displays `finalHydrationPercent`, then `hydrationPercent`, and only when
  both are null uses `—`/`не указана`. This computed→legacy→dash fallback applies to the formulary table.
- Home's current starter-status headline uses a computed snapshot and must not promote any
  legacy value to current truth.
- Share to family remains restricted to `feedingId`, flour, water and timestamp.
- `save` in the form is not considered complete until successful local
  persistence; duplicate taps while saving are rejected.
- Weather and location are current-only: no background tracking and no raw
  coordinate persistence, coarse location no older than 30 minutes.
- Release screenshot contract includes feeding form and formulary in 360dp/1080×1920
  portrait (actual local exact next-feeding datetime, deterministic data).

## Non-goals

No recipe ingredient/procedure edits, release/versionCode bump, credential edits,
push, PR, RuStore upload, APK/AAB publication, notification redesign,
foreground service changes, routing changes, Chronometer, or destructive migration.

## Recipe reconciliation backlog for Claude Opus

After its limit resets, inspect the original page scans (not OCR alone) and produce
a page-cited reconciliation of every ambiguous recipe-adjacent/editorial claim. Do
not touch canonical `recipes.json` ingredient or procedure fields until that
review resolves wording and provenance. Specifically audit remaining flour,
whole-grain/rye, fermentation-state, timing, and attribution claims against
original scan pages.

## Release screenshot contract

After a successful APK build, 1080×1920 capture status is complete:

- Captured states (7): starter diary, Home not-due hydration, feeding form with automatic
  hydration, exact weight input, generated immutable comment, Home due with
  `Кормление уже по вашему расписанию`/`Покормить Мадре` and feeding reminder notification.
- Missing mandatory states (0): none.
- Visual and release gates remain pending.
