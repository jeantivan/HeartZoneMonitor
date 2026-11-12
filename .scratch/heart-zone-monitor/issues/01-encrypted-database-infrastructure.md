# 01: Encrypted Database Infrastructure & Keystore Master Key (ADR-0001)

**What to build:** Initialize the application's secure, offline-first local persistence layer using Zetetic SQLCipher and Android Keystore. The database encryption key is derived from a 256-bit passphrase encrypted by a hardware-backed AES-256-GCM master key in Android Keystore, allowing unattended background recording without manual password entry while keeping raw data undecipherable if extracted from device storage.

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] Generates or retrieves an AES-256-GCM key inside `AndroidKeyStore` with `PURPOSE_ENCRYPT | PURPOSE_DECRYPT` and `ENCRYPTION_PADDING_NONE`.
- [x] On first run, generates a cryptographically secure 256-bit random passphrase (`SecureRandom`), encrypts it with the Keystore master key, and persists ciphertext and 12-byte IV in private app storage.
- [x] Decrypts the passphrase in ephemeral memory at startup and initializes Zetetic SQLCipher via `SupportFactory`.
- [x] Enforces `PRAGMA foreign_keys = ON;` and `PRAGMA journal_mode = WAL;` on database open.
- [x] Executes `PRAGMA cipher_integrity_check` on open, halting with a diagnostic failure state if integrity fails.
- [x] Strictly forbids destructive migrations (`fallbackToDestructiveMigration` not used).

## Answer

Implemented encrypted database infrastructure in `com.example.heartzonemonitor.core.security` and `com.example.heartzonemonitor.data.local.AppDatabase` per ADR-0001:
- `KeystoreManager`: Manages 256-bit AES-GCM master key in `AndroidKeyStore` with `PURPOSE_ENCRYPT | PURPOSE_DECRYPT` and `ENCRYPTION_PADDING_NONE`.
- `EncryptedPassphraseStorage`: Generates random 256-bit passphrase on first run, encrypts it with Keystore master key, and persists IV + ciphertext in private storage.
- `AppDatabase`: Configures Zetetic SQLCipher via `SupportFactory(passphrase)`, enforces `foreign_keys = ON` and WAL journal mode, runs `PRAGMA cipher_integrity_check;` on database open, and strictly omits destructive migrations.
- Tested and verified via `KeystoreManagerTest` and `AppDatabaseTest`.
