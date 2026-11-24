package com.example.heartzonemonitor.ui.session

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.example.heartzonemonitor.data.ble.AutoConnectState
import com.example.heartzonemonitor.data.ble.BleConnectionState
import com.example.heartzonemonitor.data.ble.FakeBleTransport
import com.example.heartzonemonitor.data.ble.HrmGattClient
import com.example.heartzonemonitor.data.local.FakeHeartRateSampleDao
import com.example.heartzonemonitor.data.local.FakeRememberedHrmDao
import com.example.heartzonemonitor.data.local.FakeSessionDao
import com.example.heartzonemonitor.data.local.FakeUserProfileDao
import com.example.heartzonemonitor.data.local.HeartRateSampleBuffer
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.SessionEntity
import com.example.heartzonemonitor.data.local.entity.SessionStatus
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import com.example.heartzonemonitor.data.session.PauseReason
import com.example.heartzonemonitor.data.session.SessionRepository
import com.example.heartzonemonitor.data.session.SessionUiState
import com.example.heartzonemonitor.domain.model.HeartZone
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import com.example.heartzonemonitor.domain.timing.FakeSessionClock
import com.example.heartzonemonitor.domain.timing.MonotonicDurationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp", packageName = "com.example.heartzonemonitor")
class LiveSessionContactLossUiTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var uiScope: CoroutineScope
    private lateinit var testScheduler: TestCoroutineScheduler
    private lateinit var repoScope: CoroutineScope

    @Before
    fun setUp() {
        uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        testScheduler = TestCoroutineScheduler()
        repoScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
    }

    @After
    fun tearDown() {
        repoScope.cancel()
        uiScope.cancel()
    }

    @Test
    fun statelessBanner_rendersAmberStylingAndCleanSemantics() {
        composeTestRule.activity.setContent {
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .height(640.dp)
            ) {
                LiveSessionContent(
                    uiState = LiveSessionUiState(
                        currentBpm = 148,
                        currentZone = HeartZone.ZONE_3,
                        isRecording = true,
                        sensorContact = false,
                        isSensorContactLost = true
                    )
                )
            }
        }
        composeTestRule.waitForIdle()

        val bannerNode = composeTestRule.onNodeWithTag(LIVE_SESSION_CONTACT_LOSS_BANNER_TAG)
        bannerNode.assertIsDisplayed()
        composeTestRule.onNodeWithText("Sensor contact lost — check strap positioning")
            .assertIsDisplayed()

        val semanticsNode = bannerNode.fetchSemanticsNode()
        assertColorClose(
            expected = Color(0xFFF59E0B),
            actual = semanticsNode.config[LiveSessionBorderColorKey]
        )
        assertColorClose(
            expected = Color.Black.copy(alpha = 0.45f),
            actual = semanticsNode.config[LiveSessionContainerColorKey]
        )

        val rootBounds = composeTestRule.onNodeWithTag(LIVE_SESSION_BACKGROUND_TAG).getBoundsInRoot()
        val bannerBounds = bannerNode.getBoundsInRoot()
        assertTrue(bannerBounds.left >= rootBounds.left)
        assertTrue(bannerBounds.right <= rootBounds.right)

        // Banner must contain zero Role.Image nodes and zero forbidden text fragments
        val bannerSubtree = collectSemanticsTree(
            composeTestRule.onNodeWithTag(LIVE_SESSION_CONTACT_LOSS_BANNER_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
        )
        val forbiddenFragments = listOf("%", "heart", "pulse", "elapsed", ":")
        bannerSubtree.forEach { node ->
            assertNotEquals(Role.Image, node.config.getOrNull(SemanticsProperties.Role))
            val text = node.config.getOrNull(SemanticsProperties.Text)
                ?.joinToString(" ") { it.text }
                .orEmpty()
                .lowercase()
            val description = node.config.getOrNull(SemanticsProperties.ContentDescription)
                ?.joinToString(" ")
                .orEmpty()
                .lowercase()
            val combined = "$text $description"
            forbiddenFragments.forEach { fragment ->
                assertFalse(
                    "Found forbidden fragment '$fragment' in banner semantics: '$combined'",
                    combined.contains(fragment)
                )
            }
        }
    }

    @Test
    fun liveSessionScreen_showsPersistsAndDismissesBanner() {
        val harness = createHarness()
        try {
            composeTestRule.activity.setContent {
                LiveSessionScreen(viewModel = harness.viewModel)
            }

            // Connect Active HRM, stream valid contact telemetry, and start Session
            harness.connectAndEmitHr(bpm = 152, contactSupported = true, contactDetected = true)
            composeTestRule.waitForIdle()

            assertFalse(harness.viewModel.uiState.value.isSensorContactLost)
            assertEquals(true, harness.viewModel.uiState.value.sensorContact)
            composeTestRule.onNodeWithTag(LIVE_SESSION_CONTACT_LOSS_BANNER_TAG).assertDoesNotExist()

            runBlocking { harness.repository.startSession() }
            testScheduler.runCurrent()
            composeTestRule.waitForIdle()

            assertTrue(harness.viewModel.uiState.value.isRecording)
            assertFalse(harness.viewModel.uiState.value.isSensorContactLost)
            composeTestRule.onNodeWithTag(LIVE_SESSION_CONTACT_LOSS_BANNER_TAG).assertDoesNotExist()
            composeTestRule.onNodeWithTag(LIVE_SESSION_PAUSE_RESUME_BUTTON_TAG).assertTextEquals("PAUSE")

            // Contact lost while BLE remains connected -> banner appears IMMEDIATELY before 5s auto-pause
            harness.emitHr(bpm = 0, contactSupported = true, contactDetected = false)
            composeTestRule.waitForIdle()

            val contactLostRecording = harness.viewModel.uiState.value
            assertTrue(contactLostRecording.isRecording)
            assertFalse(contactLostRecording.isPaused)
            assertEquals(false, contactLostRecording.sensorContact)
            assertTrue(contactLostRecording.isSensorContactLost)
            composeTestRule.onNodeWithTag(LIVE_SESSION_CONTACT_LOSS_BANNER_TAG).assertIsDisplayed()
            composeTestRule.onNodeWithText("Sensor contact lost — check strap positioning")
                .assertIsDisplayed()

            // Advance 5,000ms -> transitions to Paused(SENSOR_CONTACT_LOST), banner stays visible, button switches to RESUME
            harness.advanceBothClocks(SessionRepository.SENSOR_CONTACT_LOSS_DEBOUNCE_MS)
            composeTestRule.waitForIdle()

            val contactLostPaused = harness.viewModel.uiState.value
            assertFalse(contactLostPaused.isRecording)
            assertTrue(contactLostPaused.isPaused)
            assertEquals(PauseReason.SENSOR_CONTACT_LOST, contactLostPaused.pauseReason)
            assertTrue(contactLostPaused.isSensorContactLost)
            composeTestRule.onNodeWithTag(LIVE_SESSION_CONTACT_LOSS_BANNER_TAG).assertIsDisplayed()
            composeTestRule.onNodeWithText("Sensor contact lost — check strap positioning")
                .assertIsDisplayed()
            composeTestRule.onNodeWithTag(LIVE_SESSION_PAUSE_RESUME_BUTTON_TAG).assertTextEquals("RESUME")

            // Sensor contact restored -> auto-resumes to Recording and immediately dismisses banner
            harness.emitHr(bpm = 158, contactSupported = true, contactDetected = true)
            composeTestRule.waitForIdle()

            val resumed = harness.viewModel.uiState.value
            assertTrue(resumed.isRecording)
            assertFalse(resumed.isPaused)
            assertNull(resumed.pauseReason)
            assertEquals(true, resumed.sensorContact)
            assertFalse(resumed.isSensorContactLost)
            assertEquals(158, resumed.currentBpm)
            composeTestRule.onNodeWithTag(LIVE_SESSION_CONTACT_LOSS_BANNER_TAG).assertDoesNotExist()
            composeTestRule.onNodeWithTag(LIVE_SESSION_BPM_TEXT_TAG).assertTextEquals("158")
            composeTestRule.onNodeWithTag(LIVE_SESSION_PAUSE_RESUME_BUTTON_TAG).assertTextEquals("PAUSE")
        } finally {
            harness.close()
        }
    }

    @Test
    fun bannerAbsentWhenNormalOrNotSupported_andIdleBehavior() {
        val harness = createHarness()
        try {
            composeTestRule.activity.setContent {
                LiveSessionScreen(viewModel = harness.viewModel)
            }

            // NOT_SUPPORTED HRM in Idle and Recording -> banner never appears
            harness.connectAndEmitHr(bpm = 136, contactSupported = false, contactDetected = false)
            composeTestRule.waitForIdle()

            assertEquals(true, harness.viewModel.uiState.value.sensorContact)
            assertFalse(harness.viewModel.uiState.value.isSensorContactLost)
            composeTestRule.onNodeWithTag(LIVE_SESSION_CONTACT_LOSS_BANNER_TAG).assertDoesNotExist()

            runBlocking { harness.repository.startSession() }
            testScheduler.runCurrent()
            harness.advanceBothClocks(6_000L)
            harness.emitHr(bpm = 142, contactSupported = false, contactDetected = false)
            composeTestRule.waitForIdle()

            assertTrue(harness.viewModel.uiState.value.isRecording)
            assertFalse(harness.viewModel.uiState.value.isSensorContactLost)
            composeTestRule.onNodeWithTag(LIVE_SESSION_CONTACT_LOSS_BANNER_TAG).assertDoesNotExist()

            // Manual pause with contact intact -> banner remains absent
            runBlocking { harness.repository.pauseSession(PauseReason.USER_MANUAL) }
            testScheduler.runCurrent()
            composeTestRule.waitForIdle()
            assertFalse(harness.viewModel.uiState.value.isSensorContactLost)
            composeTestRule.onNodeWithTag(LIVE_SESSION_CONTACT_LOSS_BANNER_TAG).assertDoesNotExist()

            // Contact lost while manually paused -> banner appears; contact restored -> banner dismisses without auto-resume
            harness.emitHr(bpm = 0, contactSupported = true, contactDetected = false)
            composeTestRule.waitForIdle()
            assertTrue(harness.viewModel.uiState.value.isSensorContactLost)
            composeTestRule.onNodeWithTag(LIVE_SESSION_CONTACT_LOSS_BANNER_TAG).assertIsDisplayed()

            harness.emitHr(bpm = 140, contactSupported = true, contactDetected = true)
            composeTestRule.waitForIdle()
            assertTrue(harness.viewModel.uiState.value.isPaused)
            assertEquals(PauseReason.USER_MANUAL, harness.viewModel.uiState.value.pauseReason)
            assertFalse(harness.viewModel.uiState.value.isSensorContactLost)
            composeTestRule.onNodeWithTag(LIVE_SESSION_CONTACT_LOSS_BANNER_TAG).assertDoesNotExist()

            // Discard & reset to Idle: contact lost while connected in Idle shows banner; disconnect hides it
            runBlocking { harness.repository.discardSession() }
            harness.repository.resetToIdle()
            testScheduler.runCurrent()
            harness.emitHr(bpm = 0, contactSupported = true, contactDetected = false)
            composeTestRule.waitForIdle()

            assertTrue(harness.viewModel.uiState.value.isSensorContactLost)
            composeTestRule.onNodeWithTag(LIVE_SESSION_CONTACT_LOSS_BANNER_TAG).assertIsDisplayed()

            harness.bleTransport.simulateDisconnect()
            testScheduler.runCurrent()
            composeTestRule.waitForIdle()

            assertFalse(harness.viewModel.uiState.value.isSensorContactLost)
            assertNull(harness.viewModel.uiState.value.sensorContact)
            composeTestRule.onNodeWithTag(LIVE_SESSION_CONTACT_LOSS_BANNER_TAG).assertDoesNotExist()
        } finally {
            harness.close()
        }
    }

    @Test
    fun contactLossPriorityOverAutoConnect_andStateMapping() {
        composeTestRule.activity.setContent {
            LiveSessionContent(
                uiState = LiveSessionUiState(
                    isRecording = false,
                    isPaused = false,
                    sensorContact = false,
                    isSensorContactLost = true,
                    autoConnectState = AutoConnectState.DormantPrompt(
                        macAddress = ACTIVE_HRM_MAC,
                        hrmDisplayName = "Chest Strap"
                    )
                )
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(LIVE_SESSION_CONTACT_LOSS_BANNER_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(LIVE_SESSION_DORMANT_PROMPT_TAG).assertDoesNotExist()

        // Verify SessionUiState.toLiveSessionUiState() mapping across Idle, Completed, and Discarded
        val hrm = activeHrm()
        val idleWrongMac = SessionUiState.Idle(
            activeHrm = hrm,
            connectionState = BleConnectionState.Connected("FF:EE:DD:CC:BB:AA"),
            sensorContact = false
        ).toLiveSessionUiState()
        assertFalse(idleWrongMac.isSensorContactLost)

        val idleConnectedLost = SessionUiState.Idle(
            activeHrm = hrm,
            connectionState = BleConnectionState.Connected(ACTIVE_HRM_MAC),
            sensorContact = false
        ).toLiveSessionUiState()
        assertTrue(idleConnectedLost.isSensorContactLost)
        assertEquals(false, idleConnectedLost.sensorContact)

        val completedMapped = SessionUiState.Completed(
            SessionEntity(
                id = "completed-1",
                startEpochMs = 1_000L,
                endEpochMs = 15_000L,
                elapsedDurationMs = 14_000L,
                effectiveDurationMs = 14_000L,
                pausedDurationMs = 0L,
                status = SessionStatus.COMPLETED,
                hrmMacAddress = ACTIVE_HRM_MAC,
                userAge = 30,
                calculatedHrmax = 200,
                restingHr = null,
                calculationMode = ZoneCalculationMode.PERCENT_HRMAX
            )
        ).toLiveSessionUiState()
        assertNull(completedMapped.sensorContact)
        assertFalse(completedMapped.isSensorContactLost)

        val discardedMapped = SessionUiState.Discarded("discarded-1").toLiveSessionUiState()
        assertNull(discardedMapped.sensorContact)
        assertFalse(discardedMapped.isSensorContactLost)
    }

    private fun createHarness(): UiHarness {
        val bleTransport = FakeBleTransport()
        val hrmDao = FakeRememberedHrmDao(listOf(activeHrm()))
        val profileDao = FakeUserProfileDao(
            initialProfile = UserProfileEntity(
                id = UserProfileEntity.SINGLETON_ID,
                birthYear = 1996,
                restingHr = null,
                customHrmax = 200,
                calculationMode = ZoneCalculationMode.PERCENT_HRMAX,
                updatedAtEpochMs = 1_700_000_000_000L
            )
        )
        val sampleDao = FakeHeartRateSampleDao()
        val sessionDao = FakeSessionDao(sampleDao = sampleDao)
        val clock = FakeSessionClock(initialRealtimeMs = 10_000L, initialEpochMs = 1_700_000_000_000L)
        val sampleBuffer = HeartRateSampleBuffer(sampleDao = sampleDao, scope = uiScope)
        val gattClient = HrmGattClient(bleTransport = bleTransport, scope = repoScope)
        val repository = SessionRepository(
            bleTransport = bleTransport,
            rememberedHrmDao = hrmDao,
            userProfileDao = profileDao,
            sessionDao = sessionDao,
            sampleDao = sampleDao,
            scope = repoScope,
            hrmGattClient = gattClient,
            clock = clock,
            durationEngine = MonotonicDurationEngine(clock),
            sampleBuffer = sampleBuffer,
            currentYearProvider = { 2026 }
        )
        testScheduler.runCurrent()
        val viewModel = LiveSessionViewModel(sessionRepository = repository)
        return UiHarness(
            testScheduler = testScheduler,
            bleTransport = bleTransport,
            clock = clock,
            sampleBuffer = sampleBuffer,
            gattClient = gattClient,
            repository = repository,
            viewModel = viewModel
        )
    }

    private class UiHarness(
        private val testScheduler: TestCoroutineScheduler,
        val bleTransport: FakeBleTransport,
        val clock: FakeSessionClock,
        val sampleBuffer: HeartRateSampleBuffer,
        val gattClient: HrmGattClient,
        val repository: SessionRepository,
        val viewModel: LiveSessionViewModel
    ) {
        fun connectAndEmitHr(
            bpm: Int,
            contactSupported: Boolean,
            contactDetected: Boolean
        ) {
            runBlocking {
                bleTransport.connect(ACTIVE_HRM_MAC)
                bleTransport.emitNotification(
                    macAddress = ACTIVE_HRM_MAC,
                    value = FakeBleTransport.buildHeartRateMeasurementPacket(
                        bpm = bpm,
                        sensorContactSupported = contactSupported,
                        sensorContactDetected = contactDetected
                    ),
                    timestampEpochMs = clock.currentEpochMs()
                )
            }
            testScheduler.runCurrent()
        }

        fun emitHr(
            bpm: Int,
            contactSupported: Boolean,
            contactDetected: Boolean
        ) {
            runBlocking {
                bleTransport.emitNotification(
                    macAddress = ACTIVE_HRM_MAC,
                    value = FakeBleTransport.buildHeartRateMeasurementPacket(
                        bpm = bpm,
                        sensorContactSupported = contactSupported,
                        sensorContactDetected = contactDetected
                    ),
                    timestampEpochMs = clock.currentEpochMs()
                )
            }
            testScheduler.runCurrent()
        }

        fun advanceBothClocks(deltaMs: Long) {
            clock.advanceBothBy(deltaMs)
            testScheduler.advanceTimeBy(deltaMs)
            testScheduler.runCurrent()
        }

        fun close() {
            runBlocking { sampleBuffer.discard() }
            gattClient.close()
            repository.close()
        }
    }

    private fun activeHrm(): RememberedHrmEntity = RememberedHrmEntity(
        macAddress = ACTIVE_HRM_MAC,
        advertisedName = "COOSPO H808S",
        customNickname = "Chest Strap",
        lastSeenTimestamp = 1_700_000_000_000L,
        batteryLevel = 92,
        isActive = true
    )

    private fun assertColorClose(expected: Color, actual: Color) {
        assertEquals(expected.red, actual.red, 0.002f)
        assertEquals(expected.green, actual.green, 0.002f)
        assertEquals(expected.blue, actual.blue, 0.002f)
        assertEquals(expected.alpha, actual.alpha, 0.002f)
    }

    private fun collectSemanticsTree(root: SemanticsNode): List<SemanticsNode> {
        val collected = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) {
            collected.add(node)
            node.children.forEach(::visit)
        }
        visit(root)
        return collected
    }

    companion object {
        private const val ACTIVE_HRM_MAC = "AA:BB:CC:DD:EE:01"
    }
}
