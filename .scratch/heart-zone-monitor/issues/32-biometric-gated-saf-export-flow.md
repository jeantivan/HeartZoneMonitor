# 32: Biometric-Gated SAF Export Flow Integration

**What to build:** The complete export user flow on the Session Detail view: gates export behind biometric authentication first, triggers Android's Storage Access Framework file picker second, and streams data into the selected destination.

**Blocked by:** 25 (Session Detail View: Telemetry & Zone Distribution), 30 (Biometric Security Verification), 31 (RFC 4180 CSV & Hierarchical JSON Streaming Exporter)

**Status:** ready-for-agent

- [ ] Adds `[Export CSV]` and `[Export JSON]` action buttons to the bottom action bar of the Session Detail view.
- [ ] Tapping an export button presents `BiometricPrompt` immediately.
- [ ] Authentication occurs first: only upon `Success` is the SAF `ActivityResultContracts.CreateDocument` file picker launched (`ACTION_CREATE_DOCUMENT`), preventing empty 0-byte orphan files on cancelled or failed authentication.
- [ ] Pre-populates default filename formatted as `hzm_session_<YYYYMMDD>_<HHMMSS>.csv` or `.json` in the user's local timezone.
- [ ] Streams data directly from database to `contentResolver.openOutputStream(uri)` on a background IO dispatcher.
- [ ] Displays a non-blocking progress indicator while writing, and displays a success or error `Snackbar` on completion.
