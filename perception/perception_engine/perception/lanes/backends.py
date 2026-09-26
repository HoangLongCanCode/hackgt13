"""Model wrappers for the lane / drivable block.

Every backend maps one upright BGR frame (any size, BDD100K is 1280x720) to
two float "margin" maps at the common work resolution (WORK_W x WORK_H =
640x360, the TwinLiteNet+/YOLOP evaluation resolution):

    lane_margin[y, x]      > 0  <=> lane-line pixel
    drivable_margin[y, x]  > 0  <=> drivable pixel

A margin is (score_fg - score_bg) for 2-class heads, or
(score_fg - max(other scores)) for multi-class heads, so bilinear up-sampling
of the margin followed by `> 0` equals bilinear up-sampling of the logits
followed by argmax (what the upstream demos do).

Backends
--------
twinlitenetplus_large / twinlitenetplus_medium
    github.com/chequanghuy/TwinLiteNetPlus (MIT code, BDD100K-trained weights).
    Pre-processing = upstream BDD100K.py `letterbox`: cv2 INTER_LINEAR resize to
    640x360, pad 12 px top/bottom with 114 -> 640x384, BGR->RGB, /255.
    PyTorch FP32 + cudnn.benchmark (faster than ORT for this net, see README).
yolop_onnx
    github.com/hustvl/YOLOP weights/yolop-640-640.onnx (MIT code, BDD100K-trained).
    Pre-processing = upstream test_onnx.py `resize_unscale`: BGR->RGB, INTER_AREA
    resize to 640x360, paste into a 640x640 canvas filled with 114 at dh=140,
    /255, ImageNet mean/std. Runs on onnxruntime CUDA EP (torch is imported
    first so ORT finds the CUDA 13 / cuDNN 9 DLLs shipped with torch).
comma10k_segnet
    huggingface.co/commaai/comma10k-segnet (MIT weights, MIT comma10k data).
    smp.Unet(tu-efficientnet_b2), 5 classes: 0 road, 1 lane markings (no arrows,
    no crosswalks), 2 undrivable, 3 movable, 4 my car. Eval transform from
    albumentations_config_eval.json = Resize(384, 512, INTER_LINEAR) + ToTensorV2,
    i.e. raw 0..255 RGB, no normalisation. Drivable := road | lane markings.
"""
from __future__ import annotations

import argparse
import contextlib
import importlib
import json
import sys
import time
from pathlib import Path
from typing import Optional

import cv2
import numpy as np
import torch  # noqa: F401  (must be imported before onnxruntime for the CUDA EP)
import torch.nn.functional as F

# Folders from perception.common.paths (KSR_MODELS_DIR / KSR_THIRD_PARTY_DIR override the defaults).
from perception.common.paths import MODELS_ROOT, PROJECT_ROOT, THIRD_PARTY  # noqa: E402,F401
MODELS_DIR = MODELS_ROOT / "lanes"
TLNP_REPO = THIRD_PARTY / "TwinLiteNetPlus"

WORK_W, WORK_H = 640, 360


def _sync(device: str) -> None:
    if device.startswith("cuda") and torch.cuda.is_available():
        torch.cuda.synchronize()


def capped_warmup(fn, device: str, cap_gb: Optional[float] = 1.0, n: int = 3) -> None:
    """Run `fn()` n times while the caching allocator is capped at `cap_gb`.

    With cudnn.benchmark=True the first forward at a new input shape tries
    every conv algorithm and can grab 2-5 GB of workspace (measured: 2.1 GB
    TLNP+, 5.1 GB comma U-Net). Under a cap, algorithms whose workspace does
    not fit are skipped (PyTorch catches the OOM), the choice is cached per
    shape, and later calls reuse it. The previous per-process fraction is
    restored afterwards, so the rest of the process is not limited.

    The cap is `cap_gb` ABOVE what this process has already reserved (review fix:
    an absolute cap made LaneDetector() raise CUDA OOM whenever other models in the
    same process already held more than `cap_gb`, i.e. in the integrated pipeline).
    """
    if not (device.startswith("cuda") and torch.cuda.is_available()) or cap_gb is None:
        for _ in range(n):
            fn()
        return
    dev = torch.device(device)
    idx = dev.index if dev.index is not None else torch.cuda.current_device()
    total = torch.cuda.get_device_properties(idx).total_memory
    try:
        prev = torch.cuda.get_per_process_memory_fraction(idx)
    except Exception:  # older torch
        prev = 1.0
    base = torch.cuda.memory_reserved(idx)
    frac = min(prev, (base + cap_gb * 2**30) / total)
    torch.cuda.set_per_process_memory_fraction(frac, idx)
    try:
        for _ in range(n):
            fn()
        torch.cuda.synchronize(idx)
    finally:
        torch.cuda.set_per_process_memory_fraction(prev, idx)


def _load_tlnp_class():
    """Import TwinLiteNetPlus from the vendored repo without leaking its
    generic top-level package name `model` into sys.modules."""
    saved = {k: v for k, v in sys.modules.items() if k == "model" or k.startswith("model.")}
    for k in saved:
        del sys.modules[k]
    sys.path.insert(0, str(TLNP_REPO))
    try:
        mod = importlib.import_module("model.model")
        cls = mod.TwinLiteNetPlus
    finally:
        sys.path.remove(str(TLNP_REPO))
        for k in [k for k in sys.modules if k == "model" or k.startswith("model.")]:
            del sys.modules[k]
        sys.modules.update(saved)
    return cls


class BaseBackend:
    name = "base"
    provides = ("lane", "drivable")

    def __init__(self, device: str = "cuda"):
        if device.startswith("cuda") and not torch.cuda.is_available():
            device = "cpu"
        self.device = device
        self.last_timings: dict[str, float] = {}

    def margins(self, frame_bgr: np.ndarray) -> dict[str, np.ndarray]:
        raise NotImplementedError

    def close(self) -> None:
        pass


class TwinLiteNetPlusBackend(BaseBackend):
    def __init__(self, size: str = "large", device: str = "cuda", half: bool = False,
                 weights: Optional[str] = None, warmup_cap_gb: Optional[float] = 1.0):
        super().__init__(device)
        self.name = f"twinlitenetplus_{size}"
        cls = _load_tlnp_class()
        self.model = cls(argparse.Namespace(config=size))
        wpath = Path(weights) if weights else MODELS_DIR / "twinlitenetplus" / f"{size}.pth"
        sd = torch.load(str(wpath), map_location="cpu", weights_only=True)
        self.model.load_state_dict(sd, strict=True)
        self.model = self.model.to(self.device).eval()
        self.half = bool(half and self.device.startswith("cuda"))
        if self.half:
            self.model.half()
        torch.backends.cudnn.benchmark = True
        dummy = np.zeros((720, 1280, 3), np.uint8)
        capped_warmup(lambda: self.margins(dummy), self.device, warmup_cap_gb)

    def _prep(self, frame_bgr: np.ndarray) -> torch.Tensor:
        h, w = frame_bgr.shape[:2]
        r = min(384 / h, 640 / w)
        nw, nh = int(round(w * r)), int(round(h * r))
        im = cv2.resize(frame_bgr, (nw, nh), interpolation=cv2.INTER_LINEAR) if (nw, nh) != (w, h) else frame_bgr
        dh, dw = (384 - nh) / 2, (640 - nw) / 2
        top, bottom = int(round(dh - 0.1)), int(round(dh + 0.1))
        left, right = int(round(dw - 0.1)), int(round(dw + 0.1))
        im = cv2.copyMakeBorder(im, top, bottom, left, right, cv2.BORDER_CONSTANT, value=(114, 114, 114))
        self._crop = (top, top + nh, left, left + nw)
        x = torch.from_numpy(np.ascontiguousarray(im[:, :, ::-1].transpose(2, 0, 1))).to(self.device)
        x = x.half() if self.half else x.float()
        return (x / 255.0).unsqueeze(0)

    @torch.no_grad()
    def margins(self, frame_bgr):
        t0 = time.perf_counter()
        x = self._prep(frame_bgr)
        _sync(self.device)
        t1 = time.perf_counter()
        da, ll = self.model(x)
        _sync(self.device)
        t2 = time.perf_counter()
        y0, y1, x0, x1 = self._crop
        da = da[0, :, y0:y1, x0:x1].float()
        ll = ll[0, :, y0:y1, x0:x1].float()
        m = torch.stack([ll[1] - ll[0], da[1] - da[0]])[None]
        if m.shape[-2:] != (WORK_H, WORK_W):
            m = F.interpolate(m, size=(WORK_H, WORK_W), mode="bilinear", align_corners=False)
        m = m[0].cpu().numpy()
        t3 = time.perf_counter()
        self.last_timings = {"pre_ms": (t1 - t0) * 1e3, "model_ms": (t2 - t1) * 1e3,
                             "post_ms": (t3 - t2) * 1e3}
        return {"lane": m[0], "drivable": m[1]}

    def close(self):
        del self.model
        if torch.cuda.is_available():
            torch.cuda.empty_cache()


class YolopOnnxBackend(BaseBackend):
    name = "yolop_onnx"

    def __init__(self, device: str = "cuda", onnx_path: Optional[str] = None,
                 gpu_mem_limit: int = 1 << 30):
        super().__init__(device)
        import onnxruntime as ort
        path = onnx_path or str(MODELS_DIR / "yolop" / "yolop-640-640.onnx")
        so = ort.SessionOptions()
        so.log_severity_level = 3
        providers = ["CPUExecutionProvider"]
        if self.device.startswith("cuda"):
            dev_id = int(self.device.split(":")[1]) if ":" in self.device else 0
            providers = [("CUDAExecutionProvider", {"device_id": dev_id, "gpu_mem_limit": gpu_mem_limit,
                                                    "arena_extend_strategy": "kSameAsRequested"}),
                         "CPUExecutionProvider"]
        self.sess = ort.InferenceSession(path, sess_options=so, providers=providers)
        self.active_provider = self.sess.get_providers()[0]
        self.in_name = self.sess.get_inputs()[0].name
        self.out_names = [o.name for o in self.sess.get_outputs()]  # det_out, drive_area_seg, lane_line_seg
        mean = np.array([0.485, 0.456, 0.406], np.float32)
        std = np.array([0.229, 0.224, 0.225], np.float32)
        # 256-entry per-channel LUT, RGB order: value -> (v/255 - mean)/std
        self._lut = ((np.arange(256, dtype=np.float32)[:, None] / 255.0 - mean) / std).astype(np.float32)[None]
        self._canvas = np.full((640, 640, 3), 114, np.uint8)
        dummy = np.zeros((720, 1280, 3), np.uint8)
        for _ in range(3):
            self.margins(dummy)

    def _prep(self, frame_bgr):
        h, w = frame_bgr.shape[:2]
        r = min(640 / h, 640 / w)
        nw, nh = int(round(w * r)), int(round(h * r))
        dw, dh = (640 - nw) // 2, (640 - nh) // 2
        im = cv2.resize(frame_bgr, (nw, nh), interpolation=cv2.INTER_AREA) if (nw, nh) != (w, h) else frame_bgr
        canvas = self._canvas.copy()
        canvas[dh:dh + nh, dw:dw + nw] = im[:, :, ::-1]          # BGR -> RGB (resize is per-channel)
        blob = cv2.LUT(canvas, self._lut)                         # float32 HWC, normalised
        self._crop = (dh, dh + nh, dw, dw + nw)
        return np.ascontiguousarray(blob.transpose(2, 0, 1)[None])

    def margins(self, frame_bgr):
        t0 = time.perf_counter()
        x = self._prep(frame_bgr)
        t1 = time.perf_counter()
        det, da, ll = self.sess.run(self.out_names, {self.in_name: x})
        t2 = time.perf_counter()
        y0, y1, x0, x1 = self._crop
        lm = ll[0, 1, y0:y1, x0:x1] - ll[0, 0, y0:y1, x0:x1]
        dm = da[0, 1, y0:y1, x0:x1] - da[0, 0, y0:y1, x0:x1]
        if lm.shape != (WORK_H, WORK_W):
            lm = cv2.resize(lm, (WORK_W, WORK_H), interpolation=cv2.INTER_LINEAR)
            dm = cv2.resize(dm, (WORK_W, WORK_H), interpolation=cv2.INTER_LINEAR)
        self.last_det = det  # [1, 25200, 6] single-class vehicle boxes (unused here)
        t3 = time.perf_counter()
        self.last_timings = {"pre_ms": (t1 - t0) * 1e3, "model_ms": (t2 - t1) * 1e3,
                             "post_ms": (t3 - t2) * 1e3}
        return {"lane": np.ascontiguousarray(lm, np.float32), "drivable": np.ascontiguousarray(dm, np.float32)}

    def close(self):
        del self.sess


class Comma10kSegnetBackend(BaseBackend):
    name = "comma10k_segnet"
    provides = ("lane", "drivable", "movable", "ego_car", "undrivable")
    CLASSES = ("road", "lane_markings", "undrivable", "movable", "my_car")

    def __init__(self, device: str = "cuda", half: bool = False, weights_dir: Optional[str] = None,
                 warmup_cap_gb: Optional[float] = 1.0):
        super().__init__(device)
        import segmentation_models_pytorch as smp
        from safetensors.torch import load_file
        wdir = Path(weights_dir) if weights_dir else MODELS_DIR / "comma10k-segnet"
        cfg = json.loads((wdir / "config.json").read_text())
        cfg.pop("_model_class", None)
        self.model = smp.Unet(**cfg)
        self.model.load_state_dict(load_file(str(wdir / "model.safetensors")), strict=True)
        self.model = self.model.to(self.device).eval()
        self.half = bool(half and self.device.startswith("cuda"))
        torch.backends.cudnn.benchmark = True
        self.in_w, self.in_h = 512, 384
        dummy = np.zeros((720, 1280, 3), np.uint8)
        capped_warmup(lambda: self.margins(dummy), self.device, warmup_cap_gb)

    def _prep(self, frame_bgr):
        rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)
        im = cv2.resize(rgb, (self.in_w, self.in_h), interpolation=cv2.INTER_LINEAR)
        x = torch.from_numpy(np.ascontiguousarray(im.transpose(2, 0, 1))).to(self.device).float()
        return x.unsqueeze(0)

    @torch.no_grad()
    def margins(self, frame_bgr):
        t0 = time.perf_counter()
        x = self._prep(frame_bgr)
        _sync(self.device)
        t1 = time.perf_counter()
        ctx = torch.autocast("cuda", dtype=torch.float16) if self.half else contextlib.nullcontext()
        with ctx:
            s = self.model(x)
        _sync(self.device)
        t2 = time.perf_counter()
        s = F.interpolate(s.float(), size=(WORK_H, WORK_W), mode="bilinear", align_corners=False)[0]
        road, lane, undr, mov, car = s[0], s[1], s[2], s[3], s[4]
        lane_m = lane - torch.maximum(torch.maximum(road, undr), torch.maximum(mov, car))
        drv_m = torch.maximum(road, lane) - torch.maximum(torch.maximum(undr, mov), car)
        mov_m = mov - torch.maximum(torch.maximum(road, lane), torch.maximum(undr, car))
        car_m = car - torch.maximum(torch.maximum(road, lane), torch.maximum(undr, mov))
        m = torch.stack([lane_m, drv_m, mov_m, car_m]).cpu().numpy()
        t3 = time.perf_counter()
        self.last_timings = {"pre_ms": (t1 - t0) * 1e3, "model_ms": (t2 - t1) * 1e3,
                             "post_ms": (t3 - t2) * 1e3}
        return {"lane": m[0], "drivable": m[1], "movable": m[2], "ego_car": m[3]}

    def close(self):
        del self.model
        if torch.cuda.is_available():
            torch.cuda.empty_cache()


def make_backend(backend: str, device: str = "cuda", **opts) -> BaseBackend:
    cap = opts.get("warmup_cap_gb", 1.0)
    if backend == "twinlitenetplus_large":
        return TwinLiteNetPlusBackend("large", device, half=opts.get("half", False), weights=opts.get("weights"),
                                      warmup_cap_gb=cap)
    if backend == "twinlitenetplus_medium":
        return TwinLiteNetPlusBackend("medium", device, half=opts.get("half", False), weights=opts.get("weights"),
                                      warmup_cap_gb=cap)
    if backend == "yolop_onnx":
        return YolopOnnxBackend(device, onnx_path=opts.get("weights"),
                                gpu_mem_limit=opts.get("gpu_mem_limit", 1 << 30))
    if backend == "comma10k_segnet":
        return Comma10kSegnetBackend(device, half=opts.get("half", False), weights_dir=opts.get("weights"),
                                     warmup_cap_gb=cap)
    raise ValueError(f"unknown backend {backend!r}")
