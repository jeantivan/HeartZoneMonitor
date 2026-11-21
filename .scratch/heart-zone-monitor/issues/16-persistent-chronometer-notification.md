# 16: Persistent Chronometer Notification

**What to build:** An ongoing, low-importance Android system notification displaying real-time heart rate, zone name, and a native system chronometer ticking active workout duration with zero CPU overhead.

**Blocked by:** 15 (Foreground Recording Service & WakeLock)

**Status:** resolved

- [x] Creates notification channel `recording_channel` with `IMPORTANCE_LOW` and `setOngoing(true)`.
- [x] Title displays current BPM and Heart Zone (e.g. *"142 BPM • Zone 3 (Aerobic)"*).
- [x] Duration rendered natively using `NotificationCompat.Builder.setUsesChronometer(true)` seeded with monotonic baseline.
- [x] Provides interactive action buttons: `[Pause]` / `[Resume]` and `[Stop & Save]`, sending pending intents to `SessionRepository`.
- [x] Content click intent deep-links directly into the live session screen.
- [x] Debounces calls to `notify()`: updates only on zone transitions, heart rate shifts $\ge 2$ BPM, or at minimum every 5 seconds.

## Answer

Implemented the persistent low-importance chronometer notification, monotonic `notify()` debouncer, interactive Session control actions, and Live Session deep-linking:
- `RecordingNotificationFactory` (`app/src/main/java/com/example/heartzonemonitor/service/RecordingNotificationFactory.kt`):
  - Ensures channel `recording_channel` (`NotificationManager.IMPORTANCE_LOW`, badge disabled) and builds ongoing notifications with `setOngoing(true)`, `setOnlyAlertOnce(true)`, `NotificationCompat.CATEGORY_SERVICE`, `PRIORITY_LOW`, and `VISIBILITY_PUBLIC`.
  - Formats notification titles from live BPM and `HeartZone.zoneName` (e.g., `"142 BPM • Zone 3 (Tempo)"`, `"Paused • 142 BPM • Zone 3 (Tempo)"`, and clean fallbacks when telemetry is absent).
  - Enables native system chronometer (`setUsesChronometer(true)`, `setShowWhen(true)`, `setWhen(clock.currentEpochMs() - state.effectiveDurationMs)`) while `Recording`, and stops the chronometer (`setUsesChronometer(false)`, `setShowWhen(false)`) while `Paused`, displaying frozen formatted Effective Duration (`MM:SS` / `HH:MM:SS`) along with `PauseReason` or micro-session prompt context.
  - Attaches `PendingIntent.getService` (`FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`) actions for `[Pause]` (`Recording`) / `[Resume]` (`Paused`) and `[Stop & Save]`, plus a `PendingIntent.getActivity` content click intent deep-linking straight to `MainActivity` Live Session.
- `RecordingNotificationDebouncer` (`app/src/main/java/com/example/heartzonemonitor/service/RecordingNotificationDebouncer.kt`):
  - Pure monotonic debouncer using `SessionClock.elapsedRealtimeMs()` gating notification updates strictly on initial/state-class/sessionId/`PauseReason`/micro-session-prompt changes, `HeartZone` transitions, BPM shifts $\ge 2$ BPM (or `null <-> non-null`), or $\ge 5{,}000\text{ ms}$ elapsed since the previous emission, suppressing 0–1 BPM jitter inside the 5s window.
- `RecordingService` (`app/src/main/java/com/example/heartzonemonitor/service/RecordingService.kt`):
  - Wires `RecordingNotificationFactory`, `RecordingNotificationDebouncer`, injectable `sessionClockProvider` (default `AndroidSessionClock`), and `notificationPoster` (`NotificationManager.notify`), and handles `ACTION_PAUSE_SESSION`, `ACTION_RESUME_SESSION`, and `ACTION_STOP_SESSION` directly against `SessionRepository` (`pauseSession(PauseReason.USER_MANUAL)`, `resumeSession()`, `stopSession()`) without restarting the active observer or breaking debounce state (while preserving foreground + prompt state on micro-session stops).
- Live Session deep-linking (`AndroidManifest.xml`, `MainActivity.kt`, `RootNavHost.kt`, `MainShell.kt`):
  - Configures `MainActivity` with `android:launchMode="singleTop"`, `EXTRA_OPEN_LIVE_SESSION`, `createLiveSessionIntent(context)`, `onNewIntent(...)`, and instance-state-preserved `openLiveSessionRequestId` so tapping the notification from any tab or the `Devices` screen returns directly to the Live Session screen without bypassing the onboarding gate.
- Automated verification (`RecordingNotificationTest.kt`, `RecordingServiceTest.kt`, `MainShellTest.kt`, `RootNavHostTest.kt`):
  - Verified with `./scripts/check-glossary.sh` (0 violations), `./scripts/check-standards.sh` (0 violations), and `./gradlew :app:testDebugUnitTest --tests "com.example.heartzonemonitor.service.*" --tests "com.example.heartzonemonitor.ui.navigation.*"` (42/42 passed: `RecordingNotificationTest` 10/10, `RecordingServiceTest` 10/10, `MainShellTest` 6/6, `RootNavHostTest` 11/11, `RootViewModelTest` 5/5).
