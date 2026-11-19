package com.example.heartzonemonitor.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Production Android BLE implementation of [BleTransport] wrapping [BluetoothManager],
 * [BluetoothAdapter], [BluetoothLeScanner], and [BluetoothGatt].
 *
 * Enforces runtime permission and adapter checks before BLE calls, stops any active scan before
 * opening a GATT link, serializes GATT commands to prevent Error 133 collisions, and always closes
 * [BluetoothGatt] resources on disconnect or failure.
 */
class AndroidBleTransport(
    private val context: Context,
    private val permissionChecker: BlePermissionChecker = AndroidBlePermissionChecker(context),
    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(BluetoothManager::class.java),
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val clockEpochMs: () -> Long = { System.currentTimeMillis() },
    private val operationTimeoutMs: Long = DEFAULT_OPERATION_TIMEOUT_MS
) : BleTransport {

    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    override val isBluetoothEnabled: Boolean
        get() = try {
            bluetoothAdapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        }

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    override val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _characteristicNotifications = MutableSharedFlow<BleCharacteristicNotification>(
        extraBufferCapacity = NOTIFICATION_BUFFER_CAPACITY
    )
    override val characteristicNotifications: Flow<BleCharacteristicNotification> =
        _characteristicNotifications.asSharedFlow()

    private val scanLock = Any()
    private var activeScanner: BluetoothLeScanner? = null
    private var activeScanCallback: ScanCallback? = null
    private var activeScanClose: (() -> Unit)? = null

    private val gattMutex = Mutex()
    private val gattStateLock = Any()
    private var activeGatt: BluetoothGatt? = null

    private var pendingConnect: CompletableDeferred<BleOperationResult<Unit>>? = null
    private var pendingDiscoverServices: CompletableDeferred<BleOperationResult<List<BleDiscoveredService>>>? = null
    private var pendingReadCharacteristic: CompletableDeferred<BleOperationResult<ByteArray>>? = null
    private var pendingWriteCharacteristic: CompletableDeferred<BleOperationResult<Unit>>? = null
    private var pendingWriteDescriptor: CompletableDeferred<BleOperationResult<Unit>>? = null

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device?.address.orEmpty()
            synchronized(gattStateLock) {
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    if (activeGatt == null || activeGatt === gatt) {
                        activeGatt = gatt
                        _connectionState.value = BleConnectionState.Connected(address)
                        pendingConnect?.complete(BleOperationResult.Success(Unit))
                        pendingConnect = null
                    } else {
                        safeCloseGatt(gatt)
                    }
                    return
                }

                // Disconnect or error: always close BluetoothGatt to prevent Error 133 resource leaks.
                safeCloseGatt(gatt)
                if (activeGatt === gatt || activeGatt == null) {
                    activeGatt = null
                    _connectionState.value = BleConnectionState.Disconnected
                    val failure = BleOperationResult.Failure(
                        status = status,
                        message = "GATT connection closed or failed (status=$status, state=$newState)"
                    )
                    pendingConnect?.complete(failure)
                    pendingConnect = null
                    failPendingOperationsLocked(failure)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            synchronized(gattStateLock) {
                val deferred = pendingDiscoverServices ?: return
                pendingDiscoverServices = null
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val discovered = safeGetDiscoveredServices(gatt)
                    deferred.complete(BleOperationResult.Success(discovered))
                } else {
                    deferred.complete(
                        BleOperationResult.Failure(
                            status = status,
                            message = "GATT service discovery failed with status $status"
                        )
                    )
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            completeReadDeferred(value.copyOf(), status)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (sdkInt < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                val value = characteristic.value?.copyOf() ?: ByteArray(0)
                completeReadDeferred(value, status)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            synchronized(gattStateLock) {
                val deferred = pendingWriteCharacteristic ?: return
                pendingWriteCharacteristic = null
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    deferred.complete(BleOperationResult.Success(Unit))
                } else {
                    deferred.complete(
                        BleOperationResult.Failure(
                            status = status,
                            message = "Characteristic write failed with GATT status $status"
                        )
                    )
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            synchronized(gattStateLock) {
                val deferred = pendingWriteDescriptor ?: return
                pendingWriteDescriptor = null
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    deferred.complete(BleOperationResult.Success(Unit))
                } else {
                    deferred.complete(
                        BleOperationResult.Failure(
                            status = status,
                            message = "Descriptor write failed with GATT status $status"
                        )
                    )
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            emitCharacteristicNotification(gatt, characteristic, value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (sdkInt < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                characteristic.value?.let { emitCharacteristicNotification(gatt, characteristic, it) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun scan(serviceUuidFilter: UUID?): Flow<BleScanResult> = callbackFlow {
        val permissions = permissionChecker.checkPermissions()
        if (!permissions.hasScanPermission || !isBluetoothEnabled) {
            close()
            return@callbackFlow
        }

        val scanner = try {
            bluetoothAdapter?.bluetoothLeScanner
        } catch (_: SecurityException) {
            null
        }
        if (scanner == null) {
            close()
            return@callbackFlow
        }

        stopScanSync(expectedCallback = null)

        val filters = if (serviceUuidFilter != null) {
            listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(serviceUuidFilter))
                    .build()
            )
        } else {
            emptyList()
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                toBleScanResult(result)?.let { trySend(it) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result ->
                    toBleScanResult(result)?.let { trySend(it) }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                stopScanSync(expectedCallback = this)
                close()
            }
        }

        synchronized(scanLock) {
            activeScanner = scanner
            activeScanCallback = callback
            activeScanClose = { close() }
            if (_connectionState.value is BleConnectionState.Disconnected) {
                _connectionState.value = BleConnectionState.Scanning
            }
        }

        val started = try {
            scanner.startScan(filters, settings, callback)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalStateException) {
            false
        }

        if (!started) {
            stopScanSync(expectedCallback = callback)
            close()
            return@callbackFlow
        }

        awaitClose {
            stopScanSync(expectedCallback = callback)
        }
    }

    override suspend fun stopScan() {
        stopScanSync(expectedCallback = null)
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(macAddress: String, autoConnect: Boolean): BleOperationResult<Unit> {
        stopScanSync(expectedCallback = null)

        val permissions = permissionChecker.checkPermissions()
        if (!permissions.hasConnectPermission) {
            return BleOperationResult.Failure(
                status = BleOperationResult.STATUS_PERMISSION_DENIED,
                message = "BLUETOOTH_CONNECT permission is required to connect to a Heart Rate Monitor"
            )
        }

        val adapter = bluetoothAdapter
            ?: return BleOperationResult.Failure(
                status = BleOperationResult.STATUS_BLUETOOTH_UNAVAILABLE,
                message = "Bluetooth adapter is unavailable on this device"
            )

        if (!isBluetoothEnabled) {
            return BleOperationResult.Failure(
                status = BleOperationResult.STATUS_BLUETOOTH_DISABLED,
                message = "Bluetooth is disabled"
            )
        }

        if (!BluetoothAdapter.checkBluetoothAddress(macAddress)) {
            return BleOperationResult.Failure(
                status = BleOperationResult.STATUS_INVALID_ARGUMENT,
                message = "Invalid Bluetooth MAC address: $macAddress"
            )
        }

        val device = try {
            adapter.getRemoteDevice(macAddress)
        } catch (e: IllegalArgumentException) {
            return BleOperationResult.Failure(
                status = BleOperationResult.STATUS_INVALID_ARGUMENT,
                message = e.message ?: "Invalid Bluetooth MAC address: $macAddress"
            )
        }

        return gattMutex.withLock {
            val deferred = CompletableDeferred<BleOperationResult<Unit>>()
            var connectFailure: BleOperationResult.Failure? = null
            val gatt = synchronized(gattStateLock) {
                closeActiveGattLocked()
                pendingConnect = deferred
                _connectionState.value = BleConnectionState.Connecting(macAddress)
                try {
                    @Suppress("DEPRECATION")
                    device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } catch (e: SecurityException) {
                    pendingConnect = null
                    _connectionState.value = BleConnectionState.Disconnected
                    connectFailure = BleOperationResult.Failure(
                        status = BleOperationResult.STATUS_PERMISSION_DENIED,
                        message = e.message ?: "Missing Bluetooth connect permission"
                    )
                    null
                }
            }

            connectFailure?.let { return@withLock it }

            if (gatt == null) {
                synchronized(gattStateLock) {
                    pendingConnect = null
                    _connectionState.value = BleConnectionState.Disconnected
                }
                return@withLock BleOperationResult.Failure(
                    status = BleOperationResult.STATUS_OPERATION_FAILED,
                    message = "connectGatt returned null for $macAddress"
                )
            }

            synchronized(gattStateLock) {
                if (deferred.isCompleted) {
                    if (activeGatt == null) {
                        safeCloseGatt(gatt)
                    }
                } else {
                    activeGatt = gatt
                }
            }

            val result = withTimeoutOrNull(operationTimeoutMs) { deferred.await() }
            if (result == null) {
                synchronized(gattStateLock) {
                    pendingConnect = null
                    closeActiveGattLocked()
                    _connectionState.value = BleConnectionState.Disconnected
                }
                BleOperationResult.Failure(
                    status = BleOperationResult.STATUS_TIMEOUT,
                    message = "Timed out connecting to $macAddress"
                )
            } else {
                result
            }
        }
    }

    override suspend fun disconnect(macAddress: String) {
        synchronized(gattStateLock) {
            val current = activeGatt
            if (current == null || current.device?.address == macAddress) {
                closeActiveGattLocked()
                _connectionState.value = BleConnectionState.Disconnected
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun discoverServices(macAddress: String): BleOperationResult<List<BleDiscoveredService>> =
        executeAsyncGattOperation(
            macAddress = macAddress,
            timeoutMessage = "Timed out discovering services on $macAddress",
            registerDeferred = { deferred -> pendingDiscoverServices = deferred },
            clearDeferred = { pendingDiscoverServices = null },
            initiate = { gatt ->
                if (!gatt.discoverServices()) {
                    BleOperationResult.Failure(
                        status = BleOperationResult.STATUS_OPERATION_FAILED,
                        message = "BluetoothGatt.discoverServices returned false"
                    )
                } else {
                    null
                }
            }
        )

    @SuppressLint("MissingPermission")
    override suspend fun setCharacteristicNotification(
        macAddress: String,
        serviceUuid: UUID,
        characteristicUuid: UUID,
        enable: Boolean
    ): BleOperationResult<Unit> = gattMutex.withLock {
        val gatt = validateConnectedGatt(macAddress)
            ?: return@withLock failureForUnavailableConnection(macAddress)
        val characteristic = findCharacteristic(gatt, serviceUuid, characteristicUuid)
            ?: return@withLock failureForMissingCharacteristic(serviceUuid, characteristicUuid)

        try {
            val success = gatt.setCharacteristicNotification(characteristic, enable)
            if (success) {
                BleOperationResult.Success(Unit)
            } else {
                BleOperationResult.Failure(
                    status = BleOperationResult.STATUS_OPERATION_FAILED,
                    message = "setCharacteristicNotification returned false for $characteristicUuid"
                )
            }
        } catch (e: SecurityException) {
            BleOperationResult.Failure(
                status = BleOperationResult.STATUS_PERMISSION_DENIED,
                message = e.message ?: "Missing Bluetooth connect permission"
            )
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun writeDescriptor(
        macAddress: String,
        serviceUuid: UUID,
        characteristicUuid: UUID,
        descriptorUuid: UUID,
        value: ByteArray
    ): BleOperationResult<Unit> = executeAsyncGattOperation(
        macAddress = macAddress,
        timeoutMessage = "Timed out writing descriptor $descriptorUuid",
        registerDeferred = { deferred -> pendingWriteDescriptor = deferred },
        clearDeferred = { pendingWriteDescriptor = null },
        initiate = { gatt ->
            val characteristic = findCharacteristic(gatt, serviceUuid, characteristicUuid)
                ?: return@executeAsyncGattOperation failureForMissingCharacteristic(serviceUuid, characteristicUuid)
            val descriptor = characteristic.getDescriptor(descriptorUuid)
                ?: return@executeAsyncGattOperation BleOperationResult.Failure(
                    status = BleOperationResult.STATUS_SERVICE_OR_CHAR_NOT_FOUND,
                    message = "Descriptor $descriptorUuid not found on $characteristicUuid"
                )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                val status = gatt.writeDescriptor(descriptor, value)
                if (status != BluetoothStatusCodes.SUCCESS) {
                    BleOperationResult.Failure(
                        status = status,
                        message = "writeDescriptor failed with status $status"
                    )
                } else {
                    null
                }
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                if (!gatt.writeDescriptor(descriptor)) {
                    BleOperationResult.Failure(
                        status = BleOperationResult.STATUS_OPERATION_FAILED,
                        message = "writeDescriptor returned false"
                    )
                } else {
                    null
                }
            }
        }
    )

    @SuppressLint("MissingPermission")
    override suspend fun readCharacteristic(
        macAddress: String,
        serviceUuid: UUID,
        characteristicUuid: UUID
    ): BleOperationResult<ByteArray> = executeAsyncGattOperation(
        macAddress = macAddress,
        timeoutMessage = "Timed out reading characteristic $characteristicUuid",
        registerDeferred = { deferred -> pendingReadCharacteristic = deferred },
        clearDeferred = { pendingReadCharacteristic = null },
        initiate = { gatt ->
            val characteristic = findCharacteristic(gatt, serviceUuid, characteristicUuid)
                ?: return@executeAsyncGattOperation failureForMissingCharacteristic(serviceUuid, characteristicUuid)
            if (!gatt.readCharacteristic(characteristic)) {
                BleOperationResult.Failure(
                    status = BleOperationResult.STATUS_OPERATION_FAILED,
                    message = "readCharacteristic returned false"
                )
            } else {
                null
            }
        }
    )

    @SuppressLint("MissingPermission")
    override suspend fun writeCharacteristic(
        macAddress: String,
        serviceUuid: UUID,
        characteristicUuid: UUID,
        value: ByteArray,
        writeType: Int
    ): BleOperationResult<Unit> = executeAsyncGattOperation(
        macAddress = macAddress,
        timeoutMessage = "Timed out writing characteristic $characteristicUuid",
        registerDeferred = { deferred -> pendingWriteCharacteristic = deferred },
        clearDeferred = { pendingWriteCharacteristic = null },
        initiate = { gatt ->
            val characteristic = findCharacteristic(gatt, serviceUuid, characteristicUuid)
                ?: return@executeAsyncGattOperation failureForMissingCharacteristic(serviceUuid, characteristicUuid)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                val status = gatt.writeCharacteristic(characteristic, value, writeType)
                if (status != BluetoothStatusCodes.SUCCESS) {
                    BleOperationResult.Failure(
                        status = status,
                        message = "writeCharacteristic failed with status $status"
                    )
                } else {
                    null
                }
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = writeType
                @Suppress("DEPRECATION")
                characteristic.value = value
                @Suppress("DEPRECATION")
                if (!gatt.writeCharacteristic(characteristic)) {
                    BleOperationResult.Failure(
                        status = BleOperationResult.STATUS_OPERATION_FAILED,
                        message = "writeCharacteristic returned false"
                    )
                } else {
                    null
                }
            }
        }
    )

    override fun close() {
        stopScanSync(expectedCallback = null)
        synchronized(gattStateLock) {
            closeActiveGattLocked()
            _connectionState.value = BleConnectionState.Disconnected
        }
    }

    private suspend fun <T> executeAsyncGattOperation(
        macAddress: String,
        timeoutMessage: String,
        registerDeferred: (CompletableDeferred<BleOperationResult<T>>) -> Unit,
        clearDeferred: () -> Unit,
        initiate: (BluetoothGatt) -> BleOperationResult.Failure?
    ): BleOperationResult<T> = gattMutex.withLock {
        val gatt = validateConnectedGatt(macAddress)
            ?: return@withLock failureForUnavailableConnection(macAddress)

        val deferred = CompletableDeferred<BleOperationResult<T>>()
        val immediateFailure = synchronized(gattStateLock) {
            registerDeferred(deferred)
            val failure = try {
                initiate(gatt)
            } catch (e: SecurityException) {
                BleOperationResult.Failure(
                    status = BleOperationResult.STATUS_PERMISSION_DENIED,
                    message = e.message ?: "Missing Bluetooth connect permission"
                )
            }
            if (failure != null) {
                clearDeferred()
            }
            failure
        }
        if (immediateFailure != null) {
            return@withLock immediateFailure
        }

        val result = withTimeoutOrNull(operationTimeoutMs) { deferred.await() }
        if (result == null) {
            synchronized(gattStateLock) {
                clearDeferred()
            }
            BleOperationResult.Failure(
                status = BleOperationResult.STATUS_TIMEOUT,
                message = timeoutMessage
            )
        } else {
            result
        }
    }

    private fun validateConnectedGatt(macAddress: String): BluetoothGatt? {
        if (!permissionChecker.checkPermissions().hasConnectPermission) return null
        if (!isBluetoothEnabled) return null
        return synchronized(gattStateLock) {
            val current = activeGatt ?: return@synchronized null
            if (_connectionState.value !is BleConnectionState.Connected) return@synchronized null
            if (current.device?.address != macAddress) return@synchronized null
            current
        }
    }

    private fun failureForUnavailableConnection(macAddress: String): BleOperationResult.Failure {
        val permissions = permissionChecker.checkPermissions()
        return when {
            !permissions.hasConnectPermission -> BleOperationResult.Failure(
                status = BleOperationResult.STATUS_PERMISSION_DENIED,
                message = "BLUETOOTH_CONNECT permission is required"
            )
            !isBluetoothEnabled -> BleOperationResult.Failure(
                status = BleOperationResult.STATUS_BLUETOOTH_DISABLED,
                message = "Bluetooth is disabled"
            )
            else -> BleOperationResult.Failure(
                status = BleOperationResult.STATUS_NOT_CONNECTED,
                message = "Not connected to $macAddress"
            )
        }
    }

    private fun failureForMissingCharacteristic(
        serviceUuid: UUID,
        characteristicUuid: UUID
    ): BleOperationResult.Failure = BleOperationResult.Failure(
        status = BleOperationResult.STATUS_SERVICE_OR_CHAR_NOT_FOUND,
        message = "Characteristic $characteristicUuid not found under service $serviceUuid"
    )

    private fun findCharacteristic(
        gatt: BluetoothGatt,
        serviceUuid: UUID,
        characteristicUuid: UUID
    ): BluetoothGattCharacteristic? =
        gatt.getService(serviceUuid)?.getCharacteristic(characteristicUuid)

    @SuppressLint("MissingPermission")
    private fun stopScanSync(expectedCallback: ScanCallback?) {
        val scanner: BluetoothLeScanner?
        val callback: ScanCallback?
        val closeFlow: (() -> Unit)?
        synchronized(scanLock) {
            if (expectedCallback != null && activeScanCallback !== expectedCallback) {
                return
            }
            scanner = activeScanner
            callback = activeScanCallback
            closeFlow = activeScanClose
            activeScanner = null
            activeScanCallback = null
            activeScanClose = null
            if (_connectionState.value is BleConnectionState.Scanning) {
                _connectionState.value = BleConnectionState.Disconnected
            }
        }
        if (scanner != null && callback != null) {
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
            } catch (_: IllegalStateException) {
            }
        }
        closeFlow?.invoke()
    }

    @SuppressLint("MissingPermission")
    private fun toBleScanResult(result: ScanResult): BleScanResult? {
        val device = result.device ?: return null
        val address = device.address ?: return null
        val scanRecord = result.scanRecord
        val advertisedName = scanRecord?.deviceName ?: safeDeviceName(device)
        val serviceUuids = scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
        return BleScanResult(
            macAddress = address,
            advertisedName = advertisedName,
            rssi = result.rssi,
            serviceUuids = serviceUuids,
            timestampEpochMs = clockEpochMs()
        )
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice): String? =
        if (permissionChecker.checkPermissions().hasConnectPermission) {
            try {
                device.name
            } catch (_: SecurityException) {
                null
            }
        } else {
            null
        }

    private fun safeGetDiscoveredServices(gatt: BluetoothGatt): List<BleDiscoveredService> =
        gatt.services.orEmpty().map { service ->
            BleDiscoveredService(
                uuid = service.uuid,
                characteristicUuids = service.characteristics.orEmpty().map { it.uuid }
            )
        }

    private fun emitCharacteristicNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        val notification = BleCharacteristicNotification(
            macAddress = gatt.device?.address.orEmpty(),
            serviceUuid = characteristic.service?.uuid ?: BleUuids.HEART_RATE_SERVICE_UUID,
            characteristicUuid = characteristic.uuid,
            value = value.copyOf(),
            timestampEpochMs = clockEpochMs()
        )
        _characteristicNotifications.tryEmit(notification)
    }

    private fun completeReadDeferred(value: ByteArray, status: Int) {
        synchronized(gattStateLock) {
            val deferred = pendingReadCharacteristic ?: return
            pendingReadCharacteristic = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                deferred.complete(BleOperationResult.Success(value))
            } else {
                deferred.complete(
                    BleOperationResult.Failure(
                        status = status,
                        message = "Characteristic read failed with GATT status $status"
                    )
                )
            }
        }
    }

    private fun closeActiveGattLocked() {
        val gatt = activeGatt
        activeGatt = null
        if (gatt != null) {
            safeCloseGatt(gatt)
        }
        val disconnectFailure = BleOperationResult.Failure(
            status = BleOperationResult.STATUS_DISCONNECTED,
            message = "GATT connection closed"
        )
        pendingConnect?.complete(disconnectFailure)
        pendingConnect = null
        failPendingOperationsLocked(disconnectFailure)
    }

    private fun failPendingOperationsLocked(failure: BleOperationResult.Failure) {
        pendingDiscoverServices?.complete(failure)
        pendingDiscoverServices = null
        pendingReadCharacteristic?.complete(failure)
        pendingReadCharacteristic = null
        pendingWriteCharacteristic?.complete(failure)
        pendingWriteCharacteristic = null
        pendingWriteDescriptor?.complete(failure)
        pendingWriteDescriptor = null
    }

    @SuppressLint("MissingPermission")
    private fun safeCloseGatt(gatt: BluetoothGatt) {
        try {
            gatt.disconnect()
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
        try {
            gatt.close()
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    companion object {
        const val DEFAULT_OPERATION_TIMEOUT_MS = 15_000L
        private const val NOTIFICATION_BUFFER_CAPACITY = 64
    }
}
