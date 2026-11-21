# 19: BLE Auto-Connect & Dormant Strap Notification

**What to build:** The automatic connection workflow on entering the session screen, connecting directly to the Active HRM in the background with a 10-second timeout, and guiding users when dormant chest straps need moistening.

**Blocked by:** 14 (Session Recording State Machine Repository), 17 (Live Session UI: Dynamic Zone Background & Giant Typography)

**Status:** resolved

**Design Specification & Mockups:**
- Screen Spec: [Live Session Screen (The Athletic Monolith)](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/screens.md#3-live-session-screen-the-athletic-monolith)
- Visual Mockup: [`docs/design/stitch/03-live-session/screen.png`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/03-live-session/screen.png)
- HTML Prototype: [`docs/design/stitch/03-live-session/code.html`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/03-live-session/code.html)

- [x] On navigating to the live session screen, immediately attempts a direct non-blocking background connection (`autoConnect = false`) to the designated Active HRM with a 10-second timeout.
- [x] Displays a subtle ambient status banner: *"Connecting to [Nickname/Name]..."*.
- [x] If the 10-second timeout expires without connection (common for chest straps like COOSPO H808S which remain dormant until worn), transitions the banner to an inline prompt: *"Sensor not found. Ensure strap is moist and worn, or tap to Scan."*.
- [x] Tapping the prompt opens the BLE scanner bottom sheet.
- [x] Automated tests via `FakeBleTransport` verify timeout behavior and prompt transitions.

## Answer

Implemented the BLE auto-connect workflow, dormant strap prompt, and BLE scanner bottom sheet for the Live Session screen:
- Added `ActiveHrmAutoConnector`, `AutoConnectState` (`Idle`, `Connecting`, `Connected`, `DormantPrompt`), and `resolveHrmDisplayName` (custom nickname -> advertised name -> MAC fallback) in `app/src/main/java/com/example/heartzonemonitor/data/ble/ActiveHrmAutoConnector.kt`. Immediately initiates non-blocking `bleTransport.connect(macAddress, autoConnect = false)` with a strict 10-second `withTimeoutOrNull` timeout, initializes connected GATT sessions (`0x180D` / `0x2A37` / `0x2902` / `0x2A19`) through the shared `HrmGattClient`, short-circuits when already connected, cleans up via `disconnect()` on timeout/failure, clears `DormantPrompt` on external connection, and never interferes with active Sessions or mid-session reconnection loops.
- Added `LiveSessionAutoConnectBanner` in `app/src/main/java/com/example/heartzonemonitor/ui/session/LiveSessionAutoConnectBanner.kt` rendering `"Connecting to [Nickname/Name]..."` while connecting, the clickable inline prompt `"Sensor not found. Ensure strap is moist and worn, or tap to Scan."` on timeout, and nothing for `Idle`/`Connected` while preserving Ticket 17's uncluttered semantic invariants.
- Added Material 3 `BleScannerBottomSheet` with stateless `BleScannerSheetContent` and bundled `BleScannerSheetActions` in `app/src/main/java/com/example/heartzonemonitor/ui/session/BleScannerBottomSheet.kt`, integrating `BleDeviceDiscoveryService` (`0x180D` filter), `BlePermissionRequester` (Android 12+ `neverForLocation` permissions), 1-tap connect + GATT initialization + `RememberedHrmDao` Active HRM persistence/promotion (preserving existing custom nicknames), and clean scan stop on dismiss.
- Exposed shared `SessionRepository.hrmGattClient`, wired `LiveSessionUiState.kt` / `LiveSessionScreen.kt` (optional default params preserving full compatibility with `LiveSessionViewModel(repository)`, `LaunchedEffect`/`DisposableEffect` lifecycle hooks, and top `statusBannerContent` slot), and wired the shared `HrmGattClient` and `ActiveHrmAutoConnector` in `MainActivity.kt`.
- Verified with `ActiveHrmAutoConnectorTest` (10 tests), `LiveSessionAutoConnectUiTest` (7 Robolectric Compose UI tests), `LiveSessionScreenTest` (7 tests), `LiveSessionViewModelTest` (5 tests), targeted `data.ble.*` + `ui.session.*` suite (81/81 passed), full `:app:testDebugUnitTest` (266/266 passed across 37 classes), `./scripts/check-glossary.sh`, and `./scripts/check-standards.sh` (0 violations).
