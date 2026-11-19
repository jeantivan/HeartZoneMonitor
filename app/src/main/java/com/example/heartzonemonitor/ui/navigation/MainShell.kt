package com.example.heartzonemonitor.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import com.example.heartzonemonitor.prototype.LiveSessionPrototypeScreen
import com.example.heartzonemonitor.ui.session.LiveSessionNoHrmNotice

// Athletic Monolith Design Tokens
private val VoidBlack = Color(0xFF0B0F19)
private val DarkSlate = Color(0xFF1E293B)
private val BorderSlate = Color(0xFF334155)
private val AthleticLime = Color(0xFFBDCF73)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFF94A3B8)

enum class MainTab(val route: String, val label: String) {
    LIVE_SESSION(NavDestinations.LIVE_SESSION, "LIVE"),
    SESSION_HISTORY(NavDestinations.SESSION_HISTORY, "HISTORY"),
    SETTINGS(NavDestinations.SETTINGS, "SETTINGS")
}

data class MainShellUiState(
    val selectedTab: MainTab = MainTab.LIVE_SESSION,
    val isImmersionMode: Boolean = false,
    val activeHrm: RememberedHrmEntity? = null
)

data class MainShellActions(
    val onTabSelected: (MainTab) -> Unit = {},
    val onManageDevices: () -> Unit = {}
)

@Composable
fun MainShell(
    userProfile: UserProfileEntity?,
    modifier: Modifier = Modifier,
    initialTab: MainTab = MainTab.LIVE_SESSION,
    activeHrm: RememberedHrmEntity? = null,
    onManageDevices: () -> Unit = {}
) {
    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }

    MainShellContent(
        uiState = MainShellUiState(
            selectedTab = selectedTab,
            activeHrm = activeHrm
        ),
        actions = MainShellActions(
            onTabSelected = { selectedTab = it },
            onManageDevices = onManageDevices
        ),
        userProfile = userProfile,
        modifier = modifier
    )
}

@Composable
fun MainShellContent(
    uiState: MainShellUiState,
    actions: MainShellActions,
    userProfile: UserProfileEntity?,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            if (!uiState.isImmersionMode) {
                MainShellBottomBar(
                    selectedTab = uiState.selectedTab,
                    onTabSelected = actions.onTabSelected
                )
            }
        },
        containerColor = VoidBlack
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (uiState.selectedTab) {
                MainTab.LIVE_SESSION -> {
                    if (uiState.activeHrm == null) {
                        LiveSessionNoHrmNotice(
                            onPairHrmInSettings = actions.onManageDevices
                        )
                    } else {
                        LiveSessionPrototypeScreen()
                    }
                }
                MainTab.SESSION_HISTORY -> {
                    SessionHistoryPlaceholder()
                }
                MainTab.SETTINGS -> {
                    SettingsPlaceholder(
                        userProfile = userProfile,
                        onManageDevices = actions.onManageDevices
                    )
                }
            }
        }
    }
}

@Composable
fun MainShellBottomBar(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(VoidBlack)
            .border(width = 1.dp, color = BorderSlate),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MainTab.entries.forEach { tab ->
            val isSelected = tab == selectedTab
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clickable { onTabSelected(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = tab.label,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = 1.5.sp,
                    color = if (isSelected) AthleticLime else TextSecondary
                )
            }
        }
    }
}

@Composable
fun SessionHistoryPlaceholder(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = VoidBlack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "SESSION HISTORY",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = TextPrimary
            )
            Text(
                text = "Recorded sessions will appear here",
                fontSize = 14.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun SettingsPlaceholder(
    userProfile: UserProfileEntity?,
    modifier: Modifier = Modifier,
    onManageDevices: () -> Unit = {}
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = VoidBlack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "SETTINGS & PROFILE",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = TextPrimary
            )
            if (userProfile != null) {
                Text(
                    text = "Birth Year: ${userProfile.birthYear} • Mode: ${userProfile.calculationMode}",
                    fontSize = 14.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onManageDevices,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AthleticLime,
                    contentColor = VoidBlack
                )
            ) {
                Text(
                    text = "Manage Heart Rate Monitors",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}
