# 21: Sensor Contact Loss Detection & Auto-Pause

**What to build:** The physical skin-contact monitoring logic that evaluates characteristic `0x2A37` contact status bits, warning the user and automatically pausing the workout if the chest strap loses skin contact for more than 5 seconds.

**Blocked by:** 14 (Session Recording State Machine Repository), 17 (Live Session UI: Dynamic Zone Background & Giant Typography)

**Status:** ready-for-agent

**Design Specification & Mockups:**
- Screen Spec: [Live Session Screen: State Variations](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/screens.md#3-live-session-screen-the-athletic-monolith)
- Visual Mockup: [`docs/design/stitch/03-live-session/screen.png`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/03-live-session/screen.png)
- HTML Prototype: [`docs/design/stitch/03-live-session/code.html`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/03-live-session/code.html)

- [ ] Continuously parses sensor contact status flags from characteristic `0x2A37`.
- [ ] If contact is lost while BLE remains connected, immediately displays an inline alert banner: *"Sensor contact lost — check strap positioning"*.
- [ ] If contact remains lost for 5 consecutive seconds, transitions the session state to `Paused(SENSOR_CONTACT_LOST)`.
- [ ] As soon as sensor contact is restored, dismisses the alert and automatically transitions the session back to `Recording`.
- [ ] Preserves honest telemetry: does not generate fake 0-BPM samples during lost contact.
- [ ] Automated tests via `FakeBleTransport` verify the 5-second contact-loss debounce, auto-pause transition, and hands-free auto-resume.
