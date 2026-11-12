package com.example.heartzonemonitor.domain.calculator

import com.example.heartzonemonitor.domain.model.HeartZone
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class ZoneCalculatorTest {

    private val calculator = ZoneCalculator()

    @Test
    fun calculateHrmax_usesTanakaFormula_whenNoOverrideProvided() {
        // Age 20: 208 - 0.7 * 20 = 194
        assertEquals(194, calculator.calculateHrmax(20))
        // Age 40: 208 - 0.7 * 40 = 180
        assertEquals(180, calculator.calculateHrmax(40))
        // Age 60: 208 - 0.7 * 60 = 166
        assertEquals(166, calculator.calculateHrmax(60))
    }

    @Test
    fun calculateHrmax_usesManualOverride_whenProvided() {
        assertEquals(190, calculator.calculateHrmax(age = 40, customHrmax = 190))
    }

    @Test
    fun calculateZones_usesKarvonenHRR_whenRestingHrIsProvided() {
        // Age 40 (HRmax = 180), Resting HR = 60, HRR = 120
        // Z1: 50%-60% -> 60 + 0.50*120 = 120 to 60 + 0.60*120 = 132
        // Z2: 60%-70% -> 132 to 60 + 0.70*120 = 144
        // Z3: 70%-80% -> 144 to 60 + 0.80*120 = 156
        // Z4: 80%-90% -> 156 to 60 + 0.90*120 = 168
        // Z5: 90%-100% -> 168 to 60 + 1.00*120 = 180
        val zones = calculator.calculateZones(age = 40, restingHr = 60, customHrmax = null)
        assertEquals(5, zones.size)
        assertEquals(ZoneCalculationMode.KARVONEN, zones.calculationMode)
        assertEquals(180, zones.hrMax)

        assertEquals(120, zones.zone1.minBpm)
        assertEquals(132, zones.zone1.maxBpm)

        assertEquals(132, zones.zone2.minBpm)
        assertEquals(144, zones.zone2.maxBpm)

        assertEquals(144, zones.zone3.minBpm)
        assertEquals(156, zones.zone3.maxBpm)

        assertEquals(156, zones.zone4.minBpm)
        assertEquals(168, zones.zone4.maxBpm)

        assertEquals(168, zones.zone5.minBpm)
        assertEquals(180, zones.zone5.maxBpm)
    }

    @Test
    fun calculateZones_fallsBackToPercentHrmax_whenRestingHrIsNull() {
        // Age 40 (HRmax = 180), Resting HR = null
        // Z1: 50%-60% -> 0.50*180 = 90 to 0.60*180 = 108
        // Z2: 60%-70% -> 108 to 0.70*180 = 126
        // Z3: 70%-80% -> 126 to 0.80*180 = 144
        // Z4: 80%-90% -> 144 to 0.90*180 = 162
        // Z5: 90%-100% -> 162 to 1.00*180 = 180
        val zones = calculator.calculateZones(age = 40, restingHr = null, customHrmax = null)
        assertEquals(5, zones.size)
        assertEquals(ZoneCalculationMode.PERCENT_HRMAX, zones.calculationMode)
        assertEquals(180, zones.hrMax)

        assertEquals(90, zones.zone1.minBpm)
        assertEquals(108, zones.zone1.maxBpm)

        assertEquals(108, zones.zone2.minBpm)
        assertEquals(126, zones.zone2.maxBpm)

        assertEquals(126, zones.zone3.minBpm)
        assertEquals(144, zones.zone3.maxBpm)

        assertEquals(144, zones.zone4.minBpm)
        assertEquals(162, zones.zone4.maxBpm)

        assertEquals(162, zones.zone5.minBpm)
        assertEquals(180, zones.zone5.maxBpm)
    }

    @Test
    fun getZoneForBpm_correctlyMapsBpmToHeartZone() {
        val zones = calculator.calculateZones(age = 40, restingHr = 60, customHrmax = null)
        // Below Z1 (< 120)
        assertNull(zones.getZoneForBpm(100))
        assertNull(zones.getZoneForBpm(119))

        // Z1: [120, 132)
        assertEquals(HeartZone.ZONE_1, zones.getZoneForBpm(120))
        assertEquals(HeartZone.ZONE_1, zones.getZoneForBpm(125))
        assertEquals(HeartZone.ZONE_1, zones.getZoneForBpm(131))

        // Z2: [132, 144)
        assertEquals(HeartZone.ZONE_2, zones.getZoneForBpm(132))
        assertEquals(HeartZone.ZONE_2, zones.getZoneForBpm(140))

        // Z3: [144, 156)
        assertEquals(HeartZone.ZONE_3, zones.getZoneForBpm(144))
        assertEquals(HeartZone.ZONE_3, zones.getZoneForBpm(150))

        // Z4: [156, 168)
        assertEquals(HeartZone.ZONE_4, zones.getZoneForBpm(156))
        assertEquals(HeartZone.ZONE_4, zones.getZoneForBpm(160))

        // Z5: [168, 180+]
        assertEquals(HeartZone.ZONE_5, zones.getZoneForBpm(168))
        assertEquals(HeartZone.ZONE_5, zones.getZoneForBpm(180))
        assertEquals(HeartZone.ZONE_5, zones.getZoneForBpm(195))
    }

    @Test
    fun heartZoneColors_matchDesignatedPalette() {
        assertEquals(0xFF64748B, HeartZone.ZONE_1.colorArgb)
        assertEquals(0xFF3B82F6, HeartZone.ZONE_2.colorArgb)
        assertEquals(0xFF10B981, HeartZone.ZONE_3.colorArgb)
        assertEquals(0xFFF59E0B, HeartZone.ZONE_4.colorArgb)
        assertEquals(0xFFEF4444, HeartZone.ZONE_5.colorArgb)

        assertEquals("#64748B", HeartZone.ZONE_1.colorHex)
        assertEquals("#3B82F6", HeartZone.ZONE_2.colorHex)
        assertEquals("#10B981", HeartZone.ZONE_3.colorHex)
        assertEquals("#F59E0B", HeartZone.ZONE_4.colorHex)
        assertEquals("#EF4444", HeartZone.ZONE_5.colorHex)
    }

    @Test
    fun boundaryTestingAcrossAges10To110_inBothModes() {
        for (age in 10..110) {
            val hrmax = calculator.calculateHrmax(age)
            val expectedHrmax = (208.0 - 0.7 * age).roundToInt()
            assertEquals(expectedHrmax, hrmax)

            // Karvonen mode
            val karvonenZones = calculator.calculateZones(age = age, restingHr = 50)
            assertEquals(5, karvonenZones.size)
            assertTrue(karvonenZones.zone1.minBpm < karvonenZones.zone1.maxBpm)
            assertTrue(karvonenZones.zone1.maxBpm <= karvonenZones.zone2.minBpm)
            assertTrue(karvonenZones.zone2.maxBpm <= karvonenZones.zone3.minBpm)
            assertTrue(karvonenZones.zone3.maxBpm <= karvonenZones.zone4.minBpm)
            assertTrue(karvonenZones.zone4.maxBpm <= karvonenZones.zone5.minBpm)
            assertEquals(hrmax, karvonenZones.zone5.maxBpm)

            // %HRmax fallback mode
            val percentZones = calculator.calculateZones(age = age, restingHr = null)
            assertEquals(5, percentZones.size)
            assertTrue(percentZones.zone1.minBpm < percentZones.zone1.maxBpm)
            assertTrue(percentZones.zone1.maxBpm <= percentZones.zone2.minBpm)
            assertTrue(percentZones.zone2.maxBpm <= percentZones.zone3.minBpm)
            assertTrue(percentZones.zone3.maxBpm <= percentZones.zone4.minBpm)
            assertTrue(percentZones.zone4.maxBpm <= percentZones.zone5.minBpm)
            assertEquals(hrmax, percentZones.zone5.maxBpm)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun calculateZones_throwsException_whenRestingHrExceedsOrEqualsHrmax() {
        calculator.calculateZones(age = 40, restingHr = 180, customHrmax = 180)
    }
}
