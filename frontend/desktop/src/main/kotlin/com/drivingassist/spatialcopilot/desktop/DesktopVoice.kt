package com.drivingassist.spatialcopilot.desktop

import com.drivingassist.copilot.bridge.LinkState
import com.drivingassist.copilot.context.Priority
import com.drivingassist.spatialcopilot.voice.CueCatalog
import com.drivingassist.spatialcopilot.voice.CuePolicy
import com.drivingassist.spatialcopilot.voice.CueRequest
import com.drivingassist.spatialcopilot.voice.Earcons
import com.drivingassist.spatialcopilot.voice.PolicyInput
import com.drivingassist.spatialcopilot.voice.VoiceArbiter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/** One cue decision, for the screen and cues.log. */
data class CueLog(val atPts: Double, val cueId: String, val text: String?, val outcome: String)

/**
 * The app's VoiceCoordinator loop on the desktop: Driving Context + route -> [CuePolicy] -> [VoiceArbiter], every
 * 50 ms, with the same inputs (the bridge's merged world, link, SIM pause, the speed gate). The audio is Windows speech
 * (System.Speech in a background PowerShell, one utterance at a time) after the catalog's earcon through Java Sound;
 * muted (or offscreen) the arbiter still runs, with each utterance's estimated length.
 */
class DesktopVoice(private val session: SimSession, private val catalog: CueCatalog, speak: Boolean) : AutoCloseable {
    private val policy = CuePolicy(catalog)
    private val arbiter = VoiceArbiter()
    private val finishedKeys = ConcurrentLinkedQueue<String>()
    private val log = ArrayDeque<CueLog>()
    private val allLog = ConcurrentLinkedQueue<CueLog>()
    private var job: Job? = null
    private val speech: WindowsSpeech? = if (speak) WindowsSpeech.startOrNull() else null

    /** Speak the cues (key V); the rules run either way. */
    @Volatile var muted: Boolean = speech == null

    /** "Windows speech", "muted", "no speech engine" (debug line). */
    val label: String
        get() = when {
            speech == null -> "cues shown, not spoken"
            muted -> "muted (V)"
            else -> "Windows speech"
        }

    /** The last few decisions, newest last. */
    fun recent(): List<CueLog> = synchronized(log) { log.toList() }

    /** Every decision so far (cues.log), and clears them. */
    fun drain(): List<CueLog> = generateSequence { allLog.poll() }.toList()

    fun start() {
        job = session.scope.launch {
            var wasBlocked = false
            while (isActive) {
                val now = System.nanoTime() / 1_000_000
                while (true) {
                    val k = finishedKeys.poll() ?: break
                    if (arbiter.playing?.key == k) arbiter.finished(now)
                }
                val link = session.link.value
                val connected = link.state == LinkState.CONNECTED
                val takenOver = link.takenOver
                val simPaused = !session.playing
                val blocked = simPaused
                if (blocked && !wasBlocked) {
                    arbiter.clear().forEach(policy::onDropped)
                    if (arbiter.playing != null) cut()
                }
                wasBlocked = blocked
                val route = session.route.value
                val input = PolicyInput(
                    nowMs = now,
                    context = session.context.value,
                    world = session.bridge.world.value,
                    linkConnected = connected,
                    takenOver = takenOver,
                    simPaused = simPaused,
                    hostVisible = true,
                    route = route,
                    routeDistanceMeters = session.routeDistanceNow(route),
                    live = false,
                    gpsAccuracyMeters = null,
                    speedGateMps = session.bridge.config.drivingContext.criticalMinEgoSpeedMps,
                )
                for (r in policy.step(input)) {
                    for (d in arbiter.offer(r, now)) handle(d, now)
                }
                // Audio is rendered on the fly: every request is ready.
                val decisions = arbiter.tick(
                    now,
                    ready = { true },
                    gateOpen = { !blocked && !(takenOver && it.cueId != CuePolicy.PAUSED) && policy.stillValid(it) },
                )
                for (d in decisions) handle(d, now)
                delay(STEP_MS)
            }
        }
    }

    private fun handle(d: VoiceArbiter.Decision, now: Long) {
        when (d) {
            is VoiceArbiter.Decision.Drop -> {
                policy.onDropped(d.request)
                note(d.request, "drop ${d.reason}")
            }
            is VoiceArbiter.Decision.Cut -> {
                cut()
                note(d.request, "cut")
            }
            is VoiceArbiter.Decision.Play -> {
                val r = d.request
                val lead = Earcons.lead(r.earcon, alone = r.text == null)
                val text = r.text
                if (lead == null && text == null) {
                    arbiter.finished(now)
                    policy.onDropped(r)
                    return
                }
                val ms = (lead?.let { it.size * 1000L / Earcons.RATE } ?: 0L) + (text?.let(::estimateMs) ?: 0L)
                arbiter.started(r, now, ms)
                note(r, "play")
                val s = speech
                if (s != null && !muted) s.say(lead, text, r.priority == Priority.CRITICAL_SAFETY) { finishedKeys.add(r.key) }
                else timer.schedule({ finishedKeys.add(r.key) }, ms, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun cut() {
        speech?.cut()
    }

    private fun note(r: CueRequest, outcome: String) {
        val entry = CueLog(session.positionSeconds, r.cueId, r.text, outcome)
        allLog.add(entry)
        synchronized(log) {
            log.addLast(entry)
            while (log.size > KEEP) log.removeFirst()
        }
    }

    override fun close() {
        job?.cancel()
        speech?.close()
        timer.shutdownNow()
    }

    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "voice-timer").apply { isDaemon = true } }

    companion object {
        const val STEP_MS = 50L
        const val KEEP = 6

        /** Spoken length of [text] at the default Windows voice rate (about 2.6 words a second) plus a short tail. */
        fun estimateMs(text: String): Long = 250L + CueCatalog.words(text) * 385L

        fun loadCatalog(file: File?): CueCatalog {
            val json = file?.readText() ?: DesktopVoice::class.java.getResource("/audio/audio_cues.v1.json")?.readText()
                ?: error("audio_cues.v1.json is not in the viewer (rebuild :desktop) and no --catalog was given")
            return CueCatalog.parse(json)
        }
    }
}

/**
 * Windows speech in a background PowerShell (System.Speech SpeechSynthesizer), fed one line per utterance over stdin
 * ("SAY <text>", "CUT"); it answers "done" when an utterance ended or was cut. Earcons play through Java Sound first.
 * One utterance at a time on a single worker thread; a CRITICAL cue cuts what is playing (the arbiter decides).
 */
class WindowsSpeech private constructor(private val process: Process) : AutoCloseable {
    private val input: BufferedWriter = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val done = LinkedBlockingQueue<String>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "voice-out").apply { isDaemon = true } }
    @Volatile private var cutRequested = false
    @Volatile private var line: SourceDataLine? = null

    init {
        Thread({
            runCatching { process.inputStream.bufferedReader().forEachLine { done.offer(it.trim()) } }
        }, "voice-in").apply { isDaemon = true }.start()
    }

    fun say(earcon: ShortArray?, text: String?, critical: Boolean, onDone: () -> Unit) {
        worker.execute {
            try {
                cutRequested = false
                if (earcon != null) playPcm(earcon)
                if (text != null && !cutRequested) {
                    done.clear()
                    send("SAY " + text.replace('\n', ' ').replace('\r', ' '))
                    done.poll(DesktopVoice.estimateMs(text) * 3 + 3_000, TimeUnit.MILLISECONDS)
                }
            } catch (_: Exception) {
            } finally {
                onDone()
            }
        }
    }

    fun cut() {
        cutRequested = true
        line?.let { runCatching { it.flush(); it.stop() } }
        runCatching { send("CUT") }
    }

    @Synchronized private fun send(s: String) {
        input.write(s)
        input.newLine()
        input.flush()
    }

    private fun playPcm(pcm: ShortArray) {
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            bytes[2 * i] = (pcm[i].toInt() and 0xFF).toByte()
            bytes[2 * i + 1] = (pcm[i].toInt() shr 8 and 0xFF).toByte()
        }
        val format = AudioFormat(Earcons.RATE.toFloat(), 16, 1, true, false)
        val l = runCatching { AudioSystem.getSourceDataLine(format) }.getOrNull() ?: return
        try {
            line = l
            l.open(format)
            l.start()
            l.write(bytes, 0, bytes.size)
            if (!cutRequested) l.drain()
        } finally {
            line = null
            runCatching { l.close() }
        }
    }

    override fun close() {
        worker.shutdownNow()
        runCatching { input.close() }
        process.destroy()
    }

    companion object {
        private val SCRIPT = """
            |Add-Type -AssemblyName System.Speech
            |${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
            |${'$'}s.SetOutputToDefaultAudioDevice()
            |${'$'}in = New-Object System.IO.StreamReader([Console]::OpenStandardInput(), [Text.Encoding]::UTF8)
            |${'$'}read = ${'$'}in.ReadLineAsync()
            |${'$'}p = ${'$'}null
            |[Console]::Out.WriteLine('ready'); [Console]::Out.Flush()
            |while (${'$'}true) {
            |  if (${'$'}read.IsCompleted) {
            |    ${'$'}l = ${'$'}read.Result
            |    if (${'$'}null -eq ${'$'}l) { break }
            |    if (${'$'}l -eq 'CUT') { ${'$'}s.SpeakAsyncCancelAll() }
            |    elseif (${'$'}l.StartsWith('SAY ')) { ${'$'}s.SpeakAsyncCancelAll(); ${'$'}p = ${'$'}s.SpeakAsync(${'$'}l.Substring(4)) }
            |    ${'$'}read = ${'$'}in.ReadLineAsync()
            |  }
            |  if (${'$'}null -ne ${'$'}p -and ${'$'}p.IsCompleted) { [Console]::Out.WriteLine('done'); [Console]::Out.Flush(); ${'$'}p = ${'$'}null }
            |  Start-Sleep -Milliseconds 15
            |}
        """.trimMargin()

        /** Null off Windows or when PowerShell / System.Speech does not start within a few seconds. */
        fun startOrNull(): WindowsSpeech? {
            if (!System.getProperty("os.name", "").lowercase().contains("win")) return null
            return runCatching {
                val encoded = Base64.getEncoder().encodeToString(SCRIPT.toByteArray(Charsets.UTF_16LE))
                val p = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start()
                val speech = WindowsSpeech(p)
                val first = speech.done.poll(8, TimeUnit.SECONDS)
                if (first != "ready") { speech.close(); null } else speech
            }.getOrNull()
        }
    }
}
