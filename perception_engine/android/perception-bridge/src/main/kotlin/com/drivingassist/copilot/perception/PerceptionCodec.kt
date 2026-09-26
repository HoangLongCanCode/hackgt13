package com.drivingassist.copilot.perception

import kotlinx.serialization.KSerializer
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * JSON text <-> protocol messages (PROTOCOL_v2).
 *
 * Server messages ([PerceptionMessage]) are decoded in one pass: kotlinx.serialization reads the
 * `"type"` discriminator and decodes straight into the data class. Lenient on purpose for realtime
 * use: unknown keys are ignored, unknown enum values fall back to the property default
 * (`class` -> UNKNOWN, `lightState` -> null, `source.kind` -> UNKNOWN), and unknown message types
 * become [UnknownMessage].
 *
 * Client messages ([ClientMessage]) are encoded with defaults and explicit nulls, exactly like the
 * protocol examples (`"protocolVersion": 2`, `"sim": null`).
 */
object PerceptionCodec {
    private val module = SerializersModule {
        polymorphic(PerceptionMessage::class) {
            subclass(PerceptionFrame::class)
            subclass(PerceptionUpdate::class)
            subclass(HelloMessage::class)
            subclass(StatsMessage::class)
            subclass(SkipMessage::class)
            subclass(PongMessage::class)
            subclass(ErrorMessage::class)
            subclass(NavigationPacketMessage::class)
            subclass(NavigationPlacesMessage::class)
        }
    }

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        classDiscriminator = "type"
        serializersModule = module
    }

    /** Client -> server encoding: defaults and nulls written, like the PROTOCOL_v2 examples. */
    val clientJson: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = true
        encodeDefaults = true
        coerceInputValues = true
        classDiscriminator = "type"
    }

    private val prettyJson: Json = Json(json) { prettyPrint = true }

    private val messageSerializer: KSerializer<PerceptionMessage> = PolymorphicSerializer(PerceptionMessage::class)
    private val clientSerializer: KSerializer<ClientMessage> = ClientMessage.serializer()

    val knownTypes: Set<String> = setOf(
        PerceptionFrame.TYPE, PerceptionUpdate.TYPE, HelloMessage.TYPE, StatsMessage.TYPE, SkipMessage.TYPE, PongMessage.TYPE,
        ErrorMessage.TYPE, NavigationPacketMessage.TYPE, NavigationPlacesMessage.TYPE,
    )

    /** Decode one server WebSocket text message. Throws [SerializationException] for a malformed known message. */
    fun decode(text: String): PerceptionMessage {
        try {
            return json.decodeFromString(messageSerializer, text)
        } catch (e: SerializationException) {
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw e
            val type = (obj["type"] as? JsonPrimitive)?.content
            if (type in knownTypes) throw e
            return UnknownMessage(type, obj)
        } catch (e: IllegalArgumentException) {
            // e.g. a require() in a data class; surface it as a serialization problem.
            throw SerializationException("Invalid perception message: ${e.message}", e)
        }
    }

    fun decodeFrame(text: String): PerceptionFrame =
        decode(text) as? PerceptionFrame ?: throw SerializationException("Not a ${PerceptionFrame.TYPE} message")

    /** Encode including the `"type"` field, as the Python side sends it. */
    fun encode(message: PerceptionMessage, pretty: Boolean = false): String {
        if (message is UnknownMessage) return message.raw.toString()
        return (if (pretty) prettyJson else json).encodeToString(messageSerializer, message)
    }

    fun toJsonObject(message: PerceptionMessage): JsonObject = json.parseToJsonElement(encode(message)).jsonObject

    /**
     * Encode a tablet -> server message (`client.hello`, `client.playback`, `client.ping`, `client.trip_state`,
     * `client.destination`, `client.place_search`).
     */
    fun encodeClient(message: ClientMessage): String = clientJson.encodeToString(clientSerializer, message)

    /** Decode a tablet -> server message (test servers, sample files). */
    fun decodeClient(text: String): ClientMessage = try {
        clientJson.decodeFromString(clientSerializer, text)
    } catch (e: SerializationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw SerializationException("Invalid client message: ${e.message}", e)
    }
}
