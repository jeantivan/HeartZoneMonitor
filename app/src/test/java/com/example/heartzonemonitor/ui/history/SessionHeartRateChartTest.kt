package com.example.heartzonemonitor.ui.history

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.SessionEntity
import com.example.heartzonemonitor.data.local.entity.SessionStatus
import com.example.heartzonemonitor.domain.model.HeartRateSample
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import java.time.Instant
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
class SessionHeartRateChartTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `renders chart canvas with five frozen zone thresholds LTTB indicator and empty state`() {
        val baseTs = Instant.parse("2026-11-11T17:00:00Z").toEpochMilli()
        val manySamples = (0 until 1_200).map { sec ->
            HeartRateSample(
                id = sec + 1L,
                sessionId = "session-chart-ui",
                timestampEpochMs = baseTs + sec * 1_000L,
                bpm = 122 + (sec % 50),
                zoneIndex = 3,
                sensorContact = true
            )
        }
        val model = buildDetailModelWithSamples(
            startEpochMs = baseTs,
            elapsedDurationMs = 1_199_000L,
            samples = manySamples
        )

        composeTestRule.activity.setContent {
            SessionDetailContent(
                uiState = SessionDetailUiState(isLoading = false, session = model)
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(SESSION_DETAIL_CHART_CARD_TAG)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(SESSION_DETAIL_CHART_CANVAS_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(SESSION_DETAIL_CHART_EMPTY_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(SESSION_DETAIL_CHART_SUBTITLE_TAG)
            .assertTextContains("LTTB downsampled", substring = true)

        // Frozen Karvonen thresholds for age 38, restingHr 58, HRmax 181: Z1=120..Z5=169
        val expectedThresholds = listOf("Z1 120", "Z2 132", "Z3 144", "Z4 156", "Z5 169")
        expectedThresholds.forEachIndexed { idx, label ->
            composeTestRule
                .onNodeWithTag(sessionDetailChartThresholdTag(idx + 1))
                .assertIsDisplayed()
                .assertTextEquals(label)
        }

        // Empty sample session renders empty state safely
        val emptyModel = buildDetailModelWithSamples(
            startEpochMs = baseTs,
            elapsedDurationMs = 60_000L,
            samples = emptyList()
        )
        composeTestRule.activity.setContent {
            SessionDetailContent(
                uiState = SessionDetailUiState(isLoading = false, session = emptyModel)
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(SESSION_DETAIL_CHART_CARD_TAG)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(SESSION_DETAIL_CHART_EMPTY_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("No Heart Rate Samples recorded for this Session.")
            .assertIsDisplayed()
    }

    @Test
    fun `horizontal drag scrubs crosshair and floating tooltip across samples and dismisses on release`() {
        val baseTs = Instant.parse("2026-11-11T17:00:00Z").toEpochMilli()
        val samples = listOf(
            HeartRateSample(id = 1L, sessionId = "session-chart-ui", timestampEpochMs = baseTs, bpm = 122, zoneIndex = 1, sensorContact = true),
            HeartRateSample(id = 2L, sessionId = "session-chart-ui", timestampEpochMs = baseTs + 10 * 60_000L, bpm = 138, zoneIndex = 2, sensorContact = true),
            HeartRateSample(id = 3L, sessionId = "session-chart-ui", timestampEpochMs = baseTs + 20 * 60_000L, bpm = 150, zoneIndex = 3, sensorContact = true),
            HeartRateSample(id = 4L, sessionId = "session-chart-ui", timestampEpochMs = baseTs + 30 * 60_000L, bpm = 164, zoneIndex = 4, sensorContact = true),
            HeartRateSample(id = 5L, sessionId = "session-chart-ui", timestampEpochMs = baseTs + 40 * 60_000L, bpm = 176, zoneIndex = 5, sensorContact = true)
        )
        val model = buildDetailModelWithSamples(
            startEpochMs = baseTs,
            elapsedDurationMs = 40 * 60_000L,
            samples = samples
        )

        composeTestRule.activity.setContent {
            SessionDetailContent(
                uiState = SessionDetailUiState(isLoading = false, session = model)
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(SESSION_DETAIL_CHART_CARD_TAG)
            .performScrollTo()
            .assertIsDisplayed()

        // Initially no crosshair or tooltip
        composeTestRule.onNodeWithTag(SESSION_DETAIL_CHART_TOOLTIP_TAG).assertDoesNotExist()
        val initialCanvasNode = composeTestRule
            .onNodeWithTag(SESSION_DETAIL_CHART_CANVAS_TAG)
            .fetchSemanticsNode()
        assertFalse(initialCanvasNode.config[SessionDetailChartCrosshairVisibleKey])

        val density = composeTestRule.density
        val plotLeftPx = with(density) { CHART_PLOT_LEFT_PADDING.toPx() }
        val plotRightPx = initialCanvasNode.size.width.toFloat() -
            with(density) { CHART_PLOT_RIGHT_PADDING.toPx() }
        val plotWidthPx = plotRightPx - plotLeftPx
        val midY = initialCanvasNode.size.height / 2f
        val x50 = plotLeftPx + plotWidthPx * 0.50f
        val x75 = plotLeftPx + plotWidthPx * 0.75f

        // Begin horizontal drag and move to 50% (20:00, 150 BPM, Zone 3 Emerald)
        composeTestRule.onNodeWithTag(SESSION_DETAIL_CHART_CANVAS_TAG).performTouchInput {
            down(Offset(plotLeftPx, midY))
            moveTo(Offset(x50, midY))
        }
        composeTestRule.waitForIdle()

        val midCanvasNode = composeTestRule
            .onNodeWithTag(SESSION_DETAIL_CHART_CANVAS_TAG)
            .fetchSemanticsNode()
        assertTrue(midCanvasNode.config[SessionDetailChartCrosshairVisibleKey])
        val midCrosshairX = midCanvasNode.config[SessionDetailChartCrosshairXKey]
        assertEquals(x50, midCrosshairX, 4f)

        composeTestRule.onNodeWithTag(SESSION_DETAIL_CHART_TOOLTIP_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(SESSION_DETAIL_CHART_TOOLTIP_ELAPSED_TAG)
            .assertTextEquals("20:00")
        composeTestRule.onNodeWithTag(SESSION_DETAIL_CHART_TOOLTIP_BPM_TAG)
            .assertTextEquals("150 BPM")
        val z3Badge = composeTestRule
            .onNodeWithTag(SESSION_DETAIL_CHART_TOOLTIP_ZONE_TAG)
            .assertIsDisplayed()
            .assertTextEquals("ZONE 3")
            .fetchSemanticsNode()
        assertColorEquals(Color(0xFF10B981), z3Badge.config[SessionDetailChartTooltipZoneColorKey])

        // Continue dragging to 75% (30:00, 164 BPM, Zone 4 Amber)
        composeTestRule.onNodeWithTag(SESSION_DETAIL_CHART_CANVAS_TAG).performTouchInput {
            moveTo(Offset(x75, midY))
        }
        composeTestRule.waitForIdle()

        val lateCanvasNode = composeTestRule
            .onNodeWithTag(SESSION_DETAIL_CHART_CANVAS_TAG)
            .fetchSemanticsNode()
        val lateCrosshairX = lateCanvasNode.config[SessionDetailChartCrosshairXKey]
        assertTrue("Expected crosshair X ($lateCrosshairX) to advance right of $midCrosshairX", lateCrosshairX > midCrosshairX)
        assertEquals(x75, lateCrosshairX, 4f)

        composeTestRule.onNodeWithTag(SESSION_DETAIL_CHART_TOOLTIP_ELAPSED_TAG)
            .assertTextEquals("30:00")
        composeTestRule.onNodeWithTag(SESSION_DETAIL_CHART_TOOLTIP_BPM_TAG)
            .assertTextEquals("164 BPM")
        val z4Badge = composeTestRule
            .onNodeWithTag(SESSION_DETAIL_CHART_TOOLTIP_ZONE_TAG)
            .assertIsDisplayed()
            .assertTextEquals("ZONE 4")
            .fetchSemanticsNode()
        assertColorEquals(Color(0xFFF59E0B), z4Badge.config[SessionDetailChartTooltipZoneColorKey])

        // Lift finger -> crosshair and floating tooltip dismiss smoothly
        composeTestRule.onNodeWithTag(SESSION_DETAIL_CHART_CANVAS_TAG).performTouchInput {
            up()
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(SESSION_DETAIL_CHART_TOOLTIP_TAG).assertDoesNotExist()
        val dismissedCanvasNode = composeTestRule
            .onNodeWithTag(SESSION_DETAIL_CHART_CANVAS_TAG)
            .fetchSemanticsNode()
        assertFalse(dismissedCanvasNode.config[SessionDetailChartCrosshairVisibleKey])

        // Releasing touch leaves parent vertical scroll container unlocked
        composeTestRule.onNodeWithTag(SESSION_DETAIL_SNAPSHOT_CARD_TAG)
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun assertColorEquals(expected: Color, actual: Color) {
        assertEquals(expected.red, actual.red, 0.002f)
        assertEquals(expected.green, actual.green, 0.002f)
        assertEquals(expected.blue, actual.blue, 0.002f)
        assertEquals(expected.alpha, actual.alpha, 0.002f)
    }

    private fun buildDetailModelWithSamples(
        startEpochMs: Long,
        elapsedDurationMs: Long,
        samples: List<HeartRateSample>
    ): SessionDetailModel {
        val hrm = RememberedHrmEntity(
            macAddress = "D4:36:39:88:21:9B",
            advertisedName = "COOSPO H808S",
            customNickname = "Chest Strap",
            lastSeenTimestamp = 1_000L,
            isActive = true
        )
        val entity = SessionEntity(
            id = "session-chart-ui",
            startEpochMs = startEpochMs,
            endEpochMs = startEpochMs + elapsedDurationMs,
            elapsedDurationMs = elapsedDurationMs,
            effectiveDurationMs = elapsedDurationMs,
            pausedDurationMs = 0L,
            status = SessionStatus.COMPLETED,
            hrmMacAddress = hrm.macAddress,
            userAge = 38,
            calculatedHrmax = 181,
            restingHr = 58,
            calculationMode = ZoneCalculationMode.KARVONEN,
            avgBpm = 146,
            maxBpm = 174,
            minBpm = 118,
            zone1DurationMs = 200_000L,
            zone2DurationMs = 300_000L,
            zone3DurationMs = 400_000L,
            zone4DurationMs = 200_000L,
            zone5DurationMs = 99_000L
        )
        return entity.toSessionDetailModel(
            hrmsByNormalizedMac = mapOf(hrm.macAddress to hrm),
            samples = samples
        )
    }
}
