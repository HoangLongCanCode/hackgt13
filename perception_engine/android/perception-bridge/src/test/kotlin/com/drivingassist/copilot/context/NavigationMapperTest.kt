package com.drivingassist.copilot.context

import com.drivingassist.copilot.context.TestFrames.frame
import com.drivingassist.copilot.context.TestFrames.laneLines
import com.drivingassist.copilot.perception.NavRouteState
import com.drivingassist.copilot.perception.NavigationPacketMessage
import com.drivingassist.copilot.perception.PerceptionCodec
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** phase1 navigation packets -> NavigationState -> lane guidance in the Driving Context. */
class NavigationMapperTest {
    private fun sample(name: String) = assertIs<NavigationPacketMessage>(
        PerceptionCodec.decode(File(javaClass.getResource("/samples/v2/$name")!!.toURI()).readText()),
    )

    /**
     * A live packet in phase1's shape: the active step is GO_STRAIGHT at route position 1100 m, 100 m ahead;
     * the car has traveled [traveled] m (null = no progress block); [events] is `upcomingManeuvers` (null = absent);
     * [activeInstruction] is the active step's instruction text (null = absent).
     */
    private fun straight(events: String?, traveled: Int? = 1000, speedLimit: String? = null, activeInstruction: String? = null): NavigationPacketMessage {
        val progress = traveled?.let { """"progress":{"speedMps":12.0,"distanceTraveledMeters":$it},""" } ?: ""
        val list = events?.let { """"upcomingManeuvers":$it,""" } ?: ""
        val limit = speedLimit?.let { ""","speedLimit":$it""" } ?: ""
        val instruction = activeInstruction?.let { ",\"instruction\":\"$it\"" } ?: ""
        return assertIs(
            PerceptionCodec.decode(
                """{"type":"navigation.packet","schemaVersion":2,
                "routeState":{"action":"GO_STRAIGHT","audio":"Continue straight in 100 m.","ui":"DISTANCE_LABEL","distanceMeters":100,
                  "offRoute":false,"requiredLane":null,"turnDirection":"straight","roadName":"Spring St"},
                "packet":{"routeId":"r1",$progress$list
                  "activeManeuver":{"eventId":"step_3","type":"GO_STRAIGHT","distanceMeters":1100,"roadName":"Spring St"$instruction},
                  "routeSemantics":{"roadName":"Spring St","highwayName":"I-75","turnDirection":"straight"}}$limit}""",
            ),
        )
    }

    @Test
    fun `phase1 actions map to maneuvers`() {
        val m = NavigationMapper
        assertEquals(Maneuver.FOLLOW_ROAD, m.maneuverFor("GO_STRAIGHT"))
        assertEquals(Maneuver.FOLLOW_ROAD, m.maneuverFor("START_ROUTE"))
        assertEquals(Maneuver.TURN_LEFT, m.maneuverFor("TURN_LEFT"))
        assertEquals(Maneuver.KEEP_RIGHT, m.maneuverFor("keep_right"))
        assertEquals(Maneuver.MERGE, m.maneuverFor("MERGE"))
        assertEquals(Maneuver.MERGE_LEFT, m.maneuverFor("MERGE", "left"))
        assertEquals(Maneuver.EXIT, m.maneuverFor("EXIT_HIGHWAY"))
        assertEquals(Maneuver.ARRIVE, m.maneuverFor("ARRIVE"))
        assertEquals(Maneuver.FOLLOW_ROAD, m.maneuverFor("SOMETHING_NEW"))
    }

    @Test
    fun `required lane free text is parsed`() {
        val m = NavigationMapper
        assertEquals(emptyList<Int>() to LaneSide.RIGHT, m.parseRequiredLane("right"))
        assertEquals(emptyList<Int>() to LaneSide.RIGHT, m.parseRequiredLane("Rightmost lane"))
        assertEquals(emptyList<Int>() to LaneSide.LEFT, m.parseRequiredLane("left 2 lanes"))
        assertEquals(listOf(2) to null, m.parseRequiredLane("2"))
        assertEquals(listOf(2, 3) to null, m.parseRequiredLane("2-3"))
        assertEquals(listOf(2, 3) to null, m.parseRequiredLane("lanes 3, 2"))
        assertEquals(emptyList<Int>() to null, m.parseRequiredLane(null))
        assertEquals(emptyList<Int>() to null, m.parseRequiredLane("none"))
    }

    @Test
    fun `sim packet - turn right with no lane from the route infers the right side`() {
        val nav = assertNotNull(NavigationMapper.toNavigationState(sample("navigation_packet_sim.json")))
        assertEquals(Maneuver.TURN_RIGHT, nav.maneuver)
        assertEquals(120.0, nav.distanceMeters)
        assertEquals("North Ave", nav.label)
        assertEquals(LaneSide.RIGHT, nav.requiredSide)
        assertTrue(nav.laneHintInferred)
        assertEquals(8.4, nav.egoSpeedMps, "GPS speed from packet.progress")
        assertEquals("Turn right in 120 m.", nav.audio)
        assertFalse(nav.offRoute)
        assertNull(NavigationMapper.toNavigationState(sample("navigation_packet_sim.json"), inferLaneSide = false)!!.requiredSide)
    }

    @Test
    fun `live packet - exit with required lane uses the exit number as label`() {
        val nav = assertNotNull(NavigationMapper.toNavigationState(sample("navigation_packet_live.json")))
        assertEquals(Maneuver.EXIT, nav.maneuver)
        assertEquals("Exit 250", nav.label)
        assertEquals(LaneSide.RIGHT, nav.requiredSide)
        assertFalse(nav.laneHintInferred, "the route said which lane")
        assertEquals(24.5, nav.egoSpeedMps)
        assertEquals(listOf(2), NavigationMapper.toNavigationState(sample("navigation_packet_numeric_lane.json"))!!.requiredLanes)
    }

    @Test
    fun `a GO_STRAIGHT step is skipped for the next real maneuver - ramp with an exit number`() {
        val msg = sample("navigation_packet_ramp_exit.json")
        val t = assertNotNull(NavigationMapper.target(msg))
        assertEquals("KEEP_RIGHT", t.action)
        assertEquals(3400.0 - 1050.0, t.distanceMeters)
        assertEquals("94", t.exitNumber)
        assertEquals("right", t.turnDirection)
        assertEquals("step_4", t.eventId)
        assertFalse(t.isActive)
        assertNull(t.audio, "phase1's prompt is about the skipped step")

        val nav = assertNotNull(NavigationMapper.toNavigationState(msg))
        assertEquals(Maneuver.KEEP_RIGHT, nav.maneuver)
        assertEquals(2350.0, nav.distanceMeters)
        assertEquals("Exit 94", nav.label, "a ramp with an exit number reads as the exit")
        assertEquals(LaneSide.RIGHT, nav.requiredSide)
        assertTrue(nav.laneHintInferred, "side inferred from the skipped-to target")
        assertNull(nav.audio)
        assertEquals(35, nav.mapSpeedLimitMph)
        assertEquals("North Avenue Northwest", nav.mapSpeedLimitRoad)
        assertEquals("step_4", nav.eventId, "the target's identity")
    }

    @Test
    fun `a roundabout or ferry step sent as GO_STRAIGHT is not skipped`() {
        for (text in listOf("At the roundabout, take the 2nd exit onto X St", "Enter the Traffic Circle", "At the ROTARY, take the 1st exit", "Take the ferry")) {
            val msg = straight(
                """[{"eventId":"step_4","type":"GO_STRAIGHT","distanceMeters":1150,"roadName":"X St","instruction":"$text"},
                {"eventId":"step_5","type":"TURN_RIGHT","distanceMeters":4650,"roadName":"Y St"}]""",
            )
            val t = NavigationMapper.target(msg)!!
            assertEquals("step_4", t.eventId, text)
            assertEquals(150.0, t.distanceMeters, text)
            assertEquals(text, t.instruction, text)
            assertFalse(t.isActive, text)
            val nav = NavigationMapper.toNavigationState(msg)!!
            assertEquals(Maneuver.FOLLOW_ROAD, nav.maneuver, text)
            assertEquals(150.0, nav.distanceMeters, "not the right turn 3.5 km later: $text")
        }
        val active = NavigationMapper.target(
            straight("""[{"type":"TURN_RIGHT","distanceMeters":4650}]""", activeInstruction = "At the roundabout, take the 2nd exit onto X St"),
        )!!
        assertTrue(active.isActive, "the active step is the roundabout")
        assertEquals(100.0, active.distanceMeters)
        assertEquals("Continue straight in 100 m.", active.audio, "phase1's prompt is about this step")
        val plain = NavigationMapper.target(
            straight("""[{"type":"GO_STRAIGHT","distanceMeters":1150,"instruction":"Continue onto X St"},{"type":"TURN_RIGHT","distanceMeters":4650}]"""),
        )!!
        assertEquals("TURN_RIGHT", plain.action, "a plain continue step is still skipped")
        val unknown = NavigationMapper.target(
            straight("""[{"type":"SOMETHING_NEW","distanceMeters":1150},{"type":"TURN_RIGHT","distanceMeters":4650}]"""),
        )!!
        assertEquals("SOMETHING_NEW", unknown.action, "an unknown step type is not skipped")
    }

    @Test
    fun `upcoming maneuvers are sorted by route position, events behind the car are ignored`() {
        val nav = assertNotNull(
            NavigationMapper.toNavigationState(
                straight(
                    """[{"eventId":"step_5","type":"ARRIVE","distanceMeters":3000},
                    {"eventId":"step_1","type":"TURN_RIGHT","distanceMeters":400,"roadName":"Behind Rd"},
                    {"eventId":"step_4","type":"TURN_LEFT","distanceMeters":1500,"roadName":"Ponce de Leon Ave"},
                    {"eventId":"step_3","type":"GO_STRAIGHT","distanceMeters":1100,"roadName":"Spring St"}]""",
                ),
            ),
        )
        assertEquals(Maneuver.TURN_LEFT, nav.maneuver)
        assertEquals(500.0, nav.distanceMeters)
        assertEquals("Ponce de Leon Ave", nav.label)
        assertEquals(LaneSide.LEFT, nav.requiredSide)
        assertTrue(nav.laneHintInferred)
    }

    @Test
    fun `only straight steps ahead - the target is the arrival`() {
        val msg = straight(
            """[{"eventId":"step_3","type":"GO_STRAIGHT","distanceMeters":1100},{"eventId":"step_4","type":"START_ROUTE","distanceMeters":2000},
            {"eventId":"step_6","type":"ARRIVE","distanceMeters":2500,"roadName":"Student Center"}]""",
        )
        val t = NavigationMapper.target(msg)!!
        assertEquals("ARRIVE", t.action)
        assertEquals(1500.0, t.distanceMeters)
        assertEquals("straight", t.turnDirection, "as phase1's semanticsTurnDirection")
        val nav = NavigationMapper.toNavigationState(msg)!!
        assertEquals(Maneuver.ARRIVE, nav.maneuver)
        assertEquals("Student Center", nav.label)
        assertNull(nav.requiredSide)
    }

    @Test
    fun `skipped-to exit keeps its exit number and required lane`() {
        val nav = NavigationMapper.toNavigationState(
            straight("""[{"eventId":"step_4","type":"EXIT_HIGHWAY","distanceMeters":1800,"exitNumber":"12A","requiredLane":"right"}]"""),
        )!!
        assertEquals(Maneuver.EXIT, nav.maneuver)
        assertEquals(800.0, nav.distanceMeters)
        assertEquals("Exit 12A", nav.label)
        assertEquals(LaneSide.RIGHT, nav.requiredSide)
        assertFalse(nav.laneHintInferred, "the route said which lane")
        val unnamed = NavigationMapper.target(straight("""[{"type":"EXIT_HIGHWAY","distanceMeters":1800}]"""))!!
        assertEquals("exit", unnamed.turnDirection)
        assertEquals("EXIT_HIGHWAY@1800", unnamed.eventId, "no eventId: type@route position")
        assertEquals("I-75", NavigationMapper.toNavigationState(straight("""[{"type":"EXIT_HIGHWAY","distanceMeters":1800}]"""))!!.label)
    }

    @Test
    fun `without a real maneuver ahead, a list or progress the active step stays as it is`() {
        fun assertActive(msg: NavigationPacketMessage, why: String) {
            val t = NavigationMapper.target(msg)!!
            assertTrue(t.isActive, why)
            assertEquals("GO_STRAIGHT", t.action, why)
            assertEquals(100.0, t.distanceMeters, why)
            assertEquals("step_3", t.eventId, why)
            val nav = NavigationMapper.toNavigationState(msg)!!
            assertEquals(Maneuver.FOLLOW_ROAD, nav.maneuver, why)
            assertEquals("Spring St", nav.label, why)
            assertEquals("Continue straight in 100 m.", nav.audio, why)
        }
        assertActive(straight(null), "no upcomingManeuvers")
        assertActive(straight("[]"), "empty list")
        assertActive(straight("""[{"type":"TURN_RIGHT","distanceMeters":900},{"type":"GO_STRAIGHT","distanceMeters":1100}]"""), "the only turn is behind")
        // No progress block: traveled = active position - routeState distance (1100 - 100).
        assertEquals(500.0, NavigationMapper.target(straight("""[{"type":"TURN_LEFT","distanceMeters":1500}]""", traveled = null))!!.distanceMeters)
    }

    @Test
    fun `a real active maneuver is used as phase1 sent it`() {
        val t = NavigationMapper.target(sample("navigation_packet_sim.json"))!! // TURN_RIGHT active, ARRIVE later
        assertTrue(t.isActive)
        assertEquals("TURN_RIGHT", t.action)
        assertEquals(120.0, t.distanceMeters)
        assertEquals("evt_2", t.eventId)
        assertEquals("Turn right in 120 m.", t.audio)
        val live = NavigationMapper.target(sample("navigation_packet_live.json"))!!
        assertEquals("250", live.exitNumber, "exit number from routeSemantics")
        assertEquals("right", live.requiredLane)
    }

    @Test
    fun `map speed limit outside 5 to 85 mph is unknown`() {
        val events = """[{"type":"TURN_LEFT","distanceMeters":1500}]"""
        fun nav(limit: String?) = NavigationMapper.toNavigationState(straight(events, speedLimit = limit))!!
        assertEquals(25, nav("""{"valueMph":25}""").mapSpeedLimitMph)
        assertNull(nav("""{"valueMph":25}""").mapSpeedLimitRoad)
        assertNull(nav("""{"valueMph":90,"roadName":"X"}""").mapSpeedLimitMph)
        assertNull(nav("""{"valueMph":90,"roadName":"X"}""").mapSpeedLimitRoad)
        assertNull(nav("""{"valueMph":0}""").mapSpeedLimitMph)
        assertNull(nav("null").mapSpeedLimitMph)
        assertNull(nav(null).mapSpeedLimitMph)
    }

    @Test
    fun `engine - map speed limit shows with its source while no sign is confirmed`() {
        val engine = DrivingContextEngine()
        val ctx = engine.evaluate(WorldSnapshot.EMPTY, NavigationMapper.toNavigationState(sample("navigation_packet_ramp_exit.json"))).context
        assertEquals(35, ctx.speedLimit)
        assertEquals(SpeedLimitSource.MAP, ctx.speedLimitSource)
        val none = engine.evaluate(WorldSnapshot.EMPTY, null).context
        assertNull(none.speedLimit, "no (or a stale) packet: no map value")
        assertNull(none.speedLimitSource)
    }

    @Test
    fun `sign classes read as words`() {
        assertEquals("PEDESTRIAN CROSSING", DrivingContextEngine.signText("pedestrianCrossing"))
        assertEquals("DO NOT ENTER", DrivingContextEngine.signText("doNotEnter"))
        assertEquals("DO NOT ENTER", DrivingContextEngine.signText("do_not_enter"))
        assertEquals("STOP", DrivingContextEngine.signText("stop"))
    }

    @Test
    fun `no route or no distance means no navigation state`() {
        assertNull(NavigationMapper.toNavigationState(sample("navigation_packet_no_route.json")))
        assertNull(NavigationMapper.toNavigationState(NavRouteState(action = "TURN_LEFT", distanceMeters = null)))
        assertEquals(0.0, NavigationMapper.toNavigationState(NavRouteState(action = "ARRIVE"))!!.distanceMeters)
    }

    @Test
    fun `engine - route lane plus perceived lanes gives lane guidance, phase1 audio is spoken`() {
        val world = WorldModel(clockMs = { 0L })
        val engine = DrivingContextEngine()
        val nav = NavigationMapper.toNavigationState(sample("navigation_packet_live.json"))!! // exit in 400 m, rightmost lane
        // Driving in lane 1 of 3 (the layout of the lines is stable from the third lanes run).
        repeat(2) { world.update(frame(it.toLong(), lanes = laneLines(1, count = 3))) }
        val r = engine.evaluate(world.update(frame(2, lanes = laneLines(1, count = 3))), nav)
        val g = assertNotNull(r.context.laneGuidance)
        assertEquals(LaneAction.CHANGE_LANE_RIGHT, g.action)
        assertEquals(listOf(3), g.targetLanes)
        assertEquals(2, g.lanesToMove)
        val maneuver = r.events.single { it.type == DrivingEventType.EXIT }
        assertEquals("Take the exit in 400 m.", maneuver.speech, "route engine's own prompt, not a generated one")
        assertTrue(r.context.activeAlerts.any { it.type == DrivingEventType.CHANGE_LANE_RIGHT })
    }

    @Test
    fun `engine - inferred side only guides close to the maneuver and never off route`() {
        val world = WorldModel(clockMs = { 0L })
        val engine = DrivingContextEngine()
        val base = NavigationMapper.toNavigationState(sample("navigation_packet_sim.json"))!! // turn right, inferred RIGHT
        repeat(2) { world.update(frame(it.toLong(), lanes = laneLines(1, count = 2))) }
        val s = world.update(frame(2, lanes = laneLines(1, count = 2)))
        assertNull(engine.evaluate(s, base.copy(distanceMeters = 900.0)).context.laneGuidance, "900 m > 300 m: too early for an inferred hint")
        assertEquals(LaneAction.CHANGE_LANE_RIGHT, engine.evaluate(s, base.copy(distanceMeters = 250.0)).context.laneGuidance!!.action)
        assertNull(engine.evaluate(s, base.copy(distanceMeters = 250.0, offRoute = true)).context.laneGuidance, "off route: no lane guidance")
        // At speed the inferred hint starts 20 s of travel out (at most 800 m), so a highway lane change fits before the exit.
        assertNull(engine.evaluate(s, base.copy(distanceMeters = 390.0, egoSpeedMps = 5.0)).context.laneGuidance, "slow: still 300 m")
        assertEquals(LaneAction.CHANGE_LANE_RIGHT, engine.evaluate(s, base.copy(distanceMeters = 390.0, egoSpeedMps = 27.0)).context.laneGuidance!!.action)
        assertNull(engine.evaluate(s, base.copy(distanceMeters = 850.0, egoSpeedMps = 70.0)).context.laneGuidance, "capped at 800 m")
    }
}
