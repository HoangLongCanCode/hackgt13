package com.drivingassist.spatialcopilot.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log

/**
 * The one audio output for cues: a streaming AudioTrack (16-bit mono 24 kHz,
 * USAGE_ASSISTANCE_NAVIGATION_GUIDANCE / CONTENT_TYPE_SPEECH) fed by one writer thread in 20 ms blocks.
 * Between utterances it writes silence, so the output (and Bluetooth) never goes to standby and a clip
 * starts within one block. A cut is pause + flush between blocks. Audio focus: GAIN_TRANSIENT for
 * CRITICAL, MAY_DUCK otherwise, abandoned 500 ms after the bus goes idle; CRITICAL and TRAFFIC play even
 * if focus is refused.
 */
class VoiceBus(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private class Clip(val pcm: ShortArray, val critical: Boolean, val onDone: () -> Unit) {
        var pos = 0
    }

    private val lock = Object()
    private var clip: Clip? = null
    private var cutRequested = false
    @Volatile private var running = false
    /** App in the background: with nothing to play, the track is paused and the writer sleeps (no wakelock). */
    @Volatile private var suspended = false
    private var thread: Thread? = null
    @Volatile private var focus: AudioFocusRequest? = null
    private var idleSinceMs = 0L

    val isPlaying: Boolean get() = synchronized(lock) { clip != null }

    fun start() {
        if (running) return
        running = true
        thread = Thread(::writerLoop, "voice-bus").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop() {
        running = false
        synchronized(lock) { (lock as Object).notifyAll() }
        thread?.join(500)
        thread = null
        synchronized(lock) { clip?.onDone?.invoke(); clip = null }
        abandonFocus()
    }

    /** Plays [pcm] now (the arbiter already decided). [onDone] runs on the writer thread when it has played out or was cut. */
    fun play(pcm: ShortArray, critical: Boolean, onDone: () -> Unit) {
        if (!running) { onDone(); return } // no writer (AudioTrack failed or bus stopped): never hold focus for nothing
        requestFocus(critical)
        synchronized(lock) {
            clip?.onDone?.invoke()
            clip = Clip(pcm, critical, onDone)
            cutRequested = false
            (lock as Object).notifyAll()
        }
    }

    fun setSuspended(value: Boolean) {
        suspended = value
        synchronized(lock) { (lock as Object).notifyAll() }
    }

    fun cut() {
        synchronized(lock) { if (clip != null) cutRequested = true }
    }

    private fun writerLoop() {
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(Earcons.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(Earcons.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), Earcons.RATE / 5 * 2))
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "no AudioTrack: voice off", e)
            running = false
            return
        }
        val block = ShortArray(BLOCK)
        val silence = ShortArray(BLOCK)
        try {
            track.play()
            while (running) {
                var done: (() -> Unit)? = null
                var toWrite: ShortArray = silence
                synchronized(lock) {
                    if (suspended && clip == null && running) {
                        track.pause()
                        abandonFocus()
                        while (suspended && clip == null && running) (lock as Object).wait(1_000)
                        if (running) track.play()
                    }
                    val c = clip
                    if (c != null && cutRequested) {
                        track.pause(); track.flush(); track.play()
                        done = c.onDone; clip = null; cutRequested = false
                    } else if (c != null) {
                        val n = minOf(BLOCK, c.pcm.size - c.pos)
                        c.pcm.copyInto(block, 0, c.pos, c.pos + n)
                        if (n < BLOCK) block.fill(0, n, BLOCK)
                        c.pos += n
                        toWrite = block
                        if (c.pos >= c.pcm.size) { done = c.onDone; clip = null }
                    }
                }
                if (!running) break
                val written = track.write(toWrite, 0, BLOCK)
                if (written < 0) {
                    Log.e(TAG, "AudioTrack write failed ($written): voice bus stopped")
                    running = false
                    done?.invoke()
                    synchronized(lock) { clip?.onDone?.invoke(); clip = null }
                    break
                }
                done?.let {
                    it()
                    idleSinceMs = SystemClock.elapsedRealtime()
                }
                if (focus != null && !isPlaying && SystemClock.elapsedRealtime() - idleSinceMs > 500) abandonFocus()
            }
        } catch (e: Exception) {
            Log.e(TAG, "voice bus stopped", e)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun requestFocus(critical: Boolean) {
        val am = audio ?: return
        abandonFocus()
        val req = AudioFocusRequest.Builder(if (critical) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        val granted = runCatching { am.requestAudioFocus(req) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) Log.i(TAG, "focus-denied (playing anyway)")
        focus = req
    }

    private fun abandonFocus() {
        val f = focus ?: return
        focus = null
        runCatching { audio?.abandonAudioFocusRequest(f) }
    }

    private companion object {
        const val TAG = "VoiceBus"
        const val BLOCK = Earcons.RATE / 50 // 20 ms
    }
}
