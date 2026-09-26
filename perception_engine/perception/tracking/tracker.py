"""Plan section 8: multi-object tracking behind one detector-agnostic interface.

    from perception.tracking import Tracker
    trk = Tracker(backend="ul_botsort", frame_rate=30)
    tracks = trk.update(detections, frame_bgr, pts_s)   # list[TrackState]; also fills Detection.id

Backends (``Tracker.BACKENDS``):

* ``ul_*``: the trackers shipped in Ultralytics 8.4.163 (AGPL-3.0), driven standalone
  with our own detections: bytetrack, botsort (GMC sparseOptFlow), botsort_nogmc,
  ocsort, deepocsort (no ReID standalone, so it runs as OC-SORT plus Deep OC-SORT's
  dynamic appearance off), fasttrack, tracktrack.
* ``rf_*``: Roboflow ``trackers`` 2.6.1 (Apache-2.0): sort, bytetrack, botsort (CMC
  on), botsort_nocmc, cbiou, ocsort, mcbyte (without its SAM/Cutie mask manager).

``UltralyticsTrackPipeline`` is the convenience path. It runs detector plus tracker in
one ``model.track(persist=True)`` call, which is the only path where Ultralytics'
``with_reid: True, model: auto`` can reuse the detector's own features.

Both paths put the same motion layer (``motion.MotionEstimator``) on top. It gives
scale rate, TTC, approaching (with hysteresis) and lateral velocity. They also apply
a per-track class vote, so a track that flickers car/truck keeps one label.
"""
from __future__ import annotations

import os
import tempfile
import time
from collections import Counter, deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional

import numpy as np

os.environ.setdefault("YOLO_AUTOINSTALL", "False")

from perception.common.schemas import (  # noqa: E402
    BDD_CLASSES, VEHICLE_CLASSES, Detection, TrackState, canonical_class)
from perception.tracking.motion import MotionConfig, MotionEstimator  # noqa: E402

MOT_CLASSES = ("pedestrian", "rider", "car", "truck", "bus", "train", "motorcycle", "bicycle")
_CLS_INDEX = {c: i for i, c in enumerate(BDD_CLASSES)}


def _cls_index(name: str) -> int:
    n = canonical_class(name)
    if n not in _CLS_INDEX:
        _CLS_INDEX[n] = len(_CLS_INDEX)
    return _CLS_INDEX[n]


# ----------------------------------------------------------------------------- backends
class _UltralyticsBackend:
    """Ultralytics tracker classes fed with externally produced detections."""

    def __init__(self, tracker_type: str, frame_rate: float, track_buffer_s: float,
                 device: str, overrides: dict[str, Any]):
        from ultralytics.trackers.track import TRACKER_MAP
        from ultralytics.utils import YAML, IterableSimpleNamespace
        from ultralytics.utils.checks import check_yaml

        cfg = dict(YAML.load(check_yaml(f"{tracker_type}.yaml")))
        # Ultralytics counts track_buffer in frames and does not rescale by fps.
        cfg["track_buffer"] = max(1, int(round(track_buffer_s * frame_rate)))
        overrides = dict(overrides)
        gmc_downscale = overrides.pop("gmc_downscale", None)  # not a yaml key: GMC(downscale=2) is hard-coded
        cfg.update(overrides)
        if cfg.get("with_reid") and cfg.get("model", "auto") == "auto":
            raise ValueError("with_reid + model='auto' needs detector features; use "
                             "UltralyticsTrackPipeline, or pass model='yolo26n-cls.pt'")
        ns = IterableSimpleNamespace(**cfg)
        ns.device = device
        self.cfg = cfg
        self.impl = TRACKER_MAP[cfg["tracker_type"]](args=ns)
        if gmc_downscale and getattr(self.impl, "gmc", None) is not None:
            self.impl.gmc.downscale = max(1, int(gmc_downscale))
            cfg["gmc_downscale"] = self.impl.gmc.downscale
        self.needs_frame = str(cfg.get("gmc_method", "none")).lower() not in ("none", "") or bool(
            cfg.get("with_reid"))
        self.impl.reset_id() if hasattr(self.impl, "reset_id") else None

    def update(self, xyxy, conf, cls, frame, pts_s) -> list[tuple[int, int]]:
        from ultralytics.engine.results import Boxes

        h, w = frame.shape[:2] if frame is not None else (720, 1280)
        data = np.concatenate([xyxy, conf[:, None], cls[:, None]], 1).astype(np.float32).reshape(-1, 6)
        out = self.impl.update(Boxes(data, (h, w)), frame)
        if len(out) == 0:
            return []
        return [(int(r[7]), int(r[4])) for r in out if int(r[7]) >= 0]

    def reset(self) -> None:
        self.impl.reset()


class _RoboflowBackend:
    """Roboflow ``trackers`` (Apache-2.0)."""

    def __init__(self, name: str, frame_rate: float, track_buffer_s: float,
                 device: str, overrides: dict[str, Any]):
        import trackers as rf

        cls = {"sort": rf.SORTTracker, "bytetrack": rf.ByteTrackTracker, "botsort": rf.BoTSORTTracker,
               "cbiou": rf.CBIoUTracker, "ocsort": rf.OCSORTTracker, "mcbyte": rf.McByteTracker}[name]
        kw = dict(lost_track_buffer=max(1, int(round(track_buffer_s * 30))),  # rf rescales 30-fps frames
                  frame_rate=frame_rate)
        kw.update(overrides)
        self.impl = cls(**kw)
        self.kw = kw
        self.rgb = name == "mcbyte"
        self.needs_frame = name in ("botsort", "mcbyte") and bool(kw.get("enable_cmc", True))

    def update(self, xyxy, conf, cls, frame, pts_s) -> list[tuple[int, int]]:
        import supervision as sv

        n = len(xyxy)
        det = sv.Detections(xyxy=xyxy.astype(np.float32).reshape(-1, 4), confidence=conf.astype(np.float32),
                            class_id=cls.astype(int), data={"idx": np.arange(n)})
        img = None
        if self.needs_frame and frame is not None:
            img = np.ascontiguousarray(frame[..., ::-1]) if self.rgb else frame
        res = self.impl.update(det, frame=img, timestamp=pts_s)
        if len(res) == 0:
            return []
        return [(int(i), int(t)) for i, t in zip(res.data["idx"], res.tracker_id) if t >= 0]

    def reset(self) -> None:
        self.impl.reset()


_UL = {
    "ul_bytetrack": ("bytetrack", {}),
    "ul_botsort": ("botsort", {"gmc_method": "sparseOptFlow"}),
    "ul_botsort_nogmc": ("botsort", {"gmc_method": "none"}),
    "ul_ocsort": ("ocsort", {}),
    "ul_deepocsort": ("deepocsort", {"gmc_method": "sparseOptFlow"}),
    "ul_fasttrack": ("fasttrack", {}),
    "ul_tracktrack": ("tracktrack", {}),
}
_RF = {
    "rf_sort": ("sort", {}),
    "rf_bytetrack": ("bytetrack", {}),
    "rf_botsort": ("botsort", {"enable_cmc": True}),
    "rf_botsort_nocmc": ("botsort", {"enable_cmc": False}),
    "rf_cbiou": ("cbiou", {}),
    "rf_ocsort": ("ocsort", {}),
    "rf_mcbyte": ("mcbyte", {"enable_mask_manager": False}),
}
# Harmonised thresholds for a detector-fair comparison (YOLO26s-BDD scores are lower
# than the MOT17 YOLOX scores the Roboflow defaults were tuned for). See README.
HARMONISED = {
    "ul": {"track_high_thresh": 0.4, "track_low_thresh": 0.1, "new_track_thresh": 0.5},
    "rf_sort": {"track_activation_threshold": 0.4, "minimum_consecutive_frames": 1},
    "rf": {"track_activation_threshold": 0.5, "high_conf_det_threshold": 0.4, "minimum_consecutive_frames": 1},
    "rf_ocsort": {"high_conf_det_threshold": 0.4, "minimum_consecutive_frames": 1},
}


def harmonised_overrides(backend: str) -> dict[str, Any]:
    if backend.startswith("ul_"):
        d = dict(HARMONISED["ul"])
        if backend == "ul_tracktrack":
            d.update(track_high_thresh=0.4, track_low_thresh=0.1, new_track_thresh=0.5, min_track_len=1)
        if backend == "ul_ocsort" or backend == "ul_deepocsort":
            d.pop("track_low_thresh")
        return d
    if backend in HARMONISED:
        return dict(HARMONISED[backend])
    return dict(HARMONISED["rf"])


# ----------------------------------------------------------------------------- track book
@dataclass
class _Book:
    public_id: int
    first_frame: int
    hits: int = 0
    last_frame: int = -1
    votes: deque = field(default_factory=lambda: deque(maxlen=15))

    def vote(self) -> str:
        c: Counter = Counter()
        for name, conf in self.votes:
            c[name] += conf
        return c.most_common(1)[0][0]


class _TrackLayer:
    """Id remapping + class vote + motion, shared by both tracker paths."""

    def __init__(self, motion: Optional[MotionConfig | dict], class_vote_len: int):
        mc = motion if isinstance(motion, MotionConfig) else MotionConfig(**(motion or {}))
        self.motion = MotionEstimator(mc)
        self.class_vote_len = class_vote_len
        self.reset()

    def reset(self) -> None:
        self.books: dict[int, _Book] = {}
        self.next_id = 1
        self.frame = -1
        self.motion.reset()
        self.last_meta: dict[int, dict[str, Any]] = {}

    def build(self, matched: list[tuple[Detection, int]], t: float, shape, ego_polygon=None) -> list[TrackState]:
        self.frame += 1
        rows = []
        for det, raw in matched:
            b = self.books.get(raw)
            if b is None:
                b = self.books[raw] = _Book(self.next_id, self.frame, votes=deque(maxlen=self.class_vote_len))
                self.next_id += 1
            b.hits += 1
            b.last_frame = self.frame
            b.votes.append((canonical_class(det.cls), float(det.confidence)))
            det.id = b.public_id
            rows.append((b, det, b.vote()))
        mot = self.motion.update([(b.public_id, cls, det.bbox) for b, det, cls in rows], t, shape, ego_polygon)
        out, meta = [], {}
        for b, det, cls in rows:
            m = mot[b.public_id]
            out.append(TrackState(id=b.public_id, cls=cls, bbox=[float(v) for v in det.bbox],
                                  age_frames=self.frame - b.first_frame + 1, scale_rate=m.scale_rate,
                                  ttc_s=m.ttc_s, approaching=m.approaching, lateral_px_s=m.lateral_px_s))
            meta[b.public_id] = {"hits": b.hits, "confidence": float(det.confidence), "detClass": det.cls,
                                 "scaleRateLo": m.scale_rate_lo, "scaleRateHi": m.scale_rate_hi,
                                 "lateralWidthsPerS": m.lateral_widths_s, "closing": m.closing,
                                 "inCorridor": m.in_corridor, "nSamples": m.n_samples,
                                 "sampleRejected": m.reject_reason}
        self.last_meta = meta
        # forget books for tracks long gone (raw ids are never reused by the backends)
        if self.frame % 300 == 0:
            for raw in [r for r, b in self.books.items() if self.frame - b.last_frame > 900]:
                del self.books[raw]
        return out


def _monotonic_clock(obj, pts_s):
    """pts -> strictly increasing seconds. None -> frame count / frame_rate.

    The BDD .mov files carry duplicate / slightly non-monotonic pts in the first frames
    (seen with both OpenCV 5.0 and PyAV). Roboflow trackers *skip the whole update* on a
    backwards timestamp, so non-increasing values are nudged forward by 1/4 frame.
    """
    t = obj._n / obj.frame_rate if pts_s is None else float(pts_s)
    last = getattr(obj, "_last_t", None)
    if last is not None and t <= last:
        t = last + 0.25 / obj.frame_rate
    obj._last_t = t
    obj._n += 1
    return t


# ----------------------------------------------------------------------------- public API
class Tracker:
    """Detector-agnostic tracker: ``update(detections, frame_bgr, pts_s) -> list[TrackState]``.

    Args:
        backend: one of ``Tracker.BACKENDS`` (default ``"ul_botsort"``).
        device: only used by ReID encoders (the association itself runs on CPU).
        frame_rate: stream frame rate (5 for BDD MOT frames, 30 for the clips).
        track_buffer_s: how long a lost track is kept for re-association, in seconds.
        classes: class names to track (default: the 8 BDD MOT classes); ``"all"`` tracks
            every class it is given. Other detections pass through with ``id=None``.
        harmonised: apply the harmonised thresholds used for the BDD comparison (default True).
        motion: ``MotionConfig`` or dict of overrides for the motion layer.
        class_vote_s: time window of the per-track class vote (confidence-weighted majority
            over the observations of the last ``class_vote_s`` seconds; 0 = latest detection's class).
        **opts: raw overrides passed to the backend (Ultralytics yaml keys or Roboflow kwargs;
            ``gmc_downscale`` for the Ultralytics GMC).
    """

    BACKENDS = tuple(_UL) + tuple(_RF)

    def __init__(self, backend: str = "ul_botsort", device: str = "cuda", frame_rate: float = 30.0,
                 track_buffer_s: float = 2.0, classes: Any = MOT_CLASSES, harmonised: bool = True,
                 motion: Optional[MotionConfig | dict] = None, class_vote_s: float = 0.5, **opts):
        if backend not in self.BACKENDS:
            raise ValueError(f"unknown backend {backend!r}; choose from {self.BACKENDS}")
        self.backend_name = backend
        self.frame_rate = float(frame_rate)
        self.classes = None if classes in (None, "all") else {canonical_class(c) for c in classes}
        ov = harmonised_overrides(backend) if harmonised else {}
        if backend in _UL:
            ttype, base = _UL[backend]
            ov = {**base, **ov, **opts}
            self._be = _UltralyticsBackend(ttype, frame_rate, track_buffer_s, device, ov)
        else:
            name, base = _RF[backend]
            ov = {**base, **ov, **opts}
            self._be = _RoboflowBackend(name, frame_rate, track_buffer_s, device, ov)
        self.config = {"backend": backend, "frame_rate": frame_rate, "track_buffer_s": track_buffer_s,
                       "class_vote_s": class_vote_s, "harmonised": harmonised, "overrides": ov}
        self._layer = _TrackLayer(motion, max(1, int(round(class_vote_s * frame_rate))))
        self._n = 0
        self._last_t = None
        self.last_ms = 0.0

    @property
    def last_meta(self) -> dict[int, dict[str, Any]]:
        """Extra per-track fields for the last frame (hits, CI of scale rate, ...)."""
        return self._layer.last_meta

    @property
    def needs_frame(self) -> bool:
        """True when the backend uses the image (GMC / CMC / ReID); otherwise frame_bgr may be None."""
        return self._be.needs_frame

    def reset(self) -> None:
        """Call between unrelated videos."""
        self._be.reset()
        self._layer.reset()
        self._n = 0
        self._last_t = None

    def _clock(self, pts_s):
        return _monotonic_clock(self, pts_s)

    def update(self, detections: list[Detection], frame_bgr: Optional[np.ndarray] = None,
               pts_s: Optional[float] = None, ego_polygon=None) -> list[TrackState]:
        """Associate this frame's detections; fills ``Detection.id`` in place.

        ego_polygon: optional ego-path polygon [[x, y], ...] in pixels (plan section 13
        ``RoadGeometry.egoPathPolygon``). It gates ``approaching``; without it a static
        trapezoid is used (see motion.MotionConfig).
        """
        t0 = time.perf_counter()
        pts_s = self._clock(pts_s)
        for d in detections:
            d.id = None
        dets = [d for d in detections if self.classes is None or canonical_class(d.cls) in self.classes]
        if dets:
            xyxy = np.asarray([d.bbox for d in dets], dtype=np.float32)
            conf = np.asarray([d.confidence for d in dets], dtype=np.float32)
            cls = np.asarray([_cls_index(d.cls) for d in dets], dtype=np.float32)
        else:
            xyxy = np.zeros((0, 4), np.float32); conf = np.zeros(0, np.float32); cls = np.zeros(0, np.float32)
        pairs = self._be.update(xyxy, conf, cls, frame_bgr, float(pts_s))
        matched = [(dets[i], raw) for i, raw in pairs]
        shape = frame_bgr.shape[:2] if frame_bgr is not None else (720, 1280)
        out = self._layer.build(matched, float(pts_s), shape, ego_polygon)
        self.last_ms = (time.perf_counter() - t0) * 1000.0
        return out


class UltralyticsTrackPipeline:
    """Convenience path: detector + tracker in one ``model.track(persist=True)`` call.

    ``step(frame_bgr, pts_s) -> (list[Detection], list[TrackState])``. Ultralytics keeps
    only the tracked boxes in its Results when at least one track is active, so the
    detection list is the tracked subset (ids filled). On frames with no active track it
    is the raw detections with ``id=None``.
    """

    def __init__(self, weights: Optional[str] = None, tracker: str = "botsort", device: str = "cuda",
                 frame_rate: float = 30.0, track_buffer_s: float = 2.0, imgsz: int = 960, conf: float = 0.05,
                 half: bool = True, classes: Any = MOT_CLASSES, harmonised: bool = True,
                 motion: Optional[MotionConfig | dict] = None, class_vote_s: float = 0.5, **tracker_opts):
        from ultralytics import YOLO
        from ultralytics.utils import YAML
        from ultralytics.utils.checks import check_yaml

        from perception.tracking.detector import resolve_weights

        self.model = YOLO(resolve_weights(weights))
        self.names = {int(k): canonical_class(v) for k, v in self.model.names.items()}
        self.classes = None if classes in (None, "all") else {canonical_class(c) for c in classes}
        self.class_ids = None if self.classes is None else [k for k, v in self.names.items() if v in self.classes]
        cfg = dict(YAML.load(check_yaml(f"{tracker}.yaml")))
        cfg["track_buffer"] = max(1, int(round(track_buffer_s * frame_rate)))
        if harmonised:
            cfg.update(harmonised_overrides(f"ul_{tracker}"))
        cfg.update(tracker_opts)
        self.tracker_cfg = cfg
        fd, path = tempfile.mkstemp(prefix=f"perception_{tracker}_", suffix=".yaml")
        os.close(fd)
        YAML.save(Path(path), cfg)
        self.tracker_yaml = path
        self.device, self.imgsz, self.conf, self.half = device, imgsz, conf, (half and device != "cpu")
        self.frame_rate = frame_rate
        self._layer = _TrackLayer(motion, max(1, int(round(class_vote_s * frame_rate))))
        self._n = 0
        self._last_t = None
        self.last_ms = 0.0

    @property
    def last_meta(self) -> dict[int, dict[str, Any]]:
        return self._layer.last_meta

    def reset(self) -> None:
        p = getattr(self.model, "predictor", None)
        if p is not None and getattr(p, "trackers", None):
            for t in p.trackers:
                t.reset()
        self._layer.reset()
        self._n = 0
        self._last_t = None

    def _clock(self, pts_s):
        return _monotonic_clock(self, pts_s)

    def step(self, frame_bgr: np.ndarray, pts_s: Optional[float] = None,
             ego_polygon=None) -> tuple[list[Detection], list[TrackState]]:
        from perception.tracking.detector import results_to_detections

        t0 = time.perf_counter()
        pts_s = self._clock(pts_s)
        r = self.model.track(frame_bgr, persist=True, tracker=self.tracker_yaml, imgsz=self.imgsz,
                             conf=self.conf, device=self.device, quantize=16 if self.half else None, classes=self.class_ids,
                             verbose=False)[0]
        tracked = results_to_detections(r, self.names)
        raw_pairs = []
        for d in tracked:
            raw = d.id
            d.id = None
            if raw is not None:
                raw_pairs.append((d, raw))
        out = self._layer.build(raw_pairs, float(pts_s), frame_bgr.shape[:2], ego_polygon)
        self.last_ms = (time.perf_counter() - t0) * 1000.0
        return tracked, out

    def close(self) -> None:
        try:
            os.remove(self.tracker_yaml)
        except OSError:
            pass
