# 33: Settings User Profile Editor & Snapshot Invariant

**What to build:** The User Profile editor in Settings allowing users to update their physiological parameters post-onboarding, while strictly preserving the immutability of historical session profile snapshots.

**Blocked by:** 04 (First-Run Onboarding Profile Input Screen & Live Zone Preview), 25 (Session Detail View: Telemetry & Zone Distribution)

**Status:** ready-for-agent

**Design Specification & Mockups:**
- Screen Spec: [Settings & Profile Screen](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/screens.md#7-settings--profile-screen)
- Visual Mockup: [`docs/design/stitch/07-settings-profile/screen.png`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/07-settings-profile/screen.png)
- HTML Prototype: [`docs/design/stitch/07-settings-profile/code.html`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/07-settings-profile/code.html)
- Design System: [Athletic Monolith Design System](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/DESIGN_SYSTEM.md)

- [ ] Delivers a "User Profile" screen under Settings to modify Birth Year, Resting Heart Rate, Custom HRmax override, and Calculation Mode.
- [ ] Enforces identical physiological validation rules as onboarding (age 10–110, resting HR 30–120 bpm, custom HRmax 100–240 bpm and $> \text{resting HR} + 20$).
- [ ] Updates the singleton `user_profile` record with a new `updatedAtEpochMs` timestamp.
- [ ] Verifies the snapshot invariant: updated profile values apply to all future sessions, but historical session rows and their associated zone breakdowns remain completely unchanged.
- [ ] Automated tests verify persistence of updated profile values and immutability of existing session snapshots.
