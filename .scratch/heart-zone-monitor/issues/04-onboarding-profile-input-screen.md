# 04: First-Run Onboarding Profile Input Screen & Live Zone Preview

**What to build:** The first step of the onboarding interface in Jetpack Compose where users configure their birth year, optional resting heart rate, and optional custom Maximum Heart Rate override, featuring real-time visual feedback of calculated zones and physiological validation.

**Blocked by:** 02 (Physiological Zone Calculator Engine), 03 (User Profile Schema & Local Persistence)

**Status:** resolved

**Design Specification & Mockups:**
- Screen Spec: [Onboarding Profile Screen](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/screens.md#1-onboarding-profile-screen)
- Visual Mockup: [`docs/design/stitch/01-onboarding-profile/screen.png`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/01-onboarding-profile/screen.png)
- HTML Prototype: [`docs/design/stitch/01-onboarding-profile/code.html`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/01-onboarding-profile/code.html)
- Design System: [Athletic Monolith Design System](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/DESIGN_SYSTEM.md)

- [x] Compose UI captures Birth Year (required), Resting Heart Rate (optional), and Custom HRmax (optional toggle).
- [x] Enforces input validation: Birth Year evaluates to age between 10 and 110; Resting Heart Rate must be between 30 and 120 BPM; Custom HRmax must be between 100 and 240 BPM and strictly $> \text{resting HR} + 20$.
- [x] Automatically selects `KARVONEN` calculation mode if resting heart rate is provided, falling back to `PERCENT_HRMAX` if omitted.
- [x] Displays live, dynamic preview of Tanaka Maximum Heart Rate and the 5 resulting Heart Zone threshold boundaries as the user types.
- [x] Next button is disabled until all active validation constraints are satisfied.
- [x] Tapping Next persists the configured User Profile entity into the encrypted database.

## Answer

Implemented the Onboarding Profile Input Screen and Live Zone Preview in accordance with Athletic Monolith design specifications:
- `OnboardingProfileValidator`: Pure domain validator enforcing physiological constraints:
  - Birth Year: age between 10 and 110 evaluated against current calendar year.
  - Resting Heart Rate: optional, 30–120 BPM when present.
  - Custom HRmax: optional toggle, 100–240 BPM and strictly $> \text{resting HR} + 20$.
  - Mode Selection: automatically activates `KARVONEN` mode when resting HR is supplied, falling back to `PERCENT_HRMAX` when omitted.
  - Live Preview Calculation: evaluates Tanaka formula ceiling and 5 Heart Zone boundaries via `ZoneCalculator`.
- `OnboardingProfileViewModel`: Presentation state holder managing reactive input flow (`StateFlow<OnboardingProfileUiState>`), revalidating on every keystroke/toggle, disabling/enabling save button, and persisting `UserProfileEntity` (with `id = 1`) into `UserProfileDao` on `saveProfile()`.
- `OnboardingProfileScreen` & `OnboardingProfileContent`: Athletic Monolith Composable UI matching `docs/design/stitch/01-onboarding-profile/code.html`:
  - 2-step progress header with offline & encrypted badge.
  - High-contrast OLED dark theme cards (`VoidBlack`, `DarkSlate`, `BorderSlate`, `AthleticLime`, `TertiaryCyan`).
  - Form card capturing required birth year, optional resting HR, and custom HRmax override toggle.
  - Live Zone Preview card with calculated ceiling, mode badge (`KARVONEN HRR` or `%HRMAX`), 5-row zone breakdown table, and 5-segment continuous spectrum bar.
  - Docked 56dp bottom action button ("Continue to Sensor Pairing"), disabled until all active validation constraints pass, triggering encrypted profile persistence and navigation callback.
- Automated Test Coverage:
  - `OnboardingProfileValidatorTest`: 14 unit tests validating all boundary and error conditions.
  - `OnboardingProfileViewModelTest`: 7 unit tests validating initial state, reactive updates, mode switches, custom HRmax validation, persistence to `UserProfileDao`, and error handling.
  - `OnboardingProfileScreenTest`: 5 Robolectric Compose UI tests asserting rendering, live preview updates, error messages, and button interactions.
