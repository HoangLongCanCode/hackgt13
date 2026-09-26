package com.drivingassist.glass.perception

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.drivingassist.glass.BuildConfig
import java.net.URI

/** Where Glass Mode's vision (and route) data comes from. */
enum class PerceptionSource {
    /** Scripted [com.drivingassist.glass.MockVisionSource] + the 5 s route loop (the AR app's default, no laptop). */
    MOCK,

    /** Tablet camera -> JPEG uplink -> laptop models -> results drawn on the live preview. */
    LIVE,

    /** The same clip plays on the tablet (Media3) and on the laptop, which analyses ahead of playback. */
    SIM;

    companion object {
        fun parse(value: String?): PerceptionSource? = entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}

/**
 * One switch for the whole perception integration (see PERCEPTION_INTEGRATION.md).
 *
 * Precedence: launch-intent extras > values saved with `--ez perception.persist true` (SharedPreferences) >
 * BuildConfig defaults (`gradlew.bat :app:assembleDebug -Pperception.source=LIVE -Pperception.url=ws://...`).
 * Extras apply to THAT launch only unless `perception.persist` is true, so a plain launch (Android Studio
 * Run, the launcher icon) keeps the BuildConfig defaults (MOCK unless built otherwise). Saved values
 * are validated first and flagged "(saved)" in the status chip; `perception.reset` clears them.
 *
 * ```
 * adb shell am start -S -n com.drivingassist.glass/.MainActivity --es perception.source live --es perception.url ws://192.168.1.20:8765/perception
 * adb shell am start -S -n com.drivingassist.glass/.MainActivity --es perception.source sim --es perception.video b1ff4656-0435391e
 * adb shell am start -S -n com.drivingassist.glass/.MainActivity --es perception.source live --ez perception.persist true   # also for later plain launches
 * adb shell am start -S -n com.drivingassist.glass/.MainActivity --ez perception.reset true     # forget saved values
 * ```
 */
data class PerceptionConfig(
    val source: PerceptionSource = PerceptionSource.MOCK,
    /** `ws://127.0.0.1:8765/perception` with `adb reverse tcp:8765 tcp:8765` (USB), or the laptop's LAN IP. */
    val serverUrl: String = DEFAULT_URL,
    /** SIM: file stem of the clip in `<external files>/sim/` (and on the laptop). */
    val simVideoId: String = DEFAULT_VIDEO,
    /** LIVE: camera height above the road, sent in `client.hello.camera.mountHeightMeters`. */
    val mountHeightMeters: Double = 1.25,
    /** Show phase1 navigation from the laptop instead of the 5 s mock route loop (LIVE also sends GPS). */
    val navEnabled: Boolean = true,
    /** Why an override was ignored (shown in the status chip), null when everything applied. */
    val warning: String? = null,
    /** Keys whose value came from SharedPreferences (saved with `perception.persist`); the chip shows "(saved)". */
    val savedKeys: List<String> = emptyList(),
) {
    companion object {
        private const val TAG = "PerceptionConfig"
        const val DEFAULT_URL = "ws://127.0.0.1:8765/perception"
        const val DEFAULT_VIDEO = "b1ff4656-0435391e"

        const val EXTRA_SOURCE = "perception.source"
        const val EXTRA_URL = "perception.url"
        const val EXTRA_VIDEO = "perception.video"
        const val EXTRA_MOUNT = "perception.mount"
        const val EXTRA_NAV = "perception.nav"
        const val EXTRA_RESET = "perception.reset"
        const val EXTRA_PERSIST = "perception.persist"

        private const val PREFS = "perception_prefs"
        private val KEYS = listOf(EXTRA_SOURCE, EXTRA_URL, EXTRA_VIDEO, EXTRA_MOUNT, EXTRA_NAV)

        /** BuildConfig defaults (Gradle properties `perception.source`, `perception.url`, `perception.simVideoId`, `perception.mountHeight`, `perception.nav`). */
        fun buildDefaults(): PerceptionConfig = PerceptionConfig(
            source = PerceptionSource.parse(BuildConfig.PERCEPTION_SOURCE) ?: PerceptionSource.MOCK,
            serverUrl = BuildConfig.PERCEPTION_SERVER_URL,
            simVideoId = BuildConfig.PERCEPTION_SIM_VIDEO_ID,
            mountHeightMeters = BuildConfig.PERCEPTION_MOUNT_HEIGHT_M,
            navEnabled = BuildConfig.PERCEPTION_NAV_ENABLED,
        )

        /**
         * Reads BuildConfig defaults <- saved values <- intent extras. Extras are saved for later plain
         * launches only with `--ez perception.persist true`, and only the valid ones. Runs once in onCreate (a
         * tiny SharedPreferences read on the main thread).
         */
        fun load(context: Context, intent: Intent?): PerceptionConfig {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val extras = intent?.extras
            if (extras != null && extraString(extras, EXTRA_RESET)?.toBooleanStrictOrNull() == true) {
                prefs.edit().clear().apply()
                Log.i(TAG, "saved perception values cleared: BuildConfig defaults")
            }
            val saved = HashMap<String, String>()
            for (k in KEYS) prefs.getString(k, null)?.let { saved[k] = it }
            val fromExtras = HashMap<String, String>()
            if (extras != null) for (k in KEYS) extraString(extras, k)?.let { fromExtras[k] = it }
            val persist = extras != null && extraString(extras, EXTRA_PERSIST)?.toBooleanStrictOrNull() == true
            if (persist && fromExtras.isNotEmpty()) {
                val edit = prefs.edit()
                for ((k, v) in fromExtras) {
                    if (isValidOverride(k, v)) { edit.putString(k, v.trim()); saved[k] = v.trim() } else Log.w(TAG, "not saving invalid $k='$v'")
                }
                edit.apply()
            }
            val cfg = merge(buildDefaults(), saved, fromExtras)
            Log.i(TAG, "perception config: $cfg")
            return cfg
        }

        /**
         * [saved] values over [base], then this launch's [extras] over both. Pure (unit-tested): records
         * which saved keys are in effect so the chip can say "(saved)".
         */
        fun merge(base: PerceptionConfig, saved: Map<String, String>, extras: Map<String, String>): PerceptionConfig {
            val cfg = resolve(base, saved + extras)
            val savedInUse = saved.keys.filter { it !in extras && isValidOverride(it, saved.getValue(it)) }.sorted()
            return cfg.copy(savedKeys = savedInUse)
        }

        /** True when [value] would be applied for [key] (never saves something [resolve] would reject). */
        fun isValidOverride(key: String, value: String): Boolean =
            key in KEYS && resolve(PerceptionConfig(), mapOf(key to value)).warning == null

        /** Applies string overrides to [base]; invalid values are ignored and reported in [warning]. Pure (unit-tested). */
        fun resolve(base: PerceptionConfig, values: Map<String, String>): PerceptionConfig {
            val warnings = ArrayList<String>()
            var c = base
            values[EXTRA_SOURCE]?.let { v -> PerceptionSource.parse(v)?.let { c = c.copy(source = it) } ?: warnings.add("bad perception.source '$v'") }
            values[EXTRA_URL]?.let { v -> if (isAllowedUrl(v)) c = c.copy(serverUrl = v.trim()) else warnings.add("URL rejected: $v") }
            values[EXTRA_VIDEO]?.let { v -> if (v.isNotBlank() && '/' !in v && '\\' !in v) c = c.copy(simVideoId = v.trim()) else warnings.add("bad perception.video '$v'") }
            values[EXTRA_MOUNT]?.let { v -> v.toDoubleOrNull()?.takeIf { it in 0.3..4.0 }?.let { c = c.copy(mountHeightMeters = it) } ?: warnings.add("bad perception.mount '$v'") }
            values[EXTRA_NAV]?.let { v -> v.trim().lowercase().toBooleanStrictOrNull()?.let { c = c.copy(navEnabled = it) } ?: warnings.add("bad perception.nav '$v'") }
            if (!isAllowedUrl(c.serverUrl)) {
                warnings.add("URL rejected: ${c.serverUrl}")
                c = c.copy(serverUrl = DEFAULT_URL)
            }
            return c.copy(warning = warnings.takeIf { it.isNotEmpty() }?.joinToString("; "))
        }

        /**
         * `wss://` anywhere; cleartext `ws://` only to loopback, private LAN (10/8, 172.16/12, 192.168/16),
         * link-local, CGNAT / hotspot (100.64/10), `localhost` and `*.local`. The network security config
         * permits cleartext at the platform level because Android cannot express IP ranges there; this is
         * the real guard (see PERCEPTION_INTEGRATION.md).
         */
        fun isAllowedUrl(url: String): Boolean {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
            val host = uri.host?.lowercase()?.trim('[', ']') ?: return false
            return when (uri.scheme?.lowercase()) {
                "wss" -> true
                "ws" -> isLocalHost(host)
                else -> false
            }
        }

        fun isLocalHost(host: String): Boolean {
            if (host == "localhost" || host.endsWith(".local") || host == "::1") return true
            if (host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")) return host.contains(':')
            val octets = host.split('.').map { it.toIntOrNull() ?: return false }
            if (octets.size != 4 || octets.any { it !in 0..255 }) return false
            val (a, b) = octets[0] to octets[1]
            return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }

        /** String or boolean / number extra as text (`--es`, `--ez`, `--ef` all work). */
        @Suppress("DEPRECATION")
        private fun extraString(extras: Bundle, key: String): String? =
            if (!extras.containsKey(key)) null else extras.getString(key) ?: extras.get(key)?.toString()
    }
}
