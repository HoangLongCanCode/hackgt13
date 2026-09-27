package com.drivingassist.spatialcopilot.desktop

/**
 * The player's position in media seconds, like ExoPlayer's `currentPosition`: runs from the base position at [rate]
 * while playing, holds while paused, jumps on [seek]. Stops at [durationS] when that is known. Thread-safe.
 */
class MediaClock(private val nowNs: () -> Long = System::nanoTime, val rate: Double = 1.0) {
    private var basePts = 0.0
    private var baseNs = 0L

    @Volatile var playing: Boolean = false
        private set

    /** Clip length (seconds); null = unknown. */
    @Volatile var durationS: Double? = null

    @Synchronized fun position(): Double {
        val p = if (playing) basePts + (nowNs() - baseNs) / 1e9 * rate else basePts
        val d = durationS
        return if (d != null && d > 0.0) p.coerceIn(0.0, d) else p.coerceAtLeast(0.0)
    }

    /** Past the end of the clip (known length). */
    fun atEnd(): Boolean = durationS?.let { position() >= it - 1e-3 } ?: false

    @Synchronized fun play() {
        if (playing) return
        baseNs = nowNs()
        playing = true
    }

    @Synchronized fun pause() {
        if (!playing) return
        basePts = position()
        playing = false
    }

    @Synchronized fun seek(pts: Double) {
        val d = durationS
        basePts = if (d != null && d > 0.0) pts.coerceIn(0.0, d) else pts.coerceAtLeast(0.0)
        baseNs = nowNs()
    }
}
