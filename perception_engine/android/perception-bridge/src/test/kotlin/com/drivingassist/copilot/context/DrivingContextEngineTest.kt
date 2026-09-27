package com.drivingassist.copilot.context

import com.drivingassist.copilot.context.DrivingContextEngine.Companion.nextFollowingState
import com.drivingassist.copilot.context.TestFrames.car
import com.drivingassist.copilot.context.TestFrames.frame
import com.drivingassist.copilot.context.TestFrames.laneLines
import com.drivingassist.copilot.context.TestFrames.lanes
import com.drivingassist.copilot.context.TestFrames.light
import com.drivingassist.copilot.context.TestFrames.pedestrian
import com.drivingassist.copilot.perception.LightState
import com.drivingassist.copilot.perception.PerceptionFrame
import com.drivingassist.copilot.perception.ReplayPerceptionSource
import com.drivingassist.copilot.perception.Sign
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
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
        assertEquals(FollowingState.CRITICAL, go(10.0, ttc = 1.5), "TTC can trigger CRITICAL under 12 m")
        assertEquals(FollowingState.CRITICAL, go(12.5, ttc = 2.2), "stays CRITICAL up to 13 m")
        assertEquals(FollowingState.CLOSE, go(12.5, ttc = 3.0))
        assertEquals(FollowingState.CLOSE, go(30.0, ttc = 1.5), "never TOO CLOSE at 12 m or more, whatever the TTC")
        assertEquals(FollowingState.NORMAL, go(null))
        assertEquals(FollowingState.CLOSE, nextFollowingState(FollowingState.NORMAL, 30.0, null, 1.2, t), "headway when ego speed is known")
        assertEquals(FollowingState.CLOSE, nextFollowingState(FollowingState.NORMAL, 20.0, null, 0.6, t), "a 0.6 s headway at 20 m is CLOSE, not TOO CLOSE")
        assertEquals(FollowingState.CRITICAL, nextFollowingState(FollowingState.NORMAL, 11.0, null, 0.6, t), "the same headway under 12 m is TOO CLOSE")
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
    fun `optional speed gate holds CRITICAL at CLOSE while the ego is stopped`() {
        val gated = DrivingContextEngine(DrivingContextConfig(criticalMinEgoSpeedMps = 1.5))
        val model = WorldModel(clockMs = { 0L })
        fun nav(v: Double?) = NavigationState(Maneuver.FOLLOW_ROAD, 500.0, egoSpeedMps = v)
        var s = 0L
        fun at(d: Double, v: Double?) = gated.evaluate(model.update(frame(s, s++ / 10.0, objects = listOf(car(7, d)))), nav(v)).context.following.state
        assertEquals(FollowingState.CLOSE, at(5.0, 0.0), "stopped behind a car: CLOSE, not TOO CLOSE")
        assertEquals(FollowingState.CRITICAL, at(5.0, 6.0), "moving: CRITICAL")
        assertEquals(FollowingState.CRITICAL, at(5.0, null), "unknown speed never gates")
        assertEquals(FollowingState.CRITICAL, DrivingContextEngine().evaluate(WorldModel(clockMs = { 0L }).update(frame(0, 0.0, objects = listOf(car(7, 5.0)))), nav(0.0)).context.following.state,
            "off by default")
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
        repeat(2) { step(frame(seq++, lanes = laneLines(1))) } // the layout is stable from the third lanes run
        var ev = step(frame(seq++, lanes = laneLines(1)), nav(400.0))
        val change = ev.single { it.type == DrivingEventType.CHANGE_LANE_RIGHT }
        assertEquals(Priority.UPCOMING_NAVIGATION, change.priority)
        assertEquals(2, change.lanesToMove)
        assertTrue(change.text.startsWith("PREPARE TO MOVE RIGHT | 2 LANES"), change.text)
        assertEquals("Prepare to move right two lanes for Exit 23B.", change.speech)
        assertEquals(DrivingEventType.EXIT, ev.single { it.priority == Priority.UPCOMING_NAVIGATION && it.type == DrivingEventType.EXIT }.type)

        ev = step(frame(seq++, lanes = laneLines(1)), nav(390.0))
        assertTrue(ev.isEmpty(), "no repeat while nothing changes: $ev")

        ev = step(frame(seq++, lanes = laneLines(1)), nav(280.0))
        assertEquals(Priority.IMMEDIATE_NAVIGATION, ev.single { it.type == DrivingEventType.CHANGE_LANE_RIGHT }.priority, "escalates")

        // Driver moves two lanes right, 0.7 m per lanes run (the lines move with the car, the layout follows them).
        for (k in 1..10) ev = step(frame(seq++, lanes = laneLines(1, offset = 0.7 * k)), nav(250.0))
        val g = engine.context.value.laneGuidance!!
        assertEquals(LaneAction.KEEP_LANE, g.action)
        assertEquals(3 to 3, g.currentLane to g.laneCount)
    }

    @Test
    fun `keep lane after a completed change is spoken, lane side resolves with lane count`() {
        val nav = NavigationState(Maneuver.TURN_RIGHT, 200.0, "University Blvd", requiredSide = LaneSide.RIGHT)
        repeat(2) { step(frame(seq++, lanes = laneLines(1, count = 2))) }
        step(frame(seq++, lanes = laneLines(1, count = 2)), nav)
        assertEquals(LaneAction.CHANGE_LANE_RIGHT, engine.context.value.laneGuidance!!.action)
        assertEquals(listOf(2), engine.context.value.laneGuidance!!.targetLanes)
        val events = mutableListOf<DrivingEvent>()
        for (k in 1..5) events += step(frame(seq++, lanes = laneLines(1, count = 2, offset = 0.7 * k)), nav)
        assertEquals("Stay in this lane for University Blvd.", events.single { it.type == DrivingEventType.KEEP_LANE }.speech)
    }

    @Test
    fun `the server's lane numbers alone give no lane numbers`() {
        val nav = NavigationState(Maneuver.EXIT, 400.0, "Exit 23B", requiredLanes = listOf(3))
        repeat(3) { step(frame(seq++, lanes = lanes(1, confidence = 0.95)), nav) }
        val g = engine.context.value.laneGuidance!!
        assertEquals(LaneAction.UNKNOWN, g.action, "lane 1 of 3 at 0.95 from the server, but no lines: unknown")
        assertNull(g.currentLane)
        assertTrue(g.text.startsWith("USE LANE 3"), g.text)
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
        // 1 s at 10 fps: the limit needs 0.8 s of the same read before it is shown.
        repeat(10) { events += next { s, p -> frame(s, p, signs = listOf(stop, limit)) } }
        assertEquals(1, events.count { it.type == DrivingEventType.STOP_SIGN })
        assertEquals("SPEED LIMIT 45", events.single { it.type == DrivingEventType.SPEED_LIMIT }.text)
        assertEquals(45, engine.context.value.speedLimit)
        assertEquals(SpeedLimitSource.SIGN, engine.context.value.speedLimitSource)
        assertEquals(Priority.TRAFFIC_ALERT, events.first { it.type == DrivingEventType.STOP_SIGN }.priority)
    }

    /** A fresh WorldModel + engine at 10 fps (pts = frame index / 10); [clockNs] = the engine's wall clock. */
    private class Drive(config: DrivingContextConfig = DrivingContextConfig(), clockNs: () -> Long = { 0L }) {
        val world = WorldModel(clockMs = { 0L })
        val engine = DrivingContextEngine(config, clockNs)
        var seq = 0L

        /** [n] frames showing [signs]; returns the SPEED_LIMIT events. */
        fun frames(n: Int, signs: List<Sign> = emptyList(), nav: NavigationState? = null, pts: Double? = null): List<DrivingEvent> {
            val out = ArrayList<DrivingEvent>()
            repeat(n) {
                val s = seq++
                out += engine.evaluate(world.update(frame(s, pts ?: (s / 10.0), signs = signs)), nav).events
            }
            return out.filter { it.type == DrivingEventType.SPEED_LIMIT }
        }

        val limit: Int? get() = engine.context.value.speedLimit
        val source: SpeedLimitSource? get() = engine.context.value.speedLimitSource
    }

    private fun limitSign(mph: Int, confidence: Double = 0.9, distance: Double? = 40.0, id: Int = 5) =
        Sign(id, "speed_limit_$mph", listOf(1000.0, 200.0, 1030.0, 240.0), confidence, distance)

    @Test
    fun `speed limit sign is shown only when strong, near, allowed and held`() {
        for ((sign, why) in listOf(
            limitSign(45, confidence = 0.8) to "below 0.85 confidence",
            limitSign(45, distance = 75.0) to "farther than 60 m",
            limitSign(47) to "not a multiple of 5",
            limitSign(90) to "above 85 mph",
            limitSign(5) to "below 10 mph",
        )) {
            val d = Drive()
            assertTrue(d.frames(20, listOf(sign)).isEmpty(), why)
            assertNull(d.limit, why)
            assertNull(d.source, why)
        }
        val d = Drive()
        assertTrue(d.frames(8, listOf(limitSign(45))).isEmpty(), "0.7 s: not held long enough")
        assertNull(d.limit)
        assertEquals(listOf("SPEED LIMIT 45"), d.frames(1, listOf(limitSign(45))).map { it.text }, "0.8 s")
        assertEquals(45, d.limit)
        assertEquals(SpeedLimitSource.SIGN, d.source)
        assertTrue(d.frames(20, listOf(limitSign(45))).isEmpty(), "one event per newly confirmed value")
        assertEquals(35, Drive().apply { frames(9, listOf(limitSign(35, distance = null))) }.limit, "unknown distance is accepted")
    }

    @Test
    fun `a newer sign replaces the limit, a changing read restarts`() {
        val d = Drive()
        d.frames(9, listOf(limitSign(45)))
        assertEquals(45, d.limit)
        assertEquals(listOf("SPEED LIMIT 35"), d.frames(9, listOf(limitSign(35, id = 6))).map { it.text })
        assertEquals(35, d.limit)

        val flicker = Drive()
        assertTrue(flicker.frames(5, listOf(limitSign(45))).isEmpty())
        assertTrue(flicker.frames(5, listOf(limitSign(50))).isEmpty(), "same sign reads 50 from 0.5 s: restarts")
        assertNull(flicker.limit)
        assertEquals(listOf("SPEED LIMIT 50"), flicker.frames(4, listOf(limitSign(50))).map { it.text }, "50 held 0.5 -> 1.3 s")
    }

    @Test
    fun `sign limit expires after the hold and the map value shows again`() {
        val nav = NavigationState(Maneuver.FOLLOW_ROAD, 5000.0, "I-75", mapSpeedLimitMph = 55, mapSpeedLimitRoad = "I-75")
        val d = Drive()
        assertTrue(d.frames(1, nav = nav).isEmpty())
        assertEquals(55, d.limit)
        assertEquals(SpeedLimitSource.MAP, d.source)
        d.frames(9, listOf(limitSign(45)), nav)
        assertEquals(45, d.limit, "a confirmed sign wins over the map")
        assertEquals(SpeedLimitSource.SIGN, d.source)
        d.frames(1, nav = nav, pts = 500.0)
        assertEquals(45, d.limit, "kept within 600 s")
        d.frames(1, nav = nav, pts = 601.0)
        assertEquals(55, d.limit, "600 s without a new read")
        assertEquals(SpeedLimitSource.MAP, d.source)
    }

    @Test
    fun `passing a turn clears the sign limit, a far reroute does not`() {
        fun turn(dist: Double) = NavigationState(Maneuver.TURN_RIGHT, dist, "North Ave")
        val d = Drive()
        d.frames(9, listOf(limitSign(35)), turn(300.0))
        assertEquals(35, d.limit)
        d.frames(1, nav = NavigationState(Maneuver.TURN_LEFT, 200.0, "Spring St"))
        assertEquals(35, d.limit, "target changed 200 m before it: not passed")
        d.frames(1, nav = NavigationState(Maneuver.TURN_LEFT, 60.0, "Spring St"))
        assertEquals(35, d.limit, "same target")
        d.frames(1, nav = NavigationState(Maneuver.ARRIVE, 400.0, "Student Center"))
        assertNull(d.limit, "the turn at 60 m was passed: another road")
        assertNull(d.source)
    }

    @Test
    fun `passing the first of two unnamed turns the same way clears the sign limit`() {
        fun right(dist: Double, id: String?) = NavigationState(Maneuver.TURN_RIGHT, dist, eventId = id)
        val d = Drive()
        d.frames(9, listOf(limitSign(25)), right(300.0, "step_3"))
        d.frames(1, nav = right(40.0, "step_3"))
        assertEquals(25, d.limit, "same step, not passed yet")
        d.frames(1, nav = right(900.0, "step_4"))
        assertNull(d.limit, "another step replaced the right turn at 40 m")

        // No step ids (a hand-built state): the target distance jumping back up counts as passed.
        val e = Drive()
        e.frames(9, listOf(limitSign(25)), right(300.0, null))
        e.frames(1, nav = right(40.0, null))
        e.frames(1, nav = right(45.0, null))
        assertEquals(25, e.limit, "GPS noise at the turn is not a pass")
        e.frames(1, nav = right(900.0, null))
        assertNull(e.limit)
    }

    @Test
    fun `map value or road change after passing the sign clears the sign limit, no navigation gives no map value`() {
        val north = NavigationState(Maneuver.FOLLOW_ROAD, 3000.0, "North Ave", mapSpeedLimitMph = 35, mapSpeedLimitRoad = "North Ave")
        val d = Drive()
        d.frames(9, listOf(limitSign(45)), north) // last read at 0.8 s
        assertEquals(45, d.limit)
        d.frames(1, nav = north.copy(mapSpeedLimitMph = 40, mapSpeedLimitRoad = null))
        d.frames(70, nav = north.copy(mapSpeedLimitMph = 40)) // up to 7.9 s
        assertEquals(45, d.limit, "the map way splits at the sign (40, out of date): the confirmed sign still wins")
        assertEquals(SpeedLimitSource.SIGN, d.source)
        d.frames(10, nav = north.copy(mapSpeedLimitMph = 40, mapSpeedLimitRoad = null)) // 8.9 s
        assertEquals(45, d.limit, "an unnamed road is not a change (the anchor kept North Ave)")
        d.frames(1, nav = north.copy(mapSpeedLimitMph = 40, mapSpeedLimitRoad = "Spring St"))
        assertEquals(40, d.limit, "map road changed 8 s after the last read of the sign")
        assertEquals(SpeedLimitSource.MAP, d.source)
        assertTrue(d.frames(8, listOf(limitSign(45)), north).isEmpty(), "reads from before the clear do not count")
        assertEquals(1, d.frames(1, listOf(limitSign(45)), north).size)
        assertEquals(45, d.limit)
        d.frames(75, nav = north) // last read at 9.9 s
        d.frames(1, nav = north.copy(mapSpeedLimitMph = 40)) // 17.5 s
        assertEquals(45, d.limit, "7.6 s after the last read: passing the sign")
        d.frames(10, nav = north.copy(mapSpeedLimitMph = 40))
        assertEquals(45, d.limit)
        d.frames(1, nav = north.copy(mapSpeedLimitMph = 50)) // 18.6 s
        assertEquals(50, d.limit, "map value changed after passing the sign")
        d.frames(1, nav = null)
        assertNull(d.limit, "no (or a stale) navigation packet: no map value")
    }

    @Test
    fun `sign limit is kept while perception is stale and cleared on reset`() {
        val d = Drive()
        d.frames(9, listOf(limitSign(45)))
        val stale = d.world.snapshot.value.copy(perceptionStale = true)
        assertEquals(45, d.engine.evaluate(stale).context.speedLimit)
        assertEquals(SpeedLimitSource.SIGN, d.engine.context.value.speedLimitSource)
        d.engine.reset()
        assertNull(d.engine.evaluate(stale).context.speedLimit)
    }

    @Test
    fun `while perception is stale the sign limit is timed on the wall clock`() {
        var wallMs = 0L
        val d = Drive(clockNs = { wallMs * 1_000_000L })
        val nav = NavigationState(Maneuver.FOLLOW_ROAD, 5000.0, "I-75")
        d.frames(9, listOf(limitSign(45)), nav)
        val stale = d.world.snapshot.value.copy(perceptionStale = true) // media time stops at 0.8 s
        assertEquals(45, d.engine.evaluate(stale, nav).context.speedLimit)
        wallMs = 29_999
        assertEquals(45, d.engine.evaluate(stale, null).context.speedLimit, "kept through a short outage")
        wallMs = 30_000
        assertNull(d.engine.evaluate(stale, null).context.speedLimit, "30 s stale: unknown")
        assertNull(d.engine.context.value.speedLimitSource)

        // Fresh perception restarts the stale timer; the rest of the 600 s hold caps it.
        wallMs = 0L
        val e = Drive(clockNs = { wallMs * 1_000_000L })
        e.frames(9, listOf(limitSign(45)))
        e.frames(1, pts = 20.0)
        val blip = e.world.snapshot.value.copy(perceptionStale = true)
        e.engine.evaluate(blip)
        wallMs = 20_000
        e.frames(1, pts = 590.0) // perception back: 589.2 s of the hold used
        assertEquals(45, e.limit)
        e.engine.evaluate(e.world.snapshot.value.copy(perceptionStale = true))
        wallMs = 30_000
        assertEquals(45, e.engine.evaluate(e.world.snapshot.value.copy(perceptionStale = true)).context.speedLimit)
        wallMs = 30_900
        assertNull(e.engine.evaluate(e.world.snapshot.value.copy(perceptionStale = true)).context.speedLimit, "hold ran out")
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `run - a sign limit held while perception is stale runs out without new input`() = runTest {
        val d = Drive(clockNs = { testScheduler.currentTime * 1_000_000L })
        d.frames(9, listOf(limitSign(45)))
        val world = MutableStateFlow(d.world.snapshot.value)
        val nav = MutableStateFlow<NavigationState?>(null)
        backgroundScope.launch { d.engine.run(world, nav) }
        runCurrent()
        world.value = world.value.copy(perceptionStale = true, revision = world.value.revision + 1) // link lost
        runCurrent()
        assertEquals(45, d.limit)
        advanceTimeBy(29_000)
        runCurrent()
        assertEquals(45, d.limit)
        advanceTimeBy(1_100)
        runCurrent()
        assertNull(d.limit, "30 s stale with no new input: unknown")
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
