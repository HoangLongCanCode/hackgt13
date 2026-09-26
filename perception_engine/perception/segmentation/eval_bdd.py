"""Evaluate the segmentation block on the local BDD100K subset (measured here).

    .venv\\Scripts\\python.exe -m perception.segmentation.eval_bdd

(run from perception_engine/). Parts:
  A. semantic  : 19-class mIoU / per-class IoU on the 30 sem_seg val pairs
                 (data/bdd100k/images/sem_seg_val), global confusion matrix, ignore=255,
                 bootstrap 95% CI over images, plus provisional latency and peak VRAM.
  B. geometry  : on the 250 val keyframes (data/bdd100k/images/val) against the locally
                 rasterised drivable masks and a lane-label vanishing-point reference:
                 road / ego-component / corridor overlap with drivable area, anchor hit
                 rates, horizon error versus a constant-prior baseline.
Writes outputs/segmentation/metrics.json (merged with the existing file's "video" section) and PNGs.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

os.environ.setdefault("YOLO_AUTOINSTALL", "False")

import cv2
import numpy as np
import torch

from perception.common.video import DATA_ROOT, OUTPUTS_ROOT
from perception.segmentation.geometry import CITYSCAPES_CLASSES
from perception.segmentation.gt_horizon import gt_vanishing_point
from perception.segmentation.semantic import BACKENDS, SemanticSegmenter, colorize

OUT = OUTPUTS_ROOT / "segmentation"
SEMSEG = DATA_ROOT / "images" / "sem_seg_val"
GT_OCCLUDERS = {"car", "truck", "bus", "train", "person", "rider", "bike", "motor"}
AR_CORE = ["road", "sidewalk", "building", "vegetation", "terrain", "car", "truck", "bus", "person", "rider"]
INK, INK2, SURF = "#0b0b0b", "#52514e", "#fcfcfb"
SERIES = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300"]


def log(*a):
    print(*a, flush=True)


def semseg_pairs():
    imgs = sorted((SEMSEG / "images" / "val").glob("*.jpg"))
    return [(p, SEMSEG / "labels" / "val" / (p.stem + "_train_id.png")) for p in imgs]


def iou_from_inter_union(inter, union):
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(union > 0, inter / np.maximum(union, 1), np.nan)


def summarize_conf(conf: np.ndarray) -> dict:
    tp = np.diag(conf).astype(float)
    gt = conf.sum(1).astype(float)
    pr = conf.sum(0).astype(float)
    union = gt + pr - tp
    iou = iou_from_inter_union(tp, union)
    present = gt > 0
    return {
        "mIoU19": float(np.nanmean(iou)) * 100,
        "mIoU_gtPresent": float(np.nanmean(iou[present])) * 100,
        "mIoU_ARcore": float(np.nanmean([iou[CITYSCAPES_CLASSES.index(c)] for c in AR_CORE])) * 100,
        "pixelAcc": float(tp.sum() / max(gt.sum(), 1)) * 100,
        "roadIoU": float(iou[0]) * 100,
        "perClassIoU": {c: (None if np.isnan(v) else round(float(v) * 100, 2)) for c, v in zip(CITYSCAPES_CLASSES, iou)},
        "classesWithGT": int(present.sum()),
    }


def bootstrap_ci(per_img_conf: np.ndarray, n: int = 2000, seed: int = 0) -> dict:
    rng = np.random.default_rng(seed)
    N = len(per_img_conf)
    m19, road = [], []
    for _ in range(n):
        c = per_img_conf[rng.integers(0, N, N)].sum(0)
        s = summarize_conf(c)
        m19.append(s["mIoU19"]); road.append(s["roadIoU"])
    q = lambda a: [round(float(np.percentile(a, 2.5)), 2), round(float(np.percentile(a, 97.5)), 2)]
    return {"mIoU19_ci95": q(m19), "roadIoU_ci95": q(road), "resamples": n}


def time_segment(seg: SemanticSegmenter, frames: list[np.ndarray], warmup: int, iters: int) -> dict:
    for i in range(warmup):
        seg.segment(frames[i % len(frames)])
    ts = []
    for i in range(iters):
        if seg.device.type == "cuda":
            torch.cuda.synchronize()
        t = time.perf_counter()
        seg.segment(frames[i % len(frames)])  # ends with .cpu() -> synchronised
        ts.append((time.perf_counter() - t) * 1000)
    return {"p50_ms": round(float(np.median(ts)), 2), "p95_ms": round(float(np.percentile(ts, 95)), 2),
            "iters": iters}


def run_semantic(backend: str, scale: float, args) -> dict:
    pairs = semseg_pairs()
    if seg_cuda():
        torch.cuda.reset_peak_memory_stats()
    seg = make_segmenter(backend, input_scale=scale)
    conf = np.zeros((19, 19), np.int64)
    per_img = []
    preds = {}
    for ip, lp in pairs:
        img = cv2.imread(str(ip))
        gt = cv2.imread(str(lp), cv2.IMREAD_UNCHANGED)
        pred = seg.segment(img)
        m = gt != 255
        c = np.bincount(gt[m].astype(np.int64) * 19 + pred[m], minlength=361).reshape(19, 19)
        conf += c
        per_img.append(c)
        preds[ip.stem] = pred
    res = summarize_conf(conf)
    res.update(bootstrap_ci(np.stack(per_img)))
    frames = [cv2.imread(str(p)) for p, _ in pairs[:8]]
    res["latency_segment_1280x720"] = time_segment(seg, frames, args.warmup, args.iters)
    h, w = frames[0].shape[:2]
    res["networkInput"] = f"{int(round(w * scale))}x{int(round(h * scale))}" + \
        (f" padded to /{seg.pad}" if seg.pad > 1 else "")
    res["peakVRAM_MB"] = int(torch.cuda.max_memory_allocated() / 2**20) if seg_cuda() else None
    res["fp16"] = seg.fp16
    res["N_images"] = len(pairs)
    seg.release()
    del seg
    return res, preds


def seg_cuda():
    return torch.cuda.is_available()


def make_segmenter(backend, **kw):
    """CUDA OOM on the shared GPU: retry once after 60 s (task rule)."""
    try:
        return SemanticSegmenter(backend=backend, device="cuda", **kw)
    except torch.cuda.OutOfMemoryError:
        log("CUDA OOM while loading", backend, "- retrying in 60 s")
        torch.cuda.empty_cache()
        time.sleep(60)
        return SemanticSegmenter(backend=backend, device="cuda", **kw)


# ----------------------------------------------------------------------------- geometry
def raster_poly(poly, H, W):
    m = np.zeros((H, W), np.uint8)
    if poly and len(poly) >= 3:
        cv2.fillPoly(m, [np.round(np.array(poly)).astype(np.int32)], 1)
    return m.astype(bool)


def ego_component_mask(geo, H, W):
    return raster_poly(geo.egoPathPolygon, H, W)


def direct_lane_centre_err(direct, x, y):
    """|x - centre of the nearest GT 'direct' run| on the anchor's row, px (None if the row has none)."""
    row = direct[y]
    if not row.any():
        return None
    d = np.diff(np.concatenate(([0], row.astype(np.int8), [0])))
    st, en = np.flatnonzero(d == 1), np.flatnonzero(d == -1)
    return float(np.min(np.abs((st + en - 1) / 2.0 - x)))


def run_geometry(backend: str, args, keep_examples=None) -> dict:
    labels = json.load(open(DATA_ROOT / "labels" / "eval_subset_det.json", encoding="utf-8"))
    seg = make_segmenter(backend)
    rows = []
    geo_ms, seg_ms = [], []
    examples = {}
    for e in labels:
        name = e["name"]
        img = cv2.imread(str(DATA_ROOT / "images" / "val" / name))
        dm = cv2.imread(str(DATA_ROOT / "labels" / "drivable_masks" / (Path(name).stem + ".png")), cv2.IMREAD_UNCHANGED)
        H, W = img.shape[:2]
        lab = seg.segment(img)
        seg_ms.append(seg.timings_ms["segment"])
        geo = seg.road_geometry(img, seg=lab)
        geo_ms.append(seg.timings_ms["road_geometry"])
        info = seg.last_info
        drv = dm <= 1
        direct = dm == 0
        drv_near = cv2.dilate(drv.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (21, 21))).astype(bool)
        road = lab == 0
        comp = ego_component_mask(geo, H, W)
        corr = raster_poly(info.get("egoCorridorPolygon"), H, W)
        r = {"name": name, "tod": e["attributes"].get("timeofday"), "scene": e["attributes"].get("scene"),
             "weather": e["attributes"].get("weather"),
             "hasDrivable": bool(drv.any()),
             "road_inter": int((road & drv).sum()), "road_union": int((road | drv).sum()),
             "road_area": int(road.sum()), "drv_area": int(drv.sum()),
             "comp_inter": int((comp & drv).sum()), "comp_union": int((comp | drv).sum()), "comp_area": int(comp.sum()),
             "corr_in_drv": int((corr & drv).sum()), "corr_in_direct": int((corr & direct).sum()), "corr_area": int(corr.sum()),
             "method": info.get("horizonRaw", {}).get("method"), "horizonY": geo.horizonY,
             "candidates": info.get("candidates", {})}
        gv = gt_vanishing_point(e["labels"], W, H)
        r["gtVP"] = gv
        boxes = [l["box2d"] for l in e["labels"] if "box2d" in l and l["category"] in GT_OCCLUDERS]
        for a in geo.anchorPoints:
            if "distanceM" not in a:
                continue
            d = int(a["distanceM"])
            r[f"a{d}_valid"] = bool(a["valid"])
            r[f"a{d}_drawable"] = bool(a["valid"] and a["occludedBy"] is None)
            r[f"a{d}_conf"] = float(a.get("confidence") or 0.0)
            if a["valid"] and a["xy"]:
                x, y = int(round(a["xy"][0])), int(round(a["xy"][1]))
                x, y = min(max(x, 0), W - 1), min(max(y, 0), H - 1)
                r[f"a{d}_inDrv"] = bool(drv[y, x])
                r[f"a{d}_nearDrv"] = bool(drv_near[y, x])
                r[f"a{d}_inDirect"] = bool(direct[y, x])
                r[f"a{d}_inGTbox"] = any(b["x1"] <= x <= b["x2"] and b["y1"] <= y <= b["y2"] for b in boxes)
                r[f"a{d}_laneErr"] = direct_lane_centre_err(direct, x, y)  # review addition
        rows.append(r)
        if keep_examples is not None and name in keep_examples:
            examples[name] = (img, lab, geo, dict(info), gv, dm)
    seg.release()
    del seg
    return summarize_geometry(rows, geo_ms, seg_ms), rows, examples


def _horizon_stats(err):
    err = np.abs(np.asarray(err, float))
    if len(err) == 0:
        return None
    return {"N": int(len(err)), "median_px": round(float(np.median(err)), 1), "mean_px": round(float(err.mean()), 1),
            "p90_px": round(float(np.percentile(err, 90)), 1),
            "within10px": round(float((err <= 10).mean()) * 100, 1), "within20px": round(float((err <= 20).mean()) * 100, 1)}


def summarize_geometry(rows, geo_ms, seg_ms, prior_y=None):
    from perception.segmentation.semantic import BDD_HORIZON_PRIOR_Y_720
    prior_y = prior_y or BDD_HORIZON_PRIOR_Y_720
    out = {"N_images": len(rows)}
    rd = [r for r in rows if r["hasDrivable"]]
    out["N_withDrivable"] = len(rd)
    si = lambda k1, k2, rr: (100.0 * sum(r[k1] for r in rr) / max(1, sum(r[k2] for r in rr)))
    out["roadClass_vs_drivable_IoU"] = round(si("road_inter", "road_union", rd), 2)
    out["roadClass_recall_of_drivable"] = round(si("road_inter", "drv_area", rd), 2)
    out["roadClass_precision_in_drivable"] = round(si("road_inter", "road_area", rd), 2)
    out["egoComponent_vs_drivable_IoU"] = round(si("comp_inter", "comp_union", rd), 2)
    out["egoComponent_precision_in_drivable"] = round(si("comp_inter", "comp_area", rd), 2)
    out["egoCorridor_precision_in_drivable"] = round(si("corr_in_drv", "corr_area", rd), 2)
    out["egoCorridor_precision_in_direct"] = round(si("corr_in_direct", "corr_area", rd), 2)
    anchors = {}
    pct = lambda xs: round(100.0 * float(np.mean(xs)), 1) if len(xs) else None
    for d in (10, 20, 40):
        v = [r for r in rd if r.get(f"a{d}_valid")]
        dr = [r for r in v if r.get(f"a{d}_drawable")]
        oc = [r for r in v if not r.get(f"a{d}_drawable")]
        anchors[f"{d}m"] = {
            "validRate": pct([bool(r.get(f"a{d}_valid")) for r in rd]),
            "drawableRate": pct([bool(r.get(f"a{d}_drawable")) for r in rd]),
            "drawable_inDrivable": pct([r[f"a{d}_inDrv"] for r in dr]),
            "drawable_within10px_of_drivable": pct([r[f"a{d}_nearDrv"] for r in dr]),
            "drawable_inDirect": pct([r[f"a{d}_inDirect"] for r in dr]),
            "drawable_medianErrToDirectLaneCentre_px": (lambda e: round(float(np.median(e)), 1) if e else None)(
                [r[f"a{d}_laneErr"] for r in dr if r.get(f"a{d}_laneErr") is not None]),
            "drawableConf>=0.5_rate": pct([bool(r.get(f"a{d}_drawable") and r[f"a{d}_conf"] >= 0.5) for r in rd]),
            "drawableConf>=0.5_inDrivable": pct([r[f"a{d}_inDrv"] for r in dr if r[f"a{d}_conf"] >= 0.5]),
            "occludedFlagged_inGTbox": pct([r[f"a{d}_inGTbox"] for r in oc]),
            "valid_inDrivable_or_GTbox": pct([r[f"a{d}_inDrv"] or r[f"a{d}_inGTbox"] for r in v]),
        }
    out["anchors_on_images_with_drivable_GT"] = anchors
    hv = [r for r in rows if r["gtVP"]]
    out["horizon"] = {
        "reference": "median pairwise intersection of straight 'parallel' lane labels (gt_horizon.py)",
        "estimator": _horizon_stats([r["horizonY"] - r["gtVP"]["y"] for r in hv]),
        "constantPriorBaseline": _horizon_stats([prior_y - r["gtVP"]["y"] for r in hv]),
        "byMethod": {},
        "candidateAccuracy": {},
        "methodShare_allImages": {},
    }
    for m in sorted(set(r["method"] for r in rows if r["method"])):
        out["horizon"]["methodShare_allImages"][m] = round(100.0 * sum(r["method"] == m for r in rows) / len(rows), 1)
        sub = [r for r in hv if r["method"] == m]
        if sub:
            out["horizon"]["byMethod"][m] = _horizon_stats([r["horizonY"] - r["gtVP"]["y"] for r in sub])
    for c in ("lines", "two_edges", "single_edge", "road_top"):
        sub = [r for r in hv if c in (r["candidates"] or {})]
        if sub:
            out["horizon"]["candidateAccuracy"][c] = _horizon_stats([r["candidates"][c] - r["gtVP"]["y"] for r in sub])
            out["horizon"]["candidateAccuracy"][c]["priorOnSameImages"] = _horizon_stats(
                [prior_y - r["gtVP"]["y"] for r in sub])["median_px"]
    by_tod = {}
    for tod in sorted(set(r["tod"] for r in rows)):
        rr = [r for r in rd if r["tod"] == tod]
        hh = [r for r in hv if r["tod"] == tod]
        by_tod[tod] = {"N": len([r for r in rows if r["tod"] == tod]),
                       "roadClass_vs_drivable_IoU": round(si("road_inter", "road_union", rr), 2) if rr else None,
                       "egoCorridor_precision_in_drivable": round(si("corr_in_drv", "corr_area", rr), 2) if rr else None,
                       "anchor20m_drawableRate": pct([bool(r.get("a20_drawable")) for r in rr]),
                       "anchor10m_drawable_inDrivable": pct([r["a10_inDrv"] for r in rr if r.get("a10_drawable")]),
                       "horizon_median_err_px": _horizon_stats([r["horizonY"] - r["gtVP"]["y"] for r in hh])["median_px"] if hh else None}
    out["byTimeOfDay"] = by_tod
    out["latency_road_geometry_cpu"] = {"p50_ms": round(float(np.median(geo_ms)), 2),
                                        "p95_ms": round(float(np.percentile(geo_ms, 95)), 2)}
    out["latency_segment_p50_ms_during_geometry_run"] = round(float(np.median(seg_ms)), 2)
    return out


# ----------------------------------------------------------------------------- plots
def plot_per_class(sem: dict, path: Path, backends: list[str]):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    bks = [b for b in backends if b in sem][:4]
    classes = [c for c in CITYSCAPES_CLASSES]
    fig, ax = plt.subplots(figsize=(13, 4.6), facecolor=SURF)
    ax.set_facecolor(SURF)
    w = 0.8 / len(bks)
    for i, b in enumerate(bks):
        vals = [sem[b]["perClassIoU"][c] if sem[b]["perClassIoU"][c] is not None else 0 for c in classes]
        ax.bar(np.arange(len(classes)) + (i - (len(bks) - 1) / 2) * w, vals, w * 0.9, color=SERIES[i],
               label=f"{b} (mIoU {sem[b]['mIoU19']:.1f})", zorder=3)
    ax.set_xticks(np.arange(len(classes)), classes, rotation=40, ha="right", color=INK2, fontsize=9)
    ax.set_ylabel("IoU (%)", color=INK2)
    ax.set_ylim(0, 100)
    ax.grid(axis="y", color="#e4e3df", zorder=0)
    for sp in ("top", "right"):
        ax.spines[sp].set_visible(False)
    for sp in ("left", "bottom"):
        ax.spines[sp].set_color("#c9c8c2")
    ax.tick_params(colors=INK2)
    ax.set_title("Per-class IoU on 30 BDD100K sem_seg val images (measured here; train/motorcycle absent from GT)",
                 color=INK, fontsize=11, loc="left")
    ax.legend(frameon=False, fontsize=9, labelcolor=INK)
    fig.tight_layout()
    fig.savefig(path, dpi=110, facecolor=SURF)
    plt.close(fig)


def plot_speed_accuracy(sem: dict, path: Path):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, ax = plt.subplots(figsize=(7, 4.4), facecolor=SURF)
    ax.set_facecolor(SURF)
    for k, r in sem.items():
        x, y = r["latency_segment_1280x720"]["p50_ms"], r["mIoU19"]
        lo, hi = r["mIoU19_ci95"]
        ax.errorbar(x, y, yerr=[[y - lo], [hi - y]], fmt="o", color=SERIES[0], ms=8, capsize=3, zorder=3)
        ax.annotate(k, (x, y), textcoords="offset points", xytext=(8, 4), fontsize=9, color=INK)
    ax.set_xlabel("segment() p50 latency, ms (1280x720 frame, shared GPU, provisional)", color=INK2)
    ax.set_ylabel("mIoU19 (%), 95% bootstrap CI", color=INK2)
    ax.grid(color="#e4e3df", zorder=0)
    for sp in ("top", "right"):
        ax.spines[sp].set_visible(False)
    ax.tick_params(colors=INK2)
    ax.set_title("Speed vs accuracy (N=30 sem_seg val)", color=INK, fontsize=11, loc="left")
    fig.tight_layout()
    fig.savefig(path, dpi=110, facecolor=SURF)
    plt.close(fig)


def plot_horizon_hist(rows, path: Path):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from perception.segmentation.semantic import BDD_HORIZON_PRIOR_Y_720
    hv = [r for r in rows if r["gtVP"]]
    e1 = [r["horizonY"] - r["gtVP"]["y"] for r in hv]
    e0 = [BDD_HORIZON_PRIOR_Y_720 - r["gtVP"]["y"] for r in hv]
    fig, ax = plt.subplots(figsize=(7, 4), facecolor=SURF)
    ax.set_facecolor(SURF)
    bins = np.arange(-150, 151, 10)
    ax.hist(np.clip(e0, -150, 150), bins=bins, color=SERIES[1], alpha=0.55, label=f"constant prior (median |err| {np.median(np.abs(e0)):.0f} px)", zorder=3)
    ax.hist(np.clip(e1, -150, 150), bins=bins, color=SERIES[0], alpha=0.75, label=f"estimator (median |err| {np.median(np.abs(e1)):.0f} px)", zorder=3)
    ax.set_xlabel("horizonY - lane-label VP y (px, 720p; clipped to +/-150)", color=INK2)
    ax.set_ylabel("images", color=INK2)
    ax.grid(axis="y", color="#e4e3df", zorder=0)
    for sp in ("top", "right"):
        ax.spines[sp].set_visible(False)
    ax.tick_params(colors=INK2)
    ax.set_title(f"Horizon error on {len(hv)} BDD100K val keyframes with a lane-label VP", color=INK, fontsize=11, loc="left")
    ax.legend(frameon=False, fontsize=9, labelcolor=INK)
    fig.tight_layout()
    fig.savefig(path, dpi=110, facecolor=SURF)
    plt.close(fig)


def semseg_grid(preds_by_backend: dict, backends: list[str], path: Path, n: int = 6):
    pairs = semseg_pairs()
    idx = np.linspace(0, len(pairs) - 1, n).round().astype(int)
    tiles = []
    for i in idx:
        ip, lp = pairs[i]
        img = cv2.imread(str(ip))
        gt = cv2.imread(str(lp), cv2.IMREAD_UNCHANGED)
        row = [img, colorize(gt)] + [colorize(preds_by_backend[b][ip.stem]) for b in backends]
        row = [cv2.resize(t, (384, 216)) for t in row]
        tiles.append(np.hstack(row))
    grid = np.vstack(tiles)
    hdr = np.full((28, grid.shape[1], 3), 252, np.uint8)
    for j, t in enumerate(["image", "ground truth"] + backends):
        cv2.putText(hdr, t, (8 + j * 384, 20), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (11, 11, 11), 1, cv2.LINE_AA)
    cv2.imwrite(str(path), np.vstack([hdr, grid]))


def geometry_grid(examples: dict, path: Path, backend: str):
    from perception.segmentation.viz import draw_overlay
    tiles = []
    for name, (img, lab, geo, info, gv, dm) in examples.items():
        o = draw_overlay(img, lab, geo, info, hud=f"{name}  {backend}  horizon={info['horizonRaw']['method']}"
                         + (f"  err={geo.horizonY - gv['y']:+.0f}px" if gv else ""))
        cnts, _ = cv2.findContours((dm <= 1).astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        cv2.drawContours(o, cnts, -1, (255, 0, 255), 1, cv2.LINE_AA)  # GT drivable outline
        if gv:
            cv2.drawMarker(o, (int(gv["x"]), int(gv["y"])), (255, 0, 255), cv2.MARKER_TILTED_CROSS, 24, 2)
        tiles.append(cv2.resize(o, (640, 360)))
    while len(tiles) % 2:
        tiles.append(np.zeros_like(tiles[0]))
    cv2.imwrite(str(path), np.vstack([np.hstack(tiles[i:i + 2]) for i in range(0, len(tiles), 2)]))


# ----------------------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--backends", default="pidnet_s,efficientvit_b0,efficientvit_b1,efficientvit_b2,segformer_b0,segformer_b2")
    ap.add_argument("--scales", default="1.0", help="extra input scales for pidnet_s/efficientvit_b1, e.g. 1.0,1.5")
    ap.add_argument("--geometry-backends", default="efficientvit_b1,pidnet_s,efficientvit_b2,segformer_b0")
    ap.add_argument("--warmup", type=int, default=10)
    ap.add_argument("--iters", type=int, default=50)
    ap.add_argument("--skip-semantic", action="store_true")
    ap.add_argument("--skip-geometry", action="store_true")
    args = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    mpath = OUT / "metrics.json"
    metrics = json.load(open(mpath, encoding="utf-8")) if mpath.exists() else {}
    metrics["block"] = "segmentation"
    metrics["device"] = torch.cuda.get_device_name(0) if torch.cuda.is_available() else "cpu"
    metrics["note"] = ("measured here on a GPU shared with other agents; latencies are provisional. "
                       "N=30 sem_seg images -> wide CIs.")
    backends = [b for b in args.backends.split(",") if b]
    scales = [float(s) for s in args.scales.split(",")]
    if not args.skip_semantic:
        sem = metrics.get("semantic", {})
        preds_all = {}
        for b in backends:
            for sc in scales:
                if sc != 1.0 and b not in ("pidnet_s", "efficientvit_b1"):
                    continue
                key = b if sc == 1.0 else f"{b}@x{sc}"
                log("semantic", key)
                try:
                    r, preds = run_semantic(b, sc, args)
                except Exception as ex:  # report, keep going
                    log("FAILED", key, repr(ex))
                    sem[key] = {"error": repr(ex)}
                    continue
                sem[key] = r
                if sc == 1.0:
                    preds_all[b] = preds
                log(f"  mIoU19 {r['mIoU19']:.2f} CI {r['mIoU19_ci95']}  road {r['roadIoU']:.1f}  "
                    f"p50 {r['latency_segment_1280x720']['p50_ms']} ms  VRAM {r['peakVRAM_MB']} MB")
                torch.cuda.empty_cache()
        metrics["semantic"] = sem
        ok = {k: v for k, v in sem.items() if "error" not in v}
        plot_per_class(ok, OUT / "per_class_iou.png", [b for b in ["pidnet_s", "efficientvit_b1", "efficientvit_b2", "segformer_b2"] if b in ok])
        plot_speed_accuracy(ok, OUT / "speed_vs_miou.png")
        shown = [b for b in ["pidnet_s", "efficientvit_b1", "segformer_b2"] if b in preds_all]
        if shown:
            semseg_grid(preds_all, shown, OUT / "semseg_examples.png")
    if not args.skip_geometry:
        geo_all = metrics.get("geometry", {})
        ex_names = ["b1f4491b-cf446195.jpg", "b1ff4656-0435391e.jpg", "b20e291a-6012d836.jpg",
                    "b23adb0d-8a7aaced.jpg", "b204a5c1-064b0040.jpg", "b1d968b9-ce42734f.jpg",
                    "b2064e61-2beadd45.jpg", "bc07d865-f526e4d9.jpg"]
        for i, b in enumerate([x for x in args.geometry_backends.split(",") if x]):
            log("geometry", b)
            g, rows, examples = run_geometry(b, args, keep_examples=set(ex_names) if i == 0 else None)
            geo_all[b] = g
            log("  ", json.dumps({k: g[k] for k in ("egoComponent_precision_in_drivable", "egoCorridor_precision_in_drivable")}),
                json.dumps(g["horizon"]["estimator"]), json.dumps(g["horizon"]["constantPriorBaseline"]))
            if i == 0:
                plot_horizon_hist(rows, OUT / "horizon_error_hist.png")
                geometry_grid({k: examples[k] for k in ex_names if k in examples}, OUT / "geometry_examples.png", b)
                with open(OUT / f"geometry_rows_{b}.jsonl", "w", encoding="utf-8") as f:
                    for r in rows:
                        f.write(json.dumps(r) + "\n")
            torch.cuda.empty_cache()
        metrics["geometry"] = geo_all
    with open(mpath, "w", encoding="utf-8") as f:
        json.dump(metrics, f, indent=1)
    log("wrote", mpath)


if __name__ == "__main__":
    main()
