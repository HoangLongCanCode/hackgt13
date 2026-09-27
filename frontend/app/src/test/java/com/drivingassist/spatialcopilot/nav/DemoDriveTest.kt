package com.drivingassist.spatialcopilot.nav

import com.drivingassist.copilot.context.DrivingContext
import com.drivingassist.copilot.context.DrivingContextEngine
import com.drivingassist.copilot.context.DrivingEvent
import com.drivingassist.copilot.context.DrivingEventType
import com.drivingassist.copilot.context.FollowingState
import com.drivingassist.copilot.context.LaneAction
import com.drivingassist.copilot.context.LaneGuidance
import com.drivingassist.copilot.context.LaneLayout
import com.drivingassist.copilot.context.Priority
import com.drivingassist.copilot.context.SpeedLimitSource
import com.drivingassist.copilot.context.WorldSnapshot
import com.drivingassist.copilot.perception.ImageSize
import com.drivingassist.spatialcopilot.ar.ArInput
import com.drivingassist.spatialcopilot.ar.ArSceneBuilder
import com.drivingassist.spatialcopilot.ar.FillCenter
import com.drivingassist.spatialcopilot.ar.Ground
import com.drivingassist.spatialcopilot.ar.GroundProjector
import com.drivingassist.spatialcopilot.ar.LaneArrowStyle
import com.drivingassist.spatialcopilot.ar.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DemoDriveTest {
    private val projector = GroundProjector(525.0, 480.0, 270.0, 250.0, 1.25, 960, 540)
    private val w = DemoDrive.LANE_W

    private fun bend(z: Double) = 0.0012 * z * z

    /** The lane lines of the script before the lane change was added (ego lane 2 of 3, no shift). */
    private fun linesBefore(): List<List<List<Double>>> = listOf(-1.5, -0.5, 0.5, 1.5).map { k ->
        (0..12).mapNotNull { i ->
            val z = 3.0 + i * 5.0
            projector.toImage(Ground(bend(z) + k * 3.6, z))?.let { listOf(it.x.toDouble(), it.y.toDouble()) }
        }
    }

    /** Road lateral of an image polyline at [z] metres ahead (linear between its points). */
    private fun lateralOf(line: List<List<Double>>, z: Double): Double {
        val g = line.map { projector.toGround(it[0], it[1])!! }
        val i = g.indices.first { it + 1 < g.size && g[it].z <= z && z <= g[it + 1].z }
        val f = (z - g[i].z) / (g[i + 1].z - g[i].z)
        return g[i].x + f * (g[i + 1].x - g[i].x)
    }

    private fun lines(world: WorldSnapshot) = world.lanes!!.lanes.laneBoundaries

    @Test
    fun `the first 10 s keep the geometry the AR tests are built on`() {
        for (t in listOf(0.0, 10.0)) {
            val world = DemoDrive.world(t, 1)
            assertEquals(ImageSize(960, 540), world.image)
            assertEquals(DemoDrive.camera, world.camera)
            assertEquals("lines at $t s", linesBefore(), lines(world))
            assertEquals(2, world.lanes!!.currentLane)
            assertEquals(3, world.lanes!!.laneCount)
            val anchors = world.road!!.road.anchorPoints
            assertEquals(listOf("ego_lane_center_near", "ego_lane_center_mid", "ego_lane_center_far"), anchors.map { it.name })
            for ((a, f) in anchors.zip(listOf(0.85, 0.6, 0.35))) {
                val row = 250.0 + f * 290.0
                val z = projector.forwardAtRow(row)!!
                assertEquals(listOf(projector.toImage(Ground(bend(z), z))!!.x.toDouble(), row), a.xy)
                assertEquals(listOf(bend(z), z), a.groundXZ)
            }
        }
        // Pixel values of the script as it was.
        val l = lines(DemoDrive.world(0.0, 1))
        assertEquals(171.5588, l[1][0][0], 1e-3)
        assertEquals(465.6445, l[1][0][1], 1e-3)
        assertEquals(792.1649, l[2][0][0], 1e-3)
        assertEquals(534.6884, l[2][12][0], 1e-3)
        val near = DemoDrive.world(0.0, 1).road!!.road.anchor("ego_lane_center_near")!!
        assertEquals(481.6214, near.x, 1e-3)
        assertEquals(496.5, near.y, 1e-9)
    }

    @Test
    fun `the car moves from lane 2 into lane 3 and the lines slide left by one lane width`() {
        for (t in listOf(0.0, 6.0, 11.9, 13.7)) assertEquals("lane at $t s", 2, DemoDrive.world(t, 1).lanes!!.currentLane)
        for (t in listOf(13.8, 15.6, 20.0, 39.9)) assertEquals("lane at $t s", 3, DemoDrive.world(t, 1).lanes!!.currentLane)
        for (t in listOf(0.0, 13.0, 20.0)) {
            val lanes = DemoDrive.world(t, 1).lanes!!
            assertEquals(3, lanes.laneCount)
            assertEquals(0.9, lanes.lanes.confidence, 1e-9)
        }
        assertEquals("the next loop starts in lane 2 again", 2, DemoDrive.world(41.0, 1).lanes!!.currentLane)

        val before = linesBefore()
        val after = lines(DemoDrive.world(20.0, 1))
        assertEquals(before.size, after.size)
        for (j in before.indices) {
            assertEquals(before[j].size, after[j].size)
            for (i in before[j].indices) {
                val g0 = projector.toGround(before[j][i][0], before[j][i][1])!!
                val g1 = projector.toGround(after[j][i][0], after[j][i][1])!!
                assertEquals("line $j point $i forward", g0.z, g1.z, 1e-3)
                assertEquals("line $j point $i lateral", g0.x - w, g1.x, 0.05)
            }
        }

        // Smooth: the near end of the lane 1/2 line moves left every frame, never by more than 0.15 m.
        val xs = (0..(3.5 * 15).toInt()).map { i -> lines(DemoDrive.world(12.0 + i / 15.0, 1))[1][0] }
            .map { projector.toGround(it[0], it[1])!!.x }
        assertTrue(xs.zipWithNext().all { (a, b) -> b <= a + 1e-6 && a - b < 0.15 })
        assertEquals(xs.first() - w, xs.last(), 0.05)
        assertEquals(0.0, DemoDrive.laneShift(12.0), 1e-9)
        assertEquals(0.5, DemoDrive.laneShift(13.75), 1e-9)
        assertEquals(1.0, DemoDrive.laneShift(15.5), 1e-9)
    }

    @Test
    fun `the ego anchors stay inside the lane the car reports`() {
        for (i in 0 until 160) {
            val t = i * 0.25
            val world = DemoDrive.world(t, 1)
            val lane = world.lanes!!.currentLane!!
            val mid = world.road!!.road.anchor("ego_lane_center_mid")!!
            val g = projector.toGround(mid.x, mid.y)!!
            assertEquals("anchor groundXZ at $t s", mid.lateralMeters!!, g.x, 0.02)
            val left = lateralOf(lines(world)[lane - 1], g.z)
            val right = lateralOf(lines(world)[lane], g.z)
            assertTrue("anchor inside lane $lane at $t s", g.x > left + 0.5 && g.x < right - 0.5)
        }
    }

    @Test
    fun `the lane layout of the scripted lines has three lanes and follows the car into lane 3`() {
        for (i in 0 until 160) {
            val t = i * 0.25
            val world = DemoDrive.world(t, 1)
            val layout = world.laneLayout
            assertNotNull("layout at $t s", layout)
            assertEquals("the bridge's layout of the snapshot", LaneLayout.from(world.copy(laneLayout = null)), layout)
            assertEquals("lanes at $t s", 3, layout!!.laneCount)
            assertTrue("usable at $t s: q ${layout.quality}", layout.usable())
            // The car's lane flips when the camera crosses the line, half way through the move (13.75 s).
            if (abs(t % DemoDrive.LOOP_S - DemoDrive.CHANGE_MID_S) > 0.3) assertEquals("ego lane at $t s", world.lanes!!.currentLane, layout.egoLane)
        }
        // So DEMO draws an arrow on every lane: the exit lane green, and once the debounce has run, the car's lane red and the exit lane blinking.
        val b = ArSceneBuilder()
        val guidance = LaneGuidance(LaneAction.CHANGE_LANE_RIGHT, 2, 3, listOf(3), 1, Priority.UPCOMING_NAVIGATION, "")
        var scene = b.build(ArInput(DemoDrive.world(5.0, 1), DrivingContext(laneGuidance = guidance), DemoDrive.route(5.0, 0L), 350.0, false), 2560f, 1600f, 1L)
        for (i in 1..60) {
            val t = 5.0 + i * 0.016
            scene = b.build(ArInput(DemoDrive.world(t, 1), DrivingContext(laneGuidance = guidance), DemoDrive.route(t, 0L), DemoDrive.exitDistance(t), false), 2560f, 1600f, 1L + i * 16_000_000L)
        }
        assertEquals(listOf(1, 2, 3), scene.laneArrows.map { it.lane }.sortedBy { it })
        assertEquals(LaneArrowStyle.WRONG, scene.laneArrows.single { it.lane == 2 }.style)
        assertEquals(LaneArrowStyle.TARGET_BLINK, scene.laneArrows.single { it.lane == 3 }.style)
        assertEquals(LaneArrowStyle.OTHER, scene.laneArrows.single { it.lane == 1 }.style)
    }

    /** Even-odd point-in-polygon. */
    private fun inside(p: Vec2, polygon: List<Vec2>): Boolean {
        var c = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) c = !c
            j = i
        }
        return c
    }

    @Test
    fun `the drivable road is the area between the outer lines and the lane arrows lie inside it`() {
        for (t in listOf(0.0, 13.0, 20.0)) {
            val world = DemoDrive.world(t, 1)
            val road = world.road!!.road.drivablePolygon
            assertTrue("at most 32 points like the server's: ${road.size}", road.size in 3..32)
            assertEquals("at $t s", lines(world).first() + lines(world).last().asReversed(), road)
        }
        val b = ArSceneBuilder()
        val guidance = LaneGuidance(LaneAction.CHANGE_LANE_RIGHT, 2, 3, listOf(3), 1, Priority.UPCOMING_NAVIGATION, "")
        var scene = b.build(ArInput(DemoDrive.world(5.0, 1), DrivingContext(laneGuidance = guidance), DemoDrive.route(5.0, 0L), 350.0, false), 2560f, 1600f, 1L)
        for (i in 1..30) {
            val t = 5.0 + i * 0.016
            scene = b.build(ArInput(DemoDrive.world(t, 1), DrivingContext(laneGuidance = guidance), DemoDrive.route(t, 0L), DemoDrive.exitDistance(t), false), 2560f, 1600f, 1L + i * 16_000_000L)
        }
        // The canvas clips the arrows to the outline (DEMO exercises that path); they lie inside it anyway, and still start
        // 6 m ahead since the outline ends at 3 m.
        val map = FillCenter(960, 540, 2560f, 1600f)
        assertEquals(DemoDrive.world(5.0 + 30 * 0.016, 1).road!!.road.drivablePolygon.map { map.point(it[0], it[1]) }, scene.drivable)
        assertEquals(3, scene.laneArrows.size)
        for (a in scene.laneArrows) {
            assertTrue("lane ${a.lane} inside the road", a.outline.all { inside(it, scene.drivable) })
            assertEquals("lane ${a.lane} starts 6 m ahead", map.point(0.0, projector.rowAt(6.0)).y, a.outline.maxOf { it.y }, 1f)
        }
    }

    @Test
    fun `the car ahead is in lane 3 and only in the ego path after the change`() {
        for (i in 0 until 160) {
            val t = i * 0.25
            val world = DemoDrive.world(t, 1)
            val car = world.objects.getValue(DemoDrive.LEAD_ID)
            val d = car.distanceMeters!!
            val g = projector.toGround((car.bbox[0] + car.bbox[2]) / 2, car.bbox[3])!!
            assertEquals("box on the road at its distance, $t s", d, g.z, 0.05)
            assertEquals(car.lateralMeters!!, g.x, 0.05)
            assertTrue("car in lane 3 at $t s", g.x > lateralOf(lines(world)[2], d) && g.x < lateralOf(lines(world)[3], d))
            assertEquals("in path at $t s", world.lanes!!.currentLane == 3, car.inEgoPath)
        }
        val beforeChange = DemoDrive.world(5.0, 1)
        assertFalse(beforeChange.objects.getValue(DemoDrive.LEAD_ID).inEgoPath!!)
        assertEquals("right neighbour lane", w, beforeChange.objects.getValue(DemoDrive.LEAD_ID).lateralMeters!! - bend(30.0), 1e-9)
        assertNull(beforeChange.leadVehicle())
        assertEquals(DemoDrive.LEAD_ID, DemoDrive.world(16.0, 1).leadVehicle()!!.id)
    }

    @Test
    fun `the car ahead closes into TOO CLOSE range around 24 s and drops back`() {
        for (t in listOf(0.0, 10.0, 15.9, 32.0, 35.0, 39.9)) assertEquals("distance at $t s", 30.0, DemoDrive.leadDistance(t), 1e-9)
        assertEquals(5.0, DemoDrive.leadDistance(24.0), 1e-9)
        for (i in 0..8) {
            val t = 23.0 + i * 0.25
            assertTrue("distance at $t s", DemoDrive.leadDistance(t) < 7.0)
        }
        val closing = DemoDrive.world(20.0, 1).objects.getValue(DemoDrive.LEAD_ID)
        assertTrue(closing.relativeSpeedMps!! < -1.0)
        assertEquals(closing.distanceMeters!! / -closing.relativeSpeedMps!!, closing.ttcSeconds!!, 1e-9)
        val opening = DemoDrive.world(28.0, 1).objects.getValue(DemoDrive.LEAD_ID)
        assertTrue(opening.relativeSpeedMps!! > 1.0)
        assertNull(opening.ttcSeconds)
    }

    @Test
    fun `a speed limit 55 sign passes on the right road side from 2 to 7 s`() {
        for (t in listOf(0.0, 1.9, 7.1, 12.0, 30.0)) assertTrue("no sign at $t s", DemoDrive.world(t, 1).signs.isEmpty())
        var lastDistance = Double.MAX_VALUE
        var lastSeen = 0
        for (i in 0..20) {
            val t = 2.0 + i * 0.25
            val world = DemoDrive.world(t, 1)
            val s = world.signs.single()
            assertEquals("demo-sign-55", s.key)
            assertEquals("speedLimit55", s.sign.signClass)
            assertEquals(55, s.sign.speedLimit)
            assertEquals(0.95, s.sign.confidence, 1e-9)
            assertEquals(2.0, s.firstSeenPts, 1e-9)
            assertEquals(t, s.lastSeenPts, 1e-9)
            assertTrue(s.seenCount >= lastSeen)
            lastSeen = s.seenCount
            val d = s.sign.distanceMeters!!
            assertTrue("distance at $t s", d in 15.0..60.0 && d < lastDistance)
            lastDistance = d
            val b = s.sign.bbox
            assertTrue("box inside the image at $t s", b[0] >= 0 && b[2] <= 960 && b[1] >= 0 && b[3] <= 540 && b[0] < b[2] && b[1] < b[3])
            assertTrue("right of the road at $t s", projector.lateralAt(b[0], d) > lateralOf(lines(world)[3], d) + 3.0)
            assertTrue("above the road at $t s", b[3] < projector.rowAt(d))
        }
        assertTrue("seen about 15 times a second", lastSeen in 70..80)
        assertEquals("next loop: first seen again at 2 s into it", 42.0, DemoDrive.world(42.5, 1).signs.single().firstSeenPts, 1e-9)
    }

    @Test
    fun `the Driving Context gets lane change right, keep lane, TOO CLOSE and the speed limit`() {
        val engine = DrivingContextEngine()
        val contexts = ArrayList<Pair<Double, DrivingContext>>()
        val events = ArrayList<DrivingEvent>()
        for (i in 0..600) {
            val t = i / 15.0
            val r = engine.evaluate(DemoDrive.world(t, i.toLong()), DemoDrive.navigation(t))
            contexts += t to r.context
            events += r.events
        }
        fun at(t: Double) = contexts.minBy { abs(it.first - t) }.second

        for (t in listOf(0.0, 5.0, 11.0, 13.5)) {
            val g = at(t).laneGuidance!!
            assertEquals("lane action at $t s", LaneAction.CHANGE_LANE_RIGHT, g.action)
            assertEquals(2, g.currentLane)
            assertEquals(3, g.laneCount)
            assertEquals(listOf(3), g.targetLanes)
            assertEquals(1, g.lanesToMove)
        }
        for (t in listOf(14.0, 16.0, 25.0, 39.0)) {
            val g = at(t).laneGuidance!!
            assertEquals("lane action at $t s", LaneAction.KEEP_LANE, g.action)
            assertEquals(3, g.currentLane)
        }
        val change = events.first { it.type == DrivingEventType.CHANGE_LANE_RIGHT }
        assertTrue("lane change asked for at the start", change.ptsSeconds < 1.0)
        assertNotNull(change.speech)
        val keep = events.first { it.type == DrivingEventType.KEEP_LANE }
        assertEquals("keep lane once the lane number flips", 13.75, keep.ptsSeconds, 0.1)

        for (t in listOf(1.0, 5.0, 13.0)) {
            assertEquals("following at $t s", FollowingState.NORMAL, at(t).following.state)
            assertNull("the car in the next lane is not the lead at $t s", at(t).following.leadTrackId)
        }
        assertEquals(DemoDrive.LEAD_ID, at(16.0).following.leadTrackId)
        assertEquals(FollowingState.NORMAL, at(16.0).following.state)
        for (t in listOf(23.0, 24.0, 25.0)) {
            val f = at(t).following
            assertEquals("following at $t s", FollowingState.CRITICAL, f.state)
            assertEquals(DemoDrive.LEAD_ID, f.leadTrackId)
            assertTrue(f.distanceMeters!! < 7.0)
        }
        assertEquals(FollowingState.NORMAL, at(36.0).following.state)
        val tooClose = events.first { it.type == DrivingEventType.VEHICLE_TOO_CLOSE }
        assertTrue(tooClose.ptsSeconds in 18.0..24.0)

        assertNull("no limit known before the sign", at(1.0).speedLimit)
        assertNull("the sign is not confirmed before 0.8 s of reads", at(2.5).speedLimit)
        for ((t, c) in contexts) {
            if (t < 3.0 || t >= DemoDrive.LOOP_S) continue
            assertEquals("speed limit at $t s", 55, c.speedLimit)
            assertEquals(SpeedLimitSource.SIGN, c.speedLimitSource)
        }
        // The loop wraps at 40 s: the exit was passed, so the sign's limit no longer applies until it is read again.
        assertNull("cleared after passing the exit", at(DemoDrive.LOOP_S).speedLimit)
        assertEquals("announced once", 1, events.count { it.type == DrivingEventType.SPEED_LIMIT })
    }
}
