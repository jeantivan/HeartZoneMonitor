package com.example.heartzonemonitor.data.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Snapshot of a discovered Bluetooth LE Heart Rate Monitor (HRM) advertising the standard
 * Bluetooth SIG Heart Rate Service (`0x180D`).
 */
data class DiscoveredHrm(
    val macAddress: String,
    val advertisedName: String?,
    val rssi: Int,
    val lastSeenEpochMs: Long
)

/**
 * Reasons Bluetooth LE Heart Rate Monitor (HRM) discovery cannot start or continue.
 */
enum class BleDiscoveryUnavailableReason {
    BLUETOOTH_DISABLED,
    PERMISSION_DENIED
}

/**
 * Lifecycle states emitted by [BleDeviceDiscoveryService].
 */
sealed interface BleDiscoveryState {
    data object Idle : BleDiscoveryState
    data class Scanning(val devices: List<DiscoveredHrm>) : BleDiscoveryState
    data class TimedOut(val devices: List<DiscoveredHrm>) : BleDiscoveryState
    data class Stopped(val devices: List<DiscoveredHrm>) : BleDiscoveryState
    data class Unavailable(val reason: BleDiscoveryUnavailableReason) : BleDiscoveryState
}

/**
 * Coordinates Bluetooth LE discovery exclusively for Heart Rate Monitors (HRM) advertising
 * [BleUuids.HEART_RATE_SERVICE_UUID] (`0x180D`), deduplicating and sorting live signal strength
 * updates, enforcing a scan timeout, and stopping active scans prior to GATT connections to
 * prevent Error 133 collisions.
 */
class BleDeviceDiscoveryService(
    private val bleTransport: BleTransport,
    private val scope: CoroutineScope,
    private val permissionChecker: BlePermissionChecker? = null,
    private val scanTimeoutMs: Long = DEFAULT_SCAN_TIMEOUT_MS
) {

    init {
        require(scanTimeoutMs > 0L) { "scanTimeoutMs must be positive" }
    }

    private val stateLock = Any()
    private val discoveredByMac = LinkedHashMap<String, DiscoveredHrm>()

    private var scanGeneration: Long = 0L
    private var lastScanJob: Job? = null
    private var activeSession: ScanSession? = null

    private val _state = MutableStateFlow<BleDiscoveryState>(BleDiscoveryState.Idle)
    val state: StateFlow<BleDiscoveryState> = _state.asStateFlow()

    private val _discoveredHrms = MutableStateFlow<List<DiscoveredHrm>>(emptyList())
    val discoveredHrms: StateFlow<List<DiscoveredHrm>> = _discoveredHrms.asStateFlow()

    /**
     * Begins scanning for Bluetooth LE Heart Rate Monitors (HRM) advertising the `0x180D` service.
     * Cancels any prior active scan, clears stale results, and enforces a [scanTimeoutMs] window.
     */
    fun startScan() {
        val previousJob: Job?
        val generation: Long
        val session: ScanSession
        val newJob: Job

        synchronized(stateLock) {
            generation = ++scanGeneration
            previousJob = lastScanJob
            previousJob?.cancel()
            activeSession = null

            if (permissionChecker != null && !permissionChecker.checkPermissions().hasScanPermission) {
                _state.value = BleDiscoveryState.Unavailable(
                    BleDiscoveryUnavailableReason.PERMISSION_DENIED
                )
                return
            }

            if (!bleTransport.isBluetoothEnabled) {
                _state.value = BleDiscoveryState.Unavailable(
                    BleDiscoveryUnavailableReason.BLUETOOTH_DISABLED
                )
                return
            }

            discoveredByMac.clear()
            _discoveredHrms.value = emptyList()
            _state.value = BleDiscoveryState.Scanning(emptyList())

            session = ScanSession(generation)
            activeSession = session

            newJob = scope.launch(start = CoroutineStart.LAZY) {
                previousJob?.join()
                if (!isActive || !isCurrentSession(generation, session)) {
                    return@launch
                }

                // Trigger transport stopScan() from a sibling child coroutine before cancelling
                // collection so the transport closes the scan stream while scanning is active.
                val timeoutJob = launch {
                    delay(scanTimeoutMs)
                    if (isCurrentSession(generation, session)) {
                        session.timedOut = true
                        bleTransport.stopScan()
                    }
                }

                try {
                    bleTransport.scan(BleUuids.HEART_RATE_SERVICE_UUID).collect { result ->
                        if (BleUuids.HEART_RATE_SERVICE_UUID in result.serviceUuids) {
                            upsertDiscoveredDevice(result, generation, session)
                        }
                    }
                } finally {
                    timeoutJob.cancel()
                    if (isActive) {
                        handleScanCollectionFinished(generation, session)
                    }
                }
            }
            lastScanJob = newJob
        }

        newJob.start()
    }

    /**
     * Stops any active Heart Rate Monitor (HRM) scan cleanly and transitions [state] from
     * [BleDiscoveryState.Scanning] to [BleDiscoveryState.Stopped] while retaining discovered devices.
     */
    suspend fun stopScan() {
        val targetGeneration: Long
        val targetJob: Job?
        val targetSession: ScanSession?

        synchronized(stateLock) {
            targetGeneration = scanGeneration
            targetJob = lastScanJob
            targetSession = activeSession
            targetSession?.manuallyStopped = true
        }

        bleTransport.stopScan()
        targetJob?.cancelAndJoin()

        synchronized(stateLock) {
            if (lastScanJob === targetJob) {
                lastScanJob = null
            }
            if (activeSession === targetSession) {
                activeSession = null
            }
            if (scanGeneration == targetGeneration && _state.value is BleDiscoveryState.Scanning) {
                _state.value = BleDiscoveryState.Stopped(buildSortedSnapshotLocked())
            }
        }
    }

    /**
     * Stops any active scan before initiating a GATT connection to the Heart Rate Monitor (HRM)
     * identified by [macAddress] to prevent GATT Error 133 collisions.
     */
    suspend fun connectToHrm(
        macAddress: String,
        autoConnect: Boolean = false
    ): BleOperationResult<Unit> {
        stopScan()
        return bleTransport.connect(macAddress, autoConnect)
    }

    private fun isCurrentSession(generation: Long, session: ScanSession): Boolean =
        synchronized(stateLock) {
            scanGeneration == generation &&
                activeSession === session &&
                !session.manuallyStopped
        }

    private fun upsertDiscoveredDevice(
        result: BleScanResult,
        generation: Long,
        session: ScanSession
    ) {
        synchronized(stateLock) {
            if (scanGeneration != generation ||
                activeSession !== session ||
                session.manuallyStopped ||
                _state.value !is BleDiscoveryState.Scanning
            ) {
                return
            }

            val existing = discoveredByMac[result.macAddress]
            val resolvedName = result.advertisedName ?: existing?.advertisedName
            val updated = DiscoveredHrm(
                macAddress = result.macAddress,
                advertisedName = resolvedName,
                rssi = result.rssi,
                lastSeenEpochMs = result.timestampEpochMs
            )
            discoveredByMac[result.macAddress] = updated

            val snapshot = buildSortedSnapshotLocked()
            _discoveredHrms.value = snapshot
            _state.value = BleDiscoveryState.Scanning(snapshot)
        }
    }

    private fun handleScanCollectionFinished(
        generation: Long,
        session: ScanSession
    ) {
        synchronized(stateLock) {
            if (scanGeneration != generation ||
                activeSession !== session ||
                session.manuallyStopped
            ) {
                return
            }
            activeSession = null
            val snapshot = buildSortedSnapshotLocked()
            when {
                session.timedOut -> {
                    _state.value = BleDiscoveryState.TimedOut(snapshot)
                }
                permissionChecker != null && !permissionChecker.checkPermissions().hasScanPermission -> {
                    _state.value = BleDiscoveryState.Unavailable(
                        BleDiscoveryUnavailableReason.PERMISSION_DENIED
                    )
                }
                !bleTransport.isBluetoothEnabled -> {
                    _state.value = BleDiscoveryState.Unavailable(
                        BleDiscoveryUnavailableReason.BLUETOOTH_DISABLED
                    )
                }
                _state.value is BleDiscoveryState.Scanning -> {
                    _state.value = BleDiscoveryState.Stopped(snapshot)
                }
            }
        }
    }

    private fun buildSortedSnapshotLocked(): List<DiscoveredHrm> =
        discoveredByMac.values.sortedWith(
            compareByDescending<DiscoveredHrm> { it.rssi }
                .thenBy { it.macAddress }
        )

    private class ScanSession(val generation: Long) {
        @Volatile
        var manuallyStopped: Boolean = false

        @Volatile
        var timedOut: Boolean = false
    }

    companion object {
        const val DEFAULT_SCAN_TIMEOUT_MS = 30_000L
    }
}
