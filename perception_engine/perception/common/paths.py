"""Filesystem locations used by every perception block.

Everything is relative to this checkout (the `perception_engine/` folder) unless an environment
variable overrides it, so the engine runs from a fresh clone on any machine:

    KSR_MODELS_DIR       weights (HF cache, models/<block>/...)   default: perception_engine/models
    KSR_DATA_DIR         datasets (bdd100k/, kitti/)              default: perception_engine/data
    KSR_OUTPUTS_DIR      eval / demo outputs                      default: perception_engine/outputs
    KSR_THIRD_PARTY_DIR  vendored upstream code                   default: perception_engine/perception/third_party

Relative values are taken relative to the current working directory.

Library caches follow the models dir. `apply_cache_env()` (called when this module is imported, and
`perception/__init__.py` imports it first) sets these with `os.environ.setdefault`, so a value that is
already set always wins:

    HF_HUB_CACHE -> <models>/huggingface/hub      (Hugging Face snapshots; HF token/config stay in HF_HOME)
    TORCH_HOME   -> <models>/torch                (torch.hub / timm checkpoints)

`huggingface_hub` reads HF_HUB_CACHE once, when it is first imported. Import `perception` (any block)
before `huggingface_hub` / `transformers`, or set HF_HUB_CACHE yourself. The venv created by
scripts/setup_env.ps1 / setup_env.sh also sets these defaults at interpreter start (a .pth hook).
"""
from __future__ import annotations

import os
from pathlib import Path

PACKAGE_ROOT = Path(__file__).resolve().parents[1]      # perception_engine/perception
PROJECT_ROOT = PACKAGE_ROOT.parent                        # perception_engine


def _env_dir(name: str, default: Path) -> Path:
    value = os.environ.get(name, "").strip()
    return Path(value).expanduser().absolute() if value else default


MODELS_ROOT = _env_dir("KSR_MODELS_DIR", PROJECT_ROOT / "models")
DATA_DIR = _env_dir("KSR_DATA_DIR", PROJECT_ROOT / "data")
DATA_ROOT = DATA_DIR / "bdd100k"                          # BDD100K sample set (scripts/fetch_bdd_samples.py)
KITTI_ROOT = DATA_DIR / "kitti"                           # KITTI depth sample (fetch_bdd_samples.py --kitti)
OUTPUTS_ROOT = _env_dir("KSR_OUTPUTS_DIR", PROJECT_ROOT / "outputs")
THIRD_PARTY = _env_dir("KSR_THIRD_PARTY_DIR", PACKAGE_ROOT / "third_party")   # scripts/fetch_third_party.py
TOOLS_ROOT = PROJECT_ROOT / "tools"                       # tools/bdd (range-extraction data tools)


def resolve_data_path(path: str | os.PathLike) -> Path:
    """A clip / file path from a command line or a tool default such as `data/bdd100k/videos/val/<clip>.mov`:
    used as-is when it is absolute or exists (relative to the current directory); otherwise a leading `data/` is
    mapped onto DATA_DIR (so KSR_DATA_DIR relocates those defaults), and anything else is taken relative to
    perception_engine/."""
    p = Path(path).expanduser()
    if p.is_absolute() or p.exists():
        return p
    if p.parts and p.parts[0] == "data":
        return DATA_DIR.joinpath(*p.parts[1:])
    return PROJECT_ROOT / p


def portable_path(value: str | os.PathLike) -> str:
    """A path for files that get committed (results/*.json): `<models>/...`, `<data>/...`, `<outputs>/...`,
    `<third_party>/...` or relative to perception_engine/, with forward slashes, so no machine path is recorded.
    Anything else absolute keeps only its file name (`<external>/name`); relative paths are returned unchanged."""
    p = Path(value)
    if not p.is_absolute():
        return str(value)
    for tag, root in (("<models>", MODELS_ROOT), ("<data>", DATA_DIR), ("<outputs>", OUTPUTS_ROOT),
                      ("<third_party>", THIRD_PARTY), ("<models>/huggingface/hub", Path(hf_cache_dir()))):
        try:
            return f"{tag}/{p.resolve().relative_to(root.resolve()).as_posix()}"
        except (ValueError, OSError):
            continue
    try:
        return p.resolve().relative_to(PROJECT_ROOT.resolve()).as_posix()
    except (ValueError, OSError):
        return f"<external>/{p.name}"


def portable_paths(obj):
    """Recursively applies portable_path to every string in a JSON-like object that looks like an absolute path."""
    if isinstance(obj, dict):
        return {k: portable_paths(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [portable_paths(v) for v in obj]
    if isinstance(obj, str) and "\n" not in obj and len(obj) < 1024 and Path(obj).is_absolute():
        return portable_path(obj)
    return obj


def hf_cache_dir() -> Path:
    """Directory Hugging Face downloads go to (HF_HUB_CACHE if set, else <models>/huggingface/hub)."""
    return Path(os.environ.get("HF_HUB_CACHE") or MODELS_ROOT / "huggingface" / "hub")


def apply_cache_env() -> None:
    """Point HF_HUB_CACHE / TORCH_HOME into the models dir unless they are already set."""
    os.environ.setdefault("HF_HUB_CACHE", str(MODELS_ROOT / "huggingface" / "hub"))
    os.environ.setdefault("TORCH_HOME", str(MODELS_ROOT / "torch"))


apply_cache_env()
