package com.example.heartzonemonitor.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.example.heartzonemonitor.data.ble.BleDeviceDiscoveryService
import com.example.heartzonemonitor.data.ble.BleDiscoveryState
import com.example.heartzonemonitor.data.ble.BleOperationResult
import com.example.heartzonemonitor.data.ble.BlePermissionChecker
import com.example.heartzonemonitor.data.ble.BlePermissionStatus
import com.example.heartzonemonitor.data.ble.BleRecordedOperation
import com.example.heartzonemonitor.data.ble.BleScanResult
import com.example.heartzonemonitor.data.ble.BleUuids
import com.example.heartzonemonitor.data.ble.DiscoveredHrm
import com.example.heartzonemonitor.data.ble.FakeBleTransport
import com.example.heartzonemonitor.data.local.FakeRememberedHrmDao
import com.example.heartzonemonitor.data.local.dao.RememberedHrmDao
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingPairingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val fixedEpochMs = 1_750_000_000_000L

    private lateinit var fakeTransport: FakeBleTransport
    private lateinit var fakeRememberedHrmDao: FakeRememberedHrmDao
    private lateinit var permissionChecker: MutableBlePermissionChecker

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeTransport = FakeBleTransport()
        fakeRememberedHrmDao = FakeRememberedHrmDao()
        permissionChecker = MutableBlePermissionChecker(
            BlePermissionStatus(
                hasScanPermission = true,
                hasConnectPermission = true,
                hasNotificationPermission = false
            )
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `when BLE permissions are granted, auto-starts 0x180D scan and streams sorted HRMs`() =
        runTest(testDispatcher) {
            val hrmStrong = BleScanResult(
                macAddress = "D4:36:39:88:21:9B",
                advertisedName = "COOSPO H808S",
                rssi = -54,
                serviceUuids = listOf(BleUuids.HEART_RATE_SERVICE_UUID),
                timestampEpochMs = 1_000L
            )
            val hrmWeak = BleScanResult(
                macAddress = "A0:9E:1A:33:12:11",
                advertisedName = "Polar H10",
                rssi = -78,
                serviceUuids = listOf(BleUuids.HEART_RATE_SERVICE_UUID),
                timestampEpochMs = 1_100L
            )
            val nonHrm = BleScanResult(
                macAddress = "11:22:33:44:55:66",
                advertisedName = "Audio Headset",
                rssi = -40,
                serviceUuids = listOf(UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb")),
                timestampEpochMs = 1_200L
            )
            fakeTransport.setScanResults(listOf(nonHrm, hrmWeak, hrmStrong))

            val discoveryService = BleDeviceDiscoveryService(
                bleTransport = fakeTransport,
                scope = backgroundScope,
                permissionChecker = permissionChecker
            )
            val viewModel = OnboardingPairingViewModel(
                discoveryService = discoveryService,
                rememberedHrmDao = fakeRememberedHrmDao,
                permissionChecker = permissionChecker,
                nowEpochMs = { fixedEpochMs }
            )

            runCurrent()

            assertTrue(fakeTransport.isScanning)
            assertEquals(
                listOf(BleRecordedOperation.ScanStarted(BleUuids.HEART_RATE_SERVICE_UUID)),
                fakeTransport.recordedOperations
            )
            val state = viewModel.uiState.value
            assertTrue(state.isBlePermissionGranted)
            assertTrue(state.isScanning)
            assertEquals(
                listOf(
                    DiscoveredHrm("D4:36:39:88:21:9B", "COOSPO H808S", -54, 1_000L),
                    DiscoveredHrm("A0:9E:1A:33:12:11", "Polar H10", -78, 1_100L)
                ),
                state.discoveredHrms
            )

            discoveryService.stopScan()
        }

    @Test
    fun `when permissions are initially denied, waits until granted via onPermissionsUpdated to start scan`() =
        runTest(testDispatcher) {
            permissionChecker.status = BlePermissionStatus(
                hasScanPermission = false,
                hasConnectPermission = false,
                hasNotificationPermission = false
            )
            val hrm = BleScanResult(
                macAddress = "D4:36:39:88:21:9B",
                advertisedName = "COOSPO H808S",
                rssi = -56,
                serviceUuids = listOf(BleUuids.HEART_RATE_SERVICE_UUID),
                timestampEpochMs = 1_000L
            )
            fakeTransport.setScanResults(listOf(hrm))

            val discoveryService = BleDeviceDiscoveryService(
                bleTransport = fakeTransport,
                scope = backgroundScope,
                permissionChecker = permissionChecker
            )
            val viewModel = OnboardingPairingViewModel(
                discoveryService = discoveryService,
                rememberedHrmDao = fakeRememberedHrmDao,
                permissionChecker = permissionChecker,
                nowEpochMs = { fixedEpochMs }
            )

            runCurrent()

            assertFalse(fakeTransport.isScanning)
            assertTrue(fakeTransport.recordedOperations.isEmpty())
            assertEquals(BleDiscoveryState.Idle, viewModel.uiState.value.discoveryState)
            assertFalse(viewModel.uiState.value.isBlePermissionGranted)

            val grantedStatus = BlePermissionStatus(
                hasScanPermission = true,
                hasConnectPermission = true,
                hasNotificationPermission = false
            )
            permissionChecker.status = grantedStatus
            viewModel.onPermissionsUpdated(grantedStatus)
            runCurrent()

            assertTrue(fakeTransport.isScanning)
            assertEquals(
                listOf(BleRecordedOperation.ScanStarted(BleUuids.HEART_RATE_SERVICE_UUID)),
                fakeTransport.recordedOperations
            )
            assertTrue(viewModel.uiState.value.isBlePermissionGranted)
            assertTrue(viewModel.uiState.value.isScanning)
            assertEquals(1, viewModel.uiState.value.discoveredHrms.size)

            // Re-notifying the same granted status while already scanning must not double-restart
            viewModel.onPermissionsUpdated(grantedStatus)
            runCurrent()
            assertEquals(
                1,
                fakeTransport.recordedOperations.count { it is BleRecordedOperation.ScanStarted }
            )

            discoveryService.stopScan()
        }

    @Test
    fun `pairDevice stops scan prior to connect, persists Active HRM, and marks pairingCompleted`() =
        runTest(testDispatcher) {
            val mac = "D4:36:39:88:21:9B"
            fakeTransport.setScanResults(
                listOf(
                    BleScanResult(
                        macAddress = mac,
                        advertisedName = "COOSPO H808S",
                        rssi = -54,
                        serviceUuids = listOf(BleUuids.HEART_RATE_SERVICE_UUID),
                        timestampEpochMs = 2_000L
                    )
                )
            )
            val discoveryService = BleDeviceDiscoveryService(
                bleTransport = fakeTransport,
                scope = backgroundScope,
                permissionChecker = permissionChecker
            )
            val viewModel = OnboardingPairingViewModel(
                discoveryService = discoveryService,
                rememberedHrmDao = fakeRememberedHrmDao,
                permissionChecker = permissionChecker,
                nowEpochMs = { fixedEpochMs }
            )

            runCurrent()
            val discovered = viewModel.uiState.value.discoveredHrms.single()

            val states = Channel<OnboardingPairingUiState>(Channel.UNLIMITED)
            val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect { states.send(it) }
            }
            // Drain initial state
            states.receive()

            viewModel.pairDevice(discovered)
            runCurrent()

            val finalState = viewModel.uiState.value
            assertTrue(finalState.pairingCompleted)
            assertNull(finalState.pairingMacAddress)
            assertNull(finalState.errorMessage)
            assertFalse(fakeTransport.isScanning)

            // Verify ScanStopped strictly precedes Connect to prevent GATT Error 133
            val ops = fakeTransport.recordedOperations
            val scanStartedIndex = ops.indexOf(
                BleRecordedOperation.ScanStarted(BleUuids.HEART_RATE_SERVICE_UUID)
            )
            val scanStoppedIndex = ops.indexOf(BleRecordedOperation.ScanStopped)
            val connectIndex = ops.indexOf(BleRecordedOperation.Connect(mac, autoConnect = false))
            assertTrue("Expected ScanStarted in $ops", scanStartedIndex >= 0)
            assertTrue(
                "Expected ScanStopped before Connect in $ops",
                scanStoppedIndex in (scanStartedIndex + 1) until connectIndex
            )

            // Verify persistence in remembered_hrms with isActive = true
            val active = fakeRememberedHrmDao.getActiveHrm()
            assertEquals(
                RememberedHrmEntity(
                    macAddress = mac,
                    advertisedName = "COOSPO H808S",
                    customNickname = null,
                    lastSeenTimestamp = fixedEpochMs,
                    batteryLevel = null,
                    isActive = true
                ),
                active
            )

            collectJob.cancel()
        }

    @Test
    fun `pairDevice uses default Heart Rate Monitor name when advertisedName is null or blank`() =
        runTest(testDispatcher) {
            val discoveryService = BleDeviceDiscoveryService(
                bleTransport = fakeTransport,
                scope = backgroundScope,
                permissionChecker = permissionChecker
            )
            val viewModel = OnboardingPairingViewModel(
                discoveryService = discoveryService,
                rememberedHrmDao = fakeRememberedHrmDao,
                permissionChecker = permissionChecker,
                nowEpochMs = { fixedEpochMs }
            )
            runCurrent()

            val unnamed = DiscoveredHrm(
                macAddress = "AA:BB:CC:DD:EE:01",
                advertisedName = "   ",
                rssi = -62,
                lastSeenEpochMs = 3_000L
            )
            viewModel.pairDevice(unnamed)
            runCurrent()

            assertTrue(viewModel.uiState.value.pairingCompleted)
            val active = fakeRememberedHrmDao.getActiveHrm()
            assertNotNull(active)
            assertEquals("Heart Rate Monitor", active?.advertisedName)
            assertTrue(active?.isActive == true)
        }

    @Test
    fun `pairDevice ignores concurrent taps while connection is in flight`() =
        runTest(testDispatcher) {
            val firstConnectGate = CompletableDeferred<BleOperationResult<Unit>>()
            fakeTransport.onConnectAttempt = { _, _ -> firstConnectGate.await() }

            val discoveryService = BleDeviceDiscoveryService(
                bleTransport = fakeTransport,
                scope = backgroundScope,
                permissionChecker = permissionChecker
            )
            val viewModel = OnboardingPairingViewModel(
                discoveryService = discoveryService,
                rememberedHrmDao = fakeRememberedHrmDao,
                permissionChecker = permissionChecker,
                nowEpochMs = { fixedEpochMs }
            )
            runCurrent()

            val firstDevice = DiscoveredHrm("D4:36:39:88:21:9B", "COOSPO H808S", -54, 1_000L)
            val secondDevice = DiscoveredHrm("A0:9E:1A:33:12:11", "Polar H10", -65, 1_100L)

            viewModel.pairDevice(firstDevice)
            assertEquals(firstDevice.macAddress, viewModel.uiState.value.pairingMacAddress)
            assertTrue(viewModel.uiState.value.isPairing)

            // Concurrent second tap must be ignored
            viewModel.pairDevice(secondDevice)
            runCurrent()
            assertEquals(firstDevice.macAddress, viewModel.uiState.value.pairingMacAddress)

            firstConnectGate.complete(BleOperationResult.Success(Unit))
            runCurrent()

            assertTrue(viewModel.uiState.value.pairingCompleted)
            assertNull(viewModel.uiState.value.pairingMacAddress)
            val connectOps = fakeTransport.recordedOperations.filterIsInstance<BleRecordedOperation.Connect>()
            assertEquals(1, connectOps.size)
            assertEquals(firstDevice.macAddress, connectOps.single().macAddress)
            assertEquals(firstDevice.macAddress, fakeRememberedHrmDao.getActiveHrm()?.macAddress)
        }

    @Test
    fun `pairDevice failure clears pairing state, sets inline error, and does not persist or complete`() =
        runTest(testDispatcher) {
            val mac = "D4:36:39:88:21:9B"
            fakeTransport.setConnectResult(
                BleOperationResult.Failure(status = 133, message = "GATT connection failed (status 133)"),
                macAddress = mac
            )
            val discoveryService = BleDeviceDiscoveryService(
                bleTransport = fakeTransport,
                scope = backgroundScope,
                permissionChecker = permissionChecker
            )
            val viewModel = OnboardingPairingViewModel(
                discoveryService = discoveryService,
                rememberedHrmDao = fakeRememberedHrmDao,
                permissionChecker = permissionChecker,
                nowEpochMs = { fixedEpochMs }
            )
            runCurrent()

            val device = DiscoveredHrm(mac, "COOSPO H808S", -58, 1_000L)
            viewModel.pairDevice(device)
            runCurrent()

            val state = viewModel.uiState.value
            assertFalse(state.pairingCompleted)
            assertNull(state.pairingMacAddress)
            assertNotNull(state.errorMessage)
            assertTrue(state.errorMessage!!.contains("COOSPO H808S"))
            assertTrue(fakeRememberedHrmDao.getAllHrms().isEmpty())

            viewModel.dismissError()
            assertNull(viewModel.uiState.value.errorMessage)
        }

    @Test
    fun `pairDevice database exception reports error without marking pairingCompleted`() =
        runTest(testDispatcher) {
            val failingDao = FailingUpsertRememberedHrmDao("Encrypted storage unavailable")
            val discoveryService = BleDeviceDiscoveryService(
                bleTransport = fakeTransport,
                scope = backgroundScope,
                permissionChecker = permissionChecker
            )
            val viewModel = OnboardingPairingViewModel(
                discoveryService = discoveryService,
                rememberedHrmDao = failingDao,
                permissionChecker = permissionChecker,
                nowEpochMs = { fixedEpochMs }
            )
            runCurrent()

            val device = DiscoveredHrm("D4:36:39:88:21:9B", "COOSPO H808S", -55, 1_000L)
            viewModel.pairDevice(device)
            runCurrent()

            assertFalse(viewModel.uiState.value.pairingCompleted)
            assertNull(viewModel.uiState.value.pairingMacAddress)
            assertEquals("Encrypted storage unavailable", viewModel.uiState.value.errorMessage)
        }

    @Test
    fun `onPairLater stops active scan cleanly without persisting any HRM`() =
        runTest(testDispatcher) {
            val discoveryService = BleDeviceDiscoveryService(
                bleTransport = fakeTransport,
                scope = backgroundScope,
                permissionChecker = permissionChecker
            )
            val viewModel = OnboardingPairingViewModel(
                discoveryService = discoveryService,
                rememberedHrmDao = fakeRememberedHrmDao,
                permissionChecker = permissionChecker,
                nowEpochMs = { fixedEpochMs }
            )
            runCurrent()
            assertTrue(fakeTransport.isScanning)

            viewModel.onPairLater()
            runCurrent()

            assertFalse(fakeTransport.isScanning)
            assertTrue(fakeTransport.recordedOperations.contains(BleRecordedOperation.ScanStopped))
            assertTrue(fakeRememberedHrmDao.getAllHrms().isEmpty())
            assertFalse(viewModel.uiState.value.pairingCompleted)
        }

    @Test
    fun `startOrRestartScan recovers from timeout and discovers fresh HRMs`() =
        runTest(testDispatcher) {
            val discoveryService = BleDeviceDiscoveryService(
                bleTransport = fakeTransport,
                scope = backgroundScope,
                permissionChecker = permissionChecker
            )
            val viewModel = OnboardingPairingViewModel(
                discoveryService = discoveryService,
                rememberedHrmDao = fakeRememberedHrmDao,
                permissionChecker = permissionChecker,
                nowEpochMs = { fixedEpochMs }
            )
            runCurrent()

            advanceTimeBy(BleDeviceDiscoveryService.DEFAULT_SCAN_TIMEOUT_MS)
            runCurrent()
            assertTrue(viewModel.uiState.value.discoveryState is BleDiscoveryState.TimedOut)

            fakeTransport.setScanResults(
                listOf(
                    BleScanResult(
                        macAddress = "C2:11:85:44:90:4A",
                        advertisedName = "Garmin HRM-Pro",
                        rssi = -61,
                        serviceUuids = listOf(BleUuids.HEART_RATE_SERVICE_UUID),
                        timestampEpochMs = 31_000L
                    )
                )
            )
            viewModel.startOrRestartScan()
            runCurrent()

            assertTrue(viewModel.uiState.value.isScanning)
            assertEquals(1, viewModel.uiState.value.discoveredHrms.size)
            assertEquals("Garmin HRM-Pro", viewModel.uiState.value.discoveredHrms.single().advertisedName)

            discoveryService.stopScan()
        }

    @Test
    fun `clearing ViewModel stops active scan cleanly even after viewModelScope cancellation`() =
        runTest(testDispatcher) {
            val discoveryService = BleDeviceDiscoveryService(
                bleTransport = fakeTransport,
                scope = backgroundScope,
                permissionChecker = permissionChecker
            )
            val store = ViewModelStore()
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    OnboardingPairingViewModel(
                        discoveryService = discoveryService,
                        rememberedHrmDao = fakeRememberedHrmDao,
                        permissionChecker = permissionChecker,
                        nowEpochMs = { fixedEpochMs }
                    ) as T
            }
            ViewModelProvider(store, factory)[OnboardingPairingViewModel::class.java]
            runCurrent()
            assertTrue(fakeTransport.isScanning)

            // Triggers ViewModel.clear() -> cancels viewModelScope -> calls onCleared()
            store.clear()
            advanceUntilIdle()

            assertFalse(fakeTransport.isScanning)
            assertTrue(fakeTransport.recordedOperations.contains(BleRecordedOperation.ScanStopped))
        }

    @Test
    fun `formatMacSnippet and rssiToSignalBars handle standard and boundary values`() {
        assertEquals("AA:BB:CC:...:12", formatMacSnippet("AA:BB:CC:DD:EE:12"))
        assertEquals("D4:36:39:...:9B", formatMacSnippet("D4:36:39:88:21:9B"))
        assertEquals("SHORT", formatMacSnippet("SHORT"))

        assertEquals(4, rssiToSignalBars(-54))
        assertEquals(4, rssiToSignalBars(-60))
        assertEquals(3, rssiToSignalBars(-61))
        assertEquals(3, rssiToSignalBars(-72))
        assertEquals(2, rssiToSignalBars(-73))
        assertEquals(2, rssiToSignalBars(-82))
        assertEquals(1, rssiToSignalBars(-83))
        assertEquals(1, rssiToSignalBars(-92))
    }

    private class MutableBlePermissionChecker(
        var status: BlePermissionStatus
    ) : BlePermissionChecker {
        override fun checkPermissions(): BlePermissionStatus = status
    }

    private class FailingUpsertRememberedHrmDao(
        private val failureMessage: String
    ) : RememberedHrmDao {
        override fun observeActiveHrm(): Flow<RememberedHrmEntity?> = flowOf(null)
        override suspend fun getActiveHrm(): RememberedHrmEntity? = null
        override fun observeAllHrms(): Flow<List<RememberedHrmEntity>> = flowOf(emptyList())
        override suspend fun getAllHrms(): List<RememberedHrmEntity> = emptyList()
        override suspend fun getHrmByMacAddress(macAddress: String): RememberedHrmEntity? = null
        override suspend fun getActiveHrmCount(): Int = 0
        override suspend fun upsertHrmRecord(hrm: RememberedHrmEntity) {
            throw RuntimeException(failureMessage)
        }
        override suspend fun upsertHrm(hrm: RememberedHrmEntity) {
            throw RuntimeException(failureMessage)
        }
        override suspend fun clearActiveHrms() = Unit
        override suspend fun deactivateOtherHrms(macAddress: String) = Unit
        override suspend fun markHrmAsActive(macAddress: String): Int = 0
        override suspend fun updateNickname(macAddress: String, customNickname: String?): Int = 0
        override suspend fun updateBatteryLevel(macAddress: String, batteryLevel: Int?): Int = 0
        override suspend fun updateLastSeenTimestamp(macAddress: String, lastSeenTimestamp: Long): Int = 0
        override suspend fun deleteHrmByMacAddress(macAddress: String): Int = 0
        override suspend fun deleteHrm(hrm: RememberedHrmEntity): Int = 0
        override suspend fun deleteAllHrms() = Unit
    }
}
