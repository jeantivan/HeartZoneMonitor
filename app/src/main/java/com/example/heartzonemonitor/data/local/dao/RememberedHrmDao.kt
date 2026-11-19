package com.example.heartzonemonitor.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RememberedHrmDao {

    @Query("SELECT * FROM remembered_hrms WHERE isActive = 1 LIMIT 1")
    fun observeActiveHrm(): Flow<RememberedHrmEntity?>

    fun getActiveHrmFlow(): Flow<RememberedHrmEntity?> = observeActiveHrm()

    @Query("SELECT * FROM remembered_hrms WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveHrm(): RememberedHrmEntity?

    @Query("SELECT * FROM remembered_hrms ORDER BY isActive DESC, lastSeenTimestamp DESC, macAddress ASC")
    fun observeAllHrms(): Flow<List<RememberedHrmEntity>>

    fun getAllHrmsFlow(): Flow<List<RememberedHrmEntity>> = observeAllHrms()

    @Query("SELECT * FROM remembered_hrms ORDER BY isActive DESC, lastSeenTimestamp DESC, macAddress ASC")
    suspend fun getAllHrms(): List<RememberedHrmEntity>

    @Query("SELECT * FROM remembered_hrms WHERE macAddress = :macAddress LIMIT 1")
    suspend fun getHrmByMacAddress(macAddress: String): RememberedHrmEntity?

    @Query("SELECT COUNT(*) FROM remembered_hrms WHERE isActive = 1")
    suspend fun getActiveHrmCount(): Int

    @Upsert
    suspend fun upsertHrmRecord(hrm: RememberedHrmEntity)

    @Transaction
    suspend fun upsertHrm(hrm: RememberedHrmEntity) {
        if (hrm.isActive) {
            deactivateOtherHrms(hrm.macAddress)
        }
        upsertHrmRecord(hrm)
    }

    @Query("UPDATE remembered_hrms SET isActive = 0 WHERE isActive = 1")
    suspend fun clearActiveHrms()

    @Query("UPDATE remembered_hrms SET isActive = 0 WHERE macAddress != :macAddress AND isActive = 1")
    suspend fun deactivateOtherHrms(macAddress: String)

    @Query("UPDATE remembered_hrms SET isActive = 1 WHERE macAddress = :macAddress")
    suspend fun markHrmAsActive(macAddress: String): Int

    @Transaction
    suspend fun promoteToActiveHrm(macAddress: String) {
        clearActiveHrms()
        val updatedRows = markHrmAsActive(macAddress)
        require(updatedRows > 0) {
            "Cannot promote unknown Remembered HRM to Active HRM: $macAddress"
        }
    }

    @Transaction
    suspend fun setActiveHrm(macAddress: String) {
        promoteToActiveHrm(macAddress)
    }

    @Query("UPDATE remembered_hrms SET customNickname = :customNickname WHERE macAddress = :macAddress")
    suspend fun updateNickname(macAddress: String, customNickname: String?): Int

    @Query("UPDATE remembered_hrms SET batteryLevel = :batteryLevel WHERE macAddress = :macAddress")
    suspend fun updateBatteryLevel(macAddress: String, batteryLevel: Int?): Int

    @Query("UPDATE remembered_hrms SET lastSeenTimestamp = :lastSeenTimestamp WHERE macAddress = :macAddress")
    suspend fun updateLastSeenTimestamp(macAddress: String, lastSeenTimestamp: Long): Int

    @Query("DELETE FROM remembered_hrms WHERE macAddress = :macAddress")
    suspend fun deleteHrmByMacAddress(macAddress: String): Int

    suspend fun deleteHrm(macAddress: String): Int = deleteHrmByMacAddress(macAddress)

    @Delete
    suspend fun deleteHrm(hrm: RememberedHrmEntity): Int

    @Query("DELETE FROM remembered_hrms")
    suspend fun deleteAllHrms()
}
