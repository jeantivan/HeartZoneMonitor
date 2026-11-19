package com.example.heartzonemonitor.domain.timing

/**
 * Lifecycle states tracked by [MonotonicDurationEngine] across a single Session.
 */
enum class DurationTrackingState {
    IDLE,
    RECORDING,
    PAUSED,
    STOPPED
}

/**
 * Atomic point-in-time snapshot of a Session's monotonic durations and epoch boundaries.
 */
data class DurationSnapshot(
    val state: DurationTrackingState,
    val startEpochMs: Long?,
    val endEpochMs: Long?,
    val effectiveDurationMs: Long,
    val pausedDurationMs: Long,
    val elapsedDurationMs: Long
) {
    init {
        require(effectiveDurationMs >= 0L) {
            "effectiveDurationMs must be non-negative, was $effectiveDurationMs"
        }
        require(pausedDurationMs >= 0L) {
            "pausedDurationMs must be non-negative, was $pausedDurationMs"
        }
        require(elapsedDurationMs == effectiveDurationMs + pausedDurationMs) {
            "elapsedDurationMs ($elapsedDurationMs) must equal effectiveDurationMs ($effectiveDurationMs) + pausedDurationMs ($pausedDurationMs)"
        }
    }
}

/**
 * Thread-safe, pure Kotlin monotonic interval duration engine for a Session (ADR-0002).
 *
 * Accumulates `Effective Duration` and `Paused Duration` exclusively from [SessionClock.elapsedRealtimeMs]
 * so duration math remains immune to NTP adjustments, manual wall-clock edits, timezone/DST changes,
 * and coroutine delay drift. Wall-clock epoch timestamps are captured strictly for calendar metadata.
 */
class MonotonicDurationEngine(
    private val clock: SessionClock
) {
    private val lock = Any()

    private var state: DurationTrackingState = DurationTrackingState.IDLE
    private var startEpochMs: Long? = null
    private var endEpochMs: Long? = null
    private var activeIntervalStartRealtimeMs: Long? = null
    private var pauseIntervalStartRealtimeMs: Long? = null
    private var accumulatedEffectiveMs: Long = 0L
    private var accumulatedPausedMs: Long = 0L

    /**
     * Begins a Session from [DurationTrackingState.IDLE], capturing [startEpochMs] once and
     * opening the initial active recording interval.
     */
    fun start(): DurationSnapshot = synchronized(lock) {
        check(state == DurationTrackingState.IDLE) {
            "Cannot start duration tracking from state $state"
        }
        val nowEpoch = clock.currentEpochMs()
        val nowRealtime = clock.elapsedRealtimeMs()

        startEpochMs = nowEpoch
        endEpochMs = null
        activeIntervalStartRealtimeMs = nowRealtime
        pauseIntervalStartRealtimeMs = null
        accumulatedEffectiveMs = 0L
        accumulatedPausedMs = 0L
        state = DurationTrackingState.RECORDING

        snapshotLocked(nowRealtime)
    }

    /**
     * Closes the open recording interval into `Effective Duration` and begins a pause interval.
     */
    fun pause(): DurationSnapshot = synchronized(lock) {
        check(state == DurationTrackingState.RECORDING) {
            "Cannot pause duration tracking from state $state"
        }
        val nowRealtime = clock.elapsedRealtimeMs()
        closeActiveIntervalLocked(nowRealtime)
        pauseIntervalStartRealtimeMs = nowRealtime
        state = DurationTrackingState.PAUSED

        snapshotLocked(nowRealtime)
    }

    /**
     * Closes the open pause interval into `Paused Duration` and opens a new recording interval.
     */
    fun resume(): DurationSnapshot = synchronized(lock) {
        check(state == DurationTrackingState.PAUSED) {
            "Cannot resume duration tracking from state $state"
        }
        val nowRealtime = clock.elapsedRealtimeMs()
        closePauseIntervalLocked(nowRealtime)
        activeIntervalStartRealtimeMs = nowRealtime
        state = DurationTrackingState.RECORDING

        snapshotLocked(nowRealtime)
    }

    /**
     * Closes whichever interval is currently open, captures [endEpochMs], and freezes all
     * durations permanently in [DurationTrackingState.STOPPED].
     */
    fun stop(): DurationSnapshot = synchronized(lock) {
        check(state == DurationTrackingState.RECORDING || state == DurationTrackingState.PAUSED) {
            "Cannot stop duration tracking from state $state"
        }
        val nowRealtime = clock.elapsedRealtimeMs()
        when (state) {
            DurationTrackingState.RECORDING -> closeActiveIntervalLocked(nowRealtime)
            DurationTrackingState.PAUSED -> closePauseIntervalLocked(nowRealtime)
            DurationTrackingState.IDLE, DurationTrackingState.STOPPED -> error("Unreachable state: $state")
        }
        endEpochMs = clock.currentEpochMs()
        state = DurationTrackingState.STOPPED

        snapshotLocked(nowRealtime)
    }

    /**
     * Restores the engine to [DurationTrackingState.IDLE] with all counters and timestamps cleared.
     */
    fun reset() {
        synchronized(lock) {
            state = DurationTrackingState.IDLE
            startEpochMs = null
            endEpochMs = null
            activeIntervalStartRealtimeMs = null
            pauseIntervalStartRealtimeMs = null
            accumulatedEffectiveMs = 0L
            accumulatedPausedMs = 0L
        }
    }

    /**
     * Returns an atomic [DurationSnapshot] derived from a single monotonic clock reading.
     */
    fun snapshot(): DurationSnapshot = synchronized(lock) {
        snapshotLocked(clock.elapsedRealtimeMs())
    }

    /**
     * Returns instantaneous `Effective Duration` in milliseconds.
     */
    fun effectiveDurationMs(): Long = snapshot().effectiveDurationMs

    /**
     * Returns instantaneous `Paused Duration` in milliseconds.
     */
    fun pausedDurationMs(): Long = snapshot().pausedDurationMs

    /**
     * Returns instantaneous `Elapsed Duration` in milliseconds (`Effective Duration + Paused Duration`).
     */
    fun elapsedDurationMs(): Long = snapshot().elapsedDurationMs

    /**
     * Returns the Session start wall-clock timestamp in Unix epoch milliseconds, or `null` if idle.
     */
    fun startEpochMs(): Long? = synchronized(lock) { startEpochMs }

    /**
     * Returns the Session end wall-clock timestamp in Unix epoch milliseconds, or `null` if not stopped.
     */
    fun endEpochMs(): Long? = synchronized(lock) { endEpochMs }

    /**
     * Returns the current [DurationTrackingState].
     */
    fun currentState(): DurationTrackingState = synchronized(lock) { state }

    /**
     * Returns the monotonic start timestamp of the open recording interval, or `null` when not recording.
     */
    fun activeIntervalStartRealtimeMs(): Long? = synchronized(lock) { activeIntervalStartRealtimeMs }

    /**
     * Returns the monotonic start timestamp of the open pause interval, or `null` when not paused.
     */
    fun pauseIntervalStartRealtimeMs(): Long? = synchronized(lock) { pauseIntervalStartRealtimeMs }

    private fun closeActiveIntervalLocked(nowRealtimeMs: Long) {
        val intervalStart = checkNotNull(activeIntervalStartRealtimeMs) {
            "activeIntervalStartRealtimeMs missing while in state $state"
        }
        accumulatedEffectiveMs += elapsedSince(intervalStart, nowRealtimeMs)
        activeIntervalStartRealtimeMs = null
    }

    private fun closePauseIntervalLocked(nowRealtimeMs: Long) {
        val pauseStart = checkNotNull(pauseIntervalStartRealtimeMs) {
            "pauseIntervalStartRealtimeMs missing while in state $state"
        }
        accumulatedPausedMs += elapsedSince(pauseStart, nowRealtimeMs)
        pauseIntervalStartRealtimeMs = null
    }

    private fun snapshotLocked(nowRealtimeMs: Long): DurationSnapshot {
        val liveEffectiveMs = if (state == DurationTrackingState.RECORDING) {
            elapsedSince(activeIntervalStartRealtimeMs, nowRealtimeMs)
        } else {
            0L
        }
        val livePausedMs = if (state == DurationTrackingState.PAUSED) {
            elapsedSince(pauseIntervalStartRealtimeMs, nowRealtimeMs)
        } else {
            0L
        }
        val effectiveMs = accumulatedEffectiveMs + liveEffectiveMs
        val pausedMs = accumulatedPausedMs + livePausedMs

        return DurationSnapshot(
            state = state,
            startEpochMs = startEpochMs,
            endEpochMs = endEpochMs,
            effectiveDurationMs = effectiveMs,
            pausedDurationMs = pausedMs,
            elapsedDurationMs = effectiveMs + pausedMs
        )
    }

    private fun elapsedSince(startRealtimeMs: Long?, nowRealtimeMs: Long): Long {
        return if (startRealtimeMs == null) 0L else (nowRealtimeMs - startRealtimeMs).coerceAtLeast(0L)
    }
}
