# 05: Root Navigation Gate for Onboarding

**What to build:** The root navigation guard that locks app start destinations to the onboarding flow until a valid User Profile row exists in the encrypted database.

**Blocked by:** 04 (First-Run Onboarding Profile Input Screen & Live Zone Preview)

**Status:** resolved

**Design Specification & Mockups:**
- Navigation Graph: [Screen Overview & Navigation Graph](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/screens.md#screen-overview--navigation-graph)
- Navigation Shell Behavior: [Navigation & Shell Behavior](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/DESIGN_SYSTEM.md#5-navigation--shell-behavior)

- [x] Root ViewModel observes `UserProfileDao.getProfile()`.
- [x] If no profile exists (`null`), start destination of the navigation graph is locked to `OnboardingGraph`.
- [x] System back-press while in the onboarding flow exits the application (backstack cannot be popped to main destinations).
- [x] Main app destinations (`SessionHistory`, `LiveSession`, `Settings`) are structurally unreachable without an existing profile.
- [x] Saving a valid profile immediately unlocks the navigation graph and transitions smoothly to the main application shell.

## Answer

Implemented the Root Navigation Guard and shell architecture for onboarding gating:
- `RootViewModel`: Presentation state holder observing `UserProfileDao.getProfileFlow()`, mapping Room emissions to reactive `RootNavState` (`Loading`, `Onboarding` when profile is null, `Ready(userProfile)` when non-null).
- `NavDestinations`: Codified route constants for root graphs (`onboarding_graph`, `main_graph`) and destinations (`onboarding/profile`, `onboarding/pairing`, `session/live`, `history`, `settings`).
- `RootNavHost`: Compose root navigation guard that:
  - Dynamically sets `startDestination` based on whether a User Profile row exists in the encrypted database.
  - Structurally isolates main application destinations: `MAIN_GRAPH` (and its routes `session/live`, `history`, `settings`) is conditionally included in the navigation graph builder only when `hasProfile == true`, ensuring navigation attempts when no profile exists fail fast with `IllegalArgumentException`.
  - Enforces backstack invariants: locks system back-press on the onboarding screen via `BackHandler(enabled = !hasProfile)` to exit the application rather than popping back into unauthenticated/uninitialized states.
  - Dynamic unlock: automatically observes database writes via `LaunchedEffect(hasProfile)` when a valid User Profile is saved, unlocking `MAIN_GRAPH` and transitioning smoothly while popping `ONBOARDING_GRAPH` from the backstack.
- `MainShell`: Athletic Monolith 3-tab navigation shell (`LIVE`, `HISTORY`, `SETTINGS`) adhering to `DESIGN_SYSTEM.md`:
  - 56dp+ bottom navigation bar with OLED dark theme styling (`VoidBlack`, `BorderSlate`, `AthleticLime`).
  - Supports immersion mode invariant where bottom navigation bar is hidden during active workouts.
- `FakeUserProfileDao`: Reusable test double for deterministic reactive flow and database assertion in unit and Compose tests.
- Comprehensive Automated Test Suite:
  - `RootViewModelTest`: 3 unit tests verifying initial onboarding state, existing profile detection, and dynamic transitions.
  - `RootNavHostTest`: 5 Robolectric Compose tests asserting start destination gating, structural unreachability of main destinations, dynamic unlocking, and back-press exit handling.
  - `MainShellTest`: 3 Robolectric Compose tests verifying tab navigation, profile display, and immersion mode hiding.
