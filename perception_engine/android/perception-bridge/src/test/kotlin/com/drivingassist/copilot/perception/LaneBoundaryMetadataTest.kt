package com.drivingassist.copilot.perception

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The optional per-line `lanes.boundaryColors` / `lanes.boundaryStyles` arrays (parallel to `laneBoundaries`). */
class LaneBoundaryMetadataTest {
    private fun sample(name: String) = javaClass.getResource("/samples/v2/$name")!!.readText()

    /** [json] with the two arrays added to its lanes block. */
    private fun withMetadata(json: String, colors: List<String>, styles: List<String>): String {
        val message = Json.parseToJsonElement(json).jsonObject
        val lanes = message["lanes"]!!.jsonObject
        val extra = mapOf("boundaryColors" to JsonArray(colors.map(::JsonPrimitive)), "boundaryStyles" to JsonArray(styles.map(::JsonPrimitive)))
        return JsonObject(message + ("lanes" to JsonObject(lanes + extra))).toString()
    }

    @Test
    fun `wave-2 lanes carry a colour and a style per polyline`() {
        val update = assertIs<PerceptionUpdate>(PerceptionCodec.decode(withMetadata(sample("update_wave2.json"), listOf("yellow"), listOf("solid"))))
        val lanes = update.lanes!!
        assertEquals(listOf("yellow"), lanes.boundaryColors)
        assertEquals(listOf("solid"), lanes.boundaryStyles)
        assertEquals(lanes.laneBoundaries.size, lanes.boundaryColors!!.size)
    }

    @Test
    fun `wave-1 frames too, index i describes polyline i, and a round trip keeps them`() {
        val text = withMetadata(sample("frame_live_wave1.json"), listOf("white", "yellow"), listOf("dashed", "unknown"))
        val frame = assertIs<PerceptionFrame>(PerceptionCodec.decode(text))
        val lanes = frame.lanes!!
        assertEquals(2, lanes.laneBoundaries.size)
        assertEquals(listOf("white", "yellow"), lanes.boundaryColors)
        assertEquals(listOf("dashed", "unknown"), lanes.boundaryStyles)
        assertEquals(frame, PerceptionCodec.decode(PerceptionCodec.encode(frame)))
    }

    @Test
    fun `without per-line metadata both are null`() {
        val lanes = assertIs<PerceptionUpdate>(PerceptionCodec.decode(sample("update_wave2.json"))).lanes!!
        assertNull(lanes.boundaryColors)
        assertNull(lanes.boundaryStyles)
    }
}
