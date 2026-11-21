package com.example.heartzonemonitor.ui.session

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

val LiveSessionBgTopColorKey = SemanticsPropertyKey<Color>("LiveSessionBgTopColor")
val LiveSessionBgBottomColorKey = SemanticsPropertyKey<Color>("LiveSessionBgBottomColor")

const val LIVE_SESSION_BACKGROUND_TAG = "live_session_background"
const val LIVE_SESSION_BPM_TEXT_TAG = "live_session_bpm_text"
const val LIVE_SESSION_ZONE_BADGE_TAG = "live_session_zone_badge"
private const val LIVE_SESSION_DURATION_TICK_MS = 1_000L

/**
 * Stateful Live Session screen collecting [LiveSessionViewModel.uiState] and delegating to
 * [LiveSessionContent].
 */
@Composable
fun LiveSessionScreen(
    viewModel: LiveSessionViewModel,
    onManageDevices: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.isRecording) {
        if (uiState.isRecording) {
            while (isActive) {
                delay(LIVE_SESSION_DURATION_TICK_MS)
                viewModel.refreshDurations()
            }
        }
    }

    LiveSessionContent(
        uiState = uiState,
        actions = LiveSessionActions(
            onStartSession = viewModel::startSession,
            onPauseSession = viewModel::pauseSession,
            onResumeSession = viewModel::resumeSession,
            onStopSession = viewModel::stopSession,
            onManageDevices = onManageDevices,
            onConfirmSaveMicroSession = viewModel::confirmSaveMicroSession,
            onDiscardSession = viewModel::discardSession,
            onDismissMicroSessionPrompt = viewModel::dismissMicroSessionPrompt
        ),
        modifier = modifier
    )
}

/**
 * Stateless Athletic Monolith Live Session content featuring full-bleed animated Heart Zone
 * background illumination, massive glanceable BPM typography, 5-segment Heart Zone progress bar,
 * focused monospace Effective Duration card, and large 56dp Session controls.
 */
@Composable
fun LiveSessionContent(
    uiState: LiveSessionUiState,
    actions: LiveSessionActions = LiveSessionActions(),
    modifier: Modifier = Modifier,
    secondaryContent: @Composable () -> Unit = {
        LiveSessionSecondaryTelemetry(
            currentZone = uiState.currentZone,
            effectiveDurationMs = uiState.effectiveDurationMs
        )
    },
    controlsContent: @Composable () -> Unit = {
        LiveSessionControlButtons(
            isRecording = uiState.isRecording,
            isPaused = uiState.isPaused,
            canStartSession = uiState.canStartSession,
            actions = actions
        )
    }
) {
    val targetColor = uiState.currentZone.toLiveSessionBackgroundColor()
    val animatedTopColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(
            durationMillis = LIVE_SESSION_BG_ANIMATION_DURATION_MS,
            easing = FastOutSlowInEasing
        ),
        label = "LiveSessionBgTopColor"
    )
    val animatedBottomColor by animateColorAsState(
        targetValue = targetColor.copy(alpha = LIVE_SESSION_BG_DEPTH_ALPHA),
        animationSpec = tween(
            durationMillis = LIVE_SESSION_BG_ANIMATION_DURATION_MS,
            easing = FastOutSlowInEasing
        ),
        label = "LiveSessionBgBottomColor"
    )

    val bpmText = uiState.currentBpm?.toString() ?: "--"
    val zoneBadgeText = uiState.currentZone?.let { zone ->
        "BPM | ZONE ${zone.zoneIndex}"
    } ?: "BPM | STANDBY"

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LiveSessionVoidBlack)
            .background(Brush.verticalGradient(listOf(animatedTopColor, animatedBottomColor)))
            .testTag(LIVE_SESSION_BACKGROUND_TAG)
            .semantics {
                this[LiveSessionBgTopColorKey] = animatedTopColor
                this[LiveSessionBgBottomColorKey] = animatedBottomColor
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            LiveSessionHeroTelemetry(
                bpmText = bpmText,
                zoneBadgeText = zoneBadgeText
            )
            secondaryContent()
            controlsContent()
        }
    }

    if (uiState.pendingMicroSessionDiscardPrompt) {
        LiveSessionMicroSessionDiscardDialog(actions = actions)
    }
}

@Composable
private fun LiveSessionHeroTelemetry(
    bpmText: String,
    zoneBadgeText: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = bpmText,
            color = Color.White,
            fontSize = 124.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = (-4).sp,
            lineHeight = 124.sp,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.testTag(LIVE_SESSION_BPM_TEXT_TAG)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Black.copy(alpha = 0.32f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.40f)),
            modifier = Modifier
                .testTag(LIVE_SESSION_ZONE_BADGE_TAG)
                .semantics(mergeDescendants = true) {}
        ) {
            Text(
                text = zoneBadgeText,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = 1.5.sp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }
    }
}
