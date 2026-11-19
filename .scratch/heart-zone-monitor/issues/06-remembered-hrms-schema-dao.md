# 06: Remembered HRMs Schema & DAO

**What to build:** The database schema and Room Data Access Object for persisting paired Heart Rate Monitors, strictly maintaining the invariant that exactly one device is designated as the Active HRM.

**Blocked by:** 01 (Encrypted Database Infrastructure & Keystore Master Key)

**Status:** resolved

- [x] Creates Room entity `remembered_hrms` with columns: `macAddress` (TEXT, PK), `advertisedName` (TEXT), `customNickname` (TEXT, nullable), `lastSeenTimestamp` (INTEGER), `batteryLevel` (INTEGER, nullable), and `isActive` (INTEGER/BOOLEAN).
- [x] Provides `RememberedHrmDao` with methods to observe the Active HRM (`Flow<RememberedHrm?>`), list all remembered sensors (`Flow<List<RememberedHrm>>`), upsert sensors, update nicknames, update battery levels, and delete sensors.
- [x] Promotes a selected device to Active HRM in an atomic transaction: sets `isActive = 0` for all other devices and `isActive = 1` for the target sensor.
- [x] Automated tests verify the single Active HRM invariant and transaction atomicity when switching sensors.

## Answer

Implemented the Remembered HRMs Room schema, single Active HRM invariant enforcement, and DAO in `com.example.heartzonemonitor.data.local`:
- `RememberedHrmEntity` (with `RememberedHrm` typealias): Defines the `remembered_hrms` Room table keyed by `macAddress` (`TEXT` PK) with `advertisedName` (`TEXT`), `customNickname` (`TEXT?`), `lastSeenTimestamp` (`INTEGER`), `batteryLevel` (`INTEGER?`), and `isActive` (`BOOLEAN`/`INTEGER`), plus validation for non-blank MAC address and 0..100 battery bounds and `displayName()` helper.
- `RememberedHrmDao`: Provides reactive observation of the Active HRM (`observeActiveHrm()` / `getActiveHrmFlow(): Flow<RememberedHrm?>`), all remembered HRMs ordered active-first (`observeAllHrms()` / `getAllHrmsFlow(): Flow<List<RememberedHrm>>`), transactional `upsertHrm` (automatically demoting other rows when upserting an active HRM), atomic `@Transaction` promotion (`promoteToActiveHrm` / `setActiveHrm` clearing `isActive = 0` on all devices and setting `isActive = 1` on the target sensor with rollback on unknown MAC), `updateNickname`, `updateBatteryLevel`, `updateLastSeenTimestamp`, and deletion methods (`deleteHrm`, `deleteHrmByMacAddress`, `deleteAllHrms`).
- `AppDatabase` (`version = 2` with explicit `MIGRATION_1_2`): Registers `RememberedHrmEntity`, exposes `rememberedHrmDao()`, provides non-destructive `MIGRATION_1_2` per ADR-0001, and installs SQLite `AFTER INSERT` / `AFTER UPDATE` triggers (`remembered_hrms_single_active_insert_trigger` and `remembered_hrms_single_active_update_trigger`) to guarantee the single Active HRM invariant even on raw SQLite writes.
- `FakeRememberedHrmDao`: Deterministic in-memory test double for downstream UI and repository tests.
- Automated Tests (`RememberedHrmDaoTest` and `AppDatabaseTest`): 12 tests covering schema persistence, nullable columns, single Active HRM invariant on upsert and direct SQLite triggers, atomic transaction promotion and rollback on unknown sensor MAC, deterministic `Channel`-collected reactive flow transitions without intermediate `null` states, nickname/battery updates, deletion, and v1-to-v2 Room migration.
