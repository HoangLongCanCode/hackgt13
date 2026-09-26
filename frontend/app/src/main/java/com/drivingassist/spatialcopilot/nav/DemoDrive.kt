package com.drivingassist.spatialcopilot.nav

import com.drivingassist.copilot.context.FrameTiming
import com.drivingassist.copilot.context.LanesState
import com.drivingassist.copilot.context.LaneSide
import com.drivingassist.copilot.context.Maneuver
import com.drivingassist.copilot.context.NavigationState
import com.drivingassist.copilot.context.ObjectState
import com.drivingassist.copilot.context.RoadState
import com.drivingassist.copilot.context.SignState
import com.drivingassist.copilot.context.WorldSnapshot
import com.drivingassist.copilot.perception.AnchorPoint
import com.drivingassist.copilot.perception.Camera
import com.drivingassist.copilot.perception.ImageSize
import com.drivingassist.copilot.perception.Lanes
import com.drivingassist.copilot.perception.ObjectClass
import com.drivingassist.copilot.perception.Road
import com.drivingassist.copilot.perception.Sign
import com.drivingassist.spatialcopilot.ar.Ground
import com.drivingassist.spatialcopilot.ar.GroundProjector
import kotlin.math.PI
import kotlin.math.cos

/**
 * DEMO mode only: a scripted highway run for pitching without the laptop. Its "Exit 56" is a placeholder
 * route; LIVE and SIM never use it (their route is phase1's `navigation.packet`). The scene is fed
 * through the real DrivingContextEngine, AR and audio code, so it shows what they do. One 40 s loop:
 * - 0-12 s: ego in lane 2 of 3 with the exit on the right, so a lane change right is asked for.
 * - 12-15.5 s: the car moves into lane 3 (the lines slide left by one lane, the lane number flips at 13.75 s).
 * - 2-7 s: a speed-limit 55 sign passes on the right road side.
 * - The car ahead drives in lane 3 at 30 m; once it is in the ego lane it closes to 5 m (TOO CLOSE) at
 *   24 s and drops back to 30 m by 32 s.
 * - The exit counts down from 420 m to 0.
 * All numbers are made up and labelled DEMO on screen.
 */
object DemoDrive {
    const val IMAGE_W = 960
    const val IMAGE_H = 540
    const val LOOP_S = 40.0
    const val LANE_W = 3.6
    const val LANE_COUNT = 3
    /** Lane change 2 -> 3: start and end of the move (loop seconds); the lane number flips halfway. */
    const val CHANGE_START_S = 12.0
    const val CHANGE_END_S = 15.5
    const val CHANGE_MID_S = (CHANGE_START_S + CHANGE_END_S) / 2
    const val LEAD_ID = 7
    const val SIGN_KEY = "demo-sign-55"
    const val SIGN_CLASS = "speedLimit55"
    /** The sign is in view from [SIGN_START_S] to [SIGN_END_S] (loop seconds). */
    const val SIGN_START_S = 2.0
    const val SIGN_END_S = 7.0
    private const val FOCAL = 525.0
    private const val HORIZON = 250.0
    private const val HEIGHT_M = 1.25
    private const val SPEED_MPS = 14.0
    private const val FPS = 15.0
    private const val LEAD_FAR_M = 30.0
    private const val LEAD_NEAR_M = 5.0
    private const val SIGN_FAR_M = 58.0
    private const val SIGN_NEAR_M = 15.0

    val camera = Camera(focalPx = FOCAL, principalPoint = listOf(IMAGE_W / 2.0, IMAGE_H / 2.0), horizonY = HORIZON, cameraHeightMeters = HEIGHT_M)
    private val projector = GroundProjector(FOCAL, IMAGE_W / 2.0, IMAGE_H / 2.0, HORIZON, HEIGHT_M, IMAGE_W, IMAGE_H)

    /** Gentle right-hand curve of the road centre (lateral metres at z metres ahead). */
    private fun bend(z: Double) = 0.0012 * z * z

    /** 0 -> 1 with zero slope at both ends; clamped outside [0, 1]. */
    private fun ease(p: Double): Double = 0.5 - 0.5 * cos(PI * p.coerceIn(0.0, 1.0))

    /** How far the car has moved right, in lane widths: 0 before the change, 1 after it. */
    fun laneShift(t: Double): Double = ease(((t % LOOP_S) - CHANGE_START_S) / (CHANGE_END_S - CHANGE_START_S))

    /** Lane the car is in (1-based from the left): 2, then 3 from the middle of the change. */
    fun currentLane(t: Double): Int = if (t % LOOP_S < CHANGE_MID_S) 2 else 3

    /** Distance to the car ahead (lane 3): 30 m, closing to 5 m from 16 s to 24 s, back to 30 m by 32 s. */
    fun leadDistance(t: Double): Double {
        val p = t % LOOP_S
        return when {
            p < 16.0 -> LEAD_FAR_M
            p < 24.0 -> LEAD_FAR_M - (LEAD_FAR_M - LEAD_NEAR_M) * ease((p - 16.0) / 8.0)
            p < 32.0 -> LEAD_NEAR_M + (LEAD_FAR_M - LEAD_NEAR_M) * ease((p - 24.0) / 8.0)
            else -> LEAD_FAR_M
        }
    }

    /** Metres to the exit: 420 m at the start of each loop, 0 at the end. */
    fun exitDistance(t: Double): Double = 420.0 * (1.0 - (t % LOOP_S) / LOOP_S)

    fun world(t: Double, seq: Long): WorldSnapshot {
        val shift = laneShift(t)
        val lane = currentLane(t)
        // Lines of lanes 1-3 at -1.5 .. 1.5 lane widths from the lane 2 centre, sliding left as the car moves right.
        val boundaries = listOf(-1.5, -0.5, 0.5, 1.5).map { k ->
            (0..12).mapNotNull { i ->
                val z = 3.0 + i * 5.0
                projector.toImage(Ground(bend(z) + (k - shift) * LANE_W, z))?.let { listOf(it.x.toDouble(), it.y.toDouble()) }
            }
        }
        val car = lead(t, shift, inEgoLane = lane == 3)
        val roadH = IMAGE_H - HORIZON
        // Centre of the lane the car reports (jumps one lane when the lane number flips, like the server's).
        val egoOffset = (lane - 2 - shift) * LANE_W
        val anchors = listOf("ego_lane_center_near" to 0.85, "ego_lane_center_mid" to 0.6, "ego_lane_center_far" to 0.35).map { (name, f) ->
            val row = HORIZON + f * roadH
            val z = projector.forwardAtRow(row)!!
            val lateral = bend(z) + egoOffset
            val x = projector.toImage(Ground(lateral, z))!!.x
            AnchorPoint(name, listOf(x.toDouble(), row), listOf(lateral, z), true)
        }
        val lanes = Lanes(currentLane = lane, laneCount = LANE_COUNT, laneBoundaries = boundaries, confidence = 0.9)
        return WorldSnapshot(
            timing = FrameTiming("demo", seq, seq, t, 0L, 0L, 0.0, 0.0, 0.0, FPS, FPS, seq, 0L),
            image = ImageSize(IMAGE_W, IMAGE_H),
            camera = camera,
            objects = mapOf(car.id to car),
            lanes = LanesState(lanes, currentLane = lane, laneCount = LANE_COUNT, measuredPts = t, ageSeconds = 0.0),
            road = RoadState(Road(drivableCoverage = 0.4, horizonY = HORIZON, anchorPoints = anchors), measuredPts = t, ageSeconds = 0.0),
            signs = listOfNotNull(sign(t, shift)),
            perceptionStale = false,
            revision = seq,
        )
    }

    /** The car ahead, in lane 3: the right neighbour lane before the change, the ego lane after it. */
    private fun lead(t: Double, shift: Double, inEgoLane: Boolean): ObjectState {
        val d = leadDistance(t)
        val rel = (leadDistance(t + 0.1) - d) / 0.1
        val cx = bend(d) + (1.0 - shift) * LANE_W
        val bl = projector.toImage(Ground(cx - 0.9, d))!!
        val br = projector.toImage(Ground(cx + 0.9, d))!!
        val carTop = bl.y - (FOCAL * 1.5 / d).toFloat()
        return ObjectState(
            id = LEAD_ID, cls = ObjectClass.CAR, bbox = listOf(bl.x.toDouble(), carTop.toDouble(), br.x.toDouble(), bl.y.toDouble()),
            confidence = 0.9, ageFrames = 30, firstSeenPts = 0.0, lastSeenPts = t, visible = true,
            distanceMeters = d, rawDistanceMeters = d, distanceAgeSeconds = 0.0, distanceMethod = "demo", lateralMeters = cx,
            relativeSpeedMps = rel, ttcSeconds = if (rel < -0.5) d / -rel else null, inEgoPath = inEgoLane,
        )
    }

    /** Speed-limit 55 sign 5 m right of the right road edge, plate 1.5-2.5 m high; null while out of view. */
    private fun sign(t: Double, shift: Double): SignState? {
        val p = t % LOOP_S
        if (p < SIGN_START_S || p > SIGN_END_S) return null
        val d = SIGN_NEAR_M + (SIGN_FAR_M - SIGN_NEAR_M) * (SIGN_END_S - p) / (SIGN_END_S - SIGN_START_S)
        val x = bend(d) + (1.5 - shift) * LANE_W + 5.0
        val left = projector.toImage(Ground(x - 0.4, d))!!
        val right = projector.toImage(Ground(x + 0.4, d))!!
        val pxPerM = FOCAL / d
        val bbox = listOf(left.x.toDouble(), left.y - 2.5 * pxPerM, right.x.toDouble(), left.y - 1.5 * pxPerM)
        return SignState(
            key = SIGN_KEY,
            sign = Sign(signClass = SIGN_CLASS, bbox = bbox, confidence = 0.95, distanceMeters = d),
            firstSeenPts = t - p + SIGN_START_S,
            lastSeenPts = t,
            seenCount = 1 + ((p - SIGN_START_S) * FPS).toInt(),
        )
    }

    /** The Driving Context's navigation input: exit on the right, so it asks for the right lane. */
    fun navigation(t: Double): NavigationState = NavigationState(
        maneuver = Maneuver.EXIT,
        distanceMeters = exitDistance(t),
        label = "Exit 56",
        requiredSide = LaneSide.RIGHT,
        egoSpeedMps = SPEED_MPS,
    )

    fun route(t: Double, nowNs: Long): RouteGuide = RouteGuide(
        maneuver = Maneuver.EXIT,
        action = "EXIT_HIGHWAY",
        turnDirection = "right",
        distanceMeters = exitDistance(t),
        spatialType = "EXIT_MARKER",
        anchorAheadMeters = exitDistance(t),
        roadName = "Exit 56",
        exitNumber = "56",
        destination = "Demo destination",
        etaSeconds = exitDistance(t) / SPEED_MPS,
        remainingMeters = exitDistance(t),
        offRoute = false,
        speedMps = null, // the script already moves the distance every frame
        provider = "demo",
        stale = false,
        ptsSeconds = null,
        receivedAtNs = nowNs,
        routeKey = "demo",
        eventKey = "demo/exit56-${(t / LOOP_S).toInt()}",
    )
}
