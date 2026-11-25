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
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.example.heartzonemonitor.data.local.FakeRememberedHrmDao
import com.example.heartzonemonitor.data.local.FakeSessionDao
import com.example.heartzonemonitor.data.local.FakeUserProfileDao
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.SessionEntity
import com.example.heartzonemonitor.data.local.entity.SessionStatus
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import com.example.heartzonemonitor.domain.model.HeartZone
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
class SessionHistoryScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `renders athletic card metrics and summary header`() {
        val shortSession = buildItem(
            id = "s-1",
            formattedDate = "Monday, Nov 9, 2026",
            formattedStartTime = "07:15 AM",
            effectiveDurationMs = 2_712_000L, // 45:12
            avgBpm = 146,
            maxBpm = 174,
            hrmDisplayName = "Chest Strap",
            zoneDurationsMs = listOf(100_000L, 350_000L, 400_000L, 150_000L, 0L)
        )
        val longSession = buildItem(
            id = "s-2",
            formattedDate = "Saturday, Nov 7, 2026",
            formattedStartTime = "05:30 PM",
            effectiveDurationMs = 4_325_000L, // 01:12:05
            avgBpm = 138,
            maxBpm = 162,
            hrmDisplayName = "AA:BB:CC:...:99",
            zoneDurationsMs = listOf(0L, 600_000L, 400_000L, 0L, 0L)
        )

        composeTestRule.activity.setContent {
            SessionHistoryContent(
                uiState = SessionHistoryUiState(
                    sessions = listOf(shortSession, longSession),
                    isLoading = false
                )
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("SESSION HISTORY").assertIsDisplayed()
        composeTestRule.onNodeWithTag(SESSION_HISTORY_SUMMARY_TAG)
            .assertIsDisplayed()
            .assertTextEquals("2 SESSIONS · 2.0 HRS")

        // Card 1 metrics
        composeTestRule.onNodeWithTag(sessionHistoryCardTag("s-1")).assertIsDisplayed()
        composeTestRule.onNodeWithText("Monday, Nov 9, 2026").assertIsDisplayed()
        composeTestRule.onNodeWithText("07:15 AM").assertIsDisplayed()
        composeTestRule.onNodeWithText("Chest Strap").assertIsDisplayed()
        composeTestRule.onNodeWithTag(sessionDurationTag("s-1"))
            .assertIsDisplayed()
            .assertTextEquals("45:12")
        composeTestRule.onNodeWithTag(sessionAvgBpmTag("s-1"))
            .assertIsDisplayed()
            .assertTextContains("146")
            .assertTextContains("BPM")
        composeTestRule.onNodeWithTag(sessionMaxBpmTag("s-1"))
            .assertIsDisplayed()
            .assertTextContains("174")
            .assertTextContains("BPM")
        composeTestRule.onNodeWithText("Dominant: Zone 3 Tempo (40%)").assertIsDisplayed()

        // Card 2 metrics (>= 1 hour duration format + MAC snippet fallback)
        composeTestRule.onNodeWithTag(SESSION_HISTORY_LIST_TAG)
            .performScrollToNode(hasTestTag(sessionHistoryCardTag("s-2")))
        composeTestRule.onNodeWithTag(sessionHistoryCardTag("s-2")).assertIsDisplayed()
        composeTestRule.onNodeWithText("Saturday, Nov 7, 2026").assertIsDisplayed()
        composeTestRule.onNodeWithText("05:30 PM").assertIsDisplayed()
        composeTestRule.onNodeWithText("AA:BB:CC:...:99").assertIsDisplayed()
        composeTestRule.onNodeWithTag(sessionDurationTag("s-2"))
            .assertIsDisplayed()
            .assertTextEquals("01:12:05")
        composeTestRule.onNodeWithTag(sessionAvgBpmTag("s-2"))
            .assertIsDisplayed()
            .assertTextContains("138")
        composeTestRule.onNodeWithTag(sessionMaxBpmTag("s-2"))
            .assertIsDisplayed()
            .assertTextContains("162")
    }

    @Test
    fun `renders proportional 5-segment zone bar and omits zero zones`() {
        val fiveZoneSession = buildItem(
            id = "s-all-five",
            zoneDurationsMs = listOf(10_000L, 20_000L, 40_000L, 20_000L, 10_000L)
        )
        val partialZoneSession = buildItem(
            id = "s-partial",
            zoneDurationsMs = listOf(0L, 30_000L, 70_000L, 0L, 0L)
        )

        composeTestRule.activity.setContent {
            SessionHistoryContent(
                uiState = SessionHistoryUiState(
                    sessions = listOf(fiveZoneSession, partialZoneSession),
                    isLoading = false
                )
            )
        }
        composeTestRule.waitForIdle()

        // Full 5-zone session: verify official colors, fractions, bar height, and proportional widths
        val barBounds = composeTestRule.onNodeWithTag(sessionZoneBarTag("s-all-five")).getBoundsInRoot()
        assertEquals(6.dp.value, (barBounds.bottom - barBounds.top).value, 0.5f)
        composeTestRule.onNodeWithTag(sessionZoneBarEmptyTag("s-all-five")).assertDoesNotExist()

        val expectedZones = listOf(
            Triple(1, Color(0xFF64748B), 0.10f), // Zone 1 Slate
            Triple(2, Color(0xFF3B82F6), 0.20f), // Zone 2 Blue
            Triple(3, Color(0xFF10B981), 0.40f), // Zone 3 Green
            Triple(4, Color(0xFFF59E0B), 0.20f), // Zone 4 Amber
            Triple(5, Color(0xFFEF4444), 0.10f)  // Zone 5 Red
        )

        val widthsByZone = mutableMapOf<Int, Float>()
        expectedZones.forEach { (zoneIndex, expectedColor, expectedFraction) ->
            val segmentNode = composeTestRule
                .onNodeWithTag(sessionZoneSegmentTag("s-all-five", zoneIndex))
                .assertIsDisplayed()
                .fetchSemanticsNode()

            assertColorEquals(expectedColor, segmentNode.config[SessionHistoryZoneColorKey])
            assertEquals(expectedFraction, segmentNode.config[SessionHistoryZoneFractionKey], 0.001f)

            val bounds = composeTestRule
                .onNodeWithTag(sessionZoneSegmentTag("s-all-five", zoneIndex))
                .getBoundsInRoot()
            widthsByZone[zoneIndex] = (bounds.right - bounds.left).value
        }

        // Verify actual proportional layout width reflects 40% > 20% > 10%
        assertTrue(
            "Expected Z3 width (${widthsByZone[3]}) > Z2 width (${widthsByZone[2]})",
            widthsByZone.getValue(3) > widthsByZone.getValue(2)
        )
        assertTrue(
            "Expected Z2 width (${widthsByZone[2]}) > Z1 width (${widthsByZone[1]})",
            widthsByZone.getValue(2) > widthsByZone.getValue(1)
        )
        assertEquals(widthsByZone.getValue(1), widthsByZone.getValue(5), 1.5f)

        // Partial zone session: 0ms zones must be omitted (no weight(0f)) while Z2 & Z3 render proportionally
        composeTestRule.onNodeWithTag(SESSION_HISTORY_LIST_TAG)
            .performScrollToNode(hasTestTag(sessionHistoryCardTag("s-partial")))
        composeTestRule.onNodeWithTag(sessionZoneSegmentTag("s-partial", 1)).assertDoesNotExist()
        composeTestRule.onNodeWithTag(sessionZoneSegmentTag("s-partial", 4)).assertDoesNotExist()
        composeTestRule.onNodeWithTag(sessionZoneSegmentTag("s-partial", 5)).assertDoesNotExist()
        composeTestRule.onNodeWithTag(sessionZoneBarEmptyTag("s-partial")).assertDoesNotExist()

        val z2Node = composeTestRule
            .onNodeWithTag(sessionZoneSegmentTag("s-partial", 2))
            .assertIsDisplayed()
            .fetchSemanticsNode()
        val z3Node = composeTestRule
            .onNodeWithTag(sessionZoneSegmentTag("s-partial", 3))
            .assertIsDisplayed()
            .fetchSemanticsNode()

        assertColorEquals(Color(0xFF3B82F6), z2Node.config[SessionHistoryZoneColorKey])
        assertEquals(0.30f, z2Node.config[SessionHistoryZoneFractionKey], 0.001f)
        assertColorEquals(Color(0xFF10B981), z3Node.config[SessionHistoryZoneColorKey])
        assertEquals(0.70f, z3Node.config[SessionHistoryZoneFractionKey], 0.001f)
    }

    @Test
    fun `renders muted bar safely when all zones are zero`() {
        val zeroZoneSession = buildItem(
            id = "s-zero-zones",
            effectiveDurationMs = 0L,
            avgBpm = 0,
            maxBpm = 0,
            status = SessionStatus.COMPLETED_UNEXPECTEDLY,
            zoneDurationsMs = listOf(0L, 0L, 0L, 0L, 0L)
        )

        composeTestRule.activity.setContent {
            SessionHistoryContent(
                uiState = SessionHistoryUiState(
                    sessions = listOf(zeroZoneSession),
                    isLoading = false
                )
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(sessionZoneBarTag("s-zero-zones")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(sessionZoneBarEmptyTag("s-zero-zones")).assertIsDisplayed()
        for (zoneIndex in 1..5) {
            composeTestRule.onNodeWithTag(sessionZoneSegmentTag("s-zero-zones", zoneIndex))
                .assertDoesNotExist()
        }
        composeTestRule.onNodeWithTag(sessionAvgBpmTag("s-zero-zones")).assertTextContains("--")
        composeTestRule.onNodeWithTag(sessionMaxBpmTag("s-zero-zones")).assertTextContains("--")
    }

    @Test
    fun `shows amber badge only on unexpected stop sessions`() {
        val normalSession = buildItem(
            id = "s-normal",
            status = SessionStatus.COMPLETED
        )
        val unexpectedSession = buildItem(
            id = "s-unexpected",
            status = SessionStatus.COMPLETED_UNEXPECTEDLY
        )

        composeTestRule.activity.setContent {
            SessionHistoryContent(
                uiState = SessionHistoryUiState(
                    sessions = listOf(normalSession, unexpectedSession),
                    isLoading = false
                )
            )
        }
        composeTestRule.waitForIdle()

        // Normal completed session: SAVED badge present, warning badge absent
        composeTestRule.onNodeWithTag(sessionSavedBadgeTag("s-normal"))
            .assertIsDisplayed()
            .assertTextEquals("SAVED")
        composeTestRule.onNodeWithTag(sessionUnexpectedBadgeTag("s-normal"))
            .assertDoesNotExist()

        // Unexpectedly completed session: amber warning badge present, SAVED badge absent
        composeTestRule.onNodeWithTag(SESSION_HISTORY_LIST_TAG)
            .performScrollToNode(hasTestTag(sessionHistoryCardTag("s-unexpected")))
        val badgeNode = composeTestRule
            .onNodeWithTag(sessionUnexpectedBadgeTag("s-unexpected"))
            .assertIsDisplayed()
            .assertTextEquals("UNEXPECTED STOP")
            .fetchSemanticsNode()
        assertColorEquals(Color(0xFFF59E0B), badgeNode.config[SessionHistoryBadgeColorKey])
        composeTestRule.onNodeWithTag(sessionSavedBadgeTag("s-unexpected"))
            .assertDoesNotExist()
    }

    @Test
    fun `renders empty state when no sessions exist`() {
        composeTestRule.activity.setContent {
            SessionHistoryContent(
                uiState = SessionHistoryUiState(
                    sessions = emptyList(),
                    isLoading = false
                )
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("SESSION HISTORY").assertIsDisplayed()
        composeTestRule.onNodeWithTag(SESSION_HISTORY_EMPTY_STATE_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "No sessions recorded yet. Pair your Heart Rate Monitor and start your first session."
        ).assertIsDisplayed()
        composeTestRule.onNodeWithTag(SESSION_HISTORY_SUMMARY_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(SESSION_HISTORY_LIST_TAG).assertDoesNotExist()
    }

    @Test
    fun `tapping session card invokes onSessionClick with ID`() {
        val clickedIds = mutableListOf<String>()
        val first = buildItem(id = "session-alpha")
        val second = buildItem(id = "session-beta")

        composeTestRule.activity.setContent {
            SessionHistoryContent(
                uiState = SessionHistoryUiState(
                    sessions = listOf(first, second),
                    isLoading = false
                ),
                actions = SessionHistoryActions(onSessionClick = { clickedIds.add(it) })
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(sessionHistoryCardTag("session-alpha")).performClick()
        composeTestRule.onNodeWithTag(SESSION_HISTORY_LIST_TAG)
            .performScrollToNode(hasTestTag(sessionHistoryCardTag("session-beta")))
        composeTestRule.onNodeWithTag(sessionHistoryCardTag("session-beta")).performClick()

        assertEquals(listOf("session-alpha", "session-beta"), clickedIds)
    }

    @Test
    fun `RootNavHost navigates to Session Detail and back`() {
        val profileDao = FakeUserProfileDao(
            initialProfile = UserProfileEntity(
                id = UserProfileEntity.SINGLETON_ID,
                birthYear = 1990,
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
        val sessionId = "session-detail-target-42"
        val sessionDao = FakeSessionDao(
            listOf(
                SessionEntity(
                    id = sessionId,
                    startEpochMs = Instant.parse("2026-11-09T07:15:00Z").toEpochMilli(),
                    endEpochMs = Instant.parse("2026-11-09T08:00:12Z").toEpochMilli(),
                    elapsedDurationMs = 2_712_000L,
                    effectiveDurationMs = 2_712_000L,
                    pausedDurationMs = 0L,
                    status = SessionStatus.COMPLETED,
                    hrmMacAddress = "D4:36:39:88:21:9B",
                    userAge = 36,
                    calculatedHrmax = 183,
                    restingHr = 58,
                    calculationMode = ZoneCalculationMode.KARVONEN,
                    avgBpm = 148,
                    maxBpm = 175,
                    minBpm = 104,
                    zone1DurationMs = 271_200L,
                    zone2DurationMs = 813_600L,
                    zone3DurationMs = 1_084_800L,
                    zone4DurationMs = 542_400L,
                    zone5DurationMs = 0L
                ),
                SessionEntity(
                    id = "session-recovered-7",
                    startEpochMs = Instant.parse("2026-11-07T17:30:00Z").toEpochMilli(),
                    endEpochMs = Instant.parse("2026-11-07T18:00:00Z").toEpochMilli(),
                    elapsedDurationMs = 1_800_000L,
                    effectiveDurationMs = 1_800_000L,
                    pausedDurationMs = 0L,
                    status = SessionStatus.COMPLETED_UNEXPECTEDLY,
                    hrmMacAddress = "AA:BB:CC:DD:EE:99",
                    userAge = 36,
                    calculatedHrmax = 183,
                    restingHr = 58,
                    calculationMode = ZoneCalculationMode.KARVONEN,
                    avgBpm = 136,
                    maxBpm = 158,
                    minBpm = 98,
                    zone1DurationMs = 300_000L,
                    zone2DurationMs = 1_500_000L,
                    zone3DurationMs = 0L,
                    zone4DurationMs = 0L,
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

        // Switch to HISTORY tab
        composeTestRule.onNodeWithText("HISTORY").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("SESSION HISTORY").assertIsDisplayed()
        composeTestRule.onNodeWithTag(sessionHistoryCardTag(sessionId)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Chest Strap").assertIsDisplayed()
        composeTestRule.onNodeWithTag(sessionDurationTag(sessionId)).assertTextEquals("45:12")

        // Tap session card -> navigates to Session Detail destination displaying session telemetry
        composeTestRule.onNodeWithTag(sessionHistoryCardTag(sessionId)).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(SESSION_DETAIL_SCREEN_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("SESSION DETAIL").assertIsDisplayed()
        composeTestRule.onNodeWithTag(SESSION_DETAIL_EFFECTIVE_DURATION_TAG)
            .assertIsDisplayed()
            .assertTextEquals("00:45:12")

        // Back button returns to Session History list cleanly
        composeTestRule.onNodeWithContentDescription("Navigate back").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(SESSION_DETAIL_SCREEN_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithText("SESSION HISTORY").assertIsDisplayed()
        composeTestRule.onNodeWithTag(sessionHistoryCardTag(sessionId)).assertIsDisplayed()
    }

    @Test
    fun `rendered UI uses strict glossary terminology`() {
        val completed = buildItem(id = "s-glossary-1", status = SessionStatus.COMPLETED)
        val unexpected = buildItem(id = "s-glossary-2", status = SessionStatus.COMPLETED_UNEXPECTEDLY)

        composeTestRule.activity.setContent {
            SessionHistoryContent(
                uiState = SessionHistoryUiState(
                    sessions = listOf(completed, unexpected),
                    isLoading = false
                )
            )
        }
        composeTestRule.waitForIdle()

        val allNodes = collectSemanticsTree(
            composeTestRule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        )
        val forbiddenTerms = listOf(
            "max bpm",
            "max heart rate",
            "peak heart rate",
            "pulse",
            "saved sensor",
            "workout"
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

    private fun buildItem(
        id: String,
        formattedDate: String = "Monday, Nov 9, 2026",
        formattedStartTime: String = "07:15 AM",
        effectiveDurationMs: Long = 2_712_000L,
        avgBpm: Int = 146,
        maxBpm: Int = 174,
        hrmDisplayName: String = "Chest Strap",
        status: SessionStatus = SessionStatus.COMPLETED,
        zoneDurationsMs: List<Long> = listOf(100_000L, 350_000L, 400_000L, 150_000L, 0L)
    ): SessionHistoryItem {
        val total = zoneDurationsMs.sum()
        val segments = HeartZone.entries.mapIndexed { index, zone ->
            val duration = zoneDurationsMs[index]
            ZoneDistributionSegment(
                zone = zone,
                durationMs = duration,
                fraction = if (total > 0L) (duration.toDouble() / total.toDouble()).toFloat() else 0f
            )
        }
        val dominant = if (total > 0L) segments.maxByOrNull { it.durationMs } else null
        return SessionHistoryItem(
            id = id,
            startEpochMs = 1_794_208_500_000L,
            formattedDate = formattedDate,
            formattedStartTime = formattedStartTime,
            effectiveDurationMs = effectiveDurationMs,
            formattedEffectiveDuration = formatSessionEffectiveDuration(effectiveDurationMs),
            avgBpm = avgBpm,
            maxBpm = maxBpm,
            hrmDisplayName = hrmDisplayName,
            status = status,
            isUnexpectedCompletion = status == SessionStatus.COMPLETED_UNEXPECTEDLY,
            zoneSegments = segments,
            dominantZone = dominant
        )
    }

    private fun assertColorEquals(expected: Color, actual: Color) {
        assertEquals(expected.red, actual.red, 0.002f)
        assertEquals(expected.green, actual.green, 0.002f)
        assertEquals(expected.blue, actual.blue, 0.002f)
        assertEquals(expected.alpha, actual.alpha, 0.002f)
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
}
