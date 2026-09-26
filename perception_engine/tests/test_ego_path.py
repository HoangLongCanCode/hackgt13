"""`inEgoPath` regression tests (perception.realtime.wire.EgoPath), plain Python, no server, no GPU.

AI Spatial Driving Copilot. Run from perception_engine/:

    python tests/test_ego_path.py

The real case is the frame at pts 12.58 s of the city clip b1ff4656-0435391e, taken from the end-to-end sim run
(bridge-cli sim --dump-snapshot --dump-at 12.2): session camera, the lanes block's road geometry (anchors and its
ego-LANE polygon, which spans ~9 m and stops ~5 m ahead) and the car boxes. Before EgoPath, the parked car
beside the ego car (#30) was "in path" and the real lead at ~12 m (#2) was not.
"""
from __future__ import annotations

import os
import sys
import traceback
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
os.environ.setdefault("YOLO_AUTOINSTALL", "False")

from perception.common.schemas import RoadGeometry  # noqa: E402
from perception.realtime.wire import EgoPath, ego_polygon, in_ego_path  # noqa: E402

W, H = 1280, 720
CAMERA = {"focalPx": 700.0, "principalPoint": [640.0, 360.0], "horizonY": 370.8, "cameraHeightMeters": 1.355}
ROAD_12S = RoadGeometry(
    drivableCoverage=0.1785,
    egoPathPolygon=[[-247.6, 718.0], [193.3, 550.0], [1164.9, 550.0], [1817.8, 718.0]],
    horizonY=400.8,
    vanishingPoint=None,
    anchorPoints=[
        {"name": "ego_lane_center_near", "xy": [755.1, 670.4]},
        {"name": "ego_lane_center_mid", "xy": [705.1, 591.1]},
        {"name": "ego_lane_center_far", "xy": [655.0, 511.8]},
        {"name": "right_lane_center_mid", "xy": [1594.0, 591.1]},
    ],
)
# track id -> (bbox, expected inEgoPath, what it is in the video)
CARS_12S = {
    2: ([455.0, 365.0, 565.0, 451.0], True, "lead sedan ~12 m ahead in the ego lane (road heads ~6 deg left of the camera axis)"),
    30: ([955.0, 359.0, 1280.0, 703.0], False, "parked car right beside the ego car"),
    35: ([809.0, 375.0, 1057.0, 532.0], False, "parked car at the kerb ~6 m ahead"),
    41: ([730.0, 353.0, 903.0, 476.0], False, "parked car at the kerb ~9 m ahead"),
    38: ([681.0, 370.0, 763.0, 451.0], False, "parked car at the kerb ~12 m ahead"),
}


def box_at(lateral_m: float, z_m: float, width_m: float = 1.8, height_m: float = 1.5, cam=CAMERA) -> list[float]:
    """Image box of a car whose centre is `lateral_m` right of the camera axis, `z_m` ahead, on flat ground."""
    f, (cx, cy), h, yh = cam["focalPx"], cam["principalPoint"], cam["cameraHeightMeters"], cam["horizonY"]
    import math
    pitch = math.atan((cy - yh) / f)
    y2 = cy + f * math.tan(math.atan(h / z_m) - pitch)
    zc = z_m * math.cos(pitch) + h * math.sin(pitch)
    x1, x2 = cx + f * (lateral_m - width_m / 2) / zc, cx + f * (lateral_m + width_m / 2) / zc
    return [x1, y2 - f * height_m / zc, x2, y2]


def test_real_frame_parked_cars_out_lead_in():
    ego = EgoPath(ROAD_12S, CAMERA, W, H)
    assert ego.source == "corridor+lane", ego.source
    assert 540.0 < ego.x_vp < 600.0, f"lane direction vanishing x {ego.x_vp:.1f} (expected ~566)"
    poly, _ = ego_polygon(ROAD_12S, W, H)
    lines = []
    for tid, (bbox, want, what) in CARS_12S.items():
        got = ego.contains(bbox)
        old = in_ego_path(bbox, poly)
        lines.append(f"#{tid}: inEgoPath {got} (old polygon rule {old}) - {what}")
        assert got == want, f"#{tid} {what}: inEgoPath {got}, expected {want}"
    assert in_ego_path(CARS_12S[30][0], poly) and not in_ego_path(CARS_12S[2][0], poly), "old rule changed?"
    return "; ".join(lines)


def test_synthetic_corridor():
    road = RoadGeometry(drivableCoverage=0.3, egoPathPolygon=[], horizonY=None, vanishingPoint=None, anchorPoints=[])
    ego = EgoPath(road, CAMERA, W, H)
    assert ego.source == "corridor" and ego.x_vp == 640.0
    for z in (4.0, 12.0, 30.0, 60.0):
        assert ego.contains(box_at(0.0, z)), f"car straight ahead at {z} m"
        assert ego.contains(box_at(1.0, z)), f"car 1 m right at {z} m (overlaps the ego corridor)"
        assert not ego.contains(box_at(3.4, z)), f"car in the right lane at {z} m"
        assert not ego.contains(box_at(-3.4, z)), f"car in the left lane at {z} m"
        assert not ego.contains(box_at(2.6, z)), f"parked car (centre 2.6 m right) at {z} m"
    assert not ego.contains(box_at(0.0, 120.0)), "beyond EGO_PATH_MAX_M"
    # a box whose bottom is above the horizon is never in path
    assert not ego.contains([600.0, 300.0, 680.0, 360.0])
    return "ahead in, adjacent lanes / parked out at 4-60 m, > 80 m out"


def test_yaw_clamp_and_fallback():
    # absurd anchors (lane centre far to the right) -> the yaw is clamped to +-8 deg
    road = RoadGeometry(drivableCoverage=0.3, egoPathPolygon=[], horizonY=None, vanishingPoint=None, anchorPoints=[
        {"name": "ego_lane_center_near", "xy": [640.0, 700.0]},
        {"name": "ego_lane_center_far", "xy": [1200.0, 420.0]},
    ])
    ego = EgoPath(road, CAMERA, W, H)
    import math
    assert abs(ego.x_vp - (640.0 + 700.0 * math.tan(math.radians(8.0)))) < 1e-6, ego.x_vp
    # no camera height / horizon -> the old polygon rule (static corridor without a road polygon)
    ego2 = EgoPath(None, {"focalPx": 700.0, "principalPoint": [640.0, 360.0]}, W, H)
    assert not ego2.metric and ego2.source == "static"
    assert ego2.contains([600.0, 500.0, 680.0, 700.0]) and not ego2.contains([1100.0, 500.0, 1270.0, 700.0])
    return f"yaw clamped to x_vp {ego.x_vp:.1f}; no geometry -> {ego2.source} polygon"


def main() -> int:
    tests = [test_real_frame_parked_cars_out_lead_in, test_synthetic_corridor, test_yaw_clamp_and_fallback]
    failed = 0
    for t in tests:
        try:
            info = t()
            print(f"PASS {t.__name__}: {info}")
        except Exception as e:  # noqa: BLE001
            failed += 1
            print(f"FAIL {t.__name__}: {type(e).__name__}: {e}")
            traceback.print_exc()
    print(f"{len(tests) - failed}/{len(tests)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
