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


def main():
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
