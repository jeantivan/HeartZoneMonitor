package com.example.heartzonemonitor.ui.onboarding

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import com.example.heartzonemonitor.data.ble.BleDeviceDiscoveryService
import com.example.heartzonemonitor.data.ble.BleDiscoveryState
import com.example.heartzonemonitor.data.ble.BleOperationResult
import com.example.heartzonemonitor.data.ble.BlePermissionChecker
import com.example.heartzonemonitor.data.ble.BlePermissionStatus
import com.example.heartzonemonitor.data.ble.BleRecordedOperation
import com.example.heartzonemonitor.data.ble.BleScanResult
import com.example.heartzonemonitor.data.ble.BleUuids
import com.example.heartzonemonitor.data.ble.DiscoveredHrm
import com.example.heartzonemonitor.data.ble.FakeBleTransport
import com.example.heartzonemonitor.data.local.FakeRememberedHrmDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], packageName = "com.example.heartzonemonitor")
class OnboardingPairingScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var discoveryScope: CoroutineScope

    @Before
    fun setUp() {
        discoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    @After
    fun tearDown() {
        discoveryScope.cancel()
    }

    @Test
    fun `stateless content renders step 2 header, permission status, discovered HRMs with MAC snippet and RSSI bars`() {
        val hrmStrong = DiscoveredHrm(
            macAddress = "D4:36:39:88:21:9B",
            advertisedName = "COOSPO H808S",
            rssi = -54,
            lastSeenEpochMs = 1_000L
        )
        val hrmMedium = DiscoveredHrm(
            macAddress = "A0:9E:1A:33:12:11",
            advertisedName = "Polar H10",
            rssi = -78,
            lastSeenEpochMs = 1_100L
        )
        val hrmWeak = DiscoveredHrm(
            macAddress = "C2:11:85:44:90:4A",
            advertisedName = "Garmin HRM-Pro",
            rssi = -86,
            lastSeenEpochMs = 1_200L
        )
        val devices = listOf(hrmStrong, hrmMedium, hrmWeak)
        val state = OnboardingPairingUiState(
            permissionStatus = BlePermissionStatus(
                hasScanPermission = true,
                hasConnectPermission = true,
                hasNotificationPermission = false
            ),
            discoveryState = BleDiscoveryState.Scanning(devices),
            discoveredHrms = devices
        )

        var pairedDevice: DiscoveredHrm? = null
        var rescanClicked = false
        var pairLaterClicked = false

        composeTestRule.activity.setContent {
            OnboardingPairingContent(
                uiState = state,
                actions = OnboardingPairingActions(
                    onRescan = { rescanClicked = true },
                    onPairDevice = { pairedDevice = it },
                    onPairLater = { pairLaterClicked = true }
                )
            )
        }

        composeTestRule.onNodeWithText("Step 2 of 2: Heart Rate Monitor").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pair Heart Rate Monitor").assertIsDisplayed()
        composeTestRule.onNodeWithText("BLE SCAN ACTIVE").assertIsDisplayed()
        composeTestRule.onNodeWithText("DISCOVERED HRMS (3)").performScrollTo().assertIsDisplayed()

        // Recommended HRM (4 bars)
        composeTestRule.onNodeWithText("COOSPO H808S").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("MAC: D4:36:39:...:9B").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("-54 dBm").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Signal strength 4 of 4 bars, -54 dBm")
            .performScrollTo()
            .assertIsDisplayed()

        // Medium signal HRM (2 bars)
        composeTestRule.onNodeWithText("Polar H10").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("MAC: A0:9E:1A:...:11").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Signal strength 2 of 4 bars, -78 dBm")
            .performScrollTo()
            .assertIsDisplayed()

        // Weak signal HRM (1 bar)
        composeTestRule.onNodeWithText("Garmin HRM-Pro").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("MAC: C2:11:85:...:4A").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Signal strength 1 of 4 bars, -86 dBm")
            .performScrollTo()
            .assertIsDisplayed()

        composeTestRule.onNodeWithTag("rescan_button").performScrollTo().performClick()
        assertTrue(rescanClicked)

        composeTestRule.onNodeWithTag("pair_hrm_button_${hrmStrong.macAddress}")
            .performScrollTo()
            .performClick()
        assertEquals(hrmStrong, pairedDevice)

        composeTestRule.onNodeWithTag("pair_later_button").performClick()
        assertTrue(pairLaterClicked)
    }

    @Test
    fun `stateless content renders connecting state and inline error banner`() {
        val hrm = DiscoveredHrm(
            macAddress = "D4:36:39:88:21:9B",
            advertisedName = "COOSPO H808S",
            rssi = -54,
            lastSeenEpochMs = 1_000L
        )
        var dismissed = false

        composeTestRule.activity.setContent {
            OnboardingPairingContent(
                uiState = OnboardingPairingUiState(
                    permissionStatus = BlePermissionStatus(true, true, false),
                    discoveryState = BleDiscoveryState.Stopped(listOf(hrm)),
                    discoveredHrms = listOf(hrm),
                    pairingMacAddress = hrm.macAddress,
                    errorMessage = "Could not connect to COOSPO H808S: GATT error 133"
                ),
                actions = OnboardingPairingActions(
                    onDismissError = { dismissed = true }
                )
            )
        }

        composeTestRule.onNodeWithText("PAIRING FAILED").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Could not connect to COOSPO H808S: GATT error 133")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("dismiss_pairing_error_button").performScrollTo().performClick()
        assertTrue(dismissed)

        composeTestRule.onNodeWithText("Connecting...").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("pair_hrm_button_${hrm.macAddress}")
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun `stateful screen requests BLE permissions without notifications and starts 0x180D scan once granted`() {
        val checker = FakeBlePermissionChecker(
            BlePermissionStatus(
                hasScanPermission = false,
                hasConnectPermission = false,
                hasNotificationPermission = false
            )
        )
        val fakeTransport = FakeBleTransport().apply {
            setScanResults(
                listOf(
                    BleScanResult(
                        macAddress = "D4:36:39:88:21:9B",
                        advertisedName = "COOSPO H808S",
                        rssi = -52,
                        serviceUuids = listOf(BleUuids.HEART_RATE_SERVICE_UUID),
                        timestampEpochMs = 1_000L
                    )
                )
            )
        }
        val discoveryService = BleDeviceDiscoveryService(
            bleTransport = fakeTransport,
            scope = discoveryScope,
            permissionChecker = checker
        )
        val dao = FakeRememberedHrmDao()
        val viewModel = OnboardingPairingViewModel(
            discoveryService = discoveryService,
            rememberedHrmDao = dao,
            permissionChecker = checker,
            nowEpochMs = { 5_000L }
        )

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

        composeTestRule.activity.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                OnboardingPairingScreen(
                    viewModel = viewModel,
                    onPairingComplete = {},
                    onPairLater = {}
                )
            }
        }

        composeTestRule.waitForIdle()

        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ),
            requestedPermissions
        )
        assertTrue(fakeTransport.isScanning)
        assertTrue(
            fakeTransport.recordedOperations.contains(
                BleRecordedOperation.ScanStarted(BleUuids.HEART_RATE_SERVICE_UUID)
            )
        )
        composeTestRule.onNodeWithText("COOSPO H808S").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("MAC: D4:36:39:...:9B").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `tapping discovered HRM stops scan, connects, persists Active HRM, and triggers completion`() {
        val mac = "D4:36:39:88:21:9B"
        val checker = FakeBlePermissionChecker(
            BlePermissionStatus(true, true, false)
        )
        val fakeTransport = FakeBleTransport().apply {
            setScanResults(
                listOf(
                    BleScanResult(
                        macAddress = mac,
                        advertisedName = "COOSPO H808S",
                        rssi = -54,
                        serviceUuids = listOf(BleUuids.HEART_RATE_SERVICE_UUID),
                        timestampEpochMs = 1_000L
                    )
                )
            )
        }
        val discoveryService = BleDeviceDiscoveryService(
            bleTransport = fakeTransport,
            scope = discoveryScope,
            permissionChecker = checker
        )
        val dao = FakeRememberedHrmDao()
        val viewModel = OnboardingPairingViewModel(
            discoveryService = discoveryService,
            rememberedHrmDao = dao,
            permissionChecker = checker,
            nowEpochMs = { 9_999L }
        )
        var pairingCompleted = false

        composeTestRule.activity.setContent {
            OnboardingPairingScreen(
                viewModel = viewModel,
                onPairingComplete = { pairingCompleted = true },
                onPairLater = {}
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("discovered_hrm_card_$mac")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        assertTrue(pairingCompleted)
        assertFalse(fakeTransport.isScanning)
        val ops = fakeTransport.recordedOperations
        val stopIndex = ops.indexOf(BleRecordedOperation.ScanStopped)
        val connectIndex = ops.indexOf(BleRecordedOperation.Connect(mac, autoConnect = false))
        assertTrue("Expected ScanStopped before Connect in $ops", stopIndex in 0 until connectIndex)

        val persisted = runBlocking { dao.getActiveHrm() }
        assertEquals(mac, persisted?.macAddress)
        assertEquals("COOSPO H808S", persisted?.advertisedName)
        assertEquals(9_999L, persisted?.lastSeenTimestamp)
        assertTrue(persisted?.isActive == true)
    }

    @Test
    fun `failed pairing shows inline error without persisting HRM or completing onboarding`() {
        val mac = "D4:36:39:88:21:9B"
        val checker = FakeBlePermissionChecker(
            BlePermissionStatus(true, true, false)
        )
        val fakeTransport = FakeBleTransport().apply {
            setScanResults(
                listOf(
                    BleScanResult(
                        macAddress = mac,
                        advertisedName = "COOSPO H808S",
                        rssi = -54,
                        serviceUuids = listOf(BleUuids.HEART_RATE_SERVICE_UUID),
                        timestampEpochMs = 1_000L
                    )
                )
            )
            setConnectResult(
                BleOperationResult.Failure(
                    status = BleOperationResult.STATUS_TIMEOUT,
                    message = "Connection timed out"
                ),
                macAddress = mac
            )
        }
        val discoveryService = BleDeviceDiscoveryService(
            bleTransport = fakeTransport,
            scope = discoveryScope,
            permissionChecker = checker
        )
        val dao = FakeRememberedHrmDao()
        val viewModel = OnboardingPairingViewModel(
            discoveryService = discoveryService,
            rememberedHrmDao = dao,
            permissionChecker = checker
        )
        var pairingCompleted = false

        composeTestRule.activity.setContent {
            OnboardingPairingScreen(
                viewModel = viewModel,
                onPairingComplete = { pairingCompleted = true },
                onPairLater = {}
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("pair_hrm_button_$mac")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        assertFalse(pairingCompleted)
        composeTestRule.onNodeWithTag("pairing_error_banner").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("PAIRING FAILED").performScrollTo().assertIsDisplayed()
        assertTrue(runBlocking { dao.getAllHrms() }.isEmpty())
    }

    @Test
    fun `Pair Later stops active scan without persisting HRM and invokes callback`() {
        val checker = FakeBlePermissionChecker(
            BlePermissionStatus(true, true, false)
        )
        val fakeTransport = FakeBleTransport()
        val discoveryService = BleDeviceDiscoveryService(
            bleTransport = fakeTransport,
            scope = discoveryScope,
            permissionChecker = checker
        )
        val dao = FakeRememberedHrmDao()
        val viewModel = OnboardingPairingViewModel(
            discoveryService = discoveryService,
            rememberedHrmDao = dao,
            permissionChecker = checker
        )
        var pairLaterTriggered = false

        composeTestRule.activity.setContent {
            OnboardingPairingScreen(
                viewModel = viewModel,
                onPairingComplete = {},
                onPairLater = { pairLaterTriggered = true }
            )
        }

        composeTestRule.waitForIdle()
        assertTrue(fakeTransport.isScanning)

        composeTestRule.onNodeWithTag("pair_later_button").performClick()
        composeTestRule.waitForIdle()

        assertTrue(pairLaterTriggered)
        assertFalse(fakeTransport.isScanning)
        assertTrue(runBlocking { dao.getAllHrms() }.isEmpty())
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

    private class FakeBlePermissionChecker(
        var status: BlePermissionStatus
    ) : BlePermissionChecker {
        override fun checkPermissions(): BlePermissionStatus = status
    }
}
