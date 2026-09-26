"""Perception Engine (AI Spatial Driving Copilot). See perception_engine/README.md.

Importing any `perception.*` module first runs `perception.common.paths`, which resolves the models /
data / outputs / third_party folders (PERCEPTION_MODELS_DIR, PERCEPTION_DATA_DIR, ... override them) and points the
Hugging Face / torch.hub caches into the models folder unless HF_HUB_CACHE / TORCH_HOME are already set.
"""
from perception.common import paths as _paths  # noqa: F401  (side effect: cache env defaults)
