package com.ksr.spatialcopilot.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 24-byte little-endian camera header. Layout matches perception protocol v2:
 * magic `KSR1`, headerVersion 1, flags 0, frameId uint32, captureTimeNs int64,
 * rotationDegrees uint16, reserved 0. The JPEG bytes follow immediately.
 *
 * Python: `struct.Struct("<4sHHIqHH")`.
 */
object UplinkHeader {
    const val MAGIC = "KSR1"
    const val SIZE = 24

    fun encode(frameId: Long, captureTimeNs: Long, rotationDegrees: Int): ByteArray {
        val buffer = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC.toByteArray(Charsets.US_ASCII))
        buffer.putShort(1)
        buffer.putShort(0)
        buffer.putInt(frameId.toInt())
        buffer.putLong(captureTimeNs)
        buffer.putShort(rotationDegrees.coerceIn(0, 359).toShort())
        buffer.putShort(0)
        return buffer.array()
    }

    fun wrap(frameId: Long, captureTimeNs: Long, rotationDegrees: Int, jpeg: ByteArray): ByteArray {
        val header = encode(frameId, captureTimeNs, rotationDegrees)
        return ByteArray(header.size + jpeg.size).also { out ->
            header.copyInto(out)
            jpeg.copyInto(out, destinationOffset = header.size)
        }
    }
}
