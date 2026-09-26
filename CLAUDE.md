# CLAUDE.md

AI Spatial Driving Copilot, HackGT 13, by Knuckle Sandwich Robotics Inc. (KSR). **Read [AGENTS.md](AGENTS.md) first:**
it is the canonical guide (layout, ownership, conventions, commands, rules, known gaps). This file only repeats the top
rules.

- Protocol source of truth: `contracts/PROTOCOL_v2.md` + `contracts/schemas/` + `contracts/samples/v2/`. Change the
  spec, schema, samples, Python (`perception_engine/perception/realtime/wire.py`) and Kotlin
  (`driving_assist/perception-bridge/`) together; bump `schemaVersion` on a breaking change.
- Overlay contract: `driving_assist/ENGINEER_A.md` (`VisionData` in 0..1 overlay space, `RouteState` from phase1).
- No route logic in the Android app or the relay: phase1 (`src/phase1/`, branch `phase1`) owns it.
- Never block the camera thread; always `image.close()` in `finally`; drop frames without a credit.
- `MOCK` must keep working (`MockDataViewModel()` no-arg, `MockVisionSource`, the 5 s route loop). Tom owns the app
  UI files: minimal, backward-compatible edits, and list them in the PR.
- Display only (plan section 38): nothing steers, brakes or accelerates; no LLM in a safety path.
- Never commit weights, videos, datasets, APKs, build folders, `outputs/`, `.env` or keys; no absolute machine paths.
- Python: run from `perception_engine/` with its `.venv`; `import torch` before `onnxruntime`; no ad hoc `pip install`.
- Kotlin: keep Kotlin 2.0.21, AGP 8.7.3, compileSdk 35, JVM 17. Build with `gradlew.bat` from `driving_assist/`.
- Do not create `docs/README.md` (phase1 owns it). Do not edit `perception_engine/perception/third_party/`.
- Do not commit, push, reset or switch branches unless the user asks.

Quick checks: `python tests/test_protocol_v2.py --offline` (from `perception_engine/`) and
`gradlew.bat :perception-bridge:test :app:testDebugUnitTest` (from `driving_assist/`).
