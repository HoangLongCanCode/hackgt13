#!/usr/bin/env python3
"""build_sample_manifest.py - (maintainers) pin every member of the BDD100K sample set.

Writes tools/bdd/sample_members.csv: one row per remote-zip member that scripts/fetch_bdd_samples.py
fetches (source, local header offset, sizes, method, CRC32, destination). With it, a fresh clone
range-reads exactly those members and never downloads a zip's central directory (videos 12.9 MB,
solesensei 17.8 MB, mot_labels 30.4 MB, mot_val1 5.8 MB, YOLOP 2 x ~9 MB). The CRC32 in each row is
what fetch_bdd_samples.py verifies, so the manifest also pins the data.

Input: an existing sample set (<PERCEPTION_DATA_DIR>/bdd100k) plus the central-directory CSVs that
bdd_remote_zip.py cached in <PERCEPTION_DATA_DIR>/bdd100k/index/ (and the two YOLOP index CSVs, see
--yolop-index-dir). Every local file is checked against its row (size + CRC32) before writing.

    python tools/bdd/build_sample_manifest.py --yolop-index-dir outputs/lanes/_cache     # from perception_engine/

Stdlib only.
"""
from __future__ import annotations

import argparse
import csv
import sys
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import bdd_remote_zip as brz  # noqa: E402

ROOT = brz.ROOT                                   # <PERCEPTION_DATA_DIR>/bdd100k
IMG_PREFIX = "bdd100k/bdd100k/images/100k/val/"
SEG_PREFIX = "bdd100k_seg/bdd100k/seg/"
FIELDS = ["group", "source", "dest", "layout", "name", "local_header_offset", "compressed_size",
          "uncompressed_size", "method", "crc32"]


def crc_file(p: Path) -> int:
    c = 0
    with open(p, "rb") as fh:
        for chunk in iter(lambda: fh.read(8 << 20), b""):
            c = zlib.crc32(chunk, c)
    return c & 0xFFFFFFFF


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--yolop-index-dir", required=True,
                    help="folder with yolop_ll_seg_index.csv and yolop_da_seg_index.csv (lanes block cache)")
    ap.add_argument("--out", default=str(HERE / "sample_members.csv"))
    ap.add_argument("--no-crc", action="store_true", help="skip the CRC32 check of the local files")
    a = ap.parse_args()

    idx = ROOT / "index"
    yidx = Path(a.yolop_index_dir)
    indexes = {"videos": idx / "videos_zip_index.csv", "solesensei": idx / "solesensei_zip_index.csv",
               "mot_labels": idx / "mot_labels_zip_index.csv", "mot_val1": idx / "mot_val1_zip_index.csv",
               "yolop_ll": yidx / "yolop_ll_seg_index.csv", "yolop_da": yidx / "yolop_da_seg_index.csv"}
    cache = {}

    def entry(source: str, name: str) -> brz.Entry:
        if source not in cache:
            cache[source] = {e.name: e for e in brz.load_index(indexes[source])}
        return cache[source][name]

    eval_names = [l.strip() for l in open(HERE / "eval_subset_images.txt", encoding="utf-8") if l.strip()]
    rows = []

    def add(group, source, dest, layout, names_and_local):
        for name, local in names_and_local:
            e = entry(source, name)
            if local is not None and local.exists():
                if local.stat().st_size != e.uncompressed_size:
                    raise SystemExit(f"{local}: size {local.stat().st_size} != {e.uncompressed_size} ({name})")
                if not a.no_crc and crc_file(local) != e.crc32:
                    raise SystemExit(f"{local}: CRC32 differs from {source}:{name}")
            elif local is not None:
                print(f"warning: {local} missing locally (row kept)")
            rows.append([group, source, dest, layout, e.name, e.local_header_offset, e.compressed_size,
                         e.uncompressed_size, e.method, f"{e.crc32:08x}"])

    for sub in ("val", "val_extra"):
        vids = sorted((ROOT / "videos" / sub).glob("*.mov"))
        add(f"videos_{sub}", "videos", f"videos/{sub}", "flat",
            [(f"bdd100k/videos/100k/val/{p.name}", p) for p in vids])
    add("labels_val", "solesensei", "labels", "flat",
        [("bdd100k_labels_release/bdd100k/labels/bdd100k_labels_images_val.json",
          ROOT / "labels" / "bdd100k_labels_images_val.json")])
    add("eval_images", "solesensei", "images/val", "flat",
        [(IMG_PREFIX + n, ROOT / "images" / "val" / n) for n in eval_names])
    seg_root = ROOT / "images" / "sem_seg_val"
    add("semseg", "solesensei", "images/sem_seg_val", "strip:3",
        [(SEG_PREFIX + p.relative_to(seg_root).as_posix(), p) for p in sorted(seg_root.rglob("*")) if p.is_file()])
    mot_lab = sorted((ROOT / "labels" / "box_track_20" / "val").glob("*.json"))
    add("mot_labels", "mot_labels", "labels", "strip:2",
        [(f"bdd100k/labels/box_track_20/val/{p.name}", p) for p in mot_lab])
    mot_root = ROOT / "mot" / "images" / "track" / "val"
    add("mot_frames", "mot_val1", "mot", "strip:1",
        [(f"bdd100k/images/track/val/{p.parent.name}/{p.name}", p) for p in sorted(mot_root.glob("*/*.jpg"))])
    add("yolop_ll", "yolop_ll", "labels/yolop_ll_seg_val", "flat",
        [(f"bdd_lane_gt/val/{Path(n).stem}.png", ROOT / "labels" / "yolop_ll_seg_val" / f"{Path(n).stem}.png")
         for n in eval_names])
    add("yolop_da", "yolop_da", "labels/yolop_da_seg_val", "flat",
        [(f"bdd_seg_gt/val/{Path(n).stem}.png", ROOT / "labels" / "yolop_da_seg_val" / f"{Path(n).stem}.png")
         for n in eval_names])
    lights = sorted((ROOT / "images" / "val_lights").glob("*.jpg"))
    add("val_lights", "solesensei", "images/val_lights", "flat", [(IMG_PREFIX + p.name, p) for p in lights])

    with open(a.out, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(FIELDS)
        w.writerows(rows)
    groups = {}
    for r in rows:
        g = groups.setdefault(r[0], [0, 0])
        g[0] += 1
        g[1] += int(r[6])
    for g, (n, b) in groups.items():
        print(f"{g:18s} {n:5d} members  {b / 1e6:8.1f} MB compressed")
    print(f"wrote {len(rows)} rows -> {a.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
