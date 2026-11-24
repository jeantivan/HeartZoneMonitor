# 21: Sensor Contact Loss Detection & Auto-Pause

**What to build:** The physical skin-contact monitoring logic that evaluates characteristic `0x2A37` contact status bits, warning the user and automatically pausing the workout if the chest strap loses skin contact for more than 5 seconds.

**Blocked by:** 14 (Session Recording State Machine Repository), 17 (Live Session UI: Dynamic Zone Background & Giant Typography)

**Status:** resolved

**Design Specification & Mockups:**
- Screen Spec: [Live Session Screen: State Variations](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/screens.md#3-live-session-screen-the-athletic-monolith)
- Visual Mockup: [`docs/design/stitch/03-live-session/screen.png`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/03-live-session/screen.png)
- HTML Prototype: [`docs/design/stitch/03-live-session/code.html`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/03-live-session/code.html)

- [x] Continuously parses sensor contact status flags from characteristic `0x2A37`.
- [x] If contact is lost while BLE remains connected, immediately displays an inline alert banner: *"Sensor contact lost — check strap positioning"*.
- [x] If contact remains lost for 5 consecutive seconds, transitions the session state to `Paused(SENSOR_CONTACT_LOST)`.
- [x] As soon as sensor contact is restored, dismisses the alert and automatically transitions the session back to `Recording`.
- [x] Preserves honest telemetry: does not generate fake 0-BPM samples during lost contact.
- [x] Automated tests via `FakeBleTransport` verify the 5-second contact-loss debounce, auto-pause transition, and hands-free auto-resume.

## Answer

Implemented 5-second sensor contact loss detection, honest-telemetry filtering, auto-pause/hands-free auto-resume, and the Athletic Monolith inline contact-loss alert banner across the Session state machine and Live Session UI:

- `app/src/main/java/com/example/heartzonemonitor/data/session/SessionRepository.kt`: Added `SENSOR_CONTACT_LOSS_DEBOUNCE_MS = 5_000L` and optional `contactLossDebounceMs` parameter (with `getInstance` compatibility). Continuously evaluates `0x2A37` `measurement.sensorContact` (`false` only for `SensorContactStatus.NOT_DETECTED`, avoiding false pauses on `NOT_SUPPORTED` HRMs). On contact loss during `Recording`, skips enqueueing any Heart Rate Sample (zero fake 0-BPM or stale samples), immediately republishes `sensorContact = false`, and runs a single generation-guarded 5-second debounce coroutine (`contactLossJob`) without resetting on subsequent contact-lost frames. Transitions via `pauseSessionLocked(session, PauseReason.SENSOR_CONTACT_LOST)` (pausing `MonotonicDurationEngine`, flushing `HeartRateSampleBuffer`, persisting `SessionStatus.PAUSED`) after 5 consecutive seconds, auto-resumes via `resumeSessionLocked(session)` exclusively from `PauseReason.SENSOR_CONTACT_LOST` on contact restoration (enqueuing valid restoring BPM samples while skipping 0-BPM samples), preserves `USER_MANUAL` and `BLE_CONNECTION_LOST` pauses, and cancels pending timers cleanly on manual pause, BLE disconnect/reconnection, `stopSession`, `discardSession`, and `close()`.
- `app/src/main/java/com/example/heartzonemonitor/ui/session/LiveSessionUiState.kt`: Added `sensorContact: Boolean? = null` and `isSensorContactLost: Boolean = false` to `LiveSessionUiState` and mapped contact-loss visibility across `Idle`, `Recording`, `Paused`, `Completed`, and `Discarded` in `SessionUiState.toLiveSessionUiState()`.
- `app/src/main/java/com/example/heartzonemonitor/ui/session/LiveSessionContactLossBanner.kt` & `app/src/main/java/com/example/heartzonemonitor/ui/session/LiveSessionScreen.kt`: Created the Athletic Monolith `LiveSessionContactLossBanner` (`live_session_contact_loss_banner`) displaying `"Sensor contact lost — check strap positioning"` with WarningAmber (`#F59E0B`) border and status dot on a translucent dark surface (zero Material icon imports and zero forbidden text), wired into `LiveSessionContent`'s default `statusBannerContent` ahead of `LiveSessionAutoConnectBanner`.
- `app/src/test/java/com/example/heartzonemonitor/data/session/SessionContactLossAutoPauseTest.kt` & `app/src/test/java/com/example/heartzonemonitor/ui/session/LiveSessionContactLossUiTest.kt`: Added deterministic virtual-time and Robolectric Compose UI/ViewModel tests covering immediate contact-loss signaling, <5s transient recovery, exact 5s debounce + buffer flush, honest telemetry (zero fake 0-BPM/stale samples), hands-free auto-resume (valid and 0-BPM restore), `USER_MANUAL` pause preservation, BLE disconnect cancellation during debounce, `SensorContactStatus.NOT_SUPPORTED` safety, lifecycle cleanup, and inline banner rendering/dismissal.
