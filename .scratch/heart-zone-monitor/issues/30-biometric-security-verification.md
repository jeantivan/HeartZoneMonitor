# 30: Biometric Security Verification (`BiometricPrompt`)

**What to build:** The biometric authentication module wrapping Android's `androidx.biometric.BiometricPrompt`, enforcing verified user identity before exporting sensitive health data and failing closed on unsecured devices.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] Implements `BiometricAuthenticator` contract wrapping `BiometricPrompt` configured with `BIOMETRIC_STRONG | BIOMETRIC_WEAK | DEVICE_CREDENTIAL`.
- [ ] Enforces strict per-export authentication with zero cached authentication grace window.
- [ ] Evaluates device security status via `BiometricManager.canAuthenticate(...)`.
- [ ] Fail-closed policy: if `canAuthenticate(...)` returns `BIOMETRIC_ERROR_NONE_ENROLLED` (no device lock screen PIN/pattern or biometrics set up), immediately blocks the action and displays an alert directing the user to Android Settings.
- [ ] Emits clean callbacks: `Success`, `Error(message)`, `Cancelled`.
- [ ] Automated tests using test doubles verify fail-closed behavior, error emissions, and success dispatches.
