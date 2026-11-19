package com.example.heartzonemonitor.domain.timing

/**
 * Deterministic mutable [SessionClock] test double supporting independent advancement of monotonic
 * elapsed-realtime and wall-clock epoch timestamps, including positive and negative wall-clock jumps.
 */
class FakeSessionClock(
    initialRealtimeMs: Long = 10_000L,
    initialEpochMs: Long = 1_700_000_000_000L
) : SessionClock {

    private val lock = Any()
    private var realtimeMs: Long = initialRealtimeMs
    private var epochMs: Long = initialEpochMs

    override fun elapsedRealtimeMs(): Long = synchronized(lock) { realtimeMs }

    override fun currentEpochMs(): Long = synchronized(lock) { epochMs }

    fun advanceRealtimeBy(deltaMs: Long) {
        require(deltaMs >= 0L) {
            "Monotonic realtime clock cannot move backwards (deltaMs=$deltaMs)"
        }
        synchronized(lock) {
            realtimeMs += deltaMs
        }
    }

    fun advanceWallClockBy(deltaMs: Long) {
        synchronized(lock) {
            epochMs += deltaMs
        }
    }

    fun advanceBothBy(deltaMs: Long) {
        require(deltaMs >= 0L) {
            "Monotonic realtime clock cannot move backwards when advancing both clocks (deltaMs=$deltaMs)"
        }
        synchronized(lock) {
            realtimeMs += deltaMs
            epochMs += deltaMs
        }
    }
}
