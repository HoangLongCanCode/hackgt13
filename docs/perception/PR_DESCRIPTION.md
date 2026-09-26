# Perception engine, protocol v2, phase1 navigation relay and tablet bridge

<!-- Paste everything below this line into the GitHub pull request (perception-engine -> main). -->

## Summary

This PR adds the laptop half of the AI Spatial Driving Copilot by Knuckle Sandwich Robotics Inc. (KSR) and connects it
to the Glass Mode app:

- **Perception engine** (`perception_engine/`): detection, tracking, distance, lanes, traffic lights and signs on the
  laptop GPU, each block evaluated on BDD100K (plus KITTI for depth), running in a two-lane realtime engine.
- **Protocol v2** (`contracts/`): one WebSocket `ws://<host>:8765/perception` for camera uplink, perception results
  and navigation, with JSON Schemas and golden samples that both sides test against.
- **phase1 navigation** (`perception_engine/nav/`): the unmodified phase1 route engine runs as a Node child process;
  its `SpatialNavigationPacket` reaches the tablet on the same socket.
- **Tablet bridge** (`driving_assist/`): a pure Kotlin client library (`perception-bridge`), a JVM fake tablet
  (`bridge-cli`) and a one-switch `MOCK` / `LIVE` / `SIM` integration in the app. **`MOCK` stays the default and
  behaves as before.**
- **Handoff docs**: `README.md`, `AGENTS.md` (+ `CLAUDE.md`, `.github/copilot-instructions.md`), `docs/perception/`.

Everything is display-only (plan section 38): nothing steers, brakes or accelerates, and no LLM is in a safety path.

## What's included

| Area | Highlights |
|---|---|
| Perception blocks | YOLO26s-BDD detector (mAP50-95 38.5 on 250 BDD val images), BoT-SORT tracking with TTC (MOTA 55.7, 487 ID switches on 7 MOT sequences), DA3 depth fused with geometry (KITTI 2.31 m MAE / 6.8 %), TwinLiteNet+ lanes (lane IoU 34.4, drivable mIoU 92.4), Autoware lights + HMM (87 % on lit lights, red read as green 0.6 %), LISA signs + OCR (precision 0.73) |
| Realtime server | Modes video / sim / live / auto; fast lane (wave 1, about 16 Hz) and slow lane (wave 2, about 8 Hz); credits with exactly one answer per uplinked frame; sim look-ahead so results arrive before their frame is shown; sessions and controller/watcher roles; `/health`, `/config` |
| Contracts | `PROTOCOL_v2.md`, 12 schemas, 19 golden samples + the KSR1 header example |
| Navigation | `nav/phase1_relay.js` + `perception/realtime/nav_relay.py` with crash recovery; sim by media time, live by `client.trip_state`; demo sessions for the city, highway and night clips |
| Kotlin | `PerceptionBridge` (reconnect, credits, sim buffer, prediction), `WorldModel`, `DrivingContextEngine` (following distance, lights, pedestrians, signs, lane guidance), `NavigationMapper` |
| App | `LaptopVisionSource` (camera -> JPEG uplink), `SimVisionSource` (ExoPlayer), `VisionMapper` -> `VisionData`, `BridgeRouteSource` -> `RouteState`, `LocationFeeder`, status chip |
| Setup | `scripts/setup_env.ps1` / `.sh`, pinned `requirements.txt`, `download_models.py` (sha-checked weights), `fetch_third_party.py`, `fetch_bdd_samples.py` (range-extracted, CRC-checked samples) |

### Files of others touched

Tom's files: 8 in `driving_assist/` (+170 / -15 lines, additive and backward compatible; details in
`driving_assist/PERCEPTION_INTEGRATION.md` section 2), plus the root `README.md`.

- root `README.md`: Tom's one-line `# hackgt13` placeholder (first commit, UTF-16) is replaced by the project README.
- `driving_assist/app/src/main/java/com/drivingassist/glass/MockDataViewModel.kt`: `visionSource` public; optional
  `routeSource` parameter (`@JvmOverloads` kept; null = the 5 s route loop as before).
- `driving_assist/app/src/main/java/com/drivingassist/glass/MainActivity.kt`: config + factory; SIM background; in
  LIVE / SIM only, the status chip and `PerceptionHostEffects` (screen on, lifecycle, location). MOCK shows nothing new.
- `driving_assist/app/src/main/java/com/drivingassist/glass/CameraPreview.kt`: optional `targetResolution` (analysis
  size; the preview gets the same aspect ratio; null = no selector, as before) and a `DisplayListener` that keeps
  `targetRotation` right on 180-degree flips (also fixes Tom's analyzer path).
- `driving_assist/app/src/main/AndroidManifest.xml`: INTERNET, optional location, network security config.
- `driving_assist/app/build.gradle.kts`, `driving_assist/build.gradle.kts`, `driving_assist/settings.gradle.kts`: build
  fields, the two JVM modules, OkHttp, Media3.
- `driving_assist/README.md`: a short "Perception bridge" section.

Not touched: `AROverlay.kt`, `Models.kt`, `VisionSource.kt`, `PreviewCoordinates.kt`, `ENGINEER_A.md`, and nothing on
the `phase1` branch.

## How to test

Prerequisites and one-time setup are in `README.md` and `perception_engine/SETUP.md`.

```powershell
# perception_engine/  (after: powershell -ExecutionPolicy Bypass -File scripts\setup_env.ps1 -WithData)
.venv\Scripts\python.exe tests\test_protocol_v2.py --offline      # 7/7, no GPU
.venv\Scripts\python.exe tests\test_protocol_v2.py                # 11/11, starts a real server
.venv\Scripts\python.exe tests\test_ego_path.py                   # 3/3
git worktree add ../hackgt13-phase1 origin/phase1                  # from the repo root, once (navigation)
.venv\Scripts\python.exe tests\test_nav_relay.py                  # 20/20

# driving_assist/  (JAVA_HOME = JDK 17+, Android SDK with platforms;android-35 and build-tools;34.0.0)
.\gradlew.bat :perception-bridge:test :bridge-cli:installDist :app:assembleDebug :app:testDebugUnitTest   # 116 + 15 tests
```

End to end without a tablet:

```powershell
# perception_engine/
.venv\Scripts\python.exe -m perception.realtime.server --mode sim --nav-session nav/demo_sessions/b1ff4656-0435391e
# driving_assist/ (second terminal)
.\bridge-cli\build\install\bridge-cli\bin\bridge-cli.bat sim --video-id b1ff4656-0435391e --seconds 30
```

On the tablet: `adb install -r app\build\outputs\apk\debug\app-debug.apk`, `adb reverse tcp:8765 tcp:8765`, then
`adb shell am start -S -n com.drivingassist.glass/.MainActivity --es ksr.source live` (or `sim` after pushing the clip;
`mock` needs no laptop). Full procedure: `docs/perception/RUNBOOK.md`.

## Results

Measured 2026-09-26 on the dev laptop (RTX 5060 Laptop), city clip `b1ff4656-0435391e`, localhost (USB-equivalent).
Numbers in parentheses are the end-to-end runs on battery power (about 20-30 % slower). All logs:
`docs/perception/examples/`.

| Run | Rate | Latency |
|---|---|---|
| SIM | 16.2 (12.5) results/s | results 91 (190) ms before their frame is shown; every displayed frame covered from 5 s on |
| LIVE, 15-30 fps uplink, maxInFlight 2 | 16.7 (13) results/s | capture -> result 106 / 141 (114 / 162) ms p50 / p95 |
| LIVE, emulated busy Wi-Fi | 14.5 (12) results/s | 119 / 192 (122 / 180) ms |
| Navigation | about 2 packets/s in SIM; 1 per trip state in LIVE | relay round trip about 0.1 ms |

SIM with phase1 navigation (excerpt from `docs/perception/examples/sim_nav_console.txt`; the fake tablet runs the app's `PerceptionBridge`):

```text
---- SIM pts=15.17s playing x1.0 | result pts 15.15 (21 ms behind playback) | results arrived early by p50 384 ms (min 232), late 2 | buffered 32
     [CONNECTED] res 13.0 fps (wave2 7.0/s) | rtt 1.6 ms | nav packets 30
  lane   1/1 (conf 0.18, age 0.17s, 1 lines)   road coverage 0.14
  lead   #2 car 12.3 m (age 0.17 s)  ttc --  rel 0.3 m/s  -> CLOSE
  light  #1000063 GREEN 24 m   pedestrians in path: #55 11 m
  route  TURN_RIGHT "TURN RIGHT in 9 m." ui=TURN_ARROW  9 m  eta 25 s  (#30, age 0.4 s)
  guide  USE RIGHT LANE | TURN RIGHT | MOCK STREET 10 m  [IMMEDIATE_NAVIGATION]
  alert  PEDESTRIAN 11 m  (PEDESTRIAN | 11 m)  [CRITICAL_SAFETY]
  EVENT  [CRITICAL_SAFETY] PEDESTRIAN_IN_PATH  "PEDESTRIAN | 12 m"  voice: "Pedestrian ahead."  @14.75s
```

LIVE with JPEG uplink and GPS replay (excerpt from `docs/perception/examples/live_nav_console.txt`):

```text
---- LIVE t=13.5s up 14.5 fps (sent 165/206) | cap->res p50 105 ms p95 163 | credits 1/2 drop(no credit) 39 not-ready 2 skip 0 timeout 0 | last result 49 ms ago
     [CONNECTED] res 14.5 fps (wave2 7.5/s) | rtt 1.6 ms | nav packets 28, trip states sent 28
  lead   #45 car 12.6 m (age 0.21 s)  ttc 2.5 s  rel -5.1 m/s  -> CLOSE
  light  #1000047 GREEN 24 m
  route  TURN_RIGHT "TURN RIGHT in 17 m." ui=TURN_ARROW  17 m  eta 26 s  (#28, age 0.1 s)
  guide  USE RIGHT LANE | TURN RIGHT | MOCK STREET 20 m  [IMMEDIATE_NAVIGATION]
  EVENT  [CRITICAL_SAFETY] VEHICLE_TOO_CLOSE  "VEHICLE TOO CLOSE | 9.7 m"  voice: "Vehicle too close."  @13.27s
```

No screenshots yet: the app has not been run on a Tab S9.

## Risks

- **Not run on tablet hardware.** Camera uplink, ExoPlayer, GPS, on-device JPEG cost and thermals are untested on the
  Tab S9; the JVM bridge was tested end to end against the real server.
- **Live latency** is 5-15 ms over the 100 ms target at `maxInFlight 2` (both lanes share one Python process);
  `--max-in-flight 1` meets it at about 10 fps.
- **Start-up transient**: the first 1-3 s of every session are slow; the Driving Context shows STALE meanwhile.
- **Licences**: several defaults are research/demo only (BDD100K, Waymo, LISA terms; Ultralytics AGPL-3.0). Fine for
  the hackathon, not for a product: `docs/perception/MODELS_AND_LICENSES.md`.
- **Tom's files**: 8 files in `driving_assist/` changed (additive) and the root `README.md` placeholder was replaced.
  Please review against the `tom` branch before merging. `PreviewCoordinates.rotateNormalized` (not changed) looks like
  it has its 90/270 cases swapped; Tom to confirm in DEBUG.
- **Navigation data is synthetic** in the demo sessions (mock route timed to the clip; it does not match the video).
  phase1 announces `ARRIVE` early and has no re-routing (reported to the phase1 owners).
- **SIM orientation on the device** is unverified: the BDD clips carry a -90 degree display matrix that ExoPlayer must
  apply (check once in DEBUG; `RUNBOOK.md` troubleshooting).

## Review fixes

Three adversarial reviews (protocol/server, Android app, docs/hygiene) were addressed before this PR; the details are
in `CHANGELOG_perception-engine.md` ("Review fixes"). In short: the Kotlin bridge honours `perception.hello.role`
(a taken-over tablet stops uplinking, ignores the other controller's results and takes the session back when the
server goes idle); the server starts a new session on a mid-session frame-size change, keeps a taken-over client's
intrinsics on implicit re-promotion, answers wrong-magic frames, handles a missing sim clip without an error storm,
treats `rate` 0 as paused, accepts `client.trip_state` only from the controller, reports a failing phase1 relay and
evicts peers that stop reading; the app no longer makes launch extras sticky, keeps the screen on, follows 180-degree
flips, shows no chip in MOCK and stops its loops in the background; and the committed results carry no machine paths.

## Before merging

- Check that nothing gitignored is staged (weights, data, outputs, `third_party`, build folders, APKs,
  `local.properties`). The results files record weights / dataset / outputs as `<models>/...`, `<data>/...`,
  `<outputs>/...` (the eval writers do that now), so no machine paths are committed.
- The repo has no root `.gitignore` yet (phase1 brings one); do not commit a root-level `.env`.

## Follow-ups

1. Tab S9 bring-up (LIVE over USB, SIM with a pushed clip), overlay alignment in DEBUG, latency and thermals on device.
2. HUD work: light icons, alert emphasis, lane guidance, TTS from `bridge.events`, stale banner
   (`driving_assist/PERCEPTION_INTEGRATION.md` section 11).
3. Start SIM playback after the first result; warmer initial look-ahead.
4. Process-per-lane server (or tuned `slow.max_hz` / `maxInFlight`) for live latency.
5. Speed-aware following distance; smoothed ego-path yaw.
6. Merge `phase1` into `main`; the relay then finds it without flags (`docs/perception/INTEGRATION_PHASE1.md`).
7. A real recorded session (android-collector GPS + dashcam video) for SIM navigation; a local Google key for live
   routes.

Full change list: `docs/perception/CHANGELOG_perception-engine.md`. Agent and contributor guide: `AGENTS.md`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
