package com.example.heartzonemonitor.ui.navigation

import com.example.heartzonemonitor.data.local.FakeRememberedHrmDao
import com.example.heartzonemonitor.data.local.FakeUserProfileDao
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RootViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeDao: FakeUserProfileDao

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeDao = FakeUserProfileDao()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `when profile is null, navState emits Onboarding`() = runTest(testDispatcher) {
        val viewModel = RootViewModel(userProfileDao = fakeDao)
        val state = viewModel.navState.first { it !is RootNavState.Loading }
        assertEquals(RootNavState.Onboarding, state)
    }

    @Test
    fun `when profile exists, navState emits Ready with existing profile`() = runTest(testDispatcher) {
        val testProfile = UserProfileEntity(
            id = UserProfileEntity.SINGLETON_ID,
            birthYear = 1990,
            restingHr = 60,
            customHrmax = null,
            calculationMode = ZoneCalculationMode.KARVONEN,
            updatedAtEpochMs = 1000L
        )
        fakeDao.emitProfile(testProfile)

        val viewModel = RootViewModel(userProfileDao = fakeDao)
        val state = viewModel.navState.first { it !is RootNavState.Loading }
        assertTrue(state is RootNavState.Ready)
        assertEquals(testProfile, (state as RootNavState.Ready).userProfile)
    }

    @Test
    fun `when profile is saved dynamically, navState transitions from Onboarding to Ready`() = runTest(testDispatcher) {
        val viewModel = RootViewModel(userProfileDao = fakeDao)
        val channel = Channel<RootNavState>(Channel.UNLIMITED)
        val collectJob = launch {
            viewModel.navState.collect { channel.send(it) }
        }

        val firstState = channel.receive()
        assertEquals(RootNavState.Onboarding, firstState)

        val newProfile = UserProfileEntity(
            id = UserProfileEntity.SINGLETON_ID,
            birthYear = 1985,
            restingHr = null,
            customHrmax = 195,
            calculationMode = ZoneCalculationMode.PERCENT_HRMAX,
            updatedAtEpochMs = 2000L
        )
        fakeDao.upsertProfile(newProfile)

        val nextState = channel.receive()
        assertTrue(nextState is RootNavState.Ready)
        assertEquals(newProfile, (nextState as RootNavState.Ready).userProfile)

        collectJob.cancel()
    }

    @Test
    fun `activeHrm emits null when rememberedHrmDao is omitted`() = runTest(testDispatcher) {
        val viewModel = RootViewModel(userProfileDao = fakeDao)
        assertNull(viewModel.activeHrm.first())
    }

    @Test
    fun `activeHrm tracks Active HRM persistence and deletion reactively`() = runTest(testDispatcher) {
        val fakeHrmDao = FakeRememberedHrmDao()
        val viewModel = RootViewModel(
            userProfileDao = fakeDao,
            rememberedHrmDao = fakeHrmDao
        )
        val channel = Channel<RememberedHrmEntity?>(Channel.UNLIMITED)
        val collectJob = launch {
            viewModel.activeHrm.collect { channel.send(it) }
        }

        assertNull(channel.receive())

        val activeStrap = RememberedHrmEntity(
            macAddress = "D4:36:39:88:21:9B",
            advertisedName = "COOSPO H808S",
            customNickname = null,
            lastSeenTimestamp = 5_000L,
            batteryLevel = null,
            isActive = true
        )
        fakeHrmDao.upsertHrm(activeStrap)
        assertEquals(activeStrap, channel.receive())

        fakeHrmDao.deleteHrmByMacAddress(activeStrap.macAddress)
        assertNull(channel.receive())

        collectJob.cancel()
    }
}
