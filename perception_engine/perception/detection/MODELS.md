# Detection block: model weights

These are the weights used by `perception/detection` (plan section 7) for the Knuckle Sandwich Robotics Inc. (KSR)
AI Spatial Driving Copilot prototype. All files were downloaded on 2026-09-25 from the original publisher (Hugging Face
model repos, the Ultralytics GitHub release, or Roboflow's Google Cloud Storage bucket). `sha256` shows the first 16 hex
digits.

| Preset | File on disk (under `models\`, or `KSR_MODELS_DIR`) | Original URL | Bytes | sha256 (16) |
|---|---|---|---|---|
| `bdd-yolo26s` (default) | `huggingface\hub\models--dronefreak--bdd100k-yolo26s\snapshots\b11da17c1149b60c0f067eee6588f1780507e064\best.pt` | https://huggingface.co/dronefreak/bdd100k-yolo26s/resolve/main/best.pt | 20,340,357 | `47a879aff374b220` |
| `bdd-yolo26n` | `huggingface\hub\models--dronefreak--bdd100k-yolo26n\snapshots\52902393f0455d0b66259da1ede2bede5fb7e8ed\best.pt` | https://huggingface.co/dronefreak/bdd100k-yolo26n/resolve/main/best.pt | 5,409,029 | `eb100fbb28fbe9d1` |
| `bdd-rfdetr-nano` | `huggingface\hub\models--dronefreak--bdd100k-rfdetr-nano\snapshots\6ac2a8c75da01829b3147dc0ab95dce78403b799\checkpoint_best_total.pth` | https://huggingface.co/dronefreak/bdd100k-rfdetr-nano/resolve/main/checkpoint_best_total.pth | 122,095,365 | `1de1a513754e0593` |
| `coco-yolo26s` | `detection\yolo26s.pt` | https://github.com/ultralytics/assets/releases/download/v8.4.0/yolo26s.pt | 20,422,725 | `646f8bc3fe0a6568` |
| `coco-rfdetr-nano` | `detection\rfdetr\rf-detr-nano.pth` | https://storage.googleapis.com/rfdetr/nano_coco/checkpoint_best_regular.pth | 366,287,238 | `d8d6b9ee57d4d0ed` |
| `coco-rfdetr-small` | `detection\rfdetr\rf-detr-small.pth` | https://storage.googleapis.com/rfdetr/small_coco/checkpoint_best_regular.pth | 386,045,550 | `d81979a9213a2109` |

The small metadata files in the same Hugging Face snapshots (`args.yaml`, `config.json`, `README.md`, `results.csv`) were
also downloaded, for provenance only. The two RF-DETR COCO files match the MD5 values in `rfdetr` 1.11.0's registry
(`fb6504cce7fbdc783f7a46991f07639f` for nano and `fb37061c1af7bace359c91b723a8d5c1` for small). The URLs above are the
same ones `rfdetr` uses when it downloads the files itself. The COCO RF-DETR files are large (about 370 MB) because they
are training checkpoints that include EMA and optimizer state.

Total downloaded by this block: 920.6 MB of weights plus about 60 KB of metadata. The cap was 1 GB.

`coco-yolo26s` stays at `models\detection\yolo26s.pt`. It is not in `models\` next to `yolo26n.pt`, so
`YOLO("yolo26s.pt")` does NOT resolve it. `Detector` always passes the full path.

## Class order checked in each checkpoint

| Checkpoint | Class order (index 0..N) |
|---|---|
| bdd-yolo26s, bdd-yolo26n (`model.names`) | person, rider, car, truck, bus, train, motor, bike, traffic light, traffic sign |
| bdd-rfdetr-nano (`config.json` class_names, `model.class_names`) | the same 10 in the same order; RF-DETR `class_id` is a 0-based index |
| coco-yolo26s | the 80 COCO classes (0 person ... 9 traffic light, 11 stop sign ...) |
| coco-rfdetr-* | the 80 COCO names. RF-DETR returns sparse COCO category ids (1..90), so class mapping goes by the returned `class_name`, not by position |

`Detector` maps every class **by name**. The 2018 aliases person, motor and bike become pedestrian, motorcycle and
bicycle. COCO names go through `COCO_TO_BDD`.

## Licenses

| Preset | Code license | Weights license (as published) | Training-data inheritance | KSR commercial use |
|---|---|---|---|---|
| `bdd-yolo26s`, `bdd-yolo26n` | Ultralytics AGPL-3.0 (runtime). DetectionBench training code is Apache-2.0 | AGPL-3.0 (HF card) | **BDD100K**: educational, research and not-for-profit use only; commercial use only for BDD/BAIR Commons members or under a UC Berkeley OTL license. Base `yolo26s.pt` was pretrained on **Objects365v1** ("academic purpose only") and then COCO | **No.** Research and demo only |
| `bdd-rfdetr-nano` | rfdetr Apache-2.0 | Apache-2.0 (HF card) | **BDD100K** non-commercial, plus the RF-DETR base pretraining on **Objects365** | **No.** Research and demo only, despite the Apache tag |
| `coco-yolo26s` | AGPL-3.0 | AGPL-3.0; closed-source use needs an Ultralytics Enterprise License | COCO (CC-BY-4.0 annotations; Flickr image terms) and Objects365v1 pretraining (academic only) | Needs an Ultralytics Enterprise License and a legal review of Objects365 |
| `coco-rfdetr-nano`, `coco-rfdetr-small` | Apache-2.0 | Apache-2.0 (N/S/M/L; XL and 2XL are PML-1.0 and are not used here) | COCO, plus Objects365 pretraining per the RF-DETR paper | Unclear. Apache weights, but the upstream Objects365 term needs legal review |

The dataset-license facts and the Objects365 points come from the verified research track
(`research/wf1_raw_results.json`, tracks `detection` and `runtime_licensing`). This table is an engineering summary,
not a legal opinion.
