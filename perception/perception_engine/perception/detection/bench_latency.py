"""Interleaved latency benchmark for all detector runs (PROVISIONAL on a shared GPU).

    cd perception_engine
    .venv\\Scripts\\python.exe -m perception.detection.bench_latency --rounds 3 --frames 100

Each round loads every run in turn, warms up, times `detect()` on the same preloaded 1280x720 BGR frames
(conf 0.25, deployment setting), then frees it. Rounds are interleaved, so contention from other GPU jobs
is spread across models. We report the median of the per-round p50 values, plus the per-round p50s.
Results go to outputs/detection/latency.json.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import time

os.environ.setdefault("YOLO_AUTOINSTALL", "False")

import cv2
import numpy as np
import torch

from perception.common.video import DATA_ROOT, OUTPUTS_ROOT
from perception.detection.eval_bdd import DEFAULT_RUNS, parse_run
from perception.detection.detector import Detector


def _gpu_state() -> str:
    try:
        return subprocess.run(["nvidia-smi", "--query-gpu=memory.used,utilization.gpu", "--format=csv,noheader"],
                              capture_output=True, text=True, timeout=10).stdout.strip()
    except Exception as e:  # pragma: no cover
        return repr(e)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", nargs="*", default=DEFAULT_RUNS)
    ap.add_argument("--rounds", type=int, default=3)
    ap.add_argument("--frames", type=int, default=100)
    args = ap.parse_args(argv)
    torch.backends.cudnn.benchmark = False
    torch.cuda.set_per_process_memory_fraction(min(1.0, 1.5 * 1024**3 / torch.cuda.get_device_properties(0).total_memory))
    gt = json.loads((DATA_ROOT / "labels" / "eval_subset_coco.json").read_text())
    imgs = [cv2.imread(str(DATA_ROOT / "images" / "val" / im["file_name"])) for im in gt["images"][: args.frames]]
    res = {r: {"round_p50": [], "round_p90": [], "peak_vram_mb": 0.0} for r in args.runs}
    states = []
    for rd in range(args.rounds):
        states.append(_gpu_state())
        for run in args.runs:
            preset, imgsz, extra = parse_run(run)
            torch.cuda.reset_peak_memory_stats()
            det = Detector(weights=preset, imgsz=imgsz, conf=0.25, allow_download=False, **extra)
            det.warmup(15)
            ts = []
            for im in imgs:
                det.detect(im)
                ts.append(det.last_timings_ms["detect_total"])
            res[run]["round_p50"].append(float(np.percentile(ts, 50)))
            res[run]["round_p90"].append(float(np.percentile(ts, 90)))
            res[run]["peak_vram_mb"] = max(res[run]["peak_vram_mb"], torch.cuda.max_memory_allocated() / 1e6)
            det.close()
            del det
            torch.cuda.empty_cache()
            print(f"round {rd} {run}: p50 {res[run]['round_p50'][-1]:.1f} ms", flush=True)
    for r in res.values():
        r["p50_ms"] = float(np.median(r["round_p50"]))
        r["p90_ms"] = float(np.median(r["round_p90"]))
        r["p50_ms_min_round"] = float(np.min(r["round_p50"]))  # least-contended round
    out = {"note": "PROVISIONAL: GPU shared with other agents; detect() wall time incl. letterbox/resize, "
                   "inference, NMS/postprocess and class mapping; conf 0.25; 1280x720 BGR input; PyTorch eager FP16",
           "gpu": torch.cuda.get_device_name(0), "torch": torch.__version__, "frames_per_round": len(imgs),
           "rounds": args.rounds, "gpu_state_at_round_start (used MiB, util %)": states, "runs": res,
           "timestamp": time.strftime("%Y-%m-%d %H:%M:%S")}
    (OUTPUTS_ROOT / "detection" / "latency.json").write_text(json.dumps(out, indent=1))
    print(json.dumps({k: v["p50_ms"] for k, v in res.items()}, indent=1))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
