"""In-process pub/sub for PerceptionFrame dicts (no serialisation, no copies).

AI Spatial Driving Copilot. Python consumers (a Python Driving
Context prototype, a debug overlay, a logger) subscribe to the same wire dicts the WebSocket server sends:

    from perception.realtime.subscribers import PerceptionBus
    bus = PerceptionBus()
    bus.subscribe(lambda msg: print(msg["seq"], len(msg["objects"])))    # runs in the publisher thread: keep it fast
    q = bus.subscribe_queue()                                           # latest-wins queue for a consumer thread
    msg = q.get(timeout=1.0)                                            # newest frame, or None on timeout

Every subscriber receives the SAME dict object: treat it as read-only (copy.deepcopy if you must mutate).
A slow queue consumer never blocks the publisher (the inference thread): older frames are overwritten
and counted in `dropped`.

Example consumer (runs the engine in-process on a clip, no server; run from perception_engine/):
    python -m perception.realtime.subscribers --video data/bdd100k/videos/val/b1ff4656-0435391e.mov --seconds 10

The realtime server (perception/realtime/server.py) publishes every wave-1 perception.frame and wave-2
perception.update dict on its PerceptionBus (Server.bus) before serialising it; subscribe with
types=("perception.frame", "perception.update") to get both.
"""
from __future__ import annotations

import argparse
import itertools
import threading
import time
from collections import deque
from dataclasses import dataclass, field
from typing import Any, Callable, Optional

Message = dict[str, Any]


class LatestQueue:
    """Thread-safe bounded queue that keeps only the newest `maxsize` messages (default 1 = latest wins)."""

    def __init__(self, maxsize: int = 1):
        self._dq: deque = deque(maxlen=max(1, int(maxsize)))
        self._cond = threading.Condition()
        self.dropped = 0
        self.received = 0

    def put(self, msg: Message) -> None:
        with self._cond:
            if len(self._dq) == self._dq.maxlen:
                self.dropped += 1
            self._dq.append(msg)
            self.received += 1
            self._cond.notify()

    def get(self, timeout: Optional[float] = None) -> Optional[Message]:
        with self._cond:
            if not self._dq and not self._cond.wait_for(lambda: bool(self._dq), timeout=timeout):
                return None
            return self._dq.popleft()

    def __len__(self) -> int:
        return len(self._dq)


@dataclass
class Subscription:
    id: int
    types: frozenset
    callback: Optional[Callable[[Message], None]] = None
    queue: Optional[LatestQueue] = None
    name: str = ""
    errors: int = 0
    last_error: Optional[str] = None


class PerceptionBus:
    """Publish PerceptionFrame / hello / stats dicts to in-process subscribers."""

    def __init__(self):
        self._subs: dict[int, Subscription] = {}
        self._ids = itertools.count(1)
        self._lock = threading.Lock()
        self.published = 0

    def subscribe(self, callback: Callable[[Message], None], *, types=("perception.frame",),
                  name: str = "") -> Subscription:
        """Synchronous callback, called in the publisher's thread. Exceptions are caught and counted."""
        sub = Subscription(next(self._ids), frozenset(types), callback=callback, name=name)
        with self._lock:
            self._subs[sub.id] = sub
        return sub

    def subscribe_queue(self, maxsize: int = 1, *, types=("perception.frame",), name: str = "") -> LatestQueue:
        """A LatestQueue that receives every published message of `types` (oldest overwritten when full)."""
        q = LatestQueue(maxsize)
        sub = Subscription(next(self._ids), frozenset(types), queue=q, name=name)
        with self._lock:
            self._subs[sub.id] = sub
        q.subscription_id = sub.id  # type: ignore[attr-defined]
        return q

    def unsubscribe(self, sub: Subscription | LatestQueue | int) -> None:
        sid = sub if isinstance(sub, int) else getattr(sub, "id", None) or getattr(sub, "subscription_id", None)
        with self._lock:
            self._subs.pop(sid, None)

    def publish(self, msg: Message) -> None:
        self.published += 1
        mtype = msg.get("type")
        with self._lock:
            subs = list(self._subs.values())
        for s in subs:
            if mtype not in s.types:
                continue
            if s.queue is not None:
                s.queue.put(msg)
            elif s.callback is not None:
                try:
                    s.callback(msg)
                except Exception as e:  # a consumer bug must never stop inference
                    s.errors += 1
                    s.last_error = f"{type(e).__name__}: {e}"

    def __len__(self) -> int:
        return len(self._subs)


# ----------------------------------------------------------------------------- example consumer
VEHICLES = {"car", "truck", "bus", "train", "motorcycle", "bicycle"}


@dataclass
class LeadVehicleMonitor:
    """Tiny example of a Driving Context consumer (plan section 18, display-only): the nearest vehicle in the
    ego path, the most relevant traffic light, and the lane state, printed once per `every_s` seconds."""
    every_s: float = 1.0
    lines: list[str] = field(default_factory=list)
    _last: float = 0.0

    @staticmethod
    def lead(msg: Message) -> Optional[Message]:
        cands = [o for o in msg["objects"] if o["class"] in VEHICLES and o.get("inEgoPath")
                 and o.get("distanceMeters") is not None]
        return min(cands, key=lambda o: o["distanceMeters"]) if cands else None

    @staticmethod
    def main_light(msg: Message) -> Optional[Message]:
        lights = [o for o in msg["objects"] if o["class"] == "traffic light" and o.get("lightState") not in (None, "UNKNOWN")]
        return max(lights, key=lambda o: o["bbox"][3] - o["bbox"][1]) if lights else None

    def __call__(self, msg: Message) -> None:
        now = time.monotonic()
        if now - self._last < self.every_s:
            return
        self._last = now
        lead, light, lanes = self.lead(msg), self.main_light(msg), msg.get("lanes")
        parts = [f"seq {msg['seq']:5d} t={msg['ptsSeconds']:6.2f}s objs={len(msg['objects']):2d}"]
        if lead:
            ttc = f", TTC {lead['ttcSeconds']:.1f}s" if lead.get("ttcSeconds") is not None else ""
            parts.append(f"lead {lead['class']}#{lead['id']} {lead['distanceMeters']:.1f} m{ttc}")
        else:
            parts.append("lead none")
        if light:
            d = f" ~{light['distanceMeters']:.0f} m" if light.get("distanceMeters") else ""
            parts.append(f"light {light['lightState']}{d}")
        if lanes and lanes.get("laneCount"):
            parts.append(f"lane {lanes['currentLane']}/{lanes['laneCount']} (conf {lanes['confidence']:.2f})")
        if msg.get("signs"):
            parts.append("signs " + ",".join(s["signClass"] for s in msg["signs"]))
        parts.append(f"ages {msg['blockAges']}")
        line = " | ".join(parts)
        self.lines.append(line)
        print(line, flush=True)


def main() -> None:
    ap = argparse.ArgumentParser(description="Run the engine in-process and feed an example PerceptionBus consumer.")
    ap.add_argument("--video", required=True)
    ap.add_argument("--seconds", type=float, default=10.0)
    ap.add_argument("--start", type=float, default=0.0)
    ap.add_argument("--stride", type=int, default=2, help="analyse every n-th frame (2 = ~15 Hz, like realtime)")
    ap.add_argument("--config", default=None)
    a = ap.parse_args()

    from perception.common.video import VideoFileInput
    from perception.engine import PerceptionEngine
    from perception.realtime.wire import to_wire

    eng = PerceptionEngine(a.config)
    bus = PerceptionBus()
    bus.subscribe(LeadVehicleMonitor(), name="lead-monitor")
    q = bus.subscribe_queue(name="demo-queue")
    from perception.common.paths import resolve_data_path
    vin = VideoFileInput(str(resolve_data_path(a.video)), start_s=a.start, stride=a.stride)
    n_max = int(a.seconds * vin.fps / a.stride)
    for seq, fr in enumerate(vin):
        if seq >= n_max:
            break
        res = eng.step(fr)
        bus.publish(to_wire(res, {**eng.last_meta, "seq": seq, "sessionId": "inproc",
                                  "source": {"kind": "replay", "id": a.video}}))
    print(f"published {bus.published}; queue received {q.received}, dropped {q.dropped} (nobody drained it)")
    eng.close()


if __name__ == "__main__":
    main()
