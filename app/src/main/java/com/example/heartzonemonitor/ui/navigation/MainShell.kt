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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import com.example.heartzonemonitor.ui.history.SessionHistoryActions
import com.example.heartzonemonitor.ui.history.SessionHistoryContent
import com.example.heartzonemonitor.ui.history.SessionHistoryScreen
import com.example.heartzonemonitor.ui.history.SessionHistoryUiState
import com.example.heartzonemonitor.ui.history.SessionHistoryViewModel
import com.example.heartzonemonitor.ui.session.LiveSessionActions
import com.example.heartzonemonitor.ui.session.LiveSessionContent
import com.example.heartzonemonitor.ui.session.LiveSessionNoHrmNotice
import com.example.heartzonemonitor.ui.session.LiveSessionScreen
import com.example.heartzonemonitor.ui.session.LiveSessionUiState
import com.example.heartzonemonitor.ui.session.LiveSessionViewModel

// Athletic Monolith Design Tokens
private val VoidBlack = Color(0xFF0B0F19)
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
    val onManageDevices: () -> Unit = {},
    val onSessionClick: (String) -> Unit = {}
)

@Composable
fun MainShell(
    userProfile: UserProfileEntity?,
    modifier: Modifier = Modifier,
    initialTab: MainTab = MainTab.LIVE_SESSION,
    activeHrm: RememberedHrmEntity? = null,
    onManageDevices: () -> Unit = {},
    onSessionClick: (String) -> Unit = {},
    liveSessionViewModelFactory: (() -> LiveSessionViewModel)? = null,
    sessionHistoryViewModelFactory: (() -> SessionHistoryViewModel)? = null,
    openLiveSessionRequestId: Int = 0
) {
    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }
    var lastHandledOpenLiveSessionRequestId by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(openLiveSessionRequestId) {
        if (openLiveSessionRequestId > lastHandledOpenLiveSessionRequestId) {
            lastHandledOpenLiveSessionRequestId = openLiveSessionRequestId
            selectedTab = MainTab.LIVE_SESSION
        }
    }
    val liveSessionViewModel = remember(liveSessionViewModelFactory != null) {
        liveSessionViewModelFactory?.invoke()
    }
    val viewModelStoreOwner = LocalViewModelStoreOwner.current
    val sessionHistoryViewModel: SessionHistoryViewModel? = when {
        sessionHistoryViewModelFactory == null -> null
        viewModelStoreOwner != null -> viewModel(
            viewModelStoreOwner = viewModelStoreOwner,
            factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    sessionHistoryViewModelFactory() as T
            }
        )
        else -> remember(true) { sessionHistoryViewModelFactory() }
    }
    val liveSessionState = liveSessionViewModel?.uiState?.collectAsState()?.value

    MainShellContent(
        uiState = MainShellUiState(
            selectedTab = selectedTab,
            isImmersionMode = liveSessionState?.isImmersionMode ?: false,
            activeHrm = activeHrm
        ),
        actions = MainShellActions(
            onTabSelected = { selectedTab = it },
            onManageDevices = onManageDevices,
            onSessionClick = onSessionClick
        ),
        userProfile = userProfile,
        modifier = modifier,
        liveSessionViewModel = liveSessionViewModel,
        sessionHistoryViewModel = sessionHistoryViewModel
    )
}

@Composable
fun MainShellContent(
    uiState: MainShellUiState,
    actions: MainShellActions,
    userProfile: UserProfileEntity?,
    modifier: Modifier = Modifier,
    liveSessionViewModel: LiveSessionViewModel? = null,
    sessionHistoryViewModel: SessionHistoryViewModel? = null
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
                    } else if (liveSessionViewModel != null) {
                        LiveSessionScreen(
                            viewModel = liveSessionViewModel,
                            onManageDevices = actions.onManageDevices
                        )
                    } else {
                        LiveSessionContent(
                            uiState = LiveSessionUiState(activeHrm = uiState.activeHrm),
                            actions = LiveSessionActions(onManageDevices = actions.onManageDevices)
                        )
                    }
                }
                MainTab.SESSION_HISTORY -> {
                    if (sessionHistoryViewModel != null) {
                        SessionHistoryScreen(
                            viewModel = sessionHistoryViewModel,
                            onSessionClick = actions.onSessionClick
                        )
                    } else {
                        SessionHistoryContent(
                            uiState = SessionHistoryUiState(isLoading = false),
                            actions = SessionHistoryActions(onSessionClick = actions.onSessionClick)
                        )
                    }
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
