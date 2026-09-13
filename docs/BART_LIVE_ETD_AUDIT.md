# BART live ETD audit

The checked-in capture selected by
`app/src/test/resources/bart_live_fixture.txt` contains:

- BART trip-update GTFS-Realtime protobuf;
- BART alerts GTFS-Realtime protobuf;
- raw XML ETD boards for every one of the 49 passenger stations modeled by the
  app.

JSON decodes of the two protobuf feeds are checked in alongside the audit
notes: [trip updates](bart_trip_updates_live_20260912_071605.json) and
[alerts](bart_alerts_live_20260912_071605.json).

The current capture was taken on 2026-09-12 at approximately 7:16 AM PDT in
`app/src/test/resources/bart_live_20260912_071605/`. It includes an active bus
bridge: passengers between Union City and Warm Springs/South Fremont transfer
to a free bus, with 30–40 minute delays expected. The captured ETD boards show
Orange-line trains north from Union City toward Richmond and south from Warm
Springs toward Berryessa, which provides the train-side routing context for the
bridge.

This capture is intentionally a disruption snapshot. The station-board audit
still finds the expected ETD times and rows, but reports destination-label
mismatches for the temporary Berryessa/Richmond service because the checked-in
static trip patterns terminate at Union City/Warm Springs during the bridge.
That mismatch is the regression data for adding bus-bridge routing support.

`LiveEtdStationBoardAuditTest` builds the same static-plus-GTFS-RT station-board
projection used by the app, then compares each XML ETD row by line, predicted
time, destination label, and cancellation state. ETD returns only a short
upcoming-board window, while GTFS-RT contains a larger prediction horizon, so
additional app predictions outside the ETD rows are not treated as failures.
The comparison tolerance is two minutes to cover ETD's whole-minute values and
the small capture-time gap between requests.

Results from this capture:

- 49/49 station boards loaded;
- 456 ETD predictions compared;
- 456 predictions matched by line and time;
- 50 destination-label mismatches;
- 0 time mismatches;
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
departures on their own. Their terminal times may supplement an already
confirmed normal Yellow trip with matching direction, platform, and PITT
realtime evidence.

Run the audit with:

```text
$env:GRADLE_USER_HOME = (Resolve-Path .gradle-user).Path
.\gradlew.bat :app:testDebugUnitTest --tests in.izyum.bart.backend.LiveEtdStationBoardAuditTest
```

To capture a new snapshot, run
`tools/capture_bart_live_fixture.ps1`; it writes a new timestamped directory
and updates the fixture pointer used by the test.
