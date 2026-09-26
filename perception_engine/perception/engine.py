"""PerceptionEngine: loads the perception blocks once and runs a deterministic per-frame schedule.

Knuckle Sandwich Robotics Inc. (KSR) AI Spatial Driving Copilot, HackGT 13 prototype.

    from perception.engine import PerceptionEngine
    from perception.common.video import VideoFileInput
    eng = PerceptionEngine("perception/config_realtime.yaml")      # or a dict, or None for the defaults
    for frame in VideoFileInput(path):
        result = eng.step(frame)          # schemas.FrameResult, in memory (no files)
        meta = eng.last_meta              # blockAges, per-track depth details, camera, ran blocks
    # -> perception.realtime.wire.to_wire(result, meta) gives the contracts/ PerceptionFrame dict

Schedule (plan section 9-13 blocks at different rates, driven by the analysed-step counter k, so it is
deterministic for a given frame sequence): a block with `every: N, phase: P` runs on step k when
k % N == P % N. Between runs its last output is carried forward and `blockAges[block]` counts the steps
since it ran. Detection, tracking and traffic-light state run on every step. Depth uses the depth block's
own `depth_every` counter (network on k % N == 0, geometry-only fusion + per-track Kalman in between).

Two lanes (protocol v2, results in two waves; perception/realtime/pipeline.py runs the threads):
    FAST lane  (one thread)  fast_step(frame)  -> wave 1: detection + tracking + traffic-light state, plus the
               latest known distance / lanes / road / signs carried forward (distanceAgeMs, blockAges). A vehicle or
               pedestrian without a (fresh enough) slow-lane distance gets a geometry-only one computed on the
               frame itself (size prior + flat ground, distanceMethod "geometry", distanceAgeMs 0).
    SLOW lane  (one thread)  slow_step(snapshot) -> wave 2: depth/distance for the snapshot's track ids, lanes +
               road, signs, on the `slow_schedule` (counted in slow-lane cycles); updates the shared state.
Every model object is owned by exactly one lane: detector, both trackers and the light classifier by the fast
lane; the depth estimator, lane detector, sign recognizer and segmenter by the slow lane. The only state both
lanes touch is `self.shared` (results, never models), guarded by its lock. A new stream (begin_session) bumps
`shared.generation`; the slow lane resets its own models when it sees a new generation and drops snapshots /
results of an older one.

All outputs are measurements for display (plan sections 18 and 38). Nothing here controls a vehicle and no
LLM is involved (section 16).
"""
from __future__ import annotations

import copy
import dataclasses
import math
import os
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional

os.environ.setdefault("YOLO_AUTOINSTALL", "False")

import numpy as np  # noqa: E402

from perception.common.schemas import (  # noqa: E402
    Detection, DistanceEstimate, FrameResult, LaneState, RoadGeometry, TrackState, TrafficLightState, TrafficSign,
    canonical_class)
from perception.common.video import PROJECT_ROOT, Frame  # noqa: E402

DEFAULT_CONFIG_PATH = PROJECT_ROOT / "perception" / "config_realtime.yaml"

# Track ids from the second (static-object) tracker for traffic lights and signs are offset so they can
# never collide with the vehicle/pedestrian tracker's ids (both trackers count from 1).
STATIC_ID_OFFSET = 1_000_000
MAIN_CLASSES = ("pedestrian", "rider", "car", "truck", "bus", "train", "motorcycle", "bicycle")
STATIC_CLASSES = ("traffic light", "traffic sign")

DEFAULTS: dict[str, Any] = {
    "target_hz": 16.0,            # expected analysed-frame rate (sizes the tracker buffers / class vote)
    "device": "cuda",
    "input": {"width": 1280, "height": 720},   # frames of another size are centre-cropped to 16:9 and resized
    "camera": {"focal_px": 700.0, "cam_height_m": None},   # focal at 1280 px width, scaled to the frame width
    "detection": {"weights": "bdd-yolo26s", "imgsz": 960, "conf": 0.25},
    "tracking": {"backend": "ul_botsort", "track_buffer_s": 2.0, "class_vote_s": 0.5, "opts": {}},
    "static_tracking": {"backend": "ul_bytetrack", "harmonised": False, "track_buffer_s": 1.0},
    "depth": {"enabled": True, "backend": "da3_metric_large", "every": 2, "backend_opts": {},
              "hood_freeze_after": 12},   # depth runs of 'auto' hood detection, then the hood row is frozen
    "lanes": {"enabled": True, "backend": "twinlitenetplus_large", "every": 2, "phase": 1},
    "lights": {"enabled": True, "backend": "autoware_onnx", "device": "cuda", "orient": "auto",
               "track_stride": 1, "smoothing": "hmm", "fixed_batch": 8},
    "signs": {"enabled": True, "backend": "lisa_crops", "every": 4, "phase": 1, "emit_unknown": False,
              "ocr": "pp_ocrv6_small", "ocr_device": "cpu"},
    "segmentation": {"enabled": False, "backend": "efficientvit_b1", "every": 3, "phase": 0},
    "openpilot": {"enabled": False},
    "warmup": True,
    # ---- two-lane realtime schedule (protocol v2). 'serial' = the single-thread step() schedule above.
    "lanes_mode": "two_lane",
    "fast": {"max_hz": None, "cuda_priority": -5,           # wave-1 rate cap (None = every frame it can get)
             "geometry_distance": True},                      # size prior + flat ground until a fused one exists
    "slow": {"max_hz": None, "cuda_priority": 0,              # wave-2 rate cap; low-priority CUDA stream
             "max_distance_age_s": 1.0},                      # wave 1 stops carrying a distance older than this
    "slow_schedule": {                                        # counted in slow-lane cycles
        "depth": {"every": 1},                                # depth network every n-th cycle (geometry between)
        "lanes": {"every": 1, "phase": 0},
        "signs": {"every": 2, "phase": 1},
        "segmentation": {"every": 3, "phase": 0},
    },
    "gil_switch_interval_ms": 1.0,                            # sys.setswitchinterval while the lanes run
}

# One-line summaries of the block READMEs / MODELS.md (engineering summary, not legal advice).
LICENCES = {
    "detection": "AGPL-3.0 (Ultralytics) + BDD100K non-commercial data terms: research/demo only",
    "tracking": "AGPL-3.0 (Ultralytics trackers)",
    "depth": {"da3_metric_large": "Apache-2.0 weights, but trained on Waymo Open (terms bar vehicle-assist use)",
              "da2_metric_outdoor_small": "Apache-2.0; lineage includes VKITTI2 (CC BY-NC-SA), BDD100K, SA-1B",
              "metric3d_vit_s_onnx": "BSD-2 code; weights licence unstated, trained on Waymo",
              None: "own geometry code (licence-clean)"},
    "lanes": {"twinlitenetplus_large": "BDD100K-trained weights: research/non-commercial only",
              "twinlitenetplus_medium": "BDD100K-trained weights: research/non-commercial only",
              "yolop_onnx": "BDD100K-trained weights: research/non-commercial only",
              "comma10k_segnet": "MIT on MIT data"},
    "lights": {"autoware_onnx": "Apache-2.0 (Autoware classifier)", "hsv": "own code (Apache-2.0)"},
    "signs": "AGPL-3.0 (Ultralytics) + LISA academic licence; PP-OCRv6 Apache-2.0",
    "segmentation": "see perception/segmentation/MODELS.md (Cityscapes-trained: non-commercial)",
}


GEOMETRY_METHOD = "geometry"      # wave-1 distanceMethod of the fast lane's geometry-only fallback
GEOMETRY_MAX_CONFIDENCE = 0.5     # geometry-only never claims more than this (fused DA3 distances go to 0.99)


def geometry_distance(cls: str, box, cam: Any, horizon_y: Optional[float], horizon_sigma_px: float,
                      hood_row: Optional[float] = None, max_m: float = 150.0
                      ) -> Optional[tuple[float, float, float]]:
    """Cheap per-object distance WITHOUT the depth network (fast lane, ~0.05 ms per box, no model state):
    class size prior (height, and width for near-rear views) + flat ground from the box bottom and a horizon row,
    fused inverse-variance in log space (same components / sigmas as perception.depth, minus depth_model and the
    per-track Kalman). `cam` is a perception.depth.geometry.Camera. Returns (metres, confidence, lateral m) or None.
    """
    from perception.depth import geometry as G
    x1, y1, x2, y2 = (float(v) for v in box[:4])
    comp: list[tuple[float, float]] = []
    bottom_ok = y2 < cam.height - 3 and (hood_row is None or y2 < hood_row - 2)
    if cls in G.GROUND_CLASSES and horizon_y is not None and bottom_ok and (y2 - horizon_y) > 2.0:
        zg = G.ground_distance(y2, horizon_y, cam)
        if zg is not None and 0.5 < zg < max_m:
            comp.append((zg, G.ground_sigma(zg, cam, 1.5 + 0.01 * (y2 - y1), horizon_sigma_px, 0.12)))
    for z, s in G.size_distances(cls, (x1, y1, x2, y2), cam, bottom_limit=hood_row).values():
        if 0.5 < z < max_m:
            comp.append((z, s))
    if not comp:
        return None
    lz = np.log([z for z, _ in comp])
    rel = np.array([max(1e-3, s / z) for z, s in comp])
    w = 1.0 / rel ** 2
    mean = float(np.sum(w * lz) / w.sum())
    srel = float(1.0 / math.sqrt(w.sum()))
    if len(comp) > 1:
        chi2 = float(np.sum(w * (lz - mean) ** 2)) / (len(comp) - 1)
        if chi2 > 1:
            srel *= math.sqrt(chi2)
    z = math.exp(mean)
    conf = float(np.clip(1.0 - srel / 0.30, 0.05, GEOMETRY_MAX_CONFIDENCE))
    lateral = ((x1 + x2) / 2.0 - cam.cx) * z / cam.focal_px
    return z, conf, lateral


def _deep_merge(base: dict, over: dict) -> dict:
    out = copy.deepcopy(base)
    for k, v in (over or {}).items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = _deep_merge(out[k], v)
        else:
            out[k] = copy.deepcopy(v)
    return out


def load_config(config: dict | str | os.PathLike | None = None) -> dict:
    """DEFAULTS deep-merged with a YAML file path or a dict (None = the repo's config_realtime.yaml if present)."""
    if config is None:
        config = DEFAULT_CONFIG_PATH if DEFAULT_CONFIG_PATH.exists() else {}
    if isinstance(config, (str, os.PathLike)):
        import yaml
        p = Path(config)
        if not p.is_absolute() and not p.exists():
            p = PROJECT_ROOT / p
        with open(p, "r", encoding="utf-8") as f:
            config = yaml.safe_load(f) or {}
    return _deep_merge(DEFAULTS, config)


def fit_frame(img: np.ndarray, width: int, height: int) -> np.ndarray:
    """Return `img` at width x height. Same aspect: resize. Other aspect: centre-crop to the target aspect first."""
    import cv2
    h, w = img.shape[:2]
    if (w, h) == (width, height):
        return img
    target = width / height
    if abs(w / h - target) > 1e-3:
        if w / h > target:            # too wide: crop the sides
            nw = int(round(h * target))
            x0 = (w - nw) // 2
            img = img[:, x0:x0 + nw]
        else:                         # too tall: crop top and bottom
            nh = int(round(w / target))
            y0 = (h - nh) // 2
            img = img[y0:y0 + nh]
    interp = cv2.INTER_AREA if img.shape[1] > width else cv2.INTER_LINEAR
    return cv2.resize(img, (width, height), interpolation=interp)


class _FixedBatchSession:
    """Workaround (traffic block unchanged): onnxruntime's CUDA EP adds ~22 ms to a run whenever the input batch
    shape differs from the previous run (measured here: 25-27 ms alternating 4/8/16 vs 3.7 ms same-shape, with
    EXHAUSTIVE and HEURISTIC cudnn search alike). The light classifier pads to buckets 2/4/8/16/32, so every change
    in the number of visible lights paid that. This proxy always feeds ONE shape (chunks of `batch`, zero-padded)."""

    def __init__(self, sess, batch: int = 8):
        self._s, self.batch = sess, int(batch)

    def __getattr__(self, name):
        return getattr(self._s, name)

    def run(self, output_names, feeds):
        (key, x), = feeds.items()
        outs = []
        for i in range(0, len(x), self.batch):
            chunk = x[i:i + self.batch]
            m = len(chunk)
            if m < self.batch:
                chunk = np.concatenate([chunk, np.zeros((self.batch - m, *chunk.shape[1:]), chunk.dtype)])
            outs.append(self._s.run(output_names, {key: chunk})[0][:m])
        return [np.concatenate(outs)] if outs else self._s.run(output_names, feeds)


@dataclass
class _Slot:
    every: int = 1
    phase: int = 0
    last_run: Optional[int] = None   # step index of the last run

    def due(self, k: int) -> bool:
        return k % self.every == self.phase % self.every

    def age(self, k: int) -> Optional[int]:
        return None if self.last_run is None else k - self.last_run


@dataclass
class _SignMemo:
    signClass: str
    confidence: float
    last_step: int


# ----------------------------------------------------------------------------- two-lane data
@dataclass(frozen=True)
class SessionCamera:
    """Pinhole camera of one stream, in the engine-input pixels of that stream (= the wire `image` space).
    None fields = defaults (BDD 700 px focal at 1280 wide, centred principal point, height from depth)."""
    width: int
    height: int
    focal_px: float
    cx: float
    cy: float
    cam_height_m: Optional[float] = None
    pitch_deg: Optional[float] = None
    source: str = "default"          # "default" | "client" (client.hello camera)

    def to_dict(self) -> dict[str, Any]:
        return {"focalPx": self.focal_px, "principalPoint": [self.cx, self.cy], "horizonY": None,
                "cameraHeightMeters": self.cam_height_m}


@dataclass
class FastSnapshot:
    """What the slow lane analyses: one fast-lane frame (image + tracked detections), read-only."""
    generation: int
    k: int                            # fast step index (blockAges are counted in these)
    frame_index: int
    pts_s: float
    image: np.ndarray                 # engine-input-size BGR frame (never mutated)
    main_dets: list[Detection]        # tracked vehicles / pedestrians (copies, id = track id)
    sign_dets: list[Detection]        # tracked signs (copies, id offset by STATIC_ID_OFFSET)
    tag: Any = None                   # opaque to the engine (the pipeline's Item)


@dataclass
class SlowResult:
    """One slow-lane run (-> wave-2 perception.update)."""
    generation: int
    snapshot: FastSnapshot
    ran: list[str]
    distances: Optional[list[dict[str, Any]]]     # None when the distance block did not run
    lanes_ran: bool
    lanes: Optional[LaneState]
    road: Optional[RoadGeometry]
    signs: Optional[list[TrafficSign]]            # None when the sign block did not run
    camera: dict[str, Any]
    timings: dict[str, float]


@dataclass(frozen=True)
class _DistMemo:
    distance: float
    method: str
    confidence: float
    lateral: Optional[float]
    pts_s: float                     # media time of the frame it was measured on
    k: int                           # fast step index of that frame


class _Shared:
    """Results the slow lane publishes and the fast lane reads (never model objects). Guard with `lock`."""

    def __init__(self):
        self.lock = threading.Lock()
        self.generation = 0
        self.camera: Optional[SessionCamera] = None
        self.clear()

    def clear(self) -> None:
        self.distances: dict[int, _DistMemo] = {}
        self.lanes: Optional[LaneState] = None
        self.road: Optional[RoadGeometry] = None
        self.lane_extras: dict[str, Any] = {}
        self.signs: dict[int, _SignMemo] = {}
        self.camera_info: dict[str, Any] = {}           # horizon_y, camera_height_m from the depth block
        self.block_last_k: dict[str, int] = {}          # slow block -> fast step index of its last snapshot


_STREAM_SYNC_PATCHED = False


def install_stream_local_sync() -> None:
    """Workaround (blocks unchanged) needed for two concurrent CUDA lanes: Ultralytics' Profile timer and the lanes
    backend's `_sync` call torch.cuda.synchronize(), which waits for EVERY stream on the device, so a fast-lane
    detection would wait for the slow lane's queued depth kernels (~40 ms). Both are only timing barriers; this
    makes them wait for the calling thread's current stream instead (same result for that thread's work)."""
    global _STREAM_SYNC_PATCHED
    if _STREAM_SYNC_PATCHED:
        return
    import torch
    if not torch.cuda.is_available():
        return
    try:
        from ultralytics.utils import ops as _ul_ops
        _orig_time = _ul_ops.Profile.time

        def _profile_time(self):
            acc = getattr(self, "accelerator", None)
            if acc is not None and getattr(self.device, "type", str(self.device)).startswith("cuda"):
                torch.cuda.current_stream().synchronize()
                return time.perf_counter()
            return _orig_time(self)
        _ul_ops.Profile.time = _profile_time
    except Exception as e:  # pragma: no cover - older/newer Ultralytics layouts
        print(f"[engine] could not patch Ultralytics Profile sync: {e}", flush=True)
    try:
        import perception.lanes.backends as _lb

        def _stream_sync(device) -> None:
            if str(device).startswith("cuda"):
                torch.cuda.current_stream().synchronize()
        _lb._sync = _stream_sync
    except Exception as e:  # pragma: no cover
        print(f"[engine] could not patch lanes _sync: {e}", flush=True)
    _STREAM_SYNC_PATCHED = True


class PerceptionEngine:
    """Loads every enabled block once (and warms it up).

    Serial use: `step(frame) -> FrameResult`, one thread. Two-lane use (realtime server): `fast_step` from ONE
    fast thread and `slow_step` from ONE slow thread (see the module docstring and realtime/pipeline.py).
    """

    def __init__(self, config: dict | str | os.PathLike | None = None, *, warmup: Optional[bool] = None,
                 verbose: bool = True):
        import torch  # noqa: F401  (must precede any onnxruntime session: ORT's CUDA EP reuses torch's DLLs)
        self.cfg = load_config(config)
        c = self.cfg
        self.verbose = verbose
        dev = c["device"]
        hz = float(c["target_hz"])
        t_load = time.perf_counter()
        self.load_ms: dict[str, float] = {}

        def _timed(name, fn):
            t0 = time.perf_counter()
            obj = fn()
            self.load_ms[name] = round((time.perf_counter() - t0) * 1000.0, 1)
            if verbose:
                print(f"[engine] loaded {name} in {self.load_ms[name] / 1000:.1f} s", flush=True)
            return obj

        from perception.detection import Detector
        from perception.tracking import Tracker
        d = c["detection"]
        self.detector = _timed("detection", lambda: Detector(weights=d["weights"], imgsz=d.get("imgsz"),
                                                            conf=d.get("conf"), device=dev))
        t = c["tracking"]
        self.tracker = _timed("tracking", lambda: Tracker(
            backend=t["backend"], device=dev, frame_rate=hz, track_buffer_s=t["track_buffer_s"],
            class_vote_s=t["class_vote_s"], classes=MAIN_CLASSES, **(t.get("opts") or {})))
        st = c["static_tracking"]
        self.static_tracker = Tracker(backend=st["backend"], device=dev, frame_rate=hz,
                                      track_buffer_s=st["track_buffer_s"], classes=STATIC_CLASSES,
                                      harmonised=st.get("harmonised", False), class_vote_s=0.0)

        self.depth = None
        if c["depth"]["enabled"]:
            from perception.depth import DistanceEstimator
            dc = c["depth"]
            be = dc.get("backend")
            self.depth = _timed("depth", lambda: DistanceEstimator(
                depth_backend=be, device=dev, focal_px=None, cam_height_m=c["camera"].get("cam_height_m"),
                depth_every=int(dc.get("every", 1)), backend_opts=dc.get("backend_opts") or None))
        self.lanes = None
        if c["lanes"]["enabled"]:
            from perception.lanes import LaneDetector
            self.lanes = _timed("lanes", lambda: LaneDetector(backend=c["lanes"]["backend"], device=dev, temporal=True))
        self.lights = None
        if c["lights"]["enabled"]:
            from perception.traffic.lights import TrafficLightClassifier
            lc = c["lights"]
            self.lights = _timed("lights", lambda: TrafficLightClassifier(
                backend=lc["backend"], device=lc.get("device", dev), smoothing=lc.get("smoothing", "hmm"),
                autoware_orient=lc.get("orient", "both"), track_stride=int(lc.get("track_stride", 1)),
                focal_px=self.focal_px_at(1280), fps=hz))
            aw = getattr(self.lights, "_aw", None)
            fb = int(lc.get("fixed_batch", 8) or 0)
            if aw is not None and fb > 0 and "CUDAExecutionProvider" in aw.providers:
                aw.sess = _FixedBatchSession(aw.sess, fb)
                aw.bucket = False          # the proxy does the padding
        self.signs = None
        if c["signs"]["enabled"]:
            from perception.traffic.signs import SignRecognizer
            sc = c["signs"]
            self.signs = _timed("signs", lambda: SignRecognizer(
                backend=sc["backend"], device=dev, emit_unknown=bool(sc.get("emit_unknown", False)),
                ocr=None, focal_px=self.focal_px_at(1280)))
            if sc.get("ocr") and sc["backend"] != "coco_stop":
                # Workaround (block unchanged): PP-OCR on the CUDA EP autotunes cudnn for every new text-line
                # width (30-255 ms on the first call per width, measured); the CPU EP is 2.5-8 ms with no spikes.
                from perception.traffic.signs import PPOCRRec
                self.signs.ocr = PPOCRRec(device=sc.get("ocr_device", "cpu"))
        self.segmenter = None
        if c["segmentation"]["enabled"]:
            from perception.segmentation.semantic import SemanticSegmenter
            self.segmenter = _timed("segmentation", lambda: SemanticSegmenter(
                backend=c["segmentation"]["backend"], device=dev, temporal=True))
        if c["openpilot"].get("enabled"):
            raise NotImplementedError("the openpilot block is experimental and not wired into the realtime engine")

        self.slots: dict[str, _Slot] = {"detection": _Slot(), "tracking": _Slot(), "lights": _Slot()}
        if self.depth is not None:
            self.slots["depth"] = _Slot(int(c["depth"].get("every", 1)), 0)   # depth block's own counter: phase 0
        if self.lanes is not None:
            self.slots["lanes"] = _Slot(int(c["lanes"].get("every", 1)), int(c["lanes"].get("phase", 0)))
        if self.signs is not None:
            self.slots["signs"] = _Slot(int(c["signs"].get("every", 1)), int(c["signs"].get("phase", 0)))
        if self.segmenter is not None:
            self.slots["segmentation"] = _Slot(int(c["segmentation"].get("every", 1)),
                                               int(c["segmentation"].get("phase", 0)))
        if self.lights is None:
            self.slots.pop("lights")
        # two-lane state: slow-lane schedule in slow cycles, shared results, per-lane session bookkeeping
        self.two_lane = str(c.get("lanes_mode", "two_lane")) == "two_lane"
        ss = c.get("slow_schedule") or {}
        self.slow_slots: dict[str, _Slot] = {}
        if self.depth is not None:
            self.slow_slots["depth"] = _Slot(max(1, int((ss.get("depth") or {}).get("every", 1))), 0)
        for name, blk in (("lanes", self.lanes), ("signs", self.signs), ("segmentation", self.segmenter)):
            if blk is not None:
                sc_ = ss.get(name) or {}
                self.slow_slots[name] = _Slot(max(1, int(sc_.get("every", 1))), int(sc_.get("phase", 0)))
        self.shared = _Shared()
        self._slow_gen = -1
        self._slow_cycle = 0
        self._fast_cam: Optional[SessionCamera] = None
        self.load_ms["total"] = round((time.perf_counter() - t_load) * 1000.0, 1)
        self.reset()
        if c["warmup"] if warmup is None else warmup:
            self.warmup()

    # ------------------------------------------------------------------ camera
    def focal_px_at(self, width: int) -> float:
        return float(self.cfg["camera"]["focal_px"]) * width / 1280.0

    def camera(self, width: int, height: int) -> dict[str, Any]:
        """Pinhole camera actually used by the depth block (or the configured one)."""
        cam = getattr(self.depth, "cam", None) if self.depth is not None else None
        info = self.depth.last_info if self.depth is not None else {}
        if cam is not None and cam.width == width:
            focal, cx, cy = cam.focal_px, cam.cx, cam.cy
        else:
            focal, cx, cy = self.focal_px_at(width), width / 2.0, height / 2.0
        horizon = info.get("horizon_y")
        if horizon is None and self._road is not None:
            horizon = self._road.horizonY
        height_m = info.get("camera_height_m", self.cfg["camera"].get("cam_height_m"))
        return {"focalPx": float(focal), "principalPoint": [float(cx), float(cy)],
                "horizonY": None if horizon is None else float(horizon),
                "cameraHeightMeters": None if height_m is None else float(height_m)}

    # ------------------------------------------------------------------ state
    def reset(self) -> None:
        """Forget all per-video state (call between unrelated videos / when a camera stream restarts)."""
        self.k = 0
        for s in getattr(self, "slots", {}).values():
            s.last_run = None
        self.tracker.reset()
        self.static_tracker.reset()
        for blk in (self.depth, self.lanes, self.lights, self.signs, self.segmenter):
            if blk is not None:
                blk.reset()
        self._lanes: Optional[LaneState] = None
        self._road: Optional[RoadGeometry] = None
        self._lane_extras: dict[str, Any] = {}
        self._signs: dict[int, _SignMemo] = {}
        self.last_meta: dict[str, Any] = {}
        self._depth_runs = 0
        if self.depth is not None:
            self.depth.detect_hood = "auto"       # re-arm hood detection for the new video
            self.depth.depth_every = max(1, int(self.cfg["depth"].get("every", 1)))
            self._apply_slow_camera(None)
        self._slow_gen = -1                        # two-lane: the slow lane re-syncs on its next snapshot

    def _maybe_freeze_hood(self) -> None:
        """Workaround (block unchanged): the depth block's 'auto' hood-row detection costs ~17-20 ms of CPU per
        depth run (measured). The hood does not move within a video, so after `hood_freeze_after` depth runs the
        detected row (or 'no hood') is fixed, as the depth README recommends ('a per-video float is safer')."""
        n = int(self.cfg["depth"].get("hood_freeze_after") or 0)
        if n > 0 and self.depth.detect_hood == "auto" and self._depth_runs >= n:
            self.depth.detect_hood = self.depth.hood_row()   # float row or None (= no hood)

    def warmup(self, n_cycles: int = 2) -> None:
        """Run every block on synthetic input (cudnn / ORT algorithm search, CUDA context), then reset state."""
        t0 = time.perf_counter()
        W, H = self.cfg["input"]["width"], self.cfg["input"]["height"]
        rng = np.random.default_rng(0)
        img = (rng.integers(0, 255, (H, W, 3), dtype=np.uint8) // 2 + 60).astype(np.uint8)
        self.detector.warmup(n=3, shape=(H, W))
        if self.depth is not None:
            for _ in range(2):
                self.depth.raw_depth_map(img)
        if self.lanes is not None:
            for _ in range(2):
                self.lanes.analyze(img, full_res_masks=False)
        if self.lights is not None:
            # ORT CUDA EP autotunes once per input shape (without the fixed-batch proxy: buckets 2/4/8/16/32)
            crops = [img[100:160, 600:625].copy() for _ in range(32)]
            for n in (1, 3, 5, 9, 17):
                for _ in range(2):
                    self.lights.probs(crops[:n])   # with the fixed-batch proxy this is one shape
        if self.signs is not None:
            # cudnn.benchmark is on (set by the lanes backend), so every new LISA crop-batch size autotunes once
            crop = img[200:520, 400:720].copy()
            for n in range(1, 9):
                self.signs.model.predict([crop] * n, imgsz=self.signs.imgsz_crop, conf=self.signs.conf,
                                         device=self.signs.device, verbose=False)
            if getattr(self.signs, "ocr", None) is not None:
                for w in (40, 120, 320):
                    self.signs.ocr.read(img[300:348, 400:400 + w].copy())
        if self.segmenter is not None:
            for _ in range(2):
                self.segmenter.process(img)
        n = n_cycles * math.lcm(*[s.every for s in self.slots.values()])
        for i in range(n):
            self.step(Frame(i, i / float(self.cfg["target_hz"]), img))
        self.reset()
        self.warmup_ms = round((time.perf_counter() - t0) * 1000.0, 1)
        if self.verbose:
            print(f"[engine] warm-up done in {self.warmup_ms / 1000:.1f} s", flush=True)

    # ------------------------------------------------------------------ main
    def step(self, frame: Frame) -> FrameResult:
        """Analyse one upright BGR frame. Returns the in-memory FrameResult; see `last_meta` for the rest."""
        t_start = time.perf_counter()
        k = self.k
        W, H = self.cfg["input"]["width"], self.cfg["input"]["height"]
        img = fit_frame(frame.image, W, H)
        pts = float(frame.pts_s)
        tm: dict[str, float] = {}
        ran: list[str] = []

        def tick(name, t0):
            tm[name] = (time.perf_counter() - t0) * 1000.0

        # 1) detection (every step)
        t0 = time.perf_counter()
        dets = self.detector.detect(img)
        tick("detect", t0)
        ran.append("detection")
        main = [d for d in dets if canonical_class(d.cls) in MAIN_CLASSES]
        static = [d for d in dets if canonical_class(d.cls) in STATIC_CLASSES]

        # 2) tracking (every step). The ego polygon (carried from the last lanes run) gates `approaching`.
        t0 = time.perf_counter()
        ego = self._road.egoPathPolygon if (self._road is not None and len(self._road.egoPathPolygon) >= 3) else None
        tracks = self.tracker.update(main, img, pts, ego_polygon=ego)
        tick("track", t0)
        t0 = time.perf_counter()
        s_tracks = self.static_tracker.update(static, img, pts)
        for d in static:
            if d.id is not None:
                d.id += STATIC_ID_OFFSET
        for tr in s_tracks:
            tr.id += STATIC_ID_OFFSET
            tr.ttc_s = None           # scale-change TTC is meaningless for lights/signs
            tr.approaching = None
            tr.scale_rate = None
        tick("trackStatic", t0)
        ran.append("tracking")

        # 3) depth / distance (network every N-th step inside the depth block; geometry + Kalman in between)
        distances: list[DistanceEstimate] = []
        depth_details: dict[int, dict[str, Any]] = {}
        if self.depth is not None:
            t0 = time.perf_counter()
            det_list = self.depth.estimate_detailed(img, main, pts_s=pts)
            for r in det_list:
                if r["distance"] is None or r["id"] is None or r["id"] < 0:
                    continue
                distances.append(DistanceEstimate(vehicleId=int(r["id"]), distanceMeters=float(r["distance"]),
                                                  method=r["method"], confidence=float(r["confidence"])))
                depth_details[int(r["id"])] = {"lateral_m": r.get("lateral_m"), "components": r.get("components"),
                                               "sigma": r.get("sigma")}
            tick("depth", t0)
            if self.depth.last_info.get("depth_ms") is not None:
                ran.append("depth")
                tm["depthNet"] = float(self.depth.last_info["depth_ms"])
                self._depth_runs += 1
                self._maybe_freeze_hood()

        # 4) lanes + lane-based road geometry (every N; carried forward)
        if self.lanes is not None and self.slots["lanes"].due(k):
            t0 = time.perf_counter()
            a = self.lanes.analyze(img, full_res_masks=False)
            self._lanes = a.lane_state
            self._lane_extras = {kk: a.extras.get(kk) for kk in ("event", "lanesLeft", "lanesRight",
                                                                 "egoLateralOffsetNorm", "boundaries")}
            if self.segmenter is None:
                self._road = a.road
            tick("lanes", t0)
            ran.append("lanes")

        # 5) optional semantic segmentation road geometry (off by default; replaces the lane-based road)
        if self.segmenter is not None and self.slots["segmentation"].due(k):
            t0 = time.perf_counter()
            _, road = self.segmenter.process(img)
            self._road = road
            tick("segmentation", t0)
            ran.append("segmentation")

        # 6) traffic-light state on light tracks (every step; per-track stride inside the block)
        lights: list[TrafficLightState] = []
        if self.lights is not None:
            t0 = time.perf_counter()
            tracked_lights = [d for d in static if d.cls == "traffic light" and d.id is not None]
            lights = self.lights.classify(img, tracked_lights, pts_s=pts)
            tick("lights", t0)
            ran.append("lights")

        # 7) signs (every N; typed class carried per track id in between)
        signs: list[TrafficSign] = []
        tracked_signs = [d for d in static if d.cls == "traffic sign" and d.id is not None]
        if self.signs is not None and self.slots["signs"].due(k):
            t0 = time.perf_counter()
            typed = self.signs.recognize(img, tracked_signs, pts_s=pts) if tracked_signs else []
            for s in typed:
                if s.id is not None and s.signClass != "unknown":
                    self._signs[s.id] = _SignMemo(s.signClass, float(s.confidence), k)
            signs = typed
            tick("signs", t0)
            ran.append("signs")
        elif self.signs is not None:
            from perception.traffic.signs import sign_distance_m
            for d in tracked_signs:
                m = self._signs.get(d.id)
                if m is None:
                    continue
                z = sign_distance_m(d.bbox, m.signClass, self.signs.focal_px)
                signs.append(TrafficSign(id=d.id, signClass=m.signClass, bbox=list(d.bbox), confidence=m.confidence,
                                         distanceMeters=round(z, 1) if z else None))
        # forget sign memos whose track has been gone for ~3 s
        if self._signs:
            horizon = int(3.0 * float(self.cfg["target_hz"]))
            live = {d.id for d in tracked_signs}
            for sid in live & self._signs.keys():
                self._signs[sid].last_step = k
            for sid in [s for s, m in self._signs.items() if k - m.last_step > horizon]:
                self._signs.pop(sid, None)

        for name in ran:
            if name in self.slots:
                self.slots[name].last_run = k
        tm["total"] = (time.perf_counter() - t_start) * 1000.0

        result = FrameResult(frameIndex=int(frame.index), ptsSeconds=pts, detections=dets,
                             tracks=tracks + s_tracks, distances=distances, lanes=self._lanes,
                             trafficLights=lights, trafficSigns=signs, road=self._road,
                             timingsMs={kk: round(v, 2) for kk, v in tm.items()})
        ages = {name: s.age(k) for name, s in self.slots.items() if s.last_run is not None}
        if self.lanes is not None and self.segmenter is None and "lanes" in ages:
            ages["road"] = ages["lanes"]
        elif self.segmenter is not None and "segmentation" in ages:
            ages["road"] = ages["segmentation"]
        self.last_meta = {
            "step": k,
            "image": {"width": W, "height": H},
            "camera": self.camera(W, H),
            "blockAges": ages,
            "ranBlocks": ran,
            "depthDetails": depth_details,
            "laneExtras": self._lane_extras,
        }
        self.k += 1
        return result

    # ================================================================== two-lane API (protocol v2)
    def configure_slow_schedule(self, schedule: dict[str, dict[str, int]]) -> None:
        """Change the slow-lane schedule (slow cycles) at runtime, e.g. from a benchmark. Call between streams."""
        for name, sc_ in (schedule or {}).items():
            if name in self.slow_slots:
                s = self.slow_slots[name]
                s.every = max(1, int(sc_.get("every", s.every)))
                s.phase = int(sc_.get("phase", s.phase))
            self.cfg.setdefault("slow_schedule", {}).setdefault(name, {}).update(sc_)
        self._slow_gen = -1

    def default_camera(self, width: Optional[int] = None, height: Optional[int] = None) -> SessionCamera:
        W = int(width or self.cfg["input"]["width"])
        H = int(height or self.cfg["input"]["height"])
        return SessionCamera(W, H, self.focal_px_at(W), W / 2.0, H / 2.0, self.cfg["camera"].get("cam_height_m"))

    def session_camera(self, client_cam: Optional[dict[str, Any]], frame_wh: Optional[tuple[int, int]] = None,
                       max_width: Optional[int] = None) -> SessionCamera:
        """SessionCamera for a stream: client.hello `camera` (intrinsics at the uplinked, upright resolution) with
        defaults for missing fields. The engine-input size is the client's image size (or the first frame's),
        scaled down to `max_width` (intrinsics scaled with it)."""
        cc = client_cam or {}
        W = cc.get("imageWidth") or (frame_wh[0] if frame_wh else None) or self.cfg["input"]["width"]
        H = cc.get("imageHeight") or (frame_wh[1] if frame_wh else None) or self.cfg["input"]["height"]
        W, H = int(W), int(H)
        f = cc.get("focalPx")
        pp = cc.get("principalPoint") or [None, None]
        cx, cy = pp[0], pp[1]
        s = 1.0
        mw = int(max_width or self.cfg["input"].get("max_width", 1280) or 0)
        if mw and W > mw:
            s = mw / W
        W2, H2 = int(round(W * s)), int(round(H * s))
        return SessionCamera(
            W2, H2,
            float(f) * s if f else self.focal_px_at(W2),
            float(cx) * s if cx is not None else W2 / 2.0,
            float(cy) * s if cy is not None else H2 / 2.0,
            float(cc["mountHeightMeters"]) if cc.get("mountHeightMeters") else self.cfg["camera"].get("cam_height_m"),
            float(cc["pitchDegrees"]) if cc.get("pitchDegrees") is not None else None,
            "client" if (f or cc.get("mountHeightMeters")) else "default")

    def _apply_slow_camera(self, cam: Optional[SessionCamera]) -> None:
        """Slow lane (or serial reset): point the depth + sign blocks at this stream's camera."""
        if self.depth is not None:
            d = self.depth
            if cam is None:
                d.focal_px, d.cx, d.cy = None, None, None
                d.cam_height_given = self.cfg["camera"].get("cam_height_m")
                d.pitch_deg = 0.0
            else:
                d.focal_px, d.cx, d.cy = cam.focal_px, cam.cx, cam.cy
                d.cam_height_given = cam.cam_height_m
                d.pitch_deg = float(cam.pitch_deg) if cam.pitch_deg is not None else 0.0
            # same rule as DistanceEstimator.__init__(calibration='auto'): a given mount height is trusted
            if d.backend is None:
                d.calibration = "fixed"
            elif d.backend.needs_focal:
                d.calibration = "fixed" if d.cam_height_given is not None else "height_from_depth"
            else:
                d.calibration = "scale_from_height"
            d.cam = None
        if self.signs is not None:
            self.signs.focal_px = cam.focal_px if cam is not None else self.focal_px_at(1280)

    def warmup_lane(self, lane: str) -> float:
        """Warm up one lane's models IN THE CALLING THREAD (and its current CUDA stream), then reset that lane.

        Needed because PyTorch's cuDNN v8 execution-plan cache and onnxruntime's CUDA per-thread contexts are
        thread-local: the main-thread warmup() does not cover the lane threads. Measured without it: the first
        ~5 sign-recognizer calls in the slow thread took ~1.5 s each (one autotune per new crop-batch size) and
        the first slow cycle ~3 s. Returns the warm-up time in ms."""
        t0 = time.perf_counter()
        W, H = self.cfg["input"]["width"], self.cfg["input"]["height"]
        rng = np.random.default_rng(1)
        img = (rng.integers(0, 255, (H, W, 3), dtype=np.uint8) // 2 + 60).astype(np.uint8)
        if lane == "fast":
            self.detector.warmup(n=3, shape=(H, W))
            if self.lights is not None:
                crops = [img[100:160, 600:625].copy() for _ in range(17)]
                for n in (1, 3, 5, 9, 17):
                    self.lights.probs(crops[:n])
            self.begin_session(self.default_camera(W, H))
            for i in range(6):
                self.fast_step(Frame(i, i / float(self.cfg["target_hz"]), img))
            self.begin_session(self.default_camera(W, H))
        elif lane == "slow":
            if self.depth is not None:
                for _ in range(2):
                    self.depth.raw_depth_map(img)
            if self.lanes is not None:
                for _ in range(2):
                    self.lanes.analyze(img, full_res_masks=False)
            if self.signs is not None:
                crop = img[200:520, 400:720].copy()
                for n in range(1, 9):
                    self.signs.model.predict([crop] * n, imgsz=self.signs.imgsz_crop, conf=self.signs.conf,
                                             device=self.signs.device, verbose=False)
                if getattr(self.signs, "ocr", None) is not None:
                    for w in (40, 120, 320):
                        self.signs.ocr.read(img[300:348, 400:400 + w].copy())
            if self.segmenter is not None:
                for _ in range(2):
                    self.segmenter.process(img)
            if self.depth is not None:    # geometry / fusion code path (no detections)
                for i in range(2):
                    self.depth.estimate_detailed(img, [], pts_s=float(i))
            self._slow_gen = -1           # reset the slow models on the first real snapshot
        else:
            raise ValueError(lane)
        return round((time.perf_counter() - t0) * 1000.0, 1)

    # ---------------------------------------------------------------- fast lane (ONE thread)
    def begin_session(self, camera: Optional[SessionCamera] = None) -> int:
        """FAST thread: start a new stream (new video / uplink session / seek). Resets the fast-lane models and the
        shared results and bumps the generation; the slow lane resets its own models when it sees it."""
        cam = camera or self.default_camera()
        with self.shared.lock:
            self.shared.generation += 1
            self.shared.clear()
            self.shared.camera = cam
            gen = self.shared.generation
        self.k = 0
        self.tracker.reset()
        self.static_tracker.reset()
        if self.lights is not None:
            self.lights.reset()
            self.lights.focal_px = cam.focal_px
        self._fast_cam = cam
        return gen

    def fast_step(self, frame: Frame) -> tuple[FrameResult, dict[str, Any], FastSnapshot]:
        """Wave 1: detection + tracking + light state on one upright BGR frame, with the slow lane's latest
        distances / lanes / road / signs carried forward. Returns (result, meta, snapshot for the slow lane)."""
        if self._fast_cam is None:
            self.begin_session(None)
        t_start = time.perf_counter()
        cam = self._fast_cam
        k = self.k
        W, H = cam.width, cam.height
        img = fit_frame(frame.image, W, H)
        pts = float(frame.pts_s)
        tm: dict[str, float] = {}
        sh = self.shared
        with sh.lock:                     # one short read of everything the slow lane publishes
            gen = sh.generation
            road, lanes, lane_extras = sh.road, sh.lanes, sh.lane_extras
            dist_memo = dict(sh.distances)
            sign_memo = {sid: m.signClass for sid, m in sh.signs.items()}
            sign_conf = {sid: m.confidence for sid, m in sh.signs.items()}
            cam_info = dict(sh.camera_info)
            last_k = dict(sh.block_last_k)

        def tick(name, t0):
            tm[name] = (time.perf_counter() - t0) * 1000.0

        t0 = time.perf_counter()
        dets = self.detector.detect(img)
        tick("detect", t0)
        main = [d for d in dets if canonical_class(d.cls) in MAIN_CLASSES]
        static = [d for d in dets if canonical_class(d.cls) in STATIC_CLASSES]

        t0 = time.perf_counter()
        ego = road.egoPathPolygon if (road is not None and len(road.egoPathPolygon) >= 3) else None
        tracks = self.tracker.update(main, img, pts, ego_polygon=ego)
        tick("track", t0)
        t0 = time.perf_counter()
        s_tracks = self.static_tracker.update(static, img, pts)
        for d in static:
            if d.id is not None:
                d.id += STATIC_ID_OFFSET
        for tr in s_tracks:
            tr.id += STATIC_ID_OFFSET
            tr.ttc_s = None
            tr.approaching = None
            tr.scale_rate = None
        tick("trackStatic", t0)

        lights: list[TrafficLightState] = []
        if self.lights is not None:
            t0 = time.perf_counter()
            tracked_lights = [d for d in static if d.cls == "traffic light" and d.id is not None]
            lights = self.lights.classify(img, tracked_lights, pts_s=pts)
            tick("lights", t0)

        # carried forward from the slow lane: typed signs per static track id, distances per track id
        signs: list[TrafficSign] = []
        tracked_signs = [d for d in static if d.cls == "traffic sign" and d.id is not None]
        if self.signs is not None and sign_memo:
            from perception.traffic.signs import sign_distance_m
            for d in tracked_signs:
                cls_ = sign_memo.get(d.id)
                if cls_ is None:
                    continue
                z = sign_distance_m(d.bbox, cls_, cam.focal_px)
                signs.append(TrafficSign(id=d.id, signClass=cls_, bbox=list(d.bbox), confidence=sign_conf[d.id],
                                         distanceMeters=round(z, 1) if z else None))
        if self.signs is not None and tracked_signs:
            live = {d.id for d in tracked_signs}
            horizon = int(3.0 * float(self.cfg["target_hz"]))
            with sh.lock:
                if sh.generation == gen:
                    for sid in live & sh.signs.keys():
                        sh.signs[sid].last_step = k
                    for sid in [s for s, m in sh.signs.items() if k - m.last_step > horizon]:
                        sh.signs.pop(sid, None)

        distances: list[DistanceEstimate] = []
        depth_details: dict[int, dict[str, Any]] = {}
        ages_ms: dict[int, float] = {}
        max_age = float(self.cfg["slow"].get("max_distance_age_s") or 1e9)
        for tr in tracks:
            m = dist_memo.get(tr.id)
            if m is None:
                continue
            age_s = max(0.0, pts - m.pts_s)
            if age_s > max_age:
                continue
            distances.append(DistanceEstimate(vehicleId=int(tr.id), distanceMeters=m.distance, method=m.method,
                                              confidence=m.confidence))
            depth_details[int(tr.id)] = {"lateral_m": m.lateral}
            ages_ms[int(tr.id)] = age_s * 1000.0
        # geometry-only fallback so every vehicle / pedestrian has a number before (or long after) its first fused
        # slow-lane distance: size prior + flat ground on THIS frame (distanceMethod "geometry", age 0)
        if self.cfg["fast"].get("geometry_distance", True):
            have = {d.vehicleId for d in distances}
            todo = [tr for tr in tracks if tr.id not in have]
            if todo:
                t0 = time.perf_counter()
                from perception.depth import geometry as G
                h_c = cam_info.get("camera_height_m") or cam.cam_height_m or 1.3
                gcam = G.Camera(cam.focal_px, cam.cx, cam.cy, float(h_c), W, H)
                yh, syh = cam_info.get("horizon_y"), cam_info.get("horizon_sigma_px") or 6.0
                if yh is None and road is not None and road.horizonY is not None:
                    yh, syh = float(road.horizonY), 8.0
                if yh is None:   # no slow-lane horizon yet: object-size virtual horizon + principal-row prior
                    prior = cam.cy - cam.focal_px * math.tan(math.radians(cam.pitch_deg or 0.0))
                    cands = [(prior, 0.06 * H)]
                    vh = G.virtual_horizon([(canonical_class(tr.cls), tr.bbox) for tr in tracks], gcam)
                    if vh is not None:
                        cands.append((vh[0], vh[1] * (1.0 if vh[2] > 1 else 1.5)))
                    wts = np.array([1.0 / s ** 2 for _, s in cands])
                    yh = float(np.sum(wts * np.array([c for c, _ in cands])) / wts.sum())
                    syh = float(1.0 / math.sqrt(wts.sum()))
                hood = cam_info.get("hood_row")
                for tr in todo:
                    g = geometry_distance(canonical_class(tr.cls), tr.bbox, gcam, yh, float(syh), hood)
                    if g is None:
                        continue
                    distances.append(DistanceEstimate(vehicleId=int(tr.id), distanceMeters=float(g[0]),
                                                      method=GEOMETRY_METHOD, confidence=float(g[1])))
                    depth_details[int(tr.id)] = {"lateral_m": float(g[2])}
                    ages_ms[int(tr.id)] = 0.0
                tick("geometryDistance", t0)
        tm["total"] = (time.perf_counter() - t_start) * 1000.0

        result = FrameResult(frameIndex=int(frame.index), ptsSeconds=pts, detections=dets,
                             tracks=tracks + s_tracks, distances=distances, lanes=lanes,
                             trafficLights=lights, trafficSigns=signs, road=road,
                             timingsMs={kk: round(v, 2) for kk, v in tm.items()})
        ages: dict[str, int] = {"detection": 0, "tracking": 0}
        if self.lights is not None:
            ages["lights"] = 0
        for name, kk in last_k.items():
            ages[name] = max(0, k - kk)
        if "lanes" in ages and self.segmenter is None:
            ages["road"] = ages["lanes"]
        elif "segmentation" in ages:
            ages["road"] = ages["segmentation"]
        camera = cam.to_dict()
        horizon_y = cam_info.get("horizon_y")
        if horizon_y is None and road is not None:
            horizon_y = road.horizonY
        camera["horizonY"] = None if horizon_y is None else float(horizon_y)
        if cam_info.get("camera_height_m") is not None:
            camera["cameraHeightMeters"] = float(cam_info["camera_height_m"])
        meta = {
            "step": k,
            "generation": gen,
            "image": {"width": W, "height": H},
            "camera": camera,
            "blockAges": ages,
            "ranBlocks": ["detection", "tracking"] + (["lights"] if self.lights is not None else []),
            "depthDetails": depth_details,
            "distanceAgesMs": ages_ms,
            "laneExtras": lane_extras,
        }
        snap = FastSnapshot(gen, k, int(frame.index), pts, img,
                            [dataclasses.replace(d) for d in main if d.id is not None],
                            [dataclasses.replace(d) for d in tracked_signs])
        self.last_meta = meta
        self.k += 1
        return result, meta, snap

    # ---------------------------------------------------------------- slow lane (ONE thread)
    def _slow_sync(self, gen: int) -> bool:
        """Reset the slow-lane models when the fast lane started a new stream. False = snapshot is stale."""
        with self.shared.lock:
            cur, cam = self.shared.generation, self.shared.camera
        if gen != cur:
            return False
        if gen != self._slow_gen:
            for blk in (self.depth, self.lanes, self.signs, self.segmenter):
                if blk is not None:
                    blk.reset()
            if self.depth is not None:
                self.depth.detect_hood = "auto"
                self.depth.depth_every = self.slow_slots["depth"].every
            self._apply_slow_camera(cam)
            self._depth_runs = 0
            self._slow_cycle = 0
            for s in self.slow_slots.values():
                s.last_run = None
            self._slow_gen = gen
        return True

    def slow_step(self, snap: FastSnapshot) -> Optional[SlowResult]:
        """Wave 2 on one fast-lane snapshot: depth/distance for its track ids, lanes + road, signs (on the slow
        schedule). Publishes into the shared state. None = the snapshot belongs to an older stream."""
        if not self._slow_sync(snap.generation):
            return None
        t_start = time.perf_counter()
        c = self._slow_cycle
        self._slow_cycle += 1
        img, pts = snap.image, snap.pts_s
        H, W = img.shape[:2]
        tm: dict[str, float] = {}
        ran: list[str] = []
        distances = None
        new_memo: dict[int, _DistMemo] = {}
        cam_info: dict[str, Any] = {}
        if self.depth is not None:
            t0 = time.perf_counter()
            det_list = self.depth.estimate_detailed(img, snap.main_dets, pts_s=pts)
            distances = []
            for r in det_list:
                if r["distance"] is None or r["id"] is None or r["id"] < 0:
                    continue
                d = {"id": int(r["id"]), "distanceMeters": float(r["distance"]), "distanceMethod": r["method"],
                     "distanceConfidence": float(r["confidence"]), "lateralMeters": r.get("lateral_m")}
                distances.append(d)
                new_memo[d["id"]] = _DistMemo(d["distanceMeters"], d["distanceMethod"], d["distanceConfidence"],
                                              d["lateralMeters"], pts, snap.k)
            tm["distance"] = (time.perf_counter() - t0) * 1000.0
            info = self.depth.last_info
            cam_info = {"horizon_y": info.get("horizon_y"), "camera_height_m": info.get("camera_height_m"),
                        "horizon_sigma_px": info.get("horizon_sigma_px"), "hood_row": info.get("hood_row")}
            ran.append("distance")
            if info.get("depth_ms") is not None:
                ran.append("depth")
                tm["depthNet"] = float(info["depth_ms"])
                self._depth_runs += 1
                self._maybe_freeze_hood()

        lanes = road = None
        lanes_ran = False
        extras: dict[str, Any] = {}
        if self.lanes is not None and self.slow_slots["lanes"].due(c):
            t0 = time.perf_counter()
            a = self.lanes.analyze(img, full_res_masks=False)
            lanes, lanes_ran = a.lane_state, True
            if self.segmenter is None:
                road = a.road
            extras = {kk: a.extras.get(kk) for kk in ("event", "lanesLeft", "lanesRight", "egoLateralOffsetNorm",
                                                      "boundaries")}
            tm["lanes"] = (time.perf_counter() - t0) * 1000.0
            ran.append("lanes")
        seg_road = None
        if self.segmenter is not None and self.slow_slots["segmentation"].due(c):
            t0 = time.perf_counter()
            _, seg_road = self.segmenter.process(img)
            tm["segmentation"] = (time.perf_counter() - t0) * 1000.0
            ran.append("segmentation")

        signs = None
        if self.signs is not None and self.slow_slots["signs"].due(c):
            t0 = time.perf_counter()
            signs = self.signs.recognize(img, snap.sign_dets, pts_s=pts) if snap.sign_dets else []
            tm["signs"] = (time.perf_counter() - t0) * 1000.0
            ran.append("signs")
        tm["total"] = (time.perf_counter() - t_start) * 1000.0

        with self.shared.lock:
            sh = self.shared
            if sh.generation != snap.generation:
                return None
            if distances is not None:
                sh.distances.update(new_memo)
                horizon_s = 3.0
                for tid in [t for t, m in sh.distances.items() if pts - m.pts_s > horizon_s]:
                    sh.distances.pop(tid, None)
                sh.camera_info = cam_info
            if lanes_ran:
                sh.lanes = lanes
                if road is not None:
                    sh.road = road
                sh.lane_extras = extras
            if seg_road is not None:
                sh.road = seg_road
            if signs is not None:
                for s in signs:
                    if s.id is not None and s.signClass != "unknown":
                        sh.signs[s.id] = _SignMemo(s.signClass, float(s.confidence), snap.k)
            for name in ran:
                prev = sh.block_last_k.get(name)
                sh.block_last_k[name] = snap.k if prev is None else max(prev, snap.k)
            cam = sh.camera
        camera = cam.to_dict() if cam is not None else self.default_camera(W, H).to_dict()
        if cam_info.get("horizon_y") is not None:
            camera["horizonY"] = float(cam_info["horizon_y"])
        elif road is not None and road.horizonY is not None:
            camera["horizonY"] = float(road.horizonY)
        if cam_info.get("camera_height_m") is not None:
            camera["cameraHeightMeters"] = float(cam_info["camera_height_m"])
        return SlowResult(snap.generation, snap, ran, distances, lanes_ran, lanes,
                          road if seg_road is None else seg_road, signs, camera,
                          {kk: round(v, 2) for kk, v in tm.items()})

    # ------------------------------------------------------------------ describe
    def describe(self) -> dict[str, Any]:
        """Model list, licences and schedule (goes into perception.hello and GET /config)."""
        c = self.cfg
        models = [{"block": "detection", "name": self.detector.preset_name or str(self.detector.weights_path),
                   "detail": f"imgsz {self.detector.imgsz}, conf {self.detector.conf}", "licence": LICENCES["detection"]},
                  {"block": "tracking", "name": c["tracking"]["backend"],
                   "detail": f"+ {c['static_tracking']['backend']} for lights/signs (ids >= {STATIC_ID_OFFSET})",
                   "licence": LICENCES["tracking"]}]
        if self.depth is not None:
            models.append({"block": "depth", "name": self.depth.backend_name or "geometry",
                           "detail": "fused depth-model + flat-ground + size prior, per-track Kalman",
                           "licence": LICENCES["depth"].get(self.depth.backend_name, "")})
        if self.lanes is not None:
            models.append({"block": "lanes", "name": self.lanes.backend_name, "detail": "temporal=True",
                           "licence": LICENCES["lanes"].get(self.lanes.backend_name, "")})
        if self.lights is not None:
            models.append({"block": "lights", "name": self.lights.backend,
                           "detail": f"smoothing {c['lights'].get('smoothing')}, orient {c['lights'].get('orient')}",
                           "licence": LICENCES["lights"].get(self.lights.backend, "")})
        if self.signs is not None:
            models.append({"block": "signs", "name": self.signs.backend, "detail": "OCR-verified speed limits",
                           "licence": LICENCES["signs"]})
        if self.segmenter is not None:
            models.append({"block": "segmentation", "name": c["segmentation"]["backend"], "detail": "",
                           "licence": LICENCES["segmentation"]})
        if self.two_lane:
            # fast blocks run on every analysed frame; slow blocks count slow-lane cycles ("every": n)
            schedule = {name: {"lane": "fast", "every": 1, "phase": 0}
                        for name in ("detection", "tracking", "lights") if name in self.slots}
            if self.cfg["fast"].get("geometry_distance", True):
                schedule["geometryDistance"] = {"lane": "fast", "every": 1, "phase": 0}
            if self.depth is not None:
                schedule["distance"] = {"lane": "slow", "every": 1, "phase": 0}
            for name, s in self.slow_slots.items():
                schedule[name] = {"lane": "slow", "every": s.every, "phase": s.phase % s.every}
        else:
            schedule = {name: {"lane": "serial", "every": s.every, "phase": s.phase % s.every}
                        for name, s in self.slots.items()}
        return {"models": models, "schedule": schedule, "targetHz": float(c["target_hz"]),
                "lanesMode": "two_lane" if self.two_lane else "serial",
                "staticIdOffset": STATIC_ID_OFFSET, "loadMs": self.load_ms,
                "image": {"width": c["input"]["width"], "height": c["input"]["height"]}}

    def close(self) -> None:
        for name in ("detector", "depth", "lanes"):
            blk = getattr(self, name, None)
            if blk is not None and hasattr(blk, "close"):
                try:
                    blk.close()
                except Exception:
                    pass
        if self.segmenter is not None:
            self.segmenter.release()
