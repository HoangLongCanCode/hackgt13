"""Measure what the YOLOP lane / drivable GT actually contains (answers "how wide are the lines at
1280x720 and which BDD lane categories are drawn").

    python -m perception.lanes.tools.inspect_yolop_gt
Prints JSON and writes outputs/lanes/yolop_gt_inspection.json.
"""
from __future__ import annotations

import collections
import glob
import json
import os

import cv2
import numpy as np
from skimage.morphology import skeletonize

from perception.common.video import DATA_ROOT, OUTPUTS_ROOT
from perception.lanes.eval_bdd import sample_poly2d

LAB = DATA_ROOT / "labels"


def width_ratio(mask):
    return float(mask.sum() / max(skeletonize(mask).sum(), 1))


def main():
    files = sorted(glob.glob(str(LAB / "yolop_ll_seg_val" / "*.png")))
    m0 = cv2.imread(files[0], cv2.IMREAD_UNCHANGED)
    out = {"n_masks": len(files), "shape": list(m0.shape), "dtype": str(m0.dtype),
           "lane_values": np.unique(m0[..., 0]).tolist()}
    ws = [width_ratio(cv2.imread(f, cv2.IMREAD_UNCHANGED)[..., 0] > 0) for f in files[:80]]
    out["lane_area_over_skeleton_p10_p50_p90"] = np.percentile(ws, [10, 50, 90]).round(2).tolist()
    synth = {}
    for t in (1, 2, 3):
        a = np.zeros((720, 1280), np.uint8)
        for x0 in (100, 400, 700):
            cv2.polylines(a, [np.array([[x0, 700], [x0 + 300, 300]], np.int32)], False, 255, t)
            cv2.polylines(a, [np.array([[x0 + 500, 700], [x0 + 450, 300]], np.int32)], False, 255, t)
        synth[f"cv2_thickness_{t}"] = round(width_ratio(a > 0), 2)
    out["synthetic_area_over_skeleton"] = synth
    # which lane categories are drawn: median distance of each labelled polyline to the GT mask
    subset = json.loads((LAB / "eval_subset_det.json").read_text())
    stat = collections.defaultdict(list)
    for im in subset:
        g = cv2.imread(str(LAB / "yolop_ll_seg_val" / im["name"].replace(".jpg", ".png")), cv2.IMREAD_UNCHANGED)
        if g is None:
            continue
        dt = cv2.distanceTransform((~(g[..., 0] > 0)).astype(np.uint8), cv2.DIST_L2, 5)
        for l in im["labels"]:
            if l["category"] != "lane":
                continue
            for p in l["poly2d"]:
                s = sample_poly2d(p)
                s = s[(s[:, 0] >= 0) & (s[:, 0] < 1280) & (s[:, 1] >= 0) & (s[:, 1] < 720)]
                if len(s):
                    d = dt[s[:, 1].astype(int).clip(0, 719), s[:, 0].astype(int).clip(0, 1279)]
                    a = l["attributes"]
                    stat[f"{a['laneDirection']}/{a['laneType']}"].append(float(np.median(d)))
    out["category_median_px_to_gt"] = {k: {"n": len(v), "median": round(float(np.median(v)), 1),
                                           "share_within_15px": round(float((np.array(v) < 15).mean()), 2)}
                                       for k, v in sorted(stat.items())}
    # drivable: YOLOP values vs local direct/alternative/background
    C = np.zeros((4, 3))
    vals = [0, 127, 191, 255]
    for f in sorted(glob.glob(str(LAB / "yolop_da_seg_val" / "*.png"))):
        y = cv2.imread(f, cv2.IMREAD_UNCHANGED)[..., 0]
        l = cv2.imread(str(LAB / "drivable_masks" / os.path.basename(f)), cv2.IMREAD_UNCHANGED)
        for i, v in enumerate(vals):
            for j in range(3):
                C[i, j] += np.count_nonzero((y == v) & (l == j))
    out["drivable_yolop_value_vs_local_label_Mpx"] = {
        f"yolop_{v}": {"direct": round(C[i, 0] / 1e6, 2), "alternative": round(C[i, 1] / 1e6, 2),
                       "background": round(C[i, 2] / 1e6, 2)} for i, v in enumerate(vals)}
    print(json.dumps(out, indent=1))
    (OUTPUTS_ROOT / "lanes" / "yolop_gt_inspection.json").write_text(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
