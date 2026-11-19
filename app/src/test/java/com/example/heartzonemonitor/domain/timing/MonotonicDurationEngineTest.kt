package com.example.heartzonemonitor.domain.timing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MonotonicDurationEngineTest {

    @Test
    fun initialState_isIdleWithZeroDurationsAndNullTimestamps() {
        val clock = FakeSessionClock(initialRealtimeMs = 50_000L, initialEpochMs = 1_700_000_000_000L)
        val engine = MonotonicDurationEngine(clock)

        assertEquals(DurationTrackingState.IDLE, engine.currentState())
        assertNull(engine.startEpochMs())
        assertNull(engine.endEpochMs())
        assertNull(engine.activeIntervalStartRealtimeMs())
        assertNull(engine.pauseIntervalStartRealtimeMs())
        assertEquals(0L, engine.effectiveDurationMs())
        assertEquals(0L, engine.pausedDurationMs())
        assertEquals(0L, engine.elapsedDurationMs())

        assertEquals(
            DurationSnapshot(
                state = DurationTrackingState.IDLE,
                startEpochMs = null,
                endEpochMs = null,
                effectiveDurationMs = 0L,
                pausedDurationMs = 0L,
                elapsedDurationMs = 0L
            ),
            engine.snapshot()
        )
    }

    @Test
    fun start_capturesStartEpochOnceAndTracksLiveEffectiveAndElapsedDurations() {
        val initialRealtime = 100_000L
        val initialEpoch = 1_710_000_000_000L
        val clock = FakeSessionClock(initialRealtimeMs = initialRealtime, initialEpochMs = initialEpoch)
        val engine = MonotonicDurationEngine(clock)

        val started = engine.start()

        assertEquals(DurationTrackingState.RECORDING, started.state)
        assertEquals(initialEpoch, started.startEpochMs)
        assertNull(started.endEpochMs)
        assertEquals(0L, started.effectiveDurationMs)
        assertEquals(0L, started.pausedDurationMs)
        assertEquals(0L, started.elapsedDurationMs)
        assertEquals(initialRealtime, engine.activeIntervalStartRealtimeMs())
        assertNull(engine.pauseIntervalStartRealtimeMs())

        clock.advanceBothBy(4_321L)

        assertEquals(DurationTrackingState.RECORDING, engine.currentState())
        assertEquals(initialEpoch, engine.startEpochMs())
        assertNull(engine.endEpochMs())
        assertEquals(4_321L, engine.effectiveDurationMs())
        assertEquals(0L, engine.pausedDurationMs())
        assertEquals(4_321L, engine.elapsedDurationMs())
        assertEquals(initialRealtime, engine.activeIntervalStartRealtimeMs())
    }

    @Test
    fun multiplePauseResumeStopCycles_accumulateWithMillisecondPrecision() {
        val clock = FakeSessionClock(initialRealtimeMs = 20_000L, initialEpochMs = 1_700_000_000_000L)
        val engine = MonotonicDurationEngine(clock)

        engine.start()

        // Active interval 1: 12,345 ms
        clock.advanceBothBy(12_345L)
        val paused1 = engine.pause()
        assertEquals(DurationTrackingState.PAUSED, paused1.state)
        assertEquals(12_345L, paused1.effectiveDurationMs)
        assertEquals(0L, paused1.pausedDurationMs)
        assertEquals(12_345L, paused1.elapsedDurationMs)
        assertNull(engine.activeIntervalStartRealtimeMs())
        assertEquals(32_345L, engine.pauseIntervalStartRealtimeMs())

        // Live check mid-pause: 2,000 ms into pause 1
        clock.advanceBothBy(2_000L)
        assertEquals(12_345L, engine.effectiveDurationMs())
        assertEquals(2_000L, engine.pausedDurationMs())
        assertEquals(14_345L, engine.elapsedDurationMs())

        // Remaining pause 1: +2,567 ms (total pause 1 = 4,567 ms)
        clock.advanceBothBy(2_567L)
        val resumed1 = engine.resume()
        assertEquals(DurationTrackingState.RECORDING, resumed1.state)
        assertEquals(12_345L, resumed1.effectiveDurationMs)
        assertEquals(4_567L, resumed1.pausedDurationMs)
        assertEquals(16_912L, resumed1.elapsedDurationMs)
        assertEquals(36_912L, engine.activeIntervalStartRealtimeMs())
        assertNull(engine.pauseIntervalStartRealtimeMs())

        // Active interval 2: 8,901 ms
        clock.advanceBothBy(8_901L)
        engine.pause()
        assertEquals(21_246L, engine.effectiveDurationMs())
        assertEquals(4_567L, engine.pausedDurationMs())
        assertEquals(25_813L, engine.elapsedDurationMs())

        // Pause interval 2: 1,234 ms
        clock.advanceBothBy(1_234L)
        engine.resume()
        assertEquals(21_246L, engine.effectiveDurationMs())
        assertEquals(5_801L, engine.pausedDurationMs())
        assertEquals(27_047L, engine.elapsedDurationMs())

        // Active interval 3: 555 ms
        clock.advanceBothBy(555L)
        val stopped = engine.stop()

        val expectedEffective = 12_345L + 8_901L + 555L
        val expectedPaused = 4_567L + 1_234L
        val expectedElapsed = expectedEffective + expectedPaused

        assertEquals(DurationTrackingState.STOPPED, stopped.state)
        assertEquals(1_700_000_000_000L, stopped.startEpochMs)
        assertEquals(1_700_000_000_000L + expectedElapsed, stopped.endEpochMs)
        assertEquals(expectedEffective, stopped.effectiveDurationMs)
        assertEquals(expectedPaused, stopped.pausedDurationMs)
        assertEquals(expectedElapsed, stopped.elapsedDurationMs)
        assertNull(engine.activeIntervalStartRealtimeMs())
        assertNull(engine.pauseIntervalStartRealtimeMs())
    }

    @Test
    fun wallClockJumps_ntpDstAndNegativeManualChanges_haveZeroImpactOnDurationsOrStartEpoch() {
        val initialEpoch = 1_700_000_000_000L
        val clock = FakeSessionClock(initialRealtimeMs = 1_000L, initialEpochMs = initialEpoch)
        val engine = MonotonicDurationEngine(clock)

        engine.start()

        // 15s active recording + simulated NTP forward jump (+1 hour = +3,600,000 ms)
        clock.advanceBothBy(15_000L)
        clock.advanceWallClockBy(+3_600_000L)
        assertEquals(15_000L, engine.effectiveDurationMs())
        assertEquals(0L, engine.pausedDurationMs())
        assertEquals(15_000L, engine.elapsedDurationMs())
        assertEquals(initialEpoch, engine.startEpochMs())

        // Simulate DST/timezone shift (-1 hour) while still RECORDING
        clock.advanceRealtimeBy(5_000L)
        clock.advanceWallClockBy(-3_600_000L)
        assertEquals(20_000L, engine.effectiveDurationMs())
        assertEquals(0L, engine.pausedDurationMs())
        assertEquals(20_000L, engine.elapsedDurationMs())
        assertEquals(initialEpoch, engine.startEpochMs())

        // Pause and apply manual backward wall-clock jump (-2 hours = -7,200,000 ms, earlier than startEpochMs)
        engine.pause()
        clock.advanceRealtimeBy(7_500L)
        clock.advanceWallClockBy(-7_200_000L)
        assertTrue(clock.currentEpochMs() < initialEpoch)
        assertEquals(20_000L, engine.effectiveDurationMs())
        assertEquals(7_500L, engine.pausedDurationMs())
        assertEquals(27_500L, engine.elapsedDurationMs())
        assertEquals(initialEpoch, engine.startEpochMs())

        // Resume, apply another +1 hour DST jump while RECORDING, then stop
        engine.resume()
        clock.advanceRealtimeBy(10_000L)
        clock.advanceWallClockBy(+3_600_000L)
        val finalSnapshot = engine.stop()

        assertEquals(DurationTrackingState.STOPPED, finalSnapshot.state)
        assertEquals(initialEpoch, finalSnapshot.startEpochMs)
        assertEquals(30_000L, finalSnapshot.effectiveDurationMs)
        assertEquals(7_500L, finalSnapshot.pausedDurationMs)
        assertEquals(37_500L, finalSnapshot.elapsedDurationMs)
    }

    @Test
    fun backwardWallClockBeforeStartEpochAtStop_stillPreservesMonotonicDurations() {
        val initialEpoch = 1_700_000_000_000L
        val clock = FakeSessionClock(initialRealtimeMs = 5_000L, initialEpochMs = initialEpoch)
        val engine = MonotonicDurationEngine(clock)

        engine.start()
        clock.advanceRealtimeBy(12_000L)
        clock.advanceWallClockBy(-7_200_000L)

        val stopped = engine.stop()
        assertEquals(initialEpoch, stopped.startEpochMs)
        assertEquals(initialEpoch - 7_200_000L, stopped.endEpochMs)
        assertEquals(12_000L, stopped.effectiveDurationMs)
        assertEquals(0L, stopped.pausedDurationMs)
        assertEquals(12_000L, stopped.elapsedDurationMs)
    }

    @Test
    fun stopFromRecording_freezesDurationsPermanentlyWhileClocksAdvance() {
        val clock = FakeSessionClock(initialRealtimeMs = 500L, initialEpochMs = 1_700_000_000_000L)
        val engine = MonotonicDurationEngine(clock)

        engine.start()
        clock.advanceBothBy(25_000L)
        val stopped = engine.stop()
        val capturedEndEpoch = stopped.endEpochMs

        assertEquals(DurationTrackingState.STOPPED, stopped.state)
        assertEquals(25_000L, stopped.effectiveDurationMs)
        assertEquals(0L, stopped.pausedDurationMs)
        assertEquals(25_000L, stopped.elapsedDurationMs)

        // Advance both clocks substantially after stopping
        clock.advanceBothBy(600_000L)

        assertEquals(DurationTrackingState.STOPPED, engine.currentState())
        assertEquals(25_000L, engine.effectiveDurationMs())
        assertEquals(0L, engine.pausedDurationMs())
        assertEquals(25_000L, engine.elapsedDurationMs())
        assertEquals(capturedEndEpoch, engine.endEpochMs())
        assertEquals(stopped, engine.snapshot())
    }

    @Test
    fun stopFromPaused_closesPauseIntervalAndFreezesDurationsPermanently() {
        val clock = FakeSessionClock(initialRealtimeMs = 1_000L, initialEpochMs = 1_700_000_000_000L)
        val engine = MonotonicDurationEngine(clock)

        engine.start()
        clock.advanceBothBy(18_000L)
        engine.pause()
        clock.advanceBothBy(9_500L)

        val stopped = engine.stop()
        val capturedEndEpoch = stopped.endEpochMs

        assertEquals(DurationTrackingState.STOPPED, stopped.state)
        assertEquals(18_000L, stopped.effectiveDurationMs)
        assertEquals(9_500L, stopped.pausedDurationMs)
        assertEquals(27_500L, stopped.elapsedDurationMs)
        assertEquals(1_700_000_027_500L, capturedEndEpoch)
        assertNull(engine.activeIntervalStartRealtimeMs())
        assertNull(engine.pauseIntervalStartRealtimeMs())

        clock.advanceBothBy(300_000L)

        assertEquals(18_000L, engine.effectiveDurationMs())
        assertEquals(9_500L, engine.pausedDurationMs())
        assertEquals(27_500L, engine.elapsedDurationMs())
        assertEquals(capturedEndEpoch, engine.endEpochMs())
        assertEquals(stopped, engine.snapshot())
    }

    @Test
    fun reset_restoresCleanIdleStateForSubsequentSessions() {
        val clock = FakeSessionClock(initialRealtimeMs = 1_000L, initialEpochMs = 1_700_000_000_000L)
        val engine = MonotonicDurationEngine(clock)

        // First Session
        engine.start()
        clock.advanceBothBy(30_000L)
        engine.pause()
        clock.advanceBothBy(5_000L)
        engine.stop()

        engine.reset()
        assertEquals(DurationTrackingState.IDLE, engine.currentState())
        assertNull(engine.startEpochMs())
        assertNull(engine.endEpochMs())
        assertNull(engine.activeIntervalStartRealtimeMs())
        assertNull(engine.pauseIntervalStartRealtimeMs())
        assertEquals(0L, engine.effectiveDurationMs())
        assertEquals(0L, engine.pausedDurationMs())
        assertEquals(0L, engine.elapsedDurationMs())

        // Second Session after advancing clock while IDLE
        clock.advanceBothBy(60_000L)
        val secondStartEpoch = clock.currentEpochMs()
        engine.start()
        clock.advanceBothBy(7_250L)
        val secondStopped = engine.stop()

        assertEquals(secondStartEpoch, secondStopped.startEpochMs)
        assertEquals(secondStartEpoch + 7_250L, secondStopped.endEpochMs)
        assertEquals(7_250L, secondStopped.effectiveDurationMs)
        assertEquals(0L, secondStopped.pausedDurationMs)
        assertEquals(7_250L, secondStopped.elapsedDurationMs)

        // Reset mid-Session (e.g., discarded Session while RECORDING or PAUSED)
        engine.reset()
        engine.start()
        clock.advanceBothBy(2_000L)
        engine.pause()
        engine.reset()
        assertEquals(DurationTrackingState.IDLE, engine.currentState())
        assertNull(engine.startEpochMs())
        assertNull(engine.pauseIntervalStartRealtimeMs())
        assertEquals(0L, engine.elapsedDurationMs())
    }

    @Test
    fun invalidTransitions_throwIllegalStateException() {
        val clock = FakeSessionClock()
        val engine = MonotonicDurationEngine(clock)

        // From IDLE
        assertThrows(IllegalStateException::class.java) { engine.pause() }
        assertThrows(IllegalStateException::class.java) { engine.resume() }
        assertThrows(IllegalStateException::class.java) { engine.stop() }

        // From RECORDING
        engine.start()
        assertThrows(IllegalStateException::class.java) { engine.start() }
        assertThrows(IllegalStateException::class.java) { engine.resume() }

        // From PAUSED
        engine.pause()
        assertThrows(IllegalStateException::class.java) { engine.start() }
        assertThrows(IllegalStateException::class.java) { engine.pause() }

        // From STOPPED
        engine.stop()
        assertThrows(IllegalStateException::class.java) { engine.start() }
        assertThrows(IllegalStateException::class.java) { engine.pause() }
        assertThrows(IllegalStateException::class.java) { engine.resume() }
        assertThrows(IllegalStateException::class.java) { engine.stop() }
    }

    @Test
    fun durationSnapshot_enforcesNonNegativeAndAdditiveInvariants() {
        assertThrows(IllegalArgumentException::class.java) {
            DurationSnapshot(
                state = DurationTrackingState.RECORDING,
                startEpochMs = 1_000L,
                endEpochMs = null,
                effectiveDurationMs = -1L,
                pausedDurationMs = 0L,
                elapsedDurationMs = -1L
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DurationSnapshot(
                state = DurationTrackingState.PAUSED,
                startEpochMs = 1_000L,
                endEpochMs = null,
                effectiveDurationMs = 1_000L,
                pausedDurationMs = -1L,
                elapsedDurationMs = 999L
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DurationSnapshot(
                state = DurationTrackingState.STOPPED,
                startEpochMs = 1_000L,
                endEpochMs = 3_000L,
                effectiveDurationMs = 1_000L,
                pausedDurationMs = 500L,
                elapsedDurationMs = 2_000L
            )
        }
    }

    @Test
    fun defensiveNonNegativeClamp_protectsAgainstRegressingClockImplementation() {
        var realtime = 5_000L
        val nonMonotonicClock = object : SessionClock {
            override fun elapsedRealtimeMs(): Long = realtime
            override fun currentEpochMs(): Long = 1_700_000_000_000L
        }
        val engine = MonotonicDurationEngine(nonMonotonicClock)

        engine.start()
        realtime = 4_000L // Simulated broken clock regression during RECORDING
        assertEquals(0L, engine.effectiveDurationMs())
        assertEquals(0L, engine.elapsedDurationMs())

        val paused = engine.pause()
        assertEquals(0L, paused.effectiveDurationMs)
        realtime = 3_000L // Simulated broken clock regression during PAUSED
        assertEquals(0L, engine.pausedDurationMs())

        val stopped = engine.stop()
        assertEquals(0L, stopped.effectiveDurationMs)
        assertEquals(0L, stopped.pausedDurationMs)
        assertEquals(0L, stopped.elapsedDurationMs)
    }

    @Test
    fun concurrentSnapshotsAndTransitions_neverProduceTornReads() {
        val clock = FakeSessionClock(initialRealtimeMs = 1_000L, initialEpochMs = 1_700_000_000_000L)
        val engine = MonotonicDurationEngine(clock)
        val executor = Executors.newFixedThreadPool(4)
        val startGate = CountDownLatch(1)
        val doneLatch = CountDownLatch(4)
        val errors = mutableListOf<Throwable>()

        try {
            val writer = Runnable {
                try {
                    startGate.await()
                    repeat(50) {
                        engine.start()
                        clock.advanceBothBy(10L)
                        engine.pause()
                        clock.advanceBothBy(5L)
                        engine.resume()
                        clock.advanceBothBy(15L)
                        engine.stop()
                        engine.reset()
                    }
                } catch (t: Throwable) {
                    synchronized(errors) { errors += t }
                } finally {
                    doneLatch.countDown()
                }
            }

            val reader = Runnable {
                try {
                    startGate.await()
                    repeat(500) {
                        val snap = engine.snapshot()
                        assertEquals(
                            snap.effectiveDurationMs + snap.pausedDurationMs,
                            snap.elapsedDurationMs
                        )
                        assertTrue(snap.effectiveDurationMs >= 0L)
                        assertTrue(snap.pausedDurationMs >= 0L)
                    }
                } catch (t: Throwable) {
                    synchronized(errors) { errors += t }
                } finally {
                    doneLatch.countDown()
                }
            }

            executor.submit(writer)
            repeat(3) { executor.submit(reader) }
            startGate.countDown()

            assertTrue("Concurrent test timed out", doneLatch.await(10, TimeUnit.SECONDS))
            assertTrue("Unexpected concurrency errors: $errors", errors.isEmpty())
        } finally {
            executor.shutdownNow()
        }
    }
}
