# KITTI test sample: manifest

A small KITTI sample that Knuckle Sandwich Robotics Inc. (KSR) uses to validate the depth and distance block (`perception/depth`, plan section 9). BDD100K has no depth ground truth, so the metric numbers come from this sample. It is for **testing only**. Nothing in this folder may be used for training or shipped.

- Built: 2026-09-25
- On disk: 141 MB (the cap was 600 MB)
- Source: the official public KITTI host `https://s3.eu-central-1.amazonaws.com/avg-kitti/`
- Method: every file was range-extracted from its remote zip with `tools/bdd/bdd_remote_zip.py --url ... --index data/kitti/index/<zip>_index.csv get --from-file ...`. No whole zip was downloaded, and every member passed a CRC32 check.
- License: **CC BY-NC-SA 3.0** (Attribution-NonCommercial-ShareAlike), per https://www.cvlibs.net/datasets/kitti/. For commercial use, contact the KITTI authors.

## Contents

| Path | What | Files | Size |
|---|---|---|---|
| `object/training/label_2/*.txt` | Every object-split training label: 2D box, 3D dimensions, location, rotation, truncation, occlusion | 7,481 | 16 MB on disk (5.6 MB of data) |
| `object/training/image_2/*.png` | 100 left colour images (about 1242x375). IDs are in `index/object_selected_ids.txt` | 100 | 80 MB |
| `object/training/calib/*.txt` | Calibration (P0-P3, R0_rect, Tr_velo_to_cam) for those 100 images | 100 | 0.2 MB |
| `depth_selection/val_selection_cropped/{image,groundtruth_depth,intrinsics}/` | 50 val pairs (every 20th of the 1,000), each 1216x352. GT is a uint16 PNG where depth = value / 256 m, and 0 means no GT. `intrinsics/*.txt` holds the 3x3 K | 150 | 42 MB |
| `index/*_index.csv` | Cached central directories of the 4 remote zips | 4 | 3.2 MB |
| `perception_engine/tools/kitti/*.txt` | Member lists used for the `get` calls, plus the selected object IDs (committed; the originals were in `index/`) | 4 | small |
| `perception_engine/tools/kitti/select_kitti.py` | Rebuilds the selection. Stdlib only, seed 9 | 1 | |

Source zips and their sizes:

- `data_object_label_2.zip`: 7,482 entries
- `data_object_calib.zip`: 15,001 entries
- `data_object_image_2.zip`: 15,001 entries, about 12 GB in total; only 100 members were fetched
- `data_depth_selection.zip`: 8,013 entries, 2,010,655,006 B; only 150 members were fetched

## How the 100 object images were chosen

`tools/kitti/select_kitti.py` uses greedy coverage with seed 9. It counts only objects that pass the distance-eval filter:

- class Car, Van, Truck, Pedestrian or Cyclist
- truncated < 0.3
- occluded <= 1
- 0 < z <= 80 m
- box height >= 10 px

It picks images until every class group and every range bin (0-10, 10-20, 20-40, 40-80 m) is represented. The result is 100 images with 595 evaluable objects:

| Group | 0-10 m | 10-20 m | 20-40 m | 40-80 m |
|---|---|---|---|---|
| Vehicle | 60 | 93 | 127 | 102 |
| Pedestrian | 26 | 46 | 41 | 15 |
| Cyclist | 13 | 25 | 25 | 22 |

The selection favours rarer bins and classes, so this sample is **not** representative of KITTI's natural distribution. That is intended, because it gives stable per-bin medians.

## Rebuild

Everything below runs from `perception_engine/`. The one-liner is
`python scripts/fetch_bdd_samples.py --kitti --only none` (same members, skips files that exist). By hand:

```bash
python tools/kitti/select_kitti.py            # only to re-pick; the committed lists are what was used
PY=.venv/Scripts/python.exe; B=https://s3.eu-central-1.amazonaws.com/avg-kitti; K=data/kitti; L=tools/kitti
$PY tools/bdd/bdd_remote_zip.py --url $B/data_object_label_2.zip  --index $K/index/data_object_label_2_index.csv  get --from-file <all training/label_2/*.txt names> --out $K/object
$PY tools/bdd/bdd_remote_zip.py --url $B/data_object_calib.zip    --index $K/index/data_object_calib_index.csv    get --from-file $L/object_calib_members.txt --out $K/object
$PY tools/bdd/bdd_remote_zip.py --url $B/data_object_image_2.zip  --index $K/index/data_object_image_2_index.csv  get --from-file $L/object_image_members.txt --out $K/object
$PY tools/bdd/bdd_remote_zip.py --url $B/data_depth_selection.zip --index $K/index/data_depth_selection_index.csv get --from-file $L/depth_selection_members.txt --out $K
```

## License notes for KSR

- KITTI is CC BY-NC-SA 3.0. It is non-commercial, it requires attribution, and derivatives must be shared alike. Evaluating a hackathon prototype is research use.
- Keep this folder out of any public repository and any product build.
- Attribution: A. Geiger, P. Lenz, R. Urtasun, "Are we ready for Autonomous Driving? The KITTI Vision Benchmark Suite", CVPR 2012. For the depth-selection split: J. Uhrig et al., "Sparsity Invariant CNNs", 3DV 2017.
