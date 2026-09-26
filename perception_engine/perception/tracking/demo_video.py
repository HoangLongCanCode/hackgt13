"""30 fps demo: detector + default tracker + motion layer on BDD100K clips.

    cd perception_engine
    .venv\\Scripts\\python.exe -m perception.tracking.demo_video
    .venv\\Scripts\\python.exe -m perception.tracking.demo_video --clips b1f4491b-cf446195 --seconds 15 --video-seconds 10

Per clip it writes these outputs to outputs/tracking/:
  demo_<clip>_tracks.jsonl   one FrameResult.to_dict() per frame for the whole run (default 15 s),
                             plus "trackMeta" (hits, scale-rate CI, lateral widths/s, ...)
  demo_<clip>.mp4            annotated video (IDs, class, TTC, APPROACHING / closing, ego corridor
                             outline in orange), capped at 10 s, 960x540
  demo_<clip>_ttc.png        scale-rate / TTC timeline of the longest-lived tracks
"""
from __future__ import annotations

import argparse
import json
import os
import time
from pathlib import Path

os.environ.setdefault("YOLO_AUTOINSTALL", "False")

import cv2  # noqa: E402
import numpy as np  # noqa: E402

from perception.common.paths import portable_paths  # noqa: E402
from perception.common.schemas import FrameResult  # noqa: E402
from perception.common.video import DATA_ROOT, OUTPUTS_ROOT, VideoFileInput  # noqa: E402

OUT = OUTPUTS_ROOT / "tracking"
CLIPS = ["b1f4491b-cf446195", "b1d968b9-ce42734f"]


def _color(i: int) -> tuple[int, int, int]:
    rng = np.random.default_rng(i * 7919)
    c = rng.integers(64, 256, 3)
    return int(c[0]), int(c[1]), int(c[2])


def draw(img, tracks, dets, meta, info: str, scale: float, corridor=None):
    out = cv2.resize(img, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA) if scale != 1 else img.copy()
    if corridor is not None:  # static fallback ego corridor used by the approaching gate
        pts = (np.asarray(corridor, np.float32) * np.float32([out.shape[1], out.shape[0]])).astype(np.int32)
        cv2.polylines(out, [pts.reshape(-1, 1, 2)], True, (0, 200, 255), 1, cv2.LINE_AA)
    for d in dets:  # untracked detections: thin grey
        if d.id is None and d.confidence >= 0.4 and d.cls not in ("traffic light", "traffic sign"):
            x1, y1, x2, y2 = [int(v * scale) for v in d.bbox]
            cv2.rectangle(out, (x1, y1), (x2, y2), (150, 150, 150), 1)
    for t in tracks:
        x1, y1, x2, y2 = [int(v * scale) for v in t.bbox]
        appr = bool(t.approaching)
        col = (0, 0, 255) if appr else _color(t.id)
        cv2.rectangle(out, (x1, y1), (x2, y2), col, 3 if appr else 2)
        if (t.bbox[3] - t.bbox[1]) < 40 and not appr:  # declutter: small / far boxes get no label
            continue
        lab = f"#{t.id} {t.cls}"
        if t.ttc_s is not None and t.ttc_s <= 10:
            lab += f" TTC {t.ttc_s:.1f}s"
        if appr:
            lab += " APPROACHING"
        elif meta.get(t.id, {}).get("closing"):
            lab += " closing"
        (tw, th), _ = cv2.getTextSize(lab, cv2.FONT_HERSHEY_SIMPLEX, 0.45, 1)
        yt = max(y1 - 4, th + 4)
        cv2.rectangle(out, (x1, yt - th - 4), (x1 + tw + 4, yt + 2), col, -1)
        cv2.putText(out, lab, (x1 + 2, yt - 2), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 0, 0), 1, cv2.LINE_AA)
    cv2.rectangle(out, (0, 0), (out.shape[1], 24), (0, 0, 0), -1)
    cv2.putText(out, info, (6, 17), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1, cv2.LINE_AA)
    return out


def ttc_plot(series: dict, clip: str, path: Path, fps: float) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    top = sorted(series.items(), key=lambda kv: len(kv[1]), reverse=True)[:6]
    fig, (a1, a2) = plt.subplots(2, 1, figsize=(10, 6), sharex=True)
    for tid, rows in top:
        t = np.array([r[0] for r in rows]); s = np.array([np.nan if r[1] is None else r[1] for r in rows])
        ttc = np.array([np.nan if r[2] is None else r[2] for r in rows]); ap = np.array([bool(r[3]) for r in rows])
        cls = rows[-1][4]
        ln, = a1.plot(t, s, lw=1.2, label=f"#{tid} {cls}")
        a2.plot(t, ttc, lw=1.2, color=ln.get_color())
        if ap.any():
            a2.scatter(t[ap], np.clip(ttc[ap], 0, 30), s=6, color=ln.get_color(), marker="s")
    a1.axhline(0, color="k", lw=0.6)
    a1.axhline(0.10, color="r", lw=0.6, ls="--")
    a1.set_ylabel("scale rate d ln h/dt [1/s]")
    a1.legend(fontsize=7, ncol=3)
    a1.set_title(f"{clip}: motion cues of the 6 longest tracks (30 fps, 1 s Theil-Sen window). "
                 "Dashed = approaching on-threshold", fontsize=9)
    a2.set_ylabel("TTC [s] (squares = approaching)")
    a2.set_ylim(0, 30)
    a2.set_xlabel("time [s]")
    for a in (a1, a2):
        a.grid(alpha=0.3)
    fig.tight_layout()
    fig.savefig(path, dpi=110)
    plt.close(fig)


def run_clip(clip: str, backend: str, seconds: float, video_seconds: float, video_start: float,
             device: str, out_w: int) -> dict:
    import torch

    from perception.tracking.detector import YoloDetector
    from perception.tracking.tracker import Tracker

    path = DATA_ROOT / "videos" / "val" / f"{clip}.mov"
    src = VideoFileInput(path)
    fps = src.fps
    n_frames = int(round(seconds * fps))
    det = YoloDetector(device=device, conf=0.05, classes=None)
    trk = Tracker(backend, frame_rate=fps)
    OUT.mkdir(parents=True, exist_ok=True)
    jl = open(OUT / f"demo_{clip}_tracks.jsonl", "w", encoding="utf-8")
    scale = out_w / 1280.0
    vw = cv2.VideoWriter(str(OUT / f"demo_{clip}.mp4"), cv2.VideoWriter_fourcc(*"mp4v"), fps,
                         (out_w, int(round(720 * scale))))
    det_ms, trk_ms, series, n_appr, n_close, ids = [], [], {}, 0, 0, set()
    raw_pts = []
    for k in range(n_frames):
        f = src.get_frame()
        if f is None:
            break
        raw_pts.append(f.pts_s)
        dets = det(f.image)
        det_ms.append(det.last_ms)
        tracks = trk.update(dets, f.image, f.pts_s)
        trk_ms.append(trk.last_ms)
        t_used = trk._last_t
        fr = FrameResult(frameIndex=f.index, ptsSeconds=f.pts_s, detections=dets, tracks=tracks,
                         timingsMs={"detector": det.last_ms, "tracker": trk.last_ms})
        d = fr.to_dict()
        d["trackerTimeSeconds"] = round(t_used, 4)
        d["trackMeta"] = {str(i): {kk: (round(v, 4) if isinstance(v, float) else v) for kk, v in m.items()}
                          for i, m in trk.last_meta.items()}
        jl.write(json.dumps(d) + "\n")
        for t in tracks:
            ids.add(t.id)
            n_appr += bool(t.approaching)
            n_close += bool(trk.last_meta.get(t.id, {}).get("closing"))
            series.setdefault(t.id, []).append((t_used, t.scale_rate, t.ttc_s, t.approaching, t.cls))
        tv = f.index / fps
        if video_start <= tv < video_start + video_seconds:
            info = (f"{clip}  t={tv:5.2f}s  {backend}  tracks={len(tracks)}  det {det.last_ms:4.1f} ms  "
                    f"trk {trk.last_ms:4.1f} ms  [prototype - measurements, not safety claims]")
            vw.write(draw(f.image, tracks, dets, trk.last_meta, info, scale, trk._layer.motion.cfg.corridor_norm))
    jl.close()
    vw.release()
    src.release()
    ttc_plot(series, clip, OUT / f"demo_{clip}_ttc.png", fps)
    rp = np.diff(np.asarray(raw_pts))
    res = {"clip": clip, "backend": backend, "fps": fps, "frames": len(det_ms), "unique_track_ids": len(ids),
           "track_frames_approaching": n_appr, "track_frames_closing_any_position": n_close,
           "detector_ms_p50": float(np.median(det_ms[5:])), "tracker_update_ms_p50": float(np.median(trk_ms[5:])),
           "tracker_update_ms_p90": float(np.percentile(trk_ms[5:], 90)),
           "reader_pts_nonincreasing_steps": int((rp <= 0).sum()),
           "video": str(OUT / f"demo_{clip}.mp4"), "video_window_s": [video_start, video_start + video_seconds],
           "jsonl": str(OUT / f"demo_{clip}_tracks.jsonl"), "ttc_plot": str(OUT / f"demo_{clip}_ttc.png"),
           "peak_vram_mb": torch.cuda.max_memory_allocated() / 2**20 if torch.cuda.is_available() else None}
    del det
    torch.cuda.empty_cache()
    return res


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--clips", nargs="+", default=CLIPS)
    ap.add_argument("--backend", default="ul_botsort")
    ap.add_argument("--seconds", type=float, default=15.0)
    ap.add_argument("--video-seconds", type=float, default=10.0)
    ap.add_argument("--video-start", type=float, default=0.0)
    ap.add_argument("--width", type=int, default=960)
    ap.add_argument("--device", default="cuda")
    a = ap.parse_args(argv)
    res = []
    for c in a.clips:
        t0 = time.time()
        r = run_clip(c, a.backend, a.seconds, a.video_seconds, a.video_start, a.device, a.width)
        r["wall_s"] = round(time.time() - t0, 1)
        print(json.dumps(r), flush=True)
        res.append(r)
    # merged into metrics.json by `eval_bdd --stages report`
    json.dump(portable_paths(res), open(OUT / "demo_metrics.json", "w"), indent=1, default=float)


if __name__ == "__main__":
    main()
