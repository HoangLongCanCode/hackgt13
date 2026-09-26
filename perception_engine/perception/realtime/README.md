# Realtime Perception Bridge server (protocol v2)

This folder belongs to the AI Spatial Driving Copilot, the HackGT 13 prototype. It serves the perception models that run on the laptop GPU (RTX 5060 Laptop, 8 GB) to the Samsung Galaxy Tab S9 over one WebSocket. The tablet does all I/O: camera, display, audio and GPS. The laptop only runs models, plus the phase1 route engine as a child process.

The source of truth for the messages is [`contracts/PROTOCOL_v2.md`](../../contracts/PROTOCOL_v2.md). Every message this server sends or accepts has a JSON Schema in [`contracts/schemas/`](../../contracts/schemas/) (draft 2020-12) and a golden sample from a real run in [`contracts/samples/v2/`](../../contracts/samples/v2/).

Everything here is display-only (plan sections 18 and 38). No output steers, brakes or accelerates. Distances, TTC and light states are estimates: `UNKNOWN` means unknown, and `GREEN` is never permission to go.

Run all commands from `perception_engine/` with the project venv's Python.

## Quick start

```text
python -m perception.realtime.server --mode auto                 # tablet chooses live or sim in its client.hello
python -m perception.realtime.server --mode live                 # tablet camera uplink only
python -m perception.realtime.server --mode sim                  # tablet plays a clip the laptop also has
python -m perception.realtime.server --mode video --video data/bdd100k/videos/val/b1ff4656-0435391e.mov --loop
```

Start-up takes about 30-60 s (26-54 s measured; longer while other jobs use the GPU): about 7 s to load the models, then each lane warms up in its own thread and CUDA stream (fast lane about 7 s, slow lane about 22 s). The server prints its URLs once it is ready.

- **USB (preferred).** Run `adb reverse tcp:8765 tcp:8765`, then point the app at `ws://127.0.0.1:8765/perception`.
- **Wi-Fi.** Point the app at `ws://<laptop-LAN-IP>:8765/perception`. Windows Firewall may ask you to allow `python.exe` on private networks. Wi-Fi is fine for slow in-town driving only.
- **Health and settings.** `GET /health` returns the mode, session, clients, stats, lane errors and nav state. `GET /config` returns the merged config and the clip list.

| Option | Meaning |
|---|---|
| `--mode video\|live\|sim\|auto` | `auto` accepts `live` and `sim`; the controlling client's `client.hello` decides |
| `--video PATH [--loop] [--start-on-connect]` | video mode: the laptop plays the clip in real time. Each loop is a new session |
| `--video-dir DIR` (repeatable) | sim: extra folders searched for `<videoId>.*`. Always searched: `data/bdd100k/videos/**` and `data/sim_videos/**`. A folder holding a `video.mp4` (a phase1 session) is registered under the folder name |
| `--lookahead auto\|SECONDS`, `--sim-margin 0.10` | sim look-ahead: auto-tuned by default (see "Sim scheduler") |
| `--max-in-flight N` (default 2) | live uplink credits announced in the hello; see "Choosing maxInFlight" |
| `--config perception/config_realtime.yaml`, `--set KEY=VALUE` | engine config plus overrides, e.g. `--set slow.max_hz=6 --set slow_schedule.depth.every=2` |
| `--host 0.0.0.0 --port 8765` | listen address |
| `--nav-session DIR` | sim/video navigation from a phase1 session folder (`session_manifest.json`, `route.json`, `trip_state.jsonl`) |
| `--nav-route route.json` \| `--nav-destination "QUERY" [--nav-origin "QUERY"] [--nav-provider mock\|google]` | live navigation driven by `client.trip_state` |
| `--phase1-dir DIR`, `--node node` | where the navigation engine lives (default: env `PHASE1_DIR`, else `../spatial` on `main`, or a legacy `src/phase1` checkout; see `nav/README.md`) and which Node to run |

## Architecture

```text
 tablet ──ws──► asyncio loop (uvicorn) ── parse JSON / SDC1 header ── per-client queues ──ws──► tablet(s)
                  │ live: Item(jpeg, rotation, echo)              ▲ call_soon_threadsafe(serialised JSON)
                  ▼                                               │
   video/sim ─► [1-slot latest-wins inbox] ─► FAST lane thread ───┤  perception.frame (wave 1), every analysed frame
   source         (superseded -> skip)         decode + rotate,   │
   thread                                      detect, track,     │
                                               lights, carried    │
                                               + geometry dist.   │
                                                    │ snapshot (latest wins)
                                                    ▼             │
                                               SLOW lane thread ──┘  perception.update (wave 2) per slow run
                                               depth/distance, lanes + road, signs
   nav worker thread ── NavRelay (Node child, JSON lines) ────────►  navigation.packet
```

- **Model ownership.** Each model is used by exactly one thread. The fast lane owns the detector, both trackers and the light classifier. The slow lane owns DA3 depth, TwinLiteNet+ lanes, the sign recognizer and, if enabled, segmentation. The only state both lanes touch is `engine.shared` (results only, never models), behind a lock. A new stream bumps a generation counter, and slow results from an older generation are dropped.
- **The asyncio loop never touches torch.** It parses messages, submits live frames to the inbox, which is a lock plus an assignment, and sends pre-serialised strings. Clip decoding runs on the source thread. Relay calls run on the nav worker.
- **Per-client sending.** Each client has a reliable control queue (hello, skip, error, pong, stats) and three 1-slot latest-wins slots: `perception.frame`, `perception.update` and `navigation.packet`. A slow socket loses only its own stale frames. Each message is serialised once (orjson) and the same string goes to every client. A send that cannot complete within 10 s (the peer keeps the TCP connection but stopped reading) evicts the client: its transport is aborted, it leaves `/health` and the stats, and if it was the controller the session ends.
- **Roles.**
  - The **controller** is the client whose `client.hello` started the live or sim session. It uplinks frames, reports playback and feeds live navigation. The newest hello takes over; the old controller becomes a watcher (`role: "watcher"` in its next hello) and receives `perception.error notUplinkClient`.
  - **Watchers** are clients that sent no hello, or were taken over. Everyone receives all results, stats and navigation packets (the Kotlin bridge ignores live / sim results while it is a watcher and re-sends its hello when the server goes idle).
  - A client that sends SDC1 frames without a hello while nobody controls the session becomes the controller implicitly: with the camera of its last live `client.hello` if it sent one (a taken-over client), else with no intrinsics (v1-style client).
- **Sessions.** Every new stream gets a new `sessionId` and a `perception.hello` is broadcast. A new stream is:
  - a new controller,
  - a video loop,
  - a sim seek or clip switch,
  - the controller asking for a clip the laptop lacks (a sim session without a source), or
  - a change in the upright uplink frame size in the middle of a live session (new `sessionId`, `ptsSeconds` from 0).

  Track ids are only comparable within one session. Stale queued results are dropped when the session changes, and any queued uplink answer is replaced by `perception.skip sessionReset`, so clients get every credit back without resetting them. While idle the hello says `sessionId: "idle"` and `mode` = the first accepted mode.

## Modes

### live (tablet camera)

- **Frame format.** Each binary message is the 24-byte `SDC1` header (`struct "<4sHHIqHH"`, see [`uplink_header.example.txt`](../../contracts/samples/v2/uplink_header.example.txt)) followed by a baseline JPEG. The recommended size is 960x540 at quality 80, about 60 KB. JPEG decode and rotation run on the fast lane (about 2 ms).
- **Credits.** The hello announces `uplink.maxInFlight`. Every uplinked frame gets **exactly one** wave-1 answer: a `perception.frame` whose `echo` matches, or a `perception.skip` with a reason.

  | Reason | When |
  |---|---|
  | `superseded` | a newer frame replaced it, either in the inbox or in the controller's send slot |
  | `decodeError` | the JPEG could not be decoded |
  | `badHeader` | the SDC1 header is invalid (bad magic, headerVersion or rotation); any message of 24 bytes or more is answered, since `frameId` sits at offset 8. A shorter message gets only a rate-limited `badMessage` |
  | `notAccepted` | the sender is not the controller, the server is not in live mode, or the fast lane raised |
  | `sessionReset` | the session changed before the result was sent |

  The tests check this invariant on normal, rotated and credit-violating traffic.
- **Rotation.** `rotationDegrees` rotates the JPEG clockwise to upright. All coordinates, including `image.width/height`, are in the upright image. If the upright size changes mid-stream, for example when the tablet is rotated, the trackers reset, a new session and hello start, and the `client.hello` intrinsics are adapted: scaled for the same aspect, or treated as rotated. The client is told with a non-fatal `perception.error badMessage`.
- **Intrinsics.** The `client.hello` `camera` block (`focalPx`, `principalPoint`, `mountHeightMeters`, `pitchDegrees`, at the uplinked upright resolution) becomes the session camera:
  - The depth block uses it: `focal_px`, `cx`, `cy`. With a mount height given, calibration is "fixed" and the height is trusted.
  - The fast lane's geometry distance uses it.
  - The wire `camera` and the road anchors' `groundXZ` use it.

  Streams wider than `input.max_width` (1280) are scaled down, and the intrinsics are scaled with them.
- **Timestamps.** `ptsSeconds = (captureTimeNs - first captureTimeNs of the session) / 1e9`, so it advances with the tablet's capture clock. `echo.captureTimeNs` lets the client measure capture-to-result latency on its own clock.

### sim (same clip on both devices)

`client.hello {mode: "sim", sim: {videoId}}` resolves the videoId to a local clip (`<PERCEPTION_DATA_DIR or data>/bdd100k/videos`, `<data>/sim_videos`, `--video-dir`). An unknown id returns one `perception.error unknownVideo`, whose `detail.videos` lists up to 50 clips the laptop has; if it came from the controller (or nobody controlled the server), the previous session ends and that client controls a sim session without a source until the clip appears (its playback reports re-check the folders at most every 5 s, without repeating the error). The tablet then sends `client.playback` at about 10 Hz and on every seek, pause or resume.

**Sim scheduler** (`SimSource`):

- Whenever the fast lane has taken the previous frame, the frame at `playbackPts(now) + lookahead` is decoded and submitted. The look-ahead is in media seconds. Playback is extrapolated from the newest `client.playback` for at most 1.5 s.
- **pts.** Frame pts are the clip's **container pts**, which the tablet's player reports. `ClipReader` reads `CAP_PROP_POS_MSEC` after `grab()`, which equals the PyAV `frame.pts` on BDD clips. `VideoFileInput` reads it before `read()` and is one frame early, so it is not used here.
- **Auto look-ahead.** The look-ahead is the p95 of the measured submit-to-wave-1 latency over the last 3 s, plus `--sim-margin` (0.10 s, enough for the emulated busy Wi-Fi). It is multiplied by `rate` (media seconds), clamped to 0.12-1.5 s, rises immediately and decays slowly. The current value appears in the hello (`sim.lookaheadSeconds`) and in stats (`lookaheadSeconds`, `simLeadMs`, `simLateFraction`).
- **Pause.** Frames up to the look-ahead are already analysed, so nothing new runs unless the paused position has not been analysed yet. `rate` <= 0 counts as paused.
- **Seek.** A jump of more than 0.6 s x max(1, rate) against the extrapolation, for example a seek or a loop back to the start, resets the trackers and starts a new session and hello, so the tablet drops its result buffer (the Kotlin `PlaybackClock` uses the same rule).
- **Clip switch.** A `client.playback` with a different videoId switches the clip.

### video (laptop-only testing)

The laptop plays `--video` on its own clock and every client watches. Frames the fast lane cannot take are dropped (`seq` gaps; `framesDropped` in stats).

## Distances in wave 1

Each vehicle, rider and pedestrian object carries a distance whenever any estimate is possible. The measured coverage on the city and night clips is 100 % of vehicle/pedestrian objects.

1. **Slow-lane result.** The latest result for that track id is used while it is younger than `slow.max_distance_age_s` (1.0 s). Methods are `fused`, `depth_model`, `ground_plane` and `width_prior`, from DA3 metric depth plus flat ground plus size prior with a per-track Kalman filter. `distanceAgeMs` is the media time between the analysed frame and this frame.
2. **Otherwise, a geometry distance** (`distanceMethod: "geometry"`) computed on this frame in the fast lane (`engine.geometry_distance`, about 0.1 ms for all boxes):
   - It fuses the class size prior (height, plus width for near-rear views) and flat ground, inverse-variance in log space.
   - The horizon comes from the depth block's smoothed estimate. Before the first depth run it comes from the lanes horizon, or from an object-size virtual horizon plus a principal-row prior.
   - Confidence is capped at 0.5. `distanceAgeMs` is 0.
3. **Traffic lights and signs** use `size_prior` (1.0 m housing, 0.75 m sign).

Wave 2 (`perception.update.distances`) carries the fresh slow-lane values for the analysed frame's track ids.

## Navigation

When a `--nav-*` flag is given, the server imports `perception.realtime.nav_relay.NavRelay` (the navigation side's module; the phase1 route engine runs in a Node child process) and drives it from one worker thread. Navigation is optional: the server keeps working without Node or phase1, and the hello's `navigation` field says `{mode, available, error}`.

- **sim/video (`--nav-session DIR`).** About every 0.5 s of media time (and after a seek), `relay.packet_at_pts(playback pts)` is called and the result is broadcast as `navigation.packet`. In video mode the laptop player's pts is used.
- **live (`--nav-route` or `--nav-destination`).** Each `client.trip_state` from the controller (or from a client whose hello has `navigation.mode: "live"`; others get `notUplinkClient`) is validated (`badMessage` for a sample without numeric `timestampMs` / `location`; null `heading` / `speedMps` become 0), goes to `relay.on_trip_state(body)`, and the packet is broadcast to everyone. A `client.trip_state` without live navigation gets one non-fatal `perception.error modeNotAvailable`.
- **Relay health.** 3 relay calls in a row without a packet set `navigation.available: false` (reason in `error`), re-announce the hello and send one `perception.error internal`; the next packet clears it.
- **New clients** receive the last packet on connect.

## Choosing maxInFlight (live)

Measured with `ws_probe live` (a 30 fps "camera", 960x540 q80, credits honoured) on the city clip. Latency is capture to wave-1 result on the client clock.

| maxInFlight | link | capture->result p50 / p95 | result fps | notes |
|---|---|---|---|---|
| 1 | localhost (USB-like) | 74 / 90 (20 s) · 91 / 111 (30 s, laptop busier) | 11.5 · 9.6 | the lane idles between frames |
| 1 | netem wifi-busy | 121 / 186 | 7.3 | |
| **2 (default)** | localhost | 106 / 141 (30 s) · 112 / 152 (20 s) | 16.7 · 15.8 | the queued frame waits about 45 ms in the inbox |
| 2 | netem wifi-busy | 119 / 192 | 14.5 | |
| 2 | netem hotspot | 143 / 275 | 11.6 | 3 superseded skips |
| 3 | localhost | 87 / 123 (20 s) · 108 / 135 (30 s, laptop busier) | 15.6 · 12.4 | about half of the frames answered `skip superseded`; the probe's 30 fps JPEG encoding shares the laptop CPU |
| 3 | netem wifi-busy | 147 / 229 | 12.3 | |
| 3 | netem hotspot | 193 / 346 | 11.6 | |

netem profiles, per direction: wifi-busy is 6 +- 5 ms with 80 ms spikes about every 4 s for 150 ms; hotspot is 12 +- 8 ms with 150 ms spikes about every 3 s for 300 ms, capped at 40 Mbit/s.

- **1** gives the lowest latency on USB. The fast lane idles while the answer travels back and the next camera frame arrives, so throughput drops to about 10-11 fps (7 fps on busy Wi-Fi).
- **2** (the protocol default, kept) has the best throughput: one frame is processed while the next one waits. It is also the most robust over Wi-Fi.
- **3** makes the client send every camera frame. The inbox always holds the newest frame, and the older one gets `perception.skip superseded`.
  - On an idle USB link it cut p50 by about 25 ms.
  - It gave no gain when the laptop was busy, and it was worse over emulated Wi-Fi or hotspot, because twice the bytes queue up behind latency spikes.
  - It also costs about 15 Mbit/s of uplink and 30 JPEG encodes per second on the tablet.
  - Consider `--max-in-flight 3` only for a USB demo, after checking it on the real tablet.

## Measured performance

Setup: city clip `b1ff4656-0435391e`, 30 s runs, RTX 5060 Laptop, default `config_realtime.yaml` (two lanes, DA3 every slow cycle, lanes every cycle, signs every 2nd). Processing time is measured from when the frame is available on the server until the message is built, and includes queue waits. Logs are under `outputs/realtime/measure/` (gitignored). The GPU and CPU were shared with other agents' builds and servers during some runs, so treat the numbers as indicative.

| Mode (30 s, city clip) | wave-1 fps | wave-1 processing p50 / p95 | wave-2 fps | wave-2 processing p50 / p95 | End to end, as seen by `ws_probe` |
|---|---|---|---|---|---|
| video (laptop clock, watcher) | 16.5 | 76 / 99 ms | 8.0 | 227 / 282 ms | 100 % of vehicle/pedestrian objects carry a distance |
| sim, localhost | 16.2 | 118 / 145 ms (includes the inbox wait, by design) | 8.1 | 269 / 324 ms | results ready 91 ms (p50) / 46 ms (p5) before their frame is shown; 0.45 % late; the tablet rule covers 99.6 % of display frames, staleness p50 28 / p95 62 ms; auto look-ahead 0.21 s; 56 `navigation.packet` from the real phase1 relay |
| sim, netem wifi-busy, margin 0.06 | 15.9 | 121 / 145 ms | 7.8 | 276 / 334 ms | lead p50 51 / p5 1 ms; 4.9 % late; coverage 100 % |
| sim, netem wifi-busy, margin 0.10 (new default; laptop busier) | 12.4 | 153 / 189 ms | 6.8 | 347 / 424 ms | lead p50 96 / p5 35 ms; 1.5 % late; coverage 99.8 % |
| sim, localhost, playback rate 2.0 (15 s, laptop busier) | 12.6 | - | - | - | results ready 179 ms (p50) / 121 ms (p5) before their frame is shown; 1.1 % late; look-ahead 0.6 media s; coverage 89 % (only about 1 in 5 media frames can be analysed at 2x) |
| live, localhost, maxInFlight 2 | 16.7 | 98 / 128 ms | 8.1 | 244 / 305 ms | capture->result 106 / 141 ms; 504/504 frames answered exactly once |
| live, netem wifi-busy | 14.5 | 69 / 108 ms | 8.6 | 210 / 299 ms | capture->result 119 / 192 ms |
| live, netem hotspot | 11.6 | 62 / 93 ms | 8.8 | 193 / 261 ms | capture->result 143 / 275 ms |
| live + live navigation (real relay, `--nav-route`, laptop busier) | 12.7 | 129 / 177 ms | 6.9 | 308 / 417 ms | 20 `client.trip_state` -> 20 `navigation.packet` |

Pipeline alone (`bench_lanes`, 20 s, city clip):

| Slow-lane setting | wave-1 Hz | wave-1 processing | wave-1 compute | distance Hz | distance age p50 |
|---|---|---|---|---|---|
| default | 16.7 | 76 / 106 ms | 57 / 79 ms | 8.6 | 267 ms |
| `slow.max_hz=5` | 19.0 | 66 / 105 ms | 48 / 83 ms | 4.9 | 267 ms |
| `slow_schedule.depth.every=2` | 15.4 | 79 / 110 ms | 63 / 89 ms | 11.3 | 233 ms |

The fast lane alone takes about 32 ms (detect 20, track 3, lights 7, decode 3) when the slow lane is idle.

## Tools

| Tool | What it does |
|---|---|
| `python -m perception.realtime.ws_probe watch\|sim\|live [...]` | Fake tablet. Validates every message against `contracts/schemas/`. **watch** is a watcher with no hello. **sim** sends hello plus 10 Hz playback from pts 0 and reports how early results arrive (lead p5/p50/p95, late fraction, display coverage under the tablet's "newest result with pts <= playback, within 150 ms" rule). **live** sends SDC1 frames under credits (`--rotate`, `--focal-px`, `--mount-height`, `--bad-header`) and reports capture-to-result p50/p95 on its own clock, answers per frame, skips by reason and fps. `--trip-states FILE.jsonl` replays `client.trip_state`. `--ping` measures RTT. `--save-samples DIR` writes golden samples. Any `navigation.packet` is printed |
| `python scripts/netem_proxy.py --listen 127.0.0.1:8766 --target 127.0.0.1:8765 --profile wifi-busy` | TCP proxy that adds one-way delay, jitter, spikes and a bandwidth cap per direction (profiles `usb`, `wifi-good`, `wifi-busy`, `hotspot`; every parameter can be overridden). Point the probe at the proxy port |
| `python -m perception.realtime.bench_lanes --seconds 30 [--variant JSON ...]` | The two-lane pipeline alone, at real-time speed, without sockets |
| `python tests/test_protocol_v2.py [--offline]` | Plain-Python tests: schemas, samples, SDC1 header, wire builders, geometry distance, nav worker, plus a real server subprocess for live loopback and sim tests |
| `python tests/make_golden_samples_v2.py` | Regenerates `contracts/samples/v2/perception.*` and `client.*` from real server runs (about 3 min) |
| `python -m perception.realtime.glasses_probe [--url ws://HOST:8000/ws] [--video CLIP] [--pad 640x480] [--save DIR]` | Fake glasses app: streams a clip as 640-px jpeg-base64 JSON frames at 8 fps without waiting, prints replies, errors and send-to-reply latency once a second, and with `--save` draws every reply onto the JPEG it answers (overlay check without a phone) |
| `python tests/test_glasses_server.py [--offline] [--url ws://127.0.0.1:8000/ws]` | Glasses listener tests: bad-frame reasons, `fit_frame` inverse, the document builder, plus a real server for one frame, errors, ping, an 8 fps burst, padded frames and one-stream-at-a-time |

## Glasses listener (port 8000)

A second, separate server for the glasses app: a Kotlin CameraX client that is not built from this repo and that does not speak protocol v2. It sends each upright camera frame as one JSON text frame and draws only the `instructions` of the JSON reply. The v2 server above is unchanged; both can run at once (two processes, each with its own engine: about 1.7 GB of GPU memory together, measured).

```text
python -m perception.realtime.glasses_server                     # ws://0.0.0.0:8000/ws, GET /health, GET /stats
```

- **Phone URL.** Emulator `ws://10.0.2.2:8000/ws`. Same Wi-Fi `ws://<laptop LAN IP>:8000/ws` (the LAN URL is printed at start-up); allow inbound TCP 8000 once from an admin PowerShell: `New-NetFirewallRule -DisplayName "Glasses socket 8000" -Direction Inbound -Protocol TCP -LocalPort 8000 -Action Allow -Profile Private,Public` (campus Wi-Fi such as eduroam is a Public network, and it may also block phone-to-laptop traffic altogether). USB: `adb reverse tcp:8000 tcp:8000`, then `ws://127.0.0.1:8000/ws`. Cleartext `ws://` only. A second server on a busy port exits at once.
- **In.** `{"frameId", "timestampMs", "encoding": "jpeg-base64", "width", "height", "data"}` (standard base64, an optional `data:image/jpeg;base64,` prefix, decoded JPEG under 8 MB). No handshake: the first message is a frame. The decoded image wins over `width` / `height`, and EXIF orientation is ignored (the phone already rotated the pixels). A JPEG header claiming more than 4096 px per side is refused before decoding.
- **Out.** One Spatial Instruction document per analysed frame: `frameId`, `timestampMs` (echoed), `perception` (`lanes` with the ego `left` / `right` boundaries, up to 3 in-front `vehicles` nearest first, up to 4 `signs`), a fixed demo `navigation` block (EXIT 54/56 in 0.4 mi from the rightmost lane) and `instructions` (`LANE_BOUNDARY`, one `LANE_ARROW` per lane, `EXIT_MARKER`, then `VEHICLE_MARKER` + `DISTANCE_LABEL` per vehicle, all with `lifetimeMs` 500). A bad frame gets `{"error": "<reason>"}` and the socket keeps reading. Builders and the exact rules: `glasses_wire.py`.
- **Ego lane lines.** The rule `laneBoundaries[currentLane - 1]`, `[currentLane]` assumes `laneCount + 1` lines, but the lanes block also returns uncounted chains (for example beyond a double yellow) and has no polyline for road edges or virtual boundaries: on the 7 BDD val clips only about 30 % of frames have `laneCount + 1` lines, and the index pair was the lanes block's own ego pair in 40-48 % of them. So the index pair is used only when that shape holds and the pair brackets the image centre; otherwise the lines whose mean x straddles the centre are sent (about 96 % agreement with the lanes block's ego tags). Lines are cut where they leave the image instead of being clamped to its edge.
- **Coordinates.** Every x / y is a 0..1 fraction of the JPEG the phone sent (top-left origin), clipped and rounded to 4 decimals. `step()` centre-crops frames of another aspect ratio before resizing them to 1280x720; `glasses_wire.FitMap` undoes that crop, so boxes and lane lines land on the JPEG and not on the fitted image.
- **Engine.** Loaded once, in the background, with the same `config_realtime.yaml` and warm-up as the v2 server; frames get `{"error": "engine is loading"}` and `/health` says `"loading"` until it is ready (about 20-60 s). One engine thread runs the serial `engine.step()` under a lock. The engine's temporal state belongs to one connection at a time: while it streams, another connection gets `{"error": "engine busy with another client"}` (two interleaved streams would reset the engine on every step, and lanes and signs never run on a stream's first step); it hands over when the owner closes or sends nothing for 2 s. A new owner, a jump in `timestampMs` (more than 2 s forward or 0.5 s back) or a new JPEG size (phone rotated) starts a new stream (`engine.reset()`).
- **Flow control.** The phone sends about 8 frames/s without waiting. Each connection keeps only its newest unanalysed frame; a frame that a newer one replaces gets no reply. Measured on the RTX 5060 Laptop with 640x360 frames: `step()` p50 70 ms, p95 79 ms, so 8 fps is answered frame for frame (send-to-reply 61-70 ms p50 over localhost). An error reply can overtake the document of an earlier frame that is still in the engine; documents never reorder. Messages over 32 MB close the socket (1009).

## Files

| File | Role |
|---|---|
| `server.py` | The server: CLI, sessions and roles, live uplink, `VideoSource`, `SimSource`, `NavWorker`, stats, HTTP `/health` and `/config` |
| `pipeline.py` | `TwoLanePipeline`: the fast and slow lane threads, each on its own CUDA stream (fast = higher priority), latest-wins inbox and snapshot slots, per-lane warm-up |
| `wire.py` | Message builders (`to_wire`, `make_update`, `make_hello`, `make_stats`, `make_skip`, `make_pong`, `make_error`) and the SDC1 header (`pack_uplink_header`, `parse_uplink`, `describe_uplink_header`) |
| `subscribers.py` | `PerceptionBus`: the server publishes every wave-1 and wave-2 dict in-process before it serialises them |
| `ws_probe.py`, `bench_lanes.py` | measurement tools (above) |
| `nav_relay.py` | owned by the navigation side; the server only calls its interface |
| `glasses_server.py`, `glasses_wire.py` | the glasses listener on port 8000 (above): socket, engine thread, and the frame decoder + `FrameResult` to Spatial Instruction mapper |
| `../engine.py` | `PerceptionEngine`: `fast_step` / `slow_step` (two lanes), `step` (serial), `geometry_distance`, session camera |
| `../config_realtime.yaml` | models, the two-lane schedule, and the serial schedule for offline tools |

## Known limits

- **Lane contention.** Both lanes run in one Python process. The fast lane takes about 32 ms alone but 50-60 ms (p50) while the slow lane runs back to back, and GPU utilisation stays at about 50 %: GIL and CPU contention between the lanes dominate. `--set slow.max_hz=6` buys roughly 10 ms of wave-1 compute at the cost of distance, lane and sign refresh rate (distance age p50 is unchanged at about 270 ms, see `bench_lanes`). A process-per-lane design would remove the contention.
- **Default focal length.** Video and sim clips have no intrinsics, so the BDD default of 700 px at 1280 wide applies. For live, send real intrinsics in `client.hello`.
- **Navigation relay.** Relay calls block the nav worker thread only. A hung child is restarted by `NavRelay` itself.
