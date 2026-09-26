package com.drivingassist.copilot.context

import com.drivingassist.copilot.perception.LightState
import com.drivingassist.copilot.perception.ObjectClass
import kotlinx.serialization.Serializable

/** Plan §24 voice/alert priority, most urgent first (compare by [ordinal] or [rank]). */
@Serializable
enum class Priority {
    CRITICAL_SAFETY,
    TRAFFIC_ALERT,
    IMMEDIATE_NAVIGATION,
    UPCOMING_NAVIGATION,
    GENERAL_INFORMATION,
    SOCIAL;

    val rank: Int get() = ordinal
}

/** Plan §18. */
@Serializable
enum class FollowingState { NORMAL, CLOSE, CRITICAL }

/** Plan §16 driving events plus the perception-driven alerts of §17 / §20. */
@Serializable
enum class DrivingEventType {
    // Navigation (§16; KEEP_* / MERGE are phase1 maneuvers)
    KEEP_LANE, CHANGE_LANE_LEFT, CHANGE_LANE_RIGHT, MERGE_LEFT, MERGE_RIGHT, MERGE, KEEP_LEFT, KEEP_RIGHT,
    TURN_LEFT, TURN_RIGHT, EXIT, ENTER_HIGHWAY, FOLLOW_ROAD, STOP, ARRIVE,

    // Road awareness (§17, §18, §20)
    VEHICLE_TOO_CLOSE, FOLLOWING_CLOSE, FOLLOWING_NORMAL,
    TRAFFIC_LIGHT_RED, TRAFFIC_LIGHT_YELLOW, TRAFFIC_LIGHT_GREEN,
    PEDESTRIAN_IN_PATH, STOP_SIGN, SPEED_LIMIT, ROAD_SIGN,

    // Perception availability (plan §38): object / distance alerts off while perception is stale.
    PERCEPTION_LOST, PERCEPTION_RESTORED,
}

/**
 * One deterministic output of the DrivingContextEngine. [text] is the AR label (§20, e.g.
 * "RED | 42 m"); [speech] is the sentence for the VoiceProvider (§23), null = do not speak.
 * Consumers: AR renderer (§19-21) and audio engine (§22, picks by [priority]).
 */
@Serializable
data class DrivingEvent(
    val type: DrivingEventType,
    val priority: Priority,
    val text: String,
    val speech: String? = null,
    val ptsSeconds: Double,
    val seq: Long,
    val distanceMeters: Double? = null,
    val trackId: Int? = null,
    val lanesToMove: Int? = null,
)

/** Navigation maneuver (plan §4), independent of the route provider. Phase1 actions map via [NavigationMapper]. */
@Serializable
enum class Maneuver(val eventType: DrivingEventType) {
    TURN_LEFT(DrivingEventType.TURN_LEFT),
    TURN_RIGHT(DrivingEventType.TURN_RIGHT),
    KEEP_LEFT(DrivingEventType.KEEP_LEFT),
    KEEP_RIGHT(DrivingEventType.KEEP_RIGHT),
    EXIT(DrivingEventType.EXIT),
    MERGE(DrivingEventType.MERGE),
    MERGE_LEFT(DrivingEventType.MERGE_LEFT),
    MERGE_RIGHT(DrivingEventType.MERGE_RIGHT),
    ENTER_HIGHWAY(DrivingEventType.ENTER_HIGHWAY),
    FOLLOW_ROAD(DrivingEventType.FOLLOW_ROAD),
    STOP(DrivingEventType.STOP),
    ARRIVE(DrivingEventType.ARRIVE),
}

@Serializable
enum class LaneSide { LEFT, RIGHT }

/**
 * Plan §4 internal navigation representation, e.g.
 * `{"event": "TURN_RIGHT", "distanceMeters": 243, "street": "University Blvd", "requiredLane": "right"}`.
 * [requiredLanes] are 1-based from the left (same numbering as `lanes.currentLane`); use
 * [requiredSide] when only "rightmost / leftmost lane" is known.
 */
@Serializable
data class NavigationState(
    val maneuver: Maneuver,
    val distanceMeters: Double,
    val label: String? = null,
    val requiredLanes: List<Int> = emptyList(),
    val requiredSide: LaneSide? = null,
    /** Optional ego speed (GPS / OBD) for time headway; perception alone does not know it. */
    val egoSpeedMps: Double? = null,
    /** The route engine's own spoken prompt (phase1 `audioInstructions[0].content`); used instead of generated speech. */
    val audio: String? = null,
    /** phase1 `progress.offRoute`: no lane guidance while off route. */
    val offRoute: Boolean = false,
    /**
     * [requiredSide] was inferred from the maneuver direction (the route gave no lane): guidance only
     * starts within `DrivingContextConfig.inferredLaneGuidanceStartMeters`.
     */
    val laneHintInferred: Boolean = false,
)

@Serializable
enum class LaneAction { KEEP_LANE, CHANGE_LANE_LEFT, CHANGE_LANE_RIGHT, UNKNOWN }

@Serializable
data class LaneGuidance(
    val action: LaneAction,
    val currentLane: Int?,
    val laneCount: Int?,
    val targetLanes: List<Int>,
    val lanesToMove: Int?,
    val priority: Priority,
    /** e.g. "PREPARE TO MOVE RIGHT" (plan §15). */
    val text: String,
)

@Serializable
data class FollowingInfo(
    val state: FollowingState = FollowingState.NORMAL,
    val leadTrackId: Int? = null,
    val leadClass: ObjectClass? = null,
    val distanceMeters: Double? = null,
    val ttcSeconds: Double? = null,
    val relativeSpeedMps: Double? = null,
    val headwaySeconds: Double? = null,
)

@Serializable
data class TrafficLightInfo(
    val trackId: Int,
    val state: LightState,
    val distanceMeters: Double?,
)

@Serializable
data class PedestrianInfo(val trackId: Int, val distanceMeters: Double?, val ttcSeconds: Double?)

/**
 * The Driving Context (plan §15): what is happening right now. Persistent conditions live here
 * (AR shows them while they hold); edges are emitted separately as [DrivingEvent]s.
 */
@Serializable
data class DrivingContext(
    val seq: Long = -1,
    val ptsSeconds: Double = 0.0,
    val following: FollowingInfo = FollowingInfo(),
    val trafficLight: TrafficLightInfo? = null,
    val pedestriansInPath: List<PedestrianInfo> = emptyList(),
    val laneGuidance: LaneGuidance? = null,
    val navigation: NavigationState? = null,
    val speedLimit: Int? = null,
    val activeSigns: List<String> = emptyList(),
    /** Currently active conditions, most urgent first (the top one is what AR should emphasise). */
    val activeAlerts: List<DrivingEvent> = emptyList(),
    /**
     * Perception results are stale or the laptop link is down: object / distance / light / sign
     * alerts are suppressed and only navigation guidance is produced (plan §38). The UI should say
     * so ("Road alerts paused - navigation only") instead of showing old markers.
     */
    val perceptionStale: Boolean = false,
) {
    companion object {
        val EMPTY = DrivingContext()
    }
}
