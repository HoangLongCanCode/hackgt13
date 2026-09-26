"""OpenStreetMap speed-limit lookup tests (perception.realtime.speed_limit), plain Python, no GPU, no internet.

AI Spatial Driving Copilot. Run from perception_engine/:

    python tests/test_speed_limit.py

A fake Overpass server (http.server on 127.0.0.1, a free port) answers with ways built in local metres around a
fixed origin, honouring the query's around: radius. Lookups use a synthetic clock (now_ms) so the refresh, gap,
backoff and staleness rules are checked without waiting; only the HTTP round trips run in real time. The highway test
holds each answer until a synthetic latency has passed (a car at 30-36 m/s, 2-3 s Overpass round trips). The last test
runs the server's NavWorker with the fake nav relay: speedLimit is attached to navigation.packet (schema-valid),
null until the first answer, and absent with --speed-limits off.
"""
from __future__ import annotations

import json
import math
import os
import re
import sys
import threading
import time
import traceback
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace
from typing import Any, Callable, Optional

ENGINE_ROOT = Path(__file__).resolve().parents[1]              # perception_engine/
sys.path.insert(0, str(ENGINE_ROOT))
os.environ.setdefault("YOLO_AUTOINSTALL", "False")

from perception.realtime.speed_limit import (  # noqa: E402
    MATCH_MAX_M, QUERY_RADIUS_M, REUSE_RADIUS_M, USER_AGENT, OsmSpeedLimits, normalize_name, packet_inputs,
    parse_mph)

SAMPLES = ENGINE_ROOT / "contracts" / "samples" / "v2"
LAT0, LNG0 = 33.7700, -84.3900                                  # origin of the fake world (local metres)
M_LAT = 111_194.9                                              # metres per degree of latitude (R = 6371 km)
M_LNG = M_LAT * math.cos(math.radians(LAT0))


def ll(east: float, north: float, lat0: float = LAT0, lng0: float = LNG0) -> tuple[float, float]:
    return lat0 + north / M_LAT, lng0 + east / (M_LAT * math.cos(math.radians(lat0)))


def way(way_id: int, pts_m: list[tuple[float, float]], **tags: str) -> dict[str, Any]:
    """A way through local (east, north) metre points; tag keys use _ for : (maxspeed_forward)."""
    return {"id": way_id, "pts": pts_m, "tags": {"highway": "residential", **{k.replace("_", ":"): v
                                                                          for k, v in tags.items()}}}


def seg_dist(p: tuple[float, float], a: tuple[float, float], b: tuple[float, float]) -> float:
    dx, dy = b[0] - a[0], b[1] - a[1]
    ll2 = dx * dx + dy * dy
    t = 0.0 if ll2 == 0 else max(0.0, min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / ll2))
    return math.hypot(a[0] + t * dx - p[0], a[1] + t * dy - p[1])


class FakeOverpass:
    """Overpass stand-in: POST data=<query>; answers the ways of `world` within the query's around: radius."""

    def __init__(self):
        self.world: list[dict[str, Any]] = []
        self.status = 200
        self.delay_s = 0.0
        self.hold: Optional[threading.Event] = None     # when a test sets it, each answer waits for this event
        self.requests: list[dict[str, Any]] = []
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode()
                query = urllib.parse.parse_qs(body).get("data", [""])[0]
                m = re.search(r"around:([\d.]+),(-?[\d.]+),(-?[\d.]+)", query)
                around = tuple(float(g) for g in m.groups()) if m else (0.0, LAT0, LNG0)
                fake.requests.append({"query": query, "ua": self.headers.get("User-Agent"), "around": around})
                if fake.delay_s:
                    time.sleep(fake.delay_s)
                hold = fake.hold
                if hold is not None:
                    hold.wait(10)
                if fake.status != 200:
                    data = b"<html>busy</html>"
                    self.send_response(fake.status)
                else:
                    data = json.dumps(fake.answer(*around)).encode()
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                with_suppress(lambda: self.wfile.write(data))

            def log_message(self, *args):
                pass
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.httpd.daemon_threads = True
        self.url = f"http://127.0.0.1:{self.httpd.server_address[1]}/api/interpreter"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def answer(self, radius: float, lat: float, lng: float) -> dict[str, Any]:
        c = ((lng - LNG0) * M_LNG, (lat - LAT0) * M_LAT)
        els = []
        for w in self.world:
            pts = w["pts"]
            if min(seg_dist(c, a, b) for a, b in zip(pts, pts[1:])) > radius:
                continue
            geom = [dict(zip(("lat", "lon"), ll(*p))) for p in pts]
            els.append({"type": "way", "id": w["id"], "geometry": geom, "tags": w["tags"]})
        return {"version": 0.6, "generator": "fake", "elements": els}

    def close(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()


def with_suppress(fn: Callable) -> None:
    try:
        fn()
    except OSError:
        pass


def settle(sl: OsmSpeedLimits, timeout: float = 5.0) -> None:
    """Wait (real time) until the background request finished; the next lookup applies it."""
    t0 = time.time()
    while sl.job is not None and not sl.job.done.is_set():
        if time.time() - t0 > timeout:
            raise AssertionError("request did not finish")
        time.sleep(0.01)


def ask(sl: OsmSpeedLimits, east: float, north: float, heading: Optional[float] = None,
        road: Optional[str] = None, now: int = 0) -> Optional[dict]:
    """Lookup, and if that started a request, wait for it and ask again at the same time."""
    lat, lng = ll(east, north)
    r = sl.lookup(lat, lng, heading, road, now)
    if sl.job is not None:
        settle(sl)
        r = sl.lookup(lat, lng, heading, road, now)
    return r


def mph(r: Optional[dict]) -> Optional[int]:
    return None if r is None else r["valueMph"]


# ============================================================================= tests
def test_parse_and_names() -> str:
    for v, want in [("35 mph", 35), ("35mph", 35), (" 45 MPH ", 45), ("5 mph", 5), ("85 mph", 85), ("50", None),
                    ("4 mph", None), ("90 mph", None), ("signals", None), ("none", None), ("US:urban", None),
                    ("35 mph;45 mph", None), ("30 km/h", None), ("", None), (None, None), (35, None)]:
        assert parse_mph(v) == want, f"parse_mph({v!r}) = {parse_mph(v)} != {want}"
    assert normalize_name("Peachtree St. NE") == "peachtree street northeast"
    assert normalize_name("I-75 N") == "i 75 north"
    assert normalize_name("North Ave NW") == normalize_name("North Avenue Northwest")
    assert normalize_name(None) == ""
    live = json.loads((SAMPLES / "navigation.packet.live.json").read_text(encoding="utf-8"))
    lat, lng, heading, road = packet_inputs(live)
    assert (round(lat, 5), round(lng, 5), heading, road) == (33.77652, -84.39615, 9.6, "Mock Avenue"), \
        "current road = the step before the active maneuver (routeState.roadName is the next road)"
    slow = json.loads(json.dumps(live))
    slow["packet"]["progress"]["speedMps"] = 0.3
    slow["packet"]["progress"]["offRoute"] = True
    assert packet_inputs(slow)[2:] == (None, None), "no heading at standstill, no road name off route"
    no_bearing = json.loads(json.dumps(live))
    no_bearing["packet"]["progress"].update(heading=0.0, speedMps=6.0)
    assert packet_inputs(no_bearing)[2] is None, "heading 0 = unknown (PROTOCOL_v2), not due north"
    del no_bearing["packet"]["progress"]["heading"]
    assert packet_inputs(no_bearing)[2] is None, "missing heading = unknown"
    assert packet_inputs({"packet": {"progress": {"currentLocation": None}}}) is None
    return "mph only (unitless = km/h ignored), names normalized, packet inputs (heading 0 / missing = unknown)"


def test_nearest_road_only(fake: FakeOverpass) -> str:
    """The nearest road's own value: a parallel road with a limit is never borrowed; unitless is ignored."""
    parallel = way(2, [(15, -300), (15, 300)], name="Service Drive", maxspeed="45 mph")
    out = []
    for tags, want in [({}, None), ({"maxspeed": "50"}, None), ({"maxspeed": "signals"}, None),
                       ({"maxspeed": "35 mph"}, 35)]:
        fake.world = [way(1, [(0, -300), (0, 300)], name="Main Street", **tags), parallel]
        sl = OsmSpeedLimits(fake.url)
        r = ask(sl, 2, 0, heading=0.0)
        assert mph(r) == want, f"{tags}: {r}"
        out.append(f"{tags.get('maxspeed', '-')}->{mph(r)}")
    assert r == {"valueMph": 35, "source": "osm", "roadName": "Main Street", "wayId": 1, "queriedAtMs": 0}, r
    fake.world = [parallel]
    assert ask(OsmSpeedLimits(fake.url), -20, 0) is None, "no road within 25 m"
    return ", ".join(out) + "; nothing within 25 m -> None"


def test_tie_breaks(fake: FakeOverpass) -> str:
    """Roads within 8 m of the nearest: route road name first, then heading (one-way aware, :forward/:backward)."""
    fake.world = [way(1, [(-3, -300), (-3, 300)], name="Oak Street", maxspeed="25 mph"),
                  way(2, [(3, -300), (3, 300)], name="Main Street", maxspeed="35 mph")]
    assert mph(ask(OsmSpeedLimits(fake.url), 0.5, 0, road="Main St")) == 35
    assert mph(ask(OsmSpeedLimits(fake.url), 0.5, 0, road="Oak St.")) == 25
    assert mph(ask(OsmSpeedLimits(fake.url), 0.5, 0, road=None)) == 35, "no name: the nearer one"
    # divided road: northbound carriageway drawn south->north, southbound north->south, both one-way
    fake.world = [way(3, [(-4, -300), (-4, 300)], name="Connector", highway="motorway", maxspeed="55 mph"),
                  way(4, [(4, 300), (4, -300)], name="Connector", highway="motorway", maxspeed="45 mph")]
    assert mph(ask(OsmSpeedLimits(fake.url), 1, 0, heading=2.0)) == 55
    assert mph(ask(OsmSpeedLimits(fake.url), 1, 0, heading=178.0)) == 45, "wrong-way carriageway loses"
    # two-way road with directional limits (geometry south->north)
    fake.world = [way(5, [(0, -300), (0, 300)], name="Ponce Road", maxspeed_forward="40 mph",
                      maxspeed_backward="30 mph")]
    got = [mph(ask(OsmSpeedLimits(fake.url), 1, 0, heading=h)) for h in (10.0, 190.0, None, 90.0)]
    assert got == [40, 30, None, None], f"forward / backward / unknown direction: {got}"
    return "name > heading > distance; one-way carriageways; maxspeed:forward/backward " + str(got)


def test_cache_refresh_and_gap(fake: FakeOverpass) -> str:
    """No request within 40 m / 30 s (local re-selection); >= 40 m or >= 30 s refreshes; >= 5 s between requests."""
    fake.world = [way(1, [(0, -2000), (0, 2000)], name="Long Road", maxspeed="40 mph")]
    fake.requests.clear()
    sl = OsmSpeedLimits(fake.url)
    assert sl.lookup(*ll(1, 0), None, None, 0) is None, "nothing before the first answer"
    settle(sl)
    assert mph(sl.lookup(*ll(1, 0), None, None, 100)) == 40 and len(fake.requests) == 1
    for n, t in ((10, 1000), (30, 2000), (39, 3000)):
        assert mph(sl.lookup(*ll(1, n), None, None, t)) == 40, f"cache at {n} m"
    assert len(fake.requests) == 1, f"no request within 40 m: {len(fake.requests)}"
    assert mph(sl.lookup(*ll(1, 45), None, None, 4000)) == 40 and sl.job is None, "45 m, but < 5 s after the last"
    r = sl.lookup(*ll(1, 45), None, None, 6000)
    assert mph(r) == 40 and sl.job is not None, "45 m: refresh started, answer from the cache meanwhile"
    settle(sl)
    assert mph(sl.lookup(*ll(1, 46), None, None, 6100)) == 40 and len(fake.requests) == 2
    around = fake.requests[-1]["around"]
    assert abs((around[1] - LAT0) * M_LAT - 45) < 1, f"re-queried at the new position {around}"
    for t in (20_000, 35_000):
        sl.lookup(*ll(1, 46), None, None, t)
    assert len(fake.requests) == 2 and sl.job is None, "29 s old: no refresh yet"
    sl.lookup(*ll(1, 46), None, None, 36_500)
    settle(sl)
    assert len(fake.requests) == 3, "a 30 s old answer is refreshed in place"
    q = fake.requests[0]
    assert q["ua"] == USER_AGENT and "out tags geom" in q["query"] and "motorway_link" in q["query"], q
    return f"{len(fake.requests)} requests for 11 lookups (40 m / 30 s / 5 s rules); User-Agent and query ok"


def test_backoff(fake: FakeOverpass) -> str:
    """429 / 503 / timeouts: 30 s, then 60 s (doubling to 5 min); a success resets it."""
    fake.world = [way(1, [(0, -300), (0, 300)], maxspeed="30 mph")]
    fake.requests.clear()
    fake.status = 429
    sl = OsmSpeedLimits(fake.url, timeout_s=0.5)
    p = ll(1, 0)
    sl.lookup(*p, None, None, 0)
    settle(sl)
    sl.lookup(*p, None, None, 1000)                       # applies the failure: next try at 31 s
    assert sl.failures == 1 and sl.last_error == "HTTP 429", sl.info()
    for t in (6000, 20_000, 30_500):
        sl.lookup(*p, None, None, t)
    assert len(fake.requests) == 1, f"backing off: {len(fake.requests)} requests"
    fake.status = 503
    sl.lookup(*p, None, None, 31_500)
    settle(sl)
    sl.lookup(*p, None, None, 32_000)                     # second failure: 60 s
    assert len(fake.requests) == 2 and sl.last_error == "HTTP 503", sl.info()
    sl.lookup(*p, None, None, 91_000)
    assert len(fake.requests) == 2, "60 s after the second failure"
    fake.status, fake.delay_s = 200, 1.5                  # slower than the 0.5 s client timeout
    sl.lookup(*p, None, None, 92_500)
    settle(sl)
    sl.lookup(*p, None, None, 93_000)                     # third failure: 120 s
    assert len(fake.requests) == 3 and sl.last_error == "timeout", sl.info()
    sl.lookup(*p, None, None, 212_000)
    assert len(fake.requests) == 3
    fake.delay_s = 0.0
    assert mph(ask(sl, 1, 0, now=213_500)) == 30 and sl.last_error is None and sl.backoff_ms == 30_000, sl.info()
    time.sleep(1.2)                                       # let the timed-out handler finish
    return f"{len(fake.requests)} requests, failures {sl.failures}: 429, 503, timeout, then recovered"


def test_non_blocking(fake: FakeOverpass) -> str:
    """lookup() returns at once while the server sleeps; at most one request in flight."""
    fake.world = [way(1, [(0, -300), (0, 300)], maxspeed="25 mph")]
    fake.requests.clear()
    fake.delay_s = 1.0
    sl = OsmSpeedLimits(fake.url)
    worst = 0.0
    for k in range(20):
        t0 = time.perf_counter()
        r = sl.lookup(*ll(1, k * 50), None, None, k * 1000)     # 50 m / 1 s apart: every 5th would request
        worst = max(worst, time.perf_counter() - t0)
        assert r is None
    assert worst < 0.05, f"lookup blocked {worst * 1000:.0f} ms"
    assert len(fake.requests) <= 1 and sl.requests == 1, f"one request in flight, {sl.requests} started"
    settle(sl)
    fake.delay_s = 0.0
    return f"slowest lookup {worst * 1000:.1f} ms while the server slept 1 s; 1 request"


def test_staleness(fake: FakeOverpass) -> str:
    """No value from data older than 60 s, or more than 150 m from where it was selected."""
    fake.world = [way(1, [(0, -2000), (0, 2000)], maxspeed="45 mph")]
    sl = OsmSpeedLimits(fake.url)
    assert mph(ask(sl, 1, 0, now=0)) == 45
    fake.status = 503                                     # every refresh fails from now on
    assert mph(sl.lookup(*ll(1, 45), None, None, 1000)) == 45
    assert mph(sl.lookup(*ll(1, 190), None, None, 2000)) == 45, "re-selected from the cache (within 200 m)"
    assert mph(sl.lookup(*ll(1, 300), None, None, 3000)) == 45, "beyond the cache, 110 m from the last selection"
    assert sl.lookup(*ll(1, 400), None, None, 4000) is None, "210 m from the last selection"
    assert mph(sl.lookup(*ll(1, 0), None, None, 45_000)) == 45, "45 s old data still used"
    settle(sl)
    assert sl.lookup(*ll(1, 0), None, None, 61_000) is None, "61 s old data"
    fake.status = 200
    return "150 m and 60 s limits hold while refreshes fail"


def test_soundness_guard(fake: FakeOverpass) -> str:
    """A position too close to the edge of the fetched area is not re-selected: a road outside it could be nearer."""
    assert QUERY_RADIUS_M >= REUSE_RADIUS_M + MATCH_MAX_M, "default radii: every reuse passes the guard"
    fake.world = [way(1, [(12, 30), (12, 300)], name="Main Street", maxspeed="35 mph"),
                  way(2, [(-3, 62), (-3, 300)], name="Oak Street", maxspeed="25 mph")]
    fake.requests.clear()
    sl = OsmSpeedLimits(fake.url, query_radius_m=60.0)    # Oak Street starts 62 m from (0, 0): not in the answer
    assert ask(sl, 0, 0) is None, "no road within 25 m"
    r = sl.lookup(*ll(0, 55), None, None, 1000)
    assert r is None, f"55 m out: Main Street (12 m) is the nearest fetched road, Oak Street (8 m) was not: {r}"
    assert mph(sl.lookup(*ll(0, 38), None, None, 2000)) == 35, "38 m out: nothing unfetched can be within 20 m"
    assert len(fake.requests) == 1
    assert mph(ask(sl, 0, 55, now=6000)) == 25, "after a refresh centred at the car: Oak Street"
    fake.requests.clear()
    sl = OsmSpeedLimits(fake.url)                         # the default 250 m answer holds both roads
    assert ask(sl, 0, 0) is None
    assert mph(sl.lookup(*ll(0, 55), None, None, 1000)) == 25 and len(fake.requests) == 1
    return "60 m answer: None at 55 m (not the fetched road's 35), 25 after the refresh; 250 m answer: 25 at once"


def drive(fake: FakeOverpass, sl: OsmSpeedLimits, speed: float, latency_s: float, period_s: float,
          duration_s: float) -> list[Optional[int]]:
    """Lookups every period_s (synthetic clock) for a car driving east along y = 0 at `speed` m/s. Each Overpass
    answer is held until latency_s after its request, so the first packet at or after that time uses it."""
    fake.hold = threading.Event()
    out = []
    try:
        for k in range(int(duration_s / period_s) + 1):
            now = int(round(k * period_s * 1000))
            if sl.job is not None and now - sl.job.now_ms >= latency_s * 1000:
                fake.hold.set()
                settle(sl)
                fake.hold = threading.Event()
            out.append(mph(sl.lookup(*ll(-400 + speed * now / 1000, 0), 90.0, None, now)))
    finally:
        fake.hold.set()
        settle(sl)
        fake.hold = None
    return out


def test_highway_latency(fake: FakeOverpass) -> str:
    """30-36 m/s with 2-3 s Overpass latency at 1 Hz (live) and 2 Hz (sim) packets: after the first answer every
    packet carries the limit, and requests stay >= 5 s apart."""
    fake.world = [way(1, [(-500, 3), (4000, 3)], name="Interstate 75", highway="motorway", maxspeed="65 mph")]
    out = []
    for speed, latency_s, period_s in ((30.0, 2.0, 1.0), (30.0, 3.0, 1.0), (30.0, 3.0, 0.5), (36.0, 3.0, 1.0)):
        fake.requests.clear()
        sl = OsmSpeedLimits(fake.url)
        duration_s = 90.0
        got = drive(fake, sl, speed, latency_s, period_s, duration_s)
        case = f"{speed:.0f} m/s, {latency_s:.0f} s, {1 / period_s:.0f} Hz"
        first = next((i for i, v in enumerate(got) if v is not None), None)
        assert first is not None and first * period_s <= latency_s + period_s, f"{case}: first answer {first} {got}"
        assert all(v == 65 for v in got[first:]), f"{case}: null or wrong after the first answer: {got}"
        assert len(fake.requests) <= duration_s / 5 + 1, f"{case}: {len(fake.requests)} requests"
        out.append(f"{case}: {len(got) - first}/{len(got)} x 65, {len(fake.requests)} req")
    return "; ".join(out)


def test_nav_worker(fake: FakeOverpass) -> str:
    """server.NavWorker attaches speedLimit to navigation.packet: null first, then the value; absent when off."""
    import jsonschema
    from perception.realtime.server import NavWorker, build_parser
    from tests.fake_nav_relay import FakeNavRelay
    schema = json.loads((ENGINE_ROOT / "contracts" / "schemas" / "navigation.packet.schema.json").read_text("utf-8"))
    validator = jsonschema.Draft202012Validator(schema)
    city = json.loads((SAMPLES / "navigation.packet.sim_city.json").read_text(encoding="utf-8"))
    loc = city["packet"]["progress"]["currentLocation"]
    lat0, lng0 = loc["lat"], loc["lng"]
    east0, north0 = (lng0 - LNG0) * M_LNG, (lat0 - LAT0) * M_LAT     # the sample position in fake-world metres
    fake.world = [way(9, [(east0 + 2, north0 - 300), (east0 + 2, north0 + 300)], name="Mock Avenue",
                      maxspeed="30 mph")]
    fake.delay_s = 0.3
    a = build_parser().parse_args([])
    assert a.speed_limits == "off" and a.speed_limit_endpoint is None
    a = build_parser().parse_args(["--speed-limits", "osm", "--speed-limit-endpoint", fake.url])
    got: list[dict] = []

    class Srv:
        def post(self, fn, *args):
            fn(*args)

        def broadcast_nav(self, data):
            got.append(json.loads(data))

        def broadcast_error(self, code, msg):
            raise AssertionError(f"relay error {code}: {msg}")

        def broadcast_hello(self, *_):
            pass

        def media_pts_now(self):
            return None
    base = dict(nav_session=None, nav_route=None, nav_destination="GT", nav_origin=None, nav_provider="mock",
                phase1_dir=None, node="node")
    results = {}
    for mode in ("osm", "off"):
        got.clear()
        w = NavWorker(Srv(), FakeNavRelay, SimpleNamespace(**base, speed_limits=mode,
                                                           speed_limit_endpoint=a.speed_limit_endpoint))
        w.start()
        try:
            for k in range(8):
                w.submit_trip({"timestampMs": 1790000000000 + 1000 * k, "location": loc, "heading": 7.4,
                               "speedMps": 7.6}, "c1")
                time.sleep(0.1)
            t0 = time.time()
            while len(got) < 8 and time.time() - t0 < 5:
                time.sleep(0.02)
        finally:
            w.stop()
            w.join(2)
        assert len(got) == 8, f"{mode}: {len(got)} packets"
        for m in got:
            errs = list(validator.iter_errors(m))
            assert not errs, f"{mode}: {errs[0].message} at {list(errs[0].absolute_path)}"
        results[mode] = [m.get("speedLimit", "absent") for m in got]
    osm = results["osm"]
    assert osm[0] is None, f"first packet before the answer: {osm[0]}"
    last = osm[-1]
    assert isinstance(last, dict) and last["valueMph"] == 30 and last["wayId"] == 9 and last["source"] == "osm" \
        and last["roadName"] == "Mock Avenue", f"value after the answer: {osm}"
    assert all(v == "absent" for v in results["off"]), f"--speed-limits off: {results['off']}"
    fake.delay_s = 0.0
    n_null = sum(v is None for v in osm)
    return f"osm: {n_null} null then {last['valueMph']} mph ({len(osm)} packets, schema-valid); off: field absent"


def main() -> int:
    fake = FakeOverpass()
    tests: list[tuple[str, Callable[[], str]]] = [
        ("parse + names", test_parse_and_names),
        ("nearest road only", lambda: test_nearest_road_only(fake)),
        ("tie breaks", lambda: test_tie_breaks(fake)),
        ("cache, refresh, gap", lambda: test_cache_refresh_and_gap(fake)),
        ("backoff", lambda: test_backoff(fake)),
        ("non-blocking", lambda: test_non_blocking(fake)),
        ("staleness", lambda: test_staleness(fake)),
        ("soundness guard", lambda: test_soundness_guard(fake)),
        ("highway latency", lambda: test_highway_latency(fake)),
        ("nav worker attaches speedLimit", lambda: test_nav_worker(fake)),
    ]
    failed = 0
    for name, fn in tests:
        fake.status, fake.delay_s, fake.hold = 200, 0.0, None
        t0 = time.perf_counter()
        try:
            info = fn()
            print(f"PASS {name} ({(time.perf_counter() - t0) * 1000:.0f} ms): {info}", flush=True)
        except Exception as e:  # noqa: BLE001
            failed += 1
            print(f"FAIL {name}: {type(e).__name__}: {e}", flush=True)
            traceback.print_exc()
    fake.close()
    print(f"{len(tests) - failed} passed, {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
