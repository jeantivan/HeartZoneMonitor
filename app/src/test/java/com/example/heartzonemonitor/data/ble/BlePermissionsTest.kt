package com.example.heartzonemonitor.data.ble

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import android.os.Build
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], packageName = "com.example.heartzonemonitor")
class BlePermissionsTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `manifest declares BLE and notification permissions without location permissions`() {
        val packageInfo = application.packageManager.getPackageInfo(
            application.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val declaredPermissions = packageInfo.requestedPermissions?.toList().orEmpty()

        assertTrue(Manifest.permission.BLUETOOTH_SCAN in declaredPermissions)
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in declaredPermissions)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in declaredPermissions)
        assertFalse(Manifest.permission.ACCESS_FINE_LOCATION in declaredPermissions)
        assertFalse(Manifest.permission.ACCESS_COARSE_LOCATION in declaredPermissions)
    }

    @Test
    fun `manifest xml declares neverForLocation on BLUETOOTH_SCAN and optional bluetooth_le feature`() {
        val androidNs = "http://schemas.android.com/apk/res/android"
        var scanHasNeverForLocation = false
        var bleFeatureDeclared = false
        var bleFeatureRequired: Boolean? = null

        application.assets.openXmlResourceParser("AndroidManifest.xml").use { parser ->
            while (parser.eventType != XmlResourceParser.END_DOCUMENT) {
                if (parser.eventType == XmlResourceParser.START_TAG) {
                    when (parser.name) {
                        "uses-permission" -> {
                            val name = parser.getAttributeValue(androidNs, "name")
                            if (name == Manifest.permission.BLUETOOTH_SCAN) {
                                // usesPermissionFlags="neverForLocation" compiles to bitmask 0x10000
                                val flags = parser.getAttributeIntValue(androidNs, "usesPermissionFlags", 0)
                                val rawValue = parser.getAttributeValue(androidNs, "usesPermissionFlags")
                                scanHasNeverForLocation = (flags and 0x10000) != 0 ||
                                    rawValue == "neverForLocation"
                            }
                        }
                        "uses-feature" -> {
                            val name = parser.getAttributeValue(androidNs, "name")
                            if (name == PackageManager.FEATURE_BLUETOOTH_LE) {
                                bleFeatureDeclared = true
                                bleFeatureRequired = parser.getAttributeBooleanValue(androidNs, "required", true)
                            }
                        }
                    }
                }
                parser.next()
            }
        }

        assertTrue("BLUETOOTH_SCAN must specify neverForLocation", scanHasNeverForLocation)
        assertTrue("android.hardware.bluetooth_le feature must be declared", bleFeatureDeclared)
        assertEquals(false, bleFeatureRequired)
    }

    @Test
    fun `requiredBlePermissions and requiredAllPermissions adapt cleanly across API 31 and API 33 plus`() {
        val bleApi31 = BlePermissions.requiredBlePermissions(Build.VERSION_CODES.S)
        val allApi31 = BlePermissions.requiredAllPermissions(Build.VERSION_CODES.S)
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            bleApi31
        )
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            allApi31
        )

        val allApi32 = BlePermissions.requiredAllPermissions(Build.VERSION_CODES.S_V2)
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            allApi32
        )

        val bleApi33 = BlePermissions.requiredBlePermissions(Build.VERSION_CODES.TIRAMISU)
        val allApi33 = BlePermissions.requiredAllPermissions(Build.VERSION_CODES.TIRAMISU)
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            bleApi33
        )
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS
            ),
            allApi33
        )
    }

    @Test
    fun `BlePermissionStatus computed properties reflect missing and granted combinations`() {
        val missingAll = BlePermissionStatus(
            hasScanPermission = false,
            hasConnectPermission = false,
            hasNotificationPermission = false
        )
        assertFalse(missingAll.allBlePermissionsGranted)
        assertFalse(missingAll.allRequiredPermissionsGranted)
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS
            ),
            missingAll.missingPermissions
        )

        val bleOnlyGranted = BlePermissionStatus(
            hasScanPermission = true,
            hasConnectPermission = true,
            hasNotificationPermission = false
        )
        assertTrue(bleOnlyGranted.allBlePermissionsGranted)
        assertFalse(bleOnlyGranted.allRequiredPermissionsGranted)
        assertEquals(
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            bleOnlyGranted.missingPermissions
        )

        val allGranted = BlePermissionStatus(
            hasScanPermission = true,
            hasConnectPermission = true,
            hasNotificationPermission = true
        )
        assertTrue(allGranted.allBlePermissionsGranted)
        assertTrue(allGranted.allRequiredPermissionsGranted)
        assertTrue(allGranted.missingPermissions.isEmpty())
    }

    @Test
    fun `AndroidBlePermissionChecker evaluates API 31 and API 33 plus permissions accurately`() {
        val shadowApp = Shadows.shadowOf(application)
        shadowApp.denyPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS
        )

        val checkerApi31 = AndroidBlePermissionChecker(application, sdkInt = Build.VERSION_CODES.S)
        val checkerApi33 = AndroidBlePermissionChecker(application, sdkInt = Build.VERSION_CODES.TIRAMISU)

        // Initially none granted: API 31 treats notifications as granted, API 33 requires POST_NOTIFICATIONS
        val initial31 = checkerApi31.checkPermissions()
        assertFalse(initial31.allBlePermissionsGranted)
        assertTrue(initial31.hasNotificationPermission)
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            initial31.missingPermissions
        )

        val initial33 = checkerApi33.checkPermissions()
        assertFalse(initial33.allBlePermissionsGranted)
        assertFalse(initial33.hasNotificationPermission)
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS
            ),
            initial33.missingPermissions
        )

        // Grant BLE permissions only
        shadowApp.grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        val bleOnly31 = checkerApi31.checkPermissions()
        assertTrue(bleOnly31.allRequiredPermissionsGranted)
        assertTrue(bleOnly31.missingPermissions.isEmpty())

        val bleOnly33 = checkerApi33.checkPermissions()
        assertTrue(bleOnly33.allBlePermissionsGranted)
        assertFalse(bleOnly33.allRequiredPermissionsGranted)
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), bleOnly33.missingPermissions)

        // Grant POST_NOTIFICATIONS as well
        shadowApp.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val full33 = checkerApi33.checkPermissions()
        assertTrue(full33.allRequiredPermissionsGranted)
        assertTrue(full33.missingPermissions.isEmpty())
    }

    @Test
    fun `createAppSettingsIntent targets package details settings`() {
        val intent = BlePermissions.createAppSettingsIntent("com.example.heartzonemonitor")
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:com.example.heartzonemonitor", intent.dataString)
        assertEquals(Intent::class.java, intent::class.java)
    }
}
