"""Reviewer's independent re-score of the saved predictions (no code shared with eval_bdd.py).

    cd perception_engine
    .venv\\Scripts\\python.exe -m perception.detection.review_check      # CPU only, ~2 min

GT is rebuilt from the raw 2018 labels (labels/eval_subset_det.json box2d), NOT from eval_subset_coco.json
(which is only used to map file names to the image ids the saved predictions reference). Class names are
mapped here by hand, and pycocotools COCOeval is used directly. It prints mAP50-95 / mAP50 with the scalabel
+1 convention on both sides (primary, as in eval_bdd) and with no +1 anywhere (Ultralytics-val style).
Output: outputs/detection/review_independent_scores.json
"""
import contextlib
import io
import json
import sys
from pathlib import Path

from pycocotools.coco import COCO
from pycocotools.cocoeval import COCOeval

from perception.common.paths import DATA_ROOT, OUTPUTS_ROOT

PRED_DIR = Path(sys.argv[1]) if len(sys.argv) > 1 else OUTPUTS_ROOT / "detection" / "preds"
LAB = DATA_ROOT / "labels"
CLS = ["pedestrian", "rider", "car", "truck", "bus", "train", "motorcycle", "bicycle", "traffic light", "traffic sign"]
MAP8 = ["pedestrian", "car", "truck", "bus", "train", "motorcycle", "bicycle", "traffic light"]
ALIAS = {"person": "pedestrian", "motor": "motorcycle", "bike": "bicycle"}
COCO2BDD = {"person": "pedestrian", "bicycle": "bicycle", "car": "car", "motorcycle": "motorcycle", "bus": "bus",
            "train": "train", "truck": "truck", "traffic light": "traffic light", "stop sign": "traffic sign"}
CID = {n: i + 1 for i, n in enumerate(CLS)}


def build_gt(frames, fname2id, plus):
    images, anns = [], []
    for fr in frames:
        iid = fname2id[fr["name"]]
        images.append({"id": iid, "width": 1280, "height": 720})
        for l in fr["labels"]:
            if "box2d" not in l:
                continue
            b = l["box2d"]
            w, h = b["x2"] - b["x1"] + plus, b["y2"] - b["y1"] + plus
            anns.append({"id": len(anns) + 1, "image_id": iid, "category_id": CID[ALIAS.get(l["category"], l["category"])],
                         "bbox": [b["x1"], b["y1"], w, h], "area": w * h, "iscrowd": 0})
    g = COCO()
    with contextlib.redirect_stdout(io.StringIO()):
        g.dataset = {"images": images, "annotations": anns, "categories": [{"id": v, "name": k} for k, v in CID.items()]}
        g.createIndex()
    return g


def load_preds(path, plus, space):
    out = []
    for r in json.loads(path.read_text())["raw"]:
        for (x1, y1, x2, y2), s, n in zip(r["xyxy"], r["scores"], r["names"]):
            m = ALIAS.get(n, n) if space == "bdd" else COCO2BDD.get(n)
            if m in CID:
                out.append({"image_id": r["image_id"], "category_id": CID[m],
                            "bbox": [x1, y1, x2 - x1 + plus, y2 - y1 + plus], "score": s})
    return out


def score(g, res, names):
    with contextlib.redirect_stdout(io.StringIO()):
        E = COCOeval(g, g.loadRes(res), "bbox")
        E.params.catIds = [CID[n] for n in names]
        E.evaluate(); E.accumulate(); E.summarize()
    return round(float(E.stats[0]) * 100, 2), round(float(E.stats[1]) * 100, 2)


def main() -> int:
    frames = json.loads((LAB / "eval_subset_det.json").read_text(encoding="utf-8"))
    fname2id = {im["file_name"]: im["id"] for im in json.loads((LAB / "eval_subset_coco.json").read_text(encoding="utf-8"))["images"]}
    g1, g0 = build_gt(frames, fname2id, 1), build_gt(frames, fname2id, 0)
    out = {}
    for f in sorted(PRED_DIR.glob("*.json")):
        space = "coco" if f.name.startswith("coco") else "bdd"
        p1, p0 = load_preds(f, 1, space), load_preds(f, 0, space)
        row = {"map8_plus1": score(g1, p1, MAP8), "map8_no_plus1": score(g0, p0, MAP8)}
        if space == "bdd":
            row["all10_plus1"] = score(g1, p1, CLS)
            row["all10_no_plus1"] = score(g0, p0, CLS)
        out[f.stem] = row
        print(f.stem, row, flush=True)
    dst = OUTPUTS_ROOT / "detection" / "review_independent_scores.json"
    dst.write_text(json.dumps({"note": "(mAP50-95, mAP50) in %, pycocotools, GT rebuilt from raw 2018 box2d", "runs": out}, indent=1))
    print("wrote", dst)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
