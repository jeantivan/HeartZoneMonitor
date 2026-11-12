# 01: Encrypted Database Infrastructure & Keystore Master Key (ADR-0001)

**What to build:** Initialize the application's secure, offline-first local persistence layer using Zetetic SQLCipher and Android Keystore. The database encryption key is derived from a 256-bit passphrase encrypted by a hardware-backed AES-256-GCM master key in Android Keystore, allowing unattended background recording without manual password entry while keeping raw data undecipherable if extracted from device storage.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] Generates or retrieves an AES-256-GCM key inside `AndroidKeyStore` with `PURPOSE_ENCRYPT | PURPOSE_DECRYPT` and `ENCRYPTION_PADDING_NONE`.
- [ ] On first run, generates a cryptographically secure 256-bit random passphrase (`SecureRandom`), encrypts it with the Keystore master key, and persists ciphertext and 12-byte IV in private app storage.
- [ ] Decrypts the passphrase in ephemeral memory at startup and initializes Zetetic SQLCipher via `SupportFactory`.
- [ ] Enforces `PRAGMA foreign_keys = ON;` and `PRAGMA journal_mode = WAL;` on database open.
- [ ] Executes `PRAGMA cipher_integrity_check` on open, halting with a diagnostic failure state if integrity fails.
- [ ] Strictly forbids destructive migrations (`fallbackToDestructiveMigration` not used).
