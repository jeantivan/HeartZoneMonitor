# 08: BLE Device Discovery & Scan Filtering

**What to build:** The Bluetooth LE discovery service that scans exclusively for devices advertising the standard Bluetooth SIG Heart Rate Service (`0x180D`), filtering out unrelated peripherals.

**Blocked by:** 07 (Hardware Seam `BleTransport` & Bluetooth Permissions)

**Status:** resolved

- [x] Applies OS hardware-level scan filter: `ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID_HEART_RATE_SERVICE))` (`0x180D`).
- [x] Emits discovered devices as a reactive stream with advertised device name, MAC address, and real-time signal strength (RSSI).
- [x] Automatically stops scanning prior to initiating a connection to avoid GATT connection collisions and GATT 133 errors.
- [x] Includes timeout handling to stop scanning after 30 seconds to conserve battery if no device is selected.
- [x] Automated tests via `FakeBleTransport` verify non-HRM devices are filtered out and discovery streams emit properly.
