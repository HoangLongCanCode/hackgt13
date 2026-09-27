package com.drivingassist.copilot.bridge

import com.drivingassist.copilot.context.DrivingContext
import com.drivingassist.copilot.context.DrivingContextEngine
import com.drivingassist.copilot.context.DrivingEvent
import com.drivingassist.copilot.context.EgoSpeedEstimator
import com.drivingassist.copilot.context.NavigationMapper
import com.drivingassist.copilot.context.NavigationState
import com.drivingassist.copilot.context.WorldModel
import com.drivingassist.copilot.context.WorldSnapshot
import com.drivingassist.copilot.context.predictedAt
import com.drivingassist.copilot.perception.ClientDestination
import com.drivingassist.copilot.perception.ClientHello
import com.drivingassist.copilot.perception.ClientPing
import com.drivingassist.copilot.perception.ClientPlaceSearch
import com.drivingassist.copilot.perception.ClientPlayback
import com.drivingassist.copilot.perception.ClientTripState
import com.drivingassist.copilot.perception.ErrorMessage
import com.drivingassist.copilot.perception.GeoPoint
import com.drivingassist.copilot.perception.HelloMessage
import com.drivingassist.copilot.perception.NavigationPacketMessage
import com.drivingassist.copilot.perception.NavigationPlacesMessage
import com.drivingassist.copilot.perception.PerceptionCodec
import com.drivingassist.copilot.perception.PerceptionFrame
import com.drivingassist.copilot.perception.PerceptionMessage
import com.drivingassist.copilot.perception.PerceptionMode
import com.drivingassist.copilot.perception.PerceptionUpdate
import com.drivingassist.copilot.perception.PongMessage
import com.drivingassist.copilot.perception.SkipMessage
import com.drivingassist.copilot.perception.StatsMessage
import com.drivingassist.copilot.perception.UplinkHeader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.ByteString
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

/**
 * The single facade the tablet AR app talks to (PROTOCOL_v2). Pure JVM + OkHttp + coroutines, so
 * the same class runs in the Android app, the JVM fake tablet (`:bridge-cli`) and the tests.
 *
 * ```
 * val bridge = PerceptionBridge(Protocol.USB_URL, scope, BridgeConfig(clockNs = SystemClock::elapsedRealtimeNanos))
 * bridge.connect(ClientHello.live("tab-s9-01", ClientCamera(960, 540, focalPx = 745.2, mountHeightMeters = 1.25)))
 * // camera thread:   bridge.offerCameraFrame(jpeg, captureTimeNs, image.imageInfo.rotationDegrees)
 * // render thread:   val w = bridge.predictedAt(clockNs())                     // live
 * //                  val w = bridge.resultForPts(player.currentPosition / 1000.0)  // sim
 * // UI:              bridge.world / context / events / navigation / link
 * // GPS (live nav):  bridge.sendTripState(ClientTripState(...))  ~1 Hz
 * // "Where to?":     val id = bridge.searchPlaces("coffee", near)  -> bridge.places; bridge.sendDestination(label, placeId, location)
 * ```
 *
 * - **live**: [offerCameraFrame] sends `SDC1` header + JPEG under credit flow control (never queues).
 * - **sim**: [reportPlayback] from the player (~10 Hz + on seek/pause); [resultForPts] picks the
 *   buffered result for the frame on screen; [world] follows the playback position.
 * - **video**: results of a clip the laptop plays itself (laptop-side testing).
 * - **navigation**: `navigation.packet`s from the phase1 route engine (relayed by the laptop) land
 *   in [navigation]; they also drive the Driving Context's lane guidance, and give it the ego speed
 *   ([EgoSpeedEstimator]: the smaller of the traveled-distance speed and phase1's lagging `speedMps`). [sendTripState] feeds
 *   live GPS to the relay; [searchPlaces] / [places] / [sendDestination] pick where it routes to.
 *   Route logic stays in phase1.
 *
 * Auto-reconnects with backoff and re-sends the [ClientHello] after every reconnect; pings ~1 Hz.
 * [world] merges wave-1 frames and wave-2 updates ([WorldModel]); [context] / [events] come from
 * the deterministic [DrivingContextEngine], which goes navigation-only while perception is stale.
 *
 * Roles: the newest `client.hello` on the server takes the session over. When another client does
 * that, the server's hello says `role: "watcher"` (plus `perception.error notUplinkClient`): the
 * bridge then stops uplinking / reporting playback / sending GPS, ignores the other controller's
 * results (they belong to another camera or clip) so [world] goes stale, and sets
 * [LinkStatus.takenOver]. It takes the session back by itself when the server goes idle (the other
 * controller left, [BridgeConfig.reclaimWhenIdle]); [reclaim] takes it back right away.
 *
 * Threading: every public function is non-blocking and callable from any thread. Internal work
 * runs on [BridgeConfig.dispatcher]; read the StateFlows from the UI.
 *
 * @param scope parent scope (e.g. `viewModelScope`); cancelling it stops the bridge.
 */
class PerceptionBridge(
    val url: String,
    scope: CoroutineScope,
    val config: BridgeConfig = BridgeConfig(),
) : AutoCloseable {

    private val clock = config.clockNs
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val bridgeScope = CoroutineScope(scope.coroutineContext + job + config.dispatcher + CoroutineName("perception-bridge"))
    private val ownsClient = config.okHttpClient == null
    private val client: OkHttpClient = config.okHttpClient ?: defaultOkHttpClient()

    private val worldModel = WorldModel(config.worldModel, clockNs = clock)
    private val engine = DrivingContextEngine(config.drivingContext)
    private val worldLock = Any()
    private val startLock = Any()
    private val navLock = Any()

    private val _world = MutableStateFlow(WorldSnapshot.EMPTY)

    /** The realtime world (objects by track id, lanes, road, signs, timing, perceptionStale). */
    val world: StateFlow<WorldSnapshot> = _world.asStateFlow()

    /** Deterministic Driving Context (following state, light, pedestrians, lane guidance, alerts). */
    val context: StateFlow<DrivingContext> = engine.context

    /** Edges with a plan §24 priority and optional speech. No replay: collect before [connect]. */
    val events: SharedFlow<DrivingEvent> = engine.events

    private val _link = MutableStateFlow(LinkStatus(url = url))

    /** Link health (state, RTT, capture -> result latency, fps, credits, skips, reconnects, navigation age). */
    val link: StateFlow<LinkStatus> = _link.asStateFlow()

    private val _serverHello = MutableStateFlow<HelloMessage?>(null)

    /** Newest `perception.hello` (session, mode, uplink parameters, sim lookahead, models). */
    val serverHello: StateFlow<HelloMessage?> = _serverHello.asStateFlow()

    private val _navigation = MutableStateFlow<NavigationUpdate?>(null)

    /** Newest phase1 `navigation.packet` (route state + verbatim packet), null before the first one. */
    val navigation: StateFlow<NavigationUpdate?> = _navigation.asStateFlow()

    private val navigationInput = MutableStateFlow<NavigationState?>(null)

    /** What the Driving Context uses as navigation input (from packets, or a [setNavigation] override). */
    val navigationState: StateFlow<NavigationState?> = navigationInput.asStateFlow()

    private val _places = MutableStateFlow<NavigationPlacesMessage?>(null)

    /**
     * The laptop's `navigation.places` answer to the newest [searchPlaces]: null while that search is pending
     * (and before the first); answers to older searches are dropped.
     */
    val places: StateFlow<NavigationPlacesMessage?> = _places.asStateFlow()

    /** requestId of the newest [searchPlaces]; only its answer lands in [places]. */
    @Volatile private var newestSearchId: String? = null
    private val searchCounter = AtomicLong()

    @Volatile private var clientHello: ClientHello? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var started = false
    @Volatile private var state = LinkState.IDLE
    @Volatile private var helloOnConnection = false
    @Volatile private var uplinkAllowed = false
    @Volatile private var lastError: String? = null
    @Volatile private var serverError: String? = null
    @Volatile private var rttMs: Double? = null
    @Volatile private var clockWarning: String? = null
    @Volatile private var lastPingNs: Long? = null
    @Volatile private var lastSimPublished: WorldSnapshot? = null
    @Volatile private var manualNavigation: NavigationState? = null
    @Volatile private var packetNavigation: NavigationState? = null
    /** Ego speed of the packet stream ([NavigationState.egoSpeedMps]; fed on the socket thread only, [onNavigation]). */
    private val egoSpeed = EgoSpeedEstimator()
    private var lastSessionId: String? = null

    /** `perception.hello.role` of the newest hello on this connection (null = the server has no roles). */
    @Volatile private var serverRole: String? = null

    /** Another client took the session over (we were its controller, or the server said notUplinkClient). */
    @Volatile private var takenOver = false

    /** A hello on this connection made us the controller (so a later watcher hello means we were taken over). */
    @Volatile private var controllerOnConnection = false

    /** Session whose live / sim results are ours (the newest hello that did not call us a watcher). */
    @Volatile private var ownSessionId: String? = null

    /** sessionId of the previous hello on this connection (serverError is kept until the session changes). */
    @Volatile private var lastHelloSessionId: String? = null

    private val credits = CreditGate(config.defaultMaxInFlight, config.creditTimeoutMs * 1_000_000L)
    private val uplinkLock = Any()
    private var nextFrameId = 0L

    private val framesOffered = AtomicLong()
    private val framesSent = AtomicLong()
    private val droppedNoCredit = AtomicLong()
    private val droppedNotReady = AtomicLong()
    private val skips = AtomicLong()
    private val creditTimeouts = AtomicLong()
    private val lateAnswers = AtomicLong()
    private val reconnects = AtomicLong()
    private val decodeErrors = AtomicLong()
    private val simLate = AtomicLong()
    private val navPackets = AtomicLong()
    private val ignoredNavPackets = AtomicLong()
    private val tripStatesSent = AtomicLong()
    private val captureToResult = RollingWindow(config.statsWindow)
    private val simLead = RollingWindow(config.statsWindow)
    private val resultRate = RateMeter()
    private val updateRate = RateMeter()
    private val uplinkRate = RateMeter()
    private val playback = PlaybackClock()
    private val simBuffer = PtsResultBuffer()

    /** [own] = the message belongs to our stream (not the results of another controller we are watching). */
    private class Inbound(val message: PerceptionMessage, val receivedNs: Long, val receivedMs: Long, val own: Boolean = true)

    private val inbound = Channel<Inbound>(config.inboundCapacity, BufferOverflow.DROP_OLDEST)

    /** Mode requested by the current [ClientHello] (null before [connect]). */
    val mode: PerceptionMode? get() = clientHello?.mode

    /**
     * Starts connecting (first call) and keeps reconnecting until [close]. [hello] is sent first on
     * every (re)connect. Calling again with another hello (e.g. switching sim -> live, or new camera
     * dimensions) re-sends it on the open socket and clears mode-specific state.
     */
    fun connect(hello: ClientHello) {
        check(!closed) { "PerceptionBridge is closed" }
        val previous = clientHello
        clientHello = hello
        if (previous != null && previous != hello) {
            synchronized(worldLock) { simBuffer.clear(); lastSimPublished = null }
            credits.reset(); captureToResult.clear(); simLead.clear()
        }
        val first = synchronized(startLock) { if (started) false else { started = true; true } }
        if (first) {
            start()
        } else if (previous != hello) {
            socket?.let { ws -> takenOver = false; ws.send(PerceptionCodec.encodeClient(hello)) }
        }
    }

    /**
     * Takes the session back after another client took it over ([LinkStatus.takenOver]): re-sends
     * the current [ClientHello] (the newest hello wins on the server), plus the player position in
     * sim. Returns false when not connected. Not needed when [BridgeConfig.reclaimWhenIdle] is on and
     * the other controller has left.
     */
    fun reclaim(): Boolean {
        val ws = socket ?: return false
        if (closed || clientHello == null) return false
        sendHello(ws, clock())
        return true
    }

    /** Sends the current hello (and, in sim, where the player is) on [ws]. We take over: no longer "taken over". */
    private fun sendHello(ws: WebSocket, now: Long) {
        val hello = clientHello ?: return
        takenOver = false
        ws.send(PerceptionCodec.encodeClient(hello))
        // Sim: tell the server where the player is right away (don't wait for the next report).
        val pb = playback.state
        if (hello.mode == PerceptionMode.SIM && pb != null) {
            val pts = playback.estimate(now) ?: pb.ptsSeconds
            ws.send(PerceptionCodec.encodeClient(ClientPlayback(pb.videoId, pts, pb.playing, pb.rate, now)))
        }
    }

    // ------------------------------------------------------------------------------------ live

    /**
     * Live mode: uplink one camera frame. Non-blocking (safe on the CameraX analyzer thread).
     * Returns false when the frame was DROPPED: not connected / server not ready or not in live
     * mode, or all `maxInFlight` credits in use. Frames are never queued.
     *
     * @param jpeg baseline JPEG, ~960x540 q80. Copied once before this returns, so the caller may
     *   reuse the array for the next frame.
     * @param captureTimeNs capture time on the same clock as [BridgeConfig.clockNs].
     * @param rotationDegrees `ImageProxy.imageInfo.rotationDegrees` (clockwise rotation to upright).
     * @param length number of JPEG bytes at the start of [jpeg] (for a reused encoder buffer).
     */
    fun offerCameraFrame(jpeg: ByteArray, captureTimeNs: Long, rotationDegrees: Int = 0, length: Int = jpeg.size): Boolean {
        require(length in 0..jpeg.size) { "length $length outside the jpeg array (${jpeg.size} bytes)" }
        framesOffered.incrementAndGet()
        val ws = socket
        if (ws == null || closed || !uplinkAllowed || !helloOnConnection) {
            droppedNotReady.incrementAndGet()
            return false
        }
        val now = clock()
        checkClock(captureTimeNs, now)
        synchronized(uplinkLock) {
            val expired = credits.sweep(now)
            if (expired > 0) creditTimeouts.addAndGet(expired.toLong())
            val id = nextFrameId
            if (!credits.tryAcquire(id, now)) {
                droppedNoCredit.incrementAndGet()
                return false
            }
            nextFrameId = (nextFrameId + 1) and UplinkHeader.UINT32_MAX
            // Header + JPEG copied once into okio segments; snapshot() shares them (no second copy).
            val payload = Buffer()
                .write(UplinkHeader(id, captureTimeNs, UplinkHeader.normalizeRotation(rotationDegrees)).encode())
                .write(jpeg, 0, length)
                .snapshot()
            if (!ws.send(payload)) {
                credits.release(id)
                droppedNotReady.incrementAndGet()
                return false
            }
        }
        framesSent.incrementAndGet()
        uplinkRate.mark(now)
        return true
    }

    /**
     * Live mode: true when a frame offered now would be sent (connected, server ready for the
     * uplink, a credit free). Check it before JPEG-encoding a camera image to save the encode
     * when the frame would be dropped anyway. Advisory: [offerCameraFrame] re-checks.
     */
    fun canUplinkNow(): Boolean = socket != null && !closed && uplinkAllowed && helloOnConnection && credits.available > 0

    /** Counts a camera frame the caller dropped before offering it (e.g. [canUplinkNow] was false), for the link stats. */
    fun noteFrameSkippedByCaller() {
        framesOffered.incrementAndGet()
        if (socket == null || !uplinkAllowed || !helloOnConnection) droppedNotReady.incrementAndGet() else droppedNoCredit.incrementAndGet()
    }

    /**
     * Live mode: [world] moved forward from the analysed frame's capture time to [displayTimeNs]
     * (same clock as captureTimeNs): boxes by track velocity, distances by relative speed.
     * Other modes: [world] unchanged.
     */
    fun predictedAt(displayTimeNs: Long): WorldSnapshot = _world.value.predictedAt(displayTimeNs, config.maxPredictionMs)

    // ------------------------------------------------------------------------------------- sim

    /**
     * Sim mode: the tablet player's position. Call ~10 Hz while playing and immediately on
     * seek / pause / resume / rate change. Non-blocking.
     */
    fun reportPlayback(videoId: String, ptsSeconds: Double, playing: Boolean, rate: Double = 1.0) {
        val now = clock()
        if (playback.update(videoId, ptsSeconds, playing, rate, now)) {
            // Seek or another clip: buffered results belong to the old position.
            synchronized(worldLock) { simBuffer.clear(); lastSimPublished = null }
        }
        // Taken over: the server only takes playback from the controller; [reclaim] sends the position.
        if (!takenOver) socket?.send(PerceptionCodec.encodeClient(ClientPlayback(videoId, ptsSeconds, playing, rate, now)))
    }

    /**
     * Sim mode: the result for the frame being shown: newest buffered result with
     * `pts <= ptsSeconds` and no more than [BridgeConfig.simMaxLagSeconds] older; null if none
     * (laptop behind / just seeked). Call once per rendered frame with the player's position.
     */
    fun resultForPts(ptsSeconds: Double): WorldSnapshot? {
        val s = simBuffer.select(ptsSeconds, config.simMaxLagSeconds) ?: return null
        return if (state != LinkState.CONNECTED) s.copy(perceptionStale = true) else s
    }

    // ------------------------------------------------------------------------------ navigation

    /**
     * Live navigation: one GPS fix for the phase1 relay (`client.trip_state`, ~1 Hz). Returns false
     * when it could not be sent (not connected, or another client controls the session); fixes are
     * not queued, the next one supersedes it.
     */
    fun sendTripState(trip: ClientTripState): Boolean {
        val ws = socket ?: return false
        if (closed || takenOver) return false
        val ok = ws.send(PerceptionCodec.encodeClient(trip))
        if (ok) tripStatesSent.incrementAndGet()
        return ok
    }

    /**
     * Live navigation: where to go (a place or address, at most 200 characters). The laptop resolves it with its
     * phase1 provider and builds the route from the next GPS fix. With [location] (a place picked from [places],
     * [query] = its label) the laptop routes to exactly that point and does not geocode. Returns false when it
     * could not be sent (not connected, taken over, blank). Not re-sent on reconnect: the server keeps the destination.
     */
    fun sendDestination(query: String, placeId: String? = null, location: GeoPoint? = null): Boolean {
        val q = query.trim().take(ClientDestination.MAX_LENGTH)
        if (q.isEmpty()) return false
        val ws = socket ?: return false
        if (closed || takenOver) return false
        val id = placeId?.trim()?.takeIf { it.isNotEmpty() && it.length <= ClientDestination.MAX_PLACE_ID_LENGTH }
        return ws.send(PerceptionCodec.encodeClient(ClientDestination(q, id, location)))
    }

    /**
     * Live navigation: searches places for a destination ("coffee", a name, an address; at most 200 characters)
     * around [near] (the latest GPS fix, null = no bias). Call on submit, not per keystroke (the laptop answers at
     * most 2 searches per second). The answer arrives in [places], which is cleared now. Returns the requestId,
     * null when nothing was sent (not connected, taken over, blank query).
     */
    fun searchPlaces(query: String, near: GeoPoint? = null): String? {
        val q = query.trim().take(ClientPlaceSearch.MAX_LENGTH)
        if (q.isEmpty()) return null
        val ws = socket ?: return null
        if (closed || takenOver) return null
        val id = "s${searchCounter.incrementAndGet()}"
        // Before the send: the answer may arrive on the socket thread before send() returns.
        newestSearchId = id
        _places.value = null
        return if (ws.send(PerceptionCodec.encodeClient(ClientPlaceSearch(id, q, near)))) id else null
    }

    /** [headingDegrees] / [speedMps] null (no bearing / speed in the fix) are sent as 0, as PROTOCOL_v2 asks. */
    fun sendTripState(
        timestampMs: Long,
        lat: Double,
        lng: Double,
        headingDegrees: Double? = null,
        speedMps: Double? = null,
        accuracyMeters: Double? = null,
    ): Boolean = sendTripState(ClientTripState(timestampMs, GeoPoint(lat, lng), headingDegrees ?: 0.0, speedMps ?: 0.0, accuracyMeters))

    /**
     * Overrides the Driving Context's navigation input (tests, a stub route, another navigation
     * source); null returns to the phase1 packets. The [navigation] flow is not affected.
     */
    fun setNavigation(state: NavigationState?) {
        synchronized(navLock) {
            manualNavigation = state
            navigationInput.value = state ?: packetNavigation.takeIf { _navigation.value?.stale == false }
        }
    }

    // ------------------------------------------------------------------------------- lifecycle

    /** Closes the socket and stops all bridge coroutines. The bridge cannot be reused. */
    override fun close() {
        if (closed) return
        closed = true
        state = LinkState.CLOSED
        socket?.close(1000, "client closed")
        socket = null
        uplinkAllowed = false
        inbound.close()
        publishLink(clock())
        job.cancel()
        if (ownsClient) {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun start() {
        bridgeScope.launch(CoroutineName("bridge-context")) { engine.run(world, navigationInput) }
        bridgeScope.launch(CoroutineName("bridge-world")) { processLoop() }
        bridgeScope.launch(CoroutineName("bridge-tick")) { tickLoop() }
        bridgeScope.launch(CoroutineName("bridge-socket")) { connectLoop() }
    }

    private suspend fun connectLoop() {
        var failures = 0
        var attempt = 0
        while (currentCoroutineContext().isActive && !closed) {
            if (attempt++ > 0) reconnects.incrementAndGet()
            state = LinkState.CONNECTING
            val done = CompletableDeferred<String>()
            val listener = Listener(done)
            val ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
            val reason = try {
                done.await()
            } finally {
                if (!done.isCompleted) ws.cancel() // cancelled by close()
            }
            if (socket === ws) socket = null
            uplinkAllowed = false
            helloOnConnection = false
            credits.reset()
            if (!currentCoroutineContext().isActive || closed) break
            failures = if (listener.opened) 0 else failures + 1
            lastError = reason
            state = LinkState.DISCONNECTED
            delay(config.reconnect.delayMs(failures))
        }
    }

    private inner class Listener(private val done: CompletableDeferred<String>) : WebSocketListener() {
        @Volatile
        var opened = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (closed) { webSocket.close(1000, "client closed"); return }
            opened = true
            credits.reset()
            uplinkAllowed = false
            helloOnConnection = false
            serverRole = null
            controllerOnConnection = false
            ownSessionId = null
            lastHelloSessionId = null
            sendHello(webSocket, clock())
            socket = webSocket
            lastPingNs = null
            state = LinkState.CONNECTED
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val now = clock()
            val nowMs = System.currentTimeMillis()
            val msg = try {
                PerceptionCodec.decode(text)
            } catch (e: Exception) {
                decodeErrors.incrementAndGet()
                lastError = "decode: ${e.message?.take(200)}"
                return
            }
            // Transport bookkeeping on the socket thread, so credits never wait for a busy consumer.
            var own = true
            when (msg) {
                is HelloMessage -> onServerHello(webSocket, msg)
                is PerceptionFrame -> own = onWave1(msg, now)
                is PerceptionUpdate -> {
                    own = isOwn(msg.sessionId)
                    if (own) updateRate.mark(now)
                }
                is SkipMessage -> {
                    // Skips are sent to the uplinking client only, so they are always ours (a
                    // sessionReset skip carries the NEW session's id).
                    skips.incrementAndGet()
                    val id = msg.frameId
                    if (id != null && !credits.release(id)) lateAnswers.incrementAndGet()
                }
                is PongMessage -> msg.clientTimeNs?.let { rttMs = (now - it) / 1e6 }
                is ErrorMessage -> {
                    serverError = listOfNotNull(msg.code ?: "error", msg.message, if (msg.fatal) "(fatal)" else null).joinToString(" ")
                    if (msg.code == ErrorMessage.NOT_UPLINK_CLIENT) markTakenOver()
                }
                is NavigationPacketMessage -> onNavigation(msg, now)
                // Sent to the searching client only; an answer to an older search is superseded.
                is NavigationPlacesMessage -> if (msg.requestId != null && msg.requestId == newestSearchId) _places.value = msg
                else -> Unit
            }
            if (msg is HelloMessage || msg is PerceptionFrame || msg is PerceptionUpdate || msg is StatsMessage) {
                inbound.trySend(Inbound(msg, now, nowMs, own))
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit // server sends text only

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            done.complete("closed by server ($code${if (reason.isNotEmpty()) " $reason" else ""})")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            done.complete("${t::class.simpleName}: ${t.message}")
        }
    }

    /** Socket thread: session / role bookkeeping of a `perception.hello`. */
    private fun onServerHello(webSocket: WebSocket, msg: HelloMessage) {
        if (msg.sessionId != lastHelloSessionId) serverError = null // an error stays visible until the session changes
        lastHelloSessionId = msg.sessionId
        credits.maxInFlight = msg.uplink?.maxInFlight ?: config.defaultMaxInFlight
        serverRole = msg.role
        if (msg.isWatcher) {
            // Another client's session (or no session yet: the first hello on a connection comes
            // before ours is processed). Only a demotion from controller counts as "taken over".
            if (controllerOnConnection) markTakenOver()
            ownSessionId = null
        } else {
            controllerOnConnection = msg.isController
            takenOver = false
            ownSessionId = msg.sessionId
        }
        // The server advertises its uplink in every mode (it can switch on client.hello); camera
        // frames only make sense while it runs a live session that this client controls.
        uplinkAllowed = msg.acceptsSdc1Uplink && (msg.mode == null || msg.mode == PerceptionMode.LIVE) && !msg.isWatcher
        helloOnConnection = true
        _serverHello.value = msg
        // The other controller left and the server is idle: take the session back.
        if (takenOver && msg.isWatcher && msg.isIdle && config.reclaimWhenIdle && !closed) sendHello(webSocket, clock())
    }

    /** Another client controls the session: stop uplinking and drop latency samples of our old stream. */
    private fun markTakenOver() {
        takenOver = true
        controllerOnConnection = false
        uplinkAllowed = false
        ownSessionId = null
        captureToResult.clear()
        simLead.clear()
    }

    /**
     * True when a live / sim result belongs to our stream: the session we control (video: every
     * client watches the laptop's clip; null = a server without session ids).
     */
    private fun isOwn(sessionId: String?): Boolean =
        mode == PerceptionMode.VIDEO || sessionId == null || sessionId == ownSessionId

    /** Socket thread: transport bookkeeping of a wave-1 frame. Returns [isOwn]. */
    private fun onWave1(frame: PerceptionFrame, now: Long): Boolean {
        if ((frame.wave ?: 1) != 1) return true
        // Another controller's result: its echo ids are ITS frame counter (they collide with ours)
        // and its captureTimeNs is on its clock, so it must not release our credits or feed latency.
        if (!isOwn(frame.sessionId)) return false
        resultRate.mark(now)
        frame.echo?.let { e ->
            if (!credits.release(e.frameId)) lateAnswers.incrementAndGet()
            captureToResult.add((now - e.captureTimeNs) / 1e6)
        }
        if (mode == PerceptionMode.SIM) {
            playback.estimate(now)?.let { p ->
                simLead.add((frame.ptsSeconds - p) * 1000.0)
                if (frame.ptsSeconds < p - config.simMaxLagSeconds) simLate.incrementAndGet()
            }
        }
        return true
    }

    private fun onNavigation(msg: NavigationPacketMessage, now: Long) {
        // The server replays its newest packet to every new client, whatever mode it was made for: sim packets
        // carry media time, live packets do not. A packet of the other mode would show another trip's route.
        val m = mode
        if ((m == PerceptionMode.LIVE && msg.ptsSeconds != null) || (m == PerceptionMode.SIM && msg.ptsSeconds == null)) {
            ignoredNavPackets.incrementAndGet()
            return
        }
        val mapped = NavigationMapper.toNavigationState(msg, config.inferLaneSideFromManeuver, egoSpeed)
        synchronized(navLock) {
            val seq = navPackets.incrementAndGet()
            _navigation.value = NavigationUpdate.from(msg, now, seq)
            packetNavigation = mapped
            if (manualNavigation == null) navigationInput.value = mapped
        }
    }

    private suspend fun processLoop() {
        for (item in inbound) {
            synchronized(worldLock) { handle(item) }
        }
    }

    /** Runs under [worldLock]: the only place (with [tick]) that touches the WorldModel. */
    private fun handle(item: Inbound) {
        val sim = mode == PerceptionMode.SIM
        when (val m = item.message) {
            is HelloMessage -> {
                worldModel.onHello(m)
                if (m.sessionId != null && m.sessionId != lastSessionId) {
                    lastSessionId = m.sessionId
                    simBuffer.clear(); lastSimPublished = null
                }
                if (!sim) _world.value = worldModel.snapshot.value
            }
            is PerceptionFrame -> {
                if ((m.wave ?: 1) != 1 || !item.own) return // not ours: [world] goes stale instead
                val s = worldModel.update(m, item.receivedMs, item.receivedNs)
                if (sim) simBuffer.put(s) else _world.value = s
            }
            is PerceptionUpdate -> {
                if (!item.own) return
                val s = worldModel.applyUpdate(m, item.receivedMs, item.receivedNs)
                if (sim) simBuffer.put(s) else _world.value = s
            }
            is StatsMessage -> {
                worldModel.onStats(m)
                if (!sim) _world.value = worldModel.snapshot.value
            }
            else -> Unit
        }
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive && !closed) {
            delay(config.tickMs)
            tick(clock())
        }
    }

    private fun tick(now: Long) {
        val expired = credits.sweep(now)
        if (expired > 0) creditTimeouts.addAndGet(expired.toLong())
        val connected = state == LinkState.CONNECTED
        synchronized(worldLock) {
            if (mode == PerceptionMode.SIM) publishSim(now, connected) else _world.value = worldModel.setLinkUp(connected, now)
        }
        synchronized(navLock) {
            val nav = _navigation.value
            if (nav != null && !nav.stale && now - nav.receivedAtNs > config.navigationStaleAfterMs * 1_000_000L) {
                _navigation.value = nav.copy(stale = true)
                if (manualNavigation == null) navigationInput.value = null
            }
        }
        val ws = socket
        val last = lastPingNs
        if (ws != null && (last == null || now - last >= config.pingIntervalMs * 1_000_000L)) {
            lastPingNs = now
            ws.send(PerceptionCodec.encodeClient(ClientPing(now)))
        }
        publishLink(now)
    }

    /** Sim: [world] follows the estimated playback position (called under [worldLock]). */
    private fun publishSim(now: Long, connected: Boolean) {
        val p = playback.estimate(now)
        val last = lastSimPublished
        val out = if (p == null) {
            (last ?: WorldSnapshot.EMPTY).copy(perceptionStale = true)
        } else {
            val sel = simBuffer.select(p, config.simMaxLagSeconds)
            simBuffer.trimBefore(p - config.simKeepBehindSeconds)
            when {
                sel != null -> { lastSimPublished = sel; sel.copy(perceptionStale = !connected) }
                last != null && p - last.ptsSeconds in -0.05..config.simStaleAfterSeconds -> last.copy(perceptionStale = !connected)
                else -> (last ?: WorldSnapshot.EMPTY).copy(perceptionStale = true)
            }
        }
        _world.value = out
    }

    private fun checkClock(captureTimeNs: Long, now: Long) {
        val ageMs = (now - captureTimeNs) / 1e6
        clockWarning = if (ageMs < -50.0 || ageMs > 5_000.0) {
            "captureTimeNs is ${ageMs.roundToLong()} ms from the bridge clock: set BridgeConfig.clockNs to the camera timestamp's time base"
        } else {
            null
        }
    }

    private fun publishLink(now: Long) {
        val hello = _serverHello.value
        val connected = state == LinkState.CONNECTED
        _link.value = LinkStatus(
            state = state,
            url = url,
            mode = mode,
            serverMode = hello?.mode,
            sessionId = hello?.sessionId,
            serverReady = connected && helloOnConnection,
            role = if (connected) serverRole else null,
            takenOver = connected && takenOver,
            perceptionStale = _world.value.perceptionStale,
            rttMs = rttMs?.let(::r1),
            captureToResultMsP50 = captureToResult.percentile(50.0)?.let(::r1),
            captureToResultMsP95 = captureToResult.percentile(95.0)?.let(::r1),
            resultFps = resultRate.rate(now)?.let(::r1),
            updateFps = updateRate.rate(now)?.let(::r1),
            uplinkFps = uplinkRate.rate(now)?.let(::r1),
            maxInFlight = credits.maxInFlight,
            inFlight = credits.inFlightCount,
            credits = credits.available,
            framesOffered = framesOffered.get(),
            framesSent = framesSent.get(),
            framesDroppedNoCredit = droppedNoCredit.get(),
            framesDroppedNotReady = droppedNotReady.get(),
            skips = skips.get(),
            creditTimeouts = creditTimeouts.get(),
            lateAnswers = lateAnswers.get(),
            simLeadMsP50 = simLead.percentile(50.0)?.let(::r1),
            simLeadMsMin = simLead.percentile(0.0)?.let(::r1),
            simLateResults = simLate.get(),
            simBuffered = simBuffer.size,
            playbackPts = playback.estimate(now)?.let(::r3),
            navigationPackets = navPackets.get(),
            navigationAgeMs = _navigation.value?.ageMs(now)?.let(::r1),
            serverNavigationMode = hello?.navigationMode,
            serverNavigationAvailable = hello?.navigationAvailable,
            serverNavigationError = hello?.navigationError,
            tripStatesSent = tripStatesSent.get(),
            reconnects = reconnects.get(),
            decodeErrors = decodeErrors.get(),
            lastError = lastError,
            serverError = serverError,
            clockWarning = clockWarning,
        )
    }

    private companion object {
        fun r1(x: Double) = Math.round(x * 10.0) / 10.0
        fun r3(x: Double) = Math.round(x * 1000.0) / 1000.0
    }
}
