"""SemanticSegmenter: 19-class Cityscapes / BDD100K-sem_seg segmentation + road geometry (plan §13).

Usage (run from perception_engine/):

    from perception.segmentation.semantic import SemanticSegmenter
    seg = SemanticSegmenter(backend="pidnet_s", device="cuda")
    label = seg.segment(frame_bgr)            # HxW uint8 trainIds (0 road ... 18 bicycle)
    road = seg.road_geometry(frame_bgr, seg=label)   # perception.common.schemas.RoadGeometry
    seg.last_info                              # horizon method/confidence, corridor polygon, ...
    seg.release()

Backends (all Cityscapes-trained, so their trainIds equal the BDD100K sem_seg ids):
    pidnet_s        PIDNet-S (MIT code/weights), native 1280x720
    efficientvit_b0 / efficientvit_b1 / efficientvit_b2   EfficientViT-Seg (Apache-2.0), 1280x736 (padded to /32)
    segformer_b0 / segformer_b2   NVIDIA SegFormer via transformers (NVIDIA source-code license:
                    research / evaluation only), native 1280x720

Every backend's weights inherit the Cityscapes non-commercial license. They are for
demo and evaluation only (see MODELS.md). Outputs are advisory visual context only (§38).
"""
from __future__ import annotations

import time
from pathlib import Path
from typing import Any, Optional

import cv2
import numpy as np
import torch
import torch.nn.functional as F

from perception.common.schemas import RoadGeometry
from perception.common.video import MODELS_ROOT
from perception.segmentation.geometry import (CITYSCAPES_CLASSES, CameraModel, GeometryConfig,
                                              HorizonTracker, compute_road_geometry)

SEG_MODELS = MODELS_ROOT / "segmentation"
IMAGENET_MEAN = (0.485, 0.456, 0.406)
IMAGENET_STD = (0.229, 0.224, 0.225)

BACKENDS: dict[str, dict[str, Any]] = {
    "pidnet_s": {"weights": SEG_MODELS / "PIDNet_S_Cityscapes_val.pt", "pad": 8},
    "efficientvit_b0": {"weights": SEG_MODELS / "efficientvit" / "efficientvit_seg_b0_cityscapes.pt", "pad": 32},
    "efficientvit_b1": {"weights": SEG_MODELS / "efficientvit" / "efficientvit_seg_b1_cityscapes.pt", "pad": 32},
    "efficientvit_b2": {"weights": SEG_MODELS / "efficientvit" / "efficientvit_seg_b2_cityscapes.pt", "pad": 32},
    "segformer_b0": {"hf": "nvidia/segformer-b0-finetuned-cityscapes-1024-1024", "pad": 1},
    "segformer_b2": {"hf": "nvidia/segformer-b2-finetuned-cityscapes-1024-1024", "pad": 1},
}

# Cityscapes palette (RGB), index = trainId
PALETTE_RGB = np.array([
    (128, 64, 128), (244, 35, 232), (70, 70, 70), (102, 102, 156), (190, 153, 153),
    (153, 153, 153), (250, 170, 30), (220, 220, 0), (107, 142, 35), (152, 251, 152),
    (70, 130, 180), (220, 20, 60), (255, 0, 0), (0, 0, 142), (0, 0, 70),
    (0, 60, 100), (0, 80, 100), (0, 0, 230), (119, 11, 32)], dtype=np.uint8)


# Flat-ground camera defaults for BDD100K dashcams (assumptions, see README):
#   f = 700 px at 1280 px width (task brief; ~85 deg HFOV), camera height 1.3 m,
#   horizon prior y = 339.3 and VP column prior x = 593.3 at 720p = medians of the
#   lane-label vanishing points of 5,459 BDD100K val images NOT in the eval subset
#   (outputs/segmentation/horizon_prior.json).
BDD_FOCAL_PX_1280 = 700.0
BDD_CAMERA_HEIGHT_M = 1.3
BDD_HORIZON_PRIOR_Y_720 = 339.3
BDD_VP_PRIOR_X_1280 = 593.3


def bdd_camera(W: int = 1280, H: int = 720, focal_px_1280: float = BDD_FOCAL_PX_1280,
               height_m: float = BDD_CAMERA_HEIGHT_M) -> CameraModel:
    sx, sy = W / 1280.0, H / 720.0
    return CameraModel(width=W, height=H, focal_px=focal_px_1280 * sx, height_m=height_m,
                       horizon_prior_y=BDD_HORIZON_PRIOR_Y_720 * sy, vp_prior_x=BDD_VP_PRIOR_X_1280 * sx)


def colorize(label: np.ndarray) -> np.ndarray:
    """trainId map -> BGR color image (255/ignore -> black)."""
    lut = np.zeros((256, 3), np.uint8)
    lut[:19] = PALETTE_RGB[:, ::-1]
    return lut[label]


class SemanticSegmenter:
    def __init__(self, backend: str = "efficientvit_b1", device: str = "cuda", fp16: bool = True,
                 input_scale: float = 1.0, camera: Optional[CameraModel] = None,
                 geometry: Optional[GeometryConfig] = None, temporal: bool = False,
                 prob_ema: float = 0.0, **opts):
        """
        backend      one of BACKENDS
        device       'cuda' or 'cpu'
        fp16         autocast to float16 on CUDA
        input_scale  resize factor applied to the frame before the network (1.0 = native 1280x720)
        camera       CameraModel for anchors (default f=700 px, h=1.3 m, principal point at centre)
        temporal     True for video: smooth the horizon across frames (HorizonTracker)
        prob_ema     0 = off; else EMA factor on class probabilities at network resolution (video only)
        opts         weights=<path> overrides the default checkpoint;
                     half_weights=True/False: pure-fp16 weights instead of autocast
                     (default True for SegFormer, to stay under 1.5 GB VRAM)
        """
        if backend not in BACKENDS:
            raise ValueError(f"unknown backend {backend!r}; choose from {sorted(BACKENDS)}")
        self.backend = backend
        self.device = torch.device(device if (device != "cuda" or torch.cuda.is_available()) else "cpu")
        self.fp16 = bool(fp16 and self.device.type == "cuda")
        self.input_scale = float(input_scale)
        self.camera = camera
        self.geometry_cfg = geometry or GeometryConfig()
        self.tracker = HorizonTracker() if temporal else None
        self.prob_ema = float(prob_ema)
        self._ema: Optional[torch.Tensor] = None
        self.pad = BACKENDS[backend]["pad"]
        self.last_info: dict[str, Any] = {}
        self.timings_ms: dict[str, float] = {}
        self.model = self._build(backend, opts.get("weights"))
        self.model.eval().to(self.device)
        # pure-fp16 weights instead of autocast: SegFormer-B2 at 1280x720 peaks at 2.37 GB
        # under autocast (fp32 softmax/LayerNorm copies) and 1.29 GB in pure fp16 (measured here)
        self.half_weights = bool(self.fp16 and opts.get("half_weights", backend.startswith("segformer")))
        if self.half_weights:
            self.model.half()
        self._mean = torch.tensor(IMAGENET_MEAN, device=self.device).view(1, 3, 1, 1) * 255.0
        self._std = torch.tensor(IMAGENET_STD, device=self.device).view(1, 3, 1, 1) * 255.0

    # ------------------------------------------------------------------ models
    def _build(self, backend: str, weights: Optional[str]):
        spec = BACKENDS[backend]
        if backend == "pidnet_s":
            from perception.segmentation._vendor import load_pidnet_module
            pm = load_pidnet_module()
            model = pm.get_pred_model("pidnet-s", 19)  # augment=False -> single output
            sd = torch.load(weights or spec["weights"], map_location="cpu", weights_only=True)
            if "state_dict" in sd:
                sd = sd["state_dict"]
            # Qualcomm AI Hub checkpoint = upstream PIDNet state_dict with a 'model.' prefix
            sd = {k[6:] if k.startswith("model.") else k: v for k, v in sd.items()}
            res = model.load_state_dict(sd, strict=False)
            if res.missing_keys:
                raise RuntimeError(f"PIDNet checkpoint missing keys: {res.missing_keys[:5]}")
            return model
        if backend.startswith("efficientvit"):
            from perception.segmentation._vendor import load_efficientvit_seg_module
            seg_mod, norm_mod = load_efficientvit_seg_module()
            fn = getattr(seg_mod, f"efficientvit_seg_{backend.split('_')[1]}")
            model = fn(dataset="cityscapes")
            norm_mod.set_norm_eps(model, 1e-5)  # as in efficientvit/seg_model_zoo.py
            sd = torch.load(weights or spec["weights"], map_location="cpu", weights_only=True)
            model.load_state_dict(sd.get("state_dict", sd))
            return model
        if backend.startswith("segformer"):
            from transformers import SegformerForSemanticSegmentation
            m = SegformerForSemanticSegmentation.from_pretrained(weights or spec["hf"])
            return _SegformerLogits(m)
        raise ValueError(backend)

    # ------------------------------------------------------------------ inference
    @torch.inference_mode()
    def logits(self, frame_bgr: np.ndarray) -> torch.Tensor:
        """Class scores (1, 19, H, W) upsampled to the source frame size (float32, on device)."""
        H, W = frame_bgr.shape[:2]
        # upload BGR as-is and reorder channels on the device (a CPU-side flip copy costs ~3 ms)
        x = torch.from_numpy(np.ascontiguousarray(frame_bgr)).to(self.device)
        x = x.permute(2, 0, 1).flip(0).unsqueeze(0).float()
        if self.input_scale != 1.0:
            x = F.interpolate(x, scale_factor=self.input_scale, mode="bilinear", align_corners=False)
        x = (x - self._mean) / self._std
        h, w = x.shape[-2:]
        ph, pw = (-h) % self.pad, (-w) % self.pad
        if ph or pw:
            x = F.pad(x, (0, pw, 0, ph), mode="replicate")
        if self.half_weights:
            out = self.model(x.half())
        else:
            with torch.autocast(self.device.type, dtype=torch.float16, enabled=self.fp16):
                out = self.model(x)
        out = out.float()
        # crop the padded area at output resolution, then resize to the source frame
        oh = int(round(out.shape[-2] * h / (h + ph)))
        ow = int(round(out.shape[-1] * w / (w + pw)))
        out = out[..., :oh, :ow]
        if self.prob_ema > 0:
            p = out.softmax(1)
            self._ema = p if (self._ema is None or self._ema.shape != p.shape) else \
                self.prob_ema * self._ema + (1 - self.prob_ema) * p
            out = self._ema
        return F.interpolate(out, size=(H, W), mode="bilinear", align_corners=False)

    def segment(self, frame_bgr: np.ndarray) -> np.ndarray:
        """HxW uint8 trainIds (19 Cityscapes classes = BDD100K sem_seg classes)."""
        t0 = time.perf_counter()
        lab = self.logits(frame_bgr).argmax(1)[0].to(torch.uint8).cpu().numpy()
        self.timings_ms["segment"] = (time.perf_counter() - t0) * 1000.0
        return lab

    def road_geometry(self, frame_bgr: Optional[np.ndarray], seg: Optional[np.ndarray] = None) -> RoadGeometry:
        """Deterministic road geometry + AR anchors (see geometry.py for the exact rules)."""
        if seg is None:
            seg = self.segment(frame_bgr)
        t0 = time.perf_counter()
        H, W = seg.shape[:2]
        cam = self.camera
        if cam is None or (cam.width, cam.height) != (W, H):
            cam = self.camera = bdd_camera(W, H)
        geo, info = compute_road_geometry(seg, cam, self.geometry_cfg, self.tracker, frame_bgr=frame_bgr)
        self.last_info = info
        self.timings_ms["road_geometry"] = (time.perf_counter() - t0) * 1000.0
        return geo

    def process(self, frame_bgr: np.ndarray) -> tuple[np.ndarray, RoadGeometry]:
        """Convenience: (trainId map, RoadGeometry) for one frame."""
        lab = self.segment(frame_bgr)
        return lab, self.road_geometry(frame_bgr, seg=lab)

    def reset(self):
        """Call between videos (clears temporal state)."""
        self._ema = None
        if self.tracker is not None:
            self.tracker.reset()

    def release(self):
        self.model = None
        self._ema = None
        if torch.cuda.is_available():
            torch.cuda.empty_cache()

    @property
    def class_names(self) -> list[str]:
        return list(CITYSCAPES_CLASSES)


class _SegformerLogits(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, x):
        return self.m(pixel_values=x).logits  # (1, 19, H/4, W/4)
