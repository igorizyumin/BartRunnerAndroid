# Schedule and realtime merge audit

# Current runtime note

The design described below is a historical audit of the pre-canonical path.
The current runtime uses `CanonicalTransitSnapshot` as the single source of
truth, merges electric and DMU GTFS-Realtime observations there, and does not
use ETD. The ETD sections remain only to explain the evidence behind retiring
that path.

Status: documentation-only audit of the current working tree. No application
or test code was changed for this audit.

The observations below preserve the pre-canonical findings for historical
context. The current runtime has completed this migration: the legacy handler
and its compatibility facade have been deleted, and canonical projectors are
the only production projection path.

## Executive summary

The pre-canonical app had three different representations of service:

1. `Schedule` is a static, time-windowed GTFS graph with exact-trip realtime
   corrections.
2. The legacy `GtfsRealtimeContentHandler` built a second snapshot list from the same
   static graph plus every parsed trip-update entity, then performs a local
   Yellow/DMU join and suppression pass.
3. UI and trip-following code merges the resulting `Departure` values by a
   display-oriented identity.

These layers do not share one authoritative trip identity or one authoritative
terminal-service model. That is the main reason the Yellow line can look
plausible in one projection and be wrong in another.

The most important confirmed discrepancies in that historical path were:

- BART's DMU telemetry does not carry a valid `trip_id` that identifies the
  electric portion of the same passenger trip. `Schedule.applyRealtime()`
  therefore cannot perform that join; the handler may join it later, but only
  for an electric realtime snapshot that it can match.
- A DMU update with no matching electric realtime trip is discarded from
  passenger output; a schedule-only electric trip is not eligible to receive
  that DMU update.
- The SFO–Millbrae shuttle appears to have the same identity/feed limitation:
  it is not represented as a normal GTFS-RT trip update in the checked-in
  behavior. Whether BART supplies a dedicated static-GTFS shuttle trip is not
  yet independently verified; the current code synthesizes the late-night leg
  from a Yellow SFO stop and nominal running time instead.
- BART can omit the GTFS-RT update for a trip that would normally be present
  when that trip is canceled. The one-hour rule is therefore a probabilistic
  operational cancellation heuristic, not an explicit cancellation signal.
- Schedule suppression is station-global for trips inside the first hour:
  one forward realtime departure at the queried origin can suppress unrelated
  schedule branches.
- Existing connecting legs are considered feasible during refresh when their
  times are merely chronological; the configured transfer margin is not
  checked on that fast path.
- The fallback trip matcher for progress refresh uses the first same-line,
  same-terminal trip that serves the leg. It does not compare scheduled or
  realtime times, and the rebuilt leg retains the old trip ID.
- `Departure.merge()` keeps most top-level fields from the previous value even
  when incoming trip legs carry changed platform, terminal, line, or
  cancellation metadata. `Departure.identity` itself includes platform, so a
  platform change can instead bypass merging entirely.

## Actual data flow

```text
GTFS static catalog
    -> Schedule.fromStatic(feed timestamp)
    -> Schedule.applyRealtime(index)
    -> TransitFeedSnapshot corrected-schedule cache
                                      |
                                      v
                         RouteDepartureProjection
                                      |
                                      v
                            RealTimeDepartures
                              /             \
                 departures screen       trip following
                 Departure.replaceFeed    updateTripLegs/Departure.merge
```

`TransitRepository` replaces complete feed snapshots. It does not call
`GtfsRealtimeFeedIndex.merge()` during normal refresh. When a fetch is partial,
it retains the previous missing feed component; it does not reconcile entity
lifetimes or merge trip updates across feed timestamps.

`TransitFeedSnapshot` lazily indexes trip updates and alerts once per snapshot
and caches one corrected `Schedule` per network object. The cache prevents
duplicate work for consumers of the same snapshot, but the public
`Schedule.applyRealtime()` operation itself is not guarded against being
called repeatedly.

## Static schedule behavior

`Schedule.fromStatic()`:

- uses the feed timestamp in the Pacific time zone;
- loads the current and previous service dates;
- requests a 30-minute look-behind and two-hour look-ahead;
- keeps a trip when any static stop falls in that window;
- collapses platform stop IDs to passenger stations and removes `SPCL`;
- sets the static train destination to the last retained station; and
- derives nominal adjacent-station travel times from the median static sample.

It de-duplicates trips by `tripId` alone, not by `(serviceDate, tripId)`. That
assumes the source feed has globally unique trip IDs across service dates.

It creates only the explicit late-night synthetic SFO–Millbrae trip when the
feed timestamp is 21:00–05:00 and a Yellow trip reaches SFO without already
serving Millbrae. That synthetic trip uses the Yellow trip's effective/static
SFO arrival plus the nominal SFO–Millbrae running time; it does not require a
separate SFO–Millbrae GTFS-RT entity. The implementation does not synthetically
extend a Pittsburg/Bay Point short-turn to Pittsburg Center or Antioch.

## Exact-trip realtime correction

`GtfsRealtimeFeedIndex.from()` retains all trip-update entities for parsing,
but `tripUpdatesById` excludes numeric trip IDs 600–799. For duplicate
non-DMU trip IDs it prefers a cancellation relationship change and otherwise
the entity with at least as many stop-time updates.

`Schedule.applyRealtime()` then iterates only the existing non-synthetic static
trips and looks up an exact trip ID. For a matching update it:

- maps stop IDs to stations, with an abbreviation/prefix fallback;
- applies absolute event times, then delay-only values against that stop's
  static time;
- preserves static times and provenance separately;
- marks explicit trip cancellation;
- marks skipped stops when a stop update is mapped; and
- estimates only stops after the last mapped realtime stop, using the previous
  effective departure plus a median adjacent-station travel time.

It does not add realtime-only trips, infer a missing trip ID, or apply a
600–799 DMU update. This is the authoritative behavior of the cached corrected
schedule, even though the handler has additional parsing behavior later.

## Yellow and DMU behavior in the legacy handler

The handler parses the static schedule into mutable `TripSnapshot` objects and
parses every trip-update entity separately. It resolves a missing route ID from
the static trip catalog; an unknown route with Pittsburg, Pittsburg Center, or
Antioch stops is classified as `YELLOW_DMU`. This classification is necessary
because BART's DMU update has a technical trip ID (for example, a 600–799 ID)
that is not the valid passenger/electric trip ID.

For a DMU join, the handler:

1. selects non-canceled Yellow realtime snapshots with non-DMU IDs;
2. derives north/south from platform 1/2;
3. takes Pittsburg Center as the northbound terminal evidence and Antioch as
   the southbound terminal evidence, with fallbacks;
4. optionally checks that a known static electric trip can serve PITT→ANTC or
   ANTC→PITT;
5. matches the nearest unused electric realtime snapshot within 20 minutes;
6. copies terminal points into that electric snapshot while retaining the
   electric trip ID; and
7. drops DMU-only snapshots before passenger departures are emitted.

Consequences that motivated the canonical redesign:

- the join is not an identity join because BART does not provide a shared trip
  ID; it is a time/direction/platform heuristic;
- it cannot enrich an electric trip that exists only in static GTFS;
- it can only claim one DMU update per electric realtime snapshot;
- the handler's enriched copy existed only for that projection call.

The late-night SFO–Millbrae case is similar but currently modeled differently:
the schedule layer creates a synthetic `YELLOW_LATE_NIGHT` trip when the static
Yellow pattern reaches SFO without Millbrae. No RT identity join is currently
performed for that shuttle leg. The presence or absence of a dedicated shuttle
trip in the downloaded static GTFS must be verified before changing this model.

The current tests cover synthetic examples for a northbound and southbound
join, rejection of a later Pittsburg match, and suppression of a DMU-only
departure. They do not establish correctness for every simultaneous 600–799
entity, missing electric partner, cancellation, duplicate trip ID, or partial
stop sequence in the captured feeds.

## Schedule coverage and suppression

`parseTrips()` starts with corrected schedule snapshots and then decides which
schedule snapshots survive alongside realtime snapshots.

- ETD reconciliation can request an uncut projection.
- Normal projection suppresses schedule snapshots that appear covered by
  realtime.
- During the first hour after the feed timestamp, the presence of *any*
  forward realtime departure at the queried origin suppresses schedule-only
  snapshots in that window, regardless of the normalized line/destination
  branch. This treats an omitted normally-published trip as likely canceled
  when there is live service evidence, but it does not mark the static trip's
  `canceled` flag.
- After that hour, the cutoff uses the latest realtime departure keyed by line
  and normalized Yellow SFO/SFIA branch. A static trip later than that branch
  cutoff can remain.
- A DMU-enriched static ID is suppressed so its enriched realtime snapshot is
  the passenger copy.

The branch key normalizes Yellow late-night service and treats Yellow trips to
SFO or Millbrae as the same SFO branch. This is useful for avoiding duplicates,
but it is broader than a trip identity and can hide service when the feed's
published set is sparse or split across terminal patterns.

The stale-origin filter in `addTripUpdate()` drops a departure more than
45 seconds before the feed timestamp. This differs from the two-minute grace
period described in older route-planning documentation and must be treated as
the current implementation contract until changed deliberately.

## Omission-based cancellation and ETD validation

BART's feed behavior makes absence ambiguous: some future trips are simply not
published in a snapshot, while a trip that would normally be published may be
omitted because operations canceled it without sending a `CANCELED` entity.
The current handler uses the one-hour suppression window as a heuristic:

- if there is forward realtime evidence at the queried origin, schedule-only
  trips in the next 60 minutes are suppressed;
- the suppression is not written back as `Schedule.Trip.canceled`; and
- outside that window, the handler uses the latest matching line/terminal
  branch prediction as a looser coverage cutoff.

The legacy ETD API is currently used as a second source of evidence. The ETD
aware projection retains an uncut candidate set, identifies schedule-sourced
trips absent from the indexed GTFS-RT IDs, and then uses station ETD data to
match, override, or suppress them. This is defense in depth for the feed's
omission-based cancellation behavior, not proof that ETD is strictly required.
Whether the ETD request and matching layer is necessary can only be decided
after the GTFS-RT coverage heuristic is made explicit and validated against
more complete multi-snapshot captures.

## Realtime refresh and merge behavior

There are two separate refresh paths:

### Departures screen

`DeparturesViewModel` projects a new feed and calls `Departure.replaceFeed()`.
Matching is by `Departure.identity`, which includes line, terminal, direction,
platform, and the ordered trip-leg IDs. A match calls `Departure.merge()`;
non-matching incoming values replace the old list.

`Departure.merge()` uses incoming legs when present and intersects the old and
new estimate ranges, with special handling for already-departed and
long-linger stations. It does not generally copy incoming top-level metadata.
Therefore the displayed metadata and the leg metadata can disagree after a
realtime change.

### Trip following

`TripProgressProjection` rebuilds routes from the latest corrected schedule and
calls `updateTripLegs()`.

- Exact trip IDs are preferred.
- If an exact ID is missing, the first non-canceled snapshot matching line,
  train destination, and leg topology is selected.
- A missing stop in the new feed keeps the old stop.
- The existing leg's trip ID, scheduled times, and endpoints are retained when
  the leg is rebuilt from a fallback snapshot.
- Connecting legs are refreshed only when the old leg is canceled, missing, or
  fails a simple `next departure >= previous arrival` check. That check does
  not include the `TransferPolicy` minimum or extra margin, although the
  replacement search does.

`TripProgressViewModel` also matches a selected departure by exact identity or
by the ordered trip-leg IDs. The second path exists because platform/terminal
metadata can change after a DMU join, but it does not repair the top-level merge
semantics described above.

## Confirmed verification status

Run on 2026-09-15 from the current working tree with the repository-local
Gradle cache:

```text
.\gradlew.bat :app:testDebugUnitTest --tests in.izyum.bart.backend.ScheduleTest --tests in.izyum.bart.networktasks.CanonicalProjectionRegressionTest --tests in.izyum.bart.backend.LiveEtdStationBoardAuditTest
```

Results:

- `ScheduleTest`: 4/4 passed.
- `CanonicalProjectionRegressionTest`: canonical regression coverage retained
  after the legacy handler tests were migrated.
- `LiveEtdStationBoardAuditTest`: 1 passed, 1 failed.
- Failure: `capturedFixturesHaveNoEtdOnlyDepartures` reports that
  `bart_live_20260911_161720` has an ETD row not found in the base projection.
- The same run's exhaustive audit reports 592 ETD predictions versus 2,111
  projected departures for that fixture, with 590 time-matched rows, two
  timing mismatches, and no missing rows in the ETD-aware comparison.

A second focused run of the live routing tests also failed current assertions
for the terminal feed duplicate set, the captured Antioch departure count, the
static/DMU terminal oracle, and the normal Yellow-through-Antioch projection.
Those failures are evidence that the test suite and current implementation
describe different terminal semantics; they are not fixes or conclusions about
the correct BART operational model.

## Recommended audit order (future implementation work)

This section is intentionally a work queue, not an implementation change.

1. Define one passenger-trip identity model for static trips, ordinary RT
   trips, DMU terminal telemetry, added trips, and cancellations.
2. Decide whether terminal continuation belongs in `Schedule`, in the handler,
   or in a separate explicit terminal-enrichment layer; make all consumers use
   that same result.
3. Replace branch-wide absence suppression with entity/branch coverage rules
   that distinguish a missing update from an omission-based likely cancellation
   and an explicit cancellation.
4. Make stop-time merge rules directional and field-specific, including
   partial updates, skipped stops, absolute times, delay-only events, and
   passed origins.
5. Revalidate connecting legs with the same transfer-margin predicate used to
   select replacements.
6. Define whether a fallback trip match is allowed; if it is, require a
   deterministic time/sequence score and update the stored trip identity.
7. Separate stable departure identity from mutable display metadata so a
   platform or terminal correction cannot silently duplicate or strand a trip.
8. Verify whether BART's static GTFS contains a dedicated SFO–Millbrae shuttle
   trip, then add fixture-derived assertions at SFO/Millbrae and
   Antioch/Pittsburg/Pittsburg Center for
   every 600–799 entity, plus multi-snapshot refresh tests that verify identity,
   cancellation, terminal, platform, and connection behavior together.
