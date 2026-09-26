"""Latency micro-benchmark for the lane backends (PROVISIONAL on a shared GPU).

    python -m perception.lanes.tools.bench_latency [--frames 150]

Decodes `frames` frames of the highway clip once, then per backend: warm-up, then
per-frame timings of the model forward (GPU-synchronised), the whole segmentation call
(pre-processing + model + margin maps to host) and LaneDetector.analyze() end to end
(segmentation + post-processing + 1280x720 masks). Writes outputs/lanes/latency.json.
"""
from __future__ import annotations

import argparse
import json
import time

import numpy as np
import torch

from perception.common.video import OUTPUTS_ROOT, VideoFileInput
from perception.lanes.lanes import LaneDetector
from perception.lanes.make_overlays import find_clip

CONFIGS = [("twinlitenetplus_large", {}), ("twinlitenetplus_large", {"half": True}),
           ("twinlitenetplus_medium", {}), ("yolop_onnx", {}),
           ("comma10k_segnet", {}), ("comma10k_segnet", {"half": True})]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--frames", type=int, default=150)
    ap.add_argument("--clip", default="b1f4491b-cf446195")
    a = ap.parse_args()
    src = VideoFileInput(find_clip(a.clip), start_s=5)
    frames = [f.image for f in (src.get_frame() for _ in range(a.frames)) if f is not None]
    src.release()
    out = {"clip": a.clip, "frames": len(frames), "gpu": torch.cuda.get_device_name(0),
           "note": "GPU shared with other agents while measured: PROVISIONAL", "results": []}
    for name, opts in CONFIGS:
        torch.cuda.empty_cache()
        torch.cuda.reset_peak_memory_stats()
        det = LaneDetector(name, **opts)
        for f in frames[:10]:
            det.analyze(f)
        model, seg, total, post = [], [], [], []
        for f in frames:
            t0 = time.perf_counter()
            an = det.analyze(f)
            total.append((time.perf_counter() - t0) * 1e3)
            model.append(an.timings_ms["model_ms"])
            seg.append(an.timings_ms["segment_ms"])
            post.append(an.timings_ms["lanestate_ms"])
        r = {"backend": name, "opts": opts,
             "model_ms_p50": round(float(np.median(model)), 2), "model_ms_p95": round(float(np.percentile(model, 95)), 2),
             "segment_ms_p50": round(float(np.median(seg)), 2),
             "lanestate_ms_p50": round(float(np.median(post)), 2),
             "analyze_total_ms_p50": round(float(np.median(total)), 2),
             "analyze_total_ms_p95": round(float(np.percentile(total, 95)), 2),
             "torch_peak_alloc_mib": round(torch.cuda.max_memory_allocated() / 2 ** 20, 1)}
        print(json.dumps(r), flush=True)
        out["results"].append(r)
        det.close()
        del det
    (OUTPUTS_ROOT / "lanes" / "latency.json").write_text(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
