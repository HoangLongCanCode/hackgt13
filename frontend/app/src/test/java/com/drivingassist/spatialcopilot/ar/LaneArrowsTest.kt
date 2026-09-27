package com.drivingassist.spatialcopilot.ar

import com.drivingassist.copilot.context.DrivingContext
import com.drivingassist.copilot.context.FrameTiming
import com.drivingassist.copilot.context.LaneAction
import com.drivingassist.copilot.context.LaneGuidance
import com.drivingassist.copilot.context.LaneLayout
import com.drivingassist.copilot.context.LaneLine
import com.drivingassist.copilot.context.Maneuver
import com.drivingassist.copilot.context.ObjectState
import com.drivingassist.copilot.context.Priority
import com.drivingassist.copilot.context.RoadState
import com.drivingassist.copilot.context.WorldSnapshot
import com.drivingassist.copilot.perception.Camera
import com.drivingassist.copilot.perception.ImageSize
import com.drivingassist.copilot.perception.ObjectClass
import com.drivingassist.copilot.perception.Road
import com.drivingassist.spatialcopilot.nav.RouteGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class LaneArrowsTest {
    private val laneW = 3.5
    private val viewW = 2560f
    private val viewH = 1600f
    /** The recorded drives' camera: 1280x720, f 700, horizon 40 px below the principal point, 1.3 m up. */
    private val projector = GroundProjector(700.0, 640.0, 360.0, 400.0, 1.3, 1280, 720)
    private val map = FillCenter(1280, 720, viewW, viewH)
    private val frameNs = 16_000_000L

    /**
     * The lane layout of a straight road seen with the phone yawed [yawDeg] off it (+ = the road runs to the right of
     * the camera axis, vanishing point right of the principal point). Line j lies (j + 0.5 - [carPos]) lane widths to
     * the right of the camera, measured across the road, so lane k's centre is k - [carPos] lanes away. Each line is
     * the ray from the vanishing point through its projected point 10 m ahead; the car's lane is the one around the
     * vertical line through the vanishing point.
     */
    private fun layout(
        yawDeg: Double = 0.0,
        carPos: Double = 2.0,
        lines: Int = 4,
        quality: Double = 0.8,
        age: Double = 0.1,
        nearestRowY: Double = 715.0,
        detected: (Int) -> Boolean = { true },
    ): LaneLayout {
        val psi = Math.toRadians(yawDeg)
        val vpX = projector.cx + projector.focalPx * tan(psi) / cos(projector.pitch)
        val vpY = projector.horizonY
        val slopes = (0 until lines).map { j ->
            val o = (j + 0.5 - carPos) * laneW
            val along = (10.0 + o * sin(psi)) / cos(psi)
            val p = projector.toImage(Ground(o * cos(psi) + along * sin(psi), 10.0))!!
            (p.x - vpX) / (p.y - vpY)
        }
        val ego = (1 until lines).first { k -> slopes[k - 1] <= 0.0 && slopes[k] > 0.0 }
        return LaneLayout(vpX, vpY, slopes.mapIndexed { j, s -> LaneLine(s, detected(j)) }, ego, nearestRowY, quality, 1.0, age)
    }

    private fun world(
        layout: LaneLayout? = layout(),
        objects: List<ObjectState> = emptyList(),
        stale: Boolean = false,
        roadVp: List<Double>? = null,
        drivable: List<List<Double>> = emptyList(),
        roadAge: Double = 0.0,
    ) = WorldSnapshot(
        timing = FrameTiming("test", 1, 1, 1.0, 0L, 0L, 0.0, 0.0, 0.0, 15.0, 15.0, 1, 0L),
        image = ImageSize(1280, 720),
        camera = Camera(focalPx = 700.0, principalPoint = listOf(640.0, 360.0), horizonY = 400.0, cameraHeightMeters = 1.3),
        objects = objects.associateBy { it.id },
        // No server lane numbers at all: the arrows only need the layout.
        laneLayout = layout,
        road = if (roadVp == null && drivable.isEmpty()) null else RoadState(
            Road(drivableCoverage = 0.4, horizonY = 400.0, vanishingPoint = roadVp, drivablePolygon = drivable), measuredPts = 1.0, ageSeconds = roadAge,
        ),
        perceptionStale = stale,
    )

    /** The road above a dashboard at row 540, cut at the lower left by the A-pillar edge from (0, 460) to (400, 540). */
    private val dashAndPillar = listOf(listOf(0.0, 405.0), listOf(1280.0, 405.0), listOf(1280.0, 540.0), listOf(400.0, 540.0), listOf(0.0, 460.0))

    /** Even-odd point-in-polygon, image pixels. */
    private fun inside(p: Vec2, polygon: List<List<Double>>): Boolean {
        var c = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val (ax, ay) = polygon[i]
            val (bx, by) = polygon[j]
            if ((ay > p.y) != (by > p.y) && p.x < (bx - ax) * (p.y - ay) / (by - ay) + ax) c = !c
            j = i
        }
        return c
    }

    private fun route(maneuver: Maneuver, turn: String? = null, exitNumber: String? = null, stale: Boolean = false, offRoute: Boolean = false) = RouteGuide(
        maneuver = maneuver, action = maneuver.name, turnDirection = turn, distanceMeters = 400.0, spatialType = null,
        anchorAheadMeters = 400.0, roadName = null, exitNumber = exitNumber, destination = null, etaSeconds = null,
        remainingMeters = null, offRoute = offRoute, speedMps = null, provider = "mock", stale = stale, ptsSeconds = null, receivedAtNs = 0L,
    )

    private fun guidance(action: LaneAction, targets: List<Int>, current: Int = 2, count: Int = 3) =
        LaneGuidance(action, current, count, targets, null, Priority.UPCOMING_NAVIGATION, "")

    private fun input(
        world: WorldSnapshot = world(),
        lane: LaneGuidance? = guidance(LaneAction.CHANGE_LANE_RIGHT, listOf(3)),
        route: RouteGuide? = route(Maneuver.EXIT, "right"),
        distance: Double? = 400.0,
        debug: Boolean = false,
    ) = ArInput(world, DrivingContext(laneGuidance = lane), route, distance, debug)

    private fun car(id: Int, cls: ObjectClass, bbox: List<Double>, visible: Boolean = true) = ObjectState(
        id = id, cls = cls, bbox = bbox, confidence = 0.9, ageFrames = 10, firstSeenPts = 0.0, lastSeenPts = 1.0, visible = visible,
    )

    private class Clock(var ns: Long = 1_000_000_000L)

    /** Runs the builder at ~60 Hz for [seconds]; returns the last scene. */
    private fun run(b: ArSceneBuilder, clock: Clock, seconds: Double, w: Float = viewW, input: () -> ArInput): ArScene {
        var scene = b.build(input(), w, viewH, clock.ns)
        val frames = (seconds * 1e9 / frameNs).toInt()
        repeat(frames) {
            clock.ns += frameNs
            scene = b.build(input(), w, viewH, clock.ns)
        }
        return scene
    }

    private fun ArScene.lane(k: Int?): LaneArrow? = laneArrows.firstOrNull { it.lane == k }

    private fun ArScene.lanes(): List<Int?> = laneArrows.map { it.lane }.sortedBy { it ?: 0 }

    /** View -> analysed-image pixels. */
    private fun img(p: Vec2) = Vec2((p.x - map.dx) / map.scale, (p.y - map.dy) / map.scale)

    /** Lane fraction of view point [p] in [lane] of [l]: -0.5 = left line, 0 = midline, 0.5 = right line. */
    private fun laneS(p: Vec2, l: LaneLayout, lane: Int): Double {
        val q = img(p)
        val xl = l.lineX(lane - 1, q.y.toDouble())
        val xr = l.lineX(lane, q.y.toDouble())
        return (q.x - xl) / (xr - xl) - 0.5
    }

    private fun rowView(z: Double): Float = map.point(0.0, projector.rowAt(z)).y

    /** Middle of the outline's base (its lowest row) and its tip (the highest point), in view pixels. */
    private fun baseAndTip(a: LaneArrow): Pair<Vec2, Vec2> {
        val bottom = a.outline.maxOf { it.y }
        val base = a.outline.filter { abs(it.y - bottom) < 0.5f }
        return Vec2((base.minOf { it.x } + base.maxOf { it.x }) / 2f, bottom) to a.outline.minBy { it.y }
    }

    @Test
    fun `every visible lane gets an arrow, and without a lane requirement the car's lane is the green one`() {
        val scene0 = run(ArSceneBuilder(), Clock(), 0.6) { input(lane = null) }
        assertEquals(listOf<Int?>(1, 2, 3), scene0.lanes())
        assertEquals(LaneArrowStyle.TARGET, scene0.lane(2)!!.style)
        assertEquals(LaneArrowStyle.OTHER, scene0.lane(1)!!.style)
        assertEquals(LaneArrowStyle.OTHER, scene0.lane(3)!!.style)
        assertTrue(scene0.laneArrows.all { it.glyph == LaneArrowGlyph.STRAIGHT })
        // Not emphasised: the exit is 400 m away (> 300 m) and there is no lane choice.
        assertEquals(0.55f, scene0.lane(2)!!.alpha, 1e-3f)
        assertEquals(0.55f * 0.45f, scene0.lane(1)!!.alpha, 1e-3f)
        // Guidance UNKNOWN: a lane requirement the Driving Context cannot resolve. The car's lane is not painted green.
        val unknown = run(ArSceneBuilder(), Clock(), 0.6) { input(lane = guidance(LaneAction.UNKNOWN, listOf(3)), debug = true) }
        assertEquals(listOf<Int?>(1, 2, 3), unknown.lanes())
        assertTrue(unknown.laneArrows.all { it.style == LaneArrowStyle.OTHER && it.glyph == LaneArrowGlyph.STRAIGHT })
        assertTrue(unknown.laneArrows.all { abs(it.alpha - 0.55f * 0.45f) < 1e-3f })
        assertEquals("layout 2/3 q0.80 targets ?", unknown.debug!!.laneStatus)
        // The target lane fading out as the guidance turns UNKNOWN (the layout too old for lane numbers) is not green either.
        val b = ArSceneBuilder()
        val clock = Clock()
        assertEquals(LaneArrowStyle.TARGET, run(b, clock, 0.6) { input() }.lane(3)!!.style)
        clock.ns += frameNs
        val fading = b.build(input(world(layout(age = 1.4)), guidance(LaneAction.UNKNOWN, emptyList())), viewW, viewH, clock.ns)
        assertEquals(LaneArrowStyle.OTHER, fading.lane(3)!!.style)
        assertTrue(fading.laneArrows.all { it.style == LaneArrowStyle.OTHER && it.glyph == LaneArrowGlyph.STRAIGHT })
        // A phone yawed 17 degrees, the car left of its lane centre: still one arrow per lane, the car's lane from the layout.
        val yawed = layout(yawDeg = 17.0, carPos = 2.3)
        assertEquals(2, yawed.egoLane)
        val scene = run(ArSceneBuilder(), Clock(), 0.6) { input(world(yawed), lane = null) }
        assertEquals(listOf<Int?>(1, 2, 3), scene.lanes())
        assertEquals(LaneArrowStyle.TARGET, scene.lane(2)!!.style)
    }

    @Test
    fun `only lanes whose arrow centre is on screen get an arrow`() {
        // Six lanes, the car in lane 3. Lane centres 9 m ahead are 275 image px apart around column 640; the Tab S9 view
        // shows image columns 64..1216, a 1600 px wide one only 280..1000.
        val six = layout(carPos = 3.0, lines = 7)
        assertEquals(3, six.egoLane)
        val keep = guidance(LaneAction.KEEP_LANE, listOf(3), current = 3, count = 6)
        assertEquals(listOf<Int?>(1, 2, 3, 4, 5), run(ArSceneBuilder(), Clock(), 0.5) { input(world(six), keep) }.lanes())
        val narrow = run(ArSceneBuilder(), Clock(), 0.5, w = 1600f) { input(world(six), keep) }
        assertEquals(listOf<Int?>(2, 3, 4), narrow.lanes())
    }

    @Test
    fun `lane choice - target green, then the car's lane red and targets blinking after the debounce, green again after the lane change`() {
        val b = ArSceneBuilder()
        val clock = Clock()
        var scene = run(b, clock, 0.6) { input() }
        assertEquals(listOf<Int?>(1, 2, 3), scene.lanes())
        assertEquals(LaneArrowStyle.TARGET, scene.lane(3)!!.style)
        assertEquals("not red before 0.8 s", LaneArrowStyle.OTHER, scene.lane(2)!!.style)
        assertEquals(LaneArrowStyle.OTHER, scene.lane(1)!!.style)
        assertEquals("faded in, emphasised", 0.9f, scene.lane(3)!!.alpha, 1e-3f)
        assertEquals("other lanes faint", 0.9f * 0.45f, scene.lane(1)!!.alpha, 1e-3f)

        scene = run(b, clock, 0.3) { input() }
        assertEquals(LaneArrowStyle.WRONG, scene.lane(2)!!.style)
        assertEquals(LaneArrowStyle.TARGET_BLINK, scene.lane(3)!!.style)
        assertEquals(LaneArrowStyle.OTHER, scene.lane(1)!!.style)
        // Slow pulse: the target's alpha swings over one period.
        val alphas = ArrayList<Float>()
        repeat(75) { clock.ns += frameNs; alphas += b.build(input(), viewW, viewH, clock.ns).lane(3)!!.alpha }
        assertTrue("pulse ${alphas.min()}..${alphas.max()}", alphas.min() < 0.35f && alphas.max() > 0.85f)

        // The layout now has the car in lane 3 (a target): no red at once, blinking for 0.3 s more, then steady green.
        val inLane3 = { input(world(layout(carPos = 3.0)), guidance(LaneAction.KEEP_LANE, listOf(3), current = 3)) }
        clock.ns += frameNs
        scene = b.build(inLane3(), viewW, viewH, clock.ns)
        assertTrue(scene.laneArrows.none { it.style == LaneArrowStyle.WRONG })
        assertEquals(LaneArrowStyle.TARGET_BLINK, scene.lane(3)!!.style)
        scene = run(b, clock, 0.2, input = inLane3)
        assertEquals(LaneArrowStyle.TARGET_BLINK, scene.lane(3)!!.style)
        scene = run(b, clock, 0.15, input = inLane3)
        assertEquals(LaneArrowStyle.TARGET, scene.lane(3)!!.style)
        assertEquals(LaneArrowStyle.OTHER, scene.lane(2)!!.style)
    }

    @Test
    fun `a short flicker out of the target lane does not turn anything red`() {
        val b = ArSceneBuilder()
        val clock = Clock()
        val keep = { carPos: Double -> input(world(layout(carPos = carPos)), guidance(LaneAction.KEEP_LANE, listOf(3), current = 3)) }
        run(b, clock, 0.5) { keep(3.0) }
        val scene = run(b, clock, 0.5) { keep(2.0) }
        assertTrue(scene.laneArrows.none { it.style == LaneArrowStyle.WRONG || it.style == LaneArrowStyle.TARGET_BLINK })
    }

    @Test
    fun `without a usable layout only the car's lane arrow, never red`() {
        // Green only when the Driving Context has the car's lane as a route lane (KEEP_LANE) or gives no lane guidance.
        // While it asks for a lane change (the default guidance here) the lone arrow is white and straight.
        val keep = guidance(LaneAction.KEEP_LANE, listOf(2))
        val poor = world(layout(quality = 0.3))
        val old = world(layout(age = 1.4))
        val none = world(layout = null)
        val cases = mapOf(
            "no layout, keep lane" to Triple(input(none, keep), LaneArrowStyle.TARGET, "layout unknown"),
            "no layout, no guidance" to Triple(input(none, lane = null), LaneArrowStyle.TARGET, "layout unknown"),
            "no layout, lane change" to Triple(input(none), LaneArrowStyle.OTHER, "layout unknown"),
            "no layout, guidance UNKNOWN" to Triple(input(none, guidance(LaneAction.UNKNOWN, emptyList())), LaneArrowStyle.OTHER, "layout unknown"),
            "poor layout, keep lane" to Triple(input(poor, keep), LaneArrowStyle.TARGET, "layout unknown: q0.30"),
            "poor layout, lane change" to Triple(input(poor), LaneArrowStyle.OTHER, "layout unknown: q0.30"),
            "old layout, lane change" to Triple(input(old), LaneArrowStyle.OTHER, "layout unknown: old 1.4 s"),
        )
        for ((name, case) in cases) {
            val (inp, style, status) = case
            val scene = run(ArSceneBuilder(), Clock(), 1.5) { inp.copy(debug = true) }
            val a = scene.laneArrows.singleOrNull()
            assertNotNull(name, a)
            assertNull(name, a!!.lane)
            assertEquals(name, style, a.style)
            assertEquals(name, if (style == LaneArrowStyle.OTHER) 0.55f * 0.45f else 0.55f, a.alpha, 1e-3f)
            assertEquals(name, status, scene.debug!!.laneStatus)
        }
        // A lane change asked 80 m before a right turn: the lone arrow does not take the turn's shape either.
        val nearTurn = run(ArSceneBuilder(), Clock(), 0.5) { input(none, route = route(Maneuver.TURN_RIGHT, "right"), distance = 80.0) }
        assertEquals(LaneArrowStyle.OTHER, nearTurn.laneArrows.single().style)
        assertEquals(LaneArrowGlyph.STRAIGHT, nearTurn.laneArrows.single().glyph)
    }

    @Test
    fun `the lone arrow runs along the road's vanishing point and is a lane wide`() {
        val keep = guidance(LaneAction.KEEP_LANE, listOf(2))
        val cases = mapOf(
            "road vanishing point" to (world(layout = null, roadVp = listOf(854.0, 400.0)) to 854.0),
            "road before the layout" to (world(layout(quality = 0.3, yawDeg = 17.0), roadVp = listOf(700.0, 400.0)) to 700.0),
            "poor layout's vanishing point" to (world(layout(quality = 0.3, yawDeg = 17.0)) to layout(yawDeg = 17.0).vpX),
            "old layout: principal point" to (world(layout(age = 1.4, yawDeg = 17.0)) to 640.0),
            "nothing: principal point" to (world(layout = null) to 640.0),
            // No road run and no layout for a while: the bridge's newest vanishing point of the session, not the camera axis.
            "old layout: the session's track" to (world(layout(age = 1.4, yawDeg = 17.0)).copy(trackVpX = 812.0) to 812.0),
            "nothing: the session's track" to (world(layout = null).copy(trackVpX = 812.0) to 812.0),
            "road before the track" to (world(layout = null, roadVp = listOf(854.0, 400.0)).copy(trackVpX = 812.0) to 854.0),
            "track outside the image: principal point" to (world(layout = null).copy(trackVpX = 1400.0) to 640.0),
        )
        for ((name, case) in cases) {
            val (w, vpX) = case
            val a = run(ArSceneBuilder(), Clock(), 0.5) { input(w, keep) }.laneArrows.single()
            val (base, tip) = baseAndTip(a)
            val axis = map.point(vpX, 0.0).x
            assertEquals("$name: base on the axis", axis, base.x, 1f)
            assertEquals("$name: tip on the axis", axis, tip.x, 1f)
            // The shaft is 0.13 of a 3.5 m lane wide at the arrow's near end, 6 m ahead.
            val lanePx = laneW / projector.lateralAt(projector.cx + 1.0, 6.0) * map.scale
            val baseRow = a.outline.filter { abs(it.y - base.y) < 0.5f }
            assertEquals(name, 0.13 * lanePx, (baseRow.maxOf { it.x } - baseRow.minOf { it.x }).toDouble(), 1.0)
        }
    }

    @Test
    fun `every arrow's axis runs through the point where its lane's two lines meet`() {
        for (yaw in listOf(0.0, 17.0, -12.0)) for (carPos in listOf(2.0, 2.4)) {
            val l = layout(yawDeg = yaw, carPos = carPos)
            val scene = run(ArSceneBuilder(), Clock(), 0.5) { input(world(l), guidance(LaneAction.KEEP_LANE, listOf(l.egoLane), current = l.egoLane)) }
            assertTrue("yaw $yaw car $carPos", scene.laneArrows.size >= 2)
            val vp = map.point(l.vpX, l.vpY)
            for (a in scene.laneArrows) {
                val name = "yaw $yaw car $carPos lane ${a.lane}"
                assertEquals(name, LaneArrowGlyph.STRAIGHT, a.glyph)
                val (base, tip) = baseAndTip(a)
                assertEquals("$name: base on the midline", 0.0, laneS(base, l, a.lane!!), 2e-3)
                assertEquals("$name: tip on the midline", 0.0, laneS(tip, l, a.lane!!), 2e-3)
                val xAtVp = base.x + (tip.x - base.x) * (vp.y - base.y) / (tip.y - base.y)
                assertEquals("$name: the axis hits the vanishing point", vp.x, xAtVp, 3f)
                // Inside its lane, between the two lines.
                assertTrue(name, a.outline.all { abs(laneS(it, l, a.lane!!)) < 0.2 })
            }
        }
    }

    @Test
    fun `arrows start 6 m ahead, never below the lowest line row, and end below the vanishing point`() {
        val scene = run(ArSceneBuilder(), Clock(), 0.5) { input() }
        val horizon = map.point(0.0, 400.0).y
        for (a in scene.laneArrows) {
            assertTrue("below the horizon", a.outline.all { it.y > horizon })
            // Nearer than before (9 m): 6 m, 1 m beyond the hood row, 6 m long.
            assertEquals(rowView(6.0), a.outline.maxOf { it.y }, 1f)
            assertEquals(rowView(12.0), a.outline.minOf { it.y }, 1f)
        }
        // Lines seen only down to row 520 (~7.7 m): the arrows start there.
        val short = run(ArSceneBuilder(), Clock(), 0.5) { input(world(layout(nearestRowY = 520.0))) }
        assertEquals(3, short.laneArrows.size)
        for (a in short.laneArrows) {
            assertEquals(map.point(0.0, 520.0).y, a.outline.maxOf { it.y }, 1f)
            val z0 = projector.forwardAtRow(520.0)!!
            assertEquals("still 6 m long", rowView(z0 + 6.0), a.outline.minOf { it.y }, 1f)
        }
        // A vanishing point low in the picture (row 470): the arrows end 8 px below it.
        val low = layout().copy(vpY = 470.0)
        val lowScene = run(ArSceneBuilder(), Clock(), 0.5) { input(world(low)) }
        assertTrue(lowScene.laneArrows.isNotEmpty())
        for (a in lowScene.laneArrows) assertEquals(map.point(0.0, 478.0).y, a.outline.minOf { it.y }, 1f)
        // Lines seen only far up (row 420, ~46 m): what is left below the vanishing point is a sliver, no arrow.
        assertTrue(run(ArSceneBuilder(), Clock(), 0.5) { input(world(layout(nearestRowY = 420.0))) }.laneArrows.isEmpty())
    }

    @Test
    fun `target arrows take the maneuver's shape within 100 m and are straight beyond`() {
        val l = layout()
        val keep = guidance(LaneAction.KEEP_LANE, listOf(2))
        val near = run(ArSceneBuilder(), Clock(), 0.5) { input(world(l), keep, route(Maneuver.TURN_RIGHT, "right"), 80.0) }
        assertEquals(LaneArrowGlyph.TURN_RIGHT, near.lane(2)!!.glyph)
        assertEquals("other lanes stay straight", LaneArrowGlyph.STRAIGHT, near.lane(1)!!.glyph)
        // Beyond 60 % of its length (the bend and head) the glyph reaches much farther right of the midline than left.
        val head = near.lane(2)!!.outline.filter { it.y < rowView(6.0 + 0.6 * 6.0) }.map { laneS(it, l, 2) }
        assertTrue("the head points right: ${head.min()}..${head.max()}", head.max() > 0.35 && head.max() > 1.3 * -head.min())
        assertTrue("inside the lane lines", near.lane(2)!!.outline.all { abs(laneS(it, l, 2)) <= LaneArrows.MAX_S + 1e-3 })
        val far = run(ArSceneBuilder(), Clock(), 0.5) { input(world(l), keep, route(Maneuver.TURN_RIGHT, "right"), 150.0) }
        assertEquals(LaneArrowGlyph.STRAIGHT, far.lane(2)!!.glyph)
        val lone = run(ArSceneBuilder(), Clock(), 0.5) { input(world(layout = null), lane = null, route = route(Maneuver.TURN_LEFT, "left"), distance = 60.0) }
        assertEquals(LaneArrowGlyph.TURN_LEFT, lone.laneArrows.single().glyph)
        assertEquals("maneuver within 300 m: emphasised", 0.9f, lone.laneArrows.single().alpha, 1e-3f)

        assertEquals(LaneArrowGlyph.BEAR_LEFT, LaneArrows.targetGlyph(route(Maneuver.KEEP_LEFT, "left", exitNumber = "94"), 50.0))
        assertEquals(LaneArrowGlyph.BEAR_RIGHT, LaneArrows.targetGlyph(route(Maneuver.EXIT, "right"), 50.0))
        assertEquals("exit side unknown: straight", LaneArrowGlyph.STRAIGHT, LaneArrows.targetGlyph(route(Maneuver.EXIT, "exit"), 50.0))
        assertEquals(LaneArrowGlyph.BEAR_RIGHT, LaneArrows.targetGlyph(route(Maneuver.MERGE_RIGHT), 50.0))
        assertEquals(LaneArrowGlyph.STRAIGHT, LaneArrows.targetGlyph(route(Maneuver.FOLLOW_ROAD), 50.0))
        assertEquals(LaneArrowGlyph.STRAIGHT, LaneArrows.targetGlyph(route(Maneuver.TURN_RIGHT), null))
    }

    @Test
    fun `arrows lie flat on the road in perspective`() {
        val scene = run(ArSceneBuilder(), Clock(), 0.5) { input() }
        val ego = scene.lane(2)!!
        val pts = ego.outline
        val cx = map.point(projector.cx, 0.0).x
        // Straight road, camera on the lane centre: the outline is left-right symmetric around it.
        for (p in pts) {
            assertTrue("mirror of $p", pts.any { q -> abs(q.y - p.y) < 0.5f && abs(q.x - (2 * cx - p.x)) < 0.5f })
        }
        // Foreshortening: 1 m of road at the near end takes more rows than 1 m at the far end.
        assertTrue(rowView(6.0) - rowView(7.0) > 1.5f * (rowView(11.0) - rowView(12.0)))
        // The side lanes' arrows lean towards the vanishing point: tips inwards of their bases.
        val (leftBase, leftTip) = baseAndTip(scene.lane(1)!!)
        assertTrue(leftTip.x > leftBase.x)
        val (rightBase, rightTip) = baseAndTip(scene.lane(3)!!)
        assertTrue(rightTip.x < rightBase.x)
    }

    @Test
    fun `every glyph outline is a simple polygon inside its lane`() {
        for (g in LaneArrowGlyph.entries) {
            val o = LaneArrows.outline(g)
            val n = o.size
            assertTrue(g.name, n > 20)
            for (i in 0 until n) {
                assertTrue("$g inside the lane lines: ${o.s[i]}", abs(o.s[i]) <= LaneArrows.MAX_S)
                assertTrue("$g within its length", o.t[i] in 0.0..1.0)
            }
            assertEquals("$g starts at the near end", 0.0, o.t.min(), 1e-9)
            assertEquals("$g reaches the far end", 1.0, o.t.max(), 0.02)
            for (i in 0 until n) for (j in i + 2 until n) {
                if (i == 0 && j == n - 1) continue // adjacent through the closing edge
                val hit = crosses(o.s[i], o.t[i], o.s[(i + 1) % n], o.t[(i + 1) % n], o.s[j], o.t[j], o.s[(j + 1) % n], o.t[(j + 1) % n])
                assertFalse("$g edges $i and $j cross", hit)
            }
        }
        // Painted-arrow proportions: a narrow shaft, a head about a third of the length.
        val straight = LaneArrows.outline(LaneArrowGlyph.STRAIGHT)
        val shaft = (0 until straight.size).filter { straight.t[it] < 0.5 }.maxOf { abs(straight.s[it]) }
        assertEquals(0.065, shaft, 1e-3)
        assertEquals(0.19, (0 until straight.size).maxOf { abs(straight.s[it]) }, 1e-3)
    }

    private fun crosses(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double): Boolean {
        fun side(px: Double, py: Double, qx: Double, qy: Double, rx: Double, ry: Double) = (qx - px) * (ry - py) - (qy - py) * (rx - px)
        val d1 = side(cx, cy, dx, dy, ax, ay)
        val d2 = side(cx, cy, dx, dy, bx, by)
        val d3 = side(ax, ay, bx, by, cx, cy)
        val d4 = side(ax, ay, bx, by, dx, dy)
        return ((d1 > 1e-12 && d2 < -1e-12) || (d1 < -1e-12 && d2 > 1e-12)) && ((d3 > 1e-12 && d4 < -1e-12) || (d3 < -1e-12 && d4 > 1e-12))
    }

    @Test
    fun `a red arrow does not linger when the layout becomes unusable or perception goes stale`() {
        val cases = mapOf("poor layout" to input(world(layout(quality = 0.3))), "stale perception" to input(world(stale = true)))
        for ((name, after) in cases) {
            val b = ArSceneBuilder()
            val clock = Clock()
            assertEquals(name, LaneArrowStyle.WRONG, run(b, clock, 1.2) { input() }.lane(2)!!.style)
            clock.ns += frameNs
            val scene = b.build(after, viewW, viewH, clock.ns)
            assertTrue(name, scene.laneArrows.none { it.style == LaneArrowStyle.WRONG })
            assertEquals("$name: the car's lane fades out white", LaneArrowStyle.OTHER, scene.lane(2)!!.style)
        }
    }

    @Test
    fun `a lane choice lost for one run neither clears the wrong-lane look nor counts as wrong-lane time`() {
        // A poor layout; the Driving Context's lane guidance is UNKNOWN then too.
        val low = { input(world(layout(quality = 0.3)), guidance(LaneAction.UNKNOWN, emptyList())) }
        val b = ArSceneBuilder()
        val clock = Clock()
        // Wrong for 0.5 s, one 0.13 s run without a layout, wrong again: red once 0.8 s of wrong-lane time add up.
        run(b, clock, 0.5) { input() }
        var scene = run(b, clock, 0.13, input = low)
        assertTrue(scene.laneArrows.none { it.style == LaneArrowStyle.WRONG })
        scene = run(b, clock, 0.25) { input() }
        assertEquals("0.75 s wrong so far", LaneArrowStyle.OTHER, scene.lane(2)!!.style)
        scene = run(b, clock, 0.1) { input() }
        assertEquals(LaneArrowStyle.WRONG, scene.lane(2)!!.style)

        // Already red: a 0.13 s gap draws no red and no green over the car's lane, and the red is back with the layout.
        scene = run(b, clock, 0.13, input = low)
        assertTrue(scene.laneArrows.none { it.style == LaneArrowStyle.WRONG || it.style == LaneArrowStyle.TARGET })
        assertEquals(LaneArrowStyle.OTHER, scene.lane(null)!!.style)
        clock.ns += frameNs
        assertEquals(LaneArrowStyle.WRONG, b.build(input(), viewW, viewH, clock.ns).lane(2)!!.style)

        // A gap longer than 0.4 s clears the look: 0.8 s again before red.
        run(b, clock, 0.6, input = low)
        scene = run(b, clock, 0.5) { input() }
        assertTrue(scene.laneArrows.none { it.style == LaneArrowStyle.WRONG })
        scene = run(b, clock, 0.4) { input() }
        assertEquals(LaneArrowStyle.WRONG, scene.lane(2)!!.style)
    }

    @Test
    fun `targets beyond the visible lanes draw nothing green and nothing red`() {
        val scene = run(ArSceneBuilder(), Clock(), 1.5) { input(lane = guidance(LaneAction.CHANGE_LANE_RIGHT, listOf(4, 5)), debug = true) }
        assertEquals(listOf<Int?>(1, 2, 3), scene.lanes())
        assertTrue(scene.laneArrows.all { it.style == LaneArrowStyle.OTHER })
        assertEquals("layout 2/3 q0.80 targets -", scene.debug!!.laneStatus)
    }

    @Test
    fun `crossing into the next lane keeps every arrow on its own lane`() {
        val b = ArSceneBuilder()
        val clock = Clock()
        val at = { carPos: Double -> input(world(layout(carPos = carPos)), debug = true) }
        run(b, clock, 1.2) { at(2.0) }
        // Drift right inside lane 2, up to the line: the lines slide left, every arrow with its lane.
        var scene = run(b, clock, 1.0) { at(2.45) }
        assertEquals(LaneArrowStyle.WRONG, scene.lane(2)!!.style)
        for (a in scene.laneArrows) assertEquals("lane ${a.lane}", 0.0, laneS(baseAndTip(a).second, layout(carPos = 2.45), a.lane!!), 0.01)
        // Across the line: the same four lines, the car's lane is now 3 (a target). The arrows glide with the lines and
        // stay in their lanes; nothing red in the lane the car just entered.
        clock.ns += frameNs
        scene = b.build(at(2.55), viewW, viewH, clock.ns)
        for (a in scene.laneArrows) assertTrue("lane ${a.lane}", abs(laneS(baseAndTip(a).second, layout(carPos = 2.55), a.lane!!)) < 0.15)
        assertTrue(scene.laneArrows.none { it.style == LaneArrowStyle.WRONG })
        assertEquals(LaneArrowStyle.TARGET_BLINK, scene.lane(3)!!.style)
        scene = run(b, clock, 1.0) { at(2.55) }
        for (a in scene.laneArrows) assertEquals("lane ${a.lane}", 0.0, laneS(baseAndTip(a).second, layout(carPos = 2.55), a.lane!!), 0.01)
        assertEquals(LaneArrowStyle.TARGET, scene.lane(3)!!.style)
        assertEquals(LaneArrowStyle.OTHER, scene.lane(2)!!.style)
        assertEquals("layout 3/3 q0.80 targets 3", scene.debug!!.laneStatus)
    }

    @Test
    fun `debug shows the layout lines, inserted ones dashed, its vanishing point and the lane state`() {
        val l = layout(yawDeg = 12.0, detected = { it != 2 })
        val b = ArSceneBuilder()
        val clock = Clock()
        var scene = run(b, clock, 0.5) { input(world(l), debug = true) }
        var d = scene.debug!!
        assertEquals("layout 2/3 q0.80 targets 3", d.laneStatus)
        assertTrue(d.layoutUsed)
        assertEquals(listOf(true, true, false, true), d.layoutLines.map { it.detected })
        val vp = map.point(l.vpX, l.vpY)
        assertEquals(vp.x, d.vanishingPoint!!.x, 0.5f)
        assertEquals(vp.y, d.vanishingPoint!!.y, 0.5f)
        for ((i, line) in d.layoutLines.withIndex()) {
            val (top, bottom) = line.points
            assertEquals(map.point(l.lineX(i, l.vpY + 8.0), l.vpY + 8.0).x, top.x, 0.5f)
            assertEquals(map.point(l.lineX(i, 720.0), 720.0).x, bottom.x, 0.5f)
        }
        scene = run(b, clock, 0.5) { input(world(l), debug = true) }
        assertEquals("layout 2/3 q0.80 targets 3 WRONG", scene.debug!!.laneStatus)
        // A poor layout is still drawn, for reference, but the arrows do not use it.
        d = run(ArSceneBuilder(), Clock(), 0.2) { input(world(layout(quality = 0.3)), debug = true) }.debug!!
        assertFalse(d.layoutUsed)
        assertEquals(4, d.layoutLines.size)
        assertEquals("layout unknown: q0.30", d.laneStatus)
        assertEquals("layout unknown", run(ArSceneBuilder(), Clock(), 0.2) { input(world(layout = null), debug = true) }.debug!!.laneStatus)
    }

    @Test
    fun `nothing without a live route, on stale perception or without a camera model`() {
        val cases = mapOf(
            "no route" to input(route = null),
            "stale route" to input(route = route(Maneuver.EXIT, "right", stale = true)),
            "off route" to input(route = route(Maneuver.EXIT, "right", offRoute = true)),
            "stale perception" to input(world = world(stale = true)),
            "no camera" to input(world = world().copy(camera = null)),
        )
        for ((name, inp) in cases) {
            assertTrue(name, run(ArSceneBuilder(), Clock(), 0.5) { inp }.laneArrows.isEmpty())
        }
        // Shown, then the route goes: faded out within 0.35 s.
        val b = ArSceneBuilder()
        val clock = Clock()
        assertTrue(run(b, clock, 0.5) { input() }.laneArrows.isNotEmpty())
        assertTrue(run(b, clock, 0.4) { input(route = null) }.laneArrows.isEmpty())
    }

    @Test
    fun `road users over the arrows are clipped out, others are not`() {
        // A car 9 m ahead in the car's lane sits on the arrow; a pedestrian up above the horizon and a sign do not count.
        val bl = projector.toImage(Ground(-0.9, 9.0))!!
        val br = projector.toImage(Ground(0.9, 9.0))!!
        val carBox = listOf(bl.x.toDouble(), bl.y - 70.0, br.x.toDouble(), bl.y.toDouble())
        val objects = listOf(
            car(1, ObjectClass.CAR, carBox),
            car(2, ObjectClass.PEDESTRIAN, listOf(100.0, 300.0, 110.0, 340.0)),
            car(3, ObjectClass.TRAFFIC_SIGN, carBox),
            car(4, ObjectClass.TRUCK, carBox, visible = false),
            car(5, ObjectClass.BICYCLE, carBox.map { it + 1.0 }),
        )
        val scene = run(ArSceneBuilder(), Clock(), 0.5) { input(world = world(objects = objects)) }
        assertEquals(2, scene.occluders.size)
        val r = scene.occluders.first()
        val box = map.box(carBox)!!
        assertEquals(box.left - 4f, r.left, 1e-3f)
        assertEquals(box.bottom + 4f, r.bottom, 1e-3f)
        assertTrue("no arrows, no clipping", run(ArSceneBuilder(), Clock(), 0.5) { input(route = null, world = world(objects = objects)) }.occluders.isEmpty())
    }

    @Test
    fun `a lane gained or lost at the left keeps every arrow's fade on its own lane`() {
        // The same painted lines, without and with the line left of the car's left neighbour lane.
        val two = layout(carPos = 2.0, lines = 3)
        val three = layout(carPos = 3.0, lines = 4)
        assertEquals(2, two.egoLane)
        assertEquals(3, three.egoLane)
        assertEquals(1, LaneArrows.lineOffset(two, three))
        assertEquals(-1, LaneArrows.lineOffset(three, two))
        assertEquals(0, LaneArrows.lineOffset(two, two))
        val other = two.copy(lines = two.lines.map { it.copy(slope = it.slope * 2.5) })
        assertNull("different lines", LaneArrows.lineOffset(two, other))

        fun noTwoInOneLane(scene: ArScene, name: String) {
            val xs = scene.laneArrows.map { baseAndTip(it).first.x }.sorted()
            assertTrue("$name: $xs", xs.zipWithNext().all { (a, b) -> b - a > 300f })
        }
        val own = 0.55f
        val faint = 0.55f * 0.45f
        // Gained: the car's green arrow and its neighbour keep their opacity, only the new lane fades in.
        val clock = Clock()
        var b = ArSceneBuilder()
        run(b, clock, 0.6) { input(world(two), lane = null) }
        var gained = 0f
        repeat(25) { i ->
            clock.ns += frameNs
            val scene = b.build(input(world(three), lane = null), viewW, viewH, clock.ns)
            val name = "gained, frame $i"
            assertEquals(name, LaneArrowStyle.TARGET, scene.lane(3)!!.style)
            assertEquals(name, own, scene.lane(3)!!.alpha, 1e-3f)
            assertEquals(name, faint, scene.lane(2)!!.alpha, 1e-3f)
            val a = scene.lane(1)?.alpha ?: 0f
            assertTrue("$name: the new lane fades in, $gained -> $a", a >= gained && a <= faint + 1e-3f)
            gained = a
            assertTrue(name, scene.laneArrows.none { it.lane == null })
            noTwoInOneLane(scene, name)
        }
        assertEquals(faint, gained, 1e-3f)

        // Lost: the lanes left keep their opacity; the lost lane is no lane of the layout any more and fades out where it was.
        b = ArSceneBuilder()
        run(b, clock, 0.6) { input(world(three), lane = null) }
        var lost = Float.MAX_VALUE
        repeat(25) { i ->
            clock.ns += frameNs
            val scene = b.build(input(world(two), lane = null), viewW, viewH, clock.ns)
            val name = "lost, frame $i"
            assertEquals(name, LaneArrowStyle.TARGET, scene.lane(2)!!.style)
            assertEquals(name, own, scene.lane(2)!!.alpha, 1e-3f)
            assertEquals(name, faint, scene.lane(1)!!.alpha, 1e-3f)
            val gone = scene.laneArrows.filter { it.lane == null }
            assertTrue(name, gone.size <= 1)
            gone.singleOrNull()?.let { assertTrue("$name: left of lane 1", baseAndTip(it).first.x < baseAndTip(scene.lane(1)!!).first.x) }
            val a = gone.singleOrNull()?.alpha ?: 0f
            if (i == 0) assertTrue("$name: fades, does not vanish: $a", a > 0.2f)
            assertTrue("$name: $lost -> $a", a <= lost)
            lost = a
            noTwoInOneLane(scene, name)
        }
        assertEquals(0f, lost, 0f)
    }

    @Test
    fun `the arrows follow the bridge's stable flag, so a quality hovering at the threshold does not flicker them`() {
        // The bridge keeps a stable layout stable down to a lower quality, and a new one needs more than the threshold.
        val held = run(ArSceneBuilder(), Clock(), 0.5) { input(world(layout(quality = 0.35).copy(stable = true)), debug = true) }
        assertEquals(listOf<Int?>(1, 2, 3), held.lanes())
        assertEquals("layout 2/3 q0.35 targets 3", held.debug!!.laneStatus)
        val notYet = run(ArSceneBuilder(), Clock(), 0.5) { input(world(layout(quality = 0.8).copy(stable = false)), debug = true) }
        assertNull(notYet.laneArrows.single().lane)
        assertEquals("layout unknown: unstable q0.80", notYet.debug!!.laneStatus)

        // The car in the wrong lane, red: runs alternating q0.41 / q0.38 every 0.1 s, all stable, keep the red and the
        // blinking target, and never bring up the lone arrow.
        val b = ArSceneBuilder()
        val clock = Clock()
        assertEquals(LaneArrowStyle.WRONG, run(b, clock, 1.2) { input() }.lane(2)!!.style)
        for (i in 0 until 20) {
            val l = layout(quality = if (i % 2 == 0) 0.41 else 0.38).copy(stable = true)
            val scene = run(b, clock, 0.1) { input(world(l)) }
            assertEquals("run $i", LaneArrowStyle.WRONG, scene.lane(2)!!.style)
            assertEquals("run $i", LaneArrowStyle.TARGET_BLINK, scene.lane(3)!!.style)
            assertTrue("run $i", scene.laneArrows.none { it.lane == null })
        }
    }

    @Test
    fun `the emphasis fades when the lane choice comes and goes`() {
        val b = ArSceneBuilder()
        val clock = Clock()
        // A lane choice 400 m before the exit: emphasised (0.9). Lane 1 stays faint white throughout.
        var last = run(b, clock, 0.5) { input() }.lane(1)!!.alpha
        assertEquals(0.9f * 0.45f, last, 1e-3f)
        val maxStep = 0.45f * (0.9f - 0.55f) * (frameNs / 1e9f) / 0.35f + 1e-4f
        repeat(30) { i ->
            clock.ns += frameNs
            val a = b.build(input(lane = null), viewW, viewH, clock.ns).lane(1)!!.alpha
            assertTrue("choice gone, frame $i: $last -> $a", a <= last + 1e-6f && last - a <= maxStep)
            last = a
        }
        assertEquals(0.55f * 0.45f, last, 1e-3f)
        repeat(30) { i ->
            clock.ns += frameNs
            val a = b.build(input(), viewW, viewH, clock.ns).lane(1)!!.alpha
            assertTrue("choice back, frame $i: $last -> $a", a >= last - 1e-6f && a - last <= maxStep)
            last = a
        }
        assertEquals(0.9f * 0.45f, last, 1e-3f)
    }

    @Test
    fun `targets numbered on another lane count stay on the lanes they were numbered on`() {
        val three = layout(carPos = 2.0, lines = 4)
        // A line gained at the left: the same lanes are now 2..4, the car in lane 3.
        val four = layout(carPos = 3.0, lines = 5)
        val ctx3 = guidance(LaneAction.CHANGE_LANE_RIGHT, listOf(3), current = 2, count = 3)
        val ctx4 = guidance(LaneAction.CHANGE_LANE_RIGHT, listOf(4), current = 3, count = 4)
        val b = ArSceneBuilder()
        val clock = Clock()
        var scene = run(b, clock, 1.2) { input(world(three), ctx3, debug = true) }
        assertEquals(LaneArrowStyle.WRONG, scene.lane(2)!!.style)
        val rightLaneX = baseAndTip(scene.lane(3)!!).first.x

        // The display is a lanes run ahead of the Driving Context: it keeps the layout the targets were numbered on.
        scene = run(b, clock, 0.2) { input(world(four), ctx3, debug = true) }
        assertEquals(listOf<Int?>(1, 2, 3), scene.lanes())
        assertEquals(LaneArrowStyle.TARGET_BLINK, scene.lane(3)!!.style)
        assertEquals(LaneArrowStyle.WRONG, scene.lane(2)!!.style)
        assertTrue(scene.debug!!.laneStatus, scene.debug!!.laneStatus!!.startsWith("layout 2/3 "))

        // The context catches up: the same lanes, renumbered, keep their colours and their fades.
        clock.ns += frameNs
        scene = b.build(input(world(four), ctx4, debug = true), viewW, viewH, clock.ns)
        assertEquals(listOf<Int?>(1, 2, 3, 4), scene.lanes())
        assertEquals(LaneArrowStyle.TARGET_BLINK, scene.lane(4)!!.style)
        assertEquals("the same lane", rightLaneX, baseAndTip(scene.lane(4)!!).first.x, 1f)
        assertTrue("not fading in again: ${scene.lane(4)!!.alpha}", scene.lane(4)!!.alpha > 0.15f)
        assertEquals(LaneArrowStyle.WRONG, scene.lane(3)!!.style)
        assertTrue("only the new lane fades in", scene.lane(1)!!.alpha < 0.05f)

        // A context that stays on the old count: after 0.4 s the display moves on, and no green or red lands on lanes
        // numbered on another count (its target 3 is now the car's own lane).
        val b2 = ArSceneBuilder()
        run(b2, clock, 1.2) { input(world(three), ctx3, debug = true) }
        scene = run(b2, clock, 1.0) { input(world(four), ctx3, debug = true) }
        assertEquals(listOf<Int?>(1, 2, 3, 4), scene.lanes())
        assertTrue(scene.laneArrows.all { it.style == LaneArrowStyle.OTHER })
        assertEquals("layout 3/4 q0.80 targets -", scene.debug!!.laneStatus)
    }

    @Test
    fun `with a drivable outline every arrow starts above the road's bottom edge in its own lane and is clipped to the road`() {
        val l = layout()
        val scene = run(ArSceneBuilder(), Clock(), 0.5) { input(world(l, drivable = dashAndPillar)) }
        assertEquals(listOf<Int?>(1, 2, 3), scene.lanes())
        // Lane 1's midline meets the A-pillar edge x = 5 (y - 460) above the dashboard; lanes 2 and 3 end at the dashboard.
        val m = (l.lines[0].slope + l.lines[1].slope) / 2
        val pillarY = (l.vpX - m * l.vpY + 2300.0) / (5.0 - m)
        assertTrue("on the pillar edge: $pillarY", pillarY in 461.0..539.0)
        val bottoms = mapOf(1 to pillarY, 2 to 540.0, 3 to 540.0)
        for (a in scene.laneArrows) {
            val z0 = projector.forwardAtRow(bottoms.getValue(a.lane!!))!! + LaneArrows.START_GAP_M
            assertEquals("lane ${a.lane}: 1 m beyond the road's edge", rowView(z0), a.outline.maxOf { it.y }, 1f)
            assertTrue("lane ${a.lane} on the road", a.outline.all { inside(img(it), dashAndPillar) })
        }
        assertTrue(scene.lane(1)!!.outline.maxOf { it.y } < scene.lane(2)!!.outline.maxOf { it.y } - 10f)
        // The canvas clips the fills to the road's outline, in view pixels.
        assertEquals(dashAndPillar.map { map.point(it[0], it[1]) }, scene.drivable)

        // The lone arrow too.
        val lone = run(ArSceneBuilder(), Clock(), 0.5) {
            input(world(layout = null, drivable = dashAndPillar), guidance(LaneAction.KEEP_LANE, listOf(2)))
        }.laneArrows.single()
        assertEquals(rowView(projector.forwardAtRow(540.0)!! + 1.0), lone.outline.maxOf { it.y }, 1f)

        // No outline, or only one from an old road run: placed as without (6 m ahead), nothing to clip to.
        for (w in listOf(world(l), world(l, drivable = dashAndPillar, roadAge = 2.0))) {
            val s = run(ArSceneBuilder(), Clock(), 0.5) { input(w) }
            assertTrue(s.drivable.isEmpty())
            assertEquals(3, s.laneArrows.size)
            for (a in s.laneArrows) assertEquals(rowView(6.0), a.outline.maxOf { it.y }, 1f)
        }
    }

    @Test
    fun `a lane whose midline misses the drivable road, or with too little road above its edge, gets no arrow`() {
        val rightHalf = listOf(listOf(700.0, 405.0), listOf(1280.0, 405.0), listOf(1280.0, 720.0), listOf(700.0, 720.0))
        assertEquals(listOf<Int?>(3), run(ArSceneBuilder(), Clock(), 0.5) { input(world(drivable = rightHalf)) }.lanes())
        assertNull(LaneArrows.roadBottomRow(LaneArrows.strip(layout(), 1)!!, rightHalf, 720.0))
        val dashHigh = listOf(listOf(0.0, 405.0), listOf(1280.0, 405.0), listOf(1280.0, 425.0), listOf(0.0, 425.0))
        assertTrue(run(ArSceneBuilder(), Clock(), 0.5) { input(world(drivable = dashHigh)) }.laneArrows.isEmpty())

        val ego = LaneArrows.strip(layout(), 2)!!
        // A car standing in the lane notches the outline higher up: the arrow still starts above the dashboard.
        val notched = listOf(
            listOf(0.0, 405.0), listOf(560.0, 405.0), listOf(560.0, 470.0), listOf(720.0, 470.0), listOf(720.0, 405.0),
            listOf(1280.0, 405.0), listOf(1280.0, 540.0), listOf(0.0, 540.0),
        )
        assertEquals(540.0, LaneArrows.roadBottomRow(ego, notched, 720.0)!!, 1e-9)
        val whole = listOf(listOf(0.0, 405.0), listOf(1280.0, 405.0), listOf(1280.0, 800.0), listOf(0.0, 800.0))
        assertEquals("the road reaches the bottom of the image", 720.0, LaneArrows.roadBottomRow(ego, whole, 720.0)!!, 1e-9)
    }

    @Test
    fun `a fresh road run with an empty outline draws no arrow once the server has sent outlines`() {
        val l = layout()
        val keep = guidance(LaneAction.KEEP_LANE, listOf(2))
        // A road run without an outline (roadVp only: the field decodes to empty).
        val noRoad = { lay: LaneLayout? -> input(world(lay, roadVp = listOf(640.0, 400.0)), keep, debug = true) }
        val b = ArSceneBuilder()
        val clock = Clock()
        assertEquals(listOf<Int?>(1, 2, 3), run(b, clock, 0.5) { input(world(l, drivable = dashAndPillar), keep) }.lanes())
        // Stopped close behind a car, the server sees no road: nothing at once, the layout's lanes or the lone arrow.
        for (lay in listOf(l, null)) {
            clock.ns += frameNs
            val scene = b.build(noRoad(lay), viewW, viewH, clock.ns)
            assertTrue("layout $lay", scene.laneArrows.isEmpty())
            assertTrue(scene.drivable.isEmpty())
            assertTrue(scene.debug!!.laneStatus!!, scene.debug!!.laneStatus!!.endsWith(" | no road"))
        }
        assertTrue(run(b, clock, 2.0) { noRoad(null) }.laneArrows.isEmpty())
        // The road back: the arrows fade in on it.
        val back = run(b, clock, 0.1) { input(world(l, drivable = dashAndPillar), keep) }
        assertEquals(listOf<Int?>(1, 2, 3), back.lanes())
        assertTrue(back.laneArrows.all { a -> a.outline.all { inside(img(it), dashAndPillar) } })
        // A one-run gap does not restart the fade from nothing.
        val full = run(b, clock, 0.5) { input(world(l, drivable = dashAndPillar), keep) }.lane(2)!!.alpha
        run(b, clock, 0.07) { noRoad(l) }
        clock.ns += frameNs
        val after = b.build(input(world(l, drivable = dashAndPillar), keep), viewW, viewH, clock.ns).lane(2)!!.alpha
        assertTrue("$full -> $after", after > 0.6f * full)

        // A server that never sent an outline (an old one): an empty field is no outline, the arrows are drawn unclipped.
        val old = run(ArSceneBuilder(), Clock(), 0.5) { noRoad(l) }
        assertEquals(listOf<Int?>(1, 2, 3), old.lanes())
        assertTrue(old.drivable.isEmpty())
        // No road run at all is not "no road" either: the lone arrow along the track.
        val gone = ArSceneBuilder()
        val c2 = Clock()
        run(gone, c2, 0.5) { input(world(l, drivable = dashAndPillar), keep) }
        assertNull(run(gone, c2, 0.5) { input(world(layout = null), keep) }.laneArrows.single().lane)
    }

    @Test
    fun `an arrow fading out on its old strip is not drawn once a new outline puts its near end below the road`() {
        val l = layout()
        val keep = guidance(LaneAction.KEEP_LANE, listOf(2))
        // The road's bottom edge now at row 480 (was 540): the lanes' arrows start near row 521.
        val higher = listOf(listOf(0.0, 405.0), listOf(1280.0, 405.0), listOf(1280.0, 480.0), listOf(0.0, 480.0))
        for ((name, poly) in mapOf("same outline" to dashAndPillar, "higher outline" to higher)) {
            val b = ArSceneBuilder()
            val clock = Clock()
            run(b, clock, 0.5) { input(world(l, drivable = dashAndPillar), keep) }
            // The layout drops out: the lanes fade on their old strips, the lone arrow fades in on the new outline.
            clock.ns += frameNs
            val scene = b.build(input(world(layout = null, drivable = poly), keep), viewW, viewH, clock.ns)
            val fading = scene.laneArrows.mapNotNull { it.lane }.sorted()
            assertEquals(name, if (poly == dashAndPillar) listOf(1, 2, 3) else emptyList(), fading)
            val lone = scene.laneArrows.single { it.lane == null }
            assertTrue(name, lone.outline.all { inside(img(it), poly) })
        }
        val strip = LaneArrows.strip(l, 2)!!.copy(roadBottomY = 540.0)
        val span = LaneArrows.span(strip, projector)!!
        assertTrue(LaneArrows.nearEndOnRoad(strip, span, dashAndPillar, projector))
        assertFalse(LaneArrows.nearEndOnRoad(strip, span, higher, projector))
    }
}
