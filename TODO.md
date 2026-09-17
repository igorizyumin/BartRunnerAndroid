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
- Legacy raw/normalized handler APIs and static route helpers are now marked
  deprecated so remaining migration points are visible in compiler warnings.

Existing verification recorded for the completed slice: unit tests, lint,
debug APK build, connected Android tests, and canonical DMU regression tests.

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

## Remaining work, in priority order

### 1. Make RAPTOR the only route-selection path

- [x] Remove `Schedule.routesFor`, `preferredTransferRoutes`,
  `doubleTransferRoutes`, and `transferRoutes` from production callers.
  They are deprecated static-topology APIs; compatibility tests still
  exercise them directly.
- [x] Change `RouteDepartureProjection` to construct RAPTOR input from the
  canonical passenger schedule and ask `RaptorRouter` for journeys directly.
  Remove direct-route, transfer-route, and double-transfer fallback passes.
- [x] Keep `Route` only where it is needed as journey/display metadata. It
  should be derived from a selected RAPTOR journey rather than used to drive a
  separate static search.
- [x] Migrate `RealTimeDepartures` transfer metadata away from calling static
  schedule route helpers.
- [x] Delete the old static route helpers and remove the historical tests that
  existed only to validate that obsolete routing engine.

### 2. Split `GtfsRealtimeContentHandler`

`GtfsRealtimeContentHandler` currently combines several unrelated roles:

- raw-feed compatibility entry points;
- legacy schedule reconstruction;
- canonical-schedule departure projection;
- RAPTOR input conversion and journey selection;
- `Departure`/`TripLeg`/`TripStop` construction;
- existing-itinerary refresh and connection repair.

- [ ] Extract a canonical `DepartureProjector` that converts selected RAPTOR
  journeys into app models.
- [ ] Move RAPTOR-trip conversion and journey selection into a focused routing
  adapter, or make `RouteDepartureProjection` own that thin adapter.
- [ ] Move existing-itinerary refresh into a separate
  `TripProgressProjector`/`ItineraryRefreshProjector`.
- [ ] Remove the handler’s raw-feed overloads after all production callers
  migrate to canonical inputs.
- [ ] Remove `correctedSchedule(...)` from the handler; schedule correction is
  owned by `CanonicalTransitSnapshot`.
- [ ] Remove `parseRealtimeOnlyTrips(...)` from the route-departure path. The
  canonical passenger-trip collection is authoritative; this fallback must not
  synthesize an additional departure set.
- [ ] Replace the handler’s manual `findConnectingTrip` and
  `refreshConnectingLegs` logic, or isolate it as an explicit itinerary-repair
  algorithm. It must not become a second general-purpose router.

### 3. Finish trip-progress migration

- [ ] Make `TripProgressProjection` consume canonical trip state without
  calling deprecated static route helpers or legacy handler overloads.
- [ ] Use the same transfer-policy predicate for initial routing and itinerary
  refresh.
- [ ] Make replacement of a canceled or infeasible connecting leg
  deterministic and preserve the selected trip identity where possible.
- [ ] Ensure a partially completed leg retains passed stops while future legs
  are refreshed from the canonical snapshot.

### 4. Stabilize departure identity and presentation

- [ ] Separate stable departure identity from mutable timing/platform/display
  metadata in `Departure.merge` and `Departure.replaceFeed`.
- [ ] Ensure a platform or terminal correction updates one departure rather
  than creating a duplicate or stranding the old identity.
- [ ] Ensure every visible departure carries stable identity and source
  provenance sufficient to explain schedule-only, realtime, estimated, and
  canceled states.

### 5. Fixture and parity coverage

- [ ] Add or retain focused fixtures for after-midnight service, calendar
  exceptions, parent stations/platforms, missing route IDs, missing stop
  sequences, partial stop lists, explicit cancellations, duplicate IDs, stale
  origins, DMU observations, and source ambiguity/skew.
- [ ] Add tests proving route projection uses only the canonical corrected
  passenger schedule and does not independently parse realtime entities.
- [ ] Compare legacy and canonical outputs for every checked-in capture until
  each difference is classified as an intentional behavior change.
- [ ] Remove or rewrite static-routing tests once the production migration is
  complete; retain only tests for behavior still required by the canonical
  router.

## Deprecated API inventory

These are intentionally still present because they have callers. Do not add
new call sites:

- Raw and normalized-feed `GtfsRealtimeContentHandler` departure overloads
- Legacy `GtfsRealtimeContentHandler.updateTripLegs(...)` overloads
- Private handler compatibility path for realtime-only trip parsing

The compiler warnings are migration markers, not errors to suppress globally.
Once production callers are removed, delete the deprecated APIs and their
obsolete compatibility tests rather than merely silencing the warnings.

## Verification gate

- [ ] All focused canonical, routing, departure, and trip-progress tests pass.
- [ ] Full `:app:testDebugUnitTest` passes.
- [ ] `:app:lintDebug` passes.
- [ ] `:app:assembleDebug` passes.
- [ ] Connected tests pass on the Pixel 10a emulator.
- [ ] No production code depends on deprecated static-routing or raw-feed
  projection APIs.

## Completion criteria

- [ ] One canonical passenger-trip truth feeds routing, departures, trip
  following, persistence updates, and notifications.
- [ ] RAPTOR is the only timed route-selection implementation.
- [ ] No projection rebuilds or independently interprets static and realtime
  data.
- [ ] Partial realtime updates cannot truncate static service patterns.
- [ ] DMU telemetry remains available as provenance without becoming a
  passenger GTFS trip.
- [ ] Connection refresh uses the same transfer semantics as initial routing.
- [ ] Visible departures have stable identities and explainable provenance.
