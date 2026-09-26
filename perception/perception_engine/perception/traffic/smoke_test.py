"""Fast API smoke test for the traffic block (about 30 s, includes model loads).

    .venv\\Scripts\\python.exe -m perception.traffic.smoke_test
"""
from __future__ import annotations

import json

import cv2
import numpy as np

from perception.common.schemas import Detection, FrameResult, TrafficLightState, TrafficSign
from perception.common.video import DATA_ROOT


def main() -> None:
    import torch  # noqa: F401  (before onnxruntime)
    from perception.traffic.lights import TrafficLightClassifier
    from perception.traffic.signs import SignRecognizer

    labels = json.load(open(DATA_ROOT / "labels" / "eval_subset_det.json", encoding="utf-8"))
    im = next(i for i in labels if sum(l["category"] == "traffic light" for l in i["labels"]) >= 3)
    img = cv2.imread(str(DATA_ROOT / "images" / "val" / im["name"]))
    gts = [l for l in im["labels"] if l["category"] == "traffic light"]
    dets = [Detection("traffic light", [l["box2d"][k] for k in ("x1", "y1", "x2", "y2")], 0.9, id=i)
            for i, l in enumerate(gts)]
    for backend in ("hsv", "autoware_onnx"):
        for smoothing in ("hmm", "vote", "none"):
            clf = TrafficLightClassifier(backend=backend, smoothing=smoothing, track_stride=2)
            for f in range(6):  # six frames of the same image: tracks and track_stride paths exercised
                out = clf.classify(img, dets, pts_s=f / 30)
            assert len(out) == len(dets) and all(isinstance(o, TrafficLightState) for o in out)
            assert all(o.state in ("RED", "YELLOW", "GREEN", "UNKNOWN") for o in out)
            prim = TrafficLightClassifier.primary(out, img.shape[1])
            print(backend, smoothing, [(o.id, o.state, o.confidence, o.distanceMeters) for o in out],
                  "gt:", [l["attributes"]["trafficLightColor"] for l in gts],
                  "primary:", prim.state if prim else None, f"{clf.last_timing_ms:.1f} ms")
    # plain boxes and dicts are accepted too
    clf = TrafficLightClassifier("hsv")
    assert len(clf.classify(img, [d.bbox for d in dets])) == len(dets)
    assert len(clf.classify(img, [{"bbox": d.bbox, "id": 7} for d in dets[:1]])) == 1
    # signs
    sim = next(i for i in labels if any(l["category"] == "traffic sign" for l in i["labels"]))
    simg = cv2.imread(str(DATA_ROOT / "images" / "val" / sim["name"]))
    sdets = [Detection("traffic sign", [l["box2d"][k] for k in ("x1", "y1", "x2", "y2")], 0.9, id=i)
             for i, l in enumerate(sim["labels"]) if l["category"] == "traffic sign"]
    rec = SignRecognizer("lisa_crops")
    signs = rec.recognize(simg, sdets, pts_s=0.0)
    assert len(signs) == len(sdets) and all(isinstance(s, TrafficSign) for s in signs)
    print("signs lisa_crops:", [(s.id, s.signClass, s.confidence, s.distanceMeters) for s in signs])
    print("signs lisa_full:", [(s.signClass, s.confidence) for s in SignRecognizer("lisa_full").recognize(simg)])
    fr = FrameResult(frameIndex=0, ptsSeconds=0.0, trafficLights=out, trafficSigns=signs)
    json.dumps(fr.to_dict())
    print("OK")


if __name__ == "__main__":
    main()
