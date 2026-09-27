package com.drivingassist.spatialcopilot.ar

import com.drivingassist.copilot.context.LaneAction
import com.drivingassist.copilot.context.LaneGuidance
import com.drivingassist.copilot.context.LaneLayout
import com.drivingassist.copilot.context.Maneuver
import com.drivingassist.copilot.context.WorldSnapshot
import com.drivingassist.spatialcopilot.nav.RouteGuide
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
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
 * @param lane 1-based lane of the [LaneLayout] from the left; null = not a lane of the displayed layout: the car's
 *   lane when there is no usable layout, or a lane that has just left the layout (fading out where it was drawn).
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

/**
 * One lane in analysed-image pixels, between two image lines through ([apexX], [apexY]):
 * `x(y) = apexX + slope * (y - apexY)`. A lane of a [LaneLayout] has its two lines meeting at the layout's
 * vanishing point; the lone arrow's lane (no layout) is 3.5 m wide around the camera's ground track and
 * meets at the horizon.
 * @param nearestRowY the arrow does not start below this row (the lowest row a lane line reached); null = no limit.
 * @param roadBottomY lowest row of the drivable road on the lane's midline (above the dashboard, hood or A-pillar):
 *   the arrow starts [LaneArrows.START_GAP_M] beyond it; null = no drivable outline known.
 */
data class LaneStrip(
    val apexX: Double,
    val apexY: Double,
    val leftSlope: Double,
    val rightSlope: Double,
    val nearestRowY: Double? = null,
    val roadBottomY: Double? = null,
) {
    /** x at row [y] of lane fraction [s]: -0.5 = left line, 0 = midline, 0.5 = right line. The midline passes through the apex. */
    fun x(s: Double, y: Double): Double = apexX + (leftSlope + (0.5 + s) * (rightSlope - leftSlope)) * (y - apexY)
}

/** The stretch of road an arrow covers: [z0] (near end) to [z1] metres ahead. */
data class ArrowSpan(val z0: Double, val z1: Double)

/** Output of [LaneArrowsBuilder.build] for one display frame. */
class LaneArrowFrame(
    val arrows: List<LaneArrow>,
    /** View rects of road users overlapping the arrows (inflated): the arrows are not painted over them. */
    val occluders: List<ViewRect>,
    /** Outline of the drivable road (closed, view pixels): the arrows are painted only inside it; empty = unknown. */
    val drivable: List<Vec2>,
    /** Debug badge, e.g. "layout 2/3 q0.72 targets 3 WRONG" or "layout unknown". */
    val status: String,
    /** The layout the arrows were placed on (display-smoothed); null = no usable layout. */
    val layout: LaneLayout? = null,
)

/**
 * Painted-style lane arrows: glyph outlines in lane coordinates and their mapping onto the image. Lanes come
 * from the lane layout (`world.laneLayout`: the detected lane lines through one vanishing point), target
 * lanes from the Driving Context's lane guidance. Each arrow is laid out between its lane's own two lines,
 * so it points where they meet whatever the phone's yaw. Nothing here decides a route.
 */
object LaneArrows {
    /**
     * The arrow starts this far beyond the nearest visible road (the hood, or where its lane's midline leaves the
     * drivable road above the dashboard / A-pillar), and never nearer than [MIN_START_Z].
     */
    const val START_GAP_M = 1.0
    const val MIN_START_Z = 6.0
    const val LENGTH_M = 6.0

    /** The arrow's far end stays this many image pixels below the vanishing point. */
    const val VP_GAP_PX = 8.0

    /** A layout is used when the bridge marks it stable and it is at most this old (media seconds): the bridge's "usable". */
    const val MAX_LAYOUT_AGE_S = LaneLayout.MAX_USABLE_AGE_SECONDS

    /** Width of the lone arrow's lane when there is no usable layout. */
    const val AXIS_LANE_W = 3.5

    /** Display smoothing of the layout between perception results (a changed set of lines snaps). */
    const val LAYOUT_TAU_S = 0.1

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

    /** Turn / bear heads stay this far (lane fractions) from the lane midline, inside the lines. */
    const val MAX_S = 0.42

    /** Glyphs are drawn in a nominal lane this wide (and [LENGTH_M] long), then stored as lane fractions. */
    private const val NOMINAL_W_M = 3.5
    private const val SHAFT_HALF_M = 0.065 * NOMINAL_W_M
    private const val HEAD_HALF_M = 0.19 * NOMINAL_W_M
    private const val HEAD_LEN_M = LENGTH_M / 3
    private const val STEP_M = 0.25
    private const val MIN_SPAN_M = 2.0
    private const val MIN_ROWS_PX = 6.0

    /** Lane-local outline: [s] across the lane in lane widths (-0.5..0.5, + right), [t] along it (0 = near end, 1 = far end). */
    class Outline(val s: DoubleArray, val t: DoubleArray) {
        val size: Int get() = s.size
    }

    private val outlines: Map<LaneArrowGlyph, Outline> = LaneArrowGlyph.entries.associateWith { build(it) }

    fun outline(glyph: LaneArrowGlyph): Outline = outlines.getValue(glyph)

    /**
     * [world]'s lane layout when it can be relied on, else null with [reason] set (not set when there is no
     * layout at all): at most [MAX_LAYOUT_AGE_S] old, [LaneLayout.stable] (the bridge's quality gate, with
     * hysteresis over runs, so the Driving Context's lane guidance flips with it), the car's lane among its lanes.
     */
    fun usableLayout(world: WorldSnapshot, reason: (String) -> Unit = {}): LaneLayout? {
        val layout = world.laneLayout ?: return null
        if (layout.ageSeconds > MAX_LAYOUT_AGE_S) return null.also { reason("old ${fmt(layout.ageSeconds, 1)} s") }
        if (!layout.stable) return null.also {
            reason((if (layout.quality < LaneLayout.USABLE_QUALITY) "q" else "unstable q") + fmt(layout.quality, 2))
        }
        if (layout.lines.size < 2 || layout.egoLane !in 1..layout.laneCount) return null.also { reason("lane ${layout.egoLane}/${layout.laneCount}") }
        return layout
    }

    /**
     * The outline of the drivable road of [world] (image px, at least 3 points) from a lanes-fresh road run; null when
     * the server sent none (the arrows are then placed and painted as without it, unless [roadHidden]).
     */
    fun drivableRoad(world: WorldSnapshot): List<List<Double>>? =
        world.road?.takeIf { it.ageSeconds <= EgoLane.MAX_LANES_AGE_S }?.road?.drivablePolygon
            ?.filter { it.size >= 2 && it[0].isFinite() && it[1].isFinite() }
            ?.takeIf { it.size >= 3 }

    /**
     * The server sees no road: a lanes-fresh road run without a usable drivable outline (a v2 server sends `[]`, e.g.
     * stopped close behind a car, where the rest of the picture is crosswalk and median). No lane arrow is drawn then;
     * the instruction banner still guides. [outlinesSeen]: this session's server has sent an outline before. The field
     * decodes to empty when absent, so only then does an empty one mean "no road"; an old server that never sends it
     * keeps the unclipped arrows. No fresh road run at all is not "no road" either (the lone arrow along the track).
     */
    fun roadHidden(world: WorldSnapshot, outlinesSeen: Boolean): Boolean =
        outlinesSeen && world.road?.takeIf { it.ageSeconds <= EgoLane.MAX_LANES_AGE_S } != null && drivableRoad(world) == null

    /**
     * The near end of an arrow in [strip] over [span] is on the drivable road [polygon]: at or above the road's bottom
     * edge on the lane's midline ([roadBottomRow]). An arrow fading out on the strip it was placed on is drawn only then,
     * so a new road outline never shows it cut off below the road's edge.
     */
    fun nearEndOnRoad(strip: LaneStrip, span: ArrowSpan, polygon: List<List<Double>>, projector: GroundProjector): Boolean {
        val bottom = roadBottomRow(strip, polygon, projector.imageHeight.toDouble()) ?: return false
        return projector.rowAt(span.z0) <= bottom + 1e-6
    }

    /**
     * Lowest row where [strip]'s midline is on the drivable road [polygon] (image px, closed) below [VP_GAP_PX] under
     * the apex: the road's bottom edge in that lane (dashboard, hood, A-pillar). A car standing in the lane only
     * notches the outline higher up, so the lowest exit is the one that counts. [imageHeight] when the midline
     * reaches the bottom of the image on the road; null when it never meets the road.
     */
    fun roadBottomRow(strip: LaneStrip, polygon: List<List<Double>>, imageHeight: Double): Double? {
        val top = strip.apexY + VP_GAP_PX
        // Signed column offset from the midline (a straight image line): it changes sign where an edge crosses it.
        fun side(p: List<Double>) = p[0] - strip.x(0.0, p[1])
        val rows = ArrayList<Double>()
        for (i in polygon.indices) {
            val p = polygon[i]
            val q = polygon[(i + 1) % polygon.size]
            val a = side(p)
            val b = side(q)
            if ((a > 0) == (b > 0)) continue
            rows += p[1] + (q[1] - p[1]) * a / (a - b)
        }
        // Along the line the outline is crossed alternately inwards and outwards; the last crossing is an exit.
        val below = rows.filter { it > top }
        if (below.isEmpty()) return if (rows.count { it <= top } % 2 == 1) imageHeight else null
        return min(below.max(), imageHeight)
    }

    /** [strip] with its [LaneStrip.roadBottomY] on [road] (see [roadBottomRow]); null when its midline misses the road. No outline: as is. */
    fun onRoad(strip: LaneStrip, road: List<List<Double>>?, imageHeight: Double): LaneStrip? {
        if (road == null) return strip
        return roadBottomRow(strip, road, imageHeight)?.let { strip.copy(roadBottomY = it) }
    }

    /** Lane [lane] (1-based) of [layout]; null outside its lanes or when its lines do not open towards the car. */
    fun strip(layout: LaneLayout, lane: Int): LaneStrip? {
        if (lane !in 1..layout.laneCount) return null
        val left = layout.lines[lane - 1].slope
        val right = layout.lines[lane].slope
        if (right - left < 1e-3) return null
        return LaneStrip(layout.vpX, layout.vpY, left, right, layout.nearestRowY)
    }

    /**
     * The car's lane without a usable layout: [AXIS_LANE_W] wide at every row around the camera's ground track,
     * the vertical image line through [trackX]. On flat ground a lateral offset `u` metres is `u cos(pitch) / h`
     * pixels per pixel below the horizon.
     */
    fun axisStrip(world: WorldSnapshot, projector: GroundProjector): LaneStrip {
        val half = AXIS_LANE_W / 2 * cos(projector.pitch) / projector.cameraHeightMeters
        return LaneStrip(trackX(world, projector), projector.horizonY, -half, half)
    }

    /**
     * Image column of the camera's ground track: the road's vanishing point ([roadVanishingX]), else the session's
     * newest one ([WorldSnapshot.trackVpX], kept by the bridge through stretches without road or lines: a mounted
     * phone's yaw does not change), and only then the principal point (the camera axis, metres off the track with a
     * yawed phone, on a lane line).
     */
    fun trackX(world: WorldSnapshot, projector: GroundProjector): Double =
        roadVanishingX(world)
            ?: world.trackVpX?.takeIf { it.isFinite() && it in 0.0..projector.imageWidth.toDouble() }
            ?: projector.cx

    /** Where the arrows start on the road. */
    fun startZ(projector: GroundProjector): Double = max(projector.nearestVisibleZ + START_GAP_M, MIN_START_Z)

    /**
     * The road an arrow in [strip] covers: [LENGTH_M] from [startZ], started farther when that row is below the
     * lowest row the lines reached, and at least [START_GAP_M] beyond the drivable road's bottom edge in the lane
     * ([LaneStrip.roadBottomY]), then shortened to end [VP_GAP_PX] below the apex. Null when too little is left
     * (under 2 m of road or 6 image rows).
     */
    fun span(strip: LaneStrip, projector: GroundProjector): ArrowSpan? {
        var z0 = startZ(projector)
        val cap = strip.nearestRowY
        if (cap != null && projector.rowAt(z0) > cap) z0 = projector.forwardAtRow(cap) ?: return null
        val road = strip.roadBottomY
        if (road != null) z0 = max(z0, (projector.forwardAtRow(road) ?: return null) + START_GAP_M)
        val top = strip.apexY + VP_GAP_PX
        var z1 = z0 + LENGTH_M
        if (projector.rowAt(z1) < top) z1 = projector.forwardAtRow(top) ?: return null
        if (z1 - z0 < MIN_SPAN_M || projector.rowAt(z0) - projector.rowAt(z1) < MIN_ROWS_PX) return null
        return ArrowSpan(z0, z1)
    }

    /**
     * [glyph] mapped into view pixels: row `rowAt(z0 + t (z1 - z0))` (perspective along the lane), column
     * [LaneStrip.x] at that row (across the lane between its lines).
     */
    fun project(glyph: LaneArrowGlyph, strip: LaneStrip, span: ArrowSpan, projector: GroundProjector, map: FillCenter): List<Vec2> {
        val o = outline(glyph)
        val len = span.z1 - span.z0
        return List(o.size) { i ->
            val y = projector.rowAt(span.z0 + o.t[i] * len)
            map.point(strip.x(o.s[i], y), y)
        }
    }

    /** View point of the middle of the arrow (lane midline, halfway along). */
    fun center(strip: LaneStrip, span: ArrowSpan, projector: GroundProjector, map: FillCenter): Vec2 {
        val y = projector.rowAt((span.z0 + span.z1) / 2)
        return map.point(strip.x(0.0, y), y)
    }

    /**
     * [to] is [from] a little later (the same lines, moved less than a third of a lane, vanishing point within
     * 60 px), so the display may glide between them; otherwise it snaps.
     */
    fun sameLines(from: LaneLayout, to: LaneLayout): Boolean {
        if (from.lines.size != to.lines.size) return false
        if (abs(from.vpX - to.vpX) > 60.0 || abs(from.vpY - to.vpY) > 60.0) return false
        val n = to.lines.size
        for (i in 0 until n) {
            val s = to.lines[i].slope
            val gapLeft = if (i > 0) s - to.lines[i - 1].slope else Double.MAX_VALUE
            val gapRight = if (i < n - 1) to.lines[i + 1].slope - s else Double.MAX_VALUE
            if (abs(from.lines[i].slope - s) > min(gapLeft, gapRight) / 3) return false
        }
        return true
    }

    /**
     * How [to]'s lines are numbered against [from]'s, when the set of lines changed (a line gained or lost at an
     * edge): d such that line i of [from] is line i + d of [to]. It is the shift, with at least two lines in common,
     * whose common lines differ least in slope on average; null when even that is more than a third of [to]'s mean
     * lane (a different road).
     */
    fun lineOffset(from: LaneLayout, to: LaneLayout): Int? {
        if (from.lines.size < 2 || to.lines.size < 2) return null
        val lane = (to.lines.last().slope - to.lines.first().slope) / (to.lines.size - 1)
        var best: Int? = null
        var bestCost = Double.MAX_VALUE
        for (d in -(from.lines.size - 2)..(to.lines.size - 2)) {
            var sum = 0.0
            var n = 0
            for (i in from.lines.indices) {
                if (i + d !in to.lines.indices) continue
                sum += abs(from.lines[i].slope - to.lines[i + d].slope)
                n++
            }
            if (n >= 2 && sum / n < bestCost) { bestCost = sum / n; best = d }
        }
        return best.takeIf { bestCost <= lane / 3 }
    }

    /** [from] moved towards [to] by [t] in 0..1; lane numbers, quality and age are [to]'s. */
    fun blend(from: LaneLayout, to: LaneLayout, t: Double): LaneLayout = to.copy(
        vpX = from.vpX + (to.vpX - from.vpX) * t,
        vpY = from.vpY + (to.vpY - from.vpY) * t,
        lines = to.lines.mapIndexed { i, l -> l.copy(slope = from.lines[i].slope + (l.slope - from.lines[i].slope) * t) },
        nearestRowY = from.nearestRowY + (to.nearestRowY - from.nearestRowY) * t,
    )

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

    /** Smooth pulse starting fully on: 1.0 -> 0.25 -> 1.0 every [BLINK_PERIOD_S]. */
    fun blink(sinceS: Double): Float = (0.625 + 0.375 * cos(2 * PI * sinceS / BLINK_PERIOD_S)).toFloat()

    // --- Glyph outlines -------------------------------------------------------------------------
    // Drawn in metres in a nominal lane (u across, + right of the midline; v along, 0..LENGTH_M) so the stroke
    // keeps its width through bends, then stored as lane fractions (s = u / NOMINAL_W_M, t = v / LENGTH_M).

    private fun build(glyph: LaneArrowGlyph): Outline {
        val right = when (glyph) {
            LaneArrowGlyph.STRAIGHT -> stroke(listOf(0.0 to 0.0, 0.0 to LENGTH_M - HEAD_LEN_M), headHalf = HEAD_HALF_M, headLen = HEAD_LEN_M)
            LaneArrowGlyph.TURN_LEFT, LaneArrowGlyph.TURN_RIGHT -> turn()
            LaneArrowGlyph.BEAR_LEFT, LaneArrowGlyph.BEAR_RIGHT -> bear()
        }
        val mirrored = glyph == LaneArrowGlyph.TURN_LEFT || glyph == LaneArrowGlyph.BEAR_LEFT
        val pts = if (mirrored) right.map { -it.first to it.second } else right
        return densify(pts)
    }

    /**
     * Shaft up the lane, a quarter bend to the right, head pointing sideways (a US turn-lane marking). The
     * head is long along the lane (2.3 m): seen from the driver's seat, depth is foreshortened ~10x. Its tip
     * is 1.4 m right of the midline (s 0.4).
     */
    private fun turn(): List<Pair<Double, Double>> {
        val u0 = -0.65
        val r = 0.85
        val headHalf = 1.15
        val vb = LENGTH_M - headHalf - r
        val centre = ArrayList<Pair<Double, Double>>()
        centre += u0 to 0.0
        centre += u0 to vb
        val n = 8
        for (i in 1..n) {
            val th = PI - (PI / 2) * i / n
            centre += (u0 + r + r * cos(th)) to (vb + r * sin(th))
        }
        centre += (u0 + r + 0.1) to (vb + r)
        return stroke(centre, headHalf = headHalf, headLen = 1.1)
    }

    /** Shaft up the lane, then a diagonal to the right with the head along it. */
    private fun bear(): List<Pair<Double, Double>> {
        val u0 = -0.55
        val du = 1.0 / sqrt(5.0)
        val dv = 2.0 / sqrt(5.0)
        val headLen = 1.9
        val diag = 1.2
        val vk = LENGTH_M - 0.06 - (diag + headLen) * dv
        return stroke(listOf(u0 to 0.0, u0 to vk, (u0 + diag * du) to (vk + diag * dv)), headHalf = HEAD_HALF_M, headLen = headLen)
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
        val h = SHAFT_HALF_M
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

    /**
     * Closed polygon with no edge longer than [STEP_M] in the nominal lane, so it bends with the perspective
     * (a diagonal in lane coordinates is a curve on the image), stored as lane fractions.
     */
    private fun densify(pts: List<Pair<Double, Double>>): Outline {
        val ss = ArrayList<Double>()
        val ts = ArrayList<Double>()
        for (i in pts.indices) {
            val (u0, v0) = pts[i]
            val (u1, v1) = pts[(i + 1) % pts.size]
            val steps = max(1, ceil(hypot(u1 - u0, v1 - v0) / STEP_M).toInt())
            for (k in 0 until steps) {
                val f = k.toDouble() / steps
                ss += (u0 + (u1 - u0) * f) / NOMINAL_W_M
                ts += ((v0 + (v1 - v0) * f) / LENGTH_M).coerceIn(0.0, 1.0)
            }
        }
        return Outline(ss.toDoubleArray(), ts.toDoubleArray())
    }

    internal fun fmt(x: Double, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", x)
}

/**
 * Chooses, styles and fades the lane arrows at display rate. Owned by [ArSceneBuilder]; every piece of
 * presentation state (display-smoothed layout, wrong-lane debounce, blink phase, per-lane fades) lives here
 * and runs on the display clock (`nowNs`).
 *
 * With a usable layout (see [LaneArrows.usableLayout]): one arrow in every visible lane whose arrow centre is
 * on screen, keyed by the painted lane: a line gained or lost at an edge renumbers the lanes, and each arrow's
 * fade stays with its lane ([LaneArrows.lineOffset]). The target lanes are the lane guidance's (KEEP_LANE /
 * CHANGE_LANE_* with targets among the visible lanes, numbered on as many lanes as are shown); with no lane
 * guidance there is no lane requirement and the car's lane is the lane to drive. Lane guidance UNKNOWN is a lane
 * requirement that cannot be resolved: nothing green then, every lane faint white. Targets are green, blinking
 * while the car is in another lane, whose arrow is then red (after the debounce); every other lane is faint white.
 * Without a usable layout: only the car's lane arrow, laid along the camera's ground track ([LaneArrows.trackX]),
 * never red: unknown stays unknown. It is green with KEEP_LANE or no lane guidance, and faint white and straight
 * while the guidance asks for a lane change (the car's lane is then not a route lane), is UNKNOWN, or the wrong-lane
 * look is held. With a drivable road outline (`road.drivablePolygon`) every arrow starts above the road's bottom edge
 * in its lane, and one fading out on its old strip is drawn only while its near end is on the new outline
 * ([LaneArrows.nearEndOnRoad]). A fresh road run with an empty outline (the server sees no road) draws no arrow at
 * all ([LaneArrows.roadHidden]).
 */
class LaneArrowsBuilder {
    private class Shown(var strip: LaneStrip, var style: LaneArrowStyle, var glyph: LaneArrowGlyph, var alpha: Float, var target: Float, var lane: Int?)

    private class Want(val strip: LaneStrip, val style: LaneArrowStyle, val glyph: LaneArrowGlyph, val lane: Int?)

    /** Key: the painted lane ([keys]), or [EGO_KEY] for the lone arrow without a layout. */
    private val shown = LinkedHashMap<Int, Shown>()
    /** The usable layout as displayed: glides towards each new perception result ([LaneArrows.blend]). */
    private var layout: LaneLayout? = null
    /** The layout displayed last (kept over gaps) and the key of each of its lanes, left to right. */
    private var keyed: LaneLayout? = null
    private var keys = IntArray(0)
    private var nextKey = EGO_KEY + 1
    /** Base opacity, ramped between [LaneArrows.IDLE_ALPHA] and [LaneArrows.ACTIVE_ALPHA]; NaN = not shown yet. */
    private var base = Float.NaN
    private var wrongLook = false
    private var pendingNs = NONE
    private var choiceLostNs = NONE
    private var skewSinceNs = NONE
    private var blinkStartNs = 0L
    /** This session's server sends drivable outlines (one has come): an empty one then means no road ([LaneArrows.roadHidden]). */
    private var outlinesSeen = false

    /** Clears the presentation state; what is known about the server ([outlinesSeen]) stays. */
    fun reset() {
        shown.clear()
        layout = null
        keyed = null
        keys = IntArray(0)
        base = Float.NaN
        wrongLook = false
        pendingNs = NONE
        choiceLostNs = NONE
        skewSinceNs = NONE
    }

    fun build(input: ArInput, projector: GroundProjector, map: FillCenter, nowNs: Long, dt: Double): LaneArrowFrame {
        val world = input.world
        val route = input.route
        val showable = route != null && !route.stale && !route.offRoute && !world.perceptionStale

        var reason: String? = if (world.perceptionStale) "stale" else null
        val measured = if (world.perceptionStale) null else LaneArrows.usableLayout(world) { reason = it }
        val guidance = input.context.laneGuidance
            ?.takeIf { it.action == LaneAction.KEEP_LANE || it.action == LaneAction.CHANGE_LANE_LEFT || it.action == LaneAction.CHANGE_LANE_RIGHT }
        // A lane requirement the Driving Context cannot resolve to lanes: the car's lane is not known to be a route lane.
        val unresolved = input.context.laneGuidance?.action == LaneAction.UNKNOWN
        // SIM: the Driving Context runs on the 50 ms tick's snapshot, the canvas at the player's position, up to a lanes
        // run apart. While their lane counts differ, the display keeps the layout the targets were numbered on (0.4 s at most).
        val ctxCount = guidance?.laneCount
        val skewed = measured != null && ctxCount != null && ctxCount != measured.laneCount
        if (!skewed) skewSinceNs = NONE else if (skewSinceNs == NONE) skewSinceNs = nowNs
        val prev = layout
        val hold = skewed && prev != null && prev.laneCount == ctxCount && nowNs - skewSinceNs <= CHOICE_HOLD_NS
        val glide = !hold && prev != null && measured != null && LaneArrows.sameLines(prev, measured)
        layout = when {
            measured == null -> null
            hold -> prev
            glide -> LaneArrows.blend(prev!!, measured, 1.0 - exp(-dt / LaneArrows.LAYOUT_TAU_S))
            else -> measured
        }
        val lay = layout
        if (lay != null) { if (hold || glide) keyed = lay else rekey(lay) }

        val changeLane = guidance?.action == LaneAction.CHANGE_LANE_LEFT || guidance?.action == LaneAction.CHANGE_LANE_RIGHT
        // Targets numbered on another lane count would land on the wrong lanes: none then (the lane choice is lost).
        val choiceTargets = if (lay != null && guidance != null && (ctxCount == null || ctxCount == lay.laneCount)) {
            guidance.targetLanes.filter { it in 1..lay.laneCount }.distinct()
        } else emptyList()
        val choice = showable && lay != null && choiceTargets.isNotEmpty()
        debounce(choice, choice && lay!!.egoLane !in choiceTargets, nowNs)

        val distance = input.routeDistanceMeters
        val targetGlyph = LaneArrows.targetGlyph(route, distance)
        val road = LaneArrows.drivableRoad(world)
        if (road != null) outlinesSeen = true
        val noRoad = LaneArrows.roadHidden(world, outlinesSeen)
        val imageH = projector.imageHeight.toDouble()
        val wanted = HashMap<Int, Want>()
        if (showable && !noRoad) {
            if (lay != null) {
                // No lane guidance: the car's lane is the lane to drive. Lane guidance without a visible target or UNKNOWN,
                // or the wrong-lane look held over a gap in the lane choice: nothing green, and nothing red.
                val targets = when {
                    choice -> choiceTargets
                    guidance != null || unresolved || wrongLook -> emptyList()
                    else -> listOf(lay.egoLane)
                }
                for (k in 1..lay.laneCount) {
                    val strip = LaneArrows.strip(lay, k)?.let { LaneArrows.onRoad(it, road, imageH) } ?: continue
                    val key = keys.getOrNull(k - 1) ?: continue
                    val style = when {
                        k in targets -> if (choice && wrongLook) LaneArrowStyle.TARGET_BLINK else LaneArrowStyle.TARGET
                        choice && wrongLook && k == lay.egoLane -> LaneArrowStyle.WRONG
                        else -> LaneArrowStyle.OTHER
                    }
                    val glyph = if (style == LaneArrowStyle.TARGET || style == LaneArrowStyle.TARGET_BLINK) targetGlyph else LaneArrowGlyph.STRAIGHT
                    wanted[key] = Want(strip, style, glyph, k)
                }
            } else {
                // The Driving Context says the car's lane is not a route lane or cannot tell (UNKNOWN), or the wrong-lane look
                // is held over a short gap: no green, no maneuver shape. Never red either, the lanes are unknown here.
                val strip = LaneArrows.onRoad(LaneArrows.axisStrip(world, projector), road, imageH)
                if (strip != null) {
                    wanted[EGO_KEY] = if (changeLane || unresolved || wrongLook) Want(strip, LaneArrowStyle.OTHER, LaneArrowGlyph.STRAIGHT, null)
                    else Want(strip, LaneArrowStyle.TARGET, targetGlyph, null)
                }
            }
        }
        val glideT = 1.0 - exp(-dt / LaneArrows.LAYOUT_TAU_S)
        for ((key, w) in wanted) {
            val s = shown.getOrPut(key) { Shown(w.strip, w.style, w.glyph, 0f, 1f, w.lane) }
            // The road's bottom edge comes from each segmentation run: it glides like the layout, so the near end does not jitter.
            val was = s.strip.roadBottomY
            val now = w.strip.roadBottomY
            s.strip = if (was != null && now != null) w.strip.copy(roadBottomY = was + (now - was) * glideT) else w.strip
            s.style = w.style
            s.glyph = w.glyph
            s.lane = w.lane
            s.target = 1f
        }
        val step = (dt / LaneArrows.FADE_S).toFloat()
        val iter = shown.entries.iterator()
        while (iter.hasNext()) {
            val (key, s) = iter.next()
            if (key !in wanted) {
                // Fades out where it was drawn last. No red lingers once the layout is unusable or perception is stale, and
                // no green once the lane requirement is UNKNOWN.
                s.target = 0f
                if (s.style == LaneArrowStyle.WRONG) s.style = LaneArrowStyle.OTHER
                if (unresolved && s.style != LaneArrowStyle.OTHER) { s.style = LaneArrowStyle.OTHER; s.glyph = LaneArrowGlyph.STRAIGHT }
            }
            s.alpha = if (s.alpha < s.target) min(s.target, s.alpha + step) else max(s.target, s.alpha - step)
            if (s.alpha <= 0f && s.target == 0f) iter.remove()
        }

        // The emphasis fades too: no 0.9 <-> 0.55 jump when the lane choice comes and goes.
        val maneuverNear = route != null && route.maneuver != Maneuver.FOLLOW_ROAD && distance != null && distance <= LaneArrows.ACTIVE_M
        val baseTarget = if (choice || maneuverNear) LaneArrows.ACTIVE_ALPHA else LaneArrows.IDLE_ALPHA
        val baseStep = step * (LaneArrows.ACTIVE_ALPHA - LaneArrows.IDLE_ALPHA)
        base = when {
            base.isNaN() -> baseTarget
            base < baseTarget -> min(baseTarget, base + baseStep)
            else -> max(baseTarget, base - baseStep)
        }
        val pulse = LaneArrows.blink((nowNs - blinkStartNs) / 1e9)
        val arrows = ArrayList<LaneArrow>(shown.size)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        // No road seen: nothing is drawn, not even the fades (they run on, so a one-run gap does not restart them from 0).
        if (!noRoad) for ((key, s) in shown) {
            val span = LaneArrows.span(s.strip, projector) ?: continue
            // Fading out where it was placed: not once a newer outline puts its near end off the road.
            if (key !in wanted && road != null && !LaneArrows.nearEndOnRoad(s.strip, span, road, projector)) continue
            val c = LaneArrows.center(s.strip, span, projector, map)
            if (c.x < 0f || c.x > map.viewWidth || c.y < 0f || c.y > map.viewHeight) continue
            val factor = when (s.style) {
                LaneArrowStyle.OTHER -> LaneArrows.OTHER_FACTOR
                LaneArrowStyle.TARGET_BLINK -> pulse
                else -> 1f
            }
            val alpha = s.alpha * base * factor
            if (alpha <= 0.01f) continue
            val outline = LaneArrows.project(s.glyph, s.strip, span, projector, map)
            for (p in outline) {
                if (p.x < minX) minX = p.x
                if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y
                if (p.y > maxY) maxY = p.y
            }
            arrows += LaneArrow(s.lane, s.style, s.glyph, outline, alpha)
        }
        val occluders = if (arrows.isEmpty()) emptyList() else occluders(world, map, ViewRect(minX, minY, maxX, maxY))
        val drivable = if (arrows.isEmpty() || road == null) emptyList() else road.map { map.point(it[0], it[1]) }
        val status = status(lay, reason, guidance, unresolved, choiceTargets) + if (noRoad) " | no road" else ""
        return LaneArrowFrame(arrows, occluders, drivable, status, lay)
    }

    /**
     * Keys [to]'s lanes after a changed set of lines: a lane that was in the layout displayed last keeps its key
     * (matched by [LaneArrows.lineOffset]; the same lines give the same keys), a new one gets a new key. An arrow
     * whose lane left the layout is no lane of it any more and fades out where it was drawn.
     */
    private fun rekey(to: LaneLayout) {
        val d = keyed?.let { LaneArrows.lineOffset(it, to) }
        val next = IntArray(to.laneCount) { i -> if (d != null && i - d in keys.indices) keys[i - d] else nextKey++ }
        for (k in keys) if (k !in next) shown[k]?.lane = null
        keyed = to
        keys = next
    }

    /**
     * Wrong-lane look: on after 0.8 s of "the car's lane not in the targets", off 0.3 s after reaching a target.
     * A lane choice lost for up to 0.4 s (one poor lanes run) keeps the look and pauses the timer, so unknown
     * time never counts as wrong-lane time; nothing red is drawn meanwhile.
     */
    private fun debounce(choice: Boolean, rawWrong: Boolean, nowNs: Long) {
        if (!choice) {
            if (choiceLostNs == NONE) choiceLostNs = nowNs
            if (nowNs - choiceLostNs > CHOICE_HOLD_NS) { wrongLook = false; pendingNs = NONE }
            return
        }
        if (choiceLostNs != NONE) {
            if (pendingNs != NONE) pendingNs += nowNs - choiceLostNs
            choiceLostNs = NONE
        }
        if (rawWrong == wrongLook) {
            pendingNs = NONE
            return
        }
        if (pendingNs == NONE) pendingNs = nowNs
        val need = if (wrongLook) LaneArrows.RIGHT_AFTER_S else LaneArrows.WRONG_AFTER_S
        if ((nowNs - pendingNs) / 1e9 >= need - 1e-9) {
            wrongLook = rawWrong
            pendingNs = NONE
            if (wrongLook) blinkStartNs = nowNs
        }
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

    private fun status(layout: LaneLayout?, reason: String?, guidance: LaneGuidance?, unresolved: Boolean, targets: List<Int>): String {
        if (layout == null) return if (reason == null) "layout unknown" else "layout unknown: $reason"
        return buildString {
            append("layout ").append(layout.egoLane).append('/').append(layout.laneCount)
            append(" q").append(LaneArrows.fmt(layout.quality, 2))
            if (guidance != null) append(" targets ").append(if (targets.isEmpty()) "-" else targets.joinToString(","))
            else if (unresolved) append(" targets ?")
            if (wrongLook) append(" WRONG")
        }
    }

    companion object {
        const val EGO_KEY = 0
        private const val NONE = Long.MIN_VALUE
        private const val CHOICE_HOLD_NS = 400_000_000L
    }
}
