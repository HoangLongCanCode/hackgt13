"""Per-object distance for the Knuckle Sandwich Robotics Inc. (KSR) AI Spatial
Driving Copilot (plan section 9 "the car ahead is 18 m away"; feeds section 11
light distance and section 18 following distance).

    from perception.depth import DistanceEstimator
    est = DistanceEstimator(depth_backend="da3_metric_large", depth_every=2)  # BDD defaults: f=700 px @1280
    dists = est.estimate(frame_bgr, tracked_detections, pts_s=frame.pts_s)  # list[DistanceEstimate]
    depth = est.depth_map(frame_bgr)                                        # HxW float32 metres

Four estimates per object, fused in log space by inverse variance and then
smoothed per track id with a constant-velocity Kalman filter:
  depth_model  robust percentile of the metric depth map in the lower-central box
  ground_plane flat-road range from the bbox bottom row and a per-frame horizon
  width_prior  class size prior (width for rear/front views, height otherwise)
  fused        the combination, with sigma and a confidence in [0.05, 0.99]

This is measurement for display (plan sections 18 and 38): it never claims a
"safe distance" and must not drive any control decision.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Optional, Sequence

import numpy as np

from perception.common.schemas import DistanceEstimate, Detection, VEHICLE_CLASSES, canonical_class
from perception.depth import geometry as G
from perception.depth.backends import make_backend

# Relative 1-sigma of a box depth readout per backend. Values from the KITTI
# per-object evaluation in this folder (median |rel err| x 1.4826, rounded up);
# see outputs/depth/metrics.json. BDD is a different camera, so these are floors.
DEPTH_REL_SIGMA = {
    "da2_metric_outdoor_small": 0.18,   # KITTI median |rel err| 12.2 %
    "da3_metric_large": 0.13,           # 8.5 %
    "metric3d_vit_s_onnx": 0.08,        # 4.9 %
}
DEFAULT_CLASSES = VEHICLE_CLASSES | {"pedestrian", "rider"}


@dataclass
class _Kalman:
    """Constant-velocity filter on [Z, dZ/dt] with soft outlier gating."""
    z: float
    p: np.ndarray
    v: float = 0.0
    t: float = 0.0
    n: int = 1
    accel_sigma: float = 3.0  # m/s^2

    def step(self, t: float, zm: float, sigma: float) -> tuple[float, float]:
        dt = max(1e-3, t - self.t)
        Fm = np.array([[1.0, dt], [0.0, 1.0]])
        q = self.accel_sigma ** 2
        Q = q * np.array([[dt ** 4 / 4, dt ** 3 / 2], [dt ** 3 / 2, dt ** 2]])
        x = Fm @ np.array([self.z, self.v])
        P = Fm @ self.p @ Fm.T + Q
        R = sigma ** 2
        S = P[0, 0] + R
        innov = zm - x[0]
        if innov ** 2 > 9 * S:  # soft gate: inflate R instead of dropping
            R *= innov ** 2 / (9 * S)
            S = P[0, 0] + R
        K = P[:, 0] / S
        x = x + K * innov
        P = P - np.outer(K, P[0, :])
        self.z, self.v, self.p, self.t, self.n = float(x[0]), float(x[1]), P, t, self.n + 1
        return self.z, math.sqrt(max(P[0, 0], 1e-6))


@dataclass
class _Scalar1D:
    """Tiny 1-D Kalman for slowly varying calibration values (horizon row)."""
    x: Optional[float] = None
    p: float = 1e6
    t: Optional[float] = None
    q_per_s: float = 15.0 ** 2  # px^2/s (pitch changes: braking, bumps)

    def update(self, t: float, meas: float, sigma: float) -> float:
        if self.x is None:
            self.x, self.p, self.t = meas, sigma ** 2, t
            return self.x
        self.p += self.q_per_s * max(1e-3, t - (self.t or t))
        k = self.p / (self.p + sigma ** 2)
        self.x += k * (meas - self.x)
        self.p *= (1 - k)
        self.t = t
        return self.x


class DistanceEstimator:
    """Per-object metric distance from a monocular dashcam frame.

    Parameters
    ----------
    depth_backend : 'da3_metric_large' (default) | 'da2_metric_outdoor_small' | 'metric3d_vit_s_onnx' | None
        DA3 is the default because it is the most self-consistent on BDD (agrees with the
        flat-ground and size-prior estimates within ~3-10 %); DA2 is the fast fallback but
        over-estimates BDD distances ~2x (FOV mismatch) and relies on 'scale_from_height'.
        None = geometry only (zero VRAM, lower confidence on slopes / occluded boxes).
    focal_px : focal length in px at the *input* frame width (default: BDD 700 px @ 1280).
    cam_height_m : camera height above the road. None = estimate it from the depth
        model's road profile when the backend takes intrinsics (DA3 / Metric3D);
        otherwise 1.3 m (windscreen phone mount; an assumption).
    calibration : 'auto' | 'fixed' | 'height_from_depth' | 'scale_from_height' | 'none'
        'height_from_depth' trusts the depth scale and estimates H_c; 'scale_from_height'
        trusts H_c and rescales the depth map (s = H_c / H_depth). 'auto' picks the
        first for intrinsics-aware backends when cam_height_m is None, the second for DA2,
        and 'fixed' when cam_height_m is given for an intrinsics-aware backend.
    horizon : 'auto' | 'depth' | 'virtual' | 'fixed' | float
        Horizon row for the flat-ground estimate. 'auto' fuses the depth road-profile
        horizon, the virtual horizon from object size priors and the principal row.
        A float fixes the row; a per-call `horizon_y` (e.g. from the lanes block's
        RoadGeometry.horizonY) always wins.
    depth_every : run the depth network on every n-th call (geometry-only in between).
    methods : subset of ('depth', 'ground', 'size') used in the fusion.
    temporal : EMA/Kalman smoothing across calls (set False for independent images).
    hood_row : 'auto' | float | None. Top row of the ego hood/dashboard (BDD phones often
        see it). 'auto' detects it from depth discontinuities (works for DA3, often not
        for DA2/Metric3D, which smooth the hood into the road); a float fixes it per
        video; None disables. Boxes whose bottom reaches the hood get no flat-ground
        or height-prior estimate, and road rows below it are not used for calibration.
    """

    def __init__(self, depth_backend: Optional[str] = "da3_metric_large", focal_px: Optional[float] = None,
                 cam_height_m: Optional[float] = None, device: str = "cuda", calibration: str = "auto",
                 horizon="auto", depth_every: int = 1, readout_percentile: float = 30.0,
                 classes: Optional[set] = None, methods: Sequence[str] = ("depth", "ground", "size"),
                 temporal: bool = True, depth_rel_sigma: Optional[float] = None, cx: Optional[float] = None,
                 cy: Optional[float] = None, pitch_deg: float = 0.0, max_distance_m: float = 150.0,
                 backend_opts: Optional[dict] = None, backend=None, hood_row="auto"):
        if isinstance(backend, str):  # review fix: accept the integration convention backend='<name>' ('none' = geometry)
            depth_backend, backend = (None if backend.lower() == "none" else backend), None
        self.backend = backend if backend is not None else make_backend(depth_backend, device=device, **(backend_opts or {}))
        self.backend_name = self.backend.name if self.backend is not None else None
        self.focal_px, self.cx, self.cy = focal_px, cx, cy
        self.cam_height_given = cam_height_m
        self.pitch_deg = pitch_deg
        needs_focal = bool(self.backend is not None and self.backend.needs_focal)
        if calibration == "auto":
            if self.backend is None:
                calibration = "fixed"
            elif needs_focal:
                calibration = "fixed" if cam_height_m is not None else "height_from_depth"
            else:
                calibration = "scale_from_height"
        self.calibration = calibration
        self.horizon_mode = horizon
        self.depth_every = max(1, int(depth_every))
        self.readout_percentile = readout_percentile
        self.classes = set(classes) if classes is not None else set(DEFAULT_CLASSES)
        self.methods = tuple(methods)
        self.temporal = temporal
        self.depth_rel_sigma = depth_rel_sigma if depth_rel_sigma is not None else DEPTH_REL_SIGMA.get(self.backend_name, 0.15)
        self.max_distance_m = max_distance_m
        self.detect_hood = hood_row
        self.cam: Optional[G.Camera] = None
        self.reset()

    # ------------------------------------------------------------------ state
    def reset(self) -> None:
        """Forget per-video state (tracks, horizon filter, calibration history)."""
        self._tracks: dict[int, _Kalman] = {}
        self._yh = _Scalar1D()
        self._h_hist: list[float] = []
        self._s_hist: list[float] = []
        self._hood_hist: list[float] = []
        self._calls = 0
        self._last_t = 0.0
        self.last_depth: Optional[np.ndarray] = None
        self.last_info: dict = {}
        self.last_details: list[dict] = []

    def set_camera(self, focal_px: float, cx: Optional[float] = None, cy: Optional[float] = None,
                   cam_height_m: Optional[float] = None, width: Optional[int] = None, height: Optional[int] = None):
        """Override intrinsics (e.g. KITTI P2). Takes effect on the next call."""
        self.focal_px, self.cx, self.cy = focal_px, cx, cy
        if cam_height_m is not None:
            self.cam_height_given = cam_height_m
        self.cam = None
        if width and height:
            self._ensure_camera(width, height)

    def _ensure_camera(self, w: int, h: int) -> G.Camera:
        if self.cam is None or self.cam.width != w or self.cam.height != h:
            cam = G.Camera.bdd_default(w, h, self.focal_px, self.cam_height_given or 1.3)
            if self.cx is not None:
                cam.cx = self.cx
            if self.cy is not None:
                cam.cy = self.cy
            self.cam = cam
        return self.cam

    # ------------------------------------------------------------------ depth
    def raw_depth_map(self, frame_bgr: np.ndarray) -> Optional[np.ndarray]:
        """Unscaled network depth (metres) at frame resolution, or None without a backend."""
        if self.backend is None:
            return None
        cam = self._ensure_camera(frame_bgr.shape[1], frame_bgr.shape[0])
        return self.backend.predict(frame_bgr, focal_px=cam.focal_px)

    def depth_map(self, frame_bgr: np.ndarray) -> Optional[np.ndarray]:
        """Metric depth map (metres, HxW float32) with the current scale calibration applied."""
        d = self.raw_depth_map(frame_bgr)
        if d is None:
            return None
        s = self.depth_scale()
        return d * s if s != 1.0 else d

    def depth_scale(self) -> float:
        if self.calibration != "scale_from_height" or not self._s_hist:
            return 1.0
        return float(np.median(self._s_hist[-150:]))

    def hood_row(self) -> Optional[float]:
        """Median hood-edge row over recent depth frames if a hood is seen in >= 30 % of them."""
        if self.detect_hood is None or self.detect_hood is False:
            return None
        if isinstance(self.detect_hood, (int, float)) and not isinstance(self.detect_hood, bool):
            return float(self.detect_hood)
        hist = self._hood_hist[-60:]
        if not hist:
            return None
        v = np.asarray(hist, float)
        ok = np.isfinite(v)
        if ok.mean() < 0.3:
            return None
        return float(np.median(v[ok]))

    def camera_height(self) -> float:
        if self.calibration == "height_from_depth" and self._h_hist:
            return float(np.median(self._h_hist[-150:]))
        return self.cam_height_given or 1.3

    # ------------------------------------------------------------------ main API
    def estimate(self, frame_bgr: np.ndarray, tracked_detections: Sequence[Detection], pts_s: Optional[float] = None,
                 depth_map: Optional[np.ndarray] = None, horizon_y: Optional[float] = None,
                 road_mask: Optional[np.ndarray] = None) -> list[DistanceEstimate]:
        det = self.estimate_detailed(frame_bgr, tracked_detections, pts_s, depth_map, horizon_y, road_mask)
        out = []
        for d in det:
            if d["distance"] is None:
                continue
            out.append(DistanceEstimate(vehicleId=d["id"], distanceMeters=float(d["distance"]),
                                        method=d["method"], confidence=float(d["confidence"])))
        return out

    def estimate_detailed(self, frame_bgr: np.ndarray, tracked_detections: Sequence[Detection],
                          pts_s: Optional[float] = None, depth_map: Optional[np.ndarray] = None,
                          horizon_y: Optional[float] = None, road_mask: Optional[np.ndarray] = None) -> list[dict]:
        """Like estimate() but returns dicts with every component, sigma and the calibration used.

        `depth_map` may be passed in (raw network metres, e.g. computed on another
        thread); otherwise the backend runs every `depth_every` calls.
        """
        h, w = frame_bgr.shape[:2]
        cam = self._ensure_camera(w, h)
        t = pts_s if pts_s is not None else self._last_t + 1 / 30.0
        self._last_t = t
        dets = [d for d in tracked_detections if canonical_class(d.cls) in self.classes]
        boxes = [list(map(float, d.bbox)) for d in dets]
        clss = [canonical_class(d.cls) for d in dets]

        # 1) depth map (raw) + road profile
        raw = depth_map
        need_depth = ("depth" in self.methods or self.calibration in ("height_from_depth", "scale_from_height")
                      or self.horizon_mode in ("auto", "depth"))
        if raw is None and self.backend is not None and need_depth and self._calls % self.depth_every == 0:
            raw = self.raw_depth_map(frame_bgr)
        self._calls += 1
        sky = getattr(self.backend, "last_sky", None) if (self.backend is not None and depth_map is None) else None
        if sky is not None and sky.shape != (h, w):
            sky = None
        prof = None
        if raw is not None:
            hood_prev = self.hood_row()
            prof = G.fit_road_profile(raw, cam, boxes, road_mask=road_mask, sky=sky, hood_row=hood_prev)
            if self.detect_hood == "auto":
                cands = [v for v in (G.detect_hood_row(raw, boxes, road_mask=road_mask, sky=sky),
                                     None if (prof is None or hood_prev is not None) else prof.hood_row)
                         if v is not None]
                self._hood_hist.append(min(cands) if cands else np.nan)
                if not self.temporal:
                    self._hood_hist = self._hood_hist[-1:]
            if prof is not None:
                hc = self.cam_height_given or 1.3
                self._h_hist.append(prof.height_depth_m)
                self._s_hist.append(hc / prof.height_depth_m)
                if not self.temporal:
                    self._h_hist, self._s_hist = self._h_hist[-1:], self._s_hist[-1:]
        hood_row = self.hood_row()
        scale = self.depth_scale()
        H_c = self.camera_height()
        rel_sigma_h = {"fixed": 0.03 if self.cam_height_given else 0.12,
                       "height_from_depth": 0.08, "scale_from_height": 0.12, "none": 0.12}.get(self.calibration, 0.12)
        depth = raw * scale if (raw is not None and scale != 1.0) else raw
        self.last_depth = depth

        # 2) horizon
        yh, syh, yh_src = self._horizon(t, cam, H_c, prof, list(zip(clss, boxes)), horizon_y)

        # 3) per-object components
        results = []
        for i, (d, cls, box) in enumerate(zip(dets, clss, boxes)):
            comp: dict[str, tuple[float, float]] = {}
            x1, y1, x2, y2 = box
            occ_bottom, occ_all = self._occlusion(i, boxes)
            if "depth" in self.methods and depth is not None:
                nearer = [bj for j, bj in enumerate(boxes) if j != i and bj[3] > y2 + 2]
                r = G.box_readout(depth, box, cls, self.readout_percentile, sky, exclude_boxes=nearer)
                if r is not None and 0.5 < r[0] < self.max_distance_m:
                    zr, spread, _ = r
                    rel = math.hypot(self.depth_rel_sigma, 0.25 * min(spread, 2.0))
                    if occ_all > 0.3:
                        rel *= 1.5
                    comp["depth_model"] = (zr, zr * rel)
            bottom_ok = y2 < h - 3 and (hood_row is None or y2 < hood_row - 2)
            if "ground" in self.methods and cls in G.GROUND_CLASSES and yh is not None:
                if bottom_ok and (y2 - yh) > 2.0:
                    zg = G.ground_distance(y2, yh, cam, H_c)
                    if zg is not None and 0.5 < zg < self.max_distance_m:
                        sg = G.ground_sigma(zg, cam, 1.5 + 0.01 * (y2 - y1), syh, rel_sigma_h)
                        if occ_bottom > 0.3:
                            sg *= 3.0
                        comp["ground_plane"] = (zg, sg)
            if "size" in self.methods:
                sz = G.size_distances(cls, box, cam, bottom_limit=hood_row)
                if sz:
                    ws = [(z, s) for z, s in sz.values()]
                    wt = np.array([1 / (s / z) ** 2 for z, s in ws])
                    lz = float(np.sum(wt * np.log([z for z, _ in ws])) / wt.sum())
                    zs = math.exp(lz)
                    ss = zs / math.sqrt(wt.sum())
                    if occ_all > 0.3:
                        ss *= 2.0
                    if 0.5 < zs < self.max_distance_m:
                        comp["width_prior"] = (zs, ss)
            z, s, method = self._fuse(comp)
            tid = d.id if d.id is not None else -(i + 1)
            zf, sf = z, s
            if z is not None and self.temporal and d.id is not None:
                kf = self._tracks.get(tid)
                if kf is None or (t - kf.t) > 1.5:
                    kf = _Kalman(z=z, p=np.diag([s ** 2, 25.0]), t=t)
                    self._tracks[tid] = kf
                else:
                    zf, sf = kf.step(t, z, s)
            conf = None
            if zf is not None:
                conf = float(np.clip(1.0 - (sf / zf) / 0.30, 0.05, 0.99))
                if len(comp) == 1:
                    conf = min(conf, 0.6)
            results.append({
                "id": tid, "class": cls, "bbox": box, "distance": zf, "sigma": sf, "raw_fused": z,
                "method": method, "confidence": conf,
                "components": {k: round(v[0], 3) for k, v in comp.items()},
                "sigmas": {k: round(v[1], 3) for k, v in comp.items()},
                "lateral_m": None if zf is None else float(((x1 + x2) / 2 - cam.cx) * zf / cam.focal_px),
            })
        # forget stale tracks
        for k in [k for k, kf in self._tracks.items() if t - kf.t > 3.0]:
            del self._tracks[k]
        self.last_info = {"horizon_y": yh, "horizon_sigma_px": syh, "horizon_source": yh_src,
                          "camera_height_m": H_c, "depth_scale": scale, "focal_px": cam.focal_px, "hood_row": hood_row,
                          "road_profile": None if prof is None else prof.__dict__,
                          "depth_ms": getattr(self.backend, "last_ms", None) if raw is not None and depth_map is None else None}
        self.last_details = results
        return results

    # ------------------------------------------------------------------ helpers
    def _horizon(self, t, cam: G.Camera, H_c: float, prof, dets, horizon_y):
        prior = cam.cy - cam.focal_px * math.tan(math.radians(self.pitch_deg))
        if horizon_y is not None:
            return float(horizon_y), 3.0, "given"
        mode = self.horizon_mode
        if isinstance(mode, (int, float)) and not isinstance(mode, bool):
            return float(mode), 3.0, "fixed_row"
        if mode == "fixed":
            return prior, 3.0, "principal_row"
        cands = []
        if mode in ("auto", "depth") and prof is not None:
            cands.append((prof.horizon_y, 4.0 + 20.0 * (1 - prof.inlier_frac), "depth_profile"))
        if mode in ("auto", "virtual"):
            vh = G.virtual_horizon(dets, cam, H_c)
            if vh is not None:
                cands.append((vh[0], vh[1] * (1.0 if vh[2] > 1 else 1.5), "virtual"))
        if mode == "auto":
            cands.append((prior, 0.06 * cam.height, "principal_row"))  # weak prior (mount pitch unknown)
        if not cands:
            if self.temporal and self._yh.x is not None:
                return self._yh.x, math.sqrt(self._yh.p), "tracked"
            return prior, 0.06 * cam.height, "principal_row"
        wts = np.array([1 / s ** 2 for _, s, _ in cands])
        y = float(np.sum(wts * np.array([c[0] for c in cands])) / wts.sum())
        s = float(1 / math.sqrt(wts.sum()))
        src = "+".join(c[2] for c in cands)
        if self.temporal:
            y = self._yh.update(t, y, s)
            s = max(2.0, math.sqrt(self._yh.p))
        return y, s, src

    @staticmethod
    def _occlusion(i: int, boxes: list[list[float]]) -> tuple[float, float]:
        """Fraction of box i's bottom strip / whole area covered by nearer boxes (larger y2)."""
        x1, y1, x2, y2 = boxes[i]
        area = max(1.0, (x2 - x1) * (y2 - y1))
        sb_y1 = y2 - 0.15 * (y2 - y1)
        cov_b, cov_a = 0.0, 0.0
        for j, (a1, b1, a2, b2) in enumerate(boxes):
            if j == i or b2 <= y2:
                continue
            ix = max(0.0, min(x2, a2) - max(x1, a1))
            cov_a += ix * max(0.0, min(y2, b2) - max(y1, b1))
            cov_b += ix * max(0.0, min(y2, b2) - max(sb_y1, b1))
        return min(1.0, cov_b / max(1.0, (x2 - x1) * (y2 - sb_y1))), min(1.0, cov_a / area)

    @staticmethod
    def _fuse(comp: dict[str, tuple[float, float]]):
        if not comp:
            return None, None, "none"
        names = list(comp)
        z = np.array([comp[k][0] for k in names])
        rel = np.array([max(1e-3, comp[k][1] / comp[k][0]) for k in names])
        w = 1 / rel ** 2
        lz = float(np.sum(w * np.log(z)) / w.sum())
        srel = float(1 / math.sqrt(w.sum()))
        if len(names) > 1:  # inflate when the estimates disagree more than their sigmas allow
            chi2 = float(np.sum(w * (np.log(z) - lz) ** 2)) / (len(names) - 1)
            if chi2 > 1:
                srel *= math.sqrt(chi2)
        zf = math.exp(lz)
        method = "fused" if len(names) > 1 else names[0]
        return zf, zf * srel, method

    def close(self) -> None:
        if self.backend is not None:
            self.backend.close()
        self.backend = None
