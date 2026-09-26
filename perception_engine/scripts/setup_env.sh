#!/usr/bin/env bash
# One-shot setup of the perception engine from a fresh clone (Git Bash on Windows, or Linux).
#
# Creates perception_engine/.venv (Python 3.13), installs torch 2.14.0+cu130 / torchvision 0.29.0+cu130 from the
# PyTorch CUDA 13.0 index, then requirements.txt, writes a .pth hook that keeps caches inside the project, points
# the Ultralytics settings at models/, clones the vendored upstream code (scripts/fetch_third_party.py), downloads
# the realtime default weights (scripts/download_models.py --minimal) and runs scripts/verify_env.py.
# Every step is idempotent. Same steps as scripts/setup_env.ps1 (see SETUP.md).
#
#   bash scripts/setup_env.sh                       # from perception_engine/ (or anywhere)
#   bash scripts/setup_env.sh --all-models --with-data
#   bash scripts/setup_env.sh --dry-run             # print the steps only
#   PYTHON=/path/to/python3.13 bash scripts/setup_env.sh
#
# Options: --all-models  every alternative backend's weights (download_models.py --all, ~3.0 GB)
#          --with-data   BDD100K sample set (fetch_bdd_samples.py, ~0.5 GB; research / non-commercial use only)
#          --skip-third-party | --skip-models | --skip-verify | --dry-run
set -euo pipefail

TORCH_INDEX="https://download.pytorch.org/whl/cu130"
TORCH_PKGS=("torch==2.14.0+cu130" "torchvision==0.29.0+cu130")

ALL_MODELS=0; WITH_DATA=0; SKIP_TP=0; SKIP_MODELS=0; SKIP_VERIFY=0; DRY=0
for arg in "$@"; do
  case "$arg" in
    --all-models) ALL_MODELS=1 ;;
    --with-data) WITH_DATA=1 ;;
    --skip-third-party) SKIP_TP=1 ;;
    --skip-models) SKIP_MODELS=1 ;;
    --skip-verify) SKIP_VERIFY=1 ;;
    --dry-run) DRY=1 ;;
    -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
    *) echo "unknown option: $arg (see --help)" >&2; exit 2 ;;
  esac
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"     # perception_engine/
cd "$ROOT"
VENV="$ROOT/.venv"
if [ -x "$VENV/Scripts/python.exe" ] || { [ ! -e "$VENV/bin/python" ] && [ "${OS:-}" = "Windows_NT" ]; }; then
  VPY="$VENV/Scripts/python.exe"          # Windows layout
else
  VPY="$VENV/bin/python"                  # Linux / macOS layout
fi

step() { printf '\n==> %s\n' "$*"; }
run() {
  printf '    %s\n' "$*"
  if [ "$DRY" = 1 ]; then return 0; fi
  "$@"
}

# ---------------------------------------------------------------- 1. base interpreter + venv
step "Python 3.13 venv in $VENV"
if [ -x "$VPY" ]; then
  echo "    venv exists: $VPY"
else
  BASE=()
  if [ -n "${PYTHON:-}" ]; then BASE=("$PYTHON")
  elif command -v py >/dev/null 2>&1; then BASE=(py -3.13)
  elif command -v python3.13 >/dev/null 2>&1; then BASE=(python3.13)
  elif command -v python3 >/dev/null 2>&1; then BASE=(python3)
  elif command -v python >/dev/null 2>&1; then BASE=(python)
  else echo "No Python found: install CPython 3.13 x64 or set PYTHON=/path/to/python" >&2; exit 1
  fi
  if [ "$DRY" = 0 ]; then
    VER="$("${BASE[@]}" -c "import sys, struct; print('%d.%d %d' % (sys.version_info[0], sys.version_info[1], struct.calcsize('P') * 8))" | tr -d '\r')"
    if [ "$VER" != "3.13 64" ]; then
      echo "need CPython 3.13 64-bit, got '$VER' from ${BASE[*]} (set PYTHON=...)" >&2; exit 1
    fi
  fi
  run "${BASE[@]}" -m venv "$VENV"
fi

# ---------------------------------------------------------------- 2. torch (CUDA 13.0 build) + requirements
step "torch + torchvision from $TORCH_INDEX (about 2 GB the first time)"
HAVE=""
if [ "$DRY" = 0 ] && [ -x "$VPY" ]; then
  HAVE="$("$VPY" -c "import importlib.metadata as m; print(m.version('torch'), m.version('torchvision'))" 2>/dev/null | tr -d '\r' || true)"
fi
if [ "$HAVE" = "2.14.0+cu130 0.29.0+cu130" ]; then
  echo "    already installed: torch 2.14.0+cu130, torchvision 0.29.0+cu130"
else
  run "$VPY" -m pip install --progress-bar off "${TORCH_PKGS[@]}" --index-url "$TORCH_INDEX"
fi
step "requirements.txt"
run "$VPY" -m pip install --progress-bar off -r requirements.txt

# ---------------------------------------------------------------- 3. project-local caches (.pth hook) + Ultralytics settings
step "project-local cache hook (perception_engine_env.pth in the venv's site-packages)"
# Runs at every start of this venv's interpreter; setdefault, so explicitly set variables always win.
PTH='import os, sys, pathlib; _r = pathlib.Path(sys.prefix).parent; _m = pathlib.Path(os.environ.get("PERCEPTION_MODELS_DIR") or _r / "models"); [os.environ.setdefault(_k, str(_v)) for _k, _v in (("YOLO_CONFIG_DIR", _r / ".cache" / "ultralytics"), ("HF_HUB_CACHE", _m / "huggingface" / "hub"), ("TORCH_HOME", _m / "torch"), ("YOLO_AUTOINSTALL", "False"))]; [pathlib.Path(os.environ[_k]).mkdir(parents=True, exist_ok=True) for _k in ("YOLO_CONFIG_DIR", "HF_HUB_CACHE", "TORCH_HOME")]'
if [ "$DRY" = 0 ]; then
  SITE="$("$VPY" -c "import sysconfig; print(sysconfig.get_paths()['purelib'])" | tr -d '\r')"
  printf '%s\n' "$PTH" > "$SITE/perception_engine_env.pth"
  echo "    -> $SITE/perception_engine_env.pth"
else
  echo "    -> <venv site-packages>/perception_engine_env.pth"
fi
step "Ultralytics settings (weights_dir=models, runs_dir=outputs/runs, sync=False)"
run "$VPY" -c "from pathlib import Path; from ultralytics import settings; r = Path.cwd(); settings.update({'weights_dir': str(r / 'models'), 'runs_dir': str(r / 'outputs' / 'runs'), 'datasets_dir': str(r / 'data'), 'sync': False}); print('    ultralytics settings:', settings.file)"

# ---------------------------------------------------------------- 4. vendored code, weights, data
if [ "$SKIP_TP" = 0 ]; then
  step "vendored upstream code -> perception/third_party (git, pinned commits)"
  run "$VPY" scripts/fetch_third_party.py
fi
if [ "$SKIP_MODELS" = 0 ]; then
  SEL="--minimal"; [ "$ALL_MODELS" = 1 ] && SEL="--all"
  step "model weights -> models/ ($SEL)"
  run "$VPY" scripts/download_models.py "$SEL"
fi
if [ "$WITH_DATA" = 1 ]; then
  step "BDD100K sample set -> data/bdd100k (research / non-commercial use only)"
  run "$VPY" scripts/fetch_bdd_samples.py
fi

# ---------------------------------------------------------------- 5. verify
if [ "$SKIP_VERIFY" = 0 ]; then
  step "environment check (scripts/verify_env.py: torch CUDA, ORT CUDA EP, Ultralytics, ...)"
  run "$VPY" scripts/verify_env.py
fi
printf '\nDone. Activate with: source .venv/Scripts/activate  (Linux: source .venv/bin/activate)\n'
printf 'Run the server:      %s -m perception.realtime.server --help\n' "${VPY#"$ROOT"/}"
