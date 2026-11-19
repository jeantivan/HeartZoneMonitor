package com.example.heartzonemonitor.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.example.heartzonemonitor.data.local.entity.SessionEntity
import com.example.heartzonemonitor.data.local.entity.SessionStatus
import kotlinx.coroutines.flow.Flow

/** Data access object for persisting and querying recorded Sessions. */
@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: SessionEntity)

    @Upsert
    suspend fun upsertSession(session: SessionEntity)

    @Update
    suspend fun updateSession(session: SessionEntity): Int

    @Query("SELECT * FROM sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :sessionId LIMIT 1")
    fun observeSessionById(sessionId: String): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions ORDER BY startEpochMs DESC, id ASC")
    fun observeAllSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY startEpochMs DESC, id ASC")
    suspend fun getAllSessions(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE status IN (:statuses) ORDER BY startEpochMs DESC, id ASC")
    suspend fun getSessionsByStatuses(statuses: List<SessionStatus>): List<SessionEntity>

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: String): Int

    @Delete
    suspend fun deleteSession(session: SessionEntity): Int
}
