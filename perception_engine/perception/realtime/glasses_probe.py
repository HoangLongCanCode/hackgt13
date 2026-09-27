"""Fake glasses app for the glasses listener (perception.realtime.glasses_server, ws://<host>:8000/ws).

AI Spatial Driving Copilot. Streams a clip the way the phone does (upright JPEG, longest edge 640 px, quality 60,
jpeg-base64 JSON text frames at about 8 fps, never waiting for a reply, websocket ping every 15 s), prints what comes
back once a second, and can draw every reply onto the JPEG it answers so you can check by eye that the lane lines,
arrows and vehicle markers sit on the road. Run from perception_engine/:

    python -m perception.realtime.glasses_probe                                   # city clip, 20 s, localhost
    python -m perception.realtime.glasses_probe --save outputs/glasses_probe      # + overlay JPEGs and replies.jsonl
    python -m perception.realtime.glasses_probe --url ws://<laptop-LAN-IP>:8000/ws --video data/bdd100k/videos/val/X.mov
    python -m perception.realtime.glasses_probe --pad 640x480                     # letterbox to test the crop undo

The overlay drawing is an approximation of the app's (it draws `instructions` only, plus thin perception boxes).
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import json
import sys
import time
from collections import Counter
from pathlib import Path
from typing import Any, Optional

import cv2
import numpy as np

from perception.common.paths import DATA_DIR, resolve_data_path

DEFAULT_VIDEO = DATA_DIR / "bdd100k" / "videos" / "val" / "b1ff4656-0435391e.mov"
GREEN, WHITE, YELLOW, CYAN = (80, 220, 80), (255, 255, 255), (0, 220, 255), (255, 200, 0)


def read_frames(path: Path, start_s: float, seconds: float, fps: float, size: int,
                pad: Optional[tuple[int, int]]) -> list[np.ndarray]:
    """Upright frames at `fps`, longest edge `size`, optionally letterboxed / pillarboxed to pad = (w, h)."""
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened():
        sys.exit(f"cannot open {path}")
    cap.set(cv2.CAP_PROP_ORIENTATION_AUTO, 1)
    cap.set(cv2.CAP_PROP_POS_MSEC, start_s * 1000.0)
    src_fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    step = max(1.0, src_fps / fps)
    out, i, nxt = [], 0, 0.0
    while len(out) < int(seconds * fps):
        ok, img = cap.read()
        if not ok:
            break
        if i >= nxt:
            nxt += step
            h, w = img.shape[:2]
            s = size / max(w, h)
            img = cv2.resize(img, (max(1, round(w * s)), max(1, round(h * s))), interpolation=cv2.INTER_AREA)
            if pad is not None:
                ph, pw = max(pad[1], img.shape[0]), max(pad[0], img.shape[1])
                top, left = (ph - img.shape[0]) // 2, (pw - img.shape[1]) // 2
                img = cv2.copyMakeBorder(img, top, ph - img.shape[0] - top, left, pw - img.shape[1] - left,
                                         cv2.BORDER_CONSTANT, value=(0, 0, 0))
            out.append(img)
        i += 1
    cap.release()
    if not out:
        sys.exit(f"no frames read from {path} at {start_s} s")
    return out


def draw(img: np.ndarray, doc: dict[str, Any]) -> np.ndarray:
    """Approximate the glasses overlay: everything is 0..1 of this JPEG."""
    out = img.copy()
    H, W = out.shape[:2]

    def px(x: float, y: float) -> tuple[int, int]:
        return int(round(x * W)), int(round(y * H))

    for v in doc.get("perception", {}).get("vehicles", []):          # thin perception boxes (not drawn by the app)
        x1, y1, x2, y2 = v["bbox"]
        cv2.rectangle(out, px(x1, y1), px(x2, y2), CYAN, 1, cv2.LINE_AA)
    for s in doc.get("perception", {}).get("signs", []):
        x1, y1, x2, y2 = s["bbox"]
        cv2.rectangle(out, px(x1, y1), px(x2, y2), YELLOW, 1, cv2.LINE_AA)
        cv2.putText(out, s["label"], (px(x1, y1)[0], max(10, px(x1, y1)[1] - 3)), cv2.FONT_HERSHEY_SIMPLEX, 0.35,
                    YELLOW, 1, cv2.LINE_AA)
    for ins in doc.get("instructions", []):
        t, a, content = ins["type"], ins["anchor"], ins.get("content", "")
        if t == "LANE_BOUNDARY":
            pts = np.array([px(*p) for p in ins["points"]], np.int32).reshape(-1, 1, 2)
            cv2.polylines(out, [pts], False, WHITE, 2, cv2.LINE_AA)
        elif t == "LANE_ARROW":
            x, y = px(a["x"], a["y"])
            col = GREEN if ins.get("highlighted") else WHITE
            if ins.get("highlighted"):
                cv2.putText(out, content, (x - 18, y + 5), cv2.FONT_HERSHEY_SIMPLEX, 0.55, col, 2, cv2.LINE_AA)
            else:
                cv2.arrowedLine(out, (x, y + 14), (x, y - 14), col, 2, cv2.LINE_AA, tipLength=0.4)
            cv2.putText(out, str(ins.get("laneIndex", "")), (x - 4, y + 30), cv2.FONT_HERSHEY_SIMPLEX, 0.35, col, 1,
                        cv2.LINE_AA)
        elif t == "EXIT_MARKER":                                        # the app centres it at the top
            (tw, _), _ = cv2.getTextSize(content, cv2.FONT_HERSHEY_SIMPLEX, 0.6, 2)
            cv2.putText(out, content, (W // 2 - tw // 2, 22), cv2.FONT_HERSHEY_SIMPLEX, 0.6, WHITE, 2, cv2.LINE_AA)
            dl = ins.get("distanceLabel", "")
            (dw, _), _ = cv2.getTextSize(dl, cv2.FONT_HERSHEY_SIMPLEX, 0.5, 1)
            cv2.putText(out, dl, (W // 2 - dw // 2, 42), cv2.FONT_HERSHEY_SIMPLEX, 0.5, GREEN, 1, cv2.LINE_AA)
        elif t == "VEHICLE_MARKER":
            x, y = px(a["x"], a["y"])
            cv2.drawMarker(out, (x, y), GREEN, cv2.MARKER_CROSS, 12, 2, cv2.LINE_AA)
            cv2.putText(out, f"{content} {ins.get('distanceLabel', '')}", (x + 8, y - 6), cv2.FONT_HERSHEY_SIMPLEX,
                        0.4, GREEN, 1, cv2.LINE_AA)
        elif t == "DISTANCE_LABEL":
            x, y = px(a["x"], a["y"])
            cv2.putText(out, content, (x - 16, y), cv2.FONT_HERSHEY_SIMPLEX, 0.45, WHITE, 1, cv2.LINE_AA)
    return out


def summary(doc: dict[str, Any]) -> str:
    P = doc["perception"]
    ln = P["lanes"]
    veh = ", ".join(f"{v['class']} {v['distanceMeters']}m" for v in P["vehicles"]) or "-"
    signs = ", ".join(s["label"] for s in P["signs"]) or "-"
    return (f"lane {ln['currentLane']}/{ln['laneCount']} lines {[b['id'] for b in ln['boundaries']]} | "
            f"vehicles {veh} | signs {signs} | {len(doc['instructions'])} instructions")


async def run(a: argparse.Namespace) -> int:
    from websockets.asyncio.client import connect
    pad = tuple(int(v) for v in a.pad.lower().split("x")) if a.pad else None
    video = resolve_data_path(Path(a.video))
    frames = read_frames(video, a.start, a.seconds, a.fps, a.size, pad)
    jpegs = [cv2.imencode(".jpg", f, [cv2.IMWRITE_JPEG_QUALITY, a.quality])[1].tobytes() for f in frames]
    H, W = frames[0].shape[:2]
    print(f"[probe] {len(jpegs)} frames {W}x{H} q{a.quality} ({np.mean([len(j) for j in jpegs]) / 1024:.0f} KB avg) "
          f"from {video.name} at {a.fps:g} fps -> {a.url}", flush=True)
    save = Path(a.save) if a.save else None
    if save is not None:
        save.mkdir(parents=True, exist_ok=True)
    sent_at: dict[int, float] = {}
    lat: list[float] = []
    docs = 0
    errors: Counter = Counter()
    last_doc: Optional[dict[str, Any]] = None
    jsonl = open(save / "replies.jsonl", "w", encoding="utf-8") if save is not None else None
    base_id = 1
    try:
        async with connect(a.url, max_size=None, compression=None, ping_interval=15, ping_timeout=15,
                           open_timeout=10) as ws:
            async def rx():
                nonlocal docs, last_doc
                async for raw in ws:
                    if isinstance(raw, bytes):
                        errors["binary reply (the app ignores it)"] += 1
                        continue
                    t = time.monotonic()
                    try:
                        doc = json.loads(raw)
                    except ValueError:
                        errors["reply is not JSON"] += 1
                        continue
                    if jsonl is not None:
                        jsonl.write(raw + "\n")
                    if "instructions" in doc:
                        docs += 1
                        last_doc = doc
                        fid = doc.get("frameId")
                        if fid in sent_at:
                            lat.append((t - sent_at[fid]) * 1000.0)
                        if save is not None and isinstance(fid, int) and 0 <= fid - base_id < len(frames):
                            cv2.imwrite(str(save / f"frame_{fid:05d}.jpg"), draw(frames[fid - base_id], doc))
                    elif "error" in doc:
                        errors[str(doc["error"])] += 1
                    else:
                        errors["Bad spatial instruction (no instructions, no error)"] += 1
            task = asyncio.create_task(rx())
            t0 = time.monotonic()
            last_print = t0
            ts0 = int(time.time() * 1000)
            for i, j in enumerate(jpegs):
                fid = base_id + i
                await asyncio.sleep(max(0.0, t0 + i / a.fps - time.monotonic()))
                sent_at[fid] = time.monotonic()
                await ws.send(json.dumps({"frameId": fid, "timestampMs": ts0 + int(i * 1000 / a.fps),
                                          "encoding": "jpeg-base64", "width": W, "height": H,
                                          "data": base64.b64encode(j).decode()}))
                now = time.monotonic()
                if now - last_print >= 1.0:
                    last_print = now
                    p50 = f"{np.percentile(lat, 50):.0f} ms" if lat else "-"
                    print(f"[probe] t={now - t0:4.1f}s sent {i + 1} docs {docs} errors {sum(errors.values())} "
                          f"latency p50 {p50} | " + (summary(last_doc) if last_doc else "no document yet"), flush=True)
            await asyncio.sleep(a.drain)
            task.cancel()
    except OSError as e:
        print(f"[probe] cannot connect to {a.url}: {e}. Is the glasses server running (python -m "
              f"perception.realtime.glasses_server)? From another machine: firewall / LAN IP?", flush=True)
        return 2
    finally:
        if jsonl is not None:
            jsonl.close()
    n = len(jpegs)
    print(f"\n[probe] sent {n}, documents {docs} ({n - docs - sum(errors.values())} superseded / unanswered), "
          f"errors {dict(errors) or 0}")
    if lat:
        print(f"[probe] send -> reply latency p50 {np.percentile(lat, 50):.0f} ms, p95 {np.percentile(lat, 95):.0f} ms")
    if last_doc:
        print(f"[probe] last document: {summary(last_doc)}")
    if save is not None:
        print(f"[probe] overlays + replies.jsonl in {save}")
    return 0 if docs and not errors else 1


def main(argv: Optional[list[str]] = None) -> None:
    ap = argparse.ArgumentParser(description="Fake glasses app: streams a clip as jpeg-base64 JSON text frames")
    ap.add_argument("--url", default="ws://127.0.0.1:8000/ws")
    ap.add_argument("--video", default=str(DEFAULT_VIDEO))
    ap.add_argument("--start", type=float, default=5.0, help="clip start, seconds")
    ap.add_argument("--seconds", type=float, default=20.0)
    ap.add_argument("--fps", type=float, default=8.0)
    ap.add_argument("--size", type=int, default=640, help="longest edge of the JPEG (the app sends <= 640)")
    ap.add_argument("--quality", type=int, default=60)
    ap.add_argument("--pad", default=None, metavar="WxH", help="letterbox / pillarbox every frame to WxH first")
    ap.add_argument("--save", default=None, metavar="DIR", help="write overlay JPEGs + replies.jsonl here")
    ap.add_argument("--drain", type=float, default=1.5, help="seconds to wait for the last replies")
    sys.exit(asyncio.run(run(ap.parse_args(argv))))


if __name__ == "__main__":
    main()
