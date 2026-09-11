# BART route planning and GTFS-Realtime trip updates

This document describes the route-planning pipeline used by BartRunner and the
normalizations required for BART's GTFS and GTFS-Realtime feeds. The JSON files
in this directory are readable copies of the protobuf fixtures in
`app/src/test/resources/gtfsrt/`.

## Data sources

BartRunner uses two related but different feeds:

1. The static GTFS schedule defines the network topology, station order,
   route patterns, trip metadata, stop times, service calendars, and transfer
   rules.
2. The GTFS-Realtime trip-update feed supplies current arrival and departure
   predictions for individual trips.

The realtime feed is not a complete schedule. It is an update stream for the
trips BART currently chooses to publish. A missing trip-update entity does not
mean that a scheduled train does not exist.

The static feed is loaded into `GtfsNetworkCatalog` and adapted to
`BartGtfsNetwork`. Calendar and stop-time data are retained so the static
schedule can provide missing future trips. For an after-midnight lookup, both
the current service date and the previous service date are considered because
GTFS times such as `24:29:00` belong to the previous service day's trip but
occur at 00:29 on the following civil date.

## Route-planning pipeline

### 1. Build the topology

`BartGtfsNetwork` maps source GTFS IDs to the app's stable `Station` and
`Line` values. It derives ordered station patterns from `stop_times.txt`,
preserving route direction and trip membership. Static station order is the
authoritative answer for questions such as:

- which station is next;
- which station is the terminal;
- whether a destination is ahead of an origin;
- whether a transfer path passes through the requested destination too early.

### 2. Select candidate routes

`Schedule.routesFor(origin, destination)` selects routes from the same
time-scoped graph used for predictions. It selects in this order:

- direct catalog-backed routes, one for each usable direction/pattern;
- preferred one- or two-transfer catalog routes when no direct route exists;
- terminal shuttle extensions for Pittsburg Center and Antioch;
- special late-night SFO-to-Millbrae routing when the destination is Millbrae.

Routes contain line sequences, transfer stations, direction, and the station
sequence for each leg. Route scoring prefers fewer transfers, with a few BART-
specific preferences for common East Bay/San Francisco trunk journeys. A route
is rejected when the corrected schedule has no usable service for one of its
legs; a route with no static trips in the current window remains eligible
because realtime may still supply that trip.

Station-only queries use the longest useful static pattern for each line at the
origin. They are deliberately not destination-filtered, because the board must
show every applicable train terminal.

### 3. Build and correct the Schedule

`Schedule` is the time-dependent source of truth. It builds a graph whose
nodes are passenger stations and whose edges are individual scheduled train
movements. It retains the original scheduled arrival/departure for every stop
and derives nominal directed travel times from the static feed.

The live trip-update index is applied as corrections to that graph. A missing
realtime entity leaves the static trip intact; it is not treated as a
cancellation. Static trips therefore fill omitted future trips without being
converted into synthetic GTFS-Realtime entities.

### 4. Parse each trip into an itinerary

The projection converts corrected Schedule trips into `Departure` values and:

1. resolves the route ID, falling back to the static trip catalog when the
   realtime descriptor omits it;
2. maps platform stop IDs to passenger stations;
3. overlays realtime times onto the static station sequence;
4. retains absent static stations and estimates missing downstream arrivals
   from the previous effective departure plus nominal segment travel time;
5. applies direction filtering for destination queries;
6. builds one or more `TripLeg` values and validates every requested leg;
7. converts valid trips into `Departure` values.

A destination query rejects an itinerary unless every route leg is complete and
the final leg ends at the requested destination. A station-only query instead
uses the train's static terminal as its displayed destination.

## BART GTFS-Realtime quirks

### Realtime trip updates are partial

BART can omit a future scheduled train from the trip-update feed. The absence
of an entity must therefore not be interpreted as cancellation or as proof that
no departure exists. Static stop times are the fallback source for missing
future trips.

### Stop updates can be incomplete

A trip update may contain only a subset of the stations, may stop before the
terminal, or may have no usable departure event for the final station. The
static trip sequence supplies the missing station order and terminal. Realtime
times are used where present; missing downstream arrivals are estimated from
the previous effective departure plus nominal travel time for that station
pair.

This prevents the classic off-by-one destination bug: the last realtime update
must not be treated as the train's terminal when the static trip continues past
that update. For example, a Daly City train can have its last published update
at Balboa Park while still being a Daly City train.

### Arrival and departure are separate fields

At a station, arrival and departure may have different delays and timestamps.
Realtime event times take precedence, followed by delay-only corrections. Each
effective time retains a provenance of `REALTIME`, `SCHEDULE`, or `ESTIMATE`.

### Route IDs may be absent

Some trip descriptors do not carry a route ID. The parser resolves the route
from the static `trip_id` catalog. Unknown trips are ignored unless their stop
pattern clearly identifies the Antioch/Pittsburg DMU shuttle.

### Platform stop IDs are not passenger stations

The feed frequently uses IDs such as `M50-1` and `M50-2` for platforms. These
must be mapped to the parent passenger station before route ordering,
destination matching, or display. Platform suffixes are still useful for
identifying the direction/platform of special shuttle updates.

### Pittsburg Center and Antioch use separate update streams

The main schedule update can describe the train to or from Pittsburg, while a
separate DMU update describes the terminal shuttle between Pittsburg, Pittsburg
Center, and Antioch. Their trip IDs are not necessarily joinable.

The Schedule always extends a Yellow trip through the terminal shuttle when
the main trip ends at Pittsburg. It uses static nominal Pittsburg-to-Pittsburg
Center and Pittsburg Center-to-Antioch timings, and marks the continuation as
estimated unless the separate DMU update can be joined using platform,
direction, and a bounded timing window. The result is an explicit
`YELLOW_DMU` terminal leg rather than a parser-only special case.

### The late-night SFO/Millbrae change is a transfer

At night, the East Bay train can terminate at SFO while BART represents the
SFO-to-Millbrae movement as a separate shuttle. The shuttle may have no
realtime trip entity at all.

For an origin-to-Millbrae query, the planner can therefore create:

```text
YELLOW: origin -> SFO Airport
YELLOW_LATE_NIGHT: SFO Airport -> Millbrae
```

When the second entity is absent, the handler creates an unscheduled terminal
leg with unknown timing rather than returning “No departures found.” The first
leg still requires a real or static scheduled SFO train.

A train whose terminal is Millbrae can still be a valid `YELLOW` leg for an
earlier destination such as 16th Street. `YELLOW_LATE_NIGHT` is applied to the
Millbrae leg or to a station-board train being shown as Millbrae-bound; it is
not applied merely because the train eventually continues to Millbrae.

The special topology is selected from the feed timestamp during the local
21:00–05:00 service window. During the day, a direct Yellow trip to Millbrae
remains a normal single `YELLOW` leg.

### Direction is a route property, not just a train destination

The route direction is derived from the static GTFS route name (`-N` or `-S`)
and is matched against the requested route. For destination queries, a train
going the wrong direction is rejected even if its stop list happens to contain
both station names in an unusable order. Station-only boards do not apply this
destination-direction filter.

### Old origin departures are not current departures

The feed can retain a trip entity after the train has passed the queried
origin. Staleness is determined from the trip's origin departure event, not
from the age of the feed entity itself.

The handler allows a two-minute grace period for feed latency, clock
differences, and a prediction that is just late. It compares the origin event
with the feed timestamp:

```text
origin departure >= feed timestamp - 2 minutes  -> keep
origin departure <  feed timestamp - 2 minutes  -> drop
```

For example, with a feed timestamp of 12:00:

- a departure at 12:03 is kept;
- a departure at 11:58:30 is still kept as possibly just leaving;
- a departure at 11:45 is discarded.

The same rule applies to static fallback entities. A correct future departure
remains valid even if the surrounding feed entity was created earlier. A
departure kept inside the grace period may be displayed as leaving or departed
depending on the station's normal departure-display policy; the grace period
only decides whether it remains in the current departure set.

### Transfer connections need complete time ordering

For a transfer itinerary, the next train must depart after the previous leg
arrives plus the station's minimum transfer time. A route is not considered
valid merely because both legs exist in the feed. This is why static trips are
merged before connection validation: the missing connecting train can be the
only reason a valid route previously appeared unavailable.

### Refreshes must preserve already-passed stops

After a train has left the origin, a later trip update can omit the passed
stations. Progress refreshes update matching stop estimates but retain the
previously selected leg's passed stops and origin timing when the new feed no
longer contains them.

## Fixture JSON files

Each file below was generated from the corresponding protobuf with Python's
`gtfs-realtime-bindings` and `google.protobuf.json_format`. The protobuf files
remain unchanged in the test fixture directory.

- [bart_trip_updates.json](bart_trip_updates.json) — daytime fixture.
- [bart_trip_updates_night.json](bart_trip_updates_night.json) — night routing
  fixture.
- [bart_trip_updates_night_latest.json](bart_trip_updates_night_latest.json) —
  later night snapshot.
- [bart_trip_updates_12th_16th_now.json](bart_trip_updates_12th_16th_now.json)
  — fresh snapshot used by the 12th Street Oakland to 16th Street regression.
- [bart_trip_updates_current.json](bart_trip_updates_current.json) and
  [bart_alerts_current.json](bart_alerts_current.json) — paired live snapshot
  captured from BART at feed timestamp `1788911555` (2026-09-08 23:52:35 UTC).
  The matching protobuf fixtures are
  `app/src/test/resources/gtfsrt/bart_trip_updates_current.pb` and
  `app/src/test/resources/gtfsrt/bart_alerts_current.pb`.
- `bart_trip_updates_live_20260908_194746.pb` and
  `bart_alerts_live_20260908_194746.pb` — earlier paired live snapshot captured
  from BART at 2026-09-08 19:47:46 PDT for the Castro Valley to SFO regression.
- `bart_trip_updates_live_20260908_202339.pb` and
  `bart_alerts_live_20260908_202339.pb` — latest paired live snapshot captured
  from BART at 2026-09-08 20:23:39 PDT for the Castro Valley to SFO regression.
  The matching test fixtures are in
  `app/src/test/resources/gtfsrt/`.
- `bart_trip_updates_live_20260910_171711.pb` and
  `bart_alerts_live_20260910_171711.pb` — current paired live snapshot captured
  from BART at 2026-09-10 17:17:11 PDT. The trip feed contains 78 trip updates;
  all 1,043 stop-time updates omit `stop_sequence`, matching BART's current
  feed behavior. The matching test fixtures are in
  `app/src/test/resources/gtfsrt/`.

## Regression coverage

The routing tests cover:

- line endpoints, transfer points, and the Antioch terminal continuation;
- exact static/realtime/estimated epoch and provenance checks at Pittsburg and
  Antioch, based on the checked-in fixture JSON and static GTFS;
- incomplete terminal updates that stop before the static terminal;
- Ashby to Daly City routing;
- 12th Street Oakland to SFO and station-board display;
- 12th Street Oakland to 16th Street using the fresh snapshot plus static
  schedule fallback;
- late-night 12th Street Oakland to Millbrae through SFO;
- Pittsburg/Antioch DMU joining and transfer timing.

The exhaustive static route audit is intentionally manual because it expands
every station pair in both fixture networks. Run it explicitly with:

```text
./gradlew -DrunAllPairs=true :app:testDebugUnitTest --tests in.izyum.bart.transit.gtfs.LiveGtfsRoutingTest.fixtureProtobufsProduceValidRoutingForEveryStationPair
```

When adding a new fixture, test both the station-only board and at least one
destination query. A station board can look plausible while destination
routing still fails because the selected train does not have a complete leg to
the requested endpoint.
