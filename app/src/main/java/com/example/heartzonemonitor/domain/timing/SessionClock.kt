package com.example.heartzonemonitor.domain.timing

/**
 * Pure clock abstraction separating monotonic elapsed-realtime intervals from wall-clock epoch
 * timestamps for drift-free Session duration tracking (ADR-0002).
 */
interface SessionClock {
    /**
     * Returns monotonic milliseconds elapsed since device boot, unaffected by wall-clock shifts.
     */
    fun elapsedRealtimeMs(): Long

    /**
     * Returns current wall-clock time in Unix epoch milliseconds for Session calendar metadata.
     */
    fun currentEpochMs(): Long
}
