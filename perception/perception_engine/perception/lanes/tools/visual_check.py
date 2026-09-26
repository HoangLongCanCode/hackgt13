"""Contact sheets for the manual laneCount / currentLane sanity check.

Left: prediction overlay (backend lane mask red, drivable green, ego lane blue, instances
cyan = ego boundaries / orange = counted neighbours / grey = other).
Right: BDD100K GT (YOLOP lane centre lines yellow, drivable direct blue / alternative red)
with the ORACLE lane state (post-processor on GT masks).

    python -m perception.lanes.tools.visual_check --backend twinlitenetplus_large --n 30
Writes outputs/lanes/visual_check/sheet_XX.jpg and visual_check/selection.json.
"""
from __future__ import annotations

import argparse
import json
import random

import cv2
import numpy as np

from perception.common.video import DATA_ROOT, OUTPUTS_ROOT
from perception.lanes.backends import WORK_H, WORK_W
from perception.lanes.lanes import LaneDetector
from perception.lanes.postprocess import PostConfig, process_masks
from perception.lanes.viz import draw_analysis

LABELS = DATA_ROOT / "labels"
KEYFRAMES = ["b1f4491b-cf446195", "b1ff4656-0435391e", "b20e291a-6012d836", "b23adb0d-8a7aaced",
             "b204a5c1-064b0040", "b1d968b9-ce42734f", "b2064e61-2beadd45", "bc07d865-f526e4d9",
             "b780088d-0bf9e946", "c38cd3f6-c74ca48b", "b23d2079-0c019d8e", "b9ecc316-529acd3c",
             "bf1af616-e750e7dd"]


def select(n, seed=3):
    subset = json.loads((LABELS / "eval_subset_det.json").read_text())
    names = [s["name"][:-4] for s in subset]
    rest = [x for x in names if x not in KEYFRAMES]
    random.Random(seed).shuffle(rest)
    return (KEYFRAMES + rest)[:n]


def gt_panel(stem, frame):
    ll = cv2.imread(str(LABELS / "yolop_ll_seg_val" / f"{stem}.png"), cv2.IMREAD_UNCHANGED)[..., 0] > 0
    da = cv2.imread(str(LABELS / "drivable_masks" / f"{stem}.png"), cv2.IMREAD_UNCHANGED)
    img = frame.copy()
    for val, col in ((0, (255, 80, 0)), (1, (0, 0, 255))):
        m = da == val
        img[m] = (img[m] * 0.6 + np.array(col) * 0.4).astype(np.uint8)
    img[cv2.dilate(ll.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0] = (0, 255, 255)
    ll_s = cv2.resize(ll.astype(np.uint8) * 255, (WORK_W, WORK_H), interpolation=cv2.INTER_LINEAR) > 1
    dl_s = cv2.resize(da, (WORK_W, WORK_H), interpolation=cv2.INTER_NEAREST) < 2
    small = cv2.resize(frame, (WORK_W, WORK_H), interpolation=cv2.INTER_AREA)
    st = process_masks(ll_s, dl_s, PostConfig(), frame_small=small)["state"]
    txt = "oracle: unknown" if not st.get("ok") else f"oracle: lane {st['nL'] + 1} of {st['nL'] + 1 + st['nR']}"
    cv2.rectangle(img, (0, 0), (560, 70), (0, 0, 0), -1)
    cv2.putText(img, f"GT {stem[:8]}", (10, 28), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255, 255, 255), 2, cv2.LINE_AA)
    cv2.putText(img, txt, (10, 58), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255, 255, 255), 2, cv2.LINE_AA)
    return img


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", default="twinlitenetplus_large")
    ap.add_argument("--n", type=int, default=30)
    ap.add_argument("--per-sheet", type=int, default=3)
    a = ap.parse_args()
    out = OUTPUTS_ROOT / "lanes" / "visual_check"
    out.mkdir(parents=True, exist_ok=True)
    det = LaneDetector(a.backend)
    names = select(a.n)
    rows, sheet, sel = [], 0, []
    for k, stem in enumerate(names):
        frame = cv2.imread(str(DATA_ROOT / "images" / "val" / f"{stem}.jpg"))
        an = det.analyze(frame)
        left = draw_analysis(frame, an, title=f"#{k} {stem[:8]} {a.backend}")
        right = gt_panel(stem, frame)
        rows.append(np.hstack([cv2.resize(left, (960, 540)), cv2.resize(right, (960, 540))]))
        sel.append({"idx": k, "name": stem, "pred": [an.lane_state.laneCount, an.lane_state.currentLane],
                    "conf": an.lane_state.confidence})
        if len(rows) == a.per_sheet or k == len(names) - 1:
            cv2.imwrite(str(out / f"sheet_{sheet:02d}.jpg"), np.vstack(rows), [cv2.IMWRITE_JPEG_QUALITY, 85])
            rows, sheet = [], sheet + 1
    (out / "selection.json").write_text(json.dumps({"backend": a.backend, "items": sel}, indent=1))
    det.close()
    print("wrote", sheet, "sheets to", out)


if __name__ == "__main__":
    main()
