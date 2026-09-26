"""Measure the two-lane pipeline ALONE (no WebSocket): a clip is released at real-time speed into the pipeline,
and wave-1 / wave-2 latency and rates are recorded. Optionally the old serial engine.step schedule is measured on
the same clip for comparison. Run from perception_engine/:

    python -m perception.realtime.bench_lanes --video data/bdd100k/videos/val/b1ff4656-0435391e.mov --seconds 30
        [--serial] [--set slow_schedule.signs.every=3 --set fast.max_hz=20] [--log outputs/realtime/bench.json]
        [--variant '{"slow_max_hz": 5}' --variant '{"slow_schedule": {"depth": {"every": 2}}}']

processing = frame released by the source -> wave message built (includes the 1-slot queue wait);
compute = the lane's own model time. distanceAgeMsInWave1 ignores the fast lane's geometry-only distances (age 0),
geometryDistanceFraction counts them. AI Spatial Driving Copilot.
"""
from __future__ import annotations

import argparse
import json
import threading
import time
from collections import defaultdict
from pathlib import Path
from typing import Any, Optional

import numpy as np



def pct(v, q) -> Optional[float]:
    return round(float(np.percentile(np.asarray(v, float), q)), 2) if len(v) else None


def p5095(v) -> dict[str, Optional[float]]:
    return {"p50": pct(v, 50), "p95": pct(v, 95), "n": len(v)}


def set_override(cfg: dict, expr: str) -> None:
    import yaml
    key, val = expr.split("=", 1)
    parts = key.split(".")
    d = cfg
    for p in parts[:-1]:
        d = d.setdefault(p, {})
    d[parts[-1]] = yaml.safe_load(val)


class LoadMonitor(threading.Thread):
    """Samples system CPU % (psutil) and GPU utilisation (nvidia-smi) during a run, so numbers taken while other
    work shared the laptop can be recognised."""

    def __init__(self, period: float = 1.0):
        super().__init__(daemon=True)
        self.period, self.cpu, self.gpu = period, [], []
        self.stop_evt = threading.Event()

    def run(self) -> None:
        import subprocess
        try:
            import psutil
            psutil.cpu_percent(None)
        except Exception:
            psutil = None
        while not self.stop_evt.wait(self.period):
            if psutil is not None:
                self.cpu.append(psutil.cpu_percent(None))
            try:
                out = subprocess.run(["nvidia-smi", "--query-gpu=utilization.gpu", "--format=csv,noheader,nounits"],
                                     capture_output=True, text=True, timeout=2).stdout.strip()
                self.gpu.append(float(out.splitlines()[0]))
            except Exception:
                pass

    def summary(self) -> dict[str, Any]:
        return {"cpuPercent": p5095(self.cpu), "gpuUtilPercent": p5095(self.gpu)}


class RealtimeClip(threading.Thread):
    """Releases clip frames at wall-clock pts into `sink(item)`."""

    def __init__(self, path: Path, seconds: float, sink, start_s: float = 0.0):
        super().__init__(daemon=True)
        self.path, self.seconds, self.sink, self.start_s = path, seconds, sink, start_s
        self.stop_evt = threading.Event()
        self.frames = 0

    def run(self) -> None:
        from perception.common.video import VideoFileInput
        from perception.realtime.pipeline import Item
        vin = VideoFileInput(self.path, start_s=self.start_s)
        t0 = None
        pts0 = None
        last = None
        first = True
        for fr in vin:
            if self.stop_evt.is_set():
                break
            pts = fr.pts_s if last is None else max(fr.pts_s, last + 1e-3)
            last = pts
            if t0 is None:
                t0, pts0 = time.perf_counter(), pts
            if pts - pts0 > self.seconds:
                break
            target = t0 + (pts - pts0)
            now = time.perf_counter()
            if target > now:
                time.sleep(target - now)
            self.sink(Item(seq=self.frames, index=fr.index, pts_s=pts, t_grab=time.perf_counter(),
                           session_id="bench", source={"kind": "video", "id": self.path.stem}, image=fr.image,
                           reset=first, new_session=first))
            first = False
            self.frames += 1
        vin.release()


class Recorder:
    """Pipeline callbacks that build + serialise the real wire messages and record timings."""

    def __init__(self):
        self.clear()

    def clear(self):
        self.rec: dict[str, list] = defaultdict(list)
        self.fast_t: dict[str, list] = defaultdict(list)
        self.slow_t: dict[str, list] = defaultdict(list)
        self.ages: list[float] = []
        self.active = True

    def on_wave1(self, item, result, meta, timings):
        from perception.realtime.wire import dumps, to_wire
        msg = to_wire(result, {**meta, "seq": item.seq, "sessionId": "bench", "source": item.source,
                               "timingsMs": timings})
        dumps(msg)
        if not self.active:
            return
        self.rec["w1_proc"].append((time.perf_counter() - item.t_grab) * 1000.0)
        self.rec["w1_t"].append(time.perf_counter())
        self.rec["w1_compute"].append(timings["fastLane"])
        for k, v in result.timingsMs.items():
            self.fast_t[k].append(v)
        self.fast_t["queueWait"].append(timings["queueWait"])
        for o in msg["objects"]:
            if o["class"] in ("traffic light", "traffic sign"):
                continue
            self.rec["objects"].append(1)
            if o.get("distanceMethod") == "geometry":        # fast-lane fallback (age 0): counted separately
                self.rec["geometry"].append(1)
            elif o.get("distanceAgeMs") is not None:
                self.ages.append(o["distanceAgeMs"])

    def on_wave2(self, item, out):
        from perception.realtime.wire import dumps, make_update
        msg = make_update(out, {"seq": item.seq, "sessionId": "bench", "frameIndex": item.index,
                                "ptsSeconds": item.pts_s, "image": {"width": out.snapshot.image.shape[1],
                                                                    "height": out.snapshot.image.shape[0]},
                                "camera": out.camera, "processingMs": (time.perf_counter() - item.t_grab) * 1000})
        dumps(msg)
        if not self.active:
            return
        now = time.perf_counter()
        self.rec["w2_proc"].append((now - item.t_grab) * 1000.0)
        self.rec["w2_t"].append(now)
        self.rec["w2_compute"].append(out.timings.get("total", 0.0))
        if "distance" in out.ran:
            self.rec["dist_t"].append(now)
        if "depth" in out.ran:
            self.rec["depth_t"].append(now)
        for k, v in out.timings.items():
            self.slow_t[k].append(v)


def run_two_lane(engine, pipe, recorder: Recorder, video: Path, seconds: float,
                 variant: dict[str, Any]) -> dict[str, Any]:
    """One measurement on an already started (warm) pipeline. variant: fast_max_hz, slow_max_hz, slow_schedule."""
    pipe.fast_max_hz = variant.get("fast_max_hz")
    pipe.slow_max_hz = variant.get("slow_max_hz")
    if variant.get("slow_schedule"):
        engine.configure_slow_schedule(variant["slow_schedule"])
    recorder.clear()
    sup0, runs0 = pipe.inbox.superseded, pipe.slow_runs
    src = RealtimeClip(video, seconds, pipe.submit)
    mon = LoadMonitor()
    mon.start()
    t0 = time.perf_counter()
    src.start()
    src.join()
    time.sleep(0.4)
    recorder.active = False
    mon.stop_evt.set()
    dur = time.perf_counter() - t0
    time.sleep(0.4)                       # let both lanes drain before the next variant
    rec = recorder.rec

    def hz(ts):
        return round((len(ts) - 1) / (ts[-1] - ts[0]), 2) if len(ts) >= 2 else None
    return {
        "mode": "two_lane",
        "variant": variant,
        "sourceFrames": src.frames,
        "wallSeconds": round(dur, 1),
        "wave1Hz": hz(rec["w1_t"]),
        "wave2Hz": hz(rec["w2_t"]),
        "distanceHz": hz(rec["dist_t"]),
        "depthNetHz": hz(rec["depth_t"]),
        "wave1ProcessingMs": p5095(rec["w1_proc"]),
        "wave1ComputeMs": p5095(rec["w1_compute"]),
        "wave2ProcessingMs": p5095(rec["w2_proc"]),
        "wave2ComputeMs": p5095(rec["w2_compute"]),
        "distanceAgeMsInWave1": p5095(recorder.ages),
        "geometryDistanceFraction": (round(len(rec["geometry"]) / len(rec["objects"]), 3)
                                     if rec["objects"] else None),
        "fastBlocksMs": {k: p5095(v) for k, v in sorted(recorder.fast_t.items())},
        "slowBlocksMs": {k: p5095(v) for k, v in sorted(recorder.slow_t.items())},
        "load": mon.summary(),
        "superseded": pipe.inbox.superseded - sup0,
        "slowRuns": pipe.slow_runs - runs0,
        "errors": list(pipe.errors),
    }


def run_serial(engine, video: Path, seconds: float) -> dict[str, Any]:
    """The old single-thread schedule: one worker, engine.step on the newest frame."""
    from perception.common.video import Frame
    from perception.realtime.pipeline import LatestSlot
    from perception.realtime.wire import dumps, to_wire
    slot = LatestSlot()
    stop = threading.Event()
    rec: dict[str, list] = defaultdict(list)
    blocks: dict[str, list] = defaultdict(list)

    def worker():
        while not stop.is_set():
            item = slot.get(0.25)
            if item is None:
                continue
            if item.reset:
                engine.reset()
            t0 = time.perf_counter()
            res = engine.step(Frame(item.index, item.pts_s, item.image))
            rec["compute"].append((time.perf_counter() - t0) * 1000.0)
            dumps(to_wire(res, {**engine.last_meta, "seq": item.seq, "sessionId": "bench", "source": item.source}))
            rec["proc"].append((time.perf_counter() - item.t_grab) * 1000.0)
            rec["t"].append(time.perf_counter())
            for k, v in res.timingsMs.items():
                blocks[k].append(v)

    th = threading.Thread(target=worker, daemon=True)
    th.start()
    src = RealtimeClip(video, seconds, slot.put)
    src.start()
    src.join()
    time.sleep(0.5)
    stop.set()
    th.join(2)
    ts = rec["t"]
    return {"mode": "serial", "sourceFrames": src.frames,
            "outputHz": round((len(ts) - 1) / (ts[-1] - ts[0]), 2) if len(ts) > 1 else None,
            "processingMs": p5095(rec["proc"]), "computeMs": p5095(rec["compute"]),
            "blocksMs": {k: p5095(v) for k, v in sorted(blocks.items())}, "superseded": slot.superseded}


def main() -> None:
    ap = argparse.ArgumentParser(description="Measure the two-lane pipeline alone (no server)")
    ap.add_argument("--video", default="data/bdd100k/videos/val/b1ff4656-0435391e.mov",
                    help="clip (a leading data/ follows PERCEPTION_DATA_DIR)")
    ap.add_argument("--seconds", type=float, default=30.0)
    ap.add_argument("--config", default="perception/config_realtime.yaml")
    ap.add_argument("--set", action="append", default=[], help="config override, e.g. slow_schedule.signs.every=3")
    ap.add_argument("--variant", action="append", default=[],
                    help='JSON, e.g. {"slow_max_hz": 8, "slow_schedule": {"signs": {"every": 3}}}; repeatable')
    ap.add_argument("--serial", action="store_true", help="also measure the old single-thread schedule")
    ap.add_argument("--serial-only", action="store_true")
    ap.add_argument("--log", type=Path, default=None)
    a = ap.parse_args()
    from perception.common.paths import resolve_data_path
    a.video = str(resolve_data_path(a.video))

    import torch  # noqa: F401
    from perception.engine import PerceptionEngine, load_config
    cfg = load_config(a.config)
    for s in a.set:
        set_override(cfg, s)
    # two-lane: each lane warms up in its own thread (engine.warmup_lane); the serial warm-up only for --serial
    eng = PerceptionEngine(cfg, warmup=bool(a.serial or a.serial_only))
    out: dict[str, Any] = {"video": Path(a.video).name, "seconds": a.seconds, "overrides": a.set,
                           "gpu": torch.cuda.get_device_name(0) if torch.cuda.is_available() else None,
                           "date": time.strftime("%Y-%m-%d %H:%M:%S")}
    if not a.serial_only:
        from perception.realtime.pipeline import TwoLanePipeline
        rec = Recorder()
        pipe = TwoLanePipeline(eng, on_wave1=rec.on_wave1, on_wave2=rec.on_wave2)
        out["laneWarmupMs"] = pipe.start()
        variants = [json.loads(v) for v in a.variant] or [{
            "fast_max_hz": cfg["fast"].get("max_hz"), "slow_max_hz": cfg["slow"].get("max_hz")}]
        out["twoLane"] = []
        for v in variants:
            r = run_two_lane(eng, pipe, rec, Path(a.video), a.seconds, v)
            out["twoLane"].append(r)
            print(f"[bench] {json.dumps(v)} -> wave1 {r['wave1Hz']} Hz proc {r['wave1ProcessingMs']} "
                  f"compute {r['wave1ComputeMs']} | wave2 {r['wave2Hz']} Hz distance {r['distanceHz']} Hz "
                  f"proc {r['wave2ProcessingMs']} | distAge {r['distanceAgeMsInWave1']} | load {r['load']}", flush=True)
        pipe.stop()
    if a.serial or a.serial_only:
        eng.reset()
        out["serial"] = run_serial(eng, Path(a.video), a.seconds)
    text = json.dumps(out, indent=2)
    print(text, flush=True)
    if a.log:
        a.log.parent.mkdir(parents=True, exist_ok=True)
        a.log.write_text(text + "\n", encoding="utf-8")
    eng.close()


if __name__ == "__main__":
    main()
