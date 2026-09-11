# Legacy ETD corroboration

BART's GTFS-Realtime trip-update feed is useful for delays and stop-level
estimates, but it can omit scheduled trains that BART's operations system still
knows about. The legacy ETD API is the source displayed at stations and is used
as a corroborating source for that narrow case.

## Request policy

The app does not fetch ETD boards as part of a normal realtime refresh. It
first builds the ordinary static-plus-GTFS-RT departure list. A departure leg is
**suspicious** only when all of the following are true:

- the leg has a trip ID;
- its trip ID is absent from the current GTFS-RT trip-update index;
- its scheduled departure from that leg's origin is between the feed time and
  60 minutes after the feed time.

Only then does the app request ETD data, and only for the distinct station
origins of suspicious legs. For a transfer itinerary, a transfer station is
queried only when the connecting leg itself is suspicious.

One station response covers every suspicious candidate at that station. A
response is cached in memory until its earliest reported departure. A response
with no usable departure gets a short retry lifetime. Concurrent requests for
the same station share the cache entry and do not issue duplicate requests.

## Matching policy

The ETD API does not expose GTFS trip IDs. Candidates are matched one-to-one by
station, train destination, BART line/color, and approximate departure time.
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

For a positive ETD match, the ETD departure time is used as the operational
departure time for routing and transfer validation. The static scheduled time
is retained as the trip's identity/time reference. Downstream predicted times
are shifted by the same amount, preserving the scheduled running time. This
means a neighboring ETD train can still be useful: the goal is to determine
whether the rider can make the connection, not to prove the exact GTFS trip
identity.

## Pipeline

```text
static schedule + GTFS-RT
        -> ordinary candidate departures
        -> identify suspicious missing-trip legs
        -> fetch/cache ETD boards for those leg origins only
        -> pure ETD candidate matcher
        -> apply matched ETD times and suppress only corroborated missing trips
```

ETD failures, empty responses, stale responses, and candidates outside the
returned ETD window preserve the existing static fallback behavior.
