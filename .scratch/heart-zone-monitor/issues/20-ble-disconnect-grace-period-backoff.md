# 20: BLE Disconnection Grace Period & Reconnection Backoff

**What to build:** The transient radio loss resilience mechanism during an active workout session, providing a 10-second grace period with exponential backoff before auto-pausing, and transparently resuming upon link restoration.

**Blocked by:** 14 (Session Recording State Machine Repository)

**Status:** resolved

- [x] When the BLE connection drops during an active `Recording` session, transitions connection state to `Reconnecting` without immediately altering session state.
- [x] Executes reconnection attempts with exponential backoff delays (0s, 2s, 5s, 10s up to 60s max).
- [x] Initiates a 10-second grace period: if the link is re-established within 10 seconds, `Recording` continues seamlessly without state change.
- [x] If the connection is not recovered within 10 seconds, transitions the session state to `Paused(BLE_CONNECTION_LOST)` and dispatches an alert notification.
- [x] When the sensor successfully reconnects after auto-pausing, automatically transitions the session back to `Recording` and resumes duration accumulation.
- [x] Automated tests via `FakeBleTransport` verify recovery under 10 seconds vs auto-pause after 10 seconds, followed by auto-resume.

## Answer

Implemented the 10-second BLE disconnection grace period, exponential backoff reconnection loop, and automatic recovery/resume in `SessionRepository` alongside `BleReconnectionPolicy`, `BleDisconnectAlertNotifier`, and `SessionUiState` connection state exposure:

- `app/src/main/java/com/example/heartzonemonitor/data/ble/BleReconnectionPolicy.kt`: Defines the default 10,000 ms grace period and 1-indexed exponential backoff schedule (`0s, 2s, 5s, 10s, 20s, 40s, 60s` max).
- `app/src/main/java/com/example/heartzonemonitor/data/session/BleDisconnectAlertNotifier.kt`: Adds `BleDisconnectAlertNotifier`, `NoOpBleDisconnectAlertNotifier`, and `AndroidBleDisconnectAlertNotifier` targeting `session_alerts_channel` (`IMPORTANCE_HIGH`) with API 33+ `POST_NOTIFICATIONS` permission checks and `SecurityException` guards.
- `app/src/main/java/com/example/heartzonemonitor/data/session/SessionUiState.kt`: Appends `connectionState: BleConnectionState` (defaulting to `Connected(activeHrm.macAddress)`) to `Recording` and `Paused`.
- `app/src/main/java/com/example/heartzonemonitor/data/session/SessionRepository.kt`: Exposes `connectionState: StateFlow<BleConnectionState>`, enters `Reconnecting(mac, attempt)` without pausing on link drops during `Recording`, runs the 10s grace timer (`Paused(BLE_CONNECTION_LOST)`, buffer flush, alert notification) and exponential backoff GATT re-initialization loop, auto-resumes only `BLE_CONNECTION_LOST` pauses, preserves `USER_MANUAL` pauses, and cancels active timers on session completion, discard, or repository close.
- `app/src/test/java/com/example/heartzonemonitor/data/ble/BleReconnectionPolicyTest.kt` and `app/src/test/java/com/example/heartzonemonitor/data/session/SessionDisconnectReconnectionTest.kt`: Verify backoff math, <10s seamless recovery, >10s auto-pause + alert + auto-resume, `USER_MANUAL` pause invariants, GATT re-init failure retries, external reconnects, and lifecycle cancellation.
