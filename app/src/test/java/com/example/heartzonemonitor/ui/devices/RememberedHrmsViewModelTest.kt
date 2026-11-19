package com.example.heartzonemonitor.ui.devices

import com.example.heartzonemonitor.data.local.FakeRememberedHrmDao
import com.example.heartzonemonitor.data.local.dao.RememberedHrmDao
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
class RememberedHrmsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val activeStrap = RememberedHrmEntity(
        macAddress = "D4:36:39:88:21:9B",
        advertisedName = "COOSPO H808S",
        customNickname = "Chest Strap",
        lastSeenTimestamp = 2000L,
        batteryLevel = 95,
        isActive = true
    )

    private val inactiveArmband = RememberedHrmEntity(
        macAddress = "A0:9E:1A:33:12:44",
        advertisedName = "Polar OH1",
        customNickname = null,
        lastSeenTimestamp = 3000L,
        batteryLevel = 80,
        isActive = false
    )

    private val inactiveBackup = RememberedHrmEntity(
        macAddress = "C2:11:85:44:90:4A",
        advertisedName = "Garmin HRM-Pro",
        customNickname = "Cycling Strap",
        lastSeenTimestamp = 1000L,
        batteryLevel = null,
        isActive = false
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial observation splits active and inactive remembered HRMs`() = runTest(testDispatcher) {
        val fakeDao = FakeRememberedHrmDao(listOf(activeStrap, inactiveArmband, inactiveBackup))
        val viewModel = RememberedHrmsViewModel(rememberedHrmDao = fakeDao)

        advanceUntilIdle()

        val state = viewModel.uiState.first { it.allHrms.size == 3 }
        assertEquals(activeStrap, state.activeHrm)
        assertEquals(listOf(inactiveArmband, inactiveBackup), state.inactiveHrms)
        assertEquals(3, state.allHrms.size)
        assertFalse(state.isSessionActive)
        assertFalse(state.showActiveHrmDeletedPrompt)
        assertNull(state.errorMessage)
    }

    @Test
    fun `promoteToActive atomically switches active HRM and clears deleted prompt`() = runTest(testDispatcher) {
        val fakeDao = FakeRememberedHrmDao(listOf(activeStrap, inactiveArmband))
        val viewModel = RememberedHrmsViewModel(rememberedHrmDao = fakeDao)
        advanceUntilIdle()

        val activeChannel = Channel<RememberedHrmEntity?>(Channel.UNLIMITED)
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            fakeDao.observeActiveHrm().collect { activeChannel.send(it) }
        }
        assertEquals(activeStrap.macAddress, activeChannel.receive()?.macAddress)

        viewModel.promoteToActive(inactiveArmband.macAddress)
        advanceUntilIdle()

        val promoted = activeChannel.receive()
        assertEquals(inactiveArmband.macAddress, promoted?.macAddress)
        assertEquals(1, fakeDao.getActiveHrmCount())

        val state = viewModel.uiState.value
        assertEquals(inactiveArmband.macAddress, state.activeHrm?.macAddress)
        assertEquals(listOf(activeStrap.macAddress), state.inactiveHrms.map { it.macAddress })
        assertFalse(state.showActiveHrmDeletedPrompt)

        collectJob.cancel()
    }

    @Test
    fun `edit nickname trims whitespace and updates DAO, blank input clears to null`() = runTest(testDispatcher) {
        val fakeDao = FakeRememberedHrmDao(listOf(activeStrap, inactiveArmband))
        val viewModel = RememberedHrmsViewModel(rememberedHrmDao = fakeDao)
        advanceUntilIdle()

        // Start editing inactive armband (no custom nickname initially)
        viewModel.startEditNickname(inactiveArmband)
        assertEquals(inactiveArmband, viewModel.uiState.value.editingNicknameTarget)
        assertEquals("", viewModel.uiState.value.nicknameDraft)

        viewModel.onNicknameDraftChange("  Track Armband  ")
        viewModel.confirmEditNickname()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.editingNicknameTarget)
        assertEquals("", viewModel.uiState.value.nicknameDraft)
        assertEquals("Track Armband", fakeDao.getHrmByMacAddress(inactiveArmband.macAddress)?.customNickname)

        // Now start editing active strap (pre-populated with "Chest Strap") and clear it
        val currentActive = fakeDao.getHrmByMacAddress(activeStrap.macAddress)!!
        viewModel.startEditNickname(currentActive)
        assertEquals("Chest Strap", viewModel.uiState.value.nicknameDraft)

        viewModel.onNicknameDraftChange("   ")
        viewModel.confirmEditNickname()
        advanceUntilIdle()

        assertNull(fakeDao.getHrmByMacAddress(activeStrap.macAddress)?.customNickname)
        assertEquals("COOSPO H808S", viewModel.uiState.value.activeHrm?.displayName())
    }

    @Test
    fun `dismissEditNickname discards draft without updating database`() = runTest(testDispatcher) {
        val fakeDao = FakeRememberedHrmDao(listOf(activeStrap))
        val viewModel = RememberedHrmsViewModel(rememberedHrmDao = fakeDao)
        advanceUntilIdle()

        viewModel.startEditNickname(activeStrap)
        viewModel.onNicknameDraftChange("Temporary Name")
        viewModel.dismissEditNickname()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.editingNicknameTarget)
        assertEquals("Chest Strap", fakeDao.getHrmByMacAddress(activeStrap.macAddress)?.customNickname)
    }

    @Test
    fun `forgetting inactive HRM deletes row without disconnecting GATT or showing active deleted prompt`() = runTest(testDispatcher) {
        val fakeDao = FakeRememberedHrmDao(listOf(activeStrap, inactiveArmband))
        val disconnectedMacs = mutableListOf<String>()
        val viewModel = RememberedHrmsViewModel(
            rememberedHrmDao = fakeDao,
            onDisconnectActiveHrm = { mac -> disconnectedMacs.add(mac) }
        )
        advanceUntilIdle()

        viewModel.requestForgetDevice(inactiveArmband)
        assertEquals(inactiveArmband, viewModel.uiState.value.forgetConfirmationTarget)

        viewModel.confirmForgetDevice()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.forgetConfirmationTarget)
        assertTrue(disconnectedMacs.isEmpty())
        assertFalse(viewModel.uiState.value.showActiveHrmDeletedPrompt)
        assertNull(fakeDao.getHrmByMacAddress(inactiveArmband.macAddress))
        assertEquals(activeStrap.macAddress, fakeDao.getActiveHrm()?.macAddress)
        assertEquals(1, viewModel.uiState.value.allHrms.size)
    }

    @Test
    fun `forgetting Active HRM disconnects GATT, clears active flag, deletes row, and shows prompt`() = runTest(testDispatcher) {
        val fakeDao = FakeRememberedHrmDao(listOf(activeStrap, inactiveArmband))
        val callOrder = mutableListOf<String>()
        val recordingDao = object : RememberedHrmDao by fakeDao {
            override suspend fun clearActiveHrms() {
                callOrder.add("clearActiveHrms")
                fakeDao.clearActiveHrms()
            }

            override suspend fun deleteHrmByMacAddress(macAddress: String): Int {
                callOrder.add("delete:$macAddress")
                return fakeDao.deleteHrmByMacAddress(macAddress)
            }
        }
        val viewModel = RememberedHrmsViewModel(
            rememberedHrmDao = recordingDao,
            onDisconnectActiveHrm = { mac -> callOrder.add("disconnect:$mac") }
        )
        advanceUntilIdle()

        viewModel.requestForgetDevice(activeStrap)
        assertEquals(activeStrap, viewModel.uiState.value.forgetConfirmationTarget)

        viewModel.confirmForgetDevice()
        advanceUntilIdle()

        assertEquals(
            listOf(
                "disconnect:${activeStrap.macAddress}",
                "clearActiveHrms",
                "delete:${activeStrap.macAddress}"
            ),
            callOrder
        )
        assertNull(fakeDao.getHrmByMacAddress(activeStrap.macAddress))
        assertNull(fakeDao.getActiveHrm())
        assertEquals(0, fakeDao.getActiveHrmCount())

        val state = viewModel.uiState.value
        assertNull(state.activeHrm)
        assertEquals(listOf(inactiveArmband), state.inactiveHrms)
        assertTrue(state.showActiveHrmDeletedPrompt)

        // Promoting remaining HRM clears the prompt
        viewModel.promoteToActive(inactiveArmband.macAddress)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.showActiveHrmDeletedPrompt)
        assertEquals(inactiveArmband.macAddress, viewModel.uiState.value.activeHrm?.macAddress)
    }

    @Test
    fun `dismissActiveDeletedPrompt hides the active deleted prompt`() = runTest(testDispatcher) {
        val fakeDao = FakeRememberedHrmDao(listOf(activeStrap))
        val viewModel = RememberedHrmsViewModel(rememberedHrmDao = fakeDao)
        advanceUntilIdle()

        viewModel.requestForgetDevice(activeStrap)
        viewModel.confirmForgetDevice()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showActiveHrmDeletedPrompt)

        viewModel.dismissActiveDeletedPrompt()
        assertFalse(viewModel.uiState.value.showActiveHrmDeletedPrompt)
    }

    @Test
    fun `active session lockout blocks promoting or forgetting Active HRM`() = runTest(testDispatcher) {
        val fakeDao = FakeRememberedHrmDao(listOf(activeStrap, inactiveArmband))
        val sessionActiveFlow = MutableStateFlow(true)
        val disconnectedMacs = mutableListOf<String>()
        val viewModel = RememberedHrmsViewModel(
            rememberedHrmDao = fakeDao,
            isSessionActiveFlow = sessionActiveFlow,
            onDisconnectActiveHrm = { mac -> disconnectedMacs.add(mac) }
        )
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isSessionActive)

        viewModel.promoteToActive(inactiveArmband.macAddress)
        advanceUntilIdle()
        assertEquals(activeStrap.macAddress, fakeDao.getActiveHrm()?.macAddress)
        assertNotNull(viewModel.uiState.value.errorMessage)

        viewModel.requestForgetDevice(activeStrap)
        assertNull(viewModel.uiState.value.forgetConfirmationTarget)
        assertTrue(disconnectedMacs.isEmpty())
    }

    @Test
    fun `database or callback failures surface errorMessage without crashing`() = runTest(testDispatcher) {
        val fakeDao = FakeRememberedHrmDao(listOf(activeStrap, inactiveArmband))
        val failingDao = object : RememberedHrmDao by fakeDao {
            override suspend fun promoteToActiveHrm(macAddress: String) {
                throw IllegalStateException("Promote failed")
            }

            override suspend fun updateNickname(macAddress: String, customNickname: String?): Int {
                throw IllegalStateException("Nickname failed")
            }

            override suspend fun deleteHrmByMacAddress(macAddress: String): Int {
                throw IllegalStateException("Delete failed")
            }
        }
        val viewModel = RememberedHrmsViewModel(rememberedHrmDao = failingDao)
        advanceUntilIdle()

        viewModel.promoteToActive(inactiveArmband.macAddress)
        advanceUntilIdle()
        assertEquals("Promote failed", viewModel.uiState.value.errorMessage)

        viewModel.startEditNickname(inactiveArmband)
        viewModel.onNicknameDraftChange("Test")
        viewModel.confirmEditNickname()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.editingNicknameTarget)
        assertEquals("Nickname failed", viewModel.uiState.value.errorMessage)

        viewModel.requestForgetDevice(inactiveArmband)
        viewModel.confirmForgetDevice()
        advanceUntilIdle()
        assertEquals("Delete failed", viewModel.uiState.value.errorMessage)
    }
}
