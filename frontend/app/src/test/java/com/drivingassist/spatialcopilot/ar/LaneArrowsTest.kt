package com.drivingassist.spatialcopilot.ar

import com.drivingassist.copilot.context.DrivingContext
import com.drivingassist.copilot.context.FrameTiming
import com.drivingassist.copilot.context.LaneAction
import com.drivingassist.copilot.context.LaneGuidance
import com.drivingassist.copilot.context.LanesState
import com.drivingassist.copilot.context.Maneuver
import com.drivingassist.copilot.context.ObjectState
import com.drivingassist.copilot.context.Priority
import com.drivingassist.copilot.context.WorldSnapshot
import com.drivingassist.copilot.perception.Camera
import com.drivingassist.copilot.perception.ImageSize
import com.drivingassist.copilot.perception.Lanes
import com.drivingassist.copilot.perception.ObjectClass
import com.drivingassist.spatialcopilot.nav.RouteGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LaneArrowsTest {
    private val laneW = 3.6
    private val viewW = 2560f
    private val viewH = 1600f
    private val projector = GroundProjector(525.0, 480.0, 270.0, 250.0, 1.25, 960, 540)
    private val map = FillCenter(960, 540, viewW, viewH)
    private val frameNs = 16_000_000L

    /**
     * Straight road seen from a camera 1.25 m up. [carPos] is the car's lateral position in lanes (lane k's
     * centre is at k), so the lines around it move when it changes lane; [current] is what the lane model reports.
     */
    private fun world(
        carPos: Double = 2.0,
        current: Int? = 2,
        count: Int? = 3,
        conf: Double = 0.82,
        age: Double = 0.0,
        lineCount: Int = (count ?: 3) + 1,
        objects: List<ObjectState> = emptyList(),
        stale: Boolean = false,
    ): WorldSnapshot {
        val lines = (0 until lineCount).map { j ->
            (0..12).mapNotNull { i ->
                val z = 3.0 + i * 5.0
                projector.toImage(Ground((j + 0.5 - carPos) * laneW, z))?.let { listOf(it.x.toDouble(), it.y.toDouble()) }
            }
        }
        return WorldSnapshot(
            timing = FrameTiming("test", 1, 1, 1.0, 0L, 0L, 0.0, 0.0, 0.0, 15.0, 15.0, 1, 0L),
            image = ImageSize(960, 540),
            camera = Camera(focalPx = 525.0, principalPoint = listOf(480.0, 270.0), horizonY = 250.0, cameraHeightMeters = 1.25),
            objects = objects.associateBy { it.id },
            lanes = LanesState(Lanes(current, count, lines, conf), currentLane = current, laneCount = count, measuredPts = 1.0, ageSeconds = age),
            perceptionStale = stale,
        )
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

    /** Runs the builder at ~60 Hz for [seconds]; returns the last scene. */
    private class Clock(var ns: Long = 1_000_000_000L)

    private fun run(b: ArSceneBuilder, clock: Clock, seconds: Double, input: () -> ArInput): ArScene {
        var scene = b.build(input(), viewW, viewH, clock.ns)
        val frames = (seconds * 1e9 / frameNs).toInt()
        repeat(frames) {
            clock.ns += frameNs
            scene = b.build(input(), viewW, viewH, clock.ns)
        }
        return scene
    }

    private fun ArScene.lane(k: Int?): LaneArrow? = laneArrows.firstOrNull { it.lane == k }

    /** View x of a straight arrow's tip (the far end, on the lane centre). */
    private fun tipX(a: LaneArrow): Float = a.outline.minBy { it.y }.x

    @Test
    fun `lane choice - target green, then the car's lane red and targets blinking after the debounce, green again after the lane change`() {
        val b = ArSceneBuilder()
        val clock = Clock()
        var scene = run(b, clock, 0.6) { input() }
        assertEquals(listOf(1, 2, 3), scene.laneArrows.map { it.lane }.sortedBy { it })
        assertEquals(LaneArrowStyle.TARGET, scene.lane(3)!!.style)
        assertEquals("not red before 0.8 s", LaneArrowStyle.OTHER, scene.lane(2)!!.style)
        assertEquals(LaneArrowStyle.OTHER, scene.lane(1)!!.style)
        assertEquals("faded in, emphasised", 0.9f, scene.lane(3)!!.alpha, 1e-3f)
        assertEquals("other lanes faint", 0.9f * 0.45f, scene.lane(1)!!.alpha, 1e-3f)

        scene = run(b, clock, 0.3) { input() }
        assertEquals(LaneArrowStyle.WRONG, scene.lane(2)!!.style)
        assertEquals(LaneArrowStyle.TARGET_BLINK, scene.lane(3)!!.style)
        // Slow pulse: the target's alpha swings over one period.
        val alphas = ArrayList<Float>()
        repeat(75) { clock.ns += frameNs; alphas += b.build(input(), viewW, viewH, clock.ns).lane(3)!!.alpha }
        assertTrue("pulse ${alphas.min()}..${alphas.max()}", alphas.min() < 0.35f && alphas.max() > 0.85f)

        // The lane model now puts the car in lane 3 (a target): no red at once, blinking for 0.3 s more, then steady green.
        val inLane3 = { input(world(current = 3), guidance(LaneAction.KEEP_LANE, listOf(3), current = 3)) }
        clock.ns += frameNs
        scene = b.build(inLane3(), viewW, viewH, clock.ns)
        assertTrue(scene.laneArrows.none { it.style == LaneArrowStyle.WRONG })
        assertEquals(LaneArrowStyle.TARGET_BLINK, scene.lane(3)!!.style)
        scene = run(b, clock, 0.2, inLane3)
        assertEquals(LaneArrowStyle.TARGET_BLINK, scene.lane(3)!!.style)
        scene = run(b, clock, 0.15, inLane3)
        assertEquals(LaneArrowStyle.TARGET, scene.lane(3)!!.style)
        assertEquals(LaneArrowStyle.OTHER, scene.lane(2)!!.style)
    }

    @Test
    fun `a short flicker out of the target lane does not turn anything red`() {
        val b = ArSceneBuilder()
        val clock = Clock()
        val keep = { c: Int -> input(world(current = c), guidance(LaneAction.KEEP_LANE, listOf(3), current = c)) }
        run(b, clock, 0.5) { keep(3) }
        val scene = run(b, clock, 0.5) { keep(2) }
        assertTrue(scene.laneArrows.none { it.style == LaneArrowStyle.WRONG || it.style == LaneArrowStyle.TARGET_BLINK })
    }

    @Test
    fun `lanes that are not known give only the ego arrow and never red`() {
        // Green only when the Driving Context has the car's lane as a route lane (KEEP_LANE) or gives no lane guidance.
        // While it asks for a lane change (the default guidance here) the lone arrow is white and straight.
        val keep = guidance(LaneAction.KEEP_LANE, listOf(2))
        val cases = mapOf(
            "guidance UNKNOWN" to (input(lane = guidance(LaneAction.UNKNOWN, listOf(3))) to LaneArrowStyle.TARGET),
            "no guidance" to (input(lane = null) to LaneArrowStyle.TARGET),
            "keep lane, low confidence" to (input(world = world(conf = 0.22), lane = keep) to LaneArrowStyle.TARGET),
            "keep lane, camera axis" to (input(world = world(lineCount = 1), lane = keep) to LaneArrowStyle.TARGET),
            "no lanes" to (input(world = world().copy(lanes = null)) to LaneArrowStyle.OTHER),
            "low confidence (city)" to (input(world = world(conf = 0.22)) to LaneArrowStyle.OTHER),
            "old lanes" to (input(world = world(age = 1.4)) to LaneArrowStyle.OTHER),
            "run saw no lines" to (input(world = world(lineCount = 0)) to LaneArrowStyle.OTHER),
            "camera axis" to (input(world = world(lineCount = 1)) to LaneArrowStyle.OTHER),
            "lane number out of range" to (input(world = world(current = 4)) to LaneArrowStyle.OTHER),
            "too many lanes" to (input(world = world(current = 2, count = 7)) to LaneArrowStyle.OTHER),
            "target beyond the lanes seen" to (input(lane = guidance(LaneAction.CHANGE_LANE_RIGHT, listOf(4))) to LaneArrowStyle.OTHER),
        )
        for ((name, case) in cases) {
            val (inp, style) = case
            val scene = run(ArSceneBuilder(), Clock(), 1.5) { inp }
            val a = scene.laneArrows.singleOrNull()
            assertNotNull(name, a)
            assertNull(name, a!!.lane)
            assertEquals(name, style, a.style)
            // Not emphasised: the exit is 400 m away (> 300 m) and there is no lane choice.
            assertEquals(name, if (style == LaneArrowStyle.OTHER) 0.55f * 0.45f else 0.55f, a.alpha, 1e-3f)
        }
        // A lane change asked 80 m before a right turn: the lone arrow does not take the turn's shape either.
        val nearTurn = run(ArSceneBuilder(), Clock(), 0.5) { input(world = world(lineCount = 1), route = route(Maneuver.TURN_RIGHT, "right"), distance = 80.0) }
        assertEquals(LaneArrowStyle.OTHER, nearTurn.laneArrows.single().style)
        assertEquals(LaneArrowGlyph.STRAIGHT, nearTurn.laneArrows.single().glyph)
        val debug = run(ArSceneBuilder(), Clock(), 0.1) { input(world = world(conf = 0.22), debug = true) }
        assertEquals("lanes unknown: conf 0.22", debug.debug!!.laneStatus)
    }

    @Test
    fun `a red arrow does not linger when lanes become unknown or perception goes stale`() {
        val cases = mapOf("low confidence" to input(world(conf = 0.22)), "stale perception" to input(world(stale = true)))
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
        // Below 0.3 lane confidence the Driving Context's lane guidance is UNKNOWN too.
        val low = { input(world(conf = 0.22), guidance(LaneAction.UNKNOWN, emptyList())) }
        val b = ArSceneBuilder()
        val clock = Clock()
        // Wrong for 0.5 s, one 0.13 s run with unknown lanes, wrong again: red once 0.8 s of wrong-lane time add up.
        run(b, clock, 0.5) { input() }
        var scene = run(b, clock, 0.13, low)
        assertTrue(scene.laneArrows.none { it.style == LaneArrowStyle.WRONG })
        scene = run(b, clock, 0.25) { input() }
        assertEquals("0.75 s wrong so far", LaneArrowStyle.OTHER, scene.lane(2)!!.style)
        scene = run(b, clock, 0.1) { input() }
        assertEquals(LaneArrowStyle.WRONG, scene.lane(2)!!.style)

        // Already red: a 0.13 s gap draws no red and no green over the car's lane, and the red is back with the lanes.
        scene = run(b, clock, 0.13, low)
        assertTrue(scene.laneArrows.none { it.style == LaneArrowStyle.WRONG || it.style == LaneArrowStyle.TARGET })
        assertEquals(LaneArrowStyle.OTHER, scene.lane(null)!!.style)
        clock.ns += frameNs
        assertEquals(LaneArrowStyle.WRONG, b.build(input(), viewW, viewH, clock.ns).lane(2)!!.style)

        // A gap longer than 0.4 s clears the look: 0.8 s again before red.
        run(b, clock, 0.6, low)
        scene = run(b, clock, 0.5) { input() }
        assertTrue(scene.laneArrows.none { it.style == LaneArrowStyle.WRONG })
        scene = run(b, clock, 0.4) { input() }
        assertEquals(LaneArrowStyle.WRONG, scene.lane(2)!!.style)
    }

    @Test
    fun `at most two lanes either side, and only lanes whose arrow is on screen`() {
        val six = { c: Int, t: Int -> input(world(current = c, count = 6, carPos = c.toDouble()), guidance(LaneAction.CHANGE_LANE_RIGHT, listOf(t), c, 6)) }
        assertEquals(listOf(1, 2, 3), run(ArSceneBuilder(), Clock(), 0.5) { six(1, 6) }.laneArrows.map { it.lane!! }.sorted())
        assertEquals(listOf(2, 3, 4, 5, 6), run(ArSceneBuilder(), Clock(), 0.5) { six(4, 6) }.laneArrows.map { it.lane!! }.sorted())
        // A narrow view crops the lanes two over.
        val b = ArSceneBuilder()
        var scene = b.build(six(4, 6), 1000f, 1600f, 1L)
        for (i in 1..30) scene = b.build(six(4, 6), 1000f, 1600f, 1L + i * frameNs)
        assertEquals(listOf(3, 4, 5), scene.laneArrows.map { it.lane!! }.sorted())
        assertTrue(scene.laneArrows.all { a -> a.outline.any { it.x in 0f..1000f } })
    }

    @Test
    fun `target arrows take the maneuver's shape within 100 m and are straight beyond`() {
        val keep = guidance(LaneAction.KEEP_LANE, listOf(2))
        val near = run(ArSceneBuilder(), Clock(), 0.5) { input(lane = keep, route = route(Maneuver.TURN_RIGHT, "right"), distance = 80.0) }
        assertEquals(LaneArrowGlyph.TURN_RIGHT, near.lane(2)!!.glyph)
        assertEquals("other lanes stay straight", LaneArrowGlyph.STRAIGHT, near.lane(1)!!.glyph)
        // Beyond 14 m (the bend and head) the glyph reaches much farther right of the lane centre than left.
        val head = near.lane(2)!!.outline.filter { it.y < map.point(0.0, projector.rowAt(14.0)).y }
        val cx = map.point(projector.cx, 0.0).x
        assertTrue("the head points right", head.maxOf { it.x } - cx > 1.3f * (cx - head.minOf { it.x }))
        val far = run(ArSceneBuilder(), Clock(), 0.5) { input(lane = keep, route = route(Maneuver.TURN_RIGHT, "right"), distance = 150.0) }
        assertEquals(LaneArrowGlyph.STRAIGHT, far.lane(2)!!.glyph)
        val lone = run(ArSceneBuilder(), Clock(), 0.5) { input(lane = null, route = route(Maneuver.TURN_LEFT, "left"), distance = 60.0) }
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
        val horizon = map.point(0.0, 250.0).y
        for (a in scene.laneArrows) assertTrue("below the horizon", a.outline.all { it.y > horizon })

        val ego = scene.lane(2)!!
        val pts = ego.outline
        // Near end at 9 m, far end (the tip) at 16 m, nearer the horizon.
        assertEquals(map.point(0.0, projector.rowAt(9.0)).y, pts.maxOf { it.y }, 1f)
        assertEquals(map.point(0.0, projector.rowAt(16.0)).y, pts.minOf { it.y }, 1f)
        val tip = pts.minBy { it.y }
        val cx = map.point(projector.cx, 0.0).x
        assertEquals("tip on the lane centre", cx, tip.x, 1f)
        // Straight road: the outline is left-right symmetric around the lane centre.
        for (p in pts) {
            assertTrue("mirror of $p", pts.any { q -> abs(q.y - p.y) < 0.5f && abs(q.x - (2 * cx - p.x)) < 0.5f })
        }
        // Foreshortening: the 0.45 m shaft at 9 m is wider per metre than the 1.3 m head at ~13.6 m.
        val base = pts.filter { abs(it.y - pts.maxOf { p -> p.y }) < 0.5f }
        val shaftPxPerM = (base.maxOf { it.x } - base.minOf { it.x }) / 0.45f
        val headRow = map.point(0.0, projector.rowAt(9.0 + 4.6)).y
        val head = pts.filter { abs(it.y - headRow) < 1.5f }
        val headPxPerM = (head.maxOf { it.x } - head.minOf { it.x }) / 1.3f
        assertTrue("$shaftPxPerM > $headPxPerM", shaftPxPerM > headPxPerM * 1.2f)
        // A left lane's arrow leans towards the vanishing point: its tip is right of its base.
        val left = scene.lane(1)!!.outline
        val leftBase = left.filter { abs(it.y - left.maxOf { p -> p.y }) < 0.5f }
        assertTrue(left.minBy { it.y }.x > (leftBase.minOf { it.x } + leftBase.maxOf { it.x }) / 2f)
    }

    @Test
    fun `every glyph outline is a simple polygon inside its lane`() {
        for (g in LaneArrowGlyph.entries) {
            val o = LaneArrows.outline(g)
            val n = o.size
            assertTrue(g.name, n > 20)
            for (i in 0 until n) {
                assertTrue("$g inside the lane", abs(o.u[i]) < laneW / 2)
                assertTrue("$g within its length", o.v[i] in -1e-9..LaneArrows.LENGTH_M + 1e-9)
            }
            for (i in 0 until n) for (j in i + 2 until n) {
                if (i == 0 && j == n - 1) continue // adjacent through the closing edge
                val hit = crosses(o.u[i], o.v[i], o.u[(i + 1) % n], o.v[(i + 1) % n], o.u[j], o.v[j], o.u[(j + 1) % n], o.v[(j + 1) % n])
                assertFalse("$g edges $i and $j cross", hit)
            }
        }
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
        // A car 12 m ahead in the ego lane sits on the arrow; a pedestrian up at the horizon and a sign do not.
        val bl = projector.toImage(Ground(-0.9, 12.0))!!
        val br = projector.toImage(Ground(0.9, 12.0))!!
        val carBox = listOf(bl.x.toDouble(), bl.y - 70.0, br.x.toDouble(), bl.y.toDouble())
        val objects = listOf(
            car(1, ObjectClass.CAR, carBox),
            car(2, ObjectClass.PEDESTRIAN, listOf(100.0, 200.0, 110.0, 240.0)),
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
    fun `crossing into the next lane keeps every arrow on its lane until the lane number catches up`() {
        val b = ArSceneBuilder()
        val clock = Clock()
        val lane3 = { carPos: Double, reported: Int -> input(world(carPos = carPos, current = reported)) }
        run(b, clock, 1.2) { lane3(2.0, 2) }
        // Drift right inside lane 2, up to the line.
        var scene = run(b, clock, 1.2) { lane3(2.45, 2) }
        assertEquals(LaneArrowStyle.WRONG, scene.lane(2)!!.style)
        val zTip = LaneArrows.MIN_START_Z + LaneArrows.LENGTH_M
        val lane3At = { carPos: Double -> map.point(projector.toImage(Ground((3.0 - carPos) * laneW, zTip))!!).x }
        assertEquals(lane3At(2.45), tipX(scene.lane(3)!!), 3f)
        // Across the line: the ego lines are now lane 3's, the lane model still says 2.
        clock.ns += frameNs
        scene = b.build(input(world(carPos = 2.55, current = 2), debug = true), viewW, viewH, clock.ns)
        assertEquals(lane3At(2.55), tipX(scene.lane(3)!!), 3f)
        assertTrue("no red once the car is in the target lane", scene.laneArrows.none { it.style == LaneArrowStyle.WRONG })
        assertTrue(scene.debug!!.laneStatus!!, scene.debug!!.laneStatus!!.startsWith("lanes 3/3") && scene.debug!!.laneStatus!!.contains("shift +1"))
        // The lane model catches up: same place, no shift.
        scene = run(b, clock, 0.5) { input(world(carPos = 2.55, current = 3), guidance(LaneAction.KEEP_LANE, listOf(3), current = 3), debug = true) }
        assertEquals(lane3At(2.55), tipX(scene.lane(3)!!), 3f)
        assertFalse(scene.debug!!.laneStatus!!.contains("shift"))
        assertEquals(LaneArrowStyle.TARGET, scene.lane(3)!!.style)
    }

    @Test
    fun `a lane change across a camera-axis or low-confidence run still keeps every arrow on its lane`() {
        val zTip = LaneArrows.MIN_START_Z + LaneArrows.LENGTH_M
        val lane3At = { carPos: Double -> map.point(projector.toImage(Ground((3.0 - carPos) * laneW, zTip))!!).x }
        // The run that crosses the line has unknown lane numbers: one line seen (camera axis), or confidence 0.22.
        val gaps = mapOf(
            "camera axis" to input(world(carPos = 2.55, current = 2, lineCount = 1)),
            "low confidence" to input(world(carPos = 2.55, current = 2, conf = 0.22)),
        )
        for ((name, gap) in gaps) {
            val b = ArSceneBuilder()
            val clock = Clock()
            run(b, clock, 1.2) { input(world(carPos = 2.0)) }
            var scene = run(b, clock, 1.2) { input(world(carPos = 2.45)) }
            assertEquals(name, LaneArrowStyle.WRONG, scene.lane(2)!!.style)
            run(b, clock, 0.13) { gap }
            // Lanes known again, the lane model still says 2: no red in the lane the car just entered, the target on it.
            scene = run(b, clock, 0.1) { input(world(carPos = 2.55, current = 2), debug = true) }
            val status = scene.debug!!.laneStatus!!
            assertTrue("$name: $status", status.startsWith("lanes 3/3") && status.contains("shift +1"))
            assertEquals(name, lane3At(2.55), tipX(scene.lane(3)!!), 3f)
            assertTrue(name, scene.laneArrows.none { it.style == LaneArrowStyle.WRONG })
            // Held while the lane model catches up, even when that takes 2 s.
            scene = run(b, clock, 2.0) { input(world(carPos = 2.55, current = 2), debug = true) }
            assertTrue("$name: ${scene.debug!!.laneStatus}", scene.debug!!.laneStatus!!.contains("shift +1"))
            assertEquals(name, LaneArrowStyle.TARGET, scene.lane(3)!!.style)
        }
    }
}
