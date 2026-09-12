# Legacy ETD corroboration

BART's GTFS-Realtime trip-update feed is useful for delays and stop-level
estimates, but it can omit scheduled trains that BART's operations system still
knows about. The legacy ETD API is the station-board ground truth for that
narrow gap. Antioch's terminal vehicle is published under a separate technical
trip ID; when its terminal stops can be matched to a scheduled Yellow trip,
those GTFS-RT predictions are joined to that trip directly.

## Request policy

The app does not fetch ETD boards as part of a normal realtime refresh. It
first builds the ordinary static-plus-GTFS-RT departure list. For a station
with any forward GTFS-RT departure, schedule-only departures inside the next
60 minutes are treated as cancelled by absence; beyond that window, the
schedule cutoff is specific to the normalized line and destination branch.
The ETD-aware projection temporarily retains the uncut candidates so ETD can
recover a real train that GTFS-RT omitted. A departure leg is **suspicious**
only when all of the following are true:

- the leg has a trip ID;
- its departure source is `SCHEDULE`;
- its scheduled departure from that leg's origin is between the feed time and
  60 minutes after the feed time.

The ETD-aware projection requests boards only for stations with suspicious
static candidates. A candidate is suspicious only when its trip ID is absent
from the GTFS-RT feed; schedule-timed stops on an otherwise present trip are
not enough by themselves. For a transfer itinerary, a transfer station is
queried only when the connecting leg itself is suspicious.

One station response covers every suspicious candidate at that station. A
response is cached in memory until its earliest reported departure. A response
with no usable departure gets a short retry lifetime. Concurrent requests for
the same station share the cache entry and do not issue duplicate requests.

## Matching policy

The ETD API does not expose GTFS trip IDs. Candidates are matched one-to-one by
station, train destination, BART line/color, and approximate departure time.
Realtime departures claim the closest available ETD slot first; one realtime
departure cannot claim multiple neighboring ETD trains. This matters when a
station reports trains one or two minutes apart.
The match uses absolute times derived from the ETD response's `minutes` field;
`Leaving` is treated as the current time.

An ETD response can suppress a suspicious candidate only when its returned
departure window reaches the candidate's scheduled time. If the API only lists
earlier trains, absence is inconclusive and the static candidate remains. A
successfully parsed empty station board is treated as no service during the
normal suspicious window; a request or parse failure remains unknown.

Explicit GTFS-RT `CANCELED` relationships remain the stronger cancellation
signal. ETD corroboration is represented as a cancellation-like suppression for
display and routing, but does not change the schedule model's explicit
`canceled` flag.

For a positive ETD match on a suspicious trip, the ETD departure time is used
as the operational departure time for routing and transfer validation. The
static scheduled time is retained as the trip's identity/time reference.
Ordinary GTFS-RT trips are not overwritten by ETD timing differences.

ETD does not synthesize general station departures. It only corroborates or
suppresses suspicious scheduled trips; normal station boards remain driven by
GTFS-RT plus the static schedule.

## Pipeline

```text
static schedule + GTFS-RT
        -> realtime coverage cutoff for schedule-only departures
        -> retain an uncut candidate set for ETD reconciliation
        -> identify schedule-sourced suspicious legs
        -> fetch/cache ETD boards for those leg origins only
        -> one-to-one ETD/realtime/schedule arbitration
        -> apply matched ETD times and suppress absent trips
```

ETD failures preserve realtime departures and future schedule service; they do
not re-enable schedule-only departures inside the realtime coverage cutoff.
Candidates outside the returned ETD window remain available as future schedule
fallbacks.
