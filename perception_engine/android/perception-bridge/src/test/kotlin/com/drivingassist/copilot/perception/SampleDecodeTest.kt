package com.drivingassist.copilot.perception

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Decodes every golden v1 sample: the synthetic ones shipped with this module AND the real ones the
 * Python engine wrote to `perception_engine/contracts/samples/` (picked up automatically when present). A field
 * rename on either side breaks this test.
 */
class SampleDecodeTest {

    private fun bundledSamples(): List<File> {
        val url = javaClass.getResource("/samples") ?: fail("test resources /samples missing")
        return File(url.toURI()).listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
    }

    private fun contractSamples(): List<File> {
        val dir = System.getProperty("contracts.samples.dir")?.let(::File) ?: return emptyList()
        return dir.listFiles { f -> f.isFile && (f.extension == "json" || f.extension == "jsonl") }?.sortedBy { it.name } ?: emptyList()
    }

    @TestFactory
    fun `bundled synthetic samples decode`(): List<DynamicTest> =
        bundledSamples().map { f -> DynamicTest.dynamicTest(f.name) { checkFile(f) } }

    @TestFactory
    fun `contracts samples from the Python engine decode`(): List<DynamicTest> {
        val files = contractSamples()
        if (files.isEmpty()) {
            return listOf(DynamicTest.dynamicTest("no perception_engine/contracts/samples yet (skipped)") {
                assumeTrue(false, "perception_engine/contracts/samples is empty or missing: ${System.getProperty("contracts.samples.dir")}")
            })
        }
        return files.map { f -> DynamicTest.dynamicTest(f.name) { checkFile(f) } }
    }

    private fun checkFile(f: File) {
        val messages = ReplayPerceptionSource.readMessages(f)
        assertTrue(messages.isNotEmpty(), "${f.name}: no messages found")
        for ((i, text) in messages.withIndex()) {
            val msg = PerceptionCodec.decode(text)
            if (msg is UnknownMessage) fail("${f.name}[$i]: unknown message type '${msg.type}'")
            if (msg is PerceptionFrame) checkFrame(msg, "${f.name}[$i]")
            // Round trip through our encoder must give the same object.
            assertEquals(msg, PerceptionCodec.decode(PerceptionCodec.encode(msg)), "${f.name}[$i]: round trip changed the message")
        }
    }

    private fun checkFrame(frame: PerceptionFrame, where: String) {
        assertTrue(frame.schemaVersion in PerceptionFrame.MIN_SCHEMA_VERSION..PerceptionFrame.SCHEMA_VERSION, "$where schemaVersion ${frame.schemaVersion}")
        assertTrue(frame.image.width > 0 && frame.image.height > 0, where)
        assertEquals(2, frame.camera.principalPoint.size, where)
        for (o in frame.objects) {
            assertEquals(4, o.bbox.size, "$where object ${o.id}")
            assertTrue(o.cls != ObjectClass.UNKNOWN, "$where object ${o.id}: class not in the schema enum")
            if (o.lightState != null) assertEquals(ObjectClass.TRAFFIC_LIGHT, o.cls, "$where lightState on a non-light")
        }
        for (s in frame.signs) assertEquals(4, s.bbox.size, where)
        frame.lanes?.laneBoundaries?.forEach { line -> line.forEach { p -> assertEquals(2, p.size, where) } }
        frame.road?.anchorPoints?.forEach { a -> assertEquals(2, a.xy.size, where) }
    }

    @Test
    fun `city sample has the expected content`() {
        val f = bundledSamples().first { it.name == "synthetic_frame_city.json" }
        val frame = assertIs<PerceptionFrame>(PerceptionCodec.decode(f.readText()))
        assertEquals(128, frame.seq)
        assertEquals(SourceKind.VIDEO, frame.source.kind)
        val byId = frame.objects.associateBy { it.id }
        assertEquals(ObjectClass.CAR, byId.getValue(7).cls)
        assertEquals(12.4, byId.getValue(7).distanceMeters)
        assertEquals(true, byId.getValue(7).inEgoPath)
        assertEquals(ObjectClass.TRAFFIC_LIGHT, byId.getValue(42).cls)
        assertEquals(LightState.RED, byId.getValue(42).lightState)
        assertEquals(35, frame.signs.first().speedLimit)
        assertTrue(frame.signs[1].isStop)
        assertEquals(2, frame.lanes!!.currentLane)
        assertEquals(3, frame.lanes!!.laneCount)
        assertEquals(10.0, frame.road!!.anchor("ego_path_10m")!!.forwardMeters)
        assertEquals(1, frame.blockAges["depth"])
        assertEquals(BBox(575.2, 441.0, 724.8, 551.3), byId.getValue(7).box)
    }
}
