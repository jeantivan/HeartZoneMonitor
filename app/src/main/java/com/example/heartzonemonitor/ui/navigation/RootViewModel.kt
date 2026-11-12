package com.example.heartzonemonitor.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.heartzonemonitor.data.local.dao.UserProfileDao
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface RootNavState {
    data object Loading : RootNavState
    data object Onboarding : RootNavState
    data class Ready(val userProfile: UserProfileEntity) : RootNavState
}

class RootViewModel(
    userProfileDao: UserProfileDao
) : ViewModel() {

    val navState: StateFlow<RootNavState> = userProfileDao.getProfileFlow()
        .map { profile ->
            if (profile == null) {
                RootNavState.Onboarding
            } else {
                RootNavState.Ready(profile)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = RootNavState.Loading
        )
}
