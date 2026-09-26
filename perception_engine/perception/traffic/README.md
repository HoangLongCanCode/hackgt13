# Traffic lights + traffic signs (plan sections 11 and 12)

Perception block of the AI Spatial Driving Copilot prototype (HackGT 13). It turns detector boxes into:

- **traffic-light state**: RED / YELLOW / GREEN / UNKNOWN, smoothed per track, with a coarse distance;
- **US sign type**: stop, yield, do_not_enter, pedestrian_crossing, speed_limit_N, unknown.

Everything here is **informational only** (plan section 38). UNKNOWN means unknown, GREEN is never permission to go, and no output should drive control. Everything is deterministic: no LLM anywhere (section 16).

All numbers below were **measured here** on 2026-09-25 unless marked otherwise. Timings are **PROVISIONAL**: the GPU (RTX 5060 Laptop, 8 GB) was shared with up to 6 other agents at 28 to 84% utilization, so re-benchmark alone.

## 1. API

```python
import torch  # import before onnxruntime (ORT's CUDA EP finds CUDA DLLs through torch)
from perception.traffic.lights import TrafficLightClassifier
from perception.traffic.signs import SignRecognizer

lights = TrafficLightClassifier(backend="autoware_onnx", device="cuda", smoothing="hmm")  # or backend="hsv"
signs  = SignRecognizer(backend="lisa_crops", device="cuda")        # PP-OCRv6 verification on by default

# per frame; dets = list[Detection] from the shared detector + tracker (Detection.id = track id)
tl = lights.classify(frame_bgr, [d for d in dets if d.cls == "traffic light"], pts_s=frame.pts_s)
ts = signs.recognize(frame_bgr, [d for d in dets if d.cls == "traffic sign"], pts_s=frame.pts_s)
main_light = TrafficLightClassifier.primary(tl, frame_w=1280)   # HUD 'RED 42m' candidate, or None
```

- `classify()` returns `list[TrafficLightState]` (id, state, bbox, confidence, distanceMeters).
  - It accepts `Detection` objects, dicts with `bbox` and `id`, or plain `[x1, y1, x2, y2]` lists.
  - Temporal smoothing needs `id`; without it each frame is independent.
- `recognize()` returns `list[TrafficSign]` (id, signClass, bbox, confidence, distanceMeters).
  - Boxes it cannot type come back as `signClass="unknown"`; pass `emit_unknown=False` to drop them.
- Both are `perception.common.schemas` types, so they drop straight into `FrameResult.trafficLights` / `trafficSigns`.

### TrafficLightClassifier options

| Option | Default | Meaning |
|---|---|---|
| `backend` | `hsv` | `hsv` or `autoware_onnx` (recommended; see results) |
| `smoothing` | `hmm` | `hmm` (4-state forward filter, stay prob exp(-dt/0.35 s), tempered observations; UNKNOWN reads are weak evidence), `vote` (confidence-weighted majority over 0.4 s + 2-frame hysteresis), or `none` |
| `min_box_h` | 4 | boxes whose short side is below this many px -> UNKNOWN (was 6 before the review; see Review fixes) |
| `focal_px` | 1000 | **assumed** focal length for the size prior (BDD100K publishes no intrinsics; about 65 deg HFOV at 1280 px) |
| `autoware_orient` | `both` | `both` = average the crop as-is with a copy rotated (vertical US head, 90 deg clockwise) or mirrored (horizontal) into the Japanese layout, where red is on the right; `auto` = the re-oriented copy only; `none` = as-is |
| `exposure_gate` | True | Autoware's over/under-exposure rule (brightness >= 0.85 or <= -0.83 -> UNKNOWN) |
| `track_stride` | 1 | classify each tracked light only every N frames (staggered); the smoothed state is reused in between. This is the main budget lever |
| `hsv_params` | tuned | `HSVParams` (thresholds tuned on the DEV split by `tune_hsv.py`) |

Distance is a pinhole size prior, Z = f x H_real / h_px:
- full 3-section head (aspect >= 2.2): the long side = 1.07 m (US 12-inch head);
- otherwise: the housing width = 0.36 m.

`lights.distance_bin()` gives the coarse bins <30 / 30-60 / 60-100 / >100 m. These values are **not validated** (BDD has no range ground truth).

### SignRecognizer options

| Option | Default | Meaning |
|---|---|---|
| `backend` | `lisa_crops` | `lisa_crops`: LISA YOLO11n on square context crops (4x the box, >= 128 px, resized to 320) around the shared detector's BDD `traffic sign` boxes. `lisa_full`: LISA on the whole frame (imgsz 1280). `coco_stop`: COCO YOLO26s `stop sign` only |
| `ocr` | `pp_ocrv6_small` | PP-OCRv6 small rec (ONNX) on the bottom, middle and top bands of the upscaled sign. **speed_limit_N**: N comes from OCR (regex `[1-8][05]`, conf >= 0.8), never from LISA; if no number is confirmed, or other text such as 'NO TURN' / 'AROW' is read, the sign becomes `unknown` (`ocr_strict_speed=True`). **stop**: 'STOP' read -> confirmed, 'ENTER'/'NOT' -> `do_not_enter`, unreadable -> kept. `None` disables OCR |
| `ocr_every` | 5 | per track, OCR re-runs at most every N frames (cached in between) |
| `vote_n` | 5 | per-track majority over the last N typed reads |
| `focal_px` | 1000 | size prior with MUTCD conventional sizes: STOP 30 in, YIELD 36 in, speed limit 30 in tall, W11-2 diamond box about 42 in |

## 2. Reproduce (from `perception_engine/`)

```powershell
$env:YOLO_AUTOINSTALL = "False"
.venv\Scripts\python.exe -m perception.traffic.smoke_test          # API smoke test (~30 s)
.venv\Scripts\python.exe -m perception.traffic.eval_bdd            # light color on GT crops -> metrics.json "light_color" + confusion PNGs (~3 min)
.venv\Scripts\python.exe -m perception.traffic.e2e_video           # detector+ByteTrack+lights+signs on 3 clips -> e2e_*.mp4/jsonl (~4 min)
.venv\Scripts\python.exe -m perception.traffic.e2e_video --backend hsv --no-video --clips b1ff4656-0435391e,b23adb0d-8a7aaced
.venv\Scripts\python.exe -m perception.traffic.eval_signs          # 13 clips every 2 s -> sign sheets + manual precision (~5 min)
.venv\Scripts\python.exe -m perception.traffic.bench_traffic       # per-component p50/p95
# optional
.venv\Scripts\python.exe -m perception.traffic.tune_hsv --trials 400   # HSV random search on DEV (starts from the current defaults)
.venv\Scripts\python.exe -m perception.traffic.fetch_val_lights        # re-fetch the 400 extra val keyframes (25 MB)
```

Git Bash: `YOLO_AUTOINSTALL=False .venv/Scripts/python.exe -m perception.traffic.eval_bdd`. Weights download on first use into the project HF cache (see `MODELS.md`).

## 3. Results: light color on GT crops (measured here)

GT: BDD100K 2018 `trafficLightColor` on ground-truth boxes (oracle crops; this isolates the color stage from detection). BDD `none` (side/back-facing, off, unreadable) should come out UNKNOWN.

Crops with long side < 10 px or short side < 4 px are ignored:
- 17 of 638 in the subset (7 green, 9 red, 1 none);
- 36 of 2,386 in val_lights (22 green, 8 red, 5 none, 1 yellow).

Splits:
- **TEST = 250-image eval subset**: 621 crops (red 161, yellow 13, green 283, none 164). Nothing was tuned on it.
- **DEV = val_lights**: 400 extra val keyframes that I fetched (25 MB), 314 of them chosen because they contain a yellow light. 2,350 crops (red 518, yellow 484, green 634, none 714). The HSV thresholds were tuned here. It is yellow-enriched, so it is not a random sample.

`lit acc` counts UNKNOWN on a lit light as an error. The 95% intervals are Wilson intervals.

| Backend | Split | lit acc | macro-F1 (R,Y,G,none->UNK) | red recall | yellow recall | green recall | none->UNKNOWN | red->GREEN | green->RED |
|---|---|---|---|---|---|---|---|---|---|
| **autoware_onnx** (orient both, gate on) | TEST | **0.871** [0.837, 0.899] | **0.697** | 0.832 | 8/13 = 0.62 [0.36, 0.82] | 0.905 | 0.701 | **0.6%** | 2.5% |
| autoware_onnx, orient auto | TEST | 0.873 | 0.666 | 0.826 | 0.62 | 0.912 | 0.579 | 1.9% | 1.8% |
| autoware_onnx, orient none | TEST | 0.845 | 0.695 | 0.808 | 0.62 | 0.876 | 0.756 | 0.6% | 3.9% |
| autoware_onnx, gate off | TEST | 0.875 | 0.698 | 0.839 | 0.62 | 0.908 | 0.695 | 0.6% | n/a |
| ensemble 0.5 x HSV + 0.5 x Autoware | TEST | 0.801 | 0.648 | 0.752 | 0.62 | 0.838 | 0.695 | 1.2% | n/a |
| hsv (tuned on DEV) | TEST | 0.650 [0.605, 0.692] | 0.558 | 0.603 | 9/13 = 0.69 | 0.675 | 0.646 | 1.9% | 0.7% |
| **autoware_onnx** | DEV | 0.814 [0.794, 0.832] | 0.772 | 0.892 | 297/484 = **0.61** [0.57, 0.66] | 0.902 | 0.705 | 0.6% | 3.3% |
| hsv | DEV | 0.682 | 0.681 | 0.654 | 0.717 | 0.678 | 0.658 | 1.2% | 2.1% |
| **autoware_onnx through the public API** `TrafficLightClassifier.classify()` (smoothing off, defaults, min_box_h 4) | TEST | **0.867** [0.832, 0.895] | 0.696 | 0.826 | 0.62 | 0.901 | 0.707 | 0.6% | 2.5% |
| same, with the pre-review default min_box_h 6 | TEST | 0.818 | 0.673 | 0.776 | 0.62 | 0.852 | 0.726 | 0.6% | n/a |
| autoware_onnx through the public API (min_box_h 4) | DEV | 0.811 | 0.774 | 0.890 | 0.614 | 0.897 | 0.716 | 0.6% | n/a |

The rows above the API rows call the raw classifier on the GT crops. The API rows run the same crops through `classify()`, including its `min_box_h` gate. These rows were added in review (`metrics.json` -> `light_color.splits.<split>.api_path`). Quote the API row as the deployed number.

**Day vs night, autoware_onnx, TEST (lit acc / none->UNKNOWN):**
- day: 0.811 / 0.744 (n_lit 148)
- night: **0.907** / 0.444 (n_lit 246)
- dawn/dusk: 0.873 / 0.875 (n_lit 63)

**Same breakdown on DEV:**
- day: 0.780 / 0.766
- night: 0.843 / 0.436
- dawn/dusk: 0.791 / 0.727
- Night yellow recall is only 0.51; yellow goes mostly to RED.

**HSV on TEST:**
- day: 0.473
- night: 0.752
- dawn/dusk: 0.667
- Daytime is the weak case: sunlit yellow housings, pale LED greens, and orange-looking red LEDs.

**By box long side (autoware_onnx, lit acc on TEST):**

| Long side | lit acc | n_lit |
|---|---|---|
| 10-15 px | 0.92 | 129 |
| 15-20 px | 0.91 | 106 |
| 20-30 px | 0.85 | 106 |
| 30-50 px | 0.78 | 81 |
| >= 50 px | 0.86 | 35 |

Small (distant) heads are *not* the weak spot for color. Big, close heads are, because of glare and oblique views. So the plan's 'RED 120m' is limited by the detector, not by the color stage: at f = 1000 px, a 1.07 m head at 120 m is only about 9 px long.

**Takeaways**
- Autoware (Apache-2.0, trained in Japan) transfers well to US lights: 87% on lit TEST lights, and red is called green only 1 in 161 times on TEST (0.6% on DEV).
- **Yellow is the weak class** (about 0.61 recall; its errors go mostly to RED, the safer direction).
- At night Autoware over-calls lights that BDD marks 'none': only 44% become UNKNOWN.
- HSV is a sub-millisecond, dependency-free baseline and disagreement check, not a primary.

Plots: `outputs/traffic/confusion_{hsv,autoware_onnx,ensemble_hsv_autoware}_{subset,val_lights}.png`. Full numbers: `outputs/traffic/metrics.json` -> `light_color`.

## 4. Results: end to end on video (measured here)

Pipeline: `dronefreak/bdd100k-yolo26s` at imgsz 960, conf 0.25, classes {traffic light, traffic sign}, `model.track(persist=True, tracker="bytetrack.yaml")`, then `TrafficLightClassifier(autoware_onnx, hmm)` and `SignRecognizer(lisa_crops + OCR)`.

Clips, each 10 s (300 frames) at 1280x720, mp4v: `outputs/traffic/e2e_<clip>.mp4`. Per-frame `FrameResult` JSON lines: `e2e_<clip>.jsonl` (Autoware). The `--backend hsv` run now writes `e2e_<clip>_hsv.jsonl` (review fix: it used to overwrite the Autoware jsonl files).

**Flip rate** is state changes per track-second over tracks of at least 5 frames. No GT is needed for it: real lights change a few times a minute at most.

| Clip (window) | light tracks | raw per-frame | vote | **hmm** | hmm, ignoring UNKNOWN | keyframe t=10 s (IoU >= 0.5 to GT) |
|---|---|---|---|---|---|---|
| `b1ff4656` city day, many lights (5-15 s) | 33 | 0.245 | 0.000 | **0.000** | 0.000 | **4 of 9** GT lights detected (review fix: was 2 of 9, measured one frame late); 1 lit match, called UNKNOWN (a pedestrian 'walk' head labeled green); 3 'none' matches: 2 UNKNOWN, 1 GREEN |
| `b23adb0d` city night (5-15 s) | 33 | 1.162 | 0.110 | **0.153** | 0.066 | 8 of 12 detected, **7 of 7 lit matches correct**; 1 'none' called YELLOW |
| `b9ecc316` rainy city, signs demo (15-25 s) | 33 | 0.134 | 0.134 | 0.134 | 0.000 | (keyframe not in window) |

The HSV backend on the same windows gives flip rate raw 1.40 / hmm 0.45 (day) and raw 0.50 / hmm 0.15 (night), with 5 of 7 lit keyframe matches correct (night); on the day keyframe HSV calls all 4 matches UNKNOWN.

Smoothing cuts flips 7 to 8 times at night, and the hmm output keeps a color through brief UNKNOWN frames.

Sign demo (`b9ecc316`): the STOP, YIELD and pedestrian-crossing signs are typed; a bicycle warning sign W11-1 is typed pedestrian_crossing (LISA has no bicycle class).

Per-frame p50 in these runs (provisional, shared GPU):
- detector + ByteTrack: 21 to 26 ms
- lights `classify()`: 6.5 to 10.6 ms with 3.4 to 4.6 lights per frame
- signs `recognize()`: 12 to 27 ms

## 5. Results: signs (measured here; manual precision)

BDD100K has one generic `traffic sign` class, so sign subtypes cannot be scored automatically. Instead:

- 273 frames were sampled (13 clips, every 2 s), with 957 BDD sign boxes detected (conf >= 0.3; 671 of them >= 15 px tall).
- I (the agent) visually inspected a contact sheet of 40 typed detections, stratified by backend and raw class. Dark tiles were re-rendered brightened at 300 px.
- The verdicts are stored in `perception/traffic/signs_manual_truth.json` and are re-applied automatically: `eval_signs.py` or `--rescore`.
- Tiles are **not independent**: one 'CASH' billboard appears 4x, one DO NOT ENTER 4x, one SPEED LIMIT 25 sign 3x.
- Sheets: `outputs/traffic/signs_contact_sheet.png` (labels `raw>verified`) and `signs_recall_sheet.png`.

**Precision** = correct / typed. 'near' = a school sign S1-1 typed pedestrian_crossing (counted as not correct). Abstain = OCR turned the sign into unknown.

| Predicted class | n | raw LISA precision | with PP-OCRv6 verification |
|---|---|---|---|
| stop (LISA) | 8 | 3/8 = 0.38 (errors: 4x DO NOT ENTER at night, 1 fluorescent school sign) | 0.38 (the night DO NOT ENTER signs are unreadable) |
| **stop (COCO YOLO26s)** | 3 | **3/3** | 3/3 |
| yield | 6 | 5/6 = 0.83 | 0.83 |
| pedestrian_crossing | 8 | 4/8 = 0.50 (+4 near: S1-1 school signs; review fix, was 6/8) | 0.50 |
| speed_limit_N (value must match) | 15 | **2/15 = 0.13** (4 right sign, wrong number: 25 read as 35 3x, 20 as 35; 9 not speed-limit signs: 'NO TURN ON RED ARROW', 'CASH' billboard, 'PHARMACY' banner, fluorescent sign) | **6/6 = 1.0**, 9 abstained; all 9 were false positives, 0 true signs suppressed |
| all LISA tiles, `lisa_crops` / `lisa_full` | 18 / 19 | 0.44 / 0.32 (review fix, was 0.42) | **0.73 / 0.54** (review fix, was 0.69) |

**Recall (qualitative):**
- Of 40 random BDD sign boxes >= 15 px, only **2 are plan-class signs** (SPEED LIMIT 20 at 19 px and SPEED LIMIT 25 at 31 px).
  - LISA typed both as speed_limit_35; with OCR both get the right value (20, 25).
  - Two non-speed-limit boxes that LISA also called speed_limit_35 ('NO STANDING...', 'NO TURN ON RED ARROW') become unknown.
- The rest are street names, parking, one-way, guide, no-turn-on-red, pick-up/drop-off signs and billboards.
- So most BDD 'traffic signs' are outside the plan's classes, and plan-class recall cannot be estimated from BDD boxes.
- By eye:
  - clear STOP / YIELD / pedestrian signs at least about 20 px tall are usually typed;
  - COCO `stop sign` is precise, but it fired only 3 times in 273 frames (vs 19 to 23 LISA 'stop' reads);
  - OCR confirms most speed-limit reads at >= 30 px (25 of 56 raw reads, the rest being mostly non-speed signs). Below about 20 px it confirms only a few (2 of 8 at 16-20 px); signs under 16 px abstain (`ocr_min_h`).
- Typed fraction of all BDD sign boxes (`lisa_crops`): 6.2% raw, 4.4% after OCR.

**Plan section 12 coverage:**

| Plan class | Status |
|---|---|
| stop, yield, speed limit (value via OCR), do not enter | covered, with the quality above. do_not_enter is in LISA's classes, but was never predicted correctly here; its main effect was DO NOT ENTER signs mis-typed as stop |
| exit, merge, construction, road work | **not covered**: no class in any backend here |
| pedestrian crossing (not in the plan list) | also produced |

Future sign evaluation sets:
- **BDD100K-MUTCD** (IEEE DataPort, 38 MUTCD classes over BDD100K train/val, subscription, BDD NC terms): the only way to score the plan classes on BDD itself;
- **LISA Traffic Sign** (47 US classes, academic license);
- **Mapillary MTSD** (CC BY-NC-SA);
- **TLoNY** (Traffic Lights of New York, MIT labels on Unsplash images) and **LISA Traffic Light** (CC BY-NC-SA) for US light states and arrows.

## 6. Latency (bench_traffic.py, PROVISIONAL)

Load at start of the bench: 48% GPU utilization from other agents.

| Component | p50 ms | p95 ms |
|---|---|---|
| HSV, 1-8 lights per frame (CPU) | 0.36 | 0.93 |
| Autoware ORT **CUDA**, orient both, 1-8 lights | 45.3 | 61.7 |
| Autoware ORT CUDA, orient auto | 43.5 | 80.3 |
| Autoware ORT CUDA, HEURISTIC cudnn | 69.5 | 92.5 |
| Autoware ORT **CPU**, orient both | 31.7 | 63.4 |
| Autoware ORT CPU, orient auto | **15.9** | 37.1 |
| TemporalSmoother hmm update | 0.016 | 0.019 |
| LISA YOLO11n on 3 sign crops (no OCR) | 15.1 | 21.8 |
| PP-OCRv6 small rec, one band (CUDA) | 5.3 | 6.4 |

With a quieter GPU earlier in the session:
- the same Autoware CUDA call took **about 7 ms** for 5 lights (10 views, one uint8 batch);
- a single dynamic batch of 10 took 5 ms;
- in the e2e runs, `classify()` p50 was 6.5 to 10.6 ms.

Autoware on CUDA is dispatch-bound and very sensitive to GPU sharing on Windows/WDDM; the CPU EP is steadier under load. Suggested for the integrator's solo benchmark:
- compare `device="cuda"` against `device="cpu"`;
- use `autoware_orient="auto"` (half the compute; lit acc about the same on TEST, 0.873 vs 0.871, but fewer 'none' become UNKNOWN: 0.58 vs 0.70);
- set `track_stride=2` or `3`.

VRAM is small: the ORT classifier and OCR use well under 0.5 GB, and torch peaked at 236 MB in the e2e run.

Engineering note: the published Autoware ONNX files have a fixed batch (1/4/6). `lights.autoware_u8_model()` writes a derived copy to `models/traffic/` with a dynamic batch and uint8 NHWC input, and moves the normalization into the graph. Its outputs are identical to the official batch_6 file (max abs diff 0.0). This cut the numpy preprocessing from about 4.5 ms to under 0.3 ms per frame.

## 7. Backends tried

| Backend | Status | Notes |
|---|---|---|
| HSV + lamp position (own) | works, baseline | Hue votes of saturated, emissive pixels (V >= 0.7 x max), amber split red/yellow by lamp position, lamp-slot contrast gate against sunlit yellow housings, vertical and horizontal housings. Thresholds random-searched on DEV (900 trials) |
| Autoware traffic_light_classifier v4.0 (MobileNetV2, ONNX, Apache-2.0) | **works, recommended** | Checked against the upstream source (`autoware_tensorrt_classifier` preprocess_opt, `traffic_light_classifier.cpp`, `utils::compute_brightness`): RGB, top-left letterbox to 224 with 0 padding, mean [123.675, 116.28, 103.53] / std [58.395, 57.12, 57.375], exposure gate. 11 labels; all arrow combos map to RED |
| Autoware lamp recognizer (`comlops`) | not tried | anchor decode; not needed |
| HSV + Autoware ensemble | worse than Autoware alone | |
| LISA YOLO11n (cvtechniques/TrafficSignDetection) | works, low raw precision | the card's 0.994 mAP50 does not transfer to BDD |
| + PP-OCRv6 small rec (ONNX) verification | **works** | Fixes speed-limit values and false positives. PP-OCRv6 **tiny** rec was tried and misread more on 20-40 px signs |
| COCO YOLO26s `stop sign` | works, precise, low recall | |
| BDD-trained light-color CNN (the research's Level-2 primary) | not done | needs BDD *train* crops; the obvious next step for yellow and night 'none' |

## 8. Licenses (details and hashes in `MODELS.md`)

- **Commercially clean:** Autoware classifier (Apache-2.0), PP-OCRv6 (Apache-2.0), and the HSV code (own code on OpenCV, Apache-2.0).
- **Research / demo only:**
  - `dronefreak/bdd100k-yolo26s`: AGPL-3.0 plus BDD100K non-commercial data terms;
  - `cvtechniques/TrafficSignDetection`: AGPL-3.0 (Ultralytics) plus the LISA academic license. The HF card's `mit` tag does not override the LISA data terms;
  - COCO `yolo26s.pt`: AGPL-3.0.
- BDD100K images and labels, including the 400 fetched keyframes: research / non-commercial only.
- This is not a legal conclusion; clear the BDD and AGPL questions before any commercial use.

## 9. Caveats

- **Distances are unvalidated.** The focal length is assumed (1000 px), and the head/sign sizes are MUTCD nominal. At night, glare inflates light boxes, so distances read short. Use the bins, and fuse with the depth block and ego speed later.
- **Relevance is heuristic.** `primary()` weights box height x centrality with a majority vote across heads. There is no lane or map association yet, so it can pick a cross-street head.
- **Pedestrian signals**: BDD labels 'walk' signals green; Autoware (car model) calls them UNKNOWN. Arrows are mapped to RED (red plus arrow); protected-green arrows are not modeled. BDD has no arrow labels.
- **HSV thresholds are tuned to BDD's phone cameras** (cyan greens, orange-looking red LEDs). Re-tune for glasses cameras.
- **The keyframe checks are small** (2 clips, 21 GT lights). Since the review, the keyframe is aligned by image: the frame within +-0.1 s of t = 10 s that best matches `images/val/<clip>.jpg` (b1ff4656: pts 9.977 s, mean abs diff 2.7 vs 13.5 for the frame the old rule took). The `frameIndex` values from `VideoFileInput` after a seek are off by about 3 frames from a sequential decode (the shared reader sets the index to round(start_s x fps)); `ptsSeconds` is consistent, so align on pts.
- **Sign precision is based on 40 non-independent tiles** judged by the agent. Use BDD100K-MUTCD for real numbers.
- **The e2e detector is the dronefreak YOLO26s** loaded directly. The detection block owns the production detector and tracker; this block only needs its `traffic light` / `traffic sign` boxes with track ids.

## 10. Review fixes (independent reviewer, 2026-09-25)

The reviewer re-ran `eval_bdd`, `e2e_video` (both backends), `eval_signs` (the full run, then `--rescore`) and `smoke_test`. Before any fix, every number reproduced exactly; only timings changed.

The checks that found no problem:
- the Autoware preprocessing and labels (checked against the upstream C++ and `lamp_labels.txt`; the ONNX ends in Softmax);
- the derived uint8 ONNX (max abs diff 2.6e-6 against the official batch_1 file);
- the scalabel +1 crop convention;
- TEST/DEV overlap (0 shared images);
- the Wilson intervals;
- MODELS.md sizes and sha256 values (all 9 match);
- the licenses, against the research verify notes.

Four fixes:

1. **The API gate did not match the eval** (`lights.py`, `eval_bdd.py`).
   - The GT-crop rows call the raw classifier. `TrafficLightClassifier.classify()` applies `min_box_h`, which defaulted to 6 px.
   - On TEST that gate turned 24 of 457 lit lights into UNKNOWN (short side under 6 px). The raw classifier got all 24 right.
   - So the API as shipped scored lit acc 0.818, not the reported 0.871.
   - The default is now 4 px, the same as the eval's ignore rule (it still gates 2 lit TEST lights). The API now scores **0.867** [0.832, 0.895] on TEST and 0.811 on DEV. red->GREEN is unchanged (0.6%).
   - `eval_bdd.py` now also scores through `classify()` (the `api_path` rows), so the two paths cannot drift apart again.
2. **The keyframe check ran one frame late** (`e2e_video.py`).
   - The old rule took the first frame with pts >= 10 s minus half a frame. For b1ff4656 that is pts 10.010 s, but the keyframe JPEG matches the frame at pts 9.977 s.
   - The check now picks the frame within +-0.1 s that best matches the keyframe JPEG. This uses pixels only, no labels.
   - Result: b1ff4656 detected lights go from **2 of 9 to 4 of 9**. b23adb0d is unchanged (8 of 12, 7 of 7 lit correct).
3. **The HSV run overwrote the Autoware outputs** (`e2e_video.py`).
   - The documented `--backend hsv` run wrote `e2e_<clip>.jsonl`, the same file names as the Autoware run.
   - So the shipped `e2e_b1ff4656` and `e2e_b23adb0d` jsonl files held HSV states (median lights time 0.7 ms) while the mp4s showed Autoware.
   - Non-default backends now write `e2e_<clip>_<backend>.jsonl` / `.mp4`. All jsonl files were regenerated.
4. **Two sign tiles had the wrong manual truth** (`signs_manual_truth.json`).
   - Tiles 17 and 27 are S1-1 school advance signs (pentagon, two children, AHEAD plate), the same sign type as tiles 1, 2 and 6. They had been marked pedestrian_crossing.
   - Truth is now `school`, which counts as 'near', not correct.
   - lisa_full precision: raw 0.42 -> **0.32**, with OCR 0.69 -> **0.54**.
   - LISA pedestrian_crossing: 6/8 -> **4/8**.
   - lisa_crops (0.44 / 0.73) is unchanged.

Not changed, but worth knowing:
- Reviewer timings (provisional): Autoware on the CUDA EP took 4.4 ms p50 for 4 lights (orient both) in a direct loop. A `bench_traffic --iters 50` run gave 27.8 ms for 1-8 lights, even though nvidia-smi read 0% at its start and end, so load from other agents probably changed during the run. `metrics.json` `bench` now holds the reviewer run; the table in section 6 is the builder's.
- The HSV thresholds were tuned on DEV, but `tune_hsv.py` prints TEST scores on every run, so TEST was visible while tuning. The objective and the selection use DEV only.
- The 'near' tiles and the 40-tile sign sample are still one agent's judgment.

