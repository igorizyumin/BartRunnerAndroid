"""Diagnose ETD, GTFS-Realtime, and projection discrepancies for one capture.

This tool deliberately does not treat an unmatched snapshot row as a
cancellation.  It reports the evidence needed to distinguish a projection
problem from a sparse or contradictory realtime feed.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path
from typing import Any

from .audit import (
    RESOURCES,
    explicit_associations,
    match_etd_to_gtfs,
    parse_etd,
    parse_trip_feed,
    read_static,
)
SUPPORTED_ETD_LINES = {"Red", "Orange", "Yellow", "Blue", "Green"}


SUMMARY_RE = re.compile(
    r"Live ETD audit: fixture=(?P<fixture>\S+).*?"
    r"expected=(?P<expected>\d+) matched=(?P<matched>\d+) "
    r"missing=(?P<missing>\d+) timeMismatches=(?P<time>\d+) "
    r"destinationMismatches=(?P<destination>\d+) "
    r"projectionOnly=(?P<projection_only>\d+) "
    r"(?:projectionSources=(?P<projection_sources>\{.*?\}) )?"
    r"unexpectedCancellations=(?P<unexpected_cancellations>\d+) "
    r"expectedCancellations=(?P<expected_cancellations>\d+) "
    r"actualCancellations=(?P<actual_cancellations>\d+)"
)


def parse_test_projection_summary(path: Path, capture_id: str) -> dict[str, Any] | None:
    """Read the optional JUnit XML emitted by LiveEtdStationBoardAuditTest."""
    if not path.is_file():
        return None
    root = ET.parse(path).getroot()
    output = "\n".join(node.text or "" for node in root.findall(".//system-out"))
    match = next(
        (
            parsed
            for line in output.splitlines()
            if (parsed := SUMMARY_RE.search(line)) is not None
            and parsed.group("fixture") == capture_id
        ),
        None,
    )
    if match is None:
        return None
    result = {}
    for key, value in match.groupdict().items():
        if key == "fixture":
            result[key] = value
        elif key == "projection_sources":
            result[key] = {
                item.split("=", 1)[0].strip(): int(item.split("=", 1)[1])
                for item in value.strip("{}").split(", ")
                if "=" in item
            } if value else {}
        else:
            result[key] = int(value)
    result["test_results"] = str(path)
    return result


def _epoch(value: str) -> float | None:
    if not value:
        return None
    return datetime.fromisoformat(value).timestamp()


def _nearest_unmatched_etd(
    etd_rows: list[dict[str, Any]],
    stop_rows: list[dict[str, Any]],
    entities: list[dict[str, Any]],
    matches: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Find the nearest raw RT stop for each active ETD row left unmatched."""
    entity_by_id = {row["entity_id"]: row for row in entities}
    stops_by_station: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for stop in stop_rows:
        stops_by_station[stop["station"]].append(stop)

    result = []
    for match in matches:
        if match["method"] != "unmatched" or not match["etd_index"]:
            continue
        etd = etd_rows[int(match["etd_index"])]
        if etd["status"] == "canceled":
            continue
        target_time = _epoch(etd["absolute_prediction_time"])
        if target_time is None:
            continue
        candidates = []
        for stop in stops_by_station.get(etd["station"], []):
            event_time = _epoch(stop["event_time"])
            entity = entity_by_id.get(stop["entity_id"], {})
            if event_time is None:
                continue
            line_match = not etd["line"] or etd["line"] == entity.get("static_line", "")
            destination = entity.get("static_terminal", "")
            destination_match = not etd["destination_abbreviation"] or (
                etd["destination_abbreviation"] == destination
            )
            candidates.append(
                (
                    0 if line_match else 1,
                    0 if destination_match else 1,
                    abs(target_time - event_time),
                    stop,
                    entity,
                )
            )
        nearest = sorted(candidates, key=lambda item: item[:3])[:3]
        result.append(
            {
                "station": etd["station"],
                "line": etd["line"],
                "direction": etd["direction"],
                "destination": etd["destination_abbreviation"],
                "etd_time": etd["absolute_prediction_time"],
                "reason": match["unmatched_reason"],
                "nearest_realtime": [
                    {
                        "trip_id": entity.get("trip_id", ""),
                        "entity_id": stop.get("entity_id", ""),
                        "station": stop.get("station", ""),
                        "line": entity.get("static_line", ""),
                        "terminal": entity.get("static_terminal", ""),
                        "event_time": stop.get("event_time", ""),
                        "delta_seconds": round(delta),
                        "line_match": line_match,
                        "destination_match": destination_match,
                        "partial_stop_list": entity.get("has_partial_stop_list", False),
                        "stale_origin": entity.get("origin_prediction_stale", False),
                    }
                    for _, _, delta, stop, entity in nearest
                ],
            }
        )
    return result


def _read_csv_rows(path: Path, capture_id: str) -> list[dict[str, str]]:
    if not path.is_file():
        return []
    with path.open(newline="", encoding="utf-8") as handle:
        return [row for row in csv.DictReader(handle) if row.get("capture_id") == capture_id]


def debug_capture(
    capture_id: str,
    test_results: Path | None = None,
) -> dict[str, Any]:
    """Build a structured discrepancy diagnosis from raw checked-in inputs."""
    capture = RESOURCES / capture_id
    trip_path = capture / "trip_updates.pb"
    if not trip_path.is_file():
        raise FileNotFoundError(f"missing GTFS-Realtime fixture: {trip_path}")

    static = read_static(RESOURCES / "gtfs" / "bart_google_transit.zip")
    feed, entities, stop_rows = parse_trip_feed(trip_path, static, capture_id)
    association_by_entity = {
        row["entity_id"]: row for row in explicit_associations({"entities": entities})
    }
    entities = [
        {**entity, **association_by_entity.get(entity["entity_id"], {})}
        for entity in entities
    ]
    etd_rows: list[dict[str, Any]] = []
    for path in sorted((capture / "etd").glob("*.xml")):
        _, rows = parse_etd(path, capture_id)
        etd_rows.extend(rows)
    comparable_etd_rows = [row for row in etd_rows if row["line"] in SUPPORTED_ETD_LINES]
    matches = match_etd_to_gtfs(comparable_etd_rows, stop_rows, entities)

    unmatched_etd = [
        comparable_etd_rows[int(row["etd_index"])]
        for row in matches
        if row["method"] == "unmatched"
        and row["etd_index"]
        and comparable_etd_rows[int(row["etd_index"])] ["status"] != "canceled"
    ]
    unmatched_canceled = [
        comparable_etd_rows[int(row["etd_index"])]
        for row in matches
        if row["method"] == "unmatched"
        and row["etd_index"]
        and comparable_etd_rows[int(row["etd_index"])] ["status"] == "canceled"
    ]
    rt_only = [row for row in matches if row["method"] == "gtfsrt_only"]
    entity_by_trip = {row["trip_id"]: row for row in entities}
    rt_only_by_trip = Counter(row["trip_id"] for row in rt_only)

    test_summary = None
    if test_results is not None:
        test_summary = parse_test_projection_summary(test_results, capture_id)

    return {
        "capture_id": capture_id,
        "feed": feed,
        "etd": {
            "rows": len(etd_rows),
            "unsupported_line_rows": sum(
                row["line"] not in SUPPORTED_ETD_LINES for row in etd_rows
            ),
            "unsupported_lines": dict(
                Counter(row["line"] for row in etd_rows if row["line"] not in SUPPORTED_ETD_LINES)
            ),
            "active_rows": sum(row["status"] != "canceled" for row in comparable_etd_rows),
            "canceled_rows": len(unmatched_canceled),
            "active_unmatched_rows": len(unmatched_etd),
            "unmatched_by_station": dict(Counter(row["station"] for row in unmatched_etd)),
            "unmatched_details": _nearest_unmatched_etd(
                comparable_etd_rows, stop_rows, entities, matches
            ),
        },
        "realtime": {
            "entities": len(entities),
            "stop_rows": len(stop_rows),
            "association_status": dict(Counter(row["association_status"] for row in entities)),
            "schedule_relationship": dict(Counter(row["schedule_relationship"] for row in entities)),
            "partial_stop_lists": sum(row["has_partial_stop_list"] for row in entities),
            "stale_origin_predictions": sum(row["origin_prediction_stale"] for row in entities),
            "ends_before_static_terminal": sum(row["ends_before_static_terminal"] for row in entities),
            "missing_route_ids": sum(not row["has_route_id"] for row in entities),
            "rt_only_stop_rows": len(rt_only),
            "rt_only_trips": rt_only_by_trip.most_common(20),
            "rt_only_entities": [
                {
                    "trip_id": trip_id,
                    "entity_id": entity_by_trip.get(trip_id, {}).get("entity_id", ""),
                    "line": entity_by_trip.get(trip_id, {}).get("static_line", ""),
                    "terminal": entity_by_trip.get(trip_id, {}).get("static_terminal", ""),
                    "association_status": entity_by_trip.get(trip_id, {}).get("association_status", ""),
                    "partial_stop_list": entity_by_trip.get(trip_id, {}).get("has_partial_stop_list", False),
                    "stale_origin": entity_by_trip.get(trip_id, {}).get("origin_prediction_stale", False),
                }
                for trip_id, _ in rt_only_by_trip.most_common(20)
            ],
        },
        "projection_test": test_summary,
    }


def _print_text(report: dict[str, Any]) -> None:
    print(f"capture: {report['capture_id']}")
    feed = report["feed"]
    print(
        "feed: "
        f"{feed['feed_timestamp']} trips={feed['trip_update_count']} "
        f"stop_rows={feed['stop_time_update_count']} "
        f"canceled_updates={feed['canceled_trip_update_count']}"
    )
    etd = report["etd"]
    print(
        "ETD: "
        f"rows={etd['rows']} active={etd['active_rows']} "
        f"active_unmatched={etd['active_unmatched_rows']} "
        f"canceled={etd['canceled_rows']} "
        f"unsupported_line_rows={etd['unsupported_line_rows']}"
    )
    print(f"ETD unmatched stations: {etd['unmatched_by_station']}")
    rt = report["realtime"]
    print(
        "GTFS-RT: "
        f"entities={rt['entities']} stop_rows={rt['stop_rows']} "
        f"partial={rt['partial_stop_lists']} stale_origins={rt['stale_origin_predictions']} "
        f"ends_before_terminal={rt['ends_before_static_terminal']} "
        f"rt_only_stop_rows={rt['rt_only_stop_rows']}"
    )
    print(f"GTFS-RT association status: {rt['association_status']}")
    if report["projection_test"]:
        print(f"projection test: {report['projection_test']}")
    print("\nNearest realtime candidates for active ETD rows with no match:")
    for row in etd["unmatched_details"]:
        nearest = "; ".join(
            f"{candidate['trip_id']} {candidate['line']}/{candidate['terminal']} "
            f"delta={candidate['delta_seconds']}s"
            for candidate in row["nearest_realtime"]
        )
        print(
            f"  {row['station']} {row['line']}->{row['destination']} "
            f"{row['etd_time']} reason={row['reason']} | {nearest}"
        )
    print("\nTop GTFS-RT-only trips:")
    for trip_id, count in rt["rt_only_trips"]:
        print(f"  {trip_id}: {count} stop rows")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture", required=True, help="fixture directory name")
    parser.add_argument(
        "--test-results",
        type=Path,
        default=Path("app/build/test-results/testDebugUnitTest/TEST-in.izyum.bart.backend.LiveEtdStationBoardAuditTest.xml"),
        help="optional JUnit XML from the projection audit",
    )
    parser.add_argument("--json", action="store_true", help="emit structured JSON")
    parser.add_argument("--output", type=Path, help="write structured JSON to this path")
    args = parser.parse_args(argv)
    report = debug_capture(args.capture, args.test_results)
    encoded = json.dumps(report, indent=2, sort_keys=True, default=str)
    if args.output:
        args.output.write_text(encoded + "\n", encoding="utf-8")
    if args.json:
        print(encoded)
    else:
        _print_text(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
