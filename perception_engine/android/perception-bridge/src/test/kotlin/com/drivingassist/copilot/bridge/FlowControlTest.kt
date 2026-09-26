package com.drivingassist.copilot.bridge

import com.drivingassist.copilot.context.FrameTiming
import com.drivingassist.copilot.context.WorldSnapshot
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlowControlTest {
    private val ms = 1_000_000L

    @Test
    fun `credit gate never exceeds maxInFlight and returns credits on answer or timeout`() {
        val gate = CreditGate(maxInFlight = 2, timeoutNs = 1000 * ms)
        assertTrue(gate.tryAcquire(0, 0))
        assertTrue(gate.tryAcquire(1, 10 * ms))
        assertFalse(gate.tryAcquire(2, 20 * ms), "third frame must be dropped, not queued")
        assertEquals(0, gate.available)
        assertTrue(gate.release(0), "wave-1 frame with echo.frameId 0")
        assertFalse(gate.release(0), "second answer for the same frame is ignored")
        assertTrue(gate.tryAcquire(2, 30 * ms))
        assertTrue(gate.release(1), "perception.skip for frame 1")
        assertEquals(1, gate.inFlightCount)
        assertEquals(0, gate.sweep(999 * ms), "frame 2 sent at 30 ms: not expired yet")
        assertEquals(1, gate.sweep(1030 * ms), "lost answer: credit comes back after the timeout")
        assertEquals(2, gate.available)
        assertFalse(gate.release(2), "late answer after the timeout")
    }

    @Test
    fun `credit gate follows the server's maxInFlight and resets on disconnect`() {
        val gate = CreditGate(maxInFlight = 2, timeoutNs = 1000 * ms)
        gate.maxInFlight = 1
        assertTrue(gate.tryAcquire(0, 0))
        assertFalse(gate.tryAcquire(1, 0))
        gate.reset()
        assertEquals(1, gate.available)
        gate.maxInFlight = 0
        assertEquals(1, gate.maxInFlight, "at least one frame in flight")
    }

    private fun snap(pts: Double, seq: Long = Math.round(pts * 10)) = WorldSnapshot(
        timing = FrameTiming("s", seq, seq, pts, 0, 0, 0.0, 0.0, 0.0, null, null, 0, 0),
    )

    @Test
    fun `pts buffer picks the newest result at or before playback within the lag`() {
        val buf = PtsResultBuffer()
        listOf(0.0, 0.1, 0.2, 0.3, 0.4).forEach { buf.put(snap(it)) }
        assertEquals(0.2, buf.select(0.2, 0.15)!!.ptsSeconds, "exact")
        assertEquals(0.2, buf.select(0.29, 0.15)!!.ptsSeconds, "never a result from the future")
        assertEquals(0.4, buf.select(0.5, 0.15)!!.ptsSeconds, "100 ms old is fine")
        assertNull(buf.select(0.6, 0.15), "200 ms old: too late for this frame")
        assertNull(buf.select(-0.05, 0.15), "nothing before the first result")
        // A wave-2 merge re-publishes the same pts: it replaces the entry.
        buf.put(snap(0.4).copy(revision = 9))
        assertEquals(9, buf.select(0.45, 0.15)!!.revision)
        assertEquals(5, buf.size)
        buf.trimBefore(0.25)
        assertEquals(listOf(0.3, 0.4), listOf(buf.select(0.3, 0.0)!!.ptsSeconds, buf.newestPts))
        buf.put(WorldSnapshot.EMPTY)
        assertEquals(2, buf.size, "snapshots without timing are ignored")
    }

    @Test
    fun `playback clock extrapolates between reports and detects seeks`() {
        val clock = PlaybackClock()
        assertNull(clock.estimate(0))
        assertFalse(clock.update("a", 10.0, playing = true, rate = 1.0, nowNs = 0), "first report is not a seek")
        assertEquals(10.5, clock.estimate(500 * ms)!!, 1e-9)
        assertFalse(clock.update("a", 10.1, true, 1.0, 100 * ms), "normal 10 Hz report")
        assertTrue(clock.update("a", 30.0, true, 1.0, 200 * ms), "jump = seek")
        assertFalse(clock.update("a", 30.0, false, 1.0, 300 * ms), "pause at the extrapolated position")
        assertEquals(30.0, clock.estimate(5_000 * ms)!!, 1e-9, "paused: position does not move")
        assertTrue(clock.update("b", 30.0, false, 1.0, 400 * ms), "another clip")
        assertFalse(clock.update("b", 30.0, true, 2.0, 500 * ms))
        assertEquals(31.0, clock.estimate(1_000 * ms)!!, 1e-9, "rate 2")
    }

    @Test
    fun `playback clock uses the server seek rule and treats rate 0 as paused`() {
        val clock = PlaybackClock()
        assertEquals(0.6, PlaybackClock.SEEK_THRESHOLD_SECONDS, "same as server.py SEEK_THRESHOLD_S")
        clock.update("a", 10.0, playing = true, rate = 1.0, nowNs = 0)
        assertFalse(clock.update("a", 10.65, true, 1.0, 100 * ms), "0.55 s off at rate 1: not a seek (server keeps its session)")
        assertTrue(clock.update("a", 11.5, true, 1.0, 200 * ms), "0.75 s off at rate 1: seek")
        clock.update("a", 20.0, true, 4.0, 1_000 * ms)
        // Expected 20.4 at +100 ms: 2 s off is a seek only above 0.6 x 4 = 2.4 s at rate 4.
        assertFalse(clock.update("a", 22.4, true, 4.0, 1_100 * ms), "2.0 s off at rate 4: not a seek")
        assertTrue(clock.update("a", 30.0, true, 4.0, 1_200 * ms), "7.2 s off at rate 4: seek")
        clock.update("a", 40.0, playing = true, rate = 0.0, nowNs = 2_000 * ms)
        assertEquals(40.0, clock.estimate(4_000 * ms)!!, 1e-9, "rate 0 while 'playing': frozen, like the server")
    }

    @Test
    fun `rolling window percentiles and rate meter`() {
        val w = RollingWindow(capacity = 5)
        assertNull(w.percentile(50.0))
        listOf(100.0, 10.0, 20.0, 30.0, 40.0, 50.0).forEach(w::add) // 100 falls out
        assertEquals(30.0, w.percentile(50.0))
        assertEquals(10.0, w.percentile(0.0))
        assertEquals(50.0, w.percentile(100.0))
        val r = RateMeter(windowNs = 1_000 * ms)
        assertNull(r.rate(0))
        for (i in 0 until 30) r.mark(i * 50 * ms) // 20 Hz for 1.5 s
        assertEquals(20.0, r.rate(1_450 * ms)!!, 1.1)
        assertEquals(0.0, r.rate(10_000 * ms)!!, "stream stopped")
    }
}
