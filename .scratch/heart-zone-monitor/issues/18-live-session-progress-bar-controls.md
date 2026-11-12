# 18: Live Session UI: Zone Progress Bar & Monospace Timer Controls

**What to build:** The secondary visual elements of the Athletic Monolith live screen in Compose: a 5-segment horizontal Heart Zone indicator bar, a single-metric monospace Effective Duration timer card, and large 56dp action controls.

**Blocked by:** 17 (Live Session UI: Dynamic Zone Background & Giant Typography)

**Status:** ready-for-agent

**Design Specification & Mockups:**
- Screen Spec: [Live Session Screen (The Athletic Monolith)](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/screens.md#3-live-session-screen-the-athletic-monolith)
- Visual Mockup: [`docs/design/stitch/03-live-session/screen.png`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/03-live-session/screen.png)
- HTML Prototype: [`docs/design/stitch/03-live-session/code.html`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/03-live-session/code.html)
- Design System: [Athletic Monolith Design System](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/DESIGN_SYSTEM.md)

- [ ] Renders a 5-segment horizontal progress bar representing Zones 1 through 5: active zone segment renders in solid white; past zones in 55% white; future zones in translucent black (`alpha = 0.25f`).
- [ ] Renders clean textual labels (`Z1`, `Z2`, `Z3`, `Z4`, `Z5`) under each segment.
- [ ] Renders a focused duration card displaying exclusively active `Effective Duration` in 52sp bold monospace (`HH:mm:ss` or `mm:ss`), omitting secondary elapsed or paused clocks.
- [ ] Renders large 56dp bottom action controls: Pause/Resume toggle button with high-contrast white border, and Stop & Save button with translucent dark container (`Color.Black.copy(alpha = 0.32f)`).
- [ ] UI tests verify button click dispatches and segment highlight logic as zones transition.
