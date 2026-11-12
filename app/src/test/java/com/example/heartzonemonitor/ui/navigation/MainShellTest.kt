package com.example.heartzonemonitor.ui.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
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
}
