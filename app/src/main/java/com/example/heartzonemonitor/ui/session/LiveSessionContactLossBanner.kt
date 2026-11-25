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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.heartzonemonitor.ui.theme.WarningAmber

const val LIVE_SESSION_CONTACT_LOSS_BANNER_TAG = "live_session_contact_loss_banner"
const val SENSOR_CONTACT_LOSS_BANNER_TEXT = "Sensor contact lost — check strap positioning"

val LiveSessionContactLossWarningAmber = WarningAmber
val LiveSessionContactLossContainer = Color.Black.copy(alpha = 0.45f)

/**
 * Inline Athletic Monolith warning banner displayed when the connected Active HRM reports lost
 * skin contact via characteristic `0x2A37` or when a Session is auto-paused for sensor contact
 * loss.
 */
@Composable
fun LiveSessionContactLossBanner(
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = LiveSessionContactLossContainer,
        border = BorderStroke(1.5.dp, LiveSessionContactLossWarningAmber),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .testTag(LIVE_SESSION_CONTACT_LOSS_BANNER_TAG)
            .semantics {
                this[LiveSessionContainerColorKey] = LiveSessionContactLossContainer
                this[LiveSessionBorderColorKey] = LiveSessionContactLossWarningAmber
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(LiveSessionContactLossWarningAmber, CircleShape)
            )
            Text(
                text = SENSOR_CONTACT_LOSS_BANNER_TEXT,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 20.sp
            )
        }
    }
}
