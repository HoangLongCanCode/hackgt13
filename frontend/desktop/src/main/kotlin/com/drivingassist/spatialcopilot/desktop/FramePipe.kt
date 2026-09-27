package com.drivingassist.spatialcopilot.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** What `frame_pipe.py` says first: the size of the frames it sends and the clip's timing. */
data class ClipHeader(val width: Int, val height: Int, val fps: Double, val frameCount: Int, val durationS: Double, val start: Double)

/** One record of the frame pipe (frontend/desktop/frame_pipe.py). */
sealed interface PipeRecord {
    data class Header(val header: ClipHeader) : PipeRecord
    class Frame(val pts: Double, val jpeg: ByteArray) : PipeRecord
    data object End : PipeRecord
}

class FramePipeException(message: String) : RuntimeException(message)

/**
 * Reads frame-pipe records: a 4-byte tag, a big-endian uint32 payload length, the payload.
 * HDR1 = JSON header, FRM1 = big-endian float64 pts + JPEG, END1 = empty. [next] returns null at a clean end of stream
 * (between records) and throws [FramePipeException] on a truncated record, an unknown tag or an absurd length.
 */
class FramePipeReader(input: InputStream) {
    private val data = DataInputStream(input.buffered(1 shl 16))

    fun next(): PipeRecord? {
        val tag = ByteArray(4)
        val first = data.read()
        if (first < 0) return null
        tag[0] = first.toByte()
        try {
            data.readFully(tag, 1, 3)
            val length = data.readInt().toLong() and 0xFFFFFFFFL
            if (length > MAX_RECORD) throw FramePipeException("record of $length bytes")
            val payload = ByteArray(length.toInt())
            data.readFully(payload)
            return when (val t = String(tag, Charsets.US_ASCII)) {
                HDR -> PipeRecord.Header(parseHeader(String(payload, Charsets.UTF_8)))
                FRM -> {
                    if (payload.size < 8) throw FramePipeException("frame record without pts")
                    PipeRecord.Frame(ByteBuffer.wrap(payload, 0, 8).double, payload.copyOfRange(8, payload.size))
                }
                END -> PipeRecord.End
                else -> throw FramePipeException("unknown record tag ${t.filter { it in ' '..'~' }}")
            }
        } catch (e: EOFException) {
            throw FramePipeException("truncated record")
        }
    }

    companion object {
        const val HDR = "HDR1"
        const val FRM = "FRM1"
        const val END = "END1"
        const val MAX_RECORD = 64L shl 20

        fun parseHeader(json: String): ClipHeader {
            val o = runCatching { Json.parseToJsonElement(json) as JsonObject }.getOrNull() ?: throw FramePipeException("bad header")
            fun num(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
            val w = (o["width"] as? JsonPrimitive)?.intOrNull ?: 0
            val h = (o["height"] as? JsonPrimitive)?.intOrNull ?: 0
            val fps = num("fps")?.takeIf { it > 0.0 } ?: 30.0
            val count = (o["frameCount"] as? JsonPrimitive)?.intOrNull ?: 0
            return ClipHeader(w, h, fps, count, num("durationS") ?: (count / fps), num("start") ?: 0.0)
        }

        /** Writes one record (tests, and anything that wants to feed the viewer frames). */
        fun write(out: OutputStream, tag: String, payload: ByteArray) {
            require(tag.length == 4)
            out.write(tag.toByteArray(Charsets.US_ASCII))
            out.write(ByteBuffer.allocate(4).putInt(payload.size).array())
            out.write(payload)
        }

        fun frame(pts: Double, jpeg: ByteArray): ByteArray =
            ByteArrayOutputStream().also { it.write(ByteBuffer.allocate(8).putDouble(pts).array()); it.write(jpeg) }.toByteArray()
    }
}

/** A decoded frame at its container pts. */
class VideoFrame(val pts: Double, val image: BufferedImage)

/**
 * Frames of the clip for the playback clock: runs `frame_pipe.py` from a start time (restarted on every seek), decodes
 * the JPEGs on its reader thread and keeps a few frames ahead of the clock. [frameFor] gives the frame to show at a
 * media time: the newest one not after it (half a frame of tolerance), never an older one than already shown.
 */
class FramePipe(
    private val python: String,
    private val script: File,
    clip: File?,
    private val width: Int,
    private val onProblem: (String?) -> Unit,
) : AutoCloseable {
    /** The clip being decoded (null = none; [start] then does nothing). */
    @Volatile var clip: File? = clip
        private set

    /** Decodes [clip] from now on, starting at [pts]. */
    fun open(clip: File?, pts: Double) {
        this.clip = clip
        header = null
        if (clip == null) {
            synchronized(lock) { generation++; buffer.clear(); shown = null; lock.notifyAll() }
            process?.let { stop(it) }
            return
        }
        start(pts)
    }

    private val lock = Object()
    private val buffer = ArrayDeque<VideoFrame>()
    @Volatile private var process: Process? = null
    @Volatile private var generation = 0
    @Volatile private var closed = false
    private var shown: VideoFrame? = null

    @Volatile var header: ClipHeader? = null
        private set

    /** The clip ended (END1) in the current run. */
    @Volatile var ended = false
        private set

    /** Frames decoded per second (debug line). */
    val decodeRate = RateCounter()

    private var lastRestartNs = 0L

    /** (Re)starts decoding at [pts]; frames of an earlier run are dropped. */
    fun start(pts: Double) {
        val clip = clip
        if (closed || clip == null) return
        val gen: Int
        synchronized(lock) {
            generation++
            gen = generation
            buffer.clear()
            shown = null
            ended = false
            lock.notifyAll()
        }
        process?.let { stop(it) }
        lastRestartNs = System.nanoTime()
        val cmd = listOf(python, script.absolutePath, clip.absolutePath, "--start", Options.fmt(pts, 3), "--width", width.toString())
        val p = try {
            ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        } catch (e: Exception) {
            onProblem("Video: cannot start $python (${e.message})")
            return
        }
        process = p
        Thread({ readLoop(p, gen) }, "frame-pipe-$gen").apply { isDaemon = true }.start()
    }

    private fun readLoop(p: Process, gen: Int) {
        try {
            val reader = FramePipeReader(p.inputStream)
            while (!closed && gen == generation) {
                when (val r = reader.next() ?: break) {
                    is PipeRecord.Header -> if (gen == generation) header = r.header
                    is PipeRecord.End -> { ended = true; break }
                    is PipeRecord.Frame -> {
                        val img = ImageIO.read(r.jpeg.inputStream()) ?: continue
                        decodeRate.tick()
                        synchronized(lock) {
                            while (!closed && gen == generation && buffer.size >= CAPACITY) lock.wait(100)
                            if (gen != generation) return
                            buffer.addLast(VideoFrame(r.pts, img))
                        }
                        onProblem(null)
                    }
                }
            }
        } catch (e: FramePipeException) {
            if (gen == generation && !closed) onProblem("Video: ${e.message}")
        } catch (_: InterruptedException) {
        } catch (e: java.io.IOException) {
            if (gen == generation && !closed) onProblem("Video: ${e.message}")
        }
    }

    /**
     * The frame to show at media time [pts]. Drops the frames it passes; restarts the pipe at [pts] when decoding
     * fell more than [MAX_LAG_S] behind (at most every few seconds).
     */
    fun frameFor(pts: Double): VideoFrame? {
        val half = 0.5 / (header?.fps ?: 30.0)
        val result: VideoFrame?
        var lagging = false
        synchronized(lock) {
            while (buffer.isNotEmpty() && buffer.first().pts <= pts + half) {
                shown = buffer.removeFirst()
                lock.notifyAll()
            }
            result = shown
            val newest = buffer.peekLast()?.pts ?: shown?.pts
            if (!ended && (newest == null || newest < pts - MAX_LAG_S)) lagging = true
        }
        if (lagging && System.nanoTime() - lastRestartNs > RESTART_GAP_NS) start(pts + 0.3)
        return result
    }

    /** Frames decoded but not shown yet. */
    val buffered: Int get() = synchronized(lock) { buffer.size }

    override fun close() {
        closed = true
        synchronized(lock) { lock.notifyAll() }
        process?.let { stop(it) }
    }

    private fun stop(p: Process) {
        runCatching { p.inputStream.close() }
        p.destroy()
    }

    companion object {
        /** Frames kept ahead of the clock (about 0.4 s at 30 fps). */
        const val CAPACITY = 12
        const val MAX_LAG_S = 1.5
        private const val RESTART_GAP_NS = 3_000_000_000L
    }
}

/** Events per second over the last second or so (render fps, decoded fps). */
class RateCounter {
    private val times = ArrayDeque<Long>()

    @Synchronized fun tick(nowNs: Long = System.nanoTime()) {
        times.addLast(nowNs)
        while (times.size > 2 && nowNs - times.first() > 1_500_000_000L) times.removeFirst()
    }

    @Synchronized fun rate(nowNs: Long = System.nanoTime()): Double? {
        if (times.size < 2 || nowNs - times.last() > 1_000_000_000L) return null
        return (times.size - 1) / ((times.last() - times.first()) / 1e9)
    }
}
