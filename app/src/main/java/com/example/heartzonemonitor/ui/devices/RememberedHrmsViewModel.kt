package com.example.heartzonemonitor.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.heartzonemonitor.data.local.dao.RememberedHrmDao
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RememberedHrmsUiState(
    val activeHrm: RememberedHrmEntity? = null,
    val inactiveHrms: List<RememberedHrmEntity> = emptyList(),
    val allHrms: List<RememberedHrmEntity> = emptyList(),
    val editingNicknameTarget: RememberedHrmEntity? = null,
    val nicknameDraft: String = "",
    val forgetConfirmationTarget: RememberedHrmEntity? = null,
    val showActiveHrmDeletedPrompt: Boolean = false,
    val isSessionActive: Boolean = false,
    val errorMessage: String? = null
)

class RememberedHrmsViewModel(
    private val rememberedHrmDao: RememberedHrmDao,
    private val isSessionActiveFlow: Flow<Boolean> = flowOf(false),
    private val onDisconnectActiveHrm: suspend (macAddress: String) -> Unit = {}
) : ViewModel() {

    private val _uiState = MutableStateFlow(RememberedHrmsUiState())
    val uiState: StateFlow<RememberedHrmsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            rememberedHrmDao.observeAllHrms().collect { hrms ->
                _uiState.update { current ->
                    current.copy(
                        activeHrm = hrms.firstOrNull { it.isActive },
                        inactiveHrms = hrms.filterNot { it.isActive },
                        allHrms = hrms
                    )
                }
            }
        }
        viewModelScope.launch {
            isSessionActiveFlow.collect { active ->
                _uiState.update { it.copy(isSessionActive = active) }
            }
        }
    }

    fun promoteToActive(macAddress: String) {
        if (_uiState.value.isSessionActive) {
            _uiState.update {
                it.copy(errorMessage = "Cannot change Active HRM while a Session is active.")
            }
            return
        }
        viewModelScope.launch {
            try {
                rememberedHrmDao.promoteToActiveHrm(macAddress)
                _uiState.update {
                    it.copy(
                        showActiveHrmDeletedPrompt = false,
                        errorMessage = null
                    )
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = t.message ?: "Failed to set Active HRM")
                }
            }
        }
    }

    fun startEditNickname(hrm: RememberedHrmEntity) {
        _uiState.update {
            it.copy(
                editingNicknameTarget = hrm,
                nicknameDraft = hrm.customNickname.orEmpty(),
                errorMessage = null
            )
        }
    }

    fun onNicknameDraftChange(text: String) {
        _uiState.update { it.copy(nicknameDraft = text) }
    }

    fun confirmEditNickname() {
        val target = _uiState.value.editingNicknameTarget ?: return
        val normalized = _uiState.value.nicknameDraft.trim().takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            try {
                rememberedHrmDao.updateNickname(target.macAddress, normalized)
                _uiState.update {
                    it.copy(
                        editingNicknameTarget = null,
                        nicknameDraft = "",
                        errorMessage = null
                    )
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        editingNicknameTarget = null,
                        nicknameDraft = "",
                        errorMessage = t.message ?: "Failed to update nickname"
                    )
                }
            }
        }
    }

    fun dismissEditNickname() {
        _uiState.update {
            it.copy(
                editingNicknameTarget = null,
                nicknameDraft = ""
            )
        }
    }

    fun requestForgetDevice(hrm: RememberedHrmEntity) {
        if (_uiState.value.isSessionActive && hrm.isActive) {
            _uiState.update {
                it.copy(errorMessage = "Cannot forget the Active HRM while a Session is active.")
            }
            return
        }
        _uiState.update {
            it.copy(
                forgetConfirmationTarget = hrm,
                errorMessage = null
            )
        }
    }

    fun dismissForgetDevice() {
        _uiState.update { it.copy(forgetConfirmationTarget = null) }
    }

    fun confirmForgetDevice() {
        val target = _uiState.value.forgetConfirmationTarget ?: return
        val currentTarget = _uiState.value.allHrms.firstOrNull { it.macAddress == target.macAddress } ?: target
        if (_uiState.value.isSessionActive && currentTarget.isActive) {
            _uiState.update {
                it.copy(
                    forgetConfirmationTarget = null,
                    errorMessage = "Cannot forget the Active HRM while a Session is active."
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                forgetConfirmationTarget = null,
                errorMessage = null
            )
        }
        viewModelScope.launch {
            try {
                if (currentTarget.isActive) {
                    onDisconnectActiveHrm(currentTarget.macAddress)
                    rememberedHrmDao.clearActiveHrms()
                    rememberedHrmDao.deleteHrmByMacAddress(currentTarget.macAddress)
                    _uiState.update { it.copy(showActiveHrmDeletedPrompt = true) }
                } else {
                    rememberedHrmDao.deleteHrmByMacAddress(currentTarget.macAddress)
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = t.message ?: "Failed to forget Heart Rate Monitor")
                }
            }
        }
    }

    fun dismissActiveDeletedPrompt() {
        _uiState.update { it.copy(showActiveHrmDeletedPrompt = false) }
    }
}
