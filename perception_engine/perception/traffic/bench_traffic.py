"""Per-component latency (p50 / p95 ms) for the traffic block. PROVISIONAL when the GPU is shared.

    .venv\\Scripts\\python.exe -m perception.traffic.bench_traffic [--iters 100]

Measures, on real crops from the 250-image eval subset (batch = one frame's worth of crops):
  light classify, 1-8 lights per frame: HSV; Autoware on CUDA EP (orient both / auto; ORT default and
  HEURISTIC cudnn algo) and CPU EP (both / auto); TemporalSmoother.update
  signs: LISA on context crops (3 signs per call), PP-OCRv6 small rec (one band)
Writes metrics.json "bench" plus nvidia-smi utilization at start and end (a load indicator).
"""
from __future__ import annotations

import argparse
import json
import random
import subprocess
import time

import numpy as np

from perception.traffic.eval_bdd import load_crops, load_gt, update_metrics


def gpu_util() -> str:
    try:
        return subprocess.run(["nvidia-smi", "--query-gpu=utilization.gpu,memory.used", "--format=csv,noheader"],
                              capture_output=True, text=True, timeout=10).stdout.strip()
    except Exception as e:  # noqa: BLE001
        return f"n/a ({e})"


def timeit(fn, iters: int) -> dict:
    ts = []
    for _ in range(iters):
        t0 = time.perf_counter()
        fn()
        ts.append((time.perf_counter() - t0) * 1000)
    return {"p50": round(float(np.median(ts)), 3), "p95": round(float(np.percentile(ts, 95)), 3), "n": iters}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--iters", type=int, default=100)
    args = ap.parse_args()
    import torch  # noqa: F401
    from perception.traffic.lights import AutowareLightClassifier, HSVParams, TemporalSmoother, hsv_probs

    res = {"gpu_util_start": gpu_util(), "note": "PROVISIONAL: GPU shared with other agents; Windows/WDDM; Python overhead included"}
    items, _ = load_gt("subset")
    crops = load_crops(items)
    rng = random.Random(0)
    frames = []
    for _ in range(args.iters):
        k = rng.randint(1, 8)
        j = rng.randint(0, len(crops) - k)
        frames.append(crops[j:j + k])
    it = iter(range(10**9))

    def per_frame(fn):
        def run():
            fn(frames[next(it) % len(frames)])
        return run

    p = HSVParams()
    res["hsv_1to8_lights"] = timeit(per_frame(lambda fr: [hsv_probs(c, p) for c in fr]), args.iters)
    print("hsv", res["hsv_1to8_lights"], flush=True)
    for device, orient, algo in (("cuda", "both", None), ("cuda", "auto", None), ("cuda", "both", "HEURISTIC"),
                                 ("cpu", "both", None), ("cpu", "auto", None)):
        a = AutowareLightClassifier(device=device, orient=orient, cudnn_algo=algo)
        for k in range(1, 9):  # warm-up every batch size once
            a.probs(crops[:k])
        key = f"autoware_{device}_{orient}_{algo or 'ortdefault'}_1to8_lights"
        res[key] = timeit(per_frame(a.probs), args.iters)
        print(key, res[key], a.providers[0], flush=True)
        del a
    sm = TemporalSmoother("hmm")
    pv = np.array([0.7, 0.1, 0.1, 0.1])
    res["smoother_hmm_update"] = timeit(lambda: sm.update(1, pv, time.perf_counter()), args.iters)
    from perception.traffic.signs import PPOCRRec, SignRecognizer
    rec = SignRecognizer("lisa_crops", ocr=None)
    img = np.zeros((720, 1280, 3), np.uint8)
    boxes = [[600, 300, 630, 330], [900, 280, 925, 310], [200, 320, 240, 360]]
    rec._typed_boxes(img, boxes)
    res["lisa_crops_3_signs_no_ocr"] = timeit(lambda: rec._typed_boxes(img, boxes), args.iters)
    print("lisa", res["lisa_crops_3_signs_no_ocr"], flush=True)
    ocr = PPOCRRec()
    band = (np.random.rand(70, 130, 3) * 255).astype(np.uint8)
    ocr.read(band)
    res["ppocrv6_small_rec_one_band"] = timeit(lambda: ocr.read(band), args.iters)
    print("ocr", res["ppocrv6_small_rec_one_band"], ocr.providers[0], flush=True)
    res["gpu_util_end"] = gpu_util()
    try:
        res["cuda_max_mem_allocated_mb_torch"] = round(torch.cuda.max_memory_allocated() / 2**20, 1)
    except Exception:
        pass
    update_metrics("bench", res)
    print(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()
