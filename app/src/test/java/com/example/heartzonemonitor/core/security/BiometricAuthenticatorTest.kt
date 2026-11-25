package com.example.heartzonemonitor.core.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricAuthenticatorTest {

    @Test
    fun `when no credentials enrolled, authenticate fails closed and does not launch prompt`() {
        val fakeChecker = FakeBiometricSecurityChecker(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED)
        val fakeLauncher = FakeBiometricPromptLauncher()
        val authenticator = BiometricAuthenticatorImpl(fakeChecker, fakeLauncher)
        var capturedResult: BiometricAuthResult? = null

        authenticator.authenticate(
            activity = null,
            title = "Verify identity",
            subtitle = "Authenticate to export health data",
        ) { result ->
            capturedResult = result
        }

        assertFalse(fakeLauncher.wasLaunched)
        val error = capturedResult as BiometricAuthResult.Error
        assertTrue(error.requiresSecuritySetup)
        assertTrue(error.message.contains("Settings", ignoreCase = true))
    }

    @Test
    fun `when device is secured and prompt succeeds, emits Success with expected authenticators`() {
        val fakeChecker = FakeBiometricSecurityChecker(BiometricManager.BIOMETRIC_SUCCESS)
        val fakeLauncher = FakeBiometricPromptLauncher()
        val authenticator = BiometricAuthenticatorImpl(fakeChecker, fakeLauncher)
        var capturedResult: BiometricAuthResult? = null

        authenticator.authenticate(
            activity = null,
            title = "Verify identity",
            subtitle = "Authenticate to export health data",
        ) { result ->
            capturedResult = result
        }

        assertTrue(fakeLauncher.wasLaunched)
        val expectedAuthenticators =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        assertEquals(expectedAuthenticators, fakeLauncher.lastAllowedAuthenticators)

        fakeLauncher.emitSuccess()
        assertEquals(BiometricAuthResult.Success, capturedResult)
    }

    @Test
    fun `when authenticate is called twice, launches prompt each time with zero grace window`() {
        val fakeChecker = FakeBiometricSecurityChecker(BiometricManager.BIOMETRIC_SUCCESS)
        val fakeLauncher = FakeBiometricPromptLauncher()
        val authenticator = BiometricAuthenticatorImpl(fakeChecker, fakeLauncher)

        authenticator.authenticate(null, "Title", "Subtitle") {}
        fakeLauncher.emitSuccess()

        authenticator.authenticate(null, "Title", "Subtitle") {}
        fakeLauncher.emitSuccess()

        assertEquals(2, fakeLauncher.launchCount)
    }

    @Test
    fun `when user cancels prompt, emits Cancelled`() {
        val fakeChecker = FakeBiometricSecurityChecker(BiometricManager.BIOMETRIC_SUCCESS)
        val fakeLauncher = FakeBiometricPromptLauncher()
        val authenticator = BiometricAuthenticatorImpl(fakeChecker, fakeLauncher)
        var capturedResult: BiometricAuthResult? = null

        authenticator.authenticate(null, "Title", "Subtitle") { capturedResult = it }
        fakeLauncher.emitError(BiometricPrompt.ERROR_USER_CANCELED, "User canceled")

        assertEquals(BiometricAuthResult.Cancelled, capturedResult)
    }

    @Test
    fun `when prompt returns non-cancellation error, emits Error with message`() {
        val fakeChecker = FakeBiometricSecurityChecker(BiometricManager.BIOMETRIC_SUCCESS)
        val fakeLauncher = FakeBiometricPromptLauncher()
        val authenticator = BiometricAuthenticatorImpl(fakeChecker, fakeLauncher)
        var capturedResult: BiometricAuthResult? = null

        authenticator.authenticate(null, "Title", "Subtitle") { capturedResult = it }
        fakeLauncher.emitError(BiometricPrompt.ERROR_LOCKOUT, "Too many attempts")

        val error = capturedResult as BiometricAuthResult.Error
        assertEquals("Too many attempts", error.message)
        assertFalse(error.requiresSecuritySetup)
    }

    @Test
    fun `when hardware is unavailable, fails closed without launching prompt`() {
        val fakeChecker = FakeBiometricSecurityChecker(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE)
        val fakeLauncher = FakeBiometricPromptLauncher()
        val authenticator = BiometricAuthenticatorImpl(fakeChecker, fakeLauncher)
        var capturedResult: BiometricAuthResult? = null

        authenticator.authenticate(null, "Title", "Subtitle") { capturedResult = it }

        assertFalse(fakeLauncher.wasLaunched)
        val error = capturedResult as BiometricAuthResult.Error
        assertFalse(error.requiresSecuritySetup)
    }
}

private class FakeBiometricSecurityChecker(
    private val status: Int,
) : BiometricSecurityChecker {
    override fun canAuthenticate(): Int = status
}

private class FakeBiometricPromptLauncher : BiometricPromptLauncher {
    var wasLaunched: Boolean = false
        private set
    var launchCount: Int = 0
        private set
    var lastAllowedAuthenticators: Int? = null
        private set
    private var pendingCallback: BiometricPromptCallback? = null

    override fun launch(
        activity: FragmentActivity?,
        title: String,
        subtitle: String,
        allowedAuthenticators: Int,
        callback: BiometricPromptCallback,
    ) {
        wasLaunched = true
        launchCount++
        lastAllowedAuthenticators = allowedAuthenticators
        pendingCallback = callback
    }

    fun emitSuccess() {
        pendingCallback?.onSuccess()
    }

    fun emitError(errorCode: Int, message: String) {
        pendingCallback?.onError(errorCode, message)
    }
}
