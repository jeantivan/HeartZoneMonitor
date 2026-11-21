package com.example.heartzonemonitor.data.session

import com.example.heartzonemonitor.data.ble.BleConnectionState
import com.example.heartzonemonitor.data.ble.BleOperationResult
import com.example.heartzonemonitor.data.ble.BleRecordedOperation
import com.example.heartzonemonitor.data.ble.BleReconnectionPolicy
import com.example.heartzonemonitor.data.ble.BleUuids
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionDisconnectReconnectionTest {

    @Test
    fun disconnectUnderGrace_recoversSeamlessly() =
        runTest {
            val harness = createHarness(
                scope = this,
                sessionIds = listOf("session-grace-recovery")
            )
            val observedUiStates = Channel<SessionUiState>(Channel.UNLIMITED)
            val observedConnectionStates = Channel<BleConnectionState>(Channel.UNLIMITED)
            val uiCollector = backgroundScope.launch {
                harness.repository.uiState.collect { observedUiStates.trySend(it) }
            }
            val connCollector = backgroundScope.launch {
                harness.repository.connectionState.collect { observedConnectionStates.trySend(it) }
            }

            try {
                harness.connectInitializeAndPrimeTelemetry(bpm = 128)
                assertEquals(
                    StartSessionResult.Started("session-grace-recovery"),
                    harness.repository.startSession()
                )

                // Record 3 initial 1 Hz Heart Rate Samples before link drops.
                repeat(3) { idx ->
                    harness.advanceBothClocks(1_000L)
                    harness.emitHrPacket(
                        macAddress = ACTIVE_HRM_MAC,
                        bpm = 130 + idx,
                        timestampEpochMs = harness.clock.currentEpochMs()
                    )
                    runCurrent()
                }
                assertEquals(3, (harness.repository.uiState.value as SessionUiState.Recording).sampleCount)

                // Drain pre-disconnect emissions so we inspect strictly post-disconnect transitions.
                drainChannel(observedUiStates)
                drainChannel(observedConnectionStates)
                harness.bleTransport.clearRecordedOperations()

                // Fail attempts 1 (0s) and 2 (2s), then succeed on attempt 3 (7s < 10s grace period).
                var connectAttempts = 0
                harness.bleTransport.onConnectAttempt = { _, _ ->
                    connectAttempts += 1
                    if (connectAttempts < 3) {
                        BleOperationResult.Failure(
                            status = BleOperationResult.STATUS_TIMEOUT,
                            message = "Transient link loss"
                        )
                    } else {
                        BleOperationResult.Success(Unit)
                    }
                }

                // Drop BLE link.
                harness.bleTransport.simulateDisconnect()
                runCurrent()

                // First emission must immediately expose Reconnecting(mac, 1) while Session stays Recording.
                val connAfterDisconnect = drainChannel(observedConnectionStates)
                assertTrue(connAfterDisconnect.isNotEmpty())
                assertEquals(
                    BleConnectionState.Reconnecting(ACTIVE_HRM_MAC, attempt = 1),
                    connAfterDisconnect.first()
                )
                assertEquals(
                    BleConnectionState.Reconnecting(ACTIVE_HRM_MAC, attempt = 2),
                    harness.repository.connectionState.value
                )

                val uiAfterDisconnect = drainChannel(observedUiStates)
                assertTrue(uiAfterDisconnect.none { it is SessionUiState.Paused })
                val recordingDuringReconnect = harness.repository.uiState.value as SessionUiState.Recording
                assertEquals(
                    BleConnectionState.Reconnecting(ACTIVE_HRM_MAC, attempt = 2),
                    recordingDuringReconnect.connectionState
                )
                assertNull(recordingDuringReconnect.currentBpm)
                assertNull(recordingDuringReconnect.sensorContact)
                assertEquals(SessionStatus.RECORDING, harness.sessionDao.getSessionById("session-grace-recovery")!!.status)

                // Advance 2s (attempt 2 fails) then +5s to 7s (attempt 3 succeeds within 10s grace window).
                harness.advanceBothClocks(2_000L)
                runCurrent()
                assertEquals(2, connectAttempts)
                assertEquals(
                    BleConnectionState.Reconnecting(ACTIVE_HRM_MAC, attempt = 3),
                    harness.repository.connectionState.value
                )

                harness.advanceBothClocks(5_000L)
                runCurrent()
                assertEquals(3, connectAttempts)

                // Verify link restored, GATT re-initialized (discover + notify + 0x2902 write), zero pauses, zero alerts, zero fake samples.
                assertEquals(
                    BleConnectionState.Connected(ACTIVE_HRM_MAC),
                    harness.repository.connectionState.value
                )
                val recordingRestored = harness.repository.uiState.value as SessionUiState.Recording
                assertEquals(
                    BleConnectionState.Connected(ACTIVE_HRM_MAC),
                    recordingRestored.connectionState
                )
                assertEquals(0L, recordingRestored.pausedDurationMs)
                assertEquals(3, recordingRestored.sampleCount)
                assertTrue(harness.alertNotifier.alerts.isEmpty())

                val allUiDuringRecovery = drainChannel(observedUiStates)
                assertTrue(
                    "Session must never enter Paused when recovering under 10s",
                    allUiDuringRecovery.none { it is SessionUiState.Paused }
                )

                val ops = harness.bleTransport.recordedOperations
                assertTrue(ops.any { it == BleRecordedOperation.Connect(ACTIVE_HRM_MAC, autoConnect = false) })
                assertTrue(ops.any { it == BleRecordedOperation.DiscoverServices(ACTIVE_HRM_MAC) })
                assertTrue(
                    ops.any {
                        it == BleRecordedOperation.SetCharacteristicNotification(
                            macAddress = ACTIVE_HRM_MAC,
                            serviceUuid = BleUuids.HEART_RATE_SERVICE_UUID,
                            characteristicUuid = BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID,
                            enable = true
                        )
                    }
                )
                assertTrue(
                    ops.any {
                        it is BleRecordedOperation.WriteDescriptor &&
                            it.macAddress == ACTIVE_HRM_MAC &&
                            it.descriptorUuid == BleUuids.CCCD_DESCRIPTOR_UUID &&
                            it.value.contentEquals(HrmGattClient.CCCD_ENABLE_NOTIFICATION_VALUE)
                    }
                )

                // Advance past 10s mark and stream 3 more real 1 Hz samples; confirm zero late pause & continuous sample persistence.
                repeat(3) { idx ->
                    harness.advanceBothClocks(1_000L)
                    harness.emitHrPacket(
                        macAddress = ACTIVE_HRM_MAC,
                        bpm = 142 + idx,
                        timestampEpochMs = harness.clock.currentEpochMs()
                    )
                    runCurrent()
                }

                assertTrue(harness.alertNotifier.alerts.isEmpty())
                val outcome = harness.repository.stopSession() as StopSessionOutcome.Completed
                assertEquals(0L, outcome.session.pausedDurationMs)
                assertEquals(13_000L, outcome.session.effectiveDurationMs)
                val persisted = harness.sampleDao.getSamplesForSession("session-grace-recovery")
                assertEquals(listOf(130, 131, 132, 142, 143, 144), persisted.map { it.bpm })
            } finally {
                uiCollector.cancel()
                connCollector.cancel()
                observedUiStates.close()
                observedConnectionStates.close()
                harness.close()
            }
        }

    @Test
    fun disconnectPastGrace_backoffAndAutoPause() =
        runTest {
            val harness = createHarness(
                scope = this,
                sessionIds = listOf("session-backoff-pause")
            )
            try {
                harness.connectInitializeAndPrimeTelemetry(bpm = 125)
                assertEquals(
                    StartSessionResult.Started("session-backoff-pause"),
                    harness.repository.startSession()
                )

                // Record 2 samples (< 5s flush interval so both sit in memory buffer before disconnect).
                repeat(2) { idx ->
                    harness.advanceBothClocks(1_000L)
                    harness.emitHrPacket(
                        macAddress = ACTIVE_HRM_MAC,
                        bpm = 135 + idx,
                        timestampEpochMs = harness.clock.currentEpochMs()
                    )
                    runCurrent()
                }
                assertEquals(2, harness.sampleBuffer.pendingCount())
                assertEquals(0, harness.sampleDao.getSampleCountForSession("session-backoff-pause"))

                val disconnectVirtualTime = currentTime
                val recordedAttemptOffsets = mutableListOf<Long>()
                val recordedAutoConnectFlags = mutableListOf<Boolean>()
                harness.bleTransport.onConnectAttempt = { _, autoConnect ->
                    recordedAttemptOffsets.add(currentTime - disconnectVirtualTime)
                    recordedAutoConnectFlags.add(autoConnect)
                    BleOperationResult.Failure(
                        status = BleOperationResult.STATUS_TIMEOUT,
                        message = "Out of range"
                    )
                }

                // Trigger disconnect at offset 0ms.
                harness.bleTransport.simulateDisconnect()
                runCurrent()
                assertEquals(listOf(0L), recordedAttemptOffsets)
                assertTrue(harness.repository.uiState.value is SessionUiState.Recording)
                assertTrue(harness.alertNotifier.alerts.isEmpty())

                // Offset +2_000ms -> attempt 2
                harness.advanceBothClocks(2_000L)
                runCurrent()
                assertEquals(listOf(0L, 2_000L), recordedAttemptOffsets)
                assertTrue(harness.repository.uiState.value is SessionUiState.Recording)
                assertTrue(harness.alertNotifier.alerts.isEmpty())

                // Offset +7_000ms (delta 5_000ms) -> attempt 3
                harness.advanceBothClocks(5_000L)
                runCurrent()
                assertEquals(listOf(0L, 2_000L, 7_000L), recordedAttemptOffsets)
                assertTrue(harness.repository.uiState.value is SessionUiState.Recording)
                assertTrue(harness.alertNotifier.alerts.isEmpty())

                // Offset +10_000ms (delta 3_000ms) -> grace period expires!
                harness.advanceBothClocks(3_000L)
                runCurrent()

                val pausedState = harness.repository.uiState.value as SessionUiState.Paused
                assertEquals(PauseReason.BLE_CONNECTION_LOST, pausedState.reason)
                assertEquals(
                    BleConnectionState.Reconnecting(ACTIVE_HRM_MAC, attempt = 4),
                    pausedState.connectionState
                )
                assertEquals(12_000L, pausedState.effectiveDurationMs)
                assertEquals(0L, pausedState.pausedDurationMs)
                assertEquals(0, harness.sampleBuffer.pendingCount())
                assertEquals(2, harness.sampleDao.getSampleCountForSession("session-backoff-pause"))
                assertEquals(
                    SessionStatus.PAUSED,
                    harness.sessionDao.getSessionById("session-backoff-pause")!!.status
                )
                assertEquals(
                    listOf(AlertRecord("Chest Strap", "session-backoff-pause")),
                    harness.alertNotifier.alerts
                )

                // Verify remaining exponential backoff offsets up to +197_000ms:
                // 0s, 2s, 7s (+5s), 17s (+10s), 37s (+20s), 77s (+40s), 137s (+60s), 197s (+60s)
                harness.advanceBothClocks(7_000L)   // +17_000ms
                harness.advanceBothClocks(20_000L)  // +37_000ms
                harness.advanceBothClocks(40_000L)  // +77_000ms
                harness.advanceBothClocks(60_000L)  // +137_000ms
                harness.advanceBothClocks(60_000L)  // +197_000ms
                runCurrent()

                assertEquals(
                    listOf(0L, 2_000L, 7_000L, 17_000L, 37_000L, 77_000L, 137_000L, 197_000L),
                    recordedAttemptOffsets
                )
                assertTrue(recordedAutoConnectFlags.all { !it })
                assertEquals(1, harness.alertNotifier.alerts.size)

                // Confirm Effective Duration stayed frozen at 12s while Paused Duration accumulated 187s.
                harness.repository.refreshDurations()
                val refreshedPaused = harness.repository.uiState.value as SessionUiState.Paused
                assertEquals(12_000L, refreshedPaused.effectiveDurationMs)
                assertEquals(187_000L, refreshedPaused.pausedDurationMs)
                assertEquals(199_000L, refreshedPaused.elapsedDurationMs)
            } finally {
                harness.close()
            }
        }

    @Test
    fun reconnectAfterAutoPause_autoResumes() =
        runTest {
            val harness = createHarness(
                scope = this,
                sessionIds = listOf("session-auto-resume")
            )
            try {
                harness.connectInitializeAndPrimeTelemetry(bpm = 118)
                assertEquals(
                    StartSessionResult.Started("session-auto-resume"),
                    harness.repository.startSession()
                )

                // Record 4s before disconnect.
                repeat(4) { idx ->
                    harness.advanceBothClocks(1_000L)
                    harness.emitHrPacket(
                        macAddress = ACTIVE_HRM_MAC,
                        bpm = 120 + idx,
                        timestampEpochMs = harness.clock.currentEpochMs()
                    )
                    runCurrent()
                }

                // Fail attempts 1..3 (0s, 2s, 7s) and succeed on attempt 4 (17s -> 7s after 10s auto-pause).
                var attemptCount = 0
                harness.bleTransport.onConnectAttempt = { _, _ ->
                    attemptCount += 1
                    if (attemptCount < 4) {
                        BleOperationResult.Failure(
                            status = BleOperationResult.STATUS_TIMEOUT,
                            message = "Disconnected"
                        )
                    } else {
                        BleOperationResult.Success(Unit)
                    }
                }

                harness.bleTransport.simulateDisconnect()
                runCurrent()

                // Advance to 10s: session auto-pauses with BLE_CONNECTION_LOST (effective = 4 + 10 = 14s).
                harness.advanceBothClocks(10_000L)
                runCurrent()
                val autoPaused = harness.repository.uiState.value as SessionUiState.Paused
                assertEquals(PauseReason.BLE_CONNECTION_LOST, autoPaused.reason)
                assertEquals(14_000L, autoPaused.effectiveDurationMs)
                assertEquals(1, harness.alertNotifier.alerts.size)

                // Advance +7s to 17s: attempt 4 succeeds -> auto-resumes back to Recording!
                harness.advanceBothClocks(7_000L)
                runCurrent()

                assertEquals(4, attemptCount)
                assertEquals(
                    BleConnectionState.Connected(ACTIVE_HRM_MAC),
                    harness.repository.connectionState.value
                )
                val autoResumed = harness.repository.uiState.value as SessionUiState.Recording
                assertEquals(
                    BleConnectionState.Connected(ACTIVE_HRM_MAC),
                    autoResumed.connectionState
                )
                assertEquals(14_000L, autoResumed.effectiveDurationMs)
                assertEquals(7_000L, autoResumed.pausedDurationMs)
                assertEquals(21_000L, autoResumed.elapsedDurationMs)
                assertEquals(
                    SessionStatus.RECORDING,
                    harness.sessionDao.getSessionById("session-auto-resume")!!.status
                )

                // Stream 2 new samples after auto-resume and verify persistence + final durations.
                repeat(2) { idx ->
                    harness.advanceBothClocks(1_000L)
                    harness.emitHrPacket(
                        macAddress = ACTIVE_HRM_MAC,
                        bpm = 150 + idx,
                        timestampEpochMs = harness.clock.currentEpochMs()
                    )
                    runCurrent()
                }

                val completed = (harness.repository.stopSession() as StopSessionOutcome.Completed).session
                assertEquals(16_000L, completed.effectiveDurationMs)
                assertEquals(7_000L, completed.pausedDurationMs)
                assertEquals(23_000L, completed.elapsedDurationMs)
                assertEquals(
                    listOf(120, 121, 122, 123, 150, 151),
                    harness.sampleDao.getSamplesForSession("session-auto-resume").map { it.bpm }
                )
            } finally {
                harness.close()
            }
        }

    @Test
    fun manualPause_cancelsGraceAndNoAutoResume() =
        runTest {
            val harness = createHarness(
                scope = this,
                sessionIds = listOf("session-manual-during", "session-manual-before")
            )
            try {
                // Case A: Manual pause during 10s grace period.
                harness.connectInitializeAndPrimeTelemetry(bpm = 122)
                assertEquals(
                    StartSessionResult.Started("session-manual-during"),
                    harness.repository.startSession()
                )
                harness.advanceBothClocks(2_000L)

                var attemptCount = 0
                harness.bleTransport.onConnectAttempt = { _, _ ->
                    attemptCount += 1
                    if (attemptCount < 4) {
                        BleOperationResult.Failure(
                            status = BleOperationResult.STATUS_TIMEOUT,
                            message = "Disconnected"
                        )
                    } else {
                        BleOperationResult.Success(Unit)
                    }
                }

                harness.bleTransport.simulateDisconnect()
                runCurrent()
                assertTrue(harness.repository.uiState.value is SessionUiState.Recording)

                // User manually pauses at +4s (< 10s grace period).
                harness.advanceBothClocks(4_000L)
                assertTrue(harness.repository.pauseSession(PauseReason.USER_MANUAL))
                assertEquals(
                    PauseReason.USER_MANUAL,
                    (harness.repository.uiState.value as SessionUiState.Paused).reason
                )

                // Advance past 10s (+6s -> 10s, then +7s -> 17s where attempt 4 succeeds).
                harness.advanceBothClocks(6_000L)
                runCurrent()
                assertEquals(
                    PauseReason.USER_MANUAL,
                    (harness.repository.uiState.value as SessionUiState.Paused).reason
                )
                assertTrue(harness.alertNotifier.alerts.isEmpty())

                harness.advanceBothClocks(7_000L)
                runCurrent()
                assertEquals(4, attemptCount)
                assertEquals(
                    BleConnectionState.Connected(ACTIVE_HRM_MAC),
                    harness.repository.connectionState.value
                )
                // Must remain Paused(USER_MANUAL) even after BLE restores!
                val stillManualPaused = harness.repository.uiState.value as SessionUiState.Paused
                assertEquals(PauseReason.USER_MANUAL, stillManualPaused.reason)
                assertEquals(
                    BleConnectionState.Connected(ACTIVE_HRM_MAC),
                    stillManualPaused.connectionState
                )
                assertTrue(harness.alertNotifier.alerts.isEmpty())

                assertTrue(harness.repository.discardSession())
                harness.repository.resetToIdle()

                // Case B: Manual pause BEFORE BLE disconnects -> no grace period, no alert, no auto-resume on reconnect.
                harness.bleTransport.onConnectAttempt = null
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 126)
                runCurrent()
                assertEquals(
                    StartSessionResult.Started("session-manual-before"),
                    harness.repository.startSession()
                )
                harness.advanceBothClocks(2_000L)
                assertTrue(harness.repository.pauseSession(PauseReason.USER_MANUAL))

                harness.bleTransport.simulateDisconnect()
                runCurrent()
                harness.advanceBothClocks(15_000L)
                runCurrent()

                assertEquals(
                    PauseReason.USER_MANUAL,
                    (harness.repository.uiState.value as SessionUiState.Paused).reason
                )
                assertTrue(harness.alertNotifier.alerts.isEmpty())

                harness.bleTransport.connect(ACTIVE_HRM_MAC)
                runCurrent()
                val afterExternalReconnect = harness.repository.uiState.value as SessionUiState.Paused
                assertEquals(PauseReason.USER_MANUAL, afterExternalReconnect.reason)
                assertEquals(
                    BleConnectionState.Connected(ACTIVE_HRM_MAC),
                    afterExternalReconnect.connectionState
                )
                assertTrue(harness.alertNotifier.alerts.isEmpty())
            } finally {
                harness.close()
            }
        }

    @Test
    fun stopDiscardClose_cancelReconnectCleanly() =
        runTest {
            val harness = createHarness(
                scope = this,
                sessionIds = listOf("session-stop", "session-discard", "session-close")
            )
            try {
                // 1. stopSession(forceSave = true) cancels reconnection & grace timers cleanly.
                harness.connectInitializeAndPrimeTelemetry(bpm = 132)
                assertEquals(StartSessionResult.Started("session-stop"), harness.repository.startSession())
                var connectCalls = 0
                harness.bleTransport.onConnectAttempt = { _, _ ->
                    connectCalls += 1
                    BleOperationResult.Failure(
                        status = BleOperationResult.STATUS_TIMEOUT,
                        message = "Disconnected"
                    )
                }

                harness.bleTransport.simulateDisconnect()
                runCurrent()
                assertEquals(1, connectCalls)

                harness.advanceBothClocks(3_000L)
                runCurrent()
                assertEquals(2, connectCalls)

                val stopOutcome = harness.repository.stopSession(forceSave = true)
                assertTrue(stopOutcome is StopSessionOutcome.Completed)
                assertEquals(
                    BleConnectionState.Disconnected,
                    harness.repository.connectionState.value
                )

                // Advancing virtual time by 2 minutes must trigger zero further reconnects or alerts.
                harness.advanceBothClocks(120_000L)
                runCurrent()
                assertEquals(2, connectCalls)
                assertTrue(harness.alertNotifier.alerts.isEmpty())

                // 2. Micro-session prompt keeps reconnection alive, then discardSession() cancels it.
                harness.bleTransport.onConnectAttempt = null
                harness.repository.resetToIdle()
                harness.connectInitializeAndPrimeTelemetry(bpm = 132)
                assertEquals(StartSessionResult.Started("session-discard"), harness.repository.startSession())

                connectCalls = 0
                harness.bleTransport.onConnectAttempt = { _, _ ->
                    connectCalls += 1
                    BleOperationResult.Failure(
                        status = BleOperationResult.STATUS_TIMEOUT,
                        message = "Disconnected"
                    )
                }
                harness.bleTransport.simulateDisconnect()
                runCurrent()
                assertEquals(1, connectCalls)

                // Micro-session (< 10s, 0 samples) prompts instead of cancelling reconnection.
                val microOutcome = harness.repository.stopSession()
                assertTrue(microOutcome is StopSessionOutcome.MicroSessionDiscardPromptRequired)
                harness.advanceBothClocks(2_000L)
                runCurrent()
                assertEquals(2, connectCalls)

                assertTrue(harness.repository.discardSession())
                assertEquals(
                    BleConnectionState.Disconnected,
                    harness.repository.connectionState.value
                )
                harness.advanceBothClocks(120_000L)
                runCurrent()
                assertEquals(2, connectCalls)
                assertTrue(harness.alertNotifier.alerts.isEmpty())

                // 3. close() during active reconnection cancels retry and grace timers cleanly.
                harness.bleTransport.onConnectAttempt = null
                harness.repository.resetToIdle()
                harness.connectInitializeAndPrimeTelemetry(bpm = 132)
                assertEquals(StartSessionResult.Started("session-close"), harness.repository.startSession())

                connectCalls = 0
                harness.bleTransport.onConnectAttempt = { _, _ ->
                    connectCalls += 1
                    BleOperationResult.Failure(
                        status = BleOperationResult.STATUS_TIMEOUT,
                        message = "Disconnected"
                    )
                }
                harness.bleTransport.simulateDisconnect()
                runCurrent()
                assertEquals(1, connectCalls)

                harness.close()
                advanceTimeBy(120_000L)
                runCurrent()
                assertEquals(1, connectCalls)
                assertTrue(harness.alertNotifier.alerts.isEmpty())
            } finally {
                harness.close()
            }
        }

    @Test
    fun gattFailureRetries_andExternalReconnect() =
        runTest {
            val harness = createHarness(
                scope = this,
                sessionIds = listOf("session-gatt-retry", "session-external-reconnect")
            )
            try {
                // Case A: Connect succeeds on attempt 1, but GATT service discovery fails -> disconnects & retries attempt 2 at 2s.
                harness.connectInitializeAndPrimeTelemetry(bpm = 124)
                assertEquals(
                    StartSessionResult.Started("session-gatt-retry"),
                    harness.repository.startSession()
                )

                harness.bleTransport.discoverServicesFailure = BleOperationResult.Failure(
                    status = BleOperationResult.STATUS_OPERATION_FAILED,
                    message = "GATT 133 during discovery"
                )
                harness.bleTransport.clearRecordedOperations()
                harness.bleTransport.simulateDisconnect()
                runCurrent()

                // Attempt 1 connected, failed GATT init, disconnected, and is now waiting 2s for attempt 2.
                assertEquals(
                    BleConnectionState.Reconnecting(ACTIVE_HRM_MAC, attempt = 2),
                    harness.repository.connectionState.value
                )
                assertTrue(
                    harness.bleTransport.recordedOperations.any {
                        it == BleRecordedOperation.Disconnect(ACTIVE_HRM_MAC)
                    }
                )

                // Clear GATT failure before attempt 2 runs at +2s -> recovers seamlessly inside grace window.
                harness.bleTransport.discoverServicesFailure = null
                harness.advanceBothClocks(2_000L)
                runCurrent()
                assertEquals(
                    BleConnectionState.Connected(ACTIVE_HRM_MAC),
                    harness.repository.connectionState.value
                )
                assertTrue(harness.repository.uiState.value is SessionUiState.Recording)
                assertTrue(harness.alertNotifier.alerts.isEmpty())

                assertTrue(harness.repository.discardSession())
                harness.repository.resetToIdle()

                // Case B: External Connected(mac) between backoff attempts after 10s auto-pause cancels loop and auto-resumes.
                harness.connectInitializeAndPrimeTelemetry(bpm = 124)
                assertEquals(
                    StartSessionResult.Started("session-external-reconnect"),
                    harness.repository.startSession()
                )
                harness.bleTransport.onConnectAttempt = { _, _ ->
                    BleOperationResult.Failure(
                        status = BleOperationResult.STATUS_TIMEOUT,
                        message = "Retry failed"
                    )
                }
                harness.bleTransport.simulateDisconnect()
                runCurrent()

                // Advance to 10s so session transitions to Paused(BLE_CONNECTION_LOST); next retry isn't until 17s.
                harness.advanceBothClocks(10_000L)
                runCurrent()
                assertEquals(
                    PauseReason.BLE_CONNECTION_LOST,
                    (harness.repository.uiState.value as SessionUiState.Paused).reason
                )

                // External reconnect succeeds at +12s while reconnectionJob is waiting for 17s.
                harness.advanceBothClocks(2_000L)
                harness.bleTransport.onConnectAttempt = null
                harness.bleTransport.connect(ACTIVE_HRM_MAC)
                runCurrent()

                val resumed = harness.repository.uiState.value as SessionUiState.Recording
                assertEquals(BleConnectionState.Connected(ACTIVE_HRM_MAC), resumed.connectionState)
                assertEquals(BleConnectionState.Connected(ACTIVE_HRM_MAC), harness.repository.connectionState.value)
                assertEquals(10_000L, resumed.effectiveDurationMs)
                assertEquals(2_000L, resumed.pausedDurationMs)
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
        policy: BleReconnectionPolicy = BleReconnectionPolicy.Default
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
            flushIntervalMs = HeartRateSampleBuffer.DEFAULT_FLUSH_INTERVAL_MS
        )
        val alertNotifier = RecordingDisconnectAlertNotifier()
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
            reconnectionPolicy = policy,
            disconnectAlertNotifier = alertNotifier
        )
        return TestHarness(
            testScope = scope,
            bleTransport = bleTransport,
            gattClient = gattClient,
            sampleDao = sampleDao,
            sessionDao = sessionDao,
            clock = clock,
            sampleBuffer = sampleBuffer,
            alertNotifier = alertNotifier,
            repository = repository
        )
    }

    private data class AlertRecord(
        val hrmDisplayName: String,
        val sessionId: String
    )

    private class RecordingDisconnectAlertNotifier : BleDisconnectAlertNotifier {
        val alerts = mutableListOf<AlertRecord>()

        override fun notifyBleConnectionLost(hrmDisplayName: String, sessionId: String) {
            alerts.add(AlertRecord(hrmDisplayName = hrmDisplayName, sessionId = sessionId))
        }
    }

    private class TestHarness(
        private val testScope: TestScope,
        val bleTransport: FakeBleTransport,
        val gattClient: HrmGattClient,
        val sampleDao: FakeHeartRateSampleDao,
        val sessionDao: FakeSessionDao,
        val clock: FakeSessionClock,
        val sampleBuffer: HeartRateSampleBuffer,
        val alertNotifier: RecordingDisconnectAlertNotifier,
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

        suspend fun connectInitializeAndPrimeTelemetry(bpm: Int = 120) {
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
