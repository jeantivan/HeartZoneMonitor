package com.example.heartzonemonitor.domain.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionMetricsCalculatorTest {

    private val calculator = SessionMetricsCalculator()

    @Test
    fun calculate_emptySamples_returnsAllZerosAndNullLatestTimestamp() {
        val result = calculator.calculate(emptyList())

        assertEquals(0, result.avgBpm)
        assertEquals(0, result.maxBpm)
        assertEquals(0, result.minBpm)
        assertEquals(0L, result.zone1DurationMs)
        assertEquals(0L, result.zone2DurationMs)
        assertEquals(0L, result.zone3DurationMs)
        assertEquals(0L, result.zone4DurationMs)
        assertEquals(0L, result.zone5DurationMs)
        assertEquals(0L, result.totalZoneDurationMs)
        assertNull(result.latestSampleEpochMs)
    }

    @Test
    fun calculate_singleSample_creditsNominalOneSecondAndMatchingStats() {
        val result = calculator.calculate(
            listOf(SampleMetricPoint(timestampEpochMs = 10_000L, bpm = 142, zoneIndex = 3))
        )

        assertEquals(142, result.avgBpm)
        assertEquals(142, result.maxBpm)
        assertEquals(142, result.minBpm)
        assertEquals(0L, result.zone1DurationMs)
        assertEquals(0L, result.zone2DurationMs)
        assertEquals(1_000L, result.zone3DurationMs)
        assertEquals(0L, result.zone4DurationMs)
        assertEquals(0L, result.zone5DurationMs)
        assertEquals(1_000L, result.totalZoneDurationMs)
        assertEquals(10_000L, result.latestSampleEpochMs)
    }

    @Test
    fun calculate_contiguous1HzAcrossAllFiveZones_computesExactStatsAndDurations() {
        val samples = listOf(
            SampleMetricPoint(timestampEpochMs = 1_000L, bpm = 100, zoneIndex = 1),
            SampleMetricPoint(timestampEpochMs = 2_000L, bpm = 120, zoneIndex = 2),
            SampleMetricPoint(timestampEpochMs = 3_000L, bpm = 125, zoneIndex = 2),
            SampleMetricPoint(timestampEpochMs = 4_000L, bpm = 145, zoneIndex = 3),
            SampleMetricPoint(timestampEpochMs = 5_000L, bpm = 165, zoneIndex = 4),
            SampleMetricPoint(timestampEpochMs = 6_000L, bpm = 182, zoneIndex = 5)
        )

        val result = calculator.calculate(samples)

        // (100 + 120 + 125 + 145 + 165 + 182) / 6.0 = 837 / 6.0 = 139.5 -> rounds to 140
        assertEquals(140, result.avgBpm)
        assertEquals(182, result.maxBpm)
        assertEquals(100, result.minBpm)
        assertEquals(1_000L, result.zone1DurationMs)
        assertEquals(2_000L, result.zone2DurationMs)
        assertEquals(1_000L, result.zone3DurationMs)
        assertEquals(1_000L, result.zone4DurationMs)
        assertEquals(1_000L, result.zone5DurationMs)
        assertEquals(6_000L, result.totalZoneDurationMs)
        assertEquals(6_000L, result.latestSampleEpochMs)
    }

    @Test
    fun calculate_gapThresholdBoundary_creditsExactDeltaUpToThreeSecondsAndNominalAboveThreeSeconds() {
        val samples = listOf(
            // delta to next = 2_000ms (<= 3_000ms) -> credits 2_000ms to Z1
            SampleMetricPoint(timestampEpochMs = 1_000L, bpm = 105, zoneIndex = 1),
            // delta to next = 3_000ms (exact boundary <= 3_000ms) -> credits 3_000ms to Z2
            SampleMetricPoint(timestampEpochMs = 3_000L, bpm = 125, zoneIndex = 2),
            // delta to next = 3_001ms (> 3_000ms gap) -> credits nominal 1_000ms to Z3
            SampleMetricPoint(timestampEpochMs = 6_000L, bpm = 145, zoneIndex = 3),
            // delta to next = 20_000ms (pause gap > 3_000ms) -> credits nominal 1_000ms to Z4
            SampleMetricPoint(timestampEpochMs = 9_001L, bpm = 165, zoneIndex = 4),
            // last sample -> credits nominal 1_000ms to Z5
            SampleMetricPoint(timestampEpochMs = 29_001L, bpm = 180, zoneIndex = 5)
        )

        val result = calculator.calculate(samples)

        assertEquals(144, result.avgBpm)
        assertEquals(180, result.maxBpm)
        assertEquals(105, result.minBpm)
        assertEquals(2_000L, result.zone1DurationMs)
        assertEquals(3_000L, result.zone2DurationMs)
        assertEquals(1_000L, result.zone3DurationMs)
        assertEquals(1_000L, result.zone4DurationMs)
        assertEquals(1_000L, result.zone5DurationMs)
        assertEquals(8_000L, result.totalZoneDurationMs)
        assertEquals(29_001L, result.latestSampleEpochMs)
    }

    @Test
    fun calculate_duplicateTimestamps_creditsZeroToFirstAndPreservesStableOrderForTies() {
        val samples = listOf(
            SampleMetricPoint(timestampEpochMs = 5_000L, bpm = 110, zoneIndex = 1),
            SampleMetricPoint(timestampEpochMs = 5_000L, bpm = 130, zoneIndex = 2),
            SampleMetricPoint(timestampEpochMs = 7_000L, bpm = 150, zoneIndex = 3)
        )

        val result = calculator.calculate(samples)

        // First tie (Z1) gets delta = 0L; second tie (Z2) gets delta = 2_000L; last (Z3) gets 1_000L
        assertEquals(130, result.avgBpm)
        assertEquals(150, result.maxBpm)
        assertEquals(110, result.minBpm)
        assertEquals(0L, result.zone1DurationMs)
        assertEquals(2_000L, result.zone2DurationMs)
        assertEquals(1_000L, result.zone3DurationMs)
        assertEquals(3_000L, result.totalZoneDurationMs)
        assertEquals(7_000L, result.latestSampleEpochMs)
    }

    @Test
    fun calculate_unsortedSamples_sortsChronologicallyBeforeComputingMetrics() {
        val samples = listOf(
            SampleMetricPoint(timestampEpochMs = 4_000L, bpm = 160, zoneIndex = 4),
            SampleMetricPoint(timestampEpochMs = 1_000L, bpm = 110, zoneIndex = 1),
            SampleMetricPoint(timestampEpochMs = 2_500L, bpm = 135, zoneIndex = 3)
        )

        val result = calculator.calculate(samples)

        // Sorted: 1_000 (Z1 -> +1_500ms), 2_500 (Z3 -> +1_500ms), 4_000 (Z4 -> +1_000ms)
        assertEquals(135, result.avgBpm)
        assertEquals(160, result.maxBpm)
        assertEquals(110, result.minBpm)
        assertEquals(1_500L, result.zone1DurationMs)
        assertEquals(0L, result.zone2DurationMs)
        assertEquals(1_500L, result.zone3DurationMs)
        assertEquals(1_000L, result.zone4DurationMs)
        assertEquals(0L, result.zone5DurationMs)
        assertEquals(4_000L, result.totalZoneDurationMs)
        assertEquals(4_000L, result.latestSampleEpochMs)
    }
}
