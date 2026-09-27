"""Shared per-frame output types for the Perception Engine (plan §7–§13).

Field names follow the JSON examples in the production plan so the Driving
Context Engine and the React/WebSocket frontend can consume them unchanged.
Every block returns plain dataclasses; `to_dict()` gives the wire format.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any, Literal, Optional

# BDD100K det_20 class names (canonical spelling used across the pipeline).
BDD_CLASSES = [
    "pedestrian", "rider", "car", "truck", "bus",
    "train", "motorcycle", "bicycle", "traffic light", "traffic sign",
]
# 2018-label / checkpoint aliases -> canonical names.
BDD_ALIASES = {"person": "pedestrian", "motor": "motorcycle", "bike": "bicycle"}
VEHICLE_CLASSES = {"car", "truck", "bus", "train", "motorcycle", "bicycle"}


def canonical_class(name: str) -> str:
    return BDD_ALIASES.get(name, name)


@dataclass
class Detection:
    """Plan §7: {"id", "class", "bbox": [x1, y1, x2, y2], "confidence"}."""
    cls: str
    bbox: list[float]  # x1, y1, x2, y2 in source-frame pixels
    confidence: float
    id: Optional[int] = None  # track id once §8 tracking has run

    def to_dict(self) -> dict[str, Any]:
        return {"id": self.id, "class": self.cls,
                "bbox": [round(v, 1) for v in self.bbox],
                "confidence": round(self.confidence, 3)}


@dataclass
class TrackState:
    """Plan §8 derived motion for one tracked object."""
    id: int
    cls: str
    bbox: list[float]
    age_frames: int
    scale_rate: Optional[float] = None       # d(ln box height)/dt, 1/s
    ttc_s: Optional[float] = None             # time to collision from scale change
    approaching: Optional[bool] = None
    lateral_px_s: Optional[float] = None      # box-center x velocity

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class DistanceEstimate:
    """Plan §9: {"vehicleId", "distanceMeters"}."""
    vehicleId: int
    distanceMeters: float
    method: str = "fused"  # e.g. "depth_model", "ground_plane", "width_prior", "fused"
    confidence: float = 0.5

    def to_dict(self) -> dict[str, Any]:
        d = asdict(self)
        d["distanceMeters"] = round(self.distanceMeters, 2)
        return d


@dataclass
class LaneState:
    """Plan §10: {"currentLane", "laneCount", "laneBoundaries"}.

    Lanes are numbered 1..laneCount from the left. laneBoundaries holds
    polylines [[x, y], ...] in source-frame pixels, left to right.
    boundaryColors / boundaryStyles are parallel to laneBoundaries (index i describes
    laneBoundaries[i]): "yellow" | "white" | "unknown" and "solid" | "dashed" | "unknown".
    None when the block has no per-line metadata.
    """
    currentLane: Optional[int]
    laneCount: Optional[int]
    laneBoundaries: list[list[list[float]]] = field(default_factory=list)
    confidence: float = 0.0
    boundaryColors: Optional[list[str]] = None
    boundaryStyles: Optional[list[str]] = None

    def to_dict(self) -> dict[str, Any]:
        d = asdict(self)
        for k in ("boundaryColors", "boundaryStyles"):
            if d[k] is None:
                del d[k]
        return d


@dataclass
class TrafficLightState:
    """Plan §11: RED / YELLOW / GREEN (+ distance when available)."""
    id: Optional[int]
    state: Literal["RED", "YELLOW", "GREEN", "UNKNOWN"]
    bbox: list[float]
    confidence: float
    distanceMeters: Optional[float] = None

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class TrafficSign:
    """Plan §12: sign class (stop, yield, speed_limit_45, ...)."""
    id: Optional[int]
    signClass: str
    bbox: list[float]
    confidence: float
    distanceMeters: Optional[float] = None

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class RoadGeometry:
    """Plan §13: what AR content can be anchored to."""
    drivableCoverage: float                      # fraction of frame that is drivable
    egoPathPolygon: list[list[float]] = field(default_factory=list)  # [[x, y], ...]
    horizonY: Optional[float] = None
    vanishingPoint: Optional[list[float]] = None  # [x, y]
    anchorPoints: list[dict[str, Any]] = field(default_factory=list)  # e.g. {"name": "lane_center_20m", "xy": [x, y]}
    # outline of the visible drivable road [[x, y], ...] (<= 32 points; excludes dashboard / hood / pillars); [] = unknown
    drivablePolygon: list[list[float]] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class FrameResult:
    """One line of the perception timeline (JSONL), keyed by frame index + pts."""
    frameIndex: int
    ptsSeconds: float
    detections: list[Detection] = field(default_factory=list)
    tracks: list[TrackState] = field(default_factory=list)
    distances: list[DistanceEstimate] = field(default_factory=list)
    lanes: Optional[LaneState] = None
    trafficLights: list[TrafficLightState] = field(default_factory=list)
    trafficSigns: list[TrafficSign] = field(default_factory=list)
    road: Optional[RoadGeometry] = None
    timingsMs: dict[str, float] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "frameIndex": self.frameIndex,
            "ptsSeconds": round(self.ptsSeconds, 4),
            "detections": [d.to_dict() for d in self.detections],
            "tracks": [t.to_dict() for t in self.tracks],
            "distances": [d.to_dict() for d in self.distances],
            "lanes": self.lanes.to_dict() if self.lanes else None,
            "trafficLights": [t.to_dict() for t in self.trafficLights],
            "trafficSigns": [s.to_dict() for s in self.trafficSigns],
            "road": self.road.to_dict() if self.road else None,
            "timingsMs": {k: round(v, 2) for k, v in self.timingsMs.items()},
        }
