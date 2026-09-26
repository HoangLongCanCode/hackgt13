package com.drivingassist.copilot.perception

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** PROTOCOL_v2 messages: decode/encode of every sample, exact client JSON, uplink header bytes, navigation. */
class ProtocolV2Test {
    private val rawJson = Json { ignoreUnknownKeys = true }
    private val lenientJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private fun bundled(name: String): File = File(javaClass.getResource("/samples/v2/$name")?.toURI() ?: fail("missing sample $name"))

    private fun bundledV2(): List<File> = File(javaClass.getResource("/samples/v2")!!.toURI())
        .listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }

    private fun contractsV2Dir(): File? = System.getProperty("contracts.samples.dir")?.let { File(it, "v2") }?.takeIf { it.isDirectory }

    /** Decodes a server or client message and checks the encode -> decode round trip. */
    private fun checkMessageText(text: String, where: String) {
        val obj = rawJson.parseToJsonElement(text).jsonObject
        val type = obj["type"]?.jsonPrimitive?.content
        assumeTrue(type != null, "$where: not a protocol message (no \"type\" field)")
        if (type!!.startsWith("client.")) {
            val msg = PerceptionCodec.decodeClient(text)
            assertEquals(msg, PerceptionCodec.decodeClient(PerceptionCodec.encodeClient(msg)), "$where: client round trip")
        } else {
            val msg = PerceptionCodec.decode(text)
            if (msg is UnknownMessage) fail("$where: unknown message type '${msg.type}'")
            assertEquals(msg, PerceptionCodec.decode(PerceptionCodec.encode(msg)), "$where: round trip")
            if (msg is PerceptionFrame) {
                assertTrue(msg.schemaVersion in PerceptionFrame.MIN_SCHEMA_VERSION..PerceptionFrame.SCHEMA_VERSION, where)
                msg.objects.forEach { o -> assertEquals(4, o.bbox.size, "$where object ${o.id}") }
            }
        }
    }

    private fun checkFile(f: File) {
        val messages = ReplayPerceptionSource.readMessages(f)
        assertTrue(messages.isNotEmpty(), "${f.name}: no messages")
        messages.forEachIndexed { i, t -> checkMessageText(t, "${f.name}[$i]") }
    }

    @TestFactory
    fun `bundled v2 samples decode and round trip`(): List<DynamicTest> =
        bundledV2().map { f -> DynamicTest.dynamicTest(f.name) { checkFile(f) } }

    /** `perception_engine/contracts/samples/v2/` is written by the Python server (other team members); decoded whenever present. */
    @TestFactory
    fun `contracts v2 samples from the Python engine decode`(): List<DynamicTest> {
        val files = contractsV2Dir()?.listFiles { f -> f.extension == "json" || f.extension == "jsonl" }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) {
            return listOf(DynamicTest.dynamicTest("no perception_engine/contracts/samples/v2 json yet (skipped)") { assumeTrue(false, "perception_engine/contracts/samples/v2 has no JSON samples yet") })
        }
        return files.map { f -> DynamicTest.dynamicTest(f.name) { checkFile(f) } }
    }

    @Test
    fun `wave-1 frame carries wave, echo and distanceAgeMs`() {
        val f = assertIs<PerceptionFrame>(PerceptionCodec.decode(bundled("frame_live_wave1.json").readText()))
        assertEquals(2, f.schemaVersion)
        assertEquals(1, f.wave)
        assertEquals(Echo(1234, 123456789012), f.echo)
        assertEquals(SourceKind.CAMERA, f.source.kind)
        val car = f.objects.first { it.id == 17 }
        assertEquals(66.0, car.distanceAgeMs)
        assertNull(f.objects.first { it.id == 42 }.distanceAgeMs)
        assertEquals(LightState.RED, f.objects.first { it.id == 42 }.lightState)
    }

    @Test
    fun `wave-2 update keeps omitted blocks null and empty lists empty`() {
        val u = assertIs<PerceptionUpdate>(PerceptionCodec.decode(bundled("update_wave2.json").readText()))
        assertEquals(4711, u.seq)
        assertEquals(301, u.frameIndex)
        assertEquals(DistanceUpdate(17, 18.4, "fused", 0.8, -0.3), u.distances!!.single())
        assertEquals(2, u.lanes!!.currentLane)
        assertEquals(listOf(640.0, 262.0), u.road!!.vanishingPoint)
        assertEquals(emptyList(), u.signs, "signs ran and found nothing")
        assertEquals(listOf("depth", "lanes"), u.blocks)
        val depthOnly = assertIs<PerceptionUpdate>(PerceptionCodec.decode(bundled("update_depth_only.json").readText()))
        assertNull(depthOnly.lanes, "lanes did not run")
        assertNull(depthOnly.signs, "signs did not run")
        assertNull(depthOnly.echo)
    }

    @Test
    fun `skip, pong, stats and hello decode`() {
        assertEquals(SkipMessage(1233, "superseded"), PerceptionCodec.decode(bundled("skip.json").readText()))
        assertEquals(PongMessage(123456789012, 1790000000000), PerceptionCodec.decode(bundled("pong.json").readText()))
        assertEquals(PongMessage(null, 1790000000000), PerceptionCodec.decode(bundled("pong_no_client_time.json").readText()), "python make_pong(None)")
        val err = assertIs<ErrorMessage>(PerceptionCodec.decode(bundled("error.json").readText()))
        assertEquals("unknownVideo", err.code)
        assertFalse(err.fatal)
        val stats = assertIs<StatsMessage>(PerceptionCodec.decode(bundled("stats.json").readText()))
        assertEquals(Percentiles(58.0, 72.5), stats.wave2ProcessingMs)
        assertEquals(448, stats.framesSkipped)
        val live = assertIs<HelloMessage>(PerceptionCodec.decode(bundled("server_hello_live.json").readText()))
        assertEquals(2, live.protocolVersion)
        assertEquals(PerceptionMode.LIVE, live.mode)
        assertEquals(UplinkInfo(2, 960, 540, 80, "SDC1"), live.uplink)
        assertTrue(live.acceptsSdc1Uplink)
        val sim = assertIs<HelloMessage>(PerceptionCodec.decode(bundled("server_hello_sim.json").readText()))
        assertEquals(PerceptionMode.SIM, sim.mode)
        assertEquals(0.35, sim.sim!!.lookaheadSeconds)
        assertFalse(sim.acceptsSdc1Uplink, "no uplink in sim mode")
        assertNull(sim.navigationAvailable, "older hellos have no navigation block")
        val nav = assertIs<HelloMessage>(PerceptionCodec.decode("""{"type":"perception.hello","protocolVersion":2,"mode":"live","navigation":{"mode":"live","available":false,"error":"node not found"}}"""))
        assertEquals("live", nav.navigationMode)
        assertEquals(false, nav.navigationAvailable)
        assertEquals("node not found", nav.navigationError)
    }

    @Test
    fun `v1 server hello never enables the SDC1 uplink`() {
        val v1 = assertIs<HelloMessage>(
            PerceptionCodec.decode("""{"type":"perception.hello","schemaVersion":1,"sessionId":"x","uplink":{"accepted":true,"format":"binary: 8-byte little-endian pts"}}"""),
        )
        assertFalse(v1.acceptsSdc1Uplink)
        assertFalse(HelloMessage(protocolVersion = 2, mode = PerceptionMode.VIDEO).acceptsSdc1Uplink)
        assertTrue(HelloMessage(protocolVersion = 2, mode = PerceptionMode.LIVE).acceptsSdc1Uplink, "live without uplink block: defaults")
        assertFalse(HelloMessage(protocolVersion = 2, uplink = UplinkInfo(accepted = false)).acceptsSdc1Uplink)
    }

    @Test
    fun `unknown source kind and unknown message type still decode`() {
        val text = bundled("frame_live_wave1.json").readText().replace("\"kind\":\"camera\"", "\"kind\":\"tablet-cam\"")
        assertEquals(SourceKind.UNKNOWN, PerceptionCodec.decodeFrame(text).source.kind)
        val unknown = assertIs<UnknownMessage>(PerceptionCodec.decode("""{"type":"perception.future","x":1}"""))
        assertEquals("perception.future", unknown.type)
    }

    @Test
    fun `client hello encodes exactly like the protocol example`() {
        val hello = ClientHello(
            clientId = "tab-s9-01",
            device = DeviceInfo("samsung", "SM-X710", "16"),
            mode = PerceptionMode.LIVE,
            camera = ClientCamera(
                imageWidth = 960, imageHeight = 540, focalPx = 745.2, principalPoint = listOf(480.0, 270.0),
                mountHeightMeters = 1.25, pitchDegrees = null, lensFacing = "back", stabilization = false,
            ),
            sim = null,
        )
        val encoded = rawJson.parseToJsonElement(PerceptionCodec.encodeClient(hello)) as JsonObject
        val expected = rawJson.parseToJsonElement(bundled("client_hello_live.json").readText()) as JsonObject
        assertEquals(expected, encoded, "no \"navigation\" key when the hint is null")
        assertEquals(hello, PerceptionCodec.decodeClient(bundled("client_hello_live.json").readText()))
    }

    @Test
    fun `sim hello, playback and ping encode like the protocol`() {
        val sim = ClientHello.sim("tab-s9-01", "b1ff4656-0435391e", DeviceInfo("samsung", "SM-X710", "16"))
        assertEquals(rawJson.parseToJsonElement(bundled("client_hello_sim.json").readText()), rawJson.parseToJsonElement(PerceptionCodec.encodeClient(sim)))
        val pb = ClientPlayback("b1ff4656-0435391e", 12.345, true, 1.0, 123456789012)
        assertEquals(rawJson.parseToJsonElement(bundled("client_playback.json").readText()), rawJson.parseToJsonElement(PerceptionCodec.encodeClient(pb)))
        assertEquals("""{"type":"client.ping","clientTimeNs":123456789012}""", PerceptionCodec.encodeClient(ClientPing(123456789012)))
    }

    // ---------------------------------------------------------------------------- navigation

    @Test
    fun `navigation packet decodes routeState and keeps the phase1 packet verbatim`() {
        val text = bundled("navigation_packet_sim.json").readText()
        val m = assertIs<NavigationPacketMessage>(PerceptionCodec.decode(text))
        assertEquals(12.3, m.ptsSeconds)
        assertEquals(1730000012300, m.tripTimestampMs)
        val rs = assertNotNull(m.routeState)
        assertEquals(NavRouteState("TURN_RIGHT", "Turn right in 120 m.", "TURN_ARROW", 120.0, false, 95.0, 840.0, null, "right", "North Ave"), rs)
        val packet = assertNotNull(m.packet)
        assertEquals("SPATIAL_NAVIGATION_PACKET", packet["packetType"]!!.jsonPrimitive.content)
        assertEquals(rawJson.parseToJsonElement(text).jsonObject["packet"], packet, "packet is passed through unchanged")

        val live = assertIs<NavigationPacketMessage>(PerceptionCodec.decode(bundled("navigation_packet_live.json").readText()))
        assertNull(live.ptsSeconds, "live packets have no media time")
        assertEquals("right", live.routeState!!.requiredLane)
        assertNull(live.routeState!!.etaSeconds)

        val none = assertIs<NavigationPacketMessage>(PerceptionCodec.decode(bundled("navigation_packet_no_route.json").readText()))
        assertNull(none.routeState)
        assertNull(none.packet)
    }

    @Test
    fun `requiredLane decodes from a JSON number too`() {
        val m = assertIs<NavigationPacketMessage>(PerceptionCodec.decode(bundled("navigation_packet_numeric_lane.json").readText()))
        assertEquals("2", m.routeState!!.requiredLane)
        val minimal = assertIs<NavigationPacketMessage>(PerceptionCodec.decode("""{"type":"navigation.packet","routeState":{"action":"ARRIVE"}}"""))
        assertEquals("", minimal.routeState!!.audio, "missing strings default to empty, never null")
    }

    @Test
    fun `trip state and navigation hint encode like the protocol`() {
        val trip = ClientTripState(1790000000123, GeoPoint(33.7756, -84.3963), 91.2, 6.1, 4.1)
        assertEquals(rawJson.parseToJsonElement(bundled("client_trip_state.json").readText()), rawJson.parseToJsonElement(PerceptionCodec.encodeClient(trip)))
        assertEquals(trip, PerceptionCodec.decodeClient(bundled("client_trip_state.json").readText()))
        val hello = ClientHello.live("tab-s9-01", ClientCamera(960, 540, mountHeightMeters = 1.25), DeviceInfo("samsung", "SM-X710", "16"), NavigationHint.LIVE)
        assertEquals(rawJson.parseToJsonElement(bundled("client_hello_live_nav.json").readText()), rawJson.parseToJsonElement(PerceptionCodec.encodeClient(hello)))
    }

    @Test
    fun `a GPS fix without bearing or speed still matches the trip_state schema`() {
        // Android: no Location.bearing / speed (e.g. network positioning) -> 0, never null.
        val text = PerceptionCodec.encodeClient(ClientTripState(1790000000123, GeoPoint(33.7756, -84.3963), accuracyMeters = 25.0))
        val obj = rawJson.parseToJsonElement(text).jsonObject
        assertEquals(0.0, obj.getValue("heading").jsonPrimitive.content.toDouble())
        assertEquals(0.0, obj.getValue("speedMps").jsonPrimitive.content.toDouble())
        // A replayed jsonl line with nulls decodes to 0 as well.
        val replayed = lenientJson.decodeFromString(
            ClientTripState.serializer(), """{"timestampMs":1,"location":{"lat":1.0,"lng":2.0},"heading":null,"speedMps":null}""",
        )
        assertEquals(0.0, replayed.heading)
        assertEquals(0.0, replayed.speedMps)
        // Against perception_engine/contracts/schemas/client.trip_state.schema.json: required keys present and numeric, no unknown keys.
        val schemaFile = System.getProperty("contracts.samples.dir")?.let { File(File(it).parentFile, "schemas/client.trip_state.schema.json") }
        assumeTrue(schemaFile?.isFile == true, "perception_engine/contracts/schemas not found")
        val schema = rawJson.parseToJsonElement(schemaFile!!.readText()).jsonObject
        val props = schema.getValue("properties").jsonObject
        val required = (schema.getValue("required") as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content }
        for (key in required) {
            val v = assertNotNull(obj[key], "required '$key' missing")
            val type = (props.getValue(key).jsonObject["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (type == "number") assertNotNull(v.jsonPrimitive.content.toDoubleOrNull(), "'$key' must be a number, got $v")
        }
        assertTrue(obj.keys.all { it in props.keys }, "keys not in the schema: ${obj.keys - props.keys}")
        val heading = obj.getValue("heading").jsonPrimitive.content.toDouble()
        assertTrue(heading >= 0.0 && heading < 360.0)
    }

    // ------------------------------------------------------------------------- uplink header

    @Test
    fun `uplink header matches the struct pack vector`() {
        // Python: struct.Struct("<4sHHIqHH").pack(b"SDC1", 1, 0, 1234, 123456789012, 90, 0).hex()
        val h = UplinkHeader(frameId = 1234, captureTimeNs = 123456789012, rotationDegrees = 90)
        assertEquals("5344433101000000d2040000141a99be1c0000005a000000", UplinkHeader.toHex(h.encode()))
        // Python: pack(b"SDC1", 1, 0, 0xFFFFFFFE, 987654321098765432, 270, 0)
        val big = UplinkHeader(frameId = 0xFFFF_FFFEL, captureTimeNs = 987654321098765432, rotationDegrees = 270)
        assertEquals("5344433101000000feffffff78b4f8495fdab40d0e010000", UplinkHeader.toHex(big.encode()))
        assertEquals(big, UplinkHeader.decode(big.encode()))
    }

    @Test
    fun `uplink frame is header then jpeg and decode validates`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val frame = UplinkHeader(7, 42, 0).frame(jpeg)
        assertEquals(UplinkHeader.SIZE + jpeg.size, frame.size)
        assertContentEquals(jpeg, frame.copyOfRange(UplinkHeader.SIZE, frame.size))
        assertEquals(UplinkHeader(7, 42, 0), UplinkHeader.decode(frame))
        val bad = frame.copyOf().also { it[0] = 'X'.code.toByte() }
        assertFailsWith<IllegalArgumentException> { UplinkHeader.decode(bad) }
        assertFailsWith<IllegalArgumentException> { UplinkHeader.decode(ByteArray(10)) }
        assertFailsWith<IllegalArgumentException> { UplinkHeader(frameId = -1, captureTimeNs = 0) }
        assertEquals(listOf(0, 90, 180, 270, 0, 270), listOf(0, 90, 180, 270, 360, -90).map(UplinkHeader::normalizeRotation))
    }

    /**
     * Parses the Python `describe_uplink_header()` text (hex line + offset/size/field/type/bytes/value table),
     * also accepting `name = value` / `"name": value` pairs. Returns the header bytes and the field values found.
     */
    private fun parseUplinkExample(text: String): Pair<ByteArray, Map<String, Long>> {
        val hex = Regex("""(?i)\b(?:[0-9a-f]{2}[ \t:]?){24}""").findAll(text)
            .map { it.value.filter { c -> c.isLetterOrDigit() } }
            .firstOrNull { it.length == 48 && it.lowercase().startsWith("53444331") }
            ?: fail("no 24-byte SDC1 hex string in: $text")
        val fields = listOf("frameId", "captureTimeNs", "rotationDegrees", "headerVersion", "flags").mapNotNull { name ->
            val pair = Regex("""(?i)"?\b$name"?\s*[=:]\s*(-?\d+)""").find(text)?.groupValues?.get(1)
            val row = Regex("""(?m)^\s*\d+\s+\d+\s+$name\s.*\s(-?\d+)\s*$""").find(text)?.groupValues?.get(1)
            (pair ?: row)?.let { name to it.toLong() }
        }.toMap()
        return UplinkHeader.fromHex(hex) to fields
    }

    private fun checkUplinkExample(text: String, where: String) {
        val (bytes, fields) = parseUplinkExample(text)
        val decoded = UplinkHeader.decode(bytes)
        assertTrue("frameId" in fields && "captureTimeNs" in fields && "rotationDegrees" in fields, "$where: fields found $fields")
        assertEquals(fields.getValue("frameId"), decoded.frameId, "$where frameId")
        assertEquals(fields.getValue("captureTimeNs"), decoded.captureTimeNs, "$where captureTimeNs")
        assertEquals(fields.getValue("rotationDegrees").toInt(), decoded.rotationDegrees, "$where rotationDegrees")
        fields["headerVersion"]?.let { assertEquals(it.toInt(), decoded.headerVersion, "$where headerVersion") }
        // Kotlin encoding of the same values must give the same 24 bytes.
        val mine = UplinkHeader(decoded.frameId, decoded.captureTimeNs, decoded.rotationDegrees).encode()
        assertEquals(UplinkHeader.toHex(bytes), UplinkHeader.toHex(mine), "$where: Kotlin encode differs from Python pack")
    }

    /** The exact text Python's `perception.realtime.wire.describe_uplink_header(pack_uplink_header(1234, 123456789012, 90))` prints. */
    @Test
    fun `uplink header matches the Python describe_uplink_header output`() {
        val text = bundled("uplink_header.python_format.txt").readText()
        checkUplinkExample(text, "python format")
        assertEquals(UplinkHeader(1234, 123456789012, 90), UplinkHeader.decode(parseUplinkExample(text).first))
    }

    /** `perception_engine/contracts/samples/v2/uplink_header.example.txt`, written by the Python side (checked when present). */
    @Test
    fun `uplink header matches the Python example file when present`() {
        val file = contractsV2Dir()?.let { File(it, "uplink_header.example.txt") }
        assumeTrue(file != null && file.isFile, "perception_engine/contracts/samples/v2/uplink_header.example.txt not written yet")
        checkUplinkExample(file!!.readText(), file.name)
    }
}
