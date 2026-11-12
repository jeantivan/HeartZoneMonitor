# 20: BLE Disconnection Grace Period & Reconnection Backoff

**What to build:** The transient radio loss resilience mechanism during an active workout session, providing a 10-second grace period with exponential backoff before auto-pausing, and transparently resuming upon link restoration.

**Blocked by:** 14 (Session Recording State Machine Repository)

**Status:** ready-for-agent

- [ ] When the BLE connection drops during an active `Recording` session, transitions connection state to `Reconnecting` without immediately altering session state.
- [ ] Executes reconnection attempts with exponential backoff delays (0s, 2s, 5s, 10s up to 60s max).
- [ ] Initiates a 10-second grace period: if the link is re-established within 10 seconds, `Recording` continues seamlessly without state change.
- [ ] If the connection is not recovered within 10 seconds, transitions the session state to `Paused(BLE_CONNECTION_LOST)` and dispatches an alert notification.
- [ ] When the sensor successfully reconnects after auto-pausing, automatically transitions the session back to `Recording` and resumes duration accumulation.
- [ ] Automated tests via `FakeBleTransport` verify recovery under 10 seconds vs auto-pause after 10 seconds, followed by auto-resume.
