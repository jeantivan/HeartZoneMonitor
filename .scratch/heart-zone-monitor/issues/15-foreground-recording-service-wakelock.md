# 15: Foreground Recording Service & WakeLock (`connectedDevice`)

**What to build:** The Android Foreground Service that anchors the recording session in memory, acquires a partial wake lock while recording, and survives screen-off and app backgrounding.

**Blocked by:** 14 (Session Recording State Machine Repository)

**Status:** ready-for-agent

- [ ] Declares and implements `RecordingService` with `android:foregroundServiceType="connectedDevice"`.
- [ ] Starts service as a foreground service immediately upon transition to `Recording`.
- [ ] Acquires a `PowerManager.PARTIAL_WAKE_LOCK` exclusively while in `Recording` state to prevent OS Doze mode from throttling 1 Hz sample processing.
- [ ] Releases the wake lock immediately upon transition to `Paused`.
- [ ] Stops service immediately (`stopForeground(STOP_FOREGROUND_REMOVE)` and `stopSelf()`) on `Completed` or `Discarded`.
- [ ] Returns `START_NOT_STICKY` from `onStartCommand` to prevent zombie service restarts with null intents.
- [ ] Automated tests verify service lifecycle transitions aligned with `SessionRepository` state changes.
