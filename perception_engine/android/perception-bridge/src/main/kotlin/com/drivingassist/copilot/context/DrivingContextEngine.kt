package com.drivingassist.copilot.context

import com.drivingassist.copilot.perception.LightState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlin.math.roundToInt

/**
 * Deterministic Driving Context Engine (plan §15-§18, §24). No LLM: the same WorldSnapshot +
 * NavigationState sequence always yields the same context and events (time = media pts; the one exception is
 * a speed-limit sign value held while perception is stale, timed on [clockNs] because media time stops then).
 *
 * Navigation comes from the phase1 route engine (`navigation.packet` -> [NavigationMapper]); this
 * engine does not plan routes. It combines the route's next maneuver / distance / required lane with
 * the perceived lanes ([WorldSnapshot.laneLayout], built from the detected lane lines; never the server's
 * `lanes.currentLane`) into lane guidance ("PREPARE TO MOVE RIGHT | 2 LANES").
 * The lead vehicle for the following distance is the nearest one in the car's own lane ([WorldSnapshot.leadVehicle]).
 *
 * Staleness (plan §38): when [WorldSnapshot.perceptionStale] is set (or no perception has arrived
 * yet) every object / distance / light / sign alert is suppressed and only navigation guidance is
 * produced; [DrivingEventType.PERCEPTION_LOST] / [DrivingEventType.PERCEPTION_RESTORED] mark the edges.
 *
 * Outputs:
 * - [context]: persistent state (following state, light, pedestrians, lane guidance, alerts).
 * - [events]: edges (state changes), debounced, each with a §24 [Priority] and optional speech.
 *
 * Information / alerts only: it never controls the vehicle (plan §38).
 */
class DrivingContextEngine(
    val config: DrivingContextConfig = DrivingContextConfig(),
    /** Monotonic wall clock (ns); only times a sign value held while perception is stale (inject it in tests). */
    private val clockNs: () -> Long = System::nanoTime,
) {

    data class Result(val context: DrivingContext, val events: List<DrivingEvent>)

    private val _context = MutableStateFlow(DrivingContext.EMPTY)
    val context: StateFlow<DrivingContext> = _context.asStateFlow()

    private val _events = MutableSharedFlow<DrivingEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<DrivingEvent> = _events.asSharedFlow()

    private val gate = EventGate()
    private var following = FollowingState.NORMAL
    private var lastLaneAction: LaneAction? = null
    private val speedLimit = SpeedLimitLatch(config)
    private var lastSeq = Long.MIN_VALUE
    private var lastNav: NavigationState? = null
    private var lastRevision = Long.MIN_VALUE
    private var lastStale: Boolean? = null
    private var perceptionWasStale = false
    private var hadPerception = false
    private var lastLightId: Int? = null

    /**
     * Re-evaluates whenever the world (new frame, wave-2 merge, staleness flip) or the navigation
     * state changes (latest wins). Navigation-only evaluation runs even before any perception arrives.
     * While perception is stale and a sign value is held, it also re-evaluates when that value runs out
     * (a link loss may bring no new input at all).
     */
    suspend fun run(world: StateFlow<WorldSnapshot>, navigation: StateFlow<NavigationState?>) {
        combine(world, navigation) { w, n -> w to n }.collectLatest { (w, n) ->
            val changed = w.revision != lastRevision || n != lastNav || w.perceptionStale != lastStale
            if ((w.timing != null || n != null || lastNav != null) && changed) evaluate(w, n)
            while (true) { // cancelled by the next input
                val deadline = speedLimit.staleDeadlineNs ?: break
                delay(((deadline - clockNs()) / 1_000_000L).coerceAtLeast(0L) + 1L)
                evaluate(w, n)
            }
        }
    }

    /** One deterministic step. Publishes to [context] / [events] and returns the same data. */
    fun evaluate(input: WorldSnapshot, navigation: NavigationState? = null): Result {
        val stale = input.perceptionStale || input.timing == null
        if (!stale && input.seq < lastSeq) reset() // new session / video looped
        if (!stale) lastSeq = input.seq
        lastNav = navigation
        lastRevision = input.revision
        lastStale = input.perceptionStale
        // While stale, perception content is ignored entirely: navigation-only (plan §38).
        val world = if (stale) input.navigationOnly() else input
        val now = input.ptsSeconds
        val seq = input.seq
        val out = ArrayList<DrivingEvent>()
        val alerts = ArrayList<DrivingEvent>()
        fun event(type: DrivingEventType, priority: Priority, text: String, speech: String?, distance: Double? = null, trackId: Int? = null, lanes: Int? = null) =
            DrivingEvent(type, priority, text, speech, now, seq, distance, trackId, lanes)

        // --- Perception availability edges (§38) ------------------------------------------------
        if (stale && !perceptionWasStale && hadPerception) {
            out += event(DrivingEventType.PERCEPTION_LOST, Priority.GENERAL_INFORMATION, "ROAD ALERTS PAUSED | NAVIGATION ONLY", null)
            // Re-announce lights / pedestrians / signs once perception is back.
            gate.forget("light"); gate.forget("ped"); gate.forget("ped-level"); gate.forgetPrefix("sign:")
        }
        if (!stale && perceptionWasStale && hadPerception) {
            out += event(DrivingEventType.PERCEPTION_RESTORED, Priority.GENERAL_INFORMATION, "ROAD ALERTS ACTIVE", null)
        }
        if (!stale) hadPerception = true
        perceptionWasStale = stale

        // --- Following distance (§18) ---------------------------------------------------------
        val lead = world.leadVehicle(
            maxDistanceMeters = config.leadMaxDistanceMeters,
            maxLayoutAgeSeconds = config.maxLayoutAgeSeconds,
            corridorHalfWidthMeters = config.leadCorridorHalfWidthMeters,
        )
        // The distance the state (and every text / FollowingInfo) uses: a low-confidence one cross-checked on the ground.
        val leadDistance = lead?.let { followingDistance(world, it) }
        val leadTtc = lead?.let { o ->
            val raised = leadDistance != null && o.distanceMeters != null && leadDistance > o.distanceMeters
            if (raised && o.ttcSource == "range_rate") o.closingSpeedMps?.takeIf { it > 0.0 }?.let { round2(leadDistance!! / it) } else o.ttcSeconds
        }
        val egoSpeed = navigation?.egoSpeedMps
        val headway = leadDistance?.let { d -> egoSpeed?.takeIf { it > 1.0 }?.let { d / it } }
        val raw = if (stale) FollowingState.NORMAL else nextFollowingState(following, leadDistance, leadTtc, headway, config.following)
        // Optional speed gate: stopped behind a car is close, not closing in (unknown speed never gates).
        val speedGate = config.criticalMinEgoSpeedMps
        val belowGate = speedGate != null && egoSpeed != null && egoSpeed < speedGate
        val next = if (raw == FollowingState.CRITICAL && belowGate) FollowingState.CLOSE else raw
        val dText = leadDistance?.let { "${fmt1(it)} m" } ?: "--"
        val leadName = lead?.cls?.wire?.uppercase() ?: "VEHICLE"
        if (next != following && !stale) {
            out += when (next) {
                FollowingState.CRITICAL -> event(DrivingEventType.VEHICLE_TOO_CLOSE, Priority.CRITICAL_SAFETY, "VEHICLE TOO CLOSE | $dText", "Vehicle too close.", leadDistance, lead?.id)
                FollowingState.CLOSE -> event(
                    DrivingEventType.FOLLOWING_CLOSE, Priority.TRAFFIC_ALERT, "$leadName | $dText",
                    if (following == FollowingState.NORMAL) "${lead?.cls?.wire?.replaceFirstChar { it.uppercase() } ?: "Vehicle"} ahead, ${leadDistance?.roundToInt() ?: "unknown"} meters." else null,
                    leadDistance, lead?.id,
                )
                FollowingState.NORMAL -> event(DrivingEventType.FOLLOWING_NORMAL, Priority.GENERAL_INFORMATION, "DISTANCE | $dText", null, leadDistance, lead?.id)
            }
        }
        following = next
        when (next) {
            FollowingState.CRITICAL -> alerts += event(DrivingEventType.VEHICLE_TOO_CLOSE, Priority.CRITICAL_SAFETY, "VEHICLE TOO CLOSE | $dText", null, leadDistance, lead?.id)
            FollowingState.CLOSE -> alerts += event(DrivingEventType.FOLLOWING_CLOSE, Priority.TRAFFIC_ALERT, "$leadName | $dText", null, leadDistance, lead?.id)
            FollowingState.NORMAL -> Unit
        }
        val followingInfo = FollowingInfo(next, lead?.id, lead?.cls, leadDistance, leadTtc, lead?.relativeSpeedMps, headway?.let(::round2))

        // --- Traffic light (§11, §20 marker "RED 120m") ---------------------------------------
        val light = selectLight(world)
        var lightInfo: TrafficLightInfo? = null
        if (light != null) {
            val state = light.lightState!!
            lightInfo = TrafficLightInfo(light.id, state, light.distanceMeters)
            val text = "${state.name}${light.distanceMeters?.let { " | ${it.roundToInt()} m" } ?: ""}"
            val previous = gate.signature("light")
            if (gate.pass("light", state.name, now)) {
                out += when (state) {
                    LightState.RED -> event(DrivingEventType.TRAFFIC_LIGHT_RED, Priority.TRAFFIC_ALERT, text, "Red light ahead.", light.distanceMeters, light.id)
                    LightState.YELLOW -> event(DrivingEventType.TRAFFIC_LIGHT_YELLOW, Priority.TRAFFIC_ALERT, text, "Yellow light.", light.distanceMeters, light.id)
                    else -> event(DrivingEventType.TRAFFIC_LIGHT_GREEN, Priority.GENERAL_INFORMATION, text, if (previous == "RED" || previous == "YELLOW") "Light is green." else null, light.distanceMeters, light.id)
                }
            }
            if (state == LightState.RED || state == LightState.YELLOW) {
                val type = if (state == LightState.RED) DrivingEventType.TRAFFIC_LIGHT_RED else DrivingEventType.TRAFFIC_LIGHT_YELLOW
                alerts += event(type, Priority.TRAFFIC_ALERT, text, null, light.distanceMeters, light.id)
            }
        }
        gate.forgetIfUnseen("light", now, config.lightForgetSeconds)

        // --- Pedestrians in path (§17) ---------------------------------------------------------
        val peds = world.pedestriansInPath(config.pedestrianMaxDistanceMeters)
        peds.firstOrNull()?.let { p ->
            // Below the speed gate (waiting at a crosswalk) a pedestrian in the path is a traffic alert, not a critical one.
            val critical = !belowGate && ((p.distanceMeters?.let { it <= config.pedestrianCriticalDistanceMeters } ?: false) ||
                (p.ttcSeconds?.let { it <= config.pedestrianCriticalTtcSeconds } ?: false))
            val priority = if (critical) Priority.CRITICAL_SAFETY else Priority.TRAFFIC_ALERT
            val text = "PEDESTRIAN${p.distanceMeters?.let { " | ${it.roundToInt()} m" } ?: ""}"
            val e = event(DrivingEventType.PEDESTRIAN_IN_PATH, priority, text, "Pedestrian ahead.", p.distanceMeters, p.id)
            alerts += e.copy(speech = null)
            val escalated = critical && gate.signature("ped-level") != Priority.CRITICAL_SAFETY.name
            gate.pass("ped-level", priority.name, now)
            if (gate.pass("ped", "present", now, config.pedestrianRepeatSeconds) || escalated) out += e
        }
        gate.forgetIfUnseen("ped", now, config.pedestrianForgetSeconds)
        gate.forgetIfUnseen("ped-level", now, config.pedestrianForgetSeconds)

        // --- Navigation + lane guidance (§4, §5, §15) ------------------------------------------
        var guidance: LaneGuidance? = null
        if (navigation != null) {
            val immediate = navigation.distanceMeters <= config.immediateNavigationMeters
            val navPriority = if (immediate) Priority.IMMEDIATE_NAVIGATION else Priority.UPCOMING_NAVIGATION
            val stage = if (immediate) "now" else "upcoming"
            if (gate.pass("maneuver", "${navigation.maneuver}|${navigation.label}|$stage", now)) {
                out += event(navigation.maneuver.eventType, navPriority, maneuverText(navigation), maneuverSpeech(navigation), navigation.distanceMeters)
            }
            guidance = laneGuidance(world, navigation, navPriority)
            if (guidance != null) {
                val type = when (guidance.action) {
                    LaneAction.CHANGE_LANE_LEFT -> DrivingEventType.CHANGE_LANE_LEFT
                    LaneAction.CHANGE_LANE_RIGHT -> DrivingEventType.CHANGE_LANE_RIGHT
                    else -> DrivingEventType.KEEP_LANE
                }
                val e = event(type, guidance.priority, guidance.text, laneSpeech(guidance, navigation), navigation.distanceMeters, lanes = guidance.lanesToMove)
                if (guidance.action == LaneAction.CHANGE_LANE_LEFT || guidance.action == LaneAction.CHANGE_LANE_RIGHT) alerts += e.copy(speech = null)
                if (gate.pass("lane", "${guidance.action}|${guidance.lanesToMove}|${guidance.priority}", now)) out += e
                lastLaneAction = guidance.action
            }
        } else {
            gate.forget("maneuver"); gate.forget("lane"); lastLaneAction = null
        }

        // --- Signs (§12) ------------------------------------------------------------------------
        val activeSigns = ArrayList<String>()
        for (s in world.signs) {
            val sign = s.sign
            if (sign.confidence < config.signMinConfidence || s.seenCount < config.signMinSeen) continue
            val cls = sign.signClass
            if (cls.equals("unknown", ignoreCase = true)) continue
            activeSigns += cls
            if (sign.speedLimit != null) continue // confirmed by [speedLimit] below
            val e = when {
                sign.isStop -> event(DrivingEventType.STOP_SIGN, Priority.TRAFFIC_ALERT, "STOP SIGN${sign.distanceMeters?.let { " | ${it.roundToInt()} m" } ?: ""}", "Stop sign ahead.", sign.distanceMeters)
                else -> event(DrivingEventType.ROAD_SIGN, Priority.GENERAL_INFORMATION, signText(cls), null, sign.distanceMeters)
            }
            if (sign.isStop) alerts += e.copy(speech = null)
            if (gate.pass("sign:$cls", cls, now)) out += e
        }
        gate.forgetIfUnseenPrefix("sign:", now, config.signForgetSeconds)

        // --- Speed limit (display only): confirmed sign, else map; kept for a while when perception is stale ---
        speedLimit.update(now, if (stale) null else world.signs, navigation, clockNs())?.let { c ->
            out += event(DrivingEventType.SPEED_LIMIT, Priority.GENERAL_INFORMATION, "SPEED LIMIT ${c.valueMph}", "Speed limit ${c.valueMph}.", c.distanceMeters)
        }

        val ctx = DrivingContext(
            seq = seq,
            ptsSeconds = now,
            following = followingInfo,
            trafficLight = lightInfo,
            pedestriansInPath = peds.map { PedestrianInfo(it.id, it.distanceMeters, it.ttcSeconds) },
            laneGuidance = guidance,
            navigation = navigation,
            speedLimit = speedLimit.valueMph,
            speedLimitSource = speedLimit.source,
            activeSigns = activeSigns.distinct(),
            activeAlerts = alerts.sortedBy { it.priority.rank },
            perceptionStale = stale,
        )
        val events = out.sortedBy { it.priority.rank }
        _context.value = ctx
        for (e in events) _events.tryEmit(e)
        return Result(ctx, events)
    }

    /** Nearest relevant light, sticky to the previously chosen track (see [DrivingContextConfig.lightSwitchMarginMeters]). */
    private fun selectLight(world: WorldSnapshot): ObjectState? {
        val lights = world.lightsAhead(aheadOnly = true)
            .filter { it.distanceMeters == null || it.distanceMeters <= config.lightAlertMaxDistanceMeters }
        val nearest = lights.firstOrNull()
        val previous = lastLightId?.let { id -> lights.firstOrNull { it.id == id } }
        val chosen = if (previous != null && nearest != null && previous.id != nearest.id) {
            val dPrev = previous.distanceMeters
            val dNew = nearest.distanceMeters
            if (dPrev != null && dNew != null && dNew < dPrev - config.lightSwitchMarginMeters) nearest else previous
        } else {
            nearest
        }
        lastLightId = chosen?.id
        return chosen
    }

    fun reset() {
        gate.clear()
        lastLightId = null
        following = FollowingState.NORMAL
        lastLaneAction = null
        speedLimit.reset()
        lastSeq = Long.MIN_VALUE
        lastRevision = Long.MIN_VALUE
    }

    /**
     * The lead's distance for the following state: its [ObjectState.distanceMeters], or, when the server's
     * [ObjectState.distanceConfidence] is below [DrivingContextConfig.leadDistanceCheckMaxConfidence], the larger of that and
     * the flat-ground distance of its box bottom ([WorldSnapshot.flatGroundDistanceMeters]). Never smaller than the
     * server's: on real_010 at 118.6-121.9 s the server said 3.3-4.9 m at confidence 0.05-0.26 (its camera height
     * 0.68-0.71 m) while the box bottom on the road was about 10 m ahead, and TOO CLOSE followed at highway speed.
     */
    private fun followingDistance(world: WorldSnapshot, lead: ObjectState): Double? {
        val d = lead.distanceMeters ?: return null
        val conf = lead.distanceConfidence ?: return d
        if (conf >= config.leadDistanceCheckMaxConfidence) return d
        val ground = world.flatGroundDistanceMeters(lead) ?: return d
        return if (ground > d) round2(ground) else d
    }

    /** Where inferred lane guidance starts: [DrivingContextConfig.inferredLaneGuidanceStartMeters], farther at speed. */
    private fun inferredStart(egoSpeedMps: Double?): Double {
        val bySpeed = (egoSpeedMps ?: 0.0).coerceAtLeast(0.0) * config.inferredLaneGuidanceLeadSeconds
        return maxOf(config.inferredLaneGuidanceStartMeters, minOf(config.inferredLaneGuidanceMaxMeters, bySpeed))
    }

    private fun laneGuidance(world: WorldSnapshot, nav: NavigationState, priority: Priority): LaneGuidance? {
        if (nav.offRoute) return null
        val start = if (nav.laneHintInferred) inferredStart(nav.egoSpeedMps) else config.laneGuidanceStartMeters
        if (nav.distanceMeters > start) return null
        if (nav.requiredLanes.isEmpty() && nav.requiredSide == null) return null
        val layout = trustedLayout(world)
        val current = layout?.egoLane
        val count = layout?.laneCount
        val targets = when {
            nav.requiredLanes.isNotEmpty() -> nav.requiredLanes.sorted().let { l -> if (count != null) l.map { it.coerceIn(1, count) }.distinct() else l }
            nav.requiredSide == LaneSide.RIGHT && count != null -> listOf(count)
            // Lane 1 only when it is ours: with line colours but no yellow left edge it may be an oncoming lane.
            nav.requiredSide == LaneSide.LEFT && layout != null && layout.leftmostLaneIsOurs() -> listOf(1)
            else -> emptyList()
        }
        val immediate = priority == Priority.IMMEDIATE_NAVIGATION
        val suffix = " | ${maneuverText(nav)}"
        val (action, move, text) = when {
            current == null || targets.isEmpty() -> {
                val use = if (targets.isNotEmpty()) "USE LANE ${targets.joinToString("-")}" else "USE ${nav.requiredSide} LANE"
                Triple(LaneAction.UNKNOWN, null, use + suffix)
            }
            current in targets -> Triple(LaneAction.KEEP_LANE, 0, "KEEP LANE$suffix")
            current < targets.first() -> {
                val n = targets.first() - current
                Triple(LaneAction.CHANGE_LANE_RIGHT, n, (if (immediate) "MOVE RIGHT" else "PREPARE TO MOVE RIGHT") + lanesSuffix(n) + suffix)
            }
            else -> {
                val n = current - targets.last()
                Triple(LaneAction.CHANGE_LANE_LEFT, n, (if (immediate) "MOVE LEFT" else "PREPARE TO MOVE LEFT") + lanesSuffix(n) + suffix)
            }
        }
        return LaneGuidance(action, current, count, targets, move, priority, text)
    }

    /**
     * The layout lanes are numbered by: [WorldSnapshot.laneLayout] when [LaneLayout.stable] (the WorldModel's quality
     * hysteresis) and at most [DrivingContextConfig.maxLayoutAgeSeconds] old. Null: the lane (and the lane count) is
     * unknown, whatever the server's own `lanes.currentLane` says (on the recorded drives: lane 1 of 1-3 throughout,
     * confidently, even with four to six lines visible).
     */
    private fun trustedLayout(world: WorldSnapshot): LaneLayout? = world.laneLayout?.takeIf { it.stableAndFresh(config.maxLayoutAgeSeconds) }

    private fun laneSpeech(g: LaneGuidance, nav: NavigationState): String? {
        val forWhat = nav.label?.let { " for $it" } ?: ""
        val lanes = g.lanesToMove?.takeIf { it > 1 }?.let { " ${numberWord(it)} lanes" } ?: ""
        val immediate = g.priority == Priority.IMMEDIATE_NAVIGATION
        return when (g.action) {
            LaneAction.CHANGE_LANE_RIGHT -> if (immediate) "Move right$lanes now$forWhat." else "Prepare to move right$lanes$forWhat."
            LaneAction.CHANGE_LANE_LEFT -> if (immediate) "Move left$lanes now$forWhat." else "Prepare to move left$lanes$forWhat."
            LaneAction.KEEP_LANE -> if (lastLaneAction == LaneAction.CHANGE_LANE_LEFT || lastLaneAction == LaneAction.CHANGE_LANE_RIGHT) "Stay in this lane$forWhat." else null
            LaneAction.UNKNOWN -> g.targetLanes.takeIf { it.isNotEmpty() }?.let { "Use lane ${it.joinToString(" or ")}$forWhat." }
        }
    }

    private fun maneuverText(nav: NavigationState): String {
        val what = when (nav.maneuver) {
            Maneuver.EXIT -> nav.label?.uppercase() ?: "EXIT"
            else -> nav.maneuver.name.replace('_', ' ') + (nav.label?.let { " | ${it.uppercase()}" } ?: "")
        }
        return "$what ${formatNavDistance(nav.distanceMeters, config.navigationUnits)}"
    }

    /** The route engine's own prompt when it gave one (phase1 audio), else a generated sentence. */
    private fun maneuverSpeech(nav: NavigationState): String {
        nav.audio?.takeIf { it.isNotBlank() }?.let { return it }
        val dist = speakNavDistance(nav.distanceMeters, config.navigationUnits)
        val onto = nav.label?.let { if (it.startsWith("Exit ")) " for $it" else " onto $it" } ?: ""
        return when (nav.maneuver) {
            Maneuver.EXIT -> "In $dist, take ${nav.label ?: "the exit"}."
            Maneuver.TURN_LEFT -> "Turn left in $dist$onto."
            Maneuver.TURN_RIGHT -> "Turn right in $dist$onto."
            Maneuver.KEEP_LEFT -> "Keep left in $dist$onto."
            Maneuver.KEEP_RIGHT -> "Keep right in $dist$onto."
            Maneuver.MERGE -> "Merge in $dist$onto."
            Maneuver.MERGE_LEFT -> "Merge left in $dist."
            Maneuver.MERGE_RIGHT -> "Merge right in $dist."
            Maneuver.ENTER_HIGHWAY -> "In $dist, enter ${nav.label ?: "the highway"}."
            Maneuver.FOLLOW_ROAD -> "Follow the road for $dist."
            Maneuver.STOP -> "Stop in $dist."
            Maneuver.ARRIVE -> "Your destination is in $dist."
        }
    }

    companion object {
        /** Pure following-state transition with hysteresis (unit-tested on its own). */
        fun nextFollowingState(
            previous: FollowingState,
            distanceMeters: Double?,
            ttcSeconds: Double?,
            headwaySeconds: Double?,
            t: FollowingThresholds,
        ): FollowingState {
            fun below(v: Double?, enter: Double, exit: Double, sticky: Boolean) = v != null && v < (if (sticky) exit else enter)
            val wasCritical = previous == FollowingState.CRITICAL
            // TOO CLOSE only with a measured distance under the cap (12 m), then by distance, TTC or headway.
            val withinCap = below(distanceMeters, t.criticalMaxMeters, t.criticalMaxExitMeters, wasCritical)
            val critical = withinCap && (below(distanceMeters, t.criticalEnterMeters, t.criticalExitMeters, wasCritical) ||
                below(ttcSeconds, t.criticalEnterTtcSeconds, t.criticalExitTtcSeconds, wasCritical) ||
                below(headwaySeconds, t.criticalEnterHeadwaySeconds, t.criticalExitHeadwaySeconds, wasCritical))
            if (critical) return FollowingState.CRITICAL
            val wasClose = previous != FollowingState.NORMAL
            val close = below(distanceMeters, t.closeEnterMeters, t.closeExitMeters, wasClose) ||
                below(ttcSeconds, t.closeEnterTtcSeconds, t.closeExitTtcSeconds, wasClose) ||
                below(headwaySeconds, t.closeEnterHeadwaySeconds, t.closeExitHeadwaySeconds, wasClose)
            return if (close) FollowingState.CLOSE else FollowingState.NORMAL
        }

        /** "0.4 mi" / "800 ft" (plan §5) or "400 m" / "1.2 km". */
        fun formatNavDistance(meters: Double, units: Units): String = when (units) {
            Units.IMPERIAL -> {
                val miles = meters / 1609.344
                if (miles >= 0.1) "${fmt1(miles)} mi" else "${(meters * 3.28084 / 50.0).roundToInt().coerceAtLeast(1) * 50} ft"
            }
            Units.METRIC -> if (meters >= 1000) "${fmt1(meters / 1000.0)} km" else "${(meters / 10.0).roundToInt() * 10} m"
        }

        fun speakNavDistance(meters: Double, units: Units): String {
            val (value, unit) = formatNavDistance(meters, units).split(' ', limit = 2)
            val word = when (unit) {
                "mi" -> if (value == "1.0") "mile" else "miles"
                "ft" -> "feet"
                "km" -> "kilometers"
                else -> "meters"
            }
            return "$value $word"
        }

        /** Wire sign class (camelCase or snake_case) -> label: "pedestrianCrossing" / "do_not_enter" -> "PEDESTRIAN CROSSING" / "DO NOT ENTER". */
        fun signText(signClass: String): String =
            signClass.replace(CAMEL_BOUNDARY, "$1 $2").replace('_', ' ').trim().uppercase()

        private val CAMEL_BOUNDARY = Regex("([a-z])([A-Z0-9])")
        private fun lanesSuffix(n: Int) = if (n > 1) " | $n LANES" else ""
        private fun numberWord(n: Int) = listOf("zero", "one", "two", "three", "four", "five", "six").getOrElse(n) { n.toString() }
        private fun fmt1(x: Double) = String.format(java.util.Locale.ROOT, "%.1f", x)
        private fun round2(x: Double) = Math.round(x * 100.0) / 100.0
    }
}

/** Emit-on-change / min-interval debouncer keyed by condition, in media time. */
internal class EventGate {
    private class Entry(var signature: String, var emittedPts: Double, var seenPts: Double)

    private val entries = HashMap<String, Entry>()

    /** True when (key, signature) is new, changed, or [repeatAfterSeconds] passed since the last emit. */
    fun pass(key: String, signature: String, now: Double, repeatAfterSeconds: Double? = null): Boolean {
        val e = entries[key]
        if (e == null) {
            entries[key] = Entry(signature, now, now); return true
        }
        e.seenPts = now
        val changed = e.signature != signature
        val repeat = repeatAfterSeconds != null && now - e.emittedPts >= repeatAfterSeconds
        if (changed || repeat) {
            e.signature = signature; e.emittedPts = now; return true
        }
        return false
    }

    fun signature(key: String): String? = entries[key]?.signature
    fun forget(key: String) { entries.remove(key) }
    fun forgetPrefix(prefix: String) { entries.keys.removeAll { it.startsWith(prefix) } }
    fun clear() = entries.clear()

    fun forgetIfUnseen(key: String, now: Double, afterSeconds: Double) {
        val e = entries[key] ?: return
        if (now - e.seenPts > afterSeconds) entries.remove(key)
    }

    fun forgetIfUnseenPrefix(prefix: String, now: Double, afterSeconds: Double) {
        entries.entries.removeAll { it.key.startsWith(prefix) && now - it.value.seenPts > afterSeconds }
    }
}
