# 14: Session Recording State Machine Repository

**What to build:** The singleton `SessionRepository` acting as the single source of truth for session lifecycle states (`Idle`, `Recording`, `Paused`, `Completed`, `Discarded`), Active HRM connection gating, and micro-session discarding.

**Blocked by:** 11 (GATT 0x2A37 Parser & Sequential Command Queue), 12 (Sessions & Heart Rate Samples Schema & Buffered Batch DAO), 13 (Monotonic Clock Duration Engine)

**Status:** ready-for-agent

- [ ] Implements state machine exposing `StateFlow<SessionUiState>`: `Idle`, `Recording`, `Paused(PauseReason: USER_MANUAL, BLE_CONNECTION_LOST, SENSOR_CONTACT_LOST)`, `Completed`, `Discarded`.
- [ ] Strictly disables starting a session until the Active HRM is in `Connected` state and streaming valid heart rate telemetry.
- [ ] Coordinates with `ZoneCalculator` to tag each 1 Hz sample with its active `zoneIndex` (1–5) based on the user's current profile snapshot.
- [ ] Finalizes session on "Stop & Save": computes summary metrics (`avgBpm`, `maxBpm`, `minBpm`, zone durations Z1–Z5), updates session record with `endEpochMs`, and marks status `COMPLETED`.
- [ ] Guards against micro-sessions: stopping a session with 0 samples or `Effective Duration` $< 10$ seconds prompts the user to discard the session.
- [ ] Automated tests via `FakeBleTransport` verify all valid state transitions, gating invariants, and summary calculations.
