"""Synthetic checks for the motion layer (no data needed).

A pinhole camera (f = 1000 px) watches a car of 1.8 m x 1.5 m. Box edges get Gaussian
pixel noise, and we check that the scale-rate / TTC / approaching outputs recover the
known ground truth.

    .venv\\Scripts\\python.exe -m perception.tracking.test_motion
"""
from __future__ import annotations

import json
import math

import numpy as np

from perception.tracking.motion import MotionConfig, MotionEstimator, theil_sen

F, W_OBJ, H_OBJ, CX, CY = 1000.0, 1.8, 1.5, 640.0, 360.0


def box_at(z: float, x_lat: float = 0.0, noise: float = 0.0, rng=None) -> list[float]:
    w, h = F * W_OBJ / z, F * H_OBJ / z
    cx = CX + F * x_lat / z
    cy = CY + F * 0.6 / z  # object sits slightly below the horizon
    b = np.array([cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2])
    if noise and rng is not None:
        b = b + rng.normal(0, noise, 4)
    return b.tolist()


def run_case(z0: float, v_close: float, fps: float, dur: float, noise: float, seed: int = 0,
             lat_v: float = 0.0, x0: float = 0.5, **cfg) -> dict:
    rng = np.random.default_rng(seed)
    me = MotionEstimator(MotionConfig(**cfg))
    errs, rel_errs, flags, lat_err, closing = [], [], [], [], []
    n = int(dur * fps)
    for k in range(n):
        t = k / fps
        z = z0 - v_close * t
        if z < 3.0:
            break
        x_lat = x0 + lat_v * t
        o = me.update([(1, "car", box_at(z, x_lat, noise, rng))], t, (720, 1280))[1]
        closing.append(bool(o.closing))
        true_ttc = z / v_close if v_close > 0 else math.inf
        if o.scale_rate is not None:
            flags.append(bool(o.approaching))
            if v_close > 0 and o.ttc_s is not None and true_ttc <= 30:
                errs.append(abs(o.ttc_s - true_ttc))
                rel_errs.append(abs(o.ttc_s - true_ttc) / true_ttc)
            if o.lateral_widths_s is not None:
                lat_err.append(abs(o.lateral_widths_s * W_OBJ - lat_v))
    return {
        "z0_m": z0, "closing_mps": v_close, "fps": fps, "noise_px": noise,
        "n_estimates": len(flags),
        "ttc_abs_err_p50_s": float(np.median(errs)) if errs else None,
        "ttc_rel_err_p50": float(np.median(rel_errs)) if rel_errs else None,
        "ttc_rel_err_p90": float(np.percentile(rel_errs, 90)) if rel_errs else None,
        "approaching_true_fraction": float(np.mean(flags)) if flags else None,
        "closing_true_fraction": float(np.mean(closing)) if closing else None,
        "lateral_mps_abs_err_p50": float(np.median(lat_err)) if lat_err else None,
    }


def main() -> dict:
    # Theil-Sen sanity check: exact line
    t = np.linspace(0, 1, 31)
    s, lo, hi = theil_sen(t, 0.2 * t + 3.0)
    assert abs(s - 0.2) < 1e-9 and lo <= s <= hi
    cases = {
        "approach_30fps_noise1px": run_case(30, 5.0, 30, 5.0, 1.0),
        "approach_30fps_noise2px": run_case(30, 5.0, 30, 5.0, 2.0),
        "approach_5fps_noise1px": run_case(30, 5.0, 5, 5.0, 1.0),
        "approach_close_30fps": run_case(15, 5.0, 30, 2.0, 1.0),
        "no_lag_comp_close_30fps": run_case(15, 5.0, 30, 2.0, 1.0, lag_compensate=False),
        "constant_gap_30fps": run_case(20, 0.0, 30, 5.0, 1.0),
        "receding_30fps": run_case(20, -3.0, 30, 5.0, 1.0),
        "far_slow_30fps_noise1px": run_case(40, 1.0, 30, 5.0, 1.0),
        "lateral_1mps_30fps": run_case(20, 0.0, 30, 4.0, 1.0, lat_v=1.0),
        "approach_parked_on_shoulder_30fps": run_case(30, 5.0, 30, 4.0, 1.0, x0=7.0),
    }
    checks = {
        "approach detected (>=80% of estimates)": cases["approach_30fps_noise1px"]["approaching_true_fraction"] >= 0.8,
        "median TTC rel err < 15% @1px 30fps": cases["approach_30fps_noise1px"]["ttc_rel_err_p50"] < 0.15,
        "constant gap never approaching": cases["constant_gap_30fps"]["approaching_true_fraction"] == 0.0,
        "receding never approaching": cases["receding_30fps"]["approaching_true_fraction"] == 0.0,
        "shoulder object: closing but not approaching (corridor gate)": (
            cases["approach_parked_on_shoulder_30fps"]["closing_true_fraction"] > 0.5
            and cases["approach_parked_on_shoulder_30fps"]["approaching_true_fraction"] == 0.0),
        "lag compensation helps close range": (cases["approach_close_30fps"]["ttc_abs_err_p50_s"]
                                                < cases["no_lag_comp_close_30fps"]["ttc_abs_err_p50_s"]),
    }
    return {"cases": cases, "checks": checks, "all_pass": all(checks.values())}


if __name__ == "__main__":
    r = main()
    print(json.dumps(r, indent=1))
    raise SystemExit(0 if r["all_pass"] else 1)
