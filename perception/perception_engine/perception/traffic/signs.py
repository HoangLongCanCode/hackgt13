"""Traffic-sign typing (plan section 12) -> list[TrafficSign].

    from perception.traffic.signs import SignRecognizer
    rec = SignRecognizer(backend="lisa_crops")                  # needs BDD 'traffic sign' boxes
    signs = rec.recognize(frame_bgr, sign_detections, pts_s=t)  # Detection objects (id = track id)

Backends:
  lisa_crops  cvtechniques/TrafficSignDetection (YOLO11n fine-tuned on a LISA US subset:
              stop, yield, doNotEnter, pedestrianCrossing, speedLimit15..65) run on square
              context crops (crop_scale x the box, min min_crop px, resized to imgsz_crop)
              around each BDD 'traffic sign' box from the shared detector. A LISA box that
              overlaps the BDD box types it. Otherwise the sign is returned as 'unknown'.
  lisa_full   the same LISA model on the whole frame (imgsz_full). No BDD boxes needed.
  coco_stop   COCO YOLO26s class 'stop sign' on the whole frame (stop signs only).

OCR verification (ocr='pp_ocrv6_small', default for the LISA backends): PaddlePaddle
PP-OCRv6_small_rec ONNX (Apache-2.0) reads text bands of the upscaled sign crop.
  speed_limit_*  the value comes from OCR (regex [1-8][05]). LISA's own number is not used.
                 With ocr_strict_speed=True (default), no confirmed number -> 'unknown', and
                 readable non-speed text (e.g. 'NO TURN', 'ARROW') also -> 'unknown'.
  stop           'STOP' read -> confirmed. 'ENTER'/'NOT' read -> do_not_enter. Unreadable -> kept.
Per track, OCR re-runs at most every ocr_every frames and its reads are voted.

signClass values: stop, yield, do_not_enter, pedestrian_crossing, speed_limit_<N>, unknown.
Not covered by any backend here: exit, merge, construction, road work (see README).
Per-track majority vote over the last `vote_n` reads when detections carry track ids.
distanceMeters: pinhole size prior with MUTCD conventional sizes and an ASSUMED focal
length (BDD100K publishes no intrinsics). Coarse; informational only (plan section 38).

Licenses: Ultralytics code + weights are AGPL-3.0. The LISA-derived weights inherit
the LISA academic license, despite the HF card's 'mit' tag. Research / demo only.
"""
from __future__ import annotations

import math
import re
import time
from collections import Counter, defaultdict, deque
from pathlib import Path
from typing import Any, Iterable, Optional, Sequence

import numpy as np

from perception.common.schemas import Detection, TrafficSign
from perception.common.video import MODELS_ROOT

LISA_REPO = "cvtechniques/TrafficSignDetection"
LISA_REV = "1daf1b3e802c151c32f152590b7b1e9e765380b4"
COCO_YOLO26S_URL = "https://github.com/ultralytics/assets/releases/download/v8.4.0/yolo26s.pt"
COCO_STOP_ID = 11
OCR_REPO = "PaddlePaddle/PP-OCRv6_small_rec_onnx"
OCR_REV = "b8f84f0b80c529de40b4fbb3544b84fa7233a513"
SPEED_RE = re.compile(r"(?<!\d)([1-8][05])(?!\d)")
DEFAULT_FOCAL_PX = 1000.0

# MUTCD 2009 Table 2B-1 conventional-road sizes (meters). Value = (dimension, which box side).
# A diamond W11-2 (30 in side) has a bounding box of about 30*sqrt(2) in = 1.08 m.
SIGN_SIZE_M = {
    "stop": (0.762, "w"), "yield": (0.914, "w"), "do_not_enter": (0.762, "w"),
    "speed_limit": (0.762, "h"), "pedestrian_crossing": (1.08, "w"), "unknown": (0.75, "max"),
}


def lisa_to_plan(name: str) -> str:
    if name.startswith("speedLimit"):
        return f"speed_limit_{name[len('speedLimit'):]}"
    return {"doNotEnter": "do_not_enter", "pedestrianCrossing": "pedestrian_crossing",
            "stop": "stop", "yield": "yield"}.get(name, "unknown")


def sign_distance_m(bbox: Sequence[float], sign_class: str, focal_px: float = DEFAULT_FOCAL_PX) -> Optional[float]:
    x1, y1, x2, y2 = bbox[:4]
    w, h = x2 - x1, y2 - y1
    key = "speed_limit" if sign_class.startswith("speed_limit") else sign_class
    size, side = SIGN_SIZE_M.get(key, SIGN_SIZE_M["unknown"])
    px = {"w": w, "h": h, "max": max(w, h)}[side]
    if px < 3:
        return None
    return float(focal_px * size / px)


def _iou(a, b) -> float:
    ix1, iy1, ix2, iy2 = max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])
    inter = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
    ua = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / ua if ua > 0 else 0.0


def _as_box_and_id(d: Any) -> tuple[list[float], Optional[int], float]:
    if isinstance(d, Detection):
        return list(d.bbox), d.id, float(d.confidence)
    if isinstance(d, dict):
        return list(d["bbox"]), d.get("id"), float(d.get("confidence", 1.0))
    return list(d[:4]), None, 1.0


def lisa_weights() -> str:
    from huggingface_hub import hf_hub_download
    return hf_hub_download(LISA_REPO, "best.pt", revision=LISA_REV)


def coco_yolo26s_weights() -> str:
    p = MODELS_ROOT / "traffic" / "yolo26s.pt"
    if not p.exists():
        import urllib.request
        p.parent.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(COCO_YOLO26S_URL, str(p))
    return str(p)


class PPOCRRec:
    """PP-OCRv6 text-line recognizer (ONNX, CTC). read(bgr) -> (text, mean char prob)."""

    def __init__(self, device: str = "cuda", repo: str = OCR_REPO, rev: str = OCR_REV):
        import torch  # noqa: F401  (CUDA DLLs for ORT)
        import onnxruntime as ort
        import yaml
        from huggingface_hub import hf_hub_download
        onnx_path = hf_hub_download(repo, "inference.onnx", revision=rev)
        cfg = yaml.safe_load(open(hf_hub_download(repo, "inference.yml", revision=rev), encoding="utf-8"))
        self.chars = ["<blank>"] + list(cfg["PostProcess"]["character_dict"]) + [" "]
        so = ort.SessionOptions()
        so.log_severity_level = 3
        prov = ["CUDAExecutionProvider", "CPUExecutionProvider"] if device.startswith("cuda") else ["CPUExecutionProvider"]
        self.sess = ort.InferenceSession(onnx_path, so, providers=prov)
        self.inp = self.sess.get_inputs()[0].name
        self.providers = self.sess.get_providers()

    def read(self, img_bgr: np.ndarray) -> tuple[str, float]:
        import cv2
        h, w = img_bgr.shape[:2]
        if h < 2 or w < 2:
            return "", 0.0
        W = min(320, max(16, int(math.ceil(48 * w / h))))
        x = (cv2.resize(img_bgr, (W, 48)).astype(np.float32) / 255.0 - 0.5) / 0.5  # PaddleOCR RecResizeImg
        pad = np.zeros((48, max(W, 64), 3), np.float32)
        pad[:, :W] = x
        out = self.sess.run(None, {self.inp: pad.transpose(2, 0, 1)[None]})[0][0]
        idx, prob = out.argmax(1), out.max(1)
        txt, ps, prev = [], [], -1
        for i, pr in zip(idx, prob):
            if i != prev and i != 0:
                txt.append(self.chars[i] if i < len(self.chars) else "")
                ps.append(float(pr))
            prev = i
        return "".join(txt), (float(np.mean(ps)) if ps else 0.0)


class SignRecognizer:
    def __init__(self, backend: str = "lisa_crops", device: str = "cuda", conf: float = 0.35,
                 imgsz_full: int = 1280, imgsz_crop: int = 320, crop_scale: float = 4.0, min_crop: int = 128,
                 min_box: float = 10.0, match_iou: float = 0.3, focal_px: float = DEFAULT_FOCAL_PX,
                 vote_n: int = 5, emit_unknown: bool = True, weights: Optional[str] = None,
                 ocr: Optional[str] = "pp_ocrv6_small", ocr_strict_speed: bool = True, ocr_every: int = 5,
                 ocr_min_h: float = 16.0):
        import os
        os.environ.setdefault("YOLO_AUTOINSTALL", "False")
        from ultralytics import YOLO

        if backend not in ("lisa_crops", "lisa_full", "coco_stop"):
            raise ValueError(f"unknown backend {backend!r}")
        self.backend = backend
        self.device = 0 if device.startswith("cuda") else "cpu"
        self.conf, self.imgsz_full, self.imgsz_crop = conf, imgsz_full, imgsz_crop
        self.crop_scale, self.min_crop, self.min_box, self.match_iou = crop_scale, min_crop, min_box, match_iou
        self.focal_px, self.emit_unknown = focal_px, emit_unknown
        self.weights = weights or (coco_yolo26s_weights() if backend == "coco_stop" else lisa_weights())
        self.model = YOLO(self.weights)
        self.names = self.model.names
        self.ocr = PPOCRRec(device) if (ocr and backend != "coco_stop") else None
        self.ocr_strict_speed, self.ocr_every, self.ocr_min_h = ocr_strict_speed, ocr_every, ocr_min_h
        self.ocr_cache: dict[int, tuple[int, str, float]] = {}
        self.last_debug: list[dict] = []  # per typed sign: raw LISA class, OCR text, final class
        self.votes: dict[int, deque] = defaultdict(lambda: deque(maxlen=vote_n))
        self.last_seen: dict[int, float] = {}
        self._frame_i = 0
        self.last_timing_ms = 0.0

    # ------------------------------------------------------------------ raw reads
    def _full_frame(self, frame: np.ndarray) -> list[tuple[list[float], str, float]]:
        kw = dict(imgsz=self.imgsz_full, conf=self.conf, device=self.device, verbose=False)
        if self.backend == "coco_stop":
            kw["classes"] = [COCO_STOP_ID]
        r = self.model.predict(frame, **kw)[0]
        out = []
        for b, c, s in zip(r.boxes.xyxy.cpu().numpy(), r.boxes.cls.cpu().numpy(), r.boxes.conf.cpu().numpy()):
            name = "stop" if self.backend == "coco_stop" else lisa_to_plan(self.names[int(c)])
            out.append(([float(v) for v in b], name, float(s)))
        return out

    def _crop_window(self, frame: np.ndarray, box: Sequence[float]) -> tuple[int, int, int]:
        H, W = frame.shape[:2]
        x1, y1, x2, y2 = box
        side = int(round(max(self.min_crop, self.crop_scale * max(x2 - x1, y2 - y1))))
        side = min(side, H, W)
        cx, cy = (x1 + x2) / 2, (y1 + y2) / 2
        ox = int(min(max(0, round(cx - side / 2)), W - side))
        oy = int(min(max(0, round(cy - side / 2)), H - side))
        return ox, oy, side

    def _typed_boxes(self, frame: np.ndarray, boxes: list[list[float]]) -> list[tuple[str, float]]:
        """Type each BDD sign box with LISA run on a context crop; ('unknown', 0) if no match."""
        res: list[tuple[str, float]] = [("unknown", 0.0)] * len(boxes)
        idx, crops, wins = [], [], []
        for i, b in enumerate(boxes):
            if min(b[2] - b[0], b[3] - b[1]) < self.min_box:
                continue
            ox, oy, side = self._crop_window(frame, b)
            crops.append(frame[oy:oy + side, ox:ox + side])
            wins.append((ox, oy))
            idx.append(i)
        if not crops:
            return res
        outs = self.model.predict(crops, imgsz=self.imgsz_crop, conf=self.conf, device=self.device, verbose=False)
        for i, (ox, oy), r in zip(idx, wins, outs):
            best, best_s = None, 0.0
            for b, c, s in zip(r.boxes.xyxy.cpu().numpy(), r.boxes.cls.cpu().numpy(), r.boxes.conf.cpu().numpy()):
                g = [b[0] + ox, b[1] + oy, b[2] + ox, b[3] + oy]
                bb = boxes[i]
                cxy = ((g[0] + g[2]) / 2, (g[1] + g[3]) / 2)
                inside = bb[0] <= cxy[0] <= bb[2] and bb[1] <= cxy[1] <= bb[3]
                if (_iou(g, bb) >= self.match_iou or inside) and s > best_s:
                    best, best_s = lisa_to_plan(self.names[int(c)]), float(s)
            if best:
                res[i] = (best, best_s)
        return res

    def _ocr_bands(self, frame: np.ndarray, box: Sequence[float]) -> dict[str, tuple[str, float]]:
        import cv2
        H, W = frame.shape[:2]
        x1, y1, x2, y2 = box[:4]
        bw, bh = x2 - x1, y2 - y1
        xa, ya = max(0, int(x1 - 0.05 * bw)), max(0, int(y1 - 0.05 * bh))
        xb, yb = min(W, int(x2 + 0.05 * bw) + 1), min(H, int(y2 + 0.05 * bh) + 1)
        crop = frame[ya:yb, xa:xb]
        if crop.shape[0] < 4 or crop.shape[1] < 4:
            return {}
        f = min(6.0, max(1.0, 128.0 / crop.shape[0]))
        up = cv2.resize(crop, None, fx=f, fy=f, interpolation=cv2.INTER_CUBIC)
        h = up.shape[0]
        return {"bottom": self.ocr.read(up[int(0.45 * h):]), "mid": self.ocr.read(up[int(0.25 * h):int(0.75 * h)]),
                "top": self.ocr.read(up[:int(0.5 * h)])}

    def _verify(self, frame: np.ndarray, box: Sequence[float], cls: str, s: float,
                tid: Optional[int]) -> tuple[str, float, str]:
        """OCR check of speed-limit / stop reads. Returns (class, conf, ocr_text)."""
        if self.ocr is None or not (cls.startswith("speed_limit") or cls == "stop"):
            return cls, s, ""
        if (box[3] - box[1]) < self.ocr_min_h:
            if cls.startswith("speed_limit") and self.ocr_strict_speed:
                return "unknown", 0.0, "too_small_for_ocr"
            return cls, s, "too_small_for_ocr"
        if tid is not None and tid in self.ocr_cache:
            fi, c, cf = self.ocr_cache[tid]
            if self._frame_i - fi < self.ocr_every:
                return c, cf, "cached"
        bands = self._ocr_bands(frame, box)
        txt = " | ".join(f"{k}:{t}" for k, (t, _) in bands.items())
        out = (cls, s)
        if cls.startswith("speed_limit"):
            val = None
            for k in ("bottom", "mid"):
                t, c = bands.get(k, ("", 0.0))
                m = SPEED_RE.search(t.replace(" ", ""))
                if m and c >= 0.8:
                    val = (m.group(1), c)
                    break
            if val:
                out = (f"speed_limit_{val[0]}", max(s, val[1]))
            else:
                other_text = any(len(re.sub(r"[^A-Z]", "", t)) >= 3 and c >= 0.6 and not re.search(r"SPEED|LIMIT|MPH", t)
                                 for t, c in bands.values())
                out = ("unknown", 0.0) if (self.ocr_strict_speed or other_text) else (cls, s)
        elif cls == "stop":
            t, c = bands.get("mid", ("", 0.0))
            if "STOP" in t.upper():
                out = ("stop", max(s, c))
            elif re.search(r"ENTER|NOT", t.upper()):
                out = ("do_not_enter", c)
        if tid is not None:
            self.ocr_cache[tid] = (self._frame_i, out[0], out[1])
        return out[0], out[1], txt

    # ------------------------------------------------------------------- public
    def recognize(self, frame_bgr: np.ndarray, sign_detections: Optional[Iterable[Any]] = None,
                  pts_s: Optional[float] = None) -> list[TrafficSign]:
        t0 = time.perf_counter()
        t = pts_s if pts_s is not None else self._frame_i / 30.0
        self._frame_i += 1
        out: list[TrafficSign] = []
        if self.backend == "lisa_crops" and sign_detections is not None:
            dets = []
            for d in sign_detections:
                if isinstance(d, Detection) and d.cls != "traffic sign":
                    continue
                dets.append(_as_box_and_id(d))
            typed = self._typed_boxes(frame_bgr, [d[0] for d in dets])
            self.last_debug = []
            for (box, tid, det_conf), (raw, s) in zip(dets, typed):
                cls, s, otxt = self._verify(frame_bgr, box, raw, s, tid)
                if raw != "unknown":
                    self.last_debug.append({"id": tid, "bbox": box, "raw": raw, "ocr": otxt, "verified": cls})
                cls, s = self._vote(tid, cls, s, t)
                if cls == "unknown" and not self.emit_unknown:
                    continue
                z = sign_distance_m(box, cls, self.focal_px)
                out.append(TrafficSign(id=tid, signClass=cls, bbox=[float(v) for v in box],
                                       confidence=round(s if cls != "unknown" else det_conf, 3),
                                       distanceMeters=round(z, 1) if z else None))
        else:
            self.last_debug = []
            for box, raw, s in self._full_frame(frame_bgr):
                cls, s, otxt = self._verify(frame_bgr, box, raw, s, None)
                self.last_debug.append({"id": None, "bbox": box, "raw": raw, "ocr": otxt, "verified": cls})
                if cls == "unknown" and not self.emit_unknown:
                    continue
                z = sign_distance_m(box, cls, self.focal_px)
                out.append(TrafficSign(id=None, signClass=cls, bbox=box, confidence=round(s, 3),
                                       distanceMeters=round(z, 1) if z else None))
        for tid in [k for k, v in self.last_seen.items() if t - v > 3.0]:
            self.last_seen.pop(tid, None)
            self.votes.pop(tid, None)
        self.last_timing_ms = (time.perf_counter() - t0) * 1000.0
        return out

    def _vote(self, tid: Optional[int], cls: str, s: float, t: float) -> tuple[str, float]:
        if tid is None:
            return cls, s
        self.last_seen[tid] = t
        if cls != "unknown":
            self.votes[tid].append((cls, s))
        v = self.votes.get(tid)
        if not v:
            return "unknown", 0.0
        cnt = Counter(c for c, _ in v)
        best, n = cnt.most_common(1)[0]
        conf = float(np.mean([sc for c, sc in v if c == best])) * n / len(v)
        return best, conf

    def reset(self) -> None:
        self.ocr_cache.clear()
        self.votes.clear()
        self.last_seen.clear()
        self._frame_i = 0
