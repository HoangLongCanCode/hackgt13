package com.drivingassist.glass

import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Input side of Glass Mode. Engineer A's OpenCV detector implements this.
 * The overlay only reads [visionData]. It does not know whether the source is mock or live.
 *
 * If the implementation also implements [androidx.camera.core.ImageAnalysis.Analyzer],
 * [CameraPreview] will deliver landscape camera frames to it.
 */
interface VisionSource {
    val visionData: StateFlow<VisionData>
    val frameGeometry: FrameGeometry
    fun start()
    fun stop()
}

/**
 * Scripted lanes, vehicles, signs, and exit signs so the overlay runs before OpenCV is merged.
 * Replace this class at the swap line in [MockDataViewModel]. Do not delete it.
 */
class MockVisionSource : VisionSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val startedNanos = System.nanoTime()

    private val _visionData = MutableStateFlow(sample(0f))
    override val visionData: StateFlow<VisionData> = _visionData.asStateFlow()
    override val frameGeometry: FrameGeometry = FrameGeometry()

    override fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                _visionData.value = sample(nowSeconds())
                delay(100)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    private fun nowSeconds(): Float = (System.nanoTime() - startedNanos) / 1_000_000_000f

    private fun sample(time: Float): VisionData {
        val sway = sin(time * 0.6f) * 0.02f
        return VisionData(
            time = time,
            vehicles = listOf(
                vehicle(id = 1, x = 0.40f, y = 0.50f, w = 0.12f, h = 0.20f, distance = 16f, time = time, phase = 0.3f),
                vehicle(id = 2, x = 0.58f, y = 0.52f, w = 0.10f, h = 0.16f, distance = 28f, time = time, phase = 1.6f),
            ),
            lanes = listOf(
                LaneLine(
                    id = "left",
                    points = listOf(
                        NormPoint(0.06f + sway, 0.98f),
                        NormPoint(0.30f + sway, 0.64f),
                        NormPoint(0.42f + sway * 0.4f, 0.40f),
                    ),
                ),
                LaneLine(
                    id = "right",
                    points = listOf(
                        NormPoint(0.94f + sway, 0.98f),
                        NormPoint(0.70f + sway, 0.64f),
                        NormPoint(0.56f + sway * 0.4f, 0.40f),
                    ),
                ),
            ),
            signs = listOf(
                TrafficSign(
                    id = 1,
                    label = "SPEED_LIMIT_55",
                    box = floatArrayOf(
                        (0.84f + sin(time * 0.4f) * 0.008f).coerceIn(0.7f, 0.90f),
                        0.28f,
                        0.07f,
                        0.16f,
                    ),
                ),
            ),
            exitSigns = listOf(
                ExitSign(
                    id = 1,
                    label = "EXIT 24",
                    box = floatArrayOf(0.66f, 0.08f, 0.18f, 0.12f),
                    distanceMeters = (420f - (time * 8f) % 180f).coerceAtLeast(40f),
                ),
            ),
        )
    }

    private fun vehicle(
        id: Int,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        distance: Float,
        time: Float,
        phase: Float,
    ): Vehicle {
        val driftX = sin(time * 0.9f + phase) * 0.02f
        val driftY = cos(time * 0.65f + phase) * 0.012f
        return Vehicle(
            id = id,
            box = floatArrayOf(
                (x + driftX).coerceIn(0.02f, 1f - w - 0.02f),
                (y + driftY).coerceIn(0.30f, 0.72f - h),
                w,
                h,
            ),
            distanceMeters = (distance + sin(time * 0.45f + phase) * 1.2f).coerceAtLeast(1f),
        )
    }
}
