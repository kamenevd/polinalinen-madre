# Cycle 18 plan

Date: 2026-08-09 (night autonomous)

## Scope
Block B UX minimal: B0 control language, B2/B3/B4 home cleanup, B5/B6 feed+chronicle.

## Out of scope
- Full B1 audit of every raw clickable outside Home
- B7/B8 tab IA redesign
- Decorative new effects

## Preconditions verified live
- origin/main at v6.1.0-cycle17 lineage
- PB family_rules + client_event_id applied on LXC108 :8091
- Public downloads + madre-api restored via matrix-nginx

# Cycle 18 plan — MoA REVISE lock (2026-08-09)

## Product decisions (orchestrator after MoA REVISE)

### P0 Home semantics scope
- CLAUDE.md rule remains the **canon for new/changed controls**.
- Cycle 18 **Home a11y test** asserts only: TextAction/BookButton feed CTA, Летопись, no CommunitySection, Ribbon/Mood non-action.
- Residual Home clickables (ticket, MadreLine, ChapterRow, dog-ear, weather invite) get Role+label+48dp **if cheap in same PR**; otherwise listed as known exceptions in CLAUDE.md until B1 Home pass.

### P0 Notification «Покормила»
- **Behavior = open feeding form** (MainActivity/deep-link → starter/feed). Does **not** write a feeding row.
- Action title: **«Покормить»** (present tense; honest).
- Unit test: action title + PendingIntent extras/component; FLAG_IMMUTABLE; no-op path when notifications denied.

### P1 Feed CTA honesty
- Inactive when `GrowthPhase.LAG` **or** hours since last feed < 1h (if millis available).
- `enabled=false` + caption with relative time when inactive.
- **PRIMARY** only when phase in HUNGRY/DECLINING/EMPTY; else **SECONDARY**.
- Acceptance: «1 тап с главной **до формы** кормления», not completed log.

### P1 Mood / Ribbon
- MoodBookmark: decorative only — **no** Role.Button, no clickable, description non-action.
- RibbonBookmark when bake active: visual-only; description must **not** say «Открыть таймер» (ticket is the only timer path).

### P1 Chronicle label
- Masthead action renamed **«Летопись»** (not «Полка»).
- Navigates to `bookStats("me")`.
- ShelfScreen remains in code for friends path if referenced; daily path does not use it.
- Nav test: Home → BookStats(me).

### P2 Community
- Remove from Home; if CommunityStatsViewModel has zero call sites after — delete + graveyard note; keep pure model tests only if model stays.

### Effects budget
- Do not add a third visual effect on Home (already breathingPage + dampPaper).
