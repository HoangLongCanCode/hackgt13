package com.drivingassist.spatialcopilot.voice

import com.drivingassist.copilot.context.DrivingContext
import com.drivingassist.copilot.context.FollowingInfo
import com.drivingassist.copilot.context.FollowingState
import com.drivingassist.copilot.context.LaneAction
import com.drivingassist.copilot.context.LaneGuidance
import com.drivingassist.copilot.context.LaneLayout
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
    /**
     * The Driving Context's speed gate (`DrivingContextConfig.criticalMinEgoSpeedMps`, set by the settings switch),
     * null = off. Below it (route speed known) the car counts as stopped: no pedestrian cue.
     */
    val speedGateMps: Double? = null,
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
    /** A fixed phrase to play instead when [text] has no audio by its readiness deadline ("Take the exit."). */
    val fallbackText: String? = null,
    /** The route event of a nav stage or lane cue: an IMMEDIATE prompt cuts an UPCOMING one of the same event (7.2). */
    val eventKey: String? = null,
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
 * Lane change: `lane.change_left` / `lane.change_right` once per (event key, side) on evidence over a sliding
 * `evidenceWindowMs` of steps: the Driving Context asked for that side in `evidenceShare` of them, the lane layout
 * was stable and good in as many, and the side was asked within `lastAskWithinMs`; with room to say it before the
 * immediate prompt is due ("Move to the right lane for the exit, check for cars.": the driver is asked to look;
 * the lane is never called free). A share, not a continuous run: a layout that loses a lane behind the A-pillar
 * now and then must not keep the cue silent. A middle target lane says the side only ("Move right ..."). At most
 * `maxPresentationsPerEventKey` presentations per event key; the lane cue is the one left out.
 *
 * Pedestrians: no cue while the car is stopped ([PolicyInput.speedGateMps] set and the route speed below it), as
 * the Driving Context holds TOO CLOSE back then; an unknown speed never holds them.
 *
 * Implemented cues: safety.vehicle_too_close, safety.pedestrian_critical, alert.pedestrian,
 * alert.red_light, info.road_alerts_paused / back, nav.start, nav.continue, nav.prepare, nav.immediate,
 * nav.arrive_prepare, nav.arrived, lane.change_left / right. The catalog's off-by-default cues are not
 * implemented.
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
    private var eventKey: String? = null
    private var eventSeenMs = 0L

    // --- lane change --------------------------------------------------------------------------------
    /** One step of lane evidence: the side asked for (null: none, or the PERCEPTION gates were closed) and the layout. */
    private class LaneSample(val t: Long, val side: String?, val layoutStable: Boolean, val quality: Double, val ageSeconds: Double)
    private val laneSamples = ArrayDeque<LaneSample>()
    /** Event key the evidence is collected for, and its first step (a new event key or a gap in the steps starts over). */
    private var laneEvidenceKey: String? = null
    private var laneEvidenceSince = 0L
    /** The last guidance that asked for each side: its target lanes pick the phrase while the guidance flickers. */
    private val laneAsk = HashMap<String, LaneGuidance>()
    private val laneSpoken = HashSet<String>()

    /** Keys (cue / event key) whose every condition but the evidence held on the last step. */
    private val laneHeld = HashSet<String>()
    private var stepMs = 0L

    /** The lane cue's inputs on the last step, for the voice log when a queued lane cue is dropped. */
    var laneState: String = ""
        private set

    fun step(input: PolicyInput): List<CueRequest> {
        val out = ArrayList<CueRequest>()
        val now = input.nowMs
        stepMs = now
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
        laneChange(input, perceptionGates, out)
        return out
    }

    /** The arbiter dropped [r] before it played (TTL, gate, eviction): re-arm what it would have used up. */
    fun onDropped(r: CueRequest) {
        when (r.cueId) {
            VTC -> { vtcArmed = true; vtcLastSpeechMs = vtcPendingPrevSpeech }
            PED_CRIT -> pedCritCount = (pedCritCount - 1).coerceAtLeast(0)
            LANE_LEFT, LANE_RIGHT -> laneSpoken.remove(r.key)
            else -> if (r.cueId.startsWith("nav.")) navSpoken.remove(r.key)
        }
    }

    /**
     * Re-validation of a queued [r] before it starts, on the last step's state: a lane cue needs the same event key,
     * every condition but the evidence (gates, route, room before the immediate prompt), and its side asked for in
     * more than half of the steps of the last `revalidateWindowMs`, so a short KEEP_LANE or UNKNOWN flicker (the
     * layout too old for lane numbers for a moment) does not drop it, but the car settled in the lane does. Other
     * cues: true.
     */
    fun stillValid(r: CueRequest): Boolean {
        if (r.cueId != LANE_LEFT && r.cueId != LANE_RIGHT) return true
        val windowMs = catalog[r.cueId].numbers["revalidateWindowMs"] ?: 1_000.0
        val recent = laneSamples.filter { stepMs - it.t < windowMs }
        return r.key in laneHeld && recent.count { it.side == r.cueId } * 2 > recent.size
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

    /**
     * `alert.pedestrian` / `safety.pedestrian_critical` (one shared episode). Not while stopped: with the speed gate on
     * and the route speed known and below it (people crossing in front of a car waiting at a light), no cue is created;
     * the episode goes on meanwhile, so driving off with the same pedestrian in the path does not repeat an alert
     * already spoken. Moving, or with an unknown speed, as before.
     */
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
        if (stopped(input)) { pedSince = null; return }
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

    /** The speed gate is on and the route speed is known and below it (the Driving Context holds CRITICAL back then too). */
    private fun stopped(input: PolicyInput): Boolean {
        val gate = input.speedGateMps ?: return false
        val v = input.context.navigation?.egoSpeedMps ?: return false
        return v < gate
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
            laneSpoken.clear()
        }
        if (r.eventKey != eventKey) {
            eventKey = r.eventKey
            eventSeenMs = now
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
        val v = navSpeed(r)
        val lead = nav["leadS"] ?: 1.0
        val imm = immediateMeters(v)
        val prep = prepareMeters(v)
        val skipIfImmediateS = nav["skipPrepareIfImmediateWithinS"] ?: 4.0
        val minExec = nav["minExecutableMeters"] ?: 10.0
        // A far, settled target: "Drive straight for three miles." Only continueMinGapSeconds of travel or more
        // before the prepare window, so it is never followed right away by nav.prepare with the same distance.
        // Not for FOLLOW_ROAD / STOP (no maneuver to drive to).
        val continueMin = max((nav["continueMinSeconds"] ?: 60.0) * v, nav["continueMinMeters"] ?: 1_609.344)
        val continueGap = v * (nav["continueMinGapSeconds"] ?: 30.0)
        val hasPrompt = r.maneuver == Maneuver.ARRIVE || SpokenText.maneuver(r) != null
        if (hasPrompt && now - eventSeenMs >= SETTLE_MS && d >= continueMin && d - (prep + v * lead) >= continueGap) {
            SpokenText.distance(d - v * lead)?.let { words ->
                val text = catalog[CONTINUE].text?.replace("{distance}", words)
                fitting(CONTINUE, text)?.let { once(out, CONTINUE, r.eventKey, "continue", it, now) }
            }
        }
        if (r.maneuver == Maneuver.ARRIVE) {
            val arrived = nav["arrivedMeters"] ?: 15.0
            if (d <= arrived) {
                if (!(input.live && r.provider == "mock")) once(out, "nav.arrived", r.eventKey, "arrived", "You have arrived at your destination.", now)
            } else if (d <= prep + v * lead && d - arrived >= v * skipIfImmediateS) {
                SpokenText.distance(d - v * lead)?.let { once(out, "nav.arrive_prepare", r.eventKey, "prepare", SpokenText.arrivePrepare(it), now) }
            }
            return
        }
        if (!hasPrompt) return // nothing to say for FOLLOW_ROAD / STOP
        // An exit says its number ("Take exit ninety-four."); a sentence over its word budget drops the number.
        if (d in minExec..imm) {
            val uncertain = input.live && (input.gpsAccuracyMeters == null || input.gpsAccuracyMeters > (nav["immediateMaxAccuracyMeters"] ?: 20.0))
            if (input.live && input.gpsAccuracyMeters != null && d < input.gpsAccuracyMeters) return
            val words = if (uncertain) SpokenText.distance(d - v * lead) else null
            val banked = SpokenText.immediate(r, withExitNumber = false)
            val text = if (words != null) {
                fitting(IMMEDIATE, SpokenText.prepare(words, r), SpokenText.prepare(words, r, withExitNumber = false), SpokenText.immediate(r), banked)
            } else {
                fitting(IMMEDIATE, SpokenText.immediate(r), banked)
            }
            // The banked bare form stands in only when the position is certain: in the LIVE uncertain case the
            // distance sentence waits for its own audio (6.5), it never falls back to "Turn right.".
            text?.let { once(out, IMMEDIATE, r.eventKey, "immediate", it, now, fallback = banked.takeIf { !uncertain }) }
        } else if (d <= prep + v * lead && d - imm >= v * skipIfImmediateS) {
            SpokenText.distance(d - v * lead)?.let { words ->
                fitting(PREPARE, SpokenText.prepare(words, r), SpokenText.prepare(words, r, withExitNumber = false))
                    ?.let { once(out, PREPARE, r.eventKey, "prepare", it, now) }
            }
        }
    }

    private fun laneChange(input: PolicyInput, gates: Boolean, out: MutableList<CueRequest>) {
        val now = input.nowMs
        val g = input.context.laneGuidance
        val r = input.route
        val asked = when (g?.action) {
            LaneAction.CHANGE_LANE_LEFT -> LANE_LEFT
            LaneAction.CHANGE_LANE_RIGHT -> LANE_RIGHT
            else -> null
        }?.takeIf { gates }
        // A new event key, or a gap in the steps (the window would cover time nobody sampled), starts the evidence over.
        if (r?.eventKey != laneEvidenceKey || laneSamples.lastOrNull()?.let { now - it.t > LANE_MAX_STEP_GAP_MS } == true) {
            laneSamples.clear()
            laneAsk.clear()
            laneEvidenceKey = r?.eventKey
            laneEvidenceSince = now
        }
        if (asked != null) laneAsk[asked] = g!!
        val layout = input.world.laneLayout
        laneSamples.addLast(LaneSample(
            now, asked, layout?.let { it.stable && it.ageSeconds <= LaneLayout.MAX_USABLE_AGE_SECONDS } == true,
            layout?.quality ?: 0.0, layout?.ageSeconds ?: Double.MAX_VALUE,
        ))
        val keepMs = LANE_SIDES.maxOf { max(catalog[it].numbers["evidenceWindowMs"] ?: 2_000.0, catalog[it].numbers["revalidateWindowMs"] ?: 1_000.0) }
        while (now - laneSamples.first().t >= keepMs) laneSamples.removeFirst()
        laneHeld.clear()
        val inferred = input.context.navigation?.laneHintInferred == true
        val shares = StringBuilder()
        for (id in LANE_SIDES) {
            val spec = catalog[id]
            val n = spec.numbers
            val windowMs = n["evidenceWindowMs"] ?: 2_000.0
            val window = laneSamples.filter { now - it.t < windowMs }
            val askShare = window.count { it.side == id }.toDouble() / window.size
            val layoutShare = window.count { layoutOk(it, spec) }.toDouble() / window.size
            shares.append(" ${if (id == LANE_LEFT) "L" else "R"} ask ${"%.2f".format(askShare)} layout ${"%.2f".format(layoutShare)}")
            if (r == null) continue
            val text = laneAsk[id]?.let { laneText(spec, r, it, inferred) } ?: continue
            if (!laneHolds(input, gates, spec, r, text)) continue
            val key = "${spec.id}/${r.eventKey}"
            laneHeld += key
            // The evidence: a full window, both shares, and the side asked for lately (not only long ago).
            val share = (n["evidenceShare"] ?: 0.7) - 1e-9
            val lastAsk = window.lastOrNull { it.side == id }?.t ?: continue
            if (now - laneEvidenceSince < windowMs || askShare < share || layoutShare < share || now - lastAsk > (n["lastAskWithinMs"] ?: 500.0)) continue
            if (key in laneSpoken || !laneFits(r, input.routeDistanceMeters!!)) continue
            laneSpoken += key
            out += request(spec, text, key, now, r.eventKey)
        }
        laneState = "action ${g?.action} gates $gates d ${input.routeDistanceMeters?.toInt()} v ${r?.let { navSpeed(it).toInt() }} " +
            "layout ${layout?.let { "q ${it.quality} age ${it.ageSeconds} stable ${it.stable} lane ${it.egoLane}/${it.laneCount}" }}$shares key ${r?.eventKey}"
    }

    /**
     * Every lane-cue condition on the current step but the evidence (AUDIO_CUE_RULES.md 6.5): the PERCEPTION
     * gates, a route that is neither stale nor off route, an inferred side only when the catalog allows it, and a
     * known distance with room to say [text] before the immediate prompt is due: `d - I(v) >= v x (its duration +
     * 1 s)`, the same margin as nav.prepare at bind (7.3 item 6).
     */
    private fun laneHolds(input: PolicyInput, gates: Boolean, spec: CueSpec, r: RouteGuide, text: String): Boolean {
        val d = input.routeDistanceMeters ?: return false
        val v = navSpeed(r)
        val inferredOk = input.context.navigation?.laneHintInferred != true || spec.flags["allowInferredLaneSide"] == true
        return gates && spec.enabledByDefault && !r.stale && !r.offRoute && inferredOk &&
            d - immediateMeters(v) >= v * (spokenSeconds(text) + 1.0)
    }

    /**
     * The step's lane layout (built from the detected lines) counts as evidence: present, stable (the bridge's
     * hysteresis), at most `maxAgeSeconds` and [LaneLayout.MAX_USABLE_AGE_SECONDS] old, of quality at least
     * `minLayoutQuality` (6.5). The lane model's own confidence is not used: it stays at 0.2-0.4 in the city while
     * the lines themselves are good.
     */
    private fun layoutOk(s: LaneSample, spec: CueSpec): Boolean =
        s.layoutStable && s.ageSeconds <= (spec.numbers["maxAgeSeconds"] ?: 1.0) && s.quality >= (spec.numbers["minLayoutQuality"] ?: 0.5)

    /**
     * At most `maxPresentationsPerEventKey` (3) per event key, the lane cue dropped first (6.5): the stages and lane
     * cues already made plus the stages still due (the final prompt; nav.prepare while its window is ahead).
     */
    private fun laneFits(r: RouteGuide, d: Double): Boolean {
        val ev = r.eventKey
        val v = navSpeed(r)
        val made = NAV_STAGES.count { "$ev:$it" in navSpoken } + listOf(LANE_LEFT, LANE_RIGHT).count { "$it/$ev" in laneSpoken }
        val last = if (r.maneuver == Maneuver.ARRIVE) "arrived" else "immediate"
        val due = (if ("$ev:$last" in navSpoken) 0 else 1) +
            (if ("$ev:prepare" !in navSpoken && d > prepareMeters(v) + v * (catalog.nav["leadS"] ?: 1.0)) 1 else 0)
        return made + due < (catalog.nav["maxPresentationsPerEventKey"] ?: 3.0).toInt()
    }

    /**
     * "Move to the right lane ..." only when the target is that edge lane (the rightmost for right, lane 1 for left,
     * or a side inferred from the maneuver); a middle target (a numeric requiredLane) says the side only ("Move right
     * ..."), never a lane number. Then "... for the turn" (TURN_LEFT / TURN_RIGHT), "... for the exit"
     * ([RouteGuide.isExit]), else the plain text.
     */
    private fun laneText(spec: CueSpec, r: RouteGuide, g: LaneGuidance, inferred: Boolean): String? {
        val edge = inferred || if (spec.id == LANE_RIGHT) g.laneCount?.let { it in g.targetLanes } == true else 1 in g.targetLanes
        val base = if (edge) "text" else "textSide"
        val forWhat = when {
            r.isExit -> "ForExit"
            r.maneuver == Maneuver.TURN_LEFT || r.maneuver == Maneuver.TURN_RIGHT -> "ForTurn"
            else -> ""
        }
        return spec.texts["$base$forWhat"] ?: spec.texts[base]
    }

    /** The spec's timing model for an utterance: `words / 2.6 + 0.2 s`. */
    private fun spokenSeconds(text: String): Double = CueCatalog.words(text) / 2.6 + 0.2

    private fun navSpeed(r: RouteGuide): Double = (r.speedMps ?: catalog.nav["defaultSpeedMps"] ?: 11.2).coerceIn(0.5, 45.0)

    /** I(v): where the immediate prompt is due. */
    private fun immediateMeters(v: Double): Double {
        val nav = catalog.nav
        return (v * (nav["immediate.seconds"] ?: 7.0)).coerceIn(nav["immediate.minMeters"] ?: 45.0, nav["immediate.maxMeters"] ?: 200.0)
    }

    /** The first candidate that matches the speech regex and fits [cueId]'s word budget. */
    private fun fitting(cueId: String, vararg candidates: String?): String? {
        val priority = catalog[cueId].priority
        return candidates.firstOrNull { it != null && catalog.fits(it, priority) }
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

    /** One nav stage ([NAV_STAGES]) of [eventKey], keyed `<eventKey>:<stage>`, at most once. */
    private fun once(out: MutableList<CueRequest>, cueId: String, eventKey: String, stage: String, text: String, now: Long, fallback: String? = null) {
        val key = "$eventKey:$stage"
        if (!navSpoken.add(key)) return
        val spec = catalog[cueId]
        out += request(spec, text, key, now, eventKey).copy(fallbackText = fallback?.takeIf { it != text })
    }

    private fun request(spec: CueSpec, text: String?, key: String, now: Long, eventKey: String? = null) = CueRequest(
        cueId = spec.id,
        key = key,
        priority = spec.priority,
        text = text,
        earcon = spec.earcon,
        profile = spec.voiceProfile,
        createdMs = now,
        ttlMs = spec.ttlMs,
        eventKey = eventKey,
    )

    companion object {
        const val VTC = "safety.vehicle_too_close"
        const val PED_CRIT = "safety.pedestrian_critical"
        const val PED_ALERT = "alert.pedestrian"
        const val RED = "alert.red_light"
        const val PAUSED = "info.road_alerts_paused"
        const val BACK = "info.road_alerts_back"
        const val CONTINUE = "nav.continue"
        const val PREPARE = "nav.prepare"
        const val IMMEDIATE = "nav.immediate"
        const val LANE_LEFT = "lane.change_left"
        const val LANE_RIGHT = "lane.change_right"
        private val LANE_SIDES = listOf(LANE_LEFT, LANE_RIGHT)
        /** The per-event-key nav stages ([once] keys `<eventKey>:<stage>`; nav.arrive_prepare uses `prepare`). */
        val NAV_STAGES = listOf("continue", "prepare", "immediate", "arrived")
        const val WARMUP_MS = 500L
        const val WINDOW_MS = 3_000L
        const val STALE_MS = 2_500L
        const val SETTLE_MS = 500L
        /** Steps further apart than this (the voice loop was not running) start the lane evidence over. */
        const val LANE_MAX_STEP_GAP_MS = 500L
    }
}
