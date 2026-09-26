# End-to-end examples: laptop perception + phase1 navigation -> tablet bridge

AI Spatial Driving Copilot by Knuckle Sandwich Robotics Inc. (KSR). These files come from real end-to-end runs on 2026-09-26. The KSR perception server (`perception_engine/perception/realtime/server.py`) ran on the laptop GPU with the phase1 route engine relayed through `perception_engine/nav/`. The fake tablet `driving_assist/bridge-cli` drove the **same** `PerceptionBridge` library that the Android app uses. Protocol: [`contracts/PROTOCOL_v2.md`](../../../contracts/PROTOCOL_v2.md).

| File | What it shows |
|---|---|
| `sim_nav_console.txt` | SIM mode (same clip on both sides, laptop analyses ahead of playback). Each block has perception (lane, lead vehicle, light, pedestrians), the phase1 `routeState` (TURN_RIGHT counting down to ARRIVE), Driving Context guidance and alerts, and spoken events. |
| `live_nav_console.txt` | LIVE mode: 960x540 JPEG uplink at 15 fps with credits, plus GPS replay as `client.trip_state`. Shows capture-to-result latency on the tablet clock and credit drops, with one Wi-Fi-emulated block at the end. |
| `snapshot_live.json` | `bridge-cli --dump-snapshot`: `WorldSnapshot` (objects by track id, lanes, road, timing), `DrivingContext` (lead vehicle, alerts), the navigation update with the phase1 packet, `NavigationState` and `LinkStatus`. Trimmed to the lead vehicle, 3 other objects and 1 light. |
| `messages.json` | One of each PROTOCOL_v2 message from the golden samples (`contracts/samples/v2/`), trimmed, in the order of a live session. |
| `metrics.json` | Measured numbers for every run (below), plus the before/after effect of the `inEgoPath` fix. |

## Measured (localhost, laptop on battery power, clip `b1ff4656-0435391e`)

| Run | Result |
|---|---|
| SIM + sim nav, 31 s | Wave 1: 12.5 results/s (7.4-16). Wave 2: 6.5 updates/s. Results reached the tablet about 190 ms before their frame was shown (server p50; tablet window p50 median 259 ms). From 5 s on, a result was available for every displayed frame; the shown result was 36 ms p50 / 93 ms p95 behind playback. 59 `navigation.packet`s (about 2 Hz): GO_STRAIGHT, then TURN_RIGHT 116 m counting down to 1 m, then ARRIVE. |
| LIVE + live nav, USB-equivalent | Capture to result: 114 ms p50 / 162 ms p95 (steady state); 13 results/s. The client dropped 64 of 382 frames for lack of a credit (by design); 0 skips, 0 credit timeouts. 51 trip states, 51 navigation packets. |
| LIVE through `scripts/netem_proxy.py --profile wifi-busy` | 122 ms p50 / 180 ms p95, 12 results/s, 1 skip, 2 credit timeouts (all during session start-up). |
| LIVE through `--profile hotspot` | 159 ms p50 / 683 ms p95, 8 results/s. A random cluster of latency spikes held the uplink at about 3 fps for about 5 s. |

Caveats:
- The first 1-3 s of every session are slower. In SIM, the first results arrive 0.4-0.8 s late and the auto look-ahead then overshoots; in LIVE, the first frames take 0.6-1.1 s. The Driving Context reports `STALE` and keeps navigation-only guidance during that time.
- The laptop ran on battery power, so GPU-bound numbers are about 20-30 % below what it reaches on AC power.
- The navigation routes are synthetic (mock provider around Georgia Tech, timed to the clip), and the GPS speed does not match the video.

## Reproduce

PowerShell, from `perception_engine/`, with the phase1 checkout next to the repo (see `perception_engine/nav/README.md`).
Run one server at a time (both use port 8770):

```powershell
.\.venv\Scripts\python.exe -m perception.realtime.server --mode sim --port 8770 --nav-session nav/demo_sessions/b1ff4656-0435391e
.\.venv\Scripts\python.exe -m perception.realtime.server --mode live --port 8770 --nav-route nav/demo_sessions/b1ff4656-0435391e/route.json
.\.venv\Scripts\python.exe scripts\extract_frames.py b1ff4656-0435391e --fps 15 --seconds 20 --out outputs\e2e\frames_b1ff4656_960x540
.\.venv\Scripts\python.exe scripts\netem_proxy.py --listen 127.0.0.1:8771 --target 127.0.0.1:8770 --profile wifi-busy
```

(`.venv` is the venv made by `scripts\setup_env.ps1`; use your own interpreter path if it lives elsewhere.)

PowerShell, from `driving_assist/` (after `.\gradlew.bat :bridge-cli:installDist`):

```powershell
.\bridge-cli\build\install\bridge-cli\bin\bridge-cli.bat sim --video-id b1ff4656-0435391e --url ws://127.0.0.1:8770/perception --seconds 31
.\bridge-cli\build\install\bridge-cli\bin\bridge-cli.bat live `
    --frames ..\perception_engine\outputs\e2e\frames_b1ff4656_960x540 --fps 15 --loop `
    --focal-px 525 --mount-height 1.35 `
    --trip-states ..\perception_engine\nav\demo_sessions\b1ff4656-0435391e\trip_state.jsonl `
    --url ws://127.0.0.1:8770/perception --seconds 25
```

Over the emulated Wi-Fi link, run the same `live` command with `--url ws://127.0.0.1:8771/perception` (the netem proxy).
`snapshot_live.json` comes from the same `live` command with `--dump-snapshot --dump-at 12 --seconds 14` added.
