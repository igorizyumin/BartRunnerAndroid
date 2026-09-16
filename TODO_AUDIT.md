# Assignment: Empirical audit of BART static, GTFS-Realtime, and ETD data

## Objective

Build a reproducible Python-based audit of the BART data captured in this repository.

The audit must determine:

1. Which parts of the static GTFS schedule accurately describe BART’s actual passenger service.
2. What information GTFS-Realtime supplies, omits, or represents ambiguously.
3. Which apparent defects are caused by BART’s data, capture timing, or our interpretation/matching logic.
4. The smallest and most reliable mitigation for each confirmed data gap.

Do not modify the production Kotlin architecture as part of this assignment. The result should be an empirical report and a machine-readable evidence set that can guide the later correction.

## Input data

Discover and inventory all available captures under:

- \`app/src/test/resources/bart_live_*\`
- \`app/src/test/resources/gtfsrt\`
- \`app/src/test/resources/gtfs\`
- \`app/src/test/resources/etd\`
- readable JSON copies under \`docs/\`

A live capture may contain:

- \`trip_updates.pb\`
- \`alerts.pb\`
- \`etd/*.xml\`
- capture metadata or timestamps

The static GTFS ZIP files are the reference for published schedule structure, service calendars, routes, trips, and stop times.

The scripts must tolerate incomplete captures and report missing components explicitly.

## Required implementation

Create a small Python audit package, preferably under:

~~~text
tools/bart_data_audit/
~~~

Provide one reproducible entry point, for example:

~~~text
python -m tools.bart_data_audit audit
~~~

The implementation should use the raw protobuf and XML files as the authoritative inputs. Existing JSON decodes may be used for diagnostics, but the audit must not depend on hand-edited JSON.

The audit should produce:

- a capture manifest;
- normalized static-trip records;
- normalized GTFS-RT entity and stop-time records;
- normalized ETD prediction records;
- deterministic match records;
- summary CSV/JSON reports;
- a human-readable Markdown report.

Every reported match or mismatch must be traceable to its source fixture, station, entity, trip ID, and timestamp.

## Phase 1: Capture inventory

For every capture, report:

- capture directory;
- local capture timestamp;
- GTFS-RT feed timestamp;
- presence of trip updates, alerts, and ETD boards;
- number of GTFS-RT entities;
- number of trip updates;
- number of stop-time updates;
- number of ETD stations and predictions;
- static GTFS version used;
- request-time differences if metadata is available.

Flag captures that are unsuitable for strict comparison, including:

- missing ETD boards;
- missing GTFS-RT;
- known bus-bridge or major-disruption captures;
- large differences between ETD and GTFS-RT capture times.

Do not silently exclude such captures. Mark them and explain why.

## Phase 2: Static GTFS validation

Analyze the static schedule independently of realtime data.

Validate:

- service-date and calendar interpretation;
- after-midnight times such as \`24:xx:xx\`;
- route-to-line mapping;
- route direction;
- ordered station sequences;
- terminal stations;
- short-turn trips;
- duplicate trip IDs across service dates;
- platform stop IDs and passenger-station normalization;
- SFO–Millbrae shuttle representation;
- Pittsburg, Pittsburg Center, and Antioch patterns;
- scheduled stop-time consistency;
- impossible or contradictory stop sequences.

For every static trip, retain at least:

~~~text
service_date
trip_id
route_id
line
direction
ordered_stations
terminal_station
scheduled_origin_departure
scheduled_stop_times
~~~

Answer separately:

### Structural accuracy

Does static GTFS correctly describe:

- which stations a trip serves;
- the order of stations;
- its published terminal;
- its line and direction;
- whether it is a short turn?

### Timetable accuracy

How far do realtime predictions and ETD predictions deviate from static scheduled times?

Do not interpret a large delay as a static schedule error. Separate:

- normal operational delay;
- systematic schedule mismatch;
- trip pattern mismatch;
- wrong service-date interpretation.

## Phase 3: GTFS-Realtime characterization

For every GTFS-RT trip update, record:

- feed timestamp;
- entity ID;
- trip ID;
- route ID;
- schedule relationship;
- stop IDs;
- stop sequences, including missing sequences;
- arrival and departure event times;
- delay-only fields;
- platform suffixes;
- whether the trip ID maps to static GTFS;
- whether the route ID maps to a known line.

Measure:

- percentage of updates with missing route IDs;
- percentage with missing stop sequences;
- percentage with partial stop lists;
- percentage with explicit \`CANCELED\`;
- percentage whose trip IDs do not exist in static GTFS;
- percentage of numeric 600–799 entities;
- duplicate trip IDs within one feed;
- entities whose predicted origin time is already stale;
- updates that stop before the static terminal;
- updates that contain platform information but no passenger-station identity.

Analyze feed behavior across multiple captures, not just one snapshot:

- When does a trip first appear?
- Does it remain present in later snapshots?
- When does it disappear?
- Does disappearance correlate with ETD disappearance?
- Does disappearance correlate with explicit cancellation?
- Can a later snapshot replace an earlier entity for the same trip?
- Are updates monotonic, partial, or sometimes regressive?

## Phase 4: ETD normalization and matching

Parse each XML station board into normalized prediction rows:

~~~text
capture_id
station
line
destination_label
platform
direction
minutes
absolute_prediction_time
status
color
raw_source_path
~~~

Account explicitly for:

- whole-minute rounding;
- ETD request time versus GTFS-RT feed time;
- “Leaving” versus future predictions;
- platform and direction;
- short ETD look-ahead windows;
- destination labels that may not match static terminal names during disruptions.

ETD is an operational passenger-facing reference, not a perfect trip identity source. It generally does not expose GTFS trip IDs. Therefore, every ETD match must state its method and confidence.

Use matching keys in this order:

1. station;
2. line/color;
3. platform or direction when available;
4. destination label;
5. absolute time within an explicitly documented tolerance.

Do not allow one prediction to match multiple rows. Report ambiguous matches separately.

## Phase 5: Cross-source evidence analysis

For each capture, compare:

1. static schedule;
2. GTFS-RT predictions;
3. ETD predictions;
4. alerts and disruption context.

Classify every relevant service observation as one of:

- exact static/realtime match;
- static trip with realtime timing correction;
- realtime-only trip;
- static-only trip;
- ETD-only prediction;
- explicit realtime cancellation;
- likely omission;
- unmatched because of identity limitations;
- unmatched because of timing/capture skew;
- unmatched because of route or terminal semantics;
- ambiguous.

The report must distinguish “not observed” from “proven absent.”

## Required investigations

### A. Static schedule accuracy

Measure:

- static trips with corresponding GTFS-RT entities;
- static trips visible on ETD but absent from GTFS-RT;
- GTFS-RT trips with no static counterpart;
- terminal mismatches;
- short-turn mismatches;
- SFO–Millbrae representation;
- Pittsburg/Pittsburg Center/Antioch representation;
- systematic scheduled-time differences.

### B. GTFS-RT limitations

Specifically investigate:

- DMU/600–799 entities;
- whether DMU records contain a stable passenger-trip identity;
- whether DMU records can be matched consistently to electric trips;
- whether matches remain stable across consecutive captures;
- whether a DMU update appears without an electric partner;
- whether an electric trip exists only in static GTFS when DMU telemetry is present;
- missing route IDs;
- missing stop sequences;
- partial stop updates;
- terminal updates that stop before the published terminal;
- omission-based cancellations;
- duplicate or replaced entities;
- stale predictions;
- platform-specific stop IDs.

### C. SFO–Millbrae

Determine from actual static GTFS and captures:

- whether a dedicated shuttle trip exists in static GTFS;
- whether it appears in GTFS-RT;
- whether it appears in ETD;
- whether it has a stable identity;
- whether the current synthetic leg corresponds to observed service;
- whether the shuttle is a scheduled leg, transfer, operational continuation, or separate service.

Do not assume the current synthetic model is correct merely because it produces plausible departures.

### D. Omission-based cancellation

For static trips absent from GTFS-RT, compare later captures and ETD boards to determine:

- whether the trip eventually appears;
- whether it disappears from ETD;
- whether it is replaced by another prediction;
- whether an alert explains the absence;
- whether omission is a reliable cancellation signal within a particular time window.

Quantify false-positive and false-negative rates for any proposed omission rule.

## Phase 6: Mitigation recommendations

For every confirmed gap, recommend one of:

- no mitigation required;
- static GTFS should remain authoritative;
- exact GTFS-RT correction is sufficient;
- realtime-only service must be admitted;
- ETD corroboration is justified;
- a separate operational telemetry layer is required;
- a heuristic association is acceptable only with confidence/provenance;
- the case must remain unknown rather than being inferred;
- the static GTFS or station mapping needs correction.

Each recommendation must include:

~~~text
problem
evidence
confidence
affected consumers
proposed source of truth
proposed fallback
known failure mode
required regression fixtures
~~~

The analysis must not recommend silently treating ETD as a universal ground truth. ETD has no stable trip identity, is rounded to minutes, has a limited horizon, and may describe disruption service differently from static GTFS.

## Expected deliverables

1. Reproducible Python audit scripts.
2. Capture inventory report.
3. Normalized machine-readable evidence files.
4. Static schedule accuracy report.
5. GTFS-RT limitations report.
6. DMU-specific analysis.
7. SFO–Millbrae analysis.
8. Omission/cancellation analysis.
9. Cross-source match diagnostics.
10. Mitigation recommendation matrix.
11. Markdown report with representative examples.
12. Tests for parsers, normalizers, matchers, and known edge cases.

## Acceptance criteria

The assignment is complete only when:

- every checked-in capture is inventoried;
- incomplete or disruptive captures are explicitly flagged;
- every match is deterministic and explainable;
- unmatched rows are categorized rather than discarded;
- ETD rounding and capture-time skew are modeled;
- static-only, realtime-only, ETD-only, canceled, and omitted cases are distinct;
- DMU behavior is measured across multiple captures;
- SFO–Millbrae is verified against the actual static feed;
- the report separates observed facts from operational hypotheses;
- no production Kotlin code is changed;
- the resulting evidence is sufficient to define the next architectural correction.

