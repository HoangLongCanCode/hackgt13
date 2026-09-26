# Perception Engine setup (Windows)

This is the detailed setup for the perception engine on a Windows laptop with an
NVIDIA GPU. [README.md](README.md) has the short version and the run commands. Every command below runs from
`perception_engine/` unless it says otherwise.

The pins were verified on 2026-09-25/26 with this configuration:

- Windows 11 Home 10.0.26200
- NVIDIA GeForce RTX 5060 Laptop GPU: Blackwell, compute capability 12.0 (sm_120), 8 GB
- NVIDIA driver 616.56
- CPython 3.13.14 x64

## Contents

1. [Prerequisites](#1-prerequisites)
2. [One-shot setup](#2-one-shot-setup)
3. [Manual setup, step by step](#3-manual-setup-step-by-step)
4. [Where things live, and how to move them](#4-where-things-live-and-how-to-move-them)
5. [GPU notes](#5-gpu-notes)
6. [Checking the install](#6-checking-the-install)
7. [Troubleshooting](#7-troubleshooting)
8. [Updating the pins](#8-updating-the-pins)

## 1. Prerequisites

| Need | Why / how to check |
|---|---|
| NVIDIA driver with CUDA 13.0 support (R580 or newer) | The torch and onnxruntime wheels bundle the CUDA 13 runtime, so no CUDA Toolkit install is needed, only a new enough driver. `nvidia-smi` must show `CUDA Version: 13.0` or higher in its header |
| CPython **3.13 x64** | Every pinned wheel is `cp313-win_amd64`. Use the python.org installer or the Microsoft Store build. `py -3.13 --version` or `python --version` should print 3.13.x. 32-bit Python and 3.14 are not supported by the pins |
| Git for Windows | `fetch_third_party.py` clones the vendored upstream code. `git --version` |
| Disk | `.venv` about 4.1 GB; `models/` 1.4 GB (`--minimal`) or 3.0 GB (`--all`); `perception/third_party/` about 0.3 GB (310 MB measured); `data/` about 0.7 GB (+0.14 GB with `--kitti`); `outputs/` grows with evals. Plan for about 10 GB |
| Network | About 2.6 GB of wheels (torch alone is about 2 GB) plus the weights. Hugging Face, GitHub, download.pytorch.org, storage.googleapis.com, the Qualcomm AI Hub S3 bucket, drive.usercontent.google.com and (for `--kitti`) s3.eu-central-1.amazonaws.com must be reachable |
| Optional: `adb` (Android platform-tools) | Only for the USB link to the tablet: `adb reverse tcp:8765 tcp:8765` |

No administrator rights are needed. Nothing is installed system-wide, and no global settings are changed.

### PowerShell execution policy

Windows client editions default to the `Restricted` policy, which blocks every `.ps1` script. Under `RemoteSigned`,
scripts extracted from a downloaded zip are blocked too. Do not change the machine policy. Either:

- bypass it for one run: `powershell -ExecutionPolicy Bypass -File scripts\setup_env.ps1`, or
- bypass it for the current PowerShell window only: `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass`.
  This is also what makes `.venv\Scripts\Activate.ps1` usable in that window.

You never have to activate the venv. Calling `.venv\Scripts\python.exe` directly works the same way.

## 2. One-shot setup

```powershell
git clone https://github.com/HoangLongCanCode/hackgt13.git
cd hackgt13\perception_engine
powershell -ExecutionPolicy Bypass -File scripts\setup_env.ps1
```

| Option | Effect |
|---|---|
| `-AllModels` | Downloads every alternative backend's weights too (`download_models.py --all`) |
| `-WithData` | Fetches the BDD100K sample test set (`fetch_bdd_samples.py`; research use only) |
| `-Python <exe>` | The base interpreter for the venv. The default is `py -3.13`, then `python` |
| `-SkipThirdParty`, `-SkipModels`, `-SkipVerify` | Skip that step |
| `-DryRun` | Prints the steps and changes nothing |

In Git Bash: `bash scripts/setup_env.sh [--all-models] [--with-data] [--dry-run]`. Set `PYTHON=...` to pick the
interpreter. The steps are identical to the PowerShell script, and the bash script also works on Linux.

The script is idempotent: an existing `.venv`, an installed torch of the right build, cloned repos and downloaded
weights are all detected and skipped. If a step fails, fix the cause and re-run the same command.

## 3. Manual setup, step by step

These are the same steps the script runs.

```powershell
# 1. venv (Python 3.13 x64)
py -3.13 -m venv .venv

# 2. torch FIRST, from the PyTorch CUDA 13.0 index (PyPI's Windows torch is CPU-only)
.venv\Scripts\python.exe -m pip install torch==2.14.0+cu130 torchvision==0.29.0+cu130 --index-url https://download.pytorch.org/whl/cu130

# 3. everything else (pinned; torch is already satisfied, so nothing pulls the CPU build)
.venv\Scripts\python.exe -m pip install -r requirements.txt
```

Step 4 writes the cache hook. This one line in `.venv\Lib\site-packages\perception_engine_env.pth` runs at every start of
the venv's interpreter. It uses `setdefault`, so a variable you set yourself always wins. It sets:

| Variable | Value |
|---|---|
| `YOLO_CONFIG_DIR` | `<project>\.cache\ultralytics` |
| `HF_HUB_CACHE` | `<models>\huggingface\hub` |
| `TORCH_HOME` | `<models>\torch` |
| `YOLO_AUTOINSTALL` | `False` (Ultralytics must never pip-install into the venv) |

`<project>` is `perception_engine\`. `<models>` is `PERCEPTION_MODELS_DIR`, or `<project>\models`. `setup_env.ps1` contains
the exact line. The `perception` package applies the same `HF_HUB_CACHE` and `TORCH_HOME` defaults when it is imported
(`perception/common/paths.py`), so the layout is right even without the hook. In that case, import `perception` before
`huggingface_hub` / `transformers`.

The remaining steps:

```powershell
# 5. Ultralytics settings: weights_dir = models\, runs_dir = outputs\runs, no usage analytics
.venv\Scripts\python.exe -c "from pathlib import Path; from ultralytics import settings; r = Path.cwd(); settings.update({'weights_dir': str(r / 'models'), 'runs_dir': str(r / 'outputs' / 'runs'), 'datasets_dir': str(r / 'data'), 'sync': False})"

# 6. vendored upstream code at pinned commits -> perception\third_party\ (+ THIRD_PARTY_NOTICES.md)
.venv\Scripts\python.exe scripts\fetch_third_party.py            # --minimal: only DA3 + addict + TwinLiteNet+; --check: verify

# 7. weights -> models\ (pinned revisions, size + sha256 checked)
.venv\Scripts\python.exe scripts\download_models.py              # --minimal (default) | --all | --list | --dry-run | --verify

# 8. check the environment
.venv\Scripts\python.exe scripts\verify_env.py

# 9. optional: BDD100K sample set -> data\bdd100k (and KITTI -> data\kitti)
.venv\Scripts\python.exe scripts\fetch_bdd_samples.py            # --lights | --kitti | --dry-run | --only GROUP...
```

What each fetch script does:

- **`fetch_third_party.py`** does `git init`, then `git fetch --depth 1 origin <sha>`, then a detached checkout, for
  each of Depth-Anything-3, addict, TwinLiteNetPlus, PIDNet, efficientvit and openpilot. openpilot is a
  `blob:none` partial clone with a sparse checkout of the dozen source files the openpilot block uses, and Git LFS is
  skipped. A repo already at its commit is left alone, and one with local changes is reported, not overwritten.
  `--list` prints the pins and licences.
- **`download_models.py`** fetches from these sources, verifies each file, and skips files that are already there:
  - Hugging Face, at pinned commits
  - GitHub (Ultralytics release assets, the hustvl/YOLOP raw file, the openpilot LFS media URL)
  - Roboflow's GCS bucket
  - the Qualcomm AI Hub S3 bucket
  - Google Drive: TwinLiteNet+. The script handles Drive's "can't scan for viruses" confirm page itself; gdown is not needed.

  `--minimal` covers what `perception/config_realtime.yaml` runs, plus `yolo26n.pt` for `verify_env.py`. The one
  derived file, the uint8 Autoware light classifier, is generated locally by the same function the traffic block uses.
- **`fetch_bdd_samples.py`** pulls each file out of the public zip mirrors with HTTP Range requests (it never
  downloads a whole archive). Offsets and CRC32s are pinned in `tools/bdd/sample_members.csv`. It then rebuilds the
  derived labels (eval subset JSON/COCO, drivable masks) locally. Files that exist are skipped.

All three can be interrupted and re-run. `download_models.py` also resumes partial files (`*.part`).

**Hugging Face token (optional).** Everything is public. Setting `HF_TOKEN` only raises the rate limits and silences
the "unauthenticated requests" warning. Never commit a token or a `.env` file.

## 4. Where things live, and how to move them

| Folder | Created by | Override |
|---|---|---|
| `.venv\` | step 1 | Any Python 3.13 venv with the same packages works: call its `python.exe` from `perception_engine\` |
| `models\` | `download_models.py` | `PERCEPTION_MODELS_DIR` (or `--models-dir`). `HF_HUB_CACHE` / `TORCH_HOME` follow it unless you set them |
| `data\` | `fetch_bdd_samples.py` | `PERCEPTION_DATA_DIR` (or `--data-dir`). The realtime server's clip folders (`<data>\bdd100k\videos`, `<data>\sim_videos`) and the `data/...` defaults of `ws_probe`, `bench_lanes`, `subscribers` and the tests follow it too |
| `outputs\` | evals, demos | `PERCEPTION_OUTPUTS_DIR` |
| `perception\third_party\` | `fetch_third_party.py` | `PERCEPTION_THIRD_PARTY_DIR` (or `--dest`) |
| `.cache\ultralytics\` | the `.pth` hook | `YOLO_CONFIG_DIR` |

All of these folders are gitignored. The data licences forbid committing `data\`, and the weights are too large for
git.

**Sharing one copy between checkouts.** Several clones, or a teammate's checkout next to yours, can share a copy in
either of two ways:

- Set the variables for the session: `$env:PERCEPTION_MODELS_DIR = "$HOME\perception-models"`. Set `$env:HF_HUB_CACHE` to match, if
  the venv's hook was written for another models folder.
- Use directory junctions. They need no admin rights:

  ```powershell
  cmd /c mklink /J models <existing models folder>
  cmd /c mklink /J data <existing data folder>
  ```

**Offline use.** After `download_models.py` has run, the engine needs no network. The realtime default config was
checked with `$env:HF_HUB_OFFLINE = "1"`, loaded from a freshly downloaded models folder. Keep that variable set when
offline: without it, a few loaders that resolve a branch instead of a pinned commit still ask the Hub for updates.
Those are the SegFormer backends and some eval scripts. They fall back to the cache when the Hub is unreachable, but
only after a timeout.

## 5. GPU notes

- **RTX 50xx (Blackwell, sm_120) needs a CUDA 12.8+ build of PyTorch** (`cu128`, `cu129`, `cu130`, ...). Older wheels
  (`cu126` and below, and PyPI's CPU-only Windows torch) have no sm_120 kernels. The symptom is `CUDA error: no kernel
  image is available for the device`, or `torch.cuda.is_available()` returning False. The pins use
  `torch 2.14.0+cu130`, and `verify_env.py` asserts that `sm_120` is in `torch.cuda.get_arch_list()`. `cu130` was
  chosen because onnxruntime-gpu 1.30 is a CUDA 13 build that can share torch's CUDA 13 DLLs.
- **onnxruntime-gpu needs `import torch` first.** It is installed *without* the `[cuda,cudnn]` extras, to avoid a
  second ~1 GB copy of CUDA. It finds cuBLAS and cuDNN only because importing torch puts `torch\lib` on the DLL search
  path. If `onnxruntime` is imported first, the CUDA execution provider fails to load and ORT **silently falls back to
  the CPU**. Look for `cublasLt64_13.dll ... is missing` / `Failed to create CUDAExecutionProvider` in the log, and
  `sess.get_providers()` returning only `['CPUExecutionProvider']`.
  - Every block imports torch before creating an ORT session. Do the same in your own scripts.
  - `TensorrtExecutionProvider` is listed, but TensorRT is not installed, so requesting it falls back.
  - Do not install `onnxruntime` or `onnxruntime-directml` next to `onnxruntime-gpu`: they all provide the same module.
- **Install torch before anything else.** Otherwise `pip install ultralytics` (or any torch-dependent package) into
  an empty venv pulls the CPU build. When you add packages later, keep torch pinned. For example, use a constraints
  file with `torch==2.14.0+cu130` and `torchvision==0.29.0+cu130`, then check that
  `python -c "import torch; print(torch.version.cuda)"` still prints `13.0`.
- **OpenCV** from pip is CPU-only (`cv2.cuda` reports 0 devices). That is expected; decoding and resizing stay on the
  CPU.
- **VRAM:** 8 GB is enough for the realtime default set. Measured here on a shared GPU (2026-09-26), the engine
  held about 1.5 GB after warm-up and 30 frames. The torch peak during warm-up (cuDNN autotuning) was about 5 GB, so
  keep other GPU jobs small while it starts. Other GPU jobs running at the same time slow everything down: the
  latencies in the block READMEs were measured on a shared GPU and are provisional.
- **Laptop power:** run on AC power with the NVIDIA GPU preferred for `python.exe` (Windows Graphics settings, or
  the NVIDIA Control Panel). On battery, Windows may throttle the GPU.
- **Symlinks:** without Windows Developer Mode, `huggingface_hub` stores cache files as copies instead of symlinks and
  prints a warning. That is harmless; `download_models.py` silences it.

## 6. Checking the install

```powershell
.venv\Scripts\python.exe scripts\verify_env.py                         # 8 checks, exit code 0 = all OK
.venv\Scripts\python.exe scripts\fetch_third_party.py --check           # vendored repos at their pinned commits
.venv\Scripts\python.exe scripts\download_models.py --all --dry-run --verify   # every weight present with the recorded sha256
.venv\Scripts\python.exe scripts\fetch_bdd_samples.py --lights --kitti --dry-run   # data complete
.venv\Scripts\python.exe -m perception.openpilot.tests_geometry         # quick CPU test of a vendored-code port
```

`verify_env.py` checks:

- the Python and cache variables
- torch CUDA: sm_120 in the arch list, an fp16 matmul and a cuDNN conv
- torchvision CUDA NMS
- onnxruntime with the CUDA EP actually used
- an Ultralytics YOLO26n predict on the GPU
- transformers and timm on the GPU
- the remaining imports

A healthy RTX 5060 Laptop reports about 32 TFLOPS fp16 and YOLO26n at about 10 ms at 640 px.

## 7. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `...setup_env.ps1 cannot be loaded because running scripts is disabled` | The execution policy; see section 1. Use `powershell -ExecutionPolicy Bypass -File ...` |
| `need CPython 3.13 64-bit` | Install Python 3.13 x64 and pass `-Python C:\path\to\python.exe`, or make sure `py -3.13` works |
| `torch.cuda.is_available()` is False / `no kernel image` | The CPU or a pre-cu128 torch got installed. Run `pip uninstall -y torch torchvision`, then step 2 again. Check that the driver's `nvidia-smi` shows CUDA >= 13.0 |
| `pip` wants to download torch while installing requirements | torch is missing or is the wrong build. Install it first (step 2) |
| ORT runs on the CPU, or `Failed to create CUDAExecutionProvider` | `import torch` before `import onnxruntime` (section 5) |
| `git is not on PATH` from `fetch_third_party.py` | Install Git for Windows and open a new terminal |
| `fetch by SHA failed` | Rare, if a server disallows fetching by SHA. The script falls back to a full fetch automatically |
| `Google Drive returned an HTML page without a download form` | Drive's download quota for that file is exhausted. Retry later, or download `large.pth` from the TwinLiteNet+ README's Drive folder in a browser into `models\lanes\twinlitenetplus\` (the sha256 is checked on the next run) |
| `sha256 ... != ...` from `download_models.py` | The upstream file changed, or the download is corrupt. The file was deleted, so re-run. If it persists, the upstream artefact really changed: compare with the block's `MODELS.md` before using it |
| `HTTP 429` or slow Hugging Face downloads | Rate limiting. Set `HF_TOKEN`, or wait. Re-running resumes |
| `pinned member check failed ... fetching the ... central directory` | A dataset mirror changed its archive layout. The script recovers by reading that zip's live central directory (a few MB) |
| Ultralytics writes `Ultralytics\settings.json` into the current folder | `YOLO_CONFIG_DIR` points to a folder whose parent does not exist. The hook creates it, so re-run step 4 |
| `ModuleNotFoundError: perception` | Run from `perception_engine\` (the package is imported from the current folder) |
| A block asks to download a whole Hugging Face repo | Its `refs/main` (or `refs/v4.0`) is missing from the cache. Re-run `download_models.py`, which writes the refs for the pinned commits |

## 8. Updating the pins

- **Python packages.** Install the new version into a copy of the venv. Then regenerate `requirements.txt` from
  `pip freeze`, leaving out torch, torchvision, setuptools and wheel, and keeping the group comments. Check it with
  these two commands. The first must report nothing to install; the second resolves a fresh environment from PyPI and
  the cu130 index as binary wheels only (metadata only, nothing is installed):

  ```powershell
  .venv\Scripts\python.exe -m pip install --dry-run -r requirements.txt
  .venv\Scripts\python.exe -m pip install --dry-run --ignore-installed --only-binary=:all: --extra-index-url https://download.pytorch.org/whl/cu130 torch==2.14.0+cu130 torchvision==0.29.0+cu130 -r requirements.txt
  ```
- **Weights.** Add or change the item in `scripts/download_models.py` (`ITEMS`: repo + commit, or URL + size +
  sha256) and in the block's `MODELS.md`. Then test into a scratch folder:
  `download_models.py --models-dir outputs\models_test`, followed by loading the model with
  `$env:PERCEPTION_MODELS_DIR = "outputs\models_test"` and `$env:HF_HUB_CACHE = "outputs\models_test\huggingface\hub"`.
- **Vendored code.** Change the commit in `scripts/fetch_third_party.py` (`REPOS`) and the block's `MODELS.md`.
- **Sample data.** After changing the local sample set, run
  `python tools/bdd/build_sample_manifest.py --yolop-index-dir <folder with yolop_*_seg_index.csv>`. It CRC-checks
  every local file against the zip indexes and rewrites `tools/bdd/sample_members.csv`.
