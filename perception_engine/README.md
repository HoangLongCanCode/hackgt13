# Perception Engine

The laptop half of the Knuckle Sandwich Robotics Inc. (KSR) AI Spatial Driving Copilot (HackGT 13). A Samsung
Galaxy Tab S9 runs the AR app and does all I/O: camera, display, audio and GPS. This engine runs the vision models
on the laptop GPU (Windows 11, RTX 5060 Laptop, 8 GB). It streams the results to the tablet over one WebSocket,
`ws://<host>:8765/perception`, and [`contracts/PROTOCOL_v2.md`](../contracts/PROTOCOL_v2.md) is the source of truth
for the messages.

What it produces, per frame:

- tracked objects with class, box and track ID
- distance and time-to-collision for each tracked object
- traffic-light state and sign type
- lane lines, the ego lane, drivable area and road geometry for AR anchoring

Everything it outputs is a measurement for display. Nothing here steers, brakes or accelerates, and no LLM is in the
loop (plan sections 16, 18 and 38).

## Contents

- [Folder layout](#folder-layout)
- [Blocks and defaults](#blocks-and-defaults)
- [Set up from a fresh clone (Windows)](#set-up-from-a-fresh-clone-windows)
- [Run the server](#run-the-server)
- [Use the engine from Python](#use-the-engine-from-python)
- [Run the evaluations](#run-the-evaluations)
- [Results](#results)
- [Licences](#licences)

## Folder layout

| Path | What | In git? |
|---|---|---|
| `perception/` | The Python package: one sub-package per block, `engine.py` (the per-frame scheduler), `realtime/` (the WebSocket server), `common/` (schemas, video input, [`paths.py`](perception/common/paths.py)) | yes |
| `perception/config_realtime.yaml` | Realtime defaults: models, schedule and camera | yes |
| `perception/<block>/README.md`, `MODELS.md` | Each block's API, measured accuracy and latency, weights, sources and licences | yes |
| `scripts/` | `setup_env.ps1` / `setup_env.sh`, `fetch_third_party.py`, `download_models.py`, `fetch_bdd_samples.py`, `verify_env.py` | yes |
| `tools/bdd/`, `tools/kitti/` | Range-extraction data tools, the pinned sample member list, the BDD100K sample manifest and licence | yes |
| `results/<block>/metrics.json` | The measured numbers behind each block README | yes |
| `tests/` | Golden samples and protocol tests | yes |
| `requirements.txt` | Pinned packages, without torch (torch comes from the CUDA index, see [SETUP.md](SETUP.md)) | yes |
| `.venv/` | Project virtual environment, created by `setup_env` | no |
| `models/` | Weights: Hugging Face cache plus `models/<block>/...`, from `download_models.py` | no |
| `perception/third_party/` | Vendored upstream model code at pinned commits, from `fetch_third_party.py` | no |
| `data/` | BDD100K sample set and optional KITTI sample, from `fetch_bdd_samples.py` | no (licence) |
| `outputs/` | Eval and demo outputs | no |

Every path is relative to `perception_engine/`. Environment variables can move the big folders elsewhere:

| Variable | Default | Used for |
|---|---|---|
| `KSR_MODELS_DIR` | `perception_engine/models` | All weights. `HF_HUB_CACHE` and `TORCH_HOME` default to `<models>/huggingface/hub` and `<models>/torch` |
| `KSR_DATA_DIR` | `perception_engine/data` | `bdd100k/` and `kitti/` |
| `KSR_OUTPUTS_DIR` | `perception_engine/outputs` | Eval outputs |
| `KSR_THIRD_PARTY_DIR` | `perception_engine/perception/third_party` | Vendored code |

## Blocks and defaults

The realtime column is what [`config_realtime.yaml`](perception/config_realtime.yaml) runs. Each block README
explains why its default won.

| Block (plan section) | What it does | Realtime default | Alternatives in the code | Default's weights / data licence |
|---|---|---|---|---|
| [detection](perception/detection/README.md) (7) | 2D boxes, 10 BDD100K classes | `bdd-yolo26s` @ 960, conf 0.25, every frame | `bdd-yolo26n`, `bdd-rfdetr-nano`, COCO YOLO26s / RF-DETR N/S | AGPL-3.0 + BDD100K non-commercial |
| [tracking](perception/tracking/README.md) (8) | Track IDs, scale rate, TTC, approaching flag | `ul_botsort` (Ultralytics BoT-SORT + GMC) for vehicles and VRUs; `ul_bytetrack` for lights and signs | `ul_tracktrack`, Roboflow `rf_mcbyte` / `rf_botsort` / `rf_bytetrack` (Apache-2.0) | AGPL-3.0 (no weights) |
| [depth](perception/depth/README.md) (9) | Metric distance per track: depth network fused with flat-ground and size-prior geometry | `da3_metric_large` (DA3METRIC-LARGE), slow lane | `metric3d_vit_s_onnx`, `da2_metric_outdoor_small`, geometry only (`None`) | Apache-2.0, but trained on Waymo (terms bar vehicle-assist use) |
| [lanes](perception/lanes/README.md) (10, 13) | Lane lines, drivable area, `LaneState`, lane-based `RoadGeometry` | `twinlitenetplus_large`, slow lane | `twinlitenetplus_medium`, `yolop_onnx`, `comma10k_segnet` (MIT data) | BDD100K-trained, non-commercial |
| [traffic](perception/traffic/README.md) (11, 12) | Light colour (HMM-smoothed per track) and US sign type with OCR check | `autoware_onnx` lights every frame; `lisa_crops` signs + PP-OCRv6 small (CPU) | HSV lights, `coco_stop` / `lisa_full` signs | Autoware and PP-OCR: Apache-2.0. LISA signs: AGPL-3.0 + academic |
| [segmentation](perception/segmentation/README.md) (13) | 19-class semantic map, road geometry and AR anchors | off (lanes already yields `RoadGeometry`); `efficientvit_b1` when enabled | `pidnet_s`, `efficientvit_b0/b2`, `segformer_b0/b2` | Cityscapes-trained, non-commercial |
| [openpilot](perception/openpilot/README.md) (experimental) | comma.ai driving model: metric lanes, lead car, ego speed | off (not wired into realtime) | none | MIT (research-only per openpilot) |

The engine runs these on two threads (protocol v2):

- **Fast lane:** detection, tracking and light state on every frame. Each result goes out as `perception.frame`.
- **Slow lane:** depth, lanes and signs, whenever the slow thread is free. Each result goes out as `perception.update`.

Each model belongs to one lane, so no model is shared between threads. The measured schedule and timings are in the
comments of `config_realtime.yaml` and in `perception/realtime/`.

## Set up from a fresh clone (Windows)

You need:

- Windows 11
- an NVIDIA GPU whose driver reports `CUDA Version` 13.0 or newer in `nvidia-smi`
- CPython 3.13 x64
- Git for Windows
- about 10 GB of free disk space

[SETUP.md](SETUP.md) has the details, the manual steps and troubleshooting.

```powershell
git clone https://github.com/HoangLongCanCode/hackgt13.git
cd hackgt13\perception_engine
powershell -ExecutionPolicy Bypass -File scripts\setup_env.ps1              # add -WithData for the BDD100K samples
```

The script does the following, in order. Every step is idempotent, so you can re-run it after a failure.

1. Creates `.venv` with Python 3.13.
2. Installs `torch==2.14.0+cu130` and `torchvision==0.29.0+cu130` from `https://download.pytorch.org/whl/cu130`.
3. Installs `requirements.txt`.
4. Writes a `.pth` hook that keeps the Hugging Face, torch.hub and Ultralytics caches inside the project.
5. Runs `scripts\fetch_third_party.py`, `scripts\download_models.py --minimal` (about 1.4 GB) and `scripts\verify_env.py`.

In Git Bash, `bash scripts/setup_env.sh` runs the same steps. You can also run each piece by hand:

```powershell
.venv\Scripts\python.exe scripts\fetch_third_party.py          # vendored code at pinned commits (git; ~0.3 GB)
.venv\Scripts\python.exe scripts\download_models.py            # --minimal: realtime defaults (~1.4 GB)
.venv\Scripts\python.exe scripts\download_models.py --all      # + every alternative backend (~3.0 GB in total)
.venv\Scripts\python.exe scripts\download_models.py --list     # inventory: source, sha256, licence
.venv\Scripts\python.exe scripts\fetch_bdd_samples.py          # BDD100K sample set (~0.5 GB download)
.venv\Scripts\python.exe scripts\fetch_bdd_samples.py --lights --kitti   # + traffic-light and KITTI eval extras
.venv\Scripts\python.exe scripts\verify_env.py                 # torch CUDA, ORT CUDA EP, Ultralytics, ...
```

What the scripts guarantee:

- **`download_models.py`** pins every Hugging Face file to a commit. It also checks the size and the sha256 prefix
  recorded in each block's `MODELS.md`, and it writes the files to exactly the paths the blocks read.
- **`fetch_bdd_samples.py`** range-reads each file out of the public mirrors' zip archives and checks it against the
  CRC32 pinned in `tools/bdd/sample_members.csv`. It never downloads a whole archive. After a download,
  `HF_HUB_OFFLINE=1` works: the realtime default engine was loaded offline from a freshly downloaded models folder.

## Run the server

Run from `perception_engine/`. The server loads the models once, then serves `ws://<host>:8765/perception`.
[PROTOCOL_v2.md](../contracts/PROTOCOL_v2.md) defines the three modes:

```powershell
# video: the laptop plays a clip on its own clock. For testing without the tablet.
.venv\Scripts\python.exe -m perception.realtime.server --mode video --video data\bdd100k\videos\val\b1ff4656-0435391e.mov --loop

# sim: the same clip plays on both devices. The tablet plays it and sends its playback position; the laptop
# analyses ahead of that position, so results arrive before their frame is shown.
.venv\Scripts\python.exe -m perception.realtime.server --mode sim

# live: the tablet camera uplinks JPEG frames (KSR1 header). Target is under 100 ms from capture to result over USB.
.venv\Scripts\python.exe -m perception.realtime.server --mode live

# auto: live or sim, whichever the client's client.hello asks for
.venv\Scripts\python.exe -m perception.realtime.server --mode auto
```

Options every mode takes:

- `--host 0.0.0.0`, `--port 8765`
- `--config perception/config_realtime.yaml`
- `--max-in-flight 2`
- `--lookahead auto|SECONDS` (sim)
- `--start-on-connect` (video)
- `--video-dir DIR` (sim): extra clip folders

Navigation is optional. The server runs the phase1 Node.js route engine as a child process and sends its
`navigation.packet` messages on the same socket (see the Navigation section of the protocol and `nav/`):

- sim and video: `--nav-session DIR`
- live: `--nav-route route.json`, or `--nav-destination "QUERY"` with `--nav-provider mock|google`

`python -m perception.realtime.server --help` lists the current flags.

Connect the tablet:

- **USB (preferred):** run `adb reverse tcp:8765 tcp:8765`. The app then connects to `ws://127.0.0.1:8765/perception`.
- **Wi-Fi:** put both devices on the same network and have the app connect to `ws://<laptop LAN IP>:8765/perception`.
  The server prints that address at startup. Wi-Fi is fine for slow in-town driving only.

To check a running server without the tablet, use `ws_probe`, a Python fake tablet. It validates every message
against `contracts/schemas/` and reports rates, latency and lead:

```powershell
.venv\Scripts\python.exe -m perception.realtime.ws_probe watch --seconds 30                         # watcher, any mode
.venv\Scripts\python.exe -m perception.realtime.ws_probe sim --video-id b1ff4656-0435391e --seconds 30
.venv\Scripts\python.exe -m perception.realtime.ws_probe live --video data\bdd100k\videos\val\b1ff4656-0435391e.mov
```

## Use the engine from Python

```python
from perception.engine import PerceptionEngine
from perception.common.video import VideoFileInput, DATA_ROOT

eng = PerceptionEngine("perception/config_realtime.yaml")   # loads every enabled block once (about 13 s + warm-up)
for frame in VideoFileInput(DATA_ROOT / "videos" / "val" / "b1ff4656-0435391e.mov"):
    result = eng.step(frame)          # schemas.FrameResult: objects, distances, lights, signs, lanes, road
    meta = eng.last_meta              # blockAges, per-track depth details, camera, which blocks ran
eng.close()
```

Each block can also be used on its own: `perception.detection.Detector`, `perception.tracking.Tracker`,
`perception.depth.DistanceEstimator`, `perception.lanes.LaneDetector`,
`perception.traffic.lights.TrafficLightClassifier`, `perception.traffic.signs.SignRecognizer` and
`perception.segmentation.semantic.SemanticSegmenter`. Their READMEs have the APIs.

## Run the evaluations

Before running any eval:

- Fetch the data with `scripts\fetch_bdd_samples.py`. Add `--lights` for the traffic colour DEV split and `--kitti`
  for the depth KITTI eval.
- Download the weights with `scripts\download_models.py --all`.
- Set `$env:YOLO_AUTOINSTALL = "False"`. The venv hook does this too.

Outputs go to `outputs\<block>\`. The committed copies of the numbers are in `results\<block>\metrics.json`. Commands:

| Block | Command (from `perception_engine/`, prefix `.venv\Scripts\python.exe -m`) | Data | Time |
|---|---|---|---|
| detection | `perception.detection.eval_bdd` (then `bench_latency`, `plots`, `make_videos`) | 250-image eval subset | ~10 min |
| tracking | `perception.tracking.eval_bdd` (`--stages default ttc report` for a fast re-check); `perception.tracking.test_motion` | MOT labels + frames, 7 clips | ~30 min |
| depth | `perception.depth.eval_kitti`; `perception.depth.eval_bdd`; `perception.depth.test_geometry` | KITTI sample; 7 clips + MOT labels | ~3 + 6 min |
| lanes | `perception.lanes.eval_bdd` (then `make_overlays`, `tools.bench_latency`) | eval subset + YOLOP masks | ~3 min |
| traffic | `perception.traffic.eval_bdd`; `perception.traffic.e2e_video`; `perception.traffic.eval_signs`; `perception.traffic.smoke_test` | eval subset + val_lights; clips | ~3-5 min each |
| segmentation | `perception.segmentation.eval_bdd --scales 1.0,1.5`; `perception.segmentation.make_overlays` | 30 sem_seg pairs + eval subset | ~5 min |
| openpilot | `perception.openpilot.tests_geometry`; `perception.openpilot.eval_bdd [--quick]` | 13 clips | ~10 min |

`fetch_bdd_samples.py` does not recreate two development-only inputs:

- `outputs/lanes/_cache/calib_*`, the 600 masks behind `lanes.tools.calibrate_priors`
- `outputs/segmentation/devset/`, the 120 horizon-tuning images behind `segmentation.review_yaw_check`

The block MODELS.md files say how they were fetched with `tools/bdd/bdd_remote_zip.py`.

## Results

All numbers were measured on this project's data and machine. Latencies are **provisional**: up to six other jobs
shared the GPU while they were taken. Each block README has confidence intervals, per-class tables, caveats and the
review fixes.

| Block | Eval set | Default | Headline result | Latency (p50, provisional) |
|---|---|---|---|---|
| detection | BDD100K val, 250 keyframes, 4,604 boxes | bdd-yolo26s @ 960 | mAP50-95 **38.5** (all 10 classes; 95% CI 34.3-44.0), mAP50 62.9; COCO-comparable 8 classes 37.9 | 14.3 ms `detect()` |
| tracking | BDD100K MOT val, 7 sequences at 5 fps, 20,585 boxes | ul_botsort | MOTA **55.7**, IDF1 65.4, HOTA 56.0, 487 ID switches (about 40 % fewer than the other top trackers) | 15.2 ms CPU update |
| depth | KITTI, 100 images / 595 objects, GT boxes | DA3 fused, auto calibration | Nearest-face distance MAE **2.31 m**, median relative error **6.8 %**, 100 % coverage (Metric3D fused: 4.7 %) | 50 ms network at 280x504 |
| lanes | BDD100K val, 250 keyframes, YOLOP GT | TwinLiteNet+ large | Lane IoU **34.4** (paper 34.2), drivable mIoU **92.4** (paper 92.9) | 31 ms model, 45 ms `analyze()` |
| traffic lights | BDD100K GT crops, 621 lights | Autoware classifier + HMM | Lit-light accuracy **0.871** [0.837, 0.899]; red read as green 0.6 % | 16-45 ms per frame (CPU / CUDA EP) |
| traffic signs | 273 BDD frames, manual check of 40 typed signs | LISA crops + PP-OCRv6 | Precision 0.44 raw, **0.73** with OCR verification; speed limits 6/6 after OCR (9 abstained) | 15 ms LISA + 5 ms OCR |
| segmentation | BDD100K sem_seg val, 30 images | EfficientViT-B1 (when enabled) | mIoU19 **43.8** (95% CI 38.3-49.9), road IoU 91.7 | 27 ms |
| openpilot | 13 BDD100K clips | driving_vision v0.11.1, f = 1100 px | Lane rows within 20 px of GT **72 %** (86 % on labelled rows); lead distance vs geometry median ratio 0.96 | 6.3 ms model (provisional, shared GPU) |

At the realtime defaults, the solo-GPU measurement on the city clip gave about 17 Hz output from the serial
schedule, with depth and lanes each refreshed at about 8.5 Hz (from the `config_realtime.yaml` notes). The two-lane
server's current numbers are in `perception/realtime/`.

## Licences

This is an engineering summary, not legal advice. Every block's `MODELS.md` has the per-file table: source URL,
sha256, code licence, weights licence and dataset inheritance.

- **KSR code** (this folder): KSR, unpublished.
- **Vendored upstream code** (`fetch_third_party.py` writes `perception/third_party/THIRD_PARTY_NOTICES.md`; nothing is
  modified):

  | Repository | Licence |
  |---|---|
  | Depth-Anything-3 | Apache-2.0 |
  | addict | MIT |
  | TwinLiteNetPlus | MIT |
  | PIDNet | MIT |
  | efficientvit | Apache-2.0 |
  | openpilot v0.11.1 (sparse) | MIT |

- **Runtime libraries:**
  - Ultralytics (detector runtime and trackers) is **AGPL-3.0**. A closed-source product needs an Ultralytics
    Enterprise License, or the Apache-2.0 path: RF-DETR plus Roboflow `trackers`.
  - The other main dependencies are permissive: torch, transformers, onnxruntime, timm, supervision, trackers,
    fastapi and uvicorn (BSD, Apache-2.0 or MIT).
- **Weights** are demo and research only unless noted:
  - BDD100K-trained: the detector and TwinLiteNet+ / YOLOP.
  - Cityscapes-trained: all segmentation weights. SegFormer also carries the NVIDIA non-commercial licence.
  - LISA: the sign model (academic licence).
  - Waymo lineage: DA3 and Metric3D. The Waymo terms bar vehicle-assist use.
  - Commercially cleaner: Autoware lights and PP-OCR (Apache-2.0), comma10k-segnet (MIT weights on MIT data) and the
    geometry-only distance path.
- **Data** (never commit `data/`; it is gitignored):
  - BDD100K is (c) 2018 The Regents of the University of California. It is for educational, research and
    not-for-profit use only; commercial use requires BDD/BAIR Commons membership or a UC Berkeley OTL licence.
    `fetch_bdd_samples.py` writes `LICENSE_BDD100K.txt` next to the data, as the licence requires.
  - KITTI is CC BY-NC-SA 3.0.
  - The YOLOP masks are derived BDD100K labels.
