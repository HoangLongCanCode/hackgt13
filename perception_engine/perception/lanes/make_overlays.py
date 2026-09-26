"""Annotated 10 s clips + temporal-stability stats for the lane block.

    python -m perception.lanes.make_overlays                      # default clips, TLNP+ Large
    python -m perception.lanes.make_overlays --clips b1f4491b-cf446195 --start 5 --seconds 10

Writes outputs/lanes/<clip>_<backend>_lanes.mp4 (mp4v, 1280x720) and outputs/lanes/video_stats.json.
Overlay legend: green = drivable, blue = ego lane (derived 'direct' area), red = lane-line mask,
cyan = ego boundaries, orange = counted neighbour lines, grey = other lines, white dots = AR anchor
points, yellow cross = vanishing point.
"""
from __future__ import annotations

import argparse
import json
import time

import cv2
import numpy as np

from perception.common.video import DATA_ROOT, OUTPUTS_ROOT, VideoFileInput
from perception.lanes.lanes import LaneDetector
from perception.lanes.viz import draw_analysis

DEFAULT_CLIPS = ["b1f4491b-cf446195", "b1ff4656-0435391e", "b23adb0d-8a7aaced"]


def find_clip(stem):
    for sub in ("val", "val_extra"):
        p = DATA_ROOT / "videos" / sub / f"{stem}.mov"
        if p.exists():
            return p
    raise FileNotFoundError(stem)


def run_clip(det: LaneDetector, stem: str, start: float, seconds: float, out_dir, write=True):
    src = VideoFileInput(find_clip(stem), start_s=start)
    n_max = int(round(seconds * src.fps))
    writer = None
    raw, smooth, times, confs, events = [], [], [], [], []
    det.reset()
    for k in range(n_max):
        fr = src.get_frame()
        if fr is None:
            break
        t0 = time.perf_counter()
        a = det.analyze(fr.image)
        times.append((time.perf_counter() - t0) * 1e3)
        rf = a.extras.get("rawFrame")
        raw.append(None if not rf else (rf["lanesLeft"] + 1 + rf["lanesRight"], rf["lanesLeft"] + 1))
        smooth.append((a.lane_state.laneCount, a.lane_state.currentLane))
        confs.append(a.lane_state.confidence)
        if a.extras.get("event"):
            events.append({"frame": fr.index, "t": round(fr.pts_s, 2), "event": a.extras["event"]})
        if write:
            vis = draw_analysis(fr.image, a, title=f"{stem[:8]} t={fr.pts_s:5.2f}s {det.backend_name}")
            cv2.putText(vis, "prototype - not a driving aid", (10, vis.shape[0] - 14), cv2.FONT_HERSHEY_SIMPLEX,
                        0.6, (255, 255, 255), 1, cv2.LINE_AA)
            if writer is None:
                path = out_dir / f"{stem}_{det.backend_name}_lanes.mp4"
                writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"mp4v"), src.fps,
                                         (vis.shape[1], vis.shape[0]))
            writer.write(vis)
    src.release()
    if writer is not None:
        writer.release()

    def changes(seq):
        vals = [s for s in seq if s is not None and s[0] is not None]
        return sum(1 for a, b in zip(vals, vals[1:]) if a != b)

    dur_min = len(smooth) / src.fps / 60.0
    stats = {
        "clip": stem, "start_s": start, "frames": len(smooth), "fps": round(src.fps, 2),
        "frames_with_estimate": sum(1 for s in smooth if s[0] is not None),
        "laneCount_mode": _mode([s[0] for s in smooth if s[0] is not None]),
        "currentLane_mode": _mode([s[1] for s in smooth if s[1] is not None]),
        "state_changes_per_min_raw": round(changes(raw) / max(dur_min, 1e-6), 1),
        "state_changes_per_min_smoothed": round(changes(smooth) / max(dur_min, 1e-6), 1),
        "confidence_p50": round(float(np.median(confs)), 3) if confs else None,
        "analyze_ms_p50_provisional": round(float(np.percentile(times[10:], 50)), 2) if len(times) > 10 else None,
        "analyze_ms_p95_provisional": round(float(np.percentile(times[10:], 95)), 2) if len(times) > 10 else None,
        "events": events[:20],
    }
    return stats


def _mode(v):
    if not v:
        return None
    vals, cnt = np.unique(v, return_counts=True)
    return int(vals[np.argmax(cnt)])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", default="twinlitenetplus_large")
    ap.add_argument("--clips", nargs="+", default=DEFAULT_CLIPS)
    ap.add_argument("--start", type=float, default=5.0)
    ap.add_argument("--seconds", type=float, default=10.0)
    ap.add_argument("--no-video", action="store_true")
    a = ap.parse_args()
    out = OUTPUTS_ROOT / "lanes"
    out.mkdir(parents=True, exist_ok=True)
    det = LaneDetector(a.backend, temporal=True)
    allstats = []
    for c in a.clips:
        s = run_clip(det, c, a.start, a.seconds, out, write=not a.no_video)
        print(json.dumps({k: v for k, v in s.items() if k != "events"}), flush=True)
        allstats.append(s)
    det.close()
    p = out / "video_stats.json"
    prev = json.loads(p.read_text()) if p.exists() else {}
    prev[a.backend] = allstats
    p.write_text(json.dumps(prev, indent=1))
    print("wrote", p)


if __name__ == "__main__":
    main()
