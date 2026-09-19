"""Extract the official Yellow-line PDF timetables and compare GTFS-RT.

The PDFs use positioned glyphs rather than ordinary text lines.  Extraction is
therefore based on the table's drawn vertical boundaries and glyph coordinates,
not on a hand-copied text dump.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import statistics
from collections import Counter, defaultdict
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

import pdfplumber

from .audit import PACIFIC, ROOT, build_audit, iso, local_schedule_epoch, parse_capture_time, service_capture_date, static_trip, time_seconds


PDF_DIR = ROOT / "docs" / "bart_data_audit" / "schedule_pdfs"
OUTPUT_DIR = ROOT / "docs" / "bart_data_audit"
TIME_PATTERN = re.compile(r"^\d{1,2}:\d{2} [AP]M$")

OUTBOUND_STATIONS = ["ANTC", "PCTR", "PITT", "NCON", "CONC", "PHIL", "WCRK", "LAFY", "ORIN", "ROCK", "MCAR", "19TH", "12TH", "WOAK", "EMBR", "MONT", "POWL", "CIVC", "16TH", "24TH", "GLEN", "BALB", "DALY", "COLM", "SSAN", "SBRN", "SFIA_ARR", "SFIA_DEP", "MLBR"]
INBOUND_STATIONS = ["MLBR", "SFIA_ARR", "SFIA_DEP", "SBRN", "SSAN", "COLM", "DALY", "BALB", "GLEN", "24TH", "16TH", "CIVC", "POWL", "MONT", "EMBR", "WOAK", "12TH", "19TH", "MCAR", "ROCK", "ORIN", "LAFY", "WCRK", "PHIL", "CONC", "NCON", "PITT", "PCTR", "ANTC"]


def pdf_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def parse_clock(value: str) -> int | None:
    match = re.match(r"^(\d{1,2}):(\d{2}) ([AP]M)$", value or "")
    if not match:
        return None
    hour, minute = int(match.group(1)), int(match.group(2))
    if match.group(3) == "AM":
        hour = 0 if hour == 12 else hour
    else:
        hour = 12 if hour == 12 else hour + 12
    seconds = hour * 3600 + minute * 60
    # The timetable runs through 1:xx AM after the late-night 11:xx PM rows.
    return seconds + (24 * 3600 if seconds < 3 * 3600 else 0)


def table_boundaries(page: Any) -> list[float]:
    boundaries = sorted({round(line["x0"], 2) for line in page.lines if line["top"] < 60 and line["bottom"] > 30 and abs(line["x0"] - line["x1"]) < 0.1})
    if len(boundaries) < 30:
        raise ValueError(f"could not recover 29 PDF schedule columns; found {len(boundaries) - 1}")
    return boundaries


def extract_page(path: Path, page_number: int, service_kind: str, direction: str) -> list[dict[str, Any]]:
    station_columns = OUTBOUND_STATIONS if direction == "S" else INBOUND_STATIONS
    with pdfplumber.open(path) as pdf:
        page = pdf.pages[page_number - 1]
        boundaries = table_boundaries(page)
        glyph_rows: dict[float, list[dict[str, Any]]] = defaultdict(list)
        for char in page.chars:
            if char["top"] < 63 or char["top"] > 700 or char["text"] not in "0123456789: AMP":
                continue
            glyph_rows[round(char["top"], 1)].append(char)
        result = []
        for y, chars in sorted(glyph_rows.items()):
            values = []
            for left, right in zip(boundaries, boundaries[1:]):
                text_value = "".join(c["text"] for c in sorted((c for c in chars if left - 0.1 <= c["x0"] < right + 0.1), key=lambda c: c["x0"]))
                text_value = re.sub(r"\s+", " ", text_value).strip()
                values.append(text_value if TIME_PATTERN.fullmatch(text_value) else "")
            if sum(bool(value) for value in values) < 2:
                continue
            row_id = len(result) + 1
            cells = []
            for column, value in zip(station_columns, values):
                if not value:
                    continue
                cells.append({"pdf_path": str(path), "pdf_sha256": pdf_sha256(path), "page": page_number, "service_kind": service_kind,
                              "direction": direction, "row_id": row_id, "row_y": y, "station": column, "time_text": value,
                              "seconds_after_midnight": parse_clock(value)})
            result.extend(cells)
        return result


def extract_schedule() -> tuple[list[dict[str, Any]], dict[str, Any]]:
    files = [("weekday", "yellow_weekday.pdf"), ("weekend", "yellow_weekend.pdf")]
    cells: list[dict[str, Any]] = []
    metadata = {"source_urls": {
        "weekday": "https://www.bart.gov/sites/default/files/2026-07/August%2010%2C%20%202026%20%20WDAY%20Service%20for%20Antioch_SFO%20%28Yellow%29%20Line.pdf",
        "weekend": "https://www.bart.gov/sites/default/files/2026-07/August%2010%2C%20%202026%20%20Sat_Sun%20Service%20for%20Antioch%20to%20SFO%20%28Yellow%29%20Line.pdf",
    }, "pdfs": []}
    for service_kind, filename in files:
        path = PDF_DIR / filename
        if not path.exists():
            raise FileNotFoundError(f"missing official schedule PDF: {path}")
        with pdfplumber.open(path) as pdf:
            metadata["pdfs"].append({"service_kind": service_kind, "path": str(path), "sha256": pdf_sha256(path), "pages": len(pdf.pages)})
        cells.extend(extract_page(path, 1, service_kind, "S"))
        cells.extend(extract_page(path, 2, service_kind, "N"))
    return cells, metadata


def schedule_rows(cells: list[dict[str, Any]]) -> list[dict[str, Any]]:
    grouped: dict[tuple[str, int, str, int], list[dict[str, Any]]] = defaultdict(list)
    for cell in cells:
        grouped[(cell["service_kind"], cell["page"], cell["direction"], cell["row_id"])].append(cell)
    rows = []
    for (service_kind, page, direction, row_id), group in sorted(grouped.items()):
        by_station = {c["station"]: c for c in group}
        first = min(c["seconds_after_midnight"] for c in group)
        rows.append({"service_kind": service_kind, "page": page, "direction": direction, "row_id": row_id,
                     "origin_station": group[0]["station"], "first_time_seconds": first,
                     "late_night": first >= 21 * 3600 or first >= 24 * 3600,
                     "times": by_station})
    return rows


def comparison_rows(audit: dict[str, Any], pdf_rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    by_kind_direction_station: dict[tuple[str, str, str], list[tuple[float, dict[str, Any]]]] = defaultdict(list)
    for row in pdf_rows:
        for station, cell in row["times"].items():
            key = (row["service_kind"], row["direction"], station)
            by_kind_direction_station[key].append((cell["seconds_after_midnight"], row))
    result = []
    static_cache: dict[tuple[str, str], dict[str, Any] | None] = {}
    stops_by_entity = defaultdict(list)
    for stop in audit["stop_rows"]:
        stops_by_entity[(stop["capture_id"], stop["entity_id"])].append(stop)
    for entity in audit["entities"]:
        service_date = service_capture_date(entity["capture_id"])
        if not service_date:
            continue
        kind = "weekday" if service_date.weekday() < 5 else "weekend"
        key = (entity["capture_id"], entity["trip_id"])
        if key not in static_cache:
            static_cache[key] = static_trip(audit["static"], entity["trip_id"], service_date)
        static = static_cache[key]
        if not static or static["line"] != "Yellow":
            continue
        for stop in stops_by_entity[(entity["capture_id"], entity["entity_id"])]:
            if stop["station"] not in set(OUTBOUND_STATIONS + INBOUND_STATIONS) - {"SFIA_ARR", "SFIA_DEP"}:
                continue
            station = stop["station"]
            pdf_station = (("SFIA_DEP" if static["direction"] == "N" else "SFIA_ARR") if station == "SFIA" else station)
            candidates = by_kind_direction_station[(kind, static["direction"], pdf_station)]
            static_stop = next((s for s in static["scheduled_stop_times"] if s["station"] == station), None)
            static_epoch = local_schedule_epoch(service_date.isoformat(), (static_stop or {}).get("departure_time", "") or (static_stop or {}).get("arrival_time", "")) if static_stop else None
            static_seconds = ((static_epoch - datetime.fromisoformat(service_date.isoformat()).replace(tzinfo=PACIFIC).timestamp()) if static_epoch else None)
            selected = min(candidates, key=lambda item: abs(item[0] - static_seconds)) if candidates and static_seconds is not None else None
            observed_epoch = datetime.fromisoformat(stop["event_time"]).timestamp() if stop["event_time"] else None
            pdf_epoch = (datetime.fromisoformat(service_date.isoformat()).replace(tzinfo=PACIFIC) + timedelta(seconds=selected[0])).timestamp() if selected else None
            result.append({"capture_id": entity["capture_id"], "service_date": service_date.isoformat(), "service_kind": kind, "entity_id": entity["entity_id"], "trip_id": entity["trip_id"],
                           "direction": static["direction"], "station": station, "pdf_station_column": pdf_station, "stop_id": stop["stop_id"],
                           "static_scheduled_time": static_stop["departure_time"] if static_stop else "", "pdf_time": selected[1]["times"][pdf_station]["time_text"] if selected else "",
                           "pdf_row_id": selected[1]["row_id"] if selected else "", "pdf_page": selected[1]["page"] if selected else "",
                           "pdf_schedule_seconds": selected[0] if selected else "", "gtfsrt_event_time": stop["event_time"],
                           "static_vs_pdf_seconds": round(selected[0] - static_seconds) if selected and static_seconds is not None else "",
                           "gtfsrt_vs_pdf_seconds": round(observed_epoch - pdf_epoch) if observed_epoch is not None and pdf_epoch is not None else "",
                           "late_night_pdf_row": bool(selected and (selected[0] >= 21 * 3600)),
                           "match_method": "nearest_pdf_time_to_static_stop_time" if selected else "no_pdf_candidate"})
    return result


def write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    if not rows:
        path.write_text("\n", encoding="utf-8"); return
    keys = list(dict.fromkeys(k for row in rows for k in row))
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=keys); writer.writeheader(); writer.writerows(rows)


def run(output: Path = OUTPUT_DIR) -> dict[str, Any]:
    cells, metadata = extract_schedule()
    rows = schedule_rows(cells)
    audit = build_audit()
    comparisons = comparison_rows(audit, rows)
    output.mkdir(parents=True, exist_ok=True)
    write_csv(output / "yellow_pdf_schedule_cells.csv", cells)
    flat_rows = []
    for row in rows:
        flat_rows.append({"service_kind": row["service_kind"], "page": row["page"], "direction": row["direction"], "row_id": row["row_id"], "origin_station": row["origin_station"], "first_time_seconds": row["first_time_seconds"], "late_night": row["late_night"], "times": json.dumps({k: v["time_text"] for k, v in row["times"].items()}, separators=(",", ":"))})
    write_csv(output / "yellow_pdf_schedule_rows.csv", flat_rows)
    write_csv(output / "yellow_pdf_gtfsrt_comparison.csv", comparisons)
    late = [c for c in comparisons if c["station"] == "MLBR" and c["late_night_pdf_row"]]
    deviation = [c["gtfsrt_vs_pdf_seconds"] for c in late if c["gtfsrt_vs_pdf_seconds"] != ""]
    schedule_mismatches = [c for c in comparisons if c["static_vs_pdf_seconds"] != "" and c["static_vs_pdf_seconds"] != 0]
    mismatches_by_pdf = Counter(c["service_kind"] for c in schedule_mismatches)
    tolerated_mismatches = [c for c in schedule_mismatches if abs(c["static_vs_pdf_seconds"]) < 300]
    mismatch_policy_exceeded = any(count > 2 for count in mismatches_by_pdf.values()) or any(abs(c["static_vs_pdf_seconds"]) >= 300 for c in schedule_mismatches)
    all_mlbr = [s for s in audit["stop_rows"] if s["station"] == "MLBR"]
    yellow_mlbr = [s for s in all_mlbr if next((e for e in audit["entities"] if e["capture_id"] == s["capture_id"] and e["entity_id"] == s["entity_id"]), {}).get("static_line") == "Yellow"]
    summary = {"pdf_schedule_cells": len(cells), "pdf_schedule_rows": len(rows), "gtfsrt_yellow_comparisons": len(comparisons), "millbrae_comparisons": sum(c["station"] == "MLBR" for c in comparisons), "late_night_millbrae_comparisons": len(late), "late_night_gtfsrt_vs_pdf_median_seconds": statistics.median(deviation) if deviation else None, "late_night_gtfsrt_vs_pdf_abs_max_seconds": max((abs(x) for x in deviation), default=None), "static_pdf_mismatch_count": len(schedule_mismatches), "static_pdf_mismatches_by_pdf": dict(sorted(mismatches_by_pdf.items())), "tolerated_static_pdf_mismatch_count": len(tolerated_mismatches), "static_pdf_mismatch_policy": "allow up to two mismatches per PDF when each mismatch is strictly under five minutes; ignore tolerated mismatches for normalization", "static_pdf_mismatch_policy_exceeded": mismatch_policy_exceeded, "all_gtfsrt_millbrae_stop_rows": len(all_mlbr), "yellow_gtfsrt_millbrae_stop_rows": len(yellow_mlbr), "operational_question_status": "unresolved_from_checked_in_fixtures", "operational_question_limit": "Only one captured Yellow GTFS-RT Millbrae stop row is available; this cannot establish realtime coverage for every late-night Yellow movement.", "source_pdfs": metadata["pdfs"]}
    (output / "yellow_pdf_schedule_metadata.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    (output / "yellow_pdf_comparison_summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    report = ["# Official Yellow-line PDF schedule comparison", "", "The official BART weekday and Saturday/Sunday PDFs are optional regression fixtures for this report. They are not runtime normalization inputs. The PDFs were visually rendered before extraction; positioned glyph coordinates and the drawn table columns were used to create the machine-readable cells.", "", f"- Extracted schedule cells: **{len(cells)}**; schedule rows: **{len(rows)}**.", f"- Yellow GTFS-RT comparisons: **{len(comparisons)}**; Millbrae stop comparisons: **{summary['millbrae_comparisons']}**.", f"- Static/PDF mismatches: **{summary['static_pdf_mismatch_count']}**; tolerated under the policy: **{summary['tolerated_static_pdf_mismatch_count']}**; policy exceeded: **{summary['static_pdf_mismatch_policy_exceeded']}**.", f"- Late-night Millbrae comparisons: **{summary['late_night_millbrae_comparisons']}**.", f"- Late-night GTFS-RT minus PDF median: **{summary['late_night_gtfsrt_vs_pdf_median_seconds']} seconds**; maximum absolute deviation: **{summary['late_night_gtfsrt_vs_pdf_abs_max_seconds']} seconds**.", f"- All captured GTFS-RT Millbrae stop rows: **{summary['all_gtfsrt_millbrae_stop_rows']}**; Yellow subset: **{summary['yellow_gtfsrt_millbrae_stop_rows']}**. The remainder are Red/unknown service and are not used as Yellow evidence.", "", "## Interpretation", "", "Static GTFS remains the app-facing schedule source. PDF comparisons are regression evidence only. The policy allows up to two static/PDF mismatches per PDF when each is strictly under five minutes, accommodating a likely PDF-preparation typo; tolerated mismatches are ignored for normalization. A nonzero GTFS-RT minus PDF difference is classified as realtime operational timing deviation. Rows without a PDF candidate remain unmatched rather than being inferred.", "", "The weekday PDF explicitly labels the outbound timetable `Antioch to SFO + Millbrae (Late Nights)` and the inbound timetable `Millbrae (Early AM & Late Night) + SFO to Antioch`. The timetable includes separate SFO arrival/departure columns and Millbrae rows after the late-night transition; the comparison preserves those columns and focuses Millbrae arrival times.", "", "Status: **unresolved from the checked-in fixtures**. Only one captured Yellow GTFS-RT stop-time row reaches Millbrae. That is a limitation of the checked-in realtime snapshots, not evidence that the published Yellow schedule lacks Millbrae service; a paired late-night capture series is required to establish realtime coverage.", "", "## Machine-readable outputs", "", "- `yellow_pdf_schedule_cells.csv`: one source PDF cell per station/time, with page, row, direction, and source hash.", "- `yellow_pdf_schedule_rows.csv`: one timetable row with the station/time map encoded as JSON.", "- `yellow_pdf_gtfsrt_comparison.csv`: deterministic static-nearest PDF matching plus GTFS-RT timing deltas.", "- `yellow_pdf_comparison_summary.json`: counts, mismatch policy status, and late-night statistics.", "", "Source PDFs:"]
    for source in metadata["pdfs"]: report.append(f"- `{source['path']}` SHA-256 `{source['sha256']}`")
    (output / "yellow_pdf_report.md").write_text("\n".join(report) + "\n", encoding="utf-8")
    return summary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=OUTPUT_DIR)
    args = parser.parse_args(argv)
    print(json.dumps(run(args.output), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
