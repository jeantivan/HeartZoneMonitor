# Design export format and biometric authentication flow

Type: grilling
Status: resolved
Blocked by: 04, 05

## Question

What exact schema and fields will be included in the CSV and JSON session exports, and how will `BiometricPrompt` with device credential fallback (PIN/pattern) guard the export action via Android Storage Access Framework?

## Answer

All decisions regarding session export formats, schemas, biometric authentication policy, and Storage Access Framework (SAF) streaming have been resolved through interactive grilling:

### 1. Export Granularity & Scope
- **Single-Session Export Only**: Triggered directly from the Session Detail screen for a specific `Session` (`COMPLETED` or `COMPLETED_UNEXPECTEDLY`).
- The user chooses between two target formats: **CSV** (tabular time-series optimized for spreadsheet/script ingestion) or **JSON** (complete hierarchical archive of metadata, profile snapshot, summary metrics, and 1Hz samples).

### 2. Biometric Authentication & Security Policy
- **Allowed Authenticators**: Guarded by `androidx.biometric.BiometricPrompt` configured with `BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL` (supporting fingerprint, face unlock, or fallback to device PIN/pattern/password).
- **Strict Per-Export Authentication**: Every export initiation requires fresh authentication via `BiometricPrompt`; no in-memory grace window is kept across separate export actions.
- **Fail-Closed on Unsecured Devices**: Because session data is encrypted at rest via SQLCipher to protect sensitive physiological telemetry, exporting it to plaintext on disk requires verified device ownership. If `BiometricManager.canAuthenticate(...)` returns `BIOMETRIC_ERROR_NONE_ENROLLED` (no lock screen security or biometrics configured on the device), the export is **blocked** and the user is shown an explanatory dialog with a shortcut to configure device security in Android Settings.

### 3. Interaction Sequence & Zero-Cache Delivery via SAF
- **Biometric First $\rightarrow$ SAF File Picker Second $\rightarrow$ Direct Stream**:
  1. User taps `Export CSV` or `Export JSON` on the Session Detail screen.
  2. `BiometricPrompt` is presented immediately.
  3. Only upon `onAuthenticationSucceeded` does the app launch Android's Storage Access Framework picker via `ActivityResultContracts.CreateDocument` (`Intent.ACTION_CREATE_DOCUMENT` with MIME type `text/csv` or `application/json`).
  4. This ordering guarantees that failed or cancelled authentication attempts never leave orphaned 0-byte stub files in the user's storage.
- **Default Filename Convention**: Pre-populated using the session's `startEpochMs` formatted in the device's local timezone:
  - `hzm_session_<YYYYMMDD>_<HHMMSS>.csv`
  - `hzm_session_<YYYYMMDD>_<HHMMSS>.json`
- **Zero Temporary Plaintext Files**: Data is streamed directly from the SQLCipher database into `contentResolver.openOutputStream(uri)`. No plaintext files are ever written to `cacheDir` or internal storage.

### 4. Exact CSV Export Schema (RFC 4180)
- Pure 1Hz tabular time-series with a single header row and 6 analytical columns:
  ```csv
  timestamp_iso,timestamp_epoch_ms,elapsed_seconds,bpm,zone_index,sensor_contact
  2026-11-11T17:00:01.250Z,1794416401250,1.2,142,3,true
  ```
- **Field Definitions**:
  - `timestamp_iso`: ISO-8601 UTC string (`YYYY-MM-DDTHH:mm:ss.SSSZ`).
  - `timestamp_epoch_ms`: Exact Unix epoch milliseconds (`heart_rate_samples.timestampEpochMs`).
  - `elapsed_seconds`: Relative time in seconds from `session.startEpochMs` (`(timestampEpochMs - startEpochMs) / 1000.0`), enabling immediate X-axis plotting in Excel, Pandas, or R.
  - `bpm`: Integer heart rate (`0–255`).
  - `zone_index`: Integer Heart Zone active at sample time (`1–5`).
  - `sensor_contact`: Boolean literal (`true` or `false`).

### 5. Exact JSON Export Schema
- Self-describing, versioned root envelope grouping metadata according to `GLOSSARY.md`:
  ```json
  {
    "schemaVersion": 1,
    "exportedAtIso": "2026-11-11T18:30:00.000Z",
    "session": {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "status": "COMPLETED",
      "startIso": "2026-11-11T17:00:00.000Z",
      "endIso": "2026-11-11T17:45:00.000Z",
      "startEpochMs": 1794416400000,
      "endEpochMs": 1794419100000,
      "elapsedDurationMs": 2700000,
      "effectiveDurationMs": 2640000,
      "pausedDurationMs": 60000,
      "hrmMacAddress": "AA:BB:CC:DD:EE:FF",
      "hrmName": "COOSPO H808S"
    },
    "userProfileSnapshot": {
      "userAge": 32,
      "restingHr": 55,
      "calculatedHrmax": 186,
      "calculationMode": "KARVONEN"
    },
    "summaryMetrics": {
      "avgBpm": 148,
      "maxBpm": 179,
      "minBpm": 92,
      "timeInZonesMs": {
        "zone1": 120000,
        "zone2": 600000,
        "zone3": 1200000,
        "zone4": 600000,
        "zone5": 120000
      }
    },
    "samples": [
      {
        "timestampEpochMs": 1794416401000,
        "bpm": 92,
        "zoneIndex": 1,
        "sensorContact": true
      }
    ]
  }
  ```

### 6. Memory-Safe Streaming & Error Handling
- **Streaming I/O on `Dispatchers.IO`**: Export serialization writes directly to a `BufferedWriter` wrapping the SAF `OutputStream` (using line-by-line emission for CSV and streaming `JsonWriter` / Kotlinx Serialization stream for JSON), maintaining an $O(1)$ memory footprint even for multi-hour sessions (~10,800+ samples).
- **UI Feedback**: Shows a non-blocking progress indicator while writing and displays a completion or I/O error `Snackbar` once the stream closes.
