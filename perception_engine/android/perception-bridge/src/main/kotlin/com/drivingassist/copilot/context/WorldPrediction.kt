package com.drivingassist.copilot.context

/**
 * Live-mode latency hiding: moves a snapshot forward from the capture time of the analysed camera
 * frame ([FrameTiming.captureTimeNs], client clock) to [displayTimeNs] (same clock), so overlays
 * drawn on the live preview line up with what the camera shows NOW rather than ~80 ms ago.
 *
 * - Boxes: image-space constant velocity ([ObjectState.bboxVelocityPxPerS]), clamped to the image.
 * - Distances: `distance + relativeSpeed * (distanceAge + dt)` (the distance was measured
 *   `distanceAgeSeconds` before the frame), limited to [maxDistanceExtrapolationSeconds], >= 0.1 m.
 * - TTC: reduced by dt (>= 0).
 *
 * Snapshots without a capture time (sim / video modes) are returned unchanged: sim is already in
 * sync with playback. Display estimates only (plan §18, §38).
 *
 * @param maxAheadMs cap on dt; beyond it the result is stale anyway.
 */
fun WorldSnapshot.predictedAt(
    displayTimeNs: Long,
    maxAheadMs: Double = 300.0,
    maxDistanceExtrapolationSeconds: Double = 1.0,
): WorldSnapshot {
    val capture = timing?.captureTimeNs ?: return this
    val dtMs = ((displayTimeNs - capture) / 1e6).coerceIn(0.0, maxAheadMs)
    val dt = dtMs / 1000.0
    if (dt <= 0.0) return copy(predictedAheadMs = 0.0)
    val w = image?.width?.toDouble()
    val h = image?.height?.toDouble()
    val moved = objects.mapValues { (_, o) ->
        val v = o.bboxVelocityPxPerS
        val box = if (v != null && v.size == 4 && o.bbox.size == 4) {
            var x1 = o.bbox[0] + v[0] * dt
            var y1 = o.bbox[1] + v[1] * dt
            var x2 = o.bbox[2] + v[2] * dt
            var y2 = o.bbox[3] + v[3] * dt
            if (w != null && h != null) {
                x1 = x1.coerceIn(0.0, w); x2 = x2.coerceIn(0.0, w)
                y1 = y1.coerceIn(0.0, h); y2 = y2.coerceIn(0.0, h)
            }
            if (x2 > x1 && y2 > y1) listOf(r1(x1), r1(y1), r1(x2), r1(y2)) else o.bbox
        } else {
            o.bbox
        }
        val distance = o.distanceMeters?.let { d ->
            val rel = o.relativeSpeedMps ?: return@let d
            val horizon = ((o.distanceAgeSeconds ?: 0.0) + dt).coerceAtMost(maxDistanceExtrapolationSeconds)
            r2((d + rel * horizon).coerceAtLeast(0.1))
        }
        val ttc = o.ttcSeconds?.let { r2((it - dt).coerceAtLeast(0.0)) }
        o.copy(bbox = box, distanceMeters = distance, ttcSeconds = ttc)
    }
    return copy(objects = moved, predictedAheadMs = r1(dtMs))
}

private fun r1(x: Double) = Math.round(x * 10.0) / 10.0
private fun r2(x: Double) = Math.round(x * 100.0) / 100.0
