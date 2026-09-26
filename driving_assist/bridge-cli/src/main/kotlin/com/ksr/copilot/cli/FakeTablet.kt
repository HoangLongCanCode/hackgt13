package com.ksr.copilot.cli

import com.ksr.copilot.bridge.PerceptionBridge
import com.ksr.copilot.context.DrivingEvent
import com.ksr.copilot.context.WorldSnapshot
import com.ksr.copilot.perception.ClientCamera
import com.ksr.copilot.perception.ClientHello
import com.ksr.copilot.perception.ClientTripState
import com.ksr.copilot.perception.DeviceInfo
import com.ksr.copilot.perception.NavigationHint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

/**
 * The JVM stand-in for the Galaxy Tab S9 app: it drives [PerceptionBridge] exactly as the Android
 * app does (same library, same calls), so the laptop server, the phase1 relay and the bridge can be
 * tested end to end without the tablet.
 */
internal object FakeTablet {
    private val device = DeviceInfo("ksr", "fake-tablet-jvm", "jvm-" + System.getProperty("java.version"))
    private val lenient = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private fun hint(o: Options, default: String): NavigationHint = NavigationHint(o.navHint ?: default)

    /** Common harness: bridge + event collection + navigation feeds + print loop + optional dump. */
    private fun run(
        o: Options,
        hello: ClientHello,
        driver: suspend CoroutineScope.(PerceptionBridge, Long) -> Unit,
        header: (PerceptionBridge, Long) -> String,
        shown: (PerceptionBridge, Long) -> WorldSnapshot?,
        /** --nav-stub only: metres travelled (bridge, nowNs, startNs). */
        travelled: (PerceptionBridge, Long, Long) -> Double,
        finished: () -> Boolean = { false },
    ) {
        println("KSR fake tablet - ${hello.mode.wire} mode -> ${o.url} (client ${hello.clientId}, navigation hint ${hello.navigation?.mode})")
        runBlocking {
            val bridge = PerceptionBridge(o.url, this)
            val events = ConcurrentLinkedQueue<DrivingEvent>()
            // Collect before connecting: events has no replay.
            val eventsJob = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { bridge.events.collect { events.add(it) } }
            bridge.connect(hello)
            val startNs = System.nanoTime()
            val driverJob = launch(Dispatchers.Default) { driver(bridge, startNs) }
            val tripJob = o.tripStates?.let { f -> launch(Dispatchers.Default) { replayTripStates(o, f, bridge) } }
            val stubJob = if (o.navStub) launch(Dispatchers.Default) { navStubLoop(o, bridge) { travelled(bridge, System.nanoTime(), startNs) } } else null
            var dumped = false
            var finishedAtNs: Long? = null
            while (true) {
                delay(o.intervalMs)
                val now = System.nanoTime()
                val s = shown(bridge, now)
                val ctx = bridge.context.value
                print(header(bridge, now) + "\n" + worldLines(s, ctx, bridge.navigation.value, now, events))
                val elapsed = (now - startNs) / 1e9
                if (o.dump && !dumped && s != null && s.objects.isNotEmpty() && s.ptsSeconds >= (o.dumpAt ?: 3.0)) {
                    println(dumpJson(s, ctx, bridge.navigation.value, bridge.navigationState.value, bridge.link.value, "WorldSnapshot + DrivingContext + navigation + LinkStatus (seq ${s.seq})"))
                    dumped = true
                    if (o.seconds == null) break
                }
                if (finished() && finishedAtNs == null) finishedAtNs = now
                val doneFrames = finishedAtNs?.let { now - it > 1_500_000_000L } ?: false
                if ((o.seconds != null && elapsed >= o.seconds) || doneFrames) {
                    if (o.dump && !dumped) println(dumpJson(s, ctx, bridge.navigation.value, bridge.navigationState.value, bridge.link.value, "WorldSnapshot + DrivingContext + navigation + LinkStatus (at exit)"))
                    break
                }
            }
            listOfNotNull(driverJob, eventsJob, tripJob, stubJob).forEach { it.cancel() }
            bridge.close()
        }
    }

    // ------------------------------------------------------------------------------------ live

    fun live(o: Options) {
        val dir = o.frames ?: fail("live needs --frames DIR (see perception_engine/scripts/extract_frames.py)")
        val frames = dir.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg") }?.sortedBy { it.name }.orEmpty()
        if (frames.isEmpty()) fail("no .jpg frames in $dir (run perception_engine/scripts/extract_frames.py first)")
        val meta = runCatching { Json.parseToJsonElement(File(dir, "meta.json").readText()).jsonObject }.getOrNull()
        val fps = o.fps ?: meta?.get("fps")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 15.0
        val (w, h) = JpegInfo.size(frames.first().readBytes()) ?: (960 to 540)
        // Intrinsics describe the UPRIGHT image (after the uplink rotation).
        val (uw, uh) = if (o.rotation % 180 == 0) w to h else h to w
        val camera = ClientCamera(
            imageWidth = uw, imageHeight = uh, focalPx = o.focalPx, principalPoint = null,
            mountHeightMeters = o.mountHeight, pitchDegrees = null, lensFacing = "back", stabilization = false,
        )
        val limit = o.maxFrames ?: Int.MAX_VALUE
        println("uplink: ${frames.size} frames ${w}x$h from $dir at ${f1(fps)} fps${if (o.loop) " (looping)" else ""}")
        val sentAll = AtomicBoolean(false)
        run(
            o, ClientHello.live(o.clientId, camera, device, hint(o, "live")),
            driver = { bridge, startNs ->
                val periodNs = 1e9 / fps
                var i = 0
                while (isActive) {
                    if (i >= limit || (!o.loop && i >= frames.size)) { sentAll.set(true); break }
                    val jpeg = frames[i % frames.size].readBytes() // read before the due time
                    val due = startNs + (i * periodNs).toLong()
                    val waitMs = (due - System.nanoTime()) / 1_000_000
                    if (waitMs > 0) delay(waitMs)
                    // Exactly what the tablet analyzer does: capture time on the bridge clock, drop if no credit.
                    bridge.offerCameraFrame(jpeg, System.nanoTime(), o.rotation)
                    i++
                }
            },
            header = { bridge, now ->
                val k = bridge.link.value
                val last = bridge.world.value.timing?.receivedAtNs?.let { f0((now - it) / 1e6) + " ms ago" } ?: "none"
                "---- LIVE t=${f1(bridge.world.value.ptsSeconds)}s up ${k.uplinkFps?.let(::f1) ?: "-"} fps (sent ${k.framesSent}/${k.framesOffered})" +
                    " | cap->res p50 ${k.captureToResultMsP50?.let(::f0) ?: "-"} ms p95 ${k.captureToResultMsP95?.let(::f0) ?: "-"}" +
                    " | credits ${k.credits}/${k.maxInFlight} drop(no credit) ${k.framesDroppedNoCredit} not-ready ${k.framesDroppedNotReady}" +
                    " skip ${k.skips} timeout ${k.creditTimeouts} | last result $last\n     " + linkSummary(k) +
                    (k.clockWarning?.let { "\n  WARN   $it" } ?: "")
            },
            shown = { bridge, now -> bridge.predictedAt(now) },
            travelled = { _, now, start -> (now - start) / 1e9 * o.navSpeed },
            finished = { sentAll.get() },
        )
    }

    // ------------------------------------------------------------------------------------- sim

    fun sim(o: Options) {
        val videoId = o.videoId ?: fail("sim needs --video-id ID (clip stem present on both devices)")
        val playerStart = AtomicLong(0L)
        fun pts(now: Long): Double {
            val startNs = playerStart.get()
            if (startNs == 0L) return o.start
            val p = o.start + (now - startNs) / 1e9 * o.rate
            return o.duration?.let { minOf(p, it) } ?: p
        }
        fun playing(now: Long) = playerStart.get() != 0L && (o.duration == null || pts(now) < o.duration)
        run(
            o, ClientHello.sim(o.clientId, videoId, device, hint(o, "sim")),
            driver = { bridge, _ ->
                // Like a user pressing play once the laptop is ready (max 10 s wait).
                bridge.reportPlayback(videoId, o.start, playing = false, rate = o.rate)
                withTimeoutOrNull(10_000) { bridge.link.first { it.serverReady } }
                playerStart.set(System.nanoTime())
                while (isActive) {
                    val now = System.nanoTime()
                    bridge.reportPlayback(videoId, pts(now), playing(now), o.rate) // ~10 Hz, like a player ticker
                    delay(100)
                }
            },
            header = { bridge, now ->
                val k = bridge.link.value
                val p = pts(now)
                val r = bridge.resultForPts(p)
                val res = if (r != null) "result pts ${f2(r.ptsSeconds)} (${f0((p - r.ptsSeconds) * 1000)} ms behind playback)" else "NO RESULT for this pts"
                "---- SIM pts=${f2(p)}s ${if (playing(now)) "playing" else "paused"} x${f1(o.rate)} | $res" +
                    " | results arrived early by p50 ${k.simLeadMsP50?.let(::f0) ?: "-"} ms (min ${k.simLeadMsMin?.let(::f0) ?: "-"}), late ${k.simLateResults}" +
                    " | buffered ${k.simBuffered}\n     " + linkSummary(k)
            },
            shown = { bridge, now -> bridge.resultForPts(pts(now)) },
            travelled = { _, now, _ -> (pts(now) - o.start) * o.navSpeed },
            finished = { o.duration != null && playerStart.get() != 0L && pts(System.nanoTime()) >= o.duration },
        )
    }

    // ----------------------------------------------------------------------------------- watch

    fun watch(o: Options) {
        var firstPts: Double? = null
        run(
            o, ClientHello.video(o.clientId, device, hint(o, "off")),
            driver = { _, _ -> },
            header = { bridge, _ ->
                val s = bridge.world.value
                val tm = s.timing
                val k = bridge.link.value
                "---- WATCH t=${f2(s.ptsSeconds)}s seq=${s.seq}" +
                    (tm?.let { " proc ${f0(it.processingMs)} ms srv-drop ${it.serverDroppedFrames}" } ?: " waiting for frames") +
                    "\n     " + linkSummary(k)
            },
            shown = { bridge, _ -> bridge.world.value },
            travelled = { bridge, _, _ ->
                val p = bridge.world.value.timing?.ptsSeconds ?: 0.0
                if (firstPts == null || p < firstPts!!) firstPts = p
                (p - firstPts!!) * o.navSpeed
            },
        )
    }

    // ------------------------------------------------------------------------------ navigation

    /**
     * Replays a phase1 `trip_state.jsonl` as `client.trip_state` messages at the recorded pace
     * (real time), starting once the server is ready. `--trip-rebase` shifts the timestamps to now.
     */
    private suspend fun replayTripStates(o: Options, file: File, bridge: PerceptionBridge) {
        val samples = file.readLines().map { it.trim() }.filter { it.startsWith("{") }.mapNotNull { line ->
            runCatching { lenient.decodeFromString(ClientTripState.serializer(), line) }
                .onFailure { System.err.println("trip-states: skipping bad line: ${it.message?.take(120)}") }
                .getOrNull()
        }.sortedBy { it.timestampMs }
        if (samples.isEmpty()) { System.err.println("trip-states: no samples in $file"); return }
        println("trip states: ${samples.size} GPS samples over ${f1((samples.last().timestampMs - samples.first().timestampMs) / 1000.0)} s from $file${if (o.tripRebase) " (timestamps rebased to now)" else ""}")
        withTimeoutOrNull(10_000) { bridge.link.first { it.serverReady } }
        do {
            val t0 = samples.first().timestampMs
            val startNs = System.nanoTime()
            val nowMs = System.currentTimeMillis()
            for (s in samples) {
                val dueNs = startNs + (s.timestampMs - t0) * 1_000_000L
                val waitMs = (dueNs - System.nanoTime()) / 1_000_000
                if (waitMs > 0) delay(waitMs)
                val out = if (o.tripRebase) s.copy(timestampMs = nowMs + (s.timestampMs - t0)) else s
                if (!bridge.sendTripState(out)) System.err.println("trip-states: not sent (not connected, or another client controls the session), sample ${s.timestampMs} dropped")
            }
        } while (o.loop)
    }

    private suspend fun navStubLoop(o: Options, bridge: PerceptionBridge, travelled: () -> Double) {
        println("stub navigation (--nav-stub): ${o.navLabel} requires lane ${o.navLane} in ${o.navDistance.toInt()} m (counting down at ${o.navSpeed} m/s)")
        while (true) {
            bridge.setNavigation(stubNavigation(o, travelled()))
            delay(200)
        }
    }

    private fun fail(msg: String): Nothing {
        System.err.println(msg)
        exitProcess(2)
    }
}
