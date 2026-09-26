package com.ksr.copilot.perception

import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Field-level cross-check of the real `contracts/samples/v2/` messages written by the Python server and the
 * phase1 relay against the Kotlin model. [ProtocolV2Test] proves every sample decodes; decoding is lenient
 * (`ignoreUnknownKeys`, `coerceInputValues`), so a renamed field or an unknown enum value would silently
 * become null / a default there. This test re-encodes each decoded message and compares it with the sample:
 *
 * - **changed**: a value the sample has that comes back different, e.g. `"class": "traffic sign"` decoded
 *   as UNKNOWN, or a number truncated. Always a failure.
 * - **dropped**: a key the Kotlin model does not carry. Allowed only for the server diagnostics listed in
 *   [IGNORED] (the client has no use for them); anything else is a failure, so a new protocol field shows up
 *   here until the Kotlin side models it or it is added to the list on purpose.
 *
 * Free-form blocks that Kotlin keeps as raw JSON (`models`, `schedule`, `navigation`, `packet`, `detail`) are
 * compared as a whole by the round trip and not walked.
 */
class ContractFieldCoverageTest {
    private val dir: File? = System.getProperty("contracts.samples.dir")?.let { File(it, "v2") }?.takeIf { it.isDirectory }

    /** Server encoding with every property written, so "dropped" means "not modelled", never "was null". */
    private val fullServerJson = Json(PerceptionCodec.json) { explicitNulls = true; encodeDefaults = true }
    private val serverSerializer = PolymorphicSerializer(PerceptionMessage::class)

    @Test
    fun `every field of every real v2 sample is modelled in Kotlin or deliberately ignored`() {
        val files = dir?.listFiles { f -> f.extension == "json" }?.sortedBy { it.name }.orEmpty()
        assumeTrue(files.isNotEmpty(), "contracts/samples/v2 not present")
        val report = StringBuilder()
        val problems = mutableListOf<String>()
        for (f in files) {
            val sample = Json.parseToJsonElement(f.readText()).jsonObject
            val type = sample["type"]!!.jsonPrimitive.content
            val back: JsonObject = if (type.startsWith("client.")) {
                val msg = PerceptionCodec.decodeClient(f.readText())
                Json.parseToJsonElement(PerceptionCodec.encodeClient(msg)).jsonObject
            } else {
                val msg = PerceptionCodec.decode(f.readText())
                assertTrue(msg !is UnknownMessage, "${f.name}: unknown type $type")
                Json.parseToJsonElement(fullServerJson.encodeToString(serverSerializer, msg)).jsonObject
            }
            val dropped = sortedSetOf<String>()
            val changed = mutableListOf<String>()
            compare(sample, back, "", dropped, changed)
            val unexpected = dropped.filterNot { path -> IGNORED[type].orEmpty().any { path == it || path.startsWith("$it.") } }
            report.append("${f.name} ($type): ${if (dropped.isEmpty()) "all fields modelled" else "ignored ${dropped.joinToString()}"}")
            if (changed.isNotEmpty()) report.append("  CHANGED ${changed.take(5)}")
            report.append('\n')
            if (changed.isNotEmpty()) problems += "${f.name}: values changed by the Kotlin round trip: ${changed.take(5)}"
            if (unexpected.isNotEmpty()) problems += "${f.name}: fields not modelled in Kotlin: $unexpected"
        }
        println(report)
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /** Walks [a] (the sample); array elements share one path (`objects[].id`). */
    private fun compare(a: JsonElement, b: JsonElement?, path: String, dropped: MutableSet<String>, changed: MutableList<String>) {
        when (a) {
            is JsonObject -> {
                if (path.substringAfterLast('.') in FREE_FORM) return
                val bo = b as? JsonObject
                if (bo == null) { changed += "$path: object -> ${b?.toString()?.take(40)}"; return }
                for ((k, v) in a) {
                    val p = if (path.isEmpty()) k else "$path.$k"
                    if (k !in bo) { if (v !is JsonNull) dropped += p; continue }
                    compare(v, bo[k], p, dropped, changed)
                }
            }
            is JsonArray -> {
                val ba = b as? JsonArray
                if (ba == null || ba.size != a.size) { changed += "$path: array of ${a.size} -> ${ba?.size ?: b}"; return }
                a.forEachIndexed { i, e -> compare(e, ba[i], "$path[]", dropped, changed) }
            }
            is JsonNull -> if (b != null && b !is JsonNull) changed += "$path: null -> $b"
            is JsonPrimitive -> {
                val bp = b as? JsonPrimitive
                val same = when {
                    bp == null || bp is JsonNull -> false
                    a.isString || bp.isString -> a.isString == bp.isString && a.content == bp.content ||
                        (path.endsWith("requiredLane") && a.content == bp.content) // number accepted as free text
                    else -> a.content == bp.content || (a.doubleOrNull != null && a.doubleOrNull == bp.doubleOrNull)
                }
                if (!same) changed += "$path: $a -> $bp"
            }
        }
    }

    private companion object {
        /** Raw JSON in Kotlin (kept verbatim, compared by the round trip, not walked). */
        val FREE_FORM = setOf("models", "schedule", "navigation", "packet", "detail")

        /**
         * Server fields the tablet deliberately does not model: diagnostics and server bookkeeping
         * (PROTOCOL_v2 "additive fields"). Everything a client acts on is modelled.
         */
        val IGNORED: Map<String, List<String>> = mapOf(
            "perception.hello" to listOf(
                "serverTimeMs", "targetHz", "classes", "staticIdOffset", "server", "safety",
                "sim.lookaheadMode", "sim.videoId",
            ),
            "perception.update" to listOf("camera"),
            "perception.skip" to listOf("sessionId", "serverTimeMs"),
            "perception.stats" to listOf(
                "schemaVersion", "mode", "windowSeconds", "wave2Fps", "distanceFps", "sourceFps", "wave1ComputeMs",
                "wave2ComputeMs", "updates", "sendDropped", "lookaheadSeconds", "simLeadMs", "simLateFraction",
                "uplinkClient", "navigationPackets", "uptimeSeconds",
            ),
        )
    }
}
