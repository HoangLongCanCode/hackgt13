"""Annotated <= 10 s demo clip for the depth & distance block (Knuckle Sandwich Robotics Inc., KSR).

Detections: Ultralytics YOLO26n (COCO, models/yolo26n.pt) + ByteTrack, used only
as a stand-in driver for this block (the real detector/tracker live in
perception/detection and perception/tracking). Distances: DistanceEstimator.

    .venv\\Scripts\\python.exe -m perception.depth.demo_clip --video b1f4491b-cf446195 --start 14 --seconds 10
"""
from __future__ import annotations

import argparse
import os
import time

os.environ.setdefault("YOLO_AUTOINSTALL", "False")

import cv2
import numpy as np

from perception.common.schemas import Detection
from perception.common.video import DATA_ROOT, MODELS_ROOT, OUTPUTS_ROOT, VideoFileInput
from perception.depth.distance import DistanceEstimator
from perception.depth.eval_bdd import colorize

COCO_TO_BDD = {0: "pedestrian", 1: "bicycle", 2: "car", 3: "motorcycle", 5: "bus", 7: "truck"}


def color_for(z):
    if z is None:
        return (160, 160, 160)
    if z < 10:
        return (0, 0, 255)
    if z < 20:
        return (0, 165, 255)
    return (0, 220, 0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--video", default="b1f4491b-cf446195")
    ap.add_argument("--start", type=float, default=14.0)
    ap.add_argument("--seconds", type=float, default=10.0)
    ap.add_argument("--backend", default="da3_metric_large")
    ap.add_argument("--depth-every", type=int, default=1)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()
    from ultralytics import YOLO

    det_model = YOLO(str(MODELS_ROOT / "yolo26n.pt"))
    est = DistanceEstimator(args.backend, depth_every=args.depth_every)
    src = DATA_ROOT / "videos" / "val" / f"{args.video}.mov"
    if not src.exists():
        src = DATA_ROOT / "videos" / "val_extra" / f"{args.video}.mov"
    vin = VideoFileInput(src, start_s=args.start)
    n_max = int(min(args.seconds, 10.0) * 30)  # written at 30 fps -> <= 10 s
    out_p = args.out or str(OUTPUTS_ROOT / "depth" / f"demo_{args.video}_{args.backend}.mp4")
    vw = cv2.VideoWriter(out_p, cv2.VideoWriter_fourcc(*"mp4v"), 30.0, (1280, 720))
    t_depth, t_all, n = [], [], 0
    for fr in vin:
        if n >= n_max:
            break
        t0 = time.perf_counter()
        img = fr.image
        res = det_model.track(img, persist=True, tracker="bytetrack.yaml", verbose=False, device=0, half=True,
                              classes=list(COCO_TO_BDD), conf=0.25, imgsz=640)[0]
        dets = []
        if res.boxes is not None and len(res.boxes):
            ids = res.boxes.id.int().tolist() if res.boxes.id is not None else [None] * len(res.boxes)
            for b, c, s, i in zip(res.boxes.xyxy.tolist(), res.boxes.cls.int().tolist(), res.boxes.conf.tolist(), ids):
                dets.append(Detection(COCO_TO_BDD[c], b, float(s), id=i))
        pts = fr.pts_s if fr.pts_s > 0 else fr.index / vin.fps
        details = est.estimate_detailed(img, dets, pts_s=pts)
        t_all.append((time.perf_counter() - t0) * 1000)
        if est.last_info.get("depth_ms"):
            t_depth.append(est.last_info["depth_ms"])
        vis = img.copy()
        info = est.last_info
        if info.get("horizon_y") is not None:
            y = int(info["horizon_y"])
            cv2.line(vis, (0, y), (1279, y), (0, 255, 255), 1)
        # lead vehicle: nearest vehicle whose centre is in the middle 20 % of the frame
        cands = [d for d in details if d["class"] in ("car", "truck", "bus") and d["distance"] is not None
                 and 512 <= (d["bbox"][0] + d["bbox"][2]) / 2 <= 768]
        lead = min(cands, key=lambda d: d["distance"]) if cands else None
        for d in details:
            x1, y1, x2, y2 = map(int, d["bbox"])
            z = d["distance"]
            col = color_for(z)
            cv2.rectangle(vis, (x1, y1), (x2, y2), col, 3 if d is lead else 1)
            if z is None:
                continue
            txt = f"{z:.1f} m" + (f" ({d['confidence']:.2f})" if d is lead else "")
            (tw, th), _ = cv2.getTextSize(txt, cv2.FONT_HERSHEY_SIMPLEX, 0.5, 1)
            cv2.rectangle(vis, (x1, max(0, y1 - th - 5)), (x1 + tw + 4, y1), (0, 0, 0), -1)
            cv2.putText(vis, txt, (x1 + 2, y1 - 4), cv2.FONT_HERSHEY_SIMPLEX, 0.5, col, 1, cv2.LINE_AA)
        if est.last_depth is not None:
            inset = cv2.resize(colorize(est.last_depth), (320, 180), interpolation=cv2.INTER_AREA)
            vis[10:190, 1280 - 330:1280 - 10] = inset
            cv2.rectangle(vis, (1280 - 330, 10), (1280 - 10, 190), (255, 255, 255), 1)
        hdr = [f"KSR depth & distance | {args.backend} + flat ground + size prior | YOLO26n+ByteTrack boxes",
               f"lead vehicle: {lead['distance']:.1f} m  [{lead['method']}, conf {lead['confidence']:.2f}]" if lead else "lead vehicle: -",
               f"assumed f=700px, H_cam={info.get('camera_height_m', 0):.2f} m, horizon y={info.get('horizon_y', 0):.0f}"
               f" ({info.get('horizon_source', '')})  | measurement only, not a safety system"]
        band = vis[0:78, 0:930]
        band[:] = (band * 0.35).astype(np.uint8)
        for k, line in enumerate(hdr):
            cv2.putText(vis, line, (10, 22 + 22 * k), cv2.FONT_HERSHEY_SIMPLEX, 0.52, (255, 255, 255), 1, cv2.LINE_AA)
        vw.write(vis)
        if n == int(n_max * 0.6):
            cv2.imwrite(out_p.replace(".mp4", "_frame.png"), vis)
        n += 1
    vw.release()
    vin.release()
    est.close()
    print(f"wrote {out_p}: {n} frames; per-frame total p50 {np.median(t_all[5:]):.1f} ms, depth p50 "
          f"{np.median(t_depth[5:]) if len(t_depth) > 5 else float('nan'):.1f} ms (provisional, shared GPU)")


if __name__ == "__main__":
    main()
