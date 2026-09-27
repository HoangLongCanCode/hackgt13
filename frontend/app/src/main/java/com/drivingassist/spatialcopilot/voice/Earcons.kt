package com.drivingassist.spatialcopilot.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Earcons generated in code (catalog `earcons`), 16-bit mono PCM at [RATE]; no sound files.
 * E1 (CRITICAL): 55 ms pulses of 1000 Hz + 3000 Hz at -6 dB, 40 ms gaps. E2 (TRAFFIC): 880 Hz then 660 Hz,
 * 100 ms each, 40 ms gap, 2nd harmonic at -8 dB. 5 ms raised-cosine ramps.
 */
object Earcons {
    const val RATE = 24_000

    /** E1 before speech: 2 pulses, then 40 ms. */
    val e1BeforeSpeech: ShortArray by lazy { concat(pulses(2), silence(40)) }

    /** E1 alone: 3 pulses. */
    val e1Alone: ShortArray by lazy { pulses(3) }

    /** E2 before speech: the two tones, then 60 ms. */
    val e2BeforeSpeech: ShortArray by lazy { concat(e2(), silence(60)) }

    val e2Alone: ShortArray by lazy { e2() }

    fun lead(earcon: String?, alone: Boolean): ShortArray? = when (earcon) {
        "E1" -> if (alone) e1Alone else e1BeforeSpeech
        "E2" -> if (alone) e2Alone else e2BeforeSpeech
        else -> null
    }

    private fun pulses(n: Int): ShortArray {
        val parts = ArrayList<ShortArray>()
        repeat(n) { i ->
            if (i > 0) parts += silence(40)
            parts += tone(55, listOf(1000.0 to 1.0, 3000.0 to db(-6.0)), peakDbfs = -1.0)
        }
        return concat(*parts.toTypedArray())
    }

    private fun e2(): ShortArray = concat(
        tone(100, listOf(880.0 to 1.0, 1760.0 to db(-8.0)), peakDbfs = -4.0),
        silence(40),
        tone(100, listOf(660.0 to 1.0, 1320.0 to db(-8.0)), peakDbfs = -4.0),
    )

    fun silence(ms: Int): ShortArray = ShortArray(RATE * ms / 1000)

    private fun tone(ms: Int, partials: List<Pair<Double, Double>>, peakDbfs: Double): ShortArray {
        val n = RATE * ms / 1000
        val ramp = RATE * 5 / 1000
        val norm = partials.sumOf { it.second }
        val peak = db(peakDbfs) * Short.MAX_VALUE
        return ShortArray(n) { i ->
            val t = i.toDouble() / RATE
            var v = 0.0
            for ((hz, g) in partials) v += g * sin(2 * PI * hz * t)
            val env = when {
                i < ramp -> 0.5 - 0.5 * cos(PI * i / ramp)
                i > n - ramp -> 0.5 - 0.5 * cos(PI * (n - i) / ramp)
                else -> 1.0
            }
            (v / norm * env * peak).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun db(x: Double) = 10.0.pow(x / 20.0)

    fun concat(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }
}
