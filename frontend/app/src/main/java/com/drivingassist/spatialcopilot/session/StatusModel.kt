package com.drivingassist.spatialcopilot.session

import com.drivingassist.copilot.bridge.LinkState
import com.drivingassist.spatialcopilot.voice.VoiceState
import java.util.Locale

/** What the chrome shows: the status chip, at most one banner, and the debug numbers. */
data class StatusUi(
    val line1: String,
    val line2: String?,
    val level: Level,
    /** One sentence about a degraded state that changes what the driver sees (null = all normal). */
    val banner: String?,
    /** Debug view only: link / latency / navigation / voice details, one item per line. */
    val debugLines: List<String>,
) {
    enum class Level { OK, WARN, ERROR }

    companion object {
        val STARTING = StatusUi("STARTING", null, Level.WARN, null, emptyList())
    }
}

/**
 * Turns the session's link, navigation, GPS and voice state into [StatusUi]. Every degraded state gets a
 * visible line and nothing crashes: no laptop, laptop starting, model / server error, another client in
 * control, stale perception, no GPS, stale or inaccurate GPS, no route (Google failure / invalid route),
 * stale navigation, off route, camera or player problems, and voice fallbacks.
 */
object StatusModel {
    /** GPS fixes older than this: the route on screen is held, not followed. */
    const val GPS_STALE_MS = 5_000L

    /** Worse accuracy than this: phase1's progress can jump; say so. */
    const val GPS_POOR_ACCURACY_M = 30.0

    fun compute(session: CopilotSession, settings: AppSettings, voice: VoiceState, nowMs: Long): StatusUi {
        val mode = settings.mode
        val lines = ArrayList<String>()
        if (mode == SourceMode.DEMO) {
            lines += "DEMO: scripted scene and placeholder route, no laptop"
            lines += "voice: ${voice.label}"
            return StatusUi("DEMO", "scripted scene · tap for settings", StatusUi.Level.WARN, null, lines)
        }
        val k = session.link!!.value
        val ctx = session.context.value
        val route = session.route.value
        var level = StatusUi.Level.OK
        val line1 = StringBuilder(mode.name)
        var line2: String? = null
        var banner: String? = null
        when {
            k.state != LinkState.CONNECTED -> {
                line1.append(" · LAPTOP NOT CONNECTED")
                line2 = k.lastError?.take(70) ?: settings.serverUrl
                level = StatusUi.Level.ERROR
                banner = "Laptop not connected: no road alerts. Check the server and adb reverse tcp:8765 tcp:8765."
            }
            !k.serverReady -> {
                line1.append(" · WAITING FOR LAPTOP")
                level = StatusUi.Level.WARN
            }
            k.takenOver -> {
                line1.append(" · TAKEN OVER")
                line2 = "another client controls the laptop; tap to take it back"
                level = StatusUi.Level.WARN
            }
            else -> {
                line1.append(" · ").append(k.resultFps?.let { f1(it) } ?: "-").append(" fps")
                when (mode) {
                    SourceMode.LIVE -> k.captureToResultMsP50?.let { line1.append(" · ").append(f0(it)).append(" ms") }
                    SourceMode.SIM -> k.simLeadMsP50?.let { line1.append(" · lead ").append(f0(it)).append(" ms") }
                    else -> Unit
                }
                if (ctx.perceptionStale) {
                    level = StatusUi.Level.WARN
                    banner = "Road alerts paused: perception results are late. Navigation only."
                }
            }
        }
        k.serverError?.let { line2 = line2 ?: it.take(80); if (level == StatusUi.Level.OK) level = StatusUi.Level.WARN }
        session.problem.value?.let { p ->
            line2 = p
            if (level == StatusUi.Level.OK) level = StatusUi.Level.WARN
        }

        // Navigation health (phase1 on the laptop, fed by GPS in LIVE and by media time in SIM).
        val navLine = when {
            k.serverNavigationError?.contains("waiting for a destination") == true ->
                if (settings.destination.isBlank()) "Live navigation is on: set a destination (tap the chip)." else "Sending the destination to the laptop..."
            k.serverNavigationAvailable == false || k.serverNavigationMode == "off" ->
                "Route unavailable" + (k.serverNavigationError?.let { ": ${it.take(60)}" } ?: " (laptop runs no navigation)")
            route == null -> null
            route.stale -> "Navigation paused: no route update for 10 s. Last route shown."
            route.offRoute -> "Off route. phase1 is re-matching the position."
            else -> null
        }
        val gpsLine = if (mode == SourceMode.LIVE) gpsLine(session, nowMs) else null
        banner = banner ?: gpsLine ?: navLine?.takeIf { k.state == LinkState.CONNECTED }

        lines += "link ${k.state} ready=${k.serverReady} role=${k.role ?: "-"} rtt ${k.rttMs?.let(::f0) ?: "-"} ms"
        lines += "results ${k.resultFps?.let(::f1) ?: "-"} fps · updates ${k.updateFps?.let(::f1) ?: "-"} fps · uplink ${k.uplinkFps?.let(::f1) ?: "-"} fps"
        if (mode == SourceMode.LIVE) {
            lines += "capture→result p50 ${k.captureToResultMsP50?.let(::f0) ?: "-"} / p95 ${k.captureToResultMsP95?.let(::f0) ?: "-"} ms"
            lines += "credits ${k.credits}/${k.maxInFlight} · sent ${k.framesSent} · no-credit ${k.framesDroppedNoCredit} · skips ${k.skips}"
        } else {
            lines += "sim lead p50 ${k.simLeadMsP50?.let(::f0) ?: "-"} ms · late ${k.simLateResults} · buffered ${k.simBuffered} · pts ${k.playbackPts?.let(::f1) ?: "-"}"
        }
        lines += "nav ${k.serverNavigationMode ?: "?"} packets ${k.navigationPackets} age ${k.navigationAgeMs?.let { f0(it) + " ms" } ?: "-"} provider ${route?.provider ?: "-"}"
        session.bridge?.serverHello?.value?.navigationDestination?.let { lines += "destination on laptop: $it" }
        if (mode == SourceMode.LIVE) {
            val fix = session.gps.value
            lines += "gps ${fix?.provider ?: "-"} ±${fix?.accuracyMeters?.let(::f0) ?: "-"} m · age ${fix?.let { (nowMs - it.receivedAtMs) / 1000 }?.toString() ?: "-"} s · sent ${k.tripStatesSent}"
        }
        val f = ctx.following
        lines += "following ${f.state} lead ${f.leadTrackId ?: "none"} d ${f.distanceMeters?.let(::f1) ?: "--"} m ttc ${f.ttcSeconds?.let(::f1) ?: "--"} s"
        lines += "voice: ${voice.label}"
        k.clockWarning?.let { lines += "clock: $it" }
        return StatusUi(line1.toString(), line2, level, banner, lines)
    }

    private fun gpsLine(session: CopilotSession, nowMs: Long): String? {
        if (session.gpsAvailable.value == false) return "No location: turn Location on for live navigation. Last route held."
        val fix = session.gps.value ?: return if (session.gpsAvailable.value == true) "Waiting for GPS. Last route held." else null
        val age = nowMs - fix.receivedAtMs
        return when {
            age > GPS_STALE_MS -> "No new location fix for ${age / 1000} s (${fix.provider}). Last route held."
            (fix.accuracyMeters ?: 0.0) > GPS_POOR_ACCURACY_M -> "GPS accuracy ±${f0(fix.accuracyMeters!!)} m: route progress may jump."
            else -> null
        }
    }

    private fun f0(x: Double) = String.format(Locale.ROOT, "%.0f", x)
    private fun f1(x: Double) = String.format(Locale.ROOT, "%.1f", x)
}
