# Detection block (plan sections 6 and 7)

This block does 2D detection of vehicles, vulnerable road users, traffic lights and traffic signs. It is part of the
AI Spatial Driving Copilot HackGT 13 prototype. It returns the plan's section 7
`Detection` objects (`perception/common/schemas.py`) with canonical BDD100K class names. The section 8 tracker, section 9
distance, section 11 light-state and section 12 sign-class blocks can consume them unchanged.

**Default: `bdd-yolo26s` at imgsz 960** (Ultralytics YOLO26s fine-tuned on BDD100K by DetectionBench, conf 0.25).
**It is for research and demo use only**; see [Licenses](#licenses).

## API

```python
# run from perception_engine/ so `import perception...` resolves
from perception.detection import Detector

det = Detector()                                    # = Detector(backend="ultralytics", weights="bdd-yolo26s", imgsz=960, conf=0.25)
dets = det.detect(frame_bgr)                        # list[Detection]; frame: upright HxWx3 uint8 BGR (e.g. VideoFileInput)
dets[0].to_dict()   # {'id': None, 'class': 'car', 'bbox': [921.3, 359.3, 1169.3, 518.0], 'confidence': 0.915}
det.last_timings_ms          # {'preprocess':..., 'inference':..., 'postprocess':..., 'total':..., 'detect_total':...}
det.last_source_classes      # the checkpoint's own class names, aligned with `dets` (e.g. 'stop sign' for COCO)
det.close()                  # free GPU memory
```

Constructor: `Detector(backend=None|"ultralytics"|"rfdetr", weights=<preset or path>, imgsz=None, conf=None, device="cuda",
*, iou=0.7, half=None, max_det=300, classes=None, rider_heuristic=False, nms=None, allow_download=True, optimize=True)`.

- `weights` takes a preset name (below) or a checkpoint path. With a preset, the preset sets `backend`, the native
  `imgsz` and the default `conf`.
- `backend` alone picks that backend's default preset: `Detector(backend="rfdetr")` loads `bdd-rfdetr-nano` and
  `Detector(backend="ultralytics")` loads `bdd-yolo26s`. A backend that conflicts with the preset raises `ValueError`
  (see Review fixes).
- `classes={"car", "truck", "bus"}` keeps only those canonical classes.
- `nms=False` switches YOLO26 to its NMS-free one-to-one head. It is as accurate as the default here; see the results.
- `rider_heuristic=True` (COCO models only) relabels a person whose lower half sits on a bicycle or motorcycle box as
  `rider`. It is a deterministic geometric rule.
- `detect_raw(img)` returns boxes before class mapping. `warmup()` and `describe()` are also available.
- Boxes are `[x1, y1, x2, y2]` floats in source-frame pixels, clipped to the frame. `id` stays `None` until section 8
  tracking assigns one.
- Output is deterministic for fixed weights, thresholds and input. No LLM is involved (plan sections 16 and 38).

| Preset | Backend | Classes | imgsz (as run) | default conf | Notes |
|---|---|---|---|---|---|
| `bdd-yolo26s` **(default)** | ultralytics | 10 BDD | 960 (letterboxed rect: 960x544 for 16:9) | 0.25 | DetectionBench fine-tune |
| `bdd-yolo26n` | ultralytics | 10 BDD | 960 | 0.25 | |
| `bdd-rfdetr-nano` | rfdetr | 10 BDD | 576x576 (square resize, native) | 0.40 | Apache code; scores run lower, so 0.25 over-fires |
| `coco-yolo26s` | ultralytics | 8 of 10 (+ stop sign) | 640 | 0.25 | COCO baseline |
| `coco-rfdetr-nano` | rfdetr | 8 of 10 (+ stop sign) | 384x384 | 0.35 | COCO baseline |
| `coco-rfdetr-small` | rfdetr | 8 of 10 (+ stop sign) | 512x512 | 0.30 | COCO baseline |

COCO to BDD mapping (`COCO_TO_BDD`): person to pedestrian; bicycle, car, motorcycle, bus, train, truck and traffic light
keep their names; stop sign becomes traffic sign (only partly: BDD's traffic sign covers every sign face). All other COCO
classes are dropped. COCO has no rider class. BDD-trained checkpoints use the 2018 names person, motor and bike, which
map to pedestrian, motorcycle and bicycle through `schemas.canonical_class`. The class order was checked for each
checkpoint (see `MODELS.md`), and every mapping is done by name.

Wiring notes for the integrator:

- Route `traffic light` boxes to the section 11 colour classifier and `traffic sign` boxes to section 12. The section 7
  vehicle subset is `schemas.VEHICLE_CLASSES` plus pedestrian and rider.
- Tracking: the section 8 block owns it. With the Ultralytics backend, `det.model.track(..., tracker="bytetrack.yaml")`
  is also available. Pass the tracker explicitly, because the 8.4.163 default is `tracktrack.yaml`.
- Import torch before creating any onnxruntime session (see `perception_engine/SETUP.md`, known issues). `Detector` imports torch first.

## Results (measured here)

**Setup:** 250 BDD100K val keyframes from `data/bdd100k/labels/eval_subset_coco.json` (2018 labels, 4,604 boxes, no
ignore regions). The evaluator is faster-coco-eval 1.8.0 `COCOeval_faster` with standard COCO bbox metrics and
maxDets 100; one evaluator is used for every model. The prediction threshold is conf 0.001 with max_det 300.

**Checks:**

- pycocotools 2.0.11 gives the identical default-run number (0.38494 vs 0.38494; `metrics.json` field `crosscheck`).
- Boxes follow the scalabel convention `[x1, y1, x2-x1+1, y2-y1+1]`. The same +1 is applied to predictions, as the
  official bdd100k eval does.
- Category ids are looked up by name.

**Class sets:**

- **map8** covers the 8 classes a COCO model can express: pedestrian, car, truck, bus, train, motorcycle, bicycle and
  traffic light. It is the apples-to-apples comparison across all models.
- **all10** covers every BDD class and applies to BDD-trained models only.

**How to read the uncertainty columns:**

- **95% CI:** image-level bootstrap, B=200, seed 0. It is slightly skewed upward, because a resample that has no `train`
  image drops that class from the mean.
- **Difference vs default:** a paired bootstrap on the same resamples and the same class set. COCO rows compare map8
  with the default's map8.

**Latency:** `detect()` wall time on a 1280x720 BGR frame, covering resize or letterbox, FP16 inference,
NMS or postprocess and class mapping, at conf 0.25. The figure is the median of 3 interleaved rounds of 100 frames
(`bench_latency.py`); the least-contended round is in parentheses. These timings are **PROVISIONAL**: up to 6 other
agents were using the GPU (RTX 5060 Laptop, 8 GB; PyTorch 2.14 cu130 eager).

| Run | map8 mAP50-95 | map8 mAP50 | all10 mAP50-95 | all10 mAP50 | all10 excl. train | box-weighted AP | 95% CI (all10, else map8) | diff vs default, same class set [95% CI] | p50 ms (min round) | peak VRAM MB |
|---|---|---|---|---|---|---|---|---|---|---|
| `bdd-yolo26s@960` **default** | 37.9 | 61.8 | **38.5** | 62.9 | **42.8** | **45.6** | 34.3-44.0 | reference | 14.3 (10.8) | 93 |
| `bdd-yolo26s@640` | 33.5 | 55.7 | 34.2 | 57.2 | 38.0 | 40.9 | 30.6-39.3 | -4.3 [-6.0, -2.9] | 12.1 (9.5) | 72 |
| `bdd-yolo26n@960` | 33.1 | 55.8 | 33.3 | 56.5 | 37.1 | 41.6 | 29.7-38.2 | -5.3 [-7.2, -3.7] | 14.1 (10.0) | 56 |
| `bdd-yolo26n@640` | 26.5 | 45.3 | 26.7 | 46.2 | 29.7 | 35.5 | 23.6-31.4 | -11.8 [-14.4, -9.8] | 11.6 (9.8) | 46 |
| `bdd-rfdetr-nano@576` | 38.4 | 63.6 | 38.7 | 64.5 | 41.5 | 41.2 | 34.6-45.6 | +0.6 [-2.6, +4.3] | 14.1 (13.2) | 474 |
| `coco-yolo26s@640` | 23.0 | 38.6 | - | - | - | 28.0 | 19.6-28.0 | -14.6 [-19.7, -10.8] | 10.0 (9.8) | 72 |
| `coco-yolo26s@960` | 24.8 | 41.3 | - | - | - | 31.9 | 21.8-30.6 | -12.7 [-17.3, -9.0] | 10.8 (10.6) | 85 |
| `coco-rfdetr-nano@384` | 23.2 | 40.8 | - | - | - | 25.8 | 19.2-29.3 | -14.3 [-19.2, -8.8] | 13.9 (12.0) | 337 |
| `coco-rfdetr-small@512` | 27.6 | 48.1 | - | - | - | 31.7 | 23.7-33.9 | -9.8 [-14.0, -4.2] | 13.9 (12.5) | 435 |
| `bdd-yolo26s@960:e2e` (`nms=False`) | 38.9 | 62.5 | 39.1 | 63.4 | 43.3 | 45.8 | 35.5-44.5 | +0.8 [-0.4, +2.2] | 10.4 (10.1) | 85 |

"box-weighted AP" weights each class's AP50-95 by its number of GT boxes. It shows which model does better on the
objects that actually fill a driving frame.

Per-class AP@[.50:.95] (%) with GT box counts, from `outputs/detection/per_class_ap.png`:

| Run | pedestrian (n=301) | rider (n=23) | car (n=2618) | truck (n=101) | bus (n=45) | train (n=4) | motorcycle (n=15) | bicycle (n=28) | traffic light (n=638) | traffic sign (n=831) |
|---|---|---|---|---|---|---|---|---|---|---|
| `bdd-yolo26s@960` | 40.1 | 40.7 | 51.4 | 52.2 | 51.8 | 0.0 | 31.8 | 46.2 | 29.8 | 41.0 |
| `bdd-yolo26s@640` | 33.9 | 38.0 | 46.6 | 47.0 | 43.4 | 0.0 | 26.5 | 44.5 | 26.0 | 36.4 |
| `bdd-yolo26n@960` | 34.0 | 31.7 | 47.6 | 48.1 | 46.6 | 0.0 | 20.0 | 42.1 | 26.7 | 36.7 |
| `bdd-rfdetr-nano@576` | 33.3 | 41.5 | 46.6 | 53.6 | 54.1 | 13.1 | 35.1 | 46.8 | 25.0 | 37.5 |
| `coco-yolo26s@960` | 30.4 | - | 36.7 | 35.3 | 36.6 | 4.3 | 15.8 | 27.1 | 12.5 | - |
| `coco-rfdetr-small@512` | 26.6 | - | 36.5 | 44.1 | 38.6 | 9.5 | 22.1 | 30.8 | 12.7 | - |
| `bdd-yolo26s@960:e2e` | 39.4 | 39.7 | 51.4 | 55.5 | 57.2 | 1.3 | 30.9 | 44.4 | 30.7 | 40.8 |

The table leaves out some runs; `metrics.json` has all of them. Size breakdown for the default (all10): AP75 39.4,
APs 18.7, APm 45.9, APl 62.0.

Time-of-day slices (map8 mAP50-95, %; `timeofday_slices.png`). The slices cover daytime 123 images / 2,383 boxes,
night 93 / 1,532 and dawn/dusk 34 / 689. The dawn/dusk slice is small and noisy.

| Run | daytime | night | dawn/dusk |
|---|---|---|---|
| `bdd-yolo26s@960` | 39.4 | 37.0 | 45.8 |
| `bdd-yolo26n@960` | 34.6 | 31.8 | 40.4 |
| `bdd-rfdetr-nano@576` | 38.7 | 40.6 | 45.3 |
| `coco-yolo26s@960` | 28.7 | 18.5 | 30.8 |
| `coco-rfdetr-small@512` | 31.1 | 22.5 | 36.4 |
| `bdd-yolo26s@960:e2e` | 39.8 | 41.4 | 45.0 |

**Default operating point** (conf 0.25, IoU 0.5, greedy matching, box-weighted over the 10 classes): precision 0.82,
recall 0.69, F1 0.75.

- A coarse sweep of 0.10 to 0.70 in steps of 0.05 peaks at conf 0.20 (F1 0.752, the same as at 0.25).
- Per class at 0.25: car P 0.85 / R 0.73, pedestrian 0.82 / 0.60, traffic light 0.78 / 0.66, traffic sign 0.77 / 0.65.
- For `bdd-rfdetr-nano`, conf 0.25 gives P 0.60 / R 0.76. Its best F1 is at conf 0.40 (0.727), which is why that preset
  defaults to 0.40.
- These thresholds were tuned on the same 250 images, so treat them as coarse defaults.

### What the numbers say

1. **Fine-tuning on BDD100K is worth about 10 to 15 points** on the 8 shared classes for the same architecture:
   YOLO26s@960 goes from 24.8 to 37.9, YOLO26s@640 from 23.0 to 33.5, RF-DETR-N from 23.2 (COCO@384) to 38.4 (BDD@576).
   - The best COCO model (RF-DETR-S, 27.6) trails BDD YOLO26s@960 by 10.3 points and even BDD YOLO26n@960 by 5.5.
   - The largest losses are traffic lights (8 to 13 AP vs 22 to 31 for the BDD models) and the night slice (17 to 23 vs
     37 to 41 for BDD YOLO26s@960 and RF-DETR-N).
   - COCO models cannot produce `rider` at all. They cover `traffic sign` only through `stop sign`: traffic-sign AP is
     3.7 (YOLO26s@640) to 6.1 (RF-DETR-S) against 831 GT signs.
   - The rider heuristic yields 16 to 27 rider AP (23 GT) but slightly lowers pedestrian AP (YOLO26s@640 25.9 to 24.8),
     so it stays off by default.
   - COCO YOLO26s also fires on the **ego-vehicle hood** as `car`. The bottom-of-frame box is visible in both frames of
     `examples_day_night.png` (a qualitative observation, not measured over the set). That is a false "vehicle ahead" for section 18. BDD-trained models do not do this.
2. **Resolution matters more than model size.**
   - YOLO26s drops 4.3 points going from 960 to 640 (CI excludes 0). YOLO26n@960 is 5.3 points below s@960.
   - Small objects suffer most: at 640, traffic light AP falls from 29.8 to 26.0 and APs from 18.7 to 13.4.
3. **RF-DETR-N (BDD) and YOLO26s@960 (BDD) tie on class-mean mAP** (+0.6 [-2.6, +4.3]).
   - YOLO26s is better on the high-count, small classes the copilot relies on: car +4.8, pedestrian +6.8, traffic light
     +4.8, traffic sign +3.5, APs 18.7 vs 15.9. It is also higher on box-weighted AP (45.6 vs 41.2) and best F1 (0.752 vs
     0.727), and it uses 5x less VRAM (93 vs 474 MB).
   - RF-DETR-N wins on large objects (APl 69 vs 62), trucks and buses, the rare `train` class (4 GT boxes, worth 1.3
     points of class-mean mAP on its own) and the night slice.
   - **So the default stays `bdd-yolo26s@960`, which agrees with the verified research recommendation. `bdd-rfdetr-nano`
     is the Apache-code alternate.**
4. **The YOLO26 NMS-free head (`nms=False`) matches the NMS head** (+0.8 [-0.4, +2.2]), with a better night slice and a
   slightly lower F1 at its best threshold (0.741 vs 0.752). The default keeps Ultralytics' standard NMS path. Switch to
   `nms=False` if fixed-cost post-processing is preferred.
5. **Sanity vs the published card.**
   - DetectionBench reports 33.86 mAP50-95 / 58.76 mAP50 for YOLO26s on the full 10k val with Ultralytics val. This
     subset gives 38.5 / 62.9.
   - The subset is stratified toward night and dawn/dusk and a different metric implementation is used, so this is not
     a like-for-like comparison. The number is in the expected range, so there is no class-order or box-format bug.
   - The +1 box convention moves mAP by about 0.4 to 0.8 points: 38.5 consistent vs 38.1 mismatched vs 37.7 with +1
     stripped from both (`metrics.json` field `bbox_convention_check`).
6. **Small-N caveat.** 250 images give CIs about ±4 to 5 points wide on mAP. `train` (4 boxes), motorcycle (15), rider
   (23) and bicycle (28) per-class APs are close to noise. Differences under about 2 points between runs are not
   meaningful here, except where the paired CI says otherwise.

### Latency (provisional)

- Every model lands at about 10 to 14 ms p50 per 1280x720 frame (70 to 100 FPS) in eager PyTorch on this GPU. Windows
  eager small-CNN inference is launch-bound, which is why YOLO26n is no faster than s.
- Round-to-round spread under contention from other GPU jobs is 1 to 5 ms (`accuracy_vs_latency.png` whiskers).
- The two annotated clips below ran at p50 14.9 ms (city) and 15.5 ms (night) for detect() while other jobs were
  running (`videos_summary.json`).
- **Faster paths (not measured here, from the verified research track):**
  - CUDA-graph replay at a fixed letterbox: YOLO26s about 5.3 ms at 544x960, 2.1 ms at 384x640.
  - ORT CUDA EP with IO binding: YOLO26s about 7.7 ms model-only at 544x960.
  - TensorRT FP16. It needs `tensorrt-cu13` 10.x for ORT's TensorRT EP, and it is not installed.
- An integrator should re-benchmark alone.

## Outputs (`outputs\detection\`)

| File | What |
|---|---|
| `metrics.json` | Everything above. Per run: map8/all10 overall, per-class (with n_gt), AP75/APs/APm/APl, time-of-day slices, operating point, threshold sweep, COCO diagnostics, latency. Plus the pycocotools cross-check, the bbox-convention check and the bootstrap |
| `latency.json` | Interleaved latency benchmark (per-round p50/p90, peak VRAM, GPU state at each round start) |
| `preds/*.json` | Raw predictions per run (conf ≥ 0.001), reused by `--reuse-preds` |
| `map_by_run.png`, `accuracy_vs_latency.png`, `per_class_ap.png`, `timeofday_slices.png`, `examples_day_night.png` | Figures |
| `b1ff4656-0435391e_bdd-yolo26s.mp4` / `.jsonl` | City intersection, day, t = 5 to 15 s, 300 frames, 1280x720 mp4v, with a per-frame `FrameResult` JSONL (detections only). About 23.5 objects per frame |
| `b23adb0d-8a7aaced_bdd-yolo26s.mp4` / `.jsonl` | City, night, t = 5 to 15 s, 301 frames (9.99 s). About 30.2 objects per frame |
| `videos_summary.json`, `eval_log.txt` (inference pass), `latency_log.txt`, `eval_log_final.txt` (scoring and bootstrap) | Run logs |

## Reproduce

```powershell
cd perception_engine
$env:YOLO_AUTOINSTALL = "False"
# 1) predictions + metrics + bootstrap (GPU for inference, then about 6 min CPU bootstrap with 4 workers)
.venv\Scripts\python.exe -m perception.detection.eval_bdd
#    re-score saved predictions only (CPU):  ... eval_bdd --reuse-preds
#    one run, no bootstrap:                  ... eval_bdd --runs bdd-yolo26s@960 --bootstrap 0
# 2) interleaved latency benchmark -> latency.json (re-run eval_bdd --reuse-preds afterwards to merge it)
.venv\Scripts\python.exe -m perception.detection.bench_latency --rounds 3 --frames 100
# 3) figures and demo clips
.venv\Scripts\python.exe -m perception.detection.plots
.venv\Scripts\python.exe -m perception.detection.make_videos
```

The numbers above come from this order: eval_bdd (inference), bench_latency, then eval_bdd `--reuse-preds` (final
metrics plus bootstrap). Every GPU process caps itself at 1.5 GB (`torch.cuda.set_per_process_memory_fraction`), sets
`cudnn.benchmark=False`, runs batch 1 and retries once after 60 s on CUDA OOM (eval only). The weights must already be on
disk (`allow_download=False` in the scripts); see `MODELS.md`.

## Backends tried

| Backend / model | Status | Notes |
|---|---|---|
| Ultralytics 8.4.163, BDD YOLO26s/n (`dronefreak/bdd100k-yolo26{s,n}`) | works | `quantize=16` (FP16; `half` is deprecated in 8.4.163). Letterbox rect 960x544 / 640x384 |
| Ultralytics, COCO `yolo26s.pt` | works | Ego-hood false positives (see above) |
| rfdetr 1.11.0, BDD RF-DETR-N (`dronefreak/bdd100k-rfdetr-nano`) | works | Loaded with `RFDETRNano(pretrain_weights=..., resolution=576, num_classes=10)` and traced FP16 via `model.inference(dtype=float16)`. Its logs say DINOv2 hub weights are not loaded; that is expected, since the checkpoint carries all weights. Nothing was fetched from the hub |
| rfdetr, COCO RF-DETR-N/S | works | Returns sparse COCO category ids; mapped via `class_name` |
| YOLO26 NMS-free head (`nms=False`) | works | Listed as `bdd-yolo26s@960:e2e` |
| ONNX Runtime / TensorRT | not tried in this block | The ONNX exports left over from the verification run were random-weight latency probes, useless for accuracy |

## Licenses

Details and hashes are in `MODELS.md`. This is an engineering summary, not legal advice.

- **Ultralytics YOLO26 (runtime and all `*-yolo26*` weights): AGPL-3.0.** Ultralytics requires either open-sourcing the
  whole project under AGPL-3.0 or buying an Enterprise License; per the verified runtime_licensing track, its stated
  position covers internal R&D use too.
  Distributing or network-serving the demo triggers AGPL source-sharing obligations.
- **RF-DETR (rfdetr package, N/S weights): Apache-2.0.** This is the less restrictive code path.
- **BDD100K data-license inheritance.** `bdd-yolo26s`, `bdd-yolo26n` and `bdd-rfdetr-nano` were trained on BDD100K,
  whose terms allow educational, research and not-for-profit use only. Commercial use is limited to BDD/BAIR Commons
  members or requires a UC Berkeley OTL license. **Treat these checkpoints as research and demo only, including
  the Apache-tagged RF-DETR one.** The local eval data is under the same license and stays out of any public repo.
- **Objects365.** Every YOLO26 base checkpoint and RF-DETR was pretrained on Objects365, which is "academic purpose only".
  So even the COCO baselines are not cleanly commercial without legal review.
- **For a commercial path**, the verified research track recommends Apache-2.0 COCO-only bases (D-FINE `*-coco`,
  RT-DETRv2-R18) fine-tuned on commercially licensed driving data. None of those were tested in this block.

## Review fixes

An independent reviewer checked this block on 2026-09-25.

**Reproduction (measured here by the reviewer).**

- The full `eval_bdd` run was repeated with 10 runs of inference plus the B=200 bootstrap, taking 9 min 16 s.
- Raw predictions came out bit-identical to the builder's `preds/*.json` for all 10 runs.
- Every non-latency number in `metrics.json` matches to within 1e-4, including the per-class, time-of-day, operating
  point, sweep, bootstrap CI and paired-difference values. The accuracy numbers above therefore stand unchanged.
- Latency did **not** reproduce, because GPU contention was heavier during the review (other jobs at 88 to 94% GPU
  utilisation). `eval_bdd` pass p50 values were 12 to 25 ms. A 3-round check gave `bdd-yolo26s@960` 30.8 ms,
  `nms=False` 29.0 ms and `bdd-rfdetr-nano` 25.2 ms p50, with round spreads of 19 to 56 ms. All timings in this
  README are contention-bound and provisional.

**Independent scorer: `review_check.py`.**

- It shares no code with `eval_bdd.py`. It rebuilds GT from the raw 2018 `box2d` labels in `eval_subset_det.json`,
  maps class names by hand and runs pycocotools directly.
- It gives the same numbers: default all10 38.50 / 62.95, and so on for every run
  (`outputs/detection/review_independent_scores.json`).
- Without the +1 on either side, which is closer to how the model card's Ultralytics val scores, the default is
  **37.69** all10 mAP50-95. That is the fairer number to set beside the card's 33.86.

**Bug checks that passed:**

- `eval_subset_coco.json` matches the 2018 labels label-by-label: same category, bbox within 5e-4 px, area = w*h,
  no crowd or ignore flags.
- The scalabel +1 is applied to both sides, as in the official bdd100k eval.
- Category ids are looked up by name, and person, motor and bike map to pedestrian, motorcycle and bicycle.
- There is no train/val leak. Both DetectionBench model cards use the official 10k val as `test` and hold out a seeded
  15% of train for checkpoint selection. All 250 images are in `bdd100k_labels_images_val.json`.
- rfdetr is given RGB, as its `predict` docstring requires. Ultralytics is given BGR.
- With `nms=None`, Ultralytics 8.4.163 builds the one-to-many head plus NMS (`end2end = args.nms is False`), so the
  `:e2e` run really is a different head.
- maxDets 100 is never binding: at most 42 GT boxes per image and class.
- The GT and prediction overlays in `examples_day_night.png` line up, so there is no scaling error.
- The MODELS.md sha256 prefixes and byte counts match the files on disk. The license rows agree with the verified
  research notes (tracks `detection` and `runtime_licensing`).

**Fixes and corrections:**

1. **API bug (fixed in `detector.py`).**
   - Before: `Detector(backend="rfdetr")` silently loaded the Ultralytics `bdd-yolo26s` default, because the preset
     overrode `backend`.
   - Now: `backend` defaults to `None`, and a backend given alone selects its default preset
     (`DEFAULT_PRESET_BY_BACKEND`: rfdetr to `bdd-rfdetr-nano`). An explicit backend that conflicts with the preset,
     or an unknown backend, raises `ValueError`.
   - The eval, benchmark and video scripts never pass `backend`, so no number changes.
2. **"diff vs default" column (clarification).** The column is the *bootstrap mean* of the paired differences, and the
   upward bootstrap skew (the `train` class drops out of some resamples) inflates it. The observed point differences
   are:

   | Run | Observed difference |
   |---|---|
   | `bdd-rfdetr-nano@576` | **+0.17** (not +0.6) |
   | `bdd-yolo26s@960:e2e` | +0.64 |
   | `bdd-yolo26s@640` | -4.26 |
   | `bdd-yolo26n@960` | -5.14 |
   | `bdd-yolo26n@640` | -11.75 |
   | `coco-yolo26s@640` (map8) | -14.94 |
   | `coco-yolo26s@960` (map8) | -13.08 |
   | `coco-rfdetr-nano@384` (map8) | -14.70 |
   | `coco-rfdetr-small@512` (map8) | -10.29 |

   The CIs and the conclusions are unchanged. RF-DETR-N BDD and the default are a tie.
3. **Ego-hood false positives are not limited to COCO YOLO26s (correction).** The reviewer counted `car` boxes at
   conf ≥ 0.25 whose bottom edge is within 5 px of the frame bottom, with y1 ≥ 450 and width ≥ 640 px. No GT box in
   the subset fits this rule. The counts are:
   - `coco-rfdetr-small`: 105 of 250 images
   - `coco-rfdetr-nano`: 88 of 250
   - `coco-yolo26s`: 32 to 35 of 250
   - every BDD-trained run: 0 of 250

   All COCO baselines, RF-DETR included, need a hood mask before plan section 18.
4. **Cold start (note).** The first `detect()` without `warmup()` took about 4.3 s (CUDA and cuDNN init). The
   integrator should call `det.warmup()` before the live loop.

Reviewer command (CPU only, about 2 min): `.venv\Scripts\python.exe -m perception.detection.review_check`

## Caveats

- This is a driving-assistance visualisation prototype (plan section 38). Detections feed displays and alerts only, never
  vehicle control. The accuracy figures above are for 250 images and are no safety claim.
- Only 250 images were used, and the labels are the 2018 BDD release (the same one DetectionBench trained on), not
  det_20. Ignore regions and crowd flags do not exist in this release.
- RF-DETR resizes 16:9 frames to a square without letterboxing, as its native pipeline does. YOLO letterboxes to a rect.
- The BDD checkpoints were fine-tuned on the BDD100K train split. The DetectionBench holdout came from train, so this val
  subset is unseen by them, but the domain (BDD dashcams) is the same. Expect lower numbers on other cameras, such as
  future glasses input (plan section 34).
- Latency is provisional (shared GPU). VRAM is `torch.cuda.max_memory_allocated` and does not include the per-process
  CUDA context (not measured here; typically a few hundred MB).
- Ultralytics prints `half is deprecated` if `half=True` is passed to it directly. `Detector` uses `quantize=16`.
- The HF cache emits a "symlinks not supported" warning on this Windows setup. It is harmless; files are copied instead.
