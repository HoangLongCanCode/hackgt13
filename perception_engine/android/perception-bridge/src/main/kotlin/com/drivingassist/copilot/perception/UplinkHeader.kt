package com.drivingassist.copilot.perception

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The 24-byte little-endian header in front of every live camera JPEG (PROTOCOL_v2, binary
 * uplink). Python: `struct.Struct("<4sHHIqHH")` = magic, headerVersion, flags, frameId,
 * captureTimeNs, rotationDegrees, reserved.
 *
 * @param frameId client counter, uint32 (wraps); echoed back in `perception.frame.echo` / `perception.skip`.
 * @param captureTimeNs client clock (CameraX `ImageProxy.imageInfo.timestamp` base).
 * @param rotationDegrees 0/90/180/270: rotate the JPEG clockwise by this to make it upright.
 */
data class UplinkHeader(
    val frameId: Long,
    val captureTimeNs: Long,
    val rotationDegrees: Int = 0,
    val flags: Int = 0,
    val headerVersion: Int = VERSION,
) {
    init {
        require(frameId in 0..UINT32_MAX) { "frameId must be a uint32, got $frameId" }
        require(rotationDegrees in 0..0xFFFF) { "rotationDegrees must fit a uint16, got $rotationDegrees" }
        require(flags in 0..0xFFFF) { "flags must fit a uint16" }
        require(headerVersion in 0..0xFFFF) { "headerVersion must fit a uint16" }
    }

    /** The 24 header bytes. */
    fun encode(): ByteArray = ByteArray(SIZE).also { writeTo(it, 0) }

    /** Header + JPEG in one array: the payload of one binary WebSocket message. */
    fun frame(jpeg: ByteArray): ByteArray = ByteArray(SIZE + jpeg.size).also {
        writeTo(it, 0)
        jpeg.copyInto(it, destinationOffset = SIZE)
    }

    fun writeTo(dst: ByteArray, offset: Int) {
        ByteBuffer.wrap(dst, offset, SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(MAGIC_BYTES)
            putShort(headerVersion.toShort())
            putShort(flags.toShort())
            putInt(frameId.toInt())
            putLong(captureTimeNs)
            putShort(rotationDegrees.toShort())
            putShort(0)
        }
    }

    companion object {
        const val SIZE = 24
        const val VERSION = 1
        const val UINT32_MAX = 0xFFFF_FFFFL
        private val MAGIC_BYTES = Protocol.UPLINK_MAGIC.toByteArray(Charsets.US_ASCII)

        /** Parses a header (e.g. on a test server). Throws [IllegalArgumentException] on a short buffer or bad magic. */
        fun decode(bytes: ByteArray, offset: Int = 0): UplinkHeader {
            require(bytes.size - offset >= SIZE) { "uplink message shorter than the $SIZE-byte header" }
            val b = ByteBuffer.wrap(bytes, offset, SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }
            require(magic.contentEquals(MAGIC_BYTES)) { "bad uplink magic '${String(magic, Charsets.US_ASCII)}'" }
            val version = b.short.toInt() and 0xFFFF
            val flags = b.short.toInt() and 0xFFFF
            val frameId = b.int.toLong() and UINT32_MAX
            val capture = b.long
            val rotation = b.short.toInt() and 0xFFFF
            return UplinkHeader(frameId, capture, rotation, flags, version)
        }

        /** Rounds any angle to 0/90/180/270 (CameraX already gives one of these). */
        fun normalizeRotation(degrees: Int): Int {
            val d = ((degrees % 360) + 360) % 360
            return ((d + 45) / 90 * 90) % 360
        }

        fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        fun fromHex(hex: String): ByteArray {
            val clean = hex.filter { !it.isWhitespace() && it != ':' && it != '-' }
            require(clean.length % 2 == 0) { "odd hex length" }
            return ByteArray(clean.length / 2) { clean.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        }
    }
}
