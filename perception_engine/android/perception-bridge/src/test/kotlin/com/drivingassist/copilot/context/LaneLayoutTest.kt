package com.drivingassist.copilot.context

import com.drivingassist.copilot.perception.Lanes
import com.drivingassist.copilot.perception.Road
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [LaneLayout.from] on recorded frames (yawed phone) and on exact synthetic views. */
class LaneLayoutTest {

    /** Frames of the fixture that show two to four lanes around the car (checked on the video). */
    private val multiLane = RealLaneFrames.all.filter { !(it.clip == "real_010" && it.frameIndex == 19L) && !(it.clip == "real_009" && it.frameIndex == 2822L) }

    @TestFactory
    fun `real frames give 2-4 lanes around the car, parallel to the painted lines`(): List<DynamicTest> = multiLane.map { f ->
        DynamicTest.dynamicTest("${f.clip} @ ${f.ptsSeconds} s") {
            val layout = assertNotNull(LaneLayout.from(f.snapshot()), "no layout")
            assertTrue(layout.laneCount in 2..4, "lane count ${layout.laneCount}")
            assertTrue(layout.egoLane in 1..layout.laneCount, "ego lane ${layout.egoLane}")
            assertTrue(layout.quality >= LaneLayout.USABLE_QUALITY, "quality ${layout.quality}")
            // The car's ground track (x = vpX) is inside the ego lane at every row below the vanishing point.
            for (y in listOf(layout.vpY + 30, (layout.vpY + f.image.height) / 2, f.image.height.toDouble())) {
                assertEquals(layout.egoLane, layout.laneAt(layout.vpX, y), "ego lane at row $y")
            }
            f.road.vanishingPoint?.let { vp ->
                assertEquals(vp[0], layout.vpX, 0.05); assertEquals(vp[1], layout.vpY, 0.05)
            }
            // Every detected line follows a painted polyline, and at a near and a far row the middle of each lane's two
            // painted lines lies inside the layout's lane: the lanes point where the painted lines meet, not along the
            // camera axis (at 7-18 degrees of yaw a camera-parallel lane leaves its painted lines within a few metres).
            val painted = layout.lines.indices.associateWith { i -> if (layout.lines[i].detected) closestPolyline(layout, i, f.lanes) else null }
            for ((i, p) in painted) {
                if (p == null) continue
                val dev = meanDeviation(layout, i, p)
                assertTrue(dev < 0.03 * f.image.width, "line $i is ${dev.toInt()} px from its painted line")
            }
            assertEquals(emptyList(), paintedMiddlesOutside(layout, layout, f))
        }
    }

    @Test
    fun `control - the same lanes aimed along the camera axis leave the painted lines`() {
        // real_011 @ 40.7 s: vanishing point at x 884 of 1280 (about 19 degrees of yaw).
        val f = RealLaneFrames.get("real_011", 1222L)
        val layout = LaneLayout.from(f.snapshot())!!
        val near = f.image.height - 0.1 * (f.image.height - layout.vpY)
        val cx = f.camera.cx
        // Same line positions at the near row, but every line is a ray from the principal column.
        val cameraAxis = layout.copy(vpX = cx, lines = layout.lines.indices.map { i -> LaneLine((layout.lineX(i, near) - cx) / (near - layout.vpY)) })
        assertEquals(emptyList(), paintedMiddlesOutside(layout, layout, f))
        assertTrue(paintedMiddlesOutside(cameraAxis, layout, f).isNotEmpty(), "a camera-parallel lane must fail the check")
    }

    /**
     * Lanes of [candidate] whose painted middle (the two painted polylines nearest to [reference]'s bounding lines)
     * is outside the lane at a far or a near row, as "lane@row".
     */
    private fun paintedMiddlesOutside(candidate: LaneLayout, reference: LaneLayout, f: RealLaneFrame): List<String> {
        val painted = reference.lines.indices.associateWith { i -> if (reference.lines[i].detected) closestPolyline(reference, i, f.lanes) else null }
        val rows = listOf(reference.vpY + 0.3 * (f.image.height - reference.vpY), f.image.height - 0.1 * (f.image.height - reference.vpY))
        val out = ArrayList<String>()
        for (lane in 1..candidate.laneCount) {
            val left = painted[lane - 1] ?: continue
            val right = painted[lane] ?: continue
            for (y in rows) {
                val mid = (paintedX(left, reference.vpY, y) + paintedX(right, reference.vpY, y)) / 2
                if (mid <= candidate.lineX(lane - 1, y) || mid >= candidate.lineX(lane, y)) out += "$lane@${y.toInt()}"
            }
        }
        return out
    }

    @Test
    fun `layouts checked on the video`() {
        // (clip, frame) -> (lane count, ego lane) as seen on the recording.
        val expected = mapOf(
            ("real_011" to 451L) to (3 to 2), // arterial under the overpass, middle lane
            ("real_011" to 1560L) to (2 to 1), // left lane next to the yellow median
            ("real_010" to 3105L) to (3 to 2), // highway
            ("real_009" to 654L) to (2 to 1), // city, left lane next to the median
            ("real_009" to 58L) to (3 to 2), // no road vanishing point: from the line intersections, one missed line
        )
        for ((key, want) in expected) {
            val layout = assertNotNull(LaneLayout.from(RealLaneFrames.get(key.first, key.second).snapshot()), "$key")
            assertEquals(want, layout.laneCount to layout.egoLane, "$key")
        }
        val virtual = LaneLayout.from(RealLaneFrames.get("real_009", 58L).snapshot())!!
        assertEquals(1, virtual.lines.count { !it.detected }, "the missed line of real_009 @ 1.9 s is filled in")
    }

    @Test
    fun `the server's lane numbers are not used`() {
        val f = RealLaneFrames.get("real_011", 451L)
        assertEquals(1 to 1, f.lanes.currentLane to f.lanes.laneCount)
        assertEquals(3 to 2, LaneLayout.from(f.snapshot())!!.let { it.laneCount to it.egoLane })
    }

    @Test
    fun `lines on one side of the car only give no layout`() {
        // real_009 @ 94 s: two lines, both left of the car (the right line is not detected).
        assertNull(LaneLayout.from(RealLaneFrames.get("real_009", 2822L).snapshot()))
    }

    @Test
    fun `yawed camera - lanes follow the road, the ego lane is under the car's track`() {
        val road = SyntheticRoad(yawDeg = 15.0, pitchDeg = 1.0)
        val layout = assertNotNull(LaneLayout.from(road.snapshot(road.lanes(-5.25, -1.75, 1.75, 5.25))))
        assertEquals(3, layout.laneCount)
        assertEquals(2, layout.egoLane)
        assertTrue(layout.lines.all { it.detected })
        assertEquals(road.vpX, layout.vpX, 1.0, "vanishing point from the line intersections")
        assertEquals(road.vpY, layout.vpY, 1.0)
        assertTrue(layout.vpX - road.cx > 150, "the road vanishes well right of the image centre")
        for ((i, lateral) in listOf(-5.25, -1.75, 1.75, 5.25).withIndex()) {
            for (z in listOf(6.0, 25.0)) {
                val p = road.project(lateral, z)!!
                assertEquals(p[0], layout.lineX(i, p[1]), 1.5, "line $i at $z m")
            }
        }
        // A car 3.5 m to the right at 12 m is in lane 3, one right under the track in lane 2, whatever the yaw.
        val right = road.carBox(3.5, 12.0)
        assertEquals(3, layout.laneAt((right[0] + right[2]) / 2, right[3]))
        val ahead = road.carBox(0.0, 12.0)
        assertEquals(2, layout.laneAt((ahead[0] + ahead[2]) / 2, ahead[3]))
    }

    @Test
    fun `road vanishing point is used when given`() {
        val road = SyntheticRoad(yawDeg = -10.0)
        val base = road.snapshot(road.lanes(-1.75, 1.75))
        val withVp = base.copy(road = RoadState(Road(0.3, horizonY = road.vpY, vanishingPoint = listOf(road.vpX + 4.0, road.vpY)), 1.0, 0.0))
        val layout = assertNotNull(LaneLayout.from(withVp))
        assertEquals(road.vpX + 4.0, layout.vpX, 0.05)
        assertEquals(1 to 1, layout.laneCount to layout.egoLane)
    }

    @Test
    fun `a gap of two lane widths gets one virtual line`() {
        val road = SyntheticRoad(yawDeg = 8.0)
        val layout = assertNotNull(LaneLayout.from(road.snapshot(road.lanes(-1.75, 1.75, 8.75))))
        assertEquals(3, layout.laneCount)
        assertEquals(1, layout.egoLane)
        assertEquals(listOf(true, true, false, true), layout.lines.map { it.detected })
        val p = road.project(5.25, 15.0)!!
        assertEquals(p[0], layout.lineX(2, p[1]), 3.0, "virtual line in the middle of the gap")
        assertTrue(layout.quality < LaneLayout.from(road.snapshot(road.lanes(-1.75, 1.75, 5.25, 8.75)))!!.quality, "a virtual line lowers the quality")
    }

    @Test
    fun `stripes, curbs and lines of another road are rejected`() {
        val road = SyntheticRoad(yawDeg = 12.0)
        val clean = road.lanes(-5.25, -1.75, 1.75)
        val crosswalk = listOf(listOf(150.0, 690.0), listOf(700.0, 640.0), listOf(1150.0, 600.0)) // steep in dx/dy, misses the VP
        val bar = listOf(listOf(300.0, 560.0), listOf(900.0, 565.0)) // 5 px tall: not a line
        val otherRoad = listOf(listOf(0.0, 600.0), listOf(400.0, 520.0)) // points at x ~ 1200 at the VP row instead of ~ 790
        val noisy = clean.copy(laneBoundaries = clean.laneBoundaries + listOf(crosswalk, bar, otherRoad))
        val a = assertNotNull(LaneLayout.from(road.snapshot(clean)))
        val b = assertNotNull(LaneLayout.from(road.snapshot(noisy)))
        assertEquals(a.lines, b.lines)
        assertEquals(a.egoLane to a.laneCount, b.egoLane to b.laneCount)
        assertEquals(a.vpX, b.vpX, 1.0, "the median intersection ignores the outliers")
    }

    @Test
    fun `near-duplicate lines are merged, other gaps split off the far lanes`() {
        val road = SyntheticRoad()
        val doubled = assertNotNull(LaneLayout.from(road.snapshot(road.lanes(-1.75, -1.5, 1.75))))
        assertEquals(1, doubled.laneCount, "a double line is one line")
        // A curb 1.5 m right of the right edge line, and a line 12 m away: neither makes a lane.
        val split = assertNotNull(LaneLayout.from(road.snapshot(road.lanes(-15.0, -1.75, 1.75, 3.25))))
        assertEquals(1 to 1, split.laneCount to split.egoLane)
    }

    @Test
    fun `no lines, one line or no camera give null`() {
        val road = SyntheticRoad()
        assertNull(LaneLayout.from(road.snapshot(Lanes(1, 1, emptyList(), 0.9))))
        assertNull(LaneLayout.from(road.snapshot(road.lanes(1.75))))
        assertNull(LaneLayout.from(road.snapshot(null)))
        assertNull(LaneLayout.from(road.snapshot(road.lanes(-1.75, 1.75)).copy(camera = null)))
        assertNull(LaneLayout.from(road.snapshot(road.lanes(-1.75, 1.75)).copy(image = null)))
        assertNull(LaneLayout.from(road.snapshot(road.lanes(1.75, 5.25))), "both lines right of the car")
    }

    @Test
    fun `quality follows the server confidence only softly`() {
        val road = SyntheticRoad()
        val low = LaneLayout.from(road.snapshot(road.lanes(-1.75, 1.75, confidence = 0.2)))!!
        val high = LaneLayout.from(road.snapshot(road.lanes(-1.75, 1.75, confidence = 0.9)))!!
        assertEquals(1.0, high.quality)
        assertEquals(0.6, low.quality, 1e-9)
        assertTrue(low.usable(), "a good line pair with a low server confidence is still usable")
        assertFalse(low.copy(ageSeconds = 1.2).usable(), "too old")
    }

    @Test
    fun `implausible camera heights fall back to the default`() {
        val road = SyntheticRoad(heightM = 1.25)
        val lanes = road.lanes(-5.25, -1.75, 1.75, 5.25)
        val base = road.snapshot(lanes)
        val good = LaneLayout.from(base)!!
        // A 4.5 m height would make every lane 13 m wide (no lanes); 0.66 m would make them 1.8 m (merged / split).
        for (h in listOf(4.46, 0.66)) {
            val bad = LaneLayout.from(base.copy(camera = base.camera!!.copy(cameraHeightMeters = h)))
            assertEquals(good.lines, bad?.lines, "height $h")
            assertEquals(1.25, bad?.cameraHeightMeters, "the height the widths were measured with")
        }
        assertEquals(3, good.laneCount)
        assertEquals(1.25, good.cameraHeightMeters)
    }

    // --- line colours (lanes.boundaryColors) ---------------------------------------------------------------------

    private fun Lanes.colored(vararg colors: String) = copy(boundaryColors = colors.toList())

    @Test
    fun `yellow line left of the car - the oncoming lines beyond it are dropped`() {
        val road = SyntheticRoad(yawDeg = 10.0)
        // Two oncoming lanes (lines at -8.75 and -5.25) beyond the yellow centre line at -1.75; our road has two lanes.
        val lanes = road.lanes(-8.75, -5.25, -1.75, 1.75, 5.25)
        val plain = assertNotNull(LaneLayout.from(road.snapshot(lanes)))
        assertEquals(4 to 3, plain.laneCount to plain.egoLane, "without colours every line through the vanishing point counts")
        assertTrue(plain.lines.all { it.color == null })

        val cut = assertNotNull(LaneLayout.from(road.snapshot(lanes.colored("white", "white", "yellow", "white", "white"))))
        assertEquals(2 to 1, cut.laneCount to cut.egoLane, "the yellow line is the left edge of our direction")
        assertEquals(listOf(LaneLine.YELLOW, LaneLine.WHITE, LaneLine.WHITE), cut.lines.map { it.color })
        assertEquals(plain.lines.drop(2).map { it.slope }, cut.lines.map { it.slope })
        // The nearest yellow line to the track wins: a yellow line farther left is dropped with the rest.
        val twoYellow = assertNotNull(LaneLayout.from(road.snapshot(lanes.colored("yellow", "white", "yellow", "white", "white"))))
        assertEquals(cut.lines, twoYellow.lines)
    }

    @Test
    fun `a yellow line right of the car is ignored, unknown colours cut nothing`() {
        val road = SyntheticRoad(yawDeg = -8.0)
        val lanes = road.lanes(-5.25, -1.75, 1.75, 5.25)
        val plain = LaneLayout.from(road.snapshot(lanes))!!
        for (colors in listOf(arrayOf("white", "white", "yellow", "white"), arrayOf("unknown", "unknown", "unknown", "unknown"))) {
            val layout = LaneLayout.from(road.snapshot(lanes.colored(*colors)))!!
            assertEquals(plain.lines.map { it.slope }, layout.lines.map { it.slope }, colors.joinToString())
            assertEquals(plain.egoLane, layout.egoLane)
        }
        // "unknown" and anything the client does not know read as unknown.
        assertEquals(List(4) { LaneLine.UNKNOWN }, LaneLayout.from(road.snapshot(lanes.colored("unknown", "UNKNOWN", "blue", "")))!!.lines.map { it.color })
    }

    @Test
    fun `merged lines - yellow wins, virtual lines are unknown, a misaligned colour array is ignored`() {
        val road = SyntheticRoad(yawDeg = 6.0)
        // A double centre line (white + yellow 0.25 m apart) is one yellow line.
        val double = LaneLayout.from(road.snapshot(road.lanes(-1.75, -1.5, 1.75).colored("white", "yellow", "white")))!!
        assertEquals(listOf(LaneLine.YELLOW, LaneLine.WHITE), double.lines.map { it.color })
        // Two lane widths without a line: the inserted line has no paint.
        val gap = LaneLayout.from(road.snapshot(road.lanes(-1.75, 1.75, 8.75).colored("yellow", "white", "white")))!!
        assertEquals(listOf(LaneLine.YELLOW, LaneLine.WHITE, LaneLine.UNKNOWN, LaneLine.WHITE), gap.lines.map { it.color })
        assertEquals(listOf(true, true, false, true), gap.lines.map { it.detected })
        assertEquals(listOf(null, null, null, null), LaneLayout.from(road.snapshot(road.lanes(-1.75, 1.75, 8.75)))!!.lines.map { it.color })
        // Three colours for four polylines cannot be matched up: no colours, so no cut.
        val misaligned = LaneLayout.from(road.snapshot(road.lanes(-5.25, -1.75, 1.75, 5.25).colored("white", "yellow", "white")))!!
        assertEquals(3 to 2, misaligned.laneCount to misaligned.egoLane)
        assertTrue(misaligned.lines.all { it.color == null })
    }

    /** Mean |x| distance from layout line [i] to the points of [polyline] below the vanishing point. */
    private fun meanDeviation(layout: LaneLayout, i: Int, polyline: List<List<Double>>): Double =
        polyline.filter { it[1] > layout.vpY + 15 }.map { abs(it[0] - layout.lineX(i, it[1])) }.average()

    /** x of a painted polyline at row [y]: its own straight least-squares fit over the points below the vanishing point. */
    private fun paintedX(polyline: List<List<Double>>, vpY: Double, y: Double): Double {
        val pts = polyline.filter { it[1] > vpY + 15 }
        val my = pts.map { it[1] }.average()
        val mx = pts.map { it[0] }.average()
        val b = pts.sumOf { (it[0] - mx) * (it[1] - my) } / pts.sumOf { (it[1] - my) * (it[1] - my) }
        return mx + b * (y - my)
    }

    /** The painted polyline nearest to layout line [i]. */
    private fun closestPolyline(layout: LaneLayout, i: Int, lanes: Lanes): List<List<Double>>? =
        lanes.laneBoundaries.filter { p -> p.count { it[1] > layout.vpY + 15 } >= 2 }.minByOrNull { meanDeviation(layout, i, it) }
}
