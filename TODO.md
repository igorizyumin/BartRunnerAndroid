# BART transit data-model rearchitecture

This document tracks the migration from the former static/GTFS-RT routing
pipeline to one canonical transit snapshot. The target architecture is:

```text
Static GTFS + raw GTFS-RT
          ↓
normalization, association, and source reconciliation
          ↓
CanonicalTransitSnapshot
          ↓
RaptorRouter
          ↓
Departure / trip-progress projections
          ↓
ViewModels and GUI
```

The canonical snapshot is the only source that should decide how static
schedule facts and realtime observations combine. Downstream code may query,
route, or format that result; it should not reinterpret the two feeds.

## Status — September 17, 2026

Completed foundation:

- Lossless GTFS-Realtime normalization with immutable entity and stop
  observations.
- Service-date-scoped static trip identity.
- Exact static/realtime association before heuristic association.
- Explicit cancellation and duplicate-decision metadata.
- Canonical static-plus-realtime schedule correction.
- DMU/electric transfer pairing with provenance and confidence.
- Canonical DMU stop-time enrichment, including reported PITT timing.
- ETD corroboration and synthetic late-night SFO–Millbrae service removed from
  the runtime path.
- `RouteDepartureProjection` now receives a canonical snapshot and its route
  projection path does not parse unmatched realtime trips.
- Legacy raw/normalized handler APIs and static route helpers have been removed
  from production; the canonical projectors are now the only supported path.

Existing verification recorded for the completed slice: focused/full unit
tests, lint, debug APK build, and canonical DMU regression tests. Connected
Android-test execution remains unfinished work below.

## Code-review assessment — September 17, 2026

The accompanying code review is substantially correct. The canonical data
pipeline and RAPTOR migration are complete enough to preserve, but the
following follow-ups are warranted. KSP releases no longer need to match the
Kotlin compiler version; the remaining build task is to move the pinned KSP
version to the proposed `2.3.12` and verify the build. The elevator API key is
intentionally public and needs no remediation. Line colors are intentionally
UI-owned so themes can customize them; they should not be derived from GTFS.

## Architectural rules

- Static GTFS owns published service dates, station order, platform stops,
  route/line identity, directions, and scheduled times.
- GTFS-Realtime owns observed timing and explicit status changes.
- A partial realtime update must overlay the static pattern; it must not
  shorten or replace the passenger pattern merely because its stop list is
  incomplete.
- Operational 600–799 DMU records remain provenance/transfer evidence. They
  are not passenger trips.
- An omitted realtime entity means unknown, not canceled.
- `RaptorRouter` is the application’s timed routing implementation. Static
  route enumeration is legacy and must not remain a second routing engine.
- Projection code must consume canonical data and must not normalize feeds,
  rebuild schedules, associate trips, or parse raw protobuf entities.
## Remaining work

### Priority 1 — build and realtime correctness

- [x] Update the pinned KSP plugin to `2.3.12` and verify the complete build
  gate.
- [x] Define one partial-feed policy for every refresh path. Retain the
  last-known-good trip-updates and alert feeds independently, attach per-feed
  freshness/error metadata, and use schedule-only data only when no usable
  realtime remains.
- [x] Add repository tests for each partial-failure direction, initial failure,
  recovery, and stale-feed expiry.

### Priority 2 — alarm lifecycle and API safety

- [x] Re-arm persisted followed-trip and polling alarms after reboot with a
  minimal non-exported boot receiver; handle exact-alarm permission, expiry,
  idempotence, and emulator verification.
- [x] Provide a suspend refresh operation or explicitly named blocking test
  helper, migrate production callers, and guard against main-thread refreshes.

### Priority 3 — maintainability and policy ownership

- [ ] Split `ui/BartRunnerUi.kt` by screen and shared UI concerns while
  preserving public composable entry points.
- [x] Centralize BART routing/data policy constants, including route-ranking
  weights, station sets, the DMU range, OAKL filtering, and Pacific time zone;
  keep display colors UI-owned.
- [x] Add a short data-audit note for intentionally BART-specific policy facts.

### Priority 4 — hygiene and verification

- [x] Add backup/data-extraction rules excluding the re-fetchable GTFS Room
  cache while preserving user-followed-trip state where appropriate.
- [x] Remove the unused release NDK debug-symbol setting, update the package
  name in `AGENTS.md`, and remove obsolete empty source-tree remnants.
- [x] Bound or prune expired `DepartureAlarmScheduler` preference keys; split
  polling/show-activity request-code constants if that file is touched.
- [x] Document the intentional destructive Room migration and bounded
  background RAPTOR cost.
- [x] Add the remaining canonical fixture matrix for feed-shape edge cases:
  after-midnight service, calendar exceptions, parent stations/platforms,
  missing route IDs/sequences, partial stops, cancellations, duplicates,
  stale origins, DMU observations, and source ambiguity/skew.
- [x] Run connected Android tests on the Pixel 10a emulator.
- [x] Audit persistence and notification consumers for canonical departures or
  canonical trip state rather than reconstructed feed data.
- [x] Confirm one canonical passenger-trip truth feeds routing, departures,
  trip following, persistence updates, and notifications.
