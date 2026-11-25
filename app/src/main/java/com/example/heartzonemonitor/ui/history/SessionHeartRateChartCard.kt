package com.example.heartzonemonitor.ui.history

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.heartzonemonitor.domain.model.HeartZone
import com.example.heartzonemonitor.ui.theme.BorderSlate
import com.example.heartzonemonitor.ui.theme.DarkSlate
import com.example.heartzonemonitor.ui.theme.TertiaryCyan
import com.example.heartzonemonitor.ui.theme.TextPrimary
import com.example.heartzonemonitor.ui.theme.TextSecondary
import com.example.heartzonemonitor.ui.theme.VoidBlack
import kotlin.math.roundToInt

val SessionDetailChartCrosshairVisibleKey =
    SemanticsPropertyKey<Boolean>("SessionDetailChartCrosshairVisible")
val SessionDetailChartCrosshairXKey =
    SemanticsPropertyKey<Float>("SessionDetailChartCrosshairX")
val SessionDetailChartTooltipZoneColorKey =
    SemanticsPropertyKey<Color>("SessionDetailChartTooltipZoneColor")

const val SESSION_DETAIL_CHART_CARD_TAG = "session_detail_chart_card"
const val SESSION_DETAIL_CHART_SUBTITLE_TAG = "session_detail_chart_subtitle"
const val SESSION_DETAIL_CHART_CANVAS_TAG = "session_detail_chart_canvas"
const val SESSION_DETAIL_CHART_EMPTY_TAG = "session_detail_chart_empty"
const val SESSION_DETAIL_CHART_TOOLTIP_TAG = "session_detail_chart_tooltip"
const val SESSION_DETAIL_CHART_TOOLTIP_ELAPSED_TAG = "session_detail_chart_tooltip_elapsed"
const val SESSION_DETAIL_CHART_TOOLTIP_BPM_TAG = "session_detail_chart_tooltip_bpm"
const val SESSION_DETAIL_CHART_TOOLTIP_ZONE_TAG = "session_detail_chart_tooltip_zone"

fun sessionDetailChartThresholdTag(zoneIndex: Int): String =
    "session_detail_chart_threshold_$zoneIndex"

private val CHART_HEIGHT = 224.dp
private val CHART_CANVAS_HEIGHT = 196.dp
internal val CHART_PLOT_LEFT_PADDING = 48.dp
internal val CHART_PLOT_RIGHT_PADDING = 12.dp
private val PLOT_TOP_PADDING = 28.dp
private val PLOT_BOTTOM_PADDING = 10.dp

/**
 * Interactive 1 Hz Heart Rate time-series chart card built natively with Jetpack Compose Canvas.
 */
@Composable
internal fun SessionHeartRateChartCard(
    chart: SessionChartModel,
    modifier: Modifier = Modifier
) {
    val subtitle = when {
        chart.plottedSamples.isEmpty() -> "No samples recorded"
        chart.isDownsampled -> "${chart.rawSampleCount} samples · LTTB downsampled"
        else -> "${chart.rawSampleCount} samples · 1 Hz raw"
    }
    val modeBadgeLabel = if (chart.isDownsampled) "LTTB" else "1 HZ"

    DetailSectionCard(
        tag = SESSION_DETAIL_CHART_CARD_TAG,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "HEART RATE TELEMETRY (1 HZ)",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag(SESSION_DETAIL_CHART_SUBTITLE_TAG)
                )
            }

            if (chart.plottedSamples.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(VoidBlack)
                        .border(1.dp, BorderSlate, RoundedCornerShape(5.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(TertiaryCyan, CircleShape)
                    )
                    Text(
                        text = modeBadgeLabel,
                        color = TextSecondary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.6.sp
                    )
                }
            }
        }

        val containerShape = RoundedCornerShape(10.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT)
                .clip(containerShape)
                .background(VoidBlack)
                .border(1.dp, BorderSlate.copy(alpha = 0.8f), containerShape)
        ) {
            if (chart.plottedSamples.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .testTag(SESSION_DETAIL_CHART_EMPTY_TAG),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No Heart Rate Samples recorded for this Session.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.SansSerif
                    )
                }
            } else {
                SessionHeartRateChartPlot(chart = chart)
            }
        }
    }
}

@Composable
private fun SessionHeartRateChartPlot(chart: SessionChartModel) {
    val density = LocalDensity.current
    val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f) }
    val crosshairDashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f) }

    var activeTouchX by remember(chart) { mutableStateOf<Float?>(null) }
    var lastReadout by remember(chart) { mutableStateOf<ScrubbedSampleReadout?>(null) }
    var tooltipWidthPx by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(CHART_CANVAS_HEIGHT)
        ) {
            val containerWidthPx = with(density) { maxWidth.toPx() }
            val plotBounds = remember(maxWidth, maxHeight, density) {
                with(density) {
                    ChartPlotBounds(
                        left = CHART_PLOT_LEFT_PADDING.toPx(),
                        top = PLOT_TOP_PADDING.toPx(),
                        right = (maxWidth.toPx() - CHART_PLOT_RIGHT_PADDING.toPx())
                            .coerceAtLeast(CHART_PLOT_LEFT_PADDING.toPx() + 1f),
                        bottom = (maxHeight.toPx() - PLOT_BOTTOM_PADDING.toPx())
                            .coerceAtLeast(PLOT_TOP_PADDING.toPx() + 1f)
                    )
                }
            }
            val projection = remember(chart, plotBounds) { ChartProjection(chart, plotBounds) }
            val zoneGradientColorStops = remember(projection) {
                projection.zoneGradientStops()
                    .map { (stop, zone) -> stop to Color(zone.colorArgb) }
                    .toTypedArray()
            }

            val activeReadout = activeTouchX?.let { touchX ->
                projection.resolveScrubbedSample(touchX = touchX)
            }
            if (activeReadout != null) {
                lastReadout = activeReadout
            }
            val displayReadout = activeReadout ?: lastReadout
            val crosshairAlpha by animateFloatAsState(
                targetValue = if (activeReadout != null) 1f else 0f,
                animationSpec = tween(durationMillis = 180),
                label = "chart_crosshair_alpha"
            )

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(projection) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                activeTouchX = offset.x.coerceIn(plotBounds.left, plotBounds.right)
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                activeTouchX = change.position.x.coerceIn(
                                    plotBounds.left,
                                    plotBounds.right
                                )
                            },
                            onDragEnd = { activeTouchX = null },
                            onDragCancel = { activeTouchX = null }
                        )
                    }
                    .testTag(SESSION_DETAIL_CHART_CANVAS_TAG)
                    .semantics {
                        contentDescription = "Heart Rate time-series chart"
                        this[SessionDetailChartCrosshairVisibleKey] = activeReadout != null
                        this[SessionDetailChartCrosshairXKey] = activeReadout?.pointX ?: -1f
                    }
            ) {
                // 1. Faint horizontal gridlines for the session's 5 Heart Zone threshold boundaries
                chart.zoneThresholds.forEach { threshold ->
                    val y = projection.projectBpmY(threshold.bpm)
                    drawLine(
                        color = Color(threshold.zone.colorArgb).copy(alpha = 0.35f),
                        start = Offset(plotBounds.left, y),
                        end = Offset(plotBounds.right, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dashEffect
                    )
                }

                // 2. Heart rate polyline and subtle gradient fill
                val samples = chart.plottedSamples
                if (samples.size == 1) {
                    val only = samples[0]
                    val x = projection.projectSampleX(only.timestampEpochMs)
                    val y = projection.projectBpmY(only.bpm)
                    drawCircle(
                        color = Color(HeartZone.fromIndex(only.zoneIndex).colorArgb),
                        radius = 4.dp.toPx(),
                        center = Offset(x, y)
                    )
                } else {
                    val linePath = Path()
                    val fillPath = Path()
                    var firstX = plotBounds.left
                    var lastX = plotBounds.left

                    samples.forEachIndexed { index, sample ->
                        val x = projection.projectSampleX(sample.timestampEpochMs)
                        val y = projection.projectBpmY(sample.bpm)
                        if (index == 0) {
                            firstX = x
                            linePath.moveTo(x, y)
                            fillPath.moveTo(x, plotBounds.bottom)
                            fillPath.lineTo(x, y)
                        } else {
                            linePath.lineTo(x, y)
                            fillPath.lineTo(x, y)
                        }
                        lastX = x
                    }
                    fillPath.lineTo(lastX, plotBounds.bottom)
                    fillPath.lineTo(firstX, plotBounds.bottom)
                    fillPath.close()

                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                TertiaryCyan.copy(alpha = 0.22f),
                                Color.Transparent
                            ),
                            startY = plotBounds.top,
                            endY = plotBounds.bottom
                        )
                    )
                    drawPath(
                        path = linePath,
                        brush = Brush.verticalGradient(
                            colorStops = zoneGradientColorStops,
                            startY = plotBounds.top,
                            endY = plotBounds.bottom
                        ),
                        style = Stroke(
                            width = 2.2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                // 3. Vertical scrubbing crosshair & highlighted sample marker
                if (crosshairAlpha > 0f && displayReadout != null) {
                    val markerZoneColor = Color(displayReadout.zone.colorArgb)
                    drawLine(
                        color = TextPrimary.copy(alpha = 0.85f * crosshairAlpha),
                        start = Offset(displayReadout.pointX, plotBounds.top),
                        end = Offset(displayReadout.pointX, plotBounds.bottom),
                        strokeWidth = 1.2.dp.toPx(),
                        pathEffect = crosshairDashEffect
                    )
                    drawCircle(
                        color = VoidBlack.copy(alpha = crosshairAlpha),
                        radius = 6.dp.toPx(),
                        center = Offset(displayReadout.pointX, displayReadout.pointY)
                    )
                    drawCircle(
                        color = markerZoneColor.copy(alpha = crosshairAlpha),
                        radius = 4.dp.toPx(),
                        center = Offset(displayReadout.pointX, displayReadout.pointY)
                    )
                }
            }

            // Y-axis Heart Zone threshold labels aligned beside each horizontal gridline
            val halfLabelHeightPx = with(density) { 6.dp.toPx() }
            chart.zoneThresholds.forEach { threshold ->
                val yPx = projection.projectBpmY(threshold.bpm)
                val offsetY = (yPx - halfLabelHeightPx)
                    .roundToInt()
                    .coerceIn(
                        0,
                        (with(density) { CHART_CANVAS_HEIGHT.toPx() } - halfLabelHeightPx * 2)
                            .roundToInt()
                            .coerceAtLeast(0)
                    )
                Text(
                    text = "Z${threshold.zone.zoneIndex} ${threshold.bpm}",
                    color = Color(threshold.zone.colorArgb).copy(alpha = 0.85f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .offset { IntOffset(x = with(density) { 6.dp.roundToPx() }, y = offsetY) }
                        .testTag(sessionDetailChartThresholdTag(threshold.zone.zoneIndex))
                )
            }

            // Floating scrubbing tooltip pill anchored near crosshair X and clamped within bounds
            val edgePaddingPx = with(density) { 8.dp.roundToPx() }
            val topOffsetPx = with(density) { 4.dp.roundToPx() }
            val targetTooltipX = if (displayReadout != null) {
                val maxTooltipX = (containerWidthPx.roundToInt() - tooltipWidthPx - edgePaddingPx)
                    .coerceAtLeast(edgePaddingPx)
                (displayReadout.pointX - tooltipWidthPx / 2f)
                    .roundToInt()
                    .coerceIn(edgePaddingPx, maxTooltipX)
            } else {
                edgePaddingPx
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = activeReadout != null && displayReadout != null,
                enter = fadeIn(animationSpec = tween(durationMillis = 120)),
                exit = fadeOut(animationSpec = tween(durationMillis = 180)),
                modifier = Modifier
                    .offset { IntOffset(x = targetTooltipX, y = topOffsetPx) }
                    .onSizeChanged { tooltipWidthPx = it.width }
            ) {
                if (displayReadout != null) {
                    ChartScrubTooltip(readout = displayReadout)
                }
            }
        }

        // X-axis elapsed Session timestamps (MM:SS)
        val axisTimestamps = remember(chart.totalElapsedMs) {
            listOf(0.0, 0.25, 0.50, 0.75, 1.0).map { fraction ->
                formatElapsedMmSs((chart.totalElapsedMs * fraction).toLong())
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = CHART_PLOT_LEFT_PADDING, end = CHART_PLOT_RIGHT_PADDING, top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            axisTimestamps.forEach { label ->
                Text(
                    text = label,
                    color = TextSecondary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun ChartScrubTooltip(readout: ScrubbedSampleReadout) {
    val zoneColor = Color(readout.zone.colorArgb)
    val badgeTextColor = when (readout.zone) {
        HeartZone.ZONE_3, HeartZone.ZONE_4 -> VoidBlack
        else -> TextPrimary
    }
    val pillShape = RoundedCornerShape(6.dp)

    Row(
        modifier = Modifier
            .clip(pillShape)
            .background(DarkSlate.copy(alpha = 0.96f))
            .border(1.dp, zoneColor, pillShape)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag(SESSION_DETAIL_CHART_TOOLTIP_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = readout.formattedElapsedMmSs,
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.testTag(SESSION_DETAIL_CHART_TOOLTIP_ELAPSED_TAG)
        )
        Text(
            text = "·",
            color = BorderSlate,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "${readout.bpm} BPM",
            color = TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.testTag(SESSION_DETAIL_CHART_TOOLTIP_BPM_TAG)
        )
        Text(
            text = "·",
            color = BorderSlate,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(zoneColor)
                .padding(horizontal = 5.dp, vertical = 1.dp)
                .testTag(SESSION_DETAIL_CHART_TOOLTIP_ZONE_TAG)
                .semantics(mergeDescendants = true) {
                    this[SessionDetailChartTooltipZoneColorKey] = zoneColor
                }
        ) {
            Text(
                text = "ZONE ${readout.zone.zoneIndex}",
                color = badgeTextColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.4.sp,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
