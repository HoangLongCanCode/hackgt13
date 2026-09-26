# Segmentation block: road / semantic segmentation + AR anchors (plan §13)

This block is part of the AI Spatial Driving Copilot perception prototype (HackGT 13). It answers the plan §13 question, **"where should AR content be anchored?"**, in two steps:

1. A 19-class Cityscapes semantic segmenter. The class ids are identical to the BDD100K sem_seg trainIds.
2. A **deterministic** geometry layer (no learning, no LLM; see plan §16) that turns the class map into a `RoadGeometry`: road coverage, ego road polygon, horizon / vanishing point, and ground-plane anchor points at 10, 20 and 40 m for the AR renderer.

Safety boundary (§38): all outputs are advisory visual context for a driver-facing display. They must not feed steering, braking or acceleration. When confidence is low, the renderer should fall back to screen-fixed HUD cues.

## API

Run from `perception_engine/` so that `import perception...` resolves.

```python
from perception.segmentation.semantic import SemanticSegmenter

seg = SemanticSegmenter(backend="efficientvit_b1", device="cuda", temporal=True)  # temporal=True for video
label = seg.segment(frame_bgr)                   # (720, 1280) uint8 trainIds: 0 road, 1 sidewalk, ... 18 bicycle
road = seg.road_geometry(frame_bgr, seg=label)   # perception.common.schemas.RoadGeometry
# or: label, road = seg.process(frame_bgr)
road.to_dict()        # {"drivableCoverage", "egoPathPolygon", "horizonY", "vanishingPoint", "anchorPoints"}
seg.last_info         # debug: horizon method / candidates, egoCorridorPolygon, walk stats
seg.timings_ms        # {"segment": ..., "road_geometry": ...}
seg.reset()           # between videos (clears the horizon tracker)
seg.release()
```

Constructor options: `backend` (see the table below), `device`, `fp16=True`, and `input_scale=1.0`, which resizes the frame before the network. `camera=CameraModel(...)` overrides the flat-ground camera; the default is `semantic.bdd_camera()`. `geometry=GeometryConfig(...)` holds every geometry threshold. `temporal` turns on horizon smoothing for video. `prob_ema=0.0` sets an optional EMA on class probabilities. `weights=<path>` overrides the checkpoint, and `half_weights` switches to pure-fp16 weights (the default for SegFormer). The geometry also works without a network: `perception.segmentation.geometry.compute_road_geometry(label, camera, cfg, tracker, frame_bgr)`.

### What `RoadGeometry` contains

| Field | Meaning |
|---|---|
| `drivableCoverage` | Fraction of the frame labelled road (trainId 0). |
| `egoPathPolygon` | The road region connected to the bottom centre: the 8-connected road component with the most pixels in the bottom-centre seed window (x 30-70 %, y 55-100 %), after a 5 px close/open at half resolution. It is the outer contour simplified with `approxPolyDP` (epsilon = 0.4 % of the perimeter) and clipped below the horizon. It includes the hood when the model calls the hood "road" (see caveats). |
| `horizonY`, `vanishingPoint` | The first method that passes its checks, in this order. **lines**: RANSAC vanishing point of Hough segments (lane markings, road edges) inside the dilated ego road region, with the angle-consistency inlier test and a length-weighted least-squares refine. **two_edges**: intersection of RANSAC line fits to the left and right edges of the ego road run. **single_edge**: one edge line intersected with the prior VP column. **road_top**: min(prior, top row of the ego road), since the road cannot sit above the horizon on flat ground. **prior**: y = 339.3 and x = 593.3, the medians of the lane-label vanishing points of 5,459 BDD val images that are not in the eval subset (`outputs/segmentation/horizon_prior.json`). With `temporal=True`, a `HorizonTracker` smooths the result: EMA alpha 0.15 on strong measurements, a 40 px jump gate, and a reset after 15 consecutive rejections. |
| `anchorPoints` | `ego_path_10m`, `ego_path_20m` and `ego_path_40m` are dicts with `xy` (the pixel the renderer pins an arrow or label to), `groundXZ` (metres: lateral, forward), `valid`, `onRoad`, `occludedBy` (e.g. `"car"`, meaning draw it behind), `roadEdgesXY` (the road run at that distance), `confidence` and `reason` when not valid. An extra `ego_path_centerline` entry carries a `polyline` for a path arrow. |

**How the anchors are built.** An ego corridor (+/- 1.4 m) is walked in ground coordinates from Z = 4.5 m to 80 m in 0.5 m steps. The start at 4.5 m skips the hood and near field. At each step the corridor centre moves only as far as it must to stay on the road run at that image row, and the lateral rate is bounded at 0.6 m per m. Vehicles and people count as "road underneath", so a car beside you is not treated as a road edge. The walk must see road in the central +/- 0.5 m strip before 15 m, and it stops 40 m after the last visible road. Each anchor is `(X_c(Z), Z)` projected with the flat-ground pinhole model, where the pitch is `atan((cy - horizonY)/f)` and (review fix) the yaw is `atan((vanishingPoint.x - cx) cos(pitch) / f)`, so X = 0 converges on the reported vanishing point. `GeometryConfig(yaw_from_vp=False)` restores the original zero-yaw projection; `last_info["yawDeg"]` holds the yaw used. The defaults are f = 700 px at 1280 width, camera height h = 1.3 m and principal point at the image centre; all are assumptions, since BDD100K has no intrinsics. An anchor is `valid` only if it lies on the visible stretch of the walk. Beyond that it is reported with `reason: "occluded"` (for example behind the lead vehicle), and the renderer should hide it. `last_info["egoCorridorPolygon"]` is the drawable path "carpet", cut at the last visible road.

## Backends tried (all measured here, 2026-09-25)

**Semantic segmentation**: 30 BDD100K sem_seg val pairs (`data/bdd100k/images/sem_seg_val`), one global confusion matrix, ignore = 255, 95 % bootstrap CI over images (2,000 resamples). `mIoU19` averages the classes with a non-empty union; train and motorcycle have no GT pixels. **N = 30, so the CIs are about +/- 5 points.**

> **Review note on the metric convention.** Every backend predicts some train and motorcycle pixels, but those two classes have no GT pixels in these 30 images. Each therefore enters `mIoU19` as a 0, so `mIoU19` = 17/19 x the mean over the 17 GT-present classes (`mIoU_gtPresent` in metrics.json). The ranking is identical under both conventions. Against published full-split numbers, such as the 61-65 zero-shot figure below, compare `mIoU_gtPresent`: pidnet_s 33.1, efficientvit_b0 36.7, **efficientvit_b1 49.0**, efficientvit_b2 49.8, segformer_b0 41.3, segformer_b2 48.5.

**Latency**: `segment()` end to end (upload, forward, bilinear upsample to 1280x720, argmax, download), batch 1, fp16, 10 warm-up and 50 timed iterations, RTX 5060 Laptop **shared with other agents**. These timings are **PROVISIONAL**.

| Backend | Network input | mIoU19 | 95 % CI | AR-core mIoU* | road IoU | pixel acc | p50 / p95 ms | peak VRAM | Status |
|---|---|---|---|---|---|---|---|---|---|
| `pidnet_s` | 1280x720 (native) | 29.7 | 25.7-35.8 | 38.0 | 77.2 | 75.6 | 17.9 / 25.3 | 108 MB | works; weak under domain shift |
| `pidnet_s`, `input_scale=1.5` | 1920x1080 | 31.1 | 27.4-36.6 | 40.2 | 81.1 | 75.9 | 18.2 / 24.4 | 149 MB | works |
| `efficientvit_b0` | 1280x736 (padded to /32) | 32.8 | 29.1-38.0 | 44.2 | 87.4 | 81.6 | 15.7 / 18.9 | 113 MB | works |
| **`efficientvit_b1` (default)** | 1280x736 | **43.8** | 38.3-49.9 | 53.6 | 91.7 | 89.1 | 27.3 / 35.0 | 133 MB | works |
| `efficientvit_b1`, `input_scale=1.5` | 1920x1088 | 42.0 | 35.7-46.7 | 53.5 | 87.7 | 87.4 | 31.0 / 36.8 | 234 MB | works, no gain |
| `efficientvit_b2` | 1280x736 | 44.6 | 39.1-52.3 | 55.5 | 93.2 | 89.5 | 37.3 / 44.7 | 210 MB | works |
| `segformer_b0` (NVIDIA NC) | 1280x720 | 37.0 | 33.2-41.0 | 47.6 | 87.6 | 85.8 | 19.9 / 23.9 | 455 MB | works |
| `segformer_b2` (NVIDIA NC) | 1280x720 | 43.4 | 38.4-49.7 | 54.6 | 91.0 | 88.7 | 54.5 / 59.5 | 1,295 MB | works (pure fp16; under autocast it peaked at 2,374 MB, over the 1.5 GB budget, so it now defaults to `half_weights`) |
| EoMT-L Cityscapes | - | - | - | - | - | - | - | - | not run: the 1.28 GB checkpoint exceeds the 1 GB download cap |

\*AR-core = road, sidewalk, building, vegetation, terrain, car, truck, bus, person, rider. Per-class IoU is in `outputs/segmentation/metrics.json` and `per_class_iou.png`.

For reference, the published Cityscapes-to-BDD zero-shot numbers are 61-65 mIoU for ViT-L-class models (Rein paper), and the best BDD-trained model reaches 67.3. The research track estimated 45-55 for PIDNet-S. That estimate is **refuted here**: PIDNet-S generalises worst of the six. An RGB/BGR swap check gives 24.6, against 29.7 as implemented, so the input order is right. Upscaling the input 1.5x does not help EfficientViT and helps PIDNet only slightly.

**Chosen default: `efficientvit_b1`.** It is Apache-2.0 code with Apache-2.0-tagged weights. Its accuracy is in the top group, statistically tied with B2 and SegFormer-B2, and it is 1.4x faster than B2 and 2x faster than SegFormer-B2. It uses 133 MB of VRAM and had the best road recall. Use `efficientvit_b0` or `pidnet_s` only if the frame budget is tight; their road IoU is 87 and 77.

### Geometry / AR anchors (measured here)

These were measured on the 250 val keyframes (`data/bdd100k/images/val`) against the locally rasterised drivable masks. The **horizon reference** is the median pairwise intersection of straight "parallel" lane labels, accepted only when the intersections agree (MAD <= 12 px). It exists for 132 of the 250 images. It is a proxy, not true ground truth. The estimator was tuned only on a separate 120-image dev set (`outputs/segmentation/devset`, disjoint from the 250), so these numbers are held out.

The reviewer confirmed that the horizon prior (y = 339.3, x = 593.3, n = 5,459) reproduces exactly from `bdd100k_labels_images_val.json` and excludes all 132 eval images that have a VP reference. The held-out claim covers the **horizon only**. The dev set has no drivable masks, and `eval_log.txt` shows the corridor/anchor code was revised after the 250-image results were seen: B1 corridor precision was 69.2 in that log and 79.8 in the builder's final metrics. So the corridor and anchor numbers are not strictly held out. The review yaw fix has no tuned parameters, and it was confirmed independently on the dev set (see Review fixes). The horizon reference also exists only for the 132 images with straight "parallel" lane labels, the same images where the "lines" method tends to work, so the horizon numbers are optimistic for curved roads and for images without markings.

| Metric (efficientvit_b1 unless noted) | Value |
|---|---|
| Horizon \|error\|, estimator (single image, no tracking) | median **12.5 px**, 64 % within 20 px (N=132) |
| Horizon \|error\|, constant-prior baseline | median 41.6 px, 26 % within 20 px |
| Horizon, "lines" method only (chosen on 44 % of all images) | median **4.5 px**, 90 % within 20 px (N=73; prior on the same images: 38.9 px) |
| Horizon, "road_top" fallback (chosen on 51 %) | median 47.6 px (N=52), i.e. hardly better than the prior. Treat it as low confidence (0.3) |
| Road class recall of GT drivable area / precision in GT drivable | 94.5 % / 48.0 %. Road is a superset: opposite lanes, parking lanes, hood |
| Ego corridor ("carpet") pixels inside GT drivable / inside GT direct lane | 81.2 % / 74.5 % (before the review yaw fix: 79.8 / 72.5) |
| Anchor 10 m: rate drawable (valid, not occluded) / inside GT drivable / within 10 px / inside GT direct lane / median \|x - direct-lane centre\| | 66.1 % / **84.2 %** / 89.2 % / 80.4 % / 45.5 px (before the fix: 66.9 / 79.4 / 88.1 / 72.5 / 63.5 px) |
| Anchor 20 m: same | 47.7 % / **71.9 %** / 78.9 % / 69.3 % / 28.5 px (before: 45.6 / 60.6 / 76.1 / 51.4 / 51.8 px) |
| Anchor 40 m: same | 33.5 % / **55.0 %** / 77.5 % / 52.5 % / 27.0 px (before: 28.5 / 36.8 / 60.3 / 30.9 / 33.8 px) |
| Anchors flagged `occludedBy`: inside a GT vehicle/person box | 75 % (10 m), 80 % (20 m), 50 % (40 m). Only about 8, 5 and 2 anchors are flagged, so this is anecdotal |
| 10 m anchor inside drivable when confidence >= 0.5 | 90.9 % (on 32.2 % of images) |
| `road_geometry()` CPU time | p50 about 15-18 ms, p95 about 23-32 ms across runs (Python; can run in a worker thread) |

Other backends (after the review fix): `pidnet_s` gave 13.1 px median horizon error, a 78.4 % 10 m anchor hit rate and 61 % road recall. `efficientvit_b2` gave 12.7 px and 76.6 %. `segformer_b0` gave 12.4 px and 78.6 %.

At night (93 images) the 10 m hit rate is 79.4 %, against 86.3 % by day (before the fix: 76 % and 82 %). The pixel hit test is harsh at 40 m, where the anchor is only about 23 px below the horizon and BDD's drivable polygons end in a thin sliver or at the lead vehicle. The "within 10 px" column shows this.

**Video (10 s from t = 5 s, efficientvit_b1, temporal=True; `outputs/segmentation/overlay_*.mp4`)**

| Clip | Horizon std (tracked / raw) | Mean \|frame-to-frame delta\| (tracked / raw) | Raw "lines" share | Ego-polygon IoU t to t+1 (median) | 20 m anchor valid | 20 m anchor median \|dx\| per frame | seg / geo p50 ms |
|---|---|---|---|---|---|---|---|
| b20e291a residential | 6.6 / 9.6 px | 0.83 / 5.1 px | 97 % | 0.956 | 98.7 % | 0.9 px | 27.3 / 18.7 |
| b204a5c1 snow | 5.3 / 13.7 px | 0.62 / 8.9 px | 85 % | 0.972 | 100 % | 0.5 px | 25.1 / 29.3 |
| b23adb0d night (failure-mode example) | 15.2 / 29.3 px | 0.55 / 19.8 px | 29 % | 0.869 | 99.7 % | 0.0 px | 26.0 / 17.1 |

These are re-measured after the review fix. Before it, the 20 m anchor x was pinned at the principal-point column 640 whenever the corridor stayed at X = 0 (median \|dx\| = 0.0 on every clip). It now follows the tracked VP column. On the night clip the VP mostly falls back to the prior, so the anchor is still fixed there.

In the night clip the hood and glare get labelled "road", and the tracked horizon drifts upward over the 10 s. Anchors there look plausible but are not trustworthy.

## Reproduce

```powershell
cd perception_engine
# semantic (6 backends + 1.5x scale variants) and geometry (4 backends): metrics.json + PNGs, about 5 min
.\.venv\Scripts\python.exe -m perception.segmentation.eval_bdd --scales 1.0,1.5
# the three annotated 10 s clips + temporal metrics (merged into metrics.json under "video")
.\.venv\Scripts\python.exe -m perception.segmentation.make_overlays --backend efficientvit_b1
```

Useful flags: `eval_bdd --backends pidnet_s,efficientvit_b1 --skip-geometry` and `--geometry-backends efficientvit_b1 --skip-semantic`, plus `make_overlays --clips <id,...> --start 5 --seconds 10 --no-video`.

The review cross-check compares yaw-fix off and on for efficientvit_b1 on the eval set and on the 120-image dev set, whose drivable masks are rasterised with the data tool's rasteriser. It takes about 2 min and writes `outputs/segmentation/review_yaw_check.json`:

```powershell
.\.venv\Scripts\python.exe -m perception.segmentation.review_yaw_check
```

## Outputs (`outputs/segmentation/`)

- `metrics.json`: `semantic.<backend>`, `geometry.<backend>` and `video.<clip>`.
- `per_class_iou.png`, `speed_vs_miou.png`, `semseg_examples.png` (image, GT, PIDNet-S, EffViT-B1, SegFormer-B2), `geometry_examples.png` (overlays with the GT drivable outline in magenta and the lane-label VP as a magenta X), and `horizon_error_hist.png`.
- `overlay_b20e291a-6012d836.mp4`, `overlay_b204a5c1-064b0040.mp4` and `overlay_b23adb0d-8a7aaced.mp4` (1280x720, mp4v, 10 s).
- `road_<clip>.jsonl`: one `FrameResult` per frame with `road` and `timingsMs`, for the integrator and frontend.
- `geometry_rows_efficientvit_b1.jsonl` (per-image geometry eval rows), `horizon_prior.json`, `devset/` (120 dev images + labels) and `eval_log.txt`.

## Files

`semantic.py` (SemanticSegmenter, backends, `bdd_camera`, `colorize`), `geometry.py` (CameraModel, GeometryConfig, HorizonTracker, `compute_road_geometry`), `gt_horizon.py` (lane-label VP reference, eval only), `viz.py` (overlay drawing), `_vendor.py` (loads the vendored PIDNet / EfficientViT code without their package `__init__`s), `eval_bdd.py`, `make_overlays.py` and `review_yaw_check.py` (reviewer cross-check). Weights and licenses are in `MODELS.md`.

## Licenses (details in MODELS.md)

- **All checkpoints are Cityscapes-trained, so they are non-commercial**: demo, research and evaluation only.
- PIDNet: MIT code and weights.
- EfficientViT: Apache-2.0 code and weights tag.
- **SegFormer: NVIDIA Source Code License, research or evaluation only, for both code and weights.** Do not ship it.
- BDD100K evaluation data: research and non-commercial only.
- The commercial path is a Level-2 retrain, for example an EfficientViT or DINOv2 backbone on comma10k (MIT data), with labels of our own.

## Caveats

- **N = 30** for mIoU, with CIs of about +/- 5 points. The ranking between B1, B2 and SegFormer-B2 is not significant. Rare classes are nearly absent from the GT: rider and bicycle each appear in only 1 of the 30 images, bus in 3 and truck in 8. Their per-class IoUs are essentially anecdotal.
- **Latency is provisional.** The GPU was shared with up to six other processes, and p50 moved by up to about 2x between runs; EfficientViT-B1, for example, ranged from 25 to 41 ms. Re-benchmark in isolation. EfficientViT under autocast was not faster than fp32 here, because LiteMLA upcasts attention to fp32. TensorRT or ORT export was not attempted.
- **Camera model assumptions.** f = 700 px, h = 1.3 m and zero roll are assumed. Forward distances scale linearly with f and h. Lateral corridor widths in pixels do not depend on f. One spot check was consistent: on b1f4491b, the lead car's pixel width at its ground-contact row matched a car about 1.8 m wide at about 10 m. This checks h against the horizon, not f.
- **Flat-ground assumption.** Hills and crests bend the anchors. A crest shows up as an early walk stop.
- **Hood.** The models often label the ego hood as road or car, so `egoPathPolygon` can include it. Anchors and the corridor start at 4.5 m, which avoids the hood. A per-video hood mask would be a cheap improvement.
- **Night and glare.** Segmentation degrades, and the "lines" method finds a VP on only about 30 % of night frames. The raw horizon falls back to road_top/prior with confidence 0.3; the renderer should gate on `confidence`.
- **Reference data.** The horizon reference comes from lane labels (a proxy). The drivable masks were rasterised locally from poly2d, not taken from the official PNGs.
- **Road is not drivable.** The semantic "road" class includes opposite lanes and parking lanes, which is why road precision against BDD drivable area is 48 %. Lane-level direct/alternative needs the lanes or drivable blocks; TwinLiteNet+ is recommended by the research track.
- **`drivableCoverage` is the road-class fraction.** The shared schema describes this field as "fraction of frame that is drivable". Here it is trainId 0 (road), which is about twice the BDD drivable area (48 % precision). Integrators should not read it as drivable area.
- **SegFormer loading contacts the Hub.** `from_pretrained("nvidia/...")` makes an unauthenticated HF Hub request on every load, then uses the local cache. For an offline demo, set `HF_HUB_OFFLINE=1` or pass the snapshot path as `weights=`.

## Review fixes (independent reviewer, 2026-09-25)

**Reproduction.** The reviewer re-ran `eval_bdd --scales 1.0,1.5` and `make_overlays --backend efficientvit_b1` with the builder's code. Every non-latency number in metrics.json reproduced exactly: all 8 semantic configs, all 4 geometry backends and all 3 clips. An independent re-implementation of the semantic metric (boolean-mask TP/FP/FN instead of bincount) gave identical values. Latency did not reproduce, because the GPU was at about 83 % utilisation from other agents. The re-measured p50s were about 2-3x the builder's: pidnet_s 38, efficientvit_b0 39, efficientvit_b1 61, efficientvit_b2 83, segformer_b0 62 and segformer_b2 168 ms. Treat the latency table as provisional, as stated.

**Checks that passed:**
- GT trainIds contain only 0-15, 18 and 255.
- Colour labels match the trainIds on 100 % of labelled pixels.
- The GT overlays line up with the images.
- The SegFormer id2label order equals the Cityscapes trainId order.
- EfficientViT preprocessing matches upstream `eval_efficientvit_seg_model.py`: RGB input, ImageNet mean/std, bilinear logit resize.
- Pad-and-crop is exact: for 720 -> 736, the stride-8 crop is 90 rows.
- fp16 autocast vs fp32 changes B1 by 0.13 and PIDNet by 0.00 mIoU.
- PIDNet with `align_corners=True`, as in the upstream eval, gains only +0.4, so its low score is domain shift, not a bug.
- Category names in `eval_subset_det.json` are the 2018 names (`person`, `bike`, `motor`), which match `GT_OCCLUDERS`.
- The drivable masks hold only {0, 1, 2}.
- The dev, eval and sem_seg sets are disjoint.
- The horizon prior reproduces exactly and excludes the eval images.
- Weight sizes and sha256 prefixes match MODELS.md.
- The vendored repos are clean at the recorded commits.

**Fix 1 (bug, `geometry.py`): the ground model ignored camera yaw.** `CameraModel` projected with pitch only. Straight-ahead ground lines (X = const) therefore converged at (cx = 640, horizonY), not at the `vanishingPoint` that `RoadGeometry` itself reports. The median BDD lane VP column is 593, so the ego path and anchors were biased about 47 px to the right on average: about 0.7 m of lateral error at 10 m, 1.3 m at 20 m and 2.7 m at 40 m, and far more on individual images, where the VP column spans 441-733 px (p10-p90).

What changed:
- `CameraModel` gains `yaw_rad` and `with_heading(vp_x, horizon_y)`.
- `compute_road_geometry` uses the prior VP column for the edge-collection walk, and the final (tracked) VP column for the corridor walk, the corridor polygon and the anchors.
- `GeometryConfig.yaw_from_vp=False` restores the old behaviour, and reproduces the builder's numbers exactly.
- The fix has no tuned parameters. The horizon estimate is unchanged for B1 and PIDNet; B2 and SegFormer-B0 moved by about 1 px through the edge-collection walk.

Measured here with efficientvit_b1 (`review_yaw_check`). Each cell reads: drawable anchor inside GT direct lane, then median |x - direct-lane centre|, before -> after the fix:

| Set | 10 m | 20 m | 40 m |
|---|---|---|---|
| Eval 250 | 72.5 % / 63.5 px -> **80.4 % / 45.5 px** | 51.4 % / 51.8 px -> **69.3 % / 28.5 px** | 30.9 % / 33.8 px -> **52.5 % / 27.0 px** |
| Dev 120 (independent; masks rasterised by the reviewer) | 50.0 % / 70.5 px -> **67.5 % / 37.8 px** | 30.4 % / 61.0 px -> **64.8 % / 19.5 px** | 10.8 % / 47.5 px -> **56.4 % / 13.5 px** |

A fixed yaw taken from the prior VP column alone gave only small, mixed gains (eval 40 m 34.2 %, dev 40 m 14.6 %). The per-frame VP column is what matters.

**Fix 2 (docs):**
- Stale README numbers were corrected: the road_top median is 47.6 px, not 49.5.
- A note on the metric convention was added: `mIoU19` = 17/19 x `mIoU_gtPresent`.
- The held-out claim is now limited to the horizon.

**Fix 3 (eval):** `eval_bdd.py` now also reports `drawable_medianErrToDirectLaneCentre_px` for each anchor distance.

**Files touched:** `geometry.py`, `eval_bdd.py`, `README.md`, and the new `review_yaw_check.py`. Regenerated: `outputs/segmentation/metrics.json`, the PNGs, the 3 overlay clips and `road_*.jsonl`, `geometry_rows_efficientvit_b1.jsonl`, and `review_yaw_check.json`. `eval_log.txt` is the builder's pre-fix log and is left as is.
