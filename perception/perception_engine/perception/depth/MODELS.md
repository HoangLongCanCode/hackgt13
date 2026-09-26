# Depth block: weights and code inventory

This lists every weight file and every vendored repository used by `perception/depth` in the Knuckle Sandwich Robotics Inc. (KSR) AI Spatial Driving Copilot prototype (HackGT 13).

Weights live in the project HF cache at `models/huggingface/hub`. The venv `.pth` hook points `HF_HUB_CACHE` there, and `backends.py` resolves them with `snapshot_download(local_files_only=True)` first. Nothing was pip-installed.

## Weights

| Name | Original URL | Bytes | sha256 (first 16) | Code license | Weights license (label) | Dataset-license inheritance |
|---|---|---|---|---|---|---|
| Depth-Anything-V2-Metric-Outdoor-Small-hf `model.safetensors` (24.8M params) | https://huggingface.co/depth-anything/Depth-Anything-V2-Metric-Outdoor-Small-hf (rev `fd2c2202`) | 99,173,660 | `ad065c77a7421ca5` | Apache-2.0 (DepthAnything/Depth-Anything-V2) | HF repo has no tag. The DA2 README says the Small model is Apache-2.0. The original `.pth` repo, depth-anything/Depth-Anything-V2-Metric-VKITTI-Small, is tagged apache-2.0 | Metric fine-tune on Virtual KITTI 2 (CC BY-NC-SA 3.0). The DA2 encoder was trained on 62M pseudo-labelled images including **BDD100K (8.2M)**, SA-1B and ImageNet-21K (DA2 paper, Table 7). DA2 issue #320 on the licensing is unanswered. BDD frames are **in-distribution** for this model |
| DA3METRIC-LARGE `model.safetensors` (0.35B params, fp32) | https://huggingface.co/depth-anything/DA3METRIC-LARGE (rev `4010e39f`) | 1,336,734,448 | `bbea5b0b3ee38984` | Apache-2.0 (ByteDance-Seed/Depth-Anything-3) | Apache-2.0 (HF card and README table) | Trained on 14 sets including **Waymo Open** (WOD terms forbid WOD-trained weights "in operation of a vehicle or to assist in the operation of a vehicle"), Argoverse, DDAD, Lyft, PandaSet, DSEC, DrivingStereo, Cityscapes and others |
| metric3d-vit-small `onnx/model_fp16.onnx` (**default**) | https://huggingface.co/onnx-community/metric3d-vit-small (rev `ee63b95d`) | 75,778,144 | `4afcc0893dbb3c0c` | BSD-2-Clause (YvanYin/Metric3D) | **Unstated** by the authors. The Metric3D README sends commercial inquiries to the authors. The onnx-community re-upload is tagged cc0-1.0, which is not authoritative | Waymo, Argoverse2, DDAD, Lyft, Virtual KITTI, Cityscapes, DSEC, Mapillary PSD, PandaSet and others (Metric3D v2 paper, Table V) |
| metric3d-vit-small `onnx/model.onnx` (fp32; used for the first KITTI run, same accuracy) | same repo | 150,739,433 | `674a665052f01bb2` | same | same | same |
| YOLO26n `yolo26n.pt` (demo clip detector only; **not downloaded by this block**, already present from the environment setup) | https://github.com/ultralytics/assets/releases (Ultralytics) | 5.5 MB | `9b09cc8bf347f0fc` | AGPL-3.0 | AGPL-3.0 (Ultralytics: "All Ultralytics YOLO trained models fall under the AGPL-3.0 License by default") | COCO (CC BY 4.0 annotations; Flickr image terms) |

Downloaded by this block: 1,661,825,685 B of weights (about 1.66 GB, under the 2.5 GB cap), plus about 141 MB of KITTI data (see `data/kitti/MANIFEST.md`).

## Vendored code (git clone --depth 1, imported via sys.path; not pip-installed)

| Repository | Path | Commit | License | Why |
|---|---|---|---|---|
| https://github.com/ByteDance-Seed/Depth-Anything-3 | `perception/third_party/Depth-Anything-3` | `3d835ec1a580` (2026-07-27) | Apache-2.0 | DA3 network definition (`model/da3.py`, `dinov2/`, `dpt.py`). We build `DepthAnything3Net(DinoV2('vitl'), DPT(...))` directly and load the safetensors. This bypasses `api.py` and the YAML config system, which need omegaconf, open3d, pycolmap, xformers, moviepy and numpy<2. A tiny `omegaconf` stand-in is injected only if the real package is missing. xformers is not needed: it is only an optional import for SwiGLU in the Giant model |
| https://github.com/mewwts/addict | `perception/third_party/addict` | `75284f9593df` (2021-01-05) | MIT | `addict.Dict`, imported by the DA3 model code |

## Not used, or tried and dropped

- Official Metric3D fp32 ONNX at a smaller input (420x728): the metric scale breaks (KITTI median ratio 0.82). Keep the 616x1064 canonical input.
- DA3 at `process_res` 756 or 1008: no accuracy gain on KITTI, and 1.6-1.9 GB VRAM. Over the 1.5 GB per-process budget.
- Image-difference hood detector (appearance change over time): failed, because windscreen reflections move. It was removed. Depth-discontinuity hood detection is kept.

## Commercial-use summary for KSR (flagged for legal review; not a legal conclusion)

None of the three depth models is clean for a shipped driving product.

- **DA3METRIC:** inherits the Waymo vehicle-assist prohibition.
- **Metric3D:** the weights license is unstated, and it also inherits Waymo.
- **DA2-Small metric:** inherits VKITTI2 (CC BY-NC-SA) and its BDD100K pseudo-label lineage.

The geometry path (flat ground plus size prior) is KSR's own implementation of published formulas. It is the only license-clean distance source, and it runs with `depth_backend=None`.
