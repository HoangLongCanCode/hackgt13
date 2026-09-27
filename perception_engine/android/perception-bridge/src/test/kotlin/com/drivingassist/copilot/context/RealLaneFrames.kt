package com.drivingassist.copilot.context

import com.drivingassist.copilot.perception.Camera
import com.drivingassist.copilot.perception.ImageSize
import com.drivingassist.copilot.perception.Lanes
import com.drivingassist.copilot.perception.ObjectClass
import com.drivingassist.copilot.perception.PerceivedObject
import com.drivingassist.copilot.perception.PerceptionFrame
import com.drivingassist.copilot.perception.Road
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * `src/test/resources/real/lane_frames.json`: 20 analysed frames of three recorded drives (real_009 city, real_010
 * highway, real_011 arterial; phone yawed 7-18 degrees, 1280x720): the wave-2 lanes block (line polylines, the
 * server's lane numbers and confidence), the road block's horizon / vanishing point, the wave-1 camera block and the
 * vehicle boxes of the same frame. Nothing else of the recording is kept.
 */
@Serializable
data class RealLaneFrame(
    val clip: String,
    val frameIndex: Long,
    val ptsSeconds: Double,
    val image: ImageSize,
    val camera: Camera,
    val lanes: Lanes,
    val road: Road,
    val objects: List<RealVehicle>,
) {
    fun vehicle(id: Int): RealVehicle = objects.single { it.id == id }

    /** The frame as the WorldModel would publish it right after the run (no layout set: [LaneLayout.from] builds it). */
    fun snapshot(objects: List<ObjectState> = this.objects.map { it.state(ptsSeconds) }): WorldSnapshot = WorldSnapshot(
        timing = FrameTiming(clip, frameIndex, frameIndex, ptsSeconds, 0L, 0L, 40.0, 0.0, 40.0, null, null, 1L, 0L),
        image = image,
        camera = camera,
        objects = objects.associateBy { it.id },
        lanes = LanesState(lanes, lanes.currentLane, lanes.laneCount, ptsSeconds, 0.0),
        road = RoadState(road, ptsSeconds, 0.0),
    )

    /** As a v1 frame (lanes / road carried on wave 1, so the WorldModel adopts them without wave 2). */
    fun frame(seq: Long, pts: Double = seq / 10.0, session: String = "real"): PerceptionFrame =
        TestFrames.frame(seq, pts, session = session).copy(image = image, camera = camera, lanes = lanes, road = road)
}

@Serializable
data class RealVehicle(
    val id: Int,
    @SerialName("class") val cls: ObjectClass,
    val bbox: List<Double>,
    val distanceMeters: Double? = null,
    val lateralMeters: Double? = null,
    val inEgoPath: Boolean? = null,
) {
    fun state(pts: Double, distance: Double? = distanceMeters): ObjectState = ObjectState(
        id = id, cls = cls, bbox = bbox, confidence = 0.8, ageFrames = 10, firstSeenPts = pts, lastSeenPts = pts, visible = true,
        distanceMeters = distance, lateralMeters = lateralMeters, inEgoPath = inEgoPath,
    )

    fun perceived(): PerceivedObject = PerceivedObject(
        id = id, cls = cls, bbox = bbox, confidence = 0.8, ageFrames = 10,
        distanceMeters = distanceMeters, lateralMeters = lateralMeters, inEgoPath = inEgoPath,
    )
}

object RealLaneFrames {
    val all: List<RealLaneFrame> by lazy {
        val text = RealLaneFrames::class.java.getResourceAsStream("/real/lane_frames.json")!!.bufferedReader().use { it.readText() }
        Json { ignoreUnknownKeys = true }.decodeFromString<List<RealLaneFrame>>(text)
    }

    fun get(clip: String, frameIndex: Long): RealLaneFrame = all.single { it.clip == clip && it.frameIndex == frameIndex }
}

/**
 * Exact pinhole view of a straight flat road for tests: the camera is [heightM] above the road on the car's track,
 * yawed [yawDeg] against the road (+ = the road vanishes right of the image centre) and pitched down [pitchDeg].
 */
class SyntheticRoad(
    val focalPx: Double = 700.0,
    val cx: Double = 640.0,
    val cy: Double = 360.0,
    val heightM: Double = 1.25,
    yawDeg: Double = 0.0,
    pitchDeg: Double = 0.0,
    val width: Int = 1280,
    val height: Int = 720,
) {
    private val yaw = Math.toRadians(yawDeg)
    private val pitch = Math.toRadians(pitchDeg)

    /** Image point of the road point [lateral] m right of the car's track and [forward] m ahead along the road. */
    fun project(lateral: Double, forward: Double): List<Double>? {
        val xl = lateral * cos(yaw) + forward * sin(yaw)
        val zl = -lateral * sin(yaw) + forward * cos(yaw)
        val yc = heightM * cos(pitch) - zl * sin(pitch)
        val zc = heightM * sin(pitch) + zl * cos(pitch)
        if (zc <= 0.5) return null
        return listOf(cx + focalPx * xl / zc, cy + focalPx * yc / zc)
    }

    val vpX: Double get() = cx + focalPx * tan(yaw) / cos(pitch)
    val vpY: Double get() = cy - focalPx * tan(pitch)

    val camera: Camera get() = Camera(focalPx, listOf(cx, cy), vpY, heightM)
    val image: ImageSize get() = ImageSize(width, height)

    /** A painted line [lateral] m right of the track, sampled from [from] to [to] m ahead, clipped to the image rows. */
    fun line(lateral: Double, from: Double = 4.0, to: Double = 60.0, n: Int = 15): List<List<Double>> =
        (0 until n).mapNotNull { i -> project(lateral, from + (to - from) * i / (n - 1)) }.filter { it[1] in 0.0..height.toDouble() }

    fun lanes(vararg lateral: Double, confidence: Double = 0.8): Lanes =
        Lanes(currentLane = 1, laneCount = 1, laneBoundaries = lateral.map { line(it) }, confidence = confidence)

    /** Bounding box of a 1.8 m wide, 1.5 m tall car whose rear is [forward] m ahead, centred [lateral] m right of the track. */
    fun carBox(lateral: Double, forward: Double): List<Double> {
        val l = project(lateral - 0.9, forward)!!
        val r = project(lateral + 0.9, forward)!!
        val bottom = maxOf(l[1], r[1])
        return listOf(minOf(l[0], r[0]), bottom - focalPx * 1.5 / forward, maxOf(l[0], r[0]), bottom)
    }

    fun snapshot(lanes: Lanes?, objects: List<ObjectState> = emptyList(), pts: Double = 1.0): WorldSnapshot = WorldSnapshot(
        timing = FrameTiming("synthetic", 1L, 1L, pts, 0L, 0L, 40.0, 0.0, 40.0, null, null, 1L, 0L),
        image = image,
        camera = camera,
        objects = objects.associateBy { it.id },
        lanes = lanes?.let { LanesState(it, it.currentLane, it.laneCount, pts, 0.0) },
    )

    fun car(id: Int, lateral: Double, forward: Double, distance: Double? = forward, inEgoPath: Boolean? = null, pts: Double = 1.0): ObjectState = ObjectState(
        id = id, cls = ObjectClass.CAR, bbox = carBox(lateral, forward), confidence = 0.9, ageFrames = 10, firstSeenPts = 0.0, lastSeenPts = pts,
        visible = true, distanceMeters = distance, lateralMeters = lateral, inEgoPath = inEgoPath,
    )
}
