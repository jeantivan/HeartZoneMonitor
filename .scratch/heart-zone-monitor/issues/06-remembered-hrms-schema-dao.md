# 06: Remembered HRMs Schema & DAO

**What to build:** The database schema and Room Data Access Object for persisting paired Heart Rate Monitors, strictly maintaining the invariant that exactly one device is designated as the Active HRM.

**Blocked by:** 01 (Encrypted Database Infrastructure & Keystore Master Key)

**Status:** ready-for-agent

- [ ] Creates Room entity `remembered_hrms` with columns: `macAddress` (TEXT, PK), `advertisedName` (TEXT), `customNickname` (TEXT, nullable), `lastSeenTimestamp` (INTEGER), `batteryLevel` (INTEGER, nullable), and `isActive` (INTEGER/BOOLEAN).
- [ ] Provides `RememberedHrmDao` with methods to observe the Active HRM (`Flow<RememberedHrm?>`), list all remembered sensors (`Flow<List<RememberedHrm>>`), upsert sensors, update nicknames, update battery levels, and delete sensors.
- [ ] Promotes a selected device to Active HRM in an atomic transaction: sets `isActive = 0` for all other devices and `isActive = 1` for the target sensor.
- [ ] Automated tests verify the single Active HRM invariant and transaction atomicity when switching sensors.
