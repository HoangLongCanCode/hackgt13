# Perception Bridge protocol v2 (laptop GPU ⇄ Samsung tablet/phone)

Status: source of truth for the bridge. Supersedes the v1 message list in `README.md` (v1 `perception.frame` fields are kept; v2 adds fields and messages).

Target client: Samsung Galaxy Tab S9 (any Samsung Android device works the same), the Kotlin tablet app in `frontend/` (through the shared `PerceptionBridge`). The tablet does all I/O (camera, display, audio, GPS/navigation, Driving Context); the laptop (RTX 5060) only runs the models.

## Transport

- One WebSocket: `ws://<host>:8765/perception`.
- USB (preferred): `adb reverse tcp:8765 tcp:8765`, then the app connects to `ws://127.0.0.1:8765/perception`.
- Wireless: laptop and tablet on the same network (e.g. the Galaxy S25 hotspot, 5 GHz), app connects to `ws://<laptop-LAN-IP>:8765/perception`. Android needs `INTERNET` permission and cleartext allowed for that host (network security config).
- Text frames = JSON messages (below). Binary frames = camera uplink (live mode only).

## Modes

| Mode | Who has the video | What travels | Latency seen on the display |
|---|---|---|---|
| `video` | laptop plays a clip on its own clock | results only | n/a (laptop-only testing / debugging) |
| `sim` | same clip on both; tablet plays it, laptop analyses **ahead** of the tablet's playback position | playback position up, results down | ~0 (results arrive before their frame is shown) |
| `live` | tablet camera | JPEG frames up, results down | capture→result, target < 100 ms on USB |

## Client → server (JSON text)

### `client.hello` (first message after connect; required for `live`/`sim`)
```json
{
  "type": "client.hello", "protocolVersion": 2,
  "clientId": "tab-s9-01",
  "device": { "manufacturer": "samsung", "model": "SM-X710", "osVersion": "16" },
  "mode": "live",
  "camera": {
    "imageWidth": 960, "imageHeight": 540,
    "focalPx": 745.2, "principalPoint": [480.0, 270.0],
    "mountHeightMeters": 1.25, "pitchDegrees": null,
    "lensFacing": "back", "stabilization": false
  },
  "sim": null
}
```
- `camera` (live): intrinsics **at the uplinked resolution** (the app scales Camera2 `LENS_INTRINSIC_CALIBRATION`, or derives `focalPx = focalLengthMm / sensorWidthMm * imageWidth`). `mountHeightMeters` is entered once in the app. Nulls = server estimates/defaults.
- `sim`: `{ "videoId": "b1ff4656-0435391e" }` — file stem of a clip that exists on both devices.

### `client.playback` (sim mode, ~10 Hz and on every seek/pause/resume)
```json
{ "type": "client.playback", "videoId": "b1ff4656-0435391e", "ptsSeconds": 12.345, "playing": true, "rate": 1.0, "clientTimeNs": 123456789012 }
```
- `rate` <= 0 counts as paused (the position is not extrapolated). Only the session controller's reports are used; others get `perception.error notUplinkClient`.

### `client.ping` (optional, ~1 Hz) → server answers `perception.pong`
```json
{ "type": "client.ping", "clientTimeNs": 123456789012 }
```

## Client → server (binary): camera frame uplink (live mode)

24-byte little-endian header, then the JPEG bytes (baseline JPEG, upright or with `rotationDegrees` set):

| Offset | Type | Field |
|---|---|---|
| 0 | 4 bytes ASCII | magic `SDC1` |
| 4 | uint16 | headerVersion = 1 |
| 6 | uint16 | flags (0) |
| 8 | uint32 | frameId (client counter, wraps) |
| 12 | int64 | captureTimeNs (client clock: CameraX `ImageProxy.imageInfo.timestamp` / `SystemClock.elapsedRealtimeNanos()` base) |
| 20 | uint16 | rotationDegrees (0/90/180/270: rotate the JPEG clockwise by this to make it upright) |
| 22 | uint16 | reserved (0) |

Python: `struct.Struct("<4sHHIqHH")`. Recommended: 960×540, JPEG quality ~80 (≈50–80 KB). An upright frame wider than 1280 px (`input.max_width` in `config_realtime.yaml`) is scaled down on the server, keeping its aspect, and the `client.hello` intrinsics are scaled with it; `image.width/height` and all coordinates then refer to the scaled image.

A binary message of 24 bytes or more is always answered, even with a wrong magic: the server reads `frameId` at offset 8 and answers `perception.skip badHeader` (bad magic / headerVersion / rotation) or `decodeError` (empty or undecodable JPEG). A message shorter than 24 bytes cannot be attributed to a frame: it gets only a rate-limited `perception.error badMessage`, and the client's credit comes back by its timeout.

**Flow control (credits):** the server's hello announces `uplink.maxInFlight` (default 2). The client may have at most that many frames without a wave-1 answer. Every uplinked frame gets exactly one wave-1 answer: a `perception.frame` whose `echo.frameId` matches, or a `perception.skip`. Never queue camera frames on the client — if no credit, drop the frame (CameraX `STRATEGY_KEEP_ONLY_LATEST` already does this upstream).

## Server → client (JSON text)

### `perception.hello` (once per session)
`{ "type": "perception.hello", "protocolVersion": 2, "schemaVersion": 2, "sessionId", "mode", "source", "image", "camera", "uplink": { "maxInFlight": 2, "preferredWidth": 960, "preferredHeight": 540, "jpegQuality": 80, "header": "SDC1" } | null, "sim": { "lookaheadSeconds": 0.35, "videos": ["..."] } | null, "schedule", "models" }`
- `uplink` is present whenever the server **can** take camera frames. An `--mode auto` server also sends it during a sim session, because a `client.hello` can switch it to live. So a client uplinks only while `mode == "live"`, not merely because `uplink` is non-null.
- While no session runs, `sessionId` is `"idle"` and `mode` is the server's first accepted mode (`live` for `--mode auto` or `--mode live`, `sim` for `--mode sim`).
- `role` (`controller` | `watcher`, per client): the controller is the client whose `client.hello` started the live / sim session; the results are for its camera or clip. A **watcher** must not uplink camera frames, send `client.playback` or feed navigation, and must not draw live / sim results as its own (they belong to another camera or clip; their `echo.frameId`s are the other client's counter). The Kotlin bridge stops uplinking and ignores the results while it is a watcher, shows "taken over", and re-sends its hello by itself when the server goes idle (`sessionId "idle"`: the other controller left). In `video` mode every client is a watcher and everyone uses the results. Servers without `role` are treated as "controller".
- Additive fields (optional for clients): `serverTimeMs`, `acceptedModes` (e.g. `["live", "sim"]`; `mode` itself is always `video` / `live` / `sim`), `sourceFps`, `targetHz`, `classes`, `staticIdOffset` (1000000: traffic lights and signs have ids at or above this), `server` (free-form), `safety`, and `navigation`: `{ "mode": "sim" | "live" | "off", "available": bool, "error": string | null }` (whether the phase1 relay is running). `sim` also carries `lookaheadMode` (`auto` | `fixed`) and `videoId`. `lookaheadSeconds` is in media seconds.

### `perception.frame` — wave 1 (fast: detection + tracking + light state), every analysed frame
All v1 fields (see `perception_frame.v1.schema.json`), with `schemaVersion: 2` plus:
- `"wave": 1`
- `"echo": { "frameId": 1234, "captureTimeNs": 123456789012 } | null` — copied from the uplink header (live) so the client measures latency on **its own clock**: `nowNs - captureTimeNs`.
- per object: `"distanceAgeMs"`: age of the carried-forward distance (null if none). Wave 1 includes the latest known distance/lanes/road so a client that ignores wave 2 still works.
- Coordinates (`bbox`, lanes, road, `image.width/height`) are in the **upright** image, i.e. after applying the uplink `rotationDegrees`; `client.hello.camera` intrinsics describe that upright image too.
- `lanes` and `road` are always present (null when unknown). `timingsMs` may include `queueWait`, `jpegDecode`, `fastLane`, `geometryDistance`.
- `distanceMethod`: `fused` / `depth_model` / `ground_plane` / `width_prior` (slow lane: the fusion of the depth network, flat-ground range and class size prior, or the one component that was available; carried with `distanceAgeMs`); `geometry` (fast-lane size prior + flat ground on this very frame, age 0, confidence ≤ 0.5, used when no slow-lane distance younger than 1 s exists); `size_prior` (traffic lights and signs).
- `inEgoPath` (vehicles, pedestrians): the box bottom (at 1/4, 1/2 or 3/4 of its width) lies inside the ego **vehicle's** corridor. That corridor is ±1.3 m on flat ground around the ego heading (the ego lane direction from the lanes block's `ego_lane_center_near/far` anchors, clamped to ±8°; the camera axis without them), up to 80 m ahead. The server falls back to the ego-lane / static polygon only when the camera height or horizon is unknown. (The ego-lane polygon alone included parking lanes and ended about 5 m ahead, which made parked cars "leads"; fixed 2026-09-26.) The Kotlin Driving Context picks the lead vehicle and pedestrians-in-path from this flag.
- `live`: `ptsSeconds` must advance in real time with the tablet's capture clock: `(captureTimeNs − first captureTimeNs of the session) / 1e9` (the client's relative speed / TTC / box-velocity maths runs on `ptsSeconds`).

### `perception.update` — wave 2 (slow blocks: distance, lanes, road, signs), whenever a slow block finishes
```json
{ "type": "perception.update", "schemaVersion": 2, "wave": 2,
  "seq": 4711, "frameIndex": 301, "ptsSeconds": 10.03, "echo": { "frameId": 1234, "captureTimeNs": 123 } ,
  "serverTimeMs": 1790000000000, "processingMs": 61.2,
  "distances": [ { "id": 17, "distanceMeters": 18.4, "distanceMethod": "fused", "distanceConfidence": 0.8, "lateralMeters": -0.3 } ],
  "lanes": { "currentLane": 2, "laneCount": 3, "laneBoundaries": [[[x, y], "..."]], "confidence": 0.7 },
  "road": { "drivableCoverage": 0.31, "egoPathPolygon": [[x, y]], "horizonY": 262.0, "vanishingPoint": [640, 262], "anchorPoints": [] },
  "signs": [], "blocks": ["distance", "depth", "lanes"], "timingsMs": { "distance": 58.3, "depthNet": 44.1, "lanes": 14.9, "total": 75.2 } }
```
`seq`/`frameIndex`/`ptsSeconds`/`echo` identify the frame the slow blocks analysed (usually a few frames older than the latest wave 1). Fields for blocks that did not run are omitted. Track ids match wave-1 `objects[].id`. Also carries `sessionId` and `camera`; `blocks` ⊆ `distance`, `depth`, `lanes`, `segmentation`, `signs`. `distance` is in every update while the distance block is enabled (the default: the per-track distance update runs every slow cycle); `depth` only when the depth network itself ran. `timingsMs` keys are per block (`distance`, `depthNet` for the network alone, `lanes`, `signs`, ...) plus `total`.

### `perception.skip` (live) — a frame will not be analysed (superseded by a newer one)
`{ "type": "perception.skip", "frameId": 1233, "reason": "superseded" }` — reasons: `superseded`, `decodeError`, `badHeader`, `notAccepted`, `sessionReset`; every reason returns the frame's credit. Also carries `sessionId` and `serverTimeMs`. `superseded` is also sent when a finished result is replaced in the client's 1-slot send queue. `notAccepted` is sent when the server does not accept live (plus `perception.error modeNotAvailable`), to the controller of a sim session (plus `badMessage`: send a live `client.hello` first), to a client that is not the controller (plus `notUplinkClient`), after a takeover (see Sessions), and when the fast lane failed on that frame (plus a `perception.error internal`), so the exactly-one-answer rule holds end to end.

### `perception.stats` (~1 Hz)
`{ "type": "perception.stats", "outputFps", "wave1ProcessingMs": {"p50","p95"}, "wave2ProcessingMs": {"p50","p95"}, "framesIn", "framesAnalysed", "framesSkipped", "clients" }` plus diagnostics (optional for clients): `schemaVersion`, `sessionId`, `serverTimeMs`, `mode`, `windowSeconds`, `wave2Fps`, `distanceFps`, `sourceFps`, `wave1ComputeMs` / `wave2ComputeMs` (`{p50, p95}`, compute only, without queueing), `updates`, `sendDropped`, `framesDropped`, `lookaheadSeconds`, `simLeadMs` (`{p5, p50}`, wall ms), `simLateFraction`, `uplinkClient`, `navigationPackets`, `uptimeSeconds`.

### `perception.pong`
`{ "type": "perception.pong", "clientTimeNs": 123456789012, "serverTimeMs": 1790000000000 }`

### `perception.error`
`{ "type": "perception.error", "code": "badMessage" | "modeNotAvailable" | "unknownVideo" | "notUplinkClient" | "internal", "message": "...", "fatal": false, "detail": {} | null, "serverTimeMs": 1790000000000 }` — the Kotlin bridge shows it in `LinkStatus.serverError` until a `perception.hello` of another session arrives (a hello of the same session, as sent right after a rejected `client.hello`, keeps it). `notUplinkClient` also marks the bridge as taken over (see `role`). Errors are rate-limited per client and code (about one per 5 s for a repeated mistake).

## Navigation (phase1 route engine ⇄ tablet, same socket)

The phase1 Node.js navigation engine computes `SpatialNavigationPacket`s. It is merged into `main` under `spatial/` (`spatial/phase1/`), so the relay finds it with no setup: no separate worktree or checkout is needed any more. The laptop server runs it as a child process (`perception_engine/nav/phase1_relay.js`, JSON lines over stdin/stdout) and forwards its packets on the same WebSocket, so the tablet needs one connection only. The engine folder is resolved as `--phase1-dir` > env `PHASE1_DIR` > `<repo>/spatial` > a legacy `src/phase1` layout (repo root, then `../hackgt13-phase1`); `--phase1-dir` and `PHASE1_DIR` are still supported and accept both layouts (details: `perception_engine/nav/README.md`). Route logic stays in phase1 (its rule: the Android app must not own route logic).

### `client.trip_state` (live, ~1 Hz from the device GPS; same field names as phase1 `trip_state.jsonl`)
```json
{ "type": "client.trip_state", "timestampMs": 1790000000123, "location": { "lat": 33.7756, "lng": -84.3963 }, "heading": 91.2, "speedMps": 6.1, "accuracyMeters": 4.1 }
```

### `client.destination` (live; when the user picks where to go)
```json
{ "type": "client.destination", "query": "Piedmont Park, Atlanta" }
```
A free-text place or address (1-200 characters). The server's phase1 provider (`--nav-provider mock|google`; Google =
Geocoding API + Routes API) resolves it and builds the route from the next `client.trip_state` position; until then no
packets change. `perception.hello` `navigation.destination` echoes the current target. Same sender rule as
`client.trip_state`; without live navigation (`--nav-live`, `--nav-destination` or `--nav-route`) the answer is one
`perception.error modeNotAvailable`. The Kotlin app sends it when the destination in its settings differs from the
hello's.

### `client.place_search` → `navigation.places` (live; the tablet's destination search)
```json
{ "type": "client.place_search", "requestId": "s1", "query": "coffee", "near": { "lat": 33.7756, "lng": -84.3963 } }
{ "type": "navigation.places", "schemaVersion": 2, "serverTimeMs": 1790000000500, "requestId": "s1", "query": "coffee", "provider": "google",
  "places": [ { "placeId": "ChIJ...", "label": "Foxtail Coffee - Society Atlanta", "address": "811 Peachtree St NE ...", "location": { "lat": 33.7766, "lng": -84.3838 }, "distanceMeters": 1162.0 } ],
  "error": null }
```
Free-text search for a destination (a name, a kind of place, an address), biased around `near` (the tablet's latest
GPS fix). The phase1 provider answers: Google = Places API Text Search (falling back to the Geocoding API when Places
is not enabled), mock = made-up places near `near`. The answer goes to the sender only, echoes `requestId`, lists at
most 8 places best first; on failure `places` is empty and `error` says why (never a key). At most 2 searches per
second per client are answered (the rest get `error` "rate limited"). Sent on submit, not per keystroke. Same sender
rule and `modeNotAvailable` as `client.destination`. Picking a result sends `client.destination` with its `location`
(and `placeId`), so the laptop routes to exactly that place without geocoding the label again:
```json
{ "type": "client.destination", "query": "Foxtail Coffee - Society Atlanta", "placeId": "ChIJ...", "location": { "lat": 33.7766, "lng": -84.3838 } }
```

### `navigation.packet` (server → client; sim: for the current playback position, ~2 Hz; live: after each `client.trip_state`)
```json
{ "type": "navigation.packet", "schemaVersion": 2, "serverTimeMs": 1790000000000,
  "ptsSeconds": 12.3, "tripTimestampMs": 1730000012300,
  "routeState": { "action": "TURN_RIGHT", "audio": "Turn right in 120 m.", "ui": "TURN_ARROW",
                  "distanceMeters": 120, "offRoute": false, "etaSeconds": 95, "remainingDistanceMeters": 840,
                  "requiredLane": null, "turnDirection": "right", "roadName": "North Ave" },
  "packet": { "packetType": "SPATIAL_NAVIGATION_PACKET", "...": "verbatim phase1 SpatialNavigationPacket" } }
```
- `routeState.action` = phase1 `activeManeuver.type` (`GO_STRAIGHT`, `TURN_LEFT`, `TURN_RIGHT`, `KEEP_LEFT`, `KEEP_RIGHT`, `MERGE`, `EXIT_HIGHWAY`, `ARRIVE`, `START_ROUTE`); `audio` = `audioInstructions[0].content`; `ui` = `spatialInstructions[0].type`; the tablet app reads these (through the bridge's `NavigationMapper`) into its route guide without route logic of its own. `ptsSeconds` is null in live mode.
- Sim: a phase1 session folder (`session_manifest.json`, `route.json`, `trip_state.jsonl`; e.g. `perception_engine/nav/demo_sessions/<clip>/`) provides the timeline. The clip itself is not in the folder: the manifest names it (`videoFile`, `videoId`) and the relay never opens it. Media time t maps to `trip_state[0].timestampMs + 1000·t`, or to `videoStartTimestampMs + 1000·t` when the manifest sets that optional field (for recordings where the video and the GPS log started at different times).
- When phase1 has no active maneuver, `routeState` defaults to `action: "GO_STRAIGHT"`, `audio: ""`, `ui: "DISTANCE_LABEL"`, `distanceMeters: null`. `requiredLane` is free text (`"right"`, `"2"`, `"2-3"`); clients also accept a JSON number.
- `client.trip_state`: `heading` and `speedMps` are numbers (send 0 when unknown, like the android-collector; the server also reads null as 0). Only the session controller, or a client whose `client.hello` has `navigation.mode: "live"`, may feed live navigation; others get `perception.error notUplinkClient`. A sample without a numeric `timestampMs` and `location {lat, lng}` gets a rate-limited `perception.error badMessage`. A `client.trip_state` sent while live navigation is not running gets one `perception.error modeNotAvailable`. A newly connected client immediately receives the last `navigation.packet`.
- Relay health: `perception.hello.navigation.available` turns false (with the reason in `error`) after 3 relay calls in a row produced no packet (phase1 failing); the server then re-announces the hello and sends one `perception.error internal`. The next packet sets it back to true (hello again). A crashed Node child is restarted on the next call and is not reported.
- `client.hello` may carry `"navigation": { "mode": "sim" | "live" | "off" }` as a hint; the server's CLI flags decide what is actually running (reported in `perception.hello.navigation`).

## Sessions (server behaviour the client must follow)

- Every new `sessionId` (announced by a new `perception.hello`) resets the client's per-session state: tracks, the sim pts buffer and lanes. Track ids and `ptsSeconds` are only comparable within a session.
- Credits are **not** reset on a new `sessionId`: on every session change the server answers each frame still in flight (`perception.skip sessionReset` / `superseded`, or `notAccepted` after a takeover), so every credit comes back through its answer. Clients drop credits only when the socket is lost (and on their own credit timeout).
- A new session starts on: a new controller; a video loop; a sim seek (a playback jump of more than 0.6 s × max(1, rate) against the extrapolated position; the Kotlin bridge uses the same rule to clear its sim buffer); a `client.playback` with a different `videoId` (clip switch); a change of the upright uplink frame size in the middle of a live session (the trackers restart in another coordinate space: new `sessionId`, `ptsSeconds` from 0, intrinsics re-derived; the Kotlin app also re-sends its hello when its JPEG size changes).
- The newest `client.hello` takes over the session. The previous controller becomes a watcher (`role: "watcher"` in the next hello) and receives `perception.error notUplinkClient`. A client that sends SDC1 frames without a hello while nobody controls the session becomes the controller implicitly, with the camera of its last live `client.hello` if it sent one (else no intrinsics).
- A `client.hello` or `client.playback` naming a clip the laptop does not have gets one `perception.error unknownVideo` (playback reports re-check the clip at most every 5 s, without repeating the error). If it came from the controller (or nobody controlled the server), the previous session ends: a new sim session without a source (new `sessionId`, no results) keeps that client as controller until the clip appears.
- A camera size in `client.hello` that does not match the uplinked frames gets a non-fatal `badMessage`, and the server adapts the intrinsics to the real frame size.
- A client whose socket accepts no data for 10 s (it stopped reading) is dropped by the server; if it was the controller, the session ends.

## Contract files and tests

- JSON Schemas (draft 2020-12), one per message type: `perception_engine/contracts/schemas/<type>.schema.json`. Golden samples from real server / relay runs: `perception_engine/contracts/samples/v2/` (regenerate with `perception_engine/tests/make_golden_samples_v2.py` and `perception_engine/nav/make_contract_samples.js`).
- Python: `perception_engine/tests/test_protocol_v2.py` validates every sample against its schema and runs a real server loopback. Kotlin: `perception_engine/android/perception-bridge` (built from `frontend/` as `:perception-bridge`) `ProtocolV2Test` decodes and round-trips every sample; `ContractFieldCoverageTest` fails when a sample field is not modelled in Kotlin (apart from a short list of server diagnostics) or a value changes on the Kotlin round trip.

## Client-side rules (implemented in the Kotlin `PerceptionBridge`)

- **live:** render with the newest wave-1 result; predict boxes/distances forward from `captureTimeNs` to display time (track velocity, relative speed). Optionally show the analysed frame itself with its result ("synced" view).
- **sim:** keep results in a small buffer keyed by `ptsSeconds`; each display frame uses the newest result with `ptsSeconds <= playbackPts` (within ~150 ms). Results arriving late are dropped.
- **Staleness:** if the newest result is older than 500 ms (live: by `echo.captureTimeNs` on the client clock; sim: no result within 500 ms of media time before the playback position) or the socket is down, `WorldSnapshot.perceptionStale = true`: the Driving Context suppresses object/distance alerts and keeps navigation-only guidance (plan §38).
- Reconnect with backoff; re-send `client.hello` after every reconnect.
