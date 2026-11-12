# 19: BLE Auto-Connect & Dormant Strap Notification

**What to build:** The automatic connection workflow on entering the session screen, connecting directly to the Active HRM in the background with a 10-second timeout, and guiding users when dormant chest straps need moistening.

**Blocked by:** 14 (Session Recording State Machine Repository), 17 (Live Session UI: Dynamic Zone Background & Giant Typography)

**Status:** ready-for-agent

**Design Specification & Mockups:**
- Screen Spec: [Live Session Screen (The Athletic Monolith)](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/screens.md#3-live-session-screen-the-athletic-monolith)
- Visual Mockup: [`docs/design/stitch/03-live-session/screen.png`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/03-live-session/screen.png)
- HTML Prototype: [`docs/design/stitch/03-live-session/code.html`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/03-live-session/code.html)

- [ ] On navigating to the live session screen, immediately attempts a direct non-blocking background connection (`autoConnect = false`) to the designated Active HRM with a 10-second timeout.
- [ ] Displays a subtle ambient status banner: *"Connecting to [Nickname/Name]..."*.
- [ ] If the 10-second timeout expires without connection (common for chest straps like COOSPO H808S which remain dormant until worn), transitions the banner to an inline prompt: *"Sensor not found. Ensure strap is moist and worn, or tap to Scan."*.
- [ ] Tapping the prompt opens the BLE scanner bottom sheet.
- [ ] Automated tests via `FakeBleTransport` verify timeout behavior and prompt transitions.
