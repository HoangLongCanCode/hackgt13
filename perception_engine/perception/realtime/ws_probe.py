"""ws_probe v2: a Python fake tablet for the protocol-v2 Perception server (contracts/PROTOCOL_v2.md).

AI Spatial Driving Copilot. Run from perception_engine/:

    python -m perception.realtime.ws_probe watch [--url ws://127.0.0.1:8765/perception] [--seconds 30]
    python -m perception.realtime.ws_probe sim  --video-id b1ff4656-0435391e [--seconds 30] [--rate 1.0]
    python -m perception.realtime.ws_probe live --video data/bdd100k/videos/val/b1ff4656-0435391e.mov [--seconds 30]
        [--width 960 --height 540 --quality 80] [--rotate 0|90|180|270] [--focal-px 525 --mount-height 1.3]
  common: [--log outputs/realtime/probe_x.json] [--save-samples DIR --sample-tag city] [--ping] [--quiet]
          [--no-validate] [--connect-timeout 180] [--trip-states FILE.jsonl]   (live nav: replays client.trip_state)

watch  connects without a client.hello (a watcher) and measures what the server broadcasts.
sim    sends client.hello {mode: sim} and client.playback at 10 Hz from pts 0 on its own clock, and reports how far
       AHEAD of the playback position each wave-1 result arrives (lead), the late fraction, and the tablet rule
       "newest result with pts <= playback, within 150 ms" evaluated at 30 display frames/s (coverage, staleness).
live   plays a clip as a 30 fps camera: 960x540 JPEG q80 frames with the 24-byte SDC1 header, sent only when a
       credit is free (hello uplink.maxInFlight; no credit = the frame is dropped, as CameraX KEEP_ONLY_LATEST
       would), and reports capture -> wave-1 result latency on ITS OWN clock (p50/p95), answers per frame (must be
       exactly one: a frame with a matching echo or a skip), skips by reason and result fps.
Every message is validated against contracts/schemas/*.schema.json (matched by the schema's `type` const).
navigation.packet messages are printed (routeState). --save-samples writes real golden samples from the stream.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import sys
import threading
import time
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Optional

import numpy as np

ENGINE_ROOT = Path(__file__).resolve().parents[2]            # perception_engine/
CONTRACTS = ENGINE_ROOT / "contracts"                        # perception_engine/contracts/
SCHEMAS_DIR = CONTRACTS / "schemas"


def pct(v, q) -> Optional[float]:
    return round(float(np.percentile(np.asarray(v, float), q)), 2) if len(v) else None


def p5095(v) -> dict[str, Any]:
    return {"p50": pct(v, 50), "p95": pct(v, 95), "max": round(float(max(v)), 2) if len(v) else None, "n": len(v)}


def load_validators(schemas_dir: Path = SCHEMAS_DIR) -> dict[str, Any]:
    """{message type: Draft202012Validator} from every schema whose properties.type has a const."""
    from jsonschema import Draft202012Validator
    out = {}
    for p in sorted(schemas_dir.glob("*.schema.json")):
        try:
            s = json.loads(p.read_text(encoding="utf-8"))
        except Exception as e:
            print(f"[probe] cannot read schema {p.name}: {e}", flush=True)
            continue
        t = ((s.get("properties") or {}).get("type") or {}).get("const")
        if t:
            Draft202012Validator.check_schema(s)
            out[t] = Draft202012Validator(s)
    return out


class Probe:
    def __init__(self, a: argparse.Namespace):
        self.a = a
        self.validators = {} if a.no_validate else load_validators()
        self.lines: list[str] = []
        self.counts: Counter = Counter()
        self.schema_errors: list[str] = []
        self.n_schema_errors = 0
        self.unvalidated: Counter = Counter()
        self.hellos: list[dict] = []
        self.stats: list[dict] = []
        self.errors: list[dict] = []
        self.skips: list[dict] = []
        self.pongs: list[dict] = []
        self.navs: list[dict] = []
        self.rtt_ms: list[float] = []
        self.frame_sizes: list[int] = []
        self.update_sizes: list[int] = []
        self.frame_recv: list[float] = []          # monotonic s
        self.update_recv: list[float] = []
        self.server_lat: list[float] = []          # local wall ms - serverTimeMs (same machine only)
        self.w1_proc: list[float] = []
        self.w2_proc: list[float] = []
        self.best_frame: Optional[tuple] = None
        self.best_update: Optional[tuple] = None
        self.first_frame: Optional[dict] = None
        self.frac = Counter()
        self.t0 = time.monotonic()
        self.stop = asyncio.Event()
        self.sample_extra: dict[str, dict] = {}       # client messages actually sent (golden client samples)
        self.header_example: Optional[dict] = None

    def out(self, s: str) -> None:
        self.lines.append(s)
        if not self.a.quiet:
            print(s, flush=True)

    # ---- validation + generic bookkeeping
    def validate(self, msg: dict) -> None:
        t = msg.get("type")
        v = self.validators.get(t)
        if v is None:
            self.unvalidated[t] += 1
            return
        errs = list(v.iter_errors(msg))
        if errs:
            self.n_schema_errors += 1
            if len(self.schema_errors) < 8:
                e = errs[0]
                self.schema_errors.append(f"{t}: {e.message} at {list(e.absolute_path)}")

    @staticmethod
    def frame_score(msg: dict) -> tuple:
        objs = msg["objects"]
        n_lit = sum(o.get("lightState") in ("RED", "YELLOW", "GREEN") for o in objs)
        n_fused = sum(o.get("distanceMethod") not in (None, "geometry", "size_prior")
                      and o["class"] not in ("traffic light", "traffic sign") for o in objs)
        n_dist = sum(o.get("distanceMeters") is not None for o in objs)
        lanes = msg.get("lanes") or {}
        road_ok = bool(msg.get("road") and any(x["valid"] for x in msg["road"]["anchorPoints"]))
        has_lanes = bool(lanes.get("laneCount") and len(lanes.get("laneBoundaries") or []) >= 2)
        return ((n_lit > 0) + (n_fused > 0) + has_lanes + road_ok + bool(msg.get("signs")),
                min(n_lit, 3), min(n_fused, 6), min(n_dist, 8), -abs(len(objs) - 12))

    @staticmethod
    def update_score(msg: dict) -> tuple:
        d = msg.get("distances") or []
        lanes = msg.get("lanes") or {}
        return (("distances" in msg) + ("lanes" in msg) + ("road" in msg) + bool(msg.get("signs")),
                bool(lanes.get("laneCount")), min(len(d), 8))

    def on_message(self, raw: str | bytes) -> Optional[dict]:
        import orjson
        t_mono = time.monotonic()
        t_wall = time.time() * 1000.0
        data = raw.encode() if isinstance(raw, str) else raw
        msg = orjson.loads(data)
        mtype = msg.get("type")
        self.counts[mtype] += 1
        self.validate(msg)
        if mtype == "perception.hello":
            self.hellos.append(msg)
            self.out(f"HELLO session={msg['sessionId']} mode={msg['mode']} accepted={msg.get('acceptedModes')} "
                     f"role={msg.get('role')} image={msg['image']} uplink={msg.get('uplink')} "
                     f"sim={{lookahead: {(msg.get('sim') or {}).get('lookaheadSeconds')}, "
                     f"videoId: {(msg.get('sim') or {}).get('videoId')}}} nav={msg.get('navigation')}")
        elif mtype == "perception.frame":
            if self.first_frame is None:
                self.first_frame = msg
            self.frame_recv.append(t_mono)
            self.frame_sizes.append(len(data))
            self.server_lat.append(t_wall - msg["serverTimeMs"])
            self.w1_proc.append(msg["processingMs"])
            objs = msg["objects"]
            self.frac["frames"] += 1
            self.frac["objects"] += len(objs)
            self.frac["with_distance"] += any(o.get("distanceMeters") is not None for o in objs)
            self.frac["with_light_state"] += any(o.get("lightState") in ("RED", "YELLOW", "GREEN") for o in objs)
            self.frac["with_lanes"] += msg.get("lanes") is not None
            vp = [o for o in objs if o["class"] not in ("traffic light", "traffic sign")]
            self.frac["vru_or_vehicle"] += len(vp)
            self.frac["vru_or_vehicle_with_distance"] += sum(o.get("distanceMeters") is not None for o in vp)
            for o in vp:
                self.frac["method_" + str(o.get("distanceMethod"))] += 1
            s = self.frame_score(msg)
            if self.best_frame is None or s > self.best_frame[0]:
                self.best_frame = (s, msg)
        elif mtype == "perception.update":
            self.update_recv.append(t_mono)
            self.update_sizes.append(len(data))
            self.w2_proc.append(msg["processingMs"])
            s = self.update_score(msg)
            if self.best_update is None or s > self.best_update[0]:
                self.best_update = (s, msg)
        elif mtype == "perception.stats":
            self.stats.append(msg)
            if not self.a.quiet and len(self.stats) % max(1, int(self.a.stats_every)) == 0:
                self.out(f"STATS t={t_mono - self.t0:5.1f}s fps={msg['outputFps']} w2={msg.get('wave2Fps')} "
                         f"w1={msg['wave1ProcessingMs']} w2proc={msg['wave2ProcessingMs']} in={msg['framesIn']} "
                         f"analysed={msg['framesAnalysed']} skipped={msg['framesSkipped']} "
                         f"lookahead={msg.get('lookaheadSeconds')} lead={msg.get('simLeadMs')}")
        elif mtype == "perception.skip":
            self.skips.append(msg)
        elif mtype == "perception.pong":
            self.pongs.append(msg)
            if msg.get("clientTimeNs") is not None:
                self.rtt_ms.append((time.monotonic_ns() - int(msg["clientTimeNs"])) / 1e6)
        elif mtype == "perception.error":
            self.errors.append(msg)
            self.out(f"ERROR {msg['code']}: {msg['message']}")
        elif mtype == "navigation.packet":
            self.navs.append(msg)
            rs = msg.get("routeState") or {}
            self.out(f"NAV pts={msg.get('ptsSeconds')} action={rs.get('action')} ui={rs.get('ui')} "
                     f"dist={rs.get('distanceMeters')} audio={rs.get('audio')!r}")
        return msg

    def summary(self) -> dict[str, Any]:
        n = self.frac["frames"]
        dur = (self.frame_recv[-1] - self.frame_recv[0]) if len(self.frame_recv) >= 2 else 0.0
        udur = (self.update_recv[-1] - self.update_recv[0]) if len(self.update_recv) >= 2 else 0.0
        methods = {k[len("method_"):]: v for k, v in self.frac.items() if k.startswith("method_")}
        last = self.stats[-1] if self.stats else None
        return {
            "url": self.a.url, "mode": self.a.cmd, "messages": dict(self.counts),
            "framesReceived": n, "updatesReceived": len(self.update_recv),
            "wave1Fps": round((n - 1) / dur, 2) if dur > 0 else None,
            "wave2Fps": round((len(self.update_recv) - 1) / udur, 2) if udur > 0 else None,
            "wave1ServerProcessingMs": p5095(self.w1_proc), "wave2ServerProcessingMs": p5095(self.w2_proc),
            "serverToProbeMs": p5095(self.server_lat),
            "frameBytes": p5095(self.frame_sizes), "updateBytes": p5095(self.update_sizes),
            "fractionOfFrames": {k: round(self.frac[k] / n, 3) for k in ("with_distance", "with_light_state",
                                                                         "with_lanes")} if n else {},
            "vehiclesPedestriansWithDistance": (round(self.frac["vru_or_vehicle_with_distance"]
                                                      / self.frac["vru_or_vehicle"], 4)
                                                if self.frac["vru_or_vehicle"] else None),
            "distanceMethods": methods,
            "schemaErrors": self.n_schema_errors, "schemaErrorExamples": self.schema_errors,
            "unvalidatedTypes": dict(self.unvalidated),
            "errors": [f"{e['code']}: {e['message']}" for e in self.errors][:10],
            "navigationPackets": len(self.navs), "pingRttMs": p5095(self.rtt_ms),
            "lastServerStats": last,
        }

    def save_samples(self, d: Path, tag: str, extra: dict[str, dict]) -> list[str]:
        d.mkdir(parents=True, exist_ok=True)
        written = []

        def w(name: str, obj: Optional[dict]) -> None:
            if obj is None:
                return
            (d / name).write_text(json.dumps(obj, indent=2) + "\n", encoding="utf-8")
            written.append(name)
        mode = self.hellos[-1]["mode"] if self.hellos else self.a.cmd
        good = [h for h in self.hellos if h["sessionId"] != "idle"]
        w(f"perception.hello.{mode}.json", good[-1] if good else (self.hellos[-1] if self.hellos else None))
        if tag:
            w(f"perception.frame.wave1.{tag}.json", self.best_frame[1] if self.best_frame else None)
            w(f"perception.update.wave2.{tag}.json", self.best_update[1] if self.best_update else None)
        steady = [s for s in self.stats if s.get("framesAnalysed", 0) > 0]
        w("perception.stats.json", steady[-1] if steady else None)
        w("perception.skip.json", self.skips[0] if self.skips else None)
        w("perception.pong.json", self.pongs[0] if self.pongs else None)
        w("perception.error.json", self.errors[0] if self.errors else None)
        for name, obj in extra.items():
            w(name, obj)
        return written


# ----------------------------------------------------------------------------- connect
async def connect(url: str, timeout: float):
    from websockets.asyncio.client import connect as ws_connect
    t0 = time.monotonic()
    while True:
        try:
            return await ws_connect(url, max_size=None, compression=None, open_timeout=10, ping_interval=None)
        except (OSError, asyncio.TimeoutError) as e:
            if time.monotonic() - t0 > timeout:
                raise SystemExit(f"cannot connect to {url} within {timeout:.0f} s: {e}")
            await asyncio.sleep(1.0)


async def recv_loop(ws, probe: Probe, on_msg=None) -> None:
    from websockets.exceptions import ConnectionClosed
    try:
        async for raw in ws:
            msg = probe.on_message(raw)
            if on_msg is not None and msg is not None:
                on_msg(msg)
    except ConnectionClosed:
        pass
    finally:
        probe.stop.set()


async def ping_loop(ws, probe: Probe) -> None:
    import orjson
    while not probe.stop.is_set():
        msg = {"type": "client.ping", "clientTimeNs": time.monotonic_ns()}
        probe.sample_extra.setdefault("client.ping.json", msg)
        await ws.send(orjson.dumps(msg).decode())
        await asyncio.sleep(1.0)


async def wait_first(pred, probe: Probe, timeout: float) -> bool:
    t0 = time.monotonic()
    while not pred():
        if probe.stop.is_set() or time.monotonic() - t0 > timeout:
            return False
        await asyncio.sleep(0.02)
    return True


# ----------------------------------------------------------------------------- modes
async def run_watch(a, probe: Probe) -> dict[str, Any]:
    ws = await connect(a.url, a.connect_timeout)
    async with ws:
        rx = asyncio.create_task(recv_loop(ws, probe))
        pinger = asyncio.create_task(ping_loop(ws, probe)) if a.ping else None
        if a.bad_message:
            await ws.send("{not json")
        await wait_first(lambda: probe.frac["frames"] > 0, probe, a.connect_timeout)
        t_first = time.monotonic()
        while time.monotonic() - t_first < a.seconds and not probe.stop.is_set():
            await asyncio.sleep(0.1)
        for t in (rx, pinger):
            if t:
                t.cancel()
    return {}


async def run_sim(a, probe: Probe) -> dict[str, Any]:
    import orjson
    ws = await connect(a.url, a.connect_timeout)
    arrivals: list[tuple[float, float, str]] = []       # (probe playback time s at arrival, result pts, session)
    leads: list[float] = []
    session = {"id": None, "t0": None}
    state = {"pts0": a.start}

    def playback_pts(now: float) -> Optional[float]:
        if session["t0"] is None:
            return None
        return state["pts0"] + (now - session["t0"]) * a.rate

    def on_msg(msg):
        if msg["type"] == "perception.frame" and session["t0"] is not None:
            now = time.monotonic()
            p = playback_pts(now)
            leads.append((msg["ptsSeconds"] - p) * 1000.0 / a.rate)
            arrivals.append((now - session["t0"], msg["ptsSeconds"], msg["sessionId"]))

    async with ws:
        rx = asyncio.create_task(recv_loop(ws, probe, on_msg))
        pinger = asyncio.create_task(ping_loop(ws, probe)) if a.ping else None
        hello = {"type": "client.hello", "protocolVersion": 2, "clientId": a.client_id,
                 "device": {"manufacturer": "generic", "model": "ws_probe", "osVersion": sys.platform}, "mode": "sim",
                 "camera": None, "sim": {"videoId": a.video_id}}
        probe.sample_extra["client.hello.sim.json"] = hello
        await ws.send(orjson.dumps(hello).decode())
        ok = await wait_first(lambda: any(h.get("mode") == "sim" and h.get("role") == "controller"
                                          for h in probe.hellos) or probe.errors, probe, 60)
        if not ok or probe.errors and not any(h.get("role") == "controller" for h in probe.hellos):
            rx.cancel()
            return {"error": "sim session not started", "errors": [e["message"] for e in probe.errors]}
        session["t0"] = time.monotonic()
        t_end = session["t0"] + a.seconds
        while time.monotonic() < t_end and not probe.stop.is_set():
            now = time.monotonic()
            pb = {"type": "client.playback", "videoId": a.video_id, "ptsSeconds": round(playback_pts(now), 4),
                  "playing": True, "rate": a.rate, "clientTimeNs": time.monotonic_ns()}
            if session["t0"] is not None and now - session["t0"] > 5.0:
                probe.sample_extra.setdefault("client.playback.json", pb)
            await ws.send(orjson.dumps(pb).decode())
            await asyncio.sleep(0.1)
        await asyncio.sleep(0.3)
        for t in (rx, pinger):
            if t:
                t.cancel()
    # the tablet rule: at each display frame use the newest result with pts <= playback, within 150 ms
    warm = min(2.0, a.seconds / 4)
    ticks = np.arange(warm, a.seconds, 1.0 / 30.0)
    arr = sorted(arrivals)
    covered, gaps, j, best = 0, [], 0, None
    pending: list[float] = []
    for T in ticks:
        while j < len(arr) and arr[j][0] <= T:
            pending.append(arr[j][1])
            j += 1
        p = state["pts0"] + T * a.rate
        cands = [x for x in pending if x <= p + 1e-6]
        if cands:
            best = max(cands)
            pending = [x for x in pending if x > best] + [best]
        if best is not None and p - best <= 0.150:
            covered += 1
            gaps.append((p - best) * 1000.0)
    lead_steady = leads[len(leads) // 10:] if len(leads) > 20 else leads
    return {"sim": {
        "videoId": a.video_id, "rate": a.rate,
        "leadMs": {"p5": pct(lead_steady, 5), "p50": pct(lead_steady, 50), "p95": pct(lead_steady, 95),
                   "n": len(lead_steady)},
        "lateFraction": round(sum(v < 0 for v in lead_steady) / len(lead_steady), 4) if lead_steady else None,
        "displayCoverage150ms": round(covered / len(ticks), 4) if len(ticks) else None,
        "displayStalenessMs": p5095(gaps),
        "sessions": len({s for _, _, s in arrivals}),
    }}


class CreditGate:
    def __init__(self, n: int, timeout_s: float = 2.0):
        self.n, self.timeout = max(1, n), timeout_s
        self.lock = threading.Lock()
        self.inflight: dict[int, float] = {}
        self.timeouts = 0

    def try_take(self, frame_id: int) -> bool:
        with self.lock:
            now = time.monotonic()
            for fid, t in list(self.inflight.items()):
                if now - t > self.timeout:            # a lost answer must never deadlock the uplink
                    del self.inflight[fid]
                    self.timeouts += 1
            if len(self.inflight) >= self.n:
                return False
            self.inflight[frame_id] = now
            return True

    def release(self, frame_id: int) -> bool:
        with self.lock:
            return self.inflight.pop(frame_id, None) is not None


async def run_live(a, probe: Probe) -> dict[str, Any]:
    import cv2
    import orjson
    from perception.realtime.wire import pack_uplink_header
    from perception.common.paths import resolve_data_path
    video = resolve_data_path(a.video)             # data/... follows PERCEPTION_DATA_DIR
    if not video.exists():
        raise SystemExit(f"video not found: {a.video}")
    ws = await connect(a.url, a.connect_timeout)
    loop = asyncio.get_running_loop()
    sendq: asyncio.Queue = asyncio.Queue()
    answers: defaultdict[int, list[str]] = defaultdict(list)
    sent_at: dict[int, int] = {}                   # frameId -> captureTimeNs
    lat_ms: list[float] = []
    answer_t: list[float] = []
    echo_bad = 0
    credits = CreditGate(2)
    state = {"session": None, "frames_sent": 0, "no_credit": 0, "captured": 0, "stop": False, "first_ns": None}
    sample_extra: dict[str, dict] = {}

    def on_msg(msg):
        nonlocal echo_bad
        t = msg["type"]
        if t == "perception.hello" and msg.get("role") == "controller":
            credits.n = int((msg.get("uplink") or {}).get("maxInFlight") or 2)
            state["session"] = msg["sessionId"]
        elif t == "perception.frame" and msg.get("echo"):
            fid = int(msg["echo"]["frameId"])
            if fid in sent_at:
                answers[fid].append("frame")
                if int(msg["echo"]["captureTimeNs"]) != sent_at[fid]:
                    echo_bad += 1
                now = time.monotonic_ns()
                lat_ms.append((now - sent_at[fid]) / 1e6)
                answer_t.append(time.monotonic())
            credits.release(fid)
        elif t == "perception.skip":
            fid = int(msg["frameId"])
            if fid in sent_at:
                answers[fid].append("skip:" + msg["reason"])
            credits.release(fid)

    rot = int(a.rotate)
    rot_code = {90: cv2.ROTATE_90_COUNTERCLOCKWISE, 180: cv2.ROTATE_180, 270: cv2.ROTATE_90_CLOCKWISE}.get(rot)

    def capture_thread(t_end: float) -> None:
        """A 30 fps 'camera': frames paced by the clip's own timing; sent only with a free credit."""
        cap = cv2.VideoCapture(str(video))
        cap.set(cv2.CAP_PROP_ORIENTATION_AUTO, 1)
        fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
        t0 = time.monotonic()
        fid = 0
        while not state["stop"] and time.monotonic() < t_end:
            ok, img = cap.read()
            if not ok:
                cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
                continue
            target = t0 + state["captured"] / fps
            d = target - time.monotonic()
            if d > 0:
                time.sleep(d)
            state["captured"] += 1
            cap_ns = time.monotonic_ns()           # 'sensor timestamp' of this frame on the probe clock
            fid = (fid + 1) & 0xFFFFFFFF
            if not credits.try_take(fid):
                state["no_credit"] += 1
                continue
            small = cv2.resize(img, (a.width, a.height), interpolation=cv2.INTER_AREA)
            if rot_code is not None:               # sensor-oriented buffer; rotationDegrees makes it upright
                small = cv2.rotate(small, rot_code)
            ok, jpg = cv2.imencode(".jpg", small, [cv2.IMWRITE_JPEG_QUALITY, int(a.quality)])
            if not ok:
                credits.release(fid)
                continue
            sent_at[fid] = cap_ns
            payload = pack_uplink_header(fid, cap_ns, rot) + jpg.tobytes()
            if "uplink_header.example.txt" not in sample_extra:
                sample_extra["uplink_header.example.txt"] = {"_raw": payload[:24], "_jpeg": len(payload) - 24}
            loop.call_soon_threadsafe(sendq.put_nowait, payload)
        cap.release()

    async def sender():
        while True:
            payload = await sendq.get()
            await ws.send(payload)
            state["frames_sent"] += 1

    async with ws:
        rx = asyncio.create_task(recv_loop(ws, probe, on_msg))
        tx = asyncio.create_task(sender())
        pinger = asyncio.create_task(ping_loop(ws, probe)) if a.ping else None
        cam = None
        if a.focal_px or a.mount_height:
            w, h = (a.width, a.height)
            cam = {"imageWidth": w, "imageHeight": h, "focalPx": a.focal_px,
                   "principalPoint": [w / 2.0, h / 2.0] if a.focal_px else None,
                   "mountHeightMeters": a.mount_height, "pitchDegrees": None, "lensFacing": "back",
                   "stabilization": False}
        hello = {"type": "client.hello", "protocolVersion": 2, "clientId": a.client_id,
                 "device": {"manufacturer": "generic", "model": "ws_probe", "osVersion": sys.platform},
                 "mode": "live", "camera": cam, "sim": None}
        sample_extra["client.hello.live.json"] = hello
        await ws.send(orjson.dumps(hello).decode())
        ok = await wait_first(lambda: state["session"] is not None, probe, 60)
        if not ok:
            rx.cancel()
            tx.cancel()
            return {"error": "no controller hello", "errors": [e["message"] for e in probe.errors]}
        if a.bad_header:                           # exercise perception.skip(badHeader) + perception.error
            await ws.send(b"SDC1" + b"\x07\x00" + b"\x00" * 18 + b"\xff\xd8\xff\xd9")
        trips = []
        if a.trip_states:
            trips = [json.loads(line) for line in Path(a.trip_states).read_text(encoding="utf-8").splitlines()
                     if line.strip()]
        t_end = time.monotonic() + a.seconds
        th = threading.Thread(target=capture_thread, args=(t_end,), daemon=True)
        th.start()
        k = 0
        while time.monotonic() < t_end and not probe.stop.is_set():
            if trips:
                s = dict(trips[k % len(trips)])
                s["type"] = "client.trip_state"
                await ws.send(orjson.dumps(s).decode())
                k += 1
            await asyncio.sleep(1.0)
        state["stop"] = True
        th.join(2.0)
        t_drain = time.monotonic()                  # let in-flight answers arrive
        while credits.inflight and time.monotonic() - t_drain < 3.0:
            await asyncio.sleep(0.05)
        await asyncio.sleep(0.2)
        for t in (rx, tx, pinger):
            if t:
                t.cancel()
    counts = Counter(len(v) for fid, v in answers.items())
    unanswered = [fid for fid in sent_at if fid not in answers]
    reasons = Counter(x for v in answers.values() for x in v)
    dur = (answer_t[-1] - answer_t[0]) if len(answer_t) >= 2 else 0.0
    steady = lat_ms[len(lat_ms) // 10:] if len(lat_ms) > 20 else lat_ms
    if "uplink_header.example.txt" in sample_extra:
        probe.header_example = sample_extra.pop("uplink_header.example.txt")
    probe.sample_extra.update(sample_extra)
    return {"live": {
        "video": video.name, "size": [a.width, a.height], "jpegQuality": a.quality, "rotationDegrees": rot,
        "framesCaptured": state["captured"], "framesSent": len(sent_at), "droppedNoCredit": state["no_credit"],
        "maxInFlight": credits.n, "creditTimeouts": credits.timeouts,
        "answersPerFrame": {str(k): v for k, v in sorted(counts.items())}, "unanswered": len(unanswered),
        "answerKinds": dict(reasons), "echoMismatches": echo_bad,
        "captureToResultMs": p5095(steady), "captureToResultMsAll": p5095(lat_ms),
        "resultFps": round((len(answer_t) - 1) / dur, 2) if dur > 0 else None,
    }}


def header_example_text(raw: bytes, jpeg_len: int) -> str:
    from perception.realtime.wire import describe_uplink_header
    return ("SDC1 camera-frame uplink header (contracts/PROTOCOL_v2.md): one binary WebSocket message = these 24\n"
            "bytes (little-endian, Python struct '<4sHHIqHH') followed by the JPEG. Captured from ws_probe live;\n"
            f"the JPEG that followed was {jpeg_len} bytes (960x540, quality 80).\n\n" + describe_uplink_header(raw)
            + "\n")


def main(argv: Optional[list[str]] = None) -> dict[str, Any]:
    ap = argparse.ArgumentParser(description="Fake tablet for the protocol-v2 /perception server")
    sub = ap.add_subparsers(dest="cmd", required=True)
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--url", default="ws://127.0.0.1:8765/perception")
    common.add_argument("--seconds", type=float, default=30.0)
    common.add_argument("--log", type=Path, default=None, help="write the JSON summary (+ transcript) here")
    common.add_argument("--save-samples", type=Path, default=None, help="write golden samples into this folder")
    common.add_argument("--sample-tag", default="", help="suffix for frame/update samples, e.g. city / night")
    common.add_argument("--ping", action="store_true", help="send client.ping at 1 Hz (RTT on the probe clock)")
    common.add_argument("--quiet", action="store_true")
    common.add_argument("--no-validate", action="store_true")
    common.add_argument("--stats-every", type=int, default=5, help="print every n-th perception.stats")
    common.add_argument("--connect-timeout", type=float, default=180.0, help="keep retrying while the server loads")
    common.add_argument("--client-id", default="ws-probe")
    common.add_argument("--bad-message", action="store_true", help="also send one invalid JSON text message")
    w = sub.add_parser("watch", parents=[common], help="watcher: no hello, measure the broadcast")
    del w
    s = sub.add_parser("sim", parents=[common], help="sim: hello + playback at 10 Hz from pts 0")
    s.add_argument("--video-id", default="b1ff4656-0435391e")
    s.add_argument("--rate", type=float, default=1.0)
    s.add_argument("--start", type=float, default=0.0, help="playback start pts")
    lv = sub.add_parser("live", parents=[common], help="live: SDC1 JPEG uplink under credits")
    lv.add_argument("--video", default="data/bdd100k/videos/val/b1ff4656-0435391e.mov")
    lv.add_argument("--width", type=int, default=960)
    lv.add_argument("--height", type=int, default=540)
    lv.add_argument("--quality", type=int, default=80)
    lv.add_argument("--rotate", type=int, default=0, choices=[0, 90, 180, 270])
    lv.add_argument("--focal-px", type=float, default=None, help="client.hello camera.focalPx (upright image)")
    lv.add_argument("--mount-height", type=float, default=None, help="client.hello camera.mountHeightMeters")
    lv.add_argument("--bad-header", action="store_true", help="also send one frame with a bad header")
    lv.add_argument("--trip-states", default=None, help="jsonl of phase1 trip_state samples to send at 1 Hz")
    a = ap.parse_args(argv)
    if a.cmd == "sim":
        a.client_id = a.client_id if a.client_id != "ws-probe" else "ws-probe-sim"

    probe = Probe(a)
    runner = {"watch": run_watch, "sim": run_sim, "live": run_live}[a.cmd]
    extra = asyncio.run(runner(a, probe))
    summary = {**probe.summary(), **(extra or {})}
    probe.out("")
    probe.out("SUMMARY " + json.dumps(summary, indent=2))
    if a.log:
        a.log.parent.mkdir(parents=True, exist_ok=True)
        a.log.write_text(json.dumps(summary, indent=2) + "\n\n# transcript\n" + "\n".join(probe.lines) + "\n",
                         encoding="utf-8")
    if a.save_samples:
        written = probe.save_samples(a.save_samples, a.sample_tag, dict(probe.sample_extra))
        hx = probe.header_example
        if hx:
            (a.save_samples / "uplink_header.example.txt").write_text(header_example_text(hx["_raw"], hx["_jpeg"]),
                                                                    encoding="utf-8")
            written.append("uplink_header.example.txt")
        probe.out(f"samples written to {a.save_samples}: {written}")
    return summary


if __name__ == "__main__":
    s = main()
    sys.exit(1 if s.get("schemaErrors") else 0)
