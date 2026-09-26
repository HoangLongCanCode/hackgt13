# REPO_AGENTS.md: repo-wide guide for AI coding agents and teammates

AI Spatial Driving Copilot, HackGT 13 project by Long Huynh, Luong Nguyen and Gia Minh Do. This file is the repo-wide
entry point for AI coding tools (Claude Code, Cursor, Codex, GitHub Copilot) and for people joining. It was the
repo-root `AGENTS.md`; the repo root now holds only `frontend/`, `perception_engine/` and `spatial/`, and
[`perception_engine/CLAUDE.md`](../CLAUDE.md) points here. Each part has its own detailed guide (see the last section).

## Layout and owners

| Folder | What | Owner (branch) |
|---|---|---|
| `frontend/` | The tablet app (Android Studio project: Kotlin 2.0.21, AGP 8.7.3, compileSdk 35, minSdk 26, JVM 17, landscape, Samsung Galaxy Tab S9 target; package `com.drivingassist.spatialcopilot`): camera uplink, SIM player, AR view (clean / Debug), GPS, Driving Context, voice. `settings.gradle.kts` includes `:perception-bridge` and `:bridge-cli` from `perception_engine/android/` | Tablet app (branch `tom`, merged into `main`) |
| `spatial/` | The phase1 navigation engine (Node.js, `spatial/phase1/`), its scripts, the android-collector GPS app, its docs | Navigation engine (branches `louis` / `phase1`, merged into `main`) |
| `perception_engine/` | The perception engine and realtime server (Python), the protocol contract (`contracts/`), the navigation relay (`nav/`), the Kotlin bridge library and JVM fake tablet (`android/`), docs (`docs/`) | Perception (branch `long`, merged into `main`) |

All three parts are on `main`. The production plan (`AI_Spatial_Driving_Copilot_Production_Plan.md`) is not in the
repo; code comments still cite its section numbers. Change another part's folder only in small, additive,
backward-compatible steps, and list every touched file in the PR description.

## How the three parts connect

- The tablet app (`frontend/`) talks to the laptop over **one WebSocket**, `ws://<host>:8765/perception` (USB via
  `adb reverse`, or Wi-Fi). It uplinks camera frames (24-byte `SDC1` header + JPEG, under credits) in `LIVE`, playback
  positions (`client.playback`) in `SIM`, and GPS fixes (`client.trip_state`, about 1 Hz) for live navigation. `DEMO`
  needs no laptop.
- The laptop server (`perception_engine/perception/realtime/server.py`) answers with `perception.frame` (wave 1) and
  `perception.update` (wave 2), and relays `navigation.packet`s from the navigation engine, which it runs unchanged as
  a Node child process (`perception_engine/nav/phase1_relay.js` -> `spatial/phase1/`). The same port serves the
  ElevenLabs text-to-speech proxy (`POST /tts`) for the app's voice.
- In the app, the Kotlin `PerceptionBridge` (`perception_engine/android/perception-bridge`, included by
  `frontend/settings.gradle.kts` as `:perception-bridge`) merges results into a `WorldModel` and a Driving Context
  (`DrivingContextEngine`); the app turns them and the phase1 route into the AR scene (road arrows, lead vehicle,
  maneuver card, banners) and into voice cues.

## Cross-cutting rules

1. **One protocol source of truth:** `perception_engine/contracts/PROTOCOL_v2.md`, with its schemas and golden samples.
   Change the spec, schemas, samples, the Python producer and the Kotlin consumer together.
2. **No route logic in the app** (or in the relay): maneuvers, progress, ETA and spoken text come from `spatial/`.
3. **Never block the camera thread and always close `ImageProxy`** (in `finally`); drop frames without a credit.
4. **`DEMO` mode must keep working:** no laptop, a scripted scene and the placeholder "Exit 56" route (only in DEMO),
   and `gradlew.bat :app:assembleDebug` passes with no laptop.
5. **Display only:** nothing steers, brakes or accelerates, and no LLM sits in a safety path (plan section 38). Visual
   alerts never depend on audio.
6. **Nothing large or secret in git:** no weights, videos, datasets, APKs, build folders, `outputs/`, `.env` files, API
   keys (the Google Maps key lives in the gitignored `spatial/.env`, the ElevenLabs key in the gitignored
   `perception_engine/.env`) or absolute machine paths.
7. **Agents do not commit, push, reset or switch branches unless the user asks.**

## Detailed guides

- Perception engine, protocol, relay, JVM modules, every command and known gap:
  [`perception_engine/AGENTS.md`](../AGENTS.md) (and [`perception_engine/README.md`](../README.md)).
- Tablet app: [`frontend/README.md`](../../frontend/README.md); voice cue rules
  [`perception_engine/docs/audio/AUDIO_CUE_RULES.md`](audio/AUDIO_CUE_RULES.md).
- Navigation engine: [`spatial/docs/README.md`](../../spatial/docs/README.md); how the relay uses it:
  [`perception_engine/docs/INTEGRATION_PHASE1.md`](INTEGRATION_PHASE1.md).
- Protocol: [`perception_engine/contracts/PROTOCOL_v2.md`](../contracts/PROTOCOL_v2.md).
- Demo day: [`perception_engine/docs/RUNBOOK.md`](RUNBOOK.md).

Quick checks: `python tests/test_protocol_v2.py --offline` (from `perception_engine/`, with its venv) and
`gradlew.bat :app:assembleDebug :app:testDebugUnitTest :perception-bridge:test :bridge-cli:installDist` (from `frontend/`).
