package com.drivingassist.spatialcopilot.desktop

import java.io.File
import java.util.Locale

/** Command line of the desktop viewer (see [USAGE]). */
data class Options(
    val videoId: String = "real_009",
    val url: String = "ws://127.0.0.1:8766/perception",
    /** The clip to show; null = looked up by [videoId] like the server does ([ClipFinder]). */
    val clip: File? = null,
    /** Python with OpenCV for [FramePipe] (the perception venv). */
    val python: String? = null,
    val debug: Boolean = false,
    /** Speak the cues (window mode only; the cue rules run either way and are shown in the debug view). */
    val voice: Boolean = true,
    val catalog: File? = null,
    val width: Int = 1280,
    val height: Int = 800,
    /** Media time playback starts at. */
    val start: Double = 0.0,
    /** Hold TOO CLOSE at CLOSE below the speed gate (the app's settings switch, on by default). */
    val speedGate: Boolean = true,
    /** Frames sent by the pipe are this wide (the clip is 1920x1080; the window rarely needs more). */
    val videoWidth: Int = 1280,
    /** Offscreen: write PNGs here instead of opening a window. */
    val renderOut: File? = null,
    val fps: Double = 10.0,
    val from: Double? = null,
    val to: Double? = null,
    /** Offscreen: single PNGs at these media times (each played in real time from [leadIn] seconds before). */
    val snapshots: List<Double> = emptyList(),
    val leadIn: Double = 4.0,
    /** Offscreen: draw the media time in a corner of every PNG (viewer-only, not on the tablet). */
    val stamp: Boolean = true,
    /** Snapshots go here when [renderOut] is not given. */
    val snapshotDir: File? = null,
    /** Give up waiting for the laptop after this long (offscreen). */
    val readyTimeoutS: Double = 60.0,
) {
    val offscreen: Boolean get() = renderOut != null || snapshots.isNotEmpty()

    companion object {
        val USAGE = """
            |desktop-sim: the tablet's SIM mode on this PC (same AR / route / voice code as the app).
            |
            |  desktop-sim --video real_009 [--url ws://127.0.0.1:8766/perception] [--debug] [--no-voice]
            |  desktop-sim --video real_009 --render-out DIR [--fps 10] [--from S] [--to S]     (PNGs, no window)
            |  desktop-sim --video real_009 --snapshots 5,25,70 [--snapshot-dir DIR] [--lead-in 4]
            |
            |  --video ID        clip id the server knows (a sim_videos/<ID>/video.mp4 folder or a clip stem)
            |  --url URL         the perception server (start it with --mode sim; scripts/desktop_sim.ps1 does)
            |  --clip FILE       the clip to show (default: looked up by id under perception_engine/data)
            |  --python EXE      Python with OpenCV for the frame pipe (default: perception_engine/.venv, else python)
            |  --debug           start in the debug view (key D toggles)
            |  --no-voice        do not speak (cues still run and show in the debug view; key V toggles)
            |  --size WxH        window / PNG size (default 1280x800, 16:10 like the Tab S9)
            |  --start S         media time to start at
            |  --no-speed-gate   do not hold TOO CLOSE at CLOSE while stopped
            |  --video-width PX  width of the decoded frames (default 1280)
            |  --render-out DIR  offscreen: frame_00000.png ... + frames.csv + cues.log, real time from --from to --to
            |  --fps N           offscreen frames per media second (default 10)
            |  --snapshots LIST  offscreen: one PNG at each media time (comma separated seconds)
            |  --no-stamp        offscreen: no media-time stamp in the corner
            |  --catalog FILE    audio_cues.v1.json (default: the copy built into the viewer)
            |
            |Keys: Space pause/resume, Left/Right seek -/+5 s, N / P next / previous clip, O pick a clip, D debug view,
            |      V voice on/off, S screenshot, Esc quit.
        """.trimMargin()

        /** Parses [args]; throws [IllegalArgumentException] with a readable message on a bad one. */
        fun parse(args: Array<String>): Options {
            var o = Options()
            var i = 0
            fun value(name: String): String {
                require(i + 1 < args.size) { "$name needs a value" }
                i++
                return args[i]
            }
            fun number(name: String): Double = value(name).toDoubleOrNull() ?: throw IllegalArgumentException("$name needs a number")
            while (i < args.size) {
                when (val a = args[i]) {
                    "--video", "--video-id" -> o = o.copy(videoId = value(a))
                    "--url" -> o = o.copy(url = value(a))
                    "--port" -> o = o.copy(url = "ws://127.0.0.1:${value(a).toIntOrNull() ?: throw IllegalArgumentException("--port needs a number")}/perception")
                    "--clip" -> o = o.copy(clip = File(value(a)))
                    "--python" -> o = o.copy(python = value(a))
                    "--debug" -> o = o.copy(debug = true)
                    "--no-voice" -> o = o.copy(voice = false)
                    "--voice" -> o = o.copy(voice = true)
                    "--catalog" -> o = o.copy(catalog = File(value(a)))
                    "--size" -> {
                        val (w, h) = parseSize(value(a))
                        o = o.copy(width = w, height = h)
                    }
                    "--start" -> o = o.copy(start = number(a).coerceAtLeast(0.0))
                    "--no-speed-gate" -> o = o.copy(speedGate = false)
                    "--video-width" -> o = o.copy(videoWidth = number(a).toInt().coerceIn(0, 3840))
                    "--render-out" -> o = o.copy(renderOut = File(value(a)))
                    "--fps" -> o = o.copy(fps = number(a).also { require(it > 0.0 && it <= 60.0) { "--fps must be in (0, 60]" } })
                    "--from" -> o = o.copy(from = number(a).coerceAtLeast(0.0))
                    "--to" -> o = o.copy(to = number(a))
                    "--snapshots" -> o = o.copy(snapshots = parseTimes(value(a)))
                    "--snapshot-dir" -> o = o.copy(snapshotDir = File(value(a)))
                    "--lead-in" -> o = o.copy(leadIn = number(a).coerceIn(0.0, 30.0))
                    "--no-stamp" -> o = o.copy(stamp = false)
                    "--ready-timeout" -> o = o.copy(readyTimeoutS = number(a))
                    "-h", "--help" -> throw HelpRequested()
                    else -> throw IllegalArgumentException("unknown option $a")
                }
                i++
            }
            val from = o.from
            val to = o.to
            require(from == null || to == null || to > from) { "--to must be after --from" }
            require(o.url.startsWith("ws://") || o.url.startsWith("wss://")) { "--url must be a ws:// or wss:// URL" }
            return o
        }

        fun parseSize(s: String): Pair<Int, Int> {
            val m = Regex("""^(\d{3,5})[xX](\d{3,5})$""").matchEntire(s.trim()) ?: throw IllegalArgumentException("--size needs WxH, e.g. 1280x800")
            return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }

        /** "5,25,70" or "5 25 70" -> sorted media times. */
        fun parseTimes(s: String): List<Double> = s.split(',', ' ', ';').filter { it.isNotBlank() }.map {
            it.trim().toDoubleOrNull()?.takeIf { t -> t >= 0.0 } ?: throw IllegalArgumentException("--snapshots needs seconds, e.g. 5,25,70")
        }.sorted()

        fun fmt(x: Double, digits: Int = 1): String = String.format(Locale.US, "%.${digits}f", x)
    }
}

class HelpRequested : RuntimeException()
