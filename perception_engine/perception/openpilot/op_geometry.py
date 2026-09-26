"""Camera / frame geometry for running openpilot's driving_vision model on non-comma video.

Small numpy ports of openpilot v0.11.1 code (MIT, Copyright (c) 2018 Comma.ai):
  common/transformations/{transformations,orientation,camera,model}.py
  selfdrive/modeld/constants.py
Ported so the block has no import-time dependency on the vendored checkout;
`perception/openpilot/tests_geometry.py` checks them against the originals.

Frames (openpilot convention, see common/transformations/README.md):
  device / calib : x forward, y right, z down (metres). Model outputs are in the CALIB frame.
  view           : x right, y down, z forward.
  camera         : pixels (u, v).
"""
from __future__ import annotations

import numpy as np

# ---- selfdrive/modeld/constants.py -------------------------------------------------
IDX_N = 33
X_IDXS = np.array([192.0 * (i / 32) ** 2 for i in range(IDX_N)], dtype=np.float32)  # 0..192 m
T_IDXS = np.array([10.0 * (i / 32) ** 2 for i in range(IDX_N)], dtype=np.float32)
LEAD_T_IDXS = np.array([0., 2., 4., 6., 8., 10.], dtype=np.float32)
LEAD_T_OFFSETS = np.array([0., 2., 4.], dtype=np.float32)
MODEL_RUN_FREQ = 20
MODEL_CONTEXT_FREQ = 5          # frames fed to the vision net are 1/5 s apart
FRAME_GAP_S = 1.0 / MODEL_CONTEXT_FREQ

# output_slices read from the ONNX metadata_props of v0.11.1 driving_vision.onnx
# (decoded with a slice-only restricted unpickler; see eval_bdd.py --dump-metadata)
OUTPUT_SLICES = {
    "meta": slice(0, 55), "desire_pred": slice(55, 87), "pose": slice(87, 99),
    "wide_from_device_euler": slice(99, 105), "road_transform": slice(105, 117),
    "lane_lines": slice(117, 645), "lane_lines_prob": slice(645, 653),
    "road_edges": slice(653, 917), "lead": slice(917, 1061), "lead_prob": slice(1061, 1064),
    "hidden_state": slice(1064, 1576),
}

# ---- common/transformations/camera.py -------------------------------------------------
device_frame_from_view_frame = np.array([[0., 0., 1.], [1., 0., 0.], [0., 1., 0.]])
view_frame_from_device_frame = device_frame_from_view_frame.T


def rot_from_euler(rpy) -> np.ndarray:
    """transformations.euler2rot_single: R = Rz(yaw) @ Ry(pitch) @ Rx(roll)."""
    phi, theta, psi = [float(v) for v in rpy]
    cx, sx = np.cos(phi), np.sin(phi)
    cy, sy = np.cos(theta), np.sin(theta)
    cz, sz = np.cos(psi), np.sin(psi)
    Rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    Ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    Rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return Rz @ Ry @ Rx


def euler_from_rot(R: np.ndarray) -> np.ndarray:
    """Inverse of rot_from_euler (ZYX), same branch as openpilot's quat route for |pitch|<90 deg."""
    pitch = np.arcsin(np.clip(-R[2, 0], -1.0, 1.0))
    roll = np.arctan2(R[2, 1], R[2, 2])
    yaw = np.arctan2(R[1, 0], R[0, 0])
    return np.array([roll, pitch, yaw])


def intrinsics(focal_px: float, width: int, height: int, cx: float | None = None, cy: float | None = None) -> np.ndarray:
    return np.array([[focal_px, 0.0, width / 2 if cx is None else cx],
                     [0.0, focal_px, height / 2 if cy is None else cy],
                     [0.0, 0.0, 1.0]])


def get_view_frame_from_calib_frame(roll, pitch, yaw, height):
    view_from_calib = view_frame_from_device_frame.dot(rot_from_euler([roll, pitch, yaw]))
    return np.hstack((view_from_calib, [[0], [height], [0]]))


def calib_from_vp(vp_xy, K: np.ndarray) -> np.ndarray:
    """camera.get_calib_from_vp: (roll=0, pitch, yaw) that puts the calib-frame forward axis at pixel vp."""
    vp_norm = np.linalg.inv(K) @ np.array([vp_xy[0], vp_xy[1], 1.0])
    yaw = np.arctan(vp_norm[0])
    pitch = -np.arctan(vp_norm[1] * np.cos(yaw))
    return np.array([0.0, pitch, yaw])


def vp_from_calib(rpy, K: np.ndarray) -> np.ndarray:
    d = K @ view_frame_from_device_frame @ rot_from_euler(rpy) @ np.array([1.0, 0.0, 0.0])
    return d[:2] / d[2]


# ---- common/transformations/model.py -------------------------------------------------
MEDMODEL_INPUT_SIZE = (512, 256)
MEDMODEL_CY = 47.6
medmodel_fl = 910.0
medmodel_intrinsics = np.array([[medmodel_fl, 0.0, 0.5 * MEDMODEL_INPUT_SIZE[0]],
                                [0.0, medmodel_fl, MEDMODEL_CY],
                                [0.0, 0.0, 1.0]])
SBIGMODEL_INPUT_SIZE = (512, 256)
sbigmodel_fl = 455.0
sbigmodel_intrinsics = np.array([[sbigmodel_fl, 0.0, 0.5 * SBIGMODEL_INPUT_SIZE[0]],
                                 [0.0, sbigmodel_fl, 0.5 * (256 + MEDMODEL_CY)],
                                 [0.0, 0.0, 1.0]])
medmodel_frame_from_calib_frame = medmodel_intrinsics @ get_view_frame_from_calib_frame(0, 0, 0, 0)
sbigmodel_frame_from_calib_frame = sbigmodel_intrinsics @ get_view_frame_from_calib_frame(0, 0, 0, 0)
calib_from_medmodel = np.linalg.inv(medmodel_frame_from_calib_frame[:, :3])
calib_from_sbigmodel = np.linalg.inv(sbigmodel_frame_from_calib_frame[:, :3])


def get_warp_matrix(device_from_calib_euler, cam_intrinsics: np.ndarray, bigmodel_frame: bool = False) -> np.ndarray:
    """camera_from_model homography: maps model-frame pixels (512x256) to source-camera pixels.
    Use with cv2.warpPerspective(..., flags=WARP_INVERSE_MAP)."""
    calib_from_model = calib_from_sbigmodel if bigmodel_frame else calib_from_medmodel
    camera_from_calib = cam_intrinsics @ view_frame_from_device_frame @ rot_from_euler(device_from_calib_euler)
    return camera_from_calib @ calib_from_model


def project_calib_points(pts_calib: np.ndarray, rpy, K: np.ndarray) -> np.ndarray:
    """Project Nx3 calib-frame points (x fwd, y right, z down; metres) to Nx2 source pixels.
    Points behind the camera come back as NaN."""
    P = K @ view_frame_from_device_frame @ rot_from_euler(rpy)
    q = (P @ np.asarray(pts_calib, dtype=np.float64).T).T
    out = np.full((q.shape[0], 2), np.nan)
    ok = q[:, 2] > 1e-3
    out[ok] = q[ok, :2] / q[ok, 2:3]
    return out


def backproject_to_ground(uv: np.ndarray, rpy, K: np.ndarray, cam_height_m: float) -> np.ndarray:
    """Intersect pixel rays with the flat road plane z = +cam_height (calib frame, z down).
    Returns Nx3 calib-frame points; NaN for rays at/above the horizon."""
    uv = np.atleast_2d(np.asarray(uv, dtype=np.float64))
    rays_view = (np.linalg.inv(K) @ np.hstack([uv, np.ones((len(uv), 1))]).T)       # 3xN
    rays_calib = rot_from_euler(rpy).T @ device_frame_from_view_frame @ rays_view   # calib = R^T device
    out = np.full((len(uv), 3), np.nan)
    ok = rays_calib[2] > 1e-6
    s = cam_height_m / rays_calib[2, ok]
    out[ok] = (rays_calib[:, ok] * s).T
    return out
