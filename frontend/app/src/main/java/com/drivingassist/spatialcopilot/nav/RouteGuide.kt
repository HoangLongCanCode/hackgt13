package com.drivingassist.spatialcopilot.nav

import com.drivingassist.copilot.bridge.NavigationUpdate
import com.drivingassist.copilot.context.Maneuver
import com.drivingassist.copilot.context.NavigationMapper
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What the tablet shows of the phase1 route: read off the newest `navigation.packet`. phase1 decides maneuvers,
 * distances and progress; the maneuver fields describe the next real maneuver ([NavigationMapper.target]:
 * phase1's active one, or the first real one after a GO_STRAIGHT / START_ROUTE step). Otherwise this only picks
 * fields and formats them.
 */
data class RouteGuide(
    val maneuver: Maneuver,
    /** phase1 type of the target maneuver, e.g. TURN_RIGHT, KEEP_RIGHT; GO_STRAIGHT only when nothing real is ahead. */
    val action: String,
    /** left | right | straight | merge | exit (phase1's `routeSemantics.turnDirection` for the target). */
    val turnDirection: String?,
    /** Metres from the car to the target maneuver when the packet was built. */
    val distanceMeters: Double?,
    /** phase1 `spatialInstructions[0].type`: TURN_ARROW, LANE_ARROW, EXIT_MARKER, WARNING, DISTANCE_LABEL. */
    val spatialType: String?,
    /** Metres ahead of the car where the spatial instruction is anchored (anchor route distance - traveled). */
    val anchorAheadMeters: Double?,
    val roadName: String?,
    val exitNumber: String?,
    val destination: String?,
    val etaSeconds: Double?,
    val remainingMeters: Double?,
    val offRoute: Boolean,
    /** phase1's ego speed estimate (`progress.speedMps`), used to move the countdown between packets. */
    val speedMps: Double?,
    val provider: String?,
    /** No packet for a while (relay stopped, GPS lost, link down): keep it on screen, marked. */
    val stale: Boolean,
    /** Sim: media time the packet was computed for. */
    val ptsSeconds: Double?,
    val receivedAtNs: Long,
    /** Identifies the route (phase1 routeId / tripId): voice prompts reset on a new one. */
    val routeKey: String = "",
    /** Identifies the target maneuver of the route ("<routeKey>/<phase1 eventId>"): each prompt stage once per key. */
    val eventKey: String = "",
    /** phase1 `route.polyline` (Google's overview polyline, or the mock provider's): the mini-map's route line. */
    val polyline: String? = null,
    /** phase1 `progress.currentLocation`: the car's position the packet was computed for. */
    val carLocation: LatLng? = null,
    /** phase1 `progress.heading` (degrees from north; 0 also means unknown). */
    val headingDegrees: Double? = null,
) {
    val isArrival: Boolean get() = maneuver == Maneuver.ARRIVE

    /** An exit: phase1's EXIT, or a Google ramp (KEEP_LEFT / KEEP_RIGHT) whose instruction names an exit number. */
    val isExit: Boolean
        get() = maneuver == Maneuver.EXIT || (exitNumber != null && (maneuver == Maneuver.KEEP_LEFT || maneuver == Maneuver.KEEP_RIGHT))

    /** e.g. "TURN RIGHT", "EXIT 56", "KEEP LEFT", "CONTINUE". */
    val headline: String
        get() = when (maneuver) {
            Maneuver.TURN_LEFT -> "TURN LEFT"
            Maneuver.TURN_RIGHT -> "TURN RIGHT"
            Maneuver.KEEP_LEFT -> "KEEP LEFT"
            Maneuver.KEEP_RIGHT -> "KEEP RIGHT"
            Maneuver.MERGE, Maneuver.MERGE_LEFT, Maneuver.MERGE_RIGHT -> "MERGE"
            Maneuver.EXIT -> exitNumber?.let { "EXIT $it" } ?: "EXIT"
            Maneuver.ENTER_HIGHWAY -> "ENTER HIGHWAY"
            Maneuver.ARRIVE -> "ARRIVE"
            Maneuver.STOP -> "STOP AHEAD"
            Maneuver.FOLLOW_ROAD -> "CONTINUE"
        }

    /**
     * Distance to the maneuver at [nowNs] (bridge clock) or, in sim, at media time [ptsNow]: the packet's
     * distance moved on by phase1's own speed since the packet (never below 0, at most 2 s of travel).
     */
    fun distanceAt(nowNs: Long, ptsNow: Double? = null): Double? {
        val d = distanceMeters ?: return null
        val v = speedMps?.takeIf { it > 0.3 } ?: return d
        return (d - v * ageSeconds(nowNs, ptsNow).coerceIn(0.0, MAX_EXTRAPOLATION_S)).coerceAtLeast(0.0)
    }

    /** Seconds since the packet at [nowNs] (bridge clock) or, in sim, at media time [ptsNow]: the clock [distanceAt] uses. */
    fun ageSeconds(nowNs: Long, ptsNow: Double? = null): Double =
        if (ptsNow != null && ptsSeconds != null) ptsNow - ptsSeconds else (nowNs - receivedAtNs) / 1e9

    /** Older than the extrapolation cap: [distanceAt] no longer moves, so the distance is not known any more. */
    fun heldAt(nowNs: Long, ptsNow: Double? = null): Boolean = ageSeconds(nowNs, ptsNow) > MAX_EXTRAPOLATION_S

    companion object {
        const val MAX_EXTRAPOLATION_S = 2.0

        fun from(update: NavigationUpdate?): RouteGuide? {
            val u = update ?: return null
            val rs = u.routeState ?: return null
            val packet = u.packet
            val progress = packet.obj("progress")
            val traveled = progress.num("distanceTraveledMeters")
            val spatial = (packet?.get("spatialInstructions") as? JsonArray)?.firstOrNull() as? JsonObject
            val anchorRoute = spatial.obj("anchor").num("routeDistanceMeters")
            val routeKey = packet.str("routeId") ?: packet.str("tripId") ?: "route"
            val target = NavigationMapper.target(rs, packet)
            val eventId = target.eventId ?: "${rs.action}@${anchorRoute ?: "?"}"
            return RouteGuide(
                maneuver = NavigationMapper.maneuverFor(target.action, target.turnDirection),
                action = target.action,
                turnDirection = target.turnDirection,
                distanceMeters = target.distanceMeters,
                spatialType = spatial.str("type"),
                anchorAheadMeters = if (anchorRoute != null && traveled != null) (anchorRoute - traveled).coerceAtLeast(0.0) else rs.distanceMeters,
                roadName = target.roadName,
                exitNumber = target.exitNumber,
                destination = packet.obj("destination").str("label"),
                etaSeconds = rs.etaSeconds,
                remainingMeters = rs.remainingDistanceMeters,
                offRoute = rs.offRoute == true || progress.bool("offRoute") == true,
                speedMps = progress.num("speedMps"),
                provider = packet.obj("source").str("provider"),
                stale = u.stale,
                ptsSeconds = u.ptsSeconds,
                receivedAtNs = u.receivedAtNs,
                routeKey = routeKey,
                eventKey = "$routeKey/$eventId",
                polyline = packet.obj("route").str("polyline"),
                carLocation = progress.obj("currentLocation").let { c ->
                    val lat = c.num("lat")
                    val lng = c.num("lng")
                    if (lat != null && lng != null) LatLng(lat, lng) else null
                },
                headingDegrees = progress.num("heading"),
            )
        }

        /** Navigation distances in metres below 1 km, else km (phase1 prompts use metres too). */
        fun formatDistance(meters: Double): String = when {
            meters < 20.0 -> "${meters.roundToInt()} m"
            meters < 1000.0 -> "${(meters / 5.0).roundToInt() * 5} m"
            else -> String.format(Locale.US, "%.1f km", meters / 1000.0)
        }

        fun formatEta(seconds: Double): String {
            val s = seconds.roundToInt().coerceAtLeast(0)
            return if (s < 60) "$s s" else "${(s + 30) / 60} min"
        }

        private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject
        private fun JsonObject?.prim(key: String): JsonPrimitive? = (this?.get(key) as? JsonElement) as? JsonPrimitive
        private fun JsonObject?.str(key: String): String? =
            prim(key)?.takeIf { it.isString || it.doubleOrNull != null }?.content?.takeIf { it.isNotBlank() && it != "null" }
        private fun JsonObject?.num(key: String): Double? = prim(key)?.doubleOrNull
        private fun JsonObject?.bool(key: String): Boolean? = prim(key)?.booleanOrNull
    }
}
