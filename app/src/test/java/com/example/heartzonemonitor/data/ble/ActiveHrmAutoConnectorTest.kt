package com.example.heartzonemonitor.data.ble

import com.example.heartzonemonitor.data.local.FakeHeartRateSampleDao
import com.example.heartzonemonitor.data.local.FakeRememberedHrmDao
import com.example.heartzonemonitor.data.local.FakeSessionDao
import com.example.heartzonemonitor.data.local.FakeUserProfileDao
import com.example.heartzonemonitor.data.local.HeartRateSampleBuffer
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import com.example.heartzonemonitor.data.session.SessionRepository
import com.example.heartzonemonitor.data.session.SessionUiState
import com.example.heartzonemonitor.domain.model.HeartZone
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import com.example.heartzonemonitor.domain.timing.FakeSessionClock
import com.example.heartzonemonitor.domain.timing.MonotonicDurationEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveHrmAutoConnectorTest {

    @Test
    fun `autoConnect false connects, initializes GATT, and streams telemetry`() =
        runTest(StandardTestDispatcher()) {
            val activeHrm = RememberedHrmEntity(
                macAddress = PRIMARY_MAC,
                advertisedName = "COOSPO H808S",
                customNickname = "Chest Strap",
                lastSeenTimestamp = 1_700_000_000_000L,
                batteryLevel = null,
                isActive = true
            )
            val profile = defaultProfile()
            val bleTransport = FakeBleTransport().apply {
                stubCharacteristicRead(
                    characteristicUuid = BleUuids.BATTERY_LEVEL_CHAR_UUID,
                    serviceUuid = BleUuids.BATTERY_SERVICE_UUID,
                    result = BleOperationResult.Success(byteArrayOf(92.toByte()))
                )
            }
            val connectGate = CompletableDeferred<BleOperationResult<Unit>>()
            bleTransport.onConnectAttempt = { _, _ -> connectGate.await() }

            val hrmDao = FakeRememberedHrmDao(listOf(activeHrm))
            val profileDao = FakeUserProfileDao(initialProfile = profile)
            val sampleDao = FakeHeartRateSampleDao()
            val sessionDao = FakeSessionDao(sampleDao = sampleDao)
            val clock = FakeSessionClock(initialRealtimeMs = 1_000L, initialEpochMs = 1_700_000_000_000L)
            val sampleBuffer = HeartRateSampleBuffer(sampleDao = sampleDao, scope = this)

            val sharedGattClient = HrmGattClient(
                bleTransport = bleTransport,
                scope = this,
                onBatteryLevelUpdated = { mac, level ->
                    hrmDao.updateBatteryLevel(mac, level)
                }
            )
            val repository = SessionRepository(
                bleTransport = bleTransport,
                rememberedHrmDao = hrmDao,
                userProfileDao = profileDao,
                sessionDao = sessionDao,
                sampleDao = sampleDao,
                scope = this,
                hrmGattClient = sharedGattClient,
                clock = clock,
                durationEngine = MonotonicDurationEngine(clock),
                sampleBuffer = sampleBuffer,
                currentYearProvider = { 2026 }
            )
            val connector = ActiveHrmAutoConnector(
                bleTransport = bleTransport,
                hrmGattClient = repository.hrmGattClient,
                rememberedHrmDao = hrmDao,
                scope = this,
                isSessionActiveProvider = { repository.isSessionActive },
                sessionConnectionStateProvider = { repository.connectionState.value }
            )

            val emissions = Channel<AutoConnectState>(Channel.UNLIMITED)
            val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
                connector.state.collect { emissions.send(it) }
            }

            try {
                assertEquals(AutoConnectState.Idle, emissions.receive())

                // Non-blocking initiation
                val job = connector.startAutoConnect()
                assertTrue(job.isActive)
                runCurrent()

                assertEquals(
                    AutoConnectState.Connecting(PRIMARY_MAC, "Chest Strap"),
                    emissions.receive()
                )
                assertTrue(
                    bleTransport.recordedOperations.contains(
                        BleRecordedOperation.Connect(PRIMARY_MAC, autoConnect = false)
                    )
                )

                // Complete direct connection; GATT init runs automatically
                connectGate.complete(BleOperationResult.Success(Unit))
                runCurrent()

                assertEquals(
                    AutoConnectState.Connected(PRIMARY_MAC, "Chest Strap"),
                    emissions.receive()
                )

                val ops = bleTransport.recordedOperations
                assertEquals(
                    listOf(
                        BleRecordedOperation.Connect(PRIMARY_MAC, autoConnect = false),
                        BleRecordedOperation.DiscoverServices(PRIMARY_MAC),
                        BleRecordedOperation.SetCharacteristicNotification(
                            macAddress = PRIMARY_MAC,
                            serviceUuid = BleUuids.HEART_RATE_SERVICE_UUID,
                            characteristicUuid = BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID,
                            enable = true
                        ),
                        BleRecordedOperation.WriteDescriptor(
                            macAddress = PRIMARY_MAC,
                            serviceUuid = BleUuids.HEART_RATE_SERVICE_UUID,
                            characteristicUuid = BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID,
                            descriptorUuid = BleUuids.CCCD_DESCRIPTOR_UUID,
                            value = HrmGattClient.CCCD_ENABLE_NOTIFICATION_VALUE
                        ),
                        BleRecordedOperation.ReadCharacteristic(
                            macAddress = PRIMARY_MAC,
                            serviceUuid = BleUuids.BATTERY_SERVICE_UUID,
                            characteristicUuid = BleUuids.BATTERY_LEVEL_CHAR_UUID
                        )
                    ),
                    ops
                )

                // Battery persisted & reflected, and live telemetry flows into SessionRepository
                assertEquals(92, hrmDao.getActiveHrm()?.batteryLevel)

                bleTransport.emitNotification(
                    macAddress = PRIMARY_MAC,
                    value = FakeBleTransport.buildHeartRateMeasurementPacket(
                        bpm = 148,
                        sensorContactSupported = true,
                        sensorContactDetected = true
                    ),
                    timestampEpochMs = clock.currentEpochMs()
                )
                runCurrent()

                val repoIdle = repository.uiState.value as SessionUiState.Idle
                assertEquals(148, repoIdle.currentBpm)
                assertEquals(HeartZone.ZONE_3, repoIdle.currentZone)
                assertEquals(92, repoIdle.batteryLevel)
                assertTrue(repoIdle.canStartSession)
            } finally {
                collectJob.cancel()
                connector.close()
                sampleBuffer.discard()
                sharedGattClient.close()
                repository.close()
            }
        }

    @Test
    fun `resolveHrmDisplayName falls back from nickname to advertised name to MAC`() {
        val withNickname = RememberedHrmEntity(
            macAddress = PRIMARY_MAC,
            advertisedName = "COOSPO H808S",
            customNickname = "Race Strap",
            lastSeenTimestamp = 1_000L,
            isActive = true
        )
        val withBlankNickname = withNickname.copy(customNickname = "   ")
        val withNullNickname = withNickname.copy(customNickname = null)
        val withBlankAdvertisedName = withNickname.copy(customNickname = null, advertisedName = "  ")

        assertEquals("Race Strap", resolveHrmDisplayName(withNickname))
        assertEquals("COOSPO H808S", resolveHrmDisplayName(withBlankNickname))
        assertEquals("COOSPO H808S", resolveHrmDisplayName(withNullNickname))
        assertEquals(PRIMARY_MAC, resolveHrmDisplayName(withBlankAdvertisedName))
    }

    @Test
    fun `10-second timeout transitions to DormantPrompt and disconnects`() =
        runTest(StandardTestDispatcher()) {
            assertEquals(10_000L, ActiveHrmAutoConnector.DEFAULT_AUTO_CONNECT_TIMEOUT_MS)

            val hrm = RememberedHrmEntity(
                macAddress = PRIMARY_MAC,
                advertisedName = "COOSPO H808S",
                customNickname = null,
                lastSeenTimestamp = 1_000L,
                isActive = true
            )
            val bleTransport = FakeBleTransport().apply {
                onConnectAttempt = { _, _ -> awaitCancellation() }
            }
            val gattClient = HrmGattClient(bleTransport = bleTransport, scope = this)
            val hrmDao = FakeRememberedHrmDao(listOf(hrm))
            val connector = ActiveHrmAutoConnector(
                bleTransport = bleTransport,
                hrmGattClient = gattClient,
                rememberedHrmDao = hrmDao,
                scope = this
            )

            try {
                connector.startAutoConnect()
                runCurrent()

                assertEquals(
                    AutoConnectState.Connecting(PRIMARY_MAC, "COOSPO H808S"),
                    connector.state.value
                )
                assertEquals(BleConnectionState.Connecting(PRIMARY_MAC), bleTransport.connectionState.value)

                // Still connecting at 9,999ms
                advanceTimeBy(9_999L)
                runCurrent()
                assertEquals(
                    AutoConnectState.Connecting(PRIMARY_MAC, "COOSPO H808S"),
                    connector.state.value
                )
                assertFalse(bleTransport.recordedOperations.contains(BleRecordedOperation.Disconnect(PRIMARY_MAC)))

                // Transitions to DormantPrompt and cleans up at exactly 10,000ms
                advanceTimeBy(1L)
                runCurrent()
                assertEquals(
                    AutoConnectState.DormantPrompt(PRIMARY_MAC, "COOSPO H808S"),
                    connector.state.value
                )
                assertEquals(BleConnectionState.Disconnected, bleTransport.connectionState.value)
                assertEquals(
                    listOf(
                        BleRecordedOperation.Connect(PRIMARY_MAC, autoConnect = false),
                        BleRecordedOperation.Disconnect(PRIMARY_MAC)
                    ),
                    bleTransport.recordedOperations
                )
            } finally {
                connector.close()
                gattClient.close()
            }
        }

    @Test
    fun `timeout during GATT init disconnects partial link and emits DormantPrompt`() =
        runTest(StandardTestDispatcher()) {
            val hrm = RememberedHrmEntity(
                macAddress = PRIMARY_MAC,
                advertisedName = "COOSPO H808S",
                customNickname = "Strap",
                lastSeenTimestamp = 1_000L,
                isActive = true
            )
            val bleTransport = FakeBleTransport()
            val commandQueue = GattCommandQueue()
            val gattClient = HrmGattClient(
                bleTransport = bleTransport,
                scope = this,
                commandQueue = commandQueue
            )
            val hrmDao = FakeRememberedHrmDao(listOf(hrm))
            val connector = ActiveHrmAutoConnector(
                bleTransport = bleTransport,
                hrmGattClient = gattClient,
                rememberedHrmDao = hrmDao,
                scope = this,
                autoConnectTimeoutMs = 10_000L
            )

            // Hold the GATT command queue so discoverServices suspends until timeout fires
            val queueBlocker = launch {
                commandQueue.enqueue { awaitCancellation() }
            }
            runCurrent()

            try {
                connector.startAutoConnect()
                runCurrent()

                // Transport connected, but connector must stay Connecting until GATT init succeeds
                assertEquals(BleConnectionState.Connected(PRIMARY_MAC), bleTransport.connectionState.value)
                assertEquals(
                    AutoConnectState.Connecting(PRIMARY_MAC, "Strap"),
                    connector.state.value
                )

                advanceTimeBy(10_000L)
                runCurrent()

                assertEquals(
                    AutoConnectState.DormantPrompt(PRIMARY_MAC, "Strap"),
                    connector.state.value
                )
                assertEquals(BleConnectionState.Disconnected, bleTransport.connectionState.value)
                assertTrue(bleTransport.recordedOperations.contains(BleRecordedOperation.Disconnect(PRIMARY_MAC)))
            } finally {
                queueBlocker.cancel()
                connector.close()
                gattClient.close()
            }
        }

    @Test
    fun `already-connected Active HRM short-circuits without reconnecting`() =
        runTest(StandardTestDispatcher()) {
            val hrm = RememberedHrmEntity(
                macAddress = PRIMARY_MAC,
                advertisedName = "COOSPO H808S",
                customNickname = "Chest Strap",
                lastSeenTimestamp = 1_000L,
                isActive = true
            )
            val bleTransport = FakeBleTransport()
            bleTransport.connect(PRIMARY_MAC)
            bleTransport.clearRecordedOperations()

            val gattClient = HrmGattClient(bleTransport = bleTransport, scope = this)
            val hrmDao = FakeRememberedHrmDao(listOf(hrm))
            val connector = ActiveHrmAutoConnector(
                bleTransport = bleTransport,
                hrmGattClient = gattClient,
                rememberedHrmDao = hrmDao,
                scope = this
            )

            try {
                connector.startAutoConnect()
                runCurrent()

                assertEquals(
                    AutoConnectState.Connected(PRIMARY_MAC, "Chest Strap"),
                    connector.state.value
                )
                assertTrue(
                    "Expected no BLE operations when already connected, got ${bleTransport.recordedOperations}",
                    bleTransport.recordedOperations.isEmpty()
                )
            } finally {
                connector.close()
                gattClient.close()
            }
        }

    @Test
    fun `does not interfere with active Session or mid-session Reconnecting`() =
        runTest(StandardTestDispatcher()) {
            val hrm = RememberedHrmEntity(
                macAddress = PRIMARY_MAC,
                advertisedName = "COOSPO H808S",
                customNickname = "Chest Strap",
                lastSeenTimestamp = 1_000L,
                isActive = true
            )
            val bleTransport = FakeBleTransport()
            val gattClient = HrmGattClient(bleTransport = bleTransport, scope = this)
            val hrmDao = FakeRememberedHrmDao(listOf(hrm))

            var sessionActive = true
            var sessionConnectionState: BleConnectionState = BleConnectionState.Disconnected

            val connector = ActiveHrmAutoConnector(
                bleTransport = bleTransport,
                hrmGattClient = gattClient,
                rememberedHrmDao = hrmDao,
                scope = this,
                isSessionActiveProvider = { sessionActive },
                sessionConnectionStateProvider = { sessionConnectionState }
            )

            try {
                // Active session blocks auto-connect
                connector.startAutoConnect()
                runCurrent()
                assertEquals(AutoConnectState.Idle, connector.state.value)
                assertTrue(bleTransport.recordedOperations.isEmpty())

                // SessionRepository mid-session reconnection blocks auto-connect even if transport is Disconnected
                sessionActive = false
                sessionConnectionState = BleConnectionState.Reconnecting(PRIMARY_MAC, attempt = 2)
                connector.startAutoConnect()
                runCurrent()
                assertEquals(AutoConnectState.Idle, connector.state.value)
                assertTrue(bleTransport.recordedOperations.isEmpty())

                // Transport Reconnecting state blocks auto-connect
                sessionConnectionState = BleConnectionState.Disconnected
                bleTransport.setConnectionState(BleConnectionState.Reconnecting(PRIMARY_MAC, attempt = 1))
                connector.startAutoConnect()
                runCurrent()
                assertEquals(AutoConnectState.Idle, connector.state.value)
                assertTrue(bleTransport.recordedOperations.isEmpty())
            } finally {
                connector.close()
                gattClient.close()
            }
        }

    @Test
    fun `immediate failure, Bluetooth disabled, and missing permission emit DormantPrompt`() =
        runTest(StandardTestDispatcher()) {
            val hrm = RememberedHrmEntity(
                macAddress = PRIMARY_MAC,
                advertisedName = "COOSPO H808S",
                customNickname = null,
                lastSeenTimestamp = 1_000L,
                isActive = true
            )
            val bleTransport = FakeBleTransport()
            val gattClient = HrmGattClient(bleTransport = bleTransport, scope = this)
            val hrmDao = FakeRememberedHrmDao(listOf(hrm))
            val permissionChecker = MutableBlePermissionChecker(
                BlePermissionStatus(
                    hasScanPermission = true,
                    hasConnectPermission = true,
                    hasNotificationPermission = false
                )
            )
            val connector = ActiveHrmAutoConnector(
                bleTransport = bleTransport,
                hrmGattClient = gattClient,
                rememberedHrmDao = hrmDao,
                scope = this,
                permissionChecker = permissionChecker
            )

            try {
                // 1. Immediate GATT failure (e.g. Error 133)
                bleTransport.setConnectResult(
                    BleOperationResult.Failure(status = 133, message = "GATT error 133"),
                    macAddress = PRIMARY_MAC
                )
                connector.startAutoConnect()
                runCurrent()
                assertEquals(
                    AutoConnectState.DormantPrompt(PRIMARY_MAC, "COOSPO H808S"),
                    connector.state.value
                )

                // 2. Bluetooth disabled
                bleTransport.setConnectResult(BleOperationResult.Success(Unit), macAddress = PRIMARY_MAC)
                bleTransport.isBluetoothEnabled = false
                connector.startAutoConnect()
                runCurrent()
                assertEquals(
                    AutoConnectState.DormantPrompt(PRIMARY_MAC, "COOSPO H808S"),
                    connector.state.value
                )

                // 3. Permission denied
                bleTransport.isBluetoothEnabled = true
                bleTransport.clearRecordedOperations()
                permissionChecker.status = BlePermissionStatus(
                    hasScanPermission = false,
                    hasConnectPermission = false,
                    hasNotificationPermission = false
                )
                connector.startAutoConnect()
                runCurrent()
                assertEquals(
                    AutoConnectState.DormantPrompt(PRIMARY_MAC, "COOSPO H808S"),
                    connector.state.value
                )
                assertTrue(bleTransport.recordedOperations.isEmpty())
            } finally {
                connector.close()
                gattClient.close()
            }
        }

    @Test
    fun `external connection clears DormantPrompt into Connected`() =
        runTest(StandardTestDispatcher()) {
            val hrm = RememberedHrmEntity(
                macAddress = PRIMARY_MAC,
                advertisedName = "COOSPO H808S",
                customNickname = "Chest Strap",
                lastSeenTimestamp = 1_000L,
                isActive = true
            )
            val bleTransport = FakeBleTransport().apply {
                onConnectAttempt = { _, _ -> awaitCancellation() }
            }
            val gattClient = HrmGattClient(bleTransport = bleTransport, scope = this)
            val hrmDao = FakeRememberedHrmDao(listOf(hrm))
            val connector = ActiveHrmAutoConnector(
                bleTransport = bleTransport,
                hrmGattClient = gattClient,
                rememberedHrmDao = hrmDao,
                scope = this
            )

            try {
                connector.startAutoConnect()
                advanceTimeBy(10_000L)
                runCurrent()
                assertEquals(
                    AutoConnectState.DormantPrompt(PRIMARY_MAC, "Chest Strap"),
                    connector.state.value
                )

                // Strap connects externally
                bleTransport.setConnectionState(BleConnectionState.Connected(PRIMARY_MAC))
                runCurrent()

                assertEquals(
                    AutoConnectState.Connected(PRIMARY_MAC, "Chest Strap"),
                    connector.state.value
                )
            } finally {
                connector.close()
                gattClient.close()
            }
        }

    @Test
    fun `cancelAutoConnect and Active HRM switch cancel in-flight attempt cleanly`() =
        runTest(StandardTestDispatcher()) {
            val hrmA = RememberedHrmEntity(
                macAddress = PRIMARY_MAC,
                advertisedName = "COOSPO H808S",
                customNickname = "Strap A",
                lastSeenTimestamp = 1_000L,
                isActive = true
            )
            val hrmB = RememberedHrmEntity(
                macAddress = SECONDARY_MAC,
                advertisedName = "Polar H10",
                customNickname = "Strap B",
                lastSeenTimestamp = 2_000L,
                isActive = true
            )
            val bleTransport = FakeBleTransport().apply {
                onConnectAttempt = { _, _ -> awaitCancellation() }
            }
            val gattClient = HrmGattClient(bleTransport = bleTransport, scope = this)
            val hrmDao = FakeRememberedHrmDao(listOf(hrmA))
            val connector = ActiveHrmAutoConnector(
                bleTransport = bleTransport,
                hrmGattClient = gattClient,
                rememberedHrmDao = hrmDao,
                scope = this
            )

            try {
                // 1. Explicit cancelAutoConnect
                connector.startAutoConnect()
                runCurrent()
                assertEquals(
                    AutoConnectState.Connecting(PRIMARY_MAC, "Strap A"),
                    connector.state.value
                )

                connector.cancelAutoConnect()
                runCurrent()
                assertEquals(AutoConnectState.Idle, connector.state.value)
                assertEquals(BleConnectionState.Disconnected, bleTransport.connectionState.value)

                advanceTimeBy(15_000L)
                runCurrent()
                assertEquals(AutoConnectState.Idle, connector.state.value)

                // 2. Active HRM changes mid-connect
                connector.startAutoConnect()
                runCurrent()
                assertEquals(
                    AutoConnectState.Connecting(PRIMARY_MAC, "Strap A"),
                    connector.state.value
                )

                hrmDao.upsertHrm(hrmB)
                runCurrent()
                assertEquals(AutoConnectState.Idle, connector.state.value)

                advanceTimeBy(15_000L)
                runCurrent()
                assertEquals(AutoConnectState.Idle, connector.state.value)
            } finally {
                connector.close()
                gattClient.close()
            }
        }

    @Test
    fun `no Active HRM stays Idle without issuing BLE operations`() =
        runTest(StandardTestDispatcher()) {
            val bleTransport = FakeBleTransport()
            val gattClient = HrmGattClient(bleTransport = bleTransport, scope = this)
            val hrmDao = FakeRememberedHrmDao(emptyList())
            val connector = ActiveHrmAutoConnector(
                bleTransport = bleTransport,
                hrmGattClient = gattClient,
                rememberedHrmDao = hrmDao,
                scope = this
            )

            try {
                connector.startAutoConnect()
                runCurrent()

                assertEquals(AutoConnectState.Idle, connector.state.value)
                assertTrue(bleTransport.recordedOperations.isEmpty())
            } finally {
                connector.close()
                gattClient.close()
            }
        }

    private fun defaultProfile(): UserProfileEntity = UserProfileEntity(
        birthYear = 1996,
        restingHr = null,
        customHrmax = 200,
        calculationMode = ZoneCalculationMode.PERCENT_HRMAX,
        updatedAtEpochMs = 1_700_000_000_000L
    )

    private class MutableBlePermissionChecker(
        var status: BlePermissionStatus
    ) : BlePermissionChecker {
        override fun checkPermissions(): BlePermissionStatus = status
    }

    companion object {
        private const val PRIMARY_MAC = "AA:BB:CC:DD:EE:01"
        private const val SECONDARY_MAC = "AA:BB:CC:DD:EE:02"
    }
}
