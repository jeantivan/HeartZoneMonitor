# 12: Sessions & Heart Rate Samples Schema & Buffered Batch DAO

**What to build:** The core workout persistence schema, including the `sessions` table, 1 Hz `heart_rate_samples` table with composite indexing, and a memory buffer that executes atomic batch writes every 5 seconds.

**Blocked by:** 01 (Encrypted Database Infrastructure & Keystore Master Key)

**Status:** ready-for-agent

- [ ] Creates Room entity `sessions`: `id` (TEXT UUIDv4 PK), `startEpochMs`, `endEpochMs` (nullable), `elapsedDurationMs`, `effectiveDurationMs`, `pausedDurationMs`, `status` (`RECORDING`, `PAUSED`, `COMPLETED`, `COMPLETED_UNEXPECTEDLY`, `DISCARDED`), `hrmMacAddress`, profile snapshots (`userAge`, `calculatedHrmax`, `restingHr`, `calculationMode`), and precalculated metrics (`avgBpm`, `maxBpm`, `minBpm`, `zone1DurationMs` ... `zone5DurationMs`).
- [ ] Creates Room entity `heart_rate_samples`: `id` (autoincrement PK), `sessionId` (FK `ON DELETE CASCADE`), `timestampEpochMs`, `bpm`, `zoneIndex` (1–5), `sensorContact` (boolean).
- [ ] Creates composite index: `CREATE INDEX idx_heart_rate_samples_session_ts ON heart_rate_samples(sessionId, timestampEpochMs ASC)`.
- [ ] Implements in-memory sample buffer that flushes 1 Hz samples in atomic batch transactions every 5 seconds.
- [ ] Triggers immediate flush of buffered samples upon session pause, stop, or app backgrounding.
- [ ] Automated tests verify batch insertion, cascading delete on session removal, and composite index query efficiency.
