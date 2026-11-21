package com.example.heartzonemonitor.ui.session

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
import com.example.heartzonemonitor.data.session.SessionRepository
import com.example.heartzonemonitor.domain.model.HeartZone
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import com.example.heartzonemonitor.domain.timing.FakeSessionClock
import com.example.heartzonemonitor.domain.timing.MonotonicDurationEngine
import com.example.heartzonemonitor.ui.navigation.MainShell
import com.example.heartzonemonitor.ui.navigation.RootNavHost
import com.example.heartzonemonitor.ui.navigation.RootViewModel
import com.example.heartzonemonitor.ui.onboarding.OnboardingProfileViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
class LiveSessionScreenTest {

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
    fun `renders all five heart zone colors, 82 percent bottom gradient depth, 124sp bold typography, and zone badge`() {
        var uiState by mutableStateOf(
            LiveSessionUiState(
                currentBpm = null,
                currentZone = null
            )
        )

        composeTestRule.activity.setContent {
            LiveSessionContent(uiState = uiState)
        }

        // Standby state when zone is null
        composeTestRule.waitForIdle()
        assertBackgroundColors(
            expectedTop = Color(0xFF0B0F19),
            expectedBottom = Color(0xFF0B0F19).copy(alpha = 0.82f)
        )
        composeTestRule.onNodeWithTag(LIVE_SESSION_BPM_TEXT_TAG)
            .assertIsDisplayed()
            .assertTextEquals("--")
        composeTestRule.onNodeWithTag(LIVE_SESSION_ZONE_BADGE_TAG)
            .assertIsDisplayed()
            .assertTextEquals("BPM | STANDBY")
        assertGiantBpmTypography()

        val expectedZones = listOf(
            Triple(HeartZone.ZONE_1, 108, Color(0xFF64748B)),
            Triple(HeartZone.ZONE_2, 128, Color(0xFF3B82F6)),
            Triple(HeartZone.ZONE_3, 148, Color(0xFF10B981)),
            Triple(HeartZone.ZONE_4, 168, Color(0xFFF59E0B)),
            Triple(HeartZone.ZONE_5, 188, Color(0xFFEF4444))
        )

        expectedZones.forEach { (zone, bpm, expectedColor) ->
            uiState = LiveSessionUiState(
                currentBpm = bpm,
                currentZone = zone,
                isRecording = true
            )
            composeTestRule.waitForIdle()

            assertBackgroundColors(
                expectedTop = expectedColor,
                expectedBottom = expectedColor.copy(alpha = 0.82f)
            )
            composeTestRule.onNodeWithTag(LIVE_SESSION_BPM_TEXT_TAG)
                .assertIsDisplayed()
                .assertTextEquals(bpm.toString())
            composeTestRule.onNodeWithTag(LIVE_SESSION_ZONE_BADGE_TAG)
                .assertIsDisplayed()
                .assertTextEquals("BPM | ZONE ${zone.zoneIndex}")
            assertGiantBpmTypography()
        }
    }

    @Test
    fun `animates background smoothly over 500ms FastOutSlowInEasing between zones`() {
        val startColor = Color(0xFF64748B) // Zone 1 Slate
        val targetColor = Color(0xFFEF4444) // Zone 5 Crimson
        var uiState by mutableStateOf(
            LiveSessionUiState(
                currentBpm = 108,
                currentZone = HeartZone.ZONE_1,
                isRecording = true
            )
        )

        composeTestRule.activity.setContent {
            LiveSessionContent(uiState = uiState)
        }
        composeTestRule.waitForIdle()
        assertBackgroundColors(
            expectedTop = startColor,
            expectedBottom = startColor.copy(alpha = 0.82f)
        )

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.runOnIdle {
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                uiState = LiveSessionUiState(
                    currentBpm = 188,
                    currentZone = HeartZone.ZONE_5,
                    isRecording = true
                )
            }
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()

        // Midpoint (250ms of 500ms FastOutSlowInEasing tween)
        composeTestRule.mainClock.advanceTimeBy(250L)
        composeTestRule.waitForIdle()
        val midNode = composeTestRule.onNodeWithTag(LIVE_SESSION_BACKGROUND_TAG).fetchSemanticsNode()
        val midTop = midNode.config[LiveSessionBgTopColorKey]
        val midBottom = midNode.config[LiveSessionBgBottomColorKey]

        assertNotEquals(startColor, midTop)
        assertNotEquals(targetColor, midTop)
        assertNotEquals(startColor.copy(alpha = 0.82f), midBottom)
        assertNotEquals(targetColor.copy(alpha = 0.82f), midBottom)
        assertTrue(midTop.red > startColor.red && midTop.red < targetColor.red)
        assertEquals(0.82f, midBottom.alpha, 0.001f)

        // Completion (remaining 250ms reaches 500ms target color)
        composeTestRule.mainClock.advanceTimeBy(250L)
        composeTestRule.waitForIdle()
        assertBackgroundColors(
            expectedTop = targetColor,
            expectedBottom = targetColor.copy(alpha = 0.82f)
        )
    }

    @Test
    fun `omits decorative heart icons and raw percentage math`() {
        composeTestRule.activity.setContent {
            LiveSessionContent(
                uiState = LiveSessionUiState(
                    currentBpm = 152,
                    currentZone = HeartZone.ZONE_3,
                    isRecording = true
                )
            )
        }
        composeTestRule.waitForIdle()

        val root = composeTestRule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val allNodes = collectSemanticsTree(root)

        val forbiddenFragments = listOf(
            "%",
            "hrmax",
            "50-60",
            "60-70",
            "70-80",
            "80-90",
            "90-100",
            "heart",
            "pulse"
        )

        allNodes.forEach { node ->
            val texts = node.config.getOrNull(SemanticsProperties.Text)
                ?.joinToString(" ") { it.text }
                .orEmpty()
            val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription)
                ?.joinToString(" ")
                .orEmpty()
            val combined = "$texts $descriptions".lowercase()

            forbiddenFragments.forEach { fragment ->
                assertFalse(
                    "Found forbidden fragment '$fragment' in node semantics: '$combined'",
                    combined.contains(fragment)
                )
            }
            assertNotEquals(Role.Image, node.config.getOrNull(SemanticsProperties.Role))
        }
    }

    @Test
    fun `scales cleanly across narrow 320dp, standard 411dp, and tablet 600dp without clipping or overlap`() {
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
                        isRecording = true
                    )
                )
            }
        }

        val viewports: List<Pair<Dp, Dp>> = listOf(
            320.dp to 568.dp,
            411.dp to 891.dp,
            600.dp to 960.dp
        )

        viewports.forEach { (width, height) ->
            composeTestRule.runOnIdle {
                boxWidth = width
                boxHeight = height
            }
            composeTestRule.waitForIdle()

            val rootBounds = composeTestRule.onNodeWithTag(LIVE_SESSION_BACKGROUND_TAG).getBoundsInRoot()
            val bpmBounds = composeTestRule.onNodeWithTag(LIVE_SESSION_BPM_TEXT_TAG).getBoundsInRoot()
            val badgeBounds = composeTestRule.onNodeWithTag(LIVE_SESSION_ZONE_BADGE_TAG).getBoundsInRoot()

            assertTrue("BPM left clipped at $width", bpmBounds.left >= rootBounds.left)
            assertTrue("BPM right clipped at $width", bpmBounds.right <= rootBounds.right)
            assertTrue("BPM top clipped at $width", bpmBounds.top >= rootBounds.top)
            assertTrue("BPM bottom clipped at $width", bpmBounds.bottom <= rootBounds.bottom)
            assertTrue("BPM width must be positive at $width", (bpmBounds.right - bpmBounds.left) > 0.dp)
            assertTrue("BPM height must be positive at $width", (bpmBounds.bottom - bpmBounds.top) > 0.dp)

            assertTrue("Badge left clipped at $width", badgeBounds.left >= rootBounds.left)
            assertTrue("Badge right clipped at $width", badgeBounds.right <= rootBounds.right)
            assertTrue("Badge top clipped at $width", badgeBounds.top >= rootBounds.top)
            assertTrue("Badge bottom clipped at $width", badgeBounds.bottom <= rootBounds.bottom)
            assertTrue("Badge width must be positive at $width", (badgeBounds.right - badgeBounds.left) > 0.dp)
            assertTrue("Badge height must be positive at $width", (badgeBounds.bottom - badgeBounds.top) > 0.dp)

            assertTrue(
                "Expected BPM (${bpmBounds.bottom}) strictly above badge (${badgeBounds.top}) at $width",
                bpmBounds.bottom <= badgeBounds.top
            )

            val bpmLayout = getBpmTextLayoutResult()
            assertEquals("BPM text must remain on a single line at $width", 1, bpmLayout.lineCount)
            assertFalse("BPM text overflowed vertically at $width", bpmLayout.didOverflowHeight)
            val lineWidthPx = bpmLayout.getLineRight(0) - bpmLayout.getLineLeft(0)
            assertTrue(
                "BPM line width ($lineWidthPx) must fit within maxWidth (${bpmLayout.layoutInput.constraints.maxWidth}) at $width",
                lineWidthPx > 0f && lineWidthPx <= bpmLayout.layoutInput.constraints.maxWidth.toFloat()
            )
        }
    }

    @Test
    fun `exposes secondaryContent and controlsContent slots cleanly`() {
        composeTestRule.activity.setContent {
            LiveSessionContent(
                uiState = LiveSessionUiState(
                    currentBpm = 148,
                    currentZone = HeartZone.ZONE_3
                ),
                secondaryContent = { Text("SECONDARY_SLOT_MARKER") },
                controlsContent = { Text("CONTROLS_SLOT_MARKER") }
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("SECONDARY_SLOT_MARKER").assertIsDisplayed()
        composeTestRule.onNodeWithText("CONTROLS_SLOT_MARKER").assertIsDisplayed()
        composeTestRule.onNodeWithTag("start_session_button").assertDoesNotExist()
    }

    @Test
    fun `MainShell and RootNavHost wire LiveSessionScreen and hide bottom navigation in immersion mode`() {
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
            val rootViewModel = RootViewModel(userProfileDao = profileDao, rememberedHrmDao = hrmDao)
            val liveSessionViewModel = LiveSessionViewModel(repository)

            composeTestRule.activity.setContent {
                RootNavHost(
                    rootViewModel = rootViewModel,
                    onboardingViewModelFactory = { OnboardingProfileViewModel(profileDao) },
                    liveSessionViewModelFactory = { liveSessionViewModel }
                )
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

            // Idle: LiveSessionScreen visible, bottom bar visible, no start_session_button tag
            composeTestRule.onNodeWithTag(LIVE_SESSION_BPM_TEXT_TAG).assertTextEquals("148")
            composeTestRule.onNodeWithTag(LIVE_SESSION_ZONE_BADGE_TAG).assertTextEquals("BPM | ZONE 3")
            composeTestRule.onNodeWithText("LIVE").assertIsDisplayed()
            composeTestRule.onNodeWithText("HISTORY").assertIsDisplayed()
            composeTestRule.onNodeWithTag("start_session_button").assertDoesNotExist()

            // Recording: immersion mode hides bottom bar
            runBlocking {
                repository.startSession()
                bleTransport.emitNotification(
                    macAddress = ACTIVE_HRM_MAC,
                    value = FakeBleTransport.buildHeartRateMeasurementPacket(
                        bpm = 188,
                        sensorContactSupported = true,
                        sensorContactDetected = true
                    ),
                    timestampEpochMs = clock.currentEpochMs()
                )
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag(LIVE_SESSION_BPM_TEXT_TAG).assertTextEquals("188")
            composeTestRule.onNodeWithTag(LIVE_SESSION_ZONE_BADGE_TAG).assertTextEquals("BPM | ZONE 5")
            composeTestRule.onNodeWithText("LIVE").assertDoesNotExist()
            composeTestRule.onNodeWithText("HISTORY").assertDoesNotExist()
            composeTestRule.onNodeWithText("SETTINGS").assertDoesNotExist()

            // Completed + reset: bottom bar returns
            runBlocking {
                clock.advanceBothBy(12_000L)
                repository.stopSession(forceSave = true)
                repository.resetToIdle()
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("LIVE").assertIsDisplayed()
            composeTestRule.onNodeWithText("HISTORY").assertIsDisplayed()
        } finally {
            runBlocking { sampleBuffer.discard() }
            gattClient.close()
            repository.close()
        }
    }

    @Test
    fun `MainShell without liveSessionViewModelFactory renders standby LiveSessionContent when Active HRM exists`() {
        val activeHrm = RememberedHrmEntity(
            macAddress = ACTIVE_HRM_MAC,
            advertisedName = "COOSPO H808S",
            customNickname = null,
            lastSeenTimestamp = 1_000L,
            batteryLevel = 90,
            isActive = true
        )
        val profile = UserProfileEntity(
            id = UserProfileEntity.SINGLETON_ID,
            birthYear = 1990,
            restingHr = 60,
            customHrmax = null,
            calculationMode = ZoneCalculationMode.KARVONEN,
            updatedAtEpochMs = 1_000L
        )

        composeTestRule.activity.setContent {
            MainShell(
                userProfile = profile,
                activeHrm = activeHrm
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(LIVE_SESSION_BPM_TEXT_TAG).assertTextEquals("--")
        composeTestRule.onNodeWithTag(LIVE_SESSION_ZONE_BADGE_TAG).assertTextEquals("BPM | STANDBY")
        composeTestRule.onNodeWithTag("start_session_button").assertDoesNotExist()
        composeTestRule.onNodeWithText("LIVE").assertIsDisplayed()
    }

    private fun assertBackgroundColors(expectedTop: Color, expectedBottom: Color) {
        val bgNode = composeTestRule.onNodeWithTag(LIVE_SESSION_BACKGROUND_TAG).fetchSemanticsNode()
        val actualTop = bgNode.config[LiveSessionBgTopColorKey]
        val actualBottom = bgNode.config[LiveSessionBgBottomColorKey]
        assertColorClose(expectedTop, actualTop)
        assertColorClose(expectedBottom, actualBottom)
    }

    private fun assertColorClose(expected: Color, actual: Color) {
        assertEquals(expected.red, actual.red, 0.002f)
        assertEquals(expected.green, actual.green, 0.002f)
        assertEquals(expected.blue, actual.blue, 0.002f)
        assertEquals(expected.alpha, actual.alpha, 0.002f)
    }

    private fun assertGiantBpmTypography() {
        val style = getBpmTextLayoutResult().layoutInput.style
        assertEquals(124.sp, style.fontSize)
        assertEquals(FontWeight.Bold, style.fontWeight)
        assertEquals(FontFamily.SansSerif, style.fontFamily)
        assertEquals((-4).sp, style.letterSpacing)
        assertEquals(124.sp, style.lineHeight)
        assertEquals(Color.White, style.color)
    }

    private fun getBpmTextLayoutResult(): TextLayoutResult {
        val node = composeTestRule.onNodeWithTag(LIVE_SESSION_BPM_TEXT_TAG).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
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

    companion object {
        private const val ACTIVE_HRM_MAC = "AA:BB:CC:DD:EE:01"
    }
}
