"""Regenerate the protocol-v2 golden samples in contracts/samples/v2/ from REAL server runs.

Knuckle Sandwich Robotics Inc. (KSR) AI Spatial Driving Copilot. Run from perception_engine/ (takes ~5 min: three
server start-ups with model loading + lane warm-up):

    python tests/make_golden_samples_v2.py [--keep-tmp]

  1. --mode video on the city clip b1ff4656-0435391e, ws_probe watch (+ ping + one invalid JSON message)
       -> perception.hello.video, perception.frame.wave1.city (a frame with a lit light, fused distances, lanes),
          perception.update.wave2.city (distances + lanes + road), perception.stats, perception.pong,
          perception.error (badMessage)
  2. --mode video on the night clip b23adb0d-8a7aaced            -> perception.frame.wave1.night
  3. --mode auto with the phase1 nav session (real NavRelay when Node + phase1 are available):
       ws_probe live (960x540 q80 KSR1 uplink, client intrinsics, one bad header)
          -> perception.hello.live, perception.frame.wave1.live (with echo), perception.skip (badHeader),
             client.hello.live, uplink_header.example.txt
       ws_probe sim  -> perception.hello.sim, client.hello.sim, client.playback, client.ping
Every file is validated against contracts/schemas/ before it is copied. The navigation.* / client.trip_state
samples in the same folder belong to the navigation side and are not touched.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

ENGINE_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ENGINE_ROOT))
from tests.test_protocol_v2 import SAMPLES_V2, assert_valid, free_port, validators, wait_health  # noqa: E402

TMP = ENGINE_ROOT / "outputs" / "realtime" / "golden_v2_tmp"
CITY = "data/bdd100k/videos/val/b1ff4656-0435391e.mov"
NIGHT = "data/bdd100k/videos/val/b23adb0d-8a7aaced.mov"
NAV_SESSION = "nav/demo_sessions/b1ff4656-0435391e"


def server(args: list[str], name: str):
    port = free_port()
    log = TMP / f"server_{name}.log"
    log.parent.mkdir(parents=True, exist_ok=True)
    f = open(log, "w", encoding="utf-8")
    p = subprocess.Popen([sys.executable, "-m", "perception.realtime.server", "--host", "127.0.0.1", "--port",
                          str(port), *args], cwd=str(ENGINE_ROOT), stdout=f, stderr=subprocess.STDOUT,
                         env={**os.environ, "PYTHONUNBUFFERED": "1"})
    wait_health(port, p)
    return p, f"ws://127.0.0.1:{port}/perception"


def probe(args: list[str]) -> None:
    r = subprocess.run([sys.executable, "-m", "perception.realtime.ws_probe", *args, "--quiet"], cwd=str(ENGINE_ROOT))
    if r.returncode != 0:
        raise SystemExit(f"ws_probe {args[0]} failed ({r.returncode})")


def stop(p) -> None:
    p.terminate()
    try:
        p.wait(15)
    except subprocess.TimeoutExpired:
        p.kill()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep-tmp", action="store_true")
    ap.add_argument("--seconds", type=float, default=20.0)
    a = ap.parse_args()
    if TMP.exists():
        shutil.rmtree(TMP)
    s = str(a.seconds)
    p, url = server(["--mode", "video", "--video", CITY, "--start-on-connect"], "video_city")
    try:
        probe(["watch", "--url", url, "--seconds", s, "--ping", "--bad-message", "--save-samples", str(TMP / "city"),
               "--sample-tag", "city", "--log", str(TMP / "probe_city.json")])
    finally:
        stop(p)
    p, url = server(["--mode", "video", "--video", NIGHT, "--start-on-connect"], "video_night")
    try:
        probe(["watch", "--url", url, "--seconds", s, "--save-samples", str(TMP / "night"), "--sample-tag", "night",
               "--log", str(TMP / "probe_night.json")])
    finally:
        stop(p)
    p, url = server(["--mode", "auto", "--nav-session", NAV_SESSION], "auto")
    try:
        probe(["live", "--url", url, "--seconds", "12", "--ping", "--bad-header", "--focal-px", "525",
               "--mount-height", "1.3", "--save-samples", str(TMP / "live"), "--sample-tag", "live",
               "--log", str(TMP / "probe_live.json")])
        probe(["sim", "--url", url, "--seconds", "12", "--ping", "--save-samples", str(TMP / "sim"),
               "--log", str(TMP / "probe_sim.json")])
    finally:
        stop(p)

    pick = {
        "perception.hello.video.json": "city/perception.hello.video.json",
        "perception.frame.wave1.city.json": "city/perception.frame.wave1.city.json",
        "perception.update.wave2.city.json": "city/perception.update.wave2.city.json",
        "perception.stats.json": "city/perception.stats.json",
        "perception.pong.json": "city/perception.pong.json",
        "perception.error.json": "city/perception.error.json",
        "perception.frame.wave1.night.json": "night/perception.frame.wave1.night.json",
        "perception.hello.live.json": "live/perception.hello.live.json",
        "perception.frame.wave1.live.json": "live/perception.frame.wave1.live.json",
        "perception.skip.json": "live/perception.skip.json",
        "client.hello.live.json": "live/client.hello.live.json",
        "uplink_header.example.txt": "live/uplink_header.example.txt",
        "perception.hello.sim.json": "sim/perception.hello.sim.json",
        "client.hello.sim.json": "sim/client.hello.sim.json",
        "client.playback.json": "sim/client.playback.json",
        "client.ping.json": "sim/client.ping.json",
    }
    v = validators()
    SAMPLES_V2.mkdir(parents=True, exist_ok=True)
    for dst, src in pick.items():
        sp = TMP / src
        if not sp.exists():
            raise SystemExit(f"missing {src} (see {TMP})")
        text = sp.read_text(encoding="utf-8")
        if ":\\\\" in text or ":/Users" in text:
            raise SystemExit(f"{src} contains an absolute machine path")
        if dst.endswith(".json"):
            assert_valid(v, json.loads(text))
        (SAMPLES_V2 / dst).write_text(text, encoding="utf-8")
        print(f"wrote contracts/samples/v2/{dst}")
    f = json.loads((SAMPLES_V2 / "perception.frame.wave1.city.json").read_text(encoding="utf-8"))
    o = f["objects"]
    print(f"city frame {f['frameIndex']} t={f['ptsSeconds']} s: {len(o)} objects, "
          f"lights {[x['lightState'] for x in o if x['class'] == 'traffic light']}, "
          f"distances {sorted({x['distanceMethod'] for x in o if x['distanceMethod']})}")
    if not a.keep_tmp:
        shutil.rmtree(TMP, ignore_errors=True)


if __name__ == "__main__":
    t0 = time.time()
    main()
    print(f"done in {time.time() - t0:.0f} s")
