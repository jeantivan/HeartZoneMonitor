package com.example.heartzonemonitor.data.local

import android.content.Context
import com.example.heartzonemonitor.data.local.dao.UserProfileDao
import com.example.heartzonemonitor.data.local.entity.UserProfile
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UserProfileDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: UserProfileDao
    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        db = AppDatabase.buildInMemory(context)
        dao = db.userProfileDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun userProfileTable_isInitializedCompletelyEmpty() = runTest {
        assertEquals(0, dao.getProfileCount())
        assertNull(dao.getProfile())
        val initialFlowValue = dao.getProfileFlow().first()
        assertNull(initialFlowValue)
    }

    @Test
    fun upsertProfile_persistsData_withNullRestingHrAndNullCustomHrmax() = runTest {
        val profile = UserProfile(
            id = UserProfileEntity.SINGLETON_ID,
            birthYear = 1995,
            restingHr = null,
            customHrmax = null,
            calculationMode = ZoneCalculationMode.PERCENT_HRMAX,
            updatedAtEpochMs = 1700000001000L
        )

        dao.upsertProfile(profile)

        assertEquals(1, dao.getProfileCount())
        val retrieved = dao.getProfile()
        assertNotNull(retrieved)
        assertEquals(1, retrieved?.id)
        assertEquals(1995, retrieved?.birthYear)
        assertNull(retrieved?.restingHr)
        assertNull(retrieved?.customHrmax)
        assertEquals(ZoneCalculationMode.PERCENT_HRMAX, retrieved?.calculationMode)
        assertEquals(1700000001000L, retrieved?.updatedAtEpochMs)
    }

    @Test
    fun upsertProfile_persistsData_withRestingHrAndCustomHrmax() = runTest {
        val profile = UserProfile(
            id = UserProfileEntity.SINGLETON_ID,
            birthYear = 1988,
            restingHr = 52,
            customHrmax = 192,
            calculationMode = ZoneCalculationMode.KARVONEN,
            updatedAtEpochMs = 1700000002000L
        )

        dao.upsertProfile(profile)

        val retrieved = dao.getProfile()
        assertNotNull(retrieved)
        assertEquals(52, retrieved?.restingHr)
        assertEquals(192, retrieved?.customHrmax)
        assertEquals(ZoneCalculationMode.KARVONEN, retrieved?.calculationMode)
    }

    @Test
    fun upsertProfile_enforcesSingletonCount_whenUpdatingExistingRecord() = runTest {
        val initialProfile = UserProfile(
            birthYear = 1990,
            restingHr = 60,
            customHrmax = null,
            calculationMode = ZoneCalculationMode.KARVONEN,
            updatedAtEpochMs = 1000L
        )
        dao.upsertProfile(initialProfile)
        assertEquals(1, dao.getProfileCount())

        val updatedProfile = UserProfile(
            birthYear = 1990,
            restingHr = 56,
            customHrmax = 186,
            calculationMode = ZoneCalculationMode.KARVONEN,
            updatedAtEpochMs = 2000L
        )
        dao.upsertProfile(updatedProfile)

        assertEquals(1, dao.getProfileCount())
        val retrieved = dao.getProfile()
        assertNotNull(retrieved)
        assertEquals(56, retrieved?.restingHr)
        assertEquals(186, retrieved?.customHrmax)
        assertEquals(2000L, retrieved?.updatedAtEpochMs)
    }

    @Test
    fun userProfileEntity_throwsIllegalArgumentException_whenIdIsNotSingleton() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            UserProfileEntity(
                id = 2,
                birthYear = 1990,
                restingHr = null,
                customHrmax = null,
                calculationMode = ZoneCalculationMode.PERCENT_HRMAX,
                updatedAtEpochMs = 1000L
            )
        }
        assertTrue(exception.message?.contains("ID must be 1") == true)
    }

    @Test
    fun database_enforcesSingletonConstraint_viaTriggerOnDirectSqlInsert() {
        val writableDb = db.openHelper.writableDatabase

        try {
            writableDb.execSQL(
                "INSERT INTO user_profile (id, birthYear, restingHr, customHrmax, calculationMode, updatedAtEpochMs) " +
                    "VALUES (2, 1990, NULL, NULL, 'PERCENT_HRMAX', 1000)"
            )
            fail("Expected SQLiteException when inserting row with id != 1")
        } catch (e: Exception) {
            assertTrue(
                "Expected constraint failure message but got: ${e.message}",
                e.message?.contains("singleton constraint", ignoreCase = true) == true ||
                    e.message?.contains("abort", ignoreCase = true) == true
            )
        }
    }

    @Test
    fun database_enforcesSingletonConstraint_viaTriggerOnDirectSqlUpdate() = runTest {
        val initialProfile = UserProfile(
            birthYear = 1990,
            restingHr = null,
            customHrmax = null,
            calculationMode = ZoneCalculationMode.PERCENT_HRMAX,
            updatedAtEpochMs = 1000L
        )
        dao.upsertProfile(initialProfile)

        val writableDb = db.openHelper.writableDatabase
        try {
            writableDb.execSQL("UPDATE user_profile SET id = 99 WHERE id = 1")
            fail("Expected SQLiteException when updating row id != 1")
        } catch (e: Exception) {
            assertTrue(
                "Expected constraint failure message but got: ${e.message}",
                e.message?.contains("singleton constraint", ignoreCase = true) == true ||
                    e.message?.contains("abort", ignoreCase = true) == true
            )
        }
    }

    @Test
    fun getProfileFlow_emitsInitialState_andReactsToUpsertAndDelete() = runTest {
        // Initial emission should be null on empty database
        val initial = dao.getProfileFlow().first()
        assertNull(initial)

        // Upsert profile and verify reactive emission
        val profile = UserProfile(
            birthYear = 1992,
            restingHr = 58,
            customHrmax = null,
            calculationMode = ZoneCalculationMode.KARVONEN,
            updatedAtEpochMs = 3000L
        )
        dao.upsertProfile(profile)

        val updated = dao.getProfileFlow().first()
        assertEquals(profile, updated)

        // Delete profile and verify emission returns to null
        dao.deleteProfile()

        val afterDelete = dao.getProfileFlow().first()
        assertNull(afterDelete)
    }

    @Test
    fun getProfileFlow_reactsToContinuousSubscriptionEmissions() = runTest {
        val channel = Channel<UserProfileEntity?>(Channel.UNLIMITED)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            dao.getProfileFlow().collect { channel.send(it) }
        }

        // 1. Initial emission on empty database
        assertNull(channel.receive())

        // 2. Upsert profile emits new record
        val profile = UserProfile(
            birthYear = 1991,
            restingHr = 62,
            customHrmax = 188,
            calculationMode = ZoneCalculationMode.KARVONEN,
            updatedAtEpochMs = 4000L
        )
        dao.upsertProfile(profile)
        assertEquals(profile, channel.receive())

        // 3. Delete profile emits null
        dao.deleteProfile()
        assertNull(channel.receive())

        job.cancel()
    }
}
