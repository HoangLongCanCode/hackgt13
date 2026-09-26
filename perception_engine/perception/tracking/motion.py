"""Per-track motion cues for plan section 8: scale rate, time-to-collision (TTC),
an "approaching" flag with hysteresis, and lateral image velocity.

Everything here is deterministic arithmetic on tracked boxes (plan section 16). No
learned model or LLM is involved. The outputs are *measurements* for the Driving
Context Engine. They are not safety guarantees (plan sections 18 and 38), and the
thresholds below are placeholders to tune, not validated safety values.

Formulas
--------
Pinhole camera, object of fixed physical height H at range Z: image height h = f*H/Z.

* Scale rate: s = d ln(h)/dt = -(dZ/dt)/Z   [1/s]. s > 0 means the object is getting
  closer (relative closing speed > 0). Estimated robustly as the Theil-Sen slope
  (median of all pairwise slopes; Theil 1950, Sen 1968) of ln(h) against the frame
  pts over a sliding window (default 1.0 s). A Sen-style confidence interval [lo, hi]
  on the slope comes from Kendall's tau variance (the same construction as
  scipy.stats.theilslopes).
* TTC (first order, constant closing speed): TTC = Z/(-dZ/dt) = 1/s, valid only for s > 0.
  This is the "tau" of Lee (1976, Perception 5:437) as used for monocular forward
  collision warning by Mobileye (Dagan, Mano, Stein, Shashua, IEEE IV 2004; Stein,
  Mano, Shashua, IEEE IV 2003, DOI 10.1109/IVS.2003.1212895).
  Lag compensation: with constant closing speed, ln h(t) = c - ln(T - t), so a window
  fit returns s ~ 1/(T - t_c) at the window's centre time t_c. The TTC at the newest
  sample time t_now is therefore 1/s - (t_now - t_c). This is on by default
  (``lag_compensate``).
* Closing flag (hysteresis, ``closing`` in meta): turns ON when s >= on_rate AND the
  lower confidence bound lo > 0 for at least on_hold_s. It turns OFF when
  s <= off_rate for at least off_hold_s, or when no estimate has been possible for
  stale_s.
* Approaching flag (``TrackState.approaching``) = closing AND in the ego corridor. The
  corridor test puts any of three points on the box bottom edge (x at 1/4, 1/2, 3/4 of
  the width, y = y2) inside the ego-path polygon, with a corridor_off_hold_s exit
  delay. The polygon is ``Tracker.update(..., ego_polygon=RoadGeometry.egoPathPolygon)``
  from plan section 13 when available. Otherwise it is a static, normalised trapezoid
  (``corridor_norm``, a heuristic for a centred dashcam). ``approach_scope="any"``
  disables the corridor gate. Without the gate, cars that the ego vehicle passes on the
  shoulder, and oncoming traffic, also count as "approaching", because they do grow in
  the image.
* Lateral velocity: Theil-Sen slope of the box centre x [px/s] (``lateral_px_s``). Also
  the scale-free form d/dt[(cx - W_img/2)/w] (``lateralWidthsPerS`` in meta). Since
  (cx - c_x)/w = X/W_obj, this is the lateral speed in object widths per second,
  independent of range; times about 1.8 m for a car it is roughly m/s. Both are raw
  image-space values and include ego yaw.

Samples are rejected (not used in any fit) when the box touches the image border
(truncated), is smaller than min_box_h_px, or is covered by a nearer box (larger y2)
by more than occlusion_ioa of its own area. A gap longer than gap_reset_s (for example
after re-identification) clears the history.
"""
from __future__ import annotations

import math
from collections import deque
from dataclasses import dataclass, field
from typing import Iterable, Optional

import numpy as np

from perception.common.schemas import VEHICLE_CLASSES

_Z90 = 1.6448536269514722  # norm.ppf(0.95), two-sided 90 %


@dataclass
class MotionConfig:
    window_s: float = 1.0          # sliding window for the robust fits
    min_samples: int = 4           # valid samples needed before any estimate
    min_span_s: float = 0.35       # and they must span at least this long
    scale_mode: str = "h"          # "h" (schema: d ln h/dt), "w", or "sqrt_wh"
    lag_compensate: bool = True    # TTC at newest sample instead of window centre
    ttc_max_s: float = 30.0        # report ttc_s only when 0 < TTC <= this
    ci_z: float = _Z90             # z for the Sen confidence interval
    on_rate: float = 0.10          # 1/s  -> TTC <= 10 s  (placeholder, tune)
    off_rate: float = 0.04         # 1/s  -> TTC >= 25 s  (placeholder, tune)
    on_hold_s: float = 0.30
    off_hold_s: float = 0.50
    stale_s: float = 1.0           # no estimate for this long -> approaching off
    min_box_h_px: float = 12.0
    border_px: float = 2.0
    occlusion_ioa: float = 0.30
    gap_reset_s: float = 0.6
    forget_s: float = 5.0          # drop per-track state after this long unseen
    approach_classes: frozenset = field(
        default_factory=lambda: frozenset(VEHICLE_CLASSES | {"pedestrian", "rider"}))
    approach_scope: str = "corridor"  # "corridor" (closing AND in ego path) or "any" (closing only)
    # static fallback ego corridor, normalised (x/W, y/H): bottom 20..80 % of width, apex at 40 % height
    corridor_norm: tuple = ((0.20, 1.0), (0.80, 1.0), (0.54, 0.40), (0.46, 0.40))
    corridor_off_hold_s: float = 0.3


def theil_sen(t: np.ndarray, y: np.ndarray, z: float = _Z90) -> tuple[float, float, float]:
    """Theil-Sen slope with a Sen (1968) confidence interval. Returns (slope, lo, hi)."""
    n = len(t)
    i, j = np.triu_indices(n, 1)
    dt = t[j] - t[i]
    ok = dt > 1e-6
    slopes = np.sort((y[j] - y[i])[ok] / dt[ok])
    m = len(slopes)
    if m == 0:
        return float("nan"), float("nan"), float("nan")
    med = float(np.median(slopes))
    sigma = math.sqrt(n * (n - 1) * (2 * n + 5) / 18.0)
    k_lo = int(max(0, math.floor((m - z * sigma) / 2.0)))
    k_hi = int(min(m - 1, math.ceil((m + z * sigma) / 2.0) - 1))
    return med, float(slopes[k_lo]), float(slopes[k_hi])


@dataclass
class MotionOutput:
    scale_rate: Optional[float] = None
    scale_rate_lo: Optional[float] = None
    scale_rate_hi: Optional[float] = None
    ttc_s: Optional[float] = None
    approaching: Optional[bool] = None
    lateral_px_s: Optional[float] = None
    lateral_widths_s: Optional[float] = None
    closing: Optional[bool] = None
    in_corridor: Optional[bool] = None
    n_samples: int = 0
    sample_valid: bool = False
    reject_reason: Optional[str] = None


class _State:
    __slots__ = ("t", "y", "cx", "lw", "last_seen", "approaching", "on_since", "off_since",
                 "last_est_t", "ever_est", "in_corr", "corr_off_since")

    def __init__(self):
        self.t: deque = deque()
        self.y: deque = deque()
        self.cx: deque = deque()
        self.lw: deque = deque()
        self.last_seen = -1e9
        self.approaching = False
        self.on_since: Optional[float] = None
        self.off_since: Optional[float] = None
        self.last_est_t = -1e9
        self.ever_est = False
        self.in_corr = False
        self.corr_off_since: Optional[float] = None


class MotionEstimator:
    """Keeps a short box history per track id and derives motion cues each frame."""

    def __init__(self, config: Optional[MotionConfig] = None, **overrides):
        cfg = config or MotionConfig()
        for k, v in overrides.items():
            if not hasattr(cfg, k):
                raise TypeError(f"unknown MotionConfig field {k!r}")
            setattr(cfg, k, v)
        self.cfg = cfg
        self._st: dict[int, _State] = {}

    def reset(self) -> None:
        self._st.clear()

    # ------------------------------------------------------------------ helpers
    def _measure(self, b) -> float:
        w = max(b[2] - b[0], 1e-3)
        h = max(b[3] - b[1], 1e-3)
        m = self.cfg.scale_mode
        if m == "h":
            return math.log(h)
        if m == "w":
            return math.log(w)
        return 0.5 * (math.log(w) + math.log(h))

    def _reject(self, k: int, boxes: np.ndarray, img_w: float, img_h: float) -> Optional[str]:
        c = self.cfg
        x1, y1, x2, y2 = boxes[k]
        if (y2 - y1) < c.min_box_h_px:
            return "small"
        bp = c.border_px
        if x1 <= bp or y1 <= bp or x2 >= img_w - 1 - bp or y2 >= img_h - 1 - bp:
            return "truncated"
        if c.occlusion_ioa < 1.0 and len(boxes) > 1:
            area = max((x2 - x1) * (y2 - y1), 1e-6)
            front = boxes[:, 3] > y2  # nearer objects have lower bottoms (larger y2)
            front[k] = False
            if front.any():
                f = boxes[front]
                iw = np.clip(np.minimum(f[:, 2], x2) - np.maximum(f[:, 0], x1), 0, None)
                ih = np.clip(np.minimum(f[:, 3], y2) - np.maximum(f[:, 1], y1), 0, None)
                if float((iw * ih).max()) / area > c.occlusion_ioa:
                    return "occluded"
        return None

    # --------------------------------------------------------------------- main
    def update(self, tracks: Iterable[tuple[int, str, list[float]]], t: float,
               frame_shape: tuple[int, int], ego_polygon=None) -> dict[int, MotionOutput]:
        """tracks: (track_id, class, [x1, y1, x2, y2]) for every track matched this frame.

        ego_polygon: optional [[x, y], ...] ego-path polygon in pixels (plan section 13).
        """
        c = self.cfg
        tracks = list(tracks)
        img_h, img_w = frame_shape[:2]
        poly = self._corridor(ego_polygon, img_w, img_h)
        boxes = np.asarray([tr[2] for tr in tracks], dtype=np.float64).reshape(-1, 4)
        out: dict[int, MotionOutput] = {}
        for k, (tid, cls, b) in enumerate(tracks):
            st = self._st.get(tid)
            if st is None:
                st = self._st[tid] = _State()
            if st.t and (t - st.last_seen) > c.gap_reset_s:
                st.t.clear(); st.y.clear(); st.cx.clear(); st.lw.clear()
            st.last_seen = t
            reason = self._reject(k, boxes, img_w, img_h)
            if reason is None:
                w = max(b[2] - b[0], 1e-3)
                cx = 0.5 * (b[0] + b[2])
                st.t.append(t); st.y.append(self._measure(b)); st.cx.append(cx)
                st.lw.append((cx - 0.5 * img_w) / w)
            while st.t and st.t[0] < t - c.window_s - 1e-9:
                st.t.popleft(); st.y.popleft(); st.cx.popleft(); st.lw.popleft()
            o = MotionOutput(n_samples=len(st.t), sample_valid=reason is None, reject_reason=reason)
            if len(st.t) >= c.min_samples and (st.t[-1] - st.t[0]) >= c.min_span_s:
                ta = np.fromiter(st.t, float)
                s, lo, hi = theil_sen(ta, np.fromiter(st.y, float), c.ci_z)
                o.scale_rate, o.scale_rate_lo, o.scale_rate_hi = s, lo, hi
                o.lateral_px_s = theil_sen(ta, np.fromiter(st.cx, float), c.ci_z)[0]
                o.lateral_widths_s = theil_sen(ta, np.fromiter(st.lw, float), c.ci_z)[0]
                if s > 0:
                    ttc = 1.0 / s
                    if c.lag_compensate:
                        ttc -= (ta[-1] - float(np.median(ta)))
                    ttc = max(ttc, 0.05)
                    o.ttc_s = float(ttc) if ttc <= c.ttc_max_s else None
                st.last_est_t = t
                st.ever_est = True
            self._hysteresis(st, o, cls, t)
            self._corridor_state(st, o, b, poly, t)
            out[tid] = o
        # forget stale tracks
        for tid in [i for i, s in self._st.items() if t - s.last_seen > c.forget_s]:
            del self._st[tid]
        return out

    def _hysteresis(self, st: _State, o: MotionOutput, cls: str, t: float) -> None:
        c = self.cfg
        s, lo = o.scale_rate, o.scale_rate_lo
        eligible = cls in c.approach_classes
        if not st.approaching:
            if eligible and s is not None and s >= c.on_rate and lo is not None and lo > 0:
                st.on_since = t if st.on_since is None else st.on_since
                if t - st.on_since >= c.on_hold_s - 1e-9:
                    st.approaching, st.off_since = True, None
            elif s is not None or not eligible:
                st.on_since = None
        else:
            if not eligible or (s is not None and s <= c.off_rate):
                st.off_since = t if st.off_since is None else st.off_since
                if t - st.off_since >= c.off_hold_s - 1e-9 or not eligible:
                    st.approaching, st.on_since = False, None
            elif s is None and (t - st.last_est_t) >= c.stale_s:
                st.approaching, st.on_since, st.off_since = False, None, None
            elif s is not None:
                st.off_since = None
        o.closing = st.approaching if st.ever_est else None

    def _corridor(self, ego_polygon, img_w: float, img_h: float):
        if self.cfg.approach_scope == "any":
            return None
        if ego_polygon is not None and len(ego_polygon) >= 3:
            return np.asarray(ego_polygon, np.float32).reshape(-1, 1, 2)
        return (np.asarray(self.cfg.corridor_norm, np.float32) * np.float32([img_w, img_h])).reshape(-1, 1, 2)

    def _corridor_state(self, st: _State, o: MotionOutput, b, poly, t: float) -> None:
        if poly is None:
            o.in_corridor = None
            o.approaching = o.closing
            return
        import cv2

        w = b[2] - b[0]
        y = float(min(b[3], poly[:, 0, 1].max() - 1.0))  # hood / frame bottom: clamp into polygon
        inside = any(cv2.pointPolygonTest(poly, (float(b[0] + f * w), y), False) >= 0 for f in (0.25, 0.5, 0.75))
        if inside:
            st.in_corr, st.corr_off_since = True, None
        elif st.in_corr:
            st.corr_off_since = t if st.corr_off_since is None else st.corr_off_since
            if t - st.corr_off_since >= self.cfg.corridor_off_hold_s - 1e-9:
                st.in_corr = False
        o.in_corridor = st.in_corr
        o.approaching = None if o.closing is None else bool(o.closing and st.in_corr)
