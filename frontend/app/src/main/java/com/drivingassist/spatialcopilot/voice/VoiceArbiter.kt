package com.drivingassist.spatialcopilot.voice

import com.drivingassist.copilot.context.Priority

/**
 * One utterance at a time (AUDIO_CUE_RULES.md section 7, subset). Pure: the caller passes the clock.
 *
 * - Dedupe: a key already playing is dropped; a key already queued has its payload replaced (older time kept).
 * - Queue: at most [queueMax], by priority then age; on overflow the lowest is evicted, never a CRITICAL.
 * - Preemption: CRITICAL cuts any non-CRITICAL; TRAFFIC cuts UPCOMING / GENERAL / SOCIAL, and IMMEDIATE with
 *   more than 1 s left; IMMEDIATE cuts GENERAL / SOCIAL, and the UPCOMING prompt or lane cue of its own event key.
 *   Earcon-only never cuts speech (a CRITICAL one does). A cut cue is not re-queued.
 * - Start: nothing playing, [minGapMs] since the last end (CRITICAL ignores it, TRAFFIC waits 500 ms),
 *   TTL not passed (a CRITICAL waiting behind another CRITICAL keeps its TTL from that one's end), audio ready.
 */
class VoiceArbiter(private val queueMax: Int = 4, private val minGapMs: Long = 700, private val trafficAfterMs: Long = 500) {

    sealed interface Decision {
        data class Play(val request: CueRequest) : Decision
        data class Cut(val request: CueRequest) : Decision
        data class Drop(val request: CueRequest, val reason: String) : Decision
    }

    private val queue = ArrayList<CueRequest>()
    var playing: CueRequest? = null
        private set
    private var playingEndsMs = 0L
    private var lastEndMs = Long.MIN_VALUE / 2
    private var criticalBlockEndMs: Long? = null

    val queued: List<CueRequest> get() = queue.toList()

    fun offer(r: CueRequest, now: Long): List<Decision> {
        val out = ArrayList<Decision>()
        val p = playing
        if (p != null && p.key == r.key) return listOf(Decision.Drop(r, "alreadyPlaying"))
        val i = queue.indexOfFirst { it.key == r.key }
        if (i >= 0) {
            queue[i] = r.copy(createdMs = queue[i].createdMs)
        } else {
            queue += r
            sort()
            while (queue.size > queueMax) {
                val victim = queue.lastOrNull { it.priority != Priority.CRITICAL_SAFETY } ?: break
                queue.remove(victim)
                out += Decision.Drop(victim, "evicted")
            }
        }
        if (p != null && cuts(r, p, now)) {
            out += Decision.Cut(p)
            finished(now)
        }
        return out
    }

    /**
     * Called every step. [ready] says whether the request's audio is available now (or its readiness deadline
     * passed and the caller will play a fallback). Returns what to start or drop.
     */
    fun tick(now: Long, ready: (CueRequest) -> Boolean, gateOpen: (CueRequest) -> Boolean): List<Decision> {
        val out = ArrayList<Decision>()
        val it = queue.iterator()
        while (it.hasNext()) {
            val r = it.next()
            val ttlStart = if (r.priority == Priority.CRITICAL_SAFETY) maxOf(r.createdMs, criticalBlockEndMs ?: Long.MIN_VALUE) else r.createdMs
            val waitingBehindCritical = r.priority == Priority.CRITICAL_SAFETY && playing?.priority == Priority.CRITICAL_SAFETY
            if (!waitingBehindCritical && now - ttlStart > r.ttlMs) { it.remove(); out += Decision.Drop(r, "ttl"); continue }
            if (!gateOpen(r)) { it.remove(); out += Decision.Drop(r, "gate") }
        }
        if (playing != null) return out
        val next = queue.firstOrNull() ?: return out
        val gap = when (next.priority) {
            Priority.CRITICAL_SAFETY -> 0L
            Priority.TRAFFIC_ALERT -> trafficAfterMs
            else -> minGapMs
        }
        if (now - lastEndMs < gap) return out
        if (!ready(next)) return out
        queue.remove(next)
        playing = next
        out += Decision.Play(next)
        return out
    }

    /** The bus started [r] and it will last [durationMs]. */
    fun started(r: CueRequest, now: Long, durationMs: Long) {
        if (playing?.key == r.key) playingEndsMs = now + durationMs
    }

    /** The playing utterance ended or was cut. */
    fun finished(now: Long) {
        if (playing?.priority == Priority.CRITICAL_SAFETY) criticalBlockEndMs = now
        playing = null
        lastEndMs = now
    }

    /** Drop everything (session change, host hidden, voice off). Returns the dropped requests. */
    fun clear(): List<CueRequest> {
        val dropped = queue.toList()
        queue.clear()
        return dropped
    }

    private fun sort() = queue.sortWith(compareBy<CueRequest> { it.priority.rank }.thenBy { it.createdMs })

    private fun cuts(new: CueRequest, current: CueRequest, now: Long): Boolean {
        if (new.text == null && new.priority != Priority.CRITICAL_SAFETY) return false // earcon-only never cuts speech
        return when (new.priority) {
            Priority.CRITICAL_SAFETY -> current.priority != Priority.CRITICAL_SAFETY
            Priority.TRAFFIC_ALERT -> current.priority in LOW ||
                (current.priority == Priority.IMMEDIATE_NAVIGATION && playingEndsMs - now > 1_000)
            Priority.IMMEDIATE_NAVIGATION -> current.priority == Priority.GENERAL_INFORMATION || current.priority == Priority.SOCIAL ||
                (current.priority == Priority.UPCOMING_NAVIGATION && new.eventKey != null && current.eventKey == new.eventKey)
            else -> false
        }
    }

    private companion object {
        val LOW = setOf(Priority.UPCOMING_NAVIGATION, Priority.GENERAL_INFORMATION, Priority.SOCIAL)
    }
}
