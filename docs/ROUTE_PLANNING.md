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

`TripPlanner.routesFor(origin, destination, network)` selects routes in this
order:

- direct catalog-backed routes, one for each usable direction/pattern;
- preferred one- or two-transfer catalog routes when no direct route exists;
- terminal shuttle extensions for Pittsburg Center and Antioch;
- special late-night SFO-to-Millbrae routing when the destination is Millbrae.

Routes contain line sequences, transfer stations, direction, and the station
sequence for each leg. Route scoring prefers fewer transfers, with a few BART-
specific preferences for common East Bay/San Francisco trunk journeys.

Station-only queries use the longest useful static pattern for each line at the
origin. They are deliberately not destination-filtered, because the board must
show every applicable train terminal.

### 3. Combine static and realtime trips

`RouteDepartureProjection` creates a small synthetic GTFS-Realtime view of
active static trips near the feed timestamp. It includes trips from the
relevant route IDs only, normally looking 30 minutes backward and two hours
forward.

The synthetic entities are merged with the live trip-update index. If the
same `trip_id` appears in both sources, the live entity wins. Otherwise the
static entity fills the gap. This is important for cases such as the fresh
12th Street Oakland fixture: the realtime snapshot contained eastbound trains
but omitted an upcoming westbound train, while the static night schedule still
contained the valid 12th Street to 16th Street trip.

### 4. Parse each trip into an itinerary

`GtfsRealtimeContentHandler` parses the merged trip entities and:

1. resolves the route ID, falling back to the static trip catalog when the
   realtime descriptor omits it;
2. maps platform stop IDs to passenger stations;
3. overlays realtime times onto the static station sequence;
4. fills absent static stations with zero-valued estimates so the train's
   topology and terminal remain known;
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
times are used where present; missing values remain unknown rather than being
invented.

This prevents the classic off-by-one destination bug: the last realtime update
must not be treated as the train's terminal when the static trip continues past
that update. For example, a Daly City train can have its last published update
at Balboa Park while still being a Daly City train.

### Arrival and departure are separate fields

At a station, arrival and departure may have different delays and timestamps.
The parser uses departure when available and falls back to arrival when it is
not. For a station with no usable event, the static topology is retained but
the time remains zero.

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

BartRunner joins compatible updates using platform, direction, and a bounded
Pittsburg-to-Pittsburg Center travel-time window. The result is a single
complete route leg or a route with an explicit `YELLOW_DMU` terminal leg.

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

## Regression coverage

The routing tests cover:

- static route validity for every distinct station pair in the day and night
  fixture networks;
- incomplete terminal updates that stop before the static terminal;
- Ashby to Daly City routing;
- 12th Street Oakland to SFO and station-board display;
- 12th Street Oakland to 16th Street using the fresh snapshot plus static
  schedule fallback;
- late-night 12th Street Oakland to Millbrae through SFO;
- Pittsburg/Antioch DMU joining and transfer timing.

When adding a new fixture, test both the station-only board and at least one
destination query. A station board can look plausible while destination
routing still fails because the selected train does not have a complete leg to
the requested endpoint.
