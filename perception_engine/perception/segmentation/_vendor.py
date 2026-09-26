"""Loaders for vendored model code (no pip installs; see README "Vendored code").

Both upstream repos have package __init__ files that pull in things we cannot or
should not install (PIDNet: a top-level `models` package name; EfficientViT:
triton, omegaconf, segment_anything, training utilities). Instead of editing the
vendored files, we register synthetic parent packages whose __path__ points at
the real folders, so only the inference modules we need are executed.
"""
from __future__ import annotations

import importlib
import sys
import types
from pathlib import Path

from perception.common.paths import THIRD_PARTY  # noqa: E402  (PERCEPTION_THIRD_PARTY_DIR overrides)
PIDNET_DIR = THIRD_PARTY / "PIDNet"
EFFICIENTVIT_DIR = THIRD_PARTY / "efficientvit"


def _pkg(name: str, path: Path | None) -> types.ModuleType:
    if name in sys.modules:
        return sys.modules[name]
    mod = types.ModuleType(name)
    mod.__path__ = [str(path)] if path is not None else []  # namespace-like package
    mod.__package__ = name
    sys.modules[name] = mod
    return mod


def load_pidnet_module():
    """Return the vendored `pidnet.py` module (github.com/XuJiacong/PIDNet, MIT)."""
    if not (PIDNET_DIR / "models" / "pidnet.py").exists():
        raise FileNotFoundError(
            f"PIDNet code missing in {PIDNET_DIR}; run from perception_engine/: "
            "python scripts/fetch_third_party.py PIDNet")
    _pkg("_vendored_pidnet", PIDNET_DIR / "models")
    return importlib.import_module("_vendored_pidnet.pidnet")


def load_efficientvit_seg_module():
    """Return `efficientvit.models.efficientvit.seg` (github.com/mit-han-lab/efficientvit, Apache-2.0).

    Stubs: `efficientvit.models.nn.triton_rms_norm` (needs triton, which has no
    Windows wheel; only used by the 'trms2d' norm, which the seg models do not use)
    and `efficientvit.apps.trainer.run_config.Scheduler` (training-time
    drop-path schedule; unused at inference).
    """
    root = EFFICIENTVIT_DIR / "efficientvit"
    if not (root / "models" / "efficientvit" / "seg.py").exists():
        raise FileNotFoundError(
            f"EfficientViT code missing in {EFFICIENTVIT_DIR}; run from perception_engine/: "
            "python scripts/fetch_third_party.py efficientvit")
    _pkg("efficientvit", root)
    _pkg("efficientvit.models", root / "models")
    _pkg("efficientvit.models.efficientvit", root / "models" / "efficientvit")
    _pkg("efficientvit.apps", root / "apps")
    _pkg("efficientvit.apps.trainer", root / "apps" / "trainer")
    if "efficientvit.apps.trainer.run_config" not in sys.modules:
        rc = types.ModuleType("efficientvit.apps.trainer.run_config")

        class Scheduler:  # noqa: D401 - stub
            PROGRESS = 0

        rc.Scheduler = Scheduler
        sys.modules[rc.__name__] = rc
    if "efficientvit.models.nn.triton_rms_norm" not in sys.modules:
        tr = types.ModuleType("efficientvit.models.nn.triton_rms_norm")

        class TritonRMSNorm2dFunc:  # stub; raises if a model actually uses it
            @staticmethod
            def apply(*_a, **_k):
                raise RuntimeError("TritonRMSNorm2d needs triton (not available on Windows)")

        tr.TritonRMSNorm2dFunc = TritonRMSNorm2dFunc
        tr.__all__ = ["TritonRMSNorm2dFunc"]
        sys.modules[tr.__name__] = tr
    seg = importlib.import_module("efficientvit.models.efficientvit.seg")
    norm = importlib.import_module("efficientvit.models.nn.norm")
    return seg, norm
