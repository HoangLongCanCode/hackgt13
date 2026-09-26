package com.ksr.copilot.perception

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * Decodes any JSON primitive (string, number, boolean) as its text, so a free-text field that a
 * producer sometimes writes as a number (phase1 `requiredLane`: "right" or 2) never breaks decoding.
 * Encodes as a JSON string.
 */
object AnyPrimitiveAsStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ksr.AnyPrimitiveAsString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        if (decoder is JsonDecoder) {
            return when (val e = decoder.decodeJsonElement()) {
                is JsonPrimitive -> e.content
                else -> e.toString()
            }
        }
        return decoder.decodeString()
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}
