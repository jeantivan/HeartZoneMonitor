# 24: Session History List & O(1) Athletic Cards

**What to build:** The chronological workout history screen rendering athletic cards reading precalculated summary columns with $O(1)$ performance, without requiring heavy sample queries or table joins.

**Blocked by:** 12 (Sessions & Heart Rate Samples Schema & Buffered Batch DAO)

**Status:** ready-for-agent

**Design Specification & Mockups:**
- Screen Spec: [Session History Screen](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/screens.md#4-session-history-screen)
- Visual Mockup: [`docs/design/stitch/04-session-history/screen.png`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/04-session-history/screen.png)
- HTML Prototype: [`docs/design/stitch/04-session-history/code.html`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/04-session-history/code.html)
- Design System: [Athletic Monolith Design System](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/DESIGN_SYSTEM.md)

- [ ] Queries `sessions` table ordered by `startEpochMs DESC` using purely precalculated summary metrics (`effectiveDurationMs`, `avgBpm`, `maxBpm`, `zone1DurationMs` ... `zone5DurationMs`).
- [ ] Renders an athletic session card displaying: formatted session date and start time, large bold `Effective Duration` (e.g. `45:12`), Average BPM, and Maximum BPM.
- [ ] Renders a proportional horizontal mini 5-segment bar showing the relative time spent in Zones 1 through 5 using official zone colors (Slate, Blue, Green, Amber, Red).
- [ ] If session status is `COMPLETED_UNEXPECTEDLY`, displays a subtle warning badge indicating ungraceful shutdown.
- [ ] Tapping a session card navigates to the Session Detail view with the session ID.
- [ ] Compose UI tests verify card layout, metric display, and empty state handling.
