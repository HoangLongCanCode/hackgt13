package com.drivingassist.copilot.context

import com.drivingassist.copilot.perception.Camera
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * One lane line in analysed-image pixels, as a ray through the layout's vanishing point:
 * `x(y) = vpX + slope * (y - vpY)`. [detected] is false for a line inserted between two detected lines
 * that are two lane widths apart (a missed dashed line). [color] is the paint colour from the server's
 * `lanes.boundaryColors` ([YELLOW] / [WHITE] / [UNKNOWN]; [UNKNOWN] on an inserted line), null when the
 * server sent no colours.
 */
@Serializable
data class LaneLine(val slope: Double, val detected: Boolean = true, val color: String? = null) {
    companion object {
        const val YELLOW = "yellow"
        const val WHITE = "white"
        const val UNKNOWN = "unknown"

        /** Wire colour -> [YELLOW] / [WHITE] / [UNKNOWN] (anything else is unknown); null stays null. */
        fun colorOf(wire: String?): String? = when (wire?.trim()?.lowercase()) {
            null -> null
            YELLOW -> YELLOW
            WHITE -> WHITE
            else -> UNKNOWN
        }

        /** Colour of two lines merged into one: yellow wins, then white; unknown if either had a colour. */
        fun mergedColor(a: String?, b: String?): String? = when {
            a == YELLOW || b == YELLOW -> YELLOW
            a == WHITE || b == WHITE -> WHITE
            a != null || b != null -> UNKNOWN
            else -> null
        }
    }
}

/**
 * The lanes the car can see, built from the lane model's detected line polylines (`lanes.laneBoundaries`)
 * rather than its lane numbers: every line is fitted through one vanishing point, so all lanes point where
 * the painted lines meet, whatever the camera's yaw. Lane k (1-based from the left of the visible lanes)
 * lies between [lines] `k-1` and `k`. [egoLane] is the lane under the camera's ground track (the vertical
 * image line through the vanishing point, zero roll).
 */
@Serializable
data class LaneLayout(
    val vpX: Double,
    val vpY: Double,
    /** Left to right, at least two. */
    val lines: List<LaneLine>,
    /** The car's lane, 1-based from the left of the visible lanes. */
    val egoLane: Int,
    /** Lowest image row a detected line reached. */
    val nearestRowY: Double,
    /** 0..1: how much of the layout is detected lines, and how stable it has been. */
    val quality: Double,
    /** Media time of the lanes run the layout comes from. */
    val measuredPts: Double,
    val ageSeconds: Double = 0.0,
    /**
     * Usable for lane numbers, arrows, the own-lane lead and the lane voice, with hysteresis over runs (the
     * WorldModel sets it, see `WorldModelConfig.laneLayoutStableQuality`; a layout built on its own, e.g. by [from],
     * uses the plain threshold). Consumers check `stable && ageSeconds <= MAX_USABLE_AGE_SECONDS` ([stableAndFresh]).
     */
    val stable: Boolean = quality >= USABLE_QUALITY && ageSeconds <= MAX_USABLE_AGE_SECONDS,
    /** Camera height (m) the layout's widths were measured with (smoothed, clamped); the AR projects with the same. */
    val cameraHeightMeters: Double? = null,
    /**
     * Lane 1's left line is the yellow left edge of our direction of travel: the oncoming cut ran, and the yellow line
     * nearest the car's track is [lines] 0 or lies less than [LaneLayoutParams.minLaneWidthMeters] left of it (a
     * painted median; a narrower gap that still bounds a lane, see [LaneLayoutParams.narrowLaneFraction], puts the
     * yellow line into [lines] as line 0). See [leftmostLaneIsOurs].
     */
    val yellowLeftEdge: Boolean = false,
    /**
     * Lane 1's left line is where the WorldModel last saw the yellow left edge of our direction, at most
     * `WorldModelConfig.laneYellowEdgeMemorySeconds` ago, followed from run to run while it read white (see
     * `WorldModel.followYellowEdge`). Never set by [from] (one run has no history). See [leftmostLaneIsOurs].
     */
    val recentYellowLeftEdge: Boolean = false,
) {
    val laneCount: Int get() = lines.size - 1

    /** x of line [index] (0-based, left to right) at image row [y]. */
    fun lineX(index: Int, y: Double): Double = vpX + lines[index].slope * (y - vpY)

    /** 1-based lane containing image point ([x], [y]); null at or above the vanishing point or outside the visible lanes. */
    fun laneAt(x: Double, y: Double): Int? {
        if (y <= vpY + 1.0 || lines.size < 2) return null
        for (i in 0 until lines.lastIndex) {
            if (x >= lineX(i, y) && x < lineX(i + 1, y)) return i + 1
        }
        return null
    }

    /**
     * This layout on its own clears the plain bar: at least [minQuality] and at most [maxAgeSeconds] old. The lane
     * numbers, the own-lane lead and the arrows use [stableAndFresh] (the WorldModel's hysteresis) instead.
     */
    fun usable(minQuality: Double = USABLE_QUALITY, maxAgeSeconds: Double = MAX_USABLE_AGE_SECONDS): Boolean =
        quality >= minQuality && ageSeconds <= maxAgeSeconds

    /** Good enough to number lanes and pick the own-lane lead: [stable] and at most [maxAgeSeconds] old. */
    fun stableAndFresh(maxAgeSeconds: Double = MAX_USABLE_AGE_SECONDS): Boolean = stable && ageSeconds <= maxAgeSeconds

    /**
     * Lane 1 is the leftmost lane of OUR direction of travel, the target of an inferred left turn:
     * 1. its left line is the yellow left edge ([yellowLeftEdge]); or
     * 2. its left line is where that yellow edge was seen recently ([recentYellowLeftEdge]): the edge read white for a
     *    while, e.g. on real_009 at 8-16 s, where a left-turn bay opened next to the yellow curb line and the server read
     *    it white for about 8 s. A line a lane or more left of the remembered edge (the oncoming side of a centre line
     *    read white) is not at the edge, so its lane is not taken; or
     * 3. no line carries a colour at all (the server sent none: nothing tells the lanes apart, lane 1 as before colours).
     * With colours but none of these (the centre line read white or unknown for longer than the memory, the centre line
     * hidden behind the A-pillar or out of view) lane 1 may be an oncoming lane: false. "No line right of line 0 was
     * ever yellow" is deliberately not enough on its own: a centre line read white on every run (the two-way test road in
     * OwnLaneGuidanceTest) would make the oncoming lane the target, and the line tracks' own colour memory is not either:
     * on real_009 at 8.2 s, as the bay opened, the yellow edge's track moved onto the new dashed bay line.
     */
    fun leftmostLaneIsOurs(): Boolean = yellowLeftEdge || recentYellowLeftEdge || lines.all { it.color == null }

    companion object {
        /** [quality] from which a layout is "usable" (lane numbers, own-lane lead). Placeholder. */
        const val USABLE_QUALITY = 0.4

        /** A held layout older than this is not used for lane numbers or the lead. Placeholder. */
        const val MAX_USABLE_AGE_SECONDS = 1.0

        /**
         * Layout of one lanes run of [world] (`lanes.laneBoundaries`, `road.vanishingPoint` / `horizonY`, `camera`,
         * `image`), or null when its lines do not make a lane around the car. Pure and deterministic.
         *
         * 1. Every polyline with >= 2 points spanning >= [LaneLayoutParams.minLineSpanPx] rows is fitted as a straight
         *    image line `x = a + b * y` (least squares).
         * 2. Vanishing point: `road.vanishingPoint`, else a robust intersection of the fitted lines (pairwise
         *    intersections inside the image rows [LaneLayoutParams.vpMinRowFraction]..[LaneLayoutParams.vpMaxRowFraction]
         *    and above both lines' lowest points; the one most lines agree with, refined by a median), else
         *    (principal x, horizon).
         * 3. Lines whose extension misses the vanishing point by more than [LaneLayoutParams.vpToleranceFraction] of the
         *    image width at its row (crosswalk / stop bars, curbs, lines of another road) are dropped; the rest are
         *    re-fitted through it.
         * 4. Flat-ground model with the vanishing-point row as horizon ([FlatGround]): each line's lateral offset (metres
         *    across the road) at [LaneLayoutParams.referenceDepthMeters]. Lines closer than [LaneLayoutParams.mergeMeters]
         *    are merged; a merged line keeps the more telling colour (yellow wins). Colours come from
         *    `lanes.boundaryColors`, used only when it has one entry per polyline.
         * 5. Oncoming road: of the lines left of the camera's ground track, the yellow one nearest the track is the left
         *    edge of our direction of travel (US); every line left of it (median, oncoming lanes) is dropped. A yellow
         *    line right of the track is ignored. Without colours nothing is cut. [yellowLeftEdge] when the cut line
         *    bounds lane 1 (see there).
         * 6. Neighbours [LaneLayoutParams.minLaneWidthMeters]..[LaneLayoutParams.maxLaneWidthMeters] apart bound a lane;
         *    a narrower gap also does when it is at least [LaneLayoutParams.narrowLaneFraction] of the median width of
         *    those lanes and at least [LaneLayoutParams.minNarrowLaneWidthMeters] (real_011 at 42-46 s: a 2.2 m left-turn
         *    bay next to 3.3 m lanes; double yellow lines and painted medians, about 1.2 m, stay out); up to
         *    [LaneLayoutParams.maxTwoLaneWidthMeters] apart they get one virtual line in the middle (a missed dashed
         *    line); any other gap splits the lines, and only the connected run of lanes around the car is kept.
         * 7. [egoLane]: the lane containing the camera's ground track (x = vpX, i.e. slope 0). None there: null.
         *
         * [quality] = (detected lines / lines) * min(1, mean vertical span of the detected lines /
         * ([LaneLayoutParams.fullLengthFraction] * rows below the vanishing point)) * min(1, `lanes.confidence` +
         * [LaneLayoutParams.confidenceBoost]). The server's confidence is only a soft factor: on the recorded city drive
         * its median was 0.36 while the lines themselves were good. >= [USABLE_QUALITY] is usable.
         */
        fun from(world: WorldSnapshot, params: LaneLayoutParams = LaneLayoutParams()): LaneLayout? =
            measure(world, params)?.let {
                build(it.vpX, it.vpY, it.metersPerSlope, it.lines, it.imageHeight, it.confidence, it.measuredPts, it.ageSeconds, params)
                    ?.copy(cameraHeightMeters = it.heightMeters)
            }

        /** Steps 1-4 of [from]: the run's lines through one vanishing point with their lateral offsets, merged; null with fewer than 2. */
        internal fun measure(world: WorldSnapshot, params: LaneLayoutParams = LaneLayoutParams()): LaneRun? {
            val lanesState = world.lanes ?: return null
            val image = world.image ?: return null
            val camera = world.camera?.takeIf { FlatGround.usable(it) } ?: return null
            if (image.width <= 0 || image.height <= 0) return null
            val width = image.width.toDouble()
            val height = image.height.toDouble()
            val lanes = lanesState.lanes
            // Colours only when the array lines up with the polylines (index i describes laneBoundaries[i]).
            val colors = lanes.boundaryColors?.takeIf { it.size == lanes.laneBoundaries.size }
            val fits = lanes.laneBoundaries.mapIndexedNotNull { i, p -> fitLine(p, params.minLineSpanPx, LaneLine.colorOf(colors?.get(i))) }
            if (fits.size < 2) return null

            val road = world.road?.road
            val (vpX, vpY) = road?.vanishingPoint?.takeIf { it.size >= 2 && it[0].isFinite() && it[1].isFinite() }?.let { it[0] to it[1] }
                ?: intersection(fits, width, height, params)
                ?: (camera.cx to (road?.horizonY ?: camera.horizonY ?: camera.cy))
            if (vpY >= height - MIN_ROAD_ROWS_PX) return null

            val tolerance = params.vpToleranceFraction * width
            val throughVp = fits
                .filter { it.maxY >= vpY + MIN_BELOW_VP_PX && abs(it.xAt(vpY) - vpX) <= tolerance }
                .mapNotNull { it.slopeThrough(vpX, vpY) }
            if (throughVp.size < 2) return null

            val metersPerSlope = metersPerSlope(vpX, vpY, camera, height, params) ?: return null
            val merged = ArrayList<LaneCandidate>()
            for (c in throughVp.map { LaneCandidate(it.slope, it.slope * metersPerSlope, true, it.maxY, it.span, it.weight, it.color) }.sortedBy { it.lateral }) {
                val last = merged.lastOrNull()
                if (last != null && c.lateral - last.lateral < params.mergeMeters) merged[merged.lastIndex] = last.mergedWith(c, metersPerSlope) else merged += c
            }
            val heightMeters = FlatGround.heightOf(camera, params.plausibleCameraHeightMeters, params.defaultCameraHeightMeters)
            return LaneRun(vpX, vpY, metersPerSlope, heightMeters, merged, height, lanes.confidence, lanesState.measuredPts, lanesState.ageSeconds)
        }

        /**
         * Metres across the road per unit of slope (`x = vpX + slope * (y - vpY)`) at the reference row
         * ([LaneLayoutParams.referenceDepthMeters]) for the vanishing point ([vpX], [vpY]) and [camera] (flat ground,
         * the vanishing-point row as horizon); null when there is no road there.
         */
        internal fun metersPerSlope(vpX: Double, vpY: Double, camera: Camera, imageHeight: Double, params: LaneLayoutParams = LaneLayoutParams()): Double? {
            val ground = FlatGround(camera.focalPx, camera.cx, camera.cy, vpY, FlatGround.heightOf(camera, params.plausibleCameraHeightMeters, params.defaultCameraHeightMeters))
            val refRow = ground.rowAt(params.referenceDepthMeters).coerceIn(vpY + MIN_REF_ROWS_PX, imageHeight)
            val refZ = ground.forwardAtRow(refRow) ?: return null
            return ((refRow - vpY) * ground.metersPerPixelAt(refZ) * ground.cosYaw(vpX)).takeIf { it > 0.0 }
        }

        /**
         * Steps 5-7 of [from] and the quality, on [lines] (left to right, merged; lateral = slope * [metersPerSlope]):
         * the oncoming cut, lanes, gaps and virtual lines, the ego lane (left of the track = [LaneCandidate.left]). Null
         * without a lane around the car. The WorldModel builds its layout from its tracked lines with it: two lines that
         * were neighbours with the same number of lanes between them in its last layout ([LaneCandidate.rightNeighbour])
         * keep them while their gap stays within [gapHysteresisMeters] of the limits (no flicker at a limit).
         * [oncomingCut]: lateral of the yellow line that is the left edge of our direction (default: from [lines]; the
         * WorldModel passes the one of all its tracks, also when that line itself is left out as a stray).
         */
        internal fun build(
            vpX: Double,
            vpY: Double,
            metersPerSlope: Double,
            lines: List<LaneCandidate>,
            imageHeight: Double,
            confidence: Double,
            measuredPts: Double,
            ageSeconds: Double,
            params: LaneLayoutParams = LaneLayoutParams(),
            gapHysteresisMeters: Double = 0.0,
            oncomingCut: Double? = lines.filter { it.left && it.color == LaneLine.YELLOW }.maxOfOrNull { it.lateral },
        ): LaneLayout? {
            val kept = if (oncomingCut == null) lines else lines.filter { it.lateral >= oncomingCut }
            val virtualColor = if (kept.any { it.color != null }) LaneLine.UNKNOWN else null
            val minLane = minLaneWidth(kept, params)

            /** Lanes between neighbouring lines [a] and [b] (0 = they do not bound lanes together). */
            fun lanesBetween(a: LaneCandidate, b: LaneCandidate): Int {
                val gap = b.lateral - a.lateral
                val h = gapHysteresisMeters
                if (h > 0.0 && a.rightNeighbour >= 0 && a.rightNeighbour == b.trackId) {
                    if (a.lanesToRight == 1 && gap >= minLane - h && gap <= params.maxLaneWidthMeters + h) return 1
                    if (a.lanesToRight == 2 && gap >= params.maxLaneWidthMeters - h && gap <= params.maxTwoLaneWidthMeters + h) return 2
                }
                return when {
                    gap < minLane || gap > params.maxTwoLaneWidthMeters -> 0
                    gap <= params.maxLaneWidthMeters -> 1
                    else -> 2
                }
            }

            val runs = ArrayList<ArrayList<LaneCandidate>>()
            for (c in kept) {
                val run = runs.lastOrNull()
                when (if (run == null) 0 else lanesBetween(run.last(), c)) {
                    0 -> runs += arrayListOf(c)
                    1 -> run!! += c
                    else -> {
                        val slope = round4((run!!.last().slope + c.slope) / 2.0)
                        run += LaneCandidate(slope, slope * metersPerSlope, false, Double.NaN, 0.0, 0, virtualColor)
                        run += c
                    }
                }
            }
            var run: List<LaneCandidate>? = null
            var ego = 0
            for (r in runs) {
                val i = (0 until r.lastIndex).firstOrNull { r[it].left && !r[it + 1].left } ?: continue
                run = r; ego = i + 1
                break
            }
            if (run == null) return null

            val detected = run.filter { it.detected }
            val share = detected.size.toDouble() / run.size
            val lengthFactor = (detected.map { it.span }.average() / (params.fullLengthFraction * (imageHeight - vpY))).coerceIn(0.0, 1.0)
            val confidenceFactor = (confidence + params.confidenceBoost).coerceIn(0.0, 1.0)
            return LaneLayout(
                vpX = round1(vpX),
                vpY = round1(vpY),
                lines = run.map { LaneLine(it.slope, it.detected, it.color) },
                egoLane = ego,
                nearestRowY = round1(detected.maxOf { it.maxY }.coerceAtMost(imageHeight)),
                quality = round3(share * lengthFactor * confidenceFactor),
                measuredPts = measuredPts,
                ageSeconds = ageSeconds,
                yellowLeftEdge = oncomingCut != null && run.first().lateral - oncomingCut < params.minLaneWidthMeters,
            )
        }

        /**
         * Narrowest gap between neighbouring [lines] that bounds a lane (step 6 of [from]): the median width of the lanes
         * [LaneLayoutParams.minLaneWidthMeters]..[LaneLayoutParams.maxLaneWidthMeters] wide times
         * [LaneLayoutParams.narrowLaneFraction], at least [LaneLayoutParams.minNarrowLaneWidthMeters], at most
         * [LaneLayoutParams.minLaneWidthMeters]; [LaneLayoutParams.minLaneWidthMeters] when no gap is such a lane (a
         * narrow gap needs a regular lane to compare with).
         */
        internal fun minLaneWidth(lines: List<LaneCandidate>, params: LaneLayoutParams = LaneLayoutParams()): Double {
            val lanes = lines.zipWithNext { a, b -> b.lateral - a.lateral }
                .filterTo(ArrayList()) { it >= params.minLaneWidthMeters && it <= params.maxLaneWidthMeters }
            if (lanes.isEmpty()) return params.minLaneWidthMeters
            return (params.narrowLaneFraction * RobustFit.median(lanes)).coerceIn(params.minNarrowLaneWidthMeters, params.minLaneWidthMeters)
        }

        /** Below this many rows between the vanishing point and the image bottom there is no road to lay lanes on. */
        private const val MIN_ROAD_ROWS_PX = 40.0
        /** A line must reach at least this far below the vanishing point. */
        private const val MIN_BELOW_VP_PX = 10.0
        /** The reference row stays at least this far below the vanishing point (lines are too close together above). */
        private const val MIN_REF_ROWS_PX = 20.0

        /** Least-squares straight line of one polyline; null for fewer than 2 finite points or too short a vertical span. */
        private fun fitLine(polyline: List<List<Double>>, minSpanPx: Double, color: String?): LineFit? {
            val pts = polyline.filter { it.size >= 2 && it[0].isFinite() && it[1].isFinite() }
            if (pts.size < 2) return null
            val xs = DoubleArray(pts.size) { pts[it][0] }
            val ys = DoubleArray(pts.size) { pts[it][1] }
            val minY = ys.min()
            val maxY = ys.max()
            if (maxY - minY < minSpanPx) return null
            val mx = xs.average()
            val my = ys.average()
            var syy = 0.0
            var sxy = 0.0
            for (i in xs.indices) { syy += (ys[i] - my) * (ys[i] - my); sxy += (xs[i] - mx) * (ys[i] - my) }
            val b = sxy / syy
            return LineFit(mx - b * my, b, xs, ys, minY, maxY, color)
        }

        /**
         * Vanishing point from the lines themselves: of the pairwise intersections that can be one (see [from]), the one
         * the most lines pass near ([LaneLayoutParams.vpToleranceFraction]; ties: the smaller total miss), refined to the
         * coordinate-wise median of the intersections among those lines. A consensus pick rather than a plain median,
         * because one stray line adds as many intersections as three good lines make. Null if there is no candidate.
         */
        private fun intersection(fits: List<LineFit>, width: Double, height: Double, params: LaneLayoutParams): Pair<Double, Double>? {
            fun pairwise(lines: List<LineFit>): List<Pair<Double, Double>> {
                val out = ArrayList<Pair<Double, Double>>()
                for (i in lines.indices) for (j in i + 1 until lines.size) {
                    val p = lines[i]
                    val q = lines[j]
                    val db = p.b - q.b
                    if (abs(db) < params.minIntersectionSlopeDifference) continue
                    val y = (q.a - p.a) / db
                    val x = p.a + p.b * y
                    if (y < params.vpMinRowFraction * height || y > params.vpMaxRowFraction * height) continue
                    if (x < -0.5 * width || x > 1.5 * width || y >= minOf(p.maxY, q.maxY)) continue
                    out += x to y
                }
                return out
            }
            val tolerance = params.vpToleranceFraction * width
            fun misses(c: Pair<Double, Double>) = fits.map { if (it.maxY >= c.second + MIN_BELOW_VP_PX) abs(it.xAt(c.second) - c.first) else Double.MAX_VALUE }
            val candidates = pairwise(fits)
            val best = candidates.maxWithOrNull(
                compareBy<Pair<Double, Double>> { c -> misses(c).count { it <= tolerance } }.thenByDescending { c -> misses(c).filter { it <= tolerance }.sum() },
            ) ?: return null
            val inliers = fits.filterIndexed { i, _ -> misses(best)[i] <= tolerance }
            val refined = pairwise(inliers)
            if (refined.isEmpty()) return best
            return RobustFit.median(refined.mapTo(ArrayList()) { it.first }) to RobustFit.median(refined.mapTo(ArrayList()) { it.second })
        }

        private fun round1(x: Double) = Math.round(x * 10.0) / 10.0
        private fun round3(x: Double) = Math.round(x * 1000.0) / 1000.0
        internal fun round4(x: Double) = Math.round(x * 10000.0) / 10000.0
    }

    /** A fitted polyline: `x = a + b * y`, with its points. */
    private class LineFit(val a: Double, val b: Double, val xs: DoubleArray, val ys: DoubleArray, val minY: Double, val maxY: Double, val color: String?) {
        fun xAt(y: Double) = a + b * y

        /** Least-squares slope of the line through ([vpX], [vpY]) from the points below it; null with fewer than 2 there. */
        fun slopeThrough(vpX: Double, vpY: Double): Through? {
            var sxy = 0.0
            var syy = 0.0
            var n = 0
            var top = Double.MAX_VALUE
            for (i in xs.indices) {
                val dy = ys[i] - vpY
                if (dy <= 2.0) continue
                sxy += (xs[i] - vpX) * dy; syy += dy * dy; n++
                top = minOf(top, ys[i])
            }
            if (n < 2 || syy <= 0.0) return null
            return Through(round4(sxy / syy), maxY, maxY - top, n, color)
        }
    }

    private class Through(val slope: Double, val maxY: Double, val span: Double, val weight: Int, val color: String?)
}

/**
 * A line on the way to a [LaneLayout]: [lateral] = metres across the road from the camera's ground track at the
 * reference depth (+ = right), [maxY] / [span] its lowest row and vertical extent in pixels, [weight] its point count,
 * [color] as in [LaneLine.color]. [left]: the line counts as left of the car's track (the WorldModel keeps it sticky
 * near 0). WorldModel tracks only: [trackId], and the track right of it with [lanesToRight] lanes between them in the
 * last layout ([rightNeighbour], -1 = none).
 */
internal class LaneCandidate(
    val slope: Double,
    val lateral: Double,
    val detected: Boolean,
    val maxY: Double,
    val span: Double,
    val weight: Int,
    val color: String?,
    val left: Boolean = slope <= 0.0,
    val trackId: Int = -1,
    val rightNeighbour: Int = -1,
    val lanesToRight: Int = 0,
) {
    fun mergedWith(o: LaneCandidate, metersPerSlope: Double): LaneCandidate {
        val s = LaneLayout.round4((slope * weight + o.slope * o.weight) / (weight + o.weight))
        return LaneCandidate(s, s * metersPerSlope, true, maxOf(maxY, o.maxY), maxOf(span, o.span), weight + o.weight, LaneLine.mergedColor(color, o.color))
    }
}

/**
 * One lanes run measured by [LaneLayout.measure]: its vanishing point, the metres per unit of slope there, the camera
 * height the ground model used, and its merged [lines] left to right (no lanes formed, nothing cut yet).
 */
internal class LaneRun(
    val vpX: Double,
    val vpY: Double,
    val metersPerSlope: Double,
    val heightMeters: Double,
    val lines: List<LaneCandidate>,
    val imageHeight: Double,
    val confidence: Double,
    val measuredPts: Double,
    val ageSeconds: Double,
)

/**
 * [LaneLayout.from] tuning. PLACEHOLDERS, set on three recorded drives (city, arterial, highway; phone yawed 7-18
 * degrees); they shape what is displayed, they are not validated limits.
 */
data class LaneLayoutParams(
    /** Polylines spanning fewer image rows are ignored (stop bars, arrows, specks). */
    val minLineSpanPx: Double = 25.0,
    /** A line may miss the vanishing point by this share of the image width (at the vanishing-point row). */
    val vpToleranceFraction: Double = 0.08,
    /** Line pairs whose slopes (dx/dy) differ by less than this are too parallel to intersect reliably. */
    val minIntersectionSlopeDifference: Double = 0.05,
    /**
     * Rows (share of the image height) where an intersection can be the vanishing point. Deliberately down to 0.75:
     * a dashboard phone tilted up put the horizon at 350-440 of 720 rows.
     */
    val vpMinRowFraction: Double = 0.0,
    val vpMaxRowFraction: Double = 0.75,
    /** Depth at which lateral offsets are measured (the row comes from the flat-ground model). */
    val referenceDepthMeters: Double = 9.0,
    /** Lines closer than this across the road are one line (about 0.3 lane widths: double lines, two fits of one line). */
    val mergeMeters: Double = 1.0,
    /** Neighbouring lines this far apart bound one lane. */
    val minLaneWidthMeters: Double = 2.4,
    val maxLaneWidthMeters: Double = 5.2,
    /**
     * A narrower gap also bounds a lane when it is at least this share of the median width of the layout's regular
     * ([minLaneWidthMeters]..[maxLaneWidthMeters]) lanes, and at least [minNarrowLaneWidthMeters]: a left-turn bay opens
     * narrower than the through lanes (real_011 at 42-46 s: 2.2 m next to 3.3 m), while double yellow lines (merged as one
     * line, [mergeMeters]) and painted medians (about 1.2 m) stay out.
     */
    val narrowLaneFraction: Double = 0.55,
    val minNarrowLaneWidthMeters: Double = 1.8,
    /** Up to this far apart (and above [maxLaneWidthMeters]): two lanes, with a virtual line in the middle. */
    val maxTwoLaneWidthMeters: Double = 9.5,
    /** See [FlatGround.PLAUSIBLE_CAMERA_HEIGHT_M] (the WorldModel smooths the height inside it, `laneCameraHeightEmaAlpha`). */
    val plausibleCameraHeightMeters: ClosedFloatingPointRange<Double> = FlatGround.PLAUSIBLE_CAMERA_HEIGHT_M,
    val defaultCameraHeightMeters: Double = FlatGround.DEFAULT_CAMERA_HEIGHT_M,
    /** Detected lines spanning this share of the rows below the vanishing point count as full length for [LaneLayout.quality]. */
    val fullLengthFraction: Double = 0.4,
    /** Added to the server's lanes confidence (capped at 1) in [LaneLayout.quality]. */
    val confidenceBoost: Double = 0.4,
)
