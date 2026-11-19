# 07: Hardware Seam `BleTransport` & Bluetooth Permissions

**What to build:** The primary external testing seam abstracting Android's Bluetooth LE system APIs, paired with modern Android 12+ runtime permissions handling.

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] Declares Android manifest permissions: `BLUETOOTH_SCAN` with `android:usesPermissionFlags="neverForLocation"`, `BLUETOOTH_CONNECT`, and `POST_NOTIFICATIONS`.
- [x] Implements runtime permission request handling and educational explanation dialog if permissions are denied.
- [x] Defines the `BleTransport` interface representing BLE scanner, GATT connection, characteristic notification subscriptions, and write/read operations.
- [x] Implements `AndroidBleTransport` wrapping standard Android `BluetoothManager`, `BluetoothAdapter`, and `BluetoothGatt`.
- [x] Implements `FakeBleTransport` test double allowing simulated BLE scans, connection drops, RSSI values, and synthetic byte streaming for automated testing.

## Answer

Implemented the hardware BLE transport seam and modern Android 12+ Bluetooth/notification permission handling on branch `ticket/07-ble-transport`:

- **Manifest (`app/src/main/AndroidManifest.xml`)**: Declared `android.permission.BLUETOOTH_SCAN` with `android:usesPermissionFlags="neverForLocation"` (`tools:targetApi="s"`), `android.permission.BLUETOOTH_CONNECT`, `android.permission.POST_NOTIFICATIONS`, and `<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />` without requesting `ACCESS_FINE_LOCATION` or `ACCESS_COARSE_LOCATION`.
- **Runtime Permissions & Rationale UI (`data/ble/BlePermissions.kt`, `ui/ble/BlePermissionDialog.kt`)**: Added `BlePermissionStatus` (`allBlePermissionsGranted`, `allRequiredPermissionsGranted`, `missingPermissions`), `BlePermissions` (`requiredBlePermissions`, `requiredAllPermissions`, `createAppSettingsIntent`), `BlePermissionChecker` / `AndroidBlePermissionChecker` (API 31–32 vs API 33+ handling), `BlePermissionDialogUiState`, `BlePermissionDialogActions`, stateless Athletic Monolith `BlePermissionRationaleDialogContent`, and stateful `BlePermissionRequester` wrapping `ActivityResultContracts.RequestMultiplePermissions()` with denial rationale and permanently-denied Android App Settings guidance.
- **BLE Seam & Models (`data/ble/BleUuids.kt`, `data/ble/BleModels.kt`, `data/ble/BleTransport.kt`)**: Defined Bluetooth SIG UUIDs (`0x180D`, `0x2A37`, `0x2902`, `0x180F`, `0x2A19`), `BleScanResult`, `BleConnectionState`, `BleDiscoveredService`, `BleCharacteristicNotification` (content-based `equals`/`hashCode`), `BleOperationResult`, and the `BleTransport` interface.
- **Production Adapter (`data/ble/AndroidBleTransport.kt`)**: Implemented `AndroidBleTransport` wrapping `BluetoothManager`, `BluetoothAdapter`, `BluetoothLeScanner`, and `BluetoothGatt` with permission/adapter checks, `ScanFilter` (`ParcelUuid`), stop-scan-before-connect, serialized GATT commands (`Mutex`), guaranteed `gatt.close()` cleanup to prevent GATT Error 133, and API 33+ (`TIRAMISU`) GATT overloads with API 31–32 fallbacks.
- **Test Double & Verification (`FakeBleTransport.kt` and unit/Robolectric test suites)**: Built `FakeBleTransport` supporting `serviceUuidFilter`, simulated disconnects, stubbed reads/writes, operation logging, and `0x2A37` byte notification streaming. Verified 96 tests (0 failures, 0 skipped across `BlePermissionsTest`, `BlePermissionDialogTest`, `FakeBleTransportTest`, `AndroidBleTransportTest`, and existing suites) and `./scripts/check-glossary.sh` (0 violations).
