package com.example.heartzonemonitor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import java.util.UUID

typealias Session = SessionEntity

/**
 * Room entity representing a single heart rate monitoring Session with frozen User Profile
 * parameters and precalculated summary metrics.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val startEpochMs: Long,
    val endEpochMs: Long? = null,
    val elapsedDurationMs: Long = 0L,
    val effectiveDurationMs: Long = 0L,
    val pausedDurationMs: Long = 0L,
    val status: SessionStatus = SessionStatus.RECORDING,
    // Plain TEXT snapshot without a foreign key so forgetting a Remembered HRM never affects historical Sessions.
    val hrmMacAddress: String,
    val userAge: Int,
    val calculatedHrmax: Int,
    val restingHr: Int? = null,
    val calculationMode: ZoneCalculationMode,
    val avgBpm: Int = 0,
    val maxBpm: Int = 0,
    val minBpm: Int = 0,
    val zone1DurationMs: Long = 0L,
    val zone2DurationMs: Long = 0L,
    val zone3DurationMs: Long = 0L,
    val zone4DurationMs: Long = 0L,
    val zone5DurationMs: Long = 0L
) {
    init {
        require(id.isNotBlank()) { "Session id must not be blank" }
        require(hrmMacAddress.isNotBlank()) { "Session hrmMacAddress must not be blank" }
        require(elapsedDurationMs >= 0L) { "Session elapsedDurationMs must be non-negative" }
        require(effectiveDurationMs >= 0L) { "Session effectiveDurationMs must be non-negative" }
        require(pausedDurationMs >= 0L) { "Session pausedDurationMs must be non-negative" }
        require(avgBpm >= 0) { "Session avgBpm must be non-negative" }
        require(maxBpm >= 0) { "Session maxBpm must be non-negative" }
        require(minBpm >= 0) { "Session minBpm must be non-negative" }
        require(zone1DurationMs >= 0L) { "Session zone1DurationMs must be non-negative" }
        require(zone2DurationMs >= 0L) { "Session zone2DurationMs must be non-negative" }
        require(zone3DurationMs >= 0L) { "Session zone3DurationMs must be non-negative" }
        require(zone4DurationMs >= 0L) { "Session zone4DurationMs must be non-negative" }
        require(zone5DurationMs >= 0L) { "Session zone5DurationMs must be non-negative" }
    }
}
