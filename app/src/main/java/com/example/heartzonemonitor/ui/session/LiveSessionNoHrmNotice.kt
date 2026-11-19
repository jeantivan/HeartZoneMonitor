package com.example.heartzonemonitor.ui.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Athletic Monolith Design Tokens
private val VoidBlack = Color(0xFF0B0F19)
private val DarkSlate = Color(0xFF1E293B)
private val BorderSlate = Color(0xFF334155)
private val AthleticLime = Color(0xFFBDCF73)
private val WarningAmber = Color(0xFFF59E0B)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFF94A3B8)
private val TextDisabled = Color(0xFF64748B)

/**
 * Stateless Athletic Monolith Live Session standby view displayed when no Active HRM is paired
 * (for example, after selecting "Pair Later" during onboarding). Surfaces a prominent notice,
 * a shortcut to pair a Heart Rate Monitor in Settings, and a visibly disabled Start Session control.
 */
@Composable
fun LiveSessionNoHrmNotice(
    onPairHrmInSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = VoidBlack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Status Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LIVE SESSION",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.5.sp
                )

                Row(
                    modifier = Modifier
                        .background(DarkSlate, RoundedCornerShape(6.dp))
                        .border(1.dp, WarningAmber, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(WarningAmber, CircleShape)
                    )
                    Text(
                        text = "NO ACTIVE HRM",
                        color = WarningAmber,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Inert Hero Telemetry Preview
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "--",
                    color = TextDisabled,
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-4).sp,
                    lineHeight = 96.sp
                )
                Box(
                    modifier = Modifier
                        .background(DarkSlate, RoundedCornerShape(16.dp))
                        .border(1.dp, BorderSlate, RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "BPM  |  NO HRM CONNECTED",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                }
                Text(
                    text = "00:00:00",
                    color = TextDisabled,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Prominent Warning Notice Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSlate, RoundedCornerShape(14.dp))
                    .border(1.5.dp, WarningAmber, RoundedCornerShape(14.dp))
                    .padding(18.dp)
                    .testTag("live_session_no_hrm_notice"),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(VoidBlack, RoundedCornerShape(4.dp))
                        .border(1.dp, WarningAmber, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "HEART RATE MONITOR REQUIRED",
                        color = WarningAmber,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }

                Text(
                    text = "No Heart Rate Monitor paired",
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                )

                Text(
                    text = "Pair a Bluetooth LE Heart Rate Monitor (0x180D) in Settings to receive live heart rate telemetry and unlock Session recording.",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )

                Button(
                    onClick = onPairHrmInSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .testTag("pair_hrm_in_settings_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AthleticLime,
                        contentColor = VoidBlack
                    )
                ) {
                    Text(
                        text = "Pair HRM in Settings",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Disabled Start Session Control
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .testTag("start_session_button"),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, BorderSlate),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AthleticLime,
                        contentColor = VoidBlack,
                        disabledContainerColor = DarkSlate,
                        disabledContentColor = TextDisabled
                    )
                ) {
                    Text(
                        text = "Start Session",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }

                Text(
                    text = "Pair an Active HRM to enable Session recording.",
                    color = TextDisabled,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
