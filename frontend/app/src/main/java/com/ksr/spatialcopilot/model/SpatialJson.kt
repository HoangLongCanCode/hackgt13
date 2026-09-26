package com.ksr.spatialcopilot.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Codec for the Spatial Instruction payload documented in perception_api.md. */
object SpatialJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(instruction: SpatialInstruction): String = buildJsonObject {
        put("type", SpatialInstruction.TYPE)
        put("schemaVersion", SpatialInstruction.SCHEMA_VERSION)
        put("source", instruction.source)
        put("timeSeconds", instruction.timeSeconds)
        putJsonObject("image") {
            put("width", instruction.imageWidth)
            put("height", instruction.imageHeight)
        }
        put("currentLane", instruction.currentLane)
        put("laneCount", instruction.laneCount)
        putJsonArray("lanes") {
            instruction.lanes.forEach { lane -> add(laneObject(lane)) }
        }
        putJsonArray("vehicles") {
            instruction.vehicles.forEach { vehicle -> add(vehicleObject(vehicle)) }
        }
        putJsonArray("signs") {
            instruction.signs.forEach { sign -> add(signObject(sign)) }
        }
        put("navigation", navigationObject(instruction.navigation))
    }.toString()

    fun decode(text: String): SpatialInstruction =
        decode(json.parseToJsonElement(text) as JsonObject)

    fun decode(root: JsonObject): SpatialInstruction {
        val image = root.obj("image")
        val navigation = root.obj("navigation") ?: error("spatial.instruction missing navigation")
        return SpatialInstruction(
            timeSeconds = root.double("timeSeconds") ?: 0.0,
            imageWidth = image?.int("width") ?: 960,
            imageHeight = image?.int("height") ?: 540,
            currentLane = root.int("currentLane"),
            laneCount = root.int("laneCount") ?: root.array("lanes")?.size ?: 0,
            lanes = root.array("lanes")?.mapNotNull { element ->
                (element as? JsonObject)?.toLane()
            }.orEmpty(),
            vehicles = root.array("vehicles")?.mapNotNull { element ->
                (element as? JsonObject)?.toVehicle()
            }.orEmpty(),
            signs = root.array("signs")?.mapNotNull { element ->
                (element as? JsonObject)?.toSign()
            }.orEmpty(),
            navigation = navigation.toNavigation(),
            source = root.str("source") ?: "spatial",
        )
    }

    private fun laneObject(lane: LaneSlot) = buildJsonObject {
        put("index", lane.index)
        put("recommended", lane.recommended)
        putJsonArray("boundaries") {
            lane.boundaries.forEach { polyline ->
                add(buildJsonArray {
                    polyline.forEach { point ->
                        add(buildJsonArray {
                            add(JsonPrimitive(point.x))
                            add(JsonPrimitive(point.y))
                        })
                    }
                })
            }
        }
        put("arrow", buildJsonObject {
            put("laneIndex", lane.arrow.laneIndex)
            put("anchor", buildJsonArray {
                add(JsonPrimitive(lane.arrow.anchorX))
                add(JsonPrimitive(lane.arrow.anchorY))
            })
            put("heading", lane.arrow.heading.name)
            put("highlighted", lane.arrow.highlighted)
        })
    }

    private fun vehicleObject(vehicle: VehicleMarker) = buildJsonObject {
        put("id", vehicle.id)
        put("box", boxArray(vehicle.box))
        if (vehicle.distanceMeters == null) {
            put("distanceMeters", JsonNull)
            put("distanceLabel", JsonNull)
        } else {
            put("distanceMeters", vehicle.distanceMeters)
            put("distanceLabel", vehicle.distanceLabel)
        }
        put("inFront", vehicle.inFront)
    }

    private fun signObject(sign: SignMarker) = buildJsonObject {
        put("id", sign.id)
        put("label", sign.label)
        put("box", boxArray(sign.box))
    }

    private fun navigationObject(navigation: NavigationCue) = buildJsonObject {
        put("action", navigation.action)
        put("requiredLane", navigation.requiredLane)
        put("turnDirection", navigation.turnDirection)
        put("audio", navigation.audio)
        val exit = navigation.exit
        if (exit == null) {
            put("exit", JsonNull)
        } else {
            putJsonObject("exit") {
                put("label", exit.label)
                put("distanceMeters", exit.distanceMeters)
                put("distanceLabel", exit.distanceLabel)
            }
        }
    }

    private fun boxArray(box: ImageBox) = buildJsonArray {
        add(JsonPrimitive(box.x1))
        add(JsonPrimitive(box.y1))
        add(JsonPrimitive(box.x2))
        add(JsonPrimitive(box.y2))
    }

    private fun JsonObject.toLane(): LaneSlot? {
        val index = int("index") ?: return null
        val arrow = obj("arrow")
        val anchor = arrow?.array("anchor")
        val ax = anchor?.numAt(0) ?: 0f
        val ay = anchor?.numAt(1) ?: 0f
        val highlighted = arrow?.bool("highlighted") ?: bool("recommended") ?: false
        return LaneSlot(
            index = index,
            recommended = bool("recommended") ?: highlighted,
            boundaries = array("boundaries")?.toBoundaries().orEmpty(),
            arrow = LaneArrow(
                laneIndex = arrow?.int("laneIndex") ?: index,
                anchorX = ax,
                anchorY = ay,
                heading = ArrowHeading.from(arrow?.str("heading")),
                highlighted = highlighted,
            ),
        )
    }

    private fun JsonObject.toVehicle(): VehicleMarker? {
        val box = array("box")?.toBox() ?: return null
        return VehicleMarker(
            id = int("id") ?: 0,
            box = box,
            distanceMeters = double("distanceMeters"),
            inFront = bool("inFront") ?: true,
        )
    }

    private fun JsonObject.toSign(): SignMarker? {
        val label = str("label")?.takeIf { it.isNotBlank() } ?: return null
        val box = array("box")?.toBox() ?: return null
        return SignMarker(id = int("id") ?: 0, label = label, box = box)
    }

    private fun JsonObject.toNavigation(): NavigationCue {
        val exit = obj("exit")
        val meters = exit?.double("distanceMeters")
        return NavigationCue(
            action = str("action") ?: "GO_STRAIGHT",
            requiredLane = int("requiredLane"),
            turnDirection = str("turnDirection"),
            audio = str("audio") ?: "",
            exit = if (exit == null || meters == null) {
                null
            } else {
                ExitCue(label = exit.str("label") ?: "EXIT", distanceMeters = meters)
            },
        )
    }
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.str(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive is JsonNull || primitive.content == "null") return null
    return primitive.content
}

internal fun JsonObject.double(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject.int(key: String): Int? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return primitive.intOrNullFlexible()
}

internal fun JsonObject.bool(key: String): Boolean? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return primitive.content.toBooleanStrictOrNull()
}

internal fun JsonArray.toBoundaries(): List<List<Px>> = mapNotNull { element ->
    (element as? JsonArray)?.toPolyline()?.takeIf { it.size >= 2 }
}

internal fun JsonArray.toPolyline(): List<Px> = mapNotNull { element ->
    val pair = element as? JsonArray ?: return@mapNotNull null
    val x = pair.numAt(0) ?: return@mapNotNull null
    val y = pair.numAt(1) ?: return@mapNotNull null
    Px(x, y)
}

internal fun JsonArray.toBox(): ImageBox? {
    val x1 = numAt(0) ?: return null
    val y1 = numAt(1) ?: return null
    val x2 = numAt(2) ?: return null
    val y2 = numAt(3) ?: return null
    return ImageBox(x1, y1, x2, y2)
}

internal fun JsonArray.numAt(index: Int): Float? =
    (getOrNull(index) as? JsonPrimitive)?.doubleOrNull?.toFloat()

private fun JsonPrimitive.intOrNullFlexible(): Int? {
    content.toIntOrNull()?.let { return it }
    return doubleOrNull?.toInt()
}
