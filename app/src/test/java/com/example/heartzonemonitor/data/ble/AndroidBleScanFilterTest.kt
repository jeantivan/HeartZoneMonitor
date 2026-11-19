package com.example.heartzonemonitor.data.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], packageName = "com.example.heartzonemonitor")
class AndroidBleScanFilterTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `ScanFilter builder sets Heart Rate Service UUID 0x180D`() {
        val expectedParcelUuid = ParcelUuid(BleUuids.HEART_RATE_SERVICE_UUID)
        val filter = ScanFilter.Builder()
            .setServiceUuid(expectedParcelUuid)
            .build()

        assertEquals(expectedParcelUuid, filter.serviceUuid)
        assertEquals(
            UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"),
            filter.serviceUuid?.uuid
        )
    }

    @Test
    fun `AndroidBleTransport scan starts and stops cleanly`() =
        runTest {
            val grantedChecker = FixedPermissionChecker(
                BlePermissionStatus(
                    hasScanPermission = true,
                    hasConnectPermission = true,
                    hasNotificationPermission = true
                )
            )
            val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
            val adapter = bluetoothManager?.adapter
            assertNotNull("Expected Robolectric BluetoothAdapter", adapter)
            @Suppress("DEPRECATION")
            Shadows.shadowOf(adapter!!).setEnabled(true)
            val scanner = adapter.bluetoothLeScanner
            assertNotNull("Expected Robolectric BluetoothLeScanner when enabled", scanner)

            val transport = AndroidBleTransport(
                context = context,
                permissionChecker = grantedChecker,
                bluetoothManager = bluetoothManager
            )
            val service = BleDeviceDiscoveryService(
                bleTransport = transport,
                scope = backgroundScope,
                permissionChecker = grantedChecker
            )

            service.startScan()
            runCurrent()

            assertEquals(BleConnectionState.Scanning, transport.connectionState.value)
            assertEquals(BleDiscoveryState.Scanning(emptyList()), service.state.value)
            assertTrue(Shadows.shadowOf(scanner).scanCallbacks.isNotEmpty())

            service.stopScan()

            assertEquals(BleConnectionState.Disconnected, transport.connectionState.value)
            assertEquals(BleDiscoveryState.Stopped(emptyList()), service.state.value)
            assertTrue(Shadows.shadowOf(scanner).scanCallbacks.isEmpty())

            transport.close()
        }

    @Test
    fun `AndroidBleTransport reports Unavailable when denied or disabled`() =
        runTest {
            val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
            val adapter = bluetoothManager?.adapter
            assertNotNull("Expected Robolectric BluetoothAdapter", adapter)
            @Suppress("DEPRECATION")
            Shadows.shadowOf(adapter!!).setEnabled(true)

            val deniedChecker = FixedPermissionChecker(
                BlePermissionStatus(
                    hasScanPermission = false,
                    hasConnectPermission = true,
                    hasNotificationPermission = true
                )
            )
            val deniedTransport = AndroidBleTransport(
                context = context,
                permissionChecker = deniedChecker,
                bluetoothManager = bluetoothManager
            )
            val deniedService = BleDeviceDiscoveryService(
                bleTransport = deniedTransport,
                scope = backgroundScope,
                permissionChecker = deniedChecker
            )

            deniedService.startScan()
            runCurrent()
            assertEquals(
                BleDiscoveryState.Unavailable(BleDiscoveryUnavailableReason.PERMISSION_DENIED),
                deniedService.state.value
            )
            assertEquals(BleConnectionState.Disconnected, deniedTransport.connectionState.value)

            @Suppress("DEPRECATION")
            Shadows.shadowOf(adapter).setEnabled(false)
            val grantedChecker = FixedPermissionChecker(
                BlePermissionStatus(
                    hasScanPermission = true,
                    hasConnectPermission = true,
                    hasNotificationPermission = true
                )
            )
            val disabledTransport = AndroidBleTransport(
                context = context,
                permissionChecker = grantedChecker,
                bluetoothManager = bluetoothManager
            )
            val disabledService = BleDeviceDiscoveryService(
                bleTransport = disabledTransport,
                scope = backgroundScope,
                permissionChecker = grantedChecker
            )

            disabledService.startScan()
            runCurrent()
            assertEquals(
                BleDiscoveryState.Unavailable(BleDiscoveryUnavailableReason.BLUETOOTH_DISABLED),
                disabledService.state.value
            )
            assertEquals(BleConnectionState.Disconnected, disabledTransport.connectionState.value)

            deniedTransport.close()
            disabledTransport.close()
        }

    private class FixedPermissionChecker(
        private val status: BlePermissionStatus
    ) : BlePermissionChecker {
        override fun checkPermissions(): BlePermissionStatus = status
    }
}
