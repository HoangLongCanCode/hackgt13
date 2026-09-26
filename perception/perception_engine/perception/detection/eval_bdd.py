"""Evaluate every detector preset on the local 250-image BDD100K val subset with ONE evaluator.

    cd perception_engine
    .venv\\Scripts\\python.exe -m perception.detection.eval_bdd            # all runs, ~10 min on a shared GPU
    .venv\\Scripts\\python.exe -m perception.detection.eval_bdd --runs bdd-yolo26s@960 --bootstrap 0

Protocol
  * GT: data/bdd100k/labels/eval_subset_coco.json (2018 BDD labels -> COCO, 10 classes, no ignore regions).
  * Evaluator: faster-coco-eval 1.8.0 COCOeval_faster (a C++ re-implementation of pycocotools'
    COCOeval; cross-checked against pycocotools on the default run, see metrics.json "crosscheck").
    Standard COCO bbox metrics: AP@[.50:.95], AP50, AP75, APs/m/l, maxDets=100.
  * Box convention: GT bbox = scalabel box2d_to_bbox [x1, y1, x2-x1+1, y2-y1+1]. Predictions get the SAME
    +1 (exactly what the official bdd100k/scalabel det eval does to both sides). A sensitivity check with
    mismatched / stripped conventions is stored under "bbox_convention_check".
  * Category ids are looked up BY NAME from the GT file (never by position).
  * Class sets: "all10" = the 10 BDD classes (BDD-trained models only); "map8" = the 8 classes a COCO
    model can express fully (pedestrian, car, truck, bus, train, motorcycle, bicycle, traffic light). map8 is
    the apples-to-apples comparison for all models. COCO models additionally report 'traffic sign' (COCO
    'stop sign' only) and 'rider' (geometric heuristic) as diagnostics.
  * Eval threshold conf=0.001, max_det=300 per image. Latency is measured separately at the deployment
    threshold conf=0.25 (detect() wall time incl. pre/post, images preloaded in RAM, after warm-up).
  * Slices by image attribute timeofday (daytime 123 / night 93 / dawn/dusk 34 images).
  * Small N: 250 images, 4,604 boxes. 95% CIs from an image-level bootstrap (--bootstrap B, seed 0);
    paired bootstrap differences against the default run.
"""
from __future__ import annotations

import argparse
import contextlib
import io
import json
import os
import sys
import time
from pathlib import Path

os.environ.setdefault("YOLO_AUTOINSTALL", "False")

import cv2
import numpy as np
import torch

from perception.common.paths import portable_paths
from perception.common.video import DATA_ROOT, OUTPUTS_ROOT
from perception.detection.detector import COCO_MAPPABLE_BDD, DEFAULT_PRESET, PRESETS, Detector

OUT = OUTPUTS_ROOT / "detection"
GT_PATH = DATA_ROOT / "labels" / "eval_subset_coco.json"
IMG_DIR = DATA_ROOT / "images" / "val"
VRAM_CAP_GB = 1.5

DEFAULT_RUNS = [
    "bdd-yolo26s@960", "bdd-yolo26s@640",
    "bdd-yolo26n@960", "bdd-yolo26n@640",
    "bdd-rfdetr-nano@576",
    "coco-yolo26s@640", "coco-yolo26s@960",
    "coco-rfdetr-nano@384", "coco-rfdetr-small@512",
    "bdd-yolo26s@960:e2e",   # YOLO26 NMS-free (one-to-one) head
]
DEFAULT_RUN = f"{DEFAULT_PRESET}@960"
TIMEOFDAY = ["daytime", "night", "dawn/dusk"]


# ----------------------------------------------------------------------------- evaluator
def _quiet():
    return contextlib.redirect_stdout(io.StringIO())


def load_gt_dict() -> dict:
    return json.loads(GT_PATH.read_text(encoding="utf-8"))


def make_coco(d: dict):
    from faster_coco_eval import COCO
    with _quiet():
        c = COCO()
        c.dataset = d
        c.createIndex()
    return c


def coco_eval(gt_coco, results: list[dict], cat_ids: list[int], img_ids: list[int] | None = None,
              engine: str = "faster") -> dict:
    """COCO bbox eval. Returns overall stats + per-class AP / AP50 (None if class has no GT)."""
    if engine == "faster":
        from faster_coco_eval import COCOeval_faster as COCOeval
    else:
        from pycocotools.coco import COCO as PCOCO
        from pycocotools.cocoeval import COCOeval
    stats_names = ["mAP50_95", "mAP50", "mAP75", "APs", "APm", "APl", "AR1", "AR10", "AR100", "ARs", "ARm", "ARl"]
    if not results:
        return {k: 0.0 for k in stats_names} | {"per_class": {}}
    with _quiet():
        if engine == "faster":
            dt = gt_coco.loadRes(results)
            E = COCOeval(gt_coco, dt, "bbox")
        else:
            g = PCOCO()
            g.dataset = gt_coco.dataset
            g.createIndex()
            dt = g.loadRes(results)
            E = COCOeval(g, dt, "bbox")
        E.params.catIds = list(cat_ids)
        if img_ids is not None:
            E.params.imgIds = list(img_ids)
        E.evaluate()
        E.accumulate()
        E.summarize()
    out = {k: float(v) for k, v in zip(stats_names, E.stats)}
    prec = E.eval["precision"]  # [T, R, K, A, M]
    per = {}
    for k, cid in enumerate(E.params.catIds):
        p = prec[:, :, k, 0, -1]
        p50 = prec[0, :, k, 0, -1]
        ap = float(np.mean(p[p > -1])) if (p > -1).any() else None
        ap50 = float(np.mean(p50[p50 > -1])) if (p50 > -1).any() else None
        per[cid] = {"AP": ap, "AP50": ap50}
    out["per_class"] = per
    return out


# ----------------------------------------------------------------------------- predictions
def parse_run(run: str) -> tuple[str, int, dict]:
    extra = {}
    if ":" in run:
        run, flag = run.split(":", 1)
        if flag == "e2e":
            extra["nms"] = False
    preset, imgsz = run.split("@")
    return preset, int(imgsz), extra


def to_results(raw: list[dict], det: Detector | None, name_to_id: dict[str, int], label_space: str,
               rider_heuristic: bool = False, plus_one: bool = True, class_map=None) -> list[dict]:
    """Raw per-image predictions -> COCO results list with canonical-BDD category ids."""
    from perception.detection.detector import COCO_TO_BDD, apply_rider_heuristic
    from perception.common.schemas import canonical_class
    res = []
    for r in raw:
        xyxy = np.asarray(r["xyxy"], np.float32).reshape(-1, 4)
        if label_space == "bdd":
            names = [canonical_class(n) for n in r["names"]]
        else:
            names = [COCO_TO_BDD.get(n) for n in r["names"]]
            if rider_heuristic:
                keep = [i for i, n in enumerate(names) if n is not None]
                rel = apply_rider_heuristic(xyxy[keep], [names[i] for i in keep]) if keep else []
                for i, n in zip(keep, rel):
                    names[i] = n
        add = 1.0 if plus_one else 0.0
        for (x1, y1, x2, y2), s, n in zip(xyxy, r["scores"], names):
            if n is None or n not in name_to_id:
                continue
            res.append({"image_id": r["image_id"], "category_id": name_to_id[n],
                        "bbox": [float(x1), float(y1), float(x2 - x1 + add), float(y2 - y1 + add)],
                        "score": float(s)})
    return res


def run_model(run: str, images: list[tuple[int, np.ndarray]], lat_n: int) -> dict:
    preset, imgsz, extra = parse_run(run)
    torch.cuda.reset_peak_memory_stats()
    det = Detector(weights=preset, imgsz=imgsz, conf=0.001, max_det=300, allow_download=False, **extra)
    det.warmup(5)
    raw, eval_ms = [], []
    t_all = time.perf_counter()
    for img_id, img in images:
        r = det.detect_raw(img)
        eval_ms.append(r.timings_ms["total"])
        raw.append({"image_id": img_id, "xyxy": np.round(r.xyxy, 2).tolist(),
                    "scores": np.round(r.scores, 5).tolist(), "names": r.source_names})
    eval_wall = time.perf_counter() - t_all
    # latency at the deployment threshold, full detect() incl. class mapping
    det.conf = 0.25
    det.warmup(10)
    lat = []
    for _, img in images[:lat_n]:
        det.detect(img)
        lat.append(det.last_timings_ms["detect_total"])
    lat = np.asarray(lat)
    info = det.describe()
    info.update(conf_eval=0.001, conf_latency=0.25)
    out = {
        "run": run, "config": info,
        "latency_ms": {"p50": float(np.percentile(lat, 50)), "p90": float(np.percentile(lat, 90)),
                        "mean": float(lat.mean()), "n": int(lat.size), "conf": 0.25,
                        "note": "PROVISIONAL: shared GPU (other agents running); detect() wall time incl. pre/post"},
        "eval_pass_ms_p50": float(np.median(eval_ms)),
        "eval_pass_wall_s": eval_wall,
        "peak_vram_mb": torch.cuda.max_memory_allocated() / 1e6,
        "raw": raw,
    }
    det.close()
    del det
    torch.cuda.empty_cache()
    return out


# ----------------------------------------------------------------------------- operating point
def _iou(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    ix1 = np.maximum(a[:, None, 0], b[None, :, 0]); iy1 = np.maximum(a[:, None, 1], b[None, :, 1])
    ix2 = np.minimum(a[:, None, 2], b[None, :, 2]); iy2 = np.minimum(a[:, None, 3], b[None, :, 3])
    inter = np.clip(ix2 - ix1, 0, None) * np.clip(iy2 - iy1, 0, None)
    aa = (a[:, 2] - a[:, 0]) * (a[:, 3] - a[:, 1]); ab = (b[:, 2] - b[:, 0]) * (b[:, 3] - b[:, 1])
    return inter / np.maximum(aa[:, None] + ab[None, :] - inter, 1e-9)


def operating_point(gt: dict, results: list[dict], cat_ids: list[int], conf: float = 0.25, iou_thr: float = 0.5):
    """Greedy per-image/per-class matching at a fixed score threshold -> precision / recall per class."""
    from collections import defaultdict
    g = defaultdict(list); d = defaultdict(list)
    for a in gt["annotations"]:
        x, y, w, h = a["bbox"]; g[(a["image_id"], a["category_id"])].append([x, y, x + w, y + h])
    for r in results:
        if r["score"] >= conf:
            x, y, w, h = r["bbox"]; d[(r["image_id"], r["category_id"])].append((r["score"], [x, y, x + w, y + h]))
    out = {}
    for c in cat_ids:
        tp = fp = n = 0
        for img in {k[0] for k in g if k[1] == c} | {k[0] for k in d if k[1] == c}:
            gb = np.asarray(g.get((img, c), []), np.float32).reshape(-1, 4)
            dd = sorted(d.get((img, c), []), key=lambda t: -t[0])
            db = np.asarray([b for _, b in dd], np.float32).reshape(-1, 4)
            n += len(gb)
            if len(db) == 0:
                continue
            if len(gb) == 0:
                fp += len(db); continue
            ious = _iou(db, gb); used = np.zeros(len(gb), bool)
            for i in range(len(db)):
                j = int(np.argmax(np.where(used, -1, ious[i])))
                if not used[j] and ious[i, j] >= iou_thr:
                    used[j] = True; tp += 1
                else:
                    fp += 1
        out[c] = {"precision": tp / max(tp + fp, 1), "recall": tp / max(n, 1), "tp": tp, "fp": fp, "n_gt": n}
    return out


# ----------------------------------------------------------------------------- bootstrap
def _boot_one_run(payload) -> dict[str, list[float]]:
    """Worker: mAP50-95 of one run over all bootstrap picks (image-level resampling, new ids for duplicates)."""
    gt, rs, cat_sets, picks = payload
    anns_by: dict[int, list] = {}
    for a in gt["annotations"]:
        anns_by.setdefault(a["image_id"], []).append(a)
    res_by = {cs: {} for cs in rs}
    for cs, lst in rs.items():
        for r in lst:
            res_by[cs].setdefault(r["image_id"], []).append(r)
    out = {cs: [] for cs in rs}
    for pick in picks:
        images, anns, aid = [], [], 1
        for new_id, old in enumerate(pick, start=1):
            images.append({"id": new_id, "width": 1280, "height": 720})
            for a in anns_by.get(int(old), []):
                anns.append(dict(a, id=aid, image_id=new_id)); aid += 1
        gcoco = make_coco({"images": images, "annotations": anns, "categories": gt["categories"]})
        for cs in rs:
            res = [dict(r, image_id=new_id) for new_id, old in enumerate(pick, start=1)
                   for r in res_by[cs].get(int(old), [])]
            out[cs].append(coco_eval(gcoco, res, cat_sets[cs])["mAP50_95"])
    return out


def bootstrap(gt: dict, run_results: dict[str, dict[str, list[dict]]], cat_sets: dict[str, list[int]],
              B: int, seed: int = 0, ref: str = DEFAULT_RUN, workers: int = 4) -> dict:
    """Image-level bootstrap of mAP50-95 for every (run, class set); paired differences vs `ref`.
    The same B resamples (seeded) are used for every run, so differences are paired."""
    from concurrent.futures import ProcessPoolExecutor
    rng = np.random.default_rng(seed)
    img_ids = np.asarray([im["id"] for im in gt["images"]])
    picks = [rng.choice(img_ids, size=len(img_ids), replace=True) for _ in range(B)]
    runs = list(run_results)
    payloads = [(gt, run_results[r], cat_sets, picks) for r in runs]
    if workers > 1:
        with ProcessPoolExecutor(max_workers=workers) as ex:
            results = list(ex.map(_boot_one_run, payloads))
    else:
        results = [_boot_one_run(p) for p in payloads]
    samples = dict(zip(runs, results))
    out = {}
    for run, rs in samples.items():
        out[run] = {}
        for cs, v in rs.items():
            v = np.asarray(v)
            o = {"mean": float(v.mean()), "std": float(v.std()),
                 "ci95": [float(np.percentile(v, 2.5)), float(np.percentile(v, 97.5))]}
            if ref in samples and cs in samples[ref] and run != ref:
                dlt = v - np.asarray(samples[ref][cs])
                o["diff_vs_default"] = {"mean": float(dlt.mean()),
                                        "ci95": [float(np.percentile(dlt, 2.5)), float(np.percentile(dlt, 97.5))],
                                        "p_run_better": float((dlt > 0).mean())}
            out[run][cs] = o
    return {"B": B, "seed": seed, "metric": "mAP50_95", "reference": ref, "paired": True, "runs": out}


def threshold_sweep(gt: dict, results: list[dict], cat_ids: list[int],
                    thresholds=tuple(np.round(np.arange(0.10, 0.71, 0.05), 2))) -> dict:
    """Micro-averaged (box-weighted) precision / recall / F1 over `cat_ids` at IoU 0.5 for several thresholds."""
    rows = []
    for t in thresholds:
        op = operating_point(gt, results, cat_ids, conf=float(t))
        tp = sum(v["tp"] for v in op.values()); fp = sum(v["fp"] for v in op.values())
        n = sum(v["n_gt"] for v in op.values())
        p, r = tp / max(tp + fp, 1), tp / max(n, 1)
        rows.append({"conf": float(t), "precision": p, "recall": r, "f1": 2 * p * r / max(p + r, 1e-9)})
    best = max(rows, key=lambda x: x["f1"])
    return {"iou": 0.5, "classes": "eval classes of the run (all10 for BDD, map8 for COCO)", "sweep": rows,
            "best_f1_conf": best["conf"], "best": best}


def derived(per_class: dict) -> dict:
    """Extra summaries from per-class AP: mean without 'train' (4 GT boxes) and GT-box-weighted AP."""
    items = [(c, v) for c, v in per_class.items() if v["AP"] is not None]
    no_train = [v["AP"] for c, v in items if c != "train"]
    w = sum(v["n_gt"] for _, v in items)
    return {"mAP50_95_excl_train": float(np.mean(no_train)) if no_train else None,
            "box_weighted_AP50_95": float(sum(v["AP"] * v["n_gt"] for _, v in items) / max(w, 1))}


# ----------------------------------------------------------------------------- main
def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--runs", nargs="*", default=DEFAULT_RUNS, help="preset@imgsz[:e2e]")
    ap.add_argument("--bootstrap", type=int, default=200, help="bootstrap resamples (0 = off)")
    ap.add_argument("--workers", type=int, default=4, help="bootstrap worker processes")
    ap.add_argument("--latency-frames", type=int, default=250)
    ap.add_argument("--limit", type=int, default=0, help="debug: only first N images")
    ap.add_argument("--reuse-preds", action="store_true", help="reuse outputs/detection/preds/*.json if present")
    ap.add_argument("--out", default=str(OUT / "metrics.json"))
    args = ap.parse_args(argv)

    torch.backends.cudnn.benchmark = False
    if torch.cuda.is_available():
        total = torch.cuda.get_device_properties(0).total_memory
        torch.cuda.set_per_process_memory_fraction(min(1.0, VRAM_CAP_GB * 1024**3 / total))

    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "preds").mkdir(exist_ok=True)
    gt = load_gt_dict()
    if args.limit:
        keep = {im["id"] for im in gt["images"][: args.limit]}
        gt = dict(gt, images=[im for im in gt["images"] if im["id"] in keep],
                  annotations=[a for a in gt["annotations"] if a["image_id"] in keep])
    gt_coco = make_coco(gt)
    name_to_id = {c["name"]: c["id"] for c in gt["categories"]}
    id_to_name = {v: k for k, v in name_to_id.items()}
    all10 = [name_to_id[n] for n in name_to_id]
    map8 = [name_to_id[n] for n in COCO_MAPPABLE_BDD]
    n_gt = {c: 0 for c in all10}
    for a in gt["annotations"]:
        n_gt[a["category_id"]] += 1
    tod_imgs = {t: [im["id"] for im in gt["images"] if im.get("timeofday") == t] for t in TIMEOFDAY}
    n_gt_tod = {t: sum(1 for a in gt["annotations"] if a["image_id"] in set(ids)) for t, ids in tod_imgs.items()}

    print(f"loading {len(gt['images'])} images ...", flush=True)
    images = [(im["id"], cv2.imread(str(IMG_DIR / im["file_name"]))) for im in gt["images"]]
    assert all(img is not None and img.shape == (720, 1280, 3) for _, img in images)

    metrics = {
        "block": "detection",
        "dataset": {"gt": str(GT_PATH), "images": len(gt["images"]), "boxes": len(gt["annotations"]),
                    "n_gt_per_class": {id_to_name[c]: n for c, n in n_gt.items()},
                    "timeofday_images": {t: len(v) for t, v in tod_imgs.items()},
                    "timeofday_boxes": n_gt_tod,
                    "label_release": "BDD100K 2018 (bdd100k_labels_images_val.json), no ignore regions"},
        "protocol": {"evaluator": "faster-coco-eval 1.8.0 COCOeval_faster (bbox, maxDets 100)",
                     "bbox_convention": "scalabel [x1,y1,x2-x1+1,y2-y1+1] on GT and predictions",
                     "conf_eval": 0.001, "max_det": 300, "class_sets": {
                         "all10": [id_to_name[c] for c in all10], "map8": [id_to_name[c] for c in map8]},
                     "latency": "detect() wall ms at conf 0.25, p50 over preloaded images, after warm-up; PROVISIONAL (shared GPU)"},
        "env": {"torch": torch.__version__, "gpu": torch.cuda.get_device_name(0) if torch.cuda.is_available() else None},
        "runs": {},
    }

    run_results: dict[str, dict[str, list[dict]]] = {}
    raw_by_run: dict[str, list[dict]] = {}
    for run in args.runs:
        pfile = OUT / "preds" / (run.replace("@", "_").replace(":", "_") + ".json")
        if args.reuse_preds and pfile.exists():
            rec = json.loads(pfile.read_text())
            print(f"[{run}] reusing {pfile.name}", flush=True)
        else:
            print(f"[{run}] running ...", flush=True)
            for attempt in (1, 2):
                try:
                    rec = run_model(run, images, args.latency_frames)
                    break
                except torch.OutOfMemoryError as e:  # shared GPU: wait and retry once
                    print(f"[{run}] CUDA OOM ({e}); retry in 60 s", flush=True)
                    torch.cuda.empty_cache()
                    if attempt == 2:
                        raise
                    time.sleep(60)
            if not args.limit:  # never overwrite the full-set predictions with a debug subset
                pfile.write_text(json.dumps(rec))
        raw_by_run[run] = rec["raw"]
        preset, imgsz, _ = parse_run(run)
        space = PRESETS[preset].label_space
        res = to_results(rec["raw"], None, name_to_id, space)
        m = {k: v for k, v in rec.items() if k != "raw"}
        m["label_space"] = space
        m["n_predictions_eval"] = len(res)
        sets = {"map8": map8} | ({"all10": all10} if space == "bdd" else {})
        run_results[run] = {cs: res for cs in sets}
        for cs, ids in sets.items():
            ev = coco_eval(gt_coco, res, ids)
            ev["per_class"] = {id_to_name[c]: dict(v, n_gt=n_gt[c]) for c, v in ev["per_class"].items()}
            ev["timeofday"] = {}
            for t, imgs in tod_imgs.items():
                e2 = coco_eval(gt_coco, res, ids, img_ids=imgs)
                ev["timeofday"][t] = {"mAP50_95": e2["mAP50_95"], "mAP50": e2["mAP50"], "n_images": len(imgs),
                                      "per_class_AP": {id_to_name[c]: v["AP"] for c, v in e2["per_class"].items()}}
            ev.update(derived(ev["per_class"]))
            m[cs] = ev
        m["threshold_sweep"] = threshold_sweep(gt, res, all10 if space == "bdd" else map8)
        op = operating_point(gt, res, all10 if space == "bdd" else map8 + [name_to_id["traffic sign"]], conf=0.25)
        m["operating_point_conf0.25_iou0.5"] = {id_to_name[c]: v for c, v in op.items()}
        if space == "coco":
            diag = {}
            ts = coco_eval(gt_coco, res, [name_to_id["traffic sign"]])
            diag["traffic_sign_from_stop_sign"] = {"AP": ts["per_class"][name_to_id["traffic sign"]]["AP"],
                                                   "AP50": ts["per_class"][name_to_id["traffic sign"]]["AP50"],
                                                   "n_gt": n_gt[name_to_id["traffic sign"]]}
            res_r = to_results(rec["raw"], None, name_to_id, space, rider_heuristic=True)
            rr = coco_eval(gt_coco, res_r, [name_to_id["rider"]])
            pm = coco_eval(gt_coco, res_r, map8)
            diag["rider_heuristic"] = {"rider_AP": rr["per_class"][name_to_id["rider"]]["AP"],
                                       "rider_AP50": rr["per_class"][name_to_id["rider"]]["AP50"],
                                       "n_gt_rider": n_gt[name_to_id["rider"]],
                                       "map8_mAP50_95_with_heuristic": pm["mAP50_95"],
                                       "pedestrian_AP_with_heuristic": pm["per_class"][name_to_id["pedestrian"]]["AP"]}
            m["coco_diagnostics"] = diag
        metrics["runs"][run] = m
        head = m.get("all10", m["map8"])
        print(f"[{run}] map8 mAP50-95 {m['map8']['mAP50_95']:.4f} mAP50 {m['map8']['mAP50']:.4f}"
              + (f" | all10 {m['all10']['mAP50_95']:.4f}/{m['all10']['mAP50']:.4f}" if "all10" in m else "")
              + f" | p50 {m['latency_ms']['p50']:.1f} ms | vram {m['peak_vram_mb']:.0f} MB", flush=True)

    # --- checks on the default run
    if DEFAULT_RUN in metrics["runs"]:
        raw = raw_by_run[DEFAULT_RUN]
        res_p1 = to_results(raw, None, name_to_id, "bdd", plus_one=True)
        res_p0 = to_results(raw, None, name_to_id, "bdd", plus_one=False)
        gt_strip = dict(gt, annotations=[dict(a, bbox=[a["bbox"][0], a["bbox"][1], a["bbox"][2] - 1, a["bbox"][3] - 1])
                                         for a in gt["annotations"]])
        gcs = make_coco(gt_strip)
        chk = {}
        for cs, ids in (("all10", all10), ("map8", map8)):
            chk[cs] = {
                "both_plus1 (primary)": coco_eval(gt_coco, res_p1, ids)["mAP50_95"],
                "both_stripped": coco_eval(gcs, res_p0, ids)["mAP50_95"],
                "MISMATCH gt+1 pred+0": coco_eval(gt_coco, res_p0, ids)["mAP50_95"],
            }
            tl = name_to_id["traffic light"]
            chk[cs + "_traffic_light_AP"] = {
                "both_plus1": coco_eval(gt_coco, res_p1, [tl])["per_class"][tl]["AP"],
                "MISMATCH gt+1 pred+0": coco_eval(gt_coco, res_p0, [tl])["per_class"][tl]["AP"]}
        metrics["bbox_convention_check"] = {"run": DEFAULT_RUN, **chk}
        try:
            pc = coco_eval(gt_coco, res_p1, all10, engine="pycocotools")
            fc = coco_eval(gt_coco, res_p1, all10)
            metrics["crosscheck"] = {"run": DEFAULT_RUN, "pycocotools_mAP50_95": pc["mAP50_95"],
                                     "faster_coco_eval_mAP50_95": fc["mAP50_95"],
                                     "pycocotools_mAP50": pc["mAP50"], "faster_coco_eval_mAP50": fc["mAP50"],
                                     "abs_diff": abs(pc["mAP50_95"] - fc["mAP50_95"])}
        except Exception as e:  # pragma: no cover
            metrics["crosscheck"] = {"error": repr(e)}

    lat_file = OUT / "latency.json"
    if lat_file.exists():  # interleaved benchmark (bench_latency.py) is the latency number to quote
        lat = json.loads(lat_file.read_text())
        metrics["latency_interleaved"] = {k: v for k, v in lat.items() if k != "runs"}
        for run, v in lat["runs"].items():
            if run in metrics["runs"]:
                metrics["runs"][run]["latency_interleaved_ms"] = v

    if args.bootstrap > 0:
        print(f"bootstrap B={args.bootstrap} ...", flush=True)
        t = time.perf_counter()
        metrics["bootstrap"] = bootstrap(gt, run_results, {"map8": map8, "all10": all10}, args.bootstrap,
                                         workers=args.workers)
        metrics["bootstrap"]["seconds"] = time.perf_counter() - t

    # no machine paths in the file that gets copied to results/ (dataset / weights as <data>/..., <models>/...)
    Path(args.out).write_text(json.dumps(portable_paths(metrics), indent=1))
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
