"""comma.ai openpilot v0.11.1 driving_vision.onnx on arbitrary dashcam video (EXPERIMENTAL).

Plan §10 (lane lines), §9/§18 (lead distance), §13/§19 (metric anchors), §14 (reuse of open AV research).
Visualisation / information only (plan §38): nothing here may drive steering, braking or acceleration.

What the network gets (reproduced from openpilot v0.11.1 selfdrive/modeld/{modeld,compile_modeld}.py):
  * two frames 0.2 s apart (MODEL_CONTEXT_FREQ = 5 Hz), oldest first;
  * each frame warped into two 512x256 "model frames" by get_warp_matrix():
      img     -> medmodel  (f = 910 px, horizon at row 47.6)   "narrow road camera"
      big_img -> sbigmodel (f = 455 px, horizon at row 151.8)  "wide road camera"
    We have one camera, so both come from the same BDD frame (openpilot does the same when
    only one road camera exists: modeld's `main_wide_camera` path);
  * each warped frame -> YUV420 (BT.601 limited range) packed as 6 x 128 x 256 uint8:
      Y[0::2,0::2], Y[1::2,0::2], Y[0::2,1::2], Y[1::2,1::2], U, V  (compile_modeld.frames_to_tensor);
  * the vision net is stateless (the 25 x 512 temporal feature buffer belongs to driving_policy,
    which is NOT run here - its outputs are control plans we must not use, §38).

Outputs are in openpilot's CALIBRATED frame: x forward, y right, z down, metres, origin at the camera.
Lane-line / road-edge z is therefore ~ +camera height for points on the road.

Usage
-----
    import torch  # must precede onnxruntime so the CUDA EP finds torch's CUDA/cuDNN DLLs
    from perception.openpilot.driving_model import OpenpilotVision
    op = OpenpilotVision(device="cuda", focal_px=1100.0)     # BDD100K 1280x720: f ~1100 px measured (README)
    op.set_calibration(rpy=(0.0, pitch, yaw))                # or op.self_calibrate(frames_iter)
    res = op.infer(frame_t, frame_t_minus_0p2s)              # BGR uint8 HxWx3
    res["lane_lines"]  # (4, 33, 3) x/y/z  [left-left, ego-left, ego-right, right-right]
    res["lane_probs"]  # (4,)
    res["road_edges"]  # (2, 33, 3) [left, right]
    res["lead"]        # dict: prob (3,), x/y/v/a at t=0, stds, traj (3,6,4)
    res["pose"]        # dict: trans m/s (device frame), rot rad/s, speed_mps
    img_lines = op.project_result(res)                      # polylines in source pixels
"""
from __future__ import annotations

import time
from collections import deque
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Optional

import numpy as np
import cv2

from perception.openpilot import op_geometry as G

try:  # shared schema types (plan JSON field names)
    from perception.common.schemas import LaneState, DistanceEstimate, Detection
except Exception:  # pragma: no cover - allow standalone use
    LaneState = DistanceEstimate = Detection = None  # type: ignore

try:  # folder layout (PERCEPTION_MODELS_DIR overrides the models folder)
    from perception.common.paths import MODELS_ROOT, PROJECT_ROOT
except Exception:  # pragma: no cover - allow standalone use
    PROJECT_ROOT = Path(__file__).resolve().parents[2]
    MODELS_ROOT = PROJECT_ROOT / "models"
DEFAULT_MODEL = MODELS_ROOT / "openpilot" / "driving_vision.onnx"   # scripts/download_models.py
MODEL_URL = ("https://media.githubusercontent.com/media/commaai/openpilot/v0.11.1/"
             "selfdrive/modeld/models/driving_vision.onnx")
MODEL_SHA256 = "ee29ee5bce84d1ce23e9ff381280de9b4e4d96d2934cd751740354884e112c66"

LANE_NAMES = ("left_left", "ego_left", "ego_right", "right_right")
EDGE_NAMES = ("left_edge", "right_edge")

# calibrationd.py (v0.11.1) filter constants
MIN_SPEED_FILTER = 15 * 0.44704          # 15 mph in m/s
MAX_YAW_RATE_FILTER = np.radians(2)      # rad/s
PITCH_LIMITS = np.array([-0.09074112085129739, 0.17])
YAW_LIMITS = np.array([-0.06912048084718224, 0.06912048084718235])


def _sigmoid(x):
    return 1.0 / (1.0 + np.exp(-np.clip(x, -30, 30)))


def _safe_exp(x):
    return np.exp(np.clip(x, -np.inf, 11))


def pack_yuv420(bgr_512x256: np.ndarray, y_order: str = "modeld") -> np.ndarray:
    """BGR 256x512 -> uint8 (6, 128, 256) in openpilot channel order."""
    yuv = cv2.cvtColor(bgr_512x256, cv2.COLOR_BGR2YUV_I420)  # (384, 512), BT.601 limited range
    H, W = 256, 512
    Y = yuv[:H]
    U = yuv[H:H + H // 4].reshape(H // 2, W // 2)
    V = yuv[H + H // 4:H + H // 2].reshape(H // 2, W // 2)
    if y_order == "modeld":      # compile_modeld.frames_to_tensor (what actually runs on the car)
        ys = [Y[0::2, 0::2], Y[1::2, 0::2], Y[0::2, 1::2], Y[1::2, 1::2]]
    elif y_order == "readme":    # models/README.md wording (2nd/3rd swapped) - for A/B testing only
        ys = [Y[0::2, 0::2], Y[0::2, 1::2], Y[1::2, 0::2], Y[1::2, 1::2]]
    else:
        raise ValueError(y_order)
    return np.stack(ys + [U, V], axis=0)


@dataclass
class PreparedFrame:
    img: np.ndarray       # (6,128,256) uint8, narrow / medmodel
    big_img: np.ndarray   # (6,128,256) uint8, wide / sbigmodel
    pts_s: float = 0.0


class OpenpilotVision:
    """openpilot driving_vision (v0.11.1) wrapper returning metric lane lines, road edges, lead, pose.

    backend: "ort-cuda" (onnxruntime CUDA EP, default) or "ort-cpu".
    focal_px: source-camera focal length in pixels. BDD100K ships no intrinsics; 1100 px at 1280x720 is the value
        measured in this block (dashed-lane speed reference, README). Every metric distance/speed scales with it.
    rpy: device_from_calib (roll, pitch, yaw); use self_calibrate() or set_calibration_from_vp() per camera.
    cam_height_m: only used by ground_point() / flat-ground helpers, not by the network.
    y_order: "modeld" (compile_modeld.frames_to_tensor, default) or "readme" (A/B testing only).
    """

    def __init__(self, backend: str = "ort-cuda", device: str = "cuda",
                 model_path: str | Path | None = None,
                 focal_px: float = 1100.0, image_size: tuple[int, int] = (1280, 720),
                 principal_point: Optional[tuple[float, float]] = None,
                 rpy: Iterable[float] = (0.0, 0.0, 0.0),
                 cam_height_m: float = 1.3,
                 y_order: str = "modeld", interp: str = "linear",
                 gpu_mem_limit_mb: int = 1024, **_ignored: Any):
        self.backend = backend
        self.device = device
        self.model_path = Path(model_path) if model_path else DEFAULT_MODEL
        if not self.model_path.exists():
            raise FileNotFoundError(f"{self.model_path} missing - download {MODEL_URL}")
        self.image_size = tuple(image_size)
        self.principal_point = principal_point
        self.cam_height_m = float(cam_height_m)
        self.y_order = y_order
        self.interp = {"linear": cv2.INTER_LINEAR, "nearest": cv2.INTER_NEAREST}[interp]
        self._make_session(backend, device, gpu_mem_limit_mb)
        self.set_calibration(rpy=rpy, focal_px=focal_px)

    # ------------------------------------------------------------------ runtime
    def _make_session(self, backend: str, device: str, gpu_mem_limit_mb: int) -> None:
        use_cuda = backend in ("ort-cuda", "ort") and str(device).startswith("cuda")
        if use_cuda:
            import torch  # noqa: F401  (loads CUDA/cuDNN DLLs that the ORT CUDA EP needs on Windows)
        import onnxruntime as ort
        try:
            ort.set_default_logger_severity(3)  # silence the harmless "No registered plugin EP" warning
        except Exception:
            pass
        so = ort.SessionOptions()
        so.log_severity_level = 3
        if use_cuda:
            dev_id = int(str(device).split(":")[1]) if ":" in str(device) else 0
            providers = [("CUDAExecutionProvider", {
                "device_id": dev_id, "gpu_mem_limit": int(gpu_mem_limit_mb) * 1024 * 1024,
                "arena_extend_strategy": "kSameAsRequested", "cudnn_conv_algo_search": "HEURISTIC"}),
                "CPUExecutionProvider"]
        elif backend in ("ort-cpu", "ort") or device == "cpu":
            providers = ["CPUExecutionProvider"]
        else:
            raise ValueError(f"unknown backend {backend!r} (use 'ort-cuda' or 'ort-cpu')")
        self.session = ort.InferenceSession(str(self.model_path), so, providers=providers)
        self.providers = self.session.get_providers()

    def close(self) -> None:
        self.session = None

    # ------------------------------------------------------------------ calibration
    def set_calibration(self, rpy: Optional[Iterable[float]] = None, focal_px: Optional[float] = None,
                        principal_point: Optional[tuple[float, float]] = None,
                        cam_height_m: Optional[float] = None) -> None:
        """rpy = device_from_calib euler (roll, pitch, yaw), radians; pitch > 0 = camera tilted down
        (horizon above image centre); yaw > 0 = camera turned left (road vanishing point right of centre)."""
        if focal_px is not None:
            self.focal_px = float(focal_px)
        if principal_point is not None:
            self.principal_point = principal_point
        if rpy is not None:
            self.rpy = np.asarray(list(rpy), dtype=np.float64)
        if cam_height_m is not None:
            self.cam_height_m = float(cam_height_m)
        w, h = self.image_size
        cx, cy = self.principal_point if self.principal_point else (w / 2, h / 2)
        self.K = G.intrinsics(self.focal_px, w, h, cx, cy)
        self.M_img = G.get_warp_matrix(self.rpy, self.K, bigmodel_frame=False)
        self.M_big = G.get_warp_matrix(self.rpy, self.K, bigmodel_frame=True)

    def set_calibration_from_vp(self, vp_xy: tuple[float, float]) -> None:
        self.set_calibration(rpy=G.calib_from_vp(vp_xy, self.K))

    @property
    def vanishing_point(self) -> np.ndarray:
        return G.vp_from_calib(self.rpy, self.K)

    # ------------------------------------------------------------------ pre-processing
    def warp(self, frame_bgr: np.ndarray, big: bool = False) -> np.ndarray:
        """Source BGR frame -> 512x256 BGR model frame (narrow or wide)."""
        if frame_bgr.shape[1::-1] != self.image_size:
            raise ValueError(f"frame {frame_bgr.shape[1::-1]} != configured image_size {self.image_size}")
        M = self.M_big if big else self.M_img
        return cv2.warpPerspective(frame_bgr, M, (512, 256), flags=self.interp | cv2.WARP_INVERSE_MAP,
                                   borderMode=cv2.BORDER_REPLICATE)

    def prepare(self, frame_bgr: np.ndarray, pts_s: float = 0.0) -> PreparedFrame:
        return PreparedFrame(pack_yuv420(self.warp(frame_bgr, False), self.y_order),
                             pack_yuv420(self.warp(frame_bgr, True), self.y_order), pts_s)

    # ------------------------------------------------------------------ inference
    def run_raw(self, prev: PreparedFrame, cur: PreparedFrame) -> np.ndarray:
        feeds = {"img": np.concatenate([prev.img, cur.img], 0)[None],
                 "big_img": np.concatenate([prev.big_img, cur.big_img], 0)[None]}
        return self.session.run(None, feeds)[0][0].astype(np.float32)

    def infer(self, frame_t: np.ndarray, frame_t_minus_0p2s: np.ndarray) -> dict[str, Any]:
        """Run on two BGR frames 0.2 s apart. Returns the parsed dict (see module doc)."""
        t0 = time.perf_counter()
        cur, prev = self.prepare(frame_t), self.prepare(frame_t_minus_0p2s)
        t1 = time.perf_counter()
        res = self.infer_prepared(prev, cur)
        res["timings_ms"]["prep"] = (t1 - t0) * 1e3
        return res

    def infer_prepared(self, prev: PreparedFrame, cur: PreparedFrame) -> dict[str, Any]:
        t1 = time.perf_counter()
        raw = self.run_raw(prev, cur)
        t2 = time.perf_counter()
        res = self.parse(raw)
        res["timings_ms"] = {"model": (t2 - t1) * 1e3, "parse": (time.perf_counter() - t2) * 1e3}
        return res

    @staticmethod
    def parse(raw: np.ndarray) -> dict[str, Any]:
        """Parse the flat 1576-vector exactly like parse_model_outputs.Parser.parse_vision_outputs."""
        S = G.OUTPUT_SLICES

        def mdn(name, shape):
            v = raw[S[name]]
            n = v.shape[0] // 2
            return v[:n].reshape(shape), _safe_exp(v[n:2 * n]).reshape(shape)

        pose_mu, pose_std = mdn("pose", (6,))
        rt_mu, rt_std = mdn("road_transform", (6,))
        wfd_mu, wfd_std = mdn("wide_from_device_euler", (3,))
        ll_mu, ll_std = mdn("lane_lines", (4, G.IDX_N, 2))       # (y, z) at X_IDXS
        re_mu, re_std = mdn("road_edges", (2, G.IDX_N, 2))
        lead_mu, lead_std = mdn("lead", (3, 6, 4))                # 3 prob-times x 6 t x (x, y, v, a)
        lane_probs = _sigmoid(raw[S["lane_lines_prob"]])[1::2]
        lead_prob = _sigmoid(raw[S["lead_prob"]])
        meta = _sigmoid(raw[S["meta"]])
        dp = raw[S["desire_pred"]].reshape(4, 8)
        dp = np.exp(dp - dp.max(-1, keepdims=True)); dp /= dp.sum(-1, keepdims=True)

        X = G.X_IDXS
        lane_lines = np.stack([np.broadcast_to(X, (4, G.IDX_N)), ll_mu[..., 0], ll_mu[..., 1]], -1)
        road_edges = np.stack([np.broadcast_to(X, (2, G.IDX_N)), re_mu[..., 0], re_mu[..., 1]], -1)
        lead0 = lead_mu[0, 0]
        return {
            "lane_lines": lane_lines.astype(np.float32),          # (4,33,3)
            "lane_line_stds": ll_std[:, 0, 0].copy(),             # modelV2.laneLineStds
            "lane_line_y_stds": ll_std[..., 0].copy(),            # (4,33)
            "lane_probs": lane_probs.astype(np.float32),          # (4,)
            "road_edges": road_edges.astype(np.float32),          # (2,33,3)
            "road_edge_stds": re_std[:, 0, 0].copy(),
            "lead": {
                "prob": lead_prob.astype(np.float32),             # P(lead) now / in 2 s / in 4 s
                "x": float(lead0[0]), "y": float(lead0[1]), "v": float(lead0[2]), "a": float(lead0[3]),
                "x_std": float(lead_std[0, 0, 0]), "y_std": float(lead_std[0, 0, 1]),
                "v_std": float(lead_std[0, 0, 2]),
                "traj": lead_mu.astype(np.float32), "traj_std": lead_std.astype(np.float32),
                "t": G.LEAD_T_IDXS,
            },
            "pose": {
                "trans": pose_mu[:3].copy(), "rot": pose_mu[3:].copy(),
                "trans_std": pose_std[:3].copy(), "rot_std": pose_std[3:].copy(),
                "speed_mps": float(pose_mu[0]),
            },
            "road_transform": {"trans": rt_mu[:3].copy(), "rot": rt_mu[3:].copy(),
                               "trans_std": rt_std[:3].copy(), "rot_std": rt_std[3:].copy()},
            "wide_from_device_euler": wfd_mu.copy(),
            "meta": {"engaged_prob": float(meta[0]),
                     "brake_press_prob_0s": float(meta[32]), "gas_press_prob_0s": float(meta[31]),
                     "left_blinker_prob_0s": float(meta[33]), "right_blinker_prob_0s": float(meta[34])},
            "desire_pred": dp.astype(np.float32),
        }

    # ------------------------------------------------------------------ projection helpers
    def project_points(self, pts_calib: np.ndarray) -> np.ndarray:
        """Nx3 calib-frame points -> Nx2 source-image pixels (NaN if behind camera)."""
        return G.project_calib_points(pts_calib, self.rpy, self.K)

    def ground_point(self, uv) -> np.ndarray:
        """Pixel(s) -> flat-ground calib-frame point(s) (z = cam_height_m)."""
        return G.backproject_to_ground(uv, self.rpy, self.K, self.cam_height_m)

    @property
    def model_view_bottom_row(self) -> float:
        """Source-image row of the bottom edge of both model frames (12.9 deg below the horizon).
        Lines drawn below it are extrapolated by the net (and usually fall on the hood)."""
        ang = np.arctan((G.MEDMODEL_INPUT_SIZE[1] - G.MEDMODEL_CY) / G.medmodel_fl)
        d = np.array([np.cos(ang), 0.0, np.sin(ang)])  # calib-frame ray pitched down by ang
        uv = self.project_points(d[None])[0]
        return float(uv[1])

    def project_line(self, line_xyz: np.ndarray, x_min: float = 0.0, x_max: float = 120.0,
                     clip: bool = True, clip_to_model_view: bool = False) -> np.ndarray:
        m = (line_xyz[:, 0] >= x_min) & (line_xyz[:, 0] <= x_max)
        pts = line_xyz[m]
        if len(pts) >= 2:  # densify so curves look smooth (X_IDXS is quadratic-spaced)
            xs = np.linspace(pts[0, 0], pts[-1, 0], 60)
            pts = np.stack([xs, np.interp(xs, pts[:, 0], pts[:, 1]), np.interp(xs, pts[:, 0], pts[:, 2])], 1)
        uv = self.project_points(pts)
        uv = uv[np.isfinite(uv).all(1)]
        if clip and len(uv):
            w, h = self.image_size
            uv = uv[(uv[:, 0] > -w) & (uv[:, 0] < 2 * w) & (uv[:, 1] >= 0) & (uv[:, 1] <= h)]
        if clip_to_model_view and len(uv):
            uv = uv[uv[:, 1] <= self.model_view_bottom_row + 1.0]
        return uv

    def project_result(self, res: dict[str, Any], x_max: float = 100.0,
                       clip_to_model_view: bool = True) -> dict[str, Any]:
        """Lane lines, road edges and the lead point in source-image pixels.
        clip_to_model_view drops points below the model's field of view (bonnet / extrapolation)."""
        cv = clip_to_model_view
        out = {"lane_lines": [self.project_line(l, 0.0, x_max, clip_to_model_view=cv) for l in res["lane_lines"]],
               "road_edges": [self.project_line(e, 0.0, x_max, clip_to_model_view=cv) for e in res["road_edges"]],
               "lane_probs": res["lane_probs"].tolist(), "lead_uv": None}
        ld = res["lead"]
        if ld["x"] > 0.5:
            z = self._road_z_at(res, ld["x"])
            uv = self.project_points(np.array([[ld["x"], ld["y"], z]]))[0]
            out["lead_uv"] = uv.tolist() if np.isfinite(uv).all() else None
        return out

    def _road_z_at(self, res, x: float) -> float:
        """Road height (z, down) under distance x, from the two ego lane lines (fallback cam height)."""
        ll = res["lane_lines"]
        z = np.interp(x, G.X_IDXS, 0.5 * (ll[1, :, 2] + ll[2, :, 2]))
        return float(z) if np.isfinite(z) and 0.3 < z < 3.5 else self.cam_height_m

    # ------------------------------------------------------------------ schema adapters
    def lane_state(self, res: dict[str, Any], min_prob: float = 0.5, x_max: float = 80.0):
        """Plan §10 LaneState. laneBoundaries: projected lane lines with prob >= min_prob, left->right.
        currentLane / laneCount: heuristic from openpilot's 4 lines + 2 road edges (see README)."""
        info = lane_topology(res, min_prob)
        bounds = []
        for i in range(4):
            if res["lane_probs"][i] >= min_prob:
                uv = self.project_line(res["lane_lines"][i], 0.0, x_max, clip_to_model_view=True)
                if len(uv) >= 2:
                    bounds.append([[round(float(u), 1), round(float(v), 1)] for u, v in uv[::3]])
        conf = float(min(res["lane_probs"][1], res["lane_probs"][2]))
        if LaneState is None:
            return {"currentLane": info["current_lane"], "laneCount": info["lane_count"],
                    "laneBoundaries": bounds, "confidence": conf}
        return LaneState(currentLane=info["current_lane"], laneCount=info["lane_count"],
                         laneBoundaries=bounds, confidence=conf)

    def lead_distance(self, res: dict[str, Any], vehicle_id: int = -1, min_prob: float = 0.5):
        """Plan §9/§18 DistanceEstimate for openpilot's lead (distance from the camera, metres)."""
        p = float(res["lead"]["prob"][0])
        if p < min_prob:
            return None
        if DistanceEstimate is None:
            return {"vehicleId": vehicle_id, "distanceMeters": res["lead"]["x"], "method": "openpilot_lead",
                    "confidence": p}
        return DistanceEstimate(vehicleId=vehicle_id, distanceMeters=float(res["lead"]["x"]),
                                method="openpilot_lead", confidence=p)

    def match_lead_to_boxes(self, res: dict[str, Any], boxes_xyxy: np.ndarray, max_px: float = 60.0
                            ) -> Optional[int]:
        """Index of the detection box whose bottom-centre is nearest the projected lead point."""
        pr = self.project_result(res)
        if pr["lead_uv"] is None or len(boxes_xyxy) == 0:
            return None
        u, v = pr["lead_uv"]
        b = np.asarray(boxes_xyxy, dtype=np.float64)
        inside = (b[:, 0] - 10 <= u) & (u <= b[:, 2] + 10)
        d = np.hypot(np.clip(u, b[:, 0], b[:, 2]) - u, b[:, 3] - v)
        d[~inside] += 1e3
        j = int(np.argmin(d))
        return j if d[j] <= max_px else None

    # ------------------------------------------------------------------ self calibration
    def self_calibrate(self, pairs: Any, passes: int = 3, min_speed_mps: float = MIN_SPEED_FILTER,
                       max_yaw_rate: float = MAX_YAW_RATE_FILTER, min_samples: int = 5,
                       verbose: bool = False) -> dict[str, Any]:
        """calibrationd.py-style pitch/yaw estimate from the model's own ego-motion direction.

        pairs: callable returning a fresh iterable of (frame_t, frame_t_minus_0p2s) BGR pairs (called once
               per pass), or a list of such pairs. A few seconds of mostly straight driving is enough.
        Each pass: run the model with the current rpy, keep samples with speed > min_speed_mps and
        |yaw rate| < max_yaw_rate, observed = (0, -atan2(tz, tx), atan2(ty, tx)) and
        rpy <- euler(R(rpy) @ R(median observed)); roll stays 0. Also returns the model's camera-height
        estimate (road_transform trans z, as calibrationd does). NOTE: the model's speed scales with the
        assumed focal length, so min_speed_mps is compared against a focal-dependent speed.
        """
        get = pairs if callable(pairs) else (lambda: pairs)
        hist: list[dict[str, Any]] = []
        for p in range(passes):
            obs, heights, speeds = [], [], []
            for cur, prev in get():
                r = self.infer(cur, prev)
                tr, rot = r["pose"]["trans"], r["pose"]["rot"]
                speeds.append(float(tr[0]))
                heights.append(float(r["road_transform"]["trans"][2]))
                if tr[0] > min_speed_mps and abs(rot[2]) < max_yaw_rate:
                    obs.append([0.0, -np.arctan2(tr[2], tr[0]), np.arctan2(tr[1], tr[0])])
            if len(obs) < min_samples:
                hist.append({"pass": p, "n_used": len(obs), "n_total": len(speeds), "rpy": self.rpy.tolist(),
                             "median_speed": float(np.median(speeds)) if speeds else None,
                             "note": "too few straight-and-fast samples; calibration unchanged"})
                break
            o = np.median(np.asarray(obs), 0)
            new = G.euler_from_rot(G.rot_from_euler(self.rpy) @ G.rot_from_euler(o))
            new[0] = 0.0
            new[1] = np.clip(new[1], PITCH_LIMITS[0] - 0.1, PITCH_LIMITS[1] + 0.1)
            new[2] = np.clip(new[2], YAW_LIMITS[0] - 0.1, YAW_LIMITS[1] + 0.1)
            hist.append({"pass": p, "n_used": len(obs), "n_total": len(speeds), "observed_rpy": o.tolist(),
                         "rpy_before": self.rpy.tolist(), "rpy_after": new.tolist(),
                         "median_speed": float(np.median(speeds)), "median_height": float(np.median(heights))})
            if verbose:
                print(hist[-1])
            self.set_calibration(rpy=new)
        ok = bool(hist) and "rpy_after" in hist[0]
        return {"ok": ok, "rpy": self.rpy.tolist(), "vp": self.vanishing_point.tolist(), "history": hist,
                "height_m": next((h["median_height"] for h in reversed(hist) if "median_height" in h), None)}


def lane_topology(res: dict[str, Any], min_prob: float = 0.5, x_eval: float = 10.0) -> dict[str, Any]:
    """Heuristic currentLane / laneCount from openpilot's lines and road edges at x_eval metres.

    Lanes to the left = 1 if the left-left line is confident, plus extra lanes that fit between it and
    the left road edge (edge gap / lane width, floored); same on the right. Without confident ego lines
    returns None values. This is a heuristic on top of openpilot, not an openpilot output."""
    ll, pr, re = res["lane_lines"], res["lane_probs"], res["road_edges"]
    yl = {i: float(np.interp(x_eval, G.X_IDXS, ll[i, :, 1])) for i in range(4)}
    ye = [float(np.interp(x_eval, G.X_IDXS, re[i, :, 1])) for i in range(2)]
    if pr[1] < min_prob or pr[2] < min_prob:
        return {"current_lane": None, "lane_count": None, "lane_width_m": None, "y": yl, "edges": ye}
    width = yl[2] - yl[1]
    if not (2.2 < width < 5.5):
        return {"current_lane": None, "lane_count": None, "lane_width_m": width, "y": yl, "edges": ye}
    left = right = 0
    if pr[0] >= min_prob and (yl[1] - yl[0]) > 0.6 * width:
        left = 1 + max(0, int((yl[0] - ye[0]) / width + 0.25) if ye[0] < yl[0] else 0)
    elif ye[0] < yl[1] - 1.5 * width:  # edge far away but outer line not seen
        left = int((yl[1] - ye[0]) / width + 0.25)
    if pr[3] >= min_prob and (yl[3] - yl[2]) > 0.6 * width:
        right = 1 + max(0, int((ye[1] - yl[3]) / width + 0.25) if ye[1] > yl[3] else 0)
    elif ye[1] > yl[2] + 1.5 * width:
        right = int((ye[1] - yl[2]) / width + 0.25)
    return {"current_lane": left + 1, "lane_count": left + 1 + right, "lane_width_m": width,
            "y": yl, "edges": ye}


def iter_video_pairs(path: str | Path, stride_s: float = 0.5, start_s: float = 0.4, end_s: float = 1e9,
                     gap_s: float = G.FRAME_GAP_S):
    """Yield (frame_t, frame_t_minus_gap) BGR pairs from a video, clocked by frame index / fps.
    Skips the first 0.4 s (irregular pts in BDD .mov files)."""
    from perception.common.video import VideoFileInput
    v = VideoFileInput(path)
    gap = max(1, int(round(gap_s * v.fps)))
    stride = max(1, int(round(stride_s * v.fps)))
    first = int(np.ceil(start_s * v.fps))
    hist: deque = deque(maxlen=gap + 1)
    try:
        for fr in v:
            hist.append(fr.image)
            i = fr.index
            if i / v.fps > end_s:
                break
            if i >= first + gap and (i - first) % stride == 0 and len(hist) == gap + 1:
                yield hist[-1], hist[0]
    finally:
        v.release()


class OpenpilotStream:
    """Feed frames one at a time; runs the model whenever a frame ~0.2 s older is buffered.

    Keeps PreparedFrames (warped + packed) so each video frame is warped once.
    run_every_s: model cadence (openpilot runs at 20 Hz = 0.05 s; 0.1 s is plenty for the demo)."""

    def __init__(self, model: OpenpilotVision, run_every_s: float = 0.05, max_age_s: float = 0.6):
        self.model = model
        self.run_every_s = run_every_s
        self.max_age_s = max_age_s
        self.buf: deque[PreparedFrame] = deque()
        self._last_run = -1e9

    def reset(self) -> None:
        self.buf.clear()
        self._last_run = -1e9

    def push(self, frame_bgr: np.ndarray, pts_s: float) -> Optional[dict[str, Any]]:
        """pts_s: frame time in seconds. NOTE: perception.common.video.VideoFileInput reports the pts of
        the PREVIOUS frame (CAP_PROP_POS_MSEC read before read()) and BDD .mov files have irregular pts
        for their first ~8 frames; index / fps is a safer clock for BDD clips."""
        pf = self.model.prepare(frame_bgr, pts_s)
        self.buf.append(pf)
        while self.buf and pts_s - self.buf[0].pts_s > self.max_age_s:
            self.buf.popleft()
        if pts_s - self._last_run + 1e-6 < self.run_every_s:
            return None
        target = pts_s - G.FRAME_GAP_S
        best = min(self.buf, key=lambda f: abs(f.pts_s - target))
        if abs(best.pts_s - target) > 0.034:
            return None
        self._last_run = pts_s
        res = self.model.infer_prepared(best, pf)
        res["pts_s"] = pts_s
        res["prev_pts_s"] = best.pts_s
        return res
