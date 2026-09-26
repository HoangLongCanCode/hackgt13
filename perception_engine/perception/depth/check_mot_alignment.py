"""Measure the BDD100K MOT frameIndex -> decoded video frame mapping per clip (review tool).

box_track_20 labels are at 5 fps, the .mov streams at 29.97 fps, but the start offset differs per
clip (-7 .. +1 video frames from round(5.994 * i)), so one formula for all clips is wrong. This
script pixel-matches MOT JPEGs (BDD100K images/track/val/<vid>/<vid>-%07d.jpg, frameIndex = n - 1)
against decoded frames (VideoFileInput, 320x180 MSE; an exact match has MSE ~2, neighbours >= 5)
and prints the anchors used by eval_bdd.MOT_ANCHORS.

scripts/fetch_bdd_samples.py puts the MOT JPEGs of all 7 primary clips under data/bdd100k/mot/. For other
clips, range-fetch a few JPEGs first (from perception_engine/), e.g.
    .venv/Scripts/python.exe tools/bdd/bdd_remote_zip.py --source mot_val1 \
        get --from-file names.txt --out <scratch_dir> --flat
with names like bdd100k/images/track/val/b1f4491b-cf446195/b1f4491b-cf446195-0000011.jpg.

    .venv\\Scripts\\python.exe -m perception.depth.check_mot_alignment [--jpg-dir DIR ...] [--mot 10 50 100 150 190]
"""
from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path

import cv2
import numpy as np

from perception.common.video import DATA_ROOT, VideoFileInput


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jpg-dir", nargs="*", default=[str(DATA_ROOT / "mot" / "images" / "track" / "val" / "b1ff4656-0435391e")])
    ap.add_argument("--mot", nargs="*", type=int, default=[10, 50, 100, 150, 190])
    ap.add_argument("--search", type=int, default=14, help="+/- video frames searched around round(5.994*i)")
    args = ap.parse_args()
    jpgs = defaultdict(dict)
    for d in args.jpg_dir:
        for p in Path(d).glob("*.jpg"):
            vid, n = p.stem[:17], int(p.stem[18:])
            if n - 1 in args.mot:
                jpgs[vid][n - 1] = p
    out = {}
    for vid, items in sorted(jpgs.items()):
        need = {int(round(5.994 * i)) + o for i in items for o in range(-args.search, args.search + 1)}
        v = VideoFileInput(DATA_ROOT / "videos" / "val" / f"{vid}.mov")
        fr = {f.index: cv2.resize(f.image, (320, 180), interpolation=cv2.INTER_AREA).astype(np.float32)
              for f in v if f.index in need}
        v.release()
        anchors = []
        for i, p in sorted(items.items()):
            s = cv2.resize(cv2.imread(str(p)), (320, 180), interpolation=cv2.INTER_AREA).astype(np.float32)
            errs = sorted((float(np.mean((fr[j] - s) ** 2)), j) for j in need if j in fr and abs(j - 5.994 * i) <= args.search + 1)
            anchors.append((i, errs[0][1]))
            print(f"{vid} MOT {i:3d} -> video frame {errs[0][1]:4d} (mse {errs[0][0]:.1f}, next best {errs[1][0]:.1f})")
        out[vid] = anchors
    print(json.dumps(out))


if __name__ == "__main__":
    main()
