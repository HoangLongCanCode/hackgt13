package com.drivingassist.spatialcopilot.ar

import com.drivingassist.copilot.context.LaneAction
import com.drivingassist.copilot.context.Maneuver
import com.drivingassist.copilot.context.WorldSnapshot
import com.drivingassist.spatialcopilot.nav.RouteGuide
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Shape of a painted lane arrow (US pavement-marking style). */
enum class LaneArrowGlyph { STRAIGHT, TURN_LEFT, TURN_RIGHT, BEAR_LEFT, BEAR_RIGHT }

/**
 * TARGET: a lane for the route (green). TARGET_BLINK: the same while the car is in another lane (green,
 * slow pulse). WRONG: the car's lane when it is not a target (red). OTHER: any other lane (white, faint).
 */
enum class LaneArrowStyle { TARGET, TARGET_BLINK, WRONG, OTHER }

/**
 * One arrow lying flat on the road, in view pixels.
 * @param lane 1-based lane from the left; null = the ego lane when lane numbers are not known.
 * @param outline closed simple polygon.
 * @param alpha final opacity (fade, emphasis and blink applied).
 */
data class LaneArrow(
    val lane: Int?,
    val style: LaneArrowStyle,
    val glyph: LaneArrowGlyph,
    val outline: List<Vec2>,
    val alpha: Float,
)

/** Lane numbers the arrows may rely on (see [LaneArrows.knownLanes]). */
data class KnownLanes(val currentLane: Int, val laneCount: Int, val confidence: Double)

/** Output of [LaneArrowsBuilder.build] for one display frame. */
class LaneArrowFrame(
    val arrows: List<LaneArrow>,
    /** View rects of road users overlapping the arrows (inflated): the arrows are not painted over them. */
    val occluders: List<ViewRect>,
    /** Debug badge, e.g. "lanes 2/3 conf 0.82 targets 3 WRONG" or "lanes unknown: conf 0.21". */
    val status: String,
)

/**
 * Painted-style lane arrows: glyph outlines in lane-local metres and their projection onto the road.
 * Lane numbers come from the lane model (`world.lanes`), target lanes from the Driving Context's lane
 * guidance, positions from the fitted ego lane plus whole lane widths. Nothing here decides a route.
 */
object LaneArrows {
    /** The arrow starts this far beyond the nearest visible road (the hood), and never nearer than [MIN_START_Z]. */
    const val START_GAP_M = 2.0
    const val MIN_START_Z = 9.0
    const val LENGTH_M = 7.0

    /** Lane numbers are used only from a fresh (<= 1 s), confident run of at most 6 lanes that saw lines. */
    const val MIN_CONFIDENCE = 0.3
    const val MAX_LANE_COUNT = 6

    /** Lanes drawn on each side of the car in the lane-choice case. */
    const val SIDE_LANES = 2

    /** Target arrows take the turn / bear glyph this close to the maneuver. */
    const val GLYPH_M = 100.0

    /** Arrows are emphasised this close to the maneuver. */
    const val ACTIVE_M = 300.0

    const val WRONG_AFTER_S = 0.8
    const val RIGHT_AFTER_S = 0.3
    const val BLINK_PERIOD_S = 1.2
    const val FADE_S = 0.35
    const val OCCLUDER_PAD_PX = 4f

    const val ACTIVE_ALPHA = 0.9f
    const val IDLE_ALPHA = 0.55f
    const val OTHER_FACTOR = 0.45f

    private const val STEP_M = 0.25
    private const val SHAFT_HALF_W = 0.225

    /** Lane-local outline: [u] metres across (+ right of the lane centre), [v] metres along (0 = arrow start). */
    class Outline(val u: DoubleArray, val v: DoubleArray) {
        val size: Int get() = u.size
    }

    private val outlines: Map<LaneArrowGlyph, Outline> = LaneArrowGlyph.entries.associateWith { build(it) }

    fun outline(glyph: LaneArrowGlyph): Outline = outlines.getValue(glyph)

    /**
     * Lane numbers of [world] when they can be relied on, else null with [reason] set: lanes fresh
     * (<= [EgoLane.MAX_LANES_AGE_S]), confidence >= [MIN_CONFIDENCE], 1 <= current <= count <= [MAX_LANE_COUNT],
     * the run saw lines ([linesSeen]), and the ego lane measured from lines or anchors (on the camera
     * axis the lane positions are unknown).
     */
    fun knownLanes(world: WorldSnapshot, egoSource: EgoLane.Source, linesSeen: Boolean, reason: (String) -> Unit = {}): KnownLanes? {
        val lanes = world.lanes ?: return null.also { reason("none") }
        if (lanes.ageSeconds > EgoLane.MAX_LANES_AGE_S) return null.also { reason("old ${fmt(lanes.ageSeconds, 1)} s") }
        val conf = lanes.lanes.confidence
        if (conf < MIN_CONFIDENCE) return null.also { reason("conf ${fmt(conf, 2)}") }
        val count = lanes.laneCount
        val current = lanes.currentLane
        if (count == null || current == null || count !in 1..MAX_LANE_COUNT || current !in 1..count) {
            return null.also { reason("lane ${current ?: "?"}/${count ?: "?"}") }
        }
        if (!linesSeen) return null.also { reason("no lines") }
        if (egoSource == EgoLane.Source.CAMERA_AXIS) return null.also { reason("camera axis") }
        return KnownLanes(current, count, conf)
    }

    /** Glyph for a target lane: the maneuver's shape within [GLYPH_M], else straight. Unknown side: straight. */
    fun targetGlyph(route: RouteGuide?, distanceMeters: Double?): LaneArrowGlyph {
        if (route == null || distanceMeters == null || distanceMeters > GLYPH_M) return LaneArrowGlyph.STRAIGHT
        val side = route.turnDirection?.lowercase()
        return when (route.maneuver) {
            Maneuver.TURN_LEFT -> LaneArrowGlyph.TURN_LEFT
            Maneuver.TURN_RIGHT -> LaneArrowGlyph.TURN_RIGHT
            Maneuver.KEEP_LEFT, Maneuver.MERGE_LEFT -> LaneArrowGlyph.BEAR_LEFT
            Maneuver.KEEP_RIGHT, Maneuver.MERGE_RIGHT -> LaneArrowGlyph.BEAR_RIGHT
            // phase1 exits / merges usually carry turnDirection "exit" / "merge": no side, no bend.
            Maneuver.EXIT, Maneuver.MERGE -> when (side) {
                "left" -> LaneArrowGlyph.BEAR_LEFT
                "right" -> LaneArrowGlyph.BEAR_RIGHT
                else -> LaneArrowGlyph.STRAIGHT
            }
            else -> LaneArrowGlyph.STRAIGHT
        }
    }

    /** Where the arrows start on the road. */
    fun startZ(projector: GroundProjector): Double = max(projector.nearestVisibleZ + START_GAP_M, MIN_START_Z)

    /**
     * Road point of lane-local ([u], [v]) in the lane [offsetLanes] lanes right of the ego lane: centre
     * `ego.x(z) + offset * width`, [u] along the lane's normal so the glyph follows the lane heading.
     */
    fun ground(ego: EgoLane, offsetLanes: Int, z0: Double, u: Double, v: Double): Ground {
        val z = z0 + v
        val s = ego.slope(z)
        val inv = 1.0 / sqrt(1.0 + s * s)
        return Ground(ego.x(z) + offsetLanes * ego.widthMeters + u * inv, z - u * s * inv)
    }

    /** [glyph] projected into view pixels; null when any point cannot be projected. */
    fun project(glyph: LaneArrowGlyph, ego: EgoLane, offsetLanes: Int, z0: Double, projector: GroundProjector, map: FillCenter): List<Vec2>? {
        val o = outline(glyph)
        val out = ArrayList<Vec2>(o.size)
        for (i in 0 until o.size) {
            val p = projector.toImage(ground(ego, offsetLanes, z0, o.u[i], o.v[i])) ?: return null
            out += map.point(p)
        }
        return out
    }

    /** View point of the middle of the arrow (lane centre, halfway along). */
    fun center(ego: EgoLane, offsetLanes: Int, z0: Double, projector: GroundProjector, map: FillCenter): Vec2? =
        projector.toImage(ground(ego, offsetLanes, z0, 0.0, LENGTH_M / 2))?.let(map::point)

    /** Smooth pulse starting fully on: 1.0 -> 0.25 -> 1.0 every [BLINK_PERIOD_S]. */
    fun blink(sinceS: Double): Float = (0.625 + 0.375 * cos(2 * PI * sinceS / BLINK_PERIOD_S)).toFloat()

    // --- Glyph outlines -------------------------------------------------------------------------

    private fun build(glyph: LaneArrowGlyph): Outline {
        val right = when (glyph) {
            LaneArrowGlyph.STRAIGHT -> stroke(listOf(0.0 to 0.0, 0.0 to LENGTH_M - 2.4), headHalf = 0.65, headLen = 2.4)
            LaneArrowGlyph.TURN_LEFT, LaneArrowGlyph.TURN_RIGHT -> turn()
            LaneArrowGlyph.BEAR_LEFT, LaneArrowGlyph.BEAR_RIGHT -> bear()
        }
        val mirrored = glyph == LaneArrowGlyph.TURN_LEFT || glyph == LaneArrowGlyph.BEAR_LEFT
        val pts = if (mirrored) right.map { -it.first to it.second } else right
        return densify(pts)
    }

    /**
     * Shaft up the lane, a quarter bend to the right, head pointing sideways (a US turn-lane marking). The
     * head is long along the lane (2.4 m): seen from the driver's seat, depth is foreshortened ~10x.
     */
    private fun turn(): List<Pair<Double, Double>> {
        val u0 = -0.7
        val r = 0.9
        val headHalf = 1.2
        val vb = LENGTH_M - headHalf - r
        val centre = ArrayList<Pair<Double, Double>>()
        centre += u0 to 0.0
        centre += u0 to vb
        val n = 8
        for (i in 1..n) {
            val th = PI - (PI / 2) * i / n
            centre += (u0 + r + r * cos(th)) to (vb + r * sin(th))
        }
        centre += (u0 + r + 0.15) to (vb + r)
        return stroke(centre, headHalf = headHalf, headLen = 1.2)
    }

    /** Shaft up the lane, then a diagonal to the right with the head along it. */
    private fun bear(): List<Pair<Double, Double>> {
        val u0 = -0.6
        val du = 1.0 / sqrt(5.0)
        val dv = 2.0 / sqrt(5.0)
        val headLen = 2.2
        val diag = 1.2
        val vk = LENGTH_M - 0.06 - (diag + headLen) * dv
        return stroke(listOf(u0 to 0.0, u0 to vk, (u0 + diag * du) to (vk + diag * dv)), headHalf = 0.65, headLen = headLen)
    }

    /**
     * Outline of a centreline widened to the shaft width (mitred joints) with a triangular head at its
     * end: left start, up the right edge, the head, back down the left edge.
     */
    private fun stroke(centre: List<Pair<Double, Double>>, headHalf: Double, headLen: Double): List<Pair<Double, Double>> {
        val n = centre.size
        val tu = DoubleArray(n - 1)
        val tv = DoubleArray(n - 1)
        for (i in 0 until n - 1) {
            val du = centre[i + 1].first - centre[i].first
            val dv = centre[i + 1].second - centre[i].second
            val len = hypot(du, dv)
            tu[i] = du / len
            tv[i] = dv / len
        }
        // Right normal of heading (tu, tv) is (tv, -tu).
        val mu = DoubleArray(n)
        val mv = DoubleArray(n)
        for (i in 0 until n) {
            val a = max(i - 1, 0)
            val b = min(i, n - 2)
            val au = tv[a]; val av = -tu[a]
            val bu = tv[b]; val bv = -tu[b]
            val k = 1.0 + au * bu + av * bv
            mu[i] = (au + bu) / k
            mv[i] = (av + bv) / k
        }
        val h = SHAFT_HALF_W
        val out = ArrayList<Pair<Double, Double>>(2 * n + 3)
        out += (centre[0].first - h * mu[0]) to (centre[0].second - h * mv[0])
        for (i in 0 until n) out += (centre[i].first + h * mu[i]) to (centre[i].second + h * mv[i])
        val (eu, ev) = centre[n - 1]
        val hu = tu[n - 2]; val hv = tv[n - 2]
        out += (eu + headHalf * hv) to (ev - headHalf * hu)
        out += (eu + headLen * hu) to (ev + headLen * hv)
        out += (eu - headHalf * hv) to (ev + headHalf * hu)
        for (i in n - 1 downTo 1) out += (centre[i].first - h * mu[i]) to (centre[i].second - h * mv[i])
        return out
    }

    /** Closed polygon with no edge longer than [STEP_M], so it bends with the lane on the road. */
    private fun densify(pts: List<Pair<Double, Double>>): Outline {
        val us = ArrayList<Double>()
        val vs = ArrayList<Double>()
        for (i in pts.indices) {
            val (u0, v0) = pts[i]
            val (u1, v1) = pts[(i + 1) % pts.size]
            val steps = max(1, ceil(hypot(u1 - u0, v1 - v0) / STEP_M).toInt())
            for (s in 0 until steps) {
                val f = s.toDouble() / steps
                us += u0 + (u1 - u0) * f
                vs += v0 + (v1 - v0) * f
            }
        }
        return Outline(us.toDoubleArray(), vs.toDoubleArray())
    }

    internal fun fmt(x: Double, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", x)
}

/**
 * Chooses, styles and fades the lane arrows at display rate. Owned by [ArSceneBuilder]; every piece of
 * presentation state (wrong-lane debounce, blink phase, per-lane fades, lane-change bookkeeping) lives
 * here and runs on the display clock (`nowNs`).
 *
 * Lane choice (guidance KEEP_LANE / CHANGE_LANE_* with targets, lane numbers known): one arrow per lane
 * from current-2 to current+2. Otherwise only the ego lane's arrow, never red: unknown stays unknown. It is
 * green (TARGET) with KEEP_LANE or no lane guidance, and faint white and straight (OTHER) while the guidance
 * asks for a lane change (the car's lane is then not a route lane) or the wrong-lane look is held.
 */
class LaneArrowsBuilder {
    private class Shown(var offset: Int, var style: LaneArrowStyle, var glyph: LaneArrowGlyph, var alpha: Float, var target: Float)

    /** Key: lane number, or [EGO_KEY] for the ego lane with unknown lane numbers. */
    private val shown = LinkedHashMap<Int, Shown>()
    private var wrongLook = false
    private var pendingNs = NONE
    private var choiceLostNs = NONE
    private var blinkStartNs = 0L
    private var linesSeenNs = NONE
    private var lastMode: Int? = null
    private var lastModeNs = NONE
    private var modeChangeNs = NONE
    private var modeChangeDir = 0
    private var shift = 0
    private var shiftNs = NONE

    fun reset() {
        shown.clear()
        wrongLook = false
        pendingNs = NONE
        choiceLostNs = NONE
        linesSeenNs = NONE
        lastMode = null
        lastModeNs = NONE
        modeChangeNs = NONE
        shift = 0
        shiftNs = NONE
    }

    /**
     * @param laneShift lanes the smoothed ego lane just snapped by (+1 = the car crossed into the lane on
     *   its right), from [ArSceneBuilder]; lane numbers (a mode over recent runs) catch up later.
     */
    fun build(
        input: ArInput,
        ego: EgoLane,
        projector: GroundProjector,
        map: FillCenter,
        nowNs: Long,
        dt: Double,
        laneShift: Int,
    ): LaneArrowFrame {
        val world = input.world
        val route = input.route
        val showable = route != null && !route.stale && !route.offRoute && !world.perceptionStale

        val lanesState = world.lanes
        if (lanesState != null && lanesState.ageSeconds <= EgoLane.MAX_LANES_AGE_S && lanesState.lanes.laneBoundaries.isNotEmpty()) {
            linesSeenNs = nowNs
        }
        // One run without lines (~130 ms) does not drop the lane numbers; a run that saw lines 0.4 s ago still counts.
        val linesSeen = linesSeenNs != NONE && nowNs - linesSeenNs <= LINES_HOLD_NS
        var reason = ""
        val known = LaneArrows.knownLanes(world, ego.source, linesSeen) { reason = it }

        trackLaneChange(known?.currentLane, nowNs, laneShift)
        if (laneShift != 0) shown.values.forEach { it.offset -= laneShift }
        val current = known?.let { (it.currentLane + shift).coerceIn(1, it.laneCount) }

        val guidance = input.context.laneGuidance
            ?.takeIf { it.action == LaneAction.KEEP_LANE || it.action == LaneAction.CHANGE_LANE_LEFT || it.action == LaneAction.CHANGE_LANE_RIGHT }
        val targets = if (known != null) guidance?.targetLanes?.filter { it in 1..known.laneCount }.orEmpty() else emptyList()
        val choice = showable && known != null && current != null && targets.isNotEmpty()

        // Wrong-lane look: on after 0.8 s of "current not in targets", off 0.3 s after reaching a target. A lane
        // choice lost for up to 0.4 s (one low-confidence run) keeps the look and pauses the timer, so unknown
        // time never counts as wrong-lane time; nothing red is drawn meanwhile (only the ego arrow).
        val rawWrong = choice && current !in targets
        if (!choice) {
            if (choiceLostNs == NONE) choiceLostNs = nowNs
            if (nowNs - choiceLostNs > CHOICE_HOLD_NS) { wrongLook = false; pendingNs = NONE }
        } else {
            if (choiceLostNs != NONE) {
                if (pendingNs != NONE) pendingNs += nowNs - choiceLostNs
                choiceLostNs = NONE
            }
            if (rawWrong == wrongLook) {
                pendingNs = NONE
            } else {
                if (pendingNs == NONE) pendingNs = nowNs
                val need = if (wrongLook) LaneArrows.RIGHT_AFTER_S else LaneArrows.WRONG_AFTER_S
                if ((nowNs - pendingNs) / 1e9 >= need - 1e-9) {
                    wrongLook = rawWrong
                    pendingNs = NONE
                    if (wrongLook) blinkStartNs = nowNs
                }
            }
        }

        val distance = input.routeDistanceMeters
        val targetGlyph = LaneArrows.targetGlyph(route, distance)
        val wanted = HashMap<Int, Triple<Int, LaneArrowStyle, LaneArrowGlyph>>()
        if (showable) {
            if (choice) {
                val cur = current!!
                for (k in max(1, cur - LaneArrows.SIDE_LANES)..min(known!!.laneCount, cur + LaneArrows.SIDE_LANES)) {
                    val style = when {
                        k in targets -> if (wrongLook) LaneArrowStyle.TARGET_BLINK else LaneArrowStyle.TARGET
                        k == cur && wrongLook -> LaneArrowStyle.WRONG
                        else -> LaneArrowStyle.OTHER
                    }
                    val glyph = if (style == LaneArrowStyle.TARGET || style == LaneArrowStyle.TARGET_BLINK) targetGlyph else LaneArrowGlyph.STRAIGHT
                    wanted[k] = Triple(k - cur, style, glyph)
                }
            } else {
                // The Driving Context says the car's lane is not a route lane, or the wrong-lane look is held over a short gap:
                // no green, no maneuver shape. Never red either, the lane positions are unknown here.
                val changeLane = guidance?.action == LaneAction.CHANGE_LANE_LEFT || guidance?.action == LaneAction.CHANGE_LANE_RIGHT
                wanted[EGO_KEY] = if (changeLane || wrongLook) Triple(0, LaneArrowStyle.OTHER, LaneArrowGlyph.STRAIGHT) else Triple(0, LaneArrowStyle.TARGET, targetGlyph)
            }
        }
        for ((key, w) in wanted) {
            val s = shown.getOrPut(key) { Shown(w.first, w.second, w.third, 0f, 1f) }
            s.offset = w.first
            s.style = w.second
            s.glyph = w.third
            s.target = 1f
        }
        val step = (dt / LaneArrows.FADE_S).toFloat()
        val iter = shown.entries.iterator()
        while (iter.hasNext()) {
            val (key, s) = iter.next()
            if (key !in wanted) {
                s.target = 0f
                // No red lingers once lanes are unknown or perception is stale: the old car's-lane arrow fades out white.
                if (s.style == LaneArrowStyle.WRONG) s.style = LaneArrowStyle.OTHER
            }
            s.alpha = if (s.alpha < s.target) min(s.target, s.alpha + step) else max(s.target, s.alpha - step)
            if (s.alpha <= 0f && s.target == 0f) iter.remove()
        }

        val maneuverNear = route != null && route.maneuver != Maneuver.FOLLOW_ROAD && distance != null && distance <= LaneArrows.ACTIVE_M
        val base = if (choice || maneuverNear) LaneArrows.ACTIVE_ALPHA else LaneArrows.IDLE_ALPHA
        val pulse = LaneArrows.blink((nowNs - blinkStartNs) / 1e9)
        val z0 = LaneArrows.startZ(projector)
        val arrows = ArrayList<LaneArrow>(shown.size)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for ((key, s) in shown) {
            val c = LaneArrows.center(ego, s.offset, z0, projector, map) ?: continue
            if (c.x < 0f || c.x > map.viewWidth || c.y < 0f || c.y > map.viewHeight) continue
            val factor = when (s.style) {
                LaneArrowStyle.OTHER -> LaneArrows.OTHER_FACTOR
                LaneArrowStyle.TARGET_BLINK -> pulse
                else -> 1f
            }
            val alpha = s.alpha * base * factor
            if (alpha <= 0.01f) continue
            val outline = LaneArrows.project(s.glyph, ego, s.offset, z0, projector, map) ?: continue
            for (p in outline) {
                if (p.x < minX) minX = p.x
                if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y
                if (p.y > maxY) maxY = p.y
            }
            arrows += LaneArrow(if (key == EGO_KEY) null else key, s.style, s.glyph, outline, alpha)
        }
        val occluders = if (arrows.isEmpty()) emptyList() else occluders(world, map, ViewRect(minX, minY, maxX, maxY))
        return LaneArrowFrame(arrows, occluders, status(known, reason, targets, current))
    }

    /**
     * Lane numbers are a mode over the last runs, so they change a few runs after the ego lane snapped to
     * the next pair of lines. Until they catch up (or [SHIFT_HOLD_NS]), the snap is added to the lane
     * number so every arrow stays on its own lane. A snap right after the lane number already moved the
     * same way is not counted twice. Runs with unknown lane numbers keep this bookkeeping (a snap seen then
     * still counts once they are back); the last lane number is forgotten after [SHIFT_HOLD_NS] unknown.
     */
    private fun trackLaneChange(mode: Int?, nowNs: Long, laneShift: Int) {
        if (mode != null) {
            val prev = lastMode
            lastMode = mode
            lastModeNs = nowNs
            if (prev != null && mode != prev) {
                if (shift != 0) { shift = 0; shiftNs = NONE } else { modeChangeNs = nowNs; modeChangeDir = if (mode > prev) 1 else -1 }
            }
        } else if (lastMode != null && nowNs - lastModeNs > SHIFT_HOLD_NS) {
            lastMode = null
        }
        if (laneShift != 0) {
            val dir = if (laneShift > 0) 1 else -1
            if (modeChangeNs != NONE && nowNs - modeChangeNs <= MODE_FIRST_NS && dir == modeChangeDir && shift == 0) {
                modeChangeNs = NONE
            } else {
                shift = (shift + laneShift).coerceIn(-2, 2)
                shiftNs = nowNs
            }
        }
        if (shift != 0 && nowNs - shiftNs > SHIFT_HOLD_NS) { shift = 0; shiftNs = NONE }
    }

    private fun occluders(world: WorldSnapshot, map: FillCenter, bounds: ViewRect): List<ViewRect> {
        val pad = LaneArrows.OCCLUDER_PAD_PX
        val out = ArrayList<ViewRect>()
        for (o in world.objects.values) {
            // Vehicles (incl. motorcycles), pedestrians, riders, bicycles.
            if (!o.visible || !(o.cls.isVehicle || o.cls.isVulnerableRoadUser)) continue
            val r = map.box(o.bbox) ?: continue
            val g = ViewRect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)
            if (g.right < bounds.left || g.left > bounds.right || g.bottom < bounds.top || g.top > bounds.bottom) continue
            out += g
        }
        return out
    }

    private fun status(known: KnownLanes?, reason: String, targets: List<Int>, current: Int?): String {
        if (known == null) return "lanes unknown: $reason"
        return buildString {
            append("lanes ").append(current ?: known.currentLane).append('/').append(known.laneCount)
            append(" conf ").append(LaneArrows.fmt(known.confidence, 2))
            if (targets.isNotEmpty()) append(" targets ").append(targets.joinToString(","))
            if (wrongLook) append(" WRONG")
            if (shift != 0) append(" shift ").append(if (shift > 0) "+$shift" else "$shift")
        }
    }

    companion object {
        const val EGO_KEY = 0
        private const val NONE = Long.MIN_VALUE
        private const val LINES_HOLD_NS = 400_000_000L
        private const val CHOICE_HOLD_NS = 400_000_000L
        /** Longer than the server's lane-number vote needs to catch up without a lane-change event (~2 s). */
        private const val SHIFT_HOLD_NS = 2_500_000_000L
        private const val MODE_FIRST_NS = 800_000_000L
    }
}
