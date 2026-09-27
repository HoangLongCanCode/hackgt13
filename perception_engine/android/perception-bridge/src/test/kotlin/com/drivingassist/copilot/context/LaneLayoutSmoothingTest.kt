package com.drivingassist.copilot.context

import com.drivingassist.copilot.context.TestFrames.frame
import com.drivingassist.copilot.perception.Echo
import com.drivingassist.copilot.perception.Lanes
import com.drivingassist.copilot.perception.PerceptionUpdate
import com.drivingassist.copilot.perception.Road
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [WorldSnapshot.laneLayout] in the WorldModel: lines tracked across runs, smoothing, hold, reset. */
class LaneLayoutSmoothingTest {
    private var nowNs = 10_000_000_000L
    private val world = WorldModel(clockMs = { 0L }, clockNs = { nowNs })

    private val road = SyntheticRoad(yawDeg = 10.0)
    /** Middle lane of three. */
    private val middle = road.lanes(-5.25, -1.75, 1.75, 5.25)
    /** Left lane of three. */
    private val left = road.lanes(-1.75, 1.75, 5.25, 8.75)

    private fun at(seq: Long, lanes: Lanes?, pts: Double = seq / 10.0, session: String = "test", camera: SyntheticRoad = road) =
        world.update(frame(seq, pts, lanes = lanes, session = session).copy(camera = camera.camera))

    private fun key(s: WorldSnapshot) = s.laneLayout?.let { it.laneCount to it.egoLane }

    /** The lines [lateral] m right of the car's track (a road of 0.25 m steps is exact enough for the tracker). */
    private fun lines(vararg lateral: Double) = road.lanes(*lateral)

    @Test
    fun `a new line counts from its second sighting within 1 s`() {
        assertNull(at(0, middle).laneLayout, "the first run alone is not counted")
        assertEquals(3 to 2, key(at(1, middle)))
        val withFar = lines(-5.25, -1.75, 1.75, 5.25, 8.75)
        assertEquals(3 to 2, key(at(2, withFar)), "a line seen once does not count")
        assertEquals(3 to 2, key(at(3, middle)))
        assertEquals(4 to 2, key(at(4, withFar)), "seen again 0.2 s later: a fourth lane")
        // Sightings more than 1 s apart never count.
        val world2 = WorldModel(clockMs = { 0L }, clockNs = { nowNs })
        fun at2(seq: Long, lanes: Lanes) = world2.update(frame(seq, seq / 10.0, lanes = lanes).copy(camera = road.camera))
        at2(0, middle); at2(1, middle); at2(2, withFar)
        for (seq in 3L..13L) at2(seq, middle)
        assertEquals(3 to 2, key(at2(14, withFar)), "1.2 s after the first sighting")
        assertEquals(4 to 2, key(at2(15, withFar)))
    }

    @Test
    fun `the far-right line missed on every other run keeps the lane count`() {
        val missed = lines(-5.25, -1.75, 1.75)
        assertEquals(2 to 2, LaneLayout.from(road.snapshot(missed))!!.let { it.laneCount to it.egoLane }, "on its own that run has one lane less")
        at(0, middle); at(1, middle)
        for (seq in 2L..40L) {
            val s = at(seq, if (seq % 2 == 0L) missed else middle)
            assertEquals(3 to 2, key(s), "run $seq")
            assertEquals(0.0, s.laneLayout!!.ageSeconds, "the layout is refreshed by every run")
            assertEquals(4, s.laneLayout!!.lines.count { it.detected })
        }
    }

    @Test
    fun `a line gone for more than 2 s drops`() {
        at(0, middle); at(1, middle); at(2, middle) // far-right line last seen at 0.2 s
        val missed = lines(-5.25, -1.75, 1.75)
        for (seq in 3L..21L) assertEquals(3 to 2, key(at(seq, missed)), "run $seq: held")
        assertEquals(3 to 2, key(at(22, missed, pts = 2.15)), "unseen for 1.95 s")
        assertEquals(2 to 2, key(at(23, missed, pts = 2.25)), "unseen for 2.05 s")
        assertEquals(2 to 2, key(at(24, middle, pts = 2.35)), "back once: not counted yet")
        assertEquals(3 to 2, key(at(25, middle, pts = 2.45)))
    }

    @Test
    fun `a lane change moves the ego lane once, the tracks move with the lines`() {
        // Five lines on the road, the car moves 3.5 m right (one lane) at 0.7 m per run: more than the 0.6 m a line may
        // move on its own, so only the common shift of all lines keeps them on their tracks.
        val onRoad = doubleArrayOf(-5.25, -1.75, 1.75, 5.25, 8.75)
        fun seen(p: Double) = road.lanes(*onRoad.map { it - p }.toDoubleArray())
        at(0, seen(0.0)); assertEquals(4 to 2, key(at(1, seen(0.0))))
        val keys = ArrayList<Pair<Int, Int>?>()
        for ((k, p) in listOf(0.7, 1.4, 2.1, 2.8, 3.5, 3.5, 3.5).withIndex()) keys += key(at(2L + k, seen(p)))
        assertEquals<List<Pair<Int, Int>?>>(listOf(4 to 2, 4 to 2, 4 to 3, 4 to 3, 4 to 3, 4 to 3, 4 to 3), keys, "ego lane 2 -> 3 as the car crosses the line, no lane lost")
        val end = at(9, seen(3.5)).laneLayout!!
        val measured = LaneLayout.from(road.snapshot(seen(3.5)))!!
        assertEquals(measured.lines.size, end.lines.size)
        end.lines.zip(measured.lines).forEach { (tracked, line) -> assertEquals(line.slope, tracked.slope, 0.02, "the tracked lines are where the lines are") }
    }

    @Test
    fun `oncoming lines beyond the yellow line stay cut on a run without colours`() {
        val lanes = lines(-8.75, -5.25, -1.75, 1.75, 5.25)
        val colored = lanes.copy(boundaryColors = listOf("white", "white", "yellow", "white", "white"))
        at(0, colored)
        val a = at(1, colored).laneLayout!!
        assertEquals(2 to 1, a.laneCount to a.egoLane)
        assertEquals(LaneLine.YELLOW, a.lines.first().color)
        assertEquals(4 to 3, LaneLayout.from(road.snapshot(lanes))!!.let { it.laneCount to it.egoLane }, "that run on its own")
        val b = at(2, lanes).laneLayout!!
        assertEquals(2 to 1, b.laneCount to b.egoLane, "the tracked line is still yellow")
        assertEquals(0.0, b.ageSeconds)
        assertTrue(b.yellowLeftEdge)
    }

    @Test
    fun `a yellow line left out as a stray still cuts the oncoming lines`() {
        // Oncoming lane line, the far side of a painted median (yellow), our left line 1.5 m right of it, our right line.
        val median = lines(-6.8, -3.3, -1.8, 1.8).copy(boundaryColors = listOf("white", "yellow", "white", "white"))
        val direct = LaneLayout.from(road.snapshot(median))!!
        assertEquals(1 to 1, direct.laneCount to direct.egoLane)
        at(0, median)
        for (seq in 1L..6L) {
            val l = at(seq, median).laneLayout!!
            assertEquals(1 to 1, l.laneCount to l.egoLane, "run $seq: the yellow line is a stray from the third run on, the cut stays")
            assertTrue(l.yellowLeftEdge, "run $seq")
        }
    }

    @Test
    fun `a centre line read white for a long stretch cuts again after a few yellow readings`() {
        val five = lines(-8.75, -5.25, -1.75, 1.75, 5.25)
        val white = five.copy(boundaryColors = List(5) { "white" })
        val yellow = five.copy(boundaryColors = listOf("white", "white", "yellow", "white", "white"))
        for (seq in 0L..30L) at(seq, white)
        assertEquals(4 to 3, key(at(31, white)), "the oncoming lanes count as ours while the centre line reads white")
        assertEquals(4 to 3, key(at(32, yellow)), "one yellow reading after 32 white ones changes nothing")
        val keys = (33L..40L).map { key(at(it, yellow)) }
        assertTrue(keys.indexOf(2 to 1) in 0..4, "cut again within about five yellow readings, not 32: $keys")
        assertEquals(2 to 1, keys.last())
    }

    @Test
    fun `runs that see none of the layout's lines do not refresh it - it ages, stops being stable, is dropped`() {
        at(0, middle); at(1, middle)
        assertTrue(at(2, middle).laneLayout!!.stable)
        // Half a lane off (1.7 m): beyond the largest common shift, so every line starts a new track, and each new one lies
        // within 1.9 m of a line of the layout (a stray). The old layout is held, but not as fresh.
        val halfLane = lines(-3.55, -0.05, 3.45, 6.95)
        var s = at(3, halfLane)
        assertEquals(0.1, s.laneLayout!!.ageSeconds, 1e-9)
        for (seq in 4L..12L) s = at(seq, halfLane)
        assertEquals(1.0, s.laneLayout!!.ageSeconds, 1e-9)
        assertTrue(s.laneLayout!!.stable)
        s = at(13, halfLane)
        assertEquals(3 to 2, key(s))
        assertEquals(1.1, s.laneLayout!!.ageSeconds, 1e-9)
        assertFalse(s.laneLayout!!.stable, "older than 1 s: not stable")
        for (seq in 14L..17L) s = at(seq, halfLane)
        assertEquals(1.5, s.laneLayout!!.ageSeconds, 1e-9)
        assertNull(at(18, halfLane).laneLayout, "older than 1.5 s: dropped")
        // Once the old tracks are gone (2 s unseen) the new lines lay out, fresh, and are stable a run later.
        var seq = 19L
        var back: LaneLayout? = null
        while (back == null && seq <= 24L) back = at(seq++, halfLane).laneLayout
        assertNotNull(back, "the new lines lay out once the old tracks are dropped")
        assertTrue(seq - 1 >= 22L, "not before the old tracks are 2 s unseen: run ${seq - 1}")
        assertEquals(0.0, back.ageSeconds)
        assertTrue(at(seq, halfLane).laneLayout!!.stable)
    }

    @Test
    fun `stable - after two good runs, kept in the band, cleared below 0_35`() {
        fun q(lanes: Lanes) = LaneLayout.from(road.snapshot(lanes))!!.quality
        val fair = road.lanes(-5.25, -1.75, 1.75, 5.25, confidence = 0.0)
        val poor = Lanes(1, 1, listOf(-5.25, -1.75, 1.75, 5.25).map { road.line(it, from = 15.0) }, 0.6) // short lines, far away
        assertEquals(1.0, q(middle))
        assertEquals(0.4, q(fair), 1e-9)
        assertTrue(q(poor) < 0.35, "${q(poor)}")
        at(0, middle)
        assertFalse(at(1, middle).laneLayout!!.stable, "the first layout")
        assertTrue(at(2, middle).laneLayout!!.stable)
        var seq = 3L
        repeat(8) {
            val l = at(seq++, fair).laneLayout!!
            assertTrue(l.stable, "q ${l.quality}: at or above 0.35")
        }
        assertTrue(at(seq - 1, fair).laneLayout!!.quality < 0.45, "the smoothed quality is in the band now")
        var l = at(seq++, poor).laneLayout!!
        while (l.stable) {
            assertTrue(l.quality >= 0.35, "${l.quality}")
            l = at(seq++, poor).laneLayout!!
        }
        assertTrue(l.quality < 0.35, "${l.quality}")
        assertFalse(at(seq++, middle).laneLayout!!.stable, "back to good lines: one run is not enough")
        assertTrue(at(seq, middle).laneLayout!!.stable)
    }

    @Test
    fun `camera height - smoothed over runs, implausible readings hold it, no jump at the edge of the range`() {
        fun run(seq: Long, h: Double?) = world.update(frame(seq, seq / 10.0, lanes = middle).copy(camera = road.camera.copy(cameraHeightMeters = h)))
        run(0, 4.46)
        for (seq in 1L..10L) {
            val l = run(seq, 4.46).laneLayout!!
            assertEquals(1.25, l.cameraHeightMeters, "4.46 m is no measurement: the default holds")
            assertEquals(3 to 2, l.laneCount to l.egoLane)
        }
        assertEquals(1.25, run(11, null).laneLayout!!.cameraHeightMeters)
        // A reading hovering at the 2.0 m edge used to switch between 1.99 m and the 1.25 m default (lane widths x 1.6).
        var h = 1.25
        for (seq in 12L..31L) {
            val l = run(seq, if (seq % 2 == 0L) 1.99 else 2.01).laneLayout!!
            val next = l.cameraHeightMeters!!
            assertTrue(next >= h - 1e-9 && next - h <= 0.04, "run $seq: $h -> $next")
            assertTrue(next in 1.0..2.0)
            assertEquals(3 to 2, l.laneCount to l.egoLane, "run $seq at $next m")
            h = next
        }
        assertTrue(h > 1.4, "it moves towards the plausible reading: $h")
    }

    @Test
    fun `the vanishing point is smoothed, the tracked lines follow it`() {
        at(0, middle); val first = at(1, middle).laneLayout!!
        val turned = SyntheticRoad(yawDeg = 12.0)
        val raw = LaneLayout.from(turned.snapshot(turned.lanes(-5.25, -1.75, 1.75, 5.25)))!!
        assertTrue(raw.vpX - first.vpX > 20, "the road turned: ${raw.vpX} vs ${first.vpX}")
        val s = at(2, turned.lanes(-5.25, -1.75, 1.75, 5.25), camera = turned).laneLayout!!
        assertEquals(first.vpX + 0.5 * (raw.vpX - first.vpX), s.vpX, 0.11, "EMA, weight 0.5")
        for (i in s.lines.indices) assertEquals(first.lines[i].slope + 0.5 * (raw.lines[i].slope - first.lines[i].slope), s.lines[i].slope, 1e-3)
        assertEquals(2.0, s.measuredPts / 0.1, 1e-9)
    }

    @Test
    fun `held up to 1_5 s when runs give no layout, then dropped`() {
        at(0, middle); at(1, middle)
        var s = at(2, Lanes(1, 1, emptyList(), 0.9))
        assertEquals(3 to 2, key(s), "a run without lines keeps the layout")
        assertEquals(0.1, s.laneLayout!!.ageSeconds)
        s = at(3, null, pts = 1.0)
        assertEquals(0.9, s.laneLayout!!.ageSeconds, "no lanes block at all: held, ageing")
        s = at(4, null, pts = 1.55)
        assertEquals(1.45, s.laneLayout!!.ageSeconds)
        assertNull(at(5, null, pts = 1.65).laneLayout, "older than 1.5 s")
    }

    @Test
    fun `a new session clears the layout`() {
        at(0, middle); assertNotNull(at(1, middle).laneLayout)
        assertNull(at(0, middle, pts = 0.0, session = "other").laneLayout)
        assertNull(at(1, null, pts = 0.1, session = "other").laneLayout, "the old session's runs do not count")
    }

    @Test
    fun `wave-2 lanes runs with the road vanishing point, and the field survives prediction`() {
        val echo = { id: Long -> Echo(id, nowNs) }
        world.update(frame(0, 0.0).copy(schemaVersion = 2, wave = 1, echo = echo(0), camera = road.camera), receivedAtNs = nowNs)
        val roadBlock = Road(0.3, horizonY = road.vpY, vanishingPoint = listOf(road.vpX, road.vpY))
        fun wave2(seq: Long, pts: Double) = world.applyUpdate(PerceptionUpdate(seq = seq, frameIndex = seq, ptsSeconds = pts, lanes = middle, road = roadBlock, blocks = listOf("lanes")), receivedAtNs = nowNs)
        assertNull(wave2(0, 0.0).laneLayout)
        world.update(frame(1, 0.1).copy(schemaVersion = 2, wave = 1, echo = echo(1), camera = road.camera), receivedAtNs = nowNs)
        val s = wave2(1, 0.1)
        val layout = assertNotNull(s.laneLayout)
        assertEquals(3 to 2, layout.laneCount to layout.egoLane)
        assertEquals(Math.round(road.vpX * 10) / 10.0, layout.vpX, 1e-9)
        // A repeated update of the same run (dedupe) is not a new run.
        assertEquals(layout, world.applyUpdate(PerceptionUpdate(seq = 1, frameIndex = 1, ptsSeconds = 0.1, lanes = left, road = roadBlock), receivedAtNs = nowNs).laneLayout)

        assertEquals(layout, s.predictedAt(nowNs + 50_000_000L).laneLayout, "prediction keeps the layout")
        assertNull(s.navigationOnly().laneLayout, "stale: no lanes")
    }
}
