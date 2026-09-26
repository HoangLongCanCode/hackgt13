"""Stand-in for perception.realtime.nav_relay.NavRelay used by tests/test_protocol_v2.py to check the server's
navigation wiring without Node.js / phase1 (the real relay is exercised by the end-to-end stage).

AI Spatial Driving Copilot. Same interface as NavRelay; every packet is the
golden contracts/samples/v2/navigation.packet.sim_city.json sample with ptsSeconds / serverTimeMs / routeState
distance replaced, so it validates against contracts/schemas/navigation.packet.schema.json. search() returns the
places of contracts/samples/v2/navigation.places.json (a query "fail" raises, with a key in the message, to check
the server's redaction). Start the server with
    python -m perception.realtime.server --mode auto --nav-session <dir> --nav-relay-impl tests.fake_nav_relay:FakeNavRelay
"""
from __future__ import annotations

import copy
import json
import threading
import time
from pathlib import Path
from typing import Any, Optional

SAMPLES = Path(__file__).resolve().parents[1] / "contracts" / "samples" / "v2"   # perception_engine/contracts/


class FakeNavRelay:
    calls: list[tuple[str, Any]] = []          # class-level log (single process)

    def __init__(self, phase1_dir: Optional[str] = None, node_exe: str = "node"):
        self.lock = threading.Lock()
        self.mode: Optional[str] = None
        p = SAMPLES / "navigation.packet.sim_city.json"
        self.template = json.loads(p.read_text(encoding="utf-8")) if p.exists() else None
        p = SAMPLES / "navigation.places.json"
        self.places = json.loads(p.read_text(encoding="utf-8"))["places"] if p.exists() else []
        self.t_thread: Optional[str] = None

    def _thread_check(self) -> None:
        name = threading.current_thread().name
        if self.t_thread is None:
            self.t_thread = name
        assert name == self.t_thread, f"NavRelay called from {name} and {self.t_thread}"
        assert not name.startswith("MainThread") or True

    def start_sim(self, session_dir: str) -> None:
        with self.lock:
            self._thread_check()
            self.mode = "sim"
            FakeNavRelay.calls.append(("start_sim", session_dir))

    def start_live(self, route_json: Optional[str] = None, origin: Optional[str] = None,
                   destination: Optional[str] = None, provider: str = "mock",
                   destination_place: Optional[dict] = None) -> None:
        with self.lock:
            self._thread_check()
            self.mode = "live"
            if destination_place is not None:
                FakeNavRelay.calls.append(("start_live_place", destination_place))
            else:
                FakeNavRelay.calls.append(("start_live", destination or route_json))

    def search(self, query: str, near: Optional[dict] = None, provider: str = "mock") -> list[dict]:
        with self.lock:
            self._thread_check()
            FakeNavRelay.calls.append(("search", query))
            if query == "fail":
                raise RuntimeError("search failed: HTTP 500 from https://maps.googleapis.com/maps/api/geocode/json"
                                   "?address=fail&key=SECRET_TEST_KEY")
            # the relay's shape: no distanceMeters (the server adds it)
            return [{k: v for k, v in p.items() if k != "distanceMeters"} for p in copy.deepcopy(self.places)]

    def _packet(self, pts: Optional[float], trip_ms: int) -> Optional[dict]:
        if self.template is None:
            return {"type": "navigation.packet", "schemaVersion": 2, "serverTimeMs": int(time.time() * 1000),
                    "ptsSeconds": pts, "tripTimestampMs": trip_ms,
                    "routeState": {"action": "GO_STRAIGHT", "audio": "fake", "ui": "DISTANCE_LABEL"}, "packet": {}}
        m = copy.deepcopy(self.template)
        m["serverTimeMs"] = int(time.time() * 1000)
        m["ptsSeconds"] = None if pts is None else round(float(pts), 3)
        m["tripTimestampMs"] = int(trip_ms)
        return m

    def packet_at_pts(self, pts_seconds: float) -> Optional[dict]:
        with self.lock:
            self._thread_check()
            FakeNavRelay.calls.append(("packet_at_pts", pts_seconds))
            return self._packet(pts_seconds, 1_790_000_000_000 + int(1000 * pts_seconds))

    def on_trip_state(self, sample: dict) -> Optional[dict]:
        with self.lock:
            self._thread_check()
            FakeNavRelay.calls.append(("on_trip_state", sample.get("timestampMs")))
            return self._packet(None, int(sample.get("timestampMs") or 0))

    def close(self) -> None:
        FakeNavRelay.calls.append(("close", None))
