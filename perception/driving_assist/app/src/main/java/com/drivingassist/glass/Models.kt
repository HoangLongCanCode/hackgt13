package com.drivingassist.glass

/**
 * Shared screen contract for every box the overlay draws.
 *
 * [box] is [x, y, w, h], each value 0.0 to 1.0, in **overlay space**:
 * (0, 0) is the top-left of the landscape camera preview, (1, 1) is the bottom-right.
 * This is not raw camera-buffer space. Map buffer detections with [PreviewCoordinates]
 * before putting them in these models.
 *
 * x, y is the top-left corner. w, h are width and height as fractions of the view.
 */
data class Vehicle(
    val id: Int,
    val box: FloatArray,
    val distanceMeters: Float,
) {
    init {
        require(box.size == 4) { "Vehicle.box must be [x, y, w, h]" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Vehicle) return false
        return id == other.id &&
            distanceMeters == other.distanceMeters &&
            box.contentEquals(other.box)
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + box.contentHashCode()
        result = 31 * result + distanceMeters.hashCode()
        return result
    }

    override fun toString(): String =
        "Vehicle(id=$id, box=${box.contentToString()}, distanceMeters=$distanceMeters)"
}

/** A point in overlay space. x grows right, y grows down, both 0.0 to 1.0. */
data class NormPoint(
    val x: Float,
    val y: Float,
)

/**
 * One lane polyline from the OpenCV detector.
 *
 * [id] should be "left" or "right" for the ego lane. Extra ids are allowed.
 * [points] run from the near edge of the road (larger y) toward the horizon (smaller y).
 * At least two points.
 */
data class LaneLine(
    val id: String,
    val points: List<NormPoint>,
)

/**
 * A traffic sign that is not an exit sign. Examples: STOP, SPEED_LIMIT_55, YIELD.
 * [box] uses the same [x, y, w, h] overlay space as [Vehicle.box].
 */
data class TrafficSign(
    val id: Int,
    val label: String,
    val box: FloatArray,
) {
    init {
        require(box.size == 4) { "TrafficSign.box must be [x, y, w, h]" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrafficSign) return false
        return id == other.id && label == other.label && box.contentEquals(other.box)
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + label.hashCode()
        result = 31 * result + box.contentHashCode()
        return result
    }
}

/**
 * A highway exit sign. [label] is the text to show, for example "EXIT 24".
 * [box] uses the same [x, y, w, h] overlay space as [Vehicle.box].
 */
data class ExitSign(
    val id: Int,
    val label: String,
    val box: FloatArray,
    val distanceMeters: Float,
) {
    init {
        require(box.size == 4) { "ExitSign.box must be [x, y, w, h]" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExitSign) return false
        return id == other.id &&
            label == other.label &&
            distanceMeters == other.distanceMeters &&
            box.contentEquals(other.box)
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + label.hashCode()
        result = 31 * result + distanceMeters.hashCode()
        result = 31 * result + box.contentHashCode()
        return result
    }
}

/**
 * One vision frame. This is the only object the OpenCV engine publishes.
 *
 * JSON:
 * {
 *   "time": 1.25,
 *   "vehicles": [{ "id": 1, "box": [0.40, 0.50, 0.12, 0.18], "distanceMeters": 14.0 }],
 *   "lanes": [{ "id": "left", "points": [{ "x": 0.08, "y": 0.98 }, { "x": 0.40, "y": 0.42 }] }],
 *   "signs": [{ "id": 1, "label": "SPEED_LIMIT_55", "box": [0.80, 0.22, 0.07, 0.14] }],
 *   "exitSigns": [{ "id": 1, "label": "EXIT 24", "box": [0.68, 0.08, 0.16, 0.10], "distanceMeters": 400 }]
 * }
 *
 * [time] is seconds on the shared engine clock.
 * Use empty lists when a category has no detections. Do not omit a frame because one list is empty.
 */
data class VisionData(
    val time: Float,
    val vehicles: List<Vehicle> = emptyList(),
    val lanes: List<LaneLine> = emptyList(),
    val signs: List<TrafficSign> = emptyList(),
    val exitSigns: List<ExitSign> = emptyList(),
)

/**
 * Navigation cue from the Node.js route engine. The vision engine does not produce this.
 *
 * JSON:
 * { "time": 1.25, "action": "MERGE_LEFT", "audio": "Merging left", "ui": "LANE_ARROW" }
 *
 * [action] is the maneuver, for example MERGE_LEFT, TURN_RIGHT, CONTINUE.
 * [audio] is the spoken prompt.
 * [ui] selects the glass graphic, for example LANE_ARROW.
 * [time] is seconds on the shared engine clock.
 */
data class RouteState(
    val time: Float,
    val action: String,
    val audio: String,
    val ui: String,
)
