package com.example.heartzonemonitor.ui.ble

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import com.example.heartzonemonitor.data.ble.BlePermissionChecker
import com.example.heartzonemonitor.data.ble.BlePermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], packageName = "com.example.heartzonemonitor")
class BlePermissionDialogTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `stateless dialog hidden when isVisible is false`() {
        composeTestRule.activity.setContent {
            BlePermissionRationaleDialogContent(
                uiState = BlePermissionDialogUiState(isVisible = false),
                actions = BlePermissionDialogActions()
            )
        }

        composeTestRule.onNodeWithText("Heart Rate Monitor Permissions").assertDoesNotExist()
    }

    @Test
    fun `stateless rationale dialog renders missing permissions and triggers request and dismiss callbacks`() {
        var requestClicked = false
        var dismissClicked = false

        composeTestRule.activity.setContent {
            BlePermissionRationaleDialogContent(
                uiState = BlePermissionDialogUiState(
                    isVisible = true,
                    missingPermissions = listOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.POST_NOTIFICATIONS
                    ),
                    isPermanentlyDenied = false
                ),
                actions = BlePermissionDialogActions(
                    onRequestPermissions = { requestClicked = true },
                    onDismiss = { dismissClicked = true }
                )
            )
        }

        composeTestRule.onNodeWithText("Heart Rate Monitor Permissions").assertIsDisplayed()
        composeTestRule.onNodeWithText("BLUETOOTH_SCAN").assertIsDisplayed()
        composeTestRule.onNodeWithText("BLUETOOTH_CONNECT").assertIsDisplayed()
        composeTestRule.onNodeWithText("POST_NOTIFICATIONS").assertIsDisplayed()
        composeTestRule.onNodeWithText("Grant Permissions").performScrollTo().performClick()
        assertTrue(requestClicked)

        composeTestRule.onNodeWithText("Not Now").performScrollTo().performClick()
        assertTrue(dismissClicked)
    }

    @Test
    fun `stateless rationale dialog renders app settings prompt when permanently denied`() {
        var openSettingsClicked = false

        composeTestRule.activity.setContent {
            BlePermissionRationaleDialogContent(
                uiState = BlePermissionDialogUiState(
                    isVisible = true,
                    missingPermissions = listOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ),
                    isPermanentlyDenied = true
                ),
                actions = BlePermissionDialogActions(
                    onOpenSettings = { openSettingsClicked = true }
                )
            )
        }

        composeTestRule.onNodeWithText("Heart Rate Monitor Permissions Disabled").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open App Settings").performScrollTo().performClick()
        assertTrue(openSettingsClicked)
    }

    @Test
    fun `requester invokes onAllPermissionsGranted immediately when permissions already granted`() {
        val grantedChecker = FakeBlePermissionChecker(
            BlePermissionStatus(
                hasScanPermission = true,
                hasConnectPermission = true,
                hasNotificationPermission = true
            )
        )
        var grantedCount = 0
        var launchCount = 0
        val registryOwner = createFakeRegistryOwner { _ ->
            launchCount++
            emptyMap()
        }

        composeTestRule.activity.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                BlePermissionRequester(
                    permissionChecker = grantedChecker,
                    onAllPermissionsGranted = { grantedCount++ }
                ) { _, requestPermissions ->
                    Button(onClick = requestPermissions) {
                        Text("Check Permissions")
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("Check Permissions").performClick()
        assertEquals(1, grantedCount)
        assertEquals(0, launchCount)
        composeTestRule.onNodeWithText("Heart Rate Monitor Permissions").assertDoesNotExist()
    }

    @Test
    fun `requester shows rationale on denial and grants when re-requested`() {
        val checker = FakeBlePermissionChecker(
            BlePermissionStatus(
                hasScanPermission = false,
                hasConnectPermission = false,
                hasNotificationPermission = false
            )
        )
        var launchAttempt = 0
        var shouldShowRationaleFlag = false
        var lastRequestedPermissions: List<String> = emptyList()
        var allGrantedCallbackFired = false

        val registryOwner = createFakeRegistryOwner { input ->
            launchAttempt++
            lastRequestedPermissions = input.toList()
            if (launchAttempt == 1) {
                // First prompt: user denies all, so Android now flags rationale as needed
                shouldShowRationaleFlag = true
                input.associateWith { false }
            } else {
                // Second prompt from rationale dialog: user grants all
                checker.status = BlePermissionStatus(
                    hasScanPermission = true,
                    hasConnectPermission = true,
                    hasNotificationPermission = true
                )
                input.associateWith { true }
            }
        }

        composeTestRule.activity.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                BlePermissionRequester(
                    permissionChecker = checker,
                    includeNotifications = true,
                    shouldShowRationale = { shouldShowRationaleFlag },
                    onAllPermissionsGranted = { allGrantedCallbackFired = true }
                ) { _, requestPermissions ->
                    Button(onClick = requestPermissions) {
                        Text("Enable Bluetooth")
                    }
                }
            }
        }

        // First trigger launches OS permission prompt, which denies -> shows educational rationale
        composeTestRule.onNodeWithText("Enable Bluetooth").performClick()
        assertEquals(1, launchAttempt)
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS
            ),
            lastRequestedPermissions
        )
        assertFalse(allGrantedCallbackFired)
        composeTestRule.onNodeWithText("Heart Rate Monitor Permissions").assertIsDisplayed()

        // Tapping Grant Permissions inside rationale dialog re-launches prompt and succeeds
        composeTestRule.onNodeWithText("Grant Permissions").performScrollTo().performClick()
        assertEquals(2, launchAttempt)
        assertTrue(allGrantedCallbackFired)
        composeTestRule.onNodeWithText("Heart Rate Monitor Permissions").assertDoesNotExist()
    }

    @Test
    fun `requester shows rationale immediately before relaunch when rationale already required`() {
        val checker = FakeBlePermissionChecker(
            BlePermissionStatus(
                hasScanPermission = false,
                hasConnectPermission = false,
                hasNotificationPermission = true
            )
        )
        var launchAttempt = 0
        var allGranted = false

        val registryOwner = createFakeRegistryOwner { input ->
            launchAttempt++
            checker.status = BlePermissionStatus(
                hasScanPermission = true,
                hasConnectPermission = true,
                hasNotificationPermission = true
            )
            input.associateWith { true }
        }

        composeTestRule.activity.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                BlePermissionRequester(
                    permissionChecker = checker,
                    includeNotifications = false,
                    shouldShowRationale = { true },
                    onAllPermissionsGranted = { allGranted = true }
                ) { _, requestPermissions ->
                    Button(onClick = requestPermissions) {
                        Text("Enable Bluetooth")
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("Enable Bluetooth").performClick()
        assertEquals(0, launchAttempt)
        composeTestRule.onNodeWithText("Heart Rate Monitor Permissions").assertIsDisplayed()

        composeTestRule.onNodeWithText("Grant Permissions").performScrollTo().performClick()
        assertEquals(1, launchAttempt)
        assertTrue(allGranted)
    }

    @Test
    fun `requester guides user to app settings when permissions are permanently denied`() {
        val checker = FakeBlePermissionChecker(
            BlePermissionStatus(
                hasScanPermission = false,
                hasConnectPermission = false,
                hasNotificationPermission = true
            )
        )
        var settingsOpened = false
        val registryOwner = createFakeRegistryOwner { input ->
            input.associateWith { false }
        }

        composeTestRule.activity.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                BlePermissionRequester(
                    permissionChecker = checker,
                    includeNotifications = false,
                    shouldShowRationale = { false },
                    onOpenAppSettings = { settingsOpened = true }
                ) { _, requestPermissions ->
                    Button(onClick = requestPermissions) {
                        Text("Start Scan")
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("Start Scan").performClick()
        composeTestRule.onNodeWithText("Heart Rate Monitor Permissions Disabled").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open App Settings").performScrollTo().performClick()
        assertTrue(settingsOpened)
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
