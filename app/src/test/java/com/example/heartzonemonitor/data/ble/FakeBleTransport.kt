package com.example.heartzonemonitor.data.ble

import android.bluetooth.BluetoothGattCharacteristic
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Recorded operation issued against [FakeBleTransport] for deterministic verification of
 * scan ordering, GATT serialization, and descriptor/characteristic interactions.
 */
sealed interface BleRecordedOperation {
    data class ScanStarted(val serviceUuidFilter: UUID?) : BleRecordedOperation
    data object ScanStopped : BleRecordedOperation
    data class Connect(val macAddress: String, val autoConnect: Boolean) : BleRecordedOperation
    data class Disconnect(val macAddress: String) : BleRecordedOperation
    data class DiscoverServices(val macAddress: String) : BleRecordedOperation
    data class SetCharacteristicNotification(
        val macAddress: String,
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
        val enable: Boolean
    ) : BleRecordedOperation

    data class WriteDescriptor(
        val macAddress: String,
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
        val descriptorUuid: UUID,
        val value: ByteArray
    ) : BleRecordedOperation {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WriteDescriptor) return false
            return macAddress == other.macAddress &&
                serviceUuid == other.serviceUuid &&
                characteristicUuid == other.characteristicUuid &&
                descriptorUuid == other.descriptorUuid &&
                value.contentEquals(other.value)
        }

        override fun hashCode(): Int {
            var result = macAddress.hashCode()
            result = 31 * result + serviceUuid.hashCode()
            result = 31 * result + characteristicUuid.hashCode()
            result = 31 * result + descriptorUuid.hashCode()
            result = 31 * result + value.contentHashCode()
            return result
        }
    }

    data class ReadCharacteristic(
        val macAddress: String,
        val serviceUuid: UUID,
        val characteristicUuid: UUID
    ) : BleRecordedOperation

    data class WriteCharacteristic(
        val macAddress: String,
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
        val value: ByteArray,
        val writeType: Int
    ) : BleRecordedOperation {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WriteCharacteristic) return false
            return macAddress == other.macAddress &&
                serviceUuid == other.serviceUuid &&
                characteristicUuid == other.characteristicUuid &&
                value.contentEquals(other.value) &&
                writeType == other.writeType
        }

        override fun hashCode(): Int {
            var result = macAddress.hashCode()
            result = 31 * result + serviceUuid.hashCode()
            result = 31 * result + characteristicUuid.hashCode()
            result = 31 * result + value.contentHashCode()
            result = 31 * result + writeType
            return result
        }
    }

    data object Closed : BleRecordedOperation
}

/**
 * Deterministic test double implementing [BleTransport] for unit and integration tests without
 * Android Bluetooth hardware dependencies.
 */
class FakeBleTransport : BleTransport {

    override var isBluetoothEnabled: Boolean = true
    var hasScanPermission: Boolean = true
    var hasConnectPermission: Boolean = true

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    override val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _characteristicNotifications = MutableSharedFlow<BleCharacteristicNotification>(
        extraBufferCapacity = 64
    )
    override val characteristicNotifications: Flow<BleCharacteristicNotification> =
        _characteristicNotifications.asSharedFlow()

    private val availableScanResults = mutableListOf<BleScanResult>()
    private val liveScanResults = MutableSharedFlow<BleScanResult>(extraBufferCapacity = 64)
    private val scanStoppedSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    @Volatile
    var isScanning: Boolean = false
        private set

    var defaultConnectResult: BleOperationResult<Unit> = BleOperationResult.Success(Unit)
    private val connectResultsByMac = mutableMapOf<String, BleOperationResult<Unit>>()
    var onConnectAttempt: (suspend (macAddress: String, autoConnect: Boolean) -> BleOperationResult<Unit>?)? =
        null

    private var defaultDiscoveredServices: List<BleDiscoveredService> = listOf(
        BleDiscoveredService(
            uuid = BleUuids.HEART_RATE_SERVICE_UUID,
            characteristicUuids = listOf(BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID)
        ),
        BleDiscoveredService(
            uuid = BleUuids.BATTERY_SERVICE_UUID,
            characteristicUuids = listOf(BleUuids.BATTERY_LEVEL_CHAR_UUID)
        )
    )
    private val discoveredServicesByMac = mutableMapOf<String, List<BleDiscoveredService>>()
    var discoverServicesFailure: BleOperationResult.Failure? = null

    var setNotificationResult: BleOperationResult<Unit> = BleOperationResult.Success(Unit)
    var writeDescriptorResult: BleOperationResult<Unit> = BleOperationResult.Success(Unit)
    var writeCharacteristicResult: BleOperationResult<Unit> = BleOperationResult.Success(Unit)
    var defaultReadCharacteristicResult: BleOperationResult<ByteArray> = BleOperationResult.Failure(
        status = BleOperationResult.STATUS_SERVICE_OR_CHAR_NOT_FOUND,
        message = "No stubbed characteristic value configured"
    )
    private val stubbedReads = mutableMapOf<ReadKey, BleOperationResult<ByteArray>>()

    private val _recordedOperations = mutableListOf<BleRecordedOperation>()
    val recordedOperations: List<BleRecordedOperation>
        get() = synchronized(_recordedOperations) { _recordedOperations.toList() }

    fun setScanResults(results: List<BleScanResult>) {
        availableScanResults.clear()
        availableScanResults.addAll(results)
    }

    fun addScanResult(result: BleScanResult) {
        availableScanResults.add(result)
    }

    fun clearScanResults() {
        availableScanResults.clear()
    }

    suspend fun emitScanResult(result: BleScanResult) {
        liveScanResults.emit(result)
    }

    fun tryEmitScanResult(result: BleScanResult): Boolean =
        liveScanResults.tryEmit(result)

    fun setConnectResult(result: BleOperationResult<Unit>, macAddress: String? = null) {
        if (macAddress == null) {
            defaultConnectResult = result
        } else {
            connectResultsByMac[macAddress] = result
        }
    }

    fun setDiscoveredServices(services: List<BleDiscoveredService>, macAddress: String? = null) {
        if (macAddress == null) {
            defaultDiscoveredServices = services
        } else {
            discoveredServicesByMac[macAddress] = services
        }
    }

    fun stubCharacteristicRead(
        characteristicUuid: UUID,
        result: BleOperationResult<ByteArray>,
        serviceUuid: UUID? = null,
        macAddress: String? = null
    ) {
        stubbedReads[ReadKey(macAddress, serviceUuid, characteristicUuid)] = result
    }

    fun clearRecordedOperations() {
        synchronized(_recordedOperations) {
            _recordedOperations.clear()
        }
    }

    fun setConnectionState(state: BleConnectionState) {
        _connectionState.value = state
    }

    fun simulateDisconnect(
        nextState: BleConnectionState = BleConnectionState.Disconnected
    ) {
        _connectionState.value = nextState
    }

    suspend fun emitNotification(notification: BleCharacteristicNotification) {
        _characteristicNotifications.emit(notification)
    }

    suspend fun emitNotification(
        macAddress: String,
        value: ByteArray,
        serviceUuid: UUID = BleUuids.HEART_RATE_SERVICE_UUID,
        characteristicUuid: UUID = BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID,
        timestampEpochMs: Long = 0L
    ) {
        emitNotification(
            BleCharacteristicNotification(
                macAddress = macAddress,
                serviceUuid = serviceUuid,
                characteristicUuid = characteristicUuid,
                value = value.copyOf(),
                timestampEpochMs = timestampEpochMs
            )
        )
    }

    fun tryEmitNotification(
        macAddress: String,
        value: ByteArray,
        serviceUuid: UUID = BleUuids.HEART_RATE_SERVICE_UUID,
        characteristicUuid: UUID = BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID,
        timestampEpochMs: Long = 0L
    ): Boolean = _characteristicNotifications.tryEmit(
        BleCharacteristicNotification(
            macAddress = macAddress,
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            value = value.copyOf(),
            timestampEpochMs = timestampEpochMs
        )
    )

    override fun scan(serviceUuidFilter: UUID?): Flow<BleScanResult> = channelFlow {
        recordOperation(BleRecordedOperation.ScanStarted(serviceUuidFilter))
        if (!hasScanPermission || !isBluetoothEnabled) {
            close()
            return@channelFlow
        }

        isScanning = true
        if (_connectionState.value is BleConnectionState.Disconnected) {
            _connectionState.value = BleConnectionState.Scanning
        }

        val liveCollector = launch {
            liveScanResults.collect { result ->
                if (matchesFilter(result, serviceUuidFilter)) {
                    send(result)
                }
            }
        }

        val stopCollector = launch {
            scanStoppedSignal.first()
            close()
        }

        availableScanResults
            .filter { matchesFilter(it, serviceUuidFilter) }
            .forEach { send(it) }

        awaitClose {
            liveCollector.cancel()
            stopCollector.cancel()
            isScanning = false
            if (_connectionState.value is BleConnectionState.Scanning) {
                _connectionState.value = BleConnectionState.Disconnected
            }
        }
    }

    override suspend fun stopScan() {
        val wasScanning = isScanning
        isScanning = false
        if (_connectionState.value is BleConnectionState.Scanning) {
            _connectionState.value = BleConnectionState.Disconnected
        }
        if (wasScanning) {
            recordOperation(BleRecordedOperation.ScanStopped)
            scanStoppedSignal.tryEmit(Unit)
        }
    }

    override suspend fun connect(macAddress: String, autoConnect: Boolean): BleOperationResult<Unit> {
        if (isScanning) {
            stopScan()
        }
        recordOperation(BleRecordedOperation.Connect(macAddress, autoConnect))

        if (!hasConnectPermission) {
            return BleOperationResult.Failure(
                status = BleOperationResult.STATUS_PERMISSION_DENIED,
                message = "BLUETOOTH_CONNECT permission denied"
            )
        }
        if (!isBluetoothEnabled) {
            return BleOperationResult.Failure(
                status = BleOperationResult.STATUS_BLUETOOTH_DISABLED,
                message = "Bluetooth is disabled"
            )
        }

        _connectionState.value = BleConnectionState.Connecting(macAddress)
        val result = onConnectAttempt?.invoke(macAddress, autoConnect)
            ?: connectResultsByMac[macAddress]
            ?: defaultConnectResult

        _connectionState.value = when (result) {
            is BleOperationResult.Success -> BleConnectionState.Connected(macAddress)
            is BleOperationResult.Failure -> BleConnectionState.Disconnected
        }
        return result
    }

    override suspend fun disconnect(macAddress: String) {
        recordOperation(BleRecordedOperation.Disconnect(macAddress))
        _connectionState.value = BleConnectionState.Disconnected
    }

    override suspend fun discoverServices(macAddress: String): BleOperationResult<List<BleDiscoveredService>> {
        recordOperation(BleRecordedOperation.DiscoverServices(macAddress))
        ensureConnected(macAddress)?.let { return it }
        discoverServicesFailure?.let { return it }
        val services = discoveredServicesByMac[macAddress] ?: defaultDiscoveredServices
        return BleOperationResult.Success(services)
    }

    override suspend fun setCharacteristicNotification(
        macAddress: String,
        serviceUuid: UUID,
        characteristicUuid: UUID,
        enable: Boolean
    ): BleOperationResult<Unit> {
        recordOperation(
            BleRecordedOperation.SetCharacteristicNotification(
                macAddress = macAddress,
                serviceUuid = serviceUuid,
                characteristicUuid = characteristicUuid,
                enable = enable
            )
        )
        ensureConnected(macAddress)?.let { return it }
        return setNotificationResult
    }

    override suspend fun writeDescriptor(
        macAddress: String,
        serviceUuid: UUID,
        characteristicUuid: UUID,
        descriptorUuid: UUID,
        value: ByteArray
    ): BleOperationResult<Unit> {
        recordOperation(
            BleRecordedOperation.WriteDescriptor(
                macAddress = macAddress,
                serviceUuid = serviceUuid,
                characteristicUuid = characteristicUuid,
                descriptorUuid = descriptorUuid,
                value = value.copyOf()
            )
        )
        ensureConnected(macAddress)?.let { return it }
        return writeDescriptorResult
    }

    override suspend fun readCharacteristic(
        macAddress: String,
        serviceUuid: UUID,
        characteristicUuid: UUID
    ): BleOperationResult<ByteArray> {
        recordOperation(
            BleRecordedOperation.ReadCharacteristic(
                macAddress = macAddress,
                serviceUuid = serviceUuid,
                characteristicUuid = characteristicUuid
            )
        )
        ensureConnected(macAddress)?.let { return it }
        return stubbedReads[ReadKey(macAddress, serviceUuid, characteristicUuid)]
            ?: stubbedReads[ReadKey(null, serviceUuid, characteristicUuid)]
            ?: stubbedReads[ReadKey(null, null, characteristicUuid)]
            ?: defaultReadCharacteristicResult
    }

    override suspend fun writeCharacteristic(
        macAddress: String,
        serviceUuid: UUID,
        characteristicUuid: UUID,
        value: ByteArray,
        writeType: Int
    ): BleOperationResult<Unit> {
        recordOperation(
            BleRecordedOperation.WriteCharacteristic(
                macAddress = macAddress,
                serviceUuid = serviceUuid,
                characteristicUuid = characteristicUuid,
                value = value.copyOf(),
                writeType = writeType
            )
        )
        ensureConnected(macAddress)?.let { return it }
        return writeCharacteristicResult
    }

    override fun close() {
        isScanning = false
        scanStoppedSignal.tryEmit(Unit)
        _connectionState.value = BleConnectionState.Disconnected
        recordOperation(BleRecordedOperation.Closed)
    }

    private fun ensureConnected(macAddress: String): BleOperationResult.Failure? {
        if (!hasConnectPermission) {
            return BleOperationResult.Failure(
                status = BleOperationResult.STATUS_PERMISSION_DENIED,
                message = "BLUETOOTH_CONNECT permission denied"
            )
        }
        if (!isBluetoothEnabled) {
            return BleOperationResult.Failure(
                status = BleOperationResult.STATUS_BLUETOOTH_DISABLED,
                message = "Bluetooth is disabled"
            )
        }
        val state = _connectionState.value
        if (state !is BleConnectionState.Connected || state.macAddress != macAddress) {
            return BleOperationResult.Failure(
                status = BleOperationResult.STATUS_NOT_CONNECTED,
                message = "Device $macAddress is not connected (state=$state)"
            )
        }
        return null
    }

    private fun matchesFilter(result: BleScanResult, serviceUuidFilter: UUID?): Boolean =
        serviceUuidFilter == null || serviceUuidFilter in result.serviceUuids

    private fun recordOperation(operation: BleRecordedOperation) {
        synchronized(_recordedOperations) {
            _recordedOperations.add(operation)
        }
    }

    private data class ReadKey(
        val macAddress: String?,
        val serviceUuid: UUID?,
        val characteristicUuid: UUID
    )

    companion object {
        /**
         * Builds a synthetic Bluetooth SIG 0x2A37 Heart Rate Measurement payload for tests.
         */
        fun buildHeartRateMeasurementPacket(
            bpm: Int,
            sensorContactSupported: Boolean = true,
            sensorContactDetected: Boolean = true,
            rrIntervals1024: List<Int> = emptyList()
        ): ByteArray {
            val is16Bit = bpm > 255
            var flags = 0
            if (is16Bit) flags = flags or 0x01
            if (sensorContactSupported) {
                flags = flags or 0x04
                if (sensorContactDetected) flags = flags or 0x02
            }
            if (rrIntervals1024.isNotEmpty()) flags = flags or 0x10

            val size = 1 + (if (is16Bit) 2 else 1) + (rrIntervals1024.size * 2)
            val bytes = ByteArray(size)
            var index = 0
            bytes[index++] = flags.toByte()
            if (is16Bit) {
                bytes[index++] = (bpm and 0xFF).toByte()
                bytes[index++] = ((bpm shr 8) and 0xFF).toByte()
            } else {
                bytes[index++] = (bpm and 0xFF).toByte()
            }
            rrIntervals1024.forEach { rr ->
                bytes[index++] = (rr and 0xFF).toByte()
                bytes[index++] = ((rr shr 8) and 0xFF).toByte()
            }
            return bytes
        }
    }
}
