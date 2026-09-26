"""Calibrate the geometric priors of the lane-state estimator on BDD100K GT.

Uses 600 val images that are NOT in the 250-image eval subset (YOLOP-protocol lane / drivable
masks cached in outputs/lanes/_cache/calib_{ll,da}); the post-processor runs on the GT masks.

  lane_w_ratio        ego-lane width [px] / (y - y_vp), scale-free. Estimated as
                      current ratio x median measured ego width in u (both ego boundaries lines)
  horizon_default     median vanishing-point row / H (used when no VP is found)
  x_ego_offset_frac   median (x_vp - W/2) / W (camera yaw/offset proxy used when no VP is found)
  u_centre            median ego-lane centre in u (driver/camera lateral offset; reported only)

Usage: python -m perception.lanes.tools.calibrate_priors
"""
import glob
import json
import os

import cv2
import numpy as np

from perception.common.video import OUTPUTS_ROOT
from perception.lanes.postprocess import PostConfig, process_masks

CACHE = OUTPUTS_ROOT / "lanes" / "_cache"


def load_gt_small(path, kind):
    m = cv2.imread(str(path), cv2.IMREAD_UNCHANGED)
    if m.ndim == 3:
        m = m[..., 0]
    if kind == "ll":   # TLNP/YOLOP protocol: bilinear resize then > 1
        return cv2.resize(m, (640, 360), interpolation=cv2.INTER_LINEAR) > 1
    return cv2.resize((m > 0).astype(np.uint8), (640, 360), interpolation=cv2.INTER_NEAREST) > 0


def main():
    cfg = PostConfig()
    files = sorted(glob.glob(str(CACHE / "calib_ll" / "*.png")))
    wus, ucs, vpy, vpx, htop = [], [], [], [], []
    for f in files:
        ll = load_gt_small(f, "ll")
        da = load_gt_small(str(CACHE / "calib_da" / os.path.basename(f)), "da")
        res = process_masks(ll, da, cfg)
        st = res["state"]
        if res["vp"] is None:
            continue
        vpy.append(res["vp"][1] / 360)
        vpx.append((res["vp"][0] - 320) / 640)
        htop.append(res["y_h0"] / 360)
        if st.get("ok") and st["L0"]["kind"] == "line" and st["R0"]["kind"] == "line":
            wus.append(st["w_u"])
            ucs.append((st["L0"]["u"] + st["R0"]["u"]) / 2)
    out = {
        "n_images": len(files), "n_with_vp": len(vpy), "n_ego_line_pairs": len(wus),
        "current_lane_w_ratio": cfg.lane_w_ratio,
        "ego_width_u_median": round(float(np.median(wus)), 3),
        "ego_width_u_p25_p75": np.percentile(wus, [25, 75]).round(3).tolist(),
        "lane_w_ratio_estimate": round(float(cfg.lane_w_ratio * np.median(wus)), 3),
        "u_centre_median": round(float(np.median(ucs)), 3),
        "vp_y_frac_median": round(float(np.median(vpy)), 3),
        "vp_y_frac_p10_p90": np.percentile(vpy, [10, 90]).round(3).tolist(),
        "vp_x_offset_frac_median": round(float(np.median(vpx)), 3),
        "vp_minus_drivable_top_frac_median": round(float(np.median(np.array(vpy) - np.array(htop))), 3),
    }
    print(json.dumps(out, indent=1))
    (OUTPUTS_ROOT / "lanes" / "calibration.json").write_text(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
