# 07: Hardware Seam `BleTransport` & Bluetooth Permissions

**What to build:** The primary external testing seam abstracting Android's Bluetooth LE system APIs, paired with modern Android 12+ runtime permissions handling.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] Declares Android manifest permissions: `BLUETOOTH_SCAN` with `android:usesPermissionFlags="neverForLocation"`, `BLUETOOTH_CONNECT`, and `POST_NOTIFICATIONS`.
- [ ] Implements runtime permission request handling and educational explanation dialog if permissions are denied.
- [ ] Defines the `BleTransport` interface representing BLE scanner, GATT connection, characteristic notification subscriptions, and write/read operations.
- [ ] Implements `AndroidBleTransport` wrapping standard Android `BluetoothManager`, `BluetoothAdapter`, and `BluetoothGatt`.
- [ ] Implements `FakeBleTransport` test double allowing simulated BLE scans, connection drops, RSSI values, and synthetic byte streaming for automated testing.
