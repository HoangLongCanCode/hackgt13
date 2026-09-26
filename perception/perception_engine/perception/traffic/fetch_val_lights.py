"""Select and range-fetch extra BDD100K val keyframes that contain traffic lights.

Why: the 250-image eval subset has 638 lights but only 13 yellow ones, too few
for a per-class color score. This picks every val image outside the subset that
has a yellow light >= 10 px tall (314 images), plus 86 random light images
(seed 11), and range-extracts them from the solesensei archive mirror
(HF Xoner1/bdd100k-client BDD100k.zip) with tools/bdd/bdd_remote_zip.py.
`python scripts/fetch_bdd_samples.py --lights` does the same from a pinned member list.

    .venv\\Scripts\\python.exe -m perception.traffic.fetch_val_lights [--dry-run]

Output: data/bdd100k/images/val_lights/*.jpg and selection.json (names, reason).
The labels come from data/bdd100k/labels/bdd100k_labels_images_val.json.
BDD100K license: research / non-commercial only.
"""
from __future__ import annotations

import argparse
import json
import random
import subprocess
import sys

from perception.common.paths import DATA_ROOT, TOOLS_ROOT

OUT = DATA_ROOT / "images" / "val_lights"
N_TOTAL = 400


def select() -> list[dict]:
    labels = json.load(open(DATA_ROOT / "labels" / "bdd100k_labels_images_val.json", encoding="utf-8"))
    subset = {l.strip() for l in open(DATA_ROOT / "index" / "eval_subset_images.txt")}
    yellow, other = [], []
    for im in labels:
        if im["name"] in subset:
            continue
        tls = [l for l in im.get("labels", []) if l["category"] == "traffic light"
               and (l["box2d"]["y2"] - l["box2d"]["y1"]) >= 10]
        ny = sum(1 for l in tls if l["attributes"].get("trafficLightColor") == "yellow")
        rec = {"name": im["name"], "timeofday": im["attributes"]["timeofday"], "n_lights": len(tls), "n_yellow": ny}
        if ny:
            yellow.append(rec | {"reason": "has_yellow"})
        elif tls:
            other.append(rec | {"reason": "random_light"})
    rng = random.Random(11)
    rng.shuffle(other)
    return yellow + other[: max(0, N_TOTAL - len(yellow))]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    sel = select()
    OUT.mkdir(parents=True, exist_ok=True)
    json.dump(sel, open(OUT / "selection.json", "w"), indent=1)
    names = OUT / "_members.txt"
    names.write_text("\n".join(f"bdd100k/bdd100k/images/100k/val/{s['name']}" for s in sel) + "\n")
    print(f"selected {len(sel)} images ({sum(s['reason'] == 'has_yellow' for s in sel)} with yellow)")
    if args.dry_run:
        return
    tool = TOOLS_ROOT / "bdd" / "bdd_remote_zip.py"
    subprocess.run([sys.executable, str(tool), "--source", "solesensei", "get", "--from-file", str(names),
                    "--out", str(OUT), "--flat"], check=True, cwd=str(tool.parent))


if __name__ == "__main__":
    main()
