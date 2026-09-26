# Spatial Copilot (tablet app)

The Samsung Galaxy Tab S9 app of the AI Spatial Driving Copilot (HackGT 13, by Long Huynh, Luong Nguyen and Gia Minh Do).
The tablet owns the camera, the display, GPS, the Driving Context and audio playback. The laptop only runs the perception
models, the phase1 navigation relay and a text-to-speech proxy. This app renders their state: it runs no ML and no route
logic.

It is **display only**. Nothing steers, brakes or accelerates, and no LLM makes any safety or driving decision (alerts are
deterministic rules). Wording is measurable ("Vehicle ahead: 8.4 m"). GREEN is never permission to go, UNKNOWN stays
unknown, and without a measured distance nothing is highlighted.

```text
Tablet camera / sim clip --SDC1+JPEG--> laptop Perception Engine --perception.frame / .update--+
Tablet GPS --client.trip_state--> laptop relay -> spatial/phase1 (mock | Google) --navigation.packet--+
                                                                                                         v
                       Tablet: PerceptionBridge -> WorldModel -> DrivingContextEngine -> presentation
                                                                             |- AR on the road (Compose Canvas)
                                                                             '- voice cues -> laptop /tts (ElevenLabs) | Android TTS
```

## What you see

- **Road arrows** (mint chevrons on the road) for phase1's next maneuver: they follow the ego lane, lead up to the
  maneuver and bend there (turn / keep / exit, when phase1 gives the side), show a lane change when the Driving Context
  says the car is in the wrong lane, and fade out when nothing is coming up. They are placed on the road with the
  frame's `camera` block (focal, principal point, horizon, camera height) and the lane lines, not with device pose, so
  they also work when the tablet films a monitor playing a drive.
- **Lead vehicle**: no boxes by default. Only the Driving Context's lead vehicle, only in CLOSE or TOO CLOSE, gets
  corner brackets with "Vehicle ahead: 8.4 m" (and "TOO CLOSE" in red when critical). It clears in NORMAL.
- **Maneuver card** (top right): phase1's maneuver, the distance counting down between packets, ETA and distance left.
- **Alert pill** (bottom): TOO CLOSE / vehicle close / pedestrian / red or yellow light, with measured distances.
- **Status chip** (top left) and one **banner** for degraded states: laptop not connected, waiting for the laptop,
  another client in control (tap the chip to take it back), road alerts paused, no location, GPS lost or inaccurate
  (the last route is held), route unavailable (Google failure / invalid route), navigation stale, off route, camera or
  clip problems, voice fallbacks.
- **Debug view** (long-press the chip): every box with class and distance, lane polylines, the fitted ego lane and its
  source, anchors, horizon, and fps / latency / credits / nav / GPS / voice numbers.

## Modes

Tap the status chip for settings.

| Mode | Picture | Perception | Route |
|---|---|---|---|
| LIVE | rear camera (aimed at the road, or at a monitor playing a drive) | SDC1 24-byte header + JPEG (about 960x540, q80) under credit flow control | phase1 on the laptop, fed by this tablet's GPS (`client.trip_state`, about 1 Hz) |
| SIM | the clip, played on the tablet with Media3 | the laptop analyses the same clip ahead of the reported playback position | phase1 by media time (`--nav-session`) |
| DEMO | a dark road | a scripted scene, no laptop | a placeholder "Exit 56" route (DEMO only) |

## Run

1. Laptop, from `perception_engine/` (see `../perception_engine/AGENTS.md`):

   ```powershell
   .venv\Scripts\python.exe -m perception.realtime.server --mode auto --nav-session nav/demo_sessions/b1ff4656-0435391e
   ```

   `--mode auto` accepts LIVE and SIM. Live navigation: `--nav-destination "Piedmont Park" --nav-provider mock` (or
   `google` with `GOOGLE_MAPS_API_KEY` in `../spatial/.env`). The ElevenLabs key goes in the gitignored
   `perception_engine/.env` only (`ELEVENLABS_API_KEY`, `ELEVENLABS_VOICE_ID`); never in the app.
2. Build and install from `frontend/` (JDK 17+ as `JAVA_HOME`, `local.properties` with `sdk.dir=` in forward slashes):

   ```bat
   gradlew.bat :app:assembleDebug
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   ```

3. USB (preferred; campus Wi-Fi and hotspots can block tablet-to-laptop traffic):

   ```bat
   adb reverse tcp:8765 tcp:8765
   ```

   The default URL is `ws://127.0.0.1:8765/perception`. On Wi-Fi, enter `ws://<laptop LAN IP>:8765/perception` in
   the settings and start the server with `--tts-allow-lan` so voice can use ElevenLabs.
4. SIM: push the clip once, then pick SIM in the settings:

   ```bat
   adb push b1ff4656-0435391e.mov /sdcard/Android/data/com.drivingassist.spatialcopilot/files/sim/
   ```

5. Or start a mode from adb (saved like settings changes):

   ```bat
   adb shell am start -S -n com.drivingassist.spatialcopilot/.MainActivity --es perception.source sim --es perception.video b1ff4656-0435391e
   ```

   Extras: `perception.source` (`live` / `sim` / `demo`), `perception.url`, `perception.video`, `perception.debug`.

LIVE asks for the camera and location. On the Wi-Fi Tab S9 (no GPS chip) the fused provider uses Wi-Fi positioning:
indoors a fix comes every few seconds at 25-100 m, the banner says so and the route is held. For SIM, push the clip after
the app has run once (it creates `files/sim/` itself; a folder made with `adb shell mkdir` is not readable by the app).

## API keys (laptop only, never in the app)

| Key | File | Used by |
|---|---|---|
| `GOOGLE_MAPS_API_KEY` (Geocoding API + Routes API enabled) | `spatial/.env` (template `spatial/.env.example`) | phase1's Google provider, run by the perception server's relay |
| `ELEVENLABS_API_KEY`, `ELEVENLABS_VOICE_ID` | `perception_engine/.env` (template `perception_engine/.env.example`) | the perception server's `POST /tts` proxy |

Both files are gitignored and stay on the laptop; the tablet only talks to the laptop. The route map on the tablet is
drawn from the route line phase1 sends, so the app needs no Maps SDK key.

## Navigation with Google Maps and GPS

1. Put the key in `spatial/.env` and check it: `node spatial/scripts/check-google-key.js "Piedmont Park, Atlanta"`.
2. Start the server with live navigation: `... -m perception.realtime.server --mode auto --nav-live --nav-provider google`
   (`--nav-provider mock` works without a key).
3. On the tablet, LIVE mode, tap the chip and type a **Destination**. The app sends it (`client.destination`); the
   laptop geocodes it and builds the Google route from the tablet's next GPS fix (`client.trip_state`, 1 Hz), then
   sends `navigation.packet`s: the maneuver card, the road arrows, the voice prompts and the route map (bottom right,
   heading-up, 300 m) follow them.
   Or at launch (the whole command in one pair of quotes, the place in inner quotes, or `adb shell` splits it at the spaces):
   `adb shell "am start -S -n com.drivingassist.spatialcopilot/.MainActivity --es perception.source live --es perception.destination 'Piedmont Park, Atlanta'"`.

## Voice

Driving events and the route go through the deterministic cue rules of `../perception_engine/docs/audio/` (the catalog
`audio_cues.v1.json` is copied into the APK at build time), then a one-at-a-time arbiter (CRITICAL cuts navigation),
then one AudioTrack. TOO CLOSE is spoken once when it starts (after 250 ms, only when closing or moving), is not repeated
while it holds, and re-arms only after the following state is back to NORMAL; a re-entry within 8 s plays the warning
tone only. Sources: the laptop's `POST /tts` (ElevenLabs, fixed phrases fetched at start), then Android TextToSpeech,
then earcons. Visuals never depend on audio; the settings can turn voice off.

## Layout

| Path | Role |
|---|---|
| `app/src/main/java/com/drivingassist/spatialcopilot/session/` | `CopilotSession` (the one `PerceptionBridge`), `CameraUplink` (analyzer -> SDC1), `SimPlayback` (Media3 + `client.playback`), `LocationFeeder` (GPS -> `client.trip_state`), `AppSettings`, `StatusModel` |
| `.../ar/` | `FillCenter` (server image -> view), `GroundProjector` (road plane, same maths as the server's `groundXZ`), `EgoLane`, `RouteArrows`, `ArScene` (per-frame scene, fades, lead highlight, Debug layer) |
| `.../nav/` | `RouteGuide` (fields of phase1's `navigation.packet`), `DemoDrive` (DEMO script) |
| `.../voice/` | `CueCatalog`, `CuePolicy`, `VoiceArbiter`, `VoiceBus`, `Earcons`, `VoiceSources` (`/tts` proxy, Android TTS), `VoiceCoordinator` |
| `.../ui/` | `CopilotScreen` (chrome, settings), `SpatialArEngine` (draws the scene at display rate), `SimVideoBackground` |
| `.../camera/` | `DrivingCamera` (CameraX preview + `KEEP_ONLY_LATEST` analysis), `YuvJpegEncoder` |
| `../perception_engine/android/perception-bridge/` | shared pure-JVM library: PROTOCOL_v2 client, `WorldModel`, `DrivingContextEngine` (included as `:perception-bridge`) |

The protocol is `../perception_engine/contracts/PROTOCOL_v2.md` (with JSON Schemas and golden samples).

## Tests

```bat
gradlew.bat :app:testDebugUnitTest :perception-bridge:test :bridge-cli:installDist
```

The app tests (JVM) check the ground projection against the server's `groundXZ` on real frames, the Tab S9
FILL_CENTER crop, the arrow choice and fades, the lead highlight rules, the phase1 packet fields, the cue rules
(TOO CLOSE once, re-arm after NORMAL, nav prompts), the arbiter and the SDC1 tag. `bridge-cli` is a JVM fake tablet on
the same bridge code: `bridge-cli.bat live --frames <dir>`, `sim --video-id <id>`, `watch`.
