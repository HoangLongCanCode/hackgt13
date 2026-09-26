"""Plan section 7 (Vehicle Detection): one detector API over two backends.

    from perception.detection import Detector
    det = Detector()                                   # default: BDD100K YOLO26s @ 960, conf 0.25
    dets = det.detect(frame_bgr)                       # -> list[perception.common.schemas.Detection]

Backends
    backend="ultralytics"  Ultralytics YOLO (.pt).         Code + weights AGPL-3.0.
    backend="rfdetr"       Roboflow RF-DETR (rfdetr pkg).  Code + weights Apache-2.0.

`weights` is either a preset name from PRESETS or a path to a checkpoint.
Every output class is a canonical BDD100K det_20 name (schemas.BDD_CLASSES):
  * BDD-trained checkpoints use the 2018 names (person/motor/bike); they are
    normalised with schemas.canonical_class.
  * COCO checkpoints are mapped with COCO_TO_BDD. COCO has no 'rider', and
    COCO 'stop sign' is only a small part of BDD 'traffic sign'. All other
    COCO classes are dropped. `rider_heuristic=True` relabels a COCO person
    sitting on a bicycle/motorcycle box as 'rider' (deterministic geometry rule).

Output is deterministic for fixed weights / thresholds / input (no LLM; plan
sections 16 and 38). Boxes are [x1, y1, x2, y2] floats in source-frame pixels.
"""
from __future__ import annotations

import os
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional

import numpy as np

from perception.common.schemas import BDD_CLASSES, Detection, canonical_class
from perception.common.video import MODELS_ROOT

os.environ.setdefault("YOLO_AUTOINSTALL", "False")  # never let Ultralytics pip-install into the shared venv

DET_MODELS = MODELS_ROOT / "detection"

# COCO name -> canonical BDD name. Anything not listed is dropped.
COCO_TO_BDD = {
    "person": "pedestrian",
    "bicycle": "bicycle",
    "car": "car",
    "motorcycle": "motorcycle",
    "bus": "bus",
    "train": "train",
    "truck": "truck",
    "traffic light": "traffic light",
    "stop sign": "traffic sign",  # partial: BDD 'traffic sign' covers every sign face
}
# BDD classes a COCO model can express fully (the fair comparison subset).
COCO_MAPPABLE_BDD = ["pedestrian", "car", "truck", "bus", "train", "motorcycle", "bicycle", "traffic light"]

_BDD_SET = set(BDD_CLASSES)


@dataclass(frozen=True)
class Preset:
    backend: str
    label_space: str            # "bdd" | "coco"
    imgsz: int                  # evaluated / native inference size
    hf_repo: Optional[str] = None
    hf_file: Optional[str] = None
    local: Optional[str] = None  # relative to models/detection
    url: Optional[str] = None    # original download URL (for MODELS.md / auto-fetch)
    rfdetr_variant: Optional[str] = None
    deploy_conf: float = 0.25   # default score threshold (coarse F1 sweep on the eval subset, see README)
    notes: str = ""


PRESETS: dict[str, Preset] = {
    "bdd-yolo26s": Preset("ultralytics", "bdd", 960, hf_repo="dronefreak/bdd100k-yolo26s", hf_file="best.pt",
                          url="https://huggingface.co/dronefreak/bdd100k-yolo26s/resolve/main/best.pt",
                          notes="YOLO26s fine-tuned on BDD100K (2018 labels), imgsz 960. DEFAULT."),
    "bdd-yolo26n": Preset("ultralytics", "bdd", 960, hf_repo="dronefreak/bdd100k-yolo26n", hf_file="best.pt",
                          url="https://huggingface.co/dronefreak/bdd100k-yolo26n/resolve/main/best.pt"),
    "bdd-rfdetr-nano": Preset("rfdetr", "bdd", 576, hf_repo="dronefreak/bdd100k-rfdetr-nano",
                              hf_file="checkpoint_best_total.pth", rfdetr_variant="nano", deploy_conf=0.40,
                              url="https://huggingface.co/dronefreak/bdd100k-rfdetr-nano/resolve/main/checkpoint_best_total.pth",
                              notes="RF-DETR-N fine-tuned on BDD100K at resolution 576 (square resize)."),
    "coco-yolo26s": Preset("ultralytics", "coco", 640, local="yolo26s.pt",
                           url="https://github.com/ultralytics/assets/releases/download/v8.4.0/yolo26s.pt"),
    "coco-rfdetr-nano": Preset("rfdetr", "coco", 384, local="rfdetr/rf-detr-nano.pth", rfdetr_variant="nano", deploy_conf=0.35,
                               url="https://storage.googleapis.com/rfdetr/nano_coco/checkpoint_best_regular.pth"),
    "coco-rfdetr-small": Preset("rfdetr", "coco", 512, local="rfdetr/rf-detr-small.pth", rfdetr_variant="small", deploy_conf=0.30,
                                url="https://storage.googleapis.com/rfdetr/small_coco/checkpoint_best_regular.pth"),
}
DEFAULT_PRESET = "bdd-yolo26s"
# Default preset when only `backend` is given (review fix: backend="rfdetr" used to silently load YOLO).
DEFAULT_PRESET_BY_BACKEND = {"ultralytics": "bdd-yolo26s", "rfdetr": "bdd-rfdetr-nano"}


def resolve_weights(preset: Preset, allow_download: bool = True) -> Path:
    """Local path of a preset's checkpoint (HF cache or models/detection)."""
    if preset.hf_repo:
        from huggingface_hub import hf_hub_download
        try:
            return Path(hf_hub_download(preset.hf_repo, preset.hf_file, local_files_only=True))
        except Exception:
            if not allow_download:
                raise
            return Path(hf_hub_download(preset.hf_repo, preset.hf_file))
    path = DET_MODELS / preset.local
    if not path.exists():
        if not allow_download:
            raise FileNotFoundError(path)
        import urllib.request
        path.parent.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(preset.url, path)
    return path


def _lower_half_overlap(person: np.ndarray, wheel: np.ndarray) -> float:
    """Fraction of the person box's lower half covered by the two-wheeler box."""
    x1, y1, x2, y2 = person
    ymid = (y1 + y2) / 2.0
    ix1, iy1 = max(x1, wheel[0]), max(ymid, wheel[1])
    ix2, iy2 = min(x2, wheel[2]), min(y2, wheel[3])
    inter = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
    area = max(1e-6, (x2 - x1) * (y2 - ymid))
    return inter / area


def apply_rider_heuristic(boxes: np.ndarray, names: list[str], min_overlap: float = 0.3) -> list[str]:
    """Relabel 'pedestrian' -> 'rider' when the person's lower half sits on a bicycle/motorcycle box
    and the person's horizontal centre lies inside that box. Pure geometry, deterministic."""
    out = list(names)
    wheels = [i for i, n in enumerate(names) if n in ("bicycle", "motorcycle")]
    if not wheels:
        return out
    for i, n in enumerate(names):
        if n != "pedestrian":
            continue
        cx = (boxes[i, 0] + boxes[i, 2]) / 2.0
        for j in wheels:
            if boxes[j, 0] <= cx <= boxes[j, 2] and _lower_half_overlap(boxes[i], boxes[j]) >= min_overlap:
                out[i] = "rider"
                break
    return out


@dataclass
class RawDetections:
    """Backend output before class mapping (kept for evaluation and debugging)."""
    xyxy: np.ndarray                 # (N, 4) float32, source pixels
    scores: np.ndarray               # (N,)
    source_names: list[str]          # checkpoint's own class names
    timings_ms: dict[str, float] = field(default_factory=dict)


class Detector:
    """Per-frame 2D object detector returning plan-section-7 Detection objects.

    Args:
        backend: "ultralytics", "rfdetr" or None. With a preset name, None takes the preset's backend and a
                 conflicting explicit backend raises ValueError. With weights=None it picks the backend's default
                 preset (DEFAULT_PRESET_BY_BACKEND: ultralytics -> bdd-yolo26s, rfdetr -> bdd-rfdetr-nano).
                 With a checkpoint path, None means "ultralytics".
        weights: preset name (see PRESETS) or a checkpoint path. Default: the backend's default preset
                 ("bdd-yolo26s" when backend is None).
        imgsz: inference size. YOLO: long side (letterboxed rect, e.g. 960 -> 960x544 for 16:9).
               RF-DETR: square resolution (must be divisible by patch*windows = 32). Default: preset's native size.
        conf: score threshold. None = the preset's deploy_conf (0.25 for YOLO presets, 0.30-0.40 for RF-DETR,
              whose scores run lower-precision at 0.25); 0.25 for a custom checkpoint. The eval script uses 0.001.
        iou: NMS IoU (Ultralytics only; RF-DETR is NMS-free).
        device: "cuda" | "cuda:0" | "cpu".
        half: FP16 inference (Ultralytics quantize=16; RF-DETR traced FP16 model). Default True on CUDA.
        max_det: max boxes per frame.
        classes: optional iterable of canonical BDD names to keep (e.g. {"car", "truck", "bus"}).
        rider_heuristic: COCO models only, relabel persons on two-wheelers as 'rider'. Default False.
        nms: Ultralytics only. None = one-to-many head + NMS (default); False = YOLO26 NMS-free head.
        allow_download: fetch missing preset weights from their original URL (HF / GitHub / GCS).
        optimize: RF-DETR only, trace the model with RFDETR.inference() (FP16 if half). Default True.
    """

    def __init__(self, backend: Optional[str] = None, weights: str | os.PathLike | None = None,
                 imgsz: Optional[int] = None, conf: Optional[float] = None, device: str = "cuda", *,
                 iou: float = 0.7, half: Optional[bool] = None, max_det: int = 300,
                 classes: Optional[set[str] | list[str]] = None, rider_heuristic: bool = False,
                 nms: Optional[bool] = None, allow_download: bool = True, optimize: bool = True,
                 **opts: Any):
        import torch  # imported before any onnxruntime session elsewhere (CUDA EP DLL order)

        if backend is not None and backend not in DEFAULT_PRESET_BY_BACKEND:
            raise ValueError(f"unknown backend {backend!r} (use one of {', '.join(DEFAULT_PRESET_BY_BACKEND)})")
        weights = weights or DEFAULT_PRESET_BY_BACKEND.get(backend or "ultralytics", DEFAULT_PRESET)
        self.preset_name: Optional[str] = None
        preset: Optional[Preset] = PRESETS.get(str(weights))
        if preset is not None:
            if backend is not None and backend != preset.backend:
                raise ValueError(f"preset {str(weights)!r} runs on backend {preset.backend!r}, not {backend!r}")
            self.preset_name = str(weights)
            backend = preset.backend
            self.weights_path = resolve_weights(preset, allow_download=allow_download)
            self.label_space: Optional[str] = preset.label_space
            rfdetr_variant = preset.rfdetr_variant
            imgsz = imgsz or preset.imgsz
            conf = preset.deploy_conf if conf is None else conf
        else:
            backend = backend or "ultralytics"
            self.weights_path = Path(weights)
            if not self.weights_path.exists():
                raise FileNotFoundError(f"{weights!r} is neither a preset ({', '.join(PRESETS)}) nor a file")
            self.label_space = None  # inferred from checkpoint names below
            rfdetr_variant = opts.pop("rfdetr_variant", "nano")
            conf = 0.25 if conf is None else conf
        if backend not in ("ultralytics", "rfdetr"):
            raise ValueError(f"unknown backend {backend!r}")

        self.backend = backend
        self.device = device
        self.conf = float(conf)
        self.iou = float(iou)
        self.max_det = int(max_det)
        self.half = (str(device).startswith("cuda")) if half is None else bool(half)
        self.classes = set(classes) if classes else None
        self.rider_heuristic = bool(rider_heuristic)
        self.nms = nms
        self.opts = opts
        self.last_source_classes: list[str] = []   # checkpoint class names of the last detect() output
        self.last_timings_ms: dict[str, float] = {}

        if backend == "ultralytics":
            from ultralytics import YOLO
            self.model = YOLO(str(self.weights_path))
            self.source_names = [self.model.names[i] for i in range(len(self.model.names))]
            self.imgsz = int(imgsz or 960)
        else:
            import rfdetr
            cls = {"nano": rfdetr.RFDETRNano, "small": rfdetr.RFDETRSmall, "medium": rfdetr.RFDETRMedium,
                   "base": rfdetr.RFDETRBase, "large": rfdetr.RFDETRLarge}[rfdetr_variant]
            kw: dict[str, Any] = dict(pretrain_weights=str(self.weights_path), device=str(device))
            if self.label_space == "bdd":
                kw["num_classes"] = 10
            if imgsz:
                kw["resolution"] = int(imgsz)
            self.model = cls(**kw)
            self.imgsz = int(self.model.model.resolution)
            self.source_names = list(self.model.class_names)
            if optimize and str(device).startswith("cuda"):
                self.model.inference(dtype=torch.float16 if self.half else torch.float32)

        if self.label_space is None:
            self.label_space = "bdd" if {canonical_class(n) for n in self.source_names} <= _BDD_SET else "coco"
        self._map = self._build_map()

    # ------------------------------------------------------------------ mapping
    def _build_map(self) -> dict[str, str]:
        if self.label_space == "bdd":
            return {n: canonical_class(n) for n in self.source_names}
        return {n: COCO_TO_BDD[n] for n in self.source_names if n in COCO_TO_BDD}

    def map_names(self, source_names: list[str], xyxy: Optional[np.ndarray] = None,
                  rider_heuristic: Optional[bool] = None) -> list[Optional[str]]:
        """Checkpoint names -> canonical BDD names (None = dropped)."""
        mapped = [self._map.get(n) for n in source_names]
        use_rider = self.rider_heuristic if rider_heuristic is None else rider_heuristic
        if use_rider and self.label_space == "coco" and xyxy is not None and len(mapped):
            keep = [i for i, m in enumerate(mapped) if m is not None]
            relabeled = apply_rider_heuristic(xyxy[keep], [mapped[i] for i in keep])
            for i, m in zip(keep, relabeled):
                mapped[i] = m
        return mapped

    # ---------------------------------------------------------------- inference
    def detect_raw(self, image_bgr: np.ndarray) -> RawDetections:
        """Run the backend and return unmapped boxes in source pixels."""
        t0 = time.perf_counter()
        if self.backend == "ultralytics":
            kw: dict[str, Any] = dict(imgsz=self.imgsz, conf=self.conf, iou=self.iou, device=self.device,
                                      max_det=self.max_det, verbose=False)
            if self.half:
                kw["quantize"] = 16
            if self.nms is not None:
                kw["nms"] = self.nms
            r = self.model.predict(image_bgr, **kw)[0]
            b = r.boxes
            xyxy = b.xyxy.cpu().numpy().astype(np.float32)
            scores = b.conf.cpu().numpy().astype(np.float32)
            names = [self.source_names[int(c)] for c in b.cls.cpu().numpy()]
            timings = {k: float(v) for k, v in r.speed.items()}
        else:
            rgb = np.ascontiguousarray(image_bgr[:, :, ::-1])
            d = self.model.predict(rgb, threshold=self.conf, include_source_image=False)
            order = np.argsort(-d.confidence)[: self.max_det]
            xyxy = d.xyxy[order].astype(np.float32)
            scores = d.confidence[order].astype(np.float32)
            if "class_name" in d.data:
                names = [str(n) for n in np.asarray(d.data["class_name"])[order]]
            else:  # fine-tuned checkpoints: 0-based index into class_names
                names = [self.source_names[int(c)] for c in d.class_id[order]]
            timings = {}
        h, w = image_bgr.shape[:2]
        np.clip(xyxy[:, 0::2], 0, w, out=xyxy[:, 0::2])
        np.clip(xyxy[:, 1::2], 0, h, out=xyxy[:, 1::2])
        timings["total"] = (time.perf_counter() - t0) * 1000.0
        return RawDetections(xyxy, scores, names, timings)

    def detect(self, image_bgr: np.ndarray) -> list[Detection]:
        """Detect objects in one upright BGR uint8 frame. Returns canonical-BDD-class Detections."""
        t0 = time.perf_counter()
        raw = self.detect_raw(image_bgr)
        mapped = self.map_names(raw.source_names, raw.xyxy)
        out: list[Detection] = []
        src: list[str] = []
        for box, score, name, sname in zip(raw.xyxy, raw.scores, mapped, raw.source_names):
            if name is None or (self.classes is not None and name not in self.classes):
                continue
            out.append(Detection(cls=name, bbox=[float(v) for v in box], confidence=float(score)))
            src.append(sname)
        self.last_source_classes = src
        self.last_timings_ms = dict(raw.timings_ms, detect_total=(time.perf_counter() - t0) * 1000.0)
        return out

    __call__ = detect

    def warmup(self, n: int = 3, shape: tuple[int, int] = (720, 1280)) -> None:
        img = np.full((*shape, 3), 114, np.uint8)
        for _ in range(n):
            self.detect_raw(img)

    def describe(self) -> dict[str, Any]:
        return {"preset": self.preset_name, "backend": self.backend, "weights": str(self.weights_path),
                "label_space": self.label_space, "imgsz": self.imgsz, "conf": self.conf, "iou": self.iou,
                "half": self.half, "max_det": self.max_det, "nms": self.nms,
                "rider_heuristic": self.rider_heuristic, "source_names": self.source_names}

    def close(self) -> None:
        """Drop the model and release cached GPU memory."""
        import gc
        import torch
        self.model = None
        gc.collect()
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
