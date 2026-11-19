package com.example.heartzonemonitor.data.ble

import java.util.UUID

/**
 * Discovered Bluetooth LE advertisement for a Heart Rate Monitor (HRM).
 */
data class BleScanResult(
    val macAddress: String,
    val advertisedName: String?,
    val rssi: Int,
    val serviceUuids: List<UUID> = emptyList(),
    val timestampEpochMs: Long = 0L
)

/**
 * Connection lifecycle states exposed by [BleTransport].
 */
sealed interface BleConnectionState {
    data object Disconnected : BleConnectionState
    data object Scanning : BleConnectionState
    data class Connecting(val macAddress: String) : BleConnectionState
    data class Connected(val macAddress: String) : BleConnectionState
    data class Reconnecting(val macAddress: String, val attempt: Int) : BleConnectionState
}

/**
 * GATT service and child characteristic UUIDs discovered on a connected Heart Rate Monitor (HRM).
 */
data class BleDiscoveredService(
    val uuid: UUID,
    val characteristicUuids: List<UUID> = emptyList()
)

/**
 * Raw characteristic notification packet emitted by a connected Heart Rate Monitor (HRM).
 */
data class BleCharacteristicNotification(
    val macAddress: String,
    val serviceUuid: UUID,
    val characteristicUuid: UUID,
    val value: ByteArray,
    val timestampEpochMs: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleCharacteristicNotification) return false
        return macAddress == other.macAddress &&
            serviceUuid == other.serviceUuid &&
            characteristicUuid == other.characteristicUuid &&
            value.contentEquals(other.value) &&
            timestampEpochMs == other.timestampEpochMs
    }

    override fun hashCode(): Int {
        var result = macAddress.hashCode()
        result = 31 * result + serviceUuid.hashCode()
        result = 31 * result + characteristicUuid.hashCode()
        result = 31 * result + value.contentHashCode()
        result = 31 * result + timestampEpochMs.hashCode()
        return result
    }
}

/**
 * Outcome wrapper for asynchronous GATT operations executed through [BleTransport].
 */
sealed class BleOperationResult<out T> {
    data class Success<out T>(val value: T) : BleOperationResult<T>()
    data class Failure(val status: Int, val message: String) : BleOperationResult<Nothing>()

    companion object {
        const val STATUS_PERMISSION_DENIED = -1
        const val STATUS_BLUETOOTH_DISABLED = -2
        const val STATUS_BLUETOOTH_UNAVAILABLE = -3
        const val STATUS_INVALID_ARGUMENT = -4
        const val STATUS_NOT_CONNECTED = -5
        const val STATUS_SERVICE_OR_CHAR_NOT_FOUND = -6
        const val STATUS_OPERATION_FAILED = -7
        const val STATUS_TIMEOUT = -8
        const val STATUS_DISCONNECTED = -9
    }
}
