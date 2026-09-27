# AI Spatial Driving Copilot

HackGT 13 project by Long Huynh, Luong Nguyen and Gia Minh Do. (This was the repo-root README; the repo root now holds
only `frontend/`, `perception_engine/` and `spatial/`.)

AI Spatial Driving Copilot is an augmented-reality driving assistant. A Samsung Galaxy Tab S9 on the dashboard shows
the road through its camera, or a recorded clip, and draws navigation arrows on the road, the lead vehicle with its
measured distance ("Vehicle ahead: 8.4 m"), traffic-light alerts, a maneuver card and degraded-state banners on top of
it. The tablet owns all input and output: camera, display, GPS, the Driving Context and audio (voice cues). A Windows
laptop with an NVIDIA RTX GPU runs the perception models (detection, tracking, distance, lanes, traffic lights and
signs), the relay for the phase1 navigation engine and a text-to-speech proxy, and streams the results to the tablet
over one WebSocket. Everything is display-only: nothing steers, brakes or accelerates, and no LLM is in the safety path
(section 38 of the production plan, which is not in this repo).

## Repository map

| Folder | What | Branch it comes from | Start here |
|---|---|---|---|
| [`frontend/`](../../frontend/) | The tablet app: Android Studio project (Kotlin, Jetpack Compose, CameraX, Media3), package `com.drivingassist.spatialcopilot`. Modes `LIVE` / `SIM` show the laptop's results; `DEMO` needs no laptop | `tom` | [`frontend/README.md`](../../frontend/README.md) |
| [`spatial/`](../../spatial/) | The phase1 navigation engine (Node.js): route providers, trip-state processing, `SpatialNavigationPacket`; the android-collector GPS app | `louis`, `phase1` | [`spatial/docs/README.md`](../../spatial/docs/README.md) |
| [`perception_engine/`](../) | The perception engine (Python) and its realtime WebSocket server, the protocol v2 contract (`contracts/`), the relay that runs the navigation engine for the tablet (`nav/`), the Kotlin bridge library and JVM fake tablet the app builds (`android/`), and its docs (`docs/`) | `long` | [`perception_engine/README.md`](../README.md), [`perception_engine/AGENTS.md`](../AGENTS.md) |
| [`perception_engine/docs/REPO_AGENTS.md`](REPO_AGENTS.md), [`perception_engine/CLAUDE.md`](../CLAUDE.md) | Repo-wide rules for people and AI coding agents | | [`REPO_AGENTS.md`](REPO_AGENTS.md) |

All three folders are on `main`.

## How the parts connect

```text
 Samsung Galaxy Tab S9: frontend/ (Android)                Laptop, Windows 11 + RTX GPU: perception_engine/ (Python)
 ------------------------------------------                ---------------------------------------------------------
 CameraX camera --- SDC1 header + JPEG (live) ---------->  perception/realtime/server.py   ws://<host>:8765/perception
 Media3 clip ------ client.playback (sim) -------------->    fast lane: detect + track + light state -> perception.frame  (wave 1)
 GPS -------------- client.trip_state (live nav) ------->    slow lane: distance, lanes, road, signs  -> perception.update (wave 2)
                                                             nav worker -> node nav/phase1_relay.js -> spatial/phase1/*
 perception-bridge (Kotlin) <-- perception.* + navigation.packet (same socket) --
   WorldModel, DrivingContextEngine, NavigationMapper
 AR scene: road arrows, lead vehicle, maneuver card, banners (clean / Debug view), status chip
 voice cues -> arbiter -> AudioTrack <------------- POST /tts (ElevenLabs proxy, same port)
```

The link is USB (`adb reverse tcp:8765 tcp:8765`, preferred) or Wi-Fi on the same network (slow in-town driving only).
The protocol is [`perception_engine/contracts/PROTOCOL_v2.md`](../contracts/PROTOCOL_v2.md).

| App mode | Background on the tablet | Vision data | Route data |
|---|---|---|---|
| `LIVE` (first-install default) | camera preview | laptop results for the tablet's own camera frames | navigation engine via the laptop, from the tablet GPS |
| `SIM` | the clip itself | laptop results for the same clip, analysed ahead of playback | navigation engine via the laptop, on the clip timeline |
| `DEMO` | scripted scene | scripted, no laptop | placeholder "Exit 56" route (only in DEMO) |

Voice: the Driving Context and the route feed deterministic cue rules
([`perception_engine/docs/audio/`](audio/AUDIO_CUE_RULES.md), catalog `audio_cues.v1.json` packaged into the APK), a
one-at-a-time arbiter and `AudioTrack`. Sources: ElevenLabs through the laptop proxy, else Android TextToSpeech, else
earcons. Visual alerts never depend on audio.

## Quickstart

```bash
git clone https://github.com/HoangLongCanCode/hackgt13.git
cd hackgt13
```

**Laptop** (Windows 11, NVIDIA driver with CUDA 13.0+, CPython 3.13, Git, Node 18+ for navigation, about 10 GB of disk;
details in [`perception_engine/SETUP.md`](../SETUP.md)):

```powershell
cd perception_engine
powershell -ExecutionPolicy Bypass -File scripts\setup_env.ps1 -WithData
.venv\Scripts\python.exe -m perception.realtime.server --mode auto --nav-session nav/demo_sessions/b1ff4656-0435391e
```

The server needs about 30-60 s to load, then prints its URLs. `--mode auto` accepts live and sim. The navigation engine
is picked up from `spatial/` automatically (`PHASE1_DIR` or `--phase1-dir` point elsewhere); live navigation uses
`--nav-destination "<query>" --nav-provider mock|google` instead of `--nav-session`. For ElevenLabs voice put
`ELEVENLABS_API_KEY` and `ELEVENLABS_VOICE_ID` in the gitignored `perception_engine/.env` (`--no-tts` turns it off).

**Tablet** (JDK 17+ as `JAVA_HOME`, Android SDK with `platforms;android-35` and `build-tools;34.0.0`, `sdk.dir` with
forward slashes in `frontend/local.properties`, never committed):

```powershell
cd frontend
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb reverse tcp:8765 tcp:8765
adb shell am start -S -n com.drivingassist.spatialcopilot/.MainActivity --es perception.source live     # or sim / demo
```

`DEMO` needs no laptop. Tap the status chip for settings, long-press it for the Debug view. Demo-day procedure:
[`perception_engine/docs/RUNBOOK.md`](RUNBOOK.md). Every build, run and test command:
[`perception_engine/AGENTS.md`](../AGENTS.md).

## Tests

```powershell
# perception_engine/
.venv\Scripts\python.exe tests\test_protocol_v2.py        # 11 tests; starts its own server (GPU). --offline: 7 tests, no GPU
.venv\Scripts\python.exe tests\test_nav_relay.py          # 21 tests; needs Node and spatial/
.venv\Scripts\python.exe tests\test_ego_path.py           # 3 tests, CPU only
.venv\Scripts\python.exe tests\test_tts_proxy.py          # 9 tests, offline (fake ElevenLabs)
# frontend/
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :perception-bridge:test :bridge-cli:installDist   # 18 app + 117 bridge JVM tests
```

## Status

The laptop server, the navigation relay, the Kotlin bridge and the app build and pass their tests, and the full chain
was run end to end against the real server with the JVM fake tablet (numbers in
[`perception_engine/docs/examples/`](examples/)). The app has not been run on a Tab S9 yet.
Open items: [`perception_engine/AGENTS.md`](../AGENTS.md#current-status-known-gaps-and-next-tasks).

## Safety and licences

This is a driver-information display prototype. Distances, time-to-collision and light states are estimates for
display; `UNKNOWN` means unknown, and `GREEN` is never permission to go. Several models and all sample data carry
non-commercial terms (BDD100K, Cityscapes, KITTI, LISA) or AGPL-3.0 (Ultralytics): the demo is fine, a commercial
product is not. See
[`perception_engine/docs/MODELS_AND_LICENSES.md`](MODELS_AND_LICENSES.md). Never commit weights,
videos, datasets, APKs or secrets.
