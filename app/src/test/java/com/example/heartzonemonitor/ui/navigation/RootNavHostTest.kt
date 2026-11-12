package com.example.heartzonemonitor.ui.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.example.heartzonemonitor.data.local.FakeUserProfileDao
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import com.example.heartzonemonitor.ui.onboarding.OnboardingProfileViewModel
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

    @Before
    fun setUp() {
        fakeDao = FakeUserProfileDao()
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

        val rootViewModel = RootViewModel(userProfileDao = fakeDao)
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
    fun `saving a profile transitions to pairing step and pair later enters MainShell`() {
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

        // Enter valid birth year
        composeTestRule.onNodeWithText("1988").performTextInput("1990")
        composeTestRule.waitForIdle()

        // Continue to Sensor Pairing (persists profile into Room)
        composeTestRule.onNodeWithText("Continue to Sensor Pairing").performClick()
        composeTestRule.waitForIdle()

        // Arrive at Step 2 Pairing
        composeTestRule.onNodeWithText("PAIR HEART RATE MONITOR").assertIsDisplayed()

        // Click Pair Later to complete onboarding
        composeTestRule.onNodeWithText("PAIR LATER").performClick()
        composeTestRule.waitForIdle()

        // Main shell is now visible and unlocked
        composeTestRule.onNodeWithText("LIVE").assertIsDisplayed()
        composeTestRule.onNodeWithText("HISTORY").assertIsDisplayed()
        composeTestRule.onNodeWithText("SETTINGS").assertIsDisplayed()
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
}
