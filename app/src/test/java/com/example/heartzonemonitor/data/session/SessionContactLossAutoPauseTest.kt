package com.example.heartzonemonitor.data.session

import com.example.heartzonemonitor.data.ble.BleConnectionState
import com.example.heartzonemonitor.data.ble.BleOperationResult
import com.example.heartzonemonitor.data.ble.FakeBleTransport
import com.example.heartzonemonitor.data.ble.HrmGattClient
import com.example.heartzonemonitor.data.local.FakeHeartRateSampleDao
import com.example.heartzonemonitor.data.local.FakeRememberedHrmDao
import com.example.heartzonemonitor.data.local.FakeSessionDao
import com.example.heartzonemonitor.data.local.FakeUserProfileDao
import com.example.heartzonemonitor.data.local.HeartRateSampleBuffer
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.SessionStatus
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import com.example.heartzonemonitor.domain.timing.FakeSessionClock
import com.example.heartzonemonitor.domain.timing.MonotonicDurationEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionContactLossAutoPauseTest {

    @Test
    fun transientContactLossUnder5s_recoversWithoutPause() =
        runTest {
            val harness = createHarness(
                scope = this,
                sessionIds = listOf("session-transient-contact-loss")
            )
            val observedStates = Channel<SessionUiState>(Channel.UNLIMITED)
            val stateCollector = backgroundScope.launch {
                harness.repository.uiState.collect { observedStates.trySend(it) }
            }
            try {
                harness.connectInitializeAndPrimeTelemetry(bpm = 132)
                assertEquals(
                    StartSessionResult.Started("session-transient-contact-loss"),
                    harness.repository.startSession()
                )

                // Record 2 valid initial 1 Hz Heart Rate Samples
                repeat(2) { idx ->
                    harness.advanceBothClocks(1_000L)
                    harness.emitHrPacket(
                        macAddress = ACTIVE_HRM_MAC,
                        bpm = 134 + idx,
                        contactDetected = true
                    )
                    runCurrent()
                }
                assertEquals(2, (harness.repository.uiState.value as SessionUiState.Recording).sampleCount)
                drainChannel(observedStates)

                // Lose skin contact at t=0ms with 0 BPM -> immediate sensorContact = false while staying Recording
                harness.emitHrPacket(
                    macAddress = ACTIVE_HRM_MAC,
                    bpm = 0,
                    contactDetected = false
                )
                runCurrent()

                val immediateLoss = observedStates.receive() as SessionUiState.Recording
                assertEquals(false, immediateLoss.sensorContact)
                assertNull(immediateLoss.currentBpm)
                assertEquals(2, immediateLoss.sampleCount)
                assertEquals(2, harness.sampleBuffer.pendingCount())

                // Stream contact-lost packets (both 0-BPM and stale non-zero BPM) up to 4,999ms:
                // zero fake samples enqueued, Session remains Recording
                harness.advanceBothClocks(1_000L)
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 148, contactDetected = false)
                runCurrent()

                harness.advanceBothClocks(2_000L)
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 0, contactDetected = false)
                runCurrent()

                harness.advanceBothClocks(1_999L)
                runCurrent()

                val at4999ms = harness.repository.uiState.value as SessionUiState.Recording
                assertEquals(false, at4999ms.sensorContact)
                assertEquals(2, at4999ms.sampleCount)
                assertEquals(0L, at4999ms.pausedDurationMs)
                assertEquals(
                    SessionStatus.RECORDING,
                    harness.sessionDao.getSessionById("session-transient-contact-loss")!!.status
                )

                // Restore contact at 4,999ms (< 5s debounce) -> cancels debounce, stays Recording, persists sample
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 145, contactDetected = true)
                runCurrent()

                val restored = harness.repository.uiState.value as SessionUiState.Recording
                assertEquals(true, restored.sensorContact)
                assertEquals(145, restored.currentBpm)
                assertEquals(3, restored.sampleCount)

                // Advance well past original 5s window and stream 4 more valid samples -> never pauses
                repeat(4) { idx ->
                    harness.advanceBothClocks(1_000L)
                    harness.emitHrPacket(
                        macAddress = ACTIVE_HRM_MAC,
                        bpm = 146 + idx,
                        contactDetected = true
                    )
                    runCurrent()
                }

                val postRecoveryEmissions = drainChannel(observedStates)
                assertTrue(
                    "Transient <5s contact loss must never transition Session to Paused",
                    postRecoveryEmissions.none { it is SessionUiState.Paused }
                )

                val completed = harness.repository.stopSession() as StopSessionOutcome.Completed
                assertEquals(0L, completed.session.pausedDurationMs)
                val persisted = harness.sampleDao.getSamplesForSession("session-transient-contact-loss")
                assertEquals(listOf(134, 135, 145, 146, 147, 148, 149), persisted.map { it.bpm })
                assertTrue(persisted.all { it.sensorContact && it.bpm > 0 })
            } finally {
                stateCollector.cancel()
                observedStates.close()
                harness.close()
            }
        }

    @Test
    fun fiveSecondsContactLoss_autoPausesAndAutoResumes() =
        runTest {
            val harness = createHarness(
                scope = this,
                sessionIds = listOf("session-contact-auto-pause"),
                flushIntervalMs = 30_000L
            )
            val observedStates = Channel<SessionUiState>(Channel.UNLIMITED)
            val stateCollector = backgroundScope.launch {
                harness.repository.uiState.collect { observedStates.trySend(it) }
            }
            try {
                harness.connectInitializeAndPrimeTelemetry(bpm = 130)
                assertEquals(
                    StartSessionResult.Started("session-contact-auto-pause"),
                    harness.repository.startSession()
                )

                // Record 2 samples (< 5s periodic flush interval so both remain in memory buffer)
                repeat(2) { idx ->
                    harness.advanceBothClocks(1_000L)
                    harness.emitHrPacket(
                        macAddress = ACTIVE_HRM_MAC,
                        bpm = 136 + idx,
                        contactDetected = true
                    )
                    runCurrent()
                }
                assertEquals(2, harness.sampleBuffer.pendingCount())
                assertEquals(0, harness.sampleDao.getSampleCountForSession("session-contact-auto-pause"))
                drainChannel(observedStates)

                // Episode 1: continuous 1 Hz contact-lost frames (0 BPM & stale non-zero BPM) for 5s.
                // Subsequent frames must NOT reset the original 5,000ms debounce timer.
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 0, contactDetected = false)
                runCurrent()
                assertEquals(false, (harness.repository.uiState.value as SessionUiState.Recording).sensorContact)

                val contactLostFrames = listOf(140, 0, 152, 0)
                contactLostFrames.forEach { bpm ->
                    harness.advanceBothClocks(1_000L)
                    harness.emitHrPacket(
                        macAddress = ACTIVE_HRM_MAC,
                        bpm = bpm,
                        contactDetected = false
                    )
                    runCurrent()
                }

                // At +4,999ms: still Recording, zero fake samples persisted
                harness.advanceBothClocks(999L)
                runCurrent()
                val beforeThreshold = harness.repository.uiState.value as SessionUiState.Recording
                assertEquals(false, beforeThreshold.sensorContact)
                assertEquals(2, beforeThreshold.sampleCount)
                assertEquals(2, harness.sampleBuffer.pendingCount())
                assertEquals(0, harness.sampleDao.getSampleCountForSession("session-contact-auto-pause"))

                // At exact +5,000ms (+1ms): transitions to Paused(SENSOR_CONTACT_LOST), flushes buffer, persists PAUSED
                harness.advanceBothClocks(1L)
                runCurrent()

                val paused = harness.repository.uiState.value as SessionUiState.Paused
                assertEquals(PauseReason.SENSOR_CONTACT_LOST, paused.reason)
                assertEquals(false, paused.sensorContact)
                assertEquals(7_000L, paused.effectiveDurationMs)
                assertEquals(0L, paused.pausedDurationMs)
                assertEquals(2, paused.sampleCount)
                assertEquals(0, harness.sampleBuffer.pendingCount())
                assertEquals(2, harness.sampleDao.getSampleCountForSession("session-contact-auto-pause"))
                assertEquals(
                    SessionStatus.PAUSED,
                    harness.sessionDao.getSessionById("session-contact-auto-pause")!!.status
                )

                // Additional contact-lost frames while paused produce zero fake samples & accumulate Paused Duration
                repeat(4) { idx ->
                    harness.advanceBothClocks(1_000L)
                    harness.emitHrPacket(
                        macAddress = ACTIVE_HRM_MAC,
                        bpm = if (idx % 2 == 0) 0 else 160,
                        contactDetected = false
                    )
                    runCurrent()
                }
                val stillPaused = harness.repository.uiState.value as SessionUiState.Paused
                assertEquals(PauseReason.SENSOR_CONTACT_LOST, stillPaused.reason)
                assertEquals(7_000L, stillPaused.effectiveDurationMs)
                assertEquals(4_000L, stillPaused.pausedDurationMs)
                assertEquals(2, stillPaused.sampleCount)
                assertEquals(0, harness.sampleBuffer.pendingCount())

                // Restore contact with valid BPM -> hands-free auto-resume AND immediately enqueues restoring sample
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 156, contactDetected = true)
                runCurrent()

                val resumed = harness.repository.uiState.value as SessionUiState.Recording
                assertEquals(true, resumed.sensorContact)
                assertEquals(156, resumed.currentBpm)
                assertEquals(3, resumed.sampleCount)
                assertEquals(7_000L, resumed.effectiveDurationMs)
                assertEquals(4_000L, resumed.pausedDurationMs)
                assertEquals(
                    SessionStatus.RECORDING,
                    harness.sessionDao.getSessionById("session-contact-auto-pause")!!.status
                )

                // Follow-up valid sample persists normally
                harness.advanceBothClocks(1_000L)
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 158, contactDetected = true)
                runCurrent()
                assertEquals(4, (harness.repository.uiState.value as SessionUiState.Recording).sampleCount)

                // Episode 2: repeated contact loss auto-pauses again, then contact restores with bpm = 0
                // (must auto-resume WITHOUT persisting a fake 0-BPM sample)
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 0, contactDetected = false)
                runCurrent()
                harness.advanceBothClocks(SessionRepository.SENSOR_CONTACT_LOSS_DEBOUNCE_MS)
                runCurrent()

                val pausedSecond = harness.repository.uiState.value as SessionUiState.Paused
                assertEquals(PauseReason.SENSOR_CONTACT_LOST, pausedSecond.reason)
                assertEquals(4, harness.sampleDao.getSampleCountForSession("session-contact-auto-pause"))

                harness.advanceBothClocks(2_000L)
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 0, contactDetected = true)
                runCurrent()

                val resumedZeroBpm = harness.repository.uiState.value as SessionUiState.Recording
                assertEquals(true, resumedZeroBpm.sensorContact)
                assertNull(resumedZeroBpm.currentBpm)
                assertEquals(4, resumedZeroBpm.sampleCount)
                assertEquals(0, harness.sampleBuffer.pendingCount())

                // Next valid BPM sample resumes persistence cleanly
                harness.advanceBothClocks(1_000L)
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 162, contactDetected = true)
                runCurrent()

                val completed = harness.repository.stopSession() as StopSessionOutcome.Completed
                // Effective: 2s + 5s (ep1 debounce) + 1s + 5s (ep2 debounce) + 1s = 14s
                // Paused: 4s (ep1) + 2s (ep2) = 6s
                assertEquals(14_000L, completed.session.effectiveDurationMs)
                assertEquals(6_000L, completed.session.pausedDurationMs)
                assertEquals(20_000L, completed.session.elapsedDurationMs)

                val persisted = harness.sampleDao.getSamplesForSession("session-contact-auto-pause")
                assertEquals(listOf(136, 137, 156, 158, 162), persisted.map { it.bpm })
                assertTrue("Honest telemetry: zero fake 0-BPM or no-contact samples", persisted.all { it.bpm > 0 && it.sensorContact })

                val allTransitions = drainChannel(observedStates)
                assertTrue(allTransitions.any { it is SessionUiState.Paused && it.reason == PauseReason.SENSOR_CONTACT_LOST })
            } finally {
                stateCollector.cancel()
                observedStates.close()
                harness.close()
            }
        }

    @Test
    fun userManualPause_preservedOnContactRestore() =
        runTest {
            val harness = createHarness(
                scope = this,
                sessionIds = listOf("session-manual-pause-guard")
            )
            try {
                harness.connectInitializeAndPrimeTelemetry(bpm = 128)
                assertEquals(
                    StartSessionResult.Started("session-manual-pause-guard"),
                    harness.repository.startSession()
                )

                // Start 5s contact-loss debounce, then manually pause at +2s
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 0, contactDetected = false)
                runCurrent()
                harness.advanceBothClocks(2_000L)
                assertTrue(harness.repository.pauseSession(PauseReason.USER_MANUAL))

                // Advance past the original 5s debounce deadline -> stays Paused(USER_MANUAL)
                harness.advanceBothClocks(5_000L)
                runCurrent()
                val manualPaused = harness.repository.uiState.value as SessionUiState.Paused
                assertEquals(PauseReason.USER_MANUAL, manualPaused.reason)

                // Restoring sensor contact must NOT auto-resume a USER_MANUAL pause or enqueue samples
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 144, contactDetected = true)
                runCurrent()

                val afterRestore = harness.repository.uiState.value as SessionUiState.Paused
                assertEquals(PauseReason.USER_MANUAL, afterRestore.reason)
                assertEquals(true, afterRestore.sensorContact)
                assertEquals(0, afterRestore.sampleCount)
                assertEquals(0, harness.sampleBuffer.pendingCount())

                // Losing and restoring contact again while manually paused still never auto-resumes
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 0, contactDetected = false)
                runCurrent()
                harness.advanceBothClocks(6_000L)
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 150, contactDetected = true)
                runCurrent()

                val stillManualPaused = harness.repository.uiState.value as SessionUiState.Paused
                assertEquals(PauseReason.USER_MANUAL, stillManualPaused.reason)
                assertEquals(true, stillManualPaused.sensorContact)
                assertEquals(0, stillManualPaused.sampleCount)

                // BLE_CONNECTION_LOST pause must likewise never auto-resume on sensor contact packets
                assertTrue(harness.repository.resumeSession())
                assertTrue(harness.repository.pauseSession(PauseReason.BLE_CONNECTION_LOST))
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 0, contactDetected = false)
                runCurrent()
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 152, contactDetected = true)
                runCurrent()

                val stillBlePaused = harness.repository.uiState.value as SessionUiState.Paused
                assertEquals(PauseReason.BLE_CONNECTION_LOST, stillBlePaused.reason)
                assertEquals(true, stillBlePaused.sensorContact)
                assertEquals(0, stillBlePaused.sampleCount)
            } finally {
                harness.close()
            }
        }

    @Test
    fun bleDisconnectDuringDebounce_cancelsContactTimer() =
        runTest {
            val harness = createHarness(
                scope = this,
                sessionIds = listOf("session-ble-disconnect-during-debounce")
            )
            try {
                harness.connectInitializeAndPrimeTelemetry(bpm = 135)
                assertEquals(
                    StartSessionResult.Started("session-ble-disconnect-during-debounce"),
                    harness.repository.startSession()
                )

                // Contact lost at t=0ms -> starts 5s contact-loss debounce
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 0, contactDetected = false)
                runCurrent()
                assertEquals(false, (harness.repository.uiState.value as SessionUiState.Recording).sensorContact)

                // BLE link drops at t=2,000ms (with reconnect failing so 10s BLE grace runs)
                harness.advanceBothClocks(2_000L)
                harness.bleTransport.onConnectAttempt = { _, _ ->
                    BleOperationResult.Failure(
                        status = BleOperationResult.STATUS_TIMEOUT,
                        message = "Out of range"
                    )
                }
                harness.bleTransport.simulateDisconnect()
                runCurrent()

                // Advance to t=6,000ms (past original 5s contact-loss mark, inside 10s BLE grace window):
                // Must still be Recording (contact-loss timer was cancelled on disconnect)
                harness.advanceBothClocks(4_000L)
                runCurrent()
                val duringBleGrace = harness.repository.uiState.value as SessionUiState.Recording
                assertTrue(duringBleGrace.connectionState is BleConnectionState.Reconnecting)
                assertNull(duringBleGrace.sensorContact)

                // Advance remaining 6,000ms (10s after BLE disconnect) -> Paused(BLE_CONNECTION_LOST), not SENSOR_CONTACT_LOST
                harness.advanceBothClocks(6_000L)
                runCurrent()
                val blePaused = harness.repository.uiState.value as SessionUiState.Paused
                assertEquals(PauseReason.BLE_CONNECTION_LOST, blePaused.reason)
            } finally {
                harness.close()
            }
        }

    @Test
    fun notSupportedHrm_neverTriggersFalseContactLoss() =
        runTest {
            val harness = createHarness(
                scope = this,
                sessionIds = listOf("session-not-supported-hrm")
            )
            try {
                harness.bleTransport.connect(ACTIVE_HRM_MAC)
                assertTrue(harness.gattClient.initializeConnectedHrm(ACTIVE_HRM_MAC) is BleOperationResult.Success)
                // Prime with contactSupported = false (bits 1-2 = 00 -> SensorContactStatus.NOT_SUPPORTED)
                harness.emitHrPacket(
                    macAddress = ACTIVE_HRM_MAC,
                    bpm = 126,
                    contactSupported = false,
                    contactDetected = false
                )
                harness.repository.uiState.first { it is SessionUiState.Idle && it.canStartSession }
                assertEquals(
                    StartSessionResult.Started("session-not-supported-hrm"),
                    harness.repository.startSession()
                )

                // Stream 8 seconds of NOT_SUPPORTED packets (both bits 00 and bits 01 = flags 0x02)
                repeat(6) { idx ->
                    harness.advanceBothClocks(1_000L)
                    harness.emitHrPacket(
                        macAddress = ACTIVE_HRM_MAC,
                        bpm = 130 + idx,
                        contactSupported = false,
                        contactDetected = false
                    )
                    runCurrent()
                }
                // Raw payload with flags 0x02 (bit 2 = 0, bit 1 = 1 -> NOT_SUPPORTED)
                harness.advanceBothClocks(2_000L)
                harness.bleTransport.emitNotification(
                    macAddress = ACTIVE_HRM_MAC,
                    value = byteArrayOf(0x02, 140.toByte()),
                    timestampEpochMs = harness.clock.currentEpochMs()
                )
                runCurrent()

                // Even a 0-BPM packet from a NOT_SUPPORTED HRM does not trigger contact-loss auto-pause
                harness.advanceBothClocks(1_000L)
                harness.emitHrPacket(
                    macAddress = ACTIVE_HRM_MAC,
                    bpm = 0,
                    contactSupported = false,
                    contactDetected = false
                )
                runCurrent()
                harness.advanceBothClocks(6_000L)
                runCurrent()

                val recording = harness.repository.uiState.value as SessionUiState.Recording
                assertEquals(true, recording.sensorContact)
                assertEquals(0L, recording.pausedDurationMs)
                assertEquals(7, recording.sampleCount)

                val completed = harness.repository.stopSession() as StopSessionOutcome.Completed
                assertEquals(0L, completed.session.pausedDurationMs)
                val persisted = harness.sampleDao.getSamplesForSession("session-not-supported-hrm")
                assertEquals(listOf(130, 131, 132, 133, 134, 135, 140), persisted.map { it.bpm })
            } finally {
                harness.close()
            }
        }

    @Test
    fun stopDiscardAndClose_cancelDebounceTimers() =
        runTest {
            val harness = createHarness(
                scope = this,
                sessionIds = listOf("session-stop-cancel", "session-discard-cancel", "session-close-cancel")
            )
            try {
                // Case A: stopSession() cancels active contact-loss debounce
                harness.connectInitializeAndPrimeTelemetry(bpm = 130)
                assertEquals(StartSessionResult.Started("session-stop-cancel"), harness.repository.startSession())
                harness.advanceBothClocks(10_000L)
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 132, contactDetected = true)
                runCurrent()
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 0, contactDetected = false)
                runCurrent()

                val stopOutcome = harness.repository.stopSession()
                assertTrue(stopOutcome is StopSessionOutcome.Completed)
                harness.advanceBothClocks(10_000L)
                runCurrent()
                assertTrue(harness.repository.uiState.value is SessionUiState.Completed)
                assertEquals(
                    SessionStatus.COMPLETED,
                    harness.sessionDao.getSessionById("session-stop-cancel")!!.status
                )

                // Case B: discardSession() cancels active contact-loss debounce
                harness.repository.resetToIdle()
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 130, contactDetected = true)
                runCurrent()
                assertEquals(StartSessionResult.Started("session-discard-cancel"), harness.repository.startSession())
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 0, contactDetected = false)
                runCurrent()

                assertTrue(harness.repository.discardSession())
                harness.advanceBothClocks(10_000L)
                runCurrent()
                assertEquals(SessionUiState.Discarded("session-discard-cancel"), harness.repository.uiState.value)
                assertNull(harness.sessionDao.getSessionById("session-discard-cancel"))

                // Case C: close() cancels active contact-loss debounce without mutating DB session
                harness.repository.resetToIdle()
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 130, contactDetected = true)
                runCurrent()
                assertEquals(StartSessionResult.Started("session-close-cancel"), harness.repository.startSession())
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 0, contactDetected = false)
                runCurrent()

                harness.repository.close()
                advanceTimeBy(10_000L)
                runCurrent()
                assertEquals(
                    SessionStatus.RECORDING,
                    harness.sessionDao.getSessionById("session-close-cancel")!!.status
                )
            } finally {
                harness.close()
            }
        }

    private fun <T> drainChannel(channel: Channel<T>): List<T> {
        val items = mutableListOf<T>()
        while (true) {
            val next = channel.tryReceive().getOrNull() ?: break
            items.add(next)
        }
        return items
    }

    private fun createHarness(
        scope: TestScope,
        sessionIds: List<String>,
        contactLossDebounceMs: Long = SessionRepository.SENSOR_CONTACT_LOSS_DEBOUNCE_MS,
        flushIntervalMs: Long = HeartRateSampleBuffer.DEFAULT_FLUSH_INTERVAL_MS
    ): TestHarness {
        val bleTransport = FakeBleTransport()
        val gattClient = HrmGattClient(bleTransport = bleTransport, scope = scope)
        val sampleDao = FakeHeartRateSampleDao()
        val sessionDao = FakeSessionDao(sampleDao = sampleDao)
        val hrmDao = FakeRememberedHrmDao(initialHrms = listOf(activeHrm()))
        val profileDao = FakeUserProfileDao(initialProfile = defaultProfile())
        val clock = FakeSessionClock(
            initialRealtimeMs = 10_000L,
            initialEpochMs = 1_700_000_000_000L
        )
        val durationEngine = MonotonicDurationEngine(clock)
        val sampleBuffer = HeartRateSampleBuffer(
            sampleDao = sampleDao,
            scope = scope,
            flushIntervalMs = flushIntervalMs
        )
        val idQueue = ArrayDeque(sessionIds)
        val repository = SessionRepository(
            bleTransport = bleTransport,
            rememberedHrmDao = hrmDao,
            userProfileDao = profileDao,
            sessionDao = sessionDao,
            sampleDao = sampleDao,
            scope = scope,
            hrmGattClient = gattClient,
            clock = clock,
            durationEngine = durationEngine,
            sampleBuffer = sampleBuffer,
            currentYearProvider = { TEST_CURRENT_YEAR },
            sessionIdGenerator = { idQueue.removeFirstOrNull() ?: "generated-session" },
            contactLossDebounceMs = contactLossDebounceMs
        )
        return TestHarness(
            testScope = scope,
            bleTransport = bleTransport,
            gattClient = gattClient,
            sampleDao = sampleDao,
            sessionDao = sessionDao,
            clock = clock,
            sampleBuffer = sampleBuffer,
            repository = repository
        )
    }

    private class TestHarness(
        private val testScope: TestScope,
        val bleTransport: FakeBleTransport,
        val gattClient: HrmGattClient,
        val sampleDao: FakeHeartRateSampleDao,
        val sessionDao: FakeSessionDao,
        val clock: FakeSessionClock,
        val sampleBuffer: HeartRateSampleBuffer,
        val repository: SessionRepository
    ) {
        fun advanceBothClocks(deltaMs: Long) {
            clock.advanceBothBy(deltaMs)
            testScope.advanceTimeBy(deltaMs)
        }

        suspend fun emitHrPacket(
            macAddress: String,
            bpm: Int,
            contactSupported: Boolean = true,
            contactDetected: Boolean = true,
            timestampEpochMs: Long = clock.currentEpochMs()
        ) {
            val payload = FakeBleTransport.buildHeartRateMeasurementPacket(
                bpm = bpm,
                sensorContactSupported = contactSupported,
                sensorContactDetected = contactDetected
            )
            bleTransport.emitNotification(
                macAddress = macAddress,
                value = payload,
                timestampEpochMs = timestampEpochMs
            )
        }

        suspend fun connectInitializeAndPrimeTelemetry(bpm: Int = 125) {
            bleTransport.connect(ACTIVE_HRM_MAC)
            val initResult = gattClient.initializeConnectedHrm(ACTIVE_HRM_MAC)
            assertTrue(initResult is BleOperationResult.Success)
            emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = bpm, contactDetected = true)
            repository.uiState.first { it is SessionUiState.Idle && it.canStartSession }
        }

        suspend fun close() {
            sampleBuffer.discard()
            gattClient.close()
            repository.close()
        }
    }

    private fun defaultProfile(): UserProfileEntity = UserProfileEntity(
        birthYear = 1996,
        restingHr = null,
        customHrmax = null,
        calculationMode = ZoneCalculationMode.PERCENT_HRMAX,
        updatedAtEpochMs = 1_700_000_000_000L
    )

    private fun activeHrm(): RememberedHrmEntity = RememberedHrmEntity(
        macAddress = ACTIVE_HRM_MAC,
        advertisedName = "COOSPO H808S",
        customNickname = "Chest Strap",
        lastSeenTimestamp = 1_700_000_000_000L,
        batteryLevel = 90,
        isActive = true
    )

    companion object {
        private const val TEST_CURRENT_YEAR = 2026
        private const val ACTIVE_HRM_MAC = "AA:BB:CC:DD:EE:01"
    }
}
