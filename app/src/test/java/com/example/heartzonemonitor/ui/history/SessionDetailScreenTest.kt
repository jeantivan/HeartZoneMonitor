package com.example.heartzonemonitor.ui.history

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.heartzonemonitor.data.local.FakeRememberedHrmDao
import com.example.heartzonemonitor.data.local.FakeSessionDao
import com.example.heartzonemonitor.data.local.FakeUserProfileDao
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.SessionEntity
import com.example.heartzonemonitor.data.local.entity.SessionStatus
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import com.example.heartzonemonitor.ui.navigation.RootNavHost
import com.example.heartzonemonitor.ui.navigation.RootViewModel
import com.example.heartzonemonitor.ui.onboarding.OnboardingProfileViewModel
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SessionDetailScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `renders header card timestamps status badge HRM metadata and 4-column hero telemetry`() {
        var backClicked = false
        val model = buildDetailModel(status = SessionStatus.COMPLETED)

        composeTestRule.activity.setContent {
            SessionDetailContent(
                uiState = SessionDetailUiState(isLoading = false, session = model),
                actions = SessionDetailActions(onNavigateBack = { backClicked = true })
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(SESSION_DETAIL_SCREEN_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("SESSION DETAIL").assertIsDisplayed()
        composeTestRule.onNodeWithText("Wednesday, Nov 11, 2026").assertIsDisplayed()

        // Header Card: Start/End timestamps, status badge, HRM name & MAC address
        composeTestRule.onNodeWithTag(SESSION_DETAIL_HEADER_CARD_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(SESSION_DETAIL_START_TIMESTAMP_TAG)
            .assertIsDisplayed()
            .assertTextEquals("Nov 11, 2026 · 05:00:05 PM")
        composeTestRule.onNodeWithTag(SESSION_DETAIL_END_TIMESTAMP_TAG)
            .assertIsDisplayed()
            .assertTextEquals("Nov 11, 2026 · 05:56:15 PM")
        val completedBadge = composeTestRule
            .onNodeWithTag(SESSION_DETAIL_STATUS_BADGE_TAG)
            .assertIsDisplayed()
            .assertTextEquals("COMPLETED")
            .fetchSemanticsNode()
        assertColorEquals(Color(0xFF10B981), completedBadge.config[SessionDetailBadgeColorKey])

        composeTestRule.onNodeWithTag(SESSION_DETAIL_HRM_NAME_TAG)
            .assertIsDisplayed()
            .assertTextEquals("Chest Strap")
        composeTestRule.onNodeWithTag(SESSION_DETAIL_HRM_MAC_TAG)
            .assertIsDisplayed()
            .assertTextEquals("D4:36:39:88:21:9B")

        // 4-Column Hero Telemetry Row: Effective Duration, AVG HR, MAX HR, MIN HR
        composeTestRule.onNodeWithTag(SESSION_DETAIL_HERO_CARD_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(SESSION_DETAIL_EFFECTIVE_DURATION_TAG)
            .assertIsDisplayed()
            .assertTextEquals("00:54:20")
        composeTestRule.onNodeWithTag(SESSION_DETAIL_AVG_BPM_TAG)
            .assertIsDisplayed()
            .assertTextContains("146")
            .assertTextContains("BPM")
        composeTestRule.onNodeWithTag(SESSION_DETAIL_MAX_BPM_TAG)
            .assertIsDisplayed()
            .assertTextContains("174")
            .assertTextContains("BPM")
        composeTestRule.onNodeWithTag(SESSION_DETAIL_MIN_BPM_TAG)
            .assertIsDisplayed()
            .assertTextContains("98")
            .assertTextContains("BPM")

        composeTestRule.onNodeWithContentDescription("Navigate back").performClick()
        assertTrue(backClicked)
    }

    @Test
    fun `renders amber unexpected stop badge and zero BPM dashes`() {
        val unexpectedModel = buildDetailModel(
            status = SessionStatus.COMPLETED_UNEXPECTEDLY,
            avgBpm = 0,
            maxBpm = 0,
            minBpm = 0
        )

        composeTestRule.activity.setContent {
            SessionDetailContent(
                uiState = SessionDetailUiState(isLoading = false, session = unexpectedModel)
            )
        }
        composeTestRule.waitForIdle()

        val amberBadge = composeTestRule
            .onNodeWithTag(SESSION_DETAIL_STATUS_BADGE_TAG)
            .assertIsDisplayed()
            .assertTextEquals("UNEXPECTED STOP")
            .fetchSemanticsNode()
        assertColorEquals(Color(0xFFF59E0B), amberBadge.config[SessionDetailBadgeColorKey])

        composeTestRule.onNodeWithTag(SESSION_DETAIL_AVG_BPM_TAG).assertTextContains("--")
        composeTestRule.onNodeWithTag(SESSION_DETAIL_MAX_BPM_TAG).assertTextContains("--")
        composeTestRule.onNodeWithTag(SESSION_DETAIL_MIN_BPM_TAG).assertTextContains("--")
    }

    @Test
    fun `renders zone distribution bar percentage labels breakdown table and verifies duration consistency`() {
        // Age 38, restingHr 58, calculatedHrmax 181 -> reserve = 123 (Karvonen)
        // Z1: 120-132, Z2: 132-144, Z3: 144-156, Z4: 156-169, Z5: 169-181
        val model = buildDetailModel()

        composeTestRule.activity.setContent {
            SessionDetailContent(
                uiState = SessionDetailUiState(isLoading = false, session = model)
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(SESSION_DETAIL_ZONE_CARD_TAG)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("DOMINANT: ZONE 3 (TEMPO · 40%)").assertIsDisplayed()

        // 5-segment proportional bar: Z1..Z4 present (Z5 == 0ms omitted), proportional widths Z3 > Z2 > Z4 > Z1
        composeTestRule.onNodeWithTag(SESSION_DETAIL_ZONE_BAR_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(SESSION_DETAIL_ZONE_BAR_EMPTY_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(sessionDetailZoneSegmentTag(5)).assertDoesNotExist()

        val expectedSegments = listOf(
            Triple(1, Color(0xFF64748B), 320f / 3260f),
            Triple(2, Color(0xFF3B82F6), 1125f / 3260f),
            Triple(3, Color(0xFF10B981), 1290f / 3260f),
            Triple(4, Color(0xFFF59E0B), 525f / 3260f)
        )
        val widthsByZone = mutableMapOf<Int, Float>()
        expectedSegments.forEach { (zoneIndex, expectedColor, expectedFraction) ->
            val segNode = composeTestRule
                .onNodeWithTag(sessionDetailZoneSegmentTag(zoneIndex))
                .assertIsDisplayed()
                .fetchSemanticsNode()
            assertColorEquals(expectedColor, segNode.config[SessionDetailZoneColorKey])
            assertEquals(expectedFraction, segNode.config[SessionDetailZoneFractionKey], 0.002f)

            val bounds = composeTestRule
                .onNodeWithTag(sessionDetailZoneSegmentTag(zoneIndex))
                .getBoundsInRoot()
            widthsByZone[zoneIndex] = (bounds.right - bounds.left).value
        }
        assertTrue(widthsByZone.getValue(3) > widthsByZone.getValue(2))
        assertTrue(widthsByZone.getValue(2) > widthsByZone.getValue(4))
        assertTrue(widthsByZone.getValue(4) > widthsByZone.getValue(1))

        // Bar percentage labels for Z1-Z5
        val expectedBarLabels = listOf("Z1 10%", "Z2 34%", "Z3 40%", "Z4 16%", "Z5 0%")
        expectedBarLabels.forEachIndexed { idx, label ->
            composeTestRule
                .onNodeWithTag(sessionDetailZoneBarPercentTag(idx + 1))
                .assertIsDisplayed()
                .assertTextEquals(label)
        }

        // Breakdown table rows Z1-Z5
        data class ExpectedRow(
            val zoneIndex: Int,
            val zoneTitle: String,
            val zoneName: String,
            val bpmRange: String,
            val durationHhMmSs: String,
            val percentText: String
        )
        val expectedRows = listOf(
            ExpectedRow(1, "Zone 1", "(Active Recovery)", "120 - 132 BPM", "00:05:20", "10%"),
            ExpectedRow(2, "Zone 2", "(Aerobic Base)", "132 - 144 BPM", "00:18:45", "34%"),
            ExpectedRow(3, "Zone 3", "(Tempo)", "144 - 156 BPM", "00:21:30", "40%"),
            ExpectedRow(4, "Zone 4", "(Lactate Threshold)", "156 - 169 BPM", "00:08:45", "16%"),
            ExpectedRow(5, "Zone 5", "(VO2max)", "169 - 181 BPM", "00:00:00", "0%")
        )

        expectedRows.forEach { row ->
            composeTestRule.onNodeWithTag(sessionDetailZoneRowTag(row.zoneIndex))
                .performScrollTo()
                .assertIsDisplayed()
            composeTestRule.onNodeWithTag(sessionDetailZoneNameTag(row.zoneIndex))
                .assertIsDisplayed()
                .assertTextContains(row.zoneTitle)
                .assertTextContains(row.zoneName)
            composeTestRule.onNodeWithTag(sessionDetailZoneRangeTag(row.zoneIndex))
                .assertTextEquals(row.bpmRange)
            composeTestRule.onNodeWithTag(sessionDetailZoneDurationTag(row.zoneIndex))
                .assertTextEquals(row.durationHhMmSs)
            composeTestRule.onNodeWithTag(sessionDetailZonePercentTag(row.zoneIndex))
                .assertTextEquals(row.percentText)
        }

        // Mathematical consistency check across rendered UI strings:
        // Sum of Z1-Z5 HH:mm:ss durations equals Hero Effective Duration HH:mm:ss, and percentages sum to 100%.
        val heroEffectiveSeconds = parseHhMmSsToSeconds(
            readNodeText(SESSION_DETAIL_EFFECTIVE_DURATION_TAG)
        )
        val summedZoneSeconds = (1..5).sumOf { zoneIndex ->
            parseHhMmSsToSeconds(readNodeText(sessionDetailZoneDurationTag(zoneIndex)))
        }
        val summedPercentages = (1..5).sumOf { zoneIndex ->
            readNodeText(sessionDetailZonePercentTag(zoneIndex)).removeSuffix("%").toInt()
        }

        assertEquals(3_260L, heroEffectiveSeconds)
        assertEquals(heroEffectiveSeconds, summedZoneSeconds)
        assertEquals(100, summedPercentages)
    }

    @Test
    fun `renders muted zone bar safely when all zone durations are zero`() {
        val zeroModel = buildDetailModel(
            effectiveDurationMs = 0L,
            elapsedDurationMs = 0L,
            pausedDurationMs = 0L,
            avgBpm = 0,
            maxBpm = 0,
            minBpm = 0,
            zone1Ms = 0L,
            zone2Ms = 0L,
            zone3Ms = 0L,
            zone4Ms = 0L,
            zone5Ms = 0L
        )

        composeTestRule.activity.setContent {
            SessionDetailContent(
                uiState = SessionDetailUiState(isLoading = false, session = zeroModel)
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(SESSION_DETAIL_ZONE_CARD_TAG).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag(SESSION_DETAIL_ZONE_BAR_EMPTY_TAG).assertIsDisplayed()
        for (zoneIndex in 1..5) {
            composeTestRule.onNodeWithTag(sessionDetailZoneSegmentTag(zoneIndex)).assertDoesNotExist()
            composeTestRule.onNodeWithTag(sessionDetailZoneDurationTag(zoneIndex)).assertTextEquals("00:00:00")
            composeTestRule.onNodeWithTag(sessionDetailZonePercentTag(zoneIndex)).assertTextEquals("0%")
        }

        // Gap session (30s in Z3 out of 60s Effective Duration -> 50%): bar width occupies ~50% of track, not 100%
        val halfGapModel = buildDetailModel(
            effectiveDurationMs = 60_000L,
            elapsedDurationMs = 60_000L,
            pausedDurationMs = 0L,
            zone1Ms = 0L,
            zone2Ms = 0L,
            zone3Ms = 30_000L,
            zone4Ms = 0L,
            zone5Ms = 0L
        )
        composeTestRule.activity.setContent {
            SessionDetailContent(
                uiState = SessionDetailUiState(isLoading = false, session = halfGapModel)
            )
        }
        composeTestRule.waitForIdle()

        val trackBounds = composeTestRule.onNodeWithTag(SESSION_DETAIL_ZONE_BAR_TAG).getBoundsInRoot()
        val z3Bounds = composeTestRule.onNodeWithTag(sessionDetailZoneSegmentTag(3)).getBoundsInRoot()
        val trackWidth = (trackBounds.right - trackBounds.left).value
        val z3Width = (z3Bounds.right - z3Bounds.left).value
        assertTrue(
            "Expected Z3 width ($z3Width) to be ~50% of track width ($trackWidth)",
            z3Width < trackWidth * 0.65f && z3Width > trackWidth * 0.35f
        )
        composeTestRule.onNodeWithTag(sessionDetailZoneBarPercentTag(3)).assertTextEquals("Z3 50%")
    }

    @Test
    fun `renders durations and frozen physiological snapshot card for Karvonen and percent HRmax`() {
        val karvonenModel = buildDetailModel(
            restingHr = 58,
            calculationMode = ZoneCalculationMode.KARVONEN
        )

        composeTestRule.activity.setContent {
            SessionDetailContent(
                uiState = SessionDetailUiState(isLoading = false, session = karvonenModel)
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(SESSION_DETAIL_SNAPSHOT_CARD_TAG)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(SESSION_DETAIL_ELAPSED_DURATION_TAG)
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextEquals("00:56:10")
        composeTestRule.onNodeWithTag(SESSION_DETAIL_PAUSED_DURATION_TAG)
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextEquals("00:01:50")

        // Verify Effective Duration + Paused Duration == Elapsed Duration from rendered UI text
        val effectiveSec = parseHhMmSsToSeconds(readNodeText(SESSION_DETAIL_EFFECTIVE_DURATION_TAG))
        val pausedSec = parseHhMmSsToSeconds(readNodeText(SESSION_DETAIL_PAUSED_DURATION_TAG))
        val elapsedSec = parseHhMmSsToSeconds(readNodeText(SESSION_DETAIL_ELAPSED_DURATION_TAG))
        assertEquals(elapsedSec, effectiveSec + pausedSec)

        composeTestRule.onNodeWithTag(SESSION_DETAIL_SNAPSHOT_AGE_TAG)
            .performScrollTo()
            .assertTextEquals("38 yrs")
        composeTestRule.onNodeWithTag(SESSION_DETAIL_SNAPSHOT_RESTING_HR_TAG)
            .performScrollTo()
            .assertTextEquals("58 BPM")
        composeTestRule.onNodeWithTag(SESSION_DETAIL_SNAPSHOT_HRMAX_TAG)
            .performScrollTo()
            .assertTextEquals("181 BPM")
        composeTestRule.onNodeWithTag(SESSION_DETAIL_SNAPSHOT_MODE_TAG)
            .performScrollTo()
            .assertTextEquals("Karvonen HRR")

        // Verify %HRmax fallback mode when restingHr is null
        val percentModel = buildDetailModel(
            restingHr = null,
            calculationMode = ZoneCalculationMode.PERCENT_HRMAX
        )
        composeTestRule.activity.setContent {
            SessionDetailContent(
                uiState = SessionDetailUiState(isLoading = false, session = percentModel)
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(SESSION_DETAIL_SNAPSHOT_RESTING_HR_TAG)
            .performScrollTo()
            .assertTextEquals("Not set")
        composeTestRule.onNodeWithTag(SESSION_DETAIL_SNAPSHOT_MODE_TAG)
            .performScrollTo()
            .assertTextEquals("%HRmax")
    }

    @Test
    fun `rendered UI adheres strictly to GLOSSARY terminology`() {
        val model = buildDetailModel()
        composeTestRule.activity.setContent {
            SessionDetailContent(
                uiState = SessionDetailUiState(isLoading = false, session = model)
            )
        }
        composeTestRule.waitForIdle()

        val allNodes = collectSemanticsTree(
            composeTestRule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        )
        val forbiddenTerms = listOf(
            "workout",
            "max bpm",
            "max heart rate",
            "peak heart rate",
            "top pulse",
            "pulse",
            "saved sensor",
            "trusted peripheral",
            "user account",
            "personal info",
            "total time",
            "overall duration",
            "active time",
            "training time",
            "net time",
            "idle time",
            "rest time"
        )

        allNodes.forEach { node ->
            val texts = node.config.getOrNull(SemanticsProperties.Text)
                ?.joinToString(" ") { it.text }
                .orEmpty()
            val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription)
                ?.joinToString(" ")
                .orEmpty()
            val combined = "$texts $descriptions".lowercase(Locale.ROOT)
            forbiddenTerms.forEach { term ->
                assertFalse(
                    "Found forbidden glossary term '$term' in rendered UI semantics: '$combined'",
                    combined.contains(term)
                )
            }
        }
    }

    @Test
    fun `RootNavHost opens full SessionDetailScreen with real session telemetry and navigates back`() {
        val profileDao = FakeUserProfileDao(
            initialProfile = UserProfileEntity(
                id = UserProfileEntity.SINGLETON_ID,
                birthYear = 1988,
                restingHr = 58,
                customHrmax = null,
                calculationMode = ZoneCalculationMode.KARVONEN,
                updatedAtEpochMs = 1_000L
            )
        )
        val hrmDao = FakeRememberedHrmDao(
            listOf(
                RememberedHrmEntity(
                    macAddress = "D4:36:39:88:21:9B",
                    advertisedName = "COOSPO H808S",
                    customNickname = "Chest Strap",
                    lastSeenTimestamp = 2_000L,
                    batteryLevel = 92,
                    isActive = true
                )
            )
        )
        val sessionId = "session-nav-detail-1"
        val sessionDao = FakeSessionDao(
            listOf(
                SessionEntity(
                    id = sessionId,
                    startEpochMs = Instant.parse("2026-11-11T17:00:05Z").toEpochMilli(),
                    endEpochMs = Instant.parse("2026-11-11T17:56:15Z").toEpochMilli(),
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
            )
        )
        val rootViewModel = RootViewModel(userProfileDao = profileDao, rememberedHrmDao = hrmDao)

        composeTestRule.activity.setContent {
            RootNavHost(
                rootViewModel = rootViewModel,
                onboardingViewModelFactory = { OnboardingProfileViewModel(profileDao) },
                sessionHistoryViewModelFactory = {
                    SessionHistoryViewModel(
                        sessionDao = sessionDao,
                        rememberedHrmDao = hrmDao,
                        zoneId = ZoneId.of("UTC"),
                        locale = Locale.US
                    )
                },
                sessionDetailViewModelFactory = { targetId ->
                    SessionDetailViewModel(
                        sessionId = targetId,
                        sessionDao = sessionDao,
                        rememberedHrmDao = hrmDao,
                        zoneId = ZoneId.of("UTC"),
                        locale = Locale.US
                    )
                }
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("HISTORY").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(sessionHistoryCardTag(sessionId)).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(SESSION_DETAIL_SCREEN_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(SESSION_DETAIL_EFFECTIVE_DURATION_TAG).assertTextEquals("00:54:20")
        composeTestRule.onNodeWithTag(SESSION_DETAIL_HRM_MAC_TAG).assertTextEquals("D4:36:39:88:21:9B")
        composeTestRule.onNodeWithTag(SESSION_DETAIL_SNAPSHOT_CARD_TAG).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag(SESSION_DETAIL_SNAPSHOT_HRMAX_TAG).assertTextEquals("181 BPM")

        composeTestRule.onNodeWithContentDescription("Navigate back").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(SESSION_DETAIL_SCREEN_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithText("SESSION HISTORY").assertIsDisplayed()
    }

    private fun collectSemanticsTree(root: SemanticsNode): List<SemanticsNode> {
        val collected = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) {
            collected.add(node)
            node.children.forEach(::visit)
        }
        visit(root)
        return collected
    }

    private fun readNodeText(tag: String): String =
        composeTestRule.onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString("") { it.text }

    private fun parseHhMmSsToSeconds(value: String): Long {
        val parts = value.split(":")
        assertEquals("Expected HH:mm:ss format but got '$value'", 3, parts.size)
        return parts[0].toLong() * 3_600L + parts[1].toLong() * 60L + parts[2].toLong()
    }

    private fun buildDetailModel(
        status: SessionStatus = SessionStatus.COMPLETED,
        avgBpm: Int = 146,
        maxBpm: Int = 174,
        minBpm: Int = 98,
        restingHr: Int? = 58,
        calculationMode: ZoneCalculationMode = ZoneCalculationMode.KARVONEN,
        effectiveDurationMs: Long = 3_260_000L,
        elapsedDurationMs: Long = 3_370_000L,
        pausedDurationMs: Long = 110_000L,
        zone1Ms: Long = 320_000L,
        zone2Ms: Long = 1_125_000L,
        zone3Ms: Long = 1_290_000L,
        zone4Ms: Long = 525_000L,
        zone5Ms: Long = 0L
    ): SessionDetailModel {
        val hrm = RememberedHrmEntity(
            macAddress = "D4:36:39:88:21:9B",
            advertisedName = "COOSPO H808S",
            customNickname = "Chest Strap",
            lastSeenTimestamp = 1_000L,
            isActive = true
        )
        val entity = SessionEntity(
            id = "session-detail-test",
            startEpochMs = Instant.parse("2026-11-11T17:00:05Z").toEpochMilli(),
            endEpochMs = Instant.parse("2026-11-11T17:56:15Z").toEpochMilli(),
            elapsedDurationMs = elapsedDurationMs,
            effectiveDurationMs = effectiveDurationMs,
            pausedDurationMs = pausedDurationMs,
            status = status,
            hrmMacAddress = hrm.macAddress,
            userAge = 38,
            calculatedHrmax = 181,
            restingHr = restingHr,
            calculationMode = calculationMode,
            avgBpm = avgBpm,
            maxBpm = maxBpm,
            minBpm = minBpm,
            zone1DurationMs = zone1Ms,
            zone2DurationMs = zone2Ms,
            zone3DurationMs = zone3Ms,
            zone4DurationMs = zone4Ms,
            zone5DurationMs = zone5Ms
        )
        return entity.toSessionDetailModel(
            hrmsByNormalizedMac = mapOf(hrm.macAddress to hrm)
        )
    }

    private fun assertColorEquals(expected: Color, actual: Color) {
        assertEquals(expected.red, actual.red, 0.002f)
        assertEquals(expected.green, actual.green, 0.002f)
        assertEquals(expected.blue, actual.blue, 0.002f)
        assertEquals(expected.alpha, actual.alpha, 0.002f)
    }
}
