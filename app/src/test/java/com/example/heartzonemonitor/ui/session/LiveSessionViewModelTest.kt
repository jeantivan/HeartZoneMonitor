package com.example.heartzonemonitor.ui.session

import com.example.heartzonemonitor.data.ble.FakeBleTransport
import com.example.heartzonemonitor.data.ble.HrmGattClient
import com.example.heartzonemonitor.data.local.FakeHeartRateSampleDao
import com.example.heartzonemonitor.data.local.FakeRememberedHrmDao
import com.example.heartzonemonitor.data.local.FakeSessionDao
import com.example.heartzonemonitor.data.local.FakeUserProfileDao
import com.example.heartzonemonitor.data.local.HeartRateSampleBuffer
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import com.example.heartzonemonitor.data.session.PauseReason
import com.example.heartzonemonitor.data.session.SessionRepository
import com.example.heartzonemonitor.domain.model.HeartZone
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import com.example.heartzonemonitor.domain.timing.FakeSessionClock
import com.example.heartzonemonitor.domain.timing.MonotonicDurationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSessionViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `maps Idle state with and without Active HRM telemetry while keeping immersion mode disabled`() =
        runTest(testDispatcher) {
            val harness = createHarness(scope = this, initialHrms = emptyList())
            try {
                val viewModel = LiveSessionViewModel(harness.repository)
                runCurrent()

                val initial = viewModel.uiState.value
                assertNull(initial.activeHrm)
                assertNull(initial.currentBpm)
                assertNull(initial.currentZone)
                assertFalse(initial.isRecording)
                assertFalse(initial.isPaused)
                assertNull(initial.pauseReason)
                assertEquals(0L, initial.effectiveDurationMs)
                assertNull(initial.batteryLevel)
                assertFalse(initial.canStartSession)
                assertFalse(initial.isImmersionMode)

                val hrm = activeHrm()
                harness.hrmDao.upsertHrm(hrm)
                harness.bleTransport.connect(ACTIVE_HRM_MAC)
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 150)
                runCurrent()

                val readyIdle = viewModel.uiState.value
                assertEquals(ACTIVE_HRM_MAC, readyIdle.activeHrm?.macAddress)
                assertEquals(150, readyIdle.currentBpm)
                assertEquals(HeartZone.ZONE_3, readyIdle.currentZone)
                assertEquals(90, readyIdle.batteryLevel)
                assertEquals(0L, readyIdle.effectiveDurationMs)
                assertTrue(readyIdle.canStartSession)
                assertFalse(readyIdle.isRecording)
                assertFalse(readyIdle.isPaused)
                assertFalse(readyIdle.isImmersionMode)
            } finally {
                harness.close()
            }
        }

    @Test
    fun `maps Recording state with live zone transitions, effective duration, and immersion mode`() =
        runTest(testDispatcher) {
            val harness = createHarness(scope = this)
            try {
                val viewModel = LiveSessionViewModel(harness.repository)
                harness.connectAndPrimeTelemetry(bpm = 110)
                runCurrent()

                viewModel.startSession()
                runCurrent()

                val recordingZ1 = viewModel.uiState.value
                assertTrue(recordingZ1.isRecording)
                assertFalse(recordingZ1.isPaused)
                assertNull(recordingZ1.pauseReason)
                assertFalse(recordingZ1.canStartSession)
                assertTrue(recordingZ1.isImmersionMode)
                assertEquals(110, recordingZ1.currentBpm)
                assertEquals(HeartZone.ZONE_1, recordingZ1.currentZone)

                harness.clock.advanceBothBy(12_000L)
                harness.repository.refreshDurations()
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 190)
                runCurrent()

                val recordingZ5 = viewModel.uiState.value
                assertTrue(recordingZ5.isRecording)
                assertTrue(recordingZ5.isImmersionMode)
                assertEquals(12_000L, recordingZ5.effectiveDurationMs)
                assertEquals(190, recordingZ5.currentBpm)
                assertEquals(HeartZone.ZONE_5, recordingZ5.currentZone)
            } finally {
                harness.close()
            }
        }

    @Test
    fun `maps Paused state across pause reasons while maintaining immersion mode`() =
        runTest(testDispatcher) {
            val harness = createHarness(scope = this)
            try {
                val viewModel = LiveSessionViewModel(harness.repository)
                harness.connectAndPrimeTelemetry(bpm = 130)
                runCurrent()

                viewModel.startSession()
                runCurrent()
                harness.clock.advanceBothBy(15_000L)
                harness.repository.refreshDurations()
                runCurrent()

                viewModel.pauseSession()
                runCurrent()

                val manualPaused = viewModel.uiState.value
                assertFalse(manualPaused.isRecording)
                assertTrue(manualPaused.isPaused)
                assertEquals(PauseReason.USER_MANUAL, manualPaused.pauseReason)
                assertEquals(15_000L, manualPaused.effectiveDurationMs)
                assertFalse(manualPaused.canStartSession)
                assertTrue(manualPaused.isImmersionMode)

                harness.clock.advanceBothBy(5_000L)
                harness.repository.refreshDurations()
                runCurrent()
                assertEquals(15_000L, viewModel.uiState.value.effectiveDurationMs)

                viewModel.resumeSession()
                runCurrent()

                val resumed = viewModel.uiState.value
                assertTrue(resumed.isRecording)
                assertFalse(resumed.isPaused)
                assertNull(resumed.pauseReason)
                assertTrue(resumed.isImmersionMode)

                harness.repository.pauseSession(PauseReason.SENSOR_CONTACT_LOST)
                runCurrent()

                val contactPaused = viewModel.uiState.value
                assertFalse(contactPaused.isRecording)
                assertTrue(contactPaused.isPaused)
                assertEquals(PauseReason.SENSOR_CONTACT_LOST, contactPaused.pauseReason)
                assertTrue(contactPaused.isImmersionMode)
            } finally {
                harness.close()
            }
        }

    @Test
    fun `maps Completed and Discarded terminal states and clears immersion mode`() =
        runTest(testDispatcher) {
            val harness = createHarness(scope = this)
            try {
                val viewModel = LiveSessionViewModel(harness.repository)
                harness.connectAndPrimeTelemetry(bpm = 170)
                runCurrent()

                viewModel.startSession()
                runCurrent()
                assertTrue(viewModel.uiState.value.isImmersionMode)

                harness.clock.advanceBothBy(12_000L)
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 170)
                runCurrent()

                viewModel.stopSession()
                runCurrent()

                val completed = viewModel.uiState.value
                assertFalse(completed.isRecording)
                assertFalse(completed.isPaused)
                assertNull(completed.pauseReason)
                assertNull(completed.currentBpm)
                assertNull(completed.currentZone)
                assertEquals(0L, completed.effectiveDurationMs)
                assertFalse(completed.canStartSession)
                assertFalse(completed.isImmersionMode)

                harness.repository.resetToIdle()
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 130)
                runCurrent()
                assertTrue(viewModel.uiState.value.canStartSession)

                viewModel.startSession()
                runCurrent()
                assertTrue(viewModel.uiState.value.isImmersionMode)

                harness.repository.discardSession()
                runCurrent()

                val discarded = viewModel.uiState.value
                assertFalse(discarded.isRecording)
                assertFalse(discarded.isPaused)
                assertNull(discarded.pauseReason)
                assertNull(discarded.currentBpm)
                assertNull(discarded.currentZone)
                assertEquals(0L, discarded.effectiveDurationMs)
                assertFalse(discarded.canStartSession)
                assertFalse(discarded.isImmersionMode)
            } finally {
                harness.close()
            }
        }

    @Test
    fun `emits deterministic reactive state sequence via StateFlow`() =
        runTest(testDispatcher) {
            val harness = createHarness(scope = this)
            try {
                val viewModel = LiveSessionViewModel(harness.repository)
                val emissions = Channel<LiveSessionUiState>(Channel.UNLIMITED)
                val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.uiState.collect { emissions.send(it) }
                }

                val initial = emissions.receive()
                assertFalse(initial.isImmersionMode)

                harness.connectAndPrimeTelemetry(bpm = 150)
                runCurrent()
                val ready = receiveUntil(emissions) { it.canStartSession }
                assertEquals(HeartZone.ZONE_3, ready.currentZone)
                assertFalse(ready.isImmersionMode)

                viewModel.startSession()
                runCurrent()
                val recording = receiveUntil(emissions) { it.isRecording }
                assertTrue(recording.isImmersionMode)

                harness.clock.advanceBothBy(11_000L)
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 150)
                runCurrent()
                viewModel.pauseSession()
                runCurrent()
                val paused = receiveUntil(emissions) { it.isPaused }
                assertTrue(paused.isImmersionMode)

                viewModel.resumeSession()
                runCurrent()
                val resumed = receiveUntil(emissions) { it.isRecording }
                assertTrue(resumed.isImmersionMode)

                harness.clock.advanceBothBy(11_000L)
                harness.repository.refreshDurations()
                runCurrent()
                viewModel.stopSession()
                runCurrent()
                val completed = receiveUntil(emissions) { !it.isImmersionMode && !it.isRecording && !it.isPaused }
                assertNull(completed.currentZone)

                collectJob.cancel()
            } finally {
                harness.close()
            }
        }

    @Test
    fun `refreshDurations updates active Effective Duration only`() =
        runTest(testDispatcher) {
            val harness = createHarness(scope = this)
            try {
                val viewModel = LiveSessionViewModel(harness.repository)
                harness.connectAndPrimeTelemetry(bpm = 150)
                runCurrent()

                viewModel.startSession()
                runCurrent()
                assertEquals(0L, viewModel.uiState.value.effectiveDurationMs)

                harness.clock.advanceBothBy(3_000L)
                viewModel.refreshDurations()
                runCurrent()
                assertEquals(3_000L, viewModel.uiState.value.effectiveDurationMs)

                viewModel.pauseSession()
                runCurrent()
                harness.clock.advanceBothBy(5_000L)
                viewModel.refreshDurations()
                runCurrent()
                assertEquals(3_000L, viewModel.uiState.value.effectiveDurationMs)
            } finally {
                harness.close()
            }
        }

    @Test
    fun `micro session prompt maps and dispatches actions`() =
        runTest(testDispatcher) {
            val harness = createHarness(scope = this)
            try {
                val viewModel = LiveSessionViewModel(harness.repository)
                harness.connectAndPrimeTelemetry(bpm = 150)
                runCurrent()

                // Start and stop under 10 seconds -> micro-session discard prompt active
                viewModel.startSession()
                runCurrent()
                harness.clock.advanceBothBy(4_000L)
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 150)
                runCurrent()

                viewModel.stopSession()
                runCurrent()
                assertTrue(viewModel.uiState.value.isRecording)
                assertTrue(viewModel.uiState.value.pendingMicroSessionDiscardPrompt)

                // Dismiss keeps Session active and clears prompt
                viewModel.dismissMicroSessionPrompt()
                runCurrent()
                assertTrue(viewModel.uiState.value.isRecording)
                assertFalse(viewModel.uiState.value.pendingMicroSessionDiscardPrompt)

                // Trigger prompt again and confirm save
                viewModel.stopSession()
                runCurrent()
                assertTrue(viewModel.uiState.value.pendingMicroSessionDiscardPrompt)

                viewModel.confirmSaveMicroSession()
                runCurrent()
                assertFalse(viewModel.uiState.value.isRecording)
                assertFalse(viewModel.uiState.value.pendingMicroSessionDiscardPrompt)
                assertFalse(viewModel.uiState.value.isImmersionMode)

                // Start another short session, trigger prompt, and discard
                harness.repository.resetToIdle()
                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 150)
                runCurrent()
                viewModel.startSession()
                runCurrent()
                viewModel.stopSession()
                runCurrent()
                assertTrue(viewModel.uiState.value.pendingMicroSessionDiscardPrompt)

                viewModel.discardSession()
                runCurrent()
                assertFalse(viewModel.uiState.value.isRecording)
                assertFalse(viewModel.uiState.value.pendingMicroSessionDiscardPrompt)
                assertFalse(viewModel.uiState.value.isImmersionMode)
            } finally {
                harness.close()
            }
        }

    private suspend fun receiveUntil(
        channel: Channel<LiveSessionUiState>,
        predicate: (LiveSessionUiState) -> Boolean
    ): LiveSessionUiState {
        while (true) {
            val item = channel.receive()
            if (predicate(item)) return item
        }
    }

    private fun createHarness(
        scope: CoroutineScope,
        initialProfile: UserProfileEntity? = defaultProfile(),
        initialHrms: List<RememberedHrmEntity> = listOf(activeHrm())
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
        var nextId = 0
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
            currentYearProvider = { 2026 },
            sessionIdGenerator = { "session-vm-test-${++nextId}" }
        )
        return TestHarness(
            bleTransport = bleTransport,
            gattClient = gattClient,
            hrmDao = hrmDao,
            clock = clock,
            sampleBuffer = sampleBuffer,
            repository = repository
        )
    }

    private class TestHarness(
        val bleTransport: FakeBleTransport,
        val gattClient: HrmGattClient,
        val hrmDao: FakeRememberedHrmDao,
        val clock: FakeSessionClock,
        val sampleBuffer: HeartRateSampleBuffer,
        val repository: SessionRepository
    ) {
        suspend fun emitHrPacket(
            macAddress: String,
            bpm: Int,
            contactDetected: Boolean = true
        ) {
            val payload = FakeBleTransport.buildHeartRateMeasurementPacket(
                bpm = bpm,
                sensorContactSupported = true,
                sensorContactDetected = contactDetected
            )
            bleTransport.emitNotification(
                macAddress = macAddress,
                value = payload,
                timestampEpochMs = clock.currentEpochMs()
            )
        }

        suspend fun connectAndPrimeTelemetry(bpm: Int) {
            bleTransport.connect(ACTIVE_HRM_MAC)
            emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = bpm, contactDetected = true)
            repository.uiState.first { it is com.example.heartzonemonitor.data.session.SessionUiState.Idle && it.canStartSession }
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
        customHrmax = 200,
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
        private const val ACTIVE_HRM_MAC = "AA:BB:CC:DD:EE:01"
    }
}
