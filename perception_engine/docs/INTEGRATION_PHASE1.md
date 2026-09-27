# phase1 navigation engine integration

How the phase1 navigation engine plugs into the AI Spatial Driving Copilot. The engine is on `main` under `spatial/`
(merged from branches `louis` and `phase1`); the perception side (`perception_engine/`, from branch `long`, also on
`main`) calls it unchanged. Detailed reference for the relay: [`nav/README.md`](../nav/README.md). Message spec: the Navigation section
of [`contracts/PROTOCOL_v2.md`](../contracts/PROTOCOL_v2.md).

## What phase1 is

phase1 is the project's route engine, written in Node.js with no npm dependencies. On `main` it lives in one folder:

- `spatial/phase1/`: route providers (`google`, `mock`), `capture.buildRouteSnapshot` / `sampleRouteTripStates`,
  `session.loadPhase1Session`, `processor.buildSpatialNavigationPacket`.
- `spatial/scripts/`: demo, pull and process scripts; `scripts/load-env.js` reads `.env`.
- `spatial/android-collector/`: a small Android app that writes `session_manifest.json` + `trip_state.jsonl` from the
  phone GPS.
- `spatial/docs/`: `README.md` (start here) and the `PHASE_1_*.md` spec, upstream data contract, provider setup and
  process.
- `spatial/package.json`, `spatial/.gitignore`, `spatial/.env.example`.

Its output is one `SpatialNavigationPacket` per trip state: progress, active and upcoming maneuvers, spatial and audio
instructions, route semantics. Its rule, which this integration keeps: **the Android app must not own route logic.**

`spatial/scripts/*.js` and `spatial/package.json` (`"main": "phase1/index.js"`) point at `spatial/phase1/` (the old
`../src/phase1` paths from before the move are fixed). The relay does not use those scripts: it loads
`spatial/phase1/` and `spatial/scripts/load-env.js` only.

## How it plugs in

```text
 tablet (Tab S9)                               laptop
 ------------------                            ------------------------------------------------------------------
 client.playback (sim) ---+                    perception/realtime/server.py
 client.trip_state (live) +-- WebSocket ----->   NavWorker thread ---> NavRelay (perception/realtime/nav_relay.py)
 navigation.packet <------+                                                 | JSON lines over stdin/stdout
                                                                            v
                                                 node nav/phase1_relay.js --require--> spatial/phase1/* (unmodified)
```

- The perception server starts the relay only when a `--nav-*` flag is given. It runs `node nav/phase1_relay.js` as a
  child process; `nav/relay_core.js` calls phase1's own exports (`loadPhase1Session`, `buildSpatialNavigationPacket`,
  `buildRouteSnapshot`, `sampleRouteTripStates`, plus `processor.validateRoute` / `computeRouteProgress` and
  `session.getLatestTripState`). Nothing reimplements phase1.
- The tablet needs **one** WebSocket: navigation packets travel on the perception socket.
- Navigation is optional. Without Node or phase1 the server still runs; its hello says
  `navigation: {mode, available: false, error}` and no `navigation.packet` is sent.

### Finding phase1

`resolve_phase1_dir` (Python) and `resolvePhase1` / `resolvePhase1Dir` (Node) look in this order:

1. `--phase1-dir <dir>` (server flag, relay flag, or `NavRelay(phase1_dir=...)`)
2. env `PHASE1_DIR`
3. `<repo>/spatial`, if `spatial/phase1/index.js` exists (the layout on `main`)
4. the repo root, if it has the legacy `src/phase1/index.js` (a checkout of the old `phase1` branch)
5. `../hackgt13-phase1`, a legacy checkout next to the repo

A checkout that contains `main` needs no flag and no extra worktree. An explicit value (1 or 2) may use either layout
(a `spatial/` folder, a repo root that holds `spatial/`, or a legacy checkout with `src/phase1/`); a value that is
neither is an error, not a silent fallback, and the message lists the paths tried. The engine folder's `.env` and
`scripts/load-env.js` are used from the same folder in both layouts.

### Sim: navigation on the clip timeline

```powershell
.venv\Scripts\python.exe -m perception.realtime.server --mode sim --nav-session nav/demo_sessions/b1ff4656-0435391e
```

- A session folder has phase1's layout: `session_manifest.json`, `route.json`, `trip_state.jsonl`. The clip itself is
  not in the folder: the manifest names it (`videoFile`, `videoId`).
- About every 0.5 s of media time (and after a seek) the server calls `relay.packet_at_pts(playback pts)`; media time
  `t` maps to trip time `trip_state[0].timestampMs + 1000 * t`, or `videoStartTimestampMs + 1000 * t` when the manifest
  sets that optional field. Seeking works because the timeline has no hidden state.
- The session is fixed for the server run. If the tablet plays another clip, restart the server with that clip's
  session folder.

Demo sessions (synthetic: a mock route around Georgia Tech timed to the clip; they do not match what the video shows):

| Clip | Scene | Route | Maneuvers |
|---|---|---|---|
| `b1ff4656-0435391e` | city | 307 m, 7.6 m/s | turn right, countdown 116 m to 1 m (t about 0.5-16 s), then arrive |
| `b1f4491b-cf446195` | highway | 985 m, 24.6 m/s | turn right, countdown 382 m to about 0 m, then arrive |
| `b23adb0d-8a7aaced` | night | 404 m, 10 m/s | turn right, countdown 156 m to about 0 m, then arrive |

Regenerate them with `node nav/make_demo_session.js` (deterministic, byte-identical output); options `--clip`,
`--duration`, `--destination`, `--origin`, `--provider mock|google`, `--speed`, `--hz`, `--video-file`, `--out`.

### Live: navigation from the tablet GPS

```powershell
.venv\Scripts\python.exe -m perception.realtime.server --mode live --nav-route nav/demo_sessions/b1ff4656-0435391e/route.json
.venv\Scripts\python.exe -m perception.realtime.server --mode live --nav-destination "Georgia Tech" --nav-provider mock
.venv\Scripts\python.exe -m perception.realtime.server --mode live --nav-destination "Georgia Tech" --nav-origin "33.7756,-84.3963" --nav-provider mock
```

- The app's `LocationFeeder` sends `client.trip_state` about once per second (same field names as a phase1
  `trip_state.jsonl` line). Each one goes to `relay.on_trip_state(body)` and is answered by one `navigation.packet`
  (`ptsSeconds` null).
- With `--nav-destination` and no origin, the route is built from the first GPS fix. After a relay restart, an already
  built route is replayed as is (no second geocode).
- Missing `heading` / `speedMps` count as 0 (as the android-collector does). A sample more than 60 s older than the
  newest one starts a new history.

### What the tablet receives

`navigation.packet = {type, schemaVersion, serverTimeMs, ptsSeconds, tripTimestampMs, routeState, packet}`. `packet` is
phase1's `SpatialNavigationPacket` unchanged; `routeState` is derived 1:1 from it:

| routeState | From the phase1 packet | Default when missing |
|---|---|---|
| `action` | `activeManeuver.type` | `GO_STRAIGHT` |
| `audio` | `audioInstructions[0].content` | `""` |
| `ui` | `spatialInstructions[0].type` | `DISTANCE_LABEL` |
| `distanceMeters` | metres from the car to the active maneuver (the number in phase1's text) | null |
| `offRoute`, `etaSeconds`, `remainingDistanceMeters` | `progress.*` | |
| `requiredLane`, `turnDirection`, `roadName` | `routeSemantics.*` | null |

On the tablet (`frontend/`), `NavigationMapper` (perception-bridge) feeds the Driving Context's lane guidance, and the
app's `RouteGuide` reads the packet for the road arrows, the maneuver card and the voice prompts. Details:
[INTERFACES.md](INTERFACES.md#6-navigationpacket-to-routeguide).

### Reliability and cost

- `NavRelay` is thread-safe and blocking; the server calls it only from its `nav-worker` thread, never from the asyncio
  loop.
- A dead child, or one that does not answer within 5 s (45 s while a Google route may be fetched), is killed and
  restarted on the next call; the last `start_*` is replayed and the call retried once. Restarts are at least 1 s
  apart. The child exits when its stdin closes, so it never outlives the server.
- Measured on the dev laptop (Node v24), idle machine: relay start 53-91 ms; `packet_at_pts` p50 0.08-0.15 ms, p95
  under 0.4 ms (with a Gradle build running in parallel: start 88-118 ms, p50 0.23-0.32 ms, p95 about 0.64 ms);
  crash -> restart + packet 55 ms; hung child -> packet after about 1.1 s with a 1 s timeout; a packet is about 2.1 KB on
  the mock route.
- `packet_at_pts` / `on_trip_state` return `None` instead of raising (rate-limited log warnings, logger
  `perception.realtime.nav_relay`, and `NavRelay.last_error` says why). The server's NavWorker counts calls without a
  packet: after 3 in a row it sets `perception.hello.navigation.available` to false with the reason in `error`,
  re-announces the hello and sends one `perception.error internal`; the next packet clears it (hello again). A single
  child crash is invisible to clients: the restarted child answers the next call.

### Google Maps key

`--nav-provider google` (and `make_demo_session.js --provider google`) need `GOOGLE_MAPS_API_KEY`, either in the
environment or in `spatial/.env` (`spatial/.env.example` shows the format; `spatial/.gitignore` excludes `.env`). The
relay loads it with phase1's own `scripts/load-env.js`, never prints it, and reports only
`googleKeyConfigured: true/false`. Never commit the key. The Google path is untested so far (no key on the dev laptop);
the default provider is `mock`.

### Real captured sessions

1. Record GPS with phase1's `spatial/android-collector` (and, for SIM playback, a dashcam video at the same time).
2. Pull it into the gitignored data folder (it is location history; never commit it), from `perception_engine/`:
   `node ../spatial/scripts/pull-android-capture.js data/nav_sessions`.
3. Add `route.json` with phase1's `scripts/process-captured-session.js <repo>/perception_engine/data/nav_sessions`, run
   from the engine folder (its scripts read `.env` from the current folder; from anywhere else they silently fall back
   to the mock provider and the route will not match the drive). Origin and destination come from
   `PHASE1_DEMO_ORIGIN` / `PHASE1_DEMO_DESTINATION`; `PHASE1_ROUTE_PROVIDER=google` forces Google. Or copy a
   `route.json` made with the same provider.
4. Put the video's file name in the manifest's `videoFile`; if the video did not start with the first GPS sample, set
   `videoStartTimestampMs` (epoch ms of video frame 0).
5. Run the server with `--nav-session data/nav_sessions/phase1/session_<ts>`.

## Merge status

- Merged: `main` holds the navigation engine under `spatial/`, `perception_engine/` from branch `long` (the Python
  engine, `contracts/`, `docs/`, `nav/` and the JVM modules in `android/`) and the tablet app `frontend/` from branch
  `tom`, which builds the JVM modules. The former repo-root README and AGENTS.md are now
  `perception_engine/docs/REPO_OVERVIEW.md` and `REPO_AGENTS.md`.
- The relay finds `spatial/` with no flags; the old sibling checkout (`../hackgt13-phase1`) is no longer
  needed. `--phase1-dir` and `PHASE1_DIR` still work, with either layout.

Check it on a checkout that contains `spatial/` (from `perception_engine/`, no phase1 flags):

```powershell
.venv\Scripts\python.exe tests\test_nav_relay.py                   # 21 tests; prints "phase1: <repo>\spatial"
.venv\Scripts\python.exe -m perception.realtime.nav_relay --nav-session nav/demo_sessions/b1ff4656-0435391e --pts 0 5 10
node nav/make_contract_samples.js                                  # golden navigation samples: expect no diff if phase1 is unchanged
.venv\Scripts\python.exe tests\test_protocol_v2.py --offline
```

## Who changes what

| Change | Where | Owner (folder, branch) |
|---|---|---|
| Maneuvers, progress, ETA, audio/spatial instruction text, providers, re-routing | `spatial/phase1/` | navigation engine (`spatial/`, from branches `louis` / `phase1`) |
| Session and trip-state file format | `spatial/docs/PHASE_1_UPSTREAM_DATA_CONTRACT.md` + `spatial/phase1/session.js` | navigation engine (tell perception: the relay and `client.trip_state` schema follow it) |
| How packets travel to the tablet, `routeState` derivation, sim time mapping | `perception_engine/nav/relay_core.js`, `perception/realtime/nav_relay.py`, `server.py` `NavWorker` | perception (`perception_engine/`, branch `long`) |
| Message format | `perception_engine/contracts/PROTOCOL_v2.md`, `contracts/schemas/navigation.packet.schema.json`, `client.trip_state.schema.json`, samples | shared (both sides together) |
| How the route is shown | `frontend/` (`nav/RouteGuide.kt`, `ar/RouteArrows.kt`, the maneuver card in `ui/CopilotScreen.kt`), `NavigationMapper` in perception-bridge | tablet app / perception |

## phase1 behaviours worth fixing (reported, passed through unchanged)

1. After the last turn, `activeManeuver` is `ARRIVE` and the audio says "You have arrived at your destination." even
   with the destination 150-590 m away (the last about 24 s of each demo clip). Use `distanceMeters` /
   `remainingDistanceMeters` to decide when to speak it until this is fixed.
2. `START_ROUTE` is never produced (the `start` maneuver maps to `GO_STRAIGHT`); `turnDirection` is `straight` for
   `ARRIVE`.
3. Turn audio is upper-case ("TURN RIGHT in 28 m."), which some TTS engines spell out.
4. No re-routing when `offRoute` becomes true.
5. With Google routes, `roadName` is the step's full instruction text, and `keep-left` / `keep-right` come out as
   left / right.
6. The mock provider always returns the same 120 m + 180 m steps whatever its geometry (the demo session generator
   rescales them to the route length).

## Tests

```powershell
.venv\Scripts\python.exe tests\test_nav_relay.py      # 21 tests, about 10 s; options: --phase1-dir DIR, -k NAME
```

They cover setup errors (no phase1, no node), both engine layouts resolved the same way by Python and Node, sim packets
at 93 positions (schema-valid, monotonic progress, pts to trip time, seek back), live from `route.json`, destination,
and destination plus origin, bad inputs, crashes between and during calls, a hung child, 8-thread concurrency, latency,
orphan checks, and that the committed golden samples match the relay output. `tests/test_protocol_v2.py` exercises the
server's `NavWorker` with a fake relay (`tests/fake_nav_relay.py`).
