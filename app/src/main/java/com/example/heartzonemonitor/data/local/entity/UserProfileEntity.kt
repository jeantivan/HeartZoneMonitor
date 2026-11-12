package com.example.heartzonemonitor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode

typealias UserProfile = UserProfileEntity

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey
    val id: Int = SINGLETON_ID,
    val birthYear: Int,
    val restingHr: Int?,
    val customHrmax: Int?,
    val calculationMode: ZoneCalculationMode,
    val updatedAtEpochMs: Long
) {
    init {
        require(id == SINGLETON_ID) { "user_profile row ID must be $SINGLETON_ID" }
    }

    companion object {
        const val SINGLETON_ID = 1
    }
}
