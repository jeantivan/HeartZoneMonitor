package com.example.heartzonemonitor.data.ble

import android.bluetooth.BluetoothGattCharacteristic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Hardware boundary seam encapsulating Android Bluetooth LE scanning and GATT operations
 * for communicating with a Heart Rate Monitor (HRM).
 */
interface BleTransport {
    val isBluetoothEnabled: Boolean
    val connectionState: StateFlow<BleConnectionState>
    val characteristicNotifications: Flow<BleCharacteristicNotification>

    fun scan(serviceUuidFilter: UUID? = BleUuids.HEART_RATE_SERVICE_UUID): Flow<BleScanResult>

    suspend fun stopScan()

    suspend fun connect(macAddress: String, autoConnect: Boolean = false): BleOperationResult<Unit>

    suspend fun disconnect(macAddress: String)

    suspend fun discoverServices(macAddress: String): BleOperationResult<List<BleDiscoveredService>>

    suspend fun setCharacteristicNotification(
        macAddress: String,
        serviceUuid: UUID,
        characteristicUuid: UUID,
        enable: Boolean
    ): BleOperationResult<Unit>

    suspend fun writeDescriptor(
        macAddress: String,
        serviceUuid: UUID,
        characteristicUuid: UUID,
        descriptorUuid: UUID,
        value: ByteArray
    ): BleOperationResult<Unit>

    suspend fun readCharacteristic(
        macAddress: String,
        serviceUuid: UUID,
        characteristicUuid: UUID
    ): BleOperationResult<ByteArray>

    suspend fun writeCharacteristic(
        macAddress: String,
        serviceUuid: UUID,
        characteristicUuid: UUID,
        value: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    ): BleOperationResult<Unit>

    fun close()
}
