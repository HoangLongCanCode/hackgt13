"""Lane lines + drivable area block (plan section 10, feeds section 13 AR anchoring).

Public API:
    from perception.lanes import LaneDetector
    det = LaneDetector(backend="twinlitenetplus_large", device="cuda")
    masks = det.infer(frame_bgr)            # dict(lane_mask, drivable_mask, ...)
    state = det.lane_state(frame_bgr)       # perception.common.schemas.LaneState
    res   = det.analyze(frame_bgr)          # LaneAnalysis(lane_state, road, masks, extras, timings_ms)
"""
__all__ = ["LaneDetector", "LaneAnalysis", "BACKENDS", "PostConfig"]


def __getattr__(name):  # lazy, so `perception.lanes.backends` imports stand alone
    if name in ("LaneDetector", "LaneAnalysis", "BACKENDS"):
        from . import lanes as _l
        return getattr(_l, name)
    if name == "PostConfig":
        from .postprocess import PostConfig
        return PostConfig
    raise AttributeError(name)
