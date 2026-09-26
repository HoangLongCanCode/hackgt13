package com.ksr.copilot.context

/**
 * Following-distance thresholds (plan §18) with hysteresis: a state is entered below `*Enter*`
 * and left only above `*Exit*`, so the display does not flicker at a boundary.
 *
 * PLACEHOLDERS for the prototype. They drive what is DISPLAYED (measurable distance / TTC), they
 * are not validated safety limits and must not be presented as "safe distance" (plan §18, §38).
 * Distance-only thresholds are city-speed oriented; TTC and (when ego speed is known) time
 * headway cover higher speeds.
 */
data class FollowingThresholds(
    val closeEnterMeters: Double = 15.0,
    val closeExitMeters: Double = 18.0,
    val criticalEnterMeters: Double = 7.0,
    val criticalExitMeters: Double = 9.0,
    val closeEnterTtcSeconds: Double = 4.0,
    val closeExitTtcSeconds: Double = 5.0,
    val criticalEnterTtcSeconds: Double = 2.0,
    val criticalExitTtcSeconds: Double = 2.5,
    val closeEnterHeadwaySeconds: Double = 1.5,
    val closeExitHeadwaySeconds: Double = 1.8,
    val criticalEnterHeadwaySeconds: Double = 0.8,
    val criticalExitHeadwaySeconds: Double = 1.0,
)

enum class Units { METRIC, IMPERIAL }

/** DrivingContextEngine tuning. All values are prototype placeholders (see [FollowingThresholds]). */
data class DrivingContextConfig(
    val following: FollowingThresholds = FollowingThresholds(),
    val leadMaxDistanceMeters: Double = 80.0,

    val lightAlertMaxDistanceMeters: Double = 100.0,
    /** No relevant light for this long: the next light is announced again. */
    val lightForgetSeconds: Double = 3.0,
    /**
     * Keep following the light chosen before while it is still visible; switch only to one that is at
     * least this much closer. Intersections show several heads (turn arrows, other lanes) at the same
     * distance with different colours; without this the nearest one alternates and every flip is spoken.
     */
    val lightSwitchMarginMeters: Double = 5.0,

    val pedestrianMaxDistanceMeters: Double = 30.0,
    val pedestrianCriticalDistanceMeters: Double = 12.0,
    val pedestrianCriticalTtcSeconds: Double = 3.0,
    val pedestrianRepeatSeconds: Double = 4.0,
    val pedestrianForgetSeconds: Double = 1.0,

    /** Lane guidance starts this far before the maneuver. */
    val laneGuidanceStartMeters: Double = 2000.0,
    /** Lane side only inferred from the turn direction (the route gave no required lane): start this late instead. */
    val inferredLaneGuidanceStartMeters: Double = 300.0,
    /** At or below this distance navigation becomes IMMEDIATE_NAVIGATION. */
    val immediateNavigationMeters: Double = 300.0,
    val minLaneConfidence: Double = 0.3,
    val maxLanesAgeSeconds: Double = 1.0,

    val signMinConfidence: Double = 0.5,
    /** Sign must be seen in this many frames before it is announced (filters one-frame false positives). */
    val signMinSeen: Int = 2,
    val signForgetSeconds: Double = 5.0,

    /** Units for navigation distances in text / speech (object distances are always metres, plan §18). */
    val navigationUnits: Units = Units.IMPERIAL,
)
