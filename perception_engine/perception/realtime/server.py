"""Realtime Perception Bridge server, protocol v2: ws://<host>:8765/perception (FastAPI + uvicorn).

AI Spatial Driving Copilot, HackGT 13 prototype. Source of truth for the
messages: contracts/PROTOCOL_v2.md and contracts/schemas/. Run from perception_engine/:

    python -m perception.realtime.server --mode video --video data/bdd100k/videos/val/b1ff4656-0435391e.mov [--loop]
    python -m perception.realtime.server --mode live                  # tablet camera uplink (SDC1 + JPEG)
    python -m perception.realtime.server --mode sim [--video-dir DIR]  # same clip on both devices
    python -m perception.realtime.server --mode auto                  # live or sim, whichever the client's hello asks
  common:     [--host 0.0.0.0] [--port 8765] [--config perception/config_realtime.yaml] [--set KEY=VALUE ...]
              [--max-in-flight 2] [--lookahead auto|SECONDS] [--start-on-connect]
  navigation: sim/video: a clip recorded with its session (data/sim_videos/<id>/: video.mp4 + session_manifest.json +
              route.json + trip_state.jsonl) navigates by itself; --nav-session DIR for clips without one (BDD100K)
              live: --nav-route route.json | --nav-destination "QUERY" | --nav-live
              [--nav-origin "QUERY"] [--nav-provider mock|google] (live) [--phase1-dir DIR] [--node node]
              [--speed-limits off|osm] [--speed-limit-endpoint URL]   navigation.packet.speedLimit (speed_limit.py)
  tts:        [--no-tts] [--tts-allow-lan]   ElevenLabs proxy POST /tts, GET /tts/health (tts_proxy.py)

Tablet over USB: `adb reverse tcp:8765 tcp:8765`, then the app uses ws://127.0.0.1:8765/perception.

Threads (every model is owned by exactly one lane thread; nothing on the asyncio loop touches torch):
  * asyncio loop (uvicorn): sockets, JSON parsing, SDC1 header parsing, per-client send queues, ~1 Hz stats.
  * fast lane  (pipeline.TwoLanePipeline): JPEG decode + rotate, detection + tracking + light state
               -> perception.frame (wave 1) for every analysed frame.
  * slow lane  (pipeline.TwoLanePipeline): depth/distance, lanes + road, signs -> perception.update (wave 2).
  * source thread: video = real-time clip player; sim = playback-driven look-ahead scheduler. Live frames are
               submitted straight from the loop into the 1-slot latest-wins inbox (a superseded frame is answered
               with perception.skip).
  * nav worker (optional): the phase1 route-engine relay (perception.realtime.nav_relay.NavRelay, a Node child
               process) is only ever called from this thread -> navigation.packet (broadcast) and navigation.places
               (to the client whose client.place_search it answers). With --speed-limits osm it also attaches
               speedLimit to each packet (a non-blocking OpenStreetMap lookup; the HTTP request runs in its own thread).
Lane threads hand serialised JSON to the loop with loop.call_soon_threadsafe. Each client has a reliable control
queue (hello, skip, error, pong, stats) plus 1-slot latest-wins slots for perception.frame, perception.update and
navigation.packet, so a slow socket only loses its own messages and never stalls inference. A wave-1 frame that
answers an uplinked frame and gets replaced in the controller's slot before it was sent is answered with
perception.skip (superseded): every uplinked frame gets exactly one wave-1 answer.

Roles: one controller (the client whose client.hello started the live / sim session; the newest hello takes over)
and any number of watchers (clients that sent no hello, or were taken over). Everyone receives the results; only the
controller uplinks frames, reports playback and feeds live navigation (client.trip_state). A client whose socket
stops draining for SEND_TIMEOUT_S is evicted (a peer that stopped reading must not hold the session).
"""
from __future__ import annotations

import argparse
import asyncio
import contextlib
import dataclasses
import importlib
import itertools
import json
import math
import os
import socket
import sys
import threading
import time
import traceback
import uuid
from collections import deque
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Optional

os.environ.setdefault("YOLO_AUTOINSTALL", "False")

import numpy as np  # noqa: E402
from fastapi import WebSocket, WebSocketDisconnect  # noqa: E402

from perception.common.paths import DATA_DIR, resolve_data_path  # noqa: E402
from perception.realtime.pipeline import Item, TwoLanePipeline  # noqa: E402
from perception.realtime.subscribers import PerceptionBus  # noqa: E402
from perception.realtime.tts_proxy import TtsProxy, add_tts_routes  # noqa: E402
from perception.realtime.wire import (  # noqa: E402
    UplinkError, dumps, make_error, make_hello, make_pong, make_skip, make_stats, make_update, now_ms, parse_uplink,
    to_wire)

PROJECT_ROOT = Path(__file__).resolve().parents[2]          # perception_engine/
VIDEO_EXTS = (".mov", ".mp4", ".m4v", ".mkv", ".avi", ".webm")
DEFAULT_VIDEO_DIRS = (DATA_DIR / "bdd100k" / "videos", DATA_DIR / "sim_videos")   # PERCEPTION_DATA_DIR moves both
MODES = ("video", "live", "sim")
PREFERRED_UPLINK = {"preferredWidth": 960, "preferredHeight": 540, "jpegQuality": 80, "header": "SDC1"}
STATS_WINDOW_S = 3.0
SEEK_THRESHOLD_S = 0.6            # client.playback jump (vs. extrapolation) treated as a seek -> new session
PLAYBACK_STALE_S = 1.5            # stop extrapolating playback this long after the last client.playback
NAV_PERIOD_S = 0.5                # sim/video: one navigation.packet per this much media time
NAV_FAIL_THRESHOLD = 3            # consecutive relay calls without a packet -> navigation.available false
PLACE_SEARCHES_PER_S = 2          # client.place_search answered per client per second (the rest: "rate limited")
PLACES_MAX = 8                    # navigation.places lists at most this many places
SEND_TIMEOUT_S = 10.0             # a send that cannot complete this long (peer stopped reading) evicts the client
UNKNOWN_VIDEO_RETRY_S = 5.0       # a missing videoId is re-checked (rescan + error) at most this often per client


def new_session_id(prefix: str) -> str:
    safe = "".join(ch if (ch.isalnum() or ch in "-_.") else "_" for ch in prefix)[:48]
    return f"{safe}-{time.strftime('%Y%m%dT%H%M%S')}-{uuid.uuid4().hex[:6]}"


def lan_ips() -> list[str]:
    ips = set()
    with contextlib.suppress(OSError):
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    return sorted(ip for ip in ips if not ip.startswith("127."))


# ----------------------------------------------------------------------------- clips
def index_videos(dirs: list[Path], session_dirs: list[Path] = ()) -> dict[str, Path]:
    """videoId (file stem) -> clip path, searched recursively. A folder holding video.mp4 (a phase1 session) is
    also registered under the folder name. First hit wins."""
    out: dict[str, Path] = {}
    for d in list(dirs) + list(session_dirs):
        if not d.exists():
            continue
        for p in sorted(d.rglob("*")):
            if p.suffix.lower() in VIDEO_EXTS and p.is_file():
                out.setdefault(p.stem, p)
                if p.name == "video.mp4":
                    out.setdefault(p.parent.name, p)
    return out


@dataclass
class ClipFrame:
    index: int
    pts: float
    image: np.ndarray


@dataclass
class ClipInfo:
    fps: float
    frame_count: int
    width: int
    height: int

    @property
    def duration(self) -> float:
        return self.frame_count / self.fps if self.fps else 0.0


class ClipReader:
    """Upright BGR frames with their CONTAINER pts (what the tablet's player shows): OpenCV CAP_PROP_POS_MSEC read
    after grab() (= PyAV frame.pts * time_base, checked on BDD clips), clamped monotonic (BDD .mov files start with
    three frames at pts 0). perception.common.video.VideoFileInput reads POS_MSEC before read(), which labels every
    frame with the previous frame's pts - one frame early, too much for sim-mode sync - so it is not used here."""

    def __init__(self, path: Path):
        import cv2
        self.cv2 = cv2
        self.path = Path(path)
        self.cap = cv2.VideoCapture(str(self.path))
        if not self.cap.isOpened():
            raise IOError(f"cannot open {self.path}")
        self.cap.set(cv2.CAP_PROP_ORIENTATION_AUTO, 1)
        self.fps = float(self.cap.get(cv2.CAP_PROP_FPS) or 30.0)
        self.frame_count = int(self.cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
        self.frame_dt = 1.0 / self.fps
        self.last_pts: Optional[float] = None
        self.pending: Optional[tuple[int, float]] = None     # grabbed, not retrieved: (index, pts)

    def _grab(self) -> Optional[tuple[int, float]]:
        if not self.cap.grab():
            return None
        pts = float(self.cap.get(self.cv2.CAP_PROP_POS_MSEC)) / 1000.0
        idx = int(self.cap.get(self.cv2.CAP_PROP_POS_FRAMES)) - 1
        if self.last_pts is not None and pts <= self.last_pts:
            pts = self.last_pts + 1e-3
        self.last_pts = pts
        return max(0, idx), pts

    def _retrieve(self, idx: int, pts: float) -> Optional[ClipFrame]:
        ok, img = self.cap.retrieve()
        return ClipFrame(idx, pts, img) if ok else None

    def read(self) -> Optional[ClipFrame]:
        g = self.pending or self._grab()
        self.pending = None
        return None if g is None else self._retrieve(*g)

    def seek(self, pts: float) -> None:
        self.cap.set(self.cv2.CAP_PROP_POS_MSEC, max(0.0, pts) * 1000.0)
        self.last_pts, self.pending = None, None

    def frame_at(self, target: float) -> Optional[ClipFrame]:
        """The first frame with pts >= target - half a frame (decoding forward; seeking when the target is behind
        the decoder or more than 2 s ahead). None at the end of the clip."""
        half = 0.5 * self.frame_dt
        pos = self.pending[1] if self.pending else self.last_pts
        if pos is not None and (target < pos - 1.5 * self.frame_dt or target > pos + 2.0):
            self.seek(target - self.frame_dt)
        while True:
            g = self.pending or self._grab()
            self.pending = None
            if g is None:
                return None
            if g[1] >= target - half:
                return self._retrieve(*g)

    def release(self) -> None:
        self.cap.release()


def probe_clip(path: Path) -> ClipInfo:
    r = ClipReader(path)
    try:
        f = r.read()
        h, w = (f.image.shape[:2] if f is not None else (720, 1280))
        return ClipInfo(r.fps, r.frame_count, int(w), int(h))
    finally:
        r.release()


# ----------------------------------------------------------------------------- clients
class Client:
    """One WebSocket. Control messages are queued (reliable); frame / update / nav are 1-slot latest-wins."""
    _ids = itertools.count(1)
    MAX_CTRL = 256

    def __init__(self, ws: Any, peer: str):
        self.ws = ws
        self.key = f"c{next(self._ids)}"
        self.peer = peer
        self.client_id: Optional[str] = None
        self.hello: Optional[dict[str, Any]] = None
        self.role = "watcher"
        self.ctrl: deque[str] = deque()
        self.frame: Optional[str] = None
        self.frame_echo: Optional[int] = None       # frameId of the uplinked frame the slot answers (controller)
        self.update: Optional[str] = None
        self.nav: Optional[str] = None
        self.event = asyncio.Event()
        self.sent = 0
        self.dropped_frames = 0
        self.dropped_updates = 0
        self.dropped_ctrl = 0
        self._err_last: dict[str, float] = {}
        self.nav_error_sent = False
        self.search_times: deque[float] = deque(maxlen=PLACE_SEARCHES_PER_S)   # monotonic times of answered searches
        self.connected_at = time.time()
        self.live_camera: Optional[dict[str, Any]] = None    # validated camera of this client's last live hello
        self.unknown_videos: dict[str, float] = {}           # videoId -> monotonic time it was last reported missing
        self.evicted: Optional[str] = None                   # why the server dropped this client (send stalled)
        self.on_stall: Optional[Callable[[], None]] = None   # set by the endpoint: abort the socket, end the handler

    @property
    def name(self) -> str:
        return self.client_id or self.key

    def offer_ctrl(self, data: str) -> None:
        if len(self.ctrl) >= self.MAX_CTRL:
            self.ctrl.popleft()
            self.dropped_ctrl += 1
        self.ctrl.append(data)
        self.event.set()

    def offer_frame(self, data: str, echo_id: Optional[int]) -> Optional[int]:
        """Returns the frameId of a replaced, still unsent uplink answer (the caller answers it with a skip)."""
        replaced = self.frame_echo if self.frame is not None else None
        if self.frame is not None:
            self.dropped_frames += 1
        self.frame, self.frame_echo = data, echo_id
        self.event.set()
        return replaced

    def take_frame(self) -> Optional[int]:
        """Drop the pending frame + update (session change); returns the frame's uplink frameId if it had one."""
        echo = self.frame_echo if self.frame is not None else None
        self.frame, self.frame_echo, self.update = None, None, None
        return echo

    def offer_update(self, data: str) -> None:
        if self.update is not None:
            self.dropped_updates += 1
        self.update = data
        self.event.set()

    def offer_nav(self, data: str) -> None:
        self.nav = data
        self.event.set()

    def error(self, code: str, message: str, *, fatal: bool = False, detail: Optional[dict] = None,
              min_interval_s: float = 5.0) -> None:
        """perception.error, rate-limited per code (a client repeating a mistake at 20 Hz gets one per 5 s)."""
        now = time.monotonic()
        if now - self._err_last.get(code, -1e9) < min_interval_s:
            return
        self._err_last[code] = now
        self.offer_ctrl(dumps(make_error(code, message, fatal=fatal, detail=detail)).decode())

    async def sender(self) -> None:
        """Drains the queues. A send that cannot complete within SEND_TIMEOUT_S (the peer keeps the TCP connection
        but stopped reading, so the transport never drains) evicts the client: on_stall aborts the socket and ends
        the endpoint, which removes the client (and ends its session if it was the controller)."""
        try:
            while True:
                await self.event.wait()
                self.event.clear()
                while True:
                    if self.ctrl:
                        data = self.ctrl.popleft()
                    elif self.frame is not None:
                        data, self.frame, self.frame_echo = self.frame, None, None
                    elif self.update is not None:
                        data, self.update = self.update, None
                    elif self.nav is not None:
                        data, self.nav = self.nav, None
                    else:
                        break
                    try:
                        await asyncio.wait_for(self.ws.send_text(data), SEND_TIMEOUT_S)
                    except TimeoutError:
                        self.evicted = f"a send did not complete in {SEND_TIMEOUT_S:.0f} s (the peer stopped reading)"
                        if self.on_stall is not None:
                            self.on_stall()
                        return
                    self.sent += 1
        except asyncio.CancelledError:
            raise
        except Exception:        # socket closed under us; the receive loop cleans up
            return


def abort_transport(ws: Any) -> bool:
    """Best effort: abort the asyncio transport behind a Starlette WebSocket. A normal close waits until the unsent
    buffer drains, which never happens when the peer stopped reading. uvicorn keeps the transport on the protocol
    object whose bound `send` Starlette holds (possibly wrapped in closures), so walk that chain. False when not
    found (the caller then just ends the handler; the socket closes once uvicorn gives up on it)."""
    seen: set[int] = set()
    stack = [getattr(ws, "_send", None)]
    while stack and len(seen) < 64:
        fn = stack.pop()
        if fn is None or id(fn) in seen:
            continue
        seen.add(id(fn))
        tr = getattr(getattr(fn, "__self__", None), "transport", None)
        if tr is not None and hasattr(tr, "abort"):
            with contextlib.suppress(Exception):
                tr.abort()
                return True
        for cell in getattr(fn, "__closure__", None) or ():
            with contextlib.suppress(ValueError):
                v = cell.cell_contents
                if callable(v):
                    stack.append(v)
    return False


# ----------------------------------------------------------------------------- session
@dataclass(frozen=True)
class Session:
    """The stream being analysed. Replaced (never mutated in place) so lane threads read a consistent object."""
    id: str
    mode: str                                   # video | live | sim
    source: dict[str, str]
    image: Optional[dict[str, int]] = None      # wire `image` = coordinate space of this session's results
    camera: Optional[dict[str, Any]] = None     # wire camera for the hello
    controller: Optional[str] = None            # Client.key of the uplinking / playback client
    client_camera: Optional[dict[str, Any]] = None
    video_id: Optional[str] = None
    source_fps: Optional[float] = None


@dataclass
class Playback:
    pts: float
    playing: bool
    rate: float
    t: float                                    # perf_counter when received


# ----------------------------------------------------------------------------- sources
class VideoSource(threading.Thread):
    """--mode video: plays the clip at real-time speed (wall clock follows pts) into the pipeline; each loop is a
    new session (+ perception.hello)."""

    def __init__(self, srv: "Server", path: Path, loop: bool, start_evt: threading.Event):
        super().__init__(name="video-source", daemon=True)
        self.srv, self.path, self.loop, self.start_evt = srv, Path(path), loop, start_evt
        self.stop_evt = threading.Event()
        self.info = probe_clip(self.path)
        self.cur_pts: Optional[float] = None
        self.loops = 0
        self.finished = False

    def stop(self) -> None:
        self.stop_evt.set()
        self.start_evt.set()

    def run(self) -> None:
        self.start_evt.wait()
        srv = self.srv
        try:
            while not self.stop_evt.is_set():
                reader = ClipReader(self.path)
                sess = srv.begin_video_session(self.path.stem, self.info)
                t_wall0 = pts0 = None
                seq = 0
                while not self.stop_evt.is_set():
                    fr = reader.read()
                    if fr is None:
                        break
                    if t_wall0 is None:
                        t_wall0, pts0 = time.perf_counter(), fr.pts
                    target = t_wall0 + (fr.pts - pts0)
                    now = time.perf_counter()
                    if target > now:
                        time.sleep(target - now)
                    elif now - target > 0.5:          # fell far behind (debugger, GC): re-anchor, no burst
                        t_wall0 = now - (fr.pts - pts0)
                    self.cur_pts = fr.pts
                    srv.frames_in += 1
                    old = srv.pipe.submit(Item(seq=seq, index=fr.index, pts_s=fr.pts, t_grab=time.perf_counter(),
                                               session_id=sess.id, source=sess.source, image=fr.image,
                                               reset=seq == 0, new_session=seq == 0, mode="video"))
                    if old is not None:
                        srv.frames_dropped += 1
                    seq += 1
                reader.release()
                if not self.loop:
                    break
                self.loops += 1
        except Exception as e:
            srv.lane_error(f"video source: {type(e).__name__}: {e}")
            traceback.print_exc()
        self.finished = True


class SimSource(threading.Thread):
    """--mode sim: analyses the clip AHEAD of the tablet's playback position.

    Whenever the fast lane has taken the previous frame (inbox empty), the frame at
        target = playbackPts(now) + lookahead            (lookahead in MEDIA seconds)
    is decoded and submitted, so its wave-1 result reaches the tablet before the player shows that frame. The
    playback position is extrapolated from the newest client.playback (pts + (now - t_rx) * rate, for at most
    1.5 s). lookahead is auto-tuned from the measured submit -> wave-1-sent latency: (p95 over the last 3 s +
    --sim-margin) * rate, clamped to 0.12..1.5 media s, rising at once and decaying slowly, unless --lookahead fixes
    it (media seconds). Pause: results for frames up to the
    look-ahead already exist, so nothing new is analysed (unless nothing was analysed at that position yet). A jump
    of more than 0.6 s against the extrapolation (seek, loop to the start) resets the trackers and starts a new
    session (new perception.hello, so the client drops its result buffer)."""

    def __init__(self, srv: "Server", path: Path, info: ClipInfo, video_id: str, fixed_lookahead: Optional[float],
                 margin_s: float):
        super().__init__(name="sim-source", daemon=True)
        self.srv, self.path, self.info, self.video_id = srv, Path(path), info, video_id
        self.fixed = fixed_lookahead
        self.margin = margin_s
        self.lookahead = fixed_lookahead if fixed_lookahead is not None else 0.35
        self.stop_evt = threading.Event()
        self.cond = threading.Condition()
        self.pb: Optional[Playback] = None
        self.pending_reset = True
        self.seeks = 0
        self.submitted = 0

    def stop(self) -> None:
        self.stop_evt.set()
        with self.cond:
            self.cond.notify_all()

    # ---- called from the asyncio loop (cheap)
    def on_playback(self, pts: float, playing: bool, rate: float) -> bool:
        now = time.perf_counter()
        with self.cond:
            prev = self.pb
            seek = False
            if prev is not None:
                exp = self._extrapolate(prev, now, cap=False)
                seek = abs(pts - exp) > SEEK_THRESHOLD_S * max(1.0, rate)
                if seek:
                    self.pending_reset = True
            self.pb = Playback(float(pts), bool(playing) and rate > 0, float(rate) if rate > 0 else 1.0, now)
            self.cond.notify_all()
            return seek

    @staticmethod
    def _extrapolate(pb: Playback, now: float, cap: bool = True) -> float:
        if not pb.playing:
            return pb.pts
        dt = now - pb.t
        if cap:
            dt = min(dt, PLAYBACK_STALE_S)
        return pb.pts + dt * pb.rate

    def playback_pts(self, now: Optional[float] = None) -> Optional[float]:
        pb = self.pb
        return None if pb is None else self._extrapolate(pb, time.perf_counter() if now is None else now)

    def playback_rate(self) -> float:
        pb = self.pb
        return pb.rate if pb is not None else 1.0

    # ---- auto-tune
    def _tune(self) -> None:
        if self.fixed is not None:
            return
        now = time.perf_counter()
        lat = [ms for t, ms in list(self.srv.sim_latency) if now - t <= 3.0]
        if len(lat) < 5:
            return
        rate = self.playback_rate()
        want = (float(np.percentile(lat, 95)) / 1000.0 + self.margin) * rate
        want = min(1.5, max(0.12, want))
        self.lookahead = want if want > self.lookahead else 0.95 * self.lookahead + 0.05 * want

    # ---- thread
    def run(self) -> None:
        srv = self.srv
        reader = ClipReader(self.path)
        dt = reader.frame_dt
        last_pts: Optional[float] = None
        seq = 0
        sess: Optional[Session] = None
        t_tune = 0.0
        try:
            while not self.stop_evt.is_set():
                with self.cond:
                    if self.pb is None:
                        self.cond.wait(0.1)
                        continue
                    pb, reset = self.pb, self.pending_reset
                if not srv.pipe.inbox.wait_empty(0.1):
                    continue                          # the fast lane has not taken the previous frame yet
                now = time.perf_counter()
                if now - t_tune > 0.25:
                    self._tune()
                    t_tune = now
                if pb.playing and now - pb.t > PLAYBACK_STALE_S and not reset:
                    with self.cond:                   # client stopped reporting: wait for the next report
                        self.cond.wait(0.1)
                    continue
                play = self._extrapolate(pb, now)
                target = play + self.lookahead if pb.playing else play     # lookahead is in media seconds
                if not reset and last_pts is not None:
                    if not pb.playing and target <= last_pts + dt:
                        with self.cond:               # paused inside the analysed range: idle until it changes
                            self.cond.wait(0.1)
                        continue
                    if pb.playing and target < last_pts + 0.5 * dt:
                        time.sleep(min(0.01, (last_pts + 0.5 * dt - target) / pb.rate))
                        continue                      # no newer frame due yet
                if self.info.duration and target > self.info.duration + dt:
                    with self.cond:                   # past the end: wait for a seek / loop
                        self.cond.wait(0.1)
                    continue
                fr = reader.frame_at(target)
                if fr is None:
                    with self.cond:
                        self.cond.wait(0.1)
                    continue
                if reset or sess is None:
                    with self.cond:
                        self.pending_reset = False
                    first = sess is None
                    sess = srv.begin_sim_segment(self, first=first)
                    if sess is None:                  # superseded by another session: stop
                        return
                    seq = 0
                    if not first:
                        self.seeks += 1
                elif last_pts is not None and fr.pts <= last_pts:
                    continue
                srv.frames_in += 1
                old = srv.pipe.submit(Item(seq=seq, index=fr.index, pts_s=fr.pts, t_grab=time.perf_counter(),
                                           session_id=sess.id, source=sess.source, image=fr.image,
                                           reset=seq == 0, new_session=seq == 0, mode="sim",
                                           extra={"playPts": play}))
                if old is not None:
                    srv.frames_dropped += 1
                last_pts = fr.pts
                seq += 1
                self.submitted += 1
        except Exception as e:
            srv.lane_error(f"sim source: {type(e).__name__}: {e}")
            traceback.print_exc()
        finally:
            reader.release()


# ----------------------------------------------------------------------------- navigation
def haversine_m(a: dict[str, float], b: dict[str, float]) -> float:
    """Great-circle distance in metres between two {lat, lng} points."""
    la1, la2 = math.radians(a["lat"]), math.radians(b["lat"])
    h = (math.sin((la2 - la1) / 2) ** 2
         + math.cos(la1) * math.cos(la2) * math.sin(math.radians(b["lng"] - a["lng"]) / 2) ** 2)
    return 2 * 6_371_000.0 * math.asin(min(1.0, math.sqrt(h)))


def is_latlng(v: Any) -> bool:
    def num(x: Any) -> bool:
        return isinstance(x, (int, float)) and not isinstance(x, bool) and math.isfinite(x)
    return (isinstance(v, dict) and num(v.get("lat")) and num(v.get("lng"))
            and -90 <= v["lat"] <= 90 and -180 <= v["lng"] <= 180)


def make_places(request_id: str, query: str, provider: str, places: list[dict[str, Any]],
                error: Optional[str]) -> dict[str, Any]:
    """navigation.places: the answer to one client.place_search (PROTOCOL_v2 Navigation)."""
    return {"type": "navigation.places", "schemaVersion": 2, "serverTimeMs": now_ms(), "requestId": request_id,
            "query": query, "provider": provider, "places": places, "error": error}


def make_speed_limits(a: argparse.Namespace) -> Optional[Any]:
    """--speed-limits osm -> an OsmSpeedLimits (sends the car position to the Overpass endpoint); else None."""
    if getattr(a, "speed_limits", "off") != "osm":
        return None
    from perception.realtime.speed_limit import DEFAULT_ENDPOINT, OsmSpeedLimits
    endpoint = getattr(a, "speed_limit_endpoint", None) or DEFAULT_ENDPOINT
    print(f"[nav] speed limits: OpenStreetMap Overpass ({endpoint}); the car position is sent there", flush=True)
    return OsmSpeedLimits(endpoint)


def load_nav_relay_class(spec: Optional[str]) -> tuple[Optional[type], Optional[str]]:
    """NavRelay from perception.realtime.nav_relay (written by the navigation side), or `module:Class` (tests)."""
    try:
        if spec:
            mod, _, cls = spec.partition(":")
            return getattr(importlib.import_module(mod), cls or "NavRelay"), None
        from perception.realtime.nav_relay import NavRelay
        return NavRelay, None
    except Exception as e:  # nav is optional: the server works without Node / phase1
        return None, f"{type(e).__name__}: {e}"


NAV_SESSION_FILES = ("session_manifest.json", "route.json", "trip_state.jsonl")


def read_nav_manifest(session_dir: Path) -> Optional[dict[str, Any]]:
    """session_manifest.json of a phase1 session folder, or None when missing / not a JSON object."""
    try:
        m = json.loads((Path(session_dir) / "session_manifest.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return m if isinstance(m, dict) else None


def nav_session_video_id(session_dir: Path) -> str:
    """The clip a phase1 session was recorded for: its manifest's videoId, else the folder name."""
    vid = (read_nav_manifest(session_dir) or {}).get("videoId")
    return str(vid) if vid else Path(session_dir).name


def own_nav_session(clip: Optional[Path]) -> Optional[Path]:
    """The phase1 session a clip was recorded with: the clip's folder when session_manifest.json, route.json and
    trip_state.jsonl sit next to it and the manifest's videoFile (default video.mp4) is the clip
    (data/sim_videos/real_009/video.mp4). None otherwise, e.g. a BDD100K clip: its demo session lives in
    nav/demo_sessions/<id>/ without the clip and is given with --nav-session."""
    if clip is None:
        return None
    clip = Path(clip)
    folder = clip.parent
    if not all((folder / f).is_file() for f in NAV_SESSION_FILES):
        return None
    m = read_nav_manifest(folder)
    return folder if m is not None and str(m.get("videoFile") or "video.mp4") == clip.name else None


def same_dir(a: Optional[Path], b: Optional[Path]) -> bool:
    if a is None or b is None:
        return a is b
    with contextlib.suppress(OSError):
        return Path(a).resolve() == Path(b).resolve()
    return Path(a) == Path(b)


def choose_nav_session(video_id: Optional[str], clip: Optional[Path], explicit: Optional[Path]
                       ) -> tuple[Optional[Path], Optional[str], Optional[str]]:
    """Which phase1 session drives sim / video navigation while `video_id` (file `clip`) plays:
    (session folder or None, source "clip" | "--nav-session" | None, mismatch message or None).
    The clip's own session (own_nav_session) always wins, also over an --nav-session recorded for another clip, so
    the instructions are never another drive's. Without one, --nav-session is used; when its manifest names another
    video the message says so ("navigation session is for real_010, the clip is real_009")."""
    own = own_nav_session(clip)
    if own is not None:
        return own, "clip", None
    if explicit is None:
        return None, None, None
    for_vid = nav_session_video_id(explicit)
    note = None if video_id is None or for_vid == video_id else \
        f"navigation session is for {for_vid}, the clip is {video_id}"
    return explicit, "--nav-session", note


class NavWorker(threading.Thread):
    """Owns the NavRelay (a Node child process speaking JSON lines). Every relay call happens in this thread.
    sim/video: one relay.packet_at_pts(current media pts) per ~0.5 s of playback (and after a seek);
    live: relay.on_trip_state(sample) for each client.trip_state. Results are broadcast as navigation.packet.
    Destination search (client.place_search): relay.search(...) -> one navigation.places to the asking client only.

    Sim session: the server picks it per clip (choose_nav_session) and calls set_session on the loop; run() then
    restarts the relay on it (relay.start_sim). Packets computed for an older session are dropped on the loop, so a
    clip switch never shows the previous drive's instructions. No session (a clip without one and no
    --nav-session): no relay calls, the hello says navigation mode "off".

    Health: the relay's calls return None instead of raising when phase1 fails (NavRelay restarts a crashed child
    by itself). NAV_FAIL_THRESHOLD calls in a row without a packet set available=False with the reason, re-announce
    perception.hello (navigation.available false) and send one perception.error internal; the next packet clears
    it and re-announces the hello. `running` stays true while the relay was started, so trip states keep flowing
    (and can recover it).

    Speed limits (--speed-limits osm): every published packet gets a top-level `speedLimit` (the OpenStreetMap value
    for packet.progress.currentLocation, or null when unknown); with the flag off the field is absent."""

    def __init__(self, srv: "Server", relay_cls: type, a: argparse.Namespace, mode: Optional[str] = None):
        super().__init__(name="nav-worker", daemon=True)
        self.srv, self.relay_cls, self.a = srv, relay_cls, a
        self.mode = mode or ("sim" if a.nav_session else "live")
        # sim: the session folder navigation replays (set_session; --nav-session until a clip picks one)
        explicit = Path(a.nav_session) if a.nav_session else None
        self.session: Optional[Path] = (explicit if explicit is None or explicit.is_absolute()
                                        else PROJECT_ROOT / explicit)
        self.session_source: Optional[str] = "--nav-session" if explicit else None
        self.session_video: Optional[str] = nav_session_video_id(self.session) if self.session else None
        self.session_note: Optional[str] = None      # mismatch message, reported as navigation.error
        self.session_gen = 0                         # bumped by set_session (loop); older packets are dropped
        self.pending_session = self.mode == "sim" and self.session is not None
        self.running_gen: Optional[int] = None       # generation the relay replays (None: no session running)
        self.relay: Any = None
        self.running = False                 # the relay started (start_sim / start_live succeeded)
        self.available = False               # running and producing packets
        self.error: Optional[str] = None
        self.cond = threading.Condition()
        self.trips: deque[tuple[dict[str, Any], Optional[str]]] = deque(maxlen=8)
        self.stop_evt = threading.Event()
        self.packets = 0
        self.failures = 0                    # consecutive relay calls without a packet
        self.last_ms: Optional[float] = None
        self.destination: Optional[str] = getattr(a, "nav_destination", None)  # live: current target query / label
        # live: (query, picked place or None), set by client.destination, applied in run()
        self.pending_destination: Optional[tuple[str, Optional[dict[str, Any]]]] = None
        self.searches: deque[tuple[str, str, str, Optional[dict[str, float]]]] = deque(maxlen=16)  # key, id, q, near
        self.speed_limits: Any = make_speed_limits(a)   # OsmSpeedLimits or None (--speed-limits off)

    def info(self) -> dict[str, Any]:
        if self.mode == "sim" and self.session is None:          # this clip has no session: no navigation
            return {"mode": "off", "available": False, "error": self.error, "destination": None}
        return {"mode": self.mode, "available": self.available, "error": self.error or self.session_note,
                "destination": self.destination if self.mode == "live" else None}

    def session_info(self) -> Optional[dict[str, Any]]:
        """perception.hello server.navigationSession: which recorded session drives sim navigation (None: none)."""
        if self.mode != "sim" or self.session is None:
            return None
        return {"id": self.session.name, "videoId": self.session_video, "source": self.session_source}

    def set_session(self, session: Optional[Path], source: Optional[str], note: Optional[str]) -> bool:
        """Sim: replay `session` from now on (None: no navigation for this clip). Called on the loop; run() restarts
        the relay. Returns True when the session changed (the caller drops the last packet)."""
        with self.cond:
            changed = not same_dir(session, self.session)
            self.session_source, self.session_note = source, note
            if changed:
                self.session = session
                self.session_video = nav_session_video_id(session) if session is not None else None
                self.session_gen += 1
                self.pending_session = True
                self.cond.notify()
        return changed

    def set_destination(self, query: str, place: Optional[dict[str, Any]] = None) -> None:
        """Live: a new target from the tablet. The relay restarts live navigation with it (in run()); the route is
        built by the phase1 provider (mock or Google) from the next client.trip_state position. `place`
        ({label, placeId, coordinate}, a result of client.place_search) is routed to exactly; else `query` is
        geocoded."""
        with self.cond:
            self.pending_destination = (query, place)
            self.cond.notify()

    def submit_search(self, client_key: str, request_id: str, query: str, near: Optional[dict[str, float]]) -> None:
        """client.place_search (checked and rate-limited by the server): answered in run() to that client only."""
        with self.cond:
            self.searches.append((client_key, request_id, query, near))
            self.cond.notify()

    def submit_trip(self, sample: dict[str, Any], client_key: Optional[str]) -> None:
        with self.cond:
            self.trips.append((sample, client_key))
            self.cond.notify()

    def stop(self) -> None:
        self.stop_evt.set()
        with self.cond:
            self.cond.notify()

    def _call(self, fn: Callable, *args) -> Optional[dict[str, Any]]:
        t0 = time.perf_counter()
        try:
            pkt = fn(*args)
        except Exception as e:
            return self._failed(f"{type(e).__name__}: {e}")
        self.last_ms = (time.perf_counter() - t0) * 1000.0
        if not isinstance(pkt, dict):
            why = getattr(self.relay, "last_error", None) or "the relay returned no packet (see the server log)"
            return self._failed(str(why))
        if not self.available or self.failures >= NAV_FAIL_THRESHOLD:
            print(f"[nav] relay producing packets again (after {self.failures} failed calls)", flush=True)
            self.available, self.error = True, None
            self.srv.post(self.srv.broadcast_hello, False)
        self.failures = 0
        return pkt

    def _failed(self, why: str) -> None:
        self.failures += 1
        if self.failures == NAV_FAIL_THRESHOLD:
            self.available = False
            self.error = f"navigation relay failing ({self.failures} calls without a packet): {why}"
            print(f"[nav] {self.error}", flush=True)
            self.srv.post(self.srv.broadcast_error, "internal", self.error)
            self.srv.post(self.srv.broadcast_hello, False)
        elif self.failures % 100 == 0:
            print(f"[nav] relay still failing ({self.failures} calls): {why}", flush=True)
        return None

    def _apply_destination(self, query: str, place: Optional[dict[str, Any]] = None) -> None:
        try:
            if place is not None:                    # picked from navigation.places: that exact point, no geocode
                self.relay.start_live(destination_place=place, provider=self.a.nav_provider)
            else:
                self.relay.start_live(destination=query, provider=self.a.nav_provider)
        except Exception as e:
            self.available = False
            self.error = f"destination {query!r} not usable: {type(e).__name__}: {e}"
            print(f"[nav] {self.error}", flush=True)
            self.srv.post(self.srv.broadcast_error, "internal", self.error)
            self.srv.post(self.srv.broadcast_hello, False)
            return
        self.destination = query
        self.error = None
        self.failures = 0
        print(f"[nav] destination {query!r} ({self.a.nav_provider}{', picked place' if place else ''}); route from "
              f"the next client.trip_state", flush=True)
        self.srv.post(self.srv.broadcast_hello, False)

    def _search(self, key: str, request_id: str, query: str, near: Optional[dict[str, float]]) -> None:
        """relay.search -> one navigation.places (built here, sent on the loop to that client only). On failure
        places is empty and error carries the relay's message (redacted: never a key)."""
        provider = self.a.nav_provider
        places: list[dict[str, Any]] = []
        error: Optional[str] = None
        try:
            found = self.relay.search(query, near=near, provider=provider)
        except Exception as e:
            from perception.realtime.nav_relay import redact
            error = redact(str(e) or type(e).__name__)[:300]
            print(f"[nav] search {query!r} ({provider}) failed: {error}", flush=True)
        else:
            for p in found or []:
                loc = p.get("location") if isinstance(p, dict) else None
                if not is_latlng(loc) or not isinstance(p.get("label"), str):
                    continue
                places.append({"placeId": p["placeId"] if isinstance(p.get("placeId"), str) else None,
                               "label": p["label"],
                               "address": p["address"] if isinstance(p.get("address"), str) else None,
                               "location": {"lat": float(loc["lat"]), "lng": float(loc["lng"])},
                               "distanceMeters": round(haversine_m(near, loc), 1) if near else None})
                if len(places) >= PLACES_MAX:
                    break
            print(f"[nav] search {query!r} ({provider}): {len(places)} places", flush=True)
        msg = make_places(request_id, query, provider, places, error)
        self.srv.post(self.srv.deliver_places, key, dumps(msg).decode())

    def _apply_session(self, gen: int, session: Optional[Path]) -> None:
        """Sim: restart the relay on `session` (generation `gen`); None stops the packets (the relay idles)."""
        self.running_gen, self.failures = None, 0
        if session is None:
            self.available, self.error = False, None
            print("[nav] no navigation session for this clip: navigation off until a clip with one plays", flush=True)
            self.srv.post(self.srv.broadcast_hello, False)
            return
        try:
            self.relay.start_sim(str(session))
        except Exception as e:
            self.available = False
            self.error = f"navigation session {session.name} not usable: {type(e).__name__}: {e}"
            print(f"[nav] {self.error}", flush=True)
            self.srv.post(self.srv.broadcast_error, "internal", self.error)
            self.srv.post(self.srv.broadcast_hello, False)
            return
        self.running_gen = gen
        self.available, self.error = True, None
        print(f"[nav] sim session {session.name} (recorded for {self.session_video}; {self.session_source})",
              flush=True)
        self.srv.post(self.srv.broadcast_hello, False)

    def _deliver_nav(self, gen: int, data: str) -> None:
        """(loop) A sim packet, unless the clip switched to another session after it was computed."""
        if gen == self.session_gen:
            self.srv.broadcast_nav(data)

    def _publish(self, pkt: dict[str, Any], gen: Optional[int] = None) -> None:
        self.packets += 1
        if self.speed_limits is not None:
            try:
                limit = self.speed_limits.for_packet(pkt, now_ms())      # never waits for the network
            except Exception as e:  # a lookup bug must not stop navigation
                limit = None
                print(f"[nav] speed limit lookup failed: {type(e).__name__}: {e}", flush=True)
            pkt = {**pkt, "speedLimit": limit}
        if gen is None:
            self.srv.post(self.srv.broadcast_nav, dumps(pkt).decode())
        else:
            self.srv.post(self._deliver_nav, gen, dumps(pkt).decode())

    def run(self) -> None:
        a = self.a
        try:
            kw = {"phase1_dir": a.phase1_dir} if a.phase1_dir else {}
            self.relay = self.relay_cls(node_exe=a.node, **kw)
            # --nav-live without --nav-route / --nav-destination: wait for the tablet's client.destination.
            waiting = self.mode == "live" and not (a.nav_route or a.nav_destination)
            if self.mode == "live" and not waiting:
                self.relay.start_live(route_json=a.nav_route, origin=a.nav_origin, destination=a.nav_destination,
                                      provider=a.nav_provider)
            self.running = True
            self.available = self.mode == "live" and not waiting      # sim: once a session started (below)
            self.error = 'waiting for a destination (client.destination from the tablet)' if waiting else None
            print(f"[nav] relay running ({self.mode}{', ' + self.error if waiting else ''})", flush=True)
        except Exception as e:
            self.error = f"{type(e).__name__}: {e}"
            print(f"[nav] relay not available: {self.error}", flush=True)
            return
        last_pts: Optional[float] = None
        try:
            while not self.stop_evt.is_set():
                with self.cond:
                    if (not self.trips and self.pending_destination is None and not self.searches
                            and not self.pending_session):
                        self.cond.wait(0.1)
                    trips = list(self.trips)
                    self.trips.clear()
                    searches = list(self.searches)
                    self.searches.clear()
                    dest, self.pending_destination = self.pending_destination, None
                    switch = (self.session_gen, self.session) if self.pending_session else None
                    self.pending_session = False
                if switch is not None and self.mode == "sim":
                    self._apply_session(*switch)
                    last_pts = None                          # first packet of the new session right away
                if dest is not None and self.mode == "live":
                    self._apply_destination(*dest)
                for sample, _key in trips:
                    pkt = self._call(self.relay.on_trip_state, sample)
                    if pkt is not None:
                        self._publish(pkt)
                for search in searches:                      # after the trips: a Google search may take ~1 s
                    self._search(*search)
                if self.mode == "sim":
                    gen = self.running_gen
                    pts = self.srv.media_pts_now() if gen is not None else None
                    if pts is None:
                        continue
                    if last_pts is None or abs(pts - last_pts) >= NAV_PERIOD_S:
                        last_pts = pts
                        pkt = self._call(self.relay.packet_at_pts, float(pts))
                        if pkt is not None:
                            self._publish(pkt, gen)
        finally:
            with contextlib.suppress(Exception):
                self.relay.close()


# ----------------------------------------------------------------------------- server
class Server:
    def __init__(self, engine: Any, a: argparse.Namespace):
        self.engine = engine
        self.a = a
        self.mode_arg = a.mode
        self.accepted = ["live", "sim"] if a.mode == "auto" else [a.mode]
        self.default_mode = self.accepted[0]
        self.max_in_flight = max(1, int(a.max_in_flight))
        self.fixed_lookahead = None if str(a.lookahead).lower() == "auto" else float(a.lookahead)
        self.bus = PerceptionBus()
        self.clients: dict[str, Client] = {}
        self.aloop: Optional[asyncio.AbstractEventLoop] = None
        self.lock = threading.RLock()
        self.session = self._idle_session()
        self.announced: Optional[tuple] = None
        self.desc = engine.describe()
        self.video_dirs = [self._abs(Path(d)) for d in list(DEFAULT_VIDEO_DIRS) + list(a.video_dir or [])]
        self.nav_session_arg: Optional[Path] = self._abs(Path(a.nav_session)) if a.nav_session else None
        self.session_dirs = [self.nav_session_arg] if self.nav_session_arg else []
        self.videos = index_videos(self.video_dirs, self.session_dirs) if "sim" in self.accepted else {}
        self.video_src: Optional[VideoSource] = None
        self.sim_src: Optional[SimSource] = None
        self.start_evt = threading.Event()
        self.nav: Optional[NavWorker] = None
        self.nav_unavailable: Optional[str] = None
        self.last_nav: Optional[str] = None
        self.live_seq = 0
        self.live_first_ns: Optional[int] = None
        # stats (lane threads append; deque appends are atomic)
        self.frames_in = 0
        self.frames_analysed = 0
        self.frames_skipped = 0
        self.frames_dropped = 0
        self.updates = 0
        self.w1: deque = deque(maxlen=4000)            # (t_done, processing ms, compute ms)
        self.w2: deque = deque(maxlen=2000)            # (t_done, processing ms, compute ms, distance ran)
        self.sim_latency: deque = deque(maxlen=400)    # (t_done, submit -> wave-1 built ms)
        self.sim_lead: deque = deque(maxlen=2000)      # (t_done, media ms the result is ahead of playback)
        self.errors: deque[str] = deque(maxlen=20)
        self.started_at = time.time()
        self.pipe = TwoLanePipeline(engine, on_wave1=self.on_wave1, on_wave2=self.on_wave2, on_skip=self.on_skip,
                                    on_session=self.on_lane_session, camera_for=self.camera_for)

    @staticmethod
    def _abs(p: Path) -> Path:
        return p if p.is_absolute() else PROJECT_ROOT / p

    def _idle_session(self) -> Session:
        return Session("idle", self.default_mode, {"kind": "camera" if self.default_mode == "live" else "video",
                                                    "id": ""})

    # ------------------------------------------------------------------ thread -> loop
    def post(self, fn: Callable, *args) -> None:
        loop = self.aloop
        if loop is not None and not loop.is_closed():
            with contextlib.suppress(RuntimeError):
                loop.call_soon_threadsafe(fn, *args)

    def lane_error(self, msg: str) -> None:
        self.errors.append(msg)

    # ------------------------------------------------------------------ hello / broadcast (loop)
    def nav_info(self) -> dict[str, Any]:
        if self.nav is not None:
            return self.nav.info()
        return {"mode": "off", "available": False, "error": self.nav_unavailable}

    def nav_session_info(self) -> Optional[dict[str, Any]]:
        return self.nav.session_info() if self.nav is not None else None

    def sim_nav(self) -> bool:
        """Navigation follows recorded sessions by media time: --nav-session, or no live navigation flag at all."""
        a = self.a
        return bool(a.nav_session) or not (a.nav_route or a.nav_destination or getattr(a, "nav_live", False))

    def start_nav_worker(self) -> bool:
        """Starts the NavWorker (sim or live, by the flags); False when the relay module cannot be imported."""
        if self.nav is not None:
            return True
        if self.nav_unavailable:
            return False
        cls, err = load_nav_relay_class(self.a.nav_relay_impl)
        if cls is None:
            self.nav_unavailable = f"perception.realtime.nav_relay not importable: {err}"
            print(f"[nav] {self.nav_unavailable}; continuing without navigation", flush=True)
            return False
        self.nav = NavWorker(self, cls, self.a, mode="sim" if self.sim_nav() else "live")
        self.nav.start()
        return True

    def select_nav_session(self, video_id: Optional[str], clip: Optional[Path]) -> Optional[str]:
        """Sim / video: point navigation at the session of the clip now playing (choose_nav_session: the clip's own
        session, else --nav-session). Starts the nav worker the first time a clip brings its own session, so a
        recorded drive navigates without any nav flag. Live navigation (GPS trip states) is left alone. Returns the
        mismatch message when --nav-session is for another video (the caller tells the controller)."""
        if not self.sim_nav():
            return None
        session, source, note = choose_nav_session(video_id, clip, self.nav_session_arg)
        explicit = self.nav_session_arg
        if source == "clip" and explicit is not None and not same_dir(session, explicit):
            print(f"[nav] {video_id} has its own session ({session.name}): it drives navigation instead of "
                  f"--nav-session {explicit.name} (recorded for {nav_session_video_id(explicit)})", flush=True)
        elif note:
            print(f"[nav] {note}: --nav-session {explicit.name} kept (the clip has no session of its own)",
                  flush=True)
        if self.nav is None and (session is None or not self.start_nav_worker()):
            return None
        if self.nav.mode == "sim" and self.nav.set_session(session, source, note):
            self.last_nav = None                  # the previous session's packet must not reach a new client
        return note

    def hello_for(self, c: Optional[Client]) -> str:
        s = self.session
        uplink = ({"maxInFlight": self.max_in_flight, **PREFERRED_UPLINK} if "live" in self.accepted else None)
        sim = None
        if "sim" in self.accepted:
            src = self.sim_src
            sim = {"lookaheadSeconds": round(src.lookahead if src else (self.fixed_lookahead or 0.35), 3),
                   "lookaheadMode": "fixed" if self.fixed_lookahead is not None else "auto",
                   "videos": sorted(self.videos), "videoId": s.video_id if s.mode == "sim" else None}
        role = None if c is None else ("controller" if s.controller == c.key else "watcher")
        msg = make_hello(s.id, s.source, self.desc, mode=s.mode, accepted_modes=self.accepted, image=s.image,
                         camera=s.camera, source_fps=s.source_fps, uplink=uplink, sim=sim,
                         server={"modeArg": self.mode_arg, "wsPath": "/perception", "port": self.a.port,
                                 "loop": bool(self.a.loop), "maxInFlight": self.max_in_flight,
                                 "navigationSession": self.nav_session_info()},
                         navigation=self.nav_info(), role=role)
        return dumps(msg).decode()

    def broadcast_hello(self, flush: bool = True) -> None:
        """New session (or new camera / image size): every client gets a hello; stale results are dropped."""
        s = self.session
        self.announced = (s.id, tuple(sorted((s.image or {}).items())), repr(s.camera))
        for c in list(self.clients.values()):
            if flush:
                echo = c.take_frame()
                if echo is not None:
                    self.send_skip(c, echo, "sessionReset")
            c.offer_ctrl(self.hello_for(c))

    def broadcast_ctrl(self, data: str) -> None:
        for c in list(self.clients.values()):
            c.offer_ctrl(data)

    def broadcast_error(self, code: str, message: str) -> None:
        for c in list(self.clients.values()):
            c.error(code, message)

    def broadcast_nav(self, data: str) -> None:
        self.last_nav = data
        for c in list(self.clients.values()):
            c.offer_nav(data)

    def deliver_places(self, key: str, data: str) -> None:
        """navigation.places goes to the client that searched only (reliable control queue)."""
        c = self.clients.get(key)
        if c is not None:
            c.offer_ctrl(data)

    def send_skip(self, c: Client, frame_id: int, reason: str) -> None:
        self.frames_skipped += 1
        c.offer_ctrl(dumps(make_skip(frame_id, reason, self.session.id)).decode())

    def send_skip_key(self, key: Optional[str], frame_id: int, reason: str) -> None:
        c = self.clients.get(key) if key else None
        if c is not None:
            self.send_skip(c, frame_id, reason)
        else:
            self.frames_skipped += 1

    def deliver_frame(self, data: str, item: Item) -> None:
        if item.session_id != self.session.id:          # the session ended while this was queued for the loop
            if item.echo:
                self.send_skip_key(item.client_id, item.echo["frameId"], "sessionReset")
            return
        echo_id = item.echo["frameId"] if item.echo else None
        for c in list(self.clients.values()):
            replaced = c.offer_frame(data, echo_id if c.key == item.client_id else None)
            if replaced is not None:
                self.send_skip(c, replaced, "superseded")

    def deliver_update(self, data: str, session_id: str) -> None:
        if session_id != self.session.id:
            return
        for c in list(self.clients.values()):
            c.offer_update(data)

    # ------------------------------------------------------------------ lane callbacks (lane threads)
    def camera_for(self, item: Item, wh: tuple[int, int]) -> Any:
        """SessionCamera of a new stream: client.hello intrinsics (live) adapted to the actual upright frame size."""
        cc = dict(item.camera) if (item.mode == "live" and item.camera) else None
        if cc and cc.get("imageWidth") and cc.get("imageHeight"):
            W0, H0 = int(cc["imageWidth"]), int(cc["imageHeight"])
            w, h = wh
            if (W0, H0) != (w, h):
                if abs(W0 / H0 - w / h) < 0.01:                      # same aspect, other resolution: scale
                    s = w / W0
                    if cc.get("focalPx"):
                        cc["focalPx"] = float(cc["focalPx"]) * s
                    if cc.get("principalPoint"):
                        cc["principalPoint"] = [float(v) * s for v in cc["principalPoint"][:2]]
                    note = f"intrinsics scaled by {s:.3f}"
                elif (W0, H0) == (h, w):                            # rotated 90/270 vs. the hello
                    if cc.get("focalPx"):
                        cc["focalPx"] = float(cc["focalPx"]) * max(w, h) / max(W0, H0)
                    cc["principalPoint"] = None
                    note = "treated as rotated: focal kept, principal point = image centre"
                else:
                    cc["focalPx"], cc["principalPoint"] = None, None
                    note = "unrelated size: default focal + centred principal point, mount height kept"
                cc["imageWidth"], cc["imageHeight"] = w, h
                self.post(self._camera_mismatch, item.client_id, (W0, H0), (w, h), note)
        return self.engine.session_camera(cc, wh)

    def _camera_mismatch(self, key: Optional[str], hello_wh, frame_wh, note: str) -> None:
        c = self.clients.get(key) if key else None
        if c is not None:
            c.error("badMessage", f"client.hello camera is {hello_wh[0]}x{hello_wh[1]} but the uplinked frames are "
                                  f"{frame_wh[0]}x{frame_wh[1]} after rotation ({note})",
                    detail={"helloSize": list(hello_wh), "frameSize": list(frame_wh)})

    def on_lane_session(self, item: Item, cam: Any) -> None:
        """Fast lane began a stream: publish the camera / image size actually used (hello if it changed)."""
        self.post(self._lane_session, item.session_id, cam, bool(item.extra.get("resized")) and item.mode == "live")

    def _lane_session(self, session_id: str, cam: Any, resized: bool = False) -> None:
        """`resized`: the upright uplink frame size changed in the middle of a live session. The fast lane reset its
        trackers (track ids restart, in another coordinate space), so this is a NEW session: new sessionId, ptsSeconds
        restarts, and every client drops its tracks (PROTOCOL_v2 Sessions). Otherwise the same session is re-announced
        only if the camera / image size differs from what the last hello said."""
        with self.lock:
            s = self.session
            if s.id != session_id or cam is None:
                return
            cam_d = dict(cam.to_dict())
            cam_d["principalPoint"] = [round(float(v), 2) for v in cam_d["principalPoint"]]
            cam_d["focalPx"] = round(float(cam_d["focalPx"]), 2)
            image = {"width": int(cam.width), "height": int(cam.height)}
            if resized and s.mode == "live":
                self.session = dataclasses.replace(s, id=new_session_id(f"live-{s.source.get('id') or 'camera'}"),
                                                   image=image, camera=cam_d)
                self.live_seq = 0                            # next frame: seq 0 = reset, ptsSeconds from 0
                self.live_first_ns = None
            else:
                self.session = dataclasses.replace(s, image=image, camera=cam_d)
            s2 = self.session
        if s2.id != session_id:
            self.broadcast_hello(flush=True)
        elif (s2.id, tuple(sorted(s2.image.items())), repr(s2.camera)) != self.announced:
            self.broadcast_hello(flush=False)

    def on_wave1(self, item: Item, result: Any, meta: dict[str, Any], timings: dict[str, float]) -> None:
        if item.session_id != self.session.id:          # analysed for a session that has ended
            if item.echo:
                self.post(self.send_skip_key, item.client_id, item.echo["frameId"], "sessionReset")
            return
        msg = to_wire(result, {**meta, "seq": item.seq, "sessionId": item.session_id, "source": item.source,
                               "echo": item.echo, "timingsMs": timings})
        t_done = time.perf_counter()
        msg["serverTimeMs"] = now_ms()
        msg["processingMs"] = round((t_done - item.t_grab) * 1000.0, 2)
        self.bus.publish(msg)
        self.post(self.deliver_frame, dumps(msg).decode(), item)
        self.frames_analysed += 1
        self.w1.append((t_done, msg["processingMs"], float(timings.get("fastLane", 0.0))))
        if item.mode == "sim":
            self.sim_latency.append((t_done, msg["processingMs"]))
            src = self.sim_src
            play = src.playback_pts(t_done) if src is not None else None
            if play is not None:
                self.sim_lead.append((t_done, (item.pts_s - play) * 1000.0 / max(0.1, src.playback_rate())))

    def on_wave2(self, item: Item, out: Any) -> None:
        if item.session_id != self.session.id:
            return
        img = out.snapshot.image
        t_done = time.perf_counter()
        msg = make_update(out, {"seq": item.seq, "sessionId": item.session_id, "frameIndex": item.index,
                                "ptsSeconds": item.pts_s, "echo": item.echo,
                                "image": {"width": int(img.shape[1]), "height": int(img.shape[0])},
                                "camera": out.camera, "processingMs": (t_done - item.t_grab) * 1000.0})
        self.bus.publish(msg)
        self.post(self.deliver_update, dumps(msg).decode(), item.session_id)
        self.updates += 1
        self.w2.append((t_done, msg["processingMs"], float(out.timings.get("total", 0.0)), "distance" in out.ran))

    def on_skip(self, item: Item, reason: str) -> None:
        if item.echo:
            self.post(self.send_skip_key, item.client_id, item.echo["frameId"], reason)
            if reason == "notAccepted":
                self.post(self._internal_error, item.client_id)
            elif reason == "decodeError":
                self.post(self._decode_error, item.client_id, item.echo["frameId"])

    def _decode_error(self, key: Optional[str], frame_id: int) -> None:
        c = self.clients.get(key) if key else None
        if c is not None:
            c.error("badMessage", f"frame {frame_id}: the JPEG payload could not be decoded (baseline JPEG expected)")

    def _internal_error(self, key: Optional[str]) -> None:
        c = self.clients.get(key) if key else None
        if c is not None:
            c.error("internal", "the fast lane failed on an uplinked frame (answered with perception.skip); "
                                f"last error: {self.pipe.errors[-1] if self.pipe.errors else '?'}")

    def superseded(self, old: Item) -> None:
        """An item replaced in the pipeline inbox before the fast lane took it (loop)."""
        if old.echo:
            self.send_skip_key(old.client_id, old.echo["frameId"], "superseded")
        else:
            self.frames_dropped += 1

    # ------------------------------------------------------------------ sessions
    def begin_video_session(self, stem: str, info: ClipInfo) -> Session:
        """VideoSource thread: a new playback of the clip (start or loop)."""
        cam = self.engine.session_camera(None, (info.width, info.height))
        with self.lock:
            self.session = Session(new_session_id(f"video-{stem}"), "video", {"kind": "video", "id": stem},
                                   image={"width": cam.width, "height": cam.height}, camera=cam.to_dict(),
                                   video_id=stem, source_fps=info.fps)
            s = self.session
        self.post(self.broadcast_hello)
        return s

    def begin_sim_segment(self, src: SimSource, first: bool) -> Optional[Session]:
        """SimSource thread: first frame of a sim session, or after a seek (new session id, trackers reset)."""
        with self.lock:
            s = self.session
            if self.sim_src is not src or s.mode != "sim":
                return None
            if not first:
                self.session = dataclasses.replace(s, id=new_session_id(f"sim-{src.video_id}"))
            s = self.session
        if not first:
            self.post(self.broadcast_hello)
        return s

    def _stop_sim(self) -> None:
        if self.sim_src is not None:
            self.sim_src.stop()
            self.sim_src = None

    def _drain_inbox(self) -> None:
        old = self.pipe.inbox.take()
        if old is not None and old.echo:
            self.send_skip_key(old.client_id, old.echo["frameId"], "sessionReset")

    def _demote_controller(self, new: Client) -> None:
        s = self.session
        if s.controller and s.controller != new.key:
            old = self.clients.get(s.controller)
            if old is not None:
                old.role = "watcher"
                old.error("notUplinkClient", f"client {new.name} took over the session; this client is now a "
                                             "watcher", min_interval_s=0)

    def start_live_session(self, c: Client, camera: Optional[dict[str, Any]]) -> None:
        with self.lock:
            self._demote_controller(c)
            self._stop_sim()
            self._drain_inbox()
            if camera and camera.get("imageWidth") and camera.get("imageHeight"):
                cam = self.engine.session_camera(camera)
            else:
                cam = self.engine.session_camera(None, (PREFERRED_UPLINK["preferredWidth"],
                                                        PREFERRED_UPLINK["preferredHeight"]))
            self.session = Session(new_session_id(f"live-{c.name}"), "live", {"kind": "camera", "id": c.name},
                                   image={"width": cam.width, "height": cam.height}, camera=cam.to_dict(),
                                   controller=c.key, client_camera=camera)
            self.live_seq = 0
            self.live_first_ns = None
        c.role = "controller"
        if self.nav is not None and self.nav.mode == "sim":
            self.select_nav_session(None, None)                 # a camera has no recorded session of its own
        self.broadcast_hello()

    def video_dirs_text(self) -> str:
        def rel(p: Path) -> str:
            with contextlib.suppress(ValueError):
                return p.relative_to(PROJECT_ROOT).as_posix()
            return p.name if p.is_absolute() else str(p)
        return ", ".join(rel(d) for d in self.video_dirs + self.session_dirs)

    async def start_sim_session(self, c: Client, video_id: str, retry_now: bool = True) -> bool:
        """Starts a sim session on `video_id` with `c` as controller; False when the clip is not available.

        A missing clip triggers one rescan (it may have been copied in after start-up) and one unknownVideo error.
        With retry_now=False (client.playback, ~10 Hz) the same missing videoId is re-checked at most every
        UNKNOWN_VIDEO_RETRY_S per client, so a tablet playing a clip the laptop lacks gets no error storm. When the
        current controller asks for a missing clip, its old session ends (_sim_unavailable): no results of the
        previous clip keep flowing to the tablet."""
        path = self.videos.get(video_id)
        if path is None:
            now = time.monotonic()
            last = c.unknown_videos.get(video_id)
            if not retry_now and last is not None and now - last < UNKNOWN_VIDEO_RETRY_S:
                return False
            c.unknown_videos[video_id] = now
            self.videos = await asyncio.to_thread(index_videos, self.video_dirs, self.session_dirs)
            path = self.videos.get(video_id)
        if path is None:
            self._sim_unavailable(c, video_id)
            names = sorted(self.videos)
            c.error("unknownVideo", f"videoId {video_id!r} is not on the laptop (looked in {self.video_dirs_text()}"
                                    f"; {len(names)} clips there)",
                    detail={"videoId": video_id, "videos": names[:50]}, min_interval_s=0)
            return False
        try:
            info = await asyncio.to_thread(probe_clip, path)
        except Exception as e:
            self._sim_unavailable(c, video_id)
            c.error("unknownVideo", f"cannot open {path.name}: {e}", detail={"videoId": video_id}, min_interval_s=0)
            return False
        c.unknown_videos.pop(video_id, None)
        cam = self.engine.session_camera(None, (info.width, info.height))
        with self.lock:
            self._demote_controller(c)
            self._stop_sim()
            self._drain_inbox()
            src = SimSource(self, path, info, video_id, self.fixed_lookahead, self.a.sim_margin)
            self.session = Session(new_session_id(f"sim-{video_id}"), "sim", {"kind": "video", "id": video_id},
                                   image={"width": cam.width, "height": cam.height}, camera=cam.to_dict(),
                                   controller=c.key, video_id=video_id, source_fps=info.fps)
            self.sim_src = src
        c.role = "controller"
        note = self.select_nav_session(video_id, path)          # this clip's own session drives navigation
        src.start()
        self.broadcast_hello()
        if note:                                                # after the hello: a new session clears errors
            c.error("internal", f"{note}: its instructions are not for this drive (the clip has no session of its "
                                "own; give --nav-session the clip's session)",
                    detail={"videoId": video_id, "navigationSession": self.nav_session_info()}, min_interval_s=0)
        return True

    def _sim_unavailable(self, c: Client, video_id: str) -> None:
        """The controller (or a client while nobody controls the server) asked for a clip the laptop cannot play:
        stop analysing the previous clip. `c` becomes / stays the controller of a sim session without a source (new
        sessionId, so clients drop old results); its playback reports keep re-checking the clip (rate-limited) and
        start the real session once it appears. A client that does not control a running session changes nothing."""
        with self.lock:
            s = self.session
            free = s.controller is None or s.controller not in self.clients
            if (s.controller != c.key and not free) or (
                    s.controller == c.key and s.mode == "sim" and self.sim_src is None and s.video_id == video_id):
                return                                   # another client controls it / already in this state
            self._demote_controller(c)
            self._stop_sim()
            self._drain_inbox()
            self.session = Session(new_session_id(f"sim-{video_id}"), "sim", {"kind": "video", "id": video_id},
                                   controller=c.key, video_id=video_id)
        c.role = "controller"
        self.select_nav_session(video_id, None)                 # no clip: --nav-session or nothing
        self.broadcast_hello()

    def end_session(self, c: Client) -> None:
        """The controller disconnected: stop its source and return to idle."""
        with self.lock:
            if self.session.controller != c.key:
                return
            self._stop_sim()
            self._drain_inbox()
            self.session = self._idle_session()
        self.broadcast_hello()

    def media_pts_now(self) -> Optional[float]:
        """Current media position for navigation: sim = extrapolated tablet playback; video = laptop player."""
        src = self.sim_src
        if src is not None:
            return src.playback_pts()
        if self.video_src is not None and not self.video_src.finished:
            return self.video_src.cur_pts
        return None

    # ------------------------------------------------------------------ client messages (loop)
    async def on_text(self, c: Client, text: str) -> None:
        import orjson
        try:
            msg = orjson.loads(text)
        except orjson.JSONDecodeError as e:
            c.error("badMessage", f"invalid JSON: {e}")
            return
        if not isinstance(msg, dict):
            c.error("badMessage", "a message must be a JSON object with a 'type'")
            return
        t = msg.get("type")
        if t == "client.hello":
            await self.on_client_hello(c, msg)
        elif t == "client.playback":
            await self.on_playback(c, msg)
        elif t == "client.ping":
            cts = msg.get("clientTimeNs")
            c.offer_ctrl(dumps(make_pong(cts if isinstance(cts, int) else None)).decode())
        elif t == "client.trip_state":
            self.on_trip_state(c, msg)
        elif t == "client.destination":
            self.on_destination(c, msg)
        elif t == "client.place_search":
            self.on_place_search(c, msg)
        else:
            c.error("badMessage", f"unknown message type {t!r}", detail={"type": t})

    async def on_client_hello(self, c: Client, msg: dict[str, Any]) -> None:
        c.hello = msg
        cid = msg.get("clientId")
        c.client_id = str(cid)[:64] if cid else None
        mode = msg.get("mode")
        if msg.get("protocolVersion") not in (None, 2):
            c.error("badMessage", f"protocolVersion {msg.get('protocolVersion')!r}: this server speaks protocol 2",
                    min_interval_s=0)
        if mode not in MODES:
            c.error("badMessage", f"client.hello mode must be one of {list(MODES)}, got {mode!r}", min_interval_s=0)
            c.offer_ctrl(self.hello_for(c))
            return
        if mode not in self.accepted:
            c.error("modeNotAvailable", f"mode {mode!r} is not running on this server (--mode {self.mode_arg}); "
                                        f"accepted: {self.accepted}", detail={"acceptedModes": self.accepted},
                    min_interval_s=0)
            c.role = "watcher"
            c.offer_ctrl(self.hello_for(c))
            return
        if mode == "video":                                  # laptop-clock playback: every client watches
            c.role = "watcher"
            c.offer_ctrl(self.hello_for(c))
            return
        if mode == "live":
            cam = msg.get("camera")
            if cam is not None and not isinstance(cam, dict):
                c.error("badMessage", "client.hello camera must be an object or null", min_interval_s=0)
                cam = None
            if cam:
                bad = [k for k in ("imageWidth", "imageHeight", "focalPx", "mountHeightMeters", "pitchDegrees")
                       if cam.get(k) is not None and (isinstance(cam.get(k), bool)
                                                      or not isinstance(cam.get(k), (int, float)))]
                pp = cam.get("principalPoint")
                if pp is not None and not (isinstance(pp, list) and len(pp) >= 2
                                           and all(isinstance(v, (int, float)) for v in pp[:2])):
                    bad.append("principalPoint")
                if bad:
                    c.error("badMessage", f"client.hello camera fields ignored (not numbers): {bad}",
                            min_interval_s=0)
                    cam = {k: v for k, v in cam.items() if k not in bad}
                if cam.get("stabilization"):
                    c.error("badMessage", "camera.stabilization is true: video stabilization warps frames and "
                                          "breaks distance / lane geometry", min_interval_s=0)
            c.live_camera = cam or None
            self.start_live_session(c, cam or None)
        else:  # sim
            vid = (msg.get("sim") or {}).get("videoId") if isinstance(msg.get("sim"), dict) else None
            if not vid:
                c.error("badMessage", "client.hello mode 'sim' needs sim.videoId", min_interval_s=0)
                c.offer_ctrl(self.hello_for(c))
                return
            await self.start_sim_session(c, str(vid))
        if msg.get("navigation") and self.last_nav:
            c.offer_nav(self.last_nav)

    async def on_playback(self, c: Client, msg: dict[str, Any]) -> None:
        s = self.session
        if s.mode != "sim" or s.controller != c.key:
            if "sim" not in self.accepted:
                c.error("modeNotAvailable", "client.playback: this server is not in sim mode")
            else:
                c.error("notUplinkClient", "client.playback from a client that does not control the sim session "
                                           "(send client.hello with mode 'sim' first)")
            return
        vid = msg.get("videoId")
        vid = str(vid) if vid else None
        if self.sim_src is None or (vid and vid != s.video_id):
            # clip switch without a new hello, or the clip was not available (re-checked at most every 5 s)
            want = vid or s.video_id
            if not want or not await self.start_sim_session(c, want, retry_now=False):
                return
        try:
            pts = float(msg["ptsSeconds"])
            rate = msg.get("rate", 1.0)
            rate = 1.0 if rate is None else float(rate)       # rate 0 = paused (SimSource), not 1.0
            playing = bool(msg.get("playing", True))
            if not math.isfinite(pts) or not math.isfinite(rate):
                raise ValueError("not finite")
        except (KeyError, TypeError, ValueError) as e:
            c.error("badMessage", f"client.playback needs numeric ptsSeconds (and rate): {e}")
            return
        src = self.sim_src
        if src is not None:
            src.on_playback(max(0.0, pts), playing, rate)

    @staticmethod
    def check_trip_state(msg: dict[str, Any]) -> tuple[Optional[dict[str, Any]], Optional[str]]:
        """client.trip_state -> (phase1 trip_state body, None) or (None, why it is invalid). heading / speedMps null
        or missing become 0 (PROTOCOL_v2: send 0 when unknown)."""
        def num(v: Any) -> bool:
            return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)
        ts, loc = msg.get("timestampMs"), msg.get("location")
        if not num(ts):
            return None, "timestampMs must be a number (Unix epoch ms)"
        if not isinstance(loc, dict) or not num(loc.get("lat")) or not num(loc.get("lng")):
            return None, "location must be {lat, lng} numbers"
        if not (-90 <= loc["lat"] <= 90 and -180 <= loc["lng"] <= 180):
            return None, f"location out of range: {loc['lat']}, {loc['lng']}"
        body = {k: v for k, v in msg.items() if k != "type"}
        for k in ("heading", "speedMps"):
            if body.get(k) is None:
                body[k] = 0.0
            elif not num(body[k]):
                return None, f"{k} must be a number (0 when unknown)"
        return body, None

    def _live_nav_sender(self, c: Client, what: str, does: str) -> bool:
        """client.destination / client.place_search: live navigation must be running (else one modeNotAvailable)
        and the sender must be the controller or a client whose hello asked for live navigation (notUplinkClient)."""
        if self.nav is None or not self.nav.running or self.nav.mode != "live":
            c.error("modeNotAvailable", f"{what} ignored: live navigation is not running (start the server "
                                        "with --nav-live, --nav-destination or --nav-route)", min_interval_s=0)
            return False
        hint = (c.hello or {}).get("navigation")
        if self.session.controller != c.key and not (isinstance(hint, dict) and hint.get("mode") == "live"):
            c.error("notUplinkClient", f"{what} ignored: only the session controller (or a client whose "
                                       f"client.hello has navigation.mode 'live') {does}")
            return False
        return True

    def on_destination(self, c: Client, msg: dict[str, Any]) -> None:
        """Live navigation target from the tablet: a place or address the phase1 provider geocodes, or a place picked
        from navigation.places (`location` + `placeId`): routed to exactly, `query` is only its label. Same sender
        rule as client.trip_state."""
        if not self._live_nav_sender(c, "client.destination", "sets the destination"):
            return
        q = msg.get("query")
        if not isinstance(q, str) or not q.strip() or len(q) > 200:
            c.error("badMessage", "client.destination ignored: query must be a non-empty string of at most 200 "
                                  "characters", detail={"type": "client.destination"})
            return
        pid, loc = msg.get("placeId"), msg.get("location")
        if (pid is not None and not (isinstance(pid, str) and len(pid) <= 256)) or (loc is not None
                                                                                    and not is_latlng(loc)):
            c.error("badMessage", "client.destination ignored: placeId must be a string of at most 256 characters "
                                  "or null, location {lat, lng} in range or null", detail={"type": "client.destination"})
            return
        label = q.strip()
        place = None if loc is None else {"label": label, "placeId": pid,
                                          "coordinate": {"lat": float(loc["lat"]), "lng": float(loc["lng"])}}
        self.nav.set_destination(label, place)

    def on_place_search(self, c: Client, msg: dict[str, Any]) -> None:
        """Destination search typed on the tablet: one navigation.places to this client only (the relay call runs in
        the nav worker). Same checks as client.destination. At most PLACE_SEARCHES_PER_S searches per second per
        client are answered by the provider; the others get places [] and error "rate limited" at once."""
        if not self._live_nav_sender(c, "client.place_search", "searches destinations"):
            return
        rid, q, near = msg.get("requestId"), msg.get("query"), msg.get("near")
        why = None
        if not isinstance(rid, str) or not 1 <= len(rid) <= 64:
            why = "requestId must be a string of 1-64 characters"
        elif not isinstance(q, str) or not q.strip() or len(q) > 200:
            why = "query must be a non-empty string of at most 200 characters"
        elif near is not None and not is_latlng(near):
            why = "near must be {lat, lng} in range or null"
        if why:
            c.error("badMessage", f"client.place_search ignored: {why}", detail={"type": "client.place_search"})
            return
        query = q.strip()
        now = time.monotonic()
        if len(c.search_times) == c.search_times.maxlen and now - c.search_times[0] < 1.0:
            c.offer_ctrl(dumps(make_places(rid, query, self.a.nav_provider, [], "rate limited")).decode())
            return
        c.search_times.append(now)
        self.nav.submit_search(c.key, rid, query,
                               None if near is None else {"lat": float(near["lat"]), "lng": float(near["lng"])})

    def on_trip_state(self, c: Client, msg: dict[str, Any]) -> None:
        """Live navigation input. Only the session controller (or a client whose hello asked for live navigation)
        may move the route; invalid samples get a rate-limited badMessage instead of vanishing in the relay."""
        if self.nav is None or not self.nav.running or self.nav.mode != "live":
            if not c.nav_error_sent:
                c.nav_error_sent = True
                if self.nav is not None and self.nav.mode == "sim":
                    why = "the server runs sim navigation (a recorded session by media time)"
                elif self.nav is not None and self.nav.error:
                    why = self.nav.error
                else:
                    why = self.nav_unavailable or "start the server with --nav-destination / --nav-route"
                c.error("modeNotAvailable", f"client.trip_state ignored: live navigation is not running ({why})",
                        min_interval_s=0)
            return
        hint = (c.hello or {}).get("navigation")
        if self.session.controller != c.key and not (isinstance(hint, dict) and hint.get("mode") == "live"):
            c.error("notUplinkClient", "client.trip_state ignored: only the session controller (or a client whose "
                                       "client.hello has navigation.mode 'live') feeds live navigation")
            return
        body, why = self.check_trip_state(msg)
        if body is None:
            c.error("badMessage", f"client.trip_state ignored: {why}", detail={"type": "client.trip_state"})
            return
        self.nav.submit_trip(body, c.key)

    def on_binary(self, c: Client, data: bytes) -> None:
        self.frames_in += 1
        try:
            hdr, jpeg = parse_uplink(data)
        except UplinkError as e:
            if e.frame_id is not None:
                self.send_skip(c, e.frame_id, e.reason)
            c.error("badMessage", f"binary message is not a valid SDC1 frame: {e}", detail={"bytes": len(data)})
            return
        if "live" not in self.accepted:
            self.send_skip(c, hdr.frame_id, "notAccepted")
            c.error("modeNotAvailable", f"camera frames are only accepted in live mode (--mode {self.mode_arg})")
            return
        s = self.session
        if s.mode != "live" or s.controller != c.key:
            if s.controller is None or s.controller not in self.clients:
                # implicit hello: a v1-style client (no intrinsics), or a client that sent a live hello earlier and
                # was taken over -> keep the camera of its last live hello
                self.start_live_session(c, c.live_camera)
                s = self.session
            elif s.controller == c.key:
                self.send_skip(c, hdr.frame_id, "notAccepted")
                c.error("badMessage", "camera frame during a sim session: send client.hello with mode 'live' first")
                return
            else:
                self.send_skip(c, hdr.frame_id, "notAccepted")
                c.error("notUplinkClient", f"another client ({self.clients[s.controller].name}) is uplinking; "
                                           "this client is a watcher (send client.hello to take over)")
                return
        if self.live_first_ns is None:
            self.live_first_ns = hdr.capture_time_ns
        seq = self.live_seq
        self.live_seq += 1
        item = Item(seq=seq, index=seq, pts_s=(hdr.capture_time_ns - self.live_first_ns) / 1e9,
                    t_grab=time.perf_counter(), session_id=s.id, source=s.source, jpeg=jpeg,
                    rotation=hdr.rotation_degrees, echo=hdr.echo(), client_id=c.key, reset=seq == 0,
                    new_session=seq == 0, camera=s.client_camera, mode="live")
        old = self.pipe.submit(item)
        if old is not None:
            self.superseded(old)

    # ------------------------------------------------------------------ stats
    def stats_msg(self) -> dict[str, Any]:
        now = time.perf_counter()
        w1 = [r for r in list(self.w1) if now - r[0] <= STATS_WINDOW_S]
        w2 = [r for r in list(self.w2) if now - r[0] <= STATS_WINDOW_S]

        def rate(rows):
            span = rows[-1][0] - rows[0][0] if len(rows) >= 2 else 0.0
            return (len(rows) - 1) / span if span > 0 else 0.0
        s = self.session
        extra: dict[str, Any] = {"framesDropped": int(self.frames_dropped),
                                 "uplinkClient": (self.clients[s.controller].name
                                                  if s.controller in self.clients else None)}
        src = self.sim_src
        if s.mode == "sim":
            lead = [ms for t, ms in list(self.sim_lead) if now - t <= STATS_WINDOW_S]
            extra["simLeadMs"] = {"p5": round(float(np.percentile(lead, 5)), 1) if lead else None,
                                  "p50": round(float(np.percentile(lead, 50)), 1) if lead else None}
            extra["simLateFraction"] = round(sum(v < 0 for v in lead) / len(lead), 3) if lead else None
        if self.nav is not None:
            extra["navigationPackets"] = self.nav.packets
        return make_stats(s.id, mode=s.mode, window_s=STATS_WINDOW_S, output_fps=rate(w1), wave2_fps=rate(w2),
                          distance_fps=rate([r for r in w2 if r[3]]), wave1_ms=[r[1] for r in w1],
                          wave2_ms=[r[1] for r in w2], wave1_compute_ms=[r[2] for r in w1],
                          wave2_compute_ms=[r[2] for r in w2], frames_in=self.frames_in,
                          frames_analysed=self.frames_analysed, frames_skipped=self.frames_skipped,
                          updates=self.updates, clients=len(self.clients),
                          send_dropped=sum(cl.dropped_frames for cl in self.clients.values()),
                          lookahead_s=src.lookahead if (src is not None and s.mode == "sim") else None,
                          source_fps=s.source_fps, uptime_s=time.time() - self.started_at, extra=extra)

    def health(self) -> dict[str, Any]:
        s = self.session
        errs = list(self.pipe.errors) + list(self.errors)
        src = self.sim_src
        return {"status": "ok" if not errs else "degraded", "mode": s.mode, "modeArg": self.mode_arg,
                "acceptedModes": self.accepted, "sessionId": s.id, "source": s.source,
                "controller": self.clients[s.controller].name if s.controller in self.clients else None,
                "clients": [{"key": c.key, "clientId": c.client_id, "peer": c.peer, "role": c.role, "sent": c.sent,
                             "droppedFrames": c.dropped_frames, "droppedUpdates": c.dropped_updates}
                            for c in list(self.clients.values())],
                "stats": self.stats_msg(), "laneWarmupMs": self.pipe.warmup_ms, "navigation": self.nav_info(),
                "navigationSession": self.nav_session_info(),
                "navigationLastCallMs": self.nav.last_ms if self.nav else None,
                "videoFinished": bool(self.video_src and self.video_src.finished),
                "sim": None if src is None else {"videoId": src.video_id, "lookaheadSeconds": round(src.lookahead, 3),
                                                 "submitted": src.submitted, "seeks": src.seeks},
                "errors": errs[-10:]}

    # ------------------------------------------------------------------ lifecycle
    def start_background(self) -> None:
        if self.mode_arg == "video":
            self.video_src = VideoSource(self, self.a.video, self.a.loop, self.start_evt)
            info = self.video_src.info
            cam = self.engine.session_camera(None, (info.width, info.height))
            self.session = Session("video-pending", "video", {"kind": "video", "id": self.a.video.stem},
                                   image={"width": cam.width, "height": cam.height}, camera=cam.to_dict(),
                                   video_id=self.a.video.stem, source_fps=info.fps)
            if not self.a.start_on_connect:
                self.start_evt.set()
            self.video_src.start()
        if self.a.nav_session or self.a.nav_route or self.a.nav_destination or getattr(self.a, "nav_live", False):
            self.start_nav_worker()
        if self.mode_arg == "video":                            # the laptop's clip: its own session wins too
            self.select_nav_session(self.a.video.stem, self.a.video)

    def stop(self) -> None:
        if self.video_src is not None:
            self.video_src.stop()
        self._stop_sim()
        if self.nav is not None:
            self.nav.stop()
            self.nav.join(3.0)
        self.pipe.stop()


def create_app(srv: Server):
    # WebSocket must be a module-level name: with `from __future__ import annotations` FastAPI resolves the
    # endpoint's annotations from the module globals (a local import turns `ws` into a query param -> HTTP 403)
    from fastapi import FastAPI

    # ElevenLabs proxy (POST /tts, GET /tts/health); key from the environment or perception_engine/.env
    tts = TtsProxy.from_env(enabled=not getattr(srv.a, "no_tts", False),
                            allow_lan=getattr(srv.a, "tts_allow_lan", False))

    @contextlib.asynccontextmanager
    async def lifespan(app):
        srv.aloop = asyncio.get_running_loop()
        srv.start_background()
        stats_task = asyncio.create_task(stats_loop())
        await tts.start()                       # shared keep-alive httpx.AsyncClient on this loop
        try:
            yield
        finally:
            stats_task.cancel()
            await tts.close()
            await asyncio.to_thread(srv.stop)

    async def stats_loop():
        while True:
            await asyncio.sleep(1.0)
            if srv.clients:
                try:
                    srv.broadcast_ctrl(dumps(srv.stats_msg()).decode())
                except Exception as e:
                    srv.errors.append(f"stats: {type(e).__name__}: {e}")

    app = FastAPI(title="Perception Engine (protocol v2)", lifespan=lifespan)
    app.state.tts = tts
    add_tts_routes(app, tts)

    @app.get("/health")
    async def health():
        return srv.health()

    @app.get("/config")
    async def config():
        return {"config": srv.engine.cfg, "engine": srv.desc, "modeArg": srv.mode_arg,
                "acceptedModes": srv.accepted, "video": srv.a.video.name if srv.a.video else None,
                "videos": sorted(srv.videos), "configPath": srv.a.config}

    @app.websocket("/perception")
    async def perception(ws: WebSocket):
        await ws.accept()
        c = Client(ws, f"{ws.client.host}:{ws.client.port}" if ws.client else "?")
        handler = asyncio.current_task()

        def evict() -> None:            # the sender could not deliver for SEND_TIMEOUT_S
            print(f"[server] client {c.name} ({c.peer}) evicted: {c.evicted}", flush=True)
            srv.clients.pop(c.key, None)
            if not abort_transport(ws) and handler is not None:
                handler.cancel()        # no transport found: end the handler (receive() never returns otherwise)
        c.on_stall = evict
        srv.clients[c.key] = c
        c.offer_ctrl(srv.hello_for(c))
        if srv.last_nav:
            c.offer_nav(srv.last_nav)
        srv.start_evt.set()                                   # --start-on-connect
        sender = asyncio.create_task(c.sender())
        try:
            while True:
                msg = await ws.receive()
                if msg["type"] == "websocket.disconnect":
                    break
                if msg.get("bytes") is not None:
                    srv.on_binary(c, msg["bytes"])
                elif msg.get("text") is not None:
                    await srv.on_text(c, msg["text"])
        except asyncio.CancelledError:
            if c.evicted is None:
                raise                   # server shutdown
            if handler is not None:
                handler.uncancel()
        except (WebSocketDisconnect, RuntimeError):
            pass
        except Exception as e:
            srv.errors.append(f"client {c.name}: {type(e).__name__}: {e}")
            traceback.print_exc()
        finally:
            srv.clients.pop(c.key, None)
            sender.cancel()
            srv.end_session(c)

    return app


# ----------------------------------------------------------------------------- CLI
def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(description="Realtime Perception Engine WebSocket server, protocol v2 "
                                             "(ws://<host>:<port>/perception). Run from perception_engine/.")
    ap.add_argument("--mode", choices=["video", "live", "sim", "auto"], default=None,
                    help="video: laptop plays --video; live: tablet camera uplink; sim: tablet plays a clip the "
                         "laptop also has; auto: live or sim, chosen by the client's hello")
    ap.add_argument("--video", type=Path, default=None, help="clip for --mode video")
    ap.add_argument("--camera", action="store_true", help=argparse.SUPPRESS)          # v1 alias of --mode live
    ap.add_argument("--loop", action="store_true", help="video mode: loop the clip (each loop = new session)")
    ap.add_argument("--start-on-connect", action="store_true", help="video mode: start when a client connects")
    ap.add_argument("--video-dir", action="append", default=[], help="sim mode: extra folder of clips (repeatable)")
    ap.add_argument("--lookahead", default="auto", help="sim mode: 'auto' (default) or fixed media seconds")
    ap.add_argument("--sim-margin", type=float, default=0.10,
                    help="sim auto look-ahead: seconds added to the measured p95 latency (network + jitter)")
    ap.add_argument("--max-in-flight", type=int, default=2, help="live uplink credits announced in the hello")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--config", default="perception/config_realtime.yaml")
    ap.add_argument("--set", action="append", default=[], metavar="KEY=VALUE",
                    help="config override (YAML value), e.g. --set slow.max_hz=6 --set slow_schedule.depth.every=2")
    ap.add_argument("--no-lane-warmup", action="store_true", help="skip the per-lane warm-up (first frames slow)")
    nav = ap.add_argument_group("navigation (optional; phase1 route engine via perception.realtime.nav_relay)")
    nav.add_argument("--nav-session", default=None,
                     help="phase1 session folder (sim/video: timeline by media pts) for clips without their own; a "
                          "clip whose folder holds its session (data/sim_videos/real_009/) always uses that one")
    nav.add_argument("--nav-route", default=None, help="live: route.json to follow")
    nav.add_argument("--nav-live", action="store_true",
                     help="live navigation with the destination typed on the tablet (client.destination); "
                          "--nav-destination sets a default")
    nav.add_argument("--nav-destination", default=None, help="live: destination query")
    nav.add_argument("--nav-origin", default=None, help="live: origin query (default: first trip_state)")
    nav.add_argument("--nav-provider", default="mock", choices=["mock", "google"])
    nav.add_argument("--phase1-dir", default=None, help="navigation engine folder: spatial/ or a legacy checkout with "
                     "src/phase1 (default: env PHASE1_DIR, then <repo>/spatial)")
    nav.add_argument("--node", default="node", help="Node.js executable")
    nav.add_argument("--nav-relay-impl", default=None, help=argparse.SUPPRESS)      # module:Class (tests)
    nav.add_argument("--speed-limits", default="off", choices=["off", "osm"],
                     help="navigation.packet.speedLimit: osm = posted limits from OpenStreetMap via the Overpass API "
                          "(sends the car position to that server); off (default) = no field")
    nav.add_argument("--speed-limit-endpoint", default=None, metavar="URL",
                     help="Overpass interpreter URL for --speed-limits osm "
                          "(default https://overpass-api.de/api/interpreter)")
    tts = ap.add_argument_group("text-to-speech proxy (ElevenLabs via POST /tts, GET /tts/health; key in the "
                                "environment or perception_engine/.env)")
    tts.add_argument("--no-tts", action="store_true", help="never call ElevenLabs: /tts answers 503 notConfigured")
    tts.add_argument("--tts-allow-lan", action="store_true",
                     help="accept /tts from non-loopback clients (Wi-Fi / hotspot demos); default loopback only "
                          "(USB adb reverse arrives as 127.0.0.1)")
    return ap


def main(argv: Optional[list[str]] = None) -> None:
    a = build_parser().parse_args(argv)
    if a.mode is None:
        a.mode = "live" if a.camera else ("video" if a.video else None)
    if a.mode is None:
        sys.exit("give --mode video|live|sim|auto (and --video for video mode)")
    if a.mode == "video":
        if a.video is None:
            sys.exit("--mode video needs --video <clip>")
        a.video = resolve_data_path(a.video)          # data/... follows PERCEPTION_DATA_DIR
        if not a.video.exists():
            sys.exit(f"video not found: {a.video}")
    if a.speed_limits == "osm" and not (a.nav_session or a.nav_route or a.nav_destination or a.nav_live):
        print("[server] note: --speed-limits osm rides on navigation.packet; without a navigation flag only clips "
              "with their own session navigate", flush=True)
    if a.nav_session and a.mode == "live":
        print("[server] note: --nav-session drives navigation from media time (sim/video); in live mode use "
              "--nav-route / --nav-destination", flush=True)

    import yaml
    from perception.engine import PerceptionEngine, load_config
    cfg = load_config(a.config)
    for expr in a.set:
        key, _, val = expr.partition("=")
        d = cfg
        for part in key.split(".")[:-1]:
            d = d.setdefault(part, {})
        d[key.split(".")[-1]] = yaml.safe_load(val)
    print("[server] loading perception engine ..." + (f" (overrides {a.set})" if a.set else ""), flush=True)
    t0 = time.perf_counter()
    engine = PerceptionEngine(cfg, warmup=False)
    srv = Server(engine, a)
    print("[server] warming up the fast + slow lanes (each in its own thread / CUDA stream) ...", flush=True)
    srv.pipe.start(warmup=not a.no_lane_warmup)
    print(f"[server] engine ready in {time.perf_counter() - t0:.1f} s (lane warm-up {srv.pipe.warmup_ms} ms)",
          flush=True)
    app = create_app(srv)

    print("\n[server] Perception Engine ready (protocol v2)", flush=True)
    print(f"  mode        : {a.mode} (accepts {srv.accepted})"
          + (f"  video {a.video.name}" if a.mode == "video" else ""))
    if "sim" in srv.accepted:
        print(f"  sim clips   : {len(srv.videos)} (e.g. {', '.join(sorted(srv.videos)[:3])})")
    print(f"  local       : ws://127.0.0.1:{a.port}/perception   (GET /health, /config)")
    for ip in lan_ips():
        print(f"  LAN (Wi-Fi) : ws://{ip}:{a.port}/perception")
    print(f"  USB (adb)   : adb reverse tcp:{a.port} tcp:{a.port}   then ws://127.0.0.1:{a.port}/perception")
    if a.nav_session or a.nav_route or a.nav_destination or a.nav_live:
        print("  navigation  : " + (f"sim session {a.nav_session} (a clip with its own session uses that one)"
                                    if a.nav_session else
                                    f"live ({a.nav_provider}) route={a.nav_route} dest={a.nav_destination}")
              + ("  speed limits: OpenStreetMap" if a.speed_limits == "osm" else ""))
    elif "sim" in srv.accepted or a.mode == "video":
        print("  navigation  : from the clip's own session (data/sim_videos/<id>/) when it has one"
              + ("  speed limits: OpenStreetMap" if a.speed_limits == "osm" else ""))
    if a.tts_allow_lan:
        tts_host = a.host if a.host not in ("0.0.0.0", "::", "") else (lan_ips() or ["<laptop-LAN-IP>"])[0]
        tts_scope = "LAN clients allowed (--tts-allow-lan)"
    else:
        tts_host, tts_scope = "127.0.0.1", "loopback only (USB: adb reverse; --tts-allow-lan for Wi-Fi)"
    print(f"  TTS proxy   : http://{tts_host}:{a.port}/tts   {tts_scope}; ElevenLabs {app.state.tts.state}")
    print(flush=True)

    import uvicorn
    # per-message deflate off: each message is serialised once and sent as-is to every client.
    # proxy_headers off: uvicorn otherwise trusts X-Forwarded-For from loopback, which would spoof the /tts
    # loopback check
    uvicorn.run(app, host=a.host, port=a.port, log_level="warning", ws="websockets-sansio",
                ws_per_message_deflate=False, ws_max_size=16 * 1024 * 1024, proxy_headers=False)
    engine.close()


if __name__ == "__main__":
    main()
