"""Reference vanishing point / horizon from BDD100K lane poly2d labels (for evaluation only).

BDD100K has no horizon ground truth. For images whose labelled 'parallel' lane
markings / curbs are straight ('L' vertices), the lines meet at the road's
vanishing point. We intersect every pair of such lines that differ in angle by
>= 8 deg, take the median intersection and accept it only when the intersections
agree (MAD of y <= 12 px). Curved roads fail that test and get no reference.
"""
from __future__ import annotations

import itertools
import math
from typing import Any, Optional

import numpy as np


def _lines_from_labels(labels: list[dict[str, Any]], min_len: float = 40.0, min_angle_deg: float = 10.0):
    lines = []
    for lab in labels:
        if lab.get("category") != "lane":
            continue
        if (lab.get("attributes") or {}).get("laneDirection") != "parallel":
            continue
        for p in lab.get("poly2d") or []:
            if set(p.get("types", "")) != {"L"}:
                continue  # curves (Bezier 'C') excluded
            v = np.asarray(p["vertices"], float)
            if len(v) < 2:
                continue
            a, b = v[0], v[-1]
            d = b - a
            L = float(np.hypot(*d))
            if L < min_len:
                continue
            ang = math.degrees(math.atan2(abs(d[1]), abs(d[0])))
            if ang < min_angle_deg:
                continue
            # homogeneous line through a, b
            lines.append((np.cross([a[0], a[1], 1.0], [b[0], b[1], 1.0]), math.atan2(d[1], d[0])))
    return lines


def gt_vanishing_point(labels: list[dict[str, Any]], W: int = 1280, H: int = 720,
                       min_pair_angle_deg: float = 8.0, max_mad_px: float = 12.0) -> Optional[dict[str, float]]:
    lines = _lines_from_labels(labels)
    if len(lines) < 2:
        return None
    pts = []
    for (l1, a1), (l2, a2) in itertools.combinations(lines, 2):
        da = abs((math.degrees(a1 - a2) + 90) % 180 - 90)
        if da < min_pair_angle_deg:
            continue
        p = np.cross(l1, l2)
        if abs(p[2]) < 1e-9:
            continue
        x, y = p[0] / p[2], p[1] / p[2]
        if -0.5 * W <= x <= 1.5 * W and 0.05 * H <= y <= 0.85 * H:
            pts.append((x, y))
    if not pts:
        return None
    pts = np.array(pts)
    y_med, x_med = float(np.median(pts[:, 1])), float(np.median(pts[:, 0]))
    mad = float(np.median(np.abs(pts[:, 1] - y_med))) if len(pts) > 1 else 0.0
    if mad > max_mad_px:
        return None
    return {"x": x_med, "y": y_med, "pairs": int(len(pts)), "lines": len(lines), "madY": mad}
