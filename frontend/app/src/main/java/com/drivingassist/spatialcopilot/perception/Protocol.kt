package com.drivingassist.spatialcopilot.perception

import com.drivingassist.spatialcopilot.model.Px
import com.drivingassist.spatialcopilot.model.SignLabels
import com.drivingassist.spatialcopilot.model.SignMarker
import com.drivingassist.spatialcopilot.model.SpatialInstruction
import com.drivingassist.spatialcopilot.model.SpatialJson
import com.drivingassist.spatialcopilot.model.VehicleMarker
import com.drivingassist.spatialcopilot.model.array
import com.drivingassist.spatialcopilot.model.double
import com.drivingassist.spatialcopilot.model.int
import com.drivingassist.spatialcopilot.model.obj
import com.drivingassist.spatialcopilot.model.str
import com.drivingassist.spatialcopilot.model.toBoundaries
import com.drivingassist.spatialcopilot.model.toBox
import com.drivingassist.spatialcopilot.nav.NavigationLogic
import com.drivingassist.spatialcopilot.nav.RawNav
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

data class LaneObservation(
    val currentLane: Int?,
    val laneCount: Int?,
    val boundaries: List<List<Px>>?,
)

data class DistancePatch(
    val id: Int,
    val distanceMeters: Double?,
)

sealed interface ServerEvent {
    data class Hello(
        val sessionId: String,
        val maxInFlight: Int,
        val role: String,
    ) : ServerEvent

    data class Frame(
        val imageWidth: Int?,
        val imageHeight: Int?,
        val timeSeconds: Double?,
        val vehicles: List<VehicleMarker>?,
        val signs: List<SignMarker>?,
        val lanes: LaneObservation?,
    ) : ServerEvent

    data class Update(
        val timeSeconds: Double?,
        val distances: List<DistancePatch>,
        val signs: List<SignMarker>?,
        val lanes: LaneObservation?,
    ) : ServerEvent

    data class Navigation(val nav: RawNav) : ServerEvent

    data class Direct(val instruction: SpatialInstruction) : ServerEvent

    data class Skip(val frameId: Long) : ServerEvent

    data class Failure(val message: String) : ServerEvent

    data object Ignored : ServerEvent
}

object ProtocolDecoder {
    private val json = Json { ignoreUnknownKeys = true }
    private val vehicleClasses = setOf(
        "car",
        "truck",
        "bus",
        "motorcycle",
        "bicycle",
        "pedestrian",
        "rider",
    )

    fun decode(text: String): ServerEvent = try {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return ServerEvent.Ignored
        when (root.str("type")) {
            "perception.hello" -> root.toHello()
            "perception.frame" -> root.toFrame()
            "perception.update" -> root.toUpdate()
            "navigation.packet" -> root.toNavigation()
            SpatialInstruction.TYPE -> ServerEvent.Direct(SpatialJson.decode(root))
            "perception.skip" -> ServerEvent.Skip(root.long("frameId") ?: 0L)
            "perception.error" -> ServerEvent.Failure(
                root.str("message") ?: root.str("code") ?: "server error",
            )
            else -> ServerEvent.Ignored
        }
    } catch (_: Exception) {
        ServerEvent.Ignored
    }

    private fun JsonObject.toHello(): ServerEvent.Hello = ServerEvent.Hello(
        sessionId = str("sessionId") ?: "",
        maxInFlight = obj("uplink")?.int("maxInFlight") ?: 2,
        role = str("role") ?: "controller",
    )

    private fun JsonObject.toFrame(): ServerEvent.Frame {
        val image = obj("image")
        val parsed = array("objects")?.parseObjects()
        return ServerEvent.Frame(
            imageWidth = image?.int("width"),
            imageHeight = image?.int("height"),
            timeSeconds = double("ptsSeconds"),
            vehicles = if (containsKey("objects")) parsed?.vehicles.orEmpty() else null,
            signs = signsOrNull(parsed?.lights.orEmpty()),
            lanes = laneObservation(),
        )
    }

    private fun JsonObject.toUpdate(): ServerEvent.Update {
        val parsedLights = emptyList<SignMarker>()
        return ServerEvent.Update(
            timeSeconds = double("ptsSeconds"),
            distances = array("distances")?.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                DistancePatch(
                    id = item.int("id") ?: return@mapNotNull null,
                    distanceMeters = item.double("distanceMeters"),
                )
            }.orEmpty(),
            signs = signsOrNull(parsedLights),
            lanes = laneObservation(),
        )
    }

    private fun JsonObject.toNavigation(): ServerEvent {
        val route = obj("routeState") ?: return ServerEvent.Ignored
        return ServerEvent.Navigation(
            RawNav(
                action = route.str("action") ?: "GO_STRAIGHT",
                requiredLaneRaw = route.laneRaw("requiredLane"),
                turnDirection = route.str("turnDirection"),
                distanceMeters = route.double("distanceMeters"),
                audio = route.str("audio") ?: "",
                roadName = route.str("roadName"),
            ),
        )
    }

    private fun JsonObject.laneObservation(): LaneObservation? {
        if (!containsKey("lanes")) return null
        val lanes = obj("lanes") ?: return LaneObservation(null, null, emptyList())
        val boundaries = if (lanes.containsKey("laneBoundaries")) {
            lanes.array("laneBoundaries")?.toBoundaries().orEmpty()
        } else {
            null
        }
        return LaneObservation(
            currentLane = lanes.int("currentLane"),
            laneCount = lanes.int("laneCount"),
            boundaries = boundaries,
        )
    }

    private fun JsonObject.signsOrNull(lights: List<SignMarker>): List<SignMarker>? {
        if (!containsKey("signs") && lights.isEmpty()) return null
        val signs = array("signs")?.mapNotNull { it.toSign() }.orEmpty()
        return signs + lights
    }

    private fun JsonArray.parseObjects(): ParsedObjects {
        val vehicles = mutableListOf<VehicleMarker>()
        val lights = mutableListOf<SignMarker>()
        for (element in this) {
            val item = element as? JsonObject ?: continue
            val kind = item.str("class")?.lowercase() ?: continue
            val box = (item["bbox"] as? JsonArray)?.toBox() ?: continue
            if (kind == "traffic light") {
                val state = item.str("lightState") ?: continue
                if (state.equals("unknown", ignoreCase = true)) continue
                lights += SignMarker(
                    id = item.int("id") ?: 0,
                    label = "${state.uppercase()} LIGHT",
                    box = box,
                )
            } else if (kind in vehicleClasses) {
                vehicles += VehicleMarker(
                    id = item.int("id") ?: 0,
                    box = box,
                    distanceMeters = item.double("distanceMeters"),
                    inFront = (item["inEgoPath"] as? JsonPrimitive)?.booleanOrNull == true,
                )
            }
        }
        return ParsedObjects(vehicles, lights)
    }

    private fun kotlinx.serialization.json.JsonElement.toSign(): SignMarker? {
        val item = this as? JsonObject ?: return null
        val label = SignLabels.pretty(item.str("signClass") ?: item.str("label") ?: return null)
        if (label.isBlank()) return null
        val box = (item["bbox"] as? JsonArray)?.toBox()
            ?: (item["box"] as? JsonArray)?.toBox()
            ?: return null
        return SignMarker(id = item.int("id") ?: 0, label = label, box = box)
    }

    private fun JsonObject.laneRaw(key: String): String? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.content
    }

    private fun JsonObject.long(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.content.toLongOrNull() ?: primitive.content.toDoubleOrNull()?.toLong()
    }
}

private data class ParsedObjects(
    val vehicles: List<VehicleMarker>,
    val lights: List<SignMarker>,
)

/**
 * Perception session folded forward from server messages.
 * [instruction] is what the Spatial AR Engine draws.
 */
data class World(
    val sessionId: String = "",
    val imageWidth: Int = 960,
    val imageHeight: Int = 540,
    val timeSeconds: Double = 0.0,
    val currentLane: Int? = null,
    val laneCount: Int? = null,
    val boundaries: List<List<Px>> = emptyList(),
    val vehicles: List<VehicleMarker> = emptyList(),
    val signs: List<SignMarker> = emptyList(),
    val rawNav: RawNav? = null,
    val hasPerception: Boolean = false,
    val direct: SpatialInstruction? = null,
) {
    fun apply(event: ServerEvent): World = when (event) {
        is ServerEvent.Hello -> applyHello(event)
        is ServerEvent.Frame -> applyFrame(event)
        is ServerEvent.Update -> applyUpdate(event)
        is ServerEvent.Navigation -> copy(rawNav = event.nav)
        is ServerEvent.Direct -> copy(direct = event.instruction, hasPerception = true)
        else -> this
    }

    fun instruction(elapsedSeconds: Double): SpatialInstruction {
        direct?.let { return it }
        if (!hasPerception) return NavigationLogic.demoInstruction(elapsedSeconds)
        val nav = rawNav
        return NavigationLogic.assemble(
            timeSeconds = if (timeSeconds > 0.0) timeSeconds else elapsedSeconds,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            currentLane = currentLane,
            laneCount = laneCount,
            boundaries = boundaries,
            vehicles = vehicles,
            signs = signs,
            nav = nav ?: NavigationLogic.placeholderNav(elapsedSeconds),
            source = if (nav == null) "live-sim-nav" else "live",
        )
    }

    private fun applyHello(event: ServerEvent.Hello): World {
        if (event.sessionId == "idle") {
            return copy(
                sessionId = "idle",
                hasPerception = false,
                direct = null,
                vehicles = emptyList(),
                boundaries = emptyList(),
                signs = emptyList(),
                currentLane = null,
                laneCount = null,
            )
        }
        val switching = sessionId.isNotEmpty() &&
            event.sessionId.isNotEmpty() &&
            event.sessionId != sessionId
        val nextId = event.sessionId.ifBlank { sessionId }
        if (!switching) return copy(sessionId = nextId)
        return copy(
            sessionId = nextId,
            direct = null,
            vehicles = emptyList(),
            boundaries = emptyList(),
            signs = emptyList(),
            currentLane = null,
            laneCount = null,
        )
    }

    private fun applyFrame(event: ServerEvent.Frame): World {
        val lanes = event.lanes
        return copy(
            direct = null,
            hasPerception = true,
            imageWidth = event.imageWidth ?: imageWidth,
            imageHeight = event.imageHeight ?: imageHeight,
            timeSeconds = event.timeSeconds ?: timeSeconds,
            currentLane = lanes?.currentLane ?: currentLane,
            laneCount = lanes?.laneCount ?: laneCount,
            boundaries = lanes?.boundaries ?: boundaries,
            vehicles = if (event.vehicles != null) mergeVehicles(vehicles, event.vehicles) else vehicles,
            signs = event.signs ?: signs,
        )
    }

    private fun applyUpdate(event: ServerEvent.Update): World {
        val lanes = event.lanes
        val patched = if (event.distances.isEmpty()) {
            vehicles
        } else {
            vehicles.map { vehicle ->
                val patch = event.distances.find { it.id == vehicle.id } ?: return@map vehicle
                if (patch.distanceMeters == null) vehicle else vehicle.copy(distanceMeters = patch.distanceMeters)
            }
        }
        return copy(
            direct = null,
            hasPerception = true,
            timeSeconds = event.timeSeconds ?: timeSeconds,
            currentLane = lanes?.currentLane ?: currentLane,
            laneCount = lanes?.laneCount ?: laneCount,
            boundaries = lanes?.boundaries ?: boundaries,
            vehicles = patched,
            signs = event.signs ?: signs,
        )
    }

    private fun mergeVehicles(
        previous: List<VehicleMarker>,
        next: List<VehicleMarker>,
    ): List<VehicleMarker> {
        val prior = previous.associateBy { it.id }
        return next.map { vehicle ->
            if (vehicle.distanceMeters != null) {
                vehicle
            } else {
                vehicle.copy(distanceMeters = prior[vehicle.id]?.distanceMeters)
            }
        }
    }
}
