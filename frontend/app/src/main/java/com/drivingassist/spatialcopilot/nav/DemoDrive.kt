package com.drivingassist.spatialcopilot.nav

import com.drivingassist.copilot.context.FrameTiming
import com.drivingassist.copilot.context.LanesState
import com.drivingassist.copilot.context.LaneSide
import com.drivingassist.copilot.context.Maneuver
import com.drivingassist.copilot.context.NavigationState
import com.drivingassist.copilot.context.ObjectState
import com.drivingassist.copilot.context.RoadState
import com.drivingassist.copilot.context.WorldSnapshot
import com.drivingassist.copilot.perception.AnchorPoint
import com.drivingassist.copilot.perception.Camera
import com.drivingassist.copilot.perception.ImageSize
import com.drivingassist.copilot.perception.Lanes
import com.drivingassist.copilot.perception.ObjectClass
import com.drivingassist.copilot.perception.Road
import com.drivingassist.spatialcopilot.ar.Ground
import com.drivingassist.spatialcopilot.ar.GroundProjector
import kotlin.math.PI
import kotlin.math.cos

/**
 * DEMO mode only: a scripted highway run for pitching without the laptop. Its "Exit 56" is a placeholder
 * route; LIVE and SIM never use it (their route is phase1's `navigation.packet`). The scene is fed
 * through the real DrivingContextEngine, AR and audio code, so it shows what they do:
 * lane change right for the exit, then the exit itself, while the car ahead closes to CLOSE and TOO
 * CLOSE and drops back. All numbers are made up and labelled DEMO on screen.
 */
object DemoDrive {
    const val IMAGE_W = 960
    const val IMAGE_H = 540
    const val LOOP_S = 40.0
    private const val FOCAL = 525.0
    private const val HORIZON = 250.0
    private const val HEIGHT_M = 1.25
    private const val LANE_W = 3.6
    private const val SPEED_MPS = 14.0

    val camera = Camera(focalPx = FOCAL, principalPoint = listOf(IMAGE_W / 2.0, IMAGE_H / 2.0), horizonY = HORIZON, cameraHeightMeters = HEIGHT_M)
    private val projector = GroundProjector(FOCAL, IMAGE_W / 2.0, IMAGE_H / 2.0, HORIZON, HEIGHT_M, IMAGE_W, IMAGE_H)

    /** Gentle right-hand curve of the road centre (lateral metres at z metres ahead). */
    private fun bend(z: Double) = 0.0012 * z * z

    /** Distance to the lead car: 28 m, closing to 5 m around t = 17 s, back to 28 m, loop 40 s. */
    fun leadDistance(t: Double): Double {
        val p = ((t % LOOP_S) / LOOP_S)
        val closing = 0.5 - 0.5 * cos(2 * PI * p) // 0 -> 1 -> 0
        return 28.0 - 23.0 * closing * closing
    }

    /** Metres to the exit: 420 m at the start of each loop, 0 at the end. */
    fun exitDistance(t: Double): Double = 420.0 * (1.0 - (t % LOOP_S) / LOOP_S)

    fun world(t: Double, seq: Long): WorldSnapshot {
        val boundaries = listOf(-1.5, -0.5, 0.5, 1.5).map { k ->
            (0..12).mapNotNull { i ->
                val z = 3.0 + i * 5.0
                projector.toImage(Ground(bend(z) + k * LANE_W, z))?.let { listOf(it.x.toDouble(), it.y.toDouble()) }
            }
        }
        val d = leadDistance(t)
        val rel = (leadDistance(t + 0.1) - d) / 0.1
        val cx = bend(d)
        val bl = projector.toImage(Ground(cx - 0.9, d))!!
        val br = projector.toImage(Ground(cx + 0.9, d))!!
        val carTop = bl.y - (FOCAL * 1.5 / d).toFloat()
        val car = ObjectState(
            id = 7, cls = ObjectClass.CAR, bbox = listOf(bl.x.toDouble(), carTop.toDouble(), br.x.toDouble(), bl.y.toDouble()),
            confidence = 0.9, ageFrames = 30, firstSeenPts = 0.0, lastSeenPts = t, visible = true,
            distanceMeters = d, rawDistanceMeters = d, distanceAgeSeconds = 0.0, distanceMethod = "demo",
            relativeSpeedMps = rel, ttcSeconds = if (rel < -0.5) d / -rel else null, inEgoPath = true,
        )
        val roadH = IMAGE_H - HORIZON
        val anchors = listOf("ego_lane_center_near" to 0.85, "ego_lane_center_mid" to 0.6, "ego_lane_center_far" to 0.35).map { (name, f) ->
            val row = HORIZON + f * roadH
            val z = projector.forwardAtRow(row)!!
            val x = projector.toImage(Ground(bend(z), z))!!.x
            AnchorPoint(name, listOf(x.toDouble(), row), listOf(bend(z), z), true)
        }
        val lanes = Lanes(currentLane = 2, laneCount = 3, laneBoundaries = boundaries, confidence = 0.9)
        return WorldSnapshot(
            timing = FrameTiming("demo", seq, seq, t, 0L, 0L, 0.0, 0.0, 0.0, 15.0, 15.0, seq, 0L),
            image = ImageSize(IMAGE_W, IMAGE_H),
            camera = camera,
            objects = mapOf(car.id to car),
            lanes = LanesState(lanes, currentLane = 2, laneCount = 3, measuredPts = t, ageSeconds = 0.0),
            road = RoadState(Road(drivableCoverage = 0.4, horizonY = HORIZON, anchorPoints = anchors), measuredPts = t, ageSeconds = 0.0),
            perceptionStale = false,
            revision = seq,
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
