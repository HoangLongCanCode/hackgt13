package com.ksr.copilot.perception

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Protocol constants (`contracts/PROTOCOL_v2.md`). */
object Protocol {
    const val VERSION = 2
    const val DEFAULT_PORT = 8765
    const val PATH = "/perception"
    const val UPLINK_MAGIC = "KSR1"

    /** USB (`adb reverse tcp:8765 tcp:8765`): the tablet reaches the laptop on its own loopback. */
    const val USB_URL = "ws://127.0.0.1:$DEFAULT_PORT$PATH"

    fun lanUrl(laptopIp: String, port: Int = DEFAULT_PORT) = "ws://$laptopIp:$port$PATH"
}

/**
 * Everything the server can send on `ws://<laptop>:8765/perception`, selected by the `"type"`
 * field (see [PerceptionCodec]). Client -> server messages are [ClientMessage]s; the live camera
 * uplink is binary ([UplinkHeader] + JPEG).
 */
sealed interface PerceptionMessage

/** Demo mode (PROTOCOL_v2 "Modes"). */
@Serializable
enum class PerceptionMode {
    /** Laptop plays a clip on its own clock; results only (laptop-side testing). */
    @SerialName("video") VIDEO,

    /** Same clip on both devices; the tablet plays it and reports its position, the laptop analyses ahead. */
    @SerialName("sim") SIM,

    /** Tablet camera frames are uplinked as JPEG; results come back per frame. */
    @SerialName("live") LIVE;

    val wire: String get() = name.lowercase()
}

/**
 * `perception.hello`: once per session (on connect, and again when the server starts a new session).
 * Everything is optional and unknown keys are ignored, so v1 and v2 servers both decode.
 */
@Serializable
@SerialName(HelloMessage.TYPE)
data class HelloMessage(
    /** 2 for a PROTOCOL_v2 server, null for a v1 server. */
    val protocolVersion: Int? = null,
    val schemaVersion: Int? = null,
    val sessionId: String? = null,
    val mode: PerceptionMode? = null,
    /** Modes this server can switch to on `client.hello` (strings, so a new mode never breaks decoding). */
    val acceptedModes: List<String> = emptyList(),
    val source: Source? = null,
    val image: ImageSize? = null,
    val camera: HelloCamera? = null,
    val fps: Double? = null,
    val sourceFps: Double? = null,
    /** Live uplink parameters; null when the server does not accept camera frames. */
    val uplink: UplinkInfo? = null,
    /** Sim parameters; null outside sim mode. */
    val sim: SimInfo? = null,
    /** Model / licence list, free-form. */
    val models: JsonElement? = null,
    /** Block schedule, free-form. */
    val schedule: JsonElement? = null,
    /** Navigation relay status, e.g. `{"mode": "sim", "available": true, "error": null}`; servers may omit it. */
    val navigation: JsonElement? = null,
    /**
     * This client's role in the session: `"controller"` (its `client.hello` started it: it may uplink
     * camera frames / report playback, and the results are for its stream) or `"watcher"` (receives
     * the controller's results only). Null from servers that predate roles (treated as controller).
     */
    val role: String? = null,
) : PerceptionMessage {
    /** True when the server says another client controls the session ([role] == "watcher"). */
    val isWatcher: Boolean get() = role == ROLE_WATCHER

    /** True when the server says this client controls the session ([role] == "controller"). */
    val isController: Boolean get() = role == ROLE_CONTROLLER

    /** No session is running (the server's idle hello, `sessionId == "idle"`): a hello from any client may start one. */
    val isIdle: Boolean get() = sessionId == IDLE_SESSION_ID

    /** `navigation.mode` ("sim" / "live" / "off"), null when the server did not say. */
    val navigationMode: String? get() = navField("mode")?.takeIf { it.isString }?.content

    /** `navigation.available`: false = the laptop's phase1 relay is not running (no route will come). */
    val navigationAvailable: Boolean? get() = navField("available")?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

    /** `navigation.error`, e.g. why the relay failed to start. */
    val navigationError: String? get() = navField("error")?.takeIf { it.isString }?.content

    private fun navField(key: String): JsonPrimitive? = ((navigation as? JsonObject)?.get(key)) as? JsonPrimitive

    /** True when this server takes v2 `KSR1` camera frames (a v1 server's 8-byte uplink is not compatible). */
    val acceptsKsr1Uplink: Boolean
        get() {
            val v2 = protocolVersion?.let { it >= 2 } ?: (uplink?.header == Protocol.UPLINK_MAGIC)
            val offered = if (uplink != null) uplink.accepted != false else mode == PerceptionMode.LIVE
            return v2 && offered
        }

    companion object {
        const val TYPE = "perception.hello"
        const val ROLE_CONTROLLER = "controller"
        const val ROLE_WATCHER = "watcher"
        const val IDLE_SESSION_ID = "idle"
    }
}

/** Camera the server assumes (all optional, unlike the per-frame [Camera]). */
@Serializable
data class HelloCamera(
    val focalPx: Double? = null,
    val principalPoint: List<Double>? = null,
    val horizonY: Double? = null,
    val cameraHeightMeters: Double? = null,
)

/** `perception.hello.uplink`: credit-based flow control for the live camera uplink. */
@Serializable
data class UplinkInfo(
    /** Max frames the client may have without a wave-1 answer (frame with matching echo, or skip). */
    val maxInFlight: Int = 2,
    val preferredWidth: Int? = null,
    val preferredHeight: Int? = null,
    val jpegQuality: Int? = null,
    /** Header magic, `"KSR1"`. */
    val header: String? = null,
    /** v1 servers send `{"accepted": false}` when not in camera mode. */
    val accepted: Boolean? = null,
)

/** `perception.hello.sim`. */
@Serializable
data class SimInfo(
    /** How far ahead of the reported playback position the laptop analyses. */
    val lookaheadSeconds: Double? = null,
    /** Clip stems (video ids) the laptop has. */
    val videos: List<String> = emptyList(),
)

@Serializable
data class Percentiles(val p50: Double? = null, val p95: Double? = null)

/** `perception.stats`: ~1 Hz server health. All optional; v1 and v2 field sets both decode. */
@Serializable
@SerialName(StatsMessage.TYPE)
data class StatsMessage(
    val sessionId: String? = null,
    val serverTimeMs: Long? = null,
    val outputFps: Double? = null,
    val inputFps: Double? = null,
    /** v2 */
    val wave1ProcessingMs: Percentiles? = null,
    /** v2 */
    val wave2ProcessingMs: Percentiles? = null,
    val framesIn: Long? = null,
    val framesAnalysed: Long? = null,
    /** v2: uplink frames answered with `perception.skip`. */
    val framesSkipped: Long? = null,
    val framesDropped: Long? = null,
    val clients: Int? = null,
    /** v1 python server */
    val processingMs: Percentiles? = null,
    /** v1 synthetic samples */
    val droppedFrames: Long? = null,
    val processingMsP50: Double? = null,
    val processingMsP95: Double? = null,
) : PerceptionMessage {
    companion object {
        const val TYPE = "perception.stats"
    }
}

/** One wave-2 distance, keyed by the wave-1 track id. */
@Serializable
data class DistanceUpdate(
    val id: Int,
    val distanceMeters: Double? = null,
    val distanceMethod: String? = null,
    val distanceConfidence: Double? = null,
    /** + = right of the camera axis. */
    val lateralMeters: Double? = null,
)

/**
 * `perception.update` (wave 2): results of the slow blocks (distance, lanes, road, signs) for the
 * frame identified by [seq] / [frameIndex] / [ptsSeconds] / [echo], usually a few frames older than
 * the newest wave-1 frame. A block that did not run is omitted (null here); an empty list means
 * it ran and found nothing.
 */
@Serializable
@SerialName(PerceptionUpdate.TYPE)
data class PerceptionUpdate(
    val schemaVersion: Int = 2,
    val wave: Int = 2,
    val seq: Long,
    val frameIndex: Long? = null,
    val ptsSeconds: Double,
    val echo: Echo? = null,
    val sessionId: String? = null,
    val serverTimeMs: Long? = null,
    val processingMs: Double? = null,
    val distances: List<DistanceUpdate>? = null,
    val lanes: Lanes? = null,
    val road: Road? = null,
    val signs: List<Sign>? = null,
    val blocks: List<String> = emptyList(),
    val timingsMs: Map<String, Double> = emptyMap(),
) : PerceptionMessage {
    companion object {
        const val TYPE = "perception.update"
    }
}

/**
 * `perception.skip` (live): the uplinked frame [frameId] will not be analysed; returns its credit.
 * Reasons: superseded, decodeError, badHeader, notAccepted, sessionReset.
 */
@Serializable
@SerialName(SkipMessage.TYPE)
data class SkipMessage(
    val frameId: Long? = null,
    val reason: String? = null,
) : PerceptionMessage {
    companion object {
        const val TYPE = "perception.skip"
    }
}

/** `perception.pong`: answer to `client.ping`; [clientTimeNs] is echoed so RTT uses the client clock. */
@Serializable
@SerialName(PongMessage.TYPE)
data class PongMessage(
    val clientTimeNs: Long? = null,
    val serverTimeMs: Long? = null,
) : PerceptionMessage {
    companion object {
        const val TYPE = "perception.pong"
    }
}

/**
 * `perception.error`: the server rejected something (codes: badMessage, modeNotAvailable,
 * unknownVideo, notUplinkClient, internal). Surfaced in the bridge's `LinkStatus.serverError`.
 */
@Serializable
@SerialName(ErrorMessage.TYPE)
data class ErrorMessage(
    val code: String? = null,
    val message: String? = null,
    val fatal: Boolean = false,
    val detail: JsonElement? = null,
    val serverTimeMs: Long? = null,
) : PerceptionMessage {
    companion object {
        const val TYPE = "perception.error"

        /** Another client controls the session (it took over, or this client never sent a hello). */
        const val NOT_UPLINK_CLIENT = "notUplinkClient"
    }
}

/**
 * `navigation.packet` (PROTOCOL_v2 "Navigation"): the phase1 route engine's output, relayed by the
 * laptop on the same socket. Sim: for the current playback position (~2 Hz); live: after each
 * `client.trip_state`. Route logic stays in phase1: the tablet only displays [routeState] and may
 * use it (distance, required lane) for lane guidance in the Driving Context.
 */
@Serializable
@SerialName(NavigationPacketMessage.TYPE)
data class NavigationPacketMessage(
    val schemaVersion: Int = 2,
    val serverTimeMs: Long? = null,
    /** Sim: media time the packet was computed for. Null in live mode. */
    val ptsSeconds: Double? = null,
    /** Trip clock (phase1 `trip_state.timestampMs`) the packet was computed for. */
    val tripTimestampMs: Long? = null,
    /** The four AR fields (+ extras); null when phase1 has no active route. */
    val routeState: NavRouteState? = null,
    /** Verbatim phase1 `SpatialNavigationPacket` (free-form JSON). */
    val packet: JsonObject? = null,
) : PerceptionMessage {
    companion object {
        const val TYPE = "navigation.packet"
    }
}

/**
 * `navigation.packet.routeState`. [action] / [audio] / [ui] map 1:1 onto the AR app's
 * `RouteState(time, action, audio, ui)`:
 * - [action] = phase1 `activeManeuver.type`: GO_STRAIGHT, TURN_LEFT, TURN_RIGHT, KEEP_LEFT, KEEP_RIGHT,
 *   MERGE, EXIT_HIGHWAY, ARRIVE, START_ROUTE.
 * - [audio] = `audioInstructions[0].content`, [ui] = `spatialInstructions[0].type`
 *   (TURN_ARROW, LANE_ARROW, EXIT_MARKER, DISTANCE_LABEL, WARNING).
 */
@Serializable
data class NavRouteState(
    val action: String = "",
    val audio: String = "",
    val ui: String = "",
    /** Distance to the active maneuver. */
    val distanceMeters: Double? = null,
    val offRoute: Boolean? = null,
    val etaSeconds: Double? = null,
    val remainingDistanceMeters: Double? = null,
    /** phase1 `routeSemantics.requiredLane` (free text, e.g. "right", "2", "2-3"); a JSON number also decodes. */
    @Serializable(with = AnyPrimitiveAsStringSerializer::class)
    val requiredLane: String? = null,
    /** left | right | straight | merge | exit */
    val turnDirection: String? = null,
    val roadName: String? = null,
)

/** A message whose `type` this client does not know. Returned, not thrown, so newer servers still work. */
data class UnknownMessage(val type: String?, val raw: JsonObject) : PerceptionMessage
