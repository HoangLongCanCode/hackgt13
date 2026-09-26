package com.drivingassist.glass.perception

import android.os.SystemClock
import com.drivingassist.glass.RouteState
import com.ksr.copilot.bridge.NavigationUpdate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Where [RouteState] comes from when it is not the ViewModel's 5 s mock loop. The route logic
 * itself lives in the phase1 Node.js engine; a RouteSource only delivers its output.
 */
interface RouteSource {
    val routeState: StateFlow<RouteState>
}

/**
 * phase1 navigation via the laptop bridge: `navigation.packet.routeState` -> [RouteState]
 * (`action` / `audio` / `ui` 1:1, `time` = the packet's media time in SIM, else seconds since start).
 */
class BridgeRouteSource(runtime: PerceptionRuntime) : RouteSource {
    private val startMs = SystemClock.elapsedRealtime()

    override val routeState: StateFlow<RouteState> = runtime.bridge.navigation
        .map { toRouteState(it, secondsSinceStart()) }
        .stateIn(runtime.scope, SharingStarted.Eagerly, toRouteState(null, 0f))

    private fun secondsSinceStart(): Float = (SystemClock.elapsedRealtime() - startMs) / 1000f

    companion object {
        const val WAITING = "WAITING_FOR_ROUTE"
        const val NO_ROUTE = "NO_ROUTE"

        /** Pure mapping (unit-tested). Stale packets keep the maneuver but say that updates stopped. */
        fun toRouteState(update: NavigationUpdate?, nowSeconds: Float): RouteState {
            val time = update?.ptsSeconds?.toFloat() ?: nowSeconds
            val rs = update?.routeState
            return when {
                update == null -> RouteState(time, WAITING, "Waiting for route from the laptop", "NONE")
                rs == null || rs.action.isBlank() -> RouteState(time, NO_ROUTE, "No active route", "NONE")
                update.stale -> RouteState(time, rs.action, "Route updates paused", "WARNING")
                else -> RouteState(time, rs.action, rs.audio, rs.ui.ifBlank { "LANE_ARROW" })
            }
        }
    }
}
