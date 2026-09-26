# Lane block: weights, code and data provenance

Knuckle Sandwich Robotics Inc. (KSR), AI Spatial Driving Copilot, HackGT 13 prototype. This file covers the lane-line and drivable-area block (plan §10 and §13) in `perception/lanes/`.

This agent downloaded no weight files. The verification agents had already fetched all four files, and they were copied into `models/lanes/`. The URLs below are the original upstream sources; on a fresh clone `python scripts/download_models.py` fetches them from there into the same `models/lanes/` layout. Each copy was checked against upstream as follows:

- **YOLOP ONNX:** the git blob SHA-1 matches the GitHub contents API.
- **comma10k-segnet:** the SHA-256 matches the Hugging Face LFS oid.
- **TwinLiteNet+:** the byte sizes match the Google Drive listing.

## Weights

| File (under `models/lanes/`) | Original URL | Bytes | sha256 (first 16 hex) | Code license | Weights license | Dataset-license inheritance |
|---|---|---|---|---|---|---|
| `twinlitenetplus/large.pth` | Google Drive file `1H8P-GrOUBOaVs5LEqXBfz0dC9gguUUio` (download: `https://drive.usercontent.google.com/download?id=1H8P-GrOUBOaVs5LEqXBfz0dC9gguUUio&export=download`), from the folder `https://drive.google.com/drive/folders/1EqBzUw0b17aEumZmWYrGZmbx_XJqU-vz` linked in the README of github.com/chequanghuy/TwinLiteNetPlus | 7,959,943 | `5605d1a8ce762c1b` | MIT (Copyright 2025 Chế Quang Huy) | No separate license. The file is hosted on the author's Google Drive and linked from the MIT repo's README; it is not in the repo itself | **BDD100K** (trained on BDD100K + YOLOP masks). Research / non-commercial only. Commercial use needs a BDD100K licence from UC Berkeley OTL |
| `twinlitenetplus/medium.pth` | Google Drive file `121z9XUh7_lgze8i6nS6Ne2HQ7ZG5_uG9` (same folder) | 2,054,463 | `04a7959946f68748` | MIT | same as above | **BDD100K** (as above) |
| `yolop/yolop-640-640.onnx` | https://github.com/hustvl/YOLOP/raw/main/weights/yolop-640-640.onnx (git blob `3c87fdd1ec583dcd38c866f9a3bc7db5a56f19a7`, verified) | 35,902,568 | `cd66a3e0087a7258` | MIT (hustvl/YOLOP) | No separate license; MIT repo | **BDD100K** (as above) |
| `comma10k-segnet/model.safetensors` | https://huggingface.co/commaai/comma10k-segnet/resolve/main/model.safetensors (revision `b642c614eb0737fe7befa229f47fbd0245033038`, LFS oid verified) | 38,502,740 | `8208672861ad1b11` | MIT (model card). Runtime libraries already in the venv: `segmentation-models-pytorch` 0.5.0 (MIT) and `timm` 1.0.30 (Apache-2.0, which supplies the `tu-efficientnet_b2` encoder) | MIT | **comma10k**, MIT ("no academic only restrictions"). This is the only commercially clean option here. Plan §37 still requires a legal review before product use |
| `comma10k-segnet/config.json` | https://huggingface.co/commaai/comma10k-segnet/resolve/main/config.json | 374 | `2b8f16dbad9bd853` | MIT | n/a | n/a |
| `comma10k-segnet/albumentations_config_eval.json` | https://huggingface.co/commaai/comma10k-segnet/resolve/main/albumentations_config_eval.json | 414 | `d260853fe0a993e2` | MIT | n/a | n/a |

Loading notes:

- **TwinLiteNet+ `.pth`:** plain state_dicts, safe to load with `torch.load(weights_only=True)`. The tensors were saved on `cuda:0`, so load them with `map_location="cpu"`.
- **YOLOP ONNX:** the opset is 12. It has three outputs: `det_out` [1,25200,6], `drive_area_seg` [1,2,640,640] and `lane_line_seg` [1,2,640,640].
- **comma10k-segnet:** an `smp.Unet(tu-efficientnet_b2)` with 5 classes. Class order: 0 road, 1 lane markings (no arrows or crosswalks), 2 undrivable, 3 movable, 4 my car. It has 9.6 M parameters.

## Vendored code

| Path | Source | Commit | License | Use |
|---|---|---|---|---|
| `perception/third_party/TwinLiteNetPlus/` | `git clone --depth 1 https://github.com/chequanghuy/TwinLiteNetPlus` | `90f1b8695ae311d5123b05f8534b2e11e42499d2` (2026-05-05) | MIT | Only `model/model.py` and `model/config.py` are imported, through a sys.path loader that does not leak the generic package name `model`. Nothing was modified |

Nothing was pip-installed.

## Ground-truth data fetched for evaluation (range-extracted, CRC-verified)

The GT was pulled out of the official YOLOP annotation zips linked from the hustvl/YOLOP and TwinLiteNetPlus READMEs. The fetch used `tools/bdd/bdd_remote_zip.py --url` with HTTP Range requests, so the full 248 MB and 321 MB archives were never downloaded.

| Local path | Source zip | Members | Bytes on disk |
|---|---|---|---|
| `data/bdd100k/labels/yolop_ll_seg_val/*.png` | `ll_seg_annotations.zip`, Drive `1lDNTPIQj_YLNZVkksKM25CvCHuquJ8AP` | `bdd_lane_gt/val/<id>.png` for the 250 eval-subset images | 1,719,009 |
| `data/bdd100k/labels/yolop_da_seg_val/*.png` | `da_seg_annotations.zip`, Drive `1xy_DhUZRHR8yrZG3OwTQAHhYTnXn7URv` | `bdd_seg_gt/val/<id>.png` for the same 250 images | 2,014,962 |
| `outputs/lanes/_cache/calib_{ll,da}/*.png` | same two zips | 600 random val images **not** in the eval subset (seed 7). Used only to calibrate two geometric priors | 8,868,113 |
| `outputs/lanes/_cache/yolop_{ll,da}_seg_index.csv` | central directories of the two zips | 80,003 entries each | 12,253,330 |

About these masks:

- They are derived BDD100K labels, so the BDD100K research / non-commercial terms apply. Keep them out of any public repo.
- Their format was measured here, not taken from documentation. Masks are 1280x720 RGBA, with lane pixels 0/255. Lane lines are centre lines drawn at cv2 thickness 2 (about 3.5 px wide). All 8 BDD lane categories are included, among them crosswalks, road curbs and "vertical" (stop-line) marks. Drivable pixels have value 127 or 191 (both mean drivable) and 0 means background.

**Download accounting for this agent:** about 30 MB in total. That is about 17.4 MB of central directories, about 12.6 MB of compressed PNG members, and a few kB of READMEs, model cards and `test_onnx.py` fetched for preprocessing details. This is well under the 1 GB cap.

## Review fixes (reviewer agent, 2026-09-25)

- TwinLiteNet+ weights row: the wording now says the weights are hosted on the author's Google Drive and linked from the MIT repo's README; they are not shipped in the repo. There is still no separate weights license, and the BDD100K inheritance is unchanged.
- The comma10k-segnet row now names `timm` (Apache-2.0), which the `tu-efficientnet_b2` encoder needs at runtime.
- The sizes and sha256 prefixes of all six files were recomputed and match the table. The licenses match the prior-research verification notes: TLNP+ and YOLOP are MIT code with BDD100K-trained weights, so they need a UC Berkeley OTL license for commercial use; comma10k-segnet is MIT weights on MIT data.
