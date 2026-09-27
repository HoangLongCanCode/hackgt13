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

The **clean view** (the default) is for driving and shows only this:

- **Lane arrows painted on the road**, one in every visible lane, lying flat between that lane's two painted lines and
  pointing where the lines meet, 6 to 12 m ahead. The lanes are built on the tablet from the lines the lane model
  detects (`lanes.laneBoundaries`, fitted through one vanishing point, so a tilted or turned phone still gets parallel
  arrows; the bridge's `LaneLayout`), not from the model's lane numbers; lines beyond the yellow centre line (the
  oncoming road) are left out, and a line missed on one run is kept for 2 s. The lane to drive in is green, the others
  white: with no lane requirement that is the car's own lane; before a turn it is the leftmost visible lane for a left
  turn and the rightmost for a right turn (Google routes carry no lane data, so this is inferred, from 300 m or 20 s of
  travel out). A lane requirement the tablet cannot match to the visible lanes (lane guidance UNKNOWN) paints every
  arrow white, the car's lane too. In a wrong lane the car's own arrow turns red and the target lane's arrow blinks
  slowly (after 0.8 s, so a flickering lane does not flash it). Near the maneuver (100 m) the target arrow takes the
  turn or exit shape. Without usable lines only the car's lane gets an arrow, along the camera's ground track (the
  road's vanishing point, else the last one seen this session), never red. Arrows are drawn only on the visible road:
  the server's drivable outline (`road.drivablePolygon`), never over the dashboard, hood or A-pillars, and a lane
  whose middle misses that road gets none. When the server sees no road at all (an empty outline, e.g. stopped close
  behind a car with only the crosswalk and median in view) no arrow is drawn; the instruction at the top still guides.
  Arrows are cut out around cars and people, and only show with a live route. They use the frame's `camera` block
  (focal, principal point, horizon, camera height, 1-2 m accepted), not device pose, so they also work when the tablet
  films a monitor.
- **Instruction** (top centre): "Drive straight", "Turn left in 900 ft", "Exit 94 in 0.6 mi", "Destination in 250 ft",
  with a small maneuver glyph. It names the next real maneuver (phase1's "continue" steps are skipped), in feet and
  miles like the voice. Beyond a mile it says "Drive straight" with the maneuver as a second line.
- **Speed limit** (top left, US sign), in mph: a speed-limit sign the perception engine read confidently (the same value
  for 0.8 s at confidence 0.85 or more, within 60 m), else the map value from the laptop (OpenStreetMap, see
  `--speed-limits osm` below). Nothing when unknown. A sign value is dropped after a turn or exit, when the map road
  changes, or after 10 minutes.
- **TOO CLOSE**: red pulsing brackets on the lead vehicle and one pill, "TOO CLOSE · Vehicle ahead: 6.2 m" (also
  "Pedestrian ahead: 9 m" for a pedestrian in the path at critical range). Only with a measured distance under 12 m
  (then below 7 m, under 2 s to collision, or under 0.8 s of headway at the route speed), and only for
  a vehicle inside the car's own lane lines (without lines: within 1 m of the camera's ground track); cars in other
  lanes never trigger it. While the route speed says the car is stopped (below 1.5 m/s, e.g. at a red light behind a
  car) it is held at CLOSE: no pill and no voice. A settings switch, on by default; turn it off when the camera films a
  monitor in LIVE, where the route speed is the tablet's own GPS. An unknown speed never holds it.
- **Corner button** (bottom left): tap for settings (or to take control back), hold for the debug view. Next to it, only
  when something is wrong, one short line: "Laptop not connected", "Road alerts paused", "No GPS fix", "Route
  unavailable", ... In LIVE without a destination, a **Where to?** button.

The **debug view** (hold the corner button, or the settings switch) keeps everything else: the status chip with fps /
latency, the degraded-state banner, the maneuver card with ETA and distance left, the heading-up route map, all alert
pills (vehicle close, lights), every box with class and distance, lane polylines, the fitted ego lane and its source,
anchors, horizon, the old chevron path, the lane state ("lanes 2/3 conf 0.82 targets 3 WRONG"), the speed-limit source
and the link / nav / GPS / voice numbers.

## Modes

Tap the corner button (bottom left) for settings.

| Mode | Picture | Perception | Route |
|---|---|---|---|
| LIVE | rear camera (aimed at the road, or at a monitor playing a drive) | SDC1 24-byte header + JPEG (about 960x540, q80) under credit flow control | phase1 on the laptop, fed by this tablet's GPS (`client.trip_state`, about 1 Hz) |
| SIM | the clip, played on the tablet with Media3 | the laptop analyses the same clip ahead of the reported playback position | phase1 by media time (`--nav-session`) |
| DEMO | a dark road | a scripted scene, no laptop: wrong lane for the exit, a lane change at 12-15 s, a 55 mph sign, TOO CLOSE at about 24 s | a placeholder "Exit 56" route (DEMO only) |

## Run

1. Laptop, from `perception_engine/` (see `../perception_engine/AGENTS.md`):

   ```powershell
   .venv\Scripts\python.exe -m perception.realtime.server --mode auto --nav-session nav/demo_sessions/b1ff4656-0435391e
   ```

   `--mode auto` accepts LIVE and SIM. Live navigation: `--nav-destination "Piedmont Park" --nav-provider mock` (or
   `google` with `GOOGLE_MAPS_API_KEY` in `../spatial/.env`). The ElevenLabs key goes in the gitignored
   `perception_engine/.env` only (`ELEVENLABS_API_KEY`, `ELEVENLABS_VOICE_ID`); never in the app. Without an
   ElevenLabs plan, add `--no-tts`: the tablet speaks with Android TTS.
   Map speed limits (optional): add `--speed-limits osm`. The laptop then looks up OpenStreetMap `maxspeed` for the
   road at the car's position through the public Overpass API (`--speed-limit-endpoint` for another instance), so the
   car position is sent there, about every 40 m and at least every 30 s. Off by default; only "N mph" tags count.
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
| `GOOGLE_MAPS_API_KEY` (Geocoding API + Routes API enabled; Places API for "Where to?") | `spatial/.env` (template `spatial/.env.example`) | phase1's Google provider, run by the perception server's relay |
| `ELEVENLABS_API_KEY`, `ELEVENLABS_VOICE_ID` | `perception_engine/.env` (template `perception_engine/.env.example`) | the perception server's `POST /tts` proxy |

Both files are gitignored and stay on the laptop; the tablet only talks to the laptop. The route map on the tablet is
drawn from the route line phase1 sends, so the app needs no Maps SDK key.

## Navigation with Google Maps and GPS

1. Put the key in `spatial/.env` and check it: `node spatial/scripts/check-google-key.js "Piedmont Park, Atlanta"`.
2. Start the server with live navigation: `... -m perception.realtime.server --mode auto --nav-live --nav-provider google`
   (`--nav-provider mock` works without a key).
3. On the tablet, LIVE mode, tap **Where to?** (or type a **Destination** in the settings). The app sends it
   (`client.destination`); the laptop geocodes it and builds the Google route from the tablet's next GPS fix
   (`client.trip_state`, 1 Hz), then sends `navigation.packet`s: the instruction, the lane arrows and the voice prompts
   follow them (the debug view adds the maneuver card and the heading-up route map). Google routes carry no lane data,
   so the lane side is inferred from the maneuver (right turn / right exit: rightmost lane) within 300 m of it (20 s of
   travel at speed, at most 800 m).
   Or at launch (the whole command in one pair of quotes, the place in inner quotes, or `adb shell` splits it at the spaces):
   `adb shell "am start -S -n com.drivingassist.spatialcopilot/.MainActivity --es perception.source live --es perception.destination 'Piedmont Park, Atlanta'"`.

### Where to? (search a place on the tablet)

In LIVE mode, tap **Where to?** (bottom left, or in the settings), type "coffee", a name or an address and press Search on the
keyboard (one search per submit, never per keystroke). The laptop searches around the tablet's latest GPS fix
(`client.place_search` -> `navigation.places`; Google Places Text Search, or the Geocoding API when Places is not
enabled for the key; made-up places with `--nav-provider mock`) and the panel lists up to 8 results with address and
distance. Tap one: the app sends `client.destination` with the place's exact location, so the laptop routes there
without geocoding the name, saves the label as the Destination and closes the panel. Without `--nav-live` on the laptop the panel says so. The Google key never
leaves the laptop.

## Voice

Driving events and the route go through the deterministic cue rules of `../perception_engine/docs/audio/` (the catalog
`audio_cues.v1.json` is copied into the APK at build time), then a one-at-a-time arbiter (CRITICAL cuts navigation),
then one AudioTrack. TOO CLOSE is spoken once when it starts (after 250 ms, only when closing or moving), is not repeated
while it holds, and re-arms only after the following state is back to NORMAL; a re-entry within 8 s plays the warning
tone only. Pedestrian cues ("Pedestrian ahead.", "Pedestrian very close.") are not spoken while the route speed says
the car is stopped (below 1.5 m/s, with the same "hold TOO CLOSE while stopped" switch), so people crossing in front
of a car waiting at a light stay silent; moving, or with an unknown speed, they are spoken as usual. Sources: the laptop's `POST /tts` (ElevenLabs, fixed phrases fetched at start), then Android TextToSpeech,
then earcons. Visuals never depend on audio; the settings can turn voice off.

Navigation prompts: "Starting route to your destination.", "Drive straight for two miles." (a new maneuver more than a
mile and a minute away), "In half a mile, take exit ninety-four." and "Take exit ninety-four." (exit numbers as words;
Google ramps count as exits), "Turn left.". Lane change: "Move to the right lane for the exit, check for cars." (or "for
the turn", or neither), once per maneuver, when over the last 2 s the lane guidance asked for the same side in 70 % of
the steps (and within the last 0.5 s) and the lane layout (the lanes the bridge fits to the detected lines) was stable,
of quality 0.5 or more and no older than 1 s in 70 % of them; the lane model's own confidence is not used. A share, not
a continuous run, so a lane that drops out behind the A-pillar now and then does not keep it silent. Not once the
maneuver prompt itself is due, and a queued cue is dropped once the side has lost most of the last second. It asks the
driver to look; it never says the lane is free.

## Layout

| Path | Role |
|---|---|
| `app/src/main/java/com/drivingassist/spatialcopilot/session/` | `CopilotSession` (the one `PerceptionBridge`), `CameraUplink` (analyzer -> SDC1), `SimPlayback` (Media3 + `client.playback`), `LocationFeeder` (GPS -> `client.trip_state`), `AppSettings`, `StatusModel` |
| `.../ar/` | `FillCenter` (server image -> view), `GroundProjector` (road plane, same maths as the server's `groundXZ`), `EgoLane`, `LaneArrows` (painted lane arrows, target / wrong-lane styles), `RouteArrows` (debug chevrons, arrival pin), `ArScene` (per-frame scene, fades, lead highlight, Debug layer) |
| `.../nav/` | `RouteGuide` (fields of phase1's `navigation.packet`, next real maneuver), `NavText` (instruction text, feet / miles), `DemoDrive` (DEMO script) |
| `.../voice/` | `CueCatalog`, `CuePolicy`, `VoiceArbiter`, `VoiceBus`, `Earcons`, `VoiceSources` (`/tts` proxy, Android TTS), `VoiceCoordinator` |
| `.../ui/` | `CopilotScreen` (clean and debug chrome, settings), `Hud` (speed-limit sign, instruction banner, corner button), `PlaceSearch` ("Where to?" panel), `SpatialArEngine` (draws the scene at display rate), `SimVideoBackground` |
| `.../camera/` | `DrivingCamera` (CameraX preview + `KEEP_ONLY_LATEST` analysis), `YuvJpegEncoder` |
| `../perception_engine/android/perception-bridge/` | shared pure-JVM library: PROTOCOL_v2 client, `WorldModel`, `DrivingContextEngine` (included as `:perception-bridge`) |

The protocol is `../perception_engine/contracts/PROTOCOL_v2.md` (with JSON Schemas and golden samples).

## Tests

```bat
gradlew.bat :app:testDebugUnitTest :perception-bridge:test :bridge-cli:installDist
```

The app tests (JVM) check the ground projection against the server's `groundXZ` on real frames, the Tab S9
FILL_CENTER crop, the lane arrows (styles, debounce, glyphs, flat on the road, occluders), the ego lane through a lane
change, the lead highlight rules, the phase1 packet fields and the next-maneuver skip, the instruction text and units,
the DEMO script, the cue rules (TOO CLOSE once, re-arm after NORMAL, nav prompts, lane cue, "Drive straight", exit
numbers), the arbiter and the SDC1 tag. `bridge-cli` is a JVM fake tablet on
the same bridge code: `bridge-cli.bat live --frames <dir>`, `sim --video-id <id>`, `watch`.
