package com.drivingassist.copilot.context

import com.drivingassist.copilot.perception.BBox
import com.drivingassist.copilot.perception.Camera
import com.drivingassist.copilot.perception.ImageSize
import com.drivingassist.copilot.perception.Lanes
import com.drivingassist.copilot.perception.LightState
import com.drivingassist.copilot.perception.ObjectClass
import com.drivingassist.copilot.perception.Road
import com.drivingassist.copilot.perception.Sign
import com.drivingassist.copilot.perception.StatsMessage
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
    /** The visible lanes from the detected lane lines (see [LaneLayout]), held briefly between runs; null = unknown. */
    val laneLayout: LaneLayout? = null,
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
    /**
     * Image column of the car's ground track: the newest road / lane vanishing point x, kept for the session (a mounted
     * phone's yaw does not change) through stretches without lines. The lead corridor's fallback ([leadVehicle]).
     */
    val trackVpX: Double? = null,
) {
    val ptsSeconds: Double get() = timing?.ptsSeconds ?: 0.0
    val seq: Long get() = timing?.seq ?: -1

    /** This snapshot without perception content (what the Driving Context uses while stale). */
    fun navigationOnly(): WorldSnapshot = copy(objects = emptyMap(), lanes = null, laneLayout = null, road = null, signs = emptyList())

    fun objectsByClass(): Map<ObjectClass, List<ObjectState>> = objects.values.groupBy { it.cls }

    fun countsByClass(visibleOnly: Boolean = true): Map<ObjectClass, Int> =
        objects.values.filter { !visibleOnly || it.visible }.groupingBy { it.cls }.eachCount()

    /**
     * The nearest vehicle in the car's OWN lane: seen in this frame or held by the WorldModel (unseen for up to its
     * `staleTrackSeconds`: one missed detection keeps the lead and the following state's hysteresis), with a distance
     * of at most [maxDistanceMeters], and its box bottom-centre (where it meets the road) inside
     * 1. the [laneLayout]'s ego lane when the layout is stable and at most [maxLayoutAgeSeconds] old
     *    ([LaneLayout.stableAndFresh]); else
     * 2. a corridor of +-[corridorHalfWidthMeters] around the car's ground track at the box's flat-ground depth
     *    ([FlatGround]). The track's image column: `road.vanishingPoint`, else the held layout's vanishing point (also
     *    when not stable), else [trackVpX], and only then the principal point (the camera axis, metres off the track
     *    with a yawed phone).
     * Cars beyond the own lane's lines are never the lead: the server's `inEgoPath` corridor follows the camera axis,
     * so with a yawed phone it took in cars 1-7 m to the side. Only without camera intrinsics does the old rule apply:
     * `inEgoPath` vehicle with the smallest distance, or the lowest box bottom when none has a distance.
     */
    fun leadVehicle(
        maxDistanceMeters: Double = 120.0,
        maxLayoutAgeSeconds: Double = LaneLayout.MAX_USABLE_AGE_SECONDS,
        corridorHalfWidthMeters: Double = 1.0,
    ): ObjectState? {
        val layout = laneLayout?.takeIf { it.stableAndFresh(maxLayoutAgeSeconds) }
        val ground = if (layout == null) FlatGround.of(this) else null
        if (layout == null && ground == null) {
            val inPath = objects.values.filter { it.cls.isVehicle && it.inEgoPath == true }
            val withDistance = inPath.filter { it.distanceMeters != null && it.distanceMeters <= maxDistanceMeters }
            return withDistance.minByOrNull { it.distanceMeters!! }
                ?: inPath.filter { it.distanceMeters == null }.maxByOrNull { it.box.y2 }
        }
        val trackX = road?.road?.vanishingPoint?.getOrNull(0)?.takeIf { it.isFinite() }
            ?: laneLayout?.vpX?.takeIf { it.isFinite() }
            ?: trackVpX?.takeIf { it.isFinite() }
            ?: camera?.cx ?: 0.0
        return objects.values
            .filter { it.cls.isVehicle && it.bbox.size == 4 }
            .filter { it.distanceMeters != null && it.distanceMeters <= maxDistanceMeters }
            .filter { o ->
                val (x, y) = o.box.bottomCenter
                if (layout != null) {
                    layout.laneAt(x, y) == layout.egoLane
                } else {
                    val g = ground!!
                    val z = g.forwardAtRow(y)
                    z != null && abs((x - trackX) * g.metersPerPixelAt(z) * g.cosYaw(trackX)) <= corridorHalfWidthMeters
                }
            }
            .minByOrNull { it.distanceMeters!! }
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

    /**
     * Flat-ground distance (m) to where [o]'s box bottom meets the road ([FlatGround]): the [laneLayout]'s vanishing-point
     * row as horizon and its camera height (the WorldModel's smoothed, clamped one), else the road / camera horizon and
     * the plausible server height ([FlatGround.of]). Null without camera intrinsics or at / above the horizon. The
     * Driving Context cross-checks a low-confidence lead distance with it.
     */
    fun flatGroundDistanceMeters(o: ObjectState): Double? {
        if (o.bbox.size != 4) return null
        val layout = laneLayout
        val cam = camera?.takeIf { FlatGround.usable(it) } ?: return null
        val ground = if (layout != null) {
            FlatGround(cam.focalPx, cam.cx, cam.cy, layout.vpY, layout.cameraHeightMeters ?: FlatGround.heightOf(cam))
        } else {
            FlatGround.of(this) ?: return null
        }
        return ground.forwardAtRow(o.box.y2)
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
