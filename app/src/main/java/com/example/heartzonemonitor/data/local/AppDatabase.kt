package com.example.heartzonemonitor.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.heartzonemonitor.data.local.dao.HeartRateSampleDao
import com.example.heartzonemonitor.data.local.dao.RememberedHrmDao
import com.example.heartzonemonitor.data.local.dao.SessionDao
import com.example.heartzonemonitor.data.local.dao.UserProfileDao
import com.example.heartzonemonitor.data.local.entity.HeartRateSampleEntity
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.SessionEntity
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import net.sqlcipher.database.SupportFactory

@Database(
    entities = [
        UserProfileEntity::class,
        RememberedHrmEntity::class,
        SessionEntity::class,
        HeartRateSampleEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userProfileDao(): UserProfileDao

    abstract fun rememberedHrmDao(): RememberedHrmDao

    abstract fun sessionDao(): SessionDao

    abstract fun heartRateSampleDao(): HeartRateSampleDao

    companion object {
        const val DATABASE_NAME = "heart_zone_monitor.db"

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
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
                createRememberedHrmSingleActiveTriggers(db)
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sessions` (
                        `id` TEXT NOT NULL,
                        `startEpochMs` INTEGER NOT NULL,
                        `endEpochMs` INTEGER,
                        `elapsedDurationMs` INTEGER NOT NULL,
                        `effectiveDurationMs` INTEGER NOT NULL,
                        `pausedDurationMs` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `hrmMacAddress` TEXT NOT NULL,
                        `userAge` INTEGER NOT NULL,
                        `calculatedHrmax` INTEGER NOT NULL,
                        `restingHr` INTEGER,
                        `calculationMode` TEXT NOT NULL,
                        `avgBpm` INTEGER NOT NULL,
                        `maxBpm` INTEGER NOT NULL,
                        `minBpm` INTEGER NOT NULL,
                        `zone1DurationMs` INTEGER NOT NULL,
                        `zone2DurationMs` INTEGER NOT NULL,
                        `zone3DurationMs` INTEGER NOT NULL,
                        `zone4DurationMs` INTEGER NOT NULL,
                        `zone5DurationMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `heart_rate_samples` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `timestampEpochMs` INTEGER NOT NULL,
                        `bpm` INTEGER NOT NULL,
                        `zoneIndex` INTEGER NOT NULL,
                        `sensorContact` INTEGER NOT NULL,
                        FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `idx_heart_rate_samples_session_ts`
                    ON `heart_rate_samples` (`sessionId` ASC, `timestampEpochMs` ASC)
                    """.trimIndent()
                )
            }
        }

        fun buildEncrypted(
            context: Context,
            passphrase: ByteArray,
            databaseName: String = DATABASE_NAME
        ): AppDatabase {
            val openHelperFactory = SupportFactory(passphrase)

            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                databaseName
            )
                .openHelperFactory(openHelperFactory)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .addCallback(createDatabaseCallback(runIntegrityCheck = true))
                // Note: fallbackToDestructiveMigration() is strictly forbidden per ADR-0001
                .build()
        }

        fun buildInMemory(context: Context): AppDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                AppDatabase::class.java
            )
                .allowMainThreadQueries()
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .addCallback(createDatabaseCallback(runIntegrityCheck = false))
                .build()
        }

        private fun createDatabaseCallback(runIntegrityCheck: Boolean): RoomDatabase.Callback {
            return object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    createUserProfileSingletonTriggers(db)
                    createRememberedHrmSingleActiveTriggers(db)
                }

                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.execSQL("PRAGMA foreign_keys = ON;")
                    createUserProfileSingletonTriggers(db)
                    createRememberedHrmSingleActiveTriggers(db)

                    if (runIntegrityCheck) {
                        try {
                            db.query("PRAGMA cipher_integrity_check;").use { cursor ->
                                if (!cursor.moveToFirst() || !cursor.getString(0).equals("ok", ignoreCase = true)) {
                                    val result = if (cursor.count > 0) cursor.getString(0) else "no output"
                                    throw IllegalStateException("SQLCipher integrity check failed: $result")
                                }
                            }
                        } catch (e: Exception) {
                            if (e is IllegalStateException) throw e
                            throw IllegalStateException("Failed to verify SQLCipher integrity", e)
                        }
                    }
                }
            }
        }

        private fun createUserProfileSingletonTriggers(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS user_profile_singleton_insert_trigger
                BEFORE INSERT ON user_profile
                FOR EACH ROW
                BEGIN
                    SELECT CASE
                        WHEN NEW.id != 1 THEN RAISE(ABORT, 'user_profile singleton constraint violated: id must be 1')
                    END;
                END;
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS user_profile_singleton_update_trigger
                BEFORE UPDATE ON user_profile
                FOR EACH ROW
                BEGIN
                    SELECT CASE
                        WHEN NEW.id != 1 THEN RAISE(ABORT, 'user_profile singleton constraint violated: id must be 1')
                    END;
                END;
                """.trimIndent()
            )
        }

        private fun createRememberedHrmSingleActiveTriggers(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS remembered_hrms_single_active_insert_trigger
                AFTER INSERT ON remembered_hrms
                FOR EACH ROW
                WHEN NEW.isActive = 1
                BEGIN
                    UPDATE remembered_hrms SET isActive = 0 WHERE macAddress != NEW.macAddress AND isActive = 1;
                END;
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS remembered_hrms_single_active_update_trigger
                AFTER UPDATE OF isActive ON remembered_hrms
                FOR EACH ROW
                WHEN NEW.isActive = 1
                BEGIN
                    UPDATE remembered_hrms SET isActive = 0 WHERE macAddress != NEW.macAddress AND isActive = 1;
                END;
                """.trimIndent()
            )
        }
    }
}
