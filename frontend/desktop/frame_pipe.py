"""Video frames for the desktop viewer (frontend/desktop): decodes a clip with OpenCV from a start time and writes
length-prefixed JPEG records to stdout. The viewer starts it (the project's Python, which has cv2) and restarts it on
a seek; there is no ffmpeg on the laptop.

    python frame_pipe.py CLIP [--start SECONDS] [--width PX] [--quality 85]

Records, big-endian: a 4-byte tag, a uint32 payload length, the payload.

    HDR1  JSON {"width", "height", "fps", "frameCount", "durationS", "start"}: once, first (size of the frames sent)
    FRM1  float64 pts in seconds, then the JPEG bytes of one frame
    END1  empty: the clip ended

The pts are the container pts the tablet's player shows and the server analyses by: CAP_PROP_POS_MSEC read after
grab(), kept strictly increasing (the same rule as perception/realtime/server.py ClipReader). Frames before the start
(the decoder lands on the key frame before it) are skipped without being decoded to pixels.
"""
from __future__ import annotations

import argparse
import json
import struct
import sys


def record(out, tag: bytes, payload: bytes) -> None:
    out.write(tag + struct.pack(">I", len(payload)) + payload)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("clip")
    ap.add_argument("--start", type=float, default=0.0, help="first pts to send (seconds)")
    ap.add_argument("--width", type=int, default=1280, help="frame width sent (0 = the clip's own)")
    ap.add_argument("--quality", type=int, default=85, help="JPEG quality")
    a = ap.parse_args()

    import cv2
    cap = cv2.VideoCapture(a.clip)
    if not cap.isOpened():
        print(f"frame_pipe: cannot open {a.clip}", file=sys.stderr)
        return 2
    cap.set(cv2.CAP_PROP_ORIENTATION_AUTO, 1)
    fps = float(cap.get(cv2.CAP_PROP_FPS) or 30.0)
    count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    src_w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH) or 0)
    src_h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT) or 0)
    dt = 1.0 / fps
    start = max(0.0, a.start)
    if start > 0.0:
        cap.set(cv2.CAP_PROP_POS_MSEC, max(0.0, start - dt) * 1000.0)

    out = sys.stdout.buffer
    params = [int(cv2.IMWRITE_JPEG_QUALITY), max(10, min(100, a.quality))]
    size = None
    last_pts = None
    try:
        while True:
            if not cap.grab():
                break
            pts = float(cap.get(cv2.CAP_PROP_POS_MSEC)) / 1000.0
            if last_pts is not None and pts <= last_pts:
                pts = last_pts + 1e-3
            last_pts = pts
            if pts < start - 0.5 * dt:
                continue
            ok, img = cap.retrieve()
            if not ok:
                break
            h, w = img.shape[:2]
            if a.width > 0 and w != a.width:
                img = cv2.resize(img, (a.width, max(2, round(h * a.width / w))), interpolation=cv2.INTER_AREA)
            if size is None:
                size = (img.shape[1], img.shape[0])
                header = {"width": size[0], "height": size[1], "fps": fps, "frameCount": count,
                          "durationS": count / fps if fps else 0.0, "start": start,
                          "sourceWidth": src_w or w, "sourceHeight": src_h or h}
                record(out, b"HDR1", json.dumps(header).encode("utf-8"))
            ok, jpg = cv2.imencode(".jpg", img, params)
            if ok:
                record(out, b"FRM1", struct.pack(">d", pts) + jpg.tobytes())
        if size is None:   # nothing at or after the start: still say how big the clip is
            record(out, b"HDR1", json.dumps({"width": src_w, "height": src_h, "fps": fps, "frameCount": count,
                                             "durationS": count / fps if fps else 0.0, "start": start}).encode("utf-8"))
        record(out, b"END1", b"")
        out.flush()
    except (BrokenPipeError, OSError):
        pass                # the viewer closed the pipe (seek, quit)
    finally:
        cap.release()
    return 0


if __name__ == "__main__":
    sys.exit(main())
