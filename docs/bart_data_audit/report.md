# BART data audit

Generated from raw checked-in GTFS ZIP, GTFS-Realtime protobuf, and ETD XML files.
JSON copies under `docs/` are inventoried as supplemental diagnostics only; they are not audit inputs.

## Executive findings

- Directory captures inventoried: **10**; supplemental raw/JSON sources inventoried separately: **28**.
- Raw GTFS-Realtime trip updates normalized: **483**; stop-time rows: **5613**.
- ETD predictions normalized: **2981** from **6** directory captures.
- Explicit realtime cancellations: **1** trip updates; static-only snapshot rows are not called cancellations.
- Explicit association states: {'exact': 408, 'operational_telemetry': 36, 'realtime_only': 7, 'required_counterpart_not_observed': 32}; see `gtfsrt_associations.csv`.
- Static GTFS should remain authoritative for published station order, line/direction, and terminal. Realtime is a timing/status overlay where a stable trip identity exists.
- ETD is corroborating passenger-facing evidence only: it is minute-rounded, horizon-limited, and has no GTFS trip identity.

## Capture inventory

| Capture | GTFS-RT | ETD stations | Alerts | Feed timestamp | Flags |
|---|---:|---:|---:|---|---|
| `bart_live_20260911_161720` | 82 trips / 1093 stops | 49 | 0 | 2026-09-11T23:17:20+00:00 | none |
| `bart_live_20260911_185819` | 82 trips / 964 stops | 49 | 2 | 2026-09-12T01:58:19+00:00 | none |
| `bart_live_20260912_071554` | 0 trips / 0 stops | 0 | 0 | missing | missing_gtfs_rt_trip_updates|missing_alerts|missing_etd_boards|incomplete_etd_station_set |
| `bart_live_20260912_071605` | 59 trips / 624 stops | 49 | 2 | 2026-09-12T14:16:05+00:00 | known_bus_bridge_or_major_disruption_context |
| `bart_live_20260912_192803` | 0 trips / 0 stops | 0 | 0 | missing | missing_gtfs_rt_trip_updates|missing_alerts|missing_etd_boards|incomplete_etd_station_set |
| `bart_live_20260912_192815` | 58 trips / 632 stops | 49 | 3 | 2026-09-13T02:28:14+00:00 | known_bus_bridge_or_major_disruption_context |
| `bart_live_20260913_182420` | 0 trips / 0 stops | 0 | 0 | missing | missing_gtfs_rt_trip_updates|missing_alerts|missing_etd_boards|incomplete_etd_station_set |
| `bart_live_20260913_182435` | 59 trips / 660 stops | 49 | 3 | 2026-09-14T01:24:34+00:00 | none |
| `bart_live_20260913_201652` | 59 trips / 584 stops | 49 | 3 | 2026-09-14T03:16:52+00:00 | none |
| `bart_live_terminals_20260911_183436` | 84 trips / 1056 stops | 0 | 2 | 2026-09-12T01:34:55+00:00 | missing_etd_boards|incomplete_etd_station_set |

Captures marked with disruption or missing components remain in all machine-readable outputs. They are not silently excluded from totals.

## Static GTFS validation

Static feed version: `72`, feed dates `20260112`–`20260830`. All **2** checked-in ZIPs were parsed and compared. Selected source: `C:\Users\Igor\StudioProjects\BartRunnerAndroid\app\src\test\resources\gtfs\bart_google_transit_night.zip`. Identical table content: **True**; see `static_sources.json` for hashes, counts, coverage, and the deterministic selection rule.
Active static trips across observed service dates: **3411**. Duplicate trip IDs in `trips.txt`: **0** by ID key (the normalized table preserves service-date rows).

The parser treats `24:xx:xx` as seconds after midnight rather than rolling it to the prior date. Each realtime update receives a canonical service date: GTFS-RT `start_date` when present, otherwise a documented local-capture fallback with the pre-03:00 post-midnight rule. Platform stop IDs normalize through `parent_station`, preserving the raw platform ID in stop-time records.
Mapped realtime timing observations: **5511**; median deviation from static scheduled time: **71s**; p90 absolute deviation: **302s**. These are operational timing corrections, not evidence that static schedule structure is wrong. See `static_realtime_timing.csv`.

Special topology checks are reported from actual rows in `static_validation.json`, `sfo_millbrae.json`, and `static_trips.csv`; no synthetic trip is introduced by this audit.

## GTFS-Realtime characterization

| Metric | Count |
|---|---:|
| trip updates | 483 (100.0%) |
| missing route IDs | 483 (100.0%) |
| stop rows missing sequences | 5613 |
| partial stop lists | 408 (84.5%) |
| explicit CANCELED updates | 1 (0.2%) |
| trip IDs absent from static | 75 (15.5%) |
| numeric 600–799 entity IDs | 68 (14.1%) |
| duplicate trip IDs within feed | 4 (0.8%) |
| stale origin predictions | 14 (2.9%) |
| updates ending before static terminal | 407 (84.3%) |
| platform-without-parent station | 0 |

A short stop list is recorded as an observation, not as proof of a short-turn: feeds may be partial. A static terminal mismatch is therefore a data-shape diagnostic until corroborated by later stops, ETD, or alert context.

## DMU / 600–799 analysis

The audit found **68** numeric 600–799 records across **7** captures. **68** have no route ID and **0** map directly to static trip IDs. Consecutive-capture identity observations are in `dmu_consecutive.csv`; shared IDs are evidence of persistence only, not passenger identity.

A DMU record without an electric/static counterpart remains operational telemetry. Because passengers must transfer north of PITT, a DMU observation is not modeled as an independent service: `gtfsrt_associations.csv` records whether a plausible electric counterpart was observed in the same snapshot. Missing counterpart evidence is `required_counterpart_not_observed`, not cancellation.

## SFO–Millbrae

Static trips serving both SFO/SFIA and Millbrae: **344**; of these, Red route 7/8 direct rows: **246**, Yellow route 1/2 rows: **98**. GTFS-RT records touching SFO or Millbrae: **55**. ETD rows labeled Millbrae/SFO-Millbrae: **528**.

Status: **unresolved from the checked-in fixtures**. The static schedule and PDF establish published Yellow/Red patterns, but the captures contain only one Yellow GTFS-RT Millbrae stop row. That is insufficient to conclude whether every late-night Yellow movement has a realtime counterpart; the absence of more rows is not negative operational evidence.

## Omission and cancellation

Omission is not treated as cancellation by this audit. The checked-in captures are snapshots, not a complete service-history stream; a false-positive/false-negative omission rate is therefore not estimable without labeled consecutive observations.

The audit distinguishes `explicit CANCELED`, `static_only_in_snapshot`, and unmatched ETD/GTFS-RT rows. The omission rate remains unresolved because there is no labeled consecutive-feed series proving that a static-only trip was actually canceled or simply absent from a partial snapshot.

## Cross-source matching

ETD matching uses station, color-derived line, platform/direction where available, destination alias, and absolute time within 180 seconds. Greedy ordering is deterministic and each ETD/GTFS-RT row can be used once. `ambiguous` and unmatched rows remain in `matches.csv` with reasons. Duplicate GTFS-RT updates are retained raw and projected deterministically in `gtfsrt_projected_entities.csv` with decisions in `gtfsrt_duplicate_projection.csv`.

## Mitigation recommendation matrix

| Problem | Confidence | Proposed source of truth | Fallback |
|---|---|---|---|
| Static GTFS is the only stable trip-pattern identity for scheduled service | high | static GTFS for pattern, line, direction, and published terminal | apply realtime timing/cancellation relationship only when trip_id maps |
| Numeric 600–799 / DMU telemetry lacks a consistently joinable passenger identity | medium | separate operational telemetry layer | heuristic association only with station, direction, and time provenance |
| ETD has no stable trip identity and is minute-rounded | high | GTFS-RT/static when identity exists | ETD corroboration only for unmatched operational observations |
| Omission-based cancellation is not proven by these snapshots | high | explicit CANCELED relationship or alert | retain static service as unknown/possibly omitted within a bounded window |
| SFO–Millbrae service semantics require source-specific evidence | medium | actual static trip pattern where present; otherwise operational shuttle evidence | keep a provenance-marked transfer/estimate, never a universal ETD truth |
| Missing route IDs and partial stop lists make route/terminal inference ambiguous | high | static trip ID mapping, then station/platform evidence | classify as unknown rather than extending a trip to its published terminal |

Full machine-readable evidence is in this directory. Each normalized row contains capture/source provenance; static/realtime rows additionally retain entity, trip, station, and timestamp fields.
