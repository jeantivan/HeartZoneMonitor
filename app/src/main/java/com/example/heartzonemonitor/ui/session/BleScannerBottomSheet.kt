package com.example.heartzonemonitor.ui.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.heartzonemonitor.data.ble.BleDiscoveryState
import com.example.heartzonemonitor.data.ble.BleDiscoveryUnavailableReason
import com.example.heartzonemonitor.data.ble.BlePermissionChecker
import com.example.heartzonemonitor.data.ble.BlePermissionStatus
import com.example.heartzonemonitor.data.ble.DiscoveredHrm
import com.example.heartzonemonitor.ui.ble.BlePermissionRequester
import com.example.heartzonemonitor.ui.onboarding.OnboardingPairingViewModel
import com.example.heartzonemonitor.ui.onboarding.formatMacSnippet
import com.example.heartzonemonitor.ui.onboarding.rssiToSignalBars
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

const val BLE_SCANNER_BOTTOM_SHEET_TAG = "ble_scanner_bottom_sheet"
const val BLE_SCANNER_CLOSE_BUTTON_TAG = "ble_scanner_close_button"
const val BLE_SCANNER_RESCAN_BUTTON_TAG = "ble_scanner_rescan_button"
const val BLE_SCANNER_GRANT_PERMISSIONS_TAG = "ble_scanner_grant_permissions_button"
const val BLE_SCANNER_ERROR_BANNER_TAG = "ble_scanner_error_banner"
const val BLE_SCANNER_HRM_CARD_TAG_PREFIX = "ble_scanner_hrm_card_"
const val BLE_SCANNER_CONNECT_BUTTON_TAG_PREFIX = "ble_scanner_connect_button_"

/**
 * UI state for the Live Session BLE scanner bottom sheet.
 */
data class BleScannerSheetUiState(
    val isOpen: Boolean = false,
    val permissionStatus: BlePermissionStatus = BlePermissionStatus(
        hasScanPermission = false,
        hasConnectPermission = false,
        hasNotificationPermission = false
    ),
    val discoveryState: BleDiscoveryState = BleDiscoveryState.Idle,
    val discoveredHrms: List<DiscoveredHrm> = emptyList(),
    val connectingMacAddress: String? = null,
    val errorMessage: String? = null
) {
    val isBlePermissionGranted: Boolean
        get() = permissionStatus.allBlePermissionsGranted

    val isScanning: Boolean
        get() = discoveryState is BleDiscoveryState.Scanning

    val isConnecting: Boolean
        get() = connectingMacAddress != null
}

/**
 * Bundled user actions for [BleScannerBottomSheet] and [BleScannerSheetContent] following
 * CODING_STANDARDS.md Section 5.
 */
data class BleScannerSheetActions(
    val onDismiss: () -> Unit = {},
    val onRequestPermissions: () -> Unit = {},
    val onRescan: () -> Unit = {},
    val onSelectHrm: (DiscoveredHrm) -> Unit = {},
    val onDismissError: () -> Unit = {}
)

/**
 * Stateful Material 3 [ModalBottomSheet] wrapper integrating [BlePermissionRequester] (Android 12+
 * `neverForLocation` permissions) and delegating body rendering to stateless
 * [BleScannerSheetContent].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleScannerBottomSheet(
    uiState: BleScannerSheetUiState,
    actions: BleScannerSheetActions,
    modifier: Modifier = Modifier,
    permissionChecker: BlePermissionChecker? = null,
    onPermissionsUpdated: (BlePermissionStatus) -> Unit = {},
    requestPermissionsImmediately: Boolean = true,
    shouldShowRationale: ((String) -> Boolean)? = null,
    onOpenAppSettings: (() -> Unit)? = null
) {
    if (!uiState.isOpen) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = actions.onDismiss,
        sheetState = sheetState,
        containerColor = VoidBlack,
        contentColor = TextPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = BorderSlate) },
        modifier = modifier.testTag(BLE_SCANNER_BOTTOM_SHEET_TAG)
    ) {
        if (permissionChecker != null) {
            BlePermissionRequester(
                permissionChecker = permissionChecker,
                includeNotifications = false,
                requestImmediately = requestPermissionsImmediately && !uiState.isBlePermissionGranted,
                shouldShowRationale = shouldShowRationale,
                onOpenAppSettings = onOpenAppSettings,
                onPermissionsResult = onPermissionsUpdated
            ) { _, requestPermissions ->
                BleScannerSheetContent(
                    uiState = uiState,
                    actions = actions.copy(
                        onRequestPermissions = requestPermissions,
                        onRescan = {
                            if (uiState.isBlePermissionGranted) {
                                actions.onRescan()
                            } else {
                                requestPermissions()
                            }
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            BleScannerSheetContent(
                uiState = uiState,
                actions = actions,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Stateless Athletic Monolith BLE scanner sheet content displaying scanning progress,
 * Android 12+ permission prompts, discovered `0x180D` Heart Rate Monitors with MAC snippets and
 * RSSI signal strength indicators, and 1-tap connect controls.
 */
@Composable
fun BleScannerSheetContent(
    uiState: BleScannerSheetUiState,
    actions: BleScannerSheetActions,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScannerSheetHeader(onDismiss = actions.onDismiss)

        Text(
            text = "Moisten strap contacts and wear your strap so it advertises Bluetooth Heart Rate Service (0x180D).",
            color = TextSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )

        if (!uiState.isBlePermissionGranted) {
            ScannerPermissionCard(onRequestPermissions = actions.onRequestPermissions)
        }

        if (uiState.errorMessage != null) {
            ScannerErrorBanner(
                message = uiState.errorMessage,
                onDismiss = actions.onDismissError
            )
        }

        ScannerStatusRow(
            discoveryState = uiState.discoveryState,
            isConnecting = uiState.isConnecting,
            onRescan = actions.onRescan
        )

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
                text = "0x180D ONLY",
                color = if (uiState.isScanning) AthleticLime else TextDisabled,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
        }

        if (uiState.discoveredHrms.isEmpty()) {
            ScannerEmptyStateCard(discoveryState = uiState.discoveryState)
        } else {
            uiState.discoveredHrms.forEach { device ->
                ScannerDiscoveredHrmCard(
                    device = device,
                    isConnecting = uiState.connectingMacAddress == device.macAddress,
                    isAnyConnecting = uiState.isConnecting,
                    onSelect = { actions.onSelectHrm(device) }
                )
            }
        }
    }
}

@Composable
private fun ScannerSheetHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                modifier = Modifier
                    .background(DarkSlate, RoundedCornerShape(4.dp))
                    .border(1.dp, BorderSlate, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "BLE SCAN · 0x180D",
                    color = TertiaryCyan,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }
            Text(
                text = "Scan for Heart Rate Monitor",
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            )
        }

        TextButton(
            onClick = onDismiss,
            modifier = Modifier.testTag(BLE_SCANNER_CLOSE_BUTTON_TAG)
        ) {
            Text(
                text = "Close",
                color = TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ScannerPermissionCard(onRequestPermissions: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSlate, RoundedCornerShape(12.dp))
            .border(1.dp, WarningAmber, RoundedCornerShape(12.dp))
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
                    .background(WarningAmber, CircleShape)
            )
            Text(
                text = "BLUETOOTH PERMISSIONS REQUIRED",
                color = WarningAmber,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
        }

        Text(
            text = "Allow BLUETOOTH_SCAN (neverForLocation) and BLUETOOTH_CONNECT to discover nearby Heart Rate Monitors. Location access is never requested.",
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )

        Button(
            onClick = onRequestPermissions,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .testTag(BLE_SCANNER_GRANT_PERMISSIONS_TAG),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AthleticLime,
                contentColor = VoidBlack
            )
        ) {
            Text(
                text = "Grant Bluetooth Permissions",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ScannerErrorBanner(
    message: String,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSlate, RoundedCornerShape(12.dp))
            .border(1.5.dp, ErrorRed, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(BLE_SCANNER_ERROR_BANNER_TAG),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "CONNECTION FAILED",
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
        TextButton(onClick = onDismiss) {
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
private fun ScannerStatusRow(
    discoveryState: BleDiscoveryState,
    isConnecting: Boolean,
    onRescan: () -> Unit
) {
    val isScanning = discoveryState is BleDiscoveryState.Scanning
    val statusText = when {
        isConnecting -> "Connecting to Heart Rate Monitor..."
        isScanning -> "Scanning for nearby Heart Rate Monitors..."
        discoveryState is BleDiscoveryState.TimedOut -> "Scan timed out. Tap Rescan to try again."
        discoveryState is BleDiscoveryState.Stopped -> "Scan stopped"
        discoveryState is BleDiscoveryState.Unavailable -> when (discoveryState.reason) {
            BleDiscoveryUnavailableReason.BLUETOOTH_DISABLED -> "Bluetooth is turned off"
            BleDiscoveryUnavailableReason.PERMISSION_DENIED -> "Bluetooth permissions required"
        }
        else -> "Ready to scan"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSlate, RoundedCornerShape(12.dp))
            .border(1.dp, BorderSlate, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (isScanning || isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = if (isConnecting) TertiaryCyan else AthleticLime,
                    strokeWidth = 2.dp
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(BorderSlate, CircleShape)
                )
            }
            Text(
                text = statusText,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        OutlinedButton(
            onClick = onRescan,
            enabled = !isConnecting,
            modifier = Modifier
                .heightIn(min = 44.dp)
                .testTag(BLE_SCANNER_RESCAN_BUTTON_TAG),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, if (isConnecting) BorderSlate else AthleticLime),
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
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun ScannerEmptyStateCard(discoveryState: BleDiscoveryState) {
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
            "Ensure your strap contacts are moist and worn so it begins advertising 0x180D."
        is BleDiscoveryState.TimedOut ->
            "Verify your Heart Rate Monitor is awake and not connected to another phone, then tap Rescan."
        is BleDiscoveryState.Unavailable -> when (discoveryState.reason) {
            BleDiscoveryUnavailableReason.BLUETOOTH_DISABLED ->
                "Enable Bluetooth in Android settings and tap Rescan."
            BleDiscoveryUnavailableReason.PERMISSION_DENIED ->
                "Grant Bluetooth permissions above to scan for nearby Heart Rate Monitors."
        }
        else -> "Tap Rescan to search for nearby 0x180D Heart Rate Monitors."
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
            fontSize = 15.sp,
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
private fun ScannerDiscoveredHrmCard(
    device: DiscoveredHrm,
    isConnecting: Boolean,
    isAnyConnecting: Boolean,
    onSelect: () -> Unit
) {
    val displayName = device.advertisedName?.takeIf { it.isNotBlank() }
        ?: OnboardingPairingViewModel.DEFAULT_HRM_NAME
    val macSnippet = formatMacSnippet(device.macAddress)
    val borderColor = if (isConnecting) TertiaryCyan else BorderSlate

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSlate)
            .border(if (isConnecting) 2.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(enabled = !isAnyConnecting, onClick = onSelect)
            .padding(14.dp)
            .testTag("${BLE_SCANNER_HRM_CARD_TAG_PREFIX}${device.macAddress}"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
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
                Text(
                    text = displayName,
                    color = TextPrimary,
                    fontSize = 17.sp,
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

            ScannerRssiIndicator(rssi = device.rssi)
        }

        HorizontalDivider(color = BorderSlate.copy(alpha = 0.6f), thickness = 1.dp)

        Button(
            onClick = onSelect,
            enabled = !isAnyConnecting,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("${BLE_SCANNER_CONNECT_BUTTON_TAG_PREFIX}${device.macAddress}"),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AthleticLime,
                contentColor = VoidBlack,
                disabledContainerColor = VoidBlack,
                disabledContentColor = if (isConnecting) TertiaryCyan else TextDisabled
            )
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = TertiaryCyan,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Connecting...",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Text(
                    text = "Connect",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ScannerRssiIndicator(rssi: Int) {
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
