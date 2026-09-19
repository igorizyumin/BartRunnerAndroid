#!/usr/bin/env python3
"""Reproducible, source-oriented audit of the BART fixtures.

The audit deliberately reads the checked-in GTFS ZIP, GTFS-Realtime protobuf,
and ETD XML files.  JSON files are inventoried as diagnostics only and are not
used as inputs to any comparison.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import statistics
import sys
import zipfile
from collections import Counter, defaultdict
from dataclasses import dataclass, asdict
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable
from xml.etree import ElementTree as ET
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

try:
    from google.transit import gtfs_realtime_pb2
except ImportError as exc:  # pragma: no cover - exercised by the CLI environment
    raise SystemExit(
        "gtfs-realtime-bindings is required; install it with "
        "python -m pip install gtfs-realtime-bindings"
    ) from exc


try:
    PACIFIC = ZoneInfo("America/Los_Angeles")
except ZoneInfoNotFoundError:  # Windows Python installs may omit the IANA tzdata package.
    # Every checked-in capture is in September 2026 (PDT, UTC-07:00).  Keep
    # the audit runnable without making tzdata a second hidden dependency.
    PACIFIC = timezone(timedelta(hours=-7), "PDT")
ROOT = Path(__file__).resolve().parents[2]
RESOURCES = ROOT / "app" / "src" / "test" / "resources"
OUT = ROOT / "docs" / "bart_data_audit"
DATE_RE = re.compile(r"(?:bart_live_)?(20\d{6})_(\d{6})")
TIME_RE = re.compile(r"^(\d{1,2}):(\d{2}):(\d{2})$")
RELATIONSHIPS = {
    0: "SCHEDULED",
    1: "ADDED",
    2: "UNSCHEDULED",
    3: "CANCELED",
    4: "DUPLICATED",
    5: "DELETED",
}
STOP_RELATIONSHIPS = {0: "SCHEDULED", 1: "SKIPPED", 2: "NO_DATA", 3: "UNSCHEDULED"}
COLORS = {"YELLOW", "ORANGE", "RED", "GREEN", "BLUE", "PURPLE", "WHITE"}
COLOR_TO_LINE = {"YELLOW": "Yellow", "ORANGE": "Orange", "RED": "Red", "GREEN": "Green", "BLUE": "Blue", "PURPLE": "Purple"}


def iso(ts: int | float | None) -> str:
    return datetime.fromtimestamp(ts, timezone.utc).isoformat() if ts else ""


def clean(value: Any) -> str:
    return "" if value is None else str(value).strip()


def parse_date(value: str) -> datetime.date:
    return datetime.strptime(value, "%Y%m%d").date()


def parse_capture_time(name: str) -> datetime | None:
    match = DATE_RE.search(name)
    if not match:
        return None
    return datetime.strptime("_".join(match.groups()), "%Y%m%d_%H%M%S").replace(tzinfo=PACIFIC)


def parse_request_time(date_text: str, time_text: str) -> datetime | None:
    """Parse BART's ETD date/time pair, including PDT/PST suffixes."""
    candidates = [time_text]
    if time_text.rsplit(" ", 1)[-1].upper() in {"PDT", "PST", "PT"}:
        candidates.append(time_text.rsplit(" ", 1)[0])
    for candidate in candidates:
        for fmt in ("%m/%d/%Y %I:%M:%S %p %Z", "%m/%d/%Y %I:%M:%S %p"):
            try:
                return datetime.strptime(f"{date_text} {candidate}", fmt).replace(tzinfo=PACIFIC)
            except ValueError:
                continue
    return None


def csv_rows(raw: bytes) -> list[dict[str, str]]:
    return list(csv.DictReader(raw.decode("utf-8-sig").splitlines()))


def read_static(path: Path) -> dict[str, Any]:
    with zipfile.ZipFile(path) as archive:
        tables = {name: csv_rows(archive.read(name)) for name in archive.namelist() if name.endswith(".txt")}
    routes = {r["route_id"]: r for r in tables.get("routes.txt", [])}
    stops = {s["stop_id"]: s for s in tables.get("stops.txt", [])}
    parent_by_stop = {sid: (s.get("parent_station") or sid) for sid, s in stops.items()}
    station_names = {sid: (s.get("stop_name") or sid) for sid, s in stops.items() if sid == parent_by_stop.get(sid)}
    station_names.update({s.get("parent_station", sid): s.get("stop_name", sid) for sid, s in stops.items() if s.get("parent_station")})
    calendar = {r["service_id"]: r for r in tables.get("calendar.txt", [])}
    exceptions: dict[tuple[str, str], str] = {(r["service_id"], r["date"]): r["exception_type"] for r in tables.get("calendar_dates.txt", [])}
    trips = {r["trip_id"]: r for r in tables.get("trips.txt", [])}
    stop_times: dict[str, list[dict[str, str]]] = defaultdict(list)
    for row in tables.get("stop_times.txt", []):
        stop_times[row["trip_id"]].append(row)
    for rows in stop_times.values():
        rows.sort(key=lambda r: (int(r.get("stop_sequence") or 0), r.get("stop_id", "")))
    feed_info = tables.get("feed_info.txt", [{}])[0]
    return {
        "path": str(path), "tables": tables, "routes": routes, "stops": stops,
        "parent_by_stop": parent_by_stop, "station_names": station_names,
        "calendar": calendar, "exceptions": exceptions, "trips": trips,
        "stop_times": stop_times, "feed_info": feed_info,
    }


def service_active(static: dict[str, Any], service_id: str, service_date: datetime.date) -> bool:
    key = (service_id, service_date.strftime("%Y%m%d"))
    if key in static["exceptions"]:
        return static["exceptions"][key] == "1"
    row = static["calendar"].get(service_id)
    if not row or not (parse_date(row["start_date"]) <= service_date <= parse_date(row["end_date"])):
        return False
    return row[service_date.strftime("%A").lower()] == "1"


def time_seconds(value: str) -> int | None:
    match = TIME_RE.match(value or "")
    if not match:
        return None
    h, m, s = map(int, match.groups())
    return h * 3600 + m * 60 + s


def static_trip(static: dict[str, Any], trip_id: str, service_date: datetime.date | None = None) -> dict[str, Any] | None:
    trip = static["trips"].get(trip_id)
    if not trip:
        return None
    route = static["routes"].get(trip.get("route_id", ""), {})
    rows = static["stop_times"].get(trip_id, [])
    stations = [static["parent_by_stop"].get(r.get("stop_id", ""), r.get("stop_id", "")) for r in rows]
    station_sequence = list(dict.fromkeys(stations))
    schedule = [
        {"stop_id": r.get("stop_id", ""), "station": static["parent_by_stop"].get(r.get("stop_id", ""), r.get("stop_id", "")),
         "stop_sequence": r.get("stop_sequence", ""), "arrival_time": r.get("arrival_time", ""),
         "departure_time": r.get("departure_time", ""), "arrival_seconds": time_seconds(r.get("arrival_time", "")),
         "departure_seconds": time_seconds(r.get("departure_time", ""))}
        for r in rows
    ]
    line = (route.get("route_short_name", "").split("-", 1)[0] or "").title()
    return {
        "service_date": service_date.isoformat() if service_date else "",
        "trip_id": trip_id, "route_id": trip.get("route_id", ""), "line": line,
        "direction": route.get("route_short_name", "").split("-", 1)[-1] if "-" in route.get("route_short_name", "") else "",
        "direction_id": trip.get("direction_id", ""), "headsign": trip.get("trip_headsign", ""),
        "ordered_stations": station_sequence, "origin_station": station_sequence[0] if station_sequence else "",
        "terminal_station": station_sequence[-1] if station_sequence else "",
        "scheduled_origin_departure": schedule[0]["departure_time"] if schedule else "",
        "scheduled_stop_times": schedule, "stop_count": len(schedule),
        "is_short_turn": station_sequence[-1:] in [["PITT"], ["PCTR"], ["ANTC"]] or (line == "Yellow" and station_sequence and station_sequence[-1] not in {"MLBR", "SFIA", "ANTC"}),
        "active_on_service_date": service_active(static, trip.get("service_id", ""), service_date) if service_date else None,
    }


def enum_name(enum_cls: Any, value: int) -> str:
    try:
        return enum_cls.Name(value)
    except ValueError:
        return str(value)


def canonical_service_date(start_date: str, capture_id: str) -> tuple[str, str, bool]:
    """Return the service date used for joins, plus its provenance.

    GTFS-RT start_date is authoritative when present.  For feeds that omit it,
    BART's post-midnight operating period is assigned to the prior local date
    through 03:00; otherwise the capture's local calendar date is used.  The
    conflict flag makes a start_date/capture-date disagreement visible.
    """
    capture_dt = parse_capture_time(capture_id)
    capture_date = capture_dt.date() if capture_dt else None
    supplied = clean(start_date)
    if supplied:
        try:
            parsed = parse_date(supplied)
            return parsed.isoformat(), "gtfsrt_start_date", bool(capture_date and parsed != capture_date)
        except ValueError:
            pass
    if capture_date is None:
        return "", "unavailable", False
    if capture_dt.hour < 3:
        return (capture_date - timedelta(days=1)).isoformat(), "capture_local_date_pre_03_service_day", False
    return capture_date.isoformat(), "capture_local_date_fallback", False


def stop_row(static: dict[str, Any], stop: Any, update: Any, entity_id: str, capture_id: str, source: str,
             feed_timestamp: int, service_date: str, service_date_source: str, service_date_conflict: bool) -> dict[str, Any]:
    stop_id = stop.stop_id if stop.HasField("stop_id") else ""
    station = static["parent_by_stop"].get(stop_id, "")
    arrival = stop.arrival.time if stop.HasField("arrival") and stop.arrival.HasField("time") else None
    departure = stop.departure.time if stop.HasField("departure") and stop.departure.HasField("time") else None
    arrival_delay = stop.arrival.delay if stop.HasField("arrival") and stop.arrival.HasField("delay") else None
    departure_delay = stop.departure.delay if stop.HasField("departure") and stop.departure.HasField("delay") else None
    event = departure or arrival
    return {
        "capture_id": capture_id, "service_date": service_date, "service_date_source": service_date_source,
        "service_date_conflict": service_date_conflict, "source_path": source, "feed_timestamp": iso(feed_timestamp),
        "entity_id": entity_id, "trip_id": update.trip.trip_id, "route_id": update.trip.route_id,
        "schedule_relationship": RELATIONSHIPS.get(update.trip.schedule_relationship, str(update.trip.schedule_relationship)),
        "stop_id": stop_id, "station": station, "platform_suffix": stop_id.rsplit("-", 1)[-1] if "-" in stop_id else "",
        "stop_sequence": stop.stop_sequence if stop.HasField("stop_sequence") else "",
        "stop_relationship": STOP_RELATIONSHIPS.get(stop.schedule_relationship, str(stop.schedule_relationship)) if stop.HasField("schedule_relationship") else "SCHEDULED",
        "arrival_time": iso(arrival), "departure_time": iso(departure), "event_time": iso(event),
        "arrival_delay_seconds": arrival_delay if arrival_delay is not None else "", "departure_delay_seconds": departure_delay if departure_delay is not None else "",
        "delay_only": bool(event is None and (arrival_delay is not None or departure_delay is not None)),
        "has_platform_but_no_station": bool(stop_id and stop_id.rsplit("-", 1)[-1].isdigit() and not station),
    }


def parse_trip_feed(path: Path, static: dict[str, Any], capture_id: str) -> tuple[dict[str, Any], list[dict[str, Any]], list[dict[str, Any]]]:
    feed = gtfs_realtime_pb2.FeedMessage()
    feed.ParseFromString(path.read_bytes())
    timestamp = feed.header.timestamp if feed.header.HasField("timestamp") else 0
    entities: list[dict[str, Any]] = []
    stops: list[dict[str, Any]] = []
    updates_by_trip: Counter[str] = Counter()
    for entity in feed.entity:
        if not entity.HasField("trip_update"):
            continue
        update = entity.trip_update
        trip_id = update.trip.trip_id
        service_date, service_date_source, service_date_conflict = canonical_service_date(update.trip.start_date, capture_id)
        rows = [stop_row(static, s, update, entity.id, capture_id, str(path), timestamp, service_date, service_date_source, service_date_conflict) for s in update.stop_time_update]
        stops.extend(rows)
        static_row = static_trip(static, trip_id, datetime.fromisoformat(service_date).date() if service_date else None)
        last_station = rows[-1]["station"] if rows else ""
        origin_event = next((r["event_time"] for r in rows if r["event_time"]), "")
        entities.append({
            "capture_id": capture_id, "service_date": service_date, "service_date_source": service_date_source,
            "service_date_conflict": service_date_conflict, "source_path": str(path), "feed_timestamp": iso(timestamp),
            "entity_id": entity.id, "trip_id": trip_id, "route_id": update.trip.route_id,
            "schedule_relationship": RELATIONSHIPS.get(update.trip.schedule_relationship, str(update.trip.schedule_relationship)),
            "start_date": update.trip.start_date, "start_time": update.trip.start_time,
            "stop_time_update_count": len(rows), "missing_stop_sequence_count": sum(not bool(r["stop_sequence"]) for r in rows),
            "first_stop_id": rows[0]["stop_id"] if rows else "", "last_stop_id": rows[-1]["stop_id"] if rows else "",
            "first_station": rows[0]["station"] if rows else "", "last_station": last_station,
            "origin_event_time": origin_event, "is_numeric_600_799_entity": entity.id.isdigit() and 600 <= int(entity.id) <= 799,
            "is_numeric_600_799_trip": trip_id.isdigit() and 600 <= int(trip_id) <= 799,
            "has_route_id": bool(update.trip.route_id), "static_trip_exists": bool(static_row),
            "static_line": static_row["line"] if static_row else "",
            "static_terminal": static_row["terminal_station"] if static_row else "",
            "ends_before_static_terminal": bool(static_row and last_station and last_station != static_row["terminal_station"]),
            "has_partial_stop_list": bool(static_row and rows and len(rows) < len(static["stop_times"].get(trip_id, []))),
            "origin_prediction_stale": bool(origin_event and timestamp and datetime.fromisoformat(origin_event).timestamp() < timestamp - 60),
        })
        updates_by_trip[trip_id] += 1
    duplicate_trips = {k for k, v in updates_by_trip.items() if v > 1}
    for row in entities:
        row["duplicate_trip_id_in_feed"] = row["trip_id"] in duplicate_trips
    feed_row = {
        "capture_id": capture_id, "source_path": str(path), "feed_timestamp": iso(timestamp),
        "feed_timestamp_epoch": timestamp, "entity_count": len(feed.entity), "trip_update_count": len(entities),
        "stop_time_update_count": len(stops), "alerts_in_feed": sum(e.HasField("alert") for e in feed.entity),
        "numeric_600_799_entity_count": sum(r["is_numeric_600_799_entity"] for r in entities),
        "numeric_600_799_trip_count": sum(r["is_numeric_600_799_trip"] for r in entities),
        "duplicate_trip_id_count": len(duplicate_trips), "missing_route_id_count": sum(not r["has_route_id"] for r in entities),
        "missing_stop_sequence_count": sum(r["missing_stop_sequence_count"] for r in entities),
        "canceled_trip_update_count": sum(r["schedule_relationship"] == "CANCELED" for r in entities),
    }
    return feed_row, entities, stops


def alert_summary(path: Path, capture_id: str) -> list[dict[str, Any]]:
    feed = gtfs_realtime_pb2.FeedMessage()
    feed.ParseFromString(path.read_bytes())
    timestamp = feed.header.timestamp if feed.header.HasField("timestamp") else 0
    result = []
    for entity in feed.entity:
        if not entity.HasField("alert"):
            continue
        alert = entity.alert
        header = alert.header_text.translation[0].text if alert.header_text.translation else ""
        description = alert.description_text.translation[0].text if alert.description_text.translation else ""
        informed = [{"route_id": x.route_id, "trip_id": x.trip.trip_id, "stop_id": x.stop_id} for x in alert.informed_entity]
        result.append({"capture_id": capture_id, "source_path": str(path), "feed_timestamp": iso(timestamp), "entity_id": entity.id,
                       "cause": enum_name(gtfs_realtime_pb2.Alert.Cause, alert.cause), "effect": enum_name(gtfs_realtime_pb2.Alert.Effect, alert.effect),
                       "header": header, "description": description, "informed_entities": informed})
    return result


def parse_etd(path: Path, capture_id: str) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    root = ET.parse(path).getroot()
    date_text = root.findtext("date", "")
    time_text = root.findtext("time", "")
    requested = None
    requested = parse_request_time(date_text, time_text)
    station_node = root.find("station")
    station = station_node.findtext("abbr", "") if station_node is not None else path.stem.upper()
    station_name = station_node.findtext("name", "") if station_node is not None else ""
    rows = []
    for etd in root.findall("./station/etd"):
        destination = etd.findtext("destination", "")
        abbreviation = etd.findtext("abbreviation", "")
        for estimate in etd.findall("estimate"):
            minutes_text = estimate.findtext("minutes", "")
            numeric_minutes = int(minutes_text) if minutes_text.isdigit() else None
            absolute = requested + timedelta(minutes=numeric_minutes) if requested and numeric_minutes is not None else requested
            rows.append({
                "capture_id": capture_id, "station": station, "station_name": station_name,
                "line": COLOR_TO_LINE.get(estimate.findtext("color", "").upper(), estimate.findtext("color", "")),
                "color": estimate.findtext("color", ""), "destination_label": destination, "destination_abbreviation": abbreviation,
                "platform": estimate.findtext("platform", ""), "direction": estimate.findtext("direction", ""),
                "minutes": minutes_text, "absolute_prediction_time": absolute.isoformat() if absolute else "",
                "status": "canceled" if estimate.findtext("cancelflag", "0") == "1" else ("leaving" if minutes_text.lower() == "leaving" else "future"),
                "delay_seconds": estimate.findtext("delay", ""), "dynamic": estimate.findtext("dynamicflag", "0"),
                "raw_source_path": str(path), "request_time": requested.isoformat() if requested else "",
            })
    return {"capture_id": capture_id, "source_path": str(path), "request_time": requested.isoformat() if requested else "",
            "station": station, "prediction_count": len(rows)}, rows


def source_hash(path: Path) -> str:
    digest = hashlib.sha256()
    digest.update(path.read_bytes())
    return digest.hexdigest()


def static_source_inventory(paths: list[Path], service_dates: list[datetime.date]) -> dict[str, Any]:
    """Inventory and compare every checked-in static feed before selecting one."""
    sources = []
    for path in sorted(paths):
        parsed = read_static(path)
        table_hashes = {}
        table_counts = {}
        with zipfile.ZipFile(path) as archive:
            for name in sorted(n for n in archive.namelist() if n.endswith(".txt")):
                table_hashes[name] = hashlib.sha256(archive.read(name)).hexdigest()
                table_counts[name] = len(parsed["tables"].get(name, []))
        coverage = {date.isoformat(): sum(service_active(parsed, t.get("service_id", ""), date) for t in parsed["trips"].values()) for date in service_dates}
        sources.append({"path": str(path), "sha256": source_hash(path), "feed_info": parsed["feed_info"],
                        "table_counts": table_counts, "table_sha256": table_hashes,
                        "active_trip_counts_by_service_date": coverage})
    if not sources:
        raise FileNotFoundError(f"no static GTFS ZIPs found under {RESOURCES / 'gtfs'}")
    max_coverage = max((sum(v["active_trip_counts_by_service_date"].values()) for v in sources), default=0)
    candidates = [v for v in sources if sum(v["active_trip_counts_by_service_date"].values()) == max_coverage]
    # If feeds tie on service coverage, identical content is interchangeable;
    # otherwise choose the highest feed version and use path as a stable tie-break.
    selected = sorted(candidates, key=lambda v: (int(v["feed_info"].get("feed_version", "-1")) if v["feed_info"].get("feed_version", "").isdigit() else -1, v["path"]), reverse=True)[0]
    for source in sources:
        source["diff_from_selected"] = {name: {"selected_sha256": selected["table_sha256"].get(name, ""),
                                                "source_sha256": source["table_sha256"].get(name, ""),
                                                "selected_row_count": selected["table_counts"].get(name, 0),
                                                "source_row_count": source["table_counts"].get(name, 0)}
                                          for name in sorted(set(selected["table_sha256"]) | set(source["table_sha256"]))
                                          if selected["table_sha256"].get(name, "") != source["table_sha256"].get(name, "") or
                                          selected["table_counts"].get(name, 0) != source["table_counts"].get(name, 0)}
    identical_content = len({json.dumps(v["table_sha256"], sort_keys=True) for v in sources}) == 1
    return {"selection_rule": "maximum active-trip coverage across observed service dates; highest feed_version then path as deterministic tie-break",
            "selected": selected["path"], "selected_sha256": selected["sha256"], "identical_table_content": identical_content,
            "sources": sources}


def capture_manifest(static: dict[str, Any]) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    rows: list[dict[str, Any]] = []
    supplemental: list[dict[str, Any]] = []
    components: list[dict[str, Any]] = []
    capture_dirs = sorted(p for p in RESOURCES.glob("bart_live_*") if p.is_dir())
    for directory in capture_dirs:
        capture_id = directory.name
        trip = directory / "trip_updates.pb"
        alerts = directory / "alerts.pb"
        etd = sorted((directory / "etd").glob("*.xml")) if (directory / "etd").exists() else []
        feed_timestamp = ""
        trip_count = stop_count = alert_count = 0
        if trip.exists():
            feed = gtfs_realtime_pb2.FeedMessage(); feed.ParseFromString(trip.read_bytes())
            feed_timestamp = iso(feed.header.timestamp if feed.header.HasField("timestamp") else 0)
            trip_count = sum(e.HasField("trip_update") for e in feed.entity)
            stop_count = sum(len(e.trip_update.stop_time_update) for e in feed.entity if e.HasField("trip_update"))
            components.append({"capture_id": capture_id, "component_type": "gtfsrt_trip_updates", "source_path": str(trip),
                               "size_bytes": trip.stat().st_size, "sha256": source_hash(trip)})
        if alerts.exists():
            feed = gtfs_realtime_pb2.FeedMessage(); feed.ParseFromString(alerts.read_bytes())
            alert_count = sum(e.HasField("alert") for e in feed.entity)
            components.append({"capture_id": capture_id, "component_type": "gtfsrt_alerts", "source_path": str(alerts),
                               "size_bytes": alerts.stat().st_size, "sha256": source_hash(alerts)})
        request_times = []
        request_datetimes = []
        for path in etd:
            try:
                root = ET.parse(path).getroot()
                request_times.append(root.findtext("time", ""))
                request_dt = parse_request_time(root.findtext("date", ""), root.findtext("time", ""))
                if request_dt: request_datetimes.append(request_dt)
            except ET.ParseError:
                pass
            components.append({"capture_id": capture_id, "component_type": "etd_xml", "source_path": str(path),
                               "size_bytes": path.stat().st_size, "sha256": source_hash(path)})
        capture_dt = parse_capture_time(capture_id)
        flags = []
        if not trip.exists(): flags.append("missing_gtfs_rt_trip_updates")
        if not alerts.exists(): flags.append("missing_alerts")
        if not etd: flags.append("missing_etd_boards")
        if len(etd) < 10: flags.append("incomplete_etd_station_set")
        if "20260912_071605" in capture_id or "20260912_192815" in capture_id: flags.append("known_bus_bridge_or_major_disruption_context")
        feed_dt = datetime.fromisoformat(feed_timestamp) if feed_timestamp else None
        skew = abs((feed_dt - capture_dt).total_seconds()) if feed_dt and capture_dt else None
        if skew is not None and skew > 120: flags.append("large_capture_feed_time_difference")
        etd_skews = [abs((request_dt - feed_dt).total_seconds()) for request_dt in request_datetimes] if feed_dt else []
        if etd_skews and max(etd_skews) > 120: flags.append("large_etd_gtfsrt_time_difference")
        component_hashes = {r["component_type"]: r["sha256"] for r in components if r["capture_id"] == capture_id and r["component_type"] != "etd_xml"}
        etd_hashes = {Path(r["source_path"]).name: r["sha256"] for r in components if r["capture_id"] == capture_id and r["component_type"] == "etd_xml"}
        digest_input = "\n".join(f"{r['component_type']}|{r['source_path']}|{r['sha256']}" for r in components if r["capture_id"] == capture_id)
        capture_digest = hashlib.sha256(digest_input.encode("utf-8")).hexdigest()
        rows.append({"capture_id": capture_id, "capture_directory": str(directory), "local_capture_timestamp": capture_dt.isoformat() if capture_dt else "",
                     "gtfs_rt_feed_timestamp": feed_timestamp, "has_trip_updates": trip.exists(), "has_alerts": alerts.exists(),
                     "etd_station_count": len(etd), "gtfs_rt_trip_update_count": trip_count, "gtfs_rt_stop_time_update_count": stop_count,
                     "alert_count": alert_count, "static_gtfs_version": static["feed_info"].get("feed_version", ""),
                     "request_time_samples": "|".join(request_times[:3]), "capture_feed_skew_seconds": skew if skew is not None else "",
                     "etd_feed_skew_min_seconds": min(etd_skews) if etd_skews else "", "etd_feed_skew_max_seconds": max(etd_skews) if etd_skews else "",
                     "etd_feed_skew_median_seconds": statistics.median(etd_skews) if etd_skews else "",
                     "trip_updates_sha256": component_hashes.get("gtfsrt_trip_updates", ""), "alerts_sha256": component_hashes.get("gtfsrt_alerts", ""),
                     "etd_sha256_manifest": etd_hashes, "capture_sha256": capture_digest,
                     "flags": "|".join(flags) or "none", "strict_comparison_suitable": not flags})
    # These files are valuable evidence but are not merged into directory captures.
    for base, kind, patterns in [(RESOURCES / "gtfsrt", "gtfsrt_supplemental", ["*.pb"]), (RESOURCES / "etd", "etd_supplemental", ["*.json"]), (ROOT / "docs", "docs_supplemental", ["*.json", "*.pb"])]:
        for pattern in patterns:
            for path in sorted(base.glob(pattern)):
                supplemental.append({"source_id": f"{kind}:{path.name}", "kind": kind, "source_path": str(path), "size_bytes": path.stat().st_size,
                                     "sha256": source_hash(path), "filename_timestamp": parse_capture_time(path.name).isoformat() if parse_capture_time(path.name) else ""})
    return rows, supplemental, components


def write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not rows:
        path.write_text("\n", encoding="utf-8")
        return
    keys = list(dict.fromkeys(k for row in rows for k in row.keys()))
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=keys, extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            writer.writerow({k: json.dumps(v, separators=(",", ":"), ensure_ascii=False) if isinstance(v, (list, dict)) else v for k, v in row.items()})


def flatten_static(static_rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    output = []
    for row in static_rows:
        copy = dict(row)
        copy["ordered_stations"] = "|".join(row["ordered_stations"])
        copy["scheduled_stop_times"] = json.dumps(row["scheduled_stop_times"], separators=(",", ":"))
        output.append(copy)
    return output


def label_alias(label: str) -> str:
    normalized = re.sub(r"[^A-Z0-9]", "", label.upper())
    return {"SFO": "SFIA", "SFAIRPORT": "SFIA", "SFOAIRPORT": "SFIA", "MILLBRAE": "MLBR", "SFOMILLBRAE": "MLBR", "PITTSBURGBAYPOINT": "PITT", "PITTSBURG": "PITT", "PITTSBURGCENTER": "PCTR", "DUBLINPLEASANTON": "DUBL"}.get(normalized, normalized)


def match_etd_to_gtfs(etd_rows: list[dict[str, Any]], stop_rows: list[dict[str, Any]], entity_rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    candidates = []
    for e_index, etd in enumerate(etd_rows):
        if etd["status"] == "canceled" or not etd["absolute_prediction_time"]:
            continue
        etd_time = datetime.fromisoformat(etd["absolute_prediction_time"]).timestamp()
        for s_index, stop in enumerate(stop_rows):
            if not stop["station"] or stop["station"] != etd["station"] or not stop["event_time"]:
                continue
            entity = next((e for e in entity_rows if e["entity_id"] == stop["entity_id"]), None)
            if not entity: continue
            static_dest = label_alias(entity.get("static_terminal", ""))
            etd_dest = label_alias(etd["destination_abbreviation"] or etd["destination_label"])
            line_ok = etd["line"] in {"", entity.get("static_line", ""), COLOR_TO_LINE.get(etd["color"].upper(), "")}
            platform_ok = not etd["platform"] or etd["platform"] == stop["platform_suffix"]
            direction_ok = not etd["direction"] or (entity.get("static_line", "") and etd["direction"][0].upper() == (entity.get("static_line", "") and "N" if entity.get("route_id", "").endswith("N") else "S"))
            time_delta = abs(etd_time - datetime.fromisoformat(stop["event_time"]).timestamp())
            if time_delta > 180 or not line_ok: continue
            score = time_delta + (0 if platform_ok else 45) + (0 if direction_ok else 30) + (0 if not etd_dest or etd_dest in {static_dest, "MLBR", "SFIA", "SFO"} else 60)
            candidates.append((score, e_index, s_index, platform_ok, direction_ok, etd_dest == static_dest or not static_dest))
    matches = []
    used_e, used_s = set(), set()
    for score, e_index, s_index, platform_ok, direction_ok, dest_ok in sorted(candidates):
        if e_index in used_e or s_index in used_s: continue
        same_etd = [c for c in candidates if c[1] == e_index and c[2] not in used_s and c[0] <= score + 1]
        ambiguous = len(same_etd) > 1 and abs(same_etd[0][0] - same_etd[1][0]) < 15
        used_e.add(e_index); used_s.add(s_index)
        confidence = "high" if platform_ok and direction_ok and dest_ok and score <= 90 else ("medium" if score <= 180 else "low")
        matches.append({"etd_index": e_index, "stop_index": s_index, "entity_id": stop_rows[s_index]["entity_id"], "trip_id": stop_rows[s_index]["trip_id"],
                        "station": etd_rows[e_index]["station"], "method": "station+line+platform/direction+destination+absolute_time",
                        "time_delta_seconds": round(abs(datetime.fromisoformat(etd_rows[e_index]["absolute_prediction_time"]).timestamp() - datetime.fromisoformat(stop_rows[s_index]["event_time"]).timestamp())),
                        "confidence": "ambiguous" if ambiguous else confidence, "ambiguous": ambiguous})
    matched_e = {r["etd_index"] for r in matches}; matched_s = {r["stop_index"] for r in matches}
    for index, row in enumerate(etd_rows):
        if index not in matched_e:
            matches.append({"etd_index": index, "stop_index": "", "entity_id": "", "trip_id": "", "station": row["station"],
                            "method": "unmatched", "time_delta_seconds": "", "confidence": "unmatched", "ambiguous": False,
                            "unmatched_reason": "canceled" if row["status"] == "canceled" else "no unique GTFS-RT stop within 180 seconds"})
    for index, row in enumerate(stop_rows):
        if index not in matched_s:
            matches.append({"etd_index": "", "stop_index": index, "entity_id": row["entity_id"], "trip_id": row["trip_id"], "station": row["station"],
                            "method": "gtfsrt_only", "time_delta_seconds": "", "confidence": "unmatched", "ambiguous": False,
                            "unmatched_reason": "no ETD row with compatible station/line/time"})
    return matches


def service_capture_date(capture_id: str) -> datetime.date | None:
    dt = parse_capture_time(capture_id)
    return dt.date() if dt else None


def project_duplicate_updates(entities: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Choose one deterministic projection for each capture/trip update group."""
    groups: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for row in entities:
        identity = row["trip_id"] or f"__entity__:{row['entity_id']}"
        groups[(row["capture_id"], identity)].append(row)
    projected, decisions = [], []
    for (capture_id, trip_id), group in sorted(groups.items()):
        selected = sorted(group, key=lambda r: (
            r["schedule_relationship"] != "CANCELED",
            -(datetime.fromisoformat(r["feed_timestamp"]).timestamp() if r["feed_timestamp"] else 0),
            r["source_path"], r["entity_id"],
        ))[0]
        projected.append(dict(selected))
        for row in sorted(group, key=lambda r: r["entity_id"]):
            is_selected = row["entity_id"] == selected["entity_id"]
            decisions.append({"capture_id": capture_id, "trip_id": trip_id, "entity_id": row["entity_id"],
                              "selected": is_selected, "selected_entity_id": selected["entity_id"],
                              "duplicate_count": len(group),
                              "decision": "selected_explicit_cancellation" if is_selected and selected["schedule_relationship"] == "CANCELED" else ("selected_newest_stable_tiebreak" if is_selected else "rejected_duplicate")})
    return projected, decisions


def explicit_associations(audit: dict[str, Any]) -> list[dict[str, Any]]:
    """Materialize one explicit identity state for every GTFS-RT trip update."""
    by_capture = defaultdict(list)
    for row in audit["entities"]:
        by_capture[row["capture_id"]].append(row)
    result = []
    for row in audit["entities"]:
        numeric = row["is_numeric_600_799_entity"] or row["is_numeric_600_799_trip"]
        if numeric:
            # A DMU movement is operationally paired with an electric movement
            # north of PITT. We only mark a counterpart observed when the same
            # snapshot contains a plausible nonnumeric Yellow movement touching
            # PITT/PCTR/ANTC; this is evidence, not an invented trip join.
            candidates = [c["entity_id"] for c in by_capture[row["capture_id"]]
                          if not (c["is_numeric_600_799_entity"] or c["is_numeric_600_799_trip"])
                          and c.get("static_line") == "Yellow"
                          and any(s in (c["first_station"], c["last_station"]) for s in ("PITT", "PCTR", "ANTC"))
                          and (not row.get("origin_event_time") or not c.get("origin_event_time") or
                               abs(datetime.fromisoformat(row["origin_event_time"]).timestamp() - datetime.fromisoformat(c["origin_event_time"]).timestamp()) <= 900)]
            counterpart_observed = bool(candidates)
            status = "operational_telemetry" if counterpart_observed else "required_counterpart_not_observed"
            confidence = "medium" if counterpart_observed else "low"
            method = "bart_to_antioch_numeric_namespace_with_same_capture_counterpart_check"
            evidence = ["numeric_600_799_namespace", "BART note: BART-to-Antioch trip IDs are not coordinated with schedule trip IDs",
                        "mandatory electric transfer north of PITT"]
        elif row["static_trip_exists"]:
            status, confidence, method = "exact", "high", "trip_id+canonical_service_date"
            candidates, evidence = [row["trip_id"]], ["GTFS-RT trip_id equals static trips.txt trip_id", "canonical service date"]
            counterpart_observed = None
        else:
            status, confidence, method = "realtime_only", "low", "no_static_trip_id_match"
            candidates, evidence = [], ["GTFS-RT update retained", "no static GTFS trip_id match"]
            counterpart_observed = None
        result.append({"capture_id": row["capture_id"], "entity_id": row["entity_id"], "trip_id": row["trip_id"],
                       "service_date": row["service_date"], "association_status": status,
                       "association_confidence": confidence, "association_method": method,
                       "association_evidence": evidence, "candidate_trip_ids": candidates if not numeric else [],
                       "candidate_entity_ids": candidates if numeric else [], "rejected_candidate_ids": [],
                       "static_identity_status": "no_static_counterpart" if numeric or not row["static_trip_exists"] else "exact",
                       "counterpart_status": ("observed_candidate" if counterpart_observed else ("required_not_observed" if numeric else "not_applicable"))})
    return result


def build_audit() -> dict[str, Any]:
    static_paths = sorted((RESOURCES / "gtfs").glob("*.zip"))
    capture_dates = sorted({service_capture_date(p.name) for p in RESOURCES.glob("bart_live_*") if p.is_dir() and service_capture_date(p.name)})
    static_inventory = static_source_inventory(static_paths, capture_dates)
    static = read_static(Path(static_inventory["selected"]))
    manifest, supplemental, components = capture_manifest(static)
    static_dates = sorted({service_capture_date(r["capture_id"]) for r in manifest if service_capture_date(r["capture_id"])})
    static_rows = []
    for service_date in static_dates:
        for trip_id, trip in static["trips"].items():
            if service_active(static, trip.get("service_id", ""), service_date):
                row = static_trip(static, trip_id, service_date)
                if row: static_rows.append(row)
    feed_rows: list[dict[str, Any]] = []; entities: list[dict[str, Any]] = []; stop_rows: list[dict[str, Any]] = []; alerts: list[dict[str, Any]] = []; etd_manifest: list[dict[str, Any]] = []; etd_rows: list[dict[str, Any]] = []; matches: list[dict[str, Any]] = []
    for item in manifest:
        capture_id = item["capture_id"]; directory = Path(item["capture_directory"]); trip_path = directory / "trip_updates.pb"
        if trip_path.exists():
            feed, e_rows, s_rows = parse_trip_feed(trip_path, static, capture_id); feed_rows.append(feed); entities.extend(e_rows); stop_rows.extend(s_rows)
        alert_path = directory / "alerts.pb"
        if alert_path.exists(): alerts.extend(alert_summary(alert_path, capture_id))
        capture_etd = []
        for path in sorted((directory / "etd").glob("*.xml")) if (directory / "etd").exists() else []:
            em, rows = parse_etd(path, capture_id); etd_manifest.append(em); etd_rows.extend(rows); capture_etd.extend(rows)
        capture_stops = [r for r in stop_rows if r["capture_id"] == capture_id]
        capture_entities = [r for r in entities if r["capture_id"] == capture_id]
        matches.extend([{**row, "capture_id": capture_id} for row in match_etd_to_gtfs(capture_etd, capture_stops, capture_entities)])
    associations = explicit_associations({"entities": entities})
    association_index = {(r["capture_id"], r["entity_id"]): r for r in associations}
    for entity in entities:
        entity.update(association_index[(entity["capture_id"], entity["entity_id"])])
    projected_entities, duplicate_projection = project_duplicate_updates(entities)
    return {"static": static, "static_inventory": static_inventory, "manifest": manifest, "supplemental": supplemental,
            "components": components, "static_rows": static_rows, "feed_rows": feed_rows,
            "entities": entities, "stop_rows": stop_rows, "alerts": alerts, "etd_manifest": etd_manifest, "etd_rows": etd_rows, "matches": matches,
            "associations": associations, "projected_entities": projected_entities, "duplicate_projection": duplicate_projection,
            "service_dates": static_dates}


def dmu_report(audit: dict[str, Any]) -> dict[str, Any]:
    rows = [r for r in audit["entities"] if r["is_numeric_600_799_entity"] or r["is_numeric_600_799_trip"]]
    by_capture = defaultdict(list)
    for r in rows: by_capture[r["capture_id"]].append(r)
    ids_by_capture = {k: sorted({r["entity_id"] or r["trip_id"] for r in v}) for k, v in by_capture.items()}
    captures = sorted(ids_by_capture)
    consecutive = []
    for left, right in zip(captures, captures[1:]):
        consecutive.append({"earlier_capture": left, "later_capture": right, "shared_numeric_ids": sorted(set(ids_by_capture[left]) & set(ids_by_capture[right])),
                            "earlier_count": len(ids_by_capture[left]), "later_count": len(ids_by_capture[right])})
    return {"numeric_records": rows, "by_capture": ids_by_capture, "consecutive_capture_identity": consecutive,
            "summary": {"record_count": len(rows), "capture_count": len(by_capture), "unknown_route_count": sum(not r["has_route_id"] for r in rows),
                         "static_counterpart_count": sum(r["static_trip_exists"] for r in rows),
                         "electric_counterpart_observed_candidate_count": sum(r.get("counterpart_status") == "observed_candidate" for r in rows),
                         "required_counterpart_not_observed_count": sum(r.get("counterpart_status") == "required_not_observed" for r in rows),
                         "pittsburg_or_antioch_stop_count": sum(any(x in (r["first_station"], r["last_station"]) for x in ("PITT", "PCTR", "ANTC")) for r in rows)}}


def sfo_report(audit: dict[str, Any]) -> dict[str, Any]:
    static_rows = [r for r in audit["static_rows"] if "SFIA" in r["ordered_stations"] and "MLBR" in r["ordered_stations"]]
    rt = [r for r in audit["entities"] if any(s in (r["first_station"], r["last_station"]) for s in ("SFIA", "MLBR"))]
    etd = [r for r in audit["etd_rows"] if r["destination_abbreviation"] == "MLBR" or label_alias(r["destination_label"]) == "MLBR"]
    by_route = Counter(r["route_id"] for r in static_rows)
    red_direct = sum(r["route_id"] in {"7", "8"} for r in static_rows)
    yellow_direct = sum(r["route_id"] in {"1", "2"} for r in static_rows)
    return {"static_trips_serving_both_sfo_and_millbrae": static_rows, "gtfsrt_records_touching_sfo_or_millbrae": rt,
            "etd_predictions_labeled_millbrae": etd, "summary": {"static_both_count": len(static_rows), "gtfsrt_count": len(rt), "etd_count": len(etd),
                         "static_both_by_route": dict(sorted(by_route.items())), "static_red_direct_count": red_direct, "static_yellow_direct_count": yellow_direct,
                         "dedicated_static_shuttle_candidate": red_direct > 0,
                         "identity_observation": "Static GTFS contains explicit Red route 7/8 SFO–Millbrae trips plus Yellow route 1/2 patterns that include both stations. ETD rows do not expose GTFS trip IDs; any ETD association is operational evidence, not a stable trip identity.",
                         "operational_question_status": "unresolved_from_checked_in_fixtures",
                         "operational_question_limit": "Only one captured Yellow GTFS-RT stop row reaches MLBR; the checked-in captures do not establish whether every late-night Yellow movement has a realtime counterpart."}}


def omission_report(audit: dict[str, Any]) -> dict[str, Any]:
    by_capture = defaultdict(set)
    for r in audit["entities"]: by_capture[r["capture_id"]].add(r["trip_id"])
    records = []
    for item in audit["manifest"]:
        date = service_capture_date(item["capture_id"])
        if not date or item["capture_id"] not in by_capture: continue
        active = {r["trip_id"] for r in audit["static_rows"] if r["service_date"] == date.isoformat()}
        visible = by_capture[item["capture_id"]]
        for trip in sorted(active - visible):
            records.append({"capture_id": item["capture_id"], "trip_id": trip, "observation": "static_only_in_snapshot", "proven_absent": False,
                            "reason": "not present in this GTFS-RT snapshot; later ETD/GTFS-RT evidence is required before calling it canceled"})
    return {"records": records, "summary": {"static_only_snapshot_rows": len(records), "proven_absent_count": 0,
                                               "conclusion": "Omission is not treated as cancellation by this audit. The checked-in captures are snapshots, not a complete service-history stream; a false-positive/false-negative omission rate is therefore not estimable without labeled consecutive observations."}}


def static_validation(static: dict[str, Any], static_rows: list[dict[str, Any]], service_dates: list[datetime.date]) -> dict[str, Any]:
    duplicate_trip_ids = {trip_id: count for trip_id, count in Counter(r["trip_id"] for r in static["tables"].get("trips.txt", [])).items() if count > 1}
    bad_sequences = []
    bad_time_order = []
    after_midnight = 0
    for trip_id, rows in static["stop_times"].items():
        sequences = [int(r["stop_sequence"]) for r in rows if r.get("stop_sequence", "").isdigit()]
        if len(sequences) != len(set(sequences)) or sequences != sorted(sequences):
            bad_sequences.append({"trip_id": trip_id, "stop_sequences": sequences})
        previous = None
        for row in rows:
            arrival = time_seconds(row.get("arrival_time", "")); departure = time_seconds(row.get("departure_time", ""))
            if any((row.get(k, "") or "").split(":", 1)[0].isdigit() and int((row.get(k, "") or "").split(":", 1)[0]) >= 24 for k in ("arrival_time", "departure_time")):
                after_midnight += 1
            current = departure if departure is not None else arrival
            if previous is not None and current is not None and current < previous:
                bad_time_order.append({"trip_id": trip_id, "stop_id": row.get("stop_id", ""), "previous_seconds": previous, "current_seconds": current})
            if current is not None: previous = current
    routes = []
    for route_id, route in static["routes"].items():
        short = route.get("route_short_name", "")
        routes.append({"route_id": route_id, "route_short_name": short, "line": short.split("-", 1)[0].title(),
                       "direction": short.split("-", 1)[1] if "-" in short else "", "route_type": route.get("route_type", ""),
                       "route_long_name": route.get("route_long_name", ""), "is_bus_bridge": short.upper().startswith("BRIDGE")})
    special = {
        "pittsburg": sum("PITT" in r["ordered_stations"] for r in static_rows),
        "pittsburg_center": sum("PCTR" in r["ordered_stations"] for r in static_rows),
        "antioch": sum("ANTC" in r["ordered_stations"] for r in static_rows),
        "sfo": sum("SFIA" in r["ordered_stations"] for r in static_rows),
        "millbrae": sum("MLBR" in r["ordered_stations"] for r in static_rows),
        "sfo_and_millbrae": sum("SFIA" in r["ordered_stations"] and "MLBR" in r["ordered_stations"] for r in static_rows),
    }
    terminal_counts = Counter((r["line"], r["terminal_station"]) for r in static_rows)
    return {"source": static["path"], "feed_info": static["feed_info"], "service_dates": [d.isoformat() for d in service_dates],
            "route_mapping": routes, "duplicate_trip_ids": duplicate_trip_ids, "after_midnight_stop_time_fields": after_midnight,
            "impossible_stop_sequences": bad_sequences, "non_monotonic_scheduled_times": bad_time_order, "special_station_counts": special,
            "terminal_counts": [{"line": line, "terminal_station": terminal, "count": count} for (line, terminal), count in sorted(terminal_counts.items())],
            "bus_bridge_routes": [r for r in routes if r["is_bus_bridge"]],
            "conclusion": "Static GTFS contains explicit rail routes plus BridgeA/BridgeB bus routes. SFO/Millbrae occurs in ordinary Red/Yellow route patterns in these files; a separate dedicated shuttle trip is not identified by a unique shuttle route name."}


def local_schedule_epoch(service_date: str, value: str) -> float | None:
    seconds = time_seconds(value)
    if seconds is None: return None
    base = datetime.fromisoformat(service_date).replace(tzinfo=PACIFIC)
    return (base + timedelta(seconds=seconds)).timestamp()


def timing_rows(audit: dict[str, Any]) -> list[dict[str, Any]]:
    rows = []
    for entity in audit["entities"]:
        if not entity["static_trip_exists"]: continue
        service_date = parse_date(entity["service_date"].replace("-", "")) if entity.get("service_date") else None
        static_row = static_trip(audit["static"], entity["trip_id"], service_date)
        if not static_row: continue
        scheduled_by_stop = {r["stop_id"]: r for r in static_row["scheduled_stop_times"]}
        for stop in [s for s in audit["stop_rows"] if s["capture_id"] == entity["capture_id"] and s["entity_id"] == entity["entity_id"]]:
            scheduled = scheduled_by_stop.get(stop["stop_id"])
            if not scheduled or not stop["event_time"]: continue
            observed = datetime.fromisoformat(stop["event_time"]).timestamp()
            scheduled_epoch = local_schedule_epoch(service_date.isoformat(), scheduled["departure_time"] or scheduled["arrival_time"])
            if scheduled_epoch is None: continue
            rows.append({"capture_id": entity["capture_id"], "entity_id": entity["entity_id"], "trip_id": entity["trip_id"], "station": stop["station"],
                         "stop_id": stop["stop_id"], "line": static_row["line"], "scheduled_time": scheduled["departure_time"] or scheduled["arrival_time"],
                         "scheduled_epoch": iso(scheduled_epoch), "observed_epoch": stop["event_time"], "deviation_seconds": round(observed - scheduled_epoch),
                         "interpretation": "realtime_timing_correction; not a static_schedule_error"})
    return rows


def observation_rows(audit: dict[str, Any], omission: dict[str, Any]) -> list[dict[str, Any]]:
    rows = []
    for entity in audit["entities"]:
        category = "explicit_realtime_cancellation" if entity["schedule_relationship"] == "CANCELED" else ("static_trip_with_realtime_timing_correction" if entity["static_trip_exists"] else "realtime_only_trip")
        rows.append({"capture_id": entity["capture_id"], "source_path": entity["source_path"], "trip_id": entity["trip_id"], "entity_id": entity["entity_id"], "station": entity["first_station"], "category": category, "timestamp": entity["feed_timestamp"], "reason": entity["schedule_relationship"]})
    for row in omission["records"]:
        rows.append({"capture_id": row["capture_id"], "source_path": "", "trip_id": row["trip_id"], "entity_id": "", "station": "", "category": "static_only_trip", "timestamp": "", "reason": row["reason"]})
    for row in audit["matches"]:
        if row["method"] == "unmatched":
            etd = audit["etd_rows"][row["etd_index"]]
            rows.append({"capture_id": row["capture_id"], "source_path": etd["raw_source_path"], "trip_id": "", "entity_id": "", "station": etd["station"], "category": "etd_only_prediction", "timestamp": etd["absolute_prediction_time"], "reason": row.get("unmatched_reason", "")})
        elif row["confidence"] == "ambiguous":
            rows.append({"capture_id": row["capture_id"], "source_path": "", "trip_id": row["trip_id"], "entity_id": row["entity_id"], "station": row["station"], "category": "ambiguous", "timestamp": "", "reason": "multiple compatible candidates within the documented score margin"})
    return rows


def mitigation_matrix(audit: dict[str, Any], dmu: dict[str, Any], sfo: dict[str, Any], omission: dict[str, Any]) -> list[dict[str, Any]]:
    unknown_routes = sum(not r["has_route_id"] for r in audit["entities"])
    dmu_count = dmu["summary"]["record_count"]
    return [
        {"problem": "Static GTFS is the only stable trip-pattern identity for scheduled service", "evidence": f"{sum(r['static_trip_exists'] for r in audit['entities'])}/{len(audit['entities']) or 1} GTFS-RT trip updates map to static trip IDs; static rows retain ordered station sequences and 24:xx times.", "confidence": "high", "affected_consumers": "schedule and route planning", "proposed_source_of_truth": "static GTFS for pattern, line, direction, and published terminal", "proposed_fallback": "apply realtime timing/cancellation relationship only when trip_id maps", "known_failure_mode": "feed can be partial or use an unknown technical ID", "required_regression_fixtures": "all checked-in GTFS and trip-update captures"},
        {"problem": "Numeric 600–799 / DMU telemetry lacks a consistently joinable passenger identity", "evidence": f"{dmu_count} numeric 600–799 records; {dmu['summary']['unknown_route_count']} have no route ID; {dmu['summary']['static_counterpart_count']} map directly to static trip IDs; {dmu['summary']['electric_counterpart_observed_candidate_count']} have a same-capture electric candidate and {dmu['summary']['required_counterpart_not_observed_count']} do not.", "confidence": "medium", "affected_consumers": "terminal and realtime departure projection", "proposed_source_of_truth": "separate operational telemetry layer", "proposed_fallback": "heuristic association only with station, direction, and time provenance", "known_failure_mode": "same numeric ID may not persist across snapshots and a DMU record may have no electric partner in the captured snapshot", "required_regression_fixtures": "consecutive captures plus terminal capture"},
        {"problem": "ETD has no stable trip identity and is minute-rounded", "evidence": f"{len(audit['etd_rows'])} normalized ETD rows; {sum(r['status']=='leaving' for r in audit['etd_rows'])} are 'Leaving'; matching is one-to-one and time-bounded.", "confidence": "high", "affected_consumers": "diagnostics and corroboration", "proposed_source_of_truth": "GTFS-RT/static when identity exists", "proposed_fallback": "ETD corroboration only for unmatched operational observations", "known_failure_mode": "limited look-ahead, label changes, rounding, and request/feed skew", "required_regression_fixtures": "ETD boards with Leaving, canceled, dynamic, and SFO/Millbrae labels"},
        {"problem": "Omission-based cancellation is not proven by these snapshots", "evidence": omission["summary"]["conclusion"], "confidence": "high", "affected_consumers": "cancellation display", "proposed_source_of_truth": "explicit CANCELED relationship or alert", "proposed_fallback": "retain static service as unknown/possibly omitted within a bounded window", "known_failure_mode": "snapshot omission can be capture timing or partial feed behavior", "required_regression_fixtures": "labeled consecutive feeds with later appearance, disappearance, and explicit cancellation"},
        {"problem": "SFO–Millbrae service semantics require source-specific evidence", "evidence": f"static trips serving both={sfo['summary']['static_both_count']}; GTFS-RT records touching either={sfo['summary']['gtfsrt_count']}; ETD MLBR-labeled rows={sfo['summary']['etd_count']}.", "confidence": "medium", "affected_consumers": "late-night routing and station boards", "proposed_source_of_truth": "actual static trip pattern where present; otherwise operational shuttle evidence", "proposed_fallback": "keep a provenance-marked transfer/estimate, never a universal ETD truth", "known_failure_mode": "SFO/Millbrae destination labels may describe a disruption or transfer service", "required_regression_fixtures": "night static feed, SFO/MLBR ETD boards, and matching realtime captures"},
        {"problem": "Missing route IDs and partial stop lists make route/terminal inference ambiguous", "evidence": f"{unknown_routes} of {len(audit['entities'])} trip updates lack route IDs; {sum(bool(r['has_partial_stop_list']) for r in audit['entities'])} appear shorter than their static stop list.", "confidence": "high", "affected_consumers": "terminal matching and line classification", "proposed_source_of_truth": "static trip ID mapping, then station/platform evidence", "proposed_fallback": "classify as unknown rather than extending a trip to its published terminal", "known_failure_mode": "platform stop IDs can be present without a known passenger parent", "required_regression_fixtures": "unknown route, missing sequence, and short stop-list entities"},
    ]


def write_report(audit: dict[str, Any], dmu: dict[str, Any], sfo: dict[str, Any], omission: dict[str, Any], mitigations: list[dict[str, Any]]) -> None:
    static = audit["static"]
    feed_rows = audit["feed_rows"]
    timing = audit["timing_rows"]
    timing_deviations = [r["deviation_seconds"] for r in timing]
    association_counts = Counter(r["association_status"] for r in audit["associations"])
    lines = ["# BART data audit", "", "Generated from raw checked-in GTFS ZIP, GTFS-Realtime protobuf, and ETD XML files.", "JSON copies under `docs/` are inventoried as supplemental diagnostics only; they are not audit inputs.", "", "## Executive findings", "", f"- Directory captures inventoried: **{len(audit['manifest'])}**; supplemental raw/JSON sources inventoried separately: **{len(audit['supplemental'])}**.", f"- Raw GTFS-Realtime trip updates normalized: **{len(audit['entities'])}**; stop-time rows: **{len(audit['stop_rows'])}**.", f"- ETD predictions normalized: **{len(audit['etd_rows'])}** from **{len({r['capture_id'] for r in audit['etd_rows']})}** directory captures.", f"- Explicit realtime cancellations: **{sum(r['canceled_trip_update_count'] for r in feed_rows)}** trip updates; static-only snapshot rows are not called cancellations.", f"- Explicit association states: {dict(sorted(association_counts.items()))}; see `gtfsrt_associations.csv`.", "- Static GTFS should remain authoritative for published station order, line/direction, and terminal. Realtime is a timing/status overlay where a stable trip identity exists.", "- ETD is corroborating passenger-facing evidence only: it is minute-rounded, horizon-limited, and has no GTFS trip identity.", "", "## Capture inventory", "", "| Capture | GTFS-RT | ETD stations | Alerts | Feed timestamp | Flags |", "|---|---:|---:|---:|---|---|"]
    for row in audit["manifest"]:
        lines.append(f"| `{row['capture_id']}` | {row['gtfs_rt_trip_update_count']} trips / {row['gtfs_rt_stop_time_update_count']} stops | {row['etd_station_count']} | {row['alert_count']} | {row['gtfs_rt_feed_timestamp'] or 'missing'} | {row['flags']} |")
    p90 = sorted(abs(x) for x in timing_deviations)[max(0, int(len(timing_deviations) * .9) - 1)] if timing_deviations else 0
    inventory = audit["static_inventory"]
    lines += ["", "Captures marked with disruption or missing components remain in all machine-readable outputs. They are not silently excluded from totals.", "", "## Static GTFS validation", "", f"Static feed version: `{static['feed_info'].get('feed_version', '')}`, feed dates `{static['feed_info'].get('feed_start_date', '')}`–`{static['feed_info'].get('feed_end_date', '')}`. All **{len(inventory['sources'])}** checked-in ZIPs were parsed and compared. Selected source: `{inventory['selected']}`. Identical table content: **{inventory['identical_table_content']}**; see `static_sources.json` for hashes, counts, coverage, and the deterministic selection rule.", f"Active static trips across observed service dates: **{len(audit['static_rows'])}**. Duplicate trip IDs in `trips.txt`: **{len(static['trips']) - len(set(static['trips']))}** by ID key (the normalized table preserves service-date rows).", "", "The parser treats `24:xx:xx` as seconds after midnight rather than rolling it to the prior date. Each realtime update receives a canonical service date: GTFS-RT `start_date` when present, otherwise a documented local-capture fallback with the pre-03:00 post-midnight rule. Platform stop IDs normalize through `parent_station`, preserving the raw platform ID in stop-time records.", f"Mapped realtime timing observations: **{len(timing)}**; median deviation from static scheduled time: **{statistics.median(timing_deviations) if timing_deviations else 0:.0f}s**; p90 absolute deviation: **{p90:.0f}s**. These are operational timing corrections, not evidence that static schedule structure is wrong. See `static_realtime_timing.csv`.", "", "Special topology checks are reported from actual rows in `static_validation.json`, `sfo_millbrae.json`, and `static_trips.csv`; no synthetic trip is introduced by this audit.", "", "## GTFS-Realtime characterization", "", "| Metric | Count |", "|---|---:|"]
    metrics = [("trip updates", len(audit["entities"])), ("missing route IDs", sum(not r["has_route_id"] for r in audit["entities"])), ("stop rows missing sequences", sum(r["missing_stop_sequence_count"] for r in audit["entities"])), ("partial stop lists", sum(bool(r["has_partial_stop_list"]) for r in audit["entities"])), ("explicit CANCELED updates", sum(r["schedule_relationship"] == "CANCELED" for r in audit["entities"])), ("trip IDs absent from static", sum(not r["static_trip_exists"] for r in audit["entities"])), ("numeric 600–799 entity IDs", sum(r["is_numeric_600_799_entity"] for r in audit["entities"])), ("duplicate trip IDs within feed", sum(r["duplicate_trip_id_in_feed"] for r in audit["entities"])), ("stale origin predictions", sum(r["origin_prediction_stale"] for r in audit["entities"])), ("updates ending before static terminal", sum(r["ends_before_static_terminal"] for r in audit["entities"])), ("platform-without-parent station", sum(r["has_platform_but_no_station"] for r in audit["stop_rows"]))]
    for name, count in metrics:
        pct = f" ({count / len(audit['entities']):.1%})" if name not in {"stop rows missing sequences", "platform-without-parent station"} and audit["entities"] else ""
        lines.append(f"| {name} | {count}{pct} |" if pct else f"| {name} | {count} |")
    lines += ["", "A short stop list is recorded as an observation, not as proof of a short-turn: feeds may be partial. A static terminal mismatch is therefore a data-shape diagnostic until corroborated by later stops, ETD, or alert context.", "", "## DMU / 600–799 analysis", "", f"The audit found **{dmu['summary']['record_count']}** numeric 600–799 records across **{dmu['summary']['capture_count']}** captures. **{dmu['summary']['unknown_route_count']}** have no route ID and **{dmu['summary']['static_counterpart_count']}** map directly to static trip IDs. Consecutive-capture identity observations are in `dmu_consecutive.csv`; shared IDs are evidence of persistence only, not passenger identity.", "", "A DMU record without an electric/static counterpart remains operational telemetry. Because passengers must transfer north of PITT, a DMU observation is not modeled as an independent service: `gtfsrt_associations.csv` records whether a plausible electric counterpart was observed in the same snapshot. Missing counterpart evidence is `required_counterpart_not_observed`, not cancellation.", "", "## SFO–Millbrae", "", f"Static trips serving both SFO/SFIA and Millbrae: **{sfo['summary']['static_both_count']}**; of these, Red route 7/8 direct rows: **{sfo['summary']['static_red_direct_count']}**, Yellow route 1/2 rows: **{sfo['summary']['static_yellow_direct_count']}**. GTFS-RT records touching SFO or Millbrae: **{sfo['summary']['gtfsrt_count']}**. ETD rows labeled Millbrae/SFO-Millbrae: **{sfo['summary']['etd_count']}**.", "", "Status: **unresolved from the checked-in fixtures**. The static schedule and PDF establish published Yellow/Red patterns, but the captures contain only one Yellow GTFS-RT Millbrae stop row. That is insufficient to conclude whether every late-night Yellow movement has a realtime counterpart; the absence of more rows is not negative operational evidence.", "", "## Omission and cancellation", "", omission["summary"]["conclusion"], "", "The audit distinguishes `explicit CANCELED`, `static_only_in_snapshot`, and unmatched ETD/GTFS-RT rows. The omission rate remains unresolved because there is no labeled consecutive-feed series proving that a static-only trip was actually canceled or simply absent from a partial snapshot.", "", "## Cross-source matching", "", "ETD matching uses station, color-derived line, platform/direction where available, destination alias, and absolute time within 180 seconds. Greedy ordering is deterministic and each ETD/GTFS-RT row can be used once. `ambiguous` and unmatched rows remain in `matches.csv` with reasons. Duplicate GTFS-RT updates are retained raw and projected deterministically in `gtfsrt_projected_entities.csv` with decisions in `gtfsrt_duplicate_projection.csv`.", "", "## Mitigation recommendation matrix", "", "| Problem | Confidence | Proposed source of truth | Fallback |", "|---|---|---|---|"]
    for row in mitigations:
        lines.append(f"| {row['problem']} | {row['confidence']} | {row['proposed_source_of_truth']} | {row['proposed_fallback']} |")
    lines += ["", "Full machine-readable evidence is in this directory. Each normalized row contains capture/source provenance; static/realtime rows additionally retain entity, trip, station, and timestamp fields.", ""]
    (OUT / "report.md").write_text("\n".join(lines), encoding="utf-8")


def run(output: Path = OUT) -> dict[str, Any]:
    global OUT
    OUT = output
    audit = build_audit()
    dmu = dmu_report(audit); sfo = sfo_report(audit); omission = omission_report(audit)
    audit["static_validation"] = static_validation(audit["static"], audit["static_rows"], audit["service_dates"])
    audit["timing_rows"] = timing_rows(audit)
    observations = observation_rows(audit, omission)
    mitigations = mitigation_matrix(audit, dmu, sfo, omission)
    output.mkdir(parents=True, exist_ok=True)
    write_csv(output / "capture_manifest.csv", audit["manifest"])
    write_csv(output / "supplemental_sources.csv", audit["supplemental"])
    write_csv(output / "capture_components.csv", audit["components"])
    write_csv(output / "static_trips.csv", flatten_static(audit["static_rows"]))
    write_csv(output / "gtfsrt_feeds.csv", audit["feed_rows"])
    write_csv(output / "gtfsrt_entities.csv", audit["entities"])
    write_csv(output / "gtfsrt_associations.csv", audit["associations"])
    write_csv(output / "gtfsrt_projected_entities.csv", audit["projected_entities"])
    write_csv(output / "gtfsrt_duplicate_projection.csv", audit["duplicate_projection"])
    write_csv(output / "gtfsrt_stop_times.csv", audit["stop_rows"])
    write_csv(output / "alerts.csv", audit["alerts"])
    write_csv(output / "etd_boards.csv", audit["etd_manifest"])
    write_csv(output / "etd_predictions.csv", audit["etd_rows"])
    write_csv(output / "matches.csv", audit["matches"])
    write_csv(output / "dmu_records.csv", dmu["numeric_records"])
    write_csv(output / "dmu_consecutive.csv", dmu["consecutive_capture_identity"])
    write_csv(output / "omission_records.csv", omission["records"])
    write_csv(output / "mitigations.csv", mitigations)
    write_csv(output / "static_realtime_timing.csv", audit["timing_rows"])
    write_csv(output / "observations.csv", observations)
    (output / "static_validation.json").write_text(json.dumps(audit["static_validation"], indent=2, default=str), encoding="utf-8")
    (output / "dmu.json").write_text(json.dumps(dmu, indent=2, default=str), encoding="utf-8")
    (output / "sfo_millbrae.json").write_text(json.dumps(sfo, indent=2, default=str), encoding="utf-8")
    (output / "omission_cancellation.json").write_text(json.dumps(omission, indent=2, default=str), encoding="utf-8")
    (output / "static_sources.json").write_text(json.dumps(audit["static_inventory"], indent=2), encoding="utf-8")
    write_report(audit, dmu, sfo, omission, mitigations)
    summary = {"capture_count": len(audit["manifest"]), "supplemental_source_count": len(audit["supplemental"]), "capture_component_count": len(audit["components"]), "static_source_count": len(audit["static_inventory"]["sources"]), "static_sources_identical_table_content": audit["static_inventory"]["identical_table_content"], "selected_static_source": audit["static_inventory"]["selected"], "static_trip_rows": len(audit["static_rows"]), "gtfsrt_entities": len(audit["entities"]), "gtfsrt_associations": len(audit["associations"]), "gtfsrt_projected_entities": len(audit["projected_entities"]), "gtfsrt_stop_rows": len(audit["stop_rows"]), "etd_predictions": len(audit["etd_rows"]), "matches": len(audit["matches"]), "dmu_records": dmu["summary"]["record_count"], "static_only_snapshot_rows": omission["summary"]["static_only_snapshot_rows"], "timing_rows": len(audit["timing_rows"]), "observation_rows": len(observations)}
    (output / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    return summary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    audit_parser = sub.add_parser("audit", help="read all checked-in fixtures and write evidence/report files")
    audit_parser.add_argument("--output", type=Path, default=OUT)
    args = parser.parse_args(argv)
    if args.command == "audit":
        print(json.dumps(run(args.output), indent=2))
        return 0
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
