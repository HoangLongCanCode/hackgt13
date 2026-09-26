package com.drivingassist.copilot.context

import com.drivingassist.copilot.context.DrivingContextEngine.Companion.nextFollowingState
import com.drivingassist.copilot.context.TestFrames.car
import com.drivingassist.copilot.context.TestFrames.frame
import com.drivingassist.copilot.context.TestFrames.lanes
import com.drivingassist.copilot.context.TestFrames.light
import com.drivingassist.copilot.context.TestFrames.pedestrian
import com.drivingassist.copilot.perception.LightState
import com.drivingassist.copilot.perception.PerceptionFrame
import com.drivingassist.copilot.perception.ReplayPerceptionSource
import com.drivingassist.copilot.perception.Sign
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DrivingContextEngineTest {
    private val world = WorldModel(clockMs = { 0L })
    private val engine = DrivingContextEngine()
    private var seq = 0L

    /** Push one frame through WorldModel + engine, return the new events. */
    private fun step(frame: PerceptionFrame, nav: NavigationState? = null): List<DrivingEvent> =
        engine.evaluate(world.update(frame), nav).events

    private fun next(pts: Double? = null, build: (Long, Double) -> PerceptionFrame): List<DrivingEvent> {
        val s = seq++
        return step(build(s, pts ?: (s / 10.0)))
    }

    @Test
    fun `following state has hysteresis`() {
        val t = FollowingThresholds()
        var st = FollowingState.NORMAL
        fun go(d: Double?, ttc: Double? = null) = nextFollowingState(st, d, ttc, null, t).also { st = it }
        assertEquals(FollowingState.NORMAL, go(16.0))
        assertEquals(FollowingState.CLOSE, go(14.0))
        assertEquals(FollowingState.CLOSE, go(16.0), "stays CLOSE until above 18 m")
        assertEquals(FollowingState.NORMAL, go(18.5))
        assertEquals(FollowingState.CLOSE, go(14.0))
        assertEquals(FollowingState.CRITICAL, go(6.5))
        assertEquals(FollowingState.CRITICAL, go(8.0), "stays CRITICAL until above 9 m")
        assertEquals(FollowingState.CLOSE, go(9.5))
        assertEquals(FollowingState.CRITICAL, go(30.0, ttc = 1.5), "TTC alone can trigger CRITICAL")
        assertEquals(FollowingState.CRITICAL, go(30.0, ttc = 2.2))
        assertEquals(FollowingState.CLOSE, go(30.0, ttc = 3.0))
        assertEquals(FollowingState.NORMAL, go(null))
        assertEquals(FollowingState.CLOSE, nextFollowingState(FollowingState.NORMAL, 30.0, null, 1.2, t), "headway when ego speed is known")
    }

    @Test
    fun `lead vehicle closing produces CLOSE then CRITICAL events once each`() {
        val all = mutableListOf<DrivingEvent>()
        // Closing at 3 m/s (10 fps) from 20 m to 5.3 m, then holding, then opening to 8.5 m.
        val distances = (0 until 50).map { 20.0 - 0.3 * it } + List(5) { 5.3 } + listOf(6.5, 7.5, 8.5)
        for (d in distances) all += next { s, p -> frame(s, p, objects = listOf(car(7, d))) }
        val following = all.filter { it.type in setOf(DrivingEventType.FOLLOWING_CLOSE, DrivingEventType.VEHICLE_TOO_CLOSE) }
        assertEquals(listOf(DrivingEventType.FOLLOWING_CLOSE, DrivingEventType.VEHICLE_TOO_CLOSE), following.map { it.type })
        assertEquals(Priority.TRAFFIC_ALERT, following[0].priority)
        assertEquals(Priority.CRITICAL_SAFETY, following[1].priority)
        assertEquals("Vehicle too close.", following[1].speech)
        assertEquals(7, following[1].trackId)
        val ctx = engine.context.value
        assertEquals(FollowingState.CRITICAL, ctx.following.state)
        assertEquals(DrivingEventType.VEHICLE_TOO_CLOSE, ctx.activeAlerts.first().type)
    }

    @Test
    fun `red light raises one traffic alert, green after red is spoken`() {
        val events = mutableListOf<DrivingEvent>()
        repeat(3) { events += next { s, p -> frame(s, p, objects = listOf(light(42, LightState.GREEN))) } }
        assertEquals(listOf(DrivingEventType.TRAFFIC_LIGHT_GREEN), events.map { it.type })
        assertNull(events.single().speech, "initial green is not spoken")
        events.clear()
        repeat(10) { events += next { s, p -> frame(s, p, objects = listOf(light(42, LightState.RED))) } }
        val red = events.single()
        assertEquals(DrivingEventType.TRAFFIC_LIGHT_RED, red.type)
        assertEquals(Priority.TRAFFIC_ALERT, red.priority)
        assertEquals("RED | 40 m", red.text)
        assertEquals("Red light ahead.", red.speech)
        assertEquals(LightState.RED, engine.context.value.trafficLight!!.state)
        events.clear()
        repeat(4) { events += next { s, p -> frame(s, p, objects = listOf(light(42, LightState.GREEN))) } }
        assertEquals("Light is green.", events.single { it.type == DrivingEventType.TRAFFIC_LIGHT_GREEN }.speech)
    }

    @Test
    fun `lights far to the side are ignored`() {
        val events = next { s, p -> frame(s, p, objects = listOf(light(1, LightState.RED, lateral = 25.0))) }
        assertTrue(events.isEmpty())
        assertNull(engine.context.value.trafficLight)
    }

    @Test
    fun `lane guidance tells the driver to move right for the exit`() {
        fun nav(d: Double) = NavigationState(Maneuver.EXIT, d, "Exit 23B", requiredLanes = listOf(3))
        var ev = step(frame(seq++, lanes = lanes(1)), nav(400.0))
        val change = ev.single { it.type == DrivingEventType.CHANGE_LANE_RIGHT }
        assertEquals(Priority.UPCOMING_NAVIGATION, change.priority)
        assertEquals(2, change.lanesToMove)
        assertTrue(change.text.startsWith("PREPARE TO MOVE RIGHT | 2 LANES"), change.text)
        assertEquals("Prepare to move right two lanes for Exit 23B.", change.speech)
        assertEquals(DrivingEventType.EXIT, ev.single { it.priority == Priority.UPCOMING_NAVIGATION && it.type == DrivingEventType.EXIT }.type)

        ev = step(frame(seq++, lanes = lanes(1)), nav(390.0))
        assertTrue(ev.isEmpty(), "no repeat while nothing changes: $ev")

        ev = step(frame(seq++, lanes = lanes(1)), nav(280.0))
        assertEquals(Priority.IMMEDIATE_NAVIGATION, ev.single { it.type == DrivingEventType.CHANGE_LANE_RIGHT }.priority, "escalates")

        // Driver moves to lane 3 (lane number needs a few frames to win the mode filter).
        repeat(4) { ev = step(frame(seq++, lanes = lanes(3)), nav(250.0)) }
        val g = engine.context.value.laneGuidance!!
        assertEquals(LaneAction.KEEP_LANE, g.action)
        assertEquals(3, g.currentLane)
    }

    @Test
    fun `keep lane after a completed change is spoken, lane side resolves with lane count`() {
        val nav = NavigationState(Maneuver.TURN_RIGHT, 200.0, "University Blvd", requiredSide = LaneSide.RIGHT)
        step(frame(seq++, lanes = lanes(1, count = 2)), nav)
        assertEquals(LaneAction.CHANGE_LANE_RIGHT, engine.context.value.laneGuidance!!.action)
        assertEquals(listOf(2), engine.context.value.laneGuidance!!.targetLanes)
        val events = mutableListOf<DrivingEvent>()
        repeat(4) { events += step(frame(seq++, lanes = lanes(2, count = 2)), nav) }
        assertEquals("Stay in this lane for University Blvd.", events.single { it.type == DrivingEventType.KEEP_LANE }.speech)
    }

    @Test
    fun `without lane detection guidance falls back to the required lane`() {
        step(frame(seq++), NavigationState(Maneuver.EXIT, 400.0, "Exit 23B", requiredLanes = listOf(3)))
        val g = engine.context.value.laneGuidance!!
        assertEquals(LaneAction.UNKNOWN, g.action)
        assertTrue(g.text.startsWith("USE LANE 3"), g.text)
    }

    @Test
    fun `pedestrian in path is critical when near and not repeated too often`() {
        val events = mutableListOf<DrivingEvent>()
        for (i in 0 until 30) events += next { s, p -> frame(s, p, objects = listOf(pedestrian(21, 10.0))) } // 3 s
        val ped = events.filter { it.type == DrivingEventType.PEDESTRIAN_IN_PATH }
        assertEquals(1, ped.size)
        assertEquals(Priority.CRITICAL_SAFETY, ped.single().priority)
        for (i in 0 until 15) events += next { s, p -> frame(s, p, objects = listOf(pedestrian(21, 10.0))) } // to 4.5 s
        assertEquals(2, events.count { it.type == DrivingEventType.PEDESTRIAN_IN_PATH }, "repeats after 4 s")
        // Pedestrian not in path -> nothing.
        val other = DrivingContextEngine()
        assertTrue(other.evaluate(WorldModel().update(frame(0, objects = listOf(pedestrian(3, 10.0, inPath = false))))).events.isEmpty())
    }

    @Test
    fun `stop and speed limit signs are announced once`() {
        val stop = Sign(4, "stop", listOf(1000.0, 300.0, 1030.0, 330.0), 0.8, 25.0)
        val limit = Sign(5, "speed_limit_45", listOf(1000.0, 200.0, 1030.0, 240.0), 0.9, 30.0)
        val events = mutableListOf<DrivingEvent>()
        repeat(6) { events += next { s, p -> frame(s, p, signs = listOf(stop, limit)) } }
        assertEquals(1, events.count { it.type == DrivingEventType.STOP_SIGN })
        assertEquals(1, events.count { it.type == DrivingEventType.SPEED_LIMIT })
        assertEquals(45, engine.context.value.speedLimit)
        assertEquals(Priority.TRAFFIC_ALERT, events.first { it.type == DrivingEventType.STOP_SIGN }.priority)
    }

    @Test
    fun `events are ordered by plan section 24 priority`() {
        val ev = next { s, p -> frame(s, p, objects = listOf(car(7, 5.0), light(42, LightState.RED), pedestrian(3, 25.0))) }
        assertEquals(ev.sortedBy { it.priority.rank }, ev)
        assertEquals(Priority.CRITICAL_SAFETY, ev.first().priority)
        assertEquals(Priority.CRITICAL_SAFETY, engine.context.value.activeAlerts.first().priority)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `flows - replay source into WorldModel and engine`() = runTest {
        val frames = (0L until 30L).map { i -> frame(i, objects = listOf(car(7, 30.0 - i), light(42, LightState.RED))) }
        val w = WorldModel(clockMs = { 0L })
        val e = DrivingContextEngine()
        val nav = MutableStateFlow<NavigationState?>(null)
        val seen = mutableListOf<DrivingEvent>()
        backgroundScope.launch { e.events.collect { seen += it } }
        backgroundScope.launch { e.run(w.snapshot, nav) }
        runCurrent()
        // Paced by pts (virtual time here), exactly like an offline demo replay.
        w.run(ReplayPerceptionSource.fromMessages(frames, realtime = true))
        runCurrent()
        assertEquals(29, e.context.value.seq)
        assertEquals(FollowingState.CRITICAL, e.context.value.following.state)
        assertTrue(seen.any { it.type == DrivingEventType.TRAFFIC_LIGHT_RED }, "events: $seen")
        assertTrue(seen.any { it.type == DrivingEventType.VEHICLE_TOO_CLOSE }, "events: $seen")
    }
}
