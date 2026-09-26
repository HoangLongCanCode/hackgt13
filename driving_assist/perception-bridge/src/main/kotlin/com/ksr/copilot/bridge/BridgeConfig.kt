package com.ksr.copilot.bridge

import com.ksr.copilot.context.DrivingContextConfig
import com.ksr.copilot.context.Units
import com.ksr.copilot.context.WorldModelConfig
import com.ksr.copilot.perception.PerceptionMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** Exponential reconnect backoff: initial, x2 per consecutive failure, capped. */
data class ReconnectPolicy(
    val initialDelayMs: Long = 250,
    val maxDelayMs: Long = 3_000,
) {
    fun delayMs(consecutiveFailures: Int): Long {
        val shift = consecutiveFailures.coerceIn(0, 20)
        return (initialDelayMs shl shift).coerceAtMost(maxDelayMs)
    }
}

/**
 * [PerceptionBridge] tuning. Defaults match PROTOCOL_v2.
 *
 * @param clockNs monotonic clock in the SAME time base as the `captureTimeNs` passed to
 *   `offerCameraFrame` (Android: `SystemClock::elapsedRealtimeNanos`, and convert CameraX timestamps
 *   that use the `System.nanoTime` base; see PERCEPTION_INTEGRATION.md). Latency, staleness and
 *   prediction are all measured on this clock.
 */
data class BridgeConfig(
    /** Shared client (e.g. the app's). Null = the bridge builds one ([defaultOkHttpClient]) and shuts it down on close. */
    val okHttpClient: OkHttpClient? = null,
    val reconnect: ReconnectPolicy = ReconnectPolicy(initialDelayMs = 250, maxDelayMs = 3_000),
    /** `client.ping` period (RTT). */
    val pingIntervalMs: Long = 1_000,
    /** Housekeeping period: credit timeouts, staleness, sim world updates, link status. */
    val tickMs: Long = 50,
    /** A credit whose answer never came returns after this long. */
    val creditTimeoutMs: Long = 1_000,
    /** Used until the server hello announces `uplink.maxInFlight`. */
    val defaultMaxInFlight: Int = 2,
    /** Live/video: newest result older than this (capture time) => perceptionStale. */
    val staleAfterMs: Long = 500,
    /** Sim: a result is used for playback position p when `p - 150 ms <= pts <= p`. */
    val simMaxLagSeconds: Double = 0.15,
    /** Sim: keep showing the last matched result for this much media time before going stale. */
    val simStaleAfterSeconds: Double = 0.5,
    /** Sim: buffered results older than playback minus this are dropped. */
    val simKeepBehindSeconds: Double = 2.0,
    /** Cap for `predictedAt()` extrapolation. */
    val maxPredictionMs: Double = 300.0,
    /** No `navigation.packet` for this long: [NavigationUpdate.stale] and the Driving Context drops the route. */
    val navigationStaleAfterMs: Long = 10_000,
    /** phase1 gave no required lane: infer LEFT/RIGHT lane guidance from the turn direction (close to the maneuver only). */
    val inferLaneSideFromManeuver: Boolean = true,
    /** Samples in the latency / lead windows (p50, p95). */
    val statsWindow: Int = 90,
    /** Inbound message buffer between the socket thread and the world coroutine (oldest dropped on overflow). */
    val inboundCapacity: Int = 512,
    val clockNs: () -> Long = System::nanoTime,
    val worldModel: WorldModelConfig = WorldModelConfig(staleAfterMs = 500),
    /** Metric navigation labels by default: phase1's spoken prompts use metres ("Turn right in 120 m."). */
    val drivingContext: DrivingContextConfig = DrivingContextConfig(navigationUnits = Units.METRIC),
    /** Where the bridge's coroutines run (never the main thread). */
    val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * After another client took the session over ([LinkStatus.takenOver]): re-send our hello by
     * itself as soon as the server goes idle (the other controller left). Off = stay a watcher until
     * [PerceptionBridge.reclaim] or a new hello.
     */
    val reclaimWhenIdle: Boolean = true,
)

enum class LinkState { IDLE, CONNECTING, CONNECTED, DISCONNECTED, CLOSED }

/**
 * Link health for a status chip / debug HUD (plan §33). All latencies are measured on the
 * tablet's own clock ([BridgeConfig.clockNs]); no clock sync with the laptop is needed.
 */
@Serializable
data class LinkStatus(
    val state: LinkState = LinkState.IDLE,
    val url: String = "",
    /** Mode this client asked for in `client.hello`. */
    val mode: PerceptionMode? = null,
    /** Mode announced by the server's `perception.hello`. */
    val serverMode: PerceptionMode? = null,
    val sessionId: String? = null,
    /** Connected AND the server's hello arrived on this connection. */
    val serverReady: Boolean = false,
    /** `perception.hello.role` for this client: "controller" / "watcher" (null = server without roles). */
    val role: String? = null,
    /**
     * Another client took the session over (its newer `client.hello` won): this bridge no longer
     * uplinks and shows no results until it is the controller again (idle server or `reclaim()`).
     */
    val takenOver: Boolean = false,
    /** Same flag as `world.value.perceptionStale`: show "road alerts paused" when true. */
    val perceptionStale: Boolean = true,
    /** client.ping -> perception.pong round trip. */
    val rttMs: Double? = null,
    /** Live: camera capture -> wave-1 result arrival (echo.captureTimeNs), p50 / p95 over the last frames. */
    val captureToResultMsP50: Double? = null,
    val captureToResultMsP95: Double? = null,
    /** Wave-1 results / s arriving. */
    val resultFps: Double? = null,
    /** Wave-2 updates / s arriving. */
    val updateFps: Double? = null,
    /** Camera frames / s sent. */
    val uplinkFps: Double? = null,
    val maxInFlight: Int = 0,
    val inFlight: Int = 0,
    /** Free credits = maxInFlight - inFlight. */
    val credits: Int = 0,
    val framesOffered: Long = 0,
    val framesSent: Long = 0,
    /** Offered while all credits were in use (expected: the camera runs faster than the GPU). */
    val framesDroppedNoCredit: Long = 0,
    /** Offered while not connected / server not ready / server not in live mode. */
    val framesDroppedNotReady: Long = 0,
    /** `perception.skip` answers (frame superseded on the server). */
    val skips: Long = 0,
    /** Credits returned by timeout (answer never came). */
    val creditTimeouts: Long = 0,
    /** Answers that arrived after their credit had timed out. */
    val lateAnswers: Long = 0,
    /** Sim: result pts minus playback position at arrival (ms of media time); positive = early (good). */
    val simLeadMsP50: Double? = null,
    val simLeadMsMin: Double? = null,
    /** Sim: results that arrived after their frame had already been shown (> 150 ms late). */
    val simLateResults: Long = 0,
    val simBuffered: Int = 0,
    val playbackPts: Double? = null,
    /** `navigation.packet`s received this bridge lifetime. */
    val navigationPackets: Long = 0,
    /** Age of the newest `navigation.packet` (client clock), null before the first one. */
    val navigationAgeMs: Double? = null,
    /** `client.trip_state` messages sent (live navigation). */
    val tripStatesSent: Long = 0,
    /** Server hello `navigation.mode` / `.available` / `.error`: whether the laptop runs the phase1 relay. */
    val serverNavigationMode: String? = null,
    val serverNavigationAvailable: Boolean? = null,
    val serverNavigationError: String? = null,
    val reconnects: Long = 0,
    val decodeErrors: Long = 0,
    /** Socket-level problem (connect failure, close reason, decode error). */
    val lastError: String? = null,
    /** Last `perception.error` from the server (e.g. "unknownVideo ..."), cleared by the next server hello. */
    val serverError: String? = null,
    /** Set when captureTimeNs does not look like it is on [BridgeConfig.clockNs] (wrong time base). */
    val clockWarning: String? = null,
)

/** Socket factory that disables Nagle: small JSON messages / frame tails must not wait for ACKs. */
internal class NoDelaySocketFactory(private val delegate: SocketFactory = getDefault()) : SocketFactory() {
    private fun Socket.noDelay(): Socket = apply { runCatching { tcpNoDelay = true } }
    override fun createSocket(): Socket = delegate.createSocket().noDelay()
    override fun createSocket(host: String, port: Int): Socket = delegate.createSocket(host, port).noDelay()
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        delegate.createSocket(host, port, localHost, localPort).noDelay()
    override fun createSocket(host: InetAddress, port: Int): Socket = delegate.createSocket(host, port).noDelay()
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        delegate.createSocket(address, port, localAddress, localPort).noDelay()
}

/** OkHttp client tuned for the bridge: short connect timeout, no read timeout, WS pings detect a dead link, TCP_NODELAY. */
fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .writeTimeout(5, TimeUnit.SECONDS)
    .pingInterval(2, TimeUnit.SECONDS)
    .socketFactory(NoDelaySocketFactory())
    .build()
