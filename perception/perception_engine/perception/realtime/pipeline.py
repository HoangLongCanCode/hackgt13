"""Two-lane realtime pipeline: FAST lane thread (wave 1) + SLOW lane thread (wave 2) around one PerceptionEngine.

Knuckle Sandwich Robotics Inc. (KSR) AI Spatial Driving Copilot, HackGT 13 prototype.

    pipe = TwoLanePipeline(engine, on_wave1=..., on_wave2=..., on_skip=..., on_session=..., camera_for=...)
    pipe.start()                              # warms each lane up in its own thread + CUDA stream, then serves
    superseded = pipe.submit(Item(...))       # 1-slot latest-wins input; returns the item it replaced (or None)

Callbacks (all run in the lane threads; keep them short):
    on_wave1(item, FrameResult, meta, timings)   every analysed frame (-> perception.frame)
    on_wave2(item, SlowResult)                   every slow-lane run (-> perception.update)
    on_skip(item, reason)                        an item that will get no wave 1 (decodeError; notAccepted when the
                                                 fast lane raised) - `superseded` items come back from submit()
    on_session(item, SessionCamera)              the fast lane started a new stream (item.reset, first frame, or the
                                                 upright frame size changed mid-session: item.extra["resized"])
    camera_for(item, (w, h)) -> SessionCamera    camera of a new stream, from the frame size (+ client intrinsics)

  input slot ──► FAST thread (high-priority CUDA stream)            ──► on_wave1(item, result, meta, timings)
                 JPEG decode + rotate, engine.fast_step:                   (perception.frame, every analysed frame)
                 detection + tracking + light state + carried state
                      │ snapshot (image + tracked detections), latest wins
                      ▼
                 SLOW thread (normal-priority CUDA stream)          ──► on_wave2(item, SlowResult)
                 engine.slow_step: depth/distance, lanes + road, signs     (perception.update)

Model ownership: the fast thread only calls engine.begin_session/fast_step, the slow thread only calls
engine.slow_step, so every model object is used by exactly one thread. Results cross via engine.shared (a lock).
Each thread runs on its own CUDA stream; torch's stream is thread-local, and the fast stream has the higher
priority so its kernels are scheduled ahead of the queued depth kernels. `install_stream_local_sync()` makes the
Ultralytics / lanes timing barriers wait for the caller's stream only.
"""
from __future__ import annotations

import sys
import threading
import time
import traceback
from collections import deque
from dataclasses import dataclass, field
from typing import Any, Callable, Optional

import numpy as np


# ----------------------------------------------------------------------------- hand-off types
@dataclass
class Item:
    """One frame offered to the fast lane."""
    seq: int
    index: int
    pts_s: float
    t_grab: float                          # perf_counter when the frame became available on the server
    session_id: str
    source: dict[str, str]
    image: Optional[np.ndarray] = None     # upright BGR (video / sim)
    jpeg: Optional[bytes] = None           # live uplink: decoded + rotated by the fast lane
    rotation: int = 0                      # rotationDegrees from the KSR1 header (clockwise to upright)
    echo: Optional[dict[str, int]] = None  # {frameId, captureTimeNs} of an uplinked frame
    client_id: Optional[str] = None        # uplinking client (answers / skips go to it)
    reset: bool = False                    # reset engine state before this frame (new session or seek)
    new_session: bool = False              # session id changed: announce a new perception.hello
    camera: Any = None                     # client.hello camera dict for a new live/sim session (or None)
    mode: str = "video"                    # "video" | "live" | "sim"
    extra: dict[str, Any] = field(default_factory=dict)


class LatestSlot:
    """1-slot buffer: put() overwrites (= supersedes) an unconsumed item and returns it. A pending reset /
    new-session marker (and its camera) survives the overwrite when the newer item is of the same session."""

    def __init__(self):
        self._cond = threading.Condition()
        self._item: Any = None
        self.put_count = 0
        self.superseded = 0

    def put(self, item: Any) -> Any:
        with self._cond:
            old = self._item
            if old is not None:
                self.superseded += 1
                if isinstance(old, Item) and isinstance(item, Item) and old.session_id == item.session_id:
                    if old.reset and not item.reset:
                        item.reset = True
                        item.camera = item.camera if item.camera is not None else old.camera
                    item.new_session = item.new_session or old.new_session
            self._item = item
            self.put_count += 1
            self._cond.notify_all()
            return old

    def get(self, timeout: float) -> Any:
        with self._cond:
            if self._item is None:
                self._cond.wait(timeout)
            item, self._item = self._item, None
            if item is not None:
                self._cond.notify_all()
            return item

    def take(self) -> Any:
        """Remove and return the pending item without waiting (None if empty)."""
        with self._cond:
            item, self._item = self._item, None
            self._cond.notify_all()
            return item

    def empty(self) -> bool:
        with self._cond:
            return self._item is None

    def wait_empty(self, timeout: float) -> bool:
        with self._cond:
            if self._item is not None:
                self._cond.wait(timeout)
            return self._item is None


# ----------------------------------------------------------------------------- pipeline
def decode_jpeg(jpeg: bytes | memoryview, rotation: int = 0) -> Optional[np.ndarray]:
    """JPEG -> upright BGR (cv2.imdecode, libjpeg-turbo inside OpenCV). rotation = clockwise degrees to upright."""
    import cv2
    img = cv2.imdecode(np.frombuffer(jpeg, np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        return None
    if rotation == 90:
        img = cv2.rotate(img, cv2.ROTATE_90_CLOCKWISE)
    elif rotation == 180:
        img = cv2.rotate(img, cv2.ROTATE_180)
    elif rotation == 270:
        img = cv2.rotate(img, cv2.ROTATE_90_COUNTERCLOCKWISE)
    return img


class TwoLanePipeline:
    """Runs PerceptionEngine.fast_step / slow_step on two threads. Callbacks run in the lane threads: keep them
    short (build the wire dict, serialise once, hand the bytes to the event loop)."""

    def __init__(self, engine: Any, *,
                 on_wave1: Callable[[Item, Any, dict, dict], None],
                 on_wave2: Callable[[Item, Any], None],
                 on_skip: Optional[Callable[[Item, str], None]] = None,
                 on_session: Optional[Callable[[Item, Any], None]] = None,
                 camera_for: Optional[Callable[[Item, tuple[int, int]], Any]] = None):
        self.engine = engine
        cfg = engine.cfg
        self.on_wave1, self.on_wave2 = on_wave1, on_wave2
        self.on_skip, self.on_session = on_skip, on_session
        self.camera_for = camera_for
        self.fast_max_hz = (cfg.get("fast") or {}).get("max_hz")
        self.slow_max_hz = (cfg.get("slow") or {}).get("max_hz")
        self.fast_priority = int((cfg.get("fast") or {}).get("cuda_priority", -5))
        self.slow_priority = int((cfg.get("slow") or {}).get("cuda_priority", 0))
        self.switch_interval_ms = cfg.get("gil_switch_interval_ms")
        self.inbox = LatestSlot()
        self.snapshots = LatestSlot()
        self.stop_evt = threading.Event()
        self.threads: list[threading.Thread] = []
        self.errors: deque[str] = deque(maxlen=20)
        self.fast_busy = False
        # (t_done perf, compute_ms) for rate measurements; the server keeps its own end-to-end stats
        self.wave1_done: deque = deque(maxlen=4000)
        self.wave2_done: deque = deque(maxlen=2000)
        self.ready = {"fast": threading.Event(), "slow": threading.Event()}
        self.warmup_ms: dict[str, float] = {}
        self._warm = True
        self.frames_analysed = 0
        self.slow_runs = 0
        self.stale_snapshots = 0
        self.decode_errors = 0
        self.sessions = 0
        self._session_wh: Optional[tuple[int, int]] = None

    # ---- public
    def submit(self, item: Item) -> Optional[Item]:
        return self.inbox.put(item)

    def start(self, warmup: bool = True, timeout: float = 300.0) -> dict[str, float]:
        """Start both lane threads; with warmup=True each lane first warms its models up in its own thread and
        CUDA stream (engine.warmup_lane) and this call blocks until both are ready. Returns warm-up ms per lane."""
        from perception.engine import install_stream_local_sync
        install_stream_local_sync()
        if self.switch_interval_ms:
            sys.setswitchinterval(float(self.switch_interval_ms) / 1000.0)
        self._warm = warmup
        self.threads = [threading.Thread(target=self._fast_loop, name="fast-lane", daemon=True),
                        threading.Thread(target=self._slow_loop, name="slow-lane", daemon=True)]
        for t in self.threads:
            t.start()
        for lane, evt in self.ready.items():
            if not evt.wait(timeout):
                raise RuntimeError(f"{lane} lane did not become ready within {timeout} s")
        return dict(self.warmup_ms)

    def _lane_warmup(self, lane: str) -> None:
        try:
            if self._warm:
                self.warmup_ms[lane] = self.engine.warmup_lane(lane)
        except Exception as e:
            self.errors.append(f"{lane} warmup: {type(e).__name__}: {e}")
            traceback.print_exc()
        finally:
            self.ready[lane].set()

    def stop(self, timeout: float = 5.0) -> None:
        self.stop_evt.set()
        for t in self.threads:
            t.join(timeout)

    # ---- threads
    def _stream_ctx(self, priority: int):
        import contextlib
        try:
            import torch
            if torch.cuda.is_available():
                return torch.cuda.stream(torch.cuda.Stream(priority=priority))
        except Exception:
            pass
        return contextlib.nullcontext()

    def _fast_loop(self) -> None:
        eng = self.engine
        from perception.common.video import Frame
        with self._stream_ctx(self.fast_priority):
            self._lane_warmup("fast")
            t_last = 0.0
            while not self.stop_evt.is_set():
                item = self.inbox.get(timeout=0.25)
                if item is None:
                    continue
                self.fast_busy = True
                try:
                    t_pick = time.perf_counter()
                    timings = {"queueWait": (t_pick - item.t_grab) * 1000.0}
                    img = item.image
                    if img is None and item.jpeg is not None:
                        t0 = time.perf_counter()
                        img = decode_jpeg(item.jpeg, item.rotation)
                        timings["jpegDecode"] = (time.perf_counter() - t0) * 1000.0
                        if img is None:
                            self.decode_errors += 1
                            if self.on_skip is not None:
                                self.on_skip(item, "decodeError")
                            continue
                    wh = (int(img.shape[1]), int(img.shape[0]))
                    if item.reset or eng._fast_cam is None or wh != self._session_wh:
                        # new stream / seek, or the upright frame size changed mid-session (live: rotation or
                        # resolution change): re-derive the session camera for this size and reset the lanes.
                        # A mid-session size change is flagged (extra["resized"]): the server starts a new session.
                        if not item.reset and eng._fast_cam is not None and self._session_wh is not None:
                            item.extra["resized"] = True
                        cam = self.camera_for(item, wh) if self.camera_for else None
                        eng.begin_session(cam)
                        self._session_wh = wh
                        self.sessions += 1
                        if self.on_session is not None:
                            self.on_session(item, cam)
                    t0 = time.perf_counter()
                    result, meta, snap = eng.fast_step(Frame(item.index, item.pts_s, img))
                    compute = (time.perf_counter() - t0) * 1000.0
                    snap.tag = item
                    self.snapshots.put(snap)          # the slow lane takes the newest when it is free
                    self.on_wave1(item, result, meta, {**timings, "fastLane": compute})
                    self.frames_analysed += 1
                    self.wave1_done.append((time.perf_counter(), compute))
                except Exception as e:  # keep serving; surfaced on /health
                    self.errors.append(f"fast: {type(e).__name__}: {e}")
                    traceback.print_exc()
                    if self.on_skip is not None and item.echo is not None:
                        try:            # an uplinked frame must still get exactly one wave-1 answer
                            self.on_skip(item, "notAccepted")
                        except Exception:
                            traceback.print_exc()
                finally:
                    self.fast_busy = False
                if self.fast_max_hz:
                    dt = 1.0 / float(self.fast_max_hz) - (time.perf_counter() - t_last)
                    if dt > 0:
                        time.sleep(dt)
                t_last = time.perf_counter()

    def _slow_loop(self) -> None:
        eng = self.engine
        with self._stream_ctx(self.slow_priority):
            self._lane_warmup("slow")
            t_last = 0.0
            while not self.stop_evt.is_set():
                if self.slow_max_hz:
                    dt = 1.0 / float(self.slow_max_hz) - (time.perf_counter() - t_last)
                    if dt > 0:
                        time.sleep(dt)
                snap = self.snapshots.get(timeout=0.25)
                if snap is None:
                    continue
                t_last = time.perf_counter()
                try:
                    t0 = time.perf_counter()
                    out = eng.slow_step(snap)
                    compute = (time.perf_counter() - t0) * 1000.0
                    if out is None:
                        self.stale_snapshots += 1
                        continue
                    self.slow_runs += 1
                    self.wave2_done.append((time.perf_counter(), compute, "distance" in out.ran))
                    self.on_wave2(snap.tag, out)
                except Exception as e:
                    self.errors.append(f"slow: {type(e).__name__}: {e}")
                    traceback.print_exc()
