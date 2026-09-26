"""BDD100K MOT (box_track_20) evaluation, simplified port of scalabel's protocol.

What is implemented, following scalabel.label.eval.mot.acc_single_video_mot as used by
``python -m bdd100k.eval.run -t box_track``:

* 8 classes are evaluated separately: pedestrian, rider, car, truck, bus, train,
  motorcycle, bicycle. A prediction only matches a GT box of the same class, at
  IoU >= 0.5 (motmetrics distance 1 - IoU, max 0.5).
* Ignore regions are class-agnostic. They are GT boxes with ``crowd=True`` or category
  in {other person, other vehicle, trailer}. Per class and frame, a prediction that is
  a false positive after Hungarian matching and has intersection-over-its-own-area
  > 0.5 with any ignore region is dropped before the accumulator update. Ignore boxes
  never count as misses.
* Per-class CLEAR/ID metrics (motmetrics 1.4.0) are summed over all sequences (the
  same as scalabel merging per-video accumulators). The class-averaged mMOTA / mIDF1 /
  mHOTA use only classes that have GT in the evaluated set. Overall MOTA/IDF1 merge
  every class and sequence accumulator.
* HOTA (Luiten et al. IJCV 2021) comes from Roboflow ``trackers.eval.compute_hota_metrics``,
  a port of TrackEval, with the same ignore filtering.

Simplifications versus the official toolkit, documented in the README:

* The official toolkit also maps ``ignored`` distractor labels to their parent class,
  and it reports super-categories (HUMAN / VEHICLE / BIKE). Here super-categories are
  derived by merging the member classes' accumulators, and every distractor or crowd
  box is simply an ignore region.
* motmetrics' own iou_matrix uses np.asfarray (removed in NumPy 2), so the IoU/distance
  matrix is computed here with the same definition.
* Box convention (review fix): scalabel's parse_objects converts every box2d, GT, ignore
  and prediction alike, with ``box2d_to_bbox`` = [x1, y1, x2 - x1 + 1, y2 - y1 + 1]
  before ``mm.distances.iou_matrix`` and ``intersection_over_area``. ``plus_one=True``
  (the default) reproduces that, by using x2 + 1 and y2 + 1. The builder's first version
  had no +1: overall MOTA was 0.17 points lower for the default tracker.
* Class average (review note): the official ``compute_average`` averages all 8 leaf
  classes with NaN/inf set to 0. ``mMOTA`` / ``mIDF1`` / ``mHOTA`` here average only the
  classes that have GT in the evaluated subset (``train`` has none in the 7 local
  sequences). The official-style value is reported as ``mMOTA_8cls_nan0`` /
  ``mIDF1_8cls_nan0``.
"""
from __future__ import annotations

import json
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable

import numpy as np
from scipy.optimize import linear_sum_assignment

EVAL_CLASSES = ("pedestrian", "rider", "car", "truck", "bus", "train", "motorcycle", "bicycle")
IGNORE_CATEGORIES = {"other person", "other vehicle", "trailer"}
SUPER = {"HUMAN": ("pedestrian", "rider"), "VEHICLE": ("car", "truck", "bus", "train"),
         "BIKE": ("motorcycle", "bicycle")}
METRICS = ["num_frames", "num_objects", "num_predictions", "num_matches", "num_false_positives", "num_misses",
           "num_switches", "num_fragmentations", "mostly_tracked", "mostly_lost", "num_unique_objects",
           "mota", "motp", "idf1", "idp", "idr", "precision", "recall"]


def iou(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    a = np.asarray(a, float).reshape(-1, 4); b = np.asarray(b, float).reshape(-1, 4)
    if len(a) == 0 or len(b) == 0:
        return np.zeros((len(a), len(b)))
    iw = np.clip(np.minimum(a[:, None, 2], b[None, :, 2]) - np.maximum(a[:, None, 0], b[None, :, 0]), 0, None)
    ih = np.clip(np.minimum(a[:, None, 3], b[None, :, 3]) - np.maximum(a[:, None, 1], b[None, :, 1]), 0, None)
    inter = iw * ih
    aa = (a[:, 2] - a[:, 0]) * (a[:, 3] - a[:, 1]); ab = (b[:, 2] - b[:, 0]) * (b[:, 3] - b[:, 1])
    return inter / np.maximum(aa[:, None] + ab[None, :] - inter, 1e-9)


def ioa(pred: np.ndarray, regions: np.ndarray) -> np.ndarray:
    """Intersection over the *prediction's* area, shape (P, R)."""
    p = np.asarray(pred, float).reshape(-1, 4); r = np.asarray(regions, float).reshape(-1, 4)
    if len(p) == 0 or len(r) == 0:
        return np.zeros((len(p), len(r)))
    iw = np.clip(np.minimum(p[:, None, 2], r[None, :, 2]) - np.maximum(p[:, None, 0], r[None, :, 0]), 0, None)
    ih = np.clip(np.minimum(p[:, None, 3], r[None, :, 3]) - np.maximum(p[:, None, 1], r[None, :, 1]), 0, None)
    ap = (p[:, 2] - p[:, 0]) * (p[:, 3] - p[:, 1])
    return iw * ih / np.maximum(ap[:, None], 1e-9)


def load_gt(path: str | Path) -> list[dict[str, Any]]:
    """Scalabel box_track_20 JSON -> per-frame dict(name, frameIndex, boxes, ids, cls, ignore)."""
    frames = sorted(json.load(open(path, encoding="utf-8")), key=lambda f: f["frameIndex"])
    out = []
    for f in frames:
        boxes, ids, cls, ign = [], [], [], []
        for lab in f.get("labels") or []:
            b = lab.get("box2d")
            if b is None:
                continue
            bb = [b["x1"], b["y1"], b["x2"], b["y2"]]
            crowd = bool((lab.get("attributes") or {}).get("crowd", False))
            if crowd or lab["category"] in IGNORE_CATEGORIES:
                ign.append(bb)
            elif lab["category"] in EVAL_CLASSES:
                boxes.append(bb); ids.append(str(lab["id"])); cls.append(lab["category"])
        out.append({"name": f["name"], "frameIndex": f["frameIndex"],
                    "boxes": np.asarray(boxes, float).reshape(-1, 4), "ids": ids, "cls": cls,
                    "ignore": np.asarray(ign, float).reshape(-1, 4)})
    return out


def plus_one(boxes: np.ndarray) -> np.ndarray:
    """xyxy -> xyxy with x2 + 1, y2 + 1 (scalabel box2d_to_bbox width/height convention)."""
    b = np.array(boxes, dtype=float).reshape(-1, 4)
    b[:, 2:] += 1.0
    return b


class BddMotEvaluator:
    def __init__(self, classes: Iterable[str] = EVAL_CLASSES, iou_thr: float = 0.5, ignore_ioa: float = 0.5,
                 plus_one: bool = True):
        import motmetrics as mm

        self.mm = mm
        self.classes = tuple(classes)
        self.iou_thr = iou_thr
        self.ignore_ioa = ignore_ioa
        self.plus_one = plus_one
        self.accs = {c: mm.MOTAccumulator(auto_id=False) for c in self.classes}
        self.hota_in = {c: ([], [], []) for c in self.classes}  # gt_ids, trk_ids, sims per frame
        self._gid: dict[str, dict[str, int]] = defaultdict(dict)
        self._frame = 0
        self.sequences: list[str] = []

    def _int_id(self, space: str, key: str) -> int:
        d = self._gid[space]
        if key not in d:
            d[key] = len(d)
        return d[key]

    def add_sequence(self, seq: str, gt: list[dict], pred: dict[int, tuple[list, list, list]]) -> None:
        """pred: frameIndex -> (ids, classes, boxes xyxy). Missing frames count as empty."""
        self.sequences.append(seq)
        for g in gt:
            fid = self._frame
            self._frame += 1
            p_ids, p_cls, p_boxes = pred.get(g["frameIndex"], ([], [], []))
            p_boxes = np.asarray(p_boxes, float).reshape(-1, 4)
            g_boxes, g_ignore = g["boxes"], g["ignore"]
            if self.plus_one:  # scalabel box2d_to_bbox: w = x2 - x1 + 1, h = y2 - y1 + 1
                p_boxes, g_boxes, g_ignore = plus_one(p_boxes), plus_one(g_boxes), plus_one(g_ignore)
            p_cls = np.asarray(p_cls, dtype=object)
            # motmetrics 1.4 needs numeric ids: map "<seq>:<id>" to globally unique ints
            p_ids = np.asarray([self._int_id("P", f"{seq}:{i}") for i in p_ids], dtype=np.int64)
            g_cls = np.asarray(g["cls"], dtype=object)
            g_ids = np.asarray([self._int_id("G", f"{seq}:{i}") for i in g["ids"]], dtype=np.int64)
            for c in self.classes:
                gm, pm = g_cls == c, p_cls == c
                gb, pb = g_boxes[gm], p_boxes[pm]
                gi, pi = g_ids[gm], p_ids[pm]
                ious = iou(gb, pb)
                dist = np.where(ious >= self.iou_thr, 1.0 - ious, np.nan)
                if len(g_ignore) and len(pb):
                    fp = np.ones(len(pb), bool)
                    if len(gb):
                        cost = np.where(np.isfinite(dist), dist, 1e6)
                        r, k = linear_sum_assignment(cost)
                        for m, n in zip(r, k):
                            if np.isfinite(dist[m, n]):
                                fp[n] = False
                    ignored = (ioa(pb, g_ignore) > self.ignore_ioa).any(axis=1)
                    keep = ~(fp & ignored)
                    pb, pi, dist, ious = pb[keep], pi[keep], dist[:, keep], ious[:, keep]
                if len(gb) == 0 and len(pb) == 0:
                    continue
                self.accs[c].update(list(gi), list(pi), dist, frameid=fid)
                h = self.hota_in[c]
                h[0].append(gi.astype(int))
                h[1].append(pi.astype(int))
                h[2].append(ious)

    def _summ(self, accs: list, names: list[str]) -> dict[str, float]:
        mh = self.mm.metrics.create()
        df = mh.compute_many(accs, metrics=METRICS, names=names, generate_overall=True)
        row = df.loc["OVERALL"] if len(accs) > 1 else df.iloc[0]
        return {k: (float(row[k]) if np.isfinite(row[k]) else None) for k in METRICS}

    def _hota(self, cs: Iterable[str]) -> dict[str, float]:
        from trackers.eval.hota import compute_hota_metrics

        g, p, s = [], [], []
        for i, c in enumerate(cs):
            gi, pi, si = self.hota_in[c]
            # disjoint id spaces across classes when merging
            g += [x + i * 10_000_000 for x in gi]; p += [x + i * 10_000_000 for x in pi]; s += si
        if not g:
            return {}
        r = compute_hota_metrics(g, p, s)
        return {k: float(r[k]) for k in ("HOTA", "DetA", "AssA", "LocA")}

    def compute(self) -> dict[str, Any]:
        per_class = {}
        for c in self.classes:
            acc = self.accs[c]
            if len(acc.events) == 0:
                per_class[c] = {"num_objects": 0, "note": "no GT and no predictions"}
                continue
            m = self._summ([acc], [c])
            m.update(self._hota([c]))
            per_class[c] = m
        with_gt = [c for c in self.classes if per_class[c].get("num_objects")]
        mean = lambda k: float(np.mean([per_class[c][k] for c in with_gt if per_class[c].get(k) is not None])) \
            if with_gt else None
        supers = {}
        for s, members in SUPER.items():
            ms = [c for c in members if len(self.accs[c].events)]
            if ms:
                m = self._summ([self.accs[c] for c in ms], ms)
                m.update(self._hota(ms))
                supers[s] = {k: m[k] for k in ("mota", "idf1", "num_switches", "num_objects", "HOTA") if k in m}
        overall = self._summ([self.accs[c] for c in self.classes if len(self.accs[c].events)],
                             [c for c in self.classes if len(self.accs[c].events)])
        overall.update(self._hota([c for c in self.classes if len(self.accs[c].events)]))
        if overall.get("motp") is None:  # motmetrics' merged MOTP is NaN when one class has no matches
            w = [(per_class[c]["motp"], per_class[c]["num_matches"]) for c in with_gt
                 if per_class[c].get("motp") is not None and per_class[c].get("num_matches")]
            overall["motp"] = float(sum(m * n for m, n in w) / sum(n for _, n in w)) if w else None

        def mean_all(k):  # scalabel compute_average: every leaf class, NaN/inf -> 0
            v = [per_class[c].get(k) for c in self.classes]
            return float(np.mean([x if x is not None and np.isfinite(x) else 0.0 for x in v]))

        return {
            "sequences": self.sequences,
            "classes_with_gt": with_gt,
            "plus_one_box_convention": self.plus_one,
            "mMOTA": mean("mota"), "mIDF1": mean("idf1"), "mHOTA": mean("HOTA"),
            "mMOTA_8cls_nan0": mean_all("mota"), "mIDF1_8cls_nan0": mean_all("idf1"),
            "overall": overall,
            "super_categories": supers,
            "per_class": per_class,
        }
