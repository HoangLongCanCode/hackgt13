package com.drivingassist.spatialcopilot.nav

import com.drivingassist.copilot.context.Maneuver
import java.util.Locale
import kotlin.math.roundToInt

/** The small symbol left of the instruction; ui/Hud.kt draws each one with Canvas paths. */
enum class NavGlyph { STRAIGHT, TURN_LEFT, TURN_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, EXIT_LEFT, EXIT_RIGHT, MERGE, STOP, ARRIVE, OFF_ROUTE }

/** The top instruction banner: [primary] line, optional smaller [secondary] line, [dim] = updates paused or off route. */
data class Instruction(val primary: String, val secondary: String?, val glyph: NavGlyph, val dim: Boolean)

/**
 * Instruction text for the clean view's top banner, from the route's next real maneuver. No route logic:
 * the maneuver and its distance are phase1's. Distances are US customary like the voice ([distance]).
 * - more than a mile away: "Drive straight", the maneuver in the second line ("Exit 94 in 2.4 mi");
 * - within a mile: "Turn left in 900 ft", "onto <road>" under it for turns, keeps, merges and ramps;
 * - under 15 m: "Turn left now";
 * - no distance: the maneuver name alone (a distance that is not known is not shown);
 * - no packet for longer than [RouteGuide.MAX_EXTRAPOLATION_S] (the distance stops moving) or stale: the maneuver
 *   name alone, dimmed, "Route updates paused" under it.
 */
object NavText {
    const val METERS_PER_MILE = 1609.344
    private const val FEET_PER_METER = 3.28084

    /** SpokenText.distance says feet up to "one thousand feet" (rounded to 100 ft): below 1050 ft. */
    private const val FEET_LIMIT = 1050.0

    /** Below this the maneuver is "now" (and the destination reached). */
    const val NOW_METERS = 15.0

    /** Longer road names are left out (the banner has one short line for them). */
    const val MAX_ROAD_NAME = 40

    const val PAUSED = "Route updates paused"
    const val DEMO = "DEMO ROUTE"

    /** [nowNs] (bridge clock) and, in sim, media time [ptsNow]: the clock [RouteGuide.distanceAt] was given. */
    fun instruction(route: RouteGuide, distanceNow: Double?, nowNs: Long, ptsNow: Double? = null): Instruction {
        val paused = route.stale || route.heldAt(nowNs, ptsNow)
        val d = distanceNow?.takeIf { !paused && !it.isNaN() && it >= 0.0 }
        val base = when {
            route.offRoute -> Instruction("Off route", null, NavGlyph.OFF_ROUTE, dim = true)
            route.maneuver == Maneuver.FOLLOW_ROAD -> Instruction("Drive straight", road(route), NavGlyph.STRAIGHT, dim = false)
            route.maneuver == Maneuver.ARRIVE -> arrival(d)
            else -> maneuver(route, d)
        }
        val secondary = when {
            paused -> PAUSED
            route.provider == "demo" -> base.secondary?.let { "$it · $DEMO" } ?: DEMO
            else -> base.secondary
        }
        return base.copy(secondary = secondary, dim = paused || route.offRoute)
    }

    /** "Turn left", "Keep right", "Merge left", "Take the ramp", "Exit 94" / "Exit", "Destination", "Stop ahead". */
    fun name(route: RouteGuide): String = when {
        route.isExit -> route.exitNumber?.let { "Exit $it" } ?: "Exit"
        else -> when (route.maneuver) {
            Maneuver.TURN_LEFT -> "Turn left"
            Maneuver.TURN_RIGHT -> "Turn right"
            Maneuver.KEEP_LEFT -> "Keep left"
            Maneuver.KEEP_RIGHT -> "Keep right"
            Maneuver.MERGE -> "Merge"
            Maneuver.MERGE_LEFT -> "Merge left"
            Maneuver.MERGE_RIGHT -> "Merge right"
            Maneuver.ENTER_HIGHWAY -> "Take the ramp"
            Maneuver.EXIT -> "Exit"
            Maneuver.ARRIVE -> "Destination"
            Maneuver.STOP -> "Stop ahead"
            Maneuver.FOLLOW_ROAD -> "Drive straight"
        }
    }

    fun glyph(route: RouteGuide): NavGlyph {
        val left = route.turnDirection?.lowercase()?.contains("left") == true
        val slight = route.turnDirection?.lowercase()?.contains("slight") == true
        if (route.isExit) return if (route.maneuver == Maneuver.KEEP_LEFT || left) NavGlyph.EXIT_LEFT else NavGlyph.EXIT_RIGHT
        return when (route.maneuver) {
            Maneuver.TURN_LEFT -> if (slight) NavGlyph.SLIGHT_LEFT else NavGlyph.TURN_LEFT
            Maneuver.TURN_RIGHT -> if (slight) NavGlyph.SLIGHT_RIGHT else NavGlyph.TURN_RIGHT
            Maneuver.KEEP_LEFT -> NavGlyph.SLIGHT_LEFT
            Maneuver.KEEP_RIGHT -> NavGlyph.SLIGHT_RIGHT
            Maneuver.MERGE, Maneuver.MERGE_LEFT, Maneuver.MERGE_RIGHT -> NavGlyph.MERGE
            Maneuver.ENTER_HIGHWAY, Maneuver.EXIT -> if (left) NavGlyph.EXIT_LEFT else NavGlyph.EXIT_RIGHT
            Maneuver.STOP -> NavGlyph.STOP
            Maneuver.ARRIVE -> NavGlyph.ARRIVE
            Maneuver.FOLLOW_ROAD -> NavGlyph.STRAIGHT
        }
    }

    /**
     * US customary, matching the voice: feet while the voice speaks feet (under 1050 ft, about 0.2 mi), rounded to
     * 50 ft (50-1000 ft, "900 ft"); then one decimal under 10 mi ("1.2 mi"), else whole miles ("12 mi").
     */
    fun distance(meters: Double): String {
        val m = meters.coerceAtLeast(0.0)
        val feet = m * FEET_PER_METER
        if (feet < FEET_LIMIT) return "${((feet / 50.0).roundToInt() * 50).coerceIn(50, 1000)} ft"
        val miles = m / METERS_PER_MILE
        val tenths = (miles * 10.0).roundToInt()
        return if (tenths < 100) String.format(Locale.US, "%.1f mi", tenths / 10.0) else "${miles.roundToInt()} mi"
    }

    private fun maneuver(route: RouteGuide, d: Double?): Instruction {
        val name = name(route)
        val glyph = glyph(route)
        val onto = if (takesRoadName(route)) road(route)?.let { "onto $it" } else null
        return when {
            d == null -> Instruction(name, onto, glyph, dim = false)
            d > METERS_PER_MILE -> Instruction("Drive straight", "$name in ${distance(d)}", NavGlyph.STRAIGHT, dim = false)
            // "Stop ahead now" is not a sentence; the stop stays "ahead" until phase1 moves on.
            d < NOW_METERS -> Instruction(if (route.maneuver == Maneuver.STOP) name else "$name now", onto, glyph, dim = false)
            else -> Instruction("$name in ${distance(d)}", onto, glyph, dim = false)
        }
    }

    private fun arrival(d: Double?): Instruction = when {
        d == null -> Instruction("Destination", null, NavGlyph.ARRIVE, dim = false)
        d <= NOW_METERS -> Instruction("You have arrived", null, NavGlyph.ARRIVE, dim = false)
        d > METERS_PER_MILE -> Instruction("Drive straight", "Destination in ${distance(d)}", NavGlyph.STRAIGHT, dim = false)
        else -> Instruction("Destination in ${distance(d)}", null, NavGlyph.ARRIVE, dim = false)
    }

    /** Turns, keeps, merges and ramps say which road they lead onto; exits and stops do not. */
    private fun takesRoadName(route: RouteGuide): Boolean = !route.isExit && when (route.maneuver) {
        Maneuver.TURN_LEFT, Maneuver.TURN_RIGHT, Maneuver.KEEP_LEFT, Maneuver.KEEP_RIGHT,
        Maneuver.MERGE, Maneuver.MERGE_LEFT, Maneuver.MERGE_RIGHT, Maneuver.ENTER_HIGHWAY -> true
        else -> false
    }

    private fun road(route: RouteGuide): String? = route.roadName?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_ROAD_NAME }
}
