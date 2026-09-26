package com.ksr.spatialcopilot.nav

import com.ksr.spatialcopilot.model.ArrowHeading
import com.ksr.spatialcopilot.model.DistanceFormat
import com.ksr.spatialcopilot.model.ExitCue
import com.ksr.spatialcopilot.model.ImageBox
import com.ksr.spatialcopilot.model.LaneArrow
import com.ksr.spatialcopilot.model.LaneSlot
import com.ksr.spatialcopilot.model.NavigationCue
import com.ksr.spatialcopilot.model.Px
import com.ksr.spatialcopilot.model.SignMarker
import com.ksr.spatialcopilot.model.SpatialInstruction
import com.ksr.spatialcopilot.model.VehicleMarker
import kotlin.math.abs
import kotlin.math.sin

/**
 * Route observation copied off `navigation.packet.routeState`, or built locally
 * when the route engine has not spoken yet.
 *
 * [requiredLaneRaw] keeps the server's free-text form (`"right"`, `"2"`, `"2-3"`).
 * [NavigationLogic.selectLane] turns it into a lane index.
 */
data class RawNav(
    val action: String,
    val requiredLaneRaw: String?,
    val turnDirection: String?,
    val distanceMeters: Double?,
    val audio: String,
    val roadName: String?,
)

/**
 * Turns perception geometry plus a navigation cue into a [SpatialInstruction].
 *
 * [selectLane] is the placeholder for a future Google Routes integration. The
 * Spatial AR Engine only reads `arrow.highlighted`; it does not contain this rule.
 */
object NavigationLogic {
    /** Commit to the maneuver lane inside half a mile. Beyond that, hold the current lane. */
    const val COMMIT_METERS = 804.672

    private val demoBoundaries: List<List<Px>> = listOf(
        listOf(Px(70f, 520f), Px(430f, 230f)),
        listOf(Px(250f, 530f), Px(470f, 230f)),
        listOf(Px(640f, 530f), Px(510f, 230f)),
        listOf(Px(900f, 520f), Px(560f, 230f)),
    )

    fun demoInstruction(elapsedSeconds: Double): SpatialInstruction = assemble(
        timeSeconds = elapsedSeconds,
        imageWidth = 960,
        imageHeight = 540,
        currentLane = 2,
        laneCount = 3,
        boundaries = demoBoundaries,
        vehicles = listOf(demoVehicle(elapsedSeconds)),
        signs = listOf(
            SignMarker(
                id = 1,
                label = "SPEED LIMIT 55",
                box = ImageBox(760f, 48f, 900f, 140f),
            ),
        ),
        nav = placeholderNav(elapsedSeconds),
        source = "demo",
    )

    /**
     * Local highway-exit script used until a `navigation.packet` arrives.
     * Distance breathes around 0.4 mi. Inside 0.5 mi the right lane lights up.
     */
    fun placeholderNav(elapsedSeconds: Double): RawNav {
        val miles = 0.4 + 0.18 * sin(elapsedSeconds * 0.7)
        val meters = miles * DistanceFormat.METERS_PER_MILE
        val commit = meters <= COMMIT_METERS
        return RawNav(
            action = "EXIT_HIGHWAY",
            requiredLaneRaw = null,
            turnDirection = "right",
            distanceMeters = meters,
            audio = if (commit) {
                "Take exit 56 from the right lane"
            } else {
                "Stay in lane for exit 56"
            },
            roadName = "Exit 56",
        )
    }

    fun assemble(
        timeSeconds: Double,
        imageWidth: Int,
        imageHeight: Int,
        currentLane: Int?,
        laneCount: Int?,
        boundaries: List<List<Px>>,
        vehicles: List<VehicleMarker>,
        signs: List<SignMarker>,
        nav: RawNav?,
        source: String,
    ): SpatialInstruction {
        val width = imageWidth.coerceAtLeast(1)
        val height = imageHeight.coerceAtLeast(1)
        val count = when {
            laneCount != null && laneCount > 0 -> laneCount
            boundaries.size >= 2 -> boundaries.size - 1
            else -> 3
        }
        val cue = nav ?: placeholderNav(timeSeconds)
        val recommended = selectLane(
            laneCount = count,
            currentLane = currentLane,
            action = cue.action,
            requiredLaneRaw = cue.requiredLaneRaw,
            turnDirection = cue.turnDirection,
            distanceMeters = cue.distanceMeters,
        )
        val lanes = buildLanes(
            count = count,
            recommended = recommended,
            boundaries = boundaries,
            imageWidth = width,
            imageHeight = height,
            action = cue.action,
            turnDirection = cue.turnDirection,
        )
        return SpatialInstruction(
            timeSeconds = timeSeconds,
            imageWidth = width,
            imageHeight = height,
            currentLane = currentLane,
            laneCount = count,
            lanes = lanes,
            vehicles = leadVehicles(vehicles),
            signs = signs.filter { it.label.isNotBlank() }.take(4),
            navigation = NavigationCue(
                action = cue.action,
                requiredLane = recommended,
                turnDirection = cue.turnDirection,
                audio = cue.audio,
                exit = exitCue(cue),
            ),
            source = source,
        )
    }

    /**
     * Which lane arrow to illuminate.
     *
     * An explicit [requiredLaneRaw] from the route engine wins. Otherwise, inside
     * [COMMIT_METERS] of an exit or turn, pick the outside lane on that side.
     * Replace this body when Google Routes (or phase1) supplies the lane index.
     */
    fun selectLane(
        laneCount: Int,
        currentLane: Int?,
        action: String,
        requiredLaneRaw: String?,
        turnDirection: String?,
        distanceMeters: Double?,
    ): Int {
        val count = laneCount.coerceAtLeast(1)
        val current = (currentLane ?: ((count + 1) / 2)).coerceIn(1, count)
        parseExplicit(requiredLaneRaw, count, action)?.let { return it }
        val close = distanceMeters == null || distanceMeters <= COMMIT_METERS
        if (!close) return current
        val side = "$action ${turnDirection.orEmpty()}".uppercase()
        return when {
            "LEFT" in side -> 1
            "RIGHT" in side || "EXIT" in side -> count
            else -> current
        }
    }

    fun leadVehicles(vehicles: List<VehicleMarker>): List<VehicleMarker> {
        val measured = vehicles.filter { it.distanceMeters != null }
        val flagged = measured.filter { it.inFront }.sortedBy { it.distanceMeters }
        if (flagged.isNotEmpty()) return flagged.take(3)
        val nearest = measured.minByOrNull { it.distanceMeters ?: Double.MAX_VALUE } ?: return emptyList()
        return listOf(nearest.copy(inFront = true))
    }

    private fun parseExplicit(raw: String?, laneCount: Int, action: String): Int? {
        if (raw.isNullOrBlank()) return null
        when (raw.trim().lowercase()) {
            "left" -> return 1
            "right" -> return laneCount
            "middle", "center", "centre" -> return ((laneCount + 1) / 2).coerceAtLeast(1)
        }
        val numbers = Regex("\\d+").findAll(raw).map { it.value.toInt() }.filter { it >= 1 }.toList()
        if (numbers.isEmpty()) return null
        val actionUpper = action.uppercase()
        val pick = when {
            "LEFT" in actionUpper -> numbers.first()
            "RIGHT" in actionUpper || "EXIT" in actionUpper -> numbers.last()
            else -> numbers.first()
        }
        return pick.coerceIn(1, laneCount)
    }

    private fun buildLanes(
        count: Int,
        recommended: Int,
        boundaries: List<List<Px>>,
        imageWidth: Int,
        imageHeight: Int,
        action: String,
        turnDirection: String?,
    ): List<LaneSlot> {
        val anchorY = imageHeight * 0.74f
        return (1..count).map { index ->
            val left = boundaries.getOrNull(index - 1).orEmpty()
            val right = boundaries.getOrNull(index).orEmpty()
            val highlighted = index == recommended
            LaneSlot(
                index = index,
                recommended = highlighted,
                boundaries = listOf(left, right).filter { it.size >= 2 },
                arrow = LaneArrow(
                    laneIndex = index,
                    anchorX = anchorX(left, right, anchorY, index, count, imageWidth),
                    anchorY = anchorY,
                    heading = heading(index, recommended, action, turnDirection),
                    highlighted = highlighted,
                ),
            )
        }
    }

    private fun anchorX(
        left: List<Px>,
        right: List<Px>,
        y: Float,
        index: Int,
        count: Int,
        imageWidth: Int,
    ): Float {
        val lx = xAtY(left, y)
        val rx = xAtY(right, y)
        if (lx != null && rx != null) return (lx + rx) / 2f
        return imageWidth * index / (count + 1f)
    }

    private fun xAtY(points: List<Px>, y: Float): Float? {
        if (points.isEmpty()) return null
        if (points.size == 1) return points[0].x
        for (i in 0 until points.lastIndex) {
            val a = points[i]
            val b = points[i + 1]
            val minY = minOf(a.y, b.y)
            val maxY = maxOf(a.y, b.y)
            if (y < minY || y > maxY) continue
            if (a.y == b.y) return (a.x + b.x) / 2f
            val t = (y - a.y) / (b.y - a.y)
            return a.x + t * (b.x - a.x)
        }
        return points.minBy { abs(it.y - y) }.x
    }

    private fun heading(
        laneIndex: Int,
        recommended: Int,
        action: String,
        turnDirection: String?,
    ): ArrowHeading {
        if (laneIndex != recommended) return ArrowHeading.STRAIGHT
        val side = "$action ${turnDirection.orEmpty()}".uppercase()
        return when {
            "LEFT" in side -> ArrowHeading.LEFT
            "RIGHT" in side || "EXIT" in side -> ArrowHeading.RIGHT
            else -> ArrowHeading.STRAIGHT
        }
    }

    private fun exitCue(nav: RawNav): ExitCue? {
        val meters = nav.distanceMeters ?: return null
        val blob = listOf(nav.action, nav.roadName.orEmpty(), nav.audio).joinToString(" ")
        val isExit = nav.action.contains("EXIT", ignoreCase = true) ||
            blob.contains("exit", ignoreCase = true)
        if (!isExit) return null
        val number = Regex("(?i)exit\\s*([0-9]+[A-Za-z]?)").find(blob)?.groupValues?.getOrNull(1)
        val label = if (number.isNullOrBlank()) "EXIT" else "EXIT $number"
        return ExitCue(label = label, distanceMeters = meters)
    }

    private fun demoVehicle(elapsedSeconds: Double): VehicleMarker {
        val meters = 18.0 + 4.0 * sin(elapsedSeconds * 0.8)
        return VehicleMarker(
            id = 1,
            box = ImageBox(430f, 214f, 548f, 392f),
            distanceMeters = meters,
            inFront = true,
        )
    }
}
