package com.drivingassist.glass.perception

import com.ksr.copilot.context.FrameTiming
import com.ksr.copilot.context.LanesState
import com.ksr.copilot.context.ObjectState
import com.ksr.copilot.context.WorldModel
import com.ksr.copilot.context.WorldSnapshot
import com.ksr.copilot.perception.ImageSize
import com.ksr.copilot.perception.Lanes
import com.ksr.copilot.perception.ObjectClass
import com.ksr.copilot.perception.PerceptionCodec
import com.ksr.copilot.perception.PerceptionFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * FILL_CENTER crop maths for the real target geometry: a 1280x720 upright frame from the laptop (sim clip or
 * 16:9 LIVE analysis) drawn on the Galaxy Tab S9 in landscape (2560x1600 view, 16:10).
 *
 * Expected values come from an independent implementation of the cover-and-crop rule ([expectedBox]), not
 * from [com.drivingassist.glass.PreviewCoordinates], so this checks the app's mapping end to end:
 * scale = max(2560/1280, 1600/720) = 2.2222, displayed 2844.4 x 1600, 142.2 px cropped on each side, so only
 * image columns 64..1216 are visible and y maps 1:1 (y / 720).
 */
class TabS9FillCenterTest {
    private val viewW = 2560f
    private val viewH = 1600f
    private val imgW = 1280.0
    private val imgH = 720.0
    private val scale = max(viewW / imgW, viewH / imgH)                 // 2.2222
    private val cropX = (imgW * scale - viewW) / 2.0                      // 142.22 px of the view

    private val timing = FrameTiming("s", 10, 10, 5.0, 0, 0, 0.0, 0.0, 0.0, null, null, 1, 0)

    private fun car(id: Int, bbox: List<Double>, distance: Double = 10.0) = ObjectState(
        id = id, cls = ObjectClass.CAR, bbox = bbox, confidence = 0.9, ageFrames = 5, firstSeenPts = 0.0, lastSeenPts = 5.0,
        visible = true, distanceMeters = distance,
    )

    private fun world(objects: List<ObjectState>, lanes: LanesState? = null) = WorldSnapshot(
        timing = timing, image = ImageSize(1280, 720), objects = objects.associateBy { it.id }, lanes = lanes, perceptionStale = false,
    )

    /** Independent FILL_CENTER: image px box [x1, y1, x2, y2] -> overlay [x, y, w, h] clipped to 0..1, null if not visible. */
    private fun expectedBox(b: List<Double>): FloatArray? {
        val x1 = ((b[0] * scale - cropX) / viewW).coerceAtLeast(0.0)
        val x2 = ((b[2] * scale - cropX) / viewW).coerceAtMost(1.0)
        val y1 = (b[1] * scale / viewH).coerceAtLeast(0.0)
        val y2 = (b[3] * scale / viewH).coerceAtMost(1.0)
        if (x2 - x1 < 0.002 || y2 - y1 < 0.002) return null
        return floatArrayOf(x1.toFloat(), y1.toFloat(), (x2 - x1).toFloat(), (y2 - y1).toFloat())
    }

    private fun assertBox(msg: String, want: FloatArray, got: FloatArray) {
        for (i in 0..3) assertEquals("$msg [$i] want ${want.contentToString()} got ${got.contentToString()}", want[i], got[i], 1e-4f)
    }

    @Test
    fun `crop constants for 1280x720 into 2560x1600`() {
        assertEquals(2.2222, scale.toDouble(), 1e-4)
        assertEquals(142.22, cropX, 0.01)
        // visible image columns: 64 .. 1216 (5 % of the width cropped on each side), rows: all
        assertEquals(64.0, cropX / scale, 1e-6)
        assertEquals(1216.0, (cropX + viewW) / scale, 1e-6)
    }

    @Test
    fun `boxes map with FILL_CENTER, clip at the cropped edges and drop when fully cropped`() {
        val centre = car(1, listOf(320.0, 360.0, 640.0, 540.0))            // x = (711.1 - 142.2) / 2560
        val leftEdge = car(2, listOf(0.0, 300.0, 100.0, 400.0), 11.0)       // straddles the crop: clipped at x = 0
        val rightCropped = car(3, listOf(1220.0, 300.0, 1280.0, 400.0), 12.0) // entirely in the cropped strip
        val bottom = car(4, listOf(600.0, 500.0, 900.0, 720.0), 13.0)        // touches the bottom edge: y2 = 1
        val v = VisionMapper.map(world(listOf(centre, leftEdge, rightCropped, bottom)), viewW, viewH)
        val byId = v.vehicles.associateBy { it.id }

        assertBox("centre", floatArrayOf(0.22222f, 0.5f, 0.27778f, 0.25f), byId.getValue(1).box)
        assertBox("centre (independent)", expectedBox(centre.bbox)!!, byId.getValue(1).box)
        assertBox("left edge", floatArrayOf(0f, 300f / 720f, 0.03125f, 100f / 720f), byId.getValue(2).box)
        assertNull("fully cropped box is not drawn", byId[3])
        assertNull(expectedBox(rightCropped.bbox))
        assertBox("bottom", expectedBox(bottom.bbox)!!, byId.getValue(4).box)
        assertEquals(1f, byId.getValue(4).box[1] + byId.getValue(4).box[3], 1e-4f)
    }

    @Test
    fun `lane points map to the same crop and are clipped to the view`() {
        val left = listOf(listOf(560.0, 400.0), listOf(64.0, 720.0))        // bottom point exactly on the crop edge
        val right = listOf(listOf(720.0, 400.0), listOf(1240.0, 720.0))     // bottom point inside the cropped strip
        val lanes = LanesState(Lanes(1, 1, listOf(left, right), 0.8), 1, 1, 5.0, 0.1)
        val v = VisionMapper.map(world(emptyList(), lanes), viewW, viewH)
        val l = v.lanes.first { it.id == "left" }.points
        val r = v.lanes.first { it.id == "right" }.points
        assertEquals(0f, l.first().x, 1e-4f)
        assertEquals(1f, l.first().y, 1e-4f)
        assertEquals(((560.0 * scale - cropX) / viewW).toFloat(), l.last().x, 1e-4f)
        assertEquals((400.0 / 720.0).toFloat(), l.last().y, 1e-4f)
        // right line leaves the view through x = 1 before reaching the bottom: clipped there, still near -> far
        assertEquals(1f, r.first().x, 1e-4f)
        assertTrue(r.first().y < 1f && r.first().y > r.last().y)
        (l + r).forEach { assertTrue(it.x in 0f..1f && it.y in 0f..1f) }
    }

    /** A real 1280x720 wave-1 frame written by the Python server: every drawn vehicle matches the independent rule. */
    @Test
    fun `real 1280x720 server frame maps exactly like the independent FILL_CENTER rule`() {
        val file = File("../../contracts/samples/v2/perception.frame.wave1.city.json")
        assumeTrue("contracts sample not present", file.isFile)
        val frame = PerceptionCodec.decode(file.readText()) as PerceptionFrame
        assumeTrue("sample is ${frame.image.width}x${frame.image.height}", frame.image.width == 1280 && frame.image.height == 720)
        val world = WorldModel(clockMs = { frame.serverTimeMs }, clockNs = { 0L }).update(frame, frame.serverTimeMs, 0L)
        val v = VisionMapper.map(world, viewW, viewH)
        assertTrue("no vehicles mapped", v.vehicles.isNotEmpty())
        var cropped = 0
        for (veh in v.vehicles) {
            val o = world.objects.getValue(veh.id)
            assertBox("track ${veh.id} ${o.bbox}", expectedBox(o.bbox)!!, veh.box)
            if (o.bbox[0] < 64.0 || o.bbox[2] > 1216.0) cropped++
        }
        val drawable = world.objects.values.count {
            it.visible && it.cls in setOf(ObjectClass.CAR, ObjectClass.TRUCK, ObjectClass.BUS, ObjectClass.MOTORCYCLE, ObjectClass.BICYCLE, ObjectClass.PEDESTRIAN, ObjectClass.RIDER) &&
                (it.distanceMeters ?: 0.0) > 0.0 && expectedBox(it.bbox) != null
        }
        assertEquals(min(drawable, VisionMapper.MAX_VEHICLES), v.vehicles.size)
        println("${file.name}: ${v.vehicles.size} vehicles at 2560x1600, $cropped clipped by the 64..1216 px crop")
    }
}
