"""Deterministic post-processing: lane-line mask -> lane instances -> lane state.

Everything here is plain numpy/OpenCV, has no learned parameters, and runs on
the work-resolution masks (640x360). Output coordinates are scaled to the
source frame by the caller (`LaneDetector`).

Pipeline (plan section 10 / section 16 "deterministic logic"):

 1. Horizon guess y_h0 from the top of the drivable mask (clipped to a band).
 2. Scanlines, bottom-up, every `row_step` rows below the horizon: runs of
    lane pixels -> (x_center, width). Runs wider than a fraction of the lane
    width prior are dropped (crosswalk bars, blobs). Runs whose gap is below
    double_gap(y) are merged (double yellow / double white).
 3. Linking: greedy nearest-neighbour association of run centres to chains
    using a slope-extrapolated prediction and a run-width-aware tolerance
    (shallow side-lane lines give wide runs); chains survive gaps up to
    link_gap(y) (dashed lines, small occlusions). All tolerances scale with
    (y - y_h), i.e. with perspective, so they need no camera calibration.
 4. Chain clean-up: merge collinear fragments and parallel duplicates, drop
    short chains (vertical extent or image length), near-horizontal chains
    (|dx/dy| > max_abs_dxdy), too-thick chains (perpendicular thickness:
    stop-line / crosswalk bars), zebra-stripe clusters, and short shallow
    chains that do not point at the vanishing point.
 5. Fit x = f(y) per chain (quadratic when long enough, linear otherwise;
    tangent-line extrapolation outside the data range).
 6. Vanishing point = weighted median of intersections of long left- and
    right-leaning chains; refines y_h and gives x_vp.
 7. Lane state in u-space (row-independent lateral position in lane widths,
    camera at u = 0; see the section header below): ego boundaries = nearest
    candidate left / right of u = 0; road edges from the drivable mask are
    candidates when no painted line is near them. Walk outward counting gaps
    that look like a lane (0.5..1.7 x the measured ego width, centre line
    drivable), stop at a road edge, at a yellow line on the left (US
    right-hand traffic) or at non-drivable road; optionally count one
    unmarked lane per side (lines hidden by traffic).
 8. Confidence from boundary evidence, lane-width plausibility (vs the
    scale-free prior w = lane_w_ratio * (y - y_h)), support and VP presence.

Limits (see README): occluded lines in dense traffic, night/rain glare,
unmarked roads (the count then relies on the drivable mask), road edges
without paint, and the camera-at-centre assumption (glasses need head yaw).
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Any, Optional

import cv2
import numpy as np


@dataclass
class PostConfig:
    row_step: int = 2
    horizon_default_frac: float = 0.475     # used when the drivable mask is empty (calibrated VP row)
    horizon_above_drivable: float = 0.035   # VP sits this fraction of H above the drivable top (calibrated)
    horizon_clip: tuple = (0.25, 0.62)       # fraction of H
    start_below_horizon: float = 0.035       # first scanline, fraction of H below y_h
    run_join_gap: int = 2                    # px; fill tiny holes inside a run
    max_run_base: float = 8.0                # px; widest accepted run = base + frac * lane width prior
    max_run_lane_frac: float = 0.6
    thick_base: float = 8.0                  # max perpendicular line thickness (px) = base + slope * (y - y_h)
    thick_slope: float = 0.08
    double_gap_base: float = 2.0
    double_gap_slope: float = 0.10
    link_tol_base: float = 3.0
    link_tol_slope: float = 0.07
    link_gap_base: float = 12.0
    link_gap_slope: float = 0.40
    min_points: int = 6
    min_extent_px: float = 10.0
    min_extent_frac: float = 0.08            # of road height
    min_length_mult: float = 2.5             # ...or image length >= this x the minimum extent
    max_abs_dxdy: float = 12.0               # |dx/dy| above this (< ~5 deg) = horizontal marking
    vp_tol_frac: float = 0.3                 # short shallow chains must point at the VP within this x W
    frag_tol_base: float = 4.0
    frag_tol_slope: float = 0.08
    dup_gap_base: float = 2.0
    dup_gap_slope: float = 0.12
    xwalk_min_cluster: int = 4
    xwalk_max_extent_frac: float = 0.22
    # lane-state
    lane_w_ratio: float = 2.83               # ego lane width px / (y - y_h); scale-free (calibrated, BDD100K)
    x_ego_offset_frac: float = -0.043        # default VP x - image centre, fraction of W, used when no VP (calibrated)
    u_ego: float = 0.0                       # camera position in lane widths (u-space; see lane-state section)
    u_min_dy_frac: float = 0.06              # ignore chain points closer than this to the horizon for u
    extrap_down_frac: float = 0.45           # allowed extrapolation towards the camera (road height)
    extrap_up_frac: float = 0.06
    w_min: float = 0.5                       # accepted neighbour gap, x measured ego width (u)
    w_max: float = 1.7
    ego_w_min: float = 0.5                   # accepted ego width in u (1.0 = prior lane width)
    ego_w_max: float = 1.9
    count_unmarked: bool = True
    unmarked_min: float = 0.8                # lane widths of unmarked drivable road to count a lane
    unmarked_max: int = 1                    # at most this many unmarked lanes per side
    unmarked_min_seen: float = 0.25          # share of the unmarked lane's centre line the model sees as drivable
    edge_snap: float = 0.3                   # lane widths: a line this close to the drivable edge replaces it
    edge_beyond: float = 0.5                 # lines farther than this beyond the road edge are ignored
    edge_pct: float = 0.15                   # edge = 15th (left) / 85th (right) percentile over rows
    edge_open_frac: float = 0.5              # side open (touching the image border) in >= this share of rows
    road_close_px: int = 13                  # horizontal closing of (drivable | lane) before edge / lane tests
    yellow_stops_left: bool = True
    yellow_frac: float = 0.35


# ----------------------------------------------------------------------------- chains
@dataclass
class Chain:
    ys: list = field(default_factory=list)
    xs: list = field(default_factory=list)
    ws: list = field(default_factory=list)
    last_y: float = 0.0
    active: bool = True
    # filled by finalize()
    ymin: float = 0.0
    ymax: float = 0.0
    lin: Any = None
    quad: Any = None
    lin_bot: Any = None
    lin_top: Any = None
    color: str = "unknown"
    style: str = "unknown"
    kind: str = "line"
    support: int = 0
    thick: float = 0.0
    u: Optional[float] = None
    u_spread: Optional[float] = None

    def add(self, y, x, w):
        self.ys.append(y)
        self.xs.append(x)
        self.ws.append(w)
        self.last_y = y

    def predict(self, y):
        n = len(self.ys)
        if n == 1:
            return self.xs[-1]
        # slope from the last point and a point ~12 px back (chains grow upwards: y decreasing)
        j = n - 1
        while j > 0 and self.ys[j] - self.ys[-1] < 12 and n - j < 8:
            j -= 1
        dy = self.ys[j] - self.ys[-1]
        if dy <= 0:
            return self.xs[-1]
        m = (self.xs[j] - self.xs[-1]) / dy       # dx/dy (x change per +1 row, i.e. going down)
        return self.xs[-1] - m * (self.last_y - y)   # chains grow upwards

    def finalize(self):
        ys = np.asarray(self.ys, float)
        xs = np.asarray(self.xs, float)
        order = np.argsort(-ys)
        ys, xs = ys[order], xs[order]
        self.ys, self.xs = ys.tolist(), xs.tolist()
        self.ymin, self.ymax = float(ys.min()), float(ys.max())
        self.support = len(ys)
        self.lin = np.polyfit(ys, xs, 1) if len(ys) >= 2 and np.ptp(ys) > 0 else np.array([0.0, xs.mean()])
        ext = self.ymax - self.ymin
        self.quad = None
        if len(ys) >= 10 and ext >= 40:
            q = np.polyfit(ys, xs, 2)
            # reject wild curvature: the quadratic must stay within 25 px of the line over the range
            yy = np.linspace(self.ymin, self.ymax, 9)
            if np.abs(np.polyval(q, yy) - np.polyval(self.lin, yy)).max() < 25:
                self.quad = q
        wsa = np.asarray(self.ws, float)[order] if self.ws else np.full(len(ys), 3.0)
        self.ws = wsa.tolist()
        self.thick = float(np.median(wsa) / math.sqrt(1.0 + float(self.lin[0]) ** 2))
        k = max(3, int(0.4 * len(ys)))
        self.lin_bot = _safe_lin(ys[:k], xs[:k], self.lin)
        self.lin_top = _safe_lin(ys[-k:], xs[-k:], self.lin)

    @property
    def extent(self):
        return self.ymax - self.ymin

    @property
    def length(self):
        return self.extent * math.sqrt(1.0 + float(self.lin[0]) ** 2)

    def x_at(self, y):
        y = np.asarray(y, float)
        inside = np.polyval(self.quad if self.quad is not None else self.lin, y)
        below = _lin_from_end(self, self.lin_bot, self.ymax, y)
        above = _lin_from_end(self, self.lin_top, self.ymin, y)
        return np.where(y > self.ymax, below, np.where(y < self.ymin, above, inside))

    def dxdy(self):
        return float(self.lin[0])


def _safe_lin(ys, xs, fallback):
    if len(ys) >= 2 and np.ptp(ys) >= 4:
        return np.polyfit(ys, xs, 1)
    return fallback


def _lin_from_end(c: Chain, lin, y_end, y):
    """Tangent continuation: anchored at the fitted end point, slope of `lin`."""
    x_end = np.polyval(c.quad if c.quad is not None else c.lin, y_end)
    return x_end + lin[0] * (y - y_end)


# ----------------------------------------------------------------------------- helpers
def _runs(row: np.ndarray, join_gap: int):
    """Runs of True in a 1-D bool array -> (start, end_exclusive) arrays, tiny holes joined."""
    d = np.diff(np.concatenate(([0], row.view(np.int8), [0])))
    s = np.flatnonzero(d == 1)
    e = np.flatnonzero(d == -1)
    if len(s) > 1 and join_gap > 0:
        keep = np.concatenate(([True], (s[1:] - e[:-1]) > join_gap))
        s = s[keep]
        e = np.concatenate((e[:-1][keep[1:]], e[-1:]))
    return s, e


def _runs_rows(mask: np.ndarray, rows: np.ndarray, join_gap: int):
    """_runs() for many rows at once (one vectorised diff), returned per row."""
    if len(rows) == 0:
        return []
    sub = mask[rows].view(np.int8) if mask.dtype == bool else (mask[rows] > 0).view(np.int8)
    pad = np.zeros((len(rows), 1), np.int8)
    d = np.diff(np.concatenate((pad, sub, pad), axis=1), axis=1)
    rs, cs = np.nonzero(d == 1)
    re_, ce = np.nonzero(d == -1)
    bounds = np.searchsorted(rs, np.arange(len(rows) + 1))
    out = []
    for i in range(len(rows)):
        s = cs[bounds[i]:bounds[i + 1]]
        e = ce[bounds[i]:bounds[i + 1]]
        if len(s) > 1 and join_gap > 0:
            keep = np.concatenate(([True], (s[1:] - e[:-1]) > join_gap))
            s = s[keep]
            e = np.concatenate((e[:-1][keep[1:]], e[-1:]))
        out.append((s, e))
    return out


def estimate_horizon(drv: np.ndarray, cfg: PostConfig) -> float:
    H = drv.shape[0]
    rows = drv.mean(axis=1)
    idx = np.flatnonzero(rows > 0.01)
    if len(idx) == 0:
        return cfg.horizon_default_frac * H
    y_top = float(idx[0]) - cfg.horizon_above_drivable * H
    return float(np.clip(y_top, cfg.horizon_clip[0] * H, cfg.horizon_clip[1] * H))


def extract_chains(lane: np.ndarray, y_h: float, cfg: PostConfig, y_bot: Optional[int] = None,
                   debug: Optional[dict] = None) -> list[Chain]:
    H, W = lane.shape
    y_bot = H - 1 if y_bot is None else int(y_bot)
    y_start = int(y_h + cfg.start_below_horizon * H)
    chains: list[Chain] = []
    active: list[Chain] = []
    rejected_wide = 0
    rows_y = np.arange(y_bot, y_start - 1, -cfg.row_step)
    runs_by_row = _runs_rows(lane, rows_y, cfg.run_join_gap)
    for y, (s, e) in zip(rows_y.tolist(), runs_by_row):
        dy = max(y - y_h, 1.0)
        pts = []
        if len(s):
            # a run can be wide because the line is shallow (side lanes) - the perpendicular
            # thickness test after linking separates those from stop lines / crosswalk bars
            ok = (e - s) <= cfg.max_run_base + cfg.max_run_lane_frac * cfg.lane_w_ratio * dy
            rejected_wide += int((~ok).sum())
            s, e = s[ok], e[ok]
            dg = cfg.double_gap_base + cfg.double_gap_slope * dy
            i = 0
            while i < len(s):
                j = i
                while j + 1 < len(s) and s[j + 1] - e[j] <= dg:
                    j += 1
                pts.append(((s[i] + e[j] - 1) / 2.0, float(e[j] - s[i])))
                i = j + 1
        tol = cfg.link_tol_base + cfg.link_tol_slope * dy
        pairs = []
        for ci, c in enumerate(active):
            gap = c.last_y - y
            xp = c.predict(y)
            wl = c.ws[-1]
            for pi, (x, w) in enumerate(pts):
                d = abs(x - xp)
                if d < tol + 0.08 * gap + 0.35 * (wl + w):
                    pairs.append((d, ci, pi))
        pairs.sort()
        used_c, used_p = set(), set()
        for d, ci, pi in pairs:
            if ci in used_c or pi in used_p:
                continue
            used_c.add(ci)
            used_p.add(pi)
            active[ci].add(y, pts[pi][0], pts[pi][1])
        gap_max = cfg.link_gap_base + cfg.link_gap_slope * dy
        active = [c for c in active if c.last_y - y <= gap_max]
        for pi, (x, w) in enumerate(pts):
            if pi not in used_p:
                c = Chain()
                c.add(y, x, w)
                chains.append(c)
                active.append(c)
    for c in chains:
        c.finalize()
    if debug is not None:
        debug["raw_chains"] = len(chains)
        debug["wide_runs_rejected"] = rejected_wide
    return chains


def _merge_into(a: Chain, b: Chain) -> Chain:
    c = Chain()
    for y, x, w in sorted(zip(a.ys + b.ys, a.xs + b.xs, (a.ws or [0] * len(a.ys)) + (b.ws or [0] * len(b.ys))),
                          key=lambda t: -t[0]):
        c.add(y, x, w)
    c.finalize()
    return c


def merge_fragments(chains: list[Chain], y_h: float, cfg: PostConfig) -> list[Chain]:
    """Join collinear pieces (lower piece extended upwards meets the upper piece)."""
    chains = sorted(chains, key=lambda c: -c.ymax)
    changed = True
    while changed:
        changed = False
        for i in range(len(chains)):
            for j in range(len(chains)):
                if i == j:
                    continue
                a, b = chains[i], chains[j]      # a lower, b upper
                if a.ymax <= b.ymax or a.ymin < b.ymax - 6:
                    continue
                if a.support < 3 or b.support < 2:
                    continue
                y_join = (a.ymin + b.ymax) / 2
                dy = max(y_join - y_h, 1.0)
                tol = cfg.frag_tol_base + cfg.frag_tol_slope * dy + 0.05 * (a.ymin - b.ymax)
                xa = float(a.x_at(y_join))
                xb = float(b.x_at(y_join))
                if abs(xa - xb) > tol:
                    continue
                if b.support >= 4 and a.support >= 4 and abs(a.lin_top[0] - b.lin_bot[0]) > 0.8:
                    continue
                chains[i] = _merge_into(a, b)
                del chains[j]
                changed = True
                break
            if changed:
                break
    return chains


def merge_duplicates(chains: list[Chain], y_h: float, cfg: PostConfig) -> list[Chain]:
    changed = True
    while changed:
        changed = False
        for i in range(len(chains)):
            for j in range(i + 1, len(chains)):
                a, b = chains[i], chains[j]
                lo, hi = max(a.ymin, b.ymin), min(a.ymax, b.ymax)
                if hi - lo < 0.5 * min(a.extent, b.extent) or hi - lo < 4:
                    continue
                yy = np.linspace(lo, hi, 8)
                d = np.abs(a.x_at(yy) - b.x_at(yy))
                lim = cfg.dup_gap_base + cfg.dup_gap_slope * np.maximum(yy - y_h, 1.0)
                if np.all(d < lim):
                    chains[i] = _merge_into(a, b)
                    del chains[j]
                    changed = True
                    break
            if changed:
                break
    return chains


def filter_chains(chains: list[Chain], y_h: float, y_bot: float, cfg: PostConfig,
                  debug: Optional[dict] = None) -> list[Chain]:
    road_h = max(y_bot - y_h, 1.0)
    min_ext = max(cfg.min_extent_px, cfg.min_extent_frac * road_h)
    keep, horiz, short, thick = [], 0, 0, 0
    for c in chains:
        # shallow side-lane lines span few rows but are long: accept on image length as well
        if c.support < cfg.min_points or c.extent < cfg.min_extent_px * 0.6 or                 (c.extent < min_ext and c.length < cfg.min_length_mult * min_ext):
            short += 1
            continue
        if abs(c.dxdy()) > cfg.max_abs_dxdy:
            horiz += 1
            continue
        ym = (c.ymin + c.ymax) / 2
        if c.thick > cfg.thick_base + cfg.thick_slope * max(ym - y_h, 1.0):
            thick += 1
            continue
        keep.append(c)
    # zebra-crosswalk clusters: many short, laterally dense stripes in the same band
    xwalk = set()
    shorts = [i for i, c in enumerate(keep) if c.extent < cfg.xwalk_max_extent_frac * road_h]
    for i in shorts:
        ci = keep[i]
        grp = [j for j in shorts if min(ci.ymax, keep[j].ymax) - max(ci.ymin, keep[j].ymin)
               > 0.5 * min(ci.extent, keep[j].extent)]
        if len(grp) >= cfg.xwalk_min_cluster:
            ym = np.mean([(keep[j].ymin + keep[j].ymax) / 2 for j in grp])
            xs = np.sort([float(keep[j].x_at(ym)) for j in grp])
            spacing = np.median(np.diff(xs)) if len(xs) > 1 else 1e9
            if spacing < 0.35 * cfg.lane_w_ratio * max(ym - y_h, 1.0):
                xwalk.update(grp)
    out = [c for i, c in enumerate(keep) if i not in xwalk]
    if debug is not None:
        debug.update({"short_chains": short, "horizontal_chains": horiz, "thick_chains": thick,
                      "crosswalk_stripes": len(xwalk)})
    return out


def vanishing_point(chains: list[Chain], y_h0: float, W: int, H: int, cfg: PostConfig):
    """Median intersection of long left-leaning and right-leaning chains (lower-part fits)."""
    long = [c for c in chains if c.extent >= 30 and c.support >= 10]
    left = [c for c in long if c.lin_bot[0] < -0.15]    # x decreases downwards: left boundary
    right = [c for c in long if c.lin_bot[0] > 0.15]
    pts, wts = [], []
    for a in left:
        for b in right:
            ma, ba = a.lin_bot
            mb, bb = b.lin_bot
            if abs(ma - mb) < 1e-3:
                continue
            y = (bb - ba) / (ma - mb)
            x = ma * y + ba
            if cfg.horizon_clip[0] * H - 20 <= y <= cfg.horizon_clip[1] * H + 20 and -0.25 * W <= x <= 1.25 * W:
                pts.append((x, y))
                wts.append(min(a.support, b.support))
    if not pts:
        return None
    p = np.asarray(pts)
    w = np.asarray(wts, float)
    order = np.argsort(p[:, 1])
    cw = np.cumsum(w[order]) / w.sum()
    ym = p[order][np.searchsorted(cw, 0.5), 1]
    order = np.argsort(p[:, 0])
    cw = np.cumsum(w[order]) / w.sum()
    xm = p[order][np.searchsorted(cw, 0.5), 0]
    return float(xm), float(ym)


def vp_filter(chains: list[Chain], vp, y_h: float, y_bot: float, W: int, cfg: PostConfig,
              debug: Optional[dict] = None) -> list[Chain]:
    """Short, shallow chains whose extension misses the vanishing point are transverse
    markings (stop-line / crosswalk fragments, arrows), not lane lines."""
    road_h = max(y_bot - y_h, 1.0)
    vx = vp[0] if vp is not None else W / 2 + cfg.x_ego_offset_frac * W
    out, n = [], 0
    for c in chains:
        if c.extent < 0.3 * road_h and abs(c.lin[0]) > 1.5:
            x_at_h = float(np.polyval(c.lin, y_h))
            if abs(x_at_h - vx) > cfg.vp_tol_frac * W:
                n += 1
                continue
        out.append(c)
    if debug is not None:
        debug["vp_rejected"] = n
    return out


def classify_appearance(chains: list[Chain], frame_small: Optional[np.ndarray], cfg: PostConfig) -> None:
    """Colour (yellow/white) and style (solid/dashed) from the image under each chain. Heuristic."""
    if frame_small is None:
        return
    H, W = frame_small.shape[:2]
    hsv = cv2.cvtColor(frame_small, cv2.COLOR_BGR2HSV)
    gray = cv2.cvtColor(frame_small, cv2.COLOR_BGR2GRAY).astype(np.int16)
    for c in chains:
        ys = np.asarray(c.ys, int).clip(0, H - 1)
        xs = np.asarray(c.xs, float).round().astype(int).clip(0, W - 1)
        hh, ss, vv = hsv[ys, xs, 0], hsv[ys, xs, 1], hsv[ys, xs, 2]
        yel = (hh >= 12) & (hh <= 36) & (ss >= 70) & (vv >= 70)
        bright = (vv >= 60)
        if bright.sum() >= 4:
            c.color = "yellow" if yel.sum() / max(bright.sum(), 1) >= cfg.yellow_frac else "white"
        ws = np.asarray(c.ws, float) if c.ws else np.full(len(ys), 3.0)
        off = (ws / 2 + 4).round().astype(int)
        gc = gray[ys, xs]
        gl = gray[ys, (xs - off).clip(0, W - 1)]
        gr = gray[ys, (xs + off).clip(0, W - 1)]
        painted = (gc - np.maximum(gl, gr)) > 12
        if len(painted) >= 8:
            f = painted.mean()
            trans = int(np.abs(np.diff(painted.astype(np.int8))).sum())
            if f >= 0.8:
                c.style = "solid"
            elif 0.15 <= f < 0.8 and trans >= 2:
                c.style = "dashed"


# ----------------------------------------------------------------------------- lane state (u-space)
# Flat-ground pinhole model: a straight road line at lateral offset X (metres) from
# the camera projects to  x = x_vp + (X / h_cam) * (y - y_h).  Dividing by the
# lane-width prior w(y) = lane_w_ratio * (y - y_h), with lane_w_ratio ~ W_lane / h_cam,
# gives  u = (x - x_vp) / w(y) ~ X / W_lane : a row-independent lateral coordinate in
# lane widths with the camera at u = 0 whatever the yaw. Side-lane lines that are only
# visible near the horizon and ego boundaries seen at the bottom become comparable.

def u_of(x, y, x_vp, y_h, ratio):
    return (np.asarray(x, float) - x_vp) / (ratio * np.maximum(np.asarray(y, float) - y_h, 1.0))


def x_of(u, y, x_vp, y_h, ratio):
    return x_vp + u * ratio * np.maximum(np.asarray(y, float) - y_h, 1.0)


def chain_u(c: Chain, x_vp, y_h, ratio, min_dy):
    ys = np.asarray(c.ys, float)
    xs = np.asarray(c.xs, float)          # sorted bottom -> top
    ok = (ys - y_h) >= min_dy
    if ok.sum() < 3:
        return None, None
    u = u_of(xs[ok], ys[ok], x_vp, y_h, ratio)
    k = max(3, int(0.5 * len(u)))        # nearest half of the chain (least affected by curvature)
    return float(np.median(u[:k])), float(np.std(u))


def _drivable_span(drv: np.ndarray, y: float, x_ego: float, near: float):
    H, W = drv.shape
    yi = int(np.clip(round(y), 1, H - 2))
    row = drv[yi - 1] | drv[yi] | drv[yi + 1]
    s, e = _runs(row, 3)
    if len(s) == 0:
        return None
    inside = np.flatnonzero((s <= x_ego) & (e > x_ego))
    if len(inside):
        k = inside[0]
    else:
        dist = np.minimum(np.abs(s - x_ego), np.abs(e - 1 - x_ego))
        k = int(np.argmin(dist))
        if dist[k] > near:
            return None
    return float(s[k]), float(e[k] - 1)


def road_edges_u(drv, x_vp, y_h, y_bot, ratio, u_ego, cfg: "PostConfig"):
    """Lateral position (u) of the drivable-area edges of the ego road; None if open/unknown.

    Per row, the drivable run around the ego line gives a left/right edge, or 'open' when
    the run touches the image border. Vehicles occluding the road make many rows look
    narrower than the road, so the edge is a low/high percentile of the observed values
    (the widest rows), and a side that is open in most rows has no edge."""
    H, W = drv.shape
    road_h = max(y_bot - y_h, 1.0)
    ys = np.arange(int(y_h + 0.12 * road_h), int(y_bot) + 1, 4)
    uls, urs, open_l, open_r = [], [], 0, 0
    ext_l, ext_r = [], []          # drivable extent incl. open rows (image border = lower bound)
    for y in ys:
        xe = float(x_of(u_ego, y, x_vp, y_h, ratio))
        # rows where the ego line itself is not drivable (lead vehicle) say nothing about the edges
        span = _drivable_span(drv, y, float(np.clip(xe, 0, W - 1)), 0.08 * ratio * (y - y_h))
        if span is None:
            continue
        dl, dr = span
        ul_y, ur_y = float(u_of(dl, y, x_vp, y_h, ratio)), float(u_of(dr, y, x_vp, y_h, ratio))
        ext_l.append(ul_y)
        ext_r.append(ur_y)
        if dl > 2:
            uls.append(ul_y)
        else:
            open_l += 1
        if dr < W - 3:
            urs.append(ur_y)
        else:
            open_r += 1
    ul = ur = None
    ul_x = float(np.percentile(ext_l, 100 * cfg.edge_pct)) if len(ext_l) >= 3 else None
    ur_x = float(np.percentile(ext_r, 100 * (1 - cfg.edge_pct))) if len(ext_r) >= 3 else None
    if ul_x is not None and open_l < cfg.edge_open_frac * (open_l + len(uls)):
        ul = ul_x
    if ur_x is not None and open_r < cfg.edge_open_frac * (open_r + len(urs)):
        ur = ur_x
    # (ul, ur): road edges (None = open / unknown); (ul_x, ur_x): farthest observed drivable
    # extent, used only to count unmarked lanes when a side is open in the near rows
    return ul, ur, ul_x, ur_x


def lane_drivable_frac(drv, u, x_vp, y_h, y_bot, ratio):
    """Fraction of drivable pixels along the line u = const (lower 75 % of the road), None if unseen."""
    H, W = drv.shape
    road_h = max(y_bot - y_h, 1.0)
    ys = np.arange(int(y_h + 0.25 * road_h), int(y_bot) + 1, 6)
    xs = np.round(x_of(u, ys, x_vp, y_h, ratio)).astype(int)
    ok = (xs >= 0) & (xs < W)
    if ok.sum() < 3:
        return None
    return float(drv[ys[ok], xs[ok]].mean())


def estimate_lane_state(chains, drv, x_vp, y_h, W, H, y_bot, cfg: PostConfig, vp_found: bool,
                        drv_vis: Optional[np.ndarray] = None, tracker=None):
    """drv: road mask used for edges / lane tests (drivable | lane | occluders);
    drv_vis: the model's own drivable mask (road actually seen), required for unmarked lanes."""
    ratio = cfg.lane_w_ratio
    u_ego = cfg.u_ego
    road_h = max(y_bot - y_h, 1.0)
    min_dy = cfg.u_min_dy_frac * road_h
    cands = []
    for idx, c in enumerate(chains):
        u, spread = chain_u(c, x_vp, y_h, ratio, min_dy)
        c.u, c.u_spread = u, spread
        if u is None:
            continue
        cands.append({"u": u, "kind": "line", "chain": idx, "color": c.color, "support": c.support,
                      "spread": spread})
    if tracker is not None:
        # video: lines seen in the last few frames but missing now (occlusion, dash gap, glare)
        cands += tracker.update(cands)
    ul = ur = ul_x = ur_x = None
    if drv is not None:
        ul, ur, ul_x, ur_x = road_edges_u(drv, x_vp, y_h, y_bot, ratio, u_ego, cfg)
        lo = (ul - cfg.edge_beyond) if ul is not None else -1e9
        hi = (ur + cfg.edge_beyond) if ur is not None else 1e9
        cands = [c for c in cands if lo <= c["u"] <= hi]       # lines beyond the road edge: other carriageway
        for ue in (ul, ur):
            if ue is not None and not any(abs(c["u"] - ue) < cfg.edge_snap for c in cands):
                cands.append({"u": ue, "kind": "edge", "chain": None, "color": "unknown", "support": 0,
                              "spread": 0.0})
    cands.sort(key=lambda c: c["u"])
    left = [c for c in cands if c["u"] < u_ego][::-1]
    right = [c for c in cands if c["u"] >= u_ego]
    st = {"ok": False, "x_vp": x_vp, "y_h": y_h, "ratio": ratio, "u_ego": u_ego, "edges_u": (ul, ur),
          "cands": cands, "nL": None, "nR": None, "conf": 0.0}
    if left and right and (right[0]["u"] - left[0]["u"]) < cfg.ego_w_min:
        # implausibly narrow ego lane: drop the weaker of the two nearest boundaries
        if (left[0]["support"], left[0]["kind"] == "line") < (right[0]["support"], right[0]["kind"] == "line"):
            left = left[1:]
        else:
            right = right[1:]
    if not left and not right:
        return st
    imputed = None
    L0 = left[0] if left else None
    R0 = right[0] if right else None
    if L0 is None:
        L0 = {"u": R0["u"] - 1.0, "kind": "virtual", "chain": None, "color": "unknown", "support": 0, "spread": 0.0}
        imputed = "left"
    if R0 is None:
        R0 = {"u": L0["u"] + 1.0, "kind": "virtual", "chain": None, "color": "unknown", "support": 0, "spread": 0.0}
        imputed = "right"
    w_u = R0["u"] - L0["u"]
    w_ref = w_u if (imputed is None and cfg.ego_w_min <= w_u <= cfg.ego_w_max) else 1.0

    def walk(seq, start, edge_u, is_left):
        n, unmarked, prev, used = 0, 0, start["u"], []
        if start["kind"] == "edge" or (is_left and cfg.yellow_stops_left and start["color"] == "yellow"):
            return 0, 0, used
        stopped = False
        for c in seq:
            g = abs(c["u"] - prev)
            if g < cfg.w_min * w_ref:
                continue                                   # duplicate / double line
            frac = lane_drivable_frac(drv, (prev + c["u"]) / 2, x_vp, y_h, y_bot, ratio) if drv is not None else None
            if frac is not None and frac < 0.5:
                stopped = True                             # e.g. opposite carriageway, median, sidewalk
                break
            if g > cfg.w_max * w_ref:
                k = int(round(g / w_ref))
                if (frac is None or frac >= 0.8) and k <= 3:
                    n += k
                    unmarked += k - 1
                else:
                    stopped = True
                    break
            else:
                n += 1
            used.append(c)
            prev = c["u"]
            if c["kind"] == "edge" or (is_left and cfg.yellow_stops_left and c["color"] == "yellow"):
                stopped = True
                break
        if not stopped and cfg.count_unmarked and edge_u is not None:
            ext = abs(edge_u - prev)
            if ext >= cfg.unmarked_min * w_ref:
                k = min(cfg.unmarked_max, int(math.floor(ext / w_ref + 0.2)))
                u_mid = prev + (0.5 * w_ref if not is_left else -0.5 * w_ref)
                seen = lane_drivable_frac(drv_vis, u_mid, x_vp, y_h, y_bot, ratio) if drv_vis is not None else 1.0
                if seen is not None and seen >= cfg.unmarked_min_seen:
                    n += k
                    unmarked += k
        return n, unmarked, used

    nL, uL, usedL = walk(left[1:] if left and left[0] is L0 else left, L0, ul if ul is not None else ul_x, True)
    nR, uR, usedR = walk(right[1:] if right and right[0] is R0 else right, R0, ur if ur is not None else ur_x, False)
    c_b = 1.0 if imputed is None else 0.5
    if L0["kind"] == "edge" or R0["kind"] == "edge":
        c_b *= 0.85
    c_w = math.exp(-(math.log(max(w_u, 1e-3)) / 0.35) ** 2) if imputed is None else 0.6
    c_s = min(1.0, 0.3 + (L0["support"] + R0["support"]) / 40.0)
    spread = max(L0.get("spread") or 0.0, R0.get("spread") or 0.0)
    c_sp = math.exp(-spread / 0.4)
    conf = (c_b * (0.4 + 0.6 * c_w) * (0.6 + 0.4 * c_s) * (0.6 + 0.4 * c_sp)
            * (0.85 if uL + uR else 1.0) * (1.0 if vp_found else 0.75))
    st.update(ok=True, nL=nL, nR=nR, conf=float(np.clip(conf, 0, 1)), L0=L0, R0=R0, w_u=w_u, w_ref=w_ref,
              imputed=imputed, unmarked=uL + uR, usedL=usedL, usedR=usedR,
              offset=(u_ego - (L0["u"] + R0["u"]) / 2) / w_ref)
    return st


def boundary_x(b: dict, y, chains: list, st: dict, cfg: PostConfig, road_h: float):
    """Image x of an ego/neighbour boundary at row(s) y (work res)."""
    y = np.asarray(y, float)
    xu = x_of(b["u"], y, st["x_vp"], st["y_h"], st["ratio"])
    if b["kind"] != "line":
        return xu
    c = chains[b["chain"]]
    return np.where(y < c.ymin - cfg.extrap_up_frac * road_h, xu, c.x_at(y))


# ----------------------------------------------------------------------------- top level
def process_masks(lane: np.ndarray, drv: Optional[np.ndarray], cfg: PostConfig,
                  frame_small: Optional[np.ndarray] = None, y_bot: Optional[int] = None,
                  occluders: Optional[np.ndarray] = None, tracker=None) -> dict:
    """lane/drv: bool masks at work resolution; occluders: optional bool mask of vehicles etc.
    (counted as road for the road-edge / lane-drivable tests). Returns chains, vp, lane state."""
    H, W = lane.shape
    dbg: dict = {}
    y_bot = H - 1 if y_bot is None else int(y_bot)
    y_h0 = estimate_horizon(drv, cfg) if drv is not None else cfg.horizon_default_frac * H
    chains = extract_chains(lane, y_h0, cfg, y_bot, dbg)
    chains = merge_fragments(chains, y_h0, cfg)
    chains = merge_duplicates(chains, y_h0, cfg)
    chains = filter_chains(chains, y_h0, y_bot, cfg, dbg)
    vp = vanishing_point(chains, y_h0, W, H, cfg)
    y_h = y_h0
    x_vp = W / 2 + cfg.x_ego_offset_frac * W
    if vp is not None:
        y_h = float(np.clip(vp[1], cfg.horizon_clip[0] * H, cfg.horizon_clip[1] * H))
        x_vp = float(np.clip(vp[0], 0.15 * W, 0.85 * W))
    chains = vp_filter(chains, vp, y_h, y_bot, W, cfg, dbg)
    classify_appearance(chains, frame_small, cfg)
    road = None
    if drv is not None:
        # painted lines lie on the road: the drivable head often leaves a gap under them
        road = (drv | lane) if occluders is None else (drv | lane | occluders)
        road = road.astype(np.uint8)
        road = cv2.morphologyEx(road, cv2.MORPH_CLOSE, np.ones((1, cfg.road_close_px), np.uint8)) > 0
    st = estimate_lane_state(chains, road, x_vp, y_h, W, H, y_bot, cfg, vp is not None, drv_vis=drv,
                             tracker=tracker)
    # renumber chains left -> right by u (chains without u go by their bottom point)
    def key(i):
        c = chains[i]
        return c.u if c.u is not None else float(u_of(c.xs[0], c.ys[0], x_vp, y_h, cfg.lane_w_ratio))
    order = sorted(range(len(chains)), key=key)
    remap = {old: new for new, old in enumerate(order)}
    chains = [chains[i] for i in order]
    seen = set()
    for c in st.get("cands", []) + [st.get("L0") or {}, st.get("R0") or {}] + st.get("usedL", []) + st.get("usedR", []):
        if c.get("chain") is not None and id(c) not in seen:
            c["chain"] = remap[c["chain"]]
            seen.add(id(c))
    return {"chains": chains, "vp": vp, "y_h": y_h, "x_vp": x_vp, "y_h0": y_h0, "y_bot": y_bot, "state": st,
            "debug": dbg}


def ego_lane_polygon(res: dict, cfg: PostConfig, W: int, step: int = 4) -> Optional[np.ndarray]:
    """Polygon (work-res px) of the ego lane between its two boundaries, from the road bottom up to
    where the boundary evidence ends (or 45 % of the road height for virtual/edge boundaries)."""
    st = res["state"]
    if not st.get("ok"):
        return None
    chains, y_h, y_bot = res["chains"], res["y_h"], res["y_bot"]
    road_h = max(y_bot - y_h, 1.0)
    tops = [chains[b["chain"]].ymin for b in (st["L0"], st["R0"]) if b["kind"] == "line"]
    y_top = min(tops) if tops else y_h + 0.45 * road_h
    y_top = max(y_top, y_h + 0.05 * road_h)
    ys = np.arange(float(y_bot), y_top - 1e-6, -step)
    if len(ys) < 2:
        return None
    xl = boundary_x(st["L0"], ys, chains, st, cfg, road_h)
    xr = boundary_x(st["R0"], ys, chains, st, cfg, road_h)
    ok = (xr - xl) > 2
    if ok.sum() < 2:
        return None
    ys, xl, xr = ys[ok], xl[ok], xr[ok]
    poly = np.concatenate([np.stack([xl, ys], 1), np.stack([xr, ys], 1)[::-1]]).astype(np.float32)
    poly[:, 0] = poly[:, 0].clip(-W, 2 * W)
    return poly


class LineTracker:
    """Short-term memory of lane lines in u-space (video only).

    Each frame the detected line candidates are matched to tracks by |du| < match_du
    (greedy, nearest first); matched tracks follow with an EMA. A confirmed track
    (>= confirm hits) that is not matched is kept for up to max_miss frames and
    returned as a 'tracked' candidate, so a line hidden for a few frames by a car,
    a dash gap or glare does not change the lane count. Lane changes move all u
    values smoothly (about 1 lane width per 2-4 s), which the EMA follows."""

    def __init__(self, match_du=0.22, alpha=0.5, confirm=3, max_miss=12):
        self.match_du, self.alpha, self.confirm, self.max_miss = match_du, alpha, confirm, max_miss
        self.tracks: list = []

    def reset(self):
        self.tracks = []

    def update(self, cands: list) -> list:
        pairs = sorted((abs(c["u"] - t["u"]), ci, ti) for ci, c in enumerate(cands)
                       for ti, t in enumerate(self.tracks) if abs(c["u"] - t["u"]) < self.match_du)
        used_c, used_t = set(), set()
        for d, ci, ti in pairs:
            if ci in used_c or ti in used_t:
                continue
            used_c.add(ci)
            used_t.add(ti)
            t, c = self.tracks[ti], cands[ci]
            t["u"] = (1 - self.alpha) * t["u"] + self.alpha * c["u"]
            t["hits"] += 1
            t["miss"] = 0
            t["color"] = c["color"] if c["color"] != "unknown" else t["color"]
        for ti, t in enumerate(self.tracks):
            if ti not in used_t:
                t["miss"] += 1
        for ci, c in enumerate(cands):
            if ci not in used_c:
                self.tracks.append({"u": c["u"], "hits": 1, "miss": 0, "color": c["color"]})
        self.tracks = [t for t in self.tracks
                       if t["miss"] <= self.max_miss and (t["hits"] >= self.confirm or t["miss"] == 0)]
        return [{"u": t["u"], "kind": "tracked", "chain": None, "color": t["color"], "support": 8, "spread": 0.0}
                for t in self.tracks if t["miss"] > 0 and t["hits"] >= self.confirm]
