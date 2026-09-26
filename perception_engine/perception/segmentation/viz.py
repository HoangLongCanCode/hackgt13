"""Debug overlays for the segmentation block (class colors, ego polygon, corridor, horizon, anchors)."""
from __future__ import annotations

from typing import Any, Optional

import cv2
import numpy as np

from perception.common.schemas import RoadGeometry
from perception.segmentation.semantic import colorize


def draw_overlay(frame_bgr: np.ndarray, label: Optional[np.ndarray], geo: RoadGeometry,
                 info: Optional[dict[str, Any]] = None, alpha: float = 0.45,
                 hud: Optional[str] = None) -> np.ndarray:
    out = frame_bgr.copy()
    if label is not None:
        col = colorize(label)
        out = cv2.addWeighted(out, 1.0 - alpha, col, alpha, 0)
    H, W = out.shape[:2]
    info = info or {}
    corr = info.get("egoCorridorPolygon") or []
    if len(corr) >= 3:
        layer = out.copy()
        cv2.fillPoly(layer, [np.round(np.array(corr)).astype(np.int32)], (80, 255, 80))
        out = cv2.addWeighted(out, 0.6, layer, 0.4, 0)
    if len(geo.egoPathPolygon) >= 3:
        cv2.polylines(out, [np.round(np.array(geo.egoPathPolygon)).astype(np.int32)], True,
                      (255, 255, 255), 2, cv2.LINE_AA)
    if geo.horizonY is not None:
        y = int(round(geo.horizonY))
        cv2.line(out, (0, y), (W - 1, y), (0, 255, 255), 1, cv2.LINE_AA)
    if geo.vanishingPoint:
        vx, vy = (int(round(c)) for c in geo.vanishingPoint)
        cv2.drawMarker(out, (vx, vy), (0, 255, 255), cv2.MARKER_CROSS, 18, 2)
    for a in geo.anchorPoints:
        if "polyline" in a and len(a["polyline"]) >= 2:
            cv2.polylines(out, [np.round(np.array(a["polyline"])).astype(np.int32)], False,
                          (0, 200, 0), 2, cv2.LINE_AA)
            continue
        if not a.get("xy"):
            continue
        x, y = (int(round(c)) for c in a["xy"])
        color = (0, 140, 255) if a.get("valid") else (160, 160, 160)
        if a.get("occludedBy"):
            color = (0, 0, 255)
        if a.get("roadEdgesXY"):
            (xl, yl), (xr, yr) = a["roadEdgesXY"]
            cv2.line(out, (int(xl), int(yl)), (int(xr), int(yr)), color, 1, cv2.LINE_AA)
        # a small upward chevron = where an AR arrow would be pinned
        s = max(6, int(40 * 10.0 / max(a["distanceM"], 1.0)))
        pts = np.array([[x - s, y + s // 2], [x, y - s // 2], [x + s, y + s // 2]], np.int32)
        cv2.polylines(out, [pts], False, color, 3, cv2.LINE_AA)
        cv2.circle(out, (x, y), 4, color, -1, cv2.LINE_AA)
        txt = f"{int(a['distanceM'])} m" + ("" if a.get("valid") else " ?")
        cv2.putText(out, txt, (x + s + 4, y + 5), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (0, 0, 0), 3, cv2.LINE_AA)
        cv2.putText(out, txt, (x + s + 4, y + 5), cv2.FONT_HERSHEY_SIMPLEX, 0.55, color, 1, cv2.LINE_AA)
    if hud:
        cv2.rectangle(out, (0, 0), (W, 26), (0, 0, 0), -1)
        cv2.putText(out, hud, (8, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (255, 255, 255), 1, cv2.LINE_AA)
    return out
