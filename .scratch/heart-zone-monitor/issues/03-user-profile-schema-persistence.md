# 03: User Profile Schema & Local Persistence

**What to build:** The database schema and Room Data Access Object (DAO) for the user's physiological parameters, acting as the foundation for zone computation and onboarding gating.

**Blocked by:** 01 (Encrypted Database Infrastructure & Keystore Master Key)

**Status:** ready-for-agent

- [ ] Creates Room entity `user_profile` with singleton constraint `id = 1` containing `birthYear` (INTEGER), `restingHr` (INTEGER, nullable), `customHrmax` (INTEGER, nullable), `calculationMode` (TEXT: `KARVONEN` or `PERCENT_HRMAX`), and `updatedAtEpochMs` (INTEGER).
- [ ] Table is initialized completely empty (`COUNT == 0`) on first installation.
- [ ] Provides `UserProfileDao` with reactive `Flow<UserProfile?>` and suspend functions for upserting and reading the profile.
- [ ] Automated tests verify singleton row enforcement, nullability of resting heart rate / custom HRmax, and flow emissions.
