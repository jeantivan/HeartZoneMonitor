package com.example.heartzonemonitor.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.heartzonemonitor.data.local.dao.UserProfileDao
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import net.sqlcipher.database.SupportFactory

@Database(
    entities = [UserProfileEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userProfileDao(): UserProfileDao

    companion object {
        const val DATABASE_NAME = "heart_zone_monitor.db"

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
                .addCallback(createDatabaseCallback(runIntegrityCheck = false))
                .build()
        }

        private fun createDatabaseCallback(runIntegrityCheck: Boolean): RoomDatabase.Callback {
            return object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.execSQL("PRAGMA foreign_keys = ON;")

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
    }
}
