"""Random search of HSV light-color thresholds on the DEV split (val_lights), with the TEST split (subset) reported only.

    .venv\\Scripts\\python.exe -m perception.traffic.tune_hsv --trials 400

Objective: 4-class macro-F1 (red, yellow, green, none->UNKNOWN) on val_lights, minus
a 2x penalty on the red->GREEN rate (the safety-relevant confusion). Prints the best
params, which are then copied into HSVParams defaults by hand (see README).
Writes outputs/traffic/hsv_tuning.json.
"""
from __future__ import annotations

import argparse
import dataclasses
import json
import random

import numpy as np

from perception.traffic.eval_bdd import OUT, load_crops, load_gt, score
from perception.traffic.lights import HSVParams, hsv_probs


def evaluate(p: HSVParams, crops, gts):
    P = np.stack([hsv_probs(c, p) for c in crops])
    return score(gts, P.argmax(1))


def objective(r: dict) -> float:
    return r["macro_f1_4class"] - 2.0 * (r["red_called_green_rate"] or 0.0)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--trials", type=int, default=400)
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()
    dev_items, _ = load_gt("val_lights")
    test_items, _ = load_gt("subset")
    dev_crops, test_crops = load_crops(dev_items), load_crops(test_items)
    dev_gt, test_gt = [i["color"] for i in dev_items], [i["color"] for i in test_items]
    rng = random.Random(args.seed)
    space = {
        "s_min": [40, 55, 70, 85, 100, 120], "v_min": [80, 100, 120, 140, 160],
        "v_rel": [0.0, 0.5, 0.6, 0.7, 0.8, 0.9], "red_hi": [5, 7, 9, 12],
        "red_lo2": [150, 155, 160, 165, 170], "yel_lo": [14, 17, 19, 22, 25], "yel_hi": [30, 34, 38, 42],
        "grn_lo": [45, 55, 60, 65, 70, 75], "grn_hi": [95, 100, 105, 110],
        "min_color": [0.01, 0.02, 0.03, 0.04, 0.06, 0.08, 0.12], "pos_weight": [0.0, 0.5, 1.0, 1.5, 2.0, 3.0],
        "pos_sigma": [0.12, 0.16, 0.2, 0.25, 0.3], "min_contrast": [0.0, 10.0, 20.0, 30.0, 40.0, 55.0],
    }
    base = HSVParams()  # starts from the current defaults (already tuned: seed 0 x400, then seed 1 x500)
    best = (objective(evaluate(base, dev_crops, dev_gt)), dataclasses.asdict(base))
    print("baseline dev objective", round(best[0], 4))
    hist = []
    for t in range(args.trials):
        # half random, half local perturbation of the incumbent
        cand = dict(best[1])
        keys = list(space) if t % 2 == 0 else rng.sample(list(space), 2)
        for k in keys:
            cand[k] = rng.choice(space[k])
        p = HSVParams(**cand)
        obj = objective(evaluate(p, dev_crops, dev_gt))
        hist.append(obj)
        if obj > best[0]:
            best = (obj, cand)
            print(f"trial {t}: dev objective {obj:.4f} {cand}")
    bp = HSVParams(**best[1])
    dev_r, test_r = evaluate(bp, dev_crops, dev_gt), evaluate(bp, test_crops, test_gt)
    res = {"best_params": best[1], "dev_objective": best[0],
           "dev": {k: dev_r[k] for k in ("lit_accuracy", "macro_f1_4class", "per_class_recall", "red_called_green_rate", "none_to_unknown_rate")},
           "test_subset": {k: test_r[k] for k in ("lit_accuracy", "macro_f1_4class", "per_class_recall", "red_called_green_rate", "none_to_unknown_rate")},
           "trials": args.trials, "seed": args.seed}
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "hsv_tuning.json").write_text(json.dumps(res, indent=1, default=float))
    print(json.dumps(res, indent=1, default=float))


if __name__ == "__main__":
    main()
