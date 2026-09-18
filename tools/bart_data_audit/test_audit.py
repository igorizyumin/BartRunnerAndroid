import tempfile
import unittest
from datetime import datetime
from pathlib import Path

from .audit import (RESOURCES, build_audit, canonical_service_date, label_alias, match_etd_to_gtfs,
                    explicit_associations, parse_capture_time, parse_etd, parse_request_time,
                    parse_trip_feed, project_duplicate_updates, read_static,
                    static_source_inventory, time_seconds)
from .pdf_schedule import extract_schedule, parse_clock


class AuditHelpersTest(unittest.TestCase):
    def test_after_midnight_time_is_not_wrapped(self):
        self.assertEqual(time_seconds("24:15:00"), 24 * 3600 + 15 * 60)

    def test_capture_time_uses_pacific_offset(self):
        parsed = parse_capture_time("bart_live_20260913_201652")
        self.assertEqual(parsed.strftime("%Y-%m-%d %H:%M:%S"), "2026-09-13 20:16:52")

    def test_destination_aliases(self):
        self.assertEqual(label_alias("SFO/Millbrae"), "MLBR")
        self.assertEqual(label_alias("Pittsburg/Bay Point"), "PITT")

    def test_etd_request_time_with_pdt_suffix(self):
        self.assertEqual(parse_request_time("09/13/2026", "08:16:52 PM PDT").isoformat(), "2026-09-13T20:16:52-07:00")

    def test_etd_match_is_one_to_one(self):
        etd = [{"status": "future", "absolute_prediction_time": "2026-09-13T20:20:00-07:00", "station": "12TH", "line": "Yellow", "color": "YELLOW", "platform": "3", "direction": "North", "destination_abbreviation": "ANTC", "destination_label": "Antioch"}]
        stops = [{"station": "12TH", "event_time": "2026-09-13T20:20:30-07:00", "platform_suffix": "3", "entity_id": "e1", "trip_id": "t1"}, {"station": "12TH", "event_time": "2026-09-13T20:20:45-07:00", "platform_suffix": "3", "entity_id": "e2", "trip_id": "t2"}]
        entities = [{"entity_id": "e1", "static_line": "Yellow", "static_terminal": "ANTC", "route_id": "1N"}, {"entity_id": "e2", "static_line": "Yellow", "static_terminal": "ANTC", "route_id": "1N"}]
        matches = match_etd_to_gtfs(etd, stops, entities)
        self.assertEqual(sum(m["etd_index"] == 0 for m in matches), 1)
        self.assertEqual(sum(m["method"] == "gtfsrt_only" for m in matches), 1)

    def test_raw_protobuf_and_xml_fixtures_decode(self):
        static = read_static(RESOURCES / "gtfs" / "bart_google_transit.zip")
        feed, entities, stops = parse_trip_feed(
            RESOURCES / "bart_live_20260913_201652" / "trip_updates.pb", static, "bart_live_20260913_201652"
        )
        board, predictions = parse_etd(
            RESOURCES / "bart_live_20260913_201652" / "etd" / "12th.xml", "bart_live_20260913_201652"
        )
        self.assertGreater(feed["trip_update_count"], 0)
        self.assertEqual(feed["trip_update_count"], len(entities))
        self.assertGreater(len(stops), 0)
        self.assertEqual(board["prediction_count"], len(predictions))
        self.assertTrue(any(row["absolute_prediction_time"] for row in predictions if row["status"] == "future"))

    def test_canonical_service_date_preserves_start_date_conflicts(self):
        service_date, source, conflict = canonical_service_date("20260912", "bart_live_20260913_011500")
        self.assertEqual((service_date, source, conflict), ("2026-09-12", "gtfsrt_start_date", True))
        service_date, source, conflict = canonical_service_date("", "bart_live_20260913_011500")
        self.assertEqual((service_date, source, conflict), ("2026-09-12", "capture_local_date_pre_03_service_day", False))

    def test_static_inventory_compares_all_sources(self):
        paths = sorted((RESOURCES / "gtfs").glob("*.zip"))
        inventory = static_source_inventory(paths, [datetime(2026, 9, 11).date()])
        self.assertEqual(len(inventory["sources"]), len(paths))
        self.assertTrue(inventory["selected"] in {str(p) for p in paths})
        self.assertIn("sha256", inventory["sources"][0])
        self.assertIn("diff_from_selected", inventory["sources"][0])
        self.assertTrue(all(not source["diff_from_selected"] for source in inventory["sources"]))

    def test_duplicate_projection_is_cancel_first_and_stable(self):
        base = {"capture_id": "c", "trip_id": "t", "feed_timestamp": "2026-09-13T20:00:00+00:00", "source_path": "x"}
        rows = [
            {**base, "entity_id": "b", "schedule_relationship": "SCHEDULED"},
            {**base, "entity_id": "a", "schedule_relationship": "CANCELED"},
        ]
        projected, decisions = project_duplicate_updates(rows)
        self.assertEqual(projected[0]["entity_id"], "a")
        self.assertEqual(sum(d["selected"] for d in decisions), 1)

    def test_dmu_association_requires_same_capture_counterpart_evidence(self):
        common = {"capture_id": "c", "service_date": "2026-09-13", "static_trip_exists": False,
                  "static_line": "", "first_station": "PCTR", "last_station": "ANTC",
                  "is_numeric_600_799_entity": True, "is_numeric_600_799_trip": True}
        result = explicit_associations({"entities": [{**common, "entity_id": "600", "trip_id": "600"}]})
        self.assertEqual(result[0]["association_status"], "required_counterpart_not_observed")

        electric = {"capture_id": "c", "service_date": "2026-09-13", "static_trip_exists": True,
                    "static_line": "Yellow", "first_station": "PITT", "last_station": "ANTC",
                    "is_numeric_600_799_entity": False, "is_numeric_600_799_trip": False,
                    "entity_id": "e1", "trip_id": "scheduled-trip"}
        result = explicit_associations({"entities": [{**common, "entity_id": "600", "trip_id": "600"}, electric]})
        self.assertEqual(result[0]["association_status"], "operational_telemetry")
        self.assertEqual(result[0]["counterpart_status"], "observed_candidate")

    def test_checked_in_edge_case_signals_are_present(self):
        static = read_static(RESOURCES / "gtfs" / "bart_google_transit.zip")
        feed, entities, stops = parse_trip_feed(
            RESOURCES / "bart_live_20260912_192815" / "trip_updates.pb", static, "bart_live_20260912_192815"
        )
        self.assertTrue(all("service_date" in row and "service_date_source" in row for row in entities))
        self.assertTrue(any(row["is_numeric_600_799_entity"] for row in entities))
        self.assertTrue(any(not row["has_route_id"] for row in entities))
        self.assertTrue(any(not row["stop_sequence"] for row in stops))

    def test_full_audit_has_lossless_associations_and_projection(self):
        audit = build_audit()
        self.assertEqual(len(audit["associations"]), len(audit["entities"]))
        self.assertEqual(len(audit["projected_entities"]), 565)
        self.assertTrue(all(row["static_identity_status"] in {"exact", "no_static_counterpart"} for row in audit["associations"]))

    def test_official_pdf_cells_include_late_night_millbrae(self):
        cells, metadata = extract_schedule()
        self.assertEqual(len(metadata["pdfs"]), 2)
        self.assertEqual(parse_clock("12:01 AM"), 24 * 3600 + 60)
        self.assertGreater(len(cells), 10000)
        self.assertTrue(any(c["station"] == "MLBR" and c["seconds_after_midnight"] >= 21 * 3600 for c in cells))


if __name__ == "__main__":
    unittest.main()
