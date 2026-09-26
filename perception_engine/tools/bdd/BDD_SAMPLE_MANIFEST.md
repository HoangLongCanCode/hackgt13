# BDD100K smoke-test subset: manifest

This is a small BDD100K subset for the AI Spatial Driving Copilot. It supports run-testing the perception engine (plan sections 6 to 14). Nothing here is a full dataset archive. Every file was pulled out of its remote zip with HTTP Range requests, and each one passed a CRC32 check against the zip's central directory.

- Built: 2026-09-25
- On disk: 563 MB under `data/bdd100k/`
- Downloaded: about 0.56 GB, against a 1.5 GB cap (breakdown under "Download accounting")
- License: research / educational / not-for-profit only. See "License" below and `LICENSE_BDD100K.txt`.
- Rebuild on a fresh clone (from `perception_engine/`): `python scripts/fetch_bdd_samples.py`. It range-fetches every file below from a pinned member list (`tools/bdd/sample_members.csv`), skips files that already exist, and regenerates the derived labels. The tools live in `perception_engine/tools/bdd/` (this copy of the manifest is `tools/bdd/BDD_SAMPLE_MANIFEST.md`); the data goes to `<PERCEPTION_DATA_DIR>/bdd100k/` (default `perception_engine/data/bdd100k/`).

## 1. What is here

| Path | Contents | Files | Size |
|---|---|---|---|
| `videos/val/` | **7 primary val clips** (.mov). Each one has keyframe labels **and** 5 fps MOT tracking labels | 7 | 136 MB |
| `videos/val_extra/` | 6 more val clips from a first selection pass. These have keyframe labels only, with no MOT | 6 | 125 MB |
| `images/val/` | 250 val keyframe JPEGs (1280x720). This set includes the keyframes of all 13 clips | 250 | 15 MB |
| `images/sem_seg_val/` | 30 sem_seg val triplets: `images/val/*.jpg`, `labels/val/*_train_id.png` (19-class trainId), `color_labels/val/*_train_color.png` | 90 | 5.8 MB |
| `mot/images/track/val/b1ff4656-0435391e/` | One complete MOT val sequence at 5 fps: 202 frames of city intersection, daytime | 202 | 25 MB |
| `labels/bdd100k_labels_images_val.json` | **Original 2018 release** of the val labels. Covers all 10k val keyframes: box2d det (10 classes), lane poly2d, drivable-area poly2d, and weather/scene/timeofday | 1 | 208 MB |
| `labels/eval_subset_det.json` | The 250-image subset in the original 2018 format, with det, lane, drivable and attributes | 1 | 1.9 MB |
| `labels/eval_subset_coco.json` | The same 250 images converted to COCO: 10 BDD100K det classes, 4,604 boxes. Image-level weather/scene/timeofday are kept on each `images[]` entry | 1 | 1.2 MB |
| `labels/drivable_masks/*.png` | Drivable masks for the 250 images, **rasterised locally** from the poly2d (Bezier curves included). Values: 0 = direct, 1 = alternative, 2 = background. These are not the official PNGs | 250 | 1.1 MB |
| `labels/box_track_20/val/*.json` | Official MOT (box_track_20) labels for the 7 primary clips, in scalabel format: 5 fps, about 202 frames, stable track `id`s | 7 | 8 MB |
| `index/*.csv` | Cached central directories for each remote zip. With these, later `get` calls cost one request per member | 6 | 48 MB |
| `index/selected_videos.json` | Why each clip was chosen: scenario, attributes, keyframe label counts, runners-up | | |
| `index/videos_verify.json`, `index/videos_extra_verify.json` | Container check results for each clip | | |
| `index/eval_subset_summary.json` | Class and attribute counts for the eval subset | | |
| `perception_engine/tools/bdd/` | `bdd_remote_zip.py` (fetcher), `verify_video.py`, `select_videos.py`, `build_eval_subset.py`, `sample_members.csv` (pinned members). All use stdlib or numpy only | | |

### 1.1 Primary clips (`videos/val/`)

These clips come from the original `bdd100k_videos.zip`, via `bdd100k/videos/100k/val/<id>.mov`. The source is HF `linxxx3/bdd100k_videos`. All 7 are also BDD100K MOT val sequences, so `labels/box_track_20/val/<id>.json` provides 5 fps tracking ground truth for the first about 40 s. Keyframe counts come from the 10 s keyframe label in `bdd100k_labels_images_val.json`.

| Scenario (plan section 3.1 / 33) | Video id | Bytes | Weather / scene / time | Keyframe: car, light, sign, ped, lane, drivable | MOT: frames, tracks, boxes |
|---|---|---|---|---|---|
| Highway, daytime (following distance) | `b1f4491b-cf446195` | 20,571,671 | overcast / highway / daytime | 25, 1, 7, 1, 7, 4 | 202, 327, 3,840 |
| City intersection, many traffic lights | `b1ff4656-0435391e` | 20,794,254 | overcast / city street / daytime | 20, 9, 8, 0, 10, 1 | 202, 107, 3,023 |
| Residential | `b20e291a-6012d836` | 19,836,264 | clear / residential / daytime | 23, 0, 2, 2, 6, 3 | 203, 145, 2,973 |
| Night driving, city | `b23adb0d-8a7aaced` | 20,544,892 | clear / city street / night | 18, 12, 4, 1, 14, 1 | 203, 161, 2,507 |
| Adverse weather (snow) | `b204a5c1-064b0040` | 18,762,164 | snowy / city street / daytime | 22, 3, 1, 1, 7, 1 | 202, 72, 2,567 |
| Dawn/dusk, heavy traffic | `b1d968b9-ce42734f` | 20,342,757 | overcast / highway / dawn/dusk | 19, 4, 3, 1, 7, 3 | 203, 154, 4,138 |
| Rainy night (bonus) | `b2064e61-2beadd45` | 21,483,290 | rainy / highway / night | 19, 9, 2, 0, 6, 1 | 203, 64, 2,857 |

### 1.2 Extra clips (`videos/val_extra/`)

The first selection pass picked from all 10k val clips, not only MOT sequences. These clips have keyframe labels but no MOT labels.

| Scenario | Video id | Bytes | Weather / scene / time | Keyframe: car, light, sign, ped, lane, drivable |
|---|---|---|---|---|
| Highway, day | `bc07d865-f526e4d9` | 20,200,152 | overcast / highway / daytime | 14, 2, 8, 0, 12, 3 |
| City intersection | `b780088d-0bf9e946` | 21,336,080 | overcast / city street / daytime | 22, 8, 5, 5, 19, 3 |
| Residential | `c38cd3f6-c74ca48b` | 21,069,055 | clear / residential / daytime | 14, 4, 3, 8, 10, 1 |
| Night city | `b23d2079-0c019d8e` | 21,994,184 | clear / city street / night | 16, 12, 4, 0, 15, 4 |
| Rain | `b9ecc316-529acd3c` | 22,168,399 | rainy / city street / daytime | 14, 4, 4, 0, 6, 1 |
| Dusk, heavy traffic | `bf1af616-e750e7dd` | 24,196,280 | partly cloudy / city street / dawn/dusk | 30, 0, 0, 0, 8, 1 |

### 1.3 Video verification

`tools/verify_video.py` checks the containers without ffmpeg. All 13 clips passed:

- Top-level atoms are `ftyp`(qt) `wide` `mdat` `moov`, all complete.
- CRC32 matches the zip.
- Duration is 40.0 to 40.3 s. Each clip has one H.264 (`avc1`, High profile, level 4.1) video track with about 1,200 frames at about 30 fps. No audio track and no GPS or metadata track. The only `udta` atom is `©swr` = `Lavf57.71.100`, which means the clips were re-muxed with ffmpeg.

> **Decoder gotcha: rotation.** The coded frames are **720x1280 (portrait)**. This was confirmed from the H.264 SPS. Each clip also carries a `tkhd` rotation matrix, which makes the **display** size 1280x720. The rotation is **90° for some clips and 270° for others**: `b1f4491b`, `b1ff4656`, `b23adb0d` and `bf1af616` are 90°, and the rest are 270°. Use a decoder that applies the display matrix. OpenCV ≥ 4.5 with the FFmpeg backend does this by default (`CAP_PROP_ORIENTATION_AUTO`), as do ffmpeg/PyAV with autorotate. Otherwise you get portrait frames, and they come out sideways in opposite directions. Assert `frame.shape == (720, 1280, 3)` in the smoke test.

### 1.4 Aligning labels to video time

These mappings are approximate and have not been checked pixel-by-pixel, because no decoder was available here.

- **Keyframe image:** the image labels are for the frame at **t = 10 s** (`timestamp: 10000`), which is about video frame 300.
- **MOT frames:** `<id>-0000001.jpg` is `frameIndex` 0. Frames are at 5 fps, so `frameIndex` *i* is at about *t = i / 5* s, or video frame *6i*.

### 1.5 Eval subset (`images/val`, `labels/eval_subset_*`)

The 250 images were chosen by stratified sampling with seed 13 (see `tools/build_eval_subset.py`):

- **Always included:** the 13 video keyframes.
- **By time of day and scene:**

  | | City street | Highway | Residential |
  |---|---|---|---|
  | Daytime | 45 | 30 | 20 |
  | Night | 40 | 25 | 8 |
  | Dawn/dusk | 15 | 12 | 5 |

- **Top-ups:** 12 rainy, 12 snowy and 5 foggy images for weather, plus 3 with a train and 5 with a rider for rare classes.

What the subset contains:

- **Boxes by class:** car 2,618; traffic sign 831; traffic light 638; pedestrian 301; truck 101; bus 45; bicycle 28; rider 23; motorcycle 15; train 4.
- **Lanes and drivable areas:** 1,736 lane polylines and 449 drivable polygons.
- **Weather:** clear 104, snowy 36, rainy 33, overcast 29, undefined 25, partly cloudy 18, foggy 5.
- **Scene:** city street 135, highway 79, residential 36.
- **Time of day:** daytime 123, night 93, dawn/dusk 34.

COCO conversion details:

- **Class names:** the 2018 names map to the det_20 names (`person`→`pedestrian`, `bike`→`bicycle`, `motor`→`motorcycle`).
- **Class ids:** 1..10 in scalabel det order: pedestrian, rider, car, truck, bus, train, motorcycle, bicycle, traffic light, traffic sign.
- **Boxes:** `bbox = [x1, y1, x2-x1+1, y2-y1+1]`, which is scalabel's `box2d_to_bbox` convention.
- **Attributes:** each box keeps `occluded`, `truncated` and `trafficLightColor` under `attributes`.

**What each plan section can test with this data:**

| Plan section | Test data |
|---|---|
| 7 Vehicle detection, 11 Traffic lights, 12 Traffic signs | COCO boxes |
| 8 Tracking | `box_track_20` labels + `mot/` frames, or the 7 clips |
| 10 Lanes | lane poly2d, including laneDirection/laneStyle attributes |
| 13 Road segmentation | `drivable_masks`, plus the sem_seg triplets |
| 9 Depth | No ground truth. BDD100K has none, so use the videos only for qualitative or consistency checks |

## 2. Where it came from

| Data | Source (HF dataset → file → member) |
|---|---|
| Videos | `linxxx3/bdd100k_videos`: `bdd100k_videos/bdd100k_videos.zip.part-00000..00018`. This is the original 1.97 TB zip split byte-for-byte into 19 parts; ZIP64 with 100,005 entries, and the videos are stored uncompressed. Members are `bdd100k/videos/100k/val/<id>.mov` |
| 2018 val labels, keyframe images, sem_seg | `Xoner1/bdd100k-client`: `BDD100k.zip` (8,166,127,525 B). Same byte size as the Kaggle `solesensei/solesensei_bdd100k` archive the user linked. Members: `bdd100k_labels_release/bdd100k/labels/bdd100k_labels_images_val.json`, `bdd100k/bdd100k/images/100k/val/*.jpg`, `bdd100k_seg/bdd100k/seg/{images,labels,color_labels}/val/*` |
| MOT labels | `jaffe03195/bdd100k_sot`: `bdd100k.zip`, members `bdd100k/labels/box_track_20/val/<id>.json` |
| MOT frames | `vanthanh/BDD100kMOT`: `images20-track-val-1.zip`, members `bdd100k/images/track/val/<id>/*.jpg`. This zip holds all 200 val sequences and 39,973 frames |
| License text | `github.com/bdd100k/bdd100k`: `doc/source/license.rst`, the source of doc.bdd100k.com/license.html |

URL pattern: `https://huggingface.co/datasets/<repo>/resolve/main/<file>`. HF redirects with a 302 to a CDN that honours `Range`.

## 3. Fetching more

```bash
cd perception_engine                 # everything below runs from here; data lands in data/bdd100k
python scripts/fetch_bdd_samples.py  # the whole sample set above (pinned members, skips existing files)
python tools/bdd/bdd_remote_zip.py sources                                # known mirrors
python tools/bdd/bdd_remote_zip.py list --split val --limit 20            # videos (default source)
python tools/bdd/bdd_remote_zip.py get <video-id> --by-id --out data/bdd100k/videos/val --flat
python tools/bdd/bdd_remote_zip.py --source solesensei list --grep images/100k/val/ --limit 5
python tools/bdd/bdd_remote_zip.py --source solesensei get bdd100k/bdd100k/images/100k/val/<id>.jpg --out data/bdd100k/images/val --flat
python tools/bdd/bdd_remote_zip.py --source mot_labels get bdd100k/labels/box_track_20/val/<id>.json --out data/bdd100k/labels --strip 2
python tools/bdd/bdd_remote_zip.py --source mot_val1 get --from-file names.txt --out data/bdd100k/mot --strip 1
python tools/bdd/bdd_remote_zip.py --url https://huggingface.co/datasets/<repo>/resolve/main/<x>.zip list   # any other zip
python tools/bdd/verify_video.py "data/bdd100k/videos/val/*.mov"
python tools/bdd/select_videos.py [--any]      # re-pick scenario clips (default: only MOT-labelled val clips)
python tools/bdd/build_eval_subset.py          # re-select + rebuild the 250-image subset (idempotent)
```

How the fetcher behaves:

- **Index cache:** on first use of a source, the central directory is fetched once and saved to `data/bdd100k/index/<source>_index.csv` (`fetch_bdd_samples.py` skips this by using the pinned offsets in `tools/bdd/sample_members.csv`, and only falls back to the full index if a CRC check fails). The sizes are 12.9 MB for videos and 17.8 MB for solesensei.
- **Streaming:** large members are streamed in 16 MB range chunks.
- **Coalescing:** small nearby members are merged into one request. For example, the 202 MOT frames came in one 24.7 MB request.
- **Resuming:** existing files of the right size are skipped.
- **Politeness:** requests are sequential, with a 0.15 s pause and exponential backoff. 4xx errors other than 408/429 are not retried.
- **Split parts:** a range that crosses a 100 GiB part boundary is split into one request per part (tested).

Other mirrors that were checked and can be used the same way:

| Mirror | What it has |
|---|---|
| `Myszz/BDD100k` `val.zip` (582 MB) | 10k val images + the same `bdd100k_labels_images_val.json` |
| `hirundo-io/bdd100k-val` (570 MB zip) | 10k val images + `bdd100k.csv`, boxes only with no attributes. Indexed in `index/hirundo_val_zip_index.csv` |
| `Xing1210/bdd100k` `bdd100k_images_100k.zip` | Configured as source `images100k`; not indexed |
| `dgural/bdd100k` (and its copies `Hanshiya/bdd100k`, `ShantyCam/bdd100k-det`) | FiftyOne export of the 10k val set, as loose JPEGs + `samples.json` (71 MB) with det and weather/scene/timeofday |
| `ShayManor/bdd100k-cs19` | sem_seg 10k images and masks as individual files; `seg/images/val` and `seg/labels/val` have 1,000 each |
| `jaffe03195/bdd100k_sot` | Also has `labels/box_track_20/box_track_val_cocofmt_ori.json` (all 200 val sequences, COCO-video format, 22.6 MB compressed), `labels/100k/{train,val}/*.json` (per-image 2018 labels), and `qdtrack-frcnn_r50_fpn_12e_mot_bdd100k.pth` (a BDD100K-trained QDTrack checkpoint, 228 MB) |

## 4. License

**Data and labels.** BDD100K data and labels are © 2018 The Regents of the University of California. The terms are:

- **Allowed:** use, copying, modification and distribution **for educational, research and not-for-profit purposes**, without fee.
- **Commercial use:** granted only to BDD and BAIR Open Research Commons members and their affiliates, and not transferable. Everyone else needs a licence from UC Berkeley's Office of Technology Licensing.
- **Notice:** the copyright notice and the licence paragraphs must accompany all copies, modifications and distributions.
- **Warranty and liability:** the data is provided "as is", with no warranty, and the Regents disclaim liability.

The full text is in `LICENSE_BDD100K.txt`. It was taken verbatim from the bdd100k repo's `doc/source/license.rst` and also appears on the dgural/karthik HF mirrors. The bdd100k **toolkit code** is BSD-3-Clause, and that licence does **not** cover the data.

**What this means for this project:**

- Smoke tests and evaluation for the hackathon prototype are research/non-commercial use, which is fine.
- **Do not** ship BDD100K data in a commercial product without a Berkeley OTL licence. Also have someone check the terms before using models *trained or fine-tuned* on BDD100K commercially (plan section 37, Level 2). This is flagged for review, not a legal conclusion.
- Keep this folder out of any public repo or redistribution. It is already out of the way under `data/`, so add `data/` to `.gitignore`.
- The HF mirrors are third-party re-uploads, and their own licence tags vary (bsd, other, apache). The BDD100K licence above is what governs the data.

## 5. What did not work, and why

| Item | Result |
|---|---|
| Kaggle `solesensei/solesensei_bdd100k` | There is no Kaggle token, so it cannot be downloaded directly. The **same archive** was used from the HF copy `Xoner1/bdd100k-client/BDD100k.zip` (identical size, same layout). `2inf/bdd100k/solesensei_bdd100k.zip` is another copy, but it is **gated (HTTP 401)**, so it was skipped |
| Official hosts | `dl.cv.ethz.ch` and `doc.bdd100k.com` do not resolve (DNS). The licence was read from the GitHub source instead |
| **GPS/IMU `info` JSONs** (`bdd100k_info`) | **Not found.** Searched HF by name (`bdd100k_info`, `bdd_info`, `bdd gps`, `BDD-X`...) and with HF full-text search: no hits. `linxxx3/bdd100k_videos` has videos only. `NHirose/BDD_OmniVLA/BDD_dataset.zip` (157 GB) has 31M entries and a 4 GB central directory, too big to even index within the budget. The .mov files carry no GPS track. **For plan section 3.2, generate GPS from the scenario timeline**, as the plan already allows |
| 2020-format labels (`det_20/det_val.json`, `lane/`, official drivable PNG masks) | Not found as standalone files on any mirror checked. The 2018 `bdd100k_labels_images_val.json` covers the same images with det boxes, lane and drivable poly2d, and attributes. Drivable masks were rasterised locally (section 1). If you need the official lane or drivable PNGs, `pip install bdd100k` in the project venv and convert with its `label/to_mask` tooling. That was not done here, because nothing is installed into the system Python |
| Parquet mirrors | Skipped, because reading them needs pyarrow, which is not in the system Python. These are `quandelagl/bdd-100k`, `danjacobellis/bdd_val_500` and `bdd500`, `minato-ryan/bdd100k-images`, `adhisetiawan/bdd10k-bitmasks` and `colormaps` (sem_seg), `kd7/graid-bdd100k`, and `lance-format/BDD100K-enriched` (Lance format) |
| Depth ground truth | BDD100K has none, and none was expected |

## 6. Download accounting

| Item | Bytes |
|---|---|
| Central directories: videos 12.9 MB, solesensei 17.8 MB, hirundo 0.9 MB, jaffe `bdd100k.zip` 30.4 MB, jaffe `bdd_created.zip` 6.1 MB, Myszz `val.zip` 1.0 MB, vanthanh val-1 5.8 MB, `epiphanicc/bdd100k` `bdd100kmot_vehicle.zip` **107.6 MB** (a probe that turned out not to be needed) | 182.5 MB |
| `bdd100k_labels_images_val.json` (deflated) | 13.9 MB |
| hirundo `bdd100k.csv` (inspection only; not kept here) | 2.4 MB |
| 13 videos (7 primary + 6 extra) | 273.3 MB |
| 7 box_track_20 label files | 1.7 MB |
| One MOT sequence (202 frames) | 24.7 MB |
| 250 eval images + 30 sem_seg triplets | 22.5 MB |
| HF API tree listings, READMEs, EOCD probes | about 40 MB |
| **Total** | **about 0.56 GB** |

## 7. Added by the tracking block (plan section 8), 2026-09-25

**What was added:** the 5 fps MOT val frames for the 6 remaining primary sequences, so all 7 primary clips now have local MOT frames next to their `box_track_20` labels.

- **Location:** `mot/images/track/val/<id>/<id>-0000001.jpg` ...
- **Sequences:** `b1f4491b-cf446195` (202 frames), `b20e291a-6012d836` (203), `b23adb0d-8a7aaced` (203), `b204a5c1-064b0040` (202), `b1d968b9-ce42734f` (203), `b2064e61-2beadd45` (203). That is 1,216 JPEGs, 1280x720.
- **Source:** HF `vanthanh/BDD100kMOT`, `images20-track-val-1.zip` (source `mot_val1`, the cached `index/mot_val1_zip_index.csv`). This zip does hold all 7 primary sequences.
- **Fetch:** `python tools/bdd/bdd_remote_zip.py --source mot_val1 get --from-file <names> --out data/bdd100k/mot --strip 1`, with the member list built from the cached index. It took 6 coalesced range requests and 164,704,778 bytes, and every member passed the CRC32 check.
- **Size:** `mot/` is now about 185 MB on disk, including the original `b1ff4656-0435391e`.
- **Frame/label check:** frame names match the `box_track_20` `name` fields 1:1 for all 7 sequences, with no missing or extra frames. Use these frames, **not** frames decoded from the .mov, for MOT evaluation, because the MOT frames and the video frames drift out of alignment.
