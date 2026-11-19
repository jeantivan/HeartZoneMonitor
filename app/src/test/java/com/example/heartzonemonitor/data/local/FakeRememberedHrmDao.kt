package com.example.heartzonemonitor.data.local

import com.example.heartzonemonitor.data.local.dao.RememberedHrmDao
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeRememberedHrmDao(
    initialHrms: List<RememberedHrmEntity> = emptyList()
) : RememberedHrmDao {

    private val hrmsState = MutableStateFlow(normalizeList(initialHrms))

    override fun observeActiveHrm(): Flow<RememberedHrmEntity?> =
        hrmsState.map { list -> list.firstOrNull { it.isActive } }

    override suspend fun getActiveHrm(): RememberedHrmEntity? =
        hrmsState.value.firstOrNull { it.isActive }

    override fun observeAllHrms(): Flow<List<RememberedHrmEntity>> = hrmsState

    override suspend fun getAllHrms(): List<RememberedHrmEntity> = hrmsState.value

    override suspend fun getHrmByMacAddress(macAddress: String): RememberedHrmEntity? =
        hrmsState.value.firstOrNull { it.macAddress == macAddress }

    override suspend fun getActiveHrmCount(): Int =
        hrmsState.value.count { it.isActive }

    override suspend fun upsertHrmRecord(hrm: RememberedHrmEntity) {
        val mutable = hrmsState.value.toMutableList()
        val existingIndex = mutable.indexOfFirst { it.macAddress == hrm.macAddress }
        if (existingIndex >= 0) {
            mutable[existingIndex] = hrm
        } else {
            mutable.add(hrm)
        }
        hrmsState.value = sortHrms(mutable)
    }

    override suspend fun upsertHrm(hrm: RememberedHrmEntity) {
        val current = if (hrm.isActive) {
            hrmsState.value.map { existing ->
                if (existing.macAddress != hrm.macAddress && existing.isActive) {
                    existing.copy(isActive = false)
                } else {
                    existing
                }
            }
        } else {
            hrmsState.value
        }
        val mutable = current.toMutableList()
        val existingIndex = mutable.indexOfFirst { it.macAddress == hrm.macAddress }
        if (existingIndex >= 0) {
            mutable[existingIndex] = hrm
        } else {
            mutable.add(hrm)
        }
        hrmsState.value = sortHrms(mutable)
    }

    override suspend fun clearActiveHrms() {
        hrmsState.value = sortHrms(
            hrmsState.value.map { if (it.isActive) it.copy(isActive = false) else it }
        )
    }

    override suspend fun deactivateOtherHrms(macAddress: String) {
        hrmsState.value = sortHrms(
            hrmsState.value.map {
                if (it.macAddress != macAddress && it.isActive) it.copy(isActive = false) else it
            }
        )
    }

    override suspend fun markHrmAsActive(macAddress: String): Int {
        var updatedCount = 0
        val updated = hrmsState.value.map { hrm ->
            if (hrm.macAddress == macAddress) {
                updatedCount++
                hrm.copy(isActive = true)
            } else {
                hrm
            }
        }
        if (updatedCount > 0) {
            hrmsState.value = sortHrms(updated)
        }
        return updatedCount
    }

    override suspend fun promoteToActiveHrm(macAddress: String) {
        val exists = hrmsState.value.any { it.macAddress == macAddress }
        require(exists) {
            "Cannot promote unknown Remembered HRM to Active HRM: $macAddress"
        }
        hrmsState.value = sortHrms(
            hrmsState.value.map { hrm ->
                hrm.copy(isActive = hrm.macAddress == macAddress)
            }
        )
    }

    override suspend fun setActiveHrm(macAddress: String) {
        promoteToActiveHrm(macAddress)
    }

    override suspend fun updateNickname(macAddress: String, customNickname: String?): Int {
        var updatedCount = 0
        hrmsState.value = sortHrms(
            hrmsState.value.map { hrm ->
                if (hrm.macAddress == macAddress) {
                    updatedCount++
                    hrm.copy(customNickname = customNickname)
                } else {
                    hrm
                }
            }
        )
        return updatedCount
    }

    override suspend fun updateBatteryLevel(macAddress: String, batteryLevel: Int?): Int {
        var updatedCount = 0
        hrmsState.value = sortHrms(
            hrmsState.value.map { hrm ->
                if (hrm.macAddress == macAddress) {
                    updatedCount++
                    hrm.copy(batteryLevel = batteryLevel)
                } else {
                    hrm
                }
            }
        )
        return updatedCount
    }

    override suspend fun updateLastSeenTimestamp(macAddress: String, lastSeenTimestamp: Long): Int {
        var updatedCount = 0
        hrmsState.value = sortHrms(
            hrmsState.value.map { hrm ->
                if (hrm.macAddress == macAddress) {
                    updatedCount++
                    hrm.copy(lastSeenTimestamp = lastSeenTimestamp)
                } else {
                    hrm
                }
            }
        )
        return updatedCount
    }

    override suspend fun deleteHrmByMacAddress(macAddress: String): Int {
        val before = hrmsState.value
        val after = before.filterNot { it.macAddress == macAddress }
        hrmsState.value = sortHrms(after)
        return before.size - after.size
    }

    override suspend fun deleteHrm(hrm: RememberedHrmEntity): Int {
        return deleteHrmByMacAddress(hrm.macAddress)
    }

    override suspend fun deleteAllHrms() {
        hrmsState.value = emptyList()
    }

    private fun normalizeList(hrms: List<RememberedHrmEntity>): List<RememberedHrmEntity> {
        val lastActiveMac = hrms.lastOrNull { it.isActive }?.macAddress
        val normalized = hrms.map { hrm ->
            hrm.copy(isActive = hrm.macAddress == lastActiveMac)
        }
        return sortHrms(normalized)
    }

    private fun sortHrms(hrms: List<RememberedHrmEntity>): List<RememberedHrmEntity> {
        return hrms.sortedWith(
            compareByDescending<RememberedHrmEntity> { it.isActive }
                .thenByDescending { it.lastSeenTimestamp }
                .thenBy { it.macAddress }
        )
    }
}
