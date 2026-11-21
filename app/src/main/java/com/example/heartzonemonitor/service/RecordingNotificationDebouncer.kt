package com.example.heartzonemonitor.service

import com.example.heartzonemonitor.data.session.PauseReason
import com.example.heartzonemonitor.data.session.SessionUiState
import com.example.heartzonemonitor.domain.model.HeartZone
import com.example.heartzonemonitor.domain.timing.SessionClock
import com.example.heartzonemonitor.data.timing.AndroidSessionClock
import kotlin.math.abs
import kotlin.reflect.KClass

/**
 * Pure monotonic debouncer gating `NotificationManager.notify()` updates for [RecordingService].
 *
 * Emits an update only when one of the following occurs relative to the last displayed notification:
 * 1. No notification has been displayed yet, or the Session identity / lifecycle state class /
 *    [PauseReason] / micro-session discard prompt flag changed.
 * 2. A [HeartZone] transition occurred (including `null <-> HeartZone`).
 * 3. Current BPM changed by at least [minBpmDelta] BPM (default 2 BPM) or transitioned between
 *    `null` and a concrete reading.
 * 4. At least [minUpdateIntervalMs] monotonic milliseconds (default 5,000 ms) have elapsed since
 *    the previous notification emission via [SessionClock.elapsedRealtimeMs].
 */
class RecordingNotificationDebouncer(
    private val clock: SessionClock = AndroidSessionClock,
    private val minBpmDelta: Int = DEFAULT_MIN_BPM_DELTA,
    private val minUpdateIntervalMs: Long = DEFAULT_MIN_UPDATE_INTERVAL_MS
) {
    init {
        require(minBpmDelta >= 1) { "minBpmDelta must be at least 1, was $minBpmDelta" }
        require(minUpdateIntervalMs >= 0L) {
            "minUpdateIntervalMs must be non-negative, was $minUpdateIntervalMs"
        }
    }

    private data class DisplayedSnapshot(
        val stateClass: KClass<out SessionUiState>,
        val sessionId: String?,
        val pauseReason: PauseReason?,
        val pendingMicroSessionDiscardPrompt: Boolean,
        val currentBpm: Int?,
        val currentZone: HeartZone?,
        val emittedAtRealtimeMs: Long
    )

    private var lastSnapshot: DisplayedSnapshot? = null

    /**
     * Returns `true` if [state] satisfies any emission trigger compared to the last recorded
     * notification snapshot without mutating debouncer state.
     */
    fun shouldEmit(state: SessionUiState): Boolean {
        val previous = lastSnapshot ?: return true
        val candidate = extractFields(state)

        if (previous.stateClass != candidate.stateClass ||
            previous.sessionId != candidate.sessionId ||
            previous.pauseReason != candidate.pauseReason ||
            previous.pendingMicroSessionDiscardPrompt != candidate.pendingMicroSessionDiscardPrompt
        ) {
            return true
        }

        if (previous.currentZone != candidate.currentZone) {
            return true
        }

        val prevBpm = previous.currentBpm
        val currBpm = candidate.currentBpm
        if ((prevBpm == null) != (currBpm == null)) {
            return true
        }
        if (prevBpm != null && currBpm != null && abs(currBpm - prevBpm) >= minBpmDelta) {
            return true
        }

        val elapsedSinceLastMs = clock.elapsedRealtimeMs() - previous.emittedAtRealtimeMs
        return elapsedSinceLastMs >= minUpdateIntervalMs
    }

    /**
     * Records [state] as the currently displayed notification snapshot at the current monotonic
     * timestamp from [SessionClock.elapsedRealtimeMs].
     */
    fun recordEmitted(state: SessionUiState) {
        lastSnapshot = extractFields(state).copy(emittedAtRealtimeMs = clock.elapsedRealtimeMs())
    }

    /**
     * Atomically evaluates [shouldEmit] and records [state] when an emission is allowed.
     */
    fun consumeIfShouldEmit(state: SessionUiState): Boolean {
        if (!shouldEmit(state)) return false
        recordEmitted(state)
        return true
    }

    /**
     * Clears the last recorded snapshot so the next state is treated as an initial emission.
     */
    fun reset() {
        lastSnapshot = null
    }

    private fun extractFields(state: SessionUiState): DisplayedSnapshot = when (state) {
        is SessionUiState.Recording -> DisplayedSnapshot(
            stateClass = SessionUiState.Recording::class,
            sessionId = state.sessionId,
            pauseReason = null,
            pendingMicroSessionDiscardPrompt = state.pendingMicroSessionDiscardPrompt,
            currentBpm = state.currentBpm,
            currentZone = state.currentZone,
            emittedAtRealtimeMs = 0L
        )

        is SessionUiState.Paused -> DisplayedSnapshot(
            stateClass = SessionUiState.Paused::class,
            sessionId = state.sessionId,
            pauseReason = state.reason,
            pendingMicroSessionDiscardPrompt = state.pendingMicroSessionDiscardPrompt,
            currentBpm = state.currentBpm,
            currentZone = state.currentZone,
            emittedAtRealtimeMs = 0L
        )

        is SessionUiState.Idle -> DisplayedSnapshot(
            stateClass = SessionUiState.Idle::class,
            sessionId = null,
            pauseReason = null,
            pendingMicroSessionDiscardPrompt = false,
            currentBpm = state.currentBpm,
            currentZone = state.currentZone,
            emittedAtRealtimeMs = 0L
        )

        is SessionUiState.Completed -> DisplayedSnapshot(
            stateClass = SessionUiState.Completed::class,
            sessionId = state.session.id,
            pauseReason = null,
            pendingMicroSessionDiscardPrompt = false,
            currentBpm = null,
            currentZone = null,
            emittedAtRealtimeMs = 0L
        )

        is SessionUiState.Discarded -> DisplayedSnapshot(
            stateClass = SessionUiState.Discarded::class,
            sessionId = state.sessionId,
            pauseReason = null,
            pendingMicroSessionDiscardPrompt = false,
            currentBpm = null,
            currentZone = null,
            emittedAtRealtimeMs = 0L
        )
    }

    companion object {
        const val DEFAULT_MIN_BPM_DELTA = 2
        const val DEFAULT_MIN_UPDATE_INTERVAL_MS = 5_000L
    }
}
