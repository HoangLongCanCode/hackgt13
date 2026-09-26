"""Closed-form monocular range geometry (plan sections 9, 16, 18). numpy only.

Notation: f = focal length (px), (cx, cy) principal point, H_c = camera height
above the road (m), y_h = horizon row (px), y_b = bbox bottom row (tyre/road
contact). Distances are forward (longitudinal) metres from the camera.

Formulas (see research track `depth`, extra_findings, and README):
  flat ground      Z_g = H_c / tan(theta + atan((y_b - cy) / f)),  theta = atan((cy - y_h) / f)
                   (~= f * H_c / (y_b - y_h) for small pitch; Stein, Mano & Shashua 2003)
  size prior       Z_w = f * W / w_px (width),  Z_h = f * H_obj / h_px (height)
  virtual horizon  y_h ~= y_b - H_c * h_px / H_obj  (Park & Hwang 2014, height form)
  road profile     on a flat road 1/Z(y) = (y - y_h) / (f * H_c): a line fit of
                   1/depth vs image row gives y_h (scale-free) and H_c * k, where k
                   is the depth model's scale error -> self-calibration of either.
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Iterable, Optional, Sequence

import numpy as np

# Class size priors: (width m, height m, rel. sigma width, rel. sigma height).
# None = do not use that dimension. Assumptions to tune (US fleet; plan: car 1.8 m).
SIZE_PRIORS: dict[str, tuple[Optional[float], Optional[float], float, float]] = {
    "car": (1.80, 1.50, 0.10, 0.12),
    "truck": (2.40, 3.00, 0.20, 0.30),   # BDD 'truck' mixes pickups and HGVs
    "bus": (2.55, 3.10, 0.06, 0.10),
    "train": (3.00, 4.00, 0.20, 0.20),
    "motorcycle": (0.80, 1.20, 0.25, 0.20),
    "bicycle": (0.60, 1.05, 0.30, 0.15),
    "pedestrian": (None, 1.70, 0.0, 0.10),
    "rider": (None, 1.70, 0.0, 0.15),      # KITTI 'Cyclist' boxes (person + bike) ~1.7 m tall
    "traffic light": (None, 1.00, 0.0, 0.30),  # 3-lamp US signal head ~0.9-1.1 m (long side)
    "traffic sign": (0.75, 0.75, 0.40, 0.40),
}
GROUND_CLASSES = {"car", "truck", "bus", "train", "motorcycle", "bicycle", "pedestrian", "rider"}


@dataclass
class Camera:
    focal_px: float
    cx: float
    cy: float
    height_m: float
    width: int
    height: int

    @classmethod
    def bdd_default(cls, w: int = 1280, h: int = 720, focal_px: Optional[float] = None,
                    cam_height_m: float = 1.3) -> "Camera":
        # BDD maintainer: iPhone 5 720p, "usually use focal length 700px" (bdd100k discussion #139)
        f = focal_px if focal_px is not None else 700.0 * w / 1280.0
        return cls(f, w / 2.0, h / 2.0, cam_height_m, w, h)


def ground_distance(y_b: float, y_h: float, cam: Camera, cam_height_m: Optional[float] = None) -> Optional[float]:
    """Flat-ground forward distance from the bbox bottom row; None if at/above the horizon."""
    hc = cam.height_m if cam_height_m is None else cam_height_m
    theta = math.atan((cam.cy - y_h) / cam.focal_px)
    ang = theta + math.atan((y_b - cam.cy) / cam.focal_px)
    if ang <= math.radians(0.05):
        return None
    return hc / math.tan(ang)


def ground_sigma(z: float, cam: Camera, sigma_y_px: float, sigma_yh_px: float, rel_sigma_h: float) -> float:
    """1-sigma of Z_g: dZ ~= Z^2/(f H) * dy (pixel terms) combined with the camera-height term."""
    pix = z * z / (cam.focal_px * cam.height_m) * math.hypot(sigma_y_px, sigma_yh_px)
    return math.hypot(pix, z * rel_sigma_h)


def size_distances(cls: str, box: Sequence[float], cam: Camera, border_px: float = 2.0,
                   bottom_limit: Optional[float] = None):
    """Returns dict with optional 'width', 'height' estimates as (Z, sigma).

    Width is only used for (near-)rear/front views: box aspect w/h must be
    <= 1.35 x the class's rear-view aspect (a side view inflates w_px and would
    under-estimate Z). Dimensions touching the image border are skipped.
    """
    out = {}
    pri = SIZE_PRIORS.get(cls)
    if pri is None:
        return out
    W, Hh, sw, sh = pri
    x1, y1, x2, y2 = box
    w_px, h_px = max(1.0, x2 - x1), max(1.0, y2 - y1)
    cut_lr = x1 <= border_px or x2 >= cam.width - 1 - border_px
    cut_tb = y1 <= border_px or y2 >= cam.height - 1 - border_px
    if bottom_limit is not None and y2 >= bottom_limit - border_px:  # bottom hidden by the ego hood
        cut_tb = True
    if cls == "traffic light":  # long side is the housing length for vertical or horizontal heads
        L = max(w_px, h_px)
        if not (cut_lr or cut_tb):
            z = cam.focal_px * Hh / L
            out["height"] = (z, z * sh)
        return out
    if Hh is not None and not cut_tb:
        z = cam.focal_px * Hh / h_px
        out["height"] = (z, z * math.hypot(sh, 1.0 / h_px))
    if W is not None and not cut_lr:
        rear_aspect = W / Hh if Hh else 1.0
        if (w_px / h_px) <= 1.35 * rear_aspect or cut_tb:
            z = cam.focal_px * W / w_px
            out["width"] = (z, z * math.hypot(sw, 1.0 / w_px))
    return out


def virtual_horizon(dets: Iterable[tuple[str, Sequence[float]]], cam: Camera,
                    cam_height_m: Optional[float] = None) -> Optional[tuple[float, float, int]]:
    """Horizon row from object height priors (independent of any depth model).

    y_h_i = y_b - H_c * h_px / H_obj for untruncated cars/buses/pedestrians;
    returns (y_h, sigma_px, n) as an inverse-variance weighted median-ish mean.
    """
    hc = cam.height_m if cam_height_m is None else cam_height_m
    vals, sig = [], []
    for cls, (x1, y1, x2, y2) in dets:
        if cls not in ("car", "bus", "pedestrian"):
            continue
        if y1 <= 2 or y2 >= cam.height - 3 or x1 <= 2 or x2 >= cam.width - 3:
            continue
        h_px = y2 - y1
        if h_px < 12:
            continue
        _, Hh, _, sh = SIZE_PRIORS[cls]
        off = hc * h_px / Hh
        vals.append(y2 - off)
        sig.append(math.hypot(off * sh, 1.5))
    if not vals:
        return None
    v, s = np.asarray(vals), np.asarray(sig)
    med = np.median(v)
    keep = np.abs(v - med) <= 3 * np.maximum(s, 4.0)
    v, s = v[keep], s[keep]
    w = 1.0 / s ** 2
    yh = float(np.sum(w * v) / np.sum(w))
    sy = float(1.0 / math.sqrt(np.sum(w)))
    return yh, max(sy, 2.0), int(len(v))


@dataclass
class RoadProfile:
    horizon_y: float          # px, scale-free
    height_depth_m: float     # camera height implied by the depth map (= H_true * depth scale error)
    inlier_frac: float        # share of sampled rows in the road run
    n_rows: int
    rmse_inv: float
    first_row: float = 0.0    # road rows used: [first_row, last_row]
    last_row: float = 0.0
    hood_row: Optional[float] = None  # rows below this look like the ego hood (nearer than the road line)


def _road_band_mask(depth, boxes, road_mask, sky, x_band):
    h, w = depth.shape
    if road_mask is not None:
        m = road_mask.astype(bool).copy()
    else:
        m = np.zeros((h, w), bool)
        m[:, int(w * x_band[0]):int(w * x_band[1])] = True
    for (bx1, by1, bx2, by2) in boxes:
        pad = 0.1 * (bx2 - bx1)
        m[max(0, int(by1)):min(h, int(by2 + 4)), max(0, int(bx1 - pad)):min(w, int(bx2 + pad))] = False
    if sky is not None:
        m &= ~sky
    return m & (depth > 0.3) & (depth < 80)


def detect_hood_row(depth: np.ndarray, boxes: Sequence[Sequence[float]] = (), road_mask=None, sky=None,
                    x_band=(0.30, 0.70), y_start: float = 0.4, min_px_row: int = 12) -> Optional[float]:
    """Top row of the ego hood / dashboard, if one is visible (BDD phones often see it).

    On the road, the row-median inverse depth grows smoothly with the row; the
    hood edge is a jump to a surface closer than ~5 m. Returns the first such
    row (minus a 6 px margin) or None.
    """
    h, _ = depth.shape
    valid = _road_band_mask(depth, boxes, road_mask, sky, x_band)
    ys, inv = [], []
    for y in range(int(h * y_start), h, 2):
        r = valid[y]
        if r.sum() >= min_px_row:
            ys.append(y)
            inv.append(float(np.median(1.0 / depth[y][r])))
    if len(ys) < 10:
        return None
    inv = np.asarray(inv)
    for k in range(3, len(ys) - 3):
        before = np.median(inv[max(0, k - 6):k - 1])
        after = np.median(inv[k + 1:k + 4])
        if after > 0.2 and (after - before) > max(0.05, 0.3 * before):
            return float(ys[k] - 6)
    return None


def fit_road_profile(depth: np.ndarray, cam: Camera, boxes: Sequence[Sequence[float]] = (),
                     road_mask: Optional[np.ndarray] = None, sky: Optional[np.ndarray] = None,
                     x_band=(0.30, 0.70), y_range=(0.4, 0.97), min_px_row: int = 12,
                     hood_row: Optional[float] = None, rel_tol: float = 0.08) -> Optional[RoadProfile]:
    """Fit 1/depth = a*y + b over road rows.

    Rows: row-median inverse depth in the lower-central band (or `road_mask`),
    detection boxes removed. RANSAC with a relative tolerance picks the road
    line, then only the largest *contiguous* run of inlier rows is kept (the ego
    hood / dashboard sits below a depth jump, far rows near the horizon are
    noisy), and the line is refit on that run. If the rows below the run are
    mostly nearer than the road line, they are reported as a hood
    (`hood_row`). Rows at/below an explicit `hood_row` argument are ignored.
    Returns None if the fit is implausible.
    """
    h, w = depth.shape
    valid = _road_band_mask(depth, boxes, road_mask, sky, x_band)
    y0, y1 = int(h * y_range[0]), int(h * y_range[1])
    if hood_row is not None:
        y1 = min(y1, int(hood_row))
    rows, inv = [], []
    for y in range(y0, y1, 2):
        r = valid[y]
        if r.sum() >= min_px_row:
            rows.append(y)
            inv.append(float(np.median(1.0 / depth[y][r])))
    if len(rows) < 12:
        return None
    ys, iv = np.asarray(rows, float), np.asarray(inv, float)

    def inliers(a, b):
        return np.abs(a * ys + b - iv) < rel_tol * iv + 0.004

    rng = np.random.default_rng(0)
    best, best_score = None, -1
    for _ in range(120):
        i, j = rng.choice(len(ys), 2, replace=False)
        if abs(ys[i] - ys[j]) < 8:
            continue
        a = (iv[j] - iv[i]) / (ys[j] - ys[i])
        if a <= 0:
            continue
        b = iv[i] - a * ys[i]
        run = _longest_run(inliers(a, b))
        if run[1] - run[0] > best_score:
            best, best_score = (a, b), run[1] - run[0]
    if best is None or best_score < 10:
        return None
    a, b = best
    for _ in range(3):  # refit on the contiguous run, re-derive the run
        lo, hi = _longest_run(inliers(a, b))
        sel = slice(lo, hi)
        if hi - lo < 10:
            return None
        A = np.stack([ys[sel], np.ones(hi - lo)], 1)
        sol, *_ = np.linalg.lstsq(A, iv[sel], rcond=None)
        if sol[0] <= 0:
            return None
        a, b = float(sol[0]), float(sol[1])
    lo, hi = _longest_run(inliers(a, b))
    r = iv[lo:hi] - (a * ys[lo:hi] + b)
    yh = -b / a
    hd = 1.0 / (cam.focal_px * a)
    below = slice(hi, len(ys))
    hood = None
    if len(ys) - hi >= 5:
        nearer = iv[below] > (a * ys[below] + b) * (1 + rel_tol) + 0.004
        if nearer.mean() >= 0.6 and iv[below].max() > 0.15:
            hood = float(ys[hi - 1] + 2)
    if not (0.1 * h <= yh <= 0.8 * h) or not (0.3 <= hd <= 5.0):
        return None
    return RoadProfile(float(yh), float(hd), float((hi - lo) / len(ys)), int(hi - lo),
                       float(np.sqrt(np.mean(r ** 2))), float(ys[lo]), float(ys[hi - 1]), hood)


def _longest_run(mask: np.ndarray, max_gap: int = 3) -> tuple[int, int]:
    """[lo, hi) of the longest run of True allowing gaps of <= max_gap False samples."""
    best, cur_lo, last_true, gap = (0, 0), None, None, 0
    for k, m in enumerate(mask):
        if m:
            if cur_lo is None:
                cur_lo = k
            last_true, gap = k, 0
        elif cur_lo is not None:
            gap += 1
            if gap > max_gap:
                if last_true + 1 - cur_lo > best[1] - best[0]:
                    best = (cur_lo, last_true + 1)
                cur_lo, gap = None, 0
    if cur_lo is not None and last_true + 1 - cur_lo > best[1] - best[0]:
        best = (cur_lo, last_true + 1)
    return best


def box_readout(depth: np.ndarray, box: Sequence[float], cls: str, percentile: float = 30.0,
                sky: Optional[np.ndarray] = None,
                exclude_boxes: Sequence[Sequence[float]] = ()) -> Optional[tuple[float, float, int]]:
    """Robust depth statistic inside the lower-central part of a box.

    Vehicles: x 25-75 %, y 45-90 % of the box (bumper/plate/rear window, avoids
    the road under the car and background above). Persons/riders: torso,
    x 30-70 %, y 20-60 %. Returns (value, relative IQR spread, n_pixels).
    """
    h, w = depth.shape
    x1, y1, x2, y2 = box
    bw, bh = x2 - x1, y2 - y1
    if cls in ("pedestrian", "rider"):
        fx, fy = (0.30, 0.70), (0.20, 0.60)
    elif cls in ("traffic light", "traffic sign"):
        fx, fy = (0.25, 0.75), (0.25, 0.75)
    else:
        fx, fy = (0.25, 0.75), (0.45, 0.90)
    xa, xb = int(round(x1 + fx[0] * bw)), int(round(x1 + fx[1] * bw)) + 1
    ya, yb = int(round(y1 + fy[0] * bh)), int(round(y1 + fy[1] * bh)) + 1
    xa, xb = max(0, xa), min(w, max(xb, xa + 1))
    ya, yb = max(0, ya), min(h, max(yb, ya + 1))
    patch = depth[ya:yb, xa:xb]
    keep = np.ones(patch.shape, bool)
    if sky is not None:
        keep &= ~sky[ya:yb, xa:xb]
    if exclude_boxes:  # pixels covered by nearer (occluding) boxes
        ex = np.zeros(patch.shape, bool)
        for (ex1, ey1, ex2, ey2) in exclude_boxes:
            c1, c2 = max(0, int(ex1) - xa), min(xb - xa, int(np.ceil(ex2)) - xa)
            r1, r2 = max(0, int(ey1) - ya), min(yb - ya, int(np.ceil(ey2)) - ya)
            if c2 > c1 and r2 > r1:
                ex[r1:r2, c1:c2] = True
        if (keep & ~ex).sum() >= 0.3 * keep.sum():
            keep &= ~ex
    patch = patch[keep]
    v = patch[(patch > 0.3) & (patch < 250)].ravel()
    if v.size < 4:
        return None
    q25, qp, q75 = np.percentile(v, [25, percentile, 75])
    return float(qp), float((q75 - q25) / max(qp, 1e-3)), int(v.size)

