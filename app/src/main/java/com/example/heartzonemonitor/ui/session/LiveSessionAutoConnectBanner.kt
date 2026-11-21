package com.example.heartzonemonitor.ui.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.heartzonemonitor.data.ble.AutoConnectState

const val LIVE_SESSION_AUTO_CONNECT_BANNER_TAG = "live_session_auto_connect_banner"
const val LIVE_SESSION_DORMANT_PROMPT_TAG = "live_session_dormant_prompt"
const val DORMANT_STRAP_PROMPT_TEXT =
    "Sensor not found. Ensure strap is moist and worn, or tap to Scan."

private val DormantPromptAmber = Color(0xFFF59E0B)
private val ConnectingBannerContainer = Color.Black.copy(alpha = 0.32f)
private val ConnectingBannerBorder = Color.White.copy(alpha = 0.25f)
private val DormantPromptContainer = Color.Black.copy(alpha = 0.45f)

/**
 * Formats the subtle ambient connecting status text displayed while attempting direct background
 * connection to the Active HRM.
 */
fun formatConnectingBannerText(hrmDisplayName: String): String =
    "Connecting to $hrmDisplayName..."

/**
 * Ambient BLE auto-connect status banner and dormant strap inline prompt rendered on the Live
 * Session screen. Emits nothing when [AutoConnectState.Idle] or [AutoConnectState.Connected] so
 * the focused Athletic Monolith telemetry canvas remains uncluttered.
 */
@Composable
fun LiveSessionAutoConnectBanner(
    autoConnectState: AutoConnectState,
    onOpenScanner: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (autoConnectState) {
        AutoConnectState.Idle,
        is AutoConnectState.Connected -> return

        is AutoConnectState.Connecting -> {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = ConnectingBannerContainer,
                border = BorderStroke(1.dp, ConnectingBannerBorder),
                modifier = modifier
                    .padding(bottom = 16.dp)
                    .testTag(LIVE_SESSION_AUTO_CONNECT_BANNER_TAG)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = Color.White.copy(alpha = 0.85f),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = formatConnectingBannerText(autoConnectState.hrmDisplayName),
                        color = Color.White.copy(alpha = 0.90f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        is AutoConnectState.DormantPrompt -> {
            Surface(
                onClick = onOpenScanner,
                shape = RoundedCornerShape(14.dp),
                color = DormantPromptContainer,
                border = BorderStroke(1.5.dp, DormantPromptAmber),
                modifier = modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .heightIn(min = 56.dp)
                    .testTag(LIVE_SESSION_DORMANT_PROMPT_TAG)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(DormantPromptAmber, CircleShape)
                    )
                    Text(
                        text = DORMANT_STRAP_PROMPT_TEXT,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}
