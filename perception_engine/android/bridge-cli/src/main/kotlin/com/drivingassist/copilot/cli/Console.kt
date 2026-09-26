package com.drivingassist.copilot.cli

import com.drivingassist.copilot.bridge.DisplayText
import com.drivingassist.copilot.bridge.LinkState
import com.drivingassist.copilot.bridge.LinkStatus
import com.drivingassist.copilot.bridge.NavigationUpdate
import com.drivingassist.copilot.context.DrivingContext
import com.drivingassist.copilot.context.DrivingEvent
import com.drivingassist.copilot.context.LaneSide
import com.drivingassist.copilot.context.Maneuver
import com.drivingassist.copilot.context.NavigationState
import com.drivingassist.copilot.context.WorldSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/** What --dump-snapshot prints: the realtime dictionary, the context derived from it, the route, and link health. */
@Serializable
internal data class Dump(
    val worldSnapshot: WorldSnapshot?,
    val drivingContext: DrivingContext,
    val navigation: NavigationUpdate?,
    val navigationState: NavigationState?,
    val linkStatus: LinkStatus? = null,
)

internal val prettyJson = Json { prettyPrint = true; explicitNulls = false; encodeDefaults = true }

internal fun dumpJson(s: WorldSnapshot?, c: DrivingContext, nav: NavigationUpdate?, navState: NavigationState?, link: LinkStatus?, title: String): String =
    "\n===== $title =====\n" + prettyJson.encodeToString(Dump.serializer(), Dump(s, c, nav, navState, link))

/** --nav-stub (plan §4): an exit [Options.navDistance] m ahead requiring lane [Options.navLane], counted down by distance travelled. */
internal fun stubNavigation(o: Options, travelledMeters: Double): NavigationState {
    val remaining = (o.navDistance - travelledMeters).coerceAtLeast(0.0)
    return NavigationState(
        maneuver = Maneuver.EXIT,
        distanceMeters = Math.round(remaining / 10.0) * 10.0, // 10 m steps, like a nav SDK
        label = o.navLabel,
        requiredLanes = listOf(o.navLane),
        requiredSide = LaneSide.RIGHT,
    )
}

/**
 * The per-snapshot lines of the live block: lanes, lead vehicle (distance + age, TTC, following
 * state), nearest light, pedestrians / signs, counts, wave-2 freshness, route state, guidance, top alert, events.
 */
internal fun worldLines(s: WorldSnapshot?, c: DrivingContext, nav: NavigationUpdate?, nowNs: Long, events: ConcurrentLinkedQueue<DrivingEvent>): String {
    val sb = StringBuilder()
    if (c.perceptionStale || s == null || s.perceptionStale) sb.append("  STALE  road alerts paused - navigation only\n")
    if (s != null && s.timing != null) {
        val lanes = s.lanes
        sb.append("  lane   ")
        sb.append(if (lanes != null) "${lanes.currentLane ?: "?"}/${lanes.laneCount ?: "?"} (conf ${f2(lanes.lanes.confidence)}, age ${f2(lanes.ageSeconds)}s, ${lanes.lanes.laneBoundaries.size} lines)" else "--")
        s.road?.let { sb.append("   road coverage ${f2(it.road.drivableCoverage)}") }
        sb.append('\n')
    }

    val f = c.following
    sb.append("  lead   ")
    if (f.leadTrackId != null) {
        val age = s?.objects?.get(f.leadTrackId)?.distanceAgeSeconds
        sb.append("#${f.leadTrackId} ${f.leadClass?.wire} ${f.distanceMeters?.let { f1(it) + " m" } ?: "-- m"}")
        age?.let { sb.append(" (age ${f2(it)} s)") }
        sb.append("  ttc ${f.ttcSeconds?.let { f1(it) + " s" } ?: "--"}  rel ${f.relativeSpeedMps?.let { f1(it) + " m/s" } ?: "--"}")
    } else {
        sb.append("none")
    }
    sb.append("  -> ${f.state}\n")

    val l = c.trafficLight
    sb.append("  light  ").append(if (l != null) "#${l.trackId} ${l.state} ${l.distanceMeters?.let { f0(it) + " m" } ?: ""}" else "--")
    if (c.pedestriansInPath.isNotEmpty()) sb.append("   pedestrians in path: ").append(c.pedestriansInPath.joinToString { "#${it.trackId} ${it.distanceMeters?.let(::f0) ?: "?"} m" })
    if (c.activeSigns.isNotEmpty()) sb.append("   signs: ${c.activeSigns.joinToString()}")
    c.speedLimit?.let { sb.append("   limit $it") }
    sb.append('\n')

    if (s != null) {
        val counts = s.countsByClass().entries.sortedByDescending { it.value }.joinToString { "${it.key.wire} ${it.value}" }
        sb.append("  seen   ${counts.ifEmpty { "nothing" }}  (tracks held: ${s.objects.size})")
        s.wave2?.let { w -> sb.append("   wave2 seq ${w.seq} lag ${f0(w.lagSeconds * 1000)} ms [${w.blocks.joinToString(",")}]") }
        sb.append('\n')
    }

    sb.append("  route  ")
    val rs = nav?.routeState
    when {
        nav == null -> sb.append("no navigation.packet yet")
        rs == null -> sb.append("packet without routeState (no active route)")
        else -> {
            sb.append("${rs.action} \"${rs.audio}\" ui=${rs.ui}")
            rs.distanceMeters?.let { sb.append("  ${f0(it)} m") }
            rs.etaSeconds?.let { sb.append("  eta ${f0(it)} s") }
            rs.requiredLane?.let { sb.append("  lane=$it") }
            if (rs.offRoute == true) sb.append("  OFF ROUTE")
        }
    }
    if (nav != null) sb.append("  (#${nav.sequence}, age ${f1(nav.ageMs(nowNs) / 1000.0)} s${if (nav.stale) ", STALE" else ""})")
    sb.append('\n')

    c.laneGuidance?.let { sb.append("  guide  ${it.text}  [${it.priority}]\n") }
    c.activeAlerts.firstOrNull()?.let { sb.append("  alert  ${DisplayText.shortLabel(it)}  (${it.text})  [${it.priority}]\n") }

    var e = events.poll()
    while (e != null) {
        sb.append("  EVENT  [${e.priority}] ${e.type}  \"${e.text}\"")
        e.speech?.let { sb.append("  voice: \"$it\"") }
        sb.append("  @${f2(e.ptsSeconds)}s\n")
        e = events.poll()
    }
    return sb.toString()
}

/** Link summary shared by the bridge modes. */
internal fun linkSummary(k: LinkStatus): String {
    val sb = StringBuilder("[${k.state}${if (k.serverReady) "" else ", server not ready"}")
    k.role?.let { sb.append(", ").append(it) }
    if (k.takenOver) sb.append(", TAKEN OVER by another client")
    sb.append("] res ${k.resultFps?.let(::f1) ?: "-"} fps")
    k.updateFps?.let { sb.append(" (wave2 ${f1(it)}/s)") }
    sb.append(" | rtt ${k.rttMs?.let(::f1) ?: "-"} ms")
    sb.append(" | nav packets ${k.navigationPackets}")
    if (k.tripStatesSent > 0) sb.append(", trip states sent ${k.tripStatesSent}")
    if (k.reconnects > 0) sb.append(" | reconnects ${k.reconnects}")
    if (k.decodeErrors > 0) sb.append(" | decode-err ${k.decodeErrors}")
    if (k.state != LinkState.CONNECTED) k.lastError?.let { sb.append("  last: $it") }
    k.serverError?.let { sb.append('\n').append("  SERVER ERROR  ").append(it) }
    return sb.toString()
}

internal fun f0(x: Double) = String.format(Locale.ROOT, "%.0f", x)
internal fun f1(x: Double) = String.format(Locale.ROOT, "%.1f", x)
internal fun f2(x: Double) = String.format(Locale.ROOT, "%.2f", x)
