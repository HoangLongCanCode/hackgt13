package com.drivingassist.copilot.perception

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Kotlin mirror of `perception_engine/contracts/perception_frame.v1.schema.json` plus the v2 additions of
 * `perception_engine/contracts/PROTOCOL_v2.md` (`wave`, `echo`, per-object `distanceAgeMs`): one realtime wave-1
 * message from the Python Perception Engine. Field names match the contract 1:1.
 * Decodes both schemaVersion 1 and 2 frames (v2 fields are optional).
 *
 * Coordinates are source-image pixels (origin top-left). Units are in the field names.
 * All distances / TTC are display estimates (plan §18, §38), never control inputs.
 *
 * The schema's `"type": "perception.frame"` is the class discriminator handled by [PerceptionCodec]
 * (it is written on encode and consumed on decode), so it is not a property here.
 */
@Serializable
@SerialName(PerceptionFrame.TYPE)
data class PerceptionFrame(
    val schemaVersion: Int,
    /** Monotonic per session; gaps = frames the server dropped to stay realtime. */
    val seq: Long,
    val sessionId: String,
    val source: Source,
    val frameIndex: Long,
    /** Media time of the analysed frame. Sync AR overlays by this, not by arrival time. */
    val ptsSeconds: Double,
    /** Unix epoch ms when the server sent the message. */
    val serverTimeMs: Long,
    /** Frame grabbed -> message sent, on the server. */
    val processingMs: Double,
    val image: ImageSize,
    val camera: Camera,
    val objects: List<PerceivedObject> = emptyList(),
    val signs: List<Sign> = emptyList(),
    val lanes: Lanes? = null,
    val road: Road? = null,
    /** Frames since each scheduled block last ran (0 = ran on this frame). */
    val blockAges: Map<String, Int> = emptyMap(),
    val timingsMs: Map<String, Double> = emptyMap(),
    /** v2: always 1 for `perception.frame` (fast blocks). Null on v1 frames. */
    val wave: Int? = null,
    /** v2 live mode: the uplink header of the analysed camera frame, echoed back (client clock). */
    val echo: Echo? = null,
) : PerceptionMessage {
    companion object {
        const val TYPE = "perception.frame"
        /** Schema version this client writes; it decodes [MIN_SCHEMA_VERSION]..[SCHEMA_VERSION]. */
        const val SCHEMA_VERSION = 2
        const val MIN_SCHEMA_VERSION = 1
    }
}

/**
 * v2: identifies the uplinked camera frame a result belongs to. `captureTimeNs` is the tablet's
 * own clock, so `clientNowNs - captureTimeNs` is the true capture -> result latency.
 */
@Serializable
data class Echo(val frameId: Long, val captureTimeNs: Long)

@Serializable
data class Source(val kind: SourceKind = SourceKind.UNKNOWN, val id: String = "")

@Serializable
enum class SourceKind {
    @SerialName("video") VIDEO,
    @SerialName("camera") CAMERA,
    @SerialName("replay") REPLAY,

    /** Decoder fallback for a source kind added later (e.g. "sim"), so frames still decode. */
    @SerialName("unknown") UNKNOWN,
}

@Serializable
data class ImageSize(val width: Int, val height: Int)

/** Assumed/estimated pinhole camera, so the AR renderer can project ground points. */
@Serializable
data class Camera(
    val focalPx: Double,
    /** [x, y] px */
    val principalPoint: List<Double>,
    val horizonY: Double? = null,
    val cameraHeightMeters: Double? = null,
) {
    val cx: Double get() = principalPoint[0]
    val cy: Double get() = principalPoint[1]
}

/** BDD100K det_20 classes, spelled exactly as the Python pipeline writes them. */
@Serializable
enum class ObjectClass(val wire: String) {
    @SerialName("pedestrian") PEDESTRIAN("pedestrian"),
    @SerialName("rider") RIDER("rider"),
    @SerialName("car") CAR("car"),
    @SerialName("truck") TRUCK("truck"),
    @SerialName("bus") BUS("bus"),
    @SerialName("train") TRAIN("train"),
    @SerialName("motorcycle") MOTORCYCLE("motorcycle"),
    @SerialName("bicycle") BICYCLE("bicycle"),
    @SerialName("traffic light") TRAFFIC_LIGHT("traffic light"),
    @SerialName("traffic sign") TRAFFIC_SIGN("traffic sign"),

    /** Not in the schema: decoder fallback if the Python side adds a class later. */
    @SerialName("unknown") UNKNOWN("unknown");

    val isVehicle: Boolean
        get() = this == CAR || this == TRUCK || this == BUS || this == MOTORCYCLE || this == TRAIN

    val isVulnerableRoadUser: Boolean
        get() = this == PEDESTRIAN || this == RIDER || this == BICYCLE
}

@Serializable
enum class LightState { RED, YELLOW, GREEN, UNKNOWN }

/**
 * One tracked object with everything known about it, fused by track id
 * (plan §7 detection + §8 motion + §9 distance + §11 light state).
 */
@Serializable
data class PerceivedObject(
    /** Track id, stable across frames (plan §8). */
    val id: Int,
    @SerialName("class") val cls: ObjectClass = ObjectClass.UNKNOWN,
    /** [x1, y1, x2, y2] px */
    val bbox: List<Double>,
    val confidence: Double,
    val ageFrames: Int,
    val distanceMeters: Double? = null,
    val distanceMethod: String? = null,
    val distanceConfidence: Double? = null,
    /** + = right of the camera axis. */
    val lateralMeters: Double? = null,
    /** Time to collision from box scale change; null when not closing. */
    val ttcSeconds: Double? = null,
    val approaching: Boolean? = null,
    /** Box bottom inside the ego path / ego lane polygon. */
    val inEgoPath: Boolean? = null,
    /** Only for class "traffic light". */
    val lightState: LightState? = null,
    val lightConfidence: Double? = null,
    /** v2: age of the carried-forward distance in ms (0 = measured on this frame, null = no distance). */
    val distanceAgeMs: Double? = null,
) {
    val box: BBox get() = BBox.of(bbox)
}

@Serializable
data class Sign(
    val id: Int? = null,
    /** e.g. stop, yield, do_not_enter, pedestrian_crossing, speed_limit_45 (schema example: speedLimit45). */
    val signClass: String,
    val bbox: List<Double>,
    val confidence: Double,
    val distanceMeters: Double? = null,
) {
    val box: BBox get() = BBox.of(bbox)

    /** Speed limit parsed from `speed_limit_45` or `speedLimit45`, else null. */
    val speedLimit: Int? get() = SPEED_LIMIT.find(signClass)?.groupValues?.get(1)?.toIntOrNull()

    val isStop: Boolean get() = signClass.equals("stop", ignoreCase = true)

    private companion object {
        val SPEED_LIMIT = Regex("(?i)speed_?limit_?(\\d{1,3})")
    }
}

/** Plan §10. Lanes numbered 1..laneCount from the left. */
@Serializable
data class Lanes(
    val currentLane: Int? = null,
    val laneCount: Int? = null,
    /** Left-to-right polylines; each point is [x, y] px, <= 20 points each. */
    val laneBoundaries: List<List<List<Double>>> = emptyList(),
    val confidence: Double,
)

/** Plan §13: where AR content can be anchored. */
@Serializable
data class Road(
    val drivableCoverage: Double,
    /** Polygon points, each [x, y] px. */
    val egoPathPolygon: List<List<Double>> = emptyList(),
    val horizonY: Double? = null,
    /** [x, y] px */
    val vanishingPoint: List<Double>? = null,
    val anchorPoints: List<AnchorPoint> = emptyList(),
) {
    fun anchor(name: String): AnchorPoint? = anchorPoints.firstOrNull { it.name == name }
}

@Serializable
data class AnchorPoint(
    /** e.g. ego_path_10m, ego_path_20m, ego_path_40m */
    val name: String,
    /** [x, y] px */
    val xy: List<Double>,
    /** [lateral m, forward m] on the ground plane. */
    val groundXZ: List<Double>? = null,
    val valid: Boolean,
) {
    val x: Double get() = xy[0]
    val y: Double get() = xy[1]
    val lateralMeters: Double? get() = groundXZ?.getOrNull(0)
    val forwardMeters: Double? get() = groundXZ?.getOrNull(1)
}

/** Typed view of a `[x1, y1, x2, y2]` list. */
data class BBox(val x1: Double, val y1: Double, val x2: Double, val y2: Double) {
    val width: Double get() = x2 - x1
    val height: Double get() = y2 - y1
    val centerX: Double get() = (x1 + x2) / 2.0
    val centerY: Double get() = (y1 + y2) / 2.0
    /** Bottom-centre: where the object meets the road (AR vehicle-marker anchor). */
    val bottomCenter: Pair<Double, Double> get() = centerX to y2
    val area: Double get() = width.coerceAtLeast(0.0) * height.coerceAtLeast(0.0)

    companion object {
        fun of(list: List<Double>): BBox {
            require(list.size == 4) { "bbox must have 4 numbers, got ${list.size}" }
            return BBox(list[0], list[1], list[2], list[3])
        }
    }
}
