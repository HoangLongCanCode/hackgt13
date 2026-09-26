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
