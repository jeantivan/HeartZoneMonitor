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

const val ACTIVE_SESSION_SWITCH_LOCKOUT_NOTICE =
    "Cannot switch sensor during an active session. Stop or complete the session first."

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
    private val onDisconnectActiveHrm: suspend (macAddress: String) -> Unit = {},
    private val isSessionActiveProvider: () -> Boolean = { false }
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        RememberedHrmsUiState(isSessionActive = isSessionActiveProvider())
    )
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
                val effectiveActive = active || isSessionActiveProvider()
                _uiState.update { current ->
                    val forgetTarget = current.forgetConfirmationTarget
                    val latestForgetTarget = forgetTarget?.let { target ->
                        current.allHrms.firstOrNull { it.macAddress == target.macAddress } ?: target
                    }
                    val closingActiveForgetDialog =
                        effectiveActive && latestForgetTarget?.isActive == true
                    current.copy(
                        isSessionActive = effectiveActive,
                        forgetConfirmationTarget = if (closingActiveForgetDialog) {
                            null
                        } else {
                            current.forgetConfirmationTarget
                        },
                        errorMessage = when {
                            closingActiveForgetDialog -> ACTIVE_SESSION_SWITCH_LOCKOUT_NOTICE
                            !effectiveActive &&
                                current.errorMessage == ACTIVE_SESSION_SWITCH_LOCKOUT_NOTICE -> null
                            else -> current.errorMessage
                        }
                    )
                }
            }
        }
    }

    private fun isSessionCurrentlyActive(): Boolean =
        _uiState.value.isSessionActive || isSessionActiveProvider()

    private fun resolveLatestHrm(hrm: RememberedHrmEntity): RememberedHrmEntity =
        _uiState.value.allHrms.firstOrNull { it.macAddress == hrm.macAddress } ?: hrm

    private fun applyActiveSessionLockout(clearForgetDialog: Boolean = false) {
        _uiState.update { current ->
            current.copy(
                isSessionActive = true,
                forgetConfirmationTarget = if (clearForgetDialog) null else current.forgetConfirmationTarget,
                errorMessage = ACTIVE_SESSION_SWITCH_LOCKOUT_NOTICE
            )
        }
    }

    fun promoteToActive(macAddress: String) {
        if (isSessionCurrentlyActive()) {
            applyActiveSessionLockout()
            return
        }
        viewModelScope.launch {
            if (isSessionCurrentlyActive()) {
                applyActiveSessionLockout()
                return@launch
            }
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
        val currentTarget = resolveLatestHrm(hrm)
        if (currentTarget.isActive && isSessionCurrentlyActive()) {
            applyActiveSessionLockout(clearForgetDialog = true)
            return
        }
        _uiState.update {
            it.copy(
                forgetConfirmationTarget = currentTarget,
                errorMessage = null
            )
        }
    }

    fun dismissForgetDevice() {
        _uiState.update { it.copy(forgetConfirmationTarget = null) }
    }

    fun confirmForgetDevice() {
        val target = _uiState.value.forgetConfirmationTarget ?: return
        val currentTarget = resolveLatestHrm(target)
        val isTargetActive = currentTarget.isActive
        if (isTargetActive && isSessionCurrentlyActive()) {
            applyActiveSessionLockout(clearForgetDialog = true)
            return
        }
        _uiState.update {
            it.copy(
                forgetConfirmationTarget = null,
                errorMessage = null
            )
        }
        viewModelScope.launch {
            if (isTargetActive && isSessionCurrentlyActive()) {
                applyActiveSessionLockout(clearForgetDialog = true)
                return@launch
            }
            try {
                val freshTarget = rememberedHrmDao.getHrmByMacAddress(currentTarget.macAddress) ?: currentTarget
                val isFreshTargetActive = freshTarget.isActive || isTargetActive
                if (isFreshTargetActive) {
                    if (isSessionCurrentlyActive()) {
                        applyActiveSessionLockout(clearForgetDialog = true)
                        return@launch
                    }
                    onDisconnectActiveHrm(freshTarget.macAddress)
                    if (isSessionCurrentlyActive()) {
                        applyActiveSessionLockout(clearForgetDialog = true)
                        return@launch
                    }
                    rememberedHrmDao.clearActiveHrms()
                    rememberedHrmDao.deleteHrmByMacAddress(freshTarget.macAddress)
                    _uiState.update { it.copy(showActiveHrmDeletedPrompt = true) }
                } else {
                    rememberedHrmDao.deleteHrmByMacAddress(freshTarget.macAddress)
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
