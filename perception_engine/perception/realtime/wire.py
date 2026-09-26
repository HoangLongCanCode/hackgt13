"""Wire format of the Perception Bridge, protocol v2 (contracts/PROTOCOL_v2.md, contracts/schemas/).

Knuckle Sandwich Robotics Inc. (KSR) AI Spatial Driving Copilot. The dicts built here are the in-memory
"dictionary" the Driving Context consumes: Python subscribers get them directly (PerceptionBus), and the
WebSocket server serialises each one ONCE (orjson) for the Kotlin app on the Samsung tablet. Nothing is
written to disk.

Server -> client (JSON text frames):
    perception.hello   make_hello(...)     once on connect and whenever a new session starts
    perception.frame   to_wire(...)        wave 1: detection + tracking + light state, every analysed frame
    perception.update  make_update(...)    wave 2: distance / lanes / road / signs from the slow lane
    perception.skip    make_skip(...)      an uplinked frame that will not get a perception.frame
    perception.stats   make_stats(...)     ~1 Hz
    perception.pong    make_pong(...)      answer to client.ping
    perception.error   make_error(...)     a client message could not be honoured (unknown clip, bad mode)
Client -> server (binary): 24-byte 'KSR1' header + JPEG, see pack_uplink_header / parse_uplink.

Fusion rules for wave 1 (per track id): detection box + confidence (plan section 7), tracker motion (section 8:
TTC, approaching), the latest known distance + lateral offset from the slow lane (section 9, with
distanceAgeMs), light state (section 11). Only tracked objects (with an id) are emitted. Traffic lights /
signs come from the static tracker (ids >= 1,000,000). All distances / TTC are display estimates (plan
sections 18 and 38).
"""
from __future__ import annotations

import math
import struct
import time
from dataclasses import dataclass
from typing import Any, Iterable, Optional, Sequence

import numpy as np

from perception.common.schemas import BDD_CLASSES, FrameResult, canonical_class

PROTOCOL_VERSION = 2
SCHEMA_VERSION = 2              # perception.frame / perception.update / perception.hello schemaVersion
MAX_POLYLINE_POINTS = 20
OBJECT_CLASSES = set(BDD_CLASSES)
NON_PATH_CLASSES = {"traffic light", "traffic sign"}   # never "in the ego path"
LIGHT_STATES = {"RED", "YELLOW", "GREEN", "UNKNOWN"}
# Static fallback ego corridor when no ego-path polygon exists (same as perception.tracking.motion.MotionConfig):
# normalised (x/W, y/H): bottom 20..80 % of width, apex at 40 % height.
STATIC_CORRIDOR_NORM = ((0.20, 1.0), (0.80, 1.0), (0.54, 0.40), (0.46, 0.40))
# `inEgoPath` = the ego VEHICLE's corridor on flat ground (see EgoPath): half vehicle width (~0.9 m) + 0.4 m margin,
# pointed along the ego lane (lane-centre anchors), out to 80 m. The yaw from the anchors is clamped to +-8 deg.
EGO_PATH_HALF_WIDTH_M = 1.3
EGO_PATH_MAX_M = 80.0
EGO_PATH_MAX_YAW_DEG = 8.0

# ----------------------------------------------------------------------------- binary uplink header
UPLINK_HEADER = struct.Struct("<4sHHIqHH")     # magic, headerVersion, flags, frameId, captureTimeNs, rotation, reserved
UPLINK_MAGIC = b"KSR1"
UPLINK_HEADER_VERSION = 1
LEGACY_PTS_HEADER = struct.Struct("<q")        # protocol v1 uplink: int64 LE pts in microseconds + JPEG
SKIP_REASONS = ("superseded", "decodeError", "badHeader", "notAccepted", "sessionReset")
ROTATIONS = (0, 90, 180, 270)


@dataclass(frozen=True)
class UplinkHeader:
    frame_id: int
    capture_time_ns: int
    rotation_degrees: int = 0
    flags: int = 0
    header_version: int = UPLINK_HEADER_VERSION

    def echo(self) -> dict[str, int]:
        return {"frameId": int(self.frame_id), "captureTimeNs": int(self.capture_time_ns)}


class UplinkError(ValueError):
    """A binary message that is not a valid KSR1 frame. `reason` is a perception.skip reason; `frame_id` is set
    when the header was readable enough to answer the frame."""

    def __init__(self, reason: str, detail: str, frame_id: Optional[int] = None):
        super().__init__(detail)
        self.reason, self.frame_id = reason, frame_id


def pack_uplink_header(frame_id: int, capture_time_ns: int, rotation_degrees: int = 0, flags: int = 0) -> bytes:
    """24-byte little-endian KSR1 header; append the JPEG bytes to it."""
    if rotation_degrees not in ROTATIONS:
        raise ValueError(f"rotationDegrees must be one of {ROTATIONS}")
    return UPLINK_HEADER.pack(UPLINK_MAGIC, UPLINK_HEADER_VERSION, int(flags) & 0xFFFF, int(frame_id) & 0xFFFFFFFF,
                              int(capture_time_ns), int(rotation_degrees), 0)


def is_ksr1(data: bytes | memoryview) -> bool:
    return len(data) >= 4 and bytes(data[:4]) == UPLINK_MAGIC


def parse_uplink(data: bytes | memoryview) -> tuple[UplinkHeader, memoryview]:
    """Split one binary WebSocket message into (header, JPEG bytes view). Raises UplinkError.

    A message of at least 24 bytes always yields a frame_id (read at offset 8, even when the magic is wrong), so the
    server can answer it with perception.skip badHeader and the client gets its credit back. A shorter message
    cannot be attributed to a frame and gets no skip (only a rate-limited perception.error badMessage)."""
    mv = memoryview(data)
    if len(mv) < UPLINK_HEADER.size:
        raise UplinkError("badHeader", f"not a KSR1 frame ({len(mv)} bytes, shorter than the 24-byte header)")
    magic, ver, flags, frame_id, cap_ns, rot, _res = UPLINK_HEADER.unpack_from(mv, 0)
    if magic != UPLINK_MAGIC:
        raise UplinkError("badHeader", f"not a KSR1 frame (magic {magic!r})", frame_id)
    if ver != UPLINK_HEADER_VERSION:
        raise UplinkError("badHeader", f"unsupported headerVersion {ver}", frame_id)
    if rot not in ROTATIONS:
        raise UplinkError("badHeader", f"rotationDegrees {rot} not in {ROTATIONS}", frame_id)
    jpeg = mv[UPLINK_HEADER.size:]
    if len(jpeg) < 4:
        raise UplinkError("decodeError", "empty JPEG payload", frame_id)
    return UplinkHeader(frame_id, cap_ns, rot, flags, ver), jpeg


def describe_uplink_header(data: bytes) -> str:
    """Hex dump + field breakdown of a header (used for contracts/samples/v2/uplink_header.example.txt)."""
    h = bytes(data[:UPLINK_HEADER.size])
    magic, ver, flags, frame_id, cap_ns, rot, res = UPLINK_HEADER.unpack(h)
    rows = [("0", "4", "magic", "4 bytes ASCII", magic.decode("ascii", "replace")),
            ("4", "2", "headerVersion", "uint16 LE", ver), ("6", "2", "flags", "uint16 LE", flags),
            ("8", "4", "frameId", "uint32 LE", frame_id), ("12", "8", "captureTimeNs", "int64 LE", cap_ns),
            ("20", "2", "rotationDegrees", "uint16 LE", rot), ("22", "2", "reserved", "uint16 LE", res)]
    offs = [0, 4, 6, 8, 12, 20, 22, 24]
    lines = ["hex (24 bytes): " + h.hex(" "), ""]
    lines.append(f"{'offset':>6}  {'size':>4}  {'field':<16} {'type':<14} {'bytes':<24} value")
    for (o, n, name, typ, val), a, b in zip(rows, offs, offs[1:]):
        lines.append(f"{o:>6}  {n:>4}  {name:<16} {typ:<14} {h[a:b].hex(' '):<24} {val}")
    return "\n".join(lines)


# ----------------------------------------------------------------------------- small helpers
def _r(x: Any, nd: int) -> Optional[float]:
    """float rounded to nd decimals; None for None / NaN / inf. Always a Python float (orjson-safe)."""
    if x is None:
        return None
    try:
        v = float(x)
    except (TypeError, ValueError):
        return None
    if not math.isfinite(v):
        return None
    v = round(v, nd)
    return 0.0 if v == 0 else v   # no "-0.0" on the wire


def _pt(p: Sequence[float], nd: int = 1) -> list[float]:
    return [_r(p[0], nd) or 0.0, _r(p[1], nd) or 0.0]


def now_ms() -> int:
    return int(time.time() * 1000)


def sign_class_to_wire(name: str) -> str:
    """Sign block snake_case -> contract camelCase: do_not_enter -> doNotEnter, speed_limit_45 -> speedLimit45."""
    parts = [p for p in str(name).split("_") if p]
    if not parts:
        return "unknown"
    return parts[0].lower() + "".join(p[:1].upper() + p[1:] for p in parts[1:])


def decimate(points: Iterable[Sequence[float]], max_points: int = MAX_POLYLINE_POINTS, closed: bool = False,
             nd: int = 1) -> list[list[float]]:
    """Reduce a polyline/polygon to <= max_points with Ramer-Douglas-Peucker (growing epsilon), keeping the
    end points; uniform subsampling as a last resort. Coordinates rounded to `nd` decimals."""
    pts = np.asarray([[float(p[0]), float(p[1])] for p in points], np.float32)
    if len(pts) == 0:
        return []
    if len(pts) > max_points:
        import cv2
        c = pts.reshape(-1, 1, 2)
        eps, out = 0.5, None
        for _ in range(16):
            a = cv2.approxPolyDP(c, eps, closed).reshape(-1, 2)
            if len(a) <= max_points:
                out = a
                break
            eps *= 1.6
        if out is None:
            idx = np.unique(np.linspace(0, len(pts) - 1, max_points).round().astype(int))
            out = pts[idx]
        pts = out
    return [_pt(p, nd) for p in pts]


def _inside(poly: np.ndarray, x: float, y: float) -> bool:
    import cv2
    return cv2.pointPolygonTest(poly, (float(x), float(y)), False) >= 0


def ego_polygon(road: Any, width: int, height: int) -> tuple[np.ndarray, str]:
    """The ego-path polygon used for `inEgoPath`: road.egoPathPolygon (lanes / segmentation) when it has >= 3
    points, else the static trapezoid. `road` may be a RoadGeometry or a FrameResult (uses .road).
    Returns (Nx1x2 float32 polygon, source name)."""
    road = getattr(road, "road", road) if isinstance(road, FrameResult) else road
    if road is not None and len(road.egoPathPolygon or []) >= 3:
        return np.asarray(road.egoPathPolygon, np.float32).reshape(-1, 1, 2), "road"
    poly = np.asarray(STATIC_CORRIDOR_NORM, np.float32) * np.float32([width, height])
    return poly.reshape(-1, 1, 2), "static"


def in_ego_path(bbox: Sequence[float], poly: np.ndarray) -> bool:
    """Box bottom inside the ego path: any of the points at 1/4, 1/2, 3/4 of the bottom edge (same rule as the
    tracker's corridor test). y is lifted 1 px so a box that ends exactly on the polygon edge still counts."""
    x1, _, x2, y2 = (float(v) for v in bbox[:4])
    y = y2 - 1.0
    return any(_inside(poly, x1 + f * (x2 - x1), y) for f in (0.25, 0.5, 0.75))


def _anchor_xy(road: Any, name: str) -> Optional[tuple[float, float]]:
    for a in getattr(road, "anchorPoints", None) or []:
        if isinstance(a, dict) and a.get("name") == name and a.get("xy") is not None and len(a["xy"]) >= 2:
            return float(a["xy"][0]), float(a["xy"][1])
    return None


class EgoPath:
    """Decides `inEgoPath` for wave-1 objects: is the box bottom inside the ego vehicle's own corridor?

    The corridor is +-EGO_PATH_HALF_WIDTH_M on flat ground around the line through the camera's ground point,
    heading along the ego lane, out to EGO_PATH_MAX_M. On a straight road that line projects to the vertical
    image line x = x_vp (the lane direction's vanishing x), so for a bottom point (x, y) the lateral offset is
    (x - x_vp) * depth / f with depth from flat-ground back-projection of y. x_vp comes from the lanes block's
    ego_lane_center_near/far anchors intersected with the horizon (clamped to +-EGO_PATH_MAX_YAW_DEG; the
    principal point when the anchors are missing), which absorbs a yawed dash-cam / phone mount.

    Why not the road block's egoPathPolygon: it is the ego LANE, which (a) spans from the centre line to the
    kerb where there is no painted line on the right, i.e. includes the parking lane, and (b) stops at 45 % of
    the road height (about 5 m ahead) for virtual boundaries. Measured on b1ff4656-0435391e: parked cars beside
    the car were "in path" at 3-6 m (17 false VEHICLE_TOO_CLOSE events in 31 s) and the real lead at 12 m was not.
    The polygon test is still used when the camera geometry (focal, height, horizon) is unknown.
    """

    def __init__(self, road: Any, camera: dict[str, Any], width: int, height: int,
                 half_width_m: float = EGO_PATH_HALF_WIDTH_M, max_m: float = EGO_PATH_MAX_M,
                 max_yaw_deg: float = EGO_PATH_MAX_YAW_DEG):
        road = getattr(road, "road", road) if isinstance(road, FrameResult) else road
        self.poly, self.poly_source = ego_polygon(road, width, height)
        self.half_width_m, self.max_m = float(half_width_m), float(max_m)
        f = camera.get("focalPx")
        pp = camera.get("principalPoint") or [None, None]
        h = camera.get("cameraHeightMeters")
        yh = camera.get("horizonY")
        if yh is None and road is not None:
            yh = getattr(road, "horizonY", None)
        self.metric = None not in (f, pp[0], pp[1], h, yh) and float(f) > 0 and float(h) > 0
        self.source = "corridor" if self.metric else self.poly_source
        if not self.metric:
            return
        self.f, self.cx, self.cy, self.h, self.yh = float(f), float(pp[0]), float(pp[1]), float(h), float(yh)
        self.pitch = math.atan((self.cy - self.yh) / self.f)
        self.x_vp = self.cx
        near, far = _anchor_xy(road, "ego_lane_center_near"), _anchor_xy(road, "ego_lane_center_far")
        if near is not None and far is not None and near[1] - far[1] > 20.0 and far[1] > self.yh + 2.0:
            x_vp = far[0] + (far[0] - near[0]) * (self.yh - far[1]) / (far[1] - near[1])
            lim = self.f * math.tan(math.radians(max_yaw_deg))
            self.x_vp = min(max(x_vp, self.cx - lim), self.cx + lim)
            self.source = "corridor+lane"

    def lateral_at(self, x: float, y: float) -> Optional[tuple[float, float]]:
        """(lateral m from the ego path centre line, + right; forward m) of an image point on flat ground."""
        if y <= self.yh + 2.0:
            return None
        ang = self.pitch + math.atan((y - self.cy) / self.f)
        if ang <= 1e-4:
            return None
        z = self.h / math.tan(ang)
        zc = z * math.cos(self.pitch) + self.h * math.sin(self.pitch)
        return (x - self.x_vp) * zc / self.f, z

    def contains(self, bbox: Sequence[float]) -> bool:
        if not self.metric:
            return in_ego_path(bbox, self.poly)
        x1, _, x2, y2 = (float(v) for v in bbox[:4])
        y = y2 - 1.0
        for frac in (0.25, 0.5, 0.75):
            lz = self.lateral_at(x1 + frac * (x2 - x1), y)
            if lz is not None and lz[1] <= self.max_m and abs(lz[0]) <= self.half_width_m:
                return True
        return False


def ground_xz(xy: Sequence[float], camera: dict[str, Any], horizon_y: Optional[float]) -> Optional[list[float]]:
    """Flat-ground back-projection of an image point: [lateral m (+ right), forward m]; None above the horizon."""
    f = camera.get("focalPx")
    cx, cy = (camera.get("principalPoint") or [None, None])[:2]
    h = camera.get("cameraHeightMeters")
    if horizon_y is None or f is None or cx is None or h is None:
        return None
    x, y = float(xy[0]), float(xy[1])
    if y <= horizon_y + 2.0:
        return None
    pitch = math.atan((cy - horizon_y) / f)
    ang = pitch + math.atan((y - cy) / f)
    if ang <= 1e-4:
        return None
    z = h / math.tan(ang)
    if not (0.0 < z < 300.0):
        return None
    zc = z * math.cos(pitch) + h * math.sin(pitch)   # depth along the (pitched) optical axis
    lateral = (x - cx) * zc / f
    return [_r(lateral, 2), _r(z, 2)]


def camera_to_wire(cam_in: Optional[dict[str, Any]], width: int, height: int) -> dict[str, Any]:
    cam_in = cam_in or {"focalPx": 700.0 * width / 1280.0, "principalPoint": [width / 2, height / 2]}
    return {
        "focalPx": _r(cam_in.get("focalPx"), 1),
        "principalPoint": _pt(cam_in.get("principalPoint") or [width / 2, height / 2], 1),
        "horizonY": _r(cam_in.get("horizonY"), 1),
        "cameraHeightMeters": _r(cam_in.get("cameraHeightMeters"), 3),
    }


def lanes_to_wire(ls: Any) -> Optional[dict[str, Any]]:
    if ls is None:
        return None
    return {
        "currentLane": None if ls.currentLane is None else int(ls.currentLane),
        "laneCount": None if ls.laneCount is None else int(ls.laneCount),
        "laneBoundaries": [decimate(b) for b in (ls.laneBoundaries or []) if len(b) >= 2],
        "confidence": _r(ls.confidence, 3) or 0.0,
    }


def road_to_wire(rg: Any, camera: dict[str, Any], width: int, height: int) -> Optional[dict[str, Any]]:
    if rg is None:
        return None
    yh = rg.horizonY if rg.horizonY is not None else camera.get("horizonY")
    anchors = []
    for a in rg.anchorPoints or []:
        xy = a.get("xy")
        if xy is None or len(xy) < 2:
            continue   # e.g. segmentation's 'ego_path_centerline' (polyline only)
        x, y = float(xy[0]), float(xy[1])
        gxz = a.get("groundXZ")
        if gxz is None:
            gxz = ground_xz(xy, camera, yh)
        valid = a.get("valid")
        if valid is None:
            valid = bool(0.0 <= x < width and 0.0 <= y < height and (yh is None or y > yh))
        anchors.append({"name": str(a.get("name", "")), "xy": _pt(xy, 1),
                        "groundXZ": None if gxz is None else [_r(gxz[0], 2), _r(gxz[1], 2)],
                        "valid": bool(valid)})
    return {
        "drivableCoverage": _r(rg.drivableCoverage, 4) or 0.0,
        "egoPathPolygon": decimate(rg.egoPathPolygon or [], closed=True),
        "horizonY": _r(rg.horizonY, 1),
        "vanishingPoint": None if not rg.vanishingPoint else _pt(rg.vanishingPoint, 1),
        "anchorPoints": anchors,
    }


def signs_to_wire(signs: Iterable[Any]) -> list[dict[str, Any]]:
    return [{
        "id": None if s.id is None else int(s.id),
        "signClass": sign_class_to_wire(s.signClass),
        "bbox": [_r(v, 1) for v in s.bbox[:4]],
        "confidence": _r(s.confidence, 3) or 0.0,
        "distanceMeters": _r(s.distanceMeters, 2),
    } for s in signs]


def _timings(*dicts: Optional[dict[str, Any]]) -> dict[str, float]:
    out: dict[str, float] = {}
    for d in dicts:
        for k, v in (d or {}).items():
            if _r(v, 2) is not None:
                out[str(k)] = _r(v, 2)
    return out


def _echo(e: Optional[dict[str, Any]]) -> Optional[dict[str, int]]:
    if not e:
        return None
    return {"frameId": int(e["frameId"]), "captureTimeNs": int(e["captureTimeNs"])}


# ----------------------------------------------------------------------------- wave 1: perception.frame
def to_wire(result: FrameResult, meta: dict[str, Any], schema_version: int = SCHEMA_VERSION) -> dict[str, Any]:
    """Build one `perception.frame` dict (contracts/schemas/perception.frame.schema.json; with
    schema_version=1 exactly the old contracts/perception_frame.v1.schema.json).

    meta keys: seq, sessionId, source {kind, id}, image {width, height}, camera {focalPx, principalPoint,
    horizonY, cameraHeightMeters}, blockAges {block: int}; optional: serverTimeMs, processingMs,
    depthDetails {trackId: {lateral_m, ...}}, timingsMs (extra timings merged in),
    v2 only: echo {frameId, captureTimeNs} | None, distanceAgesMs {trackId: ms}.
    """
    image = meta.get("image") or {"width": 1280, "height": 720}
    W, H = int(image["width"]), int(image["height"])
    camera = camera_to_wire(meta.get("camera"), W, H)
    depth_details = meta.get("depthDetails") or {}
    ages_ms = meta.get("distanceAgesMs") or {}
    det_by_id = {d.id: d for d in result.detections if d.id is not None}
    dist_by_id = {d.vehicleId: d for d in result.distances}
    light_by_id = {l.id: l for l in result.trafficLights if l.id is not None}
    sign_by_id = {s.id: s for s in result.trafficSigns if s.id is not None}
    ego = EgoPath(result.road, camera, W, H)
    v2 = schema_version >= 2

    objects = []
    for t in sorted(result.tracks, key=lambda tr: tr.id):
        cls = canonical_class(t.cls)
        if cls not in OBJECT_CLASSES:
            continue
        det = det_by_id.get(t.id)
        conf = det.confidence if det is not None else 0.0
        dist = dist_by_id.get(t.id)
        d_m = d_method = d_conf = None
        age = None
        if dist is not None:
            d_m, d_method, d_conf = dist.distanceMeters, dist.method, dist.confidence
            age = ages_ms.get(t.id)
        elif cls == "traffic light" and t.id in light_by_id and light_by_id[t.id].distanceMeters:
            d_m, d_method, age = light_by_id[t.id].distanceMeters, "size_prior", 0.0
        elif cls == "traffic sign" and t.id in sign_by_id and sign_by_id[t.id].distanceMeters:
            d_m, d_method, age = sign_by_id[t.id].distanceMeters, "size_prior", 0.0
        lateral = (depth_details.get(t.id) or {}).get("lateral_m")
        if lateral is None and d_m is not None and camera["focalPx"]:
            lateral = ((t.bbox[0] + t.bbox[2]) / 2.0 - camera["principalPoint"][0]) * float(d_m) / camera["focalPx"]
        light_state = light_conf = None
        if cls == "traffic light":
            ls = light_by_id.get(t.id)
            light_state = ls.state if (ls is not None and ls.state in LIGHT_STATES) else "UNKNOWN"
            light_conf = _r(ls.confidence, 3) if ls is not None else None
        obj = {
            "id": int(t.id),
            "class": cls,
            "bbox": [_r(v, 1) for v in t.bbox[:4]],
            "confidence": _r(conf, 3) or 0.0,
            "ageFrames": int(t.age_frames),
            "distanceMeters": _r(d_m, 2),
            "distanceMethod": d_method if d_m is not None else None,
            "distanceConfidence": _r(d_conf, 3) if d_m is not None else None,
            "lateralMeters": _r(lateral, 2) if d_m is not None else None,
            "ttcSeconds": _r(t.ttc_s, 2),
            "approaching": None if t.approaching is None else bool(t.approaching),
            "inEgoPath": None if cls in NON_PATH_CLASSES else bool(ego.contains(t.bbox)),
            "lightState": light_state,
            "lightConfidence": light_conf,
        }
        if v2:
            obj["distanceAgeMs"] = _r(age, 1) if d_m is not None and age is not None else None
        objects.append(obj)

    processing = meta.get("processingMs")
    if processing is None:
        processing = (result.timingsMs or {}).get("total", 0.0)
    src = meta.get("source") or {"kind": "video", "id": "unknown"}
    msg = {
        "type": "perception.frame",
        "schemaVersion": 2 if v2 else 1,
        "seq": int(meta.get("seq", 0)),
        "sessionId": str(meta.get("sessionId", "local")),
        "source": {"kind": str(src.get("kind", "video")), "id": str(src.get("id", ""))},
        "frameIndex": int(result.frameIndex),
        "ptsSeconds": _r(result.ptsSeconds, 4) or 0.0,
        "serverTimeMs": int(meta.get("serverTimeMs") or now_ms()),
        "processingMs": _r(processing, 2) or 0.0,
        "image": {"width": W, "height": H},
        "camera": camera,
        "objects": objects,
        "signs": signs_to_wire(result.trafficSigns),
        "lanes": lanes_to_wire(result.lanes),
        "road": road_to_wire(result.road, camera, W, H),
        "blockAges": {str(k): int(v) for k, v in (meta.get("blockAges") or {}).items() if v is not None},
        "timingsMs": _timings(result.timingsMs, meta.get("timingsMs")),
    }
    if v2:
        msg["wave"] = 1
        msg["echo"] = _echo(meta.get("echo"))
    return msg


# ----------------------------------------------------------------------------- wave 2: perception.update
def make_update(slow: Any, meta: dict[str, Any]) -> dict[str, Any]:
    """`perception.update` (wave 2) for one slow-lane run (engine.SlowResult).

    meta keys: seq, sessionId, frameIndex, ptsSeconds, echo, processingMs, image {width, height}, camera
    (session camera + horizon / camera height). Only the blocks that ran appear (distances when the depth /
    distance block ran, lanes + road when lanes ran, signs when signs ran)."""
    image = meta.get("image") or {"width": 1280, "height": 720}
    W, H = int(image["width"]), int(image["height"])
    camera = camera_to_wire(meta.get("camera"), W, H)
    msg: dict[str, Any] = {
        "type": "perception.update",
        "schemaVersion": SCHEMA_VERSION,
        "wave": 2,
        "seq": int(meta.get("seq", 0)),
        "sessionId": str(meta.get("sessionId", "local")),
        "frameIndex": int(meta.get("frameIndex", 0)),
        "ptsSeconds": _r(meta.get("ptsSeconds"), 4) or 0.0,
        "echo": _echo(meta.get("echo")),
        "serverTimeMs": int(meta.get("serverTimeMs") or now_ms()),
        "processingMs": _r(meta.get("processingMs"), 2) or 0.0,
        "camera": camera,
    }
    if slow.distances is not None:
        msg["distances"] = [{
            "id": int(d["id"]),
            "distanceMeters": _r(d["distanceMeters"], 2),
            "distanceMethod": d.get("distanceMethod"),
            "distanceConfidence": _r(d.get("distanceConfidence"), 3),
            "lateralMeters": _r(d.get("lateralMeters"), 2),
        } for d in slow.distances if _r(d.get("distanceMeters"), 2) is not None]
    if slow.lanes_ran:
        msg["lanes"] = lanes_to_wire(slow.lanes)
        msg["road"] = road_to_wire(slow.road, camera, W, H)
    if slow.signs is not None:
        msg["signs"] = signs_to_wire(slow.signs)
    msg["blocks"] = list(slow.ran)
    msg["timingsMs"] = _timings(slow.timings)
    return msg


# ----------------------------------------------------------------------------- control messages
SAFETY_NOTE = ("Informational driver display only (plan section 38): no steering, braking or throttle decisions. "
               "Distances, TTC and light states are estimates; UNKNOWN means unknown and GREEN is never permission.")


def make_hello(session_id: str, source: dict[str, Any], engine_desc: dict[str, Any], *, mode: str,
               accepted_modes: Sequence[str], image: Optional[dict[str, int]] = None,
               camera: Optional[dict[str, Any]] = None, source_fps: Optional[float] = None,
               uplink: Optional[dict[str, Any]] = None, sim: Optional[dict[str, Any]] = None,
               server: Optional[dict[str, Any]] = None, navigation: Optional[dict[str, Any]] = None,
               role: Optional[str] = None) -> dict[str, Any]:
    """`perception.hello` v2 (contracts/schemas/perception.hello.schema.json).

    mode is always one of video / live / sim (never 'auto'); acceptedModes lists what a client.hello may ask for.
    role: 'controller' (this client drives the session: uplinks frames / reports playback) or 'watcher'.
    navigation: {mode: sim|live|off, available, error} of the phase1 route-engine relay."""
    img = image or engine_desc.get("image") or {"width": 1280, "height": 720}
    W, H = int(img["width"]), int(img["height"])
    return {
        "type": "perception.hello",
        "protocolVersion": PROTOCOL_VERSION,
        "schemaVersion": SCHEMA_VERSION,
        "sessionId": session_id,
        "serverTimeMs": now_ms(),
        "mode": mode,
        "acceptedModes": list(accepted_modes),
        "source": {"kind": str(source.get("kind", "video")), "id": str(source.get("id", ""))},
        "sourceFps": _r(source_fps, 3),
        "targetHz": _r(engine_desc.get("targetHz"), 2),
        "image": {"width": W, "height": H},
        "camera": camera_to_wire(camera, W, H),
        "classes": list(BDD_CLASSES),
        "staticIdOffset": int(engine_desc.get("staticIdOffset", 1_000_000)),
        "uplink": uplink,
        "sim": sim,
        "schedule": engine_desc.get("schedule", {}),
        "models": engine_desc.get("models", []),
        "server": server or {},
        "navigation": navigation or {"mode": "off", "available": False, "error": None},
        "role": role,
        "safety": SAFETY_NOTE,
    }


def _pct(v: Sequence[float], q: float) -> Optional[float]:
    return _r(np.percentile(np.asarray(v, float), q), 2) if len(v) else None


def pcts(v: Sequence[float]) -> dict[str, Optional[float]]:
    return {"p50": _pct(v, 50), "p95": _pct(v, 95)}


def make_stats(session_id: str, *, mode: str, window_s: float, output_fps: float, wave2_fps: float,
               distance_fps: float, wave1_ms: Sequence[float], wave2_ms: Sequence[float],
               wave1_compute_ms: Sequence[float], wave2_compute_ms: Sequence[float], frames_in: int,
               frames_analysed: int, frames_skipped: int, updates: int, clients: int, send_dropped: int,
               lookahead_s: Optional[float] = None, source_fps: Optional[float] = None,
               uptime_s: Optional[float] = None, extra: Optional[dict[str, Any]] = None) -> dict[str, Any]:
    """`perception.stats` v2, ~1 Hz. wave*ProcessingMs = frame available on the server -> message handed to the
    socket layer; wave*ComputeMs = the lane's own model time."""
    return {
        "type": "perception.stats",
        "schemaVersion": SCHEMA_VERSION,
        "sessionId": session_id,
        "serverTimeMs": now_ms(),
        "mode": mode,
        "windowSeconds": _r(window_s, 2),
        "outputFps": _r(output_fps, 2),
        "wave2Fps": _r(wave2_fps, 2),
        "distanceFps": _r(distance_fps, 2),
        "sourceFps": _r(source_fps, 3),
        "wave1ProcessingMs": pcts(wave1_ms),
        "wave2ProcessingMs": pcts(wave2_ms),
        "wave1ComputeMs": pcts(wave1_compute_ms),
        "wave2ComputeMs": pcts(wave2_compute_ms),
        "framesIn": int(frames_in),
        "framesAnalysed": int(frames_analysed),
        "framesSkipped": int(frames_skipped),
        "updates": int(updates),
        "clients": int(clients),
        "sendDropped": int(send_dropped),
        "lookaheadSeconds": _r(lookahead_s, 3),
        "uptimeSeconds": _r(uptime_s, 1),
        **(extra or {}),
    }


def make_skip(frame_id: int, reason: str, session_id: Optional[str] = None) -> dict[str, Any]:
    if reason not in SKIP_REASONS:
        raise ValueError(f"unknown skip reason {reason!r}")
    return {"type": "perception.skip", "frameId": int(frame_id), "reason": reason,
            "sessionId": session_id, "serverTimeMs": now_ms()}


def make_pong(client_time_ns: Optional[int]) -> dict[str, Any]:
    return {"type": "perception.pong", "clientTimeNs": None if client_time_ns is None else int(client_time_ns),
            "serverTimeMs": now_ms()}


ERROR_CODES = ("badMessage", "modeNotAvailable", "unknownVideo", "notUplinkClient", "internal")


def make_error(code: str, message: str, *, fatal: bool = False, detail: Optional[dict[str, Any]] = None) -> dict[str, Any]:
    if code not in ERROR_CODES:
        raise ValueError(f"unknown error code {code!r}")
    return {"type": "perception.error", "code": code, "message": str(message), "fatal": bool(fatal),
            "detail": detail, "serverTimeMs": now_ms()}


def dumps(msg: dict[str, Any]) -> bytes:
    """Compact UTF-8 JSON (orjson). Serialise ONCE per message and send the same bytes to every client."""
    import orjson
    return orjson.dumps(msg)
