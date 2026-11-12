package com.example.heartzonemonitor.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.example.heartzonemonitor.ui.onboarding.OnboardingProfileScreen
import com.example.heartzonemonitor.ui.onboarding.OnboardingProfileViewModel

private val VoidBlack = Color(0xFF0B0F19)
private val BorderSlate = Color(0xFF334155)
private val AthleticLime = Color(0xFFBDCF73)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFF94A3B8)

@Composable
fun RootNavHost(
    rootViewModel: RootViewModel,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    onExitApp: () -> Unit = {},
    onboardingViewModelFactory: () -> OnboardingProfileViewModel
) {
    val navState by rootViewModel.navState.collectAsState()

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
                        OnboardingPairingPlaceholder(
                            onPairLater = {
                                navController.navigate(NavDestinations.MAIN_GRAPH) {
                                    popUpTo(NavDestinations.ONBOARDING_GRAPH) {
                                        inclusive = true
                                    }
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                }

                if (hasProfile) {
                    val userProfile = (state as? RootNavState.Ready)?.userProfile

                    navigation(
                        startDestination = NavDestinations.LIVE_SESSION,
                        route = NavDestinations.MAIN_GRAPH
                    ) {
                        composable(NavDestinations.LIVE_SESSION) {
                            MainShell(
                                userProfile = userProfile,
                                initialTab = MainTab.LIVE_SESSION,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        composable(NavDestinations.SESSION_HISTORY) {
                            MainShell(
                                userProfile = userProfile,
                                initialTab = MainTab.SESSION_HISTORY,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        composable(NavDestinations.SETTINGS) {
                            MainShell(
                                userProfile = userProfile,
                                initialTab = MainTab.SETTINGS,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OnboardingPairingPlaceholder(
    onPairLater: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = VoidBlack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "STEP 2 OF 2",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    color = AthleticLime
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "PAIR HEART RATE MONITOR",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Connect your standard Bluetooth LE Heart Rate Monitor (0x180D) or choose to pair later in Settings.",
                    fontSize = 14.sp,
                    color = TextSecondary
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                OutlinedButton(
                    onClick = onPairLater,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .border(1.dp, BorderSlate, RoundedCornerShape(28.dp)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Text(
                        text = "PAIR LATER",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                }
            }
        }
    }
}
