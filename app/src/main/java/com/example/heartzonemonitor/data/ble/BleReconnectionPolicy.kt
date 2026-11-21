package com.example.heartzonemonitor.data.ble

/**
 * Policy configuring the Session disconnection grace period and exponential backoff delays for
 * Bluetooth LE reconnection attempts against an Active HRM.
 */
data class BleReconnectionPolicy(
    val gracePeriodMs: Long = DEFAULT_GRACE_PERIOD_MS,
    val initialBackoffDelaysMs: List<Long> = DEFAULT_INITIAL_BACKOFF_DELAYS_MS,
    val maxBackoffDelayMs: Long = DEFAULT_MAX_BACKOFF_DELAY_MS
) {
    init {
        require(gracePeriodMs >= 0L) {
            "gracePeriodMs must be non-negative, was $gracePeriodMs"
        }
        require(initialBackoffDelaysMs.isNotEmpty()) {
            "initialBackoffDelaysMs must not be empty"
        }
        require(initialBackoffDelaysMs.all { it >= 0L }) {
            "initialBackoffDelaysMs entries must be non-negative"
        }
        require(maxBackoffDelayMs > 0L) {
            "maxBackoffDelayMs must be positive, was $maxBackoffDelayMs"
        }
    }

    /**
     * Returns the delay in milliseconds before executing 1-indexed reconnection [attempt].
     *
     * With defaults: attempt 1 -> 0ms, 2 -> 2_000ms, 3 -> 5_000ms, 4 -> 10_000ms,
     * 5 -> 20_000ms, 6 -> 40_000ms, 7+ -> 60_000ms.
     */
    fun delayForAttemptMs(attempt: Int): Long {
        require(attempt >= 1) {
            "Reconnection attempt must be >= 1, was $attempt"
        }
        val index = attempt - 1
        if (index < initialBackoffDelaysMs.size) {
            return initialBackoffDelaysMs[index].coerceAtMost(maxBackoffDelayMs)
        }

        var delayMs = initialBackoffDelaysMs.last().coerceAtMost(maxBackoffDelayMs)
        val doublingSteps = index - (initialBackoffDelaysMs.size - 1)
        repeat(doublingSteps) {
            if (delayMs == 0L) return 0L
            if (delayMs >= maxBackoffDelayMs) return maxBackoffDelayMs
            delayMs = if (delayMs > maxBackoffDelayMs / 2L) {
                maxBackoffDelayMs
            } else {
                delayMs * 2L
            }
        }
        return delayMs
    }

    companion object {
        const val DEFAULT_GRACE_PERIOD_MS = 10_000L
        val DEFAULT_INITIAL_BACKOFF_DELAYS_MS: List<Long> = listOf(0L, 2_000L, 5_000L, 10_000L)
        const val DEFAULT_MAX_BACKOFF_DELAY_MS = 60_000L
        val Default = BleReconnectionPolicy()
    }
}
