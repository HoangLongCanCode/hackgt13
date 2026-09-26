# Lanes and drivable area (plan §10, feeds §13)

This block is part of the Perception Engine for the AI Spatial Driving Copilot, the HackGT 13 prototype by Knuckle Sandwich Robotics Inc. (KSR). For each frame it produces three things:

- a lane-line mask and a drivable-area mask,
- a `LaneState` with `{currentLane, laneCount, laneBoundaries}` (plan §10),
- lane-based `RoadGeometry` for AR anchoring (plan §13): the ego-lane polygon, the vanishing point / horizon, and anchor points.

The lane state is a **perception observation**. It is not a driving instruction and it drives no actuation (plan §38). When `confidence` is low, the Driving Context Engine should fall back to side-only guidance ("keep right") or to the map prior (OSM `lanes` / `turn:lanes`).

**Default backend:** `twinlitenetplus_large`. Use `twinlitenetplus_medium` when the shared GPU is the bottleneck. `yolop_onnx` is a cross-check. `comma10k_segnet` is the only commercially clean option, but it is weak on the BDD100K metrics.

## API

```python
import cv2
from perception.lanes import LaneDetector            # run from perception_engine/

det = LaneDetector(backend="twinlitenetplus_large",   # | twinlitenetplus_medium | yolop_onnx | comma10k_segnet
                   device="cuda",                     # "cuda", "cuda:0" or "cpu"
                   temporal=False)                    # True for video: line tracker + hysteresis smoother
frame = cv2.imread(r"data\bdd100k\images\val\b1f4491b-cf446195.jpg")   # BGR, upright, 16:9 (1280x720)

masks = det.infer(frame)          # {"lane_mask", "drivable_mask"} uint8 {0,1} at frame size
                                  # (+ "movable_mask", "ego_car_mask" for comma10k_segnet)
state = det.lane_state(frame)     # perception.common.schemas.LaneState
a     = det.analyze(frame)        # LaneAnalysis: one forward pass for everything below
a.lane_state   # LaneState(currentLane=2, laneCount=3, laneBoundaries=[[[x, y], ...], ...], confidence=0.81)
a.road         # RoadGeometry(drivableCoverage, egoPathPolygon, horizonY, vanishingPoint, anchorPoints)
a.masks        # lane_mask, drivable_mask, ego_lane_mask (derived BDD 'direct' area)
a.extras       # per-boundary side/colour/style/u, lanesLeft/Right, egoLateralOffsetNorm, roadEdgesU, event, ...
a.timings_ms   # pre_ms, model_ms, post_ms, segment_ms, lanestate_ms, masks_ms, total_ms

# Optional: vehicle boxes from the section 7 detector, so that a car in the next lane is not
# mistaken for the road edge. comma10k_segnet uses its own 'movable' class automatically.
a = det.analyze(frame, occluder_boxes=[[x1, y1, x2, y2], ...])
det.reset()   # between clips when temporal=True
det.close()
```

Output conventions:

- Lanes are numbered 1..laneCount **from the left**, the same order as OSM `turn:lanes`. `laneBoundaries` holds every accepted lane-line instance, left to right, as source-pixel polylines.
- `extras["boundaries"][i]` gives each polyline's `side` (`ego_left`, `ego_right`, `left_k`, `right_k` or `other`), `color` (yellow / white / unknown), `style` (solid / dashed / unknown, heuristic), `u` (lateral position in lane widths, camera at 0) and `confidence`.
- `extras["event"]` is `LANE_CHANGE_LEFT_OBSERVED` / `LANE_CHANGE_RIGHT_OBSERVED` (temporal mode only). It reports an observation, not a command.
- No backend separates BDD100K "direct" from "alternative" drivable area, so `infer()` has no `drivable_alt_mask`. Instead, `analyze()` derives the ego lane (`ego_lane_mask`, `road.egoPathPolygon`) from the chosen ego boundaries.

## Results (measured here, 2026-09-25)

All numbers come from the 250-image stratified BDD100K val subset (`data/bdd100k/labels/eval_subset_det.json`) and are stored in `outputs/lanes/metrics.json`. Per-image values are in `outputs/lanes/per_image_<backend>.csv`.

| Backend | Lane IoU @640x360 (TLNP/YOLOP protocol) | Lane IoU @1280x720 | Lane F @2 / 5 / 10 px | Lane F, BDD convention @1 / 2 / 5 px | Drivable mIoU @640x360 (YOLOP GT) | Drivable IoU / mIoU @1280x720 | Ego lane vs BDD "direct" IoU |
|---|---|---|---|---|---|---|---|
| **twinlitenetplus_large** | **34.4** (paper 34.2) | 26.7 | 60.8 / 73.8 / 77.8 | 53.7 / 63.3 / 75.5 | **92.4** (paper 92.9) | 87.2 / 92.1 | 63.3 |
| twinlitenetplus_medium | 32.5 (paper 32.3) | 25.5 | 56.0 / 68.4 / 72.7 | 49.6 / 58.5 / 70.2 | 91.7 (paper 92.0) | 86.0 / 91.4 | 62.7 |
| yolop_onnx | 27.4 (paper 26.2) | 21.0 | 52.4 / 68.8 / 76.1 | 44.3 / 54.0 / 69.8 | 90.9 (paper 91.5) | 84.2 / 90.3 | 65.1 |
| comma10k_segnet | 11.2 | 9.8 | 22.2 / 28.8 / 34.3 | 23.3 / 26.9 / 33.1 | 82.2 | 71.1 / 81.4 | 43.2 |

How to read the table:

- The table was re-run by the reviewer after the review fixes (see "Review fixes" below). "Lane F @2 / 5 / 10 px" leaves out the 8 to 16 images where both GT and prediction are empty. "BDD convention" scores those images as F = 1 and uses the official radii {1, 2, 5}, as `bdd100k/eval/lane.py` does; it is category-agnostic and uses a cv2 ellipse kernel.
- The TLNP+ and YOLOP numbers reproduce their published BDD100K val results to within 1.2 points on this subset. That confirms the pre-processing ports (letterbox, colour order, normalisation, crop) are correct.
- Lane IoU on the day, night and dawn/dusk slices, and on the city, highway and residential slices, stays within ±2.5 points for TLNP+ Large. The slices are in `metrics.json`.
- **comma10k-segnet** is not comparable on lane IoU. Its label set marks only painted dashes and excludes crosswalks, curbs and dash gaps, while the BDD / YOLOP GT draws continuous centre lines over all 8 categories. Its "road" class also includes opposite-direction lanes, which BDD does not count as drivable. It is still the commercially clean candidate for a Level-2 fine-tune.
- Feeding comma10k-segnet RGB or BGR gives the same results (drivable IoU 71.0 vs 70.4, lane IoU 11.2 vs 11.4). The pipeline keeps RGB, which is the albumentations convention.

### Lane state (`laneCount` / `currentLane`)

| Reference | TLNP+ L | TLNP+ M | YOLOP | comma10k |
|---|---|---|---|---|
| **Manual labels**, 19 decidable images out of 30 inspected. Both values exact | 42 % | 42 % | 32 % | 53 % |
| Same 19, reading within the listed "acceptable" alternatives | 58 % | 63 % | 53 % | 58 % |
| Same 19, laneCount within ±1 | 89 % | 95 % | 84 % | 79 % |
| Same 19, currentLane exact | 79 % | 79 % | 74 % | 58 % |
| **Oracle**: the same post-processor run on the GT lane + GT drivable masks, 240 images. Both values exact | 72 % | 68 % | 68 % | 28 % |

The manual check:

- **How it was done:** the 30 images in `outputs/lanes/visual_check/sheet_*.jpg` show the prediction next to the GT with direct/alternative drivable area. They were inspected by eye, and `perception/lanes/manual_lane_labels.json` records one annotator's reading. 11 of the 30 were not decidable from a single frame (intersections, a gas station, straddling a line, darkness) and are left out.
- **This is a sanity check, not a benchmark.** These 30 images were also used while developing the post-processor, so the numbers are optimistic.
- **The oracle is limited too.** On the 19 labelled images, the oracle itself scores only 47 % exact (58 % acceptable, 95 % within ±1). Single-frame lane counting is limited by the post-processing and by real ambiguity (parking lanes, lanes hidden by traffic, opposite-direction lanes), not only by the segmentation model.
- **Passing GT vehicle boxes as `occluder_boxes`** helps when a car in the next lane hides it (for example b1f4491b and bc16f8e9 become correct). It also counts parking lanes, so on average it is not better: 47 % exact on the manual labels and 57 % against the oracle for TLNP+ L.

### Temporal behaviour

These stats cover 10 s clips starting at t = 5 s, with `temporal=True` and TLNP+ Large. They are stored in `outputs/lanes/video_stats.json`.

| Clip | Modal state | State changes / min (raw → smoothed) | Median confidence |
|---|---|---|---|
| b1f4491b highway, day | lane 2 of 3 | 390 → 42 (includes one real lane change left, detected at t ≈ 8.2 s) | 0.81 |
| b1ff4656 city intersection | lane 1 of 1 | 264 → 24 | 0.20 (correctly low: no near-field lines) |
| b23adb0d city, night | lane 1 of 1 | 24 → 0 | 0.56 |

The raw per-frame state flickers, mostly because side lanes are hidden by vehicles and reappear. Note that the "raw" column is taken inside temporal mode, after the line tracker and before the smoother, so 390 → 42 measures the smoother alone; a fully untracked single-frame state would flicker at least as much. Temporal mode adds three things:

- a u-space line tracker that keeps a line through up to 12 missed frames,
- per-side voting over 30 frames, with a 65 % switch threshold and a 20-frame dwell,
- lane-change detection from the ego-offset sign flip, which is only trusted when both ego boundaries are painted lines.

The Driving Context Engine should still debounce lane-level instructions further.

### Latency (PROVISIONAL)

The GPU (RTX 5060 Laptop, sm_120, 8 GB) was shared with up to 6 other agents during every measurement, so these numbers are high and noisy. They come from `outputs/lanes/latency.json` (150 frames of b1f4491b) and use batch 1 on 1280x720 input.

| Backend | model p50 (ms) | segmentation incl. pre/post (ms) | lane-state post-proc (ms) | `analyze()` total p50 / p95 (ms) | torch peak alloc |
|---|---|---|---|---|---|
| twinlitenetplus_large, FP32 | 30.9 | 33.4 | 8.8 | 44.9 / 53.0 | 920 MiB (capped warm-up) |
| twinlitenetplus_large, FP16 | 27.0 | 29.3 | 10.3 | 43.7 / 67.6 | 890 MiB |
| twinlitenetplus_medium | 11.2 | 13.0 | 8.3 | 24.2 / 29.1 | 628 MiB |
| yolop_onnx (ORT CUDA EP) | 25.0 | 27.5 | 10.0 | 40.3 / 51.1 | ORT arena capped at 1 GiB |
| comma10k_segnet, FP32 | 27.6 | 31.2 | 10.3 | 47.7 / 78.2 | 971 MiB (capped warm-up) |
| comma10k_segnet, FP16 | 17.4 | 19.7 | 5.3 | 28.4 / 33.1 | 251 MiB |

For reference only, the verification agent timed the models with the GPU idle: TLNP+ L 9.8 ms, TLNP+ M 5.9 ms, YOLOP ORT 11.6 ms, comma 11.6 ms FP32 / 9.1 ms FP16. The integrator should re-benchmark alone.

The lane-state post-processing is pure numpy/OpenCV and takes about 5 ms per frame. The rest of `lanestate_ms` is the 640x360 resize and building the outputs. If the GPU is contended, run the lanes block at 10 to 15 Hz. Lanes change slowly, and the temporal mode tolerates skipped frames.

**VRAM.** With `torch.backends.cudnn.benchmark=True`, the first forward at a new shape tries every conv algorithm and grabbed 2.1 GB (TLNP+) and 5.1 GB (comma U-Net) of workspace. The backends therefore run their warm-up under a temporary per-process cap (`warmup_cap_gb=1.0`, meaning 1 GB on top of whatever the process has already reserved) and then restore the previous limit. Algorithms that do not fit are skipped, with no speed loss measured. Pass `warmup_cap_gb=None` to disable this.

## Ground truth used, and what YOLOP's lane GT really is

- **Lane GT:** the official YOLOP `ll_seg_annotations` val masks, the ones TwinLiteNet+, YOLOP and HybridNets are trained and scored on. The masks for the 250 subset images were range-extracted into `data/bdd100k/labels/yolop_ll_seg_val/`; provenance is in MODELS.md.
- **Measured here** (`tools/inspect_yolop_gt.py`):
  - The masks are 1280x720 and the lines are drawn with cv2 thickness=2. The area/skeleton ratio is 3.47, against 3.58 for a synthetic cv2 thickness-2 line and 5.36 for thickness 3.
  - All 8 BDD lane categories are included, crosswalks and "vertical" stop lines too. Crosswalk and road-curb polylines sit at a median distance of 0 px from the mask. Single lines sit at 2 to 3 px, because the double-edge labels are collapsed to a centre line, and double lines at 7 to 9 px.
- **640x360 protocol:** the same as TLNP+ `val.py`. The GT is resized with INTER_LINEAR and thresholded at > 1, and compared with the argmax of the cropped network output. The IoU here is pooled over the whole set, while TLNP+ averages per batch of 16, hence the small offsets.
- **Drivable GT:** YOLOP `da_seg_annotations` for the 640x360 protocol, resized with INTER_LINEAR and thresholded at > 1 like the TLNP+ loader (drivable = direct + alternative), and the local rasterisation `labels/drivable_masks` (0 direct, 1 alternative, 2 background) at 1280x720. The two agree well: 39.35 M of 40.86 M YOLOP-drivable pixels are direct or alternative in the local masks.
- **Parallel-only lanes:** `lane IoU ignore_transverse` in `metrics.json` excludes a 21 px zone around vertical / crosswalk labels.

## Post-processing (deterministic, `postprocess.py`)

All steps run on the 640x360 masks and are fully rule-based (plan §16). There is no LLM and no learned component.

1. **Horizon guess.** Take the top of the drivable mask minus 0.035·H. On the calibration set this matches the median VP row to within 0.003·H.
2. **Scanlines, bottom-up, every 2 rows.**
   - Find the runs of lane pixels in each row. Runs wider than 8 px + 0.6 · lane-width prior are dropped (crosswalk bars, blobs).
   - Runs closer than 2 + 0.10·(y − y_h) are merged, which handles double yellow and double white lines.
3. **Linking.** Run centres are linked into chains by greedy nearest neighbour, using a slope-extrapolated prediction plus a tolerance that grows with the run widths, so shallow side-lane lines still link. A chain survives gaps of 12 + 0.4·(y − y_h) px, which covers dashes and small occlusions.
4. **Clean-up.**
   - Merge collinear fragments and parallel duplicates.
   - Drop short chains. Shallow chains count by image length as well as by vertical extent.
   - Drop near-horizontal chains (|dx/dy| > 12) and chains whose perpendicular thickness is too large.
   - Drop clusters of 4 or more short, dense stripes (zebra crosswalks).
   - Drop short, shallow chains that miss the vanishing point by more than 0.3·W (stop-line fragments).
5. **Fit and vanishing point.** Fit each chain with a quadratic, or a linear fit plus tangent extrapolation. The vanishing point is the weighted median of the intersections of long left- and right-leaning chains, and it refines y_h.
6. **u-space.** Under a flat-ground pinhole model, a road line at lateral offset X projects to x = x_vp + (X/h)(y − y_h). So u = (x − x_vp) / (k·(y − y_h)) is the row-independent lateral position in lane widths, with the camera at u = 0 whatever the yaw.
   - k = `lane_w_ratio` = 2.83. It was calibrated on 600 val images outside the eval subset, where the median ego width in u is 1.00 (`tools/calibrate_priors.py`, `outputs/lanes/calibration.json`). Physically k ≈ W_lane / h_cam ≈ 3.6 m / 1.3 m.
   - u-space lets lines visible only near the horizon (side lanes) be compared with ego boundaries seen at the bottom of the frame.
7. **Lane state.**
   - The ego boundaries are the nearest candidate on each side of u = 0. Candidates are painted lines, or drivable-area road edges when no line lies within 0.3 lanes of them; a missing side is imputed at ±1 lane.
   - Walk outward and count a lane for every gap of 0.5 to 1.7 × the measured ego width whose centre line is at least 50 % drivable. A gap of about 2 lane widths with a drivable centre counts as 2 lanes.
   - The walk stops at a road edge, at a yellow line (on the left side: US right-hand traffic), or at non-drivable road such as an opposite carriageway or a median.
   - One unmarked lane per side is counted when there is at least 0.8 lanes of drivable road beyond the last line and the model actually sees road there. This covers lines hidden by traffic.
   - Road edges are the 15th / 85th percentile of the per-row drivable extent. Rows where the ego line itself is blocked by the lead car are skipped. A side that touches the image border in most rows is "open".
   - `currentLane` = lanesLeft + 1 and `laneCount` = lanesLeft + 1 + lanesRight.
8. **Confidence.** It combines four things: boundary evidence (line = 1, edge = 0.85, imputed = 0.5), lane-width plausibility, support, and whether a vanishing point was found. In temporal mode, the tracker and smoother described above are added.

## Limits and caveats

- **Occlusion.** Lanes filled with traffic are the main error: the drivable edge stops at the car. Pass the §7 vehicle boxes, and fuse with the map prior (OSM `lanes:forward` / `turn:lanes`) for navigation-grade lane numbers.
- **Unmarked roads, parking lanes, bike lanes.** Counting falls back on the drivable mask. BDD-trained drivable heads include parking lanes and sometimes opposite lanes on undivided streets, which leads to over-counts. Low `confidence` flags many of these cases, but not all of them.
- **Night, rain and glare** reduce the number of lines found. Measured lane IoU does not drop at night on this subset, but lane-state confidence does.
- **Curves.** u is taken from the nearest half of each chain. Strong curvature or a wrong vanishing point shifts u, particularly for far side lanes.
- **Road edges without paint.** An edge is only as good as the drivable mask. Curbs are learned as "lane" by the BDD-trained heads because the YOLOP GT includes `road curb`.
- **Camera assumptions.** The camera is assumed near the vehicle centre, with 16:9 input and square pixels. The measured median ego-lane centre is u = +0.02, and when no vanishing point is found its x defaults to W/2 − 0.043·W (calibrated). **For head-mounted glasses (plan §35),** u = 0 is the camera and not the car, so the car's lane needs head yaw / IMU compensation.
- **Out-of-frame points.** `laneBoundaries` polylines are extrapolated and can hold x coordinates outside the image, between -0.25·W and 1.25·W (for example x = -277 on a 1280 px frame). Clip them before drawing if the frontend needs in-frame points.
- **Colour and style** (yellow / white, solid / dashed) are image heuristics. TLNP+ draws lines through dash gaps, so the style comes from image contrast. Treat both as hints.
- **Single annotator.** The manual labels are one reader's judgement, and the 30 images were also used during development.
- **Licensing.** TLNP+ and YOLOP weights are BDD100K-trained: research / non-commercial only, and commercial use needs a UC Berkeley OTL licence. comma10k-segnet is MIT on MIT data. Full details are in [MODELS.md](MODELS.md). Not legal advice; plan §37 needs review.

## Review fixes (reviewer agent, 2026-09-25)

The reviewer re-ran `python -m perception.lanes.eval_bdd` with the builder's code. Every number in the builder's `metrics.json` reproduced exactly: the pipeline is deterministic, and the lane IoU, F-scores, drivable IoU/mIoU, ego IoU, lane state, oracle and occluder numbers were all identical. Checks that found no problem:

- **Split and leakage:** all 250 subset images are in the YOLOP `val/` members and none are in `train/`. The 600 calibration images are val images outside the subset. `postprocess.py` and `lanes.py` read no labels.
- **Preprocessing:** the TLNP+ letterbox (640x384, 12 px crop, RGB, /255), the YOLOP ONNX preprocessing and the comma Resize(384, 512) all match upstream.
- **Metric definitions:** the lane 640x360 GT resize and >1 threshold match TLNP+ `val.py` and `BDD100K.py`. Balanced accuracy is the TLNP+ `lineAccuracy`, and mIoU is the 2-class mean.
- **GT agreement:** channel 0 of the RGBA GT PNGs equals `imread(..., 0) > 1` on every pixel. The local and YOLOP drivable GT agree at IoU 0.980.

Fixes applied (small edits; outputs re-generated with the same command):

1. **`backends.capped_warmup` (integration bug).** The warm-up cap was absolute (`cap_gb` of total process memory). As a result, `LaneDetector()` raised `torch.OutOfMemoryError` whenever the process already held more than `cap_gb`, which will be the case in the integrated pipeline once the detector and depth models are loaded. Reproduced with 0.6 GB pre-allocated and `warmup_cap_gb=0.5`. The cap is now `memory_reserved() + cap_gb`, and the same test passes (peak 1.11 GB reserved, fraction restored to 1.0). Standalone behaviour is essentially unchanged.
2. **`eval_bdd.py`, ego-lane IoU.** The 17 images without BDD "direct" area were skipped, so their false-positive ego pixels never entered the pooled IoU. They are now pooled; the per-image CSV value stays empty. Effect: TLNP+ L 63.6 → 63.3, M 63.1 → 62.7, YOLOP 65.8 → 65.1, comma 43.5 → 43.2.
3. **`eval_bdd.py`, drivable GT at 640x360.** The README claimed the TLNP+ protocol, but the code resized the YOLOP drivable GT with INTER_NEAREST. It now uses INTER_LINEAR on the raw label followed by > 1, as `BDD100K.py` does. Effect on drivable mIoU: +0.04 (TLNP+ L 92.39 → 92.43), +0.03 (M), +0.21 (YOLOP 90.71 → 90.92) and +0.36 (comma 81.80 → 82.16).
4. **`eval_bdd.py`, BDD-convention F-score added.** The new `lane_F_r{1,2,5,10}px_1280x720_bddconv` keys score images where both GT and prediction are empty as F = 1, like `bdd100k/eval/lane.py`, and `lane_F_n_both_empty` counts those images. A 1 px radius was added. The original `lane_F_r*` keys are unchanged.
5. **Documentation.** This README now states the drivable-GT resize, the cap semantics, that the "raw" video statistic is taken after the line tracker, and that polylines can extend outside the frame. MODELS.md now says where the TLNP+ weights are hosted and that comma10k-segnet depends on timm (Apache-2.0).

The latencies in the re-run `metrics.json` (TLNP+ L model p50 10.1 ms, end-to-end 17.6 ms) are much lower than the builder's (28 to 31 ms). The GPU was less contended during the re-run; all latencies remain provisional.

## Reproduce

Run everything from `perception_engine/` with `.venv\Scripts\python.exe`, and set `YOLO_AUTOINSTALL=False`.

```powershell
$env:YOLO_AUTOINSTALL = "False"
# all backends, 250 images -> outputs\lanes\metrics.json + per_image_*.csv   (about 2-3 min)
.venv\Scripts\python.exe -m perception.lanes.eval_bdd
# three 10 s annotated clips (mp4v 1280x720) + outputs\lanes\video_stats.json
.venv\Scripts\python.exe -m perception.lanes.make_overlays
# latency micro-benchmark -> outputs\lanes\latency.json
.venv\Scripts\python.exe -m perception.lanes.tools.bench_latency
# visual-check contact sheets (30 images) and README figures
.venv\Scripts\python.exe -m perception.lanes.tools.visual_check --n 30
.venv\Scripts\python.exe -m perception.lanes.tools.make_figures
# geometric priors (lane_w_ratio, camera offset) and YOLOP-GT format checks
.venv\Scripts\python.exe -m perception.lanes.tools.calibrate_priors
.venv\Scripts\python.exe -m perception.lanes.tools.inspect_yolop_gt
```

## Files

| Path | Contents |
|---|---|
| `lanes.py` | `LaneDetector`, `LaneAnalysis`, `LaneStateSmoother` |
| `backends.py` | TLNP+ L/M (PyTorch), YOLOP (ORT CUDA EP) and comma10k (smp) wrappers, plus the capped cudnn warm-up |
| `postprocess.py` | mask → chains → vanishing point → u-space lane state, `LineTracker` |
| `viz.py` | overlays |
| `eval_bdd.py` | evaluation (one command, see above) |
| `make_overlays.py` | video overlays |
| `manual_lane_labels.json` | the manual lane-state labels |
| `tools/` | calibration, GT inspection, benchmarks, figures, contact sheets |

Outputs in `outputs/lanes/`:

- `metrics.json`, `latency.json`, `video_stats.json`, `calibration.json`, `yolop_gt_inspection.json`
- `backend_compare.png` (GT | 4 backends on 4 images) and `lane_state_examples.png`
- `visual_check/sheet_*.jpg`
- `b1f4491b-cf446195_*_lanes.mp4` (highway), `b1ff4656-0435391e_*_lanes.mp4` (city) and `b23adb0d-8a7aaced_*_lanes.mp4` (night)

Overlay legend: green = drivable, blue = ego lane, red = lane mask, cyan = ego boundaries, orange = counted neighbour lines, grey = other lines, white dots = anchor points, yellow cross = vanishing point.
