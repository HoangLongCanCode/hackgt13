package com.drivingassist.copilot.cli

/** Reads width/height from a JPEG's SOFn marker (no java.awt / ImageIO needed). */
internal object JpegInfo {
    fun size(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 8 < b.size) {
            if (b[i] != 0xFF.toByte()) { i++; continue }
            val marker = b[i + 1].toInt() and 0xFF
            when {
                marker == 0xFF -> { i++; continue } // fill byte
                marker == 0x01 || marker in 0xD0..0xD8 -> { i += 2; continue } // no length
            }
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isSof) {
                val h = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                val w = ((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)
                return w to h
            }
            i += 2 + len
        }
        return null
    }
}
