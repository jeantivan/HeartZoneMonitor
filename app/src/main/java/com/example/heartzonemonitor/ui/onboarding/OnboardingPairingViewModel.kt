package com.example.heartzonemonitor.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.heartzonemonitor.data.ble.BleDeviceDiscoveryService
import com.example.heartzonemonitor.data.ble.BleDiscoveryState
import com.example.heartzonemonitor.data.ble.BleDiscoveryUnavailableReason
import com.example.heartzonemonitor.data.ble.BleOperationResult
import com.example.heartzonemonitor.data.ble.BlePermissionChecker
import com.example.heartzonemonitor.data.ble.BlePermissionStatus
import com.example.heartzonemonitor.data.ble.DiscoveredHrm
import com.example.heartzonemonitor.data.local.dao.RememberedHrmDao
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnboardingPairingUiState(
    val permissionStatus: BlePermissionStatus = BlePermissionStatus(
        hasScanPermission = false,
        hasConnectPermission = false,
        hasNotificationPermission = false
    ),
    val discoveryState: BleDiscoveryState = BleDiscoveryState.Idle,
    val discoveredHrms: List<DiscoveredHrm> = emptyList(),
    val pairingMacAddress: String? = null,
    val errorMessage: String? = null,
    val pairingCompleted: Boolean = false
) {
    val isBlePermissionGranted: Boolean
        get() = permissionStatus.allBlePermissionsGranted

    val isScanning: Boolean
        get() = discoveryState is BleDiscoveryState.Scanning

    val isPairing: Boolean
        get() = pairingMacAddress != null
}

/**
 * Coordinates Onboarding Step 2 Heart Rate Monitor (HRM) discovery, GATT pairing, Active HRM
 * persistence in `remembered_hrms`, and clean "Pair Later" bypass handling.
 */
class OnboardingPairingViewModel(
    private val discoveryService: BleDeviceDiscoveryService,
    private val rememberedHrmDao: RememberedHrmDao,
    val permissionChecker: BlePermissionChecker,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) : ViewModel() {

    private val _uiState: MutableStateFlow<OnboardingPairingUiState>
    val uiState: StateFlow<OnboardingPairingUiState>

    private var pairingJob: Job? = null

    init {
        val initialPermissions = permissionChecker.checkPermissions()
        if (initialPermissions.allBlePermissionsGranted) {
            discoveryService.startScan()
        }

        _uiState = MutableStateFlow(
            OnboardingPairingUiState(
                permissionStatus = initialPermissions,
                discoveryState = discoveryService.state.value,
                discoveredHrms = discoveryService.discoveredHrms.value
            )
        )
        uiState = _uiState.asStateFlow()

        viewModelScope.launch {
            combine(
                discoveryService.state,
                discoveryService.discoveredHrms
            ) { state, hrms ->
                state to hrms
            }.collect { (state, hrms) ->
                _uiState.update { current ->
                    current.copy(
                        discoveryState = state,
                        discoveredHrms = hrms
                    )
                }
            }
        }
    }

    fun onPermissionsUpdated(status: BlePermissionStatus) {
        val wasGranted = _uiState.value.permissionStatus.allBlePermissionsGranted
        _uiState.update { current ->
            current.copy(
                permissionStatus = status,
                errorMessage = if (status.allBlePermissionsGranted) null else current.errorMessage
            )
        }

        if (status.allBlePermissionsGranted) {
            val current = _uiState.value
            val currentDiscovery = discoveryService.state.value
            val shouldAutoStart = !wasGranted ||
                currentDiscovery is BleDiscoveryState.Idle ||
                currentDiscovery == BleDiscoveryState.Unavailable(
                    BleDiscoveryUnavailableReason.PERMISSION_DENIED
                )

            if (shouldAutoStart &&
                currentDiscovery !is BleDiscoveryState.Scanning &&
                !current.pairingCompleted &&
                current.pairingMacAddress == null
            ) {
                discoveryService.startScan()
                syncDiscoverySnapshot()
            }
        } else if (discoveryService.state.value is BleDiscoveryState.Scanning) {
            stopScanSafely()
        }
    }

    fun startOrRestartScan() {
        val current = _uiState.value
        if (current.pairingMacAddress != null || current.pairingCompleted) {
            return
        }

        val refreshedPermissions = permissionChecker.checkPermissions()
        _uiState.update {
            it.copy(
                permissionStatus = refreshedPermissions,
                errorMessage = null
            )
        }

        if (!refreshedPermissions.allBlePermissionsGranted) {
            return
        }

        discoveryService.startScan()
        syncDiscoverySnapshot()
    }

    fun pairDevice(device: DiscoveredHrm) {
        while (true) {
            val current = _uiState.value
            if (current.pairingMacAddress != null || current.pairingCompleted) {
                return
            }
            val next = current.copy(
                pairingMacAddress = device.macAddress,
                errorMessage = null
            )
            if (_uiState.compareAndSet(current, next)) {
                break
            }
        }

        val resolvedName = device.advertisedName?.takeIf { it.isNotBlank() } ?: DEFAULT_HRM_NAME

        pairingJob = viewModelScope.launch {
            val connectResult = try {
                discoveryService.connectToHrm(device.macAddress)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                BleOperationResult.Failure(
                    status = BleOperationResult.STATUS_OPERATION_FAILED,
                    message = t.message ?: "Unexpected Bluetooth error"
                )
            }

            when (connectResult) {
                is BleOperationResult.Success -> {
                    val entity = RememberedHrmEntity(
                        macAddress = device.macAddress,
                        advertisedName = resolvedName,
                        customNickname = null,
                        lastSeenTimestamp = nowEpochMs(),
                        batteryLevel = null,
                        isActive = true
                    )
                    try {
                        rememberedHrmDao.upsertHrm(entity)
                        _uiState.update {
                            it.copy(
                                pairingMacAddress = null,
                                errorMessage = null,
                                pairingCompleted = true
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        _uiState.update {
                            it.copy(
                                pairingMacAddress = null,
                                errorMessage = t.message?.takeIf { msg -> msg.isNotBlank() }
                                    ?: "Failed to save Heart Rate Monitor.",
                                pairingCompleted = false
                            )
                        }
                    }
                }

                is BleOperationResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            pairingMacAddress = null,
                            errorMessage = formatPairingFailure(connectResult, resolvedName),
                            pairingCompleted = false
                        )
                    }
                }
            }
        }
    }

    fun onPairLater() {
        pairingJob?.cancel()
        pairingJob = null
        _uiState.update { it.copy(pairingMacAddress = null, errorMessage = null) }
        stopScanSafely()
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        pairingJob?.cancel()
        pairingJob = null
        stopScanSafely()
        super.onCleared()
    }

    private fun syncDiscoverySnapshot() {
        _uiState.update { current ->
            current.copy(
                discoveryState = discoveryService.state.value,
                discoveredHrms = discoveryService.discoveredHrms.value
            )
        }
    }

    private fun stopScanSafely() {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            discoveryService.stopScan()
        }
    }

    private fun formatPairingFailure(
        failure: BleOperationResult.Failure,
        deviceName: String
    ): String = when (failure.status) {
        BleOperationResult.STATUS_PERMISSION_DENIED ->
            "Bluetooth connect permission is required to pair with $deviceName."

        BleOperationResult.STATUS_BLUETOOTH_DISABLED ->
            "Bluetooth is turned off. Enable Bluetooth and try pairing again."

        BleOperationResult.STATUS_TIMEOUT ->
            "Connection to $deviceName timed out. Moisten strap contacts, wear the strap, and try again."

        else -> {
            val detail = failure.message.takeIf { it.isNotBlank() }
            if (detail != null) {
                "Could not connect to $deviceName: $detail"
            } else {
                "Could not connect to $deviceName (status ${failure.status}). Please try again."
            }
        }
    }

    companion object {
        const val DEFAULT_HRM_NAME = "Heart Rate Monitor"
    }
}
