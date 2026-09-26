"""LaneDetector: lane lines + drivable area + lane state (plan section 10) and
lane-based road geometry for AR anchoring (plan section 13).

    from perception.lanes import LaneDetector
    det = LaneDetector(backend="twinlitenetplus_large", device="cuda")
    m = det.infer(frame_bgr)        # {"lane_mask", "drivable_mask", ...} uint8 0/1 at frame size
    s = det.lane_state(frame_bgr)   # LaneState(currentLane, laneCount, laneBoundaries, confidence)
    a = det.analyze(frame_bgr)      # everything from one forward pass (use this in the pipeline)

Lane state is a *perception observation* for the Driving Context Engine; it is
not an instruction and it never drives actuation (plan section 38). When
`confidence` is low the consumer should fall back to side-only guidance
("keep right") or the map prior (OSM lanes / turn:lanes).
"""
from __future__ import annotations

import time
from collections import deque
from dataclasses import dataclass, field, replace
from typing import Any, Optional

import cv2
import numpy as np

from perception.common.schemas import LaneState, RoadGeometry

from .backends import WORK_H, WORK_W, make_backend
from .postprocess import LineTracker, PostConfig, boundary_x, ego_lane_polygon, process_masks, x_of

BACKENDS = ("twinlitenetplus_large", "twinlitenetplus_medium", "yolop_onnx", "comma10k_segnet")


@dataclass
class LaneAnalysis:
    lane_state: LaneState
    road: RoadGeometry
    masks: dict[str, np.ndarray]
    extras: dict[str, Any] = field(default_factory=dict)
    timings_ms: dict[str, float] = field(default_factory=dict)


class LaneStateSmoother:
    """Temporal filter for video.

    lanesLeft and lanesRight are filtered separately (a joint (L, R) vote splits its weight
    over many combinations): confidence-weighted vote over the last `window` frames with
    hysteresis - the output changes only when a new value holds >= `switch_share` of the
    window weight. A lane change is detected from the ego lateral offset: a jump from about
    +0.5 to -0.5 means the car crossed its right boundary, so the history shifts by one lane
    (and vice versa)."""

    def __init__(self, window: int = 30, switch_share: float = 0.65, jump: float = 0.6, min_dwell: int = 20):
        self.window, self.switch_share, self.jump, self.min_dwell = window, switch_share, jump, min_dwell
        self.reset()

    def reset(self):
        self.hist: deque = deque(maxlen=self.window)
        self.prev_offset: Optional[float] = None
        self.out: Optional[tuple] = None
        self.dwell = [0, 0]

    def _vote(self, idx):
        w: dict = {}
        for h in self.hist:
            w[h[idx]] = w.get(h[idx], 0.0) + h[2]
        tot = sum(w.values())
        best = max(w.items(), key=lambda kv: kv[1])
        return best[0], best[1] / tot

    def update(self, nL, nR, conf, offset, offset_valid: bool = True):
        """offset_valid: both ego boundaries are painted lines (edges / virtual boundaries jump)."""
        event = None
        if not offset_valid:
            offset = None
            self.prev_offset = None
        if offset is not None and self.prev_offset is not None:
            d = offset - self.prev_offset
            shift = 0
            if self.prev_offset > 0.2 and offset < -0.2 and -d > self.jump:
                event, shift = "LANE_CHANGE_RIGHT_OBSERVED", +1
            elif self.prev_offset < -0.2 and offset > 0.2 and d > self.jump:
                event, shift = "LANE_CHANGE_LEFT_OBSERVED", -1
            if shift:
                self.hist = deque([(max(l + shift, 0), max(r - shift, 0), c) for l, r, c in self.hist],
                                  maxlen=self.window)
                if self.out is not None:
                    self.out = (max(self.out[0] + shift, 0), max(self.out[1] - shift, 0))
        if offset is not None:
            self.prev_offset = offset
        if nL is not None:
            self.hist.append((nL, nR, max(conf, 1e-3)))
        if not self.hist:
            return None, None, 0.0, event
        (bl, sl), (br, sr) = self._vote(0), self._vote(1)
        self.dwell = [self.dwell[0] + 1, self.dwell[1] + 1]
        if self.out is None:
            self.out = (bl, br)
        else:
            l, r = self.out
            if bl != l and sl >= self.switch_share and self.dwell[0] >= self.min_dwell:
                l, self.dwell[0] = bl, 0
            if br != r and sr >= self.switch_share and self.dwell[1] >= self.min_dwell:
                r, self.dwell[1] = br, 0
            if event:
                self.dwell = [0, 0]
            self.out = (l, r)
        mean_c = float(np.mean([h[2] for h in self.hist]))
        agree = float(np.mean([(h[0], h[1]) == self.out for h in self.hist]))
        conf_s = mean_c * (0.5 + 0.5 * agree)
        return self.out[0], self.out[1], conf_s, event


class LaneDetector:
    def __init__(self, backend: str = "twinlitenetplus_large", device: str = "cuda",
                 post: Optional[PostConfig] = None, temporal: bool = False, **opts):
        """
        backend : one of BACKENDS
        device  : "cuda", "cuda:0" or "cpu"
        post    : PostConfig (post-processing thresholds / priors)
        temporal: smooth lane counts over frames (use for video, not for single images)
        opts    : half=False (FP16; no speed-up measured for TLNP+), weights=<path>,
                  warmup_cap_gb=1.0 (VRAM cap during the cudnn.benchmark warm-up; None disables),
                  gpu_mem_limit (bytes, ORT arena for yolop_onnx)
        """
        if backend not in BACKENDS:
            raise ValueError(f"backend must be one of {BACKENDS}, got {backend!r}")
        self.backend_name = backend
        self.backend = make_backend(backend, device, **opts)
        self.device = self.backend.device
        self.cfg = post or PostConfig()
        self.temporal = temporal
        self.smoother = LaneStateSmoother() if temporal else None
        self.tracker = LineTracker() if temporal else None

    # ------------------------------------------------------------------ masks
    def _margins(self, frame_bgr):
        return self.backend.margins(frame_bgr)

    def infer(self, frame_bgr: np.ndarray, full_res: bool = True) -> dict[str, np.ndarray]:
        """Segmentation only. Returns uint8 {0,1} masks at the frame size (or 640x360 work res):
        lane_mask, drivable_mask (+ movable_mask, ego_car_mask for comma10k_segnet).
        No backend separates BDD100K 'direct' from 'alternative' drivable area, so there is no
        drivable_alt_mask here; analyze() derives the ego lane ('direct') from the lane boundaries."""
        m = self._margins(frame_bgr)
        return self._to_masks(m, frame_bgr.shape[:2] if full_res else None)

    @staticmethod
    def _to_masks(m, size_hw):
        out = {}
        names = {"lane": "lane_mask", "drivable": "drivable_mask", "movable": "movable_mask",
                 "ego_car": "ego_car_mask"}
        for k, v in m.items():
            if size_hw is not None and v.shape != tuple(size_hw):
                v = cv2.resize(v, (size_hw[1], size_hw[0]), interpolation=cv2.INTER_LINEAR)
            out[names.get(k, k + "_mask")] = (v > 0).astype(np.uint8)
        return out

    # ------------------------------------------------------------------ lane state
    def lane_state(self, frame_bgr: np.ndarray) -> LaneState:
        return self.analyze(frame_bgr, full_res_masks=False).lane_state

    def analyze(self, frame_bgr: np.ndarray, full_res_masks: bool = True,
                occluder_boxes: Optional[list] = None, margins: Optional[dict] = None) -> LaneAnalysis:
        """One forward pass -> LaneState + RoadGeometry + masks.

        occluder_boxes: optional [[x1, y1, x2, y2], ...] vehicle boxes in frame pixels (from the
            section 7 detector). The road under a vehicle is treated as road when locating the road
            edges, so a car in the next lane is not mistaken for the road edge. comma10k_segnet uses
            its own 'movable' class for this automatically.
        margins: precomputed backend margins (evaluation tools); skips the forward pass.
        """
        t0 = time.perf_counter()
        m = self._margins(frame_bgr) if margins is None else margins
        t1 = time.perf_counter()
        H, W = frame_bgr.shape[:2]
        lane_s = m["lane"] > 0
        drv_s = m["drivable"] > 0
        y_bot = None
        occ = None
        if "ego_car" in m:  # comma: cut the hood out of the road region
            car = m["ego_car"] > 0
            rows = np.flatnonzero(car.mean(axis=1) > 0.3)
            if len(rows):
                y_bot = int(max(rows.min() - 2, WORK_H * 0.6))
            drv_s = drv_s & ~car
        if "movable" in m:
            occ = m["movable"] > 0
        if occluder_boxes:
            occ = np.zeros((WORK_H, WORK_W), bool) if occ is None else occ.copy()
            sx, sy = WORK_W / W, WORK_H / H
            for x1, y1, x2, y2 in occluder_boxes:
                xa, xb = int(max(0, x1 * sx)), int(min(WORK_W, x2 * sx + 1))
                ya, yb = int(max(0, y1 * sy)), int(min(WORK_H, y2 * sy + 1))
                occ[ya:yb, xa:xb] = True
        small = cv2.resize(frame_bgr, (WORK_W, WORK_H), interpolation=cv2.INTER_AREA)
        res = process_masks(lane_s, drv_s, self.cfg, frame_small=small, y_bot=y_bot, occluders=occ,
                            tracker=self.tracker)
        state, road, extras = self._build_outputs(res, drv_s, (H, W))
        t2 = time.perf_counter()
        masks = self._to_masks(m, (H, W) if full_res_masks else None)
        ego = np.zeros((WORK_H, WORK_W), np.uint8)
        if res["state"].get("ok"):
            poly = ego_lane_polygon(res, self.cfg, WORK_W)
            if poly is not None:
                cv2.fillPoly(ego, [poly.round().astype(np.int32)], 1)
                ego &= drv_s.astype(np.uint8)
        if full_res_masks:
            ego = cv2.resize(ego, (W, H), interpolation=cv2.INTER_NEAREST)
        masks["ego_lane_mask"] = ego
        t3 = time.perf_counter()
        timings = dict(self.backend.last_timings) if margins is None else {}
        timings.update({"segment_ms": (t1 - t0) * 1e3, "lanestate_ms": (t2 - t1) * 1e3,
                        "masks_ms": (t3 - t2) * 1e3, "total_ms": (t3 - t0) * 1e3})
        extras["_internal"] = res
        return LaneAnalysis(state, road, masks, extras, timings)

    def reset(self):
        """Call between clips when temporal=True."""
        if self.smoother:
            self.smoother.reset()
        if self.tracker:
            self.tracker.reset()

    def close(self):
        self.backend.close()


    # ------------------------------------------------------------------ outputs
    def _build_outputs(self, res, drv_s, frame_hw):
        H, W = frame_hw
        sx, sy = W / WORK_W, H / WORK_H
        cfg = self.cfg
        st = res["state"]
        chains, y_h, y_bot = res["chains"], res["y_h"], res["y_bot"]
        road_h = max(y_bot - y_h, 1.0)
        ok = bool(st.get("ok"))
        side_of: dict = {}
        if ok:
            if st["L0"]["kind"] == "line":
                side_of[st["L0"]["chain"]] = "ego_left"
            if st["R0"]["kind"] == "line":
                side_of[st["R0"]["chain"]] = "ego_right"
            for k, c in enumerate(st["usedL"]):
                if c["kind"] == "line":
                    side_of.setdefault(c["chain"], f"left_{k + 1}")
            for k, c in enumerate(st["usedR"]):
                if c["kind"] == "line":
                    side_of.setdefault(c["chain"], f"right_{k + 1}")
        # boundaries: every accepted chain (left -> right), from its top down to the road bottom
        polylines, meta = [], []
        for idx, c in enumerate(chains):
            y_hi = min(float(y_bot), c.ymax + cfg.extrap_down_frac * road_h)
            ys = np.arange(y_hi, c.ymin - 1e-6, -6.0)
            if len(ys) < 2:
                ys = np.array([c.ymax, c.ymin])
            xs = c.x_at(ys)
            inb = (xs > -0.25 * WORK_W) & (xs < 1.25 * WORK_W)
            pts = [[round(float(x) * sx, 1), round(float(y) * sy, 1)] for x, y in zip(xs[inb], ys[inb])]
            if len(pts) < 2:
                continue
            conf = float(min(1.0, c.support / 40.0) * min(1.0, c.extent / (0.35 * road_h)))
            polylines.append(pts)
            meta.append({"index": len(polylines) - 1, "side": side_of.get(idx, "other"), "kind": "line",
                         "u": None if c.u is None else round(float(c.u), 3), "color": c.color, "style": c.style,
                         "confidence": round(conf, 3), "counted": idx in side_of})
        nL, nR, conf = st.get("nL"), st.get("nR"), st.get("conf", 0.0)
        offset = st.get("offset") if ok else None
        event = None
        if self.smoother is not None:
            kinds_ok = ok and st["L0"]["kind"] in ("line", "tracked") and st["R0"]["kind"] in ("line", "tracked")
            nL, nR, conf, event = self.smoother.update(nL, nR, conf, offset, offset_valid=kinds_ok)
        if nL is None:
            state = LaneState(currentLane=None, laneCount=None, laneBoundaries=polylines, confidence=0.0)
        else:
            state = LaneState(currentLane=int(nL + 1), laneCount=int(nL + 1 + nR), laneBoundaries=polylines,
                              confidence=round(float(conf), 3))
        # road geometry (plan section 13)
        anchors, poly_src = [], []
        if ok:
            poly = ego_lane_polygon(res, cfg, WORK_W)
            if poly is not None:
                poly_src = [[round(float(x) * sx, 1), round(float(y) * sy, 1)] for x, y in poly]
            for name, f in (("ego_lane_center_near", 0.85), ("ego_lane_center_mid", 0.6),
                            ("ego_lane_center_far", 0.35)):
                y = y_h + f * road_h
                xl = float(boundary_x(st["L0"], y, chains, st, cfg, road_h))
                xr = float(boundary_x(st["R0"], y, chains, st, cfg, road_h))
                anchors.append({"name": name, "xy": [round((xl + xr) / 2 * sx, 1), round(y * sy, 1)]})
            ym = y_h + 0.6 * road_h
            if nL:
                u = st["L0"]["u"] - 0.5 * st["w_ref"]
                anchors.append({"name": "left_lane_center_mid",
                                "xy": [round(float(x_of(u, ym, st["x_vp"], y_h, st["ratio"])) * sx, 1), round(ym * sy, 1)]})
            if nR:
                u = st["R0"]["u"] + 0.5 * st["w_ref"]
                anchors.append({"name": "right_lane_center_mid",
                                "xy": [round(float(x_of(u, ym, st["x_vp"], y_h, st["ratio"])) * sx, 1), round(ym * sy, 1)]})
        vp = res["vp"]
        road = RoadGeometry(drivableCoverage=round(float(drv_s.mean()), 4), egoPathPolygon=poly_src,
                            horizonY=round(y_h * sy, 1),
                            vanishingPoint=[round(vp[0] * sx, 1), round(vp[1] * sy, 1)] if vp else None,
                            anchorPoints=anchors)
        edges = st.get("edges_u", (None, None))
        extras = {
            "backend": self.backend_name,
            "boundaries": meta,
            "lanesLeft": None if nL is None else int(nL),
            "lanesRight": None if nR is None else int(nR),
            "egoLateralOffsetNorm": None if offset is None else round(float(offset), 3),
            "egoLaneWidthU": None if not ok else round(float(st["w_u"]), 3),
            "egoBoundaryKinds": None if not ok else [st["L0"]["kind"], st["R0"]["kind"]],
            "roadEdgesU": [None if e is None else round(e, 3) for e in edges],
            "unmarkedLanesCounted": None if not ok else int(st["unmarked"]),
            "vanishingPointFound": vp is not None,
            "laneCountSource": "vision",
            "rawFrame": None if not ok else {"lanesLeft": st["nL"], "lanesRight": st["nR"],
                                             "confidence": round(st["conf"], 3)},
            "event": event,
            "debug": res["debug"],
        }
        return state, road, extras
