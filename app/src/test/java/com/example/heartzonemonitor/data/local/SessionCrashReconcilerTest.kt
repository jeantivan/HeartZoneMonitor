package com.example.heartzonemonitor.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.example.heartzonemonitor.data.local.entity.HeartRateSampleEntity
import com.example.heartzonemonitor.data.local.entity.SessionEntity
import com.example.heartzonemonitor.data.local.entity.SessionStatus
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import kotlinx.coroutines.test.runTest
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
class SessionCrashReconcilerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun reopenDatabase_afterAbruptCrashDuringRecording_reconcilesMetricsZonesAndStatus() = runTest {
        val dbName = uniqueDbName("crash_recording")
        val startEpochMs = 1_700_000_000_000L
        val sessionId = "session-crash-recording"

        val initialSession = createSession(
            id = sessionId,
            startEpochMs = startEpochMs,
            endEpochMs = null,
            elapsedDurationMs = 0L,
            effectiveDurationMs = 0L,
            pausedDurationMs = 0L,
            status = SessionStatus.RECORDING,
            hrmMacAddress = "AA:BB:CC:11:22:33",
            userAge = 35,
            calculatedHrmax = 184,
            restingHr = 54,
            calculationMode = ZoneCalculationMode.KARVONEN
        )

        val persistedSamples = listOf(
            // delta = 1_000ms (<= 3s) -> Z1 +1_000ms
            sample(sessionId, startEpochMs + 1_000L, bpm = 100, zoneIndex = 1),
            // delta = 3_000ms (exact 3s boundary) -> Z2 +3_000ms
            sample(sessionId, startEpochMs + 2_000L, bpm = 120, zoneIndex = 2),
            // delta = 0ms (duplicate timestamp) -> Z2 +0ms
            sample(sessionId, startEpochMs + 5_000L, bpm = 125, zoneIndex = 2),
            // delta = 3_001ms (> 3s gap boundary) -> Z3 +1_000ms
            sample(sessionId, startEpochMs + 5_000L, bpm = 140, zoneIndex = 3),
            // delta = 12_000ms (> 3s pause/disconnect gap) -> Z4 +1_000ms
            sample(sessionId, startEpochMs + 8_001L, bpm = 155, zoneIndex = 4),
            // delta = 2_000ms (<= 3s) -> Z4 +2_000ms
            sample(sessionId, startEpochMs + 20_001L, bpm = 160, zoneIndex = 4),
            // last sample -> Z5 +1_000ms
            sample(sessionId, startEpochMs + 22_001L, bpm = 178, zoneIndex = 5)
        )

        var firstOpen: AppDatabase? = null
        var reopened: AppDatabase? = null
        try {
            firstOpen = AppDatabase.buildPersistentForTesting(context, dbName)
            firstOpen.sessionDao().insertSession(initialSession)
            firstOpen.heartRateSampleDao().insertSamplesBatch(persistedSamples)
            // Simulate abrupt OS process death / battery depletion by closing without stopping
            firstOpen.close()
            firstOpen = null

            reopened = AppDatabase.buildPersistentForTesting(context, dbName)
            val reconciled = reopened.sessionDao().getSessionById(sessionId)
            assertNotNull(reconciled)
            reconciled!!

            assertEquals(SessionStatus.COMPLETED_UNEXPECTEDLY, reconciled.status)
            assertEquals(startEpochMs + 22_001L, reconciled.endEpochMs)
            // Mean of [100, 120, 125, 140, 155, 160, 178] = 978 / 7 = 139.714 -> 140
            assertEquals(140, reconciled.avgBpm)
            assertEquals(178, reconciled.maxBpm)
            assertEquals(100, reconciled.minBpm)

            assertEquals(1_000L, reconciled.zone1DurationMs)
            assertEquals(3_000L, reconciled.zone2DurationMs)
            assertEquals(1_000L, reconciled.zone3DurationMs)
            assertEquals(3_000L, reconciled.zone4DurationMs)
            assertEquals(1_000L, reconciled.zone5DurationMs)

            assertEquals(22_001L, reconciled.elapsedDurationMs)
            assertEquals(9_000L, reconciled.effectiveDurationMs)
            assertEquals(13_001L, reconciled.pausedDurationMs)
            assertEquals(
                reconciled.elapsedDurationMs,
                reconciled.effectiveDurationMs + reconciled.pausedDurationMs
            )

            // Frozen profile snapshot and persisted Heart Rate Samples remain completely intact
            assertEquals(initialSession.startEpochMs, reconciled.startEpochMs)
            assertEquals(initialSession.hrmMacAddress, reconciled.hrmMacAddress)
            assertEquals(initialSession.userAge, reconciled.userAge)
            assertEquals(initialSession.calculatedHrmax, reconciled.calculatedHrmax)
            assertEquals(initialSession.restingHr, reconciled.restingHr)
            assertEquals(initialSession.calculationMode, reconciled.calculationMode)
            assertEquals(
                persistedSamples.size,
                reopened.heartRateSampleDao().getSampleCountForSession(sessionId)
            )
        } finally {
            firstOpen?.close()
            reopened?.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun reopenDatabase_afterAbruptCrashDuringPausedSession_reconcilesAndPreservesPriorDurations() =
        runTest {
            val dbName = uniqueDbName("crash_paused")
            val startEpochMs = 1_700_001_000_000L
            val sessionId = "session-crash-paused"

            val pausedSession = createSession(
                id = sessionId,
                startEpochMs = startEpochMs,
                endEpochMs = null,
                elapsedDurationMs = 30_000L,
                effectiveDurationMs = 10_000L,
                pausedDurationMs = 20_000L,
                status = SessionStatus.PAUSED
            )

            val samples = listOf(
                sample(sessionId, startEpochMs + 1_000L, bpm = 128, zoneIndex = 2),
                sample(sessionId, startEpochMs + 2_000L, bpm = 132, zoneIndex = 2),
                sample(sessionId, startEpochMs + 3_000L, bpm = 148, zoneIndex = 3),
                sample(sessionId, startEpochMs + 4_000L, bpm = 152, zoneIndex = 3)
            )

            var firstOpen: AppDatabase? = null
            var reopened: AppDatabase? = null
            try {
                firstOpen = AppDatabase.buildPersistentForTesting(context, dbName)
                firstOpen.sessionDao().insertSession(pausedSession)
                firstOpen.heartRateSampleDao().insertSamplesBatch(samples)
                firstOpen.close()
                firstOpen = null

                reopened = AppDatabase.buildPersistentForTesting(context, dbName)
                val reconciled = reopened.sessionDao().getSessionById(sessionId)
                assertNotNull(reconciled)
                reconciled!!

                assertEquals(SessionStatus.COMPLETED_UNEXPECTEDLY, reconciled.status)
                assertEquals(startEpochMs + 4_000L, reconciled.endEpochMs)
                assertEquals(140, reconciled.avgBpm)
                assertEquals(152, reconciled.maxBpm)
                assertEquals(128, reconciled.minBpm)
                assertEquals(0L, reconciled.zone1DurationMs)
                assertEquals(2_000L, reconciled.zone2DurationMs)
                assertEquals(2_000L, reconciled.zone3DurationMs)
                assertEquals(0L, reconciled.zone4DurationMs)
                assertEquals(0L, reconciled.zone5DurationMs)

                // Existing persisted elapsed (30s) and effective (10s) exceed sample span (4s)
                assertEquals(30_000L, reconciled.elapsedDurationMs)
                assertEquals(10_000L, reconciled.effectiveDurationMs)
                assertEquals(20_000L, reconciled.pausedDurationMs)
                assertEquals(
                    reconciled.elapsedDurationMs,
                    reconciled.effectiveDurationMs + reconciled.pausedDurationMs
                )
            } finally {
                firstOpen?.close()
                reopened?.close()
                context.deleteDatabase(dbName)
            }
        }

    @Test
    fun reopenDatabase_leavesCompletedAlreadyUnexpectedAndDiscardedSessionsUntouched() = runTest {
        val dbName = uniqueDbName("untouched_terminal")
        val baseEpochMs = 1_700_002_000_000L

        val completedSession = createSession(
            id = "session-completed",
            startEpochMs = baseEpochMs,
            endEpochMs = baseEpochMs + 60_000L,
            elapsedDurationMs = 60_000L,
            effectiveDurationMs = 55_000L,
            pausedDurationMs = 5_000L,
            status = SessionStatus.COMPLETED,
            avgBpm = 144,
            maxBpm = 171,
            minBpm = 102,
            zone1DurationMs = 5_000L,
            zone2DurationMs = 20_000L,
            zone3DurationMs = 20_000L,
            zone4DurationMs = 10_000L,
            zone5DurationMs = 0L
        )
        val priorUnexpectedSession = createSession(
            id = "session-already-unexpected",
            startEpochMs = baseEpochMs + 100_000L,
            endEpochMs = baseEpochMs + 115_000L,
            elapsedDurationMs = 15_000L,
            effectiveDurationMs = 15_000L,
            pausedDurationMs = 0L,
            status = SessionStatus.COMPLETED_UNEXPECTEDLY,
            avgBpm = 136,
            maxBpm = 150,
            minBpm = 118,
            zone2DurationMs = 15_000L
        )
        val discardedSession = createSession(
            id = "session-discarded",
            startEpochMs = baseEpochMs + 200_000L,
            endEpochMs = baseEpochMs + 204_000L,
            elapsedDurationMs = 4_000L,
            effectiveDurationMs = 4_000L,
            pausedDurationMs = 0L,
            status = SessionStatus.DISCARDED,
            avgBpm = 0,
            maxBpm = 0,
            minBpm = 0
        )
        val activeCrashSession = createSession(
            id = "session-active-crash",
            startEpochMs = baseEpochMs + 300_000L,
            status = SessionStatus.RECORDING
        )

        var firstOpen: AppDatabase? = null
        var reopened: AppDatabase? = null
        try {
            firstOpen = AppDatabase.buildPersistentForTesting(context, dbName)
            firstOpen.sessionDao().insertSession(completedSession)
            firstOpen.sessionDao().insertSession(priorUnexpectedSession)
            firstOpen.sessionDao().insertSession(discardedSession)
            firstOpen.sessionDao().insertSession(activeCrashSession)
            firstOpen.heartRateSampleDao().insertSamplesBatch(
                listOf(
                    sample(completedSession.id, baseEpochMs + 1_000L, bpm = 199, zoneIndex = 5),
                    sample(activeCrashSession.id, baseEpochMs + 301_000L, bpm = 130, zoneIndex = 2)
                )
            )
            firstOpen.close()
            firstOpen = null

            reopened = AppDatabase.buildPersistentForTesting(context, dbName)
            assertEquals(completedSession, reopened.sessionDao().getSessionById(completedSession.id))
            assertEquals(
                priorUnexpectedSession,
                reopened.sessionDao().getSessionById(priorUnexpectedSession.id)
            )
            assertEquals(discardedSession, reopened.sessionDao().getSessionById(discardedSession.id))

            val reconciledCrash = reopened.sessionDao().getSessionById(activeCrashSession.id)
            assertNotNull(reconciledCrash)
            assertEquals(SessionStatus.COMPLETED_UNEXPECTEDLY, reconciledCrash?.status)
            assertEquals(baseEpochMs + 301_000L, reconciledCrash?.endEpochMs)
            assertEquals(130, reconciledCrash?.avgBpm)
        } finally {
            firstOpen?.close()
            reopened?.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun reopenDatabase_withZeroPersistedSamples_reconcilesWithZeroMetricsAndConsistentDurations() =
        runTest {
            val dbName = uniqueDbName("crash_zero_samples")
            val startEpochMs = 1_700_003_000_000L

            val emptyRecording = createSession(
                id = "session-empty-recording",
                startEpochMs = startEpochMs,
                endEpochMs = null,
                elapsedDurationMs = 0L,
                effectiveDurationMs = 0L,
                pausedDurationMs = 0L,
                status = SessionStatus.RECORDING
            )
            val emptyPaused = createSession(
                id = "session-empty-paused",
                startEpochMs = startEpochMs + 10_000L,
                endEpochMs = null,
                elapsedDurationMs = 12_000L,
                effectiveDurationMs = 4_000L,
                pausedDurationMs = 8_000L,
                status = SessionStatus.PAUSED
            )

            var firstOpen: AppDatabase? = null
            var reopened: AppDatabase? = null
            try {
                firstOpen = AppDatabase.buildPersistentForTesting(context, dbName)
                firstOpen.sessionDao().insertSession(emptyRecording)
                firstOpen.sessionDao().insertSession(emptyPaused)
                firstOpen.close()
                firstOpen = null

                reopened = AppDatabase.buildPersistentForTesting(context, dbName)

                val reconciledRecording = reopened.sessionDao().getSessionById(emptyRecording.id)
                assertNotNull(reconciledRecording)
                reconciledRecording!!
                assertEquals(SessionStatus.COMPLETED_UNEXPECTEDLY, reconciledRecording.status)
                assertEquals(startEpochMs, reconciledRecording.endEpochMs)
                assertEquals(0, reconciledRecording.avgBpm)
                assertEquals(0, reconciledRecording.maxBpm)
                assertEquals(0, reconciledRecording.minBpm)
                assertEquals(0L, reconciledRecording.zone1DurationMs)
                assertEquals(0L, reconciledRecording.zone2DurationMs)
                assertEquals(0L, reconciledRecording.zone3DurationMs)
                assertEquals(0L, reconciledRecording.zone4DurationMs)
                assertEquals(0L, reconciledRecording.zone5DurationMs)
                assertEquals(0L, reconciledRecording.elapsedDurationMs)
                assertEquals(0L, reconciledRecording.effectiveDurationMs)
                assertEquals(0L, reconciledRecording.pausedDurationMs)

                val reconciledPaused = reopened.sessionDao().getSessionById(emptyPaused.id)
                assertNotNull(reconciledPaused)
                reconciledPaused!!
                assertEquals(SessionStatus.COMPLETED_UNEXPECTEDLY, reconciledPaused.status)
                assertEquals(startEpochMs + 10_000L, reconciledPaused.endEpochMs)
                assertEquals(0, reconciledPaused.avgBpm)
                assertEquals(0, reconciledPaused.maxBpm)
                assertEquals(0, reconciledPaused.minBpm)
                assertEquals(12_000L, reconciledPaused.elapsedDurationMs)
                assertEquals(4_000L, reconciledPaused.effectiveDurationMs)
                assertEquals(8_000L, reconciledPaused.pausedDurationMs)
                assertEquals(
                    reconciledPaused.elapsedDurationMs,
                    reconciledPaused.effectiveDurationMs + reconciledPaused.pausedDurationMs
                )
            } finally {
                firstOpen?.close()
                reopened?.close()
                context.deleteDatabase(dbName)
            }
        }

    @Test
    fun reopenDatabase_reconcilesMultipleIncompleteSessionsAtomicallyAndIsIdempotent() = runTest {
        val dbName = uniqueDbName("crash_multiple_idempotent")
        val baseEpochMs = 1_700_004_000_000L

        val recordingOne = createSession(
            id = "session-rec-1",
            startEpochMs = baseEpochMs,
            status = SessionStatus.RECORDING
        )
        val pausedTwo = createSession(
            id = "session-paused-2",
            startEpochMs = baseEpochMs + 100_000L,
            status = SessionStatus.PAUSED
        )
        val recordingSingleSampleAtStart = createSession(
            id = "session-rec-single",
            startEpochMs = baseEpochMs + 200_000L,
            status = SessionStatus.RECORDING
        )
        val completed = createSession(
            id = "session-done",
            startEpochMs = baseEpochMs + 300_000L,
            endEpochMs = baseEpochMs + 310_000L,
            elapsedDurationMs = 10_000L,
            effectiveDurationMs = 10_000L,
            pausedDurationMs = 0L,
            status = SessionStatus.COMPLETED,
            avgBpm = 135,
            maxBpm = 140,
            minBpm = 130,
            zone2DurationMs = 10_000L
        )

        var firstOpen: AppDatabase? = null
        var secondOpen: AppDatabase? = null
        var thirdOpen: AppDatabase? = null
        try {
            firstOpen = AppDatabase.buildPersistentForTesting(context, dbName)
            firstOpen.sessionDao().insertSession(recordingOne)
            firstOpen.sessionDao().insertSession(pausedTwo)
            firstOpen.sessionDao().insertSession(recordingSingleSampleAtStart)
            firstOpen.sessionDao().insertSession(completed)

            firstOpen.heartRateSampleDao().insertSamplesBatch(
                listOf(
                    sample(recordingOne.id, baseEpochMs + 1_000L, bpm = 110, zoneIndex = 1),
                    sample(recordingOne.id, baseEpochMs + 2_000L, bpm = 130, zoneIndex = 2),
                    sample(pausedTwo.id, baseEpochMs + 101_000L, bpm = 165, zoneIndex = 4),
                    sample(pausedTwo.id, baseEpochMs + 102_000L, bpm = 175, zoneIndex = 5),
                    // Single sample at exact startEpochMs tests totalZoneDurationMs > wallElapsedMs
                    sample(recordingSingleSampleAtStart.id, baseEpochMs + 200_000L, bpm = 122, zoneIndex = 2)
                )
            )
            firstOpen.close()
            firstOpen = null

            // First re-open reconciles all three incomplete sessions atomically
            secondOpen = AppDatabase.buildPersistentForTesting(context, dbName)
            val afterFirstReopen = secondOpen.sessionDao().getAllSessions()
            assertEquals(4, afterFirstReopen.size)
            assertTrue(
                secondOpen.sessionDao()
                    .getSessionsByStatuses(listOf(SessionStatus.RECORDING, SessionStatus.PAUSED))
                    .isEmpty()
            )

            val rec1 = secondOpen.sessionDao().getSessionById(recordingOne.id)!!
            assertEquals(SessionStatus.COMPLETED_UNEXPECTEDLY, rec1.status)
            assertEquals(baseEpochMs + 2_000L, rec1.endEpochMs)
            assertEquals(120, rec1.avgBpm)
            assertEquals(130, rec1.maxBpm)
            assertEquals(110, rec1.minBpm)
            assertEquals(1_000L, rec1.zone1DurationMs)
            assertEquals(1_000L, rec1.zone2DurationMs)
            assertEquals(2_000L, rec1.elapsedDurationMs)
            assertEquals(2_000L, rec1.effectiveDurationMs)
            assertEquals(0L, rec1.pausedDurationMs)

            val paused2 = secondOpen.sessionDao().getSessionById(pausedTwo.id)!!
            assertEquals(SessionStatus.COMPLETED_UNEXPECTEDLY, paused2.status)
            assertEquals(baseEpochMs + 102_000L, paused2.endEpochMs)
            assertEquals(170, paused2.avgBpm)
            assertEquals(175, paused2.maxBpm)
            assertEquals(165, paused2.minBpm)
            assertEquals(1_000L, paused2.zone4DurationMs)
            assertEquals(1_000L, paused2.zone5DurationMs)

            val single = secondOpen.sessionDao().getSessionById(recordingSingleSampleAtStart.id)!!
            assertEquals(SessionStatus.COMPLETED_UNEXPECTEDLY, single.status)
            assertEquals(baseEpochMs + 200_000L, single.endEpochMs)
            assertEquals(122, single.avgBpm)
            assertEquals(122, single.maxBpm)
            assertEquals(122, single.minBpm)
            assertEquals(1_000L, single.zone2DurationMs)
            assertEquals(1_000L, single.elapsedDurationMs)
            assertEquals(1_000L, single.effectiveDurationMs)
            assertEquals(0L, single.pausedDurationMs)

            assertEquals(completed, secondOpen.sessionDao().getSessionById(completed.id))

            // Second re-open must be completely idempotent
            secondOpen.close()
            secondOpen = null

            thirdOpen = AppDatabase.buildPersistentForTesting(context, dbName)
            assertEquals(afterFirstReopen, thirdOpen.sessionDao().getAllSessions())
            assertEquals(0, SessionCrashReconciler.reconcile(thirdOpen.openHelper.writableDatabase))
        } finally {
            firstOpen?.close()
            secondOpen?.close()
            thirdOpen?.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun reconcile_onDatabaseWithoutSessionsTables_returnsZeroSafely() {
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
        try {
            val reconciledCount = SessionCrashReconciler.reconcile(helper.writableDatabase)
            assertEquals(0, reconciledCount)
        } finally {
            helper.close()
        }
    }

    @Test
    fun reconcile_viaDaoOverload_matchesSqliteReconciliationAndIsIdempotent() = runTest {
        val dbName = uniqueDbName("dao_sqlite_parity")
        val baseEpochMs = 1_700_005_000_000L

        val recordingSession = createSession(
            id = "session-dao-recording",
            startEpochMs = baseEpochMs,
            status = SessionStatus.RECORDING
        )
        val pausedSession = createSession(
            id = "session-dao-paused",
            startEpochMs = baseEpochMs + 50_000L,
            elapsedDurationMs = 25_000L,
            effectiveDurationMs = 10_000L,
            pausedDurationMs = 15_000L,
            status = SessionStatus.PAUSED
        )
        val emptySession = createSession(
            id = "session-dao-empty",
            startEpochMs = baseEpochMs + 100_000L,
            status = SessionStatus.RECORDING
        )
        val completedSession = createSession(
            id = "session-dao-completed",
            startEpochMs = baseEpochMs + 150_000L,
            endEpochMs = baseEpochMs + 180_000L,
            elapsedDurationMs = 30_000L,
            effectiveDurationMs = 30_000L,
            pausedDurationMs = 0L,
            status = SessionStatus.COMPLETED,
            avgBpm = 145,
            maxBpm = 160,
            minBpm = 130,
            zone3DurationMs = 30_000L
        )
        val discardedSession = createSession(
            id = "session-dao-discarded",
            startEpochMs = baseEpochMs + 200_000L,
            status = SessionStatus.DISCARDED
        )

        val samples = listOf(
            // Deliberately out of timestamp order to verify deterministic ordering
            sample(recordingSession.id, baseEpochMs + 12_000L, bpm = 168, zoneIndex = 4),
            sample(recordingSession.id, baseEpochMs + 1_000L, bpm = 112, zoneIndex = 1),
            sample(recordingSession.id, baseEpochMs + 4_000L, bpm = 134, zoneIndex = 2),
            sample(recordingSession.id, baseEpochMs + 13_000L, bpm = 182, zoneIndex = 5),
            sample(pausedSession.id, baseEpochMs + 51_000L, bpm = 142, zoneIndex = 3),
            sample(pausedSession.id, baseEpochMs + 52_000L, bpm = 146, zoneIndex = 3)
        )

        val allInitialSessions = listOf(
            recordingSession,
            pausedSession,
            emptySession,
            completedSession,
            discardedSession
        )

        // 1. Reconcile via FakeSessionDao + FakeHeartRateSampleDao
        val fakeSampleDao = FakeHeartRateSampleDao(initialSamples = samples)
        val fakeSessionDao = FakeSessionDao(
            initialSessions = allInitialSessions,
            sampleDao = fakeSampleDao
        )

        val reconciledList = SessionCrashReconciler.reconcile(fakeSessionDao, fakeSampleDao)
        assertEquals(
            listOf(emptySession.id, pausedSession.id, recordingSession.id),
            reconciledList.map { it.id }
        )
        assertTrue(reconciledList.all { it.status == SessionStatus.COMPLETED_UNEXPECTEDLY })
        assertEquals(completedSession, fakeSessionDao.getSessionById(completedSession.id))
        assertEquals(discardedSession, fakeSessionDao.getSessionById(discardedSession.id))

        // Second DAO call must be idempotent
        assertTrue(SessionCrashReconciler.reconcile(fakeSessionDao, fakeSampleDao).isEmpty())

        // 2. Reconcile the exact same dataset via on-disk SupportSQLiteDatabase onOpen hook and verify parity
        var firstOpen: AppDatabase? = null
        var reopened: AppDatabase? = null
        try {
            firstOpen = AppDatabase.buildPersistentForTesting(context, dbName)
            for (session in allInitialSessions) {
                firstOpen.sessionDao().insertSession(session)
            }
            firstOpen.heartRateSampleDao().insertSamplesBatch(samples)
            firstOpen.close()
            firstOpen = null

            reopened = AppDatabase.buildPersistentForTesting(context, dbName)
            assertEquals(reopened.sessionDao().getAllSessions(), fakeSessionDao.getAllSessions())
        } finally {
            firstOpen?.close()
            reopened?.close()
            context.deleteDatabase(dbName)
        }
    }

    private fun uniqueDbName(prefix: String): String = "${prefix}_${UUID.randomUUID()}.db"

    private fun sample(
        sessionId: String,
        timestampEpochMs: Long,
        bpm: Int,
        zoneIndex: Int,
        sensorContact: Boolean = true
    ): HeartRateSampleEntity {
        return HeartRateSampleEntity(
            sessionId = sessionId,
            timestampEpochMs = timestampEpochMs,
            bpm = bpm,
            zoneIndex = zoneIndex,
            sensorContact = sensorContact
        )
    }

    private fun createSession(
        id: String,
        startEpochMs: Long = 1_700_000_000_000L,
        endEpochMs: Long? = null,
        elapsedDurationMs: Long = 0L,
        effectiveDurationMs: Long = 0L,
        pausedDurationMs: Long = 0L,
        status: SessionStatus = SessionStatus.RECORDING,
        hrmMacAddress: String = "AA:BB:CC:11:22:33",
        userAge: Int = 34,
        calculatedHrmax: Int = 184,
        restingHr: Int? = 55,
        calculationMode: ZoneCalculationMode = ZoneCalculationMode.KARVONEN,
        avgBpm: Int = 0,
        maxBpm: Int = 0,
        minBpm: Int = 0,
        zone1DurationMs: Long = 0L,
        zone2DurationMs: Long = 0L,
        zone3DurationMs: Long = 0L,
        zone4DurationMs: Long = 0L,
        zone5DurationMs: Long = 0L
    ): SessionEntity {
        return SessionEntity(
            id = id,
            startEpochMs = startEpochMs,
            endEpochMs = endEpochMs,
            elapsedDurationMs = elapsedDurationMs,
            effectiveDurationMs = effectiveDurationMs,
            pausedDurationMs = pausedDurationMs,
            status = status,
            hrmMacAddress = hrmMacAddress,
            userAge = userAge,
            calculatedHrmax = calculatedHrmax,
            restingHr = restingHr,
            calculationMode = calculationMode,
            avgBpm = avgBpm,
            maxBpm = maxBpm,
            minBpm = minBpm,
            zone1DurationMs = zone1DurationMs,
            zone2DurationMs = zone2DurationMs,
            zone3DurationMs = zone3DurationMs,
            zone4DurationMs = zone4DurationMs,
            zone5DurationMs = zone5DurationMs
        )
    }
}
