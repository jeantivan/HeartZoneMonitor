package com.example.heartzonemonitor.data.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class GattCommandQueueAndClientTest {

    private lateinit var transport: FakeBleTransport

    @Before
    fun setUp() {
        transport = FakeBleTransport()
    }

    @Test
    fun `GattCommandQueue enforces mutual exclusion and FIFO order`() = runTest {
        val queue = GattCommandQueue()
        val inFlight = AtomicInteger(0)
        var maxInFlight = 0
        val completionOrder = mutableListOf<Int>()

        val op1Started = CompletableDeferred<Unit>()
        val releaseOp1 = CompletableDeferred<Unit>()
        val op2Started = CompletableDeferred<Unit>()
        val releaseOp2 = CompletableDeferred<Unit>()
        val op3Started = CompletableDeferred<Unit>()
        val releaseOp3 = CompletableDeferred<Unit>()

        val job1 = launch(UnconfinedTestDispatcher(testScheduler)) {
            queue.enqueue {
                val active = inFlight.incrementAndGet()
                maxInFlight = maxOf(maxInFlight, active)
                op1Started.complete(Unit)
                releaseOp1.await()
                completionOrder.add(1)
                inFlight.decrementAndGet()
            }
        }
        op1Started.await()

        val job2 = launch(UnconfinedTestDispatcher(testScheduler)) {
            queue.enqueue {
                val active = inFlight.incrementAndGet()
                maxInFlight = maxOf(maxInFlight, active)
                op2Started.complete(Unit)
                releaseOp2.await()
                completionOrder.add(2)
                inFlight.decrementAndGet()
            }
        }

        val job3 = launch(UnconfinedTestDispatcher(testScheduler)) {
            queue.enqueue {
                val active = inFlight.incrementAndGet()
                maxInFlight = maxOf(maxInFlight, active)
                op3Started.complete(Unit)
                releaseOp3.await()
                completionOrder.add(3)
                inFlight.decrementAndGet()
            }
        }

        assertFalse(op2Started.isCompleted)
        assertFalse(op3Started.isCompleted)
        assertEquals(1, inFlight.get())

        releaseOp1.complete(Unit)
        op2Started.await()
        assertFalse(op3Started.isCompleted)
        assertEquals(1, inFlight.get())

        releaseOp2.complete(Unit)
        op3Started.await()
        assertEquals(1, inFlight.get())

        releaseOp3.complete(Unit)
        job1.join()
        job2.join()
        job3.join()

        assertEquals(1, maxInFlight)
        assertEquals(0, inFlight.get())
        assertEquals(listOf(1, 2, 3), completionOrder)
    }

    @Test
    fun `GattCommandQueue releases lock on failure and waiter cancel`() = runTest {
        val queue = GattCommandQueue()
        val op1Started = CompletableDeferred<Unit>()
        val releaseOp1 = CompletableDeferred<Unit>()

        val job1 = launch(UnconfinedTestDispatcher(testScheduler)) {
            runCatching {
                queue.enqueue {
                    op1Started.complete(Unit)
                    releaseOp1.await()
                    throw IllegalStateException("simulated GATT failure")
                }
            }
        }
        op1Started.await()

        val cancelledJob2 = launch(UnconfinedTestDispatcher(testScheduler)) {
            queue.enqueue {
                error("Cancelled waiter must never execute")
            }
        }
        cancelledJob2.cancel()

        val op3Result = CompletableDeferred<String>()
        val job3 = launch(UnconfinedTestDispatcher(testScheduler)) {
            val result = queue.enqueue { "recovered" }
            op3Result.complete(result)
        }

        releaseOp1.complete(Unit)
        job1.join()
        cancelledJob2.join()
        job3.join()

        assertEquals("recovered", op3Result.await())
    }

    @Test
    fun `initializeConnectedHrm executes GATT setup and battery callback`() = runTest {
        val mac = "D4:36:39:11:22:9B"
        val batteryCallbacks = mutableListOf<Pair<String, Int>>()
        val client = HrmGattClient(
            bleTransport = transport,
            scope = this,
            onBatteryLevelUpdated = { address, level ->
                batteryCallbacks.add(address to level)
            }
        )

        assertTrue(transport.connect(mac) is BleOperationResult.Success)
        transport.stubCharacteristicRead(
            characteristicUuid = BleUuids.BATTERY_LEVEL_CHAR_UUID,
            result = BleOperationResult.Success(byteArrayOf(87)),
            serviceUuid = BleUuids.BATTERY_SERVICE_UUID,
            macAddress = mac
        )
        transport.clearRecordedOperations()

        val initResult = client.initializeConnectedHrm(mac)
        assertTrue(initResult is BleOperationResult.Success)
        val info = (initResult as BleOperationResult.Success).value
        assertEquals(HrmGattSessionInfo(macAddress = mac, hasBatteryService = true, initialBatteryLevel = 87), info)
        assertEquals(87, client.batteryLevel.value)
        assertEquals(listOf(mac to 87), batteryCallbacks)

        val ops = transport.recordedOperations
        assertEquals(4, ops.size)
        assertEquals(BleRecordedOperation.DiscoverServices(mac), ops[0])
        assertEquals(
            BleRecordedOperation.SetCharacteristicNotification(
                macAddress = mac,
                serviceUuid = BleUuids.HEART_RATE_SERVICE_UUID,
                characteristicUuid = BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID,
                enable = true
            ),
            ops[1]
        )
        assertEquals(
            BleRecordedOperation.WriteDescriptor(
                macAddress = mac,
                serviceUuid = BleUuids.HEART_RATE_SERVICE_UUID,
                characteristicUuid = BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID,
                descriptorUuid = BleUuids.CCCD_DESCRIPTOR_UUID,
                value = byteArrayOf(0x01, 0x00)
            ),
            ops[2]
        )
        assertEquals(
            BleRecordedOperation.ReadCharacteristic(
                macAddress = mac,
                serviceUuid = BleUuids.BATTERY_SERVICE_UUID,
                characteristicUuid = BleUuids.BATTERY_LEVEL_CHAR_UUID
            ),
            ops[3]
        )

        transport.simulateDisconnect()
        runCurrent()
    }

    @Test
    fun `periodic battery refresh runs every 10 min and stops on disconnect`() = runTest {
        val mac = "D4:36:39:11:22:9B"
        val batteryCallbacks = mutableListOf<Int>()
        val client = HrmGattClient(
            bleTransport = transport,
            scope = this,
            onBatteryLevelUpdated = { _, level -> batteryCallbacks.add(level) }
        )

        assertTrue(transport.connect(mac) is BleOperationResult.Success)
        transport.stubCharacteristicRead(
            characteristicUuid = BleUuids.BATTERY_LEVEL_CHAR_UUID,
            result = BleOperationResult.Success(byteArrayOf(92)),
            serviceUuid = BleUuids.BATTERY_SERVICE_UUID,
            macAddress = mac
        )

        val initResult = client.initializeConnectedHrm(mac)
        assertTrue(initResult is BleOperationResult.Success)
        assertEquals(92, client.batteryLevel.value)
        assertEquals(listOf(92), batteryCallbacks)

        // First 10-minute interval
        transport.stubCharacteristicRead(
            characteristicUuid = BleUuids.BATTERY_LEVEL_CHAR_UUID,
            result = BleOperationResult.Success(byteArrayOf(89)),
            serviceUuid = BleUuids.BATTERY_SERVICE_UUID,
            macAddress = mac
        )
        advanceTimeBy(600_000L)
        runCurrent()
        assertEquals(89, client.batteryLevel.value)
        assertEquals(listOf(92, 89), batteryCallbacks)

        // Second 10-minute interval
        transport.stubCharacteristicRead(
            characteristicUuid = BleUuids.BATTERY_LEVEL_CHAR_UUID,
            result = BleOperationResult.Success(byteArrayOf(85)),
            serviceUuid = BleUuids.BATTERY_SERVICE_UUID,
            macAddress = mac
        )
        advanceTimeBy(600_000L)
        runCurrent()
        assertEquals(85, client.batteryLevel.value)
        assertEquals(listOf(92, 89, 85), batteryCallbacks)

        val readsBeforeDisconnect = transport.recordedOperations.count {
            it is BleRecordedOperation.ReadCharacteristic
        }
        assertEquals(3, readsBeforeDisconnect)

        // Drop connection and advance another 10 minutes: ticker must stop with zero extra reads
        transport.simulateDisconnect()
        runCurrent()
        advanceTimeBy(600_000L)
        runCurrent()

        val readsAfterDisconnect = transport.recordedOperations.count {
            it is BleRecordedOperation.ReadCharacteristic
        }
        assertEquals(3, readsAfterDisconnect)
    }

    @Test
    fun `missing Battery Service succeeds with null batteryLevel`() = runTest {
        val mac = "A0:9E:1A:22:33:11"
        val client = HrmGattClient(bleTransport = transport, scope = this)

        transport.setDiscoveredServices(
            listOf(
                BleDiscoveredService(
                    uuid = BleUuids.HEART_RATE_SERVICE_UUID,
                    characteristicUuids = listOf(BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID)
                )
            ),
            macAddress = mac
        )
        assertTrue(transport.connect(mac) is BleOperationResult.Success)

        val initResult = client.initializeConnectedHrm(mac)
        assertTrue(initResult is BleOperationResult.Success)
        val info = (initResult as BleOperationResult.Success).value
        assertEquals(
            HrmGattSessionInfo(macAddress = mac, hasBatteryService = false, initialBatteryLevel = null),
            info
        )
        assertNull(client.batteryLevel.value)

        advanceTimeBy(600_000L)
        runCurrent()
        val batteryReads = transport.recordedOperations.filterIsInstance<BleRecordedOperation.ReadCharacteristic>()
        assertTrue(batteryReads.isEmpty())
    }

    @Test
    fun `missing Heart Rate Service fails with service not found`() = runTest {
        val mac = "11:22:33:44:55:66"
        val client = HrmGattClient(bleTransport = transport, scope = this)

        transport.setDiscoveredServices(
            listOf(
                BleDiscoveredService(
                    uuid = BleUuids.BATTERY_SERVICE_UUID,
                    characteristicUuids = listOf(BleUuids.BATTERY_LEVEL_CHAR_UUID)
                )
            ),
            macAddress = mac
        )
        assertTrue(transport.connect(mac) is BleOperationResult.Success)

        val result = client.initializeConnectedHrm(mac)
        assertTrue(result is BleOperationResult.Failure)
        assertEquals(
            BleOperationResult.STATUS_SERVICE_OR_CHAR_NOT_FOUND,
            (result as BleOperationResult.Failure).status
        )
        assertFalse(
            transport.recordedOperations.any { it is BleRecordedOperation.SetCharacteristicNotification }
        )

        // Heart Rate Service present without 0x2A37 characteristic also fails cleanly
        transport.setDiscoveredServices(
            listOf(
                BleDiscoveredService(
                    uuid = BleUuids.HEART_RATE_SERVICE_UUID,
                    characteristicUuids = emptyList()
                )
            ),
            macAddress = mac
        )
        val missingCharResult = client.initializeConnectedHrm(mac)
        assertTrue(missingCharResult is BleOperationResult.Failure)
        assertEquals(
            BleOperationResult.STATUS_SERVICE_OR_CHAR_NOT_FOUND,
            (missingCharResult as BleOperationResult.Failure).status
        )
    }

    @Test
    fun `subscribe failure aborts initializeConnectedHrm before battery`() = runTest {
        val mac = "22:33:44:55:66:77"
        val client = HrmGattClient(bleTransport = transport, scope = this)

        assertTrue(transport.connect(mac) is BleOperationResult.Success)
        transport.writeDescriptorResult = BleOperationResult.Failure(
            status = BleOperationResult.STATUS_OPERATION_FAILED,
            message = "CCCD write failed"
        )
        transport.clearRecordedOperations()

        val result = client.initializeConnectedHrm(mac)
        assertTrue(result is BleOperationResult.Failure)
        assertEquals(
            BleOperationResult.STATUS_OPERATION_FAILED,
            (result as BleOperationResult.Failure).status
        )
        assertFalse(
            transport.recordedOperations.any { it is BleRecordedOperation.ReadCharacteristic }
        )
    }

    @Test
    fun `heartRateMeasurements streams valid frames and drops invalid ones`() = runTest {
        val mac = "D4:36:39:11:22:9B"
        val client = HrmGattClient(bleTransport = transport, scope = this)
        val received = Channel<HeartRateMeasurement>(Channel.UNLIMITED)

        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            client.heartRateMeasurements.collect { received.send(it) }
        }

        // Unrelated characteristic (Battery Level 0x2A19) must be ignored
        transport.emitNotification(
            macAddress = mac,
            value = byteArrayOf(88),
            serviceUuid = BleUuids.BATTERY_SERVICE_UUID,
            characteristicUuid = BleUuids.BATTERY_LEVEL_CHAR_UUID,
            timestampEpochMs = 1000L
        )

        // Malformed 0x2A37 payload (truncated 16-bit frame) must be silently dropped
        transport.emitNotification(
            macAddress = mac,
            value = byteArrayOf(0x01, 0x48),
            serviceUuid = BleUuids.HEART_RATE_SERVICE_UUID,
            characteristicUuid = BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID,
            timestampEpochMs = 2000L
        )

        // Valid 0x2A37 payload
        val validPacket1 = FakeBleTransport.buildHeartRateMeasurementPacket(
            bpm = 152,
            sensorContactSupported = true,
            sensorContactDetected = true,
            rrIntervals1024 = listOf(410)
        )
        transport.emitNotification(
            macAddress = mac,
            value = validPacket1,
            timestampEpochMs = 3000L
        )

        // Second valid 0x2A37 payload
        val validPacket2 = FakeBleTransport.buildHeartRateMeasurementPacket(
            bpm = 160,
            sensorContactSupported = true,
            sensorContactDetected = false
        )
        transport.emitNotification(
            macAddress = mac,
            value = validPacket2,
            timestampEpochMs = 4000L
        )

        val first = received.receive()
        assertEquals(152, first.bpm)
        assertEquals(SensorContactStatus.DETECTED, first.sensorContactStatus)
        assertEquals(listOf(410), first.rrIntervals1024)
        assertEquals(3000L, first.timestampEpochMs)

        val second = received.receive()
        assertEquals(160, second.bpm)
        assertEquals(SensorContactStatus.NOT_DETECTED, second.sensorContactStatus)
        assertFalse(second.sensorContact)
        assertEquals(4000L, second.timestampEpochMs)

        assertTrue(received.isEmpty)
        collectJob.cancel()
    }

    @Test
    fun `unsubscribe writes CCCD disable then disables notifications`() = runTest {
        val mac = "D4:36:39:11:22:9B"
        val client = HrmGattClient(bleTransport = transport, scope = this)

        assertTrue(transport.connect(mac) is BleOperationResult.Success)
        transport.clearRecordedOperations()

        val result = client.unsubscribeFromHeartRateMeasurements(mac)
        assertTrue(result is BleOperationResult.Success)

        val ops = transport.recordedOperations
        assertEquals(2, ops.size)
        val writeOp = ops[0] as BleRecordedOperation.WriteDescriptor
        assertEquals(BleUuids.CCCD_DESCRIPTOR_UUID, writeOp.descriptorUuid)
        assertArrayEquals(byteArrayOf(0x00, 0x00), writeOp.value)
        assertEquals(
            BleRecordedOperation.SetCharacteristicNotification(
                macAddress = mac,
                serviceUuid = BleUuids.HEART_RATE_SERVICE_UUID,
                characteristicUuid = BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID,
                enable = false
            ),
            ops[1]
        )
    }

    @Test
    fun `readBatteryLevel invalid bytes and stop or close cancels ticker`() = runTest {
        val mac = "D4:36:39:11:22:9B"
        val client = HrmGattClient(bleTransport = transport, scope = this)

        assertTrue(transport.connect(mac) is BleOperationResult.Success)
        transport.stubCharacteristicRead(
            characteristicUuid = BleUuids.BATTERY_LEVEL_CHAR_UUID,
            result = BleOperationResult.Success(byteArrayOf(150.toByte())), // > 100 invalid
            serviceUuid = BleUuids.BATTERY_SERVICE_UUID,
            macAddress = mac
        )

        // Initial battery read failure during initializeConnectedHrm is non-fatal
        val initResult = client.initializeConnectedHrm(mac)
        assertTrue(initResult is BleOperationResult.Success)
        val info = (initResult as BleOperationResult.Success).value
        assertTrue(info.hasBatteryService)
        assertNull(info.initialBatteryLevel)
        assertNull(client.batteryLevel.value)

        // Direct readBatteryLevel returns STATUS_OPERATION_FAILED on invalid bytes
        val invalidRead = client.readBatteryLevel(mac)
        assertTrue(invalidRead is BleOperationResult.Failure)
        assertEquals(
            BleOperationResult.STATUS_OPERATION_FAILED,
            (invalidRead as BleOperationResult.Failure).status
        )

        // Stopping refresh cancels ticker cleanly even while still connected
        client.stopBatteryRefresh()
        val readsBeforeAdvance = transport.recordedOperations.count {
            it is BleRecordedOperation.ReadCharacteristic
        }
        advanceTimeBy(600_000L)
        runCurrent()
        val readsAfterStop = transport.recordedOperations.count {
            it is BleRecordedOperation.ReadCharacteristic
        }
        assertEquals(readsBeforeAdvance, readsAfterStop)

        // Restart refresh and test disconnect() + close()
        transport.stubCharacteristicRead(
            characteristicUuid = BleUuids.BATTERY_LEVEL_CHAR_UUID,
            result = BleOperationResult.Success(byteArrayOf(75)),
            serviceUuid = BleUuids.BATTERY_SERVICE_UUID,
            macAddress = mac
        )
        assertTrue(client.initializeConnectedHrm(mac) is BleOperationResult.Success)
        assertEquals(75, client.batteryLevel.value)

        client.disconnect(mac)
        assertEquals(BleConnectionState.Disconnected, client.connectionState.value)

        assertTrue(transport.connect(mac) is BleOperationResult.Success)
        assertTrue(client.initializeConnectedHrm(mac) is BleOperationResult.Success)
        client.close()
        runCurrent()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructor rejects non-positive batteryRefreshIntervalMs`() {
        HrmGattClient(
            bleTransport = FakeBleTransport(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            batteryRefreshIntervalMs = 0L
        )
    }
}
