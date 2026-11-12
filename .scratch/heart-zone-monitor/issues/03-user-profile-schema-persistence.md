# 03: User Profile Schema & Local Persistence

**What to build:** The database schema and Room Data Access Object (DAO) for the user's physiological parameters, acting as the foundation for zone computation and onboarding gating.

**Blocked by:** 01 (Encrypted Database Infrastructure & Keystore Master Key)

**Status:** resolved

- [x] Creates Room entity `user_profile` with singleton constraint `id = 1` containing `birthYear` (INTEGER), `restingHr` (INTEGER, nullable), `customHrmax` (INTEGER, nullable), `calculationMode` (TEXT: `KARVONEN` or `PERCENT_HRMAX`), and `updatedAtEpochMs` (INTEGER).
- [x] Table is initialized completely empty (`COUNT == 0`) on first installation.
- [x] Provides `UserProfileDao` with reactive `Flow<UserProfile?>` and suspend functions for upserting and reading the profile.
- [x] Automated tests verify singleton row enforcement, nullability of resting heart rate / custom HRmax, and flow emissions.

## Answer

Implemented User Profile persistence in `com.example.heartzonemonitor.data.local`:
- `UserProfileEntity` (with `UserProfile` typealias): Defines the `user_profile` Room table with primary key `id = 1` and Kotlin `init` singleton validation (`id == 1`). Contains `birthYear`, nullable `restingHr`, nullable `customHrmax`, `calculationMode` (`KARVONEN` or `PERCENT_HRMAX`), and `updatedAtEpochMs`.
- Database Triggers in `AppDatabase`: Adds idempotent SQLite triggers (`user_profile_singleton_insert_trigger` and `user_profile_singleton_update_trigger`) in Room callbacks (`onCreate` and `onOpen`) to abort any direct SQLite insert or update where `id != 1`.
- `UserProfileDao`: Room DAO providing atomic `@Upsert suspend fun upsertProfile(profile: UserProfileEntity)`, `suspend fun getProfile(): UserProfileEntity?`, `suspend fun getProfileCount(): Int`, `suspend fun deleteProfile()`, and reactive `fun getProfileFlow(): Flow<UserProfileEntity?>`.
- Initial State: Table is completely empty (`COUNT == 0`) upon creation, emitting `null` on initial flow collection.
- Tests: Thoroughly verified in `UserProfileDaoTest` covering initial empty state, upsert with nullables, upsert with custom overrides, upsert overwrite maintaining singleton count, Kotlin entity singleton validation, SQLite trigger abort on insert/update with `id != 1`, reactive flow state emissions, and continuous subscription reactivity.
