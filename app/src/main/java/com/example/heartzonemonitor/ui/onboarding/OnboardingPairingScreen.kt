package com.example.heartzonemonitor.ui.onboarding

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.heartzonemonitor.data.ble.BleDiscoveryState
import com.example.heartzonemonitor.data.ble.BleDiscoveryUnavailableReason
import com.example.heartzonemonitor.data.ble.DiscoveredHrm
import com.example.heartzonemonitor.ui.ble.BlePermissionRequester
import com.example.heartzonemonitor.ui.theme.AthleticLime
import com.example.heartzonemonitor.ui.theme.BorderSlate
import com.example.heartzonemonitor.ui.theme.DarkSlate
import com.example.heartzonemonitor.ui.theme.ErrorRed
import com.example.heartzonemonitor.ui.theme.TertiaryCyan
import com.example.heartzonemonitor.ui.theme.TextDisabled
import com.example.heartzonemonitor.ui.theme.TextPrimary
import com.example.heartzonemonitor.ui.theme.TextSecondary
import com.example.heartzonemonitor.ui.theme.VoidBlack
import com.example.heartzonemonitor.ui.theme.WarningAmber

data class OnboardingPairingActions(
    val onRequestPermissions: () -> Unit = {},
    val onRescan: () -> Unit = {},
    val onPairDevice: (DiscoveredHrm) -> Unit = {},
    val onPairLater: () -> Unit = {},
    val onDismissError: () -> Unit = {}
)

/**
 * Formats a Bluetooth MAC address into a compact onboarding snippet such as `AA:BB:CC:...:12`.
 */
fun formatMacSnippet(macAddress: String): String {
    val trimmed = macAddress.trim()
    val segments = trimmed.split(":")
    if (segments.size == 6 && segments.all { it.isNotEmpty() }) {
        return "${segments[0]}:${segments[1]}:${segments[2]}:...:${segments[5]}"
    }
    return if (trimmed.length > 11) {
        "${trimmed.take(8)}...${trimmed.takeLast(2)}"
    } else {
        trimmed
    }
}

/**
 * Maps a Bluetooth LE RSSI reading in dBm to a 1..4 signal strength bar level.
 */
fun rssiToSignalBars(rssi: Int): Int = when {
    rssi >= -60 -> 4
    rssi >= -72 -> 3
    rssi >= -82 -> 2
    else -> 1
}

@Composable
fun OnboardingPairingScreen(
    viewModel: OnboardingPairingViewModel,
    onPairingComplete: () -> Unit,
    onPairLater: () -> Unit,
    modifier: Modifier = Modifier,
    requestPermissionsImmediately: Boolean = true,
    shouldShowRationale: ((String) -> Boolean)? = null,
    onOpenAppSettings: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.pairingCompleted) {
        if (uiState.pairingCompleted) {
            onPairingComplete()
        }
    }

    BlePermissionRequester(
        modifier = modifier,
        permissionChecker = viewModel.permissionChecker,
        includeNotifications = false,
        requestImmediately = requestPermissionsImmediately,
        shouldShowRationale = shouldShowRationale,
        onOpenAppSettings = onOpenAppSettings,
        onPermissionsResult = viewModel::onPermissionsUpdated
    ) { _, requestPermissions ->
        OnboardingPairingContent(
            uiState = uiState,
            actions = OnboardingPairingActions(
                onRequestPermissions = requestPermissions,
                onRescan = {
                    if (uiState.isBlePermissionGranted) {
                        viewModel.startOrRestartScan()
                    } else {
                        requestPermissions()
                    }
                },
                onPairDevice = viewModel::pairDevice,
                onPairLater = {
                    viewModel.onPairLater()
                    onPairLater()
                },
                onDismissError = viewModel::dismissError
            ),
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun OnboardingPairingContent(
    uiState: OnboardingPairingUiState,
    actions: OnboardingPairingActions,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = VoidBlack,
        bottomBar = {
            OnboardingPairingBottomBar(
                onPairLater = actions.onPairLater
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PairingStepHeader()

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Pair Heart Rate Monitor",
                    color = TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                )
                Text(
                    text = "Scanning for standard Bluetooth SIG Heart Rate Monitors (0x180D). Moisten strap contacts and wear your strap to awaken it.",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }

            BluetoothPermissionStatusCard(
                isGranted = uiState.isBlePermissionGranted,
                isScanning = uiState.isScanning,
                onRequestPermissions = actions.onRequestPermissions
            )

            if (uiState.errorMessage != null) {
                PairingErrorBanner(
                    message = uiState.errorMessage,
                    onDismiss = actions.onDismissError
                )
            }

            ScanningBeaconCard(
                discoveryState = uiState.discoveryState,
                isPairing = uiState.isPairing,
                onRescan = actions.onRescan
            )

            DiscoveredHrmSection(
                uiState = uiState,
                onPairDevice = actions.onPairDevice
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PairingStepHeader() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "SETUP",
                    color = TertiaryCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = " / ",
                    color = TextDisabled,
                    fontSize = 11.sp
                )
                Text(
                    text = "Step 2 of 2: Heart Rate Monitor",
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }

            Row(
                modifier = Modifier
                    .background(DarkSlate, RoundedCornerShape(4.dp))
                    .border(1.dp, BorderSlate, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "BLE 5.0 · 0x180D",
                    color = AthleticLime,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(AthleticLime)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(AthleticLime)
            )
        }
    }
}

@Composable
private fun BluetoothPermissionStatusCard(
    isGranted: Boolean,
    isScanning: Boolean,
    onRequestPermissions: () -> Unit
) {
    val borderColor = if (isGranted) BorderSlate else WarningAmber
    val badgeColor = if (isGranted) AthleticLime else WarningAmber

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSlate, RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(badgeColor, CircleShape)
            )
            Text(
                text = when {
                    isGranted && isScanning -> "BLE SCAN ACTIVE"
                    isGranted -> "BLUETOOTH PERMISSIONS GRANTED"
                    else -> "BLUETOOTH PERMISSIONS REQUIRED"
                },
                color = if (isGranted) TextPrimary else WarningAmber,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
        }

        Text(
            text = if (isGranted) {
                "Bluetooth scan and connect permissions enabled (neverForLocation — GPS location is never accessed)."
            } else {
                "Allow BLUETOOTH_SCAN (neverForLocation) and BLUETOOTH_CONNECT to discover nearby Heart Rate Monitors. GPS location is never requested."
            },
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )

        if (!isGranted) {
            Button(
                onClick = onRequestPermissions,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag("grant_ble_permissions_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AthleticLime,
                    contentColor = VoidBlack
                )
            ) {
                Text(
                    text = "Grant Bluetooth Permissions",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
private fun PairingErrorBanner(
    message: String,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSlate, RoundedCornerShape(12.dp))
            .border(1.5.dp, ErrorRed, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("pairing_error_banner"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "PAIRING FAILED",
                color = ErrorRed,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
            Text(
                text = message,
                color = TextPrimary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        TextButton(
            onClick = onDismiss,
            modifier = Modifier.testTag("dismiss_pairing_error_button")
        ) {
            Text(
                text = "Dismiss",
                color = AthleticLime,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ScanningBeaconCard(
    discoveryState: BleDiscoveryState,
    isPairing: Boolean,
    onRescan: () -> Unit
) {
    val isScanning = discoveryState is BleDiscoveryState.Scanning

    val statusHeadline = when {
        isPairing -> "Connecting to Heart Rate Monitor..."
        discoveryState is BleDiscoveryState.Scanning -> "Scanning for nearby Heart Rate Monitors..."
        discoveryState is BleDiscoveryState.TimedOut -> "Scan complete (30s timeout)"
        discoveryState is BleDiscoveryState.Stopped -> "Scan paused"
        discoveryState is BleDiscoveryState.Unavailable -> when (discoveryState.reason) {
            BleDiscoveryUnavailableReason.BLUETOOTH_DISABLED -> "Bluetooth is turned off"
            BleDiscoveryUnavailableReason.PERMISSION_DENIED -> "Bluetooth permissions required"
        }
        else -> "Ready to scan for Heart Rate Monitors"
    }

    val statusCaption = when {
        isPairing -> "SCAN STOPPED PRIOR TO GATT CONNECTION"
        discoveryState is BleDiscoveryState.TimedOut -> "TAP RESCAN IF YOUR STRAP DID NOT APPEAR"
        discoveryState is BleDiscoveryState.Unavailable &&
            discoveryState.reason == BleDiscoveryUnavailableReason.BLUETOOTH_DISABLED ->
            "ENABLE BLUETOOTH IN ANDROID SETTINGS, THEN RESCAN"
        discoveryState is BleDiscoveryState.Unavailable &&
            discoveryState.reason == BleDiscoveryUnavailableReason.PERMISSION_DENIED ->
            "GRANT BLUETOOTH PERMISSIONS TO START 0x180D SCAN"
        else -> "SERVICE: 0x180D (HEART RATE PROFILE)"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSlate, RoundedCornerShape(12.dp))
            .border(1.dp, BorderSlate, RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .background(VoidBlack, RoundedCornerShape(6.dp))
                    .border(
                        width = 1.dp,
                        color = if (isScanning) TertiaryCyan else BorderSlate,
                        shape = RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isScanning) "SCANNING 0x180D" else "DISCOVERY RADAR",
                    color = if (isScanning) TertiaryCyan else TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }

            OutlinedButton(
                onClick = onRescan,
                enabled = !isPairing,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("rescan_button"),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, if (isPairing) BorderSlate else AthleticLime),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = VoidBlack,
                    contentColor = AthleticLime,
                    disabledContentColor = TextDisabled
                )
            ) {
                Text(
                    text = "Rescan",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.8.sp
                )
            }
        }

        BeaconRadarGraphic(
            isScanning = isScanning,
            isPairing = isPairing
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = statusHeadline,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = statusCaption,
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.8.sp
            )
        }
    }
}

@Composable
private fun BeaconRadarGraphic(
    isScanning: Boolean,
    isPairing: Boolean
) {
    val ringProgress = if (isScanning) {
        val transition = rememberInfiniteTransition(label = "beaconRadar")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "beaconRingProgress"
        )
        animated
    } else {
        0f
    }

    Box(
        modifier = Modifier.size(124.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val maxRadius = size.minDimension / 2f
            val innerRadius = maxRadius * 0.42f

            // Static radar target grid circles
            drawCircle(
                color = BorderSlate.copy(alpha = 0.7f),
                radius = maxRadius * 0.92f,
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                )
            )
            drawCircle(
                color = BorderSlate.copy(alpha = 0.5f),
                radius = maxRadius * 0.68f,
                style = Stroke(width = 1.dp.toPx())
            )

            if (isScanning) {
                listOf(0f, 0.5f).forEach { phaseOffset ->
                    val phase = (ringProgress + phaseOffset) % 1f
                    val radius = innerRadius + (maxRadius - innerRadius) * phase
                    val alpha = (1f - phase) * 0.7f
                    val ringColor = if (phaseOffset == 0f) AthleticLime else TertiaryCyan
                    drawCircle(
                        color = ringColor.copy(alpha = alpha),
                        radius = radius,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(VoidBlack)
                .border(
                    width = 2.dp,
                    color = when {
                        isPairing -> TertiaryCyan
                        isScanning -> AthleticLime
                        else -> BorderSlate
                    },
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isPairing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = TertiaryCyan,
                    strokeWidth = 2.5.dp
                )
            } else {
                Text(
                    text = "HRM",
                    color = if (isScanning) AthleticLime else TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
private fun DiscoveredHrmSection(
    uiState: OnboardingPairingUiState,
    onPairDevice: (DiscoveredHrm) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "DISCOVERED HRMS (${uiState.discoveredHrms.size})",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.2.sp
            )
            Text(
                text = if (uiState.isScanning) "AUTO-DISCOVERY ON" else "0x180D ONLY",
                color = if (uiState.isScanning) AthleticLime else TextDisabled,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
        }

        if (uiState.discoveredHrms.isEmpty()) {
            EmptyDiscoveryCard(discoveryState = uiState.discoveryState)
        } else {
            uiState.discoveredHrms.forEachIndexed { index, device ->
                DiscoveredHrmCard(
                    device = device,
                    isRecommended = index == 0,
                    isConnecting = uiState.pairingMacAddress == device.macAddress,
                    isAnyPairing = uiState.isPairing,
                    onPairClick = { onPairDevice(device) }
                )
            }
        }
    }
}

@Composable
private fun EmptyDiscoveryCard(discoveryState: BleDiscoveryState) {
    val title = when (discoveryState) {
        is BleDiscoveryState.Scanning -> "Listening for nearby Heart Rate Monitors..."
        is BleDiscoveryState.TimedOut -> "No Heart Rate Monitors found"
        is BleDiscoveryState.Unavailable -> when (discoveryState.reason) {
            BleDiscoveryUnavailableReason.BLUETOOTH_DISABLED -> "Bluetooth is disabled"
            BleDiscoveryUnavailableReason.PERMISSION_DENIED -> "Bluetooth access needed"
        }
        else -> "No Heart Rate Monitors detected yet"
    }

    val subtitle = when (discoveryState) {
        is BleDiscoveryState.Scanning ->
            "Make sure your chest strap or optical armband is worn with moistened contacts and advertising 0x180D."
        is BleDiscoveryState.TimedOut ->
            "Check that your Heart Rate Monitor is awake and not connected to another phone, then tap Rescan."
        is BleDiscoveryState.Unavailable -> when (discoveryState.reason) {
            BleDiscoveryUnavailableReason.BLUETOOTH_DISABLED ->
                "Turn on Bluetooth in system settings and tap Rescan, or choose Pair Later."
            BleDiscoveryUnavailableReason.PERMISSION_DENIED ->
                "Grant Bluetooth permissions above to scan for nearby Heart Rate Monitors."
        }
        else ->
            "Tap Rescan to search for nearby 0x180D Heart Rate Monitors, or select Pair Later."
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSlate, RoundedCornerShape(12.dp))
            .border(1.dp, BorderSlate, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = subtitle,
            color = TextSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
    }
}

@Composable
private fun DiscoveredHrmCard(
    device: DiscoveredHrm,
    isRecommended: Boolean,
    isConnecting: Boolean,
    isAnyPairing: Boolean,
    onPairClick: () -> Unit
) {
    val displayName = device.advertisedName?.takeIf { it.isNotBlank() }
        ?: OnboardingPairingViewModel.DEFAULT_HRM_NAME
    val macSnippet = formatMacSnippet(device.macAddress)
    val borderColor = when {
        isConnecting -> TertiaryCyan
        isRecommended -> AthleticLime
        else -> BorderSlate
    }
    val borderWidth = if (isRecommended || isConnecting) 2.dp else 1.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSlate)
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
            .clickable(enabled = !isAnyPairing, onClick = onPairClick)
            .padding(16.dp)
            .testTag("discovered_hrm_card_${device.macAddress}"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isRecommended) {
                    Box(
                        modifier = Modifier
                            .background(AthleticLime, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "RECOMMENDED",
                            color = VoidBlack,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    }
                }

                Text(
                    text = displayName,
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "MAC: $macSnippet",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.5.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            RssiSignalIndicator(
                macAddress = device.macAddress,
                rssi = device.rssi
            )
        }

        HorizontalDivider(color = BorderSlate.copy(alpha = 0.6f), thickness = 1.dp)

        Button(
            onClick = onPairClick,
            enabled = !isAnyPairing,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag("pair_hrm_button_${device.macAddress}"),
            shape = RoundedCornerShape(10.dp),
            colors = if (isRecommended) {
                ButtonDefaults.buttonColors(
                    containerColor = AthleticLime,
                    contentColor = VoidBlack,
                    disabledContainerColor = VoidBlack,
                    disabledContentColor = if (isConnecting) TertiaryCyan else TextDisabled
                )
            } else {
                ButtonDefaults.buttonColors(
                    containerColor = VoidBlack,
                    contentColor = TextPrimary,
                    disabledContainerColor = VoidBlack.copy(alpha = 0.7f),
                    disabledContentColor = if (isConnecting) TertiaryCyan else TextDisabled
                )
            },
            border = if (isRecommended && !isConnecting) {
                null
            } else {
                BorderStroke(1.dp, if (isConnecting) TertiaryCyan else BorderSlate)
            }
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = TertiaryCyan,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Connecting...",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            } else {
                Text(
                    text = if (isRecommended) "Connect & Pair" else "Connect",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
private fun RssiSignalIndicator(
    macAddress: String,
    rssi: Int
) {
    val activeBars = rssiToSignalBars(rssi)
    val barColor = when (activeBars) {
        4 -> AthleticLime
        3 -> TertiaryCyan
        2 -> WarningAmber
        else -> ErrorRed
    }
    val barHeights = listOf(6.dp, 9.dp, 12.dp, 15.dp)

    Row(
        modifier = Modifier
            .background(VoidBlack, RoundedCornerShape(6.dp))
            .border(1.dp, BorderSlate, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .testTag("rssi_bars_$macAddress")
            .semantics(mergeDescendants = true) {
                contentDescription = "Signal strength $activeBars of 4 bars, $rssi dBm"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            barHeights.forEachIndexed { index, height ->
                val isLit = index < activeBars
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(height)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (isLit) barColor else BorderSlate)
                )
            }
        }

        Text(
            text = "$rssi dBm",
            color = barColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun OnboardingPairingBottomBar(
    onPairLater: () -> Unit
) {
    Surface(
        color = VoidBlack,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderSlate)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(
                onClick = onPairLater,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag("pair_later_button"),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, BorderSlate),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = DarkSlate,
                    contentColor = TextPrimary
                )
            ) {
                Text(
                    text = "Pair Later",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }

            Text(
                text = "Explore the app now — pair an Active HRM anytime in Settings",
                color = TextDisabled,
                fontSize = 11.sp
            )
        }
    }
}
