"""Traffic-light state (plan section 11): RED / YELLOW / GREEN / UNKNOWN + coarse distance.

Stage 1 (boxes) comes from the shared BDD100K detector. This module is the crop
stage, plus per-track temporal smoothing and a size-prior distance.

    from perception.traffic.lights import TrafficLightClassifier
    clf = TrafficLightClassifier(backend="hsv")          # or "autoware_onnx"
    states = clf.classify(frame_bgr, light_detections, pts_s=frame.pts_s)

Each returned `TrafficLightState` has `state` (temporally smoothed when the
detection has a track id), `confidence`, `bbox` and `distanceMeters`
(size prior; coarse, see README). Safety (plan section 38): informational only.
UNKNOWN means unknown. Never read GREEN as permission to go.

Backends:
  hsv            OpenCV rules: hue votes of saturated, bright pixels plus the lit
                 lamp's position along the housing (vertical: top=red; horizontal
                 US: left=red). CPU, sub-millisecond per crop.
  autoware_onnx  AutowareFoundation/traffic_light_classifier v4.0 MobileNetV2 ONNX
                 (Apache-2.0, trained on Japanese lights). 224x224 top-left
                 letterbox, RGB mean/std. By default (orient='both') it averages the crop
                 as-is with a copy rotated/mirrored so the US red lamp sits where
                 Japanese heads put it (on the right).
                 Runs on the ORT CUDA EP ('import torch' must happen first; done here).
"""
from __future__ import annotations

import math
import time
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable, Optional, Sequence

import cv2
import numpy as np

from perception.common.schemas import Detection, TrafficLightState
from perception.common.video import MODELS_ROOT

STATES = ("RED", "YELLOW", "GREEN", "UNKNOWN")
R, Y, G, U = range(4)

AUTOWARE_REPO = "AutowareFoundation/traffic_light_classifier"
AUTOWARE_REV = "v4.0"
AUTOWARE_LABELS = ["green", "left,red", "left,red,straight", "red", "red,right", "red,straight",
                   "unknown", "yellow", "red,up_left", "red,right,straight", "red,up_right"]
AUTOWARE_MEAN = np.array([123.675, 116.28, 103.53], np.float32)
AUTOWARE_STD = np.array([58.395, 57.12, 57.375], np.float32)

# Size prior. A US 3-section 12-inch head is about 1.07 m tall (42 in) without a
# backplate, and its housing is about 0.36 m (14 in) across. BDD boxes usually hug
# the housing. f_px is an ASSUMPTION: BDD100K publishes no intrinsics. 1000 px at
# 1280 wide is about a 65 degree HFOV.
HEAD_LONG_M = 1.07
HEAD_SHORT_M = 0.36
DEFAULT_FOCAL_PX = 1000.0


# --------------------------------------------------------------------------- HSV
@dataclass
class HSVParams:
    """OpenCV HSV ranges (H 0-179). Defaults tuned on the val_lights DEV split (tune_hsv.py)."""
    s_min: int = 40            # min saturation for a 'colored' pixel
    v_min: int = 160           # min value for a 'colored' pixel
    v_rel: float = 0.7         # colored pixels must also have V >= v_rel * max(V) (lamps are emissive)
    red_hi: int = 7            # red: H <= red_hi or H >= red_lo2
    red_lo2: int = 150
    yel_lo: int = 14           # (red_hi, yel_lo) = amber band, split between red/yellow by lamp position
    yel_hi: int = 34
    grn_lo: int = 55
    grn_hi: int = 95
    min_color: float = 0.01    # colored-pixel mass (fraction of crop) below which -> UNKNOWN
    pos_weight: float = 0.5    # exponent on the lamp-position likelihood
    pos_sigma: float = 0.3    # width of the position likelihood (fraction of long axis)
    min_contrast: float = 30.0  # oriented heads: brightest lamp slot minus dimmest slot (mean V) below this -> UNKNOWN
    aspect_oriented: float = 1.3  # long/short ratio above which a housing counts as vertical/horizontal


def _orientation(h: float, w: float, thr: float) -> str:
    if h >= thr * w:
        return "v"
    if w >= thr * h:
        return "h"
    return "s"


def hsv_probs(crop_bgr: np.ndarray, p: HSVParams = HSVParams()) -> np.ndarray:
    """Return probabilities over (RED, YELLOW, GREEN, UNKNOWN) for one tight light crop."""
    h, w = crop_bgr.shape[:2]
    out = np.array([0.0, 0.0, 0.0, 1.0])
    if h < 2 or w < 2:
        return out
    orient = _orientation(h, w, p.aspect_oriented)
    size = {"v": (16, 40), "h": (40, 16), "s": (28, 28)}[orient]
    interp = cv2.INTER_AREA if (w > size[0] and h > size[1]) else cv2.INTER_LINEAR
    img = cv2.resize(crop_bgr, size, interpolation=interp)
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    H = hsv[..., 0].astype(np.int16)
    S = hsv[..., 1].astype(np.float32)
    V = hsv[..., 2].astype(np.float32)
    colored = (S >= p.s_min) & (V >= max(float(p.v_min), p.v_rel * float(V.max())))
    wpix = (S / 255.0) * (V / 255.0) * colored
    red_m = (H <= p.red_hi) | (H >= p.red_lo2)
    amb_m = (H > p.red_hi) & (H < p.yel_lo)
    yel_m = (H >= p.yel_lo) & (H <= p.yel_hi)
    grn_m = (H >= p.grn_lo) & (H <= p.grn_hi)
    n = float(wpix.size)
    s_r, s_a, s_y, s_g = (float((wpix * m).sum()) / n for m in (red_m, amb_m, yel_m, grn_m))
    total = s_r + s_a + s_y + s_g

    # Lamp position along the long axis (0 = top/left). Lit = bright pixels (white
    # night cores included). Weighted by V so the lamp outweighs the housing.
    pos_like = np.ones(3)
    pos = None
    if orient != "s":
        vmax = float(V.max())
        lit = V >= max(120.0, 0.75 * vmax)
        if lit.any() and vmax > 0:
            axis = 0 if orient == "v" else 1  # coordinate along rows (v) or columns (h)
            coords = np.indices(V.shape)[axis].astype(np.float32)
            L = V.shape[axis]
            wt = V * lit
            pos = float((coords * wt).sum() / max(wt.sum(), 1e-6) + 0.5) / L
            centers = np.array([1 / 6, 1 / 2, 5 / 6])  # red, yellow, green (vertical and US horizontal)
            pos_like = np.exp(-0.5 * ((pos - centers) / p.pos_sigma) ** 2) + 1e-3
            pos_like = pos_like / pos_like.max()

    # Lamp-slot contrast: a lit lamp is much brighter than the other two slots; a
    # sunlit (often yellow-painted) unlit housing is evenly bright.
    if orient != "s" and p.min_contrast > 0:
        slots = np.array_split(V, 3, axis=0 if orient == "v" else 1)
        means = np.array([float(sl.mean()) for sl in slots])
        if means.max() - means.min() < p.min_contrast:
            total = min(total, 0.5 * p.min_color)

    if total < p.min_color:
        conf_unknown = 1.0 - 0.5 * total / p.min_color
        rest = (1.0 - conf_unknown)
        if total > 0:
            vec = np.array([s_r + 0.5 * s_a, s_y + 0.5 * s_a, s_g]) + 1e-9
            vec = vec / vec.sum() * rest
        else:
            vec = np.full(3, rest / 3)
        return np.array([vec[0], vec[1], vec[2], conf_unknown])

    # Amber pixels are split by lamp position (daytime red LEDs often look orange).
    if pos is not None:
        red_share = float(pos_like[0] / (pos_like[0] + pos_like[1]))
    else:
        red_share = 0.5
    color = np.array([s_r + red_share * s_a, s_y + (1 - red_share) * s_a, s_g]) + 1e-6
    color = color / color.sum()
    comb = color * (pos_like ** p.pos_weight)
    comb = comb / comb.sum()
    # Confidence mass: saturates as the colored-pixel mass grows.
    known = min(1.0, total / (2.0 * p.min_color))
    known = 0.5 + 0.5 * known
    return np.array([comb[0] * known, comb[1] * known, comb[2] * known, 1.0 - known])


# ---------------------------------------------------------------------- Autoware
def autoware_u8_model() -> Path:
    """Derived copy of the HF batch_1 ONNX: dynamic batch 'N', uint8 NHWC RGB input.

    The Autoware normalization (Cast -> Sub mean -> Mul 1/std -> Transpose to NCHW) is
    prepended as ONNX nodes, and the weights are untouched. Why: the published files have
    a fixed batch (1/4/6), and on this Windows box each ORT call is dispatch-bound
    (3-5 ms), while numpy normalization of 10 crops took about 4.5 ms. One uint8 call per
    frame is faster. Written to models/traffic/ on first use.
    """
    out = MODELS_ROOT / "traffic" / "traffic_light_classifier_mobilenetv2_u8nhwc_dynbatch.onnx"
    if out.exists():
        return out
    import onnx
    from huggingface_hub import hf_hub_download
    from onnx import TensorProto, helper, numpy_helper
    src = hf_hub_download(AUTOWARE_REPO, "traffic_light_classifier_mobilenetv2_batch_1.onnx", revision=AUTOWARE_REV)
    m = onnx.load(src)
    g = m.graph
    orig_in = g.input[0].name
    g.initializer.extend([numpy_helper.from_array(AUTOWARE_MEAN.reshape(1, 1, 1, 3), "pre_mean"),
                          numpy_helper.from_array((1.0 / AUTOWARE_STD).reshape(1, 1, 1, 3).astype(np.float32), "pre_invstd")])
    pre = [helper.make_node("Cast", ["input_u8"], ["pre_f"], to=TensorProto.FLOAT),
           helper.make_node("Sub", ["pre_f", "pre_mean"], ["pre_s"]),
           helper.make_node("Mul", ["pre_s", "pre_invstd"], ["pre_n"]),
           helper.make_node("Transpose", ["pre_n"], [orig_in], perm=[0, 3, 1, 2])]
    del g.input[0]
    g.input.insert(0, helper.make_tensor_value_info("input_u8", TensorProto.UINT8, ["N", 224, 224, 3]))
    for n in reversed(pre):
        g.node.insert(0, n)
    g.output[0].type.tensor_type.shape.dim[0].dim_param = "N"
    onnx.checker.check_model(m)
    out.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(m, str(out))
    return out


class AutowareLightClassifier:
    """AutowareFoundation/traffic_light_classifier v4.0 (car MobileNetV2), via onnxruntime."""

    def __init__(self, device: str = "cuda", orient: str = "both", exposure_gate: bool = True,
                 model_path: Optional[str] = None, cudnn_algo: Optional[str] = None, bucket: bool = True):
        import torch  # noqa: F401  (puts torch/lib CUDA DLLs on the path for ORT's CUDA EP)
        import onnxruntime as ort

        model_path = model_path or autoware_u8_model()
        # cudnn_algo: None = ORT default (EXHAUSTIVE: autotunes once per new input shape, hence
        # the batch buckets below) | "HEURISTIC" | "DEFAULT". See bench_traffic.py.
        cuda_opts = {"cudnn_conv_algo_search": cudnn_algo} if cudnn_algo else {}
        providers = ([("CUDAExecutionProvider", cuda_opts), "CPUExecutionProvider"]
                     if device.startswith("cuda") else ["CPUExecutionProvider"])
        self.bucket = bucket
        so = ort.SessionOptions()
        so.log_severity_level = 3
        self.sess = ort.InferenceSession(str(model_path), so, providers=providers)
        self.providers = self.sess.get_providers()
        self.inp = self.sess.get_inputs()[0].name
        b = self.sess.get_inputs()[0].shape[0]
        self.batch = int(b) if isinstance(b, int) else 32  # dynamic: cap per call
        self.u8_input = self.sess.get_inputs()[0].type == "tensor(uint8)"
        self.orient = orient
        self.exposure_gate = exposure_gate
        self.model_path = str(model_path)
        self._mean = AUTOWARE_MEAN.reshape(1, 1, 1, 3)
        self._inv_std = (1.0 / AUTOWARE_STD).reshape(1, 1, 1, 3)
        # Map 11 Autoware labels -> (R, Y, G, U). All arrow combos contain a red lamp.
        self.map = np.zeros((len(AUTOWARE_LABELS), 4), np.float32)
        for i, lab in enumerate(AUTOWARE_LABELS):
            j = {"green": G, "yellow": Y, "unknown": U}.get(lab, R)
            self.map[i, j] = 1.0

    def _prep_u8(self, crop_bgr: np.ndarray, orient: Optional[str] = None) -> np.ndarray:
        """uint8 224x224 RGB canvas: optional US->JP re-orientation, then top-left letterbox (Autoware)."""
        orient = orient or self.orient
        img = cv2.cvtColor(crop_bgr, cv2.COLOR_BGR2RGB)
        h, w = img.shape[:2]
        if orient == "auto":
            o = _orientation(h, w, 1.3)
            if o == "v":    # US vertical: red on top -> rotate clockwise so red is on the right (JP layout)
                img = cv2.rotate(img, cv2.ROTATE_90_CLOCKWISE)
            elif o == "h":  # US horizontal: red on the left -> mirror so red is on the right
                img = cv2.flip(img, 1)
        elif orient == "rot90":
            if h > w:
                img = cv2.rotate(img, cv2.ROTATE_90_CLOCKWISE)
        h, w = img.shape[:2]
        s = min(224.0 / w, 224.0 / h)
        nw, nh = max(1, int(w * s)), max(1, int(h * s))
        img = cv2.resize(img, (nw, nh), interpolation=cv2.INTER_LINEAR)
        canvas = np.zeros((224, 224, 3), np.uint8)  # Autoware pads bottom/right with 0
        canvas[:nh, :nw] = img
        return canvas

    def _prep(self, crop_bgr: np.ndarray, orient: Optional[str] = None) -> np.ndarray:
        x = (self._prep_u8(crop_bgr, orient)[None].astype(np.float32) - self._mean) * self._inv_std
        return x[0].transpose(2, 0, 1)

    @staticmethod
    def brightness(crop_bgr: np.ndarray) -> float:
        y = cv2.cvtColor(crop_bgr, cv2.COLOR_BGR2YCrCb)[..., 0].mean()
        return (float(y) - 112.5) / 112.5

    def probs(self, crops: Sequence[np.ndarray]) -> np.ndarray:
        if not crops:
            return np.zeros((0, 4), np.float32)
        # orient='both': test-time average of the as-is crop and the US->JP re-oriented crop
        views = ["none", "auto"] if self.orient == "both" else [self.orient]
        u8 = np.stack([self._prep_u8(c, v) for v in views for c in crops])
        if self.u8_input:   # normalization happens inside the graph
            xs = u8
        else:               # original float NCHW exports
            xs = np.ascontiguousarray(((u8.astype(np.float32) - self._mean) * self._inv_std).transpose(0, 3, 1, 2))
        outs = []
        for i in range(0, len(xs), self.batch):
            chunk = xs[i:i + self.batch]
            k = len(chunk)
            fixed = isinstance(self.sess.get_inputs()[0].shape[0], int)
            # fixed-batch export: pad to its batch; dynamic: pad to a bucket so few shapes are ever seen
            target = self.batch if fixed else (next(b for b in (2, 4, 8, 16, 32) if b >= k) if self.bucket else k)
            if k < target:
                chunk = np.concatenate([chunk, np.zeros((target - k, *chunk.shape[1:]), chunk.dtype)])
            outs.append(self.sess.run(None, {self.inp: chunk})[0][:k])
        p11 = np.concatenate(outs).reshape(len(views), len(crops), -1).mean(0)
        p4 = p11 @ self.map
        if self.exposure_gate:  # Autoware's over/under-exposure rule (thresholds 0.85 / -0.83)
            for i, c in enumerate(crops):
                b = self.brightness(c)
                if b >= 0.85 or b <= -0.83:
                    p4[i] = [0, 0, 0, 1]
        return p4


# ------------------------------------------------------------ temporal smoothing
@dataclass
class _TrackMem:
    belief: np.ndarray
    last_t: float
    votes: deque = field(default_factory=lambda: deque(maxlen=64))
    announced: int = U
    n_obs: int = 0


class TemporalSmoother:
    """Per-track smoothing of light-state probabilities.

    mode='hmm'  forward filter over 4 hidden states. P(stay) = exp(-dt / tau_s), and
                observations are tempered toward uniform (obs_eps), so one glare or
                PWM-flicker frame cannot flip the state. UNKNOWN observations carry
                little evidence, so a lit color persists through brief washouts.
    mode='vote' confidence-weighted majority over the last window_s seconds, with
                hysteresis: switch only when the new state wins min_switch times in a row.
    mode='none' pass-through.
    """

    def __init__(self, mode: str = "hmm", window_s: float = 0.4, tau_s: float = 0.35,
                 obs_eps: float = 0.25, unknown_obs_weight: float = 0.35, min_switch: int = 2,
                 forget_s: float = 2.0):
        self.mode = mode
        self.window_s = window_s
        self.tau_s = tau_s
        self.obs_eps = obs_eps
        self.unknown_obs_weight = unknown_obs_weight
        self.min_switch = min_switch
        self.forget_s = forget_s
        self.tracks: dict[int, _TrackMem] = {}
        self._pending: dict[int, tuple[int, int]] = {}

    def reset(self) -> None:
        self.tracks.clear()
        self._pending.clear()

    def update(self, tid: Optional[int], probs: np.ndarray, t: float) -> tuple[int, float]:
        probs = np.asarray(probs, np.float64)
        if tid is None or self.mode == "none":
            k = int(np.argmax(probs))
            return k, float(probs[k])
        mem = self.tracks.get(tid)
        if mem is None:
            mem = _TrackMem(belief=np.full(4, 0.25), last_t=t)
            self.tracks[tid] = mem
        dt = max(1e-3, t - mem.last_t) if mem.n_obs else 1.0
        mem.last_t = t
        mem.n_obs += 1
        if self.mode == "hmm":
            stay = math.exp(-dt / self.tau_s) if mem.n_obs > 1 else 0.0
            stay = max(stay, 0.0)
            prior = stay * mem.belief + (1 - stay) * np.full(4, 0.25)
            # An UNKNOWN reading is weak evidence: flatten it toward uniform.
            obs = probs.copy()
            if int(np.argmax(obs)) == U:
                obs = self.unknown_obs_weight * obs + (1 - self.unknown_obs_weight) * np.full(4, 0.25)
            like = (1 - self.obs_eps) * obs + self.obs_eps * 0.25
            post = prior * like
            mem.belief = post / post.sum()
            k = int(np.argmax(mem.belief))
            return k, float(mem.belief[k])
        # vote
        mem.votes.append((t, probs))
        while mem.votes and t - mem.votes[0][0] > self.window_s:
            mem.votes.popleft()
        acc = np.zeros(4)
        for _, pv in mem.votes:
            acc[int(np.argmax(pv))] += float(np.max(pv))
        winner = int(np.argmax(acc))
        conf = float(acc[winner] / max(acc.sum(), 1e-9))
        if mem.n_obs == 1:
            mem.announced = winner
        elif winner != mem.announced:
            cand, cnt = self._pending.get(tid, (winner, 0))
            cnt = cnt + 1 if cand == winner else 1
            self._pending[tid] = (winner, cnt)
            if cnt >= self.min_switch:
                mem.announced = winner
                self._pending.pop(tid, None)
        else:
            self._pending.pop(tid, None)
        return mem.announced, conf

    def prune(self, t: float) -> None:
        for tid in [k for k, m in self.tracks.items() if t - m.last_t > self.forget_s]:
            self.tracks.pop(tid, None)
            self._pending.pop(tid, None)


# ---------------------------------------------------------------------- distance
def light_distance_m(bbox: Sequence[float], focal_px: float = DEFAULT_FOCAL_PX) -> Optional[float]:
    """Pinhole size prior: Z = f * H_real / h_px, using the long side when the box looks like a full head."""
    x1, y1, x2, y2 = bbox[:4]
    w, h = max(1e-3, x2 - x1), max(1e-3, y2 - y1)
    long_px, short_px = max(w, h), min(w, h)
    if long_px < 3:
        return None
    if long_px / short_px >= 2.2:  # full 3-section head visible
        z = focal_px * HEAD_LONG_M / long_px
    else:                          # partial, single lamp or doghouse: use the housing width
        z = focal_px * HEAD_SHORT_M / short_px
    return float(z)


def distance_bin(z: Optional[float]) -> str:
    if z is None:
        return "?"
    return "<30m" if z < 30 else "30-60m" if z < 60 else "60-100m" if z < 100 else ">100m"


# ------------------------------------------------------------------------ public
def _as_box_and_id(d: Any) -> tuple[list[float], Optional[int], float]:
    if isinstance(d, Detection):
        return list(d.bbox), d.id, float(d.confidence)
    if isinstance(d, dict):
        return list(d["bbox"]), d.get("id"), float(d.get("confidence", 1.0))
    return list(d[:4]), None, 1.0


def crop_box(frame: np.ndarray, bbox: Sequence[float], pad: float = 0.0) -> Optional[np.ndarray]:
    H, W = frame.shape[:2]
    x1, y1, x2, y2 = bbox[:4]
    bw, bh = x2 - x1, y2 - y1
    x1, x2 = x1 - pad * bw, x2 + pad * bw
    y1, y2 = y1 - pad * bh, y2 + pad * bh
    xi1, yi1 = max(0, int(math.floor(x1))), max(0, int(math.floor(y1)))
    xi2, yi2 = min(W, int(math.ceil(x2)) + 1), min(H, int(math.ceil(y2)) + 1)
    if xi2 - xi1 < 2 or yi2 - yi1 < 2:
        return None
    return frame[yi1:yi2, xi1:xi2]


class TrafficLightClassifier:
    """Crop classifier + per-track temporal smoothing + size-prior distance.

    Args:
      backend: 'hsv' | 'autoware_onnx'
      device: 'cuda' | 'cpu' (autoware_onnx only; hsv is always CPU)
      smoothing: 'hmm' (default) | 'vote' | 'none'; applied per detection.id
      min_box_h: boxes whose short side is below this (px) are returned as UNKNOWN. Default 4 matches
                 the GT-crop eval protocol (review fix: it was 6, which turned 24 of 457 lit TEST
                 lights, all classified correctly, into UNKNOWN: API lit acc 0.818 vs 0.867)
      focal_px: assumed focal length for the size prior (BDD: none published)
      hsv_params: HSVParams override
      autoware_orient: 'both' (default: average as-is + US->JP re-oriented view) | 'auto'
                       (rotate vertical heads 90 deg cw, mirror horizontal ones) | 'none' | 'rot90'
      exposure_gate: Autoware's backlight rule -> UNKNOWN
      fps: used for timestamps when classify() gets no pts_s
      track_stride: classify a tracked light only every N-th frame (staggered by id); in between
                    the smoothed state is reused. 1 = every frame. Untracked boxes: every frame.
    """

    def __init__(self, backend: str = "hsv", device: str = "cuda", smoothing: str = "hmm",
                 min_box_h: float = 4.0, focal_px: float = DEFAULT_FOCAL_PX,
                 hsv_params: Optional[HSVParams] = None, autoware_orient: str = "both",
                 exposure_gate: bool = True, fps: float = 30.0, track_stride: int = 1, **smoother_opts):
        self.backend = backend
        self.min_box_h = min_box_h
        self.focal_px = focal_px
        self.fps = fps
        self.hsv_params = hsv_params or HSVParams()
        self._aw: Optional[AutowareLightClassifier] = None
        if backend == "autoware_onnx":
            self._aw = AutowareLightClassifier(device=device, orient=autoware_orient, exposure_gate=exposure_gate)
        elif backend != "hsv":
            raise ValueError(f"unknown backend {backend!r}")
        self.smoother = TemporalSmoother(mode=smoothing, **smoother_opts)
        self.track_stride = max(1, int(track_stride))
        self._last_out: dict[int, tuple[int, float]] = {}
        self._frame_i = 0
        self.last_timing_ms: float = 0.0

    # raw per-crop probabilities (R, Y, G, U)
    def probs(self, crops: Sequence[np.ndarray]) -> np.ndarray:
        if not crops:
            return np.zeros((0, 4))
        if self._aw is not None:
            return self._aw.probs(crops)
        return np.stack([hsv_probs(c, self.hsv_params) for c in crops])

    def classify(self, frame_bgr: np.ndarray, light_detections: Iterable[Any],
                 pts_s: Optional[float] = None) -> list[TrafficLightState]:
        """light_detections: Detection objects (id = track id), dicts with 'bbox', or [x1,y1,x2,y2]."""
        t0 = time.perf_counter()
        t = pts_s if pts_s is not None else self._frame_i / self.fps
        self._frame_i += 1
        items, crops = [], []
        for d in light_detections:
            if isinstance(d, Detection) and d.cls not in ("traffic light",):
                continue
            box, tid, det_conf = _as_box_and_id(d)
            short = min(box[2] - box[0], box[3] - box[1])
            skip = (tid is not None and self.track_stride > 1 and tid in self._last_out
                    and (self._frame_i + tid) % self.track_stride != 0)
            crop = crop_box(frame_bgr, box) if (short >= self.min_box_h and not skip) else None
            items.append((box, tid, det_conf, crop, skip))
            if crop is not None:
                crops.append(crop)
        P = self.probs(crops)
        out, j = [], 0
        for box, tid, det_conf, crop, skip in items:
            if skip:
                k, conf = self._last_out[tid]
            else:
                if crop is None:
                    p = np.array([0, 0, 0, 1.0])
                else:
                    p = P[j]
                    j += 1
                k, conf = self.smoother.update(tid, p, t)
                if tid is not None:
                    self._last_out[tid] = (k, conf)
            out.append(TrafficLightState(id=tid, state=STATES[k], bbox=[float(v) for v in box],
                                         confidence=round(float(conf), 3),
                                         distanceMeters=(round(z, 1) if (z := light_distance_m(box, self.focal_px)) else None)))
        self.smoother.prune(t)
        for tid in [k for k in self._last_out if k not in self.smoother.tracks]:
            self._last_out.pop(tid, None)
        self.last_timing_ms = (time.perf_counter() - t0) * 1000.0
        return out

    @staticmethod
    def primary(lights: Sequence[TrafficLightState], frame_w: int = 1280,
                center_sigma: float = 0.22) -> Optional[TrafficLightState]:
        """Heuristic 'which light applies to me' for the HUD / Driving Context Engine.

        Among non-UNKNOWN lights, weight = box height x Gaussian(centrality, sigma = center_sigma
        x frame width), then take a weighted majority vote on the state across heads (heads of one
        approach normally agree). Returns the highest-weight head of the winning state, with its
        confidence scaled by the vote share. No lane or map information yet (plan section 11 relevance
        is a TODO for integration with the lane block).
        """
        cand = [l for l in lights if l.state != "UNKNOWN"]
        if not cand:
            return None
        def weight(l):
            x1, y1, x2, y2 = l.bbox
            cx = 0.5 * (x1 + x2) / frame_w - 0.5
            return max(1.0, y2 - y1) * math.exp(-0.5 * (cx / center_sigma) ** 2) * l.confidence
        votes: dict[str, float] = {}
        for l in cand:
            votes[l.state] = votes.get(l.state, 0.0) + weight(l)
        win = max(votes, key=votes.get)
        best = max((l for l in cand if l.state == win), key=weight)
        share = votes[win] / sum(votes.values())
        return TrafficLightState(id=best.id, state=best.state, bbox=best.bbox,
                                 confidence=round(best.confidence * share, 3), distanceMeters=best.distanceMeters)

    def reset(self) -> None:
        self.smoother.reset()
        self._last_out.clear()
        self._frame_i = 0
