"""Sign typing on the 13 BDD100K clips: detections, counts, contact sheets and MANUAL precision.

BDD100K has only a generic 'traffic sign' class (no subtypes). So precision is judged by eye
from a contact sheet (the verdicts are stored in perception/traffic/signs_manual_truth.json and
re-applied automatically), and recall is judged only qualitatively (see README).

    .venv\\Scripts\\python.exe -m perception.traffic.eval_signs            # every 2 s over 13 clips

Backends compared on the same frames:
  lisa_crops  BDD YOLO26s 'traffic sign' boxes (conf >= 0.30), typed by the LISA YOLO11n on context crops
  lisa_full   LISA YOLO11n on the full frame (imgsz 1280)
  coco_stop   COCO YOLO26s 'stop sign' on the full frame (imgsz 1280)
For the LISA backends each typed sign carries the raw LISA class and the PP-OCRv6-verified
class (SignRecognizer default). The contact sheet is stratified by (backend, raw class) with a
fixed seed, so reruns show the same tiles, and the truth file matches them by clip/frame/bbox.
Outputs (outputs/traffic/): signs_contact_sheet.png, signs_recall_sheet.png, signs_detections.json,
metrics.json "signs".
"""
from __future__ import annotations

import argparse
import json
import os
import random
import time
from collections import Counter, defaultdict
from pathlib import Path

import cv2
import numpy as np

os.environ.setdefault("YOLO_AUTOINSTALL", "False")

from perception.common.schemas import Detection  # noqa: E402
from perception.common.video import OUTPUTS_ROOT, VideoFileInput, all_videos  # noqa: E402
from perception.traffic.e2e_video import detector_weights  # noqa: E402
from perception.traffic.eval_bdd import update_metrics  # noqa: E402
from perception.traffic.signs import SignRecognizer, _iou  # noqa: E402

OUT = OUTPUTS_ROOT / "traffic"
TRUTH = Path(__file__).with_name("signs_manual_truth.json")


def context_tile(img, box, label, size=150):
    H, W = img.shape[:2]
    x1, y1, x2, y2 = box
    side = int(max(48, 3.0 * max(x2 - x1, y2 - y1)))
    cx, cy = (x1 + x2) / 2, (y1 + y2) / 2
    ox, oy = int(max(0, min(W - side, cx - side / 2))), int(max(0, min(H - side, cy - side / 2)))
    crop = img[oy:oy + side, ox:ox + side].copy()
    s = size / side
    crop = cv2.resize(crop, (size, size), interpolation=cv2.INTER_CUBIC)
    cv2.rectangle(crop, (int((x1 - ox) * s), int((y1 - oy) * s)), (int((x2 - ox) * s), int((y2 - oy) * s)), (255, 0, 255), 1)
    tile = np.zeros((size + 34, size, 3), np.uint8)
    tile[:size] = crop
    for k, line in enumerate(label.split("\n")[:2]):
        cv2.putText(tile, line, (2, size + 13 + 15 * k), cv2.FONT_HERSHEY_SIMPLEX, 0.36, (255, 255, 255), 1, cv2.LINE_AA)
    return tile


def grid(tiles, cols=8):
    if not tiles:
        return np.zeros((10, 10, 3), np.uint8)
    h, w = tiles[0].shape[:2]
    while len(tiles) % cols:
        tiles.append(np.zeros((h, w, 3), np.uint8))
    return np.vstack([np.hstack(tiles[i:i + cols]) for i in range(0, len(tiles), cols)])


def verdict(label: str, truth: str) -> str:
    if label == "unknown":
        return "abstain"
    if label == truth:
        return "correct"
    if label == "pedestrian_crossing" and truth == "school":
        return "near"
    if label.startswith("speed_limit") and truth.startswith("speed_limit"):
        return "value_wrong"
    return "wrong"


def score_manual(typed: list[dict]) -> dict | None:
    if not TRUTH.exists():
        return None
    truth = json.load(open(TRUTH))["tiles"]
    rows = []
    for t in truth:
        m = [d for d in typed if d["backend"] == t["backend"] and d["clip"] == t["clip"] and d["frame"] == t["frame"]
             and _iou(d["bbox"], t["bbox"]) > 0.8]
        if not m:
            rows.append({"sheet_index": t["sheet_index"], "matched": False})
            continue
        m.sort(key=lambda d: (d["raw"] != t["raw_pred_first_run"], -_iou(d["bbox"], t["bbox"])))
        d = m[0]
        rows.append({"sheet_index": t["sheet_index"], "matched": True, "backend": d["backend"], "truth": t["truth"],
                     "raw": d["raw"], "verified": d["cls"], "ocr": d.get("ocr", ""),
                     "raw_verdict": verdict(d["raw"], t["truth"]), "verified_verdict": verdict(d["cls"], t["truth"])})
    ok = [r for r in rows if r["matched"]]

    def agg(rs, key):
        c = Counter(r[key] for r in rs)
        called = sum(v for k, v in c.items() if k != "abstain")
        return {"n": len(rs), **dict(c), "precision_strict": round(c.get("correct", 0) / called, 3) if called else None}

    res = {"matched_tiles": len(ok), "of": len(truth), "rows": rows, "per_backend": {}, "per_raw_class_lisa": {}}
    for bk in sorted({r["backend"] for r in ok}):
        rs = [r for r in ok if r["backend"] == bk]
        res["per_backend"][bk] = {"raw": agg(rs, "raw_verdict"), "ocr_verified": agg(rs, "verified_verdict")}
    for c in ("stop", "yield", "pedestrian_crossing", "speed_limit"):
        rs = [r for r in ok if r["backend"] != "coco_stop" and r["raw"].startswith(c)]
        if rs:
            res["per_raw_class_lisa"][c] = {"raw": agg(rs, "raw_verdict"), "ocr_verified": agg(rs, "verified_verdict")}
    plan = {"stop", "yield", "pedestrian_crossing", "do_not_enter"}
    res["ocr_suppressed_true_signs"] = sum(1 for r in ok if r["verified"] == "unknown" and
                                           (r["truth"] in plan or r["truth"].startswith("speed_limit")))
    res["ocr_suppressed_false_signs"] = sum(1 for r in ok if r["verified"] == "unknown" and r["raw_verdict"] == "wrong")
    return res


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--step-s", type=float, default=2.0)
    ap.add_argument("--det-conf", type=float, default=0.30)
    ap.add_argument("--max-sheet", type=int, default=40)
    ap.add_argument("--seed", type=int, default=3)
    ap.add_argument("--rescore", action="store_true", help="only re-apply the manual truth to signs_detections.json")
    args = ap.parse_args()
    if args.rescore:
        manual = score_manual(json.load(open(OUT / "signs_detections.json"))["typed"])
        json.dump(manual, open(OUT / "signs_manual_scored.json", "w"), indent=1)
        m = json.loads((OUT / "metrics.json").read_text())
        m["signs"]["manual_precision"] = {k: v for k, v in manual.items() if k != "rows"}
        (OUT / "metrics.json").write_text(json.dumps(m, indent=1))
        print(json.dumps(m["signs"]["manual_precision"]["per_backend"], indent=1))
        return
    import torch  # noqa: F401
    from ultralytics import YOLO

    det = YOLO(detector_weights())
    recs = {"lisa_crops": SignRecognizer("lisa_crops"), "lisa_full": SignRecognizer("lisa_full"),
            "coco_stop": SignRecognizer("coco_stop")}
    stride = int(round(args.step_s * 30))
    frames_seen = 0
    typed, bdd_boxes = [], []
    times = defaultdict(list)
    stash = {}
    for vp in all_videos():
        clip = vp.stem
        vin = VideoFileInput(vp, stride=stride)
        for fr in vin:
            frames_seen += 1
            img = fr.image
            t0 = time.perf_counter()
            r = det.predict(img, imgsz=960, conf=args.det_conf, classes=[9], verbose=False, device=0)[0]
            times["bdd_yolo26s_det"].append((time.perf_counter() - t0) * 1000)
            dets = [Detection("traffic sign", [float(v) for v in b], float(s)) for b, s in
                    zip(r.boxes.xyxy.cpu().numpy(), r.boxes.conf.cpu().numpy())]
            keep = bool(dets)
            for name, rec in recs.items():
                rec.reset()  # sampled frames are independent (no tracking)
                t0 = time.perf_counter()
                out = rec.recognize(img, dets if name == "lisa_crops" else None, pts_s=fr.pts_s)
                times[name].append((time.perf_counter() - t0) * 1000)
                if name == "lisa_crops":
                    for s in out:
                        bdd_boxes.append({"clip": clip, "frame": fr.index, "t": round(fr.pts_s, 2), "bbox": s.bbox,
                                          "cls": s.signClass, "h": s.bbox[3] - s.bbox[1]})
                confs = {tuple(round(v, 1) for v in s.bbox): s.confidence for s in out}
                for dbg in rec.last_debug:  # every raw-typed sign (before OCR suppression)
                    typed.append({"backend": name, "clip": clip, "frame": fr.index, "t": round(fr.pts_s, 2),
                                  "raw": dbg["raw"], "cls": dbg["verified"], "ocr": dbg["ocr"],
                                  "conf": confs.get(tuple(round(v, 1) for v in dbg["bbox"]), 0.0),
                                  "bbox": [round(v, 1) for v in dbg["bbox"]], "h": round(dbg["bbox"][3] - dbg["bbox"][1], 1)})
                    keep = True
            if keep:
                stash[(clip, fr.index)] = img
        vin.release()
    OUT.mkdir(parents=True, exist_ok=True)
    rng = random.Random(args.seed)
    groups = defaultdict(list)
    for i, d in enumerate(typed):
        groups[(d["backend"], d["raw"])].append(i)
    for g in groups.values():
        rng.shuffle(g)
    order, keys = [], sorted(groups)
    while len(order) < min(args.max_sheet, len(typed)):
        for k in keys:
            if groups[k] and len(order) < args.max_sheet:
                order.append(groups[k].pop())
    tiles = []
    for n, i in enumerate(order):
        d = typed[i]
        d["sheet_index"] = n
        lab2 = d["raw"] if d["raw"] == d["cls"] else f"{d['raw']}>{d['cls']}"
        tiles.append(context_tile(stash[(d["clip"], d["frame"])], d["bbox"],
                                  f"#{n} {d['backend'].replace('lisa_', 'L')} {d['clip'][:4]} h{int(d['h'])}\n{lab2}"))
    cv2.imwrite(str(OUT / "signs_contact_sheet.png"), grid(tiles))
    big = [b for b in bdd_boxes if b["h"] >= 15]
    rng.shuffle(big)
    rtiles = []
    for n, b in enumerate(big[:40]):
        b["recall_index"] = n
        rtiles.append(context_tile(stash[(b["clip"], b["frame"])], b["bbox"], f"r{n} {b['clip'][:4]} h{int(b['h'])}\n{b['cls']}"))
    cv2.imwrite(str(OUT / "signs_recall_sheet.png"), grid(rtiles))
    json.dump({"typed": typed, "recall_sample": big[:40]}, open(OUT / "signs_detections.json", "w"), indent=1)
    counts_raw = {bk: dict(Counter(d["raw"] for d in typed if d["backend"] == bk)) for bk in recs}
    counts_ver = {bk: dict(Counter(d["cls"] for d in typed if d["backend"] == bk)) for bk in recs}
    manual = score_manual(typed)
    summary = {
        "frames": frames_seen, "clips": len(all_videos()), "step_s": args.step_s,
        "bdd_sign_boxes": len(bdd_boxes), "bdd_sign_boxes_h_ge_15": len(big),
        "lisa_crops_typed_fraction_raw": round(sum(1 for d in typed if d["backend"] == "lisa_crops") / max(1, len(bdd_boxes)), 4),
        "lisa_crops_typed_fraction_ocr_verified": round(sum(1 for d in typed if d["backend"] == "lisa_crops" and d["cls"] != "unknown") / max(1, len(bdd_boxes)), 4),
        "typed_counts_raw": counts_raw, "typed_counts_ocr_verified": counts_ver,
        "p50_ms": {k: round(float(np.median(v)), 2) for k, v in times.items()},
        "timings_note": "PROVISIONAL (shared GPU); lisa_crops/lisa_full include OCR; lisa_crops excludes the BDD detector",
        "manual_precision": {k: v for k, v in (manual or {}).items() if k != "rows"} if manual else "no truth file",
        "manual_rows_file": "outputs/traffic/signs_detections.json (typed[].sheet_index) + perception/traffic/signs_manual_truth.json",
    }
    if manual:
        json.dump(manual, open(OUT / "signs_manual_scored.json", "w"), indent=1)
    update_metrics("signs", summary)
    print(json.dumps({k: v for k, v in summary.items()}, indent=1))


if __name__ == "__main__":
    main()
