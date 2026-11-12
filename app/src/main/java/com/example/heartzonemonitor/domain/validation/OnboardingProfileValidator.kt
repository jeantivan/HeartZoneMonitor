package com.example.heartzonemonitor.domain.validation

import com.example.heartzonemonitor.domain.calculator.ZoneCalculator
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import com.example.heartzonemonitor.domain.model.ZoneCalculationResult

data class ProfileValidationResult(
    val isValid: Boolean,
    val validatedBirthYear: Int? = null,
    val evaluatedAge: Int? = null,
    val validatedRestingHr: Int? = null,
    val validatedCustomHrmax: Int? = null,
    val calculationMode: ZoneCalculationMode = ZoneCalculationMode.PERCENT_HRMAX,
    val birthYearError: String? = null,
    val restingHrError: String? = null,
    val customHrmaxError: String? = null,
    val previewResult: ZoneCalculationResult? = null
)

class OnboardingProfileValidator(
    private val zoneCalculator: ZoneCalculator = ZoneCalculator()
) {

    fun validate(
        birthYearInput: String,
        restingHrInput: String,
        isCustomHrmaxEnabled: Boolean,
        customHrmaxInput: String,
        currentYear: Int
    ): ProfileValidationResult {
        var birthYearError: String? = null
        var validatedBirthYear: Int? = null
        var evaluatedAge: Int? = null

        val birthYearTrimmed = birthYearInput.trim()
        if (birthYearTrimmed.isEmpty()) {
            birthYearError = "Birth year is required"
        } else {
            val parsedYear = birthYearTrimmed.toIntOrNull()
            if (parsedYear == null) {
                birthYearError = "Birth year must be a valid number"
            } else {
                val age = currentYear - parsedYear
                if (age !in 10..110) {
                    birthYearError = "Age must be between 10 and 110"
                } else {
                    validatedBirthYear = parsedYear
                    evaluatedAge = age
                }
            }
        }

        var restingHrError: String? = null
        var validatedRestingHr: Int? = null
        val restingHrTrimmed = restingHrInput.trim()
        if (restingHrTrimmed.isNotEmpty()) {
            val parsedRestingHr = restingHrTrimmed.toIntOrNull()
            if (parsedRestingHr == null || parsedRestingHr !in 30..120) {
                restingHrError = "Resting heart rate must be between 30 and 120 BPM"
            } else {
                validatedRestingHr = parsedRestingHr
            }
        }

        var customHrmaxError: String? = null
        var validatedCustomHrmax: Int? = null
        if (isCustomHrmaxEnabled) {
            val customHrmaxTrimmed = customHrmaxInput.trim()
            if (customHrmaxTrimmed.isEmpty()) {
                customHrmaxError = "Custom HRmax is required when override is enabled"
            } else {
                val parsedCustomHrmax = customHrmaxTrimmed.toIntOrNull()
                if (parsedCustomHrmax == null || parsedCustomHrmax !in 100..240) {
                    customHrmaxError = "Custom HRmax must be between 100 and 240 BPM"
                } else if (validatedRestingHr != null && parsedCustomHrmax <= validatedRestingHr + 20) {
                    customHrmaxError = "Custom HRmax must be greater than resting heart rate + 20 BPM"
                } else {
                    validatedCustomHrmax = parsedCustomHrmax
                }
            }
        }

        val calculationMode = if (validatedRestingHr != null) {
            ZoneCalculationMode.KARVONEN
        } else {
            ZoneCalculationMode.PERCENT_HRMAX
        }

        val isValid = birthYearError == null &&
            restingHrError == null &&
            (!isCustomHrmaxEnabled || customHrmaxError == null) &&
            validatedBirthYear != null

        var tanakaHrmax: Int? = null
        val previewResult = if (evaluatedAge != null) {
            tanakaHrmax = zoneCalculator.calculateHrmax(evaluatedAge)
            val effectiveHrmax = if (isCustomHrmaxEnabled && validatedCustomHrmax != null) {
                validatedCustomHrmax
            } else {
                null
            }
            try {
                zoneCalculator.calculateZones(
                    age = evaluatedAge,
                    restingHr = validatedRestingHr,
                    customHrmax = effectiveHrmax
                )
            } catch (_: IllegalArgumentException) {
                // Fall back to Tanaka %HRmax if resting HR temporarily exceeds HRmax during input
                zoneCalculator.calculateZones(age = evaluatedAge)
            }
        } else {
            null
        }

        return ProfileValidationResult(
            isValid = isValid,
            validatedBirthYear = validatedBirthYear,
            evaluatedAge = evaluatedAge,
            validatedRestingHr = validatedRestingHr,
            validatedCustomHrmax = if (isCustomHrmaxEnabled) validatedCustomHrmax else null,
            calculationMode = calculationMode,
            birthYearError = birthYearError,
            restingHrError = restingHrError,
            customHrmaxError = customHrmaxError,
            previewResult = previewResult
        )
    }
}
