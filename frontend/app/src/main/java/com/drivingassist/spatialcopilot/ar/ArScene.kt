package com.drivingassist.spatialcopilot.ar

import com.drivingassist.copilot.context.DrivingContext
import com.drivingassist.copilot.context.FollowingState
import com.drivingassist.copilot.context.WorldSnapshot
import com.drivingassist.spatialcopilot.nav.RouteGuide
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One chevron on the road, in view pixels: two wings and the tip. */
data class Chevron(val left: Vec2, val tip: Vec2, val right: Vec2, val alpha: Float, val strokePx: Float)

/**
 * The lead vehicle, highlighted only while the Driving Context says CRITICAL (debug: also CLOSE) and a
 * distance exists. [badge]: draw [label] above it (debug; in the clean view the HUD shows the distance).
 */
data class LeadHighlight(val rect: ViewRect, val label: String, val critical: Boolean, val badge: Boolean = true)

/** Debug-only overlays (every box, lane lines, the fitted ego lane, anchors, horizon). */
data class DebugLayer(
    val boxes: List<Pair<ViewRect, String>>,
    val laneLines: List<List<Vec2>>,
    val egoLane: List<Vec2>,
    val egoLaneSource: EgoLane.Source?,
    val anchors: List<Vec2>,
    val horizonY: Float?,
    /** Lane arrow state, e.g. "lanes 2/3 conf 0.82 targets 3 WRONG" / "lanes unknown: conf 0.21". */
    val laneStatus: String? = null,
)

/**
 * Everything the AR canvas draws for one display frame, already in view pixels. Clean view: [laneArrows],
 * [pin] and the TOO CLOSE [lead]. Chevrons, ribbon, the CLOSE highlight and [debug] are debug-only.
 */
data class ArScene(
    /** Painted-style arrows lying flat in the lanes. */
    val laneArrows: List<LaneArrow> = emptyList(),
    /** View rects of road users over the lane arrows (inflated): clipped out of the arrow fills. */
    val occluders: List<ViewRect> = emptyList(),
    val chevrons: List<Chevron> = emptyList(),
    /** Translucent strip under the chevrons (closed polygon), with its alpha. */
    val ribbon: List<Vec2> = emptyList(),
    val ribbonAlpha: Float = 0f,
    /** Destination pin on the road (ARRIVE within range). */
    val pin: Vec2? = null,
    val arrowKind: ArrowKind? = null,
    val lead: LeadHighlight? = null,
    val debug: DebugLayer? = null,
) {
    companion object {
        val EMPTY = ArScene()
    }
}

/** Per-frame inputs. [world] is already moved to display time (`predictedAt`) in LIVE. */
data class ArInput(
    val world: WorldSnapshot,
    val context: DrivingContext,
    val route: RouteGuide?,
    /** Distance to the maneuver at display time ([RouteGuide.distanceAt]). */
    val routeDistanceMeters: Double?,
    val debug: Boolean,
)

/**
 * Builds [ArScene]s at display rate. Stateful only for presentation: it low-pass filters the ego lane
 * between perception updates (snapping when the car crosses into the next lane), scrolls the chevrons,
 * fades arrows in and out (0.35 s) when they become relevant or stop being relevant, and holds the lane
 * arrows' state ([LaneArrowsBuilder]). Pure Kotlin (no Android types), so it is unit-tested on the JVM.
 */
class ArSceneBuilder {
    private var lane: EgoLane? = null
    /** Last ego lane measured from lines or anchors (unsmoothed), the reference for spotting a lane change. */
    private var jumpRef: EgoLane? = null
    private var jumpRefNs = 0L
    private var shown: Shown? = null
    private var lastNs: Long = 0L
    private var flow = 0.0
    private val laneArrows = LaneArrowsBuilder()

    private class Shown(var intent: ArrowIntent, var alpha: Float, var target: Float)

    fun build(input: ArInput, viewWidth: Float, viewHeight: Float, nowNs: Long): ArScene {
        val dt = if (lastNs == 0L) 0.0 else ((nowNs - lastNs) / 1e9).coerceIn(0.0, 0.25)
        lastNs = nowNs
        val world = input.world
        val projector = GroundProjector.from(world)
        val image = world.image
        if (projector == null || image == null || viewWidth <= 0f || viewHeight <= 0f) {
            shown = null
            jumpRef = null
            laneArrows.reset()
            return ArScene(debug = null)
        }
        val map = FillCenter(image.width, image.height, viewWidth, viewHeight)
        val fresh = !world.perceptionStale

        // Ego lane, smoothed (tau 180 ms) so the arrows glide between 10-17 Hz perception results. A jump of
        // most of a lane width is the car crossing into the next lane (the ego pair of lines changed): snap,
        // since the painted lanes did not move. The jump is taken against the last lines / anchors measurement
        // (both come from the server's ego pair) within 1 s, so a crossing seen through a camera-axis run or a
        // lines <-> anchors switch still counts.
        val measured = if (fresh) EgoLane.from(world, projector) else null
        var laneShift = 0
        if (measured != null && measured.source != EgoLane.Source.CAMERA_AXIS) {
            val ref = jumpRef?.takeIf { nowNs - jumpRefNs <= JUMP_REF_MAX_NS }
            if (ref != null) {
                val jump = (measured.x(JUMP_REF_Z) - ref.x(JUMP_REF_Z)) / measured.widthMeters
                if (abs(jump) >= LANE_JUMP) laneShift = jump.roundToInt().coerceIn(-2, 2)
            }
            jumpRef = measured
            jumpRefNs = nowNs
        }
        val prev = lane
        lane = when {
            measured == null -> prev
            prev == null || prev.source != measured.source || laneShift != 0 -> measured
            else -> prev.lerp(measured, 1.0 - exp(-dt / LANE_TAU_S))
        }
        val ego = lane ?: EgoLane.AXIS

        // Which arrows, and their fade.
        val wanted = if (fresh) RouteArrows.intent(input.route, input.context.laneGuidance, input.routeDistanceMeters) else null
        val s = shown
        when {
            wanted != null && (s == null || s.intent.kind != wanted.kind) && (s == null || s.alpha <= 0.05f) ->
                shown = Shown(wanted, 0f, 1f)
            wanted != null && s != null && s.intent.kind == wanted.kind -> { s.intent = wanted; s.target = 1f }
            s != null -> s.target = 0f // fading out (also before a different arrow fades in)
        }
        shown?.let { it.alpha = approach(it.alpha, it.target, (dt / FADE_S).toFloat()) }
        if (shown?.let { it.alpha <= 0f && it.target == 0f } == true) shown = null

        flow = (flow + dt * FLOW_MPS) % CHEVRON_SPACING_M
        // The chevron path and its ribbon are debug-only; the clean view keeps only the destination pin.
        val arrows = if (input.debug) shown?.let { sh -> arrows(sh.intent, sh.alpha, ego, projector, map) } else null
        val lanes = laneArrows.build(input, ego, projector, map, nowNs, dt, laneShift)
        return ArScene(
            laneArrows = lanes.arrows,
            occluders = lanes.occluders,
            chevrons = arrows?.chevrons.orEmpty(),
            ribbon = arrows?.ribbon.orEmpty(),
            ribbonAlpha = arrows?.ribbonAlpha ?: 0f,
            pin = shown?.let { pin(it.intent, ego, projector, map) },
            arrowKind = shown?.intent?.kind,
            lead = lead(input, map),
            debug = if (input.debug) debugLayer(world, ego, projector, map).copy(laneStatus = lanes.status) else null,
        )
    }

    private class Arrows(val chevrons: List<Chevron>, val ribbon: List<Vec2>, val ribbonAlpha: Float)

    /** Destination pin on the road for ARRIVE within the arrow range. */
    private fun pin(intent: ArrowIntent, ego: EgoLane, projector: GroundProjector, map: FillCenter): Vec2? {
        if (intent.kind != ArrowKind.ARRIVE) return null
        val d = intent.maneuverAheadMeters?.takeIf { it <= RouteArrows.RUN_M + 8.0 } ?: return null
        return projector.toImage(Ground(ego.x(d), d))?.let(map::point)
    }

    private fun arrows(intent: ArrowIntent, alpha: Float, ego: EgoLane, projector: GroundProjector, map: FillCenter): Arrows? {
        val zStart = max(projector.nearestVisibleZ + 1.5, MIN_START_Z)
        val path = RouteArrows.path(intent, ego, zStart)
        if (path.size < 2) return null
        val arc = RouteArrows.arcLengths(path)
        val total = arc.last()
        val chevrons = ArrayList<Chevron>()
        var sPos = CHEVRON_SPACING_M - flow
        while (sPos < total - 0.6) {
            val (p, t) = at(path, arc, sPos)
            // Left normal of the heading on the road (x right, z ahead).
            val nx = -t.z
            val nz = t.x
            // 1.6 m long on the road: foreshortening flattens a shorter chevron into a bar.
            val tip = Ground(p.x + t.x * 0.9, p.z + t.z * 0.9)
            val wl = Ground(p.x - t.x * 0.7 + nx * 0.75, p.z - t.z * 0.7 + nz * 0.75)
            val wr = Ground(p.x - t.x * 0.7 - nx * 0.75, p.z - t.z * 0.7 - nz * 0.75)
            val a = projector.toImage(wl)?.let(map::point)
            val b = projector.toImage(tip)?.let(map::point)
            val c = projector.toImage(wr)?.let(map::point)
            if (a != null && b != null && c != null) {
                val span = hypot((a.x - c.x).toDouble(), (a.y - c.y).toDouble()).toFloat()
                val fadeIn = (sPos / 2.0).coerceIn(0.0, 1.0)
                val fadeOut = ((total - sPos) / 3.0).coerceIn(0.0, 1.0)
                val depth = (1.0 - (p.z - zStart) / 60.0).coerceIn(0.45, 1.0)
                val a1 = (alpha * fadeIn * fadeOut * depth).toFloat()
                if (a1 > 0.02f && span > 6f) chevrons += Chevron(a, b, c, a1, (span * 0.16f).coerceIn(3f, 22f))
            }
            sPos += CHEVRON_SPACING_M
        }
        // Ribbon: the path widened to 1.2 m on the road.
        val left = ArrayList<Vec2>()
        val right = ArrayList<Vec2>()
        for (i in path.indices step 2) {
            val (p, t) = at(path, arc, arc[i])
            projector.toImage(Ground(p.x - t.z * 0.6, p.z + t.x * 0.6))?.let { left += map.point(it) }
            projector.toImage(Ground(p.x + t.z * 0.6, p.z - t.x * 0.6))?.let { right += map.point(it) }
        }
        val ribbon = if (left.size >= 2 && right.size >= 2) left + right.asReversed() else emptyList()
        return Arrows(chevrons, ribbon, alpha * 0.22f)
    }

    /** Point and unit heading on the path at arc length [s]. */
    private fun at(path: List<Ground>, arc: DoubleArray, s: Double): Pair<Ground, Ground> {
        var i = 1
        while (i < arc.size - 1 && arc[i] < s) i++
        val a = path[i - 1]
        val b = path[i]
        val seg = (arc[i] - arc[i - 1]).coerceAtLeast(1e-6)
        val f = ((s - arc[i - 1]) / seg).coerceIn(0.0, 1.0)
        val dx = (b.x - a.x) / seg
        val dz = (b.z - a.z) / seg
        return Ground(a.x + (b.x - a.x) * f, a.z + (b.z - a.z) * f) to Ground(dx, dz)
    }

    private fun lead(input: ArInput, map: FillCenter): LeadHighlight? {
        val following = input.context.following
        if (input.context.perceptionStale || input.world.perceptionStale) return null
        if (following.state == FollowingState.NORMAL) return null
        // Clean view: only TOO CLOSE, as brackets without a badge (the HUD shows the distance).
        val critical = following.state == FollowingState.CRITICAL
        if (!critical && !input.debug) return null
        val distance = following.distanceMeters ?: return null // no distance, no highlight: never invent a number
        val id = following.leadTrackId ?: return null
        val obj = input.world.objects[id] ?: return null
        val rect = map.box(obj.bbox) ?: return null
        return LeadHighlight(rect, "Vehicle ahead: ${fmt1(distance)} m", critical, badge = input.debug)
    }

    private fun debugLayer(world: WorldSnapshot, ego: EgoLane, projector: GroundProjector, map: FillCenter): DebugLayer {
        val boxes = world.objects.values.filter { it.visible }.mapNotNull { o ->
            val r = map.box(o.bbox) ?: return@mapNotNull null
            val d = o.distanceMeters?.let { "${fmt1(it)} m" } ?: "--"
            val tag = buildString {
                append(o.cls.wire).append(' ').append(d)
                o.lightState?.let { append(' ').append(it.name) }
                if (o.inEgoPath == true) append(" path")
            }
            r to tag
        }
        val lines = world.lanes?.lanes?.laneBoundaries.orEmpty().map { line ->
            line.filter { it.size >= 2 }.map { map.point(it[0], it[1]) }
        }
        val egoPts = ArrayList<Vec2>()
        var z = max(projector.nearestVisibleZ, 3.0)
        while (z <= 60.0) {
            projector.toImage(Ground(ego.x(z), z))?.let { egoPts += map.point(it) }
            z += if (z < 20) 1.0 else 2.5
        }
        val anchors = world.road?.road?.anchorPoints.orEmpty().filter { it.xy.size >= 2 }.map { map.point(it.x, it.y) }
        return DebugLayer(boxes, lines, egoPts, ego.source, anchors, map.point(0.0, projector.horizonY).y)
    }

    private fun approach(value: Float, target: Float, step: Float): Float =
        if (value < target) min(target, value + step) else max(target, value - step)

    companion object {
        const val LANE_TAU_S = 0.18
        const val FADE_S = 0.35
        const val CHEVRON_SPACING_M = 4.0
        const val FLOW_MPS = 3.0
        /** Dash cams see their own hood in the bottom rows: the arrows start on the road beyond it. */
        const val MIN_START_Z = 7.0

        /** The ego lane centre is compared this far ahead to spot a lane change. */
        const val JUMP_REF_Z = 10.0

        /** A jump of this many lane widths between perception results is a change of lane, not noise. */
        const val LANE_JUMP = 0.6

        /** The lane-change reference is dropped after this long without a lines / anchors measurement. */
        private const val JUMP_REF_MAX_NS = 1_000_000_000L

        fun fmt1(x: Double): String = String.format(Locale.US, "%.1f", x)
    }
}
