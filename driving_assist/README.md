# Driving Assist — Glass Mode

Landscape Android template for the AI spatial driving copilot. The phone stays horizontal. A CameraX preview fills the screen, and a Jetpack Compose canvas draws the AR overlay on top.

This repo is the shell. It runs today on mock vision data so the UI can be demoed before the detectors are merged.

## Open and run

1. Install Android Studio and the Android SDK.
2. Open this folder (`driving_assist`).
3. Let Gradle sync. If it asks for the SDK path, point it at your local Android SDK. Do not commit `local.properties`.
4. Run `app` on a phone or emulator. Allow the camera. Rotate the device if it does not land in landscape on its own.

The top-right button switches **GLASS** (consumer HUD) and **DEBUG** (raw boxes, lane polylines, and the route action).

## Who owns what

| Piece | Owner | Status in this repo |
| --- | --- | --- |
| Camera preview, landscape shell, AR overlay, debug toggle | Glass UI | Done |
| Lanes, traffic signs, exit signs, vehicles | Engineer A (OpenCV) | Mocked. See [ENGINEER_A.md](ENGINEER_A.md) |
| Route maneuvers and spoken cues | Node.js route engine | Mocked. Cycles every 5 seconds |

Engineer A's agent should read [ENGINEER_A.md](ENGINEER_A.md) before editing. That file is the merge contract.

## Layout

```
app/src/main/java/com/drivingassist/glass/
  MainActivity.kt        wires preview + overlay
  CameraPreview.kt       CameraX full-screen preview, optional frame analyzer
  AROverlay.kt           Glass and Debug drawing
  Models.kt              JSON contracts
  VisionSource.kt        vision input interface + MockVisionSource
  PreviewCoordinates.kt  buffer coords -> overlay coords
  MockDataViewModel.kt   swap line for OpenCV, plus the route mock
```

Build from the command line with `gradlew.bat :app:assembleDebug` on Windows.
