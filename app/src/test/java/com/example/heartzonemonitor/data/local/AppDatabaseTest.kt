package com.example.heartzonemonitor.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.example.heartzonemonitor.data.local.entity.HeartRateSampleEntity
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.SessionEntity
import com.example.heartzonemonitor.data.local.entity.SessionStatus
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
import java.util.UUID

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

    @Test
    fun database_migration2To3_validatesRoomSchemaAndPreservesExistingData() = runBlocking {
        val dbName = "migration_v2_to_v3_${UUID.randomUUID()}.db"
        context.deleteDatabase(dbName)

        // Create a real v2 SQLite database file on disk with sample user_profile & remembered_hrms rows
        val v2Config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
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
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `remembered_hrms` (
                            `macAddress` TEXT NOT NULL,
                            `advertisedName` TEXT NOT NULL,
                            `customNickname` TEXT,
                            `lastSeenTimestamp` INTEGER NOT NULL,
                            `batteryLevel` INTEGER,
                            `isActive` INTEGER NOT NULL,
                            PRIMARY KEY(`macAddress`)
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val v2Helper = FrameworkSQLiteOpenHelperFactory().create(v2Config)
        try {
            val v2Db = v2Helper.writableDatabase
            v2Db.execSQL(
                """
                INSERT INTO user_profile (id, birthYear, restingHr, customHrmax, calculationMode, updatedAtEpochMs)
                VALUES (1, 1990, 54, 188, 'KARVONEN', 1700000000000)
                """.trimIndent()
            )
            v2Db.execSQL(
                """
                INSERT INTO remembered_hrms (macAddress, advertisedName, customNickname, lastSeenTimestamp, batteryLevel, isActive)
                VALUES ('AA:BB:CC:11:22:33', 'COOSPO H808S', 'Chest Strap', 1700000001000, 92, 1)
                """.trimIndent()
            )
        } finally {
            v2Helper.close()
        }

        // Re-open the v2 file through Room so MIGRATION_2_3 and full TableInfo schema validation run
        var migratedDb: AppDatabase? = null
        try {
            migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
                .allowMainThreadQueries()
                .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
                .build()

            // Trigger database open & Room TableInfo verification
            val preservedProfile = migratedDb.userProfileDao().getProfile()
            assertNotNull(preservedProfile)
            assertEquals(1990, preservedProfile?.birthYear)
            assertEquals(54, preservedProfile?.restingHr)
            assertEquals(188, preservedProfile?.customHrmax)
            assertEquals(ZoneCalculationMode.KARVONEN, preservedProfile?.calculationMode)

            val preservedHrms = migratedDb.rememberedHrmDao().getAllHrms()
            assertEquals(1, preservedHrms.size)
            assertEquals("AA:BB:CC:11:22:33", preservedHrms.single().macAddress)
            assertEquals("Chest Strap", preservedHrms.single().customNickname)
            assertTrue(preservedHrms.single().isActive)

            // Verify new v3 tables, foreign key cascade, and composite index work on migrated DB
            val session = SessionEntity(
                id = "migrated-session-1",
                startEpochMs = 1_700_000_010_000L,
                endEpochMs = 1_700_000_070_000L,
                elapsedDurationMs = 60_000L,
                effectiveDurationMs = 60_000L,
                pausedDurationMs = 0L,
                status = SessionStatus.COMPLETED,
                hrmMacAddress = "AA:BB:CC:11:22:33",
                userAge = 36,
                calculatedHrmax = 188,
                restingHr = 54,
                calculationMode = ZoneCalculationMode.KARVONEN,
                avgBpm = 142,
                maxBpm = 165,
                minBpm = 110,
                zone2DurationMs = 30_000L,
                zone3DurationMs = 30_000L
            )
            migratedDb.sessionDao().insertSession(session)

            val samples = listOf(
                HeartRateSampleEntity(
                    sessionId = session.id,
                    timestampEpochMs = 1_700_000_011_000L,
                    bpm = 132,
                    zoneIndex = 2,
                    sensorContact = true
                ),
                HeartRateSampleEntity(
                    sessionId = session.id,
                    timestampEpochMs = 1_700_000_012_000L,
                    bpm = 148,
                    zoneIndex = 3,
                    sensorContact = true
                )
            )
            migratedDb.heartRateSampleDao().insertSamplesBatch(samples)
            assertEquals(2, migratedDb.heartRateSampleDao().getSampleCountForSession(session.id))

            // Verify composite index query plan on migrated DB
            val planDetails = mutableListOf<String>()
            migratedDb.openHelper.readableDatabase.query(
                "EXPLAIN QUERY PLAN SELECT * FROM heart_rate_samples WHERE sessionId = ? ORDER BY timestampEpochMs ASC",
                arrayOf(session.id)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    planDetails.add(cursor.getString(cursor.columnCount - 1))
                }
            }
            assertTrue(
                planDetails.joinToString().contains("USING INDEX idx_heart_rate_samples_session_ts")
            )

            // Verify ON DELETE CASCADE works on migrated DB
            assertEquals(1, migratedDb.sessionDao().deleteSessionById(session.id))
            assertEquals(0, migratedDb.heartRateSampleDao().getSampleCountForSession(session.id))
        } finally {
            migratedDb?.close()
            context.deleteDatabase(dbName)
        }
    }
}
