package com.example.heartzonemonitor.ui.onboarding

import com.example.heartzonemonitor.data.local.dao.UserProfileDao
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingProfileViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val fakeDao = FakeUserProfileDao()
    private val fixedCurrentYear = 2026
    private val fixedEpochMs = 1700000000000L

    private lateinit var viewModel: OnboardingProfileViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = OnboardingProfileViewModel(
            userProfileDao = fakeDao,
            currentYearProvider = { fixedCurrentYear },
            epochMsProvider = { fixedEpochMs }
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has empty fields and save disabled`() {
        val state = viewModel.uiState.value
        assertEquals("", state.birthYearInput)
        assertEquals("", state.restingHrInput)
        assertFalse(state.isCustomHrmaxEnabled)
        assertEquals("", state.customHrmaxInput)
        assertFalse(state.isSaveEnabled)
        assertNull(state.previewResult)
    }

    @Test
    fun `entering valid birth year enables save and computes Tanaka preview`() {
        viewModel.onBirthYearChange("1990")

        val state = viewModel.uiState.value
        assertEquals("1990", state.birthYearInput)
        assertNull(state.birthYearError)
        assertTrue(state.isSaveEnabled)
        assertNotNull(state.previewResult)
        assertEquals(183, state.previewResult?.hrMax)
        assertEquals(ZoneCalculationMode.PERCENT_HRMAX, state.calculationMode)
    }

    @Test
    fun `entering resting hr switches mode to KARVONEN and recalculates preview`() {
        viewModel.onBirthYearChange("1990")
        viewModel.onRestingHrChange("60")

        val state = viewModel.uiState.value
        assertEquals("60", state.restingHrInput)
        assertNull(state.restingHrError)
        assertTrue(state.isSaveEnabled)
        assertEquals(ZoneCalculationMode.KARVONEN, state.calculationMode)
        assertEquals(ZoneCalculationMode.KARVONEN, state.previewResult?.calculationMode)
    }

    @Test
    fun `enabling custom hrmax without value disables save until valid value entered`() {
        viewModel.onBirthYearChange("1990")
        viewModel.onCustomHrmaxToggled(true)

        var state = viewModel.uiState.value
        assertTrue(state.isCustomHrmaxEnabled)
        assertFalse(state.isSaveEnabled)
        // Error is suppressed until input is touched to avoid premature error state
        assertNull(state.customHrmaxError)

        viewModel.onCustomHrmaxChange("90") // invalid
        state = viewModel.uiState.value
        assertFalse(state.isSaveEnabled)
        assertNotNull(state.customHrmaxError)

        viewModel.onCustomHrmaxChange("190") // valid
        state = viewModel.uiState.value
        assertTrue(state.isSaveEnabled)
        assertNull(state.customHrmaxError)
        assertEquals(190, state.previewResult?.hrMax)
    }

    @Test
    fun `saveProfile persists UserProfileEntity to UserProfileDao and marks success`() = runTest(testDispatcher) {
        viewModel.onBirthYearChange("1988")
        viewModel.onRestingHrChange("55")

        val success = viewModel.saveProfile()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(success)
        assertTrue(viewModel.uiState.value.saveSuccess)

        val persisted = fakeDao.getProfile()
        assertNotNull(persisted)
        assertEquals(UserProfileEntity.SINGLETON_ID, persisted?.id)
        assertEquals(1988, persisted?.birthYear)
        assertEquals(55, persisted?.restingHr)
        assertNull(persisted?.customHrmax)
        assertEquals(ZoneCalculationMode.KARVONEN, persisted?.calculationMode)
        assertEquals(fixedEpochMs, persisted?.updatedAtEpochMs)
    }

    @Test
    fun `saveProfile returns false and does not persist when validation is failing`() = runTest(testDispatcher) {
        viewModel.onBirthYearChange("2025") // age 1, invalid

        val success = viewModel.saveProfile()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(success)
        assertFalse(viewModel.uiState.value.saveSuccess)
        assertNull(fakeDao.getProfile())
    }

    @Test
    fun `saveProfile handles database failure by reporting errorMessage`() = runTest(testDispatcher) {
        val failingDao = object : UserProfileDao {
            override fun getProfileFlow(): Flow<UserProfileEntity?> = flowOf(null)
            override suspend fun getProfile(): UserProfileEntity? = null
            override suspend fun getProfileCount(): Int = 0
            override suspend fun upsertProfile(profile: UserProfileEntity) {
                throw RuntimeException("SQLCipher disk I/O error")
            }
            override suspend fun deleteProfile() {}
        }
        val failingViewModel = OnboardingProfileViewModel(
            userProfileDao = failingDao,
            currentYearProvider = { fixedCurrentYear },
            epochMsProvider = { fixedEpochMs }
        )
        failingViewModel.onBirthYearChange("1988")

        val result = failingViewModel.saveProfile()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(result)
        assertFalse(failingViewModel.uiState.value.isSaving)
        assertFalse(failingViewModel.uiState.value.saveSuccess)
        assertEquals("SQLCipher disk I/O error", failingViewModel.uiState.value.errorMessage)
    }
}

private class FakeUserProfileDao : UserProfileDao {
    private var profile: UserProfileEntity? = null

    override fun getProfileFlow(): Flow<UserProfileEntity?> = flowOf(profile)

    override suspend fun getProfile(): UserProfileEntity? = profile

    override suspend fun getProfileCount(): Int = if (profile != null) 1 else 0

    override suspend fun upsertProfile(profile: UserProfileEntity) {
        this.profile = profile
    }

    override suspend fun deleteProfile() {
        this.profile = null
    }
}
