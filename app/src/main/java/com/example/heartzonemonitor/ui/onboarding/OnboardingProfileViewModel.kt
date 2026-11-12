package com.example.heartzonemonitor.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.heartzonemonitor.data.local.dao.UserProfileDao
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import com.example.heartzonemonitor.domain.model.ZoneCalculationResult
import com.example.heartzonemonitor.domain.validation.OnboardingProfileValidator
import com.example.heartzonemonitor.domain.validation.ProfileValidationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Year

data class OnboardingProfileUiState(
    val birthYearInput: String = "",
    val restingHrInput: String = "",
    val isCustomHrmaxEnabled: Boolean = false,
    val customHrmaxInput: String = "",
    val birthYearError: String? = null,
    val restingHrError: String? = null,
    val customHrmaxError: String? = null,
    val calculationMode: ZoneCalculationMode = ZoneCalculationMode.PERCENT_HRMAX,
    val previewResult: ZoneCalculationResult? = null,
    val isSaveEnabled: Boolean = false,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    val errorMessage: String? = null
)

class OnboardingProfileViewModel(
    private val userProfileDao: UserProfileDao,
    private val validator: OnboardingProfileValidator = OnboardingProfileValidator(),
    private val currentYearProvider: () -> Int = { Year.now().value },
    private val epochMsProvider: () -> Long = { System.currentTimeMillis() }
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingProfileUiState())
    val uiState: StateFlow<OnboardingProfileUiState> = _uiState.asStateFlow()

    private var latestValidation: ProfileValidationResult? = null

    init {
        revalidate()
    }

    fun onBirthYearChange(input: String) {
        _uiState.update { it.copy(birthYearInput = input) }
        revalidate()
    }

    fun onRestingHrChange(input: String) {
        _uiState.update { it.copy(restingHrInput = input) }
        revalidate()
    }

    fun onCustomHrmaxToggled(enabled: Boolean) {
        _uiState.update { it.copy(isCustomHrmaxEnabled = enabled) }
        revalidate()
    }

    fun onCustomHrmaxChange(input: String) {
        _uiState.update { it.copy(customHrmaxInput = input) }
        revalidate()
    }

    fun saveProfile(): Boolean {
        val currentState = _uiState.value
        val validation = latestValidation ?: return false
        if (!validation.isValid || validation.validatedBirthYear == null) {
            return false
        }

        val profile = UserProfileEntity(
            id = UserProfileEntity.SINGLETON_ID,
            birthYear = validation.validatedBirthYear,
            restingHr = validation.validatedRestingHr,
            customHrmax = validation.validatedCustomHrmax,
            calculationMode = validation.calculationMode,
            updatedAtEpochMs = epochMsProvider()
        )

        _uiState.update { it.copy(isSaving = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                userProfileDao.upsertProfile(profile)
                _uiState.update { it.copy(isSaving = false, saveSuccess = true) }
            } catch (t: Throwable) {
                _uiState.update { it.copy(isSaving = false, errorMessage = t.message ?: "Failed to save profile") }
            }
        }
        return true
    }

    private fun revalidate() {
        val state = _uiState.value
        val result = validator.validate(
            birthYearInput = state.birthYearInput,
            restingHrInput = state.restingHrInput,
            isCustomHrmaxEnabled = state.isCustomHrmaxEnabled,
            customHrmaxInput = state.customHrmaxInput,
            currentYear = currentYearProvider()
        )
        latestValidation = result

        _uiState.update { current ->
            current.copy(
                birthYearError = if (current.birthYearInput.isEmpty()) null else result.birthYearError,
                restingHrError = result.restingHrError,
                customHrmaxError = if (current.customHrmaxInput.isEmpty()) null else result.customHrmaxError,
                calculationMode = result.calculationMode,
                previewResult = result.previewResult,
                isSaveEnabled = result.isValid
            )
        }
    }
}
