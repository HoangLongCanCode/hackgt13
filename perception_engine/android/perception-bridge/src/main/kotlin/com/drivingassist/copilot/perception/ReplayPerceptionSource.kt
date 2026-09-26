package com.drivingassist.copilot.perception

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Replays recorded or synthetic messages through the real decoder. For tests and offline demos
 * only; realtime mode never touches files.
 *
 * @param realtime pace frames by their `ptsSeconds` deltas (divided by [speed]); false = as fast as possible.
 */
class ReplayPerceptionSource(
    private val jsonMessages: List<String>,
    private val realtime: Boolean = false,
    private val speed: Double = 1.0,
    private val loop: Boolean = false,
) : PerceptionSource {

    @Volatile
    private var closed = false

    override val messages: Flow<PerceptionMessage> = flow {
        do {
            var prevPts: Double? = null
            for (text in jsonMessages) {
                if (closed) return@flow
                val message = PerceptionCodec.decode(text)
                if (realtime && message is PerceptionFrame) {
                    val prev = prevPts
                    if (prev != null && message.ptsSeconds > prev) {
                        delay(((message.ptsSeconds - prev) * 1000.0 / speed).toLong())
                    }
                    prevPts = message.ptsSeconds
                }
                emit(message)
            }
            if (loop && realtime) delay(100) // a one-message file must not spin a core
        } while (loop && !closed)
    }

    override fun close() {
        closed = true
    }

    companion object {
        fun fromMessages(messages: List<PerceptionMessage>, realtime: Boolean = false, speed: Double = 1.0) =
            ReplayPerceptionSource(messages.map { PerceptionCodec.encode(it) }, realtime, speed)

        /** Reads a JSON array of messages, a single (pretty) JSON message, or JSON Lines. */
        fun readMessages(file: File): List<String> {
            val text = file.readText(Charsets.UTF_8)
            val whole = runCatching { PerceptionCodec.json.parseToJsonElement(text) }.getOrNull()
            return when (whole) {
                is JsonArray -> whole.map { it.toString() }
                is JsonObject -> listOf(whole.toString())
                else -> text.lineSequence().map { it.trim() }.filter { it.startsWith("{") }.toList()
            }
        }

        fun fromFile(file: File, realtime: Boolean = false, speed: Double = 1.0, loop: Boolean = false) =
            ReplayPerceptionSource(readMessages(file), realtime, speed, loop)
    }
}
