# BART live ETD audit

The checked-in capture selected by
`app/src/test/resources/bart_live_fixture.txt` contains:

- BART trip-update GTFS-Realtime protobuf;
- BART alerts GTFS-Realtime protobuf;
- raw XML ETD boards for every one of the 49 passenger stations modeled by the
  app.

The current capture was taken on 2026-09-11 at approximately 4:17 PM PDT in
`app/src/test/resources/bart_live_20260911_161720/`.

`LiveEtdStationBoardAuditTest` builds the same static-plus-GTFS-RT station-board
projection used by the app, then compares each XML ETD row by line, predicted
time, destination label, and cancellation state. ETD returns only a short
upcoming-board window, while GTFS-RT contains a larger prediction horizon, so
additional app predictions outside the ETD rows are not treated as failures.
The comparison tolerance is two minutes to cover ETD's whole-minute values and
the small capture-time gap between requests.

Results from this capture:

- 49/49 station boards loaded;
- 592 ETD predictions compared;
- 590 predictions matched by line and time;
- 2 time mismatches;
- 0 destination-label mismatches;
- 0 missing predictions and 0 cancellation mismatches.

## Mismatch diagnosis

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
