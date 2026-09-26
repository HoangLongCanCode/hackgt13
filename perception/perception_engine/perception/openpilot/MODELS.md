# openpilot block: weights and vendored code

Knuckle Sandwich Robotics Inc. (KSR) HackGT 13 prototype, Perception Engine, EXPERIMENTAL block (plan §10, §9/§18, §14).

## Weights used by the block

| Name | Local path | Original URL | Bytes | sha256 (first 16 hex) | Code license | Weights license | Dataset-license inheritance |
|---|---|---|---|---|---|---|---|
| openpilot v0.11.1 `driving_vision.onnx` (FP16, opset 20, inputs `img`/`big_img` uint8 [1,12,128,256], output [1,1576]) | `models\openpilot\driving_vision.onnx` | https://media.githubusercontent.com/media/commaai/openpilot/v0.11.1/selfdrive/modeld/models/driving_vision.onnx | 46,877,473 | `ee29ee5bce84d1ce` (full: `ee29ee5bce84d1ce23e9ff381280de9b4e4d96d2934cd751740354884e112c66`, matches the git-LFS pointer at tag v0.11.1) | MIT (openpilot `LICENSE`, Copyright (c) 2018 Comma.ai). The README adds an indemnification clause and "ALPHA QUALITY SOFTWARE FOR RESEARCH PURPOSES ONLY. THIS IS NOT A PRODUCT." | MIT (the ONNX files ship in the MIT repo via git-LFS) | None known. Trained on comma.ai's own fleet data ("fully trained using a learned simulator" since v0.11.0). comma's internal data terms were not independently verified |

`model_checkpoint` in the ONNX metadata: `6a7d09ad-bcc9-43bc-916d-29287e60cee2/200`. The `output_slices` metadata was
decoded with a restricted unpickler that only allows `builtins.slice`. The slices are hard-coded in `op_geometry.OUTPUT_SLICES`.

Not downloaded, but present at the same tag: `driving_policy.onnx` (14,060,847 B; temporal policy that outputs the plan and
desire; not needed for perception and not allowed to drive anything under plan §38) and `big_driving_vision.onnx`
(296,203,378 B; LFS oid `1f0cab50...`; untested).

## Weights used only by the evaluation baseline (`eval_bdd.py`)

| Name | Local path | Original URL | Bytes | sha256 (first 16) | Code license | Weights license | Dataset-license inheritance |
|---|---|---|---|---|---|---|---|
| `dronefreak/bdd100k-yolo26s` `best.pt` (snapshot `b11da17c`) | `models\huggingface\hub\models--dronefreak--bdd100k-yolo26s\snapshots\b11da17c1149b60c0f067eee6588f1780507e064\best.pt` (already cached by the detection block, **not** re-downloaded) | https://huggingface.co/dronefreak/bdd100k-yolo26s/resolve/main/best.pt | 20,340,357 | `47a879aff374b220` (per `perception\detection\MODELS.md`) | Ultralytics AGPL-3.0 | AGPL-3.0 (HF card) | **BDD100K**: research / non-commercial only. Base model pretrained on Objects365 (academic only). Research and demo use only |

This detector is only a yardstick for the lead-distance plausibility check (closest in-path vehicle plus flat-ground geometry).
`driving_model.py` does not depend on it.

## Vendored source (no weights)

| What | Where | Source | License |
|---|---|---|---|
| Sparse checkout of openpilot **v0.11.1** (commit `4df40d2`): `selfdrive/modeld/*.py`, `common/transformations/*.py`, `selfdrive/locationd/calibrationd.py`, `selfdrive/ui/onroad/{model_renderer,augmented_road_view}.py`, `cereal/log.capnp`, `LICENSE`, `RELEASES.md` | `perception\third_party\openpilot\` | `git clone --depth 1 --branch v0.11.1 --filter=blob:none --sparse https://github.com/commaai/openpilot.git` (LFS smudge skipped, so the `.onnx` files there are pointer stubs) | MIT |

`perception\openpilot\op_geometry.py` holds small numpy ports of `get_warp_matrix`, `rot_from_euler`, `get_calib_from_vp`,
the medmodel and sbigmodel intrinsics, `X_IDXS`, and the output parser. Each port keeps MIT attribution.
`tests_geometry.py` checks them against the vendored originals (max relative deviation 2.3e-16).

## Download accounting

| Item | Bytes |
|---|---|
| `driving_vision.onnx` | 46,877,473 |
| openpilot sparse clone (trees plus the checked-out blobs) | about 0.5 MB |
| **Total** | **about 47.4 MB** (the cap is 1 GB) |
