# 25: Session Detail View: Telemetry & Zone Distribution

**What to build:** The upper analytical sections of the unified vertical Session Detail view, displaying session metadata, hero telemetry cards, a proportional zone bar with granular breakdown table, and frozen profile snapshot metrics.

**Blocked by:** 24 (Session History List & O(1) Athletic Cards)

**Status:** ready-for-agent

**Design Specification & Mockups:**
- Screen Spec: [Session Detail & Analytics Screen](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/screens.md#5-session-detail--analytics-screen)
- Visual Mockup: [`docs/design/stitch/05-session-detail/screen.png`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/05-session-detail/screen.png)
- HTML Prototype: [`docs/design/stitch/05-session-detail/code.html`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/05-session-detail/code.html)
- Design System: [Athletic Monolith Design System](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/DESIGN_SYSTEM.md)

- [ ] Displays a top Header Card with formatted start and end timestamps, session completion status badge, and connected sensor metadata (name, MAC address).
- [ ] Displays a 4-column Hero Telemetry row: `Effective Duration`, Average BPM, Maximum BPM, and Minimum BPM.
- [ ] Displays a Zone Distribution Card featuring:
  - A primary 5-segment horizontal proportional bar with percentage labels.
  - A breakdown table listing each zone (Z1–Z5): zone name, BPM boundary range, duration spent (`HH:mm:ss`), and percentage of Effective Duration.
- [ ] Displays a Durations & Physiological Snapshot Card showing: `Elapsed Duration` (wall-clock), `Paused Duration`, and the frozen profile parameters at session start (age, resting HR, calculated HRmax, calculation mode).
- [ ] UI tests verify metric formatting and mathematical consistency between total duration and zone breakdowns.
