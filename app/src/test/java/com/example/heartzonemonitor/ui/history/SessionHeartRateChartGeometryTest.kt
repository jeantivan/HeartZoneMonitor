package com.example.heartzonemonitor.ui.history

import com.example.heartzonemonitor.domain.model.HeartRateSample
import com.example.heartzonemonitor.domain.model.HeartZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHeartRateChartGeometryTest {

    private val sampleThresholds = listOf(
        ZoneThresholdLine(zone = HeartZone.ZONE_1, bpm = 120),
        ZoneThresholdLine(zone = HeartZone.ZONE_2, bpm = 132),
        ZoneThresholdLine(zone = HeartZone.ZONE_3, bpm = 144),
        ZoneThresholdLine(zone = HeartZone.ZONE_4, bpm = 156),
        ZoneThresholdLine(zone = HeartZone.ZONE_5, bpm = 169)
    )

    @Test
    fun `keeps raw samples at or below 1000 and applies LTTB above 1000 while enclosing thresholds`() {
        val baseTs = 1_700_000_000_000L
        val rawUnderLimit = (0 until 1_000).map { sec ->
            HeartRateSample(
                id = sec + 1L,
                sessionId = "s-1000",
                timestampEpochMs = baseTs + sec * 1_000L,
                bpm = 130 + (sec % 30),
                zoneIndex = 3,
                sensorContact = true
            )
        }

        val underModel = buildSessionChartModel(
            samples = rawUnderLimit,
            sessionStartEpochMs = baseTs,
            elapsedDurationMs = 999_000L,
            zoneThresholds = sampleThresholds
        )

        assertFalse(underModel.isDownsampled)
        assertEquals(1_000, underModel.rawSampleCount)
        assertEquals(1_000, underModel.plottedSamples.size)
        assertTrue(underModel.minBpmBound < 120)
        assertTrue(underModel.maxBpmBound > 169)

        val rawOverLimit = (0 until 1_500).map { sec ->
            val bpm = when (sec) {
                777 -> 192
                222 -> 84
                else -> 135 + (sec % 20)
            }
            HeartRateSample(
                id = sec + 1L,
                sessionId = "s-1500",
                timestampEpochMs = baseTs + sec * 1_000L,
                bpm = bpm,
                zoneIndex = 3,
                sensorContact = true
            )
        }

        val overModel = buildSessionChartModel(
            samples = rawOverLimit,
            sessionStartEpochMs = baseTs,
            elapsedDurationMs = 1_499_000L,
            zoneThresholds = sampleThresholds
        )

        assertTrue(overModel.isDownsampled)
        assertEquals(1_500, overModel.rawSampleCount)
        assertEquals(DEFAULT_CHART_LTTB_TARGET_POINTS, overModel.plottedSamples.size)
        assertEquals(rawOverLimit.first(), overModel.plottedSamples.first())
        assertEquals(rawOverLimit.last(), overModel.plottedSamples.last())
        assertTrue(overModel.plottedSamples.any { it.bpm == 192 })
        assertTrue(overModel.plottedSamples.any { it.bpm == 84 })
        assertTrue(overModel.minBpmBound < 84)
        assertTrue(overModel.maxBpmBound > 192)
    }

    @Test
    fun `maps horizontal touch coordinates to nearest sample with MM SS formatting and zone`() {
        val baseTs = 1_700_000_000_000L
        val samples = listOf(
            HeartRateSample(id = 1L, sessionId = "s1", timestampEpochMs = baseTs, bpm = 75, zoneIndex = 1, sensorContact = true),
            HeartRateSample(id = 2L, sessionId = "s1", timestampEpochMs = baseTs + 15 * 60_000L, bpm = 148, zoneIndex = 3, sensorContact = true),
            HeartRateSample(id = 3L, sessionId = "s1", timestampEpochMs = baseTs + 30 * 60_000L, bpm = 162, zoneIndex = 4, sensorContact = true),
            HeartRateSample(id = 4L, sessionId = "s1", timestampEpochMs = baseTs + 60 * 60_000L, bpm = 174, zoneIndex = 5, sensorContact = true)
        )
        val chart = buildSessionChartModel(
            samples = samples,
            sessionStartEpochMs = baseTs,
            elapsedDurationMs = 60 * 60_000L,
            zoneThresholds = sampleThresholds
        )
        val bounds = ChartPlotBounds(left = 40f, top = 20f, right = 440f, bottom = 220f)
        val projection = ChartProjection(chart = chart, plotBounds = bounds)

        // Left edge -> first sample at 00:00
        val startReadout = projection.resolveScrubbedSample(touchX = 40f)!!
        assertEquals("00:00", startReadout.formattedElapsedMmSs)
        assertEquals(75, startReadout.bpm)
        assertEquals(HeartZone.ZONE_1, startReadout.zone)
        assertEquals(40f, startReadout.pointX, 0.01f)

        // Midpoint (50% of 400px -> x = 240f) -> 30:00 sample (162 BPM, Zone 4)
        val midReadout = projection.resolveScrubbedSample(touchX = 240f)!!
        assertEquals("30:00", midReadout.formattedElapsedMmSs)
        assertEquals(162, midReadout.bpm)
        assertEquals(HeartZone.ZONE_4, midReadout.zone)
        assertEquals(240f, midReadout.pointX, 0.01f)

        // Out-of-bounds right -> clamped to last sample at 60:00 (174 BPM, Zone 5)
        val endReadout = projection.resolveScrubbedSample(touchX = 999f)!!
        assertEquals("60:00", endReadout.formattedElapsedMmSs)
        assertEquals(174, endReadout.bpm)
        assertEquals(HeartZone.ZONE_5, endReadout.zone)
        assertEquals(440f, endReadout.pointX, 0.01f)

        // Out-of-bounds left -> clamped to first sample
        val clampedLeft = projection.resolveScrubbedSample(touchX = -50f)!!
        assertEquals("00:00", clampedLeft.formattedElapsedMmSs)
        assertEquals(75, clampedLeft.bpm)

        // Higher BPM projects closer to top (smaller Y coordinate)
        val yLow = projection.projectBpmY(120)
        val yHigh = projection.projectBpmY(169)
        assertTrue("Expected higher BPM ($yHigh) to project above lower BPM ($yLow)", yHigh < yLow)
        assertTrue(yHigh >= bounds.top && yLow <= bounds.bottom)

        // Gradient stops align directly to projected threshold Y fractions even with asymmetric low BPM
        val stops = projection.zoneGradientStops()
        val z1Stop = stops.first { it.second == HeartZone.ZONE_1 && it.first < 1f }
        val expectedZ1Fraction = (projection.projectBpmY(120) - bounds.top) / bounds.height
        assertEquals(expectedZ1Fraction, z1Stop.first, 0.001f)
        assertTrue("Expected Z1 stop ($z1Stop) well above bottom due to 75 BPM warmup", z1Stop.first < 0.75f)
    }

    @Test
    fun `handles empty single-sample and over-60-minute elapsed MM SS formatting safely`() {
        val baseTs = 1_700_000_000_000L
        val bounds = ChartPlotBounds(left = 20f, top = 10f, right = 220f, bottom = 110f)

        val emptyChart = buildSessionChartModel(
            samples = emptyList(),
            sessionStartEpochMs = baseTs,
            elapsedDurationMs = 0L,
            zoneThresholds = sampleThresholds
        )
        assertEquals(null, ChartProjection(emptyChart, bounds).resolveScrubbedSample(touchX = 100f))

        val singleSample = HeartRateSample(
            id = 1L,
            sessionId = "s-single",
            timestampEpochMs = baseTs + 75 * 60_000L + 5_000L,
            bpm = 151,
            zoneIndex = 3,
            sensorContact = true
        )
        val singleChart = buildSessionChartModel(
            samples = listOf(singleSample),
            sessionStartEpochMs = baseTs,
            elapsedDurationMs = 75 * 60_000L + 5_000L,
            zoneThresholds = sampleThresholds
        )
        val readout = ChartProjection(singleChart, bounds).resolveScrubbedSample(touchX = 120f)!!
        assertEquals("75:05", readout.formattedElapsedMmSs)
        assertEquals(151, readout.bpm)
        assertEquals(HeartZone.ZONE_3, readout.zone)
        assertEquals(220f, readout.pointX, 0.01f)
    }
}
