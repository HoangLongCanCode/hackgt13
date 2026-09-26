r"""Plausibility evaluation of openpilot driving_vision (v0.11.1) on BDD100K val clips.

There is no metric 3D ground truth in BDD100K, so this script measures CONSISTENCY, not accuracy:
  1. self-calibration (calibrationd-style pitch/yaw from the model's own ego-motion direction);
  2. lane lines: projected ego lines vs BDD keyframe lane polylines (pixel error, precision@tau);
  3. lead: openpilot lead x vs a flat-ground geometric distance of the closest in-path vehicle from
     dronefreak/bdd100k-yolo26s (same focal / pitch / yaw, camera height h = 1.3 m);
  4. ego speed: openpilot pose vs an independent speed reference from the passing frequency of dashed
     lane markings (MUTCD 10 ft dash + 30 ft gap = 12.19 m cycle) -> also yields a focal-length estimate;
  5. focal-length sweep and Y-plane-order A/B test;
  6. overlay PNGs, time-series plots, <= 3 short annotated clips, a JSONL timeline per primary clip.

Reproduce (from perception_engine/):
  .venv\Scripts\python.exe -m perception.openpilot.eval_bdd             # full run (~10-12 min, GPU)
  .venv\Scripts\python.exe -m perception.openpilot.eval_bdd --quick     # 7 primary clips, no sweep
Outputs: outputs\openpilot\{metrics.json, *.png, *.mp4, timeline_*.jsonl}
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from collections import deque
from pathlib import Path

os.environ.setdefault("YOLO_AUTOINSTALL", "False")
import torch  # noqa: F401  (must precede onnxruntime: CUDA EP DLLs)
import numpy as np
import cv2

from perception.openpilot.driving_model import (OpenpilotVision, iter_video_pairs, lane_topology,
                                                MODEL_URL, MODEL_SHA256)
from perception.openpilot import op_geometry as G
from perception.common.video import VideoFileInput, DATA_ROOT, OUTPUTS_ROOT, primary_videos, all_videos

OUT = OUTPUTS_ROOT / "openpilot"
SCENARIO = {
    "b1f4491b-cf446195": "highway day", "b1ff4656-0435391e": "city intersection day",
    "b20e291a-6012d836": "residential day", "b23adb0d-8a7aaced": "night city",
    "b204a5c1-064b0040": "snow city", "b1d968b9-ce42734f": "dusk highway heavy traffic",
    "b2064e61-2beadd45": "rainy highway night", "bc07d865-f526e4d9": "highway day (extra)",
    "b780088d-0bf9e946": "city intersection (extra)", "c38cd3f6-c74ca48b": "residential (extra)",
    "b23d2079-0c019d8e": "night city (extra)", "b9ecc316-529acd3c": "rain city (extra)",
    "bf1af616-e750e7dd": "dusk heavy traffic (extra)",
}
VEHICLES = {"car", "truck", "bus", "motor", "motorcycle", "train"}
DASH_CYCLE_M = 12.19   # MUTCD broken line: 10 ft segment + 30 ft gap
H_GEO = 1.3            # camera height for the geometric baseline (task spec)
DEFAULT_FOCAL = 1100.0


# ----------------------------------------------------------------------------- detector
class VehicleDetector:
    """dronefreak/bdd100k-yolo26s (Ultralytics, AGPL-3.0 weights trained on BDD100K) - baseline only."""

    def __init__(self, imgsz: int = 960, conf: float = 0.3):
        from huggingface_hub import hf_hub_download
        from ultralytics import YOLO
        self.path = hf_hub_download("dronefreak/bdd100k-yolo26s", "best.pt")  # HF cache (models\huggingface)
        self.model = YOLO(self.path)
        self.imgsz, self.conf = imgsz, conf
        self.names = self.model.names

    def __call__(self, img):
        r = self.model.predict(img, imgsz=self.imgsz, conf=self.conf, device=0, verbose=False)[0]
        b = r.boxes
        xyxy = b.xyxy.cpu().numpy().astype(np.float64)
        cls = [self.names[int(c)] for c in b.cls.cpu().numpy()]
        conf = b.conf.cpu().numpy()
        keep = [i for i, c in enumerate(cls) if c in VEHICLES and (xyxy[i, 3] - xyxy[i, 1]) >= 10]
        return xyxy[keep], [cls[i] for i in keep], conf[keep]


# ----------------------------------------------------------------------------- helpers
def find_video(vid: str) -> Path:
    for sub in ("val", "val_extra"):
        p = DATA_ROOT / "videos" / sub / f"{vid}.mov"
        if p.exists():
            return p
    raise FileNotFoundError(vid)


def load_keyframe_labels() -> dict[str, dict]:
    d = json.load(open(DATA_ROOT / "labels" / "eval_subset_det.json", encoding="utf-8"))
    return {e["name"].rsplit(".", 1)[0]: e for e in d}


def _flatten_poly2d(p: dict) -> np.ndarray:
    """scalabel poly2d -> dense polyline. 'L' = vertex; two consecutive 'C' = the control points of a cubic
    Bezier from the previous vertex to the next one (same convention as tools/bdd/build_eval_subset.py;
    e.g. types 'CCCL' = one cubic from v0 to v3). [review fix: control points used to be treated as vertices]"""
    v = np.asarray(p["vertices"], dtype=np.float64)
    types = p.get("types") or "L" * len(v)
    t = np.linspace(0, 1, 16)[1:, None]
    pts, i, n = [v[0]], 1, len(v)
    while i < n:
        if types[i] == "C" and i + 2 < n and types[i + 1] == "C":
            p0, c1, c2, end = pts[-1], v[i], v[i + 1], v[i + 2]
            pts.extend(((1 - t) ** 3) * p0 + 3 * ((1 - t) ** 2) * t * c1 + 3 * (1 - t) * t ** 2 * c2 + t ** 3 * end)
            i += 3
        else:
            pts.append(v[i])
            i += 1
    return np.asarray(pts)


def gt_lane_polys(entry: dict, markings_only: bool = False) -> list[np.ndarray]:
    """BDD 'lane' poly2d with laneDirection = parallel (Bezier segments flattened).
    markings_only drops laneType 'road curb' (openpilot reports curbs as road edges, not lane lines)."""
    polys = []
    for l in entry.get("labels", []):
        a = l.get("attributes", {})
        if l.get("category") != "lane" or a.get("laneDirection") != "parallel":
            continue
        if markings_only and a.get("laneType") == "road curb":
            continue
        for p in l.get("poly2d", []):
            v = _flatten_poly2d(p)
            if len(v) >= 2:
                polys.append(v)
    return polys


def _x_at_rows(poly: np.ndarray, rows: np.ndarray) -> np.ndarray:
    """x of a polyline at the given image rows (NaN where the polyline does not cross the row)."""
    out = np.full(len(rows), np.nan)
    for a, b in zip(poly[:-1], poly[1:]):
        y0, y1 = a[1], b[1]
        if abs(y1 - y0) < 1e-6:
            continue
        lo, hi = min(y0, y1), max(y0, y1)
        m = (rows >= lo) & (rows <= hi) & np.isnan(out)
        s = (rows[m] - y0) / (y1 - y0)
        out[m] = a[0] + s * (b[0] - a[0])
    return out


def lane_alignment(op: OpenpilotVision, res: dict, gt: list[np.ndarray], row_band: tuple[float, float],
                   min_prob: float = 0.5, taus=(10, 20, 40)) -> dict:
    rows = np.arange(np.ceil(row_band[0]), np.floor(row_band[1]), 4.0)
    if len(rows) == 0 or not gt:
        return {"note": "no rows or no GT lanes"}
    gtx = np.stack([_x_at_rows(p, rows) for p in gt])            # (n_gt, n_rows)
    per_line, all_dx = {}, []
    for i in range(4):
        if res["lane_probs"][i] < min_prob:
            continue
        uv = op.project_line(res["lane_lines"][i], 0.0, 150.0, clip=False)
        if len(uv) < 2:
            continue
        o = np.argsort(uv[:, 1])
        px = np.interp(rows, uv[o, 1], uv[o, 0], left=np.nan, right=np.nan)
        ok = np.isfinite(px) & (px > -50) & (px < op.image_size[0] + 50)
        if ok.sum() < 3:
            continue
        d = np.abs(gtx[:, ok] - px[ok][None, :])
        dmin = np.where(np.isfinite(d).any(0), np.nanmin(np.where(np.isfinite(d), d, np.inf), 0), np.inf)
        per_line[f"line{i}"] = {"prob": float(res["lane_probs"][i]), "n_rows": int(ok.sum()),
                                "median_dx_px": float(np.median(dmin)) if np.isfinite(dmin).any() else None,
                                **{f"prec@{t}px": float(np.mean(dmin <= t)) for t in taus}}
        all_dx.append(dmin)
    if not all_dx:
        return {"n_pred_lines": 0, "per_line": {}}
    dx = np.concatenate(all_dx)
    lab = np.isfinite(dx)  # rows where at least one GT polyline exists (BDD lanes often stop well short of the VP)
    # prec@tau counts unlabelled rows as misses (pessimistic); prec@tau_labeled_rows only scores rows that have GT;
    # median_dx_px is over labelled rows. [review fix: the two used to be reported without saying so]
    return {"n_pred_lines": len(all_dx), "n_gt_polylines": len(gt),
            "median_dx_px": float(np.median(dx[lab])) if lab.any() else None,
            **{f"prec@{t}px": float(np.mean(dx <= t)) for t in taus},
            "labeled_row_frac": float(lab.mean()),
            **{f"prec@{t}px_labeled_rows": (float(np.mean(dx[lab] <= t)) if lab.any() else None) for t in taus},
            "per_line": per_line, "row_band": [float(row_band[0]), float(row_band[1])]}


def ego_gt_recall(op: OpenpilotVision, res: dict, gt: list[np.ndarray], row_band: tuple[float, float],
                  min_prob: float = 0.5, tau: float = 20.0, max_lat_m: float = 3.0, min_rows: int = 3) -> dict:
    """GT-side check that also scores keyframes where openpilot predicts nothing [review addition].

    Ego GT = the labelled painted marking polyline nearest to the ego path on each side (flat-ground lateral
    offset, median over its rows in the band, 0 < |y| <= max_lat_m). It counts as recalled if some predicted
    line with p >= min_prob lies within tau px on >= 50 % of that GT's rows in the band."""
    rows = np.arange(np.ceil(row_band[0]), np.floor(row_band[1]), 4.0)
    cands = []
    for g in gt:
        gx = _x_at_rows(g, rows)
        ok = np.isfinite(gx)
        if ok.sum() < min_rows:
            continue
        gp = G.backproject_to_ground(np.stack([gx[ok], rows[ok]], 1), op.rpy, op.K, op.cam_height_m)
        lat = float(np.nanmedian(gp[:, 1]))
        if np.isfinite(lat) and 0 < abs(lat) <= max_lat_m:
            cands.append((lat, gx))
    sides = {"left": max((c for c in cands if c[0] < 0), key=lambda c: c[0], default=None),
             "right": min((c for c in cands if c[0] > 0), key=lambda c: c[0], default=None)}
    preds = []
    for i in range(4):
        if res["lane_probs"][i] < min_prob:
            continue
        uv = op.project_line(res["lane_lines"][i], 0.0, 150.0, clip=False)
        if len(uv) < 2:
            continue
        o = np.argsort(uv[:, 1])
        preds.append(np.interp(rows, uv[o, 1], uv[o, 0], left=np.nan, right=np.nan))
    out = {}
    for s, c in sides.items():
        if c is None:
            out[s] = None
            continue
        lat, gx = c
        ok = np.isfinite(gx)
        best = max((float(np.mean(np.nan_to_num(np.abs(p[ok] - gx[ok]), nan=np.inf) <= tau)) for p in preds), default=0.0)
        out[s] = {"lat_m": round(lat, 2), "hit_frac": best, "hit": bool(best >= 0.5)}
    n = sum(v is not None for v in out.values())
    return {"n_ego_gt": n, "n_hit": sum(bool(v and v["hit"]) for v in out.values()), "sides": out}


def in_path_vehicle(op: OpenpilotVision, res: dict, boxes: np.ndarray, cls: list[str], h: float):
    """Closest vehicle whose bottom-centre lies in the ego lane (openpilot ego lines if confident,
    else a +-1.8 m corridor). Returns (index, x_ground_m, y_ground_m) or None."""
    if len(boxes) == 0:
        return None
    bc = np.stack([(boxes[:, 0] + boxes[:, 2]) / 2, boxes[:, 3]], 1)
    g = G.backproject_to_ground(bc, op.rpy, op.K, h)
    ll, pr = res["lane_lines"], res["lane_probs"]
    best = None
    for j in range(len(boxes)):
        x, y = g[j, 0], g[j, 1]
        if not np.isfinite(x) or x < 2 or x > 120:
            continue
        if pr[1] >= 0.5 and pr[2] >= 0.5:
            yl = np.interp(x, G.X_IDXS, ll[1, :, 1]); yr = np.interp(x, G.X_IDXS, ll[2, :, 1])
            c, half = 0.5 * (yl + yr), max(1.2, 0.5 * (yr - yl))
        else:
            c, half = 0.0, 1.8
        if abs(y - c) <= half and (best is None or x < best[1]):
            best = (j, float(x), float(y))
    return best


def dash_reference(ts: np.ndarray, sig: np.ndarray, probs: np.ndarray, spd: np.ndarray, fps: float,
                   win_s: float = 3.0, hop_s: float = 0.5, min_ac: float = 0.4, min_prob: float = 0.7):
    """Speed from the temporal period of brightness along a dashed ego lane line (x ~ 9-11 m)."""
    win, hop = int(win_s * fps), int(hop_s * fps)
    out = []
    for a in range(0, len(sig) - win, hop):
        w = sig[a:a + win]
        if np.isnan(w).any() or probs[a:a + win].mean() < min_prob:
            continue
        w = w - np.convolve(w, np.ones(15) / 15, mode="same")
        w = w[7:-7]
        ac = np.correlate(w, w, "full")[len(w) - 1:]
        ac = ac / (ac[0] + 1e-9)
        lo, hi = int(0.2 * fps), int(1.5 * fps)
        l = lo + int(np.argmax(ac[lo:hi]))
        if ac[l] < min_ac:
            continue
        lf = float(l)
        if 0 < l < len(ac) - 1:
            y0, y1, y2 = ac[l - 1], ac[l], ac[l + 1]
            lf = l + 0.5 * (y0 - y2) / (y0 - 2 * y1 + y2 + 1e-9)
        T = lf / fps
        out.append({"t0": float(ts[a]), "T_s": T, "ac_peak": float(ac[l]), "v_ref_mps": DASH_CYCLE_M / T,
                    "v_model_mps": float(np.median(spd[a:a + win]))})
    return out


def sample_line_brightness(gray: np.ndarray, op: OpenpilotVision, res: dict, li: int) -> float:
    vals = []
    ll = res["lane_lines"][li]
    for x in np.linspace(9, 11, 5):
        y = np.interp(x, ll[:, 0], ll[:, 1]); z = np.interp(x, ll[:, 0], ll[:, 2])
        uv = op.project_points(np.array([[x, y, z]]))[0]
        if not np.isfinite(uv).all():
            continue
        u0, v0 = int(uv[0]), int(uv[1])
        if 20 < u0 < gray.shape[1] - 20 and 0 <= v0 < gray.shape[0]:
            vals.append(gray[v0, u0 - 15:u0 + 16].max())
    return float(np.mean(vals)) if vals else np.nan


# ----------------------------------------------------------------------------- drawing
LANE_COL = [(255, 160, 0), (0, 230, 0), (0, 230, 0), (255, 160, 0)]  # BGR


def draw_overlay(img: np.ndarray, op: OpenpilotVision, res: dict, rec: dict | None = None,
                 boxes=None, gt: list[np.ndarray] | None = None, title: str = "") -> np.ndarray:
    out = img.copy()
    if gt:
        for p in gt:
            cv2.polylines(out, [p.astype(np.int32)], False, (255, 255, 255), 1, cv2.LINE_AA)
    pr = op.project_result(res, x_max=100.0)
    for i, uv in enumerate(pr["lane_lines"]):
        p = float(res["lane_probs"][i])
        if len(uv) > 1 and p > 0.1:
            cv2.polylines(out, [uv.astype(np.int32)], False, LANE_COL[i], max(1, int(round(1 + 4 * p))), cv2.LINE_AA)
    for uv in pr["road_edges"]:
        if len(uv) > 1:
            cv2.polylines(out, [uv.astype(np.int32)], False, (0, 0, 255), 2, cv2.LINE_AA)
    vp = op.vanishing_point
    cv2.drawMarker(out, (int(vp[0]), int(vp[1])), (255, 0, 255), cv2.MARKER_CROSS, 16, 1)
    ld = res["lead"]
    if rec is not None and rec.get("geo_idx") is not None and boxes is not None and len(boxes):
        b = boxes[rec["geo_idx"]].astype(int)
        cv2.rectangle(out, (b[0], b[1]), (b[2], b[3]), (0, 200, 255), 2)
        cv2.putText(out, f"geo {rec['geo_x']:.1f} m", (b[0], max(15, b[1] - 6)), cv2.FONT_HERSHEY_SIMPLEX, 0.6,
                    (0, 200, 255), 2, cv2.LINE_AA)
    if ld["prob"][0] > 0.5 and pr["lead_uv"] is not None:
        u, v = [int(a) for a in pr["lead_uv"]]
        cv2.drawMarker(out, (u, v), (0, 255, 255), cv2.MARKER_TRIANGLE_UP, 22, 3)
        cv2.putText(out, f"op lead {ld['x']:.1f} m", (u + 14, v + 22), cv2.FONT_HERSHEY_SIMPLEX, 0.6,
                    (0, 255, 255), 2, cv2.LINE_AA)
    lines = [title, f"ego {res['pose']['speed_mps']:.1f} m/s  lane p {' '.join(f'{p:.2f}' for p in res['lane_probs'])}",
             f"lead p {ld['prob'][0]:.2f} x {ld['x']:.1f} m v {ld['v']:.1f} m/s (rel {ld['v'] - res['pose']['speed_mps']:+.1f})"]
    if rec is not None and rec.get("current_lane"):
        lines.append(f"lane {rec['current_lane']}/{rec['lane_count']} (heuristic)")
    lines = [l for l in lines if l]
    if lines:
        wmax = max(cv2.getTextSize(l, cv2.FONT_HERSHEY_SIMPLEX, 0.7, 2)[0][0] for l in lines)
        panel = out[0:14 + 28 * len(lines), 0:24 + wmax]
        panel[:] = (panel * 0.35).astype(np.uint8)
    for k, s in enumerate(lines):
        cv2.putText(out, s, (12, 30 + 28 * k), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 255, 255), 2, cv2.LINE_AA)
    return out


# ----------------------------------------------------------------------------- per-clip run
def calibrate_clip(op: OpenpilotVision, path: Path, passes: int = 3) -> dict:
    op.set_calibration(rpy=(0.0, 0.0, 0.0))
    cal = op.self_calibrate(lambda: iter_video_pairs(path, stride_s=0.5), passes=passes, min_speed_mps=4.0)
    if not cal["ok"]:  # slow traffic: relax the speed gate once more
        cal = op.self_calibrate(lambda: iter_video_pairs(path, stride_s=0.5), passes=passes, min_speed_mps=2.0,
                                min_samples=3)
        cal["relaxed_gate"] = True
    return cal


def run_clip(op: OpenpilotVision, det: VehicleDetector | None, vid: str, kf_entry: dict | None,
             stride: int = 3, video_range: tuple[float, float] | None = None, timeline: bool = False,
             label: str = "", rpy=None) -> dict:
    path = find_video(vid)
    t0 = time.time()
    if rpy is None:
        cal = calibrate_clip(op, path)
    else:  # re-use a stored calibration (render-only mode)
        op.set_calibration(rpy=rpy)
        cal = {"ok": True, "rpy": list(rpy), "vp": op.vanishing_point.tolist(), "history": [], "height_m": None}
    t_cal = time.time() - t0
    v = VideoFileInput(path)
    fps = v.fps
    gap = int(round(G.FRAME_GAP_S * fps))
    prepared: dict[int, object] = {}
    raw_hist: deque = deque(maxlen=gap + 1)
    kf_img = None
    if kf_entry is not None:
        kf_path = DATA_ROOT / "images" / "val" / f"{vid}.jpg"
        kf_img = cv2.imread(str(kf_path)).astype(np.float32) if kf_path.exists() else None
    kf_best = (np.inf, None, None, None)
    recs, ts_all, sig_l, sig_r, prob_all, spd_all = [], [], [], [], [], []
    last = None
    writer = None
    tl_lines = []
    model_ms, prep_ms, det_ms = [], [], []
    for fr in v:
        i = fr.index
        t = i / fps
        raw_hist.append(fr.image)
        if kf_img is not None and 9.4 <= t <= 10.6 and len(raw_hist) == gap + 1:
            mse = float(np.mean((fr.image.astype(np.float32) - kf_img) ** 2))
            if mse < kf_best[0]:
                kf_best = (mse, i, fr.image.copy(), raw_hist[0].copy())
        if i % stride == 0:
            tp = time.perf_counter()
            prepared[i] = op.prepare(fr.image)
            prep_ms.append((time.perf_counter() - tp) * 1e3)
            prepared.pop(i - 3 * gap, None)
            if (i - gap) in prepared and t >= 0.4:
                res = op.infer_prepared(prepared[i - gap], prepared[i])
                model_ms.append(res["timings_ms"]["model"])
                boxes, cls, conf = (np.zeros((0, 4)), [], np.zeros(0))
                if det is not None:
                    td = time.perf_counter()
                    boxes, cls, conf = det(fr.image)
                    det_ms.append((time.perf_counter() - td) * 1e3)
                topo = lane_topology(res)
                ld = res["lead"]
                rec = {"t": t, "i": i, "lane_probs": res["lane_probs"].tolist(),
                       "y10": [topo["y"][k] for k in range(4)], "edges10": topo["edges"],
                       "lane_width": topo["lane_width_m"], "current_lane": topo["current_lane"],
                       "lane_count": topo["lane_count"],
                       "z10": float(np.interp(10, G.X_IDXS, 0.5 * (res["lane_lines"][1, :, 2] + res["lane_lines"][2, :, 2]))),
                       "lead_prob": ld["prob"].tolist(), "lead_x": ld["x"], "lead_y": ld["y"], "lead_v": ld["v"],
                       "lead_a": ld["a"], "lead_x_std": ld["x_std"], "speed": res["pose"]["speed_mps"],
                       "trans": res["pose"]["trans"].tolist(), "rot": res["pose"]["rot"].tolist(),
                       "height": float(res["road_transform"]["trans"][2]), "geo_idx": None, "geo_x": None}
                ip = in_path_vehicle(op, res, boxes, cls, H_GEO)
                if ip is not None:
                    j, gx, gy = ip
                    rec.update(geo_idx=j, geo_x=gx, geo_y=gy, geo_cls=cls[j], geo_conf=float(conf[j]),
                               geo_box=boxes[j].tolist())
                    pr = op.project_result(res)
                    if pr["lead_uv"] is not None:
                        u, vv = pr["lead_uv"]
                        b = boxes[j]
                        bh = b[3] - b[1]
                        rec["lead_in_box"] = bool(b[0] - 5 <= u <= b[2] + 5 and abs(vv - b[3]) <= 0.35 * bh + 8)
                    mj = op.match_lead_to_boxes(res, boxes)
                    rec["lead_matches_inpath"] = (mj == j)
                recs.append(rec)
                last = (res, boxes, rec)
                if timeline:
                    ls = op.lane_state(res)
                    dist = op.lead_distance(res, vehicle_id=-1)
                    tl_lines.append(json.dumps({
                        "frameIndex": i, "ptsSeconds": round(t, 4), "lanes": ls.to_dict() if hasattr(ls, "to_dict") else ls,
                        "distances": [dist.to_dict()] if dist is not None and hasattr(dist, "to_dict") else [],
                        "openpilot": {"egoSpeedMps": round(res["pose"]["speed_mps"], 2),
                                      "leadProb": round(float(ld["prob"][0]), 3), "leadX": round(ld["x"], 2),
                                      "leadY": round(ld["y"], 2), "leadV": round(ld["v"], 2),
                                      "leadRelV": round(ld["v"] - res["pose"]["speed_mps"], 2),
                                      "laneProbs": [round(float(p), 3) for p in res["lane_probs"]],
                                      "laneWidthM": None if topo["lane_width_m"] is None else round(topo["lane_width_m"], 2),
                                      "calibRpy": [round(float(a), 5) for a in op.rpy], "focalPx": op.focal_px},
                    }))
        if last is not None:
            gray = cv2.cvtColor(fr.image, cv2.COLOR_BGR2GRAY)
            ts_all.append(t)
            sig_l.append(sample_line_brightness(gray, op, last[0], 1))
            sig_r.append(sample_line_brightness(gray, op, last[0], 2))
            prob_all.append(min(last[0]["lane_probs"][1], last[0]["lane_probs"][2]))
            spd_all.append(last[0]["pose"]["speed_mps"])
            if video_range and video_range[0] <= t <= video_range[1]:
                if writer is None:
                    OUT.mkdir(parents=True, exist_ok=True)
                    writer = cv2.VideoWriter(str(OUT / f"overlay_{vid}.mp4"), cv2.VideoWriter_fourcc(*"mp4v"),
                                             fps, (960, 540))
                o = draw_overlay(fr.image, op, last[0], last[2], last[1],
                                 title=f"{vid} {SCENARIO.get(vid, '')} t={t:.1f}s f={op.focal_px:.0f}px")
                writer.write(cv2.resize(o, (960, 540), interpolation=cv2.INTER_AREA))
    v.release()
    if writer is not None:
        writer.release()
    if timeline and tl_lines:
        (OUT / f"timeline_{vid}.jsonl").write_text("\n".join(tl_lines) + "\n", encoding="utf-8")

    # ---- keyframe lane alignment
    kf = None
    if kf_best[1] is not None and kf_entry is not None:
        res = op.infer(kf_best[2], kf_best[3])
        gt = gt_lane_polys(kf_entry)
        vp = op.vanishing_point
        band = (vp[1] + 15, min(op.image_size[1] - 1, vp[1] + 0.229 * op.focal_px, 560.0))
        kf = {"frame_index": int(kf_best[1]), "mse_vs_jpg": float(kf_best[0]), **lane_alignment(op, res, gt, band)}
        gt_mk = gt_lane_polys(kf_entry, markings_only=True)
        mk = lane_alignment(op, res, gt_mk, band)
        kf["markings_only"] = {k: mk.get(k) for k in ("n_gt_polylines", "median_dx_px", "prec@20px",
                                                      "labeled_row_frac", "prec@20px_labeled_rows")}
        kf["ego_gt_recall"] = ego_gt_recall(op, res, gt_mk, band)
        OUT.mkdir(parents=True, exist_ok=True)
        img = draw_overlay(kf_best[2], op, res, gt=gt, title=f"{vid} keyframe {label} (white = BDD GT lanes)")
        cv2.imwrite(str(OUT / f"keyframe_{vid}{label}.jpg"), img, [cv2.IMWRITE_JPEG_QUALITY, 88])
        if vid == "b1f4491b-cf446195" and not label:
            mv = np.vstack([op.warp(kf_best[2], False), op.warp(kf_best[2], True)])
            cv2.imwrite(str(OUT / "model_input_views_b1f4491b.png"), mv)

    # ---- summaries
    ts_all, spd_all = np.array(ts_all), np.array(spd_all)
    probs_all = np.array(prob_all)
    dash = {"left": dash_reference(ts_all, np.array(sig_l), probs_all, spd_all, fps),
            "right": dash_reference(ts_all, np.array(sig_r), probs_all, spd_all, fps)}
    return {"vid": vid, "scenario": SCENARIO.get(vid, ""), "fps": fps, "gap_frames": gap,
            "calibration": cal, "calib_s": t_cal, "records": recs, "dash": dash, "keyframe": kf,
            "timing": {"model_ms_p50": float(np.median(model_ms)) if model_ms else None,
                       "prep_ms_p50": float(np.median(prep_ms)) if prep_ms else None,
                       "det_ms_p50": float(np.median(det_ms)) if det_ms else None,
                       "wall_s": time.time() - t0}}


def summarize(run: dict) -> dict:
    R = run["records"]
    if not R:
        return {"n": 0}
    P = np.array([r["lane_probs"] for r in R])
    both = (P[:, 1] >= 0.5) & (P[:, 2] >= 0.5)
    W = np.array([r["lane_width"] for r in R if r["lane_width"] is not None and r["lane_probs"][1] >= 0.5 and r["lane_probs"][2] >= 0.5])
    Z = np.array([r["z10"] for r in R])
    spd = np.array([r["speed"] for r in R])
    lp = np.array([r["lead_prob"][0] for r in R])
    lx = np.array([r["lead_x"] for r in R])
    gx = np.array([np.nan if r["geo_x"] is None else r["geo_x"] for r in R])
    hgt = np.array([r["height"] for r in R])
    lc = [(r["current_lane"], r["lane_count"]) for r in R if r["current_lane"] is not None]
    pair = (lp >= 0.5) & np.isfinite(gx) & (gx < 100)
    lead = {"frac_lead_prob>=0.5": float(np.mean(lp >= 0.5)),
            "frac_inpath_detection": float(np.mean(np.isfinite(gx) & (gx < 100))),
            "presence_agreement": float(np.mean((lp >= 0.5) == (np.isfinite(gx) & (gx < 80)))),
            "n_pairs": int(pair.sum())}
    if pair.sum() >= 5:
        rel = (lx[pair] - gx[pair]) / gx[pair]
        hm = np.median(hgt)
        gx_hm = gx[pair] * hm / H_GEO
        lead.update({
            "median_op_over_geo": float(np.median(lx[pair] / gx[pair])),
            "median_abs_rel_diff": float(np.median(np.abs(rel))),
            "frac_within_20pct": float(np.mean(np.abs(rel) <= 0.2)),
            "pearson_r": float(np.corrcoef(lx[pair], gx[pair])[0, 1]) if pair.sum() > 2 else None,
            "median_op_over_geo_h_model": float(np.median(lx[pair] / gx_hm)),
            "median_abs_rel_diff_h_model": float(np.median(np.abs((lx[pair] - gx_hm) / gx_hm))),
            "op_x_range_m": [float(np.percentile(lx[pair], 5)), float(np.percentile(lx[pair], 95))],
        })
        inbox = [r.get("lead_in_box") for r, m in zip(R, pair) if m and r.get("lead_in_box") is not None]
        match = [r.get("lead_matches_inpath") for r, m in zip(R, pair) if m and r.get("lead_matches_inpath") is not None]
        lead["frac_projected_lead_on_inpath_box"] = float(np.mean(inbox)) if inbox else None
        lead["frac_lead_matched_to_inpath_box"] = float(np.mean(match)) if match else None
    dash_all = run["dash"]["left"] + run["dash"]["right"]
    dash_s = None
    if dash_all:
        ratio = np.array([d["v_model_mps"] / d["v_ref_mps"] for d in dash_all])
        vref = np.array([d["v_ref_mps"] for d in dash_all])
        dash_s = {"n_windows": len(dash_all), "median_v_ref_mps": float(np.median(vref)),
                  "median_model_over_ref": float(np.median(ratio)),
                  "iqr_model_over_ref": [float(np.percentile(ratio, 25)), float(np.percentile(ratio, 75))]}
    counts = {}
    for c in lc:
        counts[f"{c[0]}/{c[1]}"] = counts.get(f"{c[0]}/{c[1]}", 0) + 1
    return {
        "n_inferences": len(R),
        "lanes": {"mean_probs": P.mean(0).round(3).tolist(), "frac_both_ego_lines>=0.5": float(both.mean()),
                  "median_lane_width_m": float(np.median(W)) if len(W) else None,
                  "median_lane_z10_m": float(np.median(Z)), "current/count_hist": counts},
        "speed": {"median_mps": float(np.median(spd)), "p10": float(np.percentile(spd, 10)),
                  "p90": float(np.percentile(spd, 90)), "min": float(spd.min()), "max": float(spd.max())},
        "height_model_median_m": float(np.median(hgt)),
        "lead": lead, "dash_speed_reference": dash_s,
    }


# ----------------------------------------------------------------------------- plots
def plot_timeseries(run: dict, path: Path) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    R = run["records"]
    t = np.array([r["t"] for r in R])
    fig, ax = plt.subplots(3, 1, figsize=(11, 8.5), sharex=True)
    ax[0].plot(t, [r["speed"] for r in R], color="#1f5fbf", lw=1.5, label="openpilot pose speed")
    D = run["dash"]["left"] + run["dash"]["right"]
    if D:
        ax[0].scatter([d["t0"] + 1.5 for d in D], [d["v_ref_mps"] for d in D], s=14, color="#d9730d",
                      label="dashed-lane reference (12.19 m cycle)", zorder=3)
    ax[0].set_ylabel("m/s"); ax[0].legend(loc="upper left", fontsize=8); ax[0].grid(alpha=.3)
    lx = np.array([r["lead_x"] if r["lead_prob"][0] >= 0.5 else np.nan for r in R])
    gx = np.array([np.nan if r["geo_x"] is None else r["geo_x"] for r in R])
    ax[1].plot(t, lx, color="#1f5fbf", lw=1.5, label="openpilot lead x (prob >= 0.5)")
    ax[1].plot(t, gx, ".", color="#d9730d", ms=3, label=f"flat-ground distance of closest in-path box (h={H_GEO} m)")
    ax[1].set_ylabel("m"); ax[1].set_ylim(0, 100); ax[1].legend(loc="upper left", fontsize=8); ax[1].grid(alpha=.3)
    P = np.array([r["lane_probs"] for r in R])
    for k, (nm, c) in enumerate(zip(["left-left", "ego-left", "ego-right", "right-right"],
                                     ["#9aa7b8", "#2a9d3a", "#1b6b28", "#6d7a8c"])):
        ax[2].plot(t, P[:, k], color=c, lw=1.2, label=nm)
    ax[2].plot(t, [r["lead_prob"][0] for r in R], color="#b0413e", lw=1, ls="--", label="lead prob")
    ax[2].set_ylabel("prob"); ax[2].set_ylim(-0.02, 1.02); ax[2].legend(loc="lower left", fontsize=8, ncol=5)
    ax[2].grid(alpha=.3); ax[2].set_xlabel("clip time (s)")
    cal = run["calibration"]
    fig.suptitle(f"{run['vid']} ({run['scenario']}), f={run.get('focal', 0):.0f}px, "
                 f"calib pitch={np.degrees(cal['rpy'][1]):.1f} deg yaw={np.degrees(cal['rpy'][2]):.1f} deg", fontsize=11)
    fig.tight_layout()
    fig.savefig(path, dpi=110)
    plt.close(fig)


def plot_sweep(sweep: dict, path: Path) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, ax = plt.subplots(1, 4, figsize=(16, 3.8))
    for vid, rows in sweep.items():
        fs = sorted(rows, key=float)
        f = [float(x) for x in fs]
        ax[0].plot(f, [rows[x]["speed"]["median_mps"] for x in fs], "o-", label=vid)
        dr = [(rows[x].get("dash_speed_reference") or {}).get("median_model_over_ref", np.nan) for x in fs]
        if np.isfinite(dr).any():
            ax[1].plot(f, dr, "o-", label=vid)
        ax[2].plot(f, [0.5 * (rows[x]["lanes"]["mean_probs"][1] + rows[x]["lanes"]["mean_probs"][2]) for x in fs], "o-", label=vid)
        ax[3].plot(f, [rows[x]["lead"].get("median_op_over_geo", np.nan) for x in fs], "o-", label=vid)
    ax[0].axhspan(20, 35, color="#2a9d3a", alpha=.08)
    ax[1].axhline(1.0, color="k", lw=0.8, ls="--")
    ax[3].axhline(1.0, color="k", lw=0.8, ls="--"); ax[3].set_ylim(0.6, 1.4)
    for a, t in zip(ax, ["median ego speed (m/s)", "model speed / dashed-lane speed", "mean ego lane-line prob",
                         "median openpilot lead / geometric distance"]):
        a.set_title(t, fontsize=10); a.set_xlabel("assumed focal (px @1280x720)"); a.grid(alpha=.3)
    ax[0].legend(fontsize=7)
    fig.tight_layout(); fig.savefig(path, dpi=110); plt.close(fig)


# ----------------------------------------------------------------------------- main
def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--focal", type=float, default=DEFAULT_FOCAL)
    ap.add_argument("--quick", action="store_true", help="7 primary clips, no sweep / A-B")
    ap.add_argument("--clips", nargs="*", default=None)
    ap.add_argument("--no-videos", action="store_true")
    ap.add_argument("--render-only", action="store_true",
                    help="re-render demo clips + keyframe overlays with the calibrations stored in metrics.json")
    ap.add_argument("--sweep-focals", nargs="*", type=float, default=[700, 900, 1100, 1300])
    args = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    kfl = load_keyframe_labels()
    vids = args.clips or [p.stem for p in (primary_videos() if args.quick else all_videos())]
    op = OpenpilotVision(device="cuda", focal_px=args.focal)
    det = VehicleDetector()
    t_start = time.time()
    metrics = {
        "block": "openpilot", "status": "experimental",
        "model": {"name": "openpilot v0.11.1 driving_vision.onnx", "url": MODEL_URL, "sha256": MODEL_SHA256,
                  "providers": op.providers},
        "config": {"focal_px": args.focal, "image_size": list(op.image_size), "principal_point": "image centre",
                   "roll": 0.0, "pitch_yaw": "self-calibrated per clip (calibrationd-style)",
                   "model_rate_hz": 10, "frame_gap": "6 frames (0.2 s)", "h_geo_m": H_GEO,
                   "detector": "dronefreak/bdd100k-yolo26s imgsz 960 conf 0.3",
                   "dash_cycle_m": DASH_CYCLE_M},
        "clips": {}, "notes": [],
    }
    demo = {"b1f4491b-cf446195": (2.0, 12.0), "b1ff4656-0435391e": (0.5, 10.5), "b23adb0d-8a7aaced": (0.5, 10.5)}
    if args.render_only:
        stored_all = json.load(open(OUT / "metrics.json", encoding="utf-8"))
        if stored_all.get("focal_sweep"):
            plot_sweep(stored_all["focal_sweep"], OUT / "focal_sweep.png")
        stored = stored_all["clips"]
        for vid, c in stored.items():
            print(f"[render] {vid}", flush=True)
            rng = None if args.no_videos else demo.get(vid)
            prim = vid in {p.stem for p in primary_videos()}
            run_clip(op, det if rng else None, vid, kfl.get(vid), stride=3 if (rng or prim) else 30,
                     video_range=rng, rpy=c["calibration"]["rpy"], timeline=prim)
        return 0
    runs = {}
    for vid in vids:
        print(f"[run] {vid} f={args.focal}", flush=True)
        run = run_clip(op, det, vid, kfl.get(vid), video_range=None if args.no_videos else demo.get(vid),
                       timeline=vid in {p.stem for p in primary_videos()})
        run["focal"] = args.focal
        runs[vid] = run
        s = summarize(run)
        s.update(scenario=run["scenario"], calibration={k: run["calibration"][k] for k in ("ok", "rpy", "vp", "height_m")},
                 calibration_history=run["calibration"]["history"], keyframe_lane_alignment=run["keyframe"],
                 timing=run["timing"])
        metrics["clips"][vid] = s
        plot_timeseries(run, OUT / f"timeseries_{vid}.png")
        print(json.dumps({k: s[k] for k in ("lanes", "speed", "lead", "dash_speed_reference")}, default=float)[:900], flush=True)
        json.dump(metrics, open(OUT / "metrics.json", "w"), indent=1, default=float)

    if not args.quick:
        # focal sweep (+ keyframe alignment) on the two validation clips
        sweep = {}
        for vid in ("b1f4491b-cf446195", "b1ff4656-0435391e"):
            sweep[vid] = {}
            for f in args.sweep_focals:
                op.set_calibration(focal_px=f)
                print(f"[sweep] {vid} f={f}", flush=True)
                run = run_clip(op, det, vid, kfl.get(vid), stride=6, label=f"_f{int(f)}")
                s = summarize(run)
                sweep[vid][str(int(f))] = {"lanes": s["lanes"], "speed": s["speed"], "lead": s["lead"],
                                           "dash_speed_reference": s["dash_speed_reference"],
                                           "keyframe": run["keyframe"], "calibration_vp": run["calibration"]["vp"]}
        metrics["focal_sweep"] = sweep
        plot_sweep(sweep, OUT / "focal_sweep.png")
        # Y-plane order A/B (compile_modeld vs README wording), highway clip, f = args.focal
        ab = {}
        for yo in ("modeld", "readme"):
            op.y_order = yo
            op.set_calibration(focal_px=args.focal)
            run = run_clip(op, None, "b1f4491b-cf446195", kfl.get("b1f4491b-cf446195"), stride=6, label=f"_{yo}")
            s = summarize(run)
            ab[yo] = {"lanes": s["lanes"], "speed": s["speed"], "calibration": run["calibration"]["rpy"],
                      "lead_frac": s["lead"]["frac_lead_prob>=0.5"], "keyframe": run["keyframe"]}
        op.y_order = "modeld"
        metrics["y_order_ab"] = ab
        op.set_calibration(focal_px=args.focal)

    # ---- global summary
    C = metrics["clips"]
    dash_ratios = [c["dash_speed_reference"]["median_model_over_ref"] for c in C.values()
                   if c.get("dash_speed_reference") and c["dash_speed_reference"]["n_windows"] >= 5]
    kfs = [c["keyframe_lane_alignment"] for c in C.values() if c.get("keyframe_lane_alignment")
           and c["keyframe_lane_alignment"].get("n_pred_lines")]
    kf_all = [c["keyframe_lane_alignment"] for c in C.values() if c.get("keyframe_lane_alignment")]
    rec_n = sum(k.get("ego_gt_recall", {}).get("n_ego_gt", 0) for k in kf_all)
    rec_hit = sum(k.get("ego_gt_recall", {}).get("n_hit", 0) for k in kf_all)
    kfs_lab = [k["prec@20px_labeled_rows"] for k in kfs if k.get("prec@20px_labeled_rows") is not None]
    kfs_mk = [k["markings_only"]["prec@20px"] for k in kfs
              if k.get("markings_only", {}).get("prec@20px") is not None]
    lead_pairs = [c["lead"] for c in C.values() if c["lead"].get("median_abs_rel_diff") is not None]
    metrics["summary"] = {
        "clips_run": len(C),
        "latency_ms_p50_provisional": {
            "model": float(np.median([c["timing"]["model_ms_p50"] for c in C.values()])),
            "prep_two_warps_plus_yuv": float(np.median([c["timing"]["prep_ms_p50"] for c in C.values()]))},
        "calibration_ok": int(sum(c["calibration"]["ok"] for c in C.values())),
        "dash_speed_model_over_ref_median": float(np.median(dash_ratios)) if dash_ratios else None,
        "implied_focal_px_from_dash": float(args.focal / np.median(dash_ratios)) if dash_ratios else None,
        "n_clips_with_dash_ref": len(dash_ratios),
        "keyframe_lane_prec@20px_mean": float(np.mean([k["prec@20px"] for k in kfs])) if kfs else None,
        "keyframe_lane_median_dx_px_median": float(np.median([k["median_dx_px"] for k in kfs if k["median_dx_px"] is not None])) if kfs else None,
        "n_keyframes_with_pred_lines": len(kfs),
        # review additions (see README 'Review fixes')
        "keyframe_lane_prec@20px_labeled_rows_mean": float(np.mean(kfs_lab)) if kfs_lab else None,
        "keyframe_lane_labeled_row_frac_mean": float(np.mean([k["labeled_row_frac"] for k in kfs])) if kfs else None,
        "keyframe_lane_prec@20px_markings_only_mean": float(np.mean(kfs_mk)) if kfs_mk else None,
        "keyframe_ego_gt_recall@20px": (rec_hit / rec_n) if rec_n else None,
        "keyframe_ego_gt_n": rec_n, "keyframe_ego_gt_hits": rec_hit, "n_keyframes": len(kf_all),
        "lead_vs_geo_median_abs_rel_diff_median": float(np.median([l["median_abs_rel_diff"] for l in lead_pairs])) if lead_pairs else None,
        "lead_vs_geo_median_ratio_median": float(np.median([l["median_op_over_geo"] for l in lead_pairs])) if lead_pairs else None,
        "wall_s": time.time() - t_start,
    }
    json.dump(metrics, open(OUT / "metrics.json", "w"), indent=1, default=float)
    print(json.dumps(metrics["summary"], indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
