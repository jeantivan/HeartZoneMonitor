# 18: Live Session UI: Zone Progress Bar & Monospace Timer Controls

**What to build:** The secondary visual elements of the Athletic Monolith live screen in Compose: a 5-segment horizontal Heart Zone indicator bar, a single-metric monospace Effective Duration timer card, and large 56dp action controls.

**Blocked by:** 17 (Live Session UI: Dynamic Zone Background & Giant Typography)

**Status:** resolved

**Design Specification & Mockups:**
- Screen Spec: [Live Session Screen (The Athletic Monolith)](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/screens.md#3-live-session-screen-the-athletic-monolith)
- Visual Mockup: [`docs/design/stitch/03-live-session/screen.png`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/03-live-session/screen.png)
- HTML Prototype: [`docs/design/stitch/03-live-session/code.html`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/03-live-session/code.html)
- Design System: [Athletic Monolith Design System](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/DESIGN_SYSTEM.md)

- [x] Renders a 5-segment horizontal progress bar representing Zones 1 through 5: active zone segment renders in solid white; past zones in 55% white; future zones in translucent black (`alpha = 0.25f`).
- [x] Renders clean textual labels (`Z1`, `Z2`, `Z3`, `Z4`, `Z5`) under each segment.
- [x] Renders a focused duration card displaying exclusively active `Effective Duration` in 52sp bold monospace (`HH:mm:ss` or `mm:ss`), omitting secondary elapsed or paused clocks.
- [x] Renders large 56dp bottom action controls: Pause/Resume toggle button with high-contrast white border, and Stop & Save button with translucent dark container (`Color.Black.copy(alpha = 0.32f)`).
- [x] UI tests verify button click dispatches and segment highlight logic as zones transition.

## Answer

Implemented the Athletic Monolith Live Session secondary telemetry and 56dp controls:
- Added `app/src/main/java/com/example/heartzonemonitor/ui/session/LiveSessionSecondaryControls.kt` with `formatEffectiveDuration` (`mm:ss` under 1 hour and zero-padded `HH:mm:ss` at >= 1 hour), `LiveSessionZoneProgressBar` (5 equal-width segments with `Color.White` active, 55% white past, 25% black future/standby, semantics `LiveSessionSegmentColorKey`, and `Z1`–`Z5` labels directly beneath each segment), `LiveSessionDurationCard` (52sp bold monospace `Effective Duration` only in a `Color.Black.copy(alpha = 0.32f)` container), `LiveSessionControlButtons` (56dp Pause/Resume toggle with `Color.White` border, 56dp Stop & Save with `Color.Black.copy(alpha = 0.32f)`, and 56dp idle Start Session using `live_session_start_button`), and `LiveSessionMicroSessionDiscardDialog`.
- Updated `app/src/main/java/com/example/heartzonemonitor/ui/session/LiveSessionUiState.kt` and `app/src/main/java/com/example/heartzonemonitor/ui/session/LiveSessionScreen.kt` to wire `LiveSessionSecondaryTelemetry` and `LiveSessionControlButtons` as the default `secondaryContent` and `controlsContent` slots, map `pendingMicroSessionDiscardPrompt`, expose `refreshDurations()` and micro-session actions on `LiveSessionViewModel` / `LiveSessionActions`, and drive a 1-second `LaunchedEffect(uiState.isRecording)` duration ticker.
- Added `app/src/test/java/com/example/heartzonemonitor/ui/session/LiveSessionControlsTest.kt` (9 tests) and expanded `LiveSessionViewModelTest.kt` (7 tests) verifying duration formatting, upward/downward segment color transitions, `Z1`–`Z5` placement, 52sp monospace Effective Duration exclusivity, 56dp control heights/borders/fills/dispatches, micro-session discard dialog, 320dp/411dp layout fit, and end-to-end 1-second duration ticking while preserving all Ticket 17 `LiveSessionScreenTest` invariants.
