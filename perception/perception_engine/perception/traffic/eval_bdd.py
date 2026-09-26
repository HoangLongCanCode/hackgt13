"""Traffic-light COLOR accuracy on BDD100K ground-truth crops (oracle boxes).

    cd perception_engine
    .venv\\Scripts\\python.exe -m perception.traffic.eval_bdd            # all backends, both splits
    .venv\\Scripts\\python.exe -m perception.traffic.eval_bdd --split subset --backends hsv

Splits:
  subset      the 250-image eval subset (labels/eval_subset_det.json, images/val). TEST split:
              nothing was tuned on it.
  val_lights  400 extra val keyframes fetched by fetch_val_lights.py (314 chosen because they
              contain a yellow light). DEV split: HSV thresholds were tuned here (tune_hsv.py).

GT: 2018 trafficLightColor attribute {red, yellow, green, none}. 'none' (not facing,
off, unreadable) should map to UNKNOWN. Crops with long side < --min-long px or
short side < --min-short px are ignored, and the ignored counts are reported.
Writes outputs/traffic/metrics.json (section "light_color") and confusion PNGs.
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path
from typing import Callable

import cv2
import numpy as np

from perception.common.video import DATA_ROOT, OUTPUTS_ROOT

OUT = OUTPUTS_ROOT / "traffic"
GT_NAMES = ["red", "yellow", "green", "none"]
PRED_NAMES = ["RED", "YELLOW", "GREEN", "UNKNOWN"]
TOD_GROUP = {"daytime": "day", "night": "night", "dawn/dusk": "dawn_dusk"}


def update_metrics(section: str, payload) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    p = OUT / "metrics.json"
    m = json.loads(p.read_text()) if p.exists() else {"block": "lights_signs"}
    m[section] = payload
    p.write_text(json.dumps(m, indent=1))


def load_gt(split: str, min_long: float = 10.0, min_short: float = 4.0):
    """Return (items, ignored) where items = dict(img, box[x1,y1,x2,y2], color, tod)."""
    if split == "subset":
        labels = json.load(open(DATA_ROOT / "labels" / "eval_subset_det.json", encoding="utf-8"))
        img_dir = DATA_ROOT / "images" / "val"
    elif split == "val_lights":
        sel = {s["name"] for s in json.load(open(DATA_ROOT / "images" / "val_lights" / "selection.json"))}
        allv = json.load(open(DATA_ROOT / "labels" / "bdd100k_labels_images_val.json", encoding="utf-8"))
        labels = [im for im in allv if im["name"] in sel]
        img_dir = DATA_ROOT / "images" / "val_lights"
    else:
        raise ValueError(split)
    items, ignored = [], {"total": 0, "by_color": {}}
    for im in labels:
        tod = TOD_GROUP.get(im["attributes"].get("timeofday"), "other")
        for l in im.get("labels", []):
            if l["category"] != "traffic light":
                continue
            b = l["box2d"]
            box = [b["x1"], b["y1"], b["x2"], b["y2"]]
            w, h = box[2] - box[0] + 1, box[3] - box[1] + 1  # scalabel +1 convention
            color = l["attributes"].get("trafficLightColor", "none")
            if max(w, h) < min_long or min(w, h) < min_short:
                ignored["total"] += 1
                ignored["by_color"][color] = ignored["by_color"].get(color, 0) + 1
                continue
            items.append({"img": str(img_dir / im["name"]), "box": box, "color": color, "tod": tod,
                          "h": h, "w": w})
    return items, ignored


def load_crops(items):
    cache: dict[str, np.ndarray] = {}
    crops = []
    for it in items:
        if it["img"] not in cache:
            cache = {it["img"]: cv2.imread(it["img"])}  # items are grouped by image
        img = cache[it["img"]]
        x1, y1, x2, y2 = it["box"]
        xi1, yi1 = max(0, int(np.floor(x1))), max(0, int(np.floor(y1)))
        xi2, yi2 = min(img.shape[1], int(np.ceil(x2)) + 1), min(img.shape[0], int(np.ceil(y2)) + 1)
        crops.append(img[yi1:yi2, xi1:xi2].copy())
    return crops


def score(gt: list[str], pred_idx: np.ndarray, tods: list[str] | None = None) -> dict:
    g = np.array([GT_NAMES.index(c) for c in gt])
    p = np.asarray(pred_idx)
    cm = np.zeros((4, 4), int)
    for a, b in zip(g, p):
        cm[a, b] += 1
    res: dict = {"n": int(len(g)), "confusion_rows_gt_cols_pred": cm.tolist(),
                 "rows": GT_NAMES, "cols": PRED_NAMES}
    recall = {GT_NAMES[i]: (round(cm[i, i] / cm[i].sum(), 4) if cm[i].sum() else None) for i in range(4)}
    res["per_class_recall"] = recall
    prec = {PRED_NAMES[j]: (round(cm[j, j] / cm[:, j].sum(), 4) if cm[:, j].sum() else None) for j in range(4)}
    res["per_class_precision"] = prec
    f1 = []
    for i in range(4):
        r_, p_ = recall[GT_NAMES[i]], prec[PRED_NAMES[i]]
        if r_ is None:
            continue
        f1.append(0.0 if not p_ or not r_ else 2 * p_ * r_ / (p_ + r_))
    res["macro_f1_4class"] = round(float(np.mean(f1)), 4) if f1 else None
    lit = g < 3
    res["n_lit"] = int(lit.sum())
    res["lit_accuracy"] = round(float((p[lit] == g[lit]).mean()), 4) if lit.any() else None
    cov = lit & (p < 3)
    res["lit_coverage"] = round(float(cov.sum() / max(lit.sum(), 1)), 4)
    res["lit_accuracy_when_called"] = round(float((p[cov] == g[cov]).mean()), 4) if cov.any() else None
    red = g == 0
    res["red_missed_rate"] = round(float((p[red] != 0).mean()), 4) if red.any() else None
    res["red_called_green_rate"] = round(float((p[red] == 2).mean()), 4) if red.any() else None
    green = g == 2
    res["green_called_red_rate"] = round(float((p[green] == 0).mean()), 4) if green.any() else None
    none = g == 3
    res["none_to_unknown_rate"] = round(float((p[none] == 3).mean()), 4) if none.any() else None
    res["accuracy_4class"] = round(float((p == g).mean()), 4)
    if tods is not None:
        t = np.array(tods)
        res["by_timeofday"] = {}
        for grp in ("day", "night", "dawn_dusk"):
            m = t == grp
            if m.any():
                sub = score([gt[i] for i in np.where(m)[0]], p[m])
                res["by_timeofday"][grp] = {k: sub[k] for k in ("n", "n_lit", "lit_accuracy", "lit_coverage",
                                                                "lit_accuracy_when_called", "per_class_recall",
                                                                "macro_f1_4class", "red_missed_rate",
                                                                "none_to_unknown_rate", "confusion_rows_gt_cols_pred")}
    return res


def plot_confusion(cm, title: str, path: Path) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    cm = np.asarray(cm, float)
    rown = cm / np.maximum(cm.sum(1, keepdims=True), 1)
    fig, ax = plt.subplots(figsize=(4.6, 4.0), dpi=120)
    ax.imshow(rown, cmap="Blues", vmin=0, vmax=1)
    for i in range(4):
        for j in range(4):
            ax.text(j, i, f"{int(cm[i, j])}\n{rown[i, j]:.0%}", ha="center", va="center", fontsize=8,
                    color="white" if rown[i, j] > 0.55 else "black")
    ax.set_xticks(range(4), PRED_NAMES, fontsize=8)
    ax.set_yticks(range(4), [f"gt {n}" for n in GT_NAMES], fontsize=8)
    ax.set_title(title, fontsize=9)
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def backend_factories(device: str) -> dict[str, Callable]:
    from perception.traffic.lights import AutowareLightClassifier, HSVParams, hsv_probs

    def hsv():
        p = HSVParams()
        return lambda crops: np.stack([hsv_probs(c, p) for c in crops])

    def aw(orient: str, gate: bool):
        def make():
            m = AutowareLightClassifier(device=device, orient=orient, exposure_gate=gate)

            def fn(crops):
                return m.probs(crops)
            fn.providers = m.providers  # type: ignore[attr-defined]
            return fn
        return make

    def ens():
        h = hsv()
        a = aw("both", True)()

        def fn(crops):
            return 0.5 * h(crops) + 0.5 * a(crops)
        fn.providers = a.providers  # type: ignore[attr-defined]
        return fn

    return {
        "hsv": hsv,
        "autoware_onnx": aw("both", True),          # default: as-is + US->JP view averaged, exposure gate on
        "autoware_onnx_auto": aw("auto", True),     # US->JP re-oriented view only
        "autoware_onnx_noorient": aw("none", True),  # crop as-is
        "autoware_onnx_nogate": aw("both", False),
        "ensemble_hsv_autoware": ens,
    }


def api_path_score(items, backend: str, device: str, **opts) -> dict:
    """Review fix: score the PUBLIC API, TrafficLightClassifier.classify() (smoothing off, library defaults
    otherwise), on the same GT boxes. The backend rows above call the raw classifier and so skip the API's
    min_box_h gate; this row is the number the integrator actually gets."""
    from collections import OrderedDict
    from perception.traffic.lights import STATES, TrafficLightClassifier
    clf = TrafficLightClassifier(backend=backend, device=device, smoothing="none", **opts)
    groups: "OrderedDict[str, list[int]]" = OrderedDict()
    for i, it in enumerate(items):
        groups.setdefault(it["img"], []).append(i)
    pred = np.zeros(len(items), int)
    for img_path, idx in groups.items():
        img = cv2.imread(img_path)
        for i, o in zip(idx, clf.classify(img, [items[i]["box"] for i in idx])):
            pred[i] = STATES.index(o.state)
    res = score([it["color"] for it in items], pred, [it["tod"] for it in items])
    res["api_opts"] = {"backend": backend, "smoothing": "none", "min_box_h": clf.min_box_h, **opts}
    return res


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--split", default="both", choices=["subset", "val_lights", "both"])
    ap.add_argument("--backends", default="hsv,autoware_onnx,autoware_onnx_auto,autoware_onnx_noorient,autoware_onnx_nogate,ensemble_hsv_autoware")
    ap.add_argument("--device", default="cuda")
    ap.add_argument("--min-long", type=float, default=10.0)
    ap.add_argument("--min-short", type=float, default=4.0)
    args = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    facs = backend_factories(args.device)
    splits = ["subset", "val_lights"] if args.split == "both" else [args.split]
    report = {"protocol": {"gt": "BDD100K 2018 trafficLightColor on GT boxes (oracle crops)",
                           "min_long_px": args.min_long, "min_short_px": args.min_short,
                           "subset": "250-image eval subset = TEST (untuned)",
                           "val_lights": "400 extra val keyframes (314 yellow-enriched) = DEV (HSV tuned here)",
                           "note": "'none' GT should be predicted UNKNOWN; lit_accuracy counts UNKNOWN on a lit light as an error"},
              "splits": {}}
    for split in splits:
        items, ignored = load_gt(split, args.min_long, args.min_short)
        crops = load_crops(items)
        counts = {c: sum(1 for it in items if it["color"] == c) for c in GT_NAMES}
        print(f"[{split}] {len(items)} crops kept, ignored {ignored['total']} tiny ({ignored['by_color']}); {counts}")
        sres = {"n_kept": len(items), "ignored_tiny": ignored, "gt_counts": counts, "backends": {}}
        for name in [b.strip() for b in args.backends.split(",") if b.strip()]:
            fn = facs[name]()
            fn(crops[:6])  # warm-up
            t0 = time.perf_counter()
            P = fn(crops)
            dt = (time.perf_counter() - t0) * 1000 / max(1, len(crops))
            pred = P.argmax(1)
            res = score([it["color"] for it in items], pred, [it["tod"] for it in items])
            # by box long side (px): tiny distant heads are where color (and the plan's 'RED 120m') breaks
            longs = np.array([max(it["h"], it["w"]) for it in items])
            res["by_long_side_px"] = {}
            for lo, hi in ((10, 15), (15, 20), (20, 30), (30, 50), (50, 1e9)):
                m = (longs >= lo) & (longs < hi)
                if m.any():
                    sub = score([items[i]["color"] for i in np.where(m)[0]], pred[m])
                    res["by_long_side_px"][f"{lo}-{hi if hi < 1e9 else 'inf'}"] = {
                        k: sub[k] for k in ("n", "n_lit", "lit_accuracy", "none_to_unknown_rate", "red_missed_rate")}
            res["ms_per_crop_mean_incl_preproc"] = round(dt, 3)
            res["providers"] = getattr(fn, "providers", ["cpu (opencv)"])
            sres["backends"][name] = res
            print(f"  {name:28s} lit_acc={res['lit_accuracy']} macroF1={res['macro_f1_4class']} "
                  f"recall={res['per_class_recall']} red->green={res['red_called_green_rate']} "
                  f"none->UNK={res['none_to_unknown_rate']} {dt:.2f} ms/crop")
            if name in ("hsv", "autoware_onnx", "ensemble_hsv_autoware"):
                plot_confusion(res["confusion_rows_gt_cols_pred"], f"{name} | {split} (n={len(items)})",
                               OUT / f"confusion_{name}_{split}.png")
        sres["api_path"] = {}
        for name, kw in (("autoware_onnx_api_default", {}), ("autoware_onnx_api_min_box_h6_pre_review", {"min_box_h": 6.0})):
            r = api_path_score(items, "autoware_onnx", args.device, **kw)
            sres["api_path"][name] = r
            print(f"  {name:40s} lit_acc={r['lit_accuracy']} macroF1={r['macro_f1_4class']} "
                  f"red->green={r['red_called_green_rate']} none->UNK={r['none_to_unknown_rate']} (via classify())")
        report["splits"][split] = sres
    update_metrics("light_color", report)
    print("wrote", OUT / "metrics.json")


if __name__ == "__main__":
    main()
