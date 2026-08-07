# ADR-0003: Missing cycle 15/16 workflow evidence

- **Status:** Accepted
- **Date:** 2026-08-07

## Context

`workflow/evidence/` contains `cycle13/` and `cycle14/` only. Cycles 15 and 16
shipped (merged to main: Cycle 15 stability/data/coverage; Cycle 16 performance
toolchain and Compose costs) but their gate artifact directories were never
checked into the repo. Fabricating pass evidence after the fact would fake the
control plane.

## Decision

Missing evidence for cycles 15 and 16 is recorded as **historical evidence
unavailable**. We do not invent test-summary.json or reviews.md for those runs.
Cycle 17 maintenance restores forward integrity: CYCLE.yaml, DESIGN-V4 headings,
contract tests, and fresh `workflow/evidence/cycle17/` produced by this run only.

## Consequences

- Agents must not claim gate PASS for 15/16 without artifacts.
- Documentation reverse-fill in DESIGN-V4 is allowed from code comments and merge
  commits, labeled as retrospective.
- New cycles must write evidence before stage advances past the matching gate.
