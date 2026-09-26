package com.ksr.copilot.context

import com.ksr.copilot.perception.NavRouteState
import com.ksr.copilot.perception.NavigationPacketMessage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * phase1 `navigation.packet` -> [NavigationState] for the Driving Context (lane guidance, maneuver
 * events). No route logic here: action, distance, required lane and audio come from phase1 as they
 * are; this only translates names and parses the free-text `requiredLane`.
 */
object NavigationMapper {

    /**
     * Null when the packet has no active maneuver or no distance to it (nothing to guide towards).
     * @param inferLaneSide when phase1 gives no `requiredLane`, derive LEFT/RIGHT from the maneuver
     *   direction (turn / keep / merge side) and mark it [NavigationState.laneHintInferred].
     */
    fun toNavigationState(message: NavigationPacketMessage, inferLaneSide: Boolean = true): NavigationState? {
        val rs = message.routeState ?: return null
        return toNavigationState(rs, message.packet, inferLaneSide)
    }

    fun toNavigationState(rs: NavRouteState, packet: JsonObject? = null, inferLaneSide: Boolean = true): NavigationState? {
        if (rs.action.isBlank()) return null
        val maneuver = maneuverFor(rs.action, rs.turnDirection)
        val distance = rs.distanceMeters?.takeIf { !it.isNaN() && it >= 0.0 }
            ?: if (maneuver == Maneuver.ARRIVE) 0.0 else return null
        val (lanes, side) = parseRequiredLane(rs.requiredLane)
        val inferred = if (lanes.isEmpty() && side == null && inferLaneSide) sideOf(maneuver, rs.turnDirection) else null
        val semantics = packet.obj("routeSemantics")
        val exitNumber = semantics.str("exitNumber")
        val label = when (maneuver) {
            Maneuver.EXIT -> exitNumber?.let { "Exit $it" } ?: rs.roadName ?: semantics.str("highwayName")
            else -> rs.roadName ?: semantics.str("roadName")
        }
        return NavigationState(
            maneuver = maneuver,
            distanceMeters = distance,
            label = label,
            requiredLanes = lanes,
            requiredSide = side ?: inferred,
            egoSpeedMps = packet.obj("progress").num("speedMps"),
            audio = rs.audio.takeIf { it.isNotBlank() },
            offRoute = rs.offRoute == true,
            laneHintInferred = inferred != null,
        )
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

    private val RANGE = Regex("(\\d{1,2})\\s*[-–]\\s*(\\d{1,2})")
    private val NUMBER = Regex("\\d{1,2}")

    private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject
    private fun JsonObject?.prim(key: String): JsonPrimitive? = (this?.get(key) as? JsonElement) as? JsonPrimitive
    private fun JsonObject?.str(key: String): String? = prim(key)?.takeIf { it.isString || it.doubleOrNull != null }?.content?.takeIf { it.isNotBlank() }
    private fun JsonObject?.num(key: String): Double? = prim(key)?.doubleOrNull
}
