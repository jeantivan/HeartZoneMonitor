package com.example.heartzonemonitor.data.local

import com.example.heartzonemonitor.data.local.dao.UserProfileDao
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeUserProfileDao(
    initialProfile: UserProfileEntity? = null
) : UserProfileDao {

    private val flow = MutableStateFlow<UserProfileEntity?>(initialProfile)

    fun emitProfile(profile: UserProfileEntity?) {
        flow.value = profile
    }

    override fun getProfileFlow(): Flow<UserProfileEntity?> = flow

    override suspend fun getProfile(): UserProfileEntity? = flow.value

    override suspend fun getProfileCount(): Int = if (flow.value != null) 1 else 0

    override suspend fun upsertProfile(profile: UserProfileEntity) {
        flow.value = profile
    }

    override suspend fun deleteProfile() {
        flow.value = null
    }
}
