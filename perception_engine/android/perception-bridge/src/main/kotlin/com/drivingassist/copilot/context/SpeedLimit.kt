package com.drivingassist.copilot.context

/**
 * Posted speed limit for display ([DrivingContext.speedLimit]): a confirmed speed-limit sign read, else the
 * map's value ([NavigationState.mapSpeedLimitMph]), else unknown. Deterministic; time = media pts (the
 * engine's "now"), and the given wall clock only while perception is stale (media time stops then). Display
 * only: nothing here is spoken or compared with the ego speed.
 *
 * A sign value is confirmed per sign key ([SignState.key]) when the same value N, in
 * [DrivingContextConfig.speedLimitAllowedMph], is read with confidence >= [DrivingContextConfig.speedLimitMinConfidence]
 * at <= [DrivingContextConfig.speedLimitMaxDistanceMeters] (or unknown distance) for
 * [DrivingContextConfig.speedLimitDwellSeconds] of media time in >= [DrivingContextConfig.speedLimitMinSeen]
 * observations. A read of another value on that key restarts it; a weak or far read of the same value
 * neither counts nor restarts. A newly confirmed value replaces the previous one at once.
 *
 * The sign value is dropped: [DrivingContextConfig.speedLimitSignHoldSeconds] after it was last confirmed;
 * after [DrivingContextConfig.speedLimitStaleHoldSeconds] of wall-clock time with stale perception (sign reads
 * stop; [staleDeadlineNs]); when a road-changing maneuver was passed (the previous target was a turn / exit /
 * keep / merge / highway entry at <= [DrivingContextConfig.speedLimitManeuverPassedMeters] and the target then
 * changed: another maneuver, label or step id, or a distance jump back up); when the map value or road changes
 * more than [DrivingContextConfig.speedLimitSignPassSeconds] after the last read of the sign (earlier changes
 * are the car passing the sign: they move the map anchor); and on [reset].
 */
class SpeedLimitLatch(private val config: DrivingContextConfig = DrivingContextConfig()) {

    /** A newly confirmed sign value (one SPEED_LIMIT event each). */
    data class Confirmed(val valueMph: Int, val distanceMeters: Double?, val signKey: String)

    private class Candidate(val valueMph: Int, val sincePts: Double, var lastPts: Double, var count: Int, var confirmed: Boolean = false)

    private data class MapValue(val valueMph: Int, val road: String?)

    private data class Target(val maneuver: Maneuver, val label: String?, val distanceMeters: Double, val eventId: String?)

    private val candidates = HashMap<String, Candidate>()
    private var confirmedAtPts = Double.NaN

    /** Newest sign read seen, and the newest one before the last clear (older reads never count again). */
    private var lastReadPts = Double.NEGATIVE_INFINITY
    private var readsAfterPts = Double.NEGATIVE_INFINITY

    /** Map value and road at the sign: the first known one, followed while the car passes the sign. */
    private var mapAtSign: MapValue? = null
    private var lastTarget: Target? = null

    /** Stale perception with a sign value held: wall-clock time (ns) it is dropped at. Null = not stale / none held. */
    var staleDeadlineNs: Long? = null
        private set

    /** Confirmed sign value, null when none is held. */
    var signMph: Int? = null
        private set

    /** Map value of the latest [update]. */
    var mapMph: Int? = null
        private set

    val valueMph: Int? get() = signMph ?: mapMph

    val source: SpeedLimitSource?
        get() = when {
            signMph != null -> SpeedLimitSource.SIGN
            mapMph != null -> SpeedLimitSource.MAP
            else -> null
        }

    /**
     * One step at media time [now]. [signs] = the snapshot's signs, null while perception is stale (nothing is
     * read; the held value is timed on [wallNs], a monotonic clock). Returns the sign value confirmed on this step
     * when it is new, else null.
     */
    fun update(now: Double, signs: List<SignState>?, navigation: NavigationState?, wallNs: Long): Confirmed? {
        val map = navigation?.mapSpeedLimitMph?.let { MapValue(it, navigation.mapSpeedLimitRoad) }
        mapMph = map?.valueMph

        if (navigation != null) {
            val t = Target(navigation.maneuver, navigation.label, navigation.distanceMeters, navigation.eventId)
            val last = lastTarget
            if (last != null && last.maneuver in ROAD_CHANGING && last.distanceMeters <= config.speedLimitManeuverPassedMeters &&
                (t.maneuver != last.maneuver || t.label != last.label ||
                    (t.eventId != null && last.eventId != null && t.eventId != last.eventId) ||
                    t.distanceMeters > last.distanceMeters + config.speedLimitManeuverPassedMeters)
            ) {
                clearSign() // e.g. two unnamed right turns in a row: same maneuver and label, another step
            }
            lastTarget = t
        }

        if (signMph != null && map != null) {
            val before = mapAtSign
            // Passing the sign (the map way splits at it, GPS lag): follow the map instead of dropping the sign.
            if (before == null || now - confirmedAtPts <= config.speedLimitSignPassSeconds) {
                mapAtSign = MapValue(map.valueMph, map.road ?: before?.road)
            } else if (roadChanged(before, map)) {
                clearSign()
            }
        }

        if (signMph != null && now - confirmedAtPts > config.speedLimitSignHoldSeconds) clearSign()

        // Stale perception stops media time (and maybe every input): time the held value on the wall clock.
        if (signs != null || signMph == null) {
            staleDeadlineNs = null
        } else {
            val left = minOf(config.speedLimitStaleHoldSeconds, config.speedLimitSignHoldSeconds - (now - confirmedAtPts))
            val deadline = staleDeadlineNs ?: (wallNs + (left * 1e9).toLong()).also { staleDeadlineNs = it }
            if (wallNs >= deadline) clearSign()
        }

        if (signs == null) return null
        return readSigns(now, signs, map)
    }

    fun reset() {
        clearSign()
        mapMph = null
        lastTarget = null
        lastReadPts = Double.NEGATIVE_INFINITY
        readsAfterPts = Double.NEGATIVE_INFINITY
    }

    private fun readSigns(now: Double, signs: List<SignState>, map: MapValue?): Confirmed? {
        candidates.keys.retainAll(signs.mapTo(HashSet()) { it.key })
        var best: Confirmed? = null
        for (s in signs) {
            val n = s.sign.speedLimit ?: continue
            if (s.lastSeenPts > lastReadPts) lastReadPts = s.lastSeenPts
            if (s.lastSeenPts <= readsAfterPts) continue // read before the last clear (the WorldModel holds signs ~2 s)
            val c = candidates[s.key]
            if (c != null && s.lastSeenPts <= c.lastPts) continue // no new read of this key
            if (c != null && c.valueMph != n) candidates.remove(s.key)
            val d = s.sign.distanceMeters
            val strong = n in config.speedLimitAllowedMph && s.sign.confidence >= config.speedLimitMinConfidence &&
                (d == null || d <= config.speedLimitMaxDistanceMeters)
            if (!strong) continue
            val cur = candidates[s.key]?.also { it.count++; it.lastPts = s.lastSeenPts }
                ?: Candidate(n, s.lastSeenPts, s.lastSeenPts, 1).also { candidates[s.key] = it }
            val held = cur.count >= config.speedLimitMinSeen && cur.lastPts - cur.sincePts >= config.speedLimitDwellSeconds - EPS
            if (!held) continue
            if (cur.confirmed) {
                if (cur.valueMph == signMph) confirmedAtPts = now // read again: the hold restarts
                continue
            }
            cur.confirmed = true
            val closer = best == null || (d ?: Double.MAX_VALUE) < (best.distanceMeters ?: Double.MAX_VALUE)
            if (closer) best = Confirmed(n, d, s.key)
        }
        val b = best ?: return null
        val isNew = b.valueMph != signMph
        signMph = b.valueMph
        confirmedAtPts = now
        mapAtSign = map
        return b.takeIf { isNew }
    }

    private fun clearSign() {
        signMph = null
        confirmedAtPts = Double.NaN
        mapAtSign = null
        staleDeadlineNs = null
        // Reads from before the clear do not count towards the next confirmation.
        candidates.clear()
        readsAfterPts = lastReadPts
    }

    private companion object {
        const val EPS = 1e-6

        val ROAD_CHANGING = setOf(
            Maneuver.TURN_LEFT, Maneuver.TURN_RIGHT, Maneuver.EXIT, Maneuver.ENTER_HIGHWAY, Maneuver.KEEP_LEFT,
            Maneuver.KEEP_RIGHT, Maneuver.MERGE, Maneuver.MERGE_LEFT, Maneuver.MERGE_RIGHT,
        )

        /** Another value, or another named road (an unnamed side is not a change). */
        private fun roadChanged(a: MapValue, b: MapValue): Boolean =
            a.valueMph != b.valueMph || (a.road != null && b.road != null && a.road != b.road)
    }
}
