package com.example.heartzonemonitor.data.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BleReconnectionPolicyTest {

    @Test
    fun defaultPolicy_exponentialBackoff() {
        val policy = BleReconnectionPolicy.Default

        assertEquals(10_000L, policy.gracePeriodMs)
        assertEquals(listOf(0L, 2_000L, 5_000L, 10_000L), policy.initialBackoffDelaysMs)
        assertEquals(60_000L, policy.maxBackoffDelayMs)

        val expectedSequence = listOf(
            0L,      // attempt 1
            2_000L,  // attempt 2
            5_000L,  // attempt 3
            10_000L, // attempt 4
            20_000L, // attempt 5
            40_000L, // attempt 6
            60_000L, // attempt 7 (capped)
            60_000L  // attempt 8 (stays capped)
        )

        val actualSequence = (1..8).map { attempt -> policy.delayForAttemptMs(attempt) }
        assertEquals(expectedSequence, actualSequence)
    }

    @Test
    fun delayForAttemptMs_rejectsNonPositiveAttempts() {
        val policy = BleReconnectionPolicy.Default

        assertThrows(IllegalArgumentException::class.java) {
            policy.delayForAttemptMs(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.delayForAttemptMs(-3)
        }
    }

    @Test
    fun delayForAttemptMs_customAndOverflowSafe() {
        val policy = BleReconnectionPolicy(
            gracePeriodMs = 5_000L,
            initialBackoffDelaysMs = listOf(1_000L, 3_000L),
            maxBackoffDelayMs = 15_000L
        )

        assertEquals(1_000L, policy.delayForAttemptMs(1))
        assertEquals(3_000L, policy.delayForAttemptMs(2))
        assertEquals(6_000L, policy.delayForAttemptMs(3))
        assertEquals(12_000L, policy.delayForAttemptMs(4))
        assertEquals(15_000L, policy.delayForAttemptMs(5))
        assertEquals(15_000L, policy.delayForAttemptMs(10_000))
    }
}
