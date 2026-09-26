"""Thin, self-contained detector wrapper used by the tracking block (plan section 8).

The detection block (section 7) is built separately. This file only exists so the
tracker can be tested and evaluated on its own, with no import from
``perception.detection``. The integrator should feed the section 7 detector's output
into ``Tracker.update`` instead.

Default weights: ``dronefreak/bdd100k-yolo26s`` (Ultralytics YOLO26s fine-tuned on
BDD100K, 10 classes, trained at imgsz 960). See MODELS.md for licenses.
"""
from __future__ import annotations

import os
import time
from pathlib import Path
from typing import Optional

import numpy as np

os.environ.setdefault("YOLO_AUTOINSTALL", "False")

from perception.common.schemas import Detection, canonical_class  # noqa: E402

HF_REPO = "dronefreak/bdd100k-yolo26s"
HF_FILE = "best.pt"
HF_REVISION = "b11da17c1149b60c0f067eee6588f1780507e064"  # snapshot evaluated here


def resolve_weights(weights: Optional[str] = None) -> str:
    """Return a local path for the detector weights (downloads to the HF cache if needed)."""
    if weights and Path(weights).exists():
        return str(weights)
    from huggingface_hub import hf_hub_download

    repo = weights or HF_REPO
    rev = HF_REVISION if repo == HF_REPO else None
    return hf_hub_download(repo, HF_FILE, revision=rev)


class YoloDetector:
    """Ultralytics YOLO -> list[Detection] with canonical BDD class names."""

    def __init__(self, weights: Optional[str] = None, device: str = "cuda", imgsz: int = 960,
                 conf: float = 0.05, iou: float = 0.7, half: bool = True,
                 classes: Optional[set[str]] = None):
        from ultralytics import YOLO

        self.weights = resolve_weights(weights)
        self.model = YOLO(self.weights)
        self.device = device
        self.imgsz = imgsz
        self.conf = conf
        self.iou = iou
        self.half = half and device != "cpu"
        self.names = {int(k): canonical_class(v) for k, v in self.model.names.items()}
        self.keep = classes
        self.last_ms = 0.0

    def __call__(self, frame_bgr: np.ndarray) -> list[Detection]:
        t0 = time.perf_counter()
        r = self.model.predict(frame_bgr, imgsz=self.imgsz, conf=self.conf, iou=self.iou,
                               device=self.device, quantize=16 if self.half else None, verbose=False)[0]
        self.last_ms = (time.perf_counter() - t0) * 1000.0
        return results_to_detections(r, self.names, self.keep)


def results_to_detections(r, names: dict[int, str], keep: Optional[set[str]] = None) -> list[Detection]:
    """Convert one Ultralytics Results object to list[Detection] (id filled when tracked)."""
    b = r.boxes
    if b is None or len(b) == 0:
        return []
    xyxy = b.xyxy.cpu().numpy()
    conf = b.conf.cpu().numpy()
    cls = b.cls.cpu().numpy().astype(int)
    ids = b.id.cpu().numpy().astype(int) if b.id is not None else None
    out = []
    for i in range(len(xyxy)):
        name = names.get(int(cls[i]), str(int(cls[i])))
        if keep is not None and name not in keep:
            continue
        out.append(Detection(cls=name, bbox=[float(v) for v in xyxy[i]], confidence=float(conf[i]),
                             id=int(ids[i]) if ids is not None else None))
    return out
