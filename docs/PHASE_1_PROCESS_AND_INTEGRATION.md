# Phase 1 Process and Integration

This document describes the full Phase 1 path from input data to the handoff packet used by the rest of the project.

## 1. Purpose

Phase 1 turns a route request and live GPS samples into a deterministic spatial navigation packet.

It exists to answer:
- Where do we want to go?
- Where is the vehicle right now?
- What should the driver see or hear next?

## 2. Inputs

Phase 1 consumes two data streams.

### 2.1 Route input

The upper process provides navigation intent:
- origin
- destination query or resolved destination
- optional provider selection

The route provider resolves this into a normalized route snapshot:
- route id
- origin and destination coordinates
- route polyline or decoded geometry
- normalized route steps
- total distance and duration

### 2.2 Trip-state input

The Android collector writes live trip samples to `trip_state.jsonl`.

Each sample contains:
- timestampMs
- location.lat
- location.lng
- heading
- speedMps
- optional accuracyMeters

## 3. Capture Flow

Recommended capture flow:

1. Resolve origin and destination with Google Maps APIs or a mock provider.
2. Start the Android collector.
3. Collect GPS samples while driving.
4. Save the session locally on the phone.
5. Pull the session back to the computer with `adb`.
6. Add the route snapshot to the same session folder.
7. Run the Phase 1 processor on the merged files.

## 4. Processing Flow

The Phase 1 processor reads:
- `session_manifest.json`
- `route.json`
- `trip_state.jsonl`

It then:
- loads the latest trip sample
- projects the vehicle onto the route geometry
- computes route progress
- picks the next maneuver
- creates spatial instructions
- creates audio instructions

## 5. Output Handoff

Phase 1 emits one object:

`SpatialNavigationPacket`

This packet is the contract for later phases.

It includes:
- route identity
- destination metadata
- current progress
- active maneuver
- upcoming maneuvers
- spatial instructions
- audio instructions
- route semantics

## 6. How Phase 1 Fits the Whole Project

Phase 1 is the bridge between raw data and the rest of the system.

Upstream:
- Android collector
- Google route lookup
- optional simulation or mock route provider

Phase 1 core:
- normalize
- merge
- compute navigation state
- emit packet

Downstream:
- AR renderer
- audio output
- later perception fusion
- future connected-driving logic

## 7. Streamlining Rules

Keep the phase clean by following these rules:

- Do not let the Android app own route logic.
- Do not let the processor depend on raw Google response shapes.
- Do not let later phases read phone files directly.
- Always convert raw inputs into the normalized session contract first.
- Keep the packet deterministic and explainable.

## 8. Practical Demo Path

For hackathon use, the shortest reliable path is:

1. Use Google Maps API to resolve the route.
2. Use the Android app to capture GPS samples.
3. Pull the data with `adb`.
4. Run the Phase 1 processor.
5. Feed the packet into the next process.
