"""BDD100K consistency run for the depth & distance block.

BDD100K has no depth ground truth, so this script measures *agreement*, not error:
  * per-vehicle distances from every method on 3 primary clips, using the
    official box_track_20 MOT boxes (5 fps, stable track ids) so detection noise
    is excluded;
  * median ratio depth-model / flat-ground (the ground estimate here uses a
    depth-free horizon: virtual horizon from size priors + principal-row prior),
    i.e. the implied scale bias of each depth model from FOV mismatch, under the
    BDD assumptions f = 700 px @ 1280 wide and camera height 1.3 m;
  * camera height implied by each depth model's road profile (true value unknown,
    ~1.2-1.5 m expected for a windscreen phone);
  * implied car width Z * w_px / f for rear-view cars (should be ~1.8 m);
  * temporal jitter on tracks (relative 2nd difference at 5 fps) and the share of
    physically implausible jumps (|dZ/dt| > 40 m/s).
It also writes PNG panels (depth colormap + distance labels) and a lead-vehicle
distance timeline.

Run from perception_engine/:
    .venv\\Scripts\\python.exe -m perception.depth.eval_bdd
"""
from __future__ import annotations

import argparse
import json
import time
from collections import defaultdict
from pathlib import Path

import cv2
import numpy as np
import torch

from perception.common.schemas import Detection, canonical_class
from perception.common.video import DATA_ROOT, OUTPUTS_ROOT, VideoFileInput
from perception.depth import geometry as G
from perception.depth.distance import DistanceEstimator

OUT = OUTPUTS_ROOT / "depth"
CLIPS = {"b1f4491b-cf446195": "highway_day", "b1ff4656-0435391e": "city_intersection_day",
         "b23adb0d-8a7aaced": "night_city"}
VEH = {"car", "truck", "bus"}
BINS = [(0, 10), (10, 20), (20, 40), (40, 80)]


# Review fix: per-clip (MOT frameIndex, decoded video frame) anchors, measured by pixel-matching
# BDD100K MOT JPEGs against VideoFileInput frames (check_mot_alignment.py; exact match MSE ~2,
# next best >= 5). The start offset differs per clip (-7 .. +1 frames from round(5.994 * i)); the
# original single formula 5.994*i - 4 (measured on b1ff4656 only) read b1f4491b 4-5 frames and
# b23adb0d 3 frames too early (100-167 ms before the labelled instant).
MOT_ANCHORS = {
    "b1d968b9-ce42734f": [(10, 59), (50, 299), (100, 598), (150, 898), (190, 1138)],
    "b1f4491b-cf446195": [(10, 60), (50, 300), (100, 600), (150, 900), (190, 1140)],
    "b1ff4656-0435391e": [(10, 56), (50, 296), (100, 595), (150, 895), (190, 1135)],
    "b204a5c1-064b0040": [(10, 61), (50, 300), (100, 600), (150, 900), (190, 1139)],
    "b2064e61-2beadd45": [(10, 53), (50, 293), (100, 593), (150, 892), (190, 1132)],
    "b20e291a-6012d836": [(10, 56), (50, 296), (100, 595), (150, 895), (190, 1135)],
    "b23adb0d-8a7aaced": [(10, 59), (50, 299), (100, 599), (150, 898), (190, 1138)],
}


def mot_to_video_frame(i: int, vid: str | None = None) -> int:
    """Decoded video frame index for MOT frameIndex i (29.97 fps stream sampled at 5 fps)."""
    anchors = MOT_ANCHORS.get(vid)
    if anchors:
        x, y = np.asarray(anchors, float).T
        k, b = np.polyfit(x, y, 1)  # slope ~5.994, per-clip intercept; residuals <= 0.5 frame
        return max(0, int(round(k * i + b)))
    # Unmeasured clip: b1ff4656's mapping; can be ~5 frames off (run check_mot_alignment.py).
    return max(0, int(round(i * 29.97 / 5.0 - 4)))


def load_clip(vid: str, max_frames: int):
    labels = json.loads((DATA_ROOT / "labels" / "box_track_20" / "val" / f"{vid}.json").read_text())
    labels = labels[:max_frames]
    want = {mot_to_video_frame(f["frameIndex"], vid): f for f in labels}
    frames = {}
    v = VideoFileInput(DATA_ROOT / "videos" / "val" / f"{vid}.mov")
    last = max(want)
    for fr in v:
        if fr.index in want:
            frames[fr.index] = fr.image
        if fr.index >= last:
            break
    v.release()
    seq = []
    for j in sorted(want):
        if j not in frames:
            continue
        f = want[j]
        dets, attrs = [], {}
        for l in f["labels"]:
            b = l["box2d"]
            tid = int(l["id"])
            dets.append(Detection(canonical_class(l["category"]), [b["x1"], b["y1"], b["x2"], b["y2"]], 1.0, id=tid))
            attrs[tid] = l.get("attributes", {})
        seq.append(dict(mot_index=f["frameIndex"], video_frame=j, t=f["frameIndex"] / 5.0, image=frames[j],
                        dets=dets, attrs=attrs))
    return seq


def good_vehicle(d: Detection, attrs: dict, w=1280, h=720) -> bool:
    x1, y1, x2, y2 = d.bbox
    a = attrs.get(d.id, {})
    return (d.cls in VEH and not a.get("occluded", False) and not a.get("truncated", False)
            and (y2 - y1) >= 15 and y2 < h - 3 and x1 > 2 and x2 < w - 3)


def med_iqr(x):
    x = np.asarray([v for v in x if v is not None and np.isfinite(v)], float)
    if x.size == 0:
        return {"n": 0}
    return {"n": int(x.size), "median": round(float(np.median(x)), 3),
            "iqr": [round(float(np.percentile(x, 25)), 3), round(float(np.percentile(x, 75)), 3)]}


def jitter_stats(series: dict[int, list[tuple[float, float]]]):
    """series: track -> [(t, Z)] at 5 fps. Relative 2nd difference and implausible-jump share."""
    rel2, jumps, n_steps = [], 0, 0
    for pts in series.values():
        pts = sorted(p for p in pts if p[1] is not None)
        for k in range(1, len(pts)):
            (t0, z0), (t1, z1) = pts[k - 1], pts[k]
            if 0 < t1 - t0 <= 0.25:
                n_steps += 1
                if abs(z1 - z0) / (t1 - t0) > 40:
                    jumps += 1
        for k in range(2, len(pts)):
            (ta, za), (tb, zb), (tc, zc) = pts[k - 2], pts[k - 1], pts[k]
            if tc - ta <= 0.45:
                rel2.append(abs(za - 2 * zb + zc) / zb)
    return {"rel_second_diff_median": round(float(np.median(rel2)), 4) if rel2 else None,
            "rel_second_diff_p90": round(float(np.percentile(rel2, 90)), 4) if rel2 else None,
            "implausible_jump_frac": round(jumps / max(1, n_steps), 4), "n_steps": n_steps}


def colorize(depth, vmax=60.0):
    d = np.clip(depth / vmax, 0, 1)
    inv = (1.0 - np.sqrt(d)) * 255  # near = bright
    return cv2.applyColorMap(inv.astype(np.uint8), cv2.COLORMAP_INFERNO)


def draw_labels(img, rows, key="distance", color=(0, 255, 0), scale=0.5):
    out = img.copy()
    for r in rows:
        z = r.get(key) if isinstance(r, dict) else None
        x1, y1, x2, y2 = map(int, r["bbox"])
        cv2.rectangle(out, (x1, y1), (x2, y2), color, 1)
        if z is not None:
            txt = f"{z:.1f}m"
            (tw, th), _ = cv2.getTextSize(txt, cv2.FONT_HERSHEY_SIMPLEX, scale, 1)
            cv2.rectangle(out, (x1, max(0, y1 - th - 4)), (x1 + tw + 2, y1), (0, 0, 0), -1)
            cv2.putText(out, txt, (x1 + 1, y1 - 3), cv2.FONT_HERSHEY_SIMPLEX, scale, color, 1, cv2.LINE_AA)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--backends", nargs="*", default=["da2_metric_outdoor_small", "da3_metric_large", "metric3d_vit_s_onnx"])
    ap.add_argument("--clips", nargs="*", default=list(CLIPS))
    ap.add_argument("--max-frames", type=int, default=202, help="MOT frames per clip (5 fps)")
    ap.add_argument("--panel-mot-index", type=int, default=60)
    args = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    results = {"setup": {"camera": "f = 700 px @ 1280x720 (BDD maintainer), principal point = image centre, H_c = 1.3 m (assumed)",
                         "boxes": "BDD100K box_track_20 GT (5 fps); video frame from per-clip pixel-matched anchors (MOT_ANCHORS, review fix)",
                         "vehicle_filter": "car/truck/bus, not occluded, not truncated, h >= 15 px, not touching border",
                         "ground_reference": "geometry-only estimator: flat ground with horizon from virtual horizon + principal-row prior (no depth model)"},
               "clips": {}}
    timeline = {}
    for vid in args.clips:
        t0 = time.time()
        seq = load_clip(vid, args.max_frames)
        print(f"== {vid} ({CLIPS.get(vid, '')}): {len(seq)} frames loaded in {time.time() - t0:.0f}s", flush=True)
        clip_res = {"frames": len(seq)}
        per = defaultdict(dict)  # (mot_index, track) -> values
        panel = {}
        # --- geometry-only reference
        geo = DistanceEstimator(None, temporal=True)
        for fr in seq:
            det = geo.estimate_detailed(fr["image"], fr["dets"], pts_s=fr["t"])
            for d in det:
                k = (fr["mot_index"], d["id"])
                per[k]["geo_ground"] = d["components"].get("ground_plane")
                per[k]["size_prior"] = d["components"].get("width_prior")
                per[k]["geo_fused_raw"] = d["raw_fused"]
                per[k]["geo_fused_kf"] = d["distance"]
            per[("info", fr["mot_index"])]["geo_horizon"] = geo.last_info["horizon_y"]
            if fr["mot_index"] == args.panel_mot_index:
                panel["geo"] = det
        # --- each depth backend (loaded one at a time; VRAM <= ~1 GB each)
        for bname in args.backends:
            tb = time.time()
            if torch.cuda.is_available():
                torch.cuda.reset_peak_memory_stats()
            try:
                est = DistanceEstimator(bname, temporal=True)
            except torch.cuda.OutOfMemoryError:
                time.sleep(60)
                est = DistanceEstimator(bname, temporal=True)
            times, hd, yh, scl = [], [], [], []
            for fr in seq:
                raw = est.raw_depth_map(fr["image"])
                times.append(est.backend.last_ms)
                sky = getattr(est.backend, "last_sky", None)
                det = est.estimate_detailed(fr["image"], fr["dets"], pts_s=fr["t"], depth_map=raw)
                info = est.last_info
                if info["road_profile"]:
                    hd.append(info["road_profile"]["height_depth_m"])
                    yh.append(info["road_profile"]["horizon_y"])
                scl.append(info["depth_scale"])
                boxes_all = [dd["bbox"] for dd in det]
                for d in det:
                    k = (fr["mot_index"], d["id"])
                    nearer = [bb for bb in boxes_all if bb is not d["bbox"] and bb[3] > d["bbox"][3] + 2]
                    rd = G.box_readout(raw, d["bbox"], d["class"], est.readout_percentile, sky, exclude_boxes=nearer)
                    per[k][f"{bname}:depth_raw"] = None if rd is None else rd[0]
                    per[k][f"{bname}:ground"] = d["components"].get("ground_plane")
                    per[k][f"{bname}:fused_raw"] = d["raw_fused"]
                    per[k][f"{bname}:fused_kf"] = d["distance"]
                    per[k][f"{bname}:conf"] = d["confidence"]
                if fr["mot_index"] == args.panel_mot_index:
                    panel[bname] = (raw.copy(), det, dict(info))
            clip_res[bname] = {
                "latency_ms_p50_provisional": round(float(np.median(times[1:])), 1),
                "peak_torch_alloc_gb": round(torch.cuda.max_memory_allocated() / 1e9, 3) if torch.cuda.is_available() else None,
                "calibration_mode": est.calibration,
                "road_profile_fit_rate": round(len(hd) / max(1, len(seq)), 3),
                "camera_height_from_depth_m": med_iqr(hd),
                "implied_scale_vs_1p3m": None if not hd else round(float(np.median(hd)) / 1.3, 3),
                "depth_profile_horizon_y": med_iqr(yh),
                "final_depth_scale_applied": round(float(scl[-1]), 3) if scl else None,
                "hood_row_detected": est.hood_row(),
            }
            est.close()
            del est
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
            print(f"   {bname}: {time.time() - tb:.0f}s, p50 {clip_res[bname]['latency_ms_p50_provisional']} ms", flush=True)

        # --- agreement statistics on clean vehicles
        attrs_by = {(fr["mot_index"], d.id): (d, fr["attrs"]) for fr in seq for d in fr["dets"]}
        clean = [k for k, (d, a) in attrs_by.items() if good_vehicle(d, a)]
        geo_h = [per[("info", fr["mot_index"])]["geo_horizon"] for fr in seq]
        clip_res["geometry_only"] = {
            "n_clean_vehicle_boxes": len(clean),
            "horizon_y": med_iqr(geo_h),
            "ratio_ground_over_size": med_iqr([per[k]["geo_ground"] / per[k]["size_prior"] for k in clean
                                              if per[k].get("geo_ground") and per[k].get("size_prior")]),
            "jitter_fused_raw": jitter_stats(_series(per, clean, "geo_fused_raw")),
            "jitter_fused_kf": jitter_stats(_series(per, clean, "geo_fused_kf")),
        }
        for bname in args.backends:
            r = clip_res[bname]
            dz = f"{bname}:depth_raw"
            r["ratio_depth_over_ground_geo"] = med_iqr([per[k][dz] / per[k]["geo_ground"] for k in clean
                                                        if per[k].get(dz) and per[k].get("geo_ground")])
            r["ratio_fused_over_geometry_only_fused"] = med_iqr([per[k][f"{bname}:fused_raw"] / per[k]["geo_fused_raw"] for k in clean
                                                                if per[k].get(f"{bname}:fused_raw") and per[k].get("geo_fused_raw")])
            r["ratio_depth_over_size_prior"] = med_iqr([per[k][dz] / per[k]["size_prior"] for k in clean
                                                        if per[k].get(dz) and per[k].get("size_prior")])
            byb = {}
            for lo, hi in BINS:
                ks = [k for k in clean if per[k].get("geo_fused_raw") and lo <= per[k]["geo_fused_raw"] < hi]
                byb[f"{lo}-{hi}m"] = med_iqr([per[k][dz] / per[k]["geo_ground"] for k in ks
                                             if per[k].get(dz) and per[k].get("geo_ground")])
            r["ratio_depth_over_ground_geo_by_bin"] = byb
            widths = []
            for k in clean:
                d, _ = attrs_by[k]
                x1, y1, x2, y2 = d.bbox
                if d.cls == "car" and (x2 - x1) / max(1, y2 - y1) <= 1.4 and per[k].get(dz):
                    widths.append(per[k][dz] * (x2 - x1) / 700.0)
            r["implied_car_width_m_rear_views"] = med_iqr(widths)
            r["jitter_depth_raw"] = jitter_stats(_series(per, clean, dz))
            r["jitter_fused_raw"] = jitter_stats(_series(per, clean, f"{bname}:fused_raw"))
            r["jitter_fused_kf"] = jitter_stats(_series(per, clean, f"{bname}:fused_kf"))
            r["median_confidence"] = med_iqr([per[k].get(f"{bname}:conf") for k in clean])
        results["clips"][vid] = clip_res
        print(json.dumps({b: {kk: clip_res[b][kk] for kk in ("ratio_depth_over_ground_geo", "ratio_depth_over_size_prior",
                                                            "camera_height_from_depth_m", "implied_car_width_m_rear_views")}
                          for b in args.backends}, indent=None), flush=True)

        # --- lead-vehicle timeline (nearest clean-ish vehicle whose centre is in the middle 20% of the frame)
        tl = defaultdict(list)
        for fr in seq:
            cands = [d for d in fr["dets"] if d.cls in VEH and 0.4 * 1280 <= (d.bbox[0] + d.bbox[2]) / 2 <= 0.6 * 1280
                     and d.bbox[3] < 717]
            if not cands:
                continue
            lead = max(cands, key=lambda d: d.bbox[3])
            k = (fr["mot_index"], lead.id)
            for m in ["geo_fused_kf", "size_prior", "geo_ground"] + [f"{b}:{s}" for b in args.backends for s in ("depth_raw", "fused_kf")]:
                tl[m].append((fr["t"], per[k].get(m)))
        timeline[vid] = tl
        _panel(vid, seq, args.panel_mot_index, panel, args.backends)
        del seq

    mpath = OUT / "metrics.json"
    allm = json.loads(mpath.read_text()) if mpath.exists() else {}
    allm["bdd"] = results
    mpath.write_text(json.dumps(allm, indent=1))
    print("wrote", mpath)
    _timeline_plot(timeline, args.backends)


def _series(per, keys, m):
    s = defaultdict(list)
    for (i, tid) in keys:
        v = per[(i, tid)].get(m)
        if v is not None:
            s[tid].append((i / 5.0, v))
    return s


def _panel(vid, seq, idx, panel, backends):
    fr = next((f for f in seq if f["mot_index"] == idx), None)
    if fr is None or not panel:
        return
    tiles = []
    main = backends[-1] if backends else None
    for b in backends:
        if b not in panel:
            continue
        raw, det, info = panel[b]
        rows = [dict(bbox=d["bbox"], distance=d["distance"]) for d in det if d["class"] in VEH | {"pedestrian"}]
        vis = draw_labels(colorize(raw), rows, color=(255, 255, 255))
        cv2.putText(vis, f"{b} depth + fused distance", (10, 28), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255, 255, 255), 2)
        if info.get("horizon_y"):
            y = int(info["horizon_y"])
            cv2.line(vis, (0, y), (1279, y), (0, 255, 255), 1)
        tiles.append(vis)
    geo = panel.get("geo", [])
    rows = [dict(bbox=d["bbox"], distance=d["distance"]) for d in geo if d["class"] in VEH | {"pedestrian"}]
    rgb = draw_labels(fr["image"], rows, color=(0, 255, 0))
    cv2.putText(rgb, "geometry only (flat ground + size prior)", (10, 28), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 0), 2)
    tiles = [rgb] + tiles
    while len(tiles) < 4:
        tiles.append(np.zeros_like(rgb))
    grid = np.vstack([np.hstack(tiles[:2]), np.hstack(tiles[2:4])])
    grid = cv2.resize(grid, (1920, 1080), interpolation=cv2.INTER_AREA)
    p = OUT / f"bdd_{vid}_{CLIPS.get(vid, '')}_panel.png"
    cv2.imwrite(str(p), grid)
    print("wrote", p)


def _timeline_plot(timeline, backends):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    n = len(timeline)
    fig, axes = plt.subplots(n, 1, figsize=(10, 2.8 * n), sharex=True)
    for ax, (vid, tl) in zip(np.atleast_1d(axes), timeline.items()):
        for m, style in [("geo_fused_kf", "k-"), ("size_prior", "k:")] + [(f"{b}:fused_kf", "-") for b in backends] \
                + [(f"{b}:depth_raw", "--") for b in backends]:
            pts = [(t, z) for t, z in tl.get(m, []) if z is not None]
            if pts:
                t, z = zip(*pts)
                ax.plot(t, z, style, lw=1, label=m, alpha=0.85)
        ax.set_ylabel("lead distance (m)")
        ax.set_title(f"{vid} ({CLIPS.get(vid, '')}) - nearest vehicle in the central 20% (no GT depth)", fontsize=9)
        ax.set_ylim(0, 80)
        ax.grid(alpha=0.3)
    np.atleast_1d(axes)[0].legend(fontsize=6, ncol=3)
    np.atleast_1d(axes)[-1].set_xlabel("time (s)")
    fig.tight_layout()
    p = OUT / "bdd_lead_distance_timeline.png"
    fig.savefig(p, dpi=100)
    print("wrote", p)


if __name__ == "__main__":
    main()
