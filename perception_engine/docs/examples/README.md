# End-to-end examples: laptop perception + phase1 navigation -> tablet bridge

AI Spatial Driving Copilot. These files come from real end-to-end runs on 2026-09-26. The perception server (`perception_engine/perception/realtime/server.py`) ran on the laptop GPU with the phase1 navigation engine (`spatial/` on `main`) relayed through `perception_engine/nav/`. The fake tablet `perception_engine/android/bridge-cli` drove the **same** `PerceptionBridge` library that the Android app uses. Protocol: [`contracts/PROTOCOL_v2.md`](../../contracts/PROTOCOL_v2.md).

| File | What it shows |
|---|---|
| `sim_nav_console.txt` | SIM mode (same clip on both sides, laptop analyses ahead of playback). Each block has perception (lane, lead vehicle, light, pedestrians), the phase1 `routeState` (TURN_RIGHT counting down to ARRIVE), Driving Context guidance and alerts, and spoken events. |
| `live_nav_console.txt` | LIVE mode: 960x540 JPEG uplink at 15 fps with credits, plus GPS replay as `client.trip_state`. Shows capture-to-result latency on the tablet clock and credit drops, with one Wi-Fi-emulated block at the end. |
| `snapshot_live.json` | `bridge-cli --dump-snapshot`: `WorldSnapshot` (objects by track id, lanes, road, timing), `DrivingContext` (lead vehicle, alerts), the navigation update with the phase1 packet, `NavigationState` and `LinkStatus`. Trimmed to the lead vehicle and the 3 nearest other objects (plus 1 traffic light when one is tracked). |
| `messages.json` | One of each PROTOCOL_v2 message from the golden samples (`contracts/samples/v2/`), trimmed, in the order of a live session. |
| `metrics.json` | Measured numbers for every run (below), plus the before/after effect of the `inEgoPath` fix. |

## Measured (localhost, clip `b1ff4656-0435391e`)

The console excerpts and `snapshot_live.json` were re-captured on AC power after the code moved into
`perception_engine/` (`metrics.json` → `recapture_ac_power`):

| Run (AC power) | Result |
|---|---|
| SIM + sim nav, 31 s | Wave 1: 20.5 results/s (14.8-27). Wave 2: 12.5 updates/s. A result was available for every displayed frame; the shown result was 24 ms p50 / 58 ms p95 behind playback, and results reached the tablet about 150 ms before their frame was shown (running tablet p50 between 116 and 249 ms over the run). 59 `navigation.packet`s (about 2 Hz): GO_STRAIGHT, then TURN_RIGHT 116 m counting down to 1 m, then ARRIVE. |
| LIVE + live nav, USB-equivalent | Capture to result: 49 ms p50 / 65 ms p95 (steady state); 15 results/s, i.e. every uplinked frame. The client dropped 3 of 378 frames for lack of a credit; 0 skips, 0 credit timeouts. 50 trip states, 50 navigation packets. |
| LIVE through `scripts/netem_proxy.py --profile wifi-busy` | 82 ms p50 / 143 ms p95, 14.5 results/s, 13 frames dropped for lack of a credit, 0 skips, 0 credit timeouts. |

The first capture, on battery power earlier the same day (`metrics.json` top-level sections):

| Run (battery) | Result |
|---|---|
| SIM + sim nav, 31 s | Wave 1: 12.5 results/s (7.4-16). Wave 2: 6.5 updates/s. Results reached the tablet about 190 ms before their frame was shown (server p50; tablet window p50 median 259 ms). From 5 s on, a result was available for every displayed frame; the shown result was 36 ms p50 / 93 ms p95 behind playback. 59 `navigation.packet`s. |
| LIVE + live nav, USB-equivalent | Capture to result: 114 ms p50 / 162 ms p95 (steady state); 13 results/s. The client dropped 64 of 382 frames for lack of a credit (by design); 0 skips, 0 credit timeouts. 51 trip states, 51 navigation packets. |
| LIVE through `--profile wifi-busy` | 122 ms p50 / 180 ms p95, 12 results/s, 1 skip, 2 credit timeouts (all during session start-up). |
| LIVE through `--profile hotspot` | 159 ms p50 / 683 ms p95, 8 results/s. A random cluster of latency spikes held the uplink at about 3 fps for about 5 s. |

Caveats:
- The first 1-3 s of every session are slower. In SIM, the first results can arrive late and the auto look-ahead then overshoots; in LIVE, the first frames take longer (0.6-1.1 s on battery). The Driving Context reports `STALE` and keeps navigation-only guidance during that time.
- Power matters: on battery the laptop GPU was far slower (compare the two tables). Measure on the demo power setup.
- The navigation routes are synthetic (mock provider around Georgia Tech, timed to the clip), and the GPS speed does not match the video.

## Reproduce

PowerShell, from `perception_engine/`. The navigation engine is found in `../spatial/` (on `main`; see `nav/README.md` for
`--phase1-dir` / `PHASE1_DIR`). Run one server at a time (both use port 8770):

```powershell
.\.venv\Scripts\python.exe -m perception.realtime.server --mode sim --port 8770 --nav-session nav/demo_sessions/b1ff4656-0435391e
.\.venv\Scripts\python.exe -m perception.realtime.server --mode live --port 8770 --nav-route nav/demo_sessions/b1ff4656-0435391e/route.json
.\.venv\Scripts\python.exe scripts\extract_frames.py b1ff4656-0435391e --fps 15 --seconds 20 --out outputs\e2e\frames_b1ff4656_960x540
.\.venv\Scripts\python.exe scripts\netem_proxy.py --listen 127.0.0.1:8771 --target 127.0.0.1:8770 --profile wifi-busy
```

(`.venv` is the venv made by `scripts\setup_env.ps1`; use your own interpreter path if it lives elsewhere.)

PowerShell, from `driving_assist/` (after `.\gradlew.bat :bridge-cli:installDist`, which installs into
`perception_engine/android/bridge-cli/build/install/`):

```powershell
..\perception_engine\android\bridge-cli\build\install\bridge-cli\bin\bridge-cli.bat sim --video-id b1ff4656-0435391e --url ws://127.0.0.1:8770/perception --seconds 31
..\perception_engine\android\bridge-cli\build\install\bridge-cli\bin\bridge-cli.bat live `
    --frames ..\perception_engine\outputs\e2e\frames_b1ff4656_960x540 --fps 15 --loop `
    --focal-px 525 --mount-height 1.35 `
    --trip-states ..\perception_engine\nav\demo_sessions\b1ff4656-0435391e\trip_state.jsonl `
    --url ws://127.0.0.1:8770/perception --seconds 25
```

Over the emulated Wi-Fi link, run the same `live` command with `--url ws://127.0.0.1:8771/perception` (the netem proxy).
`snapshot_live.json` comes from the same `live` command with `--dump-snapshot --dump-at 12 --seconds 14` added.
