# Design BLE device management and connection flow

Type: grilling
Status: resolved
Blocked by: none

## Question

How should device pairing, discovery, remembering devices, switching the active device, and connection retries be structured in the UI and repository? (e.g., remembered devices list, auto-connect timeout before opening scanner, handling multiple nearby devices, and device nickname/renaming).

## Answer

All 10 architectural and UX decisions for BLE device management and connection flow have been resolved through interactive design grilling:

### 1. Remembered HRM Persistence & Active Selection
- Remembered HRMs are stored in an encrypted database table (`remembered_hrms`):
  - `macAddress` (TEXT, Primary Key)
  - `advertisedName` (TEXT, e.g. "COOSPO H808S-01928")
  - `customNickname` (TEXT, Nullable, e.g. "My Chest Strap")
  - `lastSeenTimestamp` (INTEGER, Unix epoch ms)
  - `batteryLevel` (INTEGER, Nullable, 0–100%)
  - `isActive` (INTEGER / BOOLEAN, exactly one device has `isActive = 1`)
- Tapping another Remembered HRM in the device manager immediately promotes it to `Active HRM`.

### 2. Auto-Connect Strategy on App Launch & Session Prep
- If an Active HRM is present in the database, the app automatically attempts a direct background connection (`autoConnect = false`) with a 10-second timeout upon entering the Session screen.
- A non-blocking ambient status banner indicates *"Connecting to [Nickname/Name]..."*.
- If the 10-second timeout expires (standard for chest straps like COOSPO H808S which stay dormant and do not advertise until worn on moist skin), the banner transitions to an inline prompt: *"Sensor not found. Ensure strap is moist and worn, or tap to Scan."*

### 3. Discovery Scanner Presentation & UUID Filtering
- Scanner strictly applies an OS hardware-level filter: `ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID_HEART_RATE_SERVICE))`. Non-HRM BLE peripherals are completely ignored.
- The scanner bottom sheet renders discovered devices with device name, RSSI signal strength bars, and MAC address snippet.
- Single-tap selection immediately stops scanning, initiates connection, registers the device in `remembered_hrms`, and sets `isActive = true`.

### 4. Connection State Machine
- Lifecycle states: `Disconnected`, `Scanning`, `Connecting`, `Connected`, `Reconnecting`.
- Exposed as a reactive `StateFlow<ConnectionState>` from the BLE repository to the UI and Foreground Service.

### 5. Transient Disconnection Recovery & Session Grace Period
- If the BLE link drops during an active recording Session:
  - State immediately transitions to `Reconnecting`.
  - Reconnection retry loop executes with exponential backoff (0s, 2s, 5s, 10s up to 60s max).
  - A **10-second grace period** begins. If the HRM reconnects within 10 seconds, recording resumes transparently without altering Session state.
  - If the link is not recovered within 10 seconds, the active Session automatically transitions to `Paused` (accumulating `Paused Duration`), and an alert notification is dispatched.

### 6. Device Management UI & Deletion Lifecycle
- A dedicated "Heart Rate Monitors" management screen/modal is accessible from Settings and the Session screen.
- Features: viewing battery level and signal status, editing custom nicknames, and "Forget Device".
- If the Active HRM is deleted: its `isActive` flag is cleared, any active GATT connection is closed, and the user is prompted to select another Remembered HRM or scan for a new one.

### 7. Mid-Session Active HRM Switching Lockout
- Switching the Active HRM is locked out while a Session is actively `Recording` or `Paused`.
- Preserves single-sensor identity, consistent baseline telemetry, and database integrity. The user must complete or discard the active Session before changing sensors.

### 8. OS Bluetooth System Events & Permissions
- If Bluetooth is OFF at launch/scan: Prompt the user to enable it via `BluetoothAdapter.ACTION_REQUEST_ENABLE`.
- If Bluetooth is turned OFF during an active Session: Immediately trigger the disconnection handler, auto-pause the Session, and display a persistent banner to turn Bluetooth back on.
- If runtime permissions (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`) are denied: Display an educational sheet directing the user to App Settings.

### 9. Sequential GATT Queue & Battery Service Integration
- To avoid GATT Error 133 collisions, all GATT operations are strictly serialized via a Coroutine Mutex/Channel sequential queue:
  1. `gatt.discoverServices()`
  2. Locate HRS (`0x180D`) and characteristic `0x2A37`
  3. Subscribe to CCCD descriptor `0x2902` using API-level write
  4. Upon `onDescriptorWrite` confirmation, read Battery Level characteristic `0x2A19` (Battery Service `0x180F`) if advertised, caching battery percentage in the database
  5. Battery Level is refreshed every 10 minutes during active connection.

### 10. Data Integrity: Missing Sample Gaps & Sensor Contact Loss
- **No fake samples**: During transient signal loss (< 10s), no interpolated or 0-BPM samples are generated. Absolute timestamps in `HeartRateSample` accurately record the gap.
- **Physical Contact Detection**: Flags in `0x2A37` (bits 1–2) are parsed for skin contact status. If contact is lost while BLE remains connected, an immediate banner displays *"Sensor contact lost — check strap positioning"*. If uncorrected within 5 seconds, the Session auto-pauses until contact is restored.
