"""Deterministic road geometry + AR anchors from a 19-class trainId map (plan §13, §19).

Nothing here is learned. Given a semantic map (Cityscapes/BDD100K trainIds), and
optionally the frame itself, it derives the following, in order:

1. road mask       = trainId 0 (road). drivableCoverage = road pixels / frame pixels.
2. ego component   = the 8-connected road component with the most pixels in a
                     bottom-centre seed window (robust to the ego hood), after a
                     small morphological close/open. egoPathPolygon = its outer
                     contour, simplified with cv2.approxPolyDP and clipped below the horizon.
3. horizon / VP    = the first method that passes its checks, in this order:
                     "lines":       RANSAC vanishing point of Hough segments (lane markings,
                                    road edges) inside the ego road region. Needs the frame.
                     "two_edges":   intersection of RANSAC line fits to the left and right
                                    edges of the ego road run.
                     "single_edge": one edge line intersected with the prior VP column.
                     "road_top":    min(prior_y, top row of the ego component). The road
                                    cannot extend above the horizon on flat ground.
                     "prior":       camera prior (median BDD100K horizon).
                     For video, the optional HorizonTracker smooths the result (EMA + jump gate).
4. ego corridor    = a walk in ground coordinates from Z = 4.5 m to 80 m in 0.5 m steps.
                     A corridor of +/- corridor_half_m around the centre X_c is projected
                     to the image. If the road run at that row is wide enough, X_c moves
                     only as much as needed to keep the corridor on the road; if it is
                     narrower, X_c goes to the run's centre. The lateral rate is bounded.
                     The walk stops when no road is found for a few steps (vehicle
                     ahead, junction end, crest).
5. anchors         = flat-ground back-projection. For Z in (10, 20, 40) m the anchor is
                     the corridor centre (X_c(Z), Z) projected with the pitch implied by
                     the horizon: t = atan((cy - horizonY)/f), and (review fix) the yaw
                     implied by the VP column, so X = 0 converges on vanishingPoint rather
                     than on (cx, horizonY). The renderer pins arrows and labels to these.

All image coordinates are in source-frame pixels (1280x720 for BDD100K).
"""
from __future__ import annotations

import math
from collections import deque
from dataclasses import dataclass, replace
from typing import Any, Optional

import cv2
import numpy as np

from perception.common.schemas import RoadGeometry

CITYSCAPES_CLASSES = [
    "road", "sidewalk", "building", "wall", "fence", "pole", "traffic light",
    "traffic sign", "vegetation", "terrain", "sky", "person", "rider", "car",
    "truck", "bus", "train", "motorcycle", "bicycle",
]
ROAD = 0
OCCLUDER_IDS = {11, 12, 13, 14, 15, 16, 17, 18}  # person, rider, vehicles: AR draws behind these
STRONG_METHODS = ("lines", "two_edges", "single_edge")


@dataclass
class CameraModel:
    """Pinhole camera over a flat ground plane (roll = 0, pitch from the horizon row).

    BDD100K publishes no per-video intrinsics. Any defaults (see semantic.bdd_camera)
    are assumptions. Lateral metric quantities (corridor width in px) do not depend on
    f, but forward distances Z scale linearly with f and with h.
    """
    width: int = 1280
    height: int = 720
    focal_px: float = 700.0
    height_m: float = 1.3
    cx: Optional[float] = None
    cy: Optional[float] = None
    horizon_prior_y: Optional[float] = None  # fallback horizon row; default = cy
    vp_prior_x: Optional[float] = None       # fallback vanishing-point column; default = cx
    yaw_rad: float = 0.0                     # heading yaw: forward (X=0) projects to the VP column, not cx

    def __post_init__(self):
        if self.cx is None:
            self.cx = self.width / 2.0
        if self.cy is None:
            self.cy = self.height / 2.0
        if self.horizon_prior_y is None:
            self.horizon_prior_y = self.cy
        if self.vp_prior_x is None:
            self.vp_prior_x = self.cx

    def pitch(self, horizon_y: float) -> float:
        """Pitch-down angle (rad) implied by the horizon row."""
        return math.atan2(self.cy - horizon_y, self.focal_px)

    def with_heading(self, vp_x: float, horizon_y: float) -> "CameraModel":
        """Copy whose forward axis (X = 0, Z -> inf) projects to the vanishing point (vp_x, horizon_y).

        Review fix: the ground model used to ignore yaw, so straight-ahead lines converged at
        (cx, horizonY) instead of the reported vanishingPoint (BDD median VP column is 593, i.e.
        47 px left of cx: ~0.7 m lateral error at 10 m, ~2.7 m at 40 m).
        """
        t = self.pitch(horizon_y)
        return replace(self, yaw_rad=math.atan((vp_x - self.cx) * math.cos(t) / self.focal_px))

    def ground_to_image(self, X: float, Z: float, horizon_y: float) -> Optional[tuple[float, float]]:
        t = self.pitch(horizon_y)
        h = self.height_m
        cy_, sy_ = math.cos(self.yaw_rad), math.sin(self.yaw_rad)
        X, Z = X * cy_ + Z * sy_, -X * sy_ + Z * cy_   # yaw (identity when yaw_rad = 0)
        zc = h * math.sin(t) + Z * math.cos(t)
        if zc <= 1e-6:
            return None
        yc = h * math.cos(t) - Z * math.sin(t)
        return (self.cx + self.focal_px * X / zc, self.cy + self.focal_px * yc / zc)

    def image_to_ground(self, u: float, v: float, horizon_y: float) -> Optional[tuple[float, float]]:
        """(lateral X to the right, forward Z) in metres for a pixel below the horizon, else None."""
        t = self.pitch(horizon_y)
        a = (u - self.cx) / self.focal_px
        b = (v - self.cy) / self.focal_px
        denom = b * math.cos(t) + math.sin(t)
        if denom <= 1e-6:
            return None
        s = self.height_m / denom
        xc, zc = a * s, (math.cos(t) - b * math.sin(t)) * s
        cy_, sy_ = math.cos(self.yaw_rad), math.sin(self.yaw_rad)
        return (xc * cy_ - zc * sy_, xc * sy_ + zc * cy_)   # undo yaw


@dataclass
class GeometryConfig:
    work_scale: float = 0.5              # geometry runs on a 640x360 mask for 720p input
    morph_kernel: int = 5                # close/open kernel at work scale
    seed_window: tuple[float, float, float, float] = (0.30, 0.70, 0.55, 1.0)  # x0, x1, y0, y1 (fractions)
    min_component_px: int = 400          # at work scale
    corridor_half_m: float = 1.4         # half width of the ego corridor (car half width + margin)
    anchor_distances_m: tuple[float, ...] = (10.0, 20.0, 40.0)
    walk_z_start_m: float = 4.5          # skips the hood / near field
    walk_z_max_m: float = 80.0
    walk_dz_m: float = 0.5
    walk_max_gap_steps: int = 3
    walk_max_lateral_rate: float = 0.6   # |dX_c/dZ| bound
    walk_start_max_m: float = 15.0       # the corridor must find visible road before this distance
    walk_occluded_max_m: float = 40.0    # walk through vehicles for at most this far past the last visible road
    visible_half_m: float = 0.5          # half width of the central strip that must show road
    edge_z_range_m: tuple[float, float] = (4.5, 30.0)   # rows used for road-edge line fits
    ransac_iters: int = 200
    ransac_thresh_px: float = 2.0        # at work scale
    min_edge_inliers: int = 12
    min_edge_span_px: float = 15.0
    use_image_lines: bool = True
    canny: tuple[int, int] = (30, 90)
    hough: tuple[int, int, int] = (18, 15, 4)   # threshold, minLineLength, maxLineGap (work px)
    line_angle_deg: tuple[float, float] = (12.0, 80.0)
    line_ransac_iters: int = 300
    line_inlier_deg: float = 2.0
    line_min_inliers: int = 3
    line_min_angle_sep_deg: float = 6.0
    vp_y_range: tuple[float, float] = (0.20, 0.75)  # plausible horizon rows (fraction of H)
    vp_above_road_top_tol_px: float = 15.0         # VP may sit at most this far below the road top
    polygon_eps_frac: float = 0.004      # approxPolyDP epsilon as a fraction of the contour perimeter
    yaw_from_vp: bool = True             # review fix: project the corridor/anchors toward the VP column


@dataclass
class HorizonEstimate:
    y: float
    x: float
    method: str          # see module docstring; "+tracked" when smoothed by HorizonTracker
    confidence: float


class HorizonTracker:
    """Temporal smoothing for video: EMA of accepted measurements, gated jumps.

    The camera is rigidly mounted, so the true horizon moves slowly (road slope,
    braking pitch). A measurement more than `gate_px` from the current estimate is
    rejected, unless `reset_after` consecutive rejections suggest a real change.
    """

    def __init__(self, alpha: float = 0.15, gate_px: float = 40.0, reset_after: int = 15):
        self.alpha, self.gate_px, self.reset_after = alpha, gate_px, reset_after
        self.y: Optional[float] = None
        self.x: Optional[float] = None
        self._rejects: deque = deque(maxlen=reset_after)

    def update(self, meas: HorizonEstimate) -> HorizonEstimate:
        strong = meas.method in STRONG_METHODS
        if self.y is None:
            if strong or meas.method == "road_top":
                self.y, self.x = meas.y, meas.x
            return meas
        if strong:
            if abs(meas.y - self.y) <= self.gate_px:
                a = self.alpha * (1.0 if meas.method != "single_edge" else 0.5)
                self.y += a * (meas.y - self.y)
                self.x += a * (meas.x - self.x)
                self._rejects.clear()
            else:
                self._rejects.append(meas)
                if len(self._rejects) == self.reset_after:
                    self.y = float(np.median([m.y for m in self._rejects]))
                    self.x = float(np.median([m.x for m in self._rejects]))
                    self._rejects.clear()
        conf = max(0.3, meas.confidence) if strong else 0.5
        return HorizonEstimate(self.y, self.x, meas.method + "+tracked", conf)

    def reset(self):
        self.y = self.x = None
        self._rejects.clear()


# ----------------------------------------------------------------------------- helpers
def _runs(row: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    d = np.diff(np.concatenate(([0], row.astype(np.int8), [0])))
    return np.flatnonzero(d == 1), np.flatnonzero(d == -1)  # [start, end)


def _run_containing(row: np.ndarray, x: int, search: int) -> Optional[tuple[int, int]]:
    starts, ends = _runs(row)
    if len(starts) == 0:
        return None
    i = int(np.searchsorted(starts, x, side="right")) - 1
    if i >= 0 and x < ends[i]:
        return int(starts[i]), int(ends[i])
    best, bd = None, search + 1
    for s, e in zip(starts, ends):
        dist = s - x if x < s else x - (e - 1)
        if dist < bd:
            best, bd = (int(s), int(e)), dist
    return best


def _ransac_line(v: np.ndarray, x: np.ndarray, iters: int, thresh: float, rng: np.random.Generator):
    """Fit x = a*v + b robustly. Returns (a, b, inlier_mask) or None."""
    n = len(v)
    if n < 2:
        return None
    best_inl, best_cnt = None, 0
    for _ in range(iters):
        i, j = rng.choice(n, 2, replace=False)
        if v[i] == v[j]:
            continue
        a = (x[j] - x[i]) / (v[j] - v[i])
        b = x[i] - a * v[i]
        inl = np.abs(a * v + b - x) < thresh
        c = int(inl.sum())
        if c > best_cnt:
            best_cnt, best_inl = c, inl
    if best_inl is None or best_cnt < 2:
        return None
    a, b = np.polyfit(v[best_inl], x[best_inl], 1)
    inl = np.abs(a * v + b - x) < thresh
    return float(a), float(b), inl


def _vp_from_segments(segs: np.ndarray, cfg: GeometryConfig, rng: np.random.Generator):
    """RANSAC vanishing point from line segments (N x 4, x1 y1 x2 y2).

    Returns (x, y, n_inliers, well_conditioned) or None.
    """
    if segs is None or len(segs) < 2:
        return None
    p1, p2 = segs[:, :2].astype(float), segs[:, 2:].astype(float)
    d = p2 - p1
    L = np.hypot(d[:, 0], d[:, 1])
    ang = np.degrees(np.arctan2(np.abs(d[:, 1]), np.abs(d[:, 0])))
    keep = (ang >= cfg.line_angle_deg[0]) & (ang <= cfg.line_angle_deg[1]) & (L > 0)
    p1, p2, d, L = p1[keep], p2[keep], d[keep], L[keep]
    n = len(L)
    if n < 2:
        return None
    mid = 0.5 * (p1 + p2)
    u = d / L[:, None]
    lines = np.cross(np.c_[p1, np.ones(n)], np.c_[p2, np.ones(n)])
    lines /= np.hypot(lines[:, 0], lines[:, 1])[:, None]
    sgn = np.sign(d[:, 0] * d[:, 1])  # image-slope sign: left-side vs right-side markings
    cos_thr = math.cos(math.radians(cfg.line_inlier_deg))

    def inliers(vp):
        r = vp[None, :] - mid
        rn = np.hypot(r[:, 0], r[:, 1]) + 1e-9
        c = np.abs((r[:, 0] * u[:, 0] + r[:, 1] * u[:, 1]) / rn)
        return (c >= cos_thr) & (r[:, 1] < 0)  # VP must lie above the segment

    pairs = [(i, j) for i in range(n) for j in range(i + 1, n)] if n <= 30 else \
        [tuple(rng.choice(n, 2, replace=False)) for _ in range(cfg.line_ransac_iters)]
    theta = np.degrees(np.arctan2(d[:, 1], d[:, 0])) % 180.0
    min_sep = cfg.line_min_angle_sep_deg
    best, best_score = None, 0.0
    for i, j in pairs:
        sep = abs((theta[i] - theta[j] + 90.0) % 180.0 - 90.0)
        if sep < min_sep:
            continue  # near-parallel pair (e.g. two pieces of one marking): ill-conditioned
        p = np.cross(lines[i], lines[j])
        if abs(p[2]) < 1e-9:
            continue
        vp = p[:2] / p[2]
        inl = inliers(vp)
        score = float(L[inl].sum())
        if score > best_score:
            best, best_score = inl, score
    if best is None:
        return None
    # least-squares point closest to all inlier lines (length-weighted)
    A = lines[best, :2] * np.sqrt(L[best])[:, None]
    b = -lines[best, 2] * np.sqrt(L[best])
    try:
        vp = np.linalg.lstsq(A, b, rcond=None)[0]
    except np.linalg.LinAlgError:
        return None
    inl = inliers(vp)
    th = theta[inl]
    spread = float(abs((th.max() - th.min() + 90.0) % 180.0 - 90.0)) if inl.sum() > 1 else 0.0
    both = bool((sgn[inl] > 0).any() and (sgn[inl] < 0).any())
    # well conditioned = markings on both sides, or same-side lines that clearly converge
    return float(vp[0]), float(vp[1]), int(inl.sum()), bool(both or spread >= 2 * min_sep)


def estimate_horizon(edges_l: np.ndarray, edges_r: np.ndarray, segs: Optional[np.ndarray],
                     road_top_y: Optional[float], cam: CameraModel, cfg: GeometryConfig,
                     s: float, W: int, H: int) -> tuple[HorizonEstimate, dict[str, Any]]:
    """edges_*: (v, x) at work scale; segs: Hough segments at work scale. Returns (estimate, info)."""
    rng = np.random.default_rng(0)  # deterministic
    ylo, yhi = cfg.vp_y_range[0] * H, cfg.vp_y_range[1] * H

    def plausible(x, y):
        return (ylo <= y <= yhi and -0.25 * W <= x <= 1.25 * W and
                (road_top_y is None or y <= road_top_y + cfg.vp_above_road_top_tol_px))

    cands: dict[str, HorizonEstimate] = {}
    info: dict[str, Any] = {}
    # 1. lines
    r = _vp_from_segments(segs, cfg, rng) if segs is not None else None
    if r is not None:
        x, y = r[0] / s, r[1] / s
        info["lineVP"] = [round(x, 1), round(y, 1), r[2], r[3]]
        if r[2] >= cfg.line_min_inliers and r[3] and plausible(x, y):
            cands["lines"] = HorizonEstimate(y, x, "lines", float(min(1.0, r[2] / 10.0)))
    # 2./3. road-edge fits
    fits: dict[str, Any] = {}
    for name, pts, sign in (("left", edges_l, -1.0), ("right", edges_r, 1.0)):
        if len(pts) < cfg.min_edge_inliers:
            continue
        rr = _ransac_line(pts[:, 0].astype(float), pts[:, 1].astype(float),
                          cfg.ransac_iters, cfg.ransac_thresh_px, rng)
        if rr is None:
            continue
        a, b, inl = rr
        span = float(np.ptp(pts[inl, 0])) if inl.any() else 0.0
        if inl.sum() >= cfg.min_edge_inliers and span >= cfg.min_edge_span_px and sign * a > 0.15:
            fits[name] = (a, b, int(inl.sum()), span)
    info["edgeFits"] = {k: [round(v[0], 4), round(v[1] / s, 2), v[2]] for k, v in fits.items()}
    if "left" in fits and "right" in fits:
        (al, bl, nl, _), (ar, br, nr, _) = fits["left"], fits["right"]
        vs = (br - bl) / (al - ar)
        x, y = (al * vs + bl) / s, vs / s
        if plausible(x, y):
            cands["two_edges"] = HorizonEstimate(y, x, "two_edges", float(min(1.0, (nl + nr) / 80.0)))
    for name in ("left", "right"):
        if name in fits and "single_edge" not in cands:
            a, b, n, _ = fits[name]
            y = ((cam.vp_prior_x * s - b) / a) / s
            if plausible(cam.vp_prior_x, y):
                cands["single_edge"] = HorizonEstimate(y, cam.vp_prior_x, "single_edge", float(min(0.6, n / 80.0)))
    if road_top_y is not None:
        cands["road_top"] = HorizonEstimate(min(cam.horizon_prior_y, road_top_y), cam.vp_prior_x, "road_top", 0.3)
    cands["prior"] = HorizonEstimate(cam.horizon_prior_y, cam.vp_prior_x, "prior", 0.1)
    info["candidates"] = {k: round(v.y, 1) for k, v in cands.items()}
    for m in ("lines", "two_edges", "single_edge", "road_top", "prior"):
        if m in cands:
            return cands[m], info
    raise AssertionError("unreachable")


# ----------------------------------------------------------------------------- main entry
def compute_road_geometry(seg: np.ndarray, cam: Optional[CameraModel] = None,
                          cfg: Optional[GeometryConfig] = None,
                          tracker: Optional[HorizonTracker] = None,
                          frame_bgr: Optional[np.ndarray] = None,
                          ) -> tuple[RoadGeometry, dict[str, Any]]:
    """seg: HxW uint8 trainIds; frame_bgr (optional) enables the "lines" VP method.

    Returns (RoadGeometry, info). info carries the horizon method/candidates, the ego
    corridor polygon and the walked centreline (for overlays and debugging).
    """
    cfg = cfg or GeometryConfig()
    H, W = seg.shape[:2]
    cam = cam or CameraModel(width=W, height=H)
    s = cfg.work_scale
    road_full = seg == ROAD
    coverage = float(road_full.mean())
    ws, hs = int(round(W * s)), int(round(H * s))
    road = cv2.resize(road_full.astype(np.uint8), (ws, hs), interpolation=cv2.INTER_NEAREST)
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (cfg.morph_kernel, cfg.morph_kernel))
    road = cv2.morphologyEx(road, cv2.MORPH_CLOSE, k)
    road = cv2.morphologyEx(road, cv2.MORPH_OPEN, k)
    info: dict[str, Any] = {"workScale": s}

    n, lab, stats, _ = cv2.connectedComponentsWithStats(road, connectivity=8)
    x0, x1, y0, y1 = (int(cfg.seed_window[0] * ws), int(cfg.seed_window[1] * ws),
                      int(cfg.seed_window[2] * hs), int(cfg.seed_window[3] * hs))
    comp_id = 0
    if n > 1:
        counts = np.bincount(lab[y0:y1, x0:x1].ravel(), minlength=n)
        counts[0] = 0
        comp_id = int(counts.argmax()) if counts.max() > 0 else 0
        if comp_id and stats[comp_id, cv2.CC_STAT_AREA] < cfg.min_component_px:
            comp_id = 0
    if comp_id == 0:
        hz = HorizonEstimate(cam.horizon_prior_y, cam.vp_prior_x, "prior", 0.1)
        info["horizonRaw"] = dict(hz.__dict__)
        if tracker is not None and tracker.y is not None:
            hz = tracker.update(hz)
        info.update(horizon=dict(hz.__dict__), egoComponent=False, egoCorridorPolygon=[], roadTopY=None)
        cam_h = cam.with_heading(hz.x, hz.y) if cfg.yaw_from_vp else cam
        anchors = [_anchor(Z, cam_h, hz, None, seg) for Z in cfg.anchor_distances_m]
        return RoadGeometry(drivableCoverage=round(coverage, 4), egoPathPolygon=[],
                            horizonY=round(hz.y, 1), vanishingPoint=[round(hz.x, 1), round(hz.y, 1)],
                            anchorPoints=anchors), info
    comp = lab == comp_id
    # occluders (vehicles, people) are transparent for the corridor walk: a car beside or
    # ahead of us hides road but is not a road edge
    occl = np.isin(seg, list(OCCLUDER_IDS)).astype(np.uint8)
    occl = cv2.resize(occl, (ws, hs), interpolation=cv2.INTER_NEAREST).astype(bool)
    walkmask = comp | occl
    comp_top = int(stats[comp_id, cv2.CC_STAT_TOP])
    road_top_y = comp_top / s
    info["egoComponent"] = True
    info["roadTopY"] = road_top_y

    # ---- pass 1: walk with the prior horizon, collect ego-run edges for the edge fits
    cam_p = cam.with_heading(cam.vp_prior_x, cam.horizon_prior_y) if cfg.yaw_from_vp else cam
    walk1 = _walk(walkmask, comp, cam_p, cfg, cam.horizon_prior_y, s)
    edges_l = edges_r = np.zeros((0, 2))
    if walk1["n"]:
        z = walk1["Z"]
        sel = (z >= cfg.edge_z_range_m[0]) & (z <= cfg.edge_z_range_m[1])
        vv, L, R = walk1["vw"][sel], walk1["Lw"][sel], walk1["Rw"][sel]
        # keep true road edges only: not truncated by the image border, and the boundary
        # pixel is road (not a vehicle silhouette)
        okl = (L > 1) & comp[vv, L]
        okr = (R < ws - 2) & comp[vv, R]
        if okl.any():
            edges_l = np.stack([vv[okl], L[okl]], 1)
        if okr.any():
            edges_r = np.stack([vv[okr], R[okr]], 1)
    segs = None
    if cfg.use_image_lines and frame_bgr is not None:
        segs = _road_segments(frame_bgr, comp, cam, cfg, s)
        info["nSegments"] = 0 if segs is None else int(len(segs))
    hz, hinfo = estimate_horizon(edges_l, edges_r, segs, road_top_y, cam, cfg, s, W, H)
    info["horizonRaw"] = dict(hz.__dict__)
    info.update(hinfo)
    if tracker is not None:
        hz = tracker.update(hz)
    info["horizon"] = dict(hz.__dict__)
    # heading from the final VP column (review fix; see CameraModel.with_heading)
    cam = cam.with_heading(hz.x, hz.y) if cfg.yaw_from_vp else cam
    info["yawDeg"] = round(math.degrees(cam.yaw_rad), 2)

    # ---- pass 2: walk with the final horizon
    walk = _walk(walkmask, comp, cam, cfg, hz.y, s)

    # ---- ego polygon: outer contour of the component, clipped below the horizon
    comp_u8 = comp.astype(np.uint8)
    comp_u8[: int(max(0, math.floor(hz.y * s)))] = 0
    cnts, _ = cv2.findContours(comp_u8, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    poly: list[list[float]] = []
    if cnts:
        c = max(cnts, key=cv2.contourArea)
        eps = cfg.polygon_eps_frac * cv2.arcLength(c, True)
        ap = cv2.approxPolyDP(c, max(1.0, eps), True).reshape(-1, 2)
        poly = [[round(float(x) / s, 1), round(float(y) / s, 1)] for x, y in ap]

    # ---- corridor polygon + centreline (full-res image coordinates)
    corridor_poly, centerline = [], []
    # the drawable corridor ends at the last visible road (the renderer hides the occluded rest)
    n_vis = int(np.searchsorted(walk["Z"], walk["lastRoadZ"], side="right")) if walk["lastRoadZ"] is not None else 0
    if n_vis >= 2:
        idx = np.unique(np.linspace(0, n_vis - 1, min(n_vis, 30)).round().astype(int))
        left, right = [], []
        for i in idx:
            Z, Xc = walk["Z"][i], walk["Xc"][i]
            for X, dst in ((Xc - cfg.corridor_half_m, left), (Xc + cfg.corridor_half_m, right), (Xc, centerline)):
                p = cam.ground_to_image(X, Z, hz.y)
                if p:
                    dst.append([round(p[0], 1), round(p[1], 1)])
        corridor_poly = left + right[::-1]
    info["egoCorridorPolygon"] = corridor_poly
    info["walk"] = {"n": int(walk["n"]), "zMax": round(float(walk["Z"][-1]), 1) if walk["n"] else None,
                    "lastRoadZ": walk["lastRoadZ"], "stop": walk["stop"]}

    anchors = [_anchor(Z, cam, hz, walk, seg) for Z in cfg.anchor_distances_m]
    if len(centerline) >= 2:
        anchors.append({"name": "ego_path_centerline", "polyline": centerline,
                        "confidence": round(hz.confidence, 2)})
    geo = RoadGeometry(drivableCoverage=round(coverage, 4), egoPathPolygon=poly,
                       horizonY=round(hz.y, 1), vanishingPoint=[round(hz.x, 1), round(hz.y, 1)],
                       anchorPoints=anchors)
    return geo, info


def _walk(walkmask: np.ndarray, road: np.ndarray, cam: CameraModel, cfg: GeometryConfig, hy: float,
          s: float) -> dict[str, Any]:
    """Corridor walk in ground coordinates (see module docstring, step 4).

    walkmask = ego road component OR occluders (vehicles/people): a vehicle hides road
    but is not a road edge. `road` = ego road component only. The walk starts at the
    first step whose corridor centre sits on visible road (within walk_start_max_m),
    continues through occluded stretches for at most walk_occluded_max_m beyond the last
    visible road, and records lastRoadZ. Anchors beyond lastRoadZ are flagged occluded.
    """
    hs, ws = walkmask.shape
    Zs, Xcs, Ls, Rs, vws, XL, XR, vis = [], [], [], [], [], [], [], []
    Xc, gap, started, stop = 0.0, 0, False, "z_max"
    z_prev, last_road_z = None, None
    for Z in np.arange(cfg.walk_z_start_m, cfg.walk_z_max_m + 1e-6, cfg.walk_dz_m):
        Z = float(Z)
        if not started and Z > cfg.walk_start_max_m:
            stop = "no_start"
            break
        if started and last_road_z is not None and Z - last_road_z > cfg.walk_occluded_max_m:
            stop = "occluded"
            break
        p = cam.ground_to_image(Xc, Z, hy)
        if p is None:
            stop = "horizon"
            break
        u, v = p
        vw = int(round(v * s))
        if vw >= hs:
            continue  # still below the image bottom
        if v <= hy + 2 or vw < 0:
            stop = "horizon"
            break
        half_px = cfg.corridor_half_m * cam.focal_px / max(Z, 1e-3) * s  # approx. (small pitch)
        uw = int(round(u * s))
        run = _run_containing(walkmask[vw], uw, int(max(2, half_px)))
        if run is None:
            gap += 1
            if started and gap > cfg.walk_max_gap_steps:
                stop = "no_road"
                break
            continue
        # visibility = the central strip (+/- visible_half_m) of the corridor shows road; a lead
        # vehicle covering the centre makes the rest of the walk "occluded" until road reappears
        vis_px = max(1.0, half_px * cfg.visible_half_m / cfg.corridor_half_m)
        a, b = max(0, int(uw - vis_px)), min(ws, int(uw + vis_px) + 1)
        road_frac = float(road[vw, a:b].mean()) if b > a else 0.0
        visible = road_frac >= 0.5
        if not started and not visible:
            continue
        gap = 0
        started = True
        if visible:
            last_road_z = Z
        gl = cam.image_to_ground(run[0] / s, v, hy)
        gr = cam.image_to_ground((run[1] - 1) / s, v, hy)
        if gl is None or gr is None:
            break
        X_L, X_R = gl[0], gr[0]
        if X_R - X_L >= 2 * cfg.corridor_half_m:
            target = min(max(Xc, X_L + cfg.corridor_half_m), X_R - cfg.corridor_half_m)
        else:
            target = 0.5 * (X_L + X_R)
        if z_prev is not None:
            lim = cfg.walk_max_lateral_rate * (Z - z_prev)
            target = Xc + max(-lim, min(lim, target - Xc))
        Xc = target
        z_prev = Z
        Zs.append(Z); Xcs.append(Xc); Ls.append(run[0]); Rs.append(run[1] - 1)
        vws.append(vw); XL.append(X_L); XR.append(X_R); vis.append(visible)
    # trim the trailing occluded stretch to lastRoadZ + walk_occluded_max_m (already bounded)
    return {"n": len(Zs), "Z": np.array(Zs), "Xc": np.array(Xcs), "Lw": np.array(Ls, int), "Rw": np.array(Rs, int),
            "vw": np.array(vws, int), "XL": np.array(XL), "XR": np.array(XR), "visible": np.array(vis, bool),
            "lastRoadZ": last_road_z, "stop": stop}


def cfg_dz(walk: dict[str, Any]) -> float:
    z = walk["Z"]
    return float(z[1] - z[0]) if len(z) > 1 else 0.5


def _road_segments(frame_bgr: np.ndarray, comp: np.ndarray, cam: CameraModel, cfg: GeometryConfig,
                   s: float) -> Optional[np.ndarray]:
    """Hough segments (work scale) inside the dilated ego road region, above the near field."""
    hs, ws = comp.shape
    gray = cv2.cvtColor(cv2.resize(frame_bgr, (ws, hs), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2GRAY)
    gray = cv2.GaussianBlur(gray, (3, 3), 0)
    edges = cv2.Canny(gray, cfg.canny[0], cfg.canny[1])
    mask = cv2.dilate(comp.astype(np.uint8), np.ones((5, 5), np.uint8))
    v_max = cam.horizon_prior_y + cam.focal_px * cam.height_m / cfg.edge_z_range_m[0]
    mask[int(min(hs, v_max * s)):] = 0  # skip hood / near field
    edges &= mask * 255
    segs = cv2.HoughLinesP(edges, 1, np.pi / 180, cfg.hough[0], minLineLength=cfg.hough[1], maxLineGap=cfg.hough[2])
    return None if segs is None else segs.reshape(-1, 4)


def _anchor(Z: float, cam: CameraModel, hz: HorizonEstimate, walk: Optional[dict[str, Any]],
            seg: np.ndarray) -> dict[str, Any]:
    H, W = seg.shape[:2]
    out: dict[str, Any] = {"name": f"ego_path_{int(Z)}m", "distanceM": Z, "xy": None, "groundXZ": None,
                           "valid": False, "onRoad": False, "occludedBy": None,
                           "roadEdgesXY": None, "confidence": 0.0}
    if walk is not None and walk["n"] and walk["Z"][0] <= Z <= walk["Z"][-1]:
        Xc = float(np.interp(Z, walk["Z"], walk["Xc"]))
        i = int(np.argmin(np.abs(walk["Z"] - Z)))
        edges = [cam.ground_to_image(float(walk["XL"][i]), Z, hz.y), cam.ground_to_image(float(walk["XR"][i]), Z, hz.y)]
        if all(edges):
            out["roadEdgesXY"] = [[round(e[0], 1), round(e[1], 1)] for e in edges]
        if walk["lastRoadZ"] is not None and Z <= walk["lastRoadZ"] + 2 * cfg_dz(walk):
            out["valid"] = True
            conf = hz.confidence
        else:
            out["reason"] = "occluded"   # the path continues behind a vehicle; renderer should hide it
            conf = 0.5 * hz.confidence
    else:
        # corridor ended before this distance (vehicle ahead, junction, crest) or no road:
        # keep the last corridor offset so the renderer can draw it behind the occluder or drop it
        Xc = float(walk["Xc"][-1]) if walk is not None and walk["n"] else 0.0
        out["reason"] = "beyond_visible_road" if walk is not None and walk["n"] else "no_ego_road"
        conf = 0.3 * hz.confidence
    p = cam.ground_to_image(Xc, Z, hz.y)
    if p is None or not (0 <= p[1] < H):
        out["valid"] = False
        out["reason"] = "outside_image"
        return out
    ui, vi = int(np.clip(round(p[0]), 0, W - 1)), int(np.clip(round(p[1]), 0, H - 1))
    cls = int(seg[vi, ui])
    out["onRoad"] = bool(out["valid"] and cls == ROAD)
    if cls in OCCLUDER_IDS:
        out["occludedBy"] = CITYSCAPES_CLASSES[cls]
    out["xy"] = [round(p[0], 1), round(p[1], 1)]
    out["groundXZ"] = [round(Xc, 2), round(Z, 2)]
    out["confidence"] = round(float(conf), 2)
    return out
