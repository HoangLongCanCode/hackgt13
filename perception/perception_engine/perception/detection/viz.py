"""Drawing helpers for detection overlays (video + PNG). Colours come from the dataviz reference palette."""
from __future__ import annotations

import cv2
import numpy as np

from perception.common.schemas import Detection

# BGR. Colour encodes the plan-level group; the class name is always written as text.
_HEX = {"vehicle": "#2a78d6", "vru": "#eb6834", "light": "#eda100", "sign": "#1baf7a"}
GROUP = {"car": "vehicle", "truck": "vehicle", "bus": "vehicle", "train": "vehicle", "motorcycle": "vehicle",
         "bicycle": "vehicle", "pedestrian": "vru", "rider": "vru", "traffic light": "light", "traffic sign": "sign"}


def _bgr(h: str) -> tuple[int, int, int]:
    h = h.lstrip("#")
    return int(h[4:6], 16), int(h[2:4], 16), int(h[0:2], 16)


COLORS = {g: _bgr(h) for g, h in _HEX.items()}
SHORT = {"traffic light": "light", "traffic sign": "sign", "pedestrian": "ped", "motorcycle": "moto"}


def draw_detections(img: np.ndarray, dets: list[Detection], show_conf: bool = True, thickness: int = 2) -> np.ndarray:
    out = img
    for d in sorted(dets, key=lambda d: d.confidence):
        x1, y1, x2, y2 = (int(round(v)) for v in d.bbox)
        col = COLORS[GROUP.get(d.cls, "vehicle")]
        cv2.rectangle(out, (x1, y1), (x2, y2), col, thickness, cv2.LINE_AA)
        label = SHORT.get(d.cls, d.cls) + (f" {d.confidence:.2f}" if show_conf else "")
        (tw, th), _ = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.42, 1)
        ty = y1 - 3 if y1 - th - 5 >= 0 else y2 + th + 3
        cv2.rectangle(out, (x1, ty - th - 3), (x1 + tw + 4, ty + 2), col, -1)
        cv2.putText(out, label, (x1 + 2, ty - 1), cv2.FONT_HERSHEY_SIMPLEX, 0.42, (255, 255, 255), 1, cv2.LINE_AA)
    return out


def draw_hud(img: np.ndarray, lines: list[str]) -> np.ndarray:
    pad, lh = 8, 20
    w = max(cv2.getTextSize(s, cv2.FONT_HERSHEY_SIMPLEX, 0.5, 1)[0][0] for s in lines) + 2 * pad
    h = lh * len(lines) + pad
    roi = img[0:h, 0:w]
    roi[:] = (roi * 0.35).astype(np.uint8)
    for i, s in enumerate(lines):
        cv2.putText(img, s, (pad, pad + 12 + i * lh), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1, cv2.LINE_AA)
    return img


def draw_legend(img: np.ndarray) -> np.ndarray:
    items = [("vehicle", "vehicle"), ("pedestrian / rider", "vru"), ("traffic light", "light"), ("traffic sign", "sign")]
    x, y = img.shape[1] - 170, 10
    for name, g in items:
        cv2.rectangle(img, (x, y), (x + 14, y + 14), COLORS[g], -1)
        cv2.putText(img, name, (x + 20, y + 12), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (255, 255, 255), 1, cv2.LINE_AA)
        y += 20
    return img
