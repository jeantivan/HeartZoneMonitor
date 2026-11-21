package com.example.heartzonemonitor.ui.session

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.app.ActivityOptionsCompat
import com.example.heartzonemonitor.data.ble.ActiveHrmAutoConnector
import com.example.heartzonemonitor.data.ble.AutoConnectState
import com.example.heartzonemonitor.data.ble.BleDeviceDiscoveryService
import com.example.heartzonemonitor.data.ble.BleDiscoveryState
import com.example.heartzonemonitor.data.ble.BleDiscoveryUnavailableReason
import com.example.heartzonemonitor.data.ble.BleOperationResult
import com.example.heartzonemonitor.data.ble.BlePermissionChecker
import com.example.heartzonemonitor.data.ble.BlePermissionStatus
import com.example.heartzonemonitor.data.ble.BleRecordedOperation
import com.example.heartzonemonitor.data.ble.BleScanResult
import com.example.heartzonemonitor.data.ble.BleUuids
import com.example.heartzonemonitor.data.ble.DiscoveredHrm
import com.example.heartzonemonitor.data.ble.FakeBleTransport
import com.example.heartzonemonitor.data.ble.HrmGattClient
import com.example.heartzonemonitor.data.local.FakeHeartRateSampleDao
import com.example.heartzonemonitor.data.local.FakeRememberedHrmDao
import com.example.heartzonemonitor.data.local.FakeSessionDao
import com.example.heartzonemonitor.data.local.FakeUserProfileDao
import com.example.heartzonemonitor.data.local.HeartRateSampleBuffer
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import com.example.heartzonemonitor.data.session.SessionRepository
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp", packageName = "com.example.heartzonemonitor")
class LiveSessionAutoConnectUiTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var uiScope: CoroutineScope
    private lateinit var testScheduler: TestCoroutineScheduler
    private lateinit var autoConnectScope: CoroutineScope

    @Before
    fun setUp() {
        uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        testScheduler = TestCoroutineScheduler()
        autoConnectScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
    }

    @After
    fun tearDown() {
        autoConnectScope.cancel()
        uiScope.cancel()
    }

    @Test
    fun `entering LiveSessionScreen starts auto-connect and hides banner on connect`() {
        val activeHrm = RememberedHrmEntity(
            macAddress = PRIMARY_MAC,
            advertisedName = "COOSPO H808S",
            customNickname = "Chest Strap",
            lastSeenTimestamp = 1_000L,
            batteryLevel = null,
            isActive = true
        )
        val connectGate = CompletableDeferred<BleOperationResult<Unit>>()
        val harness = createHarness(initialHrms = listOf(activeHrm)).apply {
            bleTransport.onConnectAttempt = { _, _ -> connectGate.await() }
        }

        try {
            composeTestRule.activity.setContent {
                LiveSessionScreen(viewModel = harness.viewModel)
            }

            composeTestRule.waitForIdle()
            testScheduler.runCurrent()
            composeTestRule.waitForIdle()

            // Direct non-blocking connection with autoConnect = false and ambient banner visible
            assertTrue(
                harness.bleTransport.recordedOperations.contains(
                    BleRecordedOperation.Connect(PRIMARY_MAC, autoConnect = false)
                )
            )
            composeTestRule.onNodeWithTag(LIVE_SESSION_AUTO_CONNECT_BANNER_TAG).assertIsDisplayed()
            composeTestRule.onNodeWithText("Connecting to Chest Strap...").assertIsDisplayed()
            composeTestRule.onNodeWithTag(LIVE_SESSION_DORMANT_PROMPT_TAG).assertDoesNotExist()

            // Connection completes -> GATT initialized, banner disappears, telemetry streams immediately
            connectGate.complete(BleOperationResult.Success(Unit))
            testScheduler.runCurrent()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag(LIVE_SESSION_AUTO_CONNECT_BANNER_TAG).assertDoesNotExist()
            composeTestRule.onNodeWithTag(LIVE_SESSION_DORMANT_PROMPT_TAG).assertDoesNotExist()
            assertTrue(
                harness.bleTransport.recordedOperations.contains(
                    BleRecordedOperation.WriteDescriptor(
                        macAddress = PRIMARY_MAC,
                        serviceUuid = BleUuids.HEART_RATE_SERVICE_UUID,
                        characteristicUuid = BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID,
                        descriptorUuid = BleUuids.CCCD_DESCRIPTOR_UUID,
                        value = HrmGattClient.CCCD_ENABLE_NOTIFICATION_VALUE
                    )
                )
            )

            runBlocking {
                harness.bleTransport.emitNotification(
                    macAddress = PRIMARY_MAC,
                    value = FakeBleTransport.buildHeartRateMeasurementPacket(bpm = 152),
                    timestampEpochMs = harness.clock.currentEpochMs()
                )
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag(LIVE_SESSION_BPM_TEXT_TAG).assertTextEquals("152")
            composeTestRule.onNodeWithTag(LIVE_SESSION_ZONE_BADGE_TAG).assertTextEquals("BPM | ZONE 3")
        } finally {
            harness.close()
        }
    }

    @Test
    fun `10s timeout shows dormant prompt, opens scanner sheet, connects, and dismisses`() {
        val activeHrm = RememberedHrmEntity(
            macAddress = PRIMARY_MAC,
            advertisedName = "COOSPO H808S",
            customNickname = null,
            lastSeenTimestamp = 1_000L,
            batteryLevel = 80,
            isActive = true
        )
        val nonHrmServiceUuid = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
        val harness = createHarness(initialHrms = listOf(activeHrm)).apply {
            bleTransport.onConnectAttempt = { mac, _ ->
                if (mac == PRIMARY_MAC) {
                    awaitCancellation()
                } else {
                    BleOperationResult.Success(Unit)
                }
            }
            bleTransport.setScanResults(
                listOf(
                    BleScanResult(
                        macAddress = SCANNED_MAC,
                        advertisedName = "Polar H10",
                        rssi = -54,
                        serviceUuids = listOf(BleUuids.HEART_RATE_SERVICE_UUID),
                        timestampEpochMs = 2_000L
                    ),
                    BleScanResult(
                        macAddress = "11:22:33:44:55:66",
                        advertisedName = "Non-HRM Speaker",
                        rssi = -45,
                        serviceUuids = listOf(nonHrmServiceUuid),
                        timestampEpochMs = 2_000L
                    )
                )
            )
            bleTransport.stubCharacteristicRead(
                characteristicUuid = BleUuids.BATTERY_LEVEL_CHAR_UUID,
                serviceUuid = BleUuids.BATTERY_SERVICE_UUID,
                result = BleOperationResult.Success(byteArrayOf(96.toByte()))
            )
        }

        try {
            composeTestRule.activity.setContent {
                LiveSessionScreen(viewModel = harness.viewModel)
            }

            composeTestRule.waitForIdle()
            testScheduler.runCurrent()
            composeTestRule.waitForIdle()

            // Ambient banner shown before 10s timeout
            composeTestRule.onNodeWithText("Connecting to COOSPO H808S...").assertIsDisplayed()

            // Still connecting at 9,999ms
            testScheduler.advanceTimeBy(9_999L)
            testScheduler.runCurrent()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Connecting to COOSPO H808S...").assertIsDisplayed()
            composeTestRule.onNodeWithTag(LIVE_SESSION_DORMANT_PROMPT_TAG).assertDoesNotExist()

            // Exactly 10,000ms -> transitions to inline dormant strap prompt and disconnects pending attempt
            testScheduler.advanceTimeBy(1L)
            testScheduler.runCurrent()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag(LIVE_SESSION_AUTO_CONNECT_BANNER_TAG).assertDoesNotExist()
            composeTestRule.onNodeWithTag(LIVE_SESSION_DORMANT_PROMPT_TAG).assertIsDisplayed()
            composeTestRule.onNodeWithText(DORMANT_STRAP_PROMPT_TEXT).assertIsDisplayed()
            assertTrue(
                harness.bleTransport.recordedOperations.contains(
                    BleRecordedOperation.Disconnect(PRIMARY_MAC)
                )
            )

            // Tapping dormant prompt opens BLE scanner bottom sheet and scans for 0x180D HRMs only
            composeTestRule.onNodeWithTag(LIVE_SESSION_DORMANT_PROMPT_TAG).performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag(BLE_SCANNER_BOTTOM_SHEET_TAG).assertIsDisplayed()
            assertTrue(harness.bleTransport.isScanning)
            assertTrue(
                harness.bleTransport.recordedOperations.contains(
                    BleRecordedOperation.ScanStarted(BleUuids.HEART_RATE_SERVICE_UUID)
                )
            )
            composeTestRule.onNodeWithText("Polar H10").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithText("MAC: BB:CC:DD:...:02").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithContentDescription("Signal strength 4 of 4 bars, -54 dBm")
                .performScrollTo()
                .assertIsDisplayed()
            composeTestRule.onNodeWithText("Non-HRM Speaker").assertDoesNotExist()

            // Tapping discovered HRM stops scan, connects, initializes GATT, promotes Active HRM, and dismisses sheet.
            // Use SemanticsActions.OnClick inside Material 3 ModalBottomSheet due to Robolectric secondary-window touch offset.
            composeTestRule.onNodeWithTag("${BLE_SCANNER_HRM_CARD_TAG_PREFIX}$SCANNED_MAC")
                .performScrollTo()
                .performSemanticsAction(SemanticsActions.OnClick)
            composeTestRule.waitForIdle()
            testScheduler.runCurrent()
            composeTestRule.waitForIdle()

            assertFalse(harness.bleTransport.isScanning)
            composeTestRule.onNodeWithTag(BLE_SCANNER_BOTTOM_SHEET_TAG).assertDoesNotExist()
            composeTestRule.onNodeWithTag(LIVE_SESSION_DORMANT_PROMPT_TAG).assertDoesNotExist()

            val ops = harness.bleTransport.recordedOperations
            val scanStopIndex = ops.indexOfLast { it == BleRecordedOperation.ScanStopped }
            val connectNewIndex = ops.indexOfLast {
                it == BleRecordedOperation.Connect(SCANNED_MAC, autoConnect = false)
            }
            assertTrue("Expected ScanStopped before Connect in $ops", scanStopIndex in 0 until connectNewIndex)
            assertTrue(
                ops.contains(
                    BleRecordedOperation.WriteDescriptor(
                        macAddress = SCANNED_MAC,
                        serviceUuid = BleUuids.HEART_RATE_SERVICE_UUID,
                        characteristicUuid = BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID,
                        descriptorUuid = BleUuids.CCCD_DESCRIPTOR_UUID,
                        value = HrmGattClient.CCCD_ENABLE_NOTIFICATION_VALUE
                    )
                )
            )

            val promoted = runBlocking { harness.hrmDao.getActiveHrm() }
            assertEquals(SCANNED_MAC, promoted?.macAddress)
            assertEquals("Polar H10", promoted?.advertisedName)
            assertEquals(96, promoted?.batteryLevel)
            assertTrue(promoted?.isActive == true)

            // Telemetry from the newly connected HRM flows into LiveSessionScreen immediately
            runBlocking {
                harness.bleTransport.emitNotification(
                    macAddress = SCANNED_MAC,
                    value = FakeBleTransport.buildHeartRateMeasurementPacket(bpm = 166),
                    timestampEpochMs = harness.clock.currentEpochMs()
                )
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag(LIVE_SESSION_BPM_TEXT_TAG).assertTextEquals("166")
        } finally {
            harness.close()
        }
    }

    @Test
    fun `dismissing BLE scanner bottom sheet stops active BLE scan cleanly`() {
        val activeHrm = RememberedHrmEntity(
            macAddress = PRIMARY_MAC,
            advertisedName = "COOSPO H808S",
            customNickname = null,
            lastSeenTimestamp = 1_000L,
            isActive = true
        )
        val harness = createHarness(initialHrms = listOf(activeHrm)).apply {
            bleTransport.onConnectAttempt = { _, _ -> awaitCancellation() }
        }

        try {
            composeTestRule.activity.setContent {
                LiveSessionScreen(viewModel = harness.viewModel)
            }

            composeTestRule.waitForIdle()
            testScheduler.advanceTimeBy(10_000L)
            testScheduler.runCurrent()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag(LIVE_SESSION_DORMANT_PROMPT_TAG).performClick()
            composeTestRule.waitForIdle()

            assertTrue(harness.bleTransport.isScanning)
            composeTestRule.onNodeWithTag(BLE_SCANNER_BOTTOM_SHEET_TAG).assertIsDisplayed()

            composeTestRule.onNodeWithTag(BLE_SCANNER_CLOSE_BUTTON_TAG)
                .performSemanticsAction(SemanticsActions.OnClick)
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag(BLE_SCANNER_BOTTOM_SHEET_TAG).assertDoesNotExist()
            assertFalse(harness.bleTransport.isScanning)
            assertTrue(
                harness.bleTransport.recordedOperations.contains(BleRecordedOperation.ScanStopped)
            )
            // Dormant prompt remains visible so user can re-open scanner if needed
            composeTestRule.onNodeWithTag(LIVE_SESSION_DORMANT_PROMPT_TAG).assertIsDisplayed()
        } finally {
            harness.close()
        }
    }

    @Test
    fun `stateless BleScannerSheetContent handles clicks across states`() {
        val device = DiscoveredHrm(
            macAddress = SCANNED_MAC,
            advertisedName = "Polar H10",
            rssi = -58,
            lastSeenEpochMs = 1_000L
        )
        var closeCount = 0
        var rescanCount = 0
        var permissionCount = 0
        var dismissErrorCount = 0
        var selectedDevice: DiscoveredHrm? = null

        composeTestRule.activity.setContent {
            BleScannerSheetContent(
                uiState = BleScannerSheetUiState(
                    isOpen = true,
                    permissionStatus = BlePermissionStatus(
                        hasScanPermission = false,
                        hasConnectPermission = false,
                        hasNotificationPermission = false
                    ),
                    discoveryState = BleDiscoveryState.Unavailable(
                        BleDiscoveryUnavailableReason.BLUETOOTH_DISABLED
                    ),
                    discoveredHrms = listOf(device),
                    errorMessage = "Could not connect to Polar H10: GATT error 133"
                ),
                actions = BleScannerSheetActions(
                    onDismiss = { closeCount++ },
                    onRequestPermissions = { permissionCount++ },
                    onRescan = { rescanCount++ },
                    onSelectHrm = { selectedDevice = it },
                    onDismissError = { dismissErrorCount++ }
                )
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(BLE_SCANNER_CLOSE_BUTTON_TAG).performScrollTo().performClick()
        composeTestRule.onNodeWithTag(BLE_SCANNER_GRANT_PERMISSIONS_TAG).performScrollTo().performClick()
        composeTestRule.onNodeWithTag(BLE_SCANNER_RESCAN_BUTTON_TAG).performScrollTo().performClick()
        composeTestRule.onNodeWithText("Dismiss").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("${BLE_SCANNER_CONNECT_BUTTON_TAG_PREFIX}$SCANNED_MAC")
            .performScrollTo()
            .performClick()

        assertEquals(1, closeCount)
        assertEquals(1, permissionCount)
        assertEquals(1, rescanCount)
        assertEquals(1, dismissErrorCount)
        assertEquals(device, selectedDevice)
    }

    @Test
    fun `reconnecting existing Remembered HRM via scanner preserves customNickname`() {
        val existingHrm = RememberedHrmEntity(
            macAddress = PRIMARY_MAC,
            advertisedName = "COOSPO H808S",
            customNickname = "Race Strap",
            lastSeenTimestamp = 1_000L,
            batteryLevel = 70,
            isActive = true
        )
        var failFirstAttempt = true
        val harness = createHarness(initialHrms = listOf(existingHrm)).apply {
            bleTransport.onConnectAttempt = { _, _ ->
                if (failFirstAttempt) {
                    failFirstAttempt = false
                    awaitCancellation()
                } else {
                    BleOperationResult.Success(Unit)
                }
            }
            bleTransport.setScanResults(
                listOf(
                    BleScanResult(
                        macAddress = PRIMARY_MAC,
                        advertisedName = "COOSPO H808S",
                        rssi = -56,
                        serviceUuids = listOf(BleUuids.HEART_RATE_SERVICE_UUID),
                        timestampEpochMs = 4_000L
                    )
                )
            )
        }

        try {
            composeTestRule.activity.setContent {
                LiveSessionScreen(viewModel = harness.viewModel)
            }

            composeTestRule.waitForIdle()
            testScheduler.advanceTimeBy(10_000L)
            testScheduler.runCurrent()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag(LIVE_SESSION_DORMANT_PROMPT_TAG).performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("${BLE_SCANNER_HRM_CARD_TAG_PREFIX}$PRIMARY_MAC")
                .performScrollTo()
                .performSemanticsAction(SemanticsActions.OnClick)
            composeTestRule.waitForIdle()
            testScheduler.runCurrent()
            composeTestRule.waitForIdle()

            val persisted = runBlocking { harness.hrmDao.getActiveHrm() }
            assertEquals(PRIMARY_MAC, persisted?.macAddress)
            assertEquals("Race Strap", persisted?.customNickname)
            assertTrue(persisted?.isActive == true)
            assertEquals(
                AutoConnectState.Connected(PRIMARY_MAC, "Race Strap"),
                harness.autoConnector.state.value
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `scanner sheet requests Android 12 BLE permissions and scans once granted`() {
        val activeHrm = RememberedHrmEntity(
            macAddress = PRIMARY_MAC,
            advertisedName = "COOSPO H808S",
            customNickname = null,
            lastSeenTimestamp = 1_000L,
            isActive = true
        )
        val checker = FakeBlePermissionChecker(
            BlePermissionStatus(
                hasScanPermission = false,
                hasConnectPermission = false,
                hasNotificationPermission = false
            )
        )
        val harness = createHarness(
            initialHrms = listOf(activeHrm),
            permissionChecker = checker
        ).apply {
            bleTransport.setScanResults(
                listOf(
                    BleScanResult(
                        macAddress = SCANNED_MAC,
                        advertisedName = "Wahoo TICKR",
                        rssi = -62,
                        serviceUuids = listOf(BleUuids.HEART_RATE_SERVICE_UUID),
                        timestampEpochMs = 3_000L
                    )
                )
            )
        }

        var requestedPermissions: List<String> = emptyList()
        val registryOwner = createFakeRegistryOwner { input ->
            requestedPermissions = input.toList()
            checker.status = BlePermissionStatus(
                hasScanPermission = true,
                hasConnectPermission = true,
                hasNotificationPermission = false
            )
            input.associateWith { true }
        }

        try {
            composeTestRule.activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                    LiveSessionScreen(viewModel = harness.viewModel)
                }
            }

            composeTestRule.waitForIdle()
            testScheduler.runCurrent()
            composeTestRule.waitForIdle()

            // Missing connect permission transitions directly to DormantPrompt
            composeTestRule.onNodeWithTag(LIVE_SESSION_DORMANT_PROMPT_TAG).assertIsDisplayed()

            // Tapping dormant prompt opens scanner sheet and requests BLUETOOTH_SCAN + BLUETOOTH_CONNECT only
            composeTestRule.onNodeWithTag(LIVE_SESSION_DORMANT_PROMPT_TAG).performClick()
            composeTestRule.waitForIdle()

            assertEquals(
                listOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                ),
                requestedPermissions
            )
            assertTrue(harness.bleTransport.isScanning)
            composeTestRule.onNodeWithText("Wahoo TICKR").performScrollTo().assertIsDisplayed()
        } finally {
            harness.close()
        }
    }

    @Test
    fun `banner hides for Idle and Connected and preserves Ticket 17 invariants`() {
        val states = listOf(
            AutoConnectState.Idle,
            AutoConnectState.Connected(PRIMARY_MAC, "Chest Strap"),
            AutoConnectState.Connecting(PRIMARY_MAC, "Chest Strap"),
            AutoConnectState.DormantPrompt(PRIMARY_MAC, "Chest Strap")
        )
        val forbiddenFragments = listOf(
            "%",
            "hrmax",
            "50-60",
            "60-70",
            "70-80",
            "80-90",
            "90-100",
            "heart",
            "pulse"
        )

        states.forEach { autoState ->
            composeTestRule.activity.setContent {
                LiveSessionContent(
                    uiState = LiveSessionUiState(
                        autoConnectState = autoState
                    )
                )
            }
            composeTestRule.waitForIdle()

            when (autoState) {
                AutoConnectState.Idle,
                is AutoConnectState.Connected -> {
                    composeTestRule.onNodeWithTag(LIVE_SESSION_AUTO_CONNECT_BANNER_TAG).assertDoesNotExist()
                    composeTestRule.onNodeWithTag(LIVE_SESSION_DORMANT_PROMPT_TAG).assertDoesNotExist()
                }
                is AutoConnectState.Connecting -> {
                    composeTestRule.onNodeWithTag(LIVE_SESSION_AUTO_CONNECT_BANNER_TAG).assertIsDisplayed()
                    composeTestRule.onNodeWithTag(LIVE_SESSION_DORMANT_PROMPT_TAG).assertDoesNotExist()
                }
                is AutoConnectState.DormantPrompt -> {
                    composeTestRule.onNodeWithTag(LIVE_SESSION_AUTO_CONNECT_BANNER_TAG).assertDoesNotExist()
                    composeTestRule.onNodeWithTag(LIVE_SESSION_DORMANT_PROMPT_TAG).assertIsDisplayed()
                }
            }

            val root = composeTestRule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
            collectSemanticsTree(root).forEach { node ->
                val texts = node.config.getOrNull(SemanticsProperties.Text)
                    ?.joinToString(" ") { it.text }
                    .orEmpty()
                val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription)
                    ?.joinToString(" ")
                    .orEmpty()
                val combined = "$texts $descriptions".lowercase()
                forbiddenFragments.forEach { fragment ->
                    assertFalse(
                        "Found forbidden fragment '$fragment' for state $autoState in '$combined'",
                        combined.contains(fragment)
                    )
                }
                assertNotEquals(Role.Image, node.config.getOrNull(SemanticsProperties.Role))
            }
        }
    }

    private fun createHarness(
        initialHrms: List<RememberedHrmEntity>,
        permissionChecker: BlePermissionChecker = FakeBlePermissionChecker(
            BlePermissionStatus(
                hasScanPermission = true,
                hasConnectPermission = true,
                hasNotificationPermission = false
            )
        )
    ): UiTestHarness {
        val bleTransport = FakeBleTransport()
        val hrmDao = FakeRememberedHrmDao(initialHrms)
        val profileDao = FakeUserProfileDao(
            initialProfile = UserProfileEntity(
                birthYear = 1996,
                restingHr = null,
                customHrmax = 200,
                calculationMode = ZoneCalculationMode.PERCENT_HRMAX,
                updatedAtEpochMs = 1_000L
            )
        )
        val sampleDao = FakeHeartRateSampleDao()
        val sessionDao = FakeSessionDao(sampleDao = sampleDao)
        val clock = FakeSessionClock(initialRealtimeMs = 10_000L, initialEpochMs = 1_700_000_000_000L)
        val sampleBuffer = HeartRateSampleBuffer(sampleDao = sampleDao, scope = uiScope)
        val gattClient = HrmGattClient(
            bleTransport = bleTransport,
            scope = uiScope,
            onBatteryLevelUpdated = { mac, level ->
                hrmDao.updateBatteryLevel(mac, level)
            }
        )
        val repository = SessionRepository(
            bleTransport = bleTransport,
            rememberedHrmDao = hrmDao,
            userProfileDao = profileDao,
            sessionDao = sessionDao,
            sampleDao = sampleDao,
            scope = uiScope,
            hrmGattClient = gattClient,
            clock = clock,
            durationEngine = MonotonicDurationEngine(clock),
            sampleBuffer = sampleBuffer,
            currentYearProvider = { 2026 }
        )
        val autoConnector = ActiveHrmAutoConnector(
            bleTransport = bleTransport,
            hrmGattClient = repository.hrmGattClient,
            rememberedHrmDao = hrmDao,
            scope = autoConnectScope,
            isSessionActiveProvider = { repository.isSessionActive },
            sessionConnectionStateProvider = { repository.connectionState.value },
            permissionChecker = permissionChecker
        )
        val discoveryService = BleDeviceDiscoveryService(
            bleTransport = bleTransport,
            scope = uiScope,
            permissionChecker = permissionChecker
        )
        val viewModel = LiveSessionViewModel(
            sessionRepository = repository,
            autoConnector = autoConnector,
            discoveryService = discoveryService,
            rememberedHrmDao = hrmDao,
            permissionChecker = permissionChecker,
            hrmGattClient = repository.hrmGattClient,
            nowEpochMs = { 5_000L }
        )
        return UiTestHarness(
            bleTransport = bleTransport,
            hrmDao = hrmDao,
            clock = clock,
            sampleBuffer = sampleBuffer,
            gattClient = gattClient,
            repository = repository,
            autoConnector = autoConnector,
            viewModel = viewModel
        )
    }

    private class UiTestHarness(
        val bleTransport: FakeBleTransport,
        val hrmDao: FakeRememberedHrmDao,
        val clock: FakeSessionClock,
        val sampleBuffer: HeartRateSampleBuffer,
        val gattClient: HrmGattClient,
        val repository: SessionRepository,
        val autoConnector: ActiveHrmAutoConnector,
        val viewModel: LiveSessionViewModel
    ) {
        fun close() {
            autoConnector.close()
            runBlocking { sampleBuffer.discard() }
            gattClient.close()
            repository.close()
        }
    }

    private fun createFakeRegistryOwner(
        onRequest: (Array<String>) -> Map<String, Boolean>
    ): ActivityResultRegistryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?
            ) {
                @Suppress("UNCHECKED_CAST")
                val permissions = input as Array<String>
                val result = onRequest(permissions)
                dispatchResult(requestCode, result)
            }
        }
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

    private class FakeBlePermissionChecker(
        var status: BlePermissionStatus
    ) : BlePermissionChecker {
        override fun checkPermissions(): BlePermissionStatus = status
    }

    companion object {
        private const val PRIMARY_MAC = "AA:BB:CC:DD:EE:01"
        private const val SCANNED_MAC = "BB:CC:DD:EE:FF:02"
    }
}
