package com.drivingassist.spatialcopilot.ar

import com.drivingassist.copilot.context.LaneAction
import com.drivingassist.copilot.context.LaneGuidance
import com.drivingassist.copilot.context.Maneuver
import com.drivingassist.spatialcopilot.nav.RouteGuide
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** What the road arrows say. Direction words are only used when phase1 gave the side. */
enum class ArrowKind { FOLLOW, TURN_LEFT, TURN_RIGHT, BEAR_LEFT, BEAR_RIGHT, LANE_LEFT, LANE_RIGHT, ARRIVE }

/**
 * @param maneuverAheadMeters where the maneuver is, metres ahead (null = not known / not needed).
 * @param lanes lanes to move for LANE_*.
 */
data class ArrowIntent(val kind: ArrowKind, val maneuverAheadMeters: Double?, val lanes: Int = 1)

/**
 * Chooses the road arrows from phase1's route state (via [RouteGuide]) and the Driving Context's lane
 * guidance, and lays their path out on the road plane relative to the ego lane. No route logic: which
 * maneuver comes next and how far away it is are phase1's; which lane the car is in is perception's.
 */
object RouteArrows {
    /** The maneuver point itself (the bend) is drawn inside this range; farther away the arrows lead up to it. */
    const val BEND_VISIBLE_M = 45.0

    /** Chevrons start leading towards a maneuver this far before it. Beyond that the road stays clear. */
    const val APPROACH_M = 150.0

    /** Farthest chevron of a straight run. */
    const val RUN_M = 32.0

    fun intent(route: RouteGuide?, lane: LaneGuidance?, distanceNow: Double?): ArrowIntent? {
        if (route == null || route.stale || route.offRoute) return null
        val d = distanceNow
        // The Driving Context says the car is not in a lane for the maneuver: show the lane change, unless the
        // maneuver itself is already close enough to draw.
        if (d == null || d > BEND_VISIBLE_M) {
            val moves = lane?.lanesToMove?.coerceIn(1, 2) ?: 1
            when (lane?.action) {
                LaneAction.CHANGE_LANE_LEFT -> return ArrowIntent(ArrowKind.LANE_LEFT, d, moves)
                LaneAction.CHANGE_LANE_RIGHT -> return ArrowIntent(ArrowKind.LANE_RIGHT, d, moves)
                else -> Unit
            }
        }
        if (d == null || d > APPROACH_M) return null
        val side = route.turnDirection?.lowercase()
        val kind = when (route.maneuver) {
            Maneuver.TURN_LEFT -> ArrowKind.TURN_LEFT
            Maneuver.TURN_RIGHT -> ArrowKind.TURN_RIGHT
            Maneuver.KEEP_LEFT, Maneuver.MERGE_LEFT -> ArrowKind.BEAR_LEFT
            Maneuver.KEEP_RIGHT, Maneuver.MERGE_RIGHT -> ArrowKind.BEAR_RIGHT
            // phase1 usually sends turnDirection "exit" / "merge": the side is unknown, so no bend is drawn.
            Maneuver.EXIT -> when (side) { "left" -> ArrowKind.BEAR_LEFT; "right" -> ArrowKind.BEAR_RIGHT; else -> ArrowKind.FOLLOW }
            Maneuver.MERGE, Maneuver.ENTER_HIGHWAY, Maneuver.FOLLOW_ROAD -> ArrowKind.FOLLOW
            Maneuver.ARRIVE -> ArrowKind.ARRIVE
            Maneuver.STOP -> return null
        }
        if (kind == ArrowKind.FOLLOW && route.maneuver == Maneuver.FOLLOW_ROAD) return null // nothing coming up
        return ArrowIntent(kind, d)
    }

    /**
     * The arrow path on the road, as ground points every ~0.5 m from [zStart]: along the ego lane centre,
     * then bending for the maneuver. Lateral offsets are relative to the lane centre at the same distance.
     */
    fun path(intent: ArrowIntent, lane: EgoLane, zStart: Double): List<Ground> {
        val out = ArrayList<Ground>(96)
        fun along(z0: Double, z1: Double, offset: (Double) -> Double) {
            var z = z0
            while (z <= z1 + 1e-6) {
                out += Ground(lane.x(z) + offset(z), z)
                z += STEP_M
            }
        }
        val d = intent.maneuverAheadMeters
        when (intent.kind) {
            ArrowKind.FOLLOW, ArrowKind.ARRIVE -> {
                if (zStart + 4.0 > RUN_M + 8.0) return emptyList()
                val end = (d ?: RUN_M).coerceIn(zStart + 4.0, RUN_M + 8.0)
                along(zStart, end) { 0.0 }
            }
            ArrowKind.TURN_LEFT, ArrowKind.TURN_RIGHT -> {
                if (zStart + 4.0 > RUN_M + 8.0) return emptyList()
                val sign = if (intent.kind == ArrowKind.TURN_RIGHT) 1.0 else -1.0
                if (d == null || d > BEND_VISIBLE_M) {
                    along(zStart, RUN_M) { 0.0 }
                } else {
                    val r = TURN_RADIUS_M
                    val zTurn = d.coerceAtLeast(zStart + r + 2.0)
                    along(zStart, zTurn - r) { 0.0 }
                    val zc = zTurn - r
                    val xc = lane.x(zc)
                    val n = 12
                    for (i in 1..n) {
                        val phi = (PI / 2) * i / n
                        out += Ground(xc + sign * (r - r * cos(phi)), zc + r * sin(phi))
                    }
                    var t = STEP_M
                    while (t <= TURN_EXIT_M) {
                        out += Ground(xc + sign * (r + t), zTurn)
                        t += STEP_M
                    }
                }
            }
            ArrowKind.BEAR_LEFT, ArrowKind.BEAR_RIGHT -> {
                val sign = if (intent.kind == ArrowKind.BEAR_RIGHT) 1.0 else -1.0
                val shift = sign * lane.widthMeters
                if (zStart + 2.0 > RUN_M) return emptyList()
                val start = ((d ?: RUN_M) - 6.0).coerceIn(zStart + 2.0, RUN_M)
                along(zStart, start + SHIFT_M + 8.0) { z -> shift * ease((z - start) / SHIFT_M) }
            }
            ArrowKind.LANE_LEFT, ArrowKind.LANE_RIGHT -> {
                val sign = if (intent.kind == ArrowKind.LANE_RIGHT) 1.0 else -1.0
                val shift = sign * lane.widthMeters * intent.lanes
                if (zStart + 2.0 > RUN_M) return emptyList()
                val start = zStart + 2.0
                along(zStart, start + SHIFT_M + 10.0) { z -> shift * ease((z - start) / SHIFT_M) }
            }
        }
        return out
    }

    /** Arc length of a ground path up to each point. */
    fun arcLengths(path: List<Ground>): DoubleArray {
        val s = DoubleArray(path.size)
        for (i in 1 until path.size) s[i] = s[i - 1] + hypot(path[i].x - path[i - 1].x, path[i].z - path[i - 1].z)
        return s
    }

    /** Smooth 0 -> 1 over t in 0..1 (clamped). */
    private fun ease(t: Double): Double {
        val c = t.coerceIn(0.0, 1.0)
        return (1 - cos(PI * c)) / 2
    }

    private const val STEP_M = 0.5
    private const val TURN_RADIUS_M = 6.0
    private const val TURN_EXIT_M = 8.0
    private const val SHIFT_M = 16.0
}
