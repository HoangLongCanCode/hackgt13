package com.ksr.copilot.cli

import com.ksr.copilot.perception.Protocol
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """bridge-cli <command> [options]      KSR fake tablet: drives the SAME PerceptionBridge as the Android app

Commands (talk to the laptop server, exactly like the tablet app):
  live  --frames DIR        uplink pre-extracted JPEG frames as the tablet camera would
                            (captureTimeNs = System.nanoTime(), dropped when no credit)
                            frames: perception_engine/scripts/extract_frames.py <clip> --fps 15
  sim   --video-id ID       simulated player clock from --start at real time, reportPlayback at 10 Hz,
                            prints resultForPts(currentPts) and how early the results arrived
  watch                     server 'video' mode (the laptop plays a clip on its own clock)

Common options:
  --url URL             default ${Protocol.USB_URL}
  --client-id ID        default fake-tablet-jvm
  --seconds N           exit after N wall-clock seconds
  --interval MS         print interval (default 500)
  --dump-snapshot       print WorldSnapshot + DrivingContext + navigation + LinkStatus JSON once
  --dump-at T           media time (s) for the dump (default: first snapshot >= 3 s with objects; else at exit)
live:    --fps F (default meta.json fps or 15)  --loop  --max-frames N  --rotation DEG (0)
         --focal-px F (client.hello camera.focalPx; default null = server estimate)  --mount-height M (1.25)
sim:     --rate R (1.0)  --start S (0)  --duration S (player pauses at S)
navigation (phase1 packets relayed by the laptop are always printed):
         --trip-states FILE    replay a phase1 trip_state.jsonl as client.trip_state at real time (live nav)
         --trip-rebase         shift the replayed timestampMs to now (default: keep the recorded times)
         --nav-hint MODE       client.hello navigation hint: sim | live | off (default: the command's mode, watch = off)
         --nav-stub            ignore packets for the Driving Context; use a stub exit instead:
                               --nav-distance M (400) --nav-lane N (3) --nav-speed MPS (12) --nav-label TEXT ("Exit 23B")"""

internal data class Options(
    val command: String = "watch",
    val url: String = Protocol.USB_URL,
    val clientId: String = "fake-tablet-jvm",
    val seconds: Double? = null,
    val intervalMs: Long = 500,
    val dump: Boolean = false,
    val dumpAt: Double? = null,
    // live
    val frames: File? = null,
    val fps: Double? = null,
    val loop: Boolean = false,
    val maxFrames: Int? = null,
    val rotation: Int = 0,
    val focalPx: Double? = null,
    val mountHeight: Double? = 1.25,
    // sim
    val videoId: String? = null,
    val rate: Double = 1.0,
    val start: Double = 0.0,
    val duration: Double? = null,
    // navigation
    val tripStates: File? = null,
    val tripRebase: Boolean = false,
    val navHint: String? = null,
    val navStub: Boolean = false,
    val navDistance: Double = 400.0,
    val navLane: Int = 3,
    val navSpeed: Double = 12.0,
    val navLabel: String = "Exit 23B",
)

private val COMMANDS = setOf("live", "sim", "watch")

private fun parse(args: Array<String>): Options {
    var o = Options()
    var i = 0
    fun value(): String = args.getOrNull(++i) ?: run { System.err.println(USAGE); exitProcess(2) }
    while (i < args.size) {
        when (val a = args[i]) {
            in COMMANDS -> o = o.copy(command = a)
            "--url" -> o = o.copy(url = value())
            "--client-id" -> o = o.copy(clientId = value())
            "--seconds" -> o = o.copy(seconds = value().toDouble())
            "--interval" -> o = o.copy(intervalMs = value().toLong())
            "--dump-snapshot" -> o = o.copy(dump = true)
            "--dump-at" -> o = o.copy(dumpAt = value().toDouble())
            "--frames" -> o = o.copy(frames = File(value()))
            "--fps" -> o = o.copy(fps = value().toDouble())
            "--loop" -> o = o.copy(loop = true)
            "--max-frames" -> o = o.copy(maxFrames = value().toInt())
            "--rotation" -> o = o.copy(rotation = value().toInt())
            "--focal-px" -> o = o.copy(focalPx = value().toDouble())
            "--mount-height" -> o = o.copy(mountHeight = value().toDouble())
            "--video-id" -> o = o.copy(videoId = value())
            "--rate" -> o = o.copy(rate = value().toDouble())
            "--start" -> o = o.copy(start = value().toDouble())
            "--duration" -> o = o.copy(duration = value().toDouble())
            "--trip-states" -> o = o.copy(tripStates = File(value()))
            "--trip-rebase" -> o = o.copy(tripRebase = true)
            "--nav-hint" -> o = o.copy(navHint = value().lowercase())
            "--nav-stub" -> o = o.copy(navStub = true)
            "--nav-distance" -> o = o.copy(navDistance = value().toDouble())
            "--nav-lane" -> o = o.copy(navLane = value().toInt())
            "--nav-speed" -> o = o.copy(navSpeed = value().toDouble())
            "--nav-label" -> o = o.copy(navLabel = value())
            "-h", "--help", "help" -> { println(USAGE); exitProcess(0) }
            else -> if (a.startsWith("ws://") || a.startsWith("wss://")) o = o.copy(url = a) else {
                System.err.println("unknown argument: $a\n$USAGE"); exitProcess(2)
            }
        }
        i++
    }
    if (o.navHint != null && o.navHint !in setOf("sim", "live", "off")) { System.err.println("--nav-hint must be sim, live or off\n$USAGE"); exitProcess(2) }
    if (o.tripStates != null && !o.tripStates.isFile) { System.err.println("--trip-states: no such file ${o.tripStates}"); exitProcess(2) }
    return o
}

fun main(args: Array<String>) {
    val o = parse(args)
    when (o.command) {
        "live" -> FakeTablet.live(o)
        "sim" -> FakeTablet.sim(o)
        else -> FakeTablet.watch(o)
    }
    exitProcess(0)
}
