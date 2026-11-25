package com.example.heartzonemonitor.ui.security

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.example.heartzonemonitor.core.security.BiometricAuthResult
import com.example.heartzonemonitor.core.security.BiometricAuthenticator
import com.example.heartzonemonitor.core.security.BiometricSecuritySettings
import com.example.heartzonemonitor.ui.theme.AthleticLime
import com.example.heartzonemonitor.ui.theme.DarkSlate
import com.example.heartzonemonitor.ui.theme.TextPrimary
import com.example.heartzonemonitor.ui.theme.TextSecondary
import com.example.heartzonemonitor.ui.theme.VoidBlack

/**
 * Coordinates [BiometricAuthenticator] verification and shows a fail-closed setup alert
 * when no device lock screen or biometric credential is enrolled.
 */
@Stable
class BiometricAuthGateState internal constructor(
    private val authenticator: BiometricAuthenticator,
    private val activity: FragmentActivity?,
    private val onOpenSettings: () -> Unit,
) {
    var isSecuritySetupDialogVisible by mutableStateOf(false)
        private set

    var securitySetupMessage by mutableStateOf("")
        private set

    fun authenticate(
        title: String,
        subtitle: String,
        onResult: (BiometricAuthResult) -> Unit,
    ) {
        authenticator.authenticate(
            activity = activity,
            title = title,
            subtitle = subtitle,
        ) { result ->
            if (result is BiometricAuthResult.Error && result.requiresSecuritySetup) {
                securitySetupMessage = result.message
                isSecuritySetupDialogVisible = true
            }
            onResult(result)
        }
    }

    fun openSecuritySettings() {
        isSecuritySetupDialogVisible = false
        onOpenSettings()
    }

    fun dismissSecuritySetupDialog() {
        isSecuritySetupDialogVisible = false
    }
}

@Composable
fun rememberBiometricAuthGate(
    authenticator: BiometricAuthenticator,
    activity: FragmentActivity? = LocalContext.current.findFragmentActivity(),
    onOpenSettings: (() -> Unit)? = null,
): BiometricAuthGateState {
    val context = LocalContext.current
    val openSettingsAction = remember(context, onOpenSettings) {
        onOpenSettings ?: { BiometricSecuritySettings.openDeviceSecuritySettings(context) }
    }
    return remember(authenticator, activity, openSettingsAction) {
        BiometricAuthGateState(
            authenticator = authenticator,
            activity = activity,
            onOpenSettings = openSettingsAction,
        )
    }
}

@Composable
fun BiometricSecuritySetupGateHost(
    gateState: BiometricAuthGateState,
    modifier: Modifier = Modifier,
) {
    BiometricSecuritySetupDialog(
        isVisible = gateState.isSecuritySetupDialogVisible,
        message = gateState.securitySetupMessage,
        onOpenSettings = gateState::openSecuritySettings,
        onDismiss = gateState::dismissSecuritySetupDialog,
        modifier = modifier,
    )
}

/**
 * Fail-closed alert directing the user to Android Settings when no device credential is enrolled.
 */
@Composable
fun BiometricSecuritySetupDialog(
    isVisible: Boolean,
    message: String,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!isVisible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = DarkSlate,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Text(
                text = "Device Security Required",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
        },
        text = {
            Text(
                text = message,
                fontSize = 15.sp,
            )
        },
        confirmButton = {
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AthleticLime,
                    contentColor = VoidBlack,
                ),
            ) {
                Text(
                    text = "Open Settings",
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = "Cancel",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
    )
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
