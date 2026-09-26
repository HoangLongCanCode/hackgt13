package com.ksr.spatialcopilot.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/** YUV_420_888 -> baseline JPEG. The buffer stays in sensor orientation. */
object YuvJpeg {
    fun encode(image: ImageProxy, quality: Int): ByteArray {
        require(image.planes.size >= 3) { "expected YUV_420_888" }
        val nv21 = yuv420ToNv21(image)
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val stream = ByteArrayOutputStream()
        val wrote = yuv.compressToJpeg(Rect(0, 0, image.width, image.height), quality.coerceIn(40, 95), stream)
        if (!wrote) error("JPEG encode failed")
        return stream.toByteArray()
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)
        copyPlane(image.planes[0], width, height, nv21, 0)
        val chromaU = image.planes[1]
        val chromaV = image.planes[2]
        val uBuffer = chromaU.buffer
        val vBuffer = chromaV.buffer
        var offset = width * height
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val vIndex = row * chromaV.rowStride + col * chromaV.pixelStride
                val uIndex = row * chromaU.rowStride + col * chromaU.pixelStride
                nv21[offset++] = vBuffer.get(vIndex)
                nv21[offset++] = uBuffer.get(uIndex)
            }
        }
        return nv21
    }

    private fun copyPlane(
        plane: ImageProxy.PlaneProxy,
        width: Int,
        height: Int,
        out: ByteArray,
        destinationOffset: Int,
    ) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (pixelStride == 1 && rowStride == width) {
            buffer.rewind()
            val bytes = (width * height).coerceAtMost(buffer.remaining())
            buffer.get(out, destinationOffset, bytes)
            return
        }
        var offset = destinationOffset
        for (row in 0 until height) {
            var index = row * rowStride
            for (col in 0 until width) {
                out[offset++] = buffer.get(index)
                index += pixelStride
            }
        }
    }
}
