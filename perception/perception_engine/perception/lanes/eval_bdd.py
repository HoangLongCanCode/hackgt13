"""Evaluate the lane / drivable backends on the 250-image BDD100K val subset.

    .venv\\Scripts\\python.exe -m perception.lanes.eval_bdd
    (options: --backends twinlitenetplus_large yolop_onnx ... --limit 50 --out outputs/lanes)

Ground truth
------------
lane lines : YOLOP-protocol masks (hustvl/YOLOP `ll_seg_annotations.zip`, val split, the masks
             TwinLiteNet+/YOLOP/HybridNets are trained and scored on), range-extracted for the 250
             subset images into data/bdd100k/labels/yolop_ll_seg_val/. Measured here: 1280x720,
             centre lines drawn with cv2 thickness=2 (area/skeleton = 3.5 px, same as a synthetic
             cv2 thickness-2 line), ALL 8 BDD lane categories incl. crosswalk, road curb and
             'vertical' (stop-line) marks; double-edge labels collapsed to a centre line.
drivable   : (a) YOLOP `da_seg_annotations` (binary, direct+alternative), for the 640x360 protocol;
             (b) data/bdd100k/labels/drivable_masks (local rasterisation of the 2018 poly2d:
             0 direct, 1 alternative, 2 background) at 1280x720, incl. 'direct only'.

Metrics
-------
* lane IoU / balanced accuracy at 640x360 = TwinLiteNet+ val.py protocol (GT resized with
  INTER_LINEAR and thresholded > 1, prediction = argmax of the cropped network output).
  Reported as a global (pooled) IoU; TLNP+ averages per batch of 16, so expect small offsets.
* lane IoU at 1280x720 (upsampled prediction vs the native mask), and the same with an ignore
  zone around 'vertical'/crosswalk labels (lane lines that matter for guidance).
* BDD100K-style boundary F-score, category-agnostic, foreground only: per image
  P = |pred & dilate(gt, r)| / |pred|, R = |gt & dilate(pred, r)| / |gt|, r in {1, 2, 5, 10} px @720p
  (cv2 ellipse kernel, close to skimage disk(r)). `lane_F_r*` excludes images where GT and prediction
  are both empty; `lane_F_r*_bddconv` scores them F = 1 like bdd100k/eval/lane.py.
* drivable IoU / mIoU (drivable vs background) at 640x360 (YOLOP GT) and 1280x720 (local GT).
* ego lane ('direct' proxy): the polygon between the chosen ego boundaries, intersected with
  the predicted drivable mask, vs BDD 'direct' area.
* lane state vs an ORACLE (the same post-processor run on the GT lane + GT drivable masks) and vs
  manual labels (perception/lanes/manual_lane_labels.json, from visual inspection) when present.
* latency (PROVISIONAL, shared GPU): p50/p95 of pre-processing + model, post-processing.
"""
from __future__ import annotations

import argparse
import csv
import json
import platform
import time
from collections import defaultdict
from pathlib import Path

import cv2
import numpy as np
import torch

from perception.common.video import DATA_ROOT, OUTPUTS_ROOT, PROJECT_ROOT
from perception.lanes.backends import WORK_H, WORK_W
from perception.lanes.lanes import BACKENDS, LaneDetector
from perception.lanes.postprocess import PostConfig, process_masks

LABELS = DATA_ROOT / "labels"
IMAGES = DATA_ROOT / "images" / "val"
MANUAL = PROJECT_ROOT / "perception" / "lanes" / "manual_lane_labels.json"
RADII = (1, 2, 5, 10)   # bdd100k/eval/lane.py BOUND_PIXELS = [1, 2, 5]; 10 kept for continuity
VEHICLES = {"car", "truck", "bus", "train", "motor", "bike"}   # 2018 label names


def _read(path):
    m = cv2.imread(str(path), cv2.IMREAD_UNCHANGED)
    if m is None:
        raise FileNotFoundError(path)
    return m[..., 0] if m.ndim == 3 else m


def sample_poly2d(poly):
    v = np.asarray(poly["vertices"], float)
    t = poly["types"]
    pts, i = [], 0
    while i < len(v) - 1:
        if i + 3 < len(v) and t[i + 1] == "C" and t[i + 2] == "C":
            s = np.linspace(0, 1, 20)[:, None]
            p0, p1, p2, p3 = v[i], v[i + 1], v[i + 2], v[i + 3]
            pts.append((1 - s) ** 3 * p0 + 3 * (1 - s) ** 2 * s * p1 + 3 * (1 - s) * s ** 2 * p2 + s ** 3 * p3)
            i += 3
        else:
            s = np.linspace(0, 1, 10)[:, None]
            pts.append(v[i] * (1 - s) + v[i + 1] * s)
            i += 1
    return np.concatenate(pts) if pts else v


def transverse_ignore_mask(labels, shape=(720, 1280), thickness=21):
    """Zone around 'vertical' (stop lines, crosswalk edges) and crosswalk lane labels."""
    m = np.zeros(shape, np.uint8)
    for l in labels:
        if l.get("category") != "lane":
            continue
        a = l.get("attributes", {})
        if a.get("laneDirection") == "vertical" or a.get("laneType") == "crosswalk":
            for p in l.get("poly2d", []):
                pts = sample_poly2d(p).round().astype(np.int32)
                cv2.polylines(m, [pts], False, 1, thickness)
    return m.astype(bool)


class PixAcc:
    def __init__(self):
        self.tp = self.fp = self.fn = self.tn = 0

    def add(self, pred, gt, valid=None):
        if valid is not None:
            pred, gt = pred[valid], gt[valid]
        tp = int(np.count_nonzero(pred & gt))
        fp = int(np.count_nonzero(pred & ~gt))
        fn = int(np.count_nonzero(~pred & gt))
        self.tp += tp
        self.fp += fp
        self.fn += fn
        self.tn += int(pred.size - tp - fp - fn)
        return tp / max(tp + fp + fn, 1)

    def iou(self):
        return self.tp / max(self.tp + self.fp + self.fn, 1)

    def miou(self):
        bg = self.tn / max(self.tn + self.fp + self.fn, 1)
        return (self.iou() + bg) / 2

    def bal_acc(self):
        sens = self.tp / max(self.tp + self.fn, 1)
        spec = self.tn / max(self.tn + self.fp, 1)
        return (sens + spec) / 2


def f_tol(pred, gt, kernels):
    out = {}
    np_, ng = int(pred.sum()), int(gt.sum())
    for r, k in kernels.items():
        if np_ == 0 and ng == 0:
            out[r] = None
            continue
        if np_ == 0 or ng == 0:
            out[r] = 0.0
            continue
        p = np.count_nonzero(pred & (cv2.dilate(gt.astype(np.uint8), k) > 0)) / np_
        rc = np.count_nonzero(gt & (cv2.dilate(pred.astype(np.uint8), k) > 0)) / ng
        out[r] = 0.0 if p + rc == 0 else 2 * p * rc / (p + rc)
    return out


def pct(v, q):
    v = [x for x in v if x is not None]
    return round(float(np.percentile(v, q)), 2) if v else None


def summarize_state(recs, key_pred, key_ref, key_alt=None):
    rows = [r for r in recs if r[key_ref][0] is not None]
    n = len(rows)
    if n == 0:
        return {"n_reference": 0}
    have = [r for r in rows if r[key_pred][0] is not None]
    out = {
        "n_reference": n,
        "pred_available": len(have),
        "laneCount_exact": round(sum(r[key_pred][0] == r[key_ref][0] for r in have) / n, 3),
        "laneCount_within1": round(sum(abs(r[key_pred][0] - r[key_ref][0]) <= 1 for r in have) / n, 3),
        "currentLane_exact": round(sum(r[key_pred][1] == r[key_ref][1] for r in have) / n, 3),
        "both_exact": round(sum(tuple(r[key_pred]) == tuple(r[key_ref]) for r in have) / n, 3),
    }
    if key_alt:
        out["both_acceptable"] = round(sum(list(r[key_pred]) in [list(x) for x in r[key_alt]] for r in have) / n, 3)
    return out


def run(backends, limit=None, out_dir=OUTPUTS_ROOT / "lanes", save_pred_dir=None):
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    subset = json.loads((LABELS / "eval_subset_det.json").read_text())
    if limit:
        subset = subset[:limit]
    manual = {}
    if MANUAL.exists():
        manual = {d["name"]: d for d in json.loads(MANUAL.read_text())["labels"]}
    kernels = {r: cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1)) for r in RADII}
    cfg = PostConfig()

    # ---- ground truth + oracle lane state (backend independent)
    gts = []
    for im in subset:
        stem = im["name"][:-4]
        ll = _read(LABELS / "yolop_ll_seg_val" / f"{stem}.png")
        da_y = _read(LABELS / "yolop_da_seg_val" / f"{stem}.png")
        da_l = _read(LABELS / "drivable_masks" / f"{stem}.png")
        ll_s = cv2.resize(ll, (WORK_W, WORK_H), interpolation=cv2.INTER_LINEAR) > 1
        da_s = cv2.resize((da_y > 0).astype(np.uint8), (WORK_W, WORK_H), interpolation=cv2.INTER_NEAREST) > 0
        frame = cv2.imread(str(IMAGES / im["name"]))
        small = cv2.resize(frame, (WORK_W, WORK_H), interpolation=cv2.INTER_AREA)
        dl_s = cv2.resize(da_l, (WORK_W, WORK_H), interpolation=cv2.INTER_NEAREST) < 2
        orc = process_masks(ll_s, dl_s, cfg, frame_small=small)["state"]
        o = (None, None) if not orc.get("ok") else (orc["nL"] + 1 + orc["nR"], orc["nL"] + 1)
        man = manual.get(stem)
        gts.append({"name": stem, "attrs": im.get("attributes", {}),
                    "oracle": o, "manual": (man["laneCount"], man["currentLane"]) if man and man.get("laneCount") else (None, None),
                    "manual_ok": man.get("acceptable", []) if man else [],
                    "labels": im.get("labels", [])})
    results = {}
    for b in backends:
        print(f"== {b}", flush=True)
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
            torch.cuda.reset_peak_memory_stats()
        det = LaneDetector(b, device="cuda")
        acc = {k: PixAcc() for k in ("lane_s", "lane_f", "lane_f_ign", "drv_s", "drv_f", "ego")}
        slices = defaultdict(lambda: {"lane": PixAcc(), "drv": PixAcc(), "n": 0})
        recs, times = [], defaultdict(list)
        for i, (im, g) in enumerate(zip(subset, gts)):
            stem = g["name"]
            frame = cv2.imread(str(IMAGES / im["name"]))
            t0 = time.perf_counter()
            m = det._margins(frame)
            t1 = time.perf_counter()
            a = det.analyze(frame, full_res_masks=True, margins=m)
            if i >= 5:
                for k, v in det.backend.last_timings.items():
                    times[k].append(v)
                times["segment_ms"].append((t1 - t0) * 1e3)
                times["lanestate_ms"].append(a.timings_ms["lanestate_ms"])
                times["masks_ms"].append(a.timings_ms["masks_ms"])
                times["end_to_end_ms"].append((t1 - t0) * 1e3 + a.timings_ms["total_ms"])
            ll = _read(LABELS / "yolop_ll_seg_val" / f"{stem}.png") > 0
            ll_s = cv2.resize(ll.astype(np.uint8) * 255, (WORK_W, WORK_H), interpolation=cv2.INTER_LINEAR) > 1
            # review fix: TLNP+ BDD100K.py protocol = cv2.resize(raw label, INTER_LINEAR default) then > 1
            da_ys = cv2.resize(_read(LABELS / "yolop_da_seg_val" / f"{stem}.png"), (WORK_W, WORK_H),
                               interpolation=cv2.INTER_LINEAR) > 1
            da_l = _read(LABELS / "drivable_masks" / f"{stem}.png")
            p_ls, p_ds = m["lane"] > 0, m["drivable"] > 0
            if "ego_car" in m:
                p_ds = p_ds & ~(m["ego_car"] > 0)
            p_lf = a.masks["lane_mask"].astype(bool)
            p_df = a.masks["drivable_mask"].astype(bool)
            if "ego_car_mask" in a.masks:
                p_df &= ~a.masks["ego_car_mask"].astype(bool)
            ign = transverse_ignore_mask(g["labels"])
            iou_s = acc["lane_s"].add(p_ls, ll_s)
            acc["lane_f"].add(p_lf, ll)
            acc["lane_f_ign"].add(p_lf, ll, valid=~ign)
            acc["drv_s"].add(p_ds, da_ys)
            d_iou = acc["drv_f"].add(p_df, da_l < 2)
            direct = da_l == 0
            ego = a.masks["ego_lane_mask"].astype(bool)
            # review fix: always pool (false positives on images without 'direct' GT must count);
            # the per-image value stays None for those images
            e_iou = acc["ego"].add(ego, direct)
            if not direct.any():
                e_iou = None
            f = f_tol(p_lf, ll, kernels)
            attrs = g["attrs"]
            for key in (f"time:{attrs.get('timeofday')}", f"scene:{attrs.get('scene')}", "all"):
                sl = slices[key]
                sl["lane"].add(p_ls, ll_s)
                sl["drv"].add(p_df, da_l < 2)
                sl["n"] += 1
            st = a.lane_state
            # lane state again with GT vehicle boxes as occluders (stand-in for the section 7 detector)
            boxes = [[l["box2d"][k] for k in ("x1", "y1", "x2", "y2")] for l in g["labels"]
                     if l.get("box2d") and l.get("category") in VEHICLES]
            st_occ = det.analyze(frame, full_res_masks=False, margins=m, occluder_boxes=boxes).lane_state
            recs.append({"name": stem, "timeofday": attrs.get("timeofday"), "scene": attrs.get("scene"),
                         "weather": attrs.get("weather"), "lane_iou_640": round(iou_s, 4),
                         **{f"lane_F{r}": (None if f[r] is None else round(f[r], 4)) for r in RADII},
                         "drv_iou": round(d_iou, 4), "ego_vs_direct_iou": None if e_iou is None else round(e_iou, 4),
                         "pred": (st.laneCount, st.currentLane), "conf": st.confidence,
                         "pred_gt_occluders": (st_occ.laneCount, st_occ.currentLane),
                         "oracle": g["oracle"], "manual": g["manual"], "manual_ok": g["manual_ok"],
                         "ego_kinds": a.extras.get("egoBoundaryKinds"), "vp": a.extras.get("vanishingPointFound")})
            if save_pred_dir is not None:
                pd_ = Path(save_pred_dir) / b
                pd_.mkdir(parents=True, exist_ok=True)
                cv2.imwrite(str(pd_ / f"{stem}_lane.png"), a.masks["lane_mask"] * 255)
            if (i + 1) % 50 == 0:
                print(f"  {i + 1}/{len(subset)}  laneIoU640 {acc['lane_s'].iou():.3f}  drvIoU {acc['drv_f'].iou():.3f}",
                      flush=True)
        peak = torch.cuda.max_memory_allocated() / 2 ** 20 if torch.cuda.is_available() else None
        det.close()
        del det
        fvals = {r: [x[f"lane_F{r}"] for x in recs if x[f"lane_F{r}"] is not None] for r in RADII}
        conf = [x["conf"] for x in recs]
        with_state = [x for x in recs if x["pred"][0] is not None]
        res = {
            "n_images": len(recs),
            "lane_iou_640x360": round(acc["lane_s"].iou(), 4),
            "lane_balanced_acc_640x360": round(acc["lane_s"].bal_acc(), 4),
            "lane_iou_640x360_mean_per_image": round(float(np.mean([x["lane_iou_640"] for x in recs])), 4),
            "lane_iou_1280x720": round(acc["lane_f"].iou(), 4),
            "lane_iou_1280x720_ignore_transverse": round(acc["lane_f_ign"].iou(), 4),
            **{f"lane_F_r{r}px_1280x720": round(float(np.mean(v)), 4) for r, v in fvals.items()},
            # bdd100k/eval/lane.py convention: an image with empty GT AND empty prediction scores F = 1
            # (the key above excludes such images instead)
            **{f"lane_F_r{r}px_1280x720_bddconv": round(float(np.mean(
                [1.0 if x[f"lane_F{r}"] is None else x[f"lane_F{r}"] for x in recs])), 4) for r in RADII},
            "lane_F_n_both_empty": sum(1 for x in recs if x[f"lane_F{RADII[0]}"] is None),
            "drivable_iou_640x360_yolopgt": round(acc["drv_s"].iou(), 4),
            "drivable_miou_640x360_yolopgt": round(acc["drv_s"].miou(), 4),
            "drivable_iou_1280x720": round(acc["drv_f"].iou(), 4),
            "drivable_miou_1280x720": round(acc["drv_f"].miou(), 4),
            "ego_lane_vs_direct_iou_1280x720": round(acc["ego"].iou(), 4),
            "slices": {k: {"n": v["n"], "lane_iou_640x360": round(v["lane"].iou(), 4),
                           "drivable_iou_1280x720": round(v["drv"].iou(), 4)}
                       for k, v in sorted(slices.items())},
            "lane_state": {
                "with_estimate": len(with_state),
                "confidence_p50": pct(conf, 50),
                "laneCount_hist": {str(k): sum(1 for x in with_state if x["pred"][0] == k)
                                   for k in sorted({x["pred"][0] for x in with_state})},
                "vs_oracle_gt_masks": summarize_state(recs, "pred", "oracle"),
                "vs_manual_labels": summarize_state(recs, "pred", "manual", "manual_ok"),
                "vs_manual_labels_conf_ge_0.5": summarize_state([x for x in recs if x["conf"] >= 0.5], "pred", "manual",
                                                                 "manual_ok"),
                "with_gt_vehicle_occluders": {
                    "vs_oracle_gt_masks": summarize_state(recs, "pred_gt_occluders", "oracle"),
                    "vs_manual_labels": summarize_state(recs, "pred_gt_occluders", "manual", "manual_ok")},
            },
            "latency_ms_provisional": {k: {"p50": pct(v, 50), "p95": pct(v, 95)} for k, v in sorted(times.items())},
            "torch_peak_alloc_mib": None if peak is None else round(peak, 1),
        }
        results[b] = res
        print(json.dumps({k: v for k, v in res.items() if k not in ("slices", "lane_state", "latency_ms_provisional")}),
              flush=True)
        with open(out_dir / f"per_image_{b}.csv", "w", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=list(recs[0].keys()))
            w.writeheader()
            for r in recs:
                w.writerow(r)
    oracle_counts = [g["oracle"] for g in gts]
    manual_vs_oracle = summarize_state([{"pred": g["oracle"], "manual": g["manual"], "manual_ok": g["manual_ok"]}
                                        for g in gts], "pred", "manual", "manual_ok")
    meta = {
        "block": "lanes",
        "measured_here": True,
        "date": time.strftime("%Y-%m-%d %H:%M"),
        "machine": {"gpu": torch.cuda.get_device_name(0) if torch.cuda.is_available() else None,
                    "torch": torch.__version__, "python": platform.python_version(),
                    "note": "GPU shared with up to 6 other agents during this run: latencies are provisional"},
        "dataset": {"name": "BDD100K val, 250-image stratified subset (data/bdd100k/labels/eval_subset_det.json)",
                    "n": len(subset), "license": "BDD100K research / non-commercial"},
        "gt_protocol": {
            "lane": "YOLOP ll_seg_annotations (val), 1280x720, centre lines cv2 thickness=2 (~3.5 px), all 8 lane "
                    "categories incl. crosswalk/road curb/vertical; 640x360 metrics resize GT with INTER_LINEAR and >1",
            "drivable_640": "YOLOP da_seg_annotations (val), resized INTER_LINEAR and > 1 = drivable "
                            "(direct+alternative), as TLNP+ BDD100K.py",
            "drivable_1280": "data/bdd100k/labels/drivable_masks (0 direct, 1 alternative, 2 background)",
            "ignore_transverse": "21 px zone around laneDirection=vertical / laneType=crosswalk labels excluded",
        },
        "post_config": {k: (list(v) if isinstance(v, tuple) else v) for k, v in vars(PostConfig()).items()},
        "oracle_lane_state": {"with_estimate": sum(1 for o in oracle_counts if o[0] is not None),
                              "vs_manual_labels": manual_vs_oracle},
        "backends": results,
    }
    (out_dir / "metrics.json").write_text(json.dumps(meta, indent=1))
    print("wrote", out_dir / "metrics.json")
    return meta


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--backends", nargs="+", default=list(BACKENDS), choices=list(BACKENDS))
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--out", default=str(OUTPUTS_ROOT / "lanes"))
    a = ap.parse_args()
    run(a.backends, a.limit, a.out)


if __name__ == "__main__":
    main()
