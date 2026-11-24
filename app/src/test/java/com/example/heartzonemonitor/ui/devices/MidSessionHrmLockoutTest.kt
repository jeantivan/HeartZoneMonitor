package com.example.heartzonemonitor.ui.devices

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.heartzonemonitor.data.ble.FakeBleTransport
import com.example.heartzonemonitor.data.ble.HrmGattClient
import com.example.heartzonemonitor.data.local.FakeHeartRateSampleDao
import com.example.heartzonemonitor.data.local.FakeRememberedHrmDao
import com.example.heartzonemonitor.data.local.FakeSessionDao
import com.example.heartzonemonitor.data.local.FakeUserProfileDao
import com.example.heartzonemonitor.data.local.HeartRateSampleBuffer
import com.example.heartzonemonitor.data.local.dao.RememberedHrmDao
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import com.example.heartzonemonitor.data.session.PauseReason
import com.example.heartzonemonitor.data.session.SessionRepository
import com.example.heartzonemonitor.data.session.SessionUiState
import com.example.heartzonemonitor.data.session.StartSessionResult
import com.example.heartzonemonitor.data.session.StopSessionOutcome
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import com.example.heartzonemonitor.domain.timing.FakeSessionClock
import com.example.heartzonemonitor.domain.timing.MonotonicDurationEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MidSessionHrmLockoutTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var uiScope: CoroutineScope

    @Before
    fun setUp() {
        uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    @After
    fun tearDown() {
        uiScope.cancel()
    }

    @Test
    fun idleState_enablesSwitchingAndForgettingActiveHrm() {
        val harness = createHarness(scope = uiScope)
        var scanClickCount = 0
        try {
            composeTestRule.activity.setContent {
                RememberedHrmsScreen(
                    viewModel = harness.viewModel,
                    onNavigateBack = {},
                    onScanForNewHrm = { scanClickCount += 1 }
                )
            }
            composeTestRule.waitForIdle()

            assertTrue(harness.repository.uiState.value is SessionUiState.Idle)
            assertFalse(harness.repository.isSessionActive)
            assertFalse(harness.viewModel.uiState.value.isSessionActive)

            // Notice is absent and all controls are enabled in Idle
            composeTestRule.onNodeWithTag(REMEMBERED_HRMS_LOCKOUT_NOTICE_TAG).assertDoesNotExist()
            composeTestRule.onNodeWithText(EXACT_LOCKOUT_NOTICE).assertDoesNotExist()
            composeTestRule.onNodeWithText("LOCKED").assertDoesNotExist()
            composeTestRule.onNodeWithTag("set_active_$SECONDARY_HRM_MAC")
                .performScrollTo()
                .assertIsEnabled()
            composeTestRule.onNodeWithTag("inactive_hrm_card_$SECONDARY_HRM_MAC")
                .performScrollTo()
                .assertIsEnabled()
            composeTestRule.onNodeWithTag("forget_device_$ACTIVE_HRM_MAC")
                .performScrollTo()
                .assertIsEnabled()
            composeTestRule.onNodeWithTag(SCAN_FOR_NEW_HRM_BUTTON_TAG)
                .performScrollTo()
                .assertIsEnabled()
                .performClick()
            assertEquals(1, scanClickCount)

            // Switch Active HRM to secondary Remembered HRM via UI
            composeTestRule.onNodeWithTag("set_active_$SECONDARY_HRM_MAC")
                .performScrollTo()
                .performClick()
            composeTestRule.waitForIdle()

            assertEquals(SECONDARY_HRM_MAC, runBlocking { harness.hrmDao.getActiveHrm()?.macAddress })
            composeTestRule.onNodeWithTag("active_hrm_card_$SECONDARY_HRM_MAC")
                .performScrollTo()
                .assertIsDisplayed()

            // Forget the newly active HRM via UI confirmation dialog
            composeTestRule.onNodeWithTag("forget_device_$SECONDARY_HRM_MAC")
                .performScrollTo()
                .performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Forget Heart Rate Monitor?").assertIsDisplayed()
            composeTestRule.onNodeWithText("Forget").performClick()
            composeTestRule.waitForIdle()

            assertEquals(listOf(SECONDARY_HRM_MAC), harness.disconnectedMacs)
            assertNull(runBlocking { harness.hrmDao.getActiveHrm() })
            assertNull(runBlocking { harness.hrmDao.getHrmByMacAddress(SECONDARY_HRM_MAC) })
            composeTestRule.onNodeWithText("Active HRM Removed")
                .performScrollTo()
                .assertIsDisplayed()
        } finally {
            runBlocking { harness.close() }
        }
    }

    @Test
    fun recordingLocksControlsAndUnlocksOnCompletedAndIdle() {
        val harness = createHarness(scope = uiScope)
        var scanClickCount = 0
        try {
            composeTestRule.activity.setContent {
                RememberedHrmsScreen(
                    viewModel = harness.viewModel,
                    onNavigateBack = {},
                    onScanForNewHrm = { scanClickCount += 1 }
                )
            }
            composeTestRule.waitForIdle()

            runBlocking {
                harness.connectAndStartRecording()
            }
            composeTestRule.waitForIdle()

            assertTrue(harness.repository.uiState.value is SessionUiState.Recording)
            assertTrue(harness.repository.isSessionActive)
            assertTrue(harness.viewModel.uiState.value.isSessionActive)

            // Lockout notice displays exact required message and LOCKED badge
            composeTestRule.onNodeWithTag(REMEMBERED_HRMS_LOCKOUT_NOTICE_TAG)
                .performScrollTo()
                .assertIsDisplayed()
            composeTestRule.onNodeWithText("LOCKED").assertIsDisplayed()
            composeTestRule.onNodeWithText(EXACT_LOCKOUT_NOTICE).assertIsDisplayed()

            // Active HRM switching, Active HRM forgetting, and Scan controls are disabled
            composeTestRule.onNodeWithTag("set_active_$SECONDARY_HRM_MAC")
                .performScrollTo()
                .assertIsNotEnabled()
            composeTestRule.onNodeWithTag("inactive_hrm_card_$SECONDARY_HRM_MAC")
                .performScrollTo()
                .assertIsNotEnabled()
            composeTestRule.onNodeWithTag("forget_device_$ACTIVE_HRM_MAC")
                .performScrollTo()
                .assertIsNotEnabled()
            composeTestRule.onNodeWithTag(SCAN_FOR_NEW_HRM_BUTTON_TAG)
                .performScrollTo()
                .assertIsNotEnabled()
                .performClick()
            assertEquals(0, scanClickCount)

            // Inactive HRM forget control remains enabled
            composeTestRule.onNodeWithTag("forget_device_$SECONDARY_HRM_MAC")
                .performScrollTo()
                .assertIsEnabled()

            // Programmatic promote and active forget attempts are rejected with zero DAO/GATT mutations
            harness.recordingDao.clearRecordedWrites()
            harness.viewModel.promoteToActive(SECONDARY_HRM_MAC)
            harness.viewModel.requestForgetDevice(activeStrap())
            composeTestRule.waitForIdle()

            assertEquals(ACTIVE_HRM_MAC, runBlocking { harness.hrmDao.getActiveHrm()?.macAddress })
            assertTrue(harness.recordingDao.writeCalls.isEmpty())
            assertTrue(harness.disconnectedMacs.isEmpty())
            assertNull(harness.viewModel.uiState.value.forgetConfirmationTarget)
            assertEquals(EXACT_LOCKOUT_NOTICE, harness.viewModel.uiState.value.errorMessage)
            // Single non-duplicated lockout notice node in UI
            composeTestRule.onNodeWithText(EXACT_LOCKOUT_NOTICE)
                .performScrollTo()
                .assertIsDisplayed()

            // Complete Session via stopSession(forceSave = true) -> immediately unlocked
            val outcome = runBlocking { harness.repository.stopSession(forceSave = true) }
            assertTrue(outcome is StopSessionOutcome.Completed)
            assertTrue(harness.repository.uiState.value is SessionUiState.Completed)
            assertFalse(harness.repository.isSessionActive)
            composeTestRule.waitForIdle()

            assertFalse(harness.viewModel.uiState.value.isSessionActive)
            assertNull(harness.viewModel.uiState.value.errorMessage)
            composeTestRule.onNodeWithTag(REMEMBERED_HRMS_LOCKOUT_NOTICE_TAG).assertDoesNotExist()
            composeTestRule.onNodeWithText(EXACT_LOCKOUT_NOTICE).assertDoesNotExist()
            composeTestRule.onNodeWithText("LOCKED").assertDoesNotExist()

            // Controls re-enabled in Completed; switching succeeds
            composeTestRule.onNodeWithTag(SCAN_FOR_NEW_HRM_BUTTON_TAG)
                .performScrollTo()
                .assertIsEnabled()
            composeTestRule.onNodeWithTag("set_active_$SECONDARY_HRM_MAC")
                .performScrollTo()
                .assertIsEnabled()
                .performClick()
            composeTestRule.waitForIdle()

            assertEquals(SECONDARY_HRM_MAC, runBlocking { harness.hrmDao.getActiveHrm()?.macAddress })
            composeTestRule.onNodeWithTag("active_hrm_card_$SECONDARY_HRM_MAC")
                .performScrollTo()
                .assertIsDisplayed()

            // Forgetting Active HRM also succeeds in Completed
            composeTestRule.onNodeWithTag("forget_device_$SECONDARY_HRM_MAC")
                .performScrollTo()
                .assertIsEnabled()
                .performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Forget").performClick()
            composeTestRule.waitForIdle()

            assertEquals(listOf(SECONDARY_HRM_MAC), harness.disconnectedMacs)
            assertNull(runBlocking { harness.hrmDao.getHrmByMacAddress(SECONDARY_HRM_MAC) })

            // Transition to Idle via resetToIdle() -> remains unlocked and can promote remaining HRM
            harness.repository.resetToIdle()
            composeTestRule.waitForIdle()
            assertTrue(harness.repository.uiState.value is SessionUiState.Idle)
            assertFalse(harness.viewModel.uiState.value.isSessionActive)
            composeTestRule.onNodeWithTag(REMEMBERED_HRMS_LOCKOUT_NOTICE_TAG).assertDoesNotExist()
            composeTestRule.onNodeWithTag("set_active_$ACTIVE_HRM_MAC")
                .performScrollTo()
                .assertIsEnabled()
                .performClick()
            composeTestRule.waitForIdle()

            assertEquals(ACTIVE_HRM_MAC, runBlocking { harness.hrmDao.getActiveHrm()?.macAddress })
        } finally {
            runBlocking { harness.close() }
        }
    }

    @Test
    fun pausedAcrossAllReasons_staysLockedWhileInactiveHrmUnaffected() {
        val harness = createHarness(
            scope = uiScope,
            initialHrms = listOf(activeStrap(), secondaryArmband(), backupStrap())
        )
        try {
            composeTestRule.activity.setContent {
                RememberedHrmsScreen(
                    viewModel = harness.viewModel,
                    onNavigateBack = {},
                    onScanForNewHrm = {}
                )
            }
            composeTestRule.waitForIdle()

            runBlocking {
                harness.connectAndStartRecording()
            }
            composeTestRule.waitForIdle()

            val allPauseReasons = listOf(
                PauseReason.USER_MANUAL,
                PauseReason.BLE_CONNECTION_LOST,
                PauseReason.SENSOR_CONTACT_LOST
            )

            allPauseReasons.forEach { reason ->
                val paused = runBlocking { harness.repository.pauseSession(reason) }
                assertTrue("Expected pauseSession($reason) to succeed", paused)
                val state = harness.repository.uiState.value as SessionUiState.Paused
                assertEquals(reason, state.reason)
                assertTrue(harness.repository.isSessionActive)
                composeTestRule.waitForIdle()

                assertTrue(harness.viewModel.uiState.value.isSessionActive)
                composeTestRule.onNodeWithTag(REMEMBERED_HRMS_LOCKOUT_NOTICE_TAG)
                    .performScrollTo()
                    .assertIsDisplayed()
                composeTestRule.onNodeWithText(EXACT_LOCKOUT_NOTICE).assertIsDisplayed()
                composeTestRule.onNodeWithTag("set_active_$SECONDARY_HRM_MAC")
                    .performScrollTo()
                    .assertIsNotEnabled()
                composeTestRule.onNodeWithTag("inactive_hrm_card_$SECONDARY_HRM_MAC")
                    .performScrollTo()
                    .assertIsNotEnabled()
                composeTestRule.onNodeWithTag("forget_device_$ACTIVE_HRM_MAC")
                    .performScrollTo()
                    .assertIsNotEnabled()
                composeTestRule.onNodeWithTag(SCAN_FOR_NEW_HRM_BUTTON_TAG)
                    .performScrollTo()
                    .assertIsNotEnabled()

                harness.recordingDao.clearRecordedWrites()
                harness.viewModel.promoteToActive(SECONDARY_HRM_MAC)
                harness.viewModel.requestForgetDevice(activeStrap())
                composeTestRule.waitForIdle()

                assertEquals(ACTIVE_HRM_MAC, runBlocking { harness.hrmDao.getActiveHrm()?.macAddress })
                assertTrue(harness.recordingDao.writeCalls.isEmpty())
                assertTrue(harness.disconnectedMacs.isEmpty())
                assertEquals(EXACT_LOCKOUT_NOTICE, harness.viewModel.uiState.value.errorMessage)

                val resumed = runBlocking { harness.repository.resumeSession() }
                assertTrue("Expected resumeSession() after $reason to succeed", resumed)
                composeTestRule.waitForIdle()
            }

            // Re-pause with SENSOR_CONTACT_LOST and confirm forgetting an INACTIVE HRM still works
            assertTrue(runBlocking { harness.repository.pauseSession(PauseReason.SENSOR_CONTACT_LOST) })
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("forget_device_$BACKUP_HRM_MAC")
                .performScrollTo()
                .assertIsEnabled()
                .performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Forget Heart Rate Monitor?").assertIsDisplayed()
            composeTestRule.onNodeWithText("Forget").performClick()
            composeTestRule.waitForIdle()

            assertNull(runBlocking { harness.hrmDao.getHrmByMacAddress(BACKUP_HRM_MAC) })
            assertEquals(ACTIVE_HRM_MAC, runBlocking { harness.hrmDao.getActiveHrm()?.macAddress })
            assertTrue(harness.disconnectedMacs.isEmpty())
        } finally {
            runBlocking { harness.close() }
        }
    }

    @Test
    fun discardedUnlocksImmediatelyAndResetToIdleStaysUnlocked() {
        val harness = createHarness(scope = uiScope)
        try {
            composeTestRule.activity.setContent {
                RememberedHrmsScreen(
                    viewModel = harness.viewModel,
                    onNavigateBack = {},
                    onScanForNewHrm = {}
                )
            }
            composeTestRule.waitForIdle()

            runBlocking {
                harness.connectAndStartRecording()
            }
            composeTestRule.waitForIdle()

            // Locked while Recording
            composeTestRule.onNodeWithTag(REMEMBERED_HRMS_LOCKOUT_NOTICE_TAG)
                .performScrollTo()
                .assertIsDisplayed()
            harness.viewModel.promoteToActive(SECONDARY_HRM_MAC)
            composeTestRule.waitForIdle()
            assertEquals(EXACT_LOCKOUT_NOTICE, harness.viewModel.uiState.value.errorMessage)

            // Discard Session -> immediately unlocked
            val discarded = runBlocking { harness.repository.discardSession() }
            assertTrue(discarded)
            assertTrue(harness.repository.uiState.value is SessionUiState.Discarded)
            assertFalse(harness.repository.isSessionActive)
            composeTestRule.waitForIdle()

            assertFalse(harness.viewModel.uiState.value.isSessionActive)
            assertNull(harness.viewModel.uiState.value.errorMessage)
            composeTestRule.onNodeWithTag(REMEMBERED_HRMS_LOCKOUT_NOTICE_TAG).assertDoesNotExist()
            composeTestRule.onNodeWithText(EXACT_LOCKOUT_NOTICE).assertDoesNotExist()

            // Switching to another Remembered HRM succeeds in Discarded
            composeTestRule.onNodeWithTag("set_active_$SECONDARY_HRM_MAC")
                .performScrollTo()
                .assertIsEnabled()
                .performClick()
            composeTestRule.waitForIdle()
            assertEquals(SECONDARY_HRM_MAC, runBlocking { harness.hrmDao.getActiveHrm()?.macAddress })

            // Forgetting Active HRM succeeds in Discarded
            composeTestRule.onNodeWithTag("forget_device_$SECONDARY_HRM_MAC")
                .performScrollTo()
                .assertIsEnabled()
                .performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Forget").performClick()
            composeTestRule.waitForIdle()

            assertEquals(listOf(SECONDARY_HRM_MAC), harness.disconnectedMacs)
            assertNull(runBlocking { harness.hrmDao.getHrmByMacAddress(SECONDARY_HRM_MAC) })

            // Reset to Idle -> stays unlocked and promotes remaining Remembered HRM
            harness.repository.resetToIdle()
            composeTestRule.waitForIdle()
            assertTrue(harness.repository.uiState.value is SessionUiState.Idle)
            assertFalse(harness.viewModel.uiState.value.isSessionActive)
            composeTestRule.onNodeWithTag("set_active_$ACTIVE_HRM_MAC")
                .performScrollTo()
                .assertIsEnabled()
                .performClick()
            composeTestRule.waitForIdle()

            assertEquals(ACTIVE_HRM_MAC, runBlocking { harness.hrmDao.getActiveHrm()?.macAddress })
        } finally {
            runBlocking { harness.close() }
        }
    }

    @Test
    fun inFlightActiveForgetDialog_closesAndBlocksOnSessionStart() {
        val harness = createHarness(scope = uiScope)
        try {
            composeTestRule.activity.setContent {
                RememberedHrmsScreen(
                    viewModel = harness.viewModel,
                    onNavigateBack = {},
                    onScanForNewHrm = {}
                )
            }
            composeTestRule.waitForIdle()

            // Open Forget confirmation dialog for Active HRM while Idle
            composeTestRule.onNodeWithTag("forget_device_$ACTIVE_HRM_MAC")
                .performScrollTo()
                .performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithText("Forget Heart Rate Monitor?").assertIsDisplayed()
            assertEquals(ACTIVE_HRM_MAC, harness.viewModel.uiState.value.forgetConfirmationTarget?.macAddress)

            // Session transitions to Recording while confirmation dialog is open
            harness.recordingDao.clearRecordedWrites()
            runBlocking {
                harness.connectAndStartRecording()
            }
            composeTestRule.waitForIdle()

            // Dialog closes automatically and lockout notice is surfaced with zero mutations
            composeTestRule.onNodeWithText("Forget Heart Rate Monitor?").assertDoesNotExist()
            composeTestRule.onNodeWithTag(REMEMBERED_HRMS_LOCKOUT_NOTICE_TAG)
                .performScrollTo()
                .assertIsDisplayed()
            composeTestRule.onNodeWithText(EXACT_LOCKOUT_NOTICE).assertIsDisplayed()
            assertNull(harness.viewModel.uiState.value.forgetConfirmationTarget)
            assertEquals(EXACT_LOCKOUT_NOTICE, harness.viewModel.uiState.value.errorMessage)

            // Late confirmation attempt is blocked with zero disconnect or DAO deletion
            harness.viewModel.confirmForgetDevice()
            composeTestRule.waitForIdle()

            assertTrue(harness.disconnectedMacs.isEmpty())
            assertTrue(harness.recordingDao.writeCalls.isEmpty())
            assertEquals(ACTIVE_HRM_MAC, runBlocking { harness.hrmDao.getActiveHrm()?.macAddress })
        } finally {
            runBlocking { harness.close() }
        }
    }

    @Test
    fun syncSessionActiveProvider_blocksBeforeFlowCollectionRuns() {
        val stalledFlow: Flow<Boolean> = flow { awaitCancellation() }
        val harness = createHarness(
            scope = uiScope,
            customIsSessionActiveFlow = stalledFlow
        )
        try {
            // Confirm Flow has not emitted and VM starts unlocked in Idle
            assertFalse(harness.viewModel.uiState.value.isSessionActive)

            // Open Active HRM forget confirmation dialog while Idle
            harness.viewModel.requestForgetDevice(activeStrap())
            assertEquals(ACTIVE_HRM_MAC, harness.viewModel.uiState.value.forgetConfirmationTarget?.macAddress)

            // Transition real SessionRepository to Recording without any Flow emission
            harness.recordingDao.clearRecordedWrites()
            runBlocking {
                harness.connectAndStartRecording()
            }
            assertTrue(harness.repository.isSessionActive)

            // Confirming forget is blocked synchronously by isSessionActiveProvider
            harness.viewModel.confirmForgetDevice()
            assertTrue(harness.viewModel.uiState.value.isSessionActive)
            assertNull(harness.viewModel.uiState.value.forgetConfirmationTarget)
            assertEquals(EXACT_LOCKOUT_NOTICE, harness.viewModel.uiState.value.errorMessage)
            assertTrue(harness.disconnectedMacs.isEmpty())
            assertTrue(harness.recordingDao.writeCalls.isEmpty())
            assertEquals(ACTIVE_HRM_MAC, runBlocking { harness.hrmDao.getActiveHrm()?.macAddress })

            // Fresh ViewModel constructed while Session is ALREADY Recording initializes locked immediately
            val alreadyLockedViewModel = RememberedHrmsViewModel(
                rememberedHrmDao = harness.recordingDao,
                isSessionActiveFlow = stalledFlow,
                onDisconnectActiveHrm = { mac -> harness.disconnectedMacs.add(mac) },
                isSessionActiveProvider = { harness.repository.isSessionActive }
            )
            assertTrue(alreadyLockedViewModel.uiState.value.isSessionActive)

            alreadyLockedViewModel.promoteToActive(SECONDARY_HRM_MAC)
            alreadyLockedViewModel.requestForgetDevice(activeStrap())
            assertEquals(EXACT_LOCKOUT_NOTICE, alreadyLockedViewModel.uiState.value.errorMessage)
            assertNull(alreadyLockedViewModel.uiState.value.forgetConfirmationTarget)
            assertTrue(harness.recordingDao.writeCalls.isEmpty())
            assertTrue(harness.disconnectedMacs.isEmpty())

            // Separate ViewModel with stalled Flow where promoteToActive is invoked before any Flow emission
            var liveSessionActive = false
            val uncollectedViewModel = RememberedHrmsViewModel(
                rememberedHrmDao = harness.recordingDao,
                isSessionActiveFlow = stalledFlow,
                onDisconnectActiveHrm = { mac -> harness.disconnectedMacs.add(mac) },
                isSessionActiveProvider = { liveSessionActive }
            )
            assertFalse(uncollectedViewModel.uiState.value.isSessionActive)

            liveSessionActive = true
            uncollectedViewModel.promoteToActive(SECONDARY_HRM_MAC)
            assertTrue(uncollectedViewModel.uiState.value.isSessionActive)
            assertEquals(EXACT_LOCKOUT_NOTICE, uncollectedViewModel.uiState.value.errorMessage)
            assertTrue(harness.recordingDao.writeCalls.isEmpty())
        } finally {
            runBlocking { harness.close() }
        }
    }

    @Test
    fun queuedCoroutines_recheckSessionActiveBeforeDaoOrGatt() {
        val testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        try {
            runTest(testDispatcher) {
                val harness = createHarness(scope = this)
                try {
                    runCurrent()
                    harness.connectAndPrimeTelemetry()
                    runCurrent()

                    val observedLockoutStates = Channel<Boolean>(Channel.UNLIMITED)
                    val collectorJob = launch {
                        harness.viewModel.uiState
                            .map { it.isSessionActive }
                            .distinctUntilChanged()
                            .collect { isLocked ->
                                observedLockoutStates.trySend(isLocked)
                            }
                    }
                    runCurrent()
                    assertFalse(observedLockoutStates.receive())

                    // 1. Queue promoteToActive while Idle, then start real Session before coroutine runs
                    harness.recordingDao.clearRecordedWrites()
                    harness.viewModel.promoteToActive(SECONDARY_HRM_MAC)
                    val startResult = harness.repository.startSession()
                    assertTrue(startResult is StartSessionResult.Started)
                    runCurrent()

                    assertTrue(harness.recordingDao.writeCalls.isEmpty())
                    assertEquals(ACTIVE_HRM_MAC, harness.hrmDao.getActiveHrm()?.macAddress)
                    assertTrue(harness.viewModel.uiState.value.isSessionActive)
                    assertEquals(EXACT_LOCKOUT_NOTICE, harness.viewModel.uiState.value.errorMessage)
                    assertTrue(observedLockoutStates.receive())

                    // Finish Session and reset back to Idle
                    assertTrue(harness.repository.discardSession())
                    harness.repository.resetToIdle()
                    runCurrent()
                    assertFalse(harness.viewModel.uiState.value.isSessionActive)
                    assertNull(harness.viewModel.uiState.value.errorMessage)
                    assertFalse(observedLockoutStates.receive())

                    // 2. Open Forget dialog & queue confirmForgetDevice while Idle, then start Session before coroutine executes
                    harness.connectAndPrimeTelemetry()
                    runCurrent()
                    harness.viewModel.requestForgetDevice(activeStrap())
                    assertEquals(ACTIVE_HRM_MAC, harness.viewModel.uiState.value.forgetConfirmationTarget?.macAddress)

                    harness.recordingDao.clearRecordedWrites()
                    harness.viewModel.confirmForgetDevice()
                    val secondStart = harness.repository.startSession()
                    assertTrue(secondStart is StartSessionResult.Started)
                    runCurrent()

                    assertTrue(harness.disconnectedMacs.isEmpty())
                    assertTrue(harness.recordingDao.writeCalls.isEmpty())
                    assertEquals(ACTIVE_HRM_MAC, harness.hrmDao.getActiveHrm()?.macAddress)
                    assertTrue(harness.viewModel.uiState.value.isSessionActive)
                    assertEquals(EXACT_LOCKOUT_NOTICE, harness.viewModel.uiState.value.errorMessage)
                    assertTrue(observedLockoutStates.receive())

                    collectorJob.cancel()
                } finally {
                    harness.close()
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun suspendedDisconnect_rechecksSessionActiveBeforeDaoDelete() {
        val testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        try {
            runTest(testDispatcher) {
                val disconnectStarted = CompletableDeferred<Unit>()
                val allowDisconnectFinish = CompletableDeferred<Unit>()
                val fakeDao = FakeRememberedHrmDao(listOf(activeStrap(), secondaryArmband()))
                val recordingDao = RecordingRememberedHrmDao(fakeDao)
                var liveSessionActive = false

                val viewModel = RememberedHrmsViewModel(
                    rememberedHrmDao = recordingDao,
                    isSessionActiveFlow = flow { awaitCancellation() },
                    onDisconnectActiveHrm = {
                        disconnectStarted.complete(Unit)
                        allowDisconnectFinish.await()
                    },
                    isSessionActiveProvider = { liveSessionActive }
                )
                advanceUntilIdle()

                viewModel.requestForgetDevice(activeStrap())
                viewModel.confirmForgetDevice()
                runCurrent()
                disconnectStarted.await()

                // Session becomes active while GATT disconnect was suspended
                liveSessionActive = true
                allowDisconnectFinish.complete(Unit)
                advanceUntilIdle()

                // DAO must NOT be mutated after disconnect resumed into an active Session
                assertTrue(recordingDao.writeCalls.isEmpty())
                assertEquals(ACTIVE_HRM_MAC, fakeDao.getActiveHrm()?.macAddress)
                assertTrue(viewModel.uiState.value.isSessionActive)
                assertEquals(EXACT_LOCKOUT_NOTICE, viewModel.uiState.value.errorMessage)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun endingSession_clearsLockoutNoticeAndPreservesOtherErrors() {
        val testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        try {
            runTest(testDispatcher) {
                val fakeDao = FakeRememberedHrmDao(listOf(activeStrap(), secondaryArmband()))
                val failingNicknameDao = object : RememberedHrmDao by fakeDao {
                    override suspend fun updateNickname(macAddress: String, customNickname: String?): Int {
                        throw IllegalStateException("Nickname update failed")
                    }
                }
                val harness = createHarness(
                    scope = this,
                    overrideDao = failingNicknameDao,
                    backingFakeDao = fakeDao
                )
                try {
                    runCurrent()
                    harness.connectAndStartRecording()
                    runCurrent()

                    // Trigger unrelated nickname error during Recording
                    harness.viewModel.startEditNickname(activeStrap())
                    harness.viewModel.onNicknameDraftChange("Race Strap")
                    harness.viewModel.confirmEditNickname()
                    runCurrent()
                    assertEquals("Nickname update failed", harness.viewModel.uiState.value.errorMessage)

                    // End Session -> unlocked, but unrelated error message is preserved
                    assertTrue(harness.repository.discardSession())
                    runCurrent()
                    assertFalse(harness.viewModel.uiState.value.isSessionActive)
                    assertEquals("Nickname update failed", harness.viewModel.uiState.value.errorMessage)
                } finally {
                    harness.close()
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun createHarness(
        scope: CoroutineScope,
        initialHrms: List<RememberedHrmEntity> = listOf(activeStrap(), secondaryArmband()),
        customIsSessionActiveFlow: Flow<Boolean>? = null,
        overrideDao: RememberedHrmDao? = null,
        backingFakeDao: FakeRememberedHrmDao? = null
    ): LockoutTestHarness {
        val bleTransport = FakeBleTransport()
        val gattClient = HrmGattClient(bleTransport = bleTransport, scope = scope)
        val sampleDao = FakeHeartRateSampleDao()
        val sessionDao = FakeSessionDao(sampleDao = sampleDao)
        val hrmDao = backingFakeDao ?: FakeRememberedHrmDao(initialHrms = initialHrms)
        val recordingDao = RecordingRememberedHrmDao(overrideDao ?: hrmDao)
        val profileDao = FakeUserProfileDao(initialProfile = defaultProfile())
        val clock = FakeSessionClock(
            initialRealtimeMs = 10_000L,
            initialEpochMs = 1_700_000_000_000L
        )
        val durationEngine = MonotonicDurationEngine(clock)
        val sampleBuffer = HeartRateSampleBuffer(
            sampleDao = sampleDao,
            scope = scope
        )
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
            currentYearProvider = { 2026 }
        )
        val disconnectedMacs = mutableListOf<String>()
        val viewModel = RememberedHrmsViewModel(
            rememberedHrmDao = recordingDao,
            isSessionActiveFlow = customIsSessionActiveFlow ?: repository.isSessionActiveFlow,
            onDisconnectActiveHrm = { macAddress ->
                disconnectedMacs.add(macAddress)
                bleTransport.disconnect(macAddress)
            },
            isSessionActiveProvider = { repository.isSessionActive }
        )
        return LockoutTestHarness(
            bleTransport = bleTransport,
            gattClient = gattClient,
            hrmDao = hrmDao,
            recordingDao = recordingDao,
            clock = clock,
            sampleBuffer = sampleBuffer,
            repository = repository,
            viewModel = viewModel,
            disconnectedMacs = disconnectedMacs
        )
    }

    private class LockoutTestHarness(
        val bleTransport: FakeBleTransport,
        val gattClient: HrmGattClient,
        val hrmDao: FakeRememberedHrmDao,
        val recordingDao: RecordingRememberedHrmDao,
        val clock: FakeSessionClock,
        val sampleBuffer: HeartRateSampleBuffer,
        val repository: SessionRepository,
        val viewModel: RememberedHrmsViewModel,
        val disconnectedMacs: MutableList<String>
    ) {
        suspend fun connectAndPrimeTelemetry(bpm: Int = 132) {
            bleTransport.connect(ACTIVE_HRM_MAC)
            bleTransport.emitNotification(
                macAddress = ACTIVE_HRM_MAC,
                value = FakeBleTransport.buildHeartRateMeasurementPacket(
                    bpm = bpm,
                    sensorContactSupported = true,
                    sensorContactDetected = true
                ),
                timestampEpochMs = clock.currentEpochMs()
            )
            repository.uiState.first { it is SessionUiState.Idle && it.canStartSession }
        }

        suspend fun connectAndStartRecording(bpm: Int = 132): String {
            connectAndPrimeTelemetry(bpm)
            val result = repository.startSession()
            check(result is StartSessionResult.Started) { "Expected Started, got $result" }
            return result.sessionId
        }

        suspend fun close() {
            sampleBuffer.discard()
            gattClient.close()
            repository.close()
        }
    }

    private class RecordingRememberedHrmDao(
        private val delegate: RememberedHrmDao
    ) : RememberedHrmDao by delegate {

        val writeCalls = mutableListOf<String>()

        fun clearRecordedWrites() {
            writeCalls.clear()
        }

        override suspend fun promoteToActiveHrm(macAddress: String) {
            writeCalls.add("promote:$macAddress")
            delegate.promoteToActiveHrm(macAddress)
        }

        override suspend fun setActiveHrm(macAddress: String) {
            writeCalls.add("setActive:$macAddress")
            delegate.setActiveHrm(macAddress)
        }

        override suspend fun clearActiveHrms() {
            writeCalls.add("clearActiveHrms")
            delegate.clearActiveHrms()
        }

        override suspend fun deleteHrmByMacAddress(macAddress: String): Int {
            writeCalls.add("delete:$macAddress")
            return delegate.deleteHrmByMacAddress(macAddress)
        }
    }

    private fun defaultProfile(): UserProfileEntity = UserProfileEntity(
        id = UserProfileEntity.SINGLETON_ID,
        birthYear = 1994,
        restingHr = 58,
        customHrmax = null,
        calculationMode = ZoneCalculationMode.KARVONEN,
        updatedAtEpochMs = 1_700_000_000_000L
    )

    private fun activeStrap(): RememberedHrmEntity = RememberedHrmEntity(
        macAddress = ACTIVE_HRM_MAC,
        advertisedName = "COOSPO H808S",
        customNickname = "Chest Strap",
        lastSeenTimestamp = 3_000L,
        batteryLevel = 92,
        isActive = true
    )

    private fun secondaryArmband(): RememberedHrmEntity = RememberedHrmEntity(
        macAddress = SECONDARY_HRM_MAC,
        advertisedName = "Polar OH1",
        customNickname = null,
        lastSeenTimestamp = 2_000L,
        batteryLevel = 84,
        isActive = false
    )

    private fun backupStrap(): RememberedHrmEntity = RememberedHrmEntity(
        macAddress = BACKUP_HRM_MAC,
        advertisedName = "Garmin HRM-Pro",
        customNickname = "Backup Strap",
        lastSeenTimestamp = 1_000L,
        batteryLevel = 70,
        isActive = false
    )

    companion object {
        private const val EXACT_LOCKOUT_NOTICE =
            "Cannot switch sensor during an active session. Stop or complete the session first."
        private const val ACTIVE_HRM_MAC = "D4:36:39:88:21:9B"
        private const val SECONDARY_HRM_MAC = "A0:9E:1A:33:12:44"
        private const val BACKUP_HRM_MAC = "C2:11:85:44:90:4A"
    }
}
