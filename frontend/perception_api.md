# Perception API

This app is the glasses. It does not run lane detection, sign detection, or routing. It sends camera frames and draws a **Spatial Instruction**.

Two server shapes are accepted on the same socket:

1. **Protocol v2** from the perception laptop: `perception.frame`, `perception.update`, `navigation.packet`. The app folds those into a Spatial Instruction. This is the live path.
2. **`spatial.instruction`** as a single JSON text frame. The Spatial AR Engine draws it as sent. Use this if a placeholder server wants to skip the perception schema.

The drawing code never chooses a lane. `arrow.highlighted` is already set when the canvas runs.

## WebSocket

- URL: `ws://<host>:8765/perception`
- USB: `adb reverse tcp:8765 tcp:8765`, then `ws://127.0.0.1:8765/perception` (the app default).
- Wi-Fi: `ws://<laptop-LAN-IP>:8765/perception`. Cleartext `ws://` is allowed so a LAN address works. `wss://` is also accepted.
- First text message from the app, after the socket opens, is `client.hello`.
- Text frames both ways are JSON.
- Binary frames are camera uplink only (live mode).
- The server hello includes `uplink.maxInFlight` (default 2). The app keeps at most that many frames without an answer. If there is no credit, the frame is dropped. It is not queued.
- An answer that returns a credit is `perception.frame`, `perception.skip`, or `spatial.instruction`. `perception.update` does not return a credit (it is the slow lane for a frame already answered).
- A credit still in flight after 1 second is released locally so one lost answer cannot stall the camera.
- Reconnect is automatic, with backoff from 1 s to 8 s. `client.hello` is sent again after every connect, and again if the upright JPEG size changes.

### `client.hello`

```json
{
  "type": "client.hello",
  "protocolVersion": 2,
  "clientId": "spatial-copilot",
  "device": { "manufacturer": "samsung", "model": "SM-X710", "osVersion": "16" },
  "mode": "live",
  "camera": {
    "imageWidth": 960,
    "imageHeight": 540,
    "focalPx": 864.0,
    "principalPoint": [480.0, 270.0],
    "mountHeightMeters": 1.25,
    "pitchDegrees": null,
    "lensFacing": "back",
    "stabilization": false
  },
  "sim": null
}
```

`imageWidth` / `imageHeight` are the **upright** size (after `rotationDegrees`). `focalPx` is a stand-in (`0.9 * imageWidth`) until a measured intrinsic is wired. `pitchDegrees: null` means the server may estimate pitch. `mountHeightMeters` is 1.25.

The app sends `mode: "live"` because the video is whatever the phone camera sees, including a monitor.

## Input frame

One binary WebSocket message:

```
24-byte header, little-endian, then the JPEG bytes
```

Python layout: `struct.Struct("<4sHHIqHH")`.

| Offset | Type | Field | Value |
|---|---|---|---|
| 0 | 4 bytes ASCII | magic | `SDC1` |
| 4 | uint16 | headerVersion | `1` |
| 6 | uint16 | flags | `0` |
| 8 | uint32 | frameId | client counter, wraps |
| 12 | int64 | captureTimeNs | `ImageProxy.imageInfo.timestamp`, or elapsed realtime if that is 0 |
| 20 | uint16 | rotationDegrees | `0`, `90`, `180`, or `270`. Rotate the JPEG clockwise by this to make it upright |
| 22 | uint16 | reserved | `0` |

JPEG:

- Baseline JPEG, sensor orientation (not pre-rotated).
- Analysis target **960×540**, 16:9. The device may return the closest size.
- Quality **80**.
- The preview is also 16:9 (`FILL_CENTER`), so upright server coordinates line up with the screen.

Reference header for `frameId = 1`, `captureTimeNs = 342385412986900`, `rotationDegrees = 0`:

```
53 44 43 31 01 00 00 00 01 00 00 00 14 d8 ea d0 65 37 01 00 00 00 00 00
```

Encoder: `app/src/main/java/com/drivingassist/spatialcopilot/model/UplinkHeader.kt`.

YUV conversion for the JPEG is `camera/YuvJpeg.kt`. The camera pipeline stops at the socket. Nothing in this repo thresholds, Hough-transforms, or classifies a frame.

## Spatial Instruction

This is the only payload the Spatial AR Engine reads (`ui/SpatialArEngine.kt`).

Coordinates are **upright image pixels**. `x` grows right, `y` grows down. `image.width` and `image.height` are that space. The engine maps them onto the preview with cover-and-crop (`FILL_CENTER`). Do not send view pixels or normalized 0–1 values.

`distanceLabel` and the exit's `distanceLabel` are display strings. Meters are the source of truth. The app formats meters as `"18 m"` (nearest meter) and exit distance as miles with one decimal (`"0.4 mi"`, using 1609.344 m per mile). A server that sends `spatial.instruction` directly should include both.

```json
{
  "type": "spatial.instruction",
  "schemaVersion": 1,
  "source": "live",
  "timeSeconds": 12.4,
  "image": { "width": 960, "height": 540 },
  "currentLane": 2,
  "laneCount": 3,
  "lanes": [
    {
      "index": 1,
      "recommended": false,
      "boundaries": [
        [[70, 520], [430, 230]],
        [[250, 530], [470, 230]]
      ],
      "arrow": {
        "laneIndex": 1,
        "anchor": [190, 400],
        "heading": "STRAIGHT",
        "highlighted": false
      }
    },
    {
      "index": 2,
      "recommended": false,
      "boundaries": [
        [[250, 530], [470, 230]],
        [[640, 530], [510, 230]]
      ],
      "arrow": {
        "laneIndex": 2,
        "anchor": [470, 400],
        "heading": "STRAIGHT",
        "highlighted": false
      }
    },
    {
      "index": 3,
      "recommended": true,
      "boundaries": [
        [[640, 530], [510, 230]],
        [[900, 520], [560, 230]]
      ],
      "arrow": {
        "laneIndex": 3,
        "anchor": [740, 400],
        "heading": "RIGHT",
        "highlighted": true
      }
    }
  ],
  "vehicles": [
    {
      "id": 17,
      "box": [430, 214, 548, 392],
      "distanceMeters": 18.4,
      "distanceLabel": "18 m",
      "inFront": true
    }
  ],
  "signs": [
    {
      "id": 1000001,
      "label": "SPEED LIMIT 55",
      "box": [760, 48, 900, 140]
    }
  ],
  "navigation": {
    "action": "EXIT_HIGHWAY",
    "requiredLane": 3,
    "turnDirection": "right",
    "audio": "Take exit 56 from the right lane",
    "exit": {
      "label": "EXIT 56",
      "distanceMeters": 643.7,
      "distanceLabel": "0.4 mi"
    }
  }
}
```

### Fields

| Field | Meaning |
|---|---|
| `type` | Always `spatial.instruction`. |
| `schemaVersion` | `1`. |
| `source` | `demo` (no perception yet), `live` (perception + route packet), `live-sim-nav` (perception, exit still simulated), or `spatial` (this JSON was drawn as sent). |
| `timeSeconds` | Perception media time, or seconds since the demo started. |
| `image` | Upright pixel space for every point and box below. |
| `currentLane` | 1-based lane the vehicle is in, from the left. Null if unknown. |
| `laneCount` | How many lane arrows to draw. |
| `lanes` | Lane array. One entry per lane, left to right. `index` is 1-based. |
| `lanes[].boundaries` | Usually two polylines, left edge then right edge. Each point is `[x, y]` in image pixels. Shared edges are repeated on the neighboring lane. |
| `lanes[].arrow` | The reusable lane-arrow primitive. `anchor` is `[x, y]` in image pixels, near the bottom of that lane. `heading` is `STRAIGHT`, `LEFT`, or `RIGHT`. |
| `lanes[].recommended` and `arrow.highlighted` | Same decision. The engine lights this arrow and fills this lane. |
| `vehicles` | Markers for vehicles in front. `box` is `[x1, y1, x2, y2]` pixels. |
| `vehicles[].distanceMeters` | Distance to that vehicle. `distanceLabel` is the string drawn on the reticle. |
| `vehicles[].inFront` | True when the vehicle is the lead (server `inEgoPath`, or the nearest measured vehicle if none is flagged). |
| `signs` | Detected signs. `label` is already display text (`SPEED LIMIT 55`, `STOP`, `RED LIGHT`). `box` is `[x1, y1, x2, y2]`. |
| `navigation.action` | Maneuver name, for example `EXIT_HIGHWAY`, `TURN_RIGHT`, `GO_STRAIGHT`. |
| `navigation.requiredLane` | 1-based lane index to illuminate. |
| `navigation.exit` | Highway exit HUD. `label` is `EXIT 56`. `distanceLabel` is `0.4 mi`. Null hides the card. |
| `navigation.audio` | Caption along the bottom of the glass. |

Empty `lanes`, `vehicles`, or `signs` arrays are valid. Omit a category with `[]`, not by leaving the frame out.

Codec: `model/SpatialJson.kt`. The unit test `SpatialContractTest` round-trips a demo frame and checks the Exit 56 labels.

## How protocol v2 becomes that JSON

Implemented in `perception/Protocol.kt` (`ProtocolDecoder`, `World`) and `nav/NavigationLogic.kt`.

| Spatial Instruction | Server message |
|---|---|
| `image` | `perception.frame.image` (`width`, `height`), upright pixels. |
| `lanes[].boundaries` | `lanes.laneBoundaries`, left to right. Lane `i` (1-based) uses boundary `i - 1` and boundary `i`. |
| `currentLane`, `laneCount` | `lanes.currentLane`, `lanes.laneCount`. |
| `vehicles` | `objects[]` whose `class` is `car`, `truck`, `bus`, `motorcycle`, `bicycle`, `pedestrian`, or `rider`. `bbox` is `[x1, y1, x2, y2]`. `inEgoPath` becomes `inFront`. |
| `vehicles[].distanceMeters` | `objects[].distanceMeters`, replaced by `perception.update.distances[]` when a slower distance arrives for the same `id`. |
| `signs` | `signs[].signClass` (`speedLimit55` → `SPEED LIMIT 55`) plus traffic lights with a known `lightState` (`red` → `RED LIGHT`). `unknown` is dropped. |
| `navigation` | `navigation.packet.routeState`: `action`, `audio`, `distanceMeters`, `turnDirection`, `roadName`, `requiredLane`. |
| `navigation.exit.label` | `EXIT` plus a number found in `roadName` or `audio` (`Exit 56` → `EXIT 56`) when the maneuver is an exit. |
| `arrow.highlighted` | `NavigationLogic.selectLane` (below). |

`perception.hello` sets `uplink.maxInFlight` and `role`. `role: "watcher"` stops the uplink and shows `TAKEN OVER`. A new `sessionId` clears tracks. `sessionId: "idle"` returns the glass to the demo sketch.

If no `navigation.packet` has arrived, the app still builds an exit cue locally so the HUD is not empty: action `EXIT_HIGHWAY`, road `Exit 56`, distance moving around 0.4 mi. The chip then reads `LIVE · sim nav`. The first real `navigation.packet` replaces that cue.

## Lane choice (Google Routes placeholder)

`NavigationLogic.selectLane` is the only place a lane is chosen.

1. If `routeState.requiredLane` is set, use it. A number (`2`) is that lane. `"left"` / `"right"` / `"middle"` pick the outside or center lane. A range such as `"2-3"` uses the left end on a left maneuver and the right end on an exit or right maneuver.
2. If it is null and the maneuver is an exit or turn **within 0.5 mi (804.7 m)**, pick the outside lane on that side (rightmost for `EXIT_HIGHWAY`).
3. Otherwise keep `currentLane`.

The highlighted arrow's `heading` is `LEFT`, `RIGHT`, or `STRAIGHT` from the maneuver. Other lanes stay `STRAIGHT`.

To plug in Google Routes or the phase1 route engine, keep sending `navigation.packet` with `routeState.requiredLane` set. Do not edit `SpatialArEngine`. If you would rather send the finished decision yourself, send `spatial.instruction` and set `arrow.highlighted` on the server. The app will not recompute it.

## Swapping the Hough transform for a real lane model

This Android project has no Hough transform and no OpenCV lane function. Frames leave the phone as JPEG. Lane polylines come back inside `perception.frame.lanes` or `spatial.instruction.lanes`.

The reference perception stack already replaced classical line finding with a trained lane model:

- `perception_engine/perception/lanes/lanes.py` — `LaneDetector.analyze(frame_bgr)` returns `LaneState` (`currentLane`, `laneCount`, `laneBoundaries`, `confidence`).
- `perception_engine/perception/lanes/backends.py` — `make_backend(...)`. Backends: `twinlitenetplus_large` (default), `twinlitenetplus_medium`, `yolop_onnx`, `comma10k_segnet`.

If the server you are integrating still has a placeholder such as `detect_lanes_hough` / `cv2.HoughLinesP`:

1. Leave the function's return shape alone: upright-image polylines, plus `currentLane` and `laneCount`.
2. Replace the body with one call:

```python
from perception.lanes import LaneDetector

_detector = LaneDetector(backend="twinlitenetplus_large", device="cuda")

def detect_lanes(frame_bgr):
    analysis = _detector.analyze(frame_bgr)
    state = analysis.lane_state
    return state.currentLane, state.laneCount, state.laneBoundaries
```

3. Publish that through the existing JSON, either as `perception.frame.lanes`:

```json
{
  "currentLane": 2,
  "laneCount": 3,
  "laneBoundaries": [[[70, 520], [430, 230]], [[250, 530], [470, 230]]],
  "confidence": 0.8
}
```

or, if your server speaks only the spatial payload, as `lanes[].boundaries` in `spatial.instruction`.

4. Do not change `UplinkHeader`, `SpatialArEngine`, or the field names in the tables above. A new model is a server change. The glasses keep drawing polylines and arrows.

`laneBoundaries` are ordered left to right, each polyline a list of `[x, y]` points in the upright frame (the JPEG after `rotationDegrees`). Lane `i` sits between boundary `i - 1` and boundary `i`.

To try another network, change the `backend=` argument. The wire format stays the same, so this app does not need a matching change.
