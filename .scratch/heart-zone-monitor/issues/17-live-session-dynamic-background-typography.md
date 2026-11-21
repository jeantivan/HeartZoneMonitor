# 17: Live Session UI: Dynamic Zone Background & Giant Typography

**What to build:** The core visual layer of the Athletic Monolith live screen in Jetpack Compose, featuring massive glanceable BPM typography and full-bleed animated background color illumination that dynamically matches the active Heart Zone.

**Blocked by:** 02 (Physiological Zone Calculator Engine), 14 (Session Recording State Machine Repository)

**Status:** resolved

**Design Specification & Mockups:**
- Screen Spec: [Live Session Screen (The Athletic Monolith)](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/screens.md#3-live-session-screen-the-athletic-monolith)
- Visual Mockup: [`docs/design/stitch/03-live-session/screen.png`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/03-live-session/screen.png)
- HTML Prototype: [`docs/design/stitch/03-live-session/code.html`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/03-live-session/code.html)
- Design System: [Athletic Monolith Design System](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/DESIGN_SYSTEM.md)

- [x] Implements full-bleed screen background animating smoothly (`animateColorAsState` over 500ms with `FastOutSlowInEasing`) into the saturated color of the active Heart Zone (Slate, Blue, Green, Amber, Red).
- [x] Applies vertical gradient from active zone color to 82% alpha depth for extreme outdoor sunlight contrast.
- [x] Renders centered massive 124sp bold BPM typography (`letterSpacing = -4sp`) readable from handlebar mounts or armbands.
- [x] Renders translucent badge `BPM | ZONE X` directly beneath the BPM counter.
- [x] Omits decorative heart icons and raw percentage math to preserve focused athletic telemetry.
- [x] Compose screenshot and UI tests verify color transitions and layout scaling across screen sizes.

## Answer

Implemented the Athletic Monolith Live Session visual layer and reactive UI state mapping:
- Added `LiveSessionUiState`, `LiveSessionActions`, Heart Zone color mapping (`HeartZone?.toLiveSessionBackgroundColor()`), and `LiveSessionViewModel` in `app/src/main/java/com/example/heartzonemonitor/ui/session/LiveSessionUiState.kt`.
- Added stateful `LiveSessionScreen` and stateless `LiveSessionContent` (with `secondaryContent` and `controlsContent` slots for Ticket 18, 500ms `FastOutSlowInEasing` animated 82%-depth vertical gradient, 124sp bold `-4sp` BPM display, and `BPM | ZONE X` / `BPM | STANDBY` badge) in `app/src/main/java/com/example/heartzonemonitor/ui/session/LiveSessionScreen.kt`.
- Wired `LiveSessionScreen` / `LiveSessionContent` and optional `liveSessionViewModelFactory` (with `isImmersionMode` bottom-bar hiding) into `MainShell.kt` and `RootNavHost.kt`.
- Verified with `LiveSessionViewModelTest` (5 tests), `LiveSessionScreenTest` (7 Robolectric Compose UI tests across all 5 zones, 500ms easing interpolation, omitted heart icons/percentages, and 320dp/411dp/600dp layout bounds), `MainShellTest`, and `RootNavHostTest`.
