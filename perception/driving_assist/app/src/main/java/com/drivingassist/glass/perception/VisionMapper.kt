package com.drivingassist.glass.perception

import com.drivingassist.glass.LaneLine
import com.drivingassist.glass.NormPoint
import com.drivingassist.glass.PreviewCoordinates
import com.drivingassist.glass.TrafficSign
import com.drivingassist.glass.Vehicle
import com.drivingassist.glass.VisionData
import com.ksr.copilot.context.LanesState
import com.ksr.copilot.context.ObjectState
import com.ksr.copilot.context.WorldSnapshot
import com.ksr.copilot.perception.LightState
import com.ksr.copilot.perception.ObjectClass
import kotlin.math.hypot

/**
 * Laptop perception ([WorldSnapshot], pixels of the UPRIGHT analysed image) -> Glass Mode [VisionData]
 * (0..1 overlay space, boxes `[x, y, w, h]`). Pure Kotlin, no Android APIs (unit-tested on the JVM).
 *
 * Coordinates: the server reports boxes / lanes in the upright image (PROTOCOL_v2: after the uplink
 * `rotationDegrees`), and `world.image` gives its size. So the mapping is [PreviewCoordinates] with
 * rotation 0 and the upright size, FILL_CENTER into the view (camera preview in LIVE, PlayerView with
 * RESIZE_MODE_ZOOM in SIM). Results are clipped to the view.
 *
 * - vehicles: road users (car, truck, bus, motorcycle, bicycle, pedestrian, rider) that are visible in
 *   the newest frame AND have a distance, nearest first, at most [MAX_VEHICLES].
 * - signs: traffic lights with a known colour -> `LIGHT_RED` / `LIGHT_YELLOW` / `LIGHT_GREEN` (id = track
 *   id); recognised signs -> `STOP`, `YIELD`, `SPEED_LIMIT_45`, `DO_NOT_ENTER`, `PEDESTRIAN_CROSSING`,
 *   anything else `WARNING` (id = [SIGN_ID_OFFSET] + sign id); `unknown` signs are dropped.
 * - lanes: `left` / `right` = ego-lane boundaries, others `lane_<index in laneBoundaries>`; points near -> far.
 * - exitSigns: always empty (the laptop has no exit-sign detector yet).
 * - stale perception (link down / results too old) -> empty lists: never draw old markers.
 */
object VisionMapper {
    const val MAX_VEHICLES = 8
    const val SIGN_ID_OFFSET = 100_000

    /** Signs are held by the WorldModel for 2 s; only draw ones seen this recently (media time). */
    const val SIGN_MAX_AGE_SECONDS = 0.5

    /** Lanes come from the slow wave; skip them when the last run is older than this. */
    const val LANES_MAX_AGE_SECONDS = 1.0

    private val ROAD_USERS = setOf(
        ObjectClass.CAR, ObjectClass.TRUCK, ObjectClass.BUS, ObjectClass.MOTORCYCLE,
        ObjectClass.BICYCLE, ObjectClass.PEDESTRIAN, ObjectClass.RIDER,
    )

    fun map(world: WorldSnapshot, viewWidth: Float, viewHeight: Float, time: Float = world.ptsSeconds.toFloat()): VisionData {
        val image = world.image
        if (image == null || world.timing == null || world.perceptionStale || viewWidth <= 0f || viewHeight <= 0f ||
            image.width <= 0 || image.height <= 0
        ) {
            return VisionData(time = time)
        }
        val m = Mapper(image.width, image.height, viewWidth, viewHeight)

        val vehicles = world.objects.values
            .filter { it.visible && it.cls in ROAD_USERS && (it.distanceMeters ?: 0.0) > 0.0 }
            .sortedBy { it.distanceMeters }
            .mapNotNull { o -> m.box(o.bbox)?.let { Vehicle(o.id, it, o.distanceMeters!!.toFloat()) } } // off-screen ones drop out first
            .take(MAX_VEHICLES)

        val lights = world.objects.values
            .filter { it.visible && it.cls == ObjectClass.TRAFFIC_LIGHT }
            .mapNotNull { o -> lightLabel(o)?.let { label -> m.box(o.bbox)?.let { TrafficSign(o.id, label, it) } } }

        val signs = world.signs
            .filter { world.ptsSeconds - it.lastSeenPts <= SIGN_MAX_AGE_SECONDS }
            .mapIndexedNotNull { i, s ->
                val label = signLabel(s.sign.signClass) ?: return@mapIndexedNotNull null
                m.box(s.sign.bbox)?.let { TrafficSign(SIGN_ID_OFFSET + (s.sign.id ?: i), label, it) }
            }

        val lanes = world.lanes?.takeIf { it.ageSeconds <= LANES_MAX_AGE_SECONDS }?.let { laneLines(it, m) }.orEmpty()

        return VisionData(time = time, vehicles = vehicles, lanes = lanes, signs = lights + signs, exitSigns = emptyList())
    }

    private fun lightLabel(o: ObjectState): String? = when (o.lightState) {
        LightState.RED -> "LIGHT_RED"
        LightState.YELLOW -> "LIGHT_YELLOW"
        LightState.GREEN -> "LIGHT_GREEN"
        else -> null
    }

    /** Wire `signClass` (camelCase or snake_case) -> Glass token; null = not recognised (draw nothing). */
    fun signLabel(signClass: String): String? {
        val k = signClass.lowercase().filter { it.isLetterOrDigit() }
        return when {
            k.isEmpty() || k == "unknown" || k == "other" -> null
            k == "stop" -> "STOP"
            k == "yield" -> "YIELD"
            k.startsWith("speedlimit") -> k.removePrefix("speedlimit").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                ?.let { "SPEED_LIMIT_$it" } ?: "SPEED_LIMIT"
            k == "donotenter" -> "DO_NOT_ENTER"
            k == "pedestriancrossing" -> "PEDESTRIAN_CROSSING"
            else -> "WARNING"
        }
    }

    private fun laneLines(state: LanesState, m: Mapper): List<LaneLine> {
        val boundaries = state.lanes.laneBoundaries
        if (boundaries.isEmpty()) return emptyList()
        val (left, right) = egoBoundaryIndices(boundaries, state.currentLane, state.laneCount, m.imageWidth.toDouble(), m.imageHeight.toDouble())
        return boundaries.mapIndexedNotNull { i, line ->
            val pts = m.polyline(line)
            if (pts.size < 2) return@mapIndexedNotNull null
            val id = when (i) {
                left -> "left"
                right -> "right"
                else -> "lane_$i"
            }
            LaneLine(id, pts)
        }
    }

    /**
     * Which of the left-to-right `laneBoundaries` bound the ego lane: (left index, right index), null
     * when unknown. When the server's list holds exactly `laneCount + 1` lines, lane `currentLane`
     * (1-based from the left) lies between lines `currentLane - 1` and `currentLane`. Otherwise
     * (the lanes block can report extra lines such as crosswalks or curbs, see perception/lanes/README.md)
     * the nearest line on each side of the image centre at the bottom of the frame is used, which is
     * how the lanes block picks the ego boundaries itself (camera at u = 0).
     */
    fun egoBoundaryIndices(
        boundaries: List<List<List<Double>>>,
        currentLane: Int?,
        laneCount: Int?,
        imageWidth: Double,
        imageHeight: Double,
    ): Pair<Int?, Int?> {
        val yRef = imageHeight * 0.95
        val xs = boundaries.map { xAt(it, yRef) }
        if (currentLane != null && laneCount != null && boundaries.size == laneCount + 1 && currentLane in 1..laneCount) {
            val l = currentLane - 1
            val r = currentLane
            val xl = xs[l]
            val xr = xs[r]
            if (xl == null || xr == null || xl < xr) return l to r
        }
        val centre = imageWidth / 2.0
        val left = xs.withIndex().filter { (_, x) -> x != null && x < centre }.maxByOrNull { it.value!! }?.index
        val right = xs.withIndex().filter { (_, x) -> x != null && x >= centre }.minByOrNull { it.value!! }?.index
        return left to right
    }

    /**
     * x of a polyline at row [y] (linear interpolation, or extrapolation from the two nearest points).
     * Null for a (nearly) horizontal line that does not reach [y] (stop line, crosswalk edge): it has no
     * meaningful lateral position there and must not be picked as an ego boundary.
     */
    internal fun xAt(line: List<List<Double>>, y: Double): Double? {
        val pts = line.filter { it.size >= 2 }.sortedBy { it[1] }
        if (pts.size < 2) return null
        for (i in 0 until pts.size - 1) {
            val (x0, y0) = pts[i][0] to pts[i][1]
            val (x1, y1) = pts[i + 1][0] to pts[i + 1][1]
            if (y in y0..y1) return if (y1 - y0 < 1e-9) x0 else x0 + (x1 - x0) * (y - y0) / (y1 - y0)
        }
        val (a, b) = if (y > pts.last()[1]) pts[pts.size - 2] to pts.last() else pts[0] to pts[1]
        val dy = b[1] - a[1]
        if (dy < 1e-6 * (1.0 + kotlin.math.abs(b[0] - a[0])) || kotlin.math.abs(b[0] - a[0]) > 20.0 * dy) return null
        return a[0] + (b[0] - a[0]) * (y - a[1]) / dy
    }

    /** Upright-image pixels -> overlay 0..1, FILL_CENTER, clipped to the view. */
    private class Mapper(val imageWidth: Int, val imageHeight: Int, val viewWidth: Float, val viewHeight: Float) {
        /** `[x1, y1, x2, y2]` px -> `[x, y, w, h]` overlay, clipped; null when (almost) outside the view. */
        fun box(bbox: List<Double>): FloatArray? {
            if (bbox.size < 4) return null
            val w = imageWidth.toFloat()
            val h = imageHeight.toFloat()
            val norm = floatArrayOf(
                bbox[0].toFloat() / w, bbox[1].toFloat() / h,
                (bbox[2] - bbox[0]).toFloat() / w, (bbox[3] - bbox[1]).toFloat() / h,
            )
            if (norm[2] <= 0f || norm[3] <= 0f) return null
            val b = PreviewCoordinates.mapBox(norm, 0, imageWidth, imageHeight, viewWidth, viewHeight) ?: return null
            val x1 = b[0].coerceAtLeast(0f)
            val y1 = b[1].coerceAtLeast(0f)
            val x2 = (b[0] + b[2]).coerceAtMost(1f)
            val y2 = (b[1] + b[3]).coerceAtMost(1f)
            if (x2 - x1 < MIN_SIZE || y2 - y1 < MIN_SIZE) return null
            return floatArrayOf(x1, y1, x2 - x1, y2 - y1)
        }

        /** Polyline px -> overlay points ordered near (large y) -> far, clipped to the view (longest visible run). */
        fun polyline(line: List<List<Double>>): List<NormPoint> {
            val w = imageWidth.toFloat()
            val h = imageHeight.toFloat()
            val norm = line.filter { it.size >= 2 }
                .sortedByDescending { it[1] }
                .map { it[0].toFloat() / w to it[1].toFloat() / h }
            val mapped = PreviewCoordinates.mapPolyline(norm, 0, imageWidth, imageHeight, viewWidth, viewHeight)
            return clipPolyline(mapped)
        }
    }

    private const val MIN_SIZE = 0.002f

    /** Clips a polyline to the unit square; returns the longest continuous visible run. */
    internal fun clipPolyline(points: List<NormPoint>): List<NormPoint> {
        if (points.size < 2) return emptyList()
        val runs = ArrayList<MutableList<NormPoint>>()
        var current: MutableList<NormPoint>? = null
        for (i in 0 until points.size - 1) {
            val seg = clipSegment(points[i], points[i + 1])
            if (seg == null) { current = null; continue }
            val (a, b) = seg
            val run = current
            if (run == null || run.last() != a) {
                current = mutableListOf(a, b).also { runs += it }
            } else {
                run += b
            }
            if (b != points[i + 1]) current = null // left the view: the next visible part is a new run
        }
        return runs.filter { it.size >= 2 }.maxByOrNull { length(it) }.orEmpty()
    }

    private fun length(run: List<NormPoint>): Float =
        (0 until run.size - 1).sumOf { hypot((run[it + 1].x - run[it].x).toDouble(), (run[it + 1].y - run[it].y).toDouble()) }.toFloat()

    /** Liang-Barsky clip of segment p-q to [0, 1] x [0, 1]; null when fully outside. */
    private fun clipSegment(p: NormPoint, q: NormPoint): Pair<NormPoint, NormPoint>? {
        var t0 = 0f
        var t1 = 1f
        val dx = q.x - p.x
        val dy = q.y - p.y
        val ps = floatArrayOf(-dx, dx, -dy, dy)
        val qs = floatArrayOf(p.x, 1f - p.x, p.y, 1f - p.y)
        for (i in 0 until 4) {
            if (ps[i] == 0f) {
                if (qs[i] < 0f) return null
            } else {
                val r = qs[i] / ps[i]
                if (ps[i] < 0f) {
                    if (r > t1) return null
                    if (r > t0) t0 = r
                } else {
                    if (r < t0) return null
                    if (r < t1) t1 = r
                }
            }
        }
        val a = if (t0 == 0f) p else NormPoint(p.x + t0 * dx, p.y + t0 * dy)
        val b = if (t1 == 1f) q else NormPoint(p.x + t1 * dx, p.y + t1 * dy)
        if (a == b) return null
        return a to b
    }
}
