package com.drivingassist.copilot.context

/**
 * Theil-Sen line fit: slope = median of pairwise slopes, level = median of residual intercepts.
 * Robust to the occasional bad depth sample (up to ~29 % outliers). O(n^2) with n <= ~40.
 */
internal object RobustFit {
    data class Line(val slope: Double, val valueAtT: Double)

    /** Fit (t, v) samples and evaluate the line at [atT]. Returns null for fewer than 2 distinct times. */
    fun theilSen(t: DoubleArray, v: DoubleArray, n: Int, atT: Double): Line? {
        if (n < 2) return null
        val slopes = ArrayList<Double>(n * (n - 1) / 2)
        for (i in 0 until n) for (j in i + 1 until n) {
            val dt = t[j] - t[i]
            if (dt > 1e-6) slopes.add((v[j] - v[i]) / dt)
        }
        if (slopes.isEmpty()) return null
        val slope = median(slopes)
        val levels = ArrayList<Double>(n)
        for (i in 0 until n) levels.add(v[i] + slope * (atT - t[i]))
        return Line(slope, median(levels))
    }

    fun median(values: MutableList<Double>): Double {
        values.sort()
        val m = values.size / 2
        return if (values.size % 2 == 1) values[m] else (values[m - 1] + values[m]) / 2.0
    }
}
