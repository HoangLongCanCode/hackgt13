"""Tests for the glasses socket (perception.realtime.glasses_server, ws://<host>:8000/ws). Plain Python, no pytest.

AI Spatial Driving Copilot. Run from perception_engine/:

    python tests/test_glasses_server.py                 # offline tests + a real server subprocess on a free port
    python tests/test_glasses_server.py --offline       # decoder, fit_frame inverse, document builder only
    python tests/test_glasses_server.py --url ws://127.0.0.1:8000/ws   # against an already running server

Offline: every bad-frame reason; the data: prefix, line-wrapped base64 and EXIF orientation (ignored); FitMap is the
exact inverse of engine.fit_frame (a marker drawn on the source is found where the map says, for wider, taller and
16:9 sources); the document for empty / full synthetic FrameResults (lane fallback, ego pair by index and by the
centre-straddle rule, vehicle filters and ordering, signs, instruction order and fields, no nulls anywhere).
Server (real engine): /health, one frame -> one document with the three LANE_ARROWs / EXIT_MARKER, garbage and binary
frames -> {"error"} with the socket still usable, websocket ping/pong, a burst without waiting (latest wins, the last
frame always answered, no protocol-v2 message), and the same scene sent 16:9 and padded wider / taller gives the same
normalized boxes and lane lines once the padding is accounted for (fit_frame undone; dividing by 1280x720 would not).
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import contextlib
import json
import math
import os
import socket
import struct
import subprocess
import sys
import time
import traceback
import urllib.request
from pathlib import Path
from typing import Any, Callable, Optional

ENGINE_ROOT = Path(__file__).resolve().parents[1]              # perception_engine/
sys.path.insert(0, str(ENGINE_ROOT))
os.environ.setdefault("YOLO_AUTOINSTALL", "False")

import numpy as np  # noqa: E402

from perception.common.paths import DATA_DIR  # noqa: E402
from perception.common.schemas import (  # noqa: E402
    Detection, DistanceEstimate, FrameResult, LaneState, TrackState, TrafficSign)
from perception.realtime import glasses_wire as gw  # noqa: E402

CITY = DATA_DIR / "bdd100k" / "videos" / "val" / "b1ff4656-0435391e.mov"
INSTRUCTION_TYPES = {"LANE_BOUNDARY", "LANE_ARROW", "EXIT_MARKER", "VEHICLE_MARKER", "DISTANCE_LABEL"}
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


def enc(img, q: int = 60) -> bytes:
    import cv2
    ok, j = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, q])
    check(ok, "imencode failed")
    return j.tobytes()


def frame_text(jpeg: bytes, frame_id: int = 1, ts: int = 1710000000000, w: int = 640, h: int = 360,
               prefix: str = "") -> str:
    return json.dumps({"frameId": frame_id, "timestampMs": ts, "encoding": "jpeg-base64", "width": w, "height": h,
                       "data": prefix + base64.b64encode(jpeg).decode()})


def walk(v: Any, path: str = "$"):
    """Yield (path, value) for every leaf."""
    if isinstance(v, dict):
        for k, x in v.items():
            yield from walk(x, f"{path}.{k}")
    elif isinstance(v, list):
        for i, x in enumerate(v):
            yield from walk(x, f"{path}[{i}]")
    else:
        yield path, v


def check_document(doc: dict[str, Any]) -> None:
    """Structural contract every success document must meet (shared by the offline and server tests)."""
    check(list(doc) == ["frameId", "timestampMs", "perception", "navigation", "instructions"], f"keys {list(doc)}")
    for path, v in walk(doc):
        check(v is not None, f"null at {path}")
        check(not (isinstance(v, float) and not math.isfinite(v)), f"non-finite at {path}")
    gw.dumps(doc)                                               # allow_nan=False round trip
    lanes = doc["perception"]["lanes"]
    lc, cur = lanes["laneCount"], lanes["currentLane"]
    check(isinstance(lc, int) and lc >= 1 and isinstance(cur, int) and 1 <= cur <= lc, f"lanes {lanes}")
    check(doc["navigation"] == {"event": "EXIT", "exitLabel": "EXIT 54/56", "distanceLabel": "0.4 mi",
                                "requiredLane": lc}, f"navigation {doc['navigation']}")
    for b in lanes["boundaries"]:
        check(b["id"] in ("left", "right") and 1 <= len(b["points"]) <= 6, f"boundary {b}")
        for p in b["points"]:
            check(len(p) == 2 and all(0.0 <= c <= 1.0 and round(c, 4) == c for c in p), f"point {p}")
    for v in doc["perception"]["vehicles"]:
        x1, y1, x2, y2 = v["bbox"]
        check(0 <= x1 < x2 <= 1 and 0 <= y1 < y2 <= 1 and x2 - x1 >= 0.005 and y2 - y1 >= 0.005, f"vehicle {v}")
        check(v["class"] in gw.VEHICLE_CLASSES and v["distanceMeters"] > 0 and 0 < v["confidence"] <= 1, f"{v}")
    check(len(doc["perception"]["vehicles"]) <= 3 and len(doc["perception"]["signs"]) <= 4, "too many objects")
    for s in doc["perception"]["signs"]:
        x1, y1, x2, y2 = s["bbox"]
        check(0 <= x1 < x2 <= 1 and 0 <= y1 < y2 <= 1 and isinstance(s["id"], int) and s["class"], f"sign {s}")
    ins = doc["instructions"]
    types = [i["type"] for i in ins]
    check(set(types) <= INSTRUCTION_TYPES, f"types {types}")
    order = {"LANE_BOUNDARY": 0, "LANE_ARROW": 1, "EXIT_MARKER": 2, "VEHICLE_MARKER": 3, "DISTANCE_LABEL": 3}
    check([order[t] for t in types] == sorted(order[t] for t in types), f"instruction order {types}")
    for i in ins:
        check(set(i) >= {"type", "anchor", "content", "lifetimeMs"} and i["lifetimeMs"] == 500, f"instruction {i}")
        check(set(i["anchor"]) == {"x", "y"} and all(0 <= i["anchor"][k] <= 1 for k in "xy"), f"anchor {i}")
    arrows = [i for i in ins if i["type"] == "LANE_ARROW"]
    check([a["laneIndex"] for a in arrows] == list(range(1, lc + 1)), f"arrows {arrows}")
    hl = [a for a in arrows if a["highlighted"]]
    check(len(hl) == 1 and hl[0]["content"] == "EXIT" and hl[0]["laneIndex"] == lc, f"highlighted {hl}")
    for a in arrows:
        check(a["anchor"] == {"x": round(a["laneIndex"] / (lc + 1), 4), "y": 0.78}, f"arrow anchor {a}")
        check(a["highlighted"] or a["content"] == "↑", f"arrow content {a}")
    ex = [i for i in ins if i["type"] == "EXIT_MARKER"]
    check(len(ex) == 1 and ex[0]["content"] == "EXIT 54/56" and ex[0]["distanceLabel"] == "0.4 mi"
          and ex[0]["anchor"] == {"x": 0.5, "y": 0.08}, f"exit marker {ex}")
    bnd = [i for i in ins if i["type"] == "LANE_BOUNDARY"]
    check(len(bnd) == len([b for b in lanes["boundaries"] if len(b["points"]) >= 2]), "LANE_BOUNDARY count")
    for i, b in zip(bnd, [b for b in lanes["boundaries"] if len(b["points"]) >= 2]):
        check(i["content"] == b["id"] and i["points"] == b["points"]
              and i["anchor"] == {"x": b["points"][0][0], "y": b["points"][0][1]}, f"LANE_BOUNDARY {i}")
    vm = [i for i in ins if i["type"] in ("VEHICLE_MARKER", "DISTANCE_LABEL")]
    check(len(vm) == 2 * len(doc["perception"]["vehicles"]), "one marker + label per vehicle")
    for k, v in enumerate(doc["perception"]["vehicles"]):
        m, lab = vm[2 * k], vm[2 * k + 1]
        cx, cy = (v["bbox"][0] + v["bbox"][2]) / 2, (v["bbox"][1] + v["bbox"][3]) / 2
        d = v["distanceMeters"]
        check(m["type"] == "VEHICLE_MARKER" and m["content"] == v["class"].upper()
              and m["distanceLabel"] == f"{int(math.floor(d + 0.5))} m"
              and abs(m["anchor"]["x"] - cx) < 1e-4 and abs(m["anchor"]["y"] - cy) < 1e-4, f"marker {m} for {v}")
        check(lab["type"] == "DISTANCE_LABEL" and lab["content"] == f"{d:.1f}m" and "distanceLabel" not in lab
              and lab["anchor"]["x"] == m["anchor"]["x"]
              and abs(lab["anchor"]["y"] - min(cy + 0.08, 0.95)) < 1e-4, f"label {lab} for {v}")


# ============================================================================= offline
def exif_orientation_6(jpeg: bytes) -> bytes:
    """Insert an APP1 EXIF segment with Orientation = 6 (rotate 90 CW) right after SOI."""
    tiff = b"MM\x00\x2a\x00\x00\x00\x08" + b"\x00\x01" + b"\x01\x12\x00\x03\x00\x00\x00\x01\x00\x06\x00\x00" \
        + b"\x00\x00\x00\x00"
    exif = b"Exif\x00\x00" + tiff
    return jpeg[:2] + b"\xff\xe1" + struct.pack(">H", len(exif) + 2) + exif + jpeg[2:]


def t_decode() -> str:
    import cv2
    img = np.zeros((360, 640, 3), np.uint8)
    img[100:200, 300:400] = (0, 0, 255)
    jpeg = enc(img)
    f = gw.decode_frame(frame_text(jpeg, 7, 1234))
    check(f.image.shape == (360, 640, 3) and f.image.dtype == np.uint8, f"shape {f.image.shape}")
    check(f.frame_id == 7 and f.timestamp_ms == 1234, "ids")
    check(abs(int(f.image[150, 350, 2]) - 255) < 40 and int(f.image[150, 350, 0]) < 60, "BGR order")
    f = gw.decode_frame(frame_text(jpeg, prefix="data:image/jpeg;base64,"))
    check(f.image.shape == (360, 640, 3), "data: prefix")
    b64 = base64.b64encode(jpeg).decode()
    wrapped = "\n".join(b64[i:i + 76] for i in range(0, len(b64), 76))
    f = gw.decode_frame(json.dumps({"frameId": 1, "timestampMs": 1, "encoding": "jpeg-base64", "data": wrapped}))
    check(f.image.shape == (360, 640, 3), "line-wrapped base64")
    # width/height in the message are informational
    f = gw.decode_frame(frame_text(jpeg, w=1280, h=720))
    check(f.image.shape == (360, 640, 3), "declared size must not win")
    # EXIF orientation: the phone already rotated the pixels, so it must be ignored
    rot = exif_orientation_6(jpeg)
    check(cv2.imdecode(np.frombuffer(rot, np.uint8), cv2.IMREAD_COLOR).shape[:2] == (640, 360),
          "test JPEG does not carry the EXIF orientation")
    check(gw.decode_frame(frame_text(rot)).image.shape == (360, 640, 3), "EXIF orientation applied (second rotation)")
    # missing ids -> None (the server numbers / stamps them)
    f = gw.decode_frame(json.dumps({"encoding": "jpeg-base64", "data": b64}))
    check(f.frame_id is None and f.timestamp_ms is None, "missing ids")

    ok, png = cv2.imencode(".png", img)
    bad = [
        ("not json {", "frame is not valid JSON"),
        ("[1, 2]", "frame must be a JSON object"),
        ("12", "frame must be a JSON object"),
        (json.dumps({"encoding": "png-base64", "data": b64}), "encoding must be jpeg-base64"),
        (json.dumps({"data": b64}), "encoding must be jpeg-base64"),
        (json.dumps({"encoding": "jpeg-base64"}), "empty jpeg"),
        (json.dumps({"encoding": "jpeg-base64", "data": ""}), "empty jpeg"),
        (json.dumps({"encoding": "jpeg-base64", "data": "data:image/jpeg;base64,"}), "empty jpeg"),
        (json.dumps({"encoding": "jpeg-base64", "data": 42}), "data is not valid base64"),
        (json.dumps({"encoding": "jpeg-base64", "data": "@@@@not base64!!"}), "data is not valid base64"),
        (json.dumps({"encoding": "jpeg-base64", "data": "abcde"}), "data is not valid base64"),
        (json.dumps({"encoding": "jpeg-base64", "data": base64.b64encode(png.tobytes()).decode()}),
         "data is not a jpeg image"),
        (json.dumps({"encoding": "jpeg-base64", "data": base64.b64encode(b"\xff\xd8\xff" + b"\x00" * 64).decode()}),
         "data is not a jpeg image"),
        (json.dumps({"encoding": "jpeg-base64",
                     "data": base64.b64encode(b"\xff\xd8\xff" + bytes(8 * 1024 * 1024)).decode()}), "jpeg exceeds 8MB"),
        (json.dumps({"encoding": "jpeg-base64", "data": "A" * (gw.MAX_BASE64_CHARS + 4)}), "jpeg exceeds 8MB"),
    ]
    for text, reason in bad:
        try:
            gw.decode_frame(text)
        except gw.FrameError as e:
            check(e.reason == reason, f"{text[:40]!r}: got {e.reason!r}, want {reason!r}")
        else:
            raise AssertionError(f"{text[:40]!r} accepted, want {reason!r}")
    return f"{len(bad)} bad-frame reasons, data: prefix, wrapped base64, EXIF ignored"


def t_fitmap_inverts_fit_frame() -> str:
    """Draw a marker at a known source pixel, run the engine's own fit_frame, find it, map it back."""
    import cv2
    from perception.engine import fit_frame
    worst = 0.0
    sizes = [(640, 360), (640, 480), (480, 640), (768, 360), (640, 488), (1280, 720), (1920, 1080), (641, 360),
             (360, 360), (320, 240)]
    for sw, sh in sizes:
        fm = gw.FitMap.of(sw, sh, 1280, 720)
        # corners of the fitted image land on the crop rectangle
        check(fm.norm(0, 0) == (fm.x0 / sw, fm.y0 / sh), f"{sw}x{sh} origin")
        ex, ey = fm.norm(1280, 720)
        check(abs(ex - (fm.x0 + fm.crop_w) / sw) < 1e-9 and abs(ey - (fm.y0 + fm.crop_h) / sh) < 1e-9, "far corner")
        for fx, fy in ((0.3, 0.6), (0.5, 0.5), (0.8, 0.25)):
            src = np.zeros((sh, sw, 3), np.uint8)
            px, py = fm.x0 + fx * fm.crop_w, fm.y0 + fy * fm.crop_h          # inside the part fit_frame keeps
            r = max(3, int(0.02 * min(sw, sh)))
            cv2.circle(src, (int(round(px)), int(round(py))), r, (255, 255, 255), -1)
            fitted = fit_frame(src, 1280, 720)
            ys, xs = np.nonzero(fitted[:, :, 0] > 127)
            check(len(xs) > 0, f"{sw}x{sh}: marker lost")
            nx, ny = fm.norm(xs.mean() + 0.5, ys.mean() + 0.5)             # pixel centre -> edge coordinates
            err = max(abs(nx * sw - (round(px) + 0.5)), abs(ny * sh - (round(py) + 0.5)))
            worst = max(worst, err)
            check(err < 1.0, f"{sw}x{sh} marker at ({px:.1f}, {py:.1f}): mapped back {err:.2f} px off")
    return f"{len(sizes)} source sizes, worst {worst:.2f} source px"


def _track(tid: int, cls: str, box) -> tuple[TrackState, Detection]:
    return TrackState(id=tid, cls=cls, bbox=list(box), age_frames=5), Detection(cls, list(box), 0.9 - tid * 0.01, tid)


def t_document_empty() -> str:
    doc = gw.build_document(FrameResult(frameIndex=3, ptsSeconds=1.0), {"width": 1280, "height": 720}, (640, 360), 3,
                            1710000000123)
    check_document(doc)
    check(doc["frameId"] == 3 and doc["timestampMs"] == 1710000000123, "echo")
    check(doc["perception"] == {"lanes": {"currentLane": 2, "laneCount": 3, "boundaries": []}, "vehicles": [],
                                "signs": []}, f"perception {doc['perception']}")
    types = [i["type"] for i in doc["instructions"]]
    check(types == ["LANE_ARROW"] * 3 + ["EXIT_MARKER"], f"types {types}")
    check([i["content"] for i in doc["instructions"][:3]] == ["↑", "↑", "EXIT"], "arrow contents")
    # lanes present but unknown counts -> the (2, 3) fallback; currentLane clamped to laneCount
    for ls, want in ((LaneState(None, 4), (2, 3)), (LaneState(0, 3), (2, 3)), (LaneState(5, 4), (4, 4)),
                     (LaneState(1, 1), (1, 1)), (LaneState(3, 5), (3, 5))):
        d = gw.build_document(FrameResult(0, 0.0, lanes=ls), {"width": 1280, "height": 720}, (640, 360), 0, 0)
        check_document(d)
        got = (d["perception"]["lanes"]["currentLane"], d["perception"]["lanes"]["laneCount"])
        check(got == want, f"{ls}: lanes {got}, want {want}")
        check(d["navigation"]["requiredLane"] == want[1], "requiredLane = laneCount")
    return "fallback lanes 2/3, 3 arrows, highlighted rightmost, exit HUD"


def _lane_polylines_fitted() -> list[list[list[float]]]:
    """4 boundaries (3 lanes) in 1280x720 fitted pixels, bottom point first, 20 points each."""
    out = []
    for xb, xt in ((-150.0, 520.0), (330.0, 610.0), (950.0, 670.0), (1430.0, 760.0)):
        ys = np.linspace(720.0, 420.0, 20)
        out.append([[float(xb + (xt - xb) * (720.0 - y) / 300.0), float(y)] for y in ys])
    return out


def t_document_full() -> str:
    lines = _lane_polylines_fitted()
    ls = LaneState(currentLane=2, laneCount=3, laneBoundaries=lines, confidence=0.8)
    tracks, dets = [], []
    specs = [(1, "car", (560, 430, 720, 560), 18.44),          # in the ego lane, 2nd nearest
             (2, "truck", (1000, 380, 1270, 600), 9.0),        # right lane: nearest overall but not in front
             (3, "car", (600, 400, 680, 470), 35.0),           # in front, far
             (4, "car", (590, 440, 700, 540), 12.25),          # in front, nearest
             (5, "bus", (610, 405, 690, 450), 60.0),           # in front, 4th -> dropped (max 3)
             (6, "pedestrian", (620, 450, 650, 560), 8.0),     # never a vehicle
             (7, "car", (640, 450, 700, 520), None),           # no distance -> dropped
             (8, "car", (640, 450, 642, 520), 20.0),           # too narrow after mapping -> dropped
             (9, "motorcycle", (620, 200, 660, 230), 70.0)]    # bottom above y = 0.4 -> not in front
    for tid, cls, box, _ in specs:
        tr, de = _track(tid, cls, box)
        tracks.append(tr)
        if tid != 3:                                           # track 3 has no detection this frame
            dets.append(de)
    distances = [DistanceEstimate(tid, d, "fused", 0.66) for tid, _, _, d in specs if d is not None]
    distances.append(DistanceEstimate(10, -1.0))               # no track, negative
    signs = [TrafficSign(1_000_001, "speed_limit_45", [900, 100, 960, 170], 0.95),
             TrafficSign(None, "stop", [100, 120, 150, 170], 0.7),
             TrafficSign(1_000_003, "unknown", [200, 120, 250, 170], 0.99),
             TrafficSign(1_000_004, "exit_speed", [300, 100, 380, 150], 0.8),
             TrafficSign(1_000_005, "", [300, 100, 380, 150], 0.8),
             TrafficSign(1_000_006, "yield", [400, 100, 440, 140], 0.5),
             TrafficSign(1_000_007, "do_not_enter", [500, 100, 540, 140], 0.4)]
    res = FrameResult(frameIndex=11, ptsSeconds=2.0, detections=dets, tracks=tracks, distances=distances, lanes=ls,
                      trafficSigns=signs)
    # 640x480 source: fit_frame crops rows 60..420 (crop 640x360) and scales x2
    fm = gw.FitMap.of(640, 480, 1280, 720)
    check((fm.x0, fm.y0, fm.crop_w, fm.crop_h) == (0, 60, 640, 360), f"crop {fm}")
    doc = gw.build_document(res, {"width": 1280, "height": 720}, (640, 480), 11, 99)
    check_document(doc)
    P = doc["perception"]
    b = {x["id"]: x["points"] for x in P["lanes"]["boundaries"]}
    check(set(b) == {"left", "right"} and all(len(p) == 6 for p in b.values()), f"boundaries {b}")

    def want_pt(x, y):
        return [round(min(max(x / 2 / 640, 0), 1), 4), round(min(max((y / 2 + 60) / 480, 0), 1), 4)]
    check(b["left"][0] == want_pt(*lines[1][0]) and b["left"][-1] == want_pt(*lines[1][-1]), f"left {b['left']}")
    check(b["right"][0] == want_pt(*lines[2][0]) and b["right"][-1] == want_pt(*lines[2][-1]), f"right {b['right']}")
    ids = [v["id"] for v in P["vehicles"]]
    check(ids == [4, 1, 3], f"in-front vehicles nearest first: {ids}")
    v1 = next(v for v in P["vehicles"] if v["id"] == 1)
    check(v1["bbox"] == [0.4375, round((215 + 60) / 480, 4), 0.5625, round((280 + 60) / 480, 4)], f"bbox {v1}")
    check(v1["distanceMeters"] == 18.4 and v1["confidence"] == 0.89 and v1["class"] == "car", f"vehicle 1 {v1}")
    v3 = next(v for v in P["vehicles"] if v["id"] == 3)
    check(v3["confidence"] == 0.66, f"no detection -> distance confidence, got {v3['confidence']}")
    marker = [i for i in doc["instructions"] if i["type"] == "VEHICLE_MARKER"]
    check([m["distanceLabel"] for m in marker] == ["12 m", "18 m", "35 m"], f"labels {marker}")
    labels = [i["content"] for i in doc["instructions"] if i["type"] == "DISTANCE_LABEL"]
    check(labels == ["12.2m", "18.4m", "35.0m"], f"distance label contents {labels}")
    S = P["signs"]
    check([s["class"] for s in S] == ["speed_limit_45", "exit", "stop", "yield"], f"signs {S}")
    check([s["label"] for s in S] == ["SPEED LIMIT 45", "EXIT SPEED", "STOP", "YIELD"], f"labels {S}")
    check([s["id"] for s in S] == [1_000_001, 1_000_004, 3, 1_000_006], f"sign ids {[s['id'] for s in S]}")
    types = [i["type"] for i in doc["instructions"]]
    check(types == ["LANE_BOUNDARY"] * 2 + ["LANE_ARROW"] * 3 + ["EXIT_MARKER"]
          + ["VEHICLE_MARKER", "DISTANCE_LABEL"] * 3, f"types {types}")

    # nothing in front -> the single nearest vehicle with a distance
    side = FrameResult(0, 0.0, detections=[dets[1]], tracks=[tracks[1], tracks[8]], lanes=ls,
                       distances=[DistanceEstimate(2, 9.0), DistanceEstimate(9, 70.0)])
    d2 = gw.build_document(side, {"width": 1280, "height": 720}, (640, 480), 0, 0)
    check_document(d2)
    check([v["id"] for v in d2["perception"]["vehicles"]] == [2], f"nearest fallback {d2['perception']['vehicles']}")

    # index pair missing (currentLane 3 of 3 but only 2 lines) -> the lines straddling the centre
    ls2 = LaneState(currentLane=3, laneCount=3, laneBoundaries=[lines[0], lines[1], lines[2]])
    d3 = gw.build_document(FrameResult(0, 0.0, lanes=ls2), {"width": 1280, "height": 720}, (640, 480), 0, 0)
    check_document(d3)
    b3 = {x["id"]: x["points"] for x in d3["perception"]["lanes"]["boundaries"]}
    check(b3["left"][0] == want_pt(*lines[1][0]) and b3["right"][0] == want_pt(*lines[2][0]), f"straddle {b3}")
    # no ego lines: in front = centre x in 0.25..0.75
    no_lanes = FrameResult(0, 0.0, detections=dets, tracks=tracks, distances=distances)
    d4 = gw.build_document(no_lanes, {"width": 1280, "height": 720}, (640, 360), 0, 0)
    check_document(d4)
    check([v["id"] for v in d4["perception"]["vehicles"]] == [4, 1, 3],
          f"no-lane corridor {d4['perception']['vehicles']}")
    return "ego pair by index + straddle, 3 in-front nearest first, nearest fallback, 4 signs, crop undone"


# ============================================================================= server
def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def start_server(port: int, log: Path) -> subprocess.Popen:
    cmd = [sys.executable, "-m", "perception.realtime.glasses_server", "--host", "127.0.0.1", "--port", str(port)]
    log.parent.mkdir(parents=True, exist_ok=True)
    f = open(log, "w", encoding="utf-8")
    return subprocess.Popen(cmd, cwd=str(ENGINE_ROOT), stdout=f, stderr=subprocess.STDOUT,
                            env={**os.environ, "PYTHONUNBUFFERED": "1"})


def get_json(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=3) as r:
        return json.loads(r.read())


def wait_ready(port: int, proc: Optional[subprocess.Popen], timeout: float = 420.0) -> dict:
    t0 = time.time()
    while time.time() - t0 < timeout:
        if proc is not None and proc.poll() is not None:
            raise RuntimeError(f"server exited with code {proc.returncode}")
        with contextlib.suppress(Exception):
            h = get_json(f"http://127.0.0.1:{port}/health")
            if h.get("status") != "loading":
                return h
        time.sleep(1.0)
    raise TimeoutError("server did not become ready")


def clip_frames(n: int, start_s: float = 5.0, step: int = 2) -> list[np.ndarray]:
    """Upright 640x360 BGR frames of the city clip (what the phone's 640-px JPEG decodes to)."""
    import cv2
    cap = cv2.VideoCapture(str(CITY))
    cap.set(cv2.CAP_PROP_ORIENTATION_AUTO, 1)
    cap.set(cv2.CAP_PROP_POS_MSEC, start_s * 1000)
    out, i = [], 0
    while len(out) < n:
        ok, img = cap.read()
        if not ok:
            break
        if i % step == 0:
            out.append(cv2.resize(img, (640, 360), interpolation=cv2.INTER_AREA))
        i += 1
    cap.release()
    check(len(out) == n, f"could not read {n} frames from {CITY}")
    return out


class Phone:
    """What the glasses app does: text frames, no handshake, no waiting; files every reply."""

    def __init__(self, url: str):
        self.url = url
        self.replies: list[dict] = []
        self.raw_binary = 0

    async def __aenter__(self):
        from websockets.asyncio.client import connect
        self.ws = await connect(self.url, max_size=None, compression=None, ping_interval=None, open_timeout=20)
        self.task = asyncio.create_task(self._rx())
        return self

    async def __aexit__(self, *exc):
        self.task.cancel()
        await self.ws.close()

    async def _rx(self):
        with contextlib.suppress(Exception):
            async for raw in self.ws:
                if isinstance(raw, bytes):
                    self.raw_binary += 1
                    continue
                self.replies.append(json.loads(raw))

    async def wait(self, n: int, timeout: float, what: str) -> None:
        t0 = time.monotonic()
        while len(self.replies) < n:
            if time.monotonic() - t0 > timeout:
                raise AssertionError(f"timeout waiting for {what} ({len(self.replies)}/{n} replies)")
            await asyncio.sleep(0.01)


async def basic_tests(url: str) -> str:
    frames = clip_frames(6)
    async with Phone(url) as ph:
        ts = int(time.time() * 1000)
        await ph.ws.send(frame_text(enc(frames[0]), 1, ts))
        await ph.wait(1, 60, "the first document")
        doc = ph.replies[0]
        check("instructions" in doc, f"first reply {str(doc)[:200]}")
        check_document(doc)
        check(doc["frameId"] == 1 and doc["timestampMs"] == ts, "echo")
        # garbage, then a good frame on the same socket
        await ph.ws.send("this is not json")
        await ph.wait(2, 10, "error for garbage")
        check(ph.replies[1] == {"error": "frame is not valid JSON"}, f"garbage reply {ph.replies[1]}")
        await ph.ws.send(json.dumps({"frameId": 2, "encoding": "raw", "data": "x"}))
        await ph.wait(3, 10, "error for wrong encoding")
        check(ph.replies[2] == {"error": "encoding must be jpeg-base64"}, f"{ph.replies[2]}")
        await ph.ws.send(b"SDC1" + bytes(40))                      # binary: never sent by the phone
        await ph.wait(4, 10, "error for binary")
        check(set(ph.replies[3]) == {"error"}, f"binary reply {ph.replies[3]}")
        await ph.ws.send(frame_text(enc(frames[1]), 3, ts + 125))
        await ph.wait(5, 30, "document after errors")
        check("instructions" in ph.replies[4] and ph.replies[4]["frameId"] == 3,
              f"after errors {str(ph.replies[4])[:200]}")
        check_document(ph.replies[4])
        # websocket ping / pong (the phone pings every 15 s)
        t0 = time.perf_counter()
        pong = await ph.ws.ping()
        await asyncio.wait_for(pong, 5)
        ping_ms = (time.perf_counter() - t0) * 1000
        # frameId / timestampMs missing: still answered
        await ph.ws.send(json.dumps({"encoding": "jpeg-base64", "data": base64.b64encode(enc(frames[2])).decode()}))
        await ph.wait(6, 30, "document without ids")
        check("instructions" in ph.replies[5], f"no-id reply {str(ph.replies[5])[:200]}")
        check_document(ph.replies[5])
        check(ph.raw_binary == 0, "binary replies")
    n_veh = len(doc["perception"]["vehicles"])
    return f"document ok ({n_veh} vehicles), errors keep the socket, ping {ping_ms:.0f} ms"


async def burst_tests(url: str) -> str:
    frames = clip_frames(24, start_s=12.0, step=1)
    jpegs = [enc(f) for f in frames]
    async with Phone(url) as ph:
        ts = int(time.time() * 1000)
        t0 = time.monotonic()
        for i, j in enumerate(jpegs):                              # ~8 fps, never waiting for replies
            await ph.ws.send(frame_text(j, 100 + i, ts + 125 * i))
            await asyncio.sleep(0.125)
        sent_s = time.monotonic() - t0
        last = 100 + len(jpegs) - 1
        t1 = time.monotonic()
        while not any(r.get("frameId") == last for r in ph.replies):
            check(time.monotonic() - t1 < 30, "the last frame was never answered")
            await asyncio.sleep(0.01)
        await asyncio.sleep(0.5)
        ids = [r["frameId"] for r in ph.replies]
        check(all("instructions" in r for r in ph.replies),
              f"errors in burst: {[r for r in ph.replies if 'error' in r][:2]}")
        check(len(ids) == len(set(ids)) and ids == sorted(ids), f"replies out of order / duplicated {ids}")
        check(set(ids) <= set(range(100, 100 + len(jpegs))), f"unknown frameIds {ids}")
        for r in ph.replies:
            check_document(r)
            check("type" not in r, f"protocol-v2 message {r.get('type')}")
        veh = sum(len(r["perception"]["vehicles"]) for r in ph.replies)
        bnd = sum(len(r["perception"]["lanes"]["boundaries"]) for r in ph.replies)
    return (f"{len(ids)}/{len(jpegs)} answered in {sent_s:.1f} s ({len(jpegs) - len(ids)} superseded), "
            f"{veh} vehicle and {bnd} boundary entries")


def _pad(img: np.ndarray, top: int, bottom: int, left: int, right: int) -> np.ndarray:
    import cv2
    return cv2.copyMakeBorder(img, top, bottom, left, right, cv2.BORDER_CONSTANT, value=(0, 0, 0))


async def _two_frames(url: str, img: np.ndarray) -> dict:
    """A fresh connection (fresh stream) sending the same image twice: lanes run on the 2nd serial step."""
    async with Phone(url) as ph:
        ts = int(time.time() * 1000)
        j = enc(img, 95)
        h, w = img.shape[:2]
        await ph.ws.send(frame_text(j, 1, ts, w, h))
        await ph.wait(1, 60, "frame 1")
        await ph.ws.send(frame_text(j, 2, ts + 125, w, h))
        await ph.wait(2, 60, "frame 2")
        check(all("instructions" in r for r in ph.replies), f"errors {ph.replies}")
        return ph.replies[1]


async def mapping_tests(url: str) -> str:
    """Same scene as 640x360 and padded wider / taller: fit_frame crops exactly the padding away, so after undoing it
    every box and lane point must describe the same content. Offsets are multiples of 16 (JPEG MCUs line up)."""
    base_img = clip_frames(1, start_s=8.0)[0]
    ref = await _two_frames(url, base_img)
    check(ref["perception"]["vehicles"] or ref["perception"]["lanes"]["boundaries"], "nothing to compare in the scene")
    worst_ok, worst_naive, n_cmp = 0.0, 0.0, 0
    for name, pad in (("taller 640x488", (64, 64, 0, 0)), ("wider 768x360", (0, 0, 64, 64))):
        top, bottom, left, right = pad
        img = _pad(base_img, *pad)
        H, W = img.shape[:2]
        fm = gw.FitMap.of(W, H, 1280, 720)
        check((fm.x0, fm.y0, fm.crop_w, fm.crop_h) == (left, top, 640, 360), f"{name}: crop {fm}")
        got = await _two_frames(url, img)

        def to_ref(x: float, y: float) -> tuple[float, float]:        # padded-JPEG fraction -> 640x360 fraction
            return (x * W - left) / 640.0, (y * H - top) / 360.0

        rv = {v["id"]: v for v in ref["perception"]["vehicles"]}
        for v in got["perception"]["vehicles"]:
            r = rv.get(v["id"])
            if r is None or r["class"] != v["class"]:
                continue
            a = to_ref(v["bbox"][0], v["bbox"][1]) + to_ref(v["bbox"][2], v["bbox"][3])
            err = max(abs(p - q) for p, q in zip(a, r["bbox"]))
            nerr = max(abs(p - q) for p, q in zip(v["bbox"], r["bbox"]))
            worst_ok, worst_naive, n_cmp = max(worst_ok, err), max(worst_naive, nerr), n_cmp + 1
            check(err < 0.02, f"{name}: vehicle {v['id']} box {v['bbox']} -> {a} vs {r['bbox']} ({err:.3f})")
        rb = {b["id"]: b["points"] for b in ref["perception"]["lanes"]["boundaries"]}
        for b in got["perception"]["lanes"]["boundaries"]:
            r = rb.get(b["id"])
            if r is None or len(r) != len(b["points"]):
                continue
            for p, q in zip(b["points"], r):
                if not (0.0 < q[0] < 1.0 and 0.0 < q[1] < 1.0):
                    continue                                       # clipped in the reference: not comparable
                a = to_ref(*p)
                err = max(abs(a[0] - q[0]), abs(a[1] - q[1]))
                worst_ok, n_cmp = max(worst_ok, err), n_cmp + 1
                check(err < 0.03, f"{name}: {b['id']} lane point {p} -> {a} vs {q} ({err:.3f})")
    check(n_cmp >= 2, f"only {n_cmp} comparable boxes / points")
    if worst_naive:
        check(worst_naive > 3 * max(worst_ok, 0.005), f"padding had no visible effect ({worst_naive:.3f})")
    return f"{n_cmp} boxes / lane points agree within {worst_ok:.4f} (raw padded fractions off by {worst_naive:.3f})"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--offline", action="store_true", help="skip the server tests")
    ap.add_argument("--url", default=None, help="use a running glasses server instead of starting one")
    ap.add_argument("--log", type=Path, default=ENGINE_ROOT / "outputs" / "realtime" / "test_glasses_server.log")
    a = ap.parse_args()
    ok = True
    for name, fn in [("decode + bad-frame reasons", t_decode), ("FitMap inverts fit_frame", t_fitmap_inverts_fit_frame),
                     ("document, empty result", t_document_empty), ("document, full result", t_document_full)]:
        ok &= run_test(name, fn)
    if not a.offline:
        proc = None
        url = a.url
        try:
            if url is None:
                port = free_port()
                print(f"[test] starting glasses server on port {port} (log {a.log}) ...", flush=True)
                proc = start_server(port, a.log)
                h = wait_ready(port, proc)
                url = f"ws://127.0.0.1:{port}/ws"
            else:
                port = int(url.rsplit(":", 1)[1].split("/")[0])
                h = wait_ready(port, None)
            ok &= run_test("health", lambda: (check(h == {"status": "ok", "engine": "perception"}, f"health {h}"), str(h))[1])
            ok &= run_test("one frame, errors, ping", lambda: asyncio.run(basic_tests(url)))
            ok &= run_test("8 fps burst, latest wins", lambda: asyncio.run(burst_tests(url)))
            ok &= run_test("fit_frame undone on padded frames", lambda: asyncio.run(mapping_tests(url)))
            st = get_json(f"http://127.0.0.1:{port}/stats")
            print(f"[test] server stats: step {st['stepMs']} in {st['framesIn']} analysed {st['analysed']} "
                  f"dropped {st['dropped']} errors {st['errors']}", flush=True)
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
