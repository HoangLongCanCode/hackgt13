package com.drivingassist.glass.perception

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.drivingassist.copilot.bridge.BridgeConfig
import com.drivingassist.copilot.bridge.DisplayText
import com.drivingassist.copilot.bridge.LinkState
import com.drivingassist.copilot.bridge.PerceptionBridge
import com.drivingassist.copilot.perception.ClientHello
import com.drivingassist.copilot.perception.DeviceInfo
import com.drivingassist.copilot.perception.NavigationHint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/** Implemented by vision sources that talk to the laptop; MainActivity reaches the runtime through it. */
interface BridgeBacked {
    val runtime: PerceptionRuntime
}

/** Implemented by vision sources that want a specific camera stream size from [com.drivingassist.glass.CameraPreview]. */
interface CameraResolutionHint {
    /**
     * Analysis target size, null = CameraX default. The preview gets the same aspect ratio (so the
     * analysed image has the preview's field of view and boxes line up) at CameraX's preview size.
     */
    val preferredCameraResolution: android.util.Size?
}

/** What the status chip shows. [line2] is the top alert / problem, null when there is nothing to say. */
data class BridgeStatusUi(val line1: String, val line2: String? = null, val level: Level = Level.OK) {
    enum class Level { OK, WARN, ERROR }

    companion object {
        val MOCK = BridgeStatusUi("MOCK")
    }
}

/**
 * Owns the one [PerceptionBridge] of a LIVE / SIM session (shared by the vision source, the route
 * source and the GPS feeder), its coroutine scope and the status-chip text. Created by
 * [PerceptionFactory]; started by the vision source's `start()`, closed by its `stop()` (ViewModel
 * lifetime). The host activity reports its visibility ([onHostStarted] / [onHostStopped], from
 * [PerceptionHostEffects]): while it is stopped the overlay mapping loops idle and GPS is paused.
 * The WebSocket stays open (this tablet stays the laptop's controller) until the ViewModel is cleared.
 */
class PerceptionRuntime(context: Context, val config: PerceptionConfig) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Bridge clock: `elapsedRealtimeNanos`. CameraX timestamps use it when the sensor's timestamp
     * source is REALTIME; [toBridgeClock] converts the `System.nanoTime` base otherwise.
     */
    val clockNs: () -> Long = SystemClock::elapsedRealtimeNanos

    val bridge = PerceptionBridge(config.serverUrl, scope, BridgeConfig(clockNs = clockNs))

    val device = DeviceInfo(Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE)
    val clientId: String = "glass-" + Build.MODEL.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
    val navigationHint: NavigationHint =
        if (!config.navEnabled) NavigationHint.OFF else if (config.source == PerceptionSource.SIM) NavigationHint.SIM else NavigationHint.LIVE

    @Volatile private var problemText: String? = config.warning
    @Volatile private var problemAtMs: Long = SystemClock.elapsedRealtime()

    /**
     * A problem to show in the chip (missing sim clip, player error, location denied, ...). For
     * [PROBLEM_PRIORITY_MS] after it is set it beats everything; after that a Driving Context alert
     * (e.g. "TOO CLOSE 4 m") takes the line and the problem shows only when there is no alert.
     */
    var problem: String?
        get() = problemText
        set(value) {
            problemText = value
            problemAtMs = SystemClock.elapsedRealtime()
        }

    /** Clears [problem] if it starts with [prefix] (e.g. a player error once playback works again). */
    fun clearProblem(prefix: String) {
        if (problemText?.startsWith(prefix) == true) problem = null
    }

    /** False while the host activity is stopped (background / screen off): loops idle, GPS paused. */
    @Volatile var hostStarted: Boolean = true
        private set

    private val _status = MutableStateFlow(BridgeStatusUi("${config.source.name}  connecting"))
    val status: StateFlow<BridgeStatusUi> = _status.asStateFlow()

    /** LIVE + navigation: the device GPS must be fed to the phase1 relay (after the permission is granted). */
    val wantsLocation: Boolean get() = config.source == PerceptionSource.LIVE && config.navEnabled

    @Volatile private var started = false
    @Volatile private var closed = false

    // Main thread only: GPS feeder state.
    private var locationFeeder: LocationFeeder? = null
    private var locationWanted = false
    private var locationRetry: Job? = null

    fun start(hello: ClientHello) {
        if (closed) return
        bridge.connect(hello)
        if (started) return
        started = true
        scope.launch {
            while (isActive) {
                _status.value = computeStatus()
                delay(250)
            }
        }
    }

    /**
     * Main thread (LocationManager callbacks run on the main looper), after the location permission
     * was granted. Safe to call more than once. When no provider is enabled (Location switched off)
     * it retries every [LOCATION_RETRY_MS] until one is, and it pauses while the host is stopped.
     */
    fun startLocationUpdates() {
        if (!wantsLocation || closed) return
        locationWanted = true
        tryStartLocation()
        if (locationRetry == null) {
            locationRetry = scope.launch(Dispatchers.Main) {
                while (isActive) {
                    delay(LOCATION_RETRY_MS)
                    tryStartLocation()
                }
            }
        }
    }

    private fun tryStartLocation() {
        if (!locationWanted || closed || !hostStarted || locationFeeder != null) return
        val feeder = LocationFeeder(appContext, bridge)
        if (feeder.start()) {
            locationFeeder = feeder
            clearProblem(NO_LOCATION)
        } else if (problemText?.startsWith(NO_LOCATION) != true) {
            problem = "$NO_LOCATION: turn Location on for live navigation"
        }
    }

    /** Main thread: the host activity became visible (ON_START). */
    fun onHostStarted() {
        hostStarted = true
        tryStartLocation()
    }

    /** Main thread: the host activity is no longer visible (ON_STOP): pause GPS, idle the overlay loops. */
    fun onHostStopped() {
        hostStarted = false
        runCatching { locationFeeder?.stop() }
        locationFeeder = null
    }

    fun close() {
        if (closed) return
        closed = true
        locationRetry?.cancel()
        runCatching { locationFeeder?.stop() }
        locationFeeder = null
        bridge.close()
        scope.cancel()
    }

    /**
     * CameraX `imageInfo.timestamp` -> bridge clock. The camera uses either the elapsedRealtime or
     * the System.nanoTime (uptime) base depending on `SENSOR_INFO_TIMESTAMP_SOURCE`; whichever the
     * timestamp is closer to is its base.
     */
    fun toBridgeClock(cameraTimestampNs: Long): Long {
        val realtime = SystemClock.elapsedRealtimeNanos()
        val mono = System.nanoTime()
        return if (abs(realtime - cameraTimestampNs) <= abs(mono - cameraTimestampNs)) cameraTimestampNs
        else cameraTimestampNs + (realtime - mono)
    }

    /**
     * Back camera focal length in pixels per pixel of sensor-oriented image width
     * (`focalLengthMm / sensorWidthMm`). Multiply by the uplinked buffer width for `focalPx`.
     * CameraX 16:9 output of a 4:3 sensor keeps the full sensor width, so this holds for 16:9 too.
     */
    val focalPerBufferWidth: Double? by lazy {
        runCatching {
            val cm = appContext.getSystemService(CameraManager::class.java) ?: return@runCatching null
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return@runCatching null
            val ch = cm.getCameraCharacteristics(id)
            val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return@runCatching null
            val sensor = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return@runCatching null
            if (sensor.width <= 0f) null else (focal / sensor.width).toDouble()
        }.onFailure { Log.w(TAG, "camera intrinsics unavailable", it) }.getOrNull()
    }

    private fun computeStatus(): BridgeStatusUi {
        val k = bridge.link.value
        val sb = StringBuilder(config.source.name)
        if (config.savedKeys.isNotEmpty()) sb.append(" (saved)")
        var level = BridgeStatusUi.Level.OK
        when {
            k.state != LinkState.CONNECTED -> { sb.append("  DISCONNECTED"); level = BridgeStatusUi.Level.ERROR }
            !k.serverReady -> { sb.append("  WAITING FOR SERVER"); level = BridgeStatusUi.Level.WARN }
            k.takenOver -> { sb.append("  TAKEN OVER"); level = BridgeStatusUi.Level.WARN }
            else -> {
                sb.append("  ").append(k.resultFps?.let { f1(it) } ?: "-").append(" fps")
                when (config.source) {
                    PerceptionSource.LIVE -> k.captureToResultMsP50?.let { sb.append("  ").append(f0(it)).append(" ms") }
                    PerceptionSource.SIM -> k.simLeadMsP50?.let { sb.append("  lead ").append(f0(it)).append(" ms") }
                    else -> Unit
                }
                if (k.perceptionStale) { sb.append("  STALE"); level = BridgeStatusUi.Level.WARN }
            }
        }
        if (config.navEnabled) {
            val nav = bridge.navigation.value
            sb.append(
                when {
                    k.serverNavigationAvailable == false || k.serverNavigationMode == "off" -> "  NAV off on laptop"
                    nav == null -> "  NAV --"
                    nav.stale -> "  NAV STALE"
                    nav.routeState == null -> "  NAV no route"
                    else -> "  NAV ok"
                },
            )
        }
        val alert = DisplayText.topAlert(bridge.context.value)
        val p = problemText
        val freshProblem = p?.takeIf { SystemClock.elapsedRealtime() - problemAtMs < PROBLEM_PRIORITY_MS }
        val line2 = freshProblem
            ?: alert
            ?: p
            ?: (if (k.takenOver) "another client controls the laptop; back when it leaves" else null)
            ?: k.serverError
            ?: (if (k.state != LinkState.CONNECTED) k.lastError?.take(70) else null)
            ?: k.clockWarning?.let { "clock base mismatch" }
        if (alert != null && line2 == alert && level == BridgeStatusUi.Level.OK) level = BridgeStatusUi.Level.WARN
        return BridgeStatusUi(sb.toString(), line2, level)
    }

    private companion object {
        const val TAG = "PerceptionRuntime"
        const val PROBLEM_PRIORITY_MS = 10_000L
        const val LOCATION_RETRY_MS = 5_000L
        const val NO_LOCATION = "no location provider"
        fun f0(x: Double) = String.format(Locale.ROOT, "%.0f", x)
        fun f1(x: Double) = String.format(Locale.ROOT, "%.1f", x)
    }
}
