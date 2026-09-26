package com.drivingassist.spatialcopilot.voice

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.drivingassist.copilot.bridge.LinkState
import com.drivingassist.copilot.context.Priority
import com.drivingassist.copilot.context.WorldSnapshot
import com.drivingassist.spatialcopilot.session.AppSettings
import com.drivingassist.spatialcopilot.session.CopilotSession
import com.drivingassist.spatialcopilot.session.SourceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Voice path health for the status chip. Visual alerts never depend on it. */
data class VoiceState(val mode: Mode, val detail: String? = null) {
    enum class Mode { OFF, STARTING, ELEVEN, LOCAL, EARCON_ONLY }

    val label: String
        get() = when (mode) {
            Mode.OFF -> "off"
            Mode.STARTING -> "starting"
            Mode.ELEVEN -> "ElevenLabs via laptop"
            Mode.LOCAL -> "VOICE LOCAL (Android TTS)" + (detail?.let { ": $it" } ?: "")
            Mode.EARCON_ONLY -> "VOICE: tones only" + (detail?.let { ": $it" } ?: "")
        }
}

/**
 * Driving Context + route -> [CuePolicy] -> [VoiceArbiter] -> [VoiceBus], every 50 ms.
 *
 * Audio sources, in order: the RAM voice pack (the catalog's fixed phrases, fetched from the laptop's
 * `/tts` proxy at session start, else rendered by Android TTS), then for other nav sentences the proxy
 * with the catalog's readiness deadline, then Android TTS. CRITICAL and TRAFFIC cues play only from RAM;
 * with no clip they play their earcon alone. The ElevenLabs key stays on the laptop.
 */
class VoiceCoordinator(context: Context, private val parent: CoroutineScope) {
    private val app = context.applicationContext
    private val catalog: CueCatalog? = runCatching {
        CueCatalog.parse(app.assets.open(CueCatalog.ASSET).bufferedReader().use { it.readText() })
    }.onFailure { Log.e(TAG, "audio catalog missing: voice off", it) }.getOrNull()

    private val _state = MutableStateFlow(VoiceState(VoiceState.Mode.STARTING))
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    /** Set by the activity (ON_START / ON_STOP): nothing plays in the background, and the output is paused. */
    @Volatile var hostVisible: Boolean = true
        set(value) {
            field = value
            bus?.setSuspended(!value)
        }

    private var job: Job? = null
    private var scope: CoroutineScope? = null
    private var bus: VoiceBus? = null
    private var proxy: ProxyTts? = null
    private var native: NativeTts? = null
    private val pack = ConcurrentHashMap<String, ShortArray>()
    private val pendingClips = ConcurrentHashMap<String, ShortArray>()
    private val fetching = ConcurrentHashMap.newKeySet<String>()

    /** Keys whose clip the bus finished (written on the bus thread, applied to the arbiter on the loop). */
    private val finishedKeys = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /** (Re)binds to [session]: new policy / arbiter state, new voice pack for its server. */
    fun attach(session: CopilotSession, settings: AppSettings) {
        detach()
        val cat = catalog
        if (cat == null || !settings.voice) {
            _state.value = VoiceState(VoiceState.Mode.OFF, if (cat == null) "catalog missing" else null)
            return
        }
        val onError = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "voice failed; visuals are unaffected", e)
            _state.value = VoiceState(VoiceState.Mode.EARCON_ONLY, "voice error: ${e.message ?: e.javaClass.simpleName}")
        }
        val s = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.Default + onError)
        scope = s
        val b = VoiceBus(app).also { it.setSuspended(!hostVisible); it.start() }
        bus = b
        val nat = NativeTts(app)
        native = nat
        val px = if (session.bridge != null) ProxyTts(settings.serverUrl) else null
        proxy = px
        _state.value = VoiceState(VoiceState.Mode.STARTING)
        s.launch { warmPack(cat, px, nat) }
        job = s.launch { loop(session, cat, CuePolicy(cat), VoiceArbiter()) }
    }

    fun close() = detach()

    private fun detach() {
        job?.cancel(); job = null
        bus?.stop(); bus = null
        proxy?.close(); proxy = null
        native?.close(); native = null
        scope?.cancel(); scope = null
        pack.clear(); pendingClips.clear(); fetching.clear(); finishedKeys.clear()
    }

    private suspend fun warmPack(cat: CueCatalog, px: ProxyTts?, nat: NativeTts) {
        val eleven = px?.probe() == true
        for (text in cat.fixedPhrases) {
            val profile = profileOf(cat, text)
            val clip = if (eleven) withContext(Dispatchers.IO) { px!!.fetch(text, profile) } else null
            if (clip != null) pack[key(profile, text)] = clip
        }
        val missing = cat.fixedPhrases.filter { pack[key(profileOf(cat, it), it)] == null }
        var nativeOk = 0
        for (text in missing) {
            nat.render(text)?.let { pack[key(profileOf(cat, text), text)] = it; nativeOk++ }
        }
        _state.value = when {
            eleven && missing.isEmpty() -> VoiceState(VoiceState.Mode.ELEVEN)
            eleven || nativeOk > 0 -> VoiceState(VoiceState.Mode.LOCAL, px?.disabledReason ?: if (eleven) "${missing.size} phrases local" else "no laptop TTS in DEMO")
            else -> VoiceState(VoiceState.Mode.EARCON_ONLY, "no ElevenLabs and no Android TTS engine")
        }
        Log.i(TAG, "voice pack: ${pack.size}/${cat.fixedPhrases.size} phrases, ${_state.value.label}, native engine ${nat.engineName}")
    }

    private suspend fun loop(session: CopilotSession, cat: CueCatalog, policy: CuePolicy, arbiter: VoiceArbiter) {
        val bus = bus ?: return
        var wasBlocked = false
        while (currentScopeActive()) {
            val now = SystemClock.elapsedRealtime()
            while (true) {
                val k = finishedKeys.poll() ?: break
                if (arbiter.playing?.key == k) arbiter.finished(now)
            }
            val link = session.link?.value
            val connected = session.bridge == null || link?.state == LinkState.CONNECTED
            val takenOver = link?.takenOver == true
            val simPaused = session.settings.mode == SourceMode.SIM && session.sim?.playing != true
            val blocked = !hostVisible || simPaused
            if (blocked && !wasBlocked) {
                // Hidden or paused: cut what is playing, drop the queue (re-arming what it had used up).
                arbiter.clear().forEach(policy::onDropped)
                if (arbiter.playing != null) bus.cut()
            }
            wasBlocked = blocked
            val route = session.route.value
            val input = PolicyInput(
                nowMs = now,
                context = session.context.value,
                world = session.bridge?.world?.value ?: WorldSnapshot.EMPTY,
                linkConnected = connected,
                takenOver = takenOver,
                simPaused = simPaused,
                hostVisible = hostVisible,
                route = route,
                routeDistanceMeters = session.routeDistanceNow(route),
                live = session.settings.mode == SourceMode.LIVE,
                gpsAccuracyMeters = session.gps.value?.accuracyMeters,
            )
            for (r in policy.step(input)) {
                prepare(r)
                for (d in arbiter.offer(r, now)) handle(d, arbiter, policy, bus, now)
            }
            val decisions = arbiter.tick(now, ready = { isReady(it, now) }, gateOpen = { !blocked && !(takenOver && it.cueId != CuePolicy.PAUSED) })
            for (d in decisions) handle(d, arbiter, policy, bus, now)
            delay(STEP_MS)
        }
    }

    private suspend fun currentScopeActive(): Boolean = kotlin.coroutines.coroutineContext.isActive

    private fun handle(d: VoiceArbiter.Decision, arbiter: VoiceArbiter, policy: CuePolicy, bus: VoiceBus, now: Long) {
        when (d) {
            is VoiceArbiter.Decision.Drop -> { policy.onDropped(d.request); Log.i(TAG, "drop ${d.request.cueId} (${d.reason})") }
            is VoiceArbiter.Decision.Cut -> { bus.cut(); Log.i(TAG, "cut ${d.request.cueId}") }
            is VoiceArbiter.Decision.Play -> {
                val r = d.request
                val speech = r.text?.let { pack[key(r.profile, it)] ?: pendingClips.remove(key(r.profile, it)) }
                val lead = Earcons.lead(r.earcon, alone = speech == null)
                val pcm = when {
                    speech != null && lead != null -> Earcons.concat(lead, speech)
                    speech != null -> speech
                    lead != null -> lead
                    else -> { arbiter.finished(now); policy.onDropped(r); Log.i(TAG, "nothing to play for ${r.cueId}"); return }
                }
                arbiter.started(r, now, pcm.size * 1000L / Earcons.RATE)
                Log.i(TAG, "play ${r.cueId} src=${if (speech == null) "earcon" else "clip"} \"${r.text ?: ""}\"")
                bus.play(pcm, r.priority == Priority.CRITICAL_SAFETY) { finishedKeys.add(r.key) }
            }
        }
    }

    /** Starts fetching audio for a nav sentence that is not in the pack (proxy, then Android TTS). */
    private fun prepare(r: CueRequest) {
        val text = r.text ?: return
        val k = key(r.profile, text)
        if (pack.containsKey(k) || pendingClips.containsKey(k) || !fetching.add(k)) return
        if (r.priority == Priority.CRITICAL_SAFETY || r.priority == Priority.TRAFFIC_ALERT) { fetching.remove(k); return } // RAM only
        val px = proxy
        val nat = native
        scope?.launch {
            val clip = (if (px != null) withContext(Dispatchers.IO) { px.fetch(text, r.profile) } else null) ?: nat?.render(text)
            if (clip != null) pendingClips[k] = clip
            fetching.remove(k)
        }
    }

    /** CRITICAL / TRAFFIC are always ready (clip from RAM or earcon alone); others once audio arrived or the deadline passed. */
    private fun isReady(r: CueRequest, now: Long): Boolean {
        if (r.priority == Priority.CRITICAL_SAFETY || r.priority == Priority.TRAFFIC_ALERT) return true
        val text = r.text ?: return true
        val k = key(r.profile, text)
        if (pack.containsKey(k) || pendingClips.containsKey(k)) return true
        // Past the deadline and still fetching: keep waiting (until the TTL drops it); a failed fetch gives up.
        return !fetching.contains(k) && now - r.createdMs > deadline(r.priority)
    }

    private fun deadline(p: Priority): Long = when (p) {
        Priority.IMMEDIATE_NAVIGATION -> 400
        Priority.UPCOMING_NAVIGATION -> 800
        else -> 1_000
    }

    private fun profileOf(cat: CueCatalog, text: String): String =
        cat.cues.values.firstOrNull { it.text == text }?.voiceProfile ?: "nav"

    private fun key(profile: String, text: String) = "$profile|$text"

    private companion object {
        const val TAG = "Voice"
        const val STEP_MS = 50L
    }
}
