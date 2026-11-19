# 13: Monotonic Clock Duration Engine (ADR-0002)

**What to build:** A pure monotonic interval duration module that accumulates Effective Duration and Paused Duration strictly using `SystemClock.elapsedRealtime()`, completely immune to wall-clock NTP adjustments, manual time changes, or coroutine delay drift.

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] Captures `startEpochMs` via `System.currentTimeMillis()` once at session start for calendar display and export metadata.
- [x] Tracks `activeIntervalStartRealtime` on entering `Recording`; accumulates `effectiveDurationMs += (elapsedRealtime - activeIntervalStartRealtime)` on pause.
- [x] Tracks `pauseIntervalStartRealtime` on entering `Paused`; accumulates `pausedDurationMs += (elapsedRealtime - pauseIntervalStartRealtime)` on resume.
- [x] Exposes instantaneous duration formulas:
  - $\text{Effective Duration} = \text{accumulatedEffectiveMs} + (\text{if Recording then } \text{elapsedRealtime}() - \text{activeIntervalStartRealtime} \text{ else } 0)$
  - $\text{Elapsed Duration} = \text{Effective Duration} + \text{Paused Duration}$
- [x] Unit tests verify interval accumulation accuracy across simulated state changes and simulated clock jumps.
