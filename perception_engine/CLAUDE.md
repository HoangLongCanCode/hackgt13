# CLAUDE.md (perception_engine/)

**Read [AGENTS.md](AGENTS.md) in this folder first:** it is the guide for the perception engine (layout, contracts,
conventions, every command, rules, known gaps). The repo-wide rules are in [`docs/REPO_AGENTS.md`](docs/REPO_AGENTS.md). This file
only repeats the top rules.

- Protocol source of truth: `contracts/PROTOCOL_v2.md` + `contracts/schemas/` + `contracts/samples/v2/`. Change the
  spec, schema, samples, Python (`perception/realtime/wire.py`) and Kotlin (`android/perception-bridge/`) together.
- Run Python from `perception_engine/` with its `.venv`; `import torch` before `onnxruntime`; `YOLO_AUTOINSTALL=False`;
  no ad hoc `pip install`.
- Kotlin modules in `android/` build from `../frontend/` (`gradlew.bat :perception-bridge:test :bridge-cli:installDist`).
- No route logic here or in the app: the navigation engine is `../spatial/` on `main`; the relay in `nav/` only calls it.
- Keep `DEMO` (no laptop) working and keep edits to the tablet app (`../frontend/`, merged from branch `tom`) minimal.
- Never commit weights, videos, datasets, APKs, build folders, `outputs/`, `.env` or keys; no absolute machine paths.
- Do not edit `perception/third_party/`. Do not commit, push, reset or switch branches unless the user asks.

Quick checks: `python tests/test_protocol_v2.py --offline` (from here) and
`gradlew.bat :perception-bridge:test :app:testDebugUnitTest` (from `../frontend/`).
