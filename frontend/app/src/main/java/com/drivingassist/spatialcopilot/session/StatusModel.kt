package com.drivingassist.spatialcopilot.session

import com.drivingassist.copilot.bridge.LinkState
import com.drivingassist.spatialcopilot.voice.VoiceState
import java.util.Locale

/** What the chrome shows: the status chip, at most one banner and the debug numbers (debug view); [short] (clean view). */
data class StatusUi(
    val line1: String,
    val line2: String?,
    val level: Level,
    /** One sentence about a degraded state that changes what the driver sees (null = all normal). */
    val banner: String?,
    /** Debug view only: link / latency / navigation / voice details, one item per line. */
    val debugLines: List<String>,
    /** Clean view: the most important degraded state in a few words (<= 30 chars) by the corner button; null = nothing to say. */
    val short: String? = null,
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
            lines += speedLimitLine(session)
            return StatusUi("DEMO", "scripted scene · tap for settings", StatusUi.Level.WARN, null, lines, "DEMO · scripted scene")
        }
        val k = session.link!!.value
        val ctx = session.context.value
        val route = session.route.value
        var level = StatusUi.Level.OK
        val line1 = StringBuilder(mode.name)
        var line2: String? = null
        var banner: String? = null
        var linkShort: String? = null
        var staleShort: String? = null
        when {
            k.state != LinkState.CONNECTED -> {
                line1.append(" · LAPTOP NOT CONNECTED")
                line2 = k.lastError?.take(70) ?: settings.serverUrl
                level = StatusUi.Level.ERROR
                banner = "Laptop not connected: no road alerts. Check the server and adb reverse tcp:8765 tcp:8765."
                linkShort = "Laptop not connected"
            }
            !k.serverReady -> {
                line1.append(" · WAITING FOR LAPTOP")
                level = StatusUi.Level.WARN
                linkShort = "Waiting for laptop"
            }
            k.takenOver -> {
                line1.append(" · TAKEN OVER")
                line2 = "another client controls the laptop; tap to take it back"
                level = StatusUi.Level.WARN
                linkShort = "Taken over · tap to take back"
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
                    staleShort = "Road alerts paused"
                }
            }
        }
        k.serverError?.let { line2 = line2 ?: it.take(80); if (level == StatusUi.Level.OK) level = StatusUi.Level.WARN }
        session.problem.value?.let { p ->
            line2 = p
            if (level == StatusUi.Level.OK) level = StatusUi.Level.WARN
        }

        // Navigation health (phase1 on the laptop, fed by GPS in LIVE and by media time in SIM). No short form for
        // what the clean view already shows: off route is in the instruction banner, a missing destination in "Where to?".
        val nav: Degraded? = when {
            k.serverNavigationError?.contains("waiting for a destination") == true -> Degraded(
                if (settings.destination.isBlank()) "Live navigation is on: tap Where to? to pick a destination." else "Sending the destination to the laptop...",
                null,
            )
            k.serverNavigationAvailable == false || k.serverNavigationMode == "off" -> Degraded(
                "Route unavailable" + (k.serverNavigationError?.let { ": ${it.take(60)}" } ?: " (laptop runs no navigation)"),
                "Route unavailable",
            )
            route == null -> null
            route.stale -> Degraded("Navigation paused: no route update for 10 s. Last route shown.", "Route paused")
            route.offRoute -> Degraded("Off route. phase1 is re-matching the position.", null)
            else -> null
        }?.takeIf { k.state == LinkState.CONNECTED }
        val gps = if (mode == SourceMode.LIVE) gpsLine(session, nowMs) else null
        banner = banner ?: gps?.banner ?: nav?.banner
        val short = linkShort ?: session.problem.value?.let(::problemShort) ?: staleShort
            ?: k.serverError?.let { if (it.contains("live navigation is not running")) "Live navigation off" else "Laptop error" }
            ?: gps?.short ?: nav?.short
        // Every short form is a degraded state: the corner dot is not mint while one is shown.
        if (short != null && level == StatusUi.Level.OK) level = StatusUi.Level.WARN

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
        lines += speedLimitLine(session)
        k.clockWarning?.let { lines += "clock: $it" }
        return StatusUi(line1.toString(), line2, level, banner, lines, short)
    }

    /** A degraded state: the banner sentence and the clean view's short form (null = not shown there). */
    private data class Degraded(val banner: String, val short: String?)

    private fun gpsLine(session: CopilotSession, nowMs: Long): Degraded? {
        if (session.gpsAvailable.value == false) {
            return Degraded("No location: turn Location on for live navigation. Last route held.", "Location off")
        }
        val fix = session.gps.value
            ?: return if (session.gpsAvailable.value == true) Degraded("Waiting for GPS. Last route held.", "No GPS fix") else null
        val age = nowMs - fix.receivedAtMs
        return when {
            age > GPS_STALE_MS -> Degraded("No new location fix for ${age / 1000} s (${fix.provider}). Last route held.", "No GPS fix")
            (fix.accuracyMeters ?: 0.0) > GPS_POOR_ACCURACY_M ->
                Degraded("GPS accuracy ±${f0(fix.accuracyMeters!!)} m: route progress may jump.", "Weak GPS")
            else -> null
        }
    }

    /** [CopilotSession.problem] (camera, player, missing clip, a failed loop) in a few words. */
    private fun problemShort(p: String): String = when {
        p.startsWith("Camera") || p.startsWith("No camera") -> "Camera problem"
        p.startsWith("No clip") -> "Clip missing"
        p.startsWith("Player") -> "Video problem"
        else -> "App problem"
    }

    /** Debug line: the limit the Driving Context shows and where it comes from. */
    private fun speedLimitLine(session: CopilotSession): String {
        val ctx = session.context.value
        val limit = ctx.speedLimit ?: return "speed limit unknown"
        return "speed limit $limit mph (${ctx.speedLimitSource?.name?.lowercase(Locale.ROOT) ?: "?"})"
    }

    private fun f0(x: Double) = String.format(Locale.ROOT, "%.0f", x)
    private fun f1(x: Double) = String.format(Locale.ROOT, "%.1f", x)
}
