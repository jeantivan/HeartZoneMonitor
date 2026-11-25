package com.example.heartzonemonitor.domain.calculator

import com.example.heartzonemonitor.data.local.entity.HeartRateSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.math.sin

class LttbDownsamplerTest {

    @Test
    fun downsampleLttb_returnsOriginalListWithoutModification_whenSizeAtOrBelowTargetCount() {
        val empty = emptyList<HeartRateSample>()
        assertSame(empty, downsampleLttb(empty, targetCount = 600))

        val samples = listOf(
            sample(timestampEpochMs = 1_000L, bpm = 110),
            sample(timestampEpochMs = 2_000L, bpm = 120),
            sample(timestampEpochMs = 3_000L, bpm = 130)
        )
        assertSame(samples, downsampleLttb(samples, targetCount = 3))
        assertEquals(samples, downsampleLttb(samples, targetCount = 10))
    }

    @Test
    fun downsampleLttb_selectsMaxTriangleAreaPerBucketAndPreservesEndpoints() {
        val samples = listOf(
            sample(timestampEpochMs = 0L, bpm = 100),
            sample(timestampEpochMs = 1_000L, bpm = 102),
            sample(timestampEpochMs = 2_000L, bpm = 140),
            sample(timestampEpochMs = 3_000L, bpm = 110),
            sample(timestampEpochMs = 4_000L, bpm = 80),
            sample(timestampEpochMs = 5_000L, bpm = 110),
            sample(timestampEpochMs = 6_000L, bpm = 110)
        )

        val result = downsampleLttb(samples, targetCount = 4)

        assertEquals(
            listOf(samples[0], samples[2], samples[4], samples[6]),
            result
        )
    }

    @Test
    fun downsampleLttb_guaranteesRetentionOfPeakMaxAndValleyMinEvenInSameOrContestedBucket() {
        val contestedSamples = listOf(
            sample(timestampEpochMs = 0L, bpm = 180),
            sample(timestampEpochMs = 1_000L, bpm = 185), // global max (small area vs 180 baseline)
            sample(timestampEpochMs = 2_000L, bpm = 120), // large area vs 180 baseline
            sample(timestampEpochMs = 3_000L, bpm = 180),
            sample(timestampEpochMs = 4_000L, bpm = 180),
            sample(timestampEpochMs = 5_000L, bpm = 180),
            sample(timestampEpochMs = 6_000L, bpm = 90),
            sample(timestampEpochMs = 7_000L, bpm = 85),  // global min (small area vs 90 baseline)
            sample(timestampEpochMs = 8_000L, bpm = 160), // large area vs 90 baseline
            sample(timestampEpochMs = 9_000L, bpm = 90),
            sample(timestampEpochMs = 10_000L, bpm = 90)
        )

        val contestedResult = downsampleLttb(contestedSamples, targetCount = 5)
        assertValidDownsampled(contestedSamples, contestedResult, expectedSize = 5)

        val sameBucketSamples = listOf(
            sample(timestampEpochMs = 0L, bpm = 140),
            sample(timestampEpochMs = 1_000L, bpm = 141),
            sample(timestampEpochMs = 2_000L, bpm = 82),  // global min inside bucket 0
            sample(timestampEpochMs = 3_000L, bpm = 192), // global max inside bucket 0
            sample(timestampEpochMs = 4_000L, bpm = 140),
            sample(timestampEpochMs = 5_000L, bpm = 140),
            sample(timestampEpochMs = 6_000L, bpm = 140),
            sample(timestampEpochMs = 7_000L, bpm = 140),
            sample(timestampEpochMs = 8_000L, bpm = 140),
            sample(timestampEpochMs = 9_000L, bpm = 140),
            sample(timestampEpochMs = 10_000L, bpm = 140),
            sample(timestampEpochMs = 11_000L, bpm = 140)
        )

        val sameBucketResult = downsampleLttb(sameBucketSamples, targetCount = 5)
        assertValidDownsampled(sameBucketSamples, sameBucketResult, expectedSize = 5)
    }

    @Test
    fun downsampleLttb_rejectsInvalidTargetCountsWhenDownsamplingRequired() {
        val samples = listOf(
            sample(timestampEpochMs = 1_000L, bpm = 100),
            sample(timestampEpochMs = 2_000L, bpm = 150),
            sample(timestampEpochMs = 3_000L, bpm = 120),
            sample(timestampEpochMs = 4_000L, bpm = 130)
        )

        assertThrows(IllegalArgumentException::class.java) {
            downsampleLttb(samples, targetCount = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            downsampleLttb(samples, targetCount = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            downsampleLttb(samples, targetCount = 2)
        }
    }

    @Test
    fun downsampleLttb_threeHourSession_completesUnder15msAndPreservesSizeOrderAndExtrema() {
        val startEpochMs = 1_760_000_000_000L
        val samples = List(10_800) { second ->
            val baseBpm = (140.0 + 25.0 * sin(second / 180.0)).roundToInt()
            val bpm = when (second) {
                2_345 -> 198 // sharp global peak
                7_890 -> 58  // sharp global trough
                else -> baseBpm
            }
            sample(
                id = second.toLong() + 1L,
                timestampEpochMs = startEpochMs + second * 1_000L,
                bpm = bpm,
                zoneIndex = when {
                    bpm < 115 -> 1
                    bpm < 135 -> 2
                    bpm < 155 -> 3
                    bpm < 175 -> 4
                    else -> 5
                }
            )
        }
        val snapshotCopy = samples.toList()

        // Warm up JIT once before measuring execution budget.
        downsampleLttb(samples, targetCount = 700)

        val startNs = System.nanoTime()
        val downsampled = downsampleLttb(samples, targetCount = 700)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0

        assertTrue("Expected < 15ms for 10,800 samples, took ${elapsedMs}ms", elapsedMs < 15.0)
        assertValidDownsampled(samples, downsampled, expectedSize = 700)
        assertEquals(snapshotCopy, samples)
        assertEquals(downsampled, downsampleLttb(samples, targetCount = 700))
    }

    private fun assertValidDownsampled(
        original: List<HeartRateSample>,
        downsampled: List<HeartRateSample>,
        expectedSize: Int
    ) {
        assertEquals(expectedSize, downsampled.size)
        assertEquals(original.first(), downsampled.first())
        assertEquals(original.last(), downsampled.last())
        assertEquals(original.maxOf { it.bpm }, downsampled.maxOf { it.bpm })
        assertEquals(original.minOf { it.bpm }, downsampled.minOf { it.bpm })
        for (i in 0 until downsampled.lastIndex) {
            assertTrue(downsampled[i].timestampEpochMs < downsampled[i + 1].timestampEpochMs)
        }
    }

    private fun sample(
        timestampEpochMs: Long,
        bpm: Int,
        id: Long = 0L,
        zoneIndex: Int = 2,
        sensorContact: Boolean = true
    ): HeartRateSample = HeartRateSample(
        id = id,
        sessionId = "session-1",
        timestampEpochMs = timestampEpochMs,
        bpm = bpm,
        zoneIndex = zoneIndex,
        sensorContact = sensorContact
    )
}
