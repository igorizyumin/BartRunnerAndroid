# GTFS-Realtime tooling

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
