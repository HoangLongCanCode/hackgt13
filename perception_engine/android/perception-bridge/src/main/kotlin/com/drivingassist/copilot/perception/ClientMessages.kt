@file:OptIn(ExperimentalSerializationApi::class)

package com.drivingassist.copilot.perception

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Client (tablet) -> server JSON text messages of PROTOCOL_v2. Encoded by
 * [PerceptionCodec.encodeClient] with the `"type"` discriminator, defaults and explicit nulls,
 * so the JSON looks exactly like the protocol examples.
 */
@Serializable
sealed interface ClientMessage

/** `client.hello`: first message after every (re)connect; required for `live` and `sim`. */
@Serializable
@SerialName(ClientHello.TYPE)
data class ClientHello(
    val protocolVersion: Int = Protocol.VERSION,
    /** Stable per device, e.g. "tab-s9-01". */
    val clientId: String,
    val device: DeviceInfo? = null,
    val mode: PerceptionMode,
    /** Live: intrinsics of the UPLINKED (upright) image. Nulls = server estimates/defaults. */
    val camera: ClientCamera? = null,
    /** Sim: which clip both devices play. */
    val sim: SimRequest? = null,
    /**
     * Optional hint `{"mode": "sim" | "live" | "off"}` (PROTOCOL_v2 Navigation); the server's CLI flags
     * decide what actually runs. Omitted from the JSON when null, so a plain hello matches the protocol example.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val navigation: NavigationHint? = null,
) : ClientMessage {
    companion object {
        const val TYPE = "client.hello"

        fun live(clientId: String, camera: ClientCamera, device: DeviceInfo? = null, navigation: NavigationHint? = null) =
            ClientHello(clientId = clientId, device = device, mode = PerceptionMode.LIVE, camera = camera, navigation = navigation)

        fun sim(clientId: String, videoId: String, device: DeviceInfo? = null, navigation: NavigationHint? = null) =
            ClientHello(clientId = clientId, device = device, mode = PerceptionMode.SIM, sim = SimRequest(videoId), navigation = navigation)

        fun video(clientId: String, device: DeviceInfo? = null, navigation: NavigationHint? = null) =
            ClientHello(clientId = clientId, device = device, mode = PerceptionMode.VIDEO, navigation = navigation)
    }
}

/** `client.hello.navigation`: which navigation feed the tablet expects ("sim", "live" or "off"). */
@Serializable
data class NavigationHint(val mode: String) {
    companion object {
        val SIM = NavigationHint("sim")
        val LIVE = NavigationHint("live")
        val OFF = NavigationHint("off")
    }
}

/** e.g. `DeviceInfo("samsung", "SM-X710", "16")` (Android: Build.MANUFACTURER / Build.MODEL / Build.VERSION.RELEASE). */
@Serializable
data class DeviceInfo(
    val manufacturer: String? = null,
    val model: String? = null,
    val osVersion: String? = null,
)

@Serializable
data class ClientCamera(
    val imageWidth: Int,
    val imageHeight: Int,
    val focalPx: Double? = null,
    /** [cx, cy] px at the uplinked resolution. */
    val principalPoint: List<Double>? = null,
    /** Camera height above the road, entered once in the app. */
    val mountHeightMeters: Double? = null,
    val pitchDegrees: Double? = null,
    /** "back" | "front" | "external" */
    val lensFacing: String? = "back",
    /** Must be false: video stabilization warps frames and breaks distance / lane geometry. */
    val stabilization: Boolean? = false,
)

@Serializable
data class SimRequest(
    /** File stem of a clip present on both devices, e.g. "b1ff4656-0435391e". */
    val videoId: String,
)

/** `client.playback` (sim): ~10 Hz and on every seek / pause / resume. */
@Serializable
@SerialName(ClientPlayback.TYPE)
data class ClientPlayback(
    val videoId: String,
    val ptsSeconds: Double,
    val playing: Boolean,
    val rate: Double = 1.0,
    val clientTimeNs: Long,
) : ClientMessage {
    companion object {
        const val TYPE = "client.playback"
    }
}

/** `client.ping` (~1 Hz); the server answers [PongMessage] echoing [clientTimeNs]. */
@Serializable
@SerialName(ClientPing.TYPE)
data class ClientPing(val clientTimeNs: Long) : ClientMessage {
    companion object {
        const val TYPE = "client.ping"
    }
}

/**
 * `client.destination` (live navigation): where to go, as typed by the user (a place or address). The laptop's
 * phase1 provider (mock or Google Geocoding + Routes) resolves it and builds the route from the next
 * `client.trip_state`; `perception.hello` `navigation.destination` echoes the current target.
 */
@Serializable
@SerialName(ClientDestination.TYPE)
data class ClientDestination(val query: String) : ClientMessage {
    companion object {
        const val TYPE = "client.destination"
        const val MAX_LENGTH = 200
    }
}

/** `[lat, lng]` in degrees (WGS84), phase1 `GeoCoordinate`. */
@Serializable
data class GeoPoint(val lat: Double, val lng: Double)

/**
 * `client.trip_state` (live navigation, ~1 Hz from the device GPS). Same field names as one line of
 * phase1 `trip_state.jsonl`, so recorded trips replay unchanged. [heading] and [speedMps] are
 * required numbers in the contract (`client.trip_state.schema.json`): 0 when the fix has none, like
 * the phase1 android-collector (a null in a replayed jsonl line decodes to 0 with `coerceInputValues`).
 */
@Serializable
@SerialName(ClientTripState.TYPE)
data class ClientTripState(
    /** Unix epoch ms of the fix (Android: `Location.time`). */
    val timestampMs: Long,
    val location: GeoPoint,
    /** Degrees clockwise from north (Android: `Location.bearing`), 0 when unknown. */
    val heading: Double = 0.0,
    /** m/s (Android: `Location.speed`), 0 when unknown. */
    val speedMps: Double = 0.0,
    val accuracyMeters: Double? = null,
) : ClientMessage {
    companion object {
        const val TYPE = "client.trip_state"
    }
}
