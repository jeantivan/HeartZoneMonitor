# 09: Onboarding Step 2: HRM Pairing & "Pair Later"

**What to build:** The second step of the onboarding flow where users discover nearby Heart Rate Monitors, pair their primary sensor, or choose to skip pairing for later.

**Blocked by:** 05 (Root Navigation Gate for Onboarding), 06 (Remembered HRMs Schema & DAO), 08 (BLE Device Discovery & Scan Filtering)

**Status:** resolved

**Design Specification & Mockups:**
- Screen Spec: [Onboarding Pairing Screen](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/screens.md#2-onboarding-pairing-screen)
- Visual Mockup: [`docs/design/stitch/02-onboarding-pairing/screen.png`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/02-onboarding-pairing/screen.png)
- HTML Prototype: [`docs/design/stitch/02-onboarding-pairing/code.html`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/02-onboarding-pairing/code.html)
- Design System: [Athletic Monolith Design System](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/DESIGN_SYSTEM.md)

- [x] Renders Step 2 onboarding screen in Compose, prompting for Bluetooth permissions and starting the 0x180D discovery scan.
- [x] Displays discovered sensors in a clean list with name, MAC snippet, and RSSI bars.
- [x] Tapping a discovered sensor pairs it, persists it in `remembered_hrms`, marks it as the Active HRM (`isActive = 1`), and transitions into the main app.
- [x] Provides a clear "Pair Later" action allowing the user to bypass sensor pairing and land on the main screen.
- [x] If "Pair Later" was chosen, the live session screen displays a prominent notice and disables the Start Session button until a sensor is paired.

## Answer

Implemented Onboarding Step 2 (HRM Pairing & "Pair Later") along with the Live Session no-HRM standby gate and full navigation/DI wiring:

- `OnboardingPairingViewModel` (`app/src/main/java/com/example/heartzonemonitor/ui/onboarding/OnboardingPairingViewModel.kt`):
  - Coordinates `BleDeviceDiscoveryService`, `RememberedHrmDao`, `BlePermissionChecker`, and injectable `nowEpochMs`, exposing `StateFlow<OnboardingPairingUiState>` (`permissionStatus`, `discoveryState`, `discoveredHrms`, `pairingMacAddress`, `errorMessage`, `pairingCompleted`).
  - Auto-starts the `0x180D` scan when scan/connect permissions are granted (`init` and `onPermissionsUpdated`) without double-restarting an active scan; supports `startOrRestartScan()`, `pairDevice(device)`, `onPairLater()`, `dismissError()`, and clean scan teardown in `onCleared()`.
  - `pairDevice` guards atomically against concurrent taps, stops scanning strictly prior to GATT connect via `discoveryService.connectToHrm(...)`, persists `RememberedHrmEntity(..., isActive = true)` via `rememberedHrmDao.upsertHrm(...)` on success, and surfaces clear inline errors without persisting or navigating on failure.
- `OnboardingPairingScreen` & `OnboardingPairingContent` (`app/src/main/java/com/example/heartzonemonitor/ui/onboarding/OnboardingPairingScreen.kt`):
  - Follows `CODING_STANDARDS.md` Section 5 (`OnboardingPairingScreen` stateful wrapper + stateless `OnboardingPairingContent(uiState, actions, modifier)` with `OnboardingPairingActions`).
  - Integrates `BlePermissionRequester(includeNotifications = false, requestImmediately = true)`, Step 2 of 2 progress header, `neverForLocation` permission status card, scanning beacon radar card with Rescan action, discovered HRM cards with advertised name, truncated MAC snippet (`AA:BB:CC:...:12`), live RSSI dBm + 4-bar signal indicator, 1-tap connect with loading state, inline error banner, and a clear docked `"Pair Later"` action.
- `LiveSessionNoHrmNotice` (`app/src/main/java/com/example/heartzonemonitor/ui/session/LiveSessionNoHrmNotice.kt`):
  - Stateless Athletic Monolith standby view displayed on the Live Session tab whenever no Active HRM is paired: renders `"No Heart Rate Monitor paired"`, `"Pair HRM in Settings"` button wired to `onManageDevices`, and visibly disabled `"Start Session"` button (`enabled = false`).
- Navigation & Activity Wiring (`RootViewModel.kt`, `RootNavHost.kt`, `MainShell.kt`, `MainActivity.kt`):
  - `RootViewModel` accepts optional `rememberedHrmDao: RememberedHrmDao? = null` and exposes `activeHrm: StateFlow<RememberedHrmEntity?>`.
  - `RootNavHost` replaces `OnboardingPairingPlaceholder` with `OnboardingPairingScreen` (with stateless fallback when `onboardingPairingViewModelFactory == null`), pops `ONBOARDING_GRAPH` inclusively into `MAIN_GRAPH` on both successful pairing and `"Pair Later"`, and passes reactive `activeHrm` to `MainShell`.
  - `MainShell` / `MainShellContent` gate `MainTab.LIVE_SESSION` on `activeHrm`, clearing the notice automatically once an Active HRM is paired.
  - `MainActivity` wires `AndroidBlePermissionChecker`, `AndroidBleTransport`, `BleDeviceDiscoveryService`, `OnboardingPairingViewModel`, and `database.rememberedHrmDao()` into `RootViewModel` and `RootNavHost`, closing transport resources in `onDestroy()`.
- Automated Tests:
  - `OnboardingPairingViewModelTest` (11 JVM tests), `OnboardingPairingScreenTest` (6 Robolectric Compose tests), `RootNavHostTest` (7 tests), `MainShellTest` (5 tests), and `RootViewModelTest` (5 tests). All 196 unit tests and mechanical glossary/standards checks pass.
