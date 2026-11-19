package com.example.heartzonemonitor.data.ble

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Snapshot of runtime permissions required to scan, connect to, and surface Session
 * notifications for a Heart Rate Monitor (HRM).
 */
data class BlePermissionStatus(
    val hasScanPermission: Boolean,
    val hasConnectPermission: Boolean,
    val hasNotificationPermission: Boolean
) {
    val allBlePermissionsGranted: Boolean
        get() = hasScanPermission && hasConnectPermission

    val allRequiredPermissionsGranted: Boolean
        get() = allBlePermissionsGranted && hasNotificationPermission

    val missingPermissions: List<String>
        get() = buildList {
            if (!hasScanPermission) add(Manifest.permission.BLUETOOTH_SCAN)
            if (!hasConnectPermission) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (!hasNotificationPermission) add(Manifest.permission.POST_NOTIFICATIONS)
        }
}

/**
 * Resolves the Android runtime permissions required for Heart Rate Monitor (HRM) discovery,
 * GATT communication, and foreground Session notifications without requesting location access.
 */
object BlePermissions {

    fun requiredBlePermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdkInt >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            emptyList()
        }

    fun requiredAllPermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> = buildList {
        addAll(requiredBlePermissions(sdkInt))
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun createAppSettingsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
}

/**
 * Evaluates whether the required Bluetooth and notification permissions are currently granted.
 */
interface BlePermissionChecker {
    fun checkPermissions(): BlePermissionStatus
}

/**
 * Android framework implementation of [BlePermissionChecker] targeting API 31+ BLE permissions
 * (`BLUETOOTH_SCAN` with `neverForLocation`, `BLUETOOTH_CONNECT`) and API 33+ `POST_NOTIFICATIONS`.
 */
class AndroidBlePermissionChecker(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : BlePermissionChecker {

    override fun checkPermissions(): BlePermissionStatus {
        val hasScan = if (sdkInt >= Build.VERSION_CODES.S) {
            isPermissionGranted(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            true
        }
        val hasConnect = if (sdkInt >= Build.VERSION_CODES.S) {
            isPermissionGranted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true
        }
        val hasNotifications = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
        return BlePermissionStatus(
            hasScanPermission = hasScan,
            hasConnectPermission = hasConnect,
            hasNotificationPermission = hasNotifications
        )
    }

    private fun isPermissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
