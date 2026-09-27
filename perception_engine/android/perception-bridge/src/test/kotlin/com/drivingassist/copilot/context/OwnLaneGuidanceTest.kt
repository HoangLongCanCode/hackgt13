package com.drivingassist.copilot.context

import com.drivingassist.copilot.perception.Lanes
import com.drivingassist.copilot.perception.ObjectClass
import com.drivingassist.copilot.perception.Road
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lane guidance numbered by [WorldSnapshot.laneLayout], and the lead vehicle taken from the car's own lane only. */
class OwnLaneGuidanceTest {
    private val engine = DrivingContextEngine()

    /** [count] lanes, the car in [ego]: lines through (800, 380) with slopes ..., -1, +1, ... around the car ([colors]: of the lines). */
    private fun layout(ego: Int, count: Int, quality: Double = 0.8, age: Double = 0.1, colors: List<String>? = null, yellowLeftEdge: Boolean = false) = LaneLayout(
        vpX = 800.0, vpY = 380.0, lines = (0..count).map { LaneLine((it - ego + 0.5) * 2.0, color = colors?.get(it)) }, egoLane = ego,
        nearestRowY = 700.0, quality = quality, measuredPts = 0.9, ageSeconds = age, yellowLeftEdge = yellowLeftEdge,
    )

    /** The server's own lane numbers (what the recorded drives showed: lane 1 of 1, confident). */
    private fun serverLanes(current: Int = 1, count: Int = 1, confidence: Double = 0.9) =
        LanesState(Lanes(current, count, emptyList(), confidence), current, count, 0.9, 0.1)

    private fun world(layout: LaneLayout?, lanes: LanesState? = serverLanes(), seq: Long = 1) = WorldSnapshot(
        timing = FrameTiming("t", seq, seq, 1.0, 0L, 0L, 40.0, 0.0, 40.0, null, null, 1L, 0L),
        lanes = lanes, laneLayout = layout, revision = seq,
    )

    private val turnLeft = NavigationState(Maneuver.TURN_LEFT, 250.0, "Main St", requiredSide = LaneSide.LEFT, laneHintInferred = true)
    private val turnRight = NavigationState(Maneuver.TURN_RIGHT, 250.0, "Main St", requiredSide = LaneSide.RIGHT, laneHintInferred = true)

    // --- lane guidance -------------------------------------------------------------------------------------------

    @Test
    fun `lane 2 of 3 with a left turn ahead - move left, from the layout not the server numbers`() {
        val r = engine.evaluate(world(layout(ego = 2, count = 3)), turnLeft)
        val g = r.context.laneGuidance!!
        assertEquals(LaneAction.CHANGE_LANE_LEFT, g.action)
        assertEquals(2 to 3, g.currentLane to g.laneCount)
        assertEquals(listOf(1), g.targetLanes)
        assertEquals(1, g.lanesToMove)
        assertTrue(g.text.startsWith("MOVE LEFT | TURN LEFT | MAIN ST"), g.text)
        val e = r.events.single { it.type == DrivingEventType.CHANGE_LANE_LEFT }
        assertEquals("Move left now for Main St.", e.speech)
        assertTrue(r.context.activeAlerts.any { it.type == DrivingEventType.CHANGE_LANE_LEFT })
    }

    @Test
    fun `in the target lane - keep lane, right side is the last visible lane`() {
        assertEquals(LaneAction.KEEP_LANE, engine.evaluate(world(layout(ego = 1, count = 3)), turnLeft).context.laneGuidance!!.action)
        val g = DrivingContextEngine().evaluate(world(layout(ego = 2, count = 3)), turnRight).context.laneGuidance!!
        assertEquals(LaneAction.CHANGE_LANE_RIGHT, g.action)
        assertEquals(listOf(3), g.targetLanes)
    }

    @Test
    fun `required lanes are clipped to the visible lane count`() {
        val nav = NavigationState(Maneuver.EXIT, 400.0, "Exit 23B", requiredLanes = listOf(4, 5))
        val g = engine.evaluate(world(layout(ego = 1, count = 3)), nav).context.laneGuidance!!
        assertEquals(listOf(3), g.targetLanes)
        assertEquals(LaneAction.CHANGE_LANE_RIGHT, g.action)
        assertEquals(2, g.lanesToMove)
    }

    @Test
    fun `a layout that is not stable means unknown, the server numbers are not used instead`() {
        val notYet = layout(2, 3).copy(stable = false) // good quality, but the WorldModel's hysteresis has not let it in yet
        for (l in listOf(layout(2, 3, quality = 0.35), layout(2, 3, age = 1.2), notYet)) {
            val g = DrivingContextEngine().evaluate(world(l, lanes = serverLanes(1, 3, 0.95)), turnLeft).context.laneGuidance!!
            assertEquals(LaneAction.UNKNOWN, g.action, "$l")
            assertNull(g.currentLane)
            assertTrue(g.text.startsWith("USE LEFT LANE | TURN LEFT"), g.text)
        }
        // A stable layout counts until it is older than 1 s, whatever its quality now (the WorldModel clears stable).
        val dipped = layout(2, 3, quality = 0.38).copy(stable = true)
        assertEquals(LaneAction.CHANGE_LANE_LEFT, DrivingContextEngine().evaluate(world(dipped), turnLeft).context.laneGuidance!!.action)
        val old = layout(2, 3).copy(stable = true, ageSeconds = 1.05)
        assertEquals(LaneAction.UNKNOWN, DrivingContextEngine().evaluate(world(old), turnLeft).context.laneGuidance!!.action)
    }

    @Test
    fun `no layout - lane numbers unknown, the server numbers are never used`() {
        for (conf in listOf(0.55, 0.95)) {
            val g = DrivingContextEngine().evaluate(world(null, lanes = serverLanes(2, 3, conf)), turnLeft).context.laneGuidance!!
            assertEquals(LaneAction.UNKNOWN, g.action)
            assertEquals(null to null, g.currentLane to g.laneCount)
        }
        val exit = NavigationState(Maneuver.EXIT, 400.0, "Exit 23B", requiredLanes = listOf(3))
        val g = DrivingContextEngine().evaluate(world(null, lanes = serverLanes(1, 3, 0.95)), exit).context.laneGuidance!!
        assertEquals(LaneAction.UNKNOWN, g.action)
        assertTrue(g.text.startsWith("USE LANE 3"), g.text)
    }

    @Test
    fun `recorded frame - the server says lane 1 of 1, the lines say lane 2 of 3 - a left turn asks to move left`() {
        val f = RealLaneFrames.get("real_011", 451L) // arterial, middle lane (checked on the video)
        assertEquals(1 to 1, f.lanes.currentLane to f.lanes.laneCount)
        assertTrue(f.lanes.confidence >= 0.6, "the old path would have trusted it: KEEP LANE")
        val model = WorldModel(clockMs = { 0L })
        val nav = turnLeft.copy(distanceMeters = 200.0)
        engine.evaluate(model.update(f.frame(0)), nav)
        assertNull(engine.evaluate(model.update(f.frame(1)), nav).context.laneGuidance!!.currentLane, "the first layout is not stable yet")
        val r = engine.evaluate(model.update(f.frame(2)), nav)
        val g = r.context.laneGuidance!!
        assertEquals(LaneAction.CHANGE_LANE_LEFT, g.action)
        assertEquals(2 to 3, g.currentLane to g.laneCount)
        assertTrue(r.events.any { it.type == DrivingEventType.CHANGE_LANE_LEFT && it.speech == "Move left now for Main St." }, "${r.events}")
    }

    // --- inferred left turn: lane 1 only when it is ours ------------------------------------------------------------

    @Test
    fun `inferred left turn - lane 1 is the target only with a yellow left edge or without any colours`() {
        fun guidance(l: LaneLayout, nav: NavigationState = turnLeft) = DrivingContextEngine().evaluate(world(l), nav).context.laneGuidance!!
        // No colours at all (the server sent none): nothing tells the lanes apart, lane 1 as before.
        assertEquals(LaneAction.CHANGE_LANE_LEFT, guidance(layout(2, 3)).action)
        // Two-way street with the centre line read white: lane 1 may be the oncoming lane - no target, no red lane, no voice.
        val white = guidance(layout(2, 2, colors = listOf("white", "white", "white")))
        assertEquals(LaneAction.UNKNOWN, white.action)
        assertEquals(emptyList(), white.targetLanes)
        assertNull(white.lanesToMove)
        assertTrue(white.text.startsWith("USE LEFT LANE | TURN LEFT"), white.text)
        assertEquals(LaneAction.UNKNOWN, guidance(layout(2, 2, colors = listOf("unknown", "white", "white"))).action)
        // The yellow line bounds lane 1: it is ours.
        val yellow = listOf("yellow", "white", "white", "white")
        val g = guidance(layout(2, 3, colors = yellow, yellowLeftEdge = true))
        assertEquals(LaneAction.CHANGE_LANE_LEFT, g.action)
        assertEquals(listOf(1), g.targetLanes)
        assertEquals(LaneAction.KEEP_LANE, guidance(layout(1, 3, colors = yellow, yellowLeftEdge = true)).action)
        // A right turn does not need the colours.
        assertEquals(listOf(2), guidance(layout(1, 2, colors = listOf("white", "white", "white")), turnRight).targetLanes)
    }

    @Test
    fun `inferred left turn from lines - the yellow cut and a painted median make lane 1 ours`() {
        val road = SyntheticRoad(yawDeg = 10.0)
        fun Lanes.colored(vararg c: String) = copy(boundaryColors = c.toList())
        fun guidance(lanes: Lanes) = DrivingContextEngine().evaluate(withLayout(road.snapshot(lanes)), turnLeft).context.laneGuidance!!
        // Oncoming lane, yellow centre line, our two lanes, the car in the right one: lane 1 (next to the yellow line).
        val twoWay = road.lanes(-8.75, -5.25, -1.75, 1.75)
        val cut = guidance(twoWay.colored("white", "yellow", "white", "white"))
        assertEquals(LaneAction.CHANGE_LANE_LEFT, cut.action)
        assertEquals(2 to 2, cut.currentLane to cut.laneCount)
        // The same road with the centre line read white: 3 lanes, and lane 1 would be the oncoming one - unknown.
        assertEquals(LaneAction.UNKNOWN, guidance(twoWay.colored("white", "white", "white", "white")).action)
        // A painted median: the yellow line 1.5 m left of our left line bounds no lane, so the car's lane is lane 1 of ours.
        val median = road.lanes(-6.8, -3.3, -1.8, 1.8).colored("white", "yellow", "white", "white")
        val layout = withLayout(road.snapshot(median)).laneLayout!!
        assertEquals(1 to 1, layout.laneCount to layout.egoLane)
        assertTrue(layout.yellowLeftEdge && layout.leftmostLaneIsOurs())
        assertEquals(LaneAction.KEEP_LANE, guidance(median).action)
    }

    // --- lead vehicle: own lane only -------------------------------------------------------------------------------

    private val road = SyntheticRoad(yawDeg = 12.0)
    private val threeLanes = road.lanes(-5.25, -1.75, 1.75, 5.25)

    private fun withLayout(s: WorldSnapshot) = s.copy(laneLayout = LaneLayout.from(s))

    @Test
    fun `a car in the next lane at 5 m is not the lead, one in the own lane is`() {
        val beside = road.car(1, lateral = 3.5, forward = 5.0, inEgoPath = true) // the server's camera-axis corridor flagged it
        val s = withLayout(road.snapshot(threeLanes, listOf(beside)))
        assertEquals(3 to 2, s.laneLayout!!.laneCount to s.laneLayout!!.egoLane)
        assertNull(s.leadVehicle())
        val r = engine.evaluate(s)
        assertEquals(FollowingState.NORMAL, r.context.following.state)
        assertNull(r.context.following.leadTrackId)
        assertTrue(r.events.none { it.type == DrivingEventType.VEHICLE_TOO_CLOSE })

        val ahead = road.car(2, lateral = 0.3, forward = 5.0, inEgoPath = false)
        val s2 = withLayout(road.snapshot(threeLanes, listOf(beside, ahead), pts = 1.1)).copy(timing = s.timing!!.copy(seq = 2, ptsSeconds = 1.1), revision = 2)
        assertEquals(2, s2.leadVehicle()!!.id)
        val r2 = engine.evaluate(s2)
        assertEquals(FollowingState.CRITICAL, r2.context.following.state)
        assertEquals(2, r2.events.single { it.type == DrivingEventType.VEHICLE_TOO_CLOSE }.trackId)
    }

    @Test
    fun `vehicles with a distance count, also one held for a missed detection`() {
        val noDistance = road.car(1, 0.0, 8.0, distance = null, inEgoPath = true)
        val bike = road.car(3, 0.0, 5.0).copy(cls = ObjectClass.BICYCLE)
        assertNull(withLayout(road.snapshot(threeLanes, listOf(noDistance, bike))).leadVehicle())
        val held = road.car(2, 0.0, 6.0).copy(visible = false) // the WorldModel holds a track 0.5 s after its last detection
        assertEquals(2, withLayout(road.snapshot(threeLanes, listOf(noDistance, held, bike))).leadVehicle()!!.id)
        val far = road.car(4, 0.0, 30.0)
        assertNull(withLayout(road.snapshot(threeLanes, listOf(far))).leadVehicle(maxDistanceMeters = 25.0))
    }

    @Test
    fun `one missed detection keeps TOO CLOSE and its hysteresis, with no new events`() {
        val e = DrivingContextEngine()
        fun at(seq: Long, forward: Double, visible: Boolean = true): DrivingContextEngine.Result {
            val pts = seq / 10.0
            val car = road.car(2, 0.2, forward, pts = pts).copy(visible = visible)
            return e.evaluate(withLayout(road.snapshot(threeLanes, listOf(car), pts = pts)))
        }
        assertEquals(FollowingState.CRITICAL, at(1, 6.5).context.following.state)
        assertEquals(FollowingState.CRITICAL, at(2, 8.0).context.following.state, "8 m: inside the 9 m exit band")
        val missed = at(3, 8.0, visible = false)
        assertEquals(FollowingState.CRITICAL, missed.context.following.state)
        assertEquals(2, missed.context.following.leadTrackId)
        assertTrue(missed.events.isEmpty(), "${missed.events}")
        val back = at(4, 8.0)
        assertEquals(FollowingState.CRITICAL, back.context.following.state)
        assertTrue(back.events.isEmpty(), "no FOLLOWING_NORMAL / FOLLOWING_CLOSE / new VEHICLE_TOO_CLOSE: ${back.events}")
    }

    @Test
    fun `recorded highway frame - the car right beside us is not the lead (no TOO CLOSE)`() {
        val f = RealLaneFrames.get("real_010", 19L)
        val beside = f.vehicle(1)
        assertEquals(true, beside.inEgoPath, "the server flagged it in path")
        assertTrue(beside.distanceMeters!! < 3.0)
        val s = withLayout(f.snapshot())
        assertTrue(s.laneLayout!!.stableAndFresh())
        assertNotEquals(1, s.leadVehicle()?.id)
        assertEquals(1, WorldSnapshot(objects = s.objects).leadVehicle()!!.id, "the old rule (no camera) picked it")
        val r = engine.evaluate(s)
        assertTrue(r.events.none { it.type == DrivingEventType.VEHICLE_TOO_CLOSE })
        assertNotEquals(FollowingState.CRITICAL, r.context.following.state)
    }

    @Test
    fun `recorded city frame - the car ahead in our lane is the lead despite a 3 m camera-axis lateral`() {
        val f = RealLaneFrames.get("real_009", 654L)
        val car = f.vehicle(5)
        assertTrue(car.lateralMeters!! > 2.5, "yawed phone: the car ahead looks 3 m to the side of the camera axis")
        val s = withLayout(f.snapshot())
        assertEquals(5, s.leadVehicle()!!.id)
        assertEquals(FollowingState.CLOSE, engine.evaluate(s).context.following.state)
    }

    @Test
    fun `without a stable layout - corridor of 1 m around the camera's ground track`() {
        val straight = SyntheticRoad()
        val ahead = straight.car(1, 0.4, 6.0, inEgoPath = false)
        val beside = straight.car(2, 2.5, 5.0, inEgoPath = true)
        assertEquals(1, straight.snapshot(null, listOf(ahead, beside)).leadVehicle()!!.id)
        assertNull(straight.snapshot(null, listOf(beside)).leadVehicle())
        // A layout that is not stable falls back to the corridor too.
        val weak = withLayout(straight.snapshot(straight.lanes(-1.75, 1.75), listOf(beside))).let { it.copy(laneLayout = it.laneLayout!!.copy(quality = 0.2, stable = false)) }
        assertNull(weak.leadVehicle())
    }

    @Test
    fun `corridor follows the road vanishing point, not the camera axis`() {
        // Yawed 12 degrees, no lines: a car on the camera axis at 20 m is 4 m left of the car's track.
        val onAxis = road.car(1, lateral = -4.2, forward = 19.6)
        val onTrack = road.car(2, lateral = 0.0, forward = 22.0)
        val box = onAxis.box
        assertEquals(road.cx, box.centerX, 30.0, "sanity: the first car is on the camera axis")
        val vp = RoadState(Road(0.3, horizonY = road.vpY, vanishingPoint = listOf(road.vpX, road.vpY)), 1.0, 0.0)
        val s = road.snapshot(null, listOf(onAxis, onTrack)).copy(road = vp)
        assertEquals(2, s.leadVehicle()!!.id)
        assertEquals(1, s.copy(road = null).leadVehicle()!!.id, "without any vanishing point the camera axis is all there is")
    }

    @Test
    fun `no road vanishing point, no stable layout - the held layout's or the session's vanishing point, not the camera axis`() {
        // Stop-and-go, yawed 12 degrees: the lead at 10 m hides the lines and the road block has no vanishing point.
        val ownLane = road.car(1, lateral = 0.2, forward = 10.0)
        val onAxis = road.car(2, lateral = -3.8, forward = 18.0)
        assertEquals(road.cx, onAxis.box.centerX, 40.0, "sanity: the second car is on the camera axis")
        val bare = road.snapshot(null, listOf(ownLane, onAxis))
        assertEquals(2, bare.leadVehicle()!!.id, "the camera axis picks the car in the next lane")
        val held = withLayout(road.snapshot(threeLanes, listOf(ownLane, onAxis))).let { it.copy(laneLayout = it.laneLayout!!.copy(ageSeconds = 1.3, stable = false)) }
        assertEquals(1, held.leadVehicle()!!.id, "the held layout's vanishing point (too old for lanes)")
        assertEquals(1, bare.copy(trackVpX = road.vpX).leadVehicle()!!.id, "the session's last vanishing point")
        // The WorldModel keeps it after the layout is gone.
        val model = WorldModel(clockMs = { 0L })
        fun frame(seq: Long, lanes: Lanes?) = TestFrames.frame(seq, lanes = lanes).copy(camera = road.camera, image = road.image)
        model.update(frame(0, threeLanes)); model.update(frame(1, threeLanes))
        val later = model.update(frame(40, null))
        assertNull(later.laneLayout)
        assertEquals(road.vpX, later.trackVpX!!, 1.0)
    }

    @Test
    fun `no camera - the old inEgoPath rule`() {
        fun car(id: Int, d: Double?, inPath: Boolean, y2: Double = 460.0) = ObjectState(
            id = id, cls = ObjectClass.CAR, bbox = listOf(600.0, 400.0, 680.0, y2), confidence = 0.9, ageFrames = 5,
            firstSeenPts = 0.0, lastSeenPts = 1.0, visible = true, distanceMeters = d, inEgoPath = inPath,
        )
        val s = WorldSnapshot(objects = listOf(car(1, 20.0, true), car(2, 8.0, false), car(3, null, true, y2 = 500.0)).associateBy { it.id })
        assertEquals(1, s.leadVehicle()!!.id)
        assertEquals(3, s.copy(objects = s.objects - 1).leadVehicle()!!.id, "no distance: lowest box bottom")
    }
}
