"""Annotated clips + temporal-stability metrics for the segmentation block (measured here).

    .venv\\Scripts\\python.exe -m perception.segmentation.make_overlays

For each clip, 10 s starting at --start (default 5 s): class colors, ego road polygon (white),
ego corridor (green), horizon (yellow), VP (cross), 10/20/40 m anchors (orange = valid,
red = behind a vehicle, grey = not reached). Writes outputs/segmentation/overlay_<id>.mp4,
road_<id>.jsonl (FrameResult lines with `road` + timings) and merges a "video" section
into outputs/segmentation/metrics.json.
"""
from __future__ import annotations

import argparse
import json
import os
import time

os.environ.setdefault("YOLO_AUTOINSTALL", "False")

import cv2
import numpy as np

from perception.common.schemas import FrameResult
from perception.common.video import DATA_ROOT, OUTPUTS_ROOT, VideoFileInput
from perception.segmentation.geometry import compute_road_geometry
from perception.segmentation.semantic import SemanticSegmenter
from perception.segmentation.viz import draw_overlay

OUT = OUTPUTS_ROOT / "segmentation"
CLIPS = {"b20e291a-6012d836": "residential, clear, day",
         "b204a5c1-064b0040": "snow, city street, day",
         "b23adb0d-8a7aaced": "night, city street (failure-mode example)"}


def poly_mask(poly, shape, scale=0.25):
    h, w = int(shape[0] * scale), int(shape[1] * scale)
    m = np.zeros((h, w), np.uint8)
    if poly and len(poly) >= 3:
        cv2.fillPoly(m, [np.round(np.array(poly) * scale).astype(np.int32)], 1)
    return m.astype(bool)


def iou(a, b):
    u = (a | b).sum()
    return float((a & b).sum() / u) if u else np.nan


def run_clip(vid: str, backend: str, start: float, seconds: float, write_video: bool) -> dict:
    path = DATA_ROOT / "videos" / "val" / f"{vid}.mov"
    src = VideoFileInput(path, start_s=start)
    n_frames = int(round(seconds * src.fps))
    seg = SemanticSegmenter(backend=backend, temporal=True)
    writer = None
    if write_video:
        writer = cv2.VideoWriter(str(OUT / f"overlay_{vid}.mp4"), cv2.VideoWriter_fourcc(*"mp4v"), src.fps, (1280, 720))
    jl = open(OUT / f"road_{vid}.jsonl", "w", encoding="utf-8")
    hz_t, hz_raw, polys, a20, methods, t_seg, t_geo = [], [], [], [], [], [], []
    valid20 = []
    for k in range(n_frames):
        fr = src.get_frame()
        if fr is None:
            break
        img = fr.image
        assert img.shape == (720, 1280, 3), img.shape
        lab = seg.segment(img)
        geo = seg.road_geometry(img, seg=lab)
        info = seg.last_info
        t_seg.append(seg.timings_ms["segment"]); t_geo.append(seg.timings_ms["road_geometry"])
        # untracked horizon on the same segmentation, for the stability comparison
        g_raw, _ = compute_road_geometry(lab, seg.camera, seg.geometry_cfg, None, frame_bgr=img)
        hz_t.append(geo.horizonY); hz_raw.append(g_raw.horizonY)
        methods.append(info["horizonRaw"]["method"])
        polys.append(poly_mask(geo.egoPathPolygon, lab.shape))
        a = next(x for x in geo.anchorPoints if x.get("name") == "ego_path_20m")
        valid20.append(bool(a["valid"]))
        a20.append(a["xy"][0] if a["valid"] and a["xy"] else np.nan)
        res = FrameResult(frameIndex=fr.index, ptsSeconds=fr.pts_s, road=geo,
                          timingsMs={"segment": t_seg[-1], "road_geometry": t_geo[-1]})
        jl.write(json.dumps(res.to_dict()) + "\n")
        if writer is not None:
            hud = (f"{vid}  {backend}  t={fr.pts_s:5.2f}s  seg {t_seg[-1]:4.1f} ms  geo {t_geo[-1]:4.1f} ms  "
                   f"horizon {geo.horizonY:.0f} ({info['horizonRaw']['method']})  road {geo.drivableCoverage:.2f}")
            writer.write(draw_overlay(img, lab, geo, info, hud=hud))
    jl.close()
    if writer is not None:
        writer.release()
    src.release()
    seg.release()
    hz_t, hz_raw = np.array(hz_t, float), np.array(hz_raw, float)
    ious = [iou(polys[i], polys[i + 1]) for i in range(len(polys) - 1)]
    a20 = np.array(a20, float)
    d20 = np.abs(np.diff(a20))
    d20 = d20[~np.isnan(d20)]
    meth, cnt = np.unique(methods, return_counts=True)
    return {
        "scenario": CLIPS.get(vid, ""), "backend": backend, "frames": int(len(hz_t)), "start_s": start,
        "horizon_tracked": {"std_px": round(float(hz_t.std()), 1), "mean_abs_frame_delta_px": round(float(np.abs(np.diff(hz_t)).mean()), 2)},
        "horizon_untracked": {"std_px": round(float(hz_raw.std()), 1), "mean_abs_frame_delta_px": round(float(np.abs(np.diff(hz_raw)).mean()), 2)},
        "rawMethodShare": {m: round(100.0 * c / len(methods), 1) for m, c in zip(meth, cnt)},
        "egoPolygon_consecutive_IoU_median": round(float(np.nanmedian(ious)), 3) if ious else None,
        "anchor20m_validRate": round(100.0 * float(np.mean(valid20)), 1),
        "anchor20m_x_median_abs_frame_delta_px": round(float(np.median(d20)), 2) if len(d20) else None,
        "latency_ms": {"segment_p50": round(float(np.median(t_seg)), 2), "road_geometry_p50": round(float(np.median(t_geo)), 2)},
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", default="pidnet_s")
    ap.add_argument("--clips", default=",".join(CLIPS))
    ap.add_argument("--start", type=float, default=5.0)
    ap.add_argument("--seconds", type=float, default=10.0)
    ap.add_argument("--no-video", action="store_true")
    args = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    video = {}
    for vid in [c for c in args.clips.split(",") if c]:
        t = time.time()
        video[vid] = run_clip(vid, args.backend, args.start, args.seconds, not args.no_video)
        print(vid, json.dumps(video[vid]), f"{time.time() - t:.1f}s", flush=True)
    mpath = OUT / "metrics.json"
    metrics = json.load(open(mpath, encoding="utf-8")) if mpath.exists() else {}
    metrics.setdefault("video", {}).update(video)
    with open(mpath, "w", encoding="utf-8") as f:
        json.dump(metrics, f, indent=1)


if __name__ == "__main__":
    main()
