# Navigation relay (phase1 route engine → tablet)

Part of the AI Spatial Driving Copilot. The navigation engine ("phase1", Node.js, on `main` under `spatial/`) computes `SpatialNavigationPacket`s from a route and GPS trip states. This folder runs it **next to the perception server** on the laptop and forwards its packets to the tablet on the same WebSocket (`ws://<host>:8765/perception`), so the AR app needs one connection only. Route logic stays in phase1 (its rule: the Android app must not own route logic); nothing here reimplements it.

```
 tablet (Tab S9)                        laptop
 ───────────────                        ─────────────────────────────────────────────────────────────
 client.playback (sim) ──┐              perception/realtime/server.py
 client.trip_state (live)┼── WebSocket ──► worker thread ──► NavRelay (nav_relay.py)
 navigation.packet ◄─────┘                                      │ JSON lines on stdin/stdout
                                                                ▼
                                          node nav/phase1_relay.js ──require──► spatial/phase1/*    
                                          (child process; exits when the server does)  (unmodified)
```

## Files

| File | What |
|---|---|
| `phase1_relay.js` | Long-running child process: JSON-lines requests on stdin, replies on stdout, logs on stderr. |
| `relay_core.js` | Shared logic: finds phase1, loads `.env`, sim timeline, live history, `navigation.packet` envelope. Every navigation computation is a call into phase1. |
| `make_demo_session.js` | Builds phase1 session folders for the BDD sim clips → `demo_sessions/<clip>/`. |
| `make_contract_samples.js` | Regenerates `contracts/samples/v2/navigation.packet.*.json` + `client.trip_state.json` (deterministic). |
| `demo_sessions/<clip>/` | `session_manifest.json`, `route.json`, `trip_state.jsonl` (small JSON, committed; **no video inside**). |
| `../perception/realtime/nav_relay.py` | Python `NavRelay`: the server's interface to the child. |
| `../tests/test_nav_relay.py` | Tests (plain Python). |
| `../contracts/schemas/navigation.packet.schema.json`, `client.trip_state.schema.json` | JSON Schemas (2020-12) of the two navigation messages. |

## How phase1 plugs in

phase1 is **required, not copied**: the relay `require()`s `spatial/phase1` (`loadPhase1Session`, `buildSpatialNavigationPacket`, `buildRouteSnapshot`, `sampleRouteTripStates`, plus `processor.validateRoute` / `computeRouteProgress` and `session.getLatestTripState`). The navigation engine is merged into `main` under `spatial/` (from branches `louis` and `phase1`), so a checkout that contains `main` needs no setup. The engine folder is found in this order:

1. `--phase1-dir <dir>` (server flag / relay flag / `NavRelay(phase1_dir=...)`)
2. env `PHASE1_DIR`
3. `<repo>/spatial` (`spatial/phase1/index.js`, the layout on `main`)
4. the repo root, if it has the legacy `src/phase1/index.js` (a checkout of the old `phase1` branch)
5. `../hackgt13-phase1` next to the repo (a legacy checkout)

An explicit folder (1 or 2) may use either layout: a `spatial/` folder (`<dir>/phase1/index.js`), a repo root that holds `spatial/`, or a legacy checkout (`<dir>/src/phase1/index.js`). An explicit value that is neither is an error, not a silent fallback; the message lists the paths tried. Navigation is optional: without Node or the engine the server still runs and just sends no `navigation.packet`.

Only `phase1/` and `scripts/load-env.js` are loaded from the engine folder. The relay does not use the other `spatial/scripts/*.js` (they `require('../phase1')` and can be run on their own).

Requirements: Node 18+ (tested with v24; uses global `fetch` for Google), no npm packages.

## Messages (tablet side)

Source of truth: [`contracts/PROTOCOL_v2.md`](../contracts/PROTOCOL_v2.md) → *Navigation*. Schemas: [`navigation.packet.schema.json`](../contracts/schemas/navigation.packet.schema.json), [`client.trip_state.schema.json`](../contracts/schemas/client.trip_state.schema.json). Golden samples (made by the real relay): [`contracts/samples/v2/`](../contracts/samples/v2/).

`navigation.packet` (server → client):

```json
{ "type": "navigation.packet", "schemaVersion": 2, "serverTimeMs": 1790000000000,
  "ptsSeconds": 12.3, "tripTimestampMs": 1730000012300,
  "routeState": { "action": "TURN_RIGHT", "audio": "TURN RIGHT in 28 m.", "ui": "TURN_ARROW",
                  "distanceMeters": 28, "offRoute": false, "etaSeconds": 27, "remainingDistanceMeters": 208,
                  "requiredLane": null, "turnDirection": "right", "roadName": "Mock Street" },
  "packet": { "packetType": "SPATIAL_NAVIGATION_PACKET", "...": "verbatim phase1 packet" } }
```

- `routeState` is derived 1:1 from `packet`: `action` = `activeManeuver.type` (`GO_STRAIGHT` if none), `audio` = `audioInstructions[0].content` (`""` if none), `ui` = `spatialInstructions[0].type` (`DISTANCE_LABEL` if none), `distanceMeters` = metres to the active maneuver, the same number phase1 puts in its text. The tablet app (`frontend/`) reads it through the bridge's `NavigationMapper` into its `RouteGuide` (`nav/RouteGuide.kt`: maneuver, distance, spatial instruction type, road name, ETA) with no route logic of its own; `ui` ∈ `TURN_ARROW`, `LANE_ARROW`, `EXIT_MARKER`, `DISTANCE_LABEL`, `WARNING`.
- `packet` is phase1's `SpatialNavigationPacket`, unchanged. Fields phase1 leaves undefined are absent.
- **sim**: media time `t` → trip time `trip_state[0].timestampMs + 1000·t`, and the packet is built from the samples up to that time. Seeking works because the timeline has no hidden state. Optional manifest extension `videoStartTimestampMs`: when present, it replaces `trip_state[0].timestampMs` (for real sessions where the video started before or after the GPS log).
- **live**: each `client.trip_state` (the body is a phase1 `trip_state.jsonl` line plus `"type"`) is answered with one packet. `ptsSeconds` is `null`, and `tripTimestampMs` is the newest sample's timestamp. Missing `heading` / `speedMps` count as 0, as the android-collector does. A sample more than 60 s older than the newest one starts a new history (client clock reset).

## Child-process protocol (`phase1_relay.js`)

One JSON object per line. Requests are handled strictly in order:

| Request | Reply |
|---|---|
| `{"id":1,"op":"start_sim","sessionDir":"<abs path>"}` | `{"id":1,"ok":true,"info":{"sessionId","videoFile","videoId","samples","t0Ms","durationSeconds","route":{...}}}` |
| `{"id":2,"op":"start_live","routeJson":"<route.json>"}` or `"route":{...}` or `"destination":"<query>"[,"origin":"<query or lat,lng>"][,"provider":"mock"\|"google"]` | `{"id":2,"ok":true,"info":{"routeReady",...},"route":{...}}` |
| `{"id":3,"op":"at_pts","ptsSeconds":12.3}` | `{"id":3,"ok":true,"message":{navigation.packet}}` |
| `{"id":4,"op":"trip_state","sample":{client.trip_state body}}` | `{"id":4,"ok":true,"message":{...}[,"route":{...}]}` (`route` only when it was just built) |
| `{"id":5,"op":"status"}` / `{"id":6,"op":"ping"}` / `{"id":7,"op":"close"}` | `{"id":N,"ok":true,"info"\|"message":...}` |
| anything that fails | `{"id":N,"ok":false,"error":"..."}` |

At start-up the relay prints `{"id":null,"ok":true,"event":"ready","info":{"phase1Dir","phase1Layout","googleKeyConfigured",...}}` (or `"event":"fatal"` and exit code 2). It exits when its stdin closes, so it never outlives the server. `console.log` is redirected to stderr so stdout carries only the protocol. Try it by hand:

```bash
cd perception_engine
printf '%s\n' '{"id":1,"op":"start_sim","sessionDir":"nav/demo_sessions/b1ff4656-0435391e"}' '{"id":2,"op":"at_pts","ptsSeconds":12.3}' | node nav/phase1_relay.js
```

## Python API (`perception/realtime/nav_relay.py`)

```python
from perception.realtime.nav_relay import NavRelay, NavRelayError

relay = NavRelay(phase1_dir=None, node_exe="node")        # raises NavRelayError (phase1 / node missing)
relay.start_sim("nav/demo_sessions/b1ff4656-0435391e")    # raises NavRelayError on a bad session
msg = relay.packet_at_pts(12.3)                            # dict | None
relay.start_live(route_json=None, origin=None, destination="Georgia Tech", provider="mock")
msg = relay.on_trip_state(trip_state_body)                 # dict | None
relay.status(); relay.close()                              # close() is idempotent; also a context manager
```

- Thread-safe (internal lock) and **blocking**: call it from a worker thread, never from the asyncio loop. A request round trip takes ~0.1 ms (p95 < 0.4 ms).
- `start_sim` can be called again at any time, for example when a `client.hello` names a different `sim.videoId`. The demo session folders are named after the clip, and `info["videoId"]` is returned.
- `packet_at_pts` / `on_trip_state` never raise. They return `None` in the wrong mode, on bad input or on failure, with a rate-limited warning in the log.
- Crash handling: a dead child (exit, crash) or a hung child (no reply within the timeout: 5 s, or 45 s while a route may be fetched from Google) is killed and restarted on the next call. The last `start_*` is replayed and the call retried once. A live route that was already built is replayed as-is, with no second geocode or Directions call. Restarts are spaced at least 1 s apart. The child's stderr goes to the `perception.realtime.nav_relay` logger.

Server flags (handled by `server.py`, which calls the relay from its `nav-worker` thread): `--nav-session <dir>` (sim / video: one packet per ~0.5 s of media time), `--nav-route <route.json>` | `--nav-destination <query> [--nav-origin <query>] [--nav-provider mock|google]` (live: one packet per `client.trip_state`), `--phase1-dir <dir>`, `--node <node executable>`.

Quick check without the server:

```bash
cd perception_engine
python -m perception.realtime.nav_relay --nav-session nav/demo_sessions/b1ff4656-0435391e --pts 0 5 10 15 20 30 40
python -m perception.realtime.nav_relay --nav-route nav/demo_sessions/b1ff4656-0435391e/route.json --trip-state nav/demo_sessions/b1ff4656-0435391e/trip_state.jsonl
```

## Demo sessions for the sim clips

> **Synthetic.** The route comes from the phase1 route provider around Georgia Tech, and the trip is a drive along it timed to the clip. It does **not** match what the BDD100K video shows. It exists so the AR navigation overlay has something to show in `sim` mode. Real sessions come from phase1's android-collector plus a `route.json` (next section).

```bash
cd perception_engine
node nav/make_demo_session.js                         # all presets → nav/demo_sessions/<clip>/
node nav/make_demo_session.js --clip b1ff4656-0435391e --provider mock
node nav/make_demo_session.js --clip <clip> --duration 38.5 --destination "Piedmont Park" [--origin "33.7756,-84.3963"] \
     [--speed 9] [--hz 2] [--video-file <clip>.mp4] [--out <dir>] [--no-rescale]
node nav/make_contract_samples.js                     # after changing demo sessions or phase1: refresh golden samples
```

| Clip | Scene | Clip length | Mock destination | Route | Mean speed | Maneuvers over the clip |
|---|---|---|---|---|---|---|
| `b1ff4656-0435391e` | city | 40.13 s | Piedmont Park | 307 m | 7.6 m/s | turn right, countdown 116 → 1 m (t ≈ 0.5–16 s), then arrive (t ≈ 16.5–40 s) |
| `b1f4491b-cf446195` | highway | 40.08 s | Amtrak station in Atlanta | 985 m (steps rescaled from 300 m) | 24.6 m/s | turn right, countdown 382 → ~0 m (t ≈ 0.5–16 s), then arrive |
| `b23adb0d-8a7aaced` | night | 40.26 s | Colony Square | 404 m (steps rescaled from 300 m) | 10.0 m/s | turn right, countdown 156 → ~0 m (t ≈ 0.5–16 s), then arrive |

How they are made:

1. The route comes from `buildRouteSnapshot` with the same post-processing as phase1's scripts. The provider is `--provider`, else `PHASE1_ROUTE_PROVIDER`, else `google` if a key is configured, else `mock`. The committed sessions use `mock`: deterministic, no network. Mock destinations are a hash of the query string, so the destinations above were picked for a plausible route length.
2. The mock provider always returns the same 120 m + 180 m steps, whatever its geometry. When the geometry is more than 10% longer or shorter than that, the step distances are scaled to it (`--no-rescale` to keep them). Otherwise maneuvers would not line up with the car's position.
3. Trip states come from `sampleRouteTripStates` along the route, resampled by distance so the speed is constant. They are re-timed to span the clip (`--hz`, default 2), and speed and heading are recomputed from the re-timed positions. With `--speed`, a route longer than `speed × duration` is only partly driven, which is useful for long Google routes.
4. `session_manifest.json` records `videoFile` = `<clip>.mov` (the file name the tablet plays), `videoId`, `source: "simulated"` and a `demo` block with the generation parameters. The fixed start time `1730000000000` makes regenerated files byte-identical.

## Using a real captured session

1. Record GPS with phase1's `android-collector` app (writes `session_manifest.json` + `trip_state.jsonl` on the phone).
2. Pull it into a git-ignored folder (it contains your location history, so don't commit it):
   `node ../spatial/scripts/pull-android-capture.js data/nav_sessions` (from `perception_engine/`; `data/` is git-ignored).
3. Add `route.json`. Either run phase1's `scripts/process-captured-session.js data/nav_sessions` (`node ../spatial/scripts/process-captured-session.js data/nav_sessions` from `perception_engine/`), which uses the newest `session_*` folder, with origin and destination from `PHASE1_DEMO_ORIGIN` / `PHASE1_DEMO_DESTINATION` and Google if a key is set. Or copy a `route.json` made with the same provider.
4. For sim playback, record the drive video at the same time and put its file name in the manifest's `videoFile` (the collector leaves it empty). If the video did not start with the first GPS sample, set `videoStartTimestampMs` (epoch ms of video frame 0).
5. Run the server with `--nav-session data/nav_sessions/phase1/session_<ts>`, or live on the road with `--nav-destination "<place>" --nav-provider google`, where the route starts at the first GPS fix.

## Google Maps key

- Put `GOOGLE_MAPS_API_KEY=...` in `spatial/.env` (the engine folder; `spatial/.env.example` shows the format and `spatial/.gitignore` excludes `.env`) or in the environment. The relay loads it with phase1's own `scripts/load-env.js`. Values already in the environment win.
- The key is never printed or logged. The relay only reports `googleKeyConfigured: true/false`, and it never goes into this repo (`perception_engine/.gitignore` also excludes `.env`).
- `--nav-provider google` without a key fails at start with a clear error. The default provider is `mock`.

## Tests

```bash
cd perception_engine
python tests/test_nav_relay.py [--phase1-dir <dir>] [-k <name filter>]
```

The suite has 21 tests: setup errors (missing phase1 dir, missing node), both engine layouts (`spatial/` and legacy `src/phase1`) resolved the same way by Python and Node, sim packets at 93 positions (schema-valid, monotonic progress, pts → trip time, seek back), live from `route.json` / destination / destination + origin, bad inputs (no restart), crash between calls, crash during a request, hung child (timeout → restart), 8-thread concurrency, latency, close / orphan checks, and the golden samples matching the relay. Measured on the dev laptop (Windows 11, Node v24.19): `NavRelay()` start 53 ms; `packet_at_pts` p50 0.08 ms / p95 0.17 ms; `on_trip_state` p50 0.10 ms; crash → restart + restore + packet 55 ms; hung child → packet after 1.07 s (with a 1 s timeout); packet ≈ 2.1 KB on the mock route (Google routes add the polyline and more steps).

## phase1 behaviours worth knowing (not changed here)

These come from phase1 as-is and are reported to the phase1 owners. The relay forwards them unchanged:

- After the last turn, `activeManeuver` is `ARRIVE` and the audio says "You have arrived at your destination." even when the destination is still hundreds of metres away. Use `routeState.distanceMeters` / `remainingDistanceMeters` to decide when to speak it.
- Audio text is upper-case for turns ("TURN RIGHT in 28 m."). `turnDirection` is `"straight"` for `ARRIVE`. `START_ROUTE` is never produced (the `start` maneuver maps to `GO_STRAIGHT`).
- With Google routes, `roadName` is the step's full instruction text, and `keep-left` / `keep-right` maneuvers come out as `left` / `right`.
- The mock provider's steps are a fixed 300 m template (see rescaling above). There is no re-routing when `offRoute` becomes true.
