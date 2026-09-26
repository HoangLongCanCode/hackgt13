package com.drivingassist.spatialcopilot.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * ElevenLabs through the laptop's `/tts` proxy (the key never leaves the laptop). Base URL = the perception
 * WebSocket URL with ws -> http, same host and port (adb reverse covers it). 503 / 403 turn the proxy off
 * for this session; 429 / 502 / 504 / IO errors fall back for that cue only.
 */
class ProxyTts(serverUrl: String) {
    private val base = serverUrl.replaceFirst("ws://", "http://").replaceFirst("wss://", "https://").substringBefore("/perception").trimEnd('/')
    private val http = OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).callTimeout(2500, TimeUnit.MILLISECONDS).build()

    @Volatile var disabledReason: String? = null
        private set

    @Volatile var configured: Boolean = false
        private set

    /** GET /tts/health within 2 s: true when the laptop has an ElevenLabs key. */
    suspend fun probe(): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching {
            withTimeoutOrNull(2_000) {
                http.newCall(Request.Builder().url("$base/tts/health").build()).execute().use { r ->
                    if (!r.isSuccessful) return@use false
                    val body = Json.parseToJsonElement(r.body?.string().orEmpty()) as? JsonObject
                    (body?.get("configured") as? JsonPrimitive)?.booleanOrNull == true
                }
            } == true
        }.getOrDefault(false)
        configured = ok
        if (!ok) disabledReason = "laptop TTS not configured or unreachable"
        ok
    }

    @Volatile private var failuresInARow = 0

    /**
     * 24 kHz mono PCM for [text], or null (fallback). Blocking: call on Dispatchers.IO. After
     * [MAX_FAILURES_IN_A_ROW] failed calls (e.g. ElevenLabs refuses the voice or the plan) the proxy is off for
     * this session, so a broken account does not cost a call per cue.
     */
    fun fetch(text: String, profile: String): ShortArray? {
        if (disabledReason != null) return null
        val body = buildJsonObject { put("text", text); put("voice", if (profile == "alert") "alert" else "nav"); put("cacheOnly", false) }.toString()
        val clip = try {
            http.newCall(Request.Builder().url("$base/tts").post(body.toRequestBody(JSON)).build()).execute().use { r ->
                when {
                    r.code == 503 || r.code == 403 -> { disabledReason = "proxy ${r.code}"; null }
                    !r.isSuccessful -> { Log.i(TAG, "tts proxy HTTP ${r.code} (see the laptop's /tts/health lastError)"); null }
                    else -> r.body?.bytes()?.takeIf { it.size >= 2 && it.size % 2 == 0 }?.let(::pcmFromBytes)
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "tts proxy: ${e.javaClass.simpleName}")
            null
        }
        failuresInARow = if (clip != null) 0 else failuresInARow + 1
        if (failuresInARow >= MAX_FAILURES_IN_A_ROW && disabledReason == null) disabledReason = "ElevenLabs failing on the laptop"
        return clip
    }

    fun close() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private companion object {
        const val TAG = "ProxyTts"
        const val MAX_FAILURES_IN_A_ROW = 3
        val JSON = "application/json".toMediaType()
    }
}

/**
 * Android TextToSpeech as the local fallback: `synthesizeToFile` only (never `speak()`), then the WAV is
 * converted to 24 kHz mono and played through the same [VoiceBus]. Prefers the Google engine.
 */
class NativeTts(context: Context) {
    private val dir = File(context.cacheDir, "tts").apply { mkdirs() }
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private var counter = 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext, { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }, GOOGLE_ENGINE)

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.complete(true) } }
            @Deprecated("Deprecated in API 21")
            override fun onError(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.complete(false) } }
            override fun onError(utteranceId: String?, errorCode: Int) { utteranceId?.let { pending.remove(it)?.complete(false) } }
        })
    }

    /** Renders [text]; null when the engine is missing or fails within [timeoutMs]. */
    suspend fun render(text: String, timeoutMs: Long = 4_000): ShortArray? {
        val ok = withTimeoutOrNull(3_000) { ready.await() } ?: false
        if (!ok) return null
        val id = synchronized(this) { "u${counter++}" }
        val file = File(dir, "$id.wav")
        val done = CompletableDeferred<Boolean>()
        pending[id] = done
        val params = Bundle()
        val started = runCatching {
            tts.language = Locale.US
            tts.synthesizeToFile(text, params, file, id) == TextToSpeech.SUCCESS
        }.getOrDefault(false)
        if (!started) { pending.remove(id); return null }
        val finished = withTimeoutOrNull(timeoutMs) { done.await() } ?: false
        if (!finished) { pending.remove(id); return null }
        return withContext(Dispatchers.IO) {
            runCatching { Wav.to24kMono(file.readBytes()) }.getOrNull().also { file.delete() }
        }
    }

    val engineName: String get() = runCatching { tts.defaultEngine }.getOrNull() ?: "?"

    fun close() = runCatching { tts.shutdown() }

    private companion object {
        const val GOOGLE_ENGINE = "com.google.android.tts"
    }
}

/** Minimal WAV (PCM 16-bit) reader + linear resampler to 24 kHz mono. */
object Wav {
    fun to24kMono(bytes: ByteArray): ShortArray? {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 44 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        var pos = 12
        var channels = 1
        var rate = Earcons.RATE
        var bits = 16
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = b.getInt(pos + 4)
            val start = pos + 8
            if (id == "fmt ") {
                channels = b.getShort(start + 2).toInt()
                rate = b.getInt(start + 4)
                bits = b.getShort(start + 14).toInt()
            } else if (id == "data") {
                if (bits != 16) return null
                val end = minOf(bytes.size, start + size.coerceAtLeast(0))
                val frames = (end - start) / (2 * channels)
                val mono = ShortArray(frames) { i -> b.getShort(start + i * 2 * channels) }
                return resample(mono, rate, Earcons.RATE)
            }
            pos = start + size + (size and 1)
        }
        return null
    }

    fun resample(src: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || src.isEmpty()) return src
        val n = (src.size.toLong() * to / from).toInt()
        return ShortArray(n) { i ->
            val x = i.toDouble() * from / to
            val j = x.toInt().coerceAtMost(src.size - 1)
            val k = (j + 1).coerceAtMost(src.size - 1)
            val f = x - j
            (src[j] * (1 - f) + src[k] * f).toInt().toShort()
        }
    }
}

/** Raw s16le bytes -> samples. */
fun pcmFromBytes(bytes: ByteArray): ShortArray {
    val out = ShortArray(bytes.size / 2)
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
    return out
}
