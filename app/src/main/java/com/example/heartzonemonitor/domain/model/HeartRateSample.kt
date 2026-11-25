package com.example.heartzonemonitor.domain.model

/**
 * Pure domain model representing a single 1 Hz Heart Rate Sample recorded during a Session.
 */
data class HeartRateSample(
    val id: Long = 0L,
    val sessionId: String,
    val timestampEpochMs: Long,
    val bpm: Int,
    val zoneIndex: Int,
    val sensorContact: Boolean
) {
    init {
        require(sessionId.isNotBlank()) { "Heart Rate Sample sessionId must not be blank" }
        require(bpm in MIN_BPM..MAX_BPM) {
            "Heart Rate Sample bpm must be between $MIN_BPM and $MAX_BPM"
        }
        require(zoneIndex in MIN_ZONE_INDEX..MAX_ZONE_INDEX) {
            "Heart Rate Sample zoneIndex must be between $MIN_ZONE_INDEX and $MAX_ZONE_INDEX"
        }
    }

    companion object {
        const val MIN_BPM = 0
        const val MAX_BPM = 255
        const val MIN_ZONE_INDEX = 1
        const val MAX_ZONE_INDEX = 5
    }
}
