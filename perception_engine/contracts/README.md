# Contracts: laptop perception and navigation to the tablet

Wire contracts of the AI Spatial Driving Copilot. The laptop (perception
models plus the phase1 route engine) and the Samsung Galaxy Tab S9 app talk over one WebSocket,
`ws://<host>:8765/perception`.

**Protocol v2 is current.** [`PROTOCOL_v2.md`](PROTOCOL_v2.md) is the source of truth: transport, modes, the SDC1
camera uplink with credits, every message, Navigation, Sessions and the client rules. Field-level reference with
examples: [`docs/INTERFACES.md`](../docs/INTERFACES.md).

## Files

| Path | What |
|---|---|
| [`PROTOCOL_v2.md`](PROTOCOL_v2.md) | The protocol (normative) |
| [`schemas/`](schemas/) | One JSON Schema (draft 2020-12) per message type: `<type>.schema.json` |
| [`samples/v2/`](samples/v2/) | Golden samples from real server and relay runs (19 JSON files) plus `uplink_header.example.txt` (a real SDC1 header, byte by byte) |
| [`perception_frame.v1.schema.json`](perception_frame.v1.schema.json), [`samples/*.json`](samples/) | The v1 frame contract and its samples, kept because v2 keeps every v1 `perception.frame` field and the Kotlin decoder still reads v1 |

## Messages

| Direction | Type | When | Schema | Sample |
|---|---|---|---|---|
| tablet -> laptop | `client.hello` | first message after every (re)connect; required for live and sim | `client.hello.schema.json` | `client.hello.live.json`, `client.hello.sim.json` |
| tablet -> laptop | `client.playback` | sim, about 10 Hz and on seek / pause / resume | `client.playback.schema.json` | `client.playback.json` |
| tablet -> laptop | `client.ping` | optional, about 1 Hz | `client.ping.schema.json` | `client.ping.json` |
| tablet -> laptop | `client.trip_state` | live navigation, about 1 Hz from the GPS | `client.trip_state.schema.json` | `client.trip_state.json` |
| tablet -> laptop | binary: 24-byte SDC1 header + JPEG | live camera uplink, under credits | (PROTOCOL_v2.md table) | `uplink_header.example.txt` |
| laptop -> tablet | `perception.hello` | on connect, after each `client.hello`, on every new session | `perception.hello.schema.json` | `perception.hello.{live,sim,video}.json` |
| laptop -> tablet | `perception.frame` | wave 1, every analysed frame | `perception.frame.schema.json` | `perception.frame.wave1.{city,night,live}.json` |
| laptop -> tablet | `perception.update` | wave 2, whenever the slow lane finishes | `perception.update.schema.json` | `perception.update.wave2.city.json` |
| laptop -> tablet | `perception.skip` | live: an uplinked frame that gets no `perception.frame` | `perception.skip.schema.json` | `perception.skip.json` |
| laptop -> tablet | `perception.stats` | about 1 Hz | `perception.stats.schema.json` | `perception.stats.json` |
| laptop -> tablet | `perception.pong` | answer to `client.ping` | `perception.pong.schema.json` | `perception.pong.json` |
| laptop -> tablet | `perception.error` | a message could not be honoured | `perception.error.schema.json` | `perception.error.json` |
| laptop -> tablet | `navigation.packet` | phase1 route state: sim about 2 Hz, live one per trip state | `navigation.packet.schema.json` | `navigation.packet.{sim_city,sim_highway,live}.json` |

## Who produces and consumes them

| Side | Code |
|---|---|
| Python producer (server) | `perception_engine/perception/realtime/wire.py` (builders, SDC1 header), `server.py` (behaviour) |
| Navigation producer | `perception_engine/nav/relay_core.js` (envelope and `routeState`; `packet` is phase1's verbatim) |
| Kotlin consumer (tablet) | `perception_engine/android/perception-bridge/src/main/kotlin/com/drivingassist/copilot/perception/` (`Messages.kt`, `ClientMessages.kt`, `PerceptionFrame.kt`, `UplinkHeader.kt`, `PerceptionCodec.kt`) |
| Python fake tablet | `perception_engine/perception/realtime/ws_probe.py` (validates everything it receives against `schemas/`) |

## Tests that guard the contract

```powershell
# from perception_engine/ (project venv)
.venv\Scripts\python.exe tests\test_protocol_v2.py --offline     # schemas well-formed, every sample validates, SDC1 round trip, wire builders
.venv\Scripts\python.exe tests\test_protocol_v2.py               # + a real server loopback (live, sim, errors, skips)
# from frontend/ (the module lives in perception_engine/android/perception-bridge)
.\gradlew.bat :perception-bridge:test                            # ProtocolV2Test (decode + round-trip every sample), ContractFieldCoverageTest
```

`ContractFieldCoverageTest` fails when a sample field is not modelled in Kotlin (apart from a short list of server
diagnostics) or when a value changes on the Kotlin round trip, so a renamed field breaks a test on both sides.

Regenerate the samples after a producer change (from `perception_engine/`):

```powershell
.venv\Scripts\python.exe tests\make_golden_samples_v2.py        # perception.* and client.* (3 real server runs, about 3 min, GPU)
node nav/make_contract_samples.js                                # navigation.packet.* and client.trip_state (deterministic)
```

## Versioning and changes

- Adding an optional (nullable) field is non-breaking: both decoders ignore unknown keys.
- A breaking change bumps `schemaVersion` (Python `wire.py` `SCHEMA_VERSION`, the schemas' `const`, Kotlin
  `PerceptionFrame.SCHEMA_VERSION`); a transport change also bumps `protocolVersion`.
- Change the spec, the schema, the samples, the Python producer and the Kotlin consumer in the same change; the
  checklist is in [`AGENTS.md`](../AGENTS.md#changing-the-protocol-checklist).

## Semantics worth knowing

- Coordinates (`bbox` `[x1, y1, x2, y2]`, lanes, road) are pixels of the **upright** analysed image (`image.width` x
  `image.height`); the tablet maps them to its 0..1 overlay space.
- Every uplinked frame gets exactly one wave-1 answer (`perception.frame` with a matching `echo.frameId`, or
  `perception.skip`); clients never exceed `uplink.maxInFlight` and drop frames instead of queueing.
- Wave 1 carries the latest slow-lane results (`blockAges`, `distanceAgeMs`), so a client that ignores wave 2 still
  works.
- A new `sessionId` resets the client's per-session state; track ids are only comparable within a session.
- All distances, TTC and light states are estimates for display (plan sections 18 and 38): `UNKNOWN` means unknown,
  and nothing here may drive vehicle control.

## v1 (legacy)

The first contract had one message type per direction: `perception.hello`, `perception.frame` (schema
[`perception_frame.v1.schema.json`](perception_frame.v1.schema.json), samples in [`samples/`](samples/)) and
`perception.stats`, with an optional binary uplink of an 8-byte pts followed by a JPEG. v2 replaced the uplink with the
24-byte SDC1 header and credits, split results into two waves, and added sim mode, sessions and navigation. Python can
still build v1 frames with `wire.to_wire(result, meta, schema_version=1)`, and the Kotlin decoder accepts
`schemaVersion` 1 and 2. New code should target v2 only.
