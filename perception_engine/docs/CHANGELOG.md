# Changelog (perception engine and its integration)

## 2026-09-26: integration with the tablet app (branch `integration`, on top of `main` 1f398a9)

The parts built separately now run as one product on the Galaxy Tab S9.

- **One client.** `frontend/` (the tablet app from branch `tom`) now talks to the laptop only through
  `android/perception-bridge` (`PerceptionBridge`, `WorldModel`, `DrivingContextEngine`), included in
  `frontend/settings.gradle.kts` together with `:bridge-cli`. The app's own v2 client and decoder, its
  `spatial.instruction` path, its lead-vehicle rule and its lane heuristics are deleted. Its frames used a leftover
  4-byte tag that the server answered with `perception.skip badHeader`; the bridge sends `SDC1`. The package is now
  `com.drivingassist.spatialcopilot`.
- **Modes.** LIVE (camera uplink under credits, predicted to display time), SIM (Media3 player + `client.playback`,
  `resultForPts`), DEMO (scripted scene; the placeholder "Exit 56" route exists only here). Settings dialog, launch
  extras `perception.source|url|video|debug`, Debug view on a long press.
- **Navigation from phase1 only.** GPS / fused location -> `client.trip_state` (about 1 Hz) in LIVE, media time in SIM.
  The maneuver card, countdown and arrows read `navigation.packet`; degraded states (no GPS, stale or inaccurate fixes,
  route unavailable, stale navigation, off route) are shown and the last route is held.
- **Road arrows.** Placed on the road plane with the frame's `camera` block (same maths as `wire.ground_xz`, checked
  against real frames) along the ego lane (lane lines by the straddle rule, then the lane anchors, then the camera
  axis); they follow phase1's maneuver (lead-in, turn, keep / exit when the side is known, lane change from the Driving
  Context), glide between results and fade in and out. FILL_CENTER mapping for the Tab S9 preview.
- **Relevant vehicles only.** No boxes in the clean view; the lead vehicle is highlighted only in CLOSE / TOO CLOSE and
  only with a measured distance ("Vehicle ahead: 8.4 m", "TOO CLOSE"). New optional
  `DrivingContextConfig.criticalMinEgoSpeedMps` (off by default) holds CRITICAL at CLOSE while the route speed says the
  car is stopped.
- **Voice.** The `docs/audio/` design (brought in from branch `long` with the glasses-listener hardening): catalog
  packaged into the APK, deterministic cue rules, a one-at-a-time arbiter, one AudioTrack with generated earcons.
  TOO CLOSE is spoken once on entry and re-arms only after NORMAL. ElevenLabs through the new laptop proxy
  `POST /tts` + `GET /tts/health` (`perception/realtime/tts_proxy.py`, `--no-tts`, `--tts-allow-lan`, loopback only by
  default, disk cache, spend guard; the key stays in `perception_engine/.env`), then Android TTS, then earcons.
- **phase1 seams.** Google HTTP errors no longer carry the API key (redacted in `http.js` and in `nav_relay.py`);
  `keep-*`, `fork-*` and `ramp-*` maneuvers are no longer read as turns; `spatial/package.json` and the scripts point at
  `spatial/phase1/`.
- **Cleanup.** `android/app-integration/` (glue for the deleted `driving_assist/` app) is retired: its camera encoder,
  GPS feeder, sim player and FILL_CENTER rule now live in `frontend/`. Docs no longer point at `driving_assist/`;
  `frontend/perception_api.md` is a pointer to `contracts/PROTOCOL_v2.md`.
- Tests: `:perception-bridge` 117, `:app` 18 (new), `tests/test_tts_proxy.py` 9/9 (new), protocol 11/11 (offline 7/7),
  ego path 3/3, nav relay 21/21. Run on the Tab S9 (Android 16): SIM, LIVE (with GPS -> phase1) and DEMO.

## Branch `long` (perception engine), 2026-09-25/26

What branch `long` of the AI Spatial Driving Copilot (HackGT 13 project by Long Huynh, Luong Nguyen and Gia Minh Do)
adds on top of its base on `main` (commit `31a22c0`, "Merge pull request #1 from HoangLongCanCode/tom"). Target:
`main`, which has since gained the navigation engine under `spatial/` (`36fef71`) and changed nothing else. Work of
2026-09-25/26; this page is the reviewer's map.

## Summary

- **One folder for the perception side**: everything lives in `perception_engine/`: the Python engine, `contracts/`
  (protocol v2), `docs/`, `nav/` (phase1 relay) and `android/` (the JVM bridge modules the app builds).
- **Laptop perception engine** (`perception_engine/perception/`): seven perception blocks with measured accuracy and
  latency, a two-lane realtime engine, a protocol v2 WebSocket server (video, sim, live, auto), fresh-clone setup
  scripts, pinned weights and data fetchers.
- **Protocol v2** (`perception_engine/contracts/`): spec, 12 JSON Schemas, 19 golden samples from real runs, including
  the Navigation section.
- **phase1 navigation relay** (`perception_engine/nav/`): runs the unmodified navigation engine (`spatial/phase1/` on
  `main`) as a Node child process and forwards `navigation.packet` on the same socket, with three demo sessions.
- **Tablet side**: `perception_engine/android/perception-bridge` (pure Kotlin protocol client, WorldModel, Driving
  Context), `perception_engine/android/bridge-cli` (JVM fake tablet), and a one-switch `MOCK` / `LIVE` / `SIM`
  integration in the app (`driving_assist/app/.../glass/perception/`). `MOCK` is the default and behaves as before.
- **Docs**: `perception_engine/docs/`, `perception_engine/AGENTS.md` + `CLAUDE.md`, and at the repo root `README.md`
  (repo map), `AGENTS.md`, `CLAUDE.md`, `.github/copilot-instructions.md`.

## Added

### perception_engine/contracts/

- `PROTOCOL_v2.md`: the laptop-tablet protocol (transport, modes, `SDC1` camera uplink with credits, all messages,
  Navigation, Sessions, client rules, contract tests).
- `schemas/`: `perception.{hello,frame,update,skip,stats,pong,error}`, `client.{hello,playback,ping,trip_state}`,
  `navigation.packet` (JSON Schema 2020-12, `$id` under `https://hackgt13.local/contracts/`).
- `samples/v2/`: 19 JSON samples plus `uplink_header.example.txt`, written by the real server and relay.
- `perception_frame.v1.schema.json`, `samples/*.json` (v1, kept for compatibility), `README.md`.

### perception_engine/ (engine)

- `perception/` blocks, each with `README.md` + `MODELS.md`: `detection` (YOLO26s-BDD), `tracking` (BoT-SORT + motion /
  TTC), `depth` (DA3 metric + geometry fusion), `lanes` (TwinLiteNet+ + lane state and road geometry), `traffic`
  (Autoware lights + HMM, LISA signs + OCR), `segmentation` (EfficientViT, off in realtime), `openpilot` (experimental).
- `perception/engine.py` (serial `step`, two-lane `fast_step` / `slow_step`, geometry distance, session camera) and
  `perception/config_realtime.yaml`.
- `perception/realtime/`: `server.py` (modes, sessions, roles, credits, sim look-ahead, `NavWorker`, `/health`,
  `/config`), `pipeline.py` (two-lane threads), `wire.py` (builders, `SDC1` header, `EgoPath`), `subscribers.py`
  (`PerceptionBus`), `ws_probe.py` (fake tablet), `bench_lanes.py`, `nav_relay.py`, `README.md`.
- `perception/common/`: `schemas.py`, `video.py`, `paths.py` (folder layout, `PERCEPTION_MODELS_DIR` /
  `PERCEPTION_DATA_DIR` / `PERCEPTION_OUTPUTS_DIR` / `PERCEPTION_THIRD_PARTY_DIR`).
- `nav/`: `phase1_relay.js`, `relay_core.js`, `make_demo_session.js`, `make_contract_samples.js`, `README.md`,
  `demo_sessions/` for the city, highway and night clips.
- `scripts/`: `setup_env.ps1` / `setup_env.sh`, `fetch_third_party.py`, `download_models.py`, `fetch_bdd_samples.py`,
  `verify_env.py`, `extract_frames.py`, `netem_proxy.py`.
- `tools/bdd/`, `tools/kitti/`: range-extraction data tools, pinned sample member list (`sample_members.csv`), sample
  manifests, BDD100K licence notice.
- `tests/`: `test_protocol_v2.py` (7 offline + 4 server tests), `test_nav_relay.py` (21), `test_ego_path.py` (3),
  `fake_nav_relay.py`, `make_golden_samples_v2.py`, `make_golden_samples.py` (v1 golden samples, still works).
- `results/<block>/metrics.json`: the numbers behind each block README.
- `README.md`, `SETUP.md`, `AGENTS.md`, `CLAUDE.md`, `requirements.txt` (97 pins, torch from the cu130 index),
  `.gitignore` (also ignores `android/**/build/`).

### perception_engine/android/ (JVM modules, built from driving_assist/)

- `perception-bridge/` (package `com.drivingassist.copilot.{perception,bridge,context}`): protocol v2 data classes,
  `PerceptionCodec`, `UplinkHeader`, `PerceptionBridge` (credits, reconnect, sim pts buffer, prediction, navigation),
  `WorldModel`, `DrivingContextEngine`, `NavigationMapper`, `DisplayText`; 116 JUnit 5 tests incl. `ProtocolV2Test` and
  `ContractFieldCoverageTest` against `perception_engine/contracts/samples/` (the test task fails if that folder is
  missing, so the sample tests cannot silently skip).
- `bridge-cli/` (package `com.drivingassist.copilot.cli`): `live`, `sim`, `watch` commands driving the same bridge;
  `--dump-snapshot`, `--trip-states`. `:bridge-cli:installDist` writes `android/bridge-cli/build/install/bridge-cli/`.

### perception_engine/docs/

- `OVERVIEW.md`, `INTERFACES.md`, `RUNBOOK.md`, `INTEGRATION_PHASE1.md`, `MODELS_AND_LICENSES.md`, this changelog,
  `PR_DESCRIPTION.md`, and `examples/` (console excerpts, snapshot dump, messages, metrics from the end-to-end runs).

### driving_assist/ (new files)

- `app/src/main/java/com/drivingassist/glass/perception/`: `PerceptionConfig`, `PerceptionFactory`,
  `PerceptionRuntime`, `LaptopVisionSource`, `YuvJpegEncoder`, `SimVisionSource`, `SimVideoBackground`,
  `VisionMapper`, `RouteSource`, `LocationFeeder`, `BridgeStatusChip`.
- `app/src/main/res/xml/network_security_config.xml`; `app/src/test/` (15 JUnit 4 tests: `VisionMapperTest`,
  `RealSampleMappingTest`, `TabS9FillCenterTest`, reading `../perception_engine/contracts/samples/v2/`).
- `PERCEPTION_INTEGRATION.md`: what changed in the app, data mapping, commands, results.

### Repo root

- `README.md` (replaces the UTF-16 placeholder; a neutral map of the three folders and the author credit), `AGENTS.md`
  (repo-wide rules and owners by folder / branch), `CLAUDE.md` and `.github/copilot-instructions.md` (pointers).

## Changed: AR-app files (driving_assist/, branch `tom`)

All additive and backward compatible (`git diff --stat 31a22c0`: 8 files, +174 / -15). In `MOCK` the app builds
`MockDataViewModel()` exactly as before and shows nothing new (the status chip is LIVE / SIM only), and a plain launch
always uses the BuildConfig source.

| File | Change |
|---|---|
| `app/src/main/java/com/drivingassist/glass/MockDataViewModel.kt` | `visionSource` is now public; new optional `routeSource: RouteSource? = null` (`@JvmOverloads` kept); with no route source the 5 s route loop runs unchanged |
| `app/src/main/java/com/drivingassist/glass/MainActivity.kt` | Loads `PerceptionConfig`, builds the ViewModel through `PerceptionFactory`; SIM shows `SimVideoBackground` instead of `CameraPreview`; passes `targetResolution`; in LIVE / SIM only, adds `BridgeStatusChip` and `PerceptionHostEffects` (screen on, lifecycle, location permission). Layout and `AROverlay` untouched |
| `app/src/main/java/com/drivingassist/glass/CameraPreview.kt` | Optional `targetResolution: Size? = null`: an analysis `ResolutionSelector` plus the same aspect ratio for the preview (same field of view, full preview sharpness); null applies no selector. A `DisplayListener` keeps `targetRotation` of both use cases right on 180-degree flips |
| `app/src/main/AndroidManifest.xml` | `INTERNET`, fine/coarse location, optional location/GPS features, `networkSecurityConfig`; `sensorLandscape` kept |
| `app/build.gradle.kts` | `buildConfig` on; `PERCEPTION_*` build fields from `-Pperception.*`; `:perception-bridge`, OkHttp 4.12.0, Media3 1.5.1, JUnit 4.13.2 (test). compileSdk, Compose and package unchanged |
| `build.gradle.kts` | Kotlin JVM and serialization plugins 2.0.21, `apply false` |
| `settings.gradle.kts` | `include(":perception-bridge")`, `include(":bridge-cli")` with `projectDir` = `../perception_engine/android/<module>` |
| `README.md` | Appended a short "Perception bridge" section (per-launch extras, `perception.persist`, `perception.reset`) |

Not modified: `AROverlay.kt`, `Models.kt`, `VisionSource.kt`, `PreviewCoordinates.kt`, `ENGINEER_A.md`. Nothing under
`spatial/` was modified (the relay only `require()`s it).

## Reorganisation and naming (2026-09-26, before the merge)

- **One folder**: `contracts/` -> `perception_engine/contracts/`, `docs/perception/` -> `perception_engine/docs/`,
  `driving_assist/perception-bridge` and `driving_assist/bridge-cli` -> `perception_engine/android/`, the old root
  `AGENTS.md` -> `perception_engine/AGENTS.md` (moved with `git mv`, history kept). Gradle includes the two modules
  from their new place; every test, script and doc path follows.
- **Project-neutral identifiers**: Kotlin packages `com.drivingassist.copilot.*`; uplink magic `SDC1` (same 24-byte
  layout, `headerVersion` 1); env vars `PERCEPTION_MODELS_DIR` / `PERCEPTION_DATA_DIR` / `PERCEPTION_OUTPUTS_DIR` /
  `PERCEPTION_THIRD_PARTY_DIR`; app launch extras and Gradle properties `perception.*` (`perception.source`,
  `perception.url`, ...), BuildConfig fields `PERCEPTION_*`, SharedPreferences file `perception_prefs`; schema `$id`s
  under `https://hackgt13.local/`. Golden samples and the example captures were regenerated from real runs afterwards.
- **Navigation engine location**: the relay (`nav/relay_core.js`, `perception/realtime/nav_relay.py`) resolves
  `--phase1-dir` > env `PHASE1_DIR` > `<repo>/spatial` > a legacy `src/phase1` layout (repo root, then
  `../hackgt13-phase1`); explicit folders may use either layout. Only `spatial/phase1/` and
  `spatial/scripts/load-env.js` are loaded. A new test covers both layouts in Python and Node.

## Fixes and behaviour decisions made on this branch

- **`inEgoPath`** (server, `wire.py` `EgoPath`): the ego-lane polygon included parking lanes and ended about 5 m ahead,
  so parked cars became the lead. Now a +-1.3 m corridor along the ego lane direction up to 80 m. City clip: false
  `VEHICLE_TOO_CLOSE` 17 -> 8-9 per 31 s; median lead 3.9 m -> about 9 m. Golden samples regenerated.
- **Traffic-light stickiness** (Kotlin Driving Context): the chosen light changes only when another is 5 m closer;
  light events 22 -> 9 in 20 s.
- **Sim look-ahead** was multiplied by the playback rate twice (rate-2 lead 422 ms); now tuned in media seconds.
- **Sim margin** default raised from 0.06 to 0.10 s (late results over emulated busy Wi-Fi 4.9 % -> 1.5 %).
- **FastAPI WebSocket 403**: `WebSocket` must be imported at module level with `from __future__ import annotations`.
- **`maxInFlight`** default kept at 2 after measuring 1, 2 and 3 over USB, busy Wi-Fi and hotspot emulation.
- **Portable paths**: `perception/common/paths.py` plus env overrides; no absolute paths in code. The realtime server,
  `ws_probe`, `bench_lanes`, `subscribers` and the tests follow `PERCEPTION_DATA_DIR` (`resolve_data_path`); the
  detection and tracking eval writers record `<models>/...`, `<data>/...`, `<outputs>/...` (`portable_paths`), and the
  committed `results/detection` and `results/tracking` metrics were rewritten that way.

### Review fixes (2026-09-26, after the adversarial reviews)

- **Takeover (Kotlin bridge)**: `perception.hello.role` is modelled. A watcher bridge stops uplinking, playback reports
  and GPS, ignores the other controller's results (their echo ids no longer release its credits or feed its latency),
  lets its world go stale, shows `TAKEN OVER`, and re-sends its hello when the server goes idle (`reclaim()` does it
  at once). `serverError` stays until a hello of another session.
- **Server sessions**: a mid-session change of the upright uplink frame size starts a new session (new `sessionId`,
  `ptsSeconds` from 0), as the spec said; implicit re-promotion of a taken-over client keeps the camera of its last
  live hello; a controller asking for a clip the laptop lacks ends the old session (one `unknownVideo`, playback
  reports re-check every 5 s instead of rescanning and erroring at 10 Hz); `rate` <= 0 is paused (was coerced to 1).
- **Uplink**: a binary message of 24 bytes or more with a wrong magic is answered with `perception.skip badHeader`.
- **Navigation**: `client.trip_state` only from the controller (or a hello with `navigation.mode: "live"`), validated
  (`badMessage`), null heading / speed read as 0; 3 relay calls without a packet set `navigation.available: false`,
  re-announce the hello and send one `perception.error internal` (cleared by the next packet; `NavRelay.last_error`).
  The Kotlin `ClientTripState` sends 0 instead of null for a missing bearing / speed.
- **Stalled peers**: a send that cannot complete in 10 s evicts the client (transport aborted, removed from stats and
  `/health`, session ended if it was the controller).
- **Seek rule**: the Kotlin `PlaybackClock` uses the server's 0.6 s x max(1, rate).
- **App**: launch extras apply to one launch unless `perception.persist` (validated, chip `(saved)`); MOCK shows no
  chip; the screen stays on in LIVE / SIM; target rotation follows 180-degree flips; the preview is no longer capped at
  1280x720; the LIVE hello (Camera2 binder calls) is built off the main thread; loops idle and GPS pauses while the
  activity is stopped, and SIM does not auto-play in the background; problems no longer hide safety alerts for more
  than 10 s and player errors clear themselves; a non-YUV camera format is reported instead of silently stopping the
  uplink; the location request no longer waits forever on a denied camera, Location switched on later is picked up
  (5 s retry), and the passive provider is not used; one copy per uplinked frame instead of three.
- **Docs**: spec corrections (idle mode, credits on a session change, the rules above), start-up 30-60 s, the phase1
  captured-session command runs from the engine folder (its `.env`), PowerShell reproduce commands,
  `extract_frames.py` help text; a duplicate setup reference with absolute dev paths was removed (superseded by
  `SETUP.md`).

## Protocol

- Protocol v2 (`protocolVersion` 2, `schemaVersion` 2). v2 keeps every v1 `perception.frame` field; the Kotlin decoder
  still reads schemaVersion 1 frames.
- Additive over the first v2 draft: hello `serverTimeMs`, `acceptedModes`, `role`, `sourceFps`, `targetHz`,
  `classes`, `staticIdOffset`, `navigation`, `safety`, `sim.lookaheadMode` / `videoId`; frame `distanceAgeMs`,
  `distanceMethod: geometry`, always-present `lanes` / `road`; update `sessionId`, `camera`; skip `sessionId`,
  `serverTimeMs`; stats diagnostics; the Sessions rules; `navigation.packet` and `client.trip_state`.
- The uplink magic is `SDC1` (4 ASCII bytes at offset 0 of the 24-byte header); nothing deployed uses another value.

## Parallel additions in the same working tree

`perception/realtime/glasses_server.py`, `glasses_wire.py`, `tests/test_glasses_server.py` (a separate port-8000
listener for a glasses app) and `docs/audio/` were added by a separate change while this reorganisation ran; they
are described in `perception/realtime/README.md` and in their own files, not on this page. They appear in the file
list below only because they are staged with the rest of `perception_engine/`.

## Known issue outside this branch

`spatial/scripts/phase1-demo-lib.js` and `spatial/scripts/process-captured-session.js` on `main` still
`require('../src/phase1')`, and `spatial/package.json` `main` is `src/phase1/index.js` (paths from before the move to
`spatial/`), so those scripts fail with "Cannot find module". Reported, not changed here; the relay does not use them.

## Before merging

Confirm that nothing gitignored is staged: `models/`, `data/`, `outputs/`, `perception/third_party/`, `.venv/`,
`build/`, `*.apk`, `local.properties`, `.env` (there is no root `.gitignore`; each folder has its own).

## Full file list

<!-- FILE LIST -->
