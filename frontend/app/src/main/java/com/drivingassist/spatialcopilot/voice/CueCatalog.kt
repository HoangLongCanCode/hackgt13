package com.drivingassist.spatialcopilot.voice

import com.drivingassist.copilot.context.Priority
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** One cue of `audio_cues.v1.json` (only the numbers and texts the tablet uses). */
data class CueSpec(
    val id: String,
    val priority: Priority,
    val enabledByDefault: Boolean,
    val persistenceMs: Long,
    val episodeEndMs: Long,
    val maxPerEpisode: Int,
    val minRepeatMs: Long,
    val ttlMs: Long,
    val earcon: String?,
    val text: String?,
    val voiceProfile: String,
    val numbers: Map<String, Double>,
)

/**
 * The agreed cue catalog (`perception_engine/docs/audio/audio_cues.v1.json`, copied into the APK assets at
 * build time, so the JSON stays the one source of truth). Prose fields (condition / trigger) are hand-coded
 * in [CuePolicy]; the numbers come from here.
 */
class CueCatalog(val cues: Map<String, CueSpec>, val fixedPhrases: List<String>, val ttlByPriority: Map<Priority, Long>, val nav: Map<String, Double>, val speechRegex: Regex) {

    operator fun get(id: String): CueSpec = cues[id] ?: error("cue $id missing from audio_cues.v1.json")

    fun ttl(priority: Priority): Long = ttlByPriority[priority] ?: 3_000L

    companion object {
        const val ASSET = "audio/audio_cues.v1.json"

        fun parse(text: String): CueCatalog {
            val root = Json.parseToJsonElement(text) as JsonObject
            require(root.str("schema") == "audio_cues.v1") { "not an audio_cues.v1 catalog" }
            val ttl = (root["ttlMsByPriority"] as JsonObject).mapNotNull { (k, v) ->
                runCatching { Priority.valueOf(k) }.getOrNull()?.let { it to (v as JsonPrimitive).content.toDouble().toLong() }
            }.toMap()
            val cues = (root["cues"] as JsonArray).map { it as JsonObject }.associate { c ->
                val priority = Priority.valueOf(c.str("priority")!!)
                val numbers = c.filterValues { it is JsonPrimitive && (it as JsonPrimitive).doubleOrNull != null && !it.isString }
                    .mapValues { (_, v) -> (v as JsonPrimitive).doubleOrNull!! }
                val id = c.str("id")!!
                id to CueSpec(
                    id = id,
                    priority = priority,
                    enabledByDefault = c.bool("enabledByDefault") ?: true,
                    persistenceMs = c.num("persistenceMs")?.toLong() ?: 0L,
                    episodeEndMs = c.num("episodeEndMs")?.toLong() ?: 2_000L,
                    maxPerEpisode = c.num("maxPerEpisode")?.toInt() ?: 1,
                    minRepeatMs = c.num("minRepeatMs")?.toLong() ?: 0L,
                    ttlMs = c.num("ttlMs")?.toLong() ?: ttl[priority] ?: 3_000L,
                    earcon = c.str("earcon"),
                    text = c.str("text") ?: c.str("template"),
                    voiceProfile = c.str("voiceProfile") ?: "nav",
                    numbers = numbers,
                )
            }
            val fixed = (root["fixedPhrases"] as JsonArray).map { (it as JsonPrimitive).content }
            val navObj = root["navigation"] as? JsonObject
            val nav = HashMap<String, Double>()
            navObj?.forEach { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { nav[k] = it } }
            (navObj?.get("immediate") as? JsonObject)?.forEach { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { nav["immediate.$k"] = it } }
            (navObj?.get("prepareMetersBySpeed") as? JsonArray)?.forEachIndexed { i, e ->
                val o = e as JsonObject
                o.num("belowMps")?.let { nav["prepare.$i.belowMps"] = it }
                o.num("meters")?.let { nav["prepare.$i.meters"] = it }
            }
            return CueCatalog(cues, fixed, ttl, nav, Regex(root.str("speechRegex") ?: "^[A-Z][A-Za-z ,'-]*\\.$"))
        }

        private fun JsonObject.prim(key: String): JsonPrimitive? = (this[key] as? JsonElement) as? JsonPrimitive
        private fun JsonObject.str(key: String): String? = prim(key)?.takeIf { it.isString }?.content
        private fun JsonObject.num(key: String): Double? = prim(key)?.takeIf { !it.isString }?.doubleOrNull
        private fun JsonObject.bool(key: String): Boolean? = prim(key)?.booleanOrNull
    }
}
