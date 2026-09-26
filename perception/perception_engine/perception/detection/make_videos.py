"""Annotated demo clips + per-frame JSONL for the default detector.

    cd perception_engine
    .venv\\Scripts\\python.exe -m perception.detection.make_videos            # city + night, 10 s each

Writes outputs/detection/<clip>_<preset>.mp4 (1280x720, mp4v, source fps) and
outputs/detection/<clip>_<preset>.jsonl (one FrameResult per frame, detections only).
"""
from __future__ import annotations

import argparse
import json
import os
import time

os.environ.setdefault("YOLO_AUTOINSTALL", "False")

import cv2
import numpy as np
import torch

from perception.common.schemas import FrameResult
from perception.common.video import DATA_ROOT, OUTPUTS_ROOT, VideoFileInput
from perception.detection.detector import DEFAULT_PRESET, PRESETS, Detector
from perception.detection.viz import draw_detections, draw_hud, draw_legend

CLIPS = {"b1ff4656-0435391e": "city intersection, day", "b23adb0d-8a7aaced": "city, night"}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--preset", default=DEFAULT_PRESET)
    ap.add_argument("--clips", nargs="*", default=list(CLIPS))
    ap.add_argument("--start", type=float, default=5.0, help="start time (s); the labelled keyframe is at 10 s")
    ap.add_argument("--seconds", type=float, default=10.0)
    ap.add_argument("--conf", type=float, default=0.25)
    args = ap.parse_args(argv)
    torch.backends.cudnn.benchmark = False
    if torch.cuda.is_available():
        torch.cuda.set_per_process_memory_fraction(min(1.0, 1.5 * 1024**3 / torch.cuda.get_device_properties(0).total_memory))

    out_dir = OUTPUTS_ROOT / "detection"
    out_dir.mkdir(parents=True, exist_ok=True)
    det = Detector(weights=args.preset, conf=args.conf)
    det.warmup(5)
    summary = {}
    for clip in args.clips:
        src = DATA_ROOT / "videos" / "val" / f"{clip}.mov"
        vin = VideoFileInput(src, start_s=args.start)
        n = int(args.seconds * vin.fps)  # floor: clip stays <= the requested length
        vin.max_frames = n
        stem = f"{clip}_{args.preset}"
        vw = cv2.VideoWriter(str(out_dir / f"{stem}.mp4"), cv2.VideoWriter_fourcc(*"mp4v"), vin.fps, (1280, 720))
        lat, counts = [], []
        with open(out_dir / f"{stem}.jsonl", "w", encoding="utf-8") as jf:
            for fr in vin:
                assert fr.image.shape == (720, 1280, 3), fr.image.shape
                dets = det.detect(fr.image)
                ms = det.last_timings_ms["detect_total"]
                lat.append(ms)
                counts.append(len(dets))
                jf.write(json.dumps(FrameResult(fr.index, fr.pts_s, detections=dets,
                                                timingsMs={"detection": ms}).to_dict()) + "\n")
                vis = draw_detections(fr.image.copy(), dets)
                draw_hud(vis, [f"KSR perception | detection: {args.preset} @ {det.imgsz}, conf {det.conf:.2f}",
                               f"{clip} ({CLIPS.get(clip, '')})  t={fr.pts_s:5.2f}s  frame {fr.index}",
                               f"{len(dets)} objects | detect {ms:4.1f} ms (provisional, shared GPU)",
                               "research demo, BDD100K-trained weights: non-commercial"])
                draw_legend(vis)
                vw.write(vis)
        vw.release()
        vin.release()
        summary[clip] = {"frames": len(lat), "fps_src": vin.fps, "start_s": args.start,
                         "detect_ms_p50": float(np.median(lat)), "detect_ms_p90": float(np.percentile(lat, 90)),
                         "objects_per_frame_mean": float(np.mean(counts)),
                         "mp4": str(out_dir / f"{stem}.mp4"), "jsonl": str(out_dir / f"{stem}.jsonl")}
        print(clip, summary[clip], flush=True)
    (out_dir / "videos_summary.json").write_text(json.dumps({"preset": args.preset, "conf": args.conf,
                                                             "clips": summary}, indent=1))
    det.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
