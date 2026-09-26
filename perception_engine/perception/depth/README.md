# Depth & distance block (plan section 9; feeds sections 11 and 18)

This block belongs to the AI Spatial Driving Copilot, the HackGT 13 prototype. It produces sentences like "the car ahead is 18 m away". For each tracked object it returns a metric distance (`DistanceEstimate`), a confidence and the per-method components. It can also return a dense metric depth map.

It is a **measurement for display**, following plan sections 18 and 38. It never claims a "safe distance", and it must not feed steering, braking or throttle.

## API

```python
from perception.depth import DistanceEstimator
from perception.common.video import VideoFileInput, primary_videos

est = DistanceEstimator(depth_backend="da3_metric_large",  # or 'da2_metric_outdoor_small', 'metric3d_vit_s_onnx', None
                        device="cuda",
                        focal_px=None,        # default: BDD 700 px at 1280 wide (scaled to the frame width)
                        cam_height_m=None,    # None: estimated from the depth model's road profile (DA3/Metric3D), else 1.3 m
                        depth_every=2)        # run the network every 2nd call; geometry-only in between
for frame in VideoFileInput(primary_videos()[1]):
    dets = my_tracker(frame.image)                       # list[perception.common.schemas.Detection] with .id set
    dists = est.estimate(frame.image, dets, pts_s=frame.pts_s)   # list[DistanceEstimate]
    # DistanceEstimate(vehicleId=17, distanceMeters=18.4, method='fused', confidence=0.86)
    details = est.last_details    # per object: components {depth_model, ground_plane, width_prior}, sigmas, lateral_m
    info = est.last_info          # horizon_y (+ source), camera_height_m, depth_scale, hood_row, road_profile, depth_ms
depth_m = est.depth_map(frame.image)   # HxW float32 metres, with the scale calibration applied
est.close()
```

### Constructor options

| Option | Values | Meaning |
|---|---|---|
| `depth_backend` | `"da3_metric_large"` (default), `"da2_metric_outdoor_small"`, `"metric3d_vit_s_onnx"`, `None` | `None` is geometry-only. It uses zero VRAM (about 0.7 ms per frame on the CPU for 10 boxes) and is the only license-clean mode |
| `backend` | a backend name string (same as `depth_backend`; `"none"` = geometry only) or a `DepthBackend` object to share one loaded network | Review fix: the string form used to raise `AttributeError` |
| `focal_px` | float or `None` | Focal length in pixels at the input frame width. The default is BDD 700 px at 1280 wide |
| `cx`, `cy` | float or `None` | Principal point. The default is the image centre |
| `cam_height_m` | float or `None` | Camera height above the road, in metres |
| `calibration` | `"auto"`, `"fixed"`, `"height_from_depth"`, `"scale_from_height"` | `height_from_depth` trusts the depth scale and estimates the camera height. `scale_from_height` trusts the camera height and rescales the depth map by `H_c / H_depth` (needed for DA2). `auto` picks the right one |
| `horizon` | `"auto"`, `"depth"`, `"virtual"`, `"fixed"`, or a float | `auto` fuses three horizon sources (road profile from the depth model, virtual horizon from object size priors, and a weak principal-row prior), smoothed over time with a 1-D Kalman filter. A per-call `horizon_y` always wins, for example `RoadGeometry.horizonY` from the lanes block |
| `road_mask` (per call) | mask or `None` | Drivable mask from the segmentation block. It improves the road-profile fit |
| `hood_row` | `"auto"`, float, `None` | Top row of the ego hood or dashboard. Boxes whose bottom reaches it get no flat-ground or height-prior estimate. `auto` detects it from a depth discontinuity. This works with DA3 but often fails with DA2 and Metric3D, which blend the hood into the road, so a per-video float is safer |
| `classes` | set of class names | Default: vehicles, pedestrian, rider. Add `"traffic light"` or `"traffic sign"` to get distances for section 11 and section 12 objects. They use the depth model plus a size prior, never flat ground |
| `temporal` | bool | `False` for independent images (KITTI eval). `True` (default) enables the per-track Kalman filter and the calibration history |
| `readout_percentile` | default 30 | Percentile used for the box depth readout |
| `methods` | subset of `("depth", "ground", "size")` | Which methods take part in the fusion |
| `max_distance_m` | default 150 | Upper limit on returned distances |
| `backend_opts` | dict | Passed to the backend, e.g. `{"process_res": 504}` for DA3 or `{"onnx_file": "model.onnx"}` for Metric3D |

Other entry points:

- `set_camera(focal, cx, cy, cam_height_m)` overrides the intrinsics, for example with KITTI P2.
- `reset()` clears the per-video state.

### Methods and fusion

1. **depth_model.** The 30th percentile of metric depth inside the box.
   - Vehicles: x 25-75 %, y 45-90 % of the box.
   - Persons: the torso region.
   - Sky pixels (DA3 sky head) and pixels covered by nearer, occluding boxes are excluded.
2. **ground_plane.** Flat road: `Z = H_c / tan(theta + atan((y_b - cy)/f))`, with pitch `theta` taken from the horizon row.
   - Gated off when the box bottom touches the frame border or the hood, or when y_b is within 2 px of the horizon.
   - Sigma is inflated 3x when a nearer box covers the bottom strip.
   - Sigma = `Z^2/(f H) * hypot(sigma_y, sigma_horizon)` combined with the camera-height term.
3. **width_prior.** Class size prior. Height `Z = f H_obj / h_px` is view-invariant. Width `Z = f W / w_px` is used only for near-rear or near-front views (aspect <= 1.35 x the rear aspect). Priors: car 1.8 x 1.5 m, bus 2.55 x 3.1, truck 2.4 x 3.0, pedestrian and rider height 1.7, traffic light long side 1.0 m, and others; see `geometry.SIZE_PRIORS`.
4. **fused.** Inverse-variance fusion in log space. Sigma is inflated by sqrt(chi^2/dof) when the components disagree. The result then goes through a per-track constant-velocity Kalman filter on [Z, dZ/dt] (accel sigma 3 m/s^2, soft outlier gating). Confidence = `clip(1 - (sigma/Z)/0.3, 0.05, 0.99)`, capped at 0.6 when only one method was available. The depth-model sigmas come from the KITTI results below: DA2 18 %, DA3 13 %, Metric3D 8 %.

**Self-calibration from the road profile.** On a flat road, `1/Z(y) = (y - y_h)/(f H_c)`. A RANSAC line fit of row-median inverse depth, restricted to the longest contiguous run of inlier rows in the lower-central band with boxes removed, gives two things:

- the horizon `y_h`, which is scale-free;
- `H_depth = H_c x (depth scale error)`.

That value becomes either the camera-height estimate or the depth scale correction. It is independent of the assumed focal length to first order.

For section 18, the displayed following distance should be `distanceMeters - d_hood`, where `d_hood` (camera to front bumper, about 1.5-2.5 m) is configurable in the UI layer. The UI should hide the cue when confidence is below about 0.3.

## Backends tried

All numbers in this section were **measured here**. Timings are **provisional**: the RTX 5060 Laptop was shared with up to 6 other agents (26-73 % busy). They cover a 1280x720 input at batch 1 and include the resize and the upsample.

| Backend | Status | Net input (BDD) | p50 ms (range over runs) | VRAM | KITTI dense AbsRel / delta1 (50 pairs) | KITTI box depth, median abs rel | BDD depth / flat-ground median ratio (hwy, city, night) |
|---|---|---|---|---|---|---|---|
| `da3_metric_large` (DA3METRIC-LARGE, vendored code) | **works (default)** | 280x504, bf16 backbone | 50 (48-138) | 1.02 GB torch peak, 1.16 GB device | 0.104 / 0.892 | 7.9 % (bias -6.1 %) | 1.14, 1.07, 1.06 (review-corrected alignment) |
| `da2_metric_outdoor_small` (HF transformers) | works (fast fallback) | 518x924, fp16 | 30 (20-56) | 0.25 GB, 0.37 GB device | 0.126 / 0.825 | 11.1 % (+4.8 %) | **2.30, 1.93, 1.69** (FOV bias) |
| `metric3d_vit_s_onnx` (ORT CUDA EP, fp16 ONNX) | works (most accurate on KITTI, slowest) | 616x1064 letterbox | 104 (98-295) | about 0.8 GB (ORT) | **0.067 / 0.948** | **4.7 %** (-1.1 %) | 1.24, 1.17, 1.18 |
| geometry only (`None`) | works | none | 0.7 (CPU) | 0 | n/a | see the KITTI table | reference |

Focal and FOV conventions, **verified on KITTI**:

- **DA3METRIC:** `depth = raw * f_net / 300`, where `f_net` is the focal length at the **network** input resolution: `f * 504/W`, then rounded to a multiple of 14. The per-image median ratio against GT was 0.89-0.96 with the network focal and 2.19-2.35 with the original-image focal. The DA3 code path is right, and the ros2-TRT-node convention would be 2.3x too far.
- **Metric3D:** canonical f = 1000 px, so `depth = raw * f * letterbox_scale / 1000`. The input **must** stay 616x1064. At 420x728 the KITTI median ratio drops to 0.82.
- **DA2 metric:** no intrinsics input. Its scale follows the network-pixel focal it saw in VKITTI training (about 1000 px at a short side of 518). On BDD (f_net about 504 px) it **over-estimates distance about 2x**: the road profile implies a 3.2-3.4 m camera height and 2.7-3.6 m wide cars. `calibration="scale_from_height"`, the automatic choice for DA2, rescales it by about 0.40. Even on KITTI its scale moves with input size: the median ratio is 0.95 at a short side of 518 and 1.07 at 364.

## Results: KITTI (measured here; `data/kitti`, CC BY-NC-SA 3.0, test only)

### (i) Per-object distance

Setup:

- 100 object-split images with **GT 2D boxes**, which isolates distance estimation from detection. 595 objects: 382 vehicles, 128 pedestrians, 85 cyclists. Per bin (by GT z): 99, 164, 193 and 139 objects.
- Filter: truncated < 0.3, occluded <= 1, z <= 80 m.
- Ground truth: forward depth of the **nearest 3D-box corner**, which is what a following-distance cue needs.
- Camera: per-image P2, camera height 1.65 m.
- Cells are MAE / median absolute relative error. Bias is the median signed relative error.
- In the "auto calib" rows the estimator gets the focal length but must estimate the horizon (from the depth road profile plus the virtual horizon) and the camera height (DA3, Metric3D) or the depth scale (DA2).

| Method | 0-10 m | 10-20 m | 20-40 m | 40-80 m | all | coverage | bias |
|---|---|---|---|---|---|---|---|
| flat ground, fixed horizon = principal row, H=1.65 | 0.56 m / 6.1% | 2.76 m / 9.1% | 8.42 m / 11.9% | 14.56 m / 21.7% | 7.31 m / 11.8% | 0.93 | +2.4% |
| flat ground, virtual horizon (size priors) | 0.42 m / 5.1% | 1.05 m / 5.1% | 5.40 m / 8.3% | 6.40 m / 9.0% | 3.78 m / 7.1% | 0.94 | -3.5% |
| width prior only | 1.37 m / 22.2% | 2.00 m / 15.6% | 2.94 m / 9.7% | 5.80 m / 8.0% | 3.13 m / 13.4% | 0.37 | -13.2% |
| height prior only | 0.37 m / 4.3% | 0.86 m / 5.2% | 1.90 m / 5.7% | 4.65 m / 6.3% | 2.09 m / 5.4% | 0.95 | -3.7% |
| size prior (width+height) | 0.74 m / 9.3% | 1.06 m / 6.0% | 2.00 m / 6.1% | 4.22 m / 6.3% | 2.08 m / 6.3% | 0.97 | -5.6% |
| geometry-only fused (virtual horizon) | 0.58 m / 5.8% | 0.88 m / 5.0% | 2.58 m / 7.4% | 4.36 m / 6.2% | 2.23 m / 6.0% | 0.97 | -4.1% |
| DA2-S metric box depth | 1.36 m / 12.8% | 2.64 m / 8.5% | 4.00 m / 10.7% | 7.75 m / 12.4% | 4.07 m / 11.1% | 1.00 | +4.8% |
| DA3METRIC-L box depth | 0.36 m / 4.5% | 1.25 m / 5.9% | 2.96 m / 9.1% | 8.75 m / 13.9% | 3.41 m / 7.9% | 1.00 | -6.1% |
| Metric3D ViT-S box depth | 0.32 m / 3.4% | 0.85 m / 3.1% | 2.03 m / 4.7% | 7.99 m / 8.4% | 2.81 m / 4.7% | 1.00 | -1.1% |
| DA2 fused, auto calib | 0.71 m / 7.8% | 1.00 m / 6.3% | 2.10 m / 7.0% | 4.76 m / 7.0% | 2.19 m / 6.8% | 1.00 | -3.6% |
| **DA3 fused, auto calib (default config)** | 0.50 m / 6.1% | 0.98 m / 5.9% | 2.12 m / 6.6% | 5.43 m / 9.3% | 2.31 m / 6.8% | 1.00 | -5.8% |
| Metric3D fused, auto calib | 0.35 m / 4.2% | 0.73 m / 4.0% | 1.75 m / 4.6% | 5.62 m / 6.7% | 2.14 m / 4.7% | 1.00 | -3.3% |
| DA3 fused, known camera (fixed horizon and H) | 0.47 m / 5.3% | 1.26 m / 6.5% | 2.15 m / 6.7% | 5.82 m / 9.6% | 2.48 m / 6.9% | 1.00 | -2.5% |

Takeaways:

- **Fusion roughly halves the DA2 error:** 11.1 % -> 6.8 %. For DA3 it cuts the error from 7.9 % to 6.8 %, and it reaches 100 % coverage. It does **not** beat the best single component (Metric3D depth 4.7 %, height prior 5.4 %).
- DA2 box depth is poor on pedestrians and cyclists: 27 % median error, against 5-9 % for DA3 and Metric3D.
- Flat ground with a fixed horizon is bad beyond 20 m (22 % at 40-80 m) because of road slope and pitch. A per-frame horizon is required.
- Against the box **centre** instead of the nearest face, every visible-surface method reads about 10-12 % short (DA3 fused 13.0 %, Metric3D depth 10.6 %). That offset is expected: roughly half the car length. Both GT variants are in `metrics.json`.

Road-profile self-calibration on KITTI (true camera height 1.65 m):

| Backend | Estimated camera height (median, IQR) | Horizon from profile vs. principal row |
|---|---|---|
| DA2 | 1.70 m (1.68-1.73) | -3.9 px |
| DA3 | 1.61 m (1.51-1.67) | -2.9 px |
| Metric3D | 1.66 m (1.63-1.70) | -2.9 px |

The virtual horizon from size priors sits -0.5 px from the principal row (MAD 5.2 px).

### (ii) Dense depth (50 `val_selection_cropped` pairs, GT <= 80 m)

| Backend | AbsRel | SqRel | RMSE | RMSElog | delta1 | delta2 | delta3 | median pred/GT | AbsRel after median scaling |
|---|---|---|---|---|---|---|---|---|---|
| DA2-S metric (518 short side) | 0.126 | 0.864 | 5.19 | 0.201 | 0.825 | 0.941 | 0.978 | 0.946 | 0.111 |
| DA3METRIC-L (504 long side) | 0.104 | 0.463 | 3.82 | 0.149 | 0.892 | 0.985 | 0.995 | 0.923 | 0.064 |
| Metric3D ViT-S (616x1064) | **0.067** | 0.308 | 3.19 | 0.110 | **0.948** | 0.990 | 0.996 | 0.981 | 0.058 |

DA3 scores below its paper (KITTI delta1 0.953). On these 1216x352 crops the 504-px long side leaves only 140 rows. Raising `process_res` to 756 or 1008 lifts delta1 to about 0.946 but needs 1.6-1.9 GB of VRAM (over budget), and AbsRel does not improve (0.092-0.097 against 0.092 at 504 on 25 pairs). With bf16 and fp32 backbones the accuracy is identical.

## Results: BDD100K (measured here; no depth GT, so this is agreement only)

Setup:

- 3 primary clips, 202 frames each at 5 fps.
- Boxes are official `box_track_20` GT boxes. The MOT-to-video frame mapping is per clip (`eval_bdd.MOT_ANCHORS`), pixel-matched against the MOT JPEGs in review (see **Review fixes**).
- Camera assumptions: f = 700 px, H_c = 1.3 m.
- "Clean" vehicles: car, truck or bus that is neither occluded nor truncated, with box height >= 15 px.
- The flat-ground reference uses a **depth-free** horizon (virtual horizon plus a principal-row prior). On these clips the virtual horizon dominates: the fitted horizon is 100-127 px above the principal row on the highway and night clips. The virtual horizon is itself built from the size priors, so here "flat ground" is close to the size prior and the two ratio columns are **not** independent checks (see **Review fixes**).

| Clip | Clean boxes | Backend | depth / flat ground | depth / size prior | fused / geometry-only fused | Camera height from road profile | Implied car width (rear views) |
|---|---|---|---|---|---|---|---|
| b1f4491b highway day | 767 | DA2 | 2.30 | 2.06 | 0.98 | 3.26 m | 3.63 m |
| | | DA3 | 1.14 | 1.06 | 1.04 | 1.59 m | 1.87 m |
| | | Metric3D | 1.24 | 1.18 | 1.09 | 1.72 m | 2.10 m |
| b1ff4656 city day | 572 | DA2 | 1.93 | 1.92 | 0.96 | 3.20 m | 3.21 m |
| | | DA3 | 1.07 | 1.03 | 1.01 | 1.37 m | 1.86 m |
| | | Metric3D | 1.17 | 1.15 | 1.08 | 1.81 m | 2.04 m |
| b23adb0d night city | 422 | DA2 | 1.69 | 1.69 | 0.93 | 3.43 m | 2.68 m |
| | | DA3 | 1.06 | 1.07 | 1.04 | 1.75 m | 1.93 m |
| | | Metric3D | 1.18 | 1.21 | 1.09 | 1.43 m | 2.38 m |

Values after the review's frame-alignment fix. b1ff4656 is unchanged. On the night clip the builder's misaligned run gave DA3 1.11 / 1.10 / 2.00 m, Metric3D 1.23 / 1.25 / 2.48 m and DA2 1.77 / 1.74.

- **Geometry self-consistency:** the median flat-ground / size-prior ratio is 0.93, 1.01 and 1.03 on the three clips. This is close to 1 largely **by construction**, because the horizon comes from the size priors (a single box gives flat ground = height prior exactly).
- **DA3 agrees best with the class size priors** (car 1.8 m wide x 1.5 m tall) on BDD. Its depth / size-prior ratio is 1.03-1.07 and its depth / flat-ground ratio 1.06-1.14. Its implied rear-view car width is 1.86-1.93 m, which is plausible with mirrors included. It found the hood row on all 3 clips: y = 528, 616 and 448.
- Metric3D reads 15-24 % longer than geometry, with cars 2.0-2.4 m wide, although it was the best on KITTI. This criterion depends on the priors: a US fleet with many SUVs and pickups taller than 1.5 m would push the truth toward Metric3D. Without BDD depth GT, this is not a ranking of accuracy.
- DA2 needs about 0.4x rescaling, and it gets it from the road profile.
- All ratios are **independent of the assumed focal length** to first order. **Absolute** BDD distances still scale with the unverified f = 700 px.

Temporal behaviour, measured on clean tracks at 5 fps:

| Signal | Median relative second difference | Implausible jumps (\|dZ/dt\| > 40 m/s) |
|---|---|---|
| Raw fused distance | 3.9-7.2 % | 0.6-4.9 % of steps |
| After the per-track Kalman filter | 0.8-1.6 % | <= 0.3 % |
| DA2 raw box depth | | 17-21 % |
| DA3 raw box depth | | 2-7 % |
| Metric3D raw box depth | | 6-8 % (was 8-11 % before the alignment fix) |

Median fused confidence on clean vehicles is 0.85-0.87 (DA3).

### Output files (`outputs/depth/`)

- `metrics.json`. Contains:
  - `kitti`: every method, both GT variants, per bin and per class group;
  - `bdd`: per clip and per backend;
  - `runtime_provisional`;
  - `focal_convention_checks`.
- `kitti_rows.json`: per-object predictions.
- `kitti_distance_error.png`: relative error against distance, per method.
- `bdd_<clip>_panel.png` (3 files): RGB with geometry-only distances, plus DA2, DA3 and Metric3D depth colormaps with fused distances and the horizon line.
- `bdd_lead_distance_timeline.png`: lead-vehicle distance over 40 s for each method.
- `demo_b1f4491b-cf446195_da3_metric_large.mp4`: 10.0 s, 1280x720, mp4v. Uses YOLO26n+ByteTrack boxes, DA3 fused distances, the lead vehicle, a depth inset and the horizon. `demo_..._frame.png` is a still from it.
- `eval_kitti.log`, `eval_bdd.log` (builder's runs); `eval_kitti_review.log`, `eval_bdd_review.log` (review re-runs; the review BDD run includes the alignment fix, and `metrics.json` holds these numbers).

## Reproduce (run from `perception_engine/`, PowerShell or Git Bash)

```
.venv\Scripts\python.exe -m perception.depth.test_geometry                 # CPU sanity checks (seconds)
.venv\Scripts\python.exe -m perception.depth.eval_kitti                    # KITTI (i)+(ii), all 3 backends, about 2-3 min
.venv\Scripts\python.exe -m perception.depth.eval_bdd                      # BDD agreement + PNGs, about 5-6 min
.venv\Scripts\python.exe -m perception.depth.demo_clip --video b1f4491b-cf446195 --start 14 --seconds 10
.venv\Scripts\python.exe -m perception.depth.check_mot_alignment      # review: MOT->video anchors (MOT JPEGs of the 7 primary clips come from scripts/fetch_bdd_samples.py)
```

Options:

- `eval_kitti`: `--backends ...`, `--n-images 100`, `--dense-pairs 50`.
- `eval_bdd`: `--clips ...`, `--max-frames 202`.

The KITTI data is range-fetched by `python scripts/fetch_bdd_samples.py --kitti` (same members as the original `select_kitti.py` + `bdd_remote_zip.py` build; see `data/kitti/MANIFEST.md`). Set `YOLO_AUTOINSTALL=False` for the demo. `demo_clip.py` also sets it.

## Licenses (details in MODELS.md)

| Component | Code | Weights and data lineage |
|---|---|---|
| Geometry and fusion | This project's own implementation of published formulas (Stein et al. 2003; Park & Hwang, Sci. World J. 2014) | The **only license-clean path** |
| DA3METRIC-LARGE | Apache-2.0 | Weights Apache-2.0, but trained on **Waymo Open**, whose terms bar WOD-trained weights from vehicle-assist use |
| DA2-Small metric | Apache-2.0 | Weights Apache-2.0 per the README, but lineage includes VKITTI2 (CC BY-NC-SA) and pseudo-labelled BDD100K and SA-1B |
| Metric3D ViT-S | BSD-2 | Weights license unstated, and trained on Waymo |
| KITTI (eval) | | CC BY-NC-SA 3.0 |
| BDD100K (eval) | | Research and non-commercial only |

All of this is fine for a research demo. **Legal review is needed before any product use.**

## Caveats

- **BDD intrinsics are assumptions.**
  - f = 700 px comes from a BDD maintainer comment.
  - The camera height is unknown. DA3 suggests 1.37-1.76 m on these clips, against the 1.3 m default.
  - A focal error scales **every** method's absolute distance by the same factor. Fusion cannot remove it, and the confidence does not include it.
  - Next step: per-video focal estimation with DA3-SMALL/BASE (Apache-2.0) or MoGe-2, or a fit against GPS ego-speed.
- **The KITTI results use GT boxes.** Real detector boxes (jitter, truncation, misses) will be worse, especially for flat ground, which is sensitive to the bottom edge at about `Z^2/(fH)` per pixel.
- **The fusion sigmas were tuned on KITTI.** On BDD the depth models disagree with each other by 10-25 %, so treat confidence as relative, not calibrated.
- **Hood detection is depth-based.** It works with DA3 (3 of 3 clips) and is unreliable with DA2 and Metric3D. An image-difference detector was tried and dropped because windscreen reflections move. Give a per-video `hood_row` when possible.
- **The road-profile fit assumes a locally flat road** in the lower-central band. Pass the segmentation block's drivable mask as `road_mask` when it is available.
- **Night.** DA3 stays consistent on the night clip (ratio 1.06). DA2's raw box depth is noisy there (16.6 % implausible jumps); the fused and filtered output is fine (0.3 %).
- **MOT-to-video alignment** is pixel-matched per clip for all 7 primary clips (±0.5 frame; review fix). MOT frames 0-1 are irregular (b1ff4656: MOT 1 is video frame 1, not 2).
- **All latencies are provisional** (shared GPU). DA3 at `depth_every=2-3` plus geometry at detector rate is the suggested real-time setting.
- **Integration.** `Detection.id` must be set for the Kalman smoothing. Detections without an id get negative ids and no smoothing.

## Review fixes

An independent reviewer re-ran the block on 2026-09-25 (same venv and commands, shared GPU). Everything below was **measured here** by the reviewer.

### Reproduction

- **KITTI** (`eval_kitti`): all 40 per-object method summaries and the 3 dense rows reproduce **exactly**. Examples: DA3 fused auto calib 6.8 %, Metric3D box depth 4.7 %, height prior 5.4 %, dense AbsRel 0.126 / 0.104 / 0.067. See `outputs/depth/eval_kitti_review.log`.
- **BDD** (the builder's unmodified `eval_bdd`): every ratio, camera height, car width, jitter and hood value reproduces **exactly**. Only the provisional latencies differ.
- **MODELS.md** byte sizes and sha256 prefixes match the files on disk. The licenses agree with the prior research verify notes:
  - DA3METRIC: Apache label, Waymo vehicle-assist clause;
  - DA2-Small: Apache per README, with BDD100K/SA-1B/VKITTI2 lineage and DA2 issue #320;
  - Metric3D: weights license unstated, BSD-2 code, non-authoritative cc0 re-upload tag;
  - YOLO26n: AGPL-3.0.
- **API:** `from perception.depth import DistanceEstimator` imports cleanly from `perception_engine/`. `estimate()` returns `perception.common.schemas.DistanceEstimate` objects. `test_geometry` passes, and `demo_clip` runs.

### Fixes applied

1. **MOT-to-video frame alignment (`eval_bdd.py`): wrong on 2 of the 3 BDD eval clips.**
   - The builder's single mapping `round(5.994*i - 4)` was measured on b1ff4656 only.
   - The reviewer pixel-matched MOT JPEGs against decoded `VideoFileInput` frames. That was 5 JPEGs per clip, range-fetched with `bdd_remote_zip.py --source mot_val1` into the reviewer's scratch folder, not into `data/`. An exact match has MSE about 2; the next-best frame has MSE of 5 to 1000.
   - The start offset differs per clip. Offsets from `round(5.994*i)`: b1ff4656 -4, b20e291a -4, b1d968b9 -1, b23adb0d -1, b204a5c1 0/+1, b1f4491b 0/+1 (exactly `6i`), b2064e61 -7.
   - The old mapping therefore sampled b1f4491b (highway) 4-5 frames and b23adb0d (night) 3 frames **before** the labelled instant (100-167 ms), so box depth readouts partly hit off-object pixels.
   - An independent YOLO26n IoU-vs-offset scan agrees: it peaks at -4, 0/+1 and about -1 to -2 (`outputs/depth/review_mot_alignment_yolo_iou.json`).
   - **Fix:** per-clip anchors in `eval_bdd.MOT_ANCHORS` with a linear fit; the residual is at most 0.5 frame, and 4 held-out b1ff4656 matches are exact. `check_mot_alignment.py` reproduces the anchors.
   - **Effect on the night clip:** DA3 depth / flat ground 1.11 -> 1.06, depth / size 1.10 -> 1.07, car width 2.00 -> 1.93 m. Metric3D 1.23 -> 1.18 and 2.48 -> 2.38 m. DA2 1.77 -> 1.69.
   - **Effect on the highway clip:** medians are unchanged, but implausible raw-depth jumps drop (DA3 4.5 -> 3.2 %, Metric3D 9.0 -> 6.3 %).
   - b1ff4656 and all KITTI numbers are unaffected. The BDD tables above show the corrected values.
2. **The constructor accepts `backend='<name>'` (`distance.py`).**
   - The integration convention `DistanceEstimator(backend=..., device='cuda', **opts)` used to crash with `AttributeError: 'str' object has no attribute 'name'`, because `backend=` expected a backend *object*.
   - A string is now treated as the backend name, and `'none'` means geometry only.
   - `depth_backend=` and passing a backend object work as before.

### Review notes (not changed; read the numbers with these in mind)

- **On BDD, "depth / flat ground" and "depth / size prior" are essentially one check, not two.**
  - The depth-free horizon is dominated by the virtual horizon, which is built from the same size priors. For one box, flat ground equals the height prior exactly.
  - The flat-ground / size-prior ratio of about 1 is therefore largely by construction.
  - What the BDD evidence really shows: DA3 is closest to car priors of 1.5 m height and 1.8 m width (ratios independent of focal length), and Metric3D reads 15-24 % longer. Which one is right depends on the true fleet dimensions.
- **DA2 "auto calib" on KITTI is given the true camera height, 1.65 m** (`eval_kitti.py` sets `cam_height_given`), so it estimates only the depth scale. On BDD it relies on the unverified 1.3 m. The DA3 and Metric3D auto rows estimate the camera height themselves.
- **Tuned and reported on the same 100 KITTI images:** the fusion sigmas, the readout percentile and the gating constants. They are a few scalars, so the overfitting risk is low, but this is not a held-out result. The `DEPTH_REL_SIGMA` comments quote 12.2 / 8.5 / 4.9 %, while the current run gives 11.1 / 7.9 / 4.7 %.
- **The DA3 sky mask is skipped in the evaluated fused path.** The eval scripts pass `depth_map=` into the estimator, so the sky mask is not used there. It is used in the production call without `depth_map`, and in the separately reported `depth_raw` readout. The effect should be negligible, since boxes are read in their lower-central part and the road in the lower band, but it was not measured.
- **DA2's KITTI scale is favoured.** Its metric fine-tune data, VKITTI2, is a synthetic clone of KITTI scenes with KITTI's camera, so its KITTI scale (median ratio 0.95) is near in-distribution. The about-2x over-estimate on BDD is more representative of a new camera.
- **Two `metrics.json` sections were written by hand.** No script produces `runtime_provisional` or `focal_convention_checks`. The network-focal convention is independently supported by the reproduced DA3 dense median ratio of 0.923. The roughly 2.3x "original focal" ratio follows arithmetically (x1216/504).
- **`perception/__init__.py` incident.** The file is 0 bytes now. Its `.pyc` was regenerated after the truncation (the recorded source size is 0), so the original content cannot be recovered from it. The sibling `perception/common/__init__.py`, created in the same instant, is 0 bytes, and all imports work.
- **Review re-run latencies** (provisional, shared GPU):
  - BDD 1280x720 p50: DA2 20-24 ms, DA3 41-55 ms, Metric3D 81-110 ms.
  - KITTI 1242x375: DA2 65 ms, DA3 49 ms, Metric3D 114 ms.
  - Demo, YOLO26n+ByteTrack plus DA3: 60 ms per frame p50 (40 ms depth).
  - DA3 torch peak memory: 1.02 GB.
