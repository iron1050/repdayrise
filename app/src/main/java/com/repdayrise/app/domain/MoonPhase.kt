package com.repdayrise.app.domain

import java.time.LocalDate
import java.time.ZoneOffset

object MoonPhase {
    private const val SYNODIC_MONTH = 29.530588853
    private val REFERENCE_NEW_MOON_EPOCH_SECONDS = 947182440L // 2000-01-06 18:14 UTC

    /** Returns the phase in [0, 1): 0 = new moon, 0.5 = full moon. */
    fun phase(date: LocalDate): Double {
        val seconds = date.atTime(12, 0).toEpochSecond(ZoneOffset.UTC)
        val days = (seconds - REFERENCE_NEW_MOON_EPOCH_SECONDS) / 86400.0
        val cycles = days / SYNODIC_MONTH
        val frac = cycles - Math.floor(cycles)
        return if (frac < 0) frac + 1 else frac
    }

    fun name(date: LocalDate): String {
        val p = phase(date)
        return when {
            p < 0.03 || p > 0.97 -> "New moon"
            p < 0.22 -> "Waxing crescent"
            p < 0.28 -> "First quarter"
            p < 0.47 -> "Waxing gibbous"
            p < 0.53 -> "Full moon"
            p < 0.72 -> "Waning gibbous"
            p < 0.78 -> "Last quarter"
            else -> "Waning crescent"
        }
    }
}
