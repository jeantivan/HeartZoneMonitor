package com.example.heartzonemonitor.data.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class BleDeviceDiscoveryServiceTest {

    private lateinit var fakeTransport: FakeBleTransport

    @Before
    fun setUp() {
        fakeTransport = FakeBleTransport()
    }

    @Test
    fun `startScan passes 0x180D UUID and filters non-HRM devices`() = runTest {
        val hrmResult = BleScanResult(
            macAddress = "D4:36:39:11:22:9B",
            advertisedName = "COOSPO H808S",
            rssi = -54,
            serviceUuids = listOf(BleUuids.HEART_RATE_SERVICE_UUID),
            timestampEpochMs = 1_700_000_000_000L
        )
        val unrelatedAudioResult = BleScanResult(
            macAddress = "AA:BB:CC:DD:EE:FF",
            advertisedName = "Studio Headphones",
            rssi = -42,
            serviceUuids = listOf(UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb")),
            timestampEpochMs = 1_700_000_000_100L
        )
        val emptyUuidResult = BleScanResult(
            macAddress = "11:22:33:44:55:66",
            advertisedName = "Generic Beacon",
            rssi = -45,
            serviceUuids = emptyList(),
            timestampEpochMs = 1_700_000_000_200L
        )

        fakeTransport.setScanResults(listOf(unrelatedAudioResult, emptyUuidResult, hrmResult))
        val service = BleDeviceDiscoveryService(
            bleTransport = fakeTransport,
            scope = backgroundScope
        )

        service.startScan()
        runCurrent()

        assertTrue(fakeTransport.isScanning)
        assertTrue(
            fakeTransport.recordedOperations.contains(
                BleRecordedOperation.ScanStarted(BleUuids.HEART_RATE_SERVICE_UUID)
            )
        )
        val expected = listOf(
            DiscoveredHrm(
                macAddress = "D4:36:39:11:22:9B",
                advertisedName = "COOSPO H808S",
                rssi = -54,
                lastSeenEpochMs = 1_700_000_000_000L
            )
        )
        assertEquals(expected, service.discoveredHrms.first())
        assertEquals(BleDiscoveryState.Scanning(expected), service.state.first())

        service.stopScan()
    }

    @Test
    fun `defensively filters non-HRM results from unfiltered transport`() = runTest {
        val hrm = BleScanResult(
            macAddress = "D4:36:39:11:22:9B",
            advertisedName = "Polar H10",
            rssi = -58,
            serviceUuids = listOf(BleUuids.HEART_RATE_SERVICE_UUID),
            timestampEpochMs = 1_000L
        )
        val nonHrmOtherUuid = BleScanResult(
            macAddress = "11:11:11:11:11:11",
            advertisedName = "Other Device",
            rssi = -40,
            serviceUuids = listOf(BleUuids.BATTERY_SERVICE_UUID),
            timestampEpochMs = 1_100L
        )
        val nonHrmEmptyUuids = BleScanResult(
            macAddress = "22:22:22:22:22:22",
            advertisedName = "Empty UUIDs",
            rssi = -35,
            serviceUuids = emptyList(),
            timestampEpochMs = 1_200L
        )

        var capturedFilter: UUID? = null
        val unfilteredTransport = object : BleTransport {
            override val isBluetoothEnabled: Boolean = true
            override val connectionState: StateFlow<BleConnectionState> =
                MutableStateFlow(BleConnectionState.Disconnected)
            override val characteristicNotifications: Flow<BleCharacteristicNotification> = emptyFlow()

            override fun scan(serviceUuidFilter: UUID?): Flow<BleScanResult> {
                capturedFilter = serviceUuidFilter
                return flowOf(nonHrmOtherUuid, nonHrmEmptyUuids, hrm)
            }

            override suspend fun stopScan() = Unit
            override suspend fun connect(macAddress: String, autoConnect: Boolean) =
                BleOperationResult.Success(Unit)

            override suspend fun disconnect(macAddress: String) = Unit
            override suspend fun discoverServices(macAddress: String) =
                BleOperationResult.Success(emptyList<BleDiscoveredService>())

            override suspend fun setCharacteristicNotification(
                macAddress: String,
                serviceUuid: UUID,
                characteristicUuid: UUID,
                enable: Boolean
            ) = BleOperationResult.Success(Unit)

            override suspend fun writeDescriptor(
                macAddress: String,
                serviceUuid: UUID,
                characteristicUuid: UUID,
                descriptorUuid: UUID,
                value: ByteArray
            ) = BleOperationResult.Success(Unit)

            override suspend fun readCharacteristic(
                macAddress: String,
                serviceUuid: UUID,
                characteristicUuid: UUID
            ) = BleOperationResult.Success(ByteArray(0))

            override suspend fun writeCharacteristic(
                macAddress: String,
                serviceUuid: UUID,
                characteristicUuid: UUID,
                value: ByteArray,
                writeType: Int
            ) = BleOperationResult.Success(Unit)

            override fun close() = Unit
        }

        val service = BleDeviceDiscoveryService(
            bleTransport = unfilteredTransport,
            scope = backgroundScope
        )
        service.startScan()
        runCurrent()

        assertEquals(BleUuids.HEART_RATE_SERVICE_UUID, capturedFilter)
        assertEquals(
            listOf(DiscoveredHrm("D4:36:39:11:22:9B", "Polar H10", -58, 1_000L)),
            service.discoveredHrms.value
        )
    }

    @Test
    fun `discoveredHrms deduplicates, preserves name, and sorts by RSSI`() =
        runTest {
            val service = BleDeviceDiscoveryService(
                bleTransport = fakeTransport,
                scope = backgroundScope
            )
            val emissions = Channel<List<DiscoveredHrm>>(Channel.UNLIMITED)
            val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
                service.discoveredHrms.collect { emissions.send(it) }
            }
            assertEquals(emptyList<DiscoveredHrm>(), emissions.receive())

            service.startScan()
            runCurrent()

            fakeTransport.emitScanResult(
                BleScanResult(
                    macAddress = "AA:BB:CC:DD:EE:02",
                    advertisedName = "Polar H10",
                    rssi = -72,
                    serviceUuids = listOf(BleUuids.HEART_RATE_SERVICE_UUID),
                    timestampEpochMs = 1_000L
                )
            )
            runCurrent()
            assertEquals(
                listOf(DiscoveredHrm("AA:BB:CC:DD:EE:02", "Polar H10", -72, 1_000L)),
                emissions.receive()
            )

            // Stronger signal device sorts ahead of weaker signal device
            fakeTransport.emitScanResult(
                BleScanResult(
                    macAddress = "AA:BB:CC:DD:EE:01",
                    advertisedName = "COOSPO H808S",
                    rssi = -55,
                    serviceUuids = listOf(BleUuids.HEART_RATE_SERVICE_UUID),
                    timestampEpochMs = 2_000L
                )
            )
            runCurrent()
            assertEquals(
                listOf(
                    DiscoveredHrm("AA:BB:CC:DD:EE:01", "COOSPO H808S", -55, 2_000L),
                    DiscoveredHrm("AA:BB:CC:DD:EE:02", "Polar H10", -72, 1_000L)
                ),
                emissions.receive()
            )

            // Follow-up packet with null advertisedName preserves "Polar H10" and updates RSSI to top
            fakeTransport.emitScanResult(
                BleScanResult(
                    macAddress = "AA:BB:CC:DD:EE:02",
                    advertisedName = null,
                    rssi = -48,
                    serviceUuids = listOf(BleUuids.HEART_RATE_SERVICE_UUID),
                    timestampEpochMs = 3_000L
                )
            )
            runCurrent()
            val afterUpdate = emissions.receive()
            assertEquals(
                listOf(
                    DiscoveredHrm("AA:BB:CC:DD:EE:02", "Polar H10", -48, 3_000L),
                    DiscoveredHrm("AA:BB:CC:DD:EE:01", "COOSPO H808S", -55, 2_000L)
                ),
                afterUpdate
            )
            assertEquals(BleDiscoveryState.Scanning(afterUpdate), service.state.value)

            // Equal RSSI tie-breaks by MAC address ascending
            fakeTransport.emitScanResult(
                BleScanResult(
                    macAddress = "AA:BB:CC:DD:EE:00",
                    advertisedName = "Garmin HRM-Pro",
                    rssi = -55,
                    serviceUuids = listOf(BleUuids.HEART_RATE_SERVICE_UUID),
                    timestampEpochMs = 4_000L
                )
            )
            runCurrent()
            assertEquals(
                listOf(
                    DiscoveredHrm("AA:BB:CC:DD:EE:02", "Polar H10", -48, 3_000L),
                    DiscoveredHrm("AA:BB:CC:DD:EE:00", "Garmin HRM-Pro", -55, 4_000L),
                    DiscoveredHrm("AA:BB:CC:DD:EE:01", "COOSPO H808S", -55, 2_000L)
                ),
                emissions.receive()
            )

            service.stopScan()
            collectJob.cancel()
        }

    @Test
    fun `connectToHrm stops active scan strictly before connect`() = runTest {
        val mac = "D4:36:39:11:22:9B"
        val hrm = BleScanResult(
            macAddress = mac,
            advertisedName = "COOSPO H808S",
            rssi = -52,
            serviceUuids = listOf(BleUuids.HEART_RATE_SERVICE_UUID),
            timestampEpochMs = 5_000L
        )
        fakeTransport.setScanResults(listOf(hrm))

        val service = BleDeviceDiscoveryService(
            bleTransport = fakeTransport,
            scope = backgroundScope
        )
        val states = Channel<BleDiscoveryState>(Channel.UNLIMITED)
        val stateJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            service.state.collect { states.send(it) }
        }
        assertEquals(BleDiscoveryState.Idle, states.receive())

        service.startScan()
        assertEquals(BleDiscoveryState.Scanning(emptyList()), states.receive())
        runCurrent()

        val expectedHrms = listOf(DiscoveredHrm(mac, "COOSPO H808S", -52, 5_000L))
        assertEquals(BleDiscoveryState.Scanning(expectedHrms), states.receive())
        assertTrue(fakeTransport.isScanning)

        val result = service.connectToHrm(mac, autoConnect = false)
        assertEquals(BleOperationResult.Success(Unit), result)
        assertFalse(fakeTransport.isScanning)
        assertEquals(BleDiscoveryState.Stopped(expectedHrms), states.receive())
        assertEquals(BleDiscoveryState.Stopped(expectedHrms), service.state.value)

        val ops = fakeTransport.recordedOperations
        val scanStartedIndex = ops.indexOf(BleRecordedOperation.ScanStarted(BleUuids.HEART_RATE_SERVICE_UUID))
        val scanStoppedIndex = ops.indexOf(BleRecordedOperation.ScanStopped)
        val connectIndex = ops.indexOf(BleRecordedOperation.Connect(mac, autoConnect = false))
        assertTrue("Expected ScanStarted in $ops", scanStartedIndex >= 0)
        assertTrue(
            "Expected ScanStopped before Connect in $ops",
            scanStoppedIndex in (scanStartedIndex + 1) until connectIndex
        )

        stateJob.cancel()
    }

    @Test
    fun `scan times out at 30s, records ScanStopped, retains devices`() =
        runTest {
            val hrm = BleScanResult(
                macAddress = "D4:36:39:11:22:9B",
                advertisedName = "COOSPO H808S",
                rssi = -60,
                serviceUuids = listOf(BleUuids.HEART_RATE_SERVICE_UUID),
                timestampEpochMs = 10_000L
            )
            fakeTransport.setScanResults(listOf(hrm))

            val service = BleDeviceDiscoveryService(
                bleTransport = fakeTransport,
                scope = backgroundScope
            )

            service.startScan()
            runCurrent()
            val expectedDevices = listOf(DiscoveredHrm("D4:36:39:11:22:9B", "COOSPO H808S", -60, 10_000L))
            assertTrue(fakeTransport.isScanning)
            assertEquals(BleDiscoveryState.Scanning(expectedDevices), service.state.value)

            advanceTimeBy(29_999L)
            runCurrent()
            assertTrue(fakeTransport.isScanning)
            assertEquals(BleDiscoveryState.Scanning(expectedDevices), service.state.value)

            advanceTimeBy(1L)
            runCurrent()
            assertFalse(fakeTransport.isScanning)
            assertTrue(fakeTransport.recordedOperations.contains(BleRecordedOperation.ScanStopped))
            assertEquals(BleDiscoveryState.TimedOut(expectedDevices), service.state.value)
            assertEquals(expectedDevices, service.discoveredHrms.value)
        }

    @Test
    fun `manual stopScan stops early and stays Stopped past 30s`() = runTest {
        val hrm = BleScanResult(
            macAddress = "A0:9E:1A:22:33:11",
            advertisedName = "Polar H10",
            rssi = -63,
            serviceUuids = listOf(BleUuids.HEART_RATE_SERVICE_UUID),
            timestampEpochMs = 20_000L
        )
        fakeTransport.setScanResults(listOf(hrm))

        val service = BleDeviceDiscoveryService(
            bleTransport = fakeTransport,
            scope = backgroundScope
        )
        service.startScan()
        runCurrent()

        advanceTimeBy(10_000L)
        runCurrent()
        service.stopScan()

        val expectedDevices = listOf(DiscoveredHrm("A0:9E:1A:22:33:11", "Polar H10", -63, 20_000L))
        assertFalse(fakeTransport.isScanning)
        assertTrue(fakeTransport.recordedOperations.contains(BleRecordedOperation.ScanStopped))
        assertEquals(BleDiscoveryState.Stopped(expectedDevices), service.state.value)

        // Advancing well past 30 seconds must not overwrite Stopped with TimedOut
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(BleDiscoveryState.Stopped(expectedDevices), service.state.value)
        assertEquals(1, fakeTransport.recordedOperations.count { it == BleRecordedOperation.ScanStopped })
    }

    @Test
    fun `disabled Bluetooth and missing permission emit Unavailable`() = runTest {
        var permissionStatus = BlePermissionStatus(
            hasScanPermission = false,
            hasConnectPermission = true,
            hasNotificationPermission = true
        )
        val permissionChecker = object : BlePermissionChecker {
            override fun checkPermissions(): BlePermissionStatus = permissionStatus
        }

        val service = BleDeviceDiscoveryService(
            bleTransport = fakeTransport,
            scope = backgroundScope,
            permissionChecker = permissionChecker
        )

        service.startScan()
        runCurrent()
        assertEquals(
            BleDiscoveryState.Unavailable(BleDiscoveryUnavailableReason.PERMISSION_DENIED),
            service.state.value
        )
        assertFalse(fakeTransport.isScanning)
        assertTrue(fakeTransport.recordedOperations.isEmpty())

        permissionStatus = BlePermissionStatus(
            hasScanPermission = true,
            hasConnectPermission = true,
            hasNotificationPermission = true
        )
        fakeTransport.isBluetoothEnabled = false

        service.startScan()
        runCurrent()
        assertEquals(
            BleDiscoveryState.Unavailable(BleDiscoveryUnavailableReason.BLUETOOTH_DISABLED),
            service.state.value
        )
        assertFalse(fakeTransport.isScanning)
        assertTrue(fakeTransport.recordedOperations.isEmpty())

        // Enabling Bluetooth and starting scan works, then disabling mid-scan and restarting cancels scan
        fakeTransport.isBluetoothEnabled = true
        service.startScan()
        runCurrent()
        assertTrue(fakeTransport.isScanning)
        assertEquals(BleDiscoveryState.Scanning(emptyList()), service.state.value)

        fakeTransport.isBluetoothEnabled = false
        service.startScan()
        runCurrent()
        assertFalse(fakeTransport.isScanning)
        assertEquals(
            BleDiscoveryState.Unavailable(BleDiscoveryUnavailableReason.BLUETOOTH_DISABLED),
            service.state.value
        )
    }

    @Test
    fun `restarting startScan clears stale devices and resets 30s window`() = runTest {
        val staleHrm = BleScanResult(
            macAddress = "AA:BB:CC:DD:EE:01",
            advertisedName = "Stale HRM",
            rssi = -70,
            serviceUuids = listOf(BleUuids.HEART_RATE_SERVICE_UUID),
            timestampEpochMs = 1_000L
        )
        val freshHrm = BleScanResult(
            macAddress = "AA:BB:CC:DD:EE:02",
            advertisedName = "Fresh HRM",
            rssi = -50,
            serviceUuids = listOf(BleUuids.HEART_RATE_SERVICE_UUID),
            timestampEpochMs = 21_000L
        )

        fakeTransport.setScanResults(listOf(staleHrm))
        val service = BleDeviceDiscoveryService(
            bleTransport = fakeTransport,
            scope = backgroundScope
        )

        service.startScan()
        runCurrent()
        assertEquals(
            listOf(DiscoveredHrm("AA:BB:CC:DD:EE:01", "Stale HRM", -70, 1_000L)),
            service.discoveredHrms.value
        )

        // Advance 20 seconds into the first window, then restart scan
        advanceTimeBy(20_000L)
        runCurrent()
        fakeTransport.setScanResults(listOf(freshHrm))

        service.startScan()
        assertEquals(emptyList<DiscoveredHrm>(), service.discoveredHrms.value)
        assertEquals(BleDiscoveryState.Scanning(emptyList()), service.state.value)

        runCurrent()
        val expectedFresh = listOf(DiscoveredHrm("AA:BB:CC:DD:EE:02", "Fresh HRM", -50, 21_000L))
        assertEquals(expectedFresh, service.discoveredHrms.value)
        assertTrue(fakeTransport.isScanning)

        // At t = 30s (10s into new scan), the old 30s timeout must NOT fire
        advanceTimeBy(10_000L)
        runCurrent()
        assertTrue(fakeTransport.isScanning)
        assertEquals(BleDiscoveryState.Scanning(expectedFresh), service.state.value)

        // At t = 49_999ms (29_999ms into new scan), still scanning
        advanceTimeBy(19_999L)
        runCurrent()
        assertTrue(fakeTransport.isScanning)
        assertEquals(BleDiscoveryState.Scanning(expectedFresh), service.state.value)

        // At t = 50_000ms (30_000ms into new scan), times out cleanly
        advanceTimeBy(1L)
        runCurrent()
        assertFalse(fakeTransport.isScanning)
        assertEquals(BleDiscoveryState.TimedOut(expectedFresh), service.state.value)
    }

    @Test
    fun `stopScan preserves non-Scanning states and connect works after timeout`() =
        runTest {
            val service = BleDeviceDiscoveryService(
                bleTransport = fakeTransport,
                scope = backgroundScope
            )

            // Idle stays Idle
            service.stopScan()
            assertEquals(BleDiscoveryState.Idle, service.state.value)

            // TimedOut stays TimedOut, and connectToHrm still connects
            val hrm = BleScanResult(
                macAddress = "D4:36:39:11:22:9B",
                advertisedName = "COOSPO H808S",
                rssi = -59,
                serviceUuids = listOf(BleUuids.HEART_RATE_SERVICE_UUID),
                timestampEpochMs = 1_000L
            )
            fakeTransport.setScanResults(listOf(hrm))
            service.startScan()
            advanceTimeBy(BleDeviceDiscoveryService.DEFAULT_SCAN_TIMEOUT_MS)
            runCurrent()

            val expected = listOf(DiscoveredHrm("D4:36:39:11:22:9B", "COOSPO H808S", -59, 1_000L))
            assertEquals(BleDiscoveryState.TimedOut(expected), service.state.value)

            service.stopScan()
            assertEquals(BleDiscoveryState.TimedOut(expected), service.state.value)

            val connectResult = service.connectToHrm("D4:36:39:11:22:9B")
            assertEquals(BleOperationResult.Success(Unit), connectResult)
            assertEquals(BleDiscoveryState.TimedOut(expected), service.state.value)

            // Unavailable stays Unavailable
            fakeTransport.isBluetoothEnabled = false
            service.startScan()
            assertEquals(
                BleDiscoveryState.Unavailable(BleDiscoveryUnavailableReason.BLUETOOTH_DISABLED),
                service.state.value
            )
            service.stopScan()
            assertEquals(
                BleDiscoveryState.Unavailable(BleDiscoveryUnavailableReason.BLUETOOTH_DISABLED),
                service.state.value
            )
        }

    @Test(expected = IllegalArgumentException::class)
    fun `non-positive scanTimeoutMs throws IllegalArgumentException`() = runTest {
        BleDeviceDiscoveryService(
            bleTransport = fakeTransport,
            scope = backgroundScope,
            scanTimeoutMs = 0L
        )
    }
}
