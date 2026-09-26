package com.drivingassist.spatialcopilot.session

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.drivingassist.copilot.bridge.BridgeConfig
import com.drivingassist.copilot.bridge.LinkStatus
import com.drivingassist.copilot.bridge.PerceptionBridge
import com.drivingassist.copilot.context.DrivingContext
import com.drivingassist.copilot.context.DrivingContextConfig
import com.drivingassist.copilot.context.DrivingContextEngine
import com.drivingassist.copilot.context.DrivingEvent
import com.drivingassist.copilot.context.Units
import com.drivingassist.copilot.context.WorldSnapshot
import com.drivingassist.copilot.perception.ClientCamera
import com.drivingassist.copilot.perception.ClientHello
import com.drivingassist.copilot.perception.DeviceInfo
import com.drivingassist.copilot.perception.NavigationHint
import com.drivingassist.spatialcopilot.nav.DemoDrive
import com.drivingassist.spatialcopilot.nav.RouteGuide
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * One run of the copilot with fixed [settings] (a settings change builds a new session). Owns the single
 * [PerceptionBridge] (LIVE / SIM), the camera uplink (LIVE), the sim player (SIM), the GPS feeder (LIVE)
 * or the scripted [DemoDrive] (DEMO, with its own local DrivingContextEngine).
 *
 * Everything the UI reads is a StateFlow; the per-frame world comes from [displayWorld]. Created and
 * closed on the main thread (ExoPlayer and LocationManager want it).
 */
class CopilotSession(context: Context, val settings: AppSettings) : AutoCloseable {
    private val app = context.applicationContext
    val clockNs: () -> Long = SystemClock::elapsedRealtimeNanos
    /** A failure inside the bridge or a loop is shown, never a crash. */
    private val onError = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "session coroutine failed", e)
        _problem.value = "Error: ${e.message ?: e.javaClass.simpleName}"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + onError)

    private val drivingConfig = DrivingContextConfig(
        navigationUnits = Units.METRIC,
        criticalMinEgoSpeedMps = if (settings.gateCriticalBySpeed) SPEED_GATE_MPS else null,
    )

    val bridge: PerceptionBridge? = if (settings.mode == SourceMode.DEMO) null else
        PerceptionBridge(settings.serverUrl, scope, BridgeConfig(clockNs = clockNs, drivingContext = drivingConfig))

    private val demoEngine: DrivingContextEngine? = if (settings.mode == SourceMode.DEMO) DrivingContextEngine(drivingConfig) else null

    private val _problem = MutableStateFlow<String?>(null)

    /** A problem to show (missing sim clip, player / camera error, no location provider), null = none. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    val context: StateFlow<DrivingContext> = bridge?.context ?: demoEngine!!.context

    /** Driving Context edges (no replay: the audio layer collects them before [start]). */
    val events: SharedFlow<DrivingEvent> = bridge?.events ?: demoEngine!!.events

    val link: StateFlow<LinkStatus>? = bridge?.link

    private val _route = MutableStateFlow<RouteGuide?>(null)

    /** The route as phase1 last described it (DEMO: the scripted placeholder). */
    val route: StateFlow<RouteGuide?> = _route.asStateFlow()

    val sim: SimPlayback? = if (settings.mode == SourceMode.SIM) SimPlayback(app, bridge!!, settings.simVideoId) { _problem.value = it } else null

    val camera: CameraUplink? = if (settings.mode == SourceMode.LIVE) CameraUplink(bridge!!, ::onUprightSize) { _problem.value = it } else null

    private var location: LocationFeeder? = null
    private var locationJob: Job? = null
    private val _gps = MutableStateFlow<GpsFix?>(null)

    /** Newest GPS fix (LIVE), for the degraded-navigation banner. */
    val gps: StateFlow<GpsFix?> = _gps.asStateFlow()

    private val _gpsAvailable = MutableStateFlow<Boolean?>(null)

    /** LIVE: false when no location provider / permission, true once updates were requested, null before. */
    val gpsAvailable: StateFlow<Boolean?> = _gpsAvailable.asStateFlow()

    private val device = DeviceInfo(Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE)
    private val clientId = "tablet-" + Build.MODEL.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
    private val startedNs = clockNs()
    @Volatile private var closed = false

    /** DEMO time (seconds since the session started). */
    fun demoSeconds(): Double = (clockNs() - startedNs) / 1e9

    fun start() {
        when (settings.mode) {
            SourceMode.LIVE -> bridge!!.connect(liveHello(960, 540, 960))
            SourceMode.SIM -> {
                bridge!!.connect(ClientHello.sim(clientId, settings.simVideoId, device, NavigationHint.SIM))
                sim!!.start()
            }
            SourceMode.DEMO -> scope.launch { demoLoop() }
        }
        bridge?.let { b -> scope.launch { b.navigation.collect { _route.value = RouteGuide.from(it) } } }
    }

    /**
     * The world to draw at this display frame: LIVE moves the newest result from its capture time to now
     * (boxes by track velocity, distances by relative speed); SIM picks the result for the frame on screen;
     * DEMO renders the script.
     */
    fun displayWorld(): WorldSnapshot {
        val b = bridge
        return when (settings.mode) {
            SourceMode.LIVE -> b!!.predictedAt(clockNs())
            SourceMode.SIM -> b!!.resultForPts(sim!!.positionSeconds) ?: b.world.value
            SourceMode.DEMO -> DemoDrive.world(demoSeconds(), (demoSeconds() * 15).toLong())
        }
    }

    /** Distance to the maneuver at this display frame (moved on from the packet by phase1's speed). */
    fun routeDistanceNow(route: RouteGuide?): Double? = when (settings.mode) {
        SourceMode.SIM -> route?.distanceAt(clockNs(), sim?.positionSeconds)
        SourceMode.DEMO -> DemoDrive.exitDistance(demoSeconds())
        SourceMode.LIVE -> route?.distanceAt(clockNs())
    }

    /** LIVE: main thread, after the location permission was granted. */
    fun startLocation() {
        if (settings.mode != SourceMode.LIVE || closed || location != null) return
        val feeder = LocationFeeder(app, bridge!!)
        if (feeder.start()) {
            location = feeder
            _gpsAvailable.value = true
            locationJob = scope.launch { feeder.fix.collect { _gps.value = it } }
        } else {
            _gpsAvailable.value = false
        }
    }

    fun stopLocation() {
        locationJob?.cancel()
        locationJob = null
        runCatching { location?.stop() }
        location = null
    }

    /** Camera bind failures and the like (null clears). */
    fun reportProblem(p: String?) {
        _problem.value = p
    }

    /** Takes the laptop session back from another client right away. */
    fun reclaim() {
        bridge?.reclaim()
    }

    override fun close() {
        if (closed) return
        closed = true
        stopLocation()
        sim?.close()
        bridge?.close()
        scope.cancel()
    }

    private suspend fun demoLoop() {
        val engine = demoEngine ?: return
        var seq = 0L
        while (scope.isActive) {
            val t = demoSeconds()
            engine.evaluate(DemoDrive.world(t, seq++), DemoDrive.navigation(t))
            _route.value = DemoDrive.route(t, clockNs())
            delay(66)
        }
    }

    private fun onUprightSize(width: Int, height: Int, bufferWidth: Int) {
        bridge?.connect(liveHello(width, height, bufferWidth))
    }

    private fun liveHello(width: Int, height: Int, bufferWidth: Int): ClientHello = ClientHello.live(
        clientId = clientId,
        camera = ClientCamera(
            imageWidth = width,
            imageHeight = height,
            // Aimed at a monitor, the picture is the recorded dash cam's, not this lens's: send no focal
            // and let the server use its dash-cam default. On a real windshield mount, send the lens.
            focalPx = if (settings.cameraOnMonitor) null else focalPerBufferWidth?.let { Math.round(it * bufferWidth * 10.0) / 10.0 },
            principalPoint = null,
            mountHeightMeters = settings.mountHeightMeters,
            pitchDegrees = null,
            lensFacing = "back",
            stabilization = false,
        ),
        device = device,
        navigation = NavigationHint.LIVE,
    )

    /** Back camera focal length per pixel of sensor-oriented image width (focal mm / sensor width mm). */
    private val focalPerBufferWidth: Double? by lazy {
        runCatching {
            val cm = app.getSystemService(CameraManager::class.java) ?: return@runCatching null
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return@runCatching null
            val ch = cm.getCameraCharacteristics(id)
            val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return@runCatching null
            val sensor = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return@runCatching null
            if (sensor.width <= 0f) null else (focal / sensor.width).toDouble()
        }.onFailure { Log.w(TAG, "camera intrinsics unavailable", it) }.getOrNull()
    }

    private companion object {
        const val TAG = "CopilotSession"
        const val SPEED_GATE_MPS = 1.5
    }
}
