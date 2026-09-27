"""Posted speed limits from OpenStreetMap `maxspeed` via the Overpass API, for navigation.packet.speedLimit.

AI Spatial Driving Copilot, HackGT 13 prototype. The route provider carries no posted limits, so the tablet's only other
source is reading speed-limit signs. Off unless the server runs with --speed-limits osm: a refresh sends the car
position to the Overpass endpoint, a third-party server.

    sl = OsmSpeedLimits()                             # https://overpass-api.de/api/interpreter
    sl.lookup(33.7718, -84.3890, heading_deg=0.0, road_name="Downtown Connector", now_ms=now)
    -> {"valueMph": 55, "source": "osm", "roadName": "Downtown Connector", "wayId": 123, "queriedAtMs": ...} | None

lookup() never blocks. It answers from the ways of the last Overpass response (the car roads within 250 m of the
query point; positions up to 200 m from it re-select from them, so an answer that arrives a few seconds late at
highway speed still covers the car) and starts at most one background request when the car moved >= 40 m from that
response's centre or the response is >= 30 s old (>= 5 s between requests; after HTTP 429 / 5xx / a timeout / an
Overpass error the next request waits 30 s, doubling to 5 min).
The answer is the maxspeed of the nearest car road within 25 m of the car; roads within 8 m of the nearest one tie
and are ranked by the route's road name, then by heading (the nearest segment within 35 degrees of the heading,
against the way's direction only on one-way roads). A nearest road without a usable "N mph" maxspeed answers None:
a parallel road's value is never borrowed. No answer comes from a response older than 60 s or from a selection
made more than 150 m from the car. Coordinates are never logged.
"""
from __future__ import annotations

import json
import logging
import math
import re
import socket
import threading
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any, Optional

log = logging.getLogger(__name__)

DEFAULT_ENDPOINT = "https://overpass-api.de/api/interpreter"
USER_AGENT = "spatial-copilot/1.0 (driving display prototype)"
CAR_ROADS = ("motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|motorway_link|"
             "trunk_link|primary_link|secondary_link|tertiary_link|service")
QUERY_RADIUS_M = 250.0      # ways fetched around the query centre (>= REUSE_RADIUS_M + MATCH_MAX_M: every
                            # re-selection within REUSE_RADIUS_M passes the soundness guard in lookup())
REUSE_RADIUS_M = 200.0      # positions this close to the last response's centre re-select from its ways; covers
                            # where a late answer is first used at highway speed (v * (latency + packet gap):
                            # 36 m/s, 3 s, 1 Hz packets -> 144 m)
REFRESH_MOVE_M = 40.0       # a refresh is due when the car is this far from the last response's centre ...
REFRESH_AGE_MS = 30_000     # ... or the response is this old
MIN_GAP_MS = 5_000          # between two requests
BACKOFF_MS = (30_000, 300_000)   # first wait after a failure, cap (doubling)
TIMEOUT_S = 8.0             # HTTP timeout; the query also asks Overpass for [timeout:8]
ABANDON_MS = 30_000         # a request still running after this counts as a timeout (its answer is ignored)
MATCH_MAX_M = 25.0          # the nearest road must be this close to the car
TIE_MARGIN_M = 8.0          # roads this much farther than the nearest one tie with it
HEADING_TOL_DEG = 35.0
STALE_MS = 60_000           # no answer from a response older than this
STALE_MOVE_M = 150.0        # ... or from a selection made farther away than this
MPH_MIN, MPH_MAX = 5, 85
EARTH_R = 6_371_000.0

_MPH = re.compile(r"^\s*(\d{1,3})\s*mph\s*$", re.IGNORECASE)
_ABBREV = {"st": "street", "ave": "avenue", "av": "avenue", "dr": "drive", "rd": "road", "blvd": "boulevard",
           "hwy": "highway", "pkwy": "parkway", "ln": "lane", "ct": "court", "pl": "place", "fwy": "freeway",
           "expy": "expressway", "n": "north", "s": "south", "e": "east", "w": "west", "ne": "northeast",
           "nw": "northwest", "se": "southeast", "sw": "southwest"}


def parse_mph(value: Any) -> Optional[int]:
    """OSM maxspeed -> mph. Only "N mph" with N in 5..85 counts: a unitless value is km/h in OSM, and "signals",
    "none", "US:urban", "walk" or a list ("35 mph;45 mph") are not a posted number (US display is mph only)."""
    if not isinstance(value, str):
        return None
    m = _MPH.match(value)
    if not m:
        return None
    n = int(m.group(1))
    return n if MPH_MIN <= n <= MPH_MAX else None


def normalize_name(name: Any) -> str:
    """Lower case, punctuation stripped, street-type and compass abbreviations expanded: "Peachtree St. NE" ->
    "peachtree street northeast", "I-75 N" -> "i 75 north"."""
    if not isinstance(name, str):
        return ""
    words = re.sub(r"[^0-9a-z]+", " ", name.lower()).split()
    return " ".join(_ABBREV.get(w, w) for w in words)


def _xy(lat0: float, lng0: float, lat: float, lng: float) -> tuple[float, float]:
    """Metres east / north of (lat0, lng0) (equirectangular, fine over a few hundred metres)."""
    return (math.radians(lng - lng0) * math.cos(math.radians(lat0)) * EARTH_R, math.radians(lat - lat0) * EARTH_R)


def distance_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    x, y = _xy(lat1, lng1, lat2, lng2)
    return math.hypot(x, y)


def _angle_diff(a: float, b: float) -> float:
    return abs((a - b + 180.0) % 360.0 - 180.0)


@dataclass
class Way:
    way_id: int
    name: Optional[str]                 # OSM name, else ref
    names: tuple[str, ...]              # normalized name / alt_name / official_name
    refs: tuple[str, ...]               # normalized ref parts ("i 75", "i 85")
    pts: list[tuple[float, float]]      # (lat, lng)
    oneway: int                         # 1 along the geometry only, -1 against it only, 0 both
    tags: dict[str, str]

    def nearest(self, lat: float, lng: float) -> tuple[float, Optional[float]]:
        """(distance m, bearing deg of the nearest segment along the geometry) from the point."""
        best, bearing = math.inf, None
        px, py = _xy(lat, lng, *self.pts[0])
        for plat, plng in self.pts[1:]:
            x, y = _xy(lat, lng, plat, plng)
            dx, dy = x - px, y - py
            ll = dx * dx + dy * dy
            t = 0.0 if ll <= 0 else max(0.0, min(1.0, -(px * dx + py * dy) / ll))
            d = math.hypot(px + t * dx, py + t * dy)
            if d < best:
                best = d
                if ll > 0:
                    bearing = math.degrees(math.atan2(dx, dy)) % 360.0
            px, py = x, y
        return best, bearing

    def travel_dir(self, bearing: Optional[float], heading: Optional[float]) -> int:
        """+1 driving along the geometry, -1 against it, 0 heading unknown or not aligned (or the wrong way)."""
        if heading is None or bearing is None:
            return 0
        if self.oneway >= 0 and _angle_diff(bearing, heading) <= HEADING_TOL_DEG:
            return 1
        if self.oneway <= 0 and _angle_diff(bearing + 180.0, heading) <= HEADING_TOL_DEG:
            return -1
        return 0

    def name_matches(self, wanted: str) -> bool:
        if not wanted:
            return False
        if wanted in self.names or wanted in self.refs:
            return True
        words = wanted.split()
        for ref in self.refs:          # "i 75 north" names the way with ref "I 75;I 85"
            r = ref.split()
            if any(words[i:i + len(r)] == r for i in range(len(words) - len(r) + 1)):
                return True
        return False

    def limit_mph(self, direction: int) -> Optional[int]:
        """maxspeed, or maxspeed:forward / :backward when the travel direction is known and the way has it."""
        t = self.tags
        if direction > 0 and "maxspeed:forward" in t:
            return parse_mph(t["maxspeed:forward"])
        if direction < 0 and "maxspeed:backward" in t:
            return parse_mph(t["maxspeed:backward"])
        if "maxspeed" in t:
            return parse_mph(t["maxspeed"])
        fwd, bwd = parse_mph(t.get("maxspeed:forward")), parse_mph(t.get("maxspeed:backward"))
        return fwd if fwd is not None and fwd == bwd else None


def parse_ways(doc: Any) -> list[Way]:
    """Overpass JSON (`out tags geom`) -> ways with at least two geometry points."""
    ways = []
    for el in (doc.get("elements") or []) if isinstance(doc, dict) else []:
        if not isinstance(el, dict) or el.get("type") != "way" or not isinstance(el.get("id"), int):
            continue
        tags = {k: v for k, v in (el.get("tags") or {}).items() if isinstance(k, str) and isinstance(v, str)}
        pts = [(float(g["lat"]), float(g["lon"])) for g in el.get("geometry") or []
               if isinstance(g, dict) and all(isinstance(g.get(k), (int, float)) for k in ("lat", "lon"))]
        if len(pts) < 2:
            continue
        ow = tags.get("oneway", "").lower()
        if ow in ("-1", "reverse"):
            oneway = -1
        elif ow in ("yes", "true", "1"):
            oneway = 1
        elif ow == "no":
            oneway = 0
        else:           # implied one-way in OSM
            oneway = 1 if tags.get("highway") == "motorway" or tags.get("junction") in ("roundabout", "circular") else 0
        names = tuple(n for n in (normalize_name(tags.get(k)) for k in ("name", "alt_name", "official_name")) if n)
        refs = tuple(n for n in (normalize_name(r) for r in tags.get("ref", "").split(";")) if n)
        ways.append(Way(el["id"], tags.get("name") or tags.get("ref"), names, refs, pts, oneway, tags))
    return ways


@dataclass
class Selection:
    way: Optional[Way]          # None: no car road within MATCH_MAX_M
    distance_m: Optional[float]
    nearest_m: Optional[float]  # distance of the nearest candidate (the selected one may be up to TIE_MARGIN_M farther)
    direction: int
    value_mph: Optional[int]


def select_way(ways: list[Way], lat: float, lng: float, heading: Optional[float],
               road_name: Optional[str]) -> Selection:
    """The nearest way within MATCH_MAX_M; ways within TIE_MARGIN_M of it are ranked by road name match, then
    heading alignment, then distance. Its own maxspeed only (None when it has no usable value)."""
    cands = []
    for w in ways:
        d, bearing = w.nearest(lat, lng)
        if d <= MATCH_MAX_M:
            cands.append((w, d, bearing))
    if not cands:
        return Selection(None, None, None, 0, None)
    dmin = min(d for _, d, _ in cands)
    wanted = normalize_name(road_name)

    def rank(c):
        w, d, bearing = c
        direction = w.travel_dir(bearing, heading)
        heading_score = 0 if heading is None else (1 if direction else -1)
        return (w.name_matches(wanted), heading_score, -d)
    w, d, bearing = max((c for c in cands if c[1] <= dmin + TIE_MARGIN_M), key=rank)
    direction = w.travel_dir(bearing, heading)
    return Selection(w, d, dmin, direction, w.limit_mph(direction))


def build_query(lat: float, lng: float, radius_m: float = QUERY_RADIUS_M) -> str:
    return (f'[out:json][timeout:{int(TIMEOUT_S)}];way(around:{radius_m:.0f},{lat:.6f},{lng:.6f})'
            f'[highway~"^({CAR_ROADS})$"];out tags geom;')


def packet_inputs(msg: dict[str, Any]) -> Optional[tuple[float, float, Optional[float], Optional[str]]]:
    """navigation.packet -> (lat, lng, heading or None, current road name or None); None without a position.
    heading: packet.progress.heading while moving (>= 1 m/s; a GPS bearing at standstill is noise) and non-zero
    (0 = unknown per PROTOCOL_v2: the tablet and the server send 0 for a fix without a bearing). Road name: the
    route step the car is on, i.e. the step before the active maneuver's (routeState.roadName and
    routeSemantics.roadName name the road of the next maneuver, not the current one); None when off route."""
    pkt = msg.get("packet") if isinstance(msg, dict) else None
    prog = pkt.get("progress") if isinstance(pkt, dict) else None
    loc = prog.get("currentLocation") if isinstance(prog, dict) else None
    if not isinstance(loc, dict):
        return None
    lat, lng = loc.get("lat"), loc.get("lng")
    if not all(isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v) for v in (lat, lng)):
        return None
    if not (-90 <= lat <= 90 and -180 <= lng <= 180):
        return None
    heading = prog.get("heading")
    speed = prog.get("speedMps")
    if (not isinstance(heading, (int, float)) or isinstance(heading, bool) or not math.isfinite(heading)
            or heading == 0 or not isinstance(speed, (int, float)) or speed < 1.0):
        heading = None          # 0 = unknown (PROTOCOL_v2 client.trip_state: "send 0 when unknown")
    road = None
    active = pkt.get("activeManeuver")
    steps = (pkt.get("route") or {}).get("steps") if isinstance(pkt.get("route"), dict) else None
    if not prog.get("offRoute") and isinstance(active, dict) and isinstance(steps, list):
        ids = [s.get("stepId") if isinstance(s, dict) else None for s in steps]
        if active.get("eventId") in ids:
            i = ids.index(active.get("eventId"))
            if i > 0 and isinstance(steps[i - 1], dict) and isinstance(steps[i - 1].get("roadName"), str):
                road = steps[i - 1]["roadName"]
    return float(lat), float(lng), (float(heading) % 360.0 if heading is not None else None), road


class _Job:
    """One Overpass request, run in its own daemon thread; lookup() applies its result."""

    def __init__(self, lat: float, lng: float, now_ms: int):
        self.lat, self.lng, self.now_ms = lat, lng, now_ms
        self.done = threading.Event()
        self.ways: Optional[list[Way]] = None
        self.error: Optional[str] = None


class OsmSpeedLimits:
    """Non-blocking posted-speed-limit lookups from OpenStreetMap (see the module docstring for the rules)."""

    def __init__(self, endpoint: str = DEFAULT_ENDPOINT, *, timeout_s: float = TIMEOUT_S,
                 query_radius_m: float = QUERY_RADIUS_M, user_agent: str = USER_AGENT):
        self.endpoint, self.timeout_s, self.user_agent = endpoint, timeout_s, user_agent
        self.query_radius_m = query_radius_m
        self.lock = threading.Lock()
        self.job: Optional[_Job] = None
        self.cache: Optional[_Job] = None          # the last successful response
        self.last: Optional[tuple[Optional[dict], float, float, int]] = None   # answer, lat, lng, response ms
        self.last_request_ms: Optional[int] = None
        self.backoff_until_ms = 0
        self.backoff_ms = BACKOFF_MS[0]
        self.requests = 0
        self.failures = 0
        self.last_error: Optional[str] = None

    def info(self) -> dict[str, Any]:
        return {"endpoint": self.endpoint, "requests": self.requests, "failures": self.failures,
                "lastError": self.last_error, "inFlight": self.job is not None}

    # ------------------------------------------------------------------ lookups
    def for_packet(self, msg: dict[str, Any], now_ms: int) -> Optional[dict[str, Any]]:
        """The speedLimit field for a navigation.packet (None without a position or a current value)."""
        inputs = packet_inputs(msg)
        return None if inputs is None else self.lookup(*inputs, now_ms=now_ms)

    def lookup(self, lat: float, lng: float, heading_deg: Optional[float], road_name: Optional[str],
               now_ms: int) -> Optional[dict[str, Any]]:
        """Best current value for the position ({valueMph, source, roadName, wayId, queriedAtMs}) or None.
        Never waits for the network: a due refresh is started in the background and used by a later call."""
        with self.lock:
            self._collect(now_ms)
            self._maybe_request(lat, lng, now_ms)
            c = self.cache
            if c is not None and now_ms - c.now_ms <= STALE_MS:
                d = distance_m(c.lat, c.lng, lat, lng)
                if d <= REUSE_RADIUS_M:
                    sel = select_way(c.ways or [], lat, lng, heading_deg, road_name)
                    # sound only if no way outside the response could be a candidate (nearest or tie)
                    reach = MATCH_MAX_M if sel.nearest_m is None else min(MATCH_MAX_M, sel.nearest_m + TIE_MARGIN_M)
                    if reach <= self.query_radius_m - d:
                        answer = None
                        if sel.way is not None and sel.value_mph is not None:
                            answer = {"valueMph": sel.value_mph, "source": "osm", "roadName": sel.way.name,
                                      "wayId": sel.way.way_id, "queriedAtMs": int(c.now_ms)}
                        log.debug("speed limit: way %s %s m, dir %d -> %s", sel.way and sel.way.way_id,
                                  None if sel.distance_m is None else round(sel.distance_m, 1), sel.direction,
                                  sel.value_mph)
                        self.last = (answer, lat, lng, c.now_ms)
                        return answer
            if self.last is not None:
                answer, plat, plng, resp_ms = self.last
                if now_ms - resp_ms <= STALE_MS and distance_m(plat, plng, lat, lng) <= STALE_MOVE_M:
                    return answer
            return None

    # ------------------------------------------------------------------ background requests
    def _collect(self, now_ms: int) -> None:
        job = self.job
        if job is None:
            return
        if not job.done.is_set():
            if now_ms - job.now_ms >= ABANDON_MS:
                self.job = None
                self._failed(now_ms, f"no answer in {ABANDON_MS // 1000} s")
            return
        self.job = None
        if job.error is None:
            self.cache = job
            self.backoff_ms = BACKOFF_MS[0]
            if self.last_error is not None:
                print("[speed-limit] Overpass answering again", flush=True)
            self.last_error = None
        else:
            self._failed(now_ms, job.error)

    def _failed(self, now_ms: int, why: str) -> None:
        self.failures += 1
        self.last_error = why
        self.backoff_until_ms = now_ms + self.backoff_ms
        print(f"[speed-limit] Overpass request failed ({why}); next try in {self.backoff_ms // 1000} s", flush=True)
        self.backoff_ms = min(BACKOFF_MS[1], self.backoff_ms * 2)

    def _maybe_request(self, lat: float, lng: float, now_ms: int) -> None:
        if self.job is not None or now_ms < self.backoff_until_ms:
            return
        if self.last_request_ms is not None and now_ms - self.last_request_ms < MIN_GAP_MS:
            return
        c = self.cache
        if (c is not None and now_ms - c.now_ms < REFRESH_AGE_MS
                and distance_m(c.lat, c.lng, lat, lng) < REFRESH_MOVE_M):
            return
        self.job = _Job(lat, lng, now_ms)
        self.last_request_ms = now_ms
        self.requests += 1
        threading.Thread(target=self._fetch, args=(self.job,), name="speed-limit-fetch", daemon=True).start()

    def _fetch(self, job: _Job) -> None:
        body = urllib.parse.urlencode({"data": build_query(job.lat, job.lng, self.query_radius_m)}).encode()
        req = urllib.request.Request(self.endpoint, data=body, method="POST",
                                     headers={"User-Agent": self.user_agent, "Accept": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=self.timeout_s) as r:
                doc = json.loads(r.read().decode("utf-8"))
            remark = doc.get("remark") if isinstance(doc, dict) else None
            if not isinstance(doc, dict) or not isinstance(doc.get("elements"), list):
                job.error = "unexpected answer"
            elif isinstance(remark, str) and "error" in remark.lower():
                job.error = f"Overpass: {remark[:120]}"
            else:
                job.ways = parse_ways(doc)
        except urllib.error.HTTPError as e:
            job.error = f"HTTP {e.code}"
        except (socket.timeout, TimeoutError):
            job.error = "timeout"
        except urllib.error.URLError as e:
            job.error = "timeout" if isinstance(e.reason, (socket.timeout, TimeoutError)) else type(e.reason).__name__
        except Exception as e:  # noqa: BLE001  (bad JSON, reset connection, ...)
            job.error = type(e).__name__
        finally:
            job.done.set()
