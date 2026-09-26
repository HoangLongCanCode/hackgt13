"""Glasses listener: ws://<host>:8000/ws for the glasses app (FastAPI + uvicorn).

AI Spatial Driving Copilot, HackGT 13 prototype. A second listener next to the protocol-v2 server
(perception.realtime.server, ws://<host>:8765/perception), which it neither changes nor replaces. The glasses app
(a separate Kotlin CameraX client) sends one JSON camera frame per text message and draws the Spatial Instruction
document it gets back; perception/realtime/glasses_wire.py has both formats. Run from perception_engine/:

    python -m perception.realtime.glasses_server [--host 0.0.0.0] [--port 8000]
                 [--config perception/config_realtime.yaml] [--set KEY=VALUE ...] [--no-warmup]

Phone URL: emulator ws://10.0.2.2:8000/ws; same Wi-Fi ws://<laptop LAN IP>:8000/ws (printed at start-up; allow
inbound TCP 8000 in Windows Firewall); USB `adb reverse tcp:8000 tcp:8000`, then ws://127.0.0.1:8000/ws.
GET /health -> {"status": "ok", "engine": "perception"} once the engine is ready ("loading" before, "error" if
it failed); GET /stats -> counters and step times.

Transport (what the phone implements): text frames only, no handshake (the first message is a camera frame), about
8 frames/s without waiting for replies, a websocket ping every 15 s, cleartext ws://. A frame gets one reply: the
document, or {"error": reason} (bad frame, engine not ready, engine busy) after which the socket keeps reading. A
frame that a newer one supersedes before the engine gets to it is dropped with no reply (latest wins per
connection), so the overlay never lags behind the camera. Nothing of protocol v2 (hello, skip, credits, waves) is
sent here.

Threads: the asyncio loop (sockets, JSON, base64 + JPEG decode in the default thread pool) and ONE engine thread
that loads the Perception Engine with the v2 server's config, warms it up and then runs every serial engine.step()
under a lock (never fast_step / slow_step). The engine's temporal state (tracks, lane smoothing, depth Kalman)
belongs to one connection at a time: while it streams, other connections get {"error": "engine busy with another
client"}; it hands over when that connection closes or sends nothing for STREAM_GAP_S. A new owner, a jump in
timestampMs or a change of JPEG size starts a new stream (engine.reset()). Running this and the v2 server at the
same time loads the engine twice (two processes; about 1.7 GB of GPU memory together, measured).
"""
from __future__ import annotations

import argparse
import asyncio
import contextlib
import itertools
import os
import queue
import socket
import sys
import threading
import time
import traceback
from collections import deque
from concurrent.futures import Future
from typing import Any, Callable, Optional

os.environ.setdefault("YOLO_AUTOINSTALL", "False")

import numpy as np  # noqa: E402
from fastapi import WebSocket, WebSocketDisconnect  # noqa: E402

from perception.realtime.glasses_wire import (  # noqa: E402
    FrameError, InboundFrame, build_document, decode_frame, dumps, error_doc)
from perception.realtime.server import abort_transport, lan_ips  # noqa: E402

HEALTH_ENGINE = "perception"             # GET /health "engine"
SEND_TIMEOUT_S = 10.0             # a reply that cannot be sent this long (peer stopped reading) closes the socket
STREAM_GAP_S = 2.0                # timestampMs jump (forward) that starts a new stream (engine.reset())
STREAM_BACKSTEP_S = 0.5           # timestampMs going back more than this starts a new stream too
LOG_EVERY_S = 10.0                # console stats line while frames arrive
BUSY_REASON = "engine busy with another client"


class EngineBusy(RuntimeError):
    """Another connection owns the engine stream."""


def primary_ip() -> Optional[str]:
    """The address of the interface with the default route (UDP connect sends nothing)."""
    with contextlib.suppress(OSError):
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 9))       # TEST-NET-1: never routed anywhere, only picks the interface
            ip = s.getsockname()[0]
            if ip and not ip.startswith(("127.", "0.")):
                return ip
    return None


def _pct(v, q: float) -> Optional[float]:
    return round(float(np.percentile(np.asarray(v, float), q)), 1) if len(v) else None


# ----------------------------------------------------------------------------- engine thread
class EngineThread(threading.Thread):
    """Owns the PerceptionEngine: loads and warms it up in this thread (cuDNN / onnxruntime caches are per thread),
    then runs submitted calls one at a time. Daemon, so Ctrl+C during the 20-60 s load does not wait for it."""

    def __init__(self, cfg: dict[str, Any], warmup: bool):
        super().__init__(name="glasses-engine", daemon=True)
        self.cfg, self.do_warmup = cfg, warmup
        self.q: "queue.SimpleQueue[tuple[Callable, tuple, Future]]" = queue.SimpleQueue()
        self.engine: Any = None
        self.state = "loading"                 # loading | ok | error
        self.error: Optional[str] = None
        self.ready_evt = threading.Event()
        self.load_s: Optional[float] = None
        self.lock = threading.Lock()           # held around engine.step() (and the stream bookkeeping)
        self.own_lock = threading.Lock()       # owner / owner_seen (short; the loop thread releases ownership)
        self.owner: Optional[int] = None       # connection whose stream the engine's temporal state belongs to
        self.owner_seen = 0.0                  # time.monotonic() of the owner's last analysed frame
        self.last_pts: Optional[float] = None
        self.last_wh: Optional[tuple[int, int]] = None
        self.streams = 0
        self.step_ms: deque[float] = deque(maxlen=400)

    def submit(self, fn: Callable, *args) -> Future:
        fut: Future = Future()
        self.q.put((fn, args, fut))
        return fut

    def run(self) -> None:
        t0 = time.perf_counter()
        try:
            from perception.engine import PerceptionEngine
            self.engine = PerceptionEngine(self.cfg, warmup=False)
            if self.do_warmup:
                self.engine.warmup()           # serial step() warm-up, in the thread that will run step()
            self.load_s = round(time.perf_counter() - t0, 1)
            self.state = "ok"
        except BaseException as e:             # keep serving: frames get {"error": ...}, /health says "error"
            self.state, self.error = "error", f"{type(e).__name__}: {e}"
            traceback.print_exc()
        self.ready_evt.set()
        while True:
            fn, args, fut = self.q.get()
            if not fut.set_running_or_notify_cancel():
                continue
            try:
                fut.set_result(fn(*args))
            except BaseException as e:
                fut.set_exception(e)

    def release(self, conn_key: int) -> None:
        """LOOP THREAD: the connection closed; the next frame from anyone starts a new stream."""
        with self.own_lock:
            if self.owner == conn_key:
                self.owner = None

    def analyse(self, conn_key: int, frame_id: int, timestamp_ms: int, image: np.ndarray) -> dict[str, Any]:
        """ENGINE THREAD: one serial engine.step() on the decoded JPEG -> Spatial Instruction document.
        Raises EngineBusy while another connection streams (interleaving two streams would reset the engine on every
        step, and lanes and signs never run on the first step of a stream)."""
        from perception.common.video import Frame
        pts = timestamp_ms / 1000.0
        wh = (int(image.shape[1]), int(image.shape[0]))
        now = time.monotonic()
        with self.own_lock:
            if self.owner is not None and conn_key != self.owner and now - self.owner_seen <= STREAM_GAP_S:
                raise EngineBusy(BUSY_REASON)
            new_owner = conn_key != self.owner
            self.owner, self.owner_seen = conn_key, now
        with self.lock:
            eng = self.engine
            if (new_owner or self.last_pts is None or wh != self.last_wh
                    or not -STREAM_BACKSTEP_S <= pts - self.last_pts <= STREAM_GAP_S):
                eng.reset()                    # new stream: forget tracks, lane smoothing, depth Kalman
                self.streams += 1
            self.last_pts, self.last_wh = pts, wh
            t0 = time.perf_counter()
            try:
                result = eng.step(Frame(index=frame_id, pts_s=pts, image=image))
            except BaseException:
                self.last_pts = None           # state may be half-updated: reset before the next frame
                raise
            self.step_ms.append((time.perf_counter() - t0) * 1000.0)
            fitted = dict(eng.last_meta["image"])
        return build_document(result, fitted, (image.shape[1], image.shape[0]), frame_id, timestamp_ms)


# ----------------------------------------------------------------------------- connections
class Conn:
    """One phone socket: a 1-slot latest-wins inbox between the receive loop and the analyse loop."""
    _ids = itertools.count(1)

    def __init__(self, ws: Any, peer: str):
        self.ws = ws
        self.key = next(self._ids)
        self.peer = peer
        self.pending: Optional[InboundFrame] = None
        self.wake = asyncio.Event()
        self.send_lock = asyncio.Lock()
        self.auto_ids = itertools.count(1)     # frameId for frames that carry none
        self.frames_in = self.analysed = self.dropped = self.errors = self.sent = 0
        self.connected_at = time.time()


class GlassesServer:
    def __init__(self, cfg: dict[str, Any], warmup: bool):
        self.worker = EngineThread(cfg, warmup)
        self.conns: dict[int, Conn] = {}
        self.totals = {"framesIn": 0, "analysed": 0, "dropped": 0, "errors": 0, "busy": 0, "connections": 0}
        self.started = time.time()

    def health(self) -> dict[str, Any]:
        return {"status": self.worker.state, "engine": HEALTH_ENGINE}

    def stats(self) -> dict[str, Any]:
        w = self.worker
        ms = list(w.step_ms)
        return {**self.health(), "error": w.error, "loadSeconds": w.load_s,
                "uptimeSeconds": round(time.time() - self.started, 1), "streams": w.streams,
                "stepMs": {"p50": _pct(ms, 50), "p95": _pct(ms, 95)}, **self.totals,
                "clients": [{"key": c.key, "peer": c.peer, "framesIn": c.frames_in, "analysed": c.analysed,
                             "dropped": c.dropped, "errors": c.errors} for c in list(self.conns.values())]}

    async def send(self, c: Conn, doc: dict[str, Any]) -> None:
        text = dumps(doc)
        async with c.send_lock:
            await asyncio.wait_for(c.ws.send_text(text), SEND_TIMEOUT_S)
        c.sent += 1

    async def reply_error(self, c: Conn, reason: str) -> None:
        c.errors += 1
        self.totals["errors"] += 1
        await self.send(c, error_doc(reason))

    async def receive_loop(self, c: Conn) -> None:
        while True:
            msg = await c.ws.receive()
            if msg["type"] == "websocket.disconnect":
                return
            text = msg.get("text")
            if text is None:                               # the phone never sends binary
                await self.reply_error(c, "expected a JSON text frame")
                continue
            c.frames_in += 1
            self.totals["framesIn"] += 1
            try:
                frame = await asyncio.to_thread(decode_frame, text)
            except FrameError as e:
                await self.reply_error(c, e.reason)
                continue
            except Exception:                              # a decoder bug must not cost the phone its socket
                traceback.print_exc()
                await self.reply_error(c, "data is not a jpeg image")
                continue
            if self.worker.state != "ok":
                await self.reply_error(c, "engine is loading" if self.worker.state == "loading"
                                       else "engine failed to load")
                continue
            if frame.frame_id is None:
                frame.frame_id = next(c.auto_ids)
            if frame.timestamp_ms is None:
                frame.timestamp_ms = int(time.time() * 1000)
            if c.pending is not None:                      # superseded before the engine took it: no reply
                c.dropped += 1
                self.totals["dropped"] += 1
            c.pending = frame
            c.wake.set()

    async def analyse_loop(self, c: Conn) -> None:
        while True:
            await c.wake.wait()
            c.wake.clear()
            frame, c.pending = c.pending, None
            if frame is None:
                continue
            try:
                doc = await asyncio.wrap_future(self.worker.submit(
                    self.worker.analyse, c.key, frame.frame_id, frame.timestamp_ms, frame.image))
                c.analysed += 1
                self.totals["analysed"] += 1
            except EngineBusy:
                c.errors += 1
                self.totals["busy"] += 1
                doc = error_doc(BUSY_REASON)
            except Exception as e:
                traceback.print_exc()
                c.errors += 1
                self.totals["errors"] += 1
                doc = error_doc(f"analysis failed: {type(e).__name__}")
            await self.send(c, doc)

    async def log_loop(self) -> None:
        last = dict(self.totals)
        while True:
            await asyncio.sleep(LOG_EVERY_S)
            cur = dict(self.totals)
            n_in = cur["framesIn"] - last["framesIn"]
            if n_in:
                ms = list(self.worker.step_ms)[-100:]
                print(f"[glasses] {len(self.conns)} client(s): in {n_in / LOG_EVERY_S:.1f}/s, analysed "
                      f"{(cur['analysed'] - last['analysed']) / LOG_EVERY_S:.1f}/s, dropped "
                      f"{cur['dropped'] - last['dropped']}, errors {cur['errors'] - last['errors']}, step p50 "
                      f"{_pct(ms, 50)} ms p95 {_pct(ms, 95)} ms", flush=True)
            last = cur


def create_app(srv: GlassesServer, banner: Callable[[], None]):
    # WebSocket must be a module-level name: with `from __future__ import annotations` FastAPI resolves the
    # endpoint's annotations from the module globals (a local import turns `ws` into a query param -> HTTP 403)
    from fastapi import FastAPI

    @contextlib.asynccontextmanager
    async def lifespan(app):
        srv.worker.start()
        log_task = asyncio.create_task(srv.log_loop())
        ready_task = asyncio.create_task(announce_ready())
        try:
            yield
        finally:
            log_task.cancel()
            ready_task.cancel()

    async def announce_ready():
        while not srv.worker.ready_evt.is_set():          # polled: a thread parked on the Event would block Ctrl+C
            await asyncio.sleep(0.25)
        if srv.worker.state == "ok":
            print(f"\n[glasses] engine ready in {srv.worker.load_s:.1f} s", flush=True)
            banner()
        else:
            print(f"\n[glasses] ENGINE FAILED TO LOAD: {srv.worker.error} (frames get an error reply)", flush=True)

    app = FastAPI(title="Perception Engine (glasses socket)", lifespan=lifespan)

    @app.get("/health")
    async def health():
        return srv.health()

    @app.get("/stats")
    async def stats():
        return srv.stats()

    @app.websocket("/ws")
    async def glasses(ws: WebSocket):
        await ws.accept()
        c = Conn(ws, f"{ws.client.host}:{ws.client.port}" if ws.client else "?")
        srv.conns[c.key] = c
        srv.totals["connections"] += 1
        print(f"[glasses] client {c.key} connected from {c.peer}", flush=True)
        worker = asyncio.create_task(srv.analyse_loop(c))
        receiver = asyncio.create_task(srv.receive_loop(c))
        try:
            done, _ = await asyncio.wait({worker, receiver}, return_when=asyncio.FIRST_COMPLETED)
            for t in done:
                exc = t.exception()
                if isinstance(exc, TimeoutError):          # a reply stalled: the peer stopped reading
                    print(f"[glasses] client {c.key}: a send did not complete in {SEND_TIMEOUT_S:.0f} s, "
                          f"closing", flush=True)
                    abort_transport(ws)
                elif exc is not None and not isinstance(exc, (WebSocketDisconnect, RuntimeError)):
                    traceback.print_exception(exc)
        finally:
            worker.cancel()
            receiver.cancel()
            srv.conns.pop(c.key, None)
            srv.worker.release(c.key)
            print(f"[glasses] client {c.key} disconnected (in {c.frames_in}, analysed {c.analysed}, dropped "
                  f"{c.dropped}, errors {c.errors})", flush=True)

    return app


# ----------------------------------------------------------------------------- CLI
def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(description="Glasses socket: JSON jpeg-base64 frames in, Spatial Instruction "
                                             "documents out (ws://<host>:<port>/ws). Run from perception_engine/.")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8000)
    ap.add_argument("--config", default="perception/config_realtime.yaml")
    ap.add_argument("--set", action="append", default=[], metavar="KEY=VALUE",
                    help="config override (YAML value), same as the v2 server's --set")
    ap.add_argument("--no-warmup", action="store_true", help="skip the engine warm-up (first frames slow)")
    return ap


def main(argv: Optional[list[str]] = None) -> None:
    a = build_parser().parse_args(argv)
    import yaml
    from perception.engine import load_config
    cfg = load_config(a.config)
    for expr in a.set:
        key, _, val = expr.partition("=")
        d = cfg
        for part in key.split(".")[:-1]:
            d = d.setdefault(part, {})
        d[key.split(".")[-1]] = yaml.safe_load(val)
    srv = GlassesServer(cfg, warmup=bool(cfg.get("warmup", True)) and not a.no_warmup)

    first = primary_ip()
    ips = ([first] if first else []) + [ip for ip in lan_ips() if ip != first]
    loopback = a.host in ("127.0.0.1", "localhost", "::1")

    def banner() -> None:
        print(f"[glasses] glasses socket on {a.host}:{a.port} (text frames, jpeg-base64 in, Spatial Instructions out)")
        print(f"  local       : ws://127.0.0.1:{a.port}/ws   (GET /health, /stats)")
        if loopback:
            print(f"  LAN (Wi-Fi) : not reachable, --host {a.host} listens on loopback only (use --host 0.0.0.0)")
        else:
            for i, ip in enumerate(ips):
                print(f"  {'LAN (Wi-Fi)' if i == 0 else 'other IP':<12}: ws://{ip}:{a.port}/ws")
            if not ips:
                print("  LAN (Wi-Fi) : no LAN address found (is Wi-Fi connected?)")
            print(f"  emulator    : ws://10.0.2.2:{a.port}/ws")
        print(f"  USB (adb)   : adb reverse tcp:{a.port} tcp:{a.port}   then ws://127.0.0.1:{a.port}/ws")
        if sys.platform == "win32":
            print(f"  firewall    : allow inbound TCP {a.port} (admin PowerShell, once): New-NetFirewallRule "
                  f"-DisplayName \"Glasses socket {a.port}\" -Direction Inbound -Protocol TCP -LocalPort {a.port} "
                  f"-Action Allow -Profile Private,Public")
            print("                (campus Wi-Fi such as eduroam is a Public network and may block phone-to-laptop "
                  "traffic: use USB then)")
        print(flush=True)

    # bind before loading anything: a busy port fails now, not after a 20-60 s engine load. No SO_REUSEADDR, and
    # SO_EXCLUSIVEADDRUSE on Windows, so a second server cannot share (or take over) the port.
    sock = socket.socket(socket.AF_INET6 if ":" in a.host else socket.AF_INET, socket.SOCK_STREAM)
    try:
        if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        sock.bind((a.host, a.port))
        sock.listen(128)
    except OSError as e:
        sock.close()
        sys.exit(f"[glasses] cannot listen on {a.host}:{a.port}: {e} (is another glasses server running?)")

    print("[glasses] loading the perception engine in the background (frames get {\"error\": \"engine is loading\"} "
          "until it is ready) ..." + (f" (overrides {a.set})" if a.set else ""), flush=True)
    banner()
    app = create_app(srv, banner)

    import uvicorn
    # per-message deflate off (base64 JPEGs barely compress); uvicorn answers websocket pings itself
    uvicorn.Server(uvicorn.Config(app, host=a.host, port=a.port, log_level="warning", ws="websockets-sansio",
                                  ws_per_message_deflate=False, ws_max_size=32 * 1024 * 1024)).run(sockets=[sock])
    if srv.worker.state == "ok":                       # close in the thread that owns the models
        with contextlib.suppress(Exception):
            srv.worker.submit(srv.worker.engine.close).result(timeout=10)


if __name__ == "__main__":
    main()
