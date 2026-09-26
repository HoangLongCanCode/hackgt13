"""Wire format of the glasses socket: one JSON camera frame in, one Spatial Instruction document out.

AI Spatial Driving Copilot. The glasses app is a separate Kotlin CameraX client (not built from this repo and not
changed by it): it JPEG-encodes the upright camera frame, sends it as a JSON text frame and draws only the
`instructions` of the reply. perception.realtime.glasses_server serves it on ws://<host>:8000/ws. Protocol v2
(contracts/PROTOCOL_v2.md, SDC1 binary uplink, client.hello, credits, two waves) is NOT spoken on that socket.

Client -> server (UTF-8 text frame, no handshake, about 8 per second, no waiting for replies):
    {"frameId": 1, "timestampMs": 1710000000000, "encoding": "jpeg-base64", "width": 640, "height": 360,
     "data": "<standard base64 of a baseline JPEG>"}            decode_frame()
Server -> client (text frame, camelCase, no type wrapper, no nulls):
    {"frameId", "timestampMs", "perception": {"lanes", "vehicles", "signs"}, "navigation", "instructions"}
                                                                build_document()
    {"error": "<short reason>"} for a frame that cannot be analysed (the socket stays open).   error_doc()

Coordinates: engine.step() centre-crops and resizes every frame to the engine input (engine.fit_frame, usually
1280x720) and reports pixels of that fitted image. FitMap undoes the crop, so every x / y in the document is a
fraction of the JPEG the phone sent: origin top-left, x right, y down, clipped to 0..1 and rounded to 4 decimals.
Lanes are numbered 1..laneCount from the driver's left.

The `navigation` block is a fixed demo (EXIT 54/56 in 0.4 mi, rightmost lane); it does not come from phase1, GPS
or a routing API. Everything here is display only (plan sections 18 and 38).
"""
from __future__ import annotations

import base64
import binascii
import json
import math
from dataclasses import dataclass
from typing import Any, Optional, Sequence

import numpy as np

from perception.common.schemas import FrameResult, LaneState, canonical_class

ENCODING = "jpeg-base64"
MAX_JPEG_BYTES = 8 * 1024 * 1024              # decoded JPEG must be smaller than this
# base64 longer than this cannot decode to less than MAX_JPEG_BYTES (10 % slack for line breaks)
MAX_BASE64_CHARS = int(MAX_JPEG_BYTES * 4 / 3 * 1.1) + 64
LIFETIME_MS = 500
VEHICLE_CLASSES = ("car", "truck", "bus", "train", "motorcycle", "bicycle")   # pedestrians / riders are skipped
DEFAULT_LANES = (2, 3)                        # (currentLane, laneCount) when the lanes block has no answer
MAX_BOUNDARY_POINTS = 6
MAX_VEHICLES = 3
MAX_SIGNS = 4
MIN_BOX_SIDE = 0.005                          # normalized; smaller boxes are dropped
MAX_JPEG_EDGE = 4096                          # SOF width / height above this is refused before decoding (phone: 640)
FRONT_MIN_BOTTOM_Y = 0.4                      # "in front": box bottom below this row ...
CORRIDOR_MARGIN = 0.06                        # ... and centre between the ego lines (widened by this) at that row
NO_LANES_CORRIDOR = (0.25, 0.75)              # centre-x band used when there are no ego lines
ARROW_Y = 0.78
EXIT_ANCHOR = {"x": 0.5, "y": 0.08}
DEMO_EXIT_LABEL = "EXIT 54/56"
DEMO_DISTANCE_LABEL = "0.4 mi"


class FrameError(ValueError):
    """A phone message that cannot be analysed. `reason` is the short text sent back as {"error": reason}."""

    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


@dataclass
class InboundFrame:
    frame_id: Optional[int]       # None when the phone sent none (the server numbers the frame)
    timestamp_ms: Optional[int]   # None when the phone sent none (the server uses its own clock)
    image: np.ndarray             # decoded JPEG, BGR uint8 HxWx3, as sent (already upright)


def error_doc(reason: str) -> dict[str, str]:
    return {"error": str(reason)}


def dumps(doc: dict[str, Any]) -> str:
    """Compact JSON text frame. allow_nan=False: a NaN would otherwise go out as an invalid token or a null."""
    return json.dumps(doc, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


# ----------------------------------------------------------------------------- inbound
def _int_or_none(v: Any) -> Optional[int]:
    if isinstance(v, bool):
        return None
    if isinstance(v, int):
        return v
    if isinstance(v, float) and math.isfinite(v) and v.is_integer():
        return int(v)
    return None


def _b64decode(s: str) -> bytes:
    try:
        return base64.b64decode(s, validate=True)
    except (binascii.Error, ValueError):
        pass
    # tolerate line breaks (android.util.Base64.DEFAULT wraps at 76 chars) and missing padding
    t = "".join(s.split())
    t += "=" * (-len(t) % 4)
    try:
        return base64.b64decode(t, validate=True)
    except (binascii.Error, ValueError):
        raise FrameError("data is not valid base64") from None


def _jpeg_dims(raw: bytes) -> Optional[tuple[int, int]]:
    """(width, height) from the first SOFn marker, or None when there is none before the scan data."""
    i, n = 2, len(raw)
    while i + 4 <= n:
        if raw[i] != 0xFF:
            return None
        m = raw[i + 1]
        if m == 0xFF:                         # fill byte
            i += 1
            continue
        if m in (0x01, 0xD8) or 0xD0 <= m <= 0xD7:
            i += 2
            continue
        seg = int.from_bytes(raw[i + 2:i + 4], "big")
        if 0xC0 <= m <= 0xCF and m not in (0xC4, 0xC8, 0xCC):
            if i + 9 > n:
                return None
            return int.from_bytes(raw[i + 7:i + 9], "big"), int.from_bytes(raw[i + 5:i + 7], "big")
        if m == 0xDA or seg < 2:              # start of scan (no SOF before it) or a broken segment
            return None
        i += 2 + seg
    return None


def decode_frame(text: str) -> InboundFrame:
    """Parse one phone text frame and decode its JPEG. Raises FrameError with the reason to send back.

    The phone rotated the JPEG upright before encoding it, so EXIF orientation is ignored (no second rotation).
    width / height in the message are informational: the decoded image wins when they disagree."""
    import cv2
    try:
        msg = json.loads(text)
    except (ValueError, RecursionError):
        raise FrameError("frame is not valid JSON") from None
    if not isinstance(msg, dict):
        raise FrameError("frame must be a JSON object")
    if msg.get("encoding") != ENCODING:
        raise FrameError("encoding must be jpeg-base64")
    data = msg.get("data")
    if data is None or data == "":
        raise FrameError("empty jpeg")
    if not isinstance(data, str):
        raise FrameError("data is not valid base64")
    if data[:5].lower() == "data:":               # data:image/jpeg;base64,<payload>
        head, sep, rest = data.partition(",")
        if sep and head.lower().endswith(";base64"):
            data = rest
        if not data.strip():
            raise FrameError("empty jpeg")
    if len(data) > MAX_BASE64_CHARS:
        raise FrameError("jpeg exceeds 8MB")
    raw = _b64decode(data)
    if not raw:
        raise FrameError("empty jpeg")
    if len(raw) >= MAX_JPEG_BYTES:
        raise FrameError("jpeg exceeds 8MB")
    if raw[:3] != b"\xff\xd8\xff":
        raise FrameError("data is not a jpeg image")
    dims = _jpeg_dims(raw)
    if dims is not None and max(dims) > MAX_JPEG_EDGE:     # a 30 KB header can claim a 3 GB image
        raise FrameError(f"jpeg larger than {MAX_JPEG_EDGE} px")
    try:
        img = cv2.imdecode(np.frombuffer(raw, np.uint8), cv2.IMREAD_COLOR | cv2.IMREAD_IGNORE_ORIENTATION)
    except (cv2.error, MemoryError):       # e.g. OpenCV's pixel-count assertion on a crafted header
        raise FrameError("data is not a jpeg image") from None
    if img is None or img.ndim != 3 or img.shape[2] != 3 or img.shape[0] < 1 or img.shape[1] < 1:
        raise FrameError("data is not a jpeg image")
    return InboundFrame(_int_or_none(msg.get("frameId")), _int_or_none(msg.get("timestampMs")), img)


# ----------------------------------------------------------------------------- coordinates
@dataclass(frozen=True)
class FitMap:
    """Inverse of engine.fit_frame: fitted-image pixels -> fractions of the source (JPEG) image.

    fit_frame centre-crops the source to the fitted aspect ratio (when it differs by more than 1e-3), then resizes
    the crop to fitted_w x fitted_h. So source = crop origin + fitted * crop size / fitted size."""
    src_w: int
    src_h: int
    fit_w: int
    fit_h: int
    x0: int
    y0: int
    crop_w: int
    crop_h: int

    @classmethod
    def of(cls, src_w: int, src_h: int, fit_w: int, fit_h: int) -> "FitMap":
        target = fit_w / fit_h
        x0 = y0 = 0
        crop_w, crop_h = src_w, src_h
        if abs(src_w / src_h - target) > 1e-3:      # same test and rounding as fit_frame
            if src_w / src_h > target:              # source wider: fit_frame cropped the sides
                crop_w = int(round(src_h * target))
                x0 = (src_w - crop_w) // 2
            else:                                   # source taller: fit_frame cropped top and bottom
                crop_h = int(round(src_w / target))
                y0 = (src_h - crop_h) // 2
        return cls(int(src_w), int(src_h), int(fit_w), int(fit_h), x0, y0, crop_w, crop_h)

    def norm(self, x: float, y: float) -> tuple[float, float]:
        """Fitted pixel -> source fraction, NOT clipped (for geometry)."""
        sx = self.x0 + (float(x) / self.fit_w) * self.crop_w
        sy = self.y0 + (float(y) / self.fit_h) * self.crop_h
        return sx / self.src_w, sy / self.src_h


def _c(v: float) -> float:
    """Clip to 0..1 and round to 4 decimals (+ 0.0 turns -0.0 into 0.0)."""
    return round(min(max(float(v), 0.0), 1.0), 4) + 0.0


def _finite(*vals: Any) -> bool:
    try:
        return all(math.isfinite(float(v)) for v in vals)
    except (TypeError, ValueError):
        return False


def _norm_box(fm: FitMap, bbox: Sequence[float]) -> Optional[list[float]]:
    """[x1, y1, x2, y2] fitted pixels -> normalized on the JPEG with x1 < x2, y1 < y2; None if degenerate."""
    if bbox is None or len(bbox) < 4 or not _finite(*bbox[:4]):
        return None
    ax, ay = fm.norm(bbox[0], bbox[1])
    bx, by = fm.norm(bbox[2], bbox[3])
    x1, x2 = sorted((_c(ax), _c(bx)))
    y1, y2 = sorted((_c(ay), _c(by)))
    if x2 - x1 < MIN_BOX_SIDE - 1e-9 or y2 - y1 < MIN_BOX_SIDE - 1e-9:     # 0.105 - 0.1 is 0.00499...
        return None
    return [x1, y1, x2, y2]


def _r(v: float, nd: int) -> float:
    return round(float(v), nd) + 0.0


# ----------------------------------------------------------------------------- perception.lanes
Line = list[tuple[float, float]]          # unclipped source fractions, in the engine's point order


def _lane_numbers(ls: Optional[LaneState]) -> tuple[int, int]:
    cur = _int_or_none(getattr(ls, "currentLane", None))
    cnt = _int_or_none(getattr(ls, "laneCount", None))
    if cur is None or cnt is None or cur < 1 or cnt < 1:
        cur, cnt = DEFAULT_LANES
    return min(cur, cnt), cnt


def _mean_x(line: Line) -> float:
    return sum(p[0] for p in line) / len(line)


def _ego_pair(lines: list[Line], ls: Optional[LaneState]) -> tuple[Optional[Line], Optional[Line]]:
    """The ego lane's boundaries: laneBoundaries[currentLane - 1] and [currentLane], which assumes LaneState lists
    laneCount + 1 lines, left to right. The lanes block often breaks that assumption (it also returns uncounted
    chains, e.g. beyond a double yellow, and has no polyline for road edges or virtual boundaries: laneCount + 1 lines
    in about 30 % of BDD frames), and then the index pair is a neighbouring lane. So the index pair counts as existing
    only when the counts came from the lanes block, there are laneCount + 1 lines and the pair brackets the image
    centre. Otherwise: the rightmost line whose mean x is left of centre and the leftmost line whose mean x is right
    of centre (either may be missing)."""
    cur = _int_or_none(getattr(ls, "currentLane", None))
    cnt = _int_or_none(getattr(ls, "laneCount", None))
    if cur is not None and cnt is not None and 1 <= cur <= cnt and len(lines) == cnt + 1:
        left, right = lines[cur - 1], lines[cur]
        if left and right and _mean_x(left) < 0.5 < _mean_x(right):
            return left, right
    lefts = [ln for ln in lines if ln and _mean_x(ln) < 0.5]
    rights = [ln for ln in lines if ln and _mean_x(ln) > 0.5]
    return (max(lefts, key=_mean_x) if lefts else None), (min(rights, key=_mean_x) if rights else None)


def _clip_segment(xa: float, ya: float, xb: float, yb: float) -> Optional[tuple[float, float]]:
    """Liang-Barsky: (t0, t1) of the part of segment a->b inside the unit square, or None."""
    dx, dy = xb - xa, yb - ya
    t0, t1 = 0.0, 1.0
    for p, q in ((-dx, xa), (dx, 1.0 - xa), (-dy, ya), (dy, 1.0 - ya)):
        if p == 0.0:
            if q < 0.0:
                return None
            continue
        t = q / p
        if p < 0.0:
            if t > t1:
                return None
            t0 = max(t0, t)
        else:
            if t < t0:
                return None
            t1 = min(t1, t)
    return t0, t1


def _clip_line(line: Line) -> Line:
    """The longest part of a polyline inside the image, cut where it crosses the border. Lane polylines are
    extrapolated past the image sides (x -0.25..1.25 W); clamping each point would draw them down the screen edge."""
    if len(line) == 1:
        x, y = line[0]
        return [line[0]] if 0.0 <= x <= 1.0 and 0.0 <= y <= 1.0 else []
    runs: list[Line] = []
    run: Line = []
    for (xa, ya), (xb, yb) in zip(line, line[1:]):
        c = _clip_segment(xa, ya, xb, yb)
        if c is None:
            if run:
                runs.append(run)
                run = []
            continue
        t0, t1 = c
        p = (xa + t0 * (xb - xa), ya + t0 * (yb - ya))
        q = (xa + t1 * (xb - xa), ya + t1 * (yb - ya))
        if t0 > 0.0 or not run:               # enters from outside: a new run
            if run:
                runs.append(run)
            run = [p]
        run.append(q)
        if t1 < 1.0:                          # leaves the image
            runs.append(run)
            run = []
    if run:
        runs.append(run)

    def length(r: Line) -> float:
        return sum(math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(r, r[1:]))
    return max(runs, key=length) if runs else []


def _display_points(line: Line) -> list[list[float]]:
    """The part inside the image, rounded, consecutive duplicates removed, at most MAX_BOUNDARY_POINTS keeping the
    first and last."""
    pts: list[list[float]] = []
    for x, y in _clip_line(line):
        p = [_c(x), _c(y)]
        if not pts or pts[-1] != p:
            pts.append(p)
    if len(pts) > MAX_BOUNDARY_POINTS:
        idx = np.unique(np.linspace(0, len(pts) - 1, MAX_BOUNDARY_POINTS).round().astype(int))
        pts = [pts[k] for k in idx]
    return pts


def _x_at(line: Line, y: float) -> float:
    """x of a polyline at row y: interpolated inside its y range, extrapolated from the nearest end segment outside."""
    for (xa, ya), (xb, yb) in zip(line, line[1:]):
        if min(ya, yb) <= y <= max(ya, yb) and abs(yb - ya) > 1e-9:
            return xa + (xb - xa) * (y - ya) / (yb - ya)
    if len(line) < 2:
        return line[0][0]
    if abs(y - line[0][1]) <= abs(y - line[-1][1]):
        (xa, ya), (xb, yb) = line[0], line[1]
    else:
        (xa, ya), (xb, yb) = line[-1], line[-2]
    if abs(yb - ya) < 1e-6:
        return xa
    return min(max(xa + (xb - xa) * (y - ya) / (yb - ya), -1.0), 2.0)


# ----------------------------------------------------------------------------- document
def _vehicles(result: FrameResult, fm: FitMap, ego: tuple[Optional[Line], Optional[Line]]) -> list[dict[str, Any]]:
    dist_by_id = {}
    for d in result.distances or []:
        if d.vehicleId not in dist_by_id and _finite(d.distanceMeters) and d.distanceMeters > 0:
            dist_by_id[d.vehicleId] = d
    det_conf = {d.id: d.confidence for d in result.detections or [] if d.id is not None and _finite(d.confidence)}
    left, right = ego
    cands: list[tuple[float, bool, dict[str, Any]]] = []
    for t in result.tracks or []:
        cls = canonical_class(t.cls)
        if cls not in VEHICLE_CLASSES:
            continue
        dist = dist_by_id.get(t.id)
        if dist is None or _r(dist.distanceMeters, 1) <= 0.0:     # would be sent as "0.0m"
            continue
        box = _norm_box(fm, t.bbox)
        if box is None:
            continue
        conf = det_conf.get(t.id)
        if conf is None:
            conf = dist.confidence if _finite(dist.confidence) else 0.0
        cx, bottom = (box[0] + box[2]) / 2.0, box[3]
        if left is not None and right is not None:     # the ego corridor needs both lines
            xl, xr = sorted((_x_at(left, bottom), _x_at(right, bottom)))
            lo, hi = xl - CORRIDOR_MARGIN, xr + CORRIDOR_MARGIN
        else:
            lo, hi = NO_LANES_CORRIDOR
        in_front = bottom > FRONT_MIN_BOTTOM_Y and lo <= cx <= hi
        cands.append((float(dist.distanceMeters), in_front, {
            "id": int(t.id), "class": cls, "bbox": box, "distanceMeters": _r(dist.distanceMeters, 1),
            "confidence": _r(conf, 3)}))
    cands.sort(key=lambda c: c[0])
    front = [v for _, f, v in cands if f][:MAX_VEHICLES]
    return front if front else [v for _, _, v in cands[:1]]


def _signs(result: FrameResult, fm: FitMap) -> list[dict[str, Any]]:
    """Typed signs, most confident first, at most MAX_SIGNS. A sign without a track id gets its 1-based position."""
    out: list[dict[str, Any]] = []
    for s in sorted(result.trafficSigns or [], key=lambda s: -(s.confidence if _finite(s.confidence) else 0.0)):
        cls = str(s.signClass or "").strip()
        if not cls or cls.lower() == "unknown":
            continue
        box = _norm_box(fm, s.bbox)
        if box is None:
            continue
        out.append({"id": int(s.id) if s.id is not None else len(out) + 1,
                    "class": "exit" if "exit" in cls.lower() else cls,
                    "label": cls.replace("_", " ").upper(),
                    "bbox": box,
                    "confidence": _r(s.confidence if _finite(s.confidence) else 0.0, 3)})
        if len(out) == MAX_SIGNS:
            break
    return out


def _anchor(x: float, y: float) -> dict[str, float]:
    return {"x": _c(x), "y": _c(y)}


def _instructions(boundaries: list[dict[str, Any]], lane_count: int, required_lane: int,
                  vehicles: list[dict[str, Any]]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for b in boundaries:                                   # 1. the ego lane lines, stroked by the overlay
        if len(b["points"]) >= 2:
            x, y = b["points"][0]
            out.append({"type": "LANE_BOUNDARY", "anchor": _anchor(x, y), "content": b["id"],
                        "points": b["points"], "lifetimeMs": LIFETIME_MS})
    for lane in range(1, lane_count + 1):                  # 2. one arrow per lane, in even slots across the screen
        hl = lane == required_lane
        out.append({"type": "LANE_ARROW", "anchor": _anchor(lane / (lane_count + 1), ARROW_Y),
                    "content": "EXIT" if hl else "↑", "laneIndex": lane, "highlighted": hl,
                    "lifetimeMs": LIFETIME_MS})
    out.append({"type": "EXIT_MARKER", "anchor": dict(EXIT_ANCHOR), "content": DEMO_EXIT_LABEL,   # 3. top HUD
                "distanceLabel": DEMO_DISTANCE_LABEL, "lifetimeMs": LIFETIME_MS})
    for v in vehicles:                                     # 4. marker + distance label per vehicle
        x1, y1, x2, y2 = v["bbox"]
        cx, cy = (x1 + x2) / 2.0, (y1 + y2) / 2.0
        d = v["distanceMeters"]
        out.append({"type": "VEHICLE_MARKER", "anchor": _anchor(cx, cy), "content": v["class"].upper(),
                    "distanceLabel": f"{int(math.floor(d + 0.5))} m", "lifetimeMs": LIFETIME_MS})
        out.append({"type": "DISTANCE_LABEL", "anchor": _anchor(cx, min(cy + 0.08, 0.95)), "content": f"{d:.1f}m",
                    "lifetimeMs": LIFETIME_MS})
    return out


def build_document(result: FrameResult, fitted: dict[str, Any], source_wh: tuple[int, int], frame_id: int,
                   timestamp_ms: int) -> dict[str, Any]:
    """Spatial Instruction document for one engine.step() result.

    fitted: engine.last_meta["image"] ({"width", "height"} of the image the engine analysed); source_wh: (width,
    height) of the decoded JPEG. frame_id / timestamp_ms are echoed."""
    fm = FitMap.of(int(source_wh[0]), int(source_wh[1]), int(fitted["width"]), int(fitted["height"]))
    current_lane, lane_count = _lane_numbers(result.lanes)
    lines: list[Line] = []
    for raw in (result.lanes.laneBoundaries if result.lanes is not None else None) or []:
        lines.append([fm.norm(p[0], p[1]) for p in raw if len(p) >= 2 and _finite(p[0], p[1])])
    ego = _ego_pair(lines, result.lanes)
    boundaries = []
    for name, ln in zip(("left", "right"), ego):
        pts = _display_points(ln) if ln is not None else []
        if pts:
            boundaries.append({"id": name, "points": pts})
    vehicles = _vehicles(result, fm, ego)
    required_lane = lane_count                           # demo: the exit is off the rightmost lane
    return {
        "frameId": int(frame_id),
        "timestampMs": int(timestamp_ms),
        "perception": {
            "lanes": {"currentLane": current_lane, "laneCount": lane_count, "boundaries": boundaries},
            "vehicles": vehicles,
            "signs": _signs(result, fm),
        },
        "navigation": {"event": "EXIT", "exitLabel": DEMO_EXIT_LABEL, "distanceLabel": DEMO_DISTANCE_LABEL,
                       "requiredLane": required_lane},
        "instructions": _instructions(boundaries, lane_count, required_lane, vehicles),
    }
