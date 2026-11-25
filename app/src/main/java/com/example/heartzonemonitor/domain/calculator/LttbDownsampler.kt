package com.example.heartzonemonitor.domain.calculator

import com.example.heartzonemonitor.domain.model.HeartRateSample
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * Pure functional Largest-Triangle-Three-Buckets (LTTB) downsampler for 1 Hz Heart Rate Samples.
 *
 * Reduces large sample lists to [targetCount] visual points while preserving endpoints,
 * maximizing triangle area per bucket, and guaranteeing retention of global maximum and minimum BPMs.
 */
fun downsampleLttb(samples: List<HeartRateSample>, targetCount: Int): List<HeartRateSample> {
    require(targetCount >= 0) { "targetCount must be non-negative, got $targetCount" }
    if (samples.size <= targetCount) {
        return samples
    }
    require(targetCount >= 3) {
        "LTTB downsampling requires targetCount >= 3 when samples (${samples.size}) exceed targetCount ($targetCount)"
    }

    val sampledIndices = selectLttbIndices(samples, targetCount)
    retainGlobalExtrema(samples, sampledIndices, targetCount)

    return ArrayList<HeartRateSample>(targetCount).apply {
        for (idx in sampledIndices) {
            add(samples[idx])
        }
    }
}

private fun selectLttbIndices(
    samples: List<HeartRateSample>,
    targetCount: Int
): IntArray {
    val totalSize = samples.size
    val selected = IntArray(targetCount)
    selected[0] = 0
    selected[targetCount - 1] = totalSize - 1

    val baseTimestamp = samples[0].timestampEpochMs
    val bucketSize = (totalSize - 2).toDouble() / (targetCount - 2).toDouble()
    var prevSelectedIdx = 0

    for (bucketIdx in 0 until targetCount - 2) {
        val rangeStart = floor(bucketIdx * bucketSize).toInt() + 1
        val rangeEnd = min(floor((bucketIdx + 1) * bucketSize).toInt() + 1, totalSize - 1)

        val avgRangeStart = floor((bucketIdx + 1) * bucketSize).toInt() + 1
        val avgRangeEnd = min(floor((bucketIdx + 2) * bucketSize).toInt() + 1, totalSize)

        var avgX = 0.0
        var avgY = 0.0
        val avgCount = (avgRangeEnd - avgRangeStart).coerceAtLeast(1)
        for (i in avgRangeStart until avgRangeEnd) {
            avgX += (samples[i].timestampEpochMs - baseTimestamp).toDouble()
            avgY += samples[i].bpm.toDouble()
        }
        avgX /= avgCount
        avgY /= avgCount

        val prevX = (samples[prevSelectedIdx].timestampEpochMs - baseTimestamp).toDouble()
        val prevY = samples[prevSelectedIdx].bpm.toDouble()

        var maxArea = -1.0
        var maxAreaIdx = rangeStart
        for (i in rangeStart until rangeEnd) {
            val pointX = (samples[i].timestampEpochMs - baseTimestamp).toDouble()
            val pointY = samples[i].bpm.toDouble()
            val area = abs((prevX - avgX) * (pointY - prevY) - (prevX - pointX) * (avgY - prevY))
            if (area > maxArea) {
                maxArea = area
                maxAreaIdx = i
            }
        }

        selected[bucketIdx + 1] = maxAreaIdx
        prevSelectedIdx = maxAreaIdx
    }

    return selected
}

private fun retainGlobalExtrema(
    samples: List<HeartRateSample>,
    selected: IntArray,
    targetCount: Int
) {
    var minIdx = 0
    var maxIdx = 0
    var minBpm = samples[0].bpm
    var maxBpm = samples[0].bpm
    for (i in 1 until samples.size) {
        val bpm = samples[i].bpm
        if (bpm < minBpm) {
            minBpm = bpm
            minIdx = i
        }
        if (bpm > maxBpm) {
            maxBpm = bpm
            maxIdx = i
        }
    }

    val bucketSize = (samples.size - 2).toDouble() / (targetCount - 2).toDouble()
    ensureExtremumRetained(samples, selected, bucketSize, extremumIdx = maxIdx, otherExtremumBpm = minBpm)
    ensureExtremumRetained(samples, selected, bucketSize, extremumIdx = minIdx, otherExtremumBpm = maxBpm)
}

private fun ensureExtremumRetained(
    samples: List<HeartRateSample>,
    selected: IntArray,
    bucketSize: Double,
    extremumIdx: Int,
    otherExtremumBpm: Int
) {
    val extremumBpm = samples[extremumIdx].bpm
    var otherExtremumCount = 0
    for (slot in selected.indices) {
        val bpm = samples[selected[slot]].bpm
        if (bpm == extremumBpm) return
        if (bpm == otherExtremumBpm) otherExtremumCount++
    }

    // Prefer replacing the representative of the extremum's own bucket so adjacent buckets keep their points.
    val ownSlot = (floor((extremumIdx - 1) / bucketSize).toInt() + 1).coerceIn(1, selected.lastIndex - 1)
    val ownSlotProtected = samples[selected[ownSlot]].bpm == otherExtremumBpm && otherExtremumCount <= 1

    val targetSlot = if (!ownSlotProtected) {
        ownSlot
    } else {
        var bestSlot = -1
        var bestDistance = Int.MAX_VALUE
        for (slot in 1 until selected.lastIndex) {
            val slotBpm = samples[selected[slot]].bpm
            if (slotBpm == otherExtremumBpm && otherExtremumCount <= 1) continue
            val distance = abs(selected[slot] - extremumIdx)
            if (distance < bestDistance) {
                bestDistance = distance
                bestSlot = slot
            }
        }
        bestSlot
    }

    if (targetSlot != -1) {
        selected[targetSlot] = extremumIdx
        selected.sort(fromIndex = 1, toIndex = selected.size - 1)
    }
}
