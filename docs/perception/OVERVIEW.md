# Perception engine overview

This is the perception half of the AI Spatial Driving Copilot, the HackGT 13 prototype by Knuckle Sandwich Robotics Inc. (KSR).
It runs on the laptop GPU (Windows 11, RTX 5060 Laptop, 8 GB) and turns each video frame into tracked
objects with distances, traffic-light states, sign types, lanes and road geometry. The results go to the Samsung
Galaxy Tab S9 over one WebSocket ([PROTOCOL_v2](../../contracts/PROTOCOL_v2.md)); the tablet merges them into a world
model and a deterministic Driving Context and draws them.

Code: [`perception_engine/`](../../perception_engine/). Each block folder has a `README.md` (API, measured results,
caveats, review fixes) and a `MODELS.md` (weights, sources, hashes, licences). The committed numbers are in
`perception_engine/results/<block>/metrics.json`. This page summarises them.

## Contents

- [What it produces](#what-it-produces)
- [Architecture](#architecture)
- [Blocks, chosen models and alternatives](#blocks-chosen-models-and-alternatives)
- [Measured accuracy and latency](#measured-accuracy-and-latency)
- [Realtime system performance](#realtime-system-performance)
- [Research findings](#research-findings)
- [Evaluation data](#evaluation-data)
- [Safety boundary](#safety-boundary)
- [Limits](#limits)

## What it produces

Per analysed frame (`perception.frame`, wave 1) and per slow-lane run (`perception.update`, wave 2):

| Output | Plan section | Notes |
|---|---|---|
| Objects: class, box, track id, confidence, age | 7, 8 | 10 BDD100K classes: pedestrian, rider, car, truck, bus, train, motorcycle, bicycle, traffic light, traffic sign |
| Distance, lateral offset, method, confidence, age | 9, 18 | Every vehicle and pedestrian gets one whenever any estimate is possible |
| Time to collision, approaching flag, in-ego-path flag | 8, 18 | TTC from box scale change; `inEgoPath` from a +-1.3 m corridor on flat ground |
| Traffic-light state | 11 | RED / YELLOW / GREEN / UNKNOWN, HMM-smoothed per track |
| Sign type | 12 | stop, yield, speed limit N (OCR-verified), do not enter, pedestrian crossing |
| Lanes | 10 | `currentLane`, `laneCount`, boundary polylines, confidence |
| Road geometry | 13 | Drivable coverage, ego path polygon, horizon, vanishing point, ground anchors at near/mid/far |

Formats: [INTERFACES.md](INTERFACES.md).

## Architecture

```text
 source (clip / sim look-ahead / tablet JPEG) --> [1-slot latest-wins inbox]
                                                        |
   FAST lane thread (own CUDA stream, higher priority), every analysed frame:
     decode + rotate -> detect (YOLO26s-BDD @960) -> track (BoT-SORT; ByteTrack for lights/signs)
     -> light colour (Autoware ONNX + HMM) -> carried slow-lane distance, or geometry distance
     -> perception.frame (wave 1)
                                                        | snapshot (latest wins)
   SLOW lane thread, back to back:
     distance (DA3 metric depth + flat ground + size prior, per-track Kalman)
     lanes + road (TwinLiteNet+ large), signs (LISA crops + PP-OCRv6, every 2nd cycle)
     -> perception.update (wave 2)
```

- **Two lanes, one process.** `perception/engine.py` has `fast_step` and `slow_step`; `perception/realtime/pipeline.py`
  runs them on two threads. Each model is owned by exactly one thread. Wave 1 already carries the latest slow-lane
  results (with `blockAges` and `distanceAgeMs`), so a client that ignores wave 2 still works.
- **Serial mode.** `PerceptionEngine.step(frame)` runs everything on one thread with the `every` / `phase` schedule in
  `config_realtime.yaml`; the offline tools and evals use it.
- **Server.** `perception/realtime/server.py` (FastAPI + uvicorn) handles the modes (video, sim, live, auto), sessions,
  controller/watcher roles, credits for the live uplink, the sim look-ahead scheduler and the phase1 navigation relay.
  The asyncio loop never touches torch.
- **Deterministic.** No LLM anywhere; outputs are reproducible for fixed weights and inputs (plan section 16).

## Blocks, chosen models and alternatives

The realtime defaults are what [`config_realtime.yaml`](../../perception_engine/perception/config_realtime.yaml) runs.

| Block | Realtime default | Lane / schedule | Alternatives in the code | Why the default |
|---|---|---|---|---|
| [detection](../../perception_engine/perception/detection/README.md) | `bdd-yolo26s` (Ultralytics YOLO26s fine-tuned on BDD100K) at imgsz 960, conf 0.25 | fast, every frame | `bdd-yolo26n`, `bdd-rfdetr-nano` (Apache code), COCO `yolo26s`, COCO RF-DETR nano/small | Ties RF-DETR-N on class-mean mAP but better on cars, pedestrians, lights and signs, 5x less VRAM |
| [tracking](../../perception_engine/perception/tracking/README.md) | `ul_botsort` (BoT-SORT + GMC) for road users; `ul_bytetrack` for lights and signs (ids >= 1,000,000) | fast, every frame | `ul_tracktrack`, `ul_deepocsort`, Roboflow `rf_mcbyte` / `rf_botsort` / `rf_bytetrack` (Apache-2.0) | About 40 % fewer ID switches than the other top trackers; each switch resets TTC history |
| [depth](../../perception_engine/perception/depth/README.md) | `da3_metric_large` (DA3METRIC-LARGE) fused with flat ground and size prior, per-track Kalman | slow, every cycle | `metric3d_vit_s_onnx`, `da2_metric_outdoor_small`, geometry only | Best agreement with physical size priors on BDD, finds the hood row, 100 % coverage |
| [lanes](../../perception_engine/perception/lanes/README.md) | `twinlitenetplus_large` + deterministic post-processing (lane state, road geometry) | slow, every cycle | `twinlitenetplus_medium`, `yolop_onnx`, `comma10k_segnet` (MIT data) | Reproduces its paper; best lane IoU here |
| [traffic](../../perception_engine/perception/traffic/README.md) lights | Autoware `traffic_light_classifier` MobileNetV2 (ONNX, CUDA EP) + HMM smoothing | fast, every frame | HSV rules | Apache-2.0, transfers to US lights, rarely calls red green |
| [traffic](../../perception_engine/perception/traffic/README.md) signs | LISA YOLO11n on sign crops + PP-OCRv6 small (CPU) verification | slow, every 2nd cycle | `coco_stop`, `lisa_full` | OCR verification raises precision from 0.44 to 0.73 |
| [segmentation](../../perception_engine/perception/segmentation/README.md) | off (lanes already yields `RoadGeometry`); `efficientvit_b1` when enabled | slow, every 3rd cycle if on | `pidnet_s`, `efficientvit_b0/b2`, `segformer_b0/b2` | Top accuracy group, Apache code and weights tag, 27 ms |
| [openpilot](../../perception_engine/perception/openpilot/README.md) (experimental) | off, not wired into realtime | - | - | comma.ai `driving_vision` v0.11.1: metric lanes, lead car, ego speed; research only |

Distance in wave 1, per object: the latest slow-lane value while it is younger than `slow.max_distance_age_s` (1.0 s),
otherwise a `geometry` distance computed on the frame itself (class size prior + flat ground, confidence capped at 0.5);
traffic lights and signs use `size_prior`. On the city and night clips 100 % of vehicle and pedestrian objects carry a
distance.

## Measured accuracy and latency

All numbers were measured in this project on BDD100K (plus KITTI for depth). Latencies are **provisional**: up to six
other jobs shared the GPU while they were taken. Confidence intervals are image-level bootstrap unless noted.

| Block | Eval set (N) | Default | Headline result | Latency p50 (provisional) |
|---|---|---|---|---|
| detection | BDD100K val, 250 keyframes, 4,604 boxes (2018 labels) | bdd-yolo26s @960 | mAP50-95 **38.5** over 10 classes (95 % CI 34.3-44.0), mAP50 62.9; 8 COCO-comparable classes 37.9; P 0.82 / R 0.69 at conf 0.25; night 37.0 vs day 39.4 | 14.3 ms `detect()` |
| tracking | BDD100K MOT 2020 val, 7 sequences at 5 fps, 1,418 frames, 20,585 boxes | ul_botsort | MOTA **55.7**, IDF1 65.4, HOTA 56.0, 487 ID switches (others 808-1,377) | 15.2 ms CPU update |
| depth | KITTI, 100 images / 595 objects, GT boxes, nearest-face GT | DA3 fused, auto calibration | MAE **2.31 m**, median relative error **6.8 %**, 100 % coverage (Metric3D fused 2.14 m / 4.7 %; geometry only 2.23 m / 6.0 % at 97 % coverage) | 50 ms network at 280x504 |
| depth (BDD, no GT) | 3 clips, 1,761 clean vehicle boxes | DA3 | Depth / size-prior ratio 1.03-1.07 (agreement, not accuracy); Kalman cuts implausible jumps from 0.6-4.9 % to <= 0.3 % of steps | - |
| lanes | BDD100K val, 250 keyframes, YOLOP GT | TwinLiteNet+ large | Lane IoU **34.4** (paper 34.2), drivable mIoU **92.4** (paper 92.9); lane state exact on 42 % of 19 hand-labelled images, 72 % when run on GT masks | 31 ms model, 45 ms `analyze()` |
| traffic lights | 621 GT light crops from the 250-image subset | Autoware + HMM | Lit-light accuracy **0.871** [0.837, 0.899]; red read as green 0.6 %; yellow recall 0.62 (8/13) | 16-45 ms per frame (CPU / CUDA EP); about 7 ms on a quiet GPU |
| traffic signs | 273 frames from 13 clips, 40 typed detections checked by hand | LISA crops + PP-OCRv6 | Precision 0.44 raw, **0.73** with OCR; speed limits 6/6 correct after OCR (9 false reads abstained) | 15 ms LISA + 5 ms OCR |
| segmentation | BDD100K sem_seg val, 30 images | EfficientViT-B1 | mIoU19 **43.8** (95 % CI 38.3-49.9), road IoU 91.7; horizon median error 12.5 px (N = 132) | 27 ms |
| openpilot | 13 BDD100K clips | driving_vision v0.11.1, f = 1100 px | 72 % of lane rows within 20 px of GT (86 % on labelled rows); lead distance vs geometry median ratio 0.96; ego speed vs lane-dash reference 0.95-0.97 | 6.3 ms model (provisional, shared GPU) |

VRAM: the realtime default set holds about 1.5 GB after warm-up; cuDNN autotuning briefly peaks at about 5 GB during
warm-up.

## Realtime system performance

Clip `b1ff4656-0435391e` (city), localhost, default config. Two sets of runs: A was on AC power with other jobs sharing
the GPU; B was the end-to-end run with the Kotlin fake tablet on battery power (about 20-30 % slower). Treat all
numbers as +-20-30 %. Details: [`perception/realtime/README.md`](../../perception_engine/perception/realtime/README.md),
[`examples/`](examples/).

| Mode | Wave 1 | Wave 2 | End to end |
|---|---|---|---|
| video (A, 30 s) | 16.5 fps, processing 76 / 99 ms (p50 / p95) | 8.0 fps, 227 / 282 ms | 100 % of vehicle/pedestrian objects carry a distance |
| sim (A, 30 s) | 16.2 fps | 8.1 fps | Results ready 91 ms (p50) before their frame is shown, 0.45 % late, 99.6 % of displayed frames covered |
| sim + phase1 nav (B, 31 s) | 12.5 results/s | 6.5 updates/s | Results about 190 ms ahead of display; 100 % coverage from 5 s on; 59 navigation packets |
| live, USB-equivalent, maxInFlight 2 (A) | 16.7 fps | 8.1 fps | Capture to result 106 / 141 ms (p50 / p95); 504/504 frames answered exactly once |
| live + phase1 nav (B, 25 s) | 13 results/s | - | Capture to result 114 / 162 ms; 51 trip states answered by 51 packets |
| live, emulated busy Wi-Fi | 12-14.5 fps | - | 119-122 / 180-192 ms |
| live, emulated phone hotspot | 8-11.6 fps | - | 143 / 275 ms (A); 159 / 683 ms in a run with a spike cluster (B) |
| live, maxInFlight 1 (A) | 9.6-11.5 fps | - | 74-91 ms p50 |

Pipeline alone (`bench_lanes`, 20 s): wave 1 at 16.7 Hz with 57 / 79 ms compute, distance refreshed at 8.6 Hz with a
median age of 267 ms. The fast lane alone takes about 32 ms (detect 20, track 3, lights 7, decode 3); with the slow lane
running it takes 52-60 ms while the GPU stays about 50 % busy, which points at GIL/CPU contention between the lanes.

## Research findings

What the measurements showed, block by block (details and caveats in each block README):

1. **Detection.** Fine-tuning on BDD100K is worth 10-15 mAP points over the same architecture trained on COCO, with the
   biggest gains on traffic lights and at night. Resolution matters more than model size: YOLO26s loses 4.3 points at
   640 instead of 960, and YOLO26n@960 is 5.3 points below YOLO26s@960. All COCO models fire on the ego hood as `car`
   (32-105 of 250 images); no BDD-trained model does.
2. **Tracking.** The top four trackers are within about 2.5 MOTA points on 7 sequences, but BoT-SORT has far fewer ID
   switches. The Apache-2.0 `rf_mcbyte` has the best MOTA / IDF1 and is the licence-safe alternative. Most of the gap to
   published BDD MOT results is the detector (bus and truck confusion).
3. **Depth.** Fusing the depth network with flat-ground and size-prior geometry roughly halves DA2's error (11.1 % to
   6.8 %) and lifts every backend to 100 % coverage. Metric3D is the most accurate on KITTI but the slowest (104 ms) and
   reads 15-24 % long on BDD; DA2 over-estimates BDD distances about 2x (field-of-view bias) unless rescaled. A
   per-frame horizon is essential: flat ground with a fixed horizon has 22 % error at 40-80 m.
4. **Camera intrinsics dominate absolute distance.** BDD100K publishes no intrinsics. Every method's absolute distance
   scales with the assumed focal length (700 px at 1280 wide by default), while the openpilot block's lane-dash speed
   check implies about 1,140 px. Live mode avoids this by sending real intrinsics in `client.hello`.
5. **Lanes.** TwinLiteNet+ and YOLOP reproduce their published numbers within 1.2 points, which validates the
   pre-processing ports. Counting lanes from a single frame is limited by real ambiguity (parking lanes, occlusion,
   opposite lanes), not only by the model: even GT masks give only 72 % exact lane states. Temporal smoothing cuts state
   flicker from 390 to 42 changes per minute on the highway clip.
6. **Traffic lights.** The Japan-trained Autoware classifier transfers well to US lights (87 % on lit lights). Yellow is
   the weak class, and its errors go mostly to red, the safer direction. HMM smoothing cuts colour flips 7-8x at night.
7. **Signs.** Most BDD "traffic sign" boxes are outside the plan's classes (street names, parking, guide signs). OCR
   verification removes all false speed-limit reads in the checked set without losing a true one. Exit, merge and
   construction signs are not covered by any backend.
8. **Segmentation.** EfficientViT-B1 is in the top group at a fraction of SegFormer-B2's cost. PIDNet-S generalises
   worst from Cityscapes to BDD, which refuted the literature estimate that preceded the measurement. Realtime keeps
   segmentation off because the lanes block already produces road geometry.
9. **openpilot.** comma.ai's vision model runs on BDD dashcam video with the right warp and calibration: lanes land on
   the paint, lead distance agrees with geometry (median ratio 0.96). It stays experimental and research-only.
10. **Realtime scheduling.** Splitting fast and slow blocks into two waves keeps wave 1 at about 16 Hz while depth and
    lanes refresh at about 8 Hz. The sim look-ahead makes display latency effectively zero. For the live uplink,
    `maxInFlight 2` has the best throughput and Wi-Fi robustness; `1` has the lowest latency; `3` only helps on an idle
    USB link.
11. **Lead-vehicle selection.** The lanes block's ego-lane polygon includes parking lanes and ends about 5 m ahead, so
    parked cars became "leads". The server now tests the box bottom against the ego vehicle's own +-1.3 m corridor along
    the ego lane direction, up to 80 m (`wire.py` `EgoPath`). On the city clip, false `VEHICLE TOO CLOSE` events dropped
    from 17 to 8-9 per 31 s and the median lead distance rose from 3.9 m to about 9 m.
12. **Light stickiness (Kotlin).** Several light heads at one intersection used to alternate as "nearest" and announce
    red/green back and forth. The Driving Context now keeps the chosen light unless another one is 5 m closer: light
    events fell from 22 to 9 in 20 s.

## Evaluation data

All evaluation data is BDD100K (plus a small KITTI sample), fetched file by file from public zip mirrors with HTTP Range
requests and CRC-checked (`scripts/fetch_bdd_samples.py`, pinned in `tools/bdd/sample_members.csv`). It is gitignored
and non-commercial.

| Set | Size | Used by |
|---|---|---|
| Stratified val keyframe subset | 250 images (day 123, night 93, dawn/dusk 34) | detection, lanes, traffic lights, segmentation geometry |
| MOT 2020 val | 7 sequences, 1,418 frames at 5 fps | tracking, depth agreement |
| Primary clips (`videos/val`) | 7 clips of about 40 s, incl. city `b1ff4656-0435391e`, highway `b1f4491b-cf446195`, night `b23adb0d-8a7aaced` | realtime runs, demos, sim mode |
| Extra clips (`videos/val_extra`) | 6 clips | openpilot, traffic end-to-end |
| sem_seg val | 30 images | segmentation |
| val_lights (`--lights`) | 400 keyframes, yellow-enriched | traffic-light tuning (DEV split) |
| KITTI (`--kitti`) | 100 object images + 50 depth pairs | depth |

Reproduce: the eval commands per block are in
[`perception_engine/README.md`](../../perception_engine/README.md#run-the-evaluations).

## Safety boundary

- **Display only** (plan section 38). No output steers, brakes or accelerates, and nothing claims autonomy or a safe
  distance. The server's hello carries this statement in its `safety` field.
- **No LLM in any safety path** (plan section 16). Every rule is deterministic.
- **Estimates, not guarantees.** Distances, TTC and light states are estimates with confidence values. `UNKNOWN` means
  unknown and is never guessed; `GREEN` is never permission to go.
- **Staleness.** If the newest result is older than 500 ms or the link is down, the tablet marks perception stale,
  suppresses object, distance, light and sign alerts, and keeps navigation-only guidance.
- **Not validated.** The accuracy figures come from small evaluation sets (N above) and are no safety claim. Any
  real-world deployment would need far more validation, safety engineering and regulatory work.

## Limits

- The evaluation sets are small (250 images, 7 MOT sequences, 30 segmentation images); confidence intervals are several
  points wide.
- Everything was tuned and measured on BDD100K dashcam video. Expect lower numbers on the tablet camera, a different
  mount height and field of view, and other regions.
- BDD clip distances depend on the assumed focal length (finding 4).
- Both lanes share one Python process; see the latency discussion above and in the [RUNBOOK](RUNBOOK.md).
- Licences: most realtime defaults are research/demo only. See [MODELS_AND_LICENSES.md](MODELS_AND_LICENSES.md).
