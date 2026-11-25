package com.example.heartzonemonitor.ui.security

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.fragment.app.FragmentActivity
import com.example.heartzonemonitor.core.security.BiometricAuthResult
import com.example.heartzonemonitor.core.security.BiometricAuthenticatorImpl
import com.example.heartzonemonitor.core.security.BiometricPromptCallback
import com.example.heartzonemonitor.core.security.BiometricPromptLauncher
import com.example.heartzonemonitor.core.security.BiometricSecurityChecker
import com.example.heartzonemonitor.core.security.BiometricSecuritySettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], packageName = "com.example.heartzonemonitor")
class BiometricSecuritySetupDialogTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `stateless alert dialog hidden when isVisible is false`() {
        composeTestRule.activity.setContent {
            BiometricSecuritySetupDialog(
                isVisible = false,
                message = "Setup required",
                onOpenSettings = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Device Security Required").assertDoesNotExist()
    }

    @Test
    fun `stateless alert dialog displays message and triggers Open Settings and Cancel`() {
        var openSettingsClicked = false
        var cancelClicked = false

        composeTestRule.activity.setContent {
            BiometricSecuritySetupDialog(
                isVisible = true,
                message = "Set up a screen lock in Android Settings.",
                onOpenSettings = { openSettingsClicked = true },
                onDismiss = { cancelClicked = true },
            )
        }

        composeTestRule.onNodeWithText("Device Security Required").assertIsDisplayed()
        composeTestRule.onNodeWithText("Set up a screen lock in Android Settings.").assertIsDisplayed()

        composeTestRule.onNodeWithText("Open Settings").performClick()
        assertTrue(openSettingsClicked)

        composeTestRule.onNodeWithText("Cancel").performClick()
        assertTrue(cancelClicked)
    }

    @Test
    fun `when device has no enrolled credentials, gate blocks action and displays Settings alert`() {
        val checker = object : BiometricSecurityChecker {
            override fun canAuthenticate(): Int = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
        }
        var promptLaunched = false
        val launcher = object : BiometricPromptLauncher {
            override fun launch(
                activity: FragmentActivity?,
                title: String,
                subtitle: String,
                allowedAuthenticators: Int,
                callback: BiometricPromptCallback,
            ) {
                promptLaunched = true
            }
        }
        val authenticator = BiometricAuthenticatorImpl(checker, launcher)
        var settingsOpened = false
        var actionAllowed = false

        composeTestRule.activity.setContent {
            val gateState = rememberBiometricAuthGate(
                authenticator = authenticator,
                activity = null,
                onOpenSettings = { settingsOpened = true },
            )

            Button(
                onClick = {
                    gateState.authenticate(
                        title = "Verify identity",
                        subtitle = "Authenticate to export health data",
                    ) { result ->
                        if (result is BiometricAuthResult.Success) {
                            actionAllowed = true
                        }
                    }
                },
            ) {
                Text("Export")
            }

            BiometricSecuritySetupGateHost(gateState = gateState)
        }

        composeTestRule.onNodeWithText("Export").performClick()

        assertFalse(promptLaunched)
        assertFalse(actionAllowed)
        composeTestRule.onNodeWithText("Device Security Required").assertIsDisplayed()

        composeTestRule.onNodeWithText("Open Settings").performClick()
        assertTrue(settingsOpened)
        composeTestRule.onNodeWithText("Device Security Required").assertDoesNotExist()
    }

    @Test
    fun `createDeviceSecuritySettingsIntent returns valid settings action`() {
        val intent = BiometricSecuritySettings.createDeviceSecuritySettingsIntent(composeTestRule.activity)
        val allowedActions = setOf(
            Settings.ACTION_BIOMETRIC_ENROLL,
            Settings.ACTION_SECURITY_SETTINGS,
            Settings.ACTION_SETTINGS,
        )
        assertTrue("Unexpected action: ${intent.action}", intent.action in allowedActions)
    }
}
