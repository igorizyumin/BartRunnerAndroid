# GTFS-Realtime tooling

Capture a complete current fixture (trip updates, alerts, and raw XML ETD
boards for every app station):

```text
powershell -ExecutionPolicy Bypass -File tools/capture_bart_live_fixture.ps1
```

The capture script updates `app/src/test/resources/bart_live_fixture.txt` as a
convenience pointer. `LiveEtdStationBoardAuditTest` discovers all complete
`bart_live_*` captures and excludes only the explicitly listed disruption
snapshots in the test.

Install the small Python dependency once:

```text
python -m pip install gtfs-realtime-bindings
```

Inspect a captured BART trip-update feed:

```text
python tools/gtfsrt_inspect.py docs/bart_trip_updates_live_20260910_171711.pb
```

Check trips containing the Milpitas and Berryessa platform stop IDs:

```text
python tools/gtfsrt_inspect.py docs/bart_trip_updates_live_20260910_171711.pb --stop-prefix S40
python tools/gtfsrt_inspect.py docs/bart_trip_updates_live_20260910_171711.pb --stop-prefix S50
```

Inspect the paired alert feed or emit standard GTFS-RT JSON:

```text
python tools/gtfsrt_inspect.py docs/bart_alerts_live_20260910_171711.pb --alerts
python tools/gtfsrt_inspect.py docs/bart_trip_updates_live_20260910_171711.pb --json > trip_updates.json
```
