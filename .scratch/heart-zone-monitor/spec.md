# Technical Specification: HeartZoneMonitor

Status: ready-for-agent

## Problem Statement

Athletes and cardiovascular training enthusiasts need to train within precise physiological Heart Zones (Zones 1 through 5) to target specific metabolic adaptations, such as building mitochondrial density in Zone 2 or raising lactate threshold power in Zone 4. However, existing mobile heart rate tracking applications suffer from several severe problems:

1. **Privacy Invasions and Cloud Lock-in**: Most applications force users to create online accounts and upload sensitive biometric health data to remote cloud servers, where it is monetized, profiled, or vulnerable to security breaches.
2. **Inaccurate, Outdated Physiological Formulas**: Mainstream apps still default to the crude Fox formula (`220 - age`), which has no empirical regression basis and severely underpredicts maximum heart rate for adults over 40 by 10 to 16 BPM. Furthermore, they ignore resting bradycardia in conditioned athletes by failing to adapt zone boundaries to Heart Rate Reserve.
3. **Fragile BLE Connectivity and Data Corruption**: Standard Bluetooth Low Energy chest straps (such as the COOSPO H808S) go dormant when not worn or lose skin contact during intense exercise. Existing apps handle disconnects poorly: they crash, inject artificial 0-BPM samples or straight-line interpolations, or freeze the session.
4. **Monotonic Timing and Clock Drift**: When phones enter power-saving Doze mode or screen sleep in a pocket or armband, coroutine timers drift and wall-clock times are subject to NTP adjustments or timezone jumps, leading to distorted duration metrics for workout intervals.
5. **Low-Affordance, Cluttered Interfaces**: Small typography, decorative heart animations, and low-contrast UI palettes make screens impossible to read at a glance while running or cycling in direct sunlight.
6. **Data Hostage and Closed Ecosystems**: Users cannot freely extract their raw, second-by-second physiological telemetry in open, standard formats without paying subscription fees or wrestling with proprietary sync tools.

## Solution

HeartZoneMonitor is a strictly offline-first, privacy-focused Android application designed for real-time Heart Zone monitoring and high-fidelity session recording using standard Bluetooth Low Energy (BLE) Heart Rate Monitors.

- **Scientifically Grounded Zone Modeling**: Employs the peer-reviewed Tanaka HRmax formula (`208 - 0.7 * age`) as the default, integrates adaptive Karvonen Heart Rate Reserve (%HRR) when resting heart rate is provided, falls back cleanly to %HRmax when resting heart rate is omitted, and supports direct measured HRmax manual overrides.
- **Hardware-Isolated Encryption at Rest**: Encrypts all session records, 1 Hz heart rate samples, user profiles, and remembered sensors in a local Zetetic SQLCipher Room database. Database passphrases are wrapped by a 256-bit AES-GCM master key in the hardware-backed Android Keystore, requiring no remote servers or cloud accounts.
- **Resilient Monotonic Session Recording**: Operates a foreground recording service (`connectedDevice`) that calculates Effective Duration and Paused Duration strictly using monotonic hardware timers (`SystemClock.elapsedRealtime()`). Maintains zero-drift timing across backgrounding and device lock while debouncing native chronometer notifications.
- **Glanceable Athletic Monolith Interface**: Renders a dynamic Jetpack Compose screen with full-bleed animated background transitions reflecting the active Heart Zone color, massive 124sp BPM typography, an active Heart Zone progress bar, and single-metric Effective Duration tracking optimized for outdoor visibility.
- **Robust BLE Device Lifecycle**: Strictly scans and connects via the standard Bluetooth SIG Heart Rate Service (`0x180D`) and Measurement characteristic (`0x2A37`), manages Remembered HRMs with an Active HRM invariant, handles dormant sensors with clear user guidance, auto-pauses on sustained contact loss, and bridges transient drops with a 10-second grace period.
- **Biometric-Gated Sovereign Export**: Streams single-session data directly from encrypted storage to standard RFC 4180 CSV and versioned JSON files via Android Storage Access Framework (SAF), authenticated per-export with `BiometricPrompt` (or device credentials) without intermediate plaintext cache files.
- **Comprehensive Historical Analytics**: Provides instant $O(1)$ session history lookups, detailed time-in-zone distributions, native Compose Canvas time-series charting with LTTB downsampling for fluid 60/120fps scrubbing, and honest missing-data gap breaks for pauses and disconnections.

## User Stories

### First-Run Onboarding & User Profile Setup
1. As a new user opening the app for the first time, I want to be guided through a streamlined onboarding flow, so that my physiological parameters and Heart Zones are calibrated before recording.
2. As a new user on the onboarding profile screen, I want to enter my birth year, so that my Maximum Heart Rate (HRmax) can be estimated accurately using the Tanaka formula.
3. As a user entering my birth year, I want immediate visual validation preventing birth years that evaluate to ages under 10 or over 110, so that invalid input cannot corrupt zone calculations.
4. As a user with known resting bradycardia, I want to enter my resting heart rate during onboarding, so that my Heart Zones are calculated using Karvonen Heart Rate Reserve for higher physiological accuracy.
5. As a user who does not know my resting heart rate, I want to leave the resting heart rate field empty, so that the app automatically configures my Heart Zones using standard %HRmax boundaries without blocking onboarding.
6. As a user entering my resting heart rate, I want input validation ensuring values fall between 30 and 120 BPM, so that erroneous heart rate values are rejected.
7. As an athlete with laboratory stress-test results, I want an optional toggle to enter a custom measured HRmax override, so that my training zones reflect my empirical physiological ceiling rather than a statistical estimate.
8. As an athlete entering a custom measured HRmax, I want the app to validate that the custom HRmax is between 100 and 240 BPM and strictly at least 20 BPM higher than my resting heart rate, so that Heart Rate Reserve remains strictly positive.
9. As a user modifying profile inputs during onboarding, I want to see a live visual preview of my calculated HRmax and resulting 5 Heart Zone boundaries update in real time, so that I understand how my inputs shape my training targets.
10. As a user completing physiological setup, I want the app to request Android Bluetooth permissions (`BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` with `neverForLocation`), so that the app can communicate with my Heart Rate Monitor without requesting access to my physical GPS location.
11. As a user without my sensor ready during onboarding, I want to tap "Pair Later", so that I can finish onboarding and explore the app interface without being forced to pair a device immediately.
12. As a user who tapped "Pair Later", I want the live session screen to show a clear notice and disable the Start Session button, so that I cannot record empty sessions without a paired Active HRM.
13. As a user who has completed onboarding, I want subsequent app launches to open directly to the main screen, so that I do not see the onboarding flow again.

### BLE Device Discovery, Pairing & Management
14. As an athlete with a Bluetooth SIG chest strap (such as the COOSPO H808S), I want the app to scan specifically for devices advertising the standard Heart Rate Service (`0x180D`), so that unrelated Bluetooth peripherals (headphones, TVs) do not clutter the device list.
15. As an athlete scanning for sensors, I want to see discovered devices listed with advertised name, MAC address snippet, and real-time signal strength (RSSI), so that I can easily identify and select my strap.
16. As an athlete pairing a sensor, I want tapping a discovered device to immediately connect, save it as a Remembered HRM in the encrypted database, and designate it as the Active HRM, so that it becomes the primary sensor for workouts.
17. As an athlete with multiple Heart Rate Monitors, I want to view a list of all Remembered HRMs in device settings, so that I can check their connection status and battery levels.
18. As an athlete with multiple Remembered HRMs, I want to tap any remembered sensor to switch it to the Active HRM, so that I can choose which sensor to use for my next workout.
19. As an athlete managing sensors, I want to assign a custom nickname (e.g. "Chest Strap" or "Armband") to any Remembered HRM, so that I can easily distinguish between multiple devices.
20. As an athlete retiring a sensor, I want to delete a Remembered HRM from the list, so that old or discarded sensors are completely removed from the database.
21. As an athlete who deletes the Active HRM, I want the app to disconnect the GATT link, clear the active designation, and prompt me to select another Remembered HRM or scan for a new one, so that device state remains consistent.
22. As an athlete navigating to the live session screen, I want the app to automatically initiate a direct 10-second background connection to my Active HRM, so that my sensor is connected by the time I am ready to begin.
23. As an athlete whose chest strap is dormant (not yet worn on moist skin), I want a clear inline notice after the 10-second connection timeout prompting me to moisten the strap and wear it, or tap to scan, so that I know why connection did not occur.
24. As an athlete during an active recording Session, I want the app to lock out switching the Active HRM, so that baseline telemetry and single-sensor integrity cannot be corrupted mid-workout.
25. As an athlete running a workout, I want the app to periodically read the Battery Level characteristic (`0x2A19`) from the Battery Service (`0x180F`) every 10 minutes and cache it in the database, so that I am aware of low battery levels before my strap dies.

### Live Session Recording & State Machine
26. As an athlete ready to train, I want the Start Session button to remain disabled until my Active HRM is connected and streaming valid telemetry, so that I cannot accidentally initiate unlinked or blank sessions.
27. As an athlete initiating a workout, I want the session recording state machine to transition from `Idle` to `Recording`, establishing a unique UUIDv4 session record in the encrypted database.
28. As an athlete recording a workout, I want the Android Foreground Service to start immediately with `foregroundServiceType="connectedDevice"`, so that recording continues uninterrupted when the app is minimized or the screen turns off.
29. As an athlete exercising with the phone locked in an armband or pocket, I want the recording service to acquire a `PARTIAL_WAKE_LOCK` exclusively while in `Recording` state, so that OS Doze mode does not throttle 1 Hz Heart Rate Sample processing or database writes.
30. As an athlete recording a workout, I want my Effective Duration to accumulate using monotonic hardware clock intervals (`SystemClock.elapsedRealtime()`), so that my active workout duration remains completely drift-free regardless of wall-clock changes, NTP syncs, or coroutine delays.
31. As an athlete needing a break, I want to tap the Pause button to transition the session to `Paused(USER_MANUAL)`, so that Effective Duration stops accumulating and Paused Duration begins accumulating.
32. As an athlete on pause, I want the `PARTIAL_WAKE_LOCK` to be released immediately while paused, so that battery power is conserved during prolonged breaks.
33. As an athlete resuming after a pause, I want to tap Resume to return to `Recording` state, so that Effective Duration resumes accumulation seamlessly.
34. As an athlete whose chest strap loses skin contact during exercise, I want the app to detect the sensor contact flag in characteristic `0x2A37` and show an immediate warning banner, so that I can adjust the strap position.
35. As an athlete whose chest strap loses skin contact for more than 5 seconds, I want the session to automatically transition to `Paused(SENSOR_CONTACT_LOST)`, so that invalid empty intervals do not dilute my workout metrics.
36. As an athlete whose chest strap restores skin contact after an auto-pause, I want the session to automatically resume `Recording` without requiring manual touch interaction, so that I do not have to touch my phone while running.
37. As an athlete whose chest strap disconnects due to radio interference or range, I want a 10-second grace period where the app attempts immediate reconnection with exponential backoff, so that brief radio hiccups do not interrupt my workout.
38. As an athlete whose strap reconnects within the 10-second grace period, I want recording to continue seamlessly without changing the session state or injecting artificial samples, so that data continuity is maintained.
39. As an athlete whose strap remains disconnected after the 10-second grace period, I want the session to automatically transition to `Paused(BLE_CONNECTION_LOST)` and post an alert notification, so that I am notified of the link failure.
40. As an athlete whose strap reconnects after a BLE disconnect auto-pause, I want the session to automatically resume `Recording`, so that workout recording resumes hands-free once the sensor is back in range.
41. As an athlete finishing a workout, I want to tap Stop & Save to finalize the session, calculate summary metrics (average BPM, maximum BPM, minimum BPM, and time spent in each of the 5 Heart Zones), stop the Foreground Service, and commit the session record.
42. As an athlete who accidentally taps Start and immediately stops within 10 seconds or with 0 samples, I want the app to prompt me to discard the micro-session, so that accidental taps do not clutter my session history.
43. As an athlete who wants to abort a session, I want a Discard action that removes buffered and uncommitted data and cleans up state, so that aborted attempts are not saved.

### Live Session UI & Dynamic Visual Feedback
44. As an athlete glancing at my mounted phone while sprinting or cycling outdoors, I want the entire screen background to dynamically illuminate in the vibrant color of my active Heart Zone, so that I know my intensity bracket without reading text.
45. As an athlete transitioning between Heart Zones, I want the background color to animate smoothly over 500 milliseconds, so that zone changes are visually seamless and non-jarring.
46. As an athlete checking my pulse during exercise, I want my heart rate displayed in massive 124sp bold typography in the center of the screen, so that it is instantly legible from a distance.
47. As an athlete tracking my intensity, I want a clean `BPM | ZONE X` badge directly beneath my heart rate without distracting heartbeat animations or raw percentage math, so that my focus remains purely on my workout.
48. As an athlete monitoring my relative workout intensity, I want a 5-segment horizontal progress bar representing Zones 1 through 5, highlighting the active zone and dimming past and future zones, so that I can visualize where I sit within the intensity spectrum.
49. As an athlete tracking workout time, I want a dedicated timer card displaying exclusively my Effective Duration in 52sp bold monospace font, so that I see only active training time without secondary clock clutter.
50. As an athlete operating the phone with sweaty hands or gloves, I want large 56dp high-affordance Pause/Resume and Stop & Save buttons at the bottom of the screen, so that I can reliably control recording on the move.
51. As an athlete switching apps or locking the screen, I want a persistent Android notification displaying current BPM, Heart Zone name, and a native chronometer ticking active duration, so that I can monitor my session from the lock screen or status bar.
52. As an athlete with the screen locked, I want interactive Pause/Resume and Stop actions directly on the persistent notification, so that I can manage my workout without unlocking the phone.

### Data Persistence, Security & Crash Recovery
53. As a privacy-conscious user, I want all my biometric data, user profile details, and session history stored in a local Zetetic SQLCipher database encrypted with AES-256, so that raw data extracted from device storage remains completely indecipherable.
54. As a user, I want the database encryption key derived from a random 256-bit passphrase wrapped by an AES-256-GCM master key in the Android Keystore, so that unattended background recording works without prompting for credentials on every database write.
55. As an athlete running long sessions, I want 1 Hz Heart Rate Samples buffered in memory and flushed to the database in 5-second atomic batches (as well as immediately on pause or stop), so that flash storage I/O and battery consumption are minimized.
56. As an athlete whose phone battery dies or whose OS terminates the app unexpectedly during a workout, I want the app to reconcile unclosed sessions on the next startup, setting their status to `COMPLETED_UNEXPECTEDLY` and calculating summary metrics from the saved samples, so that my recorded workout is never lost.
57. As a user, I want database schema updates to execute strict, non-destructive Room migrations, so that future app upgrades never erase historical session records.

### Session History & Detailed Analytics
58. As an athlete reviewing past performance, I want to browse a chronologically ordered list of past sessions, displaying session date, start time, Effective Duration, average BPM, maximum BPM, and a proportional mini 5-zone color distribution bar, so that I can review my training history at a glance.
59. As an athlete inspecting a specific session, I want to tap a session card to open a unified vertical analytics view, showing glanceable hero metrics (Effective Duration, Elapsed Duration, Paused Duration, Average BPM, Maximum BPM, Minimum BPM, and sensor metadata), so that I can evaluate the workout comprehensively.
60. As an athlete analyzing workout intensity, I want to see a detailed Heart Zone breakdown displaying both a proportional horizontal bar and a breakdown table listing duration and percentage of Effective Duration spent in each zone (Z1 to Z5), so that I know whether I hit my training targets.
61. As an athlete reviewing workout pacing, I want to view an interactive full-width time-series line chart plotting 1 Hz heart rate against session time on a custom Compose Canvas with faint horizontal zone threshold lines, so that I can examine heart rate trends and cardiac drift.
62. As an athlete with a multi-hour session (over 1,000 samples), I want the chart to apply Largest-Triangle-Three-Buckets (LTTB) downsampling down to 600–800 points, so that chart rendering and scrolling remain fluid at 60/120fps without flattening critical peaks or valleys.
63. As an athlete inspecting heart rate at a specific point in time, I want to drag my finger across the chart to scrub through samples with a vertical crosshair line and dynamic tooltip showing elapsed time, exact BPM, and active Heart Zone badge, so that I can pinpoint exact moments in my workout.
64. As an athlete whose session had pauses or connection drops, I want the time-series chart to render breaks (`Path.moveTo()`) for sample time gaps exceeding 3 seconds rather than drawing artificial straight lines, so that missing intervals are honestly represented.
65. As an athlete reviewing an older session, I want to see the physiological profile snapshot (age, resting HR, calculated HRmax, calculation mode) frozen at the time the session began, so that subsequent profile edits never alter historical zone calculations.
66. As an athlete cleaning up unwanted data, I want to delete a historical session with a double-confirmation prompt, executing a cascading delete of all associated samples from the database, so that my history remains tidy.

### Sovereign Biometric-Gated Data Export
67. As a user owning my biometric data, I want to export any completed session directly to standard RFC 4180 CSV or versioned JSON from the session detail screen, so that I can analyze my data in external software (Excel, Python, GoldenCheetah) without cloud lock-in.
68. As a privacy-conscious user, I want every export request to trigger an Android `BiometricPrompt` requiring biometric authentication (fingerprint/face) or device PIN/pattern/password, so that unauthorized persons holding my unlocked phone cannot export my sensitive health records.
69. As a user on a device with no lock screen security or biometrics configured, I want the app to block export attempts and display an educational dialog directing me to set up device security, so that sensitive biometric data is never exported without ownership verification.
70. As a user exporting data, I want the Storage Access Framework (SAF) file picker to appear only after biometric authentication succeeds, so that failed or cancelled authentication attempts never leave empty 0-byte files in my storage.
71. As a user saving an export, I want the file picker pre-populated with a standardized filename containing the session start timestamp (e.g. `hzm_session_20261111_170000.csv`), so that exported files are organized and easily identifiable.
72. As a user exporting a session, I want the file data streamed directly from the encrypted SQLCipher database into the SAF output stream on a background IO dispatcher, so that no plaintext files are ever written to temporary cache storage.
73. As a data analyst exporting to CSV, I want a 6-column tabular format (`timestamp_iso`, `timestamp_epoch_ms`, `elapsed_seconds`, `bpm`, `zone_index`, `sensor_contact`) with RFC 4180 compliance, so that time-series analysis in spreadsheets or data science environments is immediate and effortless.
74. As a developer exporting to JSON, I want a structured schema grouping schema version, export timestamp, session metadata, frozen user profile snapshot, summary metrics, and the full array of 1 Hz samples, so that third-party systems can ingest the entire session fidelity losslessly.

## Implementation Decisions

### 1. Physiological Zone Calculation & Hierarchy
- **Tanaka HRmax Formula**: Implemented as the default formula:
  $$\text{HR}_{\max} = 208 - (0.7 \times \text{age})$$
  Selected over the Fox formula (`220 - age`) due to its empirical regression validation across 18,712 subjects and correction of age-related underprediction for individuals over 40.
- **Adaptive Karvonen / Heart Rate Reserve (%HRR)**: Used when `restingHr` is populated:
  $$\text{Target HR} = \text{HR}_{\text{rest}} + \% \times (\text{HR}_{\max} - \text{HR}_{\text{rest}})$$
  Accurately matches %VO2Reserve 1:1, adapting zone boundaries to athletic bradycardia.
- **%HRmax Fallback**: Used when `restingHr` is omitted:
  $$\text{Target HR} = \% \times \text{HR}_{\max}$$
- **Manual Measured HRmax Override**: Allows athletes with empirical stress tests to supply a custom HRmax, overriding formula prediction entirely while maintaining Karvonen or %HRmax zone threshold scaling.
- **Standard 5-Zone Percentage Boundaries**:
  - **Zone 1 (Active Recovery)**: 50% – 60%
  - **Zone 2 (Aerobic Base / FatMax)**: 60% – 70% (bounded by LT1 / VT1)
  - **Zone 3 (Tempo / Aerobic Endurance)**: 70% – 80% (metabolic transition)
  - **Zone 4 (Lactate Threshold)**: 80% – 90% (centered at LT2 / VT2 / MLSS)
  - **Zone 5 (VO2max / Anaerobic)**: 90% – 100%
- **Heart Zone Visual Identity**:
  - Zone 1: Cool Slate (`#64748B`)
  - Zone 2: Vibrant Blue (`#3B82F6`)
  - Zone 3: Emerald Green (`#10B981`)
  - Zone 4: Vivid Amber (`#F59E0B`)
  - Zone 5: High-Intensity Crimson (`#EF4444`)

### 2. Encrypted Database Architecture & Key Management (ADR-0001)
- **SQLCipher Integration**: The Room database is encrypted using Zetetic SQLCipher via `SupportFactory(passphraseBytes)`.
- **Android Keystore Key-Wrapping**:
  - A 256-bit AES master key is generated in the hardware-backed `AndroidKeyStore` (`KEY_ALGORITHM_AES`, `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`).
  - On first run, a cryptographically secure 256-bit random passphrase is generated via `SecureRandom` and encrypted with the Keystore master key. The resulting ciphertext and 12-byte IV are stored in private app preferences.
  - At startup, the passphrase is decrypted in ephemeral memory and supplied to SQLCipher. Raw key bytes are never written to disk.
  - This architecture permits unattended background recording and automatic reconnections by the Foreground Service without requiring user unlock on each database write.
- **Database Schema**:
  - `user_profile`: Singleton table (`id = 1`) storing `birthYear`, `restingHr` (nullable), `customHrmax` (nullable), `calculationMode`, and `updatedAtEpochMs`. An empty table triggers onboarding.
  - `remembered_hrms`: Stores `macAddress` (PK), `advertisedName`, `customNickname` (nullable), `lastSeenTimestamp`, `batteryLevel` (nullable), and `isActive` (boolean). Exactly one record has `isActive = 1`.
  - `sessions`: Stores `id` (UUIDv4 PK), `startEpochMs`, `endEpochMs` (nullable), `elapsedDurationMs`, `effectiveDurationMs`, `pausedDurationMs`, `status` (`RECORDING`, `PAUSED`, `COMPLETED`, `COMPLETED_UNEXPECTEDLY`, `DISCARDED`), `hrmMacAddress`, profile snapshots (`userAge`, `calculatedHrmax`, `restingHr`, `calculationMode`), and precalculated metrics (`avgBpm`, `maxBpm`, `minBpm`, `zone1DurationMs` ... `zone5DurationMs`).
  - `heart_rate_samples`: Stores `id` (autoincrement PK), `sessionId` (FK with `ON DELETE CASCADE`), `timestampEpochMs`, `bpm`, `zoneIndex` (1–5), and `sensorContact` (boolean).
  - Composite Index: `CREATE INDEX idx_heart_rate_samples_session_ts ON heart_rate_samples(sessionId, timestampEpochMs ASC)` guarantees zero-scan time-series streaming.
- **Database Engine PRAGMAs**:
  - `PRAGMA foreign_keys = ON;` enforced on connection open via `RoomDatabase.Callback.onOpen`.
  - `PRAGMA journal_mode = WAL;` enabled for concurrent non-blocking reads by UI and writes by recording service.
  - `PRAGMA cipher_integrity_check` executed on database open.
  - `fallbackToDestructiveMigration` is strictly forbidden. Explicit migration scripts are required.
- **Write Pipeline & Battery Conservation**:
  - Foreground Service buffers 1 Hz samples in memory and executes atomic batch inserts every 5 seconds.
  - Immediate flush occurs on pause, completion, or app backgrounding, bounding maximum power-loss data exposure to 5 seconds.
- **Ungraceful Termination Recovery**:
  - On database initialization, any session with `status` in `RECORDING` or `PAUSED` is reconciled: `endEpochMs` is set to the latest sample timestamp, summary metrics are computed from existing samples, and status is marked `COMPLETED_UNEXPECTEDLY`.

### 3. Bluetooth Low Energy Architecture & Device Lifecycle
- **Standard Bluetooth Protocol**:
  - Strictly targets Bluetooth SIG Heart Rate Service (UUID `0x180D`), Heart Rate Measurement characteristic (UUID `0x2A37`), Client Characteristic Configuration Descriptor (CCCD UUID `0x2902`), and Battery Service (UUID `0x180F` / characteristic `0x2A19`).
- **GATT 0x2A37 Payload Unpacking**:
  - Byte 0 flags: bit 0 determines 8-bit vs 16-bit BPM; bits 1–2 indicate sensor contact status; bit 3 indicates energy expended presence; bit 4 indicates RR-interval stream presence.
  - Contact status is evaluated continuously to detect when chest strap loses physical contact with skin.
- **Sequential GATT Queue**:
  - All GATT operations (`discoverServices`, `setCharacteristicNotification`, `writeDescriptor`, `readCharacteristic`) are strictly serialized through a Coroutine Mutex/Channel queue to eliminate Android GATT Error 133 collisions.
- **Android Permissions Architecture**:
  - Manifest declares `BLUETOOTH_SCAN` with `android:usesPermissionFlags="neverForLocation"` (exempting the app from runtime fine location requirements), `BLUETOOTH_CONNECT`, `POST_NOTIFICATIONS`, and `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
- **Connection Lifecycle & Grace Period**:
  - Connection machine: `Disconnected`, `Scanning`, `Connecting`, `Connected`, `Reconnecting`.
  - Auto-connect: Direct non-blocking background connection (`autoConnect = false`) with 10-second timeout to Active HRM on entering session screen.
  - Disconnect handling: If BLE drops during `Recording`, connection enters `Reconnecting` with exponential backoff (0s, 2s, 5s, 10s up to 60s max). A 10-second grace period starts: if reconnected within 10s, recording continues transparently; if link remains down past 10s, session auto-pauses (`BLE_CONNECTION_LOST`) and alerts the user.
- **Sensor Contact Loss Auto-Pause**:
  - If characteristic `0x2A37` reports sensor contact lost for > 5 seconds while BLE is connected, session auto-pauses (`SENSOR_CONTACT_LOST`). Restoring contact automatically resumes `Recording`.
- **Honest Telemetry Gaps**:
  - Missing sample intervals are not filled with synthetic 0-BPM or interpolated points; true timestamps in `heart_rate_samples` accurately preserve physical signal gaps.
- **Active HRM Lockout**:
  - Modifying or switching the Active HRM is locked out while a session is in `Recording` or `Paused` state.

### 4. Monotonic Session Recording & Foreground Service (ADR-0002)
- **Monotonic Timing Separation**:
  - `startEpochMs` is recorded once at session start via `System.currentTimeMillis()` for calendar display and export metadata.
  - All duration math is computed strictly using `SystemClock.elapsedRealtime()`:
    $$\text{Effective Duration} = \text{accumulatedEffectiveMs} + (\text{if Recording then } \text{elapsedRealtime}() - \text{activeIntervalStartRealtime} \text{ else } 0)$$
    $$\text{Elapsed Duration} = \text{Effective Duration} + \text{Paused Duration}$$
  - Completely immune to wall-clock NTP adjustments, manual time changes, or coroutine delay drift.
- **Foreground Service Lifecycle**:
  - `RecordingService` starts as a Foreground Service (`foregroundServiceType="connectedDevice"`) only upon transition to `Recording`.
  - Runs continuously through `Recording` and `Paused` states; stops immediately (`stopForeground(STOP_FOREGROUND_REMOVE)` and `stopSelf()`) on `Completed` or `Discarded`.
  - `onStartCommand` returns `START_NOT_STICKY` to avoid zombie restarts without BLE context.
- **Power Management**:
  - `PowerManager.PARTIAL_WAKE_LOCK` is held exclusively during `Recording` state, released immediately when `Paused`.
- **Repository Single Source of Truth**:
  - A singleton `SessionRepository` owns the recording state machine and exposes `StateFlow<SessionUiState>`.
  - Both `RecordingService` and UI ViewModels observe `SessionRepository`, eliminating fragile `ServiceConnection` Binder IPC and surviving Activity recreation across screen rotations.
- **Interactive Notification UX**:
  - Ongoing, low-importance persistent notification displaying current BPM and Heart Zone name.
  - Native duration rendered via `NotificationCompat.Builder.setUsesChronometer(true)` seeded with monotonic baseline, ticking seconds natively in system UI with zero CPU/IPC overhead.
  - Updates debounced to zone transitions, $\ge 2$ BPM shifts, or at minimum every 5 seconds (aligned with database batch write).
  - Notification actions for direct Pause/Resume and Stop & Save.
- **Micro-Session Guard**:
  - Stopping a session with 0 samples or `Effective Duration` < 10 seconds prompts the user to discard the micro-session.

### 5. Athletic Monolith Live UI
- **Dynamic Background Illumination**:
  - Screen background animates smoothly (`animateColorAsState` over 500ms with `FastOutSlowInEasing`) into the saturated color of the active Heart Zone, transitioning from the vibrant zone color to an 82% alpha depth for extreme outdoor sunlight contrast.
- **Glanceable Telemetry**:
  - Centered massive 124sp bold BPM typography (`letterSpacing = -4sp`).
  - Translucent badge: `BPM | ZONE X` directly beneath the BPM reading. Decorative heart icons omitted.
- **5-Segment Zone Progress Bar**:
  - 5 horizontal segments representing Z1 through Z5. Active segment is solid white; past segments 55% white; future segments translucent black (`alpha = 0.25f`).
- **Single-Metric Duration**:
  - Session duration card renders exclusively the active `Effective Duration` in 52sp bold monospace (`HH:mm:ss` or `mm:ss`). Elapsed and paused durations are omitted from the live canvas to eliminate cognitive clutter.
- **High-Affordance Controls**:
  - Bottom 56dp action buttons: Pause/Resume toggle and Stop & Save with high-contrast borders and translucent dark containers (`Color.Black.copy(alpha = 0.32f)`).

### 6. Session History & Canvas Analytics
- **$O(1)$ Historical Listing**:
  - Reads precalculated summary columns directly from `sessions` table (`effectiveDurationMs`, `avgBpm`, `maxBpm`, `zone1DurationMs` ... `zone5DurationMs`) without sample queries or table joins.
  - Renders athletic cards with formatted date, key metrics, ungraceful completion warning badge if applicable, and a proportional mini 5-zone color distribution bar.
- **Unified Vertical Detail View**:
  - 1. Header Card (timestamps, status badge, HRM info).
  - 2. Hero Telemetry Row (4-column: Effective Duration, Avg BPM, Max BPM, Min BPM).
  - 3. Zone Distribution Card (proportional bar and table of durations and percentages for Z1–Z5).
  - 4. Custom Compose Canvas Time-Series Chart (faint horizontal zone thresholds, 1Hz BPM curve).
  - 5. Durations & Physiological Snapshot Card (Elapsed Duration, Paused Duration, frozen profile parameters).
  - 6. Action Bar (Export CSV/JSON, Delete Session with double confirmation).
- **LTTB Chart Downsampling**:
  - For sessions with > 1,000 raw samples, the Largest-Triangle-Three-Buckets (LTTB) downsampling algorithm reduces data points to ~600–800 visual points, preserving true peaks and troughs while guaranteeing 60/120fps fluid scrolling.
- **Interactive Touch Scrubbing**:
  - Horizontal drag gesture across the chart renders a vertical crosshair indicator and floating tooltip displaying relative elapsed time (`MM:SS`), BPM, and active Heart Zone badge with its corresponding background color.
- **Honest Missing-Data Rendering**:
  - Consecutive samples with a timestamp delta > 3 seconds execute `Path.moveTo()` instead of `Path.lineTo()`, rendering distinct visual breaks across pauses, BLE disconnections, or contact loss intervals.

### 7. Sovereign Biometric Export & Streaming Pipeline
- **Single-Session Export**: Accessible from the Session Detail view for completed sessions.
- **Biometric Security Policy**:
  - Gated by `androidx.biometric.BiometricPrompt` with `BIOMETRIC_STRONG or BIOMETRIC_WEAK or DEVICE_CREDENTIAL`.
  - Strict per-export authentication with no cached auth grace window.
  - Fail-closed: If `BiometricManager.canAuthenticate(...)` returns `BIOMETRIC_ERROR_NONE_ENROLLED` (no device lock screen or biometrics), export is blocked with an explanatory dialog directing the user to Android Settings.
- **Zero-Cache SAF Delivery**:
  - Biometric authentication precedes SAF file picker launch (`ActivityResultContracts.CreateDocument`).
  - Pre-populated filename: `hzm_session_<YYYYMMDD>_<HHMMSS>.csv` or `.json`.
  - Telemetry streams directly from SQLCipher Room database to `contentResolver.openOutputStream(uri)` wrapped in a `BufferedWriter` on `Dispatchers.IO`. Zero temporary plaintext files are created in cache or storage.
- **RFC 4180 CSV Schema**:
  - Header: `timestamp_iso,timestamp_epoch_ms,elapsed_seconds,bpm,zone_index,sensor_contact`
  - 1 sample per line.
- **Hierarchical JSON Schema**:
  - Self-describing schema version, export timestamp, session object, user profile snapshot, summary metrics, and array of sample objects.

### 8. First-Run Onboarding & Profile Management
- **Root Navigation Gate**:
  - App navigation checks `UserProfileDao.getProfile()`. If empty, the root navigation graph locks start destination to `OnboardingGraph`.
  - Back-press exits the app; main destinations are structurally unreachable without an initialized profile.
- **2-Step Flow**:
  - Step 1: Birth Year (required, age 10–110), Resting HR (optional, 30–120 bpm), Custom HRmax (optional, 100–240 bpm and $> \text{resting HR} + 20$). Live Tanaka zone preview updates as user types.
  - Step 2: Bluetooth permissions request and HRM scan. Option to "Pair Later".
- **Session Screen Gating**:
  - If user selected "Pair Later", the session screen displays a prominent card: *"No Heart Rate Monitor paired. [Pair HRM in Settings]"* and Start Session remains disabled until an HRM is paired and set as Active.
- **Profile Snapshot Invariant**:
  - Profile settings can be modified post-onboarding in Settings. Updated profile parameters apply to all future sessions, but historical session records retain their frozen snapshots.

## Testing Decisions

### What Makes a Good Test
Tests must verify external module behavior, invariants, state transitions, and observable outputs rather than internal implementation details. Good tests do not mock internal classes or assert private method invocations; they exercise modules through their public interfaces and verify resulting state flows, database records, and emitted streams.

### Testing Seam Architecture
To maximize testing depth, locality, and confidence while keeping the test surface minimal, the system introduces a **single primary external seam**:

1. **The Hardware / BLE Transport Seam (`BleTransport` / `BleDeviceClient`)**:
   - Instead of mocking Android's complex `BluetoothAdapter`, `BluetoothGatt`, and `BluetoothGattCallback` across multiple layers, the entire Bluetooth OS interaction is encapsulated behind a single interface (`BleTransport`).
   - In production, `AndroidBleTransport` interacts with the Android Bluetooth stack.
   - In tests, a `FakeBleTransport` simulates:
     - Discovered HRM advertisements (UUID `0x180D`, RSSI, names)
     - GATT connection, disconnection, and reconnection sequences
     - Streaming variable-length Little-Endian `0x2A37` byte notifications (8-bit/16-bit BPM, contact flags, RR intervals) at arbitrary frequencies (1 Hz, burst, delayed)
     - Battery level characteristic reads (`0x2A19`)
   - **Leverage of this Seam**: Because this seam sits at the very edge of the application, tests cross it to exercise the entire stack as real production code:
     - The `BleManager` and sequential GATT command queue
     - The `SessionRepository` recording state machine (`Idle` $\rightarrow$ `Recording` $\rightarrow$ `Paused` $\rightarrow$ `Completed`)
     - Monotonic duration accumulation (`Effective Duration`, `Paused Duration`)
     - 10-second disconnection grace period and auto-pause behavior
     - 5-second sensor contact loss auto-pause and auto-resume
     - In-memory 1 Hz sample buffering and batch writes to the Room database
     - Ungraceful session crash recovery and summary metric calculations

### Secondary Pure Functional Seams
2. **`ZoneCalculator` (Pure Domain Function)**:
   - A pure, dependency-free domain module calculating Tanaka HRmax, Karvonen target HR boundaries, %HRmax boundaries, and active zone index mapping.
   - Tested exhaustively with parameterized unit tests across age ranges (10 to 110), resting heart rates, and stress-tested custom overrides without Android runtime dependencies.

3. **`SessionExporter` (Stream Writer Seam)**:
   - Accepts an `OutputStream` and writes CSV or JSON data directly.
   - Tested by passing a `ByteArrayOutputStream` and asserting exact RFC 4180 compliance, header formatting, ISO-8601 timestamps, and JSON structural validity without requiring Android storage framework mocks.

4. **`BiometricAuthenticator` (Security Contract Seam)**:
   - Encapsulates the Android `BiometricPrompt` interaction.
   - In tests, fake implementations simulate `Success`, `Failure`, and `BIOMETRIC_ERROR_NONE_ENROLLED` (unsecured device), verifying that exports fail-closed and never initiate file writing when authentication fails.

5. **SQLCipher Room Database (Persistence Seam)**:
   - Tested in JVM unit tests (via Robolectric / SQLite) using an in-memory database configuration.
   - Validates schema foreign keys, cascading deletes of samples, composite index query performance, and crash reconciliation (`COMPLETED_UNEXPECTEDLY`).

### Prior Art
- Live session UI prototype: [`LiveSessionPrototype.kt`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/app/src/main/java/com/example/heartzonemonitor/prototype/LiveSessionPrototype.kt)
- Standard unit tests in `app/src/test/java/com/example/heartzonemonitor/`

## Out of Scope

The following capabilities are explicitly out of scope for HeartZoneMonitor:
- **Cloud Accounts and Remote Servers**: No user authentication, cloud sync, remote backup, or telemetry servers (strictly offline-first).
- **Location, GPS, and Mapping**: No GPS tracking, route mapping, elevation, pace, or distance metrics.
- **Calorie and VO2max Estimations**: No caloric expenditure modeling or algorithmic VO2max scoring (pure heart rate zone monitoring).
- **Proprietary Sensor SDKs & ANT+**: No support for proprietary smartwatch protocols (Apple Watch, WearOS proprietary APIs, Garmin ANT+). Exclusively standard Bluetooth SIG Heart Rate Service (`0x180D`).
- **Music and Media Controls**: No integrated audio playback, playlist sync, or headphone volume integration.
- **Audio / Haptic Zone Transition Alerts**: Vibrations or sound cues when crossing zone boundaries are deferred to a future iteration.

## Further Notes

- **Target Android Version**: Min SDK 26 (Android 8.0 Oreo), Target SDK 35/37 (Android 15+), using Android 12+ (API 31+) Bluetooth permissions architecture with `neverForLocation`.
- **Target Hardware Verification**: Reference testing hardware is the COOSPO H808S BLE heart rate chest strap.
- **Design Archetype**: Athletic Monolith, prioritizing high-contrast, distraction-free visual feedback and immediate sunlight glanceability.
- **ADR References**:
  - [ADR-0001: Encrypted Room Database with Keystore Key-Wrapping](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/adr/0001-encrypted-room-database-with-keystore-key-wrapping.md)
  - [ADR-0002: Monotonic Clock Timing and Foreground Service Architecture](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/adr/0002-monotonic-clock-timing-and-foreground-service-architecture.md)
