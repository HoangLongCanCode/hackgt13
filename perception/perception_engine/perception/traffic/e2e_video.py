"""End-to-end on video: BDD100K YOLO26s detector + ByteTrack (model.track(persist=True)) ->
TrafficLightClassifier (per-track smoothed state + distance) and SignRecognizer (lisa_crops).

    .venv\\Scripts\\python.exe -m perception.traffic.e2e_video                       # both clips, 5-15 s
    .venv\\Scripts\\python.exe -m perception.traffic.e2e_video --clips b1ff4656-0435391e --backend hsv

Outputs (outputs/traffic/):
  e2e_<clip>.mp4           annotated 1280x720, mp4v, 10 s (default: 2 light clips at 5-15 s, 1 sign clip b9ecc316 at 15-25 s)
  e2e_<clip>.jsonl         one FrameResult per frame (trafficLights, trafficSigns, detections, timingsMs)
  metrics.json "e2e_video" flip rates (raw / vote / hmm), keyframe check vs GT at t=10 s, p50 ms
The keyframe check matches detector light boxes to the 10 s keyframe labels (IoU >= 0.5)
and compares the smoothed state with trafficLightColor. The keyframe is aligned by image:
the frame within +-0.1 s of t = 10 s that best matches images/val/<clip>.jpg (review fix).
"""
from __future__ import annotations

import argparse
import json
import os
import time
from collections import defaultdict

import cv2
import numpy as np

os.environ.setdefault("YOLO_AUTOINSTALL", "False")

from perception.common.schemas import Detection, FrameResult, canonical_class  # noqa: E402
from perception.common.video import DATA_ROOT, OUTPUTS_ROOT, VideoFileInput, all_videos  # noqa: E402
from perception.traffic.eval_bdd import update_metrics  # noqa: E402
from perception.traffic.lights import STATES, TemporalSmoother, TrafficLightClassifier, crop_box, distance_bin  # noqa: E402
from perception.traffic.signs import SignRecognizer  # noqa: E402

OUT = OUTPUTS_ROOT / "traffic"
DET_REPO, DET_REV = "dronefreak/bdd100k-yolo26s", "b11da17c1149b60c0f067eee6588f1780507e064"
TL, TS = 8, 9
STATE_BGR = {"RED": (40, 40, 255), "YELLOW": (0, 210, 255), "GREEN": (80, 220, 60), "UNKNOWN": (180, 180, 180)}


def detector_weights() -> str:
    from huggingface_hub import hf_hub_download
    return hf_hub_download(DET_REPO, "best.pt", revision=DET_REV)


def iou(a, b) -> float:
    ix1, iy1, ix2, iy2 = max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])
    inter = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
    u = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / u if u > 0 else 0.0


def keyframe_gt(video_id: str):
    labels = json.load(open(DATA_ROOT / "labels" / "bdd100k_labels_images_val.json", encoding="utf-8"))
    for im in labels:
        if im["name"] == f"{video_id}.jpg":
            return [l for l in im["labels"] if l["category"] == "traffic light"]
    return []


def flips_per_track_second(seqs: dict, fps: float, ignore_unknown: bool) -> dict:
    flips, secs, n_tracks = 0, 0.0, 0
    for tid, seq in seqs.items():
        s = [x for x in seq if not (ignore_unknown and x == "UNKNOWN")]
        if len(seq) < 5:
            continue
        n_tracks += 1
        secs += len(seq) / fps
        flips += sum(1 for a, b in zip(s, s[1:]) if a != b)
    return {"flips": flips, "track_seconds": round(secs, 2), "tracks": n_tracks,
            "flips_per_track_second": round(flips / secs, 3) if secs else None}


def draw(img, lights, signs, dets_other, t, clip, backend):
    for ls in lights:
        x1, y1, x2, y2 = map(int, ls.bbox)
        col = STATE_BGR[ls.state]
        cv2.rectangle(img, (x1, y1), (x2, y2), col, 2)
        lab = f"{ls.state[0] if ls.state != 'UNKNOWN' else '?'} {int(ls.distanceMeters) if ls.distanceMeters else '?'}m #{ls.id}"
        cv2.putText(img, lab, (x1, max(12, y1 - 4)), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 0, 0), 3, cv2.LINE_AA)
        cv2.putText(img, lab, (x1, max(12, y1 - 4)), cv2.FONT_HERSHEY_SIMPLEX, 0.45, col, 1, cv2.LINE_AA)
    for s in signs:
        x1, y1, x2, y2 = map(int, s.bbox)
        known = s.signClass != "unknown"
        col = (255, 120, 255) if known else (200, 160, 90)
        cv2.rectangle(img, (x1, y1), (x2, y2), col, 2 if known else 1)
        if known:
            lab = f"{s.signClass} {int(s.distanceMeters) if s.distanceMeters else '?'}m"
            cv2.putText(img, lab, (x1, y2 + 14), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 0, 0), 3, cv2.LINE_AA)
            cv2.putText(img, lab, (x1, y2 + 14), cv2.FONT_HERSHEY_SIMPLEX, 0.45, col, 1, cv2.LINE_AA)
    # HUD: the primary light by the relevance heuristic (plan section 11: "RED 120m")
    near = TrafficLightClassifier.primary(lights, img.shape[1])
    if near is not None:
        dist = f"{int(near.distanceMeters)}m ({distance_bin(near.distanceMeters)})" if near.distanceMeters else "?m"
        txt = f"{near.state}  {dist}"
        cv2.rectangle(img, (10, 10), (420, 52), (0, 0, 0), -1)
        cv2.putText(img, txt, (20, 42), cv2.FONT_HERSHEY_SIMPLEX, 0.9, STATE_BGR[near.state], 2, cv2.LINE_AA)
    cv2.putText(img, f"{clip}  t={t:5.2f}s  lights:{backend}+hmm  signs:lisa_crops  (research prototype, informational only)",
                (10, 710), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (255, 255, 255), 1, cv2.LINE_AA)


def run_clip(video_id: str, model, clf: TrafficLightClassifier, rec: SignRecognizer, start_s: float,
             dur_s: float, imgsz: int, conf: float, write_video: bool) -> dict:
    path = next(p for p in all_videos() if p.stem == video_id)
    vin = VideoFileInput(path, start_s=start_s, max_frames=int(round(dur_s * 30)))
    fps = vin.fps
    OUT.mkdir(parents=True, exist_ok=True)
    # Review fix: non-default backends get a suffix; before, the documented '--backend hsv' run overwrote
    # the Autoware e2e_<clip>.jsonl files, so they no longer matched the Autoware mp4s.
    sfx = "" if clf.backend == "autoware_onnx" else f"_{clf.backend}"
    vw = cv2.VideoWriter(str(OUT / f"e2e_{video_id}{sfx}.mp4"), cv2.VideoWriter_fourcc(*"mp4v"), fps, (1280, 720)) if write_video else None
    jl = open(OUT / f"e2e_{video_id}{sfx}.jsonl", "w")
    clf.reset()
    rec.reset()
    raw_seq, vote_seq, hmm_seq = defaultdict(list), defaultdict(list), defaultdict(list)
    vote = TemporalSmoother("vote")
    t_det, t_light, t_sign = [], [], []
    n_lights, n_signs, n_typed = [], [], []
    key_rec = None
    gt_key = keyframe_gt(video_id) if start_s <= 10.0 <= start_s + dur_s else []  # keyframe is at t = 10 s
    key_img = cv2.imread(str(DATA_ROOT / "images" / "val" / f"{video_id}.jpg")) if gt_key else None
    for fi, fr in enumerate(vin):
        img = fr.image
        assert img.shape == (720, 1280, 3), img.shape
        t0 = time.perf_counter()
        r = model.track(img, persist=True, classes=[TL, TS], imgsz=imgsz, conf=conf, tracker="bytetrack.yaml",
                        verbose=False, device=0)[0]
        t_det.append((time.perf_counter() - t0) * 1000)
        dets = []
        if r.boxes is not None and len(r.boxes):
            ids = r.boxes.id.cpu().numpy().astype(int) if r.boxes.id is not None else [None] * len(r.boxes)
            for b, c, s, i in zip(r.boxes.xyxy.cpu().numpy(), r.boxes.cls.cpu().numpy(), r.boxes.conf.cpu().numpy(), ids):
                dets.append(Detection(cls=canonical_class(model.names[int(c)]), bbox=[float(v) for v in b],
                                      confidence=float(s), id=(int(i) if i is not None else None)))
        lights_in = [d for d in dets if d.cls == "traffic light"]
        signs_in = [d for d in dets if d.cls == "traffic sign"]
        t1 = time.perf_counter()
        lights = clf.classify(img, lights_in, pts_s=fr.pts_s)
        t_light.append((time.perf_counter() - t1) * 1000)
        t2 = time.perf_counter()
        signs = rec.recognize(img, signs_in, pts_s=fr.pts_s)
        t_sign.append((time.perf_counter() - t2) * 1000)
        # flip analysis: raw per-frame argmax and vote smoother on the same raw probabilities
        crops = [crop_box(img, d.bbox) for d in lights_in]
        ok = [c is not None and min(d.bbox[2] - d.bbox[0], d.bbox[3] - d.bbox[1]) >= clf.min_box_h for c, d in zip(crops, lights_in)]
        P = clf.probs([c for c, k in zip(crops, ok) if k])
        j = 0
        for d, k, ls in zip(lights_in, ok, lights):
            p = P[j] if k else np.array([0, 0, 0, 1.0])
            j += int(k)
            if d.id is None:
                continue
            raw_seq[d.id].append(STATES[int(np.argmax(p))])
            vote_seq[d.id].append(STATES[vote.update(d.id, p, fr.pts_s)[0]])
            hmm_seq[d.id].append(ls.state)
        n_lights.append(len(lights))
        n_signs.append(len(signs))
        n_typed.append(sum(1 for s in signs if s.signClass != "unknown"))
        res = FrameResult(frameIndex=fr.index, ptsSeconds=fr.pts_s, detections=dets, trafficLights=lights,
                          trafficSigns=signs, timingsMs={"det_track": t_det[-1], "lights": t_light[-1], "signs": t_sign[-1]})
        jl.write(json.dumps(res.to_dict()) + "\n")
        # Review fix: the labeled keyframe is not always the first frame with pts >= 10 s - half a frame
        # (b1ff4656: the keyframe JPEG matches pts 9.977 s, the old rule took 10.010 s). Pick the frame
        # within +-0.1 s whose pixels best match the keyframe JPEG (image alignment only, no labels used).
        kf_diff = None
        if gt_key and abs(fr.pts_s - 10.0) <= 0.1:
            kf_diff = (float(np.mean(np.abs(img.astype(np.float32) - key_img.astype(np.float32))))
                       if key_img is not None else abs(fr.pts_s - 10.0))
        if kf_diff is not None and (key_rec is None or kf_diff < key_rec["align_mean_abs_diff"]):
            key_rec = {"frameIndex": fr.index, "pts_s": round(fr.pts_s, 3), "align_mean_abs_diff": round(kf_diff, 3),
                       "align": "min mean |frame - keyframe jpg| within +-0.1 s" if key_img is not None else "nearest pts",
                       "matches": []}
            for g in gt_key:
                gb = [g["box2d"][k] for k in ("x1", "y1", "x2", "y2")]
                best = max(lights, key=lambda l: iou(l.bbox, gb), default=None)
                bi = iou(best.bbox, gb) if best else 0.0
                key_rec["matches"].append({"gt_color": g["attributes"]["trafficLightColor"], "gt_h": round(gb[3] - gb[1], 1),
                                           "iou": round(bi, 3), "pred": best.state if best and bi >= 0.5 else None})
        if vw is not None:
            vis = img.copy()
            draw(vis, lights, signs, [], fr.pts_s, video_id, clf.backend)
            vw.write(vis)
    vin.release()
    jl.close()
    if vw is not None:
        vw.release()
    if key_rec:
        m = key_rec["matches"]
        matched = [x for x in m if x["pred"] is not None]
        lit = [x for x in matched if x["gt_color"] != "none"]
        key_rec["n_gt"] = len(m)
        key_rec["n_detected_iou50"] = len(matched)
        key_rec["n_lit_matched"] = len(lit)
        key_rec["lit_color_correct"] = sum(1 for x in lit if x["pred"].lower() == x["gt_color"])
    return {
        "clip": video_id, "start_s": start_s, "frames": len(t_det),
        "light_tracks": len(hmm_seq),
        "flip_rate_raw": flips_per_track_second(raw_seq, fps, False),
        "flip_rate_vote": flips_per_track_second(vote_seq, fps, False),
        "flip_rate_hmm": flips_per_track_second(hmm_seq, fps, False),
        "flip_rate_raw_ignoring_unknown": flips_per_track_second(raw_seq, fps, True),
        "flip_rate_hmm_ignoring_unknown": flips_per_track_second(hmm_seq, fps, True),
        "state_share_hmm": {s: round(sum(v.count(s) for v in hmm_seq.values()) / max(1, sum(len(v) for v in hmm_seq.values())), 3) for s in STATES},
        "keyframe_check_t10s": key_rec,
        "p50_ms": {"det_track_yolo26s": round(float(np.median(t_det)), 2), "lights_classify": round(float(np.median(t_light)), 2),
                   "signs_recognize": round(float(np.median(t_sign)), 2)},
        "p95_ms": {"det_track_yolo26s": round(float(np.percentile(t_det, 95)), 2), "lights_classify": round(float(np.percentile(t_light, 95)), 2),
                   "signs_recognize": round(float(np.percentile(t_sign, 95)), 2)},
        "mean_lights_per_frame": round(float(np.mean(n_lights)), 2),
        "mean_signs_per_frame": round(float(np.mean(n_signs)), 2),
        "mean_typed_signs_per_frame": round(float(np.mean(n_typed)), 2),
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--clips", default="b1ff4656-0435391e,b23adb0d-8a7aaced,b9ecc316-529acd3c@15",
                    help="comma list of <video id>[@start_s]; default start is --start")
    ap.add_argument("--backend", default="autoware_onnx", choices=["hsv", "autoware_onnx"])
    ap.add_argument("--start", type=float, default=5.0)
    ap.add_argument("--dur", type=float, default=10.0)
    ap.add_argument("--imgsz", type=int, default=960)
    ap.add_argument("--conf", type=float, default=0.25)
    ap.add_argument("--no-video", action="store_true")
    args = ap.parse_args()
    import torch  # noqa: F401  (before onnxruntime)
    from ultralytics import YOLO

    clf = TrafficLightClassifier(backend=args.backend, smoothing="hmm")
    rec = SignRecognizer(backend="lisa_crops")
    report = {"detector": f"{DET_REPO}@{DET_REV[:8]} imgsz={args.imgsz} conf={args.conf} ByteTrack (Ultralytics model.track persist=True)",
              "lights_backend": args.backend, "smoothing": "hmm (tau 0.35 s, obs_eps 0.25)", "signs_backend": "lisa_crops",
              "timings_note": "PROVISIONAL: GPU shared with other agents; fp32 PyTorch; includes Python overhead", "clips": {}}
    for spec in [c.strip() for c in args.clips.split(",") if c.strip()]:
        vid, _, st = spec.partition("@")
        start = float(st) if st else args.start
        model = YOLO(detector_weights())  # fresh tracker state per clip
        r = run_clip(vid, model, clf, rec, start, args.dur, args.imgsz, args.conf, not args.no_video)
        report["clips"][vid] = r
        print(json.dumps(r, indent=1))
        del model
    try:
        report["cuda_max_mem_allocated_mb"] = round(torch.cuda.max_memory_allocated() / 2**20, 1)
    except Exception:
        pass
    update_metrics(f"e2e_video_{args.backend}", report)


if __name__ == "__main__":
    main()
