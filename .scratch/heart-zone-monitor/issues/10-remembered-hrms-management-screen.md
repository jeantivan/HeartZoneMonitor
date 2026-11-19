# 10: Remembered HRMs Management Screen

**What to build:** A dedicated management interface accessible from Settings and the session screen to review remembered Heart Rate Monitors, edit custom nicknames, promote a device to Active HRM, and remove devices.

**Blocked by:** 06 (Remembered HRMs Schema & DAO)

**Status:** resolved

**Design Specification & Mockups:**
- Screen Spec: [Device Management Screen](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/screens.md#6-device-management-screen)
- Visual Mockup: [`docs/design/stitch/06-device-management/screen.png`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/06-device-management/screen.png)
- HTML Prototype: [`docs/design/stitch/06-device-management/code.html`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/06-device-management/code.html)
- Design System: [Athletic Monolith Design System](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/DESIGN_SYSTEM.md)

- [x] Displays a list of all Remembered HRMs with advertised name, custom nickname, MAC address, battery level (if known), and Active HRM indicator.
- [x] Tapping an inactive Remembered HRM immediately promotes it to the Active HRM in an atomic transaction.
- [x] Allows editing the custom nickname (e.g. "Chest Strap", "Armband") for any remembered sensor.
- [x] Provides a "Forget Device" action with confirmation to remove a sensor from the database.
- [x] If the Active HRM is deleted, its active status is cleared, GATT connection is closed, and the user is prompted to designate another sensor or scan for a new one.

## Answer

Implemented the Remembered HRMs Management Screen, ViewModel, and navigation integration on branch `ticket/10-device-mgmt`:
- `RememberedHrmsViewModel` (`app/src/main/java/com/example/heartzonemonitor/ui/devices/RememberedHrmsViewModel.kt`):
  - Observes `RememberedHrmDao.observeAllHrms()` and an injected `isSessionActiveFlow: Flow<Boolean> = flowOf(false)` seam, exposing `StateFlow<RememberedHrmsUiState>` partitioned into `activeHrm`, `inactiveHrms`, and `allHrms` alongside modal/prompt state (`editingNicknameTarget`, `nicknameDraft`, `forgetConfirmationTarget`, `showActiveHrmDeletedPrompt`, `isSessionActive`, `errorMessage`).
  - Enforces atomic Active HRM promotion via `RememberedHrmDao.promoteToActiveHrm(macAddress)` (clearing `showActiveHrmDeletedPrompt` on success and guarding against mid-session switches when `isSessionActive == true`).
  - Supports custom nickname editing (`startEditNickname`, `onNicknameDraftChange`, `confirmEditNickname`, `dismissEditNickname`), trimming whitespace and normalizing blank strings to `null` via `RememberedHrmDao.updateNickname`.
  - Supports confirmed device removal (`requestForgetDevice`, `dismissForgetDevice`, `confirmForgetDevice`): when deleting the Active HRM, invokes the injected `onDisconnectActiveHrm(macAddress)` callback to close the GATT link, calls `clearActiveHrms()`, deletes the row via `deleteHrmByMacAddress()`, and raises `showActiveHrmDeletedPrompt = true`; inactive deletions remove the row directly without triggering GATT disconnect or the prompt.
- `RememberedHrmsScreen` & `RememberedHrmsContent` (`app/src/main/java/com/example/heartzonemonitor/ui/devices/RememberedHrmsScreen.kt`):
  - Stateless `RememberedHrmsContent(uiState, actions, modifier)` and `RememberedHrmsActions` bundled callback class following `CODING_STANDARDS.md`, wrapped by stateful `RememberedHrmsScreen`.
  - Athletic Monolith dark UI (`VoidBlack`, `DarkSlate`, `BorderSlate`, `AthleticLime`, `TertiaryCyan`, `WarningAmber`, `ErrorRed`) with >= 56dp interactive action targets, zero `material.icons.*` dependencies, and strict `GLOSSARY.md` terminology.
  - Displays the Active HRM hero card and inactive Remembered HRM cards (advertised name, custom nickname when present, MAC address, battery level or `Battery: Unknown`), 1-tap card / `"Set as Active"` promotion, Edit Nickname modal dialog, Forget Device confirmation dialog, Active HRM Removed guidance card, empty state, Session active lockout notice, and `"Scan for New HRM"` button.
- Navigation Wiring:
  - `NavDestinations.kt`: Added `DEVICES = "devices"`.
  - `MainShell.kt`: Extended `MainShellActions`, `MainShell`, and `SettingsPlaceholder` with defaulted `onManageDevices: () -> Unit = {}` and rendered `"Manage Heart Rate Monitors"` inside `SettingsPlaceholder` without breaking existing `MainShellTest`.
  - `RootNavHost.kt`: Added optional `rememberedHrmsViewModelFactory: (() -> RememberedHrmsViewModel)? = null`, registered `composable(NavDestinations.DEVICES)` inside `MAIN_GRAPH`, and wired `MainShell` `onManageDevices` to navigate to `NavDestinations.DEVICES`.
  - `MainActivity.kt`: Passed `rememberedHrmsViewModelFactory = { RememberedHrmsViewModel(database.rememberedHrmDao()) }` to `RootNavHost`.
- Automated Tests:
  - `RememberedHrmsViewModelTest`: 9 JVM unit tests verifying active/inactive splitting, atomic promotion, nickname trim/clear/cancel, inactive vs. Active HRM deletion ordering (`onDisconnectActiveHrm` -> `clearActiveHrms` -> `deleteHrmByMacAddress`), active-deleted prompt dismissal, session lockout enforcement, and error handling.
  - `RememberedHrmsScreenTest`: 9 Robolectric Compose UI tests verifying Active/inactive card rendering, card tap and `"Set as Active"` promotion, nickname dialog editing & clearing, Forget Device cancel/confirm, Active HRM deleted prompt & remaining HRM promotion, empty state, session lockout UI, and Settings -> Devices -> Back navigation via `RootNavHost`.
