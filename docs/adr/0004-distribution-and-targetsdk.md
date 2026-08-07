# ADR-0004: Distribution and targetSdk

- **Status:** Accepted
- **Date:** 2026-08-07

## Context

Google Play requires targetSdk 36 for new apps/updates from 2026-08-31.
Madre is distributed via GitHub Releases / Filebrowser, not Play.

## Decision

Keep `targetSdk = 35` for Cycle 17. Revisit only if Play distribution starts.

## Consequences

- No forced 36 migration in maintenance/17.
- Document stays the source of truth for the choice.
