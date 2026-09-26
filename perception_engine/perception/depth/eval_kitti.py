"""KITTI validation of the depth & distance block.

BDD100K has no depth ground truth, so metric accuracy is measured on a small
KITTI sample (data/kitti, CC BY-NC-SA 3.0, testing only):

 (i)  per-object distance on 100 object-split training images using the GT 2D
      boxes (isolates distance estimation from detection). Objects: Car/Van/Truck/
      Pedestrian/Cyclist with truncated < 0.3, occluded <= 1, 0 < z <= 80 m,
      box height >= 10 px. GT distance = forward depth of the nearest 3D-box
      corner (primary; what a following-distance cue needs) and of the box centre
      (KITTI `location.z`, secondary). Range bins 0-10/10-20/20-40/40-80 m (by GT z).
 (ii) dense AbsRel / SqRel / RMSE / RMSElog / delta1-3 on 50 depth-selection pairs
      (val_selection_cropped, 1216x352, GT <= 80 m).

Run from perception_engine/:
    .venv\\Scripts\\python.exe -m perception.depth.eval_kitti
"""
from __future__ import annotations

import argparse
import json
import math
import time
from collections import defaultdict
from pathlib import Path

import cv2
import numpy as np
import torch

from perception.common.schemas import Detection
from perception.depth.distance import DistanceEstimator
from perception.depth import geometry as G

from perception.common.paths import KITTI_ROOT, OUTPUTS_ROOT  # noqa: E402

KITTI = KITTI_ROOT                      # <PERCEPTION_DATA_DIR>/kitti (scripts/fetch_bdd_samples.py --kitti)
OUT = OUTPUTS_ROOT / "depth"
BINS = [(0, 10), (10, 20), (20, 40), (40, 80)]
CLASS_MAP = {"Car": "car", "Van": "car", "Truck": "truck", "Pedestrian": "pedestrian",
             "Person_sitting": "pedestrian", "Cyclist": "rider", "Tram": "train"}
EVAL_CLASSES = {"Car", "Van", "Truck", "Pedestrian", "Cyclist"}
GROUP = {"Car": "vehicle", "Van": "vehicle", "Truck": "vehicle", "Pedestrian": "pedestrian", "Cyclist": "cyclist"}
KITTI_CAM_HEIGHT = 1.65


def load_calib(p: Path):
    for line in p.read_text().splitlines():
        if line.startswith("P2:"):
            P = np.array(line.split()[1:], float).reshape(3, 4)
            return P
    raise ValueError(p)


def box_corners_z(h, w, l, x, y, z, ry):
    xs = np.array([l, l, -l, -l, l, l, -l, -l]) / 2
    zs = np.array([w, -w, -w, w, w, -w, -w, w]) / 2
    c, s = math.cos(ry), math.sin(ry)
    # rotation about camera y axis: x' = c*x + s*z, z' = -s*x + c*z
    return (-s * xs + c * zs) + z


def load_objects(ids):
    imgs = []
    for k in ids:
        P = load_calib(KITTI / "object" / "training" / "calib" / f"{k}.txt")
        objs = []
        for line in (KITTI / "object" / "training" / "label_2" / f"{k}.txt").read_text().splitlines():
            f = line.split()
            if not f or f[0] not in CLASS_MAP:
                continue
            trunc, occ = float(f[1]), int(f[2])
            box = list(map(float, f[4:8]))
            hh, ww, ll = map(float, f[8:11])
            x, y, z = map(float, f[11:14])
            ry = float(f[14])
            zn = float(np.min(box_corners_z(hh, ww, ll, x, y, z, ry)))
            valid = (f[0] in EVAL_CLASSES and trunc < 0.3 and occ <= 1 and 0 < z <= 80 and (box[3] - box[1]) >= 10)
            objs.append(dict(type=f[0], cls=CLASS_MAP[f[0]], box=box, z=z, z_near=max(zn, 0.5), valid=valid,
                             trunc=trunc, occ=occ))
        imgs.append(dict(id=k, P=P, objs=objs))
    return imgs


def err_stats(pred, gt):
    pred, gt = np.asarray(pred, float), np.asarray(gt, float)
    ok = np.isfinite(pred)
    n_all = len(gt)
    if ok.sum() == 0:
        return {"n": 0, "coverage": 0.0}
    p, g = pred[ok], gt[ok]
    rel = (p - g) / g
    return {"n": int(ok.sum()), "coverage": round(float(ok.mean()), 3) if n_all else 0.0,
            "mae_m": round(float(np.mean(np.abs(p - g))), 3),
            "median_abs_rel": round(float(np.median(np.abs(rel))), 4),
            "mean_abs_rel": round(float(np.mean(np.abs(rel))), 4),
            "median_signed_rel": round(float(np.median(rel)), 4),
            "within_10pct": round(float(np.mean(np.abs(rel) < 0.10)), 3),
            "within_20pct": round(float(np.mean(np.abs(rel) < 0.20)), 3)}


def summarize(rows, key_pred, gt_key="z_near"):
    out = {}
    g = np.array([r[gt_key] for r in rows])
    zc = np.array([r["z"] for r in rows])
    p = np.array([np.nan if r.get(key_pred) is None else r[key_pred] for r in rows], float)
    out["all"] = err_stats(p, g)
    for lo, hi in BINS:
        m = (zc >= lo) & (zc < hi) if hi < 80 else (zc >= lo) & (zc <= hi)
        out[f"{lo}-{hi}m"] = err_stats(p[m], g[m])
    for grp in ("vehicle", "pedestrian", "cyclist"):
        m = np.array([GROUP[r["type"]] == grp for r in rows])
        out[grp] = err_stats(p[m], g[m])
    return out


def dense_metrics(pred, gt, max_d=80.0):
    m = (gt > 1e-3) & (gt <= max_d)
    p, g = np.clip(pred[m], 1e-3, max_d), gt[m]
    thr = np.maximum(p / g, g / p)
    return dict(abs_rel=float(np.mean(np.abs(p - g) / g)), sq_rel=float(np.mean((p - g) ** 2 / g)),
                rmse=float(np.sqrt(np.mean((p - g) ** 2))), rmse_log=float(np.sqrt(np.mean((np.log(p) - np.log(g)) ** 2))),
                d1=float(np.mean(thr < 1.25)), d2=float(np.mean(thr < 1.25 ** 2)), d3=float(np.mean(thr < 1.25 ** 3)),
                median_ratio=float(np.median(p / g)),
                abs_rel_median_scaled=float(np.mean(np.abs(p * np.median(g / p) - g) / g)))


def run_estimator(est: DistanceEstimator, img, rec, raw):
    cam_kw = dict(focal_px=rec["P"][0, 0], cx=rec["P"][0, 2], cy=rec["P"][1, 2])
    est.set_camera(**cam_kw)
    est.reset()
    dets = [Detection(o["cls"], o["box"], 1.0, id=i) for i, o in enumerate(rec["objs"])]
    return est.estimate_detailed(img, dets, pts_s=0.0, depth_map=raw), dict(est.last_info)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--backends", nargs="*", default=["da2_metric_outdoor_small", "da3_metric_large", "metric3d_vit_s_onnx"])
    ap.add_argument("--n-images", type=int, default=100)
    ap.add_argument("--dense-pairs", type=int, default=50)
    ap.add_argument("--percentiles", nargs="*", type=float, default=[20, 30, 50])
    args = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)

    ids = (KITTI / "index" / "object_selected_ids.txt").read_text().split()[: args.n_images]
    recs = load_objects(ids)
    rows = []  # one per valid object
    for rec in recs:
        for i, o in enumerate(rec["objs"]):
            if o["valid"]:
                rows.append(dict(img=rec["id"], idx=i, type=o["type"], z=o["z"], z_near=o["z_near"], box=o["box"]))
    index = {(r["img"], r["idx"]): r for r in rows}
    print(f"KITTI objects: {len(rows)} valid in {len(recs)} images", flush=True)

    # ---- geometry-only (calibrated KITTI camera: f from P2, horizon = principal row, H_c = 1.65 m)
    geo = {
        "geo_calib": DistanceEstimator(None, cam_height_m=KITTI_CAM_HEIGHT, horizon="fixed", temporal=False),
        "geo_virtual": DistanceEstimator(None, cam_height_m=KITTI_CAM_HEIGHT, horizon="virtual", temporal=False),
    }
    vh_err = []
    for rec in recs:
        p = KITTI / "object" / "training" / "image_2" / f"{rec['id']}.png"
        img = cv2.imread(str(p))
        for name, est in geo.items():
            det, info = run_estimator(est, img, rec, None)
            for d in det:
                r = index.get((rec["id"], d["id"]))
                if r is None:
                    continue
                c = d["components"]
                if name == "geo_calib":
                    r["ground_calib"] = c.get("ground_plane")
                    r["size_prior"] = c.get("width_prior")
                    o = rec["objs"][d["id"]]
                    sz = G.size_distances(o["cls"], o["box"], est.cam)
                    r["width_only"] = sz["width"][0] if "width" in sz else None
                    r["height_only"] = sz["height"][0] if "height" in sz else None
                    r["fused_geo_calib"] = d["raw_fused"]
                else:
                    r["ground_virtual"] = c.get("ground_plane")
                    r["fused_geo_virtual"] = d["raw_fused"]
            if name == "geo_virtual" and info["horizon_source"] and "virtual" in info["horizon_source"]:
                vh_err.append(info["horizon_y"] - rec["P"][1, 2])

    metrics = {"setup": {
        "images": len(recs), "objects": len(rows), "gt_primary": "z_near = forward depth of nearest 3D-box corner",
        "gt_secondary": "z = KITTI location z (box centre)", "bins_by": "GT centre z",
        "filters": "Car/Van/Truck/Pedestrian/Cyclist, truncated<0.3, occluded<=1, 0<z<=80, box h>=10px",
        "camera": "per-image P2 focal/principal point; camera height 1.65 m",
        "class_map": CLASS_MAP, "size_priors": {k: v for k, v in G.SIZE_PRIORS.items()},
        "counts_by_bin": {f"{lo}-{hi}m": int(sum(1 for r in rows if lo <= r['z'] < hi or (hi == 80 and r['z'] == 80))) for lo, hi in BINS},
        "counts_by_group": {g: int(sum(1 for r in rows if GROUP[r['type']] == g)) for g in ("vehicle", "pedestrian", "cyclist")},
    }, "methods": {}, "calibration": {}, "dense": {}, "timing_ms_p50": {}}
    metrics["calibration"]["virtual_horizon_minus_cy_px"] = {
        "median": round(float(np.median(vh_err)), 2) if vh_err else None,
        "mad": round(float(np.median(np.abs(np.array(vh_err) - np.median(vh_err)))), 2) if vh_err else None, "n": len(vh_err)}

    # ---- depth backends
    dense_imgs = sorted((KITTI / "depth_selection" / "val_selection_cropped" / "image").glob("*.png"))[: args.dense_pairs]
    for bname in args.backends:
        print(f"== {bname}", flush=True)
        torch.cuda.reset_peak_memory_stats() if torch.cuda.is_available() else None
        t0 = time.time()
        try:
            est = DistanceEstimator(bname, cam_height_m=KITTI_CAM_HEIGHT, calibration="fixed", horizon="fixed", temporal=False)
        except Exception as e:  # report, keep going
            metrics["methods"][bname] = {"error": repr(e)}
            print("FAILED", e, flush=True)
            continue
        est_hd = DistanceEstimator(None, backend=est.backend, cam_height_m=None, calibration="height_from_depth",
                                   horizon="depth", temporal=False)
        est_hd.backend_name = bname
        est_sc = DistanceEstimator(None, backend=est.backend, cam_height_m=KITTI_CAM_HEIGHT,
                                   calibration="scale_from_height", horizon="depth", temporal=False)
        est_sc.backend_name = bname
        est_auto = DistanceEstimator(None, backend=est.backend, cam_height_m=None, calibration="auto" if est.backend.needs_focal else "scale_from_height",
                                     horizon="auto", temporal=False)
        # 'auto' for DA2 falls back to H_c prior 1.3 m unless given; give KITTI's 1.65 so the test is fair
        if not est.backend.needs_focal:
            est_auto.cam_height_given = KITTI_CAM_HEIGHT
        est_auto.backend_name = bname
        for e in (est_hd, est_sc, est_auto):
            e.depth_rel_sigma = est.depth_rel_sigma
        times, hd_list, yh_list = [], [], []
        for rec in recs:
            img = cv2.imread(str(KITTI / "object" / "training" / "image_2" / f"{rec['id']}.png"))
            est.set_camera(rec["P"][0, 0], rec["P"][0, 2], rec["P"][1, 2])
            est._ensure_camera(img.shape[1], img.shape[0])
            raw = est.raw_depth_map(img)
            times.append(est.backend.last_ms)
            sky = getattr(est.backend, "last_sky", None)
            det, _ = run_estimator(est, img, rec, raw)
            det_hd, info_hd = run_estimator(est_hd, img, rec, raw)
            det_sc, info_sc = run_estimator(est_sc, img, rec, raw)
            det_au, info_au = run_estimator(est_auto, img, rec, raw)
            prof = info_hd.get("road_profile")
            if prof:
                hd_list.append(prof["height_depth_m"])
                yh_list.append(prof["horizon_y"] - rec["P"][1, 2])
            for dd, dh, ds, da in zip(det, det_hd, det_sc, det_au):
                r = index.get((rec["id"], dd["id"]))
                if r is None:
                    continue
                o = rec["objs"][dd["id"]]
                r[f"{bname}:depth"] = dd["components"].get("depth_model")
                nearer = [oo["box"] for j, oo in enumerate(rec["objs"]) if j != dd["id"] and oo["box"][3] > o["box"][3] + 2]
                for pc in args.percentiles:
                    rd = G.box_readout(raw, o["box"], o["cls"], pc, sky, exclude_boxes=nearer)
                    r[f"{bname}:depth_p{int(pc)}"] = None if rd is None else rd[0]
                r[f"{bname}:fused_calib"] = dd["raw_fused"]
                r[f"{bname}:ground_auto_hd"] = dh["components"].get("ground_plane")
                r[f"{bname}:fused_auto_hd"] = dh["raw_fused"]
                r[f"{bname}:depth_scaled"] = ds["components"].get("depth_model")
                r[f"{bname}:ground_depth_horizon"] = ds["components"].get("ground_plane")
                r[f"{bname}:fused_scale_from_height"] = ds["raw_fused"]
                r[f"{bname}:fused_auto"] = da["raw_fused"]
        # dense
        dm = []
        for p in dense_imgs:
            img = cv2.imread(str(p))
            K = np.loadtxt(p.parent.parent / "intrinsics" / (p.stem + ".txt")).reshape(3, 3)
            gt = cv2.imread(str(p.parent.parent / "groundtruth_depth" / p.name.replace("_sync_image_", "_sync_groundtruth_depth_")), -1)
            gt = gt.astype(np.float32) / 256.0
            est.set_camera(K[0, 0], K[0, 2], K[1, 2])
            pred = est.raw_depth_map(img)
            dm.append(dense_metrics(pred, gt))
        metrics["dense"][bname] = {k: round(float(np.mean([d[k] for d in dm])), 4) for k in dm[0]} | {"n_pairs": len(dm)}
        metrics["timing_ms_p50"][bname] = {"kitti_object_1242x375": round(float(np.median(times[1:])), 1),
                                           "net_input_hw": list(est.backend.last_net_hw),
                                           "peak_torch_alloc_gb": round(torch.cuda.max_memory_allocated() / 1e9, 3) if torch.cuda.is_available() else None}
        metrics["calibration"][bname] = {
            "road_profile_fits": len(hd_list),
            "camera_height_from_depth_m": {"median": round(float(np.median(hd_list)), 3) if hd_list else None,
                                           "iqr": [round(float(np.percentile(hd_list, 25)), 3), round(float(np.percentile(hd_list, 75)), 3)] if hd_list else None,
                                           "true": KITTI_CAM_HEIGHT},
            "implied_depth_scale_error": round(float(np.median(hd_list)) / KITTI_CAM_HEIGHT, 3) if hd_list else None,
            "depth_profile_horizon_minus_cy_px": {"median": round(float(np.median(yh_list)), 2) if yh_list else None,
                                                  "mad": round(float(np.median(np.abs(np.array(yh_list) - np.median(yh_list)))), 2) if yh_list else None}}
        print(json.dumps(metrics["dense"][bname]), metrics["timing_ms_p50"][bname], metrics["calibration"][bname], flush=True)
        est.close()
        del est, est_hd, est_sc, est_auto
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
        print(f"   {bname} done in {time.time() - t0:.0f}s", flush=True)

    # ---- per-method summaries
    keys = sorted({k for r in rows for k in r if k not in ("img", "idx", "type", "z", "z_near", "box")})
    for k in keys:
        metrics["methods"].setdefault(k, {})
        if isinstance(metrics["methods"][k], dict) and "error" in metrics["methods"][k]:
            continue
        metrics["methods"][k] = {"vs_nearest_face": summarize(rows, k, "z_near"), "vs_center": summarize(rows, k, "z")}
    # compact table for the README
    table = []
    for k in keys:
        s = metrics["methods"][k]["vs_nearest_face"]
        table.append([k] + [f"{s[b].get('mae_m', float('nan')):.2f} / {100 * s[b].get('median_abs_rel', float('nan')):.1f}%"
                            if s[b].get("n") else "-" for b in ["0-10m", "10-20m", "20-40m", "40-80m", "all"]]
                     + [f"{s['all'].get('coverage', 0):.2f}", f"{100 * s['all'].get('median_signed_rel', float('nan')):+.1f}%"])
    metrics["table_vs_nearest_face"] = {"columns": ["method", "0-10m MAE/medRel", "10-20m", "20-40m", "40-80m", "all", "coverage", "bias"],
                                        "rows": table}
    for row in table:
        print(" | ".join(row))
    (OUT / "kitti_rows.json").write_text(json.dumps(rows))
    mpath = OUT / "metrics.json"
    allm = json.loads(mpath.read_text()) if mpath.exists() else {}
    allm["kitti"] = metrics
    mpath.write_text(json.dumps(allm, indent=1))
    print("wrote", mpath)
    plot(rows, OUT / "kitti_distance_error.png", args.backends)


def plot(rows, path, backends):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    methods = [("ground_calib", "flat ground (calib horizon)"), ("size_prior", "size prior")]
    methods += [(f"{b}:depth", f"{b} box depth") for b in backends]
    methods += [(f"{b}:fused_calib", f"{b} fused") for b in backends[-1:]]
    n = len(methods)
    fig, axes = plt.subplots(1, n, figsize=(3.2 * n, 3.4), sharey=True)
    for ax, (k, title) in zip(np.atleast_1d(axes), methods):
        g = np.array([r["z_near"] for r in rows])
        p = np.array([np.nan if r.get(k) is None else r[k] for r in rows], float)
        col = ["#1f77b4" if r["type"] in ("Car", "Van", "Truck") else "#d62728" if r["type"] == "Pedestrian" else "#2ca02c" for r in rows]
        ax.scatter(g, (p - g) / g * 100, s=6, c=col, alpha=0.6)
        ax.axhline(0, color="k", lw=0.8)
        ax.set_ylim(-60, 60)
        ax.set_xlim(0, 82)
        ax.set_title(title, fontsize=8)
        ax.set_xlabel("GT distance (m, nearest face)", fontsize=8)
        ax.grid(alpha=0.3)
    np.atleast_1d(axes)[0].set_ylabel("relative error (%)")
    fig.suptitle("KITTI per-object distance error (blue vehicle, red pedestrian, green cyclist) - measured here", fontsize=9)
    fig.tight_layout()
    fig.savefig(path, dpi=110)
    print("wrote", path)


if __name__ == "__main__":
    main()
