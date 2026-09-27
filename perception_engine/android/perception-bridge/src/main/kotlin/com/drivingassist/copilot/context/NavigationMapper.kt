package com.drivingassist.copilot.context

import com.drivingassist.copilot.perception.NavRouteState
import com.drivingassist.copilot.perception.NavSpeedLimit
import com.drivingassist.copilot.perception.NavigationPacketMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The next real maneuver of a phase1 packet ([NavigationMapper.target]): phase1's active maneuver, or the
 * first real one after it when the active one is only a GO_STRAIGHT / START_ROUTE / CONTINUE step.
 */
data class NavTarget(
    /** phase1 event type, e.g. TURN_RIGHT, KEEP_RIGHT, EXIT_HIGHWAY, ARRIVE (GO_STRAIGHT only when nothing real is ahead). */
    val action: String,
    /** Metres from the car to the target (event route position - traveled, >= 0); null = unknown. */
    val distanceMeters: Double?,
    val roadName: String?,
    val exitNumber: String?,
    /** phase1 free-text required lane of the target (see [NavigationMapper.parseRequiredLane]). */
    val requiredLane: String?,
    /** left | right | straight | merge | exit, as phase1's `routeSemantics.turnDirection`. */
    val turnDirection: String?,
    /** phase1 eventId of the target; null when the packet names none. */
    val eventId: String?,
    /** phase1's spoken prompt; null when a step was skipped (that prompt is about the skipped step). */
    val audio: String?,
    /** True = phase1's active maneuver; false = a GO_STRAIGHT / START_ROUTE / CONTINUE step before it was skipped. */
    val isActive: Boolean,
    /**
     * phase1's instruction text of the target step (e.g. "At the roundabout, take the 2nd exit onto X"); null when
     * the packet has none. A roundabout / ferry step arrives as GO_STRAIGHT, so only this text names it.
     */
    val instruction: String? = null,
)

/**
 * phase1 `navigation.packet` -> [NavigationState] for the Driving Context (lane guidance, maneuver
 * events). Distances, lanes and audio come from phase1 as they are; this translates names, parses the
 * free-text `requiredLane`, and applies one rule: a GO_STRAIGHT / START_ROUTE / CONTINUE step ("continue onto
 * X") is skipped for the next real maneuver in `upcomingManeuvers` ([target]), unless its instruction names a
 * roundabout / traffic circle / rotary / ferry (phase1 sends those as GO_STRAIGHT).
 */
object NavigationMapper {

    /** Accepted map speed limits (mph); anything else is treated as unknown. */
    val MAP_SPEED_LIMIT_MPH = 5..85

    /** Route positions this close behind the traveled distance still count as ahead (phase1 rounds progress to 1 m). */
    private const val AHEAD_TOLERANCE_METERS = 1.0

    /**
     * Null when the packet has no active maneuver or no distance to it (nothing to guide towards).
     * @param inferLaneSide when phase1 gives no `requiredLane`, derive LEFT/RIGHT from the maneuver
     *   direction (turn / keep / merge side) and mark it [NavigationState.laneHintInferred].
     * @param egoSpeed the packet stream's [EgoSpeedEstimator]: every packet is fed to it (also one that maps to null) and
     *   [NavigationState.egoSpeedMps] is its speed (min of the traveled-distance speed and `progress.speedMps`); null =
     *   `progress.speedMps` as it is.
     */
    fun toNavigationState(message: NavigationPacketMessage, inferLaneSide: Boolean = true, egoSpeed: EgoSpeedEstimator? = null): NavigationState? {
        val speed = egoSpeed?.update(message)
        val rs = message.routeState ?: return null
        val state = toNavigationState(rs, message.packet, inferLaneSide, message.speedLimit) ?: return null
        return if (egoSpeed == null) state else state.copy(egoSpeedMps = speed)
    }

    fun toNavigationState(
        rs: NavRouteState,
        packet: JsonObject? = null,
        inferLaneSide: Boolean = true,
        speedLimit: NavSpeedLimit? = null,
    ): NavigationState? {
        if (rs.action.isBlank()) return null
        val t = target(rs, packet)
        val maneuver = maneuverFor(t.action, t.turnDirection)
        val distance = t.distanceMeters ?: if (maneuver == Maneuver.ARRIVE) 0.0 else return null
        val (lanes, side) = parseRequiredLane(t.requiredLane)
        val inferred = if (lanes.isEmpty() && side == null && inferLaneSide) sideOf(maneuver, t.turnDirection) else null
        val mapLimit = speedLimit?.valueMph?.takeIf { it in MAP_SPEED_LIMIT_MPH }
        return NavigationState(
            maneuver = maneuver,
            distanceMeters = distance,
            label = labelFor(maneuver, t, packet),
            requiredLanes = lanes,
            requiredSide = side ?: inferred,
            egoSpeedMps = packet.obj("progress").num("speedMps"),
            audio = t.audio?.takeIf { it.isNotBlank() },
            offRoute = rs.offRoute == true,
            laneHintInferred = inferred != null,
            mapSpeedLimitMph = mapLimit,
            mapSpeedLimitRoad = speedLimit?.roadName?.takeIf { mapLimit != null && it.isNotBlank() },
            eventId = t.eventId,
        )
    }

    fun target(message: NavigationPacketMessage): NavTarget? = message.routeState?.let { target(it, message.packet) }

    /**
     * The next real maneuver: phase1's active one ([rs] as it is) unless it is a skippable straight step
     * ([skippable]), else the first event at or ahead of the traveled distance in `upcomingManeuvers` (sorted by
     * route position here) that is not skippable. No such event ahead, no list or no traveled distance: the active one.
     */
    fun target(rs: NavRouteState, packet: JsonObject?): NavTarget {
        val semantics = packet.obj("routeSemantics")
        val activeManeuver = packet.obj("activeManeuver")
        val active = NavTarget(
            action = rs.action,
            distanceMeters = rs.distanceMeters?.takeIf { !it.isNaN() && it >= 0.0 },
            roadName = rs.roadName?.takeIf { it.isNotBlank() } ?: semantics.str("roadName"),
            exitNumber = semantics.str("exitNumber"),
            requiredLane = rs.requiredLane,
            turnDirection = rs.turnDirection,
            eventId = activeManeuver.str("eventId"),
            audio = rs.audio,
            isActive = true,
            instruction = activeManeuver.str("instruction"),
        )
        if (!skippable(rs.action, active.instruction)) return active
        val events = packet?.get("upcomingManeuvers") as? JsonArray ?: return active
        val traveled = packet.obj("progress").num("distanceTraveledMeters")
            ?: activeManeuver.num("distanceMeters")?.let { a -> active.distanceMeters?.let { a - it } }
            ?: return active
        val (e, at) = events.asSequence()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { ev -> ev.num("distanceMeters")?.let { ev to it } }
            .filter { (_, at) -> at >= traveled - AHEAD_TOLERANCE_METERS }
            .sortedBy { (_, at) -> at }
            .firstOrNull { (ev, _) -> !skippable(ev.str("type"), ev.str("instruction")) }
            ?: return active
        val type = e.str("type")!!
        return NavTarget(
            action = type,
            distanceMeters = (at - traveled).coerceAtLeast(0.0),
            roadName = e.str("roadName"),
            exitNumber = e.str("exitNumber"),
            requiredLane = e.str("requiredLane"),
            turnDirection = turnDirectionOf(type),
            eventId = e.str("eventId") ?: "$type@${at.toLong()}",
            audio = null,
            isActive = false,
            instruction = e.str("instruction"),
        )
    }

    /**
     * A "continue" step that [target] may look past: GO_STRAIGHT / START_ROUTE / CONTINUE whose instruction does not
     * name a roundabout / traffic circle / rotary / ferry. Any other type (also an unknown one) is a target.
     */
    private fun skippable(type: String?, instruction: String?): Boolean =
        type?.trim()?.uppercase() in STRAIGHT_TYPES && instruction?.let { HIDDEN_MANEUVER.containsMatchIn(it) } != true

    /** "Exit N" for an exit (EXIT, or a KEEP_* ramp with an exit number), else the road name. */
    private fun labelFor(maneuver: Maneuver, t: NavTarget, packet: JsonObject?): String? {
        val exit = t.exitNumber?.let { "Exit $it" }
        return when (maneuver) {
            Maneuver.EXIT -> exit ?: t.roadName ?: packet.obj("routeSemantics").str("highwayName")
            Maneuver.KEEP_LEFT, Maneuver.KEEP_RIGHT -> exit ?: t.roadName
            else -> t.roadName
        }
    }

    /** phase1's `semanticsTurnDirection` for an event type. */
    private fun turnDirectionOf(type: String): String = when (type.trim().uppercase()) {
        "TURN_LEFT", "KEEP_LEFT", "MERGE_LEFT" -> "left"
        "TURN_RIGHT", "KEEP_RIGHT", "MERGE_RIGHT" -> "right"
        "MERGE" -> "merge"
        "EXIT_HIGHWAY", "EXIT" -> "exit"
        else -> "straight"
    }

    /** phase1 `activeManeuver.type` (and the AR mock's names) -> [Maneuver]; unknown -> FOLLOW_ROAD. */
    fun maneuverFor(action: String, turnDirection: String? = null): Maneuver {
        val a = action.trim().uppercase()
        val dir = turnDirection?.trim()?.lowercase()
        return when (a) {
            "TURN_LEFT" -> Maneuver.TURN_LEFT
            "TURN_RIGHT" -> Maneuver.TURN_RIGHT
            "KEEP_LEFT" -> Maneuver.KEEP_LEFT
            "KEEP_RIGHT" -> Maneuver.KEEP_RIGHT
            "MERGE" -> when (dir) { "left" -> Maneuver.MERGE_LEFT; "right" -> Maneuver.MERGE_RIGHT; else -> Maneuver.MERGE }
            "MERGE_LEFT" -> Maneuver.MERGE_LEFT
            "MERGE_RIGHT" -> Maneuver.MERGE_RIGHT
            "EXIT_HIGHWAY", "EXIT" -> Maneuver.EXIT
            "ENTER_HIGHWAY" -> Maneuver.ENTER_HIGHWAY
            "ARRIVE" -> Maneuver.ARRIVE
            "STOP" -> Maneuver.STOP
            else -> Maneuver.FOLLOW_ROAD // GO_STRAIGHT, START_ROUTE, CONTINUE, anything new
        }
    }

    /**
     * Free-text required lane -> (lanes 1-based from the left, side). "right" / "rightmost" -> RIGHT,
     * "left" -> LEFT, "2" -> [2], "2-3" -> [2, 3], "2,3" -> [2, 3]. A side word wins over numbers
     * ("left 2 lanes" is a side, not lane 2).
     */
    fun parseRequiredLane(raw: String?): Pair<List<Int>, LaneSide?> {
        val s = raw?.trim()?.lowercase().orEmpty()
        if (s.isEmpty() || s == "null" || s == "none") return emptyList<Int>() to null
        if ("right" in s) return emptyList<Int>() to LaneSide.RIGHT
        if ("left" in s) return emptyList<Int>() to LaneSide.LEFT
        RANGE.find(s)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            if (a in 1..12 && b in a..12) return (a..b).toList() to null
        }
        val numbers = NUMBER.findAll(s).mapNotNull { it.value.toIntOrNull() }.filter { it in 1..12 }.distinct().sorted().toList()
        return numbers to null
    }

    private fun sideOf(m: Maneuver, turnDirection: String?): LaneSide? = when (m) {
        Maneuver.TURN_LEFT, Maneuver.KEEP_LEFT, Maneuver.MERGE_LEFT -> LaneSide.LEFT
        Maneuver.TURN_RIGHT, Maneuver.KEEP_RIGHT, Maneuver.MERGE_RIGHT -> LaneSide.RIGHT
        Maneuver.EXIT -> when (turnDirection?.lowercase()) { "left" -> LaneSide.LEFT; "right" -> LaneSide.RIGHT; else -> null }
        else -> null
    }

    private val STRAIGHT_TYPES = setOf("GO_STRAIGHT", "START_ROUTE", "CONTINUE")
    private val HIDDEN_MANEUVER = Regex("roundabout|traffic circle|rotary|ferry", RegexOption.IGNORE_CASE)
    private val RANGE = Regex("(\\d{1,2})\\s*[-–]\\s*(\\d{1,2})")
    private val NUMBER = Regex("\\d{1,2}")

    private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject
    private fun JsonObject?.prim(key: String): JsonPrimitive? = (this?.get(key) as? JsonElement) as? JsonPrimitive
    private fun JsonObject?.str(key: String): String? = prim(key)?.takeIf { it.isString || it.doubleOrNull != null }?.content?.takeIf { it.isNotBlank() }
    private fun JsonObject?.num(key: String): Double? = prim(key)?.doubleOrNull
}
