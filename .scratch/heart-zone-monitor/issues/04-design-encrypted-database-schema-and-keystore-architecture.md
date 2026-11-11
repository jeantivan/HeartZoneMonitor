# Design encrypted database schema and keystore architecture

Type: grilling
Status: resolved
Blocked by: none

## Question

How should the Room database schema for Sessions and 1Hz HeartRateSamples be designed, how will the 256-bit AES master key be generated/retrieved from Android Keystore, and how will migrations and lifecycle initialization of SQLCipher be managed securely offline?

## Answer

All database schema, SQLCipher encryption, and Android Keystore architectural decisions have been resolved through interactive grilling and documented in ADR-0001:

### 1. `sessions` Table Schema & Aggregations
- **Primary Key**: `id` (TEXT, UUIDv4 string)
- **Temporal & Duration Fields**:
  - `startEpochMs` (INTEGER, Unix epoch ms)
  - `endEpochMs` (INTEGER, Nullable until finalized)
  - `elapsedDurationMs` (INTEGER, wall-clock duration)
  - `effectiveDurationMs` (INTEGER, active recording duration)
  - `pausedDurationMs` (INTEGER, accumulated pause duration)
- **Lifecycle State**:
  - `status` (TEXT: `RECORDING`, `PAUSED`, `COMPLETED`, `COMPLETED_UNEXPECTEDLY`, `DISCARDED`)
- **Telemetry & Hardware Linkage**:
  - `hrmMacAddress` (TEXT, foreign reference to `remembered_hrms.macAddress`)
- **Physiological Profile Snapshots**:
  - `userAge` (INTEGER)
  - `calculatedHrmax` (INTEGER, formula or manual override)
  - `restingHr` (INTEGER)
  - `calculationMode` (TEXT, e.g. `KARVONEN` or `PERCENT_HRMAX`)
- **Precalculated Summary Metrics**:
  - `avgBpm` (INTEGER)
  - `maxBpm` (INTEGER)
  - `minBpm` (INTEGER)
  - `zone1DurationMs` ... `zone5DurationMs` (INTEGER, time spent in each zone)
- *Rationale*: Precalculated columns yield $O(1)$ query speeds for historical session listings. Snapshotting user profile metrics preserves the historical accuracy of zone boundaries across future user profile edits.

### 2. `heart_rate_samples` Table Schema & Indexing
- **Columns**:
  - `id` (INTEGER PRIMARY KEY AUTOINCREMENT)
  - `sessionId` (TEXT, NOT NULL, Foreign Key to `sessions(id)` ON DELETE CASCADE)
  - `timestampEpochMs` (INTEGER, Unix epoch ms)
  - `bpm` (INTEGER, 0–255)
  - `zoneIndex` (INTEGER, 1–5, Heart Zone active at sample time)
  - `sensorContact` (INTEGER / BOOLEAN, 0 or 1, parsed from BLE `0x2A37` flags)
- **Index**:
  - `CREATE INDEX idx_heart_rate_samples_session_ts ON heart_rate_samples(sessionId, timestampEpochMs ASC)`
- *Rationale*: Storing `zoneIndex` and `sensorContact` directly eliminates redundant recalculations during graph rendering. Composite indexing guarantees zero table scans when streaming samples to Compose Canvas.

### 3. `user_profile` Table Schema & Lifecycle
- **Columns**:
  - `id` (INTEGER PRIMARY KEY, single-row constraint `CHECK (id = 1)`)
  - `birthYear` (INTEGER)
  - `restingHr` (INTEGER)
  - `customHrmax` (INTEGER, Nullable)
  - `calculationMode` (TEXT, default `KARVONEN`)
  - `updatedAtEpochMs` (INTEGER)
- **Onboarding Boundary**:
  - The table is initialized completely empty (`COUNT == 0`). An empty profile row serves as the architectural trigger to present the onboarding UX, preventing recording with unverified baselines.

### 4. Android Keystore Key-Wrapping & SQLCipher Passphrase Architecture
- **Hardware Enclave Master Key**:
  - Generated inside `AndroidKeyStore` with `KeyProperties.KEY_ALGORITHM_AES`, `PURPOSE_ENCRYPT | PURPOSE_DECRYPT`, `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`, 256-bit key size.
- **Passphrase Generation & Persistence**:
  - On first run, a cryptographically secure 256-bit random passphrase (32 bytes via `SecureRandom`) is generated.
  - The passphrase is encrypted with the Keystore master key using AES-GCM; ciphertext and 12-byte initialization vector (IV) are persisted in private app storage.
- **SQLCipher Initialization**:
  - At startup, the Keystore master key decrypts the passphrase in ephemeral memory and passes it to Zetetic's `SupportFactory(passphraseBytes)`. Decrypted key bytes are never written to disk.
  - Enables unattended Foreground Service recording and background reconnects without requiring biometric prompt on every DB access.

### 5. Write Pipeline & Battery Conservation
- **5-Second Buffered Writes**:
  - The Foreground Service buffers 1 Hz `HeartRateSample` objects in an in-memory batch.
  - Samples are written atomically every 5 seconds.
  - Immediate flush is triggered on session pause, session completion, or app backgrounding. Maximum sudden power-loss window is bounded to 5 seconds.

### 6. Incomplete Session Crash Recovery
- **Post-Mortem Reconciliation**:
  - During Room initialization, the database scans for sessions with `status` in `RECORDING` or `PAUSED`.
  - The latest `HeartRateSample` timestamp provides `endEpochMs`.
  - Summary metrics (`avgBpm`, `maxBpm`, `minBpm`, zone durations) are computed from existing samples.
  - Session status updates to `COMPLETED_UNEXPECTEDLY`, preserving all recorded data without manual user intervention.

### 7. SQLite PRAGMAs & Integrity Policy
- **Connection Configuration**:
  - `PRAGMA foreign_keys = ON;` enforced on connection open via `RoomDatabase.Callback.onOpen`.
  - `PRAGMA journal_mode = WAL;` enabled for concurrent non-blocking reads by UI and writes by recording service.
- **Integrity & Migrations**:
  - `PRAGMA cipher_integrity_check` runs on database opening.
  - `fallbackToDestructiveMigration` is strictly forbidden. Explicit Room migration scripts with automated testing are enforced.
  - If decryption or integrity verification fails, a fail-safe diagnostic error screen halts execution. Automatic database deletion or recreation is forbidden to protect raw user data.
