package com.example.heartzonemonitor.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.heartzonemonitor.domain.model.HeartZone
import kotlin.math.roundToInt

// Athletic Monolith Design Tokens
private val VoidBlack = Color(0xFF0B0F19)
private val DarkSlate = Color(0xFF1E293B)
private val BorderSlate = Color(0xFF334155)
private val AthleticLime = Color(0xFFBDCF73)
private val TertiaryCyan = Color(0xFF00DAF3)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFF94A3B8)
private val SavedEmerald = Color(0xFF10B981)
val SessionHistoryWarningAmber = Color(0xFFF59E0B)

// Custom Semantics Keys for deterministic Compose UI verification
val SessionHistoryZoneColorKey = SemanticsPropertyKey<Color>("SessionHistoryZoneColor")
val SessionHistoryZoneFractionKey = SemanticsPropertyKey<Float>("SessionHistoryZoneFraction")
val SessionHistoryBadgeColorKey = SemanticsPropertyKey<Color>("SessionHistoryBadgeColor")

const val SESSION_HISTORY_LIST_TAG = "session_history_list"
const val SESSION_HISTORY_SUMMARY_TAG = "session_history_summary"
const val SESSION_HISTORY_EMPTY_STATE_TAG = "session_history_empty_state"
const val SESSION_DETAIL_PLACEHOLDER_TAG = "session_detail_placeholder"
const val SESSION_DETAIL_BACK_BUTTON_TAG = "session_detail_back_button"
const val SESSION_DETAIL_ID_TAG = "session_detail_id"

fun sessionHistoryCardTag(sessionId: String): String = "session_history_card_$sessionId"

fun sessionSavedBadgeTag(sessionId: String): String = "session_saved_badge_$sessionId"

fun sessionUnexpectedBadgeTag(sessionId: String): String = "session_unexpected_badge_$sessionId"

fun sessionDurationTag(sessionId: String): String = "session_duration_$sessionId"

fun sessionAvgBpmTag(sessionId: String): String = "session_avg_bpm_$sessionId"

fun sessionMaxBpmTag(sessionId: String): String = "session_max_bpm_$sessionId"

fun sessionZoneBarTag(sessionId: String): String = "session_zone_bar_$sessionId"

fun sessionZoneBarEmptyTag(sessionId: String): String = "session_zone_bar_empty_$sessionId"

fun sessionZoneSegmentTag(sessionId: String, zoneIndex: Int): String =
    "session_zone_segment_${sessionId}_$zoneIndex"

/**
 * Stateful Session History screen collecting [SessionHistoryViewModel.uiState] and delegating
 * rendering to stateless [SessionHistoryContent].
 */
@Composable
fun SessionHistoryScreen(
    viewModel: SessionHistoryViewModel,
    onSessionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    SessionHistoryContent(
        uiState = uiState,
        actions = SessionHistoryActions(onSessionClick = onSessionClick),
        modifier = modifier
    )
}

/**
 * Stateless Athletic Monolith Session History screen rendering chronological O(1) Session cards,
 * proportional 5-zone distribution mini-bars, ungraceful-stop warning badges, and an empty state.
 */
@Composable
fun SessionHistoryContent(
    uiState: SessionHistoryUiState,
    actions: SessionHistoryActions = SessionHistoryActions(),
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = VoidBlack
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SessionHistoryHeader(
                sessionCount = uiState.sessionCount,
                totalEffectiveDurationMs = uiState.totalEffectiveDurationMs
            )

            HorizontalDivider(color = BorderSlate, thickness = 1.dp)

            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "LOADING SESSIONS...",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.5.sp
                        )
                    }
                }

                uiState.sessions.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        SessionHistoryEmptyState()
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(SESSION_HISTORY_LIST_TAG),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(
                            items = uiState.sessions,
                            key = { it.id }
                        ) { item ->
                            SessionHistoryCard(
                                item = item,
                                onClick = { actions.onSessionClick(item.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionHistoryHeader(
    sessionCount: Int,
    totalEffectiveDurationMs: Long
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(VoidBlack)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "SESSION HISTORY",
            color = TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = (-0.5).sp
        )
        if (sessionCount > 0) {
            Text(
                text = formatSessionHistorySummary(
                    sessionCount = sessionCount,
                    totalEffectiveDurationMs = totalEffectiveDurationMs
                ),
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp,
                modifier = Modifier.testTag(SESSION_HISTORY_SUMMARY_TAG)
            )
        }
    }
}

@Composable
private fun SessionHistoryCard(
    item: SessionHistoryItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(14.dp)
    val accentColor = if (item.isUnexpectedCompletion) SessionHistoryWarningAmber else AthleticLime

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(cardShape)
            .background(DarkSlate)
            .border(1.dp, BorderSlate, cardShape)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(sessionHistoryCardTag(item.id))
    ) {
        // Subtle athletic top accent edge
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(accentColor)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Card Header: Date, Start Time, HRM Label, and Completion Status Badge
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.formattedDate,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.4.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    )

                    if (item.isUnexpectedCompletion) {
                        UnexpectedStopBadge(sessionId = item.id)
                    } else {
                        SavedStatusBadge(sessionId = item.id)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.formattedStartTime,
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        maxLines = 1,
                        softWrap = false
                    )

                    HrmSourceChip(displayName = item.hrmDisplayName)
                }
            }

            // 3-Column Hero Telemetry Inset (VoidBlack inside DarkSlate)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(VoidBlack)
                    .border(1.dp, BorderSlate.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1.25f)) {
                    Text(
                        text = "DURATION",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.8.sp,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = item.formattedEffectiveDuration,
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .testTag(sessionDurationTag(item.id))
                            .semantics(mergeDescendants = true) {}
                    )
                }

                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .width(1.dp)
                        .height(36.dp)
                        .background(BorderSlate.copy(alpha = 0.6f))
                )

                BpmMetricColumn(
                    label = "AVG HR",
                    bpm = item.avgBpm,
                    valueTag = sessionAvgBpmTag(item.id),
                    modifier = Modifier.weight(1f)
                )

                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .width(1.dp)
                        .height(36.dp)
                        .background(BorderSlate.copy(alpha = 0.6f))
                )

                BpmMetricColumn(
                    label = "MAX HR",
                    bpm = item.maxBpm,
                    valueTag = sessionMaxBpmTag(item.id),
                    modifier = Modifier.weight(1f)
                )
            }

            // Proportional 6dp Horizontal 5-Segment Heart Zone Mini-Bar
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "HEART ZONE DISTRIBUTION",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        text = "Z1–Z5",
                        color = AthleticLime,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                SessionZoneMiniBar(
                    sessionId = item.id,
                    segments = item.zoneSegments
                )

                val dominant = item.dominantZone
                if (dominant != null) {
                    DominantZoneCaption(dominant = dominant)
                }
            }
        }
    }
}

@Composable
private fun SavedStatusBadge(sessionId: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(SavedEmerald.copy(alpha = 0.16f))
            .border(1.dp, SavedEmerald.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag(sessionSavedBadgeTag(sessionId))
            .semantics(mergeDescendants = true) {
                this[SessionHistoryBadgeColorKey] = SavedEmerald
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(SavedEmerald, CircleShape)
        )
        Text(
            text = "SAVED",
            color = SavedEmerald,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.8.sp,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun UnexpectedStopBadge(sessionId: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(SessionHistoryWarningAmber.copy(alpha = 0.16f))
            .border(1.dp, SessionHistoryWarningAmber, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag(sessionUnexpectedBadgeTag(sessionId))
            .semantics(mergeDescendants = true) {
                this[SessionHistoryBadgeColorKey] = SessionHistoryWarningAmber
                contentDescription = "Session completed unexpectedly after ungraceful stop"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(SessionHistoryWarningAmber, CircleShape)
        )
        Text(
            text = "UNEXPECTED STOP",
            color = SessionHistoryWarningAmber,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.8.sp,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun HrmSourceChip(displayName: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(VoidBlack)
            .border(1.dp, BorderSlate, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(TertiaryCyan, CircleShape)
        )
        Text(
            text = displayName,
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BpmMetricColumn(
    label: String,
    bpm: Int,
    valueTag: String,
    modifier: Modifier = Modifier
) {
    val bpmDisplay = if (bpm > 0) bpm.toString() else "--"

    Column(modifier = modifier) {
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.8.sp,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier
                .testTag(valueTag)
                .semantics(mergeDescendants = true) {}
        ) {
            Text(
                text = bpmDisplay,
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false
            )
            Text(
                text = "BPM",
                color = TertiaryCyan,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
    }
}

@Composable
private fun SessionZoneMiniBar(
    sessionId: String,
    segments: List<ZoneDistributionSegment>
) {
    val nonZeroSegments = segments.filter { it.durationMs > 0L && it.fraction > 0f }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(VoidBlack)
            .testTag(sessionZoneBarTag(sessionId))
            .semantics(mergeDescendants = true) {}
    ) {
        if (nonZeroSegments.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BorderSlate)
                    .testTag(sessionZoneBarEmptyTag(sessionId))
                    .semantics(mergeDescendants = true) {}
            )
        } else {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                nonZeroSegments.forEach { segment ->
                    val segmentColor = Color(segment.zone.colorArgb)
                    Box(
                        modifier = Modifier
                            .weight(segment.fraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(segmentColor)
                            .testTag(sessionZoneSegmentTag(sessionId, segment.zone.zoneIndex))
                            .semantics(mergeDescendants = true) {
                                this[SessionHistoryZoneColorKey] = segmentColor
                                this[SessionHistoryZoneFractionKey] = segment.fraction
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun DominantZoneCaption(dominant: ZoneDistributionSegment) {
    val percent = (dominant.fraction * 100f).roundToInt()
    val dominantColor = Color(dominant.zone.colorArgb)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(dominantColor)
            )
            Text(
                text = "Dominant: Zone ${dominant.zone.zoneIndex} ${dominant.zone.zoneName} ($percent%)",
                color = TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.SansSerif,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = formatZoneDurationCompact(dominant.durationMs),
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun SessionHistoryEmptyState(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(DarkSlate)
            .border(1.dp, BorderSlate, shape)
            .padding(24.dp)
            .testTag(SESSION_HISTORY_EMPTY_STATE_TAG),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Pure Compose 5-zone color strip emblem
        Row(
            modifier = Modifier
                .width(120.dp)
                .height(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            HeartZone.entries.forEach { zone ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(zone.colorArgb).copy(alpha = 0.65f))
                )
            }
        }

        Text(
            text = "No Sessions Recorded Yet",
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif
        )

        Text(
            text = "No sessions recorded yet. Pair your Heart Rate Monitor and start your first session.",
            color = TextSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontFamily = FontFamily.SansSerif
        )
    }
}

/**
 * Interim Session Detail destination scaffold until the full Session Detail analytics screen
 * replaces it.
 */
@Composable
fun SessionDetailPlaceholder(
    sessionId: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag(SESSION_DETAIL_PLACEHOLDER_TAG),
        containerColor = VoidBlack,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(VoidBlack)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(DarkSlate)
                            .border(1.dp, BorderSlate, RoundedCornerShape(10.dp))
                            .clickable(onClick = onNavigateBack)
                            .testTag(SESSION_DETAIL_BACK_BUTTON_TAG)
                            .semantics {
                                role = Role.Button
                                contentDescription = "Navigate back"
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "<-",
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Column {
                        Text(
                            text = "SESSION ANALYTICS",
                            color = TertiaryCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = "SESSION DETAIL",
                            color = TextPrimary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp
                        )
                    }
                }
                HorizontalDivider(color = BorderSlate, thickness = 1.dp)
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(DarkSlate)
                    .border(1.dp, BorderSlate, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "SESSION ID",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                Text(
                    text = sessionId,
                    color = AthleticLime,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag(SESSION_DETAIL_ID_TAG)
                )
                Text(
                    text = "Full Session telemetry and Heart Zone analytics will appear here.",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        }
    }
}
