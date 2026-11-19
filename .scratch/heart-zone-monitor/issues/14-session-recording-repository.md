# 14: Session Recording State Machine Repository

**What to build:** The singleton `SessionRepository` acting as the single source of truth for session lifecycle states (`Idle`, `Recording`, `Paused`, `Completed`, `Discarded`), Active HRM connection gating, and micro-session discarding.

**Blocked by:** 11 (GATT 0x2A37 Parser & Sequential Command Queue), 12 (Sessions & Heart Rate Samples Schema & Buffered Batch DAO), 13 (Monotonic Clock Duration Engine)

**Status:** resolved

- [x] Implements state machine exposing `StateFlow<SessionUiState>`: `Idle`, `Recording`, `Paused(PauseReason: USER_MANUAL, BLE_CONNECTION_LOST, SENSOR_CONTACT_LOST)`, `Completed`, `Discarded`.
- [x] Strictly disables starting a session until the Active HRM is in `Connected` state and streaming valid heart rate telemetry.
- [x] Coordinates with `ZoneCalculator` to tag each 1 Hz sample with its active `zoneIndex` (1–5) based on the user's current profile snapshot.
- [x] Finalizes session on "Stop & Save": computes summary metrics (`avgBpm`, `maxBpm`, `minBpm`, zone durations Z1–Z5), updates session record with `endEpochMs`, and marks status `COMPLETED`.
- [x] Guards against micro-sessions: stopping a session with 0 samples or `Effective Duration` $< 10$ seconds prompts the user to discard the session.
- [x] Automated tests via `FakeBleTransport` verify all valid state transitions, gating invariants, and summary calculations.

## Answer

Implemented the Session recording state machine repository, pure Kotlin summary metrics calculator, and sample buffer discard support:
- `SessionMetricsCalculator` (`app/src/main/java/com/example/heartzonemonitor/domain/calculator/SessionMetricsCalculator.kt`): Pure Kotlin domain module (zero `android.*`/`androidx.*` imports) defining `SampleMetricPoint` and `SessionSummaryMetrics` (`avgBpm`, `maxBpm`, `minBpm`, `zone1DurationMs..zone5DurationMs`, `totalZoneDurationMs`, `latestSampleEpochMs`) with stable chronological sorting and gap-aware Heart Zone duration attribution (exact delta up to 3,000 ms contiguous threshold; nominal 1,000 ms for pause/disconnect gaps > 3,000 ms and the trailing sample; 0 ms for duplicate timestamps).
- `HeartRateSampleBuffer.discard()` (`app/src/main/java/com/example/heartzonemonitor/data/local/HeartRateSampleBuffer.kt`): Cancels any active periodic flush job and clears the in-memory buffer under `flushMutex` + `bufferMutex` without persisting buffered Heart Rate Samples to `HeartRateSampleDao`.
- `SessionUiState` & outcomes (`app/src/main/java/com/example/heartzonemonitor/data/session/SessionUiState.kt`): Defines `PauseReason` (`USER_MANUAL`, `BLE_CONNECTION_LOST`, `SENSOR_CONTACT_LOST`), sealed `SessionUiState` (`Idle`, `Recording`, `Paused`, `Completed`, `Discarded`), `StartSessionRejectionReason`, `StartSessionResult`, and `StopSessionOutcome`.
- `SessionRepository` (`app/src/main/java/com/example/heartzonemonitor/data/session/SessionRepository.kt`): Singleton-capable (`getInstance`/`getInstanceOrNull`) and constructor-injectable single source of truth exposing `StateFlow<SessionUiState>`, `isSessionActive`, and `isSessionActiveFlow`. Enforces strict Active HRM connection and live valid telemetry gating (`bpm in 1..255` and `sensorContact == true`, immediately cleared on disconnect or Active HRM switch), freezes the User Profile snapshot via `ZoneCalculator` at Session start, clamps below-Z1 BPM to `HeartZone.ZONE_1` (`zoneIndex = 1`) on 1 Hz `0x2A37` notifications, coordinates `MonotonicDurationEngine` and `HeartRateSampleBuffer` across pause/resume/background/stop/discard lifecycles, guards micro-sessions (`sampleCount == 0` or `Effective Duration < 10_000 ms`) with `confirmSaveMicroSession()`/`dismissMicroSessionPrompt()`/`discardSession()`, finalizes summary metrics into `SessionStatus.COMPLETED`, and cancels internal coroutines cleanly via `close()`.
- Automated verification:
  - `SessionMetricsCalculatorTest` (6 tests), `HeartRateSampleBufferTest` (8 tests including 2 new `discard()` tests), and `SessionRepositoryTest` (7 tests via `FakeBleTransport`, `HrmGattClient`, fake DAOs, and `FakeSessionClock` using deterministic coroutines with zero `Thread.sleep`).
  - `./scripts/check-glossary.sh` (0 violations), `./scripts/check-standards.sh` (0 violations), and `./gradlew :app:testDebugUnitTest` (188/188 passed).
