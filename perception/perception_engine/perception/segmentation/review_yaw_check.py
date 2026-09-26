"""Review check: ego-path anchors with and without the yaw-from-VP fix (measured here).

    .venv\\Scripts\\python.exe -m perception.segmentation.review_yaw_check

(run from perception_engine/). Segments the 250 eval keyframes and the 120 dev keyframes
once with efficientvit_b1, then runs the deterministic geometry twice (GeometryConfig
yaw_from_vp=False = builder's original projection, True = review fix). For each set it scores
drawable anchors (valid, not occluded) against GT drivable masks: the rasterised eval masks,
and for the dev set masks rasterised here with the data tool's own rasteriser
(tools/bdd/build_eval_subset.py drivable_mask). The dev set has no tuned
parameters of the fix, so it is an independent confirmation. Writes
outputs/segmentation/review_yaw_check.json.
"""
from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path

os.environ.setdefault("YOLO_AUTOINSTALL", "False")

import cv2
import numpy as np

from perception.common.video import DATA_ROOT, OUTPUTS_ROOT
from perception.segmentation.eval_bdd import direct_lane_centre_err
from perception.segmentation.geometry import GeometryConfig, compute_road_geometry
from perception.segmentation.semantic import SemanticSegmenter, bdd_camera

OUT = OUTPUTS_ROOT / "segmentation"


def _drivable_rasteriser():
    from perception.common.paths import TOOLS_ROOT
    spec = importlib.util.spec_from_file_location("_bes", TOOLS_ROOT / "bdd" / "build_eval_subset.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.drivable_mask


def main():
    raster = _drivable_rasteriser()
    ev = json.load(open(DATA_ROOT / "labels" / "eval_subset_det.json", encoding="utf-8"))
    dev = json.load(open(OUT / "devset" / "dev_labels.json", encoding="utf-8"))
    sets = {
        "eval250": [(DATA_ROOT / "images" / "val" / e["name"],
                     lambda e=e: cv2.imread(str(DATA_ROOT / "labels" / "drivable_masks" / (Path(e["name"]).stem + ".png")),
                                            cv2.IMREAD_UNCHANGED)) for e in ev],
        "dev120": [(OUT / "devset" / "images" / e["name"], lambda e=e: raster(e)) for e in dev],
    }
    seg = SemanticSegmenter(backend="efficientvit_b1", device="cuda")
    cache = {k: [(cv2.imread(str(p)), mk()) for p, mk in v] for k, v in sets.items()}
    labels = {k: [seg.segment(img) for img, _ in v] for k, v in cache.items()}
    seg.release()
    out = {}
    for sname, items in cache.items():
        for yaw in (False, True):
            cfg = GeometryConfig(yaw_from_vp=yaw)
            per_d = {d: [] for d in (10, 20, 40)}
            n_img = 0
            for (img, dm), lab in zip(items, labels[sname]):
                drv, direct = dm <= 1, dm == 0
                if not drv.any():
                    continue
                n_img += 1
                geo, _ = compute_road_geometry(lab, bdd_camera(), cfg, None, frame_bgr=img)
                for a in geo.anchorPoints:
                    if "distanceM" in a and a["valid"] and a["occludedBy"] is None and a["xy"]:
                        x = min(max(int(round(a["xy"][0])), 0), 1279)
                        y = min(max(int(round(a["xy"][1])), 0), 719)
                        per_d[int(a["distanceM"])].append((bool(drv[y, x]), bool(direct[y, x]),
                                                            direct_lane_centre_err(direct, x, y)))
            res = {"N_imagesWithDrivable": n_img}
            for d, v in per_d.items():
                errs = [t[2] for t in v if t[2] is not None]
                res[f"{d}m"] = {"drawable": len(v),
                                "inDrivable_pct": round(100 * float(np.mean([t[0] for t in v])), 1) if v else None,
                                "inDirect_pct": round(100 * float(np.mean([t[1] for t in v])), 1) if v else None,
                                "medianErrToDirectLaneCentre_px": round(float(np.median(errs)), 1) if errs else None}
            key = f"{sname}/yaw_from_vp={yaw}"
            out[key] = res
            print(key, json.dumps(res), flush=True)
    with open(OUT / "review_yaw_check.json", "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1)


if __name__ == "__main__":
    main()
