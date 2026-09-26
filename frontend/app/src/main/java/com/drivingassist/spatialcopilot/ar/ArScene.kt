package com.drivingassist.spatialcopilot.ar

import com.drivingassist.copilot.context.DrivingContext
import com.drivingassist.copilot.context.FollowingState
import com.drivingassist.copilot.context.WorldSnapshot
import com.drivingassist.spatialcopilot.nav.RouteGuide
import java.util.Locale
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One chevron on the road, in view pixels: two wings and the tip. */
data class Chevron(val left: Vec2, val tip: Vec2, val right: Vec2, val alpha: Float, val strokePx: Float)

/** The lead vehicle, highlighted only while the Driving Context says CLOSE / CRITICAL and a distance exists. */
data class LeadHighlight(val rect: ViewRect, val label: String, val critical: Boolean)

/** Debug-only overlays (every box, lane lines, the fitted ego lane, anchors, horizon). */
data class DebugLayer(
    val boxes: List<Pair<ViewRect, String>>,
    val laneLines: List<List<Vec2>>,
    val egoLane: List<Vec2>,
    val egoLaneSource: EgoLane.Source?,
    val anchors: List<Vec2>,
    val horizonY: Float?,
)

/** Everything the AR canvas draws for one display frame, already in view pixels. */
data class ArScene(
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
 * between perception updates, scrolls the chevrons, and fades arrows in and out (0.35 s) when they
 * become relevant or stop being relevant. Pure Kotlin (no Android types), so it is unit-tested on the JVM.
 */
class ArSceneBuilder {
    private var lane: EgoLane? = null
    private var shown: Shown? = null
    private var lastNs: Long = 0L
    private var flow = 0.0

    private class Shown(var intent: ArrowIntent, var alpha: Float, var target: Float)

    fun build(input: ArInput, viewWidth: Float, viewHeight: Float, nowNs: Long): ArScene {
        val dt = if (lastNs == 0L) 0.0 else ((nowNs - lastNs) / 1e9).coerceIn(0.0, 0.25)
        lastNs = nowNs
        val world = input.world
        val projector = GroundProjector.from(world)
        val image = world.image
        if (projector == null || image == null || viewWidth <= 0f || viewHeight <= 0f) {
            shown = null
            return ArScene(debug = null)
        }
        val map = FillCenter(image.width, image.height, viewWidth, viewHeight)
        val fresh = !world.perceptionStale

        // Ego lane, smoothed (tau 180 ms) so the arrows glide between 10-17 Hz perception results.
        val measured = if (fresh) EgoLane.from(world, projector) else null
        val prev = lane
        lane = when {
            measured == null -> prev
            prev == null || prev.source != measured.source -> measured
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
        val arrows = shown?.let { sh -> arrows(sh.intent, sh.alpha, ego, projector, map) }
        return ArScene(
            chevrons = arrows?.chevrons.orEmpty(),
            ribbon = arrows?.ribbon.orEmpty(),
            ribbonAlpha = arrows?.ribbonAlpha ?: 0f,
            pin = arrows?.pin,
            arrowKind = shown?.intent?.kind,
            lead = lead(input, map),
            debug = if (input.debug) debugLayer(world, ego, projector, map) else null,
        )
    }

    private class Arrows(val chevrons: List<Chevron>, val ribbon: List<Vec2>, val ribbonAlpha: Float, val pin: Vec2?)

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
        val pin = if (intent.kind == ArrowKind.ARRIVE) {
            intent.maneuverAheadMeters?.takeIf { it <= RouteArrows.RUN_M + 8.0 }?.let { d ->
                projector.toImage(Ground(ego.x(d), d))?.let(map::point)
            }
        } else null
        return Arrows(chevrons, ribbon, alpha * 0.22f, pin)
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
        val distance = following.distanceMeters ?: return null // no distance, no highlight: never invent a number
        val id = following.leadTrackId ?: return null
        val obj = input.world.objects[id] ?: return null
        val rect = map.box(obj.bbox) ?: return null
        return LeadHighlight(rect, "Vehicle ahead: ${fmt1(distance)} m", following.state == FollowingState.CRITICAL)
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

        fun fmt1(x: Double): String = String.format(Locale.US, "%.1f", x)
    }
}
