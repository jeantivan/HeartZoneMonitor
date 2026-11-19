package com.example.heartzonemonitor.data.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], packageName = "com.example.heartzonemonitor")
class AndroidBleTransportTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `missing permissions fail gracefully without throwing SecurityException`() = runTest {
        val deniedChecker = FixedPermissionChecker(
            BlePermissionStatus(
                hasScanPermission = false,
                hasConnectPermission = false,
                hasNotificationPermission = false
            )
        )
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
        val transport = AndroidBleTransport(
            context = context,
            permissionChecker = deniedChecker,
            bluetoothManager = bluetoothManager
        )

        val scanResults = transport.scan().toList()
        assertTrue(scanResults.isEmpty())
        assertEquals(BleConnectionState.Disconnected, transport.connectionState.value)

        val connectResult = transport.connect("D4:36:39:11:22:9B")
        assertTrue(connectResult is BleOperationResult.Failure)
        assertEquals(
            BleOperationResult.STATUS_PERMISSION_DENIED,
            (connectResult as BleOperationResult.Failure).status
        )

        val discoverResult = transport.discoverServices("D4:36:39:11:22:9B")
        assertTrue(discoverResult is BleOperationResult.Failure)
        assertEquals(
            BleOperationResult.STATUS_PERMISSION_DENIED,
            (discoverResult as BleOperationResult.Failure).status
        )

        val notifyResult = transport.setCharacteristicNotification(
            "D4:36:39:11:22:9B",
            BleUuids.HEART_RATE_SERVICE_UUID,
            BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID,
            enable = true
        )
        assertEquals(
            BleOperationResult.STATUS_PERMISSION_DENIED,
            (notifyResult as BleOperationResult.Failure).status
        )

        val descriptorResult = transport.writeDescriptor(
            "D4:36:39:11:22:9B",
            BleUuids.HEART_RATE_SERVICE_UUID,
            BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID,
            BleUuids.CCCD_DESCRIPTOR_UUID,
            byteArrayOf(0x01, 0x00)
        )
        assertEquals(
            BleOperationResult.STATUS_PERMISSION_DENIED,
            (descriptorResult as BleOperationResult.Failure).status
        )

        val readResult = transport.readCharacteristic(
            "D4:36:39:11:22:9B",
            BleUuids.BATTERY_SERVICE_UUID,
            BleUuids.BATTERY_LEVEL_CHAR_UUID
        )
        assertEquals(
            BleOperationResult.STATUS_PERMISSION_DENIED,
            (readResult as BleOperationResult.Failure).status
        )

        val writeResult = transport.writeCharacteristic(
            "D4:36:39:11:22:9B",
            BleUuids.HEART_RATE_SERVICE_UUID,
            BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID,
            byteArrayOf(0x01)
        )
        assertEquals(
            BleOperationResult.STATUS_PERMISSION_DENIED,
            (writeResult as BleOperationResult.Failure).status
        )

        transport.close()
    }

    @Test
    fun `disabled or unavailable Bluetooth adapter fails gracefully`() = runTest {
        val grantedChecker = FixedPermissionChecker(
            BlePermissionStatus(
                hasScanPermission = true,
                hasConnectPermission = true,
                hasNotificationPermission = true
            )
        )

        // Null BluetoothManager case
        val unavailableTransport = AndroidBleTransport(
            context = context,
            permissionChecker = grantedChecker,
            bluetoothManager = null
        )
        assertFalse(unavailableTransport.isBluetoothEnabled)
        assertTrue(unavailableTransport.scan().toList().isEmpty())
        val unavailableConnect = unavailableTransport.connect("D4:36:39:11:22:9B")
        assertEquals(
            BleOperationResult.STATUS_BLUETOOTH_UNAVAILABLE,
            (unavailableConnect as BleOperationResult.Failure).status
        )

        // Disabled BluetoothAdapter case
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter
        assertNotNull("Expected Robolectric BluetoothAdapter", adapter)
        @Suppress("DEPRECATION")
        Shadows.shadowOf(adapter!!).setEnabled(false)
        val disabledTransport = AndroidBleTransport(
            context = context,
            permissionChecker = grantedChecker,
            bluetoothManager = bluetoothManager
        )
        assertFalse(disabledTransport.isBluetoothEnabled)
        assertTrue(disabledTransport.scan().toList().isEmpty())

        val disabledConnect = disabledTransport.connect("D4:36:39:11:22:9B")
        assertEquals(
            BleOperationResult.STATUS_BLUETOOTH_DISABLED,
            (disabledConnect as BleOperationResult.Failure).status
        )
    }

    @Test
    fun `invalid MAC address returns invalid argument failure`() = runTest {
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

        val transport = AndroidBleTransport(
            context = context,
            permissionChecker = grantedChecker,
            bluetoothManager = bluetoothManager
        )

        val result = transport.connect("INVALID_MAC")
        assertTrue(result is BleOperationResult.Failure)
        assertEquals(
            BleOperationResult.STATUS_INVALID_ARGUMENT,
            (result as BleOperationResult.Failure).status
        )
    }

    @Test
    fun `operations fail with STATUS_NOT_CONNECTED when no GATT connection is open`() = runTest {
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

        val transport = AndroidBleTransport(
            context = context,
            permissionChecker = grantedChecker,
            bluetoothManager = bluetoothManager
        )

        val discover = transport.discoverServices("D4:36:39:11:22:9B")
        assertTrue(discover is BleOperationResult.Failure)
        assertEquals(
            BleOperationResult.STATUS_NOT_CONNECTED,
            (discover as BleOperationResult.Failure).status
        )
    }

    @Test
    fun `scan transitions state to Scanning and resets to Disconnected on stopScan`() = runTest {
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
        assertNotNull("Expected Robolectric BluetoothLeScanner when enabled", adapter.bluetoothLeScanner)

        val transport = AndroidBleTransport(
            context = context,
            permissionChecker = grantedChecker,
            bluetoothManager = bluetoothManager
        )

        val scanJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.scan(BleUuids.HEART_RATE_SERVICE_UUID).toList()
        }
        assertEquals(BleConnectionState.Scanning, transport.connectionState.value)
        transport.stopScan()
        scanJob.join()
        assertEquals(BleConnectionState.Disconnected, transport.connectionState.value)
    }

    private class FixedPermissionChecker(
        private val status: BlePermissionStatus
    ) : BlePermissionChecker {
        override fun checkPermissions(): BlePermissionStatus = status
    }
}
