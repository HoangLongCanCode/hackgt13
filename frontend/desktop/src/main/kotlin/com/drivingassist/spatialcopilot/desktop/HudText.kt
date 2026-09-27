package com.drivingassist.spatialcopilot.desktop

import com.drivingassist.copilot.context.DrivingContext
import com.drivingassist.copilot.context.DrivingEventType
import com.drivingassist.copilot.context.FollowingState
import com.drivingassist.copilot.context.Priority
import com.drivingassist.copilot.context.WorldSnapshot
import com.drivingassist.copilot.perception.LightState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The HUD's wording rules, the same as the app's CopilotScreen (private there, so repeated here word for word).
 * Everything is measurable: a distance is shown only when the Driving Context has one.
 */
object HudText {
    /**
     * Clean view: the critical alert only. TOO CLOSE with the lead's measured distance, or a pedestrian in the path
     * at the engine's CRITICAL priority. Nothing while perception is stale.
     */
    fun criticalAlert(ctx: DrivingContext): String? {
        if (ctx.perceptionStale) return null
        val f = ctx.following
        val d = f.distanceMeters
        if (d != null && f.state == FollowingState.CRITICAL) return "TOO CLOSE · Vehicle ahead: ${fmt1(d)} m"
        val ped = ctx.activeAlerts.firstOrNull { it.type == DrivingEventType.PEDESTRIAN_IN_PATH && it.priority == Priority.CRITICAL_SAFETY }
            ?: return null
        return ped.distanceMeters?.let { "Pedestrian ahead: ${it.roundToInt()} m" } ?: "Pedestrian ahead"
    }

    /**
     * Debug view: the contextual alert under the road (text, critical): lead in CLOSE / TOO CLOSE with its distance,
     * a pedestrian in the path, a plausible red / yellow light (the voice's alert.red_light checks).
     */
    fun debugAlert(ctx: DrivingContext, world: WorldSnapshot?): Pair<String, Boolean>? {
        if (ctx.perceptionStale) return null
        val f = ctx.following
        val d = f.distanceMeters
        if (d != null && f.state == FollowingState.CRITICAL) return "TOO CLOSE · Vehicle ahead: ${fmt1(d)} m" to true
        ctx.pedestriansInPath.firstOrNull()?.let { p ->
            return (p.distanceMeters?.let { "Pedestrian ahead: ${fmt1(it)} m" } ?: "Pedestrian ahead") to true
        }
        if (d != null && f.state == FollowingState.CLOSE) return "Vehicle ahead: ${fmt1(d)} m" to false
        ctx.trafficLight?.takeIf { it.state == LightState.RED || it.state == LightState.YELLOW }?.takeIf { l ->
            val o = world?.objects?.get(l.trackId)
            val ld = l.distanceMeters
            ld != null && ld in 15.0..60.0 && o?.rawLightState == l.state &&
                (o.lightConfidence ?: 1.0) >= 0.6 && abs(o.lateralMeters ?: 0.0) <= 6.0
        }?.let { l ->
            val name = if (l.state == LightState.RED) "Red light" else "Yellow light"
            return (l.distanceMeters?.let { "$name: ${it.roundToInt()} m" } ?: name) to false
        }
        return null
    }

    /**
     * [text] cut to fit [maxWidth] with a trailing ellipsis (Compose `maxLines = 1, TextOverflow.Ellipsis`);
     * [measure] gives a string's width in the same units.
     */
    fun ellipsize(text: String, maxWidth: Float, measure: (String) -> Float): String {
        if (measure(text) <= maxWidth) return text
        val dots = "…"
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (measure(text.substring(0, mid).trimEnd() + dots) <= maxWidth) lo = mid else hi = mid - 1
        }
        return if (lo == 0) dots else text.substring(0, lo).trimEnd() + dots
    }

    fun fmt1(x: Double): String = String.format(Locale.US, "%.1f", x)
}

/**
 * The window (or PNG) in Tab S9 pixels: the scene is built and drawn on a virtual canvas at the tablet's density
 * (2560x1600 px = 1280x800 dp, [DENSITY] px per dp), scaled by [scale] into the window. The shorter side matches the
 * tablet, so every pixel threshold of the AR code and every dp size of the HUD is the tablet's; a wider window shows
 * more road on the sides, as a wider screen would.
 */
data class ViewFit(val scale: Float, val width: Float, val height: Float) {
    fun dp(v: Float): Float = v * DENSITY

    companion object {
        const val TABLET_WIDTH = 2560f
        const val TABLET_HEIGHT = 1600f

        /** Tab S9 px per dp (1280x800 dp landscape). */
        const val DENSITY = 2f

        fun of(windowWidth: Int, windowHeight: Int): ViewFit {
            val w = max(1, windowWidth).toFloat()
            val h = max(1, windowHeight).toFloat()
            val s = min(w / TABLET_WIDTH, h / TABLET_HEIGHT)
            return ViewFit(s, w / s, h / s)
        }
    }
}
