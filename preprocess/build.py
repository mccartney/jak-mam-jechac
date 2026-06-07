#!/usr/bin/env python3
"""Turn the mkuran Warsaw GTFS feed into one compact JSON file per line.

Reads the feed (a directory or the warsaw.zip directly) and emits, per line,
everything the app needs to draw a brigade's day: deduped shapes & stops, plus
each service's brigades as ordered trip chains (pull-out -> revenue -> pull-in).
Schedules change ~every 10 days, so this is meant to run in CI against the
daily-rebuilt feed; the output is uploaded to S3 and the app downloads one file
per line on demand. See README.md for the format and the S3/cron wiring.

Stdlib only. Single streaming pass over the big tables, so it fits in CI memory.
"""

import argparse
import csv
import gzip
import io
import json
import os
import sys
import zipfile
from datetime import datetime, timezone

FEED_URL = "https://mkuran.pl/gtfs/warsaw.zip"
COORD_DP = 5  # ~1.1 m; plenty for drawing, and trims a lot of bytes


class Feed:
    """Opens GTFS tables from either an unpacked directory or a .zip, the same way."""

    def __init__(self, path):
        self.zip = zipfile.ZipFile(path) if zipfile.is_zipfile(path) else None
        self.dir = None if self.zip else path

    def rows(self, table):
        """Yield each row of <table>.txt as a dict (DictReader). utf-8-sig drops any BOM."""
        if self.zip is not None:
            raw = self.zip.open(table + ".txt")
            stream = io.TextIOWrapper(raw, encoding="utf-8-sig", newline="")
        else:
            stream = open(os.path.join(self.dir, table + ".txt"), encoding="utf-8-sig", newline="")
        with stream as fh:
            yield from csv.DictReader(fh)

    def feed_version(self):
        for row in self.rows("feed_info"):
            return row["feed_version"]
        return None


def hhmm(gtfs_time):
    """'06:33:00' -> '06:33'. Keeps hours >= 24 (after-midnight trips) intact."""
    if not gtfs_time:
        return None
    h, m, _s = gtfs_time.split(":")
    return f"{int(h):02d}:{m}"


def minutes(gtfs_time):
    """Sort key: minutes since midnight, tolerant of hours >= 24."""
    h, m, _s = gtfs_time.split(":")
    return int(h) * 60 + int(m)


def stop_entry(stop_id, arr, dep):
    """Compact per-stop record: one time if arrival == departure, else both."""
    if arr == dep:
        return {"s": stop_id, "t": hhmm(arr)}
    return {"s": stop_id, "a": hhmm(arr), "d": hhmm(dep)}


def build(feed, wanted_lines):
    """Returns {line: line_dict}. wanted_lines is a set of route_short_names, or None for all."""
    # routes: keep only the lines we want, indexed by route_id (== short_name in this feed,
    # but group by short_name to be safe in case a line ever spans multiple route_ids).
    routes = {}        # route_id -> {line, name, type, color, textColor}
    line_of_route = {} # route_id -> line (route_short_name)
    for r in feed.rows("routes"):
        line = r["route_short_name"]
        if wanted_lines is not None and line not in wanted_lines:
            continue
        routes[r["route_id"]] = {
            "line": line,
            "name": r["route_long_name"],
            "type": int(r["route_type"]),
            "color": r["route_color"],
            "textColor": r["route_text_color"],
        }
        line_of_route[r["route_id"]] = line

    # trips for those routes. Collect what we need + the set of trip/shape ids to pull later.
    trips = {}          # trip_id -> trip meta (+ line, service, brigade)
    want_shapes = set()
    services_seen = set()
    for t in feed.rows("trips"):
        if t["route_id"] not in routes:
            continue
        tid = t["trip_id"]
        shape = t["shape_id"] or None
        if shape:
            want_shapes.add(shape)
        services_seen.add(t["service_id"])
        trips[tid] = {
            "line": line_of_route[t["route_id"]],
            "service": t["service_id"],
            "brigade": t["block_short_name"],
            "shape": shape,
            "head": t["trip_headsign"],
            "dir": int(t["direction_id"]) if t["direction_id"] != "" else None,
            "exc": int(t["exceptional"]) if t["exceptional"] != "" else 0,
            "var": t["variant_code"],
            "stops": [],  # filled from stop_times
        }

    # calendar: service_id -> [dates], restricted to services our trips use.
    calendar = {}
    for c in feed.rows("calendar_dates"):
        sid = c["service_id"]
        if sid not in services_seen or c["exception_type"] != "1":
            continue
        d = c["date"]
        calendar.setdefault(sid, []).append(f"{d[0:4]}-{d[4:6]}-{d[6:8]}")
    for dates in calendar.values():
        dates.sort()

    # stop_times: one streaming pass over the biggest table. Accumulate per wanted trip.
    want_stops = set()
    for st in feed.rows("stop_times"):
        tid = st["trip_id"]
        trip = trips.get(tid)
        if trip is None:
            continue
        want_stops.add(st["stop_id"])
        trip["stops"].append(
            (int(st["stop_sequence"]), st["stop_id"], st["arrival_time"], st["departure_time"])
        )

    # stops: only the ones referenced.
    stops = {}
    for s in feed.rows("stops"):
        sid = s["stop_id"]
        if sid not in want_stops:
            continue
        stops[sid] = {
            "n": s["stop_name"],
            "lat": round(float(s["stop_lat"]), COORD_DP),
            "lon": round(float(s["stop_lon"]), COORD_DP),
        }

    # shapes: streaming pass, only referenced ones; ordered by point sequence.
    raw_shapes = {}  # shape_id -> [(seq, lon, lat)]
    for sh in feed.rows("shapes"):
        sid = sh["shape_id"]
        if sid not in want_shapes:
            continue
        raw_shapes.setdefault(sid, []).append(
            (int(sh["shape_pt_sequence"]),
             round(float(sh["shape_pt_lon"]), COORD_DP),
             round(float(sh["shape_pt_lat"]), COORD_DP))
        )

    # Assemble per line.
    out = {}
    for route in routes.values():
        out[route["line"]] = {
            "line": route["line"],
            "name": route["name"],
            "type": route["type"],
            "color": route["color"],
            "textColor": route["textColor"],
            "calendar": {},
            "shapes": {},
            "stops": {},
            "services": {},
        }

    used_shapes = {line: set() for line in out}
    used_stops = {line: set() for line in out}
    used_services = {line: set() for line in out}

    for trip in trips.values():
        line = trip["line"]
        bucket = out[line]["services"].setdefault(trip["service"], {})
        used_services[line].add(trip["service"])
        trip["stops"].sort()  # by stop_sequence
        for _seq, sid, _a, _d in trip["stops"]:
            used_stops[line].add(sid)
        if trip["shape"]:
            used_shapes[line].add(trip["shape"])
        first_time = trip["stops"][0][2] if trip["stops"] else "99:99:99"
        entry = {
            "var": trip["var"],
            "head": trip["head"],
            "dir": trip["dir"],
            "exc": trip["exc"],
            "shape": trip["shape"],
            "stops": [stop_entry(sid, a, d) for _seq, sid, a, d in trip["stops"]],
            "_t": minutes(first_time) if trip["stops"] else 1 << 30,
        }
        bucket.setdefault(trip["brigade"], []).append(entry)

    # Order each brigade's trips by departure; drop the sort-only key; attach shared tables.
    for line, data in out.items():
        for brigades in data["services"].values():
            for chain in brigades.values():
                chain.sort(key=lambda e: e["_t"])
                for e in chain:
                    del e["_t"]
        data["calendar"] = {s: calendar.get(s, []) for s in sorted(used_services[line])}
        data["shapes"] = {
            sid: [[lon, lat] for _seq, lon, lat in sorted(raw_shapes.get(sid, []))]
            for sid in sorted(used_shapes[line])
        }
        data["stops"] = {sid: stops[sid] for sid in sorted(used_stops[line]) if sid in stops}

    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--feed", required=True,
                    help="path to the unpacked GTFS directory OR the warsaw.zip")
    ap.add_argument("--out", default="out", help="output directory (default: ./out)")
    ap.add_argument("--lines", nargs="*", default=None,
                    help="limit to these line numbers (route_short_name), e.g. --lines 504 222; "
                         "omit to build every line")
    ap.add_argument("--gzip", action="store_true",
                    help="also write <line>.json.gz next to each file (what S3 should serve)")
    args = ap.parse_args()

    feed = Feed(args.feed)
    feed_version = feed.feed_version()
    wanted = set(args.lines) if args.lines else None
    print(f"feed_version: {feed_version}", file=sys.stderr)
    print(f"building lines: {'ALL' if wanted is None else ' '.join(sorted(wanted))}", file=sys.stderr)

    lines = build(feed, wanted)

    if wanted is not None:
        missing = wanted - lines.keys()
        if missing:
            print(f"WARNING: no such line(s) in feed: {' '.join(sorted(missing))}", file=sys.stderr)

    lines_dir = os.path.join(args.out, "lines")
    os.makedirs(lines_dir, exist_ok=True)
    generated = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    manifest = {"feedVersion": feed_version, "generated": generated, "lines": {}}
    for line, data in sorted(lines.items()):
        data["feedVersion"] = feed_version
        payload = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        path = os.path.join(lines_dir, f"{line}.json")
        with open(path, "wb") as fh:
            fh.write(payload)
        if args.gzip:
            with gzip.open(path + ".gz", "wb") as fh:
                fh.write(payload)
        manifest["lines"][line] = {"name": data["name"], "type": data["type"], "bytes": len(payload)}
        print(f"  {line:>5}  {len(payload):>8} B  "
              f"{len(data['services'])} services  {len(data['shapes'])} shapes  "
              f"{len(data['stops'])} stops", file=sys.stderr)

    with open(os.path.join(args.out, "manifest.json"), "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, ensure_ascii=False, separators=(",", ":"))

    print(f"wrote {len(lines)} line file(s) + manifest.json to {args.out}/", file=sys.stderr)


if __name__ == "__main__":
    main()
