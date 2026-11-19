package com.example.heartzonemonitor.data.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class FakeBleTransportTest {

    private lateinit var transport: FakeBleTransport

    @Before
    fun setUp() {
        transport = FakeBleTransport()
    }

    @Test
    fun `scan filters by Heart Rate Service UUID and emits matching RSSI and names`() = runTest {
        val hrmResult = BleScanResult(
            macAddress = "D4:36:39:11:22:9B",
            advertisedName = "COOSPO H808S",
            rssi = -54,
            serviceUuids = listOf(BleUuids.HEART_RATE_SERVICE_UUID),
            timestampEpochMs = 1700000000000L
        )
        val unrelatedAudioResult = BleScanResult(
            macAddress = "AA:BB:CC:DD:EE:FF",
            advertisedName = "Studio Headphones",
            rssi = -48,
            serviceUuids = listOf(UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb")),
            timestampEpochMs = 1700000000100L
        )
        val liveHrmResult = BleScanResult(
            macAddress = "A0:9E:1A:22:33:11",
            advertisedName = "Polar H10",
            rssi = -67,
            serviceUuids = listOf(BleUuids.HEART_RATE_SERVICE_UUID),
            timestampEpochMs = 1700000000200L
        )

        transport.setScanResults(listOf(hrmResult, unrelatedAudioResult))

        val collected = mutableListOf<BleScanResult>()
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.scan(BleUuids.HEART_RATE_SERVICE_UUID).toList(collected)
        }

        assertTrue(transport.isScanning)
        assertEquals(BleConnectionState.Scanning, transport.connectionState.value)

        transport.emitScanResult(liveHrmResult)
        transport.stopScan()
        collectJob.join()

        assertFalse(transport.isScanning)
        assertEquals(BleConnectionState.Disconnected, transport.connectionState.value)
        assertEquals(listOf(hrmResult, liveHrmResult), collected)
    }

    @Test
    fun `scan with null filter emits all advertised devices`() = runTest {
        val hrm = BleScanResult("D4:36:39:11:22:9B", "COOSPO H808S", -55, listOf(BleUuids.HEART_RATE_SERVICE_UUID))
        val other = BleScanResult("11:22:33:44:55:66", "Beacon", -80, emptyList())
        transport.setScanResults(listOf(hrm, other))

        val collected = mutableListOf<BleScanResult>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.scan(serviceUuidFilter = null).toList(collected)
        }
        transport.stopScan()
        job.join()

        assertEquals(listOf(hrm, other), collected)
    }

    @Test
    fun `connect stops active scan and transitions connection state deterministically`() = runTest {
        val mac = "D4:36:39:11:22:9B"
        val states = Channel<BleConnectionState>(Channel.UNLIMITED)
        val stateJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.connectionState.collect { states.send(it) }
        }
        assertEquals(BleConnectionState.Disconnected, states.receive())

        val scanJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.scan().toList()
        }
        assertEquals(BleConnectionState.Scanning, states.receive())

        val result = transport.connect(mac, autoConnect = false)
        assertTrue(result is BleOperationResult.Success)
        assertEquals(BleConnectionState.Disconnected, states.receive())
        assertEquals(BleConnectionState.Connecting(mac), states.receive())
        assertEquals(BleConnectionState.Connected(mac), states.receive())
        assertFalse(transport.isScanning)

        val ops = transport.recordedOperations
        val stopIndex = ops.indexOf(BleRecordedOperation.ScanStopped)
        val connectIndex = ops.indexOf(BleRecordedOperation.Connect(mac, autoConnect = false))
        assertTrue("Expected ScanStopped before Connect in $ops", stopIndex in 0 until connectIndex)

        // Simulate unexpected link drop
        transport.simulateDisconnect()
        assertEquals(BleConnectionState.Disconnected, states.receive())

        scanJob.cancel()
        stateJob.cancel()
    }

    @Test
    fun `connect failure leaves connection state Disconnected and returns Failure`() = runTest {
        val mac = "D4:36:39:11:22:9B"
        transport.setConnectResult(
            BleOperationResult.Failure(133, "GATT error 133"),
            macAddress = mac
        )

        val result = transport.connect(mac)
        assertTrue(result is BleOperationResult.Failure)
        assertEquals(133, (result as BleOperationResult.Failure).status)
        assertEquals(BleConnectionState.Disconnected, transport.connectionState.value)
    }

    @Test
    fun `gatt operations require active connection and support stubbed reads, writes, and 0x2A37 streaming`() = runTest {
        val mac = "D4:36:39:11:22:9B"

        // Disconnected guard
        val disconnectedRead = transport.readCharacteristic(
            mac,
            BleUuids.BATTERY_SERVICE_UUID,
            BleUuids.BATTERY_LEVEL_CHAR_UUID
        )
        assertTrue(disconnectedRead is BleOperationResult.Failure)
        assertEquals(
            BleOperationResult.STATUS_NOT_CONNECTED,
            (disconnectedRead as BleOperationResult.Failure).status
        )

        // Connect and execute full GATT sequence
        assertTrue(transport.connect(mac) is BleOperationResult.Success)

        val servicesResult = transport.discoverServices(mac)
        assertTrue(servicesResult is BleOperationResult.Success)
        val services = (servicesResult as BleOperationResult.Success).value
        assertTrue(services.any { it.uuid == BleUuids.HEART_RATE_SERVICE_UUID })
        assertTrue(services.any { it.uuid == BleUuids.BATTERY_SERVICE_UUID })

        assertTrue(
            transport.setCharacteristicNotification(
                mac,
                BleUuids.HEART_RATE_SERVICE_UUID,
                BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID,
                enable = true
            ) is BleOperationResult.Success
        )

        val cccdEnable = byteArrayOf(0x01, 0x00)
        assertTrue(
            transport.writeDescriptor(
                mac,
                BleUuids.HEART_RATE_SERVICE_UUID,
                BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID,
                BleUuids.CCCD_DESCRIPTOR_UUID,
                cccdEnable
            ) is BleOperationResult.Success
        )

        transport.stubCharacteristicRead(
            characteristicUuid = BleUuids.BATTERY_LEVEL_CHAR_UUID,
            result = BleOperationResult.Success(byteArrayOf(87)),
            serviceUuid = BleUuids.BATTERY_SERVICE_UUID,
            macAddress = mac
        )
        val batteryResult = transport.readCharacteristic(
            mac,
            BleUuids.BATTERY_SERVICE_UUID,
            BleUuids.BATTERY_LEVEL_CHAR_UUID
        )
        assertTrue(batteryResult is BleOperationResult.Success)
        assertArrayEquals(byteArrayOf(87), (batteryResult as BleOperationResult.Success).value)

        val controlPointPayload = byteArrayOf(0x01)
        assertTrue(
            transport.writeCharacteristic(
                mac,
                BleUuids.HEART_RATE_SERVICE_UUID,
                UUID.fromString("00002a39-0000-1000-8000-00805f9b34fb"),
                controlPointPayload,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) is BleOperationResult.Success
        )

        // Stream synthetic 0x2A37 byte notifications
        val notifications = Channel<BleCharacteristicNotification>(Channel.UNLIMITED)
        val notifyJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.characteristicNotifications.collect { notifications.send(it) }
        }

        val packet1 = FakeBleTransport.buildHeartRateMeasurementPacket(
            bpm = 148,
            sensorContactSupported = true,
            sensorContactDetected = true,
            rrIntervals1024 = listOf(415)
        )
        transport.emitNotification(
            macAddress = mac,
            value = packet1,
            timestampEpochMs = 1700000001000L
        )

        val received = notifications.receive()
        assertEquals(mac, received.macAddress)
        assertEquals(BleUuids.HEART_RATE_SERVICE_UUID, received.serviceUuid)
        assertEquals(BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID, received.characteristicUuid)
        assertArrayEquals(packet1, received.value)
        assertEquals(1700000001000L, received.timestampEpochMs)

        // Verify recorded operation ordering
        assertTrue(transport.recordedOperations.contains(BleRecordedOperation.DiscoverServices(mac)))
        assertTrue(
            transport.recordedOperations.contains(
                BleRecordedOperation.WriteDescriptor(
                    macAddress = mac,
                    serviceUuid = BleUuids.HEART_RATE_SERVICE_UUID,
                    characteristicUuid = BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID,
                    descriptorUuid = BleUuids.CCCD_DESCRIPTOR_UUID,
                    value = cccdEnable
                )
            )
        )

        notifyJob.cancel()
        transport.close()
        assertEquals(BleConnectionState.Disconnected, transport.connectionState.value)
    }

    @Test
    fun `BleCharacteristicNotification implements content-based equals and hashCode`() {
        val first = BleCharacteristicNotification(
            macAddress = "D4:36:39:11:22:9B",
            serviceUuid = BleUuids.HEART_RATE_SERVICE_UUID,
            characteristicUuid = BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID,
            value = byteArrayOf(0x06, 0x8C.toByte()),
            timestampEpochMs = 5000L
        )
        val equalCopy = BleCharacteristicNotification(
            macAddress = "D4:36:39:11:22:9B",
            serviceUuid = BleUuids.HEART_RATE_SERVICE_UUID,
            characteristicUuid = BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID,
            value = byteArrayOf(0x06, 0x8C.toByte()),
            timestampEpochMs = 5000L
        )
        val differentBytes = equalCopy.copy(value = byteArrayOf(0x06, 0x90.toByte()))

        assertEquals(first, equalCopy)
        assertEquals(first.hashCode(), equalCopy.hashCode())
        assertNotEquals(first, differentBytes)
    }
}
