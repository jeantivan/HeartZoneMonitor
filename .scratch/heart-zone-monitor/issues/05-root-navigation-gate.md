# 05: Root Navigation Gate for Onboarding

**What to build:** The root navigation guard that locks app start destinations to the onboarding flow until a valid User Profile row exists in the encrypted database.

**Blocked by:** 04 (First-Run Onboarding Profile Input Screen & Live Zone Preview)

**Status:** ready-for-agent

**Design Specification & Mockups:**
- Navigation Graph: [Screen Overview & Navigation Graph](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/screens.md#screen-overview--navigation-graph)
- Navigation Shell Behavior: [Navigation & Shell Behavior](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/DESIGN_SYSTEM.md#5-navigation--shell-behavior)

- [ ] Root ViewModel observes `UserProfileDao.getProfile()`.
- [ ] If no profile exists (`null`), start destination of the navigation graph is locked to `OnboardingGraph`.
- [ ] System back-press while in the onboarding flow exits the application (backstack cannot be popped to main destinations).
- [ ] Main app destinations (`SessionHistory`, `LiveSession`, `Settings`) are structurally unreachable without an existing profile.
- [ ] Saving a valid profile immediately unlocks the navigation graph and transitions smoothly to the main application shell.
