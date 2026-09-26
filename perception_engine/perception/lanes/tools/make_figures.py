"""PNG figures for the lane block README.

    python -m perception.lanes.tools.make_figures
Writes outputs/lanes/backend_compare.png (GT | 4 backends on 4 BDD100K val images) and
outputs/lanes/lane_state_examples.png (TLNP+ Large overlays with lane state).
"""
from __future__ import annotations

import cv2
import numpy as np

from perception.common.video import DATA_ROOT, OUTPUTS_ROOT
from perception.lanes.lanes import BACKENDS, LaneDetector
from perception.lanes.viz import draw_analysis

IMGS = ["b1f4491b-cf446195", "b780088d-0bf9e946", "c6265010-8caa470f", "b9ecc316-529acd3c"]
EXAMPLES = ["b204a5c1-064b0040", "b1d968b9-ce42734f", "ca068cb6-1418f2fb", "c02fb811-03f28b56",
            "b23d2079-0c019d8e", "bc16f8e9-930d98ee"]
TW, TH = 480, 270


def label(img, text):
    cv2.rectangle(img, (0, 0), (img.shape[1], 26), (0, 0, 0), -1)
    cv2.putText(img, text, (6, 19), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (255, 255, 255), 1, cv2.LINE_AA)
    return img


def gt_tile(stem, frame):
    ll = cv2.imread(str(DATA_ROOT / "labels" / "yolop_ll_seg_val" / f"{stem}.png"), cv2.IMREAD_UNCHANGED)[..., 0] > 0
    da = cv2.imread(str(DATA_ROOT / "labels" / "drivable_masks" / f"{stem}.png"), cv2.IMREAD_UNCHANGED)
    img = frame.copy()
    for val, col in ((0, (255, 80, 0)), (1, (0, 0, 255))):
        m = da == val
        img[m] = (img[m] * 0.6 + np.array(col) * 0.4).astype(np.uint8)
    img[cv2.dilate(ll.astype(np.uint8), np.ones((5, 5), np.uint8)) > 0] = (0, 255, 255)
    return label(cv2.resize(img, (TW, TH), interpolation=cv2.INTER_AREA), f"GT {stem[:8]} (direct blue / alt red)")


def seg_tile(frame, masks, name):
    img = frame.copy()
    d = masks["drivable_mask"].astype(bool)
    img[d] = (img[d] * 0.6 + np.array((0, 160, 0)) * 0.4).astype(np.uint8)
    l = cv2.dilate(masks["lane_mask"], np.ones((3, 3), np.uint8)) > 0
    img[l] = (0, 0, 255)
    return label(cv2.resize(img, (TW, TH), interpolation=cv2.INTER_AREA), name)


def main():
    out = OUTPUTS_ROOT / "lanes"
    frames = {s: cv2.imread(str(DATA_ROOT / "images" / "val" / f"{s}.jpg")) for s in IMGS + EXAMPLES}
    cols = [[gt_tile(s, frames[s]) for s in IMGS]]
    for b in BACKENDS:
        det = LaneDetector(b)
        cols.append([seg_tile(frames[s], det.infer(frames[s]), b) for s in IMGS])
        if b == "twinlitenetplus_large":
            ex = [cv2.resize(draw_analysis(frames[s], det.analyze(frames[s]), title=s[:8]), (640, 360),
                             interpolation=cv2.INTER_AREA) for s in EXAMPLES]
            grid = np.vstack([np.hstack(ex[i:i + 3]) for i in range(0, len(ex), 3)])
            cv2.imwrite(str(out / "lane_state_examples.png"), grid)
        det.close()
    grid = np.vstack([np.hstack([c[r] for c in cols]) for r in range(len(IMGS))])
    cv2.imwrite(str(out / "backend_compare.png"), grid)
    print("wrote", out / "backend_compare.png", out / "lane_state_examples.png")


if __name__ == "__main__":
    main()
