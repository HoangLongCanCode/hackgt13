# AI Spatial Driving Copilot

HackGT 13 project by Long Huynh, Luong Nguyen and Gia Minh Do.
Repository: [github.com/HoangLongCanCode/hackgt13](https://github.com/HoangLongCanCode/hackgt13)

A Samsung Galaxy Tab S9 on the dashboard shows the road through its camera and paints navigation on top of it: lane arrows lying flat on the pavement, the lead vehicle with a measured distance ("Vehicle ahead: 8.4 m"), traffic-light and pedestrian alerts, a maneuver card, and spoken cues. A Windows laptop with an NVIDIA GPU runs the vision models and the navigation engine, and streams both to the tablet over one WebSocket.

It is a driver-information display. Nothing steers, brakes or accelerates, and no language model makes a driving decision. `UNKNOWN` stays unknown, and a green light is never permission to go.

## What you see

- **Lane arrows on the road.** The target lane is green. In the wrong lane for an upcoming turn or exit, the car's arrow turns red and the target lane blinks.
- **Turn-by-turn navigation** from live GPS, with place search ("Where to?").
- **Following distance** on the vehicle in the car's own lane, only when a distance was measured.
- **Traffic lights, pedestrians and speed-limit signs**, plus voice cues ("Pedestrian ahead.", "Move to the right lane for the exit, check for cars.").
- **Honest degradation.** If perception is older than 500 ms, object alerts turn off and a banner says so. Navigation keeps running.

Three modes: **LIVE** (camera and GPS), **SIM** (a recorded dashcam clip, analysed just ahead of playback), and **DEMO** (a scripted scene, no laptop).

## How the parts connect

```text
Galaxy Tab S9 (frontend/)                         Laptop, Windows 11 + RTX GPU
----------------------------------------          ------------------------------------------
CameraX  -- SDC1 header + JPEG (LIVE) -------->   perception_engine/  Python, two model lanes
Media3   -- playback position (SIM) ---------->     fast: detect, track, lights
GPS      -- trip state (LIVE nav) ------------>     slow: distance, lanes, signs
                                                    nav relay -> spatial/phase1 (Node.js)
PerceptionBridge (Kotlin) <--- perception.* + navigation.packet, one WebSocket --
  WorldModel + DrivingContextEngine
  AR arrows, lead highlight, maneuver card, voice
```

USB is the preferred link (`adb reverse tcp:8765 tcp:8765`). The protocol is [`perception_engine/contracts/PROTOCOL_v2.md`](perception_engine/contracts/PROTOCOL_v2.md).

## Repository

| Folder | What | Start here |
|---|---|---|
| [`frontend/`](frontend/) | Tablet app: Kotlin, Jetpack Compose, CameraX, Media3. Package `com.drivingassist.spatialcopilot` | [`frontend/README.md`](frontend/README.md) |
| [`perception_engine/`](perception_engine/) | Perception models, WebSocket server, protocol v2, navigation relay, Kotlin bridge | [`perception_engine/README.md`](perception_engine/README.md) |
| [`spatial/`](spatial/) | Phase 1 navigation engine (Node.js) and the GPS collector | [`spatial/docs/README.md`](spatial/docs/README.md) |

Deeper docs: [overview](perception_engine/docs/OVERVIEW.md), [interfaces](perception_engine/docs/INTERFACES.md), [demo-day runbook](perception_engine/docs/RUNBOOK.md), [models and licences](perception_engine/docs/MODELS_AND_LICENSES.md), [voice cue rules](perception_engine/docs/audio/AUDIO_CUE_RULES.md).

## Run

**Laptop** (Windows 11, NVIDIA driver with CUDA 13.0+, CPython 3.13, Node 18+, about 10 GB of disk). Full setup is in [`perception_engine/SETUP.md`](perception_engine/SETUP.md).

```powershell
git clone https://github.com/HoangLongCanCode/hackgt13.git
cd hackgt13\perception_engine
powershell -ExecutionPolicy Bypass -File scripts\setup_env.ps1 -WithData
.venv\Scripts\python.exe -m perception.realtime.server --mode auto --nav-session nav/demo_sessions/b1ff4656-0435391e
```

The server takes about 30–60 s to load, then prints its URLs. Live navigation by destination: `--nav-live --nav-destination "Piedmont Park" --nav-provider mock` (or `google`, with `GOOGLE_MAPS_API_KEY` in the gitignored `spatial/.env`). ElevenLabs voice uses `ELEVENLABS_API_KEY` and `ELEVENLABS_VOICE_ID` in the gitignored `perception_engine/.env`; `--no-tts` falls back to Android TTS.

**Tablet** (JDK 17+, Android SDK `platforms;android-35` and `build-tools;34.0.0`, `sdk.dir` with forward slashes in `frontend/local.properties`):

```powershell
cd frontend
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb reverse tcp:8765 tcp:8765
adb shell am start -S -n com.drivingassist.spatialcopilot/.MainActivity --es perception.source live
```

`live`, `sim` or `demo`. DEMO needs no laptop. Tap the corner button for settings; hold it for the debug view.

## Tests

```powershell
# perception_engine/
.venv\Scripts\python.exe tests\test_protocol_v2.py --offline
.venv\Scripts\python.exe tests\test_nav_relay.py
# frontend/
.\gradlew.bat :app:testDebugUnitTest :perception-bridge:test
```

## Safety and licences

Distances, time-to-collision and light states are estimates for display. Several models and all sample data are non-commercial (BDD100K, Cityscapes, KITTI, LISA) or AGPL-3.0 (Ultralytics): fine for this demo, not for a commercial product. See [`perception_engine/docs/MODELS_AND_LICENSES.md`](perception_engine/docs/MODELS_AND_LICENSES.md). Weights, videos, datasets, APKs and API keys stay out of git.
