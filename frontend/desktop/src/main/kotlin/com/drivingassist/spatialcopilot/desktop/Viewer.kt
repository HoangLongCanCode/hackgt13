package com.drivingassist.spatialcopilot.desktop

import com.drivingassist.copilot.context.DrivingContext
import com.drivingassist.copilot.context.WorldSnapshot
import com.drivingassist.spatialcopilot.ar.ArInput
import com.drivingassist.spatialcopilot.ar.ArScene
import com.drivingassist.spatialcopilot.ar.ArSceneBuilder
import com.drivingassist.spatialcopilot.nav.Instruction
import com.drivingassist.spatialcopilot.nav.NavText
import com.drivingassist.spatialcopilot.nav.RouteGuide

/** Everything one display frame shows, built once (then drawn into the window or a PNG by [Painter]). */
class FrameState(
    val fit: ViewFit,
    val nowNs: Long,
    val pts: Double,
    val playing: Boolean,
    val video: VideoFrame?,
    val world: WorldSnapshot,
    /** The bridge's merged world (the debug view's light plausibility check reads it, like the app). */
    val merged: WorldSnapshot,
    val context: DrivingContext,
    val route: RouteGuide?,
    val routeDistance: Double?,
    val instruction: Instruction?,
    val scene: ArScene,
    val status: DesktopStatus,
    val debug: Boolean,
    val problem: String?,
    /** Debug view: the viewer's own lines (clock, render / decode rates, voice). */
    val desktopLines: List<String>,
    val cues: List<CueLog>,
    val videoId: String,
)

/**
 * Builds a [FrameState] per display frame the way the app's SpatialArEngine + CopilotScreen read the session:
 * `ArInput(displayWorld(), context, route, routeDistanceNow(route), debug)` into one stateful [ArSceneBuilder] (built at
 * the tablet's pixel size, see [ViewFit]), and `NavText.instruction(route, routeDistanceNow(route), clockNs, pts)` for
 * the banner. Call [frame] at display rate (the builder's fades and smoothing are time based).
 */
class Viewer(private val session: SimSession, private val frames: FramePipe?, private val voice: DesktopVoice?) {
    private val builder = ArSceneBuilder()
    private val renderRate = RateCounter()

    @Volatile var debug: Boolean = session.options.debug

    fun frame(fit: ViewFit): FrameState {
        val now = session.clockNs()
        renderRate.tick(now)
        val pts = session.positionSeconds
        val video = frames?.frameFor(pts)
        val route = session.route.value
        val routeDistance = session.routeDistanceNow(route)
        val world = session.displayWorld()
        val context = session.context.value
        val input = ArInput(world = world, context = context, route = route, routeDistanceMeters = routeDistance, debug = debug)
        val scene = builder.build(input, fit.width, fit.height, now)
        val instruction = route?.let { NavText.instruction(it, routeDistance, now, pts) }
        val link = session.link.value
        val problem = session.problem.value
        val status = DesktopStatus.compute(link, context, route, problem, session.bridge.serverHello.value?.navigationDestination)
        val lines = ArrayList<String>()
        if (debug) {
            val result = session.bridge.resultForPts(pts)
            lines += "desktop pts ${Options.fmt(pts, 2)} s ${if (session.playing) "playing" else "paused"} · result ${result?.let { Options.fmt(it.ptsSeconds, 2) } ?: "none"}" +
                " · frame ${video?.let { Options.fmt(it.pts, 2) } ?: "-"}"
            lines += "render ${renderRate.rate(now)?.let { Options.fmt(it, 0) } ?: "-"} fps · video ${frames?.decodeRate?.rate()?.let { Options.fmt(it, 0) } ?: "-"} fps decoded, ${frames?.buffered ?: 0} ahead"
            route?.let { r ->
                lines += "route ${r.action} ${r.turnDirection ?: "-"} d ${routeDistance?.let { Options.fmt(it, 0) } ?: "-"} m road ${r.roadName ?: "-"} spatial ${r.spatialType ?: "-"} · ${r.eventKey}"
            }
            scene.arrowKind?.let { lines += "arrow ${it.name.lowercase()} · lane arrows ${scene.laneArrows.joinToString(" ") { a -> a.style.name.lowercase() }}" }
            voice?.let { lines += "voice: ${it.label}" }
        }
        return FrameState(
            fit, now, pts, session.playing, video, world, session.bridge.world.value, context, route, routeDistance, instruction,
            scene, status, debug, problem, lines, voice?.recent().orEmpty(), session.videoId,
        )
    }
}
