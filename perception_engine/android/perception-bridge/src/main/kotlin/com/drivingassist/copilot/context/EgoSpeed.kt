package com.drivingassist.copilot.context

import com.drivingassist.copilot.perception.NavigationPacketMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Ego speed for the Driving Context ([NavigationState.egoSpeedMps]: the TOO CLOSE speed gate, time headway, where
 * inferred lane guidance starts) from consecutive `navigation.packet`s.
 *
 * phase1's `progress.speedMps` lags the car by 1-2 s: on the recorded city drive (real_009) it still said 4-6 m/s while
 * `progress.distanceTraveledMeters` had stopped growing, so TOO CLOSE went on while the car rolled to a stop behind a
 * stopped car. The traveled distance is current, so this estimates the speed from it: the least-squares slope of
 * (fix time, traveled) over the fixes of the last [windowSeconds] (at least [minSpanSeconds] apart, never negative),
 * and [update] returns the smaller of that and `speedMps` (either one when the other is unknown).
 *
 * Fix time: `packet.generatedAtMs` (the trip-state sample the packet was computed from; the laptop repeats one packet
 * ~2 Hz in sim while the GPS log has 1 Hz fixes, and the repeats keep their `generatedAtMs`), else `tripTimestampMs`,
 * else `ptsSeconds`. A repeat (same fix time) adds nothing. The traveled distance comes in whole metres, so a window of
 * one 1 Hz fix interval reads +-1 m/s; [windowSeconds] spans two (+-0.5 m/s: 1.0 m/s instead of 2.0 m/s on real_009 at
 * 105.4 s, where `speedMps` said 1.51 m/s and the car was creeping). Time going backwards (a sim seek, a new trip) or a gap
 * of more than [maxGapSeconds] restarts the history.
 *
 * Not thread-safe; one instance per packet stream (the PerceptionBridge feeds it on its socket thread).
 */
class EgoSpeedEstimator(
    val windowSeconds: Double = 2.2,
    val minSpanSeconds: Double = 0.9,
    val maxGapSeconds: Double = 5.0,
) {
    private val times = ArrayDeque<Double>()
    private val traveled = ArrayDeque<Double>()

    /** Speed from the traveled distance alone after the newest [update] (m/s, >= 0); null = not enough fixes. */
    var distanceSpeedMps: Double? = null
        private set

    /** Feeds one packet; returns the ego speed to use: min(traveled-distance speed, `progress.speedMps`), null = unknown. */
    fun update(message: NavigationPacketMessage): Double? {
        val progress = message.packet?.get("progress") as? JsonObject
        val reported = progress.num("speedMps")?.takeIf { it.isFinite() && it >= 0.0 }
        val d = progress.num("distanceTraveledMeters")?.takeIf { it.isFinite() }
        val t = fixTimeSeconds(message)
        if (d != null && t != null) observe(t, d)
        val est = distanceSpeedMps
        return when {
            est == null -> reported
            reported == null -> est
            else -> minOf(est, reported)
        }
    }

    fun reset() {
        times.clear(); traveled.clear(); distanceSpeedMps = null
    }

    private fun observe(t: Double, d: Double) {
        val last = times.lastOrNull()
        if (last != null && t <= last + 1e-6) {
            if (t >= last - 1e-6) return // a repeat of the same fix
            reset() // time went backwards
        } else if (last != null && t - last > maxGapSeconds) {
            reset()
        }
        times.addLast(t); traveled.addLast(d)
        while (times.size > 2 && t - times.first() > windowSeconds + 1e-6) { times.removeFirst(); traveled.removeFirst() }
        distanceSpeedMps = slope()
    }

    /** Least-squares slope of the window, clamped at 0; null with fewer than 2 fixes or less than [minSpanSeconds] between them. */
    private fun slope(): Double? {
        val n = times.size
        if (n < 2 || times.last() - times.first() < minSpanSeconds || times.last() - times.first() > windowSeconds + 1e-6) return null
        val mt = times.sum() / n
        val md = traveled.sum() / n
        var stt = 0.0
        var std = 0.0
        for (i in 0 until n) { stt += (times[i] - mt) * (times[i] - mt); std += (times[i] - mt) * (traveled[i] - md) }
        if (stt <= 1e-12) return null
        return Math.round((std / stt).coerceAtLeast(0.0) * 100.0) / 100.0
    }

    private companion object {
        fun fixTimeSeconds(m: NavigationPacketMessage): Double? {
            (m.packet.num("generatedAtMs"))?.let { return it / 1000.0 }
            m.tripTimestampMs?.let { return it / 1000.0 }
            return m.ptsSeconds
        }

        fun JsonObject?.num(key: String): Double? = (this?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    }
}
