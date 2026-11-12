# 16: Persistent Chronometer Notification

**What to build:** An ongoing, low-importance Android system notification displaying real-time heart rate, zone name, and a native system chronometer ticking active workout duration with zero CPU overhead.

**Blocked by:** 15 (Foreground Recording Service & WakeLock)

**Status:** ready-for-agent

- [ ] Creates notification channel `recording_channel` with `IMPORTANCE_LOW` and `setOngoing(true)`.
- [ ] Title displays current BPM and Heart Zone (e.g. *"142 BPM • Zone 3 (Aerobic)"*).
- [ ] Duration rendered natively using `NotificationCompat.Builder.setUsesChronometer(true)` seeded with monotonic baseline.
- [ ] Provides interactive action buttons: `[Pause]` / `[Resume]` and `[Stop & Save]`, sending pending intents to `SessionRepository`.
- [ ] Content click intent deep-links directly into the live session screen.
- [ ] Debounces calls to `notify()`: updates only on zone transitions, heart rate shifts $\ge 2$ BPM, or at minimum every 5 seconds.
