"""netem_proxy: asyncio TCP proxy that adds one-way delay, jitter, latency spikes and an optional bandwidth cap,
to emulate the tablet <-> laptop Wi-Fi link on one machine (Windows has no `tc netem`).

AI Spatial Driving Copilot. Run from perception_engine/:

    python scripts/netem_proxy.py --listen 127.0.0.1:8766 --target 127.0.0.1:8765 --profile wifi-busy
    python -m perception.realtime.ws_probe live --url ws://127.0.0.1:8766/perception ...

It proxies raw TCP, so the WebSocket handshake and every frame pass through unchanged. Each chunk read from one
side is delivered to the other after
    base delay + jitter (half-normal, sigma = --jitter-ms) [+ spike delay while a spike is active]
    [+ serialisation time at --kbps], never earlier than the previous chunk of the same direction (TCP order).
Spikes start at random (exponential, mean --spike-every-s) and last --spike-len-ms: every chunk sent during a
spike gets --spike-ms extra (a Wi-Fi retry burst / power-save wake-up / roam). Both directions are affected
independently. Profiles (per direction; flags override): usb 0 ms; wifi-good 2 +- 1.5 ms; wifi-busy 6 +- 5 ms,
80 ms spikes every ~4 s for 150 ms; hotspot 12 +- 8 ms, 150 ms spikes every ~3 s for 300 ms, 40 Mbit/s.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import random
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Optional

PROFILES = {
    "usb": dict(delay_ms=0.0, jitter_ms=0.0, spike_ms=0.0, spike_every_s=0.0, spike_len_ms=0.0, kbps=0.0),
    "wifi-good": dict(delay_ms=2.0, jitter_ms=1.5, spike_ms=0.0, spike_every_s=0.0, spike_len_ms=0.0, kbps=0.0),
    "wifi-busy": dict(delay_ms=6.0, jitter_ms=5.0, spike_ms=80.0, spike_every_s=4.0, spike_len_ms=150.0, kbps=0.0),
    "hotspot": dict(delay_ms=12.0, jitter_ms=8.0, spike_ms=150.0, spike_every_s=3.0, spike_len_ms=300.0,
                    kbps=40000.0),
}


@dataclass
class LinkParams:
    delay_ms: float = 0.0
    jitter_ms: float = 0.0
    spike_ms: float = 0.0
    spike_every_s: float = 0.0
    spike_len_ms: float = 0.0
    kbps: float = 0.0


class Direction:
    """Delay model + counters of one direction (client->server or server->client), shared by all connections."""

    def __init__(self, name: str, p: LinkParams, rng: random.Random):
        self.name, self.p, self.rng = name, p, rng
        self.spike_until = 0.0
        self.next_spike = time.monotonic() + self._gap()
        self.bytes = 0
        self.chunks = 0
        self.added_ms: list[float] = []
        self.spikes = 0

    def _gap(self) -> float:
        return self.rng.expovariate(1.0 / self.p.spike_every_s) if self.p.spike_every_s > 0 else float("inf")

    def delay_s(self, nbytes: int) -> float:
        now = time.monotonic()
        if now >= self.next_spike:
            self.spike_until = now + self.p.spike_len_ms / 1000.0
            self.next_spike = now + self._gap()
            self.spikes += 1
        d = self.p.delay_ms + abs(self.rng.gauss(0.0, self.p.jitter_ms)) if self.p.jitter_ms > 0 else self.p.delay_ms
        if now < self.spike_until:
            d += self.p.spike_ms
        if self.p.kbps > 0:
            d += nbytes * 8.0 / self.p.kbps               # bits / (kbit/s) = ms
        self.bytes += nbytes
        self.chunks += 1
        self.added_ms.append(d)
        return d / 1000.0

    def summary(self) -> dict:
        import statistics
        v = sorted(self.added_ms)
        return {"direction": self.name, "bytes": self.bytes, "chunks": self.chunks, "spikes": self.spikes,
                "addedMsMean": round(statistics.fmean(v), 2) if v else None,
                "addedMsP95": round(v[int(0.95 * (len(v) - 1))], 2) if v else None,
                "addedMsMax": round(v[-1], 2) if v else None}


async def pump(reader: asyncio.StreamReader, writer: asyncio.StreamWriter, d: Direction) -> None:
    q: asyncio.Queue = asyncio.Queue()
    last_due = 0.0

    async def deliver():
        while True:
            due, data = await q.get()
            if data is None:
                break
            wait = due - time.monotonic()
            if wait > 0:
                await asyncio.sleep(wait)
            writer.write(data)
            await writer.drain()

    task = asyncio.create_task(deliver())
    try:
        while True:
            data = await reader.read(65536)
            if not data:
                break
            due = max(last_due, time.monotonic() + d.delay_s(len(data)))
            last_due = due
            q.put_nowait((due, data))
    except (ConnectionError, OSError):
        pass
    finally:
        q.put_nowait((0.0, None))
        try:
            await task
        except (ConnectionError, OSError):
            pass
        try:
            writer.close()
        except Exception:
            pass


def main(argv: Optional[list[str]] = None) -> None:
    ap = argparse.ArgumentParser(description="TCP proxy adding delay / jitter / spikes (Wi-Fi emulation)")
    ap.add_argument("--listen", default="127.0.0.1:8766")
    ap.add_argument("--target", default="127.0.0.1:8765")
    ap.add_argument("--profile", choices=sorted(PROFILES), default="wifi-busy")
    for k in LinkParams.__dataclass_fields__:
        ap.add_argument("--" + k.replace("_", "-"), type=float, default=None, help=f"override the profile's {k}")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--seconds", type=float, default=0.0, help="exit after this long (0 = run until Ctrl+C)")
    ap.add_argument("--log", type=Path, default=None, help="write per-direction delay stats (JSON) on exit")
    a = ap.parse_args(argv)
    p = LinkParams(**PROFILES[a.profile])
    for k in LinkParams.__dataclass_fields__:
        v = getattr(a, k)
        if v is not None:
            setattr(p, k, v)
    rng = random.Random(a.seed)
    up, down = Direction("client->server", p, rng), Direction("server->client", p, rng)
    lh, lp = a.listen.rsplit(":", 1)
    th, tp = a.target.rsplit(":", 1)

    async def handle(creader, cwriter):
        try:
            sreader, swriter = await asyncio.open_connection(th, int(tp))
        except OSError as e:
            print(f"[netem] cannot reach {a.target}: {e}", flush=True)
            cwriter.close()
            return
        for w in (cwriter, swriter):
            sock = w.get_extra_info("socket")
            if sock is not None:
                import socket
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        await asyncio.gather(pump(creader, swriter, up), pump(sreader, cwriter, down))

    async def run():
        server = await asyncio.start_server(handle, lh, int(lp))
        print(f"[netem] {a.listen} -> {a.target}  profile {a.profile}: {asdict(p)}", flush=True)
        async with server:
            if a.seconds > 0:
                await asyncio.sleep(a.seconds)
            else:
                await server.serve_forever()

    try:
        asyncio.run(run())
    except KeyboardInterrupt:
        pass
    finally:
        s = {"profile": a.profile, "params": asdict(p), "up": up.summary(), "down": down.summary()}
        print("[netem] " + json.dumps(s), flush=True)
        if a.log:
            a.log.parent.mkdir(parents=True, exist_ok=True)
            a.log.write_text(json.dumps(s, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
