# BART live ETD audit

> Historical fixture audit only. ETD is not a runtime input; production
> projections now use `CanonicalTransitSnapshot` and GTFS-Realtime data.

The checked-in captures under `app/src/test/resources/bart_live_*` contain:

- BART trip-update GTFS-Realtime protobuf;
- BART alerts GTFS-Realtime protobuf;
- raw XML ETD boards for every one of the 49 passenger stations modeled by the
  app.

JSON decodes of the two protobuf feeds are checked in alongside the audit
notes: [trip updates](bart_trip_updates_live_20260912_071605.json) and
[alerts](bart_alerts_live_20260912_071605.json).

The two captures from 2026-09-12 at approximately 7:16 AM and 7:28 PM PDT
(`bart_live_20260912_071605` and `bart_live_20260912_192815`) include an active
bus bridge: passengers between Union City and Warm Springs/South Fremont
transfer to a free bus, with 30–40 minute delays expected. The captured ETD
boards show Orange-line trains north from Union City toward Richmond and south
from Warm Springs toward Berryessa, which provides the train-side routing
context for the bridge.

Those two captures are intentionally disruption snapshots. The station-board
audit finds the expected ETD times and rows, but reports destination-label
mismatches for the temporary Berryessa/Richmond service because the checked-in
static trip patterns terminate at Union City/Warm Springs during the bridge.
They are explicitly excluded from the strict destination-label assertions;
the remaining complete captures are still audited.

`LiveEtdStationBoardAuditTest` discovers every complete capture, excluding only
the two named bus-bridge snapshots above. It builds the same static-plus-GTFS-RT
station-board projection used by the app, then compares each XML ETD row by
line, predicted time, destination label, and cancellation state. ETD returns only a short
upcoming-board window, while GTFS-RT contains a larger prediction horizon, so
additional app predictions outside the ETD rows are not treated as failures.
The comparison tolerance is two minutes to cover ETD's whole-minute values and
the small capture-time gap between requests.

The non-disruption captures currently used by the audit produce:

- 49/49 station boards loaded;
- 1,166 ETD predictions compared across two captures;
- 1,159 predictions matched by line and time;
- 0 destination-label mismatches;
- 7 timing mismatches, all retained as diagnostics for capture-time/staleness
  edge cases;
- 0 missing predictions and 0 cancellation mismatches.

## Current-capture mismatch diagnosis

The 50 destination-label mismatches are concentrated in the temporary Orange
service around the bridge. ETD reports Berryessa- and Richmond-bound service,
while the checked-in static trip patterns end at Union City and Warm Springs.
The feed therefore supplies enough timing and line data to reproduce the
station boards, but not yet enough route semantics to model the free-bus leg.

## Earlier-capture mismatch diagnosis

The original audit over-counted mismatches for two harness reasons. XML ETD
stores `color` on each `estimate`, not on the surrounding destination group,
and ETD rounds to whole minutes. The test now reads each estimate's color and
uses platform/direction to disambiguate trains that round to the same minute.

The two remaining timing mismatches are both `Leaving` rows captured at
16:17:17 PDT:

- Pittsburg/Bay Point: GTFS-RT reports the same SFO-bound train at 16:14:48;
- South San Francisco: GTFS-RT reports the same Millbrae-bound train at
  16:15:04.

The app's 45-second stale-departure filter therefore drops both GTFS-RT rows,
while ETD has a fresher operational value.

The ETD destination label is treated as the passenger-facing ground truth. The
static schedule now preserves the `196530x` Yellow trips as Pittsburg/Bay Point
short turns, matching their static GTFS stop sequences. True Antioch trips
remain represented by the explicit static PITT → PCTR → ANTC patterns. The
technical DMU GTFS-RT entities are classified and cannot create passenger
departures on their own. This audit predates the canonical snapshot migration;
the current runtime performs the association and enrichment in
`CanonicalTransitSnapshot`, and no handler-only correction or
`getCorrectedSchedule()` convenience API remains. See [the schedule/realtime
merge audit](SCHEDULE_REALTIME_AUDIT.md) for the historical boundary.

BART also commonly represents an operational cancellation by omitting the
trip's GTFS-RT data instead of sending an explicit `CANCELED` message. The
app's one-hour rule therefore treats a currently-in-progress schedule trip
with no forward realtime evidence as *likely* cancelled; it does not prove
cancellation or set the schedule trip's `canceled` flag. The legacy ETD API is
currently used as corroborating evidence for that suppression, but the audit
has not established that the extra ETD check is strictly necessary.

The same identity limitation may apply to the SFO–Millbrae shuttle leg: it may
not appear as a separately identifiable GTFS-RT trip even if it is represented
in static Yellow patterns. The current schedule code handles the observed
late-night SFO-to-Millbrae gap with a synthetic leg based on static timing and
nominal runtime; whether a dedicated shuttle exists in static GTFS remains an
open verification item.

Run the audit with:

```text
$env:GRADLE_USER_HOME = (Resolve-Path .gradle-user).Path
.\gradlew.bat :app:testDebugUnitTest --tests in.izyum.bart.backend.LiveEtdStationBoardAuditTest
```

To capture a new snapshot, run
`tools/capture_bart_live_fixture.ps1`; it writes a new timestamped directory
and updates the fixture pointer used by the test.
