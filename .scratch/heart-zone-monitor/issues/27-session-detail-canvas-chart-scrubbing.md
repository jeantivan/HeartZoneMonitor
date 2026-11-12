# 27: Session Detail: Interactive Compose Canvas Chart & Touch Scrubbing

**What to build:** The interactive time-series heart rate chart in the Session Detail view, built natively with Jetpack Compose Canvas, featuring faint horizontal zone threshold lines and touch drag scrubbing with crosshair indicators and tooltips.

**Blocked by:** 25 (Session Detail View: Telemetry & Zone Distribution), 26 (LTTB Downsampling Algorithm for Time-Series Samples)

**Status:** ready-for-agent

**Design Specification & Mockups:**
- Screen Spec: [Session Detail & Analytics Screen](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/screens.md#5-session-detail--analytics-screen)
- Visual Mockup: [`docs/design/stitch/05-session-detail/screen.png`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/05-session-detail/screen.png)
- HTML Prototype: [`docs/design/stitch/05-session-detail/code.html`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/05-session-detail/code.html)

- [ ] Renders a native full-width Compose `Canvas` drawing the 1 Hz heart rate polyline across elapsed session time using LTTB downsampling when sample count exceeds 1,000 points.
- [ ] Draws faint horizontal gridlines indicating the session's specific Heart Zone threshold boundaries (50%, 60%, 70%, 80%, 90% of HRmax).
- [ ] Implements touch scrubbing: dragging a finger horizontally draws a vertical crosshair line at the touch position.
- [ ] Displays a dynamic floating tooltip showing: relative elapsed time offset (`MM:SS`), instantaneous heart rate (`XXX BPM`), and the active Heart Zone badge with its corresponding background color.
- [ ] Lifting touch dismisses the crosshair indicator and tooltip smoothly.
- [ ] UI tests verify scrubbing touch coordinate mapping and tooltip content formatting.
