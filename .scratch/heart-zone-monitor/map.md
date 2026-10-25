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

## Not yet specified

- **First-run Onboarding & Profile Setup UX**: Exact step sequence to capture age/gender/resting HR vs single modal dialog (depends on 01).
- **Graph Sampling & Performance Optimization**: Whether downsampling (e.g. LTTB algorithm) is needed in Compose Canvas for sessions exceeding 3 hours.
- **Audio / Haptic Zone Transition Alerts**: Vibrations or sound cues when crossing into higher or lower zones during active workouts.
- **Database Backup & Disaster Recovery**: Potential encrypted file export/import mechanism for backup restoration.

## Out of scope

- Cloud backend, remote user authentication, or server synchronization (strictly offline).
- GPS, location tracking, pace, distance, or route mapping.
- Calorie burn estimations and VO2 max algorithms (pure heart rate zone monitoring).
- Proprietary fitness watch SDKs or ANT+ communication.
