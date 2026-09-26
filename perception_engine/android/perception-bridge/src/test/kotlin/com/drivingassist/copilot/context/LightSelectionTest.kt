package com.drivingassist.copilot.context

import com.drivingassist.copilot.context.TestFrames.frame
import com.drivingassist.copilot.context.TestFrames.light
import com.drivingassist.copilot.perception.LightState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seen end to end on BDD clip b1ff4656 (live fake tablet): an intersection shows several light heads at
 * about the same distance with different colours; the nearest one alternated between them every few
 * frames and "Red light ahead." / "Light is green." were spoken back and forth.
 */
class LightSelectionTest {
    @Test
    fun `the chosen light is sticky when another head is about as close`() {
        val world = WorldModel(clockMs = { 0L })
        val engine = DrivingContextEngine()
        val events = mutableListOf<DrivingEvent>()
        repeat(30) { i ->
            // Head 1 (RED) and head 2 (GREEN) swap 0.3 m back and forth: same intersection.
            val d1 = if (i % 2 == 0) 24.0 else 24.3
            val d2 = if (i % 2 == 0) 24.3 else 24.0
            val f = frame(i.toLong(), objects = listOf(light(1, LightState.RED, distance = d1), light(2, LightState.GREEN, distance = d2)))
            events += engine.evaluate(world.update(f)).events
        }
        val lightEvents = events.filter { it.type.name.startsWith("TRAFFIC_LIGHT_") }
        assertEquals(1, lightEvents.size, "one announcement, no flapping: $lightEvents")
        assertEquals(1, engine.context.value.trafficLight!!.trackId)
    }

    @Test
    fun `a clearly closer light takes over`() {
        val world = WorldModel(clockMs = { 0L })
        val engine = DrivingContextEngine()
        engine.evaluate(world.update(frame(0, objects = listOf(light(1, LightState.GREEN, distance = 40.0)))))
        val r = engine.evaluate(world.update(frame(1, objects = listOf(light(1, LightState.GREEN, distance = 40.0), light(2, LightState.RED, distance = 20.0)))))
        assertEquals(2, r.context.trafficLight!!.trackId)
        assertTrue(r.events.any { it.type == DrivingEventType.TRAFFIC_LIGHT_RED })
    }
}
