## Destination

A complete technical specification (`spec.md`) defining the architecture, encrypted Room database with Android Keystore, standard BLE communication protocol (0x180D/0x2A37), scientific Heart Zone calculation models, foreground recording service, dynamic Compose UI, and biometric export flow—fully resolved for immediate implementation.

## Notes

- Domain: Android BLE Heart Rate Monitoring and zone recording (`GLOSSARY.md`, `docs/agents/domain.md`).
- Target stack: Kotlin, Jetpack Compose (Material 3), Coroutines/Flow, Room + SQLCipher + Android Keystore, AndroidX BiometricPrompt.
- Standard protocol: Bluetooth SIG Heart Rate Service (UUID `0x180D`) and Heart Rate Measurement characteristic (UUID `0x2A37`).
- Offline-first: No remote cloud backends; all storage is strictly local and encrypted.
- Testing hardware: COOSPO H808S BLE heart rate chest strap.
- Skills to consult: `/grilling`, `/domain-modeling`, `/research`, `/prototype`, `/tdd`.

## Decisions so far

<!-- the index: one line per closed ticket, enough to judge relevance, then zoom the link for the detail the ticket holds -->

- [Research HRmax formulas and Heart Zone percentage boundaries](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/.scratch/heart-zone-monitor/issues/01-research-hrmax-and-zone-formulas.md): Tanaka formula (208 - 0.7*age) adopted over Fox; adaptive Karvonen HRR with %HRmax fallback; 5-zone thresholds (50, 60, 70, 80, 90, 100%) grounded in LT1/LT2 thresholds; user override hierarchy specified.
- [Research BLE Heart Rate Service specification and Android BLE lifecycle](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/.scratch/heart-zone-monitor/issues/02-research-ble-protocol-and-android-lifecycle.md): GATT 0x2A37 unpacked, 0x2902 CCCD streaming verified, API 31+ permissions configured without location, GATT 133 prevention rules established.
- [Design BLE device management and connection flow](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/.scratch/heart-zone-monitor/issues/03-design-ble-device-management-and-connection-flow.md): Encrypted remembered HRMs table with Active HRM invariant; 10s non-blocking background auto-connect with dormant strap prompt; strict 0x180D scan filter; 5-state connection machine with 10s grace period and exponential backoff; sequential GATT queue with battery service; mid-session switch lockout; 5s contact-loss auto-pause; strict timestamp gaps for lost samples.
- [Design encrypted database schema and keystore architecture](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/.scratch/heart-zone-monitor/issues/04-design-encrypted-database-schema-and-keystore-architecture.md): SQLCipher with Android Keystore AES-256-GCM passphrase wrapping (ADR-0001); UUID sessions with precalculated metrics & profile snapshots; 1Hz samples with zoneIndex, sensorContact & composite index; singleton user_profile with onboarding boundary; 5s write buffering; crash reconciliation to COMPLETED_UNEXPECTEDLY; WAL & foreign keys ON.
- [Design session recording state machine and foreground service](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/.scratch/heart-zone-monitor/issues/05-design-session-recording-state-machine-and-foreground-service.md): Explicit state machine with strict Active HRM connection gating; drift-free monotonic clock (SystemClock.elapsedRealtime) interval duration math (ADR-0002); foregroundServiceType="connectedDevice" active only during sessions; singleton SessionRepository single source of truth; debounced interactive notifications with native chronometer; PARTIAL_WAKE_LOCK during recording; auto-resume for connection/contact loss; START_NOT_STICKY; micro-session discard guard (< 10s).
- [Prototype live session screen with dynamic zone color transitions](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/.scratch/heart-zone-monitor/issues/06-prototype-live-session-screen.md): Athletic Monolith layout with full-bleed luminous zone background (Option C palette); 124sp giant BPM with "BPM | ZONE X" badge; heart icon removed; pure Effective Duration timer only (52sp monospace); 5-segment Z1-Z5 bar; 56dp bottom pause/resume and stop controls.
- [Design export format and biometric authentication flow](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/.scratch/heart-zone-monitor/issues/07-design-export-format-and-biometric-authentication-flow.md): Single-session export gated by per-export BiometricPrompt (BIOMETRIC_STRONG | BIOMETRIC_WEAK | DEVICE_CREDENTIAL, fail-closed on unsecured devices); auth-first then SAF CreateDocument (hzm_session_YYYYMMDD_HHMMSS.<ext>) with zero temporary cache files; 6-column RFC 4180 1Hz CSV and versioned hierarchical JSON streamed via BufferedWriter on Dispatchers.IO.
- [Design session history and detail analytics view](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/.scratch/heart-zone-monitor/issues/08-design-session-history-and-detail-analytics-view.md): O(1) history card with effective duration, avg/max BPM, mini zone bar; unified vertical detail view with 5-tier zone distribution and hero telemetry; native Compose Canvas chart with LTTB downsampling (>1000 pts) for 60/120fps; interactive touch scrubbing crosshair tooltip; explicit Path.moveTo gaps (>3s) for disconnects and pauses.
- [Design first-run onboarding and profile setup flow](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/.scratch/heart-zone-monitor/issues/09-design-first-run-onboarding-and-profile-setup-flow.md): Root navigation gate on empty user_profile table; 2-step flow (Profile with live zone preview -> BLE HRM pairing with API 31+ permissions); physiological validation (age 10-110, optional resting HR 30-120 with %HRmax fallback, HRR > 20); skippable HRM pairing ("Pair Later") with disabled recording until Active HRM paired; post-onboarding profile editing in Settings with immutable past session snapshots.

## Not yet specified

- **Graph Sampling & Performance Optimization**: Whether downsampling (e.g. LTTB algorithm) is needed in Compose Canvas for sessions exceeding 3 hours.
- **Audio / Haptic Zone Transition Alerts**: Vibrations or sound cues when crossing into higher or lower zones during active workouts.
- **Database Backup & Disaster Recovery**: Potential encrypted file export/import mechanism for backup restoration.

## Out of scope

- Cloud backend, remote user authentication, or server synchronization (strictly offline).
- GPS, location tracking, pace, distance, or route mapping.
- Calorie burn estimations and VO2 max algorithms (pure heart rate zone monitoring).
- Proprietary fitness watch SDKs or ANT+ communication.
