# 31: RFC 4180 CSV & Hierarchical JSON Streaming Exporter

**What to build:** The memory-safe export serialization engine that streams single-session data directly from the encrypted SQLCipher database to an `OutputStream` in standard RFC 4180 CSV and versioned JSON formats.

**Blocked by:** 12 (Sessions & Heart Rate Samples Schema & Buffered Batch DAO)

**Status:** ready-for-agent

- [ ] Implements `SessionExporter` streaming directly via `BufferedWriter` on `Dispatchers.IO` with an $O(1)$ memory footprint.
- [ ] Writes RFC 4180 CSV with header row: `timestamp_iso,timestamp_epoch_ms,elapsed_seconds,bpm,zone_index,sensor_contact`.
- [ ] Writes versioned JSON containing `schemaVersion`, `exportedAtIso`, `session` metadata, `userProfileSnapshot`, `summaryMetrics` (including `timeInZonesMs`), and `samples` array.
- [ ] Zero temporary cache files: writes directly to the supplied `OutputStream` without creating intermediate plaintext files on disk.
- [ ] Unit tests using `ByteArrayOutputStream` assert exact CSV row formatting, numeric precision, and JSON schema conformity on multi-hour synthetic datasets.
