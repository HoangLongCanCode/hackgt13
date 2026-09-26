"""Reproduce the tracking-block numbers on BDD100K MOT val (5 fps, 7 local sequences).

    cd perception_engine
    .venv\\Scripts\\python.exe -m perception.tracking.eval_bdd            # all stages
    .venv\\Scripts\\python.exe -m perception.tracking.eval_bdd --stages dets track native ttc report

Stages
  dets    run the YOLO26s-BDD detector once on every MOT frame and cache the boxes
          (outputs/tracking/cache/dets_*.npz), so every tracker sees identical detections
  track   run every Tracker backend (library defaults and harmonised thresholds) on the
          cached detections, then BDD MOT metrics (mot_eval.py) and tracker.update latency
  native  UltralyticsTrackPipeline (model.track persist=True), ReID off/on
  ttc     scale-rate / approaching agreement with GT-box-derived values (no TTC GT exists)
  report  metrics.json + PNG charts
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

os.environ.setdefault("YOLO_AUTOINSTALL", "False")

import cv2  # noqa: E402
import numpy as np  # noqa: E402

from perception.common.paths import portable_paths  # noqa: E402
from perception.common.schemas import Detection  # noqa: E402
from perception.common.video import DATA_ROOT, OUTPUTS_ROOT  # noqa: E402
from perception.tracking.mot_eval import BddMotEvaluator, load_gt  # noqa: E402

OUT = OUTPUTS_ROOT / "tracking"
CACHE = OUT / "cache"
GT_DIR = DATA_ROOT / "labels" / "box_track_20" / "val"
IMG_DIR = DATA_ROOT / "mot" / "images" / "track" / "val"
FPS = 5.0
SEQS = ["b1f4491b-cf446195", "b1ff4656-0435391e", "b20e291a-6012d836", "b23adb0d-8a7aaced",
        "b204a5c1-064b0040", "b1d968b9-ce42734f", "b2064e61-2beadd45"]
DET_TAG = "yolo26s_bdd_960"
# Settings shared by the backend grid and the threshold sweep. They were fixed before the
# ablations chose the Tracker defaults (2.0 s buffer, 0.5 s class vote). At 5 fps a
# 3.0 s vote is 15 observations.
GRID = {"track_buffer_s": 1.0, "class_vote_s": 3.0}


def available_seqs() -> list[str]:
    out = []
    for s in SEQS:
        if (GT_DIR / f"{s}.json").exists() and (IMG_DIR / s).is_dir() and any((IMG_DIR / s).glob("*.jpg")):
            out.append(s)
    return out


def p50(xs):
    return float(np.percentile(xs, 50)) if len(xs) else None


def p90(xs):
    return float(np.percentile(xs, 90)) if len(xs) else None


# ----------------------------------------------------------------------------- stage: dets
def stage_dets(seqs, device="cuda", imgsz=960, conf=0.05) -> dict:
    import torch

    from perception.tracking.detector import YoloDetector

    CACHE.mkdir(parents=True, exist_ok=True)
    det = YoloDetector(device=device, imgsz=imgsz, conf=conf)
    names = det.names
    lat = []
    for s in seqs:
        gt = load_gt(GT_DIR / f"{s}.json")
        fi, boxes, confs, cls = [], [], [], []
        for g in gt:
            img = cv2.imread(str(IMG_DIR / s / g["name"]))
            assert img is not None and img.shape == (720, 1280, 3), g["name"]
            d = det(img)
            lat.append(det.last_ms)
            for x in d:
                fi.append(g["frameIndex"]); boxes.append(x.bbox); confs.append(x.confidence); cls.append(x.cls)
        np.savez_compressed(CACHE / f"dets_{DET_TAG}_{s}.npz", frame=np.asarray(fi, int),
                            boxes=np.asarray(boxes, np.float32).reshape(-1, 4), conf=np.asarray(confs, np.float32),
                            cls=np.asarray(cls, dtype="U16"))
        print(f"[dets] {s}: {len(gt)} frames, {len(fi)} boxes", flush=True)
    info = {"weights": det.weights, "imgsz": imgsz, "conf": conf, "half": det.half, "device": device,
            "gpu": torch.cuda.get_device_name(0) if torch.cuda.is_available() else None,
            "latency_ms_p50": p50(lat[5:]), "latency_ms_p90": p90(lat[5:]), "frames": len(lat),
            "peak_vram_mb": torch.cuda.max_memory_allocated() / 2**20 if torch.cuda.is_available() else None,
            "names": names}
    json.dump(info, open(CACHE / f"dets_{DET_TAG}_info.json", "w"), indent=1)
    del det
    torch.cuda.empty_cache()
    return info


def load_dets(seq) -> dict[int, list[Detection]]:
    z = np.load(CACHE / f"dets_{DET_TAG}_{seq}.npz")
    out: dict[int, list[Detection]] = {}
    for f, b, c, k in zip(z["frame"], z["boxes"], z["conf"], z["cls"]):
        out.setdefault(int(f), []).append(Detection(cls=str(k), bbox=[float(v) for v in b], confidence=float(c)))
    return out


# ----------------------------------------------------------------------------- stage: track
def run_tracker_on_seq(make, seq, gt, dets, need_frames=True):
    trk = make()
    need_frames = need_frames and getattr(trk, "needs_frame", True)
    pred, lat, states = {}, [], {}
    for g in gt:
        img = cv2.imread(str(IMG_DIR / seq / g["name"])) if need_frames else None
        ds = [Detection(d.cls, list(d.bbox), d.confidence) for d in dets.get(g["frameIndex"], [])]
        t0 = time.perf_counter()
        ts = trk.update(ds, img, g["frameIndex"] / FPS)
        lat.append((time.perf_counter() - t0) * 1000)
        pred[g["frameIndex"]] = ([t.id for t in ts], [t.cls for t in ts], [t.bbox for t in ts])
        states[g["frameIndex"]] = ts
    return pred, lat, states


def eval_config(name, make, seqs, gts, dets_all, need_frames=True, keep_states=False):
    ev = BddMotEvaluator()
    lat_all, all_states = [], {}
    t0 = time.time()
    for s in seqs:
        pred, lat, states = run_tracker_on_seq(make, s, gts[s], dets_all[s], need_frames)
        ev.add_sequence(s, gts[s], pred)
        lat_all += lat[3:]
        if keep_states:
            all_states[s] = states
    m = ev.compute()
    m["update_ms_p50"] = p50(lat_all)
    m["update_ms_p90"] = p90(lat_all)
    m["wall_s"] = round(time.time() - t0, 1)
    print(f"[track] {name:34s} mMOTA {m['mMOTA']*100:6.2f} mIDF1 {m['mIDF1']*100:6.2f} "
          f"MOTA {m['overall']['mota']*100:6.2f} IDF1 {m['overall']['idf1']*100:6.2f} "
          f"IDSW {m['overall']['num_switches']:5.0f} mHOTA {(m['mHOTA'] or 0)*100:6.2f} "
          f"upd {m['update_ms_p50']:.2f} ms ({m['wall_s']} s)", flush=True)
    return (m, all_states) if keep_states else m


def stage_track(seqs, gts, dets_all, backends=None, sweep=False) -> dict:
    from perception.tracking.tracker import Tracker

    backends = backends or list(Tracker.BACKENDS)
    res = {}
    for b in backends:
        for mode, harm in (("default", False), ("harmonised", True)):
            name = f"{b}:{mode}"
            try:
                mk = lambda b=b, h=harm: Tracker(b, frame_rate=FPS, harmonised=h, **GRID)
                res[name] = eval_config(name, mk, seqs, gts, dets_all)
                res[name]["config"] = mk().config
            except Exception as e:  # report, never hide
                import traceback
                res[name] = {"error": f"{type(e).__name__}: {e}", "traceback": traceback.format_exc()[-2000:]}
                print(f"[track] {name} FAILED: {e}", flush=True)
    return res


def stage_ablate(seqs, gts, dets_all, backend) -> dict:
    """Ablations on the chosen backend (harmonised thresholds): buffer, class vote, GMC downscale."""
    from perception.tracking.tracker import Tracker

    out = {}
    for tb in (0.6, 1.0, 2.0, 3.0):
        out[f"track_buffer_s={tb} (vote 3.0 s)"] = eval_config(
            f"{backend} buffer {tb}s", lambda tb=tb: Tracker(backend, frame_rate=FPS, track_buffer_s=tb,
                                                             class_vote_s=3.0), seqs, gts, dets_all)
    for cv in (0.0, 0.5, 1.0):
        out[f"class_vote_s={cv} (buffer 2.0 s)"] = eval_config(
            f"{backend} vote {cv}s", lambda cv=cv: Tracker(backend, frame_rate=FPS, track_buffer_s=2.0,
                                                          class_vote_s=cv), seqs, gts, dets_all)
    if backend.startswith("ul_") or backend in ("rf_botsort", "rf_mcbyte"):
        key = "gmc_downscale" if backend.startswith("ul_") else "cmc_downscale"
        out[f"{key}=4 (Tracker defaults otherwise)"] = eval_config(
            f"{backend} {key} 4", lambda: Tracker(backend, frame_rate=FPS, **{key: 4}), seqs, gts, dets_all)
    return out


def stage_default(seqs, gts, dets_all, backends) -> dict:
    """Each candidate with the Tracker's own defaults (harmonised, 2.0 s buffer, 0.5 s vote)."""
    from perception.tracking.tracker import Tracker

    out = {}
    for b in backends:
        mk = lambda b=b: Tracker(b, frame_rate=FPS)
        out[b] = eval_config(f"{b} (Tracker defaults)", mk, seqs, gts, dets_all)
        out[b]["config"] = mk().config
    return out


def stage_sweep(seqs, gts, dets_all) -> dict:
    """Threshold sweep used to pick the harmonised values (reported, optimistic: same data)."""
    from perception.tracking.tracker import Tracker

    out = {}
    for hi, new in ((0.25, 0.25), (0.3, 0.4), (0.4, 0.5), (0.5, 0.6), (0.6, 0.7)):
        for b in ("ul_bytetrack", "ul_botsort"):
            n = f"{b} high={hi} new={new}"
            out[n] = eval_config(n, lambda b=b, hi=hi, new=new: Tracker(
                b, frame_rate=FPS, harmonised=False, track_high_thresh=hi, new_track_thresh=new, **GRID),
                seqs, gts, dets_all)
        n = f"rf_bytetrack high={hi} act={new}"
        out[n] = eval_config(n, lambda hi=hi, new=new: Tracker(
            "rf_bytetrack", frame_rate=FPS, harmonised=False, high_conf_det_threshold=hi,
            track_activation_threshold=new, minimum_consecutive_frames=1, **GRID), seqs, gts, dets_all)
    return out


# ----------------------------------------------------------------------------- stage: native
def stage_native(seqs, gts, device="cuda") -> dict:
    import torch

    from perception.tracking.tracker import UltralyticsTrackPipeline

    res = {}
    configs = [("botsort", False), ("botsort", True), ("tracktrack", False), ("tracktrack", True),
               ("bytetrack", False)]
    for tr, reid in configs:
        name = f"native_{tr}{'_reid' if reid else ''}"
        opts = {"with_reid": True, "model": "auto"} if reid else {}
        if tr in ("bytetrack",):
            opts = {}
        try:
            torch.cuda.reset_peak_memory_stats()
            pipe = UltralyticsTrackPipeline(tracker=tr, device=device, frame_rate=FPS, **GRID, **opts)
            ev = BddMotEvaluator()
            lat = []
            t0 = time.time()
            for s in seqs:
                pipe.reset()
                pred = {}
                for g in gts[s]:
                    img = cv2.imread(str(IMG_DIR / s / g["name"]))
                    _, ts = pipe.step(img, g["frameIndex"] / FPS)
                    lat.append(pipe.last_ms)
                    pred[g["frameIndex"]] = ([t.id for t in ts], [t.cls for t in ts], [t.bbox for t in ts])
                ev.add_sequence(s, gts[s], pred)
            m = ev.compute()
            m["step_ms_p50_detector_plus_tracker"] = p50(lat[5:])
            m["peak_vram_mb"] = torch.cuda.max_memory_allocated() / 2**20
            m["tracker_cfg"] = pipe.tracker_cfg
            # which ReID path was actually used?
            p = pipe.model.predictor
            enc = getattr(p.trackers[0], "encoder", None) if p is not None else None
            m["reid_encoder"] = None if enc is None else getattr(enc, "__name__", type(enc).__name__)
            m["reid_cfg_model_after_setup"] = getattr(getattr(p.trackers[0], "args", None), "model", None)
            m["wall_s"] = round(time.time() - t0, 1)
            print(f"[native] {name:24s} mMOTA {m['mMOTA']*100:6.2f} mIDF1 {m['mIDF1']*100:6.2f} "
                  f"MOTA {m['overall']['mota']*100:6.2f} IDF1 {m['overall']['idf1']*100:6.2f} "
                  f"IDSW {m['overall']['num_switches']:5.0f} step {m['step_ms_p50_detector_plus_tracker']:.1f} ms "
                  f"reid={m['reid_encoder']}", flush=True)
            res[name] = m
            pipe.close()
            del pipe
            torch.cuda.empty_cache()
        except Exception as e:
            import traceback
            res[name] = {"error": f"{type(e).__name__}: {e}", "traceback": traceback.format_exc()[-2000:]}
            print(f"[native] {name} FAILED: {e}", flush=True)
    return res


# ----------------------------------------------------------------------------- stage: ttc
def stage_ttc(seqs, gts, dets_all, backend) -> dict:
    """Compare motion cues from tracker output with the same estimator run on GT boxes.

    GT boxes (human-annotated, 5 fps) are the least-noisy reference available. There is
    no TTC ground truth in BDD100K. For every frame where a predicted track matches a GT
    track (IoU >= 0.5, same class), compare scale_rate and the approaching flag, with the
    corridor gate on ("corridor") and off ("any" = the raw closing flag).
    """
    from perception.tracking.mot_eval import iou
    from perception.tracking.motion import MotionEstimator
    from perception.tracking.tracker import Tracker

    out = {"backend": backend,
           "note": "reference = same MotionEstimator on GT boxes (5 fps, 1 s window); not true TTC"}
    for scope in ("corridor", "any"):
        ds, agree, ttc_rel, n_pairs = [], {"tp": 0, "fp": 0, "fn": 0, "tn": 0}, [], 0
        for s in seqs:
            _, _, states = run_tracker_on_seq(
                lambda: Tracker(backend, frame_rate=FPS, motion={"approach_scope": scope}), s, gts[s], dets_all[s])
            me = MotionEstimator(approach_scope=scope)
            for g in gts[s]:
                fi = g["frameIndex"]
                gid = [int(x) for x in g["ids"]]
                gm = me.update(list(zip(gid, g["cls"], g["boxes"].tolist())), fi / FPS, (720, 1280))
                ts = states[fi]
                if not ts or not gid:
                    continue
                m = iou(g["boxes"], np.asarray([t.bbox for t in ts]))
                for gi in range(len(gid)):
                    j = int(np.argmax(m[gi]))
                    if m[gi, j] < 0.5 or ts[j].cls != g["cls"][gi]:
                        continue
                    go, po = gm[gid[gi]], ts[j]
                    if go.scale_rate is None or po.scale_rate is None:
                        continue
                    n_pairs += 1
                    ds.append(po.scale_rate - go.scale_rate)
                    if go.ttc_s is not None and po.ttc_s is not None and go.ttc_s <= 10:
                        ttc_rel.append(abs(po.ttc_s - go.ttc_s) / go.ttc_s)
                    a, b = bool(po.approaching), bool(go.approaching)
                    agree["tp" if a and b else "fp" if a else "fn" if b else "tn"] += 1
        ds = np.asarray(ds)
        out[scope] = {
            "matched_pairs_with_estimates": n_pairs,
            "scale_rate_abs_diff_p50_per_s": float(np.median(np.abs(ds))) if len(ds) else None,
            "scale_rate_abs_diff_p90_per_s": float(np.percentile(np.abs(ds), 90)) if len(ds) else None,
            "scale_rate_bias_per_s": float(np.median(ds)) if len(ds) else None,
            "ttc_rel_diff_p50_when_gt_ttc_le_10s": p50(ttc_rel), "n_ttc_pairs": len(ttc_rel),
            "approaching_confusion_vs_gt_boxes": agree,
            "approaching_precision_vs_gt_boxes": agree["tp"] / max(agree["tp"] + agree["fp"], 1),
            "approaching_recall_vs_gt_boxes": agree["tp"] / max(agree["tp"] + agree["fn"], 1),
            "gt_positive_rate": (agree["tp"] + agree["fn"]) / max(sum(agree.values()), 1)}
        print(f"[ttc] {scope}: {out[scope]}", flush=True)
    return out


# ----------------------------------------------------------------------------- report
def make_plots(metrics: dict) -> list[str]:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    files = []
    rows = [(k, v) for k, v in metrics["trackers"].items() if "error" not in v]
    rows.sort(key=lambda kv: kv[1]["overall"]["idf1"], reverse=True)
    labels = [k for k, _ in rows]
    y = np.arange(len(rows))
    panels = (("MOTA (%)", lambda v: 100 * v["overall"]["mota"]), ("IDF1 (%)", lambda v: 100 * v["overall"]["idf1"]),
              ("mIDF1, class-avg (%)", lambda v: 100 * (v["mIDF1"] or 0)),
              ("ID switches", lambda v: v["overall"]["num_switches"]))
    fig, axes = plt.subplots(1, 4, figsize=(18, 0.32 * len(rows) + 1.8), sharey=True)
    colors = ["#2b6cb0" if k.startswith("ul_") else "#c05621" for k in labels]
    for ax, (title, fn) in zip(axes, panels):
        vals = [fn(v) for _, v in rows]
        ax.barh(y, vals, color=colors)
        for yi, vv in zip(y, vals):
            ax.text(vv, yi, f" {vv:.0f}" if title == "ID switches" else f" {vv:.1f}", va="center", fontsize=7)
        ax.set_title(title, fontsize=10)
        ax.grid(axis="x", alpha=0.3)
    axes[0].set_yticks(y, labels, fontsize=7)
    axes[0].invert_yaxis()
    fig.suptitle("Trackers on identical YOLO26s-BDD detections, BDD100K MOT val, 7 seqs @5 fps, 1.0 s buffer "
                 "(blue = Ultralytics, orange = Roboflow trackers). Measured here.", fontsize=10)
    fig.tight_layout()
    p = OUT / "tracker_comparison.png"
    fig.savefig(p, dpi=110)
    plt.close(fig)
    files.append(str(p))

    # per-class for the default
    d = metrics.get("tracker_defaults", {}).get(metrics["chosen_default"])
    if d:
        cls = [c for c in d["per_class"] if d["per_class"][c].get("num_objects")]
        fig, ax = plt.subplots(figsize=(8, 3.6))
        x = np.arange(len(cls))
        ax.bar(x - 0.2, [100 * (d["per_class"][c]["mota"] or 0) for c in cls], 0.4, label="MOTA", color="#2b6cb0")
        ax.bar(x + 0.2, [100 * (d["per_class"][c]["idf1"] or 0) for c in cls], 0.4, label="IDF1", color="#38a169")
        ax.set_xticks(x, [f"{c}\n(n={d['per_class'][c]['num_objects']:.0f})" for c in cls], fontsize=8)
        ax.axhline(0, color="k", lw=0.6)
        ax.legend(fontsize=8)
        ax.set_title(f"Per-class, {metrics['chosen_default']} with Tracker defaults, BDD100K MOT val "
                     f"7 seqs @5 fps (measured here)", fontsize=9)
        ax.grid(axis="y", alpha=0.3)
        fig.tight_layout()
        p = OUT / "per_class_default.png"
        fig.savefig(p, dpi=110)
        plt.close(fig)
        files.append(str(p))
    return files


def print_tables(metrics: dict) -> None:
    """Markdown tables (pasted into README.md)."""
    def f(x, pct=True):
        return "n/a" if x is None else (f"{100 * x:.1f}" if pct else f"{x:.0f}")

    print("\n| Tracker : thresholds | mMOTA | mIDF1 | mHOTA | MOTA | IDF1 | HOTA | ID sw. | FP | FN | update p50 ms |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    rows = [(k, v) for k, v in metrics.get("trackers", {}).items() if "error" not in v]
    for k, v in sorted(rows, key=lambda kv: -(kv[1]["overall"]["idf1"] or 0)):
        o = v["overall"]
        print(f"| {k} | {f(v['mMOTA'])} | {f(v['mIDF1'])} | {f(v['mHOTA'])} | {f(o['mota'])} | {f(o['idf1'])} | "
              f"{f(o.get('HOTA'))} | {f(o['num_switches'], False)} | {f(o['num_false_positives'], False)} | "
              f"{f(o['num_misses'], False)} | {v['update_ms_p50']:.1f} |")
    for k, v in metrics.get("trackers", {}).items():
        if "error" in v:
            print(f"| {k} | FAILED: {v['error']} |")
    d = metrics.get("tracker_defaults", {}).get(metrics.get("chosen_default"))
    if d:
        print("\n| Class | GT boxes | MOTA | IDF1 | HOTA | ID sw. | precision | recall |")
        print("|---|---|---|---|---|---|---|---|")
        for c, v in d["per_class"].items():
            if v.get("num_objects"):
                print(f"| {c} | {v['num_objects']:.0f} | {f(v['mota'])} | {f(v['idf1'])} | {f(v.get('HOTA'))} | "
                      f"{v['num_switches']:.0f} | {f(v['precision'])} | {f(v['recall'])} |")
            else:
                print(f"| {c} | 0 | n/a | n/a | n/a | n/a | n/a | n/a |")
        for sname, v in d.get("super_categories", {}).items():
            print(f"| {sname} (super) | {v['num_objects']:.0f} | {f(v['mota'])} | {f(v['idf1'])} | {f(v.get('HOTA'))} | "
                  f"{v['num_switches']:.0f} | | |")
    for sec in ("threshold_sweep", "ablations", "tracker_defaults", "native_pipeline"):
        if metrics.get(sec):
            print(f"\n[{sec}]\n| Config | mMOTA | mIDF1 | MOTA | IDF1 | ID sw. | ms p50 |")
            print("|---|---|---|---|---|---|---|")
            for k, v in metrics[sec].items():
                if "error" in v:
                    print(f"| {k} | FAILED: {v['error']} |")
                    continue
                o = v["overall"]
                ms = v.get("update_ms_p50") or v.get("step_ms_p50_detector_plus_tracker")
                print(f"| {k} | {f(v['mMOTA'])} | {f(v['mIDF1'])} | {f(o['mota'])} | {f(o['idf1'])} | "
                      f"{f(o['num_switches'], False)} | {ms:.1f} |")


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--stages", nargs="+",
                    default=["dets", "track", "sweep", "ablate", "default", "native", "ttc", "report"])
    ap.add_argument("--backends", nargs="*", default=None)
    ap.add_argument("--default", default="ul_botsort", help="backend used for ablations / TTC check")
    ap.add_argument("--device", default="cuda")
    a = ap.parse_args(argv)
    OUT.mkdir(parents=True, exist_ok=True)
    mpath = OUT / "metrics.json"
    metrics = json.load(open(mpath)) if mpath.exists() else {}
    seqs = available_seqs()
    missing = [s for s in SEQS if s not in seqs]
    print("sequences:", seqs, "missing:", missing, flush=True)
    gts = {s: load_gt(GT_DIR / f"{s}.json") for s in seqs}
    metrics["dataset"] = {"name": "BDD100K MOT 2020 val (box_track_20), 5 fps", "sequences": seqs,
                          "missing_sequences": missing, "frames": sum(len(g) for g in gts.values()),
                          "gt_boxes_eval_classes": int(sum(len(f["ids"]) for g in gts.values() for f in g)),
                          "gt_ignore_regions": int(sum(len(f["ignore"]) for g in gts.values() for f in g))}
    if "dets" in a.stages:
        metrics["detector"] = stage_dets(seqs, a.device)
    need = {"track", "sweep", "ablate", "default", "ttc"} & set(a.stages)
    dets_all = {s: load_dets(s) for s in seqs} if need else {}
    if "track" in a.stages:
        metrics.setdefault("trackers", {}).update(stage_track(seqs, gts, dets_all, a.backends))
    if "sweep" in a.stages:
        metrics["threshold_sweep"] = stage_sweep(seqs, gts, dets_all)
    if "ablate" in a.stages:
        metrics["ablations"] = stage_ablate(seqs, gts, dets_all, a.default)
    if "default" in a.stages:
        metrics["tracker_defaults"] = stage_default(
            seqs, gts, dets_all, [a.default, "ul_tracktrack", "rf_botsort", "rf_mcbyte", "rf_bytetrack"])
    if "native" in a.stages:
        metrics["native_pipeline"] = stage_native(seqs, gts, a.device)
    if "ttc" in a.stages:
        metrics["ttc_consistency"] = stage_ttc(seqs, gts, dets_all, a.default)
    metrics["chosen_default"] = a.default
    demo = OUT / "demo_metrics.json"  # written by demo_video.py (kept separate to avoid write races)
    if demo.exists():
        metrics["demo_30fps"] = json.load(open(demo))
    if "report" in a.stages and metrics.get("trackers"):
        from perception.tracking.test_motion import main as synth
        metrics["motion_synthetic_test"] = synth()
        metrics["plots"] = make_plots(metrics)
        print_tables(metrics)
    # no machine paths in the file that gets copied to results/ (weights / outputs as <models>/..., <outputs>/...)
    json.dump(portable_paths(metrics), open(mpath, "w"), indent=1, default=float)
    print("wrote", mpath)


if __name__ == "__main__":
    sys.exit(main())
