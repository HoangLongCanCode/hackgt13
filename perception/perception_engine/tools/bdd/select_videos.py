#!/usr/bin/env python3
"""Pick ~6 BDD100K val videos that cover the plan's scenarios (sec 3.1 / 33),
using the 2018 val image labels (keyframe = frame at t=10 s of each video).

All 200 BDD100K MOT (box_track_20) val sequences are ALSO val videos, so by
default the pick is restricted to those (every chosen video then has 5 fps
tracking GT as well); pass --any to consider all 10k val videos.
Writes <data>/bdd100k/index/selected_videos.json (+ .txt) and prints the choice
(<data> = KSR_DATA_DIR, default perception_engine/data). Run from perception_engine/:
    python tools/bdd/select_videos.py [--any]"""
import csv, json, os, sys
from collections import Counter
from pathlib import Path

ROOT = Path(os.environ.get("KSR_DATA_DIR") or Path(__file__).resolve().parents[2] / "data").absolute() / "bdd100k"
labels = json.load(open(ROOT / "labels" / "bdd100k_labels_images_val.json", encoding="utf-8"))
vid = {}
with open(ROOT / "index" / "videos_zip_index.csv", newline="", encoding="utf-8") as fh:
    for r in csv.DictReader(fh):
        if r["split"] == "val" and r["name"].endswith(".mov"):
            vid[Path(r["name"]).stem] = r

mot = set()
mot_idx = ROOT / "index" / "mot_labels_zip_index.csv"
if mot_idx.exists():
    with open(mot_idx, newline="", encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            n = r["name"]
            if n.startswith("bdd100k/labels/box_track_20/val/") and n.endswith(".json"):
                mot.add(Path(n).stem)
ANY = "--any" in sys.argv or not mot

rows = []
for im in labels:
    stem = Path(im["name"]).stem
    if stem not in vid or (not ANY and stem not in mot):
        continue
    c = Counter(l["category"] for l in im.get("labels") or [])
    par = sum(1 for l in im.get("labels") or [] if l["category"] == "lane"
              and l.get("attributes", {}).get("laneDirection") == "parallel")
    a = im["attributes"]
    rows.append(dict(stem=stem, **a, size=int(vid[stem]["compressed_size"]), car=c["car"],
                     tl=c["traffic light"], ts=c["traffic sign"], person=c["person"],
                     lane=c["lane"], lane_par=par, drivable=c["drivable area"],
                     truck=c["truck"], bus=c["bus"], n=sum(c.values())))

CLEARISH = {"clear", "partly cloudy", "overcast"}
SCEN = [
    ("highway_day", "Highway driving / exit / following distance (daytime)",
     lambda r: r["scene"] == "highway" and r["timeofday"] == "daytime" and r["weather"] in CLEARISH,
     lambda r: 2 * min(r["car"], 12) + 3 * min(r["lane_par"], 5) + 3 * (r["drivable"] > 0) + r["truck"]),
    ("city_intersection_day", "City intersection with many traffic lights (daytime)",
     lambda r: r["scene"] == "city street" and r["timeofday"] == "daytime" and r["weather"] in CLEARISH,
     lambda r: 4 * min(r["tl"], 8) + min(r["car"], 12) + 2 * min(r["lane"], 8) + min(r["person"], 5)
               + 3 * (r["drivable"] > 0)),
    ("residential_day", "Residential street (daytime)",
     lambda r: r["scene"] == "residential" and r["timeofday"] == "daytime" and r["weather"] in CLEARISH,
     lambda r: min(r["car"], 12) + 2 * min(r["lane"], 6) + 2 * min(r["person"], 4) + 3 * (r["drivable"] > 0)),
    ("night_city", "Night driving, city street",
     lambda r: r["scene"] == "city street" and r["timeofday"] == "night" and r["weather"] in CLEARISH,
     lambda r: 3 * min(r["tl"], 6) + min(r["car"], 12) + 2 * min(r["lane"], 6) + 3 * (r["drivable"] > 0)),
    ("rain_or_snow", "Adverse weather (rain/snow)",
     lambda r: r["weather"] in ("rainy", "snowy") and r["scene"] in ("city street", "highway")
               and r["timeofday"] == "daytime",
     lambda r: min(r["car"], 12) + 2 * min(r["lane"], 6) + 2 * min(r["tl"], 4) + 3 * (r["drivable"] > 0)
               + 2 * (r["weather"] == "rainy")),
    ("dusk_heavy_traffic", "Dawn/dusk, heavy traffic (highway or city)",
     lambda r: r["timeofday"] == "dawn/dusk" and r["scene"] in ("highway", "city street"),
     lambda r: 2 * min(r["car"] + r["truck"] + r["bus"], 25) + 2 * min(r["lane_par"], 5) + 3 * (r["drivable"] > 0)),
    ("rain_night", "Rainy night (glare + wet road) - bonus 7th clip",
     lambda r: r["weather"] == "rainy" and r["timeofday"] == "night" and r["scene"] in ("city street", "highway"),
     lambda r: min(r["car"], 15) + 2 * min(r["tl"], 6) + 2 * min(r["lane"], 6) + 3 * (r["drivable"] > 0)),
]

MIN_SIZE, MAX_SIZE = 15_000_000, 40_000_000
chosen, used = [], set()
for key, desc, filt, score in SCEN:
    cands = [r for r in rows if filt(r) and MIN_SIZE <= r["size"] <= MAX_SIZE and r["stem"] not in used
             and r["car"] >= 2 and r["lane"] >= 2 and r["drivable"] > 0]
    cands.sort(key=lambda r: (-score(r), -r["size"], r["stem"]))
    if not cands:
        print("no candidate for", key, file=sys.stderr)
        continue
    best = cands[0]
    used.add(best["stem"])
    chosen.append(dict(scenario=key, description=desc, score=score(best), candidates=len(cands),
                       video=vid[best["stem"]]["name"], has_mot_labels=best["stem"] in mot, **best,
                       runners_up=[c["stem"] for c in cands[1:6]]))

out = ROOT / "index" / "selected_videos.json"
json.dump(chosen, open(out, "w", encoding="utf-8"), indent=2)
(ROOT / "index" / "selected_videos.txt").write_text("".join(c["video"] + "\n" for c in chosen))
for c in chosen:
    print(f"{c['scenario']:22s} {c['stem']}  {c['size']/1e6:5.1f} MB  {c['weather']}/{c['scene']}/{c['timeofday']}"
          f"  car={c['car']} tl={c['tl']} ts={c['ts']} ped={c['person']} lane={c['lane']} drv={c['drivable']}"
          f"  mot={c['has_mot_labels']}  (of {c['candidates']})")
print("total MB", sum(c["size"] for c in chosen) / 1e6)
