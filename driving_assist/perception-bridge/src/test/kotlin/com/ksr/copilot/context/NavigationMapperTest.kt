package com.ksr.copilot.context

import com.ksr.copilot.context.TestFrames.frame
import com.ksr.copilot.context.TestFrames.lanes
import com.ksr.copilot.perception.NavRouteState
import com.ksr.copilot.perception.NavigationPacketMessage
import com.ksr.copilot.perception.PerceptionCodec
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
        // Driving in lane 1 of 3.
        val r = engine.evaluate(world.update(frame(0, lanes = lanes(1, count = 3))), nav)
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
        val s = world.update(frame(0, lanes = lanes(1, count = 2)))
        assertNull(engine.evaluate(s, base.copy(distanceMeters = 900.0)).context.laneGuidance, "900 m > 300 m: too early for an inferred hint")
        assertEquals(LaneAction.CHANGE_LANE_RIGHT, engine.evaluate(s, base.copy(distanceMeters = 250.0)).context.laneGuidance!!.action)
        assertNull(engine.evaluate(s, base.copy(distanceMeters = 250.0, offRoute = true)).context.laneGuidance, "off route: no lane guidance")
    }
}
