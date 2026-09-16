# Audit analysis gap closure

This note maps the analysis findings in `AUDIT_GAPS.md` to the current audit
artifacts. Kotlin implementation findings are intentionally excluded.

| Reviewer finding | Disposition | Evidence |
|---|---|---|
| Complete source provenance | Addressed | `capture_components.csv` hashes every checked-in trip-update, alert, and ETD XML component; `static_sources.json` hashes every static ZIP and table. |
| Compare all static GTFS ZIPs | Addressed | `static_sources.json` contains every source, table counts/hashes, service-date coverage, selected source, and selection rule. |
| Explicit trip-association states | Addressed | `gtfsrt_associations.csv` contains exactly one state, confidence, method, evidence, candidates, and rejected candidates per GTFS-RT trip update. |
| Duplicate-update projection | Addressed | Raw entities remain in `gtfsrt_entities.csv`; `gtfsrt_projected_entities.csv` and `gtfsrt_duplicate_projection.csv` provide a deterministic consumer projection and audit trail. |
| Canonical service date | Addressed | `service_date`, `service_date_source`, and `service_date_conflict` are emitted on update and stop rows. GTFS-RT `start_date` wins; pre-03:00 capture fallback uses the prior service date. |
| Regression fixtures | Addressed | The test suite covers after-midnight times, date conflicts/fallbacks, static-source inventory, duplicate projection, protobuf/XML decoding, missing fields, DMU IDs, and PDF late-night Millbrae extraction. |
| SFO–Millbrae realtime coverage question | Unresolved, explicitly bounded | The official PDFs and static GTFS establish published service. Only one captured Yellow GTFS-RT Millbrae stop row exists, so realtime coverage for every late-night movement cannot be inferred. |
| Omission cancellation rate | Unresolved, explicitly bounded | Static-only snapshot rows remain `proven_absent=false`; a labeled consecutive-feed series is required to estimate false-positive/false-negative omission rates. |

The machine-readable audit remains conservative: an unresolved question is
represented as unresolved rather than converted into a normalization rule.
