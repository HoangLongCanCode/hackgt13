package com.drivingassist.spatialcopilot

import com.drivingassist.spatialcopilot.model.ArrowHeading
import com.drivingassist.spatialcopilot.model.SpatialInstruction
import com.drivingassist.spatialcopilot.model.SpatialJson
import com.drivingassist.spatialcopilot.model.UplinkHeader
import com.drivingassist.spatialcopilot.nav.NavigationLogic
import com.drivingassist.spatialcopilot.perception.ProtocolDecoder
import com.drivingassist.spatialcopilot.perception.ServerEvent
import com.drivingassist.spatialcopilot.perception.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialContractTest {
    @Test
    fun uplinkHeaderMatchesProtocolSample() {
        val header = UplinkHeader.encode(
            frameId = 1,
            captureTimeNs = 342385412986900L,
            rotationDegrees = 0,
        )
        val hex = header.joinToString("") { "%02x".format(it) }
        assertEquals(24, header.size)
        assertEquals("53444331010000000100000014d8ead06537010000000000", hex)
        val wrapped = UplinkHeader.wrap(1, 342385412986900L, 0, byteArrayOf(1, 2, 3))
        assertEquals(27, wrapped.size)
    }

    @Test
    fun demoOpensOnExit56InTheRightLane() {
        val instruction = NavigationLogic.demoInstruction(0.0)
        assertEquals("demo", instruction.source)
        assertEquals("EXIT 56", instruction.navigation.exit?.label)
        assertEquals("0.4 mi", instruction.navigation.exit?.distanceLabel)
        assertEquals("18 m", instruction.vehicles.single().distanceLabel)
        val lit = instruction.lanes.filter { it.arrow.highlighted }
        assertEquals(listOf(3), lit.map { it.index })
        assertEquals(ArrowHeading.RIGHT, lit.single().arrow.heading)
        assertTrue(lit.single().recommended)
    }

    @Test
    fun laneChoicePrefersAnExplicitRouteLane() {
        assertEquals(3, NavigationLogic.selectLane(3, 2, "GO_STRAIGHT", "right", null, null))
        assertEquals(1, NavigationLogic.selectLane(3, 2, "TURN_LEFT", "left", "left", 40.0))
        assertEquals(3, NavigationLogic.selectLane(3, 1, "EXIT_HIGHWAY", "2-3", "right", 100.0))
        assertEquals(2, NavigationLogic.selectLane(3, 1, "KEEP_LEFT", "2-3", "left", 100.0))
    }

    @Test
    fun farExitStaysInTheCurrentLane() {
        val lane = NavigationLogic.selectLane(
            laneCount = 3,
            currentLane = 2,
            action = "EXIT_HIGHWAY",
            requiredLaneRaw = null,
            turnDirection = "right",
            distanceMeters = 2_000.0,
        )
        assertEquals(2, lane)
    }

    @Test
    fun perceptionMessagesBecomeASpatialInstruction() {
        var world = World()
        world = world.apply(ProtocolDecoder.decode(FRAME))
        world = world.apply(ProtocolDecoder.decode(NAV))
        val instruction = world.instruction(0.0)
        assertEquals("live", instruction.source)
        assertEquals(960, instruction.imageWidth)
        assertEquals(540, instruction.imageHeight)
        assertEquals(3, instruction.navigation.requiredLane)
        assertEquals("EXIT 56", instruction.navigation.exit?.label)
        assertEquals("0.4 mi", instruction.navigation.exit?.distanceLabel)
        assertTrue(instruction.lanes.first { it.index == 3 }.arrow.highlighted)
        assertEquals("18 m", instruction.vehicles.single().distanceLabel)
        assertEquals(true, instruction.vehicles.single().inFront)
        assertEquals("SPEED LIMIT 55", instruction.signs.first().label)
        assertEquals("RED LIGHT", instruction.signs.first { it.label == "RED LIGHT" }.label)
    }

    @Test
    fun directSpatialInstructionIsNotRecomputed() {
        val event = ProtocolDecoder.decode(DIRECT)
        val instruction = World().apply(event).instruction(9.0)
        assertEquals("spatial", instruction.source)
        assertEquals(ArrowHeading.LEFT, instruction.lanes.single().arrow.heading)
        assertTrue(instruction.lanes.single().arrow.highlighted)
        assertEquals("EXIT 56", instruction.navigation.exit?.label)
        assertEquals("0.4 mi", instruction.navigation.exit?.distanceLabel)
    }

    @Test
    fun spatialInstructionRoundTrips() {
        val original = NavigationLogic.demoInstruction(0.0)
        val encoded = SpatialJson.encode(original)
        assertTrue(encoded.contains("\"type\":\"spatial.instruction\""))
        assertTrue(encoded.contains("\"distanceLabel\":\"0.4 mi\""))
        assertTrue(encoded.contains("\"label\":\"EXIT 56\""))
        val decoded = SpatialJson.decode(encoded)
        assertEquals(SpatialInstruction.TYPE, decoded.type)
        assertEquals(original.navigation.exit?.label, decoded.navigation.exit?.label)
        assertEquals(original.navigation.exit?.distanceLabel, decoded.navigation.exit?.distanceLabel)
        assertEquals(
            original.lanes.map { it.arrow.highlighted },
            decoded.lanes.map { it.arrow.highlighted },
        )
        assertEquals(original.vehicles.single().distanceLabel, decoded.vehicles.single().distanceLabel)
    }

    @Test
    fun skipAndGarbageDoNotChangeTheWorld() {
        val world = World().apply(ProtocolDecoder.decode(FRAME))
        val skipped = world.apply(ProtocolDecoder.decode("""{"type":"perception.skip","frameId":4}"""))
        val garbage = skipped.apply(ProtocolDecoder.decode("not-json"))
        assertTrue(garbage.hasPerception)
        assertEquals(1, garbage.vehicles.size)
        assertTrue(ProtocolDecoder.decode("""{"type":"perception.skip","frameId":4}""") is ServerEvent.Skip)
    }

    private companion object {
        val FRAME = """
            {
              "type": "perception.frame",
              "ptsSeconds": 1.5,
              "image": { "width": 960, "height": 540 },
              "lanes": {
                "currentLane": 2,
                "laneCount": 3,
                "laneBoundaries": [
                  [[70, 520], [430, 230]],
                  [[250, 530], [470, 230]],
                  [[640, 530], [510, 230]],
                  [[900, 520], [560, 230]]
                ],
                "confidence": 0.8
              },
              "objects": [
                {
                  "id": 4,
                  "class": "car",
                  "bbox": [430, 214, 548, 392],
                  "distanceMeters": 18.4,
                  "inEgoPath": true
                },
                {
                  "id": 9,
                  "class": "traffic light",
                  "bbox": [500, 40, 540, 90],
                  "lightState": "red",
                  "inEgoPath": false
                }
              ],
              "signs": [
                { "id": 1000001, "signClass": "speedLimit55", "bbox": [760, 48, 900, 140], "confidence": 0.9 }
              ]
            }
        """.trimIndent()

        val NAV = """
            {
              "type": "navigation.packet",
              "routeState": {
                "action": "EXIT_HIGHWAY",
                "audio": "Take exit 56",
                "ui": "EXIT_MARKER",
                "distanceMeters": 643.7,
                "requiredLane": "right",
                "turnDirection": "right",
                "roadName": "Exit 56"
              }
            }
        """.trimIndent()

        val DIRECT = """
            {
              "type": "spatial.instruction",
              "source": "spatial",
              "image": { "width": 960, "height": 540 },
              "laneCount": 1,
              "lanes": [
                {
                  "index": 1,
                  "recommended": true,
                  "boundaries": [],
                  "arrow": { "laneIndex": 1, "anchor": [10, 20], "heading": "LEFT", "highlighted": true }
                }
              ],
              "vehicles": [],
              "signs": [],
              "navigation": {
                "action": "EXIT_HIGHWAY",
                "requiredLane": 1,
                "audio": "hold the left lane",
                "exit": { "label": "EXIT 56", "distanceMeters": 643.7 }
              }
            }
        """.trimIndent()
    }
}
