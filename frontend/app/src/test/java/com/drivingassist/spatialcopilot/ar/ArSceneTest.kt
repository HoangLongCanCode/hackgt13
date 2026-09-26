package com.drivingassist.spatialcopilot.ar

import com.drivingassist.copilot.bridge.NavigationUpdate
import com.drivingassist.copilot.context.DrivingContext
import com.drivingassist.copilot.context.FollowingInfo
import com.drivingassist.copilot.context.FollowingState
import com.drivingassist.copilot.context.LaneAction
import com.drivingassist.copilot.context.LaneGuidance
import com.drivingassist.copilot.context.Maneuver
import com.drivingassist.copilot.context.Priority
import com.drivingassist.copilot.perception.NavigationPacketMessage
import com.drivingassist.copilot.perception.PerceptionCodec
import com.drivingassist.spatialcopilot.nav.DemoDrive
import com.drivingassist.spatialcopilot.nav.RouteGuide
import com.drivingassist.spatialcopilot.session.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArSceneTest {
    private val samples = File("../../perception_engine/contracts/samples/v2")

    private fun route(maneuver: Maneuver, turn: String? = null, stale: Boolean = false, offRoute: Boolean = false) = RouteGuide(
        maneuver = maneuver, action = maneuver.name, turnDirection = turn, distanceMeters = 30.0, spatialType = null,
        anchorAheadMeters = 30.0, roadName = null, exitNumber = null, destination = null, etaSeconds = null,
        remainingMeters = null, offRoute = offRoute, speedMps = null, provider = "mock", stale = stale, ptsSeconds = null, receivedAtNs = 0L,
    )

    private fun lane(action: LaneAction) = LaneGuidance(action, 2, 3, listOf(3), 1, Priority.UPCOMING_NAVIGATION, "")

    @Test
    fun `route guide reads phase1's packet without adding route logic`() {
        val msg = PerceptionCodec.decode(File(samples, "navigation.packet.sim_city.json").readText()) as NavigationPacketMessage
        val g = RouteGuide.from(NavigationUpdate.from(msg, receivedAtNs = 0L, sequence = 1))!!
        assertEquals(Maneuver.TURN_RIGHT, g.maneuver)
        assertEquals("TURN RIGHT", g.headline)
        assertEquals(28.0, g.distanceMeters!!, 1e-9)
        assertEquals("anchor route distance 120 - traveled 92", 28.0, g.anchorAheadMeters!!, 1e-9)
        assertEquals("TURN_ARROW", g.spatialType)
        assertEquals("mock", g.provider)
        assertTrue(g.eventKey.endsWith("/step_2"))
        // Between packets the countdown moves on by phase1's speed (7.64 m/s), at most 2 s.
        assertEquals(28.0 - 7.64, g.distanceAt(1_000_000_000L)!!, 1e-6)
        assertEquals(28.0 - 2 * 7.64, g.distanceAt(9_000_000_000L)!!, 1e-6)
    }

    @Test
    fun `arrow intents follow the maneuver and the lane guidance`() {
        assertEquals(ArrowKind.TURN_RIGHT, RouteArrows.intent(route(Maneuver.TURN_RIGHT, "right"), null, 30.0)!!.kind)
        assertEquals(ArrowKind.TURN_LEFT, RouteArrows.intent(route(Maneuver.TURN_LEFT, "left"), null, 120.0)!!.kind)
        assertNull("far away: the road stays clear", RouteArrows.intent(route(Maneuver.TURN_RIGHT), null, 400.0))
        assertEquals(ArrowKind.LANE_RIGHT, RouteArrows.intent(route(Maneuver.EXIT, "exit"), lane(LaneAction.CHANGE_LANE_RIGHT), 400.0)!!.kind)
        assertEquals("close to the turn the turn wins over the lane change",
            ArrowKind.TURN_RIGHT, RouteArrows.intent(route(Maneuver.TURN_RIGHT), lane(LaneAction.CHANGE_LANE_RIGHT), 30.0)!!.kind)
        assertEquals("exit side unknown: no bend is drawn", ArrowKind.FOLLOW, RouteArrows.intent(route(Maneuver.EXIT, "exit"), null, 80.0)!!.kind)
        assertEquals(ArrowKind.BEAR_RIGHT, RouteArrows.intent(route(Maneuver.EXIT, "right"), null, 80.0)!!.kind)
        assertNull("nothing coming up", RouteArrows.intent(route(Maneuver.FOLLOW_ROAD), null, 80.0))
        assertNull("stale route", RouteArrows.intent(route(Maneuver.TURN_RIGHT, stale = true), null, 30.0))
        assertNull("off route", RouteArrows.intent(route(Maneuver.TURN_RIGHT, offRoute = true), null, 30.0))
        assertNull("no route", RouteArrows.intent(null, lane(LaneAction.CHANGE_LANE_LEFT), 30.0))
    }

    @Test
    fun `a right turn path bends right at the maneuver point`() {
        val path = RouteArrows.path(ArrowIntent(ArrowKind.TURN_RIGHT, 20.0), EgoLane.AXIS, 5.0)
        assertEquals(0.0, path.first().x, 1e-9)
        assertTrue(path.last().x > 10.0)
        assertEquals(20.0, path.last().z, 1e-6)
        val lanePath = RouteArrows.path(ArrowIntent(ArrowKind.LANE_LEFT, 300.0), EgoLane.AXIS, 5.0)
        assertEquals(-3.5, lanePath.last().x, 1e-6)
    }

    private fun input(state: FollowingState, distance: Double?, route: RouteGuide? = null, debug: Boolean = false) = ArInput(
        world = DemoDrive.world(10.0, 150),
        context = DrivingContext(following = FollowingInfo(state = state, leadTrackId = 7, distanceMeters = distance)),
        route = route,
        routeDistanceMeters = route?.distanceMeters,
        debug = debug,
    )

    @Test
    fun `lead vehicle - clean view brackets only TOO CLOSE without a badge, debug also CLOSE, never without a distance`() {
        val b = ArSceneBuilder()
        assertNull(b.build(input(FollowingState.NORMAL, 25.0), 2560f, 1600f, 1L).lead)
        assertNull("clean view: no CLOSE highlight", b.build(input(FollowingState.CLOSE, 12.34), 2560f, 1600f, 2L).lead)
        val critical = b.build(input(FollowingState.CRITICAL, 8.44), 2560f, 1600f, 3L).lead!!
        assertEquals("Vehicle ahead: 8.4 m", critical.label)
        assertTrue(critical.critical)
        assertFalse("the HUD shows the distance", critical.badge)
        assertNull("no distance, no highlight", b.build(input(FollowingState.CRITICAL, null), 2560f, 1600f, 4L).lead)
        assertNull("clean view has no debug layer", b.build(input(FollowingState.NORMAL, 25.0), 2560f, 1600f, 5L).debug)
        assertNotNull(b.build(input(FollowingState.NORMAL, 25.0, debug = true), 2560f, 1600f, 6L).debug)
        val close = b.build(input(FollowingState.CLOSE, 12.34, debug = true), 2560f, 1600f, 7L).lead!!
        assertEquals("Vehicle ahead: 12.3 m", close.label)
        assertEquals(false, close.critical)
        assertTrue(close.badge)
        assertTrue(b.build(input(FollowingState.CRITICAL, 8.44, debug = true), 2560f, 1600f, 8L).lead!!.badge)
    }

    @Test
    fun `debug chevrons fade in on the road and fade out when the route stops being relevant`() {
        val b = ArSceneBuilder()
        val r = route(Maneuver.TURN_RIGHT, "right")
        var t = 1_000_000_000L
        var scene = b.build(input(FollowingState.NORMAL, 25.0, r, debug = true), 2560f, 1600f, t)
        repeat(30) { t += 16_000_000L; scene = b.build(input(FollowingState.NORMAL, 25.0, r, debug = true), 2560f, 1600f, t) }
        assertEquals(ArrowKind.TURN_RIGHT, scene.arrowKind)
        assertTrue("chevrons drawn", scene.chevrons.size >= 3)
        assertTrue("fully faded in", scene.chevrons.maxOf { it.alpha } > 0.5f)
        // All chevrons sit below the horizon row, i.e. on the road.
        val horizon = FillCenter(960, 540, 2560f, 1600f).point(0.0, 250.0).y
        assertTrue(scene.chevrons.all { it.tip.y > horizon && it.left.y > horizon })
        repeat(40) { t += 16_000_000L; scene = b.build(input(FollowingState.NORMAL, 25.0, null, debug = true), 2560f, 1600f, t) }
        assertNull(scene.arrowKind)
        assertTrue(scene.chevrons.isEmpty())
        assertTrue(scene.laneArrows.isEmpty())
    }

    @Test
    fun `clean view draws the lane arrow, no chevrons, no ribbon`() {
        val b = ArSceneBuilder()
        val r = route(Maneuver.TURN_RIGHT, "right")
        var t = 1_000_000_000L
        var scene = b.build(input(FollowingState.NORMAL, 25.0, r), 2560f, 1600f, t)
        repeat(30) { t += 16_000_000L; scene = b.build(input(FollowingState.NORMAL, 25.0, r), 2560f, 1600f, t) }
        assertTrue(scene.chevrons.isEmpty())
        assertTrue(scene.ribbon.isEmpty())
        assertNull(scene.debug)
        // No lane guidance: the ego lane's arrow alone, green, shaped for the turn 30 m ahead.
        val a = scene.laneArrows.single()
        assertNull(a.lane)
        assertEquals(LaneArrowStyle.TARGET, a.style)
        assertEquals(LaneArrowGlyph.TURN_RIGHT, a.glyph)
    }

    @Test
    fun `clean view keeps the destination pin`() {
        val b = ArSceneBuilder()
        val r = route(Maneuver.ARRIVE)
        var t = 1_000_000_000L
        var scene = b.build(input(FollowingState.NORMAL, 25.0, r), 2560f, 1600f, t)
        repeat(10) { t += 16_000_000L; scene = b.build(input(FollowingState.NORMAL, 25.0, r), 2560f, 1600f, t) }
        assertEquals(ArrowKind.ARRIVE, scene.arrowKind)
        assertNotNull(scene.pin)
        assertTrue(scene.chevrons.isEmpty())
    }

    @Test
    fun `bad server URLs are refused before they reach the socket`() {
        assertTrue(AppSettings.isValidUrl("ws://127.0.0.1:8765/perception"))
        assertTrue(AppSettings.isValidUrl("wss://example.org/perception"))
        assertFalse(AppSettings.isValidUrl("ws://"))
        assertFalse(AppSettings.isValidUrl("ws://10.0.0.2:8765:8765/perception"))
        assertFalse(AppSettings.isValidUrl("ws://10.0.0.2:99999/perception"))
        assertFalse(AppSettings.isValidUrl("http://10.0.0.2:8765/perception"))
    }

    @Test
    fun `a horizon near the bottom of the frame gives no arrows instead of a crash`() {
        for (kind in ArrowKind.entries) {
            assertTrue(kind.name, RouteArrows.path(ArrowIntent(kind, 20.0), EgoLane.AXIS, 45.0).isEmpty())
        }
    }
}
