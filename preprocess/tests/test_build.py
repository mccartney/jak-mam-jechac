# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Grzegorz Olędzki
"""Behaviour tests for build.py, mostly over the synthetic feed in fixtures/feed."""

import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
import build  # noqa: E402

FEED = os.path.join(HERE, "fixtures", "feed")


class FakeFeed:
    """In-memory stand-in for build.Feed: {table: [row dicts]}."""

    def __init__(self, tables):
        self.tables = tables

    def rows(self, table):
        yield from self.tables.get(table, [])


class HelperTest(unittest.TestCase):
    def test_hhmm_pads_hours_and_keeps_after_midnight(self):
        self.assertEqual("06:33", build.hhmm("06:33:00"))
        self.assertEqual("06:00", build.hhmm("6:00:00"))
        self.assertEqual("25:05", build.hhmm("25:05:00"))
        self.assertIsNone(build.hhmm(""))

    def test_minutes_orders_unpadded_and_after_midnight_times(self):
        self.assertEqual(24 * 60 + 10, build.minutes("24:10:00"))
        self.assertLess(build.minutes("6:00:00"), build.minutes("10:00:00"))  # string order says otherwise

    def test_stop_entry_compact_forms(self):
        self.assertEqual({"s": "A", "t": "06:00"}, build.stop_entry("A", "06:00:00", "06:00:00", False))
        self.assertEqual({"s": "A", "a": "06:00", "d": "06:02"},
                         build.stop_entry("A", "06:00:00", "06:02:00", False))
        self.assertEqual({"s": "A", "t": "06:00", "r": 1}, build.stop_entry("A", "06:00:00", "06:00:00", True))

    def test_daytype_is_the_service_id_suffix(self):
        self.assertEqual("PtS", build.daytype("2026-11-20:PtS"))
        self.assertEqual("NdS", build.daytype("NdS"))


class BuildTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.out = build.build(build.Feed(FEED), None)
        cls.l504 = cls.out["504"]
        cls.chain = cls.l504["services"]["2026-11-20:PtS"]["1"]

    def test_chain_is_ordered_by_first_departure_across_midnight(self):
        self.assertEqual(["TX-OUT", "TP-DWC", "TP-KAB", "TP-NIGHT", "TX-IN"], [t["var"] for t in self.chain])

    def test_stops_are_ordered_by_numeric_stop_sequence(self):
        # sequences 1, 2, 9, 10 listed out of order in stop_times.txt
        self.assertEqual(["K1", "MID", "C1", "C2"], [s["s"] for s in self.chain[1]["stops"]])

    def test_request_stop_from_either_pickup_or_drop_off(self):
        north, south = self.chain[1], self.chain[2]
        self.assertEqual(1, north["stops"][1].get("r"))  # pickup_type 3
        self.assertEqual(1, south["stops"][1].get("r"))  # drop_off_type 3
        self.assertNotIn("r", north["stops"][0])

    def test_depot_moves_have_no_shape_or_direction(self):
        out = self.chain[0]
        self.assertEqual((None, None, 1), (out["shape"], out["dir"], out["exc"]))

    def test_calendar_keeps_only_added_dates_sorted_iso(self):
        self.assertEqual(["2026-11-13", "2026-11-20", "2026-11-27"], self.l504["calendar"]["2026-11-20:PtS"])

    def test_each_line_gets_only_its_own_services_shapes_and_stops(self):
        self.assertEqual({"2026-11-20:PtS", "2026-11-22:NdS"}, set(self.l504["calendar"]))
        self.assertEqual({"S_N", "S_S"}, set(self.l504["shapes"]))
        self.assertEqual({"C1", "C2", "D", "K1", "MID"}, set(self.l504["stops"]))
        self.assertEqual({"C1", "T1"}, set(self.out["9"]["stops"]))  # shared stop C1 in both

    def test_shape_points_are_lon_lat_in_sequence_rounded(self):
        self.assertEqual([[21.06825, 52.12923], [21.0244, 52.1806], [21.00372, 52.22944]],
                         self.l504["shapes"]["S_N"])

    def test_wanted_lines_limits_the_build(self):
        self.assertEqual(["9"], list(build.build(build.Feed(FEED), {"9", "nope"})))

    def test_zip_and_directory_feeds_build_the_same(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "warsaw.zip")
            with zipfile.ZipFile(path, "w") as z:
                for name in os.listdir(FEED):
                    z.write(os.path.join(FEED, name), name)
            self.assertEqual(self.out, build.build(build.Feed(path), None))


class EdgeCaseTest(unittest.TestCase):
    def feed(self, stop_times):
        return FakeFeed({
            "routes": [{"route_id": "1", "route_short_name": "1", "route_long_name": "x",
                        "route_type": "3", "route_color": "", "route_text_color": ""}],
            "trips": [{"route_id": "1", "service_id": "S:PcS", "trip_id": t, "trip_headsign": "h",
                       "direction_id": "", "shape_id": "", "exceptional": "", "block_short_name": "1",
                       "variant_code": t} for t in ("a", "b")],
            "calendar_dates": [{"service_id": "S:PcS", "date": "20261123", "exception_type": "1"}],
            "stop_times": stop_times,
            "stops": [{"stop_id": "X", "stop_name": "X", "stop_lat": "52", "stop_lon": "21"}],
            "shapes": [],
        })

    def test_trip_without_stop_times_sorts_last_with_no_stops(self):
        st = [{"trip_id": "b", "arrival_time": "05:00:00", "departure_time": "05:00:00", "stop_id": "X",
               "stop_sequence": "1", "pickup_type": "", "drop_off_type": ""}]
        chain = build.build(self.feed(st), None)["1"]["services"]["S:PcS"]["1"]
        self.assertEqual(["b", "a"], [t["var"] for t in chain])
        self.assertEqual([], chain[1]["stops"])
        self.assertEqual((None, 0), (chain[1]["dir"], chain[1]["exc"]))  # empty columns


class BrigadeIndexTest(unittest.TestCase):
    def test_index_lists_buses_only_with_day_types_per_date(self):
        with tempfile.TemporaryDirectory() as out:
            subprocess.run([sys.executable, os.path.join(HERE, "..", "build.py"), "--feed", FEED, "--out", out],
                           check=True, capture_output=True)
            with open(os.path.join(out, "brigades.json"), encoding="utf-8") as fh:
                index = json.load(fh)
                lines = index["lines"]
            with open(os.path.join(out, "manifest.json"), encoding="utf-8") as fh:
                manifest = json.load(fh)["lines"]
        self.assertEqual(["504"], list(lines))           # tram 9 is left out of the picker...
        self.assertIn("9", manifest)                     # ...but still built
        self.assertEqual({"NdS": ["1"], "PtS": ["1", "2"]}, lines["504"]["brigades"])
        self.assertEqual("NdS", index["days"]["2026-11-11"])  # Wednesday holiday runs Sunday service
        self.assertEqual("PtS", index["days"]["2026-11-20"])
        self.assertNotIn("2026-11-26", index["days"])         # removed by calendar exception
        self.assertNotIn("2026-11-23", index["days"])         # tram-only date


if __name__ == "__main__":
    unittest.main()
