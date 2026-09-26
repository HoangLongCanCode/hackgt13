package com.drivingassist.glass.perception

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.drivingassist.glass.FrameGeometry
import com.drivingassist.glass.VisionData
import com.drivingassist.glass.VisionSource
import com.ksr.copilot.perception.ClientHello
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * SIM mode: the tablet plays `<app external files>/sim/<videoId>.(mov|mp4)` with Media3 ExoPlayer
 * (shown full screen by [SimVideoBackground] instead of the camera) while the laptop analyses the
 * same clip ahead of the reported playback position.
 *
 * On the main thread (ExoPlayer's application thread): `reportPlayback` every ~100 ms and on every
 * play / pause / seek / loop, and ~30 Hz `bridge.resultForPts(player.currentPosition)` mapped with
 * the analysed video's size into [VisionData] (skipped while the activity is stopped). Playback
 * starts once the laptop is ready (max 5 s), unless the activity was paused meanwhile (then on resume).
 *
 * Orientation: the clip must display as 16:9 landscape, like the laptop analyses it. BDD .mov clips
 * are stored as 720x1280 frames with a -90 degree display matrix; ExoPlayer applies it on the
 * SurfaceView path, but check box alignment once on the device (PERCEPTION_INTEGRATION.md).
 *
 * Push a clip: `adb push b1ff4656-0435391e.mov /sdcard/Android/data/com.drivingassist.glass/files/sim/`.
 */
class SimVisionSource(override val runtime: PerceptionRuntime, context: Context) : VisionSource, BridgeBacked {

    val videoId: String = runtime.config.simVideoId
    val simDir: File? = context.getExternalFilesDir(null)?.let { File(it, "sim") }

    /** The clip, or null when it is not on the device (the chip says where to push it). */
    val videoFile: File? = simDir?.let { dir ->
        listOf("mov", "mp4", "MOV", "MP4", "mkv").map { File(dir, "$videoId.$it") }.firstOrNull { it.isFile }
    }

    /** Main thread only (created by the ViewModel factory on the main thread). */
    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE
        volume = 0f
        playWhenReady = false
        videoFile?.let {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(it)))
            prepare()
        }
    }

    private val _visionData = MutableStateFlow(VisionData(time = 0f))
    override val visionData: StateFlow<VisionData> = _visionData.asStateFlow()
    override val frameGeometry: FrameGeometry = FrameGeometry()

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastReportMs = 0L
    private var resumeOnForeground = false
    private var hostPaused = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) runtime.clearProblem("player:") // a transient player error is over
            report()
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = report()
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "player error", error)
            runtime.problem = "player: ${error.errorCodeName}"
        }
    }

    override fun start() {
        runtime.start(ClientHello.sim(runtime.clientId, videoId, runtime.device, runtime.navigationHint))
        if (videoFile == null) {
            val where = simDir?.absolutePath ?: "<external files>/sim"
            runtime.problem = "no clip $videoId.mov/.mp4 in $where"
            Log.e(TAG, "sim clip missing: push $videoId.mov to $where")
        }
        mainScope.launch {
            player.addListener(listener)
            report()
            // Like pressing play once the laptop is ready, so the first seconds already have results.
            withTimeoutOrNull(5_000) { runtime.bridge.link.first { it.serverReady } }
            if (videoFile != null) {
                // Paused while waiting (ON_PAUSE came first): start on resume instead of in the background.
                if (hostPaused) resumeOnForeground = true else player.playWhenReady = true
            }
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastReportMs >= 100) report()
                if (runtime.hostStarted) publish()
                delay(33)
            }
        }
    }

    override fun stop() {
        mainScope.cancel()
        runCatching {
            player.removeListener(listener)
            player.release()
        }
        runtime.close()
    }

    /** Host activity went to the background / came back ([SimVideoBackground] calls these). */
    fun onHostPaused() {
        hostPaused = true
        resumeOnForeground = player.playWhenReady
        player.pause()
    }

    fun onHostResumed() {
        hostPaused = false
        if (resumeOnForeground && videoFile != null) player.play()
    }

    private fun positionSeconds(): Double = player.currentPosition.coerceAtLeast(0L) / 1000.0

    private fun report() {
        lastReportMs = SystemClock.elapsedRealtime()
        runtime.bridge.reportPlayback(videoId, positionSeconds(), player.isPlaying, player.playbackParameters.speed.toDouble())
    }

    private fun publish() {
        val pts = positionSeconds()
        val g = frameGeometry
        val world = runtime.bridge.resultForPts(pts)
        _visionData.value = if (world == null) VisionData(time = pts.toFloat()) else VisionMapper.map(world, g.viewWidth, g.viewHeight, pts.toFloat())
    }

    private companion object {
        const val TAG = "SimVisionSource"
    }
}
