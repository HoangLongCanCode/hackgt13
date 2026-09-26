# Interfaces, end to end

Every data format between the tablet camera and the Glass overlay in the AI Spatial Driving Copilot by Knuckle Sandwich Robotics Inc. (KSR),
plus the Python and Kotlin APIs around them. The normative spec is
[`contracts/PROTOCOL_v2.md`](../../contracts/PROTOCOL_v2.md) with one JSON Schema per message in
[`contracts/schemas/`](../../contracts/schemas/). Every example below is trimmed from a golden sample in
[`contracts/samples/v2/`](../../contracts/samples/v2/), written by the real server and relay. If this page and the spec
disagree, the spec wins; please fix this page.

## Contents

1. [Data flow and coordinate spaces](#1-data-flow-and-coordinate-spaces)
2. [Transport, roles and sessions](#2-transport-roles-and-sessions)
3. [Camera uplink (binary)](#3-camera-uplink-binary)
4. [Client to server messages](#4-client-to-server-messages)
5. [Server to client messages](#5-server-to-client-messages)
6. [navigation.packet to RouteState](#6-navigationpacket-to-routestate)
7. [Perception to VisionData](#7-perception-to-visiondata)
8. [Python in-process API](#8-python-in-process-api)
9. [Kotlin API](#9-kotlin-api)
10. [HTTP endpoints and files on disk](#10-http-endpoints-and-files-on-disk)

## 1. Data flow and coordinate spaces

```text
 tablet camera (YUV_420_888, sensor orientation)                              [CameraX buffer space]
   -> LaptopVisionSource: downscale to <= 960 px wide, JPEG q80, KSR1 header with rotationDegrees
   -> WebSocket binary frame
 laptop server: decode, rotate clockwise by rotationDegrees, scale to <= 1280 px wide  [server image space, px]
   -> perception.frame / perception.update (bbox [x1,y1,x2,y2] px, lanes, road, image {width,height})
 tablet PerceptionBridge: WorldModel (merge by track id, smoothing, staleness), DrivingContextEngine
   -> VisionMapper: px / image size -> PreviewCoordinates (rotation 0, FILL_CENTER) -> clip to 0..1
   -> VisionData boxes [x,y,w,h] 0..1 of the view                                       [overlay space]
   -> AROverlay (GLASS / DEBUG)
 phase1 (laptop, Node child) -> navigation.packet.routeState -> BridgeRouteSource -> RouteState -> AROverlay
```

| Space | Where | Definition |
|---|---|---|
| CameraX buffer | `ImageProxy` on the tablet | Sensor orientation; `imageInfo.rotationDegrees` = rotate clockwise by this to make it upright |
| Server image | every `bbox`, lane and road point in `perception.*` | Pixels of the upright analysed image, `(0,0)` top-left, size `image.width` x `image.height` |
| Ground | `distanceMeters`, `lateralMeters`, `groundXZ` | Metres on flat ground from the camera; lateral + = right, forward + = ahead |
| Overlay | `VisionData` | `[x, y, w, h]` fractions 0..1 of the view (top-left + size); the image covers the view (`FILL_CENTER` / `RESIZE_MODE_ZOOM`) and is cropped |

Time bases: `ptsSeconds` is media time (clip pts in video/sim; seconds since the first captured frame in live).
`captureTimeNs` and `clientTimeNs` are the tablet's clock and come back unchanged. `serverTimeMs` is the laptop's
epoch ms. `timestampMs` / `tripTimestampMs` are GPS epoch ms. Never subtract clocks of different devices.

## 2. Transport, roles and sessions

- One WebSocket: `ws://<host>:8765/perception`. Text frames are JSON messages; binary frames are camera uplink (live).
- USB: `adb reverse tcp:8765 tcp:8765`, then `ws://127.0.0.1:8765/perception` on the tablet. Wi-Fi:
  `ws://<laptop-LAN-IP>:8765/perception` (the server prints its LAN URLs at start-up).
- **Controller and watchers.** The client whose `client.hello` started the live or sim session is the controller: it
  uplinks frames, reports playback and feeds live navigation. The newest `client.hello` takes over; the previous
  controller becomes a watcher (`perception.hello.role: "watcher"`) and gets `perception.error notUplinkClient`.
  Clients without a hello are watchers. Everyone receives all results, but a watcher must not treat live / sim
  results as its own: the Kotlin bridge then stops uplinking, ignores them (`LinkStatus.takenOver`), and re-sends its
  hello by itself when the server goes idle (`sessionId "idle"`).
- **Sessions.** A new `sessionId` is announced by a new `perception.hello` on: a new controller, a video loop, a sim
  seek (jump of more than 0.6 s x max(1, rate) against the extrapolated position), a clip switch (`client.playback`
  with a different `videoId`), a controller asking for a clip the laptop lacks, and a mid-session change of the
  upright uplink frame size. Clients reset tracks, the sim buffer and lanes; credits come back through the server's
  answers to the frames in flight (they are not reset). Track ids are only comparable within a session.
- A client whose socket accepts no data for 10 s is dropped by the server.

Live sequence:

```text
 tablet                                             laptop
 client.hello {mode: live, camera}  ------------->
                                    <-------------  perception.hello {sessionId, uplink.maxInFlight: 2, ...}
                                    <-------------  navigation.packet (last one, if any)
 KSR1 + JPEG (frameId 1)            ------------->
 KSR1 + JPEG (frameId 2)            ------------->  (2 in flight: the next camera frame is dropped on the tablet)
                                    <-------------  perception.frame {wave 1, echo.frameId 1}   (credit back)
                                    <-------------  perception.skip {frameId 2, superseded} or perception.frame
                                    <-------------  perception.update {wave 2}  (whenever the slow lane finishes)
 client.trip_state (~1 Hz)          ------------->
                                    <-------------  navigation.packet
 client.ping (~1 Hz)                ------------->
                                    <-------------  perception.pong, perception.stats (~1 Hz)
```

Sim sequence: `client.hello {mode: sim, sim: {videoId}}` -> `perception.hello` (with `sim.lookaheadSeconds`) ->
`client.playback` about 10 Hz and on every seek, pause and resume -> the server analyses the frame at
`playback + lookahead` and sends `perception.frame` / `perception.update` keyed by `ptsSeconds`, plus
`navigation.packet` about every 0.5 s of media time when `--nav-session` is set.

## 3. Camera uplink (binary)

One binary message = a 24-byte little-endian header followed by a baseline JPEG (Python `struct.Struct("<4sHHIqHH")`,
Kotlin `UplinkHeader`).

| Offset | Size | Field | Type | Value |
|---|---|---|---|---|
| 0 | 4 | magic | ASCII | `KSR1` |
| 4 | 2 | headerVersion | uint16 | 1 |
| 6 | 2 | flags | uint16 | 0 |
| 8 | 4 | frameId | uint32 | client counter, wraps |
| 12 | 8 | captureTimeNs | int64 | tablet clock (`elapsedRealtimeNanos` base) |
| 20 | 2 | rotationDegrees | uint16 | 0 / 90 / 180 / 270: rotate the JPEG clockwise by this to make it upright |
| 22 | 2 | reserved | uint16 | 0 |

Real header from `uplink_header.example.txt` (frameId 1, followed by a 60,965-byte 960x540 q80 JPEG):

```text
4b 53 52 31 01 00 00 00 01 00 00 00 14 d8 ea d0 65 37 01 00 00 00 00 00
```

Rules:
- Recommended 960x540, JPEG quality about 80 (50-80 KB). Wider than 1280 px is scaled down on the server.
- **Credits:** at most `uplink.maxInFlight` (default 2) frames without a wave-1 answer. Every frame gets exactly one:
  a `perception.frame` with a matching `echo.frameId`, or a `perception.skip`. No credit: drop the frame, never queue.
  The Kotlin bridge returns a credit after 1 s if no answer came (`creditTimeouts`).
- Uplink only while the server hello says `mode == "live"`. An `--mode auto` server advertises `uplink` in sim sessions
  too, because a `client.hello` can switch it to live.

## 4. Client to server messages

### client.hello

First message after every (re)connect. Required for live and sim.

| Field | Type | Notes |
|---|---|---|
| `type` | `"client.hello"` | |
| `protocolVersion` | 2 | |
| `clientId` | string | stable per device, e.g. `tab-s9-01` |
| `device` | object or null | `manufacturer`, `model`, `osVersion` |
| `mode` | `video` / `live` / `sim` | |
| `camera` | object or null | live: intrinsics of the **uplinked, upright** image. `imageWidth`, `imageHeight` (required), `focalPx`, `principalPoint` `[cx, cy]`, `mountHeightMeters`, `pitchDegrees`, `lensFacing`, `stabilization`. Nulls = server defaults |
| `sim` | object or null | `{ "videoId": "<clip stem on both devices>" }` |
| `navigation` | object or null | hint `{ "mode": "sim" \| "live" \| "off" }`; the server's flags decide |

```json
{"type": "client.hello", "protocolVersion": 2, "clientId": "ws-probe",
 "device": {"manufacturer": "ksr", "model": "ws_probe", "osVersion": "win32"}, "mode": "live",
 "camera": {"imageWidth": 960, "imageHeight": 540, "focalPx": 525.0, "principalPoint": [480.0, 270.0],
            "mountHeightMeters": 1.3, "pitchDegrees": null, "lensFacing": "back", "stabilization": false},
 "sim": null}
```

A camera size that does not match the uplinked frames gets a non-fatal `perception.error badMessage`, and the server
adapts the intrinsics to the real size.

### client.playback (sim controller)

| Field | Type | Notes |
|---|---|---|
| `videoId` | string | a different id switches the clip |
| `ptsSeconds` | number | player position (container pts) |
| `playing` | bool | |
| `rate` | number | optional, default 1.0 |
| `clientTimeNs` | int | optional, tablet clock |

```json
{"type": "client.playback", "videoId": "b1ff4656-0435391e", "ptsSeconds": 5.0831, "playing": true, "rate": 1.0, "clientTimeNs": 342403897769400}
```

### client.ping

`{"type": "client.ping", "clientTimeNs": 342398699440600}`, about 1 Hz; answered by `perception.pong`.

### client.trip_state (live navigation)

Same field names as a phase1 `trip_state.jsonl` line, plus `type`. About 1 Hz from the tablet GPS; each one is answered
by one `navigation.packet`. Sent while live navigation is not running, it gets one `perception.error modeNotAvailable`.
Only the controller (or a client whose hello has `navigation.mode: "live"`) may send it (`notUplinkClient` otherwise);
an invalid sample gets `badMessage`.

| Field | Type | Notes |
|---|---|---|
| `timestampMs` | int | epoch ms of the fix; a jump back of more than 60 s starts a new history |
| `location` | `{lat, lng}` | degrees |
| `heading` | number | degrees clockwise from north; 0 when unknown |
| `speedMps` | number | m/s; 0 when unknown |
| `accuracyMeters` | number or null | optional |

```json
{"type": "client.trip_state", "timestampMs": 1790000000123, "location": {"lat": 33.7765228, "lng": -84.3961545}, "heading": 9.6, "speedMps": 7.64, "accuracyMeters": 4.1}
```

## 5. Server to client messages

### perception.hello

Sent on connect, after every `client.hello`, and whenever a new session starts.

| Field | Notes |
|---|---|
| `protocolVersion`, `schemaVersion` | both 2 |
| `sessionId` | `idle` while a live/sim server has no controller |
| `serverTimeMs` | laptop epoch ms |
| `mode` | `video` / `live` / `sim` (an auto server reports `live` while idle) |
| `acceptedModes` | modes a `client.hello` may ask for, e.g. `["live", "sim"]` |
| `role` | `controller` / `watcher` for the receiving client |
| `source` | `{kind: video \| camera \| replay, id}` |
| `sourceFps`, `targetHz` | clip frame rate; analysed rate the trackers are sized for (16) |
| `image` | `{width, height}` of the analysed upright image |
| `camera` | `{focalPx, principalPoint, horizonY, cameraHeightMeters}` in image pixels |
| `classes`, `staticIdOffset` | the 10 classes; lights and signs have ids >= 1,000,000 |
| `uplink` | `{maxInFlight, preferredWidth, preferredHeight, jpegQuality, header: "KSR1"}` or null |
| `sim` | `{lookaheadSeconds, lookaheadMode: auto \| fixed, videoId, videos: [...]}` or null (`lookaheadSeconds` in media seconds) |
| `schedule` | blocks per lane (`fast`: every frame; `slow`: every n-th slow cycle) |
| `models` | `[{block, name, detail, licence}]` |
| `navigation` | `{mode: sim \| live \| off, available, error}`: whether the phase1 relay runs |
| `server`, `safety` | free-form settings; the display-only statement |

```json
{"type": "perception.hello", "protocolVersion": 2, "schemaVersion": 2, "sessionId": "live-ws-probe-20260926T033112-e5f768",
 "serverTimeMs": 1790411472014, "mode": "live", "acceptedModes": ["live", "sim"], "role": "controller",
 "source": {"kind": "camera", "id": "ws-probe"}, "sourceFps": null, "targetHz": 16.0,
 "image": {"width": 960, "height": 540},
 "camera": {"focalPx": 525.0, "principalPoint": [480.0, 270.0], "horizonY": null, "cameraHeightMeters": 1.3},
 "classes": ["pedestrian", "rider", "car", "truck", "bus", "train", "motorcycle", "bicycle", "traffic light", "traffic sign"],
 "staticIdOffset": 1000000,
 "uplink": {"maxInFlight": 2, "preferredWidth": 960, "preferredHeight": 540, "jpegQuality": 80, "header": "KSR1"},
 "sim": {"lookaheadSeconds": 0.35, "lookaheadMode": "auto", "videos": ["b1d968b9-ce42734f", "..."]},
 "schedule": {"detection": {"lane": "fast", "every": 1, "phase": 0}, "...": "..."},
 "models": [{"block": "detection", "name": "bdd-yolo26s", "detail": "imgsz 960, conf 0.25", "licence": "AGPL-3.0 (Ultralytics) + BDD100K non-commercial data terms: research/demo only"}, "..."],
 "navigation": {"mode": "sim", "available": true, "error": null},
 "safety": "Informational driver display only (plan section 38): ..."}
```

### perception.frame (wave 1)

One per analysed frame, from the fast lane, carrying the latest slow-lane results.

| Field | Notes |
|---|---|
| `wave` | 1 |
| `seq` | per session: video = source frame counter (gaps = dropped), sim = submitted counter, live = uplinked counter |
| `sessionId`, `source`, `frameIndex` | live: `frameIndex` = `seq` |
| `ptsSeconds` | media time (see section 1) |
| `serverTimeMs`, `processingMs` | frame available on the server -> message built (includes queue wait and JPEG decode) |
| `image`, `camera` | coordinate space and session camera |
| `objects[]` | tracked objects, table below |
| `signs[]` | `{id, signClass, bbox, confidence, distanceMeters}`; `signClass` camelCase: `stop`, `yield`, `doNotEnter`, `speedLimit45`, `pedestrianCrossing` |
| `lanes` | `{currentLane, laneCount, laneBoundaries, confidence}` or null. Lanes numbered from the left; boundaries left to right, <= 20 points, may extend outside the image |
| `road` | `{drivableCoverage, egoPathPolygon, horizonY, vanishingPoint, anchorPoints[{name, xy, groundXZ, valid}]}` or null |
| `blockAges` | analysed frames since each block produced the carried result (fast blocks 0) |
| `timingsMs` | `detect`, `track`, `trackStatic`, `lights`, `geometryDistance`, `total`, `queueWait`, `jpegDecode`, `fastLane` |
| `echo` | live: `{frameId, captureTimeNs}` copied from the uplink header; null in video/sim |

Object fields:

| Field | Notes |
|---|---|
| `id` | track id; lights and signs >= 1,000,000 |
| `class` | one of the 10 classes |
| `bbox` | `[x1, y1, x2, y2]` px in `image` space |
| `confidence`, `ageFrames` | |
| `distanceMeters` | forward distance (m) to the nearest face, or null |
| `distanceMethod` | `fused` / `depth_model` / `ground_plane` / `width_prior` (slow lane, carried); `geometry` (fast lane on this frame, confidence <= 0.5, used when no slow-lane distance younger than 1 s exists); `size_prior` (lights, signs) |
| `distanceConfidence` | 0..1 or null (relative, not calibrated) |
| `distanceAgeMs` | media ms since the distance was measured (0 = this frame; null = no distance) |
| `lateralMeters` | + = right of the camera axis |
| `ttcSeconds` | time to collision from box growth; null when not closing |
| `approaching` | closing and inside the ego corridor (hysteresis) |
| `inEgoPath` | box bottom inside the ego vehicle's +-1.3 m corridor up to 80 m ahead (null for lights and signs) |
| `lightState`, `lightConfidence` | traffic lights only: `RED` / `YELLOW` / `GREEN` / `UNKNOWN` |

```json
{"type": "perception.frame", "schemaVersion": 2, "wave": 1, "seq": 220, "sessionId": "video-b1ff4656-0435391e-20260926T032856-849e3d",
 "source": {"kind": "video", "id": "b1ff4656-0435391e"}, "frameIndex": 220, "ptsSeconds": 7.24,
 "serverTimeMs": 1790411344221, "processingMs": 112.98,
 "image": {"width": 1280, "height": 720},
 "camera": {"focalPx": 700.0, "principalPoint": [640.0, 360.0], "horizonY": 363.4, "cameraHeightMeters": 1.349},
 "objects": [
  {"id": 3, "class": "car", "bbox": [676.0, 374.0, 850.7, 495.3], "confidence": 0.902, "ageFrames": 91,
   "distanceMeters": 7.86, "distanceMethod": "fused", "distanceConfidence": 0.87, "lateralMeters": 0.98,
   "ttcSeconds": null, "approaching": false, "inEgoPath": false, "lightState": null, "lightConfidence": null, "distanceAgeMs": 266.7},
  {"id": 1000003, "class": "traffic light", "bbox": [447.7, 157.4, 463.7, 196.0], "confidence": 0.615, "ageFrames": 91,
   "distanceMeters": 19.4, "distanceMethod": "size_prior", "distanceConfidence": null, "lateralMeters": -5.11,
   "ttcSeconds": null, "approaching": null, "inEgoPath": null, "lightState": "GREEN", "lightConfidence": 0.982, "distanceAgeMs": 0.0}],
 "signs": [],
 "lanes": {"currentLane": 1, "laneCount": 1, "laneBoundaries": [[[-17.2, 718.0], [456.2, 454.0]], "..."], "confidence": 0.306},
 "road": {"drivableCoverage": 0.097, "egoPathPolygon": [[-17.2, 718.0], [427.2, 470.0], "..."], "horizonY": 446.4,
          "vanishingPoint": [440.4, 462.0],
          "anchorPoints": [{"name": "ego_lane_center_near", "xy": [815.9, 677.3], "groundXZ": [1.04, 4.32], "valid": true}, "..."]},
 "blockAges": {"detection": 0, "tracking": 0, "lights": 0, "distance": 3, "depth": 3, "lanes": 3, "signs": 5, "road": 3},
 "timingsMs": {"detect": 46.29, "track": 18.3, "trackStatic": 3.24, "lights": 14.92, "geometryDistance": 0.16,
               "total": 83.01, "queueWait": 29.16, "fastLane": 83.21},
 "echo": null}
```

Trimmed from `perception.frame.wave1.city.json` (2 of its objects, 1 of its 3 lane lines).

### perception.update (wave 2)

Sent whenever the slow lane finishes. `seq` / `frameIndex` / `ptsSeconds` / `echo` identify the frame the slow blocks
analysed, usually a few frames older than the newest wave 1. Blocks that did not run are omitted; an empty list means
the block ran and found nothing.

| Field | Notes |
|---|---|
| `wave` | 2 |
| `seq`, `sessionId`, `frameIndex`, `ptsSeconds`, `echo`, `serverTimeMs`, `processingMs`, `camera` | as in wave 1 |
| `distances[]` | `{id, distanceMeters, distanceMethod, distanceConfidence, lateralMeters}`; ids match wave-1 `objects[].id` |
| `lanes`, `road`, `signs` | as in wave 1 |
| `blocks` | which ran, subset of `distance`, `depth`, `lanes`, `segmentation`, `signs` (`depth` = the network ran; otherwise geometry + Kalman only) |
| `timingsMs` | e.g. `distance`, `depthNet`, `lanes`, `total` |

```json
{"type": "perception.update", "schemaVersion": 2, "wave": 2, "seq": 0, "sessionId": "video-b1ff4656-0435391e-20260926T032856-849e3d",
 "frameIndex": 0, "ptsSeconds": 0.0, "echo": null, "serverTimeMs": 1790411337230, "processingMs": 361.4,
 "camera": {"focalPx": 700.0, "principalPoint": [640.0, 360.0], "horizonY": 363.9, "cameraHeightMeters": 1.31},
 "distances": [{"id": 1, "distanceMeters": 5.02, "distanceMethod": "fused", "distanceConfidence": 0.798, "lateralMeters": 2.36}, "..."],
 "lanes": {"currentLane": 1, "laneCount": 1, "laneBoundaries": ["..."], "confidence": 0.0},
 "road": {"drivableCoverage": 0.112, "egoPathPolygon": ["..."], "horizonY": 353.9, "...": "..."},
 "blocks": ["distance", "depth", "lanes"],
 "timingsMs": {"distance": 209.48, "depthNet": 143.55, "lanes": 61.37, "total": 270.89}}
```

### perception.skip (live)

`{"type": "perception.skip", "frameId": 0, "reason": "badHeader", "sessionId": "live-ws-probe-...", "serverTimeMs": 1790411472036}`.
Every reason returns the frame's credit.

| Reason | When |
|---|---|
| `superseded` | a newer frame replaced it in the inbox, or its finished result was replaced in the controller's send slot |
| `decodeError` | the JPEG could not be decoded |
| `badHeader` | invalid KSR1 header |
| `notAccepted` | sender is not the controller, server not in live mode, or the fast lane failed on it (plus `perception.error internal`) |
| `sessionReset` | the session changed before its result was sent |

### perception.stats (about 1 Hz)

Required: `outputFps` (wave-1 messages/s), `wave1ProcessingMs {p50, p95}`, `wave2ProcessingMs {p50, p95}`, `framesIn`,
`framesAnalysed`, `framesSkipped`, `clients`. Diagnostics (optional for clients): `schemaVersion`, `sessionId`,
`serverTimeMs`, `mode`, `windowSeconds`, `wave2Fps`, `distanceFps`, `sourceFps`, `wave1ComputeMs` / `wave2ComputeMs`
(model time without queueing), `updates`, `sendDropped`, `framesDropped`, `lookaheadSeconds`, `simLeadMs {p5, p50}`,
`simLateFraction`, `uplinkClient`, `navigationPackets`, `uptimeSeconds`.

```json
{"type": "perception.stats", "schemaVersion": 2, "mode": "video", "windowSeconds": 3.0, "outputFps": 13.16, "wave2Fps": 6.75,
 "wave1ProcessingMs": {"p50": 85.39, "p95": 107.98}, "wave2ProcessingMs": {"p50": 270.11, "p95": 321.85},
 "wave1ComputeMs": {"p50": 73.31, "p95": 92.2}, "framesIn": 585, "framesAnalysed": 246, "framesSkipped": 0,
 "clients": 1, "framesDropped": 337, "...": "..."}
```

### perception.pong

`{"type": "perception.pong", "clientTimeNs": 342250111542600, "serverTimeMs": 1790411336797}`: the tablet computes the
round trip as `now - clientTimeNs` on its own clock.

### perception.error

`{type, code, message, fatal, detail, serverTimeMs}`. Rate-limited per code and client. The Kotlin bridge shows it in
`LinkStatus.serverError` until a `perception.hello` of another session arrives.

| Code | When |
|---|---|
| `badMessage` | invalid JSON or fields, or a camera size that does not match the uplinked frames (non-fatal; intrinsics adapted) |
| `modeNotAvailable` | the requested mode is not running on this server (`--mode`), `client.playback` outside sim, camera frames outside live, or a `client.trip_state` without live navigation |
| `unknownVideo` | sim `videoId` not found on the laptop (once per hello; playback reports re-check it every 5 s without repeating the error); `detail.videos` lists up to 50 clips it has |
| `notUplinkClient` | a newer `client.hello` took over (this client is now a watcher), or a non-controller sent frames, playback or trip states |
| `internal` | a server-side failure, for example the fast lane raised on an uplinked frame (a relay that fails to start is reported in `perception.hello.navigation.error` instead) |

### navigation.packet

Server to client. Sim: for the current playback position, about 2 Hz. Live: one per `client.trip_state`. A newly
connected client immediately gets the last one.

| Field | Notes |
|---|---|
| `schemaVersion`, `serverTimeMs` | 2; laptop epoch ms |
| `ptsSeconds` | sim: the media time the packet is for; null in live |
| `tripTimestampMs` | sim: `trip_state[0].timestampMs + 1000 * pts` (or `videoStartTimestampMs + 1000 * pts` when the manifest sets it); live: newest trip state's timestamp |
| `routeState` | flat subset, below |
| `packet` | phase1 `SpatialNavigationPacket`, verbatim: `packetType`, `tripId`, `routeId`, `generatedAtMs`, `source`, `destination`, `progress`, `route` (polyline, steps, totals), `activeManeuver`, `upcomingManeuvers`, `spatialInstructions`, `audioInstructions`, `routeSemantics`, `handoffHints` |

`routeState` fields: `action` (phase1 `activeManeuver.type`: `START_ROUTE`, `GO_STRAIGHT`, `TURN_LEFT`, `TURN_RIGHT`,
`KEEP_LEFT`, `KEEP_RIGHT`, `MERGE`, `EXIT_HIGHWAY`, `ARRIVE`; `GO_STRAIGHT` when none), `audio`
(`audioInstructions[0].content`, `""` when none), `ui` (`spatialInstructions[0].type`: `TURN_ARROW`, `LANE_ARROW`,
`EXIT_MARKER`, `DISTANCE_LABEL`, `WARNING`; `DISTANCE_LABEL` when none), `distanceMeters` (to the active maneuver, or
null), `offRoute` (more than 35 m from the route), `etaSeconds`, `remainingDistanceMeters`, `requiredLane` (free text
such as `"right"`, `"2"`, `"2-3"`; clients also accept a number), `turnDirection` (`left` / `right` / `straight` /
`merge` / `exit`), `roadName`.

```json
{"type": "navigation.packet", "schemaVersion": 2, "serverTimeMs": 1790000000000, "ptsSeconds": null, "tripTimestampMs": 1790000000123,
 "routeState": {"action": "TURN_RIGHT", "audio": "TURN RIGHT in 17 m.", "ui": "TURN_ARROW", "distanceMeters": 17, "offRoute": false,
                "etaSeconds": 26, "remainingDistanceMeters": 197, "requiredLane": null, "turnDirection": "right", "roadName": "Mock Street"},
 "packet": {"packetType": "SPATIAL_NAVIGATION_PACKET", "tripId": "live_1790000000000", "routeId": "demo_b1ff4656-0435391e_mock",
            "activeManeuver": {"eventId": "step_2", "type": "TURN_RIGHT", "distanceMeters": 120, "roadName": "Mock Street", "instruction": "Turn right onto the next road"},
            "spatialInstructions": [{"id": "spatial_step_2", "type": "TURN_ARROW", "anchor": {"kind": "ROUTE_POINT", "routeDistanceMeters": 120}, "content": "TURN RIGHT\n17 m", "priority": 1, "lifetimeMs": 4000}],
            "audioInstructions": [{"id": "audio_step_2", "content": "TURN RIGHT in 17 m.", "priority": 1, "speakAtDistanceMeters": 120}],
            "handoffHints": {"confidence": 0.95, "displayPriority": 1, "staleAfterMs": 5000}, "...": "..."}}
```

Trimmed from `navigation.packet.live.json` (made by `nav/make_contract_samples.js` with a fixed clock).

## 6. navigation.packet to RouteState

The app's `RouteState(time, action, audio, ui)` (in `Models.kt`) comes from `BridgeRouteSource`
(`app/.../glass/perception/RouteSource.kt`):

| RouteState | From |
|---|---|
| `action` | `routeState.action` as is. `AROverlay`'s arrow logic already reads LEFT / RIGHT / STRAIGHT from these names |
| `audio` | `routeState.audio` |
| `ui` | `routeState.ui` |
| `time` | the packet's `ptsSeconds` in SIM, otherwise seconds since start |

| Situation | RouteState |
|---|---|
| no packet yet | `action = WAITING_FOR_ROUTE`, `ui = NONE` |
| packet without a route | `action = NO_ROUTE` |
| no packet for 10 s (`BridgeConfig.navigationStaleAfterMs`) | last action kept, `audio = "Route updates paused"`, `ui = WARNING` |
| MOCK, or navigation off (`ksr.nav=false`) | the 5 s mock loop in `MockDataViewModel` |

The Driving Context also turns the packet into a `NavigationState` (`NavigationMapper`): maneuver, distance, label,
required lanes (1-based from the left) or side, audio, `offRoute`. Lane guidance combines it with the perceived
`currentLane` / `laneCount`; without a required lane the side is inferred from the turn direction within 300 m of the
maneuver, and there is no guidance while `offRoute`.

## 7. Perception to VisionData

`VisionMapper.map(world, viewWidth, viewHeight)` (`app/.../glass/perception/VisionMapper.kt`) turns a `WorldSnapshot`
into the overlay's `VisionData`. In LIVE the snapshot is `bridge.predictedAt(now)` (boxes moved forward from capture
time by track velocity, at most 300 ms); in SIM it is `bridge.resultForPts(playerPosition)`.

| VisionData | Source (server image space) | Rule |
|---|---|---|
| `vehicles[]` `{id, box, distanceMeters}` | `world.objects` of class car, truck, bus, motorcycle, bicycle, pedestrian, rider | Visible in the newest frame and with a distance; nearest first; off-screen ones dropped; at most 8. `id` = track id; distance = WorldModel-smoothed |
| `signs[]` (lights) | `world.objects` of class traffic light | `LIGHT_RED` / `LIGHT_YELLOW` / `LIGHT_GREEN` from the debounced state; `UNKNOWN` not drawn; `id` = track id |
| `signs[]` (road signs) | `world.signs` seen within 0.5 s | `STOP`, `YIELD`, `SPEED_LIMIT_<n>`, `DO_NOT_ENTER`, `PEDESTRIAN_CROSSING`, other classes `WARNING`, `unknown` dropped; `id` = 100000 + sign id |
| `lanes[]` `{id, points}` | `world.lanes` if the last run is <= 1 s old | Ego boundaries -> `left` / `right`, others `lane_<i>`; points near to far, clipped to the view |
| `exitSigns[]` | none | always empty (no exit-sign detector yet) |
| `time` | `ptsSeconds` | LIVE: seconds since the first uplinked frame; SIM: media time |
| everything | `world.perceptionStale` | stale -> all lists empty (never draw old markers) |

Box conversion for an image of `W x H` px shown in a view of `Vw x Vh` px:

```text
 normalised buffer box: (x1/W, y1/H, (x2-x1)/W, (y2-y1)/H)
 PreviewCoordinates.mapBox(box, rotationDegrees = 0, bufferWidth = W, bufferHeight = H, viewWidth = Vw, viewHeight = Vh)
   FILL_CENTER: s = max(Vw/W, Vh/H); dx = (Vw - W*s)/2; dy = (Vh - H*s)/2
   x_view = (x_px*s + dx) / Vw,  y_view = (y_px*s + dy) / Vh,  then clipped to 0..1
 Tab S9 (2560 x 1600) with a 1280 x 720 frame: s = 2.222, 142 px cropped each side, image columns 64..1216 visible
```

`FrameGeometry.viewWidth/viewHeight` is written by `CameraPreview` (LIVE) or `SimVideoBackground` (SIM); a size of 0
gives empty `VisionData`.

## 8. Python in-process API

Run from `perception_engine/` so `import perception` resolves to the repo copy.

### PerceptionEngine

```python
from perception.engine import PerceptionEngine
from perception.common.video import VideoFileInput, DATA_ROOT

eng = PerceptionEngine("perception/config_realtime.yaml")      # loads every enabled block once (~13 s + warm-up)
for frame in VideoFileInput(DATA_ROOT / "videos" / "val" / "b1ff4656-0435391e.mov"):   # Frame(index, pts_s, image BGR)
    result = eng.step(frame)        # perception.common.schemas.FrameResult (serial schedule)
    meta = eng.last_meta            # blockAges, per-track depth details, camera, which blocks ran
eng.close()
```

`FrameResult` (`perception/common/schemas.py`, dataclasses):

| Field | Type |
|---|---|
| `frameIndex`, `ptsSeconds` | int, float |
| `detections` | `list[Detection(cls, bbox [x1,y1,x2,y2], confidence, id)]` |
| `tracks` | `list[TrackState(id, cls, bbox, age_frames, scale_rate, ttc_s, approaching, lateral_px_s)]` |
| `distances` | `list[DistanceEstimate(vehicleId, distanceMeters, method, confidence)]` |
| `lanes` | `LaneState(currentLane, laneCount, laneBoundaries, confidence)` or None |
| `trafficLights` | `list[TrafficLightState(id, state, bbox, confidence, distanceMeters)]` |
| `trafficSigns` | `list[TrafficSign(id, signClass, bbox, confidence, distanceMeters)]` |
| `road` | `RoadGeometry(drivableCoverage, egoPathPolygon, horizonY, vanishingPoint, anchorPoints)` or None |
| `timingsMs` | `dict[str, float]` |

Two-lane use (what the server does): `fast_step(frame) -> (FrameResult, meta, FastSnapshot)` from one thread,
`slow_step(snapshot) -> SlowResult | None` from another; `perception/realtime/pipeline.py` `TwoLanePipeline` wraps
them. `perception.engine.geometry_distance(cls, box, camera, horizon_y, horizon_sigma_px)` is the fast-lane distance.
Each block is usable alone (`perception.detection.Detector`, `perception.tracking.Tracker`,
`perception.depth.DistanceEstimator`, `perception.lanes.LaneDetector`, `perception.traffic.lights.TrafficLightClassifier`,
`perception.traffic.signs.SignRecognizer`, `perception.segmentation.semantic.SemanticSegmenter`); see the block READMEs.

### Wire builders and KSR1 header

`perception/realtime/wire.py`: `to_wire(result, meta) -> perception.frame dict`, `make_update`, `make_hello`,
`make_stats`, `make_skip`, `make_pong`, `make_error`, `dumps` (orjson), `pack_uplink_header(frame_id, capture_ns,
rotation)`, `parse_uplink(data) -> (UplinkHeader, memoryview)` (raises `UplinkError(reason, frame_id)`), and
`EgoPath` (the `inEgoPath` rule).

### PerceptionBus

The server publishes every wave-1 and wave-2 dict in-process before serialising it (`Server.bus`):

```python
from perception.realtime.subscribers import PerceptionBus
bus = PerceptionBus()
bus.subscribe(lambda msg: print(msg["seq"], len(msg["objects"])))           # runs in the publisher thread: keep it fast
q = bus.subscribe_queue(types=("perception.frame", "perception.update"))    # latest-wins queue for a consumer thread
msg = q.get(timeout=1.0)                                                    # newest message, or None
```

Every subscriber gets the same dict object: treat it as read-only. Demo consumer without the server:
`python -m perception.realtime.subscribers --video data/bdd100k/videos/val/b1ff4656-0435391e.mov --seconds 10`.

### NavRelay

```python
from perception.realtime.nav_relay import NavRelay, NavRelayError
relay = NavRelay(phase1_dir=None, node_exe="node")             # raises NavRelayError if phase1 / node is missing
relay.start_sim("nav/demo_sessions/b1ff4656-0435391e")
msg = relay.packet_at_pts(12.3)                                  # navigation.packet dict, or None
relay.start_live(route_json=None, origin=None, destination="Georgia Tech", provider="mock")
msg = relay.on_trip_state(trip_state_body)                       # dict or None (relay.last_error says why)
relay.status(); relay.close()
```

Blocking and thread-safe: call it from a worker thread. Details: [INTEGRATION_PHASE1.md](INTEGRATION_PHASE1.md).

## 9. Kotlin API

### PerceptionBridge (`driving_assist/perception-bridge`, package `com.ksr.copilot.bridge`)

```kotlin
val bridge = PerceptionBridge(url, scope, BridgeConfig(clockNs = SystemClock::elapsedRealtimeNanos))
bridge.connect(ClientHello.live(clientId, ClientCamera(960, 540, focalPx, null, mountHeightMeters = 1.25)))
// or ClientHello.sim(clientId, videoId) / ClientHello.video(clientId); optional NavigationHint.SIM / LIVE / OFF
```

| Member | What |
|---|---|
| `connect(hello)` | Opens the socket; reconnects with backoff (250 ms to 3 s) and re-sends the hello; pings at 1 Hz |
| `reclaim(): Boolean` | Re-sends the hello to take the session back after `LinkStatus.takenOver` (done by itself when the server goes idle, `BridgeConfig.reclaimWhenIdle`) |
| `canUplinkNow()` | Connected, server in a live session this client controls, and a credit free. Check before JPEG-encoding |
| `offerCameraFrame(jpeg, captureTimeNs, rotationDegrees, length = jpeg.size): Boolean` | Sends a KSR1 frame (the first `length` bytes, copied once) if a credit is free; otherwise drops it and returns false. Never blocks |
| `noteFrameSkippedByCaller()` | Counts a frame the caller dropped itself |
| `predictedAt(displayTimeNs): WorldSnapshot` | Live: boxes and distances moved forward from capture time (<= 300 ms) |
| `reportPlayback(videoId, pts, playing, rate)` | Sim: `client.playback` |
| `resultForPts(pts): WorldSnapshot?` | Sim: newest result with `pts - 150 ms <= resultPts <= pts` |
| `sendTripState(ClientTripState)` / `sendTripState(timestampMs, lat, lng, heading?, speed?, accuracy?)` | Live navigation (a null heading / speed is sent as 0; not sent while taken over) |
| `setNavigation(NavigationState?)` | Override the Driving Context's route input (tests, stubs); null returns to phase1 packets |
| `close()` | Closes the socket and stops the coroutines |
| `world: StateFlow<WorldSnapshot>` | Objects by track id (`ObjectState`: smoothed distance, relative speed, TTC, debounced light, box velocity), lanes, road, signs, timing, `perceptionStale` |
| `context: StateFlow<DrivingContext>` | `following` (state NORMAL/CLOSE/CRITICAL, lead track, distance, TTC, headway), `trafficLight`, `pedestriansInPath`, `laneGuidance`, `navigation`, `speedLimit`, `activeAlerts` (most urgent first), `perceptionStale` |
| `events: SharedFlow<DrivingEvent>` | Edges: `type`, `priority` (CRITICAL_SAFETY ... SOCIAL), `text` (AR label), `speech` (or null), `distanceMeters`, `trackId` |
| `navigation: StateFlow<NavigationUpdate?>` | Newest `routeState` + verbatim `packet` + `ptsSeconds`, `sequence`, `stale` |
| `navigationState: StateFlow<NavigationState?>` | What the Driving Context uses |
| `link: StateFlow<LinkStatus>` | State, `serverReady`, `role`, `takenOver`, rtt, capture-to-result p50/p95, fps, credits, drops, skips, sim lead, navigation packets and age, server navigation state, errors |
| `serverHello: StateFlow<HelloMessage?>` | Last `perception.hello` |

Useful `BridgeConfig` fields: `staleAfterMs` (500), `creditTimeoutMs` (1000), `defaultMaxInFlight` (2),
`simMaxLagSeconds` (0.15), `simStaleAfterSeconds` (0.5), `maxPredictionMs` (300), `navigationStaleAfterMs` (10000),
`inferLaneSideFromManeuver` (true), `reclaimWhenIdle` (true), `clockNs`, `dispatcher` (default `Dispatchers.Default`,
never the main thread). The sim seek rule (`PlaybackClock`) is the server's: 0.6 s x max(1, rate); rate <= 0 = paused.

Message classes (`com.ksr.copilot.perception`): `HelloMessage`, `PerceptionFrame`, `PerceptionUpdate`, `SkipMessage`,
`StatsMessage`, `PongMessage`, `ErrorMessage`, `NavigationPacketMessage`, `UnknownMessage`; `ClientHello`,
`ClientPlayback`, `ClientPing`, `ClientTripState`; `UplinkHeader`; `PerceptionCodec` (lenient JSON,
`ignoreUnknownKeys`).

### App side (`com.drivingassist.glass.perception`)

| Class | Role |
|---|---|
| `PerceptionConfig` | `source` (MOCK/LIVE/SIM), `serverUrl`, `simVideoId`, `mountHeightMeters`, `navEnabled`. Launch extras (that launch only) > values saved with `ksr.persist` (validated; `savedKeys`, chip "(saved)") > BuildConfig. `isAllowedUrl` accepts `ws://` only for loopback, private LAN, link-local, 100.64/10, `localhost`, `*.local`; `wss://` anywhere |
| `PerceptionFactory` | Builds the vision and route sources and the `ViewModelProvider.Factory`; MOCK builds exactly `MockDataViewModel()` |
| `PerceptionRuntime` | Owns the one `PerceptionBridge` per session, the camera-clock conversion, focal length from Camera2, status text, host visibility (`onHostStarted` / `onHostStopped`), the GPS feeder (retried every 5 s). Interfaces `BridgeBacked`, `CameraResolutionHint` |
| `LaptopVisionSource` | LIVE: `VisionSource` + `ImageAnalysis.Analyzer`; YUV_420_888 only -> JPEG q80 (<= 960 px wide) -> `offerCameraFrame`; publishes `VisionMapper.map(predictedAt(now))` at about 30 Hz while the activity is started |
| `SimVisionSource`, `SimVideoBackground` | SIM: ExoPlayer on `<app external files>/sim/<videoId>.(mov\|mp4\|mkv)`, `reportPlayback` every 100 ms and on play/pause/seek, `resultForPts` at about 30 Hz |
| `BridgeRouteSource` | `navigation.packet.routeState` -> `RouteState` (section 6) |
| `LocationFeeder` | LIVE with navigation on: `LocationManager` -> `client.trip_state` about 1 Hz (GPS, then network, fused; never passive) |
| `BridgeStatusChip`, `PerceptionHostEffects`, `LocationPermissionRequest` | Status chip bottom-left (LIVE / SIM only); screen kept on + lifecycle to the runtime; location permission only in LIVE with navigation |
| `VisionMapper` | Section 7 |

To reach the bridge from Compose: `(viewModel.visionSource as? BridgeBacked)?.runtime?.bridge`.

## 10. HTTP endpoints and files on disk

| Endpoint | Returns |
|---|---|
| `GET /health` | `status` (`ok` / `degraded`), `mode`, `modeArg`, `acceptedModes`, `sessionId`, `source`, `controller`, `clients[]`, `stats` (a `perception.stats` body), `laneWarmupMs`, `navigation`, `navigationLastCallMs`, `videoFinished`, `sim` (`videoId`, `lookaheadSeconds`, `submitted`, `seeks`), last `errors` |
| `GET /config` | The merged engine config, engine description, mode, clip list, config path |

| File | Format |
|---|---|
| phase1 session folder (`nav/demo_sessions/<clip>/`) | `session_manifest.json` (`sessionId`, `capturedAtMs`, `videoFile`, `videoId`, `routeFile`, `tripStateFile`, `source`, optional `videoStartTimestampMs`), `route.json` (phase1 `RouteSnapshot`: `routeId`, `provider`, `origin`, `destination`, `polyline`, `geometry`, `steps`, totals), `trip_state.jsonl` (one `client.trip_state` body per line, without `type`). No video inside |
| Frames folder for `bridge-cli live` (`scripts/extract_frames.py`) | `frame_000000.jpg` ... (960x540 q80 by default) plus `meta.json` (`clip`, `sourceFps`, `sourceSize`, `fps`, `width`, `height`, `jpegQuality`, `start`, `count`, `avgKB`) |
| SIM clip on the tablet | `/sdcard/Android/data/com.drivingassist.glass/files/sim/<videoId>.mov` (or `.mp4`, `.mkv`); same file stem as on the laptop |
| Sim clips on the laptop | `perception_engine/data/bdd100k/videos/**` and `data/sim_videos/**`, plus `--video-dir DIR` |
