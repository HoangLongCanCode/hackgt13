package com.ksr.copilot.bridge

import com.ksr.copilot.context.WorldSnapshot
import java.util.TreeMap
import kotlin.math.abs

/**
 * Credit-based uplink flow control (PROTOCOL_v2): at most [maxInFlight] camera frames may be
 * waiting for their wave-1 answer. A credit comes back when the matching `perception.frame`
 * (echo.frameId) or `perception.skip` arrives, or after [timeoutNs] so a lost answer can never
 * deadlock the uplink. Frames without a credit are DROPPED by the caller, never queued.
 *
 * Thread-safe: acquired on the camera thread, released on the socket reader thread.
 */
class CreditGate(maxInFlight: Int, private val timeoutNs: Long) {
    private val inFlight = LinkedHashMap<Long, Long>() // frameId -> sentAtNs

    @Volatile
    var maxInFlight: Int = maxInFlight.coerceAtLeast(1)
        set(value) { field = value.coerceAtLeast(1) }

    /** Takes a credit for [frameId]; false (drop the frame) when all credits are in use. */
    @Synchronized
    fun tryAcquire(frameId: Long, nowNs: Long): Boolean {
        if (inFlight.size >= maxInFlight) return false
        inFlight[frameId] = nowNs
        return true
    }

    /** Returns the credit of [frameId]; false when it was not in flight (late answer after a timeout). */
    @Synchronized
    fun release(frameId: Long): Boolean = inFlight.remove(frameId) != null

    /** Returns credits older than the timeout; the number returned. */
    @Synchronized
    fun sweep(nowNs: Long): Int {
        var expired = 0
        val it = inFlight.entries.iterator()
        while (it.hasNext()) {
            if (nowNs - it.next().value >= timeoutNs) { it.remove(); expired++ }
        }
        return expired
    }

    /** Socket gone: every outstanding frame is lost, all credits come back. */
    @Synchronized
    fun reset() = inFlight.clear()

    val inFlightCount: Int @Synchronized get() = inFlight.size

    val available: Int get() = (maxInFlight - inFlightCount).coerceAtLeast(0)
}

/**
 * Sim mode: results keyed by `ptsSeconds` (the snapshot's newest wave-1 frame). The laptop analyses
 * ahead of the tablet's playback, so results wait here until their frame is on screen. A wave-2
 * merge re-puts the snapshot under the same pts and replaces the entry. Thread-safe.
 */
class PtsResultBuffer(private val capacity: Int = 900) {
    private val map = TreeMap<Double, WorldSnapshot>()

    @Synchronized
    fun put(snapshot: WorldSnapshot) {
        if (snapshot.timing == null) return
        map[snapshot.ptsSeconds] = snapshot
        while (map.size > capacity) map.pollFirstEntry()
    }

    /** Newest result with `pts <= playbackPts` and `playbackPts - pts <= maxLagSeconds`, else null. */
    @Synchronized
    fun select(playbackPts: Double, maxLagSeconds: Double): WorldSnapshot? {
        val e = map.floorEntry(playbackPts + 1e-6) ?: return null
        return if (playbackPts - e.key <= maxLagSeconds) e.value else null
    }

    @Synchronized
    fun trimBefore(pts: Double) {
        while (true) {
            val first = map.firstEntry() ?: return
            if (first.key >= pts) return
            map.pollFirstEntry()
        }
    }

    @Synchronized
    fun clear() = map.clear()

    val size: Int @Synchronized get() = map.size

    /** Newest buffered pts (how far ahead the laptop is), null when empty. */
    val newestPts: Double? @Synchronized get() = map.lastEntry()?.key
}

/**
 * Sim mode: model of the tablet's video player clock from `reportPlayback` calls, so the bridge
 * can estimate the playback position between reports (10 Hz reports, 20 Hz world updates).
 *
 * The seek rule is the server's (PROTOCOL_v2 Sessions): a jump of more than
 * [seekThresholdSeconds] x max(1, rate) against the extrapolated position, so the client drops its
 * buffered results exactly when the server starts a new session. A rate <= 0 counts as paused.
 */
class PlaybackClock(private val seekThresholdSeconds: Double = SEEK_THRESHOLD_SECONDS) {
    data class State(val videoId: String, val ptsSeconds: Double, val playing: Boolean, val rate: Double, val atNs: Long)

    @Volatile
    var state: State? = null
        private set

    /**
     * Records a report. Returns true on a discontinuity that invalidates buffered results: another
     * video, or a jump of more than [seekThresholdSeconds] x max(1, [rate]) against the extrapolated
     * position (seek). The very first report is not a discontinuity.
     */
    @Synchronized
    fun update(videoId: String, ptsSeconds: Double, playing: Boolean, rate: Double, nowNs: Long): Boolean {
        val prev = state
        val predicted = estimate(nowNs)
        state = State(videoId, ptsSeconds, playing, rate, nowNs)
        if (prev == null) return false
        val threshold = seekThresholdSeconds * maxOf(1.0, rate)
        return prev.videoId != videoId || (predicted != null && abs(ptsSeconds - predicted) > threshold)
    }

    /** Estimated playback position at [nowNs], null before the first report. Paused or rate <= 0: frozen. */
    fun estimate(nowNs: Long): Double? {
        val s = state ?: return null
        if (!s.playing || s.rate <= 0.0) return s.ptsSeconds
        return s.ptsSeconds + (nowNs - s.atNs).coerceAtLeast(0) / 1e9 * s.rate
    }

    companion object {
        /** Same as the server's SEEK_THRESHOLD_S (perception/realtime/server.py). */
        const val SEEK_THRESHOLD_SECONDS = 0.6
    }
}

/** Fixed-size window of samples with percentiles (latency p50 / p95). Thread-safe. */
class RollingWindow(private val capacity: Int = 90) {
    private val values = DoubleArray(capacity)
    private var start = 0
    private var size = 0

    @Synchronized
    fun add(x: Double) {
        if (x.isNaN()) return
        if (size == capacity) { start = (start + 1) % capacity; size-- }
        values[(start + size) % capacity] = x
        size++
    }

    @Synchronized
    fun percentile(p: Double): Double? {
        if (size == 0) return null
        val sorted = DoubleArray(size) { values[(start + it) % capacity] }.also { it.sort() }
        val idx = Math.round(p / 100.0 * (size - 1)).toInt().coerceIn(0, size - 1)
        return sorted[idx]
    }

    @Synchronized
    fun clear() { start = 0; size = 0 }

    val count: Int @Synchronized get() = size
}

/** Events per second over a sliding window (default 2 s). Thread-safe. */
class RateMeter(private val windowNs: Long = 2_000_000_000L) {
    private val times = ArrayDeque<Long>()
    private var firstNs: Long? = null

    @Synchronized
    fun mark(nowNs: Long) {
        if (firstNs == null) firstNs = nowNs
        times.addLast(nowNs)
        trim(nowNs)
    }

    @Synchronized
    fun rate(nowNs: Long): Double? {
        val first = firstNs ?: return null
        trim(nowNs)
        val span = minOf(windowNs, nowNs - first)
        if (span <= 0) return null
        return times.size / (span / 1e9)
    }

    @Synchronized
    fun reset() { times.clear(); firstNs = null }

    private fun trim(nowNs: Long) {
        while (times.isNotEmpty() && nowNs - times.first() > windowNs) times.removeFirst()
    }
}
