# Traffic block (lights + signs): model weights

This covers every weight used by `perception/traffic/` for the Knuckle Sandwich Robotics Inc. (KSR) AI Spatial Driving Copilot prototype. The other KSR perception blocks keep their own lists.

Where the files live:
- Hugging Face downloads go to the project HF cache, `models/huggingface/hub/` (set by the venv `.pth` hook). Revisions are pinned in code.
- Files KSR derived or fetched outside HF go to `models/traffic/`.
- sha256 values are the first 16 hex digits, computed on the files on disk (2026-09-25).
- Total downloaded by this block: about 99 MB of weights and cards, plus 25 MB of BDD100K images (listed at the end).

## Weights

| Name / role | Original URL (pinned revision) | File | Bytes | sha256[:16] | Code license | Weights license | Dataset license inheritance |
|---|---|---|---|---|---|---|---|
| **Autoware traffic_light_classifier v4.0**, car MobileNetV2. Light color (`backend='autoware_onnx'`) | https://huggingface.co/AutowareFoundation/traffic_light_classifier (tag `v4.0`, sha `527c4905`) | `traffic_light_classifier_mobilenetv2_batch_1.onnx` | 8,926,508 | 455b71b3b20d3a96 | Apache-2.0 (Autoware) | Apache-2.0 (HF card) | TIER IV internal Japanese data, not redistributed. No extra dataset terms stated. The card warns accuracy may drop outside Japan |
| same, batch 6 (reference only; used for the equivalence check) | same | `traffic_light_classifier_mobilenetv2_batch_6.onnx` | 8,926,648 | e4792eed6a46fdbd | Apache-2.0 | Apache-2.0 | same |
| **KSR-derived** Autoware classifier: dynamic batch + uint8 NHWC input, normalization moved into the graph (weights unchanged; outputs identical to batch_6, max abs diff 0.0) | generated locally by `lights.autoware_u8_model()` from the batch_1 file above | `models/traffic/traffic_light_classifier_mobilenetv2_u8nhwc_dynbatch.onnx` | 8,926,733 | 0c35e80353a3c7c2 | Apache-2.0 (derivative; keep the NOTICE/attribution) | Apache-2.0 | same as source |
| **dronefreak/bdd100k-yolo26s**. Stage-1 boxes (traffic light / traffic sign) for `e2e_video.py` and `eval_signs.py` only; the detection block owns the production detector | https://huggingface.co/dronefreak/bdd100k-yolo26s (sha `b11da17c`) | `best.pt` | 20,340,357 | 47a879aff374b220 | AGPL-3.0 (Ultralytics) | AGPL-3.0 (card) | **BDD100K: research / non-commercial only** (commercial use only for BDD/BAIR Commons members, otherwise UC Berkeley OTL) |
| **cvtechniques/TrafficSignDetection**. YOLO11n on a LISA US subset, 13 classes: doNotEnter, pedestrianCrossing, speedLimit15/25/30/35/40/45/50/55/65, stop, yield. The card lists 12; the checkpoint has 13 (it adds speedLimit55) | https://huggingface.co/cvtechniques/TrafficSignDetection (sha `1daf1b3e`) | `best.pt` | 5,522,202 | e82b514db5f47538 | AGPL-3.0 (Ultralytics 8.4.21 in the checkpoint) | Card says **MIT**, but that tag is unreliable: see next column | **LISA Traffic Sign Dataset: academic / research license (UCSD CVRR)**. Treat as research / demo only |
| **COCO YOLO26s**. `backend='coco_stop'` (class 11 'stop sign') | https://github.com/ultralytics/assets/releases/download/v8.4.0/yolo26s.pt | `models/traffic/yolo26s.pt` | 20,422,725 | 646f8bc3fe0a6568 | AGPL-3.0 | AGPL-3.0 | COCO (annotations CC BY 4.0; images under Flickr terms) |
| **PP-OCRv6_small_rec** (ONNX). Sign OCR verification (speed-limit value, STOP/ENTER) | https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx (sha `b8f84f0b`) | `inference.onnx` + `inference.yml` (character dict) | 21,159,378 + 150,579 | 5435fd747c9e0efe / ab078671bb49f062 | Apache-2.0 (PaddleOCR) | Apache-2.0 (HF card) | none beyond Apache-2.0 |
| PP-OCRv6_tiny_rec (ONNX). Evaluated for OCR and not used (it misread more on 20-40 px signs) | https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_rec_onnx (sha `2612ab37`) | `inference.onnx` | 4,462,639 | 9ef676d6ed3c8825 | Apache-2.0 | Apache-2.0 | none |

Model cards and label files were also fetched with the weights: Autoware `README.md`, `lamp_labels*.txt`, `lamp_recognizer_ml.param.yaml`, `deploy_metadata.yaml`; dronefreak `README.md`, `args.yaml`; cvtechniques `README.md`, `confusion_matrix.png`; PaddlePaddle `README.md`. `dronefreak .../results.csv` in the cache was not fetched by this block.

## Data fetched by this block

`data/bdd100k/images/val_lights/` holds 400 BDD100K **val** keyframes (25 MB): all 314 val images outside the 250-image subset that contain a yellow light at least 10 px tall, plus 86 random light images (seed 11). They were range-extracted with `tools/bdd/bdd_remote_zip.py --source solesensei`, which reads the HF mirror `Xoner1/bdd100k-client` `BDD100k.zip`, member `bdd100k/bdd100k/images/100k/val/<id>.jpg`, with a CRC32 check. The selection is in `selection.json`, and the script is `perception/traffic/fetch_val_lights.py`. The BDD100K license applies (research / non-commercial; keep the copyright notice).

## Commercial-use summary (not legal advice)

- **Commercially clean path:** Autoware classifier (Apache-2.0), PP-OCRv6 (Apache-2.0), and the HSV rules (own code on OpenCV Apache-2.0).
- **Research/demo only:** the dronefreak detector (AGPL-3.0 plus BDD100K non-commercial terms), the LISA sign model (AGPL-3.0 plus the LISA academic license, despite the "mit" tag), and COCO YOLO26s (AGPL-3.0; an Ultralytics Enterprise license would remove the copyleft).
- A KSR product needs a replacement stage-1 detector (e.g. COCO RF-DETR, Apache-2.0) and a sign typer trained on permissive data, for example public-domain MUTCD templates.
