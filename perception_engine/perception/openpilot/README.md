# openpilot driving model on BDD100K (EXPERIMENTAL block)

Knuckle Sandwich Robotics Inc. (KSR), HackGT 13 prototype, Perception Engine. This block covers plan §10 (lane lines),
§9/§18 (lead-vehicle distance), §13/§19 (metric road anchors) and §14 (reuse of open AV research). It runs comma.ai's
**openpilot v0.11.1 `driving_vision.onnx`** (MIT) on BDD100K dashcam video. It outputs metric lane lines, lane-line
probabilities, road edges, the lead vehicle (distance, lateral offset, speed) and ego motion (speed, yaw rate).

## Verdict: it works, with one calibration caveat

Everything below was **measured here**, on 13 BDD100K val clips (7 primary and 6 extra).

- **Runtime.** The model loads and runs in onnxruntime-gpu 1.30 with the CUDA EP on the RTX 5060 Laptop (sm_120).
  Provisional p50 (shared GPU): **6.3 ms** for the model and **1.8 ms** for the two warps plus YUV packing, per 0.2 s frame pair.
- **Lane lines.** On marked roads they land on the painted lines. Over the 7 BDD keyframes where the model reports a
  line with p >= 0.5, the median horizontal error against the BDD lane polylines is **4.7 px** (at 1280x720), and a mean
  **72 %** of predicted line rows lie within 20 px of a labelled marking (per clip 0.56 to 0.86; highway day: 0.86 and 6.5 px).
  That figure counts rows beyond the end of the BDD label as misses. Scored only on rows that have GT (85 % of rows),
  it is **86 %** (review addition).
  In intersections, on unmarked residential streets and in rain, the lane probabilities drop, which is the correct
  reaction. There, 6 of the 13 keyframes have no line with p >= 0.5. Counting those keyframes, the GT-side
  **ego-line recall is 11 / 20 = 0.55** (10 / 11 on the 7 keyframes with confident lines, 0 / 9 on the other 6; review addition).
- **Lead distance.** The lead distance agrees with an independent flat-ground estimate of the closest in-path
  YOLO26s box (h = 1.3 m, same camera model). Across the 12 clips with a lead, the median openpilot/geometric ratio is
  **0.96** and the median absolute relative difference is **18 %**. Highway day: ratio 0.96, 9 %, Pearson r 0.84,
  75 % of pairs within 20 %.
- **Ego speed.** Ego speed matches an independent reference: the passing frequency of dashed lane markings, assuming
  the MUTCD 10 ft + 30 ft = 12.19 m cycle. Model speed / reference = **0.97** on the highway clip (80 windows) and
  **0.95** on a night city clip (18 windows). Highway day reads 21.8 to 27.4 m/s (p10 to p90). Stopped segments read
  0.0 m/s.
- **The caveat: focal length.** BDD100K has no intrinsics. The metric **scale of every distance and speed is
  proportional to the assumed focal length**. The task's default f = 700 px gives highway speeds of about 16 m/s and
  distances and speeds about 35 to 40 % too low (700/1140 = 0.61). The dashed-lane reference implies **f of about 1140 px** at 1280x720 (HFOV about 59 deg).
  The API therefore defaults to **f = 1100 px**. Per-clip pitch and yaw come from the model itself (calibrationd-style
  self-calibration). No clip needed a manual horizon.

It is research-grade: openpilot is "ALPHA QUALITY SOFTWARE FOR RESEARCH PURPOSES ONLY". Under plan §38 its outputs
may be **displayed** (lane overlay, "car ahead 18 m"). They must never steer, brake or accelerate, and they are not a
validated safety signal. `driving_policy.onnx`, which holds the plan and desire outputs, is deliberately not run.

## API

```python
import torch                                   # import before onnxruntime (CUDA EP needs torch's CUDA DLLs);
                                               # OpenpilotVision does this itself for backend="ort-cuda"
from perception.openpilot.driving_model import OpenpilotVision, OpenpilotStream, iter_video_pairs

op = OpenpilotVision(backend="ort-cuda", device="cuda",   # or backend="ort-cpu"
                     focal_px=1100.0, image_size=(1280, 720), cam_height_m=1.3)
# 1) calibration: once per clip/camera (about 5-20 s). Or op.set_calibration(rpy=(0, pitch, yaw)) / set_calibration_from_vp((u, v))
cal = op.self_calibrate(lambda: iter_video_pairs(clip_path, stride_s=1.0, end_s=20), passes=3)

# 2a) two BGR frames 0.2 s apart (6 frames at BDD's 30 fps)
res = op.infer(frame_t, frame_t_minus_0p2s)
# 2b) or streaming: push every frame, get a result about every run_every_s
stream = OpenpilotStream(op, run_every_s=0.1)
res = stream.push(frame_bgr, frame_index / fps)            # returns None until a 0.2 s-old frame is buffered

lane_state = op.lane_state(res)          # perception.common.schemas.LaneState (pixel polylines, heuristic lane index)
lead = op.lead_distance(res)             # schemas.DistanceEstimate(method="openpilot_lead") or None if prob < 0.5
px = op.project_result(res)              # {"lane_lines": [Nx2]*4, "road_edges": [Nx2]*2, "lead_uv": [u, v], ...}
j = op.match_lead_to_boxes(res, boxes_xyxy)   # index of the detection box that is openpilot's lead (or None)
```

`infer()` returns a dict. All metric values are in openpilot's **calibrated frame**: x forward, y right, z down, in
metres, with the origin at the camera.

| Key | Content |
|---|---|
| `lane_lines` (4,33,3) | x/y/z at x = 192*(i/32)^2 m (0 to 192 m) for [left-left, ego-left, ego-right, right-right]. On the road, z is about +camera height |
| `lane_probs` (4,) | `sigmoid(lane_lines_prob)[1::2]`, as in `fill_model_msg.py` |
| `lane_line_stds`, `lane_line_y_stds` | lateral std at x=0 (modelV2.laneLineStds) and along x |
| `road_edges` (2,33,3), `road_edge_stds` | [left, right] road edges |
| `lead` | `prob` (3,): P(lead) now / in 2 s / in 4 s. `x, y, v, a` at t=0: x is the distance **from the camera** (openpilot's radard uses x - 1.52 m as dRel), y is right-positive, v is the **absolute** lead speed (relative speed = v - pose speed). Also `x_std`, `v_std`, and the full `traj` (3,6,4) at t = 0..10 s |
| `pose` | `trans` m/s and `rot` rad/s in the device frame (cameraOdometry), plus stds. `speed_mps = trans[0]` |
| `road_transform` | trans/rot. `trans[2]` is the model's camera-height estimate (calibrationd uses it) |
| `meta`, `desire_pred`, `wide_from_device_euler` | parsed but unused |
| `timings_ms` | prep / model / parse |

`lane_topology(res)` gives `currentLane` / `laneCount` from the 4 lines and 2 road edges. **This is a heuristic on
top of openpilot, not an openpilot output.** It is noisy (on the highway clip it flips between 3/3, 3/4, 3/5 and 4/4),
so smooth it over time and fuse it with the map before navigation uses it (§10).

## How the input contract was reproduced (openpilot v0.11.1 source, vendored in `perception/third_party/openpilot`)

1. **Frames.** Two frames **0.2 s apart**, oldest first. `modeld.py` uses `frame_skip = MODEL_RUN_FREQ // MODEL_CONTEXT_FREQ = 20 // 5`
   over a 5-slot queue (`compile_modeld.make_input_queues` / `sample_skip`). The vision net is stateless, so no warm-up is needed.
2. **Warp.** `get_warp_matrix(device_from_calib_euler, K_bdd, bigmodel_frame)` gives the camera_from_model homography.
   `img` uses medmodel (f 910, 512x256, horizon at row 47.6) and `big_img` uses sbigmodel (f 455, horizon at row 151.8).
   BDD has one camera, so both come from the same frame, as openpilot's own single-camera path does. The warp is
   `cv2.warpPerspective(..., WARP_INVERSE_MAP, INTER_LINEAR, BORDER_REPLICATE)`. `K_bdd` is f = 1100, principal point
   at the image centre, roll 0. The numpy ports in `op_geometry.py` match the originals to 2e-16 (`tests_geometry.py`).
3. **YUV.** BT.601 limited-range I420 (`cv2.COLOR_BGR2YUV_I420`, the same range as comma's legacy rgb_to_yuv).
   Channels: `Y[0::2,0::2], Y[1::2,0::2], Y[0::2,1::2], Y[1::2,1::2], U, V`, as in `compile_modeld.frames_to_tensor`.
   `models/README.md` lists the middle two in the other order. An A/B test on the highway clip shows the net does not
   care (ego lane prob 0.814 vs 0.817, speed 24.81 vs 24.86 m/s): the two planes are neighbouring pixels. The code order is kept.
4. **Outputs.** The ONNX output is FP16 [1,1576]. It is sliced with the `output_slices` from the ONNX metadata
   (decoded with a slice-only restricted unpickler) and parsed like `parse_model_outputs.Parser.parse_vision_outputs`.
5. **Calibration.** Roll = 0. Pitch and yaw come from `self_calibrate()`, a port of calibrationd's method: over
   straight-and-fast samples, observed = (0, -atan2(tz, tx), atan2(ty, tx)) of the model's own ego-velocity direction,
   then rpy <- euler(R(rpy) @ R(median observed)), for 3 passes. It converges in 2 to 3 passes. All 13 clips
   calibrated. Their vanishing points span x 542 to 668 and y 231 to 439 px.
   After calibration, the lane-line z at 10 m reads 1.2 to 1.35 m, consistent with the model's own camera-height output.

## Results (measured here, f = 1100 px, 10 Hz, `outputs/openpilot/metrics.json`)

The geometric baseline is the closest dronefreak/bdd100k-yolo26s vehicle whose bottom-centre lies in the ego lane
(openpilot's ego lines if both p >= 0.5, else a +-1.8 m corridor). It is back-projected onto a flat road at h = 1.3 m
with the **same** focal, pitch and yaw. That makes this a **consistency** check of openpilot's geometry (camera-height
prior, pitch, detection of the right vehicle), not an absolute-scale check: both sides scale with the focal length.
Absolute scale comes only from the dashed-lane speed reference. "lead on box" is the fraction of pairs where
openpilot's lead point, projected into the image, falls on the in-path box.

| Clip | Scenario | VP (px) | Cam h (model) | Both ego lines p>=0.5 | Lane width (m) | Keyframe lanes: n / prec@20px / median dx | Speed median (p10-p90) m/s | Dashed-lane ratio (n) | Lead p>=0.5 | Lead pairs | op/geo median | median abs rel diff | within 20% | Pearson r | lead on box |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| b1d968b9-ce42734f | dusk highway heavy traffic | 605, 387 | 1.28 | 0.37 | 2.99 | 3 / 0.62 / 8.2 px | 9.1 (0.0-16.3) | 1.02 (2) | 0.68 | 267 | 1.09 | 0.20 | 0.51 | 0.69 | 0.85 |
| b1f4491b-cf446195 | highway day | 605, 271 | 1.22 | 0.85 | 3.37 | 2 / 0.86 / 6.5 px | 24.9 (21.8-27.4) | 0.97 (80) | 1.00 | 361 | 0.96 | 0.09 | 0.75 | 0.84 | 0.90 |
| b1ff4656-0435391e | city intersection day | 581, 367 | 1.25 | 0.06 | 3.14 | 0 lines | 5.4 (0.1-7.4) | - | 0.94 | 362 | 0.99 | 0.11 | 0.71 | 0.56 | 0.64 |
| b204a5c1-064b0040 | snow city | 607, 288 | 1.31 | 0.31 | 3.04 | 3 / 0.86 / 4.7 px | 9.4 (0.0-12.2) | 0.85 (1) | 0.24 | 61 | 1.20 | 0.20 | 0.46 | 0.79 | 0.77 |
| b2064e61-2beadd45 | rainy "highway" night (a city street in practice) | 668, 418 | 1.36 | 0.08 | 3.44 | 1 / 0.60 / 0.7 px | 2.1 (0.0-10.4) | - | 0.45 | 168 | 0.96 | 0.06 | 0.96 | 0.97 | 1.00 |
| b20e291a-6012d836 | residential day | 542, 300 | 1.21 | 0.15 | 2.94 | 0 lines | 4.5 (0.0-16.0) | - | 0.78 | 275 | 0.81 | 0.21 | 0.43 | 0.89 | 0.19 |
| b23adb0d-8a7aaced | night city | 628, 231 | 1.23 | 0.14 | 2.75 | 1 / 0.56 / 3.0 px | 11.4 (5.8-15.2) | - | 0.44 | 159 | 0.95 | 0.32 | 0.33 | 0.72 | 0.79 |
| b23d2079-0c019d8e | night city (extra) | 631, 353 | 1.21 | 0.43 | 3.37 | 2 / 0.69 / 3.8 px | 11.4 (0.0-14.6) | 0.95 (18) | 0.00 | 0 | - | - | - | - | - |
| b780088d-0bf9e946 | city intersection (extra) | 631, 297 | 1.38 | 0.21 | 2.81 | 0 lines | 6.0 (4.0-11.8) | 0.96 (2) | 0.92 | 363 | 1.05 | 0.11 | 0.65 | 0.75 | 0.71 |
| b9ecc316-529acd3c | rain city (extra) | 609, 365 | 1.25 | 0.04 | 3.87 | 0 lines | 7.5 (0.0-18.1) | - | 0.49 | 147 | 0.82 | 0.19 | 0.61 | 0.91 | 0.92 |
| bc07d865-f526e4d9 | highway day (extra) | 638, 439 | 1.38 | 0.22 | 3.21 | 1 / 0.85 / 9.2 px | 4.2 (0.0-11.2) | - | 0.90 | 335 | 0.94 | 0.17 | 0.58 | 0.97 | 0.90 |
| bf1af616-e750e7dd | dusk heavy traffic (extra) | 592, 329 | 1.21 | 0.03 | 3.34 | 0 lines | 5.9 (0.2-13.4) | - | 0.97 | 366 | 0.92 | 0.12 | 0.69 | 0.63 | 0.85 |
| c38cd3f6-c74ca48b | residential (extra) | 571, 403 | 1.27 | 0.11 | 2.89 | 0 lines | 8.3 (0.0-14.2) | - | 0.35 | 88 | 0.72 | 0.28 | 0.11 | 0.89 | 0.97 |

Keyframe lanes: at each clip's 10 s keyframe, the frame is matched to `images/val/<id>.jpg` by MSE and paired with the
frame 0.2 s earlier. Each predicted line with p >= 0.5 is projected with the calibration. For each image row from
VP+15 px to the model's lowest visible row, the horizontal distance to the nearest BDD `lane` poly2d
(laneDirection = parallel; Bezier segments flattened, see "Review fixes") is measured. `prec@20px` counts rows where
no GT polyline exists (BDD lanes usually stop well short of the vanishing point) as misses. `prec@20px_labeled_rows`
scores only rows that have GT. `median_dx_px` is over labelled rows. The review added a GT-side **ego recall** over all
13 keyframes, including the 6 where openpilot predicts nothing: it takes the nearest painted marking on each side of
the ego path (flat-ground lateral offset up to 3 m) and counts it as recalled when a p >= 0.5 line lies within 20 px on
at least half of its rows.

**Focal sweep** on the highway and city clips (`focal_sweep.png`). The model speed scales linearly with the assumed
focal. On the highway clip, speed is 15.9 / 20.4 / 24.8 / 28.4 m/s at f = 700 / 900 / 1100 / 1300, and model/dash-speed
is 0.62 / 0.80 / 0.97 / 1.12, which crosses 1.0 at about 1140 px. Ego lane-line probabilities are nearly flat on the
highway clip (0.78 to 0.81, highest at 1100) and rise with f on the city clip (0.21 to 0.35). The lead/geometric ratio does not depend on f (0.92 to 0.98), as expected, since both sides scale together.

**Qualitative checks** (`overlay_*.mp4`, `keyframe_*.jpg`):
- Highway (b1f4491b, 2 to 12 s): the ego lines sit on the painted lines. openpilot's lead marker sits on the YOLO box
  of the car ahead. During the lane change at about 6 to 9 s, all lane probabilities drop to about 0.
- City (b1ff4656, 0.5 to 10.5 s): the ego-left line follows the double yellow. In the frame at t = 4.5 s, openpilot's lead
  reads 13.5 m and the geometric estimate reads 13.4 m.
- Night (b23adb0d, 0.5 to 10.5 s): the ego-left line holds the double yellow at p 0.94. In the frame at t = 7.2 s, no false
  lead is reported (p 0.12), while the corridor baseline picks up a parked car.

## Outputs (`outputs/openpilot/`)

- `metrics.json`: per-clip calibration (rpy, VP, calibration history), lane, speed, lead and dashed-lane statistics,
  keyframe alignment, timings, focal sweep, Y-order A/B and a global summary.
- `timeseries_<clip>.png`: ego speed with dashed-lane reference points, lead x vs geometric distance, and lane/lead probabilities.
- `keyframe_<clip>.jpg`: projected lines (green = ego, orange = outer, red = road edges) over the BDD GT lanes (white).
  The magenta cross is the calibrated vanishing point. `_f700`/`_f900`/... are the sweep variants; `_modeld`/`_readme` are the A/B variants.
- `model_input_views_b1f4491b.png`: what the net actually sees (top: narrow medmodel, bottom: wide sbigmodel).
- `focal_sweep.png`
- `overlay_b1f4491b-cf446195.mp4`, `overlay_b1ff4656-0435391e.mp4`, `overlay_b23adb0d-8a7aaced.mp4`: 10 s each, 960x540, mp4v.
- `timeline_<clip>.jsonl` (7 primary clips, 10 Hz): `frameIndex`, `ptsSeconds`, `lanes` (LaneState), `distances`
  ([DistanceEstimate]) and `openpilot` {egoSpeedMps, leadProb, leadX, leadY, leadV, leadRelV, laneProbs, laneWidthM, calibRpy, focalPx}.

## Reproduce

From `perception_engine/`:

```
.venv\Scripts\python.exe -m perception.openpilot.tests_geometry        # ports == openpilot v0.11.1 (needs third_party checkout)
.venv\Scripts\python.exe -m perception.openpilot.eval_bdd              # full: 13 clips + focal sweep + Y-order A/B (about 10 min)
.venv\Scripts\python.exe -m perception.openpilot.eval_bdd --quick      # 7 primary clips only
.venv\Scripts\python.exe -m perception.openpilot.eval_bdd --render-only  # re-draw overlays/timelines from stored calibrations
```

Options: `--focal 700` reproduces the task's original assumption, and `--clips <id> ...` runs a subset. The first run
needs `models\openpilot\driving_vision.onnx` (see `MODELS.md`) and the cached YOLO26s BDD checkpoint.

## Backends tried

| Backend | Status |
|---|---|
| onnxruntime-gpu 1.30 CUDA EP (FP16 graph as shipped) | **works**. Default. Session arena capped at 1 GB. Measured VRAM, as the change in total GPU memory (WDDM reports per-process usage as N/A): about 0.17 GB for the openpilot session, about 0.36 GB with the YOLO baseline |
| onnxruntime CPU EP | works (`backend="ort-cpu"`), not benchmarked |
| tinygrad (openpilot's own runtime), onnx2torch | not tried: not installed, and not needed |

## Licenses

- openpilot code and `driving_vision.onnx`: **MIT** (Copyright (c) 2018 Comma.ai), plus the README's indemnification
  clause and research-only disclaimer. No third-party dataset license is known to be inherited (comma's fleet data /
  learned simulator). Details in `MODELS.md`.
- The evaluation baseline `dronefreak/bdd100k-yolo26s` is AGPL-3.0 with BDD100K (non-commercial) weights, for evaluation only.
- BDD100K data: research / non-commercial only. Everything in `outputs/openpilot/` is derived from BDD100K video.

## Caveats and known failure modes

- **Scale is only as good as the focal length.** f = 1100 is a BDD-wide default fitted on 2 clips (1130 and 1160 px
  implied). BDD was filmed on many different phones, so per-clip focal can differ. The dashed-lane method needs a clip
  with a dashed line and assumes the 12.19 m MUTCD cycle; other patterns (such as 12/36 ft) would shift it by up to about 20 %.
  **Other blocks that use f = 700 px for BDD flat-ground geometry will underestimate distances by about 35 to 40 %.**
- The lead-vs-geometric agreement is not independent of the focal length. It checks consistency, not absolute accuracy.
  The flat-ground baseline also breaks on slopes (c38cd3f6: ratio 0.72 while the projected lead sits on the right box 97 % of the time)
  and when the +-1.8 m corridor catches parked cars on curving streets (b20e291a: lead on box 0.19).
- Lane lines are conservative off-highway. In intersections, unmarked residential streets, rain and parts of night
  driving, both ego lines are rarely >= 0.5. During lane changes the lane probabilities collapse. The pixel polylines are
  cut at the model's lowest visible row (12.9 deg below the horizon, about 250 px below the VP at f = 1100). Points
  below that are extrapolated and usually fall on the bonnet.
- With a high horizon (for example VP y = 271), the wide view's top edge lies above the BDD frame, so those rows are replicated border.
  This only affects sky and had no visible effect.
- Speed jitters by about +-1 m/s near standstill (city min -1.6 m/s). Clamp at 0 for display.
- Only one lead (the in-path car) is reported, with no id. Associate it with tracker ids with `match_lead_to_boxes`.
- Calibration assumes roll = 0 and needs some straight driving. On a new camera, run `self_calibrate` once and cache
  the rpy (all 13 clips' values are in `metrics.json` -> `clips.<id>.calibration.rpy`).
- `perception.common.video.VideoFileInput.pts_s` is **the previous frame's pts**, because CAP_PROP_POS_MSEC is read
  before `read()`. BDD .mov files also have irregular pts in their first about 8 frames (duplicates and a negative step).
  This block therefore clocks by `frame.index / fps`. The shared file was not modified.
- Latency figures are provisional: the GPU was shared with up to 6 other agents.

## Review fixes (reviewer pass, 2026-09-25)

**Reproduction.** The reviewer re-ran `.venv\Scripts\python.exe -m perception.openpilot.eval_bdd` (full run, about 10 to 12 min)
with the builder's code before changing anything. Every accuracy number in `metrics.json` reproduced exactly, because the
pipeline is deterministic: the 13 per-clip calibrations, speeds, lead statistics, dashed-lane ratios, keyframe alignment,
the focal sweep (highway 15.85 / 20.36 / 24.81 / 28.42 m/s, dash ratio 0.62 / 0.80 / 0.97 / 1.12) and the Y-order A/B.
The 7 `timeline_*.jsonl` files are byte-identical. Only the provisional latency moves with GPU contention: model p50 was
12.4 ms in the first reviewer run (other agents plus reviewer tests on the GPU) and 6.5 ms in the second.
`tests_geometry` passes (2.3e-16), and the API imports cleanly from `perception_engine/`.

**Independent checks (not part of `eval_bdd.py`).**
- The dashed-lane speed was re-measured with a different method on b1f4491b: FFT of brightness in fixed image patches
  placed on the BDD GT dashed line, with no openpilot geometry. Over four 3 s windows (9 to 21 s), model / reference is
  0.95 to 1.05 (about 0.99) at f = 1100. This confirms the builder's 0.97 and the rejection of f = 700 px. It still rests
  on the 12.19 m MUTCD cycle assumption.
- The parser matches `parse_model_outputs.parse_vision_outputs` in v0.11.1: lead is non-MHP (144 = 2x3x6x4), the meta
  indices match `constants.Meta`, and the Y order matches `frames_to_tensor`.
- The vision net is stateless: the same pair gives identical outputs.
- Warping with INTER_NEAREST (what openpilot's tinygrad warp does) instead of INTER_LINEAR changes nothing that matters:
  median speed 24.77 vs 24.56 m/s, ego-line prob 0.805 vs 0.805.
- Swapping the two frames does not flip the sign of the speed (the pose head never saw reverse motion), so the sign
  cannot validate frame order. The code order does match `shift_and_sample` / `sample_skip` (oldest first).
- `OpenpilotStream` + `lane_state()` + `lead_distance()` run end to end and return `schemas.LaneState` / `DistanceEstimate`.

**Fixes in `eval_bdd.py`.** No accuracy number was wrong in a way that flatters the model.
1. *GT rasterisation.* `gt_lane_polys` used scalabel poly2d vertices as a plain polyline. Lanes with `types` such as
   `CCCL` are cubic Beziers, so the control points were being joined as if they were vertices. Parallel lanes like that appear in 6 of the
   13 keyframes (b2064e61, b23adb0d, b9ecc316, bc07d865, bf1af616, c38cd3f6), mostly on road curbs. 3 of them are among
   the 7 scored keyframes. The new `_flatten_poly2d` uses the same
   convention as `tools/bdd/build_eval_subset.py`. **Effect: none on the reported numbers**, because the nearest
   GT to each predicted line was never one of those curves. The keyframe overlays now draw the curves correctly.
2. *Metric definition.* `prec@tau` counted rows with no GT at all (BDD lanes usually stop well before the vanishing point)
   as misses, while `median_dx_px` silently skipped them. Both are now labelled. The review adds `labeled_row_frac` and
   `prec@tau_labeled_rows`, plus a `markings_only` variant without road curbs. On the 7 keyframes: prec@20px 0.72 (as before),
   labelled-row prec@20px **0.86**, labelled-row fraction 0.85, and markings-only 0.72 / labelled 0.87.
3. *Keyframes with no predictions were ignored.* The headline lane figure only covered the 7 keyframes with a p >= 0.5 line.
   The builder left a comment for a "GT-side hit rate" but no code. `ego_gt_recall` implements it over all 13 keyframes:
   **11 / 20 ego-side markings recalled at 20 px** (`summary.keyframe_ego_gt_recall@20px` = 0.55). Caveat: the ego GT is
   chosen by flat-ground lateral offset up to 3 m, so a marking the car is crossing (b1ff4656, b20e291a: about 0.1 m offset) also counts.
4. The docstring's runtime estimate was corrected (about 10 to 12 min, not 20 to 30).

The reviewer's run log is `outputs/openpilot/eval_log_review.txt`. The builder's original `eval_log.txt` is kept.

**Not changed (flagged for the integrator).**
- The absolute scale (f about 1100 to 1140 px) is supported by the dashed-lane method on 2 clips plus the reviewer's
  independent FFT check on 1 clip. All of them assume the 12.19 m dash cycle.
- There is no `RoadGeometry` adapter (plan §13: vanishing point / horizon / anchors) and no single `FrameResult` call.
  Use `op.vanishing_point`, `project_result()`, `lane_state()` and `lead_distance()`.
- `perception.common.video.VideoFileInput.pts_s` lagging by one frame is confirmed (CAP_PROP_POS_MSEC is read before
  `read()`). This is shared code and was not modified.
