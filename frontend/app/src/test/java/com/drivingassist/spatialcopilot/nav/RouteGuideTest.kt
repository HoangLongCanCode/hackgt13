package com.drivingassist.spatialcopilot.nav

import com.drivingassist.copilot.bridge.NavigationUpdate
import com.drivingassist.copilot.context.Maneuver
import com.drivingassist.copilot.perception.NavigationPacketMessage
import com.drivingassist.copilot.perception.PerceptionCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGuideTest {
    /**
     * A live packet in phase1's shape: [action] is active at route position 1100 m (100 m ahead, the car has
     * traveled 1000 m); [events] is `upcomingManeuvers`.
     */
    private fun guide(events: String, action: String = "GO_STRAIGHT", turn: String = "straight", road: String = "I-75 N"): RouteGuide {
        val json = """{"type":"navigation.packet","schemaVersion":2,
            "routeState":{"action":"$action","audio":"Continue straight in 100 m.","ui":"DISTANCE_LABEL","distanceMeters":100,
              "offRoute":false,"turnDirection":"$turn","roadName":"$road"},
            "packet":{"routeId":"route_9","source":{"provider":"mock"},
              "progress":{"speedMps":13.4,"distanceTraveledMeters":1000,"offRoute":false},
              "activeManeuver":{"eventId":"step_3","type":"$action","distanceMeters":1100,"roadName":"$road"},
              "upcomingManeuvers":$events,
              "spatialInstructions":[{"id":"spatial_step_3","type":"DISTANCE_LABEL","anchor":{"kind":"ROUTE_POINT","routeDistanceMeters":1100}}],
              "routeSemantics":{"roadName":"$road","turnDirection":"$turn"}}}"""
        val msg = PerceptionCodec.decode(json) as NavigationPacketMessage
        return RouteGuide.from(NavigationUpdate.from(msg, receivedAtNs = 0L, sequence = 1))!!
    }

    @Test
    fun `a continue step is skipped for the next real maneuver`() {
        val g = guide(
            """[{"eventId":"step_5","type":"ARRIVE","distanceMeters":5200},
            {"eventId":"step_4","type":"TURN_LEFT","distanceMeters":1600,"roadName":"Ponce de Leon Ave"},
            {"eventId":"step_3","type":"GO_STRAIGHT","distanceMeters":1100,"roadName":"I-75 N"}]""",
        )
        assertEquals(Maneuver.TURN_LEFT, g.maneuver)
        assertEquals("TURN_LEFT", g.action)
        assertEquals("left", g.turnDirection)
        assertEquals(600.0, g.distanceMeters!!, 1e-9)
        assertEquals("Ponce de Leon Ave", g.roadName)
        assertEquals("TURN LEFT", g.headline)
        assertEquals("route_9/step_4", g.eventKey)
        assertEquals("route_9", g.routeKey)
        // phase1's spatial instruction is left as it is (it belongs to the active step).
        assertEquals("DISTANCE_LABEL", g.spatialType)
        assertEquals(100.0, g.anchorAheadMeters!!, 1e-9)
        assertEquals(600.0 - 13.4, g.distanceAt(1_000_000_000L)!!, 1e-6)
    }

    @Test
    fun `past the extrapolation cap the distance stops moving and the route is held`() {
        val g = guide("""[{"eventId":"step_4","type":"TURN_LEFT","distanceMeters":1600}]""")
        assertEquals(1.5, g.ageSeconds(1_500_000_000L), 1e-9)
        assertFalse(g.heldAt(1_500_000_000L))
        assertFalse(g.heldAt(2_000_000_000L))
        assertTrue(g.heldAt(2_100_000_000L))
        assertEquals(600.0 - 13.4 * RouteGuide.MAX_EXTRAPOLATION_S, g.distanceAt(8_000_000_000L)!!, 1e-6)
        // Sim: media time, not the wall clock; a packet without pts falls back to the wall clock.
        assertTrue(g.copy(ptsSeconds = 4.0).heldAt(0L, ptsNow = 6.5))
        assertFalse(g.copy(ptsSeconds = 4.0).heldAt(60_000_000_000L, ptsNow = 5.0))
        assertTrue(g.heldAt(3_000_000_000L, ptsNow = 5.0))
    }

    @Test
    fun `a ramp with an exit number after a continue step is an exit`() {
        val g = guide(
            """[{"eventId":"step_3","type":"GO_STRAIGHT","distanceMeters":1100},
            {"eventId":"step_4","type":"KEEP_RIGHT","distanceMeters":3400,"roadName":"10th Street","exitNumber":"94"}]""",
        )
        assertEquals(Maneuver.KEEP_RIGHT, g.maneuver)
        assertTrue(g.isExit)
        assertEquals("94", g.exitNumber)
        assertEquals("right", g.turnDirection)
        assertEquals(2400.0, g.distanceMeters!!, 1e-9)
        assertEquals("route_9/step_4", g.eventKey)
    }

    @Test
    fun `a real active maneuver is read as phase1 sent it`() {
        val g = guide(
            """[{"eventId":"step_3","type":"TURN_RIGHT","distanceMeters":1100},{"eventId":"step_4","type":"KEEP_RIGHT","distanceMeters":3400,"exitNumber":"94"}]""",
            action = "TURN_RIGHT", turn = "right", road = "North Ave",
        )
        assertEquals(Maneuver.TURN_RIGHT, g.maneuver)
        assertEquals(100.0, g.distanceMeters!!, 1e-9)
        assertEquals("North Ave", g.roadName)
        assertNull(g.exitNumber)
        assertFalse(g.isExit)
        assertEquals("route_9/step_3", g.eventKey)
    }

    @Test
    fun `nothing real ahead keeps the continue step`() {
        val g = guide("""[{"eventId":"step_3","type":"GO_STRAIGHT","distanceMeters":1100}]""")
        assertEquals(Maneuver.FOLLOW_ROAD, g.maneuver)
        assertEquals("GO_STRAIGHT", g.action)
        assertEquals("CONTINUE", g.headline)
        assertEquals(100.0, g.distanceMeters!!, 1e-9)
        assertEquals("I-75 N", g.roadName)
        assertEquals("route_9/step_3", g.eventKey)
    }
}
