package com.ksr.copilot.context

import com.ksr.copilot.perception.Camera
import com.ksr.copilot.perception.DistanceUpdate
import com.ksr.copilot.perception.HelloMessage
import com.ksr.copilot.perception.ImageSize
import com.ksr.copilot.perception.Lanes
import com.ksr.copilot.perception.LightState
import com.ksr.copilot.perception.PerceivedObject
import com.ksr.copilot.perception.PerceptionFrame
import com.ksr.copilot.perception.PerceptionSource
import com.ksr.copilot.perception.PerceptionUpdate
import com.ksr.copilot.perception.Sign
import com.ksr.copilot.perception.StatsMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToLong

/** WorldModel tuning. Defaults are prototype placeholders, tune on real clips. */
data class WorldModelConfig(
    /** Drop a track after this much media time unseen. */
    val staleTrackSeconds: Double = 0.5,
    /** Distance history window used for the slope / smoothing. */
    val distanceWindowSeconds: Double = 1.5,
    val minSpeedSamples: Int = 4,
    val minSpeedSpanSeconds: Double = 0.4,
    /** EMA weight for distance until enough samples for the robust fit. */
    val distanceEmaAlpha: Double = 0.5,
    /** Forget a distance that has not been re-measured for this long. */
    val distanceHoldSeconds: Double = 1.0,
    /**
     * Two distance / lane observations less than this apart (media time) are the same measurement
     * (wave 1 carries the value wave 2 already delivered; their time stamps differ by rounding).
     */
    val measurementDedupeSeconds: Double = 0.012,
    val maxAbsRelativeSpeedMps: Double = 70.0,
    /** Below this closing speed a track is not "approaching" and no range-rate TTC is computed. */
    val minClosingSpeedMps: Double = 0.5,
    /** Fallback for inEgoPath when the server gives null but lateral is known. */
    val egoHalfWidthMeters: Double = 1.8,
    /** A new light colour must be seen this many consecutive frames before it is adopted. */
    val lightConfirmFrames: Int = 3,
    /** UNKNOWN only replaces a known colour after this many consecutive frames. */
    val lightUnknownFrames: Int = 15,
    val laneModeWindow: Int = 5,
    val lanesStaleSeconds: Double = 1.5,
    val roadStaleSeconds: Double = 1.5,
    val signHoldSeconds: Double = 2.0,
    /** A pts jump backwards larger than this means the video looped / new source: reset. */
    val rewindResetSeconds: Double = 1.0,
    /** Live/video: perception is stale when the newest result was captured (or received) longer ago than this. */
    val staleAfterMs: Long = 500,
    /** Box-velocity fit: history window (media s), minimum samples and minimum time span. */
    val boxVelocityWindowSeconds: Double = 0.5,
    val boxVelocityMinSamples: Int = 3,
    val boxVelocityMinSpanSeconds: Double = 0.08,
    /** blockAges keys used by the Python engine. */
    val depthBlockKey: String = "depth",
    val lanesBlockKey: String = "lanes",
    val roadBlockKey: String = "road",
)

/**
 * The in-memory world ("the dictionary"): merges every wave-1 [PerceptionFrame] and every wave-2
 * [PerceptionUpdate] into per-track state and publishes an immutable [WorldSnapshot] through [snapshot].
 *
 * Two waves (PROTOCOL_v2): wave 1 (detection, tracking, light state) arrives for every analysed
 * frame; wave 2 (distance, lanes, road, signs) arrives later for an older frame. A wave-2 update is
 * merged into the latest state without resetting wave-1 data: distances enter each track's history
 * at the time they were MEASURED, lanes/road/signs replace the previous ones only if newer. Until
 * the first wave-2 update of a session, the values carried on wave-1 frames are used (v1 behaviour).
 *
 * Staleness: [WorldSnapshot.perceptionStale] is true when the link is down ([setLinkUp]) or the
 * newest result is older than [WorldModelConfig.staleAfterMs] (capture time via `echo` in live,
 * else arrival time). Call [refreshStaleness] periodically (~10 Hz) so it flips without new messages.
 *
 * Threading: NOT thread-safe. Call every mutating method from one coroutine / under one lock
 * (the PerceptionBridge does this). Readers only read [snapshot], which is safe from any thread.
 *
 * @param clockNs monotonic clock in the SAME time base as the uplink `captureTimeNs`.
 */
class WorldModel(
    val config: WorldModelConfig = WorldModelConfig(),
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val clockNs: () -> Long = System::nanoTime,
) {
    private val _snapshot = MutableStateFlow(WorldSnapshot.EMPTY)
    val snapshot: StateFlow<WorldSnapshot> = _snapshot.asStateFlow()

    private val _hello = MutableStateFlow<HelloMessage?>(null)
    val hello: StateFlow<HelloMessage?> = _hello.asStateFlow()

    private val tracks = HashMap<Int, Track>()
    private val signs = LinkedHashMap<String, SignState>()
    private val laneWindow = ArrayDeque<Int>()
    private var lanesState: LanesState? = null
    private var roadState: RoadState? = null
    private var stats: StatsMessage? = null

    private var sessionId: String? = null
    private var lastSeq: Long = -1
    private var lastPts: Double = Double.NaN
    private var lastReceivedMs: Long = -1
    private var receiveIntervalEmaMs: Double? = null
    private var framePeriodEmaSec: Double? = null
    private var framesReceived: Long = 0
    private var serverDropped: Long = 0

    private var revision: Long = 0
    private var wave2Seen = false
    private var wave2: Wave2Info? = null
    private var lastSignsPts = Double.NaN
    private var lastTiming: FrameTiming? = null
    private var lastImage: ImageSize? = null
    private var lastCamera: Camera? = null
    private var lastBlockAges: Map<String, Int> = emptyMap()
    private var lastTimingsMs: Map<String, Double> = emptyMap()
    private var linkUp = true
    private var lastResultNs: Long? = null

    /** Collects [source] until it completes. Run on one coroutine (see class doc). */
    suspend fun run(source: PerceptionSource) {
        source.messages.collect { message ->
            when (message) {
                is PerceptionFrame -> update(message)
                is PerceptionUpdate -> applyUpdate(message)
                is HelloMessage -> onHello(message)
                is StatsMessage -> onStats(message)
                else -> Unit // skip / pong / unknown: transport-level, handled by the bridge
            }
        }
    }

    fun onHello(hello: HelloMessage) {
        _hello.value = hello
        if (hello.sessionId != null && hello.sessionId != sessionId) {
            reset(hello.sessionId)
            _snapshot.value = WorldSnapshot.EMPTY.copy(serverStats = stats, revision = ++revision)
        }
    }

    fun onStats(message: StatsMessage) {
        stats = message
        _snapshot.value = _snapshot.value.copy(serverStats = message)
    }

    /** Link up/down (socket state). Down makes the snapshot stale immediately. */
    fun setLinkUp(up: Boolean, nowNs: Long = clockNs()): WorldSnapshot {
        linkUp = up
        return refreshStaleness(nowNs)
    }

    /** Re-evaluates [WorldSnapshot.perceptionStale]; publishes only when it changes. */
    fun refreshStaleness(nowNs: Long = clockNs()): WorldSnapshot {
        val current = _snapshot.value
        val stale = isStale(nowNs)
        if (current.perceptionStale == stale) return current
        val s = current.copy(perceptionStale = stale, revision = ++revision)
        _snapshot.value = s
        return s
    }

    /** Merge one wave-1 frame and publish the new snapshot. Out-of-order / duplicate frames are ignored. */
    fun update(frame: PerceptionFrame, receivedAtMs: Long = clockMs(), receivedAtNs: Long = clockNs()): WorldSnapshot {
        val pts = frame.ptsSeconds
        if (frame.sessionId != sessionId || (!lastPts.isNaN() && pts < lastPts - config.rewindResetSeconds)) {
            reset(frame.sessionId)
        } else if (frame.seq <= lastSeq) {
            return _snapshot.value
        }

        updateTiming(frame, receivedAtMs)
        val depthFresh = frame.blockAges[config.depthBlockKey]?.let { it == 0 } ?: true

        for (obj in frame.objects) {
            tracks.getOrPut(obj.id) { Track(obj.id, pts) }.observe(obj, pts, frame.seq, depthFresh)
        }
        tracks.values.removeAll { pts - it.lastSeenPts > config.staleTrackSeconds }

        if (!wave2Seen) {
            // No wave-2 stream (v1 server, or before the first update): use what wave 1 carries.
            updateLanesFromFrame(frame)
            updateRoadFromFrame(frame)
            observeSigns(frame.signs, pts)
        }
        expireSlowBlocks(pts)

        lastResultNs = frame.echo?.captureTimeNs ?: receivedAtNs
        lastTiming = timing(frame, receivedAtMs, receivedAtNs)
        lastImage = frame.image
        lastCamera = frame.camera
        lastBlockAges = frame.blockAges
        lastTimingsMs = frame.timingsMs
        lastSeq = frame.seq
        lastPts = pts
        return publish(receivedAtNs)
    }

    /**
     * Merge one wave-2 update into the latest state (does not reset wave-1 data) and publish.
     * Ignored before the first wave-1 frame of the session or when it belongs to another session.
     */
    fun applyUpdate(update: PerceptionUpdate, receivedAtMs: Long = clockMs(), receivedAtNs: Long = clockNs()): WorldSnapshot {
        if (update.sessionId != null && sessionId != null && update.sessionId != sessionId) return _snapshot.value
        if (lastSeq < 0 || lastPts.isNaN()) return _snapshot.value
        wave2Seen = true
        val m = update.ptsSeconds

        update.distances?.forEach { d -> tracks[d.id]?.observeUpdate(d, m) }
        update.lanes?.let { adoptLanes(it, m, countForMode = true) }
        update.road?.let { r ->
            val current = roadState
            if (current == null || m >= current.measuredPts - 1e-6) roadState = RoadState(r, m, 0.0)
        }
        update.signs?.let { list ->
            if (lastSignsPts.isNaN() || m > lastSignsPts + 1e-6) {
                observeSigns(list, m)
                lastSignsPts = m
            }
        }
        expireSlowBlocks(lastPts)
        wave2 = Wave2Info(
            seq = update.seq,
            frameIndex = update.frameIndex,
            ptsSeconds = m,
            processingMs = update.processingMs,
            blocks = update.blocks,
            timingsMs = update.timingsMs,
            receivedAtMs = receivedAtMs,
            lagSeconds = round3(lastPts - m),
        )
        return publish(receivedAtNs)
    }

    private fun isStale(nowNs: Long): Boolean {
        val last = lastResultNs ?: return true
        return !linkUp || nowNs - last > config.staleAfterMs * 1_000_000L
    }

    private fun publish(nowNs: Long): WorldSnapshot {
        val pts = lastPts
        val snap = WorldSnapshot(
            timing = lastTiming,
            image = lastImage,
            camera = lastCamera,
            objects = tracks.values.associate { it.id to it.toState(pts, lastSeq) },
            lanes = lanesState?.let { it.copy(ageSeconds = round3((pts - it.measuredPts).coerceAtLeast(0.0))) },
            road = roadState?.let { it.copy(ageSeconds = round3((pts - it.measuredPts).coerceAtLeast(0.0))) },
            signs = signs.values.toList(),
            blockAges = lastBlockAges,
            timingsMs = lastTimingsMs,
            serverStats = stats,
            wave2 = wave2,
            perceptionStale = isStale(nowNs),
            revision = ++revision,
        )
        _snapshot.value = snap
        return snap
    }

    private fun reset(newSession: String?) {
        tracks.clear(); signs.clear(); laneWindow.clear()
        lanesState = null; roadState = null
        sessionId = newSession
        lastSeq = -1; lastPts = Double.NaN
        framePeriodEmaSec = null; serverDropped = 0; framesReceived = 0
        wave2Seen = false; wave2 = null; lastSignsPts = Double.NaN
        lastTiming = null; lastResultNs = null
    }

    private fun updateTiming(frame: PerceptionFrame, receivedAtMs: Long) {
        framesReceived++
        if (lastSeq >= 0 && frame.seq > lastSeq + 1) serverDropped += frame.seq - lastSeq - 1
        if (lastSeq >= 0 && !lastPts.isNaN() && frame.ptsSeconds > lastPts) {
            val period = (frame.ptsSeconds - lastPts) / (frame.seq - lastSeq).coerceAtLeast(1)
            framePeriodEmaSec = ema(framePeriodEmaSec, period, 0.1)
        }
        if (lastReceivedMs >= 0 && receivedAtMs > lastReceivedMs) {
            receiveIntervalEmaMs = ema(receiveIntervalEmaMs, (receivedAtMs - lastReceivedMs).toDouble(), 0.1)
        }
        lastReceivedMs = receivedAtMs
    }

    private fun timing(frame: PerceptionFrame, receivedAtMs: Long, receivedAtNs: Long): FrameTiming {
        val network = (receivedAtMs - frame.serverTimeMs).toDouble()
        val capture = frame.echo?.captureTimeNs
        return FrameTiming(
            sessionId = frame.sessionId,
            seq = frame.seq,
            frameIndex = frame.frameIndex,
            ptsSeconds = frame.ptsSeconds,
            serverTimeMs = frame.serverTimeMs,
            receivedAtMs = receivedAtMs,
            processingMs = frame.processingMs,
            networkLatencyMs = network,
            endToEndLatencyMs = frame.processingMs + network.coerceAtLeast(0.0),
            receiveFps = receiveIntervalEmaMs?.takeIf { it > 0 }?.let { round1(1000.0 / it) },
            analysedFps = framePeriodEmaSec?.takeIf { it > 0 }?.let { round1(1.0 / it) },
            framesReceived = framesReceived,
            serverDroppedFrames = serverDropped,
            wave = frame.wave ?: 1,
            frameId = frame.echo?.frameId,
            captureTimeNs = capture,
            receivedAtNs = receivedAtNs,
            captureToResultMs = capture?.let { round1((receivedAtNs - it) / 1e6) },
        )
    }

    /** Media time when a block last ran, from its age in analysed frames. */
    private fun measuredPts(frame: PerceptionFrame, blockKey: String): Double {
        val age = frame.blockAges[blockKey] ?: 0
        return frame.ptsSeconds - age * (framePeriodEmaSec ?: 0.0)
    }

    private fun updateLanesFromFrame(frame: PerceptionFrame) {
        val lanes = frame.lanes ?: return
        val fresh = (frame.blockAges[config.lanesBlockKey] ?: 0) == 0
        adoptLanes(lanes, measuredPts(frame, config.lanesBlockKey), countForMode = fresh || lanesState == null, force = true)
    }

    /** Adopt a lanes measurement taken at [measuredAt]; [countForMode] adds it to the lane-number mode window. */
    private fun adoptLanes(lanes: Lanes, measuredAt: Double, countForMode: Boolean, force: Boolean = false) {
        val current = lanesState
        if (!force && current != null && measuredAt < current.measuredPts - 1e-6) return // older than what we have
        val isNewMeasurement = current == null || measuredAt > current.measuredPts + config.measurementDedupeSeconds
        if (lanes.currentLane != null && countForMode && (force || isNewMeasurement)) {
            laneWindow.addLast(lanes.currentLane!!)
            while (laneWindow.size > config.laneModeWindow) laneWindow.removeFirst()
        }
        val mode = laneWindow.groupingBy { it }.eachCount().maxWithOrNull(
            compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { if (it.key == lanes.currentLane) 1 else 0 },
        )?.key
        lanesState = LanesState(lanes, mode ?: lanes.currentLane, lanes.laneCount, measuredAt, 0.0)
    }

    private fun updateRoadFromFrame(frame: PerceptionFrame) {
        val road = frame.road ?: return
        roadState = RoadState(road, measuredPts(frame, config.roadBlockKey), 0.0)
    }

    private fun expireSlowBlocks(pts: Double) {
        lanesState?.let {
            if (pts - it.measuredPts > config.lanesStaleSeconds) { lanesState = null; laneWindow.clear() }
        }
        roadState?.let { if (pts - it.measuredPts > config.roadStaleSeconds) roadState = null }
        signs.values.removeAll { pts - it.lastSeenPts > config.signHoldSeconds }
    }

    private fun observeSigns(list: List<Sign>, measuredAt: Double) {
        for (s in list) {
            val key = s.id?.let { "id:$it" } ?: "${s.signClass}@${(s.box.centerX / 80.0).roundToLong()}"
            val prev = signs[key]
            signs[key] = SignState(key, s, prev?.firstSeenPts ?: measuredAt, measuredAt, (prev?.seenCount ?: 0) + 1)
        }
    }

    /** Mutable per-track memory; only [toState] copies escape. */
    private inner class Track(val id: Int, val firstSeenPts: Double) {
        var last: PerceivedObject? = null
        var lastSeenPts = firstSeenPts
        var lastSeenSeq = -1L

        // Distance history ring buffer (media time of the MEASUREMENT, metres).
        private val cap = 48
        private val hT = DoubleArray(cap)
        private val hD = DoubleArray(cap)
        private var hStart = 0
        private var hSize = 0
        /** Last distance value seen on a wave-1 frame (v1 "carried value" detection). */
        private var lastRaw: Double? = null
        /** Last adopted raw measurement. */
        private var lastMeasured: Double? = null
        var smoothed: Double? = null
        var distanceMeasuredPts: Double = Double.NaN
        var slope: Double? = null
        var distanceMethod: String? = null
        var distanceConfidence: Double? = null
        var lateral: Double? = null
        var lateralPts: Double = Double.NaN

        // Box history ring buffer (media time, [x1, y1, x2, y2]).
        private val bCap = 10
        private val bT = DoubleArray(bCap)
        private val bB = Array(bCap) { DoubleArray(4) }
        private var bStart = 0
        private var bSize = 0

        // Light-state debounce.
        var light: LightState? = null
        var candidate: LightState? = null
        var candidateCount = 0

        fun observe(obj: PerceivedObject, pts: Double, seq: Long, depthFresh: Boolean) {
            last = obj
            lastSeenPts = pts
            lastSeenSeq = seq
            if (obj.bbox.size == 4) pushBox(pts, obj.bbox)
            val raw = obj.distanceMeters
            if (raw != null && !raw.isNaN() && raw > 0.0) {
                // v2: the frame says how old the carried distance is. v1: a changed value or a fresh depth run is new.
                val measuredAt = when {
                    obj.distanceAgeMs != null -> pts - obj.distanceAgeMs!! / 1000.0
                    depthFresh || raw != lastRaw || distanceMeasuredPts.isNaN() -> pts
                    else -> Double.NaN
                }
                lastRaw = raw
                if (!measuredAt.isNaN() && measure(raw, measuredAt)) {
                    obj.distanceMethod?.let { distanceMethod = it }
                    obj.distanceConfidence?.let { distanceConfidence = it }
                }
            } else {
                lastRaw = null
            }
            // Wave-1 lateral is computed from the current box, so it is current as of this frame.
            obj.lateralMeters?.let { setLateral(it, pts) }
            if (!distanceMeasuredPts.isNaN() && pts - distanceMeasuredPts > config.distanceHoldSeconds) clearDistance()
            observeLight(obj.lightState)
        }

        /** Wave-2 distance measured at media time [measuredAt]. */
        fun observeUpdate(d: DistanceUpdate, measuredAt: Double) {
            val raw = d.distanceMeters
            if (raw != null && !raw.isNaN() && raw > 0.0 && measure(raw, measuredAt)) {
                lastRaw = raw
                d.distanceMethod?.let { distanceMethod = it }
                d.distanceConfidence?.let { distanceConfidence = it }
            }
            d.lateralMeters?.let { setLateral(it, measuredAt) }
        }

        private fun setLateral(value: Double, at: Double) {
            if (lateralPts.isNaN() || at >= lateralPts - 1e-6) { lateral = value; lateralPts = at }
        }

        private fun clearDistance() {
            smoothed = null; slope = null; hSize = 0; hStart = 0; lastRaw = null; lastMeasured = null
            distanceMeasuredPts = Double.NaN
        }

        /** Adds a measurement if it is newer than the last one. Returns true when adopted. */
        private fun measure(raw: Double, at: Double): Boolean {
            if (!distanceMeasuredPts.isNaN() && at <= distanceMeasuredPts + config.measurementDedupeSeconds) return false
            push(at, raw)
            distanceMeasuredPts = at
            lastMeasured = raw
            // Window the history.
            while (hSize > 0 && at - hT[hStart] > config.distanceWindowSeconds) { hStart = (hStart + 1) % cap; hSize-- }
            val n = hSize
            val t = DoubleArray(n) { hT[(hStart + it) % cap] }
            val d = DoubleArray(n) { hD[(hStart + it) % cap] }
            val span = if (n > 0) t[n - 1] - t[0] else 0.0
            val fit = if (n >= config.minSpeedSamples && span >= config.minSpeedSpanSeconds) RobustFit.theilSen(t, d, n, at) else null
            if (fit != null && abs(fit.slope) <= config.maxAbsRelativeSpeedMps) {
                slope = fit.slope
                smoothed = fit.valueAtT.coerceAtLeast(0.1)
            } else {
                slope = null
                smoothed = ema(smoothed, raw, config.distanceEmaAlpha)
            }
            return true
        }

        private fun push(t: Double, d: Double) {
            if (hSize == cap) { hStart = (hStart + 1) % cap; hSize-- }
            val idx = (hStart + hSize) % cap
            hT[idx] = t; hD[idx] = d; hSize++
        }

        private fun pushBox(t: Double, box: List<Double>) {
            if (bSize > 0 && t <= bT[(bStart + bSize - 1) % bCap]) return // not newer
            if (bSize == bCap) { bStart = (bStart + 1) % bCap; bSize-- }
            val idx = (bStart + bSize) % bCap
            bT[idx] = t
            for (i in 0 until 4) bB[idx][i] = box[i]
            bSize++
        }

        /** Least-squares slope of each box coordinate over the recent window, px/s. */
        fun boxVelocity(): List<Double>? {
            if (bSize < config.boxVelocityMinSamples) return null
            val newest = bT[(bStart + bSize - 1) % bCap]
            val idx = (0 until bSize).map { (bStart + it) % bCap }.filter { newest - bT[it] <= config.boxVelocityWindowSeconds }
            if (idx.size < config.boxVelocityMinSamples) return null
            val span = newest - bT[idx.first()]
            if (span < config.boxVelocityMinSpanSeconds) return null
            val tMean = idx.sumOf { bT[it] } / idx.size
            val sTT = idx.sumOf { (bT[it] - tMean) * (bT[it] - tMean) }
            if (sTT <= 1e-12) return null
            return (0 until 4).map { c ->
                val xMean = idx.sumOf { bB[it][c] } / idx.size
                val sTX = idx.sumOf { (bT[it] - tMean) * (bB[it][c] - xMean) }
                round1(sTX / sTT)
            }
        }

        private fun observeLight(raw: LightState?) {
            if (raw == null) return
            if (raw == light) { candidate = null; candidateCount = 0; return }
            if (raw == candidate) candidateCount++ else { candidate = raw; candidateCount = 1 }
            val needed = when {
                light == null -> 1 // first observation adopted immediately
                raw == LightState.UNKNOWN -> config.lightUnknownFrames
                else -> config.lightConfirmFrames
            }
            if (candidateCount >= needed) { light = raw; candidate = null; candidateCount = 0 }
        }

        fun toState(nowPts: Double, nowSeq: Long): ObjectState {
            val o = last!!
            val dist = smoothed
            val rel = slope
            val closing = rel?.let { -it }
            val (ttc, ttcSource) = when {
                o.ttcSeconds != null && o.ttcSeconds!! > 0 -> o.ttcSeconds to "box_scale"
                dist != null && closing != null && closing > config.minClosingSpeedMps -> (dist / closing) to "range_rate"
                else -> null to null
            }
            val lat = lateral ?: o.lateralMeters
            val visible = lastSeenSeq == nowSeq
            return ObjectState(
                id = id,
                cls = o.cls,
                bbox = o.bbox,
                confidence = o.confidence,
                ageFrames = o.ageFrames,
                firstSeenPts = firstSeenPts,
                lastSeenPts = lastSeenPts,
                visible = visible,
                distanceMeters = dist?.let(::round2),
                rawDistanceMeters = if (dist == null) null else lastMeasured,
                distanceAgeSeconds = if (distanceMeasuredPts.isNaN() || dist == null) null else round3((nowPts - distanceMeasuredPts).coerceAtLeast(0.0)),
                distanceMethod = distanceMethod ?: o.distanceMethod,
                distanceConfidence = distanceConfidence ?: o.distanceConfidence,
                lateralMeters = lat,
                relativeSpeedMps = rel?.let(::round2),
                ttcSeconds = ttc?.let(::round2),
                ttcSource = ttcSource,
                approaching = o.approaching ?: closing?.let { it > config.minClosingSpeedMps },
                inEgoPath = o.inEgoPath ?: lat?.let { abs(it) <= config.egoHalfWidthMeters },
                lightState = light,
                rawLightState = o.lightState,
                lightConfidence = o.lightConfidence,
                bboxVelocityPxPerS = if (visible) boxVelocity() else null,
            )
        }
    }

    private companion object {
        fun ema(prev: Double?, x: Double, alpha: Double) = if (prev == null) x else prev + alpha * (x - prev)
        fun round1(x: Double) = Math.round(x * 10.0) / 10.0
        fun round2(x: Double) = Math.round(x * 100.0) / 100.0
        fun round3(x: Double) = Math.round(x * 1000.0) / 1000.0
    }
}
