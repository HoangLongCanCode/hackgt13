# Perception bridge integration (laptop models + phase1 navigation -> Glass Mode)

Part of the AI Spatial Driving Copilot by Knuckle Sandwich Robotics Inc. (KSR). This document is for Tom and his AI tools. It covers what the KSR perception bridge added to `driving_assist`, which of Tom's files changed and why, how to switch sources, how the data maps onto `VisionData` / `RouteState`, and what to draw next.

**One switch.** The app runs in one of three sources. `MOCK` is the default and looks and behaves exactly as before (no chip, no extra permissions, the 5 s route loop). A plain launch is always the BuildConfig default: launch extras apply to that launch only unless `--ez ksr.persist true` saves them (section 3).

| Source | Background | Vision (`VisionData`) | Route (`RouteState`) |
|---|---|---|---|
| `MOCK` (default) | camera preview | `MockVisionSource` | the 5 s mock loop in `MockDataViewModel` |
| `LIVE` | camera preview (16:9, CameraX preview size; analysis about 1280x720) | laptop results for the tablet's own camera frames (JPEG uplink) | phase1 via the laptop, from the tablet's GPS (`navEnabled`) |
| `SIM` | the clip itself (Media3 ExoPlayer) | laptop results for the same clip, analysed ahead of playback | phase1 via the laptop, on the clip's timeline (`navEnabled`) |

Protocol source of truth: [`../contracts/PROTOCOL_v2.md`](../contracts/PROTOCOL_v2.md). The laptop server is `perception_engine/perception/realtime/server.py`; the phase1 relay is `perception_engine/nav/`.

---

## 1. What was added

### New modules (pure Kotlin/JVM, no Android APIs)

| Module | What |
|---|---|
| `perception-bridge/` | Kotlin 2.0.21, kotlinx-serialization 1.7.3, coroutines 1.9.0, OkHttp 4.12.0, bytecode 17. Holds the PROTOCOL_v2 data classes for every message (`perception.hello/frame/update/skip/stats/pong/error`, `navigation.packet`, `client.hello/playback/ping/trip_state`), the 24-byte `KSR1` `UplinkHeader`, `WorldModel` (wave-1 / wave-2 merge by track id, smoothing, staleness), `DrivingContextEngine` (following distance, lights, pedestrians, signs, lane guidance), `NavigationMapper` (phase1 `routeState` -> `NavigationState`) and the `PerceptionBridge` facade. Ported from the earlier standalone `copilot-kotlin` libraries and brought to PROTOCOL_v2 including the Navigation section. |
| `bridge-cli/` | A fake tablet on the JVM that drives the **same** `PerceptionBridge`. Commands: `live --frames DIR`, `sim --video-id ID`, `watch`. It prints link health, lanes, lead vehicle, light, route state and events every 0.5 s, and `--trip-states` replays a phase1 `trip_state.jsonl` as GPS. See section 9. |

`PerceptionBridge` API (all non-blocking and callable from any thread):

```kotlin
val bridge = PerceptionBridge(url, scope, BridgeConfig(clockNs = SystemClock::elapsedRealtimeNanos))
bridge.connect(ClientHello.live(...) / ClientHello.sim(...))   // auto-reconnect, hello re-sent, ping 1 Hz
bridge.offerCameraFrame(jpeg, captureTimeNs, rotationDegrees): Boolean  // credits: drops, never queues
bridge.canUplinkNow()                     // check before JPEG-encoding
bridge.reportPlayback(videoId, pts, playing, rate)   // sim, ~10 Hz + on seek/pause
bridge.resultForPts(pts): WorldSnapshot?  // sim: newest result with pts <= playback (<= 150 ms old)
bridge.predictedAt(displayTimeNs)         // live: boxes/distances moved forward from capture time
bridge.sendTripState(ClientTripState(...))           // live navigation, ~1 Hz
bridge.world:      StateFlow<WorldSnapshot>          // objects by track id, lanes, road, signs, perceptionStale
bridge.context:    StateFlow<DrivingContext>         // following state, light, pedestrians, lane guidance, activeAlerts
bridge.events:     SharedFlow<DrivingEvent>          // edges with priority + optional speech (no replay)
bridge.navigation: StateFlow<NavigationUpdate?>      // phase1 routeState + verbatim SpatialNavigationPacket, stale flag
bridge.link:       StateFlow<LinkStatus>             // state, fps, capture->result ms, sim lead, credits, skips, nav age
```

### New files in `:app` (package `com.drivingassist.glass.perception`)

| File | What |
|---|---|
| `PerceptionConfig.kt` | `source` (MOCK/LIVE/SIM), `serverUrl`, `simVideoId`, `mountHeightMeters`, `navEnabled`. Defaults come from BuildConfig; launch-intent extras override them for that launch, and are saved in SharedPreferences (validated) only with `ksr.persist`. Also holds the `ws://` host guard (section 7). |
| `PerceptionFactory.kt` | Builds the vision source and route source for a config, plus the `ViewModelProvider.Factory` used by `MainActivity`. |
| `PerceptionRuntime.kt` | Owns the single `PerceptionBridge` per LIVE/SIM session (one WebSocket), its scope, the camera-clock conversion, the focal length from Camera2, the status-chip text, the host visibility flag and the GPS feeder (retried every 5 s while no provider is on). Also defines the small interfaces `BridgeBacked` and `CameraResolutionHint`. |
| `LaptopVisionSource.kt` | LIVE. `VisionSource` + `ImageAnalysis.Analyzer`. Converts YUV_420_888 to a JPEG (q80, at most 960 px wide) and calls `offerCameraFrame`. A ~30 Hz publisher maps `predictedAt(now)` to `VisionData` (idle while the activity is stopped). **Needs `OUTPUT_IMAGE_FORMAT_YUV_420_888`** in `CameraPreview`: with another format (e.g. RGBA for an OpenCV analyzer) nothing is uplinked and the chip says `LIVE uplink: camera format ... is not YUV_420_888`. |
| `YuvJpegEncoder.kt` | YUV_420_888 -> downscaled NV21 -> `YuvImage.compressToJpeg`, reusing its buffers (including the JPEG array, which `offerCameraFrame(bytes, ..., length)` copies once). |
| `SimVisionSource.kt` | SIM. ExoPlayer on `<app external files>/sim/<videoId>.(mov\|mp4)`. Calls `reportPlayback` every 100 ms and on play/pause/seek/loop, and publishes `resultForPts(currentPosition)` about 30 times per second. |
| `SimVideoBackground.kt` | The `PlayerView` (RESIZE_MODE_ZOOM, no controller) shown in SIM mode instead of the camera. It pauses and resumes with the activity. |
| `VisionMapper.kt` | Pure mapping `WorldSnapshot` -> `VisionData` (section 4). Unit-tested. |
| `RouteSource.kt` | `RouteSource` interface + `BridgeRouteSource` (`navigation.packet.routeState` -> `RouteState`). |
| `LocationFeeder.kt` | LIVE with navigation on. Reads `LocationManager` (no Play Services) and sends `client.trip_state` about once per second (0 for a missing bearing / speed, as the contract requires). Tries the GPS provider, then network, then fused; never the passive provider (it delivers nothing unless another app asks). |
| `BridgeStatusChip.kt` | Status chip composable, plus `PerceptionHostEffects` (keeps the screen on in LIVE / SIM, reports ON_START / ON_STOP to the runtime) and `LocationPermissionRequest` (LIVE with navigation on only). |
| `res/xml/network_security_config.xml` | Allows cleartext `ws://` to the laptop (section 7). |
| `src/test/.../VisionMapperTest.kt`, `RealSampleMappingTest.kt` | JVM unit tests: mapping, lane selection, clipping, route mapping, URL guard, and the real server samples in `contracts/samples/v2/`. |

---

## 2. Tom's files that changed (diff summary)

All edits add to the existing code. `MOCK` builds `MockDataViewModel()` exactly as before; the mock route loop and `MockVisionSource` are unchanged. `git diff --stat` against `origin/main`: 7 code/build files plus a short "Perception bridge" section in `README.md` (run it for the current numbers).

| File | Change | Why |
|---|---|---|
| `MockDataViewModel.kt` | `private val visionSource` -> `val visionSource` (public). New optional constructor parameter `routeSource: RouteSource? = null` (`@JvmOverloads` kept, so the no-arg constructor still exists). When `routeSource == null` the 5 s route loop runs unchanged; otherwise `routeState` follows the route source. | MainActivity needs to reach the source (SIM player, status chip, camera size hint). Navigation comes from phase1 instead of the loop when enabled. |
| `MainActivity.kt` | `PerceptionConfig.load(this, intent)` in `onCreate`, and `viewModel(factory = PerceptionFactory.viewModelFactory(...))`. In SIM, shows `SimVideoBackground` instead of `CameraPreview`. Passes `targetResolution` from the source to `CameraPreview`. In LIVE / SIM only (nothing in MOCK): `BridgeStatusChip` bottom-left and `PerceptionHostEffects` (screen on, lifecycle, location permission). | One switch for the whole app. The layout is not forked: the overlay and debug toggle are untouched. |
| `CameraPreview.kt` | New optional parameter `targetResolution: Size? = null`. When set, ImageAnalysis gets a `ResolutionSelector` (16:9 or 4:3 by aspect, closest to the target) and Preview gets the same aspect ratio at CameraX's preview size (so it stays sharp on the 2560x1600 screen). `null` applies no selector, as before. A `DisplayManager.DisplayListener` updates `targetRotation` of Preview and ImageAnalysis when the display rotates, including the 180-degree landscape flips that cause no configuration change (this also fixes Tom's analyzer path). | The overlay mapping assumes the analysed frame has the preview's field of view; CameraX defaults can give a 16:9 preview with a 4:3 analysis image. Without the listener a tablet mounted the other way up after launch uplinked frames with a `rotationDegrees` 180 degrees off. |
| `AndroidManifest.xml` | `INTERNET`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `uses-feature` location and GPS with `required="false"`, and `android:networkSecurityConfig`. `sensorLandscape` is kept. | WebSocket to the laptop; optional GPS for live navigation. |
| `app/build.gradle.kts` | `buildFeatures.buildConfig = true`; `buildConfigField`s `KSR_SOURCE`, `KSR_SERVER_URL`, `KSR_SIM_VIDEO_ID`, `KSR_MOUNT_HEIGHT_M`, `KSR_NAV_ENABLED` (from Gradle properties); dependencies `project(":perception-bridge")`, OkHttp 4.12.0, Media3 exoplayer + ui 1.5.1 (compileSdk 35 / AGP 8.7.3 era); `testImplementation("junit:junit:4.13.2")`. compileSdk, Compose setup and package name are unchanged. | Config defaults, the bridge, and SIM playback. |
| `build.gradle.kts` (root) | `org.jetbrains.kotlin.jvm` and `org.jetbrains.kotlin.plugin.serialization` 2.0.21, `apply false`. | The two JVM modules. |
| `settings.gradle.kts` | `include(":perception-bridge")`, `include(":bridge-cli")`. | Same. |

`AROverlay.kt`, `Models.kt`, `VisionSource.kt` and `PreviewCoordinates.kt` are **not** modified. MOCK shows nothing new (the chip and the perception effects are composed only for LIVE / SIM).

---

## 3. How to switch MOCK / LIVE / SIM

Precedence: **launch-intent extras** (this launch only) > values saved with `--ez ksr.persist true` (SharedPreferences) > **BuildConfig** defaults. A plain launch (Android Studio Run, the launcher icon, `adb install -r` + start) therefore uses the BuildConfig defaults unless someone saved values on purpose; the chip then reads e.g. `LIVE (saved)`.

```bash
# Build-time defaults (optional; MOCK if nothing is given)
gradlew.bat :app:assembleDebug -Pksr.source=LIVE -Pksr.url=ws://127.0.0.1:8765/perception -Pksr.nav=true

# Per launch (this launch only). -S restarts the activity.
adb shell am start -S -n com.drivingassist.glass/.MainActivity --es ksr.source live
adb shell am start -S -n com.drivingassist.glass/.MainActivity --es ksr.source live --es ksr.url ws://192.168.43.20:8765/perception
adb shell am start -S -n com.drivingassist.glass/.MainActivity --es ksr.source sim --es ksr.video b1ff4656-0435391e
# Also for later plain launches (saved after validation; the chip shows "(saved)")
adb shell am start -S -n com.drivingassist.glass/.MainActivity --es ksr.source live --ez ksr.persist true
adb shell am start -S -n com.drivingassist.glass/.MainActivity --ez ksr.reset true        # forget saved values
```

Extras: `ksr.source` (mock/live/sim), `ksr.url`, `ksr.video` (clip stem), `ksr.mount` (camera height in metres, 0.3 to 4), `ksr.nav` (true/false), `ksr.persist` (save this launch's valid extras), `ksr.reset` (clear saved values); `--es` or `--ez` both work. Invalid values are ignored (never saved), logged under tag `PerceptionConfig`, and shown on the status chip. The app's `allowBackup` is unchanged, so saved values can come back with Auto Backup after a reinstall: `ksr.reset` clears them.

### Laptop side (run from `perception_engine/`, see its README)

```bash
python -m perception.realtime.server --mode live --nav-route nav/demo_sessions/b1ff4656-0435391e/route.json   # LIVE (+ live nav)
python -m perception.realtime.server --mode sim  --nav-session nav/demo_sessions/b1ff4656-0435391e            # SIM (+ sim nav)
python -m perception.realtime.server --mode auto                                                              # live or sim, by the app's hello
```

USB (preferred): `adb reverse tcp:8765 tcp:8765`, then the app's default URL `ws://127.0.0.1:8765/perception` works. On Wi-Fi, pass the laptop's LAN IP with `ksr.url`; the server prints it at start-up. Wi-Fi is fine for slow in-town driving only.

### SIM: put the clip on the tablet

The laptop and the tablet must have the **same** clip (same file stem). The app looks in its external files directory, which needs no storage permission:

```bash
adb shell mkdir -p /sdcard/Android/data/com.drivingassist.glass/files/sim
adb push perception_engine/data/bdd100k/videos/val/b1ff4656-0435391e.mov /sdcard/Android/data/com.drivingassist.glass/files/sim/
adb shell am start -S -n com.drivingassist.glass/.MainActivity --es ksr.source sim --es ksr.video b1ff4656-0435391e
```

Supported extensions: `.mov`, `.mp4`, `.mkv`. Playback starts once the laptop is ready (5 s at most) and loops. If the file is missing, the screen and the chip say where to push it. The server's `perception.hello.sim.videos` lists the clips the laptop has.

---

## 4. How the data maps

### `VisionData` (`VisionMapper.map`)

| Glass field | From | Rule |
|---|---|---|
| `vehicles` | `world.objects` | Road users (`car`, `truck`, `bus`, `motorcycle`, `bicycle`, `pedestrian`, `rider`) that are visible in the newest frame **and** have a distance. Sorted nearest first; off-screen ones drop out, then at most 8 are kept. `id` = track id, `distanceMeters` = smoothed distance (WorldModel). In LIVE the boxes are `predictedAt(now)`, moved forward from capture time by track velocity. |
| `signs` (lights) | `world.objects` of class `traffic light` | `LIGHT_RED` / `LIGHT_YELLOW` / `LIGHT_GREEN` from the debounced `lightState` (3 consistent frames to change). `UNKNOWN` is not drawn. `id` = track id. |
| `signs` (road signs) | `world.signs` seen within the last 0.5 s | `stop` -> `STOP`, `yield` -> `YIELD`, `speedLimit45` / `speed_limit_45` -> `SPEED_LIMIT_45`, `doNotEnter` -> `DO_NOT_ENTER`, `pedestrianCrossing` -> `PEDESTRIAN_CROSSING`, any other class -> `WARNING`, `unknown` -> dropped. `id` = 100000 + sign id. The Glass UI already replaces `_` with spaces. |
| `lanes` | `world.lanes` (run within the last 1 s) | Ego-lane boundaries become `left` / `right`; the others become `lane_<index>`. When the server sends exactly `laneCount + 1` boundaries, lane `currentLane` (1-based from the left) lies between boundaries `currentLane - 1` and `currentLane`. Otherwise the nearest line on each side of the image centre at the bottom of the frame is used (the lanes block can report extra lines, see `perception_engine/perception/lanes/README.md`). Points are ordered near -> far and clipped to the view (the server extrapolates lines past the image edge). |
| `exitSigns` | none | Always empty: the laptop has no exit-sign detector yet. |
| `time` | `ptsSeconds` | LIVE: seconds since the first uplinked frame (tablet capture clock). SIM: the clip's media time. |

When perception is stale (link down, or no result within 500 ms), every list is empty and the chip says `STALE`. Old markers are never drawn.

### `RouteState` (`BridgeRouteSource`)

| `RouteState` | From `navigation.packet.routeState` |
|---|---|
| `action` | `action` = phase1 `activeManeuver.type`: `GO_STRAIGHT`, `TURN_LEFT`, `TURN_RIGHT`, `KEEP_LEFT`, `KEEP_RIGHT`, `MERGE`, `EXIT_HIGHWAY`, `ARRIVE`, `START_ROUTE`. `AROverlay`'s arrow logic already handles these names (LEFT / RIGHT / STRAIGHT). |
| `audio` | `audio` = phase1 `audioInstructions[0].content`, e.g. "TURN RIGHT in 28 m." |
| `ui` | `ui` = phase1 `spatialInstructions[0].type`: `TURN_ARROW`, `LANE_ARROW`, `EXIT_MARKER`, `DISTANCE_LABEL`, `WARNING` |
| `time` | the packet's `ptsSeconds` (SIM), otherwise seconds since start |

Special states: no packet yet -> `action = WAITING_FOR_ROUTE`, `ui = NONE`. Packet without a route -> `NO_ROUTE`. No packet for 10 s -> the last action is kept, `audio = "Route updates paused"`, `ui = WARNING`. With `navEnabled = false` or in MOCK, the 5 s mock loop runs as before.

The Driving Context (`bridge.context`) also uses the route for lane guidance. It combines phase1 `distanceMeters` and `requiredLane` (free text such as "right", "2" or "2-3"; a JSON number also works) with the perceived `currentLane` / `laneCount`, for example "PREPARE TO MOVE RIGHT | 2 LANES | EXIT 250 400 m". When phase1 gives no required lane, the side is inferred from the turn direction, but only within 300 m of the maneuver. There is no guidance while `offRoute`. Route logic itself stays in phase1: the app only displays phase1's output and combines it with perception.

---

## 5. Coordinates

- The server reports every box, lane point and image size in the **upright** analysed image (PROTOCOL_v2: after the uplink `rotationDegrees`). `VisionMapper` therefore calls `PreviewCoordinates.mapBox/mapPolyline` with **rotation 0** and the upright size from `world.image`, using FILL_CENTER into the view, then clips to 0..1.
- LIVE: `LaptopVisionSource` asks `CameraPreview` for a 16:9 analysis stream at about 1280x720; the preview gets the same 16:9 aspect ratio at CameraX's preview size, so the analysed frame and the preview cover the same field of view (both are 16:9 crops of the sensor) while the preview stays sharp. The Tab S9 screen is 16:10, so FILL_CENTER crops about 5 % from each side; boxes at the image edge can fall off-screen, which is expected. The JPEG stays in sensor orientation with `rotationDegrees` in the uplink header; the laptop rotates it.
- SIM: `PlayerView` uses `RESIZE_MODE_ZOOM`, the same cover-and-crop as FILL_CENTER. The mapping uses the size the laptop analysed, which has the same aspect ratio as the clip.
- **SIM orientation, check once on the Tab S9:** the BDD `.mov` clips are stored as 720x1280 frames with a -90 degree display matrix. The laptop (OpenCV auto-orientation) analyses them as 1280x720, and the tablet shows them as 16:9 landscape only if ExoPlayer applies the rotation on the SurfaceView path (it should; untested on a device). Run SIM with DEBUG on and check that boxes sit on the cars. If the clip shows portrait, or a transcoded clip lost its matrix, push a pre-rotated 1280x720 `.mp4` instead (same stem on both devices).
- `FrameGeometry` is written by `CameraPreview` (LIVE) or `SimVideoBackground` (SIM). A view size of 0 gives empty `VisionData`.
- **Please check in DEBUG (not changed by me):** `PreviewCoordinates.rotateNormalized` maps 90 -> `(y, 1 - x)` and 270 -> `(1 - y, x)`. CameraX `rotationDegrees` means "rotate **clockwise** by this to make the image upright", and a 90° clockwise turn sends buffer `(x, y)` to `(1 - y, x)`. A numeric check with the buffer's top-left pixel gives `(0.75, 0.125)` for the clockwise rule and `(0.25, 0.875)` for the current code. So the 90 and 270 cases look swapped. The perception path never hits this (it always uses rotation 0), but an analyzer that maps raw buffer coordinates on a phone (`rotationDegrees = 90`) would draw mirrored. Confirm on a device in DEBUG before changing it.

---

## 6. Threading and lifecycle

- `PerceptionFactory` runs in the ViewModel initializer on the main thread, as ExoPlayer requires. The start-up does a little main-thread I/O, once: the SharedPreferences read in `onCreate` and, in SIM, `getExternalFilesDir` plus a few `File.isFile` checks for the clip. The LIVE hello (which needs Camera2 characteristics for `focalPx`, binder calls) is built on a background thread.
- `MockDataViewModel.init` calls `visionSource.start()`: the bridge connects and the publisher starts. `onCleared` calls `stop()`: the bridge closes, the scope is cancelled, the player is released and GPS stops. The ViewModel survives rotation (`sensorLandscape` flips), so the socket stays up.
- Host visibility: `PerceptionHostEffects` keeps the screen on (a mounted tablet must not time out: CameraX unbinds on ON_STOP and the uplink would stop) and reports ON_START / ON_STOP to `PerceptionRuntime`. While the activity is stopped the LIVE publisher and the SIM result mapping idle, GPS is paused (resumed on start), and SIM does not auto-play (it starts on resume if the laptop became ready meanwhile). The WebSocket stays open until the ViewModel is cleared, so the tablet stays the laptop's controller while in the background.
- Camera thread (CameraX executor, `STRATEGY_KEEP_ONLY_LATEST`): check `canUplinkNow()`, JPEG encode (a few ms plus about 10 ms), then `offerCameraFrame`, which only enqueues on OkHttp's writer. It never waits on the network and **always** calls `image.close()` in `finally`. Frames without a credit (at most 2 in flight, returned by the answer, a skip, or a 1 s timeout) are dropped and never queued.
- Bridge coroutines run on `Dispatchers.Default`: socket reader, world merge, 50 ms tick (staleness, credits, ping, link status) and the Driving Context. `StateFlow.value` writes are thread-safe; Compose collects them with `collectAsStateWithLifecycle`.
- SIM player access (`currentPosition`, `reportPlayback`, `resultForPts`) happens on `Dispatchers.Main`, as ExoPlayer requires.
- Clock: the bridge uses `SystemClock.elapsedRealtimeNanos`. `PerceptionRuntime.toBridgeClock` converts CameraX timestamps that use the `System.nanoTime` base (sensor timestamp source UNKNOWN), so capture-to-result latency, staleness and prediction all run on the tablet's own clock. No clock sync with the laptop is needed.

## 7. Network and permissions

- `ws://` is cleartext. Android's network security config cannot express IP ranges, and the laptop's LAN IP changes per network while the URL comes from an intent extra. So `network_security_config.xml` permits cleartext at the platform level. The real guard is `PerceptionConfig.isAllowedUrl`: `ws://` is accepted only for loopback, 10/8, 172.16/12, 192.168/16, 169.254/16, 100.64/10, `localhost` and `*.local`; `wss://` is accepted anywhere. Any other URL falls back to the default and the chip shows `URL rejected`. The app makes no other network calls.
- Location is requested only in LIVE with navigation on, and only after the camera permission is settled (Android shows one dialog at a time): granted, or denied (the activity has been in front for a moment with no dialog over it; a denied camera no longer blocks the location request). Denying location is fine: perception keeps working and the route stays `WAITING_FOR_ROUTE`. The Tab S9 **Wi-Fi model has no GPS receiver**. `LocationFeeder` then falls back to the network provider (Wi-Fi positioning, tens of metres) and logs it under tag `LocationFeeder`. With Location switched off (no enabled provider) the chip says `no location provider: turn Location on for live navigation`, and the runtime retries every 5 s, so turning Location on later starts the feed.

## 8. The status chip

Bottom-left, monospace, LIVE / SIM only. The first line shows the source (plus `(saved)` when saved launch values are in use), then either `DISCONNECTED` / `WAITING FOR SERVER` / `TAKEN OVER`, or the wave-1 result fps plus capture-to-result ms (LIVE) or `lead` ms (SIM: how early results arrive before their frame is shown). It adds `STALE` when results are too old, and `NAV ok` / `NAV --` / `NAV STALE` / `NAV off on laptop`. The second line shows, in this order: a problem set in the last 10 s (missing clip, rejected URL, player error, location), the top Driving Context alert as a short label (for example `CLOSE 6 m`, `TOO CLOSE 4 m`, `RED LIGHT 40 m`, `PEDESTRIAN 9 m` or `MOVE RIGHT 2`), an older problem, the takeover note, a server error, a socket error. So a safety alert is never hidden by an old problem for more than 10 s. A player problem clears itself once playback runs again. Colours: mint = OK, amber = warning or alert, red = disconnected.

`TAKEN OVER`: another client (a second tablet, `bridge-cli`, `ws_probe`) sent a newer `client.hello`, so the laptop now serves its camera or clip. The bridge stops uplinking and reporting, shows no results (they belong to the other stream) and takes the session back by itself when the other client disconnects (the server goes idle). `bridge.reclaim()` takes it back immediately.

---

## 9. Fake tablet (`bridge-cli`) and measured results

```bash
gradlew.bat :bridge-cli:installDist
# frames for live mode: 960x540 q80 JPEGs + meta.json (the script is in perception_engine/scripts/)
python scripts/extract_frames.py b1ff4656-0435391e --fps 15 --seconds 20 --out <dir>
bridge-cli\build\install\bridge-cli\bin\bridge-cli.bat live --frames <dir> --fps 15 --trip-states ../perception_engine/nav/demo_sessions/b1ff4656-0435391e/trip_state.jsonl
bridge-cli\build\install\bridge-cli\bin\bridge-cli.bat sim --video-id b1ff4656-0435391e --dump-snapshot
bridge-cli\build\install\bridge-cli\bin\bridge-cli.bat watch --url ws://127.0.0.1:8765/perception
```

Options: `--url`, `--seconds`, `--interval`, `--dump-snapshot` (WorldSnapshot + DrivingContext + navigation + LinkStatus JSON), `--dump-at T`, live `--fps/--loop/--rotation/--focal-px/--mount-height`, sim `--rate/--start/--duration`, navigation `--trip-states FILE [--trip-rebase]`, `--nav-hint`, `--nav-stub`.

Measured on 2026-09-26 against the v2 server on the dev laptop (RTX 5060 Laptop), localhost, BDD clip `b1ff4656-0435391e`, 20 s runs:

| Run | Result |
|---|---|
| SIM + phase1 sim nav | 15.0-17.5 wave-1 results/s, 7.5-9 wave-2 updates/s. Results arrived 118-202 ms **before** their frame was shown (p50), 0 late results. `navigation.packet` about 2 Hz, TURN_RIGHT counting down 108 -> 32 m. Lane guidance and alerts worked (for example "USE RIGHT LANE \| TURN RIGHT", "TOO CLOSE 6 m"). |
| LIVE (15 fps JPEG uplink) + GPS replay (live nav) | Capture -> result p50 54-90 ms, p95 79-157 ms (second run); 290/300 frames sent, 0 skips, 0 credit timeouts. Frames dropped for lack of a credit when the server slowed; the credits worked as designed. Trip states at 1 Hz, each answered by a packet; the route went TURN_RIGHT -> ARRIVE. |
| Light-selection fix | Several light heads at one intersection used to alternate as "nearest" and spoke "Red light ahead" / "Light is green" back and forth: 22 light events in 20 s. The chosen light is now sticky (a new one takes over only when it is 5 m closer): 9 events on the same clip, each a real change. |

Re-measured end to end on the same day after the `inEgoPath` fix, with the laptop on battery power (numbers about 20-30 % lower). Console excerpts, a snapshot dump, sample messages and all numbers are in [`../docs/perception/examples/`](../docs/perception/examples/). SIM + nav: 12.5 results/s, results about 190 ms ahead of display, a result for every displayed frame from 5 s on (the first 1-3 s of a session are slower). LIVE + nav: 114 / 162 ms capture -> result (p50 / p95), 122 / 180 ms through the emulated busy Wi-Fi.

`inEgoPath` fix (server side, `perception_engine/perception/realtime/wire.py` `EgoPath`): the lead vehicle used to come from the lanes block's ego-LANE polygon. That polygon included the parking lane and stopped about 5 m ahead, so parked cars beside the car were "leads" (17 false VEHICLE TOO CLOSE events in 31 s of SIM, median lead 3.9 m) and the real lead at 12 m was not. The flag now uses the vehicle's own corridor: ±1.3 m on flat ground along the ego lane direction, up to 80 m. After the fix: 8 events, median lead 9.4 m.

Tablet numbers (Wi-Fi or USB, on-device JPEG cost) still need to be measured on the Tab S9; no device was attached here.

## 10. Build and test

```bash
# JAVA_HOME = a JDK 17+ (21 used here); local.properties: sdk.dir=<Android SDK> (gitignored)
# SDK packages: platforms;android-35, build-tools;34.0.0
gradlew.bat :perception-bridge:test :bridge-cli:installDist :app:assembleDebug :app:testDebugUnitTest
```

- `:perception-bridge:test` runs 116 JUnit 5 tests. They decode every bundled synthetic fixture **and** every real sample the Python side wrote to `../contracts/samples/` (v1) and `../contracts/samples/v2/` (20 files including `navigation.packet.*` and `uplink_header.example.txt`). `ContractFieldCoverageTest` also re-encodes each real v2 sample and fails if a field is not modelled in Kotlin (apart from a short list of server diagnostics) or if a value changes, e.g. an unknown enum value silently becoming a default. They also cover exact client JSON, uplink header bytes, credits, the sim pts buffer, the wave-2 merge, staleness and navigation-only fallback, reconnect with hello re-send (MockWebServer 4.12), takeover (watcher role: no uplink, foreign results ignored, reclaim when idle), the shared seek rule, navigation packets and staleness, trip states (including a fix without bearing / speed against the schema), and light stickiness.
- `:app:testDebugUnitTest` runs 15 JUnit 4 tests: the mapping to `VisionData`, ego-lane selection, clipping, `RouteState` mapping, the config and URL guard, saved-value precedence and validation, and the three real `perception.frame.wave1.*` samples mapped at the Tab S9's 2560x1600 view. `TabS9FillCenterTest` checks a 1280x720 frame in the 2560x1600 view against an independent FILL_CENTER formula (scale 2.222, 142 px cropped per side, so image columns 64..1216 are visible): boxes, clipping at the crop, fully cropped boxes, lane points, and every vehicle of a real server frame.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`, about 16 MB (not tracked).

---

## 11. What to draw next (ideas for AROverlay)

1. **Traffic-light icon**: `signs` with `LIGHT_RED` / `LIGHT_YELLOW` / `LIGHT_GREEN` labels are currently drawn as amber boxes. Match on the label prefix and draw a coloured lamp instead (red, yellow or green circle plus the distance from `bridge.context.value.trafficLight`).
2. **Warnings from the Driving Context**: `bridge.context.value.activeAlerts` is sorted by priority. Emphasise the top one (for example tint the lead vehicle's reticle red on `VEHICLE_TOO_CLOSE`, or show a pedestrian marker). `following.leadTrackId` identifies which vehicle in `visionData.vehicles` is the lead (same id).
3. **Lane guidance**: `context.laneGuidance.text` / `.action` (`CHANGE_LANE_LEFT/RIGHT`, `KEEP_LANE`) fit next to the existing route arrow.
4. **Speech**: `bridge.events` carries `speech` and `priority`. One collector feeding Android `TextToSpeech`, ordered by priority, gives spoken alerts.
5. **Stale banner**: when `bridge.world.value.perceptionStale`, show "Road alerts paused - navigation only" (the chip already says `STALE`).
6. **Exit signs**: `exitSigns` stays empty until the laptop has an exit-sign detector; phase1's `packet.routeSemantics.exitNumber` is available in `bridge.navigation` if an exit marker should come from the route instead.

To reach the bridge from Compose: `(viewModel.visionSource as? BridgeBacked)?.runtime?.bridge`, or expose what you need from `PerceptionRuntime`.

## 12. Known limitations

- Not run on a Tab S9 yet: the APK builds and the JVM paths are tested against the real server, but camera, ExoPlayer, GPS and the on-device JPEG cost are untested on hardware.
- `focalPx` in the LIVE hello is `focalLength / sensorWidth * bufferWidth` from Camera2, which ignores `LENS_INTRINSIC_CALIBRATION` and assumes the 16:9 stream keeps the full sensor width. `principalPoint` is left to the server (image centre).
- `ImageProxy.cropRect` is ignored (it is the full frame for ImageAnalysis without a ViewPort, which is the case here).
- Sign confidence is not filtered in the overlay; the server's thresholds decide (the Driving Context itself needs 0.5 and 2 sightings before it announces a sign).
