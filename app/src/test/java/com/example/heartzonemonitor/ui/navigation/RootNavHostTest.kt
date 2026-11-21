package com.example.heartzonemonitor.ui.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.example.heartzonemonitor.data.ble.BleDeviceDiscoveryService
import com.example.heartzonemonitor.data.ble.BlePermissionChecker
import com.example.heartzonemonitor.data.ble.BlePermissionStatus
import com.example.heartzonemonitor.data.ble.BleScanResult
import com.example.heartzonemonitor.data.ble.BleUuids
import com.example.heartzonemonitor.data.ble.FakeBleTransport
import com.example.heartzonemonitor.data.local.FakeRememberedHrmDao
import com.example.heartzonemonitor.data.local.FakeUserProfileDao
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import com.example.heartzonemonitor.ui.devices.RememberedHrmsViewModel
import com.example.heartzonemonitor.ui.onboarding.OnboardingPairingViewModel
import com.example.heartzonemonitor.ui.onboarding.OnboardingProfileViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RootNavHostTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var fakeDao: FakeUserProfileDao
    private lateinit var fakeHrmDao: FakeRememberedHrmDao
    private lateinit var discoveryScope: CoroutineScope

    @Before
    fun setUp() {
        fakeDao = FakeUserProfileDao()
        fakeHrmDao = FakeRememberedHrmDao()
        discoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    @After
    fun tearDown() {
        discoveryScope.cancel()
    }

    @Test
    fun `when profile is null, locks start destination to OnboardingGraph`() {
        val rootViewModel = RootViewModel(userProfileDao = fakeDao)
        val onboardingViewModel = OnboardingProfileViewModel(userProfileDao = fakeDao)

        composeTestRule.activity.setContent {
            RootNavHost(
                rootViewModel = rootViewModel,
                onboardingViewModelFactory = { onboardingViewModel }
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Calibrate Your Heart Zones").assertIsDisplayed()
        composeTestRule.onNodeWithText("Birth Year").assertIsDisplayed()
    }

    @Test
    fun `when profile is null, main destinations are structurally unreachable`() {
        val rootViewModel = RootViewModel(userProfileDao = fakeDao)
        val onboardingViewModel = OnboardingProfileViewModel(userProfileDao = fakeDao)
        lateinit var navController: androidx.navigation.NavHostController

        composeTestRule.activity.setContent {
            navController = androidx.navigation.compose.rememberNavController()
            RootNavHost(
                rootViewModel = rootViewModel,
                navController = navController,
                onboardingViewModelFactory = { onboardingViewModel }
            )
        }

        composeTestRule.waitForIdle()
        assertThrows(IllegalArgumentException::class.java) {
            navController.navigate(NavDestinations.LIVE_SESSION)
        }
    }

    @Test
    fun `when profile exists, locks start destination to MainGraph and renders MainShell`() {
        val testProfile = UserProfileEntity(
            id = UserProfileEntity.SINGLETON_ID,
            birthYear = 1992,
            restingHr = 55,
            customHrmax = null,
            calculationMode = ZoneCalculationMode.KARVONEN,
            updatedAtEpochMs = 1000L
        )
        fakeDao.emitProfile(testProfile)

        val rootViewModel = RootViewModel(userProfileDao = fakeDao, rememberedHrmDao = fakeHrmDao)
        val onboardingViewModel = OnboardingProfileViewModel(userProfileDao = fakeDao)

        composeTestRule.activity.setContent {
            RootNavHost(
                rootViewModel = rootViewModel,
                onboardingViewModelFactory = { onboardingViewModel }
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("LIVE").assertIsDisplayed()
        composeTestRule.onNodeWithText("HISTORY").assertIsDisplayed()
        composeTestRule.onNodeWithText("SETTINGS").assertIsDisplayed()
    }

    @Test
    fun `saving a profile transitions to pairing step and Pair Later enters MainShell with no-HRM notice and disabled Start Session`() {
        val rootViewModel = RootViewModel(userProfileDao = fakeDao, rememberedHrmDao = fakeHrmDao)
        val onboardingViewModel = OnboardingProfileViewModel(userProfileDao = fakeDao)

        composeTestRule.activity.setContent {
            RootNavHost(
                rootViewModel = rootViewModel,
                onboardingViewModelFactory = { onboardingViewModel },
                rememberedHrmsViewModelFactory = { RememberedHrmsViewModel(fakeHrmDao) }
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Calibrate Your Heart Zones").assertIsDisplayed()

        // Enter valid birth year
        composeTestRule.onNodeWithText("1988").performTextInput("1990")
        composeTestRule.waitForIdle()

        // Continue to Sensor Pairing (persists profile into Room)
        composeTestRule.onNodeWithText("Continue to Sensor Pairing").performClick()
        composeTestRule.waitForIdle()

        // Arrive at Step 2 Pairing
        composeTestRule.onNodeWithText("Pair Heart Rate Monitor").assertIsDisplayed()

        // Click Pair Later to complete onboarding without a sensor
        composeTestRule.onNodeWithText("Pair Later").performClick()
        composeTestRule.waitForIdle()

        // Main shell is visible with prominent no-HRM notice and disabled Start Session button
        composeTestRule.onNodeWithText("LIVE").assertIsDisplayed()
        composeTestRule.onNodeWithText("HISTORY").assertIsDisplayed()
        composeTestRule.onNodeWithText("SETTINGS").assertIsDisplayed()
        composeTestRule.onNodeWithText("No Heart Rate Monitor paired").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("start_session_button")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()

        // Shortcut leads to Device Management screen
        composeTestRule.onNodeWithText("Pair HRM in Settings").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Heart Rate Monitors").assertIsDisplayed()
    }

    @Test
    fun `pairing a discovered HRM in Step 2 persists Active HRM and enters MainShell without no-HRM notice`() {
        val mac = "D4:36:39:88:21:9B"
        val grantedChecker = object : BlePermissionChecker {
            override fun checkPermissions(): BlePermissionStatus = BlePermissionStatus(
                hasScanPermission = true,
                hasConnectPermission = true,
                hasNotificationPermission = false
            )
        }
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
            permissionChecker = grantedChecker
        )
        val rootViewModel = RootViewModel(userProfileDao = fakeDao, rememberedHrmDao = fakeHrmDao)
        val onboardingViewModel = OnboardingProfileViewModel(userProfileDao = fakeDao)

        composeTestRule.activity.setContent {
            RootNavHost(
                rootViewModel = rootViewModel,
                onboardingViewModelFactory = { onboardingViewModel },
                onboardingPairingViewModelFactory = {
                    OnboardingPairingViewModel(
                        discoveryService = discoveryService,
                        rememberedHrmDao = fakeHrmDao,
                        permissionChecker = grantedChecker,
                        nowEpochMs = { 5_000L }
                    )
                }
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("1988").performTextInput("1990")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Continue to Sensor Pairing").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("COOSPO H808S").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("pair_hrm_button_$mac").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        val active = runBlocking { fakeHrmDao.getActiveHrm() }
        assertEquals(mac, active?.macAddress)
        assertTrue(active?.isActive == true)

        composeTestRule.onNodeWithText("LIVE").assertIsDisplayed()
        composeTestRule.onNodeWithText("No Heart Rate Monitor paired").assertDoesNotExist()
        composeTestRule.onNodeWithTag("start_session_button").assertDoesNotExist()
    }

    @Test
    fun `pairing an Active HRM after Pair Later clears Live Session no-HRM notice reactively`() {
        val testProfile = UserProfileEntity(
            id = UserProfileEntity.SINGLETON_ID,
            birthYear = 1990,
            restingHr = 60,
            customHrmax = null,
            calculationMode = ZoneCalculationMode.KARVONEN,
            updatedAtEpochMs = 1000L
        )
        fakeDao.emitProfile(testProfile)

        val rootViewModel = RootViewModel(userProfileDao = fakeDao, rememberedHrmDao = fakeHrmDao)
        val onboardingViewModel = OnboardingProfileViewModel(userProfileDao = fakeDao)

        composeTestRule.activity.setContent {
            RootNavHost(
                rootViewModel = rootViewModel,
                onboardingViewModelFactory = { onboardingViewModel }
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("No Heart Rate Monitor paired").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("start_session_button").performScrollTo().assertIsNotEnabled()

        runBlocking {
            fakeHrmDao.upsertHrm(
                RememberedHrmEntity(
                    macAddress = "D4:36:39:88:21:9B",
                    advertisedName = "COOSPO H808S",
                    customNickname = null,
                    lastSeenTimestamp = 2000L,
                    batteryLevel = 95,
                    isActive = true
                )
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("No Heart Rate Monitor paired").assertDoesNotExist()
        composeTestRule.onNodeWithTag("start_session_button").assertDoesNotExist()
    }

    @Test
    fun `system back press on onboarding flow triggers exit callback`() {
        var appExited = false
        val rootViewModel = RootViewModel(userProfileDao = fakeDao)
        val onboardingViewModel = OnboardingProfileViewModel(userProfileDao = fakeDao)

        composeTestRule.activity.setContent {
            RootNavHost(
                rootViewModel = rootViewModel,
                onExitApp = { appExited = true },
                onboardingViewModelFactory = { onboardingViewModel }
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(appExited)
    }

    @Test
    fun `openLiveSessionRequestId deep-link pops Devices screen and returns to Live Session tab`() {
        val testProfile = UserProfileEntity(
            id = UserProfileEntity.SINGLETON_ID,
            birthYear = 1990,
            restingHr = 60,
            customHrmax = null,
            calculationMode = ZoneCalculationMode.KARVONEN,
            updatedAtEpochMs = 1000L
        )
        fakeDao.emitProfile(testProfile)
        runBlocking {
            fakeHrmDao.upsertHrm(
                RememberedHrmEntity(
                    macAddress = "D4:36:39:88:21:9B",
                    advertisedName = "COOSPO H808S",
                    customNickname = "Chest Strap",
                    lastSeenTimestamp = 2000L,
                    batteryLevel = 92,
                    isActive = true
                )
            )
        }

        val rootViewModel = RootViewModel(userProfileDao = fakeDao, rememberedHrmDao = fakeHrmDao)
        val onboardingViewModel = OnboardingProfileViewModel(userProfileDao = fakeDao)
        var openLiveSessionRequestId by mutableIntStateOf(0)

        composeTestRule.activity.setContent {
            RootNavHost(
                rootViewModel = rootViewModel,
                onboardingViewModelFactory = { onboardingViewModel },
                rememberedHrmsViewModelFactory = { RememberedHrmsViewModel(fakeHrmDao) },
                openLiveSessionRequestId = openLiveSessionRequestId
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("SETTINGS").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Manage Heart Rate Monitors").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Heart Rate Monitors").assertIsDisplayed()

        composeTestRule.runOnIdle {
            openLiveSessionRequestId += 1
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Heart Rate Monitors").assertDoesNotExist()
        composeTestRule.onNodeWithText("SETTINGS & PROFILE").assertDoesNotExist()
        composeTestRule.onNodeWithTag("live_session_bpm_text").assertIsDisplayed()
    }

    @Test
    fun `openLiveSessionRequestId deep-link does not bypass Onboarding gate when profile is null`() {
        val rootViewModel = RootViewModel(userProfileDao = fakeDao, rememberedHrmDao = fakeHrmDao)
        val onboardingViewModel = OnboardingProfileViewModel(userProfileDao = fakeDao)

        composeTestRule.activity.setContent {
            RootNavHost(
                rootViewModel = rootViewModel,
                onboardingViewModelFactory = { onboardingViewModel },
                openLiveSessionRequestId = 1
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Calibrate Your Heart Zones").assertIsDisplayed()
    }

    @Test
    fun `already handled openLiveSessionRequestId does not re-pop Devices on recreation while a new increment does`() {
        val testProfile = UserProfileEntity(
            id = UserProfileEntity.SINGLETON_ID,
            birthYear = 1990,
            restingHr = 60,
            customHrmax = null,
            calculationMode = ZoneCalculationMode.KARVONEN,
            updatedAtEpochMs = 1000L
        )
        fakeDao.emitProfile(testProfile)
        runBlocking {
            fakeHrmDao.upsertHrm(
                RememberedHrmEntity(
                    macAddress = "D4:36:39:88:21:9B",
                    advertisedName = "COOSPO H808S",
                    customNickname = "Chest Strap",
                    lastSeenTimestamp = 2000L,
                    batteryLevel = 92,
                    isActive = true
                )
            )
        }

        val rootViewModel = RootViewModel(userProfileDao = fakeDao, rememberedHrmDao = fakeHrmDao)
        val onboardingViewModel = OnboardingProfileViewModel(userProfileDao = fakeDao)
        var openLiveSessionRequestId by mutableIntStateOf(0)
        val restorationTester = StateRestorationTester(composeTestRule)

        restorationTester.setContent {
            RootNavHost(
                rootViewModel = rootViewModel,
                onboardingViewModelFactory = { onboardingViewModel },
                rememberedHrmsViewModelFactory = { RememberedHrmsViewModel(fakeHrmDao) },
                openLiveSessionRequestId = openLiveSessionRequestId
            )
        }

        // First deep-link request pops Devices back to Live Session.
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("SETTINGS").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Manage Heart Rate Monitors").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Heart Rate Monitors").assertIsDisplayed()

        composeTestRule.runOnIdle {
            openLiveSessionRequestId += 1
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Heart Rate Monitors").assertDoesNotExist()
        composeTestRule.onNodeWithTag("live_session_bpm_text").assertIsDisplayed()

        // Navigate back to Devices, then simulate Activity recreation with same requestId = 1.
        composeTestRule.onNodeWithText("SETTINGS").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Manage Heart Rate Monitors").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Heart Rate Monitors").assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Heart Rate Monitors").assertIsDisplayed()

        // A new deep-link increment (requestId = 2) pops Devices back to Live Session.
        composeTestRule.runOnIdle {
            openLiveSessionRequestId += 1
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Heart Rate Monitors").assertDoesNotExist()
        composeTestRule.onNodeWithText("SETTINGS & PROFILE").assertDoesNotExist()
        composeTestRule.onNodeWithTag("live_session_bpm_text").assertIsDisplayed()
    }

    @Test
    fun `stale openLiveSessionRequestId during onboarding does not hijack pairing after profile save`() {
        val rootViewModel = RootViewModel(userProfileDao = fakeDao, rememberedHrmDao = fakeHrmDao)
        val onboardingViewModel = OnboardingProfileViewModel(userProfileDao = fakeDao)

        composeTestRule.activity.setContent {
            RootNavHost(
                rootViewModel = rootViewModel,
                onboardingViewModelFactory = { onboardingViewModel },
                openLiveSessionRequestId = 1
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Calibrate Your Heart Zones").assertIsDisplayed()

        composeTestRule.onNodeWithText("1988").performTextInput("1990")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Continue to Sensor Pairing").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Pair Heart Rate Monitor").assertIsDisplayed()
    }
}
