package com.example.heartzonemonitor.ui.history

import com.example.heartzonemonitor.data.local.FakeHeartRateSampleDao
import com.example.heartzonemonitor.data.local.FakeRememberedHrmDao
import com.example.heartzonemonitor.data.local.FakeSessionDao
import com.example.heartzonemonitor.data.local.entity.HeartRateSampleEntity
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.SessionEntity
import com.example.heartzonemonitor.data.local.entity.SessionStatus
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailViewModelTest {

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
    fun `observes session header timestamps status and reactive HRM metadata`() =
        runTest(testDispatcher) {
            val hrm = RememberedHrmEntity(
                macAddress = "D4:36:39:88:21:9B",
                advertisedName = "COOSPO H808S",
                customNickname = "Chest Strap",
                lastSeenTimestamp = 1_000L,
                batteryLevel = 94,
                isActive = true
            )
            val session = SessionEntity(
                id = "session-header-1",
                startEpochMs = Instant.parse("2026-11-11T17:00:05Z").toEpochMilli(),
                endEpochMs = Instant.parse("2026-11-11T17:56:15Z").toEpochMilli(),
                elapsedDurationMs = 3_370_000L,
                effectiveDurationMs = 3_260_000L,
                pausedDurationMs = 110_000L,
                status = SessionStatus.COMPLETED,
                hrmMacAddress = "d4:36:39:88:21:9b",
                userAge = 38,
                calculatedHrmax = 181,
                restingHr = 58,
                calculationMode = ZoneCalculationMode.KARVONEN,
                avgBpm = 146,
                maxBpm = 174,
                minBpm = 98,
                zone1DurationMs = 320_000L,
                zone2DurationMs = 1_125_000L,
                zone3DurationMs = 1_290_000L,
                zone4DurationMs = 525_000L,
                zone5DurationMs = 0L
            )
            val sessionDao = FakeSessionDao(listOf(session))
            val hrmDao = FakeRememberedHrmDao(listOf(hrm))

            val viewModel = SessionDetailViewModel(
                sessionId = session.id,
                sessionDao = sessionDao,
                rememberedHrmDao = hrmDao,
                zoneId = utcZone,
                locale = Locale.US
            )

            val states = Channel<SessionDetailUiState>(Channel.UNLIMITED)
            val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState
                    .collect { if (!it.isLoading) states.send(it) }
            }

            val detail = states.receive().session
            assertNotNull(detail)
            requireNotNull(detail)

            assertEquals("Wednesday, Nov 11, 2026", detail.formattedDate)
            assertEquals("Nov 11, 2026 · 05:00:05 PM", detail.formattedStartTimestamp)
            assertEquals("Nov 11, 2026 · 05:56:15 PM", detail.formattedEndTimestamp)
            assertEquals(SessionStatus.COMPLETED, detail.status)
            assertFalse(detail.isUnexpectedCompletion)
            assertEquals("Chest Strap", detail.hrmDisplayName)
            assertEquals("D4:36:39:88:21:9B", detail.hrmMacAddress)

            // Forgetting the HRM preserves historical MAC address and falls back cleanly on name
            hrmDao.deleteHrmByMacAddress(hrm.macAddress)
            val afterForget = requireNotNull(states.receive().session)
            assertEquals("Unknown HRM", afterForget.hrmDisplayName)
            assertEquals("D4:36:39:88:21:9B", afterForget.hrmMacAddress)

            // Updating status to COMPLETED_UNEXPECTEDLY flags unexpected completion
            sessionDao.updateSession(
                session.copy(status = SessionStatus.COMPLETED_UNEXPECTEDLY, endEpochMs = null)
            )
            val afterUnexpected = requireNotNull(states.receive().session)
            assertTrue(afterUnexpected.isUnexpectedCompletion)
            assertEquals("--", afterUnexpected.formattedEndTimestamp)

            collectJob.cancel()
        }

    @Test
    fun `maps hero telemetry durations and frozen profile snapshot boundaries`() =
        runTest(testDispatcher) {
            // Custom lab HRmax = 198 (differs from Tanaka 181 for age 38), restingHr = 58 -> reserve = 140
            // Z1: 58 + 70..84 = 128..142, Z2: 142..156, Z3: 156..170, Z4: 170..184, Z5: 184..198
            val karvonenSession = SessionEntity(
                id = "session-karvonen-override",
                startEpochMs = Instant.parse("2026-11-12T06:30:00Z").toEpochMilli(),
                endEpochMs = Instant.parse("2026-11-12T07:45:30Z").toEpochMilli(),
                elapsedDurationMs = 4_530_000L, // 01:15:30
                effectiveDurationMs = 4_325_000L, // 01:12:05
                pausedDurationMs = 205_000L, // 00:03:25
                status = SessionStatus.COMPLETED,
                hrmMacAddress = "D4:36:39:88:21:9B",
                userAge = 38,
                calculatedHrmax = 198,
                restingHr = 58,
                calculationMode = ZoneCalculationMode.KARVONEN,
                avgBpm = 154,
                maxBpm = 188,
                minBpm = 102,
                zone1DurationMs = 432_500L,
                zone2DurationMs = 1_297_500L,
                zone3DurationMs = 1_730_000L,
                zone4DurationMs = 865_000L,
                zone5DurationMs = 0L
            )

            // %HRmax session without resting HR: calculatedHrmax = 180
            // Z1: 90..108, Z2: 108..126, Z3: 126..144, Z4: 144..162, Z5: 162..180
            val percentHrmaxSession = SessionEntity(
                id = "session-percent-hrmax",
                startEpochMs = Instant.parse("2026-11-10T18:00:00Z").toEpochMilli(),
                endEpochMs = Instant.parse("2026-11-10T18:30:00Z").toEpochMilli(),
                elapsedDurationMs = 1_800_000L, // 00:30:00
                effectiveDurationMs = 1_800_000L, // 00:30:00
                pausedDurationMs = 0L, // 00:00:00
                status = SessionStatus.COMPLETED,
                hrmMacAddress = "AA:BB:CC:DD:EE:11",
                userAge = 40,
                calculatedHrmax = 180,
                restingHr = null,
                calculationMode = ZoneCalculationMode.PERCENT_HRMAX,
                avgBpm = 135,
                maxBpm = 160,
                minBpm = 94,
                zone1DurationMs = 300_000L,
                zone2DurationMs = 600_000L,
                zone3DurationMs = 900_000L,
                zone4DurationMs = 0L,
                zone5DurationMs = 0L
            )

            val sessionDao = FakeSessionDao(listOf(karvonenSession, percentHrmaxSession))

            val karvonenVm = SessionDetailViewModel(
                sessionId = karvonenSession.id,
                sessionDao = sessionDao,
                zoneId = utcZone,
                locale = Locale.US
            )
            val kDetail = requireNotNull(karvonenVm.uiState.first { !it.isLoading }.session)

            assertEquals("01:12:05", kDetail.formattedEffectiveDuration)
            assertEquals("01:15:30", kDetail.formattedElapsedDuration)
            assertEquals("00:03:25", kDetail.formattedPausedDuration)
            assertEquals(154, kDetail.avgBpm)
            assertEquals(188, kDetail.maxBpm)
            assertEquals(102, kDetail.minBpm)

            assertEquals(38, kDetail.profileSnapshot.userAge)
            assertEquals(58, kDetail.profileSnapshot.restingHr)
            assertEquals(198, kDetail.profileSnapshot.calculatedHrmax)
            assertEquals(ZoneCalculationMode.KARVONEN, kDetail.profileSnapshot.calculationMode)
            assertEquals(
                listOf(128 to 142, 142 to 156, 156 to 170, 170 to 184, 184 to 198),
                kDetail.zoneBreakdowns.map { it.minBpm to it.maxBpm }
            )

            val percentVm = SessionDetailViewModel(
                sessionId = percentHrmaxSession.id,
                sessionDao = sessionDao,
                zoneId = utcZone,
                locale = Locale.US
            )
            val pDetail = requireNotNull(percentVm.uiState.first { !it.isLoading }.session)

            assertEquals("00:30:00", pDetail.formattedEffectiveDuration)
            assertEquals("00:30:00", pDetail.formattedElapsedDuration)
            assertEquals("00:00:00", pDetail.formattedPausedDuration)
            assertEquals(40, pDetail.profileSnapshot.userAge)
            assertEquals(null, pDetail.profileSnapshot.restingHr)
            assertEquals(180, pDetail.profileSnapshot.calculatedHrmax)
            assertEquals(ZoneCalculationMode.PERCENT_HRMAX, pDetail.profileSnapshot.calculationMode)
            assertEquals(
                listOf(90 to 108, 108 to 126, 126 to 144, 144 to 162, 162 to 180),
                pDetail.zoneBreakdowns.map { it.minBpm to it.maxBpm }
            )
        }

    @Test
    fun `computes consistent zone breakdown across sub-second offsets and gaps`() =
        runTest(testDispatcher) {
            val distributedSession = SessionEntity(
                id = "session-distributed",
                startEpochMs = 1_000L,
                endEpochMs = 3_261_000L,
                elapsedDurationMs = 3_370_000L,
                effectiveDurationMs = 3_260_000L,
                pausedDurationMs = 110_000L,
                status = SessionStatus.COMPLETED,
                hrmMacAddress = "D4:36:39:88:21:9B",
                userAge = 38,
                calculatedHrmax = 181,
                restingHr = 58,
                calculationMode = ZoneCalculationMode.KARVONEN,
                avgBpm = 146,
                maxBpm = 174,
                minBpm = 98,
                zone1DurationMs = 320_000L,
                zone2DurationMs = 1_125_000L,
                zone3DurationMs = 1_290_000L,
                zone4DurationMs = 525_000L,
                zone5DurationMs = 0L
            )

            // Equal thirds with +100ms monotonic tail (300_000ms zoned vs 300_100ms effective):
            // Both format as 00:05:00 and Largest Remainder apportions to exact 100% (34, 33, 33, 0, 0).
            val subSecondOffsetThirds = distributedSession.copy(
                id = "session-thirds-subsec",
                effectiveDurationMs = 300_100L,
                elapsedDurationMs = 300_100L,
                pausedDurationMs = 0L,
                zone1DurationMs = 100_000L,
                zone2DurationMs = 100_000L,
                zone3DurationMs = 100_000L,
                zone4DurationMs = 0L,
                zone5DurationMs = 0L
            )

            // Pre-auto-pause gap session: 30_000ms in Z3 out of 60_000ms effective -> 50% and 0.50f barFraction
            val gapSession = distributedSession.copy(
                id = "session-gap-half",
                effectiveDurationMs = 60_000L,
                elapsedDurationMs = 60_000L,
                pausedDurationMs = 0L,
                zone1DurationMs = 0L,
                zone2DurationMs = 0L,
                zone3DurationMs = 30_000L,
                zone4DurationMs = 0L,
                zone5DurationMs = 0L
            )

            val zeroSession = distributedSession.copy(
                id = "session-zero",
                status = SessionStatus.COMPLETED_UNEXPECTEDLY,
                effectiveDurationMs = 0L,
                elapsedDurationMs = 0L,
                pausedDurationMs = 0L,
                avgBpm = 0,
                maxBpm = 0,
                minBpm = 0,
                zone1DurationMs = 0L,
                zone2DurationMs = 0L,
                zone3DurationMs = 0L,
                zone4DurationMs = 0L,
                zone5DurationMs = 0L
            )

            val sessionDao = FakeSessionDao(
                listOf(distributedSession, subSecondOffsetThirds, gapSession, zeroSession)
            )

            val distDetail = requireNotNull(
                SessionDetailViewModel("session-distributed", sessionDao, zoneId = utcZone, locale = Locale.US)
                    .uiState.first { !it.isLoading }.session
            )
            assertEquals(
                listOf("00:05:20", "00:18:45", "00:21:30", "00:08:45", "00:00:00"),
                distDetail.zoneBreakdowns.map { it.formattedDuration }
            )
            assertEquals(
                distDetail.effectiveDurationMs,
                distDetail.zoneBreakdowns.sumOf { it.durationMs }
            )
            assertEquals(
                listOf(10, 34, 40, 16, 0),
                distDetail.zoneBreakdowns.map { it.percentageOfEffective }
            )
            assertEquals(100, distDetail.zoneBreakdowns.sumOf { it.percentageOfEffective })
            assertEquals(1.0f, distDetail.zoneBreakdowns.sumOf { it.barFraction.toDouble() }.toFloat(), 0.001f)
            assertEquals(3, distDetail.dominantZone?.zone?.zoneIndex)

            val thirdsDetail = requireNotNull(
                SessionDetailViewModel("session-thirds-subsec", sessionDao, zoneId = utcZone, locale = Locale.US)
                    .uiState.first { !it.isLoading }.session
            )
            assertEquals(100, thirdsDetail.zoneBreakdowns.sumOf { it.percentageOfEffective })
            assertEquals(0, thirdsDetail.zoneBreakdowns[3].percentageOfEffective)
            assertEquals(0, thirdsDetail.zoneBreakdowns[4].percentageOfEffective)

            val gapDetail = requireNotNull(
                SessionDetailViewModel("session-gap-half", sessionDao, zoneId = utcZone, locale = Locale.US)
                    .uiState.first { !it.isLoading }.session
            )
            assertEquals(listOf(0, 0, 50, 0, 0), gapDetail.zoneBreakdowns.map { it.percentageOfEffective })
            assertEquals(0.50f, gapDetail.zoneBreakdowns[2].barFraction, 0.001f)

            val zeroDetail = requireNotNull(
                SessionDetailViewModel("session-zero", sessionDao, zoneId = utcZone, locale = Locale.US)
                    .uiState.first { !it.isLoading }.session
            )
            assertTrue(
                zeroDetail.zoneBreakdowns.all {
                    it.durationMs == 0L && it.percentageOfEffective == 0 && it.barFraction == 0f
                }
            )
            assertEquals(null, zeroDetail.dominantZone)

            val missingState = SessionDetailViewModel("nonexistent-id", sessionDao, zoneId = utcZone, locale = Locale.US)
                .uiState.first { !it.isLoading }
            assertEquals(null, missingState.session)
        }

    @Test
    fun `observes session samples builds frozen chart thresholds and downsamples above 1000`() =
        runTest(testDispatcher) {
            val baseStartMs = Instant.parse("2026-11-11T17:00:00Z").toEpochMilli()
            val karvonenSession = SessionEntity(
                id = "session-chart-karvonen",
                startEpochMs = baseStartMs,
                endEpochMs = baseStartMs + 1_200_000L,
                elapsedDurationMs = 1_200_000L,
                effectiveDurationMs = 1_200_000L,
                pausedDurationMs = 0L,
                status = SessionStatus.COMPLETED,
                hrmMacAddress = "D4:36:39:88:21:9B",
                userAge = 38,
                calculatedHrmax = 181,
                restingHr = 58,
                calculationMode = ZoneCalculationMode.KARVONEN,
                avgBpm = 148,
                maxBpm = 178,
                minBpm = 112,
                zone1DurationMs = 200_000L,
                zone2DurationMs = 300_000L,
                zone3DurationMs = 400_000L,
                zone4DurationMs = 200_000L,
                zone5DurationMs = 100_000L
            )
            val percentSession = karvonenSession.copy(
                id = "session-chart-percent",
                userAge = 40,
                calculatedHrmax = 180,
                restingHr = null,
                calculationMode = ZoneCalculationMode.PERCENT_HRMAX
            )
            val sessionDao = FakeSessionDao(listOf(karvonenSession, percentSession))
            val sampleDao = FakeHeartRateSampleDao()

            val karvonenVm = SessionDetailViewModel(
                sessionId = karvonenSession.id,
                sessionDao = sessionDao,
                sampleDao = sampleDao,
                zoneId = utcZone,
                locale = Locale.US
            )

            val states = Channel<SessionDetailUiState>(Channel.UNLIMITED)
            val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
                karvonenVm.uiState.collect { if (!it.isLoading) states.send(it) }
            }

            // Initially empty samples -> frozen Karvonen thresholds still populated
            val initialChart = requireNotNull(states.receive().session).chart
            assertTrue(initialChart.plottedSamples.isEmpty())
            assertFalse(initialChart.isDownsampled)
            assertEquals(
                listOf(120, 132, 144, 156, 169),
                initialChart.zoneThresholds.map { it.bpm }
            )

            // Insert 1,200 1 Hz samples -> reactive update downsamples via LTTB to 800 points
            val manySamples = (0 until 1_200).map { sec ->
                HeartRateSampleEntity(
                    sessionId = karvonenSession.id,
                    timestampEpochMs = baseStartMs + sec * 1_000L,
                    bpm = if (sec == 600) 178 else 130 + (sec % 30),
                    zoneIndex = if (sec == 600) 5 else 3,
                    sensorContact = true
                )
            }
            sampleDao.insertSamples(manySamples)

            val updatedChart = requireNotNull(states.receive().session).chart
            assertTrue(updatedChart.isDownsampled)
            assertEquals(1_200, updatedChart.rawSampleCount)
            assertEquals(DEFAULT_CHART_LTTB_TARGET_POINTS, updatedChart.plottedSamples.size)
            assertTrue(updatedChart.plottedSamples.any { it.bpm == 178 })

            // Verify %HRmax session computes 50/60/70/80/90% of HRmax thresholds (90, 108, 126, 144, 162)
            val percentVm = SessionDetailViewModel(
                sessionId = percentSession.id,
                sessionDao = sessionDao,
                sampleDao = sampleDao,
                zoneId = utcZone,
                locale = Locale.US
            )
            val percentChart = requireNotNull(percentVm.uiState.first { !it.isLoading }.session).chart
            assertEquals(
                listOf(90, 108, 126, 144, 162),
                percentChart.zoneThresholds.map { it.bpm }
            )

            collectJob.cancel()
        }
}
