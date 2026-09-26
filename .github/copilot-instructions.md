# Copilot instructions

This repo is the AI Spatial Driving Copilot (HackGT 13) by Knuckle Sandwich Robotics Inc. (KSR). The canonical guide
for AI coding tools is [`AGENTS.md`](../AGENTS.md) at the repo root: read it for the layout, ownership, conventions,
commands and rules. The short version:

- `contracts/PROTOCOL_v2.md` is the source of truth for laptop-tablet messages; change spec, schemas, samples, Python
  and Kotlin together.
- `driving_assist/ENGINEER_A.md` is the overlay contract (`VisionData`, `RouteState`). Tom owns the app UI files: keep
  edits minimal and backward compatible, and keep `MOCK` mode working.
- No route logic in the Android app (phase1 owns it). Never block the camera thread; always close `ImageProxy`.
- Display only: nothing controls the vehicle. No weights, videos, datasets, APKs, secrets or absolute paths in git.
- Python runs from `perception_engine/` with its `.venv` (`import torch` before `onnxruntime`); Kotlin stays on 2.0.21,
  AGP 8.7.3, compileSdk 35.
