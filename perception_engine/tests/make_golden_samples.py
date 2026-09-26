"""Generate the protocol-v1 golden PerceptionFrame samples in contracts/samples/ from the REAL engine (serial
engine.step schedule). Protocol v2 samples: tests/make_golden_samples_v2.py. Run from perception_engine/:

    python tests/make_golden_samples.py [--seconds 30]

For each clip the engine runs from t=0 at stride 2 (~15 Hz, like the realtime server) and one representative
frame is kept: lanes + at least one distance + at least one lit traffic light when the clip has them.
KSR AI Spatial Driving Copilot.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ENGINE_ROOT = Path(__file__).resolve().parents[1]      # perception_engine/
ROOT = ENGINE_ROOT.parent                               # repo root (contracts/)
sys.path.insert(0, str(ENGINE_ROOT))

CLIPS = {
    "city": "b1ff4656-0435391e",      # city intersection with lights
    "highway": "b1f4491b-cf446195",
    "night": "b23adb0d-8a7aaced",
}


def score(msg: dict) -> tuple:
    objs = msg["objects"]
    lanes = msg.get("lanes")
    has_lanes = bool(lanes and len(lanes["laneBoundaries"]) >= 2 and lanes.get("laneCount"))
    n_dist = sum(o["distanceMeters"] is not None and o["class"] not in ("traffic light", "traffic sign") for o in objs)
    n_lit = sum(o.get("lightState") in ("RED", "YELLOW", "GREEN") for o in objs)
    lead = any(o["inEgoPath"] and o["distanceMeters"] is not None for o in objs)
    road_ok = bool(msg.get("road") and any(a["valid"] for a in msg["road"]["anchorPoints"]))
    fresh = msg["blockAges"].get("depth", 9) == 0
    return (has_lanes + (n_dist > 0) + (n_lit > 0) + lead + road_ok + bool(msg["signs"]),
            fresh, min(n_lit, 3), min(n_dist, 6), -abs(len(objs) - 12))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=float, default=30.0)
    ap.add_argument("--out", type=Path, default=ROOT / "contracts" / "samples")
    a = ap.parse_args()

    import torch  # noqa: F401
    from jsonschema import Draft202012Validator
    from perception.common.video import DATA_ROOT, VideoFileInput
    from perception.engine import PerceptionEngine
    from perception.realtime.wire import to_wire

    validator = Draft202012Validator(json.loads((ROOT / "contracts" / "perception_frame.v1.schema.json").read_text()))
    eng = PerceptionEngine(ENGINE_ROOT / "perception" / "config_realtime.yaml")
    a.out.mkdir(parents=True, exist_ok=True)
    for tag, clip in CLIPS.items():
        eng.reset()
        vin = VideoFileInput(DATA_ROOT / "videos" / "val" / f"{clip}.mov", stride=2)
        best, best_s, n = None, None, 0
        for seq, fr in enumerate(vin):
            if fr.pts_s > a.seconds:
                break
            res = eng.step(fr)
            msg = to_wire(res, {**eng.last_meta, "seq": fr.index, "sessionId": f"golden-{tag}",
                                "source": {"kind": "video", "id": clip}}, schema_version=1)
            errs = list(validator.iter_errors(msg))
            if errs:
                raise SystemExit(f"{clip} frame {fr.index}: schema error {errs[0].message} at {list(errs[0].absolute_path)}")
            n += 1
            if seq < 20:        # let trackers / smoothers settle
                continue
            s = score(msg)
            if best_s is None or s > best_s:
                best, best_s = msg, s
        vin.release()
        path = a.out / f"perception_frame.{tag}_{clip}.json"
        path.write_text(json.dumps(best, indent=2) + "\n", encoding="utf-8")
        o = best["objects"]
        print(f"{tag}: {n} frames validated; kept frame {best['frameIndex']} (t={best['ptsSeconds']} s) score {best_s}: "
              f"{len(o)} objects, {sum(x['distanceMeters'] is not None for x in o)} with distance, "
              f"lights {[x['lightState'] for x in o if x['class'] == 'traffic light']}, "
              f"lanes {best['lanes'] and (best['lanes']['currentLane'], best['lanes']['laneCount'])}, "
              f"signs {[s['signClass'] for s in best['signs']]} -> {path.name}", flush=True)
    eng.close()


if __name__ == "__main__":
    main()
