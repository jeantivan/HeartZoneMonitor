# 04: First-Run Onboarding Profile Input Screen & Live Zone Preview

**What to build:** The first step of the onboarding interface in Jetpack Compose where users configure their birth year, optional resting heart rate, and optional custom Maximum Heart Rate override, featuring real-time visual feedback of calculated zones and physiological validation.

**Blocked by:** 02 (Physiological Zone Calculator Engine), 03 (User Profile Schema & Local Persistence)

**Status:** ready-for-agent

**Design Specification & Mockups:**
- Screen Spec: [Onboarding Profile Screen](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/screens.md#1-onboarding-profile-screen)
- Visual Mockup: [`docs/design/stitch/01-onboarding-profile/screen.png`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/01-onboarding-profile/screen.png)
- HTML Prototype: [`docs/design/stitch/01-onboarding-profile/code.html`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/01-onboarding-profile/code.html)
- Design System: [Athletic Monolith Design System](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/DESIGN_SYSTEM.md)

- [ ] Compose UI captures Birth Year (required), Resting Heart Rate (optional), and Custom HRmax (optional toggle).
- [ ] Enforces input validation: Birth Year evaluates to age between 10 and 110; Resting Heart Rate must be between 30 and 120 BPM; Custom HRmax must be between 100 and 240 BPM and strictly $> \text{resting HR} + 20$.
- [ ] Automatically selects `KARVONEN` calculation mode if resting heart rate is provided, falling back to `PERCENT_HRMAX` if omitted.
- [ ] Displays live, dynamic preview of Tanaka Maximum Heart Rate and the 5 resulting Heart Zone threshold boundaries as the user types.
- [ ] Next button is disabled until all active validation constraints are satisfied.
- [ ] Tapping Next persists the configured User Profile entity into the encrypted database.
