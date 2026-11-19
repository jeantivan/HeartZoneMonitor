package com.example.heartzonemonitor.data.local

import android.content.Context
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

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

    @Test
    fun database_migration1To2_createsRememberedHrmsTableAndPreservesUserProfile() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `user_profile` (
                            `id` INTEGER NOT NULL,
                            `birthYear` INTEGER NOT NULL,
                            `restingHr` INTEGER,
                            `customHrmax` INTEGER,
                            `calculationMode` TEXT NOT NULL,
                            `updatedAtEpochMs` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val sqliteDb = helper.writableDatabase

        // Insert a v1 user_profile record before migration
        sqliteDb.execSQL(
            """
            INSERT INTO user_profile (id, birthYear, restingHr, customHrmax, calculationMode, updatedAtEpochMs)
            VALUES (1, 1988, 52, NULL, 'KARVONEN', 1700000000000)
            """.trimIndent()
        )

        // Execute explicit MIGRATION_1_2
        AppDatabase.MIGRATION_1_2.migrate(sqliteDb)

        // Verify user_profile row survived migration
        sqliteDb.query("SELECT birthYear, restingHr FROM user_profile WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1988, cursor.getInt(0))
            assertEquals(52, cursor.getInt(1))
        }

        // Verify remembered_hrms table and single-active triggers are functional
        sqliteDb.execSQL(
            """
            INSERT INTO remembered_hrms (macAddress, advertisedName, customNickname, lastSeenTimestamp, batteryLevel, isActive)
            VALUES ('AA:BB:CC:11:22:33', 'COOSPO H808S', 'Chest Strap', 1700000001000, 95, 1)
            """.trimIndent()
        )
        sqliteDb.execSQL(
            """
            INSERT INTO remembered_hrms (macAddress, advertisedName, customNickname, lastSeenTimestamp, batteryLevel, isActive)
            VALUES ('AA:BB:CC:44:55:66', 'Polar H10', NULL, 1700000002000, 80, 1)
            """.trimIndent()
        )

        sqliteDb.query("SELECT macAddress FROM remembered_hrms WHERE isActive = 1").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("AA:BB:CC:44:55:66", cursor.getString(0))
        }

        helper.close()
    }
}
