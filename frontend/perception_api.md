# Perception API

The tablet app speaks **PROTOCOL_v2** only, through the shared Kotlin library
`perception_engine/android/perception-bridge` (`PerceptionBridge`). The one spec is
[`../perception_engine/contracts/PROTOCOL_v2.md`](../perception_engine/contracts/PROTOCOL_v2.md), with a JSON Schema per
message in `../perception_engine/contracts/schemas/` and golden samples in `../perception_engine/contracts/samples/v2/`.

In short: after `client.hello`, LIVE uplinks binary frames of a 24-byte `SDC1` header plus a baseline JPEG, at most
`uplink.maxInFlight` at a time; SIM reports `client.playback`; LIVE navigation sends `client.trip_state`. The laptop
answers with `perception.frame` (wave 1, with `echo.frameId`), `perception.update` (wave 2), `perception.skip`,
`navigation.packet` (phase1) and `perception.stats`. The ElevenLabs proxy is `POST /tts` on the same host and port.

The old `spatial.instruction` message of this app's first version is gone: no server sends it.
