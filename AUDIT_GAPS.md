# Audit gaps and follow-up work

This document records the remaining gaps after reviewing
[BART_DATA_NORMALIZATION_GUIDE.md](docs/BART_DATA_NORMALIZATION_GUIDE.md) and
rerunning the Python audit.

The empirical fixture audit is substantially complete. These are the remaining
evidence, tooling, test, and implementation gaps.

## Status summary

| Area | Status |
|---|---|
| Raw fixture inventory | Complete |
| Static GTFS validation | Complete for the selected static feed |
| GTFS-Realtime normalization | Complete for raw observation output |
| ETD normalization and matching | Complete for the current fixtures |
| DMU evidence analysis | Complete as an empirical analysis |
| SFO–Millbrae evidence analysis | Substantially complete; late-night realtime identity remains open |
| Omission/cancellation analysis | Correctly bounded, but not statistically resolved |
| Provenance completeness | Gap |
| Duplicate-update projection | Gap |
| Explicit trip-association model | Gap |
| Regression-fixture coverage | Gap |
| Production normalization architecture | Not implemented |

## Gap 1: Complete source provenance

### Evidence

The guide requires capture records to retain source paths, capture timestamps,
feed/request timestamps, static feed version, component presence, and source
SHA-256 hashes.

The current capture_manifest.csv contains paths, timestamps, flags, and counts,
but does not contain hashes for every capture component. Hashes are present for
some supplemental sources and the official PDFs.

### Closure task

Extend the audit manifest to include hashes for:

- every trip_updates.pb;
- every alerts.pb;
- every ETD XML file;
- every static GTFS ZIP used;
- any supplemental JSON or protobuf source.

Record component hashes in a stable machine-readable form. Regenerate the
manifest and report.

### Acceptance criteria

- Every raw input has a SHA-256 value.
- A changed fixture produces a visibly changed provenance record.
- The report identifies the exact static GTFS ZIP selected for analysis.
- Tests cover hash generation and stable repeated results.

## Gap 2: Analyze all static GTFS ZIPs explicitly

### Evidence

The audit discovers both checked-in GTFS ZIPs and records them in
static_sources.json, but the analysis uses only the first ZIP.

### Closure task

Compare all available static GTFS ZIPs and report:

- feed version;
- feed date range;
- route/trip/stops counts;
- differences in route patterns;
- differences in Yellow SFO/Millbrae patterns;
- differences in Pittsburg/Pittsburg Center/Antioch patterns;
- differences in service calendars and exceptions.

If one feed is intentionally selected, state the selection rule and why it is
appropriate for each capture.

### Acceptance criteria

- No static ZIP is merely inventoried and ignored without explanation.
- The report distinguishes source-version differences from operational
  differences.
- Tests cover selection of the correct static source by capture date.

## Gap 3: Materialize explicit trip-association states

### Evidence

The guide recommends:

~~~text
association_status = exact | heuristic | unknown | rejected
association_confidence = high | medium | low
association_method
association_evidence
~~~

The current audit records whether a trip has a static counterpart, but does not
produce a complete association object with these fields.

### Closure task

Add an association output for every GTFS-RT trip update.

At minimum, support:

- exact;
- realtime_only;
- operational_telemetry;
- heuristic;
- unknown;
- rejected;
- no_static_counterpart;
- required_counterpart_not_observed.

Retain all candidate evidence and rejected candidates. Do not invent a passenger
trip ID for an unknown or DMU record.

### Acceptance criteria

- Every realtime entity has exactly one explicit association status.
- Numeric 600–799 entities are never labeled as exact static-trip matches.
- Association confidence and method are present in machine-readable output.
- A reviewer can explain every heuristic or rejected association from the output.

## Gap 4: Implement deterministic duplicate-update projection

### Evidence

The audit counts duplicate trip IDs and preserves raw rows, but does not yet
apply the guide’s recommended projection policy.

### Closure task

Implement a deterministic consumer projection:

1. preserve explicit cancellation over non-canceled duplicates;
2. otherwise prefer the newest feed timestamp;
3. use stable entity/source ordering as a tie-break;
4. record selected and discarded entity IDs;
5. retain all raw entities separately.

Do not deduplicate solely by numeric entity ID across captures.

### Acceptance criteria

- Duplicate selection is deterministic across repeated runs.
- Every discarded duplicate has a selection reason.
- Explicit cancellation wins when the same trip has conflicting duplicates.
- Tests cover equal timestamps, conflicting cancellation, and multiple entities.

## Gap 5: Strengthen service-date association

### Evidence

The normalized GTFS-RT output records start_date, while static association is
currently based primarily on the capture-directory date.

The guide requires association by (trip_id, service_date) and warns against
using the wall-clock capture date alone.

### Closure task

Normalize a canonical service date for every trip update using:

1. GTFS-RT start_date when supplied;
2. otherwise a documented capture/service-date rule;
3. explicit handling for after-midnight service.

Use (trip_id, service_date) for exact association and report conflicts between
start_date, capture date, and active static service.

### Acceptance criteria

- Every trip observation has an explicit normalized service date.
- After-midnight and previous-service-day cases are tested.
- Conflicting service-date evidence is reported rather than silently resolved.
- Static association cannot accidentally use the wrong day’s trip.

## Gap 6: Complete regression fixtures for normalization rules

### Evidence

The current Python tests cover core parsing and PDF behavior, and the Android
tests cover several realtime/routing cases. The guide’s full fixture checklist
is not yet represented end-to-end.

### Closure task

Add focused tests or fixture assertions for:

- 24:xx:xx service times;
- calendar exceptions;
- platform stop IDs and parent stations;
- missing route IDs;
- missing stop sequences;
- partial stop lists;
- explicit cancellation versus omission;
- duplicate updates;
- stale origin predictions;
- DMU with an electric candidate;
- DMU without an electric candidate;
- required counterpart absent from the snapshot;
- Pittsburg/Pittsburg Center/Antioch topology;
- Yellow trips reaching SFO and Millbrae;
- late-night PDF rows;
- SFO arrival/departure columns;
- ETD Leaving;
- ETD cancellation;
- ETD dynamic rows;
- ETD rounding and request/feed skew;
- one-to-one and ambiguous ETD matching.

### Acceptance criteria

Every item above has either:

- a passing automated test; or
- an explicitly documented reason why the fixture cannot establish the claim.

## Gap 7: Resolve the remaining SFO–Millbrae operational question

### Evidence

Static GTFS contains Red SFO–Millbrae patterns and Yellow patterns containing
both SFO and Millbrae.

The PDF comparison confirms late-night Yellow schedule rows. However, only one
captured Yellow GTFS-RT stop row reaches Millbrae, so the realtime identity and
operational continuation behavior remain weakly observed.

### Closure task

Use additional paired captures, if available, to determine:

- whether late-night Yellow service appears as one through trip;
- whether SFO–Millbrae is exposed as a separate operational movement;
- whether the realtime feed provides a stable identity;
- whether ETD labels describe a train, transfer, or destination projection;
- when a synthetic/transfer leg is justified.

### Acceptance criteria

- The conclusion is supported by multiple captures or explicitly labeled as
  unresolved.
- Static through-service and operational shuttle cases are distinguished.
- No production synthetic-leg change is made solely from one observation.

## Gap 8: Establish labeled evidence for omission-based cancellation

### Evidence

The current snapshots cannot estimate omission false-positive or false-negative
rates. The audit correctly leaves omission separate from cancellation.

### Closure task

Capture or assemble consecutive, labeled feed sequences containing:

- a trip that appears, disappears, and later reappears;
- a trip that is explicitly canceled;
- a trip that disappears and is absent from ETD;
- a trip that is absent from GTFS-RT but present in ETD;
- feed refreshes close to the scheduled departure time.

Measure which signals distinguish:

- unpublished future service;
- operational cancellation;
- stale/partial feed behavior;
- capture-window artifacts.

### Acceptance criteria

- Any omission rule has measured error characteristics.
- The report states the observation window and labeling method.
- If the data remains insufficient, the result stays explicitly unknown rather
  than becoming a hard cancellation rule.

## Gap 9: Implement the operational telemetry model in production

### Evidence

The guide recommends distinct concepts:

~~~text
PassengerTripUpdate
OperationalTelemetryUpdate
RequiredTransferPair
~~~

The current Android code still handles DMU enrichment inside the realtime
projection path and does not expose a shared canonical telemetry relationship
to every consumer.

### Closure task

Design and implement a shared normalization boundary that:

- preserves raw passenger-trip updates;
- preserves raw DMU/operational telemetry;
- represents required electric/DMU service pairing separately from trip identity;
- can represent an observed record with an unobserved required counterpart;
- carries confidence and provenance;
- feeds routing, departures, ETD corroboration, and trip following consistently.

This task should begin only after the evidence gaps above are resolved or
explicitly accepted.

### Acceptance criteria

- No consumer independently reconstructs DMU identity.
- No 600–799 ID is treated as an exact passenger trip ID.
- Static schedule, realtime, ETD, and telemetry provenance remain distinguishable.
- Multi-snapshot tests cover the same normalized service across all consumers.

## Gap 10: Preserve uncertainty through consumer projections

### Evidence

The guide requires schedule-only, unknown, heuristic, telemetry, and explicit
cancellation states to remain visible. The existing Departure model tends to
flatten these distinctions into timing, terminal, platform, and cancellation
fields.

### Closure task

Review the consumer-facing domain model and separate:

- stable service identity;
- published trip pattern;
- realtime timing;
- operational telemetry;
- ETD corroboration;
- explicit cancellation;
- inferred or unknown status;
- mutable display metadata.

Do not make platform, terminal, or other mutable display fields part of stable
identity unless there is a documented reason.

### Acceptance criteria

- A platform or terminal correction does not duplicate or strand a service.
- An unknown or schedule-only departure is not silently labeled canceled.
- Incoming updates cannot leave top-level metadata inconsistent with trip legs.
- Departure-board and trip-following projections consume the same normalized
  service record.

## Recommended order

1. Complete source hashes and static-source comparison.
2. Add canonical service-date and association outputs.
3. Add deterministic duplicate selection.
4. Add the remaining normalization regression fixtures.
5. Resolve or explicitly bound the SFO–Millbrae and omission questions.
6. Only then implement the production telemetry and consumer model changes.

## Verification commands

From the repository root:

~~~text
python -m unittest tools.bart_data_audit.test_audit
python -m tools.bart_data_audit audit
python -m tools.bart_data_audit pdf-compare
~~~

The Android production architecture should not be considered corrected merely
because these commands pass. They verify the evidence and normalization audit,
not the later consumer-model redesign.
