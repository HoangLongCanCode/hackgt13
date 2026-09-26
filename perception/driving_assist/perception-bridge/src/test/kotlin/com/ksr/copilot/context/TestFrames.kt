package com.ksr.copilot.context

import com.ksr.copilot.perception.Camera
import com.ksr.copilot.perception.ImageSize
import com.ksr.copilot.perception.Lanes
import com.ksr.copilot.perception.LightState
import com.ksr.copilot.perception.ObjectClass
import com.ksr.copilot.perception.PerceivedObject
import com.ksr.copilot.perception.PerceptionFrame
import com.ksr.copilot.perception.Sign
import com.ksr.copilot.perception.Source
import com.ksr.copilot.perception.SourceKind

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

    fun lanes(current: Int?, count: Int? = 3, confidence: Double = 0.8) = Lanes(current, count, emptyList(), confidence)
}
