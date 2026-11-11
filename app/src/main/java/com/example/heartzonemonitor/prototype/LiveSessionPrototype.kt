package com.example.heartzonemonitor.prototype

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * PROTOTYPE: Live Session Screen with Dynamic Heart Zone Color Transitions
 *
 * Switch between 3 radically different UI variants:
 * - Variant A: Full-Bleed Immersive Zone (Monolithic athletic contrast, fluid animated ambient background)
 * - Variant B: Circular Tachometer & Live Waveform (OLED black, radial gauge with needle & 60s sparkline)
 * - Variant C: Split HUD / Multi-Metric Grid (Two-tone high-density telemetry layout with touch lock)
 */

enum class PrototypeVariant(val label: String, val description: String) {
    VARIANT_A("A", "Full-Bleed Immersive"),
    VARIANT_B("B", "Tachometer & Waveform"),
    VARIANT_C("C", "Split HUD Grid")
}

data class ZoneInfo(
    val index: Int,
    val name: String,
    val rangeDesc: String,
    val minBpm: Int,
    val maxBpm: Int,
    val color: Color,
    val darkTone: Color
)

object ZonePalette {
    val Zone1 = ZoneInfo(1, "Recovery", "50-60%", 95, 114, Color(0xFF64748B), Color(0xFF0F172A))
    val Zone2 = ZoneInfo(2, "Endurance", "60-70%", 115, 133, Color(0xFF3B82F6), Color(0xFF172554))
    val Zone3 = ZoneInfo(3, "Aerobic", "70-80%", 134, 152, Color(0xFF10B981), Color(0xFF064E3B))
    val Zone4 = ZoneInfo(4, "Threshold", "80-90%", 153, 171, Color(0xFFF59E0B), Color(0xFF451A03))
    val Zone5 = ZoneInfo(5, "VO2 Max", "90-100%", 172, 190, Color(0xFFEF4444), Color(0xFF450A0A))

    fun getZoneForBpm(bpm: Int): ZoneInfo {
        return when {
            bpm < 115 -> Zone1
            bpm < 134 -> Zone2
            bpm < 153 -> Zone3
            bpm < 172 -> Zone4
            else -> Zone5
        }
    }
}

@Composable
fun LiveSessionPrototypeScreen() {
    var currentVariant by remember { mutableStateOf(PrototypeVariant.VARIANT_A) }
    var simulatedBpm by remember { mutableIntStateOf(145) }
    var isRecording by remember { mutableStateOf(true) }
    var effectiveSeconds by remember { mutableLongStateOf(1345L) }
    var pausedSeconds by remember { mutableLongStateOf(120L) }
    val recentSamples = remember { mutableStateListOf<Int>().apply { addAll(List(40) { 135 + (it % 15) }) } }

    // Simulated 1Hz heartbeat ticker
    LaunchedEffect(isRecording) {
        while (isRecording) {
            delay(1000L)
            effectiveSeconds += 1L
            // Add subtle realistic wander to BPM if not manually locked
            val delta = (-1..1).random()
            simulatedBpm = (simulatedBpm + delta).coerceIn(60, 195)
            recentSamples.add(simulatedBpm)
            if (recentSamples.size > 60) recentSamples.removeAt(0)
        }
    }

    val activeZone = ZonePalette.getZoneForBpm(simulatedBpm)

    Box(modifier = Modifier.fillMaxSize()) {
        // Render Active Variant
        when (currentVariant) {
            PrototypeVariant.VARIANT_A -> VariantA_FullBleedImmersive(
                bpm = simulatedBpm,
                zone = activeZone,
                isRecording = isRecording,
                effectiveSeconds = effectiveSeconds,
                pausedSeconds = pausedSeconds,
                onTogglePause = { isRecording = !isRecording }
            )
            PrototypeVariant.VARIANT_B -> VariantB_TachometerWaveform(
                bpm = simulatedBpm,
                zone = activeZone,
                isRecording = isRecording,
                effectiveSeconds = effectiveSeconds,
                pausedSeconds = pausedSeconds,
                samples = recentSamples,
                onTogglePause = { isRecording = !isRecording }
            )
            PrototypeVariant.VARIANT_C -> VariantC_SplitHUDGrid(
                bpm = simulatedBpm,
                zone = activeZone,
                isRecording = isRecording,
                effectiveSeconds = effectiveSeconds,
                pausedSeconds = pausedSeconds,
                onTogglePause = { isRecording = !isRecording }
            )
        }

        // Top Simulator Control Panel
        SimulatorControlPanel(
            bpm = simulatedBpm,
            onBpmChange = {
                simulatedBpm = it
                recentSamples.add(it)
                if (recentSamples.size > 60) recentSamples.removeAt(0)
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp, start = 16.dp, end = 16.dp)
        )

        // Floating Prototype Switcher Bottom Bar
        PrototypeSwitcherBar(
            currentVariant = currentVariant,
            onVariantChange = { currentVariant = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        )
    }
}

// =====================================================================================
// VARIANT A: Full-Bleed Immersive Zone (Athletic Monolith)
// =====================================================================================
@Composable
fun VariantA_FullBleedImmersive(
    bpm: Int,
    zone: ZoneInfo,
    isRecording: Boolean,
    effectiveSeconds: Long,
    pausedSeconds: Long,
    onTogglePause: () -> Unit
) {
    val animatedVibrant by animateColorAsState(
        targetValue = zone.color,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "VibrantBgTransition"
    )
    val animatedDeeper by animateColorAsState(
        targetValue = zone.color.copy(alpha = 0.82f),
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "DeeperBgTransition"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val heartScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (60000 / bpm.coerceAtLeast(40)).coerceIn(300, 1500)),
            repeatMode = RepeatMode.Reverse
        ),
        label = "heartPulse"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(animatedVibrant, animatedDeeper)))
            .padding(horizontal = 24.dp)
            .padding(top = 110.dp, bottom = 90.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Giant BPM & Zone Indicator
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 16.dp)
        ) {
            Text(
                text = "$bpm",
                color = Color.White,
                fontSize = 124.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = (-4).sp,
                lineHeight = 124.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.32f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.4f))
            ) {
                Text(
                    text = "BPM  |  ZONE ${zone.index}",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }
        }

        // 5-Segment Horizontal Zone Bar
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp)),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(ZonePalette.Zone1, ZonePalette.Zone2, ZonePalette.Zone3, ZonePalette.Zone4, ZonePalette.Zone5).forEach { z ->
                    val isCurrent = z.index == zone.index
                    val isPast = z.index < zone.index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(
                                when {
                                    isCurrent -> Color.White
                                    isPast -> Color.White.copy(alpha = 0.55f)
                                    else -> Color.Black.copy(alpha = 0.25f)
                                }
                            )
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Z1", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Z2", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Z3", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Z4", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Z5", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Duration Card (Pure Timer Only)
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.32f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = formatDuration(effectiveSeconds),
                    color = Color.White,
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }
        }

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = onTogglePause,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) Color.Black.copy(alpha = 0.45f) else Color.White
                ),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.White)
            ) {
                Text(
                    text = if (isRecording) "PAUSE" else "RESUME",
                    color = if (isRecording) Color.White else Color.Black,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp
                )
            }
            Button(
                onClick = { /* Stop triggered */ },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.65f)),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.White.copy(alpha = 0.6f))
            ) {
                Text(text = "STOP & SAVE", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            }
        }
    }
}

// =====================================================================================
// VARIANT B: Circular Tachometer & Live Waveform (OLED Instrument Gauge)
// =====================================================================================
@Composable
fun VariantB_TachometerWaveform(
    bpm: Int,
    zone: ZoneInfo,
    isRecording: Boolean,
    effectiveSeconds: Long,
    pausedSeconds: Long,
    samples: List<Int>,
    onTogglePause: () -> Unit
) {
    val animatedAccent by animateColorAsState(
        targetValue = zone.color,
        animationSpec = tween(durationMillis = 400),
        label = "TachometerColor"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0C))
            .padding(horizontal = 20.dp)
            .padding(top = 110.dp, bottom = 90.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Circular Gauge
        Box(
            modifier = Modifier
                .size(240.dp)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 18.dp.toPx()
                val radius = (size.minDimension - strokeWidth) / 2f
                val center = Offset(size.width / 2f, size.height / 2f)

                // 240-degree tachometer arc from 150 to 390 deg
                val startAngle = 140f
                val sweepAngle = 260f

                // Draw Track
                drawArc(
                    color = Color(0xFF26262B),
                    startAngle = startAngle,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Draw Active Progress
                val bpmNormalized = ((bpm - 60f) / (200f - 60f)).coerceIn(0f, 1f)
                drawArc(
                    brush = Brush.sweepGradient(
                        0.0f to Color(0xFF3B82F6),
                        0.4f to Color(0xFF10B981),
                        0.7f to Color(0xFFF59E0B),
                        1.0f to Color(0xFFEF4444)
                    ),
                    startAngle = startAngle,
                    sweepAngle = sweepAngle * bpmNormalized,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Draw Needle Pip
                val currentAngleRad = (startAngle + sweepAngle * bpmNormalized) * (PI / 180.0)
                val pipCenter = Offset(
                    (center.x + radius * cos(currentAngleRad)).toFloat(),
                    (center.y + radius * sin(currentAngleRad)).toFloat()
                )
                drawCircle(color = Color.White, radius = 9.dp.toPx(), center = pipCenter)
                drawCircle(color = animatedAccent, radius = 5.dp.toPx(), center = pipCenter)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$bpm",
                    color = Color.White,
                    fontSize = 68.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.SansSerif
                )
                Text(
                    text = "BPM",
                    color = animatedAccent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "Z${zone.index} • ${zone.name}",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Live Sparkline Waveform (Last 60s)
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF16161B)),
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "LIVE TELEMETRY (PAST 60s)",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${samples.minOrNull() ?: 0} - ${samples.maxOrNull() ?: 0} BPM",
                        color = animatedAccent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (samples.size > 1) {
                        val path = Path()
                        val minVal = 60f
                        val maxVal = 200f
                        val stepX = size.width / (samples.size - 1).coerceAtLeast(1)

                        samples.forEachIndexed { i, sample ->
                            val normY = 1f - ((sample - minVal) / (maxVal - minVal)).coerceIn(0f, 1f)
                            val x = i * stepX
                            val y = normY * size.height
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(
                            path = path,
                            color = animatedAccent,
                            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
            }
        }

        // Split Metric Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF16161B)),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("EFFECTIVE TIME", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(formatDuration(effectiveSeconds), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF16161B)),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("SENSOR STATUS", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("COOSPO 88%", color = Color(0xFF10B981), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Skin Contact: OK", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                }
            }
        }

        // Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onTogglePause,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) Color(0xFF26262B) else Color(0xFF10B981)
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, animatedAccent)
            ) {
                Text(if (isRecording) "PAUSE" else "RESUME", fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { /* Finish */ },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F1D1D))
            ) {
                Text("FINISH", fontWeight = FontWeight.Bold, color = Color(0xFFFCA5A5))
            }
        }
    }
}

// =====================================================================================
// VARIANT C: Split HUD / Multi-Metric Grid (High-Density Telemetry)
// =====================================================================================
@Composable
fun VariantC_SplitHUDGrid(
    bpm: Int,
    zone: ZoneInfo,
    isRecording: Boolean,
    effectiveSeconds: Long,
    pausedSeconds: Long,
    onTogglePause: () -> Unit
) {
    var isScreenLocked by remember { mutableStateOf(false) }
    val animatedHeaderColor by animateColorAsState(
        targetValue = zone.color,
        animationSpec = tween(durationMillis = 500),
        label = "HeaderColor"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(top = 100.dp, bottom = 90.dp)
    ) {
        // Top Half: Vibrant Colored Zone Header Card
        Card(
            shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
            colors = CardDefaults.cardColors(containerColor = animatedHeaderColor),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ZONE ${zone.index} • ${zone.name.uppercase()}",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp
                    )
                    Text(
                        text = "${zone.minBpm}-${zone.maxBpm} BPM",
                        color = Color.White.copy(alpha = 0.9f),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }

                Text(
                    text = "$bpm",
                    color = Color.White,
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = (-3).sp
                )

                Text(
                    text = if (isRecording) "RECORDING ACTIVE" else "PAUSED",
                    color = Color.White.copy(alpha = 0.85f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp
                )
            }
        }

        // Bottom Half: 4-Quadrant High-Density Grid
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricTile(
                    title = "EFFECTIVE DURATION",
                    value = formatDuration(effectiveSeconds),
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    title = "ELAPSED / PAUSED",
                    value = "${formatDuration(effectiveSeconds + pausedSeconds)} / ${formatDuration(pausedSeconds)}",
                    isSub = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricTile(
                    title = "AVG / MAX BPM",
                    value = "${bpm - 4} / ${bpm + 12} BPM",
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    title = "ACTIVE HRM",
                    value = "COOSPO (92%)",
                    accent = Color(0xFF10B981),
                    modifier = Modifier.weight(1f)
                )
            }

            // Controls with Lock Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { isScreenLocked = !isScreenLocked },
                    modifier = Modifier
                        .size(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isScreenLocked) Color(0xFFEF4444) else Color(0xFF334155)
                    )
                ) {
                    Text(if (isScreenLocked) "🔒" else "🔓", fontSize = 16.sp)
                }

                Button(
                    onClick = onTogglePause,
                    enabled = !isScreenLocked,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) Color(0xFFF59E0B) else Color(0xFF10B981)
                    )
                ) {
                    Text(if (isRecording) "PAUSE" else "RESUME", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }

                Button(
                    onClick = { /* Stop */ },
                    enabled = !isScreenLocked,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("STOP", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
fun MetricTile(
    title: String,
    value: String,
    isSub: Boolean = false,
    accent: Color = Color.White,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                color = accent,
                fontSize = if (isSub) 14.sp else 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = if (isSub) FontFamily.SansSerif else FontFamily.Monospace
            )
        }
    }
}

// =====================================================================================
// SIMULATOR CONTROL PANEL & FLOATING SWITCHER
// =====================================================================================
@Composable
fun SimulatorControlPanel(
    bpm: Int,
    onBpmChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1E1E24).copy(alpha = 0.95f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "TEST BPM: $bpm",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(90.dp)
            )
            Slider(
                value = bpm.toFloat(),
                onValueChange = { onBpmChange(it.toInt()) },
                valueRange = 60f..195f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color(0xFF3B82F6)
                )
            )
        }
    }
}

@Composable
fun PrototypeSwitcherBar(
    currentVariant: PrototypeVariant,
    onVariantChange: (PrototypeVariant) -> Unit,
    modifier: Modifier = Modifier
) {
    val variants = PrototypeVariant.values()
    val currentIndex = variants.indexOf(currentVariant)

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF0F172A).copy(alpha = 0.92f),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF38BDF8)),
        shadowElevation = 12.dp,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Previous Variant Arrow
            Text(
                text = "◀",
                color = Color(0xFF38BDF8),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable {
                        val prev = if (currentIndex == 0) variants.size - 1 else currentIndex - 1
                        onVariantChange(variants[prev])
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )

            // Current Variant Badge & Label
            Text(
                text = "VARIANT ${currentVariant.label}: ${currentVariant.description}",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            // Next Variant Arrow
            Text(
                text = "▶",
                color = Color(0xFF38BDF8),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable {
                        val next = (currentIndex + 1) % variants.size
                        onVariantChange(variants[next])
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

private fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

@Preview(name = "Variant A - Immersive", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun PreviewVariantA() {
    VariantA_FullBleedImmersive(
        bpm = 148,
        zone = ZonePalette.Zone3,
        isRecording = true,
        effectiveSeconds = 2540L,
        pausedSeconds = 120L,
        onTogglePause = {}
    )
}

@Preview(name = "Variant B - Tachometer", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun PreviewVariantB() {
    VariantB_TachometerWaveform(
        bpm = 164,
        zone = ZonePalette.Zone4,
        isRecording = true,
        effectiveSeconds = 3120L,
        pausedSeconds = 60L,
        samples = listOf(140, 142, 145, 150, 155, 158, 160, 162, 164),
        onTogglePause = {}
    )
}

@Preview(name = "Variant C - Split Grid", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun PreviewVariantC() {
    VariantC_SplitHUDGrid(
        bpm = 176,
        zone = ZonePalette.Zone5,
        isRecording = true,
        effectiveSeconds = 4210L,
        pausedSeconds = 180L,
        onTogglePause = {}
    )
}
