package com.example.heartzonemonitor.data.local

import com.example.heartzonemonitor.data.local.entity.HeartRateSampleEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HeartRateSampleBufferTest {

    @Test
    fun enqueue_buffers1HzSamplesUnderFiveSeconds_andFlushesAtomicallyAtFiveSeconds() = runTest {
        val sampleDao = FakeHeartRateSampleDao()
        val buffer = HeartRateSampleBuffer(
            sampleDao = sampleDao,
            scope = backgroundScope,
            flushIntervalMs = HeartRateSampleBuffer.DEFAULT_FLUSH_INTERVAL_MS
        )

        buffer.startPeriodicFlush()

        // Simulate 1 Hz samples across 0s..3s (below 5s flush interval)
        for (second in 0..3) {
            buffer.enqueue(createSample(timestampEpochMs = 1_000L + second * 1_000L, bpm = 120 + second))
            if (second < 3) {
                advanceTimeBy(1_000L)
                runCurrent()
            }
        }

        // At 3,000ms: still buffered in memory, zero DB batch calls
        assertEquals(4, buffer.pendingCount())
        assertEquals(0, sampleDao.batchCallCount)
        assertEquals(0, sampleDao.getSampleCountForSession("session-1"))

        // At 4,999ms: still buffered
        advanceTimeBy(1_999L)
        runCurrent()
        assertEquals(4, buffer.pendingCount())
        assertEquals(0, sampleDao.batchCallCount)

        // At 5,000ms: triggers atomic batch flush of all 4 samples
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(0, buffer.pendingCount())
        assertEquals(1, sampleDao.batchCallCount)
        assertEquals(listOf(4), sampleDao.recordedBatches.map { it.size })
        assertEquals(
            listOf(120, 121, 122, 123),
            sampleDao.getSamplesForSession("session-1").map { it.bpm }
        )

        // Subsequent interval flushes next batch
        buffer.enqueue(createSample(timestampEpochMs = 5_000L, bpm = 130))
        buffer.enqueue(createSample(timestampEpochMs = 6_000L, bpm = 132))
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(0, buffer.pendingCount())
        assertEquals(2, sampleDao.batchCallCount)
        assertEquals(listOf(4, 2), sampleDao.recordedBatches.map { it.size })
        assertEquals(6, sampleDao.getSampleCountForSession("session-1"))

        buffer.onSessionStopped()
    }

    @Test
    fun startPeriodicFlush_ignoresDuplicateCalls_andOnSessionStoppedCancelsLoop() = runTest {
        val sampleDao = FakeHeartRateSampleDao()
        val buffer = HeartRateSampleBuffer(
            sampleDao = sampleDao,
            scope = this,
            flushIntervalMs = 5_000L
        )

        buffer.startPeriodicFlush()
        buffer.startPeriodicFlush()

        buffer.enqueue(createSample(timestampEpochMs = 1_000L, bpm = 140))
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(1, sampleDao.batchCallCount)

        // Stopping cancels periodic loop cleanly (allowing TestScope to complete without hanging)
        buffer.enqueue(createSample(timestampEpochMs = 6_000L, bpm = 144))
        buffer.onSessionStopped()

        assertEquals(0, buffer.pendingCount())
        assertEquals(2, sampleDao.batchCallCount)
        assertEquals(listOf(140, 144), sampleDao.getSamplesForSession("session-1").map { it.bpm })

        // Advancing further virtual time performs no additional flushes
        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(2, sampleDao.batchCallCount)
    }

    @Test
    fun lifecycleHooks_flushImmediatelyOnPauseBackgroundAndStop_andSkipEmptyBuffer() = runTest {
        val sampleDao = FakeHeartRateSampleDao()
        val buffer = HeartRateSampleBuffer(
            sampleDao = sampleDao,
            scope = backgroundScope,
            flushIntervalMs = 5_000L
        )

        buffer.startPeriodicFlush()

        // Empty flushes must not invoke batch inserts
        buffer.flush()
        buffer.onSessionPaused()
        buffer.onAppBackgrounded()
        assertEquals(0, sampleDao.batchCallCount)

        // Pause flushes immediately before 5s
        buffer.enqueue(createSample(timestampEpochMs = 1_000L, bpm = 115))
        buffer.enqueue(createSample(timestampEpochMs = 2_000L, bpm = 118))
        buffer.onSessionPaused()
        assertEquals(0, buffer.pendingCount())
        assertEquals(1, sampleDao.batchCallCount)
        assertEquals(listOf(115, 118), sampleDao.getSamplesForSession("session-1").map { it.bpm })

        // Backgrounding flushes immediately before 5s
        buffer.enqueue(createSample(timestampEpochMs = 3_000L, bpm = 122))
        buffer.onAppBackgrounded()
        assertEquals(0, buffer.pendingCount())
        assertEquals(2, sampleDao.batchCallCount)
        assertEquals(listOf(115, 118, 122), sampleDao.getSamplesForSession("session-1").map { it.bpm })

        // Stop cancels loop and flushes immediately
        buffer.enqueue(createSample(timestampEpochMs = 4_000L, bpm = 128))
        buffer.onSessionStopped()
        assertEquals(0, buffer.pendingCount())
        assertEquals(3, sampleDao.batchCallCount)
        assertEquals(
            listOf(115, 118, 122, 128),
            sampleDao.getSamplesForSession("session-1").map { it.bpm }
        )
    }

    @Test
    fun flush_restoresDrainedBatchToFrontInOrderOnWriteFailure() = runTest {
        val sampleDao = FakeHeartRateSampleDao()
        val buffer = HeartRateSampleBuffer(
            sampleDao = sampleDao,
            scope = backgroundScope,
            flushIntervalMs = 5_000L
        )

        val s1 = createSample(timestampEpochMs = 1_000L, bpm = 110)
        val s2 = createSample(timestampEpochMs = 2_000L, bpm = 112)
        val s3 = createSample(timestampEpochMs = 3_000L, bpm = 114)

        buffer.enqueue(s1)
        buffer.enqueue(s2)

        sampleDao.enqueueWriteFailure(IllegalStateException("Simulated SQLite write error"))

        try {
            buffer.flush()
            fail("Expected IllegalStateException to be rethrown from flush()")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message?.contains("Simulated") == true)
        }

        // Samples s1 and s2 were restored to the buffer; DB remains empty
        assertEquals(2, buffer.pendingCount())
        assertEquals(0, sampleDao.getSampleCountForSession("session-1"))

        // Enqueue s3 after the failed flush; restored samples must precede s3
        buffer.enqueue(s3)
        assertEquals(3, buffer.pendingCount())

        buffer.flush()

        assertEquals(0, buffer.pendingCount())
        assertEquals(
            listOf(110, 112, 114),
            sampleDao.getSamplesForSession("session-1").map { it.bpm }
        )
    }

    @Test
    fun periodicFlush_retainsAndRetriesFailedBatchOnNextTick() = runTest {
        val sampleDao = FakeHeartRateSampleDao()
        val buffer = HeartRateSampleBuffer(
            sampleDao = sampleDao,
            scope = backgroundScope,
            flushIntervalMs = 5_000L
        )

        buffer.startPeriodicFlush()
        buffer.enqueue(createSample(timestampEpochMs = 1_000L, bpm = 101))
        buffer.enqueue(createSample(timestampEpochMs = 2_000L, bpm = 102))

        sampleDao.enqueueWriteFailure(IllegalStateException("Transient write lock"))

        // First 5s tick fails and restores samples to buffer
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(2, buffer.pendingCount())
        assertEquals(0, sampleDao.getSampleCountForSession("session-1"))

        // New sample arrives before next 5s tick
        buffer.enqueue(createSample(timestampEpochMs = 6_000L, bpm = 103))
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(0, buffer.pendingCount())
        assertEquals(
            listOf(101, 102, 103),
            sampleDao.getSamplesForSession("session-1").map { it.bpm }
        )

        buffer.onSessionStopped()
    }

    @Test
    fun concurrentEnqueueAndFlush_preservesAllSamples() = runTest {
        val sampleDao = FakeHeartRateSampleDao()
        val buffer = HeartRateSampleBuffer(
            sampleDao = sampleDao,
            scope = backgroundScope,
            flushIntervalMs = 5_000L
        )

        val producers = (1..5).map { worker ->
            async {
                repeat(20) { index ->
                    val ts = (worker * 1_000L) + index
                    buffer.enqueue(createSample(timestampEpochMs = ts, bpm = 100 + index))
                    if (index % 7 == 0) {
                        buffer.flush()
                    }
                }
            }
        }
        producers.awaitAll()
        buffer.flush()

        assertEquals(0, buffer.pendingCount())
        assertEquals(100, sampleDao.getSampleCountForSession("session-1"))
    }

    private fun createSample(
        sessionId: String = "session-1",
        timestampEpochMs: Long,
        bpm: Int,
        zoneIndex: Int = 2,
        sensorContact: Boolean = true
    ): HeartRateSampleEntity {
        return HeartRateSampleEntity(
            sessionId = sessionId,
            timestampEpochMs = timestampEpochMs,
            bpm = bpm,
            zoneIndex = zoneIndex,
            sensorContact = sensorContact
        )
    }
}
