# Research BLE Heart Rate Service specification and Android BLE lifecycle

Type: research
Status: resolved
Blocked by: none

## Question

Investigate the Bluetooth SIG Heart Rate Service specification for characteristic `0x2A37` (byte flags for 8-bit vs 16-bit BPM format, RR-intervals, sensor contact status, energy expended), and the best practices for Android 12+ BLE permissions (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`), scanning with filters, auto-reconnect logic, MTU and connection priority for the COOSPO H808S and standard HRMs.

## Answer

Comprehensive research conducted against Bluetooth SIG HRS v1.0, HRP v1.0 specifications, official Android documentation (API 31–35+), and Nordic Semiconductor engineering guides.

- **Branch**: `research/ble-protocol-and-android-lifecycle`
- **Full Findings**: [docs/research/02-ble-protocol-and-android-lifecycle.md](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/research/02-ble-protocol-and-android-lifecycle.md) (on branch `research/ble-protocol-and-android-lifecycle`)

### Key Takeaways:
1. **0x2A37 Format**: Variable length, Little-Endian. Byte 0 flags: bit 0 indicates 8-bit vs 16-bit BPM; bits 1-2 sensor contact status; bit 3 energy expended (kJ); bit 4 RR-interval stream ($1/1024$ sec units). Up to 9 RR-intervals fit inside default 23-byte MTU.
2. **COOSPO H808S Specifics**: Turns off BLE advertising when snaps are not worn on moist skin; streams at 1 Hz once CCCD (`0x2902`) is enabled; supports 8-bit BPM, RR-intervals, and contact status. Also supports Battery Service (`0x180F` / `0x2A19`) and Device Info (`0x180A`).
3. **Android 12–15 Best Practices**:
   - Manifest: `BLUETOOTH_SCAN` with `neverForLocation` (avoids requesting fine location), `BLUETOOTH_CONNECT`, `POST_NOTIFICATIONS`, and `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
   - GATT Error 133 prevention: Stop scanning before connecting; always call `gatt.close()` on disconnect; serialize all GATT commands via sequential queue (Mutex/Channel); use `autoConnect = false` for active connections.
   - Descriptor write: Use API 33 `writeDescriptor(descriptor, value)` overload when running on Tiramisu+.
