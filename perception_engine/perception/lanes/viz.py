"""Overlay drawing for the lane block (debug / Glass-mode previews)."""
from __future__ import annotations

from typing import Optional

import cv2
import numpy as np

SIDE_COLORS = {"ego_left": (255, 255, 0), "ego_right": (255, 255, 0)}   # BGR cyan
OTHER_COUNTED = (0, 165, 255)            # orange
OTHER = (160, 160, 160)


def _tint(img, mask, color, alpha):
    if mask is None or not mask.any():
        return
    m = mask.astype(bool)
    img[m] = (img[m] * (1 - alpha) + np.array(color, np.float32) * alpha).astype(np.uint8)


def draw_analysis(frame_bgr: np.ndarray, a, title: Optional[str] = None, show_mask: bool = True,
                  gt_lane: Optional[np.ndarray] = None) -> np.ndarray:
    """a: LaneAnalysis (masks at frame size)."""
    img = frame_bgr.copy()
    H, W = img.shape[:2]
    m = a.masks
    drv = m.get("drivable_mask")
    if drv is not None and drv.shape[:2] != (H, W):
        drv = cv2.resize(drv, (W, H), interpolation=cv2.INTER_NEAREST)
    _tint(img, drv, (0, 160, 0), 0.30)
    ego = m.get("ego_lane_mask")
    if ego is not None:
        if ego.shape[:2] != (H, W):
            ego = cv2.resize(ego, (W, H), interpolation=cv2.INTER_NEAREST)
        _tint(img, ego, (255, 120, 0), 0.30)
    if show_mask:
        lm = m.get("lane_mask")
        if lm is not None:
            if lm.shape[:2] != (H, W):
                lm = cv2.resize(lm, (W, H), interpolation=cv2.INTER_NEAREST)
            _tint(img, lm, (0, 0, 255), 0.55)
    if gt_lane is not None:
        _tint(img, gt_lane, (255, 0, 255), 0.8)
    st = a.lane_state
    meta = a.extras.get("boundaries", [])
    for pts, md in zip(st.laneBoundaries, meta):
        p = np.array(pts, np.float32).round().astype(np.int32)
        side = md["side"]
        col = SIDE_COLORS.get(side, OTHER_COUNTED if md.get("counted") else OTHER)
        th = 4 if side.startswith("ego") else 2
        cv2.polylines(img, [p], False, col, th, cv2.LINE_AA)
        if md.get("color") == "yellow" or md.get("style") != "unknown":
            tag = ("Y" if md.get("color") == "yellow" else "") + {"solid": "s", "dashed": "d"}.get(md.get("style"), "")
            x, y = p[-1]
            if 0 <= x < W and 0 <= y < H and tag:
                cv2.putText(img, tag, (int(x) + 4, int(y) - 4), cv2.FONT_HERSHEY_SIMPLEX, 0.6, col, 2, cv2.LINE_AA)
    for anc in a.road.anchorPoints:
        x, y = anc["xy"]
        if 0 <= x < W and 0 <= y < H:
            cv2.circle(img, (int(x), int(y)), 5, (255, 255, 255) if anc["name"].startswith("ego") else (255, 200, 0),
                       -1, cv2.LINE_AA)
    if a.road.vanishingPoint:
        vx, vy = a.road.vanishingPoint
        if 0 <= vx < W and 0 <= vy < H:
            cv2.drawMarker(img, (int(vx), int(vy)), (0, 255, 255), cv2.MARKER_CROSS, 18, 2)
    # text panel
    if st.laneCount is None:
        txt = "lane: unknown"
    else:
        txt = f"lane {st.currentLane} of {st.laneCount}"
    lines = [f"{txt}   conf {st.confidence:.2f}"]
    ex = a.extras
    if ex.get("egoBoundaryKinds"):
        lines.append(f"ego L/R: {ex['egoBoundaryKinds'][0]}/{ex['egoBoundaryKinds'][1]}  w {ex.get('egoLaneWidthU')}  "
                     f"unmarked+{ex.get('unmarkedLanesCounted', 0)}  off {ex.get('egoLateralOffsetNorm')}")
    if ex.get("event"):
        lines.append(ex["event"])
    if title:
        lines.insert(0, title)
    y0 = 30
    box_h = 28 * len(lines) + 12
    cv2.rectangle(img, (0, 0), (min(W, 640), box_h), (0, 0, 0), -1)
    for i, s in enumerate(lines):
        cv2.putText(img, s, (10, y0 + 28 * i), cv2.FONT_HERSHEY_SIMPLEX, 0.75 if i else 0.8, (255, 255, 255), 2,
                    cv2.LINE_AA)
    return img
