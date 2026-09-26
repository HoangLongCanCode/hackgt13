# Perception engine, protocol v2, phase1 navigation relay and tablet bridge

<!-- Paste everything below this line into the GitHub pull request (long -> main). -->

## Summary

This PR adds the laptop half of the AI Spatial Driving Copilot (HackGT 13 project by Long Huynh, Luong Nguyen and Gia
Minh Do) and connects it to the Glass Mode app. Everything the perception side owns lives in **one folder,
`perception_engine/`**:

- **Perception engine** (`perception_engine/perception/`): detection, tracking, distance, lanes, traffic lights and
  signs on the laptop GPU, each block evaluated on BDD100K (plus KITTI for depth), running in a two-lane realtime
  engine behind a WebSocket server.
- **Protocol v2** (`perception_engine/contracts/`): one WebSocket `ws://<host>:8765/perception` for camera uplink,
  perception results and navigation, with JSON Schemas and golden samples that both sides test against.
- **phase1 navigation relay** (`perception_engine/nav/`): the unmodified navigation engine from `main` (`spatial/`)
  runs as a Node child process; its `SpatialNavigationPacket` reaches the tablet on the same socket.
- **Tablet bridge**: a pure Kotlin client library (`perception_engine/android/perception-bridge`), a JVM fake tablet
  (`perception_engine/android/bridge-cli`), both built from `driving_assist/` as `:perception-bridge` / `:bridge-cli`,
  and a one-switch `MOCK` / `LIVE` / `SIM` integration in the app (`driving_assist/app/.../glass/perception/`).
  **`MOCK` stays the default and behaves as before.**
- **Docs**: `perception_engine/docs/` (overview, interfaces, runbook, navigation integration, models and licences,
  changelog, examples), `perception_engine/AGENTS.md`, and at the repo root a neutral `README.md` map, a short
  `AGENTS.md`, `CLAUDE.md` and `.github/copilot-instructions.md`.

Everything is display-only (plan section 38): nothing steers, brakes or accelerates, and no LLM is in a safety path.

## Layout after the merge

| Folder | What | Branch |
|---|---|---|
| `driving_assist/` | AR app "Glass Mode" (Kotlin, Compose, CameraX) | `tom` (+ the small edits listed below) |
| `spatial/` | phase1 navigation engine (Node.js), android-collector, its docs | `louis` / `phase1` (already on `main`) |
| `perception_engine/` | this PR: engine, contracts, docs, nav relay, JVM bridge modules | `long` |

`main` (`36fef71`) changed nothing outside `spatial/` since this branch's base (`31a22c0`), and this branch touches
nothing under `spatial/`, so no conflicts are expected.

## What's included

| Area | Highlights |
|---|---|
| Perception blocks | YOLO26s-BDD detector (mAP50-95 38.5 on 250 BDD val images), BoT-SORT tracking with TTC (MOTA 55.7, 487 ID switches on 7 MOT sequences), DA3 depth fused with geometry (KITTI 2.31 m MAE / 6.8 %), TwinLiteNet+ lanes (lane IoU 34.4, drivable mIoU 92.4), Autoware lights + HMM (87 % on lit lights, red read as green 0.6 %), LISA signs + OCR (precision 0.73) |
| Realtime server | Modes video / sim / live / auto; fast lane (wave 1) and slow lane (wave 2); credits with exactly one answer per uplinked frame; sim look-ahead so results arrive before their frame is shown; sessions and controller/watcher roles; `/health`, `/config` |
| Contracts | `PROTOCOL_v2.md`, 12 schemas, 19 golden samples + the `SDC1` uplink header example |
| Navigation | `nav/phase1_relay.js` + `perception/realtime/nav_relay.py` with crash recovery; sim by media time, live by `client.trip_state`; demo sessions for the city, highway and night clips; finds `spatial/` automatically (`--phase1-dir` / `PHASE1_DIR` still work, with the `spatial/` or the legacy `src/phase1` layout) |
| Kotlin (`com.drivingassist.copilot.*`) | `PerceptionBridge` (reconnect, credits, sim buffer, prediction), `WorldModel`, `DrivingContextEngine` (following distance, lights, pedestrians, signs, lane guidance), `NavigationMapper` |
| App | `LaptopVisionSource` (camera -> JPEG uplink), `SimVisionSource` (ExoPlayer), `VisionMapper` -> `VisionData`, `BridgeRouteSource` -> `RouteState`, `LocationFeeder`, status chip |
| Setup | `scripts/setup_env.ps1` / `.sh`, pinned `requirements.txt`, `download_models.py` (sha-checked weights), `fetch_third_party.py`, `fetch_bdd_samples.py` (range-extracted, CRC-checked samples) |

### AR-app files touched (`driving_assist/`, branch `tom`)

8 files, +174 / -15 lines against `31a22c0`, all additive and backward compatible (details in
`driving_assist/PERCEPTION_INTEGRATION.md` section 2):

- `app/src/main/java/com/drivingassist/glass/MockDataViewModel.kt`: `visionSource` public; optional `routeSource`
  parameter (`@JvmOverloads` kept; null = the 5 s route loop as before).
- `app/src/main/java/com/drivingassist/glass/MainActivity.kt`: config + factory; SIM background; in LIVE / SIM only,
  the status chip and `PerceptionHostEffects` (screen on, lifecycle, location). MOCK shows nothing new.
- `app/src/main/java/com/drivingassist/glass/CameraPreview.kt`: optional `targetResolution` (analysis size; the preview
  gets the same aspect ratio; null = no selector, as before) and a `DisplayListener` that keeps `targetRotation` right
  on 180-degree flips (also fixes the existing analyzer path).
- `app/src/main/AndroidManifest.xml`: INTERNET, optional location, network security config.
- `app/build.gradle.kts`, `build.gradle.kts`, `settings.gradle.kts`: `PERCEPTION_*` build fields, the two JVM modules
  (included from `../perception_engine/android/` with `projectDir`), OkHttp, Media3.
- `README.md`: a short "Perception bridge" section.

New files inside `driving_assist/`: `app/src/main/java/com/drivingassist/glass/perception/` (11 files), its 3 unit
tests, `app/src/main/res/xml/network_security_config.xml` and `PERCEPTION_INTEGRATION.md`. Not touched:
`AROverlay.kt`, `Models.kt`, `VisionSource.kt`, `PreviewCoordinates.kt`, `ENGINEER_A.md`, and nothing under `spatial/`.
The root `README.md` (the one-line UTF-16 `# hackgt13` placeholder from the first commit) is replaced by a repo map.

### Note on the navigation engine (not changed here)

On `main`, `spatial/scripts/phase1-demo-lib.js` and `spatial/scripts/process-captured-session.js` still
`require('../src/phase1')`, and `spatial/package.json` has `"main": "src/phase1/index.js"`, so `run-phase1-demo.js`,
`process-captured-session.js` and the npm scripts fail with "Cannot find module" since the move to `spatial/`. The relay
is not affected (it loads `spatial/phase1/` and `spatial/scripts/load-env.js` only). Worth a one-line fix on the
navigation side (`require('../phase1')`).

## How to test

Prerequisites and one-time setup are in `README.md` and `perception_engine/SETUP.md`.

```powershell
# perception_engine/  (after: powershell -ExecutionPolicy Bypass -File scripts\setup_env.ps1 -WithData)
.venv\Scripts\python.exe tests\test_protocol_v2.py --offline      # 7/7, no GPU
.venv\Scripts\python.exe tests\test_protocol_v2.py                # 11/11, starts a real server
.venv\Scripts\python.exe tests\test_ego_path.py                   # 3/3
.venv\Scripts\python.exe tests\test_nav_relay.py                  # 21/21, needs Node 18+ and spatial/ (main)

# driving_assist/  (JAVA_HOME = JDK 17+, Android SDK with platforms;android-35 and build-tools;34.0.0)
.\gradlew.bat :perception-bridge:test :bridge-cli:installDist :app:assembleDebug :app:testDebugUnitTest   # 116 + 15 tests, 0 skipped
```

End to end without a tablet:

```powershell
# perception_engine/
.venv\Scripts\python.exe -m perception.realtime.server --mode sim --nav-session nav/demo_sessions/b1ff4656-0435391e
# driving_assist/ (second terminal)
..\perception_engine\android\bridge-cli\build\install\bridge-cli\bin\bridge-cli.bat sim --video-id b1ff4656-0435391e --seconds 30
```

On the tablet: `adb install -r app\build\outputs\apk\debug\app-debug.apk`, `adb reverse tcp:8765 tcp:8765`, then
`adb shell am start -S -n com.drivingassist.glass/.MainActivity --es perception.source live` (or `sim` after pushing
the clip; `mock` needs no laptop). Full procedure: `perception_engine/docs/RUNBOOK.md`.

## Results

Measured 2026-09-26 on the dev laptop (RTX 5060 Laptop), city clip `b1ff4656-0435391e`, localhost (USB-equivalent),
with the JVM fake tablet running the app's `PerceptionBridge`. First numbers: AC power, idle GPU (latest capture); in
parentheses the earlier runs on battery power. All logs: `perception_engine/docs/examples/`.

| Run | Rate | Latency |
|---|---|---|
| SIM + nav | 20.5 (12.5) results/s | results about 150 (190) ms before their frame is shown; every displayed frame covered after start-up |
| LIVE + nav, 15 fps uplink, maxInFlight 2 | 15 (13) results/s | capture -> result 49 / 65 (114 / 162) ms p50 / p95 |
| LIVE, emulated busy Wi-Fi | 14.5 (12) results/s | 82 / 143 (122 / 180) ms |
| Navigation | about 2 packets/s in SIM; 1 per trip state in LIVE | relay round trip about 0.1-0.2 ms |

SIM with phase1 navigation (excerpt from `perception_engine/docs/examples/sim_nav_console.txt`):

```text
---- SIM pts=15.01s playing x1.0 | result pts 14.95 (61 ms behind playback) | results arrived early by p50 154 ms (min 103), late 0 | buffered 50
     [CONNECTED, controller] res 22.5 fps (wave2 13.0/s) | rtt 0.9 ms | nav packets 30
  lane   1/1 (conf 0.18, age 0.10s, 1 lines)   road coverage 0.14
  lead   #3 car 11.5 m (age 0.10 s)  ttc 13.6 s  rel -0.9 m/s  -> CLOSE
  light  #1000080 RED 18 m   pedestrians in path: #61 10 m
  route  TURN_RIGHT "TURN RIGHT in 9 m." ui=TURN_ARROW  9 m  eta 25 s  (#30, age 0.2 s)
  guide  USE RIGHT LANE | TURN RIGHT | MOCK STREET 10 m  [IMMEDIATE_NAVIGATION]
  alert  PEDESTRIAN 10 m  (PEDESTRIAN | 10 m)  [CRITICAL_SAFETY]
  EVENT  [TRAFFIC_ALERT] TRAFFIC_LIGHT_RED  "RED | 18 m"  voice: "Red light ahead."  @14.68s
```

LIVE with JPEG uplink and GPS replay (excerpt from `perception_engine/docs/examples/live_nav_console.txt`):

```text
---- LIVE t=13.9s up 15.0 fps (sent 207/212) | cap->res p50 45 ms p95 61 | credits 1/2 drop(no credit) 3 not-ready 2 skip 0 timeout 0 | last result 23 ms ago
     [CONNECTED, controller] res 15.0 fps (wave2 14.0/s) | rtt 0.8 ms | nav packets 28, trip states sent 28
  lead   #2 car 12.4 m (age 0.07 s)  ttc --  rel 0.1 m/s  -> CLOSE
  light  #1000050 GREEN 25 m
  route  TURN_RIGHT "TURN RIGHT in 17 m." ui=TURN_ARROW  17 m  eta 26 s  (#28, age 0.5 s)
  guide  USE RIGHT LANE | TURN RIGHT | MOCK STREET 20 m  [IMMEDIATE_NAVIGATION]
  EVENT  [TRAFFIC_ALERT] PEDESTRIAN_IN_PATH  "PEDESTRIAN | 15 m"  voice: "Pedestrian ahead."  @13.60s
```

No screenshots yet: the app has not been run on a Tab S9.

## Risks

- **Not run on tablet hardware.** Camera uplink, ExoPlayer, GPS, on-device JPEG cost and thermals are untested on the
  Tab S9; the JVM bridge was tested end to end against the real server.
- **Live latency depends on the laptop's power state and GPU load**: 49 ms p50 on AC power with an idle GPU, but
  5-15 ms over the 100 ms target on battery or with other GPU jobs at `maxInFlight 2` (both lanes share one Python
  process). Run the demo on AC power.
- **Start-up transient**: the first 1-3 s of every session are slow; the Driving Context shows STALE meanwhile.
- **Licences**: several defaults are research/demo only (BDD100K, Waymo, LISA terms; Ultralytics AGPL-3.0). Fine for
  the hackathon, not for a commercial product: `perception_engine/docs/MODELS_AND_LICENSES.md`.
- **AR-app files**: 8 files in `driving_assist/` changed (additive). Please review against the `tom` branch before
  merging. `PreviewCoordinates.rotateNormalized` (not changed) looks like it has its 90/270 cases swapped; to confirm
  in DEBUG on a device.
- **Navigation data is synthetic** in the demo sessions (mock route timed to the clip; it does not match the video).
  phase1 announces `ARRIVE` early and has no re-routing (reported to the navigation side).
- **SIM orientation on the device** is unverified: the BDD clips carry a -90 degree display matrix that ExoPlayer must
  apply (check once in DEBUG; `RUNBOOK.md` troubleshooting).

## Review fixes

Three adversarial reviews (protocol/server, Android app, docs/hygiene) were addressed before this PR; the details are
in `perception_engine/docs/CHANGELOG.md` ("Review fixes"). In short: the Kotlin bridge honours `perception.hello.role`
(a taken-over tablet stops uplinking, ignores the other controller's results and takes the session back when the
server goes idle); the server starts a new session on a mid-session frame-size change, keeps a taken-over client's
intrinsics on implicit re-promotion, answers wrong-magic frames, handles a missing sim clip without an error storm,
treats `rate` 0 as paused, accepts `client.trip_state` only from the controller, reports a failing phase1 relay and
evicts peers that stop reading; the app no longer makes launch extras sticky, keeps the screen on, follows 180-degree
flips, shows no chip in MOCK and stops its loops in the background; and the committed results carry no machine paths.

## Before merging

- Check that nothing gitignored is staged (weights, data, outputs, `third_party`, build folders, APKs,
  `local.properties`). The results files record weights / dataset / outputs as `<models>/...`, `<data>/...`,
  `<outputs>/...`, so no machine paths are committed.
- There is no root `.gitignore` (`perception_engine/.gitignore`, `driving_assist/.gitignore` and `spatial/.gitignore`
  cover their folders); do not commit a root-level `.env`.

## Follow-ups

1. Tab S9 bring-up (LIVE over USB, SIM with a pushed clip), overlay alignment in DEBUG, latency and thermals on device.
2. HUD work: light icons, alert emphasis, lane guidance, TTS from `bridge.events`, stale banner
   (`driving_assist/PERCEPTION_INTEGRATION.md` section 11).
3. Start SIM playback after the first result; warmer initial look-ahead.
4. Process-per-lane server (or tuned `slow.max_hz` / `maxInFlight`) for live latency on battery or a shared GPU.
5. Speed-aware following distance; smoothed ego-path yaw.
6. Navigation side: fix the `spatial/scripts` require paths and `spatial/package.json` `main` (see the note above).
7. A real recorded session (android-collector GPS + dashcam video) for SIM navigation; a local Google key for live
   routes.

Full change list: `perception_engine/docs/CHANGELOG.md`. Guides: `AGENTS.md` (repo) and `perception_engine/AGENTS.md`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
