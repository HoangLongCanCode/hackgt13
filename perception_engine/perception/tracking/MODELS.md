# Tracking block (plan section 8): models, code and licenses

This file lists every weight file and library the tracking block uses. The block is part of the Knuckle Sandwich Robotics Inc. (KSR) AI Spatial Driving Copilot prototype. The trackers are algorithms with no learned weights. The only weight file is the detector, which the block uses to evaluate itself. In the product, the section 7 detection block supplies the detections.

## Weights

| Name | Original URL | Local path | Bytes | sha256 (first 16) | Code license | Weights license | Dataset-license inheritance |
|---|---|---|---|---|---|---|---|
| YOLO26s fine-tuned on BDD100K (`best.pt`, 10 BDD classes, trained at imgsz 960; DetectionBench) | https://huggingface.co/dronefreak/bdd100k-yolo26s/resolve/b11da17c1149b60c0f067eee6588f1780507e064/best.pt | `models/huggingface/hub/models--dronefreak--bdd100k-yolo26s/snapshots/b11da17c1149b60c0f067eee6588f1780507e064/best.pt` (HF cache; resolved with `hf_hub_download`, pinned revision) | 20,340,357 | `47a879aff374b220` | Ultralytics, AGPL-3.0 | AGPL-3.0 (HF model card) | **Trained on BDD100K**, so treat it as research, educational and not-for-profit only unless KSR clears commercial use with UC Berkeley OTL. It is also based on Ultralytics YOLO26 COCO-pretrained weights (AGPL-3.0, or an Ultralytics Enterprise License) |

Notes:

- The file was already in the shared HF cache, where the concurrent detection block had downloaded it. `hf_hub_download` reused it, so this block downloaded no weights.
- ReID with `model: auto` (Ultralytics BoT-SORT / TrackTrack in `UltralyticsTrackPipeline`) reuses the detector's own feature maps. No extra weights are involved. This detector's Detect head reports `end2end=False`, so Ultralytics did **not** fall back to `yolo26n-cls.pt`. If a future detector is end2end or exported (ONNX/TensorRT), Ultralytics would try to download `yolo26n-cls.pt` (AGPL-3.0, ImageNet-trained) from the Ultralytics GitHub assets.

## Code (installed in the venv; nothing vendored)

| Component | Version | Source | License | Used for |
|---|---|---|---|---|
| Ultralytics trackers (BYTETracker, BOTSORT, OCSORT, DeepOCSORT, FASTTracker, TRACKTRACK, GMC) | 8.4.163 | https://github.com/ultralytics/ultralytics (`ultralytics/trackers`, `ultralytics/cfg/trackers`) | AGPL-3.0 | `ul_*` backends, `UltralyticsTrackPipeline` |
| Roboflow `trackers` (SORT, ByteTrack, BoT-SORT + CMC, C-BIoU, OC-SORT, McByte; HOTA eval) | 2.6.1 | https://github.com/roboflow/trackers | Apache-2.0 | `rf_*` backends, HOTA in `mot_eval.py` (a port of TrackEval, MIT) |
| supervision | 0.30.5 | https://github.com/roboflow/supervision | MIT | `sv.Detections` container for the Roboflow backends |
| motmetrics (py-motmetrics) | 1.4.0 | https://github.com/cheind/py-motmetrics | MIT | MOTA / IDF1 / ID switches (the IoU matrix is computed in `mot_eval.py`, because motmetrics' `iou_matrix` uses the NumPy-2-removed `np.asfarray`) |
| scipy | 1.18.1 | PyPI | BSD-3-Clause | Hungarian assignment for ignore-region handling |
| KSR code in this folder (`tracker.py`, `motion.py`, `mot_eval.py`, `eval_bdd.py`, `demo_video.py`, `detector.py`, `test_motion.py`) | n/a | this repo | KSR (proprietary, unpublished) | wrapper, motion/TTC layer, BDD evaluation |

## License summary for shipping

- **The AGPL-3.0 path** is Ultralytics trackers plus an Ultralytics detector. It is fine for the public HackGT demo. For a closed-source KSR product, get an Ultralytics Enterprise License or switch to the Apache-2.0 path.
- **The Apache-2.0 path** is Roboflow `trackers` (`rf_botsort`, `rf_mcbyte`, `rf_bytetrack`, ...) plus a permissively licensed detector. The tracking and motion code in this folder does not import Ultralytics on the `rf_*` path, except through `detector.py`, which is only the evaluation detector.
- **BDD100K.** Any detector fine-tuned on BDD100K, and every number in `outputs/tracking/metrics.json`, derives from BDD100K. BDD100K's terms are research, educational and not-for-profit only. This is flagged for legal review; it is not a legal conclusion.
