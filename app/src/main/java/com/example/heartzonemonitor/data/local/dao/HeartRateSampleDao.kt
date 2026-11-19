package com.example.heartzonemonitor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.heartzonemonitor.data.local.entity.HeartRateSampleEntity
import kotlinx.coroutines.flow.Flow

/** Data access object for atomic batch insertion and indexed retrieval of Heart Rate Samples. */
@Dao
interface HeartRateSampleDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSamples(samples: List<HeartRateSampleEntity>)

    @Transaction
    suspend fun insertSamplesBatch(samples: List<HeartRateSampleEntity>) {
        if (samples.isEmpty()) return
        insertSamples(samples)
    }

    @Query(
        "SELECT * FROM heart_rate_samples WHERE sessionId = :sessionId " +
            "ORDER BY timestampEpochMs ASC, id ASC"
    )
    suspend fun getSamplesForSession(sessionId: String): List<HeartRateSampleEntity>

    @Query(
        "SELECT * FROM heart_rate_samples WHERE sessionId = :sessionId " +
            "ORDER BY timestampEpochMs ASC, id ASC"
    )
    fun observeSamplesForSession(sessionId: String): Flow<List<HeartRateSampleEntity>>

    @Query(
        "SELECT * FROM heart_rate_samples WHERE sessionId = :sessionId " +
            "ORDER BY timestampEpochMs DESC, id DESC LIMIT 1"
    )
    suspend fun getLatestSampleForSession(sessionId: String): HeartRateSampleEntity?

    @Query("SELECT COUNT(*) FROM heart_rate_samples WHERE sessionId = :sessionId")
    suspend fun getSampleCountForSession(sessionId: String): Int

    @Query(
        "SELECT * FROM heart_rate_samples WHERE sessionId = :sessionId " +
            "ORDER BY timestampEpochMs ASC, id ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getSamplesPage(
        sessionId: String,
        limit: Int,
        offset: Int
    ): List<HeartRateSampleEntity>
}
