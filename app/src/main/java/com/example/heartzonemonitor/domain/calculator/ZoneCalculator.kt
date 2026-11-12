package com.example.heartzonemonitor.domain.calculator

import com.example.heartzonemonitor.domain.model.HeartZone
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import com.example.heartzonemonitor.domain.model.ZoneCalculationResult
import com.example.heartzonemonitor.domain.model.ZoneRange
import kotlin.math.roundToInt

class ZoneCalculator {

    fun calculateHrmax(age: Int, customHrmax: Int? = null): Int {
        if (customHrmax != null) {
            return customHrmax
        }
        return (208.0 - 0.7 * age).roundToInt()
    }

    fun calculateZones(
        age: Int,
        restingHr: Int? = null,
        customHrmax: Int? = null
    ): ZoneCalculationResult {
        val hrMax = calculateHrmax(age, customHrmax)
        val mode = if (restingHr != null) {
            require(restingHr < hrMax) {
                "Resting heart rate ($restingHr bpm) must be less than HRmax ($hrMax bpm)"
            }
            ZoneCalculationMode.KARVONEN
        } else {
            ZoneCalculationMode.PERCENT_HRMAX
        }

        val baseHr = restingHr ?: 0
        val reserve = hrMax - baseHr

        val ranges = HeartZone.entries.map { zone ->
            val minBpm = (baseHr + (zone.minPercent * reserve)).roundToInt()
            val maxBpm = (baseHr + (zone.maxPercent * reserve)).roundToInt()
            ZoneRange(zone, minBpm, maxBpm)
        }

        return ZoneCalculationResult(
            hrMax = hrMax,
            calculationMode = mode,
            zones = ranges
        )
    }
}
