package com.example.heartzonemonitor.data.session

import com.example.heartzonemonitor.data.ble.BleConnectionState
import com.example.heartzonemonitor.data.ble.BleOperationResult
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
import com.example.heartzonemonitor.domain.model.HeartZone
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import com.example.heartzonemonitor.domain.timing.FakeSessionClock
import com.example.heartzonemonitor.domain.timing.MonotonicDurationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRepositoryTest {

    @Test
    fun startSession_gatingRejectsWhenDisconnectedMissingProfileNoActiveHrmWrongDeviceOrInvalidTelemetry() =
        runTest {
            val harness = createHarness(
                scope = this,
                initialProfile = null,
                initialHrms = emptyList()
            )
            try {
                // 1. Missing profile and no Active HRM
                val idleInitial = harness.repository.uiState.first() as SessionUiState.Idle
                assertFalse(idleInitial.canStartSession)
                assertFalse(idleInitial.isTelemetryValid)
                assertEquals(
                    StartSessionResult.Rejected(StartSessionRejectionReason.USER_PROFILE_MISSING),
                    harness.repository.startSession()
                )
                assertTrue(harness.sessionDao.getAllSessions().isEmpty())

                // 2. Profile exists, but no Active HRM
                harness.profileDao.upsertProfile(defaultProfile())
                runCurrent()
                assertFalse((harness.repository.uiState.value as SessionUiState.Idle).canStartSession)
                assertEquals(
                    StartSessionResult.Rejected(StartSessionRejectionReason.NO_ACTIVE_HRM),
                    harness.repository.startSession()
                )
                assertTrue(harness.sessionDao.getAllSessions().isEmpty())

                // 3. Active HRM exists, but disconnected
                harness.hrmDao.upsertHrm(activeHrm())
                runCurrent()
                assertFalse((harness.repository.uiState.value as SessionUiState.Idle).canStartSession)
                assertEquals(
                    StartSessionResult.Rejected(StartSessionRejectionReason.HRM_NOT_CONNECTED),
                    harness.repository.startSession()
                )
                assertTrue(harness.sessionDao.getAllSessions().isEmpty())

                // 4. Wrong device connected and streaming valid 0x2A37 telemetry
                harness.bleTransport.connect(OTHER_HRM_MAC)
                runCurrent()
                harness.emitHrPacket(macAddress = OTHER_HRM_MAC, bpm = 128, contactDetected = true)
                runCurrent()
                val idleWrongDevice = harness.repository.uiState.value as SessionUiState.Idle
                assertFalse(idleWrongDevice.isTelemetryValid)
                assertFalse(idleWrongDevice.canStartSession)
                assertNull(idleWrongDevice.currentBpm)
                assertEquals(
                    StartSessionResult.Rejected(StartSessionRejectionReason.HRM_NOT_CONNECTED),
                    harness.repository.startSession()
                )
                assertTrue(harness.sessionDao.getAllSessions().isEmpty())

                // 5. Active HRM connected, but NO telemetry received yet
                harness.bleTransport.connect(ACTIVE_HRM_MAC)
                runCurrent()
                val idleConnectedNoTelemetry =
                    harness.repository.uiState.value as SessionUiState.Idle
                assertEquals(
                    BleConnectionState.Connected(ACTIVE_HRM_MAC),
                    idleConnectedNoTelemetry.connectionState
                )
                assertFalse(idleConnectedNoTelemetry.isTelemetryValid)
                assertFalse(idleConnectedNoTelemetry.canStartSession)
                assertEquals(
                    StartSessionResult.Rejected(StartSessionRejectionReason.TELEMETRY_UNAVAILABLE),
                    harness.repository.startSession()
                )
                assertTrue(harness.sessionDao.getAllSessions().isEmpty())

                // 6. Active HRM connected, but invalid telemetry: no sensor contact
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 118, contactDetected = false)
                runCurrent()
                val idleNoContact = harness.repository.uiState.value as SessionUiState.Idle
                assertEquals(false, idleNoContact.sensorContact)
                assertFalse(idleNoContact.isTelemetryValid)
                assertFalse(idleNoContact.canStartSession)
                assertEquals(
                    StartSessionResult.Rejected(StartSessionRejectionReason.TELEMETRY_UNAVAILABLE),
                    harness.repository.startSession()
                )

                // 7. Active HRM connected, invalid telemetry: 0 BPM
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 0, contactDetected = true)
                runCurrent()
                val idleZeroBpm = harness.repository.uiState.value as SessionUiState.Idle
                assertNull(idleZeroBpm.currentBpm)
                assertFalse(idleZeroBpm.isTelemetryValid)
                assertFalse(idleZeroBpm.canStartSession)
                assertEquals(
                    StartSessionResult.Rejected(StartSessionRejectionReason.TELEMETRY_UNAVAILABLE),
                    harness.repository.startSession()
                )

                // 8. Active HRM connected, invalid telemetry: out-of-range 16-bit BPM (> 255)
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 300, contactDetected = true)
                runCurrent()
                assertFalse((harness.repository.uiState.value as SessionUiState.Idle).isTelemetryValid)
                assertEquals(
                    StartSessionResult.Rejected(StartSessionRejectionReason.TELEMETRY_UNAVAILABLE),
                    harness.repository.startSession()
                )

                // 9. Malformed/empty 0x2A37 payload is safely ignored
                harness.bleTransport.emitNotification(
                    macAddress = ACTIVE_HRM_MAC,
                    value = byteArrayOf(),
                    timestampEpochMs = harness.clock.currentEpochMs()
                )
                runCurrent()
                assertFalse((harness.repository.uiState.value as SessionUiState.Idle).canStartSession)
                assertTrue(harness.sessionDao.getAllSessions().isEmpty())
            } finally {
                harness.close()
            }
        }

    @Test
    fun disconnectingOrSwitchingActiveHrmInIdle_immediatelyClearsTelemetryValidityAndRequiresFreshTelemetry() =
        runTest {
            val harness = createHarness(
                scope = this,
                initialProfile = defaultProfile(),
                initialHrms = listOf(activeHrm(), secondaryHrm())
            )
            try {
                // Connect and stream valid telemetry from Active HRM
                harness.bleTransport.connect(ACTIVE_HRM_MAC)
                runCurrent()
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 122, contactDetected = true)
                runCurrent()

                val readyIdle = harness.repository.uiState.first {
                    it is SessionUiState.Idle && it.canStartSession
                } as SessionUiState.Idle
                assertTrue(readyIdle.isTelemetryValid)
                assertEquals(122, readyIdle.currentBpm)
                assertEquals(HeartZone.ZONE_2, readyIdle.currentZone)

                // Disconnect clears telemetry validity immediately
                harness.bleTransport.simulateDisconnect()
                runCurrent()
                val disconnectedIdle = harness.repository.uiState.value as SessionUiState.Idle
                assertFalse(disconnectedIdle.isTelemetryValid)
                assertFalse(disconnectedIdle.canStartSession)
                assertNull(disconnectedIdle.currentBpm)
                assertNull(disconnectedIdle.currentZone)
                assertNull(disconnectedIdle.sensorContact)

                // Reconnect alone must NOT restore canStartSession until fresh valid telemetry arrives
                harness.bleTransport.connect(ACTIVE_HRM_MAC)
                runCurrent()
                val reconnectedNoTelemetry = harness.repository.uiState.value as SessionUiState.Idle
                assertFalse(reconnectedNoTelemetry.isTelemetryValid)
                assertFalse(reconnectedNoTelemetry.canStartSession)
                assertEquals(
                    StartSessionResult.Rejected(StartSessionRejectionReason.TELEMETRY_UNAVAILABLE),
                    harness.repository.startSession()
                )

                // Fresh valid telemetry (including sensors without contact detection feature) restores readiness
                harness.emitHrPacket(
                    macAddress = ACTIVE_HRM_MAC,
                    bpm = 132,
                    contactSupported = false,
                    contactDetected = false
                )
                runCurrent()
                val restoredIdle = harness.repository.uiState.value as SessionUiState.Idle
                assertTrue(restoredIdle.isTelemetryValid)
                assertTrue(restoredIdle.canStartSession)
                assertEquals(true, restoredIdle.sensorContact)

                // Switching Active HRM clears telemetry validity immediately
                harness.hrmDao.promoteToActiveHrm(OTHER_HRM_MAC)
                runCurrent()
                val switchedIdle = harness.repository.uiState.value as SessionUiState.Idle
                assertEquals(OTHER_HRM_MAC, switchedIdle.activeHrm?.macAddress)
                assertFalse(switchedIdle.isTelemetryValid)
                assertFalse(switchedIdle.canStartSession)
                assertNull(switchedIdle.currentBpm)
                assertEquals(
                    StartSessionResult.Rejected(StartSessionRejectionReason.HRM_NOT_CONNECTED),
                    harness.repository.startSession()
                )
            } finally {
                harness.close()
            }
        }

    @Test
    fun fullSessionLifecycle_tagsZonesIncludingBelowZ1ClampedTo1_flushesAndComputesExactSummaryMetrics() =
        runTest {
            val harness = createHarness(
                scope = this,
                initialProfile = defaultProfile(), // age 30 in 2026 -> HRmax 187 (%HRmax)
                initialHrms = listOf(activeHrm()),
                sessionIds = listOf("session-full-1")
            )
            val observedStates = Channel<SessionUiState>(Channel.UNLIMITED)
            val stateCollector = backgroundScope.launch {
                harness.repository.uiState.collect { observedStates.trySend(it) }
            }
            try {
                // Stub battery level read (85%) and initialize connected HRM via HrmGattClient
                harness.bleTransport.stubCharacteristicRead(
                    characteristicUuid = BleUuids.BATTERY_LEVEL_CHAR_UUID,
                    result = BleOperationResult.Success(byteArrayOf(85)),
                    serviceUuid = BleUuids.BATTERY_SERVICE_UUID,
                    macAddress = ACTIVE_HRM_MAC
                )
                harness.bleTransport.connect(ACTIVE_HRM_MAC)
                val initResult = harness.gattClient.initializeConnectedHrm(ACTIVE_HRM_MAC)
                assertTrue(initResult is BleOperationResult.Success)
                runCurrent()

                // Emit pre-start valid telemetry
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 100, contactDetected = true)
                runCurrent()

                val readyIdle = harness.repository.uiState.first {
                    it is SessionUiState.Idle && it.canStartSession
                } as SessionUiState.Idle
                assertEquals(85, readyIdle.batteryLevel)
                assertTrue(readyIdle.canStartSession)
                assertFalse(harness.repository.isSessionActive)

                // Start session
                val startEpochMs = harness.clock.currentEpochMs()
                val startResult = harness.repository.startSession()
                assertEquals(StartSessionResult.Started("session-full-1"), startResult)
                assertTrue(harness.repository.isSessionActive)

                val recordingInitial = harness.repository.uiState.value as SessionUiState.Recording
                assertEquals("session-full-1", recordingInitial.sessionId)
                assertEquals(startEpochMs, recordingInitial.startEpochMs)
                assertEquals(0, recordingInitial.sampleCount)
                assertEquals(85, recordingInitial.batteryLevel)
                assertEquals(187, recordingInitial.zoneCalculation.hrMax)

                // Verify initial DB session row has frozen profile snapshot
                val insertedSession = harness.sessionDao.getSessionById("session-full-1")
                assertNotNull(insertedSession)
                assertEquals(SessionStatus.RECORDING, insertedSession!!.status)
                assertEquals(30, insertedSession.userAge)
                assertEquals(187, insertedSession.calculatedHrmax)
                assertNull(insertedSession.restingHr)
                assertEquals(ZoneCalculationMode.PERCENT_HRMAX, insertedSession.calculationMode)
                assertEquals(ACTIVE_HRM_MAC, insertedSession.hrmMacAddress)

                // Stream 12 1-Hz samples covering below-Z1 (clamped to Z1) and Z1..Z5:
                // Age 30, HRmax 187 (%HRmax): Z1=94..112, Z2=112..131, Z3=131..150, Z4=150..168, Z5=168..187
                val bpms = listOf(
                    65,  // below Z1 -> clamped to zoneIndex 1
                    98,  // Z1
                    115, // Z2
                    125, // Z2
                    135, // Z3
                    145, // Z3
                    155, // Z4
                    162, // Z4
                    172, // Z5
                    180, // Z5
                    140, // Z3
                    118  // Z2
                )
                val expectedZones = listOf(1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 3, 2)

                bpms.forEach { bpm ->
                    harness.clock.advanceBothBy(1_000L)
                    advanceTimeBy(1_000L)
                    harness.emitHrPacket(
                        macAddress = ACTIVE_HRM_MAC,
                        bpm = bpm,
                        contactDetected = true,
                        timestampEpochMs = harness.clock.currentEpochMs()
                    )
                    runCurrent()
                }

                // Non-0x2A37 notification and invalid 0-BPM / no-contact frames must not pollute samples
                harness.bleTransport.emitNotification(
                    macAddress = ACTIVE_HRM_MAC,
                    value = byteArrayOf(80),
                    serviceUuid = BleUuids.BATTERY_SERVICE_UUID,
                    characteristicUuid = BleUuids.BATTERY_LEVEL_CHAR_UUID,
                    timestampEpochMs = harness.clock.currentEpochMs()
                )
                harness.emitHrPacket(
                    macAddress = ACTIVE_HRM_MAC,
                    bpm = 0,
                    contactDetected = false,
                    timestampEpochMs = harness.clock.currentEpochMs()
                )
                runCurrent()

                val recordingAfterSamples =
                    harness.repository.uiState.value as SessionUiState.Recording
                assertEquals(12, recordingAfterSamples.sampleCount)
                assertEquals(12_000L, recordingAfterSamples.effectiveDurationMs)
                assertEquals(0L, recordingAfterSamples.pausedDurationMs)
                assertEquals(12_000L, recordingAfterSamples.elapsedDurationMs)
                assertEquals(false, recordingAfterSamples.sensorContact)

                // Periodic 5s flush should have already persisted earlier batches
                assertTrue(harness.sampleDao.batchCallCount >= 2)

                // Stop & Save
                val expectedEndEpochMs = harness.clock.currentEpochMs()
                val stopOutcome = harness.repository.stopSession()
                assertTrue(stopOutcome is StopSessionOutcome.Completed)
                val completed = (stopOutcome as StopSessionOutcome.Completed).session

                assertFalse(harness.repository.isSessionActive)
                assertEquals(SessionUiState.Completed(completed), harness.repository.uiState.value)

                // Verify persisted Heart Rate Samples & exact zone tagging (including below-Z1 clamped to 1)
                val persistedSamples = harness.sampleDao.getSamplesForSession("session-full-1")
                assertEquals(12, persistedSamples.size)
                assertEquals(bpms, persistedSamples.map { it.bpm })
                assertEquals(expectedZones, persistedSamples.map { it.zoneIndex })
                assertEquals(0, harness.sampleBuffer.pendingCount())

                // Verify finalized SessionEntity summary metrics and monotonic durations
                val expectedAvg = bpms.average().roundToInt()
                assertEquals(SessionStatus.COMPLETED, completed.status)
                assertEquals(startEpochMs, completed.startEpochMs)
                assertEquals(expectedEndEpochMs, completed.endEpochMs)
                assertEquals(12_000L, completed.effectiveDurationMs)
                assertEquals(0L, completed.pausedDurationMs)
                assertEquals(12_000L, completed.elapsedDurationMs)
                assertEquals(expectedAvg, completed.avgBpm)
                assertEquals(180, completed.maxBpm)
                assertEquals(65, completed.minBpm)
                assertEquals(2_000L, completed.zone1DurationMs)
                assertEquals(3_000L, completed.zone2DurationMs)
                assertEquals(3_000L, completed.zone3DurationMs)
                assertEquals(2_000L, completed.zone4DurationMs)
                assertEquals(2_000L, completed.zone5DurationMs)

                val persistedSession = harness.sessionDao.getSessionById("session-full-1")
                assertEquals(completed, persistedSession)

                // Flush collector coroutine and verify key lifecycle transitions occurred in order
                runCurrent()
                val collected = mutableListOf<SessionUiState>()
                while (true) {
                    val next = observedStates.tryReceive().getOrNull() ?: break
                    collected.add(next)
                }
                assertTrue(collected.any { it is SessionUiState.Idle })
                assertTrue(collected.any { it is SessionUiState.Recording })
                assertEquals(SessionUiState.Completed(completed), collected.last())

                // Reset back to Idle
                harness.repository.resetToIdle()
                runCurrent()
                assertTrue(observedStates.receive() is SessionUiState.Idle)
                assertTrue(harness.repository.uiState.value is SessionUiState.Idle)
            } finally {
                stateCollector.cancel()
                observedStates.close()
                harness.close()
            }
        }

    @Test
    fun pauseAndResume_supportsAllThreePauseReasons_flushesImmediately_ignoresPausedSamples_andResistsClockDrift() =
        runTest {
            val harness = createHarness(
                scope = this,
                initialProfile = defaultProfile(),
                initialHrms = listOf(activeHrm()),
                sessionIds = listOf("session-pause-1")
            )
            try {
                harness.connectAndPrimeTelemetry(bpm = 120)
                assertEquals(
                    StartSessionResult.Started("session-pause-1"),
                    harness.repository.startSession()
                )

                // Record 3 samples (< 5s flush window -> still in memory buffer, 0 written to DAO)
                repeat(3) { idx ->
                    harness.clock.advanceBothBy(1_000L)
                    advanceTimeBy(1_000L)
                    harness.emitHrPacket(
                        macAddress = ACTIVE_HRM_MAC,
                        bpm = 120 + idx,
                        contactDetected = true,
                        timestampEpochMs = harness.clock.currentEpochMs()
                    )
                    runCurrent()
                }
                assertEquals(3, harness.sampleBuffer.pendingCount())
                assertEquals(0, harness.sampleDao.getSampleCountForSession("session-pause-1"))

                // 1. Pause with USER_MANUAL: immediately flushes samples & persists PAUSED status
                assertTrue(harness.repository.pauseSession(PauseReason.USER_MANUAL))
                assertEquals(0, harness.sampleBuffer.pendingCount())
                assertEquals(3, harness.sampleDao.getSampleCountForSession("session-pause-1"))
                assertTrue(harness.repository.isSessionActive)

                val pausedManual = harness.repository.uiState.value as SessionUiState.Paused
                assertEquals(PauseReason.USER_MANUAL, pausedManual.reason)
                assertEquals(3_000L, pausedManual.effectiveDurationMs)
                assertEquals(0L, pausedManual.pausedDurationMs)
                assertEquals(3_000L, pausedManual.elapsedDurationMs)
                assertEquals(
                    SessionStatus.PAUSED,
                    harness.sessionDao.getSessionById("session-pause-1")!!.status
                )

                // Simulate backward wall-clock NTP jump (-300s) and incoming HR packets while paused
                harness.clock.advanceWallClockBy(-300_000L)
                repeat(4) {
                    harness.clock.advanceRealtimeBy(1_000L)
                    harness.clock.advanceWallClockBy(1_000L)
                    advanceTimeBy(1_000L)
                    harness.emitHrPacket(
                        macAddress = ACTIVE_HRM_MAC,
                        bpm = 175,
                        contactDetected = true,
                        timestampEpochMs = 1_700_000_020_000L + it * 1_000L
                    )
                    runCurrent()
                }
                // Samples while paused must NOT be enqueued or persisted
                assertEquals(0, harness.sampleBuffer.pendingCount())
                assertEquals(3, harness.sampleDao.getSampleCountForSession("session-pause-1"))
                assertEquals(3, (harness.repository.uiState.value as SessionUiState.Paused).sampleCount)

                // Resume from USER_MANUAL
                assertTrue(harness.repository.resumeSession())
                val resumed1 = harness.repository.uiState.value as SessionUiState.Recording
                assertEquals(3_000L, resumed1.effectiveDurationMs)
                assertEquals(4_000L, resumed1.pausedDurationMs)
                assertEquals(7_000L, resumed1.elapsedDurationMs)
                assertEquals(
                    SessionStatus.RECORDING,
                    harness.sessionDao.getSessionById("session-pause-1")!!.status
                )

                // Record 4 more seconds (effective = 7s)
                repeat(4) { idx ->
                    harness.clock.advanceRealtimeBy(1_000L)
                    advanceTimeBy(1_000L)
                    harness.emitHrPacket(
                        macAddress = ACTIVE_HRM_MAC,
                        bpm = 140 + idx,
                        contactDetected = true,
                        timestampEpochMs = 1_700_000_050_000L + idx * 1_000L
                    )
                    runCurrent()
                }

                // 2. Pause with BLE_CONNECTION_LOST
                assertTrue(harness.repository.pauseSession(PauseReason.BLE_CONNECTION_LOST))
                val pausedBle = harness.repository.uiState.value as SessionUiState.Paused
                assertEquals(PauseReason.BLE_CONNECTION_LOST, pausedBle.reason)
                assertEquals(7, harness.sampleDao.getSampleCountForSession("session-pause-1"))

                harness.clock.advanceRealtimeBy(2_000L)
                advanceTimeBy(2_000L)
                assertTrue(harness.repository.resumeSession())

                // 3. Pause with SENSOR_CONTACT_LOST after 4 more active seconds (effective = 11s)
                repeat(4) { idx ->
                    harness.clock.advanceRealtimeBy(1_000L)
                    advanceTimeBy(1_000L)
                    harness.emitHrPacket(
                        macAddress = ACTIVE_HRM_MAC,
                        bpm = 155 + idx,
                        contactDetected = true,
                        timestampEpochMs = 1_700_000_060_000L + idx * 1_000L
                    )
                    runCurrent()
                }
                assertTrue(harness.repository.pauseSession(PauseReason.SENSOR_CONTACT_LOST))
                val pausedContact = harness.repository.uiState.value as SessionUiState.Paused
                assertEquals(PauseReason.SENSOR_CONTACT_LOST, pausedContact.reason)
                assertEquals(11, harness.sampleDao.getSampleCountForSession("session-pause-1"))

                harness.clock.advanceRealtimeBy(3_000L)
                advanceTimeBy(3_000L)

                // Stop directly from Paused state: closes final 3s pause interval
                val stopOutcome = harness.repository.stopSession()
                assertTrue(stopOutcome is StopSessionOutcome.Completed)
                val completed = (stopOutcome as StopSessionOutcome.Completed).session

                // Monotonic durations: 3s + 4s + 4s = 11s effective; 4s + 2s + 3s = 9s paused; 20s elapsed
                assertEquals(11_000L, completed.effectiveDurationMs)
                assertEquals(9_000L, completed.pausedDurationMs)
                assertEquals(20_000L, completed.elapsedDurationMs)
                assertEquals(SessionStatus.COMPLETED, completed.status)

                // Zone durations: 3 samples in Z2 (3s), 4 samples in Z3 (4s), 4 samples in Z4 (4s)
                // Pause gaps (> 3s) are NOT credited to zone durations (nominal 1s per boundary sample)
                assertEquals(0L, completed.zone1DurationMs)
                assertEquals(3_000L, completed.zone2DurationMs)
                assertEquals(4_000L, completed.zone3DurationMs)
                assertEquals(4_000L, completed.zone4DurationMs)
                assertEquals(0L, completed.zone5DurationMs)
            } finally {
                harness.close()
            }
        }

    @Test
    fun microSessionGuard_promptsUnderTenSecondsOrZeroSamples_supportsDismissDiscardAndForcedSave() =
        runTest {
            val harness = createHarness(
                scope = this,
                initialProfile = defaultProfile(),
                initialHrms = listOf(activeHrm()),
                sessionIds = listOf("micro-1", "micro-zero-samples", "micro-force-save", "exact-10s")
            )
            try {
                harness.connectAndPrimeTelemetry(bpm = 130)

                // --- Case A: 6s Effective Duration with 6 samples (< 10s threshold), plus 10s paused (16s Elapsed) ---
                assertEquals(StartSessionResult.Started("micro-1"), harness.repository.startSession())
                repeat(6) { idx ->
                    harness.clock.advanceBothBy(1_000L)
                    advanceTimeBy(1_000L)
                    harness.emitHrPacket(
                        macAddress = ACTIVE_HRM_MAC,
                        bpm = 130 + idx,
                        contactDetected = true,
                        timestampEpochMs = harness.clock.currentEpochMs()
                    )
                    runCurrent()
                }
                // Pause for 10s so Elapsed Duration = 16s >= 10s, while Effective Duration = 6s < 10s
                assertTrue(harness.repository.pauseSession(PauseReason.USER_MANUAL))
                harness.clock.advanceBothBy(10_000L)
                advanceTimeBy(10_000L)

                val microOutcome = harness.repository.stopSession()
                assertEquals(
                    StopSessionOutcome.MicroSessionDiscardPromptRequired(
                        sessionId = "micro-1",
                        effectiveDurationMs = 6_000L,
                        sampleCount = 6
                    ),
                    microOutcome
                )
                val promptedPaused = harness.repository.uiState.value as SessionUiState.Paused
                assertTrue(promptedPaused.pendingMicroSessionDiscardPrompt)
                assertEquals(
                    SessionStatus.PAUSED,
                    harness.sessionDao.getSessionById("micro-1")!!.status
                )

                // Dismiss keeps active session intact
                harness.repository.dismissMicroSessionPrompt()
                val dismissedPaused = harness.repository.uiState.value as SessionUiState.Paused
                assertFalse(dismissedPaused.pendingMicroSessionDiscardPrompt)
                assertTrue(harness.repository.isSessionActive)

                // Resume and add 1 buffered sample, then discard: buffer cleared & DB session + samples deleted
                assertTrue(harness.repository.resumeSession())
                harness.clock.advanceBothBy(1_000L)
                harness.emitHrPacket(
                    macAddress = ACTIVE_HRM_MAC,
                    bpm = 140,
                    contactDetected = true,
                    timestampEpochMs = harness.clock.currentEpochMs()
                )
                runCurrent()
                assertEquals(1, harness.sampleBuffer.pendingCount())
                val batchCallsBeforeDiscard = harness.sampleDao.batchCallCount

                assertTrue(harness.repository.discardSession())
                assertEquals(0, harness.sampleBuffer.pendingCount())
                assertEquals(batchCallsBeforeDiscard, harness.sampleDao.batchCallCount)
                assertNull(harness.sessionDao.getSessionById("micro-1"))
                assertEquals(0, harness.sampleDao.getSampleCountForSession("micro-1"))
                assertEquals(SessionUiState.Discarded("micro-1"), harness.repository.uiState.value)
                assertFalse(harness.repository.isSessionActive)

                // --- Case B: >= 10s Effective Duration with 0 samples still triggers guard ---
                harness.repository.resetToIdle()
                assertEquals(
                    StartSessionResult.Started("micro-zero-samples"),
                    harness.repository.startSession()
                )
                harness.clock.advanceBothBy(15_000L)
                advanceTimeBy(15_000L)
                val zeroSamplesOutcome = harness.repository.stopSession()
                assertEquals(
                    StopSessionOutcome.MicroSessionDiscardPromptRequired(
                        sessionId = "micro-zero-samples",
                        effectiveDurationMs = 15_000L,
                        sampleCount = 0
                    ),
                    zeroSamplesOutcome
                )
                assertTrue(
                    (harness.repository.uiState.value as SessionUiState.Recording)
                        .pendingMicroSessionDiscardPrompt
                )
                assertTrue(harness.repository.discardSession())

                // --- Case C: confirmSaveMicroSession() forces save of short session ---
                harness.repository.resetToIdle()
                assertEquals(
                    StartSessionResult.Started("micro-force-save"),
                    harness.repository.startSession()
                )
                repeat(3) { idx ->
                    harness.clock.advanceBothBy(1_000L)
                    advanceTimeBy(1_000L)
                    harness.emitHrPacket(
                        macAddress = ACTIVE_HRM_MAC,
                        bpm = 140 + idx * 5,
                        contactDetected = true,
                        timestampEpochMs = harness.clock.currentEpochMs()
                    )
                    runCurrent()
                }
                assertTrue(
                    harness.repository.stopSession() is StopSessionOutcome.MicroSessionDiscardPromptRequired
                )
                val forcedOutcome = harness.repository.confirmSaveMicroSession()
                assertTrue(forcedOutcome is StopSessionOutcome.Completed)
                val forcedSession = (forcedOutcome as StopSessionOutcome.Completed).session
                assertEquals(SessionStatus.COMPLETED, forcedSession.status)
                assertEquals(3_000L, forcedSession.effectiveDurationMs)
                assertEquals(145, forcedSession.avgBpm)
                assertEquals(150, forcedSession.maxBpm)
                assertEquals(140, forcedSession.minBpm)

                // --- Case D: Exact 10,000ms Effective Duration with >= 1 sample completes directly ---
                harness.repository.resetToIdle()
                assertEquals(StartSessionResult.Started("exact-10s"), harness.repository.startSession())
                harness.clock.advanceBothBy(10_000L)
                advanceTimeBy(10_000L)
                harness.emitHrPacket(
                    macAddress = ACTIVE_HRM_MAC,
                    bpm = 150,
                    contactDetected = true,
                    timestampEpochMs = harness.clock.currentEpochMs()
                )
                runCurrent()
                val exactBoundaryOutcome = harness.repository.stopSession()
                assertTrue(exactBoundaryOutcome is StopSessionOutcome.Completed)
                assertEquals(
                    10_000L,
                    (exactBoundaryOutcome as StopSessionOutcome.Completed).session.effectiveDurationMs
                )
            } finally {
                harness.close()
            }
        }

    @Test
    fun invalidTransitions_areSafelyRejectedWithoutCorruptingState() = runTest {
        val harness = createHarness(
            scope = this,
            initialProfile = defaultProfile(),
            initialHrms = listOf(activeHrm()),
            sessionIds = listOf("session-invalid-1")
        )
        try {
            harness.connectAndPrimeTelemetry(bpm = 125)

            // From Idle: pause, resume, stop, discard are rejected
            assertFalse(harness.repository.pauseSession())
            assertFalse(harness.repository.resumeSession())
            assertEquals(StopSessionOutcome.Ignored, harness.repository.stopSession())
            assertFalse(harness.repository.discardSession())
            assertTrue(harness.repository.uiState.value is SessionUiState.Idle)

            // Start session -> Recording
            assertEquals(
                StartSessionResult.Started("session-invalid-1"),
                harness.repository.startSession()
            )
            // While Recording: start again, resume, resetToIdle are rejected/ignored
            assertEquals(
                StartSessionResult.Rejected(StartSessionRejectionReason.SESSION_ALREADY_ACTIVE),
                harness.repository.startSession()
            )
            assertFalse(harness.repository.resumeSession())
            harness.repository.resetToIdle()
            assertTrue(harness.repository.uiState.value is SessionUiState.Recording)

            // Pause -> Paused
            assertTrue(harness.repository.pauseSession(PauseReason.USER_MANUAL))
            // While Paused: start again, pause again, resetToIdle are rejected/ignored
            assertEquals(
                StartSessionResult.Rejected(StartSessionRejectionReason.SESSION_ALREADY_ACTIVE),
                harness.repository.startSession()
            )
            assertFalse(harness.repository.pauseSession(PauseReason.BLE_CONNECTION_LOST))
            harness.repository.resetToIdle()
            assertTrue(harness.repository.uiState.value is SessionUiState.Paused)

            // Complete -> terminal state rejects pause, resume, stop, discard
            val completed = harness.repository.confirmSaveMicroSession()
            assertTrue(completed is StopSessionOutcome.Completed)
            assertFalse(harness.repository.pauseSession())
            assertFalse(harness.repository.resumeSession())
            assertEquals(StopSessionOutcome.Ignored, harness.repository.stopSession())
            assertFalse(harness.repository.discardSession())
            assertTrue(harness.repository.uiState.value is SessionUiState.Completed)
        } finally {
            harness.close()
        }
    }

    @Test
    fun profileSnapshotInvariant_onAppBackgrounded_refreshDurations_timestampFallback_andSingletonInstance() =
        runTest {
            val harness = createHarness(
                scope = this,
                initialProfile = defaultProfile(), // age 30, %HRmax -> 120 BPM is Z2
                initialHrms = listOf(activeHrm()),
                sessionIds = listOf("session-snapshot-1", "session-snapshot-2")
            )
            try {
                harness.connectAndPrimeTelemetry(bpm = 120)
                assertEquals(
                    StartSessionResult.Started("session-snapshot-1"),
                    harness.repository.startSession()
                )

                // Mutate User Profile mid-session to age 40 + Karvonen (restingHr = 60 -> 120 BPM would be Z1)
                harness.profileDao.upsertProfile(
                    UserProfileEntity(
                        birthYear = 1986,
                        restingHr = 60,
                        customHrmax = null,
                        calculationMode = ZoneCalculationMode.KARVONEN,
                        updatedAtEpochMs = harness.clock.currentEpochMs()
                    )
                )
                runCurrent()

                // Emit notification with timestampEpochMs = 0L to verify clock.currentEpochMs() fallback
                harness.clock.advanceBothBy(2_000L)
                harness.emitHrPacket(
                    macAddress = ACTIVE_HRM_MAC,
                    bpm = 120,
                    contactDetected = true,
                    timestampEpochMs = 0L
                )
                runCurrent()

                // Verify onAppBackgrounded() flushes immediately before 5s periodic interval
                assertEquals(1, harness.sampleBuffer.pendingCount())
                harness.repository.onAppBackgrounded()
                assertEquals(0, harness.sampleBuffer.pendingCount())
                val flushedSample = harness.sampleDao.getSamplesForSession("session-snapshot-1").single()
                // Frozen profile (age 30, %HRmax) tags 120 BPM as Zone 2 (not Zone 1 from mutated profile)
                assertEquals(2, flushedSample.zoneIndex)
                assertEquals(harness.clock.currentEpochMs(), flushedSample.timestampEpochMs)

                // Verify refreshDurations() updates active state monotonic durations
                harness.clock.advanceBothBy(8_000L)
                harness.repository.refreshDurations()
                val refreshed = harness.repository.uiState.value as SessionUiState.Recording
                assertEquals(10_000L, refreshed.effectiveDurationMs)

                val stop1 = harness.repository.stopSession() as StopSessionOutcome.Completed
                assertEquals(30, stop1.session.userAge)
                assertEquals(ZoneCalculationMode.PERCENT_HRMAX, stop1.session.calculationMode)
                assertNull(stop1.session.restingHr)

                // Next session picks up updated profile (age 40, Karvonen, restingHr = 60)
                harness.repository.resetToIdle()
                assertEquals(
                    StartSessionResult.Started("session-snapshot-2"),
                    harness.repository.startSession()
                )
                harness.clock.advanceBothBy(10_000L)
                harness.emitHrPacket(
                    macAddress = ACTIVE_HRM_MAC,
                    bpm = 120,
                    contactDetected = true,
                    timestampEpochMs = harness.clock.currentEpochMs()
                )
                runCurrent()
                val stop2 = harness.repository.stopSession() as StopSessionOutcome.Completed
                assertEquals(40, stop2.session.userAge)
                assertEquals(60, stop2.session.restingHr)
                assertEquals(ZoneCalculationMode.KARVONEN, stop2.session.calculationMode)
                assertEquals(
                    1,
                    harness.sampleDao.getSamplesForSession("session-snapshot-2").single().zoneIndex
                )

                // Singleton getInstance returns same instance and clears cleanly
                SessionRepository.clearInstanceForTesting()
                assertNull(SessionRepository.getInstanceOrNull())
                val singleton1 = SessionRepository.getInstance(
                    bleTransport = harness.bleTransport,
                    rememberedHrmDao = harness.hrmDao,
                    userProfileDao = harness.profileDao,
                    sessionDao = harness.sessionDao,
                    sampleDao = harness.sampleDao,
                    scope = this,
                    clock = harness.clock
                )
                val singleton2 = SessionRepository.getInstance(
                    bleTransport = harness.bleTransport,
                    rememberedHrmDao = harness.hrmDao,
                    userProfileDao = harness.profileDao,
                    sessionDao = harness.sessionDao,
                    sampleDao = harness.sampleDao,
                    scope = this,
                    clock = harness.clock
                )
                assertSame(singleton1, singleton2)
                assertSame(singleton1, SessionRepository.getInstanceOrNull())
                SessionRepository.clearInstanceForTesting()
                assertNull(SessionRepository.getInstanceOrNull())
            } finally {
                SessionRepository.clearInstanceForTesting()
                harness.close()
            }
        }

    private fun createHarness(
        scope: CoroutineScope,
        initialProfile: UserProfileEntity? = defaultProfile(),
        initialHrms: List<RememberedHrmEntity> = listOf(activeHrm()),
        sessionIds: List<String> = listOf("session-1", "session-2", "session-3")
    ): TestHarness {
        val bleTransport = FakeBleTransport()
        val gattClient = HrmGattClient(bleTransport = bleTransport, scope = scope)
        val sampleDao = FakeHeartRateSampleDao()
        val sessionDao = FakeSessionDao(sampleDao = sampleDao)
        val hrmDao = FakeRememberedHrmDao(initialHrms = initialHrms)
        val profileDao = FakeUserProfileDao(initialProfile = initialProfile)
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
            sessionIdGenerator = { idQueue.removeFirstOrNull() ?: "generated-session" }
        )
        return TestHarness(
            bleTransport = bleTransport,
            gattClient = gattClient,
            sampleDao = sampleDao,
            sessionDao = sessionDao,
            hrmDao = hrmDao,
            profileDao = profileDao,
            clock = clock,
            sampleBuffer = sampleBuffer,
            repository = repository
        )
    }

    private class TestHarness(
        val bleTransport: FakeBleTransport,
        val gattClient: HrmGattClient,
        val sampleDao: FakeHeartRateSampleDao,
        val sessionDao: FakeSessionDao,
        val hrmDao: FakeRememberedHrmDao,
        val profileDao: FakeUserProfileDao,
        val clock: FakeSessionClock,
        val sampleBuffer: HeartRateSampleBuffer,
        val repository: SessionRepository
    ) {
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

        suspend fun connectAndPrimeTelemetry(bpm: Int = 120) {
            bleTransport.connect(ACTIVE_HRM_MAC)
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

    private fun secondaryHrm(): RememberedHrmEntity = RememberedHrmEntity(
        macAddress = OTHER_HRM_MAC,
        advertisedName = "Polar H10",
        customNickname = null,
        lastSeenTimestamp = 1_699_999_000_000L,
        batteryLevel = 75,
        isActive = false
    )

    companion object {
        private const val TEST_CURRENT_YEAR = 2026
        private const val ACTIVE_HRM_MAC = "AA:BB:CC:DD:EE:01"
        private const val OTHER_HRM_MAC = "AA:BB:CC:DD:EE:02"
    }
}
