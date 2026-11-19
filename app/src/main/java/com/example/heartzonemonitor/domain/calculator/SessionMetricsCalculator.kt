package com.example.heartzonemonitor.domain.calculator

import kotlin.math.roundToInt

/**
 * Pure domain projection of a single timestamped Heart Rate Sample used to compute Session summary metrics.
 */
data class SampleMetricPoint(
    val timestampEpochMs: Long,
    val bpm: Int,
    val zoneIndex: Int
)

/**
 * Precalculated summary metrics for a recorded Session across BPM statistics and Heart Zones 1 through 5.
 */
data class SessionSummaryMetrics(
    val avgBpm: Int,
    val maxBpm: Int,
    val minBpm: Int,
    val zone1DurationMs: Long,
    val zone2DurationMs: Long,
    val zone3DurationMs: Long,
    val zone4DurationMs: Long,
    val zone5DurationMs: Long,
    val latestSampleEpochMs: Long?
) {
    val totalZoneDurationMs: Long
        get() = zone1DurationMs +
            zone2DurationMs +
            zone3DurationMs +
            zone4DurationMs +
            zone5DurationMs
}

/**
 * Pure Kotlin domain calculator that derives Session BPM summary statistics and gap-aware
 * Heart Zone durations (Zones 1 through 5) from recorded Heart Rate Samples.
 */
class SessionMetricsCalculator {

    /**
     * Computes BPM statistics and gap-aware Heart Zone durations for [samples].
     *
     * Samples are stably sorted by [SampleMetricPoint.timestampEpochMs]. For each sample `i`:
     * - If `i` is not the last sample, `delta = sorted[i + 1].timestampEpochMs - sorted[i].timestampEpochMs`:
     *   credits `0L` when `delta <= 0L`, `delta` when `delta <= MAX_CONTIGUOUS_GAP_MS` (3,000 ms),
     *   or `NOMINAL_SAMPLE_DURATION_MS` (1,000 ms) when `delta > MAX_CONTIGUOUS_GAP_MS` (pause or disconnect gap).
     * - If `i` is the last sample, credits `NOMINAL_SAMPLE_DURATION_MS` (1,000 ms).
     */
    fun calculate(samples: List<SampleMetricPoint>): SessionSummaryMetrics {
        if (samples.isEmpty()) {
            return EMPTY_METRICS
        }

        val sorted = samples.sortedBy { it.timestampEpochMs }
        val avgBpm = sorted.map { it.bpm }.average().roundToInt()
        val maxBpm = sorted.maxOf { it.bpm }
        val minBpm = sorted.minOf { it.bpm }
        val latestSampleEpochMs = sorted.last().timestampEpochMs

        var zone1DurationMs = 0L
        var zone2DurationMs = 0L
        var zone3DurationMs = 0L
        var zone4DurationMs = 0L
        var zone5DurationMs = 0L

        val lastIndex = sorted.lastIndex
        for (i in sorted.indices) {
            val creditedMs = if (i < lastIndex) {
                val delta = sorted[i + 1].timestampEpochMs - sorted[i].timestampEpochMs
                when {
                    delta <= 0L -> 0L
                    delta <= MAX_CONTIGUOUS_GAP_MS -> delta
                    else -> NOMINAL_SAMPLE_DURATION_MS
                }
            } else {
                NOMINAL_SAMPLE_DURATION_MS
            }

            when (sorted[i].zoneIndex) {
                1 -> zone1DurationMs += creditedMs
                2 -> zone2DurationMs += creditedMs
                3 -> zone3DurationMs += creditedMs
                4 -> zone4DurationMs += creditedMs
                5 -> zone5DurationMs += creditedMs
            }
        }

        return SessionSummaryMetrics(
            avgBpm = avgBpm,
            maxBpm = maxBpm,
            minBpm = minBpm,
            zone1DurationMs = zone1DurationMs,
            zone2DurationMs = zone2DurationMs,
            zone3DurationMs = zone3DurationMs,
            zone4DurationMs = zone4DurationMs,
            zone5DurationMs = zone5DurationMs,
            latestSampleEpochMs = latestSampleEpochMs
        )
    }

    companion object {
        const val MAX_CONTIGUOUS_GAP_MS = 3_000L
        const val NOMINAL_SAMPLE_DURATION_MS = 1_000L

        private val EMPTY_METRICS = SessionSummaryMetrics(
            avgBpm = 0,
            maxBpm = 0,
            minBpm = 0,
            zone1DurationMs = 0L,
            zone2DurationMs = 0L,
            zone3DurationMs = 0L,
            zone4DurationMs = 0L,
            zone5DurationMs = 0L,
            latestSampleEpochMs = null
        )
    }
}
