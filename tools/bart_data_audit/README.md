# BART data audit

Install the protobuf reader once:

```text
python -m pip install gtfs-realtime-bindings
```

Run the reproducible audit from the repository root:

```text
python -m tools.bart_data_audit audit
```

With the official Yellow-line PDFs downloaded under
`docs/bart_data_audit/schedule_pdfs/`, extract their timetable cells and compare
Yellow GTFS-Realtime predictions:

```text
python -m tools.bart_data_audit pdf-compare
```

This writes `yellow_pdf_*` evidence files and `yellow_pdf_report.md`.  The PDF
tables are parsed from their drawn column geometry and positioned glyphs; they
are not reconstructed from a hand-edited text copy.

Outputs are written to `docs/bart_data_audit/`.  The audit reads raw GTFS ZIP,
GTFS-Realtime protobuf, and ETD XML fixtures.  Existing JSON decodes are
inventoried as supplemental evidence but are not used for comparisons.

The audit compares every static ZIP before applying its deterministic
service-date coverage/version/path selection rule. It preserves raw realtime
entities, emits explicit association states in `gtfsrt_associations.csv`, and
emits a deterministic one-row-per-trip projection with its decisions. Raw
component hashes are in `capture_components.csv`; static ZIP and per-table
hashes are in `static_sources.json`.

Run parser/matcher tests without touching the Android source tree:

```text
python -m unittest tools.bart_data_audit.test_audit
```
