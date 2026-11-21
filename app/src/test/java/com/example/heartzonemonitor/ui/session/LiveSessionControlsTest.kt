package com.example.heartzonemonitor.ui.session

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.heartzonemonitor.data.ble.FakeBleTransport
import com.example.heartzonemonitor.data.ble.HrmGattClient
import com.example.heartzonemonitor.data.local.FakeHeartRateSampleDao
import com.example.heartzonemonitor.data.local.FakeRememberedHrmDao
import com.example.heartzonemonitor.data.local.FakeSessionDao
import com.example.heartzonemonitor.data.local.FakeUserProfileDao
import com.example.heartzonemonitor.data.local.HeartRateSampleBuffer
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import com.example.heartzonemonitor.data.session.PauseReason
import com.example.heartzonemonitor.data.session.SessionRepository
import com.example.heartzonemonitor.domain.model.HeartZone
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import com.example.heartzonemonitor.domain.timing.FakeSessionClock
import com.example.heartzonemonitor.domain.timing.MonotonicDurationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveSessionControlsTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var testScope: CoroutineScope

    @Before
    fun setUp() {
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun `formatEffectiveDuration formats mm ss and HH mm ss`() {
        assertEquals("00:00", formatEffectiveDuration(0L))
        assertEquals("00:00", formatEffectiveDuration(-5_000L))
        assertEquals("00:00", formatEffectiveDuration(999L))
        assertEquals("00:01", formatEffectiveDuration(1_000L))
        assertEquals("05:42", formatEffectiveDuration(342_000L))
        assertEquals("59:59", formatEffectiveDuration(3_599_999L))
        assertEquals("01:00:00", formatEffectiveDuration(3_600_000L))
        assertEquals("01:05:09", formatEffectiveDuration(3_909_000L))
        assertEquals("10:00:00", formatEffectiveDuration(36_000_000L))
    }

    @Test
    fun `zone progress bar updates segment colors across transitions`() {
        var currentZone by mutableStateOf<HeartZone?>(null)

        composeTestRule.activity.setContent {
            LiveSessionZoneProgressBar(currentZone = currentZone)
        }
        composeTestRule.waitForIdle()

        // Standby (null zone): all 5 segments use translucent black (alpha = 0.25f)
        assertSegmentColors(currentZone = null)

        // Upward transitions Z1 -> Z5, then downward Z5 -> Z3 -> Z1 -> null
        val transitions: List<HeartZone?> = listOf(
            HeartZone.ZONE_1,
            HeartZone.ZONE_2,
            HeartZone.ZONE_3,
            HeartZone.ZONE_4,
            HeartZone.ZONE_5,
            HeartZone.ZONE_3,
            HeartZone.ZONE_1,
            null
        )

        transitions.forEach { zone ->
            composeTestRule.runOnIdle {
                currentZone = zone
            }
            composeTestRule.waitForIdle()
            assertSegmentColors(currentZone = zone)
        }
    }

    @Test
    fun `zone progress bar displays Z1 to Z5 labels under segments`() {
        composeTestRule.activity.setContent {
            LiveSessionZoneProgressBar(currentZone = HeartZone.ZONE_3)
        }
        composeTestRule.waitForIdle()

        var previousSegmentRight = (-1).dp
        for (index in 1..5) {
            val segmentNode = composeTestRule.onNodeWithTag(liveSessionZoneSegmentTag(index))
            val labelNode = composeTestRule.onNodeWithTag(liveSessionZoneLabelTag(index))

            segmentNode.assertIsDisplayed()
            labelNode
                .assertIsDisplayed()
                .assertTextEquals("Z$index")

            val segmentBounds = segmentNode.getBoundsInRoot()
            val labelBounds = labelNode.getBoundsInRoot()

            assertTrue(
                "Segment $index should be positioned to the right of segment ${index - 1}",
                segmentBounds.left > previousSegmentRight
            )
            assertTrue(
                "Label Z$index top (${labelBounds.top}) must sit below segment $index bottom (${segmentBounds.bottom})",
                labelBounds.top >= segmentBounds.bottom
            )
            assertTrue(
                "Label Z$index left (${labelBounds.left}) must align inside segment $index left (${segmentBounds.left})",
                labelBounds.left >= segmentBounds.left
            )
            assertTrue(
                "Label Z$index right (${labelBounds.right}) must align inside segment $index right (${segmentBounds.right})",
                labelBounds.right <= segmentBounds.right
            )

            previousSegmentRight = segmentBounds.right
        }
    }

    @Test
    fun `duration card shows only 52sp monospace Effective Duration`() {
        var durationMs by mutableStateOf(0L)

        composeTestRule.activity.setContent {
            LiveSessionContent(
                uiState = LiveSessionUiState(
                    currentBpm = 148,
                    currentZone = HeartZone.ZONE_3,
                    isRecording = true,
                    effectiveDurationMs = durationMs
                )
            )
        }

        val samples = listOf(
            0L to "00:00",
            342_000L to "05:42",
            3_909_000L to "01:05:09"
        )

        samples.forEach { (ms, expectedText) ->
            composeTestRule.runOnIdle {
                durationMs = ms
            }
            composeTestRule.waitForIdle()

            val cardNode = composeTestRule.onNodeWithTag(LIVE_SESSION_DURATION_CARD_TAG).fetchSemanticsNode()
            assertColorClose(
                expected = Color.Black.copy(alpha = 0.32f),
                actual = cardNode.config[LiveSessionContainerColorKey]
            )

            composeTestRule.onNodeWithTag(LIVE_SESSION_DURATION_TEXT_TAG)
                .assertIsDisplayed()
                .assertTextEquals(expectedText)

            val layout = getTextLayoutResult(LIVE_SESSION_DURATION_TEXT_TAG)
            val style = layout.layoutInput.style
            assertEquals(52.sp, style.fontSize)
            assertEquals(FontWeight.Bold, style.fontWeight)
            assertEquals(FontFamily.Monospace, style.fontFamily)
            assertEquals(Color.White, style.color)
            assertEquals(1, layout.lineCount)

            // Verify zero elapsed or paused clock text anywhere in LiveSessionContent
            val allText = collectAllText(composeTestRule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
            val colonTokens = allText.filter { it.contains(":") }
            assertEquals("Only a single Effective Duration clock may render", listOf(expectedText), colonTokens)
            val combinedLower = allText.joinToString(" ").lowercase()
            assertFalse("Must omit elapsed clock text", combinedLower.contains("elapsed"))
            assertFalse("Must omit paused clock text", combinedLower.contains("paused"))
        }
    }

    @Test
    fun `active controls enforce 56dp height styling and callbacks`() {
        var isRecording by mutableStateOf(true)
        var isPaused by mutableStateOf(false)
        var pauseClicks = 0
        var resumeClicks = 0
        var stopClicks = 0

        composeTestRule.activity.setContent {
            LiveSessionControlButtons(
                isRecording = isRecording,
                isPaused = isPaused,
                canStartSession = false,
                actions = LiveSessionActions(
                    onPauseSession = { pauseClicks++ },
                    onResumeSession = { resumeClicks++ },
                    onStopSession = { stopClicks++ }
                )
            )
        }
        composeTestRule.waitForIdle()

        // Recording mode -> PAUSE + STOP & SAVE
        val pauseNode = composeTestRule.onNodeWithTag(LIVE_SESSION_PAUSE_RESUME_BUTTON_TAG)
        pauseNode
            .assertIsDisplayed()
            .assertHeightIsEqualTo(56.dp)
            .assertTextEquals("PAUSE")
        assertColorClose(
            expected = Color.White,
            actual = pauseNode.fetchSemanticsNode().config[LiveSessionBorderColorKey]
        )

        val stopNode = composeTestRule.onNodeWithTag(LIVE_SESSION_STOP_SAVE_BUTTON_TAG)
        stopNode
            .assertIsDisplayed()
            .assertHeightIsEqualTo(56.dp)
            .assertTextEquals("STOP & SAVE")
        assertColorClose(
            expected = Color.Black.copy(alpha = 0.32f),
            actual = stopNode.fetchSemanticsNode().config[LiveSessionContainerColorKey]
        )

        pauseNode.performClick()
        assertEquals(1, pauseClicks)
        assertEquals(0, resumeClicks)
        assertEquals(0, stopClicks)

        stopNode.performClick()
        assertEquals(1, stopClicks)

        // Paused mode -> RESUME + STOP & SAVE
        composeTestRule.runOnIdle {
            isRecording = false
            isPaused = true
        }
        composeTestRule.waitForIdle()

        val resumeNode = composeTestRule.onNodeWithTag(LIVE_SESSION_PAUSE_RESUME_BUTTON_TAG)
        resumeNode
            .assertIsDisplayed()
            .assertHeightIsAtLeast(56.dp)
            .assertTextEquals("RESUME")
        assertColorClose(
            expected = Color.White,
            actual = resumeNode.fetchSemanticsNode().config[LiveSessionBorderColorKey]
        )

        resumeNode.performClick()
        assertEquals(1, pauseClicks)
        assertEquals(1, resumeClicks)

        stopNode.performClick()
        assertEquals(2, stopClicks)
    }

    @Test
    fun `idle controls render 56dp Start Session button safely`() {
        var canStart by mutableStateOf(false)
        var startClicks = 0

        composeTestRule.activity.setContent {
            LiveSessionControlButtons(
                isRecording = false,
                isPaused = false,
                canStartSession = canStart,
                actions = LiveSessionActions(
                    onStartSession = { startClicks++ }
                )
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("start_session_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag(LIVE_SESSION_PAUSE_RESUME_BUTTON_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(LIVE_SESSION_STOP_SAVE_BUTTON_TAG).assertDoesNotExist()

        val startButton = composeTestRule.onNodeWithTag(LIVE_SESSION_START_BUTTON_TAG)
        startButton
            .assertIsDisplayed()
            .assertHeightIsEqualTo(56.dp)
            .assertTextEquals("START SESSION")
            .assertIsNotEnabled()

        composeTestRule.runOnIdle {
            canStart = true
        }
        composeTestRule.waitForIdle()

        startButton
            .assertIsEnabled()
            .performClick()
        assertEquals(1, startClicks)
    }

    @Test
    fun `micro session discard dialog dispatches Discard and Save`() {
        var showPrompt by mutableStateOf(false)
        var discardClicks = 0
        var saveClicks = 0

        composeTestRule.activity.setContent {
            LiveSessionContent(
                uiState = LiveSessionUiState(
                    currentBpm = 148,
                    currentZone = HeartZone.ZONE_3,
                    isRecording = true,
                    effectiveDurationMs = 4_000L,
                    pendingMicroSessionDiscardPrompt = showPrompt
                ),
                actions = LiveSessionActions(
                    onDiscardSession = { discardClicks++ },
                    onConfirmSaveMicroSession = { saveClicks++ }
                )
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(LIVE_SESSION_MICRO_SESSION_DIALOG_TAG).assertDoesNotExist()

        composeTestRule.runOnIdle {
            showPrompt = true
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(LIVE_SESSION_MICRO_SESSION_DIALOG_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(LIVE_SESSION_DISCARD_SESSION_BUTTON_TAG)
            .assertIsDisplayed()
            .assertTextEquals("Discard Session")
            .performClick()
        assertEquals(1, discardClicks)
        assertEquals(0, saveClicks)

        composeTestRule.onNodeWithTag(LIVE_SESSION_SAVE_MICRO_SESSION_BUTTON_TAG)
            .assertIsDisplayed()
            .assertTextEquals("Save Session")
            .performClick()
        assertEquals(1, discardClicks)
        assertEquals(1, saveClicks)
    }

    @Test
    fun `narrow 320dp viewport fits HH mm ss and 56dp controls`() {
        var boxWidth by mutableStateOf(320.dp)
        var boxHeight by mutableStateOf(568.dp)

        composeTestRule.activity.setContent {
            Box(
                modifier = Modifier
                    .width(boxWidth)
                    .height(boxHeight)
            ) {
                LiveSessionContent(
                    uiState = LiveSessionUiState(
                        currentBpm = 188,
                        currentZone = HeartZone.ZONE_5,
                        isRecording = true,
                        effectiveDurationMs = 3_909_000L // 01:05:09
                    )
                )
            }
        }
        composeTestRule.waitForIdle()

        val rootBounds = composeTestRule.onNodeWithTag(LIVE_SESSION_BACKGROUND_TAG).getBoundsInRoot()
        val badgeBounds = composeTestRule.onNodeWithTag(LIVE_SESSION_ZONE_BADGE_TAG).getBoundsInRoot()
        val barBounds = composeTestRule.onNodeWithTag(LIVE_SESSION_ZONE_PROGRESS_BAR_TAG).getBoundsInRoot()
        val cardBounds = composeTestRule.onNodeWithTag(LIVE_SESSION_DURATION_CARD_TAG).getBoundsInRoot()
        val pauseBounds = composeTestRule.onNodeWithTag(LIVE_SESSION_PAUSE_RESUME_BUTTON_TAG).getBoundsInRoot()
        val stopBounds = composeTestRule.onNodeWithTag(LIVE_SESSION_STOP_SAVE_BUTTON_TAG).getBoundsInRoot()

        assertTrue("Progress bar must sit below zone badge", barBounds.top >= badgeBounds.bottom)
        assertTrue("Duration card must sit below progress bar", cardBounds.top >= barBounds.bottom)
        assertTrue("Controls must sit below duration card", pauseBounds.top >= cardBounds.bottom)
        assertTrue("Stop & Save button must sit to the right of Pause button", stopBounds.left >= pauseBounds.right)
        assertTrue("Controls bottom must fit inside 568dp viewport", pauseBounds.bottom <= rootBounds.bottom)
        assertTrue("Stop & Save right must fit inside 320dp viewport", stopBounds.right <= rootBounds.right)

        val narrowDurationLayout = getTextLayoutResult(LIVE_SESSION_DURATION_TEXT_TAG)
        val narrowLineWidth = narrowDurationLayout.getLineRight(0) - narrowDurationLayout.getLineLeft(0)
        assertEquals(52.sp, narrowDurationLayout.layoutInput.style.fontSize)
        assertEquals(1, narrowDurationLayout.lineCount)
        assertTrue(
            "HH:mm:ss duration lineWidth ($narrowLineWidth) must fit within maxWidth (${narrowDurationLayout.layoutInput.constraints.maxWidth}) at 320dp",
            narrowLineWidth > 0f && narrowLineWidth <= narrowDurationLayout.layoutInput.constraints.maxWidth.toFloat()
        )
        assertFalse("HH:mm:ss duration must not overflow vertically at 320dp", narrowDurationLayout.didOverflowHeight)

        // Standard 411dp phone also renders full 52sp HH:mm:ss cleanly
        composeTestRule.runOnIdle {
            boxWidth = 411.dp
            boxHeight = 891.dp
        }
        composeTestRule.waitForIdle()

        val standardDurationLayout = getTextLayoutResult(LIVE_SESSION_DURATION_TEXT_TAG)
        val standardLineWidth = standardDurationLayout.getLineRight(0) - standardDurationLayout.getLineLeft(0)
        assertEquals(52.sp, standardDurationLayout.layoutInput.style.fontSize)
        assertEquals(1, standardDurationLayout.lineCount)
        assertTrue(
            "HH:mm:ss duration lineWidth ($standardLineWidth) must fit within maxWidth (${standardDurationLayout.layoutInput.constraints.maxWidth}) at 411dp",
            standardLineWidth > 0f && standardLineWidth <= standardDurationLayout.layoutInput.constraints.maxWidth.toFloat()
        )
    }

    @Test
    fun `LiveSessionScreen ticks Effective Duration and wires controls`() {
        val activeHrm = RememberedHrmEntity(
            macAddress = ACTIVE_HRM_MAC,
            advertisedName = "COOSPO H808S",
            customNickname = "Chest Strap",
            lastSeenTimestamp = 1_700_000_000_000L,
            batteryLevel = 95,
            isActive = true
        )
        val profile = UserProfileEntity(
            id = UserProfileEntity.SINGLETON_ID,
            birthYear = 1996,
            restingHr = null,
            customHrmax = 200,
            calculationMode = ZoneCalculationMode.PERCENT_HRMAX,
            updatedAtEpochMs = 1_700_000_000_000L
        )
        val bleTransport = FakeBleTransport()
        val gattClient = HrmGattClient(bleTransport = bleTransport, scope = testScope)
        val sampleDao = FakeHeartRateSampleDao()
        val sessionDao = FakeSessionDao(sampleDao = sampleDao)
        val hrmDao = FakeRememberedHrmDao(listOf(activeHrm))
        val profileDao = FakeUserProfileDao(initialProfile = profile)
        val clock = FakeSessionClock(initialRealtimeMs = 10_000L, initialEpochMs = 1_700_000_000_000L)
        val sampleBuffer = HeartRateSampleBuffer(sampleDao = sampleDao, scope = testScope)
        val repository = SessionRepository(
            bleTransport = bleTransport,
            rememberedHrmDao = hrmDao,
            userProfileDao = profileDao,
            sessionDao = sessionDao,
            sampleDao = sampleDao,
            scope = testScope,
            hrmGattClient = gattClient,
            clock = clock,
            durationEngine = MonotonicDurationEngine(clock),
            sampleBuffer = sampleBuffer,
            currentYearProvider = { 2026 }
        )

        try {
            val viewModel = LiveSessionViewModel(repository)

            composeTestRule.activity.setContent {
                LiveSessionScreen(viewModel = viewModel)
            }

            runBlocking {
                bleTransport.connect(ACTIVE_HRM_MAC)
                bleTransport.emitNotification(
                    macAddress = ACTIVE_HRM_MAC,
                    value = FakeBleTransport.buildHeartRateMeasurementPacket(
                        bpm = 148,
                        sensorContactSupported = true,
                        sensorContactDetected = true
                    ),
                    timestampEpochMs = clock.currentEpochMs()
                )
            }
            composeTestRule.waitForIdle()

            // Idle: Start Session enabled, Zone 3 highlighted
            assertSegmentColors(currentZone = HeartZone.ZONE_3)
            composeTestRule.onNodeWithTag(LIVE_SESSION_DURATION_TEXT_TAG).assertTextEquals("00:00")
            composeTestRule.onNodeWithTag(LIVE_SESSION_START_BUTTON_TAG)
                .assertIsEnabled()
                .performClick()
            composeTestRule.waitForIdle()

            // Recording: ticker updates Effective Duration each second
            composeTestRule.onNodeWithTag(LIVE_SESSION_PAUSE_RESUME_BUTTON_TAG).assertTextEquals("PAUSE")
            clock.advanceBothBy(2_000L)
            composeTestRule.mainClock.advanceTimeBy(1_000L)
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag(LIVE_SESSION_DURATION_TEXT_TAG).assertTextEquals("00:02")

            // Pause via button: Effective Duration stays frozen as clock advances
            composeTestRule.onNodeWithTag(LIVE_SESSION_PAUSE_RESUME_BUTTON_TAG).performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag(LIVE_SESSION_PAUSE_RESUME_BUTTON_TAG).assertTextEquals("RESUME")
            assertEquals(PauseReason.USER_MANUAL, viewModel.uiState.value.pauseReason)

            clock.advanceBothBy(10_000L)
            composeTestRule.mainClock.advanceTimeBy(2_000L)
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag(LIVE_SESSION_DURATION_TEXT_TAG).assertTextEquals("00:02")

            // Resume via button and advance another 2 seconds -> 00:04
            composeTestRule.onNodeWithTag(LIVE_SESSION_PAUSE_RESUME_BUTTON_TAG).performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag(LIVE_SESSION_PAUSE_RESUME_BUTTON_TAG).assertTextEquals("PAUSE")

            clock.advanceBothBy(2_000L)
            composeTestRule.mainClock.advanceTimeBy(1_000L)
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag(LIVE_SESSION_DURATION_TEXT_TAG).assertTextEquals("00:04")

            // Stop & Save while under 10s -> micro-session discard dialog -> Discard
            composeTestRule.onNodeWithTag(LIVE_SESSION_STOP_SAVE_BUTTON_TAG).performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag(LIVE_SESSION_MICRO_SESSION_DIALOG_TAG).assertIsDisplayed()
            composeTestRule.onNodeWithTag(LIVE_SESSION_DISCARD_SESSION_BUTTON_TAG).performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag(LIVE_SESSION_MICRO_SESSION_DIALOG_TAG).assertDoesNotExist()
            assertFalse(viewModel.uiState.value.isRecording)
            assertFalse(viewModel.uiState.value.isPaused)
        } finally {
            runBlocking { sampleBuffer.discard() }
            gattClient.close()
            repository.close()
        }
    }

    private fun assertSegmentColors(currentZone: HeartZone?) {
        HeartZone.entries.forEach { zone ->
            val expected = when {
                currentZone == null -> Color.Black.copy(alpha = 0.25f)
                zone.zoneIndex == currentZone.zoneIndex -> Color.White
                zone.zoneIndex < currentZone.zoneIndex -> Color.White.copy(alpha = 0.55f)
                else -> Color.Black.copy(alpha = 0.25f)
            }
            val node = composeTestRule.onNodeWithTag(liveSessionZoneSegmentTag(zone.zoneIndex))
                .fetchSemanticsNode()
            val actual = node.config[LiveSessionSegmentColorKey]
            assertColorClose(
                expected = expected,
                actual = actual,
                message = "Zone ${zone.zoneIndex} color mismatch for active zone $currentZone"
            )
        }
    }

    private fun assertColorClose(expected: Color, actual: Color, message: String? = null) {
        assertEquals(message, expected.red, actual.red, 0.002f)
        assertEquals(message, expected.green, actual.green, 0.002f)
        assertEquals(message, expected.blue, actual.blue, 0.002f)
        assertEquals(message, expected.alpha, actual.alpha, 0.002f)
    }

    private fun getTextLayoutResult(tag: String): TextLayoutResult {
        val node = composeTestRule.onNodeWithTag(tag).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private fun collectAllText(root: SemanticsNode): List<String> {
        val texts = mutableListOf<String>()
        fun visit(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)
                ?.forEach { texts.add(it.text) }
            node.children.forEach(::visit)
        }
        visit(root)
        return texts
    }

    companion object {
        private const val ACTIVE_HRM_MAC = "AA:BB:CC:DD:EE:01"
    }
}
