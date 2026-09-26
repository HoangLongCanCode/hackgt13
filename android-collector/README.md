# Phase 1 Android Collector

This is a small Android app that captures live trip-state samples on the phone and writes them to local files.

What it captures:
- timestamp
- latitude / longitude
- heading
- speed
- accuracy

What it writes:
- `session_manifest.json`
- `trip_state.jsonl`

Where it writes:
- app-specific external storage under a `phase1/` session folder

Use it with:
- Android Studio
- USB debugging enabled on the phone

The app is intentionally small. It does not try to own route logic. The computer-side Phase 1 processor will read the captured files later.
