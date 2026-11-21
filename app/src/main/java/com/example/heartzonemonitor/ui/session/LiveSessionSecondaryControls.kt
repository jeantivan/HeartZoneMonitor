package com.example.heartzonemonitor.ui.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.heartzonemonitor.domain.model.HeartZone
import java.util.Locale

val LiveSessionSegmentColorKey = SemanticsPropertyKey<Color>("LiveSessionSegmentColor")
val LiveSessionContainerColorKey = SemanticsPropertyKey<Color>("LiveSessionContainerColor")
val LiveSessionBorderColorKey = SemanticsPropertyKey<Color>("LiveSessionBorderColor")

val LiveSessionZoneActiveSegmentColor = Color.White
val LiveSessionZonePastSegmentColor = Color.White.copy(alpha = 0.55f)
val LiveSessionZoneFutureSegmentColor = Color.Black.copy(alpha = 0.25f)
val LiveSessionTranslucentDarkContainer = Color.Black.copy(alpha = 0.32f)

private val PauseButtonRecordingContainer = Color.Black.copy(alpha = 0.45f)
private val StopSaveButtonBorderColor = Color.White.copy(alpha = 0.40f)
private val StartButtonDisabledContentColor = Color.White.copy(alpha = 0.45f)
private val StartButtonDisabledBorderColor = Color.White.copy(alpha = 0.30f)
private val MicroSessionDialogContainerColor = Color(0xFF1E293B)
private val MicroSessionDialogBodyColor = Color(0xFF94A3B8)
private val MicroSessionDiscardRed = Color(0xFFEF4444)

const val LIVE_SESSION_ZONE_PROGRESS_BAR_TAG = "live_session_zone_progress_bar"
const val LIVE_SESSION_DURATION_CARD_TAG = "live_session_duration_card"
const val LIVE_SESSION_DURATION_TEXT_TAG = "live_session_duration_text"
const val LIVE_SESSION_PAUSE_RESUME_BUTTON_TAG = "live_session_pause_resume_button"
const val LIVE_SESSION_STOP_SAVE_BUTTON_TAG = "live_session_stop_save_button"
const val LIVE_SESSION_START_BUTTON_TAG = "live_session_start_button"
const val LIVE_SESSION_MICRO_SESSION_DIALOG_TAG = "live_session_micro_session_dialog"
const val LIVE_SESSION_DISCARD_SESSION_BUTTON_TAG = "live_session_discard_session_button"
const val LIVE_SESSION_SAVE_MICRO_SESSION_BUTTON_TAG = "live_session_save_micro_session_button"

fun liveSessionZoneSegmentTag(zoneIndex: Int): String = "live_session_zone_segment_$zoneIndex"

fun liveSessionZoneLabelTag(zoneIndex: Int): String = "live_session_zone_label_$zoneIndex"

/**
 * Formats monotonic Effective Duration in milliseconds as zero-padded `HH:mm:ss` when at least
 * one hour (3,600,000 ms) has elapsed, or zero-padded `mm:ss` when under one hour.
 */
fun formatEffectiveDuration(effectiveDurationMs: Long): String {
    val totalSeconds = effectiveDurationMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

internal fun zoneSegmentColor(segmentZone: HeartZone, currentZone: HeartZone?): Color =
    when {
        currentZone == null -> LiveSessionZoneFutureSegmentColor
        segmentZone.zoneIndex == currentZone.zoneIndex -> LiveSessionZoneActiveSegmentColor
        segmentZone.zoneIndex < currentZone.zoneIndex -> LiveSessionZonePastSegmentColor
        else -> LiveSessionZoneFutureSegmentColor
    }

/**
 * Default secondary telemetry stack combining the 5-segment Heart Zone progress bar and the
 * focused Effective Duration timer card.
 */
@Composable
fun LiveSessionSecondaryTelemetry(
    currentZone: HeartZone?,
    effectiveDurationMs: Long,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        LiveSessionZoneProgressBar(currentZone = currentZone)
        LiveSessionDurationCard(effectiveDurationMs = effectiveDurationMs)
    }
}

/**
 * 5-segment horizontal Heart Zone progress bar for Zones 1 through 5 with `Z1`–`Z5` labels
 * rendered directly beneath each segment.
 */
@Composable
fun LiveSessionZoneProgressBar(
    currentZone: HeartZone?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(LIVE_SESSION_ZONE_PROGRESS_BAR_TAG),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        HeartZone.entries.forEach { zone ->
            val segmentColor = zoneSegmentColor(segmentZone = zone, currentZone = currentZone)
            val isCurrent = currentZone != null && zone.zoneIndex == currentZone.zoneIndex
            val labelColor = if (isCurrent) Color.White else Color.White.copy(alpha = 0.80f)

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(segmentColor)
                        .testTag(liveSessionZoneSegmentTag(zone.zoneIndex))
                        .semantics {
                            this[LiveSessionSegmentColorKey] = segmentColor
                        }
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Z${zone.zoneIndex}",
                    color = labelColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.testTag(liveSessionZoneLabelTag(zone.zoneIndex))
                )
            }
        }
    }
}

/**
 * Focused timer card displaying exclusively Effective Duration in 52sp bold monospace, omitting
 * Elapsed Duration and Paused Duration clocks.
 */
@Composable
fun LiveSessionDurationCard(
    effectiveDurationMs: Long,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = LiveSessionTranslucentDarkContainer),
        modifier = modifier
            .fillMaxWidth()
            .testTag(LIVE_SESSION_DURATION_CARD_TAG)
            .semantics {
                this[LiveSessionContainerColorKey] = LiveSessionTranslucentDarkContainer
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = formatEffectiveDuration(effectiveDurationMs),
                color = Color.White,
                fontSize = 52.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.sp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.testTag(LIVE_SESSION_DURATION_TEXT_TAG)
            )
        }
    }
}

/**
 * Large 56dp bottom action controls for active Session Pause/Resume and Stop & Save actions,
 * or Start Session when idle.
 */
@Composable
fun LiveSessionControlButtons(
    isRecording: Boolean,
    isPaused: Boolean,
    canStartSession: Boolean,
    actions: LiveSessionActions,
    modifier: Modifier = Modifier
) {
    if (isRecording || isPaused) {
        val pauseResumeContainerColor =
            if (isRecording) PauseButtonRecordingContainer else Color.White
        val pauseResumeContentColor =
            if (isRecording) Color.White else LiveSessionVoidBlack

        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = if (isRecording) actions.onPauseSession else actions.onResumeSession,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .testTag(LIVE_SESSION_PAUSE_RESUME_BUTTON_TAG)
                    .semantics {
                        this[LiveSessionBorderColorKey] = Color.White
                        this[LiveSessionContainerColorKey] = pauseResumeContainerColor
                    },
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = pauseResumeContainerColor,
                    contentColor = pauseResumeContentColor
                ),
                border = BorderStroke(1.5.dp, Color.White)
            ) {
                Text(
                    text = if (isRecording) "PAUSE" else "RESUME",
                    color = pauseResumeContentColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    letterSpacing = 0.8.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }

            Button(
                onClick = actions.onStopSession,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .testTag(LIVE_SESSION_STOP_SAVE_BUTTON_TAG)
                    .semantics {
                        this[LiveSessionContainerColorKey] = LiveSessionTranslucentDarkContainer
                        this[LiveSessionBorderColorKey] = StopSaveButtonBorderColor
                    },
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LiveSessionTranslucentDarkContainer,
                    contentColor = Color.White
                ),
                border = BorderStroke(1.dp, StopSaveButtonBorderColor)
            ) {
                Text(
                    text = "STOP & SAVE",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    letterSpacing = 0.8.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    } else {
        val startContainerColor =
            if (canStartSession) Color.White else LiveSessionTranslucentDarkContainer
        val startBorderColor =
            if (canStartSession) Color.White else StartButtonDisabledBorderColor

        Button(
            onClick = actions.onStartSession,
            enabled = canStartSession,
            modifier = modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag(LIVE_SESSION_START_BUTTON_TAG)
                .semantics {
                    this[LiveSessionContainerColorKey] = startContainerColor
                    this[LiveSessionBorderColorKey] = startBorderColor
                },
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = LiveSessionVoidBlack,
                disabledContainerColor = LiveSessionTranslucentDarkContainer,
                disabledContentColor = StartButtonDisabledContentColor
            ),
            border = BorderStroke(1.5.dp, startBorderColor)
        ) {
            Text(
                text = "START SESSION",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                letterSpacing = 1.sp,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * Confirmation dialog displayed when stopping a Session with Effective Duration below 10 seconds
 * or zero recorded Heart Rate Samples.
 */
@Composable
fun LiveSessionMicroSessionDiscardDialog(
    actions: LiveSessionActions,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = actions.onDismissMicroSessionPrompt,
        modifier = modifier.testTag(LIVE_SESSION_MICRO_SESSION_DIALOG_TAG),
        containerColor = MicroSessionDialogContainerColor,
        titleContentColor = Color.White,
        textContentColor = MicroSessionDialogBodyColor,
        title = {
            Text(
                text = "Short Session",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Text(
                text = "This session is shorter than 10 seconds. Would you like to discard it or save anyway?",
                fontSize = 15.sp
            )
        },
        confirmButton = {
            Button(
                onClick = actions.onDiscardSession,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(LIVE_SESSION_DISCARD_SESSION_BUTTON_TAG),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MicroSessionDiscardRed,
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = "Discard Session",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = actions.onConfirmSaveMicroSession,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(LIVE_SESSION_SAVE_MICRO_SESSION_BUTTON_TAG)
            ) {
                Text(
                    text = "Save Session",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )
}
