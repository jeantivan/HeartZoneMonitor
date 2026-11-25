package com.example.heartzonemonitor.core.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Result of a biometric or device-credential authentication attempt.
 */
sealed interface BiometricAuthResult {
    data object Success : BiometricAuthResult
    data class Error(
        val message: String,
        val requiresSecuritySetup: Boolean = false,
    ) : BiometricAuthResult

    data object Cancelled : BiometricAuthResult
}

/**
 * Contract for authenticating the user before sensitive actions such as health data export.
 */
interface BiometricAuthenticator {
    fun authenticate(
        activity: FragmentActivity?,
        title: String,
        subtitle: String,
        onResult: (BiometricAuthResult) -> Unit,
    )
}

interface BiometricSecurityChecker {
    fun canAuthenticate(): Int
}

interface BiometricPromptCallback {
    fun onSuccess()
    fun onError(errorCode: Int, message: String)
}

interface BiometricPromptLauncher {
    fun launch(
        activity: FragmentActivity?,
        title: String,
        subtitle: String,
        allowedAuthenticators: Int,
        callback: BiometricPromptCallback,
    )
}

class AndroidBiometricSecurityChecker(
    private val context: Context,
) : BiometricSecurityChecker {
    override fun canAuthenticate(): Int {
        return BiometricManager.from(context).canAuthenticate(ALLOWED_AUTHENTICATORS)
    }

    companion object {
        const val ALLOWED_AUTHENTICATORS: Int =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}

class AndroidBiometricPromptLauncher : BiometricPromptLauncher {
    override fun launch(
        activity: FragmentActivity?,
        title: String,
        subtitle: String,
        allowedAuthenticators: Int,
        callback: BiometricPromptCallback,
    ) {
        if (activity == null) {
            callback.onError(
                BiometricPrompt.ERROR_HW_UNAVAILABLE,
                "Authentication requires an active screen.",
            )
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    callback.onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    callback.onError(errorCode, errString.toString())
                }
            },
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(allowedAuthenticators)
            .build()

        prompt.authenticate(promptInfo)
    }
}

/**
 * Enforces fail-closed, zero-grace-window biometric or device-credential verification.
 */
class BiometricAuthenticatorImpl(
    private val securityChecker: BiometricSecurityChecker,
    private val promptLauncher: BiometricPromptLauncher,
) : BiometricAuthenticator {

    override fun authenticate(
        activity: FragmentActivity?,
        title: String,
        subtitle: String,
        onResult: (BiometricAuthResult) -> Unit,
    ) {
        when (val status = securityChecker.canAuthenticate()) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                promptLauncher.launch(
                    activity = activity,
                    title = title,
                    subtitle = subtitle,
                    allowedAuthenticators = AndroidBiometricSecurityChecker.ALLOWED_AUTHENTICATORS,
                    callback = object : BiometricPromptCallback {
                        override fun onSuccess() {
                            onResult(BiometricAuthResult.Success)
                        }

                        override fun onError(errorCode: Int, message: String) {
                            if (isCancellationError(errorCode)) {
                                onResult(BiometricAuthResult.Cancelled)
                            } else {
                                onResult(BiometricAuthResult.Error(message))
                            }
                        }
                    },
                )
            }

            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                onResult(
                    BiometricAuthResult.Error(
                        message = "No device lock screen or biometric credential is set up. Please configure a PIN, pattern, password, or biometric in Android Settings.",
                        requiresSecuritySetup = true,
                    ),
                )
            }

            else -> {
                onResult(
                    BiometricAuthResult.Error(
                        message = "Device authentication is unavailable (status code: $status).",
                        requiresSecuritySetup = false,
                    ),
                )
            }
        }
    }

    private fun isCancellationError(errorCode: Int): Boolean {
        return errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
            errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
            errorCode == BiometricPrompt.ERROR_CANCELED
    }
}
