package com.example.heartzonemonitor.ui.session

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.session.PauseReason
import com.example.heartzonemonitor.data.session.SessionRepository
import com.example.heartzonemonitor.data.session.SessionUiState
import com.example.heartzonemonitor.domain.model.HeartZone
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    val isImmersionMode: Boolean = isRecording || isPaused
)

/**
 * Bundled user actions for the Live Session screen following CODING_STANDARDS.md Section 5.
 */
data class LiveSessionActions(
    val onStartSession: () -> Unit = {},
    val onPauseSession: () -> Unit = {},
    val onResumeSession: () -> Unit = {},
    val onStopSession: () -> Unit = {},
    val onManageDevices: () -> Unit = {}
)

/**
 * Maps an optional [HeartZone] to its saturated Athletic Monolith background color, falling back
 * to [LiveSessionVoidBlack] when no zone is active.
 */
fun HeartZone?.toLiveSessionBackgroundColor(): Color =
    if (this == null) LiveSessionVoidBlack else Color(colorArgb)

/**
 * Bridges [SessionRepository.uiState] into [LiveSessionUiState] and dispatches Session controls.
 */
class LiveSessionViewModel(
    private val sessionRepository: SessionRepository
) : ViewModel() {

    val uiState: StateFlow<LiveSessionUiState> =
        sessionRepository.uiState
            .map(SessionUiState::toLiveSessionUiState)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = sessionRepository.uiState.value.toLiveSessionUiState()
            )

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
        isImmersionMode = false
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
        isImmersionMode = true
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
        isImmersionMode = true
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
        isImmersionMode = false
    )
}
