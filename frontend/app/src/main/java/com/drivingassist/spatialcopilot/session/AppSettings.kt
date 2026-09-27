package com.drivingassist.spatialcopilot.session

import android.content.Context
import android.content.Intent
import com.drivingassist.copilot.perception.GeoPoint
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Where the road picture and the perception results come from. */
enum class SourceMode {
    /** Tablet camera (aimed at the road or at a monitor playing a drive) -> SDC1 JPEG uplink -> laptop. */
    LIVE,

    /** The same clip plays on the tablet and on the laptop; the laptop analyses ahead of playback. */
    SIM,

    /** No laptop: a scripted highway scene through the same Driving Context, AR and audio code. */
    DEMO;

    companion object {
        fun parse(value: String?): SourceMode? = entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}

/**
 * Everything the user can change. Saved in SharedPreferences; launch-intent extras override it for
 * that launch (and are saved), so a demo can be started from adb:
 *
 * ```
 * adb shell am start -S -n com.drivingassist.spatialcopilot/.MainActivity --es perception.source sim --es perception.video b1ff4656-0435391e
 * adb shell am start -S -n com.drivingassist.spatialcopilot/.MainActivity --es perception.source live --es perception.url ws://192.168.1.20:8765/perception
 * adb shell am start -S -n com.drivingassist.spatialcopilot/.MainActivity --ez perception.debug true
 * ```
 */
data class AppSettings(
    val mode: SourceMode = SourceMode.LIVE,
    /** `ws://127.0.0.1:8765/perception` with `adb reverse tcp:8765 tcp:8765` (USB), or the laptop's LAN address. */
    val serverUrl: String = DEFAULT_URL,
    /** SIM: file stem of the clip in `<external files>/sim/` (and in the laptop's clip folder). */
    val simVideoId: String = DEFAULT_VIDEO,
    /** Debug view: every box, lane polylines, fps / latency, link details, DEMO sketch while offline. */
    val debug: Boolean = false,
    /** LIVE: camera height above the road for `client.hello.camera.mountHeightMeters`. */
    val mountHeightMeters: Double = 1.25,
    /** Spoken cues (visual alerts never depend on this). */
    val voice: Boolean = true,
    /**
     * Hold TOO CLOSE at CLOSE while the route speed says the car is (nearly) stopped: waiting at a red light
     * behind a car is close, not closing in (an unknown speed never gates). On by default; turn it off when
     * the camera films a monitor in LIVE, where the route speed is the tablet's own GPS, not the drive on screen.
     */
    val gateCriticalBySpeed: Boolean = true,
    /**
     * LIVE: the camera films a monitor playing a recorded drive (the demo set-up). The picture then has the
     * recording dash cam's geometry, so no lens intrinsics are sent. Off = windshield mount: send this lens.
     */
    val cameraOnMonitor: Boolean = true,
    /**
     * LIVE navigation target, a place or address ("Piedmont Park, Atlanta"). Sent to the laptop, whose phase1 provider
     * (mock or Google) builds the route from this tablet's GPS. Blank = the laptop's own --nav-destination.
     */
    val destination: String = "",
    /**
     * LIVE: set when [destination] is a place picked in "Where to?" (its label): the laptop routes to exactly this
     * point instead of geocoding the label. Null for a typed destination.
     */
    val destinationLocation: GeoPoint? = null,
    /** Provider place id of the picked place (with [destinationLocation]). */
    val destinationPlaceId: String? = null,
) {
    fun save(context: Context) {
        prefs(context).edit()
            .putString(KEY_MODE, mode.name)
            .putString(KEY_URL, serverUrl)
            .putString(KEY_VIDEO, simVideoId)
            .putBoolean(KEY_DEBUG, debug)
            .putFloat(KEY_MOUNT, mountHeightMeters.toFloat())
            .putBoolean(KEY_VOICE, voice)
            .putBoolean(KEY_SPEED_GATE, gateCriticalBySpeed)
            .putBoolean(KEY_MONITOR, cameraOnMonitor)
            .putString(KEY_DESTINATION, destination)
            .putString(KEY_DESTINATION_LOCATION, destinationLocation?.let(::formatLocation)) // null removes the key
            .putString(KEY_DESTINATION_PLACE_ID, destinationPlaceId)
            .apply()
    }

    companion object {
        const val DEFAULT_URL = "ws://127.0.0.1:8765/perception"
        const val DEFAULT_VIDEO = "b1ff4656-0435391e"

        const val EXTRA_SOURCE = "perception.source"
        const val EXTRA_URL = "perception.url"
        const val EXTRA_VIDEO = "perception.video"
        const val EXTRA_DEBUG = "perception.debug"
        const val EXTRA_DESTINATION = "perception.destination"

        private const val PREFS = "spatial_copilot"
        private const val KEY_MODE = "mode"
        private const val KEY_URL = "server_url"
        private const val KEY_VIDEO = "sim_video"
        private const val KEY_DEBUG = "debug"
        private const val KEY_MOUNT = "mount_height_m"
        private const val KEY_VOICE = "voice"
        /** A new key: every save wrote the old one, off (its old default), so saved settings get the new default once. */
        private const val KEY_SPEED_GATE = "gate_critical_by_speed_v2"
        private const val KEY_MONITOR = "camera_on_monitor"
        private const val KEY_DESTINATION = "destination"
        private const val KEY_DESTINATION_LOCATION = "destination_location"
        private const val KEY_DESTINATION_PLACE_ID = "destination_place_id"

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** "lat,lng" as saved in the preferences. */
        fun formatLocation(p: GeoPoint): String = "${p.lat},${p.lng}"

        /** Inverse of [formatLocation]; null for anything malformed or out of range. */
        fun parseLocation(raw: String?): GeoPoint? {
            val parts = raw?.split(',')?.takeIf { it.size == 2 } ?: return null
            val lat = parts[0].trim().toDoubleOrNull() ?: return null
            val lng = parts[1].trim().toDoubleOrNull() ?: return null
            return if (lat in -90.0..90.0 && lng in -180.0..180.0) GeoPoint(lat, lng) else null
        }

        /** ws:// or wss:// and parseable by OkHttp (a bad saved URL must not crash every launch). */
        fun isValidUrl(raw: String): Boolean {
            val http = when {
                raw.startsWith("ws://") -> "http://" + raw.removePrefix("ws://")
                raw.startsWith("wss://") -> "https://" + raw.removePrefix("wss://")
                else -> return false
            }
            return http.toHttpUrlOrNull()?.host?.isNotEmpty() == true
        }

        fun load(context: Context): AppSettings {
            val p = prefs(context)
            return AppSettings(
                mode = SourceMode.parse(p.getString(KEY_MODE, null)) ?: SourceMode.LIVE,
                serverUrl = p.getString(KEY_URL, null)?.takeIf(::isValidUrl) ?: DEFAULT_URL,
                simVideoId = p.getString(KEY_VIDEO, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_VIDEO,
                debug = p.getBoolean(KEY_DEBUG, false),
                mountHeightMeters = p.getFloat(KEY_MOUNT, 1.25f).toDouble(),
                voice = p.getBoolean(KEY_VOICE, true),
                gateCriticalBySpeed = p.getBoolean(KEY_SPEED_GATE, true),
                cameraOnMonitor = p.getBoolean(KEY_MONITOR, true),
                destination = p.getString(KEY_DESTINATION, null).orEmpty(),
                destinationLocation = parseLocation(p.getString(KEY_DESTINATION_LOCATION, null)),
                destinationPlaceId = p.getString(KEY_DESTINATION_PLACE_ID, null),
            )
        }

        /** Applies launch extras on top of [base]; unknown or invalid values are ignored. */
        fun withExtras(base: AppSettings, intent: Intent?): AppSettings {
            val extras = intent?.extras ?: return base
            var s = base
            SourceMode.parse(extras.getString(EXTRA_SOURCE))?.let { s = s.copy(mode = it) }
            extras.getString(EXTRA_URL)?.trim()?.takeIf(::isValidUrl)?.let { s = s.copy(serverUrl = it) }
            extras.getString(EXTRA_VIDEO)?.trim()?.takeIf { it.isNotEmpty() }?.let { s = s.copy(simVideoId = it) }
            if (extras.containsKey(EXTRA_DEBUG)) s = s.copy(debug = extras.getBoolean(EXTRA_DEBUG))
            extras.getString(EXTRA_DESTINATION)?.trim()?.take(200)?.takeIf { it != s.destination }?.let {
                s = s.copy(destination = it, destinationLocation = null, destinationPlaceId = null) // typed: geocoded
            }
            return s
        }
    }
}
