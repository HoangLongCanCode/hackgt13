# Phase 1 Upstream Data Contract

This file defines the exact local data shape the Phase 1 processor expects.

Design goals:
- Keep capture simple
- Keep offline replay deterministic
- Avoid special cases between simulated and production data
- Keep the processing layer independent from the source API

## 1. Session Layout

Use one directory per drive session.

Example:

```text
session_2026_09_25_001/
├── session_manifest.json
├── route.json
├── trip_state.jsonl
└── video.mp4
```

Only the first three files are required for Phase 1 processing.

## 2. Files

### 2.1 `session_manifest.json`

Purpose:
- Identifies the session
- Tells the processor which files to load

Schema:

```json
{
  "sessionId": "session_2026_09_25_001",
  "capturedAtMs": 1730000000000,
  "videoFile": "video.mp4",
  "routeFile": "route.json",
  "tripStateFile": "trip_state.jsonl",
  "source": "simulated|device",
  "notes": "optional free text"
}
```

### 2.2 `route.json`

Purpose:
- Stores the normalized route result from the routing provider or capture layer

Schema:

```json
{
  "routeId": "route_001",
  "provider": "google",
  "origin": { "lat": 33.7756, "lng": -84.3963 },
  "destination": {
    "label": "Georgia Tech Student Center",
    "placeId": "optional-place-id",
    "coordinate": { "lat": 33.7731, "lng": -84.3974 }
  },
  "polyline": "encoded_polyline_here",
  "geometry": [
    { "lat": 33.7756, "lng": -84.3963 },
    { "lat": 33.7752, "lng": -84.3958 }
  ],
  "steps": [
    {
      "stepId": "step_1",
      "instruction": "Head south on State St",
      "maneuver": "straight",
      "distanceMeters": 240,
      "durationSeconds": 32,
      "roadName": "State St"
    }
  ],
  "totalDistanceMeters": 1240,
  "totalDurationSeconds": 180,
  "createdAtMs": 1730000000000
}
```

Notes:
- `geometry` is the decoded route path so the processor does not need to depend on Google-specific decoding logic.
- `steps` are already normalized into the project-owned shape.

### 2.3 `trip_state.jsonl`

Purpose:
- Stores the live or simulated vehicle state over time
- Each line is one timestamped sample

Schema per line:

```json
{
  "timestampMs": 1730000000123,
  "location": { "lat": 33.7756, "lng": -84.3963 },
  "heading": 91.2,
  "speedMps": 13.1,
  "accuracyMeters": 4.1
}
```

Required fields:
- `timestampMs`
- `location.lat`
- `location.lng`
- `heading`
- `speedMps`

Optional fields:
- `accuracyMeters`

## 3. Why JSONL for Trip State

JSONL is the simplest reliable format for streaming samples because:
- it can be appended one record at a time
- it is easy to generate from a capture script
- it preserves exact timestamps
- it is easy to replay offline

## 4. Processor Expectations

The Phase 1 processor expects:
- one manifest
- one normalized route file
- one telemetry stream file

It does not need to know whether the data came from:
- simulated GPS
- a recording device
- a real vehicle

It only needs the normalized shapes above.
