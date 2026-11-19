package com.example.heartzonemonitor.data.timing

import android.os.SystemClock
import com.example.heartzonemonitor.domain.timing.SessionClock

/**
 * Android [SessionClock] backed by [SystemClock.elapsedRealtime] for monotonic Session duration
 * accumulation and [System.currentTimeMillis] for Session start and end epoch metadata.
 */
object AndroidSessionClock : SessionClock {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    override fun currentEpochMs(): Long = System.currentTimeMillis()
}
