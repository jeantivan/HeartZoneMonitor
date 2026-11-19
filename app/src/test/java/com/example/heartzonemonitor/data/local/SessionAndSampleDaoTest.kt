package com.example.heartzonemonitor.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import com.example.heartzonemonitor.data.local.dao.HeartRateSampleDao
import com.example.heartzonemonitor.data.local.dao.RememberedHrmDao
import com.example.heartzonemonitor.data.local.dao.SessionDao
import com.example.heartzonemonitor.data.local.entity.HeartRateSample
import com.example.heartzonemonitor.data.local.entity.HeartRateSampleEntity
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.Session
import com.example.heartzonemonitor.data.local.entity.SessionEntity
import com.example.heartzonemonitor.data.local.entity.SessionStatus
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionAndSampleDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var sessionDao: SessionDao
    private lateinit var sampleDao: HeartRateSampleDao
    private lateinit var rememberedHrmDao: RememberedHrmDao
    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        db = AppDatabase.buildInMemory(context)
        sessionDao = db.sessionDao()
        sampleDao = db.heartRateSampleDao()
        rememberedHrmDao = db.rememberedHrmDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun sessionsAndHeartRateSamplesTables_areInitializedEmpty() = runTest {
        assertTrue(sessionDao.getAllSessions().isEmpty())
        assertTrue(sessionDao.observeAllSessions().first().isEmpty())
        assertNull(sessionDao.getSessionById("missing"))
        assertNull(sessionDao.observeSessionById("missing").first())
        assertEquals(0, sampleDao.getSampleCountForSession("missing"))
        assertTrue(sampleDao.getSamplesForSession("missing").isEmpty())
        assertNull(sampleDao.getLatestSampleForSession("missing"))
    }

    @Test
    fun sessionEntity_validatesInvariants() {
        assertThrows(IllegalArgumentException::class.java) {
            createSession(id = "   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            createSession(hrmMacAddress = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            createSession(elapsedDurationMs = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            createSession(effectiveDurationMs = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            createSession(pausedDurationMs = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            createSession(avgBpm = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            createSession(maxBpm = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            createSession(minBpm = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            createSession(zone1DurationMs = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            createSession(zone2DurationMs = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            createSession(zone3DurationMs = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            createSession(zone4DurationMs = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            createSession(zone5DurationMs = -1L)
        }
    }

    @Test
    fun heartRateSampleEntity_validatesInvariants() {
        assertThrows(IllegalArgumentException::class.java) {
            HeartRateSampleEntity(
                sessionId = " ",
                timestampEpochMs = 1000L,
                bpm = 120,
                zoneIndex = 2,
                sensorContact = true
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            HeartRateSampleEntity(
                sessionId = "session-1",
                timestampEpochMs = 1000L,
                bpm = -1,
                zoneIndex = 2,
                sensorContact = true
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            HeartRateSampleEntity(
                sessionId = "session-1",
                timestampEpochMs = 1000L,
                bpm = 256,
                zoneIndex = 2,
                sensorContact = true
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            HeartRateSampleEntity(
                sessionId = "session-1",
                timestampEpochMs = 1000L,
                bpm = 120,
                zoneIndex = 0,
                sensorContact = true
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            HeartRateSampleEntity(
                sessionId = "session-1",
                timestampEpochMs = 1000L,
                bpm = 120,
                zoneIndex = 6,
                sensorContact = true
            )
        }

        val minBoundary = HeartRateSample(
            sessionId = "session-1",
            timestampEpochMs = 1000L,
            bpm = 0,
            zoneIndex = 1,
            sensorContact = false
        )
        val maxBoundary = HeartRateSample(
            sessionId = "session-1",
            timestampEpochMs = 2000L,
            bpm = 255,
            zoneIndex = 5,
            sensorContact = true
        )
        assertEquals(0, minBoundary.bpm)
        assertEquals(5, maxBoundary.zoneIndex)
    }

    @Test
    fun sessionDao_persistsCrudProfileSnapshotsStatusesAndReactiveFlows() = runTest {
        val observedList = Channel<List<SessionEntity>>(Channel.UNLIMITED)
        val listJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            sessionDao.observeAllSessions().collect { observedList.send(it) }
        }
        assertTrue(observedList.receive().isEmpty())

        val olderSession = createSession(
            id = "session-older",
            startEpochMs = 1_700_000_000_000L,
            endEpochMs = null,
            status = SessionStatus.RECORDING,
            userAge = 34,
            calculatedHrmax = 184,
            restingHr = 52,
            calculationMode = ZoneCalculationMode.KARVONEN
        )
        sessionDao.insertSession(olderSession)
        assertEquals(listOf(olderSession), observedList.receive())

        val newerSession = createSession(
            id = "session-newer",
            startEpochMs = 1_700_005_000_000L,
            endEpochMs = 1_700_006_800_000L,
            elapsedDurationMs = 1_800_000L,
            effectiveDurationMs = 1_700_000L,
            pausedDurationMs = 100_000L,
            status = SessionStatus.COMPLETED,
            userAge = 34,
            calculatedHrmax = 184,
            restingHr = null,
            calculationMode = ZoneCalculationMode.PERCENT_HRMAX,
            avgBpm = 148,
            maxBpm = 179,
            minBpm = 94,
            zone1DurationMs = 100_000L,
            zone2DurationMs = 400_000L,
            zone3DurationMs = 700_000L,
            zone4DurationMs = 400_000L,
            zone5DurationMs = 100_000L
        )
        sessionDao.upsertSession(newerSession)

        // observeAllSessions must order by startEpochMs DESC
        val allAfterInsert = observedList.receive()
        assertEquals(listOf("session-newer", "session-older"), allAfterInsert.map { it.id })

        val retrievedNewer = sessionDao.getSessionById("session-newer")
        assertNotNull(retrievedNewer)
        assertNull(retrievedNewer?.restingHr)
        assertEquals(ZoneCalculationMode.PERCENT_HRMAX, retrievedNewer?.calculationMode)
        assertEquals(148, retrievedNewer?.avgBpm)
        assertEquals(179, retrievedNewer?.maxBpm)
        assertEquals(94, retrievedNewer?.minBpm)
        assertEquals(700_000L, retrievedNewer?.zone3DurationMs)

        // Observe single session transition via Channel
        val singleChannel = Channel<SessionEntity?>(Channel.UNLIMITED)
        val singleJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            sessionDao.observeSessionById("session-older").collect { singleChannel.send(it) }
        }
        assertEquals(SessionStatus.RECORDING, singleChannel.receive()?.status)

        val pausedOlder = olderSession.copy(
            status = SessionStatus.PAUSED,
            effectiveDurationMs = 300_000L,
            elapsedDurationMs = 300_000L
        )
        assertEquals(1, sessionDao.updateSession(pausedOlder))
        assertEquals(SessionStatus.PAUSED, singleChannel.receive()?.status)
        observedList.receive()

        // Verify Room persists enum names as plain TEXT
        db.openHelper.readableDatabase.query(
            "SELECT status, calculationMode FROM sessions WHERE id = 'session-older'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("PAUSED", cursor.getString(0))
            assertEquals("KARVONEN", cursor.getString(1))
        }

        // Verify all remaining statuses & getSessionsByStatuses filtering
        val unexpectedSession = createSession(
            id = "session-unexpected",
            startEpochMs = 1_700_010_000_000L,
            status = SessionStatus.COMPLETED_UNEXPECTEDLY
        )
        val discardedSession = createSession(
            id = "session-discarded",
            startEpochMs = 1_700_015_000_000L,
            status = SessionStatus.DISCARDED
        )
        sessionDao.insertSession(unexpectedSession)
        observedList.receive()
        sessionDao.insertSession(discardedSession)
        observedList.receive()

        val activeOrPaused = sessionDao.getSessionsByStatuses(
            listOf(SessionStatus.RECORDING, SessionStatus.PAUSED)
        )
        assertEquals(listOf("session-older"), activeOrPaused.map { it.id })

        val finished = sessionDao.getSessionsByStatuses(
            listOf(SessionStatus.COMPLETED, SessionStatus.COMPLETED_UNEXPECTEDLY)
        )
        assertEquals(
            listOf("session-unexpected", "session-newer"),
            finished.map { it.id }
        )
        assertTrue(sessionDao.getSessionsByStatuses(emptyList()).isEmpty())

        assertEquals(1, sessionDao.deleteSession(discardedSession))
        observedList.receive()
        assertNull(sessionDao.getSessionById("session-discarded"))

        singleJob.cancel()
        listJob.cancel()
    }

    @Test
    fun forgettingRememberedHrm_doesNotFailFkChecksOrDeleteHistoricalSessions() = runTest {
        val hrm = RememberedHrmEntity(
            macAddress = "AA:BB:CC:11:22:33",
            advertisedName = "COOSPO H808S",
            lastSeenTimestamp = 1_700_000_000_000L,
            isActive = true
        )
        rememberedHrmDao.upsertHrm(hrm)

        val sessionWithRememberedMac = createSession(
            id = "session-with-remembered-hrm",
            hrmMacAddress = hrm.macAddress,
            status = SessionStatus.COMPLETED
        )
        val sessionWithUnknownMac = createSession(
            id = "session-with-unknown-hrm",
            hrmMacAddress = "FF:EE:DD:CC:BB:AA",
            status = SessionStatus.COMPLETED
        )
        sessionDao.insertSession(sessionWithRememberedMac)
        sessionDao.insertSession(sessionWithUnknownMac)

        sampleDao.insertSamplesBatch(
            listOf(
                HeartRateSampleEntity(
                    sessionId = sessionWithRememberedMac.id,
                    timestampEpochMs = 1_700_000_001_000L,
                    bpm = 135,
                    zoneIndex = 2,
                    sensorContact = true
                )
            )
        )

        // Forgetting the Remembered HRM must succeed cleanly and preserve historical Sessions & Samples
        assertEquals(1, rememberedHrmDao.deleteHrmByMacAddress(hrm.macAddress))
        assertNull(rememberedHrmDao.getHrmByMacAddress(hrm.macAddress))
        assertNotNull(sessionDao.getSessionById(sessionWithRememberedMac.id))
        assertNotNull(sessionDao.getSessionById(sessionWithUnknownMac.id))
        assertEquals(1, sampleDao.getSampleCountForSession(sessionWithRememberedMac.id))
    }

    @Test
    fun heartRateSampleDao_batchInsertsPaginatesObservesAndRollsBackAtomicallyOnError() = runTest {
        val session = createSession(id = "session-samples")
        sessionDao.insertSession(session)

        // Empty batch must be a safe no-op
        sampleDao.insertSamplesBatch(emptyList())
        assertEquals(0, sampleDao.getSampleCountForSession(session.id))

        val sampleChannel = Channel<List<HeartRateSampleEntity>>(Channel.UNLIMITED)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            sampleDao.observeSamplesForSession(session.id).collect { sampleChannel.send(it) }
        }
        assertTrue(sampleChannel.receive().isEmpty())

        val batch1 = listOf(
            HeartRateSampleEntity(
                sessionId = session.id,
                timestampEpochMs = 1_700_000_002_000L,
                bpm = 130,
                zoneIndex = 2,
                sensorContact = true
            ),
            HeartRateSampleEntity(
                sessionId = session.id,
                timestampEpochMs = 1_700_000_001_000L,
                bpm = 120,
                zoneIndex = 1,
                sensorContact = true
            ),
            HeartRateSampleEntity(
                sessionId = session.id,
                timestampEpochMs = 1_700_000_003_000L,
                bpm = 145,
                zoneIndex = 3,
                sensorContact = false
            )
        )
        sampleDao.insertSamplesBatch(batch1)

        val emitted = sampleChannel.receive()
        assertEquals(listOf(120, 130, 145), emitted.map { it.bpm })
        assertTrue(emitted.all { it.id > 0L })
        assertEquals(3, sampleDao.getSampleCountForSession(session.id))
        assertEquals(145, sampleDao.getLatestSampleForSession(session.id)?.bpm)
        assertFalse(sampleDao.getLatestSampleForSession(session.id)?.sensorContact ?: true)

        // Pagination check
        val page1 = sampleDao.getSamplesPage(session.id, limit = 2, offset = 0)
        val page2 = sampleDao.getSamplesPage(session.id, limit = 2, offset = 2)
        assertEquals(listOf(120, 130), page1.map { it.bpm })
        assertEquals(listOf(145), page2.map { it.bpm })

        // Batch containing an invalid foreign key must roll back ALL rows in the batch atomically
        val badBatch = listOf(
            HeartRateSampleEntity(
                sessionId = session.id,
                timestampEpochMs = 1_700_000_004_000L,
                bpm = 150,
                zoneIndex = 3,
                sensorContact = true
            ),
            HeartRateSampleEntity(
                sessionId = "non-existent-session",
                timestampEpochMs = 1_700_000_005_000L,
                bpm = 155,
                zoneIndex = 4,
                sensorContact = true
            )
        )
        try {
            sampleDao.insertSamplesBatch(badBatch)
            fail("Expected foreign key constraint failure for unknown sessionId")
        } catch (expected: Exception) {
            assertTrue(
                expected is SQLiteConstraintException ||
                    expected.message?.contains("FOREIGN KEY", ignoreCase = true) == true
            )
        }

        // Row from badBatch with valid sessionId must NOT have been partially persisted
        assertEquals(3, sampleDao.getSampleCountForSession(session.id))

        job.cancel()
    }

    @Test
    fun deleteSession_cascadesToHeartRateSamples_leavingOtherSessionsIntact() = runTest {
        val sessionA = createSession(id = "session-a", startEpochMs = 1_000L)
        val sessionB = createSession(id = "session-b", startEpochMs = 2_000L)
        sessionDao.insertSession(sessionA)
        sessionDao.insertSession(sessionB)

        sampleDao.insertSamplesBatch(
            listOf(
                HeartRateSampleEntity(
                    sessionId = sessionA.id,
                    timestampEpochMs = 1_001L,
                    bpm = 110,
                    zoneIndex = 1,
                    sensorContact = true
                ),
                HeartRateSampleEntity(
                    sessionId = sessionA.id,
                    timestampEpochMs = 1_002L,
                    bpm = 125,
                    zoneIndex = 2,
                    sensorContact = true
                ),
                HeartRateSampleEntity(
                    sessionId = sessionA.id,
                    timestampEpochMs = 1_003L,
                    bpm = 140,
                    zoneIndex = 3,
                    sensorContact = true
                ),
                HeartRateSampleEntity(
                    sessionId = sessionB.id,
                    timestampEpochMs = 2_001L,
                    bpm = 160,
                    zoneIndex = 4,
                    sensorContact = true
                ),
                HeartRateSampleEntity(
                    sessionId = sessionB.id,
                    timestampEpochMs = 2_002L,
                    bpm = 172,
                    zoneIndex = 5,
                    sensorContact = true
                )
            )
        )

        assertEquals(3, sampleDao.getSampleCountForSession(sessionA.id))
        assertEquals(2, sampleDao.getSampleCountForSession(sessionB.id))

        assertEquals(1, sessionDao.deleteSessionById(sessionA.id))

        assertNull(sessionDao.getSessionById(sessionA.id))
        assertEquals(0, sampleDao.getSampleCountForSession(sessionA.id))
        assertTrue(sampleDao.getSamplesForSession(sessionA.id).isEmpty())

        // Verify via raw SQL that zero orphaned samples remain for sessionA
        db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM heart_rate_samples WHERE sessionId = ?",
            arrayOf(sessionA.id)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }

        // Session B and its samples remain completely intact
        assertNotNull(sessionDao.getSessionById(sessionB.id))
        assertEquals(2, sampleDao.getSampleCountForSession(sessionB.id))
        assertEquals(listOf(160, 172), sampleDao.getSamplesForSession(sessionB.id).map { it.bpm })
    }

    @Test
    fun heartRateSamples_hasCompositeIndex_andExplainQueryPlanUsesIndexWithoutTempSort() = runTest {
        val readableDb = db.openHelper.readableDatabase

        // 1. Verify composite index exists in sqlite_master
        var indexFound = false
        readableDb.query(
            "SELECT name, sql FROM sqlite_master WHERE type = 'index' AND tbl_name = 'heart_rate_samples'"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val indexName = cursor.getString(0)
                if (indexName == "idx_heart_rate_samples_session_ts") {
                    indexFound = true
                }
            }
        }
        assertTrue("Expected composite index idx_heart_rate_samples_session_ts", indexFound)

        // 2. Verify EXPLAIN QUERY PLAN uses idx_heart_rate_samples_session_ts without temp B-tree sort
        val queries = listOf(
            "EXPLAIN QUERY PLAN SELECT * FROM heart_rate_samples WHERE sessionId = ? ORDER BY timestampEpochMs ASC",
            "EXPLAIN QUERY PLAN SELECT * FROM heart_rate_samples WHERE sessionId = ? ORDER BY timestampEpochMs ASC, id ASC"
        )
        for (sql in queries) {
            val planDetails = mutableListOf<String>()
            readableDb.query(sql, arrayOf("session-1")).use { cursor ->
                val detailCol = cursor.getColumnIndex("detail")
                while (cursor.moveToNext()) {
                    val detail = if (detailCol >= 0) {
                        cursor.getString(detailCol)
                    } else {
                        cursor.getString(cursor.columnCount - 1)
                    }
                    planDetails.add(detail)
                }
            }

            val combinedPlan = planDetails.joinToString(separator = " | ")
            assertTrue(
                "Expected query plan for [$sql] to use idx_heart_rate_samples_session_ts, but got: $combinedPlan",
                combinedPlan.contains("USING INDEX idx_heart_rate_samples_session_ts")
            )
            assertFalse(
                "Expected no temp B-tree sort in query plan for [$sql], but got: $combinedPlan",
                combinedPlan.contains("USE TEMP B-TREE", ignoreCase = true)
            )
        }
    }

    private fun createSession(
        id: String = "session-1",
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
