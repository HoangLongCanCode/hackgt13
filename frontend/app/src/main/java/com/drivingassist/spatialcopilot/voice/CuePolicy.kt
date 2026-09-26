package com.drivingassist.spatialcopilot.voice

import com.drivingassist.copilot.context.DrivingContext
import com.drivingassist.copilot.context.FollowingInfo
import com.drivingassist.copilot.context.FollowingState
import com.drivingassist.copilot.context.Maneuver
import com.drivingassist.copilot.context.Priority
import com.drivingassist.copilot.context.WorldSnapshot
import com.drivingassist.copilot.perception.LightState
import com.drivingassist.spatialcopilot.nav.RouteGuide
import kotlin.math.abs
import kotlin.math.max

/** Everything the cue rules look at, sampled once per step (every ~50 ms). Time is the app's own clock. */
data class PolicyInput(
    val nowMs: Long,
    val context: DrivingContext,
    /** The bridge's merged world (not display-predicted): raw light state, confidence, lateral offset. */
    val world: WorldSnapshot,
    val linkConnected: Boolean,
    val takenOver: Boolean,
    /** SIM with the clip paused: nothing new is announced. */
    val simPaused: Boolean,
    val hostVisible: Boolean,
    val route: RouteGuide?,
    /** Distance to the maneuver now (moved on from the packet). */
    val routeDistanceMeters: Double?,
    val live: Boolean,
    val gpsAccuracyMeters: Double?,
)

/** A request for one presentation. [key] = (cue, scope): the arbiter dedupes and replaces by it. */
data class CueRequest(
    val cueId: String,
    val key: String,
    val priority: Priority,
    /** Null for earcon-only presentations. */
    val text: String?,
    val earcon: String?,
    val profile: String,
    val createdMs: Long,
    val ttlMs: Long,
)

/**
 * Deterministic cue rules (AUDIO_CUE_RULES.md sections 5-6, numbers from [CueCatalog]). No LLM, no
 * speech from the engine's texts: every sentence is a catalog phrase or rendered by [SpokenText].
 *
 * TOO CLOSE: spoken once when the lead has been CRITICAL (with the catalog's voiced trigger) for the
 * persistence time; not repeated while the state holds; armed again only after the following state is
 * back to NORMAL on good perception. A re-entry within `minRepeatMs` of the last warning plays the
 * earcon alone. (The spec also allows a repeat on escalation inside an episode; this build does not, so a
 * warning never repeats while TOO CLOSE holds.)
 *
 * Implemented cues: safety.vehicle_too_close, safety.pedestrian_critical, alert.pedestrian,
 * alert.red_light, info.road_alerts_paused / back, nav.start, nav.prepare, nav.immediate,
 * nav.arrive_prepare, nav.arrived. The catalog's off-by-default cues are not implemented.
 */
class CuePolicy(private val catalog: CueCatalog) {

    // --- safety.vehicle_too_close ------------------------------------------------------------------
    private var vtcSince: Long? = null
    private var vtcLead: Int? = null
    private var vtcArmed = true
    private var vtcLastSpeechMs: Long? = null
    private var vtcPendingPrevSpeech: Long? = null

    // --- pedestrians (shared episode) ---------------------------------------------------------------
    private var pedSince: Long? = null
    private var pedLastTrue: Long? = null
    private var pedCritCount = 0
    private var pedAlertCount = 0
    private var pedLastCritMs: Long? = null

    // --- red light --------------------------------------------------------------------------------
    private var redSince: Long? = null
    private var redTrack: Int? = null
    private var redLastTrue: Long? = null
    private var redEpisodeSpoken = false
    private var redLastSpeechMs: Long? = null

    // --- road alerts paused / back ----------------------------------------------------------------
    private val health = ArrayDeque<Pair<Long, Boolean>>()
    private var goodSince: Long? = null
    private var hadGoodSecond = false
    private var pausedSpoken = false
    private var pausedLastMs: Long? = null

    // --- perception warm-up -----------------------------------------------------------------------
    private var perceptionGoodSince: Long? = null

    // --- navigation -------------------------------------------------------------------------------
    private var routeKey: String? = null
    private var routeSeenMs = 0L
    private var startSpoken = false
    private val navSpoken = HashSet<String>()

    fun step(input: PolicyInput): List<CueRequest> {
        val out = ArrayList<CueRequest>()
        val now = input.nowMs
        val ctx = input.context
        val perceptionOk = !ctx.perceptionStale && input.linkConnected && !input.takenOver
        if (perceptionOk) { if (perceptionGoodSince == null) perceptionGoodSince = now } else perceptionGoodSince = null
        val warm = perceptionGoodSince?.let { now - it >= WARMUP_MS } == true
        val common = input.hostVisible && !input.simPaused
        val perceptionGates = common && perceptionOk && warm

        vehicleTooClose(input, perceptionGates, out)
        pedestrians(input, perceptionGates, out)
        redLight(input, perceptionGates, out)
        roadAlerts(input, common, out)
        navigation(input, common && !input.takenOver, out)
        return out
    }

    /** The arbiter dropped [r] before it played (TTL, gate, eviction): re-arm what it would have used up. */
    fun onDropped(r: CueRequest) {
        when (r.cueId) {
            VTC -> { vtcArmed = true; vtcLastSpeechMs = vtcPendingPrevSpeech }
            PED_CRIT -> pedCritCount = (pedCritCount - 1).coerceAtLeast(0)
            else -> if (r.cueId.startsWith("nav.")) navSpoken.remove(r.key)
        }
    }

    // ------------------------------------------------------------------------------------------------

    private fun vehicleTooClose(input: PolicyInput, gates: Boolean, out: MutableList<CueRequest>) {
        val now = input.nowMs
        val f = input.context.following
        // Re-arm only after the state is back to NORMAL on good perception (a dropout forces NORMAL too).
        if (f.state == FollowingState.NORMAL && gates) vtcArmed = true
        val cond = gates && f.state == FollowingState.CRITICAL && voiced(f, input.context.navigation?.egoSpeedMps)
        if (!cond) { vtcSince = null; return }
        if (vtcSince == null || vtcLead != f.leadTrackId) { vtcSince = now; vtcLead = f.leadTrackId }
        val spec = catalog[VTC]
        if (!vtcArmed || now - vtcSince!! < spec.persistenceMs) return
        vtcArmed = false
        vtcPendingPrevSpeech = vtcLastSpeechMs
        val recent = vtcLastSpeechMs?.let { now - it < spec.minRepeatMs } == true
        if (!recent) vtcLastSpeechMs = now
        out += request(spec, if (recent) null else spec.text, "$VTC#$now", now)
    }

    /** The catalog's voiced trigger: closing (or moving) inside the exit distance, or a range-rate TTC. Headway alone never speaks. */
    private fun voiced(f: FollowingInfo, measuredSpeed: Double?): Boolean {
        val n = catalog[VTC].numbers
        val d = f.distanceMeters ?: return false
        val rel = f.relativeSpeedMps
        val exitD = n["exitDistanceMeters"] ?: 9.0
        val byDistance = d < exitD && ((rel != null && rel <= -0.5) || (measuredSpeed != null && measuredSpeed >= 2.8))
        val ttc = f.ttcSeconds
        val byTtc = ttc != null && rel != null && ttc < (n["exitTtcSeconds"] ?: 2.5) && d >= exitD && rel <= -1.0 &&
            d / -rel <= 3.0 && (measuredSpeed == null || -rel <= measuredSpeed + 3.0)
        return byDistance || byTtc
    }

    private fun pedestrians(input: PolicyInput, gates: Boolean, out: MutableList<CueRequest>) {
        val now = input.nowMs
        val nearest = input.context.pedestriansInPath.firstOrNull()
        val alertSpec = catalog[PED_ALERT]
        val critSpec = catalog[PED_CRIT]
        if (!gates || nearest == null) {
            pedSince = null
            if (pedLastTrue?.let { now - it > critSpec.episodeEndMs } == true) {
                pedLastTrue = null; pedCritCount = 0; pedAlertCount = 0 // episode over
            }
            return
        }
        pedLastTrue = now
        if (pedSince == null) pedSince = now
        if (now - pedSince!! < critSpec.persistenceMs) return
        val critical = (nearest.distanceMeters?.let { it <= 12.0 } == true) || (nearest.ttcSeconds?.let { it <= 3.0 } == true)
        if (critical) {
            if (pedCritCount < critSpec.maxPerEpisode && pedLastCritMs?.let { now - it < critSpec.minRepeatMs } != true) {
                pedCritCount++
                pedLastCritMs = now
                out += request(critSpec, critSpec.text, "$PED_CRIT#$pedCritCount", now)
            }
        } else if (pedAlertCount < alertSpec.maxPerEpisode && pedCritCount == 0) {
            pedAlertCount++
            out += request(alertSpec, alertSpec.text, "$PED_ALERT#$now", now)
        }
    }

    private fun redLight(input: PolicyInput, gates: Boolean, out: MutableList<CueRequest>) {
        val now = input.nowMs
        val spec = catalog[RED]
        val n = spec.numbers
        val tl = input.context.trafficLight
        val obj = tl?.let { input.world.objects[it.trackId] }
        val d = tl?.distanceMeters
        val v = input.context.navigation?.egoSpeedMps
        val speedOk = v == null || (v >= (n["minMeasuredSpeedMps"] ?: 2.8) && d != null &&
            v * v / (2 * max(0.1, d - v * (n["reactionSeconds"] ?: 1.0))) <= (n["maxDecelMps2"] ?: 4.9))
        val red = tl?.state == LightState.RED
        val cond = gates && red && obj?.rawLightState == LightState.RED &&
            (obj.lightConfidence == null || obj.lightConfidence!! >= (n["minConfidence"] ?: 0.6)) &&
            (obj.lateralMeters == null || abs(obj.lateralMeters!!) <= (n["maxLateralMeters"] ?: 6.0)) &&
            d != null && d in (n["minMeters"] ?: 15.0)..(n["maxMeters"] ?: 60.0) && speedOk
        if (red) redLastTrue = now
        if (redLastTrue?.let { now - it > spec.episodeEndMs } == true) { redEpisodeSpoken = false; redLastTrue = null }
        if (!cond) { redSince = null; return }
        if (redSince == null || redTrack != tl!!.trackId) { redSince = now; redTrack = tl!!.trackId }
        if (redEpisodeSpoken || now - redSince!! < spec.persistenceMs) return
        if (redLastSpeechMs?.let { now - it < spec.minRepeatMs } == true) return
        redEpisodeSpoken = true
        redLastSpeechMs = now
        out += request(spec, spec.text, "$RED#$now", now)
    }

    private fun roadAlerts(input: PolicyInput, gates: Boolean, out: MutableList<CueRequest>) {
        val now = input.nowMs
        val bad = input.context.perceptionStale || !input.linkConnected || input.takenOver
        health.addLast(now to bad)
        while (health.isNotEmpty() && now - health.first().first > WINDOW_MS) health.removeFirst()
        if (!bad) { if (goodSince == null) goodSince = now } else goodSince = null
        if (goodSince?.let { now - it >= 1_000 } == true) hadGoodSecond = true
        if (!gates) return
        var badMs = 0L
        for (i in 1 until health.size) if (health[i - 1].second) badMs += health[i].first - health[i - 1].first
        val spanMs = if (health.size >= 2) health.last().first - health.first().first else 0L
        val paused = hadGoodSecond && spanMs >= WINDOW_MS - 200 && badMs >= STALE_MS
        val pausedSpec = catalog[PAUSED]
        if (paused && !pausedSpoken && pausedLastMs?.let { now - it < pausedSpec.minRepeatMs } != true) {
            pausedSpoken = true
            pausedLastMs = now
            out += request(pausedSpec, pausedSpec.text, "$PAUSED#$now", now)
        }
        val backSpec = catalog[BACK]
        if (pausedSpoken && goodSince?.let { now - it >= backSpec.persistenceMs } == true) {
            pausedSpoken = false
            out += request(backSpec, backSpec.text, "$BACK#$now", now)
        }
    }

    private fun navigation(input: PolicyInput, gates: Boolean, out: MutableList<CueRequest>) {
        val r = input.route ?: return
        val now = input.nowMs
        if (r.routeKey != routeKey) {
            routeKey = r.routeKey
            routeSeenMs = now
            startSpoken = false
            navSpoken.clear()
        }
        if (!gates || r.stale || r.offRoute) return
        if (now - routeSeenMs < SETTLE_MS) return
        val nav = catalog.nav
        if (!startSpoken) {
            startSpoken = true
            val spec = catalog["nav.start"]
            out += request(spec, "Starting route to your destination.", "nav.start/${r.routeKey}", now)
        }
        val d = input.routeDistanceMeters ?: return
        val v = (r.speedMps ?: nav["defaultSpeedMps"] ?: 11.2).coerceIn(0.5, 45.0)
        val lead = nav["leadS"] ?: 1.0
        val imm = (v * (nav["immediate.seconds"] ?: 7.0)).coerceIn(nav["immediate.minMeters"] ?: 45.0, nav["immediate.maxMeters"] ?: 200.0)
        val prep = prepareMeters(v)
        val skipIfImmediateS = nav["skipPrepareIfImmediateWithinS"] ?: 4.0
        val minExec = nav["minExecutableMeters"] ?: 10.0
        if (r.maneuver == Maneuver.ARRIVE) {
            val arrived = nav["arrivedMeters"] ?: 15.0
            if (d <= arrived) {
                if (!(input.live && r.provider == "mock")) once(out, "nav.arrived", "${r.eventKey}:arrived", "You have arrived at your destination.", now)
            } else if (d <= prep + v * lead && d - arrived >= v * skipIfImmediateS) {
                SpokenText.distance(d - v * lead)?.let { once(out, "nav.arrive_prepare", "${r.eventKey}:prepare", SpokenText.arrivePrepare(it), now) }
            }
            return
        }
        SpokenText.maneuver(r.maneuver) ?: return // nothing to say for FOLLOW_ROAD / STOP
        if (d in minExec..imm) {
            val uncertain = input.live && (input.gpsAccuracyMeters == null || input.gpsAccuracyMeters > (nav["immediateMaxAccuracyMeters"] ?: 20.0))
            if (input.live && input.gpsAccuracyMeters != null && d < input.gpsAccuracyMeters) return
            val words = if (uncertain) SpokenText.distance(d - v * lead) else null
            val text = if (words != null) SpokenText.prepare(words, r.maneuver) else SpokenText.immediate(r.maneuver)
            text?.let { once(out, "nav.immediate", "${r.eventKey}:immediate", it, now) }
        } else if (d <= prep + v * lead && d - imm >= v * skipIfImmediateS) {
            SpokenText.distance(d - v * lead)?.let { words ->
                SpokenText.prepare(words, r.maneuver)?.let { once(out, "nav.prepare", "${r.eventKey}:prepare", it, now) }
            }
        }
    }

    private fun prepareMeters(v: Double): Double {
        val nav = catalog.nav
        for (i in 0..4) {
            val meters = nav["prepare.$i.meters"] ?: break
            val below = nav["prepare.$i.belowMps"]
            if (below == null || v < below) return meters
        }
        return 300.0
    }

    private fun once(out: MutableList<CueRequest>, cueId: String, key: String, text: String, now: Long) {
        if (!navSpoken.add(key)) return
        val spec = catalog[cueId]
        out += request(spec, text, key, now)
    }

    private fun request(spec: CueSpec, text: String?, key: String, now: Long) = CueRequest(
        cueId = spec.id,
        key = key,
        priority = spec.priority,
        text = text,
        earcon = spec.earcon,
        profile = spec.voiceProfile,
        createdMs = now,
        ttlMs = spec.ttlMs,
    )

    companion object {
        const val VTC = "safety.vehicle_too_close"
        const val PED_CRIT = "safety.pedestrian_critical"
        const val PED_ALERT = "alert.pedestrian"
        const val RED = "alert.red_light"
        const val PAUSED = "info.road_alerts_paused"
        const val BACK = "info.road_alerts_back"
        const val WARMUP_MS = 500L
        const val WINDOW_MS = 3_000L
        const val STALE_MS = 2_500L
        const val SETTLE_MS = 500L
    }
}
