package com.drivingassist.spatialcopilot.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * CameraX `YUV_420_888` [ImageProxy] -> baseline JPEG (PROTOCOL_v2 uplink), downscaled to at most
 * [maxWidth] px wide (nearest neighbour while repacking to NV21, so there is no second full-size copy),
 * then `YuvImage.compressToJpeg`. The JPEG stays in the buffer (sensor) orientation; the uplink header
 * carries `rotationDegrees` and the laptop rotates.
 *
 * Not thread-safe (reuses buffers, including the returned JPEG array): use from the single camera
 * analyzer thread only, and hand the bytes on (e.g. `offerCameraFrame(bytes, ..., length)`, which
 * copies them) before the next [encode]. Only `OUTPUT_IMAGE_FORMAT_YUV_420_888` images are encoded;
 * anything else returns null.
 * Typical cost on a Tab S9 class device at 1280x720 -> 960x540 q80: a few ms repack + ~10 ms encode.
 */
class YuvJpegEncoder(private val maxWidth: Int = 960, private val quality: Int = 80) {

    /**
     * The JPEG is the first [length] bytes of [bytes] (the encoder's reused buffer, valid until the
     * next [encode]); [width] x [height] in buffer orientation, before rotation.
     */
    class Jpeg(val bytes: ByteArray, val length: Int, val width: Int, val height: Int)

    /** ByteArrayOutputStream whose internal buffer can be handed out without the toByteArray() copy. */
    private class ReusableOutput(size: Int) : ByteArrayOutputStream(size) {
        val buffer: ByteArray get() = buf
    }

    private var nv21 = ByteArray(0)
    private var rowY = ByteArray(0)
    private var rowU = ByteArray(0)
    private var rowV = ByteArray(0)
    private var xMap = IntArray(0)
    private var cxMap = IntArray(0)
    private var mapKey = 0L
    private val out = ReusableOutput(96 * 1024)

    fun encode(image: ImageProxy): Jpeg? {
        if (image.format != ImageFormat.YUV_420_888 || image.planes.size < 3) return null
        val srcW = image.width
        val srcH = image.height
        if (srcW < 2 || srcH < 2) return null
        val scale = if (srcW > maxWidth) maxWidth.toFloat() / srcW else 1f
        val outW = ((srcW * scale).toInt()) and 1.inv()
        val outH = ((srcH * scale).roundToInt()) and 1.inv()
        if (outW < 2 || outH < 2) return null

        val (yPlane, uPlane, vPlane) = Triple(image.planes[0], image.planes[1], image.planes[2])
        val yPix = yPlane.pixelStride
        val uPix = uPlane.pixelStride
        val vPix = vPlane.pixelStride
        val key = (srcW.toLong() shl 48) or (srcH.toLong() shl 32) or (outW.toLong() shl 16) or outH.toLong() xor (yPix.toLong() shl 60)
        if (key != mapKey) {
            xMap = IntArray(outW) { (it * srcW / outW) * yPix }
            cxMap = IntArray(outW / 2) { it * (srcW / 2) / (outW / 2) }
            mapKey = key
        }
        val size = outW * outH * 3 / 2
        if (nv21.size != size) nv21 = ByteArray(size)
        rowY = ensure(rowY, yPlane.rowStride)
        rowU = ensure(rowU, uPlane.rowStride)
        rowV = ensure(rowV, vPlane.rowStride)

        // Luma.
        val yBuf = yPlane.buffer.duplicate()
        for (j in 0 until outH) {
            readRow(yBuf, (j * srcH / outH) * yPlane.rowStride, yPlane.rowStride, rowY)
            val o = j * outW
            for (i in 0 until outW) nv21[o + i] = rowY[xMap[i]]
        }
        // Chroma, interleaved V/U (NV21).
        val uBuf = uPlane.buffer.duplicate()
        val vBuf = vPlane.buffer.duplicate()
        val cw = outW / 2
        val ch = outH / 2
        val srcCH = srcH / 2
        for (j in 0 until ch) {
            val sy = j * srcCH / ch
            readRow(uBuf, sy * uPlane.rowStride, uPlane.rowStride, rowU)
            readRow(vBuf, sy * vPlane.rowStride, vPlane.rowStride, rowV)
            val o = outW * outH + j * outW
            for (i in 0 until cw) {
                val sx = cxMap[i]
                nv21[o + 2 * i] = rowV[sx * vPix]
                nv21[o + 2 * i + 1] = rowU[sx * uPix]
            }
        }

        out.reset()
        if (!YuvImage(nv21, ImageFormat.NV21, outW, outH, null).compressToJpeg(Rect(0, 0, outW, outH), quality, out)) return null
        return Jpeg(out.buffer, out.size(), outW, outH)
    }

    private fun ensure(a: ByteArray, n: Int) = if (a.size >= n) a else ByteArray(n)

    /** Copies one row (at most [rowStride] bytes, less for the last row) into [dst]. */
    private fun readRow(buf: ByteBuffer, start: Int, rowStride: Int, dst: ByteArray) {
        val len = min(rowStride, buf.limit() - start)
        if (len <= 0) return
        buf.position(start)
        buf.get(dst, 0, len)
    }
}
