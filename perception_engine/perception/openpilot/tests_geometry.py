r"""Check op_geometry's numpy ports against the vendored openpilot v0.11.1 originals.

Run (from perception_engine/):  .venv\Scripts\python.exe -m perception.openpilot.tests_geometry
Needs perception/third_party/openpilot (sparse clone of v0.11.1: python scripts/fetch_third_party.py).
"""
import sys
from pathlib import Path

import numpy as np

from perception.common.paths import THIRD_PARTY as TP  # noqa: E402  (PERCEPTION_THIRD_PARTY_DIR overrides)


def main() -> int:
    sys.path.insert(0, str(TP))  # makes `import openpilot.common...` resolve to the vendored checkout
    from openpilot.common.transformations import model as opm, camera as opc, orientation as opo
    from openpilot.selfdrive.modeld.constants import ModelConstants
    from perception.openpilot import op_geometry as G

    rng = np.random.default_rng(0)
    K = G.intrinsics(1100.0, 1280, 720)
    worst = 0.0
    for _ in range(200):
        rpy = rng.uniform([-0.05, -0.2, -0.1], [0.05, 0.2, 0.1])
        for big in (False, True):
            a = G.get_warp_matrix(rpy, K, big)
            b = opm.get_warp_matrix(rpy, K, big)
            worst = max(worst, float(np.abs(a - b).max() / np.abs(b).max()))
        worst = max(worst, float(np.abs(G.rot_from_euler(rpy) - opo.rot_from_euler(rpy)).max()))
        worst = max(worst, float(np.abs(G.euler_from_rot(G.rot_from_euler(rpy)) - opo.euler_from_rot(opo.rot_from_euler(rpy))).max()))
        vp = rng.uniform([500, 250], [780, 450])
        worst = max(worst, float(np.abs(G.calib_from_vp(vp, K) - np.array(opc.get_calib_from_vp(vp, K))).max()))
        # round trip: calib from vp -> vp
        worst = max(worst, float(np.abs(G.vp_from_calib(G.calib_from_vp(vp, K), K) - vp).max()) / 1000)
    assert np.allclose(G.X_IDXS, ModelConstants.X_IDXS) and np.allclose(G.T_IDXS, ModelConstants.T_IDXS)
    assert G.MODEL_CONTEXT_FREQ == ModelConstants.MODEL_CONTEXT_FREQ
    print(f"max relative deviation vs openpilot v0.11.1: {worst:.2e}")
    assert worst < 1e-9, worst
    # ground back-projection / projection round trip
    rpy = np.array([0.0, 0.1, -0.05])
    uv = np.array([[640.0, 500.0], [300.0, 600.0], [900.0, 420.0]])
    g = G.backproject_to_ground(uv, rpy, K, 1.3)
    assert np.allclose(g[:, 2], 1.3) and np.allclose(G.project_calib_points(g, rpy, K), uv)
    print("OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
