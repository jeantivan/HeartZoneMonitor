package com.example.heartzonemonitor.ui.onboarding

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.heartzonemonitor.MainActivity
import com.example.heartzonemonitor.domain.calculator.ZoneCalculator
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], packageName = "com.example.heartzonemonitor")
class OnboardingProfileScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `initial state renders headers, inputs, and disabled continue button`() {
        val state = OnboardingProfileUiState()

        composeTestRule.activity.setContent {
            OnboardingProfileContent(
                uiState = state,
                actions = OnboardingProfileActions()
            )
        }

        composeTestRule.onNodeWithText("Calibrate Your Heart Zones").assertIsDisplayed()
        composeTestRule.onNodeWithText("Birth Year").assertIsDisplayed()
        composeTestRule.onNodeWithText("Resting Heart Rate").assertIsDisplayed()
        composeTestRule.onNodeWithText("Custom Measured HRmax Override").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Continue to Sensor Pairing").assertIsNotEnabled()
    }

    @Test
    fun `valid state displays calculated HRmax, mode badge, zones, and enabled button`() {
        val zoneCalculator = ZoneCalculator()
        val preview = zoneCalculator.calculateZones(age = 38, restingHr = 60)
        val state = OnboardingProfileUiState(
            birthYearInput = "1988",
            restingHrInput = "60",
            calculationMode = ZoneCalculationMode.KARVONEN,
            previewResult = preview,
            isSaveEnabled = true
        )

        var continueClicked = false

        composeTestRule.activity.setContent {
            OnboardingProfileContent(
                uiState = state,
                actions = OnboardingProfileActions(
                    onContinueClicked = { continueClicked = true }
                )
            )
        }

        composeTestRule.onNodeWithText("KARVONEN HRR").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("181").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Continue to Sensor Pairing")
            .assertIsEnabled()
            .performClick()

        assertTrue(continueClicked)
    }

    @Test
    fun `enabling custom HRmax shows custom input field`() {
        val state = OnboardingProfileUiState(
            isCustomHrmaxEnabled = true,
            customHrmaxInput = ""
        )

        composeTestRule.activity.setContent {
            OnboardingProfileContent(
                uiState = state,
                actions = OnboardingProfileActions()
            )
        }

        composeTestRule.onNodeWithText("Custom HRmax").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `displays birth year and resting hr validation errors when present`() {
        val state = OnboardingProfileUiState(
            birthYearInput = "2020",
            birthYearError = "Age must be between 10 and 110",
            restingHrInput = "20",
            restingHrError = "Resting heart rate must be between 30 and 120 BPM",
            isSaveEnabled = false
        )

        composeTestRule.activity.setContent {
            OnboardingProfileContent(
                uiState = state,
                actions = OnboardingProfileActions()
            )
        }

        composeTestRule.onNodeWithText("Age must be between 10 and 110").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Resting heart rate must be between 30 and 120 BPM").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Continue to Sensor Pairing").assertIsNotEnabled()
    }

    @Test
    fun `displays persistence error message when database save fails`() {
        val state = OnboardingProfileUiState(
            birthYearInput = "1990",
            isSaveEnabled = true,
            errorMessage = "Database encryption error"
        )

        composeTestRule.activity.setContent {
            OnboardingProfileContent(
                uiState = state,
                actions = OnboardingProfileActions()
            )
        }

        composeTestRule.onNodeWithText("Database encryption error").assertIsDisplayed()
    }
}
