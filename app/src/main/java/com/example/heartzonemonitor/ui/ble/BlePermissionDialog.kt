package com.example.heartzonemonitor.ui.ble

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.heartzonemonitor.data.ble.AndroidBlePermissionChecker
import com.example.heartzonemonitor.data.ble.BlePermissionChecker
import com.example.heartzonemonitor.data.ble.BlePermissionStatus
import com.example.heartzonemonitor.data.ble.BlePermissions
import com.example.heartzonemonitor.ui.theme.AthleticLime
import com.example.heartzonemonitor.ui.theme.BorderSlate
import com.example.heartzonemonitor.ui.theme.DarkSlate
import com.example.heartzonemonitor.ui.theme.TertiaryCyan
import com.example.heartzonemonitor.ui.theme.TextPrimary
import com.example.heartzonemonitor.ui.theme.TextSecondary
import com.example.heartzonemonitor.ui.theme.VoidBlack
import com.example.heartzonemonitor.ui.theme.WarningAmber

data class BlePermissionDialogUiState(
    val isVisible: Boolean = true,
    val missingPermissions: List<String> = emptyList(),
    val isPermanentlyDenied: Boolean = false
)

data class BlePermissionDialogActions(
    val onRequestPermissions: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onDismiss: () -> Unit = {}
)

/**
 * Stateful permission wrapper that coordinates runtime permission requests for Bluetooth LE
 * Heart Rate Monitor (HRM) scanning, connection, and ongoing Session notifications.
 */
@Composable
fun BlePermissionRequester(
    modifier: Modifier = Modifier,
    permissionChecker: BlePermissionChecker = AndroidBlePermissionChecker(LocalContext.current),
    includeNotifications: Boolean = true,
    requestImmediately: Boolean = false,
    shouldShowRationale: ((String) -> Boolean)? = null,
    onOpenAppSettings: (() -> Unit)? = null,
    onPermissionsResult: (BlePermissionStatus) -> Unit = {},
    onAllPermissionsGranted: () -> Unit = {},
    content: @Composable (
        permissionStatus: BlePermissionStatus,
        requestPermissions: () -> Unit
    ) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current

    val resolveRationale: (String) -> Boolean = remember(activity, shouldShowRationale) {
        shouldShowRationale ?: { permission ->
            activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } == true
        }
    }

    var permissionStatus by remember(permissionChecker) {
        mutableStateOf(permissionChecker.checkPermissions())
    }
    var dialogState by remember {
        mutableStateOf(BlePermissionDialogUiState(isVisible = false))
    }

    val targetPermissions = remember(includeNotifications) {
        if (includeNotifications) {
            BlePermissions.requiredAllPermissions()
        } else {
            BlePermissions.requiredBlePermissions()
        }
    }

    fun isTargetGranted(status: BlePermissionStatus): Boolean =
        if (includeNotifications) status.allRequiredPermissionsGranted else status.allBlePermissionsGranted

    fun targetMissing(status: BlePermissionStatus): List<String> =
        status.missingPermissions.filter { it in targetPermissions }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val checked = permissionChecker.checkPermissions()
        val updatedStatus = BlePermissionStatus(
            hasScanPermission = grants[Manifest.permission.BLUETOOTH_SCAN] ?: checked.hasScanPermission,
            hasConnectPermission = grants[Manifest.permission.BLUETOOTH_CONNECT] ?: checked.hasConnectPermission,
            hasNotificationPermission = if (includeNotifications) {
                grants[Manifest.permission.POST_NOTIFICATIONS] ?: checked.hasNotificationPermission
            } else {
                checked.hasNotificationPermission
            }
        )
        permissionStatus = updatedStatus

        if (isTargetGranted(updatedStatus)) {
            dialogState = dialogState.copy(isVisible = false, isPermanentlyDenied = false)
            onPermissionsResult(updatedStatus)
            onAllPermissionsGranted()
        } else {
            val missing = targetMissing(updatedStatus)
            val canShowRationale = missing.any { resolveRationale(it) }
            dialogState = BlePermissionDialogUiState(
                isVisible = true,
                missingPermissions = missing,
                isPermanentlyDenied = !canShowRationale
            )
            onPermissionsResult(updatedStatus)
        }
    }

    fun triggerPermissionFlow(bypassPreRationale: Boolean) {
        val current = permissionChecker.checkPermissions()
        permissionStatus = current
        if (isTargetGranted(current)) {
            dialogState = dialogState.copy(isVisible = false, isPermanentlyDenied = false)
            onPermissionsResult(current)
            onAllPermissionsGranted()
            return
        }

        val missing = targetMissing(current)
        if (!bypassPreRationale && missing.any { resolveRationale(it) }) {
            dialogState = BlePermissionDialogUiState(
                isVisible = true,
                missingPermissions = missing,
                isPermanentlyDenied = false
            )
        } else {
            dialogState = dialogState.copy(isVisible = false)
            launcher.launch(missing.toTypedArray())
        }
    }

    DisposableEffect(lifecycleOwner, permissionChecker, includeNotifications) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val refreshed = permissionChecker.checkPermissions()
                val wasGranted = isTargetGranted(permissionStatus)
                permissionStatus = refreshed
                if (isTargetGranted(refreshed)) {
                    dialogState = dialogState.copy(isVisible = false, isPermanentlyDenied = false)
                    if (!wasGranted) {
                        onPermissionsResult(refreshed)
                        onAllPermissionsGranted()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(requestImmediately) {
        if (requestImmediately) {
            triggerPermissionFlow(bypassPreRationale = false)
        }
    }

    val openSettingsAction: () -> Unit = {
        if (onOpenAppSettings != null) {
            onOpenAppSettings()
        } else {
            val intent = BlePermissions.createAppSettingsIntent(context.packageName).apply {
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        }
    }

    Box(modifier = modifier) {
        content(permissionStatus) {
            triggerPermissionFlow(bypassPreRationale = false)
        }

        BlePermissionRationaleDialogContent(
            uiState = dialogState,
            actions = BlePermissionDialogActions(
                onRequestPermissions = { triggerPermissionFlow(bypassPreRationale = true) },
                onOpenSettings = openSettingsAction,
                onDismiss = { dialogState = dialogState.copy(isVisible = false) }
            )
        )
    }
}

/**
 * Stateless Athletic Monolith educational rationale dialog explaining Bluetooth Low Energy
 * and Session notification permissions without location access.
 */
@Composable
fun BlePermissionRationaleDialogContent(
    uiState: BlePermissionDialogUiState,
    actions: BlePermissionDialogActions,
    modifier: Modifier = Modifier
) {
    if (!uiState.isVisible) return

    val showScanRow = uiState.missingPermissions.isEmpty() ||
        Manifest.permission.BLUETOOTH_SCAN in uiState.missingPermissions
    val showConnectRow = uiState.missingPermissions.isEmpty() ||
        Manifest.permission.BLUETOOTH_CONNECT in uiState.missingPermissions
    val showNotificationRow = Manifest.permission.POST_NOTIFICATIONS in uiState.missingPermissions

    Dialog(
        onDismissRequest = actions.onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(16.dp),
            color = DarkSlate,
            border = BorderStroke(1.dp, BorderSlate)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(VoidBlack, RoundedCornerShape(4.dp))
                        .border(1.dp, TertiaryCyan, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "BLUETOOTH ACCESS",
                        color = TertiaryCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }

                Text(
                    text = if (uiState.isPermanentlyDenied) {
                        "Heart Rate Monitor Permissions Disabled"
                    } else {
                        "Heart Rate Monitor Permissions"
                    },
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                )

                Text(
                    text = "HeartZoneMonitor connects directly to your Bluetooth LE Heart Rate Monitor (HRM). Physical location access is never requested.",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(VoidBlack, RoundedCornerShape(10.dp))
                        .border(1.dp, BorderSlate, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (showScanRow) {
                        PermissionExplanationRow(
                            badgeText = "BLUETOOTH_SCAN",
                            description = "Discover nearby Heart Rate Monitors (0x180D) using neverForLocation.",
                            accentColor = AthleticLime
                        )
                    }
                    if (showConnectRow) {
                        PermissionExplanationRow(
                            badgeText = "BLUETOOTH_CONNECT",
                            description = "Connect to your Active HRM and receive Heart Rate Samples.",
                            accentColor = TertiaryCyan
                        )
                    }
                    if (showNotificationRow) {
                        PermissionExplanationRow(
                            badgeText = "POST_NOTIFICATIONS",
                            description = "Keep Session duration and pause controls visible while recording.",
                            accentColor = AthleticLime
                        )
                    }
                }

                if (uiState.isPermanentlyDenied) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(VoidBlack, RoundedCornerShape(8.dp))
                            .border(1.dp, WarningAmber, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "Permissions were denied in Android settings. Open App Settings to enable Heart Rate Monitor access.",
                            color = WarningAmber,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = if (uiState.isPermanentlyDenied) {
                            actions.onOpenSettings
                        } else {
                            actions.onRequestPermissions
                        },
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
                            text = if (uiState.isPermanentlyDenied) {
                                "Open App Settings"
                            } else {
                                "Grant Permissions"
                            },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }

                    OutlinedButton(
                        onClick = actions.onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BorderSlate),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TextPrimary
                        )
                    ) {
                        Text(
                            text = "Not Now",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionExplanationRow(
    badgeText: String,
    description: String,
    accentColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .background(accentColor, CircleShape)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = badgeText,
                color = TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp
            )
            Text(
                text = description,
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
