"""Realtime transport for the Perception Engine (Knuckle Sandwich Robotics Inc. (KSR) AI Spatial Driving Copilot).

Protocol v2 (contracts/PROTOCOL_v2.md, contracts/schemas/):
- server.py       FastAPI + uvicorn WebSocket server ws://<host>:8765/perception; modes video / live / sim / auto
- pipeline.py     two-lane pipeline: FAST lane thread (wave 1) + SLOW lane thread (wave 2) around one engine
- wire.py         FrameResult / SlowResult -> protocol-v2 message dicts; KSR1 uplink header pack / parse
- subscribers.py  in-process pub/sub (PerceptionBus): Python consumers get the same dicts, no serialisation
- ws_probe.py     fake tablet: watch / sim / live, validates every message, measures latency and fps
- bench_lanes.py  the two-lane pipeline alone (no sockets) at real-time speed
- nav_relay.py    phase1 route-engine relay (Node child process), used by the server's nav worker

See README.md in this folder. Run everything from perception_engine/.
"""
from perception.realtime.wire import PROTOCOL_VERSION, SCHEMA_VERSION, make_hello, make_stats, to_wire  # noqa: F401
