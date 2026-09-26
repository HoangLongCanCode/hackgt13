# Models and licences

Every model, dataset and major library used by the AI Spatial Driving Copilot perception stack of
Knuckle Sandwich Robotics Inc. (KSR), with its code licence, weights licence and dataset-licence inheritance, and the permissive
alternatives for a product. The per-file tables with sizes and sha256 prefixes are in each block's `MODELS.md`
(`perception_engine/perception/<block>/MODELS.md`); `scripts/download_models.py --list` prints the same inventory.

**This is an engineering summary, not legal advice.** Plan section 37 requires a legal review before any product use.

## Summary

- **The hackathon demo is fine**: every component may be used for research, education and a public demo.
- **A product is not**: the realtime defaults inherit non-commercial dataset terms (BDD100K, Waymo, LISA) and the
  Ultralytics runtime is AGPL-3.0. A commercial path exists for most blocks (see [Permissive path](#permissive-path)).
- **Never commit** weights, datasets, or files derived from datasets (frames, crops, masks): they are gitignored and
  fetched by the scripts.

## Realtime default models

| Block | Model (preset) | Source | Code licence | Weights licence | Dataset inheritance | Commercial use |
|---|---|---|---|---|---|---|
| detection | YOLO26s fine-tuned on BDD100K (`bdd-yolo26s`) | Hugging Face `dronefreak/bdd100k-yolo26s` | Ultralytics AGPL-3.0 (runtime) | AGPL-3.0 (model card) | **BDD100K** non-commercial; base pretrained on Objects365 (academic only) and COCO | No |
| tracking | BoT-SORT + ByteTrack (`ul_botsort`, `ul_bytetrack`) | Ultralytics 8.4.163 (pip) | AGPL-3.0 | no weights (reuses the detector) | none | Needs an Ultralytics Enterprise License, or the Apache path |
| depth | Depth Anything 3 metric large (`da3_metric_large`) | HF `depth-anything/DA3METRIC-LARGE`; code vendored from `ByteDance-Seed/Depth-Anything-3` | Apache-2.0 | Apache-2.0 | Trained on 14 sets incl. **Waymo Open** (its terms forbid use "to assist in the operation of a vehicle"), Argoverse, Cityscapes, ... | No (Waymo term) |
| lanes | TwinLiteNet+ large (`twinlitenetplus_large`) | Author's Google Drive, linked from `chequanghuy/TwinLiteNetPlus` (MIT) | MIT | no separate licence | **BDD100K** + YOLOP masks: non-commercial | No |
| traffic lights | Autoware `traffic_light_classifier` MobileNetV2 v4.0 (+ KSR uint8 derivative) | HF `AutowareFoundation/traffic_light_classifier` | Apache-2.0 | Apache-2.0 | TIER IV internal data, no extra terms stated | Yes (keep NOTICE / attribution) |
| traffic signs | YOLO11n on a LISA US subset (`lisa_crops`) | HF `cvtechniques/TrafficSignDetection` | AGPL-3.0 (Ultralytics) | card says MIT, not reliable | **LISA** Traffic Sign Dataset: academic/research licence | No |
| sign OCR | PP-OCRv6 small recogniser (ONNX) | HF `PaddlePaddle/PP-OCRv6_small_rec_onnx` | Apache-2.0 | Apache-2.0 | none beyond Apache-2.0 | Yes |
| geometry distance | flat ground + class size prior (`geometry`) | KSR code (published formulas) | KSR | none | none | Yes: the only licence-clean distance source |

The environment check (`scripts/verify_env.py`) also downloads Ultralytics `yolo26n.pt` (AGPL-3.0, COCO).

## Alternatives in the code

| Block | Model | Code | Weights | Dataset inheritance | Notes |
|---|---|---|---|---|---|
| detection | `bdd-yolo26n` | AGPL-3.0 | AGPL-3.0 | BDD100K NC | smaller |
| detection | `bdd-rfdetr-nano` (HF `dronefreak/bdd100k-rfdetr-nano`) | Apache-2.0 (`rfdetr`) | Apache-2.0 tag | BDD100K NC + Objects365 | research only despite the Apache tag |
| detection | COCO `yolo26s` (Ultralytics release v8.4.0) | AGPL-3.0 | AGPL-3.0 | COCO (CC BY 4.0 annotations, Flickr image terms) + Objects365 | fires on the ego hood |
| detection | COCO RF-DETR nano / small (Roboflow GCS) | Apache-2.0 | Apache-2.0 | COCO + Objects365 pretraining | Objects365 term needs review |
| tracking | Roboflow `trackers` 2.6.1 (`rf_mcbyte`, `rf_botsort`, `rf_bytetrack`, ...) | Apache-2.0 | none | none | best MOTA/IDF1 here; the licence-safe path |
| depth | DA2 metric outdoor small (HF `depth-anything/Depth-Anything-V2-Metric-Outdoor-Small-hf`) | Apache-2.0 | Apache-2.0 per DA2 README | Virtual KITTI 2 (CC BY-NC-SA 3.0); encoder pseudo-labels include BDD100K | No |
| depth | Metric3D ViT-S ONNX (HF `onnx-community/metric3d-vit-small`) | BSD-2-Clause | unstated by the authors | Waymo, Argoverse2, Cityscapes, ... | No |
| lanes | TwinLiteNet+ medium, YOLOP ONNX (`hustvl/YOLOP`) | MIT | no separate licence | BDD100K NC | No |
| lanes | `comma10k_segnet` (HF `commaai/comma10k-segnet`) | MIT (+ `segmentation-models-pytorch` MIT, `timm` Apache-2.0) | MIT | **comma10k, MIT** | Yes (weakest on BDD metrics) |
| traffic signs | COCO YOLO26s `stop sign` (`coco_stop`) | AGPL-3.0 | AGPL-3.0 | COCO | precise but rare |
| sign OCR | PP-OCRv6 tiny | Apache-2.0 | Apache-2.0 | none | evaluated, not used |
| segmentation | PIDNet-S (Qualcomm AI Hub re-host) | MIT | MIT | **Cityscapes** NC ("any derivative work") | No |
| segmentation | EfficientViT-Seg B0 / B1 / B2 (HF `han-cai/efficientvit-seg`) | Apache-2.0 | Apache-2.0 tag | Cityscapes NC | No |
| segmentation | SegFormer B0 / B2 (HF `nvidia/segformer-*-cityscapes`) | transformers Apache-2.0; original NVIDIA Source Code License | **NVIDIA non-commercial** | Cityscapes NC | No |
| openpilot (experimental) | `driving_vision.onnx` v0.11.1 | MIT | MIT (ships in the MIT repo) | comma.ai fleet data (terms not verified) | README: "research purposes only"; never used for control |

## Vendored code

Cloned at pinned commits by `scripts/fetch_third_party.py` into `perception_engine/perception/third_party/`
(gitignored, unmodified; the script writes `THIRD_PARTY_NOTICES.md` next to them).

| Repository | Commit | Licence | Used for |
|---|---|---|---|
| ByteDance-Seed/Depth-Anything-3 | `3d835ec1a580` | Apache-2.0 | DA3 network definition (depth default) |
| mewwts/addict | `75284f9593df` | MIT | imported by the DA3 code |
| chequanghuy/TwinLiteNetPlus | `90f1b8695ae3` | MIT | TwinLiteNet+ model (lanes default) |
| XuJiacong/PIDNet | `4c158cf24ce4` | MIT | `pidnet_s` |
| mit-han-lab/efficientvit | `de7d7733cc03` | Apache-2.0 | EfficientViT-Seg |
| commaai/openpilot v0.11.1 (sparse) | `4df40d2c1946` | MIT | reference for the openpilot geometry ports |

## Runtime libraries

| Library | Licence | Note |
|---|---|---|
| Ultralytics 8.4.163 (detector runtime, trackers) | **AGPL-3.0** | Distributing or network-serving it triggers AGPL source sharing; a closed product needs an Ultralytics Enterprise License or the Apache path (RF-DETR + Roboflow `trackers`) |
| torch, torchvision | BSD-3-Clause | cu130 wheels bundle the NVIDIA CUDA runtime (NVIDIA EULA for redistribution) |
| onnxruntime-gpu | MIT | |
| transformers, huggingface_hub, timm, rfdetr, trackers, opencv-python | Apache-2.0 | |
| supervision, segmentation-models-pytorch, motmetrics, fastapi | MIT | |
| uvicorn, websockets, scipy, numpy | BSD | |
| orjson | Apache-2.0 / MIT | |
| av (PyAV) | BSD-3-Clause | Its wheels bundle FFmpeg (LGPL; GPL when built with libx264). Pinned but not imported by the realtime path |
| Node.js (relay) | MIT | the relay uses no npm packages |
| Android app: AndroidX, Compose, CameraX, Media3, OkHttp, kotlinx-serialization, kotlinx-coroutines | Apache-2.0 | |
| Tests: JUnit 4 (EPL-1.0), JUnit 5 (EPL-2.0), MockWebServer (Apache-2.0) | | test-only |

## Datasets

| Dataset | Licence | Where it enters |
|---|---|---|
| **BDD100K** (c) 2018 The Regents of the University of California | Educational, research and not-for-profit use only; commercial use needs BDD/BAIR Commons membership or a UC Berkeley OTL licence. Keep the copyright notice (`fetch_bdd_samples.py` writes `LICENSE_BDD100K.txt` next to the data) | Training data of the detector and lanes defaults; all evaluation data; sim clips; YOLOP masks are derived BDD labels |
| **Cityscapes** | Non-commercial; trained models count as derivative works | All segmentation weights |
| **KITTI** | CC BY-NC-SA 3.0 | Depth evaluation only |
| **Virtual KITTI 2** | CC BY-NC-SA 3.0 | DA2 metric fine-tune |
| **Waymo Open Dataset** | Terms forbid WOD-trained models "in operation of a vehicle or to assist in the operation of a vehicle" | DA3 and Metric3D training |
| **LISA Traffic Sign** | Academic/research licence (UCSD CVRR) | Sign typer |
| **Objects365** | "Academic purpose only" | Pretraining of every YOLO26 and RF-DETR base |
| **COCO** | Annotations CC BY 4.0; images under Flickr terms | COCO baselines |
| **comma10k** | MIT | `comma10k_segnet` (the clean lanes option) |

Navigation: routes fetched with `--nav-provider google` are subject to the Google Maps Platform Terms of Service. The
default `mock` provider uses no external data.

## Permissive path

What a commercial build could use today, and what is missing:

| Block | Commercially cleaner option | Status |
|---|---|---|
| detection | Apache-2.0 detector on COCO-only bases (the research notes recommend D-FINE `*-coco` or RT-DETRv2-R18) fine-tuned on commercially licensed driving data; COCO RF-DETR as a stopgap after an Objects365 review | Not tested here; needs training data |
| tracking | Roboflow `trackers` (`rf_mcbyte`, `rf_botsort`) | Works today (Apache-2.0), slightly more ID switches |
| distance | geometry only (`depth_backend=None`: flat ground + size prior) | Works today: KITTI 2.23 m MAE / 6.0 % at 97 % coverage; no depth network needed |
| lanes | `comma10k_segnet` (MIT weights on MIT data), or a retrain on comma10k | Works, weak on BDD metrics (lane IoU 11.2) |
| traffic lights | Autoware classifier (Apache-2.0), HSV rules | Works today; the stage-1 boxes still come from the detector |
| traffic signs | PP-OCRv6 (Apache-2.0) plus a sign typer trained on permissive data (for example public-domain MUTCD templates) | Typer missing |
| segmentation | retrain on comma10k or other licensed data | Missing (every current weight is Cityscapes) |
| runtime | drop Ultralytics (AGPL) or buy an Enterprise License | Decision needed |

## Adding or changing a model

1. Add the weights to `scripts/download_models.py` (`ITEMS`: repo + commit, or URL + size + sha256) and to the block's
   `MODELS.md` (source, size, sha256, code licence, weights licence, dataset inheritance).
2. Vendored code: pin the commit in `scripts/fetch_third_party.py` (`REPOS`); never edit the vendored copy.
3. Update this page and, if it becomes a realtime default, `perception/config_realtime.yaml` (the hello's `models`
   list comes from the loaded engine).
4. Check the licence of the training data, not only the weights tag: several "Apache" or "MIT" checkpoints here inherit
   non-commercial dataset terms.
