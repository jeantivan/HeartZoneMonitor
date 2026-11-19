package com.example.heartzonemonitor.data.local.entity

/** Lifecycle status of a recorded Heart Zone monitoring Session. */
enum class SessionStatus {
    RECORDING,
    PAUSED,
    COMPLETED,
    COMPLETED_UNEXPECTEDLY,
    DISCARDED
}
