package com.example.heartzonemonitor.ui.history

import com.example.heartzonemonitor.data.local.FakeRememberedHrmDao
import com.example.heartzonemonitor.data.local.FakeSessionDao
import com.example.heartzonemonitor.data.local.dao.SessionDao
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.SessionEntity
import com.example.heartzonemonitor.data.local.entity.SessionStatus
import com.example.heartzonemonitor.domain.model.HeartZone
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
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
class SessionHistoryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val utcZone: ZoneId = ZoneId.of("UTC")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `observes sessions in DESC order with O1 SessionDao only`() =
        runTest(testDispatcher) {
            // Confirm SessionHistoryViewModel constructor has no HeartRateSampleDao parameter
            val constructorParamTypeNames = SessionHistoryViewModel::class.java.constructors
                .flatMap { it.parameterTypes.map { paramType -> paramType.simpleName } }
            assertFalse(
                "SessionHistoryViewModel must never depend on HeartRateSampleDao",
                constructorParamTypeNames.any { it.contains("Sample", ignoreCase = true) }
            )

            val oldest = buildSession(
                id = "session-oldest",
                startEpochMs = Instant.parse("2026-11-05T06:00:00Z").toEpochMilli(),
                effectiveDurationMs = 1_800_000L, // 30:00
                avgBpm = 132,
                maxBpm = 155
            )
            val middleB = buildSession(
                id = "session-middle-b",
                startEpochMs = Instant.parse("2026-11-07T17:30:00Z").toEpochMilli(),
                effectiveDurationMs = 2_712_000L, // 45:12
                avgBpm = 148,
                maxBpm = 176
            )
            val middleA = buildSession(
                id = "session-middle-a",
                startEpochMs = Instant.parse("2026-11-07T17:30:00Z").toEpochMilli(),
                effectiveDurationMs = 900_000L, // 15:00
                avgBpm = 140,
                maxBpm = 160
            )
            val newest = buildSession(
                id = "session-newest",
                startEpochMs = Instant.parse("2026-11-09T07:15:00Z").toEpochMilli(),
                effectiveDurationMs = 4_325_000L, // 01:12:05
                avgBpm = 152,
                maxBpm = 181
            )

            val fakeDao = FakeSessionDao(listOf(oldest, middleB, newest, middleA))
            val strictO1SessionDao = StrictObserveAllOnlySessionDao(fakeDao)

            val viewModel = SessionHistoryViewModel(
                sessionDao = strictO1SessionDao,
                zoneId = utcZone,
                locale = Locale.US
            )

            val state = viewModel.uiState.first { !it.isLoading }

            assertEquals(1, strictO1SessionDao.observeAllCalls)
            assertEquals(
                listOf("session-newest", "session-middle-a", "session-middle-b", "session-oldest"),
                state.sessions.map { it.id }
            )
            assertEquals(4, state.sessionCount)
            assertEquals(1_800_000L + 2_712_000L + 900_000L + 4_325_000L, state.totalEffectiveDurationMs)
            assertEquals(152, state.sessions.first().avgBpm)
            assertEquals(181, state.sessions.first().maxBpm)
        }

    @Test
    fun `filters non-finalized statuses and flags unexpected stops`() =
        runTest(testDispatcher) {
            val recording = buildSession(
                id = "s-recording",
                startEpochMs = 5_000L,
                effectiveDurationMs = 60_000L,
                status = SessionStatus.RECORDING
            )
            val paused = buildSession(
                id = "s-paused",
                startEpochMs = 4_000L,
                effectiveDurationMs = 120_000L,
                status = SessionStatus.PAUSED
            )
            val discarded = buildSession(
                id = "s-discarded",
                startEpochMs = 3_000L,
                effectiveDurationMs = 5_000L,
                status = SessionStatus.DISCARDED
            )
            val completed = buildSession(
                id = "s-completed",
                startEpochMs = 2_000L,
                effectiveDurationMs = 1_200_000L,
                status = SessionStatus.COMPLETED
            )
            val unexpected = buildSession(
                id = "s-unexpected",
                startEpochMs = 1_000L,
                effectiveDurationMs = 600_000L,
                status = SessionStatus.COMPLETED_UNEXPECTEDLY
            )

            val fakeDao = FakeSessionDao(listOf(recording, paused, discarded, completed, unexpected))
            val viewModel = SessionHistoryViewModel(
                sessionDao = fakeDao,
                zoneId = utcZone,
                locale = Locale.US
            )

            val state = viewModel.uiState.first { !it.isLoading }

            assertEquals(listOf("s-completed", "s-unexpected"), state.sessions.map { it.id })
            assertEquals(2, state.sessionCount)
            assertEquals(1_800_000L, state.totalEffectiveDurationMs)

            val completedItem = state.sessions[0]
            assertEquals(SessionStatus.COMPLETED, completedItem.status)
            assertFalse(completedItem.isUnexpectedCompletion)

            val unexpectedItem = state.sessions[1]
            assertEquals(SessionStatus.COMPLETED_UNEXPECTEDLY, unexpectedItem.status)
            assertTrue(unexpectedItem.isUnexpectedCompletion)
        }

    @Test
    fun `resolves HRM nickname and MAC snippet fallback reactively`() =
        runTest(testDispatcher) {
            val nicknamedHrm = RememberedHrmEntity(
                macAddress = "D4:36:39:88:21:9B",
                advertisedName = "COOSPO H808S",
                customNickname = "Chest Strap",
                lastSeenTimestamp = 1_000L,
                isActive = true
            )
            val advertisedOnlyHrm = RememberedHrmEntity(
                macAddress = "A0:9E:1A:33:12:44",
                advertisedName = "Polar OH1",
                customNickname = null,
                lastSeenTimestamp = 1_000L,
                isActive = false
            )
            val forgottenMac = "AA:BB:CC:DD:EE:99"

            val fakeSessionDao = FakeSessionDao(
                listOf(
                    buildSession(
                        id = "s-nick",
                        startEpochMs = 3_000L,
                        hrmMacAddress = nicknamedHrm.macAddress.lowercase(Locale.ROOT)
                    ),
                    buildSession(
                        id = "s-adv",
                        startEpochMs = 2_000L,
                        hrmMacAddress = advertisedOnlyHrm.macAddress
                    ),
                    buildSession(
                        id = "s-forgotten",
                        startEpochMs = 1_000L,
                        hrmMacAddress = forgottenMac
                    )
                )
            )
            val fakeHrmDao = FakeRememberedHrmDao(listOf(nicknamedHrm, advertisedOnlyHrm))

            val viewModel = SessionHistoryViewModel(
                sessionDao = fakeSessionDao,
                rememberedHrmDao = fakeHrmDao,
                zoneId = utcZone,
                locale = Locale.US
            )

            val initial = viewModel.uiState.first { !it.isLoading }
            assertEquals("Chest Strap", initial.sessions[0].hrmDisplayName)
            assertEquals("Polar OH1", initial.sessions[1].hrmDisplayName)
            assertEquals("AA:BB:CC:...:99", initial.sessions[2].hrmDisplayName)

            // Forgetting a Remembered HRM reactively falls back to its MAC snippet
            fakeHrmDao.deleteHrmByMacAddress(nicknamedHrm.macAddress)
            advanceUntilIdle()
            val afterForget = viewModel.uiState.value
            assertEquals("d4:36:39:...:9b", afterForget.sessions[0].hrmDisplayName)

            // Updating nickname reactively updates displayed HRM label
            fakeHrmDao.updateNickname(advertisedOnlyHrm.macAddress, "Track Armband")
            advanceUntilIdle()
            assertEquals("Track Armband", viewModel.uiState.value.sessions[1].hrmDisplayName)

            // Without RememberedHrmDao, falls back cleanly to MAC snippet
            val withoutHrmDaoViewModel = SessionHistoryViewModel(
                sessionDao = fakeSessionDao,
                rememberedHrmDao = null,
                zoneId = utcZone,
                locale = Locale.US
            )
            val fallbackState = withoutHrmDaoViewModel.uiState.first { !it.isLoading }
            assertEquals("AA:BB:CC:...:99", fallbackState.sessions[2].hrmDisplayName)
        }

    @Test
    fun `formats date start time and effective duration in UTC`() =
        runTest(testDispatcher) {
            val morningShort = buildSession(
                id = "s-morning",
                startEpochMs = Instant.parse("2026-11-09T07:15:00Z").toEpochMilli(),
                effectiveDurationMs = 2_712_000L // 45:12
            )
            val eveningLong = buildSession(
                id = "s-evening",
                startEpochMs = Instant.parse("2026-11-07T17:30:00Z").toEpochMilli(),
                effectiveDurationMs = 4_325_000L // 01:12:05
            )
            val boundaryExactHour = buildSession(
                id = "s-hour",
                startEpochMs = Instant.parse("2026-11-06T00:05:00Z").toEpochMilli(),
                effectiveDurationMs = 3_600_000L // 01:00:00
            )

            val fakeDao = FakeSessionDao(listOf(morningShort, eveningLong, boundaryExactHour))
            val viewModel = SessionHistoryViewModel(
                sessionDao = fakeDao,
                zoneId = utcZone,
                locale = Locale.US
            )

            val state = viewModel.uiState.first { !it.isLoading }

            assertEquals("Monday, Nov 9, 2026", state.sessions[0].formattedDate)
            assertEquals("07:15 AM", state.sessions[0].formattedStartTime)
            assertEquals("45:12", state.sessions[0].formattedEffectiveDuration)

            assertEquals("Saturday, Nov 7, 2026", state.sessions[1].formattedDate)
            assertEquals("05:30 PM", state.sessions[1].formattedStartTime)
            assertEquals("01:12:05", state.sessions[1].formattedEffectiveDuration)

            assertEquals("Friday, Nov 6, 2026", state.sessions[2].formattedDate)
            assertEquals("12:05 AM", state.sessions[2].formattedStartTime)
            assertEquals("01:00:00", state.sessions[2].formattedEffectiveDuration)

            assertEquals("00:00", formatSessionEffectiveDuration(0L))
            assertEquals("59:59", formatSessionEffectiveDuration(3_599_000L))
            assertEquals("45s", formatZoneDurationCompact(45_000L))
            assertEquals("21m 44s", formatZoneDurationCompact(1_304_000L))
            assertEquals("1h 05m 02s", formatZoneDurationCompact(3_902_000L))
            assertEquals("1 SESSION · 1.5 HRS", formatSessionHistorySummary(1, 5_400_000L))
            assertEquals("3 SESSIONS · 2.5 HRS", formatSessionHistorySummary(3, 9_000_000L))
        }

    @Test
    fun `computes zone distribution fractions and handles zero zones`() =
        runTest(testDispatcher) {
            val distributed = buildSession(
                id = "s-distributed",
                startEpochMs = 3_000L,
                zone1DurationMs = 10_000L,
                zone2DurationMs = 35_000L,
                zone3DurationMs = 40_000L,
                zone4DurationMs = 15_000L,
                zone5DurationMs = 0L
            )
            val allZeroZones = buildSession(
                id = "s-zero",
                startEpochMs = 2_000L,
                effectiveDurationMs = 0L,
                avgBpm = 0,
                maxBpm = 0,
                zone1DurationMs = 0L,
                zone2DurationMs = 0L,
                zone3DurationMs = 0L,
                zone4DurationMs = 0L,
                zone5DurationMs = 0L
            )
            val singleZone = buildSession(
                id = "s-single",
                startEpochMs = 1_000L,
                zone1DurationMs = 0L,
                zone2DurationMs = 0L,
                zone3DurationMs = 0L,
                zone4DurationMs = 0L,
                zone5DurationMs = 60_000L
            )

            val fakeDao = FakeSessionDao(listOf(distributed, allZeroZones, singleZone))
            val viewModel = SessionHistoryViewModel(
                sessionDao = fakeDao,
                zoneId = utcZone,
                locale = Locale.US
            )

            val state = viewModel.uiState.first { !it.isLoading }

            // Distributed session
            val distItem = state.sessions[0]
            assertEquals(5, distItem.zoneSegments.size)
            assertEquals(HeartZone.ZONE_1, distItem.zoneSegments[0].zone)
            assertEquals(0.10f, distItem.zoneSegments[0].fraction, 0.0001f)
            assertEquals(0.35f, distItem.zoneSegments[1].fraction, 0.0001f)
            assertEquals(0.40f, distItem.zoneSegments[2].fraction, 0.0001f)
            assertEquals(0.15f, distItem.zoneSegments[3].fraction, 0.0001f)
            assertEquals(0.00f, distItem.zoneSegments[4].fraction, 0.0001f)
            assertEquals(1.0f, distItem.zoneSegments.sumOf { it.fraction.toDouble() }.toFloat(), 0.001f)
            assertNotNull(distItem.dominantZone)
            assertEquals(HeartZone.ZONE_3, distItem.dominantZone?.zone)
            assertEquals(40_000L, distItem.dominantZone?.durationMs)

            // All-zero zones session
            val zeroItem = state.sessions[1]
            assertEquals(5, zeroItem.zoneSegments.size)
            assertTrue(zeroItem.zoneSegments.all { it.durationMs == 0L && it.fraction == 0f })
            assertNull(zeroItem.dominantZone)

            // Single non-zero zone session
            val singleItem = state.sessions[2]
            assertEquals(1.0f, singleItem.zoneSegments[4].fraction, 0.0001f)
            assertEquals(HeartZone.ZONE_5, singleItem.dominantZone?.zone)
        }

    @Test
    fun `emits reactive updates on insert finalize and delete`() =
        runTest(testDispatcher) {
            val fakeDao = FakeSessionDao()
            val viewModel = SessionHistoryViewModel(
                sessionDao = fakeDao,
                zoneId = utcZone,
                locale = Locale.US
            )
            advanceUntilIdle()

            val states = Channel<SessionHistoryUiState>(Channel.UNLIMITED)
            val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect { states.send(it) }
            }

            // Initial empty finalized state
            val initial = states.receive()
            assertFalse(initial.isLoading)
            assertTrue(initial.isEmpty)
            assertEquals(0, initial.sessionCount)

            // Inserting a RECORDING session must not produce a finalized history card
            val liveSession = buildSession(
                id = "s-live",
                startEpochMs = 10_000L,
                effectiveDurationMs = 30_000L,
                status = SessionStatus.RECORDING
            )
            fakeDao.insertSession(liveSession)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isEmpty)

            // Finalizing the session emits a 1-item state
            fakeDao.updateSession(
                liveSession.copy(
                    status = SessionStatus.COMPLETED,
                    effectiveDurationMs = 900_000L,
                    avgBpm = 144,
                    maxBpm = 165
                )
            )
            advanceUntilIdle()
            val afterComplete = states.receive()
            assertEquals(listOf("s-live"), afterComplete.sessions.map { it.id })
            assertEquals(1, afterComplete.sessionCount)
            assertEquals(900_000L, afterComplete.totalEffectiveDurationMs)

            // Inserting a newer crash-recovered session prepends it in chronological DESC order
            val crashRecovered = buildSession(
                id = "s-crash",
                startEpochMs = 20_000L,
                effectiveDurationMs = 450_000L,
                status = SessionStatus.COMPLETED_UNEXPECTEDLY
            )
            fakeDao.insertSession(crashRecovered)
            advanceUntilIdle()
            val afterSecond = states.receive()
            assertEquals(listOf("s-crash", "s-live"), afterSecond.sessions.map { it.id })
            assertEquals(2, afterSecond.sessionCount)
            assertEquals(1_350_000L, afterSecond.totalEffectiveDurationMs)
            assertTrue(afterSecond.sessions.first().isUnexpectedCompletion)

            // Deleting a session removes it reactively
            fakeDao.deleteSessionById("s-live")
            advanceUntilIdle()
            val afterDelete = states.receive()
            assertEquals(listOf("s-crash"), afterDelete.sessions.map { it.id })
            assertEquals(1, afterDelete.sessionCount)
            assertEquals(450_000L, afterDelete.totalEffectiveDurationMs)

            collectJob.cancel()
        }

    private fun buildSession(
        id: String,
        startEpochMs: Long,
        effectiveDurationMs: Long = 1_800_000L,
        status: SessionStatus = SessionStatus.COMPLETED,
        hrmMacAddress: String = "D4:36:39:88:21:9B",
        avgBpm: Int = 142,
        maxBpm: Int = 168,
        zone1DurationMs: Long = 180_000L,
        zone2DurationMs: Long = 540_000L,
        zone3DurationMs: Long = 720_000L,
        zone4DurationMs: Long = 360_000L,
        zone5DurationMs: Long = 0L
    ): SessionEntity = SessionEntity(
        id = id,
        startEpochMs = startEpochMs,
        endEpochMs = startEpochMs + effectiveDurationMs,
        elapsedDurationMs = effectiveDurationMs,
        effectiveDurationMs = effectiveDurationMs,
        pausedDurationMs = 0L,
        status = status,
        hrmMacAddress = hrmMacAddress,
        userAge = 34,
        calculatedHrmax = 184,
        restingHr = 58,
        calculationMode = ZoneCalculationMode.KARVONEN,
        avgBpm = avgBpm,
        maxBpm = maxBpm,
        minBpm = if (avgBpm > 0) 98 else 0,
        zone1DurationMs = zone1DurationMs,
        zone2DurationMs = zone2DurationMs,
        zone3DurationMs = zone3DurationMs,
        zone4DurationMs = zone4DurationMs,
        zone5DurationMs = zone5DurationMs
    )

    private class StrictObserveAllOnlySessionDao(
        private val delegate: FakeSessionDao
    ) : SessionDao {
        var observeAllCalls: Int = 0
            private set

        override fun observeAllSessions(): Flow<List<SessionEntity>> {
            observeAllCalls += 1
            return delegate.observeAllSessions()
        }

        override suspend fun insertSession(session: SessionEntity) =
            throw AssertionError("Unexpected insertSession call")

        override suspend fun upsertSession(session: SessionEntity) =
            throw AssertionError("Unexpected upsertSession call")

        override suspend fun updateSession(session: SessionEntity): Int =
            throw AssertionError("Unexpected updateSession call")

        override suspend fun getSessionById(sessionId: String): SessionEntity? =
            throw AssertionError("Unexpected O(N) getSessionById call")

        override fun observeSessionById(sessionId: String): Flow<SessionEntity?> =
            throw AssertionError("Unexpected observeSessionById call")

        override suspend fun getAllSessions(): List<SessionEntity> =
            throw AssertionError("Unexpected getAllSessions call")

        override suspend fun getSessionsByStatuses(statuses: List<SessionStatus>): List<SessionEntity> =
            throw AssertionError("Unexpected getSessionsByStatuses call")

        override suspend fun deleteSessionById(sessionId: String): Int =
            throw AssertionError("Unexpected deleteSessionById call")

        override suspend fun deleteSession(session: SessionEntity): Int =
            throw AssertionError("Unexpected deleteSession call")
    }
}
