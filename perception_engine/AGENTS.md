# AGENTS.md: perception engine guide for AI coding agents and teammates

This is the guide for working inside `perception_engine/`, the perception side of the AI Spatial Driving Copilot
(HackGT 13 project by Long Huynh, Luong Nguyen and Gia Minh Do, on `main`). It is written for AI coding tools (Claude
Code, Cursor, Codex, GitHub Copilot) and for people joining. The repo has three parts: `perception_engine/` (this
folder: laptop models, protocol, Kotlin bridge), `../frontend/` (the Galaxy Tab S9 app) and `../spatial/` (the phase1
navigation engine). [`CLAUDE.md`](CLAUDE.md) in this folder only points here. Paths below are relative to
`perception_engine/` unless they start with `../`.

## The rules that matter most

1. **The protocol has one source of truth:** [`contracts/PROTOCOL_v2.md`](contracts/PROTOCOL_v2.md), with one JSON
   Schema per message in `contracts/schemas/` and golden samples from real runs in `contracts/samples/v2/`. A protocol
   change updates the spec, the schema, the samples, the Python producer and the Kotlin consumer in the same change.
2. **One client, one Driving Context.** The tablet app (`../frontend/`) talks to the laptop only through
   `android/perception-bridge` (`PerceptionBridge` + `WorldModel` + `DrivingContextEngine`); it renders that state and
   never re-implements the protocol, lead-vehicle rules or navigation. Its AR geometry is `ar/`, its voice rules `voice/`.
3. **No route logic in the Android app or in the relay.** Maneuvers, progress, ETA, distances and the off-route flag
   come from the phase1 navigation engine (`../spatial/phase1/` on `main`). The app only displays its output and
   combines it with perception. Exception: spoken navigation sentences are rendered on the tablet from phase1's
   structured fields by the fixed templates of `docs/audio/AUDIO_CUE_RULES.md` section 6; the only progress estimate
   there (and in the on-screen countdown) is distance minus speed times a capped age.
4. **Never block the camera thread, and always close `ImageProxy`** in a `finally` block. Drop frames that have no
   credit; never queue them.
5. **DEMO mode and the sim paths must keep working without the tablet.** The app's DEMO mode runs with no laptop
   (its placeholder "Exit 56" route lives only in `nav/DemoDrive.kt`); `server --mode sim|video`, `nav/demo_sessions/`,
   `bridge-cli live|sim|watch` and `ws_probe` keep working. `gradlew.bat :app:assembleDebug` passes with no laptop.
6. **The tablet app (`../frontend/`, from branch `tom`) keeps its screens and look.** Keep edits focused and list every
   touched file in the PR description.
7. **Display only (plan section 38).** No output may steer, brake or accelerate, and no LLM sits in a safety or driving
   decision: alerts are deterministic rules. Wording is measurable ("Vehicle ahead: 8.4 m"), never "safe distance",
   "collision avoidance" or "autonomous". `UNKNOWN` stays unknown, `GREEN` is never permission, stale perception
   suppresses object alerts, and without a distance there is no highlight (never invent a number).
8. **Nothing large or secret in git:** no weights, videos, datasets, APKs, build folders, `outputs/`, `.env` files or
   API keys. No absolute machine paths in code or docs: use relative paths, env vars or CLI flags.
9. **Run Python from `perception_engine/`** with the project venv, and `import torch` before `onnxruntime`.
10. **Agents: do not run `git commit`, `git push`, `git reset` or branch switches unless your user asks.**

## Layout and ownership

| Path | What | Owner (folder, branch) | Edit policy |
|---|---|---|---|
| `perception/<block>/` | Blocks: `detection`, `tracking`, `depth`, `lanes`, `traffic`, `segmentation`, `openpilot` (each with `README.md` + `MODELS.md`) | Perception (`long`) | Keep README/MODELS.md numbers in sync |
| `perception/engine.py`, `perception/config_realtime.yaml` | Per-frame scheduler (serial `step`, two-lane `fast_step` / `slow_step`) and realtime defaults | Perception | |
| `perception/realtime/` | WebSocket server, two-lane pipeline, wire format, probe, bench, `nav_relay.py`, `tts_proxy.py` (ElevenLabs behind `POST /tts`); `glasses_server.py` + `glasses_wire.py` (separate port-8000 listener for a JSON-only glasses client) | Perception | |
| `contracts/` | `PROTOCOL_v2.md`, `schemas/`, `samples/v2/`, the v1 schema and samples | Shared with the app | Change both sides together |
| `android/perception-bridge/` | Pure Kotlin/JVM library (no Android APIs, package `com.drivingassist.copilot.*`): protocol v2 data classes, `PerceptionBridge`, `WorldModel`, `DrivingContextEngine`, `NavigationMapper`. Built from `../frontend/` as `:perception-bridge` (the app depends on it) | Perception | Free; Kotlin 2.0.21, JVM 17 bytecode |
| `android/bridge-cli/` | JVM fake tablet driving the same `PerceptionBridge` (`:bridge-cli`) | Perception | Free |
| `nav/` | phase1 relay (Node child process), demo sessions, sample generator | Perception (calls phase1 unchanged) | Never reimplement phase1 logic here |
| `docs/` | Overview, interfaces, runbook, navigation integration, models and licences, changelog, PR text, examples; `docs/audio/` = the agreed voice design (`AUDIO_CUE_RULES.md`, `audio_cues.v1.json`, which the APK packages) | Perception | The cue catalog is shared with the app |
| `scripts/`, `tools/`, `tests/`, `results/` | Setup and fetch scripts, data tools, tests, committed eval numbers | Perception | |
| `perception/third_party/`, `models/`, `data/`, `outputs/`, `.venv/` | Vendored upstream code, weights, datasets, outputs | Generated by scripts | Gitignored. Never edit or commit |
| `../frontend/app/src/main/java/com/drivingassist/spatialcopilot/`: `session/` (bridge session, camera uplink, sim player, GPS feeder, settings, status), `ar/` (FILL_CENTER, ground projection, ego lane, route arrows, scene), `voice/` (cue rules, arbiter, voice bus, `/tts` + Android TTS), `nav/` (`RouteGuide`, `DemoDrive`) | App side of the laptop bridge | Integration | Free; renders bridge state, no route or ML logic |
| `../frontend/` everything else (`ui/CopilotScreen.kt`, `ui/SpatialArEngine.kt`, `camera/`, `MainActivity`, manifest, Gradle files, `README.md`) | Tablet UI shell (Compose, CameraX) | Tablet app (`tom`) | Keep the screens and look; list touched files |
| `../spatial/` (`phase1/`, `scripts/`, `android-collector/`, `docs/`, `package.json`) | phase1 navigation engine and GPS collector | Navigation engine (`louis` / `phase1`, on `main`) | Read and run only from this folder |

## Contracts

| Contract | File | Checked by |
|---|---|---|
| Laptop-tablet messages (perception and navigation) | `contracts/PROTOCOL_v2.md`, `contracts/schemas/<type>.schema.json` | `tests/test_protocol_v2.py` (schemas, samples, live server loopback); `perception-bridge` `ProtocolV2Test` (decode and round-trip every sample) and `ContractFieldCoverageTest` (every sample field modelled in Kotlin) |
| Golden samples | `contracts/samples/v2/*.json`, `uplink_header.example.txt` | Both test suites above. Regenerate with `tests/make_golden_samples_v2.py` and `node nav/make_contract_samples.js` |
| AR input (`WorldSnapshot` + `DrivingContext` + `navigation.packet` -> `ArScene`) | `../frontend/app/src/main/java/com/drivingassist/spatialcopilot/ar/`, `nav/RouteGuide.kt` | app `ArGeometryTest` (ground projection equals the server's `groundXZ` on real frames; Tab S9 FILL_CENTER), `ArSceneTest`, `LaneArrowsTest`, `RouteGuideTest` |
| Voice cues and the `/tts` proxy | `docs/audio/AUDIO_CUE_RULES.md`, `docs/audio/audio_cues.v1.json` | app `VoiceRulesTest`, `VoiceLaneTest`, `VoiceNavPhrasesTest` (read the catalog JSON), `tests/test_tts_proxy.py` |
| Map speed limits (`navigation.packet.speedLimit`) | `perception/realtime/speed_limit.py`, `contracts/PROTOCOL_v2.md` | `tests/test_speed_limit.py`; bridge `SpeedLimitLatch` in `DrivingContextEngineTest` |
| phase1 session and packet | `../spatial/docs/PHASE_1_UPSTREAM_DATA_CONTRACT.md`, `../spatial/docs/PHASE_1_SPATIAL_NAVIGATION_SPEC.md` | `tests/test_nav_relay.py` |
| v1 frame (legacy) | `contracts/perception_frame.v1.schema.json`, `contracts/samples/*.json` | `SampleDecodeTest` |

Versioning: adding an optional (nullable) field is non-breaking, because both decoders ignore unknown keys. A breaking
change bumps `schemaVersion` (Python `perception/realtime/wire.py` `SCHEMA_VERSION`, the schemas' `const`, Kotlin
`PerceptionFrame.SCHEMA_VERSION`); a transport change also bumps `protocolVersion`. The camera uplink header starts
with the magic `SDC1` (`headerVersion` 1).

## Coordinate, unit and time conventions

| Quantity | Convention |
|---|---|
| Server image space | Every `bbox`, lane point and road point in `perception.frame` / `perception.update` is in pixels of the **upright analysed image**: `(0, 0)` top-left, x right, y down, size `image.width` x `image.height`. Live frames are rotated by the uplink `rotationDegrees` first, and scaled down if wider than 1280 px |
| `bbox` | `[x1, y1, x2, y2]` pixels (corners, not width/height) |
| Lane polylines | `laneBoundaries` left to right, up to 20 points each, lanes numbered `1..laneCount` from the left. Points are extrapolated and can lie outside the image (x from -0.25 W to 1.25 W): clip before drawing |
| View space (app) | Server image pixels -> view pixels with `FILL_CENTER` (cover and crop, `ar/Geometry.kt` `FillCenter`): the camera preview (LIVE) and the Media3 player (`RESIZE_MODE_ZOOM`, SIM) both draw that way, and the 16:9 analysis stream has the preview's field of view. Tab S9 landscape, 1280x720 into 2560x1600: scale 2.222, 142 px cropped on each side |
| Road plane (app) | `ar/Geometry.kt` `GroundProjector` uses the same flat-ground maths as `wire.ground_xz` (pitch from the horizon row, road horizon first), so arrows sit where the server measures the road. Device pose (ARCore) is not used: in the demo the camera films a monitor |
| CameraX buffer space | `ImageProxy` buffer before rotation; `rotationDegrees` = rotate clockwise by this to make the image upright. Only the uplink uses it (the `SDC1` header carries `rotationDegrees`) |
| Ground plane | Metres. `distanceMeters` = forward distance from the camera to the object's nearest face. `lateralMeters` and `groundXZ[0]`: + = right of the camera axis. `groundXZ[1]` = forward. Flat-ground assumption |
| Track ids | Integers, stable within one `sessionId` only. Traffic lights and signs have ids >= `staticIdOffset` (1,000,000) |
| `ptsSeconds` | video / sim: the clip's container pts (what the tablet player reports). live: `(captureTimeNs - first captureTimeNs of the session) / 1e9` |
| `captureTimeNs`, `clientTimeNs` | Tablet clock (`SystemClock.elapsedRealtimeNanos` base), echoed back so the tablet measures latency on its own clock. No clock sync is needed |
| `serverTimeMs` | Laptop Unix epoch ms. Never compare it with tablet clocks |
| `timestampMs`, `tripTimestampMs` | GPS fix time, Unix epoch ms (the phase1 trip clock) |
| `heading` | Degrees clockwise from north; 0 when unknown |

## Build, run and test

In the Python blocks, `python` means the project venv interpreter. Either call it directly
(`.venv\Scripts\python.exe` on Windows, `.venv/bin/python` on Linux) or activate the venv first
(PowerShell: `Set-ExecutionPolicy -Scope Process Bypass; .venv\Scripts\Activate.ps1`; Git Bash:
`source .venv/Scripts/activate`). On Windows never call a bare `python3`: it is the Microsoft Store alias.

### Prerequisites

| Part | Needs |
|---|---|
| `perception_engine/` | Windows 11 (Linux works for the scripts), NVIDIA driver with CUDA 13.0+, CPython 3.13 x64, Git, about 10 GB of disk. Setup: [`SETUP.md`](SETUP.md) |
| `nav/` | Node 18+ (v24 tested), no npm packages, and the navigation engine in `../spatial/` (on `main`) |
| `android/` + `../frontend/` | JDK 17+ (21 tested) as `JAVA_HOME`; Android SDK with `platforms;android-35`, `build-tools;34.0.0`, `platform-tools`; `ANDROID_HOME` or `../frontend/local.properties` with `sdk.dir=<SDK path, forward slashes>` (gitignored). Only `gradlew.bat` is committed |

### Python engine

```powershell
cd perception_engine
powershell -ExecutionPolicy Bypass -File scripts\setup_env.ps1 -WithData   # venv, torch cu130, requirements, third_party, weights, BDD samples
Set-ExecutionPolicy -Scope Process Bypass; .venv\Scripts\Activate.ps1     # this window only; or call .venv\Scripts\python.exe directly
python scripts\verify_env.py                                              # 8 environment checks
python -m perception.realtime.server --help
python -m perception.realtime.server --mode auto                          # tablet chooses live or sim
python -m perception.realtime.server --mode video --video data/bdd100k/videos/val/b1ff4656-0435391e.mov --loop
python -m perception.realtime.server --mode auto --tts-allow-lan          # also serve POST /tts to Wi-Fi clients (default: loopback / adb reverse only)
curl http://127.0.0.1:8765/tts/health                                     # ElevenLabs proxy state (the key is never returned)
python -m perception.realtime.glasses_server                              # JSON-only glasses client: ws://0.0.0.0:8000/ws (not v2)
python -m perception.realtime.glasses_probe --save outputs/glasses_probe  # fake glasses app, overlays drawn on the sent JPEGs
python -m perception.realtime.ws_probe watch --seconds 30                 # fake tablet: watcher
python -m perception.realtime.ws_probe sim --video-id b1ff4656-0435391e --seconds 30
python -m perception.realtime.ws_probe live --seconds 30 --focal-px 525 --mount-height 1.3
python scripts\netem_proxy.py --listen 127.0.0.1:8766 --target 127.0.0.1:8765 --profile wifi-busy   # then probe ws://127.0.0.1:8766/perception
python -m perception.realtime.bench_lanes --seconds 20                    # two-lane pipeline alone, no sockets
python scripts\extract_frames.py b1ff4656-0435391e --fps 15 --seconds 20 --out outputs/e2e/frames_b1ff4656   # JPEGs for bridge-cli live
```

Git Bash or Linux: `bash scripts/setup_env.sh --with-data`, then the same commands with forward slashes. Server
options: `--port`, `--max-in-flight N` (default 2), `--lookahead auto|S`, `--sim-margin 0.10`, `--video-dir DIR`,
`--set KEY=VALUE` (config override, for example `--set slow.max_hz=6`), `--no-tts`, `--tts-allow-lan`,
`--speed-limits osm` (map speed limits from OpenStreetMap; sends the car position to the Overpass endpoint, off by
default), `--speed-limit-endpoint URL`. `GET /health`
and `GET /config` report the server state. The ElevenLabs key (`ELEVENLABS_API_KEY`, `ELEVENLABS_VOICE_ID`) lives only
in the gitignored `perception_engine/.env` or the environment: ElevenLabs' terms forbid keys in a mobile app, so the
tablet calls `POST /tts` on this server (same port as the WebSocket). Env overrides for the big folders: `PERCEPTION_MODELS_DIR`, `PERCEPTION_DATA_DIR`,
`PERCEPTION_OUTPUTS_DIR`, `PERCEPTION_THIRD_PARTY_DIR`.

### Navigation relay (phase1)

```bash
cd perception_engine
source .venv/Scripts/activate                               # Git Bash on Windows; Linux: source .venv/bin/activate
python -m perception.realtime.server --mode sim  --nav-session nav/demo_sessions/b1ff4656-0435391e
python -m perception.realtime.server --mode live --nav-route nav/demo_sessions/b1ff4656-0435391e/route.json
python -m perception.realtime.server --mode auto --nav-live --nav-provider google   # the tablet searches (client.place_search -> navigation.places) and sends the destination (client.destination)
python -m perception.realtime.server --mode auto --nav-live --nav-provider google --no-tts   # same, voice from Android TTS only (ElevenLabs off)
python -m perception.realtime.server --mode auto --nav-live --nav-provider google --no-tts --speed-limits osm   # + posted limits from OpenStreetMap (navigation.packet.speedLimit)
python tests/test_speed_limit.py                           # speed-limit lookup against a local fake Overpass
node ../spatial/scripts/test-google-provider.js              # Google provider offline (fake responses)
node ../spatial/scripts/check-google-key.js "Piedmont Park, Atlanta"   # one real Geocoding + Routes call with spatial/.env
python -m perception.realtime.nav_relay --nav-session nav/demo_sessions/b1ff4656-0435391e --pts 0 5 10 20   # relay alone
node nav/make_demo_session.js                               # regenerate the demo sessions (deterministic)
node nav/make_contract_samples.js                           # regenerate navigation.packet / client.trip_state samples
```

The navigation engine is found by `--phase1-dir`, then env `PHASE1_DIR`, then `../spatial` (the layout on `main`), then
a legacy `src/phase1` layout (repo root, then `../../hackgt13-phase1` next to the repo); an explicit folder may use
either layout. No worktree is needed any more. Live routes by destination: `--nav-destination "QUERY"
[--nav-origin "QUERY"] --nav-provider mock|google`; Google needs `GOOGLE_MAPS_API_KEY` in `../spatial/.env`
(gitignored) or the environment, never committed. The relay uses `spatial/phase1/` and `spatial/scripts/load-env.js`;
Google HTTP errors are redacted (`key=REDACTED`) before they reach the log or the tablets.

### Kotlin / Android (built from ../frontend)

```powershell
cd frontend
$env:JAVA_HOME = "<path to a JDK 17+>"
.\gradlew.bat :perception-bridge:test :bridge-cli:installDist :app:assembleDebug :app:testDebugUnitTest
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb reverse tcp:8765 tcp:8765
adb shell am start -S -n com.drivingassist.spatialcopilot/.MainActivity --es perception.source live      # live | sim | demo
adb push b1ff4656-0435391e.mov /sdcard/Android/data/com.drivingassist.spatialcopilot/files/sim/           # SIM clip
..\perception_engine\android\bridge-cli\build\install\bridge-cli\bin\bridge-cli.bat --help
```

- `:perception-bridge` and `:bridge-cli` are included by `../frontend/settings.gradle.kts` with `projectDir`
  pointing at `android/perception-bridge` and `android/bridge-cli`; their build output goes to `android/*/build/`
  (gitignored). `:app` depends on `:perception-bridge`.
- Git Bash: `export JAVA_HOME=...`, then `./gradlew.bat ...` and `../perception_engine/android/bridge-cli/build/install/bridge-cli/bin/bridge-cli`.
- Linux or macOS (no `gradlew` script is committed): `java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain <tasks>`.
- App settings: the settings dialog (tap the status chip; long-press toggles Debug), or launch extras
  `perception.source` (`live` / `sim` / `demo`), `perception.url`, `perception.video`, `perception.debug` (saved like
  dialog changes).
- bridge-cli: `live --frames DIR [--fps 15] [--loop] [--trip-states FILE]`, `sim --video-id ID [--rate R]`, `watch`;
  common `--url`, `--seconds`, `--dump-snapshot [--dump-at T]`.

### Test matrix

| Suite | Command (from) | Needs | Expected |
|---|---|---|---|
| Protocol, offline | `python tests/test_protocol_v2.py --offline` (`perception_engine/`) | venv | 7/7 pass, about 8 s |
| Protocol, full | `python tests/test_protocol_v2.py` | venv, GPU, weights, `data/` clip | 11/11 pass; starts its own `--mode auto` server on a free port with a fake nav relay |
| Glasses listener | `python tests/test_glasses_server.py [--offline]` | venv; full run: GPU, weights, `data/` clip | offline 5/5, full 10/10; starts its own glasses server on a free port |
| Ego path | `python tests/test_ego_path.py` | venv | 3/3 pass, CPU |
| Nav relay | `python tests/test_nav_relay.py` (optional `--phase1-dir DIR`) | venv, Node, `../spatial/` or `PHASE1_DIR` | 21/21 pass, about 10 s |
| TTS proxy | `python tests/test_tts_proxy.py` | venv (offline, fake ElevenLabs) | 9/9 pass |
| Kotlin bridge | `gradlew.bat :perception-bridge:test` (`../frontend/`) | JDK | 117 pass, 0 skipped (fails fast if `contracts/samples/v2` is missing) |
| App unit tests | `gradlew.bat :app:testDebugUnitTest` (`../frontend/`) | JDK, Android SDK | 18 pass (AR geometry vs real frames, arrows, lead highlight, voice rules, SDC1) |
| App build | `gradlew.bat :app:assembleDebug` | JDK, Android SDK | `app-debug.apk`, about 16 MB |
| Golden samples | `python tests/make_golden_samples_v2.py` | GPU, about 3 min | rewrites `contracts/samples/v2/perception.*` and `client.*` |

## Invariants and do-not rules

**App (`../frontend/`)**
- Keep `sensorLandscape`, `PreviewView.ScaleType.FILL_CENTER` (and `RESIZE_MODE_ZOOM` for the SIM player), package
  `com.drivingassist.spatialcopilot`, `compileSdk 35`, AGP 8.7.3, Kotlin 2.0.21 and JVM 17. Do not pull in libraries
  built for newer Kotlin.
- The analyzer (camera thread, `STRATEGY_KEEP_ONLY_LATEST`) checks `canUplinkNow()`, encodes, calls
  `offerCameraFrame`, and returns. No network waits, model loads or locks held across I/O. `image.close()` in `finally`.
- ExoPlayer and LocationManager are touched on the main thread only. Bridge and voice coroutines run on
  `Dispatchers.Default`; the AR canvas redraws every display frame from `predictedAt(now)` (LIVE) or
  `resultForPts(player position)` (SIM).
- Clean view: road arrows, the lead vehicle only in CLOSE / TOO CLOSE with its measured distance, one alert pill and
  degraded-state banners. Every box, lane line, fps and link detail is Debug only.
- Draw nothing stale: when `perceptionStale` is true there is no highlight and no arrow; the banner says so.
- Voice never gates visuals. The ElevenLabs key never goes into the APK, `BuildConfig` or git: the app calls the
  laptop's `/tts`, then Android TTS, then earcons.
- LIVE needs `OUTPUT_IMAGE_FORMAT_YUV_420_888` (the JPEG encoder takes YUV only).

**Protocol and server**
- Every uplinked `SDC1` frame (24 bytes or more) gets exactly one wave-1 answer: a `perception.frame` with a matching
  `echo.frameId`, or a `perception.skip`. Clients never exceed `uplink.maxInFlight`.
- Clients reset per-session state (tracks, sim buffer, lanes) on every new `sessionId`; credits come back through the
  server's answers to the frames in flight, so they are reset only when the socket is lost.
- Only the controller (`perception.hello.role`) uplinks, reports playback and feeds live navigation. A watcher ignores
  live / sim results (they are another client's stream). The Kotlin bridge does this (`LinkStatus.takenOver`) and
  re-sends its hello when the server goes idle.
- The server's asyncio loop never touches torch. Each model is owned by exactly one lane thread (fast: detector,
  trackers, light classifier; slow: depth, lanes, signs, segmentation).
- `inEgoPath` is decided on the server (`wire.py` `EgoPath`); the Kotlin Driving Context picks the lead from it.

**Perception engine**
- `import torch` before creating any onnxruntime session, or ORT silently falls back to the CPU.
- `YOLO_AUTOINSTALL=False`. Do not `pip install` ad hoc: change `requirements.txt` pins as described in `SETUP.md`
  section 8, keeping torch from the cu130 index.
- Do not edit vendored code in `perception/third_party/`; pin a new commit in `scripts/fetch_third_party.py`.
- New or changed weights go through `scripts/download_models.py` (`ITEMS`, with size and sha256) and the block's
  `MODELS.md`.

**Navigation**
- The relay (`nav/relay_core.js`) only calls phase1 exports. Bugs in route behaviour go to the navigation engine
  (`../spatial/`); do not patch around them in the app.
- Never commit a Google key, a `.env` file, or a real captured GPS session (it is location history); keep real
  sessions under the gitignored `data/`.

**Repo**
- Gitignored and never committed: `{models,data,outputs,perception/third_party,.venv,.cache}` here,
  `android/**/build/`, `*.pt`, `*.pth`, `*.onnx`, `*.safetensors`, `*.mov`, `*.mp4`, `../frontend/**/build/`,
  `*.apk`, `local.properties`, `.env`.
- BDD100K, KITTI, Cityscapes and LISA are non-commercial; derived files (masks, crops, frames) inherit that.

## Changing the protocol (checklist)

1. Edit `contracts/PROTOCOL_v2.md`.
2. Edit `contracts/schemas/<type>.schema.json`.
3. Python: `perception/realtime/wire.py` (builders) and `server.py` (behaviour); `nav/relay_core.js` for navigation.
4. Regenerate samples: `python tests/make_golden_samples_v2.py` (perception and client messages) and/or
   `node nav/make_contract_samples.js` (navigation).
5. Kotlin: `android/perception-bridge/src/main/kotlin/com/drivingassist/copilot/perception/` (`Messages.kt`,
   `ClientMessages.kt`, `PerceptionFrame.kt`). Add a key to `ContractFieldCoverageTest`'s ignore list only for pure
   server diagnostics.
6. App: `../frontend/.../ar/` or `nav/RouteGuide.kt` if the field is drawn.
7. Run `python tests/test_protocol_v2.py` and `gradlew.bat :perception-bridge:test :app:testDebugUnitTest`.
8. Breaking change: bump `schemaVersion` on both sides and note it in `docs/CHANGELOG.md`.

## Where to add features

| I want to | Edit | Also |
|---|---|---|
| Draw something new on the road | `../frontend/.../ar/ArScene.kt` (scene in view pixels), `ar/LaneArrows.kt` (painted lane arrows) and `ui/SpatialArEngine.kt` (drawing); the clean view shows only lane arrows, the arrival pin and TOO CLOSE brackets, everything else goes in Debug | `ArSceneTest`, `LaneArrowsTest` |
| Screen text (instruction, speed-limit sign, corner status) | `../frontend/.../nav/NavText.kt`, `ui/Hud.kt`, `ui/CopilotScreen.kt`, `session/StatusModel.kt` | `NavTextTest` |
| Show more perception data | the Debug layer in `ArScene.kt`, `session/StatusModel.kt` | |
| Reach the bridge from Compose | `viewModel.session.value.bridge` (null in DEMO) | `session/CopilotSession.kt` |
| Add or tune a driving alert | `android/perception-bridge/.../context/DrivingContextEngine.kt`, `DrivingContextConfig.kt`, `DrivingTypes.kt` | `DrivingContextEngineTest` |
| Spoken alerts | `../frontend/.../voice/CuePolicy.kt` (rules; numbers from `docs/audio/audio_cues.v1.json`), `VoiceArbiter.kt` | `VoiceRulesTest`, `VoiceLaneTest`, `VoiceNavPhrasesTest`; spec `docs/audio/AUDIO_CUE_RULES.md` |
| Staleness, credits, sim buffer, reconnect | `android/perception-bridge/.../bridge/BridgeConfig.kt` | `PerceptionBridgeTest`, `FlowControlTest` |
| App source / URL / defaults | `../frontend/.../session/AppSettings.kt` | |
| Swap a model or change the schedule | `perception/config_realtime.yaml`; backends in `perception/<block>/` | block `README.md` + `MODELS.md`, `download_models.py`, `docs/MODELS_AND_LICENSES.md` |
| Add a perception block | `perception/<block>/` (+ README, MODELS), `engine.py` (`fast_step` or `slow_step`, one lane owns the model), `wire.py` | schemas, samples, Kotlin |
| Server modes, sessions, credits | `perception/realtime/server.py`, `pipeline.py` | `test_protocol_v2.py`, `PROTOCOL_v2.md` |
| Lead-vehicle / in-path rule | `perception/realtime/wire.py` `EgoPath` | `tests/test_ego_path.py` |
| Navigation mapping | `nav/relay_core.js` (`toRouteState`), `NavigationMapper.kt`, `../frontend/.../nav/RouteGuide.kt` | navigation schema + samples, `test_nav_relay.py` |
| Route behaviour (maneuvers, ETA, audio text, re-routing) | `../spatial/phase1/` (navigation engine) | never in the app or relay |
| A new sim clip with navigation | `node nav/make_demo_session.js --clip <id> ...` | `make_contract_samples.js` if samples change |

## Current status, known gaps and next tasks

**Works today** (tested 2026-09-26 on the dev laptop, RTX 5060 Laptop):
- Server in video, sim, live and auto modes, protocol v2 with credits, sessions, two waves, stats, `/health`, `/tts`.
- phase1 navigation relayed in sim (by media time) and live (by `client.trip_state`), with crash recovery, loaded from
  `../spatial/` (or a legacy checkout via `PHASE1_DIR`).
- The tablet app on `PerceptionBridge`: SDC1 uplink with credits, SIM playback sync, GPS -> `client.trip_state`, route
  arrows on the road from phase1's maneuver, lead-vehicle highlight in CLOSE / TOO CLOSE, voice cues, clean vs Debug.
  The APK builds and all test suites above pass.
- End to end with the JVM fake tablet (localhost, AC power): LIVE 15 results/s, capture-to-result about 50 ms p50 /
  61 ms p95, credits returned by `echo.frameId`; SIM results 180-690 ms ahead of playback with `navigation.packet`s at
  2 Hz. Earlier captures (including on battery): `docs/examples/`, `docs/RUNBOOK.md`.

**Known gaps**
1. On the Tab S9 (SM-X710, Android 16, 2026-09-26, USB `adb reverse`): SIM plays the clip with results 130-160 ms
   ahead of playback at 20-27 fps, boxes line up with the picture (FILL_CENTER and the clip orientation are right), arrows
   and the lead highlight work, voice plays (Google TTS fallback), DEMO works, LIVE uplinks 24 fps with capture-to-result
   about 180 ms p50 / 200 ms p95 (two frames in flight on a GPU shared with two other servers; see gap 2), overlay frames
   9 ms p50. Not yet measured: thermals over a long run, a real windshield drive.
2. Live latency depends on power and GPU load: on battery or with other GPU jobs it was above the 100 ms target at
   `maxInFlight 2`. There the fast lane took about 32 ms alone but 52-60 ms while the slow lane ran in the same Python
   process (GIL/CPU contention, GPU about 50 % busy); `--max-in-flight 1` gave 74-91 ms p50 at 10-11 fps and
   `--set slow.max_hz=5` saved about 10 ms. On AC power with an idle GPU the target was met.
3. The first 1-3 s of every session are slow (sim results late, then look-ahead overshoot; live 0.6-1.1 s for the first
   frames on battery). The Driving Context shows STALE meanwhile.
4. Following-distance thresholds are not speed-aware (CRITICAL below 7 m, cleared above 9 m). The optional
   `DrivingContextConfig.criticalMinEgoSpeedMps` (app setting, off by default because the monitor demo does not move)
   holds CRITICAL at CLOSE while the route's speed says the car is stopped; the voice only speaks TOO CLOSE when closing
   or moving (catalog voiced trigger). About 8 `VEHICLE_TOO_CLOSE` events per 31 s remain on the dense city clip.
5. The `EgoPath` corridor yaw from the lanes anchors is noisy at intersections (hits the 8 degree clamp); lanes and
   camera horizons can disagree by about 30 px. The app smooths its own ego-lane fit (180 ms) but not the server's.
6. Close-range depth on truncated boxes can read low (1.1-2.2 m for parked cars beside the ego car).
7. Lanes on the city clips are weak (often 1-2 lines, confidence 0.2-0.3): arrows then follow the ego-lane anchors
   (short range, straight fit) or the camera axis. Debug shows which (`ego lane: lane_lines | anchors | camera_axis`).
8. `exitSigns` do not exist (no exit-sign detector); signs cover stop, yield, speed limit, do-not-enter and pedestrian
   crossing only.
9. Camera intrinsics: aimed at a monitor, the app sends no focal (the picture is the recording dash cam's; server
   default 700 px at 1280 wide); on a windshield mount it sends focal/sensor size (not `LENS_INTRINSIC_CALIBRATION`).
10. phase1 behaviours passed through unchanged: `ARRIVE` is announced hundreds of metres early, turn audio is upper-case,
    `START_ROUTE` never appears, no re-routing when `offRoute`, exits and merges carry no side (`turnDirection`
    `exit` / `merge`), so their arrows do not bend. The Google provider uses the Routes API (falling back to the
    legacy Directions API) and is tested offline only (fake responses, no key yet); the demo navigation sessions are
    synthetic (they do not match the video).
11. The Wi-Fi Tab S9 has no GPS receiver: indoors the fused provider gives a fix every few seconds at 25-100 m, so
    LIVE navigation shows "No new location fix" and holds the route. A real drive needs a GPS source (a phone hotspot
    with location sharing, or a Tab S9 5G). The SIM clip must be pushed into the `sim/` folder the app creates (a folder
    made by `adb shell mkdir` belongs to the shell user and the app cannot read it).
12. `perception/common/video.py` `VideoFileInput` stamps each frame with the previous frame's pts (offline tools only;
    the server uses its own `ClipReader`).
13. Sim over Wi-Fi uses a fixed `--sim-margin` (0.10 s); tune it on the real hotspot.
14. Licences: the realtime defaults are research/demo only (see `docs/MODELS_AND_LICENSES.md`).
15. Voice implements the catalog's core only: vehicle too close, pedestrian, red light, road alerts paused / back,
    nav start / continue ("Drive straight for ...") / prepare / immediate / arrive (street names are not spoken; exit
    numbers are), and the lane-change cue ("Move to the right lane for the exit, check for cars.", or "Move right ..."
    for a middle target lane; on by default, confident lanes only, at most 3 prompts per event key). Not yet: the voice
    pack files and `/tts/prefetch` / `/tts/pack`, re-queue after a cut, escalation repeats, voice modes, BT latency,
    speed-limit / stop-sign cues (off by default in the catalog anyway). TOO CLOSE re-arms only after NORMAL (a
    stricter rule than the spec's escalation). ElevenLabs answered `402 paid_plan_required` for the configured library
    voice on 2026-09-26: use a premade voice id or a paid plan; until then the app falls back to Android TTS.
16. The WebSocket stays open while the app is in the background (the tablet stays the laptop's controller); voice and
    GPS stop with the activity.

**Next tasks** (roughly in priority order)
1. Tab S9 bring-up: LIVE over USB and SIM with a pushed clip; measure capture-to-result, JPEG cost and thermals; check
   arrow and box alignment in Debug.
2. ElevenLabs voice id / plan, then a voice pack for the fixed phrases (`scripts/make_voice_pack.py` in the spec).
3. Start SIM playback only after the first result arrives; warmer initial look-ahead.
4. Live latency on battery or a shared GPU: process-per-lane server, or tune `slow.max_hz` and `maxInFlight` on the
   device.
5. Speed-aware following distance on the server side; smooth the ego-path yaw.
6. Record a real session (android-collector GPS + dashcam video) for SIM navigation; add a Google key locally for live
   routes (and move the provider to the Routes API).

## Environment gotchas

- The server takes about 30-60 s to load and warm up (20-54 s measured); warm-up briefly peaks at about 5 GB of VRAM.
  Keep other GPU jobs small.
- Run on AC power: on battery the laptop GPU is much slower (compare the two capture sets in `docs/examples/`).
- FastAPI with `from __future__ import annotations` needs `WebSocket` imported at module level in `server.py`,
  otherwise every WebSocket upgrade fails with HTTP 403.
- `tests/__init__.py` exists because some venvs ship an unrelated top-level `tests` package; run tests as
  `python tests/<file>.py` from `perception_engine/`.
- The onnxruntime CUDA EP pays about 22 ms per input-shape change (the light classifier runs fixed batches of 8).
- Git Bash splits `platforms;android-35` at the `;`: give `sdkmanager` a `--package_file` instead.
- `local.properties` needs forward slashes in `sdk.dir`.
- Windows Firewall asks to allow `python.exe` the first time the server listens on Wi-Fi: allow private networks.
