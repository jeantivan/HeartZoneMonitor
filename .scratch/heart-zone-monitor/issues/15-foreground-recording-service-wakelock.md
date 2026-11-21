# 15: Foreground Recording Service & WakeLock (`connectedDevice`)

**What to build:** The Android Foreground Service that anchors the recording session in memory, acquires a partial wake lock while recording, and survives screen-off and app backgrounding.

**Blocked by:** 14 (Session Recording State Machine Repository)

**Status:** resolved

- [x] Declares and implements `RecordingService` with `android:foregroundServiceType="connectedDevice"`.
- [x] Starts service as a foreground service immediately upon transition to `Recording`.
- [x] Acquires a `PowerManager.PARTIAL_WAKE_LOCK` exclusively while in `Recording` state to prevent OS Doze mode from throttling 1 Hz sample processing.
- [x] Releases the wake lock immediately upon transition to `Paused`.
- [x] Stops service immediately (`stopForeground(STOP_FOREGROUND_REMOVE)` and `stopSelf()`) on `Completed` or `Discarded`.
- [x] Returns `START_NOT_STICKY` from `onStartCommand` to prevent zombie service restarts with null intents.
- [x] Automated tests verify service lifecycle transitions aligned with `SessionRepository` state changes.

## Answer

Implemented the `connectedDevice` Foreground Service, WakeLock lifecycle management, and `SessionRepository` service launch coordinator:
- `AndroidManifest.xml` (`app/src/main/AndroidManifest.xml`): Declares `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, and `WAKE_LOCK` permissions, and registers `.service.RecordingService` with `android:exported="false"` and `android:foregroundServiceType="connectedDevice"`.
- `RecordingService` (`app/src/main/java/com/example/heartzonemonitor/service/RecordingService.kt`):
  - Exposes `NOTIFICATION_CHANNEL_ID` (`"recording_channel"`), `NOTIFICATION_ID` (`1001`), `WAKE_LOCK_TAG` (`"HeartZoneMonitor::RecordingWakeLock"`), `MAX_WAKE_LOCK_TIMEOUT_MS` (12 hours), `ACTION_START_RECORDING`, `createStartIntent(context)`, and injectable internal companion hooks (`sessionRepositoryProvider`, `notificationBuilderProvider`, `resetTestHooks()`) ready for Ticket 16.
  - Creates the `recording_channel` notification channel (`NotificationManager.IMPORTANCE_LOW`, badge disabled) and enters foreground immediately in `onStartCommand` with `ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` and an ongoing `NotificationCompat.CATEGORY_SERVICE` notification (`setOngoing(true)`, `setOnlyAlertOnce(true)`), always returning `START_NOT_STICKY`.
  - Observes `SessionRepository.uiState` via a single `Dispatchers.Main.immediate` coroutine (`CoroutineStart.UNDISPATCHED`): holds a non-reference-counted `PowerManager.PARTIAL_WAKE_LOCK` exclusively during `SessionUiState.Recording`, releases it immediately on `SessionUiState.Paused` while keeping the foreground service active, and calls `releaseWakeLock()`, `stopForeground(STOP_FOREGROUND_REMOVE)`, and `stopSelf()` immediately on `Completed`, `Discarded`, null repository, or post-active `Idle` (as well as cleaning up in `onDestroy()`).
- `RecordingServiceController` (`app/src/main/java/com/example/heartzonemonitor/service/RecordingServiceController.kt`): Defines `RecordingServiceLauncher`, `AndroidRecordingServiceLauncher` (`ContextCompat.startForegroundService`), and `RecordingServiceCoordinator` (starts the service once on `Idle -> Recording`, avoids re-triggering across `Recording <-> Paused`, re-arms on `Completed`/`Discarded`/`Idle`, and exposes `close()`).
- Automated verification (`app/src/test/java/com/example/heartzonemonitor/service/RecordingServiceTest.kt`):
  - Robolectric `@Config(sdk = [34])` tests (zero `Thread.sleep`) verifying manifest registration and permissions, `START_NOT_STICKY`, `recording_channel` creation and foreground notification flags, WakeLock acquisition/release across `Recording`, `Paused` (`USER_MANUAL`, `BLE_CONNECTION_LOST`, `SENSOR_CONTACT_LOST`), resume, `Completed`, `Discarded`, null/already-terminal repositories, `onDestroy()`, `AndroidRecordingServiceLauncher`, and `RecordingServiceCoordinator`.
  - Verified with `./scripts/check-glossary.sh`, `./scripts/check-standards.sh`, and `./gradlew :app:testDebugUnitTest --tests "com.example.heartzonemonitor.service.RecordingServiceTest"` (10/10 passed).
