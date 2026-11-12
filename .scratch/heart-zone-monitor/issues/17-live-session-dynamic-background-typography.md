# 17: Live Session UI: Dynamic Zone Background & Giant Typography

**What to build:** The core visual layer of the Athletic Monolith live screen in Jetpack Compose, featuring massive glanceable BPM typography and full-bleed animated background color illumination that dynamically matches the active Heart Zone.

**Blocked by:** 02 (Physiological Zone Calculator Engine), 14 (Session Recording State Machine Repository)

**Status:** ready-for-agent

**Design Specification & Mockups:**
- Screen Spec: [Live Session Screen (The Athletic Monolith)](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/screens.md#3-live-session-screen-the-athletic-monolith)
- Visual Mockup: [`docs/design/stitch/03-live-session/screen.png`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/03-live-session/screen.png)
- HTML Prototype: [`docs/design/stitch/03-live-session/code.html`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/03-live-session/code.html)
- Design System: [Athletic Monolith Design System](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/DESIGN_SYSTEM.md)

- [ ] Implements full-bleed screen background animating smoothly (`animateColorAsState` over 500ms with `FastOutSlowInEasing`) into the saturated color of the active Heart Zone (Slate, Blue, Green, Amber, Red).
- [ ] Applies vertical gradient from active zone color to 82% alpha depth for extreme outdoor sunlight contrast.
- [ ] Renders centered massive 124sp bold BPM typography (`letterSpacing = -4sp`) readable from handlebar mounts or armbands.
- [ ] Renders translucent badge `BPM | ZONE X` directly beneath the BPM counter.
- [ ] Omits decorative heart icons and raw percentage math to preserve focused athletic telemetry.
- [ ] Compose screenshot and UI tests verify color transitions and layout scaling across screen sizes.
