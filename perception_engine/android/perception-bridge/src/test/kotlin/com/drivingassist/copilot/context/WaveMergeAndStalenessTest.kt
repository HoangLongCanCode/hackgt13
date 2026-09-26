package com.drivingassist.copilot.context

import com.drivingassist.copilot.context.TestFrames.car
import com.drivingassist.copilot.context.TestFrames.frame
import com.drivingassist.copilot.context.TestFrames.lanes
import com.drivingassist.copilot.context.TestFrames.light
import com.drivingassist.copilot.perception.DistanceUpdate
import com.drivingassist.copilot.perception.Echo
import com.drivingassist.copilot.perception.LightState
import com.drivingassist.copilot.perception.PerceptionUpdate
import com.drivingassist.copilot.perception.Road
import com.drivingassist.copilot.perception.Sign
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PROTOCOL_v2 on the Kotlin side: wave-2 merge, staleness, navigation-only context, prediction. */
class WaveMergeAndStalenessTest {
    private var nowNs = 10_000_000_000L
    private val world = WorldModel(clockMs = { 0L }, clockNs = { nowNs })

    private fun v2(seq: Long, pts: Double = seq / 10.0, objects: List<com.drivingassist.copilot.perception.PerceivedObject> = emptyList(), echo: Echo? = null) =
        frame(seq, pts, objects = objects).copy(schemaVersion = 2, wave = 1, echo = echo)

    private fun update(seq: Long, pts: Double, vararg d: DistanceUpdate, lanes: com.drivingassist.copilot.perception.Lanes? = null, road: Road? = null, signs: List<Sign>? = null) =
        PerceptionUpdate(seq = seq, frameIndex = seq, ptsSeconds = pts, distances = d.toList().ifEmpty { null }, lanes = lanes, road = road, signs = signs, blocks = listOf("depth"))

    @Test
    fun `wave-2 update merges into the latest wave-1 state without resetting it`() {
        var s = world.update(v2(0, objects = listOf(car(7, null), light(42, LightState.RED))))
        assertNull(s.objects.getValue(7).distanceMeters)
        val rev0 = s.revision

        val road = Road(0.3, listOf(listOf(0.0, 720.0), listOf(640.0, 360.0)), 360.0, listOf(640.0, 360.0))
        val stop = Sign(null, "stop", listOf(1000.0, 300.0, 1030.0, 330.0), 0.8, 25.0)
        s = world.applyUpdate(update(0, 0.0, DistanceUpdate(7, 20.0, "fused", 0.8, -0.3), lanes = lanes(2), road = road, signs = listOf(stop)))
        val car = s.objects.getValue(7)
        assertEquals(20.0, car.distanceMeters)
        assertEquals(-0.3, car.lateralMeters)
        assertEquals("fused", car.distanceMethod)
        assertEquals(0.8, car.distanceConfidence)
        assertEquals(LightState.RED, s.objects.getValue(42).lightState, "wave-1 state kept")
        assertEquals(2, s.lanes!!.currentLane)
        assertEquals(0.3, s.road!!.road.drivableCoverage)
        assertEquals("stop", s.signs.single().sign.signClass)
        assertEquals(0L, s.wave2!!.seq)
        assertEquals(0L, s.seq, "timing still the latest wave-1 frame")
        assertTrue(s.revision > rev0)

        // Wave-1 frames without lanes / distance keep the wave-2 data; boxes update.
        s = world.update(v2(1, objects = listOf(car(7, null).copy(bbox = listOf(610.0, 400.0, 690.0, 460.0)), light(42, LightState.RED))))
        assertEquals(20.0, s.objects.getValue(7).distanceMeters)
        assertEquals(0.1, s.objects.getValue(7).distanceAgeSeconds)
        assertEquals(listOf(610.0, 400.0, 690.0, 460.0), s.objects.getValue(7).bbox)
        assertEquals(2, s.lanes!!.currentLane)
        assertEquals(0.1, s.lanes!!.ageSeconds)

        // Once wave 2 is flowing, lanes carried on wave-1 frames are ignored (no double counting).
        s = world.update(v2(2).copy(objects = listOf(car(7, null)), lanes = lanes(3)))
        assertEquals(2, s.lanes!!.currentLane)

        // An update about an older frame than the lanes we have does not replace them.
        s = world.applyUpdate(update(3, 0.25, lanes = lanes(1)))
        assertEquals(1, s.lanes!!.currentLane)
        s = world.applyUpdate(update(2, 0.15, lanes = lanes(3)))
        assertEquals(1, s.lanes!!.lanes.currentLane, "older lanes ignored")
    }

    @Test
    fun `distances enter the history at measurement time and carried values are not double counted`() {
        // Closing at 5 m/s. Wave 1 every 0.05 s carries the latest distance with distanceAgeMs;
        // wave 2 delivers each measurement every 0.1 s, 0.05 s after the frame it analysed.
        var s: WorldSnapshot? = null
        var lastD: Double? = null
        var lastM = 0.0
        for (i in 0..40) {
            val pts = i * 0.05
            if (i % 2 == 1) {
                val m = pts - 0.05
                lastD = 30.0 - 5.0 * m
                lastM = m
                s = world.applyUpdate(update(i.toLong() - 1, m, DistanceUpdate(7, lastD)))
            }
            val carried = lastD?.let { d -> car(7, d).copy(distanceAgeMs = Math.round((pts - lastM) * 1000.0).toDouble()) } ?: car(7, null)
            s = world.update(v2(i.toLong(), pts, listOf(carried)))
        }
        val o = s!!.objects.getValue(7)
        val speed = assertNotNull(o.relativeSpeedMps)
        assertTrue(abs(speed + 5.0) < 0.2, "slope from wave-2 measurements, got $speed")
        assertEquals(lastD, o.rawDistanceMeters)
        assertTrue(o.distanceAgeSeconds!! in 0.09..0.11, "last update analysed pts 1.9, frame is 2.0: age ${o.distanceAgeSeconds}")
    }

    @Test
    fun `updates from another session or before any frame are ignored`() {
        assertNull(world.applyUpdate(update(0, 0.0, DistanceUpdate(7, 20.0))).timing)
        world.update(v2(0, objects = listOf(car(7, null))))
        val s = world.applyUpdate(update(0, 0.0, DistanceUpdate(7, 20.0)).copy(sessionId = "other"))
        assertNull(s.objects.getValue(7).distanceMeters)
    }

    @Test
    fun `box velocity is fitted over the recent history`() {
        var s: WorldSnapshot? = null
        for (i in 0..5) {
            val x = 600.0 + 10.0 * i // +100 px/s at 10 fps
            s = world.update(v2(i.toLong(), objects = listOf(car(7, 20.0).copy(bbox = listOf(x, 400.0, x + 80.0, 460.0 + i)))))
        }
        val v = assertNotNull(s!!.objects.getValue(7).bboxVelocityPxPerS)
        assertEquals(100.0, v[0], 0.5)
        assertEquals(0.0, v[1], 0.5)
        assertEquals(100.0, v[2], 0.5)
        assertEquals(10.0, v[3], 0.5)
    }

    @Test
    fun `staleness follows result age on the client clock and the link state`() {
        assertTrue(world.snapshot.value.perceptionStale, "nothing received yet")
        // Live: freshness is the CAPTURE time echoed back, not the arrival time.
        nowNs = 20_000_000_000L
        var s = world.update(v2(0, echo = Echo(1, nowNs - 80_000_000L)), receivedAtNs = nowNs)
        assertFalse(s.perceptionStale)
        assertEquals(80.0, s.timing!!.captureToResultMs)
        nowNs += 400_000_000L
        assertFalse(world.refreshStaleness(nowNs).perceptionStale, "480 ms old: still fresh")
        nowNs += 100_000_000L
        assertTrue(world.refreshStaleness(nowNs).perceptionStale, "580 ms old: stale")
        s = world.update(v2(1, echo = Echo(2, nowNs - 50_000_000L)), receivedAtNs = nowNs)
        assertFalse(s.perceptionStale)
        assertTrue(world.setLinkUp(false, nowNs).perceptionStale, "link down: stale at once")
        assertFalse(world.setLinkUp(true, nowNs).perceptionStale)
        // A result that took longer than 500 ms from capture is stale on arrival.
        s = world.update(v2(2, echo = Echo(3, nowNs - 700_000_000L)), receivedAtNs = nowNs)
        assertTrue(s.perceptionStale)
    }

    @Test
    fun `stale perception gives a navigation-only context`() {
        val engine = DrivingContextEngine()
        val nav = NavigationState(Maneuver.EXIT, 250.0, "Exit 23B", requiredLanes = listOf(3))
        nowNs = 30_000_000_000L
        var s = world.update(v2(0, objects = listOf(car(7, 6.0), light(42, LightState.RED)), echo = Echo(0, nowNs)).copy(lanes = lanes(1)), receivedAtNs = nowNs)
        var r = engine.evaluate(s, nav)
        assertEquals(FollowingState.CRITICAL, r.context.following.state)
        assertEquals(LaneAction.CHANGE_LANE_RIGHT, r.context.laneGuidance!!.action)
        assertFalse(r.context.perceptionStale)

        nowNs += 600_000_000L
        s = world.refreshStaleness(nowNs)
        assertTrue(s.perceptionStale)
        r = engine.evaluate(s, nav)
        val ctx = r.context
        assertTrue(ctx.perceptionStale)
        assertEquals(FollowingState.NORMAL, ctx.following.state)
        assertNull(ctx.following.leadTrackId)
        assertNull(ctx.trafficLight)
        assertTrue(ctx.pedestriansInPath.isEmpty())
        assertEquals(LaneAction.UNKNOWN, ctx.laneGuidance!!.action, "lanes are perception: guidance falls back to the route")
        assertTrue(ctx.laneGuidance!!.text.startsWith("USE LANE 3"), ctx.laneGuidance!!.text)
        assertTrue(ctx.activeAlerts.none { it.type == DrivingEventType.VEHICLE_TOO_CLOSE || it.type == DrivingEventType.TRAFFIC_LIGHT_RED })
        val types = r.events.map { it.type }
        assertTrue(DrivingEventType.PERCEPTION_LOST in types, "events: $types")
        assertTrue(DrivingEventType.FOLLOWING_NORMAL !in types, "no fake 'distance normal' when perception is lost")

        nowNs += 100_000_000L
        s = world.update(v2(1, objects = listOf(car(7, 6.0), light(42, LightState.RED)), echo = Echo(1, nowNs)), receivedAtNs = nowNs)
        r = engine.evaluate(s, nav)
        val back = r.events.map { it.type }
        assertFalse(r.context.perceptionStale)
        assertTrue(DrivingEventType.PERCEPTION_RESTORED in back, "events: $back")
        assertTrue(DrivingEventType.VEHICLE_TOO_CLOSE in back, "alert re-raised: $back")
        assertTrue(DrivingEventType.TRAFFIC_LIGHT_RED in back, "light re-announced: $back")
    }

    @Test
    fun `navigation works before any perception arrives`() {
        val engine = DrivingContextEngine()
        val r = engine.evaluate(WorldSnapshot.EMPTY, NavigationState(Maneuver.TURN_RIGHT, 200.0, "University Blvd", requiredSide = LaneSide.RIGHT))
        assertTrue(r.context.perceptionStale)
        assertEquals(DrivingEventType.TURN_RIGHT, r.events.first().type)
        assertTrue(r.events.none { it.type == DrivingEventType.PERCEPTION_LOST }, "never had perception: nothing was lost")
    }

    @Test
    fun `predictedAt moves boxes and distances from capture time to display time`() {
        val capture = 5_000_000_000L
        var s: WorldSnapshot? = null
        for (i in 0..5) {
            val x = 600.0 + 10.0 * i
            s = world.update(v2(i.toLong(), objects = listOf(car(7, 30.0 - 0.5 * i).copy(bbox = listOf(x, 400.0, x + 80.0, 460.0)))))
        }
        // Pretend the last frame came from the uplink, captured at `capture`.
        val base = s!!.copy(timing = s.timing!!.copy(captureTimeNs = capture))
        val o = base.objects.getValue(7)
        val p = base.predictedAt(capture + 100_000_000L)
        assertEquals(100.0, p.predictedAheadMs)
        val po = p.objects.getValue(7)
        assertEquals(o.bbox[0] + 10.0, po.bbox[0], 0.2)
        val expected = o.distanceMeters!! + o.relativeSpeedMps!! * ((o.distanceAgeSeconds ?: 0.0) + 0.1)
        assertEquals(expected, po.distanceMeters!!, 0.02)
        assertTrue(po.distanceMeters!! < o.distanceMeters!!, "closing")
        assertEquals(300.0, base.predictedAt(capture + 2_000_000_000L).predictedAheadMs, "capped")
        val noCapture = base.copy(timing = base.timing!!.copy(captureTimeNs = null))
        assertEquals(noCapture, noCapture.predictedAt(capture + 100_000_000L), "sim / video snapshots are not extrapolated")
    }
}
