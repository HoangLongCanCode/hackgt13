# Segmentation block: model weights and vendored code

Perception prototype, plan §13. Every weight file below was downloaded on 2026-09-25 from the original URL listed. The sha256 column shows the first 16 hex characters of the file hash.

**License summary:** every checkpoint here was trained on **Cityscapes**. The Cityscapes terms forbid commercial use of the dataset "or any derivative work", and trained models count as derivative works. Whatever the code license says, all of these weights are for **demo, research and evaluation only**. Product use needs legal review, or a Level-2 retrain on commercially licensed data such as comma10k (MIT). BDD100K images and labels, used here only for evaluation, are also research and non-commercial only.

## Weights

| Backend key | File (local path) | Original URL | Bytes | sha256[:16] | Code license | Weights license | Dataset-license inheritance |
|---|---|---|---|---|---|---|---|
| `pidnet_s` | `models/segmentation/PIDNet_S_Cityscapes_val.pt` | https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/pidnet/v2/PIDNet_S_Cityscapes_val.pt (the Qualcomm AI Hub re-host of the upstream `PIDNet_S_Cityscapes_val.pt`) | 31,145,857 | `b51aa935bdb64a07` | MIT (github.com/XuJiacong/PIDNet) | MIT (upstream; the Qualcomm card points to the upstream license) | Cityscapes, non-commercial |
| `efficientvit_b0` | `models/segmentation/efficientvit/efficientvit_seg_b0_cityscapes.pt` | https://huggingface.co/han-cai/efficientvit-seg/resolve/main/efficientvit_seg_b0_cityscapes.pt (repo sha cf3ccaf9) | 2,924,013 | `923d6fdd5e93640c` | Apache-2.0 (github.com/mit-han-lab/efficientvit) | Apache-2.0 (HF tag) | Cityscapes, non-commercial |
| `efficientvit_b1` | `models/segmentation/efficientvit/efficientvit_seg_b1_cityscapes.pt` | https://huggingface.co/han-cai/efficientvit-seg/resolve/main/efficientvit_seg_b1_cityscapes.pt | 19,391,716 | `80c305bb2a94b921` | Apache-2.0 | Apache-2.0 (HF tag) | Cityscapes, non-commercial |
| `efficientvit_b2` | `models/segmentation/efficientvit/efficientvit_seg_b2_cityscapes.pt` | https://huggingface.co/han-cai/efficientvit-seg/resolve/main/efficientvit_seg_b2_cityscapes.pt | 61,562,649 | `c5d8b1a319d1abce` | Apache-2.0 | Apache-2.0 (HF tag) | Cityscapes, non-commercial |
| `segformer_b0` | HF cache `models/huggingface/hub/models--nvidia--segformer-b0-finetuned-cityscapes-1024-1024/` (`pytorch_model.bin`, config.json, preprocessor_config.json) | https://huggingface.co/nvidia/segformer-b0-finetuned-cityscapes-1024-1024 (revision 21b3847f) | 14,957,601 | `027ed78d8ff9c535` | transformers modeling code: Apache-2.0. Original NVlabs code: **NVIDIA Source Code License, research/evaluation only** | **NVIDIA Source Code License (non-commercial: research or evaluation only)**. The HF card says "license: other" and links to it | Cityscapes, non-commercial |
| `segformer_b2` | HF cache `models/huggingface/hub/models--nvidia--segformer-b2-finetuned-cityscapes-1024-1024/` | https://huggingface.co/nvidia/segformer-b2-finetuned-cityscapes-1024-1024 (revision d633b207) | 109,597,961 | `584d72ace8c17c56` | as above | **NVIDIA non-commercial** | Cityscapes, non-commercial |

The two SegFormer backends are flagged: NVIDIA's license limits both the weights and the derivative works to non-commercial research or evaluation. Keep them out of anything beyond the hackathon demo.

## Vendored code (git clone --depth 1, not pip-installed, not modified)

| Folder | Source | Commit | License | What is used |
|---|---|---|---|---|
| `perception/third_party/PIDNet/` | https://github.com/XuJiacong/PIDNet | `4c158cf24ce432f0a8cb43364fae38d93cee0dc3` | MIT | `models/pidnet.py` and `models/model_utils.py` only, loaded as a synthetic package by `_vendor.py` |
| `perception/third_party/efficientvit/` | https://github.com/mit-han-lab/efficientvit | `de7d7733cc0329f391b33f1f459271562ec27bd5` | Apache-2.0 | `efficientvit/models/{efficientvit/backbone.py, efficientvit/seg.py, nn/*, utils/*}`. `_vendor.py` stubs out `triton_rms_norm`, which needs triton (no Windows wheel and unused by the seg models), and the training-only `Scheduler` |

## Considered, not downloaded

- **EoMT-L Cityscapes** (`tue-mps/cityscapes_semantic_eomt_large_1024`, MIT; `model.safetensors` is 1,276,175,488 B). Skipped: this one file is larger than the 1 GB per-agent download cap. The research track also puts it at 2 to 4 FPS on this GPU, so it would only ever be an offline teacher.
- **TwinLiteNet+ / YOLOP** drivable-area models. These belong to the drivable/lanes blocks and were not used here.

## Evaluation data fetched by this block

`outputs/segmentation/devset/images/` holds 120 BDD100K val keyframes (7.9 MB), fetched with `tools/bdd/bdd_remote_zip.py --source solesensei get --from-file outputs/segmentation/devset/members.txt`. The source is the HF `Xoner1/bdd100k-client` `BDD100k.zip` mirror, and CRC32 was verified. `dev_labels.json` holds their 2018 labels. They were picked with seed 7 from the val images **not** in the 250-image eval subset, and each has a lane-label vanishing point. They were used only to tune the horizon estimator, so the 250-image numbers stay held out. They fall under the BDD100K license: research and non-commercial only.
