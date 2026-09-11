#!/usr/bin/env python3
"""Inspect GTFS-Realtime protobuf feeds.

Requires: pip install gtfs-realtime-bindings
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

from google.protobuf.json_format import MessageToJson
from google.transit import gtfs_realtime_pb2


def parse_feed(path: Path) -> gtfs_realtime_pb2.FeedMessage:
    feed = gtfs_realtime_pb2.FeedMessage()
    feed.ParseFromString(path.read_bytes())
    return feed


def epoch_text(seconds: int) -> str:
    if not seconds:
        return "(missing)"
    return datetime.fromtimestamp(seconds, timezone.utc).isoformat()


def trip_updates(feed: gtfs_realtime_pb2.FeedMessage) -> Iterable:
    return (entity.trip_update for entity in feed.entity if entity.HasField("trip_update"))


def stop_matches(update, stop_prefix: str) -> bool:
    return any(stop.stop_id.startswith(stop_prefix) for stop in update.stop_time_update)


def print_alerts(feed: gtfs_realtime_pb2.FeedMessage) -> None:
    print(f"alerts={sum(entity.HasField('alert') for entity in feed.entity)}")
    for entity in feed.entity:
        if not entity.HasField("alert"):
            continue
        alert = entity.alert
        header = alert.header_text.translation[0].text if alert.HasField("header_text") else ""
        description = (
            alert.description_text.translation[0].text
            if alert.HasField("description_text")
            else ""
        )
        print(f"alert id={entity.id!r} header={header!r}")
        if description:
            print(f"  description: {description}")
        for informed in alert.informed_entity:
            print(
                "  informed: "
                f"route={informed.route_id!r} "
                f"trip={informed.trip.trip_id!r} "
                f"stop={informed.stop_id!r}"
            )


def inspect_trip_feed(feed: gtfs_realtime_pb2.FeedMessage, stop_prefix: str | None) -> None:
    updates = list(trip_updates(feed))
    canceled = [
        update.trip.trip_id
        for update in updates
        if update.trip.schedule_relationship == gtfs_realtime_pb2.TripDescriptor.CANCELED
    ]
    missing_sequences = sum(
        1
        for update in updates
        for stop in update.stop_time_update
        if not stop.HasField("stop_sequence")
    )
    print(f"timestamp={feed.header.timestamp} ({epoch_text(feed.header.timestamp)})")
    print(f"entities={len(feed.entity)} trip_updates={len(updates)}")
    print(f"canceled_trip_updates={len(canceled)}")
    if canceled:
        print("  " + ", ".join(canceled))
    print(f"stop_time_updates_missing_stop_sequence={missing_sequences}")

    if stop_prefix:
        matches = [update for update in updates if stop_matches(update, stop_prefix)]
        print(f"trips_matching_stop_prefix={stop_prefix!r}: {len(matches)}")
        for update in matches:
            relationship = update.trip.schedule_relationship
            stops = ",".join(stop.stop_id for stop in update.stop_time_update)
            print(
                f"  trip={update.trip.trip_id} relationship={relationship} "
                f"stops={len(update.stop_time_update)} [{stops}]"
            )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("feed", type=Path, help="GTFS-RT protobuf (.pb) file")
    parser.add_argument(
        "--alerts",
        action="store_true",
        help="print alert text and informed entities instead of trip summary",
    )
    parser.add_argument(
        "--stop-prefix",
        help="also list trips containing stop IDs with this prefix, e.g. S40 or S50",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="print the complete protobuf as GTFS-RT JSON",
    )
    args = parser.parse_args()

    try:
        feed = parse_feed(args.feed)
    except Exception as exc:  # noqa: BLE001 - make CLI errors concise
        print(f"Unable to decode {args.feed}: {exc}", file=sys.stderr)
        return 2

    if args.json:
        print(MessageToJson(feed, preserving_proto_field_name=True))
    elif args.alerts:
        print_alerts(feed)
    else:
        inspect_trip_feed(feed, args.stop_prefix)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
