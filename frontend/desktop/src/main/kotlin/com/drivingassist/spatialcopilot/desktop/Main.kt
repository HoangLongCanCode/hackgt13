package com.drivingassist.spatialcopilot.desktop

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val options = try {
        Options.parse(args)
    } catch (_: HelpRequested) {
        println(Options.USAGE)
        return
    } catch (e: IllegalArgumentException) {
        System.err.println("desktop-sim: ${e.message}\n")
        System.err.println(Options.USAGE)
        exitProcess(2)
    }
    if (options.offscreen) System.setProperty("java.awt.headless", "true")
    val app = DesktopApp(options)
    val code = try {
        if (options.offscreen) app.runOffscreen() else { app.runWindow(); return }
    } finally {
        if (options.offscreen) app.close()
    }
    exitProcess(code)
}

/** Where things live: the repo's perception_engine (clips, venv, outputs), found from the working directory up. */
object Paths {
    val perceptionEngine: File? by lazy {
        var d: File? = File("").absoluteFile
        while (d != null) {
            if (d.name == "perception_engine" && File(d, "perception").isDirectory) return@lazy d
            val child = File(d, "perception_engine")
            if (File(child, "perception").isDirectory) return@lazy child
            d = d.parentFile
        }
        null
    }

    /** PERCEPTION_DATA_DIR, else perception_engine/data (the server's rule). */
    val dataDir: File? get() = System.getenv("PERCEPTION_DATA_DIR")?.let(::File) ?: perceptionEngine?.let { File(it, "data") }

    val outputsDir: File get() = System.getenv("PERCEPTION_OUTPUTS_DIR")?.let(::File)
        ?: perceptionEngine?.let { File(it, "outputs") } ?: File("outputs")

    /** --python, else PERCEPTION_PYTHON, else perception_engine/.venv, else `python` on the PATH. */
    fun python(explicit: String?): String {
        explicit?.let { return it }
        System.getenv("PERCEPTION_PYTHON")?.takeIf { it.isNotBlank() }?.let { return it }
        val venv = perceptionEngine?.let { File(it, ".venv") }
        listOfNotNull(venv?.let { File(it, "Scripts/python.exe") }, venv?.let { File(it, "bin/python") })
            .firstOrNull { it.isFile }?.let { return it.absolutePath }
        return "python"
    }
}

/**
 * The server's clip index (server.py `index_videos`): videoId = a file stem, or the folder name of a `video.mp4`
 * (a recorded session under sim_videos/), searched recursively in bdd100k/videos then sim_videos; first hit wins.
 */
object ClipFinder {
    private val EXTS = setOf("mp4", "mov", "mkv", "avi", "m4v", "webm")

    fun find(videoId: String, dataDir: File?): File? = all(dataDir)[videoId]

    /** Every clip id -> file (recorded sessions by folder name, other clips by stem), sorted by id. */
    fun all(dataDir: File?): Map<String, File> {
        dataDir ?: return emptyMap()
        val out = LinkedHashMap<String, File>()
        for (root in listOf(File(dataDir, "bdd100k/videos"), File(dataDir, "sim_videos"))) {
            if (!root.isDirectory) continue
            root.walkTopDown().filter { it.isFile && it.extension.lowercase() in EXTS }.sortedBy { it.path }.forEach {
                if (it.name == "video.mp4") out.putIfAbsent(it.parentFile.name, it) else out.putIfAbsent(it.nameWithoutExtension, it)
            }
        }
        return out.toSortedMap()
    }
}

class DesktopApp(private val options: Options) : AutoCloseable {
    private val clip: File? = options.clip ?: ClipFinder.find(options.videoId, Paths.dataDir)
    private val python = Paths.python(options.python)
    private lateinit var session: SimSession
    private val frames: FramePipe = FramePipe(python, extractScript(), clip?.takeIf { it.isFile }, options.videoWidth) { p ->
        if (::session.isInitialized) session.reportProblem(p)
    }
    private var voice: DesktopVoice? = null
    private lateinit var viewer: Viewer

    init {
        session = SimSession(options, frames)
        if (clip == null || !clip.isFile) {
            session.reportProblem("No clip for video id ${options.videoId} on this PC (looked in ${Paths.dataDir ?: "perception_engine/data"}); give --clip FILE")
        }
        voice = runCatching { DesktopVoice(session, DesktopVoice.loadCatalog(options.catalog), speak = options.voice && !options.offscreen) }
            .onFailure { System.err.println("desktop-sim: voice off: ${it.message}") }.getOrNull()
        viewer = Viewer(session, frames, voice)
        println("desktop-sim: ${options.videoId} clip ${clip ?: "-"} | server ${options.url} | python $python")
    }

    private fun start(autoPlay: Boolean, readyWaitMs: Long) {
        session.start(autoPlay = autoPlay, readyWaitMs = readyWaitMs)
        frames.start(session.positionSeconds)
        voice?.start()
    }

    /** The clip's length reaches the clock once the pipe has said it. */
    private fun syncDuration() {
        if (session.clock.durationS == null) frames.header?.durationS?.takeIf { it > 0.0 }?.let { session.clock.durationS = it }
    }

    /** Clip ids on this PC, in order (N / P cycle through them, O picks one). */
    private fun clipIds(): List<String> = ClipFinder.all(Paths.dataDir).keys.toList()

    private fun switchTo(id: String, frame: JFrame) {
        if (id == session.videoId) return
        val clip = options.clip?.takeIf { id == options.videoId } ?: ClipFinder.find(id, Paths.dataDir)
        session.switchVideo(id, clip)
        frame.title = "desktop-sim - $id"
        println("desktop-sim: switched to $id (${clip ?: "no clip"})")
    }

    private fun cycle(step: Int, frame: JFrame) {
        val ids = clipIds().ifEmpty { return }
        val i = ids.indexOf(session.videoId)
        switchTo(ids[Math.floorMod((if (i < 0) 0 else i) + step, ids.size)], frame)
    }

    private fun choose(frame: JFrame) {
        val ids = clipIds().ifEmpty {
            JOptionPane.showMessageDialog(frame, "No clips under ${Paths.dataDir ?: "perception_engine/data"}")
            return
        }
        val picked = JOptionPane.showInputDialog(
            frame, "Clip to play (from the start):", "Change video", JOptionPane.PLAIN_MESSAGE, null,
            ids.toTypedArray(), session.videoId.takeIf { it in ids } ?: ids.first(),
        ) as? String ?: return
        switchTo(picked, frame)
    }

    // ------------------------------------------------------------------------------------------------- window

    fun runWindow() {
        start(autoPlay = true, readyWaitMs = (options.readyTimeoutS * 1000).toLong())
        SwingUtilities.invokeLater {
            val panel = ViewPanel()
            val frame = JFrame("desktop-sim - ${options.videoId}")
            frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
            frame.contentPane.add(panel)
            frame.pack()
            frame.setLocationRelativeTo(null)
            frame.addWindowListener(object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent) = quit(frame)
            })
            panel.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) = onKey(e, frame, panel)
            })
            frame.isVisible = true
            panel.requestFocusInWindow()
            Timer(15) { syncDuration(); panel.repaint() }.start()
        }
    }

    private inner class ViewPanel : JComponent() {
        init {
            preferredSize = Dimension(options.width, options.height)
            isFocusable = true
            isDoubleBuffered = true
        }

        override fun paintComponent(g: Graphics) {
            val f = viewer.frame(ViewFit.of(width, height))
            Painter.paint(g as Graphics2D, f)
        }
    }

    private fun onKey(e: KeyEvent, frame: JFrame, panel: JComponent) {
        when (e.keyCode) {
            KeyEvent.VK_SPACE -> session.togglePlay()
            KeyEvent.VK_LEFT -> session.seek(session.positionSeconds - SEEK_S)
            KeyEvent.VK_RIGHT -> session.seek(session.positionSeconds + SEEK_S)
            KeyEvent.VK_D -> viewer.debug = !viewer.debug
            KeyEvent.VK_V -> voice?.let { it.muted = !it.muted }
            KeyEvent.VK_S -> screenshot(panel.width, panel.height)
            KeyEvent.VK_N, KeyEvent.VK_PAGE_DOWN -> cycle(+1, frame)
            KeyEvent.VK_P, KeyEvent.VK_PAGE_UP -> cycle(-1, frame)
            KeyEvent.VK_O -> { choose(frame); panel.requestFocusInWindow() }
            KeyEvent.VK_ESCAPE -> quit(frame)
        }
    }

    private fun screenshot(w: Int, h: Int) {
        val f = viewer.frame(ViewFit.of(w, h))
        val dir = options.snapshotDir ?: File(Paths.outputsDir, "desktop_sim")
        val name = "${options.videoId}_${Options.fmt(f.pts, 2)}s_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"))}.png"
        val file = File(dir, name)
        writer.execute {
            runCatching { writePng(render(f, w, h, stamp = true), file) }
                .onSuccess { println("desktop-sim: screenshot $file") }
                .onFailure { System.err.println("desktop-sim: screenshot failed: ${it.message}") }
        }
    }

    private fun quit(frame: JFrame) {
        frame.dispose()
        Thread { close(); exitProcess(0) }.start()
    }

    // ---------------------------------------------------------------------------------------------- offscreen

    /** PNG sequence (--render-out) or single snapshots (--snapshots), played in real time against the server. */
    fun runOffscreen(): Int {
        if (frames.clip == null) {
            System.err.println("desktop-sim: no clip for ${options.videoId}; give --clip FILE")
            return 2
        }
        start(autoPlay = false, readyWaitMs = 0)
        val ready = runBlocking { withTimeoutOrNull((options.readyTimeoutS * 1000).toLong()) { session.link.first { it.serverReady } } }
        if (ready == null) {
            System.err.println("desktop-sim: the server at ${options.url} was not ready within ${options.readyTimeoutS} s " +
                "(${session.link.value.lastError ?: session.link.value.state})")
            return 3
        }
        return if (options.renderOut != null) renderSequence(options.renderOut) else renderSnapshots()
    }

    private fun renderSequence(out: File): Int {
        out.mkdirs()
        val from = options.from ?: options.start
        session.seek(from)
        session.play()
        val to = options.to ?: waitForDuration() ?: run {
            System.err.println("desktop-sim: clip length unknown; give --to")
            return 2
        }
        val csv = File(out, "frames.csv").bufferedWriter()
        csv.write("index,pts,video_pts,following,lead_m,route_action,route_m,arrow,instruction\n")
        var i = 0
        while (true) {
            val target = from + i / options.fps
            if (target > to) break
            waitUntil(target)
            syncDuration()
            val f = viewer.frame(ViewFit.of(options.width, options.height))
            val file = File(out, "frame_%05d.png".format(i))
            val img = render(f, options.width, options.height, options.stamp)
            writer.execute { writePng(img, file) }
            csv.write(listOf(
                i, Options.fmt(f.pts, 3), f.video?.let { Options.fmt(it.pts, 3) } ?: "",
                f.context.following.state, f.context.following.distanceMeters?.let { Options.fmt(it, 1) } ?: "",
                f.route?.action ?: "", f.routeDistance?.let { Options.fmt(it, 0) } ?: "",
                f.scene.arrowKind?.name?.lowercase() ?: "", csvText(f.instruction?.primary),
            ).joinToString(",") + "\n")
            i++
            if (session.clock.atEnd()) break
        }
        csv.close()
        session.pause()
        writeCues(out)
        finishWrites()
        println("desktop-sim: $i frames in $out")
        return 0
    }

    private fun renderSnapshots(): Int {
        val dir = options.snapshotDir ?: File(Paths.outputsDir, "desktop_sim")
        dir.mkdirs()
        for (t in options.snapshots) {
            session.seek((t - options.leadIn).coerceAtLeast(0.0))
            session.play()
            waitUntil(t)
            val f = viewer.frame(ViewFit.of(options.width, options.height))
            val file = File(dir, "${options.videoId}_${Options.fmt(t, 1)}s.png")
            val img = render(f, options.width, options.height, options.stamp)
            writer.execute { writePng(img, file); println("desktop-sim: snapshot $file") }
            session.pause()
        }
        writeCues(dir)
        finishWrites()
        return 0
    }

    private fun waitUntil(pts: Double) {
        while (session.positionSeconds + 1e-4 < pts && !(session.clock.atEnd())) {
            syncDuration()
            Thread.sleep(2)
        }
    }

    private fun waitForDuration(timeoutMs: Long = 10_000): Double? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            syncDuration()
            session.clock.durationS?.let { return it }
            Thread.sleep(20)
        }
        return null
    }

    private fun writeCues(dir: File) {
        val cues = voice?.drain().orEmpty()
        File(dir, "cues.log").writeText(cues.joinToString("") { c ->
            "${Options.fmt(c.atPts, 2)}\t${c.cueId}\t${c.outcome}\t${c.text ?: ""}\n"
        })
    }

    private fun csvText(s: String?): String = s?.let { "\"" + it.replace("\"", "\"\"") + "\"" } ?: ""

    // ------------------------------------------------------------------------------------------------ helpers

    private val writer = Executors.newSingleThreadExecutor { Thread(it, "png-writer").apply { isDaemon = true } }

    private fun finishWrites() {
        writer.shutdown()
        writer.awaitTermination(60, TimeUnit.SECONDS)
    }

    private fun render(f: FrameState, w: Int, h: Int, stamp: Boolean): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            Painter.paint(g, f, stamp)
        } finally {
            g.dispose()
        }
        return img
    }

    private fun writePng(img: BufferedImage, file: File) {
        file.parentFile?.mkdirs()
        ImageIO.write(img, "png", file)
    }

    override fun close() {
        runCatching { voice?.close() }
        runCatching { frames.close() }
        runCatching { session.close() }
    }

    companion object {
        const val SEEK_S = 5.0

        /** frame_pipe.py ships inside the viewer; Python needs it as a file. */
        private fun extractScript(): File {
            val text = DesktopApp::class.java.getResource("/desktop/frame_pipe.py")?.readText()
                ?: error("frame_pipe.py is not in the viewer (rebuild :desktop)")
            val f = File.createTempFile("frame_pipe", ".py")
            f.deleteOnExit()
            f.writeText(text)
            return f
        }
    }
}
