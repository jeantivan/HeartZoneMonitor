package com.example.heartzonemonitor.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.heartzonemonitor.domain.model.HeartRateSample

/**
 * Room entity representing a single 1 Hz Heart Rate Sample associated with a Session.
 * Cascades deletions from its parent Session and maintains a composite index for ordered streaming.
 */
@Entity(
    tableName = "heart_rate_samples",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(
            name = "idx_heart_rate_samples_session_ts",
            value = ["sessionId", "timestampEpochMs"],
            orders = [Index.Order.ASC, Index.Order.ASC]
        )
    ]
)
data class HeartRateSampleEntity(
    @PrimaryKey(autoGenerate = true)
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

    fun toDomain(): HeartRateSample = HeartRateSample(
        id = id,
        sessionId = sessionId,
        timestampEpochMs = timestampEpochMs,
        bpm = bpm,
        zoneIndex = zoneIndex,
        sensorContact = sensorContact
    )

    companion object {
        const val MIN_BPM = HeartRateSample.MIN_BPM
        const val MAX_BPM = HeartRateSample.MAX_BPM
        const val MIN_ZONE_INDEX = HeartRateSample.MIN_ZONE_INDEX
        const val MAX_ZONE_INDEX = HeartRateSample.MAX_ZONE_INDEX
    }
}

fun HeartRateSample.toEntity(): HeartRateSampleEntity = HeartRateSampleEntity(
    id = id,
    sessionId = sessionId,
    timestampEpochMs = timestampEpochMs,
    bpm = bpm,
    zoneIndex = zoneIndex,
    sensorContact = sensorContact
)
