package com.drivingassist.copilot.context

import com.drivingassist.copilot.perception.Camera
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * Flat-ground pinhole model (the few lines of the app's `GroundProjector` the bridge needs): camera pitch from the
 * horizon row, zero roll. Image rows below the horizon map to a forward road distance; a pixel step maps to metres
 * across the road at that distance.
 */
internal class FlatGround(
    val focalPx: Double,
    val cx: Double,
    val cy: Double,
    val horizonY: Double,
    val heightMeters: Double,
) {
    val pitch: Double = atan((cy - horizonY) / focalPx)
    private val cosP = cos(pitch)
    private val sinP = sin(pitch)

    /** Forward road distance at image row [v]; null at or above the horizon. */
    fun forwardAtRow(v: Double): Double? {
        if (v <= horizonY + 2.0) return null
        val ang = pitch + atan((v - cy) / focalPx)
        if (ang <= 1e-4) return null
        return heightMeters / tan(ang)
    }

    /** Image row where the road is [z] metres ahead. */
    fun rowAt(z: Double): Double = cy + focalPx * tan(atan(heightMeters / z) - pitch)

    /** Metres across the camera axis per image pixel, [z] metres ahead. */
    fun metersPerPixelAt(z: Double): Double = (z * cosP + heightMeters * sinP) / focalPx

    /** cos of the camera yaw against a road direction that vanishes at image column [vpX]: turns camera-x metres into metres across the road. */
    fun cosYaw(vpX: Double): Double = focalPx / hypot(focalPx, vpX - cx)

    companion object {
        /** Same default as the app's `GroundProjector`. */
        const val DEFAULT_CAMERA_HEIGHT_M = 1.25

        /**
         * The server's `cameraHeightMeters` is a measurement only inside this range. Placeholder: on the recorded drives
         * (phone on the dashboard) its estimate ranged 0.63-4.46 m within one clip (real_011 said 4.46 m for its first
         * 15 s), and lane widths scale with it one to one. The WorldModel smooths the readings inside it over the lanes
         * runs and holds its value through the others (never back to the default, never outside the range); a camera
         * block on its own ([heightOf]) has no history, so outside the range it is [DEFAULT_CAMERA_HEIGHT_M].
         */
        val PLAUSIBLE_CAMERA_HEIGHT_M: ClosedFloatingPointRange<Double> = 1.0..2.0

        fun heightOf(camera: Camera, plausible: ClosedFloatingPointRange<Double> = PLAUSIBLE_CAMERA_HEIGHT_M, default: Double = DEFAULT_CAMERA_HEIGHT_M): Double =
            camera.cameraHeightMeters?.takeIf { it in plausible } ?: default

        /** Camera intrinsics usable for projection (a focal length and a principal point). */
        fun usable(camera: Camera?): Boolean = camera != null && camera.focalPx > 1.0 && camera.principalPoint.size >= 2

        /**
         * Horizon like the app's `GroundProjector`: road horizon, then camera horizon, then the principal row; height:
         * the one the lane layout was measured with (the WorldModel's smoothed height), else [heightOf]. Null without a
         * usable camera.
         */
        fun of(world: WorldSnapshot): FlatGround? {
            val cam = world.camera?.takeIf { usable(it) } ?: return null
            val horizon = world.road?.road?.horizonY ?: cam.horizonY ?: cam.cy
            return FlatGround(cam.focalPx, cam.cx, cam.cy, horizon, world.laneLayout?.cameraHeightMeters ?: heightOf(cam))
        }
    }
}
