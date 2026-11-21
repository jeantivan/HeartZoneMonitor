package com.example.heartzonemonitor.ui.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainShellTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val testProfile = UserProfileEntity(
        id = UserProfileEntity.SINGLETON_ID,
        birthYear = 1990,
        restingHr = 60,
        customHrmax = null,
        calculationMode = ZoneCalculationMode.KARVONEN,
        updatedAtEpochMs = 1000L
    )

    private val activeHrm = RememberedHrmEntity(
        macAddress = "D4:36:39:88:21:9B",
        advertisedName = "COOSPO H808S",
        customNickname = "Chest Strap",
        lastSeenTimestamp = 2000L,
        batteryLevel = 92,
        isActive = true
    )

    @Test
    fun `initial render displays live tab and all 3 bottom bar items`() {
        composeTestRule.activity.setContent {
            MainShell(userProfile = testProfile)
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("LIVE").assertIsDisplayed()
        composeTestRule.onNodeWithText("HISTORY").assertIsDisplayed()
        composeTestRule.onNodeWithText("SETTINGS").assertIsDisplayed()
    }

    @Test
    fun `switching tabs displays respective destination content`() {
        composeTestRule.activity.setContent {
            MainShell(userProfile = testProfile)
        }

        composeTestRule.waitForIdle()

        // Switch to HISTORY
        composeTestRule.onNodeWithText("HISTORY").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("SESSION HISTORY").assertIsDisplayed()

        // Switch to SETTINGS
        composeTestRule.onNodeWithText("SETTINGS").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("SETTINGS & PROFILE").assertIsDisplayed()
        composeTestRule.onNodeWithText("Birth Year: 1990 • Mode: KARVONEN").assertIsDisplayed()

        // Switch back to LIVE
        composeTestRule.onNodeWithText("LIVE").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("LIVE").assertIsDisplayed()
    }

    @Test
    fun `when immersion mode is enabled, bottom navigation bar is hidden`() {
        composeTestRule.activity.setContent {
            MainShellContent(
                uiState = MainShellUiState(
                    selectedTab = MainTab.LIVE_SESSION,
                    isImmersionMode = true
                ),
                actions = MainShellActions(),
                userProfile = testProfile
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("HISTORY").assertDoesNotExist()
        composeTestRule.onNodeWithText("SETTINGS").assertDoesNotExist()
    }

    @Test
    fun `when no Active HRM is paired, Live Session shows prominent notice, Settings shortcut, and disabled Start Session`() {
        var manageDevicesClicked = false

        composeTestRule.activity.setContent {
            MainShell(
                userProfile = testProfile,
                activeHrm = null,
                onManageDevices = { manageDevicesClicked = true }
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("No Heart Rate Monitor paired").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("start_session_button")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeTestRule.onNodeWithText("Start Session").assertIsNotEnabled()

        composeTestRule.onNodeWithText("Pair HRM in Settings")
            .performScrollTo()
            .performClick()
        assertTrue(manageDevicesClicked)
    }

    @Test
    fun `when Active HRM is paired, Live Session hides no-HRM notice and renders live session surface`() {
        composeTestRule.activity.setContent {
            MainShell(
                userProfile = testProfile,
                activeHrm = activeHrm
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("No Heart Rate Monitor paired").assertDoesNotExist()
        composeTestRule.onNodeWithTag("start_session_button").assertDoesNotExist()
        composeTestRule.onNodeWithText("LIVE").assertIsDisplayed()
    }

    @Test
    fun `incrementing openLiveSessionRequestId switches from Settings or History back to Live Session`() {
        var openLiveSessionRequestId by mutableIntStateOf(0)

        composeTestRule.activity.setContent {
            MainShell(
                userProfile = testProfile,
                activeHrm = activeHrm,
                openLiveSessionRequestId = openLiveSessionRequestId
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("SETTINGS").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("SETTINGS & PROFILE").assertIsDisplayed()

        composeTestRule.runOnIdle {
            openLiveSessionRequestId += 1
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("SETTINGS & PROFILE").assertDoesNotExist()
        composeTestRule.onNodeWithTag("live_session_bpm_text").assertIsDisplayed()

        composeTestRule.onNodeWithText("HISTORY").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("SESSION HISTORY").assertIsDisplayed()

        composeTestRule.runOnIdle {
            openLiveSessionRequestId += 1
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("SESSION HISTORY").assertDoesNotExist()
        composeTestRule.onNodeWithTag("live_session_bpm_text").assertIsDisplayed()
    }
}
