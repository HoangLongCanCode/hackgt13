"""VisionInput implementations (plan §3.4): the rest of the system should not
care whether frames come from a video file or real glasses.

BDD100K .mov files are coded 720x1280 (portrait) with a +/-90 degree display
matrix. OpenCV's FFmpeg backend applies it by default
(CAP_PROP_ORIENTATION_AUTO=1), so frames come out upright at 1280x720.
PyAV / raw decoders do NOT rotate - use this reader.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Iterator, Optional, Protocol

import cv2
import numpy as np

# Folder layout lives in perception.common.paths (env overrides: KSR_MODELS_DIR, KSR_DATA_DIR, KSR_OUTPUTS_DIR).
from perception.common.paths import DATA_ROOT, MODELS_ROOT, OUTPUTS_ROOT, PROJECT_ROOT  # noqa: E402,F401


@dataclass
class Frame:
    index: int
    pts_s: float          # presentation time in seconds (use this to sync with HTML5 video)
    image: np.ndarray     # BGR uint8, HxWx3, upright


class VisionInput(Protocol):
    def get_frame(self) -> Optional[Frame]: ...


class VideoFileInput:
    """Reads an upright BGR frame sequence from a video file."""

    def __init__(self, path: str | Path, start_s: float = 0.0,
                 max_frames: Optional[int] = None, stride: int = 1):
        self.path = str(path)
        self.cap = cv2.VideoCapture(self.path)
        if not self.cap.isOpened():
            raise IOError(f"cannot open {self.path}")
        self.cap.set(cv2.CAP_PROP_ORIENTATION_AUTO, 1)
        self.fps = self.cap.get(cv2.CAP_PROP_FPS) or 30.0
        self.frame_count = int(self.cap.get(cv2.CAP_PROP_FRAME_COUNT))
        if start_s > 0:
            self.cap.set(cv2.CAP_PROP_POS_MSEC, start_s * 1000.0)
        self._index = int(round(start_s * self.fps))
        self._emitted = 0
        self.max_frames = max_frames
        self.stride = max(1, stride)

    def get_frame(self) -> Optional[Frame]:
        if self.max_frames is not None and self._emitted >= self.max_frames:
            return None
        while True:
            pts_ms = self.cap.get(cv2.CAP_PROP_POS_MSEC)
            ok, img = self.cap.read()
            if not ok:
                return None
            idx = self._index
            self._index += 1
            if idx % self.stride == 0:
                self._emitted += 1
                return Frame(idx, pts_ms / 1000.0, img)

    def __iter__(self) -> Iterator[Frame]:
        while (f := self.get_frame()) is not None:
            yield f

    def release(self) -> None:
        self.cap.release()


def primary_videos() -> list[Path]:
    """The 7 scenario clips with BDD100K MOT labels (see data/bdd100k/MANIFEST.md; fetched by scripts/fetch_bdd_samples.py)."""
    return sorted((DATA_ROOT / "videos" / "val").glob("*.mov"))


def all_videos() -> list[Path]:
    return primary_videos() + sorted((DATA_ROOT / "videos" / "val_extra").glob("*.mov"))
