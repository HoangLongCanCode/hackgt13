package com.drivingassist.glass.perception

import com.drivingassist.glass.NormPoint
import com.drivingassist.copilot.bridge.NavigationUpdate
import com.drivingassist.copilot.context.FrameTiming
import com.drivingassist.copilot.context.LanesState
import com.drivingassist.copilot.context.ObjectState
import com.drivingassist.copilot.context.SignState
import com.drivingassist.copilot.context.WorldSnapshot
import com.drivingassist.copilot.perception.ImageSize
import com.drivingassist.copilot.perception.Lanes
import com.drivingassist.copilot.perception.LightState
import com.drivingassist.copilot.perception.NavRouteState
import com.drivingassist.copilot.perception.ObjectClass
import com.drivingassist.copilot.perception.Sign
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionMapperTest {
    private val timing = FrameTiming("s", 10, 10, 2.0, 0, 0, 0.0, 0.0, 0.0, null, null, 1, 0)

    private fun obj(id: Int, cls: ObjectClass, bbox: List<Double>, distance: Double? = 10.0, visible: Boolean = true, light: LightState? = null) =
        ObjectState(
            id = id, cls = cls, bbox = bbox, confidence = 0.9, ageFrames = 5, firstSeenPts = 0.0, lastSeenPts = 2.0,
            visible = visible, distanceMeters = distance, lightState = light,
        )

    private fun world(
        objects: List<ObjectState> = emptyList(),
        lanes: LanesState? = null,
        signs: List<SignState> = emptyList(),
        stale: Boolean = false,
        image: ImageSize = ImageSize(1280, 720),
    ) = WorldSnapshot(timing = timing, image = image, objects = objects.associateBy { it.id }, lanes = lanes, signs = signs, perceptionStale = stale)

    @Test
    fun `boxes map from upright pixels to overlay space with FILL_CENTER`() {
        // 16:9 image into a 16:9 view: identity scaling.
        val car = obj(1, ObjectClass.CAR, listOf(320.0, 360.0, 640.0, 540.0), distance = 12.5)
        val v = VisionMapper.map(world(listOf(car)), 1920f, 1080f)
        assertEquals(1, v.vehicles.size)
        assertArrayEquals(floatArrayOf(0.25f, 0.5f, 0.25f, 0.25f), v.vehicles[0].box, 1e-4f)
        assertEquals(12.5f, v.vehicles[0].distanceMeters, 1e-4f)
        assertEquals(2.0f, v.time, 1e-6f)

        // Tab S9 16:10 view: the 16:9 image is scaled by height and cropped left/right.
        val w = VisionMapper.map(world(listOf(car)), 2560f, 1600f).vehicles[0].box
        val scale = 1600f / 720f
        val crop = (1280f * scale - 2560f) / 2f
        assertEquals((320f * scale - crop) / 2560f, w[0], 1e-4f)
        assertEquals(0.5f, w[1], 1e-4f)
    }

    @Test
    fun `only visible road users with a distance, nearest first, at most eight`() {
        val objects = (1..12).map { obj(it, ObjectClass.CAR, listOf(100.0 * (it % 10), 300.0, 100.0 * (it % 10) + 80, 380.0), distance = 50.0 - it) } +
            obj(20, ObjectClass.PEDESTRIAN, listOf(600.0, 300.0, 640.0, 420.0), distance = 5.0) +
            obj(21, ObjectClass.CAR, listOf(600.0, 300.0, 640.0, 420.0), distance = null) +
            obj(22, ObjectClass.CAR, listOf(600.0, 300.0, 640.0, 420.0), distance = 3.0, visible = false) +
            obj(23, ObjectClass.TRAIN, listOf(600.0, 300.0, 640.0, 420.0), distance = 2.0)
        val v = VisionMapper.map(world(objects), 1920f, 1080f)
        assertEquals(VisionMapper.MAX_VEHICLES, v.vehicles.size)
        assertEquals(20, v.vehicles.first().id)
        assertTrue(v.vehicles.none { it.id in setOf(21, 22, 23) })
        val d = v.vehicles.map { it.distanceMeters }
        assertEquals(d.sorted(), d)
    }

    @Test
    fun `lights and signs become labelled boxes, unknown signs are dropped`() {
        val red = obj(42, ObjectClass.TRAFFIC_LIGHT, listOf(600.0, 100.0, 620.0, 150.0), light = LightState.RED)
        val unknownLight = obj(43, ObjectClass.TRAFFIC_LIGHT, listOf(700.0, 100.0, 720.0, 150.0), light = LightState.UNKNOWN)
        fun sign(id: Int, cls: String, seen: Double = 2.0) = SignState("k$id", Sign(id, cls, listOf(900.0, 200.0, 950.0, 250.0), 0.9), 1.0, seen, 3)
        val signs = listOf(sign(1, "stop"), sign(2, "speedLimit45"), sign(3, "speed_limit_30"), sign(4, "doNotEnter"), sign(5, "pedestrianCrossing"), sign(6, "noUTurn"), sign(7, "unknown"), sign(8, "yield", seen = 1.0))
        val v = VisionMapper.map(world(listOf(red, unknownLight), signs = signs), 1920f, 1080f)
        val labels = v.signs.associate { it.id to it.label }
        assertEquals("LIGHT_RED", labels[42])
        assertNull("UNKNOWN light colour is not drawn", labels[43])
        assertEquals("STOP", labels[VisionMapper.SIGN_ID_OFFSET + 1])
        assertEquals("SPEED_LIMIT_45", labels[VisionMapper.SIGN_ID_OFFSET + 2])
        assertEquals("SPEED_LIMIT_30", labels[VisionMapper.SIGN_ID_OFFSET + 3])
        assertEquals("DO_NOT_ENTER", labels[VisionMapper.SIGN_ID_OFFSET + 4])
        assertEquals("PEDESTRIAN_CROSSING", labels[VisionMapper.SIGN_ID_OFFSET + 5])
        assertEquals("WARNING", labels[VisionMapper.SIGN_ID_OFFSET + 6])
        assertFalse("unknown sign dropped", labels.containsKey(VisionMapper.SIGN_ID_OFFSET + 7))
        assertFalse("last seen 1 s ago: too old to draw", labels.containsKey(VisionMapper.SIGN_ID_OFFSET + 8))
        assertTrue(v.exitSigns.isEmpty())
    }

    private fun lanesState(boundaries: List<List<List<Double>>>, current: Int?, count: Int?, age: Double = 0.1) =
        LanesState(Lanes(current, count, boundaries, 0.8), current, count, 1.9, age)

    @Test
    fun `ego boundaries by index when the line count matches, points near to far`() {
        val lines = listOf(
            listOf(listOf(100.0, 400.0), listOf(-200.0, 720.0)), // far -> near order on purpose
            listOf(listOf(560.0, 400.0), listOf(300.0, 720.0)),
            listOf(listOf(720.0, 400.0), listOf(980.0, 720.0)),
            listOf(listOf(1180.0, 400.0), listOf(1500.0, 720.0)),
        )
        val v = VisionMapper.map(world(lanes = lanesState(lines, current = 2, count = 3)), 1920f, 1080f)
        val byId = v.lanes.associateBy { it.id }
        assertEquals(setOf("lane_0", "left", "right", "lane_3"), byId.keys)
        val left = byId.getValue("left").points
        assertTrue("near point first", left.first().y > left.last().y)
        assertEquals(300f / 1280f, left.first().x, 1e-4f)
        // lane_0 leaves the image on the left: clipped to the view.
        byId.getValue("lane_0").points.forEach { assertTrue(it.x in 0f..1f && it.y in 0f..1f) }
    }

    @Test
    fun `ego boundaries by geometry when the server reports extra lines`() {
        val lines = listOf(
            listOf(listOf(560.0, 400.0), listOf(300.0, 720.0)),
            listOf(listOf(600.0, 700.0), listOf(700.0, 700.0)), // e.g. a stop line (horizontal)
            listOf(listOf(720.0, 400.0), listOf(980.0, 720.0)),
        )
        val (l, r) = VisionMapper.egoBoundaryIndices(lines, currentLane = 1, laneCount = 1, imageWidth = 1280.0, imageHeight = 720.0)
        assertEquals(0, l)
        assertEquals(2, r)
        assertTrue(VisionMapper.map(world(lanes = lanesState(lines, 1, 3), stale = false), 1920f, 1080f).lanes.any { it.id == "right" })
    }

    @Test
    fun `stale perception or unknown view size draws nothing, old lanes are skipped`() {
        val car = obj(1, ObjectClass.CAR, listOf(320.0, 360.0, 640.0, 540.0))
        assertTrue(VisionMapper.map(world(listOf(car), stale = true), 1920f, 1080f).vehicles.isEmpty())
        assertTrue(VisionMapper.map(world(listOf(car)), 0f, 0f).vehicles.isEmpty())
        val lines = listOf(listOf(listOf(560.0, 400.0), listOf(300.0, 720.0)), listOf(listOf(720.0, 400.0), listOf(980.0, 720.0)))
        assertTrue(VisionMapper.map(world(lanes = lanesState(lines, 1, 1, age = 1.4)), 1920f, 1080f).lanes.isEmpty())
    }

    @Test
    fun `polyline clipping keeps the longest visible run`() {
        val pts = listOf(NormPoint(-0.5f, 1.2f), NormPoint(0.2f, 0.8f), NormPoint(0.4f, 0.5f), NormPoint(1.5f, 0.2f))
        val c = VisionMapper.clipPolyline(pts)
        assertTrue(c.size >= 3)
        c.forEach { assertTrue(it.x in 0f..1f && it.y in 0f..1f) }
        assertTrue(VisionMapper.clipPolyline(listOf(NormPoint(-1f, -1f), NormPoint(-0.5f, -0.2f))).isEmpty())
    }

    @Test
    fun `route state maps 1 to 1, waiting and stale are explicit`() {
        assertEquals(BridgeRouteSource.WAITING, BridgeRouteSource.toRouteState(null, 3f).action)
        val rs = NavRouteState("TURN_RIGHT", "Turn right in 120 m.", "TURN_ARROW", 120.0)
        val up = NavigationUpdate(rs, null, ptsSeconds = 12.3, receivedAtNs = 0, sequence = 1)
        val r = BridgeRouteSource.toRouteState(up, 99f)
        assertEquals("TURN_RIGHT", r.action)
        assertEquals("Turn right in 120 m.", r.audio)
        assertEquals("TURN_ARROW", r.ui)
        assertEquals(12.3f, r.time, 1e-4f)
        val stale = BridgeRouteSource.toRouteState(up.copy(stale = true, ptsSeconds = null), 5f)
        assertEquals("WARNING", stale.ui)
        assertEquals(5f, stale.time, 1e-6f)
        assertEquals(BridgeRouteSource.NO_ROUTE, BridgeRouteSource.toRouteState(up.copy(routeState = null), 1f).action)
    }

    @Test
    fun `config overrides are validated and ws is limited to local hosts`() {
        assertTrue(PerceptionConfig.isAllowedUrl("ws://127.0.0.1:8765/perception"))
        assertTrue(PerceptionConfig.isAllowedUrl("ws://192.168.43.12:8765/perception"))
        assertTrue(PerceptionConfig.isAllowedUrl("ws://10.0.0.5:8765/perception"))
        assertTrue(PerceptionConfig.isAllowedUrl("ws://172.20.1.2:8765/perception"))
        assertTrue(PerceptionConfig.isAllowedUrl("ws://laptop.local:8765/perception"))
        assertTrue(PerceptionConfig.isAllowedUrl("wss://example.com/perception"))
        assertFalse(PerceptionConfig.isAllowedUrl("ws://8.8.8.8:8765/perception"))
        assertFalse(PerceptionConfig.isAllowedUrl("ws://172.32.0.1:8765/perception"))
        assertFalse(PerceptionConfig.isAllowedUrl("http://127.0.0.1:8765/perception"))
        val base = PerceptionConfig()
        val c = PerceptionConfig.resolve(
            base,
            mapOf("perception.source" to "live", "perception.url" to "ws://192.168.1.20:8765/perception", "perception.nav" to "false", "perception.mount" to "1.4", "perception.video" to "abc"),
        )
        assertEquals(PerceptionSource.LIVE, c.source)
        assertEquals("ws://192.168.1.20:8765/perception", c.serverUrl)
        assertFalse(c.navEnabled)
        assertEquals(1.4, c.mountHeightMeters, 1e-9)
        assertEquals("abc", c.simVideoId)
        assertNull(c.warning)
        val bad = PerceptionConfig.resolve(base, mapOf("perception.source" to "radar", "perception.url" to "ws://8.8.8.8/x"))
        assertEquals(PerceptionSource.MOCK, bad.source)
        assertEquals(PerceptionConfig.DEFAULT_URL, bad.serverUrl)
        assertTrue(bad.warning!!.contains("URL rejected"))
    }

    @Test
    fun `saved values apply under this launch's extras and are flagged, invalid ones are never saved`() {
        val base = PerceptionConfig() // MOCK, like the BuildConfig default
        // A plain launch with nothing saved stays MOCK.
        assertEquals(PerceptionSource.MOCK, PerceptionConfig.merge(base, emptyMap(), emptyMap()).source)
        // Saved with perception.persist: used by a plain launch and flagged "(saved)".
        val saved = mapOf("perception.source" to "live", "perception.url" to "ws://192.168.1.20:8765/perception")
        val plain = PerceptionConfig.merge(base, saved, emptyMap())
        assertEquals(PerceptionSource.LIVE, plain.source)
        assertEquals(listOf("perception.source", "perception.url"), plain.savedKeys)
        // This launch's extras win over saved values; only the saved keys still in effect are flagged.
        val overridden = PerceptionConfig.merge(base, saved, mapOf("perception.source" to "sim"))
        assertEquals(PerceptionSource.SIM, overridden.source)
        assertEquals(listOf("perception.url"), overridden.savedKeys)
        // Validation before saving.
        assertTrue(PerceptionConfig.isValidOverride("perception.mount", "1.4"))
        assertFalse(PerceptionConfig.isValidOverride("perception.mount", "40"))
        assertFalse(PerceptionConfig.isValidOverride("perception.url", "ws://8.8.8.8/x"))
        assertFalse(PerceptionConfig.isValidOverride("perception.source", "radar"))
        assertFalse(PerceptionConfig.isValidOverride("perception.persist", "true"))
    }
}
