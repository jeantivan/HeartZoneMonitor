package com.example.heartzonemonitor.data.session

import com.example.heartzonemonitor.data.ble.BleConnectionState
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.SessionEntity
import com.example.heartzonemonitor.domain.model.HeartZone
import com.example.heartzonemonitor.domain.model.ZoneCalculationResult

/**
 * Reason why an active Session transitioned into [SessionUiState.Paused].
 */
enum class PauseReason {
    USER_MANUAL,
    BLE_CONNECTION_LOST,
    SENSOR_CONTACT_LOST
}

/**
 * Single source of truth UI and lifecycle state exposed by [SessionRepository].
 */
sealed interface SessionUiState {

    /**
     * No Session is currently open. Tracks Active HRM connection and live Heart Rate Monitor (HRM)
     * telemetry readiness to gate starting a new Session.
     */
    data class Idle(
        val activeHrm: RememberedHrmEntity? = null,
        val connectionState: BleConnectionState = BleConnectionState.Disconnected,
        val currentBpm: Int? = null,
        val currentZone: HeartZone? = null,
        val sensorContact: Boolean? = null,
        val batteryLevel: Int? = null,
        val isTelemetryValid: Boolean = false,
        val canStartSession: Boolean = false
    ) : SessionUiState

    /**
     * A Session is actively recording 1 Hz Heart Rate Samples and accumulating Effective Duration.
     */
    data class Recording(
        val sessionId: String,
        val activeHrm: RememberedHrmEntity,
        val startEpochMs: Long,
        val effectiveDurationMs: Long,
        val pausedDurationMs: Long,
        val elapsedDurationMs: Long,
        val currentBpm: Int?,
        val currentZone: HeartZone?,
        val sensorContact: Boolean?,
        val batteryLevel: Int?,
        val sampleCount: Int,
        val zoneCalculation: ZoneCalculationResult,
        val pendingMicroSessionDiscardPrompt: Boolean = false,
        val connectionState: BleConnectionState = BleConnectionState.Connected(activeHrm.macAddress)
    ) : SessionUiState

    /**
     * A Session is paused (manually or automatically), accumulating Paused Duration without
     * persisting incoming Heart Rate Samples.
     */
    data class Paused(
        val sessionId: String,
        val activeHrm: RememberedHrmEntity,
        val reason: PauseReason,
        val startEpochMs: Long,
        val effectiveDurationMs: Long,
        val pausedDurationMs: Long,
        val elapsedDurationMs: Long,
        val currentBpm: Int?,
        val currentZone: HeartZone?,
        val sensorContact: Boolean?,
        val batteryLevel: Int?,
        val sampleCount: Int,
        val zoneCalculation: ZoneCalculationResult,
        val pendingMicroSessionDiscardPrompt: Boolean = false,
        val connectionState: BleConnectionState = BleConnectionState.Connected(activeHrm.macAddress)
    ) : SessionUiState

    /**
     * Terminal state emitted when a Session has been finalized with computed summary metrics.
     */
    data class Completed(
        val session: SessionEntity
    ) : SessionUiState

    /**
     * Terminal state emitted when an active Session has been discarded and removed from storage.
     */
    data class Discarded(
        val sessionId: String
    ) : SessionUiState
}

/**
 * Precondition failure reasons returned when [SessionRepository.startSession] rejects a start request.
 */
enum class StartSessionRejectionReason {
    SESSION_ALREADY_ACTIVE,
    USER_PROFILE_MISSING,
    INVALID_USER_PROFILE,
    NO_ACTIVE_HRM,
    HRM_NOT_CONNECTED,
    TELEMETRY_UNAVAILABLE
}

/**
 * Outcome returned by [SessionRepository.startSession].
 */
sealed interface StartSessionResult {
    data class Started(val sessionId: String) : StartSessionResult
    data class Rejected(val reason: StartSessionRejectionReason) : StartSessionResult
}

/**
 * Outcome returned by [SessionRepository.stopSession] and [SessionRepository.confirmSaveMicroSession].
 */
sealed interface StopSessionOutcome {
    data class Completed(val session: SessionEntity) : StopSessionOutcome
    data class MicroSessionDiscardPromptRequired(
        val sessionId: String,
        val effectiveDurationMs: Long,
        val sampleCount: Int
    ) : StopSessionOutcome
    data object Ignored : StopSessionOutcome
}
