# Official Yellow-line PDF schedule comparison

The official BART weekday and Saturday/Sunday PDFs are optional regression fixtures for this report. They are not runtime normalization inputs. The PDFs were visually rendered before extraction; positioned glyph coordinates and the drawn table columns were used to create the machine-readable cells.

- Extracted schedule cells: **16993**; schedule rows: **670**.
- Yellow GTFS-RT comparisons: **2036**; Millbrae stop comparisons: **1**.
- Static/PDF mismatches: **1**; tolerated under the policy: **1**; policy exceeded: **False**.
- Late-night Millbrae comparisons: **1**.
- Late-night GTFS-RT minus PDF median: **-150 seconds**; maximum absolute deviation: **150 seconds**.
- All captured GTFS-RT Millbrae stop rows: **16**; Yellow subset: **1**. The remainder are Red/unknown service and are not used as Yellow evidence.

## Interpretation

Static GTFS remains the app-facing schedule source. PDF comparisons are regression evidence only. The policy allows up to two static/PDF mismatches per PDF when each is strictly under five minutes, accommodating a likely PDF-preparation typo; tolerated mismatches are ignored for normalization. A nonzero GTFS-RT minus PDF difference is classified as realtime operational timing deviation. Rows without a PDF candidate remain unmatched rather than being inferred.

The weekday PDF explicitly labels the outbound timetable `Antioch to SFO + Millbrae (Late Nights)` and the inbound timetable `Millbrae (Early AM & Late Night) + SFO to Antioch`. The timetable includes separate SFO arrival/departure columns and Millbrae rows after the late-night transition; the comparison preserves those columns and focuses Millbrae arrival times.

Status: **unresolved from the checked-in fixtures**. Only one captured Yellow GTFS-RT stop-time row reaches Millbrae. That is a limitation of the checked-in realtime snapshots, not evidence that the published Yellow schedule lacks Millbrae service; a paired late-night capture series is required to establish realtime coverage.

## Machine-readable outputs

- `yellow_pdf_schedule_cells.csv`: one source PDF cell per station/time, with page, row, direction, and source hash.
- `yellow_pdf_schedule_rows.csv`: one timetable row with the station/time map encoded as JSON.
- `yellow_pdf_gtfsrt_comparison.csv`: deterministic static-nearest PDF matching plus GTFS-RT timing deltas.
- `yellow_pdf_comparison_summary.json`: counts, mismatch policy status, and late-night statistics.

Source PDFs:
- `C:\Users\Igor\StudioProjects\BartRunnerAndroid\docs\bart_data_audit\schedule_pdfs\yellow_weekday.pdf` SHA-256 `0d109cfefdd96b42042b58d381e4619cb851957a542cc37b218e285902904060`
- `C:\Users\Igor\StudioProjects\BartRunnerAndroid\docs\bart_data_audit\schedule_pdfs\yellow_weekend.pdf` SHA-256 `54821a7189d6adfe56133e43b4e4d0701725598c80f1ffb1196ab0b83c86ad34`
