# AI Spatial Driving Copilot

HackGT 13 project by Long Huynh, Luong Nguyen and Gia Minh Do.

AI Spatial Driving Copilot is an augmented-reality driving assistant. A Samsung Galaxy Tab S9 on the dashboard shows
the road through its camera, or a recorded clip, and draws navigation arrows, lane guidance, vehicle markers with
distances, traffic-light state and alerts on top of it. The tablet does all input and output: camera, display, audio
and GPS. A Windows laptop with an NVIDIA RTX GPU runs the perception models (detection, tracking, distance, lanes,
traffic lights and signs) and the phase1 navigation engine, and streams the results to the tablet over one WebSocket.
Everything is display-only: nothing steers, brakes or accelerates, and no LLM is in the safety path (section 38 of the
[production plan](AI_Spatial_Driving_Copilot_Production_Plan.md)).

## Repository map

| Folder | What | Branch it comes from | Start here |
|---|---|---|---|
| [`driving_assist/`](driving_assist/) | The AR app "Glass Mode": Android, Kotlin, Jetpack Compose, CameraX. Runs on mock data by default; `LIVE` / `SIM` show the laptop's results | `tom` | [`driving_assist/README.md`](driving_assist/README.md), [`ENGINEER_A.md`](driving_assist/ENGINEER_A.md), [`PERCEPTION_INTEGRATION.md`](driving_assist/PERCEPTION_INTEGRATION.md) |
| [`spatial/`](spatial/) | The phase1 navigation engine (Node.js): route providers, trip-state processing, `SpatialNavigationPacket`; the android-collector GPS app | `louis`, `phase1` | [`spatial/docs/README.md`](spatial/docs/README.md) |
| [`perception_engine/`](perception_engine/) | The perception engine (Python) and its realtime WebSocket server, the protocol v2 contract (`contracts/`), the relay that runs the navigation engine for the tablet (`nav/`), the Kotlin bridge library and JVM fake tablet the app builds (`android/`), and its docs (`docs/`) | `long` | [`perception_engine/README.md`](perception_engine/README.md), [`perception_engine/AGENTS.md`](perception_engine/AGENTS.md) |
| [`AI_Spatial_Driving_Copilot_Production_Plan.md`](AI_Spatial_Driving_Copilot_Production_Plan.md) | Product plan; code comments cite its section numbers | | |
| [`AGENTS.md`](AGENTS.md), [`CLAUDE.md`](CLAUDE.md), [`.github/copilot-instructions.md`](.github/copilot-instructions.md) | Repo-wide rules for people and AI coding agents | | [`AGENTS.md`](AGENTS.md) |
| `leet/` | Unrelated practice file | | |

`spatial/` is on `main`; `perception_engine/` and the app's perception integration are on branch `long` until it is
merged into `main`.

## How the parts connect

```text
 Samsung Galaxy Tab S9: driving_assist/ (Android)          Laptop, Windows 11 + RTX GPU: perception_engine/ (Python)
 ------------------------------------------------          ---------------------------------------------------------
 CameraX camera --- SDC1 header + JPEG (live) ---------->  perception/realtime/server.py   ws://<host>:8765/perception
 ExoPlayer clip --- client.playback (sim) -------------->    fast lane: detect + track + light state -> perception.frame  (wave 1)
 GPS -------------- client.trip_state (live nav) ------->    slow lane: distance, lanes, road, signs  -> perception.update (wave 2)
                                                             nav worker -> node nav/phase1_relay.js -> spatial/phase1/*
 perception-bridge (Kotlin) <-- perception.* + navigation.packet (same socket) --
   WorldModel, DrivingContextEngine, NavigationMapper
 VisionMapper -> VisionData      BridgeRouteSource -> RouteState
 AROverlay (GLASS / DEBUG), status chip
```

The link is USB (`adb reverse tcp:8765 tcp:8765`, preferred) or Wi-Fi on the same network (slow in-town driving only).
The protocol is [`perception_engine/contracts/PROTOCOL_v2.md`](perception_engine/contracts/PROTOCOL_v2.md).

| App source | Background on the tablet | Vision data | Route data |
|---|---|---|---|
| `MOCK` (default) | camera preview | scripted mock | 5 s mock loop |
| `LIVE` | camera preview | laptop results for the tablet's own camera frames | navigation engine via the laptop, from the tablet GPS |
| `SIM` | the clip itself | laptop results for the same clip, analysed ahead of playback | navigation engine via the laptop, on the clip timeline |

## Quickstart

```bash
git clone https://github.com/HoangLongCanCode/hackgt13.git
cd hackgt13
git checkout long            # until it is merged into main
```

**Laptop** (Windows 11, NVIDIA driver with CUDA 13.0+, CPython 3.13, Git, Node 18+ for navigation, about 10 GB of disk;
details in [`perception_engine/SETUP.md`](perception_engine/SETUP.md)):

```powershell
cd perception_engine
powershell -ExecutionPolicy Bypass -File scripts\setup_env.ps1 -WithData
.venv\Scripts\python.exe -m perception.realtime.server --mode sim --nav-session nav/demo_sessions/b1ff4656-0435391e
```

The server needs about 30-60 s to load, then prints its URLs. The navigation engine is picked up from `spatial/`
automatically (`PHASE1_DIR` or `--phase1-dir` point elsewhere).

**Tablet** (JDK 17+, Android SDK with `platforms;android-35` and `build-tools;34.0.0`, `sdk.dir` in
`driving_assist/local.properties`, never committed):

```powershell
cd driving_assist
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb reverse tcp:8765 tcp:8765
adb shell am start -S -n com.drivingassist.glass/.MainActivity --es perception.source live     # or sim / mock
```

`MOCK` needs no laptop. Demo-day procedure: [`perception_engine/docs/RUNBOOK.md`](perception_engine/docs/RUNBOOK.md).
Every build, run and test command: [`perception_engine/AGENTS.md`](perception_engine/AGENTS.md).

## Tests

```powershell
# perception_engine/
.venv\Scripts\python.exe tests\test_protocol_v2.py        # 11 tests; starts its own server (GPU). --offline: 7 tests, no GPU
.venv\Scripts\python.exe tests\test_nav_relay.py          # 21 tests; needs Node and spatial/
.venv\Scripts\python.exe tests\test_ego_path.py           # 3 tests, CPU only
# driving_assist/
.\gradlew.bat :perception-bridge:test :app:testDebugUnitTest   # 116 + 15 JVM tests
```

## Status

The laptop server, the navigation relay, the Kotlin bridge and the app build and pass their tests, and the full chain
was run end to end against the real server with the JVM fake tablet (numbers in
[`perception_engine/docs/examples/`](perception_engine/docs/examples/)). The app has not been run on a Tab S9 yet.
Open items: [`perception_engine/AGENTS.md`](perception_engine/AGENTS.md#current-status-known-gaps-and-next-tasks).

## Safety and licences

This is a driver-information display prototype. Distances, time-to-collision and light states are estimates for
display; `UNKNOWN` means unknown, and `GREEN` is never permission to go. Several models and all sample data carry
non-commercial terms (BDD100K, Cityscapes, KITTI, LISA) or AGPL-3.0 (Ultralytics): the demo is fine, a commercial
product is not. See
[`perception_engine/docs/MODELS_AND_LICENSES.md`](perception_engine/docs/MODELS_AND_LICENSES.md). Never commit weights,
videos, datasets, APKs or secrets.
