package com.drivingassist.spatialcopilot.voice

import com.drivingassist.copilot.context.DrivingContext
import com.drivingassist.copilot.context.LaneAction
import com.drivingassist.copilot.context.LaneGuidance
import com.drivingassist.copilot.context.LaneLayout
import com.drivingassist.copilot.context.LaneLine
import com.drivingassist.copilot.context.LanesState
import com.drivingassist.copilot.context.Maneuver
import com.drivingassist.copilot.context.NavigationState
import com.drivingassist.copilot.context.Priority
import com.drivingassist.copilot.context.WorldSnapshot
import com.drivingassist.copilot.perception.Lanes
import com.drivingassist.spatialcopilot.nav.RouteGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** lane.change_left / lane.change_right (AUDIO_CUE_RULES.md 6.5) on 50 ms steps at 14 m/s, so I(v) = 98 m. */
class VoiceLaneTest {
    private val json = File("../../perception_engine/docs/audio/audio_cues.v1.json").readText()
    private val catalog = CueCatalog.parse(json)

    private fun route(
        maneuver: Maneuver = Maneuver.TURN_RIGHT, exitNumber: String? = null, eventKey: String = "r1/step_2", offRoute: Boolean = false,
        stale: Boolean = false, speed: Double = SPEED,
    ) = RouteGuide(
        maneuver = maneuver, action = maneuver.name, turnDirection = null, distanceMeters = 280.0, spatialType = null,
        anchorAheadMeters = null, roadName = null, exitNumber = exitNumber, destination = null, etaSeconds = null, remainingMeters = null,
        offRoute = offRoute, speedMps = speed, provider = "mock", stale = stale, ptsSeconds = null, receivedAtNs = 0L, routeKey = "r1", eventKey = eventKey,
    )

    /**
     * Three lanes through a vanishing point right of the image center (a yawed phone), the car in lane 2: x = vpX
     * lies between line 1 (slope -0.3) and line 2 (slope 1.6) below the vanishing point.
     */
    private fun layout(quality: Double = 0.8, age: Double = 0.1) = LaneLayout(
        vpX = 780.0, vpY = 330.0, lines = listOf(LaneLine(-2.2), LaneLine(-0.3), LaneLine(1.6), LaneLine(3.4)),
        egoLane = 2, nearestRowY = 700.0, quality = quality, measuredPts = 0.0, ageSeconds = age,
    )

    /** The lane model's own run as the server sends it in the city: lane 1 of 1 whatever the lines show. */
    private fun serverLanes(confidence: Double) = LanesState(
        lanes = Lanes(currentLane = 1, laneCount = 1, confidence = confidence,
            laneBoundaries = listOf(listOf(listOf(300.0, 540.0), listOf(460.0, 260.0)), listOf(listOf(700.0, 540.0), listOf(500.0, 260.0)))),
        currentLane = 1, laneCount = 1, measuredPts = 0.0, ageSeconds = 0.1,
    )

    /** What one step sees; null [route] = the run's route, null [targets] = the edge lane on the action's side. */
    private data class Step(
        val action: LaneAction? = LaneAction.CHANGE_LANE_RIGHT,
        val targets: List<Int>? = null,
        val current: Int = 2,
        val layout: LaneLayout? = null,
        val noLayout: Boolean = false,
        val lanes: LanesState? = null,
        val stale: Boolean = false,
        val inferred: Boolean = true,
        val route: RouteGuide? = null,
    )

    private fun input(t: Long, d: Double, s: Step, r: RouteGuide) = PolicyInput(
        nowMs = t,
        context = DrivingContext(
            laneGuidance = s.action?.let {
                val targets = s.targets ?: listOf(if (it == LaneAction.CHANGE_LANE_LEFT) 1 else 3)
                LaneGuidance(it, currentLane = s.current, laneCount = 3, targetLanes = targets, lanesToMove = 1, priority = Priority.UPCOMING_NAVIGATION, text = "PREPARE TO MOVE")
            },
            navigation = NavigationState(Maneuver.TURN_RIGHT, d, laneHintInferred = s.inferred),
            perceptionStale = s.stale,
        ),
        world = WorldSnapshot(lanes = s.lanes, laneLayout = if (s.noLayout) null else s.layout ?: layout()),
        linkConnected = true, takenOver = false, simPaused = false, hostVisible = true,
        route = s.route ?: r, routeDistanceMeters = d, live = false, gpsAccuracyMeters = null,
    )

    /**
     * Steps [p] every 50 ms over [from, to] while the car closes at the route's speed (14 m/s by default) from [d0]
     * (at [from]); returns the lane requests, or every request with [all]. Every request of any cue must match the
     * speech regex and its word budget.
     */
    private fun run(
        p: CuePolicy, from: Long, to: Long, d0: Double = 280.0, r: RouteGuide = route(), all: Boolean = false, step: (Long) -> Step = { Step() },
    ): List<CueRequest> =
        (from..to step 50).flatMap { t ->
            val d = d0 - r.speedMps!! * (t - from) / 1000.0
            if (d < 5.0) return@flatMap emptyList()
            p.step(input(t, d, step(t), r)).onEach {
                assertTrue("speakable: ${it.text}", it.text == null || catalog.fits(it.text!!, it.priority))
            }
        }.filter { all || it.cueId == CuePolicy.LANE_LEFT || it.cueId == CuePolicy.LANE_RIGHT }

    @Test
    fun `catalog turns the lane cue on with the agreed numbers`() {
        for (id in listOf(CuePolicy.LANE_LEFT, CuePolicy.LANE_RIGHT)) {
            val spec = catalog[id]
            assertTrue(spec.enabledByDefault)
            assertEquals(Priority.UPCOMING_NAVIGATION, spec.priority)
            assertEquals(2_000.0, spec.numbers["evidenceWindowMs"])
            assertEquals(0.7, spec.numbers["evidenceShare"])
            assertEquals(500.0, spec.numbers["lastAskWithinMs"])
            assertEquals(1_000.0, spec.numbers["revalidateWindowMs"])
            assertEquals(null, spec.numbers["stableMs"])
            assertEquals(0.5, spec.numbers["minLayoutQuality"])
            assertEquals(null, spec.numbers["minConfidence"])
            assertEquals(1.0, spec.numbers["maxAgeSeconds"])
            assertEquals(3_000L, spec.ttlMs)
            assertEquals(true, spec.flags["allowInferredLaneSide"])
            assertEquals(true, spec.flags["neverCuts"])
            assertEquals(6, spec.texts.size)
            spec.texts.values.forEach { assertTrue("$it is a fixed phrase", it in catalog.fixedPhrases) }
        }
        assertFalse(catalog.fixedPhrases.any { it.endsWith(" lane for the turn.") || it.endsWith(" lane for the exit.") })
        val numbers = Regex("\\b(one|two|three|four|lanes)\\b")
        assertFalse("no lane numbers or counts", catalog.fixedPhrases.any { it.startsWith("Move") && numbers.containsMatchIn(it) })
    }

    @Test
    fun `fires once on a full evidence window on a fresh lane layout of good quality`() {
        val p = CuePolicy(catalog)
        // Up to 7 s (182 m): the cue still has room before the immediate prompt, so it stays valid.
        val cue = run(p, 0, 7_000).single()
        // The 2 s window is full at 2 s; the 500 ms of perception warm-up count as not asking: 31 of 40 steps ask.
        assertEquals(2_000L, cue.createdMs)
        assertEquals("Move to the right lane for the turn, check for cars.", cue.text)
        assertEquals(Priority.UPCOMING_NAVIGATION, cue.priority)
        assertEquals(3_000L, cue.ttlMs)
        assertTrue(p.stillValid(cue))
    }

    @Test
    fun `text names the turn, the exit, or neither`() {
        fun text(r: RouteGuide, action: LaneAction = LaneAction.CHANGE_LANE_RIGHT) =
            run(CuePolicy(catalog), 0, 3_000, r = r) { Step(action = action) }.single().text
        assertEquals("Move to the right lane for the exit, check for cars.", text(route(Maneuver.EXIT)))
        assertEquals("Move to the right lane for the exit, check for cars.", text(route(Maneuver.KEEP_RIGHT, exitNumber = "94")))
        assertEquals("Move to the right lane, check for cars.", text(route(Maneuver.KEEP_RIGHT)))
        assertEquals("Move to the right lane, check for cars.", text(route(Maneuver.MERGE)))
        assertEquals("Move to the left lane for the turn, check for cars.", text(route(Maneuver.TURN_LEFT), LaneAction.CHANGE_LANE_LEFT))
        assertEquals("Move to the left lane, check for cars.", text(route(Maneuver.ENTER_HIGHWAY), LaneAction.CHANGE_LANE_LEFT))
    }

    @Test
    fun `silent on a weak, old or missing lane layout, stale perception, off route, or once the immediate prompt is due`() {
        val cases = mapOf<String, Step>(
            "layout quality below 0.5" to Step(layout = layout(quality = 0.45)),
            "layout older than 1 s" to Step(layout = layout(age = 1.2)),
            "layout not stable (the bridge's hysteresis)" to Step(layout = layout().copy(stable = false)),
            "no layout" to Step(noLayout = true),
            "no layout, confident server lanes" to Step(noLayout = true, lanes = serverLanes(confidence = 0.9)),
            "stale perception" to Step(stale = true),
            "off route" to Step(route = route(offRoute = true)),
            "stale route" to Step(route = route(stale = true)),
            "keep lane" to Step(action = LaneAction.KEEP_LANE),
            "unknown lane action" to Step(action = LaneAction.UNKNOWN),
            "no lane guidance" to Step(action = null),
        )
        for ((name, s) in cases) assertTrue(name, run(CuePolicy(catalog), 0, 6_000) { s }.isEmpty())
        // From 120 m at 14 m/s, I(v) = 98 m is reached before the warm-up and the stable time are over.
        assertTrue("reaches I(v)", run(CuePolicy(catalog), 0, 6_000, d0 = 120.0).isEmpty())
        assertTrue("inside I(v)", run(CuePolicy(catalog), 0, 6_000, d0 = 95.0).isEmpty())
    }

    @Test
    fun `the lane model's own confidence and lane numbers do not gate the cue`() {
        // City recording: the server says lane 1 of 1 at confidence 0.3 while the fitted layout is good.
        val cue = run(CuePolicy(catalog), 0, 6_000) { Step(lanes = serverLanes(confidence = 0.3)) }.single()
        assertEquals(2_000L, cue.createdMs)
        assertEquals(CuePolicy.LANE_RIGHT, cue.cueId)
        // Exactly at the catalog minimum.
        assertEquals(1, run(CuePolicy(catalog), 0, 6_000) { Step(layout = layout(quality = 0.5)) }.size)
        // A stricter catalog number is read, not a constant.
        val strict = CueCatalog.parse(json.replace("\"minLayoutQuality\": 0.5", "\"minLayoutQuality\": 0.9"))
        assertTrue(run(CuePolicy(strict), 0, 6_000).isEmpty())
    }

    @Test
    fun `one weak step does not delay the cue, a side change needs its own share`() {
        val weak = run(CuePolicy(catalog), 0, 6_000) { t -> if (t == 1_500L) Step(layout = layout(quality = 0.3)) else Step() }
        assertEquals(2_000L, weak.single().createdMs)
        // Left until 1.5 s, then right: the right side reaches 28 of the 40 steps at 2.85 s; the left one never has 70 %.
        val flip = run(CuePolicy(catalog), 0, 6_000) { t -> Step(action = if (t < 1_500L) LaneAction.CHANGE_LANE_LEFT else LaneAction.CHANGE_LANE_RIGHT) }
        assertEquals(CuePolicy.LANE_RIGHT, flip.single().cueId)
        assertEquals(2_850L, flip.single().createdMs)
    }

    @Test
    fun `a flickering wrong-lane guidance fires, a single short blip does not`() {
        // Replay of a real drive (segment 011): the left lane drops out behind the A-pillar for 250 ms every second, so the
        // guidance flips to KEEP_LANE (the car looks like it is in the leftmost visible lane). No run reaches 1.5 s, but
        // 75 % of the steps ask for the left lane.
        fun flicker(t: Long) = t % 1_000 < 750
        val left = route(Maneuver.TURN_LEFT)
        val cue = run(CuePolicy(catalog), 0, 8_000, r = left) { t ->
            Step(action = if (flicker(t)) LaneAction.CHANGE_LANE_LEFT else LaneAction.KEEP_LANE)
        }.single()
        assertEquals("Move to the left lane for the turn, check for cars.", cue.text)
        assertEquals(2_350L, cue.createdMs)
        // The same with the layout unstable whenever the lane is hidden: 75 % stable is enough too.
        assertEquals(1, run(CuePolicy(catalog), 0, 8_000, r = left) { t ->
            if (flicker(t)) Step(action = LaneAction.CHANGE_LANE_LEFT) else Step(action = LaneAction.KEEP_LANE, layout = layout().copy(stable = false))
        }.size)
        // A layout unstable in 40 % of the steps is not enough, even when every step asks.
        assertTrue(run(CuePolicy(catalog), 0, 8_000, r = left) { t ->
            Step(action = LaneAction.CHANGE_LANE_LEFT, layout = if (t % 1_000 < 600) layout() else layout().copy(stable = false))
        }.isEmpty())
        // A single blip of 1 s (20 of 40 steps) or 300 ms never reaches 70 %.
        for (blip in listOf(1_000L until 2_000L, 3_000L until 3_300L)) {
            assertTrue("blip $blip", run(CuePolicy(catalog), 0, 8_000, r = left) { t ->
                Step(action = if (t in blip) LaneAction.CHANGE_LANE_LEFT else LaneAction.KEEP_LANE)
            }.isEmpty())
        }
    }

    @Test
    fun `the side must have been asked for within the last 500 ms`() {
        // The side asked for 3 s while the route was stale (the evidence counts, the current step's route gate blocks).
        fun staleUntil3s(t: Long, after: LaneAction) =
            if (t <= 3_000L) Step(route = route(stale = true)) else Step(action = after)
        assertEquals(3_050L, run(CuePolicy(catalog), 0, 6_000) { t -> staleUntil3s(t, LaneAction.CHANGE_LANE_RIGHT) }.single().createdMs)
        // Stale until 3.55 s, KEEP_LANE from 3.05 s: at 3.6 s 70 % of the window asked, but the last ask is 600 ms old.
        assertTrue(run(CuePolicy(catalog), 0, 6_000) { t ->
            when {
                t <= 3_000L -> Step(route = route(stale = true))
                t <= 3_550L -> Step(action = LaneAction.KEEP_LANE, route = route(stale = true))
                else -> Step(action = LaneAction.KEEP_LANE)
            }
        }.isEmpty())
    }

    @Test
    fun `an inferred side needs allowInferredLaneSide, a side from the route does not`() {
        val strict = CueCatalog.parse(json.replace("\"allowInferredLaneSide\": true", "\"allowInferredLaneSide\": false"))
        assertEquals(false, strict[CuePolicy.LANE_RIGHT].flags["allowInferredLaneSide"])
        assertTrue(run(CuePolicy(strict), 0, 6_000) { Step(inferred = true) }.isEmpty())
        assertEquals(1, run(CuePolicy(strict), 0, 6_000) { Step(inferred = false) }.size)
        // The shipped catalog allows it: Google routes only ever give an inferred side.
        assertEquals(1, run(CuePolicy(catalog), 0, 6_000) { Step(inferred = true) }.size)
    }

    @Test
    fun `a dropped cue is re-armed, a played one is not repeated for the same event key and side`() {
        val p = CuePolicy(catalog)
        val first = run(p, 0, 2_000).single()
        p.onDropped(first)
        val again = run(p, 2_050, 2_100, d0 = 280.0 - SPEED * 2.05)
        assertEquals(first.key, again.single().key)
        assertTrue("played: no repeat", run(p, 2_150, 6_000, d0 = 280.0 - SPEED * 2.15).isEmpty())
        // The next maneuver (new event key) is a new cue, after a full window of its own.
        val next = run(p, 6_050, 9_000, d0 = 900.0, r = route(eventKey = "r1/step_4"))
        assertEquals(8_050L, next.single().createdMs)
    }

    @Test
    fun `a queued cue is no longer valid once the lane is reached or the immediate prompt is due`() {
        val p = CuePolicy(catalog)
        val cue = run(p, 0, 2_000).single()
        // In the lane for 250 ms (a flicker): the side still has most of the last second.
        run(p, 2_050, 2_250, d0 = 250.0) { Step(action = LaneAction.KEEP_LANE) }
        assertTrue(p.stillValid(cue))
        // In the lane for 500 ms: half of the last second, not more.
        run(p, 2_300, 2_500, d0 = 247.0) { Step(action = LaneAction.KEEP_LANE) }
        assertFalse(p.stillValid(cue))
        run(p, 2_550, 3_050, d0 = 244.0)
        assertTrue(p.stillValid(cue))
        run(p, 3_100, 3_150, d0 = 90.0)
        assertFalse("inside I(v)", p.stillValid(cue))
        assertTrue("other cues are not re-validated here", p.stillValid(cue.copy(cueId = CuePolicy.PREPARE)))
    }

    @Test
    fun `a queued cue survives a short dip of the guidance to UNKNOWN, not a long one or a new event key`() {
        // The engine drops the lane numbers (action UNKNOWN) once the held layout is older than 1 s.
        val dip = Step(action = LaneAction.UNKNOWN, layout = layout(age = 1.2))
        val p = CuePolicy(catalog)
        val cue = run(p, 0, 2_000).single()
        run(p, 2_050, 2_350, d0 = 250.0) { dip }
        assertTrue("0.35 s of UNKNOWN", p.stillValid(cue))
        run(p, 2_400, 2_600, d0 = 245.0) { dip }
        assertFalse("0.6 s of UNKNOWN", p.stillValid(cue))
        // A weak layout while the guidance still asks: the side is what is re-validated.
        val q = CuePolicy(catalog)
        val queued = run(q, 0, 2_000).single()
        run(q, 2_050, 2_600, d0 = 250.0) { Step(layout = layout(quality = 0.45)) }
        assertTrue(q.stillValid(queued))
        // A new event key during the dip: no longer the same cue.
        val k = CuePolicy(catalog)
        val old = run(k, 0, 2_000).single()
        run(k, 2_050, 2_150, d0 = 250.0, r = route(eventKey = "r1/step_4")) { dip }
        assertFalse(k.stillValid(old))
    }

    @Test
    fun `a middle target lane says the side only, the edge lane is named`() {
        fun text(r: RouteGuide, s: Step) = run(CuePolicy(catalog), 0, 3_000, r = r) { s }.single().text
        // requiredLane 2 of 3 from lane 1 (a numeric lane from the route, not inferred): the target is not "the right lane".
        val middle = Step(targets = listOf(2), current = 1, inferred = false)
        assertEquals("Move right, check for cars.", text(route(Maneuver.KEEP_LEFT), middle))
        assertEquals("Move right for the turn, check for cars.", text(route(Maneuver.TURN_LEFT), middle))
        assertEquals("Move right for the exit, check for cars.", text(route(Maneuver.EXIT), middle))
        val middleLeft = Step(action = LaneAction.CHANGE_LANE_LEFT, targets = listOf(2), current = 3, inferred = false)
        assertEquals("Move left, check for cars.", text(route(Maneuver.KEEP_RIGHT), middleLeft))
        assertEquals("Move left for the turn, check for cars.", text(route(Maneuver.TURN_RIGHT), middleLeft))
        // The edge lane from the route (lanes 2-3 of 3, or lane 1), or a side inferred from the maneuver: named.
        assertEquals("Move to the right lane, check for cars.", text(route(Maneuver.KEEP_RIGHT), Step(targets = listOf(2, 3), current = 1, inferred = false)))
        assertEquals("Move to the left lane, check for cars.", text(route(Maneuver.KEEP_LEFT), Step(action = LaneAction.CHANGE_LANE_LEFT, targets = listOf(1), inferred = false)))
        assertEquals("Move to the right lane, check for cars.", text(route(Maneuver.KEEP_RIGHT), Step()))
        for (id in listOf(CuePolicy.LANE_LEFT, CuePolicy.LANE_RIGHT)) catalog[id].texts.values.forEach { assertTrue(it, it in catalog.fixedPhrases) }
    }

    @Test
    fun `the lane cue needs room to finish before the immediate prompt is due`() {
        // 14 m/s: I(v) = 98 m, and the 11-word turn text needs v x (11 / 2.6 + 0.2 + 1 s) = 76 m more, so d >= 174 m at 2 s.
        assertTrue("172 m at 2 s", run(CuePolicy(catalog), 0, 6_000, d0 = 200.0).isEmpty())
        assertEquals(2_000L, run(CuePolicy(catalog), 0, 6_000, d0 = 210.0).single().createdMs)
        // 30 m/s on a Google ramp (inferred side from 300 m): I(v) = 200 m leaves no room for the sentence.
        val ramp = route(Maneuver.KEEP_RIGHT, exitNumber = "94", speed = 30.0)
        assertTrue("highway ramp", run(CuePolicy(catalog), 0, 6_000, d0 = 300.0, r = ramp).isEmpty())
        // A queued cue that lost that room is no longer valid at start.
        val p = CuePolicy(catalog)
        val cue = run(p, 0, 2_000).single()
        run(p, 2_050, 2_100, d0 = 170.0)
        assertFalse(p.stillValid(cue))
    }

    @Test
    fun `the immediate prompt of the same event key cuts a playing lane cue`() {
        val cues = run(CuePolicy(catalog), 0, 14_000, all = true)
        val lane = cues.single { it.cueId == CuePolicy.LANE_RIGHT }
        val immediate = cues.single { it.cueId == CuePolicy.IMMEDIATE }.copy(createdMs = 3_800)
        assertEquals("r1/step_2", lane.eventKey)
        assertEquals("r1/step_2", immediate.eventKey)
        fun playing(): VoiceArbiter = VoiceArbiter().also { a ->
            a.offer(lane, 2_000)
            assertEquals(lane, (a.tick(2_000, { true }, { true }).single() as VoiceArbiter.Decision.Play).request)
            a.started(lane, 2_000, 4_400)
        }
        val a = playing()
        assertEquals(listOf<VoiceArbiter.Decision>(VoiceArbiter.Decision.Cut(lane)), a.offer(immediate, 3_800))
        assertTrue("the 700 ms gap", a.tick(4_450, { true }, { true }).isEmpty())
        assertEquals(immediate, (a.tick(4_500, { true }, { true }).single() as VoiceArbiter.Decision.Play).request)
        // Another event key's immediate prompt waits for the lane cue to end.
        val other = immediate.copy(key = "r1/step_4:immediate", eventKey = "r1/step_4")
        assertTrue(playing().offer(other, 3_800).none { it is VoiceArbiter.Decision.Cut })
    }

    @Test
    fun `at most three presentations per event key, the lane cue left out`() {
        // A far target: continue at 0.5 s, then prepare and immediate are still due, so no lane cue.
        val far = run(CuePolicy(catalog), 0, 125_000, d0 = 1_700.0, all = true)
        assertEquals(listOf(CuePolicy.CONTINUE, CuePolicy.PREPARE, CuePolicy.IMMEDIATE), far.filter { it.cueId != "nav.start" }.map { it.cueId })
        // A near target (no continue): prepare, the lane cue, immediate.
        val near = run(CuePolicy(catalog), 0, 20_000, d0 = 280.0, all = true)
        assertEquals(listOf(CuePolicy.PREPARE, CuePolicy.LANE_RIGHT, CuePolicy.IMMEDIATE), near.filter { it.cueId != "nav.start" }.map { it.cueId })
        // After the right-side cue, an overshoot to the left is not spoken (prepare + right + immediate due = 3).
        val p = CuePolicy(catalog)
        assertEquals(CuePolicy.LANE_RIGHT, run(p, 0, 3_000).single().cueId)
        assertTrue(run(p, 3_050, 7_000, d0 = 280.0 - SPEED * 3.05) { Step(action = LaneAction.CHANGE_LANE_LEFT) }.isEmpty())
    }

    private companion object {
        const val SPEED = 14.0
    }
}
