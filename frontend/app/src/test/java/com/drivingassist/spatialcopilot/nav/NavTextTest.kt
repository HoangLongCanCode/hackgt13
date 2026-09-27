package com.drivingassist.spatialcopilot.nav

import com.drivingassist.copilot.context.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavTextTest {
    private fun route(
        maneuver: Maneuver,
        turn: String? = null,
        road: String? = null,
        exit: String? = null,
        stale: Boolean = false,
        offRoute: Boolean = false,
        provider: String = "mock",
        pts: Double? = null,
    ) = RouteGuide(
        maneuver = maneuver, action = maneuver.name, turnDirection = turn, distanceMeters = 300.0, spatialType = null,
        anchorAheadMeters = 300.0, roadName = road, exitNumber = exit, destination = null, etaSeconds = null,
        remainingMeters = null, offRoute = offRoute, speedMps = null, provider = provider, stale = stale, ptsSeconds = pts, receivedAtNs = 0L,
    )

    /** At the packet's own time: never held. */
    private fun text(r: RouteGuide, d: Double?) = NavText.instruction(r, d, r.receivedAtNs)

    @Test
    fun `distances use feet while the voice does, then miles`() {
        assertEquals("50 ft", NavText.distance(0.0))
        assertEquals("50 ft", NavText.distance(10.0))
        assertEquals("250 ft", NavText.distance(76.0)) // 249 ft
        assertEquals("300 ft", NavText.distance(84.0)) // 276 ft rounds up to 300
        assertEquals("900 ft", NavText.distance(274.0))
        assertEquals("1000 ft", NavText.distance(318.0)) // 1043 ft: the voice says "one thousand feet"
        assertEquals("0.2 mi", NavText.distance(321.0)) // 1053 ft: the voice says "a quarter mile"
        assertEquals("0.6 mi", NavText.distance(965.0))
        assertEquals("1.2 mi", NavText.distance(1931.0))
        assertEquals("9.9 mi", NavText.distance(15_930.0))
        assertEquals("10 mi", NavText.distance(16_030.0)) // 9.96 mi would print as 10.0 mi
        assertEquals("12 mi", NavText.distance(19_312.0))
    }

    @Test
    fun `within a mile the banner counts down to the maneuver`() {
        val left = text(route(Maneuver.TURN_LEFT, turn = "left", road = "10th Street"), 274.0)
        assertEquals("Turn left in 900 ft", left.primary)
        assertEquals("onto 10th Street", left.secondary)
        assertEquals(NavGlyph.TURN_LEFT, left.glyph)
        assertFalse(left.dim)
        assertEquals("Turn right in 0.6 mi", text(route(Maneuver.TURN_RIGHT), 965.0).primary)
        assertEquals(NavGlyph.TURN_RIGHT, text(route(Maneuver.TURN_RIGHT), 965.0).glyph)
        assertEquals("Turn right in 1.0 mi", text(route(Maneuver.TURN_RIGHT), 1600.0).primary)
    }

    @Test
    fun `beyond a mile the banner says drive straight and names the maneuver below`() {
        val far = text(route(Maneuver.KEEP_RIGHT, turn = "right", exit = "94"), 3862.0)
        assertEquals("Drive straight", far.primary)
        assertEquals("Exit 94 in 2.4 mi", far.secondary)
        assertEquals(NavGlyph.STRAIGHT, far.glyph)
        val turn = text(route(Maneuver.TURN_LEFT, road = "Peachtree Street"), 1700.0)
        assertEquals("Drive straight", turn.primary)
        assertEquals("Turn left in 1.1 mi", turn.secondary)
    }

    @Test
    fun `under 15 m the maneuver is now`() {
        val now = text(route(Maneuver.TURN_RIGHT, road = "Spring Street"), 12.0)
        assertEquals("Turn right now", now.primary)
        assertEquals("onto Spring Street", now.secondary)
        assertEquals("Turn right in 50 ft", text(route(Maneuver.TURN_RIGHT), 15.0).primary)
        assertEquals("Exit 94 now", text(route(Maneuver.KEEP_RIGHT, exit = "94"), 5.0).primary)
        assertEquals("Stop ahead", text(route(Maneuver.STOP), 5.0).primary)
    }

    @Test
    fun `no distance shows the maneuver without a number`() {
        val t = text(route(Maneuver.MERGE_LEFT, road = "I-75 North"), null)
        assertEquals("Merge left", t.primary)
        assertEquals("onto I-75 North", t.secondary)
        assertEquals(NavGlyph.MERGE, t.glyph)
        assertEquals("Turn left", text(route(Maneuver.TURN_LEFT), Double.NaN).primary)
    }

    @Test
    fun `maneuver names`() {
        assertEquals("Turn left", NavText.name(route(Maneuver.TURN_LEFT)))
        assertEquals("Turn right", NavText.name(route(Maneuver.TURN_RIGHT)))
        assertEquals("Keep left", NavText.name(route(Maneuver.KEEP_LEFT)))
        assertEquals("Keep right", NavText.name(route(Maneuver.KEEP_RIGHT)))
        assertEquals("Merge", NavText.name(route(Maneuver.MERGE)))
        assertEquals("Merge left", NavText.name(route(Maneuver.MERGE_LEFT)))
        assertEquals("Merge right", NavText.name(route(Maneuver.MERGE_RIGHT)))
        assertEquals("Take the ramp", NavText.name(route(Maneuver.ENTER_HIGHWAY)))
        assertEquals("Exit 56", NavText.name(route(Maneuver.EXIT, exit = "56")))
        assertEquals("Exit", NavText.name(route(Maneuver.EXIT)))
        assertEquals("Exit 94", NavText.name(route(Maneuver.KEEP_LEFT, exit = "94")))
        assertEquals("Destination", NavText.name(route(Maneuver.ARRIVE)))
        assertEquals("Stop ahead", NavText.name(route(Maneuver.STOP)))
    }

    @Test
    fun `glyphs follow the maneuver and the exit side`() {
        assertEquals(NavGlyph.EXIT_LEFT, NavText.glyph(route(Maneuver.KEEP_LEFT, exit = "94")))
        assertEquals(NavGlyph.EXIT_RIGHT, NavText.glyph(route(Maneuver.KEEP_RIGHT, exit = "94")))
        assertEquals(NavGlyph.EXIT_LEFT, NavText.glyph(route(Maneuver.EXIT, turn = "left")))
        assertEquals(NavGlyph.EXIT_RIGHT, NavText.glyph(route(Maneuver.EXIT)))
        assertEquals(NavGlyph.EXIT_RIGHT, NavText.glyph(route(Maneuver.ENTER_HIGHWAY)))
        assertEquals(NavGlyph.SLIGHT_LEFT, NavText.glyph(route(Maneuver.KEEP_LEFT)))
        assertEquals(NavGlyph.SLIGHT_RIGHT, NavText.glyph(route(Maneuver.KEEP_RIGHT)))
        assertEquals(NavGlyph.SLIGHT_RIGHT, NavText.glyph(route(Maneuver.TURN_RIGHT, turn = "slight_right")))
        assertEquals(NavGlyph.STOP, NavText.glyph(route(Maneuver.STOP)))
        assertEquals(NavGlyph.ARRIVE, NavText.glyph(route(Maneuver.ARRIVE)))
    }

    @Test
    fun `road names only for turns, keeps, merges and ramps, and only short ones`() {
        assertEquals("onto Ramp Road", text(route(Maneuver.ENTER_HIGHWAY, road = "Ramp Road"), 300.0).secondary)
        assertEquals("onto North Avenue", text(route(Maneuver.KEEP_LEFT, road = " North Avenue "), 300.0).secondary)
        assertNull("exits name the exit, not a road", text(route(Maneuver.KEEP_RIGHT, exit = "94", road = "I-85 North"), 300.0).secondary)
        assertNull(text(route(Maneuver.STOP, road = "Main Street"), 300.0).secondary)
        assertNull(text(route(Maneuver.TURN_LEFT, road = "A".repeat(41)), 300.0).secondary)
        assertEquals("onto ${"A".repeat(40)}", text(route(Maneuver.TURN_LEFT, road = "A".repeat(40)), 300.0).secondary)
    }

    @Test
    fun `arrival`() {
        assertEquals("You have arrived", text(route(Maneuver.ARRIVE), 15.0).primary)
        assertEquals("You have arrived", text(route(Maneuver.ARRIVE), 0.0).primary)
        val near = text(route(Maneuver.ARRIVE), 120.0)
        assertEquals("Destination in 400 ft", near.primary)
        assertEquals(NavGlyph.ARRIVE, near.glyph)
        val far = text(route(Maneuver.ARRIVE), 4000.0)
        assertEquals("Drive straight", far.primary)
        assertEquals("Destination in 2.5 mi", far.secondary)
        assertEquals(NavGlyph.STRAIGHT, far.glyph)
        assertEquals("Destination", text(route(Maneuver.ARRIVE), null).primary)
    }

    @Test
    fun `follow road is drive straight with the road name`() {
        val t = text(route(Maneuver.FOLLOW_ROAD, road = "Peachtree Street Northeast"), 0.0)
        assertEquals("Drive straight", t.primary)
        assertEquals("Peachtree Street Northeast", t.secondary)
        assertEquals(NavGlyph.STRAIGHT, t.glyph)
        assertNull(text(route(Maneuver.FOLLOW_ROAD), 500.0).secondary)
    }

    @Test
    fun `off route and stale routes are dimmed`() {
        val off = text(route(Maneuver.TURN_LEFT, offRoute = true), 200.0)
        assertEquals("Off route", off.primary)
        assertEquals(NavGlyph.OFF_ROUTE, off.glyph)
        assertTrue(off.dim)
        assertNull(off.secondary)
        // A distance that no longer moves is not known: the maneuver name alone.
        val stale = text(route(Maneuver.TURN_LEFT, road = "10th Street", stale = true), 274.0)
        assertEquals("Turn left", stale.primary)
        assertEquals(NavText.PAUSED, stale.secondary)
        assertTrue(stale.dim)
    }

    @Test
    fun `a packet older than the extrapolation cap pauses the banner before it goes stale`() {
        val exit = route(Maneuver.KEEP_RIGHT, turn = "right", exit = "94")
        val cap = (RouteGuide.MAX_EXTRAPOLATION_S * 1e9).toLong()
        val live = NavText.instruction(exit, 262.0, nowNs = cap)
        assertEquals("Exit 94 in 850 ft", live.primary)
        assertFalse(live.dim)
        val held = NavText.instruction(exit, 262.0, nowNs = cap + 100_000_000L)
        assertEquals("Exit 94", held.primary)
        assertEquals(NavText.PAUSED, held.secondary)
        assertTrue(held.dim)
        assertFalse(held.primary.contains("ft") || held.primary.contains("mi"))
        // Beyond a mile the maneuver is not "in 2.4 mi" either.
        val far = NavText.instruction(exit, 3862.0, nowNs = 10_000_000_000L)
        assertEquals("Exit 94", far.primary)
        assertEquals(NavText.PAUSED, far.secondary)
        assertEquals("Destination", NavText.instruction(route(Maneuver.ARRIVE), 120.0, nowNs = 10_000_000_000L).primary)
    }

    @Test
    fun `sim measures the packet age in media time`() {
        val sim = route(Maneuver.TURN_LEFT, pts = 10.0)
        // Wall clock far past the packet but the video only 1 s on (paused or slow): still live.
        assertEquals("Turn left in 900 ft", NavText.instruction(sim, 274.0, nowNs = 60_000_000_000L, ptsNow = 11.0).primary)
        val held = NavText.instruction(sim, 274.0, nowNs = 0L, ptsNow = 12.5)
        assertEquals("Turn left", held.primary)
        assertTrue(held.dim)
    }

    @Test
    fun `the demo route is labelled`() {
        val demo = text(route(Maneuver.EXIT, turn = "right", exit = "56", road = "Exit 56", provider = "demo"), 420.0)
        assertEquals("Exit 56 in 0.3 mi", demo.primary)
        assertEquals("DEMO ROUTE", demo.secondary)
        assertEquals(NavGlyph.EXIT_RIGHT, demo.glyph)
        assertEquals("onto Ramp Road · DEMO ROUTE", text(route(Maneuver.ENTER_HIGHWAY, road = "Ramp Road", provider = "demo"), 300.0).secondary)
    }
}
