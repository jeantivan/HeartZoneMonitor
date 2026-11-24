package com.example.heartzonemonitor.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.heartzonemonitor.ui.devices.RememberedHrmsActions
import com.example.heartzonemonitor.ui.devices.RememberedHrmsContent
import com.example.heartzonemonitor.ui.devices.RememberedHrmsScreen
import com.example.heartzonemonitor.ui.devices.RememberedHrmsUiState
import com.example.heartzonemonitor.ui.devices.RememberedHrmsViewModel
import com.example.heartzonemonitor.ui.history.SessionDetailPlaceholder
import com.example.heartzonemonitor.ui.history.SessionHistoryViewModel
import com.example.heartzonemonitor.ui.onboarding.OnboardingPairingActions
import com.example.heartzonemonitor.ui.onboarding.OnboardingPairingContent
import com.example.heartzonemonitor.ui.onboarding.OnboardingPairingScreen
import com.example.heartzonemonitor.ui.onboarding.OnboardingPairingUiState
import com.example.heartzonemonitor.ui.onboarding.OnboardingPairingViewModel
import com.example.heartzonemonitor.ui.onboarding.OnboardingProfileScreen
import com.example.heartzonemonitor.ui.onboarding.OnboardingProfileViewModel
import com.example.heartzonemonitor.ui.session.LiveSessionViewModel

private val VoidBlack = Color(0xFF0B0F19)

@Composable
fun RootNavHost(
    rootViewModel: RootViewModel,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    onExitApp: () -> Unit = {},
    onboardingViewModelFactory: () -> OnboardingProfileViewModel,
    onboardingPairingViewModelFactory: (() -> OnboardingPairingViewModel)? = null,
    rememberedHrmsViewModelFactory: (() -> RememberedHrmsViewModel)? = null,
    liveSessionViewModelFactory: (() -> LiveSessionViewModel)? = null,
    sessionHistoryViewModelFactory: (() -> SessionHistoryViewModel)? = null,
    openLiveSessionRequestId: Int = 0
) {
    val navState by rootViewModel.navState.collectAsState()
    var lastHandledOpenLiveSessionRequestId by rememberSaveable { mutableIntStateOf(0) }

    when (val state = navState) {
        RootNavState.Loading -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(VoidBlack)
            )
        }
        RootNavState.Onboarding, is RootNavState.Ready -> {
            val hasProfile = state is RootNavState.Ready
            val retainedOnboardingViewModel = remember { onboardingViewModelFactory() }

            LaunchedEffect(openLiveSessionRequestId) {
                if (openLiveSessionRequestId > lastHandledOpenLiveSessionRequestId) {
                    lastHandledOpenLiveSessionRequestId = openLiveSessionRequestId
                    if (hasProfile &&
                        navController.currentDestination?.route != NavDestinations.LIVE_SESSION
                    ) {
                        if (!navController.popBackStack(NavDestinations.LIVE_SESSION, inclusive = false)) {
                            navController.navigate(NavDestinations.LIVE_SESSION) {
                                popUpTo(NavDestinations.MAIN_GRAPH) {
                                    inclusive = false
                                }
                                launchSingleTop = true
                            }
                        }
                    }
                }
            }

            BackHandler(enabled = !hasProfile) {
                onExitApp()
            }

            NavHost(
                navController = navController,
                startDestination = if (hasProfile) NavDestinations.MAIN_GRAPH else NavDestinations.ONBOARDING_GRAPH,
                modifier = modifier.fillMaxSize()
            ) {
                navigation(
                    startDestination = NavDestinations.ONBOARDING_PROFILE,
                    route = NavDestinations.ONBOARDING_GRAPH
                ) {
                    composable(NavDestinations.ONBOARDING_PROFILE) {
                        OnboardingProfileScreen(
                            viewModel = retainedOnboardingViewModel,
                            onNavigateToPairing = {
                                navController.navigate(NavDestinations.ONBOARDING_PAIRING) {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }

                    composable(NavDestinations.ONBOARDING_PAIRING) {
                        val navigateToMain = {
                            navController.navigate(NavDestinations.MAIN_GRAPH) {
                                popUpTo(NavDestinations.ONBOARDING_GRAPH) {
                                    inclusive = true
                                }
                                launchSingleTop = true
                            }
                        }

                        if (onboardingPairingViewModelFactory != null) {
                            val pairingViewModel: OnboardingPairingViewModel = viewModel(
                                factory = object : ViewModelProvider.Factory {
                                    @Suppress("UNCHECKED_CAST")
                                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                                        onboardingPairingViewModelFactory() as T
                                }
                            )
                            OnboardingPairingScreen(
                                viewModel = pairingViewModel,
                                onPairingComplete = navigateToMain,
                                onPairLater = navigateToMain
                            )
                        } else {
                            OnboardingPairingContent(
                                uiState = OnboardingPairingUiState(),
                                actions = OnboardingPairingActions(
                                    onPairLater = navigateToMain
                                )
                            )
                        }
                    }
                }

                if (hasProfile) {
                    val userProfile = (state as? RootNavState.Ready)?.userProfile
                    val navigateToDevices = {
                        navController.navigate(NavDestinations.DEVICES) {
                            launchSingleTop = true
                        }
                    }
                    val navigateToSessionDetail: (String) -> Unit = { sessionId ->
                        navController.navigate(NavDestinations.sessionDetailRoute(sessionId)) {
                            launchSingleTop = true
                        }
                    }

                    navigation(
                        startDestination = NavDestinations.LIVE_SESSION,
                        route = NavDestinations.MAIN_GRAPH
                    ) {
                        composable(NavDestinations.LIVE_SESSION) {
                            val activeHrm by rootViewModel.activeHrm.collectAsState()
                            MainShell(
                                userProfile = userProfile,
                                initialTab = MainTab.LIVE_SESSION,
                                activeHrm = activeHrm,
                                onManageDevices = navigateToDevices,
                                onSessionClick = navigateToSessionDetail,
                                liveSessionViewModelFactory = liveSessionViewModelFactory,
                                sessionHistoryViewModelFactory = sessionHistoryViewModelFactory,
                                openLiveSessionRequestId = openLiveSessionRequestId,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        composable(NavDestinations.SESSION_HISTORY) {
                            val activeHrm by rootViewModel.activeHrm.collectAsState()
                            MainShell(
                                userProfile = userProfile,
                                initialTab = MainTab.SESSION_HISTORY,
                                activeHrm = activeHrm,
                                onManageDevices = navigateToDevices,
                                onSessionClick = navigateToSessionDetail,
                                liveSessionViewModelFactory = liveSessionViewModelFactory,
                                sessionHistoryViewModelFactory = sessionHistoryViewModelFactory,
                                openLiveSessionRequestId = openLiveSessionRequestId,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        composable(NavDestinations.SETTINGS) {
                            val activeHrm by rootViewModel.activeHrm.collectAsState()
                            MainShell(
                                userProfile = userProfile,
                                initialTab = MainTab.SETTINGS,
                                activeHrm = activeHrm,
                                onManageDevices = navigateToDevices,
                                onSessionClick = navigateToSessionDetail,
                                liveSessionViewModelFactory = liveSessionViewModelFactory,
                                sessionHistoryViewModelFactory = sessionHistoryViewModelFactory,
                                openLiveSessionRequestId = openLiveSessionRequestId,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        composable(
                            route = NavDestinations.SESSION_DETAIL_ROUTE,
                            arguments = listOf(
                                navArgument(NavDestinations.SESSION_DETAIL_ARG_ID) {
                                    type = NavType.StringType
                                }
                            )
                        ) { backStackEntry ->
                            val sessionId = backStackEntry.arguments
                                ?.getString(NavDestinations.SESSION_DETAIL_ARG_ID)
                                .orEmpty()
                            SessionDetailPlaceholder(
                                sessionId = sessionId,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable(NavDestinations.DEVICES) {
                            val devicesViewModel = remember {
                                rememberedHrmsViewModelFactory?.invoke()
                            }
                            if (devicesViewModel != null) {
                                RememberedHrmsScreen(
                                    viewModel = devicesViewModel,
                                    onNavigateBack = { navController.popBackStack() },
                                    onScanForNewHrm = {
                                        // Scanning screen route will be wired in Ticket 11
                                    }
                                )
                            } else {
                                RememberedHrmsContent(
                                    uiState = RememberedHrmsUiState(),
                                    actions = RememberedHrmsActions(
                                        onNavigateBack = { navController.popBackStack() }
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
