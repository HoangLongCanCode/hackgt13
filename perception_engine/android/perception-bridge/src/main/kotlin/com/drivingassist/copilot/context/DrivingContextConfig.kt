package com.drivingassist.copilot.context

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
    /**
     * When set, a CRITICAL following state is held at CLOSE while the ego speed
     * ([NavigationState.egoSpeedMps], the route's `progress.speedMps`) is known and below this many m/s:
     * sitting behind a stopped car is close, not closing in. Null (default) = off. An unknown speed
     * never gates. The thresholds above are not speed-aware on their own.
     */
    val criticalMinEgoSpeedMps: Double? = null,

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
    /**
     * With a known ego speed the inferred start moves out to this many seconds of travel, capped at
     * [inferredLaneGuidanceMaxMeters]: 300 m is 11 s at highway speed, too little to change lanes before the exit.
     */
    val inferredLaneGuidanceLeadSeconds: Double = 20.0,
    val inferredLaneGuidanceMaxMeters: Double = 800.0,
    /** At or below this distance navigation becomes IMMEDIATE_NAVIGATION. */
    val immediateNavigationMeters: Double = 300.0,
    val minLaneConfidence: Double = 0.3,
    val maxLanesAgeSeconds: Double = 1.0,

    val signMinConfidence: Double = 0.5,
    /** Sign must be seen in this many frames before it is announced (filters one-frame false positives). */
    val signMinSeen: Int = 2,
    val signForgetSeconds: Double = 5.0,

    /**
     * Speed-limit sign confirmation ([SpeedLimitLatch]; prototype placeholders, display only). Values a US sign
     * can show (mph); any other read is not accepted.
     */
    val speedLimitAllowedMph: Set<Int> = (10..85 step 5).toSet(),
    val speedLimitMinConfidence: Double = 0.85,
    /** Farther reads do not count (an unknown distance does). */
    val speedLimitMaxDistanceMeters: Double = 60.0,
    /** The same value must be read on one sign for this much media time... */
    val speedLimitDwellSeconds: Double = 0.8,
    /** ...in at least this many reads (the server repeats a sign between its runs, so the time span is the real test). */
    val speedLimitMinSeen: Int = 3,
    /** A confirmed sign value not read again for this long is dropped (the map value, if any, shows instead). */
    val speedLimitSignHoldSeconds: Double = 600.0,
    /**
     * While perception is stale media time stops, so a held sign value is timed on the wall clock: it is dropped
     * after this long stale (or sooner, when the rest of [speedLimitSignHoldSeconds] runs out first).
     */
    val speedLimitStaleHoldSeconds: Double = 30.0,
    /**
     * Map value / road changes up to this long after the last read of the held sign move the map anchor instead of
     * dropping the sign: passing the sign itself (OSM splits the way there, GPS and lookup lag). Later changes drop it.
     */
    val speedLimitSignPassSeconds: Double = 8.0,
    /** A turn / exit / keep / merge target at most this far ahead that is then replaced counts as passed: the sign value is dropped. */
    val speedLimitManeuverPassedMeters: Double = 80.0,

    /** Units for navigation distances in text / speech (object distances are always metres, plan §18). */
    val navigationUnits: Units = Units.IMPERIAL,
)
