# Engineer A — OpenCV vision input

You are merging lane, traffic-sign, exit-sign, and vehicle detection into this Android app. Your output is the **input** to Glass Mode. The overlay, camera shell, and route mock already exist. Do not rebuild them.

The activity is locked to landscape (`sensorLandscape`). Keep it that way. Detections are drawn on a wide preview, like a windshield, not a portrait phone.

## What you produce

Publish one `VisionData` object whenever your detector has a new frame. The overlay collects `VisionSource.visionData` and draws it. Glass Mode and Debug Mode both already render:

- `lanes` as polylines
- `signs` as labeled boxes
- `exitSigns` as labeled boxes with distance
- `vehicles` as reticles (Glass) or red rectangles (Debug)

If a list is empty, draw nothing for that category. Still publish the frame.

`RouteState` is **not yours**. It comes from the Node.js route engine. Leave the route loop in `MockDataViewModel` alone.

## The only swap

In `app/src/main/java/com/drivingassist/glass/MockDataViewModel.kt`, change the default source:

```kotlin
class MockDataViewModel @JvmOverloads constructor(
    private val visionSource: VisionSource = MockVisionSource(), // replace this
) : ViewModel()
```

Replace `MockVisionSource()` with your class. Keep `@JvmOverloads` so Android's `viewModel()` can still construct it with no arguments.

Your class must implement `VisionSource`. If it also implements `androidx.camera.core.ImageAnalysis.Analyzer`, `MainActivity` already passes it to `CameraPreview`, and frames start arriving. `MockVisionSource` does not implement `Analyzer`, so the mock path binds preview only.

Keep `MockVisionSource` in the repo. The app must still build and demo if your detector is not ready.

Suggested file: `app/src/main/java/com/drivingassist/glass/OpenCvVisionSource.kt`.

```kotlin
class OpenCvVisionSource : VisionSource, ImageAnalysis.Analyzer {
    private val _visionData = MutableStateFlow(VisionData(time = 0f))
    override val visionData: StateFlow<VisionData> = _visionData.asStateFlow()
    override val frameGeometry: FrameGeometry = FrameGeometry()

    override fun start() {
        // Load models here. Do not block the main thread.
    }

    override fun stop() {
        // Release native memory.
    }

    override fun analyze(image: ImageProxy) {
        try {
            val geometry = frameGeometry
            if (geometry.viewWidth <= 0f || geometry.viewHeight <= 0f) return
            val rotation = image.imageInfo.rotationDegrees
            // Detect in buffer space, then map before publishing.
            _visionData.value = VisionData(
                time = /* seconds */,
                vehicles = emptyList(),
                lanes = emptyList(),
                signs = emptyList(),
                exitSigns = emptyList(),
            )
        } finally {
            image.close()
        }
    }
}
```

`analyze` runs on a background camera thread. Assigning `MutableStateFlow.value` from that thread is fine. Always `image.close()` in a `finally` block, including when you return early. The use case is `STRATEGY_KEEP_ONLY_LATEST` and `YUV_420_888`. If your pipeline needs RGBA, change only this line in `CameraPreview.kt`:

```kotlin
.setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
```

Add the OpenCV dependency in `app/build.gradle.kts` only. Do not change `compileSdk`, the Compose setup, or the package name `com.drivingassist.glass`.

## Coordinate contract

Every `box` and every lane point must be in **overlay space**:

- The screen is landscape.
- `(0, 0)` is the top-left of the camera preview.
- `(1, 1)` is the bottom-right.
- `x` grows right, `y` grows down.
- A box is `[x, y, w, h]`: top-left plus width and height, each 0.0 to 1.0 of the **view**, not the camera buffer.

The preview is `PreviewView.ScaleType.FILL_CENTER`. The image is scaled until it covers the view, then cropped. A phone sensor is often mounted in portrait, so `image.imageInfo.rotationDegrees` is often 90 or 270 even though the activity is landscape. Raw `image.width` / `image.height` will not line up with the canvas.

Map detections with `PreviewCoordinates` before you construct `Vehicle`, `TrafficSign`, `ExitSign`, or `LaneLine`:

```kotlin
val box = PreviewCoordinates.mapBox(
    box = bufferBox, // [x, y, w, h] normalized to the ImageProxy buffer, before rotation
    rotationDegrees = image.imageInfo.rotationDegrees,
    bufferWidth = image.width,
    bufferHeight = image.height,
    viewWidth = frameGeometry.viewWidth,
    viewHeight = frameGeometry.viewHeight,
) ?: return

val points = PreviewCoordinates.mapPolyline(
    points = bufferPoints, // each pair is buffer-normalized, before rotation
    rotationDegrees = image.imageInfo.rotationDegrees,
    bufferWidth = image.width,
    bufferHeight = image.height,
    viewWidth = frameGeometry.viewWidth,
    viewHeight = frameGeometry.viewHeight,
)
```

`CameraPreview` writes `frameGeometry.viewWidth` and `viewHeight` after layout. If either is `0`, skip the frame.

Check alignment in **DEBUG**. A lane or sign should sit on the thing in the camera image. If it is rotated or shifted, fix the mapping in your source or in `PreviewCoordinates`. Do not hard-code pixel sizes in `AROverlay`.

## JSON shape

Field names are the Kotlin property names. Use empty lists, not nulls.

```json
{
  "time": 1.25,
  "vehicles": [
    { "id": 1, "box": [0.40, 0.50, 0.12, 0.18], "distanceMeters": 14.0 }
  ],
  "lanes": [
    {
      "id": "left",
      "points": [
        { "x": 0.06, "y": 0.98 },
        { "x": 0.30, "y": 0.64 },
        { "x": 0.42, "y": 0.40 }
      ]
    },
    {
      "id": "right",
      "points": [
        { "x": 0.94, "y": 0.98 },
        { "x": 0.70, "y": 0.64 },
        { "x": 0.56, "y": 0.40 }
      ]
    }
  ],
  "signs": [
    { "id": 1, "label": "SPEED_LIMIT_55", "box": [0.84, 0.28, 0.07, 0.16] }
  ],
  "exitSigns": [
    { "id": 1, "label": "EXIT 24", "box": [0.66, 0.08, 0.18, 0.12], "distanceMeters": 400.0 }
  ]
}
```

Rules:

- `time` is seconds.
- Lane `id` is `"left"` or `"right"` for the ego lane. Other ids are allowed and will still be drawn.
- Lane `points` has at least two points, ordered from the near road (larger `y`) toward the horizon (smaller `y`).
- Sign `label` is a short token such as `STOP`, `SPEED_LIMIT_55`, `YIELD`, or `WARNING`. The Glass UI replaces underscores with spaces.
- Exit `label` is the text to show, such as `EXIT 24`.
- `distanceMeters` is a positive float.
- You may publish lanes and signs before vehicles exist. Use `vehicles = emptyList()`.

Models live in `Models.kt`. The overlay will not see fields that are not on these classes. Add a field there only if the Glass UI needs to draw it, and draw it in both Debug and Glass inside `AROverlay.kt`.

## Files

| File | What to do |
| --- | --- |
| `VisionSource.kt` | Implement the interface. Leave `MockVisionSource` in place. |
| `MockDataViewModel.kt` | Change the one `MockVisionSource()` swap. Do not remove the route loop. |
| `Models.kt` | Read the contract. Extend it only when a new drawn field is required. |
| `PreviewCoordinates.kt` | Use it. Change it only if DEBUG shows a systematic rotation or crop error. |
| `CameraPreview.kt` | Already binds your `Analyzer`. Change the image format here only if you must. Do not remove `FILL_CENTER`. |
| `MainActivity.kt` | Already landscape, already wires the analyzer and geometry. Do not fork the layout. |
| `AROverlay.kt` | Already draws your lists. Do not replace it with a second HUD. |
| `app/build.gradle.kts` | Add your OpenCV (or ML) dependency here. |
| `AndroidManifest.xml` | Leave `android:screenOrientation="sensorLandscape"`. |

## Done when

- The app stays landscape.
- `gradlew.bat :app:assembleDebug` still succeeds.
- With your class swapped in, `visionData` updates from live frames.
- Lanes, signs, and exit signs show in both GLASS and DEBUG, aligned with the camera.
- Every `ImageProxy` is closed.
- `MockVisionSource` still exists, and the route mock still cycles every 5 seconds.
