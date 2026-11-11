# Design first-run onboarding and profile setup flow

Type: grilling
Status: resolved
Blocked by: none

## Question

How should the first-run onboarding flow guide the user through setting up their User Profile (birth year, resting heart rate, calculation mode) and connecting their initial Heart Rate Monitor (HRM), what physiological boundary validation rules should be enforced, and how does the navigation architecture prevent navigating to Session recording until onboarding is complete?

## Answer

All decisions regarding the first-run onboarding flow, navigation guards, physiological boundary validation rules, and initial HRM pairing have been resolved through interactive grilling:

### 1. Root Navigation Gate & Onboarding Invariant
- **Single Source of Truth**: The root `AppViewModel` observes the `user_profile` table via `UserProfileDao.getProfile()`.
- **Hard Gate**:
  - If the table is empty (`null`), the navigation graph sets `OnboardingGraph` as the exclusive start destination.
  - Back-press on the onboarding screen exits the app (the onboarding graph cannot be popped or skipped into the main navigation tree).
  - Main app destinations (`SessionHistory`, `LiveSession`, `Settings`) are structurally unreachable until a valid singleton `user_profile` row (`id = 1`) is written to the encrypted Room database.

### 2. 2-Step Focused Flow Structure
- **Step 1: Physiological Profile & Zone Math**:
  - User enters **Birth Year** and optional **Resting Heart Rate**.
  - Interactive live preview: Displays the Tanaka-calculated HRmax (`208 - 0.7 * age`) and the resulting 5 Heart Zone boundaries in real time as values are typed.
  - Calculation mode selector: Defaults to `KARVONEN` if Resting HR is provided; automatically falls back to `%HRMAX` if Resting HR is omitted.
  - Optional custom HRmax override toggle.
- **Step 2: HRM Discovery & Pairing**:
  - Requests Android 12+ Bluetooth runtime permissions (`BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` with `neverForLocation` flag).
  - Initiates scan for devices advertising standard Heart Rate Service (`0x180D`).
  - Displays discovered devices with RSSI and device name for 1-tap pairing.
  - Designates the paired strap as the initial `Active HRM` in the `remembered_hrms` table.

### 3. Physiological Boundary Validation Rules
- **Birth Year (Required)**:
  - Must evaluate to an Age between `10` and `110` (`currentYear - 110 <= birthYear <= currentYear - 10`).
- **Resting Heart Rate (Optional)**:
  - If omitted/unknown: App operates in standard `%HRMAX` calculation mode. An educational hint is displayed: *"Optional. Leave blank to calculate zones via %HRmax, or enter your resting pulse (30–120 bpm) for Karvonen Heart Rate Reserve."*
  - If provided: Must be an integer between `30` and `120` BPM.
- **Custom HRmax (Optional Override)**:
  - If toggled ON: Must be between `100` and `240` BPM.
  - In Karvonen mode, must be strictly greater than `restingHr + 20` to guarantee a positive Heart Rate Reserve ($HRR \ge 20$).
- **Next Button State**: The "Next" button remains disabled until all active field constraints are satisfied.

### 4. HRM Pairing Flexibility & Session Gating
- **"Pair Later" Allowed**:
  - Step 2 encourages immediate scanning and pairing.
  - If the user does not have their chest strap on or nearby, a **"Pair Later"** action allows completing onboarding and entering the app.
- **Active HRM Invariant Enforced**:
  - If onboarding was completed without pairing a device, the Session Recording screen displays a persistent prominent card: *"No Heart Rate Monitor paired. [Pair HRM in Settings]"* and the **Start Session** button remains disabled until an HRM is paired and set as Active (satisfying Ticket 03).

### 5. Post-Onboarding Transition & Profile Management
- **Smooth Navigation Transition**:
  - Tapping "Finish" (or "Pair Later") inserts the `user_profile` entity into the encrypted database and pops the entire `OnboardingGraph` off the backstack, transitioning seamlessly to the main application shell (landing on the Session History / Ready screen).
- **Settings Profile Management**:
  - A dedicated "User Profile" screen in Settings allows editing Birth Year, Resting HR, Custom HRmax, or Calculation Mode at any time.
  - **Snapshot Invariant**: Changes to the user profile update calculations for all future workouts, but never mutate the frozen snapshot metrics of past historical sessions (satisfying Ticket 04).
