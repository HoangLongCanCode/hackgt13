# Demo-day runbook

How to run the AI Spatial Driving Copilot demo: laptop perception server plus
phase1 navigation, Samsung Galaxy Tab S9 running the tablet app (`frontend/`). All paths are relative to the repo root. Commands
are PowerShell; in Git Bash use forward slashes and `./gradlew.bat`.

## Contents

- [Checklist](#checklist)
- [1. Laptop setup](#1-laptop-setup)
- [2. Start the server](#2-start-the-server)
- [3. Connect the tablet: USB or Wi-Fi](#3-connect-the-tablet-usb-or-wi-fi)
- [4. Build and install the app](#4-build-and-install-the-app)
- [SIM: put the clip on the tablet](#sim-put-the-clip-on-the-tablet)
- [5. Launch a mode](#5-launch-a-mode)
- [6. What to expect](#6-what-to-expect)
- [7. Rehearse without the tablet](#7-rehearse-without-the-tablet)
- [8. Troubleshooting](#8-troubleshooting)
- [9. Shut down](#9-shut-down)

## Checklist

The day before:
- [ ] Laptop set up and `scripts\verify_env.py` passes; BDD sample clips fetched (`-WithData`).
- [ ] Checkout contains `main` (navigation engine in `spatial/`); `python tests/test_nav_relay.py` passes.
- [ ] APK built from this branch and installed; the clip pushed to the tablet for SIM.
- [ ] A dry run of SIM and LIVE on the actual tablet, over USB and over the hotspot.

On site:
- [ ] Laptop on **AC power** (battery costs 20-30 % of GPU speed), Windows power mode "Best performance", NVIDIA GPU
      preferred for `python.exe`, other GPU-heavy apps closed.
- [ ] USB cable (data-capable) between tablet and laptop; USB debugging authorised on the tablet.
- [ ] Tablet mounted landscape with a clear view ahead, out of direct sun; screen brightness up.
- [ ] Server started and showing its "ready" banner before the app is launched.

## 1. Laptop setup

Fresh clone (details and troubleshooting: [`perception_engine/SETUP.md`](../SETUP.md)):

```powershell
git clone https://github.com/HoangLongCanCode/hackgt13.git
cd hackgt13
cd perception_engine
powershell -ExecutionPolicy Bypass -File scripts\setup_env.ps1 -WithData
.venv\Scripts\python.exe scripts\verify_env.py                     # 8 checks, exit code 0
.venv\Scripts\python.exe tests\test_nav_relay.py                   # 21 tests, needs Node 18+ and spatial/ (main)
```

Needs: Windows 11, NVIDIA driver with CUDA 13.0+ (`nvidia-smi` header), CPython 3.13 x64, Git, Node 18+, Android
platform-tools (`adb`), about 10 GB free. The setup is idempotent: re-run it after any failure.

Check nothing else holds the port or the GPU:

```powershell
netstat -ano | findstr :8765        # a PID here means the port is taken
nvidia-smi                          # other python.exe processes holding VRAM?
```

## 2. Start the server

From `perception_engine/`. Loading and per-lane warm-up take about 30-60 s (up to about 55 s measured, longer on a
shared GPU); wait for the banner with the URLs.

```powershell
# SIM with navigation (the demo default): the tablet plays the clip, the laptop analyses ahead of it
.venv\Scripts\python.exe -m perception.realtime.server --mode sim --nav-session nav/demo_sessions/b1ff4656-0435391e

# LIVE with navigation: tablet camera + tablet GPS, route from a fixed route.json
.venv\Scripts\python.exe -m perception.realtime.server --mode live --nav-route nav/demo_sessions/b1ff4656-0435391e/route.json

# LIVE with a destination (route built from the first GPS fix; Google needs a key in spatial/.env)
.venv\Scripts\python.exe -m perception.realtime.server --mode live --nav-destination "Georgia Tech" --nav-provider mock

# AUTO: the app's hello picks live or sim (no navigation)
.venv\Scripts\python.exe -m perception.realtime.server --mode auto

# VIDEO: laptop-only, plays a clip on its own clock (for testing and a laptop screen demo)
.venv\Scripts\python.exe -m perception.realtime.server --mode video --video data/bdd100k/videos/val/b1ff4656-0435391e.mov --loop
```

Other demo sessions: `nav/demo_sessions/b1f4491b-cf446195` (highway) and `nav/demo_sessions/b23adb0d-8a7aaced`
(night). They are synthetic mock routes timed to the clip and do not match what the video shows.

The banner looks like this:

```text
[server] Perception Engine ready (protocol v2)
  mode        : sim (accepts ['sim'])
  sim clips   : 13 (e.g. b1d968b9-ce42734f, b1f4491b-cf446195, b1ff4656-0435391e)
  local       : ws://127.0.0.1:8765/perception   (GET /health, /config)
  LAN (Wi-Fi) : ws://<laptop-LAN-IP>:8765/perception
  USB (adb)   : adb reverse tcp:8765 tcp:8765   then ws://127.0.0.1:8765/perception
  navigation  : sim session nav/demo_sessions/b1ff4656-0435391e
```

Health at any time: open `http://127.0.0.1:8765/health` in a browser (mode, session, controller, clients, stats,
navigation state, recent errors).

Voice: the same port proxies ElevenLabs for the app (`POST /tts`, `GET /tts/health`). Put `ELEVENLABS_API_KEY` and
`ELEVENLABS_VOICE_ID` in the gitignored `perception_engine/.env`; without them (or with `--no-tts`) the app speaks with
Android TextToSpeech, else earcons. The proxy answers loopback only (USB with `adb reverse`) unless `--tts-allow-lan`.
Visual alerts never depend on audio.

Tuning knobs (restart the server to change them):

| Flag | Default | When to change |
|---|---|---|
| `--max-in-flight N` | 2 | `1` for the lowest live latency on USB (74-91 ms p50, but 10-11 fps); `3` only on an idle USB link |
| `--set slow.max_hz=5` | unlimited | Frees about 10 ms of wave-1 compute; distances and lanes refresh less often |
| `--sim-margin S` | 0.10 | Raise to 0.15-0.20 if SIM results arrive late over Wi-Fi |
| `--lookahead S` | auto | Fix the SIM look-ahead (media seconds) instead of auto-tuning |
| `--port P` | 8765 | If 8765 is taken; pass the same port to `adb reverse` and the app URL |

## 3. Connect the tablet: USB or Wi-Fi

**USB (preferred, lowest latency):**

```powershell
adb devices                          # the tablet must show as "device" (accept the USB debugging prompt)
adb reverse tcp:8765 tcp:8765        # the tablet's 127.0.0.1:8765 now reaches the laptop
adb reverse --list                   # check; re-run after every replug or adb restart
```

The app's default URL, `ws://127.0.0.1:8765/perception`, then works as is.

**Wi-Fi (Galaxy S25 hotspot or any shared network; slow in-town driving only):**
1. Turn on the S25 hotspot (5 GHz if offered) and join it with both the laptop and the tablet.
2. Set the laptop's network profile to Private and allow `python.exe` when Windows Firewall asks.
3. Start the server; note the `LAN (Wi-Fi)` URL it prints.
4. Launch the app with that URL: `--es perception.url ws://<laptop-LAN-IP>:8765/perception` (section 5).

The app accepts any `ws://` or `wss://` URL (settings dialog or `perception.url`); anything else is refused (`Use a ws://
or wss:// URL` in the dialog, ignored as a launch extra). Campus Wi-Fi and some hotspots block device-to-device
traffic, or Windows Firewall classes them Public: prefer USB.

## 4. Build and install the app

From `frontend/` with `JAVA_HOME` pointing at a JDK 17+ and the Android SDK configured (`ANDROID_HOME`, or
`local.properties` with `sdk.dir=<SDK path, forward slashes>`; packages `platforms;android-35`, `build-tools;34.0.0`):

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :perception-bridge:test :bridge-cli:installDist   # 18 + 117 tests
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

First launch asks for the camera permission; LIVE with navigation also asks for location.

## SIM: put the clip on the tablet

The tablet and the laptop need the **same** clip (same file stem). The app reads its own external files folder, which
needs no storage permission. From the repo root:

```powershell
adb shell mkdir -p /sdcard/Android/data/com.drivingassist.spatialcopilot/files/sim
adb push perception_engine\data\bdd100k\videos\val\b1ff4656-0435391e.mov /sdcard/Android/data/com.drivingassist.spatialcopilot/files/sim/
```

`.mov`, `.mp4` and `.mkv` work. The app must have been installed (and launched once) so the folder belongs to it. The
server's hello lists the clips the laptop has (`sim.videos`); an unknown id gets `perception.error unknownVideo`.

## 5. Launch a mode

```powershell
adb shell am start -S -n com.drivingassist.spatialcopilot/.MainActivity --es perception.source sim --es perception.video b1ff4656-0435391e
adb shell am start -S -n com.drivingassist.spatialcopilot/.MainActivity --es perception.source live
adb shell am start -S -n com.drivingassist.spatialcopilot/.MainActivity --es perception.source live --es perception.url ws://<laptop-LAN-IP>:8765/perception
adb shell am start -S -n com.drivingassist.spatialcopilot/.MainActivity --es perception.source demo                      # no laptop needed
adb shell am start -S -n com.drivingassist.spatialcopilot/.MainActivity --es perception.source sim --ez perception.debug true   # Debug view
```

Extras override the saved settings and are saved with them, so a plain launch reopens the last mode (first install:
LIVE on `ws://127.0.0.1:8765/perception`). Tap the status chip for the settings dialog (mode LIVE / SIM / DEMO, URL,
SIM clip id, Debug view, voice, camera films a monitor). DEMO needs no laptop: a scripted scene and a placeholder
"Exit 56" route, only in DEMO. Long-press the chip to switch the Debug view (every box, lane polylines, fitted ego
lane, anchors, horizon, fps / latency / link / nav / GPS / voice lines); use it to check that boxes sit on the objects
(in SIM this also checks that the clip's rotation metadata was applied). The app keeps the screen on.

## 6. What to expect

**Status chip** (top-left): mode, then `LAPTOP NOT CONNECTED` / `WAITING FOR LAPTOP` / `TAKEN OVER`, or the result
fps plus capture-to-result ms (LIVE) or `lead` ms (SIM: how early results arrive). The second line shows the current
problem (missing clip, camera, server error). Degraded states get one banner at the top, for example `Road alerts
paused: perception results are late. Navigation only.`, `Route unavailable ...`, `GPS lost 7 s ago. Last route held.`
Alerts sit at the bottom, for example `Vehicle ahead: 8.4 m` (CLOSE), `TOO CLOSE · Vehicle ahead: 5.1 m`,
`Red light: 40 m`. Mint = OK, amber = warning, red = disconnected.

**Clean view:** road arrows from the phase1 route, projected on the road with the frame's camera block and the ego
lane; the lead vehicle highlighted only in CLOSE / TOO CLOSE with its measured distance; a maneuver card (top-right);
the degraded-state banners. The Debug view adds everything else (section 5).

**Start-up.** The first 1-3 s of every session are slow: in SIM the first results are late and the look-ahead then
overshoots; in LIVE the first frames take 0.6-1.1 s. The `Road alerts paused` banner shows and only navigation is shown meanwhile. From
about 5 s on it is steady.

**Measured on the dev laptop** (RTX 5060 Laptop, city clip, localhost = USB-equivalent). Set A: AC power, other jobs
sharing the GPU. Set B: end to end with the Kotlin fake tablet, on battery. Set C: the same as B on AC power with an
idle GPU (re-captured after the code moved into `perception_engine/`). Tablet numbers are not measured yet.

| Run | Rate | Latency |
|---|---|---|
| SIM (A) | 16.2 results/s, wave 2 8.1/s | results 91 ms (p50) before display, 0.45 % late |
| SIM + nav (B) | 12.5 results/s, wave 2 6.5/s | results about 190 ms before display; every displayed frame covered from 5 s on; route packets about 2 Hz |
| LIVE, maxInFlight 2 (A) | 16.7 results/s | capture to result 106 / 141 ms (p50 / p95) |
| LIVE + nav (B) | 13 results/s | 114 / 162 ms; 51 trip states -> 51 route packets |
| SIM + nav (C) | 20.5 results/s, wave 2 12.5/s | results about 150 ms before display, shown result 24 / 58 ms behind playback (p50 / p95), no late results |
| LIVE + nav (C), 15 fps uplink | 15 results/s (every frame) | 49 / 65 ms; 50 trip states -> 50 route packets |
| LIVE + nav (C), emulated busy Wi-Fi | 14.5 results/s | 82 / 143 ms |
| LIVE, emulated busy Wi-Fi | 12-14.5 results/s | 119-122 / 180-192 ms |
| LIVE, emulated hotspot | 8-11.6 results/s | 143 / 275 ms; one run 159 / 683 ms during a spike cluster |
| LIVE, maxInFlight 1 (A) | 9.6-11.5 results/s | 74-91 ms p50 |

The live target is under 100 ms capture-to-result on USB. With other GPU jobs (A) or on battery (B) `maxInFlight 2`
missed it by 5-15 ms (both lanes share one Python process); on AC power with an idle GPU (C) it was met at 49 ms p50. Full logs: [`examples/`](examples/).

**Known behaviours during the demo:**
- `ARRIVE` ("You have arrived at your destination.") comes after the last turn, even with 150-590 m left: that is
  phase1's current behaviour; the distance on screen is still right.
- Stopped behind a car at 8-9 m the following state can stay `TOO CLOSE` (thresholds are not speed-aware unless the
  settings toggle `Hold TOO CLOSE at CLOSE while stopped` is on; it is off by default).
- There is no exit-sign detector: exit numbers come from the phase1 route only.
- Boxes at the far left/right edge can be cut off: the 16:9 image is cropped about 5 % per side on the 16:10 screen.

## 7. Rehearse without the tablet

From `perception_engine/`, with a server running:

```powershell
.venv\Scripts\python.exe -m perception.realtime.ws_probe sim --video-id b1ff4656-0435391e --seconds 30
.venv\Scripts\python.exe -m perception.realtime.ws_probe live --seconds 30 --focal-px 525 --mount-height 1.3 --trip-states nav/demo_sessions/b1ff4656-0435391e/trip_state.jsonl
.venv\Scripts\python.exe scripts\netem_proxy.py --listen 127.0.0.1:8766 --target 127.0.0.1:8765 --profile hotspot
.venv\Scripts\python.exe -m perception.realtime.ws_probe live --url ws://127.0.0.1:8766/perception --seconds 30
```

The same `PerceptionBridge` the app uses, with Driving Context and route output (from `frontend/`; the module
lives in `perception_engine/android/bridge-cli` and installs into its `build/install/`):

```powershell
.\gradlew.bat :bridge-cli:installDist
..\perception_engine\android\bridge-cli\build\install\bridge-cli\bin\bridge-cli.bat sim --video-id b1ff4656-0435391e --seconds 30
..\perception_engine\.venv\Scripts\python.exe ..\perception_engine\scripts\extract_frames.py b1ff4656-0435391e --fps 15 --seconds 20   # -> perception_engine\outputs\e2e\frames_b1ff4656-0435391e
..\perception_engine\android\bridge-cli\build\install\bridge-cli\bin\bridge-cli.bat live --frames ..\perception_engine\outputs\e2e\frames_b1ff4656-0435391e --fps 15 --loop --trip-states ..\perception_engine\nav\demo_sessions\b1ff4656-0435391e\trip_state.jsonl --seconds 25
```

Netem profiles: `usb`, `wifi-good`, `wifi-busy` (6 +- 5 ms, 80 ms spikes every 4 s), `hotspot` (12 +- 8 ms, 150 ms
spikes every 3 s, 40 Mbit/s).

## 8. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Chip `LAPTOP NOT CONNECTED` over USB | `adb reverse` missing (lost on replug or adb restart) | `adb reverse tcp:8765 tcp:8765`; check `adb reverse --list` |
| Chip `WAITING FOR LAPTOP` for more than 60 s | Server still loading, or crashed | Watch the server console; open `/health` |
| Settings dialog says `Use a ws:// or wss:// URL` | The URL does not start with `ws://` or `wss://` | Use the LAN URL the server prints, or `ws://127.0.0.1:8765/perception` over USB |
| Chip `TAKEN OVER` | Another client (second tablet, `bridge-cli`, `ws_probe`) sent a newer `client.hello` and now controls the laptop | Stop that client: the app takes the session back by itself when the server goes idle. Or tap the chip to take it back, or relaunch the app (its hello wins) |
| A plain launch opens the wrong mode, URL or clip | Settings and launch extras are saved (and can come back with Auto Backup) | Tap the chip and change them, or launch with `--es perception.source ...` (section 5) |
| SIM boxes do not sit on the cars, or the clip plays portrait | The clip's rotation metadata (720x1280 frames + -90 degree matrix) was not applied on the device, or a transcode lost it | Check once in the Debug view; if wrong, push a pre-rotated 1280x720 `.mp4` with the same stem to both devices |
| Chip `Camera: camera format ... is not YUV_420_888` | The analysis stream in `DrivingCamera` was switched to RGBA output | LIVE needs `OUTPUT_IMAGE_FORMAT_YUV_420_888` (the default) |
| Banner `No location: turn Location on for live navigation.` | Location switched off on the tablet, or permission denied | Turn it on (and grant the permission); the app retries when it comes back to the foreground |
| logcat `CLEARTEXT communication ... not permitted` | APK built without the `network_security_config` of `frontend/` (an older build) | Rebuild and reinstall from `frontend/` |
| WebSocket upgrade rejected with HTTP 403 | Another (old v1) server is on the port, or a FastAPI regression (`WebSocket` must be imported at module level in `server.py`) | `netstat -ano \| findstr :8765`; stop that process if it is yours, or run on `--port 8766` and pass the URL |
| Server fails to bind the port | Port in use | Same as above |
| `unknownVideo` / chip names a missing clip | Clip missing on the laptop or the tablet | Laptop: `scripts\fetch_bdd_samples.py`, or put it in `data/sim_videos/` or `--video-dir`. Tablet: [push it](#sim-put-the-clip-on-the-tablet) |
| SIM results late or `lead` negative | Start-up transient, or Wi-Fi jitter | Wait 5 s, or seek to restart the session; raise `--sim-margin` |
| Chip `NAV off on laptop` | Server started without `--nav-*` | Restart with `--nav-session` (SIM) or `--nav-route` / `--nav-destination` (LIVE) |
| `NavRelayError: phase1 route engine not found` | The checkout has no `spatial/` (branch without `main`) | Bring `main` into the checkout, or point `PHASE1_DIR` / `--phase1-dir` at a `spatial/` folder or a legacy `src/phase1` checkout |
| hello `navigation.available: false`, error mentions node | Node missing or too old | Install Node 18+; perception keeps working without it |
| `--nav-provider google` fails at start | No `GOOGLE_MAPS_API_KEY` | Put it in `spatial/.env` (gitignored; never commit it) or the environment, or use `mock` |
| LIVE shows no route, banner `Waiting for GPS` or `GPS accuracy ±... m` | No location fixes: the **Tab S9 Wi-Fi model has no GPS receiver**, permission denied, or indoors | The app uses fused, else GPS, else network location (network: tens of metres; logcat tag `LocationFeeder`). Use a 5G Tab S9, or demo navigation in SIM |
| `perception.error modeNotAvailable` for trip states | Server not running live navigation | Start with `--mode live --nav-route ...` |
| `perception.error notUplinkClient` for trip states | The sender does not control the session (live navigation follows the controller only) | Send `client.hello` (mode live) first, or `navigation.mode: "live"` in the hello |
| hello `navigation.available: false` with "relay failing" in `error`, one `perception.error internal` | phase1 returned no packet 3 times in a row (bad route, Google error) | Server console (logger `perception.realtime.nav_relay`); it recovers by itself on the next good packet |
| Light classifier or YOLOP slow, log says `Failed to create CUDAExecutionProvider` / `cublasLt64_13.dll ... missing` | onnxruntime imported before torch: its CUDA EP falls back to the CPU | Use the provided entry points; in your own scripts `import torch` first |
| `torch.cuda.is_available()` False, or `no kernel image` | CPU or pre-cu128 torch build | Reinstall torch 2.14.0+cu130 (`SETUP.md` section 7) |
| CUDA out of memory at start | Other processes hold VRAM (for example an old server) | `nvidia-smi`; stop what you started; warm-up briefly needs about 5 GB |
| `ModuleNotFoundError: perception` | Wrong working folder | Run from `perception_engine/` |
| Live latency well above the table | Battery power, other GPU jobs, Wi-Fi | AC power, close GPU apps, use USB, `--max-in-flight 1`, `--set slow.max_hz=5` |
| fps drops after some minutes | Thermal throttling (laptop GPU or tablet) | AC power and airflow for the laptop; keep the tablet out of the sun, lower brightness if hot |
| Boxes rotated or shifted in the Debug view | Preview and analysis with different fields of view, or a rotation mapping issue | LIVE requests 16:9 for both (preview about 1280x720, analysis about 960x540); report to the app owner (`frontend/app/src/main/java/com/drivingassist/spatialcopilot/camera/DrivingCamera.kt`) |
| Tablet cannot reach the laptop on the hotspot | Different networks, firewall, or client isolation | Same SSID on both, laptop profile Private, allow `python.exe`; otherwise use USB |
| `adb` shows `unauthorized` | USB debugging not accepted | Accept the prompt on the tablet, or revoke and re-plug |

## 9. Shut down

- Stop the server with Ctrl+C in its console (the phase1 relay child exits with it).
- `adb reverse --remove tcp:8765` (optional).
- Logs and outputs stay in the gitignored `perception_engine/outputs/`; never commit them, the clips or the APK.
