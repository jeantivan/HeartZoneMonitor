package com.example.heartzonemonitor.data.local

import android.content.Context
import com.example.heartzonemonitor.data.local.dao.RememberedHrmDao
import com.example.heartzonemonitor.data.local.entity.RememberedHrm
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RememberedHrmDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: RememberedHrmDao
    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        db = AppDatabase.buildInMemory(context)
        dao = db.rememberedHrmDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun rememberedHrmsTable_isInitializedCompletelyEmpty() = runTest {
        assertEquals(0, dao.getActiveHrmCount())
        assertTrue(dao.getAllHrms().isEmpty())
        assertNull(dao.getActiveHrm())
        assertNull(dao.observeActiveHrm().first())
        assertTrue(dao.observeAllHrms().first().isEmpty())
    }

    @Test
    fun upsertHrm_persistsSensorWithNullableFields_andRetrievesByMacAddress() = runTest {
        val sensor = RememberedHrm(
            macAddress = "AA:BB:CC:11:22:33",
            advertisedName = "COOSPO H808S-01928",
            customNickname = null,
            lastSeenTimestamp = 1700000001000L,
            batteryLevel = null,
            isActive = true
        )

        dao.upsertHrm(sensor)

        val retrieved = dao.getHrmByMacAddress("AA:BB:CC:11:22:33")
        assertNotNull(retrieved)
        assertEquals("AA:BB:CC:11:22:33", retrieved?.macAddress)
        assertEquals("COOSPO H808S-01928", retrieved?.advertisedName)
        assertNull(retrieved?.customNickname)
        assertEquals(1700000001000L, retrieved?.lastSeenTimestamp)
        assertNull(retrieved?.batteryLevel)
        assertTrue(retrieved?.isActive == true)
        assertEquals("COOSPO H808S-01928", retrieved?.displayName())
    }

    @Test
    fun upsertHrm_persistsSensorWithCustomNicknameAndBatteryLevel() = runTest {
        val sensor = RememberedHrm(
            macAddress = "AA:BB:CC:44:55:66",
            advertisedName = "Polar H10 99A1",
            customNickname = "Race Chest Strap",
            lastSeenTimestamp = 1700000005000L,
            batteryLevel = 88,
            isActive = false
        )

        dao.upsertHrm(sensor)

        val retrieved = dao.getHrmByMacAddress("AA:BB:CC:44:55:66")
        assertNotNull(retrieved)
        assertEquals("Race Chest Strap", retrieved?.customNickname)
        assertEquals(88, retrieved?.batteryLevel)
        assertFalse(retrieved?.isActive == true)
        assertEquals("Race Chest Strap", retrieved?.displayName())
    }

    @Test
    fun rememberedHrmEntity_validatesNonBlankMacAddress_andBatteryLevelBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            RememberedHrmEntity(
                macAddress = "   ",
                advertisedName = "COOSPO H808S",
                lastSeenTimestamp = 1000L
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            RememberedHrmEntity(
                macAddress = "AA:BB:CC:00:00:01",
                advertisedName = "COOSPO H808S",
                lastSeenTimestamp = 1000L,
                batteryLevel = -1
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            RememberedHrmEntity(
                macAddress = "AA:BB:CC:00:00:01",
                advertisedName = "COOSPO H808S",
                lastSeenTimestamp = 1000L,
                batteryLevel = 101
            )
        }
    }

    @Test
    fun upsertHrm_withActiveFlag_enforcesSingleActiveHrmInvariant() = runTest {
        val sensorA = RememberedHrm(
            macAddress = "AA:BB:CC:00:00:01",
            advertisedName = "COOSPO H808S-1",
            lastSeenTimestamp = 1000L,
            isActive = true
        )
        val sensorB = RememberedHrm(
            macAddress = "AA:BB:CC:00:00:02",
            advertisedName = "COOSPO H808S-2",
            lastSeenTimestamp = 2000L,
            isActive = true
        )
        val sensorC = RememberedHrm(
            macAddress = "AA:BB:CC:00:00:03",
            advertisedName = "Garmin HRM-Pro",
            lastSeenTimestamp = 3000L,
            isActive = false
        )

        dao.upsertHrm(sensorA)
        assertEquals(1, dao.getActiveHrmCount())
        assertEquals(sensorA.macAddress, dao.getActiveHrm()?.macAddress)

        // Upserting Sensor B as active must demote Sensor A automatically
        dao.upsertHrm(sensorB)
        assertEquals(1, dao.getActiveHrmCount())
        assertEquals(sensorB.macAddress, dao.getActiveHrm()?.macAddress)
        assertFalse(dao.getHrmByMacAddress(sensorA.macAddress)?.isActive == true)
        assertTrue(dao.getHrmByMacAddress(sensorB.macAddress)?.isActive == true)

        // Upserting Sensor C as inactive must keep Sensor B as the single Active HRM
        dao.upsertHrm(sensorC)
        assertEquals(1, dao.getActiveHrmCount())
        assertEquals(sensorB.macAddress, dao.getActiveHrm()?.macAddress)
        assertFalse(dao.getHrmByMacAddress(sensorC.macAddress)?.isActive == true)
    }

    @Test
    fun promoteToActiveHrm_switchesActiveSensor_inAtomicTransaction() = runTest {
        val sensorA = RememberedHrm(
            macAddress = "AA:BB:CC:00:00:01",
            advertisedName = "COOSPO H808S",
            lastSeenTimestamp = 1000L,
            isActive = true
        )
        val sensorB = RememberedHrm(
            macAddress = "AA:BB:CC:00:00:02",
            advertisedName = "Polar H10",
            lastSeenTimestamp = 2000L,
            isActive = false
        )
        val sensorC = RememberedHrm(
            macAddress = "AA:BB:CC:00:00:03",
            advertisedName = "Wahoo TICKR",
            lastSeenTimestamp = 3000L,
            isActive = false
        )

        dao.upsertHrm(sensorA)
        dao.upsertHrm(sensorB)
        dao.upsertHrm(sensorC)
        assertEquals(1, dao.getActiveHrmCount())
        assertEquals(sensorA.macAddress, dao.getActiveHrm()?.macAddress)

        // Promote Sensor B
        dao.promoteToActiveHrm(sensorB.macAddress)
        assertEquals(1, dao.getActiveHrmCount())
        assertEquals(sensorB.macAddress, dao.getActiveHrm()?.macAddress)
        assertFalse(dao.getHrmByMacAddress(sensorA.macAddress)?.isActive == true)
        assertTrue(dao.getHrmByMacAddress(sensorB.macAddress)?.isActive == true)
        assertFalse(dao.getHrmByMacAddress(sensorC.macAddress)?.isActive == true)

        // Promote Sensor C via setActiveHrm alias
        dao.setActiveHrm(sensorC.macAddress)
        assertEquals(1, dao.getActiveHrmCount())
        assertEquals(sensorC.macAddress, dao.getActiveHrm()?.macAddress)
        assertFalse(dao.getHrmByMacAddress(sensorA.macAddress)?.isActive == true)
        assertFalse(dao.getHrmByMacAddress(sensorB.macAddress)?.isActive == true)
        assertTrue(dao.getHrmByMacAddress(sensorC.macAddress)?.isActive == true)
    }

    @Test
    fun promoteToActiveHrm_rollsBackTransactionAtomically_whenTargetSensorDoesNotExist() = runTest {
        val sensorA = RememberedHrm(
            macAddress = "AA:BB:CC:00:00:01",
            advertisedName = "COOSPO H808S",
            lastSeenTimestamp = 1000L,
            isActive = true
        )
        val sensorB = RememberedHrm(
            macAddress = "AA:BB:CC:00:00:02",
            advertisedName = "Polar H10",
            lastSeenTimestamp = 2000L,
            isActive = false
        )

        dao.upsertHrm(sensorA)
        dao.upsertHrm(sensorB)
        assertEquals(1, dao.getActiveHrmCount())

        // Attempting to promote a non-existent MAC must fail after clearActiveHrms() and roll back
        try {
            dao.promoteToActiveHrm("FF:FF:FF:FF:FF:FF")
            org.junit.Assert.fail("Expected IllegalArgumentException for unknown MAC address")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("FF:FF:FF:FF:FF:FF") == true)
        }

        // Transaction rollback ensures Sensor A remains the single Active HRM
        assertEquals(1, dao.getActiveHrmCount())
        val activeHrm = dao.getActiveHrm()
        assertNotNull(activeHrm)
        assertEquals(sensorA.macAddress, activeHrm?.macAddress)
        assertTrue(activeHrm?.isActive == true)
    }

    @Test
    fun observeActiveHrm_emitsAtomicTransition_withoutIntermediateNullState() = runTest {
        val emissions = Channel<RememberedHrm?>(Channel.UNLIMITED)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            dao.observeActiveHrm().distinctUntilChanged().collect { emissions.send(it) }
        }

        // 1. Initial emission on empty database
        assertNull(emissions.receive())

        // 2. Pair first active sensor
        val sensorA = RememberedHrm(
            macAddress = "AA:BB:CC:00:00:01",
            advertisedName = "COOSPO H808S",
            lastSeenTimestamp = 1000L,
            isActive = true
        )
        dao.upsertHrm(sensorA)
        assertEquals(sensorA, emissions.receive())

        // 3. Add inactive second sensor
        val sensorB = RememberedHrm(
            macAddress = "AA:BB:CC:00:00:02",
            advertisedName = "Polar H10",
            lastSeenTimestamp = 2000L,
            isActive = false
        )
        dao.upsertHrm(sensorB)

        // 4. Atomically promote Sensor B -> emission transitions directly from Sensor A to Sensor B
        dao.promoteToActiveHrm(sensorB.macAddress)
        val switchedEmission = emissions.receive()
        assertNotNull(switchedEmission)
        assertEquals(sensorB.macAddress, switchedEmission?.macAddress)
        assertTrue(switchedEmission?.isActive == true)

        job.cancel()
    }

    @Test
    fun observeAllHrms_maintainsSingleActiveHrmInvariant_andOrdersActiveFirst() = runTest {
        val emissions = Channel<List<RememberedHrm>>(Channel.UNLIMITED)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            dao.observeAllHrms().collect { emissions.send(it) }
        }

        // 1. Initial empty emission
        assertTrue(emissions.receive().isEmpty())

        val sensorA = RememberedHrm(
            macAddress = "AA:BB:CC:00:00:01",
            advertisedName = "COOSPO H808S",
            lastSeenTimestamp = 1000L,
            isActive = true
        )
        val sensorB = RememberedHrm(
            macAddress = "AA:BB:CC:00:00:02",
            advertisedName = "Polar H10",
            lastSeenTimestamp = 5000L,
            isActive = false
        )

        dao.upsertHrm(sensorA)
        val listAfterA = emissions.receive()
        assertEquals(1, listAfterA.size)
        assertEquals(1, listAfterA.count { it.isActive })

        dao.upsertHrm(sensorB)
        val listAfterB = emissions.receive()
        assertEquals(2, listAfterB.size)
        assertEquals(1, listAfterB.count { it.isActive })
        // Sensor A is active so it appears first despite Sensor B having a newer lastSeenTimestamp
        assertEquals(sensorA.macAddress, listAfterB[0].macAddress)
        assertEquals(sensorB.macAddress, listAfterB[1].macAddress)

        // Switch active sensor to Sensor B
        dao.promoteToActiveHrm(sensorB.macAddress)
        val listAfterSwitch = emissions.receive()
        assertEquals(2, listAfterSwitch.size)
        assertEquals(1, listAfterSwitch.count { it.isActive })
        assertEquals(sensorB.macAddress, listAfterSwitch[0].macAddress)
        assertTrue(listAfterSwitch[0].isActive)
        assertEquals(sensorA.macAddress, listAfterSwitch[1].macAddress)
        assertFalse(listAfterSwitch[1].isActive)

        job.cancel()
    }

    @Test
    fun updateNickname_updateBatteryLevel_andUpdateLastSeenTimestamp_modifyTargetSensorOnly() = runTest {
        val sensorA = RememberedHrm(
            macAddress = "AA:BB:CC:00:00:01",
            advertisedName = "COOSPO H808S",
            customNickname = null,
            lastSeenTimestamp = 1000L,
            batteryLevel = null,
            isActive = true
        )
        val sensorB = RememberedHrm(
            macAddress = "AA:BB:CC:00:00:02",
            advertisedName = "Polar H10",
            customNickname = "Backup Strap",
            lastSeenTimestamp = 2000L,
            batteryLevel = 95,
            isActive = false
        )

        dao.upsertHrm(sensorA)
        dao.upsertHrm(sensorB)

        // Update nickname, battery level, and lastSeenTimestamp for Sensor A
        assertEquals(1, dao.updateNickname(sensorA.macAddress, "Primary Chest Strap"))
        assertEquals(1, dao.updateBatteryLevel(sensorA.macAddress, 74))
        assertEquals(1, dao.updateLastSeenTimestamp(sensorA.macAddress, 9000L))

        val updatedA = dao.getHrmByMacAddress(sensorA.macAddress)
        assertEquals("Primary Chest Strap", updatedA?.customNickname)
        assertEquals(74, updatedA?.batteryLevel)
        assertEquals(9000L, updatedA?.lastSeenTimestamp)
        assertTrue(updatedA?.isActive == true)

        // Clear nickname back to null
        assertEquals(1, dao.updateNickname(sensorA.macAddress, null))
        assertNull(dao.getHrmByMacAddress(sensorA.macAddress)?.customNickname)

        // Verify Sensor B remained untouched
        val untouchedB = dao.getHrmByMacAddress(sensorB.macAddress)
        assertEquals("Backup Strap", untouchedB?.customNickname)
        assertEquals(95, untouchedB?.batteryLevel)
        assertEquals(2000L, untouchedB?.lastSeenTimestamp)
        assertFalse(untouchedB?.isActive == true)
    }

    @Test
    fun deleteHrm_removesSensor_andClearsActiveHrmWhenActiveSensorDeleted() = runTest {
        val sensorA = RememberedHrm(
            macAddress = "AA:BB:CC:00:00:01",
            advertisedName = "COOSPO H808S",
            lastSeenTimestamp = 1000L,
            isActive = true
        )
        val sensorB = RememberedHrm(
            macAddress = "AA:BB:CC:00:00:02",
            advertisedName = "Polar H10",
            lastSeenTimestamp = 2000L,
            isActive = false
        )

        dao.upsertHrm(sensorA)
        dao.upsertHrm(sensorB)
        assertEquals(2, dao.getAllHrms().size)

        // Delete active sensor by MAC address
        val deletedRows = dao.deleteHrm(sensorA.macAddress)
        assertEquals(1, deletedRows)
        assertNull(dao.getActiveHrm())
        assertEquals(0, dao.getActiveHrmCount())
        assertEquals(1, dao.getAllHrms().size)
        assertEquals(sensorB.macAddress, dao.getAllHrms().single().macAddress)

        // Delete remaining sensor by entity reference
        val deletedEntityRows = dao.deleteHrm(sensorB)
        assertEquals(1, deletedEntityRows)
        assertTrue(dao.getAllHrms().isEmpty())
    }

    @Test
    fun database_enforcesSingleActiveHrmInvariant_viaTriggersOnDirectSqlWrites() = runTest {
        val writableDb = db.openHelper.writableDatabase

        // Insert Sensor 1 with isActive = 1 via direct SQL
        writableDb.execSQL(
            """
            INSERT INTO remembered_hrms (macAddress, advertisedName, customNickname, lastSeenTimestamp, batteryLevel, isActive)
            VALUES ('AA:BB:CC:00:00:01', 'Sensor 1', NULL, 1000, 90, 1)
            """.trimIndent()
        )
        assertEquals(1, dao.getActiveHrmCount())
        assertEquals("AA:BB:CC:00:00:01", dao.getActiveHrm()?.macAddress)

        // Insert Sensor 2 with isActive = 1 via direct SQL -> trigger must demote Sensor 1
        writableDb.execSQL(
            """
            INSERT INTO remembered_hrms (macAddress, advertisedName, customNickname, lastSeenTimestamp, batteryLevel, isActive)
            VALUES ('AA:BB:CC:00:00:02', 'Sensor 2', NULL, 2000, 85, 1)
            """.trimIndent()
        )
        assertEquals(1, dao.getActiveHrmCount())
        assertEquals("AA:BB:CC:00:00:02", dao.getActiveHrm()?.macAddress)
        assertFalse(dao.getHrmByMacAddress("AA:BB:CC:00:00:01")?.isActive == true)

        // Update Sensor 1 to isActive = 1 via direct SQL -> trigger must demote Sensor 2
        writableDb.execSQL(
            "UPDATE remembered_hrms SET isActive = 1 WHERE macAddress = 'AA:BB:CC:00:00:01'"
        )
        assertEquals(1, dao.getActiveHrmCount())
        assertEquals("AA:BB:CC:00:00:01", dao.getActiveHrm()?.macAddress)
        assertFalse(dao.getHrmByMacAddress("AA:BB:CC:00:00:02")?.isActive == true)
    }
}
