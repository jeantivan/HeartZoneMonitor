package com.example.heartzonemonitor.data.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Summary of GATT session initialization for a connected Heart Rate Monitor (HRM).
 */
data class HrmGattSessionInfo(
    val macAddress: String,
    val hasBatteryService: Boolean,
    val initialBatteryLevel: Int?
)

/**
 * Protocol client managing serialized GATT operations, `0x2A37` Heart Rate Measurement
 * notifications, CCCD (`0x2902`) subscriptions, and periodic Battery Level (`0x2A19`) reads for
 * a connected Heart Rate Monitor (HRM).
 */
class HrmGattClient(
    private val bleTransport: BleTransport,
    private val scope: CoroutineScope,
    private val commandQueue: GattCommandQueue = GattCommandQueue(),
    private val batteryRefreshIntervalMs: Long = DEFAULT_BATTERY_REFRESH_INTERVAL_MS,
    private val onBatteryLevelUpdated: (suspend (macAddress: String, batteryLevel: Int) -> Unit)? = null
) {

    init {
        require(batteryRefreshIntervalMs > 0L) {
            "batteryRefreshIntervalMs must be positive"
        }
    }

    val connectionState: StateFlow<BleConnectionState> = bleTransport.connectionState

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel.asStateFlow()

    val heartRateMeasurements: Flow<HeartRateMeasurement> =
        bleTransport.characteristicNotifications
            .filter { it.characteristicUuid == BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID }
            .mapNotNull { notification ->
                HeartRateMeasurementParser.parse(
                    payload = notification.value,
                    timestampEpochMs = notification.timestampEpochMs
                )
            }

    private val refreshLock = Any()
    private var batteryRefreshJob: Job? = null

    /**
     * Discovers GATT services and characteristics on the connected Heart Rate Monitor (HRM).
     */
    suspend fun discoverServices(macAddress: String): BleOperationResult<List<BleDiscoveredService>> =
        commandQueue.enqueue {
            bleTransport.discoverServices(macAddress)
        }

    /**
     * Enables local characteristic notifications and writes `0x01, 0x00` to CCCD `0x2902` for
     * `0x2A37` atomically inside [commandQueue].
     */
    suspend fun subscribeToHeartRateMeasurements(macAddress: String): BleOperationResult<Unit> =
        commandQueue.enqueue {
            val notifyResult = bleTransport.setCharacteristicNotification(
                macAddress = macAddress,
                serviceUuid = BleUuids.HEART_RATE_SERVICE_UUID,
                characteristicUuid = BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID,
                enable = true
            )
            if (notifyResult is BleOperationResult.Failure) {
                return@enqueue notifyResult
            }

            bleTransport.writeDescriptor(
                macAddress = macAddress,
                serviceUuid = BleUuids.HEART_RATE_SERVICE_UUID,
                characteristicUuid = BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID,
                descriptorUuid = BleUuids.CCCD_DESCRIPTOR_UUID,
                value = CCCD_ENABLE_NOTIFICATION_VALUE.copyOf()
            )
        }

    /**
     * Writes `0x00, 0x00` to CCCD `0x2902` and disables local notifications for `0x2A37`
     * atomically inside [commandQueue].
     */
    suspend fun unsubscribeFromHeartRateMeasurements(macAddress: String): BleOperationResult<Unit> =
        commandQueue.enqueue {
            val descriptorResult = bleTransport.writeDescriptor(
                macAddress = macAddress,
                serviceUuid = BleUuids.HEART_RATE_SERVICE_UUID,
                characteristicUuid = BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID,
                descriptorUuid = BleUuids.CCCD_DESCRIPTOR_UUID,
                value = CCCD_DISABLE_NOTIFICATION_VALUE.copyOf()
            )
            val notifyResult = bleTransport.setCharacteristicNotification(
                macAddress = macAddress,
                serviceUuid = BleUuids.HEART_RATE_SERVICE_UUID,
                characteristicUuid = BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID,
                enable = false
            )
            if (descriptorResult is BleOperationResult.Failure) {
                descriptorResult
            } else {
                notifyResult
            }
        }

    /**
     * Reads Battery Level (`0x180F` / `0x2A19`), updates [batteryLevel], and notifies
     * [onBatteryLevelUpdated] on valid payloads.
     */
    suspend fun readBatteryLevel(macAddress: String): BleOperationResult<Int> {
        val readResult = commandQueue.enqueue {
            bleTransport.readCharacteristic(
                macAddress = macAddress,
                serviceUuid = BleUuids.BATTERY_SERVICE_UUID,
                characteristicUuid = BleUuids.BATTERY_LEVEL_CHAR_UUID
            )
        }
        return when (readResult) {
            is BleOperationResult.Failure -> readResult
            is BleOperationResult.Success -> {
                val level = BatteryLevelParser.parse(readResult.value)
                    ?: return BleOperationResult.Failure(
                        status = BleOperationResult.STATUS_OPERATION_FAILED,
                        message = "Invalid Battery Level payload (${readResult.value.size} bytes)"
                    )
                _batteryLevel.value = level
                onBatteryLevelUpdated?.invoke(macAddress, level)
                BleOperationResult.Success(level)
            }
        }
    }

    /**
     * Initializes a connected Heart Rate Monitor (HRM): clears prior state, discovers services,
     * verifies `0x180D`/`0x2A37`, subscribes to CCCD `0x2902`, and reads plus schedules
     * periodic Battery Level (`0x180F`/`0x2A19`) updates when supported.
     */
    suspend fun initializeConnectedHrm(macAddress: String): BleOperationResult<HrmGattSessionInfo> {
        stopBatteryRefresh()
        _batteryLevel.value = null

        val servicesResult = discoverServices(macAddress)
        if (servicesResult is BleOperationResult.Failure) {
            return servicesResult
        }
        val services = (servicesResult as BleOperationResult.Success).value

        if (!hasCharacteristic(services, BleUuids.HEART_RATE_SERVICE_UUID, BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID)) {
            return BleOperationResult.Failure(
                status = BleOperationResult.STATUS_SERVICE_OR_CHAR_NOT_FOUND,
                message = "Heart Rate Service (0x180D) or characteristic 0x2A37 not found on $macAddress"
            )
        }

        val subscribeResult = subscribeToHeartRateMeasurements(macAddress)
        if (subscribeResult is BleOperationResult.Failure) {
            return subscribeResult
        }

        val hasBatteryService = hasCharacteristic(
            services = services,
            serviceUuid = BleUuids.BATTERY_SERVICE_UUID,
            characteristicUuid = BleUuids.BATTERY_LEVEL_CHAR_UUID
        )
        val initialBatteryLevel = if (hasBatteryService) {
            val batteryResult = readBatteryLevel(macAddress)
            val initialLevel = (batteryResult as? BleOperationResult.Success)?.value
            startBatteryRefresh(macAddress)
            initialLevel
        } else {
            null
        }

        return BleOperationResult.Success(
            HrmGattSessionInfo(
                macAddress = macAddress,
                hasBatteryService = hasBatteryService,
                initialBatteryLevel = initialBatteryLevel
            )
        )
    }

    /**
     * Stops the periodic Battery Level refresh job if active and waits for cancellation.
     */
    suspend fun stopBatteryRefresh() {
        val jobToCancel = synchronized(refreshLock) {
            batteryRefreshJob.also { batteryRefreshJob = null }
        }
        jobToCancel?.cancelAndJoin()
    }

    /**
     * Stops periodic Battery Level refreshes and disconnects [macAddress] through [bleTransport].
     */
    suspend fun disconnect(macAddress: String) {
        stopBatteryRefresh()
        bleTransport.disconnect(macAddress)
    }

    /**
     * Cancels active periodic jobs owned by this client synchronously.
     */
    fun close() {
        synchronized(refreshLock) {
            batteryRefreshJob?.cancel()
            batteryRefreshJob = null
        }
    }

    private fun startBatteryRefresh(macAddress: String) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            coroutineScope {
                val tickerJob = launch {
                    while (isActive) {
                        delay(batteryRefreshIntervalMs)
                        val currentState = bleTransport.connectionState.value
                        if (currentState !is BleConnectionState.Connected || currentState.macAddress != macAddress) {
                            break
                        }
                        try {
                            readBatteryLevel(macAddress)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Keep schedule running for subsequent intervals while connected.
                        }
                    }
                }

                // Cancel the periodic ticker immediately when the link drops so test scopes exit cleanly.
                bleTransport.connectionState.first { state ->
                    state !is BleConnectionState.Connected || state.macAddress != macAddress
                }
                tickerJob.cancel()
            }
        }
        synchronized(refreshLock) {
            batteryRefreshJob?.cancel()
            batteryRefreshJob = job
        }
        job.start()
    }

    private fun hasCharacteristic(
        services: List<BleDiscoveredService>,
        serviceUuid: UUID,
        characteristicUuid: UUID
    ): Boolean = services.any { service ->
        service.uuid == serviceUuid && characteristicUuid in service.characteristicUuids
    }

    companion object {
        const val DEFAULT_BATTERY_REFRESH_INTERVAL_MS = 10L * 60L * 1000L

        // Defined in pure Kotlin instead of BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        // because Android framework stub byte arrays can be null in plain JVM unit tests.
        val CCCD_ENABLE_NOTIFICATION_VALUE = byteArrayOf(0x01, 0x00)
        val CCCD_DISABLE_NOTIFICATION_VALUE = byteArrayOf(0x00, 0x00)
    }
}
