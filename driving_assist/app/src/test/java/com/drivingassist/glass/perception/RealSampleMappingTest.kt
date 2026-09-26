package com.drivingassist.glass.perception

import com.ksr.copilot.context.WorldModel
import com.ksr.copilot.perception.PerceptionCodec
import com.ksr.copilot.perception.PerceptionFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Real `perception.frame` samples written by the Python server (`<repo>/contracts/samples/v2/`, relative
 * to this module's directory, which is the unit-test working directory) through WorldModel +
 * VisionMapper, at the Tab S9's 2560x1600 landscape view. Skipped when the samples are not there.
 */
class RealSampleMappingTest {
    private val dir = File("../../contracts/samples/v2")

    private fun frames(): List<File> =
        dir.listFiles { f -> f.name.startsWith("perception.frame.") && f.extension == "json" }?.sortedBy { it.name }.orEmpty()

    @Test
    fun `real wave-1 frames map to vehicles, ego lanes and signs inside the view`() {
        val files = frames()
        assumeTrue("contracts/samples/v2 perception.frame.* not present", files.isNotEmpty())
        for (f in files) {
            val frame = PerceptionCodec.decode(f.readText()) as PerceptionFrame
            val world = WorldModel(clockMs = { frame.serverTimeMs }, clockNs = { 0L }).update(frame, frame.serverTimeMs, 0L)
            val v = VisionMapper.map(world, 2560f, 1600f)
            // 16:10 view, 16:9 image: FILL_CENTER crops ~5 % on each side, so a box at the image edge can drop out.
            val withDistance = frame.objects.count { it.cls.wire in ROAD_USERS && (it.distanceMeters ?: 0.0) > 0.0 }
            assertTrue(f.name, v.vehicles.size in 1..minOf(withDistance, VisionMapper.MAX_VEHICLES) || withDistance == 0)
            v.vehicles.forEach { assertInView(f.name, it.box) }
            v.signs.forEach { assertInView(f.name, it.box) }
            val knownLights = frame.objects.count { it.cls.wire == "traffic light" && it.lightState?.name in setOf("RED", "YELLOW", "GREEN") }
            assertTrue(f.name, v.signs.count { it.label.startsWith("LIGHT_") } <= knownLights)
            assertTrue("${f.name}: UNKNOWN lights are never drawn", v.signs.none { it.label == "LIGHT_UNKNOWN" })
            // The same frame at a 16:9 view (no crop) keeps every light and the nearest 8 road users.
            val full = VisionMapper.map(world, 1920f, 1080f)
            assertEquals(f.name, knownLights, full.signs.count { it.label.startsWith("LIGHT_") })
            assertEquals(f.name, minOf(withDistance, VisionMapper.MAX_VEHICLES), full.vehicles.size)
            val lanes = frame.lanes
            if (lanes != null && lanes.laneBoundaries.size >= 2 && lanes.currentLane != null) {
                val ids = v.lanes.map { it.id }
                assertTrue("${f.name}: ego lane ids in $ids", "left" in ids && "right" in ids)
                v.lanes.forEach { l ->
                    assertTrue(f.name, l.points.size >= 2)
                    assertTrue("${f.name}: near -> far", l.points.first().y >= l.points.last().y)
                    l.points.forEach { p -> assertTrue("${f.name}: $p", p.x in 0f..1f && p.y in 0f..1f) }
                }
            }
            println("${f.name}: ${v.vehicles.size} vehicles, ${v.lanes.map { it.id }}, signs ${v.signs.map { it.label }}")
        }
    }

    private fun assertInView(where: String, b: FloatArray) {
        assertTrue("$where: ${b.contentToString()}", b[0] >= 0f && b[1] >= 0f && b[0] + b[2] <= 1.0001f && b[1] + b[3] <= 1.0001f && b[2] > 0f && b[3] > 0f)
    }

    private companion object {
        val ROAD_USERS = setOf("car", "truck", "bus", "motorcycle", "bicycle", "pedestrian", "rider")
    }
}
