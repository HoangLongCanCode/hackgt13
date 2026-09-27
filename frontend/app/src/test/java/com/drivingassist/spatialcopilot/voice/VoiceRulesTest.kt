package com.drivingassist.spatialcopilot.voice

import com.drivingassist.copilot.context.DrivingContext
import com.drivingassist.copilot.context.FollowingInfo
import com.drivingassist.copilot.context.FollowingState
import com.drivingassist.copilot.context.Maneuver
import com.drivingassist.copilot.context.NavigationState
import com.drivingassist.copilot.context.PedestrianInfo
import com.drivingassist.copilot.context.Priority
import com.drivingassist.copilot.context.WorldSnapshot
import com.drivingassist.copilot.perception.UplinkHeader
import com.drivingassist.spatialcopilot.nav.RouteGuide
import com.drivingassist.spatialcopilot.session.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoiceRulesTest {
    private val catalog = CueCatalog.parse(File("../../perception_engine/docs/audio/audio_cues.v1.json").readText())

    private fun following(state: FollowingState, d: Double? = 6.0, rel: Double? = -2.0) =
        FollowingInfo(state = state, leadTrackId = 7, distanceMeters = d, relativeSpeedMps = rel)

    private fun input(now: Long, f: FollowingInfo, stale: Boolean = false, route: RouteGuide? = null, d: Double? = null) = PolicyInput(
        nowMs = now, context = DrivingContext(following = f, perceptionStale = stale), world = WorldSnapshot.EMPTY,
        linkConnected = true, takenOver = false, simPaused = false, hostVisible = true, route = route,
        routeDistanceMeters = d, live = false, gpsAccuracyMeters = null,
    )

    /** Runs the policy every 50 ms from [from] to [to] with the same following info; returns the TOO CLOSE requests. */
    private fun run(p: CuePolicy, from: Long, to: Long, f: FollowingInfo): List<CueRequest> =
        (from..to step 50).flatMap { p.step(input(it, f)) }.filter { it.cueId == CuePolicy.VTC }

    @Test
    fun `catalog numbers load from the agreed JSON`() {
        val vtc = catalog[CuePolicy.VTC]
        assertEquals(Priority.CRITICAL_SAFETY, vtc.priority)
        assertEquals(250L, vtc.persistenceMs)
        assertEquals(8_000L, vtc.minRepeatMs)
        assertEquals("E1", vtc.earcon)
        assertEquals("Vehicle too close.", vtc.text)
        assertTrue(catalog.fixedPhrases.all { catalog.speechRegex.matches(it) })
        assertTrue("Turn right." in catalog.fixedPhrases)
    }

    @Test
    fun `TOO CLOSE speaks once on entry, not while it holds, and again only after NORMAL`() {
        val p = CuePolicy(catalog)
        run(p, 0, 1_000, following(FollowingState.NORMAL, 25.0)) // perception warm
        val first = run(p, 1_050, 3_000, following(FollowingState.CRITICAL))
        assertEquals(1, first.size)
        assertEquals("Vehicle too close.", first.single().text)
        assertTrue("after the 250 ms persistence", first.single().createdMs >= 1_050 + 250)
        // Back to CLOSE only (not NORMAL): no re-arm, no second warning.
        run(p, 3_050, 4_000, following(FollowingState.CLOSE, 12.0))
        assertTrue(run(p, 4_050, 5_000, following(FollowingState.CRITICAL)).isEmpty())
        // NORMAL re-arms; a re-entry within 8 s of the spoken warning plays the earcon alone.
        run(p, 5_050, 5_500, following(FollowingState.NORMAL, 25.0))
        val earconOnly = run(p, 5_550, 6_500, following(FollowingState.CRITICAL))
        assertEquals(1, earconOnly.size)
        assertNull(earconOnly.single().text)
        // More than 8 s after the last spoken warning: spoken again.
        run(p, 6_550, 9_450, following(FollowingState.NORMAL, 25.0))
        val again = run(p, 9_500, 10_500, following(FollowingState.CRITICAL))
        assertEquals("Vehicle too close.", again.single().text)
    }

    @Test
    fun `TOO CLOSE is not spoken on stale perception or when not closing and not moving`() {
        val p = CuePolicy(catalog)
        run(p, 0, 1_000, following(FollowingState.NORMAL, 25.0))
        assertTrue((1_050L..3_000L step 50).flatMap { p.step(input(it, following(FollowingState.CRITICAL), stale = true)) }.none { it.cueId == CuePolicy.VTC })
        val p2 = CuePolicy(catalog)
        run(p2, 0, 1_000, following(FollowingState.NORMAL, 25.0))
        assertTrue("stopped behind a car: display only", run(p2, 1_050, 3_000, following(FollowingState.CRITICAL, rel = 0.0)).isEmpty())
    }

    @Test
    fun `stopped at a red light behind a car, the speed gate is on by default and nothing is spoken`() {
        // With the gate the Driving Context holds CRITICAL at CLOSE while the route speed is below it (no TOO CLOSE pill).
        assertTrue(AppSettings().gateCriticalBySpeed)
        val p = CuePolicy(catalog)
        run(p, 0, 1_000, following(FollowingState.NORMAL, 25.0))
        assertTrue(run(p, 1_050, 5_000, following(FollowingState.CLOSE, 6.7, rel = 0.0)).isEmpty())
    }

    /** A pedestrian in the path [d] m ahead, the route speed [v] (null = unknown), the speed gate [gate] (null = off). */
    private fun pedestrian(now: Long, d: Double?, v: Double?, gate: Double? = 1.5) = PolicyInput(
        nowMs = now,
        context = DrivingContext(
            pedestriansInPath = listOfNotNull(d?.let { PedestrianInfo(trackId = 4, distanceMeters = it, ttcSeconds = null) }),
            navigation = NavigationState(Maneuver.FOLLOW_ROAD, 500.0, egoSpeedMps = v),
        ),
        world = WorldSnapshot.EMPTY, linkConnected = true, takenOver = false, simPaused = false, hostVisible = true, route = null,
        routeDistanceMeters = null, live = false, gpsAccuracyMeters = null, speedGateMps = gate,
    )

    private fun peds(p: CuePolicy, from: Long, to: Long, d: Double?, v: Double?, gate: Double? = 1.5): List<CueRequest> =
        (from..to step 50).flatMap { p.step(pedestrian(it, d, v, gate)) }.filter { it.cueId == CuePolicy.PED_CRIT || it.cueId == CuePolicy.PED_ALERT }

    @Test
    fun `no pedestrian cue while stopped with the speed gate on, as before when moving or with an unknown speed`() {
        // People crossing 8 m in front of a car waiting at a light (0 m/s, gate 1.5 m/s): neither cue.
        assertTrue(peds(CuePolicy(catalog), 0, 5_000, 8.0, 0.0).isEmpty())
        assertTrue("alert range too", peds(CuePolicy(catalog), 0, 5_000, 20.0, 1.2).isEmpty())
        for ((name, v, gate) in listOf(Triple("moving", 5.0, 1.5), Triple("unknown speed", null, 1.5), Triple("gate off", 0.0, null))) {
            val crit = peds(CuePolicy(catalog), 0, 3_000, 8.0, v, gate)
            assertEquals(name, listOf("Pedestrian very close."), crit.map { it.text })
            assertEquals(name, listOf("Pedestrian ahead."), peds(CuePolicy(catalog), 0, 3_000, 20.0, v, gate).map { it.text })
        }
        // Driving off with the pedestrian still in the path: the persistence starts over, then the cue.
        val p = CuePolicy(catalog)
        assertTrue(peds(p, 0, 2_000, 8.0, 0.4).isEmpty())
        val off = peds(p, 2_050, 3_000, 8.0, 3.0)
        assertEquals(1, off.size)
        assertTrue(off.single().createdMs >= 2_050 + 200)
        // The episode went on while stopped: stopping and driving off again with the same pedestrian repeats nothing.
        val a = CuePolicy(catalog)
        assertEquals(1, peds(a, 0, 2_000, 20.0, 5.0).size)
        assertTrue(peds(a, 2_050, 6_000, 20.0, 0.0).isEmpty())
        assertTrue(peds(a, 6_050, 9_000, 20.0, 5.0).isEmpty())
    }

    @Test
    fun `navigation prompts come from phase1's maneuver with catalog phrases`() {
        val r = RouteGuide(
            maneuver = Maneuver.TURN_RIGHT, action = "TURN_RIGHT", turnDirection = "right", distanceMeters = 300.0, spatialType = "TURN_ARROW",
            anchorAheadMeters = 300.0, roadName = "Mock Street", exitNumber = null, destination = null, etaSeconds = null, remainingMeters = null,
            offRoute = false, speedMps = 8.0, provider = "mock", stale = false, ptsSeconds = null, receivedAtNs = 0L, routeKey = "r1", eventKey = "r1/step_2",
        )
        val p = CuePolicy(catalog)
        val normal = following(FollowingState.NORMAL, 30.0)
        val texts = ArrayList<String>()
        var d = 290.0
        var t = 0L
        while (d > 5.0) {
            p.step(input(t, normal, route = r, d = d)).mapNotNullTo(texts) { it.text }
            t += 50; d -= 0.4
        }
        assertEquals(listOf("Starting route to your destination.", "In nine hundred feet, turn right.", "Turn right."), texts)
    }

    @Test
    fun `spoken distances use words and the catalog's rounding`() {
        assertNull(SpokenText.distance(20.0))
        assertEquals("three hundred feet", SpokenText.distance(91.0))
        assertEquals("one thousand feet", SpokenText.distance(300.0))
        assertEquals("a quarter mile", SpokenText.distance(400.0))
        assertEquals("half a mile", SpokenText.distance(805.0))
        assertEquals("one and a half miles", SpokenText.distance(2_400.0))
        assertEquals("twenty-five miles", SpokenText.distance(25 * 1609.344))
    }

    private fun req(id: String, p: Priority, t: Long, text: String? = "x.") = CueRequest(id, "$id#$t", p, text, null, "nav", t, 3_000)

    @Test
    fun `arbiter plays one at a time and CRITICAL cuts navigation`() {
        val a = VoiceArbiter()
        val nav = req("nav.prepare", Priority.UPCOMING_NAVIGATION, 0)
        a.offer(nav, 0)
        val start = a.tick(1_000, { true }, { true })
        assertEquals(nav, (start.single() as VoiceArbiter.Decision.Play).request)
        a.started(nav, 1_000, 2_000)
        val crit = req(CuePolicy.VTC, Priority.CRITICAL_SAFETY, 1_200)
        val cut = a.offer(crit, 1_200)
        assertTrue(cut.any { it is VoiceArbiter.Decision.Cut && it.request == nav })
        val next = a.tick(1_210, { true }, { true })
        assertEquals(crit, (next.single() as VoiceArbiter.Decision.Play).request)
        // A late nav request behind it waits for the gap, then its TTL drops it.
        a.offer(req("nav.immediate", Priority.IMMEDIATE_NAVIGATION, 1_300), 1_300)
        a.finished(1_500)
        assertTrue(a.tick(1_600, { true }, { true }).isEmpty())
        val dropped = a.tick(5_000, { true }, { true })
        assertTrue(dropped.single() is VoiceArbiter.Decision.Drop)
    }

    @Test
    fun `the app uplinks with the SDC1 tag the server accepts`() {
        val header = UplinkHeader(1, 342385412986900L, 0).encode()
        assertEquals(24, header.size)
        assertEquals("SDC1", String(header, 0, 4, Charsets.US_ASCII))
    }
}
