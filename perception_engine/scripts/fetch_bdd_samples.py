#!/usr/bin/env python3
"""Recreate the BDD100K sample test set (and optionally the KITTI depth sample) from public mirrors.

AI Spatial Driving Copilot, perception engine. Every file is pulled out of
its remote zip with HTTP Range requests (tools/bdd/bdd_remote_zip.py), CRC32-checked, and written to the same
layout the blocks read today. Run from perception_engine/ with the project venv (stdlib + numpy):

    python scripts/fetch_bdd_samples.py                 # core set, about 0.5 GB (details below)
    python scripts/fetch_bdd_samples.py --lights        # + 400 extra traffic-light keyframes (traffic eval, 24 MB)
    python scripts/fetch_bdd_samples.py --kitti         # + KITTI depth sample for depth/eval_kitti (about 0.13 GB)
    python scripts/fetch_bdd_samples.py --dry-run       # what is missing / would be fetched
    python scripts/fetch_bdd_samples.py --only videos_val eval_images      # some groups only ("none" = no BDD groups)

Destination: <PERCEPTION_DATA_DIR>/bdd100k (default perception_engine/data/bdd100k), KITTI in <PERCEPTION_DATA_DIR>/kitti.
Files that already exist with the right size are never downloaded again; derived files that exist are kept.

Core groups (tools/bdd/BDD_SAMPLE_MANIFEST.md has the full description, sources and rationale):
    videos_val         7 primary val clips (.mov, each with MOT labels)              videos/val/
    videos_val_extra   6 extra val clips (keyframe labels only)                        videos/val_extra/
    labels_val         2018 val labels bdd100k_labels_images_val.json (208 MB)       labels/
    eval_images        250-image eval subset (keyframes, tools/bdd/eval_subset_images.txt)   images/val/
    semseg             30 sem_seg val triplets (image, trainId mask, colour mask)      images/sem_seg_val/
    mot_labels         box_track_20 MOT labels of the 7 primary clips                  labels/box_track_20/val/
    mot_frames         5 fps MOT frames of the 7 primary clips (1,418 JPEGs)           mot/images/track/val/
    yolop_ll/yolop_da  YOLOP lane / drivable GT masks for the 250 images (lanes eval)  labels/yolop_{ll,da}_seg_val/
  derived here (no download): labels/eval_subset_det.json, labels/eval_subset_coco.json, labels/drivable_masks/*.png
    (rasterised from the 2018 poly2d exactly as tools/bdd/build_eval_subset.py does), index/eval_subset_images.txt,
    index/selected_videos.json, LICENSE_BDD100K.txt, MANIFEST.md.
Optional: val_lights (--lights, images/val_lights/ + selection.json, as perception/traffic/fetch_val_lights.py).

The member offsets/sizes/CRCs are pinned in tools/bdd/sample_members.csv, so no zip central directory is
downloaded. If a mirror ever changes (CRC mismatch), the script falls back to fetching that zip's central
directory (cached under <data>/bdd100k/index/) and looks the members up by name.

Sources (Hugging Face dataset mirrors; the official BDD100K hosts no longer resolve): linxxx3/bdd100k_videos
(videos), Xoner1/bdd100k-client BDD100k.zip (labels, images, sem_seg), jaffe03195/bdd100k_sot (MOT labels),
vanthanh/BDD100kMOT (MOT frames); YOLOP annotation zips on Google Drive (hustvl/YOLOP README links);
KITTI from the official s3.eu-central-1.amazonaws.com/avg-kitti host.

LICENCES. BDD100K data and labels are (c) 2018 The Regents of the University of California: educational, research
and not-for-profit use only (commercial use only for BDD/BAIR Commons members or under a UC Berkeley OTL licence);
the copyright notice must accompany every copy, so LICENSE_BDD100K.txt is written next to the data. The YOLOP masks
are derived BDD100K labels (same terms). KITTI is CC BY-NC-SA 3.0 (non-commercial, attribution, share-alike).
Never commit or redistribute data/. Engineering summary, not legal advice.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import shutil
import sys
from collections import OrderedDict
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]           # perception_engine/
TOOLS = PROJECT_ROOT / "tools"
sys.path.insert(0, str(TOOLS / "bdd"))
import bdd_remote_zip as brz  # noqa: E402

DATA_DIR = Path(os.environ.get("PERCEPTION_DATA_DIR") or PROJECT_ROOT / "data").absolute()
MANIFEST = TOOLS / "bdd" / "sample_members.csv"
CORE_GROUPS = ["videos_val", "videos_val_extra", "labels_val", "eval_images", "semseg", "mot_labels",
               "mot_frames", "yolop_ll", "yolop_da"]
OPTIONAL_GROUPS = ["val_lights"]
DRIVE = "https://drive.usercontent.google.com/download?id={}&export=download&confirm=t"
EXTRA_SOURCES = {   # YOLOP annotation zips (Drive ids from the hustvl/YOLOP README); Range works with confirm=t
    "yolop_ll": {"parts": [(DRIVE.format("1lDNTPIQj_YLNZVkksKM25CvCHuquJ8AP"), None)], "index": "yolop_ll_seg_index.csv"},
    "yolop_da": {"parts": [(DRIVE.format("1xy_DhUZRHR8yrZG3OwTQAHhYTnXn7URv"), None)], "index": "yolop_da_seg_index.csv"},
}
KITTI_BASE = "https://s3.eu-central-1.amazonaws.com/avg-kitti"
KITTI_ZIPS = [  # (zip, member list in tools/kitti or a prefix, destination under <data>/kitti)
    ("data_object_label_2.zip", "prefix:training/label_2/", "object"),
    ("data_object_calib.zip", "object_calib_members.txt", "object"),
    ("data_object_image_2.zip", "object_image_members.txt", "object"),
    ("data_depth_selection.zip", "depth_selection_members.txt", "."),
]


def log(msg: str = "") -> None:
    print(msg, flush=True)


def human(n: float) -> str:
    return f"{n / 1e6:,.1f} MB" if n >= 1e5 else f"{n / 1e3:,.1f} KB"


# ----------------------------------------------------------------------------------------------- BDD
def load_manifest() -> "OrderedDict[str, list[dict]]":
    groups: "OrderedDict[str, list[dict]]" = OrderedDict()
    with open(MANIFEST, newline="", encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            groups.setdefault(r["group"], []).append(r)
    return groups


def to_entry(r: dict) -> brz.Entry:
    return brz.Entry(r["name"], "", int(r["local_header_offset"]), int(r["compressed_size"]),
                     int(r["uncompressed_size"]), int(r["method"]), int(r["crc32"], 16))


def layout(r: dict) -> tuple[bool, int]:
    lay = r["layout"]
    return (True, 0) if lay == "flat" else (False, int(lay.split(":")[1]))


def source_def(src: str) -> dict:
    return EXTRA_SOURCES[src] if src in EXTRA_SOURCES else brz.SOURCES[src]


def fetch_group(name: str, rows: list[dict], root: Path, dry: bool, zips: dict) -> tuple[int, int]:
    """Returns (members fetched, bytes downloaded)."""
    todo = []
    for r in rows:
        flat, strip = layout(r)
        e = to_entry(r)
        p = brz.dest_path(e, root / r["dest"], flat, strip)
        if not (p.exists() and p.stat().st_size == e.uncompressed_size):
            todo.append((r, e))
    have = len(rows) - len(todo)
    need = sum(e.compressed_size for _, e in todo)
    if not todo:
        log(f"[ok]   {name:17s} {len(rows)} file(s) present")
        return 0, 0
    log(f"[{'miss' if dry else 'get'}]  {name:17s} {len(todo)} of {len(rows)} missing ({have} present), "
        f"{human(need)} compressed")
    if dry:
        return len(todo), 0
    src = rows[0]["source"]
    if src not in zips:
        sd = source_def(src)
        zips[src] = brz.RemoteZip(sd["parts"], root / "index" / sd["index"])
    rz = zips[src]
    before = rz.f.bytes
    by_dest: "OrderedDict[tuple, list[brz.Entry]]" = OrderedDict()
    for r, e in todo:
        by_dest.setdefault((r["dest"], r["layout"]), []).append(e)
    for (dest, lay), ents in by_dest.items():
        flat, strip = layout({"layout": lay})
        try:
            rz.extract_many(ents, root / dest, flat, strip, gap=128 << 10)
        except IOError as err:
            # The pinned offsets no longer match this mirror: use its live central directory instead.
            log(f"       pinned member check failed ({err}); fetching the {src} central directory and retrying")
            live = {e.name: e for e in rz.entries()}
            missing = [e.name for e in ents if e.name not in live]
            if missing:
                raise IOError(f"{len(missing)} member(s) no longer in {src}, e.g. {missing[0]}") from err
            rz.extract_many([live[e.name] for e in ents], root / dest, flat, strip, gap=128 << 10)
    return len(todo), rz.f.bytes - before


def copy_if_missing(src: Path, dst: Path) -> bool:
    if dst.exists():
        return False
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(src, dst)
    return True


def derive(root: Path, force: bool) -> list[str]:
    """Files computed locally from the downloaded labels (same code as tools/bdd/build_eval_subset.py)."""
    import build_eval_subset as bes            # tools/bdd (numpy)
    done = []
    if copy_if_missing(TOOLS / "bdd" / "LICENSE_BDD100K.txt", root / "LICENSE_BDD100K.txt"):
        done.append("LICENSE_BDD100K.txt")
    if copy_if_missing(TOOLS / "bdd" / "BDD_SAMPLE_MANIFEST.md", root / "MANIFEST.md"):
        done.append("MANIFEST.md")
    if copy_if_missing(TOOLS / "bdd" / "selected_videos.json", root / "index" / "selected_videos.json"):
        done.append("index/selected_videos.json")
    if copy_if_missing(TOOLS / "bdd" / "eval_subset_images.txt", root / "index" / "eval_subset_images.txt"):
        done.append("index/eval_subset_images.txt")
    labels_json = root / "labels" / "bdd100k_labels_images_val.json"
    det, coco = root / "labels" / "eval_subset_det.json", root / "labels" / "eval_subset_coco.json"
    masks = root / "labels" / "drivable_masks"
    names = [l.strip() for l in open(TOOLS / "bdd" / "eval_subset_images.txt", encoding="utf-8") if l.strip()]
    need_masks = force or any(not (masks / (Path(n).stem + ".png")).exists() for n in names)
    if not (force or not det.exists() or not coco.exists() or need_masks):
        return done
    if not labels_json.exists():
        log("       (derived labels skipped: labels/bdd100k_labels_images_val.json is not there; fetch labels_val)")
        return done
    by_name = {im["name"]: im for im in json.load(open(labels_json, encoding="utf-8"))}
    frames = [by_name[n] for n in names]         # same order as the original selection (seed 13)
    if force or not det.exists():
        json.dump(frames, open(det, "w", encoding="utf-8"))
        done.append("labels/eval_subset_det.json")
    if force or not coco.exists():
        json.dump(bes.to_coco(frames), open(coco, "w", encoding="utf-8"))
        done.append("labels/eval_subset_coco.json")
    if need_masks:
        masks.mkdir(parents=True, exist_ok=True)
        n = 0
        for fr in frames:
            p = masks / (Path(fr["name"]).stem + ".png")
            if force or not p.exists():
                bes.write_png_gray(p, bes.drivable_mask(fr))
                n += 1
        done.append(f"labels/drivable_masks ({n} png)")
    return done


def lights_selection(root: Path) -> str:
    out = root / "images" / "val_lights" / "selection.json"
    if out.exists():
        return ""
    sys.path.insert(0, str(PROJECT_ROOT))
    os.environ["PERCEPTION_DATA_DIR"] = str(DATA_DIR)
    from perception.traffic.fetch_val_lights import select   # deterministic (seed 11) from the 2018 labels
    sel = select()
    out.parent.mkdir(parents=True, exist_ok=True)
    json.dump(sel, open(out, "w"), indent=1)
    return f"images/val_lights/selection.json ({len(sel)} images)"


# ----------------------------------------------------------------------------------------------- KITTI
def fetch_kitti(kroot: Path, dry: bool, limit: int | None = None) -> tuple[int, int]:
    fetched, nbytes = 0, 0
    for zname, members, dest in KITTI_ZIPS:
        rz = brz.RemoteZip([(f"{KITTI_BASE}/{zname}", None)], kroot / "index" / (zname[:-4] + "_index.csv"))
        if dry and not rz.index_path.exists():
            log(f"[miss] kitti {zname:26s} (central directory not cached yet; members unknown in --dry-run)")
            continue
        entries = rz.entries()
        if members.startswith("prefix:"):
            want = [e for e in entries if e.name.startswith(members[7:]) and not e.name.endswith("/")]
        else:
            names = {l.strip() for l in open(TOOLS / "kitti" / members, encoding="utf-8") if l.strip()}
            want = [e for e in entries if e.name in names]
            if len(want) != len(names):
                raise IOError(f"{zname}: {len(names) - len(want)} listed member(s) not in the archive")
        want = want[:limit] if limit else want
        out = kroot if dest == "." else kroot / dest
        todo = [e for e in want if not ((p := brz.dest_path(e, out, False)).exists() and p.stat().st_size == e.uncompressed_size)]
        if not todo:
            log(f"[ok]   kitti {zname:26s} {len(want)} file(s) present")
            continue
        log(f"[{'miss' if dry else 'get'}]  kitti {zname:26s} {len(todo)} of {len(want)} missing, "
            f"{human(sum(e.compressed_size for e in todo))} compressed")
        if dry:
            fetched += len(todo)
            continue
        before = rz.f.bytes
        rz.extract_many(todo, out, False, 0, gap=128 << 10)
        fetched += len(todo)
        nbytes += rz.f.bytes - before
    if not dry:
        copy_if_missing(TOOLS / "kitti" / "KITTI_SAMPLE_MANIFEST.md", kroot / "MANIFEST.md")
    return fetched, nbytes


# ----------------------------------------------------------------------------------------------- main
def main(argv=None) -> int:
    global DATA_DIR
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", nargs="+", metavar="GROUP",
                    help=f"BDD groups to fetch (default: the core set). Choices: {', '.join(CORE_GROUPS + OPTIONAL_GROUPS)}, none")
    ap.add_argument("--lights", action="store_true", help="also fetch val_lights (400 traffic-light keyframes)")
    ap.add_argument("--kitti", action="store_true", help="also fetch the KITTI depth sample (CC BY-NC-SA 3.0)")
    ap.add_argument("--data-dir", help="data root (default: PERCEPTION_DATA_DIR or perception_engine/data)")
    ap.add_argument("--dry-run", action="store_true", help="report what is missing; download nothing")
    ap.add_argument("--no-derived", action="store_true", help="skip the locally derived label files")
    ap.add_argument("--force-derived", action="store_true", help="rewrite the derived label files")
    ap.add_argument("--limit", type=int, help=argparse.SUPPRESS)   # testing: first N members per group
    a = ap.parse_args(argv)

    if a.data_dir:
        DATA_DIR = Path(a.data_dir).absolute()
    root, kroot = DATA_DIR / "bdd100k", DATA_DIR / "kitti"
    manifest = load_manifest()
    if a.only:
        bad = [g for g in a.only if g not in manifest and g != "none"]
        if bad:
            ap.error(f"unknown group(s) {bad}; choose from {list(manifest)} or none")
        groups = [g for g in a.only if g != "none"]
    else:
        groups = CORE_GROUPS + (["val_lights"] if a.lights else [])
    log(f"data dir: {DATA_DIR}")
    log("BDD100K: research / educational / not-for-profit use only (LICENSE_BDD100K.txt). Do not commit data/.")
    total_n, total_b = 0, 0
    zips: dict = {}
    failed = []
    for g in groups:
        rows = manifest[g][: a.limit] if a.limit else manifest[g]
        try:
            n, b = fetch_group(g, rows, root, a.dry_run, zips)
            total_n, total_b = total_n + n, total_b + b
        except Exception as e:  # noqa: BLE001
            failed.append(g)
            log(f"       FAILED {g}: {type(e).__name__}: {e}")
    if groups and not a.dry_run and not a.no_derived:
        try:
            done = derive(root, a.force_derived)
            if "val_lights" in groups:
                s = lights_selection(root)
                done += [s] if s else []
            log(f"[derived] {', '.join(done) if done else 'all present'}")
        except Exception as e:  # noqa: BLE001
            failed.append("derived")
            log(f"       FAILED derived files: {type(e).__name__}: {e}")
    if a.kitti:
        log("KITTI: CC BY-NC-SA 3.0 (non-commercial, attribution, share-alike).")
        try:
            n, b = fetch_kitti(kroot, a.dry_run, a.limit)
            total_n, total_b = total_n + n, total_b + b
        except Exception as e:  # noqa: BLE001
            failed.append("kitti")
            log(f"       FAILED kitti: {type(e).__name__}: {e}")
    verb = "missing" if a.dry_run else "fetched"
    log(f"\n{total_n} file(s) {verb}" + ("" if a.dry_run else f", {human(total_b)} downloaded")
        + (f"; FAILED: {', '.join(failed)}" if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
