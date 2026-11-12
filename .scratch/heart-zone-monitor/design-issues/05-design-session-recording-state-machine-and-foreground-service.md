# Design session recording state machine and foreground service

Type: grilling
Status: resolved
Blocked by: none

## Question

How will the state machine manage transitions (`Disconnected` -> `Connected` -> `Recording` -> `Paused` [manual or auto-BLE-loss] -> `Resumed` -> `Completed`), how will effective duration and paused duration be accumulated accurately without clock drift, and how will the Android Foreground Service coordinate with the ViewModel and persistent notification?

## Answer

All architectural and lifecycle decisions for the session recording state machine, monotonic timing math, Android Foreground Service, and persistent notification have been resolved through interactive grilling and recorded in ADR-0002:

### 1. Recording State Machine & Strict Gating
- **Lifecycle States**:
  - `Idle`: Ready to start; sensor telemetry visible if connected.
  - `Recording`: Actively receiving samples, accumulating `Effective Duration`.
  - `Paused(reason: PauseReason)`: Accumulating `Paused Duration`.
  - `Completed`: Session finalized, precalculated metrics saved to encrypted Room DB.
  - `Discarded`: Session aborted, buffered and uncommitted data discarded.
- **Connection Gating**:
  - "Start Session" is strictly disabled until the **Active HRM** is in `Connected` state and streaming valid telemetry, preventing unlinked or empty sessions.
- **Pause Classifications**:
  - `USER_MANUAL`: User explicitly tapped pause in UI or notification.
  - `BLE_CONNECTION_LOST`: Link dropped; 10-second grace period expired without recovery.
  - `SENSOR_CONTACT_LOST`: Characteristic `0x2A37` skin contact flag false for > 5 seconds.

### 2. Drift-Free Monotonic Timing Math
- **Clock Separation**:
  - `startEpochMs` is recorded once at start using `System.currentTimeMillis()` for calendar sorting and export metadata.
  - All duration math is calculated strictly using `SystemClock.elapsedRealtime()` (monotonic hardware timer unaffected by wall-clock drift, NTP syncs, manual user time changes, or CPU sleep).
- **Interval Accumulation**:
  - Entering `Recording`: record `activeIntervalStartRealtime = elapsedRealtime()`.
  - Entering `Paused`: `accumulatedEffectiveMs += (elapsedRealtime() - activeIntervalStartRealtime)`; record `pauseIntervalStartRealtime = elapsedRealtime()`.
  - Resuming: `accumulatedPausedMs += (elapsedRealtime() - pauseIntervalStartRealtime)`; `activeIntervalStartRealtime = elapsedRealtime()`.
  - Instantaneous calculations:
    $$\text{Effective Duration} = \text{accumulatedEffectiveMs} + (\text{if Recording then } \text{elapsedRealtime}() - \text{activeIntervalStartRealtime} \text{ else } 0)$$
    $$\text{Elapsed Duration} = \text{Effective Duration} + \text{Paused Duration}$$
  - Completely immune to coroutine delay drift and system time jumps.

### 3. Foreground Service Scope & Android 14+ Type
- **Lifecycle Boundaries**:
  - The `RecordingService` starts as a Foreground Service only when transitioning to `Recording`.
  - Remains running through `Recording` and `Paused` states.
  - Immediately stops (`stopForeground(STOP_FOREGROUND_REMOVE)` and `stopSelf()`) when transitioning to `Completed` or `Discarded`.
- **Service Type**:
  - Declared with `android:foregroundServiceType="connectedDevice"` (targetSdk 37 compliance) and guarded by `POST_NOTIFICATIONS` runtime permission.

### 4. Repository Single Source of Truth Architecture
- **State Coordination**:
  - A singleton `SessionRepository` owns the recording state machine and exposes `StateFlow<SessionUiState>`.
  - Both `RecordingService` and `SessionViewModel` inject and observe `SessionRepository`.
  - The Service acts purely as the OS process lifecycle anchor (holding the notification and keeping the process alive); the ViewModel consumes the repository's Flow directly. Eliminates fragile `ServiceConnection` Binder IPC and survives Activity recreation across screen rotations.

### 5. Persistent Notification UX & Telemetry Throttling
- **Notification Presentation**:
  - Title: *"Heart Zone Recording"*
  - Content: *"142 BPM • Zone 3 (Aerobic)"*
  - Duration rendered natively via `NotificationCompat.Builder.setUsesChronometer(true)` seeded with monotonic baseline. Ticks seconds natively in system UI with zero CPU/IPC overhead.
  - Actions: Direct `[Pause]` / `[Resume]` and `[Stop & Save]` buttons; tap content intent deep-links to live session screen.
  - Channel: `IMPORTANCE_LOW` with `setOngoing(true)` (silent, non-intrusive).
- **Update Debouncing**:
  - Calls to `notify()` are debounced: dispatched on **Heart Zone** transitions, when BPM shifts by $\ge 2$ BPM, or at a minimum of once every 5 seconds (aligned with database batch write). Prevents system notification throttling.

### 6. Power Management & WakeLock Strategy
- **Partial WakeLock**:
  - A `PowerManager.PARTIAL_WAKE_LOCK` is acquired exclusively while in `Recording` state.
  - Released immediately when transitioning to `Paused`, `Completed`, or `Discarded`.
  - Prevents aggressive OS Doze mode from throttling 1 Hz sample processing and batch writes when the phone screen is turned off in an armband or pocket.

### 7. Telemetry Recovery & Auto-Resume Behavior
- If paused due to `BLE_CONNECTION_LOST` or `SENSOR_CONTACT_LOST`, restoring connection/contact automatically transitions the state back to `Recording` and resumes `Effective Duration` accumulation.
- If paused via `USER_MANUAL`, resuming strictly requires user interaction.

### 8. Service Restart Mode & Crash Handling
- `onStartCommand` returns `START_NOT_STICKY`.
- Avoids zombie service restarts with null intents without BLE context. Any unexpected process termination is reconciled at database open by the crash reconciliation handler (Ticket 04).

### 9. Micro-Session Handling (< 10 seconds or 0 samples)
- Tapping "Stop & Save" on a session with 0 samples or `Effective Duration` < 10 seconds displays a prompt: *"Session too short (under 10 seconds). Discard or Save?"* with default action to discard, preventing empty clutter in history.
