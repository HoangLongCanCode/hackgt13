package com.drivingassist.spatialcopilot.session

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.drivingassist.copilot.bridge.PerceptionBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * SIM mode: the tablet plays `<external files>/sim/<videoId>.(mov|mp4)` with Media3 ExoPlayer while the
 * laptop analyses the same clip ahead of the reported position (`client.playback` ~10 Hz and on every
 * play / pause / seek / loop). The AR layer asks [positionSeconds] every frame and draws
 * `bridge.resultForPts(position)`.
 *
 * Push a clip: `adb push b1ff4656-0435391e.mov /sdcard/Android/data/com.drivingassist.spatialcopilot/files/sim/`.
 * Main thread only (ExoPlayer's application thread).
 */
class SimPlayback(context: Context, private val bridge: PerceptionBridge, val videoId: String, private val onProblem: (String?) -> Unit) {

    /**
     * Created by the app itself: on Android 11+ a folder that `adb shell mkdir` creates under Android/data belongs to
     * the shell user and the app cannot read the clip in it. Push into the folder the app made.
     */
    val simDir: File? = context.getExternalFilesDir(null)?.let { File(it, "sim").apply { mkdirs() } }

    /** The clip, or null when it is not on the device (the UI says where to push it). */
    val videoFile: File? = simDir?.let { dir ->
        listOf("mov", "mp4", "MOV", "MP4", "mkv").map { File(dir, "$videoId.$it") }.firstOrNull { it.isFile }
    }

    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE
        volume = 0f
        playWhenReady = false
        videoFile?.let {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(it)))
            prepare()
        }
    }

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastReportMs = 0L
    private var resumeOnForeground = false
    private var hostPaused = false

    /** Player position, updated on the main thread; read by the render loop. */
    @Volatile var positionSeconds: Double = 0.0
        private set

    @Volatile var playing: Boolean = false
        private set

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playing = isPlaying
            if (isPlaying) onProblem(null)
            report()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = report()

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "player error", error)
            onProblem("Player: ${error.errorCodeName}")
        }
    }

    fun start() {
        if (videoFile == null) {
            val where = simDir?.absolutePath ?: "<external files>/sim"
            onProblem("No clip $videoId.mov in $where")
        }
        mainScope.launch {
            player.addListener(listener)
            report()
            // Press play once the laptop is ready, so the first seconds already have results.
            withTimeoutOrNull(5_000) { bridge.link.first { it.serverReady } }
            if (videoFile != null) {
                if (hostPaused) resumeOnForeground = true else player.playWhenReady = true
            }
            while (isActive) {
                if (hostPaused) { delay(200); continue }
                positionSeconds = player.currentPosition.coerceAtLeast(0L) / 1000.0
                if (SystemClock.elapsedRealtime() - lastReportMs >= 100) report()
                delay(16)
            }
        }
    }

    fun onHostPaused() {
        hostPaused = true
        resumeOnForeground = player.playWhenReady
        player.pause()
    }

    fun onHostResumed() {
        hostPaused = false
        if (resumeOnForeground && videoFile != null) player.play()
    }

    fun togglePlay() {
        if (videoFile == null) return
        player.playWhenReady = !player.playWhenReady
    }

    fun close() {
        mainScope.cancel()
        runCatching {
            player.removeListener(listener)
            player.release()
        }
    }

    private fun report() {
        lastReportMs = SystemClock.elapsedRealtime()
        positionSeconds = player.currentPosition.coerceAtLeast(0L) / 1000.0
        bridge.reportPlayback(videoId, positionSeconds, player.isPlaying, player.playbackParameters.speed.toDouble())
    }

    private companion object {
        const val TAG = "SimPlayback"
    }
}
