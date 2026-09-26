#!/usr/bin/env python3
"""build_eval_subset.py - small labelled BDD100K val evaluation subset.

  * picks ~250 val keyframe images (stratified by timeofday x scene, plus
    rain/snow/fog and rare-class top-ups), always including the keyframes of
    the downloaded videos (videos/val, videos/val_extra)
  * range-extracts the JPEGs from the solesensei (Kaggle-mirror) zip
  * writes labels/eval_subset_det.json   (original 2018 BDD format: det boxes +
                                          lane / drivable poly2d + attributes)
          labels/eval_subset_coco.json  (COCO, the 10 BDD100K det classes)
          labels/drivable_masks/<name>.png  (0=direct, 1=alternative, 2=background,
                                             rasterised from poly2d incl. Bezier)
  * optional: --semseg N  -> N sem_seg val image/mask pairs into images/sem_seg_val

Stdlib + numpy only.  Re-running is idempotent (existing files are skipped).
Run from perception_engine/:  python tools/bdd/build_eval_subset.py   (writes under <PERCEPTION_DATA_DIR>/bdd100k)
scripts/fetch_bdd_samples.py rebuilds the same files from the committed eval_subset_images.txt.
"""
import argparse
import json
import os
import random
import struct
import sys
import zlib
from collections import Counter
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent                  # perception_engine/tools/bdd
# BDD100K sample root = <PERCEPTION_DATA_DIR or perception_engine/data>/bdd100k (index CSVs are cached in its index/)
ROOT = Path(os.environ.get("PERCEPTION_DATA_DIR") or HERE.parents[1] / "data").absolute() / "bdd100k"
sys.path.insert(0, str(HERE))
import bdd_remote_zip as brz  # noqa: E402

IMG_PREFIX = "bdd100k/bdd100k/images/100k/val/"
SEG_PREFIX = "bdd100k_seg/bdd100k/seg/"

# 2018 label names -> BDD100K det_20 class names / ids (scalabel det config order)
DET_CLASSES = ["pedestrian", "rider", "car", "truck", "bus", "train",
               "motorcycle", "bicycle", "traffic light", "traffic sign"]
NAME_MAP = {"person": "pedestrian", "bike": "bicycle", "motor": "motorcycle"}

QUOTAS = {  # (timeofday, scene): n
    ("daytime", "city street"): 45, ("daytime", "highway"): 30, ("daytime", "residential"): 20,
    ("night", "city street"): 40, ("night", "highway"): 25, ("night", "residential"): 8,
    ("dawn/dusk", "city street"): 15, ("dawn/dusk", "highway"): 12, ("dawn/dusk", "residential"): 5,
}
WEATHER_TOPUP = {"rainy": 12, "snowy": 12, "foggy": 5}
CLASS_TOPUP = {"train": 3, "rider": 5}


def cats(im):
    return Counter(l["category"] for l in im.get("labels") or [])


def select(labels, must, seed=13):
    rng = random.Random(seed)
    by = {Path(im["name"]).stem: im for im in labels}
    chosen = [s for s in must if s in by]
    used = set(chosen)
    pool = sorted(by)
    rng.shuffle(pool)

    def take(pred, n):
        k = 0
        for s in pool:
            if k >= n:
                break
            if s in used:
                continue
            im = by[s]
            c = cats(im)
            if sum(v for kk, v in c.items() if kk not in ("lane", "drivable area")) == 0:
                continue
            if pred(im, c):
                chosen.append(s)
                used.add(s)
                k += 1
        return k

    for (tod, scene), n in QUOTAS.items():
        take(lambda im, c, t=tod, sc=scene: im["attributes"]["timeofday"] == t
             and im["attributes"]["scene"] == sc, n)
    for w, n in WEATHER_TOPUP.items():
        take(lambda im, c, w=w: im["attributes"]["weather"] == w, n)
    for cl, n in CLASS_TOPUP.items():
        take(lambda im, c, cl=cl: c[cl] > 0, n)
    return [by[s] for s in chosen]


def to_coco(frames):
    cat_ids = {n: i + 1 for i, n in enumerate(DET_CLASSES)}
    coco = {"info": {"description": "BDD100K val eval subset (smoke tests); bbox = scalabel "
                                    "box2d_to_bbox convention [x1, y1, x2-x1+1, y2-y1+1]",
                     "source": "bdd100k_labels_images_val.json (2018 release)"},
            "licenses": [{"id": 1, "name": "BDD100K License", "url": "https://doc.bdd100k.com/license.html"}],
            "categories": [{"id": i, "name": n, "supercategory": "none"} for n, i in cat_ids.items()],
            "images": [], "annotations": []}
    aid = 1
    for iid, fr in enumerate(frames, 1):
        coco["images"].append({"id": iid, "file_name": fr["name"], "width": 1280, "height": 720,
                               "license": 1, "video_name": Path(fr["name"]).stem,
                               "timestamp_ms": fr.get("timestamp"), **fr["attributes"]})
        for l in fr.get("labels") or []:
            if "box2d" not in l:
                continue
            name = NAME_MAP.get(l["category"], l["category"])
            if name not in cat_ids:
                continue
            b = l["box2d"]
            w = max(0.0, b["x2"] - b["x1"] + 1)
            h = max(0.0, b["y2"] - b["y1"] + 1)
            coco["annotations"].append({
                "id": aid, "image_id": iid, "category_id": cat_ids[name],
                "bbox": [round(b["x1"], 3), round(b["y1"], 3), round(w, 3), round(h, 3)],
                "area": round(w * h, 3), "iscrowd": 0, "ignore": 0, "bdd_label_id": l.get("id"),
                "attributes": l.get("attributes", {})})
            aid += 1
    return coco


# ------------------------------------------------------------ drivable masks
def bezier(p0, p1, p2, p3, n=16):
    t = np.linspace(0, 1, n)[1:, None]
    return ((1 - t) ** 3) * p0 + 3 * ((1 - t) ** 2) * t * p1 + 3 * (1 - t) * t ** 2 * p2 + t ** 3 * p3


def poly_points(poly):
    v = np.asarray(poly["vertices"], dtype=float)
    types = poly.get("types") or "L" * len(v)
    n = len(v)
    pts = [v[0]]
    i = 1
    while i < n:
        # scalabel poly2d: 'L' = vertex, 'C' 'C' = the two control points of a cubic
        # Bezier from the previous vertex to the next one (wrapping to v[0] if closed)
        if types[i] == "C" and i + 1 < n and types[i + 1] == "C":
            end = v[i + 2] if i + 2 < n else v[0]
            pts.extend(bezier(np.asarray(pts[-1]), v[i], v[i + 1], end))
            i += 3
        else:
            pts.append(v[i])
            i += 1
    return np.asarray(pts)


def fill_polygon(mask, pts, value):
    """Even-odd scanline fill of a closed polygon (pixel centres)."""
    h, w = mask.shape
    x0, y0 = pts[:, 0], pts[:, 1]
    x1, y1 = np.roll(x0, -1), np.roll(y0, -1)
    ys = np.arange(h) + 0.5
    lo, hi = max(0, int(np.floor(pts[:, 1].min()))), min(h, int(np.ceil(pts[:, 1].max())) + 1)
    for y in ys[lo:hi]:
        cross = ((y0 <= y) & (y1 > y)) | ((y1 <= y) & (y0 > y))
        if not cross.any():
            continue
        xs = x0[cross] + (y - y0[cross]) * (x1[cross] - x0[cross]) / (y1[cross] - y0[cross])
        xs.sort()
        row = int(y)
        for a, b in zip(xs[0::2], xs[1::2]):
            ca, cb = max(0, int(np.ceil(a - 0.5))), min(w - 1, int(np.floor(b - 0.5)))
            if cb >= ca:
                mask[row, ca:cb + 1] = value


def write_png_gray(path, arr):
    h, w = arr.shape
    raw = b"".join(b"\x00" + arr[r].tobytes() for r in range(h))

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 0, 0, 0, 0)) \
        + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    path.write_bytes(png)


def drivable_mask(frame):
    m = np.full((720, 1280), 2, np.uint8)
    polys = [l for l in frame.get("labels") or [] if l["category"] == "drivable area"]
    for area, val in (("alternative", 1), ("direct", 0)):
        for l in polys:
            if l.get("attributes", {}).get("areaType") == area:
                for p in l.get("poly2d") or []:
                    fill_polygon(m, poly_points(p), val)
    return m


# ------------------------------------------------------------ main
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n-extra", type=int, default=None, help="(unused; quotas are in the script)")
    ap.add_argument("--semseg", type=int, default=30, help="sem_seg val pairs to fetch (0 = skip)")
    ap.add_argument("--no-masks", action="store_true")
    args = ap.parse_args()

    labels = json.load(open(ROOT / "labels" / "bdd100k_labels_images_val.json", encoding="utf-8"))
    must = sorted({p.stem for d in ("val", "val_extra") for p in (ROOT / "videos" / d).glob("*.mov")})
    frames = select(labels, must)
    print(f"selected {len(frames)} images ({len(must)} video keyframes)")

    rz = brz.RemoteZip(brz.SOURCES["solesensei"]["parts"], ROOT / "index" / brz.SOURCES["solesensei"]["index"])
    by_name = {e.name: e for e in rz.entries()}
    want = [by_name[IMG_PREFIX + fr["name"]] for fr in frames]
    out_img = ROOT / "images" / "val"
    rz.extract_many(want, out_img, flat=True, gap=128 << 10)

    (ROOT / "labels").mkdir(exist_ok=True)
    json.dump(frames, open(ROOT / "labels" / "eval_subset_det.json", "w", encoding="utf-8"))
    coco = to_coco(frames)
    json.dump(coco, open(ROOT / "labels" / "eval_subset_coco.json", "w", encoding="utf-8"))
    (ROOT / "index" / "eval_subset_images.txt").write_text("".join(f["name"] + "\n" for f in frames))

    if not args.no_masks:
        md = ROOT / "labels" / "drivable_masks"
        md.mkdir(exist_ok=True)
        for fr in frames:
            p = md / (Path(fr["name"]).stem + ".png")
            if not p.exists():
                write_png_gray(p, drivable_mask(fr))

    if args.semseg:
        seg = sorted(n for n in by_name if n.startswith(SEG_PREFIX + "images/val/") and n.endswith(".jpg"))
        rng = random.Random(7)
        pick = sorted(rng.sample(seg, min(args.semseg, len(seg))))
        members = []
        for n in pick:
            stem = Path(n).stem
            members += [by_name[n],
                        by_name[f"{SEG_PREFIX}labels/val/{stem}_train_id.png"],
                        by_name[f"{SEG_PREFIX}color_labels/val/{stem}_train_color.png"]]
        rz.extract_many(members, ROOT / "images" / "sem_seg_val", flat=False, strip=3, gap=128 << 10)

    # summary
    c = Counter()
    for a in coco["annotations"]:
        c[DET_CLASSES[a["category_id"] - 1]] += 1
    att = {k: Counter(f["attributes"][k] for f in frames) for k in ("weather", "scene", "timeofday")}
    lanes = sum(1 for f in frames for l in f.get("labels") or [] if l["category"] == "lane")
    drv = sum(1 for f in frames for l in f.get("labels") or [] if l["category"] == "drivable area")
    summary = {"images": len(frames), "boxes": len(coco["annotations"]), "boxes_per_class": dict(c),
               "lane_polylines": lanes, "drivable_polygons": drv,
               "attributes": {k: dict(v) for k, v in att.items()},
               "video_keyframes_included": must,
               "downloaded_bytes_this_run": rz.f.bytes, "range_requests_this_run": rz.f.requests}
    json.dump(summary, open(ROOT / "index" / "eval_subset_summary.json", "w", encoding="utf-8"), indent=2)
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
