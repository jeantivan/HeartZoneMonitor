# 22: Mid-Session Active HRM Switch Lockout

**What to build:** The safety constraint preventing users from switching or deleting the Active HRM while a workout session is actively `Recording` or `Paused`, ensuring single-sensor data integrity.

**Blocked by:** 10 (Remembered HRMs Management Screen), 14 (Session Recording State Machine Repository)

**Status:** ready-for-agent

- [ ] Evaluates the active session state whenever device management actions are invoked.
- [ ] If a session is in `Recording` or `Paused` state, disables promoting another Remembered HRM to Active HRM and displays a notice: *"Cannot switch sensor during an active session. Stop or complete the session first."*.
- [ ] Disables deleting or forgetting the Active HRM while a session is open.
- [ ] Allows full device switching and deletion when the session is `Idle` or finalized (`Completed`/`Discarded`).
- [ ] Unit and UI tests verify that device switching is locked during active sessions and unlocked otherwise.
