"""Dense metric-depth backends for the depth & distance block (plan section 9).

Every backend exposes the same call:

    backend.predict(frame_bgr, focal_px=None) -> np.ndarray  # HxW float32, meters

and an optional `backend.last_sky` (HxW bool, True = sky) when the model has a
sky head. Output is resized back to the input frame size.

Backends
--------
da2_metric_outdoor_small  depth-anything/Depth-Anything-V2-Metric-Outdoor-Small-hf
                          (HF transformers, VKITTI2 metric fine-tune, max 80 m).
                          No intrinsics input: `focal_px` is ignored (it learned
                          the KITTI/VKITTI camera implicitly -> FOV bias).
da3_metric_large          depth-anything/DA3METRIC-LARGE through the vendored
                          ByteDance-Seed/Depth-Anything-3 code (third_party/).
                          metric depth = f_net * raw / 300, f_net = focal at the
                          *network* input resolution (verified on KITTI, see README).
metric3d_vit_s_onnx       onnx-community/metric3d-vit-small (ONNX Runtime CUDA EP).
                          Canonical camera f = 1000 px: depth = raw * f_net / 1000.

`import torch` happens at module import, before any ONNX Runtime session is
created (required for the ORT CUDA EP in this venv, see SETUP.md, "onnxruntime-gpu needs `import torch` first").
"""
from __future__ import annotations

import os
import sys
import time
import types
from pathlib import Path
from typing import Optional

import cv2
import numpy as np
import torch
import torch.nn.functional as F

# Folders from perception.common.paths (PERCEPTION_MODELS_DIR / PERCEPTION_THIRD_PARTY_DIR override the defaults).
from perception.common.paths import MODELS_ROOT, PROJECT_ROOT, THIRD_PARTY  # noqa: E402,F401
HF_CACHE = Path(os.environ.get("HF_HUB_CACHE", MODELS_ROOT / "huggingface" / "hub"))

IMAGENET_MEAN = torch.tensor([0.485, 0.456, 0.406]).view(1, 3, 1, 1)
IMAGENET_STD = torch.tensor([0.229, 0.224, 0.225]).view(1, 3, 1, 1)

REPOS = {
    "da2_metric_outdoor_small": "depth-anything/Depth-Anything-V2-Metric-Outdoor-Small-hf",
    "da3_metric_large": "depth-anything/DA3METRIC-LARGE",
    "metric3d_vit_s_onnx": "onnx-community/metric3d-vit-small",
}


def _snapshot(repo: str, allow_download: bool = True) -> Path:
    """Local snapshot dir of an HF repo (downloads into models/huggingface/hub if missing)."""
    from huggingface_hub import snapshot_download
    try:
        return Path(snapshot_download(repo, local_files_only=True))
    except Exception:
        if not allow_download:
            raise
        return Path(snapshot_download(repo))


def _round14(x: float) -> int:
    return max(14, int(round(x / 14.0)) * 14)


class DepthBackend:
    name = "base"
    needs_focal = False

    def __init__(self, device: str = "cuda"):
        self.device = torch.device(device if (device != "cuda" or torch.cuda.is_available()) else "cpu")
        self.last_ms: float = 0.0
        self.last_sky: Optional[np.ndarray] = None
        self.last_net_hw: tuple[int, int] = (0, 0)

    def predict(self, frame_bgr: np.ndarray, focal_px: Optional[float] = None) -> np.ndarray:
        raise NotImplementedError

    def _to_tensor(self, frame_bgr: np.ndarray) -> torch.Tensor:
        rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)
        t = torch.from_numpy(rgb).to(self.device, non_blocking=True)
        return t.permute(2, 0, 1)[None].float() / 255.0

    def _resize_back(self, d: torch.Tensor, h: int, w: int) -> np.ndarray:
        d = F.interpolate(d[:, None].float(), size=(h, w), mode="bilinear", align_corners=False)[0, 0]
        return d.clamp_min(0).cpu().numpy().astype(np.float32)

    def close(self) -> None:
        if torch.cuda.is_available():
            torch.cuda.empty_cache()


# --------------------------------------------------------------------------------------
class DA2MetricOutdoor(DepthBackend):
    """Depth Anything V2 metric outdoor (VKITTI2) via HF transformers.

    Preprocessing mirrors the HF DPT image processor for this checkpoint:
    shorter side -> `input_size` (518) keeping aspect, multiple of 14, bicubic,
    ImageNet normalisation. Output `predicted_depth` is already in meters.
    """
    name = "da2_metric_outdoor_small"
    needs_focal = False

    def __init__(self, device: str = "cuda", input_size: int = 518, fp16: bool = True,
                 repo: str = REPOS["da2_metric_outdoor_small"]):
        super().__init__(device)
        from transformers import AutoModelForDepthEstimation
        self.path = _snapshot(repo)
        self.model = AutoModelForDepthEstimation.from_pretrained(str(self.path)).to(self.device).eval()
        self.fp16 = fp16 and self.device.type == "cuda"
        if self.fp16:
            self.model = self.model.half()
        self.input_size = input_size

    @torch.inference_mode()
    def predict(self, frame_bgr, focal_px=None):
        t0 = time.perf_counter()
        h, w = frame_bgr.shape[:2]
        s = self.input_size / min(h, w)
        nh, nw = _round14(h * s), _round14(w * s)
        x = self._to_tensor(frame_bgr)
        x = F.interpolate(x, size=(nh, nw), mode="bicubic", align_corners=False, antialias=s < 1).clamp(0, 1)
        x = (x - IMAGENET_MEAN.to(x.device)) / IMAGENET_STD.to(x.device)
        if self.fp16:
            x = x.half()
        d = self.model(pixel_values=x).predicted_depth  # (1, nh, nw) meters
        self.last_net_hw = (nh, nw)
        out = self._resize_back(d, h, w)
        self.last_ms = (time.perf_counter() - t0) * 1000
        return out


# --------------------------------------------------------------------------------------
def _install_da3_import_shims() -> None:
    """DA3 imports `addict` (vendored, MIT) and `omegaconf` (only needed by its YAML
    config system, which we bypass). Nothing is pip-installed."""
    src = THIRD_PARTY / "Depth-Anything-3" / "src"
    addict_dir = THIRD_PARTY / "addict"
    for p in (src, addict_dir):
        if str(p) not in sys.path:
            sys.path.insert(0, str(p))
    try:
        import omegaconf  # noqa: F401
    except ImportError:
        shim = types.ModuleType("omegaconf")

        class DictConfig(dict):
            pass

        class ListConfig(list):
            pass

        class OmegaConf:  # minimal stand-in; DA3's cfg.py wraps register in try/except
            @staticmethod
            def create(x):
                return x

            @staticmethod
            def register_new_resolver(*a, **k):
                return None

            @staticmethod
            def load(*a, **k):
                raise RuntimeError("omegaconf shim: YAML configs are not supported")

        shim.DictConfig, shim.ListConfig, shim.OmegaConf = DictConfig, ListConfig, OmegaConf
        sys.modules["omegaconf"] = shim


class DA3MetricLarge(DepthBackend):
    """DA3METRIC-LARGE (DinoV2 ViT-L + DPT head, 0.35B params, Apache-2.0 label).

    Backbone runs in bf16 (weights cast), head in fp32, to keep VRAM ~1 GB.
    Resize: longest side -> `process_res` (504 default, like the DA3 API
    `upper_bound_resize`), each side rounded to a multiple of 14.
    metric depth = raw * f_net / 300 with f_net = focal_px * (net_w / w)
    (`focal_convention='network'`, the DA3 code path); `'original'` uses the
    un-resized focal (the ros2 TensorRT node convention) for comparison.
    """
    name = "da3_metric_large"
    needs_focal = True

    def __init__(self, device: str = "cuda", process_res: int = 504, focal_convention: str = "network",
                 repo: str = REPOS["da3_metric_large"], bf16_backbone: bool = True):
        super().__init__(device)
        _install_da3_import_shims()
        from safetensors.torch import load_file
        from depth_anything_3.model.da3 import DepthAnything3Net
        from depth_anything_3.model.dinov2.dinov2 import DinoV2
        from depth_anything_3.model.dpt import DPT

        self.path = _snapshot(repo)
        net = DinoV2(name="vitl", out_layers=[4, 11, 17, 23], alt_start=-1, qknorm_start=-1,
                     rope_start=-1, cat_token=False)
        head = DPT(dim_in=1024, output_dim=1, features=256, out_channels=[256, 512, 1024, 1024])
        model = DepthAnything3Net(net, head)
        sd = load_file(str(self.path / "model.safetensors"))
        sd = {k[len("model."):]: v for k, v in sd.items() if k.startswith("model.")}
        missing, unexpected = model.load_state_dict(sd, strict=False)
        if missing or unexpected:
            raise RuntimeError(f"DA3 state_dict mismatch: missing={missing[:5]} unexpected={unexpected[:5]}")
        del sd
        self.model = model.eval()
        self.use_bf16 = bf16_backbone and self.device.type == "cuda" and torch.cuda.is_bf16_supported()
        if self.use_bf16:
            self.model.backbone.to(torch.bfloat16)
        self.model.to(self.device)
        self.process_res = process_res
        self.focal_convention = focal_convention
        self.last_raw_median = None

    @torch.inference_mode()
    def predict(self, frame_bgr, focal_px=None):
        if focal_px is None:
            raise ValueError("DA3METRIC needs focal_px (BDD default 700 px at 1280 wide)")
        t0 = time.perf_counter()
        h, w = frame_bgr.shape[:2]
        s = self.process_res / max(h, w)
        nh, nw = _round14(h * s), _round14(w * s)
        x = self._to_tensor(frame_bgr)
        x = F.interpolate(x, size=(nh, nw), mode="bicubic", align_corners=False, antialias=s < 1).clamp(0, 1)
        x = (x - IMAGENET_MEAN.to(x.device)) / IMAGENET_STD.to(x.device)
        x = x[:, None]  # (B=1, N=1, 3, H, W)
        dt = torch.bfloat16 if self.use_bf16 else torch.float32
        feats, _ = self.model.backbone(x.to(dt), cam_token=None, export_feat_layers=[], ref_view_strategy="first")
        feats = [tuple(t.float() if torch.is_tensor(t) else t for t in f) for f in feats]
        out = self.model.head(feats, nh, nw, patch_start_idx=0)
        raw = out["depth"].reshape(1, nh, nw).float()
        if self.focal_convention == "network":
            f_net = focal_px * 0.5 * (nw / w + nh / h)
        else:
            f_net = focal_px
        depth = raw * (f_net / 300.0)
        sky = out.get("sky", None)
        if sky is not None:
            sky = sky.reshape(1, nh, nw).float()
            sky_full = F.interpolate(sky[:, None], size=(h, w), mode="bilinear", align_corners=False)[0, 0]
            self.last_sky = (sky_full >= 0.3).cpu().numpy()
            nonsky = sky < 0.3
            if nonsky.sum() > 100:  # DA3 sets sky to the 99th pct of non-sky depth
                cap = torch.quantile(depth[nonsky][:100000].float(), 0.99)
                depth = torch.where(nonsky, depth, cap)
        self.last_net_hw = (nh, nw)
        self.last_raw_median = float(raw.median())
        res = self._resize_back(depth, h, w)
        self.last_ms = (time.perf_counter() - t0) * 1000
        return res


# --------------------------------------------------------------------------------------
class Metric3DOnnx(DepthBackend):
    """Metric3D v2 ViT-S exported to ONNX (onnx-community/metric3d-vit-small).

    Input: raw RGB 0..255 (normalisation is inside the graph), letterboxed into
    `input_size` (616x1064, the Metric3D ViT canonical size) with the ImageNet
    mean as padding. Output is depth in the canonical camera (f = 1000 px):
    metric = raw * (focal_px * scale) / 1000.
    """
    name = "metric3d_vit_s_onnx"
    needs_focal = True

    def __init__(self, device: str = "cuda", input_size=(616, 1064), repo: str = REPOS["metric3d_vit_s_onnx"],
                 providers: Optional[list] = None, onnx_file: str = "model_fp16.onnx"):
        super().__init__(device)
        import onnxruntime as ort
        self.path = _snapshot(repo)
        onnx_path = self.path / "onnx" / onnx_file
        if providers is None:
            providers = [("CUDAExecutionProvider", {"device_id": 0, "cudnn_conv_use_max_workspace": "0",
                                                    "arena_extend_strategy": "kSameAsRequested"}),
                         "CPUExecutionProvider"] if self.device.type == "cuda" else ["CPUExecutionProvider"]
        so = ort.SessionOptions()
        so.log_severity_level = 3
        self.sess = ort.InferenceSession(str(onnx_path), sess_options=so, providers=providers)
        self.providers = self.sess.get_providers()
        self.in_dtype = np.float16 if "float16" in self.sess.get_inputs()[0].type else np.float32
        self.input_size = tuple(input_size)

    def predict(self, frame_bgr, focal_px=None):
        if focal_px is None:
            raise ValueError("Metric3D needs focal_px")
        t0 = time.perf_counter()
        h, w = frame_bgr.shape[:2]
        H, W = self.input_size
        s = min(H / h, W / w)
        rh, rw = int(h * s), int(w * s)
        rgb = cv2.resize(cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB), (rw, rh), interpolation=cv2.INTER_LINEAR)
        ph, pw = H - rh, W - rw
        t, l = ph // 2, pw // 2
        rgb = cv2.copyMakeBorder(rgb, t, ph - t, l, pw - l, cv2.BORDER_CONSTANT, value=(123.675, 116.28, 103.53))
        x = np.ascontiguousarray(rgb.transpose(2, 0, 1)[None], dtype=self.in_dtype)
        raw = self.sess.run(["predicted_depth"], {"pixel_values": x})[0][0].astype(np.float32)
        raw = raw[t:t + rh, l:l + rw]
        depth = raw * (focal_px * s / 1000.0)
        self.last_net_hw = (rh, rw)
        out = cv2.resize(depth.astype(np.float32), (w, h), interpolation=cv2.INTER_LINEAR)
        self.last_ms = (time.perf_counter() - t0) * 1000
        return np.clip(out, 0, 300)

    def close(self):
        self.sess = None
        super().close()


BACKENDS = {
    "da2_metric_outdoor_small": DA2MetricOutdoor,
    "da3_metric_large": DA3MetricLarge,
    "metric3d_vit_s_onnx": Metric3DOnnx,
}


def make_backend(name: Optional[str], device: str = "cuda", **opts) -> Optional[DepthBackend]:
    if name is None or name == "none":
        return None
    if name not in BACKENDS:
        raise ValueError(f"unknown depth backend {name!r}; choose from {sorted(BACKENDS)} or None")
    return BACKENDS[name](device=device, **opts)
