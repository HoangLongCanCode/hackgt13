package com.ksr.copilot.context

import com.ksr.copilot.perception.BBox
import com.ksr.copilot.perception.Camera
import com.ksr.copilot.perception.ImageSize
import com.ksr.copilot.perception.Lanes
import com.ksr.copilot.perception.LightState
import com.ksr.copilot.perception.ObjectClass
import com.ksr.copilot.perception.Road
import com.ksr.copilot.perception.Sign
import com.ksr.copilot.perception.StatsMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Everything the WorldModel knows about one track id, merged over time.
 * Times are media time (`ptsSeconds`) so replay and live behave the same.
 */
@Serializable
data class ObjectState(
    val id: Int,
    @SerialName("class") val cls: ObjectClass,
    /** Latest [x1, y1, x2, y2] px. */
    val bbox: List<Double>,
    val confidence: Double,
    val ageFrames: Int,
    val firstSeenPts: Double,
    val lastSeenPts: Double,
    /** Seen in the latest frame (false while it is held for a few frames after disappearing). */
    val visible: Boolean,
    /** Smoothed distance (robust line fit over the recent history, EMA until enough samples). */
    val distanceMeters: Double? = null,
    /** Latest distance the server reported (already carried between its depth runs). */
    val rawDistanceMeters: Double? = null,
    /** Media seconds between the distance measurement and this snapshot's frame (wave 2 lags wave 1). */
    val distanceAgeSeconds: Double? = null,
    val distanceMethod: String? = null,
    val distanceConfidence: Double? = null,
    val lateralMeters: Double? = null,
    /** d(distance)/dt in m/s from a Theil-Sen slope; negative = closing. Null until enough history. */
    val relativeSpeedMps: Double? = null,
    val ttcSeconds: Double? = null,
    /** "box_scale" (server, from box growth) or "range_rate" (distance / closing speed). */
    val ttcSource: String? = null,
    val approaching: Boolean? = null,
    val inEgoPath: Boolean? = null,
    /** Debounced light state (needs a few consistent frames to change). */
    val lightState: LightState? = null,
    val rawLightState: LightState? = null,
    val lightConfidence: Double? = null,
    /**
     * Image-space velocity of [bbox] as [dx1, dy1, dx2, dy2] px per second (least-squares fit over the
     * last ~0.5 s of media time). Null until enough history or when the track is not visible.
     * Used by `predictedAt()` to move boxes forward to display time.
     */
    val bboxVelocityPxPerS: List<Double>? = null,
) {
    val box: BBox get() = BBox.of(bbox)
    val closingSpeedMps: Double? get() = relativeSpeedMps?.let { -it }
}

@Serializable
data class LanesState(
    val lanes: Lanes,
    /** Mode of the recent `currentLane` values (lane numbers flicker near markings). */
    val currentLane: Int?,
    val laneCount: Int?,
    /** Estimated media time of the lanes block run that produced [lanes]. */
    val measuredPts: Double,
    val ageSeconds: Double,
)

@Serializable
data class RoadState(
    val road: Road,
    val measuredPts: Double,
    val ageSeconds: Double,
)

@Serializable
data class SignState(
    val key: String,
    val sign: Sign,
    val firstSeenPts: Double,
    val lastSeenPts: Double,
    val seenCount: Int,
)

@Serializable
data class FrameTiming(
    val sessionId: String,
    val seq: Long,
    val frameIndex: Long,
    val ptsSeconds: Double,
    val serverTimeMs: Long,
    val receivedAtMs: Long,
    val processingMs: Double,
    /** receivedAt - serverTime. Only meaningful when both clocks are synced (same machine / NTP). */
    val networkLatencyMs: Double,
    /** processingMs + max(0, networkLatencyMs): camera frame grabbed -> dictionary updated. */
    val endToEndLatencyMs: Double,
    /** Frames per second arriving at this client (wall clock, smoothed). */
    val receiveFps: Double?,
    /** Frames analysed per second of media time (from pts deltas). */
    val analysedFps: Double?,
    val framesReceived: Long,
    /** Frames the server skipped to stay realtime (seq gaps), cumulative for the session. */
    val serverDroppedFrames: Long,
    /** 1 for `perception.frame`. */
    val wave: Int = 1,
    /** Live: uplink frame id echoed by the server. */
    val frameId: Long? = null,
    /** Live: capture time of the analysed camera frame on the CLIENT clock (echo). */
    val captureTimeNs: Long? = null,
    /** Client monotonic clock when the frame arrived. */
    val receivedAtNs: Long? = null,
    /** Live: capture -> result on the client's own clock (receivedAtNs - captureTimeNs). */
    val captureToResultMs: Double? = null,
)

/** The newest wave-2 `perception.update` merged into the snapshot. */
@Serializable
data class Wave2Info(
    val seq: Long,
    val frameIndex: Long? = null,
    /** Media time of the frame the slow blocks analysed. */
    val ptsSeconds: Double,
    val processingMs: Double? = null,
    val blocks: List<String> = emptyList(),
    val timingsMs: Map<String, Double> = emptyMap(),
    val receivedAtMs: Long,
    /** Newest wave-1 pts minus this update's pts when it was merged (how far the slow blocks lag). */
    val lagSeconds: Double,
)

/**
 * Immutable snapshot of the in-memory world: THE realtime "dictionary" the rest of the app reads.
 * `objects` is keyed by track id. Published by [WorldModel.snapshot] on every wave-1 frame and
 * every wave-2 update (merged into the latest frame's state), and when [perceptionStale] flips.
 */
@Serializable
data class WorldSnapshot(
    val timing: FrameTiming? = null,
    val image: ImageSize? = null,
    val camera: Camera? = null,
    val objects: Map<Int, ObjectState> = emptyMap(),
    val lanes: LanesState? = null,
    val road: RoadState? = null,
    val signs: List<SignState> = emptyList(),
    val blockAges: Map<String, Int> = emptyMap(),
    val timingsMs: Map<String, Double> = emptyMap(),
    val serverStats: StatsMessage? = null,
    /** Newest merged wave-2 update (distance / lanes / road / signs), null before the first one. */
    val wave2: Wave2Info? = null,
    /**
     * True when the results are too old to act on (live: newest result captured > 500 ms ago; sim:
     * nothing near the playback position) or the link is down. The DrivingContextEngine then drops
     * all object / distance alerts and keeps navigation-only guidance (plan §38).
     */
    val perceptionStale: Boolean = false,
    /** Increments on every publish; lets consumers detect wave-2 merges on the same frame. */
    val revision: Long = 0,
    /** Set by `predictedAt()`: how far (ms) boxes / distances were extrapolated past the capture time. */
    val predictedAheadMs: Double? = null,
) {
    val ptsSeconds: Double get() = timing?.ptsSeconds ?: 0.0
    val seq: Long get() = timing?.seq ?: -1

    /** This snapshot without perception content (what the Driving Context uses while stale). */
    fun navigationOnly(): WorldSnapshot = copy(objects = emptyMap(), lanes = null, road = null, signs = emptyList())

    fun objectsByClass(): Map<ObjectClass, List<ObjectState>> = objects.values.groupBy { it.cls }

    fun countsByClass(visibleOnly: Boolean = true): Map<ObjectClass, Int> =
        objects.values.filter { !visibleOnly || it.visible }.groupingBy { it.cls }.eachCount()

    /** Vehicle in the ego path with the smallest distance (or lowest box bottom when no distances). */
    fun leadVehicle(maxDistanceMeters: Double = 120.0): ObjectState? {
        val inPath = objects.values.filter { it.cls.isVehicle && it.inEgoPath == true }
        val withDistance = inPath.filter { it.distanceMeters != null && it.distanceMeters <= maxDistanceMeters }
        return withDistance.minByOrNull { it.distanceMeters!! }
            ?: inPath.filter { it.distanceMeters == null }.maxByOrNull { it.box.y2 }
    }

    /**
     * Nearest traffic light with a known state. With [aheadOnly], lights far to the side
     * (|lateral| > [maxLateralMeters], or box centre outside the middle 70 % of the image when
     * lateral is unknown) are ignored, since they usually control another road.
     */
    fun nearestLight(aheadOnly: Boolean = true, maxLateralMeters: Double = 15.0): ObjectState? =
        lightsAhead(aheadOnly, maxLateralMeters).firstOrNull()

    /** All traffic lights with a known state (filtered like [nearestLight]), nearest first. */
    fun lightsAhead(aheadOnly: Boolean = true, maxLateralMeters: Double = 15.0): List<ObjectState> {
        val width = image?.width?.toDouble()
        return objects.values
            .filter { it.cls == ObjectClass.TRAFFIC_LIGHT && it.lightState != null && it.lightState != LightState.UNKNOWN }
            .filter { o ->
                if (!aheadOnly) return@filter true
                val lat = o.lateralMeters
                when {
                    lat != null -> abs(lat) <= maxLateralMeters
                    width != null -> o.box.centerX in (0.15 * width)..(0.85 * width)
                    else -> true
                }
            }
            .sortedWith(compareBy<ObjectState> { it.distanceMeters ?: Double.MAX_VALUE }.thenByDescending { it.box.height })
    }

    /** Pedestrians in the ego path, nearest first (unknown distance counts as in range). */
    fun pedestriansInPath(maxDistanceMeters: Double = 40.0): List<ObjectState> =
        objects.values
            .filter { it.cls == ObjectClass.PEDESTRIAN && it.inEgoPath == true }
            .filter { (it.distanceMeters ?: 0.0) <= maxDistanceMeters }
            .sortedBy { it.distanceMeters ?: 0.0 }

    companion object {
        /** Nothing received yet: stale by definition. */
        val EMPTY = WorldSnapshot(perceptionStale = true)
    }
}
