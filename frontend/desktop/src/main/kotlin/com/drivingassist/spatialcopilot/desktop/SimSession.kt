package com.drivingassist.spatialcopilot.desktop

import com.drivingassist.copilot.bridge.BridgeConfig
import com.drivingassist.copilot.bridge.LinkStatus
import com.drivingassist.copilot.bridge.PerceptionBridge
import com.drivingassist.copilot.context.DrivingContext
import com.drivingassist.copilot.context.DrivingContextConfig
import com.drivingassist.copilot.context.Units
import com.drivingassist.copilot.context.WorldSnapshot
import com.drivingassist.copilot.perception.ClientHello
import com.drivingassist.copilot.perception.DeviceInfo
import com.drivingassist.copilot.perception.NavigationHint
import com.drivingassist.spatialcopilot.nav.RouteGuide
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * The app's CopilotSession SIM path plus SimPlayback, on the desktop: one [PerceptionBridge] with a SIM hello for the
 * clip, `client.playback` from the viewer's [MediaClock] (~10 Hz and on every play / pause / seek), the route from
 * phase1's `navigation.packet`s, and the world to draw at a display frame: `resultForPts(position) ?: world`.
 * The Driving Context runs with the app's config (metric navigation, the TOO CLOSE speed gate on by default).
 */
class SimSession(val options: Options, val frames: FramePipe?) : AutoCloseable {
    val clockNs: () -> Long = System::nanoTime
    private val onError = CoroutineExceptionHandler { _, e -> _problem.value = "Error: ${e.message ?: e.javaClass.simpleName}" }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + onError)

    val drivingConfig = DrivingContextConfig(
        navigationUnits = Units.METRIC,
        criticalMinEgoSpeedMps = if (options.speedGate) SPEED_GATE_MPS else null,
    )

    val bridge = PerceptionBridge(options.url, scope, BridgeConfig(clockNs = clockNs, drivingContext = drivingConfig))
    val clock = MediaClock(clockNs)
    /** The clip the server analyses and the viewer shows ([switchVideo] changes it). */
    @Volatile var videoId: String = options.videoId
        private set

    private val _problem = MutableStateFlow<String?>(null)

    /** Missing clip, video pipe or loop failure (null = none). */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private val _route = MutableStateFlow<RouteGuide?>(null)
    val route: StateFlow<RouteGuide?> = _route.asStateFlow()

    val context: StateFlow<DrivingContext> get() = bridge.context
    val link: StateFlow<LinkStatus> get() = bridge.link

    @Volatile private var lastReportNs = 0L
    private val device = DeviceInfo("pc", "desktop-viewer", "jvm-" + System.getProperty("java.version"))

    /** Media time on screen now (the app's `sim.positionSeconds`). */
    val positionSeconds: Double get() = clock.position()
    val playing: Boolean get() = clock.playing

    fun reportProblem(p: String?) {
        _problem.value = p
    }

    /**
     * Connects and, like SimPlayback, presses play once the laptop is ready (at most [readyWaitMs]); [autoPlay] false
     * leaves the clock paused (offscreen runs start it themselves).
     */
    fun start(autoPlay: Boolean = true, readyWaitMs: Long = 5_000) {
        clock.seek(options.from ?: options.start)
        bridge.connect(ClientHello.sim(CLIENT_ID, videoId, device, NavigationHint.SIM))
        scope.launch { bridge.navigation.collect { _route.value = RouteGuide.from(it) } }
        scope.launch {
            report()
            withTimeoutOrNull(readyWaitMs) { bridge.link.first { it.serverReady } }
            if (autoPlay) play()
            while (isActive) {
                if (clock.atEnd() && clock.playing) onEnd()
                if (clockNs() - lastReportNs >= REPORT_NS) report()
                delay(16)
            }
        }
    }

    /** The app loops the clip (REPEAT_MODE_ONE): back to 0 at the end. */
    private fun onEnd() {
        if (options.offscreen) { pause(); return }
        seek(0.0)
    }

    fun play() {
        clock.play()
        report()
    }

    fun pause() {
        clock.pause()
        report()
    }

    fun togglePlay() = if (clock.playing) pause() else play()

    /** Jumps to [pts] (the pipe restarts there; the laptop takes a jump as a seek and analyses ahead of it again). */
    fun seek(pts: Double) {
        clock.seek(pts)
        frames?.start(clock.position())
        report()
    }

    /**
     * Plays another clip from the start, like picking a new video in the app's settings: a new SIM hello on the open
     * socket (the server starts a new session for it, with the clip's own navigation), the old route and results gone.
     */
    fun switchVideo(id: String, clip: File?) {
        videoId = id
        _route.value = null
        clock.durationS = null
        clock.seek(0.0)
        _problem.value = if (clip == null) "No clip for video id $id on this PC" else null
        bridge.connect(ClientHello.sim(CLIENT_ID, id, device, NavigationHint.SIM))
        frames?.open(clip, 0.0)
        report()
    }

    fun report() {
        lastReportNs = clockNs()
        bridge.reportPlayback(videoId, clock.position(), clock.playing, clock.rate)
    }

    /** The world to draw at this display frame: the result for the frame on screen (SIM). */
    fun displayWorld(): WorldSnapshot = bridge.resultForPts(clock.position()) ?: bridge.world.value

    /** Distance to the maneuver at this display frame (moved on from the packet by phase1's speed, media time). */
    fun routeDistanceNow(route: RouteGuide?): Double? = route?.distanceAt(clockNs(), clock.position())

    override fun close() {
        runCatching { bridge.close() }
        scope.cancel()
    }

    companion object {
        /** CopilotSession.SPEED_GATE_MPS. */
        const val SPEED_GATE_MPS = 1.5
        const val CLIENT_ID = "desktop-viewer"
        private const val REPORT_NS = 100_000_000L
    }
}
