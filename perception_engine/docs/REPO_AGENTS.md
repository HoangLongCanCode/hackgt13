# AGENTS.md: repo-wide guide for AI coding agents and teammates

AI Spatial Driving Copilot, HackGT 13 project by Long Huynh, Luong Nguyen and Gia Minh Do. This file is the entry point
for AI coding tools (Claude Code, Cursor, Codex, GitHub Copilot) and for people joining. `CLAUDE.md` and
`.github/copilot-instructions.md` point here. Each part has its own detailed guide (see the last section).

## Layout and owners

| Folder | What | Owner (branch) |
|---|---|---|
| `driving_assist/` | The AR app "Glass Mode" (Android, Kotlin, Compose, CameraX): camera preview, AR overlay, mock data | AR app (branch `tom`) |
| `driving_assist/app/src/main/java/com/drivingassist/glass/perception/` + its tests, `res/xml/network_security_config.xml`, `PERCEPTION_INTEGRATION.md` | The app side of the laptop bridge (config, sources, mapping, status chip) | Perception (branch `long`) |
| `spatial/` | The phase1 navigation engine (Node.js, `spatial/phase1/`), its scripts, the android-collector GPS app, its docs | Navigation engine (branches `louis` / `phase1`, merged into `main`) |
| `perception_engine/` | The perception engine and realtime server (Python), the protocol contract (`contracts/`), the navigation relay (`nav/`), the Kotlin bridge library and JVM fake tablet (`android/`), docs (`docs/`) | Perception (branch `long`) |
| `AI_Spatial_Driving_Copilot_Production_Plan.md` | Product plan; code comments cite its section numbers | All |

Branch `long` targets `main` (which already has `spatial/`). Change another part's folder only in small, additive,
backward-compatible steps, and list every touched file in the PR description.

## How the three parts connect

- The tablet app (`driving_assist/`) talks to the laptop over **one WebSocket**, `ws://<host>:8765/perception` (USB via
  `adb reverse`, or Wi-Fi). It uplinks camera frames (24-byte `SDC1` header + JPEG) in `LIVE`, playback positions in
  `SIM`, and GPS fixes (`client.trip_state`) for live navigation.
- The laptop server (`perception_engine/perception/realtime/server.py`) answers with `perception.frame` (wave 1) and
  `perception.update` (wave 2), and relays `navigation.packet`s from the navigation engine, which it runs unchanged as
  a Node child process (`perception_engine/nav/phase1_relay.js` -> `spatial/phase1/`).
- In the app, the Kotlin `PerceptionBridge` (`perception_engine/android/perception-bridge`, included by
  `driving_assist/settings.gradle.kts`) merges results into a world model and a Driving Context; `VisionMapper` and
  `BridgeRouteSource` turn them into the overlay's `VisionData` and `RouteState`.

## Cross-cutting rules

1. **One protocol source of truth:** `perception_engine/contracts/PROTOCOL_v2.md`, with its schemas and golden samples.
   Change the spec, schemas, samples, the Python producer and the Kotlin consumer together.
2. **No route logic in the app** (or in the relay): maneuvers, progress, ETA and spoken text come from `spatial/`.
3. **Never block the camera thread and always close `ImageProxy`** (in `finally`); drop frames without a credit.
4. **`MOCK` mode must keep working:** `MockDataViewModel()` stays constructible with no arguments, `MockVisionSource`
   and the 5 s route loop stay, and `gradlew.bat :app:assembleDebug` passes with no laptop.
5. **Display only:** nothing steers, brakes or accelerates, and no LLM sits in a safety path (plan section 38).
6. **Nothing large or secret in git:** no weights, videos, datasets, APKs, build folders, `outputs/`, `.env` files, API
   keys (the Google Maps key lives in the gitignored `spatial/.env`) or absolute machine paths.
7. **Agents do not commit, push, reset or switch branches unless the user asks.**

## Detailed guides

- Perception engine, protocol, relay, JVM modules, every command and known gap:
  [`perception_engine/AGENTS.md`](perception_engine/AGENTS.md) (and [`perception_engine/README.md`](perception_engine/README.md)).
- AR app: [`driving_assist/README.md`](driving_assist/README.md), the overlay contract
  [`driving_assist/ENGINEER_A.md`](driving_assist/ENGINEER_A.md), and how the laptop bridge plugs in
  [`driving_assist/PERCEPTION_INTEGRATION.md`](driving_assist/PERCEPTION_INTEGRATION.md).
- Navigation engine: [`spatial/docs/README.md`](spatial/docs/README.md) (on `main`); how the relay uses it:
  [`perception_engine/docs/INTEGRATION_PHASE1.md`](perception_engine/docs/INTEGRATION_PHASE1.md).
- Protocol: [`perception_engine/contracts/PROTOCOL_v2.md`](perception_engine/contracts/PROTOCOL_v2.md).

Quick checks: `python tests/test_protocol_v2.py --offline` (from `perception_engine/`, with its venv) and
`gradlew.bat :perception-bridge:test :app:testDebugUnitTest` (from `driving_assist/`).
