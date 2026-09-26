# Changelog: branch `perception-engine`

What the `perception-engine` branch of the AI Spatial Driving Copilot adds on top of `main` (commit `31a22c0`, "Merge
pull request #1 from HoangLongCanCode/tom"). Work by the Knuckle Sandwich Robotics Inc. (KSR) perception team,
2026-09-25/26. Nothing on this branch is committed yet; this page is the reviewer's map.

## Summary

- **Laptop perception engine** (`perception_engine/`): seven perception blocks with measured accuracy and latency, a
  two-lane realtime engine, a protocol v2 WebSocket server (video, sim, live, auto), fresh-clone setup scripts, pinned
  weights and data fetchers.
- **Protocol v2** (`contracts/`): spec, 12 JSON Schemas, 19 golden samples from real runs, including the Navigation
  section.
- **phase1 navigation relay** (`perception_engine/nav/`): runs the unmodified phase1 route engine as a Node child
  process and forwards `navigation.packet` on the same socket, with three demo sessions.
- **Tablet side** (`driving_assist/`): `perception-bridge` (pure Kotlin protocol client, WorldModel, Driving Context),
  `bridge-cli` (JVM fake tablet), and a one-switch `MOCK` / `LIVE` / `SIM` integration in the app. `MOCK` is the
  default and behaves as before.
- **Docs**: root `README.md`, `AGENTS.md`, `CLAUDE.md`, `.github/copilot-instructions.md`, `docs/perception/`.

## Added

### contracts/ (new folder)

- `PROTOCOL_v2.md`: the laptop-tablet protocol (transport, modes, KSR1 camera uplink with credits, all messages,
  Navigation, Sessions, client rules, contract tests).
- `schemas/`: `perception.{hello,frame,update,skip,stats,pong,error}`, `client.{hello,playback,ping,trip_state}`,
  `navigation.packet` (JSON Schema 2020-12).
- `samples/v2/`: 19 JSON samples plus `uplink_header.example.txt`, written by the real server and relay.
- `perception_frame.v1.schema.json`, `samples/*.json` (v1, kept for compatibility), `README.md`.

### perception_engine/ (new folder)

- `perception/` blocks, each with `README.md` + `MODELS.md`: `detection` (YOLO26s-BDD), `tracking` (BoT-SORT + motion /
  TTC), `depth` (DA3 metric + geometry fusion), `lanes` (TwinLiteNet+ + lane state and road geometry), `traffic`
  (Autoware lights + HMM, LISA signs + OCR), `segmentation` (EfficientViT, off in realtime), `openpilot` (experimental).
- `perception/engine.py` (serial `step`, two-lane `fast_step` / `slow_step`, geometry distance, session camera) and
  `perception/config_realtime.yaml`.
- `perception/realtime/`: `server.py` (modes, sessions, roles, credits, sim look-ahead, `NavWorker`, `/health`,
  `/config`), `pipeline.py` (two-lane threads), `wire.py` (builders, KSR1 header, `EgoPath`), `subscribers.py`
  (`PerceptionBus`), `ws_probe.py` (fake tablet), `bench_lanes.py`, `nav_relay.py`, `README.md`.
- `perception/common/`: `schemas.py`, `video.py`, `paths.py` (folder layout, `KSR_MODELS_DIR` / `KSR_DATA_DIR` /
  `KSR_OUTPUTS_DIR` / `KSR_THIRD_PARTY_DIR`).
- `nav/`: `phase1_relay.js`, `relay_core.js`, `make_demo_session.js`, `make_contract_samples.js`, `README.md`,
  `demo_sessions/` for the city, highway and night clips.
- `scripts/`: `setup_env.ps1` / `setup_env.sh`, `fetch_third_party.py`, `download_models.py`, `fetch_bdd_samples.py`,
  `verify_env.py`, `extract_frames.py`, `netem_proxy.py`.
- `tools/bdd/`, `tools/kitti/`: range-extraction data tools, pinned sample member list (`sample_members.csv`), sample
  manifests, BDD100K licence notice.
- `tests/`: `test_protocol_v2.py` (7 offline + 4 server tests), `test_nav_relay.py` (20), `test_ego_path.py` (3),
  `fake_nav_relay.py`, `make_golden_samples_v2.py`, `make_golden_samples.py` (v1 golden samples, still works).
- `results/<block>/metrics.json`: the numbers behind each block README.
- `README.md`, `SETUP.md`, `requirements.txt` (97 pins, torch from the cu130 index), `.gitignore`.

### driving_assist/ (new modules and files)

- `perception-bridge/`: protocol v2 data classes, `PerceptionCodec`, `UplinkHeader`, `PerceptionBridge` (credits,
  reconnect, sim pts buffer, prediction, navigation), `WorldModel`, `DrivingContextEngine`, `NavigationMapper`,
  `DisplayText`; 116 JUnit 5 tests incl. `ProtocolV2Test` and `ContractFieldCoverageTest` against `contracts/samples/`.
- `bridge-cli/`: `live`, `sim`, `watch` commands driving the same bridge; `--dump-snapshot`, `--trip-states`.
- `app/src/main/java/com/drivingassist/glass/perception/`: `PerceptionConfig`, `PerceptionFactory`, `PerceptionRuntime`,
  `LaptopVisionSource`, `YuvJpegEncoder`, `SimVisionSource`, `SimVideoBackground`, `VisionMapper`, `RouteSource`,
  `LocationFeeder`, `BridgeStatusChip`.
- `app/src/main/res/xml/network_security_config.xml`; `app/src/test/` (15 JUnit 4 tests: `VisionMapperTest`,
  `RealSampleMappingTest`, `TabS9FillCenterTest`).
- `PERCEPTION_INTEGRATION.md`: what changed in the app, data mapping, commands, results.

### Repo root and docs

- `README.md` (replaces the UTF-16 placeholder), `AGENTS.md`, `CLAUDE.md`, `.github/copilot-instructions.md`.
- `docs/perception/`: `OVERVIEW.md`, `INTERFACES.md`, `RUNBOOK.md`, `INTEGRATION_PHASE1.md`, `MODELS_AND_LICENSES.md`,
  this changelog, `PR_DESCRIPTION.md`, and `examples/` (console excerpts, snapshot dump, messages, metrics from the
  end-to-end runs).

## Changed: files owned by others

Tom's app files (`driving_assist/`), all additive and backward compatible (`git diff --stat`: 8 files, +170 / -15). In
`MOCK` the app builds `MockDataViewModel()` exactly as before and shows nothing new (the status chip is LIVE / SIM
only), and a plain launch always uses the BuildConfig source.

| File | Change |
|---|---|
| `app/src/main/java/com/drivingassist/glass/MockDataViewModel.kt` | `visionSource` is now public; new optional `routeSource: RouteSource? = null` (`@JvmOverloads` kept); with no route source the 5 s route loop runs unchanged |
| `app/src/main/java/com/drivingassist/glass/MainActivity.kt` | Loads `PerceptionConfig`, builds the ViewModel through `PerceptionFactory`; SIM shows `SimVideoBackground` instead of `CameraPreview`; passes `targetResolution`; in LIVE / SIM only, adds `BridgeStatusChip` and `PerceptionHostEffects` (screen on, lifecycle, location permission). Layout and `AROverlay` untouched |
| `app/src/main/java/com/drivingassist/glass/CameraPreview.kt` | Optional `targetResolution: Size? = null`: an analysis `ResolutionSelector` plus the same aspect ratio for the preview (same field of view, full preview sharpness); null applies no selector. A `DisplayListener` keeps `targetRotation` of both use cases right on 180-degree flips |
| `app/src/main/AndroidManifest.xml` | `INTERNET`, fine/coarse location, optional location/GPS features, `networkSecurityConfig`; `sensorLandscape` kept |
| `app/build.gradle.kts` | `buildConfig` on; `KSR_*` build fields from `-Pksr.*`; `:perception-bridge`, OkHttp 4.12.0, Media3 1.5.1, JUnit 4.13.2 (test). compileSdk, Compose and package unchanged |
| `build.gradle.kts` | Kotlin JVM and serialization plugins 2.0.21, `apply false` |
| `settings.gradle.kts` | `include(":perception-bridge")`, `include(":bridge-cli")` |
| `README.md` | Appended a short "Perception bridge" section (per-launch extras, `ksr.persist`, `ksr.reset`) |

Not modified: `AROverlay.kt`, `Models.kt`, `VisionSource.kt`, `PreviewCoordinates.kt`, `ENGINEER_A.md`. Nothing on the
`phase1` branch was modified (the relay only `require()`s it).

Other shared files: the root `README.md` (Tom's one-line UTF-16 `# hackgt13` placeholder from the first commit) was
replaced by the project README.

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
  `ws_probe`, `bench_lanes`, `subscribers` and the tests follow `KSR_DATA_DIR` (`resolve_data_path`); the detection
  and tracking eval writers record `<models>/...`, `<data>/...`, `<outputs>/...` (`portable_paths`), and the committed
  `results/detection` and `results/tracking` metrics were rewritten that way.

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
- **App**: launch extras apply to one launch unless `ksr.persist` (validated, chip `(saved)`); MOCK shows no chip;
  the screen stays on in LIVE / SIM; target rotation follows 180-degree flips; the preview is no longer capped at
  1280x720; the LIVE hello (Camera2 binder calls) is built off the main thread; loops idle and GPS pauses while the
  activity is stopped, and SIM does not auto-play in the background; problems no longer hide safety alerts for more
  than 10 s and player errors clear themselves; a non-YUV camera format is reported instead of silently stopping the
  uplink; the location request no longer waits forever on a denied camera, Location switched on later is picked up
  (5 s retry), and the passive provider is not used; one copy per uplinked frame instead of three.
- **Docs**: spec corrections (idle mode, credits on a session change, the rules above), start-up 30-60 s, the phase1
  captured-session command runs from the phase1 checkout (its `.env`), PowerShell reproduce commands,
  `extract_frames.py` help text; the duplicate `perception_engine/ENV_SETUP_reference.md` (absolute dev paths, a copy
  of the dev tree's `ENV_SETUP.md`, superseded by `SETUP.md`) was removed from the branch.

## Protocol

- Protocol v2 (`protocolVersion` 2, `schemaVersion` 2). v2 keeps every v1 `perception.frame` field; the Kotlin decoder
  still reads schemaVersion 1 frames.
- Additive over the first v2 draft: hello `serverTimeMs`, `acceptedModes`, `role`, `sourceFps`, `targetHz`,
  `classes`, `staticIdOffset`, `navigation`, `safety`, `sim.lookaheadMode` / `videoId`; frame `distanceAgeMs`,
  `distanceMethod: geometry`, always-present `lanes` / `road`; update `sessionId`, `camera`; skip `sessionId`,
  `serverTimeMs`; stats diagnostics; the Sessions rules; `navigation.packet` and `client.trip_state`.

## Before merging

Confirm that nothing gitignored is staged: `models/`, `data/`, `outputs/`, `perception/third_party/`, `.venv/`,
`build/`, `*.apk`, `local.properties`, `.env` (there is no root `.gitignore` until phase1 merges).

## Full file list

Status against `main` (`A` = new, `M` = modified), generated with `git status --short -uall` (gitignored files excluded).

292 files: 283 new, 9 modified.

<details><summary><code>.github</code> (1)</summary>

```text
A .github/copilot-instructions.md
```

</details>

<details><summary><code>(repo root)</code> (3)</summary>

```text
A AGENTS.md
A CLAUDE.md
M README.md
```

</details>

<details><summary><code>contracts</code> (38)</summary>

```text
A contracts/PROTOCOL_v2.md
A contracts/README.md
A contracts/perception_frame.v1.schema.json
A contracts/samples/perception_frame.city_b1ff4656-0435391e.json
A contracts/samples/perception_frame.highway_b1f4491b-cf446195.json
A contracts/samples/perception_frame.night_b23adb0d-8a7aaced.json
A contracts/samples/v2/client.hello.live.json
A contracts/samples/v2/client.hello.sim.json
A contracts/samples/v2/client.ping.json
A contracts/samples/v2/client.playback.json
A contracts/samples/v2/client.trip_state.json
A contracts/samples/v2/navigation.packet.live.json
A contracts/samples/v2/navigation.packet.sim_city.json
A contracts/samples/v2/navigation.packet.sim_highway.json
A contracts/samples/v2/perception.error.json
A contracts/samples/v2/perception.frame.wave1.city.json
A contracts/samples/v2/perception.frame.wave1.live.json
A contracts/samples/v2/perception.frame.wave1.night.json
A contracts/samples/v2/perception.hello.live.json
A contracts/samples/v2/perception.hello.sim.json
A contracts/samples/v2/perception.hello.video.json
A contracts/samples/v2/perception.pong.json
A contracts/samples/v2/perception.skip.json
A contracts/samples/v2/perception.stats.json
A contracts/samples/v2/perception.update.wave2.city.json
A contracts/samples/v2/uplink_header.example.txt
A contracts/schemas/client.hello.schema.json
A contracts/schemas/client.ping.schema.json
A contracts/schemas/client.playback.schema.json
A contracts/schemas/client.trip_state.schema.json
A contracts/schemas/navigation.packet.schema.json
A contracts/schemas/perception.error.schema.json
A contracts/schemas/perception.frame.schema.json
A contracts/schemas/perception.hello.schema.json
A contracts/schemas/perception.pong.schema.json
A contracts/schemas/perception.skip.schema.json
A contracts/schemas/perception.stats.schema.json
A contracts/schemas/perception.update.schema.json
```

</details>

<details><summary><code>docs</code> (13)</summary>

```text
A docs/perception/CHANGELOG_perception-engine.md
A docs/perception/INTEGRATION_PHASE1.md
A docs/perception/INTERFACES.md
A docs/perception/MODELS_AND_LICENSES.md
A docs/perception/OVERVIEW.md
A docs/perception/PR_DESCRIPTION.md
A docs/perception/RUNBOOK.md
A docs/perception/examples/README.md
A docs/perception/examples/live_nav_console.txt
A docs/perception/examples/messages.json
A docs/perception/examples/metrics.json
A docs/perception/examples/sim_nav_console.txt
A docs/perception/examples/snapshot_live.json
```

</details>

<details><summary><code>driving_assist</code> (87)</summary>

```text
A driving_assist/PERCEPTION_INTEGRATION.md
M driving_assist/README.md
M driving_assist/app/build.gradle.kts
M driving_assist/app/src/main/AndroidManifest.xml
M driving_assist/app/src/main/java/com/drivingassist/glass/CameraPreview.kt
M driving_assist/app/src/main/java/com/drivingassist/glass/MainActivity.kt
M driving_assist/app/src/main/java/com/drivingassist/glass/MockDataViewModel.kt
A driving_assist/app/src/main/java/com/drivingassist/glass/perception/BridgeStatusChip.kt
A driving_assist/app/src/main/java/com/drivingassist/glass/perception/LaptopVisionSource.kt
A driving_assist/app/src/main/java/com/drivingassist/glass/perception/LocationFeeder.kt
A driving_assist/app/src/main/java/com/drivingassist/glass/perception/PerceptionConfig.kt
A driving_assist/app/src/main/java/com/drivingassist/glass/perception/PerceptionFactory.kt
A driving_assist/app/src/main/java/com/drivingassist/glass/perception/PerceptionRuntime.kt
A driving_assist/app/src/main/java/com/drivingassist/glass/perception/RouteSource.kt
A driving_assist/app/src/main/java/com/drivingassist/glass/perception/SimVideoBackground.kt
A driving_assist/app/src/main/java/com/drivingassist/glass/perception/SimVisionSource.kt
A driving_assist/app/src/main/java/com/drivingassist/glass/perception/VisionMapper.kt
A driving_assist/app/src/main/java/com/drivingassist/glass/perception/YuvJpegEncoder.kt
A driving_assist/app/src/main/res/xml/network_security_config.xml
A driving_assist/app/src/test/java/com/drivingassist/glass/perception/RealSampleMappingTest.kt
A driving_assist/app/src/test/java/com/drivingassist/glass/perception/TabS9FillCenterTest.kt
A driving_assist/app/src/test/java/com/drivingassist/glass/perception/VisionMapperTest.kt
A driving_assist/bridge-cli/build.gradle.kts
A driving_assist/bridge-cli/src/main/kotlin/com/ksr/copilot/cli/Console.kt
A driving_assist/bridge-cli/src/main/kotlin/com/ksr/copilot/cli/FakeTablet.kt
A driving_assist/bridge-cli/src/main/kotlin/com/ksr/copilot/cli/JpegInfo.kt
A driving_assist/bridge-cli/src/main/kotlin/com/ksr/copilot/cli/Main.kt
M driving_assist/build.gradle.kts
A driving_assist/perception-bridge/build.gradle.kts
A driving_assist/perception-bridge/src/main/kotlin/com/ksr/copilot/bridge/BridgeConfig.kt
A driving_assist/perception-bridge/src/main/kotlin/com/ksr/copilot/bridge/DisplayText.kt
A driving_assist/perception-bridge/src/main/kotlin/com/ksr/copilot/bridge/FlowControl.kt
A driving_assist/perception-bridge/src/main/kotlin/com/ksr/copilot/bridge/NavigationUpdate.kt
A driving_assist/perception-bridge/src/main/kotlin/com/ksr/copilot/bridge/PerceptionBridge.kt
A driving_assist/perception-bridge/src/main/kotlin/com/ksr/copilot/context/DrivingContextConfig.kt
A driving_assist/perception-bridge/src/main/kotlin/com/ksr/copilot/context/DrivingContextEngine.kt
A driving_assist/perception-bridge/src/main/kotlin/com/ksr/copilot/context/DrivingTypes.kt
A driving_assist/perception-bridge/src/main/kotlin/com/ksr/copilot/context/NavigationMapper.kt
A driving_assist/perception-bridge/src/main/kotlin/com/ksr/copilot/context/RobustFit.kt
A driving_assist/perception-bridge/src/main/kotlin/com/ksr/copilot/context/WorldModel.kt
A driving_assist/perception-bridge/src/main/kotlin/com/ksr/copilot/context/WorldPrediction.kt
A driving_assist/perception-bridge/src/main/kotlin/com/ksr/copilot/context/WorldSnapshot.kt
A driving_assist/perception-bridge/src/main/kotlin/com/ksr/copilot/perception/ClientMessages.kt
A driving_assist/perception-bridge/src/main/kotlin/com/ksr/copilot/perception/LenientSerializers.kt
A driving_assist/perception-bridge/src/main/kotlin/com/ksr/copilot/perception/Messages.kt
A driving_assist/perception-bridge/src/main/kotlin/com/ksr/copilot/perception/PerceptionCodec.kt
A driving_assist/perception-bridge/src/main/kotlin/com/ksr/copilot/perception/PerceptionFrame.kt
A driving_assist/perception-bridge/src/main/kotlin/com/ksr/copilot/perception/PerceptionSource.kt
A driving_assist/perception-bridge/src/main/kotlin/com/ksr/copilot/perception/ReplayPerceptionSource.kt
A driving_assist/perception-bridge/src/main/kotlin/com/ksr/copilot/perception/UplinkHeader.kt
A driving_assist/perception-bridge/src/test/kotlin/com/ksr/copilot/bridge/FlowControlTest.kt
A driving_assist/perception-bridge/src/test/kotlin/com/ksr/copilot/bridge/PerceptionBridgeTest.kt
A driving_assist/perception-bridge/src/test/kotlin/com/ksr/copilot/context/DrivingContextEngineTest.kt
A driving_assist/perception-bridge/src/test/kotlin/com/ksr/copilot/context/LightSelectionTest.kt
A driving_assist/perception-bridge/src/test/kotlin/com/ksr/copilot/context/NavigationMapperTest.kt
A driving_assist/perception-bridge/src/test/kotlin/com/ksr/copilot/context/TestFrames.kt
A driving_assist/perception-bridge/src/test/kotlin/com/ksr/copilot/context/WaveMergeAndStalenessTest.kt
A driving_assist/perception-bridge/src/test/kotlin/com/ksr/copilot/context/WorldModelTest.kt
A driving_assist/perception-bridge/src/test/kotlin/com/ksr/copilot/perception/ContractFieldCoverageTest.kt
A driving_assist/perception-bridge/src/test/kotlin/com/ksr/copilot/perception/ProtocolV2Test.kt
A driving_assist/perception-bridge/src/test/kotlin/com/ksr/copilot/perception/SampleDecodeTest.kt
A driving_assist/perception-bridge/src/test/resources/samples/synthetic_frame_city.json
A driving_assist/perception-bridge/src/test/resources/samples/synthetic_frame_minimal.json
A driving_assist/perception-bridge/src/test/resources/samples/synthetic_hello.json
A driving_assist/perception-bridge/src/test/resources/samples/synthetic_stats.json
A driving_assist/perception-bridge/src/test/resources/samples/v2/client_hello_live.json
A driving_assist/perception-bridge/src/test/resources/samples/v2/client_hello_live_nav.json
A driving_assist/perception-bridge/src/test/resources/samples/v2/client_hello_sim.json
A driving_assist/perception-bridge/src/test/resources/samples/v2/client_ping.json
A driving_assist/perception-bridge/src/test/resources/samples/v2/client_playback.json
A driving_assist/perception-bridge/src/test/resources/samples/v2/client_trip_state.json
A driving_assist/perception-bridge/src/test/resources/samples/v2/error.json
A driving_assist/perception-bridge/src/test/resources/samples/v2/frame_live_wave1.json
A driving_assist/perception-bridge/src/test/resources/samples/v2/navigation_packet_live.json
A driving_assist/perception-bridge/src/test/resources/samples/v2/navigation_packet_no_route.json
A driving_assist/perception-bridge/src/test/resources/samples/v2/navigation_packet_numeric_lane.json
A driving_assist/perception-bridge/src/test/resources/samples/v2/navigation_packet_sim.json
A driving_assist/perception-bridge/src/test/resources/samples/v2/pong.json
A driving_assist/perception-bridge/src/test/resources/samples/v2/pong_no_client_time.json
A driving_assist/perception-bridge/src/test/resources/samples/v2/server_hello_live.json
A driving_assist/perception-bridge/src/test/resources/samples/v2/server_hello_sim.json
A driving_assist/perception-bridge/src/test/resources/samples/v2/skip.json
A driving_assist/perception-bridge/src/test/resources/samples/v2/stats.json
A driving_assist/perception-bridge/src/test/resources/samples/v2/update_depth_only.json
A driving_assist/perception-bridge/src/test/resources/samples/v2/update_wave2.json
A driving_assist/perception-bridge/src/test/resources/samples/v2/uplink_header.python_format.txt
M driving_assist/settings.gradle.kts
```

</details>

<details><summary><code>perception_engine</code> (150)</summary>

```text
A perception_engine/.gitignore
A perception_engine/README.md
A perception_engine/SETUP.md
A perception_engine/nav/README.md
A perception_engine/nav/demo_sessions/b1f4491b-cf446195/route.json
A perception_engine/nav/demo_sessions/b1f4491b-cf446195/session_manifest.json
A perception_engine/nav/demo_sessions/b1f4491b-cf446195/trip_state.jsonl
A perception_engine/nav/demo_sessions/b1ff4656-0435391e/route.json
A perception_engine/nav/demo_sessions/b1ff4656-0435391e/session_manifest.json
A perception_engine/nav/demo_sessions/b1ff4656-0435391e/trip_state.jsonl
A perception_engine/nav/demo_sessions/b23adb0d-8a7aaced/route.json
A perception_engine/nav/demo_sessions/b23adb0d-8a7aaced/session_manifest.json
A perception_engine/nav/demo_sessions/b23adb0d-8a7aaced/trip_state.jsonl
A perception_engine/nav/make_contract_samples.js
A perception_engine/nav/make_demo_session.js
A perception_engine/nav/phase1_relay.js
A perception_engine/nav/relay_core.js
A perception_engine/perception/__init__.py
A perception_engine/perception/common/__init__.py
A perception_engine/perception/common/paths.py
A perception_engine/perception/common/schemas.py
A perception_engine/perception/common/video.py
A perception_engine/perception/config_realtime.yaml
A perception_engine/perception/depth/MODELS.md
A perception_engine/perception/depth/README.md
A perception_engine/perception/depth/__init__.py
A perception_engine/perception/depth/backends.py
A perception_engine/perception/depth/check_mot_alignment.py
A perception_engine/perception/depth/demo_clip.py
A perception_engine/perception/depth/distance.py
A perception_engine/perception/depth/eval_bdd.py
A perception_engine/perception/depth/eval_kitti.py
A perception_engine/perception/depth/geometry.py
A perception_engine/perception/depth/test_geometry.py
A perception_engine/perception/detection/MODELS.md
A perception_engine/perception/detection/README.md
A perception_engine/perception/detection/__init__.py
A perception_engine/perception/detection/bench_latency.py
A perception_engine/perception/detection/detector.py
A perception_engine/perception/detection/eval_bdd.py
A perception_engine/perception/detection/make_videos.py
A perception_engine/perception/detection/plots.py
A perception_engine/perception/detection/review_check.py
A perception_engine/perception/detection/viz.py
A perception_engine/perception/engine.py
A perception_engine/perception/lanes/MODELS.md
A perception_engine/perception/lanes/README.md
A perception_engine/perception/lanes/__init__.py
A perception_engine/perception/lanes/backends.py
A perception_engine/perception/lanes/eval_bdd.py
A perception_engine/perception/lanes/lanes.py
A perception_engine/perception/lanes/make_overlays.py
A perception_engine/perception/lanes/manual_lane_labels.json
A perception_engine/perception/lanes/postprocess.py
A perception_engine/perception/lanes/tools/__init__.py
A perception_engine/perception/lanes/tools/bench_latency.py
A perception_engine/perception/lanes/tools/calibrate_priors.py
A perception_engine/perception/lanes/tools/inspect_yolop_gt.py
A perception_engine/perception/lanes/tools/make_figures.py
A perception_engine/perception/lanes/tools/visual_check.py
A perception_engine/perception/lanes/viz.py
A perception_engine/perception/openpilot/MODELS.md
A perception_engine/perception/openpilot/README.md
A perception_engine/perception/openpilot/__init__.py
A perception_engine/perception/openpilot/driving_model.py
A perception_engine/perception/openpilot/eval_bdd.py
A perception_engine/perception/openpilot/op_geometry.py
A perception_engine/perception/openpilot/tests_geometry.py
A perception_engine/perception/realtime/README.md
A perception_engine/perception/realtime/__init__.py
A perception_engine/perception/realtime/bench_lanes.py
A perception_engine/perception/realtime/nav_relay.py
A perception_engine/perception/realtime/pipeline.py
A perception_engine/perception/realtime/server.py
A perception_engine/perception/realtime/subscribers.py
A perception_engine/perception/realtime/wire.py
A perception_engine/perception/realtime/ws_probe.py
A perception_engine/perception/segmentation/MODELS.md
A perception_engine/perception/segmentation/README.md
A perception_engine/perception/segmentation/__init__.py
A perception_engine/perception/segmentation/_vendor.py
A perception_engine/perception/segmentation/eval_bdd.py
A perception_engine/perception/segmentation/geometry.py
A perception_engine/perception/segmentation/gt_horizon.py
A perception_engine/perception/segmentation/make_overlays.py
A perception_engine/perception/segmentation/review_yaw_check.py
A perception_engine/perception/segmentation/semantic.py
A perception_engine/perception/segmentation/viz.py
A perception_engine/perception/tracking/MODELS.md
A perception_engine/perception/tracking/README.md
A perception_engine/perception/tracking/__init__.py
A perception_engine/perception/tracking/demo_video.py
A perception_engine/perception/tracking/detector.py
A perception_engine/perception/tracking/eval_bdd.py
A perception_engine/perception/tracking/mot_eval.py
A perception_engine/perception/tracking/motion.py
A perception_engine/perception/tracking/test_motion.py
A perception_engine/perception/tracking/tracker.py
A perception_engine/perception/traffic/MODELS.md
A perception_engine/perception/traffic/README.md
A perception_engine/perception/traffic/__init__.py
A perception_engine/perception/traffic/bench_traffic.py
A perception_engine/perception/traffic/e2e_video.py
A perception_engine/perception/traffic/eval_bdd.py
A perception_engine/perception/traffic/eval_signs.py
A perception_engine/perception/traffic/fetch_val_lights.py
A perception_engine/perception/traffic/lights.py
A perception_engine/perception/traffic/signs.py
A perception_engine/perception/traffic/signs_manual_truth.json
A perception_engine/perception/traffic/smoke_test.py
A perception_engine/perception/traffic/tune_hsv.py
A perception_engine/requirements.txt
A perception_engine/results/depth/metrics.json
A perception_engine/results/detection/metrics.json
A perception_engine/results/lanes/metrics.json
A perception_engine/results/openpilot/metrics.json
A perception_engine/results/segmentation/metrics.json
A perception_engine/results/tracking/metrics.json
A perception_engine/results/traffic/metrics.json
A perception_engine/scripts/download_models.py
A perception_engine/scripts/extract_frames.py
A perception_engine/scripts/fetch_bdd_samples.py
A perception_engine/scripts/fetch_third_party.py
A perception_engine/scripts/netem_proxy.py
A perception_engine/scripts/setup_env.ps1
A perception_engine/scripts/setup_env.sh
A perception_engine/scripts/verify_env.py
A perception_engine/tests/__init__.py
A perception_engine/tests/fake_nav_relay.py
A perception_engine/tests/make_golden_samples.py
A perception_engine/tests/make_golden_samples_v2.py
A perception_engine/tests/test_ego_path.py
A perception_engine/tests/test_nav_relay.py
A perception_engine/tests/test_protocol_v2.py
A perception_engine/tools/bdd/BDD_SAMPLE_MANIFEST.md
A perception_engine/tools/bdd/LICENSE_BDD100K.txt
A perception_engine/tools/bdd/bdd_remote_zip.py
A perception_engine/tools/bdd/build_eval_subset.py
A perception_engine/tools/bdd/build_sample_manifest.py
A perception_engine/tools/bdd/eval_subset_images.txt
A perception_engine/tools/bdd/sample_members.csv
A perception_engine/tools/bdd/select_videos.py
A perception_engine/tools/bdd/selected_videos.json
A perception_engine/tools/bdd/verify_video.py
A perception_engine/tools/kitti/KITTI_SAMPLE_MANIFEST.md
A perception_engine/tools/kitti/depth_selection_members.txt
A perception_engine/tools/kitti/object_calib_members.txt
A perception_engine/tools/kitti/object_image_members.txt
A perception_engine/tools/kitti/object_selected_ids.txt
A perception_engine/tools/kitti/select_kitti.py
```

</details>
