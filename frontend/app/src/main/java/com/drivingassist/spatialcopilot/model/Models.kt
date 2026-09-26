package com.drivingassist.spatialcopilot.model

import java.util.Locale
import kotlin.math.roundToInt

/** A point in upright image pixels. x grows right, y grows down. */
data class Px(val x: Float, val y: Float)

/** Axis-aligned box in upright image pixels: left, top, right, bottom. */
data class ImageBox(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
)

enum class ArrowHeading {
    STRAIGHT,
    LEFT,
    RIGHT,
    ;

    companion object {
        fun from(raw: String?): ArrowHeading = when (raw?.trim()?.uppercase(Locale.US)) {
            "LEFT" -> LEFT
            "RIGHT" -> RIGHT
            else -> STRAIGHT
        }
    }
}

/**
 * One road-plane arrow. The Spatial AR Engine draws this primitive and does not
 * decide which lane is correct — [highlighted] is already resolved.
 */
data class LaneArrow(
    val laneIndex: Int,
    val anchorX: Float,
    val anchorY: Float,
    val heading: ArrowHeading,
    val highlighted: Boolean,
)

/** One lane: its two edge polylines (image pixels) and the arrow that sits in it. */
data class LaneSlot(
    val index: Int,
    val recommended: Boolean,
    val boundaries: List<List<Px>>,
    val arrow: LaneArrow,
)

data class VehicleMarker(
    val id: Int,
    val box: ImageBox,
    val distanceMeters: Double?,
    val inFront: Boolean,
) {
    val distanceLabel: String?
        get() = distanceMeters?.let { DistanceFormat.meters(it) }
}

data class SignMarker(
    val id: Int,
    val label: String,
    val box: ImageBox,
)

data class ExitCue(
    val label: String,
    val distanceMeters: Double,
) {
    val distanceLabel: String get() = DistanceFormat.miles(distanceMeters)
}

data class NavigationCue(
    val action: String,
    val requiredLane: Int?,
    val turnDirection: String?,
    val audio: String,
    val exit: ExitCue?,
)

/**
 * The only object the Spatial AR Engine reads.
 *
 * Produced either by [com.drivingassist.spatialcopilot.nav.NavigationLogic] from perception
 * messages, or decoded from a `spatial.instruction` WebSocket text frame.
 * Coordinates are upright image pixels, not view pixels.
 */
data class SpatialInstruction(
    val timeSeconds: Double,
    val imageWidth: Int,
    val imageHeight: Int,
    val currentLane: Int?,
    val laneCount: Int,
    val lanes: List<LaneSlot>,
    val vehicles: List<VehicleMarker>,
    val signs: List<SignMarker>,
    val navigation: NavigationCue,
    val source: String,
) {
    val type: String get() = TYPE
    val schemaVersion: Int get() = SCHEMA_VERSION

    companion object {
        const val TYPE = "spatial.instruction"
        const val SCHEMA_VERSION = 1
    }
}

object DistanceFormat {
    const val METERS_PER_MILE = 1609.344

    fun meters(value: Double): String = "${value.roundToInt()} m"

    fun miles(meters: Double): String =
        String.format(Locale.US, "%.1f mi", meters / METERS_PER_MILE)
}

object SignLabels {
    fun pretty(raw: String): String {
        if (raw.equals("unknown", ignoreCase = true)) return ""
        val spaced = raw
            .replace('_', ' ')
            .replace(Regex("(?<=[a-z])(?=[A-Z0-9])"), " ")
            .replace(Regex("(?<=[A-Z])(?=[A-Z][a-z])"), " ")
            .replace(Regex("(?<=[0-9])(?=[A-Za-z])"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        return spaced.uppercase(Locale.US)
    }
}
