package com.drivingassist.glass

import kotlin.math.max

/**
 * Latest size of the landscape preview, in pixels. [CameraPreview] writes this.
 * The OpenCV analyzer reads it when mapping detections into overlay space.
 * Width or height is 0 until the first layout.
 */
class FrameGeometry {
    @Volatile var viewWidth: Float = 0f
    @Volatile var viewHeight: Float = 0f
}

/**
 * Maps OpenCV / CameraX buffer coordinates into the overlay's 0..1 space.
 *
 * The preview uses [androidx.camera.view.PreviewView.ScaleType.FILL_CENTER] and the
 * activity is landscape. Do not draw with raw image width and height.
 *
 * [bufferX] and [bufferY] are normalized to the ImageProxy buffer (0..1), before rotation.
 * [rotationDegrees] is `image.imageInfo.rotationDegrees`.
 * [bufferWidth] and [bufferHeight] are `image.width` and `image.height`.
 * [viewWidth] and [viewHeight] come from [FrameGeometry].
 */
object PreviewCoordinates {
    fun mapPoint(
        bufferX: Float,
        bufferY: Float,
        rotationDegrees: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        viewWidth: Float,
        viewHeight: Float,
    ): Pair<Float, Float>? {
        if (viewWidth <= 0f || viewHeight <= 0f || bufferWidth <= 0 || bufferHeight <= 0) return null
        val (uprightX, uprightY) = rotateNormalized(bufferX, bufferY, rotationDegrees)
        val uprightW: Float
        val uprightH: Float
        if (rotationDegrees % 180 == 0) {
            uprightW = bufferWidth.toFloat()
            uprightH = bufferHeight.toFloat()
        } else {
            uprightW = bufferHeight.toFloat()
            uprightH = bufferWidth.toFloat()
        }
        return coverMap(uprightX, uprightY, uprightW, uprightH, viewWidth, viewHeight)
    }

    /**
     * Maps an axis-aligned buffer box [x, y, w, h] (normalized to the buffer)
     * to an axis-aligned overlay box. Rotation can swap width and height, so all
     * four corners are mapped and the result is the covering rectangle.
     */
    fun mapBox(
        box: FloatArray,
        rotationDegrees: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        viewWidth: Float,
        viewHeight: Float,
    ): FloatArray? {
        if (box.size < 4) return null
        val corners = arrayOf(
            box[0] to box[1],
            box[0] + box[2] to box[1],
            box[0] to box[1] + box[3],
            box[0] + box[2] to box[1] + box[3],
        )
        val mapped = corners.mapNotNull { (x, y) ->
            mapPoint(x, y, rotationDegrees, bufferWidth, bufferHeight, viewWidth, viewHeight)
        }
        if (mapped.size < 4) return null
        val left = mapped.minOf { it.first }
        val top = mapped.minOf { it.second }
        val right = mapped.maxOf { it.first }
        val bottom = mapped.maxOf { it.second }
        return floatArrayOf(left, top, (right - left).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f))
    }

    fun mapPolyline(
        points: List<Pair<Float, Float>>,
        rotationDegrees: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        viewWidth: Float,
        viewHeight: Float,
    ): List<NormPoint> {
        return points.mapNotNull { (x, y) ->
            mapPoint(x, y, rotationDegrees, bufferWidth, bufferHeight, viewWidth, viewHeight)
        }.map { (x, y) -> NormPoint(x, y) }
    }

    private fun rotateNormalized(x: Float, y: Float, rotationDegrees: Int): Pair<Float, Float> {
        val turns = ((rotationDegrees % 360) + 360) % 360
        return when (turns) {
            90 -> y to (1f - x)
            180 -> (1f - x) to (1f - y)
            270 -> (1f - y) to x
            else -> x to y
        }
    }

    /** FILL_CENTER: scale until the image covers the view, then crop the overflow. */
    private fun coverMap(
        x: Float,
        y: Float,
        sourceWidth: Float,
        sourceHeight: Float,
        viewWidth: Float,
        viewHeight: Float,
    ): Pair<Float, Float> {
        val scale = max(viewWidth / sourceWidth, viewHeight / sourceHeight)
        val displayedWidth = sourceWidth * scale
        val displayedHeight = sourceHeight * scale
        val cropX = (displayedWidth - viewWidth) / 2f
        val cropY = (displayedHeight - viewHeight) / 2f
        val pixelX = x * displayedWidth - cropX
        val pixelY = y * displayedHeight - cropY
        return (pixelX / viewWidth) to (pixelY / viewHeight)
    }
}
