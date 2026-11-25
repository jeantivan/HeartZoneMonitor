package com.example.heartzonemonitor.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.heartzonemonitor.data.local.entity.SessionStatus
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import com.example.heartzonemonitor.ui.theme.AthleticLime
import com.example.heartzonemonitor.ui.theme.BorderSlate
import com.example.heartzonemonitor.ui.theme.DarkSlate
import com.example.heartzonemonitor.ui.theme.RequiredOrange
import com.example.heartzonemonitor.ui.theme.SavedEmerald
import com.example.heartzonemonitor.ui.theme.TertiaryCyan
import com.example.heartzonemonitor.ui.theme.TextPrimary
import com.example.heartzonemonitor.ui.theme.TextSecondary
import com.example.heartzonemonitor.ui.theme.VoidBlack
import com.example.heartzonemonitor.ui.theme.WarningAmber
import java.util.Locale

val SessionDetailBadgeColorKey = SemanticsPropertyKey<Color>("SessionDetailBadgeColor")
val SessionDetailZoneColorKey = SemanticsPropertyKey<Color>("SessionDetailZoneColor")
val SessionDetailZoneFractionKey = SemanticsPropertyKey<Float>("SessionDetailZoneFraction")

const val SESSION_DETAIL_SCREEN_TAG = "session_detail_screen"
const val SESSION_DETAIL_BACK_BUTTON_TAG = "session_detail_back_button"
const val SESSION_DETAIL_SCROLL_TAG = "session_detail_scroll"
const val SESSION_DETAIL_HEADER_CARD_TAG = "session_detail_header_card"
const val SESSION_DETAIL_START_TIMESTAMP_TAG = "session_detail_start_timestamp"
const val SESSION_DETAIL_END_TIMESTAMP_TAG = "session_detail_end_timestamp"
const val SESSION_DETAIL_STATUS_BADGE_TAG = "session_detail_status_badge"
const val SESSION_DETAIL_HRM_NAME_TAG = "session_detail_hrm_name"
const val SESSION_DETAIL_HRM_MAC_TAG = "session_detail_hrm_mac"
const val SESSION_DETAIL_HERO_CARD_TAG = "session_detail_hero_card"
const val SESSION_DETAIL_EFFECTIVE_DURATION_TAG = "session_detail_effective_duration"
const val SESSION_DETAIL_AVG_BPM_TAG = "session_detail_avg_bpm"
const val SESSION_DETAIL_MAX_BPM_TAG = "session_detail_max_bpm"
const val SESSION_DETAIL_MIN_BPM_TAG = "session_detail_min_bpm"
const val SESSION_DETAIL_ZONE_CARD_TAG = "session_detail_zone_card"
const val SESSION_DETAIL_ZONE_BAR_TAG = "session_detail_zone_bar"
const val SESSION_DETAIL_ZONE_BAR_EMPTY_TAG = "session_detail_zone_bar_empty"
const val SESSION_DETAIL_SNAPSHOT_CARD_TAG = "session_detail_snapshot_card"
const val SESSION_DETAIL_ELAPSED_DURATION_TAG = "session_detail_elapsed_duration"
const val SESSION_DETAIL_PAUSED_DURATION_TAG = "session_detail_paused_duration"
const val SESSION_DETAIL_SNAPSHOT_AGE_TAG = "session_detail_snapshot_age"
const val SESSION_DETAIL_SNAPSHOT_RESTING_HR_TAG = "session_detail_snapshot_resting_hr"
const val SESSION_DETAIL_SNAPSHOT_HRMAX_TAG = "session_detail_snapshot_hrmax"
const val SESSION_DETAIL_SNAPSHOT_MODE_TAG = "session_detail_snapshot_mode"

fun sessionDetailZoneSegmentTag(zoneIndex: Int): String = "session_detail_zone_segment_$zoneIndex"

fun sessionDetailZoneBarPercentTag(zoneIndex: Int): String =
    "session_detail_zone_bar_percent_$zoneIndex"

fun sessionDetailZoneRowTag(zoneIndex: Int): String = "session_detail_zone_row_$zoneIndex"

fun sessionDetailZoneNameTag(zoneIndex: Int): String = "session_detail_zone_name_$zoneIndex"

fun sessionDetailZoneRangeTag(zoneIndex: Int): String = "session_detail_zone_range_$zoneIndex"

fun sessionDetailZoneDurationTag(zoneIndex: Int): String = "session_detail_zone_duration_$zoneIndex"

fun sessionDetailZonePercentTag(zoneIndex: Int): String = "session_detail_zone_percent_$zoneIndex"

/**
 * Stateful Session Detail screen collecting [SessionDetailViewModel.uiState] and delegating
 * rendering to stateless [SessionDetailContent].
 */
@Composable
fun SessionDetailScreen(
    viewModel: SessionDetailViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    SessionDetailContent(
        uiState = uiState,
        actions = SessionDetailActions(onNavigateBack = onNavigateBack),
        modifier = modifier
    )
}

/**
 * Stateless Athletic Monolith Session Detail analytics screen.
 */
@Composable
fun SessionDetailContent(
    uiState: SessionDetailUiState,
    actions: SessionDetailActions = SessionDetailActions(),
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag(SESSION_DETAIL_SCREEN_TAG),
        containerColor = VoidBlack,
        topBar = {
            SessionDetailTopBar(
                formattedDate = uiState.session?.formattedDate,
                onNavigateBack = actions.onNavigateBack
            )
        }
    ) { innerPadding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "LOADING SESSION...",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.5.sp
                    )
                }
            }

            uiState.session == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Session not found",
                        color = TextSecondary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.SansSerif
                    )
                }
            }

            else -> {
                val session = uiState.session
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                        .testTag(SESSION_DETAIL_SCROLL_TAG),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    SessionDetailHeaderCard(session = session)
                    SessionDetailHeroTelemetryCard(session = session)
                    SessionDetailZoneDistributionCard(session = session)
                    SessionHeartRateChartCard(chart = session.chart)
                    SessionDetailDurationsAndSnapshotCard(session = session)
                }
            }
        }
    }
}

@Composable
private fun SessionDetailTopBar(
    formattedDate: String?,
    onNavigateBack: () -> Unit
) {
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

            Column(modifier = Modifier.weight(1f)) {
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
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                )
                if (formattedDate != null) {
                    Text(
                        text = formattedDate,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        HorizontalDivider(color = BorderSlate, thickness = 1.dp)
    }
}

@Composable
internal fun DetailSectionCard(
    tag: String,
    modifier: Modifier = Modifier,
    topAccentColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val cardShape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(DarkSlate)
            .border(1.dp, BorderSlate, cardShape)
            .testTag(tag)
    ) {
        if (topAccentColor != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(topAccentColor)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
private fun DetailMetricCell(
    label: String,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    horizontalPaddingDp: Int = 10,
    verticalPaddingDp: Int = 8,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(VoidBlack)
            .border(1.dp, BorderSlate.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = horizontalPaddingDp.dp, vertical = verticalPaddingDp.dp),
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.7.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        content()
    }
}

@Composable
private fun SessionDetailHeaderCard(session: SessionDetailModel) {
    val accentColor = if (session.isUnexpectedCompletion) WarningAmber else AthleticLime

    DetailSectionCard(
        tag = SESSION_DETAIL_HEADER_CARD_TAG,
        topAccentColor = accentColor
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SESSION METADATA",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )

            SessionDetailStatusBadge(
                status = session.status,
                isUnexpectedCompletion = session.isUnexpectedCompletion
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DetailMetricCell(
                label = "STARTED",
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = session.formattedStartTimestamp,
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    modifier = Modifier.testTag(SESSION_DETAIL_START_TIMESTAMP_TAG)
                )
            }
            DetailMetricCell(
                label = "ENDED",
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = session.formattedEndTimestamp,
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    modifier = Modifier.testTag(SESSION_DETAIL_END_TIMESTAMP_TAG)
                )
            }
        }

        HorizontalDivider(color = BorderSlate.copy(alpha = 0.7f), thickness = 1.dp)

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
                        .size(7.dp)
                        .background(TertiaryCyan, CircleShape)
                )
                Text(
                    text = session.hrmDisplayName,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.SansSerif,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(SESSION_DETAIL_HRM_NAME_TAG)
                )
            }

            Text(
                text = session.hrmMacAddress,
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.testTag(SESSION_DETAIL_HRM_MAC_TAG)
            )
        }
    }
}

@Composable
private fun SessionDetailStatusBadge(
    status: SessionStatus,
    isUnexpectedCompletion: Boolean
) {
    val badgeColor = if (isUnexpectedCompletion) WarningAmber else SavedEmerald
    val badgeLabel = when {
        isUnexpectedCompletion -> "UNEXPECTED STOP"
        status == SessionStatus.COMPLETED -> "COMPLETED"
        else -> status.name
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(badgeColor.copy(alpha = 0.16f))
            .border(1.dp, badgeColor.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag(SESSION_DETAIL_STATUS_BADGE_TAG)
            .semantics(mergeDescendants = true) {
                this[SessionDetailBadgeColorKey] = badgeColor
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(badgeColor, CircleShape)
        )
        Text(
            text = badgeLabel,
            color = badgeColor,
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
private fun SessionDetailHeroTelemetryCard(session: SessionDetailModel) {
    DetailSectionCard(tag = SESSION_DETAIL_HERO_CARD_TAG) {
        Text(
            text = "HERO TELEMETRY",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HeroDurationCell(
                label = "EFFECTIVE",
                formattedDuration = session.formattedEffectiveDuration,
                valueTag = SESSION_DETAIL_EFFECTIVE_DURATION_TAG,
                modifier = Modifier.weight(1.2f)
            )
            HeroBpmCell(
                label = "AVG HR",
                bpm = session.avgBpm,
                valueColor = TextPrimary,
                valueTag = SESSION_DETAIL_AVG_BPM_TAG,
                modifier = Modifier.weight(1f)
            )
            HeroBpmCell(
                label = "MAX HR",
                bpm = session.maxBpm,
                valueColor = RequiredOrange,
                valueTag = SESSION_DETAIL_MAX_BPM_TAG,
                modifier = Modifier.weight(1f)
            )
            HeroBpmCell(
                label = "MIN HR",
                bpm = session.minBpm,
                valueColor = TertiaryCyan,
                valueTag = SESSION_DETAIL_MIN_BPM_TAG,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun HeroDurationCell(
    label: String,
    formattedDuration: String,
    valueTag: String,
    modifier: Modifier = Modifier
) {
    DetailMetricCell(
        label = label,
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        horizontalPaddingDp = 6,
        verticalPaddingDp = 10
    ) {
        Text(
            text = formattedDuration,
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .testTag(valueTag)
                .semantics(mergeDescendants = true) {}
        )
        Text(
            text = "HH:MM:SS",
            color = TextSecondary,
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
    }
}

@Composable
private fun HeroBpmCell(
    label: String,
    bpm: Int,
    valueColor: Color,
    valueTag: String,
    modifier: Modifier = Modifier
) {
    val bpmText = if (bpm > 0) bpm.toString() else "--"

    DetailMetricCell(
        label = label,
        modifier = modifier
            .testTag(valueTag)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        horizontalPaddingDp = 6,
        verticalPaddingDp = 10
    ) {
        Text(
            text = bpmText,
            color = valueColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = "BPM",
            color = TextSecondary,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
    }
}

@Composable
private fun SessionDetailZoneDistributionCard(session: SessionDetailModel) {
    val dominant = session.dominantZone

    DetailSectionCard(tag = SESSION_DETAIL_ZONE_CARD_TAG) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ZONE DISTRIBUTION",
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.8.sp
            )

            if (dominant != null) {
                val domColor = Color(dominant.zone.colorArgb)
                val domNameUpper = dominant.zone.zoneName.uppercase(Locale.US)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(domColor.copy(alpha = 0.16f))
                        .border(1.dp, domColor.copy(alpha = 0.55f), RoundedCornerShape(5.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "DOMINANT: ZONE ${dominant.zone.zoneIndex} ($domNameUpper · ${dominant.percentageOfEffective}%)",
                        color = domColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        SessionDetailProportionalZoneBar(breakdowns = session.zoneBreakdowns)

        // Percentage labels for Z1-Z5 directly beneath the proportional bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            session.zoneBreakdowns.forEach { entry ->
                val zoneColor = Color(entry.zone.colorArgb)
                val labelColor = if (entry.durationMs > 0L) zoneColor else TextSecondary
                Text(
                    text = "Z${entry.zone.zoneIndex} ${entry.percentageOfEffective}%",
                    color = labelColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.testTag(sessionDetailZoneBarPercentTag(entry.zone.zoneIndex))
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            session.zoneBreakdowns.forEach { entry ->
                SessionDetailZoneBreakdownRow(
                    entry = entry,
                    isDominant = dominant?.zone == entry.zone
                )
            }
        }
    }
}

@Composable
private fun SessionDetailProportionalZoneBar(breakdowns: List<SessionZoneBreakdown>) {
    val nonZero = breakdowns.filter { it.durationMs > 0L && it.barFraction > 0f }
    val coveredFraction = nonZero.sumOf { it.barFraction.toDouble() }.toFloat()
    val unaccountedFraction = (1f - coveredFraction).coerceAtLeast(0f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(VoidBlack)
            .border(1.dp, BorderSlate, RoundedCornerShape(5.dp))
            .padding(1.dp)
            .testTag(SESSION_DETAIL_ZONE_BAR_TAG)
            .semantics(mergeDescendants = true) {}
    ) {
        if (nonZero.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(4.dp))
                    .background(BorderSlate)
                    .testTag(SESSION_DETAIL_ZONE_BAR_EMPTY_TAG)
                    .semantics(mergeDescendants = true) {}
            )
        } else {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                nonZero.forEach { entry ->
                    val segColor = Color(entry.zone.colorArgb)
                    Box(
                        modifier = Modifier
                            .weight(entry.barFraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(segColor)
                            .testTag(sessionDetailZoneSegmentTag(entry.zone.zoneIndex))
                            .semantics(mergeDescendants = true) {
                                this[SessionDetailZoneColorKey] = segColor
                                this[SessionDetailZoneFractionKey] = entry.barFraction
                            }
                    )
                }
                if (unaccountedFraction > 0.001f) {
                    Spacer(
                        modifier = Modifier
                            .weight(unaccountedFraction)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionDetailZoneBreakdownRow(
    entry: SessionZoneBreakdown,
    isDominant: Boolean
) {
    val zoneColor = Color(entry.zone.colorArgb)
    val rowShape = RoundedCornerShape(8.dp)
    val bgColor = if (isDominant) zoneColor.copy(alpha = 0.12f) else VoidBlack
    val borderColor = if (isDominant) zoneColor.copy(alpha = 0.5f) else BorderSlate.copy(alpha = 0.5f)
    val durationColor = if (isDominant) zoneColor else TextPrimary
    val percentColor = if (isDominant) zoneColor else TextSecondary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(bgColor)
            .border(1.dp, borderColor, rowShape)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag(sessionDetailZoneRowTag(entry.zone.zoneIndex)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(end = 6.dp)
                .testTag(sessionDetailZoneNameTag(entry.zone.zoneIndex))
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(zoneColor, CircleShape)
            )
            Text(
                text = "Zone ${entry.zone.zoneIndex}",
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = if (isDominant) FontWeight.Bold else FontWeight.SemiBold,
                fontFamily = FontFamily.SansSerif,
                maxLines = 1,
                softWrap = false
            )
            Text(
                text = "(${entry.zone.zoneName})",
                color = TextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.SansSerif,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "${entry.minBpm} - ${entry.maxBpm} BPM",
                color = TextSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.testTag(sessionDetailZoneRangeTag(entry.zone.zoneIndex))
            )
            Text(
                text = entry.formattedDuration,
                color = durationColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.testTag(sessionDetailZoneDurationTag(entry.zone.zoneIndex))
            )
            Text(
                text = "${entry.percentageOfEffective}%",
                color = percentColor,
                fontSize = 11.sp,
                fontWeight = if (isDominant) FontWeight.Bold else FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .width(34.dp)
                    .testTag(sessionDetailZonePercentTag(entry.zone.zoneIndex))
            )
        }
    }
}

@Composable
private fun SessionDetailDurationsAndSnapshotCard(session: SessionDetailModel) {
    val snapshot = session.profileSnapshot
    val restingHrDisplay = snapshot.restingHr?.let { "$it BPM" } ?: "Not set"
    val modeDisplay = when (snapshot.calculationMode) {
        ZoneCalculationMode.KARVONEN -> "Karvonen HRR"
        ZoneCalculationMode.PERCENT_HRMAX -> "%HRmax"
    }

    DetailSectionCard(tag = SESSION_DETAIL_SNAPSHOT_CARD_TAG) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "DURATIONS & FROZEN PROFILE SNAPSHOT",
                color = TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.7.sp
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(VoidBlack)
                    .border(1.dp, BorderSlate, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "READ-ONLY",
                    color = AthleticLime,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SnapshotValueChip(
                label = "ELAPSED DURATION",
                value = session.formattedElapsedDuration,
                valueColor = TextPrimary,
                valueTag = SESSION_DETAIL_ELAPSED_DURATION_TAG,
                modifier = Modifier.weight(1f)
            )
            SnapshotValueChip(
                label = "PAUSED DURATION",
                value = session.formattedPausedDuration,
                valueColor = WarningAmber,
                valueTag = SESSION_DETAIL_PAUSED_DURATION_TAG,
                modifier = Modifier.weight(1f)
            )
        }

        HorizontalDivider(color = BorderSlate.copy(alpha = 0.7f), thickness = 1.dp)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SnapshotValueChip(
                label = "USER AGE",
                value = "${snapshot.userAge} yrs",
                valueColor = TextPrimary,
                valueTag = SESSION_DETAIL_SNAPSHOT_AGE_TAG,
                modifier = Modifier.weight(1f)
            )
            SnapshotValueChip(
                label = "RESTING HR",
                value = restingHrDisplay,
                valueColor = TextPrimary,
                valueTag = SESSION_DETAIL_SNAPSHOT_RESTING_HR_TAG,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SnapshotValueChip(
                label = "CALCULATION MODE",
                value = modeDisplay,
                valueColor = AthleticLime,
                valueTag = SESSION_DETAIL_SNAPSHOT_MODE_TAG,
                modifier = Modifier.weight(1f)
            )
            SnapshotValueChip(
                label = "CALCULATED HRMAX",
                value = "${snapshot.calculatedHrmax} BPM",
                valueColor = RequiredOrange,
                valueTag = SESSION_DETAIL_SNAPSHOT_HRMAX_TAG,
                modifier = Modifier.weight(1f)
            )
        }

        Text(
            text = "Heart Zone boundaries frozen at session start to preserve historical telemetry integrity.",
            color = TextSecondary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            fontFamily = FontFamily.SansSerif
        )
    }
}

@Composable
private fun SnapshotValueChip(
    label: String,
    value: String,
    valueColor: Color,
    valueTag: String,
    modifier: Modifier = Modifier
) {
    DetailMetricCell(
        label = label,
        modifier = modifier
    ) {
        Text(
            text = value,
            color = valueColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.testTag(valueTag)
        )
    }
}
