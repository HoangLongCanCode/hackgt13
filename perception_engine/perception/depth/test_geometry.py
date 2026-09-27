"""CPU-only sanity checks for the closed-form geometry (no weights needed).

    .venv\\Scripts\\python.exe -m perception.depth.test_geometry
"""
import math

import numpy as np

from perception.common.schemas import Detection
from perception.depth import geometry as G
from perception.depth.distance import DistanceEstimator


def synthetic_road_depth(cam: G.Camera, yh: float, hood_row=None):
    h, w = cam.height, cam.width
    ys = np.arange(h, dtype=np.float32)[:, None].repeat(w, 1)
    with np.errstate(divide="ignore"):
        d = np.where(ys > yh + 1, cam.focal_px * cam.height_m / (ys - yh), 200.0).astype(np.float32)
    if hood_row is not None:
        d[int(hood_row):] = 1.2
    return d


def degenerate_inputs():
    """Regression: virtual_horizon raised ZeroDivisionError (1/sqrt(sum(w)) with sum(w) == 0) in the server's slow
    loop when no cue survived the median gate; each one lost a slow-lane run (real drive 010: no perception.update
    for 4.4 s). Two car boxes whose horizons disagree put the median between them, so both were dropped. Boxes
    are from the recorded frames (1280x720, f=700) at the camera height the server used then."""
    cam = G.Camera.bdd_default()
    cases = [  # (camera height, detections)
        (1.266, [("car", [692.0, 385.3, 884.0, 541.3]), ("car", [1061.3, 212.3, 1202.7, 308.3])]),  # 009 @ 86.6 s
        (1.492, [("car", [793.3, 394.0, 868.0, 448.7]), ("car", [23.8, 650.7, 54.6, 696.0])]),      # 010 @ 20.0 s
    ]
    for hc, dets in cases:
        with np.errstate(all="raise"):  # no 0/0 RuntimeWarning either
            assert G.virtual_horizon(dets, cam, hc) is None, dets
        # the estimator treats it like "no virtual horizon": depth profile / principal-row prior, finite distances
        est = DistanceEstimator(None, cam_height_m=hc, temporal=False)
        out = est.estimate_detailed(np.zeros((720, 1280, 3), np.uint8),
                                    [Detection(c, b, 0.9, id=k) for k, (c, b) in enumerate(dets)])
        assert est.last_info["horizon_source"] == "principal_row", est.last_info
        assert out and all(d["distance"] is not None and math.isfinite(d["distance"]) for d in out), out
    # a single agreeing cue still gives a horizon; non-finite boxes / heights are skipped, not propagated
    one = G.virtual_horizon([cases[0][1][0]], cam, 1.266)
    assert one is not None and one[2] == 1 and all(map(math.isfinite, one[:2])), one
    nan_box = ("car", [float("nan"), 300.0, 700.0, 400.0])
    assert G.virtual_horizon([nan_box], cam) is None
    assert G.virtual_horizon([nan_box, cases[0][1][0]], cam, 1.266) == one
    assert G.virtual_horizon(cases[0][1][:1], cam, float("nan")) is None
    assert G.virtual_horizon([], cam) is None
    # the rest of the family: no exception, no NaN / negative ranges
    assert G.size_distances("car", nan_box[1], cam) == {}
    assert G.ground_distance(500.0, float("nan"), cam) is None
    assert G.ground_distance(float("inf"), 360.0, cam) is None
    assert G.ground_distance(700.0, -5000.0, cam) is None  # bogus horizon: ray behind the vertical
    flat = np.full((720, 1280), 20.0, np.float32)            # no road slope -> no profile
    assert G.fit_road_profile(flat, cam) is None


def main():
    degenerate_inputs()
    cam = G.Camera.bdd_default()  # f=700, cy=360, H=1.3
    # flat ground round trip at pitch 0
    for z in (5, 10, 20, 40, 80):
        yb = cam.cy + cam.focal_px * cam.height_m / z
        zg = G.ground_distance(yb, cam.cy, cam)
        assert abs(zg - z) / z < 1e-6, (z, zg)
    # size prior: a 1.8 m wide, 1.5 m tall car at 20 m
    z = 20.0
    w_px, h_px = 700 * 1.8 / z, 700 * 1.5 / z
    box = [640 - w_px / 2, 400 - h_px, 640 + w_px / 2, 400]
    sz = G.size_distances("car", box, cam)
    assert abs(sz["width"][0] - z) < 1e-6 and abs(sz["height"][0] - z) < 1e-6
    # road profile recovers horizon and camera height, and finds the hood
    yh = 300.0
    d = synthetic_road_depth(cam, yh, hood_row=600)
    prof = G.fit_road_profile(d, cam)
    assert prof is not None and abs(prof.horizon_y - yh) < 2 and abs(prof.height_depth_m - 1.3) < 0.03, prof
    assert prof.hood_row is not None and abs(prof.hood_row - 600) < 8, prof
    assert G.detect_hood_row(d) is not None
    # geometry-only estimator with the given horizon returns ~20 m for the car above (bottom at y=yh+45.5)
    est = DistanceEstimator(None, temporal=False)
    yb = yh + 700 * 1.3 / z
    box = [640 - w_px / 2, yb - h_px, 640 + w_px / 2, yb]
    out = est.estimate(np.zeros((720, 1280, 3), np.uint8), [Detection("car", box, 0.9, id=7)], horizon_y=yh)
    assert len(out) == 1 and abs(out[0].distanceMeters - z) / z < 0.02, out
    assert out[0].method == "fused" and out[0].vehicleId == 7
    # log-space fusion honours weights and inflates on disagreement
    zf, sf, m = DistanceEstimator._fuse({"a": (10.0, 1.0), "b": (10.0, 1.0)})
    assert abs(zf - 10) < 1e-9 and m == "fused" and abs(sf - 10 / math.sqrt(2) / 10 * 1.0) < 0.01
    zf2, sf2, _ = DistanceEstimator._fuse({"a": (10.0, 1.0), "b": (20.0, 2.0)})
    assert 10 < zf2 < 20 and sf2 > sf
    print("geometry checks passed")


if __name__ == "__main__":
    main()
