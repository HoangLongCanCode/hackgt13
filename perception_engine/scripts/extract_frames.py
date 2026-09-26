"""Extract JPEG frames from a clip for the Knuckle Sandwich Robotics Inc. (KSR) fake tablet,
`driving_assist/bridge-cli live --frames DIR`.

bridge-cli uplinks these frames through the Kotlin PerceptionBridge exactly as the Galaxy Tab S9 camera
would (960x540, JPEG quality ~80 = the PROTOCOL_v2 recommendation).

    .venv\\Scripts\\python.exe scripts\\extract_frames.py b1ff4656-0435391e --fps 15 --seconds 30   # from perception_engine/

Writes outputs\\e2e\\frames_<clip>\\frame_000000.jpg ... plus meta.json (KSR_OUTPUTS_DIR overrides outputs\\;
docs/perception/RUNBOOK.md uses --out outputs\\e2e\\frames_b1ff4656_960x540).
`clip` is a path, or a stem looked up in data\\bdd100k\\videos\\val\\<stem>.mov (KSR_DATA_DIR overrides data\\).
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

import cv2

ROOT = Path(__file__).resolve().parents[1]
CLIP_DIR = Path(os.environ.get("KSR_DATA_DIR") or ROOT / "data") / "bdd100k" / "videos" / "val"
OUT_ROOT = Path(os.environ.get("KSR_OUTPUTS_DIR") or ROOT / "outputs") / "e2e"


def resolve_clip(clip: str) -> Path:
    p = Path(clip)
    if p.is_file():
        return p
    for ext in (".mov", ".mp4", ".avi", ".mkv"):
        cand = CLIP_DIR / f"{clip}{ext}"
        if cand.is_file():
            return cand
    sys.exit(f"clip not found: {clip} (looked for a file and {CLIP_DIR}\\{clip}.mov)")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("clip", help="clip path or stem (e.g. b1ff4656-0435391e)")
    ap.add_argument("--fps", type=float, default=15.0, help="output frame rate (default 15, the tablet uplink target)")
    ap.add_argument("--width", type=int, default=960)
    ap.add_argument("--height", type=int, default=540)
    ap.add_argument("--quality", type=int, default=80, help="JPEG quality (default 80)")
    ap.add_argument("--start", type=float, default=0.0, help="start time in seconds")
    ap.add_argument("--seconds", type=float, default=None, help="duration to extract (default: to the end)")
    ap.add_argument("--out", type=Path, default=None, help="output dir (default outputs/e2e/frames_<stem>)")
    ap.add_argument("--keep-old", action="store_true", help="do not delete frame_*.jpg left by a previous run")
    args = ap.parse_args(argv)

    clip = resolve_clip(args.clip)
    out = args.out or (OUT_ROOT / f"frames_{clip.stem}")
    out.mkdir(parents=True, exist_ok=True)
    if not args.keep_old:
        for old in out.glob("frame_*.jpg"):  # only files this script writes
            old.unlink()

    cap = cv2.VideoCapture(str(clip))
    if not cap.isOpened():
        sys.exit(f"OpenCV cannot open {clip}")
    src_fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    src_w, src_h = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)), int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    if args.start > 0:
        cap.set(cv2.CAP_PROP_POS_MSEC, args.start * 1000.0)

    params = [int(cv2.IMWRITE_JPEG_QUALITY), int(args.quality)]
    period = 1.0 / args.fps
    end = args.start + args.seconds if args.seconds else None
    t0 = time.perf_counter()
    k, total_bytes, src_idx = 0, 0, 0
    next_t = args.start
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        pos = cap.get(cv2.CAP_PROP_POS_MSEC) / 1000.0
        t = pos if pos > 0 else args.start + src_idx / src_fps
        src_idx += 1
        if end is not None and t >= end:
            break
        if t + 1e-6 < next_t:
            continue
        if (frame.shape[1], frame.shape[0]) != (args.width, args.height):
            frame = cv2.resize(frame, (args.width, args.height), interpolation=cv2.INTER_AREA)
        ok, buf = cv2.imencode(".jpg", frame, params)
        if not ok:
            sys.exit(f"JPEG encode failed at t={t:.3f}s")
        (out / f"frame_{k:06d}.jpg").write_bytes(buf.tobytes())
        total_bytes += len(buf)
        k += 1
        next_t += period
    cap.release()

    if k == 0:
        sys.exit("no frames extracted (check --start / --seconds)")
    meta = {
        "clip": clip.stem, "source": str(clip), "sourceFps": round(src_fps, 3), "sourceSize": [src_w, src_h],
        "fps": args.fps, "width": args.width, "height": args.height, "jpegQuality": args.quality,
        "start": args.start, "count": k, "avgKB": round(total_bytes / k / 1024, 1),
    }
    (out / "meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    print(f"{k} frames {args.width}x{args.height} q{args.quality} at {args.fps:g} fps "
          f"(avg {meta['avgKB']} KB) from {clip.name} -> {out}  [{time.perf_counter() - t0:.1f}s]")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
