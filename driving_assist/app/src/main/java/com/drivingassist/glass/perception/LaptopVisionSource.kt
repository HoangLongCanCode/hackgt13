package com.drivingassist.glass.perception

import android.graphics.ImageFormat
import android.util.Log
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.drivingassist.glass.FrameGeometry
import com.drivingassist.glass.VisionData
import com.drivingassist.glass.VisionSource
import com.ksr.copilot.perception.ClientCamera
import com.ksr.copilot.perception.ClientHello
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * LIVE mode: tablet camera -> laptop -> Glass overlay.
 *
 * - [analyze] (CameraX analyzer thread, `STRATEGY_KEEP_ONLY_LATEST`): if the bridge has a free
 *   credit, YUV_420_888 -> JPEG q80 (<= 960 px wide) -> `offerCameraFrame(jpeg, captureTimeNs,
 *   rotationDegrees)`. Never waits on the network (the bridge drops frames without a credit) and
 *   always closes the image. Needs `OUTPUT_IMAGE_FORMAT_YUV_420_888` (CameraPreview's default):
 *   another format is reported once in the status chip and nothing is uplinked.
 * - A ~30 Hz publisher on a background dispatcher maps `bridge.predictedAt(now)` (results moved
 *   forward from capture time to now) into [VisionData] with [VisionMapper]; it idles while the
 *   activity is stopped.
 *
 * Asks [com.drivingassist.glass.CameraPreview] for a 16:9 ~1280x720 analysis stream; the preview
 * gets the same 16:9 aspect ratio (same field of view, which the overlay mapping assumes) at
 * CameraX's preview size, so it stays sharp on the 2560x1600 screen.
 */
class LaptopVisionSource(override val runtime: PerceptionRuntime) :
    VisionSource, ImageAnalysis.Analyzer, CameraResolutionHint, BridgeBacked {

    private val _visionData = MutableStateFlow(VisionData(time = 0f))
    override val visionData: StateFlow<VisionData> = _visionData.asStateFlow()
    override val frameGeometry: FrameGeometry = FrameGeometry()
    override val preferredCameraResolution: Size = Size(1280, 720)

    private val encoder = YuvJpegEncoder(maxWidth = 960, quality = 80)
    private var publisher: Job? = null

    /** Upright size + buffer width announced in the current hello (written under [helloLock]). */
    @Volatile private var announced: Triple<Int, Int, Int> = Triple(960, 540, 960)
    private val helloLock = Any()
    private var loggedFirstFrame = false
    private var reportedBadFormat = false

    override fun start() {
        // The hello carries the camera intrinsics (CameraManager binder calls): build it off the main thread.
        runtime.scope.launch { sendHello(null) }
        if (publisher?.isActive == true) return
        publisher = runtime.scope.launch {
            while (isActive) {
                if (!runtime.hostStarted) { delay(200); continue } // activity stopped: nothing is drawn
                val g = frameGeometry
                val world = runtime.bridge.predictedAt(runtime.clockNs())
                _visionData.value = VisionMapper.map(world, g.viewWidth, g.viewHeight)
                delay(33)
            }
        }
    }

    /** (Re-)sends the hello; [upright] = new upright size + buffer width, null = the current one. */
    private fun sendHello(upright: Triple<Int, Int, Int>?) = synchronized(helloLock) {
        if (upright != null) announced = upright
        val a = announced
        runtime.start(hello(a.first, a.second, a.third))
    }

    override fun stop() {
        publisher?.cancel()
        publisher = null
        runtime.close()
    }

    override fun analyze(image: ImageProxy) {
        try {
            val bridge = runtime.bridge
            if (!bridge.canUplinkNow()) {
                bridge.noteFrameSkippedByCaller() // no credit / not connected: skip the encode
                return
            }
            val rotation = image.imageInfo.rotationDegrees
            val captureNs = runtime.toBridgeClock(image.imageInfo.timestamp)
            val jpeg = encoder.encode(image)
            if (jpeg == null) {
                bridge.noteFrameSkippedByCaller()
                if (!reportedBadFormat) {
                    reportedBadFormat = true
                    val why = if (image.format != ImageFormat.YUV_420_888) "camera format ${image.format} is not YUV_420_888" else "JPEG encode failed (${image.width}x${image.height})"
                    Log.e(TAG, "LIVE uplink stopped: $why (CameraPreview must keep OUTPUT_IMAGE_FORMAT_YUV_420_888)")
                    runtime.problem = "LIVE uplink: $why"
                }
                return
            }
            val upright = if (rotation % 180 == 0) Triple(jpeg.width, jpeg.height, jpeg.width) else Triple(jpeg.height, jpeg.width, jpeg.width)
            // Intrinsics describe the uplinked upright image: re-send the hello once when it differs (the
            // server also starts a new session when the frame size changes).
            if (upright != announced) sendHello(upright)
            if (!loggedFirstFrame) {
                loggedFirstFrame = true
                Log.i(TAG, "camera ${image.width}x${image.height} rot $rotation -> jpeg ${jpeg.width}x${jpeg.height} ${jpeg.length / 1024} KB")
            }
            // jpeg.bytes is the encoder's reused buffer: the bridge copies the first jpeg.length bytes before returning.
            bridge.offerCameraFrame(jpeg.bytes, captureNs, rotation, jpeg.length)
        } catch (t: Throwable) {
            Log.w(TAG, "camera frame not sent", t)
        } finally {
            image.close()
        }
    }

    private fun hello(uprightWidth: Int, uprightHeight: Int, bufferWidth: Int): ClientHello {
        val cfg = runtime.config
        return ClientHello.live(
            clientId = runtime.clientId,
            camera = ClientCamera(
                imageWidth = uprightWidth,
                imageHeight = uprightHeight,
                focalPx = runtime.focalPerBufferWidth?.let { Math.round(it * bufferWidth * 10.0) / 10.0 },
                principalPoint = null,
                mountHeightMeters = cfg.mountHeightMeters,
                pitchDegrees = null,
                lensFacing = "back",
                stabilization = false,
            ),
            device = runtime.device,
            navigation = runtime.navigationHint,
        )
    }

    private companion object {
        const val TAG = "LaptopVisionSource"
    }
}
