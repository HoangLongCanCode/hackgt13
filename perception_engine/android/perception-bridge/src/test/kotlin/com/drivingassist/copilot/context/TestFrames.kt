package com.drivingassist.copilot.context

import com.drivingassist.copilot.perception.Camera
import com.drivingassist.copilot.perception.ImageSize
import com.drivingassist.copilot.perception.Lanes
import com.drivingassist.copilot.perception.LightState
import com.drivingassist.copilot.perception.ObjectClass
import com.drivingassist.copilot.perception.PerceivedObject
import com.drivingassist.copilot.perception.PerceptionFrame
import com.drivingassist.copilot.perception.Sign
import com.drivingassist.copilot.perception.Source
import com.drivingassist.copilot.perception.SourceKind

/** Small builders for synthetic frames (10 fps unless a pts is given). */
object TestFrames {
    fun frame(
        seq: Long,
        pts: Double = seq / 10.0,
        objects: List<PerceivedObject> = emptyList(),
        lanes: Lanes? = null,
        signs: List<Sign> = emptyList(),
        blockAges: Map<String, Int> = emptyMap(),
        session: String = "test",
        serverTimeMs: Long = 1_000_000L + (pts * 1000).toLong(),
    ) = PerceptionFrame(
        schemaVersion = 1, seq = seq, sessionId = session, source = Source(SourceKind.REPLAY, "unit"),
        frameIndex = seq, ptsSeconds = pts, serverTimeMs = serverTimeMs, processingMs = 40.0,
        image = ImageSize(1280, 720), camera = Camera(1000.0, listOf(640.0, 360.0), 360.0, 1.4),
        objects = objects, signs = signs, lanes = lanes, blockAges = blockAges,
    )

    fun car(id: Int, distance: Double?, inPath: Boolean = true, ttc: Double? = null, lateral: Double? = 0.0, cls: ObjectClass = ObjectClass.CAR) =
        PerceivedObject(
            id = id, cls = cls, bbox = listOf(600.0, 400.0, 680.0, 460.0), confidence = 0.9, ageFrames = 10,
            distanceMeters = distance, lateralMeters = lateral, ttcSeconds = ttc, inEgoPath = inPath,
        )

    fun light(id: Int, state: LightState, distance: Double? = 40.0, lateral: Double? = 1.0) =
        PerceivedObject(
            id = id, cls = ObjectClass.TRAFFIC_LIGHT, bbox = listOf(660.0, 250.0, 670.0, 275.0), confidence = 0.8, ageFrames = 10,
            distanceMeters = distance, lateralMeters = lateral, lightState = state, lightConfidence = 0.9,
        )

    fun pedestrian(id: Int, distance: Double, inPath: Boolean = true) =
        PerceivedObject(
            id = id, cls = ObjectClass.PEDESTRIAN, bbox = listOf(620.0, 380.0, 650.0, 470.0), confidence = 0.8, ageFrames = 5,
            distanceMeters = distance, lateralMeters = 0.5, inEgoPath = inPath,
        )

    /** The server's lane numbers without any line: no layout, so the lane is unknown to the Driving Context. */
    fun lanes(current: Int?, count: Int? = 3, confidence: Double = 0.8) = Lanes(current, count, emptyList(), confidence)

    /**
     * A layout of [count] lanes with the car in lane [ego], as the WorldModel publishes a stable one (lines through the
     * horizon at the image centre of [frame]'s camera): for tests that are about the Driving Context, not the lines.
     */
    fun layout(ego: Int, count: Int = 3, quality: Double = 0.8, age: Double = 0.0) = LaneLayout(
        vpX = 640.0, vpY = 360.0, lines = (0..count).map { LaneLine((it - ego + 0.5) * 2.0) }, egoLane = ego,
        nearestRowY = 700.0, quality = quality, measuredPts = 0.0, ageSeconds = age,
    )

    /** A straight road seen by the camera of [frame]. */
    val road = SyntheticRoad(focalPx = 1000.0, heightM = 1.4)

    /**
     * [count] lanes 3.5 m wide with the car [offset] m right of the middle of lane [current]: the line polylines the
     * WorldModel lays out (stable from the third run), and the server's numbers for lane [current].
     */
    fun laneLines(current: Int, count: Int = 3, offset: Double = 0.0, confidence: Double = 0.8): Lanes =
        road.lanes(*DoubleArray(count + 1) { (it - current + 0.5) * 3.5 - offset }, confidence = confidence).copy(currentLane = current, laneCount = count)
}
