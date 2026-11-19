package com.example.heartzonemonitor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

typealias RememberedHrm = RememberedHrmEntity

@Entity(tableName = "remembered_hrms")
data class RememberedHrmEntity(
    @PrimaryKey
    val macAddress: String,
    val advertisedName: String,
    val customNickname: String? = null,
    val lastSeenTimestamp: Long,
    val batteryLevel: Int? = null,
    val isActive: Boolean = false
) {
    init {
        require(macAddress.isNotBlank()) { "Remembered HRM macAddress must not be blank" }
        if (batteryLevel != null) {
            require(batteryLevel in MIN_BATTERY_LEVEL..MAX_BATTERY_LEVEL) {
                "Remembered HRM batteryLevel must be between $MIN_BATTERY_LEVEL and $MAX_BATTERY_LEVEL"
            }
        }
    }

    fun displayName(): String = customNickname?.takeIf { it.isNotBlank() } ?: advertisedName

    companion object {
        const val MIN_BATTERY_LEVEL = 0
        const val MAX_BATTERY_LEVEL = 100
    }
}
