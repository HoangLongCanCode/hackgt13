# Tracking block (plan section 8)

This is multi-object tracking for the Knuckle Sandwich Robotics Inc. (KSR) AI Spatial Driving Copilot prototype. It takes per-frame detections from section 7 and returns stable track IDs plus deterministic motion cues:

- scale rate
- time-to-collision (TTC)
- an "approaching" flag with hysteresis
- lateral velocity

These feed the Driving Context Engine (sections 15/16) and the following-distance display (section 18). Every output is a **measurement**, not a safety claim (section 38). Nothing here controls a vehicle, and nothing depends on an LLM.

The **default** is `Tracker(backend="ul_botsort")`: Ultralytics BoT-SORT with GMC `sparseOptFlow`, harmonised thresholds, a 2.0 s lost-track buffer and a 0.5 s class vote. The **license-safe alternative** (Apache-2.0) is `backend="rf_mcbyte"` or `"rf_botsort"`, from Roboflow `trackers`.

## 1. API

```python
from perception.tracking import Tracker, UltralyticsTrackPipeline, MotionConfig

trk = Tracker(backend="ul_botsort", device="cuda", frame_rate=30.0)   # frame_rate=5 for BDD MOT frames
for frame in video:                                                    # perception.common.video.VideoFileInput
    dets = detector(frame.image)                                       # list[schemas.Detection] from section 7
    tracks = trk.update(dets, frame.image, frame.pts_s,                # -> list[schemas.TrackState]
                        ego_polygon=road.egoPathPolygon if road else None)   # optional, from section 13
    # dets[i].id is now the track id (None when not tracked this frame)
    extra = trk.last_meta            # {id: {hits, confidence, scaleRateLo/Hi, closing, inCorridor, lateralWidthsPerS, ...}}
trk.reset()                          # between unrelated videos
```

`Tracker(backend, device="cuda", frame_rate=30, track_buffer_s=2.0, classes=MOT_CLASSES, harmonised=True, motion=None, class_vote_s=0.5, **opts)`:

- **`backend`**: one of `Tracker.BACKENDS`:
  - Ultralytics 8.4.163, AGPL-3.0: `ul_bytetrack`, `ul_botsort`, `ul_botsort_nogmc`, `ul_ocsort`, `ul_deepocsort`, `ul_fasttrack`, `ul_tracktrack`.
  - Roboflow trackers 2.6.1, Apache-2.0: `rf_sort`, `rf_bytetrack`, `rf_botsort` (CMC on), `rf_botsort_nocmc`, `rf_cbiou`, `rf_ocsort`, `rf_mcbyte` (no SAM/Cutie masks).
- **`classes`**: which classes get tracked. The default is the 8 BDD MOT classes. `"all"` also tracks traffic lights and signs. Everything else passes through with `id=None`.
- **`harmonised`**: applies the confidence gates from section 4.3, the same for every backend. `False` keeps each library's defaults. Either way, the frame rate and buffer are always converted to seconds.
- **`**opts`**: raw overrides. Use Ultralytics yaml keys (for example `track_high_thresh`, `with_reid`, `gmc_method`, plus `gmc_downscale`) or Roboflow kwargs (for example `lost_track_buffer`, `cmc_downscale`).
- **`motion`**: a `MotionConfig` or a dict of overrides (see section 2).
- **`pts_s`**: time in seconds. Non-increasing values are nudged forward by 1/4 frame, because the BDD `.mov` pts are not monotonic in the first frames (see caveats). `None` means frame count / `frame_rate`.
- **`needs_frame`**: `False` for trackers that ignore the image (SORT, ByteTrack, OC-SORT, C-BIoU, no-GMC variants). For those you may pass `frame_bgr=None`.

**TrackState output** (shared schema `perception/common/schemas.py`):

| Field | Meaning |
|---|---|
| `id` | Stable track id (1, 2, ...) per `Tracker` instance |
| `cls` | Class after a confidence-weighted vote over the last `class_vote_s` seconds |
| `bbox` | The matched detection box (x1, y1, x2, y2) |
| `age_frames` | Frames since the track was born |
| `scale_rate` | d ln(h)/dt [1/s] |
| `ttc_s` | TTC in seconds (only when 0 < TTC <= 30 s) |
| `approaching` | `None` until the first estimate |
| `lateral_px_s` | Lateral velocity [px/s] |

**Convenience path.** `UltralyticsTrackPipeline(weights=None, tracker="botsort", frame_rate=30, ...)` runs `.step(frame_bgr, pts_s) -> (detections, tracks)`. It runs the default detector (`dronefreak/bdd100k-yolo26s`, imgsz 960, fp16) through `model.track(persist=True)`. This is the only path where `with_reid=True, model="auto"` can reuse the detector's feature maps. Standalone `Tracker` has no features, so it refuses that combination with a clear error. The pipeline returns only the tracked subset of detections.

`detector.py` holds `YoloDetector`, a minimal stand-in so this block can be tested on its own. It does not import the section 7 detection block. The integrator should feed that block's detections instead.

## 2. Motion cues: formulas

All motion math lives in `motion.py`: a deterministic `MotionEstimator` that runs on the tracker output.

| Output | Formula |
|---|---|
| Scale rate `scale_rate` | s = d ln h / dt = -(dZ/dt)/Z, the Theil-Sen slope (median of pairwise slopes; Theil 1950, Sen 1968) of ln h over the last **1.0 s** of samples. A Sen confidence interval [lo, hi] (90 %) goes in `last_meta`. `scale_mode` can switch to width or sqrt(wh) |
| TTC `ttc_s` | TTC = 1/s for s > 0, which is Lee's tau (1976). It is the monocular FCW construction of Dagan, Mano, Stein and Shashua (IEEE IV 2004) and of Stein, Mano and Shashua (IEEE IV 2003). **Lag-compensated:** a window fit estimates 1/(T - t_centre), so TTC_now = 1/s - (t_now - t_centre) |
| Closing (`last_meta["closing"]`) | Hysteresis. ON when s >= 0.10 /s (TTC <= 10 s) **and** lo > 0 for 0.3 s. OFF when s <= 0.04 /s for 0.5 s, or when there is no estimate for 1 s |
| `approaching` | closing **and** inside the ego corridor. The corridor test puts a box-bottom point at 1/4, 1/2 or 3/4 of the width inside `ego_polygon` (section 13 `egoPathPolygon`), with a 0.3 s exit delay. Without a polygon it uses a static trapezoid (`corridor_norm`, bottom 20-80 % of width, apex at 40 % height). `approach_scope="any"` removes the gate |
| `lateral_px_s` | Theil-Sen slope of the box centre x [px/s]. `last_meta["lateralWidthsPerS"]` = d/dt[(cx - W/2)/w] = lateral speed in **object widths per second**. It needs no range: about 1.8 m per width for a car. Both values include ego yaw |
| Sample rejection | A sample is dropped when it is truncated (touches the frame border), smaller than 12 px tall, or occluded (> 30 % of its area covered by a nearer box, meaning one with a larger y2). A gap of more than 0.6 s (re-identification) clears the history |

The on/off thresholds are **placeholders to tune**, not validated safety values.

Scale-change TTC is the time until the object reaches the camera plane. A car in an adjacent lane that the ego vehicle is passing has a short TTC but will not be hit, which is why `approaching` is gated by the ego corridor. The static corridor is only a heuristic. The integrator should pass the section 10 / section 13 ego-lane polygon.

## 3. Evaluation setup (measured here)

**Data.** BDD100K MOT 2020 val (`box_track_20`) at 5 fps: 7 sequences, 1,418 frames, 20,585 GT boxes in the 8 classes and 1,320 ignore regions. These are all 7 primary clips (highway day, city intersection, residential, night city, snow, dusk and heavy traffic, rainy night highway). The MOT JPEGs for 6 of them were fetched for this block from HF `vanthanh/BDD100kMOT` (`images20-track-val-1.zip`, 164.7 MB, CRC-checked; see `data/bdd100k/MANIFEST.md` section 7). No GT evaluation used frames decoded from video.

**Detections.** Every tracker sees **identical detections**. YOLO26s-BDD (`dronefreak/bdd100k-yolo26s`, imgsz 960, fp16, conf >= 0.05) runs once and is cached (`outputs/tracking/cache/`). Detector latency is 18.4 ms p50 / 27.7 ms p90 on the RTX 5060 Laptop, with a peak VRAM of 89 MB. That is provisional: the GPU was shared.

**Metrics** (`mot_eval.py`), a simplified port of scalabel's `acc_single_video_mot`:

- per class (8 classes), IoU >= 0.5, same-class matching only;
- scalabel's box convention: every box (GT, ignore and prediction) gets width x2 - x1 + 1 and height y2 - y1 + 1 (`box2d_to_bbox`) before IoU / IoA. This is `BddMotEvaluator(plus_one=True)`, the default since the review (see section 8);
- class-agnostic ignore regions (GT `crowd=True` plus `other person`, `other vehicle`, `trailer`). A prediction that is an FP after Hungarian matching and has IoA > 0.5 with an ignore box is dropped;
- motmetrics 1.4.0 for MOTA, IDF1 and ID switches, with a local IoU matrix, because motmetrics' `iou_matrix` uses `np.asfarray`, which NumPy 2 removed;
- HOTA from `trackers.eval` (a TrackEval port);
- mMOTA / mIDF1 / mHOTA average over the 7 classes that have GT (`train` has none);
- "overall" merges every class accumulator.

**Differences from the official toolkit.** The official `compute_average` takes mMOTA / mIDF1 over all 8 leaf classes with NaN/inf set to 0. On this subset that would count `train` as 0, so it is reported separately as `mMOTA_8cls_nan0` / `mIDF1_8cls_nan0` in `metrics.json`. HOTA is not part of the official `box_track` output. Everything else follows scalabel's `acc_single_video_mot`: class-agnostic ignore list, IoF > 0.5, IoU >= 0.5, per-class accumulators merged per class, super-categories and OVERALL by merging. Distractor parent-class remapping has no effect, because the ignore list is class-agnostic. The official toolkit itself was not run (see section 7), so the numbers should be very close to `bdd100k.eval` but are **not verified identical**.

**Caution on class averages.** On 7 sequences, the class averages are dominated by tiny classes: bicycle 14 boxes, motorcycle 56, rider 59. **Overall MOTA / IDF1 and car / pedestrian are the more meaningful numbers.** They are also not comparable to published full-val (200-sequence) anchors. For example, ByteTrack with a BDD-trained YOLOX-X at 1440 reports val mMOTA 45.5 / mIDF1 54.8 / MOTA 69.1 / IDF1 70.4. Most of that gap is the detector: at conf >= 0.25 this YOLO26s finds only 33 % of the bus boxes and 41 % of the truck boxes with the right class. It finds 61-62 % of them with any class, so it often confuses bus, truck and car.

## 4. Results (measured here)

### 4.1 Backends at their Tracker defaults

These runs use the harmonised thresholds, a 2.0 s buffer and a 0.5 s class vote:

| Backend | License | MOTA | IDF1 | HOTA | ID sw. | mMOTA | mIDF1 | update p50 ms (CPU) |
|---|---|---|---|---|---|---|---|---|
| **ul_botsort (default)** | AGPL-3.0 | 55.7 | 65.4 | 56.0 | **487** | 23.4 | 32.9 | 15.2 |
| ul_tracktrack | AGPL-3.0 | 57.4 | 65.0 | 57.1 | 1377 | 22.1 | 35.9 | 15.9 |
| rf_mcbyte (no masks) | Apache-2.0 | **58.1** | **67.0** | **57.4** | 808 | 21.5 | 34.6 | 19.0 |
| rf_botsort (CMC) | Apache-2.0 | 56.4 | 64.7 | 55.9 | 814 | 23.9 | 32.7 | 22.3 |
| rf_bytetrack | Apache-2.0 | 50.5 | 60.3 | 52.4 | 1159 | 11.5 | 32.3 | 1.8 |

The CPU timings are provisional: on this shared machine they vary by about ±30 % between identical runs. The accuracy numbers reproduced exactly on a re-run.

`ul_botsort` per class (7 sequences):

| Class | GT boxes | MOTA | IDF1 | HOTA | ID sw. | precision | recall |
|---|---|---|---|---|---|---|---|
| pedestrian | 1250 | 38.2 | 51.2 | 40.3 | 18 | 98.6 | 40.2 |
| rider | 59 | 5.1 | 9.7 | 6.1 | 0 | 100.0 | 5.1 |
| car | 16915 | 61.5 | 69.0 | 59.0 | 462 | 95.8 | 67.1 |
| truck | 1143 | 23.6 | 40.2 | 37.7 | 7 | 77.8 | 33.9 |
| bus | 1148 | 26.7 | 43.5 | 40.1 | 0 | 95.0 | 28.2 |
| train | 0 | n/a | n/a | n/a | n/a | n/a | n/a |
| motorcycle | 56 | 8.9 | 16.4 | 19.6 | 0 | 100.0 | 8.9 |
| bicycle | 14 | 0.0 | 0.0 | 0.0 | 0 | n/a | 0.0 |
| HUMAN / VEHICLE / BIKE (super-categories) | 1309 / 19206 / 70 | 36.7 / 57.2 / 7.1 | 49.8 / 66.4 / 13.3 | 39.5 / 57.0 / 17.6 | 18 / 469 / 0 | | |

**Why `ul_botsort` is the default.** It has about 40 % fewer ID switches than every other top-group tracker, and its MOTA and IDF1 are within 2.4 and 1.6 points of the best. Every ID switch resets a track's TTC history and can flicker an AR marker. It is also the stack the plan names, and it has the native ReID path.

**Where the others win.** `rf_mcbyte` has the best MOTA and IDF1 and is Apache-2.0. Use it (or `rf_botsort`) if AGPL is not acceptable. `ul_tracktrack` has the best class-averaged scores but 2.8 times as many ID switches.

**How robust these rankings are.** The top four are within about 2.5 MOTA points on only 7 sequences. Treat the ranking as indicative.

### 4.2 Full grid on identical detections

The grid uses a 1.0 s buffer and a 3.0 s class vote (15 observations at 5 fps). Rows are sorted by overall IDF1. Chart: `outputs/tracking/tracker_comparison.png`.

| Tracker : thresholds | mMOTA | mIDF1 | mHOTA | MOTA | IDF1 | HOTA | ID sw. | FP | FN | update p50 ms |
|---|---|---|---|---|---|---|---|---|---|---|
| rf_mcbyte:harmonised | 20.0 | 33.8 | 29.6 | 57.5 | 65.2 | 56.2 | 757 | 912 | 7084 | 23.0 |
| ul_deepocsort:default | 20.8 | 32.1 | 28.8 | 57.5 | 65.2 | 56.6 | 675 | 1153 | 6923 | 14.2 |
| ul_botsort:default | 18.2 | 33.3 | 29.6 | 57.7 | 64.8 | 56.4 | 695 | 1560 | 6456 | 15.0 |
| ul_botsort:harmonised | 21.9 | 31.8 | 28.5 | 55.1 | 63.7 | 54.9 | 443 | 648 | 8156 | 13.4 |
| ul_tracktrack:harmonised | 21.8 | 35.0 | 32.0 | 57.3 | 63.6 | 56.3 | 1363 | 949 | 6480 | 12.1 |
| rf_botsort:harmonised | 23.3 | 32.2 | 28.8 | 56.0 | 63.2 | 54.9 | 742 | 781 | 7538 | 17.9 |
| ul_deepocsort:harmonised | 21.0 | 31.2 | 28.0 | 54.1 | 63.2 | 54.6 | 448 | 483 | 8514 | 14.0 |
| rf_bytetrack:harmonised | 9.9 | 30.6 | 27.4 | 49.7 | 60.7 | 52.5 | 1077 | 2371 | 6903 | 2.1 |
| rf_cbiou:harmonised | 20.5 | 28.6 | 25.8 | 52.9 | 58.4 | 51.3 | 1332 | 945 | 7423 | 2.0 |
| rf_ocsort:harmonised | 19.9 | 28.6 | 26.6 | 51.6 | 57.6 | 50.9 | 1401 | 862 | 7705 | 1.9 |
| ul_ocsort:default | 18.1 | 27.2 | 25.2 | 49.9 | 56.0 | 50.0 | 1057 | 1068 | 8198 | 4.6 |
| ul_ocsort:harmonised | 17.2 | 26.2 | 24.5 | 47.6 | 55.5 | 49.3 | 795 | 461 | 9526 | 3.2 |
| ul_botsort_nogmc:default | 18.0 | 25.1 | 23.7 | 49.0 | 54.2 | 48.8 | 1170 | 1192 | 8127 | 3.3 |
| ul_botsort_nogmc:harmonised | 17.5 | 24.9 | 23.0 | 47.1 | 53.8 | 48.0 | 936 | 563 | 9394 | 2.5 |
| rf_botsort_nocmc:harmonised | 19.1 | 27.4 | 25.3 | 48.7 | 53.6 | 48.1 | 1583 | 680 | 8290 | 1.8 |
| ul_bytetrack:default | 17.9 | 24.9 | 23.4 | 48.7 | 53.3 | 48.1 | 1157 | 1181 | 8220 | 3.2 |
| ul_bytetrack:harmonised | 17.3 | 24.5 | 22.7 | 46.6 | 52.8 | 47.2 | 951 | 563 | 9470 | 3.0 |
| ul_fasttrack:default | 17.6 | 24.2 | 22.4 | 47.7 | 52.2 | 47.0 | 1391 | 1398 | 7971 | 4.2 |
| rf_mcbyte:default | 15.3 | 23.3 | 21.1 | 39.5 | 52.0 | 45.7 | 206 | 171 | 12072 | 19.8 |
| rf_sort:harmonised | 7.7 | 25.2 | 23.9 | 39.7 | 51.8 | 47.8 | 1907 | 3988 | 6527 | 2.3 |
| rf_botsort:default | 15.2 | 22.9 | 21.3 | 39.0 | 51.0 | 45.1 | 217 | 128 | 12211 | 17.8 |
| rf_bytetrack:default | 15.1 | 22.8 | 20.6 | 38.1 | 50.8 | 44.8 | 612 | 707 | 11433 | 1.9 |
| rf_sort:default | 7.4 | 30.9 | 26.5 | 33.1 | 50.7 | 46.1 | 1245 | 5483 | 7043 | 2.5 |
| ul_fasttrack:harmonised | 17.0 | 23.2 | 21.6 | 45.9 | 50.3 | 45.7 | 1169 | 702 | 9259 | 3.6 |
| ul_tracktrack:default | 14.5 | 22.3 | 20.1 | 35.9 | 49.9 | 43.6 | 70 | 71 | 13044 | 10.3 |
| rf_ocsort:default | 12.8 | 19.2 | 18.0 | 34.4 | 46.2 | 40.9 | 192 | 82 | 13240 | 1.3 |
| rf_cbiou:default | 14.0 | 20.8 | 19.3 | 37.4 | 45.9 | 42.2 | 631 | 228 | 12031 | 1.5 |
| rf_botsort_nocmc:default | 13.5 | 19.9 | 18.8 | 35.5 | 44.8 | 41.3 | 515 | 138 | 12628 | 1.1 |

**What the grid shows:**

1. **Camera-motion compensation is the biggest single factor at 5 fps.** BoT-SORT with GMC reaches MOTA 57.7 and IDF1 64.8, against 49.0 and 54.2 without it. Roboflow BoT-SORT gains +7.3 MOTA and +9.6 IDF1 from CMC. The GMC-free trackers (ByteTrack, OC-SORT, C-BIoU, FastTrack) cluster at IDF1 50-61.
2. **Library defaults do not transfer.** The Roboflow and TrackTrack defaults (activation 0.7, high 0.6, minimum 2-3 frames) were tuned for the MOT17 score scale. Here they lose 11-13k boxes as FN, because only 24 % of the YOLO26s-BDD MOT-class detections at >= 0.05 score 0.6 or more (18 % score 0.7 or more).
3. **Deep OC-SORT runs without ReID when standalone.** No features are available, so it is effectively OC-SORT with GMC.
4. **TrackTrack's TAI recovery is off when standalone.** The recovered loose-NMS boxes need the predictor hook, so standalone TrackTrack runs without them. In the native pipeline they are used, and the scores are essentially the same (57.4 / 63.6).

### 4.3 How the harmonised thresholds were chosen

The harmonised gates are high / new = 0.4 / 0.5, low 0.1, and minimum consecutive frames 1 for Roboflow. They came from a 5-point sweep on the **same 7 sequences**, so they are optimistic. The sweep covered ul_bytetrack, ul_botsort and rf_bytetrack with (high, new) in {(0.25, 0.25), (0.3, 0.4), (0.4, 0.5), (0.5, 0.6), (0.6, 0.7)}; the results are in `metrics.json:threshold_sweep`.

For ul_botsort, moving from 0.25 / 0.25 to 0.4 / 0.5 costs 2.6 MOTA and 1.1 IDF1. It cuts ID switches from 695 to 443 and false positives from 1560 to 648. Fewer spurious tracks and fewer ID switches were preferred for a HUD.

### 4.4 Ablations on ul_botsort (harmonised)

| Config | mMOTA | mIDF1 | MOTA | IDF1 | ID sw. | ms p50 |
|---|---|---|---|---|---|---|
| track_buffer_s=0.6 (vote 3.0 s) | 21.9 | 31.8 | 55.1 | 63.2 | 429 | 11.0 |
| track_buffer_s=1.0 (vote 3.0 s) | 21.9 | 31.8 | 55.1 | 63.7 | 443 | 11.9 |
| track_buffer_s=2.0 (vote 3.0 s) | 22.0 | 32.2 | 55.2 | 65.1 | 482 | 11.7 |
| track_buffer_s=3.0 (vote 3.0 s) | 22.0 | 32.6 | 55.2 | 65.3 | 484 | 13.5 |
| class_vote_s=0.0 (buffer 2.0 s) | 23.3 | 32.8 | 55.6 | 65.4 | 488 | 14.0 |
| **class_vote_s=0.5 (buffer 2.0 s) = default** | 23.4 | 32.9 | 55.7 | 65.4 | 487 | 14.7 |
| class_vote_s=1.0 (buffer 2.0 s) | 22.3 | 32.5 | 55.5 | 65.3 | 482 | 13.7 |
| gmc_downscale=4 (defaults otherwise) | 22.7 | 32.0 | 54.4 | 64.4 | 502 | 10.5 |

A 2 s buffer adds 1.4 IDF1. A long class vote (3 s) slightly hurts the per-class scores, so the default is 0.5 s: 15 frames at 30 fps, enough to stop car/truck icon flicker. GMC at downscale 4 saves about 4 ms of CPU for about 1 IDF1.

### 4.5 Native `model.track(persist=True)` path and ReID

These runs share the grid settings (harmonised thresholds, 1.0 s buffer, 3.0 s vote):

| Config | mMOTA | mIDF1 | MOTA | IDF1 | ID sw. | detector + tracker ms p50 |
|---|---|---|---|---|---|---|
| native_botsort | 21.9 | 31.9 | 55.1 | 63.8 | 440 | 53.8 |
| native_botsort + ReID `model: auto` | 22.2 | 32.2 | 55.1 | 63.9 | 488 | 44.9 |
| native_tracktrack (with TAI) | 21.8 | 35.0 | 57.4 | 63.6 | 1363 | 40.5 |
| native_tracktrack + ReID `auto` | 22.2 | 33.5 | 55.1 | 58.7 | 1755 | 48.3 |
| native_bytetrack | 16.5 | 24.0 | 44.7 | 51.9 | 920 | 30.7 |

- **The two paths agree.** Native BoT-SORT matches the standalone `ul_botsort:harmonised` (55.1 / 63.8 vs 55.1 / 63.7), which validates the standalone adapter.
- **ReID "auto" really used native features.** The encoder was `_auto_encoder`, and the detector's Detect head is `end2end=False`, so there was no `yolo26n-cls.pt` fallback.
- **ReID does not help at 5 fps.** It adds 0.1 IDF1 and 48 more ID switches for BoT-SORT, and it hurts TrackTrack (-4.9 IDF1). With these settings, ReID is not worth enabling.
- **Timing is noisy.** The step times include detector + NMS + tracker and swing by about 10 ms between runs on the shared GPU/CPU.

### 4.6 Motion-cue checks

**Synthetic check** (`test_motion.py`, pinhole f = 1000 px, car 1.8 x 1.5 m, 1 px edge noise; all 6 checks pass):

- At 30 fps: median TTC relative error 3.6 % (p90 12 %). With 2 px noise, 7.1 %. At 5 fps, 7.6 %.
- Lag compensation cuts the close-range (15 m at 5 m/s) median TTC error from 0.46 s to 0.03 s.
- A constant gap and a receding car are never flagged. A car parked on the shoulder is "closing" 83 % of the time but never "approaching" (corridor gate).
- Lateral speed in widths/s x 1.8 m has a median error of 0.02 m/s at 1 m/s.

**BDD 5 fps consistency** (there is no TTC ground truth in BDD100K). The same estimator runs on the **GT boxes** and on the tracker's boxes, and the two are compared on 6,704 matched (track, GT) frame pairs:

| Measure | Result |
|---|---|
| Scale-rate difference | median 0.042 /s, p90 0.143 /s, bias 0.000 |
| TTC relative difference (GT TTC <= 10 s) | median 17 % (n = 3,317) |
| `approaching` (corridor gate) vs GT-box-derived flag | precision 0.85, recall 0.81 (GT positive rate 13 %) |
| Closing flag without the gate | precision 0.83, recall 0.83 |

This measures how much detector and tracker noise the motion layer adds. It is not the accuracy of the true TTC.

### 4.7 30 fps demo

This is `ul_botsort` on 15 s of each clip at 30 fps. Timings are provisional: the GPU and CPU were shared with other agents.

| Clip | Frames | Track IDs | Detector p50 ms | tracker.update p50 / p90 ms | Track-frames approaching / closing (any position) |
|---|---|---|---|---|---|
| `b1f4491b-cf446195` highway day | 452 | 171 | 21.5 | 10.7 / 14.1 | 246 / 2011 |
| `b1d968b9-ce42734f` dusk, heavy traffic | 452 | 137 | 20.9 | 10.8 / 14.5 | 925 / 2775 |

Output files, in `outputs/tracking/`:

- `demo_<clip>.mp4`: 960x540, **first 10 s**, because of the 10 s clip cap. It shows IDs, class, TTC <= 10 s, APPROACHING (red) or closing, and the static corridor outline.
- `demo_<clip>_tracks.jsonl`: the full 15 s, one `FrameResult.to_dict()` per frame, plus `trackMeta` and `trackerTimeSeconds`.
- `demo_<clip>_ttc.png`: scale-rate and TTC timelines of the 6 longest tracks.

Peak VRAM for detector + tracker is 81-99 MB.

## 5. Reproduce

Run from `perception_engine/`:

```powershell
# everything: detection cache, grid, sweep, ablations, defaults, native/ReID, TTC check, report (~30 min)
.venv\Scripts\python.exe -m perception.tracking.eval_bdd
# fast re-check from the cached detections (~5 min): Tracker defaults + TTC consistency + report
.venv\Scripts\python.exe -m perception.tracking.eval_bdd --stages default ttc report
# single stages: dets | track | sweep | ablate | default | native | ttc | report ; --backends / --default to change
.venv\Scripts\python.exe -m perception.tracking.test_motion      # synthetic motion checks (exit code 0 = pass)
.venv\Scripts\python.exe -m perception.tracking.demo_video       # 30 fps demo -> mp4 + jsonl + png
```

Everything goes to `outputs/tracking/`:

- `metrics.json`: every number above, including per-class and per-config configs.
- `tracker_comparison.png`, `per_class_default.png`: charts.
- `cache/`: detections.
- `demo_*`: the 30 fps demo outputs.

The detection cache was regenerated once and came out bit-identical (7/7 sequences). The tracker association is deterministic.

## 6. Licenses

Details are in `MODELS.md`.

| Component | License |
|---|---|
| Ultralytics trackers and detector code | AGPL-3.0 |
| Roboflow `trackers` | Apache-2.0 |
| supervision | MIT |
| motmetrics | MIT |
| Detector weights `dronefreak/bdd100k-yolo26s` | AGPL-3.0, **trained on BDD100K** (non-commercial data terms; flagged for legal review) |
| All evaluation numbers | Derived from BDD100K val (research use) |

For a closed-source KSR product, use the `rf_*` backends plus a permissively licensed detector, or get an Ultralytics Enterprise License.

## 7. Caveats and known issues

- **Small, same-data evaluation.** Only 7 sequences were evaluated, not the 200 in the full val set. The harmonised thresholds and the 2.0 s / 0.5 s defaults were chosen on the same data. The class averages are noisy (see section 3).
- **Detector class confusion (bus / truck / car) dominates the per-class errors.** Trackers associate class-agnostically (Ultralytics, Roboflow), and the class vote cannot fix a class that is wrong on most frames.
- **TTC is relative (closing) TTC to the camera plane.** It is not a collision prediction, and `approaching` uses a static corridor unless the integrator supplies `ego_polygon`. The static trapezoid assumes a centred, level dashcam. On curves and at intersections it both over- and under-includes; oncoming traffic whose box bottom is inside the corridor can still be flagged.
- **Distance.** Range and metric relative velocity need section 9 depth. The recommended fusion (not implemented here) is v_rel = -Z * s (equivalently -Z / TTC), plus a 1-D Kalman filter on Z.
- **pts from `perception/common/video.py` are not monotonic.** On these `.mov` files the shared reader's `CAP_PROP_POS_MSEC` (read before `read()`) repeats values in the first frames: 7 of 450 steps on `b1f4491b`, including one backwards step. It also ends about 0.17 s short over 15 s. PyAV shows the same pts pattern, so the container is the cause. `Tracker` sanitises the timestamps. Roboflow trackers would otherwise **skip the whole update** on a backwards timestamp.
- **GMC / CMC runs on the CPU and is the main tracker latency.** Per 1280x720 frame, Ultralytics sparseOptFlow GMC costs about 10 ms (ul_botsort about 14-15 ms vs about 3 ms without it). Roboflow CMC costs about 17-20 ms (rf_botsort about 18-22 ms vs about 1-2 ms without it). `gmc_downscale=4` trades about 1 IDF1 for about 4 ms.
- **motmetrics `iou_matrix` breaks on NumPy 2 (`np.asfarray`).** It is not used here: the IoU is computed in `mot_eval.py`, and the MOTP column from motmetrics came out empty. HOTA's LocA (0.88) is reported instead.
- **Ultralytics Deep OC-SORT and TrackTrack lose features when standalone.** Deep OC-SORT loses its appearance features and TrackTrack loses its TAI recovery, because both need the predictor hooks.
