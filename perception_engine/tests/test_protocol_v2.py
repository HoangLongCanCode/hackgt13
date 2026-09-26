"""Protocol v2 tests for the realtime Perception server (plain Python, no pytest needed).

AI Spatial Driving Copilot. Run from perception_engine/:

    python tests/test_protocol_v2.py                 # offline tests + a real server subprocess on a free port
    python tests/test_protocol_v2.py --offline       # schemas, samples, header, wire builders, nav worker only
    python tests/test_protocol_v2.py --url ws://127.0.0.1:8765/perception   # against an already running
                                                     # `--mode auto` server (nav tests need the fake relay)

Offline: every contracts/schemas/*.schema.json is a valid draft 2020-12 schema; every contracts/samples/v2/*.json
validates against the schema of its `type`; the SDC1 header round-trips (and rejects bad input with the right skip
reason); the wire builders produce schema-valid messages; the fast-lane geometry distance is sane; the NavWorker
calls the relay from one thread and broadcasts its packets.
Server (real engine, `--mode auto` + a fake nav relay): live loopback (20 frames under credits -> exactly one
wave-1 answer each with the right echo and ptsSeconds, geometry/fused distance on every vehicle, rotationDegrees
honoured, a credit-violating burst still answered once per frame, badHeader / decodeError skips, ping -> pong);
sim (takes over from the live client, results arrive AHEAD of the playback position, navigation.packet follows the
playback position, seek -> new session, pause and rate 0 stop analysis, unknown videoId -> perception.error, the
controller switching to a missing clip -> one error and a new session); takeover (implicit re-promotion keeps the
client's hello intrinsics). Live also covers a wrong-magic frame (skip badHeader) and a mid-session frame size change
(new session).
"""
from __future__ import annotations

import argparse
import asyncio
import contextlib
import json
import os
import socket
import subprocess
import sys
import threading
import time
import traceback
import urllib.request
from pathlib import Path
from types import SimpleNamespace
from typing import Any, Callable, Optional

ENGINE_ROOT = Path(__file__).resolve().parents[1]              # perception_engine/
CONTRACTS = ENGINE_ROOT / "contracts"                           # perception_engine/contracts/
sys.path.insert(0, str(ENGINE_ROOT))
os.environ.setdefault("YOLO_AUTOINSTALL", "False")

SCHEMAS = CONTRACTS / "schemas"
SAMPLES_V2 = CONTRACTS / "samples" / "v2"
from perception.common.paths import DATA_DIR  # noqa: E402  (PERCEPTION_DATA_DIR or perception_engine/data)

CITY = DATA_DIR / "bdd100k" / "videos" / "val" / "b1ff4656-0435391e.mov"
NAV_SESSION = "nav/demo_sessions/b1ff4656-0435391e"
VEHICLES = {"car", "truck", "bus", "train", "motorcycle", "bicycle", "pedestrian", "rider"}

RESULTS: list[tuple[str, bool, str]] = []


def check(cond: bool, msg: str) -> None:
    if not cond:
        raise AssertionError(msg)


def run_test(name: str, fn: Callable, *args) -> bool:
    t0 = time.perf_counter()
    try:
        info = fn(*args)
        RESULTS.append((name, True, info or ""))
        print(f"PASS {name} ({time.perf_counter() - t0:.1f} s) {info or ''}", flush=True)
        return True
    except Exception as e:
        RESULTS.append((name, False, f"{type(e).__name__}: {e}"))
        print(f"FAIL {name}: {type(e).__name__}: {e}", flush=True)
        traceback.print_exc()
        return False


def validators() -> dict[str, Any]:
    from perception.realtime.ws_probe import load_validators
    return load_validators(SCHEMAS)


def assert_valid(v: dict, msg: dict) -> None:
    t = msg.get("type")
    check(t in v, f"no schema for message type {t!r}")
    errs = list(v[t].iter_errors(msg))
    check(not errs, f"{t} invalid: {errs[0].message if errs else ''} at {list(errs[0].absolute_path) if errs else ''}")


# ============================================================================= offline
def t_schemas_wellformed() -> str:
    from jsonschema import Draft202012Validator
    names = sorted(p.name for p in SCHEMAS.glob("*.schema.json"))
    for p in SCHEMAS.glob("*.schema.json"):
        Draft202012Validator.check_schema(json.loads(p.read_text(encoding="utf-8")))
    need = {"perception.hello", "perception.frame", "perception.update", "perception.skip", "perception.stats",
            "perception.pong", "perception.error", "client.hello", "client.playback", "client.ping"}
    missing = need - set(validators())
    check(not missing, f"missing schemas for {missing}")
    return f"{len(names)} schemas"


def t_samples_validate() -> str:
    v = validators()
    n = 0
    types = set()
    for p in sorted(SAMPLES_V2.glob("*.json")):
        msg = json.loads(p.read_text(encoding="utf-8"))
        try:
            assert_valid(v, msg)
        except AssertionError as e:
            raise AssertionError(f"{p.name}: {e}")
        types.add(msg["type"])
        n += 1
    need = {"perception.hello", "perception.frame", "perception.update", "perception.skip", "perception.stats",
            "perception.pong", "perception.error", "client.hello", "client.playback", "client.ping"}
    check(need <= types, f"samples missing for {need - types}")
    check((SAMPLES_V2 / "uplink_header.example.txt").exists(), "uplink_header.example.txt missing")
    return f"{n} samples, types {sorted(types)}"


def t_header_roundtrip() -> str:
    from perception.realtime.wire import (UPLINK_HEADER, UplinkError, describe_uplink_header, pack_uplink_header,
                                          parse_uplink)
    check(UPLINK_HEADER.size == 24, "header must be 24 bytes")
    jpeg = b"\xff\xd8\xff\xe0" + b"x" * 100 + b"\xff\xd9"
    for fid, cap, rot in [(0, 0, 0), (1234, 123456789012, 90), (0xFFFFFFFF, -5, 180), (7, 2**62, 270)]:
        data = pack_uplink_header(fid, cap, rot) + jpeg
        h, body = parse_uplink(data)
        check((h.frame_id, h.capture_time_ns, h.rotation_degrees, h.flags, h.header_version) == (fid, cap, rot, 0, 1),
              f"round trip {fid} {cap} {rot} -> {h}")
        check(bytes(body) == jpeg, "payload mismatch")
        check(h.echo() == {"frameId": fid, "captureTimeNs": cap}, "echo")
    check(pack_uplink_header(2**32 + 5, 1)[8:12] == (5).to_bytes(4, "little"), "frameId wraps at 2^32")
    # layout: magic, version, flags, frameId, captureTimeNs, rotation, reserved (little endian)
    raw = pack_uplink_header(0x01020304, 0x1122334455667788, 90)
    check(raw[:4] == b"SDC1" and raw[4:6] == b"\x01\x00" and raw[8:12] == b"\x04\x03\x02\x01"
          and raw[12:20] == bytes.fromhex("8877665544332211") and raw[20:22] == b"\x5a\x00", f"layout {raw.hex()}")

    def reason(data):
        try:
            parse_uplink(data)
        except UplinkError as e:
            return e.reason, e.frame_id
        return None, None
    bad_magic = bytearray(pack_uplink_header(2002, 1) + jpeg)
    bad_magic[:4] = b"SDC2"
    check(reason(bytes(bad_magic)) == ("badHeader", 2002), "bad magic (>= 24 bytes) -> badHeader with frameId")
    check(reason(b"SDC1\x01\x00") == ("badHeader", None), "short header (< 24 bytes): no frameId")
    bad_ver = bytearray(pack_uplink_header(9, 1) + jpeg)
    bad_ver[4] = 2
    check(reason(bytes(bad_ver)) == ("badHeader", 9), "bad version -> badHeader with frameId")
    bad_rot = bytearray(pack_uplink_header(10, 1) + jpeg)
    bad_rot[20] = 45
    check(reason(bytes(bad_rot)) == ("badHeader", 10), "bad rotation")
    check(reason(pack_uplink_header(11, 1)) == ("decodeError", 11), "empty payload -> decodeError")
    try:
        pack_uplink_header(1, 1, 45)
        raise AssertionError("rotation 45 accepted")
    except ValueError:
        pass
    txt = describe_uplink_header(raw)
    check("captureTimeNs" in txt and "rotationDegrees" in txt and "53 44 43 31" in txt, "describe output")
    return "ok"


def _synthetic_result():
    from perception.common.schemas import (Detection, DistanceEstimate, FrameResult, LaneState, RoadGeometry,
                                           TrackState, TrafficLightState, TrafficSign)
    dets = [Detection("car", [600, 353, 663, 405.5], 0.9, 3), Detection("pedestrian", [100, 300, 130, 390], 0.7, 4),
            Detection("traffic light", [700, 100, 715, 140], 0.8, 1_000_001),
            Detection("traffic sign", [900, 200, 930, 230], 0.6, 1_000_002)]
    tracks = [TrackState(3, "car", dets[0].bbox, 12, ttc_s=4.2, approaching=True),
              TrackState(4, "pedestrian", dets[1].bbox, 3), TrackState(1_000_001, "traffic light", dets[2].bbox, 9),
              TrackState(1_000_002, "traffic sign", dets[3].bbox, 5)]
    lanes = LaneState(2, 3, [[[100.0, 700.0], [500.0, 400.0]], [[900.0, 700.0], [700.0, 400.0]]], 0.8)
    road = RoadGeometry(0.3, [[200, 719], [1000, 719], [700, 420], [580, 420]], 360.0, [640.0, 360.0],
                        [{"name": "ego_lane_center_near", "xy": [640.0, 650.0]},
                         {"name": "ego_path_centerline"}])
    return FrameResult(10, 0.3337, dets, tracks,
                       [DistanceEstimate(3, 20.1, "fused", 0.82), DistanceEstimate(4, 17.0, "geometry", 0.4)],
                       lanes, [TrafficLightState(1_000_001, "RED", dets[2].bbox, 0.91, 35.0)],
                       [TrafficSign(1_000_002, "speed_limit_45", dets[3].bbox, 0.7, 25.0)], road,
                       {"detect": 20.0, "total": 40.0})


def t_wire_offline() -> str:
    from perception.engine import SlowResult
    from perception.realtime.wire import (make_error, make_hello, make_pong, make_skip, make_stats, make_update,
                                          sign_class_to_wire, to_wire)
    v = validators()
    res = _synthetic_result()
    cam = {"focalPx": 700.0, "principalPoint": [640.0, 360.0], "horizonY": 360.0, "cameraHeightMeters": 1.3}
    meta = {"seq": 5, "sessionId": "s", "source": {"kind": "camera", "id": "tab"}, "image": {"width": 1280,
            "height": 720}, "camera": cam, "blockAges": {"detection": 0, "distance": 2, "lanes": 3},
            "distanceAgesMs": {3: 66.7, 4: 0.0}, "depthDetails": {3: {"lateral_m": -0.4}},
            "echo": {"frameId": 77, "captureTimeNs": 123456789}}
    f = to_wire(res, meta)
    assert_valid(v, f)
    objs = {o["id"]: o for o in f["objects"]}
    check(f["wave"] == 1 and f["echo"] == {"frameId": 77, "captureTimeNs": 123456789}, "wave/echo")
    check(objs[3]["distanceAgeMs"] == 66.7 and objs[3]["lateralMeters"] == -0.4, "carried distance + age")
    check(objs[4]["distanceMethod"] == "geometry" and objs[4]["distanceAgeMs"] == 0.0, "geometry distance age 0")
    check(objs[1_000_001]["lightState"] == "RED" and objs[1_000_001]["distanceMethod"] == "size_prior", "light")
    check(f["signs"][0]["signClass"] == "speedLimit45" == sign_class_to_wire("speed_limit_45"), "sign class")
    check(to_wire(res, {**meta, "echo": None})["echo"] is None, "echo null")
    slow = SlowResult(1, None, ["distance", "depth", "lanes"], [{"id": 3, "distanceMeters": 19.8,
                      "distanceMethod": "fused", "distanceConfidence": 0.8, "lateralMeters": -0.3}], True, res.lanes,
                      res.road, None, cam, {"distance": 50.0, "total": 70.0})
    u = make_update(slow, {"seq": 5, "sessionId": "s", "frameIndex": 10, "ptsSeconds": 0.3337, "echo": None,
                           "image": {"width": 1280, "height": 720}, "camera": cam, "processingMs": 150.0})
    assert_valid(v, u)
    check("signs" not in u and u["blocks"] == ["distance", "depth", "lanes"], "omitted blocks")
    desc = {"models": [{"block": "detection", "name": "x", "detail": "", "licence": ""}],
            "schedule": {"detection": {"lane": "fast", "every": 1, "phase": 0}}, "targetHz": 16.0,
            "image": {"width": 1280, "height": 720}}
    for mode, up, sim in [("live", {"maxInFlight": 2, "preferredWidth": 960, "preferredHeight": 540,
                                    "jpegQuality": 80, "header": "SDC1"}, None),
                          ("sim", None, {"lookaheadSeconds": 0.3, "videos": ["a"], "videoId": "a"}),
                          ("video", None, None)]:
        assert_valid(v, make_hello("s", {"kind": "video", "id": "a"}, desc, mode=mode, accepted_modes=[mode],
                                   uplink=up, sim=sim, role="controller"))
    assert_valid(v, make_stats("s", mode="live", window_s=3, output_fps=17, wave2_fps=8, distance_fps=8,
                               wave1_ms=[50, 60], wave2_ms=[], wave1_compute_ms=[40], wave2_compute_ms=[],
                               frames_in=10, frames_analysed=9, frames_skipped=1, updates=4, clients=1,
                               send_dropped=0, extra={"framesDropped": 0}))
    for r in ("superseded", "decodeError", "badHeader", "notAccepted", "sessionReset"):
        assert_valid(v, make_skip(12, r, "s"))
    assert_valid(v, make_pong(99))
    assert_valid(v, make_pong(None))
    for code in ("badMessage", "modeNotAvailable", "unknownVideo", "notUplinkClient", "internal"):
        assert_valid(v, make_error(code, "m", detail={"x": 1}))
    return "frame/update/hello/stats/skip/pong/error valid"


def t_geometry_distance() -> str:
    from perception.depth.geometry import Camera
    from perception.engine import GEOMETRY_MAX_CONFIDENCE, geometry_distance
    cam = Camera(700.0, 640.0, 360.0, 1.3, 1280, 720)
    # a car 20 m ahead: height 1.5 m -> 52.5 px, width 1.8 m -> 63 px, bottom at y_h + f*H/Z = 405.5
    z, conf, lat = geometry_distance("car", [600, 353, 663, 405.5], cam, 360.0, 4.0)
    check(abs(z - 20.0) / 20.0 < 0.05, f"car at 20 m -> {z:.2f}")
    check(0.05 <= conf <= GEOMETRY_MAX_CONFIDENCE, f"confidence {conf}")
    check(abs(lat - (631.5 - 640) * z / 700) < 1e-6, "lateral")
    z2, _, _ = geometry_distance("pedestrian", [100, 300, 130, 360], cam, None, 30.0)   # size prior only
    check(abs(z2 - 700 * 1.7 / 60) < 0.5, f"pedestrian size prior {z2:.2f}")
    check(geometry_distance("car", [0, 0, 1279, 719], cam, 360.0, 4.0) is None, "frame-filling box -> None")
    return f"car {z:.2f} m conf {conf:.2f}, pedestrian {z2:.2f} m"


def t_nav_worker_offline() -> str:
    from perception.realtime.server import NavWorker
    sys.path.insert(0, str(ENGINE_ROOT))
    from tests.fake_nav_relay import FakeNavRelay
    v = validators()
    got: list[dict] = []
    pts = {"t": 0.0}

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
            pts["t"] += 0.1
            return pts["t"]
    base = dict(nav_route=None, nav_destination=None, nav_origin=None, nav_provider="mock", phase1_dir=None,
                node="node")
    w = NavWorker(Srv(), FakeNavRelay, SimpleNamespace(nav_session="x", **base))
    w.start()
    t0 = time.time()
    while len(got) < 4 and time.time() - t0 < 5:
        time.sleep(0.05)
    w.stop()
    w.join(2)
    check(len(got) >= 4, f"sim: {len(got)} packets")
    ptss = [m["ptsSeconds"] for m in got]
    check(all(b - a >= 0.49 for a, b in zip(ptss, ptss[1:])), f"sim packets every 0.5 s of media time: {ptss}")
    for m in got:
        assert_valid(v, m)
    got.clear()
    w = NavWorker(Srv(), FakeNavRelay, SimpleNamespace(nav_session=None, **{**base, "nav_destination": "GT"}))
    w.start()
    time.sleep(0.2)
    w.submit_trip({"timestampMs": 1790000000123, "location": {"lat": 33.77, "lng": -84.39}, "heading": 9.0,
                   "speedMps": 5.0}, "c1")
    t0 = time.time()
    while not got and time.time() - t0 < 3:
        time.sleep(0.05)
    w.stop()
    w.join(2)
    check(len(got) == 1 and got[0]["ptsSeconds"] is None and got[0]["tripTimestampMs"] == 1790000000123,
          f"live packet {got[:1]}")
    # --nav-live: no destination until the tablet sends client.destination; then the route follows the next fix.
    got.clear()
    w = NavWorker(Srv(), FakeNavRelay, SimpleNamespace(nav_session=None, nav_live=True, **base))
    w.start()
    time.sleep(0.2)
    check(w.info()["available"] is False and "waiting for a destination" in (w.info()["error"] or ""), f"waiting {w.info()}")
    w.set_destination("Piedmont Park")
    t0 = time.time()
    while ("start_live", "Piedmont Park") not in FakeNavRelay.calls and time.time() - t0 < 3:
        time.sleep(0.05)
    w.submit_trip({"timestampMs": 1790000000456, "location": {"lat": 33.77, "lng": -84.39}, "heading": 0.0,
                   "speedMps": 0.0}, "c1")
    t0 = time.time()
    while not got and time.time() - t0 < 3:
        time.sleep(0.05)
    info = w.info()
    w.stop()
    w.join(2)
    check(("start_live", "Piedmont Park") in FakeNavRelay.calls, "client.destination restarted live navigation")
    check(len(got) == 1 and info["available"] and info["destination"] == "Piedmont Park" and info["error"] is None,
          f"destination packet {info}")
    threads = {c for c in FakeNavRelay.calls}
    return f"sim {len(ptss)} packets, live 1 packet, tablet destination ok, relay calls {len(threads)}"


def t_nav_failures_and_trip_checks() -> str:
    """A relay that keeps returning None (phase1 failing) -> navigation unavailable + one perception.error + a hello;
    the next packet clears it. client.trip_state validation (null heading / speedMps -> 0, bad samples rejected)."""
    from perception.realtime.server import NAV_FAIL_THRESHOLD, NavWorker, Server
    sys.path.insert(0, str(ENGINE_ROOT))
    from tests.fake_nav_relay import FakeNavRelay
    body, why = Server.check_trip_state({"type": "client.trip_state", "timestampMs": 1790000000123,
                                         "location": {"lat": 33.77, "lng": -84.39}, "heading": None, "speedMps": None})
    check(why is None and body["heading"] == 0.0 and body["speedMps"] == 0.0 and "type" not in body, f"{body} {why}")
    for bad in ({"timestampMs": "x", "location": None}, {"timestampMs": 1, "location": {"lat": "x", "lng": 0}},
                {"timestampMs": 1, "location": {"lat": 91.0, "lng": 0}}, {"timestampMs": True, "location": {"lat": 1, "lng": 1}},
                {"timestampMs": 1, "location": {"lat": 1, "lng": 1}, "heading": "north"}):
        check(Server.check_trip_state(bad)[0] is None, f"accepted bad trip state {bad}")

    class FlakyRelay(FakeNavRelay):
        fail = True

        def __init__(self, *a, **k):
            super().__init__(*a, **k)
            self.last_error = None

        def on_trip_state(self, sample):
            if FlakyRelay.fail:
                with self.lock:
                    self._thread_check()
                self.last_error = "trip_state: phase1 route engine failed"
                return None
            self.last_error = None
            return super().on_trip_state(sample)
    events: list[tuple] = []

    class Srv:
        def post(self, fn, *args):
            fn(*args)

        def broadcast_nav(self, data):
            events.append(("nav",))

        def broadcast_error(self, code, msg):
            events.append(("error", code, msg))

        def broadcast_hello(self, flush=True):
            events.append(("hello", w.available))

        def media_pts_now(self):
            return None
    w = NavWorker(Srv(), FlakyRelay, SimpleNamespace(nav_session=None, nav_route=None, nav_destination="GT",
                                                     nav_origin=None, nav_provider="mock", phase1_dir=None, node="node"))
    w.start()
    t0 = time.time()
    while not w.running and time.time() - t0 < 3:
        time.sleep(0.02)
    try:
        for k in range(NAV_FAIL_THRESHOLD + 2):
            w.submit_trip({"timestampMs": 1790000000123 + 1000 * k, "location": {"lat": 33.77, "lng": -84.39},
                           "heading": 0.0, "speedMps": 0.0}, "c1")
            time.sleep(0.05)
        t0 = time.time()
        while w.available and time.time() - t0 < 3:
            time.sleep(0.02)
        check(not w.available and w.running and "phase1 route engine failed" in (w.error or ""), f"{w.info()}")
        errs = [e for e in events if e[0] == "error"]
        check(len(errs) == 1 and errs[0][1] == "internal", f"exactly one perception.error internal: {errs}")
        check(("hello", False) in events, f"hello re-announced with available false: {events}")
        FlakyRelay.fail = False
        w.submit_trip({"timestampMs": 1790000009999, "location": {"lat": 33.77, "lng": -84.39}, "heading": 0.0,
                       "speedMps": 0.0}, "c1")
        t0 = time.time()
        while not any(e[0] == "nav" for e in events) and time.time() - t0 < 3:
            time.sleep(0.02)
        check(w.available and w.error is None and ("hello", True) in events, f"recovered: {w.info()} {events}")
    finally:
        w.stop()
        w.join(2)
    return f"unavailable after {NAV_FAIL_THRESHOLD} failed calls, recovered on the next packet; trip_state checks ok"


# ============================================================================= server
def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def start_server(port: int, log: Path) -> subprocess.Popen:
    cmd = [sys.executable, "-m", "perception.realtime.server", "--mode", "auto", "--host", "127.0.0.1",
           "--port", str(port), "--nav-session", NAV_SESSION, "--nav-relay-impl", "tests.fake_nav_relay:FakeNavRelay"]
    log.parent.mkdir(parents=True, exist_ok=True)
    f = open(log, "w", encoding="utf-8")
    return subprocess.Popen(cmd, cwd=str(ENGINE_ROOT), stdout=f, stderr=subprocess.STDOUT,
                            env={**os.environ, "PYTHONUNBUFFERED": "1"})


def wait_health(port: int, proc: Optional[subprocess.Popen], timeout: float = 420.0) -> dict:
    t0 = time.time()
    while time.time() - t0 < timeout:
        if proc is not None and proc.poll() is not None:
            raise RuntimeError(f"server exited with code {proc.returncode}")
        with contextlib.suppress(Exception):
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=2) as r:
                return json.loads(r.read())
        time.sleep(1.0)
    raise TimeoutError("server did not become healthy")


def clip_jpegs(n: int, start_s: float = 5.0, size=(960, 540), q: int = 80) -> list[bytes]:
    import cv2
    cap = cv2.VideoCapture(str(CITY))
    cap.set(cv2.CAP_PROP_ORIENTATION_AUTO, 1)
    cap.set(cv2.CAP_PROP_POS_MSEC, start_s * 1000)
    out = []
    while len(out) < n:
        ok, img = cap.read()
        if not ok:
            break
        small = cv2.resize(img, size, interpolation=cv2.INTER_AREA)
        out.append(small)
    cap.release()
    return out


def enc(img, q=80) -> bytes:
    import cv2
    ok, j = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, q])
    return j.tobytes()


class WsClient:
    """Minimal asyncio client that files every received message."""

    def __init__(self, url: str):
        self.url = url
        self.msgs: list[tuple[float, dict]] = []
        self.ws = None
        self.task = None
        self.v = validators()
        self.invalid: list[str] = []

    async def __aenter__(self):
        from websockets.asyncio.client import connect
        self.ws = await connect(self.url, max_size=None, compression=None, ping_interval=None, open_timeout=20)
        self.task = asyncio.create_task(self._rx())
        return self

    async def __aexit__(self, *exc):
        self.task.cancel()
        await self.ws.close()

    async def _rx(self):
        import orjson
        with contextlib.suppress(Exception):
            async for raw in self.ws:
                m = orjson.loads(raw)
                self.msgs.append((time.monotonic(), m))
                t = m.get("type")
                if t in self.v:
                    errs = list(self.v[t].iter_errors(m))
                    if errs and len(self.invalid) < 5:
                        self.invalid.append(f"{t}: {errs[0].message} at {list(errs[0].absolute_path)}")

    async def send(self, obj):
        import orjson
        await self.ws.send(obj if isinstance(obj, (bytes, bytearray)) else orjson.dumps(obj).decode())

    def of(self, t: str, since: int = 0) -> list[dict]:
        return [m for _, m in self.msgs[since:] if m.get("type") == t]

    async def wait(self, pred: Callable[[], bool], timeout: float, what: str):
        t0 = time.monotonic()
        while not pred():
            if time.monotonic() - t0 > timeout:
                raise AssertionError(f"timeout waiting for {what}")
            await asyncio.sleep(0.01)


async def live_tests(url: str) -> str:
    from perception.realtime.wire import pack_uplink_header
    import cv2
    imgs = clip_jpegs(34)
    async with WsClient(url) as c:
        await c.wait(lambda: c.of("perception.hello"), 10, "connect hello")
        cam = {"imageWidth": 960, "imageHeight": 540, "focalPx": 525.0, "principalPoint": [480.0, 270.0],
               "mountHeightMeters": 1.3, "pitchDegrees": None, "lensFacing": "back", "stabilization": False}
        await c.send({"type": "client.hello", "protocolVersion": 2, "clientId": "test-live",
                      "device": {"manufacturer": "generic", "model": "test", "osVersion": "0"}, "mode": "live",
                      "camera": cam, "sim": None})
        await c.wait(lambda: any(h.get("role") == "controller" and h["mode"] == "live"
                                 for h in c.of("perception.hello")), 10, "controller hello")
        hello = [h for h in c.of("perception.hello") if h.get("role") == "controller"][-1]
        max_in = hello["uplink"]["maxInFlight"]
        check(hello["image"] == {"width": 960, "height": 540}, f"hello image {hello['image']}")
        check(abs(hello["camera"]["focalPx"] - 525.0) < 0.2, f"hello camera {hello['camera']}")
        session = hello["sessionId"]
        answers: dict[int, list[dict]] = {}
        sent: dict[int, int] = {}

        def answered(fid):
            return [m for _, m in c.msgs if (m.get("type") == "perception.frame" and (m.get("echo") or {}).get(
                "frameId") == fid) or (m.get("type") == "perception.skip" and m.get("frameId") == fid)]

        cap0 = 5_000_000_000_000
        # 1) 20 frames under credits
        for i in range(20):
            fid = 100 + i
            while sum(1 for f in sent if not answered(f)) >= max_in:
                await asyncio.sleep(0.002)
            cap_ns = cap0 + i * 33_333_333
            sent[fid] = cap_ns
            await c.send(pack_uplink_header(fid, cap_ns, 0) + enc(imgs[i]))
        await c.wait(lambda: all(answered(f) for f in sent), 10, "20 answers")
        await asyncio.sleep(0.3)
        frames = []
        for fid, cap_ns in sent.items():
            a = answered(fid)
            check(len(a) == 1, f"frame {fid}: {len(a)} answers {[x['type'] for x in a]}")
            m = a[0]
            check(m["type"] == "perception.frame", f"frame {fid} answered with {m}")
            check(m["echo"]["captureTimeNs"] == cap_ns, "echo captureTimeNs")
            check(abs(m["ptsSeconds"] - (cap_ns - cap0) / 1e9) < 2e-4, f"pts {m['ptsSeconds']} vs {(cap_ns-cap0)/1e9}")
            check(m["sessionId"] == session and m["image"] == {"width": 960, "height": 540}, "session / image")
            check(abs(m["camera"]["focalPx"] - 525.0) < 0.2 and m["camera"]["cameraHeightMeters"] == 1.3,
                  f"camera {m['camera']}")
            for o in m["objects"]:
                x1, y1, x2, y2 = o["bbox"]
                check(-1 <= x1 <= x2 <= 961 and -1 <= y1 <= y2 <= 541, f"bbox {o['bbox']} outside 960x540")
            frames.append(m)
        seqs = [m["seq"] for m in frames]
        check(seqs == sorted(seqs), "seq increasing")
        vp = [o for m in frames[5:] for o in m["objects"] if o["class"] in VEHICLES]
        with_d = [o for o in vp if o["distanceMeters"] is not None]
        check(vp and len(with_d) / len(vp) >= 0.9, f"vehicles/pedestrians with distance {len(with_d)}/{len(vp)}")
        methods = sorted({o["distanceMethod"] for o in with_d})
        # 2) rotationDegrees: the same frame sent upright and as a 90-degree sensor buffer -> same upright boxes
        n_hello = len(c.of("perception.hello"))
        base = imgs[20]
        rotated = cv2.rotate(base, cv2.ROTATE_90_COUNTERCLOCKWISE)      # 540x960 buffer; +90 CW makes it upright
        pair = {}
        for fid, img, rot in ((200, base, 0), (201, rotated, 90), (202, base, 0), (203, rotated, 90)):
            await c.wait(lambda: sum(1 for f in sent if not answered(f)) == 0, 5, "credits")
            sent[fid] = cap0 + 2_000_000_000 + fid
            await c.send(pack_uplink_header(fid, sent[fid], rot) + enc(img))
            await c.wait(lambda: answered(fid), 5, f"answer {fid}")
            pair[fid] = answered(fid)[0]
        for fid in pair:
            check(pair[fid]["type"] == "perception.frame" and pair[fid]["image"] == {"width": 960, "height": 540},
                  f"rotated frame {fid}: {pair[fid].get('image')}")
        check(len(c.of("perception.hello")) == n_hello, "rotation must not start a new session")

        def cars(m):
            return sorted([o["bbox"] for o in m["objects"] if o["class"] == "car"], key=lambda b: (b[0], b[1]))
        a, b = cars(pair[202]), cars(pair[203])
        check(abs(len(a) - len(b)) <= 1, f"upright vs rotated car count {len(a)} vs {len(b)}")
        if a and b:
            import numpy as np
            d = [min(max(abs(p - q) for p, q in zip(x, y)) for y in b) for x in a]
            check(float(np.median(d)) < 4.0, f"upright vs rotated boxes differ (median max-coord diff {np.median(d):.1f})")
        # 3) credit-violating burst: every frame still gets exactly one answer (skips = superseded)
        burst = list(range(300, 306))
        for i, fid in enumerate(burst):
            sent[fid] = cap0 + 3_000_000_000 + i * 33_333_333
            await c.send(pack_uplink_header(fid, sent[fid], 0) + enc(imgs[24 + i]))
        await c.wait(lambda: all(answered(f) for f in burst), 10, "burst answers")
        await asyncio.sleep(0.3)
        kinds = [answered(f)[0]["type"] + ":" + answered(f)[0].get("reason", "") for f in burst]
        check(all(len(answered(f)) == 1 for f in burst), f"burst answers {[len(answered(f)) for f in burst]}")
        check(any(k.startswith("perception.skip:superseded") for k in kinds), f"burst had no superseded skip {kinds}")
        # 4) bad header, undecodable JPEG, ping, trip_state without live nav
        n_err = len(c.of("perception.error"))
        bad = bytearray(pack_uplink_header(400, cap0) + enc(imgs[30]))
        bad[4] = 3
        await c.send(bytes(bad))
        await c.send(pack_uplink_header(401, cap0 + 4_000_000_000, 0) + b"\xff\xd8not a jpeg\xff\xd9")
        await c.send({"type": "client.ping", "clientTimeNs": 424242})
        await c.send({"type": "client.trip_state", "timestampMs": 1790000000123,
                      "location": {"lat": 33.77, "lng": -84.39}, "heading": 9.0, "speedMps": 5.0})
        await c.send("{not json")
        await c.wait(lambda: answered(400) and answered(401) and c.of("perception.pong"), 5, "skips + pong")
        check(answered(400)[0].get("reason") == "badHeader", f"400: {answered(400)}")
        check(answered(401)[0].get("reason") == "decodeError", f"401: {answered(401)}")
        check(c.of("perception.pong")[0]["clientTimeNs"] == 424242, "pong echo")
        await asyncio.sleep(0.3)
        codes = [e["code"] for e in c.of("perception.error")[n_err:]]
        check("badMessage" in codes and "modeNotAvailable" in codes, f"errors {codes}")
        # 5) >= 24 bytes with a wrong magic: still answered (skip badHeader), so the client's credit comes back
        bad_magic = bytearray(pack_uplink_header(402, cap0 + 4_100_000_000, 0) + enc(imgs[31]))
        bad_magic[:4] = b"SDC2"
        await c.send(bytes(bad_magic))
        await c.wait(lambda: answered(402), 5, "bad magic answer")
        check(answered(402)[0].get("reason") == "badHeader", f"402: {answered(402)}")
        # 6) the upright frame size changes mid-session: a NEW session (new sessionId, ptsSeconds from 0)
        n_h = len(c.of("perception.hello"))
        resized = []
        for i, im in enumerate(imgs[:6]):
            fid = 500 + i
            sent[fid] = cap0 + 6_000_000_000 + i * 33_333_333
            await c.send(pack_uplink_header(fid, sent[fid], 0) + enc(cv2.resize(im, (640, 360),
                                                                                 interpolation=cv2.INTER_AREA)))
            await c.wait(lambda: answered(fid), 5, f"answer {fid}")
            resized.append(fid)
        await asyncio.sleep(0.3)
        hs = c.of("perception.hello")[n_h:]
        check(hs and hs[-1]["sessionId"] != session and hs[-1]["image"] == {"width": 640, "height": 360},
              f"size change must start a new session: {[(h['sessionId'], h['image']) for h in hs]}")
        check(all(len(answered(f)) == 1 for f in resized), f"resize answers {[answered(f) for f in resized]}")
        new_frames = [answered(f)[0] for f in resized if answered(f)[0]["type"] == "perception.frame"]
        check(len(new_frames) >= 4 and all(m["sessionId"] == hs[-1]["sessionId"] for m in new_frames),
              f"frames after the resize {[(m['sessionId'], m['ptsSeconds']) for m in new_frames]}")
        check(min(m["ptsSeconds"] for m in new_frames) < 0.01, "ptsSeconds restarts with the new session")
        check(c.of("perception.update"), "no perception.update (wave 2) received")
        check(c.of("perception.stats"), "no perception.stats")
        check(not c.invalid, f"schema errors: {c.invalid}")
        lat = [m["processingMs"] for m in frames]
    import statistics
    return (f"20/20 answered once, rotation ok, burst {kinds.count('perception.frame:')} frames + "
            f"{sum(k.startswith('perception.skip') for k in kinds)} skips, bad magic answered, resize = new session, "
            f"distance methods {methods}, server processing p50 {statistics.median(lat):.0f} ms")


async def sim_tests(url: str) -> str:
    import numpy as np
    async with WsClient(url) as watcher, WsClient(url) as c:
        # a live controller first, so the sim hello must take over from it
        await watcher.send({"type": "client.hello", "protocolVersion": 2, "clientId": "test-old", "mode": "live",
                            "camera": None, "sim": None})
        await watcher.wait(lambda: any(h.get("role") == "controller" for h in watcher.of("perception.hello")), 10,
                           "old controller")
        await c.send({"type": "client.hello", "protocolVersion": 2, "clientId": "test-sim", "mode": "sim",
                      "camera": None, "sim": {"videoId": "no-such-clip"}})
        await c.wait(lambda: c.of("perception.error"), 10, "unknownVideo")
        check(c.of("perception.error")[0]["code"] == "unknownVideo", f"{c.of('perception.error')}")
        vid = "b1ff4656-0435391e"
        await c.send({"type": "client.hello", "protocolVersion": 2, "clientId": "test-sim", "mode": "sim",
                      "camera": None, "sim": {"videoId": vid}})
        await c.wait(lambda: any(h.get("role") == "controller" and h["mode"] == "sim"
                                 for h in c.of("perception.hello")), 15, "sim controller hello")
        h = [h for h in c.of("perception.hello") if h.get("role") == "controller"][-1]
        check(h["sim"]["videoId"] == vid and vid in h["sim"]["videos"], f"sim hello {h['sim']}")
        check(h["navigation"]["mode"] == "sim", f"nav {h['navigation']}")
        await watcher.wait(lambda: any(e["code"] == "notUplinkClient" for e in watcher.of("perception.error")), 5,
                           "takeover error to the old controller")
        session = h["sessionId"]
        t0 = time.monotonic()
        idx0 = len(c.msgs)

        def play(now):
            return now - t0
        while time.monotonic() - t0 < 8.0:
            await c.send({"type": "client.playback", "videoId": vid, "ptsSeconds": round(play(time.monotonic()), 4),
                          "playing": True, "rate": 1.0, "clientTimeNs": time.monotonic_ns()})
            await asyncio.sleep(0.1)
        frames = [(t, m) for t, m in c.msgs[idx0:] if m.get("type") == "perception.frame"]
        check(len(frames) >= 60, f"only {len(frames)} frames in 8 s")
        leads = [(m["ptsSeconds"] - play(t)) * 1000 for t, m in frames if t - t0 > 2.0]
        late = sum(x < 0 for x in leads) / max(1, len(leads))
        check(np.median(leads) > 0, f"median lead {np.median(leads):.0f} ms")
        check(late < 0.15, f"late fraction {late:.2f}")
        check({m["sessionId"] for _, m in frames} == {session}, "one session while playing")
        check(all(m["source"] == {"kind": "video", "id": vid} for _, m in frames), "source")
        navs = [(t, m) for t, m in c.msgs[idx0:] if m.get("type") == "navigation.packet"]
        check(len(navs) >= 8, f"{len(navs)} navigation.packet in 8 s")
        nav_err = [abs(m["ptsSeconds"] - play(t)) for t, m in navs if m["ptsSeconds"] is not None]
        check(max(nav_err) < 1.0, f"navigation pts off the playback position by {max(nav_err):.2f} s")
        # seek -> new session, results after the new position
        n_h = len(c.of("perception.hello"))
        t_seek = time.monotonic()
        for _ in range(20):
            await c.send({"type": "client.playback", "videoId": vid, "ptsSeconds": round(20.0 + time.monotonic()
                          - t_seek, 4), "playing": True, "rate": 1.0, "clientTimeNs": time.monotonic_ns()})
            await asyncio.sleep(0.1)
        hs = c.of("perception.hello")[n_h:]
        check(hs and hs[-1]["sessionId"] != session, "seek must start a new session")
        after = [m for m in c.of("perception.frame") if m["sessionId"] == hs[-1]["sessionId"]]
        check(after and min(m["ptsSeconds"] for m in after) >= 19.9, f"frames after seek {[m['ptsSeconds'] for m in after[:3]]}")
        # pause: analysis stops
        await c.send({"type": "client.playback", "videoId": vid, "ptsSeconds": 22.5, "playing": False, "rate": 1.0,
                      "clientTimeNs": time.monotonic_ns()})
        await asyncio.sleep(0.8)
        n_f = len(c.of("perception.frame"))
        await asyncio.sleep(1.2)
        extra = len(c.of("perception.frame")) - n_f
        check(extra <= 1, f"{extra} frames analysed while paused")
        # rate 0 while "playing" is paused too (not extrapolated at rate 1)
        for _ in range(8):
            await c.send({"type": "client.playback", "videoId": vid, "ptsSeconds": 22.5, "playing": True, "rate": 0.0,
                          "clientTimeNs": time.monotonic_ns()})
            await asyncio.sleep(0.1)
        n_f = len(c.of("perception.frame"))
        for _ in range(12):
            await c.send({"type": "client.playback", "videoId": vid, "ptsSeconds": 22.5, "playing": True, "rate": 0.0,
                          "clientTimeNs": time.monotonic_ns()})
            await asyncio.sleep(0.1)
        extra0 = len(c.of("perception.frame")) - n_f
        check(extra0 <= 1, f"{extra0} frames analysed at rate 0")
        # the controller switches to a clip the laptop lacks (new hello, then ~10 Hz playback): one unknownVideo,
        # the old clip's session ends (new sessionId, no old-clip results), no error storm
        n_m = len(c.msgs)
        last_sid = c.of("perception.hello")[-1]["sessionId"]
        await c.send({"type": "client.hello", "protocolVersion": 2, "clientId": "test-sim", "mode": "sim",
                      "camera": None, "sim": {"videoId": "missing-clip"}})
        for k in range(30):
            await c.send({"type": "client.playback", "videoId": "missing-clip", "ptsSeconds": k * 0.1, "playing": True,
                          "rate": 1.0, "clientTimeNs": time.monotonic_ns()})
            await asyncio.sleep(0.1)
        after = [m for _, m in c.msgs[n_m:]]
        unk = [m for m in after if m.get("type") == "perception.error" and m["code"] == "unknownVideo"]
        check(len(unk) == 1, f"{len(unk)} unknownVideo errors in 3 s (expected 1)")
        new_h = [i for i, m in enumerate(after) if m.get("type") == "perception.hello" and m["sessionId"] != last_sid]
        check(new_h and after[new_h[0]].get("role") == "controller", "missing clip: new session hello to the controller")
        old = [m for m in after[new_h[0]:] if m.get("type") == "perception.frame"]
        check(not old, f"{len(old)} results of the previous clip after the switch")
        check(not c.invalid, f"schema errors: {c.invalid}")
        stats = c.of("perception.stats")
        lk = [s["lookaheadSeconds"] for s in stats if s.get("lookaheadSeconds") is not None]
    return (f"{len(frames)} frames in 8 s, lead p50 {np.median(leads):.0f} ms p5 {np.percentile(leads, 5):.0f} ms, "
            f"late {late:.1%}, {len(navs)} nav packets, lookahead {lk[-1] if lk else None} s")


async def takeover_tests(url: str) -> str:
    """A (live hello, focal 700, mount 1.8) is taken over by B, B leaves, A keeps uplinking without a new hello: the
    implicit session must keep A's hello intrinsics (not the 525 px default / estimated height)."""
    from perception.realtime.wire import pack_uplink_header
    imgs = clip_jpegs(6)
    cam = {"imageWidth": 960, "imageHeight": 540, "focalPx": 700.0, "principalPoint": None, "mountHeightMeters": 1.8,
           "pitchDegrees": None, "lensFacing": "back", "stabilization": False}
    async with WsClient(url) as a:
        await a.wait(lambda: a.of("perception.hello"), 10, "connect hello")
        await a.send({"type": "client.hello", "protocolVersion": 2, "clientId": "test-A", "mode": "live",
                      "camera": cam, "sim": None})
        await a.wait(lambda: any(h.get("role") == "controller" for h in a.of("perception.hello")), 10, "A controller")
        async with WsClient(url) as b:
            await b.send({"type": "client.hello", "protocolVersion": 2, "clientId": "test-B", "mode": "live",
                          "camera": dict(cam, focalPx=800.0, mountHeightMeters=1.6), "sim": None})
            await b.wait(lambda: any(h.get("role") == "controller" for h in b.of("perception.hello")), 10, "B controller")
            await a.wait(lambda: any(e["code"] == "notUplinkClient" for e in a.of("perception.error")), 5, "A demoted")
            check(a.of("perception.hello")[-1].get("role") == "watcher", "A is a watcher after the takeover")
        await asyncio.sleep(0.5)                                           # B gone -> idle
        n = len(a.msgs)
        cap0 = 9_000_000_000_000
        for i, img in enumerate(imgs[:4]):
            fid = 600 + i
            await a.send(pack_uplink_header(fid, cap0 + i * 33_333_333, 0) + enc(img))
            await a.wait(lambda: any((m.get("echo") or {}).get("frameId") == fid or m.get("frameId") == fid
                                     for m in a.of("perception.frame", n) + a.of("perception.skip", n)), 5, f"{fid}")
        hs = [h for h in a.of("perception.hello", n) if h.get("role") == "controller"]
        check(hs and abs(hs[-1]["camera"]["focalPx"] - 700.0) < 0.5, f"implicit session camera {hs[-1]['camera'] if hs else None}")
        fr = [m for m in a.of("perception.frame", n) if m.get("echo")]
        check(fr and fr[-1]["camera"]["cameraHeightMeters"] == 1.8, f"frame camera {fr[-1]['camera'] if fr else None}")
        check(not a.invalid, f"schema errors: {a.invalid}")
    return f"implicit re-promotion kept focal {hs[-1]['camera']['focalPx']} px, camera height 1.8 m"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--offline", action="store_true", help="skip the server tests")
    ap.add_argument("--url", default=None, help="use a running --mode auto server instead of starting one")
    ap.add_argument("--log", type=Path, default=ENGINE_ROOT / "outputs" / "realtime" / "test_protocol_v2_server.log")
    a = ap.parse_args()
    ok = True
    for name, fn in [("schemas well-formed", t_schemas_wellformed), ("samples validate", t_samples_validate),
                     ("SDC1 header round trip", t_header_roundtrip), ("wire builders", t_wire_offline),
                     ("geometry distance", t_geometry_distance), ("nav worker (fake relay)", t_nav_worker_offline),
                     ("nav failures + trip_state checks", t_nav_failures_and_trip_checks)]:
        ok &= run_test(name, fn)
    if not a.offline:
        proc = None
        url = a.url
        try:
            if url is None:
                port = free_port()
                print(f"[test] starting server on port {port} (log {a.log}) ...", flush=True)
                proc = start_server(port, a.log)
                h = wait_health(port, proc)
                url = f"ws://127.0.0.1:{port}/perception"
                print(f"[test] server healthy: {h['acceptedModes']} warmup {h['laneWarmupMs']}", flush=True)
            ok &= run_test("live loopback", lambda: asyncio.run(live_tests(url)))
            ok &= run_test("sim ahead of playback", lambda: asyncio.run(sim_tests(url)))
            ok &= run_test("takeover keeps intrinsics", lambda: asyncio.run(takeover_tests(url)))
            if proc is not None:
                port = int(url.rsplit(":", 1)[1].split("/")[0])
                h = wait_health(port, proc, 10)
                ok &= run_test("server health (no lane errors)",
                               lambda: (check(not h["errors"], f"errors {h['errors']}"), "ok")[1])
        finally:
            if proc is not None:
                proc.terminate()
                with contextlib.suppress(Exception):
                    proc.wait(15)
    print("")
    for name, passed, info in RESULTS:
        print(f"{'PASS' if passed else 'FAIL'}  {name}  {info}")
    print(f"\n{sum(p for _, p, _ in RESULTS)}/{len(RESULTS)} passed")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
