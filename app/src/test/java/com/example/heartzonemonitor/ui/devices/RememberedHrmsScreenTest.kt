package com.example.heartzonemonitor.ui.devices

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.example.heartzonemonitor.data.local.FakeRememberedHrmDao
import com.example.heartzonemonitor.data.local.FakeUserProfileDao
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import com.example.heartzonemonitor.ui.navigation.RootNavHost
import com.example.heartzonemonitor.ui.navigation.RootViewModel
import com.example.heartzonemonitor.ui.onboarding.OnboardingProfileViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RememberedHrmsScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val activeStrap = RememberedHrmEntity(
        macAddress = "D4:36:39:88:21:9B",
        advertisedName = "COOSPO H808S",
        customNickname = "Chest Strap",
        lastSeenTimestamp = 2000L,
        batteryLevel = 95,
        isActive = true
    )

    private val inactiveArmband = RememberedHrmEntity(
        macAddress = "A0:9E:1A:33:12:44",
        advertisedName = "Polar OH1",
        customNickname = null,
        lastSeenTimestamp = 3000L,
        batteryLevel = 80,
        isActive = false
    )

    private val inactiveUnknownBattery = RememberedHrmEntity(
        macAddress = "C2:11:85:44:90:4A",
        advertisedName = "Garmin HRM-Pro",
        customNickname = "Cycling Strap",
        lastSeenTimestamp = 1000L,
        batteryLevel = null,
        isActive = false
    )

    @Test
    fun `renders Active HRM and inactive Remembered HRMs with name, nickname, MAC, battery, and Active badge`() {
        val fakeDao = FakeRememberedHrmDao(listOf(activeStrap, inactiveArmband, inactiveUnknownBattery))
        val viewModel = RememberedHrmsViewModel(rememberedHrmDao = fakeDao)

        composeTestRule.activity.setContent {
            RememberedHrmsScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onScanForNewHrm = {}
            )
        }

        composeTestRule.waitForIdle()

        // Active HRM details
        composeTestRule.onNodeWithText("ACTIVE HRM").assertIsDisplayed()
        composeTestRule.onNodeWithText("Chest Strap").assertIsDisplayed()
        composeTestRule.onNodeWithText("COOSPO H808S").assertIsDisplayed()
        composeTestRule.onNodeWithText("D4:36:39:88:21:9B").assertIsDisplayed()
        composeTestRule.onNodeWithText("Battery: 95%").assertIsDisplayed()

        // Inactive HRMs details
        composeTestRule.onNodeWithText("Polar OH1").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("A0:9E:1A:33:12:44").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Battery: 80%").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithText("Cycling Strap").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Garmin HRM-Pro").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("C2:11:85:44:90:4A").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Battery: Unknown").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithText("Scan for New HRM").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `tapping inactive Remembered HRM card promotes it to Active HRM`() {
        val fakeDao = FakeRememberedHrmDao(listOf(activeStrap, inactiveArmband))
        val viewModel = RememberedHrmsViewModel(rememberedHrmDao = fakeDao)

        composeTestRule.activity.setContent {
            RememberedHrmsScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onScanForNewHrm = {}
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("inactive_hrm_card_${inactiveArmband.macAddress}")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("active_hrm_card_${inactiveArmband.macAddress}")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("inactive_hrm_card_${activeStrap.macAddress}")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `clicking Set as Active button promotes inactive HRM to Active HRM`() {
        val fakeDao = FakeRememberedHrmDao(listOf(activeStrap, inactiveArmband))
        val viewModel = RememberedHrmsViewModel(rememberedHrmDao = fakeDao)

        composeTestRule.activity.setContent {
            RememberedHrmsScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onScanForNewHrm = {}
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("set_active_${inactiveArmband.macAddress}")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("active_hrm_card_${inactiveArmband.macAddress}")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `editing nickname via dialog updates displayed name and clearing resets to advertised name`() {
        val fakeDao = FakeRememberedHrmDao(listOf(activeStrap, inactiveArmband))
        val viewModel = RememberedHrmsViewModel(rememberedHrmDao = fakeDao)

        composeTestRule.activity.setContent {
            RememberedHrmsScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onScanForNewHrm = {}
            )
        }

        composeTestRule.waitForIdle()

        // Edit inactive armband nickname
        composeTestRule.onNodeWithTag("edit_nickname_${inactiveArmband.macAddress}")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Edit HRM Nickname").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nickname_input").performTextInput("Track Armband")
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Track Armband").performScrollTo().assertIsDisplayed()

        // Edit active strap nickname and clear it back to advertised name
        composeTestRule.onNodeWithTag("edit_nickname_${activeStrap.macAddress}")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("nickname_input").performTextClearance()
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Chest Strap").assertDoesNotExist()
        composeTestRule.onNodeWithText("COOSPO H808S").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `forget device confirmation dialog cancel preserves HRM and confirm deletes inactive HRM`() {
        val fakeDao = FakeRememberedHrmDao(listOf(activeStrap, inactiveArmband))
        val viewModel = RememberedHrmsViewModel(rememberedHrmDao = fakeDao)

        composeTestRule.activity.setContent {
            RememberedHrmsScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onScanForNewHrm = {}
            )
        }

        composeTestRule.waitForIdle()

        // Open confirmation and cancel
        composeTestRule.onNodeWithTag("forget_device_${inactiveArmband.macAddress}")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Forget Heart Rate Monitor?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Polar OH1").performScrollTo().assertIsDisplayed()

        // Open confirmation and confirm
        composeTestRule.onNodeWithTag("forget_device_${inactiveArmband.macAddress}")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Forget").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Polar OH1").assertDoesNotExist()
        composeTestRule.onNodeWithText("Active HRM Removed").assertDoesNotExist()
    }

    @Test
    fun `forgetting Active HRM disconnects, deletes, and shows prompt allowing promotion of remaining HRM`() {
        val fakeDao = FakeRememberedHrmDao(listOf(activeStrap, inactiveArmband))
        val disconnectedMacs = mutableListOf<String>()
        val viewModel = RememberedHrmsViewModel(
            rememberedHrmDao = fakeDao,
            onDisconnectActiveHrm = { mac -> disconnectedMacs.add(mac) }
        )
        var scanClicked = false

        composeTestRule.activity.setContent {
            RememberedHrmsScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onScanForNewHrm = { scanClicked = true }
            )
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("forget_device_${activeStrap.macAddress}")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Forget Heart Rate Monitor?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Forget").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(activeStrap.macAddress), disconnectedMacs)
        composeTestRule.onNodeWithText("Chest Strap").assertDoesNotExist()
        composeTestRule.onNodeWithText("Active HRM Removed").performScrollTo().assertIsDisplayed()

        // Promoting remaining HRM dismisses the prompt and establishes new Active HRM
        composeTestRule.onNodeWithTag("set_active_${inactiveArmband.macAddress}")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Active HRM Removed").assertDoesNotExist()
        composeTestRule.onNodeWithTag("active_hrm_card_${inactiveArmband.macAddress}")
            .performScrollTo()
            .assertIsDisplayed()

        composeTestRule.onNodeWithText("Scan for New HRM").performScrollTo().performClick()
        assertTrue(scanClicked)
    }

    @Test
    fun `session active state shows lockout notice and disables Active HRM switching`() {
        val fakeDao = FakeRememberedHrmDao(listOf(activeStrap, inactiveArmband))
        val viewModel = RememberedHrmsViewModel(
            rememberedHrmDao = fakeDao,
            isSessionActiveFlow = MutableStateFlow(true)
        )

        composeTestRule.activity.setContent {
            RememberedHrmsScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onScanForNewHrm = {}
            )
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("LOCKED").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("set_active_${inactiveArmband.macAddress}")
            .performScrollTo()
            .assertIsNotEnabled()
        composeTestRule.onNodeWithTag("forget_device_${activeStrap.macAddress}")
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun `navigating from Settings Manage Heart Rate Monitors opens Remembered HRMs screen`() {
        val profileDao = FakeUserProfileDao().apply {
            emitProfile(
                UserProfileEntity(
                    id = UserProfileEntity.SINGLETON_ID,
                    birthYear = 1990,
                    restingHr = 60,
                    customHrmax = null,
                    calculationMode = ZoneCalculationMode.KARVONEN,
                    updatedAtEpochMs = 1000L
                )
            )
        }
        val hrmDao = FakeRememberedHrmDao(listOf(activeStrap))
        val rootViewModel = RootViewModel(userProfileDao = profileDao)

        composeTestRule.activity.setContent {
            RootNavHost(
                rootViewModel = rootViewModel,
                onboardingViewModelFactory = { OnboardingProfileViewModel(profileDao) },
                rememberedHrmsViewModelFactory = { RememberedHrmsViewModel(hrmDao) }
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("SETTINGS").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Manage Heart Rate Monitors").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Heart Rate Monitors").assertIsDisplayed()
        composeTestRule.onNodeWithText("Chest Strap").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Navigate back").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("SETTINGS & PROFILE").assertIsDisplayed()
    }

    @Test
    fun `empty state and forgetting sole Active HRM prompt user to scan for a new HRM`() {
        val fakeDao = FakeRememberedHrmDao(listOf(activeStrap))
        val viewModel = RememberedHrmsViewModel(rememberedHrmDao = fakeDao)
        var backClicked = false

        composeTestRule.activity.setContent {
            RememberedHrmsScreen(
                viewModel = viewModel,
                onNavigateBack = { backClicked = true },
                onScanForNewHrm = {}
            )
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("forget_device_${activeStrap.macAddress}")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Forget").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Active HRM Removed").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Your Active HRM was disconnected and removed. Scan for a new HRM to designate an Active HRM."
        ).assertIsDisplayed()

        composeTestRule.onNodeWithText("Dismiss").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Active HRM Removed").assertDoesNotExist()
        composeTestRule.onNodeWithText("No Remembered HRMs").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Navigate back").performClick()
        assertTrue(backClicked)
    }
}
