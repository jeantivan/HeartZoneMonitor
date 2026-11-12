package com.example.heartzonemonitor.data.local

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
class AppDatabaseTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        db = AppDatabase.buildInMemory(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun database_initializes_andEnforcesForeignKeysPragma() {
        val cursor = db.openHelper.readableDatabase.query("PRAGMA foreign_keys;")
        assertTrue(cursor.moveToFirst())
        val foreignKeys = cursor.getInt(0)
        assertEquals(1, foreignKeys)
        cursor.close()
    }

    @Test
    fun database_persistsAndRetrieves_userProfileEntity() = runBlocking {
        val dao = db.userProfileDao()
        assertEquals(0, dao.getProfileCount())

        val profile = UserProfileEntity(
            id = UserProfileEntity.SINGLETON_ID,
            birthYear = 1990,
            restingHr = 55,
            customHrmax = 185,
            calculationMode = ZoneCalculationMode.KARVONEN,
            updatedAtEpochMs = 1700000000000L
        )

        dao.upsertProfile(profile)
        assertEquals(1, dao.getProfileCount())

        val retrieved = dao.getProfile()
        assertNotNull(retrieved)
        assertEquals(1990, retrieved?.birthYear)
        assertEquals(55, retrieved?.restingHr)
        assertEquals(185, retrieved?.customHrmax)
        assertEquals(ZoneCalculationMode.KARVONEN, retrieved?.calculationMode)
    }
}
