package com.drivingassist.spatialcopilot.voice

import com.drivingassist.copilot.context.Maneuver
import com.drivingassist.spatialcopilot.nav.RouteGuide
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Text rendering for navigation prompts (AUDIO_CUE_RULES.md section 6.6-6.7, 8): numbers as words,
 * US customary distances with the catalog's rounding, sentence case, a final period, no digits.
 * Object distances, TTC and lane numbers are never spoken; exit numbers are, as words.
 */
object SpokenText {
    private const val FEET_PER_METER = 3.28084
    private const val METERS_PER_MILE = 1609.344

    /** "three hundred feet", "a quarter mile", "one and a half miles"; null below ~95 ft (not spoken). */
    fun distance(meters: Double): String? {
        if (meters.isNaN() || meters <= 0.0) return null
        val feet = meters * FEET_PER_METER
        if (feet < 95.0) return null
        val roundedFeet = floor(feet / 100.0 + 0.5).toInt() * 100
        if (roundedFeet <= 1000) return if (roundedFeet == 1000) "one thousand feet" else "${words(roundedFeet / 100)} hundred feet"
        val miles = meters / METERS_PER_MILE
        return when {
            miles < 0.625 -> if (miles < 0.375) "a quarter mile" else "half a mile"
            miles <= 2.0 -> when (floor(miles * 2 + 0.5) / 2) {
                0.5 -> "half a mile"
                1.0 -> "one mile"
                1.5 -> "one and a half miles"
                else -> "two miles"
            }
            else -> "${words(miles.roundToInt())} miles"
        }
    }

    /** Lower-case maneuver phrase for "In {distance}, {maneuver}."; null = no spoken maneuver. */
    fun maneuver(m: Maneuver): String? = when (m) {
        Maneuver.TURN_LEFT -> "turn left"
        Maneuver.TURN_RIGHT -> "turn right"
        Maneuver.KEEP_LEFT -> "keep left"
        Maneuver.KEEP_RIGHT -> "keep right"
        Maneuver.MERGE -> "merge"
        Maneuver.MERGE_LEFT -> "merge left"
        Maneuver.MERGE_RIGHT -> "merge right"
        Maneuver.EXIT -> "take the exit"
        Maneuver.ENTER_HIGHWAY -> "take the ramp"
        Maneuver.ARRIVE, Maneuver.STOP, Maneuver.FOLLOW_ROAD -> null
    }

    /** The banked street-less immediate forms ("Turn right."), all in the catalog's fixed phrases. */
    fun immediate(m: Maneuver): String? = maneuver(m)?.let { sentence(it) }

    fun prepare(distanceWords: String, m: Maneuver): String? = maneuver(m)?.let { "In $distanceWords, $it." }

    /**
     * The route's maneuver phrase. An exit ([RouteGuide.isExit]: EXIT, or a ramp with an exit number) is
     * "take exit ninety-four" when [withExitNumber] and [exitNumberWords] can say the number, else "take the exit".
     */
    fun maneuver(r: RouteGuide, withExitNumber: Boolean = true): String? =
        if (r.isExit) exit(r.exitNumber.takeIf { withExitNumber }) else maneuver(r.maneuver)

    fun exit(exitNumber: String?): String = exitNumberWords(exitNumber)?.let { "take exit $it" } ?: "take the exit"

    /** "Take exit ninety-four.", "Turn right."; null = no spoken maneuver. */
    fun immediate(r: RouteGuide, withExitNumber: Boolean = true): String? = maneuver(r, withExitNumber)?.let { sentence(it) }

    /** "In half a mile, take exit ninety-four." */
    fun prepare(distanceWords: String, r: RouteGuide, withExitNumber: Boolean = true): String? =
        maneuver(r, withExitNumber)?.let { "In $distanceWords, $it." }

    /**
     * Exit numbers matching `^(\d{1,3})([A-Za-z])?$`: 1-99 in words ("ninety-four"), 100-999 in groups
     * ("two fifty", "one oh five", "three hundred"), then the letter as a capital ("twenty-three B").
     * Null for anything else (0, 1000, "12-14", "I-85"): the phrase falls back to "take the exit".
     */
    fun exitNumberWords(exitNumber: String?): String? {
        val m = EXIT_NUMBER.matchEntire(exitNumber?.trim() ?: return null) ?: return null
        val n = m.groupValues[1].toInt()
        val number = when (n) {
            in 1..99 -> words(n)
            in 100..999 -> {
                val rest = n % 100
                val hundreds = words(n / 100)
                when {
                    rest == 0 -> "$hundreds hundred"
                    rest < 10 -> "$hundreds oh ${words(rest)}"
                    else -> "$hundreds ${words(rest)}"
                }
            }
            else -> return null
        }
        val letter = m.groupValues[2].uppercase()
        return if (letter.isEmpty()) number else "$number $letter"
    }

    private val EXIT_NUMBER = Regex("^(\\d{1,3})([A-Za-z])?$")

    fun arrivePrepare(distanceWords: String): String = "In $distanceWords, you will arrive at your destination."

    fun sentence(s: String): String = s.replaceFirstChar { it.uppercaseChar() }.let { if (it.endsWith('.')) it else "$it." }

    private val ONES = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    /** 0..99 in words, hyphenated 21-99 ("twenty-five"). */
    fun words(n: Int): String = when {
        n < 20 -> ONES[n.coerceAtLeast(0)]
        n < 100 -> TENS[n / 10] + if (n % 10 != 0) "-" + ONES[n % 10] else ""
        else -> n.toString() // not reached: prompts above 99 miles are not spoken
    }
}
