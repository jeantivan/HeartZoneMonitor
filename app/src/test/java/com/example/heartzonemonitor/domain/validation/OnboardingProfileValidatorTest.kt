package com.example.heartzonemonitor.domain.validation

import com.example.heartzonemonitor.domain.calculator.ZoneCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingProfileValidatorTest {

    private val zoneCalculator = ZoneCalculator()
    private val validator = OnboardingProfileValidator(zoneCalculator)
    private val currentYear = 2026

    @Test
    fun `validate - empty birth year reports error and is invalid`() {
        val result = validator.validate(
            birthYearInput = "",
            restingHrInput = "",
            isCustomHrmaxEnabled = false,
            customHrmaxInput = "",
            currentYear = currentYear
        )

        assertFalse(result.isValid)
        assertNotNull(result.birthYearError)
        assertNull(result.validatedBirthYear)
    }

    @Test
    fun `validate - birth year younger than age 10 reports error`() {
        // currentYear = 2026, birthYear = 2017 -> age 9
        val result = validator.validate(
            birthYearInput = "2017",
            restingHrInput = "",
            isCustomHrmaxEnabled = false,
            customHrmaxInput = "",
            currentYear = currentYear
        )

        assertFalse(result.isValid)
        assertEquals("Age must be between 10 and 110", result.birthYearError)
        assertNull(result.validatedBirthYear)
    }

    @Test
    fun `validate - birth year older than age 110 reports error`() {
        // currentYear = 2026, birthYear = 1915 -> age 111
        val result = validator.validate(
            birthYearInput = "1915",
            restingHrInput = "",
            isCustomHrmaxEnabled = false,
            customHrmaxInput = "",
            currentYear = currentYear
        )

        assertFalse(result.isValid)
        assertEquals("Age must be between 10 and 110", result.birthYearError)
        assertNull(result.validatedBirthYear)
    }

    @Test
    fun `validate - valid birth year with age between 10 and 110 succeeds`() {
        // currentYear = 2026, birthYear = 1988 -> age 38
        val result = validator.validate(
            birthYearInput = "1988",
            restingHrInput = "",
            isCustomHrmaxEnabled = false,
            customHrmaxInput = "",
            currentYear = currentYear
        )

        assertTrue(result.isValid)
        assertNull(result.birthYearError)
        assertEquals(1988, result.validatedBirthYear)
        assertEquals(38, result.evaluatedAge)
    }

    @Test
    fun `validate - resting heart rate omitted defaults to PERCENT_HRMAX mode and is valid`() {
        val result = validator.validate(
            birthYearInput = "1988",
            restingHrInput = "",
            isCustomHrmaxEnabled = false,
            customHrmaxInput = "",
            currentYear = currentYear
        )

        assertTrue(result.isValid)
        assertNull(result.restingHrError)
        assertNull(result.validatedRestingHr)
        assertEquals(com.example.heartzonemonitor.domain.model.ZoneCalculationMode.PERCENT_HRMAX, result.calculationMode)
    }

    @Test
    fun `validate - resting heart rate below 30 reports error`() {
        val result = validator.validate(
            birthYearInput = "1988",
            restingHrInput = "29",
            isCustomHrmaxEnabled = false,
            customHrmaxInput = "",
            currentYear = currentYear
        )

        assertFalse(result.isValid)
        assertEquals("Resting heart rate must be between 30 and 120 BPM", result.restingHrError)
    }

    @Test
    fun `validate - resting heart rate above 120 reports error`() {
        val result = validator.validate(
            birthYearInput = "1988",
            restingHrInput = "121",
            isCustomHrmaxEnabled = false,
            customHrmaxInput = "",
            currentYear = currentYear
        )

        assertFalse(result.isValid)
        assertEquals("Resting heart rate must be between 30 and 120 BPM", result.restingHrError)
    }

    @Test
    fun `validate - valid resting heart rate selects KARVONEN calculation mode`() {
        val result = validator.validate(
            birthYearInput = "1988",
            restingHrInput = "60",
            isCustomHrmaxEnabled = false,
            customHrmaxInput = "",
            currentYear = currentYear
        )

        assertTrue(result.isValid)
        assertNull(result.restingHrError)
        assertEquals(60, result.validatedRestingHr)
        assertEquals(com.example.heartzonemonitor.domain.model.ZoneCalculationMode.KARVONEN, result.calculationMode)
    }

    @Test
    fun `validate - custom hrmax enabled but empty reports error while maintaining Tanaka baseline preview`() {
        val result = validator.validate(
            birthYearInput = "1988",
            restingHrInput = "60",
            isCustomHrmaxEnabled = true,
            customHrmaxInput = "",
            currentYear = currentYear
        )

        assertFalse(result.isValid)
        assertEquals("Custom HRmax is required when override is enabled", result.customHrmaxError)
        assertNotNull(result.previewResult)
        assertEquals(181, result.previewResult?.hrMax)
    }

    @Test
    fun `validate - custom hrmax below 100 reports error`() {
        val result = validator.validate(
            birthYearInput = "1988",
            restingHrInput = "60",
            isCustomHrmaxEnabled = true,
            customHrmaxInput = "99",
            currentYear = currentYear
        )

        assertFalse(result.isValid)
        assertEquals("Custom HRmax must be between 100 and 240 BPM", result.customHrmaxError)
    }

    @Test
    fun `validate - custom hrmax above 240 reports error`() {
        val result = validator.validate(
            birthYearInput = "1988",
            restingHrInput = "60",
            isCustomHrmaxEnabled = true,
            customHrmaxInput = "241",
            currentYear = currentYear
        )

        assertFalse(result.isValid)
        assertEquals("Custom HRmax must be between 100 and 240 BPM", result.customHrmaxError)
    }

    @Test
    fun `validate - custom hrmax not strictly greater than resting heart rate plus 20 reports error`() {
        // restingHr = 85, 85 + 20 = 105. customHrmax = 105 is not > 105.
        val result = validator.validate(
            birthYearInput = "1988",
            restingHrInput = "85",
            isCustomHrmaxEnabled = true,
            customHrmaxInput = "105",
            currentYear = currentYear
        )

        assertFalse(result.isValid)
        assertEquals("Custom HRmax must be greater than resting heart rate + 20 BPM", result.customHrmaxError)
    }

    @Test
    fun `validate - valid inputs without custom hrmax calculates Tanaka zones preview`() {
        // age = 38 (1988 in 2026). Tanaka = round(208 - 0.7 * 38) = round(208 - 26.6) = 181
        // restingHr = 60. Karvonen mode.
        val result = validator.validate(
            birthYearInput = "1988",
            restingHrInput = "60",
            isCustomHrmaxEnabled = false,
            customHrmaxInput = "",
            currentYear = currentYear
        )

        assertTrue(result.isValid)
        assertNotNull(result.previewResult)
        assertEquals(181, result.previewResult?.hrMax)
        assertEquals(com.example.heartzonemonitor.domain.model.ZoneCalculationMode.KARVONEN, result.previewResult?.calculationMode)
        assertEquals(5, result.previewResult?.zones?.size)
    }

    @Test
    fun `validate - valid inputs with custom hrmax calculates override zones preview`() {
        val result = validator.validate(
            birthYearInput = "1988",
            restingHrInput = "60",
            isCustomHrmaxEnabled = true,
            customHrmaxInput = "195",
            currentYear = currentYear
        )

        assertTrue(result.isValid)
        assertNotNull(result.previewResult)
        assertEquals(195, result.previewResult?.hrMax)
        assertEquals(195, result.validatedCustomHrmax)
        assertEquals(com.example.heartzonemonitor.domain.model.ZoneCalculationMode.KARVONEN, result.previewResult?.calculationMode)
        assertEquals(5, result.previewResult?.zones?.size)
    }
}
