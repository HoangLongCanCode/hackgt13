package com.ksr.copilot.bridge

import com.ksr.copilot.context.DrivingContext
import com.ksr.copilot.context.DrivingEvent
import com.ksr.copilot.context.DrivingEventType
import kotlin.math.roundToInt

/**
 * Short labels for small UI (status chip, CLI). Plain strings, no Android / Compose types, so the
 * app, the fake tablet and the tests format the same way.
 */
object DisplayText {
    /** e.g. "CLOSE 6 m", "TOO CLOSE 4 m", "RED LIGHT 40 m", "PEDESTRIAN 9 m", "MOVE RIGHT 2". */
    fun shortLabel(e: DrivingEvent): String {
        val d = e.distanceMeters?.let { " ${it.roundToInt()} m" } ?: ""
        return when (e.type) {
            DrivingEventType.VEHICLE_TOO_CLOSE -> "TOO CLOSE$d"
            DrivingEventType.FOLLOWING_CLOSE -> "CLOSE$d"
            DrivingEventType.TRAFFIC_LIGHT_RED -> "RED LIGHT$d"
            DrivingEventType.TRAFFIC_LIGHT_YELLOW -> "YELLOW LIGHT$d"
            DrivingEventType.TRAFFIC_LIGHT_GREEN -> "GREEN LIGHT$d"
            DrivingEventType.PEDESTRIAN_IN_PATH -> "PEDESTRIAN$d"
            DrivingEventType.STOP_SIGN -> "STOP SIGN$d"
            DrivingEventType.CHANGE_LANE_LEFT -> "MOVE LEFT" + (e.lanesToMove?.takeIf { it > 1 }?.let { " $it" } ?: "")
            DrivingEventType.CHANGE_LANE_RIGHT -> "MOVE RIGHT" + (e.lanesToMove?.takeIf { it > 1 }?.let { " $it" } ?: "")
            else -> e.text.substringBefore(" | ")
        }
    }

    /** The most urgent active condition as a short label, or null when nothing is active. */
    fun topAlert(context: DrivingContext): String? = context.activeAlerts.firstOrNull()?.let(::shortLabel)
}
