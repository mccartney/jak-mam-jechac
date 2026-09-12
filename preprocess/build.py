#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Grzegorz Olędzki
"""Turn the zbiorkom.live Warsaw GTFS feed into one compact JSON file per line.

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
from datetime import date, datetime, timedelta, timezone

FEED_URL = "https://cdn.zbiorkom.live/gtfs/warsaw.zip"
COORD_DP = 5  # ~1.1 m; plenty for drawing, and trims a lot of bytes

# A depot leg is a stop that is both un-boardable and un-alightable AND named for a
# depot. The flag alone won't do: ~24k trips block boarding at their first stop (loop
# termini like "Metro Młociny"), and only ~7k of those are actually depots.
DEPOT_STOP_PREFIX = "zajezdnia"


class Feed:
    """Opens GTFS tables from either an unpacked directory or a .zip, the same way."""

    def __init__(self, path):
        self.zip = zipfile.ZipFile(path) if zipfile.is_zipfile(path) else None
        self.dir = None if self.zip else path

    def rows(self, table):
        """Yield each row of <table>.txt as a dict (DictReader). utf-8-sig drops any BOM.

        A table GTFS marks optional (calendar.txt here) may simply be absent; yield nothing.
        """
        try:
            if self.zip is not None:
                raw = self.zip.open(table + ".txt")
                stream = io.TextIOWrapper(raw, encoding="utf-8-sig", newline="")
            else:
                stream = open(os.path.join(self.dir, table + ".txt"), encoding="utf-8-sig", newline="")
        except (KeyError, FileNotFoundError):
            return
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


def trip_entry(trip, stops):
    """One trip as the app sees it.

    `rev` is whether passengers can use it at all: a depot leg blocks both boarding and
    alighting, so fewer than two usable stops means nobody can ride it. `depot` says which
    end sits in a depot — what the driver reads as "wyjazd"/"zjazd". Together these replace
    the old `exc`/`var` pair, which this feed doesn't carry and which said the wrong thing.
    """
    ordered = trip["stops"]
    usable = sum(1 for s in ordered if not s[4])

    def at_depot(s):
        return s[4] and stops.get(s[1], {}).get("n", "").lower().startswith(DEPOT_STOP_PREFIX)

    ends = (at_depot(ordered[0]), at_depot(ordered[-1])) if ordered else (False, False)
    entry = {
        "head": trip["head"],
        "dir": trip["dir"],
        "rev": int(usable >= 2),
        "shape": trip["shape"],
        "stops": [stop_entry(sid, a, d) for _seq, sid, a, d, _blocked in ordered],
        "_t": minutes(ordered[0][2]) if ordered else 1 << 30,
    }
    depot = {(True, False): "out", (False, True): "in", (True, True): "both"}.get(ends)
    if depot:
        entry["depot"] = depot
    return entry


def iso(yyyymmdd):
    """GTFS '20260915' -> '2026-09-15'."""
    return f"{yyyymmdd[0:4]}-{yyyymmdd[4:6]}-{yyyymmdd[6:8]}"


# Mon-Thu, Fri, Sat, Sun — indexed by date.weekday().
DAY_CODES = ("PcS", "PcS", "PcS", "PcS", "PtS", "SbS", "NdS")


def daytype(iso_date):
    """The day-type code the app's picker buckets brigades under.

    This feed's service_ids are opaque numbers, so the code comes from the weekday —
    the same way the app derives it (ServiceDay.of). A public holiday runs a Sunday
    roster that this weekday label won't reflect; the *chain* is unaffected, because the
    app resolves a date through `calendar`, never through this code.
    """
    return DAY_CODES[date.fromisoformat(iso_date).weekday()]


def service_dates(feed):
    """service_id -> sorted ISO dates it runs.

    GTFS splits this over two tables and this feed uses both: calendar.txt weekday
    patterns (metro only) and calendar_dates.txt exceptions (everything else — one
    additive row per running date).
    """
    weekdays = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    dates = {}
    for c in feed.rows("calendar"):
        runs = [c[d] == "1" for d in weekdays]
        day, end = date.fromisoformat(iso(c["start_date"])), date.fromisoformat(iso(c["end_date"]))
        while day <= end:
            if runs[day.weekday()]:
                dates.setdefault(c["service_id"], set()).add(day.isoformat())
            day += timedelta(days=1)
    for c in feed.rows("calendar_dates"):
        bucket = dates.setdefault(c["service_id"], set())
        bucket.add(iso(c["date"])) if c["exception_type"] == "1" else bucket.discard(iso(c["date"]))
    return {sid: sorted(ds) for sid, ds in dates.items() if ds}


# The picker is for bus drivers, so it lists buses only. GTFS route_type cleanly
# separates the modes (0 tram, 1 metro, 2 rail, 3 bus) — filtering on it drops trams
# (incl. the oddly-named "S" Zoo tram), metro (M1/M2) and trains (S1–S40) in one go.
BUS_ROUTE_TYPE = 3


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
            "brigade": t["brigade"],
            "shape": shape,
            "head": t["trip_headsign"],
            "dir": int(t["direction_id"]) if t["direction_id"] != "" else None,
            "stops": [],  # filled from stop_times
        }

    # When each service runs, restricted to the ones our trips use.
    dates_of = {sid: ds for sid, ds in service_dates(feed).items() if sid in services_seen}

    # stop_times: one streaming pass over the biggest table. Accumulate per wanted trip.
    # pickup/drop_off come along: they are the only sound signal of what a trip is for —
    # this feed has no variant_code, and a "non-revenue" flag would lie about it anyway.
    want_stops = set()
    for st in feed.rows("stop_times"):
        tid = st["trip_id"]
        trip = trips.get(tid)
        if trip is None:
            continue
        want_stops.add(st["stop_id"])
        trip["stops"].append((
            int(st["stop_sequence"]), st["stop_id"], st["arrival_time"], st["departure_time"],
            st["pickup_type"] == "1" and st["drop_off_type"] == "1",   # no passenger use here
        ))

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
    # line -> date -> brigade -> chain. Every date a trip runs on shares the one entry
    # dict, which is what makes the dedup below both exact and cheap.
    by_date = {line: {} for line in out}
    entries = []

    for trip in trips.values():
        line = trip["line"]
        trip["stops"].sort()  # by stop_sequence
        for _seq, sid, _a, _d, _blocked in trip["stops"]:
            used_stops[line].add(sid)
        if trip["shape"]:
            used_shapes[line].add(trip["shape"])
        entry = trip_entry(trip, stops)
        entries.append(entry)
        for day in dates_of.get(trip["service"], ()):
            by_date[line].setdefault(day, {}).setdefault(trip["brigade"], []).append(entry)

    for line, data in out.items():
        # Order each brigade's day by departure, then collapse dates running the very same
        # roster into one service. This feed has an opaque service_id per trip rather than
        # one per day-type, so without this a week of identical weekdays would be emitted
        # (and downloaded) several times over.
        for roster in by_date[line].values():
            for chain in roster.values():
                chain.sort(key=lambda e: e["_t"])
        seen = {}
        for day in sorted(by_date[line]):
            roster = by_date[line][day]
            sig = tuple(sorted((b, tuple(id(e) for e in ch)) for b, ch in roster.items()))
            if sig not in seen:
                seen[sig] = f"{day}:{daytype(day)}"
                data["services"][seen[sig]] = roster
            data["calendar"].setdefault(seen[sig], []).append(day)
        data["shapes"] = {
            sid: [[lon, lat] for _seq, lon, lat in sorted(raw_shapes.get(sid, []))]
            for sid in sorted(used_shapes[line])
        }
        data["stops"] = {sid: stops[sid] for sid in sorted(used_stops[line]) if sid in stops}

    for entry in entries:
        del entry["_t"]   # sort-only; every chain is ordered by now
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
    # Small selection index for the app's picker: every line with its display name/type
    # and the brigades running on each day-type. One cached file spares the picker from
    # pulling a multi-MB line file just to list its brigades.
    select = {"feedVersion": feed_version, "generated": generated, "lines": {}}
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

        if data["type"] == BUS_ROUTE_TYPE:
            by_day = {}  # day-type code -> set of brigades running that day
            for service_id, dates in data["calendar"].items():
                for day in dates:
                    by_day.setdefault(daytype(day), set()).update(data["services"][service_id])
            select["lines"][line] = {
                "name": data["name"],
                "type": data["type"],
                "brigades": {code: sorted(by_day[code]) for code in sorted(by_day)},
            }
        print(f"  {line:>5}  {len(payload):>8} B  "
              f"{len(data['services'])} services  {len(data['shapes'])} shapes  "
              f"{len(data['stops'])} stops", file=sys.stderr)

    with open(os.path.join(args.out, "manifest.json"), "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, ensure_ascii=False, separators=(",", ":"))
    with open(os.path.join(args.out, "brigades.json"), "w", encoding="utf-8") as fh:
        json.dump(select, fh, ensure_ascii=False, separators=(",", ":"))

    print(f"wrote {len(lines)} line file(s) + manifest.json + brigades.json to {args.out}/", file=sys.stderr)


if __name__ == "__main__":
    main()
