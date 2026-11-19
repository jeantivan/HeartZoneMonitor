# 23: Crash Recovery & Ungraceful Termination Reconciliation

**What to build:** The automated crash recovery routine that reconciles incomplete sessions left in `RECORDING` or `PAUSED` state due to device shutdown, dead battery, or OS process death, salvaging all recorded samples into a completed state without user intervention.

**Blocked by:** 12 (Sessions & Heart Rate Samples Schema & Buffered Batch DAO)

**Status:** resolved

- [x] On database initialization during app startup, queries the `sessions` table for any row where `status` is `RECORDING` or `PAUSED`.
- [x] For each incomplete session found, sets `endEpochMs` to the timestamp of the latest recorded `heart_rate_sample` associated with that session.
- [x] Computes summary metrics (`avgBpm`, `maxBpm`, `minBpm`, zone durations Z1–Z5) from existing persisted samples.
- [x] Sets session status to `COMPLETED_UNEXPECTEDLY`.
- [x] Automated tests simulate abrupt process termination during active recording, re-open the database, and verify status reconciliation and correct metric computation.

## Answer

Implemented automatic ungraceful termination crash recovery in `com.example.heartzonemonitor.data.local`:
- `SessionCrashReconciler`: Exposes two entry points sharing identical summary-metric reconciliation logic:
  - `reconcile(db: SupportSQLiteDatabase): Int`: Executes directly on `SupportSQLiteDatabase` inside `AppDatabase`'s `onOpen` callback without re-entering Room DAOs synchronously. Verifies `sessions` and `heart_rate_samples` exist in `sqlite_master`, short-circuits on the fast path when no `RECORDING` or `PAUSED` rows exist, and reconciles all incomplete Sessions atomically in a single SQLite transaction.
  - `suspend fun reconcile(sessionDao: SessionDao, sampleDao: HeartRateSampleDao): List<SessionEntity>`: Queries incomplete Sessions via `SessionDao.getSessionsByStatuses(...)`, computes identical reconciled `SessionEntity` rows, persists updates via `SessionDao.updateSession(...)`, and returns the reconciled list.
- Metric & duration reconciliation: Orders persisted Heart Rate Samples by `timestampEpochMs ASC, id ASC`, sets `endEpochMs` to the latest persisted sample timestamp (or `session.endEpochMs ?: session.startEpochMs` with zeroed metrics when 0 samples exist), computes rounded `avgBpm`, `maxBpm`, `minBpm`, gap-aware Heart Zone durations Z1–Z5 (exact delta up to 3,000 ms, nominal 1,000 ms across >3,000 ms gaps and for the final sample, 0 ms for non-positive deltas), preserves `elapsedDurationMs == effectiveDurationMs + pausedDurationMs` and non-negative invariants, transitions incomplete Sessions to `COMPLETED_UNEXPECTEDLY`, leaves `COMPLETED`, `COMPLETED_UNEXPECTEDLY`, and `DISCARDED` Sessions untouched, and remains idempotent across repeated opens.
- `AppDatabase`: Invokes `SessionCrashReconciler.reconcile(db)` in `createDatabaseCallback(...).onOpen(db)` after `PRAGMA foreign_keys = ON`, trigger creation, and `PRAGMA cipher_integrity_check` succeed, and adds `internal fun buildPersistentForTesting(context, databaseName)` using explicit migrations and the production `onOpen` callback (no destructive fallback).
- `SessionCrashReconcilerTest`: 7 Robolectric/unit tests (180 total tests passing across `:app:testDebugUnitTest`) exercising real on-disk database close/re-open after abrupt termination during `RECORDING` and `PAUSED`, multi-zone gap-aware metric computation, terminal-status preservation, 0-sample reconciliation, multi-session atomic reconciliation + idempotency, pre-migration table absence guard, and parity between the `SupportSQLiteDatabase` and DAO overloads.
