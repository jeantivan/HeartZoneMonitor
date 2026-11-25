package com.example.heartzonemonitor.ui.history

import com.example.heartzonemonitor.domain.calculator.downsampleLttb
import com.example.heartzonemonitor.domain.model.HeartRateSample
import com.example.heartzonemonitor.domain.model.HeartZone
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

const val CHART_DOWNSAMPLING_THRESHOLD = 1_000
const val DEFAULT_CHART_LTTB_TARGET_POINTS = 800
private const val CHART_BPM_VERTICAL_PADDING = 8

/**
 * Represents a single Heart Zone lower threshold horizontal gridline (50%, 60%, 70%, 80%, 90%).
 */
data class ZoneThresholdLine(
    val zone: HeartZone,
    val bpm: Int
)

/**
 * Immutable chart data model for the Session Detail 1 Hz heart rate time-series Canvas.
 */
data class SessionChartModel(
    val plottedSamples: List<HeartRateSample> = emptyList(),
    val rawSampleCount: Int = 0,
    val isDownsampled: Boolean = false,
    val sessionStartEpochMs: Long = 0L,
    val totalElapsedMs: Long = 0L,
    val zoneThresholds: List<ZoneThresholdLine> = emptyList(),
    val minBpmBound: Int = 60,
    val maxBpmBound: Int = 200
)

/**
 * Builds a [SessionChartModel] from raw 1 Hz [HeartRateSample] records, applying LTTB
 * downsampling when sample count exceeds [CHART_DOWNSAMPLING_THRESHOLD] and computing Y-axis BPM
 * bounds that encompass both all zone threshold lines and sample extrema with vertical padding.
 */
fun buildSessionChartModel(
    samples: List<HeartRateSample>,
    sessionStartEpochMs: Long,
    elapsedDurationMs: Long,
    zoneThresholds: List<ZoneThresholdLine>
): SessionChartModel {
    val isDownsampled = samples.size > CHART_DOWNSAMPLING_THRESHOLD
    val plottedSamples = if (isDownsampled) {
        downsampleLttb(samples, DEFAULT_CHART_LTTB_TARGET_POINTS)
    } else {
        samples
    }

    val effectiveStartMs = if (plottedSamples.isNotEmpty()) {
        min(sessionStartEpochMs, plottedSamples.first().timestampEpochMs)
    } else {
        sessionStartEpochMs
    }
    val sampleSpanMs = if (plottedSamples.isNotEmpty()) {
        (plottedSamples.last().timestampEpochMs - effectiveStartMs).coerceAtLeast(0L)
    } else {
        0L
    }
    val totalElapsedMs = max(elapsedDurationMs.coerceAtLeast(0L), sampleSpanMs)

    val allBpms = buildList {
        plottedSamples.forEach { add(it.bpm) }
        zoneThresholds.forEach { add(it.bpm) }
    }
    val rawMinBpm = allBpms.minOrNull() ?: 60
    val rawMaxBpm = allBpms.maxOrNull() ?: 200
    val minBound = (rawMinBpm - CHART_BPM_VERTICAL_PADDING).coerceAtLeast(0)
    val maxBound = max(rawMaxBpm + CHART_BPM_VERTICAL_PADDING, minBound + 10)

    return SessionChartModel(
        plottedSamples = plottedSamples,
        rawSampleCount = samples.size,
        isDownsampled = isDownsampled,
        sessionStartEpochMs = effectiveStartMs,
        totalElapsedMs = totalElapsedMs,
        zoneThresholds = zoneThresholds,
        minBpmBound = minBound,
        maxBpmBound = maxBound
    )
}

/**
 * Pixel coordinate rectangle of the drawable time-series plot area inside the chart Canvas.
 */
data class ChartPlotBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}

/**
 * Resolved touch scrubbing readout for the nearest plotted [HeartRateSample].
 */
data class ScrubbedSampleReadout(
    val elapsedMs: Long,
    val formattedElapsedMmSs: String,
    val bpm: Int,
    val zone: HeartZone,
    val pointX: Float,
    val pointY: Float
)

/**
 * Pure coordinate projector binding a [SessionChartModel] to its [ChartPlotBounds] for X/Y
 * mapping, touch scrubber lookup, and threshold-aligned zone gradient stops.
 */
class ChartProjection(
    val chart: SessionChartModel,
    val plotBounds: ChartPlotBounds
) {
    /** Projects a sample timestamp into horizontal Canvas space within [plotBounds]. */
    fun projectSampleX(timestampEpochMs: Long): Float {
        if (plotBounds.width <= 0f || chart.totalElapsedMs <= 0L) {
            return plotBounds.left
        }
        val elapsedMs = (timestampEpochMs - chart.sessionStartEpochMs)
            .coerceIn(0L, chart.totalElapsedMs)
        val fraction = elapsedMs.toDouble() / chart.totalElapsedMs.toDouble()
        return plotBounds.left + (fraction * plotBounds.width).toFloat()
    }

    /** Projects a BPM value into vertical Canvas space within [plotBounds] (higher BPM near top). */
    fun projectBpmY(bpm: Int): Float {
        val span = (chart.maxBpmBound - chart.minBpmBound).coerceAtLeast(1)
        val fraction = ((bpm - chart.minBpmBound).toFloat() / span.toFloat()).coerceIn(0f, 1f)
        return plotBounds.bottom - fraction * plotBounds.height
    }

    /** Maps a horizontal touch X coordinate in Canvas space to the nearest plotted [HeartRateSample]. */
    fun resolveScrubbedSample(touchX: Float): ScrubbedSampleReadout? {
        val samples = chart.plottedSamples
        if (samples.isEmpty()) return null

        val clampedX = touchX.coerceIn(plotBounds.left, plotBounds.right)
        val targetEpochMs = if (plotBounds.width <= 0f || chart.totalElapsedMs <= 0L) {
            chart.sessionStartEpochMs
        } else {
            val fraction = ((clampedX - plotBounds.left) / plotBounds.width).coerceIn(0f, 1f)
            chart.sessionStartEpochMs +
                (fraction.toDouble() * chart.totalElapsedMs.toDouble()).roundToLong()
        }

        val nearest = findNearestSampleByTimestamp(samples, targetEpochMs)
        val elapsedMs = (nearest.timestampEpochMs - chart.sessionStartEpochMs).coerceAtLeast(0L)
        return ScrubbedSampleReadout(
            elapsedMs = elapsedMs,
            formattedElapsedMmSs = formatElapsedMmSs(elapsedMs),
            bpm = nearest.bpm,
            zone = HeartZone.fromIndex(nearest.zoneIndex),
            pointX = projectSampleX(nearest.timestampEpochMs),
            pointY = projectBpmY(nearest.bpm)
        )
    }

    /**
     * Computes vertical gradient stops (0f = top, 1f = bottom) aligned to each [ZoneThresholdLine]'s
     * exact projected Y coordinate so the polyline stroke color matches the zone band it crosses.
     */
    fun zoneGradientStops(): List<Pair<Float, HeartZone>> {
        if (chart.zoneThresholds.isEmpty() || plotBounds.height <= 0f) {
            return listOf(0f to HeartZone.ZONE_5, 1f to HeartZone.ZONE_1)
        }
        val sortedDesc = chart.zoneThresholds.sortedByDescending { it.bpm }
        return buildList {
            add(0f to sortedDesc.first().zone)
            sortedDesc.forEach { threshold ->
                val yFraction = ((projectBpmY(threshold.bpm) - plotBounds.top) / plotBounds.height)
                    .coerceIn(0f, 1f)
                add(yFraction to threshold.zone)
            }
            add(1f to sortedDesc.last().zone)
        }
    }
}

/**
 * Formats a relative elapsed Session offset in milliseconds as zero-padded `MM:SS`.
 * Sessions exceeding 59 minutes retain total minutes (e.g. `75:05`).
 */
fun formatElapsedMmSs(elapsedMs: Long): String {
    val totalSeconds = elapsedMs.coerceAtLeast(0L) / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

private fun findNearestSampleByTimestamp(
    samples: List<HeartRateSample>,
    targetEpochMs: Long
): HeartRateSample {
    if (samples.size == 1) return samples[0]
    var low = 0
    var high = samples.lastIndex
    while (low <= high) {
        val mid = (low + high) ushr 1
        val midTs = samples[mid].timestampEpochMs
        when {
            midTs < targetEpochMs -> low = mid + 1
            midTs > targetEpochMs -> high = mid - 1
            else -> return samples[mid]
        }
    }
    if (low <= 0) return samples.first()
    if (low >= samples.size) return samples.last()
    val before = samples[low - 1]
    val after = samples[low]
    return if (abs(targetEpochMs - before.timestampEpochMs) <= abs(after.timestampEpochMs - targetEpochMs)) {
        before
    } else {
        after
    }
}
