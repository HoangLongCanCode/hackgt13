package com.drivingassist.spatialcopilot.desktop

import com.drivingassist.copilot.bridge.LinkState
import com.drivingassist.copilot.bridge.LinkStatus
import com.drivingassist.copilot.context.DrivingContext
import com.drivingassist.spatialcopilot.nav.RouteGuide
import java.util.Locale

/** The chrome's status: chip lines, level, one banner (debug) and the clean view's short note. */
data class DesktopStatus(
    val line1: String,
    val line2: String?,
    val level: Level,
    val banner: String?,
    val short: String?,
    val debugLines: List<String>,
) {
    enum class Level { OK, WARN, ERROR }

    companion object {
        /**
         * The app's StatusModel for SIM (that one reads the Android session): the same degraded states, the same
         * short forms by the corner button and the same debug numbers, with the laptop hints for this PC.
         */
        fun compute(k: LinkStatus, ctx: DrivingContext, route: RouteGuide?, problem: String?, destination: String?): DesktopStatus {
            var level = Level.OK
            val line1 = StringBuilder("SIM")
            var line2: String? = null
            var banner: String? = null
            var linkShort: String? = null
            var staleShort: String? = null
            when {
                k.state != LinkState.CONNECTED -> {
                    line1.append(" · LAPTOP NOT CONNECTED")
                    line2 = k.lastError?.take(70) ?: k.url
                    level = Level.ERROR
                    banner = "Perception server not connected: no road alerts. Start it (scripts/desktop_sim.ps1) on ${k.url}."
                    linkShort = "Laptop not connected"
                }
                !k.serverReady -> {
                    line1.append(" · WAITING FOR LAPTOP")
                    level = Level.WARN
                    linkShort = "Waiting for laptop"
                }
                k.takenOver -> {
                    line1.append(" · TAKEN OVER")
                    line2 = "another client controls the server"
                    level = Level.WARN
                    linkShort = "Taken over"
                }
                else -> {
                    line1.append(" · ").append(k.resultFps?.let { f1(it) } ?: "-").append(" fps")
                    k.simLeadMsP50?.let { line1.append(" · lead ").append(f0(it)).append(" ms") }
                    if (ctx.perceptionStale) {
                        level = Level.WARN
                        banner = "Road alerts paused: perception results are late. Navigation only."
                        staleShort = "Road alerts paused"
                    }
                }
            }
            k.serverError?.let { line2 = line2 ?: it.take(80); if (level == Level.OK) level = Level.WARN }
            problem?.let { p ->
                line2 = p
                if (level == Level.OK) level = Level.WARN
            }
            val nav: Pair<String, String?>? = when {
                k.serverNavigationAvailable == false || k.serverNavigationMode == "off" ->
                    ("Route unavailable" + (k.serverNavigationError?.let { ": ${it.take(60)}" } ?: " (the server runs no navigation: --nav-session)")) to "Route unavailable"
                route == null -> null
                route.stale -> "Navigation paused: no route update for 10 s. Last route shown." to "Route paused"
                route.offRoute -> "Off route. phase1 is re-matching the position." to null
                else -> null
            }?.takeIf { k.state == LinkState.CONNECTED }
            banner = banner ?: nav?.first
            val short = linkShort ?: problem?.let(::problemShort) ?: staleShort
                ?: k.serverError?.let { "Laptop error" } ?: nav?.second
            if (short != null && level == Level.OK) level = Level.WARN

            val lines = ArrayList<String>()
            lines += "link ${k.state} ready=${k.serverReady} role=${k.role ?: "-"} rtt ${k.rttMs?.let(::f0) ?: "-"} ms"
            lines += "results ${k.resultFps?.let(::f1) ?: "-"} fps · updates ${k.updateFps?.let(::f1) ?: "-"} fps"
            lines += "sim lead p50 ${k.simLeadMsP50?.let(::f0) ?: "-"} ms · late ${k.simLateResults} · buffered ${k.simBuffered} · pts ${k.playbackPts?.let(::f1) ?: "-"}"
            lines += "nav ${k.serverNavigationMode ?: "?"} packets ${k.navigationPackets} age ${k.navigationAgeMs?.let { f0(it) + " ms" } ?: "-"} provider ${route?.provider ?: "-"}"
            destination?.let { lines += "destination on laptop: $it" }
            val f = ctx.following
            lines += "following ${f.state} lead ${f.leadTrackId ?: "none"} d ${f.distanceMeters?.let(::f1) ?: "--"} m ttc ${f.ttcSeconds?.let(::f1) ?: "--"} s"
            val g = ctx.laneGuidance
            lines += if (g == null) "guidance none" else
                "guidance ${g.action} lane ${g.currentLane ?: "?"}/${g.laneCount ?: "?"} targets ${g.targetLanes} move ${g.lanesToMove ?: "-"} · ${g.text}"
            lines += ctx.speedLimit?.let { "speed limit $it mph (${ctx.speedLimitSource?.name?.lowercase(Locale.ROOT) ?: "?"})" } ?: "speed limit unknown"
            k.clockWarning?.let { lines += "clock: $it" }
            return DesktopStatus(line1.toString(), line2, level, banner, short, lines)
        }

        private fun problemShort(p: String): String = when {
            p.startsWith("No clip") -> "Clip missing"
            p.startsWith("Video") -> "Video problem"
            else -> "App problem"
        }

        private fun f0(x: Double) = String.format(Locale.ROOT, "%.0f", x)
        private fun f1(x: Double) = String.format(Locale.ROOT, "%.1f", x)
    }
}
