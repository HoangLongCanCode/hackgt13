package com.drivingassist.spatialcopilot.ar

import com.drivingassist.copilot.context.WorldModel
import com.drivingassist.copilot.perception.PerceptionCodec
import com.drivingassist.copilot.perception.PerceptionFrame
import com.drivingassist.spatialcopilot.nav.DemoDrive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArGeometryTest {
    private val samples = File("../../perception_engine/contracts/samples/v2")

    private fun frame(name: String): PerceptionFrame =
        PerceptionCodec.decode(File(samples, name).readText()) as PerceptionFrame

    @Test
    fun `fill center on the Tab S9 crops 142 px per side and keeps rows`() {
        val m = FillCenter(1280, 720, 2560f, 1600f)
        assertEquals(2.2222f, m.scale, 1e-4f)
        assertEquals(-142.22f, m.dx, 0.01f)
        assertEquals(0f, m.dy, 1e-4f)
        // Image column 64 is the left edge of the screen, 1216 the right edge.
        assertEquals(0f, m.point(64.0, 0.0).x, 0.01f)
        assertEquals(2560f, m.point(1216.0, 720.0).x, 0.01f)
        assertEquals(1600f, m.point(1216.0, 720.0).y, 0.01f)
        assertNull("a box entirely in the cropped strip is not drawn", m.box(listOf(1220.0, 300.0, 1280.0, 400.0)))
        val clipped = m.box(listOf(0.0, 300.0, 100.0, 400.0))!!
        assertEquals(0f, clipped.left, 0.01f)
    }

    @Test
    fun `ground projection matches the server's groundXZ on real frames`() {
        for (name in listOf("perception.frame.wave1.live.json", "perception.frame.wave1.city.json")) {
            val f = frame(name)
            val world = WorldModel(clockMs = { 0L }).update(f)
            val p = GroundProjector.from(world)!!
            for (a in f.road!!.anchorPoints) {
                val g = p.toGround(a.x, a.y)!!
                assertEquals("$name ${a.name} lateral", a.lateralMeters!!, g.x, 0.02)
                assertEquals("$name ${a.name} forward", a.forwardMeters!!, g.z, 0.02)
                // And back: the road point lands on the anchor pixel.
                val px = p.toImage(g)!!
                assertEquals(a.x, px.x.toDouble(), 0.5)
                assertEquals(a.y, px.y.toDouble(), 0.5)
            }
        }
    }

    @Test
    fun `ego lane from the lane lines follows the road and gives the lane width`() {
        val world = DemoDrive.world(0.0, 1)
        val p = GroundProjector.from(world)!!
        val lane = EgoLane.from(world, p)
        assertEquals(EgoLane.Source.LANE_LINES, lane.source)
        assertEquals(3.6, lane.widthMeters, 0.15)
        // DemoDrive's road centre is x = 0.0012 z^2.
        for (z in listOf(6.0, 12.0, 20.0, 30.0)) assertEquals("x at $z m", 0.0012 * z * z, lane.x(z), 0.12)
    }

    @Test
    fun `ego lane falls back to anchors and then to the camera axis`() {
        val live = WorldModel(clockMs = { 0L }).update(frame("perception.frame.wave1.live.json"))
        val p = GroundProjector.from(live)!!
        val noLines = live.copy(lanes = null)
        val anchors = EgoLane.from(noLines, p)
        assertEquals(EgoLane.Source.ANCHORS, anchors.source)
        assertEquals(0.0, anchors.c, 0.0)
        assertEquals(EgoLane.Source.CAMERA_AXIS, EgoLane.from(noLines.copy(road = null), p).source)
    }

    @Test
    fun `ego lane stays on the reported lane through a lane change`() {
        // DEMO slides the lines one lane left between 12 and 15.5 s; the anchors follow the lane the model reports.
        var t = DemoDrive.CHANGE_START_S
        while (t <= DemoDrive.CHANGE_END_S) {
            val world = DemoDrive.world(t, 1)
            val p = GroundProjector.from(world)!!
            val anchor = world.road!!.road.anchor("ego_lane_center_near")!!
            val g = p.toGround(anchor.x, anchor.y)!!
            val lane = EgoLane.from(world, p)
            assertEquals("source at $t s", EgoLane.Source.LANE_LINES, lane.source)
            assertEquals("centre at $t s", g.x, lane.x(g.z), 0.35)
            t += 0.05
        }
    }

    @Test
    fun `camera axis and anchors take their heading from the road's vanishing point`() {
        val demo = DemoDrive.world(0.0, 1).copy(lanes = null, laneLayout = null)
        val p = GroundProjector.from(demo)!!
        fun withVp(vpX: Double, anchors: Boolean) = demo.copy(road = demo.road!!.copy(road = demo.road!!.road.let {
            it.copy(vanishingPoint = listOf(vpX, 250.0), anchorPoints = if (anchors) it.anchorPoints else emptyList())
        }))
        // A phone yawed ~12 degrees left of the road: vanishing point 115 px right of the principal point.
        val axis = EgoLane.from(withVp(595.0, anchors = false), p)
        assertEquals(EgoLane.Source.CAMERA_AXIS, axis.source)
        assertEquals((595.0 - 480.0) * kotlin.math.cos(p.pitch) / 525.0, axis.b, 1e-9)
        assertEquals("from under the camera", 0.0, axis.x(0.0), 1e-9)
        assertEquals("heading limited to 0.35", 0.35, EgoLane.from(withVp(900.0, anchors = false), p).b, 1e-9)
        assertEquals("no vanishing point: the camera axis", 0.0, EgoLane.from(demo.copy(road = null), p).b, 1e-9)
        // Anchors keep their lateral position halfway along their span, heading from the vanishing point.
        val plain = EgoLane.fromAnchors(withVp(595.0, anchors = true), p)!!
        val anchored = EgoLane.from(withVp(595.0, anchors = true), p)
        assertEquals(EgoLane.Source.ANCHORS, anchored.source)
        assertEquals(axis.b, anchored.b, 1e-9)
        assertEquals(plain.x(plain.maxZ / 2), anchored.x(plain.maxZ / 2), 1e-9)
        // A lane-lines fit keeps its own heading.
        val lines = DemoDrive.world(0.0, 1)
        val withRoadVp = lines.copy(road = lines.road!!.copy(road = lines.road!!.road.copy(vanishingPoint = listOf(595.0, 250.0))))
        assertEquals(EgoLane.from(lines.copy(laneLayout = null), p), EgoLane.from(withRoadVp.copy(laneLayout = null), p))
    }

    @Test
    fun `a lane fit may head up to 0_35 off the camera axis`() {
        val yawed = (0..10).map { i -> val z = 5.0 + i * 3; z to (0.2 + 0.3 * z) }
        assertEquals(0.3, EgoLane.fit(yawed, 3.5, EgoLane.Source.LANE_LINES)!!.b, 1e-6)
        val wild = (0..10).map { i -> val z = 5.0 + i * 3; z to 0.6 * z }
        assertEquals(EgoLane.MAX_SLOPE, EgoLane.fit(wild, 3.5, EgoLane.Source.LANE_LINES)!!.b, 1e-9)
    }

    @Test
    fun `arrows use the lane layout's camera height, else the server's clamped to a plausible mount height`() {
        val demo = DemoDrive.world(0.0, 1)
        fun height(server: Double?, layout: Double?) = GroundProjector.from(
            demo.copy(camera = demo.camera!!.copy(cameraHeightMeters = server), laneLayout = demo.laneLayout!!.copy(cameraHeightMeters = layout)),
        )!!.cameraHeightMeters
        assertEquals("the height the layout's widths were measured with", 1.6, height(2.4, 1.6), 1e-9)
        assertEquals(2.0, height(1.3, 2.6), 1e-9)
        assertEquals(1.37, height(1.37, null), 1e-9)
        // Clamped, not replaced: no step at the ends of the range.
        assertEquals(1.99, height(1.99, null), 1e-9)
        assertEquals(2.0, height(2.01, null), 1e-9)
        assertEquals(2.0, height(4.44, null), 1e-9)
        assertEquals(1.0, height(0.67, null), 1e-9)
        assertEquals(1.25, height(null, null), 1e-9)
        assertEquals(1.25, height(Double.NaN, Double.NaN), 1e-9)
        assertEquals(1.25, GroundProjector.from(demo.copy(laneLayout = null, camera = demo.camera!!.copy(cameraHeightMeters = null)))!!.cameraHeightMeters, 1e-9)
    }

    @Test
    fun `no camera intrinsics means nothing to anchor to`() {
        assertNull(GroundProjector.from(DemoDrive.world(0.0, 1).copy(camera = null)))
        assertNotNull(GroundProjector.from(DemoDrive.world(0.0, 1)))
    }

    @Test
    fun `least squares fit recovers a quadratic`() {
        val pts = (0..10).map { i -> val z = 5.0 + i * 3; z to (0.4 - 0.01 * z + 0.002 * z * z) }
        val f = EgoLane.fit(pts, 3.5, EgoLane.Source.LANE_LINES)!!
        assertEquals(0.4, f.a, 1e-6)
        assertEquals(-0.01, f.b, 1e-6)
        assertEquals(0.002, f.c, 1e-6)
        assertTrue("beyond the samples it continues straight", f.x(60.0) < 0.4 - 0.6 + 0.002 * 3600)
    }
}
