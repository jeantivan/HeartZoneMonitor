package com.example.heartzonemonitor.ui.session

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.heartzonemonitor.data.ble.ActiveHrmAutoConnector
import com.example.heartzonemonitor.data.ble.AutoConnectState
import com.example.heartzonemonitor.data.ble.BleDeviceDiscoveryService
import com.example.heartzonemonitor.data.ble.BleDiscoveryState
import com.example.heartzonemonitor.data.ble.BleOperationResult
import com.example.heartzonemonitor.data.ble.BlePermissionChecker
import com.example.heartzonemonitor.data.ble.BlePermissionStatus
import com.example.heartzonemonitor.data.ble.DiscoveredHrm
import com.example.heartzonemonitor.data.ble.HrmGattClient
import com.example.heartzonemonitor.data.ble.resolveHrmDisplayName
import com.example.heartzonemonitor.data.local.dao.RememberedHrmDao
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.session.PauseReason
import com.example.heartzonemonitor.data.session.SessionRepository
import com.example.heartzonemonitor.data.session.SessionUiState
import com.example.heartzonemonitor.domain.model.HeartZone
import com.example.heartzonemonitor.ui.onboarding.OnboardingPairingViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val LIVE_SESSION_BG_ANIMATION_DURATION_MS = 500
const val LIVE_SESSION_BG_DEPTH_ALPHA = 0.82f
val LiveSessionVoidBlack = Color(0xFF0B0F19)

/**
 * UI state rendered by the Athletic Monolith Live Session screen.
 */
data class LiveSessionUiState(
    val activeHrm: RememberedHrmEntity? = null,
    val currentBpm: Int? = null,
    val currentZone: HeartZone? = null,
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val pauseReason: PauseReason? = null,
    val effectiveDurationMs: Long = 0L,
    val batteryLevel: Int? = null,
    val canStartSession: Boolean = false,
    val isImmersionMode: Boolean = isRecording || isPaused,
    val pendingMicroSessionDiscardPrompt: Boolean = false,
    val autoConnectState: AutoConnectState = AutoConnectState.Idle,
    val scannerSheet: BleScannerSheetUiState = BleScannerSheetUiState()
)

/**
 * Bundled user actions for the Live Session screen following CODING_STANDARDS.md Section 5.
 */
data class LiveSessionActions(
    val onStartSession: () -> Unit = {},
    val onPauseSession: () -> Unit = {},
    val onResumeSession: () -> Unit = {},
    val onStopSession: () -> Unit = {},
    val onManageDevices: () -> Unit = {},
    val onConfirmSaveMicroSession: () -> Unit = {},
    val onDiscardSession: () -> Unit = {},
    val onDismissMicroSessionPrompt: () -> Unit = {},
    val onOpenScanner: () -> Unit = {},
    val scannerActions: BleScannerSheetActions = BleScannerSheetActions()
)

/**
 * Maps an optional [HeartZone] to its saturated Athletic Monolith background color, falling back
 * to [LiveSessionVoidBlack] when no zone is active.
 */
fun HeartZone?.toLiveSessionBackgroundColor(): Color =
    if (this == null) LiveSessionVoidBlack else Color(colorArgb)

/**
 * Bridges [SessionRepository.uiState], [ActiveHrmAutoConnector.state], and the BLE scanner bottom
 * sheet into [LiveSessionUiState] and dispatches Session and BLE controls.
 */
class LiveSessionViewModel(
    private val sessionRepository: SessionRepository,
    private val autoConnector: ActiveHrmAutoConnector? = null,
    private val discoveryService: BleDeviceDiscoveryService? = null,
    private val rememberedHrmDao: RememberedHrmDao? = null,
    val permissionChecker: BlePermissionChecker? = null,
    private val hrmGattClient: HrmGattClient? = null,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) : ViewModel() {

    private var scannerConnectJob: Job? = null

    private val _scannerSheetState = MutableStateFlow(
        BleScannerSheetUiState(
            permissionStatus = permissionChecker?.checkPermissions() ?: DEFAULT_GRANTED_PERMISSIONS,
            discoveryState = discoveryService?.state?.value ?: BleDiscoveryState.Idle,
            discoveredHrms = discoveryService?.discoveredHrms?.value ?: emptyList()
        )
    )

    private val autoConnectStateFlow: StateFlow<AutoConnectState> =
        autoConnector?.state ?: MutableStateFlow(AutoConnectState.Idle)

    val uiState: StateFlow<LiveSessionUiState> =
        combine(
            sessionRepository.uiState,
            autoConnectStateFlow,
            _scannerSheetState
        ) { sessionState, autoConnectState, scannerSheet ->
            sessionState.toLiveSessionUiState().copy(
                autoConnectState = autoConnectState,
                scannerSheet = scannerSheet
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = sessionRepository.uiState.value.toLiveSessionUiState().copy(
                autoConnectState = autoConnectStateFlow.value,
                scannerSheet = _scannerSheetState.value
            )
        )

    init {
        if (discoveryService != null) {
            viewModelScope.launch {
                combine(
                    discoveryService.state,
                    discoveryService.discoveredHrms
                ) { state, hrms -> state to hrms }
                    .collect { (state, hrms) ->
                        _scannerSheetState.update { current ->
                            current.copy(
                                discoveryState = state,
                                discoveredHrms = hrms
                            )
                        }
                    }
            }
        }
    }

    /**
     * Triggers an immediate non-blocking direct connection (`autoConnect = false`) to the
     * designated Active HRM upon entering the Live Session screen.
     */
    fun onEnterLiveSession() {
        autoConnector?.startAutoConnect()
    }

    /**
     * Cleans up any in-flight background auto-connect attempt or open scanner scan when leaving
     * the Live Session screen, without disrupting an established connection or active Session.
     */
    fun onLeaveLiveSession() {
        autoConnector?.cancelAutoConnect()
        if (_scannerSheetState.value.isOpen) {
            dismissScannerSheet()
        }
    }

    fun startSession() {
        viewModelScope.launch {
            sessionRepository.startSession()
        }
    }

    fun pauseSession() {
        viewModelScope.launch {
            sessionRepository.pauseSession(PauseReason.USER_MANUAL)
        }
    }

    fun resumeSession() {
        viewModelScope.launch {
            sessionRepository.resumeSession()
        }
    }

    fun stopSession() {
        viewModelScope.launch {
            sessionRepository.stopSession()
        }
    }

    fun confirmSaveMicroSession() {
        viewModelScope.launch {
            sessionRepository.confirmSaveMicroSession()
        }
    }

    fun discardSession() {
        viewModelScope.launch {
            sessionRepository.discardSession()
        }
    }

    fun dismissMicroSessionPrompt() {
        sessionRepository.dismissMicroSessionPrompt()
    }

    fun refreshDurations() {
        sessionRepository.refreshDurations()
    }

    /**
     * Opens the BLE scanner bottom sheet, cancels any in-flight auto-connect attempt, and starts
     * a `0x180D` scan when Bluetooth permissions are granted.
     */
    fun openScannerSheet() {
        if (sessionRepository.isSessionActive) return

        autoConnector?.cancelAutoConnect()
        val permissions = permissionChecker?.checkPermissions() ?: DEFAULT_GRANTED_PERMISSIONS
        if (permissions.allBlePermissionsGranted) {
            discoveryService?.startScan()
        }
        _scannerSheetState.update { current ->
            current.copy(
                isOpen = true,
                permissionStatus = permissions,
                discoveryState = discoveryService?.state?.value ?: BleDiscoveryState.Idle,
                discoveredHrms = discoveryService?.discoveredHrms?.value ?: emptyList(),
                connectingMacAddress = null,
                errorMessage = null
            )
        }
    }

    /**
     * Dismisses the BLE scanner bottom sheet and stops any active Bluetooth LE scan cleanly.
     */
    fun dismissScannerSheet() {
        scannerConnectJob?.cancel()
        scannerConnectJob = null
        _scannerSheetState.update { current ->
            current.copy(
                isOpen = false,
                connectingMacAddress = null,
                errorMessage = null
            )
        }
        if (discoveryService != null) {
            viewModelScope.launch {
                discoveryService.stopScan()
            }
        }
    }

    fun onScannerPermissionsUpdated(status: BlePermissionStatus) {
        _scannerSheetState.update { current ->
            current.copy(
                permissionStatus = status,
                errorMessage = if (status.allBlePermissionsGranted) null else current.errorMessage
            )
        }

        val current = _scannerSheetState.value
        if (!current.isOpen) return

        if (status.allBlePermissionsGranted) {
            if (discoveryService != null &&
                discoveryService.state.value !is BleDiscoveryState.Scanning &&
                current.connectingMacAddress == null
            ) {
                discoveryService.startScan()
                syncScannerDiscoverySnapshot()
            }
        } else if (discoveryService?.state?.value is BleDiscoveryState.Scanning) {
            viewModelScope.launch {
                discoveryService.stopScan()
            }
        }
    }

    fun rescanInScannerSheet() {
        if (_scannerSheetState.value.connectingMacAddress != null) return

        val permissions = permissionChecker?.checkPermissions() ?: DEFAULT_GRANTED_PERMISSIONS
        _scannerSheetState.update { current ->
            current.copy(
                permissionStatus = permissions,
                errorMessage = null
            )
        }

        if (!permissions.allBlePermissionsGranted) return

        discoveryService?.startScan()
        syncScannerDiscoverySnapshot()
    }

    /**
     * Stops active scanning, connects directly to [device], initializes GATT (`0x180D` / `0x2A37`
     * / `0x2902` / `0x2A19`), persists/promotes the Heart Rate Monitor (HRM) as Active HRM, and
     * dismisses the scanner bottom sheet.
     */
    fun connectFromScanner(device: DiscoveredHrm) {
        val activeDiscovery = discoveryService ?: return
        if (sessionRepository.isSessionActive) return

        while (true) {
            val current = _scannerSheetState.value
            if (current.connectingMacAddress != null) return
            val next = current.copy(
                connectingMacAddress = device.macAddress,
                errorMessage = null
            )
            if (_scannerSheetState.compareAndSet(current, next)) break
        }

        autoConnector?.cancelAutoConnect()
        val resolvedName = device.advertisedName?.takeIf { it.isNotBlank() }
            ?: OnboardingPairingViewModel.DEFAULT_HRM_NAME
        val resolvedGattClient = hrmGattClient ?: sessionRepository.hrmGattClient

        scannerConnectJob = viewModelScope.launch {
            val connectResult = try {
                activeDiscovery.connectToHrm(device.macAddress, autoConnect = false)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                BleOperationResult.Failure(
                    status = BleOperationResult.STATUS_OPERATION_FAILED,
                    message = t.message ?: "Unexpected Bluetooth error"
                )
            }

            if (connectResult is BleOperationResult.Failure) {
                _scannerSheetState.update {
                    it.copy(
                        connectingMacAddress = null,
                        errorMessage = formatScannerConnectFailure(connectResult, resolvedName)
                    )
                }
                return@launch
            }

            val initResult = try {
                resolvedGattClient.initializeConnectedHrm(device.macAddress)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                BleOperationResult.Failure(
                    status = BleOperationResult.STATUS_OPERATION_FAILED,
                    message = t.message ?: "Unexpected GATT initialization error"
                )
            }

            if (initResult is BleOperationResult.Failure) {
                resolvedGattClient.disconnect(device.macAddress)
                _scannerSheetState.update {
                    it.copy(
                        connectingMacAddress = null,
                        errorMessage = formatScannerConnectFailure(initResult, resolvedName)
                    )
                }
                return@launch
            }

            val existing = rememberedHrmDao?.getHrmByMacAddress(device.macAddress)
            val initialBattery = (initResult as BleOperationResult.Success).value.initialBatteryLevel
            val entity = RememberedHrmEntity(
                macAddress = device.macAddress,
                advertisedName = resolvedName,
                customNickname = existing?.customNickname,
                lastSeenTimestamp = nowEpochMs(),
                batteryLevel = initialBattery ?: existing?.batteryLevel,
                isActive = true
            )

            try {
                rememberedHrmDao?.upsertHrm(entity)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _scannerSheetState.update {
                    it.copy(
                        connectingMacAddress = null,
                        errorMessage = t.message?.takeIf { msg -> msg.isNotBlank() }
                            ?: "Failed to save Heart Rate Monitor."
                    )
                }
                return@launch
            }

            autoConnector?.onExternalHrmConnected(
                macAddress = device.macAddress,
                hrmDisplayName = resolveHrmDisplayName(entity)
            )
            _scannerSheetState.update {
                it.copy(
                    isOpen = false,
                    connectingMacAddress = null,
                    errorMessage = null
                )
            }
        }
    }

    fun dismissScannerError() {
        _scannerSheetState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        scannerConnectJob?.cancel()
        scannerConnectJob = null
        autoConnector?.cancelAutoConnect()
        if (discoveryService != null) {
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                discoveryService.stopScan()
            }
        }
        super.onCleared()
    }

    private fun syncScannerDiscoverySnapshot() {
        val activeDiscovery = discoveryService ?: return
        _scannerSheetState.update { current ->
            current.copy(
                discoveryState = activeDiscovery.state.value,
                discoveredHrms = activeDiscovery.discoveredHrms.value
            )
        }
    }

    private fun formatScannerConnectFailure(
        failure: BleOperationResult.Failure,
        deviceName: String
    ): String = when (failure.status) {
        BleOperationResult.STATUS_PERMISSION_DENIED ->
            "Bluetooth connect permission is required to connect to $deviceName."

        BleOperationResult.STATUS_BLUETOOTH_DISABLED ->
            "Bluetooth is turned off. Enable Bluetooth and try connecting again."

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
        private val DEFAULT_GRANTED_PERMISSIONS = BlePermissionStatus(
            hasScanPermission = true,
            hasConnectPermission = true,
            hasNotificationPermission = false
        )
    }
}

internal fun SessionUiState.toLiveSessionUiState(): LiveSessionUiState = when (this) {
    is SessionUiState.Idle -> LiveSessionUiState(
        activeHrm = activeHrm,
        currentBpm = currentBpm,
        currentZone = currentZone,
        isRecording = false,
        isPaused = false,
        pauseReason = null,
        effectiveDurationMs = 0L,
        batteryLevel = batteryLevel,
        canStartSession = canStartSession,
        isImmersionMode = false,
        pendingMicroSessionDiscardPrompt = false
    )
    is SessionUiState.Recording -> LiveSessionUiState(
        activeHrm = activeHrm,
        currentBpm = currentBpm,
        currentZone = currentZone,
        isRecording = true,
        isPaused = false,
        pauseReason = null,
        effectiveDurationMs = effectiveDurationMs,
        batteryLevel = batteryLevel,
        canStartSession = false,
        isImmersionMode = true,
        pendingMicroSessionDiscardPrompt = pendingMicroSessionDiscardPrompt
    )
    is SessionUiState.Paused -> LiveSessionUiState(
        activeHrm = activeHrm,
        currentBpm = currentBpm,
        currentZone = currentZone,
        isRecording = false,
        isPaused = true,
        pauseReason = reason,
        effectiveDurationMs = effectiveDurationMs,
        batteryLevel = batteryLevel,
        canStartSession = false,
        isImmersionMode = true,
        pendingMicroSessionDiscardPrompt = pendingMicroSessionDiscardPrompt
    )
    is SessionUiState.Completed, is SessionUiState.Discarded -> LiveSessionUiState(
        activeHrm = null,
        currentBpm = null,
        currentZone = null,
        isRecording = false,
        isPaused = false,
        pauseReason = null,
        effectiveDurationMs = 0L,
        batteryLevel = null,
        canStartSession = false,
        isImmersionMode = false,
        pendingMicroSessionDiscardPrompt = false
    )
}
