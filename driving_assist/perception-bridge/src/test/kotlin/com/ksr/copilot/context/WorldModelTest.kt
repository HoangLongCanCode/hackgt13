package com.ksr.copilot.context

import com.ksr.copilot.context.TestFrames.car
import com.ksr.copilot.context.TestFrames.frame
import com.ksr.copilot.context.TestFrames.lanes
import com.ksr.copilot.context.TestFrames.light
import com.ksr.copilot.perception.LightState
import com.ksr.copilot.perception.ObjectClass
import com.ksr.copilot.perception.Sign
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldModelTest {
    private var nowMs = 2_000_000L
    private val world = WorldModel(clockMs = { nowMs })

    @Test
    fun `merges objects by track id and publishes the snapshot`() {
        world.update(frame(0, objects = listOf(car(1, 20.0), car(2, 35.0, inPath = false))))
        val s = world.update(frame(1, objects = listOf(car(1, 19.5).copy(bbox = listOf(590.0, 395.0, 690.0, 470.0)), car(2, 35.0, inPath = false))))
        assertEquals(setOf(1, 2), s.objects.keys)
        assertEquals(listOf(590.0, 395.0, 690.0, 470.0), s.objects.getValue(1).bbox)
        assertEquals(0.0, s.objects.getValue(1).firstSeenPts)
        assertEquals(0.1, s.objects.getValue(1).lastSeenPts)
        assertEquals(s, world.snapshot.value)
        assertEquals(1, s.leadVehicle()!!.id)
        assertEquals(mapOf(ObjectClass.CAR to 2), s.countsByClass())
    }

    @Test
    fun `holds a missing track briefly then drops it`() {
        world.update(frame(0, objects = listOf(car(1, 20.0), car(2, 30.0))))
        var s = world.update(frame(1, pts = 0.3, objects = listOf(car(2, 30.0))))
        assertFalse(s.objects.getValue(1).visible, "held but not visible")
        assertTrue(s.objects.getValue(2).visible)
        s = world.update(frame(2, pts = 0.61, objects = listOf(car(2, 30.0))))
        assertNull(s.objects[1], "unseen for > 0.5 s must be removed")
        assertNotNull(s.objects[2])
    }

    @Test
    fun `relative speed is a robust slope of the distance history`() {
        // Closing at 5 m/s from 30 m, 10 fps, with one wild depth outlier.
        var s: WorldSnapshot? = null
        for (i in 0..15) {
            val d = if (i == 8) 60.0 else 30.0 - 0.5 * i
            s = world.update(frame(i.toLong(), objects = listOf(car(1, d))))
        }
        val o = s!!.objects.getValue(1)
        val speed = assertNotNull(o.relativeSpeedMps)
        assertTrue(abs(speed + 5.0) < 0.3, "expected about -5 m/s, got $speed")
        assertTrue(abs(o.distanceMeters!! - 22.5) < 0.5, "smoothed distance ${o.distanceMeters}")
        assertEquals(true, o.approaching)
        assertEquals("range_rate", o.ttcSource)
        assertTrue(abs(o.ttcSeconds!! - 4.5) < 0.4, "ttc ${o.ttcSeconds}")
    }

    @Test
    fun `distances carried between depth runs do not flatten the slope`() {
        // Depth runs every 3rd frame; the server repeats the last value in between.
        var s: WorldSnapshot? = null
        for (i in 0..20) {
            val depthAge = i % 3
            val measuredAt = (i - depthAge) / 10.0
            s = world.update(frame(i.toLong(), objects = listOf(car(1, 40.0 - 4.0 * measuredAt)), blockAges = mapOf("depth" to depthAge)))
        }
        val speed = s!!.objects.getValue(1).relativeSpeedMps!!
        assertTrue(abs(speed + 4.0) < 0.3, "expected about -4 m/s, got $speed")
        assertTrue(s.objects.getValue(1).distanceAgeSeconds!! <= 0.2)
    }

    @Test
    fun `server ttc from box scale wins over range rate`() {
        world.update(frame(0, objects = listOf(car(1, 20.0, ttc = 3.2))))
        val o = world.update(frame(1, objects = listOf(car(1, 20.0, ttc = 3.1)))).objects.getValue(1)
        assertEquals(3.1, o.ttcSeconds)
        assertEquals("box_scale", o.ttcSource)
    }

    @Test
    fun `light state is debounced`() {
        var seq = 0L
        fun step(state: LightState) = world.update(frame(seq++, objects = listOf(light(9, state)))).objects.getValue(9)
        repeat(5) { step(LightState.GREEN) }
        assertEquals(LightState.RED, step(LightState.RED).rawLightState)
        assertEquals(LightState.GREEN, step(LightState.GREEN).lightState, "one-frame RED glitch ignored")
        step(LightState.RED); step(LightState.RED)
        assertEquals(LightState.RED, step(LightState.RED).lightState, "3 consistent frames switch")
        repeat(5) { assertEquals(LightState.RED, step(LightState.UNKNOWN).lightState, "short UNKNOWN keeps RED") }
    }

    @Test
    fun `lane number is the mode of recent observations and goes stale`() {
        world.update(frame(0, lanes = lanes(2)))
        world.update(frame(1, lanes = lanes(2)))
        var s = world.update(frame(2, lanes = lanes(3))) // flicker
        assertEquals(2, s.lanes!!.currentLane)
        s = world.update(frame(3, pts = 3.0))
        assertNull(s.lanes, "lanes older than 1.5 s are dropped")
    }

    @Test
    fun `seq gaps count as server drops, duplicates are ignored, new session resets`() {
        world.update(frame(0, objects = listOf(car(1, 20.0))))
        world.update(frame(3))
        var s = world.update(frame(3, objects = listOf(car(99, 5.0)))) // duplicate seq
        assertNull(s.objects[99])
        assertEquals(2, s.timing!!.serverDroppedFrames)
        s = world.update(frame(0, pts = 0.0, objects = listOf(car(5, 10.0)), session = "other"))
        assertEquals(setOf(5), s.objects.keys)
        assertEquals(0, s.timing!!.serverDroppedFrames)
    }

    @Test
    fun `timing reports network and end-to-end latency`() {
        nowMs = 1_000_000L + 25
        val t = world.update(frame(0, pts = 0.0)).timing!!
        assertEquals(25.0, t.networkLatencyMs)
        assertEquals(65.0, t.endToEndLatencyMs)
    }

    @Test
    fun `signs are held for a while and counted`() {
        val stop = Sign(null, "stop", listOf(1000.0, 300.0, 1030.0, 330.0), 0.8, 25.0)
        world.update(frame(0, signs = listOf(stop)))
        var s = world.update(frame(1, signs = listOf(stop)))
        assertEquals(2, s.signs.single().seenCount)
        s = world.update(frame(2, pts = 1.5))
        assertEquals(1, s.signs.size, "held for 2 s")
        s = world.update(frame(3, pts = 2.5))
        assertTrue(s.signs.isEmpty())
    }
}
