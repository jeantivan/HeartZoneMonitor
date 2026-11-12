# 23: Crash Recovery & Ungraceful Termination Reconciliation

**What to build:** The automated crash recovery routine that reconciles incomplete sessions left in `RECORDING` or `PAUSED` state due to device shutdown, dead battery, or OS process death, salvaging all recorded samples into a completed state without user intervention.

**Blocked by:** 12 (Sessions & Heart Rate Samples Schema & Buffered Batch DAO)

**Status:** ready-for-agent

- [ ] On database initialization during app startup, queries the `sessions` table for any row where `status` is `RECORDING` or `PAUSED`.
- [ ] For each incomplete session found, sets `endEpochMs` to the timestamp of the latest recorded `heart_rate_sample` associated with that session.
- [ ] Computes summary metrics (`avgBpm`, `maxBpm`, `minBpm`, zone durations Z1–Z5) from existing persisted samples.
- [ ] Sets session status to `COMPLETED_UNEXPECTEDLY`.
- [ ] Automated tests simulate abrupt process termination during active recording, re-open the database, and verify status reconciliation and correct metric computation.
