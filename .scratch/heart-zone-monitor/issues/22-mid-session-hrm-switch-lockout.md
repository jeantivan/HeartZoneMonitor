# 22: Mid-Session Active HRM Switch Lockout

**What to build:** The safety constraint preventing users from switching or deleting the Active HRM while a workout session is actively `Recording` or `Paused`, ensuring single-sensor data integrity.

**Blocked by:** 10 (Remembered HRMs Management Screen), 14 (Session Recording State Machine Repository)

**Status:** resolved

- [x] Evaluates the active session state whenever device management actions are invoked.
- [x] If a session is in `Recording` or `Paused` state, disables promoting another Remembered HRM to Active HRM and displays a notice: *"Cannot switch sensor during an active session. Stop or complete the session first."*.
- [x] Disables deleting or forgetting the Active HRM while a session is open.
- [x] Allows full device switching and deletion when the session is `Idle` or finalized (`Completed`/`Discarded`).
- [x] Unit and UI tests verify that device switching is locked during active sessions and unlocked otherwise.

## Answer

Implemented the mid-session Active HRM switch and deletion lockout across `RememberedHrmsViewModel`, `RememberedHrmsScreen`, and `MainActivity`, backed by end-to-end tests wired directly to `SessionRepository`:

- `app/src/main/java/com/example/heartzonemonitor/ui/devices/RememberedHrmsViewModel.kt`: Defined `ACTIVE_SESSION_SWITCH_LOCKOUT_NOTICE` (`"Cannot switch sensor during an active session. Stop or complete the session first."`), added optional `isSessionActiveProvider: () -> Boolean = { false }` (initializing `RememberedHrmsUiState.isSessionActive` from live state), evaluated `_uiState.value.isSessionActive || isSessionActiveProvider()` synchronously and inside launched coroutines right before DAO promotion and Active HRM disconnect/deletion, auto-dismissed open Active HRM forget confirmation dialogs with the lockout notice when a Session starts, and cleared lockout `errorMessage` automatically once a Session finishes while leaving inactive Remembered HRMs unaffected.
- `app/src/main/java/com/example/heartzonemonitor/ui/devices/RememberedHrmsScreen.kt`: Added `REMEMBERED_HRMS_LOCKOUT_NOTICE_TAG` (`"session_lockout_notice"`) and `SCAN_FOR_NEW_HRM_BUTTON_TAG` (`"scan_for_new_hrm_button"`), updated `SessionLockoutNotice` to display `ACTIVE_SESSION_SWITCH_LOCKOUT_NOTICE` alongside the `"LOCKED"` badge prominently near the top of the scrollable column above `ActiveHrmHeroCard`, disabled `ScanForNewHrmButton` during active Sessions with disabled styling, and preserved disabled Active HRM forget and inactive HRM promotion controls while locked.
- `app/src/main/java/com/example/heartzonemonitor/MainActivity.kt`: Wired `isSessionActiveProvider = { sessionRepository.isSessionActive }` alongside `isSessionActiveFlow = sessionRepository.isSessionActiveFlow` in `rememberedHrmsViewModelFactory`.
- `app/src/test/java/com/example/heartzonemonitor/ui/devices/MidSessionHrmLockoutTest.kt`: Added 9 unit and Robolectric Compose UI tests wired directly to a real `SessionRepository` covering `Idle`, `Recording`, `Paused` across all three `PauseReason` values (`USER_MANUAL`, `BLE_CONNECTION_LOST`, `SENSOR_CONTACT_LOST`), `Completed` (`stopSession(forceSave = true)`), `Discarded` (`discardSession()`), `resetToIdle()`, inactive HRM independence, in-flight Active HRM forget confirmation dismissal, synchronous `isSessionActiveProvider` guarding before Flow collection, and in-coroutine re-checks prior to DAO/GATT mutations.
