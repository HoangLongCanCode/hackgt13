"""Pick the small KITTI subset used by perception/depth.

1) object split: ~100 training images whose label_2 files contain distance-
   evaluable objects (Car/Van/Truck/Pedestrian/Cyclist, truncated < 0.3,
   occluded <= 1, 0 < z <= 80 m, 2D box height >= 10 px), chosen greedily
   (seed 9) so every range bin 0-10/10-20/20-40/40-80 m and every class group
   is represented.
2) depth selection: every 20th pair of val_selection_cropped (50 pairs).

Reads the label_2 files and the cached central-directory CSVs under <PERCEPTION_DATA_DIR>/kitti (default
perception_engine/data/kitti); writes the member lists next to this script (tools/kitti/*.txt), which
scripts/fetch_bdd_samples.py --kitti range-fetches. Run from perception_engine/:
    python tools/kitti/select_kitti.py
Stdlib only.
"""
import csv, os, random
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent                 # perception_engine/tools/kitti (member lists)
ROOT = Path(os.environ.get("PERCEPTION_DATA_DIR") or HERE.parents[1] / "data").absolute() / "kitti"
LAB = ROOT / "object" / "training" / "label_2"
IDX = ROOT / "index"                                   # cached zip central directories
CLASSES = {"Car": "vehicle", "Van": "vehicle", "Truck": "vehicle",
           "Pedestrian": "pedestrian", "Cyclist": "cyclist"}
BINS = [(0, 10), (10, 20), (20, 40), (40, 80)]
N_IMAGES = 100


def valid_objects(p: Path):
    out = []
    for line in p.read_text().splitlines():
        f = line.split()
        if not f or f[0] not in CLASSES:
            continue
        trunc, occ = float(f[1]), int(f[2])
        x1, y1, x2, y2 = map(float, f[4:8])
        z = float(f[13])
        if trunc < 0.3 and occ <= 1 and 0 < z <= 80 and (y2 - y1) >= 10:
            b = next(i for i, (lo, hi) in enumerate(BINS) if lo <= z < hi or (hi == 80 and z == 80))
            out.append((CLASSES[f[0]], b))
    return out


def main():
    rng = random.Random(9)
    imgs = {p.stem: valid_objects(p) for p in sorted(LAB.glob("*.txt"))}
    imgs = {k: v for k, v in imgs.items() if v}
    keys = sorted(imgs)
    rng.shuffle(keys)
    # target per (group, bin): enough to get stable medians
    target = {(g, b): (60 if g == "vehicle" else 25) for g in ("vehicle", "pedestrian", "cyclist") for b in range(4)}
    got = Counter()
    chosen = []
    pool = set(keys)
    while len(chosen) < N_IMAGES and pool:
        def gain(k):
            return sum(max(0, target[o] - got[o]) / target[o] for o in imgs[k])
        best = max(sorted(pool, key=keys.index)[:600], key=gain)  # bounded search keeps it quick
        if gain(best) <= 0:
            best = next(k for k in keys if k in pool)
        chosen.append(best)
        pool.discard(best)
        got.update(imgs[best])
    chosen.sort()
    (HERE / "object_selected_ids.txt").write_text("\n".join(chosen) + "\n")
    (HERE / "object_image_members.txt").write_text("".join(f"training/image_2/{k}.png\n" for k in chosen))
    (HERE / "object_calib_members.txt").write_text("".join(f"training/calib/{k}.txt\n" for k in chosen))
    print("images", len(chosen), "objects", sum(got.values()))
    for g in ("vehicle", "pedestrian", "cyclist"):
        print(g, [got[(g, b)] for b in range(4)])

    # depth selection: every 20th val_selection_cropped image
    rows = list(csv.DictReader(open(IDX / "data_depth_selection_index.csv", newline="")))
    ims = sorted(r["name"] for r in rows if "/val_selection_cropped/image/" in r["name"] and r["name"].endswith(".png"))
    pick = ims[::20]
    mem = []
    for im in pick:
        base = im.rsplit("/", 1)[1]
        gt = base.replace("_sync_image_", "_sync_groundtruth_depth_")
        mem += [im, f"depth_selection/val_selection_cropped/groundtruth_depth/{gt}",
                f"depth_selection/val_selection_cropped/intrinsics/{base.replace('.png', '.txt')}"]
    names = {r["name"] for r in rows}
    missing = [m for m in mem if m not in names]
    assert not missing, missing[:5]
    (HERE / "depth_selection_members.txt").write_text("\n".join(mem) + "\n")
    print("depth selection pairs", len(pick))


if __name__ == "__main__":
    main()
