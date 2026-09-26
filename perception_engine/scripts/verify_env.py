"""Environment verification for the perception prototype venv.

Run from anywhere (paths below are relative to perception_engine/):
    .venv\\Scripts\\python.exe scripts\\verify_env.py

Keeps caches/settings project-local (same defaults as the .pth hook scripts/setup_env.ps1 writes into
.venv/Lib/site-packages/perception_engine_env.pth, which applies them to every interpreter started from that
venv; explicit env vars always win). <project> = perception_engine/, <models> = PERCEPTION_MODELS_DIR or <project>/models:
    YOLO_CONFIG_DIR -> <project>/.cache/ultralytics     (instead of %APPDATA%/Ultralytics)
    HF_HUB_CACHE    -> <models>/huggingface/hub         (HF token/config stay in the global HF_HOME)
    TORCH_HOME      -> <models>/torch
"""
from __future__ import annotations

import os
import sys
import time
import traceback
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[1]
MODELS = Path(os.environ.get("PERCEPTION_MODELS_DIR") or PROJECT / "models").absolute()
CACHE = PROJECT / ".cache"
MODELS.mkdir(parents=True, exist_ok=True)
os.environ.setdefault("YOLO_CONFIG_DIR", str(CACHE / "ultralytics"))
os.environ.setdefault("HF_HUB_CACHE", str(MODELS / "huggingface" / "hub"))
os.environ.setdefault("TORCH_HOME", str(MODELS / "torch"))
for _d in ("YOLO_CONFIG_DIR", "HF_HUB_CACHE", "TORCH_HOME"):
    # Ultralytics only honours YOLO_CONFIG_DIR if its parent already exists; otherwise it falls back to CWD.
    Path(os.environ[_d]).mkdir(parents=True, exist_ok=True)

RESULTS: dict[str, str] = {}


def section(name):
    def deco(fn):
        def run():
            print(f"\n=== {name} ===", flush=True)
            try:
                fn()
                RESULTS[name] = "OK"
            except Exception as e:  # noqa: BLE001
                traceback.print_exc()
                RESULTS[name] = f"FAIL: {type(e).__name__}: {e}"
        return run
    return deco


@section("python")
def check_python():
    print("executable:", sys.executable)
    print("version:", sys.version)
    for k in ("YOLO_CONFIG_DIR", "HF_HUB_CACHE", "TORCH_HOME"):
        print(f"{k} = {os.environ.get(k)}")


@section("torch")
def check_torch():
    import torch
    print("torch:", torch.__version__)
    print("torch.version.cuda:", torch.version.cuda)
    print("cuda available:", torch.cuda.is_available())
    assert torch.cuda.is_available(), "CUDA not available"
    print("device:", torch.cuda.get_device_name(0))
    print("capability:", torch.cuda.get_device_capability(0))
    archs = torch.cuda.get_arch_list()
    print("arch list:", archs)
    assert any(a in archs for a in ("sm_120", "sm_120a", "compute_120")), "sm_120 not in arch list"
    print("cudnn:", torch.backends.cudnn.version(), "enabled:", torch.backends.cudnn.enabled)
    free, total = torch.cuda.mem_get_info()
    print(f"mem free/total: {free/2**30:.2f} / {total/2**30:.2f} GiB")

    a = torch.randn(4096, 4096, device="cuda", dtype=torch.float16)
    b = torch.randn(4096, 4096, device="cuda", dtype=torch.float16)
    c = a @ b  # warmup
    torch.cuda.synchronize()
    n = 20
    t0 = time.perf_counter()
    for _ in range(n):
        c = a @ b
    torch.cuda.synchronize()
    dt = (time.perf_counter() - t0) / n
    tflops = 2 * 4096**3 / dt / 1e12
    ref = (a[:64].float() @ b[:, :64].float())
    err = (c[:64, :64].float() - ref).abs().max().item()
    print(f"fp16 4096x4096 matmul: {dt*1e3:.2f} ms/iter, {tflops:.1f} TFLOPS, max abs err vs fp32 = {err:.4f}")
    assert torch.isfinite(c).all().item()

    # cuDNN conv path
    x = torch.randn(1, 3, 640, 640, device="cuda", dtype=torch.float16)
    conv = torch.nn.Conv2d(3, 16, 3, padding=1).cuda().half()
    y = conv(x)
    torch.cuda.synchronize()
    print("cudnn fp16 conv ok:", tuple(y.shape))


@section("torchvision")
def check_torchvision():
    import torch
    import torchvision
    from torchvision.ops import nms
    print("torchvision:", torchvision.__version__)
    boxes = torch.tensor([[0, 0, 10, 10], [1, 1, 11, 11], [50, 50, 60, 60]], dtype=torch.float32, device="cuda")
    scores = torch.tensor([0.9, 0.8, 0.7], device="cuda")
    keep = nms(boxes, scores, 0.5)
    print("CUDA nms keep:", keep.tolist())
    assert keep.tolist() == [0, 2]


@section("onnxruntime")
def check_onnxruntime():
    import numpy as np
    import onnx
    from onnx import TensorProto, helper
    import torch  # noqa: F401  (import first so ORT can reuse torch's bundled CUDA/cuDNN DLLs)
    import onnxruntime as ort
    print("onnxruntime:", ort.__version__, "| device:", ort.get_device())
    if hasattr(ort, "preload_dlls"):
        try:
            ort.preload_dlls()
        except Exception as e:  # noqa: BLE001
            print("preload_dlls warning:", e)
    provs = ort.get_available_providers()
    print("available providers:", provs)
    # Tiny MatMul graph, run on the best GPU provider actually available.
    X = helper.make_tensor_value_info("X", TensorProto.FLOAT, [256, 256])
    Y = helper.make_tensor_value_info("Y", TensorProto.FLOAT, [256, 256])
    Z = helper.make_tensor_value_info("Z", TensorProto.FLOAT, [256, 256])
    node = helper.make_node("MatMul", ["X", "Y"], ["Z"])
    model = helper.make_model(helper.make_graph([node], "mm", [X, Y], [Z]),
                              opset_imports=[helper.make_opsetid("", 17)])
    model.ir_version = 9
    onnx.checker.check_model(model)
    want = [p for p in ("CUDAExecutionProvider", "DmlExecutionProvider") if p in provs]
    assert want, "no GPU execution provider available"
    sess = ort.InferenceSession(model.SerializeToString(), providers=want + ["CPUExecutionProvider"])
    print("session providers:", sess.get_providers())
    x = np.random.rand(256, 256).astype(np.float32)
    y = np.random.rand(256, 256).astype(np.float32)
    z = sess.run(None, {"X": x, "Y": y})[0]
    print("ORT matmul max abs err:", float(np.abs(z - x @ y).max()))
    assert sess.get_providers()[0] in want, f"GPU provider did not load, got {sess.get_providers()}"


@section("ultralytics")
def check_ultralytics():
    import numpy as np
    import torch
    import ultralytics
    from ultralytics import YOLO
    from ultralytics.utils.downloads import GITHUB_ASSETS_NAMES
    print("ultralytics:", ultralytics.__version__)
    name = next((n for n in ("yolo26n.pt", "yolo11n.pt") if n in GITHUB_ASSETS_NAMES), "yolo11n.pt")
    weights = MODELS / name
    print("weights:", weights)
    model = YOLO(str(weights))
    print("weights on disk:", weights.exists(), f"{weights.stat().st_size/1e6:.1f} MB" if weights.exists() else "")
    img = (np.random.default_rng(0).integers(0, 255, (720, 1280, 3), dtype=np.uint8))
    med = lambda xs: sorted(xs)[len(xs) // 2]  # noqa: E731
    # Ultralytics 8.4.x: `quantize=16|32` replaces the deprecated `half=True` predict arg.
    for q in (32, 16):
        for _ in range(5):  # warmup
            model.predict(img, device=0, quantize=q, imgsz=640, verbose=False)
        ts, sp = [], []
        for _ in range(30):
            torch.cuda.synchronize()
            t0 = time.perf_counter()
            r = model.predict(img, device=0, quantize=q, imgsz=640, verbose=False)
            torch.cuda.synchronize()
            ts.append((time.perf_counter() - t0) * 1e3)
            sp.append(r[0].speed)
        # AutoBackend -> PyTorchBackend -> DetectionModel actually used for inference (8.4.x layout)
        be = model.predictor.model
        net = getattr(getattr(be, "backend", None), "model", None) or be
        p = next(net.parameters())
        print(f"{name} fp{q}: end-to-end median {med(ts):.1f} ms | median pre/inf/post = "
              f"{med([s['preprocess'] for s in sp]):.1f}/{med([s['inference'] for s in sp]):.1f}/"
              f"{med([s['postprocess'] for s in sp]):.1f} ms | boxes on noise image: {len(r[0].boxes)} | "
              f"backend param device/dtype: {p.device}/{p.dtype}")
        assert p.device.type == "cuda", "Ultralytics ran on CPU"


@section("transformers")
def check_transformers():
    import torch
    import transformers
    from transformers import ViTConfig, ViTModel
    print("transformers:", transformers.__version__)
    cfg = ViTConfig(hidden_size=32, num_hidden_layers=1, num_attention_heads=2,
                    intermediate_size=64, image_size=32, patch_size=8)
    m = ViTModel(cfg).cuda().eval()
    with torch.no_grad():
        out = m(pixel_values=torch.randn(2, 3, 32, 32, device="cuda"))
    print("tiny ViT on cuda:", tuple(out.last_hidden_state.shape), out.last_hidden_state.device)


@section("timm")
def check_timm():
    import torch
    import timm
    print("timm:", timm.__version__)
    m = timm.create_model("resnet18", pretrained=False).cuda().eval()
    with torch.no_grad(), torch.autocast("cuda", dtype=torch.float16):
        o = m(torch.randn(1, 3, 224, 224, device="cuda"))
    print("timm resnet18 (random init) on cuda:", tuple(o.shape))


@section("misc imports")
def check_misc():
    import importlib
    for mod in ("cv2", "numpy", "scipy", "pandas", "matplotlib", "supervision", "pycocotools.coco",
                "lap", "einops", "accelerate", "safetensors", "huggingface_hub", "onnx", "yaml",
                "PIL", "tqdm"):
        m = importlib.import_module(mod)
        top = importlib.import_module(mod.split(".")[0])
        print(f"{mod:18s} {getattr(top, '__version__', getattr(m, '__version__', '?'))}")
    import cv2
    print("cv2 CUDA devices (expected 0 for pip wheel):", cv2.cuda.getCudaEnabledDeviceCount() if hasattr(cv2, "cuda") else "n/a")
    import lap
    import numpy as np
    cost, x, y = lap.lapjv(np.array([[1.0, 2.0], [2.0, 1.0]]), extend_cost=True)
    print("lap.lapjv ok:", cost, list(x))


if __name__ == "__main__":
    for fn in (check_python, check_torch, check_torchvision, check_onnxruntime,
               check_ultralytics, check_transformers, check_timm, check_misc):
        fn()
    print("\n=== SUMMARY ===")
    for k, v in RESULTS.items():
        print(f"{k:14s} {v}")
    sys.exit(0 if all(v == "OK" for v in RESULTS.values()) else 1)
