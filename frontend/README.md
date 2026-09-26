# Spatial Copilot

Android glasses frontend for the AI Spatial Driving Copilot. This repo is only the phone/tablet app. The perception models and the route engine stay on the laptop; this app captures a driving video, sends frames, and draws the spatial cues.

Point the rear camera at a monitor playing a first-person drive. The preview is the simulated visual field. Lane arrows, the lead vehicle's distance, signs, and the exit HUD are drawn on top.

## What you see

- Three lane arrows on the road. The lane the driver should be in is lit.
- Until a route packet arrives, a local placeholder treats the drive as **Exit 56**. Inside half a mile the right lane lights up and the HUD reads **EXIT 56** / **0.4 mi**.
- A lead vehicle shows a distance such as **18 m**.
- The status chip is the WebSocket state. Tap it to change the server URL.

The lit lane is chosen in `NavigationLogic.selectLane`. The canvas in `SpatialArEngine` only draws what that function already decided. That is the seam for a later Google Routes integration.

## Run

1. Open this folder in Android Studio and run the `app` configuration on a phone or tablet. The activity is landscape.
2. Allow the camera.
3. Start the perception server on the laptop (`ws://<host>:8765/perception`).
4. USB (preferred):

```bat
adb reverse tcp:8765 tcp:8765
```

The default URL is `ws://127.0.0.1:8765/perception`.

5. Wi-Fi: tap the status chip and enter `ws://<laptop-LAN-IP>:8765/perception`. Phone and laptop must be on the same network.
6. Aim the camera at the monitor.

While the socket is down, the overlay stays on a built-in highway sketch so the glasses are not blank. The chip says `DEMO`. When frames arrive, lane lines and vehicles come from the server. The chip says `LIVE`, or `LIVE · sim nav` if geometry is live and the exit is still the local placeholder.

## Layout

| Path | Role |
|---|---|
| `app/src/main/java/com/drivingassist/spatialcopilot/ui/SpatialArEngine.kt` | Spatial AR Engine. Lane arrows, edges, distances, signs. |
| `app/src/main/java/com/drivingassist/spatialcopilot/nav/NavigationLogic.kt` | Lane choice and the Exit 56 placeholder. |
| `app/src/main/java/com/drivingassist/spatialcopilot/perception/PerceptionClient.kt` | WebSocket, `client.hello`, JPEG uplink. |
| `app/src/main/java/com/drivingassist/spatialcopilot/perception/Protocol.kt` | Maps server JSON into a `SpatialInstruction`. |
| `perception_api.md` | Wire format for the other developer. |

JVM checks for the header, the exit HUD, and the JSON mapping:

```bat
gradlew.bat :app:testDebugUnitTest
```

## Contract

Camera uplink is a 24-byte `SDC1` header plus a baseline JPEG (about 960×540, quality 80). The server may answer with perception protocol v2 (`perception.frame`, `perception.update`, `navigation.packet`) or with one `spatial.instruction` JSON object. Both become the same drawing input. Field-level detail is in `perception_api.md`.
