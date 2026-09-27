package com.drivingassist.spatialcopilot.voice

import com.drivingassist.copilot.context.DrivingContext
import com.drivingassist.copilot.context.Maneuver
import com.drivingassist.copilot.context.Priority
import com.drivingassist.copilot.context.WorldSnapshot
import com.drivingassist.spatialcopilot.nav.RouteGuide
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** nav.continue ("Drive straight for ..."), spoken exit numbers, and the word budgets of every phrase. */
class VoiceNavPhrasesTest {
    private val json = File("../../perception_engine/docs/audio/audio_cues.v1.json").readText()
    private val catalog = CueCatalog.parse(json)

    private fun route(maneuver: Maneuver, eventKey: String, speed: Double, exitNumber: String? = null) = RouteGuide(
        maneuver = maneuver, action = maneuver.name, turnDirection = null, distanceMeters = null, spatialType = null,
        anchorAheadMeters = null, roadName = null, exitNumber = exitNumber, destination = null, etaSeconds = null, remainingMeters = null,
        offRoute = false, speedMps = speed, provider = "mock", stale = false, ptsSeconds = null, receivedAtNs = 0L, routeKey = "r1", eventKey = eventKey,
    )

    /**
     * Steps [p] every 50 ms over [from, to] while the car closes at the route's speed from [d0] (at [from]);
     * returns every request, each checked against the speech regex and its word budget.
     */
    private fun run(p: CuePolicy, r: RouteGuide, from: Long, to: Long, d0: Double, live: Boolean = false, accuracy: Double? = null): List<CueRequest> =
        (from..to step 50).flatMap { t ->
            val d = d0 - r.speedMps!! * (t - from) / 1000.0
            if (d < 5.0) return@flatMap emptyList()
            p.step(PolicyInput(
                nowMs = t, context = DrivingContext(), world = WorldSnapshot.EMPTY, linkConnected = true, takenOver = false,
                simPaused = false, hostVisible = true, route = r, routeDistanceMeters = d, live = live, gpsAccuracyMeters = accuracy,
            ))
        }.onEach { assertTrue("speakable: ${it.text}", it.text != null && catalog.fits(it.text!!, it.priority)) }

    @Test
    fun `exit numbers are spoken as words, anything else is not`() {
        assertEquals("ninety-four", SpokenText.exitNumberWords("94"))
        assertEquals("twenty-three B", SpokenText.exitNumberWords("23B"))
        assertEquals("seven A", SpokenText.exitNumberWords("7a"))
        assertEquals("two fifty", SpokenText.exitNumberWords("250"))
        assertEquals("one oh five", SpokenText.exitNumberWords("105"))
        assertEquals("three hundred", SpokenText.exitNumberWords("300"))
        assertEquals("one ten", SpokenText.exitNumberWords("110"))
        assertEquals("nine ninety-nine C", SpokenText.exitNumberWords("999C"))
        assertEquals("twelve", SpokenText.exitNumberWords(" 12 "))
        for (bad in listOf("1000", "0", "12-14", "I-85", "94AB", "", null)) assertNull("$bad", SpokenText.exitNumberWords(bad))
        assertEquals("take exit ninety-four", SpokenText.exit("94"))
        assertEquals("take the exit", SpokenText.exit("1000"))
        assertEquals("take the exit", SpokenText.exit(null))
    }

    @Test
    fun `an exit says its number in the prepare and immediate prompts`() {
        fun texts(r: RouteGuide, d0: Double) = run(CuePolicy(catalog), r, 0, 60_000, d0).map { it.text }
        val exit = route(Maneuver.EXIT, "r1/exit", 22.0, exitNumber = "94")
        assertEquals(listOf("Starting route to your destination.", "In half a mile, take exit ninety-four.", "Take exit ninety-four."), texts(exit, 830.0))
        // A Google ramp is KEEP_RIGHT with the number in its instruction: the same prompts.
        val ramp = route(Maneuver.KEEP_RIGHT, "r1/ramp", 22.0, exitNumber = "94")
        assertEquals(listOf("Starting route to your destination.", "In half a mile, take exit ninety-four.", "Take exit ninety-four."), texts(ramp, 830.0))
        val keep = route(Maneuver.KEEP_RIGHT, "r1/keep", 14.0)
        assertEquals(listOf("Starting route to your destination.", "In nine hundred feet, keep right.", "Keep right."), texts(keep, 290.0))
        val unnamed = route(Maneuver.EXIT, "r1/unnamed", 14.0, exitNumber = "I-85")
        assertEquals(listOf("Starting route to your destination.", "In nine hundred feet, take the exit.", "Take the exit."), texts(unnamed, 290.0))
        // The immediate prompt carries the banked form for when its own audio is not ready in time.
        val immediate = run(CuePolicy(catalog), exit, 0, 60_000, 830.0).single { it.cueId == CuePolicy.IMMEDIATE }
        assertEquals("Take the exit.", immediate.fallbackText)
        assertNull(run(CuePolicy(catalog), keep, 0, 60_000, 290.0).single { it.cueId == CuePolicy.IMMEDIATE }.fallbackText)
    }

    @Test
    fun `an uncertain LIVE position never falls back to the bare form`() {
        fun immediate(r: RouteGuide, d0: Double, accuracy: Double?) =
            run(CuePolicy(catalog), r, 0, 60_000, d0, live = true, accuracy = accuracy).single { it.cueId == CuePolicy.IMMEDIATE }
        val turn = route(Maneuver.TURN_RIGHT, "r1/turn", 14.0)
        for (accuracy in listOf(null, 35.0)) {
            val cue = immediate(turn, 290.0, accuracy)
            assertEquals("In three hundred feet, turn right.", cue.text)
            assertNull("accuracy $accuracy: waits for the distance sentence", cue.fallbackText)
        }
        val exit = route(Maneuver.EXIT, "r1/exit", 22.0, exitNumber = "94")
        assertNull(immediate(exit, 830.0, null).fallbackText)
        // A certain position (5 m) speaks the bare form, with the banked street-less one behind it.
        val certain = immediate(exit, 830.0, 5.0)
        assertEquals("Take exit ninety-four.", certain.text)
        assertEquals("Take the exit.", certain.fallbackText)
    }

    @Test
    fun `a sentence over its word budget drops the exit number`() {
        assertFalse(catalog.fits("In five hundred feet, take exit one oh five B.", Priority.IMMEDIATE_NAVIGATION))
        assertTrue(catalog.fits("In five hundred feet, take exit one oh five B.", Priority.UPCOMING_NAVIGATION))
        // LIVE with unknown GPS accuracy: the immediate prompt keeps its distance (ten words with the number).
        val r = route(Maneuver.EXIT, "r1/exit", 25.0, exitNumber = "105B")
        val texts = run(CuePolicy(catalog), r, 0, 1_000, 176.0, live = true).map { it.text }
        assertEquals(listOf("Starting route to your destination.", "In five hundred feet, take the exit."), texts)
    }

    @Test
    fun `drive straight is said once for each new far target`() {
        val p = CuePolicy(catalog)
        val far = run(p, route(Maneuver.TURN_RIGHT, "r1/a", 14.0), 0, 3_000, 5_000.0)
        assertEquals(listOf("Starting route to your destination.", "Drive straight for three miles."), far.map { it.text })
        val cont = far.single { it.cueId == CuePolicy.CONTINUE }
        assertEquals(500L, cont.createdMs)
        assertEquals(Priority.UPCOMING_NAVIGATION, cont.priority)
        // A near target: no continue (below max(60 s x v, one mile)), and no prompt yet.
        assertTrue(run(p, route(Maneuver.TURN_LEFT, "r1/b", 14.0), 3_050, 6_000, 1_000.0).isEmpty())
        // The destination far away qualifies too, 500 ms after the target settles.
        val arrive = run(p, route(Maneuver.ARRIVE, "r1/c", 14.0), 6_050, 9_000, 3_500.0)
        assertEquals("Drive straight for two miles.", arrive.single().text)
        assertEquals(6_550L, arrive.single().createdMs)
        // Nothing but road ahead, or a stop: no maneuver to drive to.
        assertTrue(run(p, route(Maneuver.FOLLOW_ROAD, "r1/d", 14.0), 9_050, 12_000, 8_000.0).isEmpty())
        assertTrue(run(p, route(Maneuver.STOP, "r1/e", 14.0), 12_050, 15_000, 8_000.0).isEmpty())
        // Dropped before it played: re-armed (said again once the target has settled again).
        p.onDropped(cont)
        val again = run(p, route(Maneuver.TURN_RIGHT, "r1/a", 14.0), 15_050, 16_000, 5_000.0).single()
        assertEquals("Drive straight for three miles.", again.text)
        assertEquals(15_550L, again.createdMs)
    }

    @Test
    fun `drive straight never shares a step with the prepare prompt`() {
        // 25 m/s: P(v) = one mile, so d = 1,627 m is inside both max(60 s x v, one mile) and the prepare window.
        val r = route(Maneuver.EXIT, "r1/exit", 25.0, exitNumber = "105B")
        val cues = run(CuePolicy(catalog), r, 0, 2_000, 1_640.0)
        assertEquals(listOf("Starting route to your destination.", "In one mile, take exit one oh five B."), cues.map { it.text })
        assertTrue(cues.none { it.cueId == CuePolicy.CONTINUE })
    }

    @Test
    fun `drive straight needs 30 s of travel before the prepare window`() {
        assertEquals(30.0, catalog.nav["continueMinGapSeconds"])
        // 25 m/s: P(v) = one mile. From 1,700 m the prepare prompt is 2 s away, so "Drive straight for one mile." is not said.
        val r = route(Maneuver.EXIT, "r1/exit", 25.0, exitNumber = "94")
        val near = run(CuePolicy(catalog), r, 0, 5_000, 1_700.0)
        assertEquals(listOf("Starting route to your destination.", "In one mile, take exit ninety-four."), near.map { it.text })
        // From 3,000 m it is 54 s away: said once, then the prepare prompt when it is due.
        val far = run(CuePolicy(catalog), r, 0, 60_000, 3_000.0).filter { it.cueId == CuePolicy.CONTINUE || it.cueId == CuePolicy.PREPARE }
        assertEquals(listOf("Drive straight for two miles.", "In one mile, take exit ninety-four."), far.map { it.text })
        assertTrue(far[1].createdMs - far[0].createdMs >= 30_000)
        // 14 m/s: the one-mile floor already leaves 92 s, so nothing changes there.
        val slow = run(CuePolicy(catalog), route(Maneuver.TURN_RIGHT, "r1/slow", 14.0), 0, 1_000, 1_650.0)
        assertEquals("Drive straight for one mile.", slow.single { it.cueId == CuePolicy.CONTINUE }.text)
    }

    @Test
    fun `every fixed phrase matches the speech regex and its word budget`() {
        for (phrase in catalog.fixedPhrases) {
            val priority = catalog.cues.values.firstOrNull { phrase in it.texts.values }?.priority
                ?: if (phrase.startsWith("Speed limit")) catalog["info.speed_limit"].priority else Priority.IMMEDIATE_NAVIGATION
            assertTrue(phrase, catalog.fits(phrase, priority))
            assertFalse(phrase, phrase.any { it.isDigit() })
        }
        assertEquals(12, catalog.maxWordsByPriority[Priority.UPCOMING_NAVIGATION])
        assertEquals(9, catalog.maxWordsByPriority[Priority.IMMEDIATE_NAVIGATION])
        assertEquals(11, CueCatalog.words("Move to the right lane for the turn, check for cars."))
    }

    @Test
    fun `the catalog's templates and maneuver phrases are the ones SpokenText renders`() {
        assertEquals("Drive straight for {distance}.", catalog[CuePolicy.CONTINUE].text)
        val phrases = Json.parseToJsonElement(json).jsonObject["maneuverPhrases"] as JsonObject
        for ((name, entry) in phrases) {
            val o = entry.jsonObject
            val maneuver = o["maneuver"]!!.jsonPrimitive.content
            if (name == "EXIT") {
                assertEquals(SpokenText.exit("94"), maneuver.replace("{exitNumberWords}", "ninety-four"))
                assertEquals(SpokenText.exit(null), o["fallback"]!!.jsonPrimitive.content)
                assertEquals(SpokenText.maneuver(Maneuver.EXIT), o["fallback"]!!.jsonPrimitive.content)
            } else {
                assertEquals(name, maneuver, SpokenText.maneuver(Maneuver.valueOf(name)))
            }
        }
    }
}
