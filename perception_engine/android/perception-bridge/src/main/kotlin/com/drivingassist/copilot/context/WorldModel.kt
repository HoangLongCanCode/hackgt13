package com.drivingassist.copilot.context

import com.drivingassist.copilot.perception.Camera
import com.drivingassist.copilot.perception.DistanceUpdate
import com.drivingassist.copilot.perception.HelloMessage
import com.drivingassist.copilot.perception.ImageSize
import com.drivingassist.copilot.perception.Lanes
import com.drivingassist.copilot.perception.LightState
import com.drivingassist.copilot.perception.PerceivedObject
import com.drivingassist.copilot.perception.PerceptionFrame
import com.drivingassist.copilot.perception.PerceptionSource
import com.drivingassist.copilot.perception.PerceptionUpdate
import com.drivingassist.copilot.perception.Sign
import com.drivingassist.copilot.perception.StatsMessage
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
    /** [LaneLayout.from] tuning for [WorldSnapshot.laneLayout]. */
    val laneLayout: LaneLayoutParams = LaneLayoutParams(),
    /**
     * Lane lines are tracked across lanes runs by their lateral offset at the reference depth (metres across the road
     * from the camera's track, [LaneLayout.measure]). After the run's common shift is applied to every track (see
     * [laneTrackMaxShiftMeters]), a run's line continues the nearest track within this distance, else it starts one.
     */
    val laneTrackMatchMeters: Double = 0.6,
    /** A new track counts once seen this many times, at most [laneTrackConfirmSeconds] apart (else it is dropped). */
    val laneTrackConfirmSightings: Int = 2,
    val laneTrackConfirmSeconds: Double = 1.0,
    /** A counted track not seen for this long is dropped (a line missed on a few runs keeps its lane). */
    val laneTrackHoldSeconds: Double = 2.0,
    /**
     * Largest common lateral shift of all lines from one run to the next (the car drifting across the lanes, a lane
     * change; a vanishing-point jump). A shift beyond [laneTrackMatchMeters] needs two lines agreeing on it.
     */
    val laneTrackMaxShiftMeters: Double = 1.5,
    /**
     * Two tracks that were neighbours in the last layout keep the lanes between them while their gap stays this close
     * outside the lane-width limits of [LaneLayoutParams]; a track that is not in the layout and lies closer than
     * [LaneLayoutParams.minLaneWidthMeters] minus this to one that is does not count (a stray line inside a lane).
     */
    val laneTrackGapHysteresisMeters: Double = 0.5,
    /** A track switches sides of the car's track (the ego lane) only once this far past it (driving on a line). */
    val laneTrackEgoHysteresisMeters: Double = 0.25,
    /** EMA weight of a new run for the vanishing point, the tracked lines' lateral offsets and the quality. */
    val laneLayoutEmaAlpha: Double = 0.5,
    /** Runs without a layout keep the last one this long (its `ageSeconds` grows), then it is dropped. */
    val laneLayoutHoldSeconds: Double = 1.5,
    /**
     * [LaneLayout.stable] (lane numbers, arrows, the own-lane lead, the lane voice) is set once the smoothed quality is
     * at least [laneLayoutStableQuality] on [laneLayoutStableRuns] runs in a row that refresh the layout, and cleared
     * when it drops below [laneLayoutUnstableQuality] or the layout is older than [laneLayoutStableMaxAgeSeconds]: a
     * quality hovering near one bar does not switch the lanes on and off every run. Placeholders.
     */
    val laneLayoutStableQuality: Double = 0.45,
    val laneLayoutStableRuns: Int = 2,
    val laneLayoutUnstableQuality: Double = 0.35,
    val laneLayoutStableMaxAgeSeconds: Double = LaneLayout.MAX_USABLE_AGE_SECONDS,
    /**
     * A track's paint colour is a vote over its coloured sightings that fades by this factor on each one (sightings
     * without a colour keep it): a colour read wrong for a long stretch (dusk, shade) is right again after about five
     * right readings, while one wrong reading in a long right stretch changes nothing.
     */
    val laneTrackColorDecay: Double = 0.85,
    /**
     * The yellow left edge of our direction (each run's oncoming-cut line) is remembered this much media time after it
     * last read yellow, and followed from run to run while it reads white: the run's line nearest to it, if within
     * [laneYellowEdgeMatchMeters] (less than the narrowest lane, [LaneLayoutParams.minNarrowLaneWidthMeters], so the
     * memory never steps a lane over). Not followed for [laneYellowEdgeHoldSeconds] it is forgotten (real_009 crossed an
     * intersection at 10.3-12.3 s without a lanes run with lines). A layout whose line 0 lies within
     * [laneYellowEdgeMatchMeters] of it has [LaneLayout.recentYellowLeftEdge]. On real_009 the yellow curb line read
     * white for about 8 s (7.5-15.4 s) while a left-turn bay opened next to it. Placeholders.
     */
    val laneYellowEdgeMemorySeconds: Double = 10.0,
    val laneYellowEdgeMatchMeters: Double = 1.5,
    val laneYellowEdgeHoldSeconds: Double = 3.0,
    /**
     * EMA weight per lanes run of the server's camera height (readings inside
     * [LaneLayoutParams.plausibleCameraHeightMeters]; others hold it) for the lane geometry, from
     * [LaneLayoutParams.defaultCameraHeightMeters]. Slow: every lateral offset scales with it one to one.
     */
    val laneCameraHeightEmaAlpha: Double = 0.05,
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
 * Lanes: the lines of every new lanes run ([LaneLayout.measure]) are tracked one by one by their lateral offset
 * (see [observeRun] and the `laneTrack*` knobs of [WorldModelConfig]); [WorldSnapshot.laneLayout] is built from the
 * counted tracks through the smoothed vanishing point ([LaneLayout.build]) with a smoothed camera height, held briefly
 * when a run gives none, and marked [LaneLayout.stable] with hysteresis (`laneLayoutStable*`).
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
    /** Tracked / held [WorldSnapshot.laneLayout]. */
    private var layout: LaneLayout? = null
    /** The lane lines followed across lanes runs, any order. */
    private val lineTracks = ArrayList<LineTrack>()
    /** Smoothed vanishing point of the lanes runs; null = restart from the next run. */
    private var layoutVp: Pair<Double, Double>? = null
    /** Camera height (m) the tracks' lateral offsets were measured with; they scale with it. */
    private var tracksHeight: Double? = null
    /** Smoothed camera height for the lane geometry ([WorldModelConfig.laneCameraHeightEmaAlpha]); null = none yet. */
    private var laneHeight: Double? = null
    /** Runs in a row that refreshed the layout with a quality of at least [WorldModelConfig.laneLayoutStableQuality]. */
    private var stableRuns = 0
    /** [WorldSnapshot.trackVpX]. */
    private var trackVpX: Double? = null
    /** The remembered yellow left edge of our direction ([followYellowEdge]): lateral (m, run frame), last yellow / followed pts. */
    private var yellowEdge: Double? = null
    private var yellowEdgeYellowPts = Double.NaN
    private var yellowEdgeSeenPts = Double.NaN
    private var yellowEdgeHeight = 0.0
    private var nextLineTrackId = 0
    /** A new lanes run was adopted; its layout is computed once road / camera of the same message are in. */
    private var layoutPending = false
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
        updateLayout(pts)
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
        updateLayout(lastPts)
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
            laneLayout = layout?.let { it.copy(ageSeconds = round3((pts - it.measuredPts).coerceAtLeast(0.0))) },
            road = roadState?.let { it.copy(ageSeconds = round3((pts - it.measuredPts).coerceAtLeast(0.0))) },
            trackVpX = trackVpX,
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
        layout = null; lineTracks.clear(); layoutVp = null; tracksHeight = null; layoutPending = false
        laneHeight = null; stableRuns = 0; trackVpX = null; yellowEdge = null
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
        if (countForMode && (force || isNewMeasurement)) layoutPending = true
        if (lanes.currentLane != null && countForMode && (force || isNewMeasurement)) {
            laneWindow.addLast(lanes.currentLane!!)
            while (laneWindow.size > config.laneModeWindow) laneWindow.removeFirst()
        }
        val mode = laneWindow.groupingBy { it }.eachCount().maxWithOrNull(
            compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { if (it.key == lanes.currentLane) 1 else 0 },
        )?.key
        lanesState = LanesState(lanes, mode ?: lanes.currentLane, lanes.laneCount, measuredAt, 0.0)
    }

    /**
     * After a message is merged: a new lanes run (with the road / camera now in, the camera height smoothed) goes
     * through [observeRun]; a held layout older than [WorldModelConfig.laneLayoutStableMaxAgeSeconds] is no longer
     * stable, one older than [WorldModelConfig.laneLayoutHoldSeconds] is dropped. Keeps [WorldSnapshot.trackVpX].
     */
    private fun updateLayout(pts: Double) {
        if (layoutPending) {
            layoutPending = false
            lanesState?.let { lanes ->
                val camera = lastCamera?.takeIf { FlatGround.usable(it) }?.let { it.copy(cameraHeightMeters = nextLaneHeight(it)) }
                val world = WorldSnapshot(image = lastImage, camera = camera, lanes = lanes, road = roadState)
                val run = LaneLayout.measure(world, config.laneLayout)
                if (run != null) observeRun(run, camera!!) else stableRuns = 0
            }
        }
        layout?.let {
            val age = pts - it.measuredPts
            if (age > config.laneLayoutHoldSeconds) {
                layout = null
            } else if (it.stable && age > config.laneLayoutStableMaxAgeSeconds) {
                layout = it.copy(stable = false); stableRuns = 0
            }
        }
        if (layout == null) stableRuns = 0
        (roadState?.road?.vanishingPoint?.getOrNull(0)?.takeIf { it.isFinite() } ?: layoutVp?.first)?.let { trackVpX = it }
    }

    /**
     * The lane geometry's camera height for this lanes run: an EMA ([WorldModelConfig.laneCameraHeightEmaAlpha]) of the
     * server's readings inside the plausible range, from the default height. A reading outside it (real_011 said
     * 4.46 m for its first 15 s) or none holds the value: no snap back to the default at the edge of the range, and
     * never outside it.
     */
    private fun nextLaneHeight(camera: Camera): Double {
        val p = config.laneLayout
        val range = p.plausibleCameraHeightMeters
        val h = laneHeight ?: p.defaultCameraHeightMeters
        val raw = camera.cameraHeightMeters?.takeIf { it in range }
        val next = (if (raw == null) h else h + config.laneCameraHeightEmaAlpha * (raw - h)).coerceIn(range.start, range.endInclusive)
        laneHeight = next
        return next
    }

    /**
     * One lanes run's lines (a run without two usable lines changes nothing: tracks age, the layout is held):
     *
     * 1. Tracks not seen for [WorldModelConfig.laneTrackHoldSeconds] (not yet counted: [WorldModelConfig.laneTrackConfirmSeconds])
     *    are dropped; a changed camera height scales every track's lateral offset with it.
     * 2. Common shift: the shift of all tracks (at most [WorldModelConfig.laneTrackMaxShiftMeters]) that puts the most of
     *    the run's lines within [WorldModelConfig.laneTrackMatchMeters] of one (ties: the smaller total miss), refined to
     *    the median miss. Every track moves by it, seen or not: when the car drifts across a line or changes lanes all
     *    lines move together and the tracks move with them, so the lanes keep their lines and the ego lane moves on.
     * 3. Each line continues the nearest track within the match distance (EMA of its offset), else starts a new one;
     *    tracks closer than [LaneLayoutParams.mergeMeters] become one. A track changes sides of the car's track only
     *    [WorldModelConfig.laneTrackEgoHysteresisMeters] past it.
     * 4. The counted tracks are re-projected through the smoothed vanishing point (slope = lateral / metres per slope
     *    there); the oncoming cut is made on all of them (a yellow stray still cuts), then strays inside a lane of the
     *    last layout are left out, and [LaneLayout.build] lays out the rest (lanes, gaps with
     *    [WorldModelConfig.laneTrackGapHysteresisMeters], virtual lines, ego lane); the quality is smoothed. No layout
     *    from them keeps the shown one (held until it is too old).
     * 5. The layout is as old as the newest run that saw two of its lines (a run whose lines all start new tracks or
     *    are strays does not refresh it), and [LaneLayout.stable] follows the quality with hysteresis.
     */
    private fun observeRun(run: LaneRun, camera: Camera) {
        val t = run.measuredPts
        followYellowEdge(run)
        lineTracks.removeAll { t - it.lastSeenPts > (if (it.confirmed) config.laneTrackHoldSeconds else config.laneTrackConfirmSeconds) }
        if (lineTracks.isEmpty()) layoutVp = null
        tracksHeight?.let { h -> if (h > 0.0 && run.heightMeters != h) lineTracks.forEach { it.lateral *= run.heightMeters / h } }
        tracksHeight = run.heightMeters

        val shift = commonShift(run.lines)
        lineTracks.forEach { it.lateral += shift }
        val matched = matches(run.lines, lineTracks, 0.0)
        val seen = HashSet<Int>()
        for ((i, j, _) in matched) { lineTracks[j].see(run.lines[i], t); seen += i }
        for (i in run.lines.indices) if (i !in seen) lineTracks += LineTrack(run.lines[i], t)
        mergeTracks()
        lineTracks.forEach { it.updateSide() }

        val a = config.laneLayoutEmaAlpha
        val vp = layoutVp?.let { (x, y) -> (x + a * (run.vpX - x)) to (y + a * (run.vpY - y)) } ?: (run.vpX to run.vpY)
        layoutVp = vp
        val metersPerSlope = LaneLayout.metersPerSlope(vp.first, vp.second, camera, run.imageHeight, config.laneLayout) ?: run.metersPerSlope
        val counted = lineTracks.filter { it.confirmed }.sortedBy { it.lateral }
        val candidates = counted.map { it.candidate(metersPerSlope) }
        // The oncoming cut before strays are left out: a yellow line inside a lane of the last layout is not drawn, but
        // the lines left of it are still the median / oncoming road (as LaneLayout.from sees the same lines).
        val cut = candidates.filter { it.left && it.color == LaneLine.YELLOW }.maxOfOrNull { it.lateral }
        val established = counted.filter { it.inLayout }
        val strayMeters = config.laneLayout.minLaneWidthMeters - config.laneTrackGapHysteresisMeters
        val keep = counted.indices.filter { i ->
            val c = counted[i]
            (cut == null || candidates[i].lateral >= cut) && (c.inLayout || established.none { abs(it.lateral - c.lateral) < strayMeters })
        }
        val tracks = keep.map { counted[it] }
        val lines = keep.map { candidates[it] }
        val built = LaneLayout.build(
            vp.first, vp.second, metersPerSlope, lines, run.imageHeight, run.confidence, run.measuredPts, run.ageSeconds,
            config.laneLayout, config.laneTrackGapHysteresisMeters, cut,
        )
        if (built == null) { stableRuns = 0; return }
        // Remember which tracks made the layout and their neighbours (by slope: build copies the tracks' slopes).
        lineTracks.forEach { it.inLayout = false; it.rightNeighbour = -1; it.lanesToRight = 0 }
        var previous: LineTrack? = null
        var lanes = 0
        for (line in built.lines) {
            lanes++
            if (!line.detected) continue
            val track = tracks.getOrNull(lines.indexOfFirst { it.slope == line.slope }) ?: continue
            track.inLayout = true
            previous?.let { it.rightNeighbour = track.id; it.lanesToRight = lanes }
            previous = track
            lanes = 0
        }
        // As old as the newest run that saw two of its lines: a run whose lines all start new tracks (or are strays) does
        // not refresh it, so the age bars (stable, held, dropped) still apply to it.
        val seenPts = tracks.filter { it.inLayout }.map { it.lastSeenPts }.sortedDescending().getOrNull(1) ?: t
        val shown = layout
        val quality = if (shown == null) built.quality else round3(shown.quality + a * (built.quality - shown.quality))
        if (seenPts >= t - 1e-9) stableRuns = if (quality >= config.laneLayoutStableQuality) stableRuns + 1 else 0
        val stable = quality >= config.laneLayoutUnstableQuality && (shown?.stable == true || stableRuns >= config.laneLayoutStableRuns)
        val edge = yellowEdge
        val atEdge = edge != null && abs(built.lines.first().slope * metersPerSlope - edge) <= config.laneYellowEdgeMatchMeters
        layout = built.copy(quality = quality, measuredPts = seenPts, stable = stable, cameraHeightMeters = round3(run.heightMeters), recentYellowLeftEdge = atEdge)
    }

    /**
     * Step 0 of [observeRun], the memory behind [LaneLayout.recentYellowLeftEdge] (see
     * [WorldModelConfig.laneYellowEdgeMemorySeconds]): a run with a yellow line left of the car's track sets the edge to
     * the nearest such line (the one the oncoming cut uses); a run without one moves it to the run's line nearest to it
     * within [WorldModelConfig.laneYellowEdgeMatchMeters]. It works on the run's own lines, not the tracks: when a turn
     * bay opens the yellow edge flares away from the car faster than its track follows, and the track can take the new
     * dashed line instead (real_009 at 8.2 s).
     */
    private fun followYellowEdge(run: LaneRun) {
        val t = run.measuredPts
        val yellow = run.lines.filter { it.left && it.color == LaneLine.YELLOW }.maxByOrNull { it.lateral }
        // Lateral offsets scale with the camera height, as the tracks' do.
        val edge = yellowEdge?.let { if (yellowEdgeHeight > 0.0) it * run.heightMeters / yellowEdgeHeight else it }
        yellowEdge = edge; yellowEdgeHeight = run.heightMeters
        if (yellow != null) {
            yellowEdge = yellow.lateral; yellowEdgeYellowPts = t; yellowEdgeSeenPts = t
        } else if (edge != null) {
            run.lines.minByOrNull { abs(it.lateral - edge) }?.takeIf { abs(it.lateral - edge) <= config.laneYellowEdgeMatchMeters }?.let {
                yellowEdge = it.lateral; yellowEdgeSeenPts = t
            }
        }
        if (yellowEdge != null && (t - yellowEdgeYellowPts > config.laneYellowEdgeMemorySeconds || t - yellowEdgeSeenPts > config.laneYellowEdgeHoldSeconds)) {
            yellowEdge = null
        }
    }

    /** Step 2 of [observeRun]: the common lateral shift of the tracks towards the run's [lines] (0 without tracks or a match). */
    private fun commonShift(lines: List<LaneCandidate>): Double {
        val ref = lineTracks.filter { it.confirmed }.ifEmpty { lineTracks }
        if (ref.isEmpty() || lines.isEmpty()) return 0.0
        val gate = config.laneTrackMatchMeters
        val candidates = listOf(0.0) + lines.flatMap { l -> ref.map { l.lateral - it.lateral } }.filter { abs(it) <= config.laneTrackMaxShiftMeters }
        var best: List<Triple<Int, Int, Double>> = emptyList()
        var bestMiss = Double.MAX_VALUE
        for (c in candidates) {
            val m = matches(lines, ref, c)
            if (abs(c) > gate && m.size < 2) continue
            val miss = m.sumOf { abs(it.third) }
            if (m.size > best.size || (m.size == best.size && miss < bestMiss)) { best = m; bestMiss = miss }
        }
        if (best.isEmpty()) return 0.0
        return RobustFit.median(best.mapTo(ArrayList()) { lines[it.first].lateral - ref[it.second].lateral })
    }

    /**
     * One-to-one (line index, track index, miss) pairs of [lines] and [tracks] shifted by [shift] within
     * [WorldModelConfig.laneTrackMatchMeters], nearest first.
     */
    private fun matches(lines: List<LaneCandidate>, tracks: List<LineTrack>, shift: Double): List<Triple<Int, Int, Double>> {
        val all = ArrayList<Triple<Int, Int, Double>>()
        for (i in lines.indices) for (j in tracks.indices) {
            val miss = lines[i].lateral - (tracks[j].lateral + shift)
            if (abs(miss) <= config.laneTrackMatchMeters) all += Triple(i, j, miss)
        }
        all.sortBy { abs(it.third) }
        val usedLines = HashSet<Int>()
        val usedTracks = HashSet<Int>()
        return all.filter { (i, j, _) -> i !in usedLines && j !in usedTracks && usedLines.add(i) && usedTracks.add(j) }
    }

    /** Neighbouring tracks closer than [LaneLayoutParams.mergeMeters] become the stronger one (counted, then seen more often). */
    private fun mergeTracks() {
        lineTracks.sortBy { it.lateral }
        var i = 0
        while (i < lineTracks.lastIndex) {
            val a = lineTracks[i]
            val b = lineTracks[i + 1]
            if (b.lateral - a.lateral >= config.laneLayout.mergeMeters) { i++; continue }
            val keepA = compareValuesBy(a, b, { it.confirmed }, { it.sightings }) >= 0
            val (keep, drop) = if (keepA) a to b else b to a
            keep.absorb(drop)
            lineTracks.remove(drop)
        }
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

    /** One lane line followed across lanes runs ([observeRun]); [lateral] = metres across the road from the camera's track. */
    private inner class LineTrack(first: LaneCandidate, val firstSeenPts: Double) {
        val id = nextLineTrackId++
        var lateral = first.lateral
        /** Left of the car's track (sticky within [WorldModelConfig.laneTrackEgoHysteresisMeters] of it). */
        var left = first.lateral <= 0.0
        /** In the last published layout, and the track right of it there with [lanesToRight] lanes between (-1 = none). */
        var inLayout = false
        var rightNeighbour = -1
        var lanesToRight = 0
        var lastSeenPts = firstSeenPts
        /** Sightings, each at most [WorldModelConfig.laneTrackConfirmSeconds] after the previous one, until [confirmed]. */
        var sightings = 1
        var confirmed = config.laneTrackConfirmSightings <= 1
        private var maxY = first.maxY
        private var span = first.span
        private var weight = first.weight
        private var yellowVotes = 0.0
        private var whiteVotes = 0.0
        private var colored = false

        init { vote(first.color) }

        /**
         * Paint colour over the track's sightings, recent ones weighing more ([WorldModelConfig.laneTrackColorDecay]):
         * yellow when its vote is at least white's; null = never any colour.
         */
        val color: String? get() = when {
            yellowVotes > 0.0 && yellowVotes >= whiteVotes -> LaneLine.YELLOW
            whiteVotes > 0.0 -> LaneLine.WHITE
            colored -> LaneLine.UNKNOWN
            else -> null
        }

        fun see(c: LaneCandidate, t: Double) {
            if (!confirmed) {
                sightings = if (t - lastSeenPts <= config.laneTrackConfirmSeconds) sightings + 1 else 1
                if (sightings >= config.laneTrackConfirmSightings) confirmed = true
            } else {
                sightings++
            }
            lateral += config.laneLayoutEmaAlpha * (c.lateral - lateral)
            lastSeenPts = maxOf(lastSeenPts, t)
            maxY = c.maxY; span = c.span; weight = c.weight
            vote(c.color)
        }

        /** Takes over [o] (the same line): its colour votes, and its last sighting when that is newer (the position stays). */
        fun absorb(o: LineTrack) {
            if (o.lastSeenPts > lastSeenPts) { lastSeenPts = o.lastSeenPts; maxY = o.maxY; span = o.span; weight = o.weight }
            yellowVotes += o.yellowVotes; whiteVotes += o.whiteVotes; colored = colored || o.colored
        }

        fun updateSide() {
            val h = config.laneTrackEgoHysteresisMeters
            if (lateral < -h) left = true else if (lateral > h) left = false
        }

        /** A sighting without a colour, or an unknown one, leaves the votes as they are. */
        private fun vote(c: String?) {
            if (c == null) return
            colored = true
            if (c != LaneLine.YELLOW && c != LaneLine.WHITE) return
            yellowVotes *= config.laneTrackColorDecay
            whiteVotes *= config.laneTrackColorDecay
            if (c == LaneLine.YELLOW) yellowVotes += 1.0 else whiteVotes += 1.0
        }

        /** The track as a line through a vanishing point with [metersPerSlope] metres across the road per unit of slope. */
        fun candidate(metersPerSlope: Double): LaneCandidate {
            val slope = round4(lateral / metersPerSlope)
            return LaneCandidate(slope, slope * metersPerSlope, true, maxY, span, weight, color, left, id, rightNeighbour, lanesToRight)
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
        fun round4(x: Double) = Math.round(x * 10000.0) / 10000.0
    }
}
