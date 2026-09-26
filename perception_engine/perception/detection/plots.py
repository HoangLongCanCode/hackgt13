"""PNG figures from outputs/detection/metrics.json (+ stored predictions). CPU only.

    .venv\\Scripts\\python.exe -m perception.detection.plots
"""
from __future__ import annotations

import json

import cv2
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

from perception.common.schemas import Detection, canonical_class
from perception.common.video import DATA_ROOT, OUTPUTS_ROOT
from perception.detection.detector import COCO_TO_BDD
from perception.detection.viz import draw_detections

OUT = OUTPUTS_ROOT / "detection"
# dataviz reference palette (light mode)
S1, S2, S3 = "#2a78d6", "#eb6834", "#1baf7a"
INK, INK2, MUTED, GRID, BASE, SURF = "#0b0b0b", "#52514e", "#898781", "#e1e0d9", "#c3c2b7", "#fcfcfb"
SEQ = ["#cde2fb", "#b7d3f6", "#9ec5f4", "#86b6ef", "#6da7ec", "#5598e7", "#3987e5", "#2a78d6", "#256abf",
       "#1c5cab", "#184f95", "#104281", "#0d366b"]

plt.rcParams.update({"font.family": ["Segoe UI", "DejaVu Sans"], "font.size": 9, "axes.edgecolor": BASE,
                     "axes.labelcolor": INK2, "xtick.color": MUTED, "ytick.color": MUTED, "text.color": INK,
                     "axes.facecolor": SURF, "figure.facecolor": SURF, "axes.grid": True, "grid.color": GRID,
                     "grid.linewidth": 0.6, "axes.spines.top": False, "axes.spines.right": False})


def _style(ax):
    ax.set_axisbelow(True)
    ax.tick_params(length=0)


def fig_bars(m):
    runs = list(m["runs"])
    bs = m.get("bootstrap", {}).get("runs", {})
    order = sorted(runs, key=lambda r: m["runs"][r]["map8"]["mAP50_95"])
    y = np.arange(len(order))
    fig, ax = plt.subplots(figsize=(7.5, 0.42 * len(order) + 1.3))
    h = 0.38
    for off, cs, col, lab in ((-h / 2, "map8", S1, "8 COCO-mappable classes (all models)"),
                              (h / 2, "all10", S2, "all 10 BDD classes (BDD-trained only)")):
        vals = [m["runs"][r][cs]["mAP50_95"] * 100 if cs in m["runs"][r] else np.nan for r in order]
        err = [[(m["runs"][r][cs]["mAP50_95"] - bs[r][cs]["ci95"][0]) * 100 if r in bs and cs in bs[r] else 0 for r in order],
               [(bs[r][cs]["ci95"][1] - m["runs"][r][cs]["mAP50_95"]) * 100 if r in bs and cs in bs[r] else 0 for r in order]]
        ax.barh(y + off, vals, height=h - 0.04, color=col, label=lab, xerr=err,
                error_kw=dict(ecolor=INK2, lw=0.8, capsize=2))
        for yi, v in zip(y + off, vals):
            if not np.isnan(v):
                ax.text(0.4, yi, f"{v:.1f}", va="center", ha="left", color="white", fontsize=7.5, fontweight="bold")
    ax.set_yticks(y, order)
    ax.set_xlabel("COCO mAP@[.50:.95] (%) on 250 BDD100K val images, whiskers = 95% bootstrap CI")
    ax.set_title("Detector accuracy on the local BDD100K subset (measured here)", loc="left", fontsize=10.5)
    ax.legend(loc="lower right", frameon=False, fontsize=8)
    _style(ax)
    ax.grid(axis="y", visible=False)
    fig.tight_layout()
    fig.savefig(OUT / "map_by_run.png", dpi=150)
    plt.close(fig)


def fig_scatter(m):
    fig, ax = plt.subplots(figsize=(7, 4.4))
    for r, v in m["runs"].items():
        col = S1 if v["label_space"] == "bdd" else S2
        mk = "o" if v["config"]["backend"] == "ultralytics" else "s"
        li = v.get("latency_interleaved_ms")
        x = li["p50_ms"] if li else v["latency_ms"]["p50"]
        yv = v["map8"]["mAP50_95"] * 100
        if li:  # horizontal whisker = spread of per-round p50 (GPU contention from other jobs)
            ax.plot([min(li["round_p50"]), max(li["round_p50"])], [yv, yv], color=col, lw=1, alpha=0.5, zorder=2)
        ax.scatter(x, yv, s=64, c=col, marker=mk, edgecolors=SURF, linewidths=2, zorder=3)
        ax.annotate(r, (x, yv), xytext=(6, 4), textcoords="offset points", fontsize=7.5, color=INK2)
    from matplotlib.lines import Line2D
    hs = [Line2D([], [], marker="o", ls="", color=S1, label="BDD100K-trained"),
          Line2D([], [], marker="o", ls="", color=S2, label="COCO-trained + class map"),
          Line2D([], [], marker="o", ls="", color=MUTED, label="Ultralytics YOLO26 (AGPL-3.0)"),
          Line2D([], [], marker="s", ls="", color=MUTED, label="RF-DETR (Apache-2.0)")]
    ax.legend(handles=hs, frameon=False, fontsize=7.5, loc="center left")
    ax.set_xlabel("detect() p50 ms, median of 3 interleaved rounds (line = round spread); PROVISIONAL, shared GPU")
    ax.set_ylabel("mAP@[.50:.95] (%), 8 mappable classes")
    ax.set_title("Accuracy vs latency, RTX 5060 Laptop, PyTorch eager FP16 (measured here)", loc="left", fontsize=10.5)
    _style(ax)
    fig.tight_layout()
    fig.savefig(OUT / "accuracy_vs_latency.png", dpi=150)
    plt.close(fig)


def fig_heatmap(m):
    runs = [r for r in m["runs"] if not r.endswith(":e2e")]
    classes = list(m["dataset"]["n_gt_per_class"])
    ngt = m["dataset"]["n_gt_per_class"]
    A = np.full((len(runs), len(classes)), np.nan)
    for i, r in enumerate(runs):
        pc = m["runs"][r].get("all10", m["runs"][r]["map8"])["per_class"]
        for j, c in enumerate(classes):
            if c in pc and pc[c]["AP"] is not None:
                A[i, j] = pc[c]["AP"] * 100
    from matplotlib.colors import ListedColormap
    cmap = ListedColormap(SEQ)
    cmap.set_bad("#f0efec")
    fig, ax = plt.subplots(figsize=(9.5, 0.45 * len(runs) + 1.8))
    im = ax.imshow(np.ma.masked_invalid(A), cmap=cmap, vmin=0, vmax=60, aspect="auto")
    for i in range(len(runs)):
        for j in range(len(classes)):
            v = A[i, j]
            ax.text(j, i, "n/a" if np.isnan(v) else f"{v:.0f}", ha="center", va="center", fontsize=7.5,
                    color=(MUTED if np.isnan(v) else ("white" if v > 32 else INK)))
    ax.set_xticks(range(len(classes)), [f"{c}\n(n={ngt[c]})" for c in classes], fontsize=7.5)
    ax.set_yticks(range(len(runs)), runs, fontsize=8)
    ax.grid(False)
    ax.set_title("Per-class AP@[.50:.95] (%), n = GT boxes; n/a = class not produced by COCO models",
                 loc="left", fontsize=10)
    cb = fig.colorbar(im, ax=ax, fraction=0.025, pad=0.01)
    cb.outline.set_visible(False)
    cb.ax.tick_params(length=0, labelsize=7)
    _style(ax)
    fig.tight_layout()
    fig.savefig(OUT / "per_class_ap.png", dpi=150)
    plt.close(fig)


def fig_timeofday(m):
    runs = [r for r in m["runs"] if not r.endswith(":e2e")]
    tods = ["daytime", "night", "dawn/dusk"]
    cols = [S1, S2, S3]
    x = np.arange(len(runs))
    w = 0.26
    fig, ax = plt.subplots(figsize=(10, 4.2))
    for k, (t, c) in enumerate(zip(tods, cols)):
        vals = [m["runs"][r]["map8"]["timeofday"][t]["mAP50_95"] * 100 for r in runs]
        n = m["dataset"]["timeofday_images"][t]
        ax.bar(x + (k - 1) * w, vals, width=w - 0.03, color=c, label=f"{t} ({n} images)")
    ax.set_xticks(x, runs, rotation=25, ha="right", fontsize=7.5)
    ax.set_ylabel("mAP@[.50:.95] (%), 8 mappable classes")
    ax.set_title("Time-of-day slices (measured here; dawn/dusk has only 34 images, so treat it as noisy)",
                 loc="left", fontsize=10)
    ax.legend(frameon=False, fontsize=8)
    _style(ax)
    ax.grid(axis="x", visible=False)
    fig.tight_layout()
    fig.savefig(OUT / "timeofday_slices.png", dpi=150)
    plt.close(fig)


def _dets_from_raw(raw_img, space, conf=0.25):
    out = []
    for b, s, n in zip(raw_img["xyxy"], raw_img["scores"], raw_img["names"]):
        c = canonical_class(n) if space == "bdd" else COCO_TO_BDD.get(n)
        if c is not None and s >= conf:
            out.append(Detection(c, b, s))
    return out


def fig_examples(m, runs=("bdd-yolo26s@960", "coco-yolo26s@640"), stems=("b1ff4656-0435391e", "b23adb0d-8a7aaced")):
    gt = json.loads((DATA_ROOT / "labels" / "eval_subset_coco.json").read_text())
    id_of = {im["file_name"][:-4]: im["id"] for im in gt["images"]}
    cname = {c["id"]: c["name"] for c in gt["categories"]}
    preds = {}
    for r in runs:
        p = OUT / "preds" / (r.replace("@", "_") + ".json")
        if p.exists():
            preds[r] = {x["image_id"]: x for x in json.loads(p.read_text())["raw"]}
    cols = ["ground truth"] + [r for r in runs if r in preds]
    fig, axs = plt.subplots(len(stems), len(cols), figsize=(5.2 * len(cols), 3.1 * len(stems)))
    for i, s in enumerate(stems):
        img = cv2.imread(str(DATA_ROOT / "images" / "val" / f"{s}.jpg"))
        iid = id_of[s]
        for j, c in enumerate(cols):
            if c == "ground truth":
                dets = [Detection(cname[a["category_id"]], [a["bbox"][0], a["bbox"][1], a["bbox"][0] + a["bbox"][2] - 1,
                                                            a["bbox"][1] + a["bbox"][3] - 1], 1.0)
                        for a in gt["annotations"] if a["image_id"] == iid]
                vis = draw_detections(img.copy(), dets, show_conf=False)
                title = f"{s}: ground truth ({len(dets)} boxes)"
            else:
                space = m["runs"][c]["label_space"]
                dets = _dets_from_raw(preds[c][iid], space)
                vis = draw_detections(img.copy(), dets)
                title = f"{c}, conf>=0.25 ({len(dets)} boxes)"
            ax = axs[i, j]
            ax.imshow(vis[:, :, ::-1])
            ax.set_title(title, fontsize=8.5, loc="left")
            ax.axis("off")
    fig.tight_layout()
    fig.savefig(OUT / "examples_day_night.png", dpi=110)
    plt.close(fig)


def main() -> int:
    m = json.loads((OUT / "metrics.json").read_text())
    fig_bars(m)
    fig_scatter(m)
    fig_heatmap(m)
    fig_timeofday(m)
    fig_examples(m)
    print("wrote", sorted(p.name for p in OUT.glob("*.png")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
