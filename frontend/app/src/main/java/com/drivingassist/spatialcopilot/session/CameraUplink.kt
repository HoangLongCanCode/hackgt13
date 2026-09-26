package com.drivingassist.spatialcopilot.session

import android.graphics.ImageFormat
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.drivingassist.copilot.bridge.PerceptionBridge
import com.drivingassist.spatialcopilot.camera.YuvJpegEncoder
import kotlin.math.abs

/**
 * LIVE mode: CameraX analysis frames -> `SDC1` header + JPEG -> laptop (PROTOCOL_v2 credits).
 *
 * [analyze] runs on the camera analyzer thread (`STRATEGY_KEEP_ONLY_LATEST`). It never waits on the
 * network: without a free credit it skips the JPEG encode, and the bridge drops frames instead of
 * queueing them. The image is always closed. When the upright size changes, [onUprightSize] is told
 * so the session re-sends `client.hello` with the new camera block.
 */
class CameraUplink(
    private val bridge: PerceptionBridge,
    private val onUprightSize: (width: Int, height: Int, bufferWidth: Int) -> Unit,
    private val onProblem: (String) -> Unit,
) {
    private val encoder = YuvJpegEncoder(maxWidth = 960, quality = 80)

    @Volatile private var announced: Triple<Int, Int, Int>? = null
    private var reportedBadFormat = false

    fun analyze(image: ImageProxy) {
        try {
            if (!bridge.canUplinkNow()) {
                bridge.noteFrameSkippedByCaller()
                return
            }
            val rotation = image.imageInfo.rotationDegrees
            val captureNs = toBridgeClock(image.imageInfo.timestamp)
            val jpeg = encoder.encode(image)
            if (jpeg == null) {
                bridge.noteFrameSkippedByCaller()
                if (!reportedBadFormat) {
                    reportedBadFormat = true
                    val why = if (image.format != ImageFormat.YUV_420_888) "camera format ${image.format} is not YUV_420_888" else "JPEG encode failed"
                    Log.e(TAG, "uplink stopped: $why")
                    onProblem("Camera: $why")
                }
                return
            }
            val upright = if (rotation % 180 == 0) Triple(jpeg.width, jpeg.height, jpeg.width) else Triple(jpeg.height, jpeg.width, jpeg.width)
            if (upright != announced) {
                announced = upright
                onUprightSize(upright.first, upright.second, upright.third)
            }
            // jpeg.bytes is the encoder's reused buffer: the bridge copies jpeg.length bytes before returning.
            bridge.offerCameraFrame(jpeg.bytes, captureNs, rotation, jpeg.length)
        } catch (t: Throwable) {
            Log.w(TAG, "camera frame not sent", t)
        } finally {
            image.close()
        }
    }

    companion object {
        private const val TAG = "CameraUplink"

        /**
         * CameraX `imageInfo.timestamp` -> bridge clock (`elapsedRealtimeNanos`). The sensor uses either
         * the realtime or the `System.nanoTime` base (`SENSOR_INFO_TIMESTAMP_SOURCE`); whichever the
         * timestamp is closer to is its base.
         */
        fun toBridgeClock(cameraTimestampNs: Long): Long {
            val realtime = SystemClock.elapsedRealtimeNanos()
            if (cameraTimestampNs <= 0L) return realtime
            val mono = System.nanoTime()
            return if (abs(realtime - cameraTimestampNs) <= abs(mono - cameraTimestampNs)) cameraTimestampNs
            else cameraTimestampNs + (realtime - mono)
        }
    }
}
