package com.drivingassist.spatialcopilot.ar

import com.drivingassist.copilot.context.WorldSnapshot
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Centre line of the ego lane on the road plane: `x(z) = a + b z + c z^2` metres (lateral, + right) for
 * `z` metres ahead, fitted to perception, plus the lane width. Beyond [maxZ] (the farthest sample) it
 * continues along the tangent, so a curve fit is never extrapolated into a hook.
 */
data class EgoLane(
    val a: Double,
    val b: Double,
    val c: Double,
    val widthMeters: Double,
    val maxZ: Double,
    val source: Source,
) {
    enum class Source {
        /** Ego pair of `lanes.laneBoundaries` (the two lines around the car). */
        LANE_LINES,

        /** `road.anchorPoints` ego_lane_center_near / mid / far (short range). */
        ANCHORS,

        /** No lane geometry: straight ahead along the camera axis. */
        CAMERA_AXIS,
    }

    fun x(z: Double): Double {
        if (z <= maxZ) return a + b * z + c * z * z
        val x0 = a + b * maxZ + c * maxZ * maxZ
        val slope = b + 2 * c * maxZ
        return x0 + slope * (z - maxZ)
    }

    /** Heading of the lane at [z] as dx/dz. */
    fun slope(z: Double): Double = b + 2 * c * min(z, maxZ)

    /** Blend towards [other] by [t] in 0..1 (display smoothing between perception updates). */
    fun lerp(other: EgoLane, t: Double): EgoLane = EgoLane(
        a = a + (other.a - a) * t,
        b = b + (other.b - b) * t,
        c = c + (other.c - c) * t,
        widthMeters = widthMeters + (other.widthMeters - widthMeters) * t,
        maxZ = maxZ + (other.maxZ - maxZ) * t,
        source = other.source,
    )

    companion object {
        const val DEFAULT_LANE_WIDTH_M = 3.5
        private const val MIN_LANE_WIDTH_M = 2.2
        private const val MAX_LANE_WIDTH_M = 5.5

        /** Lanes older than this (media seconds) are not used for the arrows. */
        const val MAX_LANES_AGE_S = 1.0
        private const val MAX_SAMPLE_Z = 60.0
        /** Heading limit dx/dz (about 19 degrees): recorded drives had the phone yawed 7-18 degrees off the road. */
        const val MAX_SLOPE = 0.35
        private const val MAX_OFFSET_M = 2.0

        val AXIS = EgoLane(0.0, 0.0, 0.0, DEFAULT_LANE_WIDTH_M, 60.0, Source.CAMERA_AXIS)

        /**
         * Best available ego lane of [world]: lane lines, then anchors, then the camera axis. Anchors (a few metres)
         * and the camera axis take their heading from the road's vanishing point when there is one
         * ([roadVanishingX]): with a yawed phone the road does not run along the camera axis. A lane-lines fit
         * keeps its own heading (measured on the same painted lines, and it may curve).
         */
        fun from(world: WorldSnapshot, projector: GroundProjector): EgoLane {
            fromLaneLines(world, projector)?.let { return it }
            val lane = fromAnchors(world, projector) ?: AXIS
            val heading = roadVanishingX(world)?.let { projector.headingAt(it).coerceIn(-MAX_SLOPE, MAX_SLOPE) } ?: return lane
            // The camera axis starts under the camera; anchors keep their lateral position halfway along their span.
            val zRef = if (lane.source == Source.CAMERA_AXIS) 0.0 else lane.maxZ / 2
            return lane.copy(a = (lane.x(zRef) - heading * zRef - lane.c * zRef * zRef).coerceIn(-MAX_OFFSET_M, MAX_OFFSET_M), b = heading)
        }

        /**
         * The two boundaries around the car: at a near row, the adjacent pair of polylines (ordered left to
         * right) that brackets the ego anchor (or the image centre). `currentLane` indexing is not trusted,
         * because `laneBoundaries` can contain lines the lane count does not include.
         */
        fun fromLaneLines(world: WorldSnapshot, projector: GroundProjector): EgoLane? {
            val lanes = world.lanes?.takeIf { it.ageSeconds <= MAX_LANES_AGE_S } ?: return null
            val lines = lanes.lanes.laneBoundaries
                .map { line -> line.mapNotNull { p -> if (p.size >= 2) Vec2(p[0].toFloat(), p[1].toFloat()) else null } }
                .filter { it.size >= 2 }
            if (lines.size < 2) return null
            val refZ = max(projector.nearestVisibleZ + 1.0, 6.0)
            val refRow = projector.rowAt(refZ)
            // The ego anchor sits ~2.6 m ahead: move its lateral offset to refZ before comparing columns, or a lane centre
            // more than ~1.4 m off the camera axis (mid lane change) lands in the neighbour's pair.
            val refLat = world.road?.road?.anchor("ego_lane_center_near")?.let { projector.toGround(it.x, it.y)?.x } ?: 0.0
            val refX = projector.toImage(Ground(refLat, refZ))?.x?.toDouble() ?: projector.cx
            val xs = lines.map { xAtRow(it, refRow) }
            var pair: Pair<Int, Int>? = null
            for (i in 0 until lines.lastIndex) {
                val l = xs[i] ?: continue
                val r = xs[i + 1] ?: continue
                if (l <= refX && refX <= r) { pair = i to i + 1; break }
            }
            val (li, ri) = pair ?: return null
            val samples = ArrayList<Triple<Double, Double, Double>>() // z, centre x, width
            val top = max(minY(lines[li]), minY(lines[ri]))
            val bottom = min(min(maxY(lines[li]), maxY(lines[ri])), projector.imageHeight.toDouble())
            if (bottom - top < 8.0) return null
            // Samples evenly spaced in metres (evenly spaced rows would crowd into the first few metres).
            val zNear = projector.forwardAtRow(bottom) ?: return null
            val zFar = min(projector.forwardAtRow(top + 1.0) ?: MAX_SAMPLE_Z, MAX_SAMPLE_Z)
            if (zFar - zNear < 2.0) return null
            for (k in 0..11) {
                val z = zNear + (zFar - zNear) * k / 11.0
                val row = projector.rowAt(z)
                val xl = xAtRow(lines[li], row) ?: continue
                val xr = xAtRow(lines[ri], row) ?: continue
                val gl = projector.lateralAt(xl, z)
                val gr = projector.lateralAt(xr, z)
                val width = gr - gl
                if (width !in MIN_LANE_WIDTH_M..MAX_LANE_WIDTH_M) continue
                samples += Triple(z, (gl + gr) / 2.0, width)
            }
            if (samples.size < 3) return null
            val width = samples.map { it.third }.sorted()[samples.size / 2]
            return fit(samples.map { it.first to it.second }, width, Source.LANE_LINES)
        }

        /** ego_lane_center_near / mid / far (and a neighbour lane centre for the width). */
        fun fromAnchors(world: WorldSnapshot, projector: GroundProjector): EgoLane? {
            val road = world.road?.takeIf { it.ageSeconds <= MAX_LANES_AGE_S }?.road ?: return null
            val pts = listOf("ego_lane_center_near", "ego_lane_center_mid", "ego_lane_center_far")
                .mapNotNull { road.anchor(it) }
                .mapNotNull { projector.toGround(it.x, it.y) }
                .filter { it.z in 1.0..80.0 }
            if (pts.size < 2) return null
            val mid = road.anchor("ego_lane_center_mid")?.let { projector.toGround(it.x, it.y) }
            val side = listOfNotNull(road.anchor("left_lane_center_mid"), road.anchor("right_lane_center_mid"))
                .mapNotNull { projector.toGround(it.x, it.y) }
                .firstOrNull()
            val width = if (mid != null && side != null) abs(side.x - mid.x).takeIf { it in MIN_LANE_WIDTH_M..MAX_LANE_WIDTH_M } else null
            // Anchors only span a few metres: fit a straight line, never a curve.
            return fit(pts.map { it.z to it.x }, width ?: DEFAULT_LANE_WIDTH_M, Source.ANCHORS, allowCurve = false)
        }

        /** Least-squares x(z); a curvature term only when the samples span enough distance. */
        internal fun fit(samples: List<Pair<Double, Double>>, width: Double, source: Source, allowCurve: Boolean = true): EgoLane? {
            if (samples.size < 2) return null
            val zMin = samples.minOf { it.first }
            val zMax = samples.maxOf { it.first }
            if (zMax - zMin < 1.0) return null
            val curve = allowCurve && samples.size >= 4 && zMax - zMin >= 10.0
            val (a, b, c) = if (curve) quadratic(samples) else linear(samples).let { Triple(it.first, it.second, 0.0) }
            // A curvature that bends more than 4 m of lateral offset over the sampled range is noise.
            val cc = c.coerceIn(-4.0 / (zMax * zMax), 4.0 / (zMax * zMax))
            // A phone on the dash looks roughly along its lane: heading within about 19 degrees (a yawed mount) and the
            // lane centre within 2 m of the camera. Short or noisy fits (intersections) stay sane.
            return EgoLane(a.coerceIn(-MAX_OFFSET_M, MAX_OFFSET_M), b.coerceIn(-MAX_SLOPE, MAX_SLOPE), cc, width, zMax, source)
        }

        private fun linear(s: List<Pair<Double, Double>>): Pair<Double, Double> {
            val n = s.size.toDouble()
            val mz = s.sumOf { it.first } / n
            val mx = s.sumOf { it.second } / n
            val szz = s.sumOf { (it.first - mz) * (it.first - mz) }
            val szx = s.sumOf { (it.first - mz) * (it.second - mx) }
            val b = if (szz < 1e-9) 0.0 else szx / szz
            return (mx - b * mz) to b
        }

        private fun quadratic(s: List<Pair<Double, Double>>): Triple<Double, Double, Double> {
            // Normal equations for x = a + b z + c z^2 (z centred for conditioning).
            val mz = s.sumOf { it.first } / s.size
            var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
            var t0 = 0.0; var t1 = 0.0; var t2 = 0.0
            for ((z0, x) in s) {
                val z = z0 - mz
                val z2 = z * z
                s0 += 1.0; s1 += z; s2 += z2; s3 += z2 * z; s4 += z2 * z2
                t0 += x; t1 += x * z; t2 += x * z2
            }
            val det = s0 * (s2 * s4 - s3 * s3) - s1 * (s1 * s4 - s3 * s2) + s2 * (s1 * s3 - s2 * s2)
            if (abs(det) < 1e-9) return linear(s).let { Triple(it.first, it.second, 0.0) }
            val ac = (t0 * (s2 * s4 - s3 * s3) - s1 * (t1 * s4 - s3 * t2) + s2 * (t1 * s3 - s2 * t2)) / det
            val bc = (s0 * (t1 * s4 - t2 * s3) - t0 * (s1 * s4 - s3 * s2) + s2 * (s1 * t2 - t1 * s2)) / det
            val cc = (s0 * (s2 * t2 - s3 * t1) - s1 * (s1 * t2 - t1 * s2) + t0 * (s1 * s3 - s2 * s2)) / det
            // Undo the centring: x = ac + bc (z - m) + cc (z - m)^2.
            return Triple(ac - bc * mz + cc * mz * mz, bc - 2 * cc * mz, cc)
        }

        /** x of a polyline at image row [y] (points may run either way); null outside its span. */
        internal fun xAtRow(points: List<Vec2>, y: Double): Double? {
            for (i in 0 until points.lastIndex) {
                val p = points[i]
                val q = points[i + 1]
                val lo = min(p.y, q.y)
                val hi = max(p.y, q.y)
                if (y < lo || y > hi) continue
                if (hi - lo < 1e-3) return (p.x + q.x) / 2.0
                val t = (y - p.y) / (q.y - p.y)
                return p.x + t * (q.x - p.x)
            }
            return null
        }

        private fun minY(points: List<Vec2>): Double = points.minOf { it.y }.toDouble()
        private fun maxY(points: List<Vec2>): Double = points.maxOf { it.y }.toDouble()
    }
}
