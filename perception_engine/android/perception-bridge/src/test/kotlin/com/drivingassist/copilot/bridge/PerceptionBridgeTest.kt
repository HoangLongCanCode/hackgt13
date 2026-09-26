package com.drivingassist.copilot.bridge

import com.drivingassist.copilot.context.LaneAction
import com.drivingassist.copilot.context.LaneSide
import com.drivingassist.copilot.context.Maneuver
import com.drivingassist.copilot.context.NavigationState
import com.drivingassist.copilot.perception.Camera
import com.drivingassist.copilot.perception.ClientCamera
import com.drivingassist.copilot.perception.ClientHello
import com.drivingassist.copilot.perception.ClientPing
import com.drivingassist.copilot.perception.ClientPlayback
import com.drivingassist.copilot.perception.ClientTripState
import com.drivingassist.copilot.perception.DeviceInfo
import com.drivingassist.copilot.perception.DistanceUpdate
import com.drivingassist.copilot.perception.Echo
import com.drivingassist.copilot.perception.GeoPoint
import com.drivingassist.copilot.perception.HelloMessage
import com.drivingassist.copilot.perception.ImageSize
import com.drivingassist.copilot.perception.Lanes
import com.drivingassist.copilot.perception.NavRouteState
import com.drivingassist.copilot.perception.NavigationHint
import com.drivingassist.copilot.perception.NavigationPacketMessage
import com.drivingassist.copilot.perception.ObjectClass
import com.drivingassist.copilot.perception.PerceivedObject
import com.drivingassist.copilot.perception.PerceptionCodec
import com.drivingassist.copilot.perception.PerceptionFrame
import com.drivingassist.copilot.perception.PerceptionMode
import com.drivingassist.copilot.perception.PerceptionUpdate
import com.drivingassist.copilot.perception.PongMessage
import com.drivingassist.copilot.perception.SimInfo
import com.drivingassist.copilot.perception.SkipMessage
import com.drivingassist.copilot.perception.Source
import com.drivingassist.copilot.perception.SourceKind
import com.drivingassist.copilot.perception.UplinkHeader
import com.drivingassist.copilot.perception.UplinkInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** End-to-end against a scripted MockWebServer that speaks PROTOCOL_v2 like the laptop server. */
class PerceptionBridgeTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bridges = CopyOnWriteArrayList<PerceptionBridge>()

    /** Every text the server received (all connections), and the first text of each connection. */
    private val texts = LinkedBlockingQueue<String>()
    private val firstTexts = LinkedBlockingQueue<String>()
    private val binaries = LinkedBlockingQueue<ByteArray>()

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 0xFF.toByte(), 0xD9.toByte())
    private val video = "b1ff4656-0435391e"
    private val device = DeviceInfo("samsung", "SM-X710", "16")
    private val liveHello = ClientHello.live("tab-s9-01", ClientCamera(960, 540, focalPx = 745.2, mountHeightMeters = 1.25), device)

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() {
        bridges.forEach { it.close() }
        scope.cancel()
        runCatching { server.shutdown() }
    }

    private val url get() = server.url("/perception").toString()

    private fun config() = BridgeConfig(
        reconnect = ReconnectPolicy(initialDelayMs = 50, maxDelayMs = 200),
        tickMs = 20,
        pingIntervalMs = 200,
    )

    private fun bridge(config: BridgeConfig = config()) = PerceptionBridge(url, scope, config).also { bridges += it }

    private fun enqueue(
        onOpen: (WebSocket) -> Unit = {},
        onText: (WebSocket, String) -> Unit = { _, _ -> },
        onBinary: (WebSocket, ByteArray) -> Unit = { _, _ -> },
    ) = server.enqueue(
        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            @Volatile var first = true
            override fun onOpen(webSocket: WebSocket, response: Response) = onOpen(webSocket)
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (first) { first = false; firstTexts.add(text) }
                texts.add(text)
                onText(webSocket, text)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onBinary(webSocket, bytes.toByteArray())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }),
    )

    private fun LinkedBlockingQueue<String>.pollType(type: String, timeoutMs: Long = 5_000, where: (String) -> Boolean = { true }): String? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) return null
            val t = poll(left, TimeUnit.MILLISECONDS) ?: return null
            if (t.contains("\"type\":\"$type\"") && where(t)) return t
        }
    }

    private suspend fun <T> StateFlow<T>.await(timeoutMs: Long = 5_000, predicate: (T) -> Boolean): T =
        withTimeout(timeoutMs) { first(predicate) }

    /** Like the real server: the uplink block is advertised in live AND sim (it can switch modes), plus navigation status. */
    private fun helloJson(session: String, mode: PerceptionMode, role: String? = null) = PerceptionCodec.encode(
        HelloMessage(
            protocolVersion = 2, schemaVersion = 2, sessionId = session, mode = mode,
            uplink = if (mode != PerceptionMode.VIDEO) UplinkInfo(maxInFlight = 2, preferredWidth = 960, preferredHeight = 540, jpegQuality = 80, header = "SDC1") else null,
            sim = if (mode == PerceptionMode.SIM) SimInfo(0.35, listOf(video)) else null,
            navigation = PerceptionCodec.json.parseToJsonElement("""{"mode":"${mode.wire}","available":true,"error":null}"""),
            role = role,
        ),
    )

    private val car = PerceivedObject(id = 7, cls = ObjectClass.CAR, bbox = listOf(430.0, 250.0, 540.0, 330.0), confidence = 0.9, ageFrames = 5, inEgoPath = true)

    private fun frameJson(seq: Long, pts: Double, echo: Echo?, session: String) = PerceptionCodec.encode(
        PerceptionFrame(
            schemaVersion = 2, seq = seq, sessionId = session, source = Source(SourceKind.CAMERA, "tab-s9-01"), frameIndex = seq,
            ptsSeconds = pts, serverTimeMs = System.currentTimeMillis(), processingMs = 20.0, image = ImageSize(960, 540),
            camera = Camera(745.2, listOf(480.0, 270.0)), objects = listOf(car), wave = 1, echo = echo,
        ),
    )

    private fun navJson(action: String, distance: Double, requiredLane: String? = null, pts: Double? = null) = PerceptionCodec.encode(
        NavigationPacketMessage(
            serverTimeMs = System.currentTimeMillis(), ptsSeconds = pts,
            routeState = NavRouteState(action, "$action in ${distance.toInt()} m.", "LANE_ARROW", distance, false, 60.0, 900.0, requiredLane, null, "Test Rd"),
        ),
    )

    @Test
    fun `live uplink drops instead of queueing and credits return on frame, skip and timeout`() = runBlocking {
        val serverWs = CompletableDeferred<WebSocket>()
        enqueue(onOpen = { ws -> ws.send(helloJson("live-1", PerceptionMode.LIVE)); serverWs.complete(ws) }, onBinary = { _, b -> binaries.add(b) })
        val b = bridge(config().copy(creditTimeoutMs = 1_500))
        assertFalse(b.offerCameraFrame(jpeg, System.nanoTime(), 0), "not connected yet: dropped")
        b.connect(liveHello)
        b.link.await { it.serverReady }
        assertEquals("""{"type":"client.hello"""", firstTexts.poll(5, TimeUnit.SECONDS)!!.take(22), "hello is the first message")

        val t0 = System.nanoTime()
        assertTrue(b.offerCameraFrame(jpeg, t0, 0))
        assertTrue(b.offerCameraFrame(jpeg, t0 + 1, 90))
        assertFalse(b.canUplinkNow(), "both credits in use: skip the JPEG encode")
        assertFalse(b.offerCameraFrame(jpeg, t0 + 2, 0), "no credit: dropped, not queued")
        assertFalse(b.offerCameraFrame(jpeg, t0 + 3, 0))
        val h0 = UplinkHeader.decode(binaries.poll(5, TimeUnit.SECONDS)!!)
        val second = binaries.poll(5, TimeUnit.SECONDS)!!
        val h1 = UplinkHeader.decode(second)
        assertEquals(UplinkHeader(0, t0, 0), h0)
        assertEquals(UplinkHeader(1, t0 + 1, 90), h1)
        assertContentEquals(jpeg, second.copyOfRange(UplinkHeader.SIZE, second.size))
        assertNull(binaries.poll(200, TimeUnit.MILLISECONDS), "exactly maxInFlight frames on the wire")

        val ws = serverWs.await()
        ws.send(frameJson(0, 0.0, Echo(0, t0), "live-1")) // wave-1 answer for frame 0
        b.link.await { it.credits == 1 }
        assertTrue(b.canUplinkNow())
        assertTrue(b.offerCameraFrame(jpeg, System.nanoTime(), 0)) // frame 2
        ws.send(PerceptionCodec.encode(SkipMessage(1, "superseded"))) // frame 1 superseded
        b.link.await { it.skips == 1L && it.credits == 1 }
        assertTrue(b.offerCameraFrame(jpeg, System.nanoTime(), 0)) // frame 3
        assertFalse(b.offerCameraFrame(jpeg, System.nanoTime(), 0), "2 and 3 in flight")
        assertEquals(listOf(2L, 3L), List(2) { UplinkHeader.decode(binaries.poll(5, TimeUnit.SECONDS)!!).frameId })

        // Answers for 2 and 3 never come: the credits return after the timeout (no deadlock).
        b.link.await(5_000) { it.creditTimeouts >= 2 }
        assertTrue(b.offerCameraFrame(jpeg, System.nanoTime(), 0))
        val k = b.link.value
        assertEquals(3, k.framesDroppedNoCredit)
        assertEquals(1, k.framesDroppedNotReady)
        assertEquals(0, k.lateAnswers)
        assertNotNull(k.captureToResultMsP50, "latency measured on the client clock from echo.captureTimeNs")
        assertEquals(0L, b.world.value.timing!!.frameId)
    }

    @Test
    fun `reconnects with backoff and re-sends client hello`() = runBlocking {
        enqueue(onOpen = { ws -> ws.send(helloJson("s1", PerceptionMode.SIM)) }, onText = { ws, t -> if (t.contains("\"client.hello\"")) ws.close(1000, "server restart") })
        enqueue(onOpen = { ws -> ws.send(helloJson("s2", PerceptionMode.SIM)) })
        val b = bridge()
        val hello = ClientHello.sim("tab-s9-01", video, device, NavigationHint.SIM)
        b.connect(hello)
        val first = firstTexts.poll(5, TimeUnit.SECONDS)
        val second = firstTexts.poll(5, TimeUnit.SECONDS)
        assertEquals(hello, PerceptionCodec.decodeClient(assertNotNull(first)))
        assertEquals(hello, PerceptionCodec.decodeClient(assertNotNull(second)), "hello re-sent first on the new connection")
        assertTrue(second.contains("\"navigation\":{\"mode\":\"sim\"}"), second)
        val k = b.link.await { it.serverReady && it.sessionId == "s2" }
        assertTrue(k.reconnects >= 1)
        assertEquals(LinkState.CONNECTED, k.state)
        assertEquals(PerceptionMode.SIM, k.serverMode)
    }

    @Test
    fun `sim - playback reaches the server and resultForPts picks the newest result at or before playback`() = runBlocking {
        enqueue(
            onOpen = { ws -> ws.send(helloJson("sim-1", PerceptionMode.SIM)) },
            onText = { ws, t -> if (t.contains("\"client.hello\"")) for (i in 0..20) ws.send(frameJson(i.toLong(), i / 10.0, null, "sim-1")) },
        )
        val b = bridge()
        b.reportPlayback(video, 0.0, playing = false) // player ready, paused at 0
        b.connect(ClientHello.sim("tab-s9-01", video, device))
        val pb = assertIs<ClientPlayback>(PerceptionCodec.decodeClient(texts.pollType("client.playback")!!))
        assertEquals(ClientPlayback(video, 0.0, false, 1.0, pb.clientTimeNs), pb, "position sent right after the hello")
        val ready = b.link.await { it.simBuffered >= 21 }
        assertFalse(b.canUplinkNow())
        assertFalse(b.offerCameraFrame(jpeg, System.nanoTime(), 0), "sim session takes no camera frames even though the hello advertises the uplink")
        assertEquals("sim", ready.serverNavigationMode)
        assertEquals(true, ready.serverNavigationAvailable)

        assertEquals(1.0, b.resultForPts(1.0)!!.ptsSeconds)
        assertEquals(1.0, b.resultForPts(1.04)!!.ptsSeconds, "never a result from the future")
        assertEquals(2.0, b.resultForPts(2.1)!!.ptsSeconds)
        assertNull(b.resultForPts(2.2), "newest result is 200 ms behind playback: too late for this frame")
        assertNull(b.resultForPts(-0.1))

        // world / context follow the playback position.
        b.reportPlayback(video, 0.3, playing = false)
        b.world.await { it.ptsSeconds == 0.3 && !it.perceptionStale }
        assertEquals(0.3, assertIs<ClientPlayback>(PerceptionCodec.decodeClient(texts.pollType("client.playback") { it.contains("\"ptsSeconds\":0.3") }!!)).ptsSeconds)
        b.reportPlayback(video, 0.7, playing = false)
        b.world.await { it.ptsSeconds == 0.7 }
        assertTrue(b.link.value.simLeadMsP50!! > 0, "results arrived before their frame was shown")
        assertEquals(0, b.link.value.simLateResults)

        // Seek far ahead: old results are dropped and perception is stale until new ones arrive.
        b.reportPlayback(video, 15.0, playing = false)
        b.world.await { it.perceptionStale }
        b.link.await { it.simBuffered == 0 }
        assertNull(b.resultForPts(1.0))
    }

    @Test
    fun `live - wave-2 update merges into the world, ping measures rtt, silence goes navigation-only`() = runBlocking {
        enqueue(
            onOpen = { ws -> ws.send(helloJson("live-2", PerceptionMode.LIVE)) },
            onText = { ws, t ->
                if (t.contains("\"client.ping\"")) {
                    val ping = assertIs<ClientPing>(PerceptionCodec.decodeClient(t))
                    ws.send(PerceptionCodec.encode(PongMessage(ping.clientTimeNs, System.currentTimeMillis())))
                }
            },
            onBinary = { ws, bytes ->
                val h = UplinkHeader.decode(bytes)
                val echo = Echo(h.frameId, h.captureTimeNs)
                val pts = h.frameId / 15.0
                ws.send(frameJson(h.frameId, pts, echo, "live-2"))
                ws.send(
                    PerceptionCodec.encode(
                        PerceptionUpdate(
                            seq = h.frameId, frameIndex = h.frameId, ptsSeconds = pts, echo = echo, processingMs = 55.0,
                            distances = listOf(DistanceUpdate(7, 18.4, "fused", 0.8, -0.3)),
                            lanes = Lanes(2, 3, emptyList(), 0.7), blocks = listOf("depth", "lanes"),
                        ),
                    ),
                )
            },
        )
        val b = bridge()
        b.setNavigation(NavigationState(Maneuver.EXIT, 250.0, "Exit 23B", requiredLanes = listOf(3)))
        b.connect(liveHello)
        b.link.await { it.serverReady }
        assertTrue(b.offerCameraFrame(jpeg, System.nanoTime(), 0))

        val w = b.world.await { it.objects[7]?.distanceMeters != null }
        assertEquals(18.4, w.objects.getValue(7).distanceMeters)
        assertEquals(-0.3, w.objects.getValue(7).lateralMeters)
        assertEquals(2, w.lanes!!.currentLane)
        assertEquals(0L, w.wave2!!.seq)
        assertNotNull(w.timing!!.captureToResultMs)
        assertFalse(w.perceptionStale)
        assertEquals(LaneAction.CHANGE_LANE_RIGHT, b.context.await { it.laneGuidance?.action == LaneAction.CHANGE_LANE_RIGHT }.laneGuidance!!.action)
        val k = b.link.await { it.rttMs != null && it.captureToResultMsP50 != null }
        assertTrue(k.rttMs!! >= 0.0)

        // No more camera frames: after 500 ms without results perception is stale -> navigation-only.
        assertTrue(b.world.await(3_000) { it.perceptionStale }.perceptionStale)
        val ctx = b.context.await(3_000) { it.perceptionStale }
        assertEquals(LaneAction.UNKNOWN, ctx.laneGuidance!!.action, "lanes are perception; guidance falls back to the route")
        assertNull(ctx.following.leadTrackId)
        assertTrue(b.link.await { it.perceptionStale }.perceptionStale)
    }

    @Test
    fun `navigation packets drive the navigation flow and lane guidance, then go stale`() = runBlocking {
        val serverWs = CompletableDeferred<WebSocket>()
        enqueue(onOpen = { ws -> ws.send(helloJson("live-nav", PerceptionMode.LIVE)); serverWs.complete(ws) })
        val b = bridge(config().copy(navigationStaleAfterMs = 400))
        b.connect(liveHello.copy(navigation = NavigationHint.LIVE))
        b.link.await { it.serverReady }
        val ws = serverWs.await()

        // A replayed packet of a sim session (it carries media time) is not this live trip's route.
        ws.send(navJson("TURN_RIGHT", 28.0, pts = 12.3))
        // Perception says lane 1 of 3; the route says: exit in 400 m from the rightmost lane.
        ws.send(frameJson(0, 0.0, null, "live-nav").replace("\"objects\"", "\"lanes\":{\"currentLane\":1,\"laneCount\":3,\"laneBoundaries\":[],\"confidence\":0.8},\"objects\""))
        ws.send(navJson("EXIT_HIGHWAY", 400.0, requiredLane = "right"))
        val nav = b.navigation.await { it != null }!!
        assertEquals("EXIT_HIGHWAY", nav.routeState!!.action)
        assertEquals("EXIT_HIGHWAY in 400 m.", nav.routeState!!.audio)
        assertEquals(1L, nav.sequence)
        assertFalse(nav.stale)
        assertEquals(Maneuver.EXIT, b.navigationState.await { it != null }!!.maneuver)
        val ctx = b.context.await { it.laneGuidance?.action == LaneAction.CHANGE_LANE_RIGHT }
        assertEquals(2, ctx.laneGuidance!!.lanesToMove)
        assertEquals(LaneSide.RIGHT, ctx.navigation!!.requiredSide)
        assertEquals(1L, b.link.await { it.navigationPackets == 1L }.navigationPackets)

        // An explicit override wins until cleared.
        b.setNavigation(NavigationState(Maneuver.TURN_LEFT, 100.0))
        assertEquals(Maneuver.TURN_LEFT, b.navigationState.value!!.maneuver)
        b.setNavigation(null)
        assertEquals(Maneuver.EXIT, b.navigationState.value!!.maneuver)

        // No packets for 400 ms: stale; the context drops the route.
        assertTrue(b.navigation.await(3_000) { it?.stale == true }!!.stale)
        assertNull(b.navigationState.await(3_000) { it == null })
        assertNull(b.context.await(3_000) { it.navigation == null }.laneGuidance)
        // A new packet revives it.
        ws.send(navJson("TURN_LEFT", 80.0))
        assertFalse(b.navigation.await { it?.sequence == 2L }!!.stale)
    }

    @Test
    fun `trip states reach the server as client trip_state after the hello`() = runBlocking {
        enqueue(onOpen = { ws -> ws.send(helloJson("live-gps", PerceptionMode.LIVE)) })
        val b = bridge()
        assertFalse(b.sendTripState(1L, 1.0, 2.0), "not connected: not queued")
        b.connect(liveHello)
        b.link.await { it.serverReady }
        assertTrue(b.sendTripState(1790000000123, 33.7756, -84.3963, headingDegrees = 91.2, speedMps = 6.1, accuracyMeters = 4.1))
        val t = assertIs<ClientTripState>(PerceptionCodec.decodeClient(texts.pollType("client.trip_state")!!))
        assertEquals(ClientTripState(1790000000123, GeoPoint(33.7756, -84.3963), 91.2, 6.1, 4.1), t)
        assertEquals(1L, b.link.await { it.tripStatesSent == 1L }.tripStatesSent)
    }

    @Test
    fun `server error is surfaced and cleared by the next session's hello`() = runBlocking {
        val serverWs = CompletableDeferred<WebSocket>()
        enqueue(onOpen = { ws -> serverWs.complete(ws) })
        val b = bridge()
        b.connect(ClientHello.sim("tab-s9-01", "nope", device))
        val ws = serverWs.await()
        ws.send(helloJson("idle", PerceptionMode.SIM, role = "watcher"))
        b.link.await { it.serverReady }
        // Like the server answering a hello it cannot honour: the error, then a hello of the SAME session.
        ws.send("""{"type":"perception.error","code":"unknownVideo","message":"no clip nope","fatal":false}""")
        ws.send(helloJson("idle", PerceptionMode.SIM, role = "watcher"))
        ws.send(navJson("GO_STRAIGHT", 10.0, pts = 1.0)) // processed after the hello on the socket thread (sim packets carry pts)
        b.link.await { it.navigationPackets == 1L }
        assertEquals("unknownVideo no clip nope", b.link.await { it.serverError != null }.serverError, "kept: same session")
        ws.send(helloJson("sim-2", PerceptionMode.SIM, role = "controller"))
        assertNull(b.link.await { it.sessionId == "sim-2" }.serverError, "a new session clears it")
    }

    @Test
    fun `live - taken over by another client, the bridge goes passive, ignores foreign results and reclaims when idle`() = runBlocking {
        val serverWs = CompletableDeferred<WebSocket>()
        var sessions = 0
        enqueue(
            onOpen = { ws -> ws.send(helloJson("idle", PerceptionMode.LIVE, role = "watcher")); serverWs.complete(ws) },
            onText = { ws, t -> if (t.contains("\"client.hello\"")) ws.send(helloJson("live-A${++sessions}", PerceptionMode.LIVE, role = "controller")) },
            onBinary = { _, b -> binaries.add(b) },
        )
        val b = bridge(config().copy(creditTimeoutMs = 10_000))
        b.connect(liveHello)
        assertEquals("controller", b.link.await { it.role == "controller" }.role)
        val ws = serverWs.await()
        val t0 = System.nanoTime()
        assertTrue(b.offerCameraFrame(jpeg, t0, 0)) // frame 0
        assertTrue(b.offerCameraFrame(jpeg, t0 + 1, 0)) // frame 1 stays in flight
        ws.send(frameJson(0, 0.0, Echo(0, t0), "live-A1"))
        b.world.await { it.objects.containsKey(7) }
        assertNotNull(b.link.await { it.captureToResultMsP50 != null && it.inFlight == 1 }.captureToResultMsP50)

        // Another client's newer hello wins: notUplinkClient + a watcher hello of ITS session.
        ws.send("""{"type":"perception.error","code":"notUplinkClient","message":"client py took over","fatal":false}""")
        ws.send(helloJson("live-B", PerceptionMode.LIVE, role = "watcher"))
        val k = b.link.await { it.takenOver }
        assertEquals("watcher", k.role)
        assertNull(k.captureToResultMsP50, "latency of the old stream dropped")
        assertFalse(b.canUplinkNow(), "no JPEG encode while taken over")
        assertFalse(b.offerCameraFrame(jpeg, System.nanoTime(), 0), "no uplink while taken over")
        assertFalse(b.sendTripState(1790000000123, 33.7, -84.3), "GPS belongs to the controller")
        // B's results: its echo frameId 1 collides with our in-flight frame 1 and its capture clock is not ours.
        ws.send(frameJson(1, 5.0, Echo(1, 42L), "live-B"))
        ws.send(frameJson(2, 5.1, Echo(2, 43L), "live-B"))
        ws.send(navJson("GO_STRAIGHT", 10.0)) // processed after the frames on the socket thread
        b.link.await { it.navigationPackets == 1L }
        Thread.sleep(200)
        val k2 = b.link.value
        assertEquals(1, k2.inFlight, "a foreign echo must not release our credit")
        assertEquals(0, k2.lateAnswers)
        assertNull(k2.captureToResultMsP50, "foreign captureTimeNs is not a latency sample")
        assertTrue(b.world.value.objects.isEmpty(), "the other controller's detections are not drawn")
        assertTrue(b.world.await { it.perceptionStale }.perceptionStale)
        assertTrue(binaries.size <= 2, "only frames 0 and 1 were ever uplinked")

        // The other controller leaves: the server goes idle and the bridge takes the session back.
        ws.send(helloJson("idle", PerceptionMode.LIVE, role = "watcher"))
        val back = b.link.await { it.role == "controller" && it.sessionId == "live-A2" }
        assertFalse(back.takenOver)
        assertEquals(2, texts.count { it.contains("\"type\":\"client.hello\"") }, "hello re-sent once to reclaim")
        assertTrue(b.canUplinkNow(), "uplink allowed again (frame 1 still holds one of the 2 credits)")
    }
}
