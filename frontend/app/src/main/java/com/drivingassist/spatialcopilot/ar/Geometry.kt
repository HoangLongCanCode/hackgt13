package com.drivingassist.spatialcopilot.ar

import com.drivingassist.copilot.context.WorldSnapshot
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * Image column of the road's vanishing point: the server's `road.vanishingPoint` (fresh), else the lane layout's;
 * null when neither lies inside the image.
 */
fun roadVanishingX(world: WorldSnapshot): Double? {
    val width = world.image?.width?.toDouble() ?: return null
    val road = world.road?.takeIf { it.ageSeconds <= EgoLane.MAX_LANES_AGE_S }?.road?.vanishingPoint?.takeIf { it.size >= 2 }?.get(0)
    val layout = world.laneLayout?.takeIf { it.ageSeconds <= EgoLane.MAX_LANES_AGE_S }?.vpX
    return listOfNotNull(road, layout).firstOrNull { it in 0.0..width }
}

/** A point in image or view pixels (x right, y down). */
data class Vec2(val x: Float, val y: Float)

/** A point on the road plane: [x] metres to the right of the camera axis, [z] metres ahead. */
data class Ground(val x: Double, val z: Double)

/**
 * Analysed-image pixels -> view pixels for a FILL_CENTER picture (scale to cover the view, crop the
 * centre). CameraX `PreviewView.ScaleType.FILL_CENTER` (LIVE) and Media3 `RESIZE_MODE_ZOOM` (SIM) both
 * draw that way, and the analysis stream has the preview's aspect ratio, so server coordinates land on
 * the same spot of the picture. Tab S9 landscape: 1280x720 into 2560x1600 -> scale 2.222, 142 px of the
 * view cropped on each side.
 */
class FillCenter(
    val imageWidth: Int,
    val imageHeight: Int,
    val viewWidth: Float,
    val viewHeight: Float,
) {
    val scale: Float = if (imageWidth <= 0 || imageHeight <= 0) 1f else max(viewWidth / imageWidth, viewHeight / imageHeight)
    val dx: Float = (viewWidth - imageWidth * scale) / 2f
    val dy: Float = (viewHeight - imageHeight * scale) / 2f

    fun point(x: Double, y: Double): Vec2 = Vec2(dx + x.toFloat() * scale, dy + y.toFloat() * scale)

    fun point(p: Vec2): Vec2 = Vec2(dx + p.x * scale, dy + p.y * scale)

    /** Image box [x1, y1, x2, y2] -> view rect, clipped to the view; null when nothing of it is visible. */
    fun box(bbox: List<Double>): ViewRect? {
        if (bbox.size != 4) return null
        val a = point(bbox[0], bbox[1])
        val b = point(bbox[2], bbox[3])
        val r = ViewRect(a.x.coerceIn(0f, viewWidth), a.y.coerceIn(0f, viewHeight), b.x.coerceIn(0f, viewWidth), b.y.coerceIn(0f, viewHeight))
        return r.takeIf { it.width >= 2f && it.height >= 2f }
    }
}

data class ViewRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
}

/**
 * Flat-ground camera model of the analysed image, from the frame's `camera` block. It is the same
 * maths as the server's `wire.ground_xz` (which fills `road.anchorPoints[].groundXZ`): pitch from the
 * horizon row, `z = h / tan(pitch + atan((v - cy) / f))`, lateral `(u - cx) * zc / f` with `zc` the depth
 * along the pitched optical axis. Arrows drawn with it therefore sit where the server measures the road.
 * Device pose is not used: in the demo the camera looks at a monitor.
 */
class GroundProjector(
    val focalPx: Double,
    val cx: Double,
    val cy: Double,
    val horizonY: Double,
    val cameraHeightMeters: Double,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    val pitch: Double = atan((cy - horizonY) / focalPx)
    private val cosP = cos(pitch)
    private val sinP = sin(pitch)

    /** Road point -> image pixels; null when it is behind the camera, too close or at the horizon. */
    fun toImage(g: Ground): Vec2? {
        if (g.z < MIN_Z) return null
        val below = atan(cameraHeightMeters / g.z) - pitch
        val v = cy + focalPx * tan(below)
        val zc = g.z * cosP + cameraHeightMeters * sinP
        if (zc <= 0.1) return null
        return Vec2((cx + focalPx * g.x / zc).toFloat(), v.toFloat())
    }

    /** Forward road distance at image row [v]; null at or above the horizon. */
    fun forwardAtRow(v: Double): Double? {
        if (v <= horizonY + 2.0) return null
        val ang = pitch + atan((v - cy) / focalPx)
        if (ang <= 1e-4) return null
        return cameraHeightMeters / tan(ang)
    }

    /** Image row where the road is [z] metres ahead. */
    fun rowAt(z: Double): Double = cy + focalPx * tan(atan(cameraHeightMeters / z) - pitch)

    /** Lateral road position of image column [u] at forward distance [z]. */
    fun lateralAt(u: Double, z: Double): Double = (u - cx) * (z * cosP + cameraHeightMeters * sinP) / focalPx

    /** Road point under image pixel ([u], [v]); null above the horizon. */
    fun toGround(u: Double, v: Double): Ground? = forwardAtRow(v)?.let { z -> Ground(lateralAt(u, z), z) }

    /**
     * Heading dx/dz on the road (x right, z ahead) of the direction whose vanishing point is at image column
     * [vpX]: `(vpX - cx) cos(pitch) / f`. A yawed phone sees the road's vanishing point off the principal point.
     */
    fun headingAt(vpX: Double): Double = (vpX - cx) * cosP / focalPx

    /** Nearest road distance inside the picture (bottom row). */
    val nearestVisibleZ: Double get() = forwardAtRow(imageHeight.toDouble()) ?: MIN_Z

    companion object {
        const val MIN_Z = 1.0
        const val DEFAULT_CAMERA_HEIGHT_M = 1.25
        val PLAUSIBLE_CAMERA_HEIGHT_M = 1.0..2.0

        /** From a snapshot; null without camera intrinsics or image size (nothing to anchor to). */
        fun from(world: WorldSnapshot): GroundProjector? {
            val cam = world.camera ?: return null
            val image = world.image ?: return null
            if (cam.focalPx <= 1.0 || cam.principalPoint.size < 2 || image.width <= 0 || image.height <= 0) return null
            // Same precedence as the server's groundXZ: road horizon, then camera horizon.
            val horizon = world.road?.road?.horizonY ?: cam.horizonY ?: cam.cy
            if (horizon >= image.height - 4) return null
            return GroundProjector(cam.focalPx, cam.cx, cam.cy, horizon, heightOf(world), image.width, image.height)
        }

        /**
         * Camera height the arrows are drawn with: the one the bridge measured the lane layout's widths with (smoothed,
         * clamped), so arrows and lanes agree on the road. Without it the server's estimate, which swings (0.6-4.5 m
         * within one recorded drive), clamped to a plausible dash / mount height: no step at the ends of the range.
         */
        fun heightOf(world: WorldSnapshot): Double {
            val h = world.laneLayout?.cameraHeightMeters?.takeIf { it.isFinite() }
                ?: world.camera?.cameraHeightMeters?.takeIf { it.isFinite() }
                ?: return DEFAULT_CAMERA_HEIGHT_M
            return h.coerceIn(PLAUSIBLE_CAMERA_HEIGHT_M)
        }
    }
}
