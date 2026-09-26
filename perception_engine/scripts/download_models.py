#!/usr/bin/env python3
"""Download every model weight the perception blocks use into models/ (the exact layout the blocks read).

AI Spatial Driving Copilot, perception engine. Run from perception_engine/
with the project venv (needs `requests` and `huggingface_hub`, both in requirements.txt):

    python scripts/download_models.py                 # --minimal (default): what config_realtime.yaml runs (~1.4 GB)
    python scripts/download_models.py --all           # + every alternative backend / eval-only weight (~3.0 GB)
    python scripts/download_models.py --all --only lanes segmentation
    python scripts/download_models.py --list          # inventory: set, block, size, sha256, source, licence
    python scripts/download_models.py --dry-run       # what would be downloaded
    python scripts/download_models.py --verify        # also re-hash files that are already there

Where files go
    <models> = --models-dir, else PERCEPTION_MODELS_DIR, else perception_engine/models.
    Hugging Face repos -> the HF hub cache (<models>/huggingface/hub, or HF_HUB_CACHE if that is set and no
    --models-dir was given), at the pinned commit. `refs/main` (or the tag the code asks for, e.g. `v4.0`) is
    written to point at that commit when the cache has no such ref yet, so the blocks' offline lookups
    (`hf_hub_download(..., local_files_only=True)`, `snapshot_download(..., local_files_only=True)`) resolve
    to exactly the evaluated snapshot and never trigger a full-repo download.
    Everything else -> plain files under <models>/<block>/..., same names as today (see each block's MODELS.md).

Sources: Hugging Face (pinned commits), GitHub release / raw / LFS-media URLs (Ultralytics assets, hustvl/YOLOP,
commaai/openpilot v0.11.1), Roboflow's Google Cloud Storage bucket (RF-DETR COCO), Qualcomm AI Hub S3 (PIDNet),
and Google Drive (TwinLiteNet+; the "can't scan for viruses" confirm page is handled with plain requests, no gdown).

Checks: size and sha256 prefix (from the blocks' MODELS.md) for every new download; a mismatch deletes the file
and fails the run. Existing files are only size-checked unless --verify. Idempotent: re-running downloads nothing.
Partial downloads resume from <file>.part. The one derived file (the Autoware light classifier with uint8 NHWC
input) is generated locally with perception.traffic.lights.autoware_u8_model(), exactly as the block does on
first use.

Licences are per item (--list; details in perception/<block>/MODELS.md). Many weights are research / non-commercial
only (BDD100K, Cityscapes, LISA, Waymo lineage). Engineering summary, not legal advice.
"""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import os
import re
import shutil
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

PROJECT_ROOT = Path(__file__).resolve().parents[1]   # perception_engine/
UA = "perception-engine-download-models/1.0 (+python-requests)"


# ----------------------------------------------------------------------------------------------- inventory
@dataclass
class HFFile:
    path: str                    # file path inside the repo
    size: Optional[int] = None
    sha16: Optional[str] = None  # first 16 hex digits of sha256 (MODELS.md)


@dataclass
class Item:
    key: str
    block: str
    minimal: bool
    licence: str
    note: str = ""
    # Hugging Face cache item
    repo: Optional[str] = None
    revision: Optional[str] = None        # full commit sha
    ref: Optional[str] = None             # refs/<ref> the block code resolves ("main", "v4.0"); None = code pins sha
    files: list[HFFile] = field(default_factory=list)
    # plain-file item (relative to <models>)
    dest: Optional[str] = None
    url: Optional[str] = None
    gdrive_id: Optional[str] = None
    size: Optional[int] = None
    sha16: Optional[str] = None
    sha256: Optional[str] = None          # full hash when known
    copy_of: Optional[str] = None         # dest of an identical file to copy instead of downloading
    derived: bool = False


UL = "https://github.com/ultralytics/assets/releases/download/v8.4.0"
HFR = "https://huggingface.co"

ITEMS: list[Item] = [
    # ---- detection (plan section 7) -------------------------------------------------------------
    Item("bdd-yolo26s", "detection", True, "AGPL-3.0 + BDD100K non-commercial",
         "default detector (detection, tracking, traffic e2e)",
         repo="dronefreak/bdd100k-yolo26s", revision="b11da17c1149b60c0f067eee6588f1780507e064", ref="main",
         files=[HFFile("best.pt", 20_340_357, "47a879aff374b220")]),
    Item("bdd-yolo26n", "detection", False, "AGPL-3.0 + BDD100K non-commercial", "preset bdd-yolo26n",
         repo="dronefreak/bdd100k-yolo26n", revision="52902393f0455d0b66259da1ede2bede5fb7e8ed", ref="main",
         files=[HFFile("best.pt", 5_409_029, "eb100fbb28fbe9d1")]),
    Item("bdd-rfdetr-nano", "detection", False, "Apache-2.0 tag, BDD100K non-commercial", "preset bdd-rfdetr-nano",
         repo="dronefreak/bdd100k-rfdetr-nano", revision="6ac2a8c75da01829b3147dc0ab95dce78403b799", ref="main",
         files=[HFFile("checkpoint_best_total.pth", 122_095_365, "1de1a513754e0593"), HFFile("config.json")]),
    Item("coco-yolo26s", "detection", False, "AGPL-3.0", "preset coco-yolo26s",
         dest="detection/yolo26s.pt", url=f"{UL}/yolo26s.pt", size=20_422_725, sha16="646f8bc3fe0a6568"),
    Item("coco-rfdetr-nano", "detection", False, "Apache-2.0 (Objects365 pretraining: review)", "preset coco-rfdetr-nano",
         dest="detection/rfdetr/rf-detr-nano.pth", url="https://storage.googleapis.com/rfdetr/nano_coco/checkpoint_best_regular.pth",
         size=366_287_238, sha16="d8d6b9ee57d4d0ed"),
    Item("coco-rfdetr-small", "detection", False, "Apache-2.0 (Objects365 pretraining: review)", "preset coco-rfdetr-small",
         dest="detection/rfdetr/rf-detr-small.pth", url="https://storage.googleapis.com/rfdetr/small_coco/checkpoint_best_regular.pth",
         size=386_045_550, sha16="d81979a9213a2109"),
    Item("yolo26n", "env", True, "AGPL-3.0", "scripts/verify_env.py smoke test + depth demo_clip detector",
         dest="yolo26n.pt", url=f"{UL}/yolo26n.pt", size=5_544_453, sha16="9b09cc8bf347f0fc"),
    # ---- depth (plan section 9) -----------------------------------------------------------------
    Item("da3-metric-large", "depth", True, "Apache-2.0 (Waymo-trained: no vehicle-assist use)",
         "depth backend da3_metric_large (realtime default)",
         repo="depth-anything/DA3METRIC-LARGE", revision="4010e39f3634a45bc60553321fb49fb760bd594e", ref="main",
         files=[HFFile("model.safetensors", 1_336_734_448, "bbea5b0b3ee38984"), HFFile("config.json", 847)]),
    Item("da2-metric-outdoor-small", "depth", False, "Apache-2.0 (VKITTI2 CC BY-NC-SA lineage)",
         "depth backend da2_metric_outdoor_small",
         repo="depth-anything/Depth-Anything-V2-Metric-Outdoor-Small-hf",
         revision="fd2c22027eaf20374204f14099b8341e1925ad39", ref="main",
         files=[HFFile("model.safetensors", 99_173_660, "ad065c77a7421ca5"), HFFile("config.json", 1063),
                HFFile("preprocessor_config.json", 437)]),
    Item("metric3d-vit-small", "depth", False, "BSD-2 code, weights licence unstated (Waymo lineage)",
         "depth backend metric3d_vit_s_onnx (fp16 default, fp32 alternative)",
         repo="onnx-community/metric3d-vit-small", revision="ee63b95d92d2f629b3e019e88f57eea7c1e97d4b", ref="main",
         files=[HFFile("onnx/model_fp16.onnx", 75_778_144, "4afcc0893dbb3c0c"), HFFile("config.json", 30),
                HFFile("onnx/model.onnx", 150_739_433, "674a665052f01bb2")]),
    # ---- lanes (plan sections 10 + 13) ----------------------------------------------------------
    Item("twinlitenetplus-large", "lanes", True, "MIT code, BDD100K-trained weights (non-commercial)",
         "lanes backend twinlitenetplus_large (realtime default)",
         dest="lanes/twinlitenetplus/large.pth", gdrive_id="1H8P-GrOUBOaVs5LEqXBfz0dC9gguUUio",
         size=7_959_943, sha16="5605d1a8ce762c1b"),
    Item("twinlitenetplus-medium", "lanes", False, "MIT code, BDD100K-trained weights (non-commercial)",
         "lanes backend twinlitenetplus_medium",
         dest="lanes/twinlitenetplus/medium.pth", gdrive_id="121z9XUh7_lgze8i6nS6Ne2HQ7ZG5_uG9",
         size=2_054_463, sha16="04a7959946f68748"),
    Item("yolop-onnx", "lanes", False, "MIT code, BDD100K-trained weights (non-commercial)", "lanes backend yolop_onnx",
         dest="lanes/yolop/yolop-640-640.onnx", url="https://github.com/hustvl/YOLOP/raw/main/weights/yolop-640-640.onnx",
         size=35_902_568, sha16="cd66a3e0087a7258"),
    *[Item(f"comma10k-segnet/{f}", "lanes", False, "MIT weights on MIT data", "lanes backend comma10k_segnet",
           dest=f"lanes/comma10k-segnet/{f}",
           url=f"{HFR}/commaai/comma10k-segnet/resolve/b642c614eb0737fe7befa229f47fbd0245033038/{f}", size=s, sha16=h)
      for f, s, h in (("model.safetensors", 38_502_740, "8208672861ad1b11"), ("config.json", 374, "2b8f16dbad9bd853"),
                      ("albumentations_config_eval.json", 414, "d260853fe0a993e2"))],
    # ---- traffic lights + signs (plan sections 11, 12) --------------------------------------------
    Item("autoware-tl-classifier", "traffic", True, "Apache-2.0", "lights backend autoware_onnx (realtime default)",
         repo="AutowareFoundation/traffic_light_classifier", revision="527c4905afd8f1bfcc03253529a105f7fa5a6d36",
         ref="v4.0", files=[HFFile("traffic_light_classifier_mobilenetv2_batch_1.onnx", 8_926_508, "455b71b3b20d3a96")]),
    Item("autoware-tl-classifier-b6", "traffic", False, "Apache-2.0", "reference only (batch-6 equivalence check)",
         repo="AutowareFoundation/traffic_light_classifier", revision="527c4905afd8f1bfcc03253529a105f7fa5a6d36",
         ref="v4.0", files=[HFFile("traffic_light_classifier_mobilenetv2_batch_6.onnx", 8_926_648, "e4792eed6a46fdbd"),
                            HFFile("lamp_labels.txt", 118)]),
    Item("autoware-tl-u8nhwc", "traffic", True, "Apache-2.0 (local derivative)",
         "derived locally: dynamic batch + uint8 NHWC input (perception.traffic.lights.autoware_u8_model)",
         dest="traffic/traffic_light_classifier_mobilenetv2_u8nhwc_dynbatch.onnx", size=8_926_733,
         sha16="0c35e80353a3c7c2", derived=True),
    Item("lisa-signs", "traffic", True, "AGPL-3.0 + LISA academic licence", "signs backend lisa_crops (realtime default)",
         repo="cvtechniques/TrafficSignDetection", revision="1daf1b3e802c151c32f152590b7b1e9e765380b4", ref="main",
         files=[HFFile("best.pt", 5_522_202, "e82b514db5f47538")]),
    Item("pp-ocrv6-small", "traffic", True, "Apache-2.0", "sign OCR pp_ocrv6_small (realtime default)",
         repo="PaddlePaddle/PP-OCRv6_small_rec_onnx", revision="b8f84f0b80c529de40b4fbb3544b84fa7233a513",
         files=[HFFile("inference.onnx", 21_159_378, "5435fd747c9e0efe"), HFFile("inference.yml", 150_579, "ab078671bb49f062")]),
    Item("pp-ocrv6-tiny", "traffic", False, "Apache-2.0", "OCR alternative (evaluated, not used)",
         repo="PaddlePaddle/PP-OCRv6_tiny_rec_onnx", revision="2612ab37152ae0a677521bae4e1e3d4fb4cf7c30",
         files=[HFFile("inference.onnx", 4_462_639, "9ef676d6ed3c8825"), HFFile("inference.yml", 55_571)]),
    Item("coco-yolo26s-traffic", "traffic", False, "AGPL-3.0", "signs backend coco_stop (same file as detection/yolo26s.pt)",
         dest="traffic/yolo26s.pt", url=f"{UL}/yolo26s.pt", size=20_422_725, sha16="646f8bc3fe0a6568",
         copy_of="detection/yolo26s.pt"),
    # ---- segmentation (plan section 13) ---------------------------------------------------------
    Item("pidnet-s", "segmentation", False, "MIT, Cityscapes-trained (non-commercial)", "segmentation backend pidnet_s",
         dest="segmentation/PIDNet_S_Cityscapes_val.pt",
         url="https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/pidnet/v2/PIDNet_S_Cityscapes_val.pt",
         size=31_145_857, sha16="b51aa935bdb64a07"),
    *[Item(f"efficientvit-seg-{b}", "segmentation", False, "Apache-2.0, Cityscapes-trained (non-commercial)",
           f"segmentation backend efficientvit_{b}" + (" (config_realtime.yaml choice when enabled)" if b == "b1" else ""),
           dest=f"segmentation/efficientvit/efficientvit_seg_{b}_cityscapes.pt",
           url=f"{HFR}/han-cai/efficientvit-seg/resolve/cf3ccaf9cbaf670a2cb283612773546824ca0aa1/efficientvit_seg_{b}_cityscapes.pt",
           size=s, sha16=h)
      for b, s, h in (("b0", 2_924_013, "923d6fdd5e93640c"), ("b1", 19_391_716, "80c305bb2a94b921"),
                      ("b2", 61_562_649, "c5d8b1a319d1abce"))],
    Item("segformer-b0", "segmentation", False, "NVIDIA non-commercial + Cityscapes", "segmentation backend segformer_b0",
         repo="nvidia/segformer-b0-finetuned-cityscapes-1024-1024", revision="21b3847fae21ddee674abd31129307b6a1235bd9",
         ref="main", files=[HFFile("pytorch_model.bin", 14_957_601, "027ed78d8ff9c535"), HFFile("config.json", 1680),
                            HFFile("preprocessor_config.json", 272)]),
    Item("segformer-b2", "segmentation", False, "NVIDIA non-commercial + Cityscapes", "segmentation backend segformer_b2",
         repo="nvidia/segformer-b2-finetuned-cityscapes-1024-1024", revision="d633b2072669ca68d8f8e309de9b52bfdbf6bf72",
         ref="main", files=[HFFile("pytorch_model.bin", 109_597_961, "584d72ace8c17c56"), HFFile("config.json", 1681),
                            HFFile("preprocessor_config.json", 272)]),
    # ---- openpilot (experimental) ---------------------------------------------------------------
    Item("openpilot-driving-vision", "openpilot", False, "MIT (research only per openpilot README)",
         "openpilot v0.11.1 driving_vision.onnx",
         dest="openpilot/driving_vision.onnx",
         url="https://media.githubusercontent.com/media/commaai/openpilot/v0.11.1/selfdrive/modeld/models/driving_vision.onnx",
         size=46_877_473, sha16="ee29ee5bce84d1ce",
         sha256="ee29ee5bce84d1ce23e9ff381280de9b4e4d96d2934cd751740354884e112c66"),
]
BLOCKS = sorted({i.block for i in ITEMS})


def item_bytes(it: Item) -> int:
    if it.repo:
        return sum(f.size or 0 for f in it.files)
    return it.size or 0


# ----------------------------------------------------------------------------------------------- helpers
def log(msg: str = "") -> None:
    print(msg, flush=True)


def human(n: float) -> str:
    """Decimal units (1 GB = 10^9 bytes), like the sizes quoted in the docs."""
    for u in ("B", "KB", "MB", "GB"):
        if n < 1000 or u == "GB":
            return f"{n:.1f} {u}" if u != "B" else f"{int(n)} B"
        n /= 1000
    return f"{n:.1f} GB"


def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as fh:
        for chunk in iter(lambda: fh.read(8 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


class Failure(Exception):
    pass


def check_file(p: Path, size: Optional[int], sha16: Optional[str], full: Optional[str], hash_it: bool) -> Optional[str]:
    """None if OK, else a reason."""
    if not p.exists():
        return "missing"
    if size is not None and p.stat().st_size != size:
        return f"size {p.stat().st_size:,} != {size:,}"
    if hash_it and (sha16 or full):
        h = sha256_file(p)
        if full and h != full:
            return f"sha256 {h[:16]} != {full[:16]}"
        if sha16 and not h.startswith(sha16):
            return f"sha256 {h[:16]} != {sha16}"
    return None


# ----------------------------------------------------------------------------------------------- HTTP
def _session():
    import requests
    s = requests.Session()
    s.headers["User-Agent"] = UA
    return s


def _gdrive_response(sess, file_id: str, headers: dict):
    """GET a public Google Drive file, following the "can't scan for viruses" confirm form if Drive shows it."""
    url = "https://drive.usercontent.google.com/download"
    params = {"id": file_id, "export": "download"}
    r = sess.get(url, params=params, headers=headers, stream=True, timeout=60, allow_redirects=True)
    if "text/html" not in r.headers.get("Content-Type", ""):
        return r
    page = r.text
    r.close()
    form = re.search(r'<form[^>]+id="download-form"[^>]+action="([^"]+)"', page)
    inputs = dict(re.findall(r'<input[^>]+type="hidden"[^>]+name="([^"]+)"[^>]+value="([^"]*)"', page))
    if form and inputs:
        action, params = html.unescape(form.group(1)), {k: html.unescape(v) for k, v in inputs.items()}
    else:
        tok = re.search(r"confirm=([0-9A-Za-z_-]+)", page)
        if not tok:
            raise Failure(f"Google Drive returned an HTML page without a download form for {file_id} "
                          "(quota exceeded or file not public); retry later or download it in a browser")
        action, params = url, {**params, "confirm": tok.group(1)}
    r = sess.get(action, params=params, headers=headers, stream=True, timeout=60, allow_redirects=True)
    if "text/html" in r.headers.get("Content-Type", ""):
        r.close()
        raise Failure(f"Google Drive still returned HTML after the confirm step for {file_id}")
    return r


def http_download(sess, dest: Path, url: Optional[str], gdrive_id: Optional[str], size: Optional[int],
                  retries: int = 4) -> None:
    """Stream url (or a Google Drive id) to dest via dest.part, resuming a partial download."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_name(dest.name + ".part")
    for attempt in range(retries + 1):
        have = part.stat().st_size if part.exists() else 0
        if size is not None and have > size:
            part.unlink()
            have = 0
        headers = {"Range": f"bytes={have}-"} if have else {}
        try:
            r = _gdrive_response(sess, gdrive_id, headers) if gdrive_id else \
                sess.get(url, headers=headers, stream=True, timeout=60, allow_redirects=True)
            with r:
                if have and r.status_code == 200:          # server ignored Range: start over
                    have = 0
                elif r.status_code == 416 and size is not None and have == size:
                    break
                elif r.status_code not in (200, 206):
                    raise Failure(f"HTTP {r.status_code} for {url or 'gdrive:' + gdrive_id}")
                total = size or (have + int(r.headers.get("Content-Length", 0) or 0)) or None
                mode = "ab" if have and r.status_code == 206 else "wb"
                got, t0, last = have, time.time(), 0.0
                with open(part, mode) as fh:
                    for chunk in r.iter_content(1 << 20):
                        fh.write(chunk)
                        got += len(chunk)
                        now = time.time()
                        if total and now - last > 5:
                            rate = (got - have) / max(1e-3, now - t0)
                            log(f"      {human(got)} / {human(total)} ({100 * got / total:.0f}%, {human(rate)}/s)")
                            last = now
            if size is not None and part.stat().st_size < size:
                raise Failure(f"short download {part.stat().st_size:,}/{size:,}")
            break
        except Failure:
            if attempt >= retries:
                raise
        except Exception as e:  # noqa: BLE001  (network errors: retry with backoff, keep the .part)
            if attempt >= retries:
                raise Failure(f"{type(e).__name__}: {e}") from e
            log(f"      retry {attempt + 1}/{retries} after {type(e).__name__}: {e}")
            time.sleep(min(30, 2 ** attempt))
    os.replace(part, dest)


# ----------------------------------------------------------------------------------------------- items
def hf_repo_dir(cache: Path, repo: str) -> Path:
    return cache / ("models--" + repo.replace("/", "--"))


def ensure_ref(cache: Path, it: Item) -> str:
    if not it.ref:
        return ""
    ref_file = hf_repo_dir(cache, it.repo) / "refs" / it.ref
    if ref_file.exists():
        cur = ref_file.read_text().strip()
        return "" if cur == it.revision else f" (note: refs/{it.ref} -> {cur[:12]}, pinned {it.revision[:12]}; left as is)"
    ref_file.parent.mkdir(parents=True, exist_ok=True)
    ref_file.write_text(it.revision)
    return f" (wrote refs/{it.ref} -> {it.revision[:12]})"


def do_hf(it: Item, cache: Path, verify: bool, dry: bool) -> str:
    from huggingface_hub import hf_hub_download
    out = []
    for f in it.files:
        snap = hf_repo_dir(cache, it.repo) / "snapshots" / it.revision / f.path
        why = check_file(snap, f.size, f.sha16, None, verify)
        if why is None:
            out.append(f"{f.path}: ok")
            continue
        if dry:
            out.append(f"{f.path}: would download ({why}, {human(f.size or 0)})")
            continue
        log(f"    {it.repo}@{it.revision[:8]}/{f.path} ({human(f.size or 0)}) ...")
        p = Path(hf_hub_download(it.repo, f.path, revision=it.revision, cache_dir=str(cache)))
        why = check_file(p, f.size, f.sha16, None, True)
        if why is not None:
            p.unlink(missing_ok=True)
            raise Failure(f"{it.repo}/{f.path}: {why} (file removed)")
        out.append(f"{f.path}: downloaded")
    note = "" if dry else ensure_ref(cache, it)
    return "; ".join(out) + note


def do_file(it: Item, models: Path, sess, verify: bool, dry: bool) -> str:
    dest = models / it.dest
    why = check_file(dest, it.size, it.sha16, it.sha256, verify)
    if why is None:
        return "ok"
    if dry:
        return f"would {'derive' if it.derived else 'download'} ({why}, {human(it.size or 0)})"
    src = models / it.copy_of if it.copy_of else None
    if src is not None and check_file(src, it.size, it.sha16, it.sha256, True) is None:
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, dest)
        how = f"copied from {it.copy_of}"
    else:
        log(f"    {it.dest} <- {it.url or 'gdrive:' + it.gdrive_id} ({human(it.size or 0)}) ...")
        http_download(sess, dest, it.url, it.gdrive_id, it.size)
        how = "downloaded"
    why = check_file(dest, it.size, it.sha16, it.sha256, True)
    if why is not None:
        dest.unlink(missing_ok=True)
        raise Failure(f"{it.dest}: {why} (file removed)")
    return how


def do_derived(it: Item, models: Path, verify: bool, dry: bool) -> str:
    dest = models / it.dest
    if dest.exists() and not verify:
        return "ok"
    if dry and not dest.exists():
        return "would derive from the Autoware batch_1 ONNX"
    if not dest.exists():
        sys.path.insert(0, str(PROJECT_ROOT))
        from perception.traffic.lights import autoware_u8_model   # uses PERCEPTION_MODELS_DIR / HF_HUB_CACHE set in main()
        got = autoware_u8_model()
        if got.resolve() != dest.resolve():
            raise Failure(f"autoware_u8_model wrote {got}, expected {dest}")
        how = "derived"
    else:
        how = "ok"
    why = check_file(dest, None, it.sha16, None, True)
    if why is not None:     # onnx serialisation may differ across onnx versions: warn, the model is still valid
        return f"{how} (warning: {why}; onnx version differs from the one used for MODELS.md?)"
    return how


# ----------------------------------------------------------------------------------------------- main
def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--minimal", action="store_true", help="only what perception/config_realtime.yaml uses (default)")
    g.add_argument("--all", action="store_true", help="every default + alternative backend and eval-only weight")
    ap.add_argument("--only", nargs="+", choices=BLOCKS, metavar="BLOCK", help=f"restrict to blocks: {', '.join(BLOCKS)}")
    ap.add_argument("--models-dir", help="target folder (default: PERCEPTION_MODELS_DIR or perception_engine/models)")
    ap.add_argument("--verify", action="store_true", help="re-hash files that already exist")
    ap.add_argument("--dry-run", action="store_true", help="show what would be downloaded")
    ap.add_argument("--list", action="store_true", help="print the inventory and exit")
    a = ap.parse_args(argv)

    sel = [i for i in ITEMS if (a.all or i.minimal) and (not a.only or i.block in a.only)]
    if a.list:
        log(f"{'set':8s} {'block':13s} {'key':28s} {'size':>10s}  {'sha256[:16]':16s}  source -> location   [licence]")
        for i in ITEMS:
            src = (f"hf:{i.repo}@{i.revision[:8]} {','.join(f.path for f in i.files)}" if i.repo
                   else "derived locally" if i.derived else f"gdrive:{i.gdrive_id}" if i.gdrive_id else i.url)
            loc = f"<models>/huggingface/hub/models--{i.repo.replace('/', '--')}" if i.repo else f"<models>/{i.dest}"
            sha = i.files[0].sha16 if i.repo else i.sha16
            log(f"{'minimal' if i.minimal else 'all':8s} {i.block:13s} {i.key:28s} {human(item_bytes(i)):>10s}  "
                f"{sha or '':16s}  {src} -> {loc}   [{i.licence}]")
        tot_min = sum(item_bytes(i) for i in ITEMS if i.minimal)
        log(f"\nminimal: {human(tot_min)}   all: {human(sum(item_bytes(i) for i in ITEMS))}")
        return 0

    models = Path(a.models_dir or os.environ.get("PERCEPTION_MODELS_DIR") or PROJECT_ROOT / "models").absolute()
    if a.models_dir:
        cache = models / "huggingface" / "hub"
    else:
        cache = Path(os.environ.get("HF_HUB_CACHE") or models / "huggingface" / "hub").absolute()
    # Make perception.* (derived step) and huggingface_hub see the same folders (set before they are imported).
    os.environ["PERCEPTION_MODELS_DIR"] = str(models)
    os.environ["HF_HUB_CACHE"] = str(cache)
    os.environ.setdefault("TORCH_HOME", str(models / "torch"))
    os.environ.setdefault("YOLO_AUTOINSTALL", "False")
    os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")   # Windows without Developer Mode: copies, fine
    log(f"models dir : {models}")
    log(f"HF cache   : {cache}")
    try:
        cache.relative_to(models)
    except ValueError:
        log("  note: the HF cache is outside the models dir (HF_HUB_CACHE is set); the blocks read the same variable")
    log(f"selection  : {'all' if a.all else 'minimal'}{' / ' + ','.join(a.only) if a.only else ''}, "
        f"{len(sel)} item(s), {human(sum(item_bytes(i) for i in sel))} in total")

    if not a.dry_run:
        need = sum(item_bytes(i) for i in sel if (i.repo and any(
            not (hf_repo_dir(cache, i.repo) / "snapshots" / i.revision / f.path).exists() for f in i.files))
            or (i.dest and not (models / i.dest).exists()))
        models.mkdir(parents=True, exist_ok=True)
        free = shutil.disk_usage(models).free
        if need > free - (512 << 20):
            log(f"not enough disk space: need about {human(need)}, {human(free)} free on {models.anchor}")
            return 2

    sess = None
    results, failed = [], 0
    for it in sel:
        try:
            if it.repo:
                res = do_hf(it, cache, a.verify, a.dry_run)
            elif it.derived:
                res = do_derived(it, models, a.verify, a.dry_run)
            else:
                if sess is None:
                    sess = _session()
                res = do_file(it, models, sess, a.verify, a.dry_run)
        except Failure as e:
            res, failed = f"FAILED: {e}", failed + 1
        except Exception as e:  # noqa: BLE001
            res, failed = f"FAILED: {type(e).__name__}: {e}", failed + 1
        results.append((it, res))
        log(f"[{it.block:12s}] {it.key:28s} {res}")

    if not a.dry_run:
        manifest = models / "MODELS_MANIFEST.json"
        old = {}
        if manifest.exists():
            try:
                old = json.loads(manifest.read_text(encoding="utf-8"))
            except ValueError:
                old = {}
        for it, res in results:
            if not res.startswith("FAILED"):
                old[it.key] = {"block": it.block, "licence": it.licence, "note": it.note,
                               "source": (f"https://huggingface.co/{it.repo}/tree/{it.revision}" if it.repo else
                                          it.url or (f"gdrive:{it.gdrive_id}" if it.gdrive_id else "derived")),
                               "files": ([f"huggingface/hub/models--{it.repo.replace('/', '--')}/snapshots/{it.revision}/{f.path}"
                                          for f in it.files] if it.repo else [it.dest]),
                               "sha256_16": ([f.sha16 for f in it.files] if it.repo else [it.sha16])}
        manifest.write_text(json.dumps(old, indent=1, sort_keys=True), encoding="utf-8")
    log(f"\n{len(results) - failed}/{len(results)} item(s) OK" + (f", {failed} FAILED" if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
