package com.example.heartzonemonitor.data.ble

import com.example.heartzonemonitor.data.local.dao.RememberedHrmDao
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * UI-ready lifecycle states emitted by [ActiveHrmAutoConnector] when attempting a direct
 * background connection to the designated Active HRM upon entering the Live Session screen.
 */
sealed interface AutoConnectState {
    data object Idle : AutoConnectState
    data class Connecting(
        val macAddress: String,
        val hrmDisplayName: String
    ) : AutoConnectState

    data class Connected(
        val macAddress: String,
        val hrmDisplayName: String
    ) : AutoConnectState

    data class DormantPrompt(
        val macAddress: String,
        val hrmDisplayName: String
    ) : AutoConnectState
}

/**
 * Resolves the user-facing display name for a Remembered HRM in priority order:
 * custom nickname -> advertised name -> MAC address.
 */
fun resolveHrmDisplayName(hrm: RememberedHrmEntity): String =
    hrm.customNickname?.takeIf { it.isNotBlank() }
        ?: hrm.advertisedName.takeIf { it.isNotBlank() }
        ?: hrm.macAddress

/**
 * Coordinates immediate, non-blocking direct background BLE connection (`autoConnect = false`)
 * to the designated Active HRM upon entering the Live Session screen, enforcing a strict
 * 10-second timeout and initializing the connected GATT session (`0x180D` / `0x2A37` / `0x2902` /
 * `0x2A19`) through the shared [HrmGattClient].
 *
 * When a chest strap remains dormant past [autoConnectTimeoutMs], cleans up the pending link and
 * transitions to [AutoConnectState.DormantPrompt] without interfering with active Sessions or
 * mid-session reconnection loops.
 */
class ActiveHrmAutoConnector(
    private val bleTransport: BleTransport,
    private val hrmGattClient: HrmGattClient,
    private val rememberedHrmDao: RememberedHrmDao,
    scope: CoroutineScope,
    private val isSessionActiveProvider: () -> Boolean = { false },
    private val sessionConnectionStateProvider: () -> BleConnectionState = {
        bleTransport.connectionState.value
    },
    private val permissionChecker: BlePermissionChecker? = null,
    private val autoConnectTimeoutMs: Long = DEFAULT_AUTO_CONNECT_TIMEOUT_MS
) {

    init {
        require(autoConnectTimeoutMs > 0L) {
            "autoConnectTimeoutMs must be positive"
        }
    }

    private val connectorJob = SupervisorJob(scope.coroutineContext[Job])
    private val connectorScope = CoroutineScope(scope.coroutineContext + connectorJob)

    private val stateLock = Any()
    private var currentActiveHrm: RememberedHrmEntity? = null
    private var attemptGeneration: Long = 0L
    private var inFlightJob: Job? = null
    private var inFlightMacAddress: String? = null
    private var inFlightGeneration: Long = 0L

    private val _state = MutableStateFlow<AutoConnectState>(AutoConnectState.Idle)
    val state: StateFlow<AutoConnectState> = _state.asStateFlow()

    init {
        connectorScope.launch(start = CoroutineStart.UNDISPATCHED) {
            rememberedHrmDao.observeActiveHrm().collect { activeHrm ->
                onActiveHrmChanged(activeHrm)
            }
        }
        connectorScope.launch(start = CoroutineStart.UNDISPATCHED) {
            bleTransport.connectionState.collect { connectionState ->
                onConnectionStateChanged(connectionState)
            }
        }
    }

    /**
     * Initiates a direct non-blocking background connection (`autoConnect = false`) to the
     * current Active HRM with a strict timeout of [autoConnectTimeoutMs].
     *
     * Short-circuits immediately when already connected to the Active HRM, and does nothing
     * when a Session is already active or mid-session reconnection is in progress.
     */
    fun startAutoConnect(): Job {
        val previousJob: Job?
        val generation: Long
        val newJob: Job

        synchronized(stateLock) {
            if (isBlockedByActiveSessionOrReconnection()) {
                return Job().apply { complete() }
            }

            val existingJob = inFlightJob
            val cachedActive = currentActiveHrm
            if (existingJob != null && existingJob.isActive &&
                (cachedActive == null || inFlightMacAddress == cachedActive.macAddress)
            ) {
                return existingJob
            }

            generation = ++attemptGeneration
            previousJob = inFlightJob
            inFlightGeneration = generation
            inFlightMacAddress = cachedActive?.macAddress

            newJob = connectorScope.launch(start = CoroutineStart.LAZY) {
                executeAutoConnect(generation)
            }
            inFlightJob = newJob
        }

        previousJob?.cancel()
        newJob.start()
        return newJob
    }

    /**
     * Cancels any in-flight auto-connect attempt cleanly. Transitions [AutoConnectState.Connecting]
     * back to [AutoConnectState.Idle] while leaving [AutoConnectState.DormantPrompt] and
     * [AutoConnectState.Connected] intact.
     */
    fun cancelAutoConnect() {
        val jobToCancel = synchronized(stateLock) {
            val job = cancelInFlightLocked()
            if (_state.value is AutoConnectState.Connecting) {
                _state.value = AutoConnectState.Idle
            }
            job
        }
        jobToCancel?.cancel()
    }

    /**
     * Notifies the connector that a Heart Rate Monitor (HRM) connected via an external flow
     * (such as the BLE scanner bottom sheet), cancelling any in-flight auto-connect attempt and
     * clearing any dormant prompt into [AutoConnectState.Connected].
     */
    fun onExternalHrmConnected(macAddress: String, hrmDisplayName: String) {
        val jobToCancel = synchronized(stateLock) {
            val job = cancelInFlightLocked()
            _state.value = AutoConnectState.Connected(
                macAddress = macAddress,
                hrmDisplayName = hrmDisplayName
            )
            job
        }
        jobToCancel?.cancel()
    }

    /**
     * Cancels internal observers and any in-flight auto-connect attempt.
     */
    fun close() {
        val jobToCancel = synchronized(stateLock) {
            cancelInFlightLocked()
        }
        jobToCancel?.cancel()
        connectorJob.cancel()
    }

    private suspend fun executeAutoConnect(generation: Long) {
        var targetMac: String? = null
        try {
            if (isBlockedByActiveSessionOrReconnection()) {
                clearInFlightIfCurrent(generation)
                return
            }

            val activeHrm = rememberedHrmDao.getActiveHrm()
            if (activeHrm == null) {
                synchronized(stateLock) {
                    if (isCurrentAttemptLocked(generation)) {
                        currentActiveHrm = null
                        clearInFlightLocked()
                        _state.value = AutoConnectState.Idle
                    }
                }
                return
            }

            val macAddress = activeHrm.macAddress
            val displayName = resolveHrmDisplayName(activeHrm)
            targetMac = macAddress

            val shortCircuitConnected = synchronized(stateLock) {
                if (!isCurrentAttemptLocked(generation)) return
                currentActiveHrm = activeHrm
                inFlightMacAddress = macAddress

                if (isBlockedByActiveSessionOrReconnection()) {
                    clearInFlightLocked()
                    return
                }

                val liveState = bleTransport.connectionState.value
                if (liveState is BleConnectionState.Connected && liveState.macAddress == macAddress) {
                    clearInFlightLocked()
                    _state.value = AutoConnectState.Connected(
                        macAddress = macAddress,
                        hrmDisplayName = displayName
                    )
                    true
                } else {
                    _state.value = AutoConnectState.Connecting(
                        macAddress = macAddress,
                        hrmDisplayName = displayName
                    )
                    false
                }
            }

            if (shortCircuitConnected) {
                return
            }

            if (permissionChecker != null && !permissionChecker.checkPermissions().hasConnectPermission) {
                transitionToDormantIfCurrent(generation, macAddress, displayName)
                return
            }

            val initOutcome = withTimeoutOrNull(autoConnectTimeoutMs) {
                val connectResult = try {
                    bleTransport.connect(macAddress = macAddress, autoConnect = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    BleOperationResult.Failure(
                        status = BleOperationResult.STATUS_OPERATION_FAILED,
                        message = t.message ?: "Unexpected Bluetooth error"
                    )
                }
                if (connectResult is BleOperationResult.Failure) {
                    return@withTimeoutOrNull connectResult
                }
                try {
                    hrmGattClient.initializeConnectedHrm(macAddress)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    BleOperationResult.Failure(
                        status = BleOperationResult.STATUS_OPERATION_FAILED,
                        message = t.message ?: "Unexpected GATT initialization error"
                    )
                }
            }

            when (initOutcome) {
                is BleOperationResult.Success -> {
                    synchronized(stateLock) {
                        if (isCurrentAttemptLocked(generation)) {
                            clearInFlightLocked()
                            val latestName = currentActiveHrm
                                ?.takeIf { it.macAddress == macAddress }
                                ?.let(::resolveHrmDisplayName)
                                ?: displayName
                            _state.value = AutoConnectState.Connected(
                                macAddress = macAddress,
                                hrmDisplayName = latestName
                            )
                        }
                    }
                }

                null -> {
                    if (!isBlockedByActiveSessionOrReconnection() && isCurrentAttempt(generation)) {
                        hrmGattClient.disconnect(macAddress)
                    }
                    transitionToDormantIfCurrent(generation, macAddress, displayName)
                }

                is BleOperationResult.Failure -> {
                    val liveState = bleTransport.connectionState.value
                    if (!isBlockedByActiveSessionOrReconnection() &&
                        isCurrentAttempt(generation) &&
                        ((liveState is BleConnectionState.Connected && liveState.macAddress == macAddress) ||
                            (liveState is BleConnectionState.Connecting && liveState.macAddress == macAddress))
                    ) {
                        hrmGattClient.disconnect(macAddress)
                    }
                    transitionToDormantIfCurrent(generation, macAddress, displayName)
                }
            }
        } catch (e: CancellationException) {
            val macToCleanup = targetMac
            if (macToCleanup != null && !isBlockedByActiveSessionOrReconnection()) {
                val liveState = bleTransport.connectionState.value
                if (liveState is BleConnectionState.Connecting && liveState.macAddress == macToCleanup) {
                    withContext(NonCancellable) {
                        hrmGattClient.disconnect(macToCleanup)
                    }
                }
            }
            clearInFlightIfCurrent(generation)
            throw e
        } finally {
            clearInFlightIfCurrent(generation)
        }
    }

    private fun onActiveHrmChanged(activeHrm: RememberedHrmEntity?) {
        val jobToCancel: Job?
        synchronized(stateLock) {
            val previousMac = currentActiveHrm?.macAddress
            val newMac = activeHrm?.macAddress
            currentActiveHrm = activeHrm

            if (previousMac == newMac) {
                if (activeHrm != null) {
                    val updatedName = resolveHrmDisplayName(activeHrm)
                    _state.value = when (val current = _state.value) {
                        is AutoConnectState.Connecting ->
                            if (current.macAddress == activeHrm.macAddress) {
                                current.copy(hrmDisplayName = updatedName)
                            } else {
                                current
                            }

                        is AutoConnectState.Connected ->
                            if (current.macAddress == activeHrm.macAddress) {
                                current.copy(hrmDisplayName = updatedName)
                            } else {
                                current
                            }

                        is AutoConnectState.DormantPrompt ->
                            if (current.macAddress == activeHrm.macAddress) {
                                current.copy(hrmDisplayName = updatedName)
                            } else {
                                current
                            }

                        AutoConnectState.Idle -> AutoConnectState.Idle
                    }
                }
                return
            }

            jobToCancel = cancelInFlightLocked()

            _state.value = if (activeHrm == null) {
                AutoConnectState.Idle
            } else {
                val liveConnection = bleTransport.connectionState.value
                if (liveConnection is BleConnectionState.Connected &&
                    liveConnection.macAddress == activeHrm.macAddress
                ) {
                    AutoConnectState.Connected(
                        macAddress = activeHrm.macAddress,
                        hrmDisplayName = resolveHrmDisplayName(activeHrm)
                    )
                } else {
                    AutoConnectState.Idle
                }
            }
        }
        jobToCancel?.cancel()
    }

    private fun onConnectionStateChanged(newState: BleConnectionState) {
        val jobToCancel: Job?
        synchronized(stateLock) {
            when (newState) {
                is BleConnectionState.Reconnecting -> {
                    jobToCancel = cancelInFlightLocked()
                    _state.value = AutoConnectState.Idle
                }

                is BleConnectionState.Connected -> {
                    if (inFlightJob != null && inFlightMacAddress == newState.macAddress) {
                        return
                    }
                    val activeHrm = currentActiveHrm
                    val current = _state.value
                    val matchesActive = activeHrm != null && activeHrm.macAddress == newState.macAddress
                    val matchesPrompt = current is AutoConnectState.DormantPrompt &&
                        current.macAddress == newState.macAddress
                    if (matchesActive || matchesPrompt) {
                        jobToCancel = cancelInFlightLocked()
                        val displayName = activeHrm
                            ?.takeIf { it.macAddress == newState.macAddress }
                            ?.let(::resolveHrmDisplayName)
                            ?: (current as? AutoConnectState.DormantPrompt)?.hrmDisplayName
                            ?: newState.macAddress
                        _state.value = AutoConnectState.Connected(
                            macAddress = newState.macAddress,
                            hrmDisplayName = displayName
                        )
                    } else {
                        return
                    }
                }

                is BleConnectionState.Disconnected -> {
                    if (inFlightJob != null) {
                        return
                    }
                    if (_state.value is AutoConnectState.Connected) {
                        _state.value = AutoConnectState.Idle
                    }
                    return
                }

                is BleConnectionState.Connecting, is BleConnectionState.Scanning -> {
                    return
                }
            }
        }
        jobToCancel?.cancel()
    }

    private fun transitionToDormantIfCurrent(
        generation: Long,
        macAddress: String,
        fallbackDisplayName: String
    ) {
        synchronized(stateLock) {
            if (!isCurrentAttemptLocked(generation)) return
            clearInFlightLocked()
            if (isBlockedByActiveSessionOrReconnection()) {
                _state.value = AutoConnectState.Idle
                return
            }
            val latestName = currentActiveHrm
                ?.takeIf { it.macAddress == macAddress }
                ?.let(::resolveHrmDisplayName)
                ?: fallbackDisplayName
            _state.value = AutoConnectState.DormantPrompt(
                macAddress = macAddress,
                hrmDisplayName = latestName
            )
        }
    }

    private fun isBlockedByActiveSessionOrReconnection(): Boolean =
        isSessionActiveProvider() ||
            bleTransport.connectionState.value is BleConnectionState.Reconnecting ||
            sessionConnectionStateProvider() is BleConnectionState.Reconnecting

    private fun isCurrentAttempt(generation: Long): Boolean =
        synchronized(stateLock) { isCurrentAttemptLocked(generation) }

    private fun isCurrentAttemptLocked(generation: Long): Boolean =
        attemptGeneration == generation && inFlightGeneration == generation

    private fun clearInFlightIfCurrent(generation: Long) {
        synchronized(stateLock) {
            if (inFlightGeneration == generation) {
                clearInFlightLocked()
            }
        }
    }

    private fun clearInFlightLocked() {
        inFlightJob = null
        inFlightMacAddress = null
        inFlightGeneration = 0L
    }

    private fun cancelInFlightLocked(): Job? {
        attemptGeneration += 1
        val job = inFlightJob
        clearInFlightLocked()
        return job
    }

    companion object {
        const val DEFAULT_AUTO_CONNECT_TIMEOUT_MS = 10_000L
    }
}
