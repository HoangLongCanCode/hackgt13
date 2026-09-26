package com.drivingassist.copilot.bridge

import com.drivingassist.copilot.perception.NavRouteState
import com.drivingassist.copilot.perception.NavSpeedLimit
import com.drivingassist.copilot.perception.NavigationPacketMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The newest phase1 navigation state relayed by the laptop (`navigation.packet`), as the tablet sees it.
 * The AR app shows [routeState] (action / audio / ui map 1:1 onto its `RouteState`); [packet] is the
 * verbatim phase1 `SpatialNavigationPacket` for anything else (upcoming maneuvers, ETA, polyline).
 *
 * @param stale no packet for `BridgeConfig.navigationStaleAfterMs` (relay stopped, GPS lost, link down):
 *   keep the last route on screen but mark it, and the Driving Context stops using it.
 */
@Serializable
data class NavigationUpdate(
    val routeState: NavRouteState?,
    val packet: JsonObject? = null,
    /** Sim: media time the packet was computed for; null in live mode. */
    val ptsSeconds: Double? = null,
    val tripTimestampMs: Long? = null,
    val serverTimeMs: Long? = null,
    /** Bridge clock ([BridgeConfig.clockNs]) when the packet arrived. */
    val receivedAtNs: Long,
    /** Packets received so far (increments on every packet, so equal route states still re-emit). */
    val sequence: Long,
    val stale: Boolean = false,
    /** The map's posted limit at the packet's position (`navigation.packet.speedLimit`), null = off or unknown. */
    val speedLimit: NavSpeedLimit? = null,
) {
    /** Age of this update on the bridge clock. */
    fun ageMs(nowNs: Long): Double = (nowNs - receivedAtNs) / 1e6

    companion object {
        fun from(message: NavigationPacketMessage, receivedAtNs: Long, sequence: Long) = NavigationUpdate(
            routeState = message.routeState,
            packet = message.packet,
            ptsSeconds = message.ptsSeconds,
            tripTimestampMs = message.tripTimestampMs,
            serverTimeMs = message.serverTimeMs,
            receivedAtNs = receivedAtNs,
            sequence = sequence,
            speedLimit = message.speedLimit,
        )
    }
}
